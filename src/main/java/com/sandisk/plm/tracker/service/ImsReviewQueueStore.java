package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * JSONL-backed event log for the IMS Review tab.
 *
 * <p>Each append is one event row covering a state transition (SEND_TO_DO,
 * DO_RESPONSE_NO_CHANGE, etc.). On startup the file is replayed into an
 * in-memory index keyed by {@code (docNumber, drrNumber)} so admin and DO/DM
 * lookups stay sub-millisecond.</p>
 *
 * <p>This mirrors the toolkit's existing {@code activity-log.jsonl} pattern:
 * append-only, no schema migration, easy to grep and back up.</p>
 */
@Service
public class ImsReviewQueueStore {

    private static final Logger LOG = Logger.getLogger(ImsReviewQueueStore.class.getName());

    /** State machine — derived from the latest event per (docNumber, drrNumber). */
    public enum Status {
        NOT_SENT,           // synthesized when no events exist
        SENT_TO_DO,
        SENT_TO_DM,
        DM_APPROVED,        // terminal
        DO_NEEDS_CHANGE,    // terminal
        DO_NEED_HELP,       // terminal
        CANCELLED           // terminal
    }

    public enum EventType {
        SEND_TO_DO,
        DO_RESPONSE_NO_CHANGE,
        SEND_TO_DM,
        DM_RESPONSE_APPROVED,
        DM_RESPONSE_SEND_BACK,
        DO_RESPONSE_NEEDS_CHANGE,
        DO_RESPONSE_NEED_HELP,
        CANCEL,
        /** Admin-only "throw it all the way back to the start" — reverts the
         *  in-memory item to NOT_SENT in place, preserving docNumber, drrNumber
         *  and the full audit history so the doc stays in New > Pending (not
         *  Legacy) and DCC can Send again. JSONL log keeps every prior event. */
        RESET,
        /** Records that a compliance approval PDF was generated for a
         *  response. Carries the path + SHA-256 so the queue.jsonl alone
         *  proves which PDF goes with which event. Doesn't change status. */
        PDF_GENERATED,
        /** Records the result of an Agile write-back cascade — DRR found,
         *  file attached, history written, status pushed, DCO created (or
         *  the step that broke). Doesn't change queue status; the response
         *  event that triggered it owns the workflow state. */
        AGILE_WRITEBACK
    }

    /** One row in queue.jsonl. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Event {
        public String ts;                  // ISO-8601 UTC
        public EventType type;
        public String docNumber;
        public String drrNumber;
        public Actor  actor;
        public List<String> recipients;    // To: addresses
        public String note;                // optional (Needs Change / Need Help)
        public String uploadFile;          // FIRST relative path under data/ims-review/uploads/ (back-compat)
        public List<String> uploadFiles;   // ALL relative paths for a multi-file upload (Need Help, etc.)
        public Integer fileCount;          // attachments included in the outbound email
        public Long fileBytes;             // total attachment bytes
        public Boolean attachedToEmail;    // false if size cap blocked it
        public Boolean doInactive;         // DO inactive in LDAP at send time
        public Boolean dmFallback;         // DM lookup failed; fell back to vikas.singh3

        /** Single-use UUID stamped onto SEND_TO_DO / SEND_TO_DM events. Carried
         *  in the outbound email URL; verified server-side on click. Null for
         *  every other event type. */
        public String token;
        /** Reference to the SEND token this response event is redeeming. Set
         *  on DO_RESPONSE_*, DM_RESPONSE_*, and CANCEL events so we can detect
         *  attempted re-clicks in {@link ImsReviewQueueStore#isTokenRedeemed}. */
        public String redeemsToken;
        /** SHA-256 fingerprint of the compliance PDF generated for this
         *  response (when applicable — Send Back doesn't produce one). */
        public String pdfSha256;
        /** Relative path under data/ims-review/pdfs/ for the PDF generated for
         *  this response. */
        public String pdfPath;
        /** Verified AD identity of the actor at the moment of attestation. */
        public String verifiedSamAccount;
        public String verifiedDisplayName;
        public String verifiedEmail;
        public String verifyIp;

        // ----- Agile write-back fields (Phase 4) -----
        /** Correlation UUID sent to plm-agile-service so its [AGILE-WRITE] log
         *  lines can be joined to this queue Event. Same UUID on every step
         *  for one toolkit action (DO Needs Change, DM Approve, etc.). */
        public String agileCorrId;
        /** DRR number we found / wrote against. */
        public String agileDrr;
        /** DCO number created for Needs Change (null otherwise). */
        public String agileDco;
        /** Names of the write-back steps that succeeded, in order — e.g.
         *  ["find-drr=ok","attach-file=ok","history=ok","create-dco=DCO-00123"]. */
        public List<String> agileSteps;
        /** Step name where write-back failed (null on success). */
        public String agileErrorAt;
        /** Error reason / message when {@link #agileErrorAt} is set. */
        public String agileError;

        // ----- DCO-form rich-payload fields (Phase 5) -----
        /** Full DCO form payload — captures DO intent for audit even if Agile rejects. */
        public java.util.Map<String, Object> dcoForm;
        /** Per-file metadata for attachments included in the DCO submission. */
        public java.util.List<java.util.Map<String, Object>> dcoAttachmentsManifest;
        /** SHA-256 of the canonicalized DCO form payload. Printed on the
         *  compliance PDF to bind the PDF 1:1 to the exact form the DO signed. */
        public String dcoFormChecksum;
    }

    public static final class Actor {
        public String email;
        public String displayName;
        public String role;  // "DCC" | "DO" | "DM"
    }

    /** Derived snapshot per (docNumber, drrNumber) — built from events. */
    public static final class QueueItem {
        public String docNumber;
        public String drrNumber;
        public Status status;
        public String lastUpdated;       // ts of latest event
        public String currentRecipient;  // who should act next (empty for terminal)
        public String currentRole;       // "DO" | "DM" | ""
        public List<Event> history = new ArrayList<>();
    }

    @Value("${app.ims-review.queue-file:./data/ims-review/queue.jsonl}")
    private String queueFile;

    @Value("${app.ims-review.upload-dir:./data/ims-review/uploads}")
    private String uploadDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Keyed by docNumber + "|" + drrNumber. Volatile read; synchronized write. */
    private final Map<String, QueueItem> index = new HashMap<>();
    /** All emails ever recipient'd (lowercased). Used by login auto-grant. */
    private final Set<String> pendingRecipientEmails = new HashSet<>();

    @PostConstruct
    public void load() {
        try {
            Path p = Paths.get(queueFile);
            Files.createDirectories(p.getParent());
            Files.createDirectories(Paths.get(uploadDir));
            if (!Files.exists(p)) {
                LOG.info("[IMS-QUEUE] no queue file at " + queueFile + " — starting empty");
                return;
            }
            int loaded = 0;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        Event e = mapper.readValue(line, Event.class);
                        applyToIndex(e);
                        loaded++;
                    } catch (Exception parseErr) {
                        LOG.warning("[IMS-QUEUE] skipped malformed line: " + parseErr.getMessage());
                    }
                }
            }
            LOG.info("[IMS-QUEUE] loaded " + loaded + " events into " + index.size() + " queue items");
        } catch (Exception e) {
            LOG.warning("[IMS-QUEUE] startup load failed: " + e.getMessage());
        }
    }

    /** Append a single event, persist to disk, and update the in-memory index. */
    public synchronized Event append(Event e) {
        if (e.ts == null) e.ts = Instant.now().toString();
        try {
            Path p = Paths.get(queueFile);
            Files.createDirectories(p.getParent());
            String json = mapper.writeValueAsString(e);
            try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                bw.write(json);
                bw.newLine();
            }
            applyToIndex(e);
            return e;
        } catch (IOException io) {
            throw new RuntimeException("Failed to persist IMS Review event: " + io.getMessage(), io);
        }
    }

    /** Snapshot of every QueueItem (admin view). Returns a copy. */
    public synchronized List<QueueItem> all() {
        return new ArrayList<>(index.values());
    }

    /** Every event ever appended, in file (append/chronological) order — read
     *  fresh from queue.jsonl. Unlike {@link #all()}, this includes the full
     *  history of docs that were later RESET (which drops them from the
     *  in-memory index), so it is the faithful source for the Action History
     *  log. Fail-soft: returns whatever parsed cleanly, or an empty list. */
    public synchronized List<Event> allEvents() {
        List<Event> out = new ArrayList<>();
        try {
            Path p = Paths.get(queueFile);
            if (!Files.exists(p)) return out;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        out.add(mapper.readValue(line, Event.class));
                    } catch (Exception ignore) {
                        // skip malformed line — same tolerance as load()
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[IMS-QUEUE] allEvents read failed: " + e.getMessage());
        }
        return out;
    }

    /** Items where {@code currentRecipient} matches the given email (DO/DM card view). */
    public synchronized List<QueueItem> pendingFor(String email) {
        if (email == null) return Collections.emptyList();
        String norm = email.trim().toLowerCase();
        List<QueueItem> out = new ArrayList<>();
        for (QueueItem q : index.values()) {
            if (q.currentRecipient != null && q.currentRecipient.equalsIgnoreCase(norm)) {
                out.add(q);
            }
        }
        return out;
    }

    /** True if any non-terminal queue item targets this email (login auto-grant). */
    public synchronized boolean hasPendingItemsFor(String email) {
        if (email == null) return false;
        return pendingRecipientEmails.contains(email.trim().toLowerCase());
    }

    /** Lookup by composite key. */
    public synchronized QueueItem find(String docNumber, String drrNumber) {
        return index.get(key(docNumber, drrNumber));
    }

    /** Find a SEND_TO_DO / SEND_TO_DM event by its one-time token. Returns
     *  null when the token isn't recognized (random URL, replay attack, or
     *  the event log has been pruned). */
    public synchronized Event findEventByToken(String token) {
        if (token == null || token.isEmpty()) return null;
        for (QueueItem q : index.values()) {
            for (Event e : q.history) {
                if (token.equals(e.token)) return e;
            }
        }
        return null;
    }

    /** Same as {@link #findEventByToken} but also returns the queue item so
     *  callers can render doc context without a second lookup. */
    public synchronized QueueItem findQueueItemByToken(String token) {
        if (token == null || token.isEmpty()) return null;
        for (QueueItem q : index.values()) {
            for (Event e : q.history) {
                if (token.equals(e.token)) return q;
            }
        }
        return null;
    }

    /** True when a later response event in the same queue item declares it is
     *  redeeming this token. Used to fail second-click attempts cleanly. */
    public synchronized boolean isTokenRedeemed(String token) {
        if (token == null || token.isEmpty()) return false;
        for (QueueItem q : index.values()) {
            for (Event e : q.history) {
                if (token.equals(e.redeemsToken)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Index maintenance
    // ------------------------------------------------------------------
    private void applyToIndex(Event e) {
        String k = key(e.docNumber, e.drrNumber);
        // RESET reverts the item to a clean NOT_SENT state IN PLACE — it does
        // NOT drop the entry. Previously we did index.remove(k), but the DRR#
        // for a not-yet-released doc lives only on the queue item; removing it
        // stripped the DRR linkage so the dashboard's hasDrr check failed and
        // the doc fell into the "Legacy" bucket (and out of "Need Owner")
        // instead of staying in New > Pending with status Not Sent. Keeping the
        // item — with docNumber, drrNumber and the full audit history intact —
        // restores the intended behaviour: DCC can Send again from scratch and
        // the row stays put. (PT-71, reported by Vikas 2026-07-23.)
        if (e.type == EventType.RESET) {
            QueueItem existing = index.get(k);
            if (existing != null) {
                existing.history.add(e);
                existing.lastUpdated = e.ts;
                existing.status = Status.NOT_SENT;
                existing.currentRecipient = null;
                existing.currentRole = "";
                // drrNumber / docNumber deliberately preserved so the doc keeps
                // its New-group (Pending) classification on the dashboard.
            }
            rebuildPendingRecipientEmails();
            return;
        }
        QueueItem item = index.computeIfAbsent(k, kk -> {
            QueueItem q = new QueueItem();
            q.docNumber = e.docNumber;
            q.drrNumber = e.drrNumber;
            q.status = Status.NOT_SENT;
            return q;
        });
        item.history.add(e);
        item.lastUpdated = e.ts;
        switch (e.type) {
            case SEND_TO_DO:
                item.status = Status.SENT_TO_DO;
                item.currentRecipient = firstOrNull(e.recipients);
                item.currentRole = "DO";
                break;
            case DO_RESPONSE_NO_CHANGE:
                // Bridging state; the SEND_TO_DM that follows will set the next status.
                item.status = Status.SENT_TO_DO; // unchanged until SEND_TO_DM applied
                break;
            case SEND_TO_DM:
                item.status = Status.SENT_TO_DM;
                item.currentRecipient = firstOrNull(e.recipients);
                item.currentRole = "DM";
                break;
            case DM_RESPONSE_APPROVED:
                item.status = Status.DM_APPROVED;
                item.currentRecipient = null;
                item.currentRole = "";
                break;
            case DM_RESPONSE_SEND_BACK:
                // Bridging — a fresh SEND_TO_DO event will follow.
                break;
            case DO_RESPONSE_NEEDS_CHANGE:
                item.status = Status.DO_NEEDS_CHANGE;
                item.currentRecipient = null;
                item.currentRole = "";
                break;
            case DO_RESPONSE_NEED_HELP:
                item.status = Status.DO_NEED_HELP;
                item.currentRecipient = null;
                item.currentRole = "";
                break;
            case CANCEL:
                item.status = Status.CANCELLED;
                item.currentRecipient = null;
                item.currentRole = "";
                break;
            case RESET:
                // Handled above (returns early); listed here to satisfy
                // exhaustiveness of the enum switch.
                break;
            case PDF_GENERATED:
                // Pure audit-record event; doesn't change queue status or
                // current recipient. The pdfPath + pdfSha256 fields on the
                // event itself are the payload.
                break;
            case AGILE_WRITEBACK:
                // Pure audit-record event; doesn't change queue status. The
                // agileDrr / agileDco / agileSteps / agileError fields on
                // the event itself are the payload.
                break;
        }
        rebuildPendingRecipientEmails();
    }

    private void rebuildPendingRecipientEmails() {
        pendingRecipientEmails.clear();
        for (QueueItem q : index.values()) {
            if (q.currentRecipient != null && !q.currentRecipient.isEmpty()) {
                pendingRecipientEmails.add(q.currentRecipient.toLowerCase());
            }
        }
    }

    private static String firstOrNull(List<String> xs) {
        return (xs == null || xs.isEmpty()) ? null : xs.get(0).toLowerCase();
    }

    private static String key(String doc, String drr) {
        return (doc == null ? "" : doc) + "|" + (drr == null ? "" : drr);
    }

    public String getUploadDir() { return uploadDir; }
}
