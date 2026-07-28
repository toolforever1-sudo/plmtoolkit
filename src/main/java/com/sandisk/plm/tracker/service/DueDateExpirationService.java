package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Backs the <em>Due Date Expiration</em> view under the ECN Dashboard tab.
 *
 * <p>In-app, cached replica of the daily <em>ECN Due Date Expiration</em> email
 * (see {@code ~/git/ecnreports/.../ECNDueDateExpirationNotification.java}). Instead
 * of the Agile SDK + a Global Search, it reproduces the population as direct SQL
 * against {@code agileDataSource} — the same source the Overdue / Cycle Time views
 * use — so it runs entirely inside the toolkit with no SDK dependency.
 *
 * <p><b>Population (verified against the live 07-13-26 email, 14 ECNs):</b>
 * in-flight ECNs — {@code subclass = 251739700}, {@code submit_date} set, current
 * status not in the excluded set (Pending / Cancel / Completed / Void / <b>Hold</b>)
 * — whose <b>Target Due Date</b> ({@code page_three.DATE31}, attribute node
 * 251739706 "TargetDueDate", i.e. the report's {@code ecn.duedate.baseID}) is
 * overdue or due within one calendar day. The operative in-flight status is
 * <b>Release</b> (the CCB / approval release routing) — note these carry a
 * {@code release_date} yet are NOT done, so we must NOT filter on
 * {@code release_date IS NULL} (that was the bug that showed only paused Hold
 * ECNs). Hold is paused, so it is excluded to match the email.
 * Project / IT-enhancement ECNs ({@code -PROJ}) are excluded by default to match
 * the email; a UI toggle re-includes them for the IT view.
 *
 * <p><b>Comments / Notes</b> come from the latest {@code change_history.comments}
 * row with {@code event_type IN (14,15,17,65)} — the same CCB-comment source
 * {@link OverdueTrackerService} documents for this email's Comments column.
 */
@Service
public class DueDateExpirationService {

    private static final Logger logger = Logger.getLogger(DueDateExpirationService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String CACHE_FILE = DATA_DIR + "/due-date-expiration-cache.json";
    private static final String SNAPSHOT_FILE = DATA_DIR + "/due-date-expiration-snapshots.json";

    /** ECN subclass — same constant the Overdue Tracker filters on. */
    private static final long ECN_SUBCLASS = 251739700L;

    private final DataSource agileDataSource;

    public DueDateExpirationService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    /** Current-status names excluded from the in-flight population. Comma-separated,
     *  case-sensitive to match {@code agile.nodetable.name}. */
    @Value("${app.duedate.excluded-statuses:Pending,Cancel,Cancelled,Canceled,Completed,Void,Hold}")
    private String excludedStatusesCsv;

    /** Exclude {@code -PROJ} (IT-enhancement / project) ECNs by default so the view
     *  matches the operational email. The UI toggle overrides per-request. */
    @Value("${app.duedate.exclude-proj:true}")
    private boolean excludeProjDefault;

    /** Target working-day windows shown in the header legend (display only). */
    @Value("${app.duedate.target-standard-days:10}")
    private int targetStandardDays;
    @Value("${app.duedate.target-urgent-days:6}")
    private int targetUrgentDays;

    /** Forward due-histogram horizon in calendar days. */
    @Value("${app.duedate.forward-days:28}")
    private int forwardDays;

    /** Base PLM URL for clickable ECN links. */
    @Value("${app.duedate.plm-baseurl:https://plm.sandisk.com}")
    private String plmBaseUrl;

    // --- The saved-search's other exclusions (mirrors /Global Searches/ECNReports/
    //     ECNDueDateExpireReport, confirmed from the Agile UI 2026-07-13) ---
    /** Product Line(s) "Not Equal To" values in the saved search (comma-separated,
     *  exact listentry names). Resolved to product-line ids at query time. */
    @Value("${app.duedate.exclude-product-lines:9001 - EVB ENGG,N/A}")
    private String excludeProductLinesCsv;
    /** "Cover Page.Number Does Not Contain" value (the report drops CCB-routed ECNs). */
    @Value("${app.duedate.exclude-number-contains:CCB}")
    private String excludeNumberContains;
    /** The report's "Request Classification Not In {Project Request|…}" exclusion is
     *  applied by parent classification. Blank disables it. The "Include IT" toggle
     *  lifts this per-request. */
    @Value("${app.duedate.project-classification-parent:Project Request}")
    private String projectClassificationParent;

    /** Payload schema version. Bump when the payload shape changes so a stale
     *  on-disk cache from an older build is discarded instead of served (#11). */
    private static final int CACHE_VERSION = 4;

    // ---- in-memory cache, keyed by the includeProj flag. ConcurrentHashMap so
    //      /data reads are lock-free and never block behind an in-flight refresh (#2). ----
    private final Map<Boolean, Map<String, Object>> payloadCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Serializes refreshes only (the DB work) — readers don't touch it. */
    private final Object refreshLock = new Object();
    private volatile String lastRunAt = "";
    private volatile String lastError = "";

    // =====================================================================
    // Public API
    // =====================================================================

    /** Serve cached payload lock-free; on a cold miss run live. */
    public Map<String, Object> getData(Boolean includeProjParam) {
        boolean includeProj = includeProjParam != null ? includeProjParam : !excludeProjDefault;
        Map<String, Object> cached = payloadCache.get(includeProj);
        if (cached != null) return cached;
        // Try the persisted operational cache first (survives restarts).
        if (!includeProj) {
            Map<String, Object> fromFile = readCacheFile();
            if (fromFile != null) {
                payloadCache.put(false, fromFile);
                return fromFile;
            }
        }
        return refresh(includeProj);
    }

    /** Re-run the query, rebuild the payload, update caches, and (for the
     *  operational variant) persist the cache file + append today's snapshot.
     *  Only the refresh path holds {@link #refreshLock}; cached reads never do. */
    public Map<String, Object> refresh(Boolean includeProjParam) {
        boolean includeProj = includeProjParam != null ? includeProjParam : !excludeProjDefault;
        synchronized (refreshLock) {
            lastError = "";
            Map<String, Object> payload;
            try {
                List<DueRow> rows = queryPopulation(includeProj);
                fetchNotes(rows);
                List<DueRow> forwardRows = queryForwardRows(includeProj);
                payload = buildPayload(rows, forwardRows, includeProj);
            } catch (Exception e) {
                logger.warning("[DUEDATE] refresh failed: " + e.getMessage());
                lastError = e.getMessage();
                payload = new LinkedHashMap<>();
                payload.put("error", "Query failed: " + e.getMessage());
                payload.put("rows", new ArrayList<>());
                payload.put("summary", emptySummary());
                payload.put("perAnalyst", new ArrayList<>());
                payload.put("forwardHistogram", new ArrayList<>());
                payload.put("includeProj", includeProj);
                return payload;
            }
            lastRunAt = LocalDate.now() + " " + java.time.LocalTime.now().withNano(0);
            payload.put("lastRunAt", lastRunAt);
            payload.put("cacheVersion", CACHE_VERSION);
            payloadCache.put(includeProj, payload);
            if (!includeProj) {
                writeCacheFile(payload);
                writeSnapshot(payload, false); // manual/first-write-wins per day (#9)
            }
            return payload;
        }
    }

    /** Snapshot series for the backlog-over-time chart. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTrend() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> series = new ArrayList<>();
        try {
            File f = new File(SNAPSHOT_FILE);
            if (f.exists()) {
                Map<String, Object> raw = mapper.readValue(f, Map.class);
                // stored as { "yyyy-MM-dd": {critical,overdue,dueSoon,total}, ... }
                TreeMap<String, Object> sorted = new TreeMap<>(raw);
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("date", e.getKey());
                    Map<String, Object> v = (Map<String, Object>) e.getValue();
                    pt.put("critical", intVal(v.get("critical")));
                    pt.put("overdue", intVal(v.get("overdue")));
                    pt.put("dueSoon", intVal(v.get("dueSoon")));
                    pt.put("total", intVal(v.get("total")));
                    series.add(pt);
                }
            }
        } catch (Exception e) {
            logger.warning("[DUEDATE] getTrend failed: " + e.getMessage());
            out.put("error", e.getMessage());
        }
        out.put("series", series);
        return out;
    }

    public String getLastError() { return lastError; }

    /** Scheduled daily refresh — writes the <em>authoritative</em> snapshot for the
     *  day (overwrites any earlier manual same-day point so 6 AM is canonical). */
    public Map<String, Object> scheduledRefresh() {
        synchronized (refreshLock) {
            Map<String, Object> payload = refresh(Boolean.FALSE);
            if (!payload.containsKey("error")) writeSnapshot(payload, true);
            return payload;
        }
    }

    // =====================================================================
    // Query
    // =====================================================================

    private List<DueRow> queryPopulation(boolean includeProj) throws SQLException {
        List<String> params = new ArrayList<>();
        String filters = buildEmailFilters(includeProj, params);

        String sql =
            "SELECT c.change_number AS chg_num, " +
            "       sn.name AS cur_status, " +
            "       TO_CHAR(p3.date31, 'YYYY-MM-DD') AS due_iso, " +
            "       analyst.last_name || ', ' || analyst.first_name AS analyst, " +
            "       pri.entryvalue AS priority, " +
            "       c.product_lines AS pl_raw, " +
            "       SUBSTR(REPLACE(REPLACE(c.description, CHR(10), ' '), CHR(13), ' '), 1, 300) AS proposal, " +
            "       rc.entryvalue AS change_type " +
            "FROM agile.change c " +
            "JOIN agile.nodetable sn ON sn.id = c.status " +
            "LEFT JOIN agile.agileuser analyst ON analyst.id = c.owner " +
            "LEFT JOIN agile.listentry pri ON pri.entryid = c.category AND pri.langid = 0 " +
            "JOIN agile.page_three p3 ON p3.id = c.id AND p3.class = c.class " +
            "LEFT JOIN agile.listentry rc ON rc.entryid = p3.list35 AND rc.langid = 0 AND rc.parent_entry IS NOT NULL " +
            "LEFT JOIN agile.listentry rc_parent ON rc_parent.entryid = rc.parent_entry AND rc_parent.langid = 0 " +
            "WHERE c.change_number LIKE 'ECN-%' " +
            "  AND c.subclass = " + ECN_SUBCLASS + " " +
            "  AND (c.delete_flag = 0 OR c.delete_flag IS NULL) " +
            "  AND c.submit_date IS NOT NULL " +
            "  AND p3.date31 IS NOT NULL " +
            "  AND p3.date31 < TRUNC(SYSDATE) + 2 " +
            filters +
            "ORDER BY p3.date31 ASC";

        List<DueRow> out = new ArrayList<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            int idx = 1;
            for (String p : params) ps.setString(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                LocalDate today = LocalDate.now();
                while (rs.next()) {
                    DueRow r = new DueRow();
                    r.ecnNumber = rs.getString("chg_num");
                    r.status = rs.getString("cur_status");
                    r.dueIso = rs.getString("due_iso");
                    r.analyst = cleanAnalyst(rs.getString("analyst"));
                    r.priority = rs.getString("priority");
                    r.isUrgent = r.priority != null && r.priority.toLowerCase().contains("urgent");
                    r.productLine = resolveProductLines(rs.getString("pl_raw"));
                    r.proposal = rs.getString("proposal");
                    if (r.proposal != null) r.proposal = r.proposal.trim();
                    r.changeType = normalizeChangeType(rs.getString("change_type"));
                    // Working days signed: negative = overdue, positive = remaining.
                    LocalDate due = LocalDate.parse(r.dueIso);
                    r.workingDays = signedWorkingDays(today, due);
                    r.bucket = r.workingDays < -10 ? "critical"
                             : r.workingDays < 0 ? "overdue" : "dueSoon";
                    out.add(r);
                }
            }
        }
        // Sort most overdue first (smallest / most-negative workingDays first).
        out.sort((a, b) -> Integer.compare(a.workingDays, b.workingDays));
        return out;
    }

    /** Forward histogram: in-flight ECNs (same population rules) coming due in the
     *  next {@code forwardDays} calendar days, grouped by due date. */
    /** In-flight ECNs coming due in the next {@code forwardDays} days — the detail
     *  behind each histogram bar (per-day counts are derived in {@link #buildPayload}). */
    private List<DueRow> queryForwardRows(boolean includeProj) throws SQLException {
        List<String> params = new ArrayList<>();
        String filters = buildEmailFilters(includeProj, params);

        String sql =
            "SELECT c.change_number AS chg_num, sn.name AS cur_status, " +
            "       TO_CHAR(p3.date31, 'YYYY-MM-DD') AS due_iso, " +
            "       analyst.last_name || ', ' || analyst.first_name AS analyst, " +
            "       pri.entryvalue AS priority, " +
            "       rc.entryvalue AS change_type " +
            "FROM agile.change c " +
            "JOIN agile.nodetable sn ON sn.id = c.status " +
            "LEFT JOIN agile.agileuser analyst ON analyst.id = c.owner " +
            "LEFT JOIN agile.listentry pri ON pri.entryid = c.category AND pri.langid = 0 " +
            "JOIN agile.page_three p3 ON p3.id = c.id AND p3.class = c.class " +
            "LEFT JOIN agile.listentry rc ON rc.entryid = p3.list35 AND rc.langid = 0 AND rc.parent_entry IS NOT NULL " +
            "LEFT JOIN agile.listentry rc_parent ON rc_parent.entryid = rc.parent_entry AND rc_parent.langid = 0 " +
            "WHERE c.change_number LIKE 'ECN-%' " +
            "  AND c.subclass = " + ECN_SUBCLASS + " " +
            "  AND (c.delete_flag = 0 OR c.delete_flag IS NULL) " +
            "  AND c.submit_date IS NOT NULL " +
            "  AND p3.date31 >= TRUNC(SYSDATE) " +
            "  AND p3.date31 < TRUNC(SYSDATE) + " + forwardDays + " " +
            filters +
            "ORDER BY p3.date31, c.change_number";

        List<DueRow> out = new ArrayList<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            int idx = 1;
            for (String p : params) ps.setString(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DueRow r = new DueRow();
                    r.ecnNumber = rs.getString("chg_num");
                    r.status = rs.getString("cur_status");
                    r.dueIso = rs.getString("due_iso");
                    r.analyst = cleanAnalyst(rs.getString("analyst"));
                    r.priority = rs.getString("priority");
                    r.isUrgent = r.priority != null && r.priority.toLowerCase().contains("urgent");
                    r.changeType = normalizeChangeType(rs.getString("change_type"));
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * Builds the shared filter clause that mirrors the saved Agile search
     * <em>/Global Searches/ECNReports/ECNDueDateExpireReport</em> and appends the
     * bind params in order. Covers: status exclusion (leaves RELEASED/REVIEW/SUBMIT),
     * Number-does-not-contain-CCB, Product-Line-not-EVB/N-A, and (unless
     * {@code includeProj}) Request-Classification-not-under-"Project Request".
     */
    private String buildEmailFilters(boolean includeProj, List<String> params) {
        StringBuilder sb = new StringBuilder();
        // Status: drop paused/terminal so only RELEASED/REVIEW/SUBMIT remain.
        List<String> excluded = parseCsv(excludedStatusesCsv);
        if (!excluded.isEmpty()) {
            sb.append(" AND sn.name NOT IN (").append(qMarks(excluded.size())).append(") ");
            params.addAll(excluded);
        }
        // Cover Page.Number Does Not Contain CCB.
        if (excludeNumberContains != null && !excludeNumberContains.trim().isEmpty()) {
            sb.append(" AND UPPER(c.change_number) NOT LIKE ? ");
            params.add("%" + excludeNumberContains.trim().toUpperCase() + "%");
        }
        // Cover Page.Product Line(s) Not Equal To {EVB ENGG, N/A}. product_lines is a
        // comma-wrapped CSV of listentry ids (",id,id,"); drop rows that carry one.
        for (Long plId : excludedProductLineIds()) {
            sb.append(" AND (c.product_lines IS NULL OR c.product_lines NOT LIKE ?) ");
            params.add("%," + plId + ",%");
        }
        // ECN Details.Request Classification Not In {Project Request|…} — applied by
        // parent classification so new IT/project sub-types are covered automatically.
        // The "Include IT (-PROJ)" toggle lifts this.
        if (!includeProj && projectClassificationParent != null && !projectClassificationParent.trim().isEmpty()) {
            sb.append(" AND (rc_parent.entryvalue IS NULL OR rc_parent.entryvalue <> ?) ");
            params.add(projectClassificationParent.trim());
        }
        return sb.toString();
    }

    /** Resolve the excluded product-line names to their listentry ids via the
     *  product-line lookup (parent 291). Unknown names are skipped. */
    private List<Long> excludedProductLineIds() {
        ensureProductLineLookup();
        List<Long> ids = new ArrayList<>();
        for (String name : parseCsv(excludeProductLinesCsv)) {
            for (Map.Entry<Long, String> e : productLineLookup.entrySet()) {
                if (name.equalsIgnoreCase(e.getValue())) { ids.add(e.getKey()); break; }
            }
        }
        return ids;
    }

    /** Latest CCB comment per ECN → Notes column. Chunked to stay under Oracle's
     *  1000-item IN-list cap (#7); the population is normally &lt;100 so this is
     *  defensive. */
    private void fetchNotes(List<DueRow> rows) {
        if (rows.isEmpty()) return;
        List<String> ecns = new ArrayList<>();
        for (DueRow r : rows) if (r.ecnNumber != null) ecns.add(r.ecnNumber);
        if (ecns.isEmpty()) return;
        Map<String, String> notes = new HashMap<>();
        final int CHUNK = 900;
        for (int start = 0; start < ecns.size(); start += CHUNK) {
            List<String> batch = ecns.subList(start, Math.min(start + CHUNK, ecns.size()));
            String sql =
                "SELECT chg_num, cmt FROM (" +
                "  SELECT c.change_number AS chg_num, " +
                "         REPLACE(REPLACE(SUBSTR(ch.comments, 1, 500), CHR(10), ' '), CHR(13), '') AS cmt, " +
                "         ROW_NUMBER() OVER (PARTITION BY c.change_number ORDER BY ch.local_date DESC) AS rn " +
                "  FROM agile.change c " +
                "  JOIN agile.change_history ch ON ch.change_id = c.id " +
                "  WHERE c.change_number IN (" + qMarks(batch.size()) + ") " +
                "    AND ch.comments IS NOT NULL " +
                "    AND ch.event_type IN (14, 15, 17, 65) " +
                ") WHERE rn = 1";
            try (Connection conn = agileDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(60);
                int idx = 1;
                for (String e : batch) ps.setString(idx++, e);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) notes.put(rs.getString("chg_num"), rs.getString("cmt"));
                }
            } catch (SQLException e) {
                logger.warning("[DUEDATE] fetchNotes failed: " + e.getMessage());
            }
        }
        for (DueRow r : rows) {
            String n = notes.get(r.ecnNumber);
            r.notes = n == null ? "" : n.trim();
        }
    }

    // =====================================================================
    // Payload assembly
    // =====================================================================

    private Map<String, Object> buildPayload(List<DueRow> rows, List<DueRow> forwardRows, boolean includeProj) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Summary badges with Urgent / Standard split.
        Map<String, int[]> buckets = new LinkedHashMap<>();
        buckets.put("critical", new int[3]); // [total, urgent, standard]
        buckets.put("overdue", new int[3]);
        buckets.put("dueSoon", new int[3]);
        Map<String, int[]> perAnalyst = new LinkedHashMap<>(); // analyst -> [total, critical, overdue, dueSoon]
        Map<String, Integer> changeTypeCounts = new LinkedHashMap<>(); // ECN Change Type -> count (PT-98)

        List<Map<String, Object>> rowMaps = new ArrayList<>();
        for (DueRow r : rows) {
            int[] b = buckets.get(r.bucket);
            b[0]++;
            if (r.isUrgent) b[1]++; else b[2]++;

            String analystKey = (r.analyst == null || r.analyst.isEmpty()) ? "Unassigned" : r.analyst;
            int[] pa = perAnalyst.computeIfAbsent(analystKey, k -> new int[4]);
            pa[0]++;
            if ("critical".equals(r.bucket)) pa[1]++;
            else if ("overdue".equals(r.bucket)) pa[2]++;
            else pa[3]++;

            String ctKey = (r.changeType == null || r.changeType.isEmpty()) ? "Unclassified" : r.changeType;
            changeTypeCounts.merge(ctKey, 1, Integer::sum);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ecnNumber", r.ecnNumber);
            m.put("link", plmBaseUrl + "/Agile/object/ECN/" + r.ecnNumber);
            m.put("status", r.status);
            m.put("productLine", r.productLine);
            m.put("changeType", (r.changeType == null || r.changeType.isEmpty()) ? "Unclassified" : r.changeType);
            m.put("analyst", analystKey);
            m.put("proposal", r.proposal);
            m.put("targetDueDate", displayDate(r.dueIso));
            m.put("dueIso", r.dueIso);
            m.put("priority", r.isUrgent ? "Urgent" : "Standard");
            m.put("isUrgent", r.isUrgent);
            m.put("workingDays", r.workingDays);
            m.put("bucket", r.bucket);
            m.put("daysLabel", daysLabel(r.workingDays));
            m.put("notes", r.notes == null ? "" : r.notes);
            rowMaps.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("critical", bucketMap(buckets.get("critical")));
        summary.put("overdue", bucketMap(buckets.get("overdue")));
        summary.put("dueSoon", bucketMap(buckets.get("dueSoon")));
        summary.put("total", rows.size());

        List<Map<String, Object>> analystList = new ArrayList<>();
        for (Map.Entry<String, int[]> e : perAnalyst.entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("analyst", e.getKey());
            a.put("total", e.getValue()[0]);
            a.put("critical", e.getValue()[1]);
            a.put("overdue", e.getValue()[2]);
            a.put("dueSoon", e.getValue()[3]);
            analystList.add(a);
        }
        analystList.sort((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total")));

        // Forward histogram + the per-date ECN details behind each bar (click-to-list, #29).
        Map<String, Integer> fwdCount = new HashMap<>();
        List<Map<String, Object>> forwardRowMaps = new ArrayList<>();
        for (DueRow r : forwardRows) {
            fwdCount.merge(r.dueIso, 1, Integer::sum);
            String an = (r.analyst == null || r.analyst.isEmpty()) ? "Unassigned" : r.analyst;
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("ecnNumber", r.ecnNumber);
            fm.put("link", plmBaseUrl + "/Agile/object/ECN/" + r.ecnNumber);
            fm.put("dueIso", r.dueIso);
            fm.put("analyst", an);
            fm.put("priority", r.isUrgent ? "Urgent" : "Standard");
            fm.put("isUrgent", r.isUrgent);
            fm.put("status", r.status);
            forwardRowMaps.add(fm);
        }
        List<Map<String, Object>> hist = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < forwardDays; i++) {
            LocalDate d = today.plusDays(i);
            String iso = d.toString();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("date", iso);
            h.put("label", d.format(DateTimeFormatter.ofPattern("MMM d")));
            h.put("count", fwdCount.getOrDefault(iso, 0));
            h.put("weekend", d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);
            hist.add(h);
        }

        out.put("rows", rowMaps);
        out.put("summary", summary);
        out.put("perAnalyst", analystList);
        // PT-98: counts by ECN Change Type, sorted high -> low, for the bar tile
        // and the classification filter buttons.
        List<Map<String, Object>> ctList = new ArrayList<>();
        for (Map.Entry<String, Integer> e : changeTypeCounts.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", e.getKey());
            c.put("count", e.getValue());
            ctList.add(c);
        }
        ctList.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        out.put("changeTypeCounts", ctList);
        out.put("forwardHistogram", hist);
        out.put("forwardRows", forwardRowMaps);
        out.put("includeProj", includeProj);
        out.put("targetStandardDays", targetStandardDays);
        out.put("targetUrgentDays", targetUrgentDays);
        out.put("forwardDays", forwardDays);
        out.put("reportDate", today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        return out;
    }

    private static Map<String, Object> bucketMap(int[] b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", b[0]);
        m.put("urgent", b[1]);
        m.put("standard", b[2]);
        return m;
    }

    private static Map<String, Object> emptySummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("critical", bucketMap(new int[3]));
        s.put("overdue", bucketMap(new int[3]));
        s.put("dueSoon", bucketMap(new int[3]));
        s.put("total", 0);
        return s;
    }

    // =====================================================================
    // Excel export (#4)
    // =====================================================================

    /** Streams the current population as .xlsx — columns match the email table. */
    public void buildExcel(OutputStream out, Boolean includeProjParam) throws IOException {
        boolean includeProj = includeProjParam != null ? includeProjParam : !excludeProjDefault;
        List<DueRow> rows;
        try {
            rows = queryPopulation(includeProj);
            fetchNotes(rows);
        } catch (SQLException e) {
            throw new IOException("Query failed: " + e.getMessage(), e);
        }
        SXSSFWorkbook wb = new SXSSFWorkbook(null, 200, false, false);
        try {
            Sheet sheet = wb.createSheet("Due Date Expiration");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle hdr = wb.createCellStyle();
            hdr.setFont(bold);
            String[] cols = {"ECN Number", "Status", "Product Line", "Change Type", "Analyst", "Proposal",
                    "Target Due Date", "Priority", "Days", "Comments / Notes"};
            Row h = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(hdr);
            }
            int r = 1;
            for (DueRow row : rows) {
                Row x = sheet.createRow(r++);
                x.createCell(0).setCellValue(nvl(row.ecnNumber));
                x.createCell(1).setCellValue(nvl(row.status));
                x.createCell(2).setCellValue(nvl(row.productLine));
                x.createCell(3).setCellValue(nvl(row.changeType));
                x.createCell(4).setCellValue(nvl(row.analyst));
                x.createCell(5).setCellValue(nvl(row.proposal));
                x.createCell(6).setCellValue(displayDate(row.dueIso));
                x.createCell(7).setCellValue(row.isUrgent ? "Urgent" : "Standard");
                x.createCell(8).setCellValue(daysLabel(row.workingDays));
                x.createCell(9).setCellValue(nvl(row.notes));
            }
            for (int i = 0; i < cols.length; i++) sheet.setColumnWidth(i, colWidth(i));
            wb.write(out);
        } finally {
            wb.dispose();
            wb.close();
        }
    }

    private static int colWidth(int i) {
        switch (i) {
            case 5: return 60 * 256; // Proposal
            case 9: return 45 * 256; // Notes
            case 2: return 24 * 256; // Product Line
            case 3: return 22 * 256; // Change Type
            case 4: return 22 * 256; // Analyst
            default: return 16 * 256;
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /** Trim the Request-Classification value used as the ECN Change Type label.
     *  Null / blank becomes empty so callers can bucket it as "Unclassified". */
    private static String normalizeChangeType(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t;
    }

    /** "Last, First" from agileuser, tolerating a null/empty half. */
    private static String cleanAnalyst(String an) {
        if (an == null) return "";
        String s = an.trim();
        if (s.equals(",")) return "";
        if (s.startsWith(",")) s = s.substring(1).trim();
        if (s.endsWith(",")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    // =====================================================================
    // Snapshot + cache persistence
    // =====================================================================

    /**
     * Append today's backlog point. {@code force=false} (manual refresh) keeps the
     * first write of the day so a 4 PM manual refresh doesn't clobber the 6 AM point;
     * {@code force=true} (the scheduled job) overwrites so 6 AM is authoritative (#9).
     * Each point stamps {@code capturedAt} so the trend is honest about its source.
     */
    @SuppressWarnings("unchecked")
    private void writeSnapshot(Map<String, Object> payload, boolean force) {
        try {
            new File(DATA_DIR).mkdirs();
            File f = new File(SNAPSHOT_FILE);
            Map<String, Object> all = f.exists() ? mapper.readValue(f, Map.class) : new LinkedHashMap<>();
            String today = LocalDate.now().toString();
            if (!force && all.containsKey(today)) return; // first-write-wins for manual refreshes
            Map<String, Object> summary = (Map<String, Object>) payload.get("summary");
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("critical", intVal(((Map<String, Object>) summary.get("critical")).get("total")));
            pt.put("overdue", intVal(((Map<String, Object>) summary.get("overdue")).get("total")));
            pt.put("dueSoon", intVal(((Map<String, Object>) summary.get("dueSoon")).get("total")));
            pt.put("total", intVal(summary.get("total")));
            pt.put("capturedAt", java.time.LocalTime.now().withNano(0).toString());
            pt.put("scheduled", force);
            all.put(today, pt);
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, all);
            logger.info("[DUEDATE] snapshot " + (force ? "(scheduled) " : "") + "written for " + today);
        } catch (Exception e) {
            logger.warning("[DUEDATE] writeSnapshot failed: " + e.getMessage());
        }
    }

    private void writeCacheFile(Map<String, Object> payload) {
        try {
            new File(DATA_DIR).mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CACHE_FILE), payload);
        } catch (Exception e) {
            logger.warning("[DUEDATE] writeCacheFile failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCacheFile() {
        try {
            File f = new File(CACHE_FILE);
            if (f.exists()) {
                Map<String, Object> payload = mapper.readValue(f, Map.class);
                // Discard a cache written by an older build with a different payload
                // shape so the UI never renders a stale schema (#11).
                if (intVal(payload.get("cacheVersion")) != CACHE_VERSION) {
                    logger.info("[DUEDATE] discarding cache file — version "
                            + payload.get("cacheVersion") + " != " + CACHE_VERSION);
                    return null;
                }
                return payload;
            }
        } catch (Exception e) {
            logger.warning("[DUEDATE] readCacheFile failed: " + e.getMessage());
        }
        return null;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Signed Mon–Fri working days between {@code from} and {@code to}: positive
     *  when {@code to} is in the future (remaining), negative when past (overdue). */
    static int signedWorkingDays(LocalDate from, LocalDate to) {
        if (to.equals(from)) return 0;
        boolean neg = to.isBefore(from);
        LocalDate a = neg ? to : from;
        LocalDate b = neg ? from : to;
        int wd = 0;
        LocalDate d = a;
        while (d.isBefore(b)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) wd++;
            d = d.plusDays(1);
        }
        return neg ? -wd : wd;
    }

    private static String daysLabel(int wd) {
        if (wd == 0) return "Due today";
        if (wd < 0) {
            int a = -wd;
            return a + " day" + (a != 1 ? "s" : "") + " overdue";
        }
        return wd + " day" + (wd != 1 ? "s" : "") + " remaining";
    }

    private static String displayDate(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MM-dd-yy"));
        } catch (Exception e) {
            return iso;
        }
    }

    private static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String qMarks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static int intVal(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    // ---- Product Line lookup (same source as OverdueTrackerService) ----
    private final Map<Long, String> productLineLookup = new HashMap<>();
    /** Epoch-millis of the last successful load; 0 = never. Reloaded on a 24 h TTL
     *  so new/renamed product lines (and the EVB/N-A exclusion resolution that
     *  depends on this map) don't stay wrong until a restart (#10). */
    private volatile long productLineLookupLoadedAt = 0L;
    private static final long PL_LOOKUP_TTL_MS = 24L * 60 * 60 * 1000;

    private synchronized void ensureProductLineLookup() {
        // A failed load must NOT count as loaded — otherwise the map stays empty and
        // the product-line exclusion silently drops off until restart.
        if (productLineLookupLoadedAt > 0
                && (System.currentTimeMillis() - productLineLookupLoadedAt) < PL_LOOKUP_TTL_MS) return;
        String sql = "SELECT entryid, entryvalue FROM agile.listentry WHERE langid = 0 AND parentid = 291";
        Map<Long, String> fresh = new HashMap<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) fresh.put(rs.getLong("entryid"), rs.getString("entryvalue"));
            }
            productLineLookup.clear();
            productLineLookup.putAll(fresh);
            productLineLookupLoadedAt = System.currentTimeMillis();
            logger.info("[DUEDATE] loaded " + productLineLookup.size() + " product-line entries");
        } catch (SQLException e) {
            // Leave any previously-loaded map in place; retry on the next call.
            logger.warning("[DUEDATE] productLineLookup load failed (will retry): " + e.getMessage());
        }
    }

    private String resolveProductLines(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        ensureProductLineLookup();
        List<String> names = new ArrayList<>();
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try {
                String name = productLineLookup.get(Long.parseLong(t));
                if (name != null && !names.contains(name)) names.add(name);
            } catch (NumberFormatException ignore) { }
        }
        return String.join(" · ", names);
    }

    // ---- row model ----
    private static final class DueRow {
        String ecnNumber;
        String status;
        String dueIso;
        String analyst;
        String priority;
        boolean isUrgent;
        String productLine;
        String proposal;
        String notes;
        int workingDays;
        String bucket;
        String changeType;
    }
}
