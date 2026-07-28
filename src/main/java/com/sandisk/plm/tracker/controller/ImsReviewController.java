package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.AgileDocumentAttachmentsClient;
import com.sandisk.plm.tracker.service.ImsReviewQueueStore;
import com.sandisk.plm.tracker.service.ImsReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IMS Review tab endpoints. See <code>docs/superpowers/specs/2026-05-18-ims-review-pilot-design.md</code>.
 */
@RestController
@RequestMapping("/api/ims-review")
public class ImsReviewController {

    @Autowired private ImsReviewService service;
    @Autowired private ImsReviewQueueStore queueStore;
    @Autowired private com.sandisk.plm.tracker.service.ImsActionLogService actionLogService;
    @Autowired private com.sandisk.plm.tracker.service.LdapAuthService ldapAuthService;
    @Autowired private com.sandisk.plm.tracker.service.AgileWriteBackClient writeBackClient;
    @Autowired private com.sandisk.plm.tracker.service.OwnerChangeAuditService ownerAudit;
    @Autowired private com.sandisk.plm.tracker.service.ImsReviewEmailService imsEmailService;
    @Autowired private AgileDocumentAttachmentsClient agileAttachments;
    @Autowired private com.sandisk.plm.tracker.service.ImsDocDetailsService docDetails;

    /** [IMS-TIMING] lines attribute latency to a specific leg (SQL vs agile-service)
     *  so a slow respond page / change-order drawer never has to be guessed at. */
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ImsReviewController.class.getName());

    /**
     * PT-123 (Jimmy): hide the "No DRR Required" KPI tile in the IMS Review
     * dashboard's Need-DRR group. Default {@code false} keeps the tile visible
     * (prod stays as-is — the change is dormant); set {@code true} in a QA box's
     * external application.properties to activate the removal there. Echoed to the
     * client in {@code /role} and consumed by {@code renderKpiStrip} in imsreview.js.
     */
    @org.springframework.beans.factory.annotation.Value("${app.ims.hide-no-drr-tile:false}")
    private boolean hideNoDrrTile;

    /** Fire-and-forget executor for the owner-reassignment notification — the
     *  Agile attachment fetch can take a few seconds and we don't want to
     *  block the modal's Save response on it. Single-thread so concurrent
     *  reassigns from a bulk action queue cleanly. */
    private final java.util.concurrent.ExecutorService reassignEmailExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ims-reassign-email");
                t.setDaemon(true);
                return t;
            });

    /** Admin/DCC view — doc list with status overlay. Cached at the service
     *  layer for 5 min; pass {@code refresh=true} (the Refresh button) to
     *  force a re-query of the heavy "docs due within N days" SQL.
     *
     *  <p>{@code maxOverdueYears} (default 5) caps how far INTO THE PAST a
     *  doc's next-review date is allowed to be — Jimmy's ask 2026-06-05.
     *  Without this cap, the legacy heap of docs from 2010-era reviews
     *  drowns out the actionable backlog. Pass 0 to mean "unlimited"
     *  (show every overdue doc no matter how ancient). */
    @GetMapping("/data")
    public ResponseEntity<?> data(HttpSession session,
                                  @RequestParam(value = "days", required = false, defaultValue = "30") int days,
                                  @RequestParam(value = "maxOverdueYears", required = false, defaultValue = "5") int maxOverdueYears,
                                  @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        if (refresh) service.invalidateDocsCache();
        return ResponseEntity.ok(service.dataForAdmin(days, maxOverdueYears));
    }

    /** Action History — derived, read-only feed of every user/system action on
     *  IMS Review docs over the last {@code windowDays} days (default 30),
     *  plus current per-state counts, per-transition counts (flow map) and
     *  per-doc journeys (drawer). Backed entirely by the existing queue.jsonl
     *  event log — no new logging at mutation points. Admin/DCC only. Fail-soft:
     *  an empty event store returns empty arrays / zero counts. */
    @GetMapping("/action-log")
    public ResponseEntity<?> actionLog(HttpSession session,
                                       @RequestParam(value = "windowDays", required = false, defaultValue = "30") int windowDays) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        return ResponseEntity.ok(actionLogService.buildActionLog(windowDays));
    }

    /** DO/DM card view — only items where current recipient = session user's email. */
    @GetMapping("/my-queue")
    public ResponseEntity<?> myQueue(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        String email = s(session, "email");
        return ResponseEntity.ok(service.myQueue(email));
    }

    /** Returns the user's effective role + queue size; JS picks admin vs DO/DM view.
     *  When {@code asDO=true} is passed AND the user has queue items, the view
     *  flips to card-only even for admins / permission-granted DCC users. This
     *  is the path the email link uses ("Submit your response" → focused queue). */
    @GetMapping("/role")
    public ResponseEntity<?> role(HttpSession session,
                                  @RequestParam(value = "asDO", required = false, defaultValue = "false") boolean asDO) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        boolean autoGranted = Boolean.TRUE.equals(session.getAttribute("imsReviewAutoGranted"));
        String email = s(session, "email");
        int queueSize = queueStore.pendingFor(email).size();

        // canSeeAdminView = admin OR explicit `ims-review` grant in User
        // Management. Auto-granted users (DO/DM-with-pending-items) do NOT count
        // — they only ever see the card view.
        boolean canSeeAdminView = isAdmin || hasExplicitImsReviewGrant(session);

        // Resolve view:
        //   asDO=true                 → card view (the email-link path). Even
        //                               if queueSize=0 (e.g. an admin on the
        //                               Cc DL who clicked out of curiosity),
        //                               we keep them on the card view — the
        //                               empty state renders gracefully with a
        //                               "Switch to admin view" link. The old
        //                               fallback dropped admins into the
        //                               heavy /data Agile query on every
        //                               email-link click, which is what we
        //                               want to avoid.
        //   canSeeAdminView           → admin table view
        //   otherwise                 → card view (auto-granted DO/DM)
        String view;
        if (asDO)                  view = "do_dm";
        else if (canSeeAdminView)  view = "admin";
        else                       view = "do_dm";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isAdmin", isAdmin);
        out.put("autoGranted", autoGranted);
        out.put("canSeeAdminView", canSeeAdminView);
        out.put("asDO", asDO);
        out.put("view", view);
        out.put("queueSize", queueSize);
        out.put("email", email);
        out.put("hideNoDrrTile", hideNoDrrTile);   // PT-123 — QA-only removal toggle
        return ResponseEntity.ok(out);
    }

    private static boolean hasExplicitImsReviewGrant(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("imsReviewAutoGranted"))) return false;
        @SuppressWarnings("unchecked")
        java.util.List<String> tabs = (java.util.List<String>) session.getAttribute("allowedTabs");
        return tabs != null && tabs.contains("ims-review");
    }

    /** DCC clicks Send (or Resend). */
    @PostMapping("/send")
    public ResponseEntity<?> send(HttpSession session,
                                  @RequestParam("docNumber") String docNumber,
                                  @RequestParam(value = "drrNumber", required = false, defaultValue = "") String drrNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        try {
            ImsReviewQueueStore.QueueItem q = service.send(docNumber, drrNumber,
                    s(session, "email"), s(session, "displayName"));
            return ResponseEntity.ok(toMap(q));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    /** DCC clicks "Send New DRR" on a Need-DRR row: create the DRR + send it. */
    @PostMapping("/create-drr")
    public ResponseEntity<?> createDrr(HttpSession session,
                                       @RequestParam("docNumber") String docNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        try {
            java.util.Map<String, Object> r = service.sendNewDrr(docNumber,
                    s(session, "email"), s(session, "displayName"));
            boolean ok = Boolean.TRUE.equals(r.get("ok"));
            return ok ? ResponseEntity.ok(r) : ResponseEntity.status(422).body(r);
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    /** DCC clicks Cancel. */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(HttpSession session,
                                    @RequestParam("docNumber") String docNumber,
                                    @RequestParam(value = "drrNumber", required = false, defaultValue = "") String drrNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        ImsReviewQueueStore.QueueItem q = service.cancel(docNumber, drrNumber,
                s(session, "email"), s(session, "displayName"));
        return ResponseEntity.ok(toMap(q));
    }

    /** Admin clicks Reset — throws the doc back to NOT_SENT so DCC can
     *  Send again from scratch. Admin-only (not granted DCC); destructive
     *  but auditable (RESET event lands in queue.jsonl + activity log). */
    @PostMapping("/reset")
    public ResponseEntity<?> reset(HttpSession session,
                                   @RequestParam("docNumber") String docNumber,
                                   @RequestParam(value = "drrNumber", required = false, defaultValue = "") String drrNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("IMS Review access required."));
        }
        ImsReviewQueueStore.QueueItem q = service.reset(docNumber, drrNumber,
                s(session, "email"), s(session, "displayName"));
        return ResponseEntity.ok(toMap(q));
    }

    /** Admin escape hatch — clear the password-attempt lockout on a specific
     *  token, OR on every SEND token for a doc when the recipient is stuck
     *  but doesn't have the token URL handy. Use docNumber when the user
     *  pings on Slack saying "I'm locked out of {doc}". */
    @PostMapping("/admin/unlock-token")
    public ResponseEntity<?> adminUnlockToken(HttpSession session,
                                              @RequestParam(value = "token", required = false) String token,
                                              @RequestParam(value = "docNumber", required = false) String docNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) {
            return ResponseEntity.status(403).body(err("PLM Admin access required."));
        }
        if ((token == null || token.isEmpty()) && (docNumber == null || docNumber.isEmpty())) {
            return ResponseEntity.badRequest().body(err("Provide either token or docNumber."));
        }
        int cleared = service.adminClearTokenLockouts(token, docNumber);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cleared", cleared);
        return ResponseEntity.ok(out);
    }

    /** Owner Change Audit — every Document Owner change on an IMS doc over
     *  the last {@code days} days (default 90). Distinguishes toolkit-driven
     *  changes from manual Agile UI changes via the
     *  "Owners updated via PLM Toolkit ..." history marker the toolkit stamps
     *  on every write. Admin/DCC only.
     *
     *  <p>Service-layer cache: 365-day snapshot rebuilt hourly. Sub-ms reads
     *  when warm. {@code refresh=true} forces a live SQL pull (40-90s on
     *  prod because the CLOB LIKE can't use an index) and replaces the
     *  cache; without it, the response is served from the snapshot trimmed
     *  to the requested window.
     *
     *  <p>Jimmy Sessumes's 2026-06-05 chat ask. */
    @GetMapping("/owner-audit")
    public ResponseEntity<?> ownerAudit(HttpSession session,
                                        @RequestParam(value = "days", required = false, defaultValue = "90") int days,
                                        @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("IMS Review access required."));
        }
        try {
            if (refresh) ownerAudit.refreshNow(days);
            return ResponseEntity.ok(ownerAudit.summary(days));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err("Owner audit failed: " + e.getMessage()));
        }
    }

    /** Admin diagnostic: dump the auto-retry queue (per-entry state + count
     *  of pending vs given-up). Used to know whether the runner has stuff
     *  in-flight + when the next attempt fires. */
    @GetMapping("/admin/retry-queue")
    public ResponseEntity<?> adminRetryQueue(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("IMS Review access required."));
        }
        return ResponseEntity.ok(service.retryQueueSummary());
    }

    /** Admin tool: drop a retry-queue entry without retrying it. Used when
     *  the operator resolved the underlying failure manually (e.g. via
     *  /replay-writeback) and wants to stop the runner from re-attempting. */
    @PostMapping("/admin/retry-queue/remove")
    public ResponseEntity<?> adminRetryQueueRemove(HttpSession session,
                                                   @RequestBody Map<String, String> body) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("IMS Review access required."));
        }
        String id = body == null ? null : body.get("id");
        if (id == null || id.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("id required"));
        }
        boolean removed = service.removeRetryEntry(id.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", removed);
        out.put("id", id);
        if (!removed) out.put("error", "Entry not found");
        return ResponseEntity.ok(out);
    }

    /** Admin tool: replay the Agile write-back for a stuck DRR whose original
     *  cascade aborted (typically because plm-agile-service was down during
     *  a redeploy window). Reconstructs the (event, ctx, verify) tuple from
     *  the persisted queue and runs the same writeback the live response
     *  handler would run.
     *
     *  <p>Body: {@code {docNumber, drrNumber, eventType?}}. {@code eventType}
     *  defaults to the latest response-type event in history. Returns the
     *  same shape the live writeback emits.
     *
     *  <p>Admin-only — gated on {@code hasAdminOrDccAccess}. */
    @PostMapping("/admin/replay-writeback")
    public ResponseEntity<?> adminReplayWriteback(HttpSession session,
                                                  @RequestBody Map<String, String> body) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("IMS Review access required."));
        }
        String docNumber = body == null ? null : body.get("docNumber");
        String drrNumber = body == null ? null : body.get("drrNumber");
        String eventType = body == null ? null : body.get("eventType");
        if (docNumber == null || docNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("docNumber required"));
        }
        if (drrNumber == null || drrNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("drrNumber required"));
        }
        Map<String, Object> result = service.replayWriteback(
                docNumber.trim(), drrNumber.trim(),
                eventType == null ? null : eventType.trim(),
                s(session, "email"), s(session, "displayName"));
        return ResponseEntity.ok(result);
    }

    /** Admin/DCC typeahead for the "Edit owners" modal on the admin grid.
     *  Proxies plm-agile-service /api/agile/users/search which filters to
     *  enabled Agile accounts only — matches the user's "only valid in
     *  Agile PLM should show up as replacement options" rule. */
    @GetMapping("/admin/users/search")
    public ResponseEntity<?> adminUserSearch(HttpSession session,
                                             @RequestParam("q") String q,
                                             @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r = writeBackClient.searchUsers(q, limit, null);
        if (!r.ok) return ResponseEntity.status(502).body(err("Agile user search failed: " + r.errorReason));
        return ResponseEntity.ok(r.body);
    }

    /** Admin/DCC saves a new Document Owner roster for a doc — writes back
     *  to Agile via plm-agile-service. The write is irreversible without
     *  going into Agile, so we still gate on hasAdminOrDccAccess (admin OR
     *  explicit ims-review tab grant) — auto-granted DO/DM cannot reach this. */
    @PostMapping("/admin/owner")
    public ResponseEntity<?> adminUpdateOwner(HttpSession session,
                                              @RequestBody Map<String, Object> body) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) {
            return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        }
        String docNumber = body == null ? null : (String) body.get("docNumber");
        Object idsRaw = body == null ? null : body.get("loginIds");
        Object ownersRaw = body == null ? null : body.get("owners");
        if (docNumber == null || docNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("docNumber required"));
        }
        if (!(idsRaw instanceof java.util.List) || ((java.util.List<?>) idsRaw).isEmpty()) {
            return ResponseEntity.badRequest().body(err("loginIds (non-empty list) required"));
        }
        java.util.List<String> loginIds = new java.util.ArrayList<>();
        for (Object o : (java.util.List<?>) idsRaw) {
            if (o != null) loginIds.add(o.toString().trim());
        }
        // Capture the pre-patch owner roster + doc metadata BEFORE the Agile
        // write succeeds so we can diff (added/removed) and populate the
        // reassign email's "Replaced" line with each old owner's LDAP status.
        com.sandisk.plm.tracker.service.ImsReviewService.DocRow snapshot = service.findCachedDoc(docNumber.trim());
        java.util.List<com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef> oldOwnersSnapshot = new java.util.ArrayList<>();
        if (snapshot != null && snapshot.owners != null) {
            for (com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef o : snapshot.owners) {
                com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef copy =
                        new com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef();
                copy.loginId = o.loginId;
                copy.displayName = o.displayName;
                copy.email = o.email;
                copy.ldapStatus = o.ldapStatus;
                oldOwnersSnapshot.add(copy);
            }
        }

        // Build a notedBy string the agile-service will stamp into Agile
        // history via logActionWOVersionChange. Jimmy's chat ask 2026-06-05:
        // "Maybe have action add a comment 'updated via PLM Toolkit' or
        // something that we can latch onto" so the new Owner Change Audit
        // tab can identify toolkit-driven changes (Agile's own actor field
        // shows the SDK service account for every owner change, regardless
        // of who actually triggered it).
        String displayName = s(session, "displayName");
        String username = s(session, "username");
        String notedBy = displayName == null || displayName.isEmpty()
                ? username
                : displayName + (username == null || username.isEmpty() ? "" : " (" + username + ")");

        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r =
                writeBackClient.updateItemOwners(docNumber.trim(), loginIds, notedBy, null);
        if (!r.ok) return ResponseEntity.status(502).body(err("Agile write failed: " + r.errorReason));

        // Patch the cached row in place instead of nuking the cache — otherwise
        // the user pays the full 100+ second Agile rebuild cost on the next
        // /data refresh right after clicking Save. The frontend sends an
        // {owners:[{loginId,displayName,email},...]} array so we can fill the
        // row exactly the way pullDocsDueWithin would have. ownersWithStatus
        // re-runs LDAP-status enrichment on every read, so we don't carry that
        // here. Falls back to bare loginIds if the frontend is on an older
        // build that doesn't send the metadata.
        java.util.List<com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef> patchOwners
                = new java.util.ArrayList<>();
        if (ownersRaw instanceof java.util.List) {
            for (Object o : (java.util.List<?>) ownersRaw) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef ref =
                        new com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef();
                ref.loginId = m.get("loginId") == null ? null : m.get("loginId").toString();
                ref.displayName = m.get("displayName") == null ? null : m.get("displayName").toString();
                ref.email = m.get("email") == null ? null : m.get("email").toString();
                ref.ldapStatus = m.get("ldapStatus") == null ? null : m.get("ldapStatus").toString();
                if (ref.loginId != null) patchOwners.add(ref);
            }
        }
        if (patchOwners.isEmpty()) {
            for (String id : loginIds) {
                com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef ref =
                        new com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef();
                ref.loginId = id;
                patchOwners.add(ref);
            }
        }
        int patched = service.patchOwnersInCache(docNumber.trim(), patchOwners);

        // Fire-and-forget reassign notification — attachment fetch can take a
        // few seconds, so we don't block the modal's Save response on it.
        // Captures the pre-patch snapshot + new owner roster locally so the
        // executor sees consistent values even if the cache moves underneath.
        final String docNum = docNumber.trim();
        final String descriptionFinal = snapshot != null ? snapshot.description : "";
        final String drrFinal = snapshot != null ? snapshot.drrNumber : "";
        final String nextReviewFinal = snapshot != null ? snapshot.nextReviewDate : "";
        final String actorDisplay = nvl((String) session.getAttribute("displayName"));
        final java.util.List<com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef> oldForEmail = oldOwnersSnapshot;
        final java.util.List<com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef> newForEmail = new java.util.ArrayList<>(patchOwners);
        reassignEmailExec.submit(() -> {
            try {
                AgileDocumentAttachmentsClient.Bundle attach = agileAttachments.fetch(docNum);
                byte[] bytes = attach != null && attach.ok ? attach.bytes : null;
                String fname = attach != null && attach.ok ? attach.filename : null;
                long size = attach != null ? attach.totalBytes : 0L;
                java.util.List<com.sandisk.plm.tracker.service.ImsReviewEmailService.OwnerEntry> oldList = new java.util.ArrayList<>();
                for (com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef o : oldForEmail) {
                    oldList.add(new com.sandisk.plm.tracker.service.ImsReviewEmailService.OwnerEntry(
                            o.displayName, o.email, o.loginId, o.ldapStatus));
                }
                java.util.List<com.sandisk.plm.tracker.service.ImsReviewEmailService.OwnerEntry> newList = new java.util.ArrayList<>();
                for (com.sandisk.plm.tracker.service.ImsReviewService.OwnerRef o : newForEmail) {
                    newList.add(new com.sandisk.plm.tracker.service.ImsReviewEmailService.OwnerEntry(
                            o.displayName, o.email, o.loginId, o.ldapStatus));
                }
                imsEmailService.sendOwnerReassignEmail(docNum, descriptionFinal, drrFinal,
                        nextReviewFinal, actorDisplay, oldList, newList, bytes, fname, size);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(ImsReviewController.class.getName())
                        .warning("[IMS-REASSIGN-EMAIL] failed for doc=" + docNum + ": " + e.getMessage());
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("corrId", r.corrId);
        out.put("cachePatched", patched);
        if (r.body != null) out.put("agileResponse", r.body);
        return ResponseEntity.ok(out);
    }

    /** DO/DM clicks one of the response buttons. Multipart so Needs Change / Need Help can include a file.
     *  Admins may pass {@code actAs=<email>} to impersonate the current recipient — useful for
     *  testing the cascade flows (DO → DM email) without juggling multiple AD sessions. */
    @PostMapping(value = "/respond", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> respond(HttpSession session,
                                     @RequestParam("docNumber") String docNumber,
                                     @RequestParam(value = "drrNumber", required = false, defaultValue = "") String drrNumber,
                                     @RequestParam("type") String typeStr,
                                     @RequestParam(value = "note", required = false) String note,
                                     @RequestParam(value = "actAs", required = false) String actAs,
                                     @RequestParam(value = "file", required = false) MultipartFile file) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        ImsReviewQueueStore.EventType type;
        try { type = ImsReviewQueueStore.EventType.valueOf(typeStr); }
        catch (Exception e) { return ResponseEntity.badRequest().body(err("Unknown response type: " + typeStr)); }

        // Admin override — when actAs is present AND session is admin, act as that email.
        String actorEmail = s(session, "email");
        String actorDisplay = s(session, "displayName");
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        if (isAdmin && actAs != null && !actAs.trim().isEmpty()) {
            actorEmail = actAs.trim();
            actorDisplay = actorDisplay + " (admin-act-as)";
        }

        try {
            ImsReviewQueueStore.QueueItem q = service.respond(docNumber, drrNumber, type,
                    actorEmail, actorDisplay, note, file);
            return ResponseEntity.ok(toMap(q));
        } catch (SecurityException sec) {
            return ResponseEntity.status(403).body(err(sec.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    /** Stream the admin view as .xlsx. Gated to admin + explicit ims-review
     *  grant only (auto-granted DO/DM and unauthenticated users get 403). */
    @GetMapping("/export")
    public void export(HttpSession session, HttpServletResponse response,
                       @RequestParam(value = "days", required = false, defaultValue = "30") int days) throws IOException {
        if (!isLoggedIn(session)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"Login required.\"}");
            return;
        }
        if (!hasAdminOrDccAccess(session)) {
            response.setStatus(403);
            response.getWriter().write("{\"error\":\"Admin or explicit IMS Review grant required.\"}");
            return;
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String filename = "IMS-Review-" + date + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        service.writeExcel(response.getOutputStream(), days);
    }

    /** Get Uploaded Attachment — stream the latest DO-uploaded help/needs-change
     *  file for this doc back to an admin/DCC viewer on the dashboard. The
     *  file is what the DO attached on the response page; it was also sent
     *  to DCC by email, but DCC sometimes loses the email and wants the
     *  toolkit copy. Looks up the latest event with {@code uploadFile != null}
     *  in queue history. */
    @GetMapping("/upload")
    public ResponseEntity<byte[]> uploaded(HttpSession session,
                                           @RequestParam("docNumber") String docNumber,
                                           @RequestParam(value = "drrNumber", required = false, defaultValue = "") String drrNumber,
                                           @RequestParam(value = "file", required = false, defaultValue = "") String storedName) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).build();
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).build();
        ImsReviewService.UploadFile u = service.getLatestUpload(docNumber, drrNumber, storedName);
        if (u == null || u.bytes == null) {
            return ResponseEntity.status(404).body(
                    ("{\"error\":\"No uploaded attachment for " + docNumber + "\"}").getBytes());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + u.filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(u.bytes);
    }

    /** Get File — pull attachments via plm-agile-service, stream to client. */
    @GetMapping("/file")
    public ResponseEntity<byte[]> file(HttpSession session,
                                       @RequestParam("docNumber") String docNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).build();
        AgileDocumentAttachmentsClient.Bundle b = service.getFile(docNumber,
                s(session, "email"), s(session, "displayName"));
        if (!b.ok || b.bytes == null) {
            return ResponseEntity.status(404).body(
                    ("{\"error\":\"No attachments available: " + nvl(b.errorReason) + "\"}").getBytes());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + b.filename + "\"")
                .contentType(b.filename.endsWith(".zip") ? MediaType.parseMediaType("application/zip")
                                                         : MediaType.APPLICATION_OCTET_STREAM)
                .body(b.bytes);
    }

    // ------------------------------------------------------------------
    // Token-based public endpoints (no session — token is the auth)
    // ------------------------------------------------------------------

    /** Describe what a one-time response token will let the holder do. The
     *  response page calls this on load to render the doc snapshot + the
     *  pick-your-action UI. Returns 200 with a payload even for already-
     *  redeemed / expired tokens so the page can render a helpful error
     *  state instead of a raw 4xx. */
    @GetMapping("/token/info")
    public ResponseEntity<?> tokenInfo(@RequestParam("token") String token) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", c.valid);
        out.put("errorReason", c.errorReason);
        out.put("alreadyRedeemed", c.alreadyRedeemed);
        out.put("role", c.role);
        out.put("docNumber", c.docNumber);
        out.put("drrNumber", c.drrNumber);
        out.put("description", c.description);
        out.put("rev", c.rev);
        out.put("lifecyclePhase", c.lifecyclePhase);
        out.put("documentType", c.documentType);
        out.put("nextReviewDate", c.nextReviewDate);
        out.put("recipientEmail", c.recipientEmail);
        out.put("allowedActors", c.allowedActors);   // full list for multi-recipient docs
        out.put("allowedActions", c.allowedActions);
        out.put("sentAt", c.sentAt);
        out.put("ownerNames", c.ownerNames);          // Document Owner(s) — landing-page attribute (A74)
        out.put("owners", c.owners);                  // structured owner refs {loginId,displayName,email} for the DCO form pre-fill
        out.put("expiresInMs", c.expiresInMs);
        out.put("dcoFormEnabled", service.isDcoFormEnabled());
        // Agile webclient base — lets the response page hyperlink docNumber /
        // drrNumber straight into Agile (same base the emails use).
        out.put("agileWebclientUrl", imsEmailService.agileWebclientUrl);
        // Self-study training-guide link for the response-page DCO form (gated
        // render in imsreview-dco-form.js — empty string simply hides it).
        out.put("trainingDocUrl", service.getTrainingDocUrl());
        if (c.alreadyRedeemed) {
            out.put("redeemedAction", c.redeemedAction);
            out.put("redeemedBy", c.redeemedBy);
            out.put("redeemedAt", c.redeemedAt);
            out.put("redeemedPdfPath", c.redeemedPdfPath);
        }
        // Live-read the extended "IMS Document Details" panel attributes straight from
        // the Agile schema (agqa on QA / agprod in prod) so the reference panel shows
        // real-time Title Block / More Info / Document Details values instead of "—".
        // This is a pure SQL leg (no agile-service hop) — timed separately so the
        // respond page's latency is attributable at a glance.
        // Fail-soft: an empty map just leaves the panel's placeholders in place.
        if (c.docNumber != null && !c.docNumber.isEmpty()) {
            long dd0 = System.currentTimeMillis();
            Map<String, String> details = docDetails.fetch(c.docNumber);
            long ddMs = System.currentTimeMillis() - dd0;
            out.putAll(details);
            LOG.info("[IMS-TIMING] token/info doc=" + c.docNumber
                    + " docDetailsSqlMs=" + ddMs + " fields=" + details.size());
        }
        return ResponseEntity.ok(out);
    }

    /** Token-scoped attachment download for the response page. Streams the
     *  document's attachments (single file, or a zip when there are several)
     *  to the link recipient — who has no Agile account — and writes a History
     *  entry on the Agile document naming the invoker (recipient + IP) so the
     *  download is audited in Agile's History tab (green-list A73). The
     *  docNumber is derived from the token, so a holder can't pull an arbitrary
     *  document by guessing numbers. */
    @GetMapping("/token/attachments")
    public ResponseEntity<byte[]> tokenAttachments(javax.servlet.http.HttpServletRequest req,
                                                   @RequestParam("token") String token) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        // Allow the download while the link is recognized + not expired. A
        // redeemed token is fine (read-only); only block unknown/expired links
        // (those return without a docNumber).
        if (c == null || c.docNumber == null || c.docNumber.isEmpty()
                || (!c.valid && !c.alreadyRedeemed)) {
            String reason = (c == null) ? "This link is not valid." : nvl(c.errorReason);
            return ResponseEntity.status(410).body(
                    ("{\"error\":\"" + reason + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        AgileDocumentAttachmentsClient.Bundle b = agileAttachments.fetch(c.docNumber);
        if (!b.ok || b.bytes == null) {
            return ResponseEntity.status(404).body(
                    ("{\"error\":\"No attachments are available for " + c.docNumber + ".\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        // Audit: record who downloaded it on the Agile document's History tab.
        // Best-effort — a write-back failure must never break the download.
        service.logAttachmentDownload(c, b.fileCount, b.totalBytes, clientIp(req));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + b.filename + "\"")
                .contentType(b.filename.endsWith(".zip") ? MediaType.parseMediaType("application/zip")
                                                         : MediaType.APPLICATION_OCTET_STREAM)
                .body(b.bytes);
    }

    /** Submit the response. LDAP-binds the user's creds, cross-checks the
     *  verified email matches the SEND recipient, then runs the cascade.
     *  Multipart so Upload / Help can carry a file + note. */
    @PostMapping(value = "/token/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> tokenSubmit(javax.servlet.http.HttpServletRequest req,
                                         @RequestParam("token") String token,
                                         @RequestParam("action") String action,
                                         @RequestParam("username") String username,
                                         @RequestParam("password") String password,
                                         @RequestParam(value = "note", required = false) String note,
                                         @RequestParam(value = "file", required = false)
                                             java.util.List<org.springframework.web.multipart.MultipartFile> files) {
        String ip = clientIp(req);
        ImsReviewService.TokenSubmitResult r = service.respondViaToken(
                token, action, username, password, note, files, ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.success);
        if (!r.success) {
            out.put("error", r.errorReason);
            if (r.redirectMessage != null) out.put("redirectMessage", r.redirectMessage);
            return ResponseEntity.status(r.errorReason != null
                    && r.errorReason.toLowerCase().contains("password") ? 401 : 400).body(out);
        }
        // PDF wiring goes here in Phase 3 — for now, return the event metadata.
        if (r.responseEvent != null) {
            out.put("eventType", r.responseEvent.type.name());
            out.put("eventTs", r.responseEvent.ts);
            out.put("pdfPath", r.responseEvent.pdfPath);   // null until Phase 3
        }
        if (r.verify != null) {
            out.put("signedBy", r.verify.displayName);
            out.put("signedEmail", r.verify.email);
        }
        return ResponseEntity.ok(out);
    }

    private static String clientIp(javax.servlet.http.HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    @Autowired private com.sandisk.plm.tracker.service.ImsReviewPdfService pdfService;

    /** Auditor-facing PDF verification. Pass the SEND event UUID (printed on
     *  the PDF's Provenance line) and the toolkit returns the canonical
     *  record + the on-disk PDF's SHA-256. An auditor downloads the PDF
     *  from Agile, runs <code>shasum -a 256</code>, and compares against
     *  the {@code pdfSha256} we return. If they match, the PDF is the one
     *  the toolkit generated and hasn't been altered. */
    @GetMapping("/verify-pdf")
    public ResponseEntity<?> verifyPdf(@RequestParam("eventUUID") String eventUuid) {
        com.sandisk.plm.tracker.service.ImsReviewQueueStore.QueueItem q =
                queueStore.findQueueItemByToken(eventUuid);
        if (q == null) {
            return ResponseEntity.status(404).body(err("No record found for that event UUID."));
        }
        // Find the PDF_GENERATED event that redeems this token.
        com.sandisk.plm.tracker.service.ImsReviewQueueStore.Event pdfEv = null;
        com.sandisk.plm.tracker.service.ImsReviewQueueStore.Event responseEv = null;
        for (com.sandisk.plm.tracker.service.ImsReviewQueueStore.Event e : q.history) {
            if (!eventUuid.equals(e.redeemsToken)) continue;
            if (e.type == com.sandisk.plm.tracker.service.ImsReviewQueueStore.EventType.PDF_GENERATED) {
                pdfEv = e;
            } else if (responseEv == null
                    && (e.type == com.sandisk.plm.tracker.service.ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE
                     || e.type == com.sandisk.plm.tracker.service.ImsReviewQueueStore.EventType.DM_RESPONSE_APPROVED)) {
                responseEv = e;
            }
        }
        if (pdfEv == null || responseEv == null) {
            return ResponseEntity.status(404).body(err("No signed PDF was generated for that event."));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", true);
        out.put("docNumber", q.docNumber);
        out.put("drrNumber", q.drrNumber);
        out.put("role", responseEv.actor == null ? "" : nvl(responseEv.actor.role));
        out.put("action", responseEv.type.name());
        out.put("signedBy", responseEv.verifiedDisplayName);
        out.put("signedEmail", responseEv.verifiedEmail);
        out.put("signedSamAccount", responseEv.verifiedSamAccount);
        out.put("signedAtUtc", responseEv.ts);
        out.put("signerIp", responseEv.verifyIp);
        out.put("pdfPath", pdfEv.pdfPath);
        out.put("pdfSha256", pdfEv.pdfSha256);
        out.put("howToVerify",
                "Run `shasum -a 256 <pdf>` on the PDF from your DRR and compare against pdfSha256. "
              + "Match = this is the toolkit-generated record; mismatch = the PDF has been altered.");
        return ResponseEntity.ok(out);
    }

    /** Download a stored compliance PDF. Admin-only since the queue log
     *  reveals who signed what. Auditors with the DRR copy just need
     *  {@code /verify-pdf} above; this is for internal review. */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> getPdf(javax.servlet.http.HttpSession session,
                                         @RequestParam("eventUUID") String eventUuid) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).build();
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).build();
        com.sandisk.plm.tracker.service.ImsReviewQueueStore.QueueItem q =
                queueStore.findQueueItemByToken(eventUuid);
        if (q == null) return ResponseEntity.status(404).build();
        for (com.sandisk.plm.tracker.service.ImsReviewQueueStore.Event e : q.history) {
            if (e.type == com.sandisk.plm.tracker.service.ImsReviewQueueStore.EventType.PDF_GENERATED
                    && eventUuid.equals(e.redeemsToken) && e.pdfPath != null) {
                try {
                    byte[] bytes = pdfService.readPdf(e.pdfPath);
                    String fname = e.pdfPath.substring(e.pdfPath.lastIndexOf('/') + 1);
                    return ResponseEntity.ok()
                            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename=\"" + fname + "\"")
                            .contentType(MediaType.APPLICATION_PDF)
                            .body(bytes);
                } catch (RuntimeException re) {
                    return ResponseEntity.status(500).build();
                }
            }
        }
        return ResponseEntity.status(404).build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static Map<String, Object> toMap(ImsReviewQueueStore.QueueItem q) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (q == null) return m;
        m.put("docNumber", q.docNumber);
        m.put("drrNumber", q.drrNumber);
        m.put("status", q.status == null ? "NOT_SENT" : q.status.name());
        m.put("lastUpdated", q.lastUpdated);
        m.put("currentRecipient", nvl(q.currentRecipient));
        m.put("currentRole", nvl(q.currentRole));
        m.put("history", q.history);
        return m;
    }

    private static boolean isLoggedIn(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null && !u.toString().isEmpty();
    }

    /** Admin/DCC = isPlmAdmin OR explicit `ims-review` grant. NOT the auto-grant (that's DO/DM). */
    private static boolean hasAdminOrDccAccess(HttpSession session) {
        Boolean admin = (Boolean) session.getAttribute("isPlmAdmin");
        if (Boolean.TRUE.equals(admin)) return true;
        // Auto-granted users are DO/DM; they shouldn't be hitting admin endpoints.
        if (Boolean.TRUE.equals(session.getAttribute("imsReviewAutoGranted"))) return false;
        @SuppressWarnings("unchecked")
        java.util.List<String> tabs = (java.util.List<String>) session.getAttribute("allowedTabs");
        return tabs != null && tabs.contains("ims-review");
    }

    private static String s(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v == null ? "" : v.toString();
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    // ==================================================================
    // Phase 5 — DCO form endpoints
    // ==================================================================

    /** Pass-through to plm-agile-service /dco/list-values. Token-required.
     *  Lives under /token/ so the toolkit AuthFilter bypasses session auth. */
    /** Cells the DCO form pre-fills from the document: Product Line(s) (1004)
     *  and Subcontractor(s) (1565). Read via /cell-values, NOT the /cells dump. */
    private static final String PREFILL_BASE_IDS = "1004,1565";

    @GetMapping("/token/dco-form-metadata")
    public ResponseEntity<?> dcoFormMetadata(@RequestParam("token") String token) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err(c.errorReason == null ? "Invalid token" : c.errorReason));
        }
        // The two agile-service reads are independent, so fire them together —
        // the drawer waits on max(listValues, cellValues), not the sum. Timings
        // are logged per-leg so a future stall is attributable without guessing.
        final long t0 = System.currentTimeMillis();
        final String doc = c.docNumber;
        final long[] lvMs = new long[1], cvMs = new long[1];

        java.util.concurrent.CompletableFuture<com.sandisk.plm.tracker.service.AgileWriteBackClient.Result> lvF =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                long s = System.currentTimeMillis();
                try { return writeBackClient.listValues(null); }
                finally { lvMs[0] = System.currentTimeMillis() - s; }
            });
        java.util.concurrent.CompletableFuture<com.sandisk.plm.tracker.service.AgileWriteBackClient.Result> cvF =
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                long s = System.currentTimeMillis();
                try { return writeBackClient.itemCellValues(doc, PREFILL_BASE_IDS, null); }
                catch (Exception e) { return null; }          // prefill is optional
                finally { cvMs[0] = System.currentTimeMillis() - s; }
            });

        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r;
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result cells;
        try {
            r = lvF.join();
            cells = cvF.join();
        } catch (Exception joinErr) {
            LOG.warning("[IMS-TIMING] dco-form-metadata doc=" + doc + " FAILED after "
                    + (System.currentTimeMillis() - t0) + "ms: " + joinErr);
            return ResponseEntity.status(503).body(err("Form metadata service unreachable."));
        }
        if (r == null || r.body == null) {
            LOG.warning("[IMS-TIMING] dco-form-metadata doc=" + doc + " listValues unreachable"
                    + " listValuesMs=" + lvMs[0] + " totalMs=" + (System.currentTimeMillis() - t0));
            return ResponseEntity.status(503).body(err("Form metadata service unreachable: "
                    + nvl(r == null ? null : r.errorReason)));
        }
        // Best-effort prefill — never fail the metadata call if the cell read errors.
        try {
            Map<String, Object> prefill = extractScopePrefill(cells == null ? null : cells.body);
            if (prefill != null) r.body.put("docPrefill", prefill);
        } catch (Exception ignore) { /* prefill is optional */ }
        r.body.put("agileWebclientUrl", imsEmailService.agileWebclientUrl);
        LOG.info("[IMS-TIMING] dco-form-metadata doc=" + doc
                + " listValuesMs=" + lvMs[0]
                + " cellValuesMs=" + cvMs[0]
                + " totalMs=" + (System.currentTimeMillis() - t0));
        return ResponseEntity.ok(r.body);
    }

    /** Pull the document's current Product Line(s) (baseId 1004) and
     *  Subcontractor(s) (baseId 1565) out of an item-cells response so the DCO
     *  form can pre-fill them. Values are '; '-joined display strings on this
     *  SDK; we split on ';'. Returns {productLines:[...], subcontractors:[...]}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractScopePrefill(Map<String, Object> cellsBody) {
        if (cellsBody == null) return null;
        Object bodyObj = cellsBody.get("body");
        Map<String, Object> inner = (bodyObj instanceof Map) ? (Map<String, Object>) bodyObj : cellsBody;
        Object cellsObj = inner.get("cells");
        if (!(cellsObj instanceof java.util.List)) return null;
        java.util.List<String> productLines = new java.util.ArrayList<>();
        java.util.List<String> subcontractors = new java.util.ArrayList<>();
        for (Object o : (java.util.List<?>) cellsObj) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> cell = (Map<?, ?>) o;
            String baseId = String.valueOf(cell.get("baseId"));
            String name = String.valueOf(cell.get("name"));
            String value = cell.get("value") == null ? "" : String.valueOf(cell.get("value"));
            boolean isPL  = "1004".equals(baseId) || "Product Line".equalsIgnoreCase(name) || "Product Line(s)".equalsIgnoreCase(name);
            boolean isSub = "1565".equals(baseId) || "Subcontractors".equalsIgnoreCase(name) || "Subcontractor".equalsIgnoreCase(name);
            if (isPL)  for (String v : value.split(";")) { String t = v.trim(); if (!t.isEmpty()) productLines.add(t); }
            else if (isSub) for (String v : value.split(";")) { String t = v.trim(); if (!t.isEmpty()) subcontractors.add(t); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productLines", productLines);
        out.put("subcontractors", subcontractors);
        return out;
    }

    /** Admin-gated, session-auth (NO token) variant of the DCO-form metadata so
     *  admins can render the change-order form in read-only PREVIEW mode
     *  (ims-respond.html?preview=1) to review layout without a live response link.
     *  The form's submit path is disabled client-side in preview, so this only
     *  ever feeds the dropdown lists. */
    @GetMapping("/dco-form-preview-metadata")
    public ResponseEntity<?> dcoFormPreviewMetadata(HttpSession session) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r = writeBackClient.listValues(null);
        if (r.body != null) {
            r.body.put("agileWebclientUrl", imsEmailService.agileWebclientUrl);
            return ResponseEntity.ok(r.body);
        }
        // plm-agile-service unreachable (e.g. local dev) — return canned sample lists
        // so the layout preview still renders. Real lists are used whenever the
        // service is up; this only ever feeds a read-only preview (submit disabled).
        Map<String, Object> lists = new LinkedHashMap<>();
        lists.put("priority", java.util.Arrays.asList("Standard", "Expedite", "Emergency"));
        lists.put("productLines", java.util.Arrays.asList(
                "1000 - CompactFlash Products", "1024 - MicroSD", "1025 - iNAND", "N/A"));
        lists.put("subcontractors", java.util.Arrays.asList("Amkor", "ASE", "JCET"));
        lists.put("trainingRequirement", java.util.Arrays.asList("DL Training", "No Training Required"));
        lists.put("changeImpactDisposition", java.util.Arrays.asList("no", "yes"));
        lists.put("businessUnit", java.util.Arrays.asList("SSD", "SiP", "Support Group"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lists", lists);
        body.put("previewFallback", true);
        body.put("agileWebclientUrl", imsEmailService.agileWebclientUrl);
        return ResponseEntity.ok(body);
    }

    /** Typeahead for People pickers. Token-required.
     *  Lives under /token/ so the toolkit AuthFilter bypasses session auth. */
    @GetMapping("/token/user-search")
    public ResponseEntity<?> userSearch(@RequestParam("token") String token,
                                        @RequestParam("q") String q,
                                        @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err("Invalid token"));
        }
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r = writeBackClient.searchUsers(q, limit, null);
        if (r.body == null) {
            return ResponseEntity.status(503).body(err("User search service unreachable: " + nvl(r.errorReason)));
        }
        return ResponseEntity.ok(r.body);
    }

    /** Live item typeahead for the DCO form's Product field (SDSM IMS
     *  Document — Edit). Token-required; lives under /token/ so the toolkit
     *  AuthFilter bypasses session auth. Returns {hits:[{itemNumber,
     *  description}]} from the agile-service item search. */
    @GetMapping("/token/item-search")
    public ResponseEntity<?> itemSearch(@RequestParam("token") String token,
                                        @RequestParam("q") String q,
                                        @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err("Invalid token"));
        }
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.Collections.emptyMap());
        }
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r = writeBackClient.itemSearch(q, limit, null);
        if (r.body == null) {
            return ResponseEntity.status(503).body(err("Item search service unreachable: " + nvl(r.errorReason)));
        }
        return ResponseEntity.ok(r.body);
    }

    /** Token-gated AD typeahead for the DCO form's "Notify these people on
     *  submit" field. Returns up to {@code limit} sAMAccount/displayName/email
     *  tuples for the query string. Uses {@link LdapAuthService#searchDirectory}
     *  — the same backing path the org-wide /api/permissions/ad-search uses —
     *  but auth is via a valid IMS Review token instead of a session, since
     *  the DCO form is filled via the email link.
     *
     *  <p>Stakeholders to notify don't have to be Agile users (many are DLs or
     *  non-engineering contacts), so we search AD instead of Agile here. */
    @GetMapping("/token/ad-search")
    public ResponseEntity<?> tokenAdSearch(@RequestParam("token") String token,
                                           @RequestParam("q") String q,
                                           @RequestParam(value = "limit", required = false, defaultValue = "15") int limit) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err("Invalid token"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        if (q == null || q.trim().length() < 2) {
            resp.put("results", new ArrayList<>());
            return ResponseEntity.ok(resp);
        }
        java.util.List<com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser> hits =
                ldapAuthService.searchDirectory(q.trim(), Math.max(1, Math.min(limit, 50)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser u : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sAMAccountName", u.username);
            row.put("displayName", u.displayName);
            row.put("email", u.email);
            rows.add(row);
        }
        resp.put("results", rows);
        return ResponseEntity.ok(resp);
    }

    /** Pre-validate the DCO form. Does NOT burn the token. */
    @PostMapping(value = "/token/validate-dco-form", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateDcoForm(@RequestBody Map<String, Object> body) {
        String token = body == null ? null : (String) body.get("token");
        @SuppressWarnings("unchecked")
        Map<String, Object> form = body == null ? null : (Map<String, Object>) body.get("form");
        Map<String, Object> result = service.validateDcoForm(token, form);
        return ResponseEntity.ok(result);
    }

    /** Submit the DCO form. Burns the token + runs the full Agile cascade. */
    @PostMapping(value = "/token/submit-dco", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitDco(javax.servlet.http.HttpServletRequest req,
                                       @RequestParam("token") String token,
                                       @RequestParam("username") String username,
                                       @RequestParam("password") String password,
                                       @RequestParam("form") String formJson,
                                       @RequestParam(value = "file_redline", required = false)
                                           org.springframework.web.multipart.MultipartFile fileRedline,
                                       @RequestParam(value = "file_final", required = false)
                                           org.springframework.web.multipart.MultipartFile fileFinal,
                                       @RequestParam(value = "file_email", required = false)
                                           java.util.List<org.springframework.web.multipart.MultipartFile> filesEmail,
                                       @RequestParam(value = "file_other", required = false)
                                           java.util.List<org.springframework.web.multipart.MultipartFile> filesOther) {
        String ip = clientIp(req);
        ImsReviewService.DcoSubmitResult r = service.respondViaTokenWithDcoForm(
                token, username, password, formJson,
                fileRedline, fileFinal, filesEmail, filesOther,
                ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.success);
        if (!r.success) {
            out.put("error", r.errorReason);
            if (r.fieldErrors != null) out.put("fieldErrors", r.fieldErrors);
            if (r.corrId != null) out.put("corrId", r.corrId);
            int code = 400;
            String low = r.errorReason == null ? "" : r.errorReason.toLowerCase();
            if (low.contains("password") || low.contains("authent")) code = 401;
            if (low.startsWith("dco creation failed")) code = 500;
            return ResponseEntity.status(code).body(out);
        }
        out.put("dcoNumber", r.dcoNumber);
        out.put("newRev", r.newRev);
        out.put("attachmentsCount", r.attachmentsAttached);
        out.put("stakeholdersNotified", r.stakeholdersNotified);
        out.put("signedBy", r.signedBy);
        out.put("signedEmail", r.signedEmail);
        out.put("pdfPath", r.pdfPath);
        out.put("corrId", r.corrId);
        // So the confirmation page can render the created DCO# as a clickable
        // Agile deep-link (dcoAgileChangeLink needs a webclient base URL).
        out.put("agileWebclientUrl", imsEmailService.agileWebclientUrl);
        return ResponseEntity.ok(out);
    }
}
