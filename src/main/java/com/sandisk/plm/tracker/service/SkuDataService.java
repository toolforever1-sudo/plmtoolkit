package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sandisk.plm.tracker.model.SkuRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for managing the "All SKUs" JSON data cache.
 *
 * <p>Architecture: seed-once + daily-delta
 * <ul>
 *   <li><b>Seed</b> – full extract of ~48K SKU items; runs once (or weekly reseed).</li>
 *   <li><b>Delta</b> – incremental extract of items released in the last N days; runs daily or on demand.</li>
 * </ul>
 *
 * <p><b>Performance optimisation</b>: instead of 30+ correlated scalar sub-queries per row
 * (which would take 2–10 min for 48K rows), we:
 * <ol>
 *   <li>Pre-load LISTENTRY and NODETABLE lookup maps into memory (~100K entries, &lt; 1 s)</li>
 *   <li>Run a flat 4-table JOIN returning raw IDs (~48K rows, &lt; 5 s)</li>
 *   <li>Batch-load AGILE_FLEX for all target items (~500K rows, &lt; 10 s)</li>
 *   <li>Resolve every ID → display text in Java (&lt; 1 s)</li>
 * </ol>
 * Total seed time: ~15–30 s.  Delta time: &lt; 2 s.
 *
 * <p>Column mapping:
 * <pre>
 *   Lifecycle Phase       = REV.RELEASE_TYPE  → NODETABLE (parent 1514)
 *   Single-value lists    = PAGE_TWO/THREE.LISTxx  → LISTENTRY
 *   Multi-value lists     = PAGE_TWO/THREE.MULTILISTxx  → comma-delimited LISTENTRY IDs
 *   Extended attributes   = AGILE_FLEX (ID, ATTID, CLASS=10000)  → NUMBER1 → LISTENTRY
 *   P/M "F - Family"      = ITEM.CATEGORY = 200001 (excluded)
 * </pre>
 */
@Service
public class SkuDataService {

    private static final Logger LOG = Logger.getLogger(SkuDataService.class.getName());

    // --- Agile PLM constants ---
    private static final long SKU_SUBCLASS_ID  = 251736330L;
    private static final long ITEMS_CLASS_ID   = 10000L;
    private static final long F_FAMILY_CATEGORY = 200001L;   // P/M = "F - Family"
    private static final long LC_PHASE_PARENT  = 1514L;      // NODETABLE parent for lifecycle phases

    /** AGILE_FLEX ATTIDs for extended attributes */
    private static final long ATTID_AS_SOLD_CAP    = 251735792L;
    private static final long ATTID_BIZ_SEG        = 251751610L;
    private static final long ATTID_PROD_SEG       = 251747312L;
    private static final long ATTID_PROD_SUBSEG    = 251753716L;
    private static final long ATTID_IFACE_TYPE     = 251747308L;
    private static final long ATTID_FORM_FACTOR    = 251747310L;
    private static final long ATTID_PROD_BRAND     = 251747314L;
    private static final long ATTID_BRAND          = 251752458L;
    private static final long ATTID_PARTNERSHIP    = 251752463L;
    private static final long ATTID_SWIMLANE       = 251752461L;
    private static final long ATTID_STAMP_SER      = 251752319L;
    private static final long ATTID_RETAILER       = 251753719L;
    private static final long ATTID_ITEM_CLASS     = 251747317L;

    private static final long[] ALL_FLEX_ATTIDS = {
        ATTID_AS_SOLD_CAP, ATTID_BIZ_SEG, ATTID_PROD_SEG, ATTID_PROD_SUBSEG,
        ATTID_IFACE_TYPE, ATTID_FORM_FACTOR, ATTID_PROD_BRAND, ATTID_BRAND,
        ATTID_PARTNERSHIP, ATTID_SWIMLANE, ATTID_STAMP_SER, ATTID_RETAILER, ATTID_ITEM_CLASS
    };

    // --- SQL: flat join, no scalar subqueries ---
    private static final String BASE_SELECT =
        "SELECT " +
        "  i.ID, i.ITEM_NUMBER, i.DESCRIPTION, i.CATEGORY, i.PRODUCT_LINES, " +
        "  r.RELEASE_TYPE, r.REV_NUMBER, r.RELEASE_DATE, " +
        "  p2.LIST22, p2.LIST29, p2.LIST24, p2.LIST11, p2.LIST19, p2.LIST20, p2.LIST14, " +
        "  p2.TEXT26, p2.DATE04, " +
        "  p2.MULTILIST01, p2.MULTILIST03, " +
        "  p3.LIST31, p3.LIST32, p3.LIST36, p3.LIST46, p3.LIST52, p3.LIST53, " +
        "  p3.MULTILIST32, p3.MULTILIST33 " +
        "FROM agile.ITEM i " +
        "  JOIN agile.REV r ON r.ITEM = i.ID AND i.DEFAULT_CHANGE = r.CHANGE AND r.SITE = 0 " +
        "  JOIN agile.PAGE_TWO p2 ON p2.ID = i.ID AND p2.CLASS = i.CLASS " +
        "  JOIN agile.PAGE_THREE p3 ON p3.ID = i.ID AND p3.CLASS = i.CLASS " +
        "WHERE i.SUBCLASS = " + SKU_SUBCLASS_ID +
        "  AND NVL(i.DELETE_FLAG, 0) != 1 " +
        "  AND i.CATEGORY != " + F_FAMILY_CATEGORY +
        "  AND r.RELEASE_TYPE IS NOT NULL";

    private static final String SEED_SQL = BASE_SELECT;

    private static final String DELTA_SQL = BASE_SELECT +
        "  AND r.RELEASE_DATE > SYSDATE - ?";

    private static final String FLEX_SQL =
        "SELECT af.ID, af.ATTID, af.NUMBER1 " +
        "FROM agile.AGILE_FLEX af " +
        "  JOIN agile.ITEM i ON af.ID = i.ID " +
        "WHERE af.CLASS = " + ITEMS_CLASS_ID +
        "  AND i.SUBCLASS = " + SKU_SUBCLASS_ID +
        "  AND NVL(i.DELETE_FLAG, 0) != 1 " +
        "  AND af.ATTID IN (" + flexAttIdList() + ")";

    private static final String FLEX_DELTA_SQL =
        "SELECT af.ID, af.ATTID, af.NUMBER1 " +
        "FROM agile.AGILE_FLEX af " +
        "  JOIN agile.ITEM i ON af.ID = i.ID " +
        "  JOIN agile.REV r ON r.ITEM = i.ID AND i.DEFAULT_CHANGE = r.CHANGE AND r.SITE = 0 " +
        "WHERE af.CLASS = " + ITEMS_CLASS_ID +
        "  AND i.SUBCLASS = " + SKU_SUBCLASS_ID +
        "  AND NVL(i.DELETE_FLAG, 0) != 1 " +
        "  AND af.ATTID IN (" + flexAttIdList() + ")" +
        "  AND r.RELEASE_DATE > SYSDATE - ?";

    private static final String LISTENTRY_SQL =
        "SELECT ENTRYID, MIN(ENTRYVALUE) AS ENTRYVALUE " +
        "FROM agile.LISTENTRY WHERE LANGID = 0 GROUP BY ENTRYID";

    private static final String NODETABLE_LC_SQL =
        "SELECT ID, DESCRIPTION FROM agile.NODETABLE WHERE PARENTID = " + LC_PHASE_PARENT;

    // --- injected ---
    @Autowired
    private DataSource dataSource;

    @Value("${sku.data.file:./data/all-skus.json}")
    private String jsonFilePath;

    @Value("${sku.data.delta.days:2}")
    private int deltaDays;

    @Value("${sku.data.query.timeout:120}")
    private int queryTimeoutSeconds;

    // --- state ---
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final ReentrantLock lock = new ReentrantLock();

    /** LISTENTRY lookup:  entryId → display text */
    private Map<Long, String> listEntryMap;
    /** NODETABLE lifecycle phase lookup:  nodeId → phase name (e.g. "ACT") */
    private Map<Long, String> lcPhaseMap;

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Full seed: queries all ~48K SKU items, writes JSON from scratch.
     * @return number of records written
     */
    public int seed() {
        lock.lock();
        try {
            long t0 = System.currentTimeMillis();
            LOG.info("[SKU-SEED] Starting full seed ...");

            loadLookupMaps();

            // 1. Base query – raw IDs
            List<RawRow> rows = executeBaseQuery(SEED_SQL, 0);
            LOG.info("[SKU-SEED] Base query returned " + rows.size() + " rows in " +
                    (System.currentTimeMillis() - t0) + " ms");

            // 2. AGILE_FLEX batch
            long t1 = System.currentTimeMillis();
            Map<Long, Map<Long, Long>> flexMap = executeFlexQuery(FLEX_SQL, 0);
            LOG.info("[SKU-SEED] Flex query loaded " + flexMap.size() + " items in " +
                    (System.currentTimeMillis() - t1) + " ms");

            // 3. Resolve & build records
            Map<String, SkuRecord> records = new LinkedHashMap<>();
            for (RawRow row : rows) {
                SkuRecord rec = resolve(row, flexMap.getOrDefault(row.itemId, Collections.emptyMap()));
                records.put(rec.getNumber(), rec);
            }

            // 4. Write JSON
            SkuDataEnvelope envelope = new SkuDataEnvelope();
            envelope.generatedAt = dateFmt.format(new java.util.Date());
            envelope.lastDeltaAt = null;
            envelope.totalRecords = records.size();
            envelope.records = records;

            writeJson(envelope);

            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("[SKU-SEED] Complete: " + records.size() + " records in " + elapsed + " ms (" +
                    (elapsed / 1000) + " s)");
            return records.size();

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[SKU-SEED] Failed", e);
            throw new RuntimeException("SKU seed failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delta update: queries items released in the last N days, merges into existing JSON.
     * @return number of records added/updated
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 2 * * *")
    public void scheduledDelta() {
        LOG.info("[SKU-DELTA] Scheduled daily delta starting...");
        try {
            int count = delta();
            LOG.info("[SKU-DELTA] Scheduled delta complete: " + count + " records updated");
        } catch (Exception e) {
            LOG.warning("[SKU-DELTA] Scheduled delta failed: " + e.getMessage());
        }
    }

    public int delta() {
        lock.lock();
        try {
            long t0 = System.currentTimeMillis();

            // Calculate days since last delta (smart window)
            SkuDataEnvelope existing = readJson();
            int effectiveDays = deltaDays;
            if (existing != null && existing.lastDeltaAt != null && !existing.lastDeltaAt.isEmpty()) {
                try {
                    java.util.Date lastDelta = dateFmt.parse(existing.lastDeltaAt);
                    long daysSince = (System.currentTimeMillis() - lastDelta.getTime()) / (24 * 60 * 60 * 1000) + 1;
                    effectiveDays = Math.max(deltaDays, (int) daysSince);
                    if (effectiveDays > deltaDays) {
                        LOG.info("[SKU-DELTA] Last delta was " + daysSince + " days ago — expanding window to " + effectiveDays + " days");
                    }
                } catch (Exception e) {
                    // Fall back to configured days
                }
            }

            LOG.info("[SKU-DELTA] Starting delta (last " + effectiveDays + " days) ...");

            loadLookupMaps();

            // 1. Load existing JSON
            SkuDataEnvelope envelope = existing;
            if (envelope == null) {
                LOG.warning("[SKU-DELTA] No existing JSON found – running full seed instead");
                return seed();
            }

            // 2. Delta base query
            List<RawRow> rows = executeBaseQuery(DELTA_SQL, effectiveDays);
            LOG.info("[SKU-DELTA] Delta query returned " + rows.size() + " rows");

            if (rows.isEmpty()) {
                envelope.lastDeltaAt = dateFmt.format(new java.util.Date());
                writeJson(envelope);
                LOG.info("[SKU-DELTA] No new records. Updated timestamp.");
                return 0;
            }

            // 3. AGILE_FLEX for delta items only
            Map<Long, Map<Long, Long>> flexMap = executeFlexQuery(FLEX_DELTA_SQL, effectiveDays);

            // 4. Resolve & upsert
            int count = 0;
            for (RawRow row : rows) {
                SkuRecord rec = resolve(row, flexMap.getOrDefault(row.itemId, Collections.emptyMap()));
                envelope.records.put(rec.getNumber(), rec);
                count++;
            }
            envelope.lastDeltaAt = dateFmt.format(new java.util.Date());
            envelope.totalRecords = envelope.records.size();

            writeJson(envelope);

            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("[SKU-DELTA] Complete: " + count + " upserted, " +
                    envelope.records.size() + " total in " + elapsed + " ms");
            return count;

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[SKU-DELTA] Failed", e);
            throw new RuntimeException("SKU delta failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns summary info about the current JSON cache.
     */
    public Map<String, Object> status() {
        Map<String, Object> info = new LinkedHashMap<>();
        File f = new File(jsonFilePath);
        info.put("file", jsonFilePath);
        info.put("exists", f.exists());
        if (f.exists()) {
            info.put("sizeBytes", f.length());
            info.put("sizeMB", String.format("%.1f", f.length() / (1024.0 * 1024.0)));
            try {
                SkuDataEnvelope env = readJson();
                if (env != null) {
                    info.put("totalRecords", env.totalRecords);
                    info.put("generatedAt", env.generatedAt);
                    info.put("lastDeltaAt", env.lastDeltaAt);
                }
            } catch (Exception e) {
                info.put("error", e.getMessage());
            }
        }
        return info;
    }

    // -----------------------------------------------------------------------
    //  Lookup map loading
    // -----------------------------------------------------------------------

    private void loadLookupMaps() throws SQLException {
        if (listEntryMap != null && lcPhaseMap != null) {
            return; // already loaded this JVM lifecycle
        }
        long t0 = System.currentTimeMillis();

        // LISTENTRY:  entryId → MIN(entryValue) grouped to handle duplicates
        listEntryMap = new HashMap<>(120_000);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LISTENTRY_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setFetchSize(10_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listEntryMap.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        LOG.info("[SKU-LOOKUP] LISTENTRY loaded: " + listEntryMap.size() + " entries");

        // NODETABLE lifecycle phases
        lcPhaseMap = new HashMap<>(32);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(NODETABLE_LC_SQL)) {
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lcPhaseMap.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        LOG.info("[SKU-LOOKUP] Lifecycle phases loaded: " + lcPhaseMap.size() +
                " entries in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /**
     * Force reload of lookup maps (call if list values change).
     */
    public void reloadLookups() {
        listEntryMap = null;
        lcPhaseMap = null;
    }

    // -----------------------------------------------------------------------
    //  Database queries
    // -----------------------------------------------------------------------

    private List<RawRow> executeBaseQuery(String sql, int deltaDaysParam) throws SQLException {
        List<RawRow> rows = new ArrayList<>(50_000);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setFetchSize(5_000);
            if (deltaDaysParam > 0) {
                ps.setInt(1, deltaDaysParam);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RawRow r = new RawRow();
                    r.itemId       = rs.getLong("ID");
                    r.itemNumber   = rs.getString("ITEM_NUMBER");
                    r.description  = rs.getString("DESCRIPTION");
                    r.category     = rs.getLong("CATEGORY");
                    r.productLines = rs.getString("PRODUCT_LINES");
                    r.releaseType  = rs.getLong("RELEASE_TYPE");
                    r.revNumber    = rs.getString("REV_NUMBER");
                    r.releaseDate  = rs.getTimestamp("RELEASE_DATE");
                    // PAGE_TWO single lists
                    r.p2List22     = rs.getLong("LIST22");  if (rs.wasNull()) r.p2List22 = 0;
                    r.p2List29     = rs.getLong("LIST29");  if (rs.wasNull()) r.p2List29 = 0;
                    r.p2List24     = rs.getLong("LIST24");  if (rs.wasNull()) r.p2List24 = 0;
                    r.p2List11     = rs.getLong("LIST11");  if (rs.wasNull()) r.p2List11 = 0;
                    r.p2List19     = rs.getLong("LIST19");  if (rs.wasNull()) r.p2List19 = 0;
                    r.p2List20     = rs.getLong("LIST20");  if (rs.wasNull()) r.p2List20 = 0;
                    r.p2List14     = rs.getLong("LIST14");  if (rs.wasNull()) r.p2List14 = 0;
                    r.p2Text26     = rs.getString("TEXT26");
                    r.p2Date04     = rs.getTimestamp("DATE04");
                    // PAGE_TWO multilists
                    r.p2Multi01    = rs.getString("MULTILIST01");
                    r.p2Multi03    = rs.getString("MULTILIST03");
                    // PAGE_THREE single lists
                    r.p3List31     = rs.getLong("LIST31");  if (rs.wasNull()) r.p3List31 = 0;
                    r.p3List32     = rs.getLong("LIST32");  if (rs.wasNull()) r.p3List32 = 0;
                    r.p3List36     = rs.getLong("LIST36");  if (rs.wasNull()) r.p3List36 = 0;
                    r.p3List46     = rs.getLong("LIST46");  if (rs.wasNull()) r.p3List46 = 0;
                    r.p3List52     = rs.getLong("LIST52");  if (rs.wasNull()) r.p3List52 = 0;
                    r.p3List53     = rs.getLong("LIST53");  if (rs.wasNull()) r.p3List53 = 0;
                    // PAGE_THREE multilists
                    r.p3Multi32    = rs.getString("MULTILIST32");
                    r.p3Multi33    = rs.getString("MULTILIST33");

                    rows.add(r);
                }
            }
        }
        return rows;
    }

    /**
     * Batch-load AGILE_FLEX:  itemId → { attId → number1 }
     */
    private Map<Long, Map<Long, Long>> executeFlexQuery(String sql, int deltaDaysParam) throws SQLException {
        Map<Long, Map<Long, Long>> map = new HashMap<>(50_000);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setFetchSize(10_000);
            if (deltaDaysParam > 0) {
                ps.setInt(1, deltaDaysParam);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long itemId = rs.getLong("ID");
                    long attId  = rs.getLong("ATTID");
                    long num1   = rs.getLong("NUMBER1");
                    map.computeIfAbsent(itemId, k -> new HashMap<>(16)).put(attId, num1);
                }
            }
        }
        return map;
    }

    // -----------------------------------------------------------------------
    //  Resolution:  raw IDs → display text
    // -----------------------------------------------------------------------

    private SkuRecord resolve(RawRow r, Map<Long, Long> flex) {
        SkuRecord rec = new SkuRecord();

        // Title Block
        rec.setNumber(r.itemNumber);
        rec.setDescription(trimCR(r.description));
        rec.setLifecyclePhase(lcPhaseMap.get(r.releaseType));
        rec.setRev(r.revNumber);
        rec.setProductLine(resolveMultiList(r.productLines));
        rec.setPm(resolveList(r.category));
        rec.setRevReleaseDate(formatTs(r.releaseDate));

        // PAGE_THREE (PDM Attributes)
        rec.setProductLineProgramName(resolveMultiList(r.p3Multi33));
        rec.setCategory(resolveMultiList(r.p3Multi32));
        rec.setProductCustomer(resolveList(r.p3List32));
        rec.setBomType(resolveList(r.p3List36));
        rec.setWarrantyMonths(resolveList(r.p3List46));
        rec.setProgProgramName(resolveList(r.p3List53));
        rec.setProgramShortName(resolveList(r.p3List31));
        rec.setExternalMarketingName(resolveList(r.p3List52));

        // PAGE_TWO (Inv/Planning)
        rec.setAsBuildCapacityBinary(resolveList(r.p2List22));
        rec.setAsMarketedCapacityDecimal(r.p2Text26);
        rec.setAsMarketedCapacityUom(resolveList(r.p2List29));
        rec.setFamilyName(resolveList(r.p2List24));
        rec.setSubcontractors(resolveMultiList(r.p2Multi01));
        rec.setActualBuildPlant(resolveMultiList(r.p2Multi03));
        rec.setMaterialType(resolveList(r.p2List11));
        rec.setGroup(resolveList(r.p2List19));
        rec.setMaterialGroup(resolveList(r.p2List20));
        rec.setProdDivision(resolveList(r.p2List14));
        rec.setCreateDate(formatTs(r.p2Date04));

        // AGILE_FLEX extended attributes
        rec.setAsSoldCapacityBinary(resolveFlexList(flex, ATTID_AS_SOLD_CAP));
        rec.setBusinessSegmentSubSegment(resolveFlexList(flex, ATTID_BIZ_SEG));
        rec.setProductSegment(resolveFlexList(flex, ATTID_PROD_SEG));
        rec.setProductSubSegment(resolveFlexList(flex, ATTID_PROD_SUBSEG));
        rec.setInterfaceType(resolveFlexList(flex, ATTID_IFACE_TYPE));
        rec.setFormFactorType(resolveFlexList(flex, ATTID_FORM_FACTOR));
        rec.setProductBrand(resolveFlexList(flex, ATTID_PROD_BRAND));
        rec.setBrand(resolveFlexList(flex, ATTID_BRAND));
        rec.setPartnership(resolveFlexList(flex, ATTID_PARTNERSHIP));
        rec.setProductSwimlane(resolveFlexList(flex, ATTID_SWIMLANE));
        rec.setStampSerializationFlag(resolveFlexList(flex, ATTID_STAMP_SER));
        rec.setRetailer(resolveFlexList(flex, ATTID_RETAILER));
        rec.setItemClass(resolveFlexList(flex, ATTID_ITEM_CLASS));

        return rec;
    }

    /** Single-value list: entryId → display text */
    private String resolveList(long entryId) {
        if (entryId == 0) return null;
        return listEntryMap.get(entryId);
    }

    /** AGILE_FLEX list: flex attId → NUMBER1 → LISTENTRY display text */
    private String resolveFlexList(Map<Long, Long> flex, long attId) {
        Long num1 = flex.get(attId);
        if (num1 == null || num1 == 0) return null;
        return listEntryMap.get(num1);
    }

    /**
     * Multi-value list:  comma-delimited string like ",12345,67890,"
     * → parse IDs → resolve each → join with ", "
     */
    private String resolveMultiList(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] parts = csv.split(",");
        List<String> vals = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                long id = Long.parseLong(t);
                String val = listEntryMap.get(id);
                if (val != null) vals.add(val);
            } catch (NumberFormatException ignored) {
                // skip non-numeric tokens
            }
        }
        if (vals.isEmpty()) return null;
        Collections.sort(vals);
        return String.join(", ", vals);
    }

    // -----------------------------------------------------------------------
    //  JSON persistence
    // -----------------------------------------------------------------------

    // In-memory cache of the parsed envelope. The JSON file is ~50 MB / 48K records —
    // parsing it on every lookup (as we used to) added ~5 s to every SKU search. We
    // keep a single parsed copy and invalidate when the file's mtime changes (which
    // happens on seed / delta writes via writeJson below).
    private volatile SkuDataEnvelope cachedEnvelope = null;
    private volatile long cachedMtime = -1L;
    private final Object envelopeLock = new Object();

    private void writeJson(SkuDataEnvelope envelope) throws IOException {
        File f = new File(jsonFilePath);
        f.getParentFile().mkdirs();
        mapper.writeValue(f, envelope);
        // Update the in-memory cache in place so subsequent reads skip the reparse.
        synchronized (envelopeLock) {
            cachedEnvelope = envelope;
            cachedMtime = f.lastModified();
        }
        LOG.info("[SKU-JSON] Written " + envelope.totalRecords + " records to " + f.getAbsolutePath() +
                " (" + String.format("%.1f", f.length() / (1024.0 * 1024.0)) + " MB)");
    }

    private SkuDataEnvelope readJson() {
        File f = new File(jsonFilePath);
        if (!f.exists()) return null;

        // Fast path: cached copy still matches the file on disk.
        long mtime = f.lastModified();
        SkuDataEnvelope env = cachedEnvelope;
        if (env != null && mtime == cachedMtime) return env;

        // Slow path: parse once and stash. Synchronized so concurrent first-hits don't
        // each spend 5 s reparsing the same 50 MB file.
        synchronized (envelopeLock) {
            if (cachedEnvelope != null && mtime == cachedMtime) return cachedEnvelope;
            try {
                long t0 = System.currentTimeMillis();
                SkuDataEnvelope parsed = mapper.readValue(f, SkuDataEnvelope.class);
                long elapsed = System.currentTimeMillis() - t0;
                cachedEnvelope = parsed;
                cachedMtime = mtime;
                LOG.info("[SKU-JSON] Parsed " + (parsed.records != null ? parsed.records.size() : 0)
                        + " records from " + f.getName() + " ("
                        + String.format("%.1f", f.length() / (1024.0 * 1024.0)) + " MB) in "
                        + elapsed + " ms — cached in memory");
                return parsed;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[SKU-JSON] Failed to read " + f.getAbsolutePath(), e);
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static String flexAttIdList() {
        return Arrays.stream(ALL_FLEX_ATTIDS)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    private String formatTs(Timestamp ts) {
        if (ts == null) return null;
        synchronized (dateFmt) {
            return dateFmt.format(ts);
        }
    }

    private static String trimCR(String s) {
        if (s == null) return null;
        return s.replace("\r\n", " ").replace("\r", " ").replace("\n", " ").trim();
    }

    // -----------------------------------------------------------------------
    //  Record lookup (from JSON file)
    // -----------------------------------------------------------------------

    /**
     * Look up a single SKU record by item number from the JSON cache.
     * @param itemNumber the exact item number key
     * @return the SkuRecord as a Map, or null if not found
     */
    public Object getRecord(String itemNumber) {
        SkuDataEnvelope envelope = readJson();
        if (envelope == null || envelope.records == null) return null;
        SkuRecord rec = envelope.records.get(itemNumber);
        if (rec == null) return null;
        // Convert to Map via Jackson for flexible JSON serialization
        return mapper.convertValue(rec, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Returns the list of all available field names from a sample record.
     */
    public List<String> getAvailableFields() {
        SkuDataEnvelope envelope = readJson();
        if (envelope == null || envelope.records == null || envelope.records.isEmpty()) {
            return Collections.emptyList();
        }
        // Get first record and extract its field names
        SkuRecord sample = envelope.records.values().iterator().next();
        Map<String, Object> map = mapper.convertValue(sample, new TypeReference<Map<String, Object>>() {});
        return new ArrayList<>(map.keySet());
    }

    // -----------------------------------------------------------------------
    //  Inner types
    // -----------------------------------------------------------------------

    /** Raw row from the flat SQL – holds IDs before resolution */
    private static class RawRow {
        long itemId;
        String itemNumber, description;
        long category;
        String productLines;
        long releaseType;
        String revNumber;
        Timestamp releaseDate;
        // PAGE_TWO
        long p2List22, p2List29, p2List24, p2List11, p2List19, p2List20, p2List14;
        String p2Text26;
        Timestamp p2Date04;
        String p2Multi01, p2Multi03;
        // PAGE_THREE
        long p3List31, p3List32, p3List36, p3List46, p3List52, p3List53;
        String p3Multi32, p3Multi33;
    }

    /** JSON envelope wrapping the record map */
    public static class SkuDataEnvelope {
        public String generatedAt;
        public String lastDeltaAt;
        public int totalRecords;
        /** Keyed by ITEM_NUMBER for O(1) upsert */
        public Map<String, SkuRecord> records = new LinkedHashMap<>();
    }
}
