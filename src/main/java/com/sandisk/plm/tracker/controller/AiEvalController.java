package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.AiEvalRun;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AiEvalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/ai-eval")
public class AiEvalController {

    private static final Logger logger = Logger.getLogger(AiEvalController.class.getName());

    @Autowired
    private AiEvalService evalService;

    @Autowired
    private AiHelpController aiHelpController;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private com.sandisk.plm.tracker.service.PersonaInferenceService personaInferenceService;

    @Autowired
    private com.sandisk.plm.tracker.service.LdapAuthService ldapAuthService;

    @Autowired
    private SupportController supportController;

    /** Start a new run. Body: {persona:{...}, testerModel, evaluatorModel, questionCount, parentRunId?} */
    @PostMapping("/runs")
    public ResponseEntity<?> startRun(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        AiEvalRun.RunConfig cfg = new AiEvalRun.RunConfig();
        try {
            Map<String, Object> p = castMap(body.get("persona"));
            if (p == null) throw new IllegalArgumentException("persona is required");
            cfg.persona.role = stringOrNull(p.get("role"));
            cfg.persona.team = stringOrNull(p.get("team"));
            cfg.persona.experience = stringOrNull(p.get("experience"));
            cfg.persona.goal = stringOrNull(p.get("goal"));
            // V2: optional simulated-person demographics
            cfg.persona.simulatedUsername = stringOrNull(p.get("simulatedUsername"));
            cfg.persona.simulatedDisplayName = stringOrNull(p.get("simulatedDisplayName"));
            cfg.persona.realTitle = stringOrNull(p.get("realTitle"));
            cfg.persona.realDepartment = stringOrNull(p.get("realDepartment"));
            cfg.persona.realAccountAge = stringOrNull(p.get("realAccountAge"));
            Object lc = p.get("realLoginCount90d");
            cfg.persona.realLoginCount90d = lc instanceof Number ? ((Number) lc).intValue() : null;
            Object ad = p.get("realIsPlmAdmin");
            cfg.persona.realIsPlmAdmin = ad instanceof Boolean ? (Boolean) ad : null;
            cfg.chatbotModel = stringOrNull(body.get("chatbotModel"));
            cfg.testerModel = stringOrNull(body.get("testerModel"));
            cfg.evaluatorModel = stringOrNull(body.get("evaluatorModel"));
            Object qc = body.get("questionCount");
            cfg.questionCount = qc instanceof Number ? ((Number) qc).intValue() : 0;
        } catch (Exception parseErr) {
            return ResponseEntity.badRequest().body(err("Bad request body: " + parseErr.getMessage()));
        }

        String parentRunId = stringOrNull(body.get("parentRunId"));
        String username = (String) session.getAttribute("username");

        try {
            AiEvalRun run = evalService.startRun(cfg, username == null ? "unknown" : username, parentRunId);
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                "AI_EVAL_START", "runId=" + run.runId + " persona=" + cfg.persona.role
                    + " count=" + cfg.questionCount);
            return ResponseEntity.ok(Map.of("runId", run.runId));
        } catch (IllegalArgumentException valErr) {
            return ResponseEntity.badRequest().body(err(valErr.getMessage()));
        }
    }

    /** SSE stream for one run's progress. Closes on run-complete or run-failed. */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) {
            SseEmitter em = new SseEmitter(5_000L);
            try { em.send(SseEmitter.event().name("error").data(err("Admin access required."))); } catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return evalService.registerEmitter(runId);
    }

    /** Snapshot of one run (used for SSE-fallback polling). */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        AiEvalRun r = evalService.getRun(runId);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    /** Light list view (no `results[]`). */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        return ResponseEntity.ok(evalService.listRunsLight());
    }

    /** Full results for one run (powering the expand-row view). */
    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<?> getResults(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        AiEvalRun r = evalService.getRun(runId);
        if (r == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", r.runId);
        out.put("results", r.results);
        out.put("summary", r.summary);
        return ResponseEntity.ok(out);
    }

    /** V2: list users who have logged into the toolkit (for the picker dropdown). */
    @GetMapping("/users")
    public ResponseEntity<?> listLoggers(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        return ResponseEntity.ok(personaInferenceService.listPastLoggers());
    }

    /** V2: list ALL users in the toolkit's AD access group (for the "Search wider" toggle). */
    @GetMapping("/users/all")
    public ResponseEntity<?> listAllAdUsers(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        try {
            java.util.List<com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser> all =
                ldapAuthService.listAllGroupMembers();
            // Repackage to match the picker's lightweight shape
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>(all.size());
            for (com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser u : all) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", u.username);
                m.put("displayName", u.displayName == null || u.displayName.isEmpty() ? u.username : u.displayName);
                out.add(m);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            logger.warning("[AI EVAL] AD search failed: " + e.getMessage());
            return ResponseEntity.status(503).body(err("AD search unavailable: " + e.getMessage()));
        }
    }

    /** V2: inferred persona for a single user. Used to auto-fill the form on pick. */
    @GetMapping("/users/{username}/persona")
    public ResponseEntity<?> getInferredPersona(@PathVariable String username, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        try {
            return ResponseEntity.ok(personaInferenceService.inferPersona(username));
        } catch (Exception e) {
            logger.warning("[AI EVAL] persona inference failed for " + username + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /** V2: human override of a single question's grade. */
    @PatchMapping("/runs/{runId}/results/{qIndex}")
    public ResponseEntity<?> overrideResult(@PathVariable String runId,
                                             @PathVariable int qIndex,
                                             @RequestBody Map<String, Object> body,
                                             HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        String username = (String) session.getAttribute("username");
        try {
            String grade = stringOrNull(body.get("grade"));
            String reason = stringOrNull(body.get("reason"));
            String note = stringOrNull(body.get("note"));
            com.sandisk.plm.tracker.model.AiEvalRun run = evalService.applyOverride(
                runId, qIndex, grade, reason, note, username == null ? "unknown" : username);
            // Find the updated result
            com.sandisk.plm.tracker.model.AiEvalRun.Result updated = null;
            for (com.sandisk.plm.tracker.model.AiEvalRun.Result r : run.results) {
                if (r.qIndex == qIndex) { updated = r; break; }
            }
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                "AI_EVAL_OVERRIDE", "runId=" + runId + " qIndex=" + qIndex + " grade=" + grade);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("result", updated);
            resp.put("summary", run.summary);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException valErr) {
            String msg = valErr.getMessage();
            if (msg != null && (msg.contains("not found"))) {
                return ResponseEntity.status(404).body(err(msg));
            }
            return ResponseEntity.badRequest().body(err(msg));
        } catch (Exception e) {
            logger.warning("[AI EVAL] override failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /** V3: regrade an existing run's Q/A with a different evaluator model. */
    @PostMapping("/runs/{parentRunId}/regrade")
    public ResponseEntity<?> regrade(@PathVariable String parentRunId,
                                      @RequestBody Map<String, Object> body,
                                      HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        String username = (String) session.getAttribute("username");
        try {
            String evaluatorModel = stringOrNull(body.get("evaluatorModel"));
            String chatbotModel = stringOrNull(body.get("chatbotModel")); // null/blank = keep parent's chatbot
            boolean forceRerunAiHelp = Boolean.TRUE.equals(body.get("forceRerunAiHelp"));
            com.sandisk.plm.tracker.model.AiEvalRun run = evalService.startRegrade(
                parentRunId, evaluatorModel, chatbotModel, forceRerunAiHelp,
                username == null ? "unknown" : username);
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                "AI_EVAL_REGRADE", "newRunId=" + run.runId + " parentRunId=" + parentRunId
                    + " evaluator=" + evaluatorModel
                    + (chatbotModel != null ? " chatbot=" + chatbotModel : "")
                    + (forceRerunAiHelp ? " forceRerunAiHelp=true" : ""));
            return ResponseEntity.ok(Map.of("runId", run.runId));
        } catch (IllegalArgumentException valErr) {
            String msg = valErr.getMessage();
            if (msg != null && msg.contains("not found")) {
                return ResponseEntity.status(404).body(err(msg));
            }
            return ResponseEntity.badRequest().body(err(msg));
        }
    }

    /** Write the markdown brief and return its path. */
    @PostMapping("/runs/{runId}/export")
    public ResponseEntity<?> export(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        try {
            // Snapshot the eval-mode prompt with this run's persona goal AND admin status
            // so the brief matches what AI Help actually saw during the run (not the prod-mode prompt).
            com.sandisk.plm.tracker.model.AiEvalRun rrun = evalService.getRun(runId);
            String goalHint = (rrun != null && rrun.config != null && rrun.config.persona != null) ? rrun.config.persona.goal : null;
            boolean personaAdmin = (rrun != null && rrun.config != null && rrun.config.persona != null
                && Boolean.TRUE.equals(rrun.config.persona.realIsPlmAdmin));
            String currentSystemPrompt = aiHelpController.getSystemPromptForEval("ai-eval",
                (String) session.getAttribute("displayName"), personaAdmin, true, goalHint);
            String path = evalService.exportForClaude(runId, currentSystemPrompt);
            activityLogger.log((String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "AI_EVAL_EXPORT", "runId=" + runId + " path=" + path);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.warning("[AI EVAL] export failed for " + runId + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * Stream the markdown brief back to the browser as a file download. Builds the brief
     * fresh (snapshotting the current AI Help system prompt) so the user always gets the
     * latest version without needing server-side filesystem access.
     */
    @GetMapping("/runs/{runId}/export/download")
    public ResponseEntity<?> exportDownload(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        try {
            // Snapshot the eval-mode prompt with this run's persona goal AND admin status
            // so the brief matches what AI Help actually saw during the run (not the prod-mode prompt).
            com.sandisk.plm.tracker.model.AiEvalRun rrun = evalService.getRun(runId);
            String goalHint = (rrun != null && rrun.config != null && rrun.config.persona != null) ? rrun.config.persona.goal : null;
            boolean personaAdmin = (rrun != null && rrun.config != null && rrun.config.persona != null
                && Boolean.TRUE.equals(rrun.config.persona.realIsPlmAdmin));
            String currentSystemPrompt = aiHelpController.getSystemPromptForEval("ai-eval",
                (String) session.getAttribute("displayName"), personaAdmin, true, goalHint);
            String path = evalService.exportForClaude(runId, currentSystemPrompt);
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            activityLogger.log((String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "AI_EVAL_EXPORT_DOWNLOAD", "runId=" + runId);
            return ResponseEntity.ok()
                .header("Content-Type", "text/markdown; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"eval-" + runId + ".md\"")
                .body(bytes);
        } catch (IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.warning("[AI EVAL] export download failed for " + runId + ": " + e.getMessage());
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /**
     * Excel workbook of every past run. Columns: Date, Created By, Persona, Chatbot,
     * Tester, Evaluator, Q's, Avg, Fails, Avg AI ms, Δ vs prior, Status, RunId,
     * RegradeOf. Streamed back as a download.
     */
    @GetMapping("/runs/export.xlsx")
    public void exportAllRunsXlsx(HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        if (!isAdmin(session)) { response.sendError(403, "Admin access required."); return; }
        // Use the full view (with results) so we can build the per-question sheet too.
        java.util.List<com.sandisk.plm.tracker.model.AiEvalRun> all = evalService.listRunsFull();

        // Newest first for the file (matches UI order). Build a "previous run with same config"
        // index for the Δ column by scanning older entries.
        org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(200);
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("AI Eval Runs");

        org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
        hdrFont.setBold(true); hdrFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        hdrStyle.setFont(hdrFont);
        hdrStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
        hdrStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

        String[] cols = {"Date", "Created by", "Persona", "Chatbot", "Tester", "Evaluator",
            "Q's", "Avg grade", "Avg numeric", "Fails", "Avg AI ms", "Total ms",
            "Δ vs prior (same config)", "Status", "Regrade of", "Run ID"};
        org.apache.poi.ss.usermodel.Row hdr = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = hdr.createCell(i);
            cell.setCellValue(cols[i]); cell.setCellStyle(hdrStyle);
        }

        for (int r = 0; r < all.size(); r++) {
            com.sandisk.plm.tracker.model.AiEvalRun run = all.get(r);
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            int c = 0;
            row.createCell(c++).setCellValue(run.createdAt == null ? "" : run.createdAt);
            row.createCell(c++).setCellValue(run.createdBy == null ? "" : run.createdBy);
            row.createCell(c++).setCellValue(personaText(run.config));
            row.createCell(c++).setCellValue(run.config != null && run.config.chatbotModel != null
                ? run.config.chatbotModel : "(prod default)");
            row.createCell(c++).setCellValue(run.config != null && run.config.testerModel != null
                ? run.config.testerModel : "");
            row.createCell(c++).setCellValue(run.config != null && run.config.evaluatorModel != null
                ? run.config.evaluatorModel : "");
            row.createCell(c++).setCellValue(run.config != null ? run.config.questionCount : 0);
            row.createCell(c++).setCellValue(run.summary != null && run.summary.avgGradeLetter != null
                ? run.summary.avgGradeLetter : "");
            row.createCell(c++).setCellValue(run.summary != null ? run.summary.avgGradeNumeric : 0);
            row.createCell(c++).setCellValue(run.summary != null ? run.summary.failureCount : 0);
            row.createCell(c++).setCellValue(run.summary != null ? run.summary.avgAnswerLatencyMs : 0);
            row.createCell(c++).setCellValue(run.summary != null ? run.summary.totalLatencyMs : 0);
            // Δ vs prior with same config (older run with matching chatbot/tester/eval/persona/qcount)
            Double delta = null;
            if (run.summary != null && run.config != null) {
                for (int j = r + 1; j < all.size(); j++) {
                    com.sandisk.plm.tracker.model.AiEvalRun prior = all.get(j);
                    if (prior.config != null && prior.summary != null && configsEqual(run.config, prior.config)) {
                        delta = run.summary.avgGradeNumeric - prior.summary.avgGradeNumeric;
                        break;
                    }
                }
            }
            if (delta == null) {
                row.createCell(c++).setCellValue("");
            } else {
                row.createCell(c++).setCellValue(Math.round(delta * 10.0) / 10.0);
            }
            row.createCell(c++).setCellValue(run.status == null ? "" : run.status);
            row.createCell(c++).setCellValue(run.regradeOfRunId == null ? "" : run.regradeOfRunId);
            row.createCell(c++).setCellValue(run.runId == null ? "" : run.runId);
        }
        // Auto-size most columns; the long ID columns stay narrow (we set fixed widths).
        for (int i = 0; i < cols.length - 2; i++) sheet.setColumnWidth(i, 4500);
        sheet.setColumnWidth(2, 7000);  // Persona
        sheet.setColumnWidth(3, 9500);  // Chatbot slug
        sheet.setColumnWidth(4, 9500);  // Tester slug
        sheet.setColumnWidth(5, 9500);  // Evaluator slug
        sheet.setColumnWidth(cols.length - 2, 9500); // Regrade of
        sheet.setColumnWidth(cols.length - 1, 9500); // Run ID

        // ─────────── Sheet 2: Questions (one row per Q) ───────────
        org.apache.poi.ss.usermodel.Sheet qSheet = wb.createSheet("Questions");
        String[] qCols = {"Run Date", "Run ID", "Persona", "Chatbot", "Tester", "Evaluator",
            "Q#", "Question", "Answer", "Grade", "Reason", "Answer ms", "Grade ms",
            "AI Grade (orig)", "AI Reason (orig)", "Overridden by", "Overridden at", "Override note"};
        org.apache.poi.ss.usermodel.Row qHdr = qSheet.createRow(0);
        for (int i = 0; i < qCols.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = qHdr.createCell(i);
            cell.setCellValue(qCols[i]); cell.setCellStyle(hdrStyle);
        }
        // Wrapped style for long text cells (Question / Answer / Reason).
        org.apache.poi.ss.usermodel.CellStyle wrapStyle = wb.createCellStyle();
        wrapStyle.setWrapText(true);
        wrapStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);

        int qRowIdx = 1;
        for (com.sandisk.plm.tracker.model.AiEvalRun run : all) {
            if (run.results == null || run.results.isEmpty()) continue;
            String pTxt = personaText(run.config);
            String chatM = run.config != null && run.config.chatbotModel != null ? run.config.chatbotModel : "(prod default)";
            String testM = run.config != null && run.config.testerModel != null ? run.config.testerModel : "";
            String evM   = run.config != null && run.config.evaluatorModel != null ? run.config.evaluatorModel : "";
            for (com.sandisk.plm.tracker.model.AiEvalRun.Result rs : run.results) {
                org.apache.poi.ss.usermodel.Row qr = qSheet.createRow(qRowIdx++);
                int qc = 0;
                qr.createCell(qc++).setCellValue(run.createdAt == null ? "" : run.createdAt);
                qr.createCell(qc++).setCellValue(run.runId == null ? "" : run.runId);
                qr.createCell(qc++).setCellValue(pTxt);
                qr.createCell(qc++).setCellValue(chatM);
                qr.createCell(qc++).setCellValue(testM);
                qr.createCell(qc++).setCellValue(evM);
                qr.createCell(qc++).setCellValue(rs.qIndex);
                org.apache.poi.ss.usermodel.Cell qCell = qr.createCell(qc++);
                qCell.setCellValue(safe(rs.question)); qCell.setCellStyle(wrapStyle);
                org.apache.poi.ss.usermodel.Cell aCell = qr.createCell(qc++);
                aCell.setCellValue(safe(rs.answer)); aCell.setCellStyle(wrapStyle);
                qr.createCell(qc++).setCellValue(safe(rs.grade));
                org.apache.poi.ss.usermodel.Cell rCell = qr.createCell(qc++);
                rCell.setCellValue(safe(rs.reason)); rCell.setCellStyle(wrapStyle);
                qr.createCell(qc++).setCellValue(rs.answerLatencyMs);
                qr.createCell(qc++).setCellValue(rs.gradeLatencyMs);
                qr.createCell(qc++).setCellValue(safe(rs.aiGrade));
                qr.createCell(qc++).setCellValue(safe(rs.aiReason));
                qr.createCell(qc++).setCellValue(safe(rs.overriddenBy));
                qr.createCell(qc++).setCellValue(safe(rs.overriddenAt));
                qr.createCell(qc++).setCellValue(safe(rs.overrideNote));
            }
        }
        qSheet.setColumnWidth(0, 5000);   // Run Date
        qSheet.setColumnWidth(1, 9500);   // Run ID
        qSheet.setColumnWidth(2, 7000);   // Persona
        qSheet.setColumnWidth(3, 9500);   // Chatbot
        qSheet.setColumnWidth(4, 9500);   // Tester
        qSheet.setColumnWidth(5, 9500);   // Evaluator
        qSheet.setColumnWidth(6, 1500);   // Q#
        qSheet.setColumnWidth(7, 18000);  // Question
        qSheet.setColumnWidth(8, 22000);  // Answer
        qSheet.setColumnWidth(9, 1800);   // Grade
        qSheet.setColumnWidth(10, 18000); // Reason
        qSheet.setColumnWidth(11, 2500);  // Answer ms
        qSheet.setColumnWidth(12, 2500);  // Grade ms
        for (int i = 13; i < qCols.length; i++) qSheet.setColumnWidth(i, 4500);
        qSheet.createFreezePane(0, 1);

        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        activityLogger.log(username, displayName, "AI_EVAL_EXPORT_ALL_XLSX",
            "runs=" + all.size() + " questions=" + (qRowIdx - 1));

        String fn = "AI-Eval-Runs-" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fn + "\"");
        wb.write(response.getOutputStream());
        wb.dispose();
        wb.close();
    }

    /** Null-safe string for Excel cell values; also caps at 32k (POI's max cell length). */
    private static String safe(String s) {
        if (s == null) return "";
        if (s.length() > 32000) return s.substring(0, 32000) + "\u2026[truncated]";
        return s;
    }

    private static String personaText(AiEvalRun.RunConfig cfg) {
        if (cfg == null || cfg.persona == null) return "";
        AiEvalRun.Persona p = cfg.persona;
        StringBuilder sb = new StringBuilder();
        if (p.simulatedDisplayName != null) sb.append(p.simulatedDisplayName).append(" — ");
        if (p.role != null) sb.append(p.role);
        if (p.team != null) sb.append(" · ").append(p.team);
        if (p.experience != null) sb.append(" · ").append(p.experience);
        if (p.goal != null && !p.goal.isEmpty()) sb.append(" · goal=").append(p.goal);
        return sb.toString();
    }

    private static boolean configsEqual(AiEvalRun.RunConfig a, AiEvalRun.RunConfig b) {
        if (a == null || b == null) return false;
        if (!java.util.Objects.equals(a.chatbotModel, b.chatbotModel)) return false;
        if (!java.util.Objects.equals(a.testerModel, b.testerModel)) return false;
        if (!java.util.Objects.equals(a.evaluatorModel, b.evaluatorModel)) return false;
        if (a.questionCount != b.questionCount) return false;
        if (a.persona == null || b.persona == null) return false;
        return java.util.Objects.equals(a.persona.role, b.persona.role)
            && java.util.Objects.equals(a.persona.team, b.persona.team)
            && java.util.Objects.equals(a.persona.experience, b.persona.experience)
            && java.util.Objects.equals(a.persona.goal, b.persona.goal);
    }

    /**
     * Curated list of Vortex model slugs offered in the Chatbot/Tester/Evaluator
     * dropdowns. Grouped by provider; the frontend renders {@code <optgroup>}s. Add
     * new entries here when Portkey/Vortex onboards new models.
     */
    // =========================================================================
    // User-Ask mode — any logged-in user picks a model, asks a question, grades
    // the answer. C/D/F items with an explanation get filed as Bug Reports in
    // the Feedback Queue. No tester/grader/personas (the user IS the grader).
    // =========================================================================

    /**
     * Single Q\u2192A. Body: {model: "@..", question: "..."}. Returns
     * {answer, latencyMs}. Any logged-in user.
     */
    @PostMapping("/ask/question")
    public ResponseEntity<?> askQuestion(@RequestBody Map<String, Object> body, HttpSession session) {
        if (session.getAttribute("username") == null) return ResponseEntity.status(401).body(err("Login required."));
        String model = stringOrNull(body.get("model"));
        String question = stringOrNull(body.get("question"));
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("question is required"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) body.getOrDefault("history", new ArrayList<>());
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        long t0 = System.currentTimeMillis();
        try {
            // Delegate to the full /api/help/ask interceptor chain so data
            // queries / activity reports / help docs / SKU lookups all fire.
            // modelOverride applies only to the LLM-fallback branch; if an
            // interceptor matches first, the model picker is irrelevant.
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("question", question.trim());
            req.put("currentTab", "ai-eval");
            req.put("history", history);
            if (model != null && !model.isEmpty()) req.put("modelOverride", model);

            Map<String, Object> r = aiHelpController.ask(req, session, null);
            long ms = System.currentTimeMillis() - t0;

            activityLogger.log(username, displayName, "AI_EVAL_USER_ASK",
                    "model=" + (model == null ? "default" : model)
                        + " src=" + r.getOrDefault("source", "interceptor")
                        + " q=" + question.substring(0, Math.min(80, question.length())));

            Map<String, Object> resp = new LinkedHashMap<>();
            if (Boolean.TRUE.equals(r.get("success"))) {
                resp.put("answer", r.get("answer"));
                resp.put("latencyMs", ms);
                resp.put("source", r.getOrDefault("source", "interceptor"));
            } else {
                // ask() encountered an error — surface its message
                resp.put("answer", r.get("message") != null ? "⚠️ " + r.get("message") : "⚠️ AI call failed.");
                resp.put("latencyMs", ms);
                resp.put("error", true);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            logger.warning("[AI EVAL ASK] failed: " + e.getMessage());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("error", e.getMessage() == null ? "AI call failed" : e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * End of session — collect graded items. For each item with grade C/D/F
     * AND a non-empty {@code expectation}, file a Bug Report in the Feedback
     * Queue (via the existing SupportController flow — same email + queue
     * persist as a manual feedback submission).
     *
     * Body: {items: [{question, answer, model, grade, expectation?}]}
     */
    @PostMapping("/ask/submit-session")
    public ResponseEntity<?> askSubmitSession(@RequestBody Map<String, Object> body, HttpSession session) {
        if (session.getAttribute("username") == null) return ResponseEntity.status(401).body(err("Login required."));
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        String email = (String) session.getAttribute("email");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", java.util.Collections.emptyList());
        List<String> filedPtIds = new ArrayList<>();
        int totalGraded = 0;
        int eligibleForBug = 0;
        int filedCount = 0;
        for (Map<String, Object> item : items) {
            String grade = stringOrNull(item.get("grade"));
            if (grade == null) continue;
            totalGraded++;
            grade = grade.toUpperCase();
            if (!"C".equals(grade) && !"D".equals(grade) && !"F".equals(grade)) continue;
            String expectation = stringOrNull(item.get("expectation"));
            if (expectation == null || expectation.trim().isEmpty()) continue;
            eligibleForBug++;

            String question = stringOrNull(item.get("question"));
            String answer = stringOrNull(item.get("answer"));
            String model = stringOrNull(item.get("model"));
            String source = stringOrNull(item.get("source"));
            Object latencyObj = item.get("latencyMs");

            // Prior conversation in this session — frontend ships up to 3 turns
            // that came before this graded turn so debuggers can resolve
            // referents like "of those, how many were 32GB?".
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> prior = (List<Map<String, Object>>) item.getOrDefault("prior", java.util.Collections.emptyList());
            StringBuilder priorBlock = new StringBuilder();
            if (!prior.isEmpty()) {
                priorBlock.append("PRIOR CONVERSATION (last ").append(prior.size()).append(" turn")
                    .append(prior.size() == 1 ? "" : "s").append(" in this session):\n");
                for (int i = 0; i < prior.size(); i++) {
                    Map<String, Object> p = prior.get(i);
                    String pq = stringOrNull(p.get("question"));
                    String pa = stringOrNull(p.get("answer"));
                    String ps = stringOrNull(p.get("source"));
                    String pg = stringOrNull(p.get("grade"));
                    priorBlock.append("  [").append(i + 1).append("] Q: ").append(pq == null ? "(none)" : pq).append("\n");
                    priorBlock.append("      A (").append(ps == null ? "?" : ps);
                    if (pg != null) priorBlock.append(", graded ").append(pg);
                    priorBlock.append("): ").append(pa == null ? "(empty)" : pa).append("\n");
                }
                priorBlock.append("\n");
            }

            String latencyLine = "";
            if (latencyObj instanceof Number) {
                latencyLine = "LATENCY: " + ((Number) latencyObj).longValue() + " ms\n\n";
            }

            String reportText =
                "[User-graded " + grade + "] " + (displayName == null ? username : displayName) + " flagged this AI answer.\n\n" +
                priorBlock.toString() +
                "QUESTION:\n" + (question == null ? "(none)" : question) + "\n\n" +
                "MODEL: " + (model == null ? "(default)" : model) + "\n" +
                "SOURCE: " + (source == null || source.isEmpty() ? "(unknown — likely interceptor without source tag)" : source) + "\n" +
                latencyLine +
                "AI's ANSWER:\n" + (answer == null ? "(empty)" : answer) + "\n\n" +
                "USER'S EXPECTATION:\n" + expectation.trim() + "\n\n" +
                "\u2014 Filed automatically via AI Eval \u2192 Ask AI on " +
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) +
                " by " + (displayName == null ? username : displayName);

            try {
                Map<String, Object> r = supportController.submitFeedbackInternal(
                        "Bug Report", reportText, (org.springframework.web.multipart.MultipartFile) null, username, displayName, email);
                if (Boolean.TRUE.equals(r.get("success"))) {
                    Object pt = r.get("ptId");
                    if (pt != null) filedPtIds.add(pt.toString());
                    filedCount++;
                }
            } catch (Exception e) {
                logger.warning("[AI EVAL ASK] failed to file bug report: " + e.getMessage());
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("graded", totalGraded);
        resp.put("eligibleForBug", eligibleForBug);
        resp.put("filed", filedCount);
        resp.put("ptIds", filedPtIds);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/models")
    public ResponseEntity<?> listModels(HttpSession session) {
        // Available to any logged-in user — the User-Ask sub-tab also needs the
        // catalog. The model slugs aren't sensitive (anyone logged in is a
        // SanDisk employee on the corp network).
        if (session.getAttribute("username") == null) return ResponseEntity.status(401).body(err("Login required."));
        java.util.List<Map<String, Object>> groups = new java.util.ArrayList<>();
        groups.add(group("Anthropic", new String[][]{
            {"@vertexai-global/anthropic.claude-opus-4-8", "Claude Opus 4.8"},
            {"@anthropic-eastus2/claude-sonnet-4-6", "Claude Sonnet 4.6"},
            {"@anthropic-eastus2/claude-opus-4-7",   "Claude Opus 4.7"},
            {"@anthropic-eastus2/claude-haiku-4-5-20251001", "Claude Haiku 4.5"}
        }));
        groups.add(group("OpenAI", new String[][]{
            {"@openai-eastus2/gpt-4o",       "GPT-4o"},
            {"@openai-eastus2/gpt-4o-mini",  "GPT-4o-mini"},
            {"@openai-eastus2/gpt-4-turbo",  "GPT-4 Turbo"}
        }));
        groups.add(group("Google", new String[][]{
            {"@vertexai-global/gemini-2.5-pro",   "Gemini 2.5 Pro"},
            {"@vertexai-global/gemini-2.5-flash", "Gemini 2.5 Flash"}
        }));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups", groups);
        body.put("placeholder", "@anthropic-eastus2/claude-haiku-4-5");
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> group(String label, String[][] entries) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("label", label);
        java.util.List<Map<String, String>> options = new java.util.ArrayList<>();
        for (String[] e : entries) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("slug", e[0]);
            o.put("label", e[1]);
            options.add(o);
        }
        g.put("options", options);
        return g;
    }

    /**
     * Quality-gate the "What did you expect?" text before it is filed as a Bug
     * Report. Rejects gibberish ("gibebjvpdfslvnksfdbkfsb") and content-free
     * strings ("this is wrong") so the PLM admin triage queue only sees
     * actionable feedback. Fails open on any LLM/parse error — a broken
     * validator must not block users from filing legitimate bugs.
     *
     * Body: {question, answer, expectation}. Returns {valid, reason}.
     */
    @PostMapping("/ask/validate-expectation")
    public ResponseEntity<?> validateExpectation(@RequestBody Map<String, Object> body, HttpSession session) {
        if (session.getAttribute("username") == null) return ResponseEntity.status(401).body(err("Login required."));

        String expectation = stringOrNull(body.get("expectation"));
        String trimmed = expectation == null ? "" : expectation.trim();
        if (trimmed.isEmpty()) {
            return ResponseEntity.ok(buildValidation(false, "Please enter what you expected."));
        }

        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        // Deterministic gibberish gate — runs BEFORE any LLM call so that obvious
        // keyboard-mashing ("vhjdfbk df,vndsf") is always rejected, even when
        // Portkey is rate-limited or unreachable (which previously triggered
        // fail-open and let junk through).
        if (com.sandisk.plm.tracker.util.GibberishHeuristic.looksLikeGibberish(trimmed)) {
            activityLogger.log(username, displayName, "AI_EVAL_EXPECTATION_VALIDATE",
                "valid=false len=" + trimmed.length() + " gate=gibberish-heuristic");
            return ResponseEntity.ok(buildValidation(false,
                "That looks like random characters. Please describe what you expected the AI to say."));
        }

        String question = stringOrNull(body.get("question"));
        String answer = stringOrNull(body.get("answer"));
        String qTrim = question == null ? "" : question.trim();
        String aTrim = answer == null ? "" : answer.trim();
        // Backend-side truncation keeps the LLM prompt small and cheap.
        String aForPrompt = aTrim.length() > 1000 ? aTrim.substring(0, 1000) + "…" : aTrim;
        String eForPrompt = trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;

        try {
            if (!portkeyClient.isEnabled()) {
                // No LLM available — fail open. Log so we know it's happening.
                logger.warning("[ASK VALIDATE] portkey disabled, skipping validation");
                return ResponseEntity.ok(buildValidation(true, null));
            }

            String system = "You are a quality gate for a 'What did you expect?' field on an AI bug report. "
                + "You decide whether the user's expectation text is actionable enough to file as a bug report. "
                + "Reply with JSON only: {\"valid\": true|false, \"reason\": \"<short user-facing message or null>\"}. "
                + "Reject (valid=false) if the expectation is: "
                + "(a) gibberish, keyboard mashing, or non-words; "
                + "(b) content-free (e.g. 'this is wrong', 'bad', 'no', 'fix it', 'wtf') with no description of what was expected; "
                + "(c) an insult, profanity, or off-topic remark with no actionable signal. "
                + "Accept (valid=true) if it gives at least a brief description of the expected answer, the correct value, or what the AI got wrong. "
                + "Terse-but-real expectations are fine. The 'reason' string, when valid=false, MUST be addressed to the end user "
                + "(e.g. 'Please describe what you expected the AI to say.') — keep it under 100 chars and do not quote their text back.";

            StringBuilder user = new StringBuilder();
            user.append("QUESTION the user asked the AI:\n").append(qTrim).append("\n\n");
            if (!aForPrompt.isEmpty()) {
                user.append("ANSWER the AI gave (which the user graded poorly):\n").append(aForPrompt).append("\n\n");
            }
            user.append("USER'S EXPECTATION (what they said the AI should have said):\n").append(eForPrompt).append("\n\n");
            user.append("Is this expectation actionable enough to file as a Bug Report? Reply with JSON only.");

            String haiku = "@anthropic-eastus2/claude-haiku-4-5-20251001";
            String resp = portkeyClient.chat(haiku, system, user.toString(), 150);
            if (resp == null) {
                logger.warning("[ASK VALIDATE] null response from portkey, failing open");
                return ResponseEntity.ok(buildValidation(true, null));
            }

            int braceStart = resp.indexOf('{');
            int braceEnd = resp.lastIndexOf('}');
            if (braceStart < 0 || braceEnd <= braceStart) {
                logger.warning("[ASK VALIDATE] no JSON in response, failing open: " + truncate(resp, 200));
                return ResponseEntity.ok(buildValidation(true, null));
            }
            String json = resp.substring(braceStart, braceEnd + 1);

            // Extract "valid" boolean — first occurrence.
            boolean valid = true;
            int vi = json.indexOf("\"valid\"");
            if (vi >= 0) {
                int colon = json.indexOf(':', vi);
                if (colon >= 0) {
                    String tail = json.substring(colon + 1).trim().toLowerCase();
                    if (tail.startsWith("false")) valid = false;
                    else if (tail.startsWith("true")) valid = true;
                    else {
                        // Couldn't parse — fail open.
                        logger.warning("[ASK VALIDATE] unparseable valid field, failing open: " + truncate(json, 200));
                        valid = true;
                    }
                }
            }

            String reason = null;
            if (!valid) {
                int ri = json.indexOf("\"reason\"");
                if (ri >= 0) {
                    int qStart = json.indexOf('"', json.indexOf(':', ri) + 1);
                    if (qStart >= 0) {
                        int qEnd = qStart + 1;
                        StringBuilder sb = new StringBuilder();
                        while (qEnd < json.length()) {
                            char c = json.charAt(qEnd);
                            if (c == '\\' && qEnd + 1 < json.length()) {
                                char n = json.charAt(qEnd + 1);
                                if (n == 'n') sb.append(' ');
                                else if (n == '"') sb.append('"');
                                else sb.append(n);
                                qEnd += 2;
                            } else if (c == '"') {
                                break;
                            } else {
                                sb.append(c);
                                qEnd++;
                            }
                        }
                        reason = sb.toString().trim();
                    }
                }
                if (reason == null || reason.isEmpty() || "null".equalsIgnoreCase(reason)) {
                    reason = "Please describe what you expected the AI to say.";
                }
                if (reason.length() > 200) reason = reason.substring(0, 200);
            }

            activityLogger.log(username, displayName, "AI_EVAL_EXPECTATION_VALIDATE",
                "valid=" + valid + " len=" + trimmed.length()
                    + (reason == null ? "" : " reason=" + truncate(reason, 80)));

            return ResponseEntity.ok(buildValidation(valid, reason));
        } catch (Exception e) {
            logger.warning("[ASK VALIDATE] exception, failing open: " + e.getMessage());
            return ResponseEntity.ok(buildValidation(true, null));
        }
    }

    private static Map<String, Object> buildValidation(boolean valid, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", valid);
        m.put("reason", reason);
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // Gibberish detection moved to {@link com.sandisk.plm.tracker.util.GibberishHeuristic}
    // so SupportController.submitFeedback can share the same gate.

    /**
     * Validate a custom model slug by firing a 1-token "ping" via PortkeyClient.
     * Returns {ok: true, latencyMs: N} when reachable; {ok: false, error: "..."} otherwise.
     * Used by the "Custom..." manual-entry option in each model dropdown.
     */
    @PostMapping("/models/validate")
    public ResponseEntity<?> validateModel(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        String slug = stringOrNull(body.get("model"));
        if (slug == null || slug.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("model slug is required"));
        }
        slug = slug.trim();
        if (!slug.startsWith("@") || !slug.contains("/")) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("error", "Slug must be of the form @<provider-region>/<model-name>");
            return ResponseEntity.ok(bad);
        }
        long t0 = System.currentTimeMillis();
        try {
            // Tiny "say hi" call. Some models (Gemini reasoning) burn most of the budget on hidden
            // chain-of-thought, so use 200 tokens — enough to come back with at least one visible char.
            String reply = portkeyClient.chat(slug,
                "You are a connectivity check. Reply with exactly one word: ok",
                "ping", 200);
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("latencyMs", ms);
            ok.put("sample", reply == null ? "" : reply.substring(0, Math.min(60, reply.length())));
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            logger.warning("[AI EVAL] model validate failed for " + slug + " ms=" + ms + " err=" + e.getMessage());
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("error", e.getMessage() == null ? "unknown error" : e.getMessage());
            bad.put("latencyMs", ms);
            return ResponseEntity.ok(bad);
        }
    }

    @Autowired
    private com.sandisk.plm.tracker.service.PortkeyClient portkeyClient;

    private static boolean isAdmin(HttpSession session) {
        Boolean v = (Boolean) session.getAttribute("isPlmAdmin");
        return v != null && v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, String> err(String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", message == null ? "unknown" : message);
        return m;
    }
}
