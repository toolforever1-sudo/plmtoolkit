package com.sandisk.plm.tracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AnnouncementEmailService;
import com.sandisk.plm.tracker.service.AnnouncementService;
import com.sandisk.plm.tracker.service.PortkeyClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST surface for Admin &rarr; Communications &rarr; Announcements.
 *
 * <p>Everything except the two banner endpoints is gated on the
 * {@code isPlmAdmin} session attribute (pdl-plm-admin membership), checked
 * server-side on EVERY call — hiding the UI is cosmetic only. Banner
 * endpoints serve any authenticated user (AuthFilter guarantees login).
 *
 * <p>AI actions (wordsmith / generate / subjects) go through PortkeyClient
 * with a no-credentials guardrail in the system prompt, and every AI output
 * is additionally passed through {@link AnnouncementService#scrubCredentials}
 * before it can reach a draft or an email body. Activity log entries carry
 * metadata only (lengths/model), never content.
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private static final Logger LOG = Logger.getLogger(AnnouncementController.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VOICE_GUARDRAIL =
            "Write in the PLM Toolkit voice: plain, factual, engineer-to-engineer, sentence case. "
            + "CRITICAL: never include passwords, credentials, tokens, API keys, or secrets in your output, "
            + "even if the input contains them — replace any such value with [redacted].";

    private static final String WORDSMITH_SYSTEM =
            "You clean up rough announcement drafts for an internal engineering tool (Agile PLM Toolkit at SanDisk). "
            + "Rewrite the user's draft as polished announcement prose with the SAME meaning and facts — do not add "
            + "new claims, do not drop facts, keep URLs and dates exactly as given. Keep it short (2-5 sentences). "
            + "Return ONLY the cleaned prose, no preamble, no markdown. " + VOICE_GUARDRAIL;

    private static final String GENERATE_SYSTEM =
            "You turn an announcement subject + draft into structured email content for an internal engineering tool. "
            + "Return STRICT JSON (no markdown fences, no commentary): "
            + "{\"intro\": string, \"facts\": [{\"label\": string, \"value\": string}], \"outro\": string}. "
            + "intro MUST start exactly with: Hi ${firstName} —  (keep the literal ${firstName} token). "
            + "Extract 2-4 key facts (e.g. New URL / Old URL / Action / Date) into the facts array; labels max 3 words. "
            + "The only HTML allowed inside strings is <strong> and <a href=\"...\">. "
            + "outro is a short closing line (support/contact info if present in the draft). " + VOICE_GUARDRAIL;

    private static final String SUBJECTS_SYSTEM =
            "You suggest email subject lines for an internal tool announcement. Return STRICT JSON: an array of "
            + "exactly 3 strings. Each at most 60 characters, sentence case (no Title Case), factual, no emoji, "
            + "no exclamation marks. " + VOICE_GUARDRAIL;

    @Autowired private AnnouncementService announcements;
    @Autowired private PortkeyClient portkeyClient;
    @Autowired private ActivityLogger activityLogger;

    @Value("${portkey.provider:@anthropic-eastus2}")
    private String portkeyProvider;

    @Value("${portkey.model:claude-sonnet-4-6}")
    private String portkeyModel;

    // ---- auth helpers ----------------------------------------------------

    private static boolean isAdmin(HttpSession session) {
        Boolean v = (Boolean) session.getAttribute("isPlmAdmin");
        return v != null && v;
    }

    private static String username(HttpSession session) {
        Object v = session.getAttribute("username");
        return v == null ? "?" : v.toString();
    }

    private static String displayName(HttpSession session) {
        Object v = session.getAttribute("displayName");
        return v == null ? username(session) : v.toString();
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(err("Admin access required."));
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", msg);
        return m;
    }

    // ---- CRUD / list -----------------------------------------------------

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Map<String, Object> out = new HashMap<>();
        out.put("announcements", announcements.list());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/audience")
    public ResponseEntity<?> audience(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ResponseEntity.ok(announcements.audienceCounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        AnnouncementService.Announcement a = announcements.get(id);
        if (a == null) return ResponseEntity.status(404).body(err("Not found."));
        Map<String, Object> out = new HashMap<>();
        out.put("announcement", a);
        out.put("archivedHtml", announcements.getArchivedHtml(id));
        if (a.bodyHtml != null && !a.bodyHtml.isEmpty()) {
            out.put("previewHtml", previewFor(a, session));
        }
        return ResponseEntity.ok(out);
    }

    /** Preview = full render personalized with the CALLER's first name (what their test email will look like). */
    private String previewFor(AnnouncementService.Announcement a, HttpSession session) {
        String full = announcements.renderFull(a);
        return AnnouncementEmailService.personalize(full,
                AnnouncementEmailService.firstNameOf(displayName(session)));
    }

    @PostMapping
    public ResponseEntity<?> upsert(@RequestBody AnnouncementService.Announcement patch, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            AnnouncementService.Announcement a =
                    announcements.upsert(patch, username(session), displayName(session));
            Map<String, Object> out = new HashMap<>();
            out.put("announcement", a);
            if (a.bodyHtml != null && !a.bodyHtml.isEmpty()) out.put("previewHtml", previewFor(a, session));
            return ResponseEntity.ok(out);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(err(ex.getMessage()));
        }
    }

    // ---- AI actions ------------------------------------------------------

    private String modelSlug() { return portkeyProvider + "/" + portkeyModel; }

    private ResponseEntity<?> aiUnavailable() {
        return ResponseEntity.status(503).body(err("AI is not available on this instance (Portkey disabled)."));
    }

    @PostMapping("/{id}/ai/wordsmith")
    public ResponseEntity<?> wordsmith(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        if (!portkeyClient.isEnabled()) return aiUnavailable();
        AnnouncementService.Announcement a = announcements.get(id);
        if (a == null) return ResponseEntity.status(404).body(err("Not found."));
        if (a.draft == null || a.draft.trim().isEmpty()) return ResponseEntity.badRequest().body(err("Draft is empty."));
        try {
            String text = AnnouncementService.scrubCredentials(
                    portkeyClient.chat(modelSlug(), WORDSMITH_SYSTEM, a.draft, 800));
            activityLogger.log(username(session), displayName(session), "ANNOUNCEMENT_AI_WORDSMITH",
                    "id=" + id + " inLen=" + a.draft.length() + " outLen=" + (text == null ? 0 : text.length())
                    + " model=" + modelSlug());
            Map<String, Object> out = new HashMap<>();
            out.put("text", text == null ? "" : text.trim());
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] wordsmith failed: " + ex.getMessage());
            return ResponseEntity.status(502).body(err("AI call failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/{id}/ai/generate")
    public ResponseEntity<?> generate(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        if (!portkeyClient.isEnabled()) return aiUnavailable();
        AnnouncementService.Announcement a = announcements.get(id);
        if (a == null) return ResponseEntity.status(404).body(err("Not found."));
        String source = (a.draft == null || a.draft.trim().isEmpty()) ? null : a.draft.trim();
        if (source == null) return ResponseEntity.badRequest().body(err("Draft is empty."));
        try {
            String raw = portkeyClient.chat(modelSlug(), GENERATE_SYSTEM,
                    "Subject: " + (a.subject == null ? "" : a.subject) + "\n\nDraft:\n" + source, 1200);
            String bodyHtml = AnnouncementService.scrubCredentials(buildBodyHtml(raw));

            // Persist through upsert so the revision bump + edit trail apply.
            AnnouncementService.Announcement patch = new AnnouncementService.Announcement();
            patch.id = a.id;
            patch.subject = a.subject;
            patch.bodyHtml = bodyHtml;
            patch.audienceType = a.audienceType;
            patch.specificUsers = a.specificUsers;
            patch.channelEmail = a.channelEmail;
            patch.channelBanner = a.channelBanner;
            patch.channelWhatsNew = a.channelWhatsNew;
            AnnouncementService.Announcement saved =
                    announcements.upsert(patch, username(session), displayName(session));

            activityLogger.log(username(session), displayName(session), "ANNOUNCEMENT_AI_GENERATE",
                    "id=" + id + " htmlLen=" + bodyHtml.length() + " model=" + modelSlug());
            Map<String, Object> out = new HashMap<>();
            out.put("announcement", saved);
            out.put("previewHtml", previewFor(saved, session));
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] generate failed: " + ex.getMessage());
            return ResponseEntity.status(502).body(err("AI generate failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/{id}/ai/subjects")
    public ResponseEntity<?> subjects(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        if (!portkeyClient.isEnabled()) return aiUnavailable();
        AnnouncementService.Announcement a = announcements.get(id);
        if (a == null) return ResponseEntity.status(404).body(err("Not found."));
        if (a.draft == null || a.draft.trim().isEmpty()) return ResponseEntity.badRequest().body(err("Draft is empty."));
        try {
            String raw = portkeyClient.chat(modelSlug(), SUBJECTS_SYSTEM, a.draft, 300);
            List<String> subjects = new ArrayList<>();
            JsonNode arr = JSON.readTree(stripFences(raw));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String s = AnnouncementService.scrubCredentials(n.asText(""));
                    if (s != null && !s.trim().isEmpty()) subjects.add(s.trim());
                }
            }
            activityLogger.log(username(session), displayName(session), "ANNOUNCEMENT_AI_SUBJECTS",
                    "id=" + id + " n=" + subjects.size() + " model=" + modelSlug());
            Map<String, Object> out = new HashMap<>();
            out.put("subjects", subjects);
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] subjects failed: " + ex.getMessage());
            return ResponseEntity.status(502).body(err("AI call failed: " + ex.getMessage()));
        }
    }

    /** AI JSON → email-client-safe inner body (intro, key-facts table, outro, sign-off). */
    static String buildBodyHtml(String rawAiJson) throws Exception {
        JsonNode root = JSON.readTree(stripFences(rawAiJson));
        String intro = root.path("intro").asText("");
        String outro = root.path("outro").asText("");
        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"font-size:13.5px; color:#4a4f58; line-height:1.6; margin:0 0 4px;\">")
          .append(intro).append("</p>");
        JsonNode facts = root.path("facts");
        if (facts.isArray() && facts.size() > 0) {
            sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
              .append("style=\"margin:14px 0; border:1px solid #ececec; border-radius:6px; border-collapse:separate;\">");
            for (int i = 0; i < facts.size(); i++) {
                JsonNode fact = facts.get(i);
                String borderBottom = (i < facts.size() - 1) ? "border-bottom:1px solid #ececec;" : "";
                sb.append("<tr>")
                  .append("<td width=\"140\" style=\"width:140px; background:#fafaf7; padding:10px 14px; ")
                  .append("font-family:'IBM Plex Mono',Consolas,monospace; font-size:10px; letter-spacing:0.1em; ")
                  .append("text-transform:uppercase; color:#6B7280; ").append(borderBottom).append("\">")
                  .append(fact.path("label").asText("")).append("</td>")
                  .append("<td style=\"padding:9px 14px; font-size:13px; color:#0F1720; ").append(borderBottom).append("\">")
                  .append(fact.path("value").asText("")).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }
        sb.append("<p style=\"font-size:13.5px; color:#4a4f58; line-height:1.6; margin:0;\">")
          .append(outro).append("<br>&mdash; PLM IT</p>");
        return sb.toString();
    }

    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
        }
        return t.trim();
    }

    // ---- test + send + schedule -------------------------------------------

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String email = (String) session.getAttribute("email");
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("No email address on your session — log out and back in."));
        }
        AnnouncementService.Announcement a = announcements.get(id);
        if (a == null) return ResponseEntity.status(404).body(err("Not found."));
        if (a.bodyHtml == null || a.bodyHtml.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("Generate the HTML email first."));
        }
        try {
            announcements.sendTest(id, email.trim(), displayName(session), username(session));
            Map<String, Object> out = new HashMap<>();
            out.put("sentTo", email.trim());
            out.put("announcement", announcements.get(id));
            return ResponseEntity.ok(out);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[ANNOUNCE] test send failed: " + ex.getMessage());
            return ResponseEntity.status(502).body(err("Test send failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String when = body.get("when") == null ? "now" : body.get("when").toString();
        try {
            if ("now".equalsIgnoreCase(when)) {
                return ResponseEntity.ok(announcements.sendNow(id, username(session), displayName(session)));
            }
            AnnouncementService.Announcement a =
                    announcements.schedule(id, when, username(session), displayName(session));
            activityLogger.log(username(session), displayName(session), "ANNOUNCEMENT_SCHEDULED",
                    "id=" + id + " for=" + a.scheduledFor);
            Map<String, Object> out = new HashMap<>();
            out.put("announcement", a);
            return ResponseEntity.ok(out);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(err(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(err(ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}/schedule")
    public ResponseEntity<?> cancelSchedule(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            AnnouncementService.Announcement a =
                    announcements.cancelSchedule(id, username(session), displayName(session));
            activityLogger.log(username(session), displayName(session), "ANNOUNCEMENT_UNSCHEDULED", "id=" + id);
            Map<String, Object> out = new HashMap<>();
            out.put("announcement", a);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(err(ex.getMessage()));
        }
    }

    // ---- banner (any authenticated user) ------------------------------------

    @GetMapping("/banner")
    public ResponseEntity<?> banner(HttpSession session) {
        AnnouncementService.Announcement a = announcements.activeBannerFor(username(session));
        Map<String, Object> out = new HashMap<>();
        if (a != null) {
            out.put("id", a.id);
            out.put("subject", a.subject);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/banner/{id}/dismiss")
    public ResponseEntity<?> dismissBanner(@PathVariable String id, HttpSession session) {
        try {
            announcements.dismissBanner(id, username(session));
            return ResponseEntity.ok(new HashMap<String, Object>());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(err(ex.getMessage()));
        }
    }
}
