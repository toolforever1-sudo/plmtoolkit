package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class ChangeReviewService {

    private static final Logger logger = Logger.getLogger(ChangeReviewService.class.getName());

    @Autowired
    private LdapAuthService ldapAuthService;

    private final DataSource dataSource;

    public ChangeReviewService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // =========================================================================
    // Analyst list from Agile (cached 1 hour)
    // =========================================================================

    private volatile List<Map<String, String>> analystCache = null;
    private volatile long analystCacheLoaded = 0;
    private static final long ANALYST_CACHE_TTL = 60L * 60L * 1000L; // 1 hour

    public List<Map<String, String>> getAnalysts() {
        long now = System.currentTimeMillis();
        if (analystCache != null && (now - analystCacheLoaded) < ANALYST_CACHE_TTL) {
            return analystCache;
        }
        List<Map<String, String>> fresh = fetchAnalystsFromDb();
        if (fresh != null) {
            // Enrich with country from AD (single batch LDAP query)
            try {
                List<String> loginIds = new ArrayList<>();
                for (Map<String, String> a : fresh) loginIds.add(a.get("loginId"));
                Map<String, String> countries = ldapAuthService.batchLookupCountry(loginIds);
                for (Map<String, String> a : fresh) {
                    String country = countries.getOrDefault(a.get("loginId"), "Unknown");
                    a.put("country", country);
                }
            } catch (Exception e) {
                logger.warning("[ANALYST-CACHE] Country enrichment failed: " + e.getMessage());
                for (Map<String, String> a : fresh) a.put("country", "Unknown");
            }
            analystCache = fresh;
            analystCacheLoaded = now;
            return fresh;
        }
        return analystCache != null ? analystCache : Collections.emptyList();
    }

    private List<Map<String, String>> fetchAnalystsFromDb() {
        String sql =
            "SELECT u.LOGINID AS user_id, " +
            "       u.FIRST_NAME AS first_name, " +
            "       u.LAST_NAME AS last_name, " +
            "       u.EMAIL AS email " +
            "FROM agileuser u " +
            "WHERE u.ENABLED = 1 " +
            "  AND INSTR(u.LISTS, ',11,') > 0 " +
            "ORDER BY u.LAST_NAME, u.FIRST_NAME";

        List<Map<String, String>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    String first = nvl(rs.getString("first_name"));
                    String last = nvl(rs.getString("last_name"));
                    String displayName = last.isEmpty() ? first : (first.isEmpty() ? last : last + ", " + first);
                    row.put("loginId", nvl(rs.getString("user_id")));
                    row.put("displayName", displayName);
                    row.put("email", nvl(rs.getString("email")));
                    results.add(row);
                }
            }
            logger.info("[ANALYST-CACHE] Loaded " + results.size() + " analysts from Agile");
        } catch (SQLException e) {
            logger.warning("Analyst list query failed: " + e.getMessage());
            return null;
        }
        return results;
    }

    // =========================================================================
    // Dashboard — all changes in review across all analysts
    // =========================================================================

    public List<Map<String, String>> getAllChangesInReview(int lookbackDays) {
        String dateFilter = lookbackDays > 0 ? " AND c.CREATE_DATE >= SYSDATE - " + lookbackDays : "";
        String sql =
            "SELECT c.CHANGE_NUMBER AS change_number, " +
            "       c.DESCRIPTION AS description_of_change, " +
            "       n_status.DESCRIPTION AS status, " +
            "       n_workflow.DESCRIPTION AS workflow, " +
            "       n_subclass.DESCRIPTION AS change_type, " +
            "       le_priority.ENTRYVALUE AS priority, " +
            "       u.LOGINID AS owner_loginid, " +
            "       u.LAST_NAME || ', ' || u.FIRST_NAME AS owner_name " +
            "FROM change c " +
            "JOIN agileuser u ON u.ID = c.OWNER " +
            "LEFT JOIN nodetable n_status ON n_status.ID = c.STATUS " +
            "LEFT JOIN nodetable n_workflow ON n_workflow.ID = c.WORKFLOW_ID " +
            "LEFT JOIN nodetable n_subclass ON n_subclass.ID = c.SUBCLASS " +
            "LEFT JOIN listentry le_priority ON le_priority.ENTRYID = c.CATEGORY " +
            "                                AND le_priority.LANGID = 0 " +
            "WHERE c.STATUSTYPE = 2" + dateFilter + " " +
            "ORDER BY c.CHANGE_NUMBER";

        List<Map<String, String>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("changeNumber", nvl(rs.getString("change_number")));
                    row.put("description", nvl(rs.getString("description_of_change")));
                    row.put("status", nvl(rs.getString("status")));
                    row.put("workflow", nvl(rs.getString("workflow")));
                    row.put("changeType", nvl(rs.getString("change_type")));
                    row.put("priority", nvl(rs.getString("priority")));
                    row.put("ownerLoginId", nvl(rs.getString("owner_loginid")));
                    row.put("ownerName", nvl(rs.getString("owner_name")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("Dashboard query failed: " + e.getMessage());
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }
        return results;
    }

    // =========================================================================
    // Query A — changes in review state owned by analyst(s)
    // =========================================================================

    public List<Map<String, String>> getChangesByAnalyst(List<String> loginIds) {
        if (loginIds == null || loginIds.isEmpty()) return Collections.emptyList();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < loginIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }

        String sql =
            "SELECT c.CHANGE_NUMBER AS change_number, " +
            "       c.DESCRIPTION AS description_of_change, " +
            "       n_status.DESCRIPTION AS status, " +
            "       n_workflow.DESCRIPTION AS workflow, " +
            "       n_subclass.DESCRIPTION AS change_type, " +
            "       le_priority.ENTRYVALUE AS priority, " +
            "       u.LOGINID AS owner_loginid " +
            "FROM change c " +
            "JOIN agileuser u ON u.ID = c.OWNER " +
            "LEFT JOIN nodetable n_status ON n_status.ID = c.STATUS " +
            "LEFT JOIN nodetable n_workflow ON n_workflow.ID = c.WORKFLOW_ID " +
            "LEFT JOIN nodetable n_subclass ON n_subclass.ID = c.SUBCLASS " +
            "LEFT JOIN listentry le_priority ON le_priority.ENTRYID = c.CATEGORY " +
            "                                AND le_priority.LANGID = 0 " +
            "WHERE u.LOGINID IN (" + placeholders + ") " +
            "  AND c.STATUSTYPE = 2 " +
            "ORDER BY c.CHANGE_NUMBER";

        List<Map<String, String>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(90); // multi-analyst queries can be slow
            for (int i = 0; i < loginIds.size(); i++) {
                ps.setString(i + 1, loginIds.get(i).trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("changeNumber", nvl(rs.getString("change_number")));
                    row.put("description", nvl(rs.getString("description_of_change")));
                    row.put("status", nvl(rs.getString("status")));
                    row.put("workflow", nvl(rs.getString("workflow")));
                    row.put("changeType", nvl(rs.getString("change_type")));
                    row.put("priority", nvl(rs.getString("priority")));
                    row.put("ownerLoginId", nvl(rs.getString("owner_loginid")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            String msg = e.getMessage();
            logger.warning("Change review query failed for " + loginIds + ": " + msg);
            if (msg != null && msg.contains("ORA-01013")) {
                throw new RuntimeException("Query timed out — try selecting fewer analysts or a single country", e);
            }
            throw new RuntimeException("Query failed: " + msg, e);
        }
        return results;
    }

    // =========================================================================
    // Query B — signoff detail for a specific change
    // =========================================================================

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    public Map<String, Object> getSignoffDetail(String changeNumber) {
        // Use ROW_NUMBER to keep only the latest signoff per reviewer
        // (handles resubmission cycles that create duplicate rows)
        String sql =
            "SELECT action, reqd, reviewer, signoff_user, " +
            "       local_client_time, signoff_comments, signoff_duration_days " +
            "FROM ( " +
            "    SELECT " +
            "        CASE s.SIGNOFF_STATUS " +
            "             WHEN 0 THEN 'Awaiting Approval' " +
            "             WHEN 2 THEN 'Approved' " +
            "             WHEN 3 THEN 'Rejected' " +
            "             WHEN 4 THEN NULL " +
            "             WHEN 6 THEN 'Acknowledged' " +
            "             ELSE TO_CHAR(s.SIGNOFF_STATUS) END AS action, " +
            "        CASE WHEN s.REQUIRED IN (3, 5) THEN 'Yes' " +
            "             WHEN s.REQUIRED = 6 THEN 'No' " +
            "             ELSE 'Yes' END AS reqd, " +
            "        s.USER_NAME_ASSIGNED AS reviewer, " +
            "        s.USER_NAME_SIGNED AS signoff_user, " +
            "        ch.TIMESTAMP AS local_client_time, " +
            "        ch.COMMENTS AS signoff_comments, " +
            "        CASE WHEN s.USER_SIGNED IS NOT NULL " +
            "             THEN TRUNC(s.LAST_UPD) - TRUNC(s.CREATED) " +
            "             ELSE TRUNC(SYSDATE) - TRUNC(s.CREATED) " +
            "             END AS signoff_duration_days, " +
            "        ROW_NUMBER() OVER (PARTITION BY s.USER_NAME_ASSIGNED " +
            "                           ORDER BY CASE WHEN s.SIGNOFF_STATUS = 0 THEN 0 ELSE 1 END, (TRUNC(NVL(s.LAST_UPD, SYSDATE)) - TRUNC(s.CREATED)) DESC NULLS LAST) AS rn " +
            "    FROM change c " +
            "    JOIN signoff s ON s.CHANGE_ID = c.ID " +
            "                    AND s.PROCESS_ID = c.PROCESS_ID " +
            "    LEFT JOIN change_history ch ON ch.ID = s.SIGNOFF_HISTORYID " +
            "    WHERE c.CHANGE_NUMBER = ? " +
            "      AND s.USER_NAME_ASSIGNED IS NOT NULL " +
            "      AND s.SIGNOFF_STATUS NOT IN (4, 9) " +
            ") WHERE rn = 1 " +
            "ORDER BY signoff_duration_days DESC NULLS LAST";

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, changeNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("action", nvl(rs.getString("action")));
                    row.put("reqd", nvl(rs.getString("reqd")));
                    row.put("reviewer", nvl(rs.getString("reviewer")));
                    row.put("signoffUser", nvl(rs.getString("signoff_user")));
                    Timestamp ts = rs.getTimestamp("local_client_time", utcCal());
                    row.put("localClientTime", ts != null ? ts.getTime() : null);
                    row.put("signoffComments", rs.getString("signoff_comments"));
                    Object duration = rs.getObject("signoff_duration_days");
                    row.put("signoffDurationDays", duration != null ? ((Number) duration).intValue() : null);
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("Signoff detail query failed for " + changeNumber + ": " + e.getMessage());
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }

        result.put("changeNumber", changeNumber.trim());
        result.put("rows", rows);
        return result;
    }

    // =========================================================================
    // Signoff summary for a batch of change numbers (for results table badges)
    // =========================================================================

    public Map<String, Map<String, Object>> getSignoffSummaries(List<String> changeNumbers) {
        if (changeNumbers == null || changeNumbers.isEmpty()) return Collections.emptyMap();

        // Chunk large lists to avoid Oracle IN-clause limit (~1000)
        if (changeNumbers.size() > 500) {
            Map<String, Map<String, Object>> combined = new LinkedHashMap<>();
            for (int i = 0; i < changeNumbers.size(); i += 500) {
                List<String> chunk = changeNumbers.subList(i, Math.min(i + 500, changeNumbers.size()));
                combined.putAll(getSignoffSummaries(chunk));
            }
            return combined;
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < changeNumbers.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }

        // Per-reviewer rows: deduplicated, with comments for rejection reason (P2.1)
        // and last_signoff_ts for activity ping (P2.2)
        String sql =
            "SELECT change_number, signoff_status, required, reviewer, awaiting_days, " +
            "       signoff_comments, last_upd_ts FROM ( " +
            "    SELECT c.CHANGE_NUMBER AS change_number, " +
            "           s.SIGNOFF_STATUS AS signoff_status, " +
            "           s.REQUIRED AS required, " +
            "           s.USER_NAME_ASSIGNED AS reviewer, " +
            "           CASE WHEN s.SIGNOFF_STATUS = 0 " +
            "                THEN TRUNC(SYSDATE) - TRUNC(s.CREATED) END AS awaiting_days, " +
            "           ch.COMMENTS AS signoff_comments, " +
            "           s.LAST_UPD AS last_upd_ts, " +
            "           ROW_NUMBER() OVER (PARTITION BY c.CHANGE_NUMBER, s.USER_NAME_ASSIGNED " +
            "                              ORDER BY CASE WHEN s.SIGNOFF_STATUS = 0 THEN 0 ELSE 1 END, (TRUNC(NVL(s.LAST_UPD, SYSDATE)) - TRUNC(s.CREATED)) DESC NULLS LAST) AS rn " +
            "    FROM change c " +
            "    JOIN signoff s ON s.CHANGE_ID = c.ID AND s.PROCESS_ID = c.PROCESS_ID " +
            "    LEFT JOIN change_history ch ON ch.ID = s.SIGNOFF_HISTORYID " +
            "    WHERE c.CHANGE_NUMBER IN (" + placeholders + ") " +
            "      AND s.USER_NAME_ASSIGNED IS NOT NULL " +
            "      AND s.SIGNOFF_STATUS NOT IN (4, 9) " +
            ") WHERE rn = 1 " +
            "ORDER BY change_number, awaiting_days DESC NULLS LAST";

        // Collect per-change reviewer lists + compute summaries
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < changeNumbers.size(); i++) {
                ps.setString(i + 1, changeNumbers.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cn = rs.getString("change_number");
                    int status = rs.getInt("signoff_status");
                    int required = rs.getInt("required");
                    // Match the SQL CASE: REQUIRED=6 → No, everything else → Yes
                    boolean isRequired = (required != 6);
                    String reviewer = nvl(rs.getString("reviewer"));
                    Object awaitDays = rs.getObject("awaiting_days");

                    Map<String, Object> summary = result.get(cn);
                    if (summary == null) {
                        summary = new LinkedHashMap<>();
                        summary.put("approved", 0);
                        summary.put("awaiting", 0);
                        summary.put("acknowledged", 0);
                        summary.put("rejected", 0);
                        summary.put("total", 0);
                        summary.put("requiredTotal", 0);
                        summary.put("requiredDone", 0);
                        summary.put("maxAwaitingDays", null);
                        summary.put("reviewers", new ArrayList<Map<String, Object>>());
                        result.put(cn, summary);
                    }

                    // Observers (Acknowledged, status=6) are tracked separately — excluded from progression
                    boolean isObserver = (status == 6);
                    if (isObserver) {
                        summary.put("acknowledged", (int) summary.get("acknowledged") + 1);
                    } else {
                        summary.put("total", (int) summary.get("total") + 1);
                        if (status == 2) summary.put("approved", (int) summary.get("approved") + 1);
                        else if (status == 0) summary.put("awaiting", (int) summary.get("awaiting") + 1);
                        else if (status == 3) summary.put("rejected", (int) summary.get("rejected") + 1);

                        // Track required-only counts for percentage (observers excluded)
                        if (isRequired) {
                            summary.put("requiredTotal", (int) summary.get("requiredTotal") + 1);
                            if (status == 2) {
                                summary.put("requiredDone", (int) summary.get("requiredDone") + 1);
                            }
                        }

                        if (awaitDays != null) {
                            int days = ((Number) awaitDays).intValue();
                            Integer cur = (Integer) summary.get("maxAwaitingDays");
                            if (cur == null || days > cur) summary.put("maxAwaitingDays", days);
                        }
                    }

                    // Track last activity timestamp across all reviewers
                    Timestamp lastUpd = rs.getTimestamp("last_upd_ts");
                    if (lastUpd != null) {
                        Long curMax = (Long) summary.get("lastActivityMs");
                        if (curMax == null || lastUpd.getTime() > curMax) {
                            summary.put("lastActivityMs", lastUpd.getTime());
                        }
                    }

                    // Add reviewer data
                    Map<String, Object> dot = new LinkedHashMap<>();
                    String action = status == 2 ? "Approved" : status == 0 ? "Awaiting"
                        : status == 3 ? "Rejected" : status == 6 ? "Observer" : "Other";
                    dot.put("name", reviewer);
                    dot.put("action", action);
                    dot.put("reqd", isRequired && !isObserver);
                    if (awaitDays != null) dot.put("days", ((Number) awaitDays).intValue());
                    String comment = rs.getString("signoff_comments");
                    if (comment != null && !comment.trim().isEmpty()) dot.put("comment", comment.trim());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> reviewers = (List<Map<String, Object>>) summary.get("reviewers");
                    reviewers.add(dot);
                }
            }
        } catch (SQLException e) {
            logger.warning("Signoff summary query failed: " + e.getMessage());
        }
        return result;
    }

    private String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
