package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory JSON cache of the item_extract table (key columns only).
 * Pattern mirrors SkuDataService: full seed + delta by REV_RELEASE_DATE.
 */
@Service
public class ItemCacheService {

    private static final Logger LOG = Logger.getLogger(ItemCacheService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    // 25 key columns — covers all chatbot query patterns
    public static final String[] CACHED_COLUMNS = {
        "PART_NUMBER", "DESCRIPTION", "REV", "STATUSCODE", "LIFECYCLE_PHASE",
        "NEW_PART_CLASS", "MATERIAL_GROUP", "MATERIAL_TYPE", "PRODUCTLINE",
        "SUBCONTRACTORS", "ACTUAL_BUILD_PLANT", "PRODUCT_TYPE", "CATEGORY",
        "PM", "CAPACITY", "FAMILYNAME", "CUSTOMER_PN", "REV_RELEASE_DATE",
        "CREATE_DATE", "PRODUCTDIVISION", "PROGRAM_NAME", "MARKETING_PROGRAM",
        "GOLDEN_PART", "ECCN", "CLASSIFICATION_PDM"
    };

    private static final Set<String> CACHED_COLUMN_SET = new HashSet<>(Arrays.asList(CACHED_COLUMNS));

    @Value("${app.item-cache.file:./data/item-cache.json}")
    private String cacheFile;

    @Value("${app.item-cache.delta.days:3}")
    private int deltaDays;

    @Autowired
    @Qualifier("customDataSource")
    private DataSource dataSource;

    // In-memory: PART_NUMBER -> {col -> value}
    private volatile Map<String, Map<String, String>> records = new ConcurrentHashMap<>();
    private volatile String lastSeedAt;
    private volatile String lastDeltaAt;
    private volatile int totalRecords;

    // Column indexes: column -> value -> set of PART_NUMBERs (for instant eq/in lookups)
    private static final String[] INDEXED_COLUMNS = {
        "LIFECYCLE_PHASE", "NEW_PART_CLASS", "MATERIAL_GROUP", "MATERIAL_TYPE",
        "STATUSCODE", "PRODUCTLINE", "ACTUAL_BUILD_PLANT", "PRODUCT_TYPE",
        "CATEGORY", "PRODUCTDIVISION", "GOLDEN_PART", "CLASSIFICATION_PDM"
    };
    private final Map<String, Map<String, Set<String>>> columnIndexes = new ConcurrentHashMap<>();

    // Sorted part numbers for fast prefix search (binary search on sorted array)
    private volatile String[] sortedPartNumbers;

    // Sorted epoch indexes for date range queries
    private volatile long[] epochIndex_REV_RELEASE_DATE;
    private volatile String[] epochKeys_REV_RELEASE_DATE;
    private volatile long[] epochIndex_CREATE_DATE;
    private volatile String[] epochKeys_CREATE_DATE;

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    // ── Public API ──

    public boolean isLoaded() { return !records.isEmpty(); }
    public int size() { return records.size(); }
    public String getLastSeedAt() { return lastSeedAt; }
    public String getLastDeltaAt() { return lastDeltaAt; }
    public String[] getCachedColumns() { return CACHED_COLUMNS; }

    /** Check if a column name is in the cache. */
    public boolean hasColumn(String col) {
        return CACHED_COLUMN_SET.contains(col.toUpperCase());
    }

    /** Check if ALL columns in a list are cached. Returns the first missing one, or null if all present. */
    public String findMissingColumn(List<String> columns) {
        for (String c : columns) {
            if (!CACHED_COLUMN_SET.contains(c.toUpperCase()) && !c.toUpperCase().contains("COUNT")) {
                return c;
            }
        }
        return null;
    }

    /** Look up a single item. */
    public Map<String, String> getRecord(String partNumber) {
        if (partNumber == null) return null;
        Map<String, String> rec = records.get(partNumber.toUpperCase());
        if (rec == null) rec = records.get(partNumber);
        return rec;
    }

    /**
     * Search records matching a predicate using parallel streams. Returns up to maxResults.
     */
    public List<Map<String, String>> search(java.util.function.BiPredicate<String, Map<String, String>> predicate, int maxResults) {
        if (maxResults == Integer.MAX_VALUE) {
            // Unlimited — use parallel for speed
            return records.entrySet().parallelStream()
                .filter(e -> predicate.test(e.getKey(), e.getValue()))
                .map(Map.Entry::getValue)
                .collect(java.util.stream.Collectors.toList());
        }
        // Limited — use parallel + limit
        return records.entrySet().parallelStream()
            .filter(e -> predicate.test(e.getKey(), e.getValue()))
            .limit(maxResults)
            .map(Map.Entry::getValue)
            .collect(java.util.stream.Collectors.toList());
    }

    /** Count records matching a predicate using parallel streams. */
    public int count(java.util.function.BiPredicate<String, Map<String, String>> predicate) {
        return (int) records.entrySet().parallelStream()
            .filter(e -> predicate.test(e.getKey(), e.getValue()))
            .count();
    }

    /** Get all unique values for a column (for typeahead/filtering). */
    public Set<String> distinctValues(String column) {
        Set<String> values = new LinkedHashSet<>();
        String col = column.toUpperCase();
        for (Map<String, String> rec : records.values()) {
            String val = rec.get(col);
            if (val != null && !val.isEmpty()) values.add(val);
        }
        return values;
    }

    // ── Seed (full rebuild) ──

    public int seed() {
        LOG.info("[ITEM-CACHE] Starting full seed...");
        long t0 = System.currentTimeMillis();

        String colList = String.join(", ", CACHED_COLUMNS);
        String sql = "SELECT " + colList + " FROM item_extract";

        // Build straight into the ConcurrentHashMap that becomes `records`.
        // Building a LinkedHashMap and copying it afterwards held TWO full
        // ~1.5 GB datasets simultaneously (three counting the old map) — on
        // prod that transient was a measurable slice of the 6 GB heap, and
        // locally it is why seeding OOMed at -Xmx2g.
        Map<String, Map<String, String>> newRecords = new ConcurrentHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(300); // 5 min for full scan
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> rec = new LinkedHashMap<>();
                    String partNumber = null;
                    for (String col : CACHED_COLUMNS) {
                        String val = rs.getString(col);
                        if (val != null && !val.isEmpty()) {
                            rec.put(col, val);
                            // Pre-compute epoch for date columns → enables instant date comparisons
                            if ("REV_RELEASE_DATE".equals(col) || "CREATE_DATE".equals(col)) {
                                long epoch = parseDateToEpoch(val);
                                if (epoch > 0) rec.put("_" + col + "_EPOCH", String.valueOf(epoch));
                            }
                        }
                        if ("PART_NUMBER".equals(col)) partNumber = val;
                    }
                    if (partNumber != null) {
                        newRecords.put(partNumber, rec);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[ITEM-CACHE] Seed query failed: " + e.getMessage());
            throw new RuntimeException("Seed failed: " + e.getMessage(), e);
        }

        records = newRecords;
        totalRecords = records.size();
        lastSeedAt = java.time.Instant.now().toString();
        lastDeltaAt = null;

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("[ITEM-CACHE] Seed complete: " + totalRecords + " records in " + (elapsed / 1000) + "s");

        buildIndexes();
        saveToDisk();
        return totalRecords;
    }

    // ── Delta (incremental update by REV_RELEASE_DATE) ──

    /** Daily auto-delta at 2:30 AM */
    @Scheduled(cron = "0 30 2 * * *")
    public void scheduledDelta() {
        if (records.isEmpty()) {
            LOG.info("[ITEM-CACHE] Skipping scheduled delta — cache not seeded yet");
            return;
        }
        LOG.info("[ITEM-CACHE] Scheduled delta starting...");
        try {
            int count = delta();
            LOG.info("[ITEM-CACHE] Scheduled delta complete: " + count + " records updated");
        } catch (Exception e) {
            LOG.warning("[ITEM-CACHE] Scheduled delta failed: " + e.getMessage());
        }
    }

    public int delta() {
        if (records.isEmpty()) {
            LOG.info("[ITEM-CACHE] Cache empty — running full seed instead of delta");
            return seed();
        }

        LOG.info("[ITEM-CACHE] Starting delta (last " + deltaDays + " days)...");
        long t0 = System.currentTimeMillis();

        String colList = String.join(", ", CACHED_COLUMNS);
        String sql = "SELECT " + colList + " FROM item_extract " +
            "WHERE REV_RELEASE_DATE IS NOT NULL " +
            "AND TO_DATE(SUBSTR(REV_RELEASE_DATE,1,11), 'DD-MON-YYYY') >= SYSDATE - " + deltaDays;

        int updated = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(120);
            ps.setFetchSize(2000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> rec = new LinkedHashMap<>();
                    String partNumber = null;
                    for (String col : CACHED_COLUMNS) {
                        String val = rs.getString(col);
                        if (val != null && !val.isEmpty()) {
                            rec.put(col, val);
                            if ("REV_RELEASE_DATE".equals(col) || "CREATE_DATE".equals(col)) {
                                long epoch = parseDateToEpoch(val);
                                if (epoch > 0) rec.put("_" + col + "_EPOCH", String.valueOf(epoch));
                            }
                        }
                        if ("PART_NUMBER".equals(col)) partNumber = val;
                    }
                    if (partNumber != null) {
                        records.put(partNumber, rec);
                        updated++;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[ITEM-CACHE] Delta query failed: " + e.getMessage());
            throw new RuntimeException("Delta failed: " + e.getMessage(), e);
        }

        totalRecords = records.size();
        lastDeltaAt = java.time.Instant.now().toString();

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("[ITEM-CACHE] Delta complete: " + updated + " records updated/added, total " + totalRecords + " in " + (elapsed / 1000) + "s");

        buildIndexes();
        saveToDisk();
        return updated;
    }

    // ── Persistence ──

    private void loadFromDisk() {
        File f = new File(cacheFile);
        if (!f.exists()) {
            LOG.info("[ITEM-CACHE] No cache file at " + f.getAbsolutePath() + " — run Seed to generate");
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            CacheEnvelope env = mapper.readValue(f, CacheEnvelope.class);
            if (env.records != null) {
                records = new ConcurrentHashMap<>(env.records);
                totalRecords = records.size();
                lastSeedAt = env.lastSeedAt;
                lastDeltaAt = env.lastDeltaAt;
                long elapsed = System.currentTimeMillis() - t0;
                LOG.info("[ITEM-CACHE] Loaded " + totalRecords + " records from disk ("
                    + String.format("%.1f", f.length() / (1024.0 * 1024.0)) + " MB, " + elapsed + " ms)");
                buildIndexes();
            }
        } catch (Exception e) {
            LOG.warning("[ITEM-CACHE] Failed to load cache: " + e.getMessage());
        }
    }

    private void saveToDisk() {
        try {
            File f = new File(cacheFile);
            f.getParentFile().mkdirs();
            CacheEnvelope env = new CacheEnvelope();
            env.records = records;
            env.totalRecords = totalRecords;
            env.lastSeedAt = lastSeedAt;
            env.lastDeltaAt = lastDeltaAt;
            env.columns = Arrays.asList(CACHED_COLUMNS);
            long t0 = System.currentTimeMillis();
            mapper.writeValue(f, env);
            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("[ITEM-CACHE] Saved " + totalRecords + " records to disk ("
                + String.format("%.1f", f.length() / (1024.0 * 1024.0)) + " MB, " + elapsed + " ms)");
        } catch (Exception e) {
            LOG.warning("[ITEM-CACHE] Failed to save cache: " + e.getMessage());
        }
    }

    /** Return sample records for debugging date formats */
    public List<Map<String, String>> getSampleDates(int max) {
        List<Map<String, String>> samples = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : records.entrySet()) {
            Map<String, String> rec = entry.getValue();
            String cd = rec.get("CREATE_DATE");
            String rd = rec.get("REV_RELEASE_DATE");
            if (cd != null || rd != null) {
                Map<String, String> sample = new LinkedHashMap<>();
                sample.put("PART_NUMBER", entry.getKey());
                sample.put("CREATE_DATE", cd != null ? cd : "");
                sample.put("_CREATE_DATE_EPOCH", rec.getOrDefault("_CREATE_DATE_EPOCH", ""));
                sample.put("REV_RELEASE_DATE", rd != null ? rd : "");
                sample.put("_REV_RELEASE_DATE_EPOCH", rec.getOrDefault("_REV_RELEASE_DATE_EPOCH", ""));
                samples.add(sample);
                if (samples.size() >= max) break;
            }
        }
        return samples;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("loaded", isLoaded());
        status.put("totalRecords", totalRecords);
        status.put("lastSeedAt", lastSeedAt);
        status.put("lastDeltaAt", lastDeltaAt);
        status.put("columns", CACHED_COLUMNS.length);
        File f = new File(cacheFile);
        status.put("fileSizeMB", f.exists() ? String.format("%.1f", f.length() / (1024.0 * 1024.0)) : "0");
        return status;
    }

    /** Build column indexes for instant lookups. Called after seed, delta, and load. */
    private void buildIndexes() {
        long t0 = System.currentTimeMillis();
        columnIndexes.clear();
        for (String col : INDEXED_COLUMNS) {
            Map<String, Set<String>> idx = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : records.entrySet()) {
                String val = entry.getValue().get(col);
                if (val != null && !val.isEmpty()) {
                    idx.computeIfAbsent(val.toUpperCase(), k -> new HashSet<>()).add(entry.getKey());
                }
            }
            columnIndexes.put(col, idx);
        }

        // Build sorted part number array for prefix search
        sortedPartNumbers = records.keySet().toArray(new String[0]);
        Arrays.sort(sortedPartNumbers, String.CASE_INSENSITIVE_ORDER);

        // Build sorted epoch indexes for both date columns
        String[] dateColumns = {"REV_RELEASE_DATE", "CREATE_DATE"};
        for (String dateCol : dateColumns) {
            List<long[]> epochPairs = new ArrayList<>();
            List<String> epochPartNumbers = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> entry : records.entrySet()) {
                String epochStr = entry.getValue().get("_" + dateCol + "_EPOCH");
                if (epochStr != null && !epochStr.isEmpty()) {
                    try {
                        long epoch = Long.parseLong(epochStr);
                        epochPairs.add(new long[]{epoch, epochPartNumbers.size()});
                        epochPartNumbers.add(entry.getKey());
                    } catch (Exception ignored) {}
                }
            }
            epochPairs.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] epochs = new long[epochPairs.size()];
            String[] keys = new String[epochPairs.size()];
            for (int i = 0; i < epochPairs.size(); i++) {
                epochs[i] = epochPairs.get(i)[0];
                keys[i] = epochPartNumbers.get((int) epochPairs.get(i)[1]);
            }
            if ("REV_RELEASE_DATE".equals(dateCol)) {
                epochIndex_REV_RELEASE_DATE = epochs;
                epochKeys_REV_RELEASE_DATE = keys;
            } else {
                epochIndex_CREATE_DATE = epochs;
                epochKeys_CREATE_DATE = keys;
            }
            LOG.info("[ITEM-CACHE] Date index " + dateCol + ": " + epochPairs.size() + " records");
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("[ITEM-CACHE] Built indexes: " + INDEXED_COLUMNS.length + " column indexes + 2 date indexes in " + elapsed + "ms");
    }

    /**
     * Fast indexed lookup: get part numbers where column = value.
     * Returns null if column is not indexed (caller should fall back to scan).
     */
    public Set<String> indexedLookup(String column, String value) {
        Map<String, Set<String>> idx = columnIndexes.get(column.toUpperCase());
        if (idx == null) return null;
        return idx.getOrDefault(value.toUpperCase(), Collections.emptySet());
    }

    /**
     * Fast indexed lookup: get part numbers where column IN (values).
     */
    public Set<String> indexedLookupIn(String column, List<String> values) {
        Map<String, Set<String>> idx = columnIndexes.get(column.toUpperCase());
        if (idx == null) return null;
        Set<String> result = new HashSet<>();
        for (String v : values) {
            Set<String> matches = idx.get(v.toUpperCase());
            if (matches != null) result.addAll(matches);
        }
        return result;
    }

    /**
     * Fast date range lookup using binary search on sorted epoch index.
     * Returns part numbers with the specified date column >= minEpoch.
     */
    public Set<String> dateRangeLookup(String dateColumn, long minEpoch) {
        long[] epochs;
        String[] keys;
        if ("CREATE_DATE".equals(dateColumn)) {
            epochs = epochIndex_CREATE_DATE;
            keys = epochKeys_CREATE_DATE;
        } else {
            epochs = epochIndex_REV_RELEASE_DATE;
            keys = epochKeys_REV_RELEASE_DATE;
        }
        if (epochs == null) return null;
        int pos = Arrays.binarySearch(epochs, minEpoch);
        if (pos < 0) pos = -(pos + 1);
        Set<String> result = new HashSet<>();
        for (int i = pos; i < epochs.length; i++) {
            result.add(keys[i]);
        }
        return result;
    }

    /**
     * Fast prefix search on PART_NUMBER using binary search on sorted array.
     * e.g. prefix="SD" returns all part numbers starting with "SD".
     */
    public Set<String> prefixLookup(String prefix) {
        if (sortedPartNumbers == null || prefix == null) return null;
        String upperPrefix = prefix.toUpperCase();
        // Binary search for the first match
        int pos = Arrays.binarySearch(sortedPartNumbers, upperPrefix, String.CASE_INSENSITIVE_ORDER);
        if (pos < 0) pos = -(pos + 1);
        Set<String> result = new HashSet<>();
        for (int i = pos; i < sortedPartNumbers.length; i++) {
            if (sortedPartNumbers[i].toUpperCase().startsWith(upperPrefix)) {
                result.add(sortedPartNumbers[i]);
            } else {
                break; // sorted, so no more matches after this
            }
        }
        // Also check backwards (case-insensitive sort edge cases)
        for (int i = pos - 1; i >= 0; i--) {
            if (sortedPartNumbers[i].toUpperCase().startsWith(upperPrefix)) {
                result.add(sortedPartNumbers[i]);
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * Fast indexed lookup: get part numbers where column value CONTAINS the search string (case-insensitive).
     * Scans index values (not records) so it's fast.
     */
    public Set<String> indexedLookupLike(String column, String search) {
        Map<String, Set<String>> idx = columnIndexes.get(column.toUpperCase());
        if (idx == null) return null;
        String upperSearch = search.toUpperCase();
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : idx.entrySet()) {
            if (entry.getKey().contains(upperSearch)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /** Return all part numbers (for neq/notin operations). */
    public Set<String> allPartNumbers() { return records.keySet(); }

    /** Check if a column is indexed. */
    public boolean isIndexed(String column) {
        return columnIndexes.containsKey(column.toUpperCase());
    }

    /** Parse various date formats to epoch millis. Returns 0 if unparseable. */
    public static long parseDateToEpoch(String val) {
        if (val == null || val.isEmpty()) return 0;
        // Try multiple formats
        // Order matters: check string pattern to pick the right format family
        // YYYY-MM-DD starts with 4 digits, DD-MON-YYYY starts with 2 digits + letter, MM-DD-YYYY starts with 2 digits + digit
        if (val.length() >= 10 && Character.isDigit(val.charAt(0))) {
            boolean startsWithYear = val.charAt(4) == '-'; // yyyy-...
            boolean hasMonthName = val.length() >= 6 && Character.isLetter(val.charAt(3)); // dd-MON-...

            String[][] formatGroups;
            if (startsWithYear) {
                formatGroups = new String[][]{
                    {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}
                };
            } else if (hasMonthName) {
                formatGroups = new String[][]{
                    {"dd-MMM-yyyy hh:mm:ss a", "dd-MMM-yyyy HH:mm:ss", "dd-MMM-yyyy"}
                };
            } else {
                formatGroups = new String[][]{
                    {"MM-dd-yyyy hh:mm:ss a", "MM-dd-yyyy HH:mm:ss", "MM-dd-yyyy"}
                };
            }

            for (String[] group : formatGroups) {
                for (String fmt : group) {
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.ENGLISH);
                        sdf.setLenient(false);
                        return sdf.parse(val).getTime();
                    } catch (Exception ignored) {}
                }
            }
        }
        // Last resort: try all formats
        String[] formats = {"dd-MMM-yyyy hh:mm:ss a", "MM-dd-yyyy hh:mm:ss a", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd-MMM-yyyy", "MM-dd-yyyy"};
        for (String fmt : formats) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.ENGLISH);
                sdf.setLenient(false);
                return sdf.parse(val).getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** Parse a filter date value to epoch. */
    public static long parseFilterDateToEpoch(String val) {
        return parseDateToEpoch(val);
    }

    static class CacheEnvelope {
        public Map<String, Map<String, String>> records;
        public int totalRecords;
        public String lastSeedAt;
        public String lastDeltaAt;
        public List<String> columns;
    }
}
