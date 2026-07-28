package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.ChangeRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChangeQueryService {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(ChangeQueryService.class.getName());

    private static final Pattern DETAIL_PATTERN =
            Pattern.compile("<([^>]+)>was<([^>]*)>is<([^>]*)>");

    private static final Pattern DETAIL_PATTERN_TRUNCATED =
            Pattern.compile("<([^>]+)>was<(.*)>is<(.*)");

    private DataSource dataSource;

    @Value("${app.max-lookback-days:7}")
    private int maxLookbackDays = 7;

    @Value("${app.max-results:500}")
    private int maxResults = 500;

    @Value("${app.cache.inactive.minutes:10}")
    private int cacheInactiveMinutes = 10;

    @Value("${app.cache.file:./cache/field-changes-cache.ser}")
    private String cacheFilePath;

    // Incremental cache: full 7-day dataset built up from initial load + deltas
    private volatile List<ChangeRecord> cachedRecords = null;
    private volatile Timestamp lastQueryTimestamp = null;  // watermark for delta queries
    private volatile long lastActivityTimestamp = 0;
    private volatile long lastSuccessfulRefresh = 0;       // epoch ms of last successful DB query
    private volatile boolean dbOffline = false;             // true if last DB attempt failed

    // Thundering herd lock: only one DB query at a time
    private final ReentrantLock queryLock = new ReentrantLock();

    @Autowired(required = false)
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        loadCacheFromDisk();
        // Log timezone info for debugging timestamp issues
        logger.info("[TIMEZONE] JVM default timezone: " + TimeZone.getDefault().getID() +
                    " (offset: " + TimeZone.getDefault().getRawOffset() / 3600000 + "h, DST: " +
                    TimeZone.getDefault().useDaylightTime() + ")");
        logger.info("[TIMEZONE] System.currentTimeMillis() = " + System.currentTimeMillis() +
                    " = " + new Timestamp(System.currentTimeMillis()));
        // Query Oracle session timezone
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT DBTIMEZONE, SESSIONTIMEZONE, SYSTIMESTAMP, SYSDATE FROM dual")) {
                if (rs.next()) {
                    logger.info("[TIMEZONE] Oracle DBTIMEZONE: " + rs.getString(1) +
                                " | SESSIONTIMEZONE: " + rs.getString(2) +
                                " | SYSTIMESTAMP: " + rs.getString(3) +
                                " | SYSDATE: " + rs.getString(4));
                }
            } catch (Exception e) {
                logger.warning("[TIMEZONE] Failed to query Oracle timezone: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCacheFromDisk() {
        File f = new File(cacheFilePath);
        if (!f.exists()) {
            logger.info("[CACHE] No persistent cache file found at " + cacheFilePath);
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            List<ChangeRecord> records = (List<ChangeRecord>) ois.readObject();
            long savedTimestamp = ois.readLong();
            long savedRefresh = ois.readLong();

            // Only use if not too old (within 7 days)
            long age = System.currentTimeMillis() - savedRefresh;
            if (age > maxLookbackDays * 24L * 60 * 60 * 1000) {
                logger.info("[CACHE] Persistent cache too old (" + (age / 3600000) + "h), discarding");
                return;
            }

            cachedRecords = records;
            lastQueryTimestamp = new Timestamp(savedTimestamp);
            lastSuccessfulRefresh = savedRefresh;
            logger.info("[CACHE] Loaded " + records.size() + " records from disk (age: " + (age / 60000) + " min)");
        } catch (Exception e) {
            logger.warning("[CACHE] Failed to load from disk: " + e.getMessage());
        }
    }

    private void saveCacheToDisk() {
        try {
            File f = new File(cacheFilePath);
            f.getParentFile().mkdirs();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                oos.writeObject(cachedRecords);
                oos.writeLong(lastQueryTimestamp != null ? lastQueryTimestamp.getTime() : System.currentTimeMillis());
                oos.writeLong(lastSuccessfulRefresh);
            }
            logger.fine("[CACHE] Saved " + cachedRecords.size() + " records to disk");
        } catch (Exception e) {
            logger.warning("[CACHE] Failed to save to disk: " + e.getMessage());
        }
    }

    /**
     * Background delta refresh: runs every 30 seconds.
     * Only queries Oracle if users have been active recently.
     * Pulls only NEW records since the last watermark -- typically 0-5 rows.
     */
    @Scheduled(fixedDelayString = "${app.cache.refresh.interval.ms:30000}")
    public void refreshCacheIfNeeded() {
        if (dataSource == null) return;

        long now = System.currentTimeMillis();
        long inactiveMs = cacheInactiveMinutes * 60_000L;

        // Skip if no users active recently
        if (lastActivityTimestamp == 0 || (now - lastActivityTimestamp) > inactiveMs) {
            return;
        }

        // Skip if cache not initialized yet (first user request will do the full load)
        if (cachedRecords == null || lastQueryTimestamp == null) {
            return;
        }

        // Delta refresh
        if (queryLock.tryLock()) {
            try {
                List<ChangeRecord> delta = queryDeltaFromDb(lastQueryTimestamp);
                if (!delta.isEmpty()) {
                    // Deduplicate: build set of existing record keys
                    Set<String> existingKeys = new HashSet<>();
                    for (ChangeRecord r : cachedRecords) {
                        existingKeys.add(recordKey(r));
                    }
                    int added = 0;
                    List<ChangeRecord> merged = new ArrayList<>(cachedRecords);
                    for (ChangeRecord r : delta) {
                        if (existingKeys.add(recordKey(r))) {
                            merged.add(r);
                            added++;
                        }
                    }
                    logger.info("Delta refresh: " + delta.size() + " queried, " + added + " new (deduped)");
                    long cutoff = System.currentTimeMillis() - (maxLookbackDays * 24L * 60 * 60 * 1000);
                    merged.removeIf(r -> r.getTimestamp() != null && r.getTimestamp().getTime() < cutoff);
                    merged.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
                    cachedRecords = merged;
                } else {
                    logger.fine("Delta refresh: no new records");
                }
                lastQueryTimestamp = new Timestamp(System.currentTimeMillis());
                lastSuccessfulRefresh = System.currentTimeMillis();
                dbOffline = false;
                saveCacheToDisk();
            } catch (Exception e) {
                logger.warning("Delta refresh failed (DB may be down): " + e.getMessage());
                dbOffline = true;
            } finally {
                queryLock.unlock();
            }
        }
    }

    /**
     * Get all 7-day records from cache.
     * First call does a full load; subsequent calls return cached data
     * (kept fresh by background delta refreshes).
     */
    private List<ChangeRecord> getAllRecords() {
        lastActivityTimestamp = System.currentTimeMillis();

        // Fast path: cache exists
        if (cachedRecords != null) {
            logger.info("Cache hit (" + cachedRecords.size() + " records)");
            return cachedRecords;
        }

        // First-time load: full 7-day query
        queryLock.lock();
        try {
            // Another thread may have populated cache while we waited
            if (cachedRecords != null) {
                logger.info("Cache hit after lock wait (" + cachedRecords.size() + " records)");
                return cachedRecords;
            }

            logger.info("First load - full 7-day query...");
            try {
                cachedRecords = queryFullFromDb();
                lastQueryTimestamp = new Timestamp(System.currentTimeMillis());
                lastSuccessfulRefresh = System.currentTimeMillis();
                dbOffline = false;
                logger.info("Full load complete: " + cachedRecords.size() + " records cached");
                saveCacheToDisk();
            } catch (Exception e) {
                logger.warning("First load failed (DB may be down): " + e.getMessage());
                dbOffline = true;
                cachedRecords = new ArrayList<>();
                lastSuccessfulRefresh = 0;
            }
            return cachedRecords;
        } finally {
            queryLock.unlock();
        }
    }

    /**
     * Full 7-day query. Only runs once (on first request).
     */
    private List<ChangeRecord> queryFullFromDb() {
        String sql = "WITH changed_items AS (\n" +
                "    SELECT ch.item,\n" +
                "           DBMS_LOB.SUBSTR(ch.details, 4000, 1) AS details,\n" +
                "           ch.\"TIMESTAMP\" AS change_ts,\n" +
                "           ch.user_name,\n" +
                "           ch.REVNUMBER\n" +
                "    FROM item_history ch\n" +
                "    WHERE ch.\"TIMESTAMP\" >= SYSDATE - " + maxLookbackDays + "\n" +
                "      AND ch.details LIKE '%>was<%'\n" +
                ")\n" +
                "SELECT c.ITEM_NUMBER,\n" +
                "       ci.details,\n" +
                "       ci.change_ts,\n" +
                "       ci.user_name,\n" +
                "       ci.REVNUMBER\n" +
                "FROM changed_items ci\n" +
                "JOIN item c ON c.id = ci.item\n" +
                "ORDER BY ci.change_ts DESC";

        return executeQuery(sql, null);
    }

    /**
     * Delta query: only records newer than the watermark.
     * Typically returns 0-5 rows -- very fast.
     */
    private List<ChangeRecord> queryDeltaFromDb(Timestamp since) {
        String sql = "WITH changed_items AS (\n" +
                "    SELECT ch.item,\n" +
                "           DBMS_LOB.SUBSTR(ch.details, 4000, 1) AS details,\n" +
                "           ch.\"TIMESTAMP\" AS change_ts,\n" +
                "           ch.user_name,\n" +
                "           ch.REVNUMBER\n" +
                "    FROM item_history ch\n" +
                "    WHERE ch.\"TIMESTAMP\" > ?\n" +
                "      AND ch.details LIKE '%>was<%'\n" +
                ")\n" +
                "SELECT c.ITEM_NUMBER,\n" +
                "       ci.details,\n" +
                "       ci.change_ts,\n" +
                "       ci.user_name,\n" +
                "       ci.REVNUMBER\n" +
                "FROM changed_items ci\n" +
                "JOIN item c ON c.id = ci.item\n" +
                "ORDER BY ci.change_ts DESC";

        return executeQuery(sql, since);
    }

    // Oracle item_history.TIMESTAMP stores in UTC (DBTIMEZONE = +00:00).
    // Must use UTC Calendar so JDBC doesn't misinterpret as JVM local time.
    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private List<ChangeRecord> executeQuery(String sql, Timestamp sinceParam) {
        List<ChangeRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setQueryTimeout(30);
            if (sinceParam != null) {
                ps.setTimestamp(1, sinceParam, utcCal());
            }

            try (ResultSet rs = ps.executeQuery()) {
                boolean firstRow = true;
                while (rs.next()) {
                    String itemNumber = rs.getString("ITEM_NUMBER");
                    String details = rs.getString("DETAILS");
                    Timestamp timestamp = rs.getTimestamp("CHANGE_TS", utcCal());
                    String userName = rs.getString("USER_NAME");
                    String revNumber = trimField(rs.getString("REVNUMBER"));

                    // Debug first row's timestamp handling
                    if (firstRow && timestamp != null) {
                        firstRow = false;
                        long nowMs = System.currentTimeMillis();
                        long tsMs = timestamp.getTime();
                        long ageMs = nowMs - tsMs;
                        logger.info("[TS-DEBUG] item=" + itemNumber +
                                    " | rs.getTimestamp(UTC): " + timestamp +
                                    " | epoch: " + tsMs +
                                    " | System.currentTimeMillis(): " + nowMs +
                                    " | ageMs: " + ageMs + " (" + (ageMs / 60000) + " min)");
                    }

                    ChangeRecord rec = parseDetails(itemNumber, details, timestamp, userName, revNumber);
                    if (rec != null) {
                        records.add(rec);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database query failed: " + e.getMessage(), e);
        }

        return records;
    }

    public ChangeRecord parseDetails(String itemNumber, String details,
                                     Timestamp timestamp, String userName, String revNumber) {
        if (details == null) return null;

        Matcher m = DETAIL_PATTERN.matcher(details);
        boolean found = m.find();
        if (!found) {
            m = DETAIL_PATTERN_TRUNCATED.matcher(details);
            found = m.find();
        }
        if (found) {
            String oldVal = m.group(2);
            String newVal = m.group(3);
            if (newVal.endsWith(">")) {
                newVal = newVal.substring(0, newVal.length() - 1);
            }
            return new ChangeRecord(
                    itemNumber,
                    m.group(1),
                    oldVal.isEmpty() ? "(blank)" : oldVal,
                    newVal.isEmpty() ? "(blank)" : newVal,
                    timestamp,
                    userName,
                    revNumber
            );
        }
        return null;
    }

    public List<ChangeRecord> filterByField(List<ChangeRecord> records, String fieldFilter) {
        if (fieldFilter == null || fieldFilter.trim().isEmpty()) return records;

        String[] tokens = fieldFilter.split(",");
        List<String> patterns = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        if (patterns.isEmpty()) return records;

        return records.stream()
                .filter(r -> {
                    String fn = r.getFieldName().toLowerCase();
                    for (String p : patterns) {
                        if (fn.contains(p)) return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    public List<ChangeRecord> filterByOldContains(List<ChangeRecord> records, String oldContains) {
        return filterByValueContains(records, oldContains, true);
    }

    public List<ChangeRecord> filterByNewContains(List<ChangeRecord> records, String newContains) {
        return filterByValueContains(records, newContains, false);
    }

    private List<ChangeRecord> filterByValueContains(List<ChangeRecord> records,
                                                      String filter, boolean useOld) {
        if (filter == null || filter.trim().isEmpty()) return records;

        String[] tokens = filter.split(",");
        List<String> patterns = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        if (patterns.isEmpty()) return records;

        return records.stream()
                .filter(r -> {
                    String val = useOld
                            ? r.getOldValue().toLowerCase()
                            : r.getNewValue().toLowerCase();
                    for (String p : patterns) {
                        if (val.contains(p)) return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /**
     * Excludes records where the old value already contained the new-contains tokens.
     * This means: only show records where the value was NEWLY ADDED.
     */
    private List<ChangeRecord> filterExcludeOldHasNew(List<ChangeRecord> records, String newContains) {
        String[] tokens = newContains.split(",");
        List<String> patterns = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim().toLowerCase();
            if (!trimmed.isEmpty()) patterns.add(trimmed);
        }
        if (patterns.isEmpty()) return records;

        return records.stream()
                .filter(r -> {
                    String oldVal = r.getOldValue().toLowerCase();
                    String newVal = r.getNewValue().toLowerCase();
                    // Keep the record if at least one token is IN the new value AND NOT in the old value
                    for (String p : patterns) {
                        if (newVal.contains(p) && !oldVal.contains(p)) return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    public List<ChangeRecord> filterNoNetChange(List<ChangeRecord> records) {
        Map<String, List<ChangeRecord>> groups = new LinkedHashMap<>();
        for (ChangeRecord rec : records) {
            String key = rec.getItemNumber() + "\t" + rec.getFieldName();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        }

        List<ChangeRecord> filtered = new ArrayList<>();
        for (List<ChangeRecord> group : groups.values()) {
            ChangeRecord earliest = group.get(0);
            ChangeRecord latest = group.get(0);
            for (ChangeRecord rec : group) {
                if (rec.getTimestamp().before(earliest.getTimestamp())) earliest = rec;
                if (rec.getTimestamp().after(latest.getTimestamp())) latest = rec;
            }
            if (!earliest.getOldValue().equals(latest.getNewValue())) {
                filtered.addAll(group);
            }
        }
        return filtered;
    }

    public List<ChangeRecord> filterByDays(List<ChangeRecord> records, int days) {
        int safeDays = Math.max(1, Math.min(days, maxLookbackDays));
        if (safeDays >= maxLookbackDays) return records;
        long cutoff = System.currentTimeMillis() - (safeDays * 24L * 60 * 60 * 1000);
        return records.stream()
                .filter(r -> r.getTimestamp() != null && r.getTimestamp().getTime() >= cutoff)
                .collect(Collectors.toList());
    }

    public List<ChangeRecord> filterByItem(List<ChangeRecord> records, String itemFilter) {
        if (itemFilter == null || itemFilter.trim().isEmpty()) return records;
        String pattern = itemFilter.trim().toLowerCase();
        if (pattern.contains("*")) {
            String regex = ("\\Q" + pattern + "\\E").replace("*", "\\E.*\\Q");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
            return records.stream()
                    .filter(r -> p.matcher(r.getItemNumber()).matches())
                    .collect(Collectors.toList());
        }
        return records.stream()
                .filter(r -> r.getItemNumber().toLowerCase().contains(pattern))
                .collect(Collectors.toList());
    }

    public List<ChangeRecord> filterByUser(List<ChangeRecord> records, String userFilter) {
        if (userFilter == null || userFilter.trim().isEmpty()) return records;
        String pattern = userFilter.trim().toLowerCase();
        return records.stream()
                .filter(r -> r.getUserName().toLowerCase().contains(pattern))
                .collect(Collectors.toList());
    }

    public SearchResult search(String fieldFilter, String itemFilter, String userFilter,
                                int days, String oldContains, String newContains,
                                boolean netFilter) {
        long startTime = System.currentTimeMillis();

        List<ChangeRecord> records = new ArrayList<>(getAllRecords());

        records = filterByDays(records, days);
        records = filterByItem(records, itemFilter);
        records = filterByUser(records, userFilter);
        records = filterByField(records, fieldFilter);
        records = filterByOldContains(records, oldContains);
        records = filterByNewContains(records, newContains);

        // If newContains is set but oldContains is empty, exclude records where
        // old value also had the same tokens (show only "newly added" values)
        if ((newContains != null && !newContains.trim().isEmpty()) &&
            (oldContains == null || oldContains.trim().isEmpty())) {
            records = filterExcludeOldHasNew(records, newContains);
        }

        if (netFilter) {
            records = filterNoNetChange(records);
        }

        int totalCount = records.size();
        long uniqueItems = records.stream()
                .map(ChangeRecord::getItemNumber)
                .distinct().count();

        boolean truncated = false;

        long queryTimeMs = System.currentTimeMillis() - startTime;

        String dataAsOf = null;
        if (dbOffline && lastSuccessfulRefresh > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM d, yyyy HH:mm:ss");
            dataAsOf = sdf.format(new java.util.Date(lastSuccessfulRefresh));
        }

        return new SearchResult(records, totalCount, (int) uniqueItems,
                queryTimeMs, truncated, dbOffline, dataAsOf);
    }

    private String recordKey(ChangeRecord r) {
        return r.getItemNumber() + "|" + r.getFieldName() + "|" +
                (r.getTimestamp() != null ? r.getTimestamp().getTime() : "0") + "|" +
                r.getOldValue() + "|" + r.getNewValue();
    }

    private String trimField(String val) {
        if (val == null) return "";
        return val.trim();
    }

    public static class SearchResult {
        private final List<ChangeRecord> results;
        private final int totalCount;
        private final int uniqueItems;
        private final long queryTimeMs;
        private final boolean truncated;
        private final boolean dbOffline;
        private final String dataAsOf;

        public SearchResult(List<ChangeRecord> results, int totalCount, int uniqueItems,
                            long queryTimeMs, boolean truncated, boolean dbOffline, String dataAsOf) {
            this.results = results;
            this.totalCount = totalCount;
            this.uniqueItems = uniqueItems;
            this.queryTimeMs = queryTimeMs;
            this.truncated = truncated;
            this.dbOffline = dbOffline;
            this.dataAsOf = dataAsOf;
        }

        public List<ChangeRecord> getResults() { return results; }
        public int getTotalCount() { return totalCount; }
        public int getUniqueItems() { return uniqueItems; }
        public long getQueryTimeMs() { return queryTimeMs; }
        public boolean isTruncated() { return truncated; }
        public boolean isDbOffline() { return dbOffline; }
        public String getDataAsOf() { return dataAsOf; }
    }
}
