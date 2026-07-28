package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.UserImportService;
import com.sandisk.plm.tracker.service.UserImportService.PreviewResult;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome;
import com.sandisk.plm.tracker.util.UserColumnMapper;
import com.sandisk.plm.tracker.util.UserSheetParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.util.*;

/**
 * Bulk user import from a roster spreadsheet. Three steps:
 *   analyze  — parse + AI-map columns, return grid + mapping + best-guess rows
 *   resolve  — match {name,email} rows against AD + dedupe -> preview
 *   submit   — persist tab records + one consolidated DL-request email to IT
 * Same admin gate as {@link UserPermissionsController}.
 */
@RestController
@RequestMapping("/api/permissions/import")
public class UserImportController {

    @Autowired private UserSheetParser sheetParser;     // see Task 6 note: register as @Component
    @Autowired private UserColumnMapper columnMapper;
    @Autowired private UserImportService importService;
    @Autowired private UserPermissionsService permissionsService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam("file") MultipartFile file, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            UserSheetParser.ParsedSheet ps = sheetParser.parse(file);
            UserColumnMapper.Mapping map = columnMapper.map(ps.headers, ps.sampleRows());

            List<Map<String, String>> rows = new ArrayList<>();
            for (List<String> r : ps.rows) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", cell(r, map.nameColumn));
                m.put("email", cell(r, map.emailColumn));
                rows.add(m);
            }

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("nameColumn", map.nameColumn);
            mapping.put("emailColumn", map.emailColumn);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("columns", ps.headers);
            resp.put("allRows", ps.rows);          // full grid for client-side re-map
            resp.put("mapping", mapping);
            resp.put("confident", map.confident);
            resp.put("mappingQuestion", map.confident ? null : map.question);
            resp.put("method", map.method);
            resp.put("rows", rows);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err("Could not read the file: " + e.getMessage()));
        }
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> rows = rowsOf(body.get("rows"));
            PreviewResult res = importService.resolveAll(rows);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("rows", res.rows);
            resp.put("summary", res.summary);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> users = rowsOf(body.get("rows"));   // sAMAccountName, displayName, email
            List<String> tabs = listOf(body.get("allowedTabs"));
            if (users.isEmpty()) return ResponseEntity.badRequest().body(err("No users to submit."));
            List<BulkOutcome> outcomes = permissionsService.submitBulkDLRequest(
                users, tabs, username(session), displayName(session));
            int ok = 0; for (BulkOutcome o : outcomes) if (o.ok) ok++;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("submitted", users.size());
            resp.put("ok", ok);
            resp.put("failed", users.size() - ok);
            resp.put("outcomes", outcomes);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ---- helpers ----

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || row == null || idx >= row.size()) return "";
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> rowsOf(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Map<String, String> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        row.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) if (x != null) out.add(x.toString());
        return out;
    }

    private boolean isPermsAdmin(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return true;
        return permissionsService.isPermissionsAdmin(username(session));
    }

    private String username(HttpSession session) {
        Object o = session.getAttribute("username");
        return o == null ? "" : o.toString();
    }

    private String displayName(HttpSession session) {
        Object o = session.getAttribute("displayName");
        return o == null ? username(session) : o.toString();
    }

    private static ResponseEntity<?> forbidden() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", "User Permissions admin access required.");
        return ResponseEntity.status(403).body(e);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", msg == null ? "unknown error" : msg);
        return e;
    }
}
