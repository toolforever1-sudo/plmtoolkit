package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.ImsReviewQueueStore.Event;
import com.sandisk.plm.tracker.service.ImsReviewQueueStore.EventType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Derives the IMS Review "Action History" feed from {@link ImsReviewQueueStore}
 * without adding any new logging at the mutation points — every user action is
 * already recorded as an {@link Event} in queue.jsonl. We replay the event log
 * chronologically, run the same state machine the queue store uses, and emit:
 *
 * <ul>
 *   <li>{@code events} — a newest-first, human-readable action log (each row
 *       carries a mapped actionType + from/to status for the "Moved" column);</li>
 *   <li>{@code stateCounts} — how many docs are CURRENTLY in each status
 *       (latest status across ALL of a doc's events, not just the window);</li>
 *   <li>{@code transitionCounts} — how many transitions of each kind happened
 *       inside the window (drives the flow-map arrows + off-path chips);</li>
 *   <li>{@code journeys} — per-doc chronological traces for the drawer.</li>
 * </ul>
 *
 * <p>Internal, non-user events (PDF_GENERATED, AGILE_WRITEBACK) are excluded
 * from the visible log. A DO "No Change" response is immediately followed by a
 * system SEND_TO_DM bridge event; we fold that bridge into the DO response row
 * (to = SENT_TO_DM) so the log doesn't show duplicate noise.</p>
 *
 * <p>Everything is fail-soft: a missing / empty queue file yields empty arrays
 * and zero counts so the UI still renders.</p>
 */
@Service
public class ImsActionLogService {

    private static final Logger LOG = Logger.getLogger(ImsActionLogService.class.getName());

    @Autowired
    private ImsReviewQueueStore queueStore;

    /** Build the action-log payload for the given rolling window (days). */
    public Map<String, Object> buildActionLog(int windowDays) {
        try {
            return build(windowDays);
        } catch (Exception e) {
            LOG.warning("[IMS-ACTION-LOG] build failed: " + e.getMessage());
            return emptyLog(windowDays);
        }
    }

    /** Empty-but-well-formed payload for the fail-soft path. */
    public Map<String, Object> emptyLog(int windowDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", windowDays);
        out.put("generatedAt", Instant.now().toString());
        out.put("events", new ArrayList<>());
        out.put("stateCounts", newStateCounts());
        out.put("transitionCounts", newTransitionCounts());
        out.put("journeys", new LinkedHashMap<>());
        return out;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> build(int windowDays) {
        int days = windowDays <= 0 ? 30 : windowDays;
        List<Event> all = queueStore.allEvents();
        // Chronological (oldest first). ts is ISO-8601 UTC; fall back to file order.
        all.sort(Comparator.comparingLong(this::epoch));

        long now = System.currentTimeMillis();
        long cutoff = now - (long) days * 24L * 3600L * 1000L;

        // Per-doc walk state
        Map<String, String> curStatus = new HashMap<>();      // docNumber -> current status
        Map<String, Boolean> sentBefore = new HashMap<>();     // docNumber -> a SEND_TO_DO has fired
        Map<String, EventType> prevType = new HashMap<>();     // docNumber -> last non-internal event type

        List<Map<String, Object>> windowRows = new ArrayList<>();               // chronological; reversed at end
        Map<String, List<Map<String, Object>>> journeysAll = new LinkedHashMap<>();
        Set<String> docsInWindow = new LinkedHashSet<>();
        Map<String, Integer> trans = newTransitionCounts();

        for (Event e : all) {
            if (e == null || e.type == null) continue;
            EventType t = e.type;

            // Internal audit rows — never user actions. Excluded from the log AND
            // from the status walk (they don't move the workflow).
            if (t == EventType.PDF_GENERATED || t == EventType.AGILE_WRITEBACK) continue;

            String doc = e.docNumber == null ? "" : e.docNumber;
            String from = curStatus.getOrDefault(doc, "NOT_SENT");
            String actionType;
            String to;
            boolean system = false;

            switch (t) {
                case SEND_TO_DO:
                    boolean prior = Boolean.TRUE.equals(sentBefore.get(doc));
                    actionType = prior ? "RESEND" : "SEND";
                    to = "SENT_TO_DO";
                    sentBefore.put(doc, true);
                    break;
                case DO_RESPONSE_NO_CHANGE:
                    // Fold the system SEND_TO_DM bridge that follows into this row.
                    actionType = "NO_CHANGE";
                    to = "SENT_TO_DM";
                    break;
                case SEND_TO_DM:
                    if (prevType.get(doc) == EventType.DO_RESPONSE_NO_CHANGE) {
                        // Already reflected on the DO response row — fold (drop).
                        curStatus.put(doc, "SENT_TO_DM");
                        prevType.put(doc, t);
                        continue;
                    }
                    // Standalone bridge (no preceding NO_CHANGE) — show as a system SEND.
                    actionType = "SEND";
                    to = "SENT_TO_DM";
                    system = true;
                    break;
                case DM_RESPONSE_APPROVED:
                    actionType = "DM_APPROVE";
                    to = "DM_APPROVED";
                    break;
                case DM_RESPONSE_SEND_BACK:
                    actionType = "SEND_BACK";
                    to = "SENT_TO_DO";
                    break;
                case DO_RESPONSE_NEEDS_CHANGE:
                    actionType = "NEEDS_CHANGE";
                    to = "DO_NEEDS_CHANGE";
                    break;
                case DO_RESPONSE_NEED_HELP:
                    actionType = "NEED_HELP";
                    to = "DO_NEED_HELP";
                    break;
                case CANCEL:
                    actionType = "CANCEL";
                    to = "CANCELLED";
                    break;
                case RESET:
                    actionType = "RESET";
                    to = "NOT_SENT";
                    break;
                default:
                    continue;
            }

            curStatus.put(doc, to);
            prevType.put(doc, t);

            // Full-history journey node (trimmed to window docs at the end).
            journeysAll.computeIfAbsent(doc, k -> new ArrayList<>())
                    .add(journeyEntry(e, actionType, to, system));

            long ep = epoch(e);
            if (ep >= cutoff) {
                windowRows.add(logRow(e, actionType, from, to, system));
                docsInWindow.add(doc);
                bumpTransition(trans, t);
            }
        }

        // stateCounts — current status of every doc seen in the whole log.
        Map<String, Integer> stateCounts = newStateCounts();
        for (String st : curStatus.values()) {
            if (stateCounts.containsKey(st)) stateCounts.put(st, stateCounts.get(st) + 1);
        }

        Collections.reverse(windowRows);   // newest first

        // journeys — only for docs that appear in the window (keep it lean),
        // but each carries that doc's FULL chronology for the drawer.
        Map<String, Object> journeys = new LinkedHashMap<>();
        for (String doc : docsInWindow) {
            List<Map<String, Object>> j = journeysAll.get(doc);
            if (j != null) journeys.put(doc, j);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", days);
        out.put("generatedAt", Instant.now().toString());
        out.put("events", windowRows);
        out.put("stateCounts", stateCounts);
        out.put("transitionCounts", trans);
        out.put("journeys", journeys);
        return out;
    }

    // ------------------------------------------------------------------
    // Row builders
    // ------------------------------------------------------------------

    private Map<String, Object> logRow(Event e, String actionType, String from, String to, boolean system) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", e.ts);
        m.put("epochMs", epoch(e));
        m.put("actionType", actionType);
        m.put("displayName", displayName(e, system));
        m.put("actorRole", roleLabel(e, system));
        m.put("docNumber", e.docNumber);
        m.put("drrNumber", e.drrNumber);
        m.put("fromStatus", from);
        m.put("toStatus", to);
        m.put("details", details(e, actionType));
        return m;
    }

    private Map<String, Object> journeyEntry(Event e, String actionType, String to, boolean system) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", e.ts);
        m.put("epochMs", epoch(e));
        m.put("actionType", actionType);
        m.put("toStatus", to);
        m.put("displayName", displayName(e, system));
        m.put("actorRole", roleLabel(e, system));
        m.put("note", e.note);
        return m;
    }

    // ------------------------------------------------------------------
    // Field derivation
    // ------------------------------------------------------------------

    private static String displayName(Event e, boolean system) {
        if (e.actor != null) {
            if (e.actor.displayName != null && !e.actor.displayName.isEmpty()) return e.actor.displayName;
            if (e.actor.email != null && !e.actor.email.isEmpty()) return e.actor.email;
        }
        return system ? "system" : "system";
    }

    /** DCC → "DCC admin", DO → "Doc owner", DM → "DM", absent → "system". */
    private static String roleLabel(Event e, boolean system) {
        String role = (e.actor == null) ? null : e.actor.role;
        if (role != null) {
            switch (role) {
                case "DCC": return "DCC admin";
                case "DO":  return "Doc owner";
                case "DM":  return "DM";
                default:    return role;
            }
        }
        return system ? "system" : "system";
    }

    /** Prefer the event's own note; otherwise synthesize a readable sentence. */
    private static String details(Event e, String actionType) {
        if (e.note != null && !e.note.trim().isEmpty()) return e.note.trim();
        String recipient = (e.recipients != null && !e.recipients.isEmpty()) ? e.recipients.get(0) : null;
        switch (actionType) {
            case "SEND":         return recipient != null ? "Review email sent to " + recipient : "Review email sent to the document owner.";
            case "RESEND":       return recipient != null ? "Reminder re-sent to " + recipient : "Reminder re-sent to the document owner.";
            case "NO_CHANGE":    return "Document owner confirmed no change needed — forwarded to DM.";
            case "NEEDS_CHANGE": return "Document owner flagged a needed change — change order drafted.";
            case "NEED_HELP":    return "Document owner asked Doc Control for help.";
            case "DM_APPROVE":   return "DM approved — review closed.";
            case "SEND_BACK":    return "DM sent the review back to the document owner.";
            case "CANCEL":       return "Review cancelled.";
            case "RESET":        return "Reset back to Not Sent — queue state cleared.";
            default:             return "";
        }
    }

    private long epoch(Event e) {
        if (e == null || e.ts == null) return 0L;
        try {
            return Instant.parse(e.ts).toEpochMilli();
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static void bumpTransition(Map<String, Integer> t, EventType type) {
        switch (type) {
            case SEND_TO_DO:              inc(t, "send"); break;
            case DO_RESPONSE_NO_CHANGE:   inc(t, "respond"); break;
            case DO_RESPONSE_NEEDS_CHANGE: inc(t, "respond"); inc(t, "needsChange"); break;
            case DO_RESPONSE_NEED_HELP:   inc(t, "respond"); inc(t, "needHelp"); break;
            case DM_RESPONSE_APPROVED:    inc(t, "approve"); break;
            case RESET:                   inc(t, "reset"); break;
            case CANCEL:                  inc(t, "cancelled"); break;
            default: break;   // SEND_TO_DM (folded), SEND_BACK, internal → no arrow bucket
        }
    }

    private static void inc(Map<String, Integer> m, String k) {
        m.put(k, m.getOrDefault(k, 0) + 1);
    }

    private static Map<String, Integer> newStateCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("NOT_SENT", 0);
        m.put("SENT_TO_DO", 0);
        m.put("SENT_TO_DM", 0);
        m.put("DM_APPROVED", 0);
        m.put("DO_NEEDS_CHANGE", 0);
        m.put("DO_NEED_HELP", 0);
        m.put("CANCELLED", 0);
        return m;
    }

    private static Map<String, Integer> newTransitionCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("send", 0);
        m.put("respond", 0);
        m.put("approve", 0);
        m.put("reset", 0);
        m.put("needsChange", 0);
        m.put("needHelp", 0);
        m.put("cancelled", 0);
        return m;
    }
}
