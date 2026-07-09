package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Backs the Audit Trail sub-tab under Change History (PT-50 / ECN-135744-PROJ).
 *
 * <p>Queries the live Agile change-history table directly (joined with the change
 * table for the change number). All filters are optional and stack as AND clauses.
 * Two hard guardrails enforced before the SQL runs:
 * <ul>
 *   <li><strong>1000-ID cap</strong> on item-number and change-number lists. Beyond
 *       that we'd blow Oracle's IN-list cap and starve other queries. Spec said
 *       "optimize as needed" — keeping it strict for now.</li>
 *   <li><strong>1-year date-range mandate</strong> when neither item nor change is
 *       supplied. A wide-open WHERE on change_history would scan years and hang the
 *       toolkit pool. Spec is explicit about this.</li>
 * </ul>
 *
 * <p>Item filter routes via AGILE.REV: items don't have a direct change_history row,
 * but every CHANGE has REVs, and every REV ties to one ITEM. So we resolve
 * <code>item_number → change.id</code> through a sub-SELECT and feed that into the
 * change_history filter.
 */
@Service
public class AuditTrailService {

    private static final Logger logger = Logger.getLogger(AuditTrailService.class.getName());

    private static final int HARD_ID_CAP = 1000;
    private static final int DEFAULT_MAX_ROWS = 10_000;   // soft cap on rows we ship back; spec hints at this
    private static final int QUERY_TIMEOUT_SEC = 120;
    private static final long ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000;

    // PT-60: friendly names for AGILE.CHANGE_HISTORY.EVENT_TYPE codes. The DB stores
    // event_type as a numeric code with no in-schema lookup — Agile's UI hardcodes
    // these mappings. Codes confirmed against the prod DB distribution + sample
    // DETAILS values. Unknown codes fall back to "Action " + N so users can still
    // see the raw code.
    private static final Map<Integer, String> EVENT_TYPE_LABEL;
    static {
        Map<Integer, String> m = new HashMap<>();
        m.put(0,   "Modify");
        m.put(12,  "Open");
        m.put(14,  "Submit");
        m.put(15,  "Submit");
        m.put(22,  "Approve");
        m.put(23,  "Modify Affected Items");
        m.put(24,  "Approve");
        m.put(25,  "Add Affected Item");
        m.put(27,  "Remove Affected Item");
        m.put(29,  "Add Reference");
        m.put(30,  "Remove Reference");
        m.put(37,  "Hold");
        m.put(38,  "Implement");
        m.put(41,  "Add Attachment");
        m.put(42,  "Remove Attachment");
        m.put(63,  "Sign Off");
        m.put(65,  "Status Change");
        m.put(71,  "Cancel");
        m.put(72,  "Reject");
        m.put(76,  "Modify");
        m.put(81,  "Modify Field");
        m.put(83,  "Custom Action");
        m.put(172, "Notification");
        m.put(611, "Workflow Event");
        EVENT_TYPE_LABEL = Collections.unmodifiableMap(m);
    }

    private static String formatAction(Integer eventType, String prevStatus, String nextStatus) {
        if (eventType == null) return null;
        // Status changes get the status transition as the action label — matches
        // how the toolkit already renders Prev/Next Status, but in one column.
        // PT-81 (2026-06-04): for status-change events the Action column now
        // shows just the NEXT status (the state the row landed in), matching
        // how Agile's own History tab renders it. Previously we emitted
        // "PrevStatus -> NextStatus" which duplicated values already in
        // the dedicated Prev / Next columns and made the Action column read
        // like a Prev-Status leak (reported by Vikas Singh against
        // ECO-133128-A). Falls back to prevStatus, then to EVENT_TYPE_LABEL.
        if (eventType == 65) {
            if (nextStatus != null && !nextStatus.isEmpty()) return nextStatus;
            if (prevStatus != null && !prevStatus.isEmpty()) return prevStatus;
        }
        String label = EVENT_TYPE_LABEL.get(eventType);
        return label != null ? label : "Action " + eventType;
    }

    private final DataSource dataSource;

    public AuditTrailService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static class Query {
        /** PT-54: which Agile history table to query. "item" → AGILE.ITEM_HISTORY
         *  (field-level changes on items); "change" → AGILE.CHANGE_HISTORY (workflow
         *  events on changes). Default "change" preserves Phase-2/3 behavior. */
        public String source = "change";
        public List<String> itemNumbers = new ArrayList<>();
        public List<String> changeNumbers = new ArrayList<>();
        public String dateFrom;     // ISO yyyy-MM-dd or null
        public String dateTo;       // ISO yyyy-MM-dd or null
        public String details;      // substring match (case-insensitive)
        public String detailsMode;  // "contains" (default) | "startswith"
        public String comments;     // substring match (case-insensitive; change-history only)
        public String commentsMode; // "contains" (default) | "startswith"
        public List<String> usernames = new ArrayList<>();
        public String action;       // substring on PREV_STATUS||'→'||NEXT_STATUS (change-history only)
        public String actionMode;   // "contains" (default) | "startswith"
        public Integer maxRows;     // optional; defaults to DEFAULT_MAX_ROWS
    }

    public static class Result {
        public List<Map<String, Object>> rows = new ArrayList<>();
        public int rowCount = 0;
        public boolean truncated = false;   // hit maxRows
        public String error;                // null on success
        public long elapsedMs;
        /** PT-54: which history table the rows came from. "item" or "change". The
         *  frontend reads this back so it renders the right column headers. */
        public String source;
        /** PT-66: optional hint surfaced to the user when an empty result is most
         *  likely due to filter intersection (e.g. item + change supplied but they
         *  don't relate). Null when not applicable. */
        public String hint;
    }

    public Result run(Query q) {
        long t0 = System.currentTimeMillis();
        Result out = new Result();

        // ---- input sanity (shared between item + change sources) -----------
        List<String> items = trimDedup(q.itemNumbers);
        List<String> changes = trimDedup(q.changeNumbers);
        List<String> usernames = trimDedup(q.usernames);
        String source = (q.source == null) ? "change" : q.source.toLowerCase().trim();
        if (!source.equals("item") && !source.equals("change")) source = "change";
        out.source = source;

        if (items.size() > HARD_ID_CAP) {
            out.error = "Item Number list exceeds the 1000-ID cap (got " + items.size() + "). Split into batches.";
            return out;
        }
        if (changes.size() > HARD_ID_CAP) {
            out.error = "Change Number list exceeds the 1000-ID cap (got " + changes.size() + "). Split into batches.";
            return out;
        }

        // Source-specific cross-validation. PT-54: item_history has no change-number
        // dimension, so refuse a change-number filter against the item source (and
        // vice versa) — silently ignoring would surprise the user.
        if (source.equals("item") && !changes.isEmpty()) {
            out.error = "Change Numbers don't apply to Item History — switch the Source toggle to Change History to filter by change numbers, or clear the change-number list.";
            return out;
        }
        if (source.equals("change") && !items.isEmpty() && !changes.isEmpty()) {
            // mixing is allowed for change source (item-→-change routing still works)
        }

        boolean noIds = items.isEmpty() && changes.isEmpty();
        if (noIds) {
            if (isBlank(q.dateFrom) || isBlank(q.dateTo)) {
                String idsLabel = source.equals("item") ? "Item Numbers" : "Item or Change Numbers";
                out.error = "Without " + idsLabel + ", a Date Range is required (max 1 year).";
                return out;
            }
            try {
                long from = parseIsoDay(q.dateFrom);
                long to = parseIsoDay(q.dateTo);
                if (to - from > ONE_YEAR_MS) {
                    out.error = "Date Range cannot exceed 1 year when no Item/Change numbers are provided.";
                    return out;
                }
                if (to < from) {
                    out.error = "Date Range: end date is before start date.";
                    return out;
                }
            } catch (Exception e) {
                out.error = "Date Range: " + e.getMessage();
                return out;
            }
        }

        int maxRows = (q.maxRows != null && q.maxRows > 0 && q.maxRows <= 100_000) ? q.maxRows : DEFAULT_MAX_ROWS;

        // ---- dispatch on source -------------------------------------------
        if (source.equals("item")) {
            runItemHistory(q, items, usernames, maxRows, out);
        } else {
            runChangeHistory(q, items, changes, usernames, maxRows, out);
        }

        out.rowCount = out.rows.size();
        out.elapsedMs = System.currentTimeMillis() - t0;
        logger.info("[AUDIT-TRAIL] source=" + source + " " + out.rowCount + " rows in " + out.elapsedMs + "ms"
                + " (items=" + items.size() + ", changes=" + changes.size()
                + ", truncated=" + out.truncated + ")");
        return out;
    }

    /**
     * Run the existing Change History query (AGILE.CHANGE_HISTORY joined back to
     * AGILE.CHANGE for the change number, plus NODETABLE for readable status names).
     */
    private void runChangeHistory(Query q, List<String> items, List<String> changes,
                                  List<String> usernames, int maxRows, Result out) {
        // PREV_STATUS / NEXT_STATUS are NODETABLE IDs on disk; join NODETABLE twice
        // to surface the readable workflow names.
        //
        // PT-60: three additional columns to match the Agile UI:
        //   * EVENT_TYPE (numeric code) -> mapped to action label in Java post-fetch
        //   * AFFECTED_ITEM joined to AGILE.ITEM for the affected object's item number
        //   * SIGNOFF + AGILEUSER aggregated into a "Last, First (loginid)" list per event
        // The SIGNOFF subquery is a per-row correlated LISTAGG. Cost-wise this is fine
        // because the outer query is already bounded by maxRows (10K cap) and
        // CHANGE_HISTORY rows that have signoff entries are a small subset of total
        // events. The subquery is null-safe (returns null when no signoffs exist).
        StringBuilder sql = new StringBuilder(
                "SELECT c.CHANGE_NUMBER, " +
                "       h.EVENT_TYPE, " +
                "       prev_n.DESCRIPTION AS PREV_STATUS, " +
                "       next_n.DESCRIPTION AS NEXT_STATUS, " +
                "       h.USER_NAME, h.COMMENTS, h.DETAILS, " +
                "       h.LOCAL_DATE, " +
                "       ai.ITEM_NUMBER AS AFFECTED_ITEM_NUMBER, " +
                "       (SELECT LISTAGG(u.LAST_NAME || ', ' || u.FIRST_NAME || ' (' || u.LOGINID || ')', '; ') " +
                "               WITHIN GROUP (ORDER BY u.LAST_NAME, u.FIRST_NAME) " +
                "        FROM AGILE.SIGNOFF s " +
                "        LEFT JOIN AGILE.AGILEUSER u ON s.USER_ASSIGNED = u.ID " +
                "        WHERE s.HISTORY_ID = h.ID) AS USERS_NOTIFIED " +
                "FROM AGILE.CHANGE c " +
                "JOIN AGILE.CHANGE_HISTORY h ON c.ID = h.CHANGE_ID " +
                "LEFT JOIN AGILE.NODETABLE prev_n ON h.PREV_STATUS = prev_n.ID " +
                "LEFT JOIN AGILE.NODETABLE next_n ON h.NEXT_STATUS = next_n.ID " +
                "LEFT JOIN AGILE.ITEM ai ON h.AFFECTED_ITEM = ai.ID " +
                "WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (!items.isEmpty()) {
            sql.append("AND c.ID IN (SELECT r.CHANGE FROM AGILE.REV r " +
                       "             JOIN AGILE.ITEM i ON r.ITEM = i.ID " +
                       "             WHERE i.ITEM_NUMBER IN (").append(placeholders(items.size())).append(")) ");
            params.addAll(items);
        }
        if (!changes.isEmpty()) {
            sql.append("AND c.CHANGE_NUMBER IN (").append(placeholders(changes.size())).append(") ");
            params.addAll(changes);
        }
        if (!isBlank(q.dateFrom)) {
            sql.append("AND h.LOCAL_DATE >= TO_DATE(?, 'YYYY-MM-DD') ");
            params.add(q.dateFrom);
        }
        if (!isBlank(q.dateTo)) {
            sql.append("AND h.LOCAL_DATE < TO_DATE(?, 'YYYY-MM-DD') + 1 ");
            params.add(q.dateTo);
        }
        appendStringFilter(sql, params, "h.DETAILS", q.details, q.detailsMode);
        appendStringFilter(sql, params, "h.COMMENTS", q.comments, q.commentsMode);
        if (!usernames.isEmpty()) {
            sql.append("AND UPPER(h.USER_NAME) IN (");
            for (int i = 0; i < usernames.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(") ");
            for (String u : usernames) params.add(u.toUpperCase());
        }
        appendActionFilter(sql, params, q.action, q.actionMode);

        sql.append("ORDER BY h.LOCAL_DATE DESC ");
        sql.append("FETCH FIRST ").append(maxRows + 1).append(" ROWS ONLY");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("change_number", rs.getString("CHANGE_NUMBER"));
                    String prevStatus = rs.getString("PREV_STATUS");
                    String nextStatus = rs.getString("NEXT_STATUS");
                    int eventType = rs.getInt("EVENT_TYPE");
                    Integer eventTypeBoxed = rs.wasNull() ? null : eventType;
                    row.put("action",        formatAction(eventTypeBoxed, prevStatus, nextStatus));
                    row.put("event_type",    eventTypeBoxed);
                    row.put("prev_status",   prevStatus);
                    row.put("next_status",   nextStatus);
                    row.put("user_name",     rs.getString("USER_NAME"));
                    row.put("affected_object", rs.getString("AFFECTED_ITEM_NUMBER"));
                    row.put("users_notified",  rs.getString("USERS_NOTIFIED"));
                    row.put("comments",      rs.getString("COMMENTS"));
                    row.put("details",       cleanDetails(rs.getString("DETAILS")));
                    Timestamp ts = rs.getTimestamp("LOCAL_DATE");
                    row.put("local_date", ts == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts));
                    out.rows.add(row);
                }
                out.truncated = rs.next();
            }
        } catch (SQLException sqle) {
            out.error = "Database error: " + sqle.getMessage();
            logger.warning("[AUDIT-TRAIL] change-history SQL failed: " + sqle.getMessage());
        }

        // PT-66: when items + changes are both supplied and the strict intersection
        // is empty, the user is left staring at a blank table. Most common cause is
        // the change doesn't reference the item (the REV table has no row tying them).
        // Run one cheap diagnostic query so the empty state can tell them what to do
        // instead of just "0 results".
        if (out.error == null && out.rows.isEmpty() && !items.isEmpty() && !changes.isEmpty()) {
            diagnoseIntersectionEmpty(items, changes, out);
        }
    }

    /**
     * PT-66 helper: count change-history rows for the change(s) alone (no item
     * filter). If the change has history, the user's items + changes don't
     * intersect — set a hint pointing them at the workaround.
     */
    private void diagnoseIntersectionEmpty(List<String> items, List<String> changes, Result out) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS N FROM AGILE.CHANGE_HISTORY h " +
                "JOIN AGILE.CHANGE c ON h.CHANGE_ID = c.ID " +
                "WHERE c.CHANGE_NUMBER IN (").append(placeholders(changes.size())).append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(10);
            for (int i = 0; i < changes.size(); i++) ps.setString(i + 1, changes.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong("N") > 0) {
                    String changeStr = changes.size() == 1 ? changes.get(0) : (changes.size() + " changes");
                    String itemStr   = items.size()   == 1 ? items.get(0)   : (items.size()   + " items");
                    out.hint = "The change " + changeStr + " has audit-trail rows, but none of them reference "
                            + itemStr + ". The Item and Change Number filters are combined (AND) — clear "
                            + "the Item Number field to see " + changeStr + "'s full history.";
                }
            }
        } catch (SQLException e) {
            // Diagnostic-only — never block the empty response on this failing.
            logger.warning("[AUDIT-TRAIL] intersection diagnostic failed: " + e.getMessage());
        }
    }

    /**
     * Run the Item History query (AGILE.ITEM_HISTORY joined to AGILE.ITEM for the
     * item number). PT-54: this is what users actually want when they search "audit
     * trail of item X" — field-level changes on the item itself, not events on the
     * changes that happened to touch the item.
     *
     * <p>Columns surfaced: item_number, user_name, rev, details, local_date. The
     * item_history table has no comments / status-transition columns — the frontend
     * renders a different column set when source=item.
     */
    private void runItemHistory(Query q, List<String> items, List<String> usernames,
                                int maxRows, Result out) {
        // item_history.TIMESTAMP is a reserved word (must be double-quoted). Per
        // ChangeQueryService, it's stored in UTC. DETAILS is a CLOB — wrap in
        // DBMS_LOB.SUBSTR so JDBC gets a String straight away.
        StringBuilder sql = new StringBuilder(
                "SELECT i.ITEM_NUMBER, " +
                "       DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) AS DETAILS, " +
                "       h.USER_NAME, h.REVNUMBER, " +
                "       h.\"TIMESTAMP\" AS LOCAL_DATE " +
                "FROM AGILE.ITEM_HISTORY h " +
                "JOIN AGILE.ITEM i ON h.ITEM = i.ID " +
                "WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (!items.isEmpty()) {
            sql.append("AND i.ITEM_NUMBER IN (").append(placeholders(items.size())).append(") ");
            params.addAll(items);
        }
        if (!isBlank(q.dateFrom)) {
            sql.append("AND h.\"TIMESTAMP\" >= TO_TIMESTAMP(?, 'YYYY-MM-DD') ");
            params.add(q.dateFrom);
        }
        if (!isBlank(q.dateTo)) {
            sql.append("AND h.\"TIMESTAMP\" < TO_TIMESTAMP(?, 'YYYY-MM-DD') + 1 ");
            params.add(q.dateTo);
        }
        appendStringFilter(sql, params, "DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1)", q.details, q.detailsMode);
        if (!usernames.isEmpty()) {
            sql.append("AND UPPER(h.USER_NAME) IN (");
            for (int i = 0; i < usernames.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(") ");
            for (String u : usernames) params.add(u.toUpperCase());
        }

        sql.append("ORDER BY h.\"TIMESTAMP\" DESC ");
        sql.append("FETCH FIRST ").append(maxRows + 1).append(" ROWS ONLY");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("item_number", rs.getString("ITEM_NUMBER"));
                    row.put("user_name",   rs.getString("USER_NAME"));
                    row.put("rev",         rs.getString("REVNUMBER"));
                    row.put("details",     cleanDetails(rs.getString("DETAILS")));
                    Timestamp ts = rs.getTimestamp("LOCAL_DATE", utcCal());
                    row.put("local_date", ts == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts));
                    out.rows.add(row);
                }
                out.truncated = rs.next();
            }
        } catch (SQLException sqle) {
            out.error = "Database error: " + sqle.getMessage();
            logger.warning("[AUDIT-TRAIL] item-history SQL failed: " + sqle.getMessage());
        }
    }

    private static java.util.Calendar utcCal() {
        return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    }

    // ===================================================================
    //  helpers
    // ===================================================================

    private static void appendStringFilter(StringBuilder sql, List<Object> params,
                                           String column, String value, String mode) {
        if (isBlank(value)) return;
        String pattern = "startswith".equalsIgnoreCase(mode) ? value + "%" : "%" + value + "%";
        sql.append("AND UPPER(").append(column).append(") LIKE UPPER(?) ");
        params.add(pattern);
    }

    private static void appendActionFilter(StringBuilder sql, List<Object> params,
                                           String value, String mode) {
        if (isBlank(value)) return;
        String pattern = "startswith".equalsIgnoreCase(mode) ? value + "%" : "%" + value + "%";
        // Match against the resolved workflow status names (via NODETABLE joins), not the raw IDs.
        sql.append("AND (UPPER(prev_n.DESCRIPTION) LIKE UPPER(?) OR UPPER(next_n.DESCRIPTION) LIKE UPPER(?)) ");
        params.add(pattern);
        params.add(pattern);
    }

    private static List<String> trimDedup(List<String> in) {
        if (in == null) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return new ArrayList<>(set);
    }

    private static String placeholders(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i == 0 ? "?" : ",?");
        return b.toString();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Agile's DETAILS column wraps workflow events with SOH-delimited markers like
     * {@code \x01HISTORY\x01...\x01HISTORY\x01}. Strip them and any leading numeric
     * sequence prefix (e.g. {@code 0:}) so the column reads as plain text.
     */
    private static String cleanDetails(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\u0001HISTORY\u0001", "").replace("\u0001", " ");
        // Strip leading "0:" / "1:" type sequence prefixes that Agile emits before each segment.
        s = s.replaceAll("(?m)^\\s*\\d+:", "").trim();
        return s.isEmpty() ? null : s;
    }

    private static long parseIsoDay(String iso) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setLenient(false);
        return f.parse(iso).getTime();
    }
}
