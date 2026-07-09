package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.FeedbackItem;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.FeedbackQueueService;
import com.sandisk.plm.tracker.service.FeedbackResolveEmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback/queue")
public class FeedbackQueueController {

    @Autowired private FeedbackQueueService queueService;
    @Autowired private FeedbackResolveEmailService resolveEmailService;
    @Autowired private ActivityLogger activityLogger;

    /** Admins → all items; non-admins → their own items only. */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        String username = username(session);
        if (username.isEmpty()) return unauthorized();
        boolean admin = isAdmin(session);
        List<FeedbackItem> items = admin ? queueService.listAll() : queueService.listForUser(username);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("admin", admin);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{ptId}/done")
    public ResponseEntity<?> done(@PathVariable String ptId,
                                   @RequestBody(required = false) Map<String, String> body,
                                   HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String adminNote = body == null ? null : body.get("adminNote");
        String resolver = username(session);
        String resolverDisplay = displayName(session);

        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        if (FeedbackQueueService.STATUS_DONE.equals(existing.status)) {
            return ResponseEntity.status(409).body(error("Already marked done."));
        }

        FeedbackItem updated = queueService.markDone(ptId, resolver, resolverDisplay, adminNote);
        boolean emailSent = resolveEmailService.sendReadyToTest(updated);
        activityLogger.log(resolver, resolverDisplay, "FEEDBACK_RESOLVE",
                ptId + " marked done" + (emailSent ? " (email sent)" : " (no reporter email)"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        out.put("emailSent", emailSent);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{ptId}/start")
    public ResponseEntity<?> start(@PathVariable String ptId, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String resolver = username(session);
        String resolverDisplay = displayName(session);

        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        if (FeedbackQueueService.STATUS_DONE.equals(existing.status)
                || FeedbackQueueService.STATUS_DISMISSED.equals(existing.status)) {
            return ResponseEntity.status(409).body(error("Cannot start a " + existing.status + " item — reopen first."));
        }
        if (FeedbackQueueService.STATUS_IN_PROGRESS.equals(existing.status)) {
            // Idempotent — already in progress, no-op.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("item", existing);
            return ResponseEntity.ok(out);
        }

        FeedbackItem updated = queueService.markInProgress(ptId, resolver, resolverDisplay);
        activityLogger.log(resolver, resolverDisplay, "FEEDBACK_START", ptId + " started");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{ptId}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable String ptId,
                                      @RequestBody(required = false) Map<String, String> body,
                                      HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String reason = body == null ? null : body.get("reason");
        String resolver = username(session);
        String resolverDisplay = displayName(session);

        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        if (FeedbackQueueService.STATUS_DISMISSED.equals(existing.status)) {
            return ResponseEntity.status(409).body(error("Already dismissed."));
        }

        FeedbackItem updated = queueService.markDismissed(ptId, resolver, resolverDisplay, reason);
        activityLogger.log(resolver, resolverDisplay, "FEEDBACK_DISMISS", ptId + " dismissed");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{ptId}/reopen")
    public ResponseEntity<?> reopen(@PathVariable String ptId, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String resolver = username(session);
        String resolverDisplay = displayName(session);

        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();

        FeedbackItem updated = queueService.markReopen(ptId, resolver, resolverDisplay);
        activityLogger.log(resolver, resolverDisplay, "FEEDBACK_REOPEN", ptId + " reopened");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    // =================================================================
    //  AI triage + sign-off + auto-mark-done (spec 2026-05-13)
    // =================================================================

    /** Admin signs off on a triaged ticket. {awaiting_approval, open, triaging} → in_progress. */
    @PostMapping("/{ptId}/approve")
    public ResponseEntity<?> approve(@PathVariable String ptId, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        if (FeedbackQueueService.STATUS_DONE.equals(existing.status)
                || FeedbackQueueService.STATUS_DISMISSED.equals(existing.status)) {
            return ResponseEntity.status(409).body(error("Cannot approve a " + existing.status + " item."));
        }
        FeedbackItem updated = queueService.markApproved(ptId, username(session), displayName(session));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    /** Apply AI triage verdict (called by the poller / admin). */
    @PostMapping("/{ptId}/triage")
    public ResponseEntity<?> triage(@PathVariable String ptId,
                                     @RequestBody Map<String, Object> body,
                                     HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(error("Body required."));
        String tag = (String) body.get("tag");
        String assessment = (String) body.get("assessment");
        Double effortHours = body.get("effortHours") instanceof Number ? ((Number) body.get("effortHours")).doubleValue() : null;
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) body.get("questions");
        FeedbackItem updated = queueService.applyAiTriage(ptId, tag, assessment, effortHours, questions);
        if (updated == null) return notFound();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    /**
     * Requestor (or admin) supplies answers to the AI's questions. Flips status → triaging.
     *
     * <p>Accepts both shapes (content type is detected from the request, not from a Spring
     * {@code consumes} hint — declaring both {@code application/json} and
     * {@code multipart/form-data} together with a {@code @RequestBody Map} blew up with 415
     * for multipart requests because Spring still tried to read the body via the JSON converter):
     * <ul>
     *   <li>JSON body {@code {"answers":[...]}} — original answer-only flow</li>
     *   <li>multipart form-data with {@code answers} (JSON string) + {@code files} (0..N files)
     *       — new flow where the requestor can attach supporting documents along with their
     *       written answers.</li>
     * </ul>
     */
    @PostMapping(value = "/{ptId}/answer")
    public ResponseEntity<?> answer(@PathVariable String ptId,
                                     @RequestParam(value = "answers", required = false) String answersJson,
                                     @RequestParam(value = "files", required = false) java.util.List<org.springframework.web.multipart.MultipartFile> files,
                                     javax.servlet.http.HttpServletRequest request,
                                     HttpSession session) throws java.io.IOException {
        String me = username(session);
        if (me.isEmpty()) return unauthorized();
        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        // Authorised: original requestor OR admin
        if (!isAdmin(session) && !me.equalsIgnoreCase(existing.reporterUsername)) return forbidden();

        // Pull answers from whichever shape the client sent. We can't use @RequestBody Map
        // here because Spring eagerly invokes the JSON converter on every request and 415s
        // when the body is multipart. Read the body manually iff content-type is JSON.
        List<String> answers = null;
        try {
            if (answersJson != null && !answersJson.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(answersJson, java.util.List.class);
                answers = parsed;
            } else {
                String ct = request.getContentType();
                if (ct != null && ct.toLowerCase().startsWith("application/json")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(request.getInputStream(), Map.class);
                    if (jsonBody != null) {
                        @SuppressWarnings("unchecked")
                        List<String> fromBody = (List<String>) jsonBody.get("answers");
                        answers = fromBody;
                    }
                }
            }
        } catch (Exception parseErr) {
            return ResponseEntity.badRequest().body(error("Bad answers JSON: " + parseErr.getMessage()));
        }

        // Save any files the requestor attached, alongside the answers, BEFORE
        // we apply the answers (which flips status → triaging). That way the
        // re-triage on the next poll cycle sees the attachments in scope.
        int attachedCount = 0;
        if (files != null) {
            for (org.springframework.web.multipart.MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                String origName = f.getOriginalFilename();
                if (origName == null || origName.isEmpty()) origName = "attachment.bin";
                String safe = origName.replaceAll("[^A-Za-z0-9._-]", "_");
                long ts = System.currentTimeMillis() + attachedCount;
                String relPath = queueService.getAttachmentsDir() + "/" + ptId + "-" + ts + "-" + safe;
                java.io.File dir = new java.io.File(queueService.getAttachmentsDir());
                if (!dir.exists()) dir.mkdirs();
                try (java.io.InputStream in = f.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(relPath)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                queueService.addAttachment(ptId, relPath, origName);
                attachedCount++;
            }
        }
        if (attachedCount > 0) {
            activityLogger.log(me, displayName(session), "FEEDBACK_ATTACH",
                "ptId=" + ptId + " files=" + attachedCount + " via=answer-modal");
        }

        FeedbackItem updated = queueService.applyAnswers(ptId, answers, me, displayName(session));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        out.put("attached", attachedCount);
        return ResponseEntity.ok(out);
    }

    /**
     * Admin: download the current queue as Excel.
     * {@code status} = open | in_progress | dismissed | done | all (default: all).
     * Columns: PT ID, Submitted, Status, Requestor, Email, Short Description,
     * Estimated (hr), Actual (hr).
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "status", required = false, defaultValue = "all") String status,
                       HttpSession session,
                       javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if (!isAdmin(session)) {
            response.sendError(403, "Admin access required.");
            return;
        }
        List<FeedbackItem> all = queueService.listAll();
        String want = status == null ? "all" : status.toLowerCase();
        java.util.List<FeedbackItem> rows = new java.util.ArrayList<>();
        for (FeedbackItem it : all) {
            String s = it.status == null ? "" : it.status;
            boolean match;
            switch (want) {
                case "open":
                    match = "open".equals(s) || "triaging".equals(s) || "awaiting_approval".equals(s);
                    break;
                case "all":
                    match = true;
                    break;
                default:
                    match = want.equals(s);
            }
            if (match) rows.add(it);
        }

        String[] cols = {"PT ID", "Submitted", "Status", "Requestor", "Email",
                         "Short Description", "Estimated (hr)", "Actual (hr)"};

        org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        try {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Feedback Queue");

            org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
            hdrFont.setBold(true);
            hdrStyle.setFont(hdrFont);

            org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = hdrRow.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(hdrStyle);
            }

            for (int r = 0; r < rows.size(); r++) {
                FeedbackItem it = rows.get(r);
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(safe(it.ptId));
                row.createCell(1).setCellValue(safe(it.submittedAt));
                row.createCell(2).setCellValue(safe(it.status));
                row.createCell(3).setCellValue(safe(it.reporterDisplayName != null ? it.reporterDisplayName : it.reporterUsername));
                row.createCell(4).setCellValue(safe(it.reporterEmail));
                row.createCell(5).setCellValue(oneLine(it.text));
                if (it.aiEffortHours != null) row.createCell(6).setCellValue(it.aiEffortHours);
                else                          row.createCell(6).setCellValue("");
                if (it.actualHours != null)   row.createCell(7).setCellValue(round2(it.actualHours));
                else                          row.createCell(7).setCellValue("");
            }

            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 20 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 26 * 256);
            sheet.setColumnWidth(4, 30 * 256);
            sheet.setColumnWidth(5, 80 * 256);
            sheet.setColumnWidth(6, 14 * 256);
            sheet.setColumnWidth(7, 14 * 256);

            String tag = "all".equals(want) ? "all" : want;
            String fn = "feedback-queue-" + tag + "-"
                    + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fn + "\"");
            wb.write(response.getOutputStream());
        } finally {
            wb.dispose();
            wb.close();
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*\\r?\\n\\s*", " / ").trim();
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** Admin: force the auto-mark-done sweep to run NOW (instead of waiting for the 5-min cron). */
    @PostMapping("/auto-done-now")
    public ResponseEntity<?> autoDoneNow(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        int before = queueService.listAll().stream()
            .filter(it -> FeedbackQueueService.STATUS_DONE.equals(it.status)).toArray().length;
        queueService.autoMarkDoneSweep();
        int after = queueService.listAll().stream()
            .filter(it -> FeedbackQueueService.STATUS_DONE.equals(it.status)).toArray().length;
        int resolved = after - before;
        activityLogger.log(username(session), displayName(session), "FEEDBACK_AUTO_DONE_MANUAL",
            "resolved=" + resolved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("resolved", resolved);
        out.put("totalDone", after);
        return ResponseEntity.ok(out);
    }

    /**
     * Record the moment the agent actually started working on this ticket.
     * Anchors the actualHours timer (estimated vs actual reporting on Done).
     * Idempotent: first call wins. Admin-only.
     */
    @PostMapping("/{ptId}/agent-pickup")
    public ResponseEntity<?> agentPickup(@PathVariable String ptId, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        FeedbackItem updated = queueService.markAgentPickup(ptId, username(session), displayName(session));
        if (updated == null) return notFound();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    /** Stamp the SHA-256 of the JAR the agent just built. Auto-mark-done uses this. */
    @PostMapping("/{ptId}/attach-build-sha")
    public ResponseEntity<?> attachBuildSha(@PathVariable String ptId,
                                             @RequestBody Map<String, Object> body,
                                             HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String sha = body == null ? null : (String) body.get("sha");
        if (sha == null || sha.length() != 64) {
            return ResponseEntity.badRequest().body(error("sha must be a 64-char hex SHA-256."));
        }
        FeedbackItem updated = queueService.applyBuildSha(ptId, sha, username(session), displayName(session));
        if (updated == null) return notFound();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        return ResponseEntity.ok(out);
    }

    /** Add a file to an existing ticket. Optionally skips re-triage. */
    @PostMapping("/{ptId}/attach")
    public ResponseEntity<?> attach(@PathVariable String ptId,
                                     @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                     @RequestParam(value = "skipReTriage", required = false, defaultValue = "false") boolean skipReTriage,
                                     HttpSession session) throws java.io.IOException {
        String me = username(session);
        if (me.isEmpty()) return unauthorized();
        FeedbackItem existing = queueService.findById(ptId);
        if (existing == null) return notFound();
        if (!isAdmin(session) && !me.equalsIgnoreCase(existing.reporterUsername)) return forbidden();

        // Save file to the same attachments dir submitFeedback uses.
        String origName = file.getOriginalFilename();
        if (origName == null || origName.isEmpty()) origName = "attachment.bin";
        String safe = origName.replaceAll("[^A-Za-z0-9._-]", "_");
        long ts = System.currentTimeMillis();
        String relPath = queueService.getAttachmentsDir() + "/" + ptId + "-" + ts + "-" + safe;
        File dir = new File(queueService.getAttachmentsDir());
        if (!dir.exists()) dir.mkdirs();
        try (java.io.InputStream in = file.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(relPath)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        queueService.addAttachment(ptId, relPath, origName);
        activityLogger.log(me, displayName(session), "FEEDBACK_ATTACH",
            "ptId=" + ptId + " file=" + origName + " bytes=" + file.getSize());

        FeedbackItem updated;
        if (!skipReTriage) {
            updated = queueService.markTriaging(ptId);
        } else {
            updated = queueService.findById(ptId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", updated);
        out.put("attachedFile", origName);
        out.put("reTriage", !skipReTriage);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{ptId}/attachment")
    public ResponseEntity<?> attachment(@PathVariable String ptId,
                                        @org.springframework.web.bind.annotation.RequestParam(required = false) Integer index,
                                        HttpSession session) {
        String username = username(session);
        if (username.isEmpty()) return unauthorized();
        FeedbackItem item = queueService.findById(ptId);
        if (item == null) return notFound();
        if (!isAdmin(session) && !username.equalsIgnoreCase(item.reporterUsername)) {
            return forbidden();
        }
        // PT-38: pick the right path. With no index → first attachment (back-compat
        // single-file path). With index → that entry from attachmentPaths.
        String path = null;
        String filename = null;
        if (index != null && item.attachmentPaths != null && index >= 0 && index < item.attachmentPaths.size()) {
            path = item.attachmentPaths.get(index);
            filename = (item.attachmentFilenames != null && index < item.attachmentFilenames.size())
                    ? item.attachmentFilenames.get(index) : null;
        } else if (item.attachmentPaths != null && !item.attachmentPaths.isEmpty()) {
            path = item.attachmentPaths.get(0);
            filename = (item.attachmentFilenames != null && !item.attachmentFilenames.isEmpty())
                    ? item.attachmentFilenames.get(0) : null;
        } else if (item.attachmentPath != null) {
            path = item.attachmentPath;
            filename = item.attachmentFilename;
        }
        if (path == null) return notFound();
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return notFound();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + (filename == null ? "attachment" : filename) + "\"");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
    }

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
    }

    private String username(HttpSession session) {
        Object o = session.getAttribute("username");
        return o == null ? "" : o.toString();
    }

    private String displayName(HttpSession session) {
        Object o = session.getAttribute("displayName");
        return o == null ? username(session) : o.toString();
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(error("Login required."));
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(error("Admin access required."));
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(error("Not found."));
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }
}
