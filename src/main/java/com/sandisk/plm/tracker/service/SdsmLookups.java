package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Shared lookup tables loaded once from {@code agile_prod} and reused across the
 * three SDSM services. Same approach as {@link SkuDataService}: pull LISTENTRY
 * and the lifecycle-phase NODETABLE rows into HashMaps so the per-row resolution
 * is O(1) instead of N correlated subqueries.
 *
 * <p>Loaded lazily on first call; thread-safe init under {@link #lock}.
 * Call {@link #reload()} to force a refresh if Agile vocabularies change.
 */
@Service
public class SdsmLookups {

    private static final Logger LOG = Logger.getLogger(SdsmLookups.class.getName());

    @Autowired
    private DataSource dataSource;

    /** entryId → display text (single LISTENTRY row, LANGID=0, MIN to handle dupes). */
    private volatile Map<Long, String> listEntries;

    /** NODETABLE.ID → DESCRIPTION (subclass display name, e.g. "Document", "Part"). */
    private volatile Map<Long, String> nodeDescriptions;

    /** REV.RELEASE_TYPE NODETABLE.ID → lifecycle phase string ("ACT", "OBS", ...). */
    private volatile Map<Long, String> lifecyclePhases;

    private final Object lock = new Object();

    public Map<Long, String> listEntries() throws SQLException { ensureLoaded(); return listEntries; }
    public Map<Long, String> nodeDescriptions() throws SQLException { ensureLoaded(); return nodeDescriptions; }
    public Map<Long, String> lifecyclePhases() throws SQLException { ensureLoaded(); return lifecyclePhases; }

    public void reload() {
        synchronized (lock) {
            listEntries = null;
            nodeDescriptions = null;
            lifecyclePhases = null;
        }
    }

    private void ensureLoaded() throws SQLException {
        if (listEntries != null && nodeDescriptions != null && lifecyclePhases != null) return;
        synchronized (lock) {
            if (listEntries != null && nodeDescriptions != null && lifecyclePhases != null) return;

            long t0 = System.currentTimeMillis();

            Map<Long, String> le = new HashMap<>(120_000);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT ENTRYID, MIN(ENTRYVALUE) FROM agile.LISTENTRY WHERE LANGID = 0 GROUP BY ENTRYID")) {
                ps.setFetchSize(10_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) le.put(rs.getLong(1), rs.getString(2));
                }
            }
            LOG.info("[SDSM-LOOKUP] LISTENTRY: " + le.size() + " entries");

            Map<Long, String> nodes = new HashMap<>(50_000);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT ID, DESCRIPTION FROM agile.NODETABLE WHERE DESCRIPTION IS NOT NULL")) {
                ps.setFetchSize(10_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) nodes.put(rs.getLong(1), rs.getString(2));
                }
            }
            LOG.info("[SDSM-LOOKUP] NODETABLE: " + nodes.size() + " entries");

            // Lifecycle phases live under PARENTID 1514 per SKU-DATA-HANDOFF.md §"Where Lifecycle Phase Lives".
            Map<Long, String> lc = new HashMap<>(32);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT ID, DESCRIPTION FROM agile.NODETABLE WHERE PARENTID = 1514")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) lc.put(rs.getLong(1), rs.getString(2));
                }
            }
            LOG.info("[SDSM-LOOKUP] Lifecycle phases: " + lc.size() +
                    " entries; total load " + (System.currentTimeMillis() - t0) + " ms");

            this.listEntries = le;
            this.nodeDescriptions = nodes;
            this.lifecyclePhases = lc;
        }
    }

    // -----------------------------------------------------------------------
    //  Convenience resolvers — null-safe, return null if not found.
    // -----------------------------------------------------------------------

    /**
     * Trigger lazy load of all three maps (LISTENTRY / NODETABLE / lifecycle phases).
     * Each service calls this once at the top of its {@code run()} so the per-row
     * resolvers below don't silently return null when invoked before the maps are
     * populated. (Cheap on the hot path — already-loaded check is a single volatile read.)
     */
    public void warmUp() throws SQLException { ensureLoaded(); }

    public String resolveListEntry(long entryId) {
        if (entryId == 0) return null;
        Map<Long, String> m = listEntries;
        if (m == null) {
            try { ensureLoaded(); m = listEntries; }
            catch (SQLException e) { return null; }
        }
        return m == null ? null : m.get(entryId);
    }

    public String resolveSubclassName(long subclassId) {
        if (subclassId == 0) return null;
        Map<Long, String> m = nodeDescriptions;
        if (m == null) {
            try { ensureLoaded(); m = nodeDescriptions; }
            catch (SQLException e) { return null; }
        }
        return m == null ? null : m.get(subclassId);
    }

    public String resolveLifecycle(long releaseType) {
        if (releaseType == 0) return null;
        Map<Long, String> m = lifecyclePhases;
        if (m == null) {
            try { ensureLoaded(); m = lifecyclePhases; }
            catch (SQLException e) { return null; }
        }
        return m == null ? null : m.get(releaseType);
    }

    /**
     * Parse a comma-anchored CSV like {@code ",1234,5678,"} into a list of LISTENTRY display
     * values. Returns an empty list (never null) for blank or unparseable input.
     */
    public List<String> resolveCsvListEntries(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String tok : csv.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try {
                String v = resolveListEntry(Long.parseLong(t));
                if (v != null) {
                    // Trim incidental whitespace baked into the LISTENTRY value
                    // (some ENTRYVALUE rows store ' ALL ' with padding) so the
                    // operator-facing dropdowns / chips are tight.
                    String trimmedValue = v.trim();
                    if (!trimmedValue.isEmpty()) out.add(trimmedValue);
                }
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Same as {@link #resolveCsvListEntries(String)} but returns the raw IDs (e.g. for ITEM-ref CSVs). */
    public List<Long> parseCsvLongs(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        for (String tok : csv.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
