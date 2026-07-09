package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Backs the Overdue Tracker view inside the ECN Report tab.
 *
 * <p>Mirrors the Returns Tracker pattern (PT-61) but on a different population: open
 * Standard-type ECNs that are over their cycle-time target. For each such ECN we
 * pull the CCB comment chain from {@code agile.change_history} (the same table the
 * rejection script uses, just a wider event_type filter) and send the chain to
 * Portkey/Claude for one-shot categorization into a fixed taxonomy. Aggregations
 * are computed on demand from the cached results.
 *
 * <p>Source confirmation: PCM/CCB comments shown in the daily ECN Due Date Expiration
 * email's <em>Comments / Notes</em> column originate from {@code change_history.comments}
 * rows whose Agile-side action renders as "Comment" — the same table the rejection
 * script reads with {@code event_type = 13} for Reject. Comment-shaped events live on
 * {@code event_type IN (14, 15, 17, 65)}; we read all four to match the chain the
 * rejection script already uses for context.
 */
@Service
public class OverdueTrackerService {

    private static final Logger logger = Logger.getLogger(OverdueTrackerService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired private EcnReportService ecnReportService;
    @Autowired private PortkeyClient portkeyClient;

    /**
     * Current-status names to exclude from the OPEN (in-flight) overdue population.
     * Per PT-114 (Jimmy 2026-07-08): the tracker must only count ECNs at Submittal
     * and beyond (Submitted / Review / Release / Hold / Complete). An ECN kicked back
     * to <em>Pending</em> keeps a non-null {@code submit_date}, so the existing
     * {@code submit_date IS NOT NULL} gate alone let those leak in and their
     * {@code SYSDATE - submit_date} clock kept ticking as false overdue. We exclude
     * these current statuses explicitly. Comma-separated, case-sensitive to match
     * {@code agile.nodetable.name} exactly; blank disables the exclusion. Tunable on
     * the server if the Agile status label differs from the default.
     * Retrospective (released) mode is unaffected — it already requires
     * {@code release_date IS NOT NULL}, so Pending ECNs can't appear there.
     */
    @Value("${app.overdue.excluded-statuses:Pending}")
    private String excludedStatusesCsv;

    private final DataSource agileDataSource;

    public OverdueTrackerService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    /** Default set of ECN classifications shown when the caller doesn't pass
     *  {@code classifications=…}. Standard + PDR are the "actual count" group
     *  (per Jimmy 2026-05-18) — they contribute to KPI numbers. Other
     *  classifications (CCB, Dedicated Process, Factory, Project) are
     *  reference-only and opt-in via the chip filter. */
    private static final Set<String> DEFAULT_CLASSIFICATIONS = new LinkedHashSet<>(Arrays.asList(
        "Standard ECNs", "PDR ECN's"
    ));
    /** Fallback target days for classifications whose baselineTargets value is
     *  {@code "NA"} (no formal SLA — Factory Changes, Dedicated Process, etc.).
     *  Used so "overdue" still has a sane definition on reference-only chips. */
    private static final int NO_SLA_FALLBACK_TARGET_DAYS = 30;

    /** Overdue categories, ordered by expected frequency (Jimmy's hypothesis:
     *  majority is CCB/Approval Delay). */
    public static final List<String> CATEGORIES = Arrays.asList(
        "CCB / Approval Delay",
        "Pending Stakeholder Action",
        "Cost / Cost Approval Pending",
        "Information Gap from Requester",
        "Build / Sample Pending",
        "Customer / External Dependency",
        "Tooling / System Issue",
        "Other"
    );

    private static final String MODEL = "@anthropic-eastus2/claude-sonnet-4-6";
    private static final int BATCH_SIZE = 10;

    /** Cached Product Line entryId → entryvalue resolution. The agile.change.product_lines
     *  column is a CSV of LISTENTRY IDs under parent 291 (e.g. ",3312750,") — Python
     *  resolves these via PL_LOOKUP_QUERY in ecn_report_generator.py. We do the same
     *  here once per JVM. Lazy-loaded on first lookup. */
    private final Map<Long, String> productLineLookup = new HashMap<>();
    private volatile boolean productLineLookupLoaded = false;

    // Cached results — in-memory only. Re-categorize on /refresh.
    private volatile Map<String, OverdueRow> cache = new LinkedHashMap<>();
    private volatile String lastRunAt = "";
    private volatile int lastEcnsFetched = 0;
    private volatile int lastEcnsCategorized = 0;
    private volatile String lastError = "";
    private volatile int lastMinOver = 0;
    private volatile int lastMaxOver = 170;
    /** Cached fingerprint of the date-created filter so cache invalidation handles
     *  it the same way it handles min/max-over changes. Empty = no constraint. */
    private volatile String lastCreatedFingerprint = "";
    /** Last resolved created-from / created-to (ISO yyyy-MM-dd) for echo back to UI. */
    private volatile String lastCreatedFrom = "";
    private volatile String lastCreatedTo = "";
    private volatile String lastCreatedRange = "";
    /** Last resolved released-from / released-to. When either is non-empty the tab
     *  is in "retrospective" mode (released ECNs that were overdue at release time)
     *  instead of the default "open" mode (in-flight ECNs over target right now). */
    private volatile String lastReleasedFrom = "";
    private volatile String lastReleasedTo = "";
    private volatile String lastReleasedRange = "";
    /** Last resolved classification filter (sorted CSV). Empty = default set. */
    private volatile String lastClassifications = "";

    /** Per-classification standard/urgent target days resolved from baselineTargets.
     *  Loaded lazily on first refresh and refreshed every time we run a query so
     *  edits to the underlying KPI template take effect on next /refresh. */
    private static final class ClassificationTargets {
        final Map<String, Integer> standardByRcKey = new LinkedHashMap<>(); // rc_key → standard target
        final Map<String, Integer> urgentByRcKey   = new LinkedHashMap<>(); // rc_key → urgent target
        final Map<String, String>  classByRcKey    = new LinkedHashMap<>(); // rc_key → ecnClassification
        final Set<String> allClassifications       = new LinkedHashSet<>();
        boolean isEmpty() { return classByRcKey.isEmpty(); }
    }

    // Live progress for the in-flight refresh — read by GET /api/ecn-report/overdue/progress
    // without taking the synchronized lock so the poller doesn't block on the refresh itself.
    private volatile String progressStage = "idle"; // idle | querying | comments | categorizing | done | error
    private volatile int progressEcnCount = 0;
    private volatile int progressBatchTotal = 0;
    private volatile int progressBatchDone = 0;
    private volatile long progressStartedAt = 0;
    private volatile String progressMessage = "";

    /** Default upper bound on days-over-target — matches the original 180-day age cap
     *  (180 - 10-day target = 170 days over target). End users can dial this lower or
     *  higher via the UI; we clamp to a sane ceiling to keep the SQL fast. */
    private static final int DEFAULT_MIN_OVER = 0;
    private static final int DEFAULT_MAX_OVER = 170;
    private static final int HARD_MAX_OVER = 3650;  // ~10 years; effectively unlimited

    public static final class OverdueRow {
        public String ecnNumber;
        public String productLine;
        public String productTeam;
        public String analyst;
        public String originator;
        public String priority;
        public String ecnClassification;
        public Integer actualDays;
        public Integer targetDays;
        public Integer daysOverTarget;
        public String currentStatus;
        public String createdDate;
        public String releaseDate;      // empty in open mode; ISO yyyy-MM-dd in retrospective
        public String commentChain;     // joined CCB comments
        public Integer commentCount;
        public String category;         // AI-assigned, one of CATEGORIES
        public String summary;          // optional AI-generated one-liner explaining why
    }

    public synchronized Map<String, Object> getData(Integer minOver, Integer maxOver,
                                                    String createdRange, String createdFrom, String createdTo,
                                                    String releasedRange, String releasedFrom, String releasedTo,
                                                    String classificationsCsv) {
        // PT-67: /data is a pure cache reader now. AI categorization is expensive
        // (1–3 min, Portkey calls), so it only runs on explicit POST /refresh.
        // We surface two flags so the UI can show the right empty / stale state.
        int min = clampMin(minOver);
        int max = clampMax(maxOver);
        String[] cr = resolveCreatedRange(createdRange, createdFrom, createdTo);
        String[] rr = resolveCreatedRange(releasedRange, releasedFrom, releasedTo);
        String classKey = normalizeClassificationsKey(classificationsCsv);
        // Normalize null → "" on both sides of the fingerprint comparison; the
        // older form ("null|null||...") never matched the stored fingerprint
        // ("||...") and forced a re-run every time. Empty windows are valid and
        // semantically distinct from a non-empty one — both must round-trip.
        String fingerprint = nz(cr[0]) + "|" + nz(cr[1])
                + "||" + nz(rr[0]) + "|" + nz(rr[1])
                + "||" + classKey;
        boolean filtersChanged = (min != lastMinOver) || (max != lastMaxOver)
                || !fingerprint.equals(lastCreatedFingerprint);
        Map<String, Object> out = assembleResponse();
        out.put("cacheEmpty", cache.isEmpty() && lastError.isEmpty());
        out.put("filtersChanged", filtersChanged && !cache.isEmpty());
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Back-compat: legacy callers without the classification filter. */
    public Map<String, Object> getData(Integer minOver, Integer maxOver,
                                       String createdRange, String createdFrom, String createdTo,
                                       String releasedRange, String releasedFrom, String releasedTo) {
        return getData(minOver, maxOver, createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo, null);
    }

    /** Back-compat: legacy callers without the date-released filter. */
    public Map<String, Object> getData(Integer minOver, Integer maxOver,
                                       String createdRange, String createdFrom, String createdTo) {
        return getData(minOver, maxOver, createdRange, createdFrom, createdTo, null, null, null, null);
    }

    /** Back-compat: legacy callers without any date filters. */
    public Map<String, Object> getData(Integer minOver, Integer maxOver) {
        return getData(minOver, maxOver, null, null, null, null, null, null, null);
    }

    /**
     * Refresh path: re-read the ECN JSON, filter to open standard out-of-target,
     * pull comment chains via SQL, send to Portkey in batches, replace the cache.
     *
     * @param minOver lower bound on days-over-target (inclusive of >, so >target+minOver).
     *                Null → 0 (default: anything overdue at all).
     * @param maxOver upper bound on days-over-target (inclusive). Null → 170 (matches the
     *                original 180-day age cap for back-compat).
     */
    public synchronized Map<String, Object> refresh(Integer minOver, Integer maxOver,
                                                    String createdRange, String createdFrom, String createdTo,
                                                    String releasedRange, String releasedFrom, String releasedTo,
                                                    String classificationsCsv) {
        int min = clampMin(minOver);
        int max = clampMax(maxOver);
        String[] cr = resolveCreatedRange(createdRange, createdFrom, createdTo);
        String createdFromIso = cr[0];
        String createdToIso = cr[1];
        String[] rr = resolveCreatedRange(releasedRange, releasedFrom, releasedTo);
        String releasedFromIso = rr[0];
        String releasedToIso = rr[1];
        boolean retrospective = (releasedFromIso != null || releasedToIso != null);
        Set<String> classifications = resolveClassifications(classificationsCsv);
        lastMinOver = min;
        lastMaxOver = max;
        lastCreatedFrom = createdFromIso == null ? "" : createdFromIso;
        lastCreatedTo = createdToIso == null ? "" : createdToIso;
        lastCreatedRange = createdRange == null ? "" : createdRange.trim();
        lastReleasedFrom = releasedFromIso == null ? "" : releasedFromIso;
        lastReleasedTo = releasedToIso == null ? "" : releasedToIso;
        lastReleasedRange = releasedRange == null ? "" : releasedRange.trim();
        lastClassifications = String.join(",", new TreeSet<>(classifications));
        lastCreatedFingerprint = lastCreatedFrom + "|" + lastCreatedTo
                + "||" + lastReleasedFrom + "|" + lastReleasedTo
                + "||" + lastClassifications;
        long t0 = System.currentTimeMillis();
        lastError = "";
        progressStartedAt = t0;
        progressStage = "querying";
        progressEcnCount = 0;
        progressBatchTotal = 0;
        progressBatchDone = 0;
        String createdMsg = (lastCreatedFrom.isEmpty() && lastCreatedTo.isEmpty()) ? ""
                : (", created " + lastCreatedFrom + " → " + lastCreatedTo);
        String releasedMsg = retrospective
                ? (", released " + lastReleasedFrom + " → " + lastReleasedTo)
                : "";
        String popDesc = (classifications.size() == 1)
                ? classifications.iterator().next()
                : (classifications.size() + " classifications");
        progressMessage = "Querying Agile for "
                + (retrospective ? "released " : "open overdue ")
                + popDesc
                + (retrospective ? " that were overdue at release time" : "")
                + " (" + min + "–" + max + " days over target" + createdMsg + releasedMsg + ")";
        Map<String, OverdueRow> next = new LinkedHashMap<>();

        List<OverdueRow> candidates = pickOverdueEcns(min, max, createdFromIso, createdToIso,
                                                     releasedFromIso, releasedToIso, classifications);
        lastEcnsFetched = candidates.size();
        progressEcnCount = candidates.size();
        if (candidates.isEmpty()) {
            cache = next;
            lastRunAt = nowIso();
            lastEcnsCategorized = 0;
            progressStage = "done";
            progressMessage = "No open out-of-target Standard ECNs found.";
            logger.info("[OVERDUE] no candidate ECNs (open + standard + over target)");
            return assembleResponse();
        }

        // Hydrate comment chains in a single batched query.
        progressStage = "comments";
        progressMessage = "Pulling CCB comment chains for " + candidates.size() + " ECNs";
        Map<String, String> chains = fetchCommentChains(candidates);
        for (OverdueRow r : candidates) {
            String chain = chains.getOrDefault(r.ecnNumber, "");
            r.commentChain = chain;
            r.commentCount = chain.isEmpty() ? 0 : chain.split("\n").length;
        }

        // Send chains to Portkey/Claude in batches. Categorization can fail — when
        // it does we fall back to "Other" so the row still appears in the table.
        int categorized = 0;
        if (portkeyClient.isEnabled()) {
            progressStage = "categorizing";
            progressBatchTotal = (candidates.size() + BATCH_SIZE - 1) / BATCH_SIZE;
            progressBatchDone = 0;
            progressMessage = "AI-categorizing " + candidates.size() + " ECNs in "
                    + progressBatchTotal + " batches";
            try {
                categorized = categorize(candidates);
            } catch (Exception e) {
                lastError = "Portkey categorization failed: " + e.getMessage();
                logger.warning("[OVERDUE] " + lastError);
            }
        } else {
            lastError = "Portkey gateway not configured — categories unavailable.";
        }
        lastEcnsCategorized = categorized;

        for (OverdueRow r : candidates) {
            if (r.category == null || r.category.isEmpty()) r.category = "Other";
            next.put(r.ecnNumber, r);
        }
        cache = next;
        lastRunAt = nowIso();
        long ms = System.currentTimeMillis() - t0;
        progressStage = "done";
        progressMessage = "Done — " + candidates.size() + " ECNs in " + (ms / 1000) + "s";
        logger.info("[OVERDUE] refresh complete — " + candidates.size() + " ECNs, "
                + categorized + " categorized in " + ms + " ms");
        return assembleResponse();
    }

    /** Back-compat refresh without the classification filter. */
    public Map<String, Object> refresh(Integer minOver, Integer maxOver,
                                       String createdRange, String createdFrom, String createdTo,
                                       String releasedRange, String releasedFrom, String releasedTo) {
        return refresh(minOver, maxOver, createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo, null);
    }

    /** Back-compat refresh signature without the date-released filter. */
    public Map<String, Object> refresh(Integer minOver, Integer maxOver,
                                       String createdRange, String createdFrom, String createdTo) {
        return refresh(minOver, maxOver, createdRange, createdFrom, createdTo, null, null, null, null);
    }

    /** Back-compat refresh signature without any date filters. */
    public Map<String, Object> refresh(Integer minOver, Integer maxOver) {
        return refresh(minOver, maxOver, null, null, null, null, null, null, null);
    }

    /**
     * Pull in-flight Standard ECNs from Oracle. The "Standard ECNs" classification
     * is NOT a column in any Agile table — it's a label assigned to specific
     * {@code (requestClassification | requestSubclass)} combinations in the
     * toolkit's Excel KPI template. The mapping lives in
     * {@code ecn_data.json#baselineTargets}, keyed by
     * {@code "<request_classification>|<request_subclass>"} →
     * {@code {ecnClassification: "Standard ECNs", ...}}. We read that map here,
     * pull the 39-ish Standard keys, and use them as the IN-list filter against
     * Oracle's PAGE_THREE.LIST35-derived classification.
     *
     * <p>Other filters: ECN subclass id, non-terminal status, current status not in
     * {@code app.overdue.excluded-statuses} (default {@code Pending}; PT-114 — open
     * mode only), submit_date set, elapsed days &gt; 10.
     */
    @SuppressWarnings("unchecked")
    private List<OverdueRow> pickOverdueEcns(int minOver, int maxOver,
                                             String createdFromIso, String createdToIso,
                                             String releasedFromIso, String releasedToIso,
                                             Set<String> classifications) {
        // 1. Pull the per-classification targets from baselineTargets and narrow
        //    to the set of rc_keys the caller asked for (defaults to Standard + PDR).
        ClassificationTargets ct = loadClassificationTargets();
        if (ct.isEmpty()) {
            lastError = "No baseline targets loaded — Cycle Time report data missing or empty.";
            return new ArrayList<>();
        }
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> e : ct.classByRcKey.entrySet()) {
            if (classifications.contains(e.getValue())) keys.add(e.getKey());
        }
        if (keys.isEmpty()) {
            lastError = "No baseline-target rows match the selected classifications: " + classifications;
            return new ArrayList<>();
        }

        // 2. Build the SQL. Oracle IN-list cap is 1000; 70 keys is well under
        //    so we run one query. Per-row target_days is computed via a CASE
        //    expression that groups rc_keys by their (target days, priority).
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        String targetCaseExpr = buildTargetCaseExpression(ct, keys);
        boolean retrospective = (releasedFromIso != null && !releasedFromIso.isEmpty())
                || (releasedToIso != null && !releasedToIso.isEmpty());

        // "Elapsed days at the moment we care about" — for open ECNs we measure
        // against today (still in flight); for released ECNs we measure against
        // their actual release_date (cycle time at release, frozen).
        String elapsedExpr = retrospective
                ? "TRUNC(c.release_date - c.submit_date)"
                : "TRUNC(SYSDATE - c.submit_date)";
        // Open mode keeps "not yet released / cancelled" filter. Retrospective drops
        // it and instead requires release_date to be present in the requested window.
        String openOrReleasedClause = retrospective
                ? "  AND c.release_date IS NOT NULL "
                : "  AND sn.name NOT IN ('Completed', 'Cancel', 'Cancelled') ";
        // PT-114: in open mode, drop ECNs whose CURRENT status is one Jimmy asked to
        // exclude (default: Pending). Kicked-back ECNs keep a stale submit_date, so
        // the submit_date gate below is not enough on its own. Parameterized so the
        // configurable status labels can never be a SQL-injection vector.
        List<String> excludedStatuses = new ArrayList<>();
        if (!retrospective && excludedStatusesCsv != null) {
            for (String s : excludedStatusesCsv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) excludedStatuses.add(t);
            }
        }
        String excludedStatusClause = excludedStatuses.isEmpty()
                ? ""
                : "  AND sn.name NOT IN (" + String.join(",", Collections.nCopies(excludedStatuses.size(), "?")) + ") ";
        String createdFromClause = (createdFromIso != null && !createdFromIso.isEmpty())
                ? "  AND c.submit_date >= TO_DATE(?, 'YYYY-MM-DD') " : "";
        String createdToClause   = (createdToIso   != null && !createdToIso.isEmpty())
                ? "  AND c.submit_date <  TO_DATE(?, 'YYYY-MM-DD') + 1 " : "";
        String releasedFromClause = (releasedFromIso != null && !releasedFromIso.isEmpty())
                ? "  AND c.release_date >= TO_DATE(?, 'YYYY-MM-DD') " : "";
        String releasedToClause   = (releasedToIso   != null && !releasedToIso.isEmpty())
                ? "  AND c.release_date <  TO_DATE(?, 'YYYY-MM-DD') + 1 " : "";

        String sql =
            "SELECT chg_num, cur_status, submit_date, release_date, actual_days, " +
            "       originator, analyst, pl_raw, priority, rc_key, target_days " +
            "FROM (SELECT c.change_number AS chg_num, " +
            "             sn.name AS cur_status, " +
            "             TO_CHAR(c.submit_date, 'YYYY-MM-DD') AS submit_date, " +
            "             TO_CHAR(c.release_date, 'YYYY-MM-DD') AS release_date, " +
            "             " + elapsedExpr + " AS actual_days, " +
            "             creator.last_name || ', ' || creator.first_name || ' (' || creator.loginid || ')' AS originator, " +
            "             analyst.last_name || ', ' || analyst.first_name || ' (' || analyst.loginid || ')' AS analyst, " +
            "             c.product_lines AS pl_raw, " +
            "             pri.entryvalue AS priority, " +
            "             rc_parent.entryvalue || '|' || rc.entryvalue AS rc_key, " +
            "             " + targetCaseExpr + " AS target_days " +
            "      FROM agile.change c " +
            "      JOIN agile.nodetable sn ON sn.id = c.status " +
            "      LEFT JOIN agile.agileuser creator ON creator.id = c.originator " +
            "      LEFT JOIN agile.agileuser analyst ON analyst.id = c.owner " +
            "      LEFT JOIN agile.listentry pri ON pri.entryid = c.category AND pri.langid = 0 " +
            "      LEFT JOIN agile.page_three p3 ON p3.id = c.id AND p3.class = c.class " +
            "      LEFT JOIN agile.listentry rc ON rc.entryid = p3.list35 AND rc.langid = 0 AND rc.parent_entry IS NOT NULL " +
            "      LEFT JOIN agile.listentry rc_parent ON rc_parent.entryid = rc.parent_entry AND rc_parent.langid = 0 " +
            "        AND rc_parent.parentid = 251739711 " +
            "      WHERE c.change_number LIKE 'ECN-%' " +
            "        AND c.subclass = 251739700 " +
            "        AND (c.delete_flag = 0 OR c.delete_flag IS NULL) " +
            "      " + openOrReleasedClause +
            "      " + excludedStatusClause +
            "        AND c.submit_date IS NOT NULL " +
            "      " + createdFromClause +
            "      " + createdToClause +
            "      " + releasedFromClause +
            "      " + releasedToClause +
            "        AND (rc_parent.entryvalue || '|' || rc.entryvalue) IN (" + placeholders + ")) " +
            "WHERE actual_days > target_days + ? " +
            "  AND actual_days <= target_days + ? " +
            "ORDER BY (actual_days - target_days) DESC " +
            "FETCH FIRST 100 ROWS ONLY";

        List<OverdueRow> out = new ArrayList<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            int idx = 1;
            // Parameter order must mirror placeholder order in the SQL: excluded-status
            // NOT-IN (open mode only) first, then the date filters, then the rc_key
            // IN-list, then the over-target window (min, max).
            for (String s : excludedStatuses) ps.setString(idx++, s);
            if (createdFromIso  != null && !createdFromIso.isEmpty())  ps.setString(idx++, createdFromIso);
            if (createdToIso    != null && !createdToIso.isEmpty())    ps.setString(idx++, createdToIso);
            if (releasedFromIso != null && !releasedFromIso.isEmpty()) ps.setString(idx++, releasedFromIso);
            if (releasedToIso   != null && !releasedToIso.isEmpty())   ps.setString(idx++, releasedToIso);
            for (String k : keys) ps.setString(idx++, k);
            ps.setInt(idx++, minOver);
            ps.setInt(idx++, maxOver);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OverdueRow r = new OverdueRow();
                    r.ecnNumber = rs.getString("chg_num");
                    r.currentStatus = rs.getString("cur_status");
                    r.createdDate = rs.getString("submit_date");
                    r.releaseDate = rs.getString("release_date");
                    r.actualDays = (int) rs.getLong("actual_days");
                    r.targetDays = (int) rs.getLong("target_days");
                    r.daysOverTarget = r.actualDays - r.targetDays;
                    r.originator = stripIdSuffix(rs.getString("originator"));
                    r.analyst = stripIdSuffix(rs.getString("analyst"));
                    r.priority = rs.getString("priority");
                    r.productLine = resolveProductLines(rs.getString("pl_raw"));
                    r.productTeam = resolveProductTeam(r.ecnNumber, r.productLine);
                    r.ecnClassification = ct.classByRcKey.getOrDefault(rs.getString("rc_key"), "");
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            logger.warning("[OVERDUE] pickOverdueEcns SQL failed: " + e.getMessage());
            lastError = "Pick query failed: " + e.getMessage();
        }
        return out;
    }

    /** "Last, First (12345)" → "Last, First" — strips the login-ID suffix that
     *  the rejection script also drops on display. */
    private static String stripIdSuffix(String name) {
        if (name == null) return "";
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }

    /**
     * PT-94 (Jimmy, 2026-06-12): Resolve Product Team using the same 3-tier
     * fallback the Returns Tracker uses (see
     * {@link RejectionTrackerService#PRODUCT_LINE_TEAM_MAP} for the canonical
     * Product Line → Team table):
     * <ol>
     *   <li>{@code teamOverride} annotation on the ECN (admin-set on the
     *       Cycle Time data table)</li>
     *   <li>{@code productTeam} pre-resolved on the ECN row in
     *       {@code ecn_data.json} (same source the Cycle Time tab reads)</li>
     *   <li>Static {@code PRODUCT_LINE_TEAM_MAP}, applied to each segment of
     *       the human-readable product-line string. Note this string is joined
     *       with {@code " · "} (see {@link #resolveProductLines}), unlike the
     *       Returns Tracker which uses {@code ";"} — we split on both so the
     *       same map works in both contexts.</li>
     * </ol>
     * Returns the empty string when no source matches; the caller renders that
     * as "Unknown" in aggregates and as blank in the row table.
     */
    @SuppressWarnings("unchecked")
    private String resolveProductTeam(String ecnNumber, String productLine) {
        if (ecnReportService != null && ecnNumber != null && !ecnNumber.isEmpty()) {
            // (1) Admin override
            Map<String, Object> annotations = ecnReportService.getAnnotations();
            if (annotations != null) {
                Object ann = annotations.get(ecnNumber);
                if (ann instanceof Map) {
                    Object t = ((Map<String, Object>) ann).get("teamOverride");
                    if (t != null) {
                        String s = t.toString().trim();
                        if (!s.isEmpty()) return s;
                    }
                }
            }
            // (2) ecn_data.json productTeam
            Map<String, Map<String, Object>> lookup = ecnReportService.getEcnLookup();
            if (lookup != null) {
                Map<String, Object> row = lookup.get(ecnNumber);
                if (row != null) {
                    Object pt = row.get("productTeam");
                    if (pt != null) {
                        String s = pt.toString().trim();
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        }
        // (3) Static PRODUCT_LINE_TEAM_MAP. Split on both " · " (Overdue
        // Tracker's join char from resolveProductLines) and ";" (Returns
        // Tracker's join char from upstream) so the map keys match.
        if (productLine != null && !productLine.isEmpty()) {
            java.util.TreeSet<String> teams = new java.util.TreeSet<>();
            for (String seg : productLine.split("\\s+·\\s+|;")) {
                String key = seg.trim();
                if (key.isEmpty()) continue;
                String t = RejectionTrackerService.PRODUCT_LINE_TEAM_MAP.get(key);
                if (t != null) teams.add(t);
            }
            if (!teams.isEmpty()) return String.join("; ", teams);
        }
        return "";
    }

    /** Resolve {@code c.product_lines} (CSV of LISTENTRY IDs wrapped in commas,
     *  e.g. {@code ",3312750,3280226,"}) into a human-readable product-line list
     *  by looking each ID up in {@code agile.listentry} under parent 291. Joins
     *  multiple names with {@code " · "} so the column stays compact even when
     *  an ECN spans several product lines. */
    private String resolveProductLines(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        ensureProductLineLookup();
        List<String> names = new ArrayList<>();
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try {
                String name = productLineLookup.get(Long.parseLong(t));
                if (name != null && !name.isEmpty()) names.add(name);
            } catch (NumberFormatException ignored) {}
        }
        return String.join(" · ", names);
    }

    private synchronized void ensureProductLineLookup() {
        if (productLineLookupLoaded) return;
        String sql = "SELECT entryid, entryvalue FROM agile.listentry " +
                     "WHERE langid = 0 AND parentid = 291";
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ps.setQueryTimeout(30);
            while (rs.next()) {
                productLineLookup.put(rs.getLong("entryid"), rs.getString("entryvalue"));
            }
            logger.info("[OVERDUE] loaded " + productLineLookup.size() + " product-line entries");
        } catch (SQLException e) {
            logger.warning("[OVERDUE] productLineLookup load failed: " + e.getMessage());
        }
        productLineLookupLoaded = true;
    }

    /**
     * Pulls every {@code change_history.comments} row for the given ECN numbers in
     * one batched IN-list query. Concatenates per ECN as {@code "[timestamp] user: cmt"}
     * lines, capped at the most recent 12 comments per ECN.
     */
    private Map<String, String> fetchCommentChains(List<OverdueRow> rows) {
        Map<String, String> out = new HashMap<>();
        if (rows.isEmpty()) return out;
        List<String> ecnNumbers = new ArrayList<>();
        for (OverdueRow r : rows) if (r.ecnNumber != null && !r.ecnNumber.isEmpty()) ecnNumbers.add(r.ecnNumber);
        if (ecnNumbers.isEmpty()) return out;

        // Oracle IN-list cap. ECN counts are typically <100 so chunking is defensive.
        int CHUNK = 1000;
        Map<String, List<String>> perEcn = new HashMap<>();
        for (int start = 0; start < ecnNumbers.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, ecnNumbers.size());
            List<String> batch = ecnNumbers.subList(start, end);
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql =
                "SELECT c.change_number AS chg_num, " +
                "       TO_CHAR(ch.local_date, 'YYYY-MM-DD HH24:MI') AS ts, " +
                "       ch.user_name AS user_name, " +
                "       REPLACE(REPLACE(SUBSTR(ch.comments, 1, 1000), CHR(10), ' '), CHR(13), '') AS cmt " +
                "FROM agile.change c " +
                "JOIN agile.change_history ch ON ch.change_id = c.id " +
                "WHERE c.change_number IN (" + placeholders + ") " +
                "AND ch.comments IS NOT NULL " +
                "AND ch.event_type IN (14, 15, 17, 65) " +
                "ORDER BY c.change_number, ch.timestamp";
            try (Connection conn = agileDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(60);
                int i = 1;
                for (String n : batch) ps.setString(i++, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String num = rs.getString("chg_num");
                        String line = "[" + nvl(rs.getString("ts")) + "] " + nvl(rs.getString("user_name"))
                                + ": " + nvl(rs.getString("cmt"));
                        perEcn.computeIfAbsent(num, k -> new ArrayList<>()).add(line);
                    }
                }
            } catch (SQLException e) {
                logger.warning("[OVERDUE] comment chain SQL failed: " + e.getMessage());
            }
        }
        for (Map.Entry<String, List<String>> e : perEcn.entrySet()) {
            List<String> lines = e.getValue();
            // Cap at the last 12 to keep the prompt size sane.
            int from = Math.max(0, lines.size() - 12);
            out.put(e.getKey(), String.join("\n", lines.subList(from, lines.size())));
        }
        return out;
    }

    private int categorize(List<OverdueRow> rows) throws Exception {
        int categorized = 0;
        String catsCsv = String.join(", ", CATEGORIES);

        // Build per-ECN context once, then batch into chunks of BATCH_SIZE.
        List<Map<String, String>> ctx = new ArrayList<>();
        for (OverdueRow r : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("ecn", r.ecnNumber);
            m.put("classification", str(r.ecnClassification));
            m.put("priority", str(r.priority));
            m.put("days", String.valueOf(r.actualDays));
            m.put("targetDays", String.valueOf(r.targetDays));
            m.put("overTarget", String.valueOf(r.daysOverTarget));
            m.put("status", str(r.currentStatus));
            m.put("chain", r.commentChain == null ? "(no CCB comments)" : r.commentChain);
            ctx.add(m);
        }

        for (int start = 0; start < ctx.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, ctx.size());
            List<Map<String, String>> batch = ctx.subList(start, end);
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                Map<String, String> b = batch.get(i);
                items.append("ITEM ").append(i + 1).append(":\n")
                     .append("ECN: ").append(b.get("ecn")).append(" (").append(b.get("classification"))
                     .append(", ").append(b.get("priority")).append(", at status: ").append(b.get("status"))
                     .append(", ").append(b.get("days")).append("d / target ").append(b.get("targetDays"))
                     .append("d, ").append(b.get("overTarget")).append("d over target)\n")
                     .append("CCB Comment Chain:\n").append(b.get("chain")).append("\n---\n");
            }
            String prompt =
                "Categorize each Agile PLM overdue Standard ECN below into exactly ONE of these categories:\n" +
                catsCsv + "\n\n" +
                "Rules:\n" +
                "- Use the CCB comment chain to infer the dominant reason this change is sitting past target\n" +
                "- Reply with ONLY a JSON array of objects: [{\"ecn\":\"ECN-123\",\"category\":\"CCB / Approval Delay\",\"summary\":\"<≤15 word reason>\"}]\n" +
                "- One entry per ITEM, in order\n" +
                "- No explanation, no markdown, just the JSON array\n\n" +
                items.toString();

            String raw = portkeyClient.chat(MODEL,
                    "You are a classification assistant. Respond only with valid JSON.",
                    prompt, 2000);
            categorized += applyClassifications(raw, batch, rows);
            progressBatchDone++;
        }
        return categorized;
    }

    /**
     * Build an Excel workbook of the cached rows. Ensures the cache matches the
     * requested filters first (re-runs the fetch + AI categorize if not). One
     * sheet, columns mirror the on-screen table plus Created Date + Comment Chain
     * so the recipient can inspect why each ECN landed in its category.
     */
    public void buildExcel(OutputStream out,
                           Integer minOver, Integer maxOver,
                           String createdRange, String createdFrom, String createdTo,
                           String releasedRange, String releasedFrom, String releasedTo,
                           String classificationsCsv) throws IOException {
        getData(minOver, maxOver, createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo, classificationsCsv);
        List<OverdueRow> rows = new ArrayList<>(cache.values());
        writeExcel(out, rows);
    }

    /** Back-compat for callers without the classification filter. */
    public void buildExcel(OutputStream out, Integer minOver, Integer maxOver,
                           String createdRange, String createdFrom, String createdTo,
                           String releasedRange, String releasedFrom, String releasedTo) throws IOException {
        buildExcel(out, minOver, maxOver, createdRange, createdFrom, createdTo,
                releasedRange, releasedFrom, releasedTo, null);
    }

    /** Back-compat for callers without the date-released filter. */
    public void buildExcel(OutputStream out, Integer minOver, Integer maxOver,
                           String createdRange, String createdFrom, String createdTo) throws IOException {
        buildExcel(out, minOver, maxOver, createdRange, createdFrom, createdTo, null, null, null, null);
    }

    private void writeExcel(OutputStream out, List<OverdueRow> rows) throws IOException {
        String[] headers = {
            "ECN", "Classification", "Product Line", "Team", "Analyst", "Priority",
            "Actual Days", "Target Days", "Over Target", "Status", "Created Date",
            "Release Date", "AI Category", "AI Summary", "Comment Chain"
        };
        SXSSFWorkbook wb = new SXSSFWorkbook(null, 500, false, false);
        try {
            Sheet sheet = wb.createSheet("Overdue Tracker");

            CellStyle hdr = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            hdr.setFont(hf);
            hdr.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            hdr.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle alt = wb.createCellStyle();
            alt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            alt.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(hdr);
            }

            int r = 1;
            for (OverdueRow row : rows) {
                Row x = sheet.createRow(r);
                x.createCell(0).setCellValue(nvl(row.ecnNumber));
                x.createCell(1).setCellValue(nvl(row.ecnClassification));
                x.createCell(2).setCellValue(nvl(row.productLine));
                x.createCell(3).setCellValue(nvl(row.productTeam));
                x.createCell(4).setCellValue(nvl(row.analyst));
                x.createCell(5).setCellValue(nvl(row.priority));
                x.createCell(6).setCellValue(row.actualDays == null ? 0 : row.actualDays);
                x.createCell(7).setCellValue(row.targetDays == null ? 0 : row.targetDays);
                x.createCell(8).setCellValue(row.daysOverTarget == null ? 0 : row.daysOverTarget);
                x.createCell(9).setCellValue(nvl(row.currentStatus));
                x.createCell(10).setCellValue(nvl(row.createdDate));
                x.createCell(11).setCellValue(nvl(row.releaseDate));
                x.createCell(12).setCellValue(nvl(row.category));
                x.createCell(13).setCellValue(nvl(row.summary));
                x.createCell(14).setCellValue(nvl(row.commentChain));
                if (r % 2 == 0) {
                    for (int c = 0; c < headers.length; c++) x.getCell(c).setCellStyle(alt);
                }
                r++;
            }

            int[] widths = { 3500, 5500, 5500, 4500, 4500, 3000, 3000, 3000, 3000, 3500, 3500, 3500, 5500, 8000, 16000 };
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
            sheet.createFreezePane(0, 1);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
            }
            wb.write(out);
        } finally {
            wb.close();
            wb.dispose();
        }
    }

    /** Lightweight live-progress snapshot for the in-flight refresh. Not synchronized
     *  so the poller doesn't block on the refresh that owns the service monitor. */
    public Map<String, Object> getProgress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", progressStage);
        m.put("ecnCount", progressEcnCount);
        m.put("batchTotal", progressBatchTotal);
        m.put("batchDone", progressBatchDone);
        m.put("message", progressMessage);
        long started = progressStartedAt;
        m.put("elapsedMs", started == 0 ? 0 : (System.currentTimeMillis() - started));
        return m;
    }

    @SuppressWarnings("unchecked")
    private int applyClassifications(String raw, List<Map<String, String>> batch, List<OverdueRow> allRows) {
        if (raw == null) return 0;
        String json = extractJsonArray(raw);
        if (json == null) {
            logger.warning("[OVERDUE] no JSON array in AI reply: " + raw.substring(0, Math.min(120, raw.length())));
            return 0;
        }
        try {
            List<Map<String, Object>> arr = mapper.readValue(json, List.class);
            Map<String, OverdueRow> byNum = new HashMap<>();
            for (OverdueRow r : allRows) byNum.put(r.ecnNumber, r);
            int n = 0;
            for (Map<String, Object> item : arr) {
                String ecn = str(item.get("ecn"));
                String cat = str(item.get("category"));
                String summary = str(item.get("summary"));
                OverdueRow r = byNum.get(ecn);
                if (r == null) continue;
                // Normalize: must be one of the known categories; otherwise → Other.
                String matched = CATEGORIES.stream()
                        .filter(c -> c.equalsIgnoreCase(cat))
                        .findFirst().orElse("Other");
                r.category = matched;
                r.summary = summary;
                n++;
            }
            return n;
        } catch (Exception e) {
            logger.warning("[OVERDUE] failed to parse AI reply: " + e.getMessage());
            return 0;
        }
    }

    private static String extractJsonArray(String raw) {
        int lb = raw.indexOf('[');
        int rb = raw.lastIndexOf(']');
        if (lb < 0 || rb < 0 || rb <= lb) return null;
        return raw.substring(lb, rb + 1);
    }

    private Map<String, Object> assembleResponse() {
        List<OverdueRow> rows = new ArrayList<>(cache.values());
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (String c : CATEGORIES) categoryCounts.put(c, 0);
        Map<String, Integer> teamCounts = new HashMap<>();
        Map<String, Integer> lineCounts = new HashMap<>();
        Map<String, Integer> classCounts = new LinkedHashMap<>();
        int totalOverTarget = 0;
        for (OverdueRow r : rows) {
            categoryCounts.merge(r.category == null ? "Other" : r.category, 1, Integer::sum);
            if (r.productTeam != null && !r.productTeam.isEmpty())
                teamCounts.merge(r.productTeam, 1, Integer::sum);
            if (r.productLine != null && !r.productLine.isEmpty())
                lineCounts.merge(r.productLine, 1, Integer::sum);
            if (r.ecnClassification != null && !r.ecnClassification.isEmpty())
                classCounts.merge(r.ecnClassification, 1, Integer::sum);
            if (r.daysOverTarget != null) totalOverTarget += r.daysOverTarget;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("totalDaysOverTarget", totalOverTarget);
        out.put("avgDaysOverTarget", rows.isEmpty() ? 0 : Math.round((double) totalOverTarget / rows.size() * 10.0) / 10.0);
        out.put("categoryCounts", categoryCounts);
        out.put("classificationCounts", classCounts);
        out.put("topTeams", topN(teamCounts, 10));
        out.put("topProductLines", topN(lineCounts, 10));
        out.put("lastRunAt", lastRunAt);
        out.put("ecnsFetched", lastEcnsFetched);
        out.put("ecnsCategorized", lastEcnsCategorized);
        out.put("categories", CATEGORIES);
        out.put("minOver", lastMinOver);
        out.put("maxOver", lastMaxOver);
        // Per-classification targets are surfaced via row.targetDays now;
        // for back-compat in UI code we still emit the Standard default of 10.
        out.put("targetDays", 10);
        out.put("classifications", lastClassifications);
        out.put("availableClassifications", new ArrayList<>(loadClassificationTargets().allClassifications));
        out.put("defaultClassifications", new ArrayList<>(DEFAULT_CLASSIFICATIONS));
        out.put("createdRange", lastCreatedRange);
        out.put("createdFrom", lastCreatedFrom);
        out.put("createdTo", lastCreatedTo);
        out.put("releasedRange", lastReleasedRange);
        out.put("releasedFrom", lastReleasedFrom);
        out.put("releasedTo", lastReleasedTo);
        boolean retrospective = !lastReleasedFrom.isEmpty() || !lastReleasedTo.isEmpty();
        out.put("mode", retrospective ? "retrospective" : "open");
        if (!lastError.isEmpty()) out.put("error", lastError);
        return out;
    }

    private static List<Map<String, Object>> topN(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", list.get(i).getKey());
            r.put("count", list.get(i).getValue());
            out.add(r);
        }
        return out;
    }

    private static int clampMin(Integer v) {
        if (v == null) return DEFAULT_MIN_OVER;
        return Math.max(0, v);
    }
    private static int clampMax(Integer v) {
        if (v == null) return DEFAULT_MAX_OVER;
        return Math.min(HARD_MAX_OVER, Math.max(1, v));
    }

    /** Resolve a Date Created preset/custom into a concrete (fromIso, toIso) pair.
     *  Returns {@code [null, null]} when no constraint is requested. Re-resolves
     *  presets on every call so the rolling window tracks the current date. */
    static String[] resolveCreatedRange(String range, String fromIso, String toIso) {
        String r = range == null ? "" : range.trim().toLowerCase();
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter F = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        switch (r) {
            case "last7d":
                return new String[]{ today.minusDays(7).format(F), today.format(F) };
            case "last30d":
                return new String[]{ today.minusDays(30).format(F), today.format(F) };
            case "ytd":
                return new String[]{ today.withDayOfYear(1).format(F), today.format(F) };
            case "custom":
                String f = (fromIso == null || fromIso.trim().isEmpty()) ? null : fromIso.trim();
                String t = (toIso   == null || toIso.trim().isEmpty())   ? null : toIso.trim();
                return new String[]{ f, t };
            case "":
            case "none":
            case "all":
            default:
                return new String[]{ null, null };
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String nvl(String s) { return s == null ? "" : s; }
    private static String nowIso() {
        return java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
    }

    /** Reload the classification → (standard, urgent) target lookup from
     *  baselineTargets every time we run a query. Keeps the source-of-truth
     *  honest if the underlying KPI template is regenerated mid-day. */
    @SuppressWarnings("unchecked")
    private ClassificationTargets loadClassificationTargets() {
        ClassificationTargets out = new ClassificationTargets();
        try {
            Map<String, Object> data = ecnReportService.getEcnData();
            Object bt = data.get("baselineTargets");
            if (!(bt instanceof Map)) return out;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) bt).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                String cls = str(v.get("ecnClassification"));
                if (cls.isEmpty()) continue;
                int std = parseTargetDays(v.get("standard"));
                int urg = parseTargetDays(v.get("urgent"));
                out.classByRcKey.put(e.getKey(), cls);
                out.standardByRcKey.put(e.getKey(), std);
                out.urgentByRcKey.put(e.getKey(), urg);
                out.allClassifications.add(cls);
            }
        } catch (Exception e) {
            logger.warning("[OVERDUE] baselineTargets load failed: " + e.getMessage());
        }
        return out;
    }

    /** Translates a baselineTargets entry into an integer target-days value.
     *  Numbers pass through; "NA" or missing → fallback (30 days, configurable). */
    private static int parseTargetDays(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return NO_SLA_FALLBACK_TARGET_DAYS;
        String s = v.toString().trim();
        if (s.isEmpty() || "NA".equalsIgnoreCase(s)) return NO_SLA_FALLBACK_TARGET_DAYS;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return NO_SLA_FALLBACK_TARGET_DAYS; }
    }

    /** Builds an Oracle CASE expression that maps each rc_key + priority to its
     *  target_days. Groups keys by (target days, isUrgent) so each unique target
     *  becomes one WHEN branch — keeps the expression compact even with 70+ keys. */
    private static String buildTargetCaseExpression(ClassificationTargets ct, List<String> activeKeys) {
        Set<String> active = new HashSet<>(activeKeys);
        // Group active keys by their (standardTarget, urgentTarget) tuple.
        Map<Integer, List<String>> byStd = new LinkedHashMap<>();
        Map<Integer, List<String>> byUrg = new LinkedHashMap<>();
        for (String k : activeKeys) {
            if (!active.contains(k)) continue;
            int s = ct.standardByRcKey.getOrDefault(k, NO_SLA_FALLBACK_TARGET_DAYS);
            int u = ct.urgentByRcKey.getOrDefault(k, NO_SLA_FALLBACK_TARGET_DAYS);
            byStd.computeIfAbsent(s, x -> new ArrayList<>()).add(k);
            byUrg.computeIfAbsent(u, x -> new ArrayList<>()).add(k);
        }
        StringBuilder sb = new StringBuilder("CASE ");
        // Urgent-priority branches first (more specific).
        for (Map.Entry<Integer, List<String>> e : byUrg.entrySet()) {
            sb.append("WHEN UPPER(NVL(pri.entryvalue,'')) = 'URGENT' AND ")
              .append("(rc_parent.entryvalue || '|' || rc.entryvalue) IN (")
              .append(sqlLiteralList(e.getValue())).append(") THEN ").append(e.getKey()).append(" ");
        }
        // Standard-priority branches.
        for (Map.Entry<Integer, List<String>> e : byStd.entrySet()) {
            sb.append("WHEN (rc_parent.entryvalue || '|' || rc.entryvalue) IN (")
              .append(sqlLiteralList(e.getValue())).append(") THEN ").append(e.getKey()).append(" ");
        }
        sb.append("ELSE ").append(NO_SLA_FALLBACK_TARGET_DAYS).append(" END");
        return sb.toString();
    }

    /** Render a list of rc_keys as Oracle string literals. Keys come from
     *  baselineTargets (admin-controlled) and never contain a single quote in
     *  the data we've seen, but we still escape defensively. */
    private static String sqlLiteralList(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(keys.get(i).replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    /** Resolve a CSV / null classifications parameter into a concrete Set. */
    private static Set<String> resolveClassifications(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new LinkedHashSet<>(DEFAULT_CLASSIFICATIONS);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? new LinkedHashSet<>(DEFAULT_CLASSIFICATIONS) : out;
    }

    /** Normalize a classifications param into a deterministic cache-key string. */
    private static String normalizeClassificationsKey(String csv) {
        TreeSet<String> sorted = new TreeSet<>(resolveClassifications(csv));
        return String.join(",", sorted);
    }
}
