package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AgileWriteBackClient;
import com.sandisk.plm.tracker.service.ItEnhancementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Backs the IT Enhancements dashboard tab.
 *
 * <p>{@code GET /api/it-enhancements/data} returns the full grid rows + a
 * three-bucket summary mirroring the Excel report's "Overdue & At-Risk" sheet.
 * No per-page server work — the SQL is sub-second against agprod so we render
 * everything client-side and let the user filter.
 *
 * <p>{@code POST /api/it-enhancements/save-cell} writes one cell per call using
 * the caller's cached AD password (stashed on the HttpSession by
 * {@link AuthController#login}). Failures return a per-row outcome so the UI
 * can keep the user's edit in the dirty state on retry.
 *
 * <p>Auth — anyone with the {@code it-enhancements} tab in their
 * {@code allowedTabs} (which is computed in {@link AuthController#getSession})
 * may read. Same gate for write: if the tab isn't granted, the save endpoint
 * 403s before forwarding anything to plm-agile-service.
 */
@RestController
@RequestMapping("/api/it-enhancements")
public class ItEnhancementsController {

    private static final Logger LOG = Logger.getLogger(ItEnhancementsController.class.getName());

    @Autowired private ItEnhancementsService service;
    @Autowired private AgileWriteBackClient agileWriteBackClient;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private com.sandisk.plm.tracker.service.UserPermissionsService userPermissionsService;
    @Autowired private com.sandisk.plm.tracker.service.DraftStorageService draftStorage;
    @Autowired private com.sandisk.plm.tracker.service.ItEnhancementsNotificationService notifySvc;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnService restartEcnService;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnProposalBuilder proposalBuilder;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnAttachmentConfig restartAttachmentConfig;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnHistoryStore restartHistory;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnNotificationService restartNotify;

    /** Toolkit-side mirror of the agile-service's {@code ALLOWED_CELLS} set.
     *  Defense-in-depth + fail-fast: the agile-service is the gatekeeper, but
     *  rejecting an unknown cellName here saves a network round-trip and gives
     *  the UI a sharper error before the Agile session is opened.
     *
     *  <p>Must stay in sync with
     *  {@code PerUserChangeUpdateController.ALLOWED_CELLS} in plm-agile-service.
     *  Verified against ECN-128313-PROJ on 2026-06-12 (see the cell-name table
     *  in {@code docs/superpowers/plans/2026-06-05-it-enhancements-editable-grid.md}). */
    private static final java.util.Set<String> EDITABLE_CELLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "Page Three.Target UAT Date",
            "Page Three.Target Go Live Date",
            "Page Three.Effort  (person-hours)",   // double space is intentional — verified attribute name
            "Page Three.IT Log",
            "Page Three.Assigned IT Owner",
            "Page Three.Project",
            "Page Three.IT Actions Taken"
    ));

    @GetMapping("/data")
    public ResponseEntity<?> data(HttpSession session) {
        if (!authorized(session)) return forbidden();
        long t0 = System.currentTimeMillis();
        List<ItEnhancementsService.Row> rows = service.readAll();
        return ResponseEntity.ok(buildDataResponse(rows, service.getCachedAt(), t0));
    }

    /** Force-refresh — re-pulls from agprod, replaces the JSON snapshot, returns
     *  the new data. Same auth as /data (any IT-Enhancements user). The UI's
     *  "Refresh now" button calls this; the hourly @Scheduled rebuild does
     *  not go through this path. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpSession session) {
        if (!authorized(session)) return forbidden();
        long t0 = System.currentTimeMillis();
        List<ItEnhancementsService.Row> rows;
        try {
            rows = service.refreshNow();
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Refresh failed: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
        String username = (String) session.getAttribute("username");
        activityLogger.log(username, (String) session.getAttribute("displayName"),
                "IT_ENH_REFRESH", "Forced refresh — " + rows.size() + " rows");
        return ResponseEntity.ok(buildDataResponse(rows, service.getCachedAt(), t0));
    }

    private Map<String, Object> buildDataResponse(List<ItEnhancementsService.Row> rows,
                                                  long cachedAt, long t0) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rows", rows);
        resp.put("rowCount", rows.size());
        resp.put("loadedAt", System.currentTimeMillis());
        resp.put("cachedAt", cachedAt);
        // Three-bucket counts — the bands shown at the top of the panel
        int overdueUat = 0, overdueGoLive = 0, approaching = 0;
        // Blank-date counts — drive the filter pills
        int blankUat = 0, blankGoLive = 0, blankSubmit = 0, blankRelease = 0;
        for (ItEnhancementsService.Row r : rows) {
            if ("overdue_uat".equals(r._kind)) overdueUat++;
            else if ("overdue_golive".equals(r._kind)) overdueGoLive++;
            else if ("approaching_golive".equals(r._kind)) approaching++;
            if (isBlank(r.targetUAT)) blankUat++;
            if (isBlank(r.targetGoLive)) blankGoLive++;
            if (isBlank(r.submitDate)) blankSubmit++;
            if (isBlank(r.releaseDate)) blankRelease++;
        }
        Map<String, Integer> bands = new LinkedHashMap<>();
        bands.put("overdueUat", overdueUat);
        bands.put("overdueGoLive", overdueGoLive);
        bands.put("approachingGoLive", approaching);
        resp.put("bands", bands);
        Map<String, Integer> blanks = new LinkedHashMap<>();
        blanks.put("targetUAT", blankUat);
        blanks.put("targetGoLive", blankGoLive);
        blanks.put("submitDate", blankSubmit);
        blanks.put("releaseDate", blankRelease);
        resp.put("blanks", blanks);
        // PT-IT-ENH: Agile-defined dropdown values for the editable LIST
        // cells (Assigned IT Owner / Project / IT Actions Taken). Cached on
        // the service side for 6h. Returns {} on any failure; the frontend
        // falls back to its compute-from-rows lists in that case.
        try {
            resp.put("cellOptions", service.getCellOptions());
        } catch (Exception cellsErr) {
            LOG.warning("[IT-ENH] cellOptions fetch threw on /data: " + cellsErr.getMessage());
        }
        LOG.info("[IT-ENH] served " + rows.size() + " rows in "
                + (System.currentTimeMillis() - t0) + " ms (overdueUat=" + overdueUat
                + ", overdueGoLive=" + overdueGoLive + ", approaching=" + approaching
                + ", blankUat=" + blankUat + ", blankGoLive=" + blankGoLive + ")");
        return resp;
    }

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }

    /** Body: {@code {"ecn":"ECN-…","cellName":"Page Three.Target UAT Date","value":"yyyy-MM-dd" | null}}.
     *  Server fills asUsername/asPassword from the session. Always returns
     *  HTTP 200 with a flat result map; the {@code ok} field drives the UI
     *  pending → success / error swap.
     *
     *  <p>When the session has no Agile password cached (first save in the
     *  session, OR the previously-cached password was rejected), responds with
     *  {@code {ok:false, needsAgileSignin:true}} so the UI can pop its
     *  sign-in modal and retry. */
    @PostMapping("/save-cell")
    public ResponseEntity<?> saveCell(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        String ecn = body == null ? null : (String) body.get("ecn");
        String cellName = body == null ? null : (String) body.get("cellName");
        String value = body == null ? null : (body.get("value") == null ? null : body.get("value").toString());
        resp.put("ecn", ecn);
        resp.put("cellName", cellName);

        if (ecn == null || ecn.trim().isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "ecn required");
            return ResponseEntity.ok(resp);
        }
        if (cellName == null || cellName.trim().isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "cellName required");
            return ResponseEntity.ok(resp);
        }
        if (!EDITABLE_CELLS.contains(cellName.trim())) {
            resp.put("ok", false);
            resp.put("error", "cellName not on the editable allowlist: " + cellName);
            return ResponseEntity.ok(resp);
        }
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false);
            resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to save (per-user write-back).");
            return ResponseEntity.ok(resp);
        }

        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBackClient.updateChangeCellAsUser(
                ecn.trim(), username, agilePassword, cellName.trim(), value, corrId);
        resp.put("ok", r.ok);
        resp.put("corrId", corrId);
        if (r.ok) {
            // Echo what the server says it wrote so the UI can confirm the
            // SDK saw the same value we sent (and pick up timezone normalization).
            if (r.body != null) {
                Object oldV = r.body.get("oldValue");
                Object newV = r.body.get("newValue");
                if (oldV != null) resp.put("oldValue", oldV);
                if (newV != null) resp.put("newValue", newV);
            }
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                    "IT_ENH_WRITE", ecn + " · " + cellName + " → " + value);
        } else {
            String err = r.errorReason == null ? "save failed" : r.errorReason;
            resp.put("error", err);
            // Detect "Agile rejected the cached password" so the UI can re-prompt.
            // The SDK errorCode is 60062 ("Invalid username or password"); when
            // it fires, we drop the cached password to force a re-signin.
            if (isInvalidCredsError(err)) {
                session.removeAttribute("agilePassword");
                resp.put("needsAgileSignin", true);
            }
        }
        return ResponseEntity.ok(resp);
    }

    /** Convenience batch endpoint — accepts {@code {"edits":[{ecn,cellName,value},...]}}
     *  and runs them sequentially, returning a per-row results array. Lets the
     *  UI use a single "Save all" button when the user has dirtied several
     *  rows. Each row gets its own corrId, so we can correlate one bad row
     *  in the log. */
    @PostMapping("/save-batch")
    public ResponseEntity<?> saveBatch(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        resp.put("results", results);

        if (body == null || !(body.get("edits") instanceof List)) {
            resp.put("ok", false);
            resp.put("error", "edits array required");
            return ResponseEntity.ok(resp);
        }
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false);
            resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to save (per-user write-back).");
            return ResponseEntity.ok(resp);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) body.get("edits");
        int oks = 0, fails = 0;
        boolean credsRejected = false;
        for (Map<String, Object> e : edits) {
            String ecn = (String) e.get("ecn");
            String cellName = (String) e.get("cellName");
            String value = e.get("value") == null ? null : e.get("value").toString();
            Map<String, Object> rowResp = new LinkedHashMap<>();
            rowResp.put("ecn", ecn);
            rowResp.put("cellName", cellName);
            if (ecn == null || cellName == null) {
                rowResp.put("ok", false);
                rowResp.put("error", "ecn + cellName required");
                fails++;
                results.add(rowResp);
                continue;
            }
            if (!EDITABLE_CELLS.contains(cellName.trim())) {
                rowResp.put("ok", false);
                rowResp.put("error", "cellName not on the editable allowlist: " + cellName);
                fails++;
                results.add(rowResp);
                continue;
            }
            String corrId = UUID.randomUUID().toString();
            AgileWriteBackClient.Result r = agileWriteBackClient.updateChangeCellAsUser(
                    ecn.trim(), username, agilePassword, cellName.trim(), value, corrId);
            rowResp.put("ok", r.ok);
            rowResp.put("corrId", corrId);
            if (r.ok) {
                if (r.body != null) {
                    Object oldV = r.body.get("oldValue");
                    Object newV = r.body.get("newValue");
                    if (oldV != null) rowResp.put("oldValue", oldV);
                    if (newV != null) rowResp.put("newValue", newV);
                }
                oks++;
                activityLogger.log(username, (String) session.getAttribute("displayName"),
                        "IT_ENH_WRITE", ecn + " · " + cellName + " → " + value);
            } else {
                String err = r.errorReason == null ? "save failed" : r.errorReason;
                rowResp.put("error", err);
                fails++;
                if (isInvalidCredsError(err)) {
                    credsRejected = true;
                    // No point continuing with the same bad password — fail the
                    // remaining edits with the same error so the UI tells the
                    // user once and pops the re-signin modal.
                    break;
                }
            }
            results.add(rowResp);
        }
        if (credsRejected) {
            session.removeAttribute("agilePassword");
            resp.put("needsAgileSignin", true);
        }
        resp.put("ok", fails == 0);
        resp.put("savedCount", oks);
        resp.put("failedCount", fails);
        // Auto-discard the user's Meeting Mode draft only when EVERY edit
        // landed in Agile. A partial-success leaves the draft in place so
        // the user can retry the failed entries from their recovered state.
        if (fails == 0 && oks > 0) {
            draftStorage.delete(username);
        }
        return ResponseEntity.ok(resp);
    }

    /** Sign in to Agile (modal handler). Body: {@code {"password":"…"}}.
     *  Username is taken from the session (the toolkit-login sAMAccount, which
     *  for the Agile users in scope here is also their Agile loginid). Calls
     *  the agile-service verify endpoint, stashes the password on session if
     *  it works, returns {@code {ok:true}}.
     *
     *  <p>The password lives on HttpSession in JVM heap only — same lifetime
     *  rules as the AD-password plan: never logged, never persisted to disk,
     *  freed on logout. */
    // ------------------------------------------------------------------
    // Meeting Mode draft persistence — per-user autosave of pending edits
    // so the user can recover them on next login. After a successful save
    // batch the draft is deleted automatically (the next session starts
    // from the latest Agile state).
    // ------------------------------------------------------------------

    @GetMapping("/draft")
    public ResponseEntity<?> getDraft(HttpSession session) {
        if (!authorized(session)) return forbidden();
        String username = (String) session.getAttribute("username");
        if (username == null || username.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Collections.singletonMap("error", "Not logged in."));
        }
        java.util.Map<String, Object> draft = draftStorage.read(username);
        if (draft == null) {
            return ResponseEntity.status(404).body(java.util.Collections.singletonMap("error", "No draft."));
        }
        return ResponseEntity.ok(draft);
    }

    @PostMapping("/draft")
    public ResponseEntity<?> putDraft(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        String username = (String) session.getAttribute("username");
        if (username == null || username.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Collections.singletonMap("error", "Not logged in."));
        }
        String displayName = (String) session.getAttribute("displayName");
        draftStorage.write(username, displayName, body);
        return ResponseEntity.ok(java.util.Collections.singletonMap("ok", true));
    }

    @DeleteMapping("/draft")
    public ResponseEntity<?> deleteDraft(HttpSession session) {
        if (!authorized(session)) return forbidden();
        String username = (String) session.getAttribute("username");
        if (username == null || username.isEmpty()) {
            return ResponseEntity.status(401).body(java.util.Collections.singletonMap("error", "Not logged in."));
        }
        draftStorage.delete(username);
        return ResponseEntity.ok(java.util.Collections.singletonMap("ok", true));
    }

    /** On-demand structured IT Log history for a single ECN. The legacy
     *  {@code /data} response only carries the plain-text IT Log (HTML
     *  stripped server-side), so the Meeting Mode focus card can't show
     *  "Latest update by Kuntal" for entries written directly in Agile.
     *  This endpoint fetches the raw HTML CLOB on demand and returns
     *  {author, loginId, timestamp, date, body} per entry, most recent first.
     *  Cached client-side per ECN for the session — the toolkit hits this
     *  once per focused ECN. */
    @GetMapping("/it-log-history")
    public ResponseEntity<?> itLogHistory(@RequestParam("ecn") String ecn, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (ecn == null || ecn.trim().isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "ecn query parameter required.");
            return ResponseEntity.badRequest().body(err);
        }
        List<ItEnhancementsService.ItLogEntry> entries = service.fetchItLogHistory(ecn.trim());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ecn", ecn.trim());
        resp.put("entries", entries);
        return ResponseEntity.ok(resp);
    }

    /** Fire per-ECN notification emails after a Meeting Mode save batch.
     *  Frontend calls this from the wrap-up Save button's success handler.
     *  Body shape:
     *  <pre>{
     *     edits: [
     *       { ecn, requestorLoginId, requestorName, itOwnerLoginId, itOwnerName,
     *         changes: [{field, old, new}], note: "..." }
     *     ],
     *     failed: [{ ecn, cell, error }],
     *     userDisplayName: "Vikas Jindal"
     *  }</pre>
     *  Each {@code edits} item triggers one email to requestor + IT owner
     *  with admin on cc. Each {@code failed} item triggers one admin-only
     *  failure summary email. Returns {ok, sent, notifyErrors:[…]}. */
    @PostMapping("/notify-changes")
    public ResponseEntity<?> notifyChanges(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        if (body == null) {
            resp.put("ok", false);
            resp.put("error", "request body required");
            return ResponseEntity.badRequest().body(resp);
        }
        String editor = (String) session.getAttribute("displayName");
        if (editor == null) editor = (String) body.get("userDisplayName");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) body.getOrDefault("edits", new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) body.getOrDefault("failed", new ArrayList<>());

        // To/Cc split based on the editor's team. When the editor is on the
        // IT team (PDL-PLM-admin DL = the toolkit's isPlmAdmin flag), the
        // assigned IT Owner gets demoted to Cc and the Requestor is the
        // primary To: recipient. When the editor is a Requestor (non-IT),
        // the opposite — IT Owner to To:, Requestor to Cc. Krati's
        // 2026-06-16 feedback: "if Assigned IT owner updates, in TO we send
        // to Requestor n IT owner in CC; if Requestor updates, IT Owner in
        // TO n Requestor in CC".
        boolean editorIsItTeam = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));

        List<com.sandisk.plm.tracker.service.ItEnhancementsNotificationService.NotifyResult> outcomes
                = notifySvc.sendChangeNotifications(edits, editor, editorIsItTeam);

        int sent = 0;
        List<String> notifyErrors = new ArrayList<>();
        List<Map<String, Object>> perRecipientResults = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.ItEnhancementsNotificationService.NotifyResult r : outcomes) {
            Map<String, Object> rowOut = new LinkedHashMap<>();
            rowOut.put("recipientEmail", r.recipientEmail);
            rowOut.put("recipientName", r.recipientName);
            rowOut.put("ecns", r.ecns);
            rowOut.put("sent", r.sent);
            if (r.error != null) rowOut.put("error", r.error);
            perRecipientResults.add(rowOut);
            if (r.sent) {
                sent++;
                activityLogger.log((String) session.getAttribute("username"), editor,
                        "IT_ENH_NOTIFY", "summary → " + r.recipientEmail + " · "
                                + r.ecns.size() + " ECN(s)");
            } else {
                notifyErrors.add((r.recipientName == null ? "(unresolved)" : r.recipientName)
                        + ": " + (r.error == null ? "(unknown)" : r.error));
                activityLogger.log((String) session.getAttribute("username"), editor,
                        "IT_ENH_NOTIFY_FAILED", (r.recipientName == null ? "" : r.recipientName)
                                + " · " + (r.error == null ? "" : r.error));
            }
        }

        if ((failed != null && !failed.isEmpty()) || !notifyErrors.isEmpty()) {
            notifySvc.sendFailureNotification(failed, notifyErrors, editor);
        }

        resp.put("ok", notifyErrors.isEmpty() && (failed == null || failed.isEmpty()));
        resp.put("sent", sent);
        resp.put("notifyErrors", notifyErrors);
        resp.put("results", perRecipientResults);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/agile-signin")
    public ResponseEntity<?> agileSignin(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        if (username == null || username.isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "Not logged in.");
            return ResponseEntity.ok(resp);
        }
        String password = body == null ? null : (String) body.get("password");
        if (password == null || password.isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "Password required.");
            return ResponseEntity.ok(resp);
        }
        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBackClient.verifyUserCredentials(
                username, password, corrId);
        if (r.ok) {
            session.setAttribute("agilePassword", password);
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                    "AGILE_SIGNIN", "Verified Agile credentials for per-user write-back");
            resp.put("ok", true);
            resp.put("username", username);
        } else {
            resp.put("ok", false);
            // Surface the SDK's error verbatim when it's the friendly
            // "Invalid username or password"; otherwise generic message.
            String err = r.errorReason == null ? "" : r.errorReason;
            if (isInvalidCredsError(err)) {
                resp.put("error", "Agile rejected those credentials (loginid " + username + ").");
            } else {
                resp.put("error", "Verify failed: " + err);
            }
        }
        return ResponseEntity.ok(resp);
    }

    /** Eligible enhancement ECNs (IT Status = eligible value) + the IT-owner roster
     *  for the Restart ECN dialog. The roster is derived from the distinct IT owners
     *  across the cached rows (the assignable "PLM IT team"), NOT from the Agile
     *  cell-options lookup — that lookup hits plm-agile-service and hangs ~60s /
     *  returns empty when the service is down, which left the dialog with no owners. */
    @GetMapping("/restart-ecn-candidates")
    public ResponseEntity<?> restartEcnCandidates(HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        List<ItEnhancementsService.Row> rows = service.readAll();
        List<com.sandisk.plm.tracker.service.RestartEcnService.Candidate> cands =
                restartEcnService.candidates(rows);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.sandisk.plm.tracker.service.RestartEcnService.Candidate c : cands) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ecn", c.ecn); m.put("proposal", c.proposal);
            m.put("itOwner", c.itOwner); m.put("itOwnerLoginId", c.itOwnerLoginId);
            m.put("workflowStatus", c.workflowStatus);
            out.add(m);
        }
        resp.put("candidates", out);
        List<Map<String, Object>> rosterOut = new java.util.ArrayList<>();
        for (com.sandisk.plm.tracker.service.RestartEcnService.ItOwner o : restartEcnService.itOwnerRoster(rows)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("login", o.login); m.put("name", o.name);
            rosterOut.add(m);
        }
        resp.put("itOwnerRoster", rosterOut);
        // Attachment config for the dialog: ext -> allowed IT Attachment Type values + size cap.
        resp.put("attachmentTypes", restartAttachmentConfig.typeOptions());
        resp.put("attachmentMaxMb", restartAttachmentConfig.getAttachmentMaxMb());
        return ResponseEntity.ok(resp);
    }

    /** Upload one or more files to a Restart ECN's Attachments tab (create-time and
     *  §4 collaboration). Multipart: {@code files} parts + a {@code meta} JSON array
     *  aligned by index — each {@code {ecns:[…], type, note}}. Per-file description is
     *  built ECN-list-first; per-user Agile creds with the needsAgileSignin gate. */
    @PostMapping(value = "/restart-ecn/{ecn}/add-files", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> restartEcnAddFiles(
            @PathVariable("ecn") String ecn,
            @org.springframework.web.bind.annotation.RequestParam("files") org.springframework.web.multipart.MultipartFile[] files,
            @org.springframework.web.bind.annotation.RequestParam(value = "meta", required = false) String metaJson,
            HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ecn", ecn);
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false); resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to upload files.");
            return ResponseEntity.ok(resp);
        }
        String byName = (String) session.getAttribute("displayName");
        if (byName == null || byName.isEmpty()) byName = username;
        List<Map<String, Object>> meta;
        try {
            meta = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    metaJson == null || metaJson.trim().isEmpty() ? "[]" : metaJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            resp.put("ok", false); resp.put("error", "bad meta json: " + e.getMessage());
            return ResponseEntity.ok(resp);
        }
        List<String> filesOk = new java.util.ArrayList<>();
        List<Map<String, String>> filesFailed = new java.util.ArrayList<>();
        List<com.sandisk.plm.tracker.service.RestartEcnHistoryStore.FileEntry> addedEntries = new java.util.ArrayList<>();
        String corrId = UUID.randomUUID().toString();
        for (int i = 0; files != null && i < files.length; i++) {
            org.springframework.web.multipart.MultipartFile f = files[i];
            String fname = f.getOriginalFilename();
            Map<String, Object> m = i < meta.size() ? meta.get(i) : java.util.Collections.emptyMap();
            @SuppressWarnings("unchecked")
            List<String> ecns = m.get("ecns") instanceof List ? (List<String>) (List<?>) m.get("ecns") : java.util.Collections.emptyList();
            String type = m.get("type") == null ? null : String.valueOf(m.get("type"));
            String note = m.get("note") == null ? null : String.valueOf(m.get("note"));
            String description = com.sandisk.plm.tracker.service.RestartEcnService.buildFileDescription(ecns, note, fname);
            try {
                AgileWriteBackClient.Result r = agileWriteBackClient.addRestartAttachment(
                        ecn, username, agilePassword, f.getBytes(), fname, description, type, corrId);
                if (r.ok) {
                    filesOk.add(fname);
                    com.sandisk.plm.tracker.service.RestartEcnHistoryStore.FileEntry fe =
                            new com.sandisk.plm.tracker.service.RestartEcnHistoryStore.FileEntry();
                    fe.name = fname; fe.size = f.getSize(); fe.ecns = new java.util.ArrayList<>(ecns);
                    fe.type = type; fe.by = byName; fe.at = com.sandisk.plm.tracker.service.RestartEcnHistoryStore.nowIso();
                    addedEntries.add(fe);
                } else {
                    String err = r.errorReason != null ? r.errorReason
                            : (r.body != null && r.body.get("errorReason") != null ? r.body.get("errorReason").toString() : "upload failed");
                    Map<String, String> ff = new LinkedHashMap<>(); ff.put("name", fname); ff.put("reason", err); filesFailed.add(ff);
                    if (isInvalidCredsError(err)) { session.removeAttribute("agilePassword"); resp.put("needsAgileSignin", true); }
                }
            } catch (Exception ex) {
                Map<String, String> ff = new LinkedHashMap<>(); ff.put("name", fname); ff.put("reason", ex.getMessage()); filesFailed.add(ff);
            }
        }
        resp.put("ok", filesFailed.isEmpty());
        resp.put("filesOk", filesOk);
        resp.put("filesFailed", filesFailed);
        resp.put("corrId", corrId);
        activityLogger.log(username, (String) session.getAttribute("displayName"),
                "RESTART_ECN_ADD_FILES", ecn + " · " + filesOk.size() + " ok, " + filesFailed.size() + " failed");
        // Record to Restart ECN history (fail-soft).
        if (!addedEntries.isEmpty()) {
            try {
                com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record rec = restartHistory.get(ecn);
                if (rec != null) {
                    rec.files.addAll(addedEntries);
                    restartHistory.addActivity(rec, "files-added",
                            addedEntries.size() + " file(s) added: " + String.join(", ", filesOk), byName);
                    restartHistory.save(rec);
                    String byD = (String) session.getAttribute("displayName"); if (byD == null || byD.isEmpty()) byD = username;
                    if (restartNotify.sendUpdated(rec, filesOk.size() + " file(s) added", byD, java.util.Collections.<String>emptySet(), new java.util.HashSet<>(filesOk))) {
                        restartHistory.addActivity(rec, "notify", "Emailed notify list (files added)", "system");
                        restartHistory.save(rec);
                    }
                }
            } catch (Exception histErr) { LOG.warning("[RESTART-HIST] add-files record failed: " + histErr.getMessage()); }
        }
        return ResponseEntity.ok(resp);
    }

    /** Restart ECN history list (newest first) — summary rows for the drawer. */
    @GetMapping("/restart-ecn/history")
    public ResponseEntity<?> restartEcnHistory(HttpSession session) {
        if (!authorized(session)) return forbidden();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record r : restartHistory.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ecn", r.ecn); m.put("status", r.status); m.put("deployDate", r.deployDate);
            m.put("itOwnerLogin", r.itOwnerLogin); m.put("createdBy", r.createdBy); m.put("createdAt", r.createdAt);
            m.put("ecnCount", r.bundled.size()); m.put("fileCount", r.files.size());
            out.add(m);
        }
        // Live-refresh each row's status from Agile (one batch call; fail-soft to stored).
        try {
            List<String> ecns = new java.util.ArrayList<>();
            for (Map<String, Object> m : out) ecns.add((String) m.get("ecn"));
            if (!ecns.isEmpty()) {
                AgileWriteBackClient.Result sr = agileWriteBackClient.restartStatuses(ecns, UUID.randomUUID().toString());
                if (sr.ok && sr.body != null && sr.body.get("statuses") instanceof Map) {
                    Map<?, ?> st = (Map<?, ?>) sr.body.get("statuses");
                    for (Map<String, Object> m : out) {
                        Object live = st.get(m.get("ecn"));
                        String ls = live == null ? "" : String.valueOf(live).trim();
                        if (!ls.isEmpty()) {
                            boolean dep = ls.matches("(?i)\\s*(released|release|completed|complete|implemented)\\s*");
                            m.put("status", dep ? "Deployed" : ls);
                            m.put("deployed", dep);
                        }
                    }
                }
            }
        } catch (Exception e) { LOG.warning("[RESTART-HIST] list status refresh failed: " + e.getMessage()); }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true); resp.put("history", out); resp.put("count", out.size());
        return ResponseEntity.ok(resp);
    }

    /** Full Restart ECN detail (also the deep-link target). Merges a LIVE Agile
     *  snapshot (status / IT owner / relationships / attachments) over the stored
     *  history — so direct edits in Agile are reflected — while keeping our activity
     *  feed. A Released/Completed ECN is surfaced as "Deployed" (read-only in the UI). */
    @GetMapping("/restart-ecn/{ecn}")
    public ResponseEntity<?> restartEcnDetail(@PathVariable("ecn") String ecn, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record r = restartHistory.get(ecn);
        if (r == null) { resp.put("ok", false); resp.put("error", "not found: " + ecn); return ResponseEntity.status(404).body(resp); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ecn", r.ecn); out.put("deployDate", r.deployDate); out.put("itOwnerLogin", r.itOwnerLogin);
        out.put("createdBy", r.createdBy); out.put("createdAt", r.createdAt); out.put("activity", r.activity);
        out.put("status", r.status); out.put("itOwnerName", r.itOwnerName);
        out.put("bundled", r.bundled); out.put("files", r.files); out.put("deployed", false);
        try {
            AgileWriteBackClient.Result sr = agileWriteBackClient.restartSnapshot(ecn, UUID.randomUUID().toString());
            if (sr.ok && sr.body != null) {
                String liveStatus = asStr(sr.body.get("status"));
                if (!liveStatus.isEmpty()) {
                    boolean deployed = liveStatus.matches("(?i)\\s*(released|release|completed|complete|implemented)\\s*");
                    out.put("deployed", deployed);
                    out.put("status", deployed ? "Deployed" : liveStatus);
                }
                String liveOwner = asStr(sr.body.get("itOwner"));
                if (!liveOwner.isEmpty()) out.put("itOwnerName", liveOwner);
                Object relRaw = sr.body.get("relationships");
                if (relRaw instanceof List) {
                    List<ItEnhancementsService.Row> rows = service.readAll();
                    Map<String, ItEnhancementsService.Row> byEcn = new java.util.HashMap<>();
                    for (ItEnhancementsService.Row row : rows) byEcn.put(row.ecnNumber, row);
                    Map<String, com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn> hist = new java.util.HashMap<>();
                    for (com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn b : r.bundled) hist.put(b.ecn, b);
                    List<Map<String, Object>> bundledOut = new java.util.ArrayList<>();
                    for (Object o : (List<?>) relRaw) {
                        String be = asStr(o); if (be.isEmpty()) continue;
                        com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn hb = hist.get(be);
                        ItEnhancementsService.Row row = byEcn.get(be);
                        Map<String, Object> bm = new LinkedHashMap<>();
                        bm.put("ecn", be);
                        bm.put("proposal", hb != null ? hb.proposal : (row != null ? row.proposal : ""));
                        bm.put("itOwner", hb != null ? hb.itOwner : (row != null ? row.itOwner : ""));
                        bundledOut.add(bm);
                    }
                    out.put("bundled", bundledOut);
                }
                Object attRaw = sr.body.get("attachments");
                if (attRaw instanceof List) {
                    Map<String, String> uploaderByName = new java.util.HashMap<>();
                    for (com.sandisk.plm.tracker.service.RestartEcnHistoryStore.FileEntry fe : r.files) uploaderByName.put(fe.name, fe.by);
                    List<Map<String, Object>> filesOut = new java.util.ArrayList<>();
                    for (Object o : (List<?>) attRaw) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> a = (Map<?, ?>) o;
                        String nm = asStr(a.get("name"));
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("name", nm);
                        fm.put("size", asStr(a.get("size")));
                        fm.put("type", asStr(a.get("type")));
                        fm.put("ecns", parseEcnsFromDesc(asStr(a.get("description"))));
                        fm.put("by", uploaderByName.getOrDefault(nm, ""));
                        filesOut.add(fm);
                    }
                    out.put("files", filesOut);
                }
            }
        } catch (Exception e) { LOG.warning("[RESTART-HIST] snapshot refresh failed for " + ecn + ": " + e.getMessage()); }
        resp.put("ok", true); resp.put("record", out);
        return ResponseEntity.ok(resp);
    }
    private static String asStr(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static List<String> parseEcnsFromDesc(String desc) {
        List<String> out = new java.util.ArrayList<>();
        if (desc == null || desc.isEmpty()) return out;
        int dash = desc.indexOf('—');            // em dash separates ECN list from note
        String head = dash >= 0 ? desc.substring(0, dash) : desc;
        for (String tok : head.split(",")) { String t = tok.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /** Add eligible enhancement ECNs to an existing Restart ECN's Relationships (§4).
     *  Re-validates eligibility + separation-of-duties (against the ECN's IT owner),
     *  skips already-bundled, per-user Agile write, updates history. */
    @PostMapping("/restart-ecn/{ecn}/add-ecns")
    public ResponseEntity<?> restartEcnAddEcns(@PathVariable("ecn") String ecn,
                                               @RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>(); resp.put("ecn", ecn);
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false); resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to add ECNs."); return ResponseEntity.ok(resp);
        }
        java.util.List<String> requested = new java.util.ArrayList<>();
        Object raw = body == null ? null : body.get("ecns");
        if (raw instanceof List) for (Object o : (List<?>) raw) if (o != null) requested.add(String.valueOf(o).trim());

        com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record rec = restartHistory.get(ecn);
        java.util.Set<String> already = new java.util.HashSet<>();
        if (rec != null) for (com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn b : rec.bundled) already.add(b.ecn);
        java.util.List<String> toAdd = new java.util.ArrayList<>();
        for (String e : requested) if (!already.contains(e)) toAdd.add(e);

        List<ItEnhancementsService.Row> rows = service.readAll();
        String ownerLogin = rec != null ? rec.itOwnerLogin : null;
        com.sandisk.plm.tracker.service.RestartEcnService.Validated v =
                restartEcnService.revalidate(rows, toAdd, ownerLogin == null ? "__none__" : ownerLogin);
        if (!v.ok) { resp.put("ok", false); resp.put("error", v.error); resp.put("droppedEcns", v.droppedEcns); return ResponseEntity.ok(resp); }

        // Rebuild Proposal + Problem Statement for the FULL bundle (existing + newly added)
        // so the cover page reflects every deployed ECN. Fail-soft — a build hiccup just
        // leaves the text as-is (relationships still get added).
        String newProposal = null, newProblem = null;
        try {
            if (rec != null && rec.deployDate != null && !rec.deployDate.trim().isEmpty()) {
                java.util.LinkedHashMap<String, String> descs = new java.util.LinkedHashMap<>();
                for (com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn b : rec.bundled) descs.put(b.ecn, b.proposal);
                Map<String, ItEnhancementsService.Row> byEcn2 = new java.util.HashMap<>();
                for (ItEnhancementsService.Row row : rows) byEcn2.put(row.ecnNumber, row);
                for (String e : v.acceptedEcns) { ItEnhancementsService.Row row = byEcn2.get(e); descs.put(e, row != null ? row.proposal : e); }
                com.sandisk.plm.tracker.service.RestartEcnProposalBuilder.Built built =
                        proposalBuilder.build(java.time.LocalDate.parse(rec.deployDate.trim()), descs);
                newProposal = built.proposal; newProblem = built.problemStatement;
            }
        } catch (Exception e) { LOG.warning("[RESTART-HIST] proposal rebuild on add-ecns failed: " + e.getMessage()); }

        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBackClient.addRelationships(ecn, username, agilePassword, v.acceptedEcns, newProposal, newProblem, corrId);
        resp.put("corrId", corrId); resp.put("droppedEcns", v.droppedEcns);
        if (r.ok && r.body != null) {
            @SuppressWarnings("unchecked")
            List<String> relOk = r.body.get("relatedOk") instanceof List ? (List<String>) (List<?>) r.body.get("relatedOk") : java.util.Collections.emptyList();
            resp.put("ok", true);
            resp.put("relatedOk", relOk);
            resp.put("relatedFailed", r.body.get("relatedFailed"));
            try {
                if (rec != null && !relOk.isEmpty()) {
                    Map<String, ItEnhancementsService.Row> byEcn = new java.util.HashMap<>();
                    for (ItEnhancementsService.Row row : rows) byEcn.put(row.ecnNumber, row);
                    for (String e : relOk) {
                        com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn b =
                                new com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn();
                        b.ecn = e; ItEnhancementsService.Row row = byEcn.get(e);
                        if (row != null) { b.proposal = row.proposal; b.itOwner = row.itOwner; b.itOwnerLoginId = row.itOwnerLoginId; }
                        rec.bundled.add(b);
                    }
                    restartHistory.addActivity(rec, "ecns-added", relOk.size() + " ECN(s) added: " + String.join(", ", relOk), username);
                    restartHistory.save(rec);
                    String byD = (String) session.getAttribute("displayName"); if (byD == null || byD.isEmpty()) byD = username;
                    if (restartNotify.sendUpdated(rec, relOk.size() + " ECN(s) added", byD, new java.util.HashSet<>(relOk), java.util.Collections.<String>emptySet())) {
                        restartHistory.addActivity(rec, "notify", "Emailed notify list (ECNs added)", "system");
                        restartHistory.save(rec);
                    }
                }
            } catch (Exception histErr) { LOG.warning("[RESTART-HIST] add-ecns record failed: " + histErr.getMessage()); }
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                    "RESTART_ECN_ADD_ECNS", ecn + " · +" + relOk.size());
        } else {
            String err = r.errorReason != null ? r.errorReason
                    : (r.body != null && r.body.get("errorReason") != null ? r.body.get("errorReason").toString() : "add failed");
            resp.put("ok", false); resp.put("error", err);
            if (isInvalidCredsError(err)) { session.removeAttribute("agilePassword"); resp.put("needsAgileSignin", true); }
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/create-restart-ecn")
    public ResponseEntity<?> createRestartEcn(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();

        String deployDateIso = body == null ? null : (String) body.get("deployDate"); // yyyy-MM-dd
        String itOwnerLogin  = body == null ? null : (String) body.get("itOwnerLogin");
        java.util.List<String> relatedEcns = new java.util.ArrayList<>();
        Object relRaw = body == null ? null : body.get("relatedEcns");
        if (relRaw instanceof java.util.List) {
            for (Object o : (java.util.List<?>) relRaw) {
                if (o != null) relatedEcns.add(String.valueOf(o).trim());
            }
        }

        if (deployDateIso == null || deployDateIso.trim().isEmpty()) {
            resp.put("ok", false); resp.put("error", "deployDate required"); return ResponseEntity.ok(resp);
        }
        java.time.LocalDate deployDate;
        try { deployDate = java.time.LocalDate.parse(deployDateIso.trim()); }
        catch (Exception e) { resp.put("ok", false); resp.put("error", "bad deployDate: " + deployDateIso); return ResponseEntity.ok(resp); }

        // Per-user Agile creds (same gate as save-cell).
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false); resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to create the Restart ECN.");
            return ResponseEntity.ok(resp);
        }

        List<ItEnhancementsService.Row> rows = service.readAll();
        com.sandisk.plm.tracker.service.RestartEcnService.Validated v =
                restartEcnService.revalidate(rows, relatedEcns, itOwnerLogin);
        if (!v.ok) { resp.put("ok", false); resp.put("error", v.error); return ResponseEntity.ok(resp); }

        // Build Proposal/Problem Statement from the ACCEPTED ECNs' descriptions,
        // in the user's selection order (v.acceptedEcns), not grid order.
        java.util.Map<String, String> descByEcn = new java.util.HashMap<>();
        for (ItEnhancementsService.Row r : rows) descByEcn.put(r.ecnNumber, r.proposal);
        java.util.Map<String, String> descs = new LinkedHashMap<>();
        for (String ecn : v.acceptedEcns) {
            if (descByEcn.containsKey(ecn)) descs.put(ecn, descByEcn.get(ecn));
        }
        com.sandisk.plm.tracker.service.RestartEcnProposalBuilder.Built built =
                proposalBuilder.build(deployDate, descs);

        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBackClient.createRestartEcn(
                username, agilePassword, built.proposal, built.problemStatement,
                v.itOwnerLogin, v.acceptedEcns, corrId);

        resp.put("ok", r.ok);
        resp.put("corrId", corrId);
        resp.put("droppedEcns", v.droppedEcns);
        if (r.ok && r.body != null) {
            resp.put("ecnNumber", r.body.get("ecnNumber"));
            resp.put("submitted", r.body.get("submitted"));
            resp.put("relatedOk", r.body.get("relatedOk"));
            resp.put("relatedFailed", r.body.get("relatedFailed"));
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                    "IT_ENH_CREATE_RESTART_ECN",
                    r.body.get("ecnNumber") + " · related " + v.acceptedEcns.size()
                            + " · owner " + v.itOwnerLogin);
            // Record to the Restart ECN history (§4). Fail-soft — never break the response.
            try {
                com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record rec =
                        new com.sandisk.plm.tracker.service.RestartEcnHistoryStore.Record();
                rec.ecn = String.valueOf(r.body.get("ecnNumber"));
                rec.deployDate = deployDateIso;
                rec.itOwnerLogin = v.itOwnerLogin;
                // Resolve the IT owner's display name ("Last, First") from the rows (the
                // roster is derived from row owners, so the chosen login is present).
                for (ItEnhancementsService.Row row : rows) {
                    if (row.itOwnerLoginId != null && v.itOwnerLogin != null
                            && row.itOwnerLoginId.equalsIgnoreCase(v.itOwnerLogin)) { rec.itOwnerName = row.itOwner; break; }
                }
                if (rec.itOwnerName == null || rec.itOwnerName.isEmpty()) rec.itOwnerName = v.itOwnerLogin;
                String createdByName = (String) session.getAttribute("displayName");
                if (createdByName == null || createdByName.isEmpty()) createdByName = username;
                rec.createdBy = createdByName;   // show "Last, First", not the loginid
                rec.createdAt = com.sandisk.plm.tracker.service.RestartEcnHistoryStore.nowIso();
                Map<String, ItEnhancementsService.Row> byEcn = new java.util.HashMap<>();
                for (ItEnhancementsService.Row row : rows) byEcn.put(row.ecnNumber, row);
                for (String e : v.acceptedEcns) {
                    ItEnhancementsService.Row row = byEcn.get(e);
                    com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn b =
                            new com.sandisk.plm.tracker.service.RestartEcnHistoryStore.BundledEcn();
                    b.ecn = e;
                    if (row != null) { b.proposal = row.proposal; b.itOwner = row.itOwner; b.itOwnerLoginId = row.itOwnerLoginId; }
                    rec.bundled.add(b);
                }
                restartHistory.addActivity(rec, "created",
                        "Restart ECN created — " + v.acceptedEcns.size() + " ECN(s) bundled", createdByName);
                restartHistory.save(rec);
                // Created email (fail-soft — never fail the response on SMTP trouble).
                if (restartNotify.sendCreated(rec)) {
                    restartHistory.addActivity(rec, "notify", "Emailed notify list (created)", "system");
                    restartHistory.save(rec);
                }
            } catch (Exception histErr) {
                LOG.warning("[RESTART-HIST] record-create failed: " + histErr.getMessage());
            }
        } else {
            String err = r.errorReason != null ? r.errorReason
                    : (r.body != null && r.body.get("errorReason") != null ? r.body.get("errorReason").toString() : "create failed");
            resp.put("error", err);
            if (isInvalidCredsError(err)) { session.removeAttribute("agilePassword"); resp.put("needsAgileSignin", true); }
        }
        return ResponseEntity.ok(resp);
    }

    /** Whether the SDK error string looks like "the password was wrong" so we
     *  know to drop the cached password and re-prompt. Matches Agile's
     *  canonical 60062 / "Invalid username or password" wording. */
    private static boolean isInvalidCredsError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase();
        return s.contains("60062") || s.contains("invalid username or password");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private boolean authorized(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return false;
        Object tabs = session.getAttribute("allowedTabs");
        if (tabs instanceof List) {
            return ((List<String>) tabs).contains("it-enhancements");
        }
        // Fallback: AuthController.getSession() lazily stashes allowedTabs on
        // the session, so an API call landing before /api/auth/session would
        // see a null attribute. Recompute here so we don't 403 the very first
        // /data hit after login. Cheap (no LDAP probes — just consults the
        // in-memory permissions map + the session's cached adGroups).
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        java.util.Set<String> adGroups = (java.util.Set<String>) session.getAttribute("adGroups");
        java.util.Set<String> allowed = userPermissionsService.getAllowedTabs(username, isAdmin,
                adGroups == null ? java.util.Collections.<String>emptySet() : adGroups);
        return allowed.contains("it-enhancements");
    }

    private ResponseEntity<?> forbidden() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Forbidden — IT Enhancements tab not granted to this user.");
        return ResponseEntity.status(403).body(err);
    }
}
