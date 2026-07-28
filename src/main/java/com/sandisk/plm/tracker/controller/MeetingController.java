package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AgileWriteBackClient;
import com.sandisk.plm.tracker.service.MeetingEmailService;
import com.sandisk.plm.tracker.service.MeetingModels;
import com.sandisk.plm.tracker.service.MeetingStorageService;
import com.sandisk.plm.tracker.service.UserPermissionsService;
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
 * Backs IT Enhancements <b>Meeting Mode</b> — live sessions, bubbles, and the
 * frozen meeting records (see {@code MEETING-MODE-IMPLEMENTATION.md} §4).
 *
 * <p>All writes execute <b>under the caller's AD identity</b> (the per-user
 * Agile password cached on the {@code HttpSession} by the IT-Enhancements
 * sign-in flow), identical to the editable grid's per-cell save. {@code AGILE}
 * bubbles write live as they are captured; {@code NOTE} bubbles never touch
 * Agile.
 *
 * <p>Auth — same gate as {@link ItEnhancementsController}: the caller must have
 * the {@code it-enhancements} tab granted.
 */
@RestController
@RequestMapping("/api/meeting")
public class MeetingController {

    private static final Logger LOG = Logger.getLogger(MeetingController.class.getName());

    @Autowired private MeetingStorageService storage;
    @Autowired private MeetingEmailService emailService;
    @Autowired private AgileWriteBackClient agileWriteBackClient;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private UserPermissionsService userPermissionsService;

    /** Structured AGILE field → Agile cell name. Mirrors the editable-grid
     *  allowlist (already verified against agprod); IT Status is intentionally
     *  absent — it is read-only in Meeting Mode (§4). */
    private static String cellForField(String field) {
        if (field == null) return null;
        switch (field) {
            case "TARGET_UAT":     return "Page Three.Target UAT Date";
            case "TARGET_GOLIVE":  return "Page Three.Target Go Live Date";
            case "IT_LOG_NOTE":    return "Page Three.IT Log";
            default:               return null;
        }
    }

    // ==================================================================
    // Sessions
    // ==================================================================

    /** Start a session. Body: {@code {title, attendees:[{ad,name,isLeader}]}}. */
    @PostMapping("/sessions")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> startSession(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        MeetingModels.Session s = new MeetingModels.Session();
        s.id = UUID.randomUUID().toString();
        s.title = body == null ? null : (String) body.getOrDefault("title", "IT Enhancements review");
        if (s.title == null || s.title.isEmpty()) s.title = "IT Enhancements review";
        s.startedAt = System.currentTimeMillis();
        s.status = "LIVE";
        s.createdByAd = username;

        List<Map<String, Object>> attendees = body == null ? null
                : (List<Map<String, Object>>) body.get("attendees");
        if (attendees != null) {
            for (Map<String, Object> a : attendees) {
                MeetingModels.Attendee at = new MeetingModels.Attendee();
                at.ad = str(a.get("ad"));
                at.name = str(a.get("name"));
                at.email = str(a.get("email"));
                at.leader = Boolean.TRUE.equals(a.get("isLeader")) || Boolean.TRUE.equals(a.get("leader"));
                if (at.leader) { s.leaderAd = at.ad; s.leaderName = at.name; }
                s.attendees.add(at);
            }
        }
        if (s.leaderName == null) s.leaderName = displayName;
        if (s.leaderAd == null) s.leaderAd = username;

        storage.saveSession(s);
        activityLogger.log(username, displayName, "MEETING_START",
                "session " + s.id + " · " + s.attendees.size() + " in room");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", s.id);
        resp.put("startedAt", s.startedAt);
        return ResponseEntity.ok(resp);
    }

    /** Live session state — attendees + bubbles + computed notify list. */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.status(404).body(err("No such session."));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("session", s);
        resp.put("notify", notifyList(s));
        return ResponseEntity.ok(resp);
    }

    /**
     * Add a bubble. Body:
     * {@code {ecn, kind:"AGILE"|"NOTE", text, field?, oldValue?, newValue?,
     *         assigneeAd?, assigneeName?, ownerAd?, ownerName?, time?}}.
     * Server stamps author/createdAt/ownerNotInRoom; for AGILE it performs the
     * live per-user Agile write and records {@code agileWriteStatus}.
     */
    @PostMapping("/sessions/{id}/bubbles")
    public ResponseEntity<?> addBubble(@PathVariable("id") String id,
                                       @RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.status(404).body(err("No such session."));
        if (body == null) return ResponseEntity.badRequest().body(err("request body required"));

        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        MeetingModels.Bubble b = new MeetingModels.Bubble();
        b.id = "b-" + UUID.randomUUID().toString().substring(0, 8);
        b.ecn = str(body.get("ecn"));
        b.ecnTitle = str(body.get("ecnTitle"));
        b.kind = str(body.get("kind"));
        b.text = str(body.get("text"));
        b.field = str(body.get("field"));
        b.oldValue = str(body.get("oldValue"));
        b.newValue = str(body.get("newValue"));
        b.assigneeAd = str(body.get("assigneeAd"));
        b.assigneeName = str(body.get("assigneeName"));
        b.ownerAd = str(body.get("ownerAd"));
        b.ownerName = str(body.get("ownerName"));
        b.authorAd = username;
        b.authorName = displayName != null ? displayName : s.leaderName;
        b.createdAt = System.currentTimeMillis();
        b.time = str(body.get("time"));
        if (b.ecn == null || b.ecn.isEmpty()) return ResponseEntity.badRequest().body(err("ecn required"));
        if (!"AGILE".equals(b.kind) && !"NOTE".equals(b.kind)) return ResponseEntity.badRequest().body(err("kind must be AGILE or NOTE"));

        // owner_not_in_room is authoritative server-side (§4 rule).
        b.ownerNotInRoom = "AGILE".equals(b.kind)
                && b.ownerAd != null && !b.ownerAd.isEmpty()
                && !"Unassigned".equalsIgnoreCase(b.ownerName == null ? "" : b.ownerName)
                && !inRoom(s, b.ownerAd);

        if ("AGILE".equals(b.kind)) {
            // Capture only — the Agile write is DEFERRED to wrap-up/send (the
            // leader reviews the captured set before anything lands in Agile).
            // We still validate the field maps to a known cell so a bad capture
            // surfaces immediately rather than at send time.
            String cell = cellForField(b.field);
            if (cell == null) return ResponseEntity.badRequest().body(err("unknown AGILE field: " + b.field));
            b.agileWriteStatus = "PENDING";
        }

        s.bubbles.add(b);
        storage.saveSession(s);
        return ResponseEntity.ok(b);
    }

    /** Compute the recap preview (no send) — §6 email model. */
    @PostMapping("/sessions/{id}/wrap")
    public ResponseEntity<?> wrap(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.status(404).body(err("No such session."));

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, List<String>> byEcn = new LinkedHashMap<>();
        for (MeetingModels.Bubble b : s.bubbles) {
            if ("NOTE".equals(b.kind) && b.assigneeName != null && !b.assigneeName.isEmpty()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("who", b.assigneeName); a.put("text", b.text); a.put("ecn", shortEcn(b.ecn));
                actions.add(a);
            }
            if ("AGILE".equals(b.kind)) byEcn.computeIfAbsent(shortEcn(b.ecn), k -> new ArrayList<>()).add(b.text);
        }
        List<Map<String, Object>> agile = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byEcn.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("ecn", e.getKey()); g.put("text", String.join(" · ", e.getValue()));
            agile.add(g);
        }
        List<String> notify = notifyList(s);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("recap", buildMap("actions", actions, "agile", agile));
        resp.put("separateNotifications", notify);
        resp.put("notifyCount", notify.size());
        return ResponseEntity.ok(resp);
    }

    /** Wrap up: write all captured (PENDING) AGILE bubbles to Agile under the
     *  caller's AD identity, then send the recap + notifications, freeze the
     *  record, and end the session. The Agile writes happen HERE, not when the
     *  bubble was captured. If captured Agile updates exist but no per-user
     *  Agile password is cached, returns {needsAgileSignin:true} without ending
     *  the session so the UI can sign in and retry. */
    @PostMapping("/sessions/{id}/send")
    public ResponseEntity<?> send(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.status(404).body(err("No such session."));
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        // ---- 1) flush captured Agile writes ----
        boolean hasPending = false;
        for (MeetingModels.Bubble b : s.bubbles) {
            if ("AGILE".equals(b.kind) && !"WRITTEN".equals(b.agileWriteStatus)) { hasPending = true; break; }
        }
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (hasPending && (agilePassword == null || agilePassword.isEmpty())) {
            Map<String, Object> r = err("Sign in to Agile to save the captured updates.");
            r.put("needsAgileSignin", true);
            return ResponseEntity.ok(r);   // session stays live — UI signs in + retries
        }
        int writeFailures = 0;
        List<String> writeErrors = new ArrayList<>();
        for (MeetingModels.Bubble b : s.bubbles) {
            if (!"AGILE".equals(b.kind) || "WRITTEN".equals(b.agileWriteStatus)) continue;
            String cell = cellForField(b.field);
            String value = "IT_LOG_NOTE".equals(b.field) ? b.text : b.newValue;
            String corrId = UUID.randomUUID().toString();
            AgileWriteBackClient.Result wr = agileWriteBackClient.updateChangeCellAsUser(
                    b.ecn, username, agilePassword, cell, value, corrId);
            if (wr.ok) {
                b.agileWriteStatus = "WRITTEN";
                b.agileError = null;
                if (wr.body != null && wr.body.get("newValue") != null) b.newValue = str(wr.body.get("newValue"));
                activityLogger.log(username, displayName, "MEETING_AGILE_WRITE", b.ecn + " · " + cell + " → " + value);
            } else {
                String errReason = wr.errorReason == null ? "save failed" : wr.errorReason;
                if (isInvalidCredsError(errReason)) {
                    // Bad password — persist what wrote, drop the password, ask
                    // the UI to re-sign-in and retry (already-written bubbles are
                    // skipped on the retry).
                    storage.saveSession(s);
                    session.removeAttribute("agilePassword");
                    Map<String, Object> r = err("Agile rejected the credentials — sign in again.");
                    r.put("needsAgileSignin", true);
                    return ResponseEntity.ok(r);
                }
                b.agileWriteStatus = "FAILED";
                b.agileError = errReason;
                writeFailures++;
                writeErrors.add(shortEcn(b.ecn) + ": " + errReason);
                activityLogger.log(username, displayName, "MEETING_AGILE_WRITE_FAILED", b.ecn + " · " + cell + " · " + errReason);
            }
        }

        // ---- 2) end + email + freeze ----
        s.endedAt = System.currentTimeMillis();
        s.status = "ENDED";

        MeetingEmailService.SendResult sent = emailService.sendWrapUp(s);

        MeetingModels.Record rec = freeze(s, sent);
        storage.saveRecord(rec);
        storage.deleteSession(s.id);

        activityLogger.log(username, displayName, "MEETING_SEND",
                "record " + rec.id + " · " + rec.counts.agile + " agile (" + writeFailures + " failed) · "
                        + rec.counts.actions + " actions · " + rec.counts.notified + " notified");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("record", rec);
        resp.put("mailErrors", sent.errors);
        resp.put("writeFailures", writeFailures);
        resp.put("writeErrors", writeErrors);
        return ResponseEntity.ok(resp);
    }

    // ==================================================================
    // Records
    // ==================================================================

    @GetMapping("/records")
    public ResponseEntity<?> records(HttpSession session) {
        if (!authorized(session)) return forbidden();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MeetingModels.Record r : storage.listRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("date", r.date);
            m.put("title", r.title);
            m.put("leaderName", r.leaderName);
            m.put("attendeeCount", r.attendees.size());
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("agile", r.counts.agile);
            counts.put("notes", r.counts.notes);
            counts.put("actions", r.counts.actions);
            counts.put("actionsOpen", r.counts.actionsOpen);
            counts.put("notified", r.counts.notified);
            m.put("counts", counts);
            out.add(m);
        }
        return ResponseEntity.ok(buildMap("records", out));
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<?> record(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Record r = storage.readRecord(id);
        if (r == null) return ResponseEntity.status(404).body(err("No such record."));
        return ResponseEntity.ok(r);
    }

    /** Update an action item's follow-through status. Body: {@code {status:"OPEN"|"DONE"}}.
     *  Records are few; we scan for the owning record by action-item id. */
    @PatchMapping("/action-items/{id}")
    public ResponseEntity<?> updateActionItem(@PathVariable("id") String id,
                                              @RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        String status = body == null ? null : str(body.get("status"));
        if (!"OPEN".equals(status) && !"DONE".equals(status)) {
            return ResponseEntity.badRequest().body(err("status must be OPEN or DONE"));
        }
        for (MeetingModels.Record r : storage.listRecords()) {
            for (MeetingModels.ActionItem a : r.actionItems) {
                if (id.equals(a.id)) {
                    a.status = status;
                    a.closedAt = "DONE".equals(status) ? System.currentTimeMillis() : null;
                    r.counts.actionsOpen = countOpen(r);
                    storage.saveRecord(r);
                    activityLogger.log((String) session.getAttribute("username"),
                            (String) session.getAttribute("displayName"),
                            "MEETING_ACTION_UPDATE", a.ecn + " · " + id + " → " + status);
                    return ResponseEntity.ok(a);
                }
            }
        }
        return ResponseEntity.status(404).body(err("No such action item."));
    }

    // ==================================================================
    // v3 — cancel / undo / record management / notes history
    // ==================================================================

    /** Cancel a live session — discard it entirely. Nothing was written to
     *  Agile (writes commit only at /send), so this is a pure delete; no record
     *  is created, no email sent. */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> cancelSession(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.ok(java.util.Collections.singletonMap("ok", true));   // already gone
        storage.deleteSession(id);
        activityLogger.log((String) session.getAttribute("username"), (String) session.getAttribute("displayName"),
                "MEETING_CANCEL", "session " + id + " · discarded " + s.bubbles.size() + " captured update(s)");
        return ResponseEntity.ok(java.util.Collections.singletonMap("ok", true));
    }

    /** Undo a single captured bubble in a live session — removes it. The Agile
     *  write is still pending (commits at /send), so this just drops the bubble;
     *  the UI reverts the local field edit from the bubble's old value. */
    @DeleteMapping("/sessions/{id}/bubbles/{bid}")
    public ResponseEntity<?> undoBubble(@PathVariable("id") String id, @PathVariable("bid") String bid, HttpSession session) {
        if (!authorized(session)) return forbidden();
        MeetingModels.Session s = storage.readSession(id);
        if (s == null) return ResponseEntity.status(404).body(err("No such session."));
        boolean removed = s.bubbles.removeIf(b -> bid.equals(b.id));
        if (removed) storage.saveSession(s);
        return ResponseEntity.ok(java.util.Collections.singletonMap("ok", removed));
    }

    /** Soft-delete a meeting record (PLM admin only). The file is kept (deleted
     *  flag + audit) for recoverability; it's hidden from the list. Never
     *  touches Agile — only the meeting artifact. */
    @DeleteMapping("/records/{id}")
    public ResponseEntity<?> deleteRecord(@PathVariable("id") String id, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("PLM admin only."));
        MeetingModels.Record r = storage.readRecord(id);
        if (r == null) return ResponseEntity.status(404).body(err("No such record."));
        r.deleted = true;
        r.deletedBy = (String) session.getAttribute("username");
        r.deletedAt = System.currentTimeMillis();
        storage.saveRecord(r);
        activityLogger.log((String) session.getAttribute("username"), (String) session.getAttribute("displayName"),
                "MEETING_RECORD_DELETE", "record " + id);
        return ResponseEntity.ok(java.util.Collections.singletonMap("ok", true));
    }

    /** Consolidate 2+ same-day records into one (PLM admin only). Sums counts,
     *  concatenates action items + frozen logs, soft-deletes the originals, and
     *  keeps provenance (sourceIds). Body: {@code {ids:[...]}}. */
    @PostMapping("/records/consolidate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> consolidateRecords(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("PLM admin only."));
        List<String> ids = body == null ? null : (List<String>) body.get("ids");
        if (ids == null || ids.size() < 2) return ResponseEntity.badRequest().body(err("Select 2+ records."));

        List<MeetingModels.Record> sel = new ArrayList<>();
        for (String rid : ids) { MeetingModels.Record r = storage.readRecord(rid); if (r != null && !r.deleted) sel.add(r); }
        if (sel.size() < 2) return ResponseEntity.badRequest().body(err("Select 2+ existing records."));
        // same-day guard
        String day0 = dayKey(sel.get(0).date);
        for (MeetingModels.Record r : sel) if (!dayKey(r.date).equals(day0)) return ResponseEntity.badRequest().body(err("All records must be from the same day."));
        sel.sort(java.util.Comparator.comparingLong(r -> r.date));

        MeetingModels.Record m = new MeetingModels.Record();
        m.id = "merged-" + UUID.randomUUID().toString().substring(0, 8);
        m.consolidated = true;
        m.sourceIds = new ArrayList<>(ids);
        m.date = sel.get(0).date;
        m.endedAt = sel.get(sel.size() - 1).endedAt;
        m.leaderAd = sel.get(0).leaderAd;
        m.leaderName = sel.get(0).leaderName;
        java.util.LinkedHashMap<String, MeetingModels.Attendee> att = new java.util.LinkedHashMap<>();
        for (MeetingModels.Record r : sel) for (MeetingModels.Attendee a : r.attendees) att.put(a.ad == null ? a.name : a.ad, a);
        m.attendees = new ArrayList<>(att.values());
        for (MeetingModels.Record r : sel) {
            m.actionItems.addAll(r.actionItems);
            m.log.addAll(r.log);
            m.recipients.addAll(r.recipients);
            m.counts.agile += r.counts.agile; m.counts.notes += r.counts.notes;
            m.counts.actions += r.counts.actions; m.counts.actionsOpen += r.counts.actionsOpen;
            m.counts.notified += r.counts.notified;
        }
        m.title = monthDay(m.date) + " · IT Enhancements review (consolidated)";
        storage.saveRecord(m);
        // soft-delete originals
        for (MeetingModels.Record r : sel) {
            r.deleted = true; r.deletedBy = (String) session.getAttribute("username"); r.deletedAt = System.currentTimeMillis();
            storage.saveRecord(r);
        }
        activityLogger.log((String) session.getAttribute("username"), (String) session.getAttribute("displayName"),
                "MEETING_RECORD_CONSOLIDATE", "merged " + ids + " → " + m.id);
        return ResponseEntity.ok(java.util.Collections.singletonMap("record", m));
    }

    /** Notes-from-earlier-meetings: meeting-only (NOTE) bubbles captured on an
     *  ECN in prior records, newest first. Read-only. */
    @GetMapping("/notes-history")
    public ResponseEntity<?> notesHistory(@RequestParam("ecn") String ecn, HttpSession session) {
        if (!authorized(session)) return forbidden();
        String want = ecn == null ? "" : ecn.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MeetingModels.Record r : storage.listRecords()) {
            for (MeetingModels.Bubble b : r.log) {
                if (!"NOTE".equals(b.kind)) continue;
                if (!want.equalsIgnoreCase(b.ecn) && !shortEcn(want).equalsIgnoreCase(shortEcn(b.ecn))) continue;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("text", b.text);
                n.put("author", b.authorName);
                n.put("assignee", b.assigneeName);
                n.put("createdAt", b.createdAt > 0 ? b.createdAt : r.date);
                n.put("recordId", r.id);
                out.add(n);
            }
        }
        out.sort((a, c) -> Long.compare(asLong(c.get("createdAt")), asLong(a.get("createdAt"))));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ecn", want); resp.put("notes", out);
        return ResponseEntity.ok(resp);
    }

    private static long asLong(Object o) { try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; } }
    private static String dayKey(long ms) { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(ms)); }
    private static String monthDay(long ms) { return new java.text.SimpleDateFormat("MMM d").format(new java.util.Date(ms)); }
    private boolean isAdmin(HttpSession session) { return Boolean.TRUE.equals(session.getAttribute("isPlmAdmin")); }

    // ==================================================================
    // helpers
    // ==================================================================

    /** Freeze a sent session into an immutable record. */
    private MeetingModels.Record freeze(MeetingModels.Session s, MeetingEmailService.SendResult sent) {
        MeetingModels.Record r = new MeetingModels.Record();
        r.id = s.id;
        r.title = s.title;
        r.endedAt = s.endedAt != null ? s.endedAt : System.currentTimeMillis();
        r.date = r.endedAt;
        r.leaderAd = s.leaderAd;
        r.leaderName = s.leaderName;
        r.attendees = s.attendees;
        r.log = s.bubbles;
        r.recipients = sent.recipients;

        int agile = 0, notes = 0;
        for (MeetingModels.Bubble b : s.bubbles) {
            if ("AGILE".equals(b.kind)) agile++;
            else if ("NOTE".equals(b.kind)) {
                notes++;
                if (b.assigneeName != null && !b.assigneeName.isEmpty()) {
                    MeetingModels.ActionItem a = new MeetingModels.ActionItem();
                    a.id = "a-" + UUID.randomUUID().toString().substring(0, 8);
                    a.bubbleId = b.id;
                    a.assigneeAd = b.assigneeAd;
                    a.assigneeName = b.assigneeName;
                    a.text = b.text;
                    a.ecn = b.ecn;   // full ECN (incl. -PROJ) so the UI can build an Agile link; display strips it
                    a.status = "OPEN";
                    r.actionItems.add(a);
                }
            }
        }
        r.counts.agile = agile;
        r.counts.notes = notes;
        r.counts.actions = r.actionItems.size();
        r.counts.actionsOpen = r.actionItems.size();
        r.counts.notified = sent.notifiedCount;
        return r;
    }

    private static int countOpen(MeetingModels.Record r) {
        int n = 0;
        for (MeetingModels.ActionItem a : r.actionItems) if (!"DONE".equals(a.status)) n++;
        return n;
    }

    /** Distinct display names in the notify set (out-of-room owners + out-of-room assignees). */
    private List<String> notifyList(MeetingModels.Session s) {
        java.util.LinkedHashMap<String, String> set = new java.util.LinkedHashMap<>();
        for (MeetingModels.Bubble b : s.bubbles) {
            if ("AGILE".equals(b.kind) && b.ownerNotInRoom && b.ownerAd != null && !b.ownerAd.isEmpty()) {
                set.putIfAbsent(b.ownerAd, b.ownerName);
            }
            if ("NOTE".equals(b.kind) && b.assigneeAd != null && !b.assigneeAd.isEmpty()
                    && !inRoom(s, b.assigneeAd)) {
                set.putIfAbsent(b.assigneeAd, b.assigneeName);
            }
        }
        return new ArrayList<>(set.values());
    }

    private static boolean inRoom(MeetingModels.Session s, String ad) {
        if (ad == null) return false;
        for (MeetingModels.Attendee a : s.attendees) {
            if (a.ad != null && a.ad.equalsIgnoreCase(ad)) return true;
        }
        return false;
    }

    private static String shortEcn(String ecn) { return ecn == null ? "" : ecn.replace("-PROJ", ""); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static boolean isInvalidCredsError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase();
        return s.contains("60062") || s.contains("invalid username or password");
    }

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
