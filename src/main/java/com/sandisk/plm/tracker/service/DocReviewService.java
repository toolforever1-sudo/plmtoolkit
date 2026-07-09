package com.sandisk.plm.tracker.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Backs the Review Tracker tab. Runs the Agile-side "documents up for review"
 * search from {@code documents_next_review_search.sql}: one row per
 * (Document, Pending Change) pair, joined with Document Owner(s) from
 * {@code AGILE_FLEX[attid=251733512]}, gated by {@code page_three.date32}
 * (Next Review Date) and the lifecycle phase exclusion list.
 *
 * <p>Validated 2026-05-18 against an Agile UI export: query returns ~1,628
 * rows / 1,459 unique items for {@code TODAY+30}.
 */
@Service
public class DocReviewService {

    private static final Logger logger = Logger.getLogger(DocReviewService.class.getName());

    /** Document subclass id in {@code agile.item.subclass}. */
    private static final int DOCUMENT_SUBCLASS = 9141;
    /** Lifecycle phases to exclude (matches the Agile UI search filter). */
    private static final String EXCLUDED_PHASES_SQL = "'OBS', 'OBS-SKU', 'Preliminary'";
    /** ATTID for the {@code documentOwnerS} multilist in AGILE_FLEX. */
    private static final long OWNER_ATTID = 251733512L;
    /** Query timeout — the CTE-optimized plan runs in ~1.5 s on cold cache; we
     *  give it 60 s of headroom for slow days. The pre-optimization version
     *  needed ~180 s (the owner LISTAGG scanned all 11,762 owner-tagged items
     *  before the date filter); the CTE pushes the date filter down first. */
    private static final int QUERY_TIMEOUT_SEC = 60;

    private final DataSource agileDataSource;

    public DocReviewService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    // Single-slot in-memory cache. The Agile-side query is ~3 min cold (the
    // owner LISTAGG dominates), so subsequent /data calls and the /export
    // endpoint reuse the last result when the filter fingerprint matches.
    // Cleared by force=true (which the Refresh button passes).
    private volatile String cacheKey = "";
    private volatile List<Map<String, Object>> cacheRows = new ArrayList<>();
    private volatile long cacheBuiltAtMs = 0;

    /** Date-window preset. */
    public enum Window {
        OVERDUE,   // next_review_date <= TODAY
        DAYS_7,    // <= TODAY+7
        DAYS_30,   // <= TODAY+30 (default — matches the SQL file)
        DAYS_90,   // <= TODAY+90
        CUSTOM     // bounded by from/to (inclusive)
    }

    /** Parse a window key from the request param. Defaults to DAYS_30. */
    public static Window parseWindow(String s) {
        if (s == null) return Window.DAYS_30;
        switch (s.trim().toLowerCase()) {
            case "overdue": return Window.OVERDUE;
            case "7d":  case "next7d":  case "days_7":  return Window.DAYS_7;
            case "30d": case "next30d": case "days_30": return Window.DAYS_30;
            case "90d": case "next90d": case "days_90": return Window.DAYS_90;
            case "custom": return Window.CUSTOM;
            default: return Window.DAYS_30;
        }
    }

    /**
     * Run the doc-review query and return one row per (document, pending change).
     * Documents with zero pending changes appear with empty change columns.
     *
     * @param window date-window preset; CUSTOM honors {@code fromIso}/{@code toIso}
     * @param fromIso inclusive lower bound (yyyy-MM-dd) — used only when window=CUSTOM
     * @param toIso inclusive upper bound (yyyy-MM-dd) — used only when window=CUSTOM
     */
    public List<Map<String, Object>> search(Window window, String fromIso, String toIso) {
        return search(window, fromIso, toIso, false);
    }

    /**
     * @param force when true, bypass the in-memory cache and re-query Agile.
     *              The Refresh button passes this; export and tab-click pass
     *              false so they reuse the cached rows when filters match.
     */
    public synchronized List<Map<String, Object>> search(Window window, String fromIso, String toIso, boolean force) {
        String key = window + "|" + nvl(fromIso) + "|" + nvl(toIso);
        if (!force && key.equals(cacheKey) && !cacheRows.isEmpty()) {
            logger.info("[DOC-REVIEW] cache hit (" + cacheRows.size() + " rows, key=" + key + ")");
            return cacheRows;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        // Build the upper-bound clause. Default is "<= TODAY+N"; CUSTOM uses a
        // pair of TO_DATE bounds (inclusive of the end day via "+ 1").
        String dateClause;
        switch (window) {
            case OVERDUE: dateClause = "p3.date32 <= TRUNC(SYSDATE)"; break;
            case DAYS_7:  dateClause = "p3.date32 <= TRUNC(SYSDATE) + 7"; break;
            case DAYS_90: dateClause = "p3.date32 <= TRUNC(SYSDATE) + 90"; break;
            case CUSTOM: {
                StringBuilder sb = new StringBuilder();
                if (fromIso != null && !fromIso.isEmpty())
                    sb.append("p3.date32 >= TO_DATE(?, 'YYYY-MM-DD')");
                if (toIso != null && !toIso.isEmpty()) {
                    if (sb.length() > 0) sb.append(" AND ");
                    sb.append("p3.date32 <  TO_DATE(?, 'YYYY-MM-DD') + 1");
                }
                dateClause = sb.length() == 0 ? "1=1" : sb.toString();
                break;
            }
            case DAYS_30:
            default:      dateClause = "p3.date32 <= TRUNC(SYSDATE) + 30"; break;
        }

        // CTE-optimized plan: filter ITEM × PAGE_THREE by date+subclass FIRST,
        // then carry the ~1,459-row qualified set down into the owner LISTAGG
        // and pending-changes subqueries via IN-subselect. Validated 2026-05-18
        // against the prior plan: identical 1,628-row output, ~1.5 s vs ~152 s
        // cold. See documents_next_review_search_optimized.sql for design notes.
        String sql =
            "WITH qualified_items AS ( " +
            "  SELECT i.id, i.item_number, i.description, i.subclass, i.default_change, " +
            "         p3.date32 AS next_review_date " +
            "  FROM   agile.item       i " +
            "  JOIN   agile.page_three p3 ON p3.id = i.id " +
            "  WHERE  i.subclass = " + DOCUMENT_SUBCLASS + " " +
            "    AND  NVL(i.delete_flag, 0) != 1 " +
            "    AND  " + dateClause + " " +
            "), " +
            "qualified_with_phase AS ( " +
            "  SELECT qi.id, qi.item_number, qi.description, qi.subclass, qi.next_review_date, " +
            "         r_curr.rev_number, lcp.description AS lifecycle_phase " +
            "  FROM   qualified_items qi " +
            "  JOIN   agile.rev       r_curr ON r_curr.item   = qi.id " +
            "                              AND r_curr.change = qi.default_change " +
            "                              AND r_curr.site   = 0 " +
            "  JOIN   agile.nodetable lcp    ON lcp.id = r_curr.release_type " +
            "  WHERE  lcp.description NOT IN (" + EXCLUDED_PHASES_SQL + ") " +
            "), " +
            "owners AS ( " +
            "  SELECT af.id, " +
            "         LISTAGG(u.last_name || ', ' || u.first_name || ' (' || u.loginid || ')', '; ') " +
            "           WITHIN GROUP (ORDER BY u.last_name, u.first_name) AS owner_list " +
            "  FROM   agile.agile_flex af " +
            "  JOIN   agile.agileuser  u ON af.text LIKE '%,' || u.id || ',%' " +
            "  WHERE  af.attid = " + OWNER_ATTID + " " +
            "    AND  af.id IN (SELECT id FROM qualified_with_phase) " +
            "  GROUP BY af.id " +
            "), " +
            "pending_changes AS ( " +
            "  SELECT DISTINCT " +
            "         r.item              AS item_id, " +
            "         c.id                AS change_id, " +
            "         c.change_number     AS change_number, " +
            "         sub.name            AS change_type " +
            "  FROM   agile.rev    r " +
            "  JOIN   agile.change c   ON c.id         = r.change " +
            "                         AND c.statustype IN (0, 1, 2) " +
            "                         AND NVL(c.delete_flag, 0) != 1 " +
            "  JOIN   agile.nodetable sub ON sub.id = c.subclass " +
            "  WHERE  r.site = 0 " +
            "    AND  r.item IN (SELECT id FROM qualified_with_phase) " +
            ") " +
            "SELECT q.item_number                              AS item_number, " +
            "       q.description                              AS description, " +
            "       q.lifecycle_phase                          AS lifecycle_phase, " +
            "       q.rev_number                               AS rev, " +
            "       sub.name                                   AS document_type, " +
            "       o.owner_list                               AS owners, " +
            "       pc.change_number                           AS change_number, " +
            "       pc.change_type                             AS change_type, " +
            "       TO_CHAR(q.next_review_date, 'YYYY-MM-DD')  AS next_review_date " +
            "FROM      qualified_with_phase q " +
            "JOIN      agile.nodetable      sub ON sub.id = q.subclass " +
            "LEFT JOIN owners               o   ON o.id      = q.id " +
            "LEFT JOIN pending_changes      pc  ON pc.item_id = q.id " +
            "ORDER BY  q.item_number, pc.change_number";

        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            ps.setFetchSize(1000);
            if (window == Window.CUSTOM) {
                int idx = 1;
                if (fromIso != null && !fromIso.isEmpty()) ps.setString(idx++, fromIso);
                if (toIso   != null && !toIso.isEmpty())   ps.setString(idx++, toIso);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("itemNumber",      nvl(rs.getString("item_number")));
                    r.put("description",     nvl(rs.getString("description")));
                    r.put("lifecyclePhase",  nvl(rs.getString("lifecycle_phase")));
                    r.put("rev",             nvl(rs.getString("rev")));
                    r.put("documentType",    nvl(rs.getString("document_type")));
                    r.put("owners",          nvl(rs.getString("owners")));
                    r.put("changeNumber",    nvl(rs.getString("change_number")));
                    r.put("changeType",      nvl(rs.getString("change_type")));
                    r.put("nextReviewDate",  nvl(rs.getString("next_review_date")));
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            logger.warning("[DOC-REVIEW] query failed: " + e.getMessage());
            throw new RuntimeException("Doc Review query failed: " + e.getMessage(), e);
        }
        long ms = System.currentTimeMillis() - t0;
        cacheKey = key;
        cacheRows = out;
        cacheBuiltAtMs = System.currentTimeMillis();
        logger.info("[DOC-REVIEW] " + window + " → " + out.size() + " rows in " + ms + " ms (cached)");
        return out;
    }

    /** Last cache build time (epoch ms), or 0 if no cache yet. Exposed so the
     *  controller can echo "Last fetched 12 min ago" back to the UI. */
    public long cacheBuiltAtMs() { return cacheBuiltAtMs; }
    public String cacheKey()     { return cacheKey; }

    /** Write the search results to an .xlsx workbook. Same column order as the
     *  on-screen table. */
    public void writeExcel(OutputStream out, List<Map<String, Object>> rows) throws IOException {
        String[] headers = {
            "Number", "Description", "Lifecycle Phase", "Rev", "Document Type",
            "Document Owner(s)", "Next Review Date", "Pending Change", "Pending Change Type"
        };
        String[] keys = {
            "itemNumber", "description", "lifecyclePhase", "rev", "documentType",
            "owners", "nextReviewDate", "changeNumber", "changeType"
        };
        SXSSFWorkbook wb = new SXSSFWorkbook(null, 1000, false, false);
        try {
            Sheet sheet = wb.createSheet("Docs Due for Review");

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
            for (Map<String, Object> row : rows) {
                Row x = sheet.createRow(r);
                for (int i = 0; i < keys.length; i++) {
                    Object v = row.get(keys[i]);
                    x.createCell(i).setCellValue(v == null ? "" : v.toString());
                }
                if (r % 2 == 0) {
                    for (int c = 0; c < keys.length; c++) x.getCell(c).setCellStyle(alt);
                }
                r++;
            }
            int[] widths = { 4500, 9000, 3500, 2000, 3500, 8000, 3500, 4500, 4500 };
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

    /** Resolve a window key + optional bounds into the effective from/to dates
     *  the UI can echo back. */
    public static Map<String, String> describeWindow(Window window, String fromIso, String toIso) {
        Map<String, String> m = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        String label;
        String from = null, to;
        switch (window) {
            case OVERDUE:
                label = "Overdue (≤ today)";
                to = today.toString();
                break;
            case DAYS_7:
                label = "Next 7 days";
                from = today.toString();
                to = today.plusDays(7).toString();
                break;
            case DAYS_90:
                label = "Next 90 days";
                from = today.toString();
                to = today.plusDays(90).toString();
                break;
            case CUSTOM:
                label = "Custom";
                from = nvl(fromIso);
                to   = nvl(toIso);
                break;
            case DAYS_30:
            default:
                label = "Next 30 days";
                from = today.toString();
                to = today.plusDays(30).toString();
                break;
        }
        m.put("label", label);
        if (from != null) m.put("from", from);
        m.put("to", to);
        return m;
    }

    private static String nvl(String s) { return s == null ? "" : s.trim(); }
}
