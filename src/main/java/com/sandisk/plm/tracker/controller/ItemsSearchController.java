package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.ItemsSearchService;
import com.sandisk.plm.tracker.service.ItemsSearchService.Condition;
import com.sandisk.plm.tracker.service.ItemsSearchService.RunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Endpoints for the Items / Search sub-tab (PT-67).
 *
 * <p>{@code GET /api/items-search/columns} returns the column allow-list +
 * type metadata so the UI can render its dropdowns without hard-coding the
 * list client-side.</p>
 *
 * <p>{@code GET /api/items-search/distinct?column=X} returns distinct values
 * for a categorical column — same pattern as {@code /api/refdata/bom-filters}
 * but scoped to one column at a time, on demand.</p>
 *
 * <p>{@code POST /api/items-search/run} executes the search and returns the
 * capped row set + the total match count.</p>
 *
 * <p>{@code POST /api/items-search/export} streams the full match set
 * (capped at {@code ItemsSearchService.EXPORT_ROW_CAP}) as an .xlsx.</p>
 */
@RestController
@RequestMapping("/api/items-search")
public class ItemsSearchController {

    @Autowired private ItemsSearchService service;
    @Autowired private ActivityLogger activityLogger;

    @GetMapping("/columns")
    public ResponseEntity<?> columns(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItemsSearchService.ColumnDef c : ItemsSearchService.COLUMNS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("label", c.label);
            m.put("type", c.type.name());
            m.put("common", c.common);
            m.put("derived", c.derived);
            // Derived (Agile-sourced) columns are result-only: available in the
            // result-column picker but hidden from the filter-condition dropdown.
            m.put("filterable", !c.derived);
            m.put("operators", c.derived ? new ArrayList<>()
                    : new ArrayList<>(ItemsSearchService.opsFor(c.type)));
            out.add(m);
        }
        return ResponseEntity.ok(Collections.singletonMap("columns", out));
    }

    @GetMapping("/distinct")
    public ResponseEntity<?> distinct(HttpSession session, @RequestParam("column") String column) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        try {
            return ResponseEntity.ok(Collections.singletonMap("values", service.distinctValues(column)));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(err(iae.getMessage()));
        }
    }

    /** Search request body. */
    public static final class RunReq {
        public List<Condition> conditions;
        public List<String> columns;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(HttpSession session, @RequestBody RunReq req) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        RunResult r = service.run(
                req == null ? Collections.emptyList() : nz(req.conditions),
                req == null ? Collections.emptyList() : nz(req.columns));
        if (r.errorMessage == null) {
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                    "ITEMS_SEARCH_RUN",
                    "conditions=" + safeSummary(req) + " | matched=" + r.matchedCount
                            + " | shown=" + r.rows.size() + " | elapsedMs=" + r.elapsedMs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", r.rows);
        out.put("columns", r.columns);
        out.put("matchedCount", r.matchedCount);
        out.put("returnedCount", r.rows.size());
        out.put("truncated", r.truncated);
        out.put("elapsedMs", r.elapsedMs);
        if (r.errorMessage != null) out.put("error", r.errorMessage);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/export")
    public void export(HttpSession session, HttpServletResponse response,
                       @RequestBody RunReq req) throws IOException {
        if (!isLoggedIn(session)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"Login required.\"}");
            return;
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Items-Search-" + date + ".xlsx";
        response.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").toString());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try {
            service.writeExcel(response.getOutputStream(),
                    req == null ? Collections.emptyList() : nz(req.conditions),
                    req == null ? Collections.emptyList() : nz(req.columns));
            activityLogger.log(s(session, "username"), s(session, "displayName"),
                    "ITEMS_SEARCH_EXPORT",
                    "conditions=" + safeSummary(req));
        } catch (IllegalArgumentException iae) {
            response.reset();
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + iae.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static <T> List<T> nz(List<T> in) { return in == null ? Collections.emptyList() : in; }

    private static boolean isLoggedIn(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null && !u.toString().isEmpty();
    }

    private static String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v == null ? "" : v.toString();
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /** One-line, log-safe summary of a search request. Keeps the activity log
     *  readable when grepping; doesn't dump entire value lists. */
    private static String safeSummary(RunReq req) {
        if (req == null || req.conditions == null || req.conditions.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < req.conditions.size(); i++) {
            Condition c = req.conditions.get(i);
            if (i > 0) sb.append(' ').append(c.connector == null ? "AND" : c.connector).append(' ');
            sb.append(c.column).append(' ').append(c.operator);
            if (c.values != null && !c.values.isEmpty()) {
                sb.append(" [").append(c.values.size()).append(" values]");
            } else if (c.value != null) {
                String v = c.value.length() > 30 ? c.value.substring(0, 27) + "..." : c.value;
                sb.append(" '").append(v).append("'");
            }
        }
        return sb.toString();
    }
}
