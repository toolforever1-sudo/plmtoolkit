package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class ChangeHistoryService {

    private static final Logger logger = Logger.getLogger(ChangeHistoryService.class.getName());

    private static final int IN_CHUNK = 1000;        // Oracle IN-list cap
    private static final int QUERY_TIMEOUT_SEC = 90; // bulk-friendly, single batched query per chunk
    /** Hard row cap for the items-empty path (filters-only query). Prevents an
     *  unbounded scan if a user sets a wide release-date range. */
    private static final int NO_ITEMS_ROW_CAP = 25000;

    /** Optional second datasource for item_extract Part-Type lookups (custom_user schema).
     *  Null on environments without the custom schema — filter is silently skipped. */
    private final DataSource customDataSource;

    /** Base column projection — matches the public KEYS contract used by the controller. */
    private static final String SELECT_COLS =
        "    i.ITEM_NUMBER AS item_number, " +
        "    r.REV_NUMBER AS rev, " +
        "    lp_parent.DESCRIPTION || '.' || lp.DESCRIPTION AS lifecycle_phase, " +
        "    r.RELEASE_DATE AS rel_date, " +
        "    r.INCORP_DATE AS inc_date, " +
        "    r.EFFECTIVE_DATE AS eff_date, " +
        "    r.OBSOLETE_DATE AS obs_date, " +
        "    c.CHANGE_NUMBER AS change_num, " +
        "    ct.DESCRIPTION AS change_type, " +
        "    c.DESCRIPTION AS change_desc, " +
        "    cs.DESCRIPTION AS change_status ";

    private static final String FROM_JOINS =
        "FROM AGILE.REV r " +
        "JOIN AGILE.ITEM i ON r.ITEM = i.ID " +
        "LEFT JOIN AGILE.CHANGE c ON r.CHANGE = c.ID " +
        "LEFT JOIN AGILE.NODETABLE lp ON r.RELEASE_TYPE = lp.ID " +
        "LEFT JOIN AGILE.NODETABLE lp_parent ON lp.PARENTID = lp_parent.ID " +
        "LEFT JOIN AGILE.NODETABLE cs ON c.STATUS = cs.ID " +
        "LEFT JOIN AGILE.NODETABLE ct ON c.SUBCLASS = ct.ID ";

    private final DataSource dataSource;

    /** In-memory cache of item_extract (PT-X). Used to resolve NEW_PART_CLASS for the
     *  Part Type cohort filter without a DB roundtrip — the DB-backed lookup was
     *  taking ~20s for a 1500-item cohort and dominated query latency. Null-tolerant:
     *  falls back to {@link #lookupPartClassesFromDb} when the cache is empty. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ItemCacheService itemCacheService;

    public ChangeHistoryService(@Qualifier("dataSource") DataSource dataSource,
                                @Qualifier("customDataSource") DataSource customDataSource) {
        this.dataSource = dataSource;
        this.customDataSource = customDataSource;
    }

    /** PT-63: entry-mode for ROW_NUMBER partition. */
    public enum EntryMode { ALL, FIRST, LAST }

    /** PT-63 + PT-64: optional extra filters passed to the bulk fetch. Null/empty
     *  fields mean "no constraint". Kept as a value object so callers don't grow
     *  unbounded positional argument lists. */
    public static final class HistoryFilters {
        public List<String> lifecyclePhases = Collections.emptyList();
        public List<String> changeTypes    = Collections.emptyList();
        /** Inclusive ISO-8601 yyyy-MM-dd start; null = no lower bound. */
        public String releaseDateFrom;
        /** Inclusive ISO-8601 yyyy-MM-dd end; null = no upper bound. */
        public String releaseDateTo;
        /** NEW_PART_CLASS values from item_extract; applied as a post-filter. */
        public List<String> partTypes      = Collections.emptyList();
        public EntryMode entryMode         = EntryMode.ALL;

        public static HistoryFilters none() { return new HistoryFilters(); }
    }

    /** Backwards-compatible single-item call — delegates to the unfiltered batch path. */
    public List<Map<String, String>> getHistory(String itemNumber) {
        return getHistoryFiltered(Collections.singletonList(itemNumber),
                                  Collections.emptyList(), Collections.emptyList());
    }

    /** Backwards-compatible bulk call without filters. */
    public List<Map<String, String>> getHistoryMultiple(List<String> itemNumbers) {
        return getHistoryFiltered(itemNumbers, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Backwards-compatible signature: lifecycle + change-type filters only. Delegates
     * to the full-filters variant.
     */
    public List<Map<String, String>> getHistoryFiltered(List<String> itemNumbers,
                                                        List<String> lifecyclePhases,
                                                        List<String> changeTypes) {
        HistoryFilters f = new HistoryFilters();
        f.lifecyclePhases = lifecyclePhases == null ? Collections.emptyList() : lifecyclePhases;
        f.changeTypes     = changeTypes    == null ? Collections.emptyList() : changeTypes;
        return getHistoryFiltered(itemNumbers, f);
    }

    /**
     * Bulk fetch with all optional filters in a single value object (PT-63 + PT-64).
     *
     * <p><strong>PT-X (2026-05-29) — cohort semantics.</strong> Release-date range
     * and Part Type are <em>cohort</em> filters (which items qualify), not row
     * filters (which rev entries to show). Lifecycle and Change Type stay as row
     * filters. Mode = first/last then partitions over the cohort items'
     * <em>full</em> history — so "first ACT entry" returns the actual first ever,
     * not the first inside the date window.</p>
     *
     * <p>Pre-PT-X: SKU 0TS2587's first ACT was 2026-03-31 via MCO-133719-A, but a
     * last-month + ACT + First-entry search returned the 2026-05-12 SPECIAL ORDERS
     * rev (the only ACT rev inside the window). Now the same search returns the
     * March 31 entry — the SKU still qualifies via its May release but the
     * displayed entry is the true first.</p>
     *
     * <p>When lifecycle/change-type row filters are set <strong>or</strong> mode is
     * first/last, the query also requires <code>r.RELEASE_DATE IS NOT NULL</code>
     * — pending REVs that propose a phase transition shouldn't count as having
     * reached it.</p>
     */
    public List<Map<String, String>> getHistoryFiltered(List<String> itemNumbers, HistoryFilters f) {
        if (f == null) f = HistoryFilters.none();

        // Trim and de-duplicate inputs.
        LinkedHashSet<String> rawItems = new LinkedHashSet<>();
        if (itemNumbers != null) {
            for (String it : itemNumbers) {
                if (it != null && !it.trim().isEmpty()) rawItems.add(it.trim());
            }
        }
        List<String> phases     = sanitize(f.lifecyclePhases);
        List<String> types      = sanitize(f.changeTypes);
        List<String> partTypes  = sanitize(f.partTypes);
        String dateFrom         = trimToNull(f.releaseDateFrom);
        String dateTo           = trimToNull(f.releaseDateTo);
        EntryMode mode          = f.entryMode == null ? EntryMode.ALL : f.entryMode;

        boolean hasItems     = !rawItems.isEmpty();
        boolean hasDateRange = dateFrom != null || dateTo != null;

        // Safety rail: at least one cohort selector required. Without it, a
        // "lifecycle=ACT" search would scan all of AGILE.REV.
        if (!hasItems && !hasDateRange) {
            logger.info("[CHANGE HISTORY] refused: no items and no release-date constraint");
            return new ArrayList<>();
        }

        // --- Step 1: build the cohort (which items qualify) ---
        // Date range narrows the cohort to items with a released REV in the window,
        // intersected with the explicit items list if both were given. When only an
        // items list is given, the cohort is just that list (no DB lookup needed).
        LinkedHashSet<String> cohort;
        boolean cohortFromDateScan;  // apply 25K row cap when cohort came from an unbounded date scan
        if (hasDateRange) {
            cohort = queryCohortItems(hasItems ? new ArrayList<>(rawItems) : Collections.emptyList(),
                                      dateFrom, dateTo);
            cohortFromDateScan = !hasItems;
        } else {
            cohort = rawItems;
            cohortFromDateScan = false;
        }
        if (cohort.isEmpty()) return new ArrayList<>();

        // Part Type is also a cohort filter — narrow before fetching history.
        // NEW_PART_CLASS lives in custom_user.item_extract (different DataSource,
        // can't join in-SQL), so we look it up batched and drop non-matching items.
        if (!partTypes.isEmpty()) {
            Map<String, String> classByItem = lookupPartClasses(cohort);
            Set<String> wanted = new HashSet<>();
            for (String t : partTypes) wanted.add(t.toLowerCase());
            cohort.removeIf(item -> {
                String cls = classByItem.get(item);
                return cls == null || !matchesAnyPartType(cls, wanted);
            });
        }
        if (cohort.isEmpty()) return new ArrayList<>();

        // --- Step 2: fetch full history for cohort items with row filters + mode ---
        // Chunked over IN_CHUNK to respect Oracle's IN-list cap. When the cohort
        // came from a pure date scan, apply NO_ITEMS_ROW_CAP across chunks so a
        // wide date range can't return unbounded rows. Items-explicit cohorts are
        // not capped (caller is responsible for the input size).
        List<String> cohortList = new ArrayList<>(cohort);
        List<Map<String, String>> all = new ArrayList<>();
        int rowsLeft = cohortFromDateScan ? NO_ITEMS_ROW_CAP : Integer.MAX_VALUE;
        for (int start = 0; start < cohortList.size() && rowsLeft > 0; start += IN_CHUNK) {
            int end = Math.min(start + IN_CHUNK, cohortList.size());
            List<Map<String, String>> rows = runBatch(
                    cohortList.subList(start, end), phases, types, mode, rowsLeft);
            all.addAll(rows);
            if (rowsLeft != Integer.MAX_VALUE) rowsLeft -= rows.size();
        }
        return all;
    }

    /** History fetch for a single cohort chunk. Cohort selection has already happened;
     *  this query pulls full released history for the chunk and surfaces each rev's
     *  prior released phase via a LAG window. Lifecycle / change-type row filters
     *  are applied <em>outside</em> the LAG so the prior-phase calc sees the full
     *  ordered series (otherwise the prior for a first-ACT rev would erroneously
     *  skip pre-ACT phases like PPROD). {@code cap} is the remaining row budget —
     *  pass Integer.MAX_VALUE for no cap. */
    private List<Map<String, String>> runBatch(List<String> chunk,
                                               List<String> phases,
                                               List<String> types,
                                               EntryMode mode,
                                               int cap) {
        // Inner: cohort items, released revs only, with LAG(prior phase) over the
        // SKU's full release history. Tiebreaker on REV_NUMBER for same-day revs.
        StringBuilder inner = new StringBuilder();
        inner.append("SELECT ").append(SELECT_COLS)
             .append(", lp.DESCRIPTION AS lp_desc")
             .append(", LAG(lp.DESCRIPTION) OVER (PARTITION BY i.ITEM_NUMBER ")
             .append("ORDER BY r.RELEASE_DATE, r.REV_NUMBER) AS prior_phase ")
             .append(FROM_JOINS)
             .append("WHERE r.CHANGE != 0 ")
             .append("AND r.RELEASE_DATE IS NOT NULL ")
             .append("AND i.ITEM_NUMBER IN (").append(placeholders(chunk.size())).append(") ");

        // Outer filter clause — applied on the inner aliases.
        StringBuilder outerWhere = new StringBuilder();
        if (!phases.isEmpty()) {
            outerWhere.append(" AND lp_desc IN (").append(placeholders(phases.size())).append(")");
        }
        if (!types.isEmpty()) {
            outerWhere.append(" AND change_type IN (").append(placeholders(types.size())).append(")");
        }

        String capClause = cap < Integer.MAX_VALUE
                ? (" FETCH FIRST " + cap + " ROWS ONLY")
                : "";

        StringBuilder sql = new StringBuilder();
        if (mode == EntryMode.ALL) {
            sql.append("SELECT ih.* FROM (").append(inner).append(") ih ")
               .append("WHERE 1=1").append(outerWhere)
               .append(" ORDER BY ih.item_number, ih.rel_date DESC NULLS LAST")
               .append(capClause);
        } else {
            // PT-63: rank rows within each (item, lifecycle phase) partition by release
            // date and keep rn=1. FIRST → oldest entry per state, LAST → newest.
            String dir = mode == EntryMode.FIRST ? "ASC" : "DESC";
            sql.append("SELECT * FROM (")
               .append("SELECT ih.*, ROW_NUMBER() OVER (PARTITION BY ih.item_number, ih.lp_desc ")
               .append("ORDER BY ih.rel_date ").append(dir).append(" NULLS LAST) AS rn ")
               .append("FROM (").append(inner).append(") ih ")
               .append("WHERE 1=1").append(outerWhere)
               .append(") WHERE rn = 1 ")
               .append("ORDER BY item_number, rel_date ").append(dir).append(" NULLS LAST")
               .append(capClause);
        }

        List<Map<String, String>> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            ps.setFetchSize(1000);
            int idx = 1;
            for (String it : chunk) ps.setString(idx++, it);
            for (String p  : phases) ps.setString(idx++, p);
            for (String t  : types)  ps.setString(idx++, t);

            try (ResultSet rs = ps.executeQuery()) {
                // PT-tz follow-up (May 27, 2026): AGILE.REV's RELEASE_DATE /
                // INCORP_DATE / EFFECTIVE_DATE / OBSOLETE_DATE columns are bare
                // Oracle DATEs storing UTC wall-clock (matches what Agile UI
                // shows when it renders these with a PDT/PST suffix). Without
                // a Calendar arg, the JDBC driver interprets the wall-clock in
                // the JVM's default TZ — on the Windows prod box that's Pacific,
                // so the parsed instant ends up 7–8 h off from the true UTC.
                // formatTs(PT) then renders that already-wrong instant and the
                // mismatch with Agile UI persists. Passing a UTC Calendar tells
                // JDBC "this naked wall-clock is UTC", which lands the right
                // instant and lets formatTs(PT) produce the matching PT string.
                java.util.Calendar utcCal = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("UTC"));
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("itemNumber", nvl(rs.getString("item_number")));
                    row.put("rev", nvl(rs.getString("rev")));
                    String lp = nvl(rs.getString("lifecycle_phase"));
                    if (lp.startsWith("Items.")) lp = lp.substring(6);
                    row.put("lifecyclePhase", lp);
                    row.put("priorPhase", nvl(rs.getString("prior_phase")));
                    row.put("releaseDate", formatTs(rs.getTimestamp("rel_date", utcCal)));
                    row.put("incorpDate", formatTs(rs.getTimestamp("inc_date", utcCal)));
                    row.put("effectiveDate", formatTs(rs.getTimestamp("eff_date", utcCal)));
                    row.put("obsoleteDate", formatTs(rs.getTimestamp("obs_date", utcCal)));
                    row.put("changeNumber", nvl(rs.getString("change_num")));
                    row.put("changeType", nvl(rs.getString("change_type")));
                    row.put("changeDescription", nvl(rs.getString("change_desc")));
                    row.put("changeStatus", nvl(rs.getString("change_status")));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("[CHANGE HISTORY] History batch query failed (cohort=" + chunk.size()
                    + (phases.isEmpty() ? "" : ", phases=" + phases)
                    + (types.isEmpty()  ? "" : ", types="  + types) + "): " + e.getMessage());
        }
        return out;
    }

    /** Resolve the cohort of items qualifying for a date-range search. Returns
     *  DISTINCT ITEM_NUMBERs that have at least one released REV in
     *  <code>[dateFrom, dateTo]</code>, optionally intersected with an explicit
     *  items list. {@code itemsFilter} is chunked over IN_CHUNK so a 10K-item
     *  explicit list (intersected with a date range) doesn't blow Oracle's
     *  IN-list cap. Caller has already verified that at least one of
     *  {@code itemsFilter}/{@code dateFrom}/{@code dateTo} is set. */
    private LinkedHashSet<String> queryCohortItems(List<String> itemsFilter,
                                                   String dateFrom,
                                                   String dateTo) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (itemsFilter == null || itemsFilter.isEmpty()) {
            out.addAll(runCohortQuery(Collections.emptyList(), dateFrom, dateTo));
            return out;
        }
        for (int start = 0; start < itemsFilter.size(); start += IN_CHUNK) {
            int end = Math.min(start + IN_CHUNK, itemsFilter.size());
            out.addAll(runCohortQuery(itemsFilter.subList(start, end), dateFrom, dateTo));
        }
        return out;
    }

    private List<String> runCohortQuery(List<String> itemsChunk, String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT i.ITEM_NUMBER FROM AGILE.REV r ")
           .append("JOIN AGILE.ITEM i ON r.ITEM = i.ID ")
           .append("WHERE r.CHANGE != 0 ")
           .append("AND r.RELEASE_DATE IS NOT NULL ");
        if (dateFrom != null) sql.append("AND r.RELEASE_DATE >= TO_DATE(?, 'YYYY-MM-DD') ");
        // +1 day so an upper bound of e.g. 2026-05-14 includes records released
        // anywhere on May 14 (Oracle TO_DATE drops the time component).
        if (dateTo   != null) sql.append("AND r.RELEASE_DATE <  TO_DATE(?, 'YYYY-MM-DD') + 1 ");
        if (!itemsChunk.isEmpty()) {
            sql.append("AND i.ITEM_NUMBER IN (").append(placeholders(itemsChunk.size())).append(") ");
        }

        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            ps.setFetchSize(1000);
            int idx = 1;
            if (dateFrom != null) ps.setString(idx++, dateFrom);
            if (dateTo   != null) ps.setString(idx++, dateTo);
            for (String it : itemsChunk) ps.setString(idx++, it);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String in = rs.getString(1);
                    if (in != null && !in.isEmpty()) out.add(in);
                }
            }
        } catch (SQLException e) {
            logger.warning("[CHANGE HISTORY] Cohort query failed (itemsChunk=" + itemsChunk.size()
                    + ", dates=" + dateFrom + ".." + dateTo + "): " + e.getMessage());
        }
        return out;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static List<String> sanitize(List<String> in) {
        if (in == null) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return new ArrayList<>(out);
    }

    private static String nvl(String val) { return val != null ? val.trim() : ""; }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** PT-tz (May 27, 2026): render all Change History timestamps in Pacific Time
     *  regardless of JVM default. Prod runs on UTC and was emitting dates 7–8 hours
     *  shifted (so Jan-2 transactions read as Jan-1 late-evening). Vikas Singh's
     *  field users compare these against Agile's own UI which is Pacific, so the
     *  toolkit needs to match. Single shared TZ constant for the formatter + the
     *  round-trip parsers in ChangeHistoryEnrichService. */
    static final java.util.TimeZone PACIFIC_TZ = java.util.TimeZone.getTimeZone("America/Los_Angeles");

    private static String formatTs(Timestamp ts) {
        if (ts == null) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd-yyyy hh:mm:ss a");
        sdf.setTimeZone(PACIFIC_TZ);
        return sdf.format(ts);
    }

    /**
     * Look up NEW_PART_CLASS for a batch of item numbers. NEW_PART_CLASS is a
     * pipe-separated multi-value field (e.g. "M034-Lids|P005-Trays"), so the caller
     * checks for substring matches rather than exact equality.
     *
     * <p>Prefers the in-memory ItemCacheService (sub-ms per item, ~1500 items in
     * the typical cohort) over a DB lookup against custom_user.item_extract
     * (~20s for the same batch on a chunked IN query). Falls back to the DB path
     * when the cache hasn't loaded yet.</p>
     */
    private Map<String, String> lookupPartClasses(Collection<String> items) {
        Map<String, String> out = new HashMap<>();
        if (items == null || items.isEmpty()) return out;
        if (itemCacheService != null && itemCacheService.isLoaded()) {
            for (String it : items) {
                if (it == null || it.isEmpty()) continue;
                Map<String, String> rec = itemCacheService.getRecord(it);
                if (rec == null) continue;
                String cls = rec.get("NEW_PART_CLASS");
                out.put(it, cls == null ? "" : cls);
            }
            return out;
        }
        return lookupPartClassesFromDb(items);
    }

    private Map<String, String> lookupPartClassesFromDb(Collection<String> items) {
        Map<String, String> out = new HashMap<>();
        if (customDataSource == null || items == null || items.isEmpty()) return out;
        List<String> list = new ArrayList<>(items);
        for (int s = 0; s < list.size(); s += IN_CHUNK) {
            int e = Math.min(s + IN_CHUNK, list.size());
            List<String> chunk = list.subList(s, e);
            String sql = "SELECT PART_NUMBER, NEW_PART_CLASS FROM item_extract WHERE PART_NUMBER IN ("
                    + placeholders(chunk.size()) + ")";
            try (Connection c = customDataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(30);
                int i = 1;
                for (String it : chunk) ps.setString(i++, it);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pn = rs.getString("PART_NUMBER");
                        String cls = rs.getString("NEW_PART_CLASS");
                        if (pn != null) out.put(pn, cls == null ? "" : cls);
                    }
                }
            } catch (SQLException ex) {
                logger.warning("[CHANGE HISTORY] Part-class DB lookup failed for " + chunk.size()
                        + " items: " + ex.getMessage());
            }
        }
        return out;
    }

    /** {@code cls} is a pipe-separated NEW_PART_CLASS string. Returns true if any of
     *  the wanted (already lower-cased) tokens appears as a case-insensitive
     *  substring — matches the chip behavior in the UI (typing "sku" finds
     *  "A199-Retail Finished Good (SKU)"). */
    private static boolean matchesAnyPartType(String cls, Set<String> wantedLower) {
        if (cls == null || cls.isEmpty()) return false;
        String lc = cls.toLowerCase();
        for (String w : wantedLower) {
            if (!w.isEmpty() && lc.contains(w)) return true;
        }
        return false;
    }
}
