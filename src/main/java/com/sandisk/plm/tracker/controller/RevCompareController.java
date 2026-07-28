package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.EmailService;
import com.sandisk.plm.tracker.service.RevCompareExcelExportService;
import com.sandisk.plm.tracker.service.RevCompareService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/rev-compare")
public class RevCompareController {

    private final RevCompareService revCompareService;
    private final RevCompareExcelExportService excelExportService;
    private final EmailService emailService;
    private final ActivityLogger activityLogger;

    public RevCompareController(RevCompareService revCompareService,
                                RevCompareExcelExportService excelExportService,
                                EmailService emailService,
                                ActivityLogger activityLogger) {
        this.revCompareService = revCompareService;
        this.excelExportService = excelExportService;
        this.emailService = emailService;
        this.activityLogger = activityLogger;
    }

    private String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v != null ? v.toString() : "";
    }

    // =========================================================================
    // GET /api/rev-compare/revs?part=<part_number>
    // =========================================================================

    @GetMapping("/revs")
    public List<Map<String, String>> getRevisions(@RequestParam String part) {
        return revCompareService.getRevisions(part.trim());
    }

    // =========================================================================
    // GET /api/rev-compare/compare?part=X&revA=Y&changeA=Z&revB=W&changeB=V
    // =========================================================================

    @GetMapping("/compare")
    public Map<String, Object> compare(
            @RequestParam String part,
            @RequestParam String revA,
            @RequestParam(required = false, defaultValue = "") String changeA,
            @RequestParam String revB,
            @RequestParam(required = false, defaultValue = "") String changeB,
            // Optional second part. When omitted/blank/equal to `part`, the
            // unified BOM Compare UI is in "Same part" mode and both sides
            // resolve against `part`. When supplied, "Different parts" mode:
            // side A queries `part`, side B queries `partB`.
            @RequestParam(required = false, defaultValue = "") String partB,
            HttpSession session) {

        long start = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // Run both rev-detail queries in parallel — they're independent
            String pnA = part.trim();
            String pnB = (partB == null || partB.trim().isEmpty()) ? pnA : partB.trim();
            ExecutorService exec = Executors.newFixedThreadPool(2);
            Future<Map<String, Object>> futureA = exec.submit(() -> revCompareService.getRevDetail(pnA, revA, changeA));
            Future<Map<String, Object>> futureB = exec.submit(() -> revCompareService.getRevDetail(pnB, revB, changeB));
            exec.shutdown();

            Map<String, Object> detailA = futureA.get(60, TimeUnit.SECONDS);
            Map<String, Object> detailB = futureB.get(60, TimeUnit.SECONDS);

            @SuppressWarnings("unchecked")
            Map<String, String> headerA = (Map<String, String>) detailA.get("header");
            @SuppressWarnings("unchecked")
            Map<String, String> headerB = (Map<String, String>) detailB.get("header");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rowsA = (List<Map<String, String>>) detailA.get("rows");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rowsB = (List<Map<String, String>>) detailB.get("rows");

            // Build maps keyed by component part number
            Map<String, Map<String, String>> mapA = new LinkedHashMap<>();
            for (Map<String, String> r : rowsA) mapA.put(r.get("component"), r);
            Map<String, Map<String, String>> mapB = new LinkedHashMap<>();
            for (Map<String, String> r : rowsB) mapB.put(r.get("component"), r);

            // Compute diff
            String[] compareFields = {"qty", "description", "componentRev", "componentChange",
                                      "bomType", "seqNum", "primaryPn", "refDes", "notes",
                                      "_diagBomRowId", "_diagChangeInId", "_diagChangeOutId",
                                      "_diagChangeInNum", "_diagChangeOutNum",
                                      "_diagChangeInReleaseDate", "_diagChangeOutReleaseDate"};

            List<Map<String, Object>> diff = new ArrayList<>();
            Set<String> allComponents = new LinkedHashSet<>();
            allComponents.addAll(mapA.keySet());
            allComponents.addAll(mapB.keySet());

            int added = 0, removed = 0, changed = 0, unchanged = 0;
            for (String comp : allComponents) {
                Map<String, String> a = mapA.get(comp);
                Map<String, String> b = mapB.get(comp);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("component", comp);

                if (a != null && b == null) {
                    row.put("diffStatus", "REMOVED");
                    for (String f : compareFields) {
                        row.put(f + "A", a.getOrDefault(f, ""));
                        row.put(f + "B", "");
                    }
                    removed++;
                } else if (a == null && b != null) {
                    row.put("diffStatus", "ADDED");
                    for (String f : compareFields) {
                        row.put(f + "A", "");
                        row.put(f + "B", b.getOrDefault(f, ""));
                    }
                    added++;
                } else {
                    boolean anyChanged = false;
                    for (String f : compareFields) {
                        String va = a.getOrDefault(f, "");
                        String vb = b.getOrDefault(f, "");
                        row.put(f + "A", va);
                        row.put(f + "B", vb);
                        // Diag fields differ row-to-row (bom_row_id etc.) and must
                        // not flip the CHANGED flag — only real BOM fields count.
                        if (!f.startsWith("_diag") && !va.equals(vb)) anyChanged = true;
                    }
                    row.put("diffStatus", anyChanged ? "CHANGED" : "MATCH");
                    if (anyChanged) changed++; else unchanged++;
                }
                diff.add(row);
            }

            long elapsed = System.currentTimeMillis() - start;

            // Build rev labels for display. Different-parts mode prefixes the
            // part number so the side header makes sense even when the two
            // parts are different.
            boolean diffParts = !pnA.equalsIgnoreCase(pnB);
            String labelA = (diffParts ? pnA + " · " : "") + revA + (changeA.isEmpty() ? "" : " — " + changeA);
            String labelB = (diffParts ? pnB + " · " : "") + revB + (changeB.isEmpty() ? "" : " — " + changeB);

            response.put("diff", diff);
            response.put("part", pnA);
            response.put("partB", pnB);
            response.put("samePart", !diffParts);
            response.put("headerA", headerA);
            response.put("headerB", headerB);
            response.put("revLabelA", labelA);
            response.put("revLabelB", labelB);
            response.put("countA", rowsA.size());
            response.put("countB", rowsB.size());
            response.put("onlyInA", removed);
            response.put("onlyInB", added);
            response.put("different", changed);
            response.put("same", unchanged);
            response.put("queryTimeMs", elapsed);

            activityLogger.log(s(session, "username"), s(session, "displayName"),
                "REV_COMPARE", (diffParts ? pnA + " vs " + pnB : pnA) + " | " + labelA + " vs " + labelB +
                " | A:" + rowsA.size() + " B:" + rowsB.size() +
                " | +=" + added + " -=" + removed + " ~=" + changed + " ==" + unchanged);
        } catch (Exception e) {
            response.put("error", "Compare failed: " + e.getMessage());
        }
        return response;
    }

    // =========================================================================
    // GET /api/rev-compare/export
    // =========================================================================

    @GetMapping("/export")
    public void exportCompare(
            @RequestParam String part,
            @RequestParam String revA,
            @RequestParam(required = false, defaultValue = "") String changeA,
            @RequestParam String revB,
            @RequestParam(required = false, defaultValue = "") String changeB,
            HttpSession session,
            HttpServletResponse response) throws IOException {

        Map<String, Object> compareResult = compare(part, revA, changeA, revB, changeB, "", session);
        if (compareResult.containsKey("error")) {
            response.sendError(500, (String) compareResult.get("error"));
            return;
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "Rev-Compare-" + part.trim() + "-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) compareResult.get("diff");
        String labelA = (String) compareResult.get("revLabelA");
        String labelB = (String) compareResult.get("revLabelB");
        excelExportService.exportCompare(diff, part.trim(), labelA, labelB, response.getOutputStream());
    }

    // =========================================================================
    // POST /api/rev-compare/email
    // =========================================================================

    @PostMapping("/email")
    public Map<String, Object> emailCompare(
            @RequestParam String part,
            @RequestParam String revA,
            @RequestParam(required = false, defaultValue = "") String changeA,
            @RequestParam String revB,
            @RequestParam(required = false, defaultValue = "") String changeB,
            HttpSession session) {

        Map<String, Object> resp = new LinkedHashMap<>();
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (email == null || email.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Not logged in.");
            return resp;
        }

        try {
            Map<String, Object> compareResult = compare(part, revA, changeA, revB, changeB, "", session);
            if (compareResult.containsKey("error")) {
                resp.put("success", false);
                resp.put("message", (String) compareResult.get("error"));
                return resp;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> diff = (List<Map<String, Object>>) compareResult.get("diff");
            String labelA = (String) compareResult.get("revLabelA");
            String labelB = (String) compareResult.get("revLabelB");

            ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
            excelExportService.exportCompare(diff, part.trim(), labelA, labelB, excelOut);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "Rev-Compare-" + part.trim() + "-" + date + ".xlsx";
            String title = "Rev Compare: " + part.trim() + " " + labelA + " vs " + labelB;

            emailService.sendBomReport(email, displayName, excelOut.toByteArray(),
                    filename, title, diff.size());

            activityLogger.log(s(session, "username"), displayName,
                "REV_COMPARE_EMAIL", part.trim() + " | " + labelA + " vs " + labelB +
                " | " + diff.size() + " rows emailed");

            resp.put("success", true);
            resp.put("message", "Report sent to " + email + " (" + diff.size() + " rows)");
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Failed: " + e.getMessage());
        }
        return resp;
    }
}
