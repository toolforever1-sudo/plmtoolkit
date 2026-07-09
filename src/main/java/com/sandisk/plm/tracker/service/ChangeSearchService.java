package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Volume-Reports backend: implements the ECO + MCO + AML change search SQL described
 * in {@code docs/CHANGE-SEARCH-API-SPEC.md}. One method, one SQL — change-type is a
 * column in the result, not a filter on the input.
 *
 * <p>Wired against the primary {@code dataSource} (Agile schema). Dates are converted
 * from the user's local zone to UTC in the app layer (the column is UTC).
 */
@Service
public class ChangeSearchService {

    private static final Logger logger = Logger.getLogger(ChangeSearchService.class.getName());

    private final DataSource dataSource;

    public ChangeSearchService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Result of {@link #search}. Fields mirror the JSON shape in the spec. */
    public static class Result {
        public final List<ResolvedUser> resolvedUsers;
        public final List<String> warnings;
        public final List<Map<String, Object>> rows;
        public final Map<String, Object> summary;

        public Result(List<ResolvedUser> resolvedUsers, List<String> warnings,
                      List<Map<String, Object>> rows, Map<String, Object> summary) {
            this.resolvedUsers = resolvedUsers;
            this.warnings = warnings;
            this.rows = rows;
            this.summary = summary;
        }
    }

    public static class ResolvedUser {
        public final String loginId;
        public final long agileUserId;
        public final String displayName;
        public ResolvedUser(String loginId, long agileUserId, String displayName) {
            this.loginId = loginId;
            this.agileUserId = agileUserId;
            this.displayName = displayName;
        }
    }

    /**
     * @param loginIds   AGILEUSER.LOGINID values (employee numbers like "22394"). Min 1.
     * @param fromDate   inclusive lower bound, midnight in {@code zone}
     * @param toDate     exclusive upper bound, midnight in {@code zone}
     * @param zone       IANA zone for date interpretation + output formatting
     */
    public Result search(List<String> loginIds, LocalDate fromDate, LocalDate toDate, ZoneId zone)
            throws SQLException {
        if (loginIds == null || loginIds.isEmpty())
            throw new IllegalArgumentException("loginIds must be non-empty");
        if (zone == null) zone = ZoneId.of("America/Los_Angeles");
        if (!fromDate.isBefore(toDate))
            throw new IllegalArgumentException("toDate must be after fromDate");

        Instant fromUtc = fromDate.atStartOfDay(zone).toInstant();
        Instant toUtc   = toDate.atStartOfDay(zone).toInstant();

        // === Query A: resolve loginIds → AGILEUSER.ID ===
        Map<String, ResolvedUser> resolvedByLoginId = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            String inList = repeatPlaceholders(loginIds.size());
            String sqlA =
                "SELECT ID, LOGINID, "
              + "       LAST_NAME || ', ' || FIRST_NAME || ' (' || LOGINID || ')' AS DISPLAY_NAME "
              + "  FROM agile.AGILEUSER WHERE LOGINID IN (" + inList + ")";
            try (PreparedStatement ps = conn.prepareStatement(sqlA)) {
                for (int i = 0; i < loginIds.size(); i++) ps.setString(i + 1, loginIds.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ResolvedUser u = new ResolvedUser(rs.getString("LOGINID"),
                                rs.getLong("ID"), rs.getString("DISPLAY_NAME"));
                        resolvedByLoginId.put(u.loginId, u);
                    }
                }
            }
        }

        // Surface unmatched loginIds as warnings — matches spec section 2.
        List<String> warnings = new ArrayList<>();
        for (String id : loginIds) {
            if (!resolvedByLoginId.containsKey(id)) {
                warnings.add("loginId not found: " + id);
            }
        }

        if (resolvedByLoginId.isEmpty()) {
            // None matched → empty result with warnings populated. Caller decides whether to 404.
            return new Result(new ArrayList<>(), warnings, new ArrayList<>(), emptySummary());
        }

        List<Long> agileUserIds = new ArrayList<>();
        for (ResolvedUser u : resolvedByLoginId.values()) agileUserIds.add(u.agileUserId);

        // === Query B: main search ===
        String inUserIds = repeatPlaceholders(agileUserIds.size());
        String sqlB =
            "SELECT \n"
          + "  c.CHANGE_NUMBER                                                          AS NUMBER_, \n"
          + "  c.DESCRIPTION                                                            AS DESCRIPTION_, \n"
          + "  (SELECT n.DESCRIPTION FROM agile.NODETABLE n WHERE n.ID = c.STATUS)      AS STATUS, \n"
          + "  (SELECT n.DESCRIPTION FROM agile.NODETABLE n WHERE n.ID = c.WORKFLOW_ID) AS WORKFLOW, \n"
          + "  (SELECT n.DESCRIPTION FROM agile.NODETABLE n WHERE n.ID = c.SUBCLASS)    AS CHANGE_TYPE, \n"
          + "  (SELECT LISTAGG(le.ENTRYVALUE, '|') WITHIN GROUP (ORDER BY le.ENTRYVALUE) \n"
          + "     FROM agile.LISTENTRY le \n"
          + "    WHERE le.LANGID = 0 \n"
          + "      AND INSTR(c.PRODUCT_LINES, ',' || le.ENTRYID || ',') > 0)            AS PRODUCT_LINES, \n"
          + "  (SELECT MIN(le.ENTRYVALUE) FROM agile.LISTENTRY le \n"
          + "    WHERE le.ENTRYID = c.CATEGORY AND le.LANGID = 0)                       AS PRIORITY, \n"
          + "  i.ITEM_NUMBER                                                            AS ITEM_NUMBER_AFFECTED, \n"
          + "  cu.LAST_NAME || ', ' || cu.FIRST_NAME || ' (' || cu.LOGINID || ')'       AS CREATE_USER, \n"
          + "  c.CREATE_DATE                                                            AS DATE_ORIGINATED_UTC, \n"
          + "  c.RELEASE_DATE                                                           AS DATE_RELEASED_UTC, \n"
          + "  CASE WHEN ru.ID IS NULL THEN NULL \n"
          + "       ELSE ru.LAST_NAME || ', ' || ru.FIRST_NAME || ' (' || ru.LOGINID || ')' \n"
          + "  END                                                                      AS REQUESTOR, \n"
          + "  CASE WHEN au.ID IS NULL THEN NULL \n"
          + "       ELSE au.LAST_NAME || ', ' || au.FIRST_NAME || ' (' || au.LOGINID || ')' \n"
          + "  END                                                                      AS ANALYST, \n"
          + "  CASE \n"
          + "    WHEN p3.LIST36 IS NULL THEN NULL \n"
          + "    ELSE \n"
          + "      NVL2( \n"
          + "        (SELECT pe.ENTRYVALUE FROM agile.LISTENTRY le \n"
          + "           JOIN agile.LISTENTRY pe ON pe.ENTRYID = le.PARENT_ENTRY AND pe.LANGID = 0 \n"
          + "          WHERE le.ENTRYID = p3.LIST36 AND le.LANGID = 0 AND ROWNUM = 1), \n"
          + "        (SELECT pe.ENTRYVALUE FROM agile.LISTENTRY le \n"
          + "           JOIN agile.LISTENTRY pe ON pe.ENTRYID = le.PARENT_ENTRY AND pe.LANGID = 0 \n"
          + "          WHERE le.ENTRYID = p3.LIST36 AND le.LANGID = 0 AND ROWNUM = 1) || '|' || \n"
          + "        (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le \n"
          + "          WHERE le.ENTRYID = p3.LIST36 AND le.LANGID = 0 AND ROWNUM = 1), \n"
          + "        (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le \n"
          + "          WHERE le.ENTRYID = p3.LIST36 AND le.LANGID = 0 AND ROWNUM = 1) \n"
          + "      ) \n"
          + "  END                                                                      AS CLASSIFICATION \n"
          + "FROM            agile.CHANGE      c \n"
          + "JOIN            agile.PAGE_TWO    p2 ON p2.ID = c.ID  AND p2.CLASS = c.CLASS \n"
          + "LEFT JOIN       agile.PAGE_THREE  p3 ON p3.ID = c.ID  AND p3.CLASS = c.CLASS \n"
          + "LEFT JOIN       agile.REV         r  ON r.CHANGE = c.ID AND r.SITE  = 0 \n"
          + "LEFT JOIN       agile.ITEM        i  ON i.ID = r.ITEM \n"
          + "LEFT JOIN       agile.AGILEUSER   cu ON cu.ID = p2.CREATE_USER \n"
          + "LEFT JOIN       agile.AGILEUSER   ru ON ru.ID = c.ORIGINATOR \n"
          + "LEFT JOIN       agile.AGILEUSER   au ON au.ID = c.OWNER \n"
          + "WHERE (c.CHANGE_NUMBER LIKE 'ECO-%' \n"
          + "   OR  c.CHANGE_NUMBER LIKE 'MCO-%' \n"
          + "   OR  c.CHANGE_NUMBER LIKE 'AML-%') \n"
          + "  AND c.CREATE_DATE >= ? \n"
          + "  AND c.CREATE_DATE <  ? \n"
          + "  AND p2.CREATE_USER IN (" + inUserIds + ") \n"
          + "  AND NVL(c.DELETE_FLAG, 0) != 1 \n"
          + "ORDER BY c.CHANGE_NUMBER, i.ITEM_NUMBER";

        DateTimeFormatter outFmt = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a zzz");
        List<Map<String, Object>> rows = new ArrayList<>();
        long startMs = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlB)) {
            ps.setTimestamp(1, Timestamp.from(fromUtc));
            ps.setTimestamp(2, Timestamp.from(toUtc));
            for (int i = 0; i < agileUserIds.size(); i++) {
                ps.setLong(3 + i, agileUserIds.get(i));
            }
            ps.setQueryTimeout(120); // 2 minutes max
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("number", rs.getString("NUMBER_"));
                    row.put("description", rs.getString("DESCRIPTION_"));
                    row.put("status", rs.getString("STATUS"));
                    row.put("workflow", rs.getString("WORKFLOW"));
                    row.put("changeType", normalizeChangeType(rs.getString("NUMBER_")));
                    row.put("subclass", rs.getString("CHANGE_TYPE"));
                    row.put("productLines", rs.getString("PRODUCT_LINES"));
                    row.put("priority", rs.getString("PRIORITY"));
                    row.put("itemNumberAffected", emptyIfNull(rs.getString("ITEM_NUMBER_AFFECTED")));
                    row.put("createUser", rs.getString("CREATE_USER"));
                    row.put("dateOriginated", formatTs(rs.getTimestamp("DATE_ORIGINATED_UTC"), zone, outFmt));
                    row.put("dateReleased",   formatTs(rs.getTimestamp("DATE_RELEASED_UTC"),   zone, outFmt));
                    row.put("requestor", rs.getString("REQUESTOR"));
                    row.put("analyst", rs.getString("ANALYST"));
                    row.put("classification", rs.getString("CLASSIFICATION"));
                    rows.add(row);
                }
            }
        }
        long elapsed = System.currentTimeMillis() - startMs;

        Map<String, Object> summary = buildSummary(rows);
        logger.info(String.format("[CHANGE-SEARCH] users=%d window=%s..%s rows=%d distinct=%s in %dms",
                agileUserIds.size(), fromDate, toDate, rows.size(),
                summary.getOrDefault("distinctChanges", 0), elapsed));

        return new Result(new ArrayList<>(resolvedByLoginId.values()), warnings, rows, summary);
    }

    // ----- helpers -----

    private static String repeatPlaceholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static String normalizeChangeType(String changeNumber) {
        if (changeNumber == null) return "";
        if (changeNumber.startsWith("ECO-")) return "ECO";
        if (changeNumber.startsWith("MCO-")) return "MCO";
        if (changeNumber.startsWith("AML-")) return "AML";
        return changeNumber.split("-", 2)[0];
    }

    private static String emptyIfNull(String s) { return s == null ? "" : s; }

    private static String formatTs(Timestamp ts, ZoneId zone, DateTimeFormatter fmt) {
        if (ts == null) return null;
        return ts.toInstant().atZone(zone).format(fmt);
    }

    private static Map<String, Object> emptySummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalRows", 0);
        s.put("distinctChanges", 0);
        s.put("byChangeType", new LinkedHashMap<>());
        s.put("byStatus", new LinkedHashMap<>());
        return s;
    }

    private static Map<String, Object> buildSummary(List<Map<String, Object>> rows) {
        Map<String, Set<String>> distinctByType = new LinkedHashMap<>();
        Map<String, Integer> rowsByType = new LinkedHashMap<>();
        Map<String, Integer> rowsByStatus = new LinkedHashMap<>();
        Set<String> distinctChanges = new HashSet<>();
        for (Map<String, Object> r : rows) {
            String type = (String) r.getOrDefault("changeType", "OTHER");
            String num = (String) r.get("number");
            String status = (String) r.getOrDefault("status", "");
            rowsByType.merge(type, 1, Integer::sum);
            distinctByType.computeIfAbsent(type, k -> new HashSet<>()).add(num);
            distinctChanges.add(num);
            if (status != null && !status.isEmpty()) rowsByStatus.merge(status, 1, Integer::sum);
        }
        Map<String, Object> byChangeType = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : rowsByType.entrySet()) {
            Map<String, Integer> bucket = new LinkedHashMap<>();
            bucket.put("rows", e.getValue());
            bucket.put("distinct", distinctByType.getOrDefault(e.getKey(), Collections.emptySet()).size());
            byChangeType.put(e.getKey(), bucket);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRows", rows.size());
        out.put("distinctChanges", distinctChanges.size());
        out.put("byChangeType", byChangeType);
        out.put("byStatus", rowsByStatus);
        return out;
    }
}
