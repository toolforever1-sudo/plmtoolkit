package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.FileArchiveService;
import com.sandisk.plm.tracker.service.GradeEmailService;
import com.sandisk.plm.tracker.service.GradeEvaluatorService;
import com.sandisk.plm.tracker.service.GradeModels;
import com.sandisk.plm.tracker.service.GradeStorageService;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Backs the Meeting-Mode <b>Backlog grade</b> revamp: evidence-gated disputes,
 * runtime grading-logic changes, the persisted audit trail (History/Outbox),
 * trend baseline, and screenshot attachments. Persistence mirrors Meeting Mode
 * ({@link GradeStorageService}); auth is the same gate as
 * {@link MeetingController} — the caller must have the {@code it-enhancements}
 * tab granted.
 */
@RestController
@RequestMapping("/api/meeting/grade")
public class GradeController {

    private static final Logger LOG = Logger.getLogger(GradeController.class.getName());
    private static final long MAX_ATTACH = 10L * 1024 * 1024;

    @Autowired private GradeStorageService storage;
    @Autowired private GradeEmailService emailService;
    @Autowired private GradeEvaluatorService evaluator;
    @Autowired private FileArchiveService fileArchive;
    @Autowired private UserPermissionsService userPermissionsService;

    // ==================================================================
    // Evaluate
    // ==================================================================

    /** Adjudicate a dispute. Body:
     *  {@code {kind, ecn?, ruleKey?, reason, hasFile, model, record:{desc,title,status}}}. */
    @PostMapping("/evaluate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(err("request body required"));
        String kind = str(body.get("kind"));
        String reason = str(body.get("reason"));
        boolean hasFile = Boolean.TRUE.equals(body.get("hasFile"));
        String model = str(body.get("model"));
        String desc = null, title = null, status = null;
        Object rec = body.get("record");
        if (rec instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) rec;
            desc = str(m.get("desc")); title = str(m.get("title")); status = str(m.get("status"));
        }
        GradeModels.EvaluateResult r = evaluator.evaluate(kind, reason, hasFile, model, desc, title, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status);
        out.put("verifiedBy", r.verifiedBy);
        out.put("fieldQuote", r.fieldQuote);
        out.put("reason", r.reason);
        out.put("evidence", r.evidence);
        return ResponseEntity.ok(out);
    }

    // ==================================================================
    // Accepted item disputes
    // ==================================================================

    @PostMapping("/disputes")
    public ResponseEntity<?> createDispute(@RequestBody GradeModels.GradeRecord rec, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (rec == null) return ResponseEntity.badRequest().body(err("request body required"));
        rec.kind = "item";
        if (rec.action == null || rec.action.isEmpty()) rec.action = "exclude";
        stamp(rec, session);
        storage.saveRecord(rec);
        // Excludes always email admin; includes only when they actually moved the grade
        // (e.g. restoring a previously-excluded item), so a plain "keep in scope" is silent.
        boolean gradeChanged = rec.scoreBefore != rec.scoreAfter;
        GradeModels.OutboxEntry mail = null;
        if ("exclude".equals(rec.action) || gradeChanged) {
            rec.emailedTo = emailService.adminRecipient();
            mail = emailService.sendAdminAccept(rec);
            storage.appendOutbox(mail);
        }
        return ResponseEntity.ok(buildMap("record", rec, "emailedTo", rec.emailedTo, "outbox", mail));
    }

    /** Parse a free-form Ask-AI message into resolved include/exclude actions
     *  (LLM-driven, deterministic fallback). Does not persist — the client applies
     *  the actions, re-grades, and posts each to /disputes. */
    @PostMapping("/chat")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(err("request body required"));
        String message = str(body.get("message"));
        String model = str(body.get("model"));
        List<Map<String, Object>> atRisk = body.get("atRisk") instanceof List ? (List<Map<String, Object>>) body.get("atRisk") : new java.util.ArrayList<>();
        List<String> excluded = body.get("excluded") instanceof List ? (List<String>) body.get("excluded") : new java.util.ArrayList<>();
        GradeModels.ChatResult r = evaluator.chat(message, atRisk, excluded, model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", r.reply);
        out.put("actions", r.actions);
        // A standing theme policy stated in chat is persisted here (like the rule box).
        if (r.policy != null && r.policy.accept && r.policy.predicate != null && !r.policy.predicate.isEmpty()) {
            GradeModels.ThemePolicy pol = savePolicyFrom(r.policy, message, session);
            GradeModels.OutboxEntry mail = emailService.sendItPolicy(pol, r.policy.matched.size());
            storage.appendOutbox(mail);
            out.put("policy", pol);
            out.put("matched", r.policy.matched);
            out.put("count", r.policy.matched.size());
            out.put("rationale", r.policy.rationale);
            out.put("emailedTo", emailService.itRecipient());
        }
        return ResponseEntity.ok(out);
    }

    // ==================================================================
    // Runtime logic changes
    // ==================================================================

    @PostMapping("/logic-changes")
    public ResponseEntity<?> createLogicChange(@RequestBody GradeModels.GradeRecord rec, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (rec == null) return ResponseEntity.badRequest().body(err("request body required"));
        rec.kind = "rule";
        stamp(rec, session);
        rec.emailedTo = emailService.itRecipient();
        storage.saveRecord(rec);
        GradeModels.OutboxEntry mail = emailService.sendItLogicChange(rec);
        storage.appendOutbox(mail);
        return ResponseEntity.ok(buildMap("record", rec, "emailedTo", rec.emailedTo, "outbox", mail));
    }

    /** Revert a logic change so it stops applying to future grades. */
    @DeleteMapping("/logic-changes/{id}")
    public ResponseEntity<?> revertLogicChange(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        storage.deleteRecord(id);
        return ResponseEntity.ok(buildMap("ok", true));
    }

    // ==================================================================
    // Standing theme policies
    // ==================================================================

    /** Rule-box submit: classify a free-form argument as a content POLICY (a
     *  theme of ECNs) or a numeric TOGGLE for {@code ruleKey}. Policy wins if the
     *  argument describes a class of ECNs; otherwise fall back to the toggle. */
    @PostMapping("/rule-or-policy")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> ruleOrPolicy(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(err("request body required"));
        String reason = str(body.get("reason")), ruleKey = str(body.get("ruleKey")), model = str(body.get("model"));
        List<Map<String, Object>> items = body.get("items") instanceof List ? (List<Map<String, Object>>) body.get("items") : new java.util.ArrayList<>();
        GradeModels.PolicyProposal pp = evaluator.evaluatePolicy(reason, items, model);
        if (pp.accept && pp.predicate != null && !pp.predicate.isEmpty()) {
            GradeModels.ThemePolicy pol = savePolicyFrom(pp, reason, session);
            GradeModels.OutboxEntry mail = emailService.sendItPolicy(pol, pp.matched.size());
            storage.appendOutbox(mail);
            return ResponseEntity.ok(buildMap("outcome", "policy", "policy", pol, "matched", pp.matched, "count", pp.matched.size(), "rationale", pp.rationale, "emailedTo", emailService.itRecipient()));
        }
        GradeModels.EvaluateResult ev = evaluator.evaluate("rule", reason, false, model, null, null, null);
        String outcome = "accept".equals(ev.status) ? "toggle" : "reject";   // rules never need a per-ECN file
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", outcome); out.put("ruleKey", ruleKey);
        out.put("verifiedBy", ev.verifiedBy); out.put("reason", ev.reason); out.put("evidence", ev.evidence);
        return ResponseEntity.ok(out);
    }

    /** Create a standing policy directly (used by Ask AI). */
    @PostMapping("/policies")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> createPolicy(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(err("request body required"));
        String reason = str(body.get("reason")), model = str(body.get("model"));
        List<Map<String, Object>> items = body.get("items") instanceof List ? (List<Map<String, Object>>) body.get("items") : new java.util.ArrayList<>();
        GradeModels.PolicyProposal pp = evaluator.evaluatePolicy(reason, items, model);
        if (!pp.accept || pp.predicate == null || pp.predicate.isEmpty())
            return ResponseEntity.ok(buildMap("accepted", false, "reason", pp.reason == null || pp.reason.isEmpty() ? "That isn't a clear grading policy." : pp.reason));
        GradeModels.ThemePolicy pol = savePolicyFrom(pp, reason, session);
        GradeModels.OutboxEntry mail = emailService.sendItPolicy(pol, pp.matched.size());
        storage.appendOutbox(mail);
        return ResponseEntity.ok(buildMap("accepted", true, "policy", pol, "matched", pp.matched, "count", pp.matched.size(), "rationale", pp.rationale, "emailedTo", emailService.itRecipient()));
    }

    /** Active standing policies. */
    @GetMapping("/policies")
    public ResponseEntity<?> listPolicies(HttpSession session) {
        if (!authorized(session)) return forbidden();
        java.util.List<GradeModels.ThemePolicy> active = new java.util.ArrayList<>();
        for (GradeModels.ThemePolicy p : storage.listPolicies()) if (p.active) active.add(p);
        return ResponseEntity.ok(buildMap("policies", active));
    }

    /** Revert (deactivate) a standing policy. */
    @DeleteMapping("/policies/{id}")
    public ResponseEntity<?> revertPolicy(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        storage.deactivatePolicy(id);
        return ResponseEntity.ok(buildMap("ok", true));
    }

    /** Re-apply all active policies to the current backlog (called on every grade).
     *  Returns the union of excluded ECNs plus per-policy counts. */
    @PostMapping("/apply-policies")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> applyPolicies(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        List<Map<String, Object>> items = body != null && body.get("items") instanceof List ? (List<Map<String, Object>>) body.get("items") : new java.util.ArrayList<>();
        String model = body == null ? null : str(body.get("model"));
        java.util.List<GradeModels.ThemePolicy> active = new java.util.ArrayList<>();
        for (GradeModels.ThemePolicy p : storage.listPolicies()) if (p.active) active.add(p);
        Map<String, List<String>> byPolicy = evaluator.matchPolicies(items, active, model);
        java.util.LinkedHashSet<String> excluded = new java.util.LinkedHashSet<>();
        java.util.List<Map<String, Object>> summary = new java.util.ArrayList<>();
        for (GradeModels.ThemePolicy p : active) {
            List<String> ecns = byPolicy.getOrDefault(p.id, java.util.Collections.emptyList());
            excluded.addAll(ecns);
            summary.add(buildMap("id", p.id, "predicate", p.predicate, "count", ecns.size(), "ecns", ecns));
        }
        return ResponseEntity.ok(buildMap("excluded", new java.util.ArrayList<>(excluded), "policies", summary));
    }

    private GradeModels.ThemePolicy savePolicyFrom(GradeModels.PolicyProposal pp, String reason, HttpSession session) {
        GradeModels.ThemePolicy pol = new GradeModels.ThemePolicy();
        pol.id = "gp_" + UUID.randomUUID().toString();
        pol.predicate = pp.predicate;
        pol.userReason = reason;
        pol.aiRationale = pp.rationale;
        pol.actorAd = (String) session.getAttribute("username");
        pol.actorName = (String) session.getAttribute("displayName");
        pol.createdAt = System.currentTimeMillis();
        pol.active = true;
        storage.savePolicy(pol);
        return pol;
    }

    // ==================================================================
    // History (audit trail) + trend baseline
    // ==================================================================

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpSession session) {
        if (!authorized(session)) return forbidden();
        List<GradeModels.GradeRecord> all = storage.listRecords();
        java.util.List<GradeModels.GradeRecord> logic = new java.util.ArrayList<>();
        java.util.List<GradeModels.GradeRecord> accepts = new java.util.ArrayList<>();
        for (GradeModels.GradeRecord r : all) {
            if ("rule".equals(r.kind)) { if (!r.reverted) logic.add(r); }
            else accepts.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logic", logic);
        out.put("accepts", accepts);
        out.put("outbox", storage.listOutbox());
        GradeModels.GradeRecord lg = storage.readLastGrade();
        if (lg != null && lg.gradeAfter != null) {
            out.put("lastGrade", buildMap("letter", lg.gradeAfter, "score", lg.scoreAfter));
        }
        out.put("notify", buildMap("admin", emailService.adminRecipient(), "it", emailService.itRecipient()));
        return ResponseEntity.ok(out);
    }

    /** Persist the grade snapshot at the end of a review (drives the trend pill). */
    @PostMapping("/last-grade")
    public ResponseEntity<?> saveLastGrade(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (body == null || body.get("letter") == null) return ResponseEntity.badRequest().body(err("letter + score required"));
        int score = body.get("score") instanceof Number ? ((Number) body.get("score")).intValue() : 0;
        storage.saveLastGrade(str(body.get("letter")), score);
        return ResponseEntity.ok(buildMap("ok", true));
    }

    // ==================================================================
    // Attachment upload
    // ==================================================================

    @PostMapping("/disputes/attachment")
    public ResponseEntity<?> uploadAttachment(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "ecn", required = false) String ecn,
                                              HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(err("file required"));
        if (file.getSize() > MAX_ATTACH) return ResponseEntity.badRequest().body(err("file too large (max 10 MB)"));
        String ct = file.getContentType();
        if (ct != null && !ct.toLowerCase().startsWith("image/")) return ResponseEntity.badRequest().body(err("only image screenshots are accepted"));
        String user = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        String ref = fileArchive.recordUpload(file, user, displayName,
                "/api/meeting/grade/disputes/attachment", "grade-dispute", ecn);
        if (ref == null) return ResponseEntity.status(500).body(err("could not store the attachment"));
        return ResponseEntity.ok(buildMap("attachmentRef", ref, "filename", file.getOriginalFilename(),
                "size", file.getSize(), "contentType", ct));
    }

    // ==================================================================
    // helpers (mirror MeetingController)
    // ==================================================================

    private void stamp(GradeModels.GradeRecord rec, HttpSession session) {
        if (rec.id == null || rec.id.isEmpty() || rec.id.contains("_local_")) {
            rec.id = (rec.kind.equals("rule") ? "gl_" : "gd_") + UUID.randomUUID().toString();
        }
        rec.actorAd = (String) session.getAttribute("username");
        rec.actorName = (String) session.getAttribute("displayName");
        if (rec.createdAt <= 0) rec.createdAt = System.currentTimeMillis();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private static Map<String, Object> buildMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private boolean authorized(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return false;
        Object tabs = session.getAttribute("allowedTabs");
        if (tabs instanceof List) return ((List<String>) tabs).contains("it-enhancements");
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        java.util.Set<String> adGroups = (java.util.Set<String>) session.getAttribute("adGroups");
        return userPermissionsService.getAllowedTabs(username, isAdmin,
                adGroups == null ? java.util.Collections.<String>emptySet() : adGroups)
                .contains("it-enhancements");
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(err("Forbidden — IT Enhancements tab not granted to this user."));
    }
}
