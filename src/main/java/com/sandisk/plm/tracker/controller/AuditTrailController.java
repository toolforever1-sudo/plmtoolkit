package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AuditTrailService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * REST entry point for the Audit Trail sub-tab under Change History (PT-50).
 *
 * <p>One endpoint right now: <code>POST /api/audit-trail/query</code>. The frontend
 * builds the {@link AuditTrailService.Query} from the filter form and POSTs it
 * as JSON. We do <em>not</em> accept GET — the URL would explode with comma
 * lists, and Spring's parameter-binding doesn't deserialize nested JSON-style
 * filters cleanly via query string.
 *
 * <p>Excel export is intentionally NOT in Phase 2 — that's Phase 3 polish. For
 * now the JSON response is what the UI renders directly.
 */
@RestController
@RequestMapping("/api/audit-trail")
public class AuditTrailController {

    @Autowired
    private AuditTrailService auditTrailService;

    @Autowired
    private ActivityLogger activityLogger;

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody Map<String, Object> body, HttpSession session) {
        if (body == null) body = Collections.emptyMap();
        AuditTrailService.Query q = new AuditTrailService.Query();
        q.source        = asString(body.get("source"));    // PT-54: "item" or "change"
        q.itemNumbers   = asList(body.get("itemNumbers"));
        q.changeNumbers = asList(body.get("changeNumbers"));
        q.dateFrom      = asString(body.get("dateFrom"));
        q.dateTo        = asString(body.get("dateTo"));
        q.details       = asString(body.get("details"));
        q.detailsMode   = asString(body.get("detailsMode"));
        q.comments      = asString(body.get("comments"));
        q.commentsMode  = asString(body.get("commentsMode"));
        q.usernames     = asList(body.get("usernames"));
        q.action        = asString(body.get("action"));
        q.actionMode    = asString(body.get("actionMode"));
        Object max = body.get("maxRows");
        if (max instanceof Number) q.maxRows = ((Number) max).intValue();

        AuditTrailService.Result result = auditTrailService.run(q);

        if (result.error != null) {
            // 400 for input validation, 500 for SQL failure — we don't currently distinguish
            // because the service rolls them both into Result.error. Returning 400 keeps it
            // visible to the UI without it being treated as a Spring/Tomcat error.
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", result.error);
            return ResponseEntity.badRequest().body(err);
        }

        // Log every successful query (small payload, useful for debugging slow filters).
        String me = (String) session.getAttribute("username");
        String display = (String) session.getAttribute("displayName");
        activityLogger.log(me == null ? "anonymous" : me,
                           display == null ? me : display,
                           "AUDIT_TRAIL_QUERY",
                           "source=" + result.source + " rows=" + result.rowCount + " truncated=" + result.truncated
                           + " items=" + q.itemNumbers.size() + " changes=" + q.changeNumbers.size()
                           + " elapsedMs=" + result.elapsedMs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("source", result.source);
        out.put("rows", result.rows);
        out.put("rowCount", result.rowCount);
        out.put("truncated", result.truncated);
        out.put("elapsedMs", result.elapsedMs);
        if (result.hint != null) out.put("hint", result.hint);   // PT-66
        return ResponseEntity.ok(out);
    }

    /**
     * Excel export of the full query result. Uses the same filter contract as
     * {@code /query} — the UI passes the last submitted payload, not the
     * in-memory rows, so "Export everything" from the ECN is honored even if
     * the user has client-side column filters narrowing the visible view.
     */
    @PostMapping("/export")
    public void export(@RequestBody Map<String, Object> body, HttpSession session,
                       HttpServletResponse response) throws IOException {
        if (body == null) body = Collections.emptyMap();
        AuditTrailService.Query q = new AuditTrailService.Query();
        q.source        = asString(body.get("source"));
        q.itemNumbers   = asList(body.get("itemNumbers"));
        q.changeNumbers = asList(body.get("changeNumbers"));
        q.dateFrom      = asString(body.get("dateFrom"));
        q.dateTo        = asString(body.get("dateTo"));
        q.details       = asString(body.get("details"));
        q.detailsMode   = asString(body.get("detailsMode"));
        q.comments      = asString(body.get("comments"));
        q.commentsMode  = asString(body.get("commentsMode"));
        q.usernames     = asList(body.get("usernames"));
        q.action        = asString(body.get("action"));
        q.actionMode    = asString(body.get("actionMode"));
        Object max = body.get("maxRows");
        if (max instanceof Number) q.maxRows = ((Number) max).intValue();

        AuditTrailService.Result result = auditTrailService.run(q);
        if (result.error != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"error\":\"" +
                    result.error.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            return;
        }

        String me = (String) session.getAttribute("username");
        String display = (String) session.getAttribute("displayName");
        activityLogger.log(me == null ? "anonymous" : me,
                           display == null ? me : display,
                           "AUDIT_TRAIL_EXPORT",
                           "source=" + result.source + " rows=" + result.rowCount + " truncated=" + result.truncated
                           + " elapsedMs=" + result.elapsedMs);

        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String filename = "audit-trail-" + result.source + "-" + ts + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        // PT-54: column set depends on which history table we queried. The
        // item-history rows have no prev/next status or comments — exporting
        // those columns blank would be misleading.
        String[] cols;
        String[] keys;
        int[] widths;
        if ("item".equals(result.source)) {
            cols   = new String[]{"Item #", "User", "Rev", "Date", "Details"};
            keys   = new String[]{"item_number", "user_name", "rev", "local_date", "details"};
            widths = new int[]{22, 28, 8, 22, 60};
        } else {
            cols   = new String[]{"Change #", "Action", "Prev Status", "Next Status", "User", "Affected Object", "Date", "Comments", "Details", "User(s) Notified"};
            keys   = new String[]{"change_number", "action", "prev_status", "next_status", "user_name", "affected_object", "local_date", "comments", "details", "users_notified"};
            widths = new int[]{18, 22, 18, 18, 28, 20, 22, 40, 50, 50};
        }

        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 100, false, false);
             OutputStream os = response.getOutputStream()) {
            SXSSFSheet sheet = wb.createSheet("Audit Trail");
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);

            Row header = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }
            int r = 1;
            final int EXCEL_CELL_MAX = 32_767;
            for (Map<String, Object> row : result.rows) {
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < keys.length; i++) {
                    Object v = row.get(keys[i]);
                    String s = v == null ? "" : String.valueOf(v);
                    if (s.length() > EXCEL_CELL_MAX) {
                        s = s.substring(0, EXCEL_CELL_MAX - 16) + " \u2026[truncated]";
                    }
                    xr.createCell(i).setCellValue(s);
                }
            }
            sheet.createFreezePane(0, 1);
            if (r > 1) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, r - 1, 0, cols.length - 1));
            }
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            wb.write(os);
            wb.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object raw) {
        if (raw == null) return new ArrayList<>();
        if (raw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) raw) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        // Accept a single comma/space/newline-separated string for convenience.
        String s = String.valueOf(raw);
        List<String> out = new ArrayList<>();
        for (String t : s.split("[,\\s]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
