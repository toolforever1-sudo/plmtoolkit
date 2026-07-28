package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * IMS Review orchestrator. Coordinates the queue store, email service, LDAP
 * manager lookup, and Agile attachment fetch. Holds the state-machine logic
 * for the eight flows (Send, DO response x3, DM response x2, Cancel, Get File).
 */
@Service
public class ImsReviewService {

    private static final Logger LOG = Logger.getLogger(ImsReviewService.class.getName());

    @Autowired private ImsReviewQueueStore queueStore;
    @Autowired private ImsReviewEmailService emailService;
    @Autowired private LdapManagerLookup managerLookup;
    @Autowired private AgileDocumentAttachmentsClient agileAttachments;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private AgileWriteBackClient agileWriteBack;
    @Autowired private ImsReviewRetryQueue retryQueue;

    @org.springframework.beans.factory.annotation.Value("${app.ims-review.writeback-enabled:false}")
    private boolean writebackEnabled;

    /** Default cap on "how far into the past a doc's next-review date is
     *  allowed to be before the dashboard hides it" — Jimmy's chat ask
     *  2026-06-05. 5 years matches his stated cutoff. 0 = unlimited. */
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.default-max-overdue-years:5}")
    private int defaultMaxOverdueYears;

    /** Name of the DRR workflow status the toolkit pushes to on DM Approve.
     *  In our env it's "CCB" (DCC takes over from there manually). */
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.drr-review-status:CCB}")
    private String drrReviewStatus;

    private final DataSource agileDataSource;

    public ImsReviewService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    // ------------------------------------------------------------------
    // Cache for the heavy "docs due within N days" Agile query.
    //
    // The status overlay (NOT_SENT / SENT_TO_DO / etc.) is computed
    // fresh on every dataForAdmin() call from the in-memory queue
    // event log, so cache invalidation on send/respond/cancel is NOT
    // required — those mutate queueStore, not the docs list.
    //
    // Two layers:
    //   1. In-memory map keyed by daysAhead (60 min TTL).
    //   2. JSON snapshot on disk so the cache survives JVM restarts and
    //      the first user after a deploy doesn't pay the 100+ second
    //      cold cost. Loaded in @PostConstruct, rewritten after every
    //      successful pull or owner patch.
    //
    // An hourly @Scheduled rebuild keeps the snapshot ahead of the TTL
    // so users effectively never see a cold call. Mutations that change
    // the row content (owner reassignment) patch the cache inline via
    // patchOwnersInCache instead of nuking it.
    // ------------------------------------------------------------------
    private static final long DOCS_CACHE_TTL_MS = 60L * 60 * 1000;  // 60 min
    private static final String DOCS_CACHE_FILE = "./data/ims-review-docs-cache.json";
    private static final int DOCS_CACHE_SNAPSHOT_VERSION = 1;
    private final Map<Integer, CachedDocs> docsCache = new HashMap<>();
    private final ObjectMapper cacheMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Single-thread executor that runs LDAP enrichment off the request thread,
     * so the first cold /data call after a deploy doesn't sit waiting for
     * 1,000+ serial LDAP probes (measured ~9.5 min on prod). The SQL pull is
     * cheap; the enrichment isn't. Requests see {@code ldapStatus=UNKNOWN}
     * (which renders as no badge in the UI) until the background task fills
     * in real statuses and re-persists the snapshot. The hourly rebuild also
     * uses this executor so it never blocks read paths.
     */
    private final java.util.concurrent.ExecutorService ldapEnrichmentExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ims-ldap-enrich");
                t.setDaemon(true);
                return t;
            });
    private final Set<Integer> enrichmentInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class CachedDocs {
        public List<DocRow> rows;
        public long fetchedAt;
        public CachedDocs() {}  // for Jackson
        CachedDocs(List<DocRow> rows, long fetchedAt) {
            this.rows = rows; this.fetchedAt = fetchedAt;
        }
    }

    /** On-disk snapshot envelope. */
    public static final class DocsCacheSnapshot {
        public int version;
        public String savedAt;
        public Map<Integer, CachedDocs> buckets = new HashMap<>();
    }

    @PostConstruct
    public void loadDocsCacheFromDisk() {
        File f = new File(DOCS_CACHE_FILE);
        if (!f.exists()) {
            LOG.info("[IMS-CACHE] No on-disk snapshot at " + DOCS_CACHE_FILE
                + " — cache will warm on first /data call");
            return;
        }
        try {
            DocsCacheSnapshot snap = cacheMapper.readValue(f, DocsCacheSnapshot.class);
            if (snap == null || snap.buckets == null) return;
            synchronized (this) {
                for (Map.Entry<Integer, CachedDocs> e : snap.buckets.entrySet()) {
                    if (e.getValue() != null && e.getValue().rows != null) {
                        docsCache.put(e.getKey(), e.getValue());
                    }
                }
            }
            int totalRows = snap.buckets.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(c -> c.rows == null ? 0 : c.rows.size())
                    .sum();
            LOG.info("[IMS-CACHE] Loaded snapshot from " + DOCS_CACHE_FILE
                + " — buckets=" + snap.buckets.keySet()
                + " totalRows=" + totalRows
                + " savedAt=" + snap.savedAt);
        } catch (Exception e) {
            LOG.warning("[IMS-CACHE] Failed to load snapshot — will rebuild on first /data call: " + e.getMessage());
        }
    }

    /**
     * Persist the current in-memory cache to disk. Atomic via temp-file + move
     * so a half-written file can never be loaded on the next startup. Called
     * after every fresh pull (docsWithCache) and after every patch
     * (patchOwnersInCache).
     */
    private synchronized void persistDocsCacheToDisk() {
        try {
            DocsCacheSnapshot snap = new DocsCacheSnapshot();
            snap.version = DOCS_CACHE_SNAPSHOT_VERSION;
            snap.savedAt = Instant.now().toString();
            snap.buckets = new HashMap<>(docsCache);
            File out = new File(DOCS_CACHE_FILE);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(DOCS_CACHE_FILE + ".tmp");
            cacheMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, snap);
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("[IMS-CACHE] Failed to persist snapshot to " + DOCS_CACHE_FILE + ": " + e.getMessage());
        }
    }

    /**
     * Hourly background rebuild. For every bucket the app has been asked for
     * since startup, re-pull from Agile and atomically swap into the in-memory
     * cache, then persist. Skips when nothing has been queried yet — no point
     * pre-warming windows nobody uses.
     *
     * <p>Gated like every other scheduled service in the module by the
     * project's {@code app.scheduling.disabled} flag (we run with scheduling
     * disabled on local, so this never fires there).
     */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduledDocsCacheRebuild() {
        Set<Integer> bucketsToRebuild;
        synchronized (this) {
            bucketsToRebuild = new LinkedHashSet<>(docsCache.keySet());
        }
        if (bucketsToRebuild.isEmpty()) {
            LOG.info("[IMS-CACHE] Hourly tick — no buckets to rebuild (cache cold)");
            return;
        }
        long t0 = System.currentTimeMillis();
        LOG.info("[IMS-CACHE] Hourly rebuild starting for buckets=" + bucketsToRebuild);
        for (Integer days : bucketsToRebuild) {
            try {
                List<DocRow> fresh = pullDocsDueWithin(days);
                long fetchedAt = System.currentTimeMillis();
                synchronized (this) {
                    docsCache.put(days, new CachedDocs(fresh, fetchedAt));
                }
                LOG.info("[IMS-CACHE] Rebuilt bucket days=" + days + " rows=" + fresh.size()
                    + " — queuing LDAP enrichment");
                scheduleLdapEnrichment(days, fresh);
            } catch (Exception e) {
                LOG.warning("[IMS-CACHE] Hourly rebuild failed for days=" + days + ": " + e.getMessage());
            }
        }
        persistDocsCacheToDisk();
        LOG.info("[IMS-CACHE] Hourly rebuild done in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private List<DocRow> docsWithCache(int daysAhead) {
        synchronized (this) {
            CachedDocs c = docsCache.get(daysAhead);
            long now = System.currentTimeMillis();
            if (c != null && (now - c.fetchedAt) < DOCS_CACHE_TTL_MS) {
                return c.rows;
            }
        }
        // Cache miss or TTL expired — pull outside the monitor so other buckets
        // and read paths aren't blocked while the heavy SQL runs.
        long t0 = System.currentTimeMillis();
        List<DocRow> fresh = pullDocsDueWithin(daysAhead);
        LOG.info("[IMS-CACHE] SQL pull for days=" + daysAhead + " returned " + fresh.size()
            + " rows in " + (System.currentTimeMillis() - t0) + " ms");
        long fetchedAt = System.currentTimeMillis();
        synchronized (this) {
            docsCache.put(daysAhead, new CachedDocs(fresh, fetchedAt));
        }
        // Persist the SQL result immediately so a JVM restart skips the SQL
        // even if LDAP enrichment is still pending. Owners will have null
        // ldapStatus — ownersWithStatus emits UNKNOWN for those, which the UI
        // renders as no badge (the visible badges are only DISABLED / NOT_FOUND).
        persistDocsCacheToDisk();
        // Kick LDAP enrichment to the background so the request thread
        // returns immediately. Caller gets the rows now; badges appear on
        // the next refresh after the executor finishes. We pass `fresh`
        // EXPLICITLY so the executor operates on the rows we just pulled —
        // re-reading docsCache here was racy under concurrent /data hits
        // (executor woke up while another thread's persist was still in
        // flight and saw "empty"), which lost LDAP enrichment entirely and
        // left the "N owners need new" banner stuck at 0. See 2026-06-04 fix.
        scheduleLdapEnrichment(daysAhead, fresh);
        return fresh;
    }

    /**
     * Queue a background LDAP enrichment for {@code rows}. The rows reference
     * is passed in so the executor cannot race with a concurrent SQL pull or
     * cache replacement — it operates on the exact list the caller just
     * pulled. After enrichment, the executor writes those rows back into
     * {@code docsCache} (overwriting whatever the bucket currently holds for
     * {@code daysAhead}) and re-persists the snapshot, so the on-disk file
     * always reflects enriched data once an enrichment cycle finishes.
     *
     * <p>{@link #enrichmentInFlight} still de-dups so rapid /data hits don't
     * queue duplicate enrichment passes, but the dedup now means "we already
     * have a pass running on these rows", not "we already started a pass
     * that may have lost its reference".
     */
    private void scheduleLdapEnrichment(int daysAhead, List<DocRow> rows) {
        if (rows == null || rows.isEmpty()) {
            LOG.info("[IMS-CACHE] LDAP enrichment skipped — empty row list for days=" + daysAhead);
            return;
        }
        if (!enrichmentInFlight.add(daysAhead)) {
            LOG.info("[IMS-CACHE] LDAP enrichment for days=" + daysAhead
                + " already in flight — skipping duplicate request");
            return;
        }
        ldapEnrichmentExec.submit(() -> {
            try {
                enrichOwnersLdapStatus(rows);
                long fetchedAt = System.currentTimeMillis();
                synchronized (this) {
                    docsCache.put(daysAhead, new CachedDocs(rows, fetchedAt));
                }
                persistDocsCacheToDisk();
            } catch (Exception e) {
                LOG.warning("[IMS-CACHE] Background LDAP enrichment failed for days="
                    + daysAhead + ": " + e.getMessage());
            } finally {
                enrichmentInFlight.remove(daysAhead);
            }
        });
    }

    /**
     * Bake the LDAP probe result into each owner's {@code ldapStatus} field so
     * subsequent reads serve from the snapshot. Called from the background
     * executor after a fresh pull or by the hourly rebuild. Caller is
     * responsible for re-persisting the snapshot after this returns.
     */
    private void enrichOwnersLdapStatus(List<DocRow> docs) {
        if (docs == null || docs.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        int probed = 0;
        for (DocRow d : docs) {
            if (d.owners == null) continue;
            for (OwnerRef o : d.owners) {
                LdapManagerLookup.LdapStatus s = (o.email == null || o.email.isEmpty())
                        ? LdapManagerLookup.LdapStatus.UNKNOWN
                        : managerLookup.checkUserStatus(o.email);
                o.ldapStatus = s.name();
                probed++;
            }
        }
        LOG.info("[IMS-CACHE] Enriched LDAP status for " + probed + " owners in "
            + (System.currentTimeMillis() - t0) + " ms");
    }

    /**
     * Force-rebuild on next read AND drop the on-disk snapshot. Wired to the
     * Refresh button's {@code ?refresh=true} path. Normal mutations (send /
     * respond / cancel) don't need this — they overlay on top of the rows
     * via queueStore. Owner reassignment uses {@link #patchOwnersInCache}
     * instead so the user doesn't pay the rebuild cost after clicking Save.
     */
    public synchronized void invalidateDocsCache() {
        docsCache.clear();
        File f = new File(DOCS_CACHE_FILE);
        if (f.exists() && !f.delete()) {
            LOG.warning("[IMS-CACHE] Failed to delete on-disk snapshot at " + DOCS_CACHE_FILE
                + " — it'll be overwritten on next persist");
        }
    }

    /**
     * Read the cached row for {@code docNumber} from any bucket. Returns the
     * actual cached DocRow (not a copy) — callers MUST treat it as read-only.
     * Used by the owner-reassign email path to capture the pre-patch owner
     * list and the doc metadata (description, drrNumber, nextReviewDate)
     * without re-running the heavy Agile query.
     */
    public synchronized DocRow findCachedDoc(String docNumber) {
        if (docNumber == null || docNumber.isEmpty()) return null;
        for (CachedDocs c : docsCache.values()) {
            if (c == null || c.rows == null) continue;
            for (DocRow d : c.rows) {
                if (docNumber.equals(d.docNumber)) return d;
            }
        }
        return null;
    }

    /**
     * Surgically replace the owner list on the cached row for {@code docNumber}
     * across every bucket, then persist. Called by
     * {@code POST /api/ims-review/admin/owner} after the Agile write succeeds,
     * so the next /data fetch sees the new owners without paying the 100+
     * second rebuild cost. Returns the number of cached rows patched.
     */
    public synchronized int patchOwnersInCache(String docNumber, List<OwnerRef> newOwners) {
        if (docNumber == null || docNumber.isEmpty()) return 0;
        int patched = 0;
        for (CachedDocs c : docsCache.values()) {
            if (c == null || c.rows == null) continue;
            for (DocRow d : c.rows) {
                if (docNumber.equals(d.docNumber)) {
                    d.owners = (newOwners == null) ? new ArrayList<>() : new ArrayList<>(newOwners);
                    patched++;
                }
            }
        }
        if (patched > 0) {
            persistDocsCacheToDisk();
            LOG.info("[IMS-CACHE] Patched owners for docNumber=" + docNumber + " across " + patched + " bucket row(s)");
        }
        return patched;
    }

    /** True when a document's style marks it as never needing a DRR — mirrors
     *  DocumentReview.java (Specs / Drawing / Record-for-dead-document). Match is
     *  case-insensitive and collapses runs of whitespace/underscores so the config
     *  token "Record - for dead document - buy off report" matches the Agile value
     *  regardless of spacing. Fail-open: null/blank style => false. */
    public static boolean isDrrExempt(String style, java.util.List<String> exemptTokens) {
        if (style == null || exemptTokens == null || exemptTokens.isEmpty()) return false;
        String norm = style.toLowerCase().replaceAll("[\\s_]+", " ").trim();
        for (String tok : exemptTokens) {
            if (tok == null || tok.trim().isEmpty()) continue;
            String t = tok.toLowerCase().replaceAll("[\\s_]+", " ").trim();
            if (norm.contains(t)) return true;
        }
        return false;
    }

    @org.springframework.beans.factory.annotation.Value("${app.ims-review.drr-exempt-styles:Specs,Drawing,Record - for dead document - buy off report}")
    private String drrExemptStylesRaw;

    @org.springframework.beans.factory.annotation.Value("${app.ims-review.training-doc-url:}")
    private String trainingDocUrl;

    /** Guardrail: when false, the dashboard "Send New DRR" action is blocked
     *  (server-side reject + the button is hidden). Set false in Prod until the
     *  DRR-creation flow is cleared for go-live (documentation / training /
     *  sign-offs); QA stays true so testing continues. */
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.drr-creation-enabled:true}")
    private boolean drrCreationEnabled;

    public boolean isDrrCreationEnabled() { return drrCreationEnabled; }

    private java.util.List<String> drrExemptStyles() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : (drrExemptStylesRaw == null ? "" : drrExemptStylesRaw).split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    /** Stripped-down doc row used by the admin view + send flow. */
    public static final class DocRow {
        public String docNumber, drrNumber, description, lifecyclePhase, rev, documentType, documentStyle;
        public String nextReviewDate;
        /** DRR change metadata (from the pc CTE in pullDocsDueWithin):
         *  drrStatus  = Agile workflow status node name (e.g. "Pending", "CCB").
         *  drrOwner   = DRR change originator, "Last, First".
         *  drrCreateDate = DRR change create_date as YYYY-MM-DD. */
        public String drrStatus, drrOwner, drrCreateDate;
        public List<OwnerRef> owners = new ArrayList<>();
    }
    public static final class OwnerRef {
        public String displayName;
        public String email;
        public String loginId;
        /** LDAP probe result (ACTIVE / DISABLED / NOT_FOUND / UNKNOWN) baked
         *  into the cache so the per-/data LDAP enrichment can be skipped.
         *  Filled by enrichOwnersLdapStatus when rows are pulled fresh from
         *  Agile; refreshed on the hourly rebuild. May be null on legacy
         *  snapshot files (older format) — readers fall back to a live probe
         *  in that case. */
        public String ldapStatus;
    }

    /** Returned by {@link #getLatestUpload} — bytes + a friendly filename
     *  for the Content-Disposition header on the download endpoint. */
    public static final class UploadFile {
        public byte[] bytes;
        public String filename;
    }

    // ------------------------------------------------------------------
    // Read paths — admin view + DO/DM card view
    // ------------------------------------------------------------------

    /** Admin view: docs in the review window with their queue status (if any).
     *  Back-compat overload — uses the configured default for max-overdue-years
     *  ({@link #defaultMaxOverdueYears}). */
    public Map<String, Object> dataForAdmin(int daysAhead) {
        return dataForAdmin(daysAhead, defaultMaxOverdueYears);
    }

    /** Admin view, with explicit max-overdue cap. {@code maxOverdueYears == 0}
     *  means "unlimited" — show every overdue doc regardless of how ancient
     *  the next-review date is. Filter is applied AFTER the SQL pull so the
     *  cache stays keyed by daysAhead alone (avoids fragmenting the cache
     *  across every distinct (days, years) pair). The pre-filter row count
     *  is returned as {@code rawRowCount} so the UI can show "showing N of
     *  M, M-N too overdue". */
    public Map<String, Object> dataForAdmin(int daysAhead, int maxOverdueYears) {
        List<DocRow> rawDocs = docsWithCache(daysAhead);
        // Apply the max-overdue filter. Anything with no parseable
        // nextReviewDate is kept (we don't have a basis to drop it).
        long cutoffMs = 0L;
        boolean applyCutoff = maxOverdueYears > 0;
        if (applyCutoff) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.YEAR, -maxOverdueYears);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            cutoffMs = cal.getTimeInMillis();
        }
        int droppedAsTooOverdue = 0;
        List<DocRow> docs;
        if (applyCutoff) {
            docs = new ArrayList<>(rawDocs.size());
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd");
            for (DocRow d : rawDocs) {
                if (d.nextReviewDate == null || d.nextReviewDate.isEmpty()) {
                    docs.add(d);
                    continue;
                }
                try {
                    java.util.Date parsed = iso.parse(d.nextReviewDate);
                    if (parsed.getTime() < cutoffMs) {
                        droppedAsTooOverdue++;
                        continue;
                    }
                } catch (Exception parseErr) {
                    // unparseable — keep
                }
                docs.add(d);
            }
        } else {
            docs = rawDocs;
        }
        Map<String, ImsReviewQueueStore.QueueItem> byKey = new HashMap<>();
        for (ImsReviewQueueStore.QueueItem q : queueStore.all()) {
            byKey.put(q.docNumber + "|" + q.drrNumber, q);
        }
        // Keep toolkit-tracked reviews visible after they fall out of the
        // review-date window. Implementing a DCO advances the document's Next
        // Review Date years out (e.g. DCO-523133 pushed 03-32-WW-02-00094 to
        // 2029), which drops it from pullDocsDueWithin — so a just-closed review
        // would otherwise disappear from every tile, including Closed. Union in
        // the queueStore docs that the window pull didn't already return.
        java.util.Set<String> presentDocs = new java.util.HashSet<>();
        for (DocRow d : docs) presentDocs.add(d.docNumber);
        java.util.LinkedHashSet<String> toolkitOnlyDocs = new java.util.LinkedHashSet<>();
        Map<String, String> drrByDoc = new HashMap<>();   // representative queue DRR# per doc (last-wins, mirrors byKey)
        for (ImsReviewQueueStore.QueueItem q : queueStore.all()) {
            if (q.docNumber != null && !q.docNumber.trim().isEmpty()
                    && !presentDocs.contains(q.docNumber)) {
                toolkitOnlyDocs.add(q.docNumber);
                drrByDoc.put(q.docNumber, nvl(q.drrNumber));
            }
        }
        if (!toolkitOnlyDocs.isEmpty()) {
            List<DocRow> extra = pullDocsByNumbers(toolkitOnlyDocs);
            if (!extra.isEmpty()) {
                // Align each extra row's DRR# with the queue key so the status /
                // agileDco overlay (byKey lookup below) attaches — that's what
                // marks the row as toolkit-tracked (New group) and lets the DCO
                // status drive the Closed classification.
                for (DocRow d : extra) {
                    String qDrr = drrByDoc.get(d.docNumber);
                    if (qDrr != null && !qDrr.isEmpty()) d.drrNumber = qDrr;
                }
                docs = new ArrayList<>(docs);   // fresh list — never mutate the cached rawDocs
                docs.addAll(extra);
                LOG.info("[IMS-REVIEW] unioned " + extra.size()
                        + " out-of-window toolkit-tracked doc(s) into the dashboard");
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int notSent = 0, inFlight = 0, closed = 0, needHelp = 0;
        int kpiOwnerDisabled = 0, kpiOwnerMissing = 0, kpiAllOwnersLeft = 0;
        int kpiLegacyTotal = 0, kpiLegacyValidOwner = 0, kpiLegacyNoValidOwner = 0;
        for (DocRow d : docs) {
            ImsReviewQueueStore.QueueItem q = byKey.get(d.docNumber + "|" + d.drrNumber);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docNumber", d.docNumber);
            row.put("description", d.description);
            row.put("lifecyclePhase", d.lifecyclePhase);
            row.put("rev", d.rev);
            row.put("documentType", d.documentType);
            row.put("documentStyle", d.documentStyle);
            row.put("drrExempt", isDrrExempt(d.documentStyle, drrExemptStyles()));
            row.put("ownerDisplay", ownerDisplay(d.owners));
            // Per-owner status objects for the indicator badges. Each entry:
            // {displayName, email, loginId, ldapStatus}. ldapStatus is the
            // LDAP probe enum (ACTIVE/DISABLED/NOT_FOUND/UNKNOWN). The UI
            // shows an indicator only when status != ACTIVE.
            row.put("owners", ownersWithStatus(d.owners));
            row.put("nextReviewDate", d.nextReviewDate);
            row.put("drrNumber", d.drrNumber);
            // DRR created — prefer the Agile change create_date (d.drrCreateDate,
            // from the pc CTE), falling back to the earliest queue-event timestamp
            // (when the DRR first entered the toolkit) only when the DB value is
            // blank. Used by the dashboard's "DRR Created" column + the "after
            // go-live" default filter. Empty when neither source is available
            // (no DRR change and the doc was never sent).
            row.put("drrCreated", (d.drrCreateDate != null && !d.drrCreateDate.isEmpty())
                        ? d.drrCreateDate
                        : (q == null ? "" : earliestEventTs(q)));
            row.put("drrStatus", nvl(d.drrStatus));
            row.put("drrOwner", nvl(d.drrOwner));
            String statusStr = (q == null) ? ImsReviewQueueStore.Status.NOT_SENT.name() : q.status.name();
            row.put("status", statusStr);
            row.put("lastUpdated", q == null ? "" : q.lastUpdated);
            row.put("currentRecipient", q == null ? "" : nvl(q.currentRecipient));
            row.put("currentRole", q == null ? "" : nvl(q.currentRole));
            // Surface the latest DO-uploaded attachment (Needs Change revised
            // file, or Need Help attachment) so the dashboard can render a
            // 📎 link that hits /api/ims-review/upload?docNumber=… without
            // needing a second fetch per row.
            Map<String, Object> upMeta = latestUploadMeta(q);
            if (upMeta != null) row.put("uploadedAttachment", upMeta);
            // Surface the Need-Help message the DO typed so the dashboard can
            // show it inline. Previously the note was only delivered in the DCC
            // email and never visible in the toolkit (green-list A72 bug).
            Map<String, Object> helpMeta = latestHelpNote(q);
            if (helpMeta != null) row.put("helpNote", helpMeta);
            // Surface the DCO# created by the Needs-Change cascade (if any)
            // so the dashboard can render it as a clickable Agile link in
            // the Status column. Walks history backward for the latest event
            // that has a non-null agileDco.
            String dco = latestAgileDco(q);
            if (dco != null && !dco.isEmpty()) row.put("agileDco", dco);
            String dcoTs = latestAgileDcoTs(q);
            if (dcoTs != null && !dcoTs.isEmpty()) row.put("dcoCreated", dcoTs.substring(0, Math.min(10, dcoTs.length())));
            // 2026-06-05 — semantic refresh per Vikas:
            //   * Closed = both DRR AND DCO truly Released. The only toolkit
            //     status that means "no DCO at all" is DM_APPROVED (DM signed
            //     off no-change). For DO_NEEDS_CHANGE the DCO was just
            //     CREATED (and possibly auto-submitted) — until it reaches
            //     Released that's still "In Flight". CANCELLED also terminal
            //     so counts as Closed.
            //   * In Flight now includes DO_NEEDS_CHANGE — the DCO is sitting
            //     in Submit/CCB awaiting workflow approval.
            // The toolkit doesn't currently track DRR/DCO post-Submit Agile
            // status, so we can't gate on "DCO actually Released" — using
            // DM_APPROVED + CANCELLED as the closed proxy is the closest
            // accurate read with what we have.
            switch (ImsReviewQueueStore.Status.valueOf(statusStr)) {
                case NOT_SENT: notSent++; break;
                case SENT_TO_DO:
                case SENT_TO_DM:
                case DO_NEEDS_CHANGE: inFlight++; break;
                case DM_APPROVED:
                case CANCELLED: closed++; break;
                case DO_NEED_HELP: needHelp++; break;
            }
            // Row-level owner-health flags for KPI tiles + indicator-click filter
            boolean hasDisabled = false, hasMissing = false, hasValidOwner = false;
            int ownerCount = 0;
            for (Object o : (List<?>) row.get("owners")) {
                String s = (String) ((Map<?,?>) o).get("ldapStatus");
                ownerCount++;
                if ("DISABLED".equals(s)) hasDisabled = true;
                if ("NOT_FOUND".equals(s)) hasMissing = true;
                // "Valid owner" = ACTIVE in AD (sign-in works), OR UNKNOWN
                // (LDAP probe hasn't run yet — treat as innocent so we don't
                // flag actionable docs as orphaned during enrichment).
                if ("ACTIVE".equals(s) || s == null || "UNKNOWN".equals(s)) hasValidOwner = true;
            }
            // "All owners left" = at least one owner, and EVERY owner is
            // NOT_FOUND. DISABLED counts as "still at company but disabled"
            // so allOwnersLeft stays false if any owner is DISABLED.
            boolean allOwnersLeft = ownerCount > 0 && !hasValidOwner && hasMissing && !hasDisabled;
            boolean hasDrr = d.drrNumber != null && !d.drrNumber.trim().isEmpty();
            // Surface the row-level flags so the client's segment classifier
            // doesn't have to re-derive them from the owners array on every render.
            row.put("hasDrr", hasDrr);
            row.put("hasValidOwner", hasValidOwner);
            row.put("allOwnersLeft", allOwnersLeft);
            if (hasDisabled) kpiOwnerDisabled++;
            if (hasMissing)  kpiOwnerMissing++;
            // The banner ("N documents need a new owner") should only count
            // docs that are actionable (have a DRR) AND have NO valid owner
            // at all. Without these gates the banner exaggerates: it counts
            // legacy docs (no DRR, can't be sent anyway) and partially-orphaned
            // docs (one owner left, others still here — still actionable).
            if (hasDrr && allOwnersLeft) kpiAllOwnersLeft++;
            // Legacy-bucket counts — docs without a DRR, split by whether
            // they still have at least one valid owner. Used by the new
            // "Legacy Documents" section's sub-filter pills.
            if (!hasDrr) {
                kpiLegacyTotal++;
                if (hasValidOwner) kpiLegacyValidOwner++;
                else kpiLegacyNoValidOwner++;
            }
            rows.add(row);
        }
        // Fill in DCO Status / Owner / Create-date for rows that carry a
        // toolkit-created DCO number (from the queue). Looked up by change
        // number in the same Agile DB — no SDK call.
        enrichDcoMeta(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        out.put("kpiInWindow", rows.size());
        out.put("kpiNotSent", notSent);
        out.put("kpiInFlight", inFlight);
        out.put("kpiNeedHelp", needHelp);
        out.put("kpiClosed", closed);
        out.put("kpiOwnerDisabled", kpiOwnerDisabled);
        out.put("kpiOwnerMissing", kpiOwnerMissing);
        out.put("kpiAllOwnersLeft", kpiAllOwnersLeft);
        out.put("kpiLegacyTotal", kpiLegacyTotal);
        out.put("kpiLegacyValidOwner", kpiLegacyValidOwner);
        out.put("kpiLegacyNoValidOwner", kpiLegacyNoValidOwner);
        out.put("daysAhead", daysAhead);
        out.put("maxOverdueYears", maxOverdueYears);
        out.put("rawRowCount", rawDocs.size());
        out.put("droppedAsTooOverdue", droppedAsTooOverdue);
        // Expose the Agile webclient base URL so the dashboard can render
        // doc/DRR/DCO numbers as clickable links — same /object/<TYPE>/<num>
        // pattern the email service uses (Items also append /tab/13).
        out.put("agileWebclientUrl", emailService.agileWebclientUrl);
        out.put("trainingDocUrl", trainingDocUrl == null ? "" : trainingDocUrl);
        out.put("drrCreationEnabled", drrCreationEnabled);
        return out;
    }

    /** Earliest event timestamp in a queue item's history = when the DRR first
     *  entered the toolkit. ISO-8601 strings sort lexicographically, so a string
     *  min is correct. Returns "" when there's no history. */
    private static String earliestEventTs(ImsReviewQueueStore.QueueItem q) {
        if (q == null || q.history == null || q.history.isEmpty()) return "";
        String min = null;
        for (ImsReviewQueueStore.Event e : q.history) {
            if (e == null || e.ts == null || e.ts.isEmpty()) continue;
            if (min == null || e.ts.compareTo(min) < 0) min = e.ts;
        }
        return min == null ? "" : min;
    }

    /** Walk an item's history backward for the latest event with
     *  {@code agileDco} set. Surfaces the DCO number created by the
     *  Needs-Change cascade for the dashboard render. */
    private String latestAgileDco(ImsReviewQueueStore.QueueItem q) {
        if (q == null || q.history == null) return null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.agileDco != null && !ev.agileDco.trim().isEmpty()) return ev.agileDco.trim();
        }
        return null;
    }

    /** Timestamp of the event that created the latest DCO — surfaces as the
     *  "DCO Create Date" column. Mirrors latestAgileDco() but returns ev.ts. */
    private String latestAgileDcoTs(ImsReviewQueueStore.QueueItem q) {
        if (q == null || q.history == null) return null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.agileDco != null && !ev.agileDco.trim().isEmpty()) return ev.ts;
        }
        return null;
    }

    /** Fill in DCO Status / Owner / Create-date for rows that carry a
     *  toolkit-created DCO number (row "agileDco", taken from the queue).
     *  Looks the numbers up by change_number in the same Agile DB the doc
     *  query uses — no SDK call. DCO owner = change originator (c.owner, the
     *  analyst, is unset for these freshly-created DCOs). Fail-soft: on any
     *  SQL error the placeholder "—" stays. */
    private void enrichDcoMeta(List<Map<String, Object>> rows) {
        java.util.LinkedHashSet<String> dcos = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object d = r.get("agileDco");
            if (d != null && !d.toString().trim().isEmpty()) dcos.add(d.toString().trim());
        }
        if (dcos.isEmpty()) return;
        Map<String, String[]> meta = new java.util.HashMap<>(); // num -> {status, owner, createDate}
        List<String> all = new ArrayList<>(dcos);
        final int CHUNK = 1000;
        try (Connection conn = agileDataSource.getConnection()) {
            for (int i = 0; i < all.size(); i += CHUNK) {
                List<String> batch = all.subList(i, Math.min(all.size(), i + CHUNK));
                StringBuilder in = new StringBuilder();
                for (int j = 0; j < batch.size(); j++) in.append(j == 0 ? "?" : ",?");
                String sql =
                    "SELECT c.change_number, sn.name AS dco_status, " +
                    "       NVL(orig.last_name,'') || ', ' || NVL(orig.first_name,'') AS dco_owner, " +
                    "       TO_CHAR(c.create_date,'YYYY-MM-DD') AS dco_create_date " +
                    "FROM   agile.change c " +
                    "JOIN   agile.nodetable sn ON sn.id = c.status " +
                    "LEFT JOIN agile.agileuser orig ON orig.id = c.originator " +
                    "WHERE  c.change_number IN (" + in + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(60);
                    for (int j = 0; j < batch.size(); j++) ps.setString(j + 1, batch.get(j));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            meta.put(rs.getString("change_number"), new String[]{
                                nvl(rs.getString("dco_status")),
                                nvl(rs.getString("dco_owner")),
                                nvl(rs.getString("dco_create_date"))
                            });
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[IMS-REVIEW] DCO meta enrich failed: " + e.getMessage());
            return; // fail-soft: leave the "—" placeholder
        }
        for (Map<String, Object> r : rows) {
            Object d = r.get("agileDco");
            if (d == null) continue;
            String[] m = meta.get(d.toString().trim());
            if (m == null) continue;
            if (!m[0].isEmpty()) r.put("dcoStatus", m[0]);
            // Skip the empty ", " produced when both name parts are null.
            if (!m[1].replace(",", "").trim().isEmpty()) r.put("dcoOwner", m[1]);
            if (!m[2].isEmpty()) r.put("dcoCreated", m[2]); // real create_date beats the queue ts
        }
    }

    /** Walk an item's history backward for the latest Need-Help event that
     *  carries a note, returning {note, by, at}. Surfaces the help message the
     *  Document Owner typed so the dashboard can render it inline — it was
     *  previously only delivered in the DCC "Need Help" email and never shown
     *  in the toolkit (green-list A72). */
    private Map<String, Object> latestHelpNote(ImsReviewQueueStore.QueueItem q) {
        if (q == null || q.history == null) return null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.type == ImsReviewQueueStore.EventType.DO_RESPONSE_NEED_HELP
                    && ev.note != null && !ev.note.trim().isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("note", ev.note.trim());
                m.put("by", (ev.verifiedDisplayName != null && !ev.verifiedDisplayName.isEmpty())
                        ? ev.verifiedDisplayName
                        : (ev.actor != null ? nvl(ev.actor.displayName) : ""));
                m.put("at", nvl(ev.ts));
                return m;
            }
        }
        return null;
    }

    /** Find the latest event in the queue history for {@code docNumber} that
     *  has an {@code uploadFile} attached (the file the DO uploaded on the
     *  response page — Needs Change revised doc, or Need Help attachment).
     *  Reads the file bytes off disk and returns them with a friendly
     *  filename. Returns null if there's no queue item, no upload event,
     *  or the file is missing. */
    public UploadFile getLatestUpload(String docNumber, String drrNumber, String storedName) {
        if (docNumber == null || docNumber.trim().isEmpty()) return null;
        ImsReviewQueueStore.QueueItem q = null;
        if (drrNumber != null && !drrNumber.trim().isEmpty()) {
            q = queueStore.find(docNumber, drrNumber);
        }
        if (q == null) {
            // Caller didn't know the DRR (e.g. dashboard 📎 click without DRR
            // context) — scan for the only queue item matching docNumber.
            for (ImsReviewQueueStore.QueueItem qi : queueStore.all()) {
                if (docNumber.equals(qi.docNumber)) { q = qi; break; }
            }
        }
        if (q == null) return null;
        // Build the candidate list — prefer uploadFiles (multi), fall back to
        // singular uploadFile. Walk history backward and stop at the latest
        // event that has uploads. If storedName is supplied, pick that one;
        // otherwise return the first file in the latest upload event.
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            List<String> rels = (ev.uploadFiles != null && !ev.uploadFiles.isEmpty())
                    ? ev.uploadFiles
                    : (ev.uploadFile != null && !ev.uploadFile.isEmpty()
                            ? Collections.singletonList(ev.uploadFile)
                            : null);
            if (rels == null) continue;
            String pickedRel = null;
            if (storedName != null && !storedName.trim().isEmpty()) {
                String want = storedName.trim();
                for (String rel : rels) {
                    String basename = rel.substring(rel.lastIndexOf('/') + 1);
                    if (basename.equalsIgnoreCase(want)) { pickedRel = rel; break; }
                }
                if (pickedRel == null) continue;   // not in this event, try older
            } else {
                pickedRel = rels.get(0);
            }
            try {
                String rel = pickedRel.startsWith("uploads/")
                        ? pickedRel.substring("uploads/".length())
                        : pickedRel;
                java.nio.file.Path p = java.nio.file.Paths.get(queueStore.getUploadDir(), rel);
                if (!java.nio.file.Files.exists(p)) continue;
                UploadFile out = new UploadFile();
                out.bytes = java.nio.file.Files.readAllBytes(p);
                out.filename = friendlyUploadName(p.getFileName().toString());
                return out;
            } catch (Exception e) {
                LOG.warning("[IMS-REVIEW] failed to read upload " + pickedRel + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** Cheap probe: does this queue item have any uploaded attachment? Used
     *  by dataForAdmin to surface a dashboard 📎 link without loading bytes.
     *  Multi-file uploads (Need Help with multiple attachments) return a
     *  {@code files} array of {storedName, displayName}; the singular
     *  {@code filename} is kept for back-compat with older renders. */
    private Map<String, Object> latestUploadMeta(ImsReviewQueueStore.QueueItem q) {
        if (q == null) return null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            // Prefer the multi-file list when present
            List<String> rels = (ev.uploadFiles != null && !ev.uploadFiles.isEmpty())
                    ? ev.uploadFiles
                    : (ev.uploadFile != null && !ev.uploadFile.isEmpty()
                            ? Collections.singletonList(ev.uploadFile)
                            : null);
            if (rels == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, String>> files = new ArrayList<>();
            for (String rel : rels) {
                String storedName = rel.substring(rel.lastIndexOf('/') + 1);
                Map<String, String> f = new LinkedHashMap<>();
                f.put("storedName", storedName);
                f.put("filename", friendlyUploadName(storedName));
                files.add(f);
            }
            m.put("files", files);
            // Back-compat: legacy single-file consumers still read these
            m.put("filename", files.get(0).get("filename"));
            m.put("storedName", files.get(0).get("storedName"));
            m.put("eventType", ev.type == null ? "" : ev.type.name());
            m.put("uploadedAt", ev.ts);
            return m;
        }
        return null;
    }

    /** Strip the {@code docNumber-{ts}-{idx}-} prefix added at upload-save
     *  time so the dashboard 📎 label shows the user's original filename. */
    private static String friendlyUploadName(String storedName) {
        if (storedName == null) return "";
        // Match either "doc-ts-name.ext" (old) or "doc-ts-idx-name.ext" (new).
        int sep1 = storedName.indexOf('-');
        if (sep1 < 0) return storedName;
        int sep2 = storedName.indexOf('-', sep1 + 1);
        if (sep2 < 0) return storedName;
        int sep3 = storedName.indexOf('-', sep2 + 1);
        // If the segment between sep2+1 and sep3 is all digits, it's the
        // file-index from the new naming scheme; strip that too.
        if (sep3 > 0) {
            String maybeIdx = storedName.substring(sep2 + 1, sep3);
            if (maybeIdx.matches("\\d+")) return storedName.substring(sep3 + 1);
        }
        return storedName.substring(sep2 + 1);
    }

    /** Enrich each owner with their LDAP status for the admin grid. Reads
     *  {@code ldapStatus} straight from the cached {@link OwnerRef} — never
     *  hits LDAP on the request path. Background enrichment in
     *  {@link #scheduleLdapEnrichment} fills in the status after a fresh
     *  pull; until then, owners report UNKNOWN (the UI renders no badge for
     *  UNKNOWN, only for DISABLED / NOT_FOUND). */
    private List<Map<String, Object>> ownersWithStatus(List<OwnerRef> owners) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (owners == null) return out;
        for (OwnerRef o : owners) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("displayName", o.displayName);
            m.put("email", o.email);
            m.put("loginId", o.loginId);
            String status = o.ldapStatus;
            if (status == null || status.isEmpty()) status = LdapManagerLookup.LdapStatus.UNKNOWN.name();
            m.put("ldapStatus", status);
            out.add(m);
        }
        return out;
    }

    /** DO/DM card view. Two passes merged:
     *  <ol>
     *    <li><b>Mine to act on</b> — queue items where {@code currentRecipient}
     *        equals this user. Marked {@code requiresMyAction=true} so the UI
     *        shows the response buttons.</li>
     *    <li><b>Pending where I'm an owner</b> — any doc in the next 30 days
     *        where this user is listed in Document Owners, that isn't already
     *        covered above and isn't in a terminal state. The DO can see what
     *        else is in motion under their name (sent to another co-owner,
     *        awaiting their manager's approval, or not yet kicked off by DCC)
     *        without bouncing back to the admin table. Marked
     *        {@code requiresMyAction=false} so the UI shows a status pill
     *        instead of buttons.</li>
     *  </ol>
     *  Pass 2 uses the cached docs query — same 5-min TTL as admin /data — so
     *  it's free once anyone has loaded the admin table this session.
     */
    public Map<String, Object> myQueue(String userEmail) {
        String emailLower = userEmail == null ? "" : userEmail.trim().toLowerCase();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // ---------- Pass 1: items explicitly addressed to me ----------
        for (ImsReviewQueueStore.QueueItem q : queueStore.pendingFor(userEmail)) {
            DocRow d = lookupDoc(q.docNumber);
            Map<String, Object> r = buildCardRow(q, d, /*requiresMyAction*/ true);
            rows.add(r);
            seenKeys.add(key(q.docNumber, q.drrNumber));
        }

        // ---------- Pass 2: pending docs where I'm an owner ----------
        if (!emailLower.isEmpty()) {
            for (DocRow d : docsWithCache(30)) {
                boolean iAmOwner = false;
                for (OwnerRef o : d.owners) {
                    if (o.email != null && o.email.trim().toLowerCase().equals(emailLower)) {
                        iAmOwner = true; break;
                    }
                }
                if (!iAmOwner) continue;

                ImsReviewQueueStore.QueueItem existing = queueStore.find(d.docNumber, d.drrNumber);
                String drr = existing == null ? d.drrNumber : existing.drrNumber;
                if (seenKeys.contains(key(d.docNumber, drr))) continue;

                // Skip terminal states — those are closed, no longer pending.
                if (existing != null) {
                    switch (existing.status) {
                        case DM_APPROVED:
                        case DO_NEEDS_CHANGE:
                        case DO_NEED_HELP:
                        case CANCELLED:
                            continue;
                        default: break;
                    }
                }

                Map<String, Object> r = buildCardRow(existing, d, /*requiresMyAction*/ false);
                rows.add(r);
                seenKeys.add(key(d.docNumber, drr));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("count", rows.size());
        return out;
    }

    /** Build one card-view row from a (queue item, doc row) pair. Either can
     *  be null: {@code q==null} → NOT_SENT placeholder; {@code d==null} → doc
     *  no longer in the review window (rare; the queue item still surfaces). */
    private Map<String, Object> buildCardRow(ImsReviewQueueStore.QueueItem q,
                                             DocRow d, boolean requiresMyAction) {
        Map<String, Object> r = new LinkedHashMap<>();
        String status = (q == null) ? ImsReviewQueueStore.Status.NOT_SENT.name() : q.status.name();
        r.put("docNumber", q != null ? q.docNumber : (d == null ? "" : d.docNumber));
        r.put("drrNumber", q != null ? nvl(q.drrNumber) : (d == null ? "" : nvl(d.drrNumber)));
        r.put("status", status);
        r.put("role", q == null ? "DO" : nvl(q.currentRole));
        r.put("description", d == null ? "" : d.description);
        r.put("rev", d == null ? "" : d.rev);
        r.put("lifecyclePhase", d == null ? "" : d.lifecyclePhase);
        r.put("documentType", d == null ? "" : d.documentType);
        r.put("nextReviewDate", d == null ? "" : d.nextReviewDate);
        r.put("lastUpdated", q == null ? "" : nvl(q.lastUpdated));
        r.put("currentRecipient", q == null ? "" : nvl(q.currentRecipient));
        r.put("requiresMyAction", requiresMyAction);
        // DM cards carry the DO's prior response detail
        if (q != null && q.status == ImsReviewQueueStore.Status.SENT_TO_DM) {
            for (int i = q.history.size() - 1; i >= 0; i--) {
                ImsReviewQueueStore.Event ev = q.history.get(i);
                if (ev.type == ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE) {
                    r.put("doDisplayName", ev.actor == null ? "" : ev.actor.displayName);
                    r.put("doResponseDate", ev.ts);
                    break;
                }
            }
        }
        return r;
    }

    private static String key(String doc, String drr) {
        return (doc == null ? "" : doc) + "|" + (drr == null ? "" : drr);
    }

    // ------------------------------------------------------------------
    // Write paths — Send, DO/DM responses, Cancel
    // ------------------------------------------------------------------

    /** DCC clicks Send (or Resend) — emails the DO + appends SEND_TO_DO. */
    public ImsReviewQueueStore.QueueItem send(String docNumber, String drrNumber,
                                              String actorEmail, String actorDisplay) {
        DocRow d = lookupDoc(docNumber);
        if (d == null) throw new RuntimeException("Document not found: " + docNumber);
        // Backend safety net: if the caller didn't pass a DRR but the doc has
        // one in Agile, use it. The UI button passes drrNumber from the row;
        // direct API callers (curl, scripts) sometimes omit it.
        if (drrNumber == null || drrNumber.trim().isEmpty()) {
            if (d.drrNumber != null && !d.drrNumber.trim().isEmpty()) {
                drrNumber = d.drrNumber;
                LOG.info("[IMS-REVIEW] auto-resolved missing drrNumber for " + docNumber + " → " + drrNumber);
            }
        }

        // Resolve owner emails AND check LDAP active-state for each. The Agile
        // agileuser table keeps the email after a person leaves SanDisk, but
        // their AD account flips to disabled — so the inactive-DO detection has
        // to come from LDAP, not from agileuser presence.
        List<String> doEmails = new ArrayList<>();
        List<String> inactiveOwnerLabels = new ArrayList<>();
        String firstOwnerDisplayName = null;   // for the email greeting
        for (OwnerRef o : d.owners) {
            if (o.email == null || o.email.isEmpty()) continue;
            if (managerLookup.isUserInactive(o.email)) {
                String label = (o.displayName != null && !o.displayName.isEmpty())
                        ? o.displayName + " <" + o.email + ">"
                        : o.email;
                inactiveOwnerLabels.add(label);
                LOG.info("[IMS-REVIEW] DO " + o.email + " (" + o.displayName + ") is inactive in LDAP");
            } else {
                doEmails.add(o.email);
                if (firstOwnerDisplayName == null && o.displayName != null && !o.displayName.isEmpty()) {
                    firstOwnerDisplayName = o.displayName;
                }
            }
        }
        boolean doInactive = doEmails.isEmpty();  // all owners inactive (or no owners at all)

        // Pull attachments via plm-agile-service (degrades to "Unable to attach")
        AgileDocumentAttachmentsClient.Bundle bundle = agileAttachments.fetch(docNumber);

        // Generate the SEND token BEFORE composing the email so the per-action
        // URLs in the buttons carry it. Same token is then stamped on the
        // SEND_TO_DO event below so click-time lookup can find this row.
        String sendToken = java.util.UUID.randomUUID().toString();

        ImsReviewEmailService.Payload p = emailService.payloadForDoEmail(
                docNumber, d.description, d.rev, d.documentType, d.lifecyclePhase,
                d.nextReviewDate, drrNumber == null ? "" : drrNumber,
                doEmails, bundle.bytes, bundle.filename, bundle.fileCount, bundle.totalBytes,
                doInactive, inactiveOwnerLabels, firstOwnerDisplayName);
        p.token = sendToken;
        emailService.send(p);

        // Persist event with the same token.
        ImsReviewQueueStore.Event ev = baseEvent(ImsReviewQueueStore.EventType.SEND_TO_DO,
                docNumber, drrNumber, actorEmail, actorDisplay, "DCC");
        ev.recipients = doInactive
                ? Collections.singletonList(emailService.managersDl)
                : new ArrayList<>(doEmails);
        ev.fileCount = bundle.fileCount;
        ev.fileBytes = bundle.totalBytes;
        ev.attachedToEmail = bundle.ok && bundle.totalBytes <= ImsReviewEmailService.ATTACH_LIMIT_BYTES;
        ev.doInactive = doInactive;
        ev.token = sendToken;
        queueStore.append(ev);

        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_SEND",
                "doc=" + docNumber + " | drr=" + nvl(drrNumber) + " | recipients=" + ev.recipients
                + " | attached=" + ev.attachedToEmail + " | files=" + ev.fileCount
                + (doInactive ? " | doInactive=true" : ""));
        return queueStore.find(docNumber, drrNumber);
    }

    /** Create a DRR for a doc that has none, then send the review to its
     *  owner(s) via the existing send() flow. Validates first: the doc must
     *  not already have a DRR and must have at least one active owner.
     *  Returns {ok, drrNumber, status?, reason?, sendWarning?}. */
    public java.util.Map<String, Object> sendNewDrr(String docNumber, String actorEmail, String actorDisplay) {
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        if (!drrCreationEnabled) {
            res.put("ok", false);
            res.put("reason", "Creating DRRs from this site is turned off here. Please use the QA toolkit to create and test DRRs.");
            return res;
        }
        DocRow d = lookupDoc(docNumber);
        if (d == null) { res.put("ok", false); res.put("reason", "Document not found: " + docNumber); return res; }
        if (d.drrNumber != null && !d.drrNumber.trim().isEmpty()) {
            res.put("ok", false);
            res.put("reason", "Document already has a DRR (" + d.drrNumber + ").");
            return res;
        }
        // Valid owners = ACTIVE / UNKNOWN / null ldapStatus (can receive the review).
        java.util.List<String> ownerLogins = new java.util.ArrayList<>();
        for (OwnerRef o : d.owners) {
            String st = o.ldapStatus;
            boolean valid = (st == null || "ACTIVE".equals(st) || "UNKNOWN".equals(st));
            if (valid && o.loginId != null && !o.loginId.trim().isEmpty()) ownerLogins.add(o.loginId);
        }
        if (ownerLogins.isEmpty()) {
            res.put("ok", false);
            res.put("reason", "All document owners have left — reassign an owner before sending a DRR.");
            return res;
        }
        String corrId = java.util.UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBack.createDrr(docNumber, ownerLogins, actorEmail, corrId);
        Object drrObj = (r.body == null) ? null : r.body.get("drrNumber");
        if (!r.ok || drrObj == null || String.valueOf(drrObj).trim().isEmpty()) {
            res.put("ok", false);
            res.put("reason", r.errorReason != null ? r.errorReason : "DRR creation failed");
            return res;
        }
        String drrNumber = String.valueOf(drrObj).trim();
        res.put("drrNumber", drrNumber);
        // Send the review to the owner(s) via the existing flow.
        try {
            ImsReviewQueueStore.QueueItem q = send(docNumber, drrNumber, actorEmail, actorDisplay);
            res.put("ok", true);
            res.put("status", q == null ? null : q.status.name());
        } catch (RuntimeException sendErr) {
            // DRR exists; only the send failed. Surface as partial success.
            res.put("ok", true);
            res.put("sendWarning", sendErr.getMessage());
        }
        return res;
    }

    /**
     * DO/DM response handler. Validates the actor's email matches the
     * current recipient, then runs the matching flow.
     */
    /** Legacy single-file overload — wraps in a singleton list. Kept so the
     *  session-based admin act-as controller doesn't need to change. */
    public ImsReviewQueueStore.QueueItem respond(String docNumber, String drrNumber,
                                                 ImsReviewQueueStore.EventType type,
                                                 String actorEmail, String actorDisplay,
                                                 String note, MultipartFile uploadFile) {
        List<MultipartFile> files = (uploadFile == null || uploadFile.isEmpty())
                ? Collections.emptyList()
                : Collections.singletonList(uploadFile);
        return respond(docNumber, drrNumber, type, actorEmail, actorDisplay, note, files);
    }

    public synchronized ImsReviewQueueStore.QueueItem respond(String docNumber, String drrNumber,
                                                              ImsReviewQueueStore.EventType type,
                                                              String actorEmail, String actorDisplay,
                                                              String note, List<MultipartFile> uploadFiles) {
        ImsReviewQueueStore.QueueItem q = queueStore.find(docNumber, drrNumber);
        if (q == null) throw new RuntimeException("No queue item for " + docNumber + " / " + drrNumber);
        // Cross-check: actorEmail must be on the LATEST SEND event's recipient
        // list. Multi-owner SENDs (e.g. krati + vikas) put the first recipient
        // on q.currentRecipient but the full list on the SEND event itself —
        // any of them can legitimately respond.
        if (!isRecipientOnLatestSend(q, actorEmail)) {
            throw new SecurityException("This document isn't assigned to you.");
        }

        DocRow d = lookupDoc(docNumber);
        // Save every uploaded file to disk. The FIRST file populates the
        // singular relUploadPath/uploadBytes/uploadName (back-compat with
        // every downstream path that assumed one file — PDF generator, single-
        // file email attach, dashboard latestUploadMeta). The REST become
        // extra blobs that ride on the DCC email and show up as additional
        // 📎 links on the dashboard.
        String relUploadPath = null;
        byte[] uploadBytes = null;
        String uploadName = null;
        List<String> allRelPaths = new ArrayList<>();
        List<ImsReviewEmailService.NamedBlob> extras = new ArrayList<>();
        if (uploadFiles != null) {
            for (int i = 0; i < uploadFiles.size(); i++) {
                MultipartFile f = uploadFiles.get(i);
                if (f == null || f.isEmpty()) continue;
                try {
                    String orig = sanitize(f.getOriginalFilename());
                    String filename = docNumber + "-" + System.currentTimeMillis() + "-" + i + "-" + orig;
                    Path target = Paths.get(queueStore.getUploadDir(), filename);
                    Files.createDirectories(target.getParent());
                    Files.copy(f.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                    String rel = "uploads/" + filename;
                    allRelPaths.add(rel);
                    byte[] bytes = Files.readAllBytes(target);
                    if (relUploadPath == null) {
                        relUploadPath = rel;
                        uploadBytes = bytes;
                        uploadName = orig;
                    } else {
                        extras.add(new ImsReviewEmailService.NamedBlob(orig, bytes, "application/octet-stream"));
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Upload save failed: " + e.getMessage(), e);
                }
            }
        }

        switch (type) {
            case DO_RESPONSE_NO_CHANGE:
                return handleDoNoChange(q, d, actorEmail, actorDisplay);

            case DO_RESPONSE_NEEDS_CHANGE:
                return handleDoTerminal(q, d, actorEmail, actorDisplay,
                        type, note, relUploadPath, uploadBytes, uploadName, extras, allRelPaths,
                        "ims-review-dcc-needs-change",
                        "[Needs Change] IMS Document " + docNumber);

            case DO_RESPONSE_NEED_HELP:
                return handleDoTerminal(q, d, actorEmail, actorDisplay,
                        type, note, relUploadPath, uploadBytes, uploadName, extras, allRelPaths,
                        "ims-review-dcc-need-help",
                        "[Need Help] IMS Document " + docNumber);

            case DM_RESPONSE_APPROVED:
                return handleDmApproved(q, d, actorEmail, actorDisplay);

            case DM_RESPONSE_SEND_BACK:
                return handleDmSendBack(q, d, actorEmail, actorDisplay);

            default:
                throw new RuntimeException("Unsupported response type: " + type);
        }
    }

    /** True when {@code actorEmail} appears (case-insensitive) on the recipients
     *  list of the most recent SEND_TO_DO / SEND_TO_DM event for this queue
     *  item. Used by respond() to authorize multi-recipient owners (each
     *  Document Owner can respond to a SEND that went to all of them). Falls
     *  back to q.currentRecipient match for safety if no SEND found. */
    private static boolean isRecipientOnLatestSend(ImsReviewQueueStore.QueueItem q, String actorEmail) {
        if (q == null || actorEmail == null) return false;
        String lower = actorEmail.trim().toLowerCase();
        if (lower.isEmpty()) return false;
        // Walk history backwards looking for the latest SEND event
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.type != ImsReviewQueueStore.EventType.SEND_TO_DO
                    && ev.type != ImsReviewQueueStore.EventType.SEND_TO_DM) continue;
            if (ev.recipients == null) continue;
            for (String r : ev.recipients) {
                if (r != null && r.trim().equalsIgnoreCase(lower)) return true;
            }
            return false;  // found a SEND but actor not in it
        }
        // Fallback — no SEND event found; trust the legacy currentRecipient check
        return q.currentRecipient != null && q.currentRecipient.equalsIgnoreCase(lower);
    }

    /** DCC cancels an in-flight item. No outbound email. */
    public ImsReviewQueueStore.QueueItem cancel(String docNumber, String drrNumber,
                                                String actorEmail, String actorDisplay) {
        ImsReviewQueueStore.Event ev = baseEvent(ImsReviewQueueStore.EventType.CANCEL,
                docNumber, drrNumber, actorEmail, actorDisplay, "DCC");
        queueStore.append(ev);
        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_CANCEL",
                "doc=" + docNumber + " | drr=" + nvl(drrNumber));
        return queueStore.find(docNumber, drrNumber);
    }

    /** Admin-only "throw the doc back to the start". Reverts the queue item to
     *  NOT_SENT in place (keeping its DRR# so the doc stays in New > Pending,
     *  NOT Legacy) so DCC can Send again from scratch. JSONL log keeps the full
     *  prior history as an audit trail. No outbound email. */
    public ImsReviewQueueStore.QueueItem reset(String docNumber, String drrNumber,
                                               String actorEmail, String actorDisplay) {
        ImsReviewQueueStore.Event ev = baseEvent(ImsReviewQueueStore.EventType.RESET,
                docNumber, drrNumber, actorEmail, actorDisplay, "DCC");
        queueStore.append(ev);
        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_RESET",
                "doc=" + docNumber + " | drr=" + nvl(drrNumber));
        return queueStore.find(docNumber, drrNumber);  // null after reset → NOT_SENT
    }

    // ------------------------------------------------------------------
    // Token-based action flow (PT-67 follow-on: email-link → password → PDF)
    // ------------------------------------------------------------------

    /** Response-link expiry window, in days, applied to every SEND token.
     *  Config-driven (default 100) so DCC can retune without a rebuild —
     *  Bibi to confirm the final value. Was a hard-coded 30. */
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.token-ttl-days:100}")
    private int tokenTtlDays;
    private long tokenTtlMs() { return tokenTtlDays * 24L * 60 * 60 * 1000; }
    /** After this many failed password attempts within
     *  {@link #PASSWORD_FAIL_COOLDOWN_MS}, the token is locked. The counter
     *  resets on (a) a successful verify and (b) when the cooldown window
     *  has elapsed since the last failure — so a real recipient who fat-
     *  fingered the password a few times can recover by waiting briefly
     *  instead of needing Doc Control to unlock. JVM restart also resets. */
    private static final int  MAX_PASSWORD_FAILS = 5;
    private static final long PASSWORD_FAIL_COOLDOWN_MS = 15L * 60 * 1000;  // 15 min

    private static final class FailRecord {
        int count;
        long lastFailMs;
        FailRecord(int c, long t) { this.count = c; this.lastFailMs = t; }
    }
    private final Map<String, FailRecord> tokenFailCounts = new HashMap<>();

    public static final class TokenContext {
        public boolean valid;
        public String errorReason;
        public String role;                  // "DO" | "DM"
        public String docNumber;
        public String drrNumber;
        public String description;
        public String rev;
        public String lifecyclePhase;
        public String documentType;
        public String nextReviewDate;
        public String recipientEmail;        // first recipient (kept for back-compat / pre-fill)
        public String allowedActor;          // == recipientEmail, lowercased (kept for back-compat)
        /** All recipients on the SEND event (lowercased). Multi-recipient
         *  case: when the doc has multiple Document Owners, the SEND fans out
         *  to all of them with one token. Any of them can redeem; the
         *  validator checks {@code verifiedEmail ∈ allowedActors} rather than
         *  the single-recipient equality. First redeem wins (token burned). */
        public java.util.List<String> allowedActors = new java.util.ArrayList<>();
        public List<String> allowedActions;  // ["NO_CHANGE","UPLOAD","HELP"] for DO; ["DM_APPROVE","DM_SEND_BACK"] for DM
        public String sentAt;                // ISO ts of the SEND event
        public long expiresInMs;             // remaining TTL
        /** Document Owner display names from Agile — surfaced as a landing-page
         *  attribute so the responder can visually confirm the doc (A74). */
        public java.util.List<String> ownerNames = new java.util.ArrayList<>();
        /** Structured Document Owner refs {loginId, displayName, email} from
         *  Agile (via lookupDoc). The DCO form pre-fills its Owner(s) chips from
         *  these so they match the document's real owners, instead of resolving
         *  the responder's email (which can map to the wrong Agile user). */
        public java.util.List<java.util.Map<String, Object>> owners = new java.util.ArrayList<>();
        // Pre-populated info for "already redeemed" path
        public boolean alreadyRedeemed;
        public String redeemedAction;
        public String redeemedBy;
        public String redeemedAt;
        public String redeemedPdfPath;
    }

    /** Look up a token in the queue log and return everything the response
     *  page needs to render the action confirmation. Pure read — no state
     *  mutation. */
    public synchronized TokenContext describeToken(String token) {
        TokenContext c = new TokenContext();
        ImsReviewQueueStore.Event sendEv = queueStore.findEventByToken(token);
        if (sendEv == null) {
            c.errorReason = "This response link wasn't recognized. It may have been re-issued — check your inbox for a newer email.";
            return c;
        }
        ImsReviewQueueStore.QueueItem q = queueStore.findQueueItemByToken(token);
        if (q == null) {
            c.errorReason = "The document this link points to is no longer being tracked. Please contact Doc Control.";
            return c;
        }
        // Expiry
        long ageMs = System.currentTimeMillis() - parseTsMs(sendEv.ts);
        long remaining = tokenTtlMs() - ageMs;
        c.expiresInMs = Math.max(0, remaining);
        if (remaining <= 0) {
            c.errorReason = "This response link has expired (" + tokenTtlDays + "-day window). Please contact Doc Control to re-issue the review.";
            return c;
        }
        // Burn-rate with cooldown: if the last failure was long enough ago,
        // the counter resets so a genuine recipient who fumbled a few times
        // can recover without Doc Control involvement.
        FailRecord fr = tokenFailCounts.get(token);
        if (fr != null && fr.count >= MAX_PASSWORD_FAILS) {
            long sinceLastFailMs = System.currentTimeMillis() - fr.lastFailMs;
            if (sinceLastFailMs >= PASSWORD_FAIL_COOLDOWN_MS) {
                tokenFailCounts.remove(token);
            } else {
                long minsLeft = Math.max(1, (PASSWORD_FAIL_COOLDOWN_MS - sinceLastFailMs) / 60_000);
                c.errorReason = "This link is temporarily locked after several failed password attempts. "
                              + "Try again in about " + minsLeft + " minute" + (minsLeft == 1 ? "" : "s")
                              + ", or contact Doc Control to unlock immediately.";
                return c;
            }
        }
        // Redemption — first valid click wins; check for any later response event referencing this token
        if (queueStore.isTokenRedeemed(token)) {
            c.alreadyRedeemed = true;
            for (ImsReviewQueueStore.Event e : q.history) {
                if (token.equals(e.redeemsToken)) {
                    c.redeemedAction = e.type.name();
                    c.redeemedBy = e.verifiedDisplayName != null ? e.verifiedDisplayName
                            : (e.actor != null ? e.actor.displayName : "");
                    c.redeemedAt = e.ts;
                    c.redeemedPdfPath = e.pdfPath;
                    break;
                }
            }
            c.errorReason = "This review was already submitted by " + nvl(c.redeemedBy)
                          + " on " + nvl(c.redeemedAt).replace("T", " ").substring(0, Math.min(16, nvl(c.redeemedAt).length()))
                          + " UTC.";
            // still populate doc context so the page can render the snapshot
        }

        c.valid = !c.alreadyRedeemed;
        c.role = (sendEv.type == ImsReviewQueueStore.EventType.SEND_TO_DM) ? "DM" : "DO";
        c.docNumber = q.docNumber;
        c.drrNumber = q.drrNumber;
        c.recipientEmail = (sendEv.recipients != null && !sendEv.recipients.isEmpty())
                ? sendEv.recipients.get(0).toLowerCase()
                : "";
        c.allowedActor = c.recipientEmail;
        c.allowedActors = new java.util.ArrayList<>();
        if (sendEv.recipients != null) {
            for (String r : sendEv.recipients) {
                if (r != null && !r.trim().isEmpty()) c.allowedActors.add(r.trim().toLowerCase());
            }
        }
        c.sentAt = sendEv.ts;
        c.allowedActions = "DM".equals(c.role)
                ? Arrays.asList("DM_APPROVE", "DM_SEND_BACK")
                : Arrays.asList("NO_CHANGE", "UPLOAD", "HELP");

        DocRow d = lookupDoc(q.docNumber);
        if (d != null) {
            c.description = d.description;
            c.rev = d.rev;
            c.lifecyclePhase = d.lifecyclePhase;
            c.documentType = d.documentType;
            c.nextReviewDate = d.nextReviewDate;
            if (d.owners != null) {
                for (OwnerRef o : d.owners) {
                    String nm = (o.displayName != null && !o.displayName.trim().isEmpty()
                            && !o.displayName.trim().equals(",")) ? o.displayName.trim()
                            : nvl(o.email);
                    if (!nm.isEmpty()) c.ownerNames.add(nm);
                    // Structured ref for the DCO form's Owner(s) pre-fill — only
                    // when we have a real Agile loginId to resolve the user.
                    if (o.loginId != null && !o.loginId.trim().isEmpty()) {
                        java.util.Map<String, Object> om = new java.util.LinkedHashMap<>();
                        om.put("loginId", o.loginId.trim());
                        om.put("displayName", nm);
                        om.put("email", nvl(o.email));
                        c.owners.add(om);
                    }
                }
            }
        }
        return c;
    }

    /** Best-effort: write a History entry on the Agile document recording who
     *  downloaded its attachments from the IMS response link. The downloader
     *  has no Agile account, so the comment names the link recipient(s) + the
     *  client IP as the invoker (green-list A73). Never throws — a failed
     *  write-back must not break the download itself. */
    public void logAttachmentDownload(TokenContext c, int fileCount, long totalBytes, String ip) {
        if (c == null || c.docNumber == null || c.docNumber.isEmpty()) return;
        try {
            String invoker = (c.allowedActors != null && !c.allowedActors.isEmpty())
                    ? String.join("; ", c.allowedActors)
                    : nvl(c.recipientEmail);
            if (invoker.isEmpty()) invoker = "(unknown link recipient)";
            String when = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            String comment = "IMS review: document attachment(s) downloaded via response link — "
                    + fileCount + " file(s), " + totalBytes + " bytes. "
                    + "Invoker (no Agile account): " + invoker
                    + ((ip == null || ip.isEmpty()) ? "" : " from IP " + ip)
                    + " at " + when + " UTC.";
            String corrId = java.util.UUID.randomUUID().toString();
            AgileWriteBackClient.Result r = agileWriteBack.appendItemHistory(c.docNumber, comment, corrId);
            if (r != null && r.ok) {
                LOG.info("[IMS-REVIEW] attachment-download history written to " + c.docNumber
                        + " (corr=" + corrId + ", invoker=" + invoker + ")");
            } else {
                LOG.warning("[IMS-REVIEW] attachment-download history write FAILED for " + c.docNumber
                        + ": " + (r == null ? "(null result)" : r.errorReason));
            }
        } catch (Exception e) {
            LOG.warning("[IMS-REVIEW] attachment-download history write errored for "
                    + c.docNumber + ": " + e.getMessage());
        }
    }

    /** Record a failed password attempt against a token. After
     *  {@link #MAX_PASSWORD_FAILS} within {@link #PASSWORD_FAIL_COOLDOWN_MS}
     *  the token is locked; the lock auto-releases once the cooldown window
     *  passes since the last failure. */
    public synchronized void recordTokenFail(String token) {
        if (token == null || token.isEmpty()) return;
        long now = System.currentTimeMillis();
        FailRecord fr = tokenFailCounts.get(token);
        if (fr == null) {
            fr = new FailRecord(1, now);
            tokenFailCounts.put(token, fr);
        } else {
            // If the previous failure was older than the cooldown window, this
            // is effectively a fresh attempt — restart the count.
            if (now - fr.lastFailMs >= PASSWORD_FAIL_COOLDOWN_MS) {
                fr.count = 1;
            } else {
                fr.count += 1;
            }
            fr.lastFailMs = now;
        }
        activityLogger.log("(anon)", "(anon)", "IMS_REVIEW_VERIFY_FAIL",
                "token=" + token + " | failCount=" + fr.count);
    }

    /** Clear the failure counter on a successful verify so prior fat-fingers
     *  don't carry over indefinitely. Idempotent. */
    public synchronized void clearTokenFail(String token) {
        if (token == null || token.isEmpty()) return;
        if (tokenFailCounts.remove(token) != null) {
            activityLogger.log("(anon)", "(anon)", "IMS_REVIEW_VERIFY_FAIL_CLEARED",
                    "token=" + token);
        }
    }

    /** After LDAP rejects creds AND the supplied username matches one of the
     *  allowed recipients, append a "you might not be the right person" hint
     *  to the bare "invalid password" — because in this case the failure
     *  could equally be:
     *    (a) the right recipient with a typo, or
     *    (b) a cc'd colleague who left the pre-filled username alone and
     *        typed their own password (so LDAP rejects the cross-pair).
     *  The hint nudges (b) without falsely accusing (a). */
    private String maybeWrongRecipientFollowup(String username, TokenContext ctx, String ldapMessage) {
        if (ctx == null || ctx.allowedActors == null || ctx.allowedActors.isEmpty()) return ldapMessage;
        if (ctx.allowedActor != null
                && ctx.allowedActor.equalsIgnoreCase(emailService.managersDl)) return ldapMessage;
        String supplied = username == null ? "" : username.trim().toLowerCase();
        if (supplied.isEmpty()) return ldapMessage;
        String suppliedEmail = supplied.contains("@") ? supplied : supplied + "@sandisk.com";
        String suppliedSam   = supplied.contains("@") ? supplied.substring(0, supplied.indexOf('@')) : supplied;
        boolean usernameIsRecipient = false;
        for (String allowed : ctx.allowedActors) {
            if (allowed == null) continue;
            String aLower = allowed.trim().toLowerCase();
            String aSam = aLower.contains("@") ? aLower.substring(0, aLower.indexOf('@')) : aLower;
            if (aLower.equals(supplied) || aLower.equals(suppliedEmail)
                    || aSam.equals(supplied) || aSam.equals(suppliedSam)) {
                usernameIsRecipient = true; break;
            }
        }
        if (!usernameIsRecipient) return ldapMessage;
        String who = String.join(" or ", ctx.allowedActors);
        return nvl(ldapMessage) + "\n\n"
             + "Note: this link is for " + who + ".\n"
             + "• If that's you — double-check the password and try again.\n"
             + "• If you were just Cc'd on the email (you're not " + who + "), your own AD credentials "
             + "won't work here — please ask the intended recipient to sign off.";
    }

    /** Wrong-recipient pre-check, run BEFORE LDAP so a cc'd colleague (e.g.
     *  the Document Owner Manager DL got the email, but Krati on the DL
     *  tries to sign with her own creds) sees a useful hint instead of a
     *  bare "invalid password" — which is what LDAP returns when the
     *  pre-filled username field still has the intended recipient's email
     *  but the password is the colleague's own.
     *
     *  <p>Returns null if the supplied username looks like one of
     *  {@code allowedActors}, or if we're in the DM-DL fallback case where
     *  any sandisk.com user on the DL is allowed (then defer to LDAP).
     *  Returns the friendly error string otherwise. */
    private String wrongRecipientHint(String username, TokenContext ctx) {
        if (ctx == null || ctx.allowedActors == null || ctx.allowedActors.isEmpty()) return null;
        // DM-DL fallback: when DO was inactive, the link goes to a DL and
        // any sandisk.com user can sign — let LDAP handle it normally.
        if (ctx.allowedActor != null
                && ctx.allowedActor.equalsIgnoreCase(emailService.managersDl)) return null;
        String supplied = username == null ? "" : username.trim().toLowerCase();
        if (supplied.isEmpty()) return null;
        // Normalise the supplied username into both an "email-ish" and
        // "sam-account-ish" form so common typings all match.
        String suppliedEmail = supplied.contains("@") ? supplied : supplied + "@sandisk.com";
        String suppliedSam   = supplied.contains("@") ? supplied.substring(0, supplied.indexOf('@')) : supplied;
        for (String allowed : ctx.allowedActors) {
            if (allowed == null) continue;
            String aLower = allowed.trim().toLowerCase();
            if (aLower.isEmpty()) continue;
            String aSam = aLower.contains("@") ? aLower.substring(0, aLower.indexOf('@')) : aLower;
            if (aLower.equals(supplied) || aLower.equals(suppliedEmail)
                    || aSam.equals(supplied) || aSam.equals(suppliedSam)) {
                return null;   // username matches a recipient — let LDAP verify the password
            }
        }
        // No match — surface the friendly message
        String who = String.join(" or ", ctx.allowedActors);
        return "This link was sent to " + who + ". Only the intended recipient can sign it off — "
             + "your own AD credentials won't grant access here. "
             + "If you are " + (ctx.allowedActors.size() == 1 ? "this person" : "one of these people")
             + ", please replace the AD Username with your own login and try again. "
             + "Otherwise, please ask them to handle this response.";
    }

    /** Admin escape hatch: force-unlock a token (or a doc's tokens) when the
     *  recipient is stuck behind the cooldown. Wired into a controller
     *  endpoint that's admin-gated. Returns how many counters were cleared. */
    public synchronized int adminClearTokenLockouts(String token, String docNumber) {
        int cleared = 0;
        if (token != null && !token.isEmpty()) {
            if (tokenFailCounts.remove(token) != null) cleared++;
        } else if (docNumber != null && !docNumber.isEmpty()) {
            // Clear lockouts for every live SEND token on this doc — DCC may
            // not know which specific token the recipient is fumbling.
            for (ImsReviewQueueStore.QueueItem qi : queueStore.all()) {
                if (!docNumber.equals(qi.docNumber)) continue;
                for (ImsReviewQueueStore.Event ev : qi.history) {
                    if (ev.token == null) continue;
                    if (tokenFailCounts.remove(ev.token) != null) cleared++;
                }
            }
        }
        activityLogger.log("(admin)", "(admin)", "IMS_REVIEW_TOKEN_UNLOCKED",
                "token=" + nvl(token) + " | doc=" + nvl(docNumber) + " | cleared=" + cleared);
        return cleared;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private LdapAuthService ldapAuthService;

    @org.springframework.beans.factory.annotation.Autowired
    private ImsReviewPdfService pdfService;

    /** Per-call stamp data set by {@link #respondViaToken} so the cascading
     *  handlers (handleDoNoChange, handleDmApproved, etc.) can attach the
     *  verified-identity + token-redemption fields to the response event they
     *  generate. ThreadLocal because the cascade runs inside the same call
     *  but the handlers don't otherwise know about token context. */
    private final ThreadLocal<TokenStamp> activeStamp = new ThreadLocal<>();

    private static final class TokenStamp {
        final String token;
        final LdapAuthService.VerifyResult verify;
        final String ip;
        TokenStamp(String t, LdapAuthService.VerifyResult v, String i) {
            token = t; verify = v; ip = i;
        }
    }

    /** Patch a freshly-created response event with token-redemption + verified
     *  identity metadata if a token flow is active. No-op when called from the
     *  legacy session-based respond() path. */
    private void applyTokenStamp(ImsReviewQueueStore.Event ev) {
        TokenStamp s = activeStamp.get();
        if (s == null) return;
        ev.redeemsToken = s.token;
        if (s.verify != null) {
            ev.verifiedSamAccount = s.verify.samAccount;
            ev.verifiedDisplayName = s.verify.displayName;
            ev.verifiedEmail = s.verify.email;
        }
        ev.verifyIp = s.ip;
    }

    /** Carries the response back out of {@link #respondViaToken} so the
     *  caller (controller) can route to the PDF generator (Phase 3) and the
     *  audit-log writer. */
    public static final class TokenSubmitResult {
        public boolean success;
        public String errorReason;
        public String redirectMessage;       // for already-redeemed / expired etc.
        public ImsReviewQueueStore.Event responseEvent;  // the just-appended event
        public ImsReviewQueueStore.QueueItem queueItem;
        public LdapAuthService.VerifyResult verify;
    }

    /** Public-facing approval-flow entry point. Verifies the AD password,
     *  cross-checks that the verified user is the recipient of the SEND
     *  event, then dispatches the matching response handler — same cascade
     *  the session-based respond() runs (DO_NO_CHANGE → SEND_TO_DM, etc.).
     *  The response event gets stamped with the token + verified identity +
     *  IP so the compliance PDF can be regenerated deterministically. */
    public synchronized TokenSubmitResult respondViaToken(String token, String action,
                                                          String username, String password,
                                                          String note, List<MultipartFile> uploadFiles,
                                                          String clientIp) {
        TokenSubmitResult r = new TokenSubmitResult();
        if (token == null || token.isEmpty()) {
            r.errorReason = "Missing token."; return r;
        }
        TokenContext ctx = describeToken(token);
        if (!ctx.valid) {
            r.errorReason = nvl(ctx.errorReason);
            if (ctx.alreadyRedeemed) r.redirectMessage = ctx.errorReason;
            return r;
        }
        if (!ctx.allowedActions.contains(action)) {
            r.errorReason = "Action '" + action + "' isn't valid for this link.";
            return r;
        }

        // Wrong-recipient pre-check: if the username field clearly isn't one
        // of the allowed recipients, give a useful hint INSTEAD of letting
        // LDAP say "invalid password" (which is what happens when a cc'd
        // colleague types their own password against the pre-filled username).
        String wrongRecipient = wrongRecipientHint(username, ctx);
        if (wrongRecipient != null) {
            recordTokenFail(token);
            r.errorReason = wrongRecipient;
            return r;
        }

        // LDAP verify
        LdapAuthService.VerifyResult v = ldapAuthService.verifyCredentials(username, password);
        if (!v.success) {
            recordTokenFail(token);
            r.errorReason = maybeWrongRecipientFollowup(username, ctx, v.message);
            return r;
        }
        // Verified — clear any accumulated fail counter so prior fumbles
        // don't haunt a future re-click of the same link.
        clearTokenFail(token);
        // Cross-check: the verified user must be one of the recipients on the
        // SEND event. Multi-recipient docs (multiple Document Owners) have
        // allowedActors with N entries; any of them can redeem the token.
        String verifiedEmail = v.email == null ? "" : v.email.trim().toLowerCase();
        boolean inAllowed = ctx.allowedActors != null && ctx.allowedActors.contains(verifiedEmail);
        if (!inAllowed) {
            // DM DL fallback: when DO was inactive, the link recipient is the
            // DM DL but the actual person signing is whoever sits on that DL.
            // Require their verified email to at least be on @sandisk.com.
            boolean dmDlCase = ctx.allowedActor != null
                    && ctx.allowedActor.equalsIgnoreCase(emailService.managersDl);
            if (!dmDlCase) {
                recordTokenFail(token);
                String who = (ctx.allowedActors == null || ctx.allowedActors.isEmpty())
                        ? ctx.allowedActor
                        : String.join(" or ", ctx.allowedActors);
                r.errorReason = "This link was sent to " + who
                              + " — sign in as one of those people.";
                return r;
            }
        }

        // Run the cascade with the verified actor + stamp context.
        ImsReviewQueueStore.QueueItem q = queueStore.find(ctx.docNumber, ctx.drrNumber);
        if (q == null) {
            r.errorReason = "Document is no longer being tracked.";
            return r;
        }
        try {
            activeStamp.set(new TokenStamp(token, v, clientIp));
            ImsReviewQueueStore.EventType type = mapAction(action);
            // The session-respond path checks q.currentRecipient against actorEmail.
            // For token flows the URL token already proves that — but to reuse
            // the existing respond() body cleanly we pass the verified email,
            // which IS the recipient. (DL-fallback excepted; handler accepts.)
            String actorEmail = verifiedEmail.isEmpty() ? ctx.recipientEmail : verifiedEmail;
            String actorDisplay = v.displayName != null && !v.displayName.isEmpty()
                    ? v.displayName : actorEmail;
            respond(ctx.docNumber, ctx.drrNumber, type,
                    actorEmail, actorDisplay, note, uploadFiles);

            // Find the just-appended event so we can generate the compliance
            // PDF + stamp its sha onto the response.
            ImsReviewQueueStore.QueueItem after = queueStore.find(ctx.docNumber, ctx.drrNumber);
            if (after != null && !after.history.isEmpty()) {
                for (int i = after.history.size() - 1; i >= 0; i--) {
                    ImsReviewQueueStore.Event e = after.history.get(i);
                    if (token.equals(e.redeemsToken)) { r.responseEvent = e; break; }
                }
            }

            // Generate the compliance PDF. Every DO/DM action gets one
            // EXCEPT Send Back (it's not an approval — DO will redo the work
            // anyway and a new SEND_TO_DO will land them back here with a
            // fresh attestation). The PDF gives Doc Control proof of who
            // submitted what, when, signed by AD identity.
            if (r.responseEvent != null) {
                ImsReviewQueueStore.EventType et = r.responseEvent.type;
                if (et == ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE
                        || et == ImsReviewQueueStore.EventType.DO_RESPONSE_NEEDS_CHANGE
                        || et == ImsReviewQueueStore.EventType.DO_RESPONSE_NEED_HELP
                        || et == ImsReviewQueueStore.EventType.DM_RESPONSE_APPROVED) {
                    try {
                        ImsReviewPdfService.ApprovalInput in = new ImsReviewPdfService.ApprovalInput();
                        in.docNumber = ctx.docNumber;
                        in.description = ctx.description;
                        in.rev = ctx.rev;
                        in.lifecyclePhase = ctx.lifecyclePhase;
                        in.documentType = ctx.documentType;
                        in.nextReviewDate = ctx.nextReviewDate;
                        in.drrNumber = ctx.drrNumber;
                        in.role = ctx.role;
                        in.actionLabel = actionLabelFor(et);
                        in.signerDisplayName = v.displayName;
                        in.signerEmail = v.email;
                        in.signerSamAccount = v.samAccount;
                        in.signedAtUtc = r.responseEvent.ts;
                        in.signerIp = clientIp;
                        in.sendEventUuid = token;
                        in.responseEventTs = r.responseEvent.ts;
                        ImsReviewPdfService.GeneratedPdf pdf = pdfService.generate(in);
                        // Append a follow-up event recording the PDF so the
                        // audit chain is queryable from queue.jsonl alone.
                        ImsReviewQueueStore.Event pdfEv = baseEvent(
                                ImsReviewQueueStore.EventType.PDF_GENERATED,
                                ctx.docNumber, ctx.drrNumber,
                                v.email == null ? "(system)" : v.email,
                                "PLM Toolkit",
                                ctx.role);
                        pdfEv.redeemsToken = token;
                        pdfEv.pdfPath = pdf.relPath;
                        pdfEv.pdfSha256 = pdf.sha256;
                        pdfEv.verifiedSamAccount = v.samAccount;
                        pdfEv.verifiedDisplayName = v.displayName;
                        pdfEv.verifiedEmail = v.email;
                        queueStore.append(pdfEv);
                        // Mutate the in-memory snapshot so the controller can
                        // surface pdfPath to the response page immediately.
                        r.responseEvent.pdfPath = pdf.relPath;
                        r.responseEvent.pdfSha256 = pdf.sha256;
                        activityLogger.log(v.email, v.displayName, "IMS_REVIEW_PDF_GENERATED",
                                "doc=" + ctx.docNumber + " | role=" + ctx.role
                              + " | sha256=" + pdf.sha256.substring(0, 16) + "…"
                              + " | bytes=" + pdf.bytes
                              + " | path=" + pdf.relPath);

                        // Send the deferred DCC email for Needs Change / Need
                        // Help with the signed attestation PDF attached.
                        // handleDoTerminal stashed the email params on the
                        // ThreadLocal when it skipped its own send.
                        if (et == ImsReviewQueueStore.EventType.DO_RESPONSE_NEEDS_CHANGE
                                || et == ImsReviewQueueStore.EventType.DO_RESPONSE_NEED_HELP) {
                            DeferredDccEmail dde = deferredDccEmail.get();
                            if (dde != null) {
                                try {
                                    ImsReviewEmailService.Payload dccP = emailService.payloadForDccEmail(
                                            dde.emailTemplate,
                                            dde.emailSubject + " (signed attestation attached)",
                                            ctx.docNumber, dde.docDescription, ctx.drrNumber,
                                            v.displayName, v.email, dde.note,
                                            dde.uploadBytes, dde.uploadName);
                                    List<ImsReviewEmailService.NamedBlob> blobs = new ArrayList<>();
                                    blobs.add(new ImsReviewEmailService.NamedBlob(
                                            pdf.path.getFileName().toString(),
                                            Files.readAllBytes(pdf.path), "application/pdf"));
                                    // Files 2..N from a multi-file upload ride alongside the PDF.
                                    if (dde.extraBlobs != null) blobs.addAll(dde.extraBlobs);
                                    dccP.extraAttachments = blobs;
                                    emailService.send(dccP);
                                    activityLogger.log(v.email, v.displayName,
                                            "IMS_REVIEW_DCC_SENT",
                                            "doc=" + ctx.docNumber + " | type=" + et.name()
                                          + " | pdfAttached=true | uploadBytes="
                                          + (dde.uploadBytes == null ? 0 : dde.uploadBytes.length)
                                          + " | extraBlobs=" + (dde.extraBlobs == null ? 0 : dde.extraBlobs.size()));
                                } catch (Exception dccErr) {
                                    LOG.warning("[IMS-REVIEW] DCC email failed for "
                                            + ctx.docNumber + ": " + dccErr.getMessage());
                                }
                            }
                        }

                        // On DM approval, send the rich closure email to DCC
                        // with BOTH signed PDFs attached. handleDmApproved
                        // skipped its email when activeStamp was set, so this
                        // is the only closure email DCC gets in the token flow.
                        if (et == ImsReviewQueueStore.EventType.DM_RESPONSE_APPROVED) {
                            try {
                                List<ImsReviewEmailService.NamedBlob> blobs = new ArrayList<>();
                                // Locate the DO PDF generated earlier in this review cycle
                                // (it sits in the same queue item's history as a
                                // PDF_GENERATED event whose role is DO).
                                for (ImsReviewQueueStore.Event hist : after.history) {
                                    if (hist.type == ImsReviewQueueStore.EventType.PDF_GENERATED
                                            && hist.actor != null && "DO".equals(hist.actor.role)
                                            && hist.pdfPath != null) {
                                        try {
                                            byte[] doPdfBytes = pdfService.readPdf(hist.pdfPath);
                                            String fname = hist.pdfPath.substring(hist.pdfPath.lastIndexOf('/') + 1);
                                            blobs.add(new ImsReviewEmailService.NamedBlob(
                                                    fname, doPdfBytes, "application/pdf"));
                                        } catch (Exception ignored) {}
                                        break;
                                    }
                                }
                                // The DM PDF we just generated
                                blobs.add(new ImsReviewEmailService.NamedBlob(
                                        pdf.path.getFileName().toString(),
                                        Files.readAllBytes(pdf.path), "application/pdf"));

                                ImsReviewEmailService.Payload cp = emailService.payloadForDccEmail(
                                        "ims-review-dm-approved",
                                        "[Approved] IMS Document " + ctx.docNumber + " — DM Approved (signed PDFs attached)",
                                        ctx.docNumber, ctx.description, ctx.drrNumber,
                                        v.displayName, v.email, null, null, null);
                                cp.extraAttachments = blobs;
                                // Cc the current Document Owners so they see the
                                // closure email (Vikas asked on 2026-06-03 — the
                                // existing doEmail param on payloadForDccEmail
                                // here is the DM's email, not the DO's).
                                for (String doMail : getCurrentDocOwnerEmails(ctx.docNumber)) {
                                    addCcDedupOnPayload(cp, doMail);
                                }
                                emailService.send(cp);
                                activityLogger.log(v.email, v.displayName,
                                        "IMS_REVIEW_CLOSURE_SENT",
                                        "doc=" + ctx.docNumber + " | pdfs=" + blobs.size());
                            } catch (Exception closureErr) {
                                LOG.warning("[IMS-REVIEW] closure email failed for "
                                        + ctx.docNumber + ": " + closureErr.getMessage());
                            }
                        }
                    } catch (RuntimeException pdfErr) {
                        LOG.warning("[IMS-REVIEW] PDF generation failed for "
                                + ctx.docNumber + ": " + pdfErr.getMessage());
                        activityLogger.log(v.email, v.displayName, "IMS_REVIEW_PDF_FAILED",
                                "doc=" + ctx.docNumber + " | err=" + pdfErr.getMessage());
                    }
                }
            }

            // ---- Agile write-back (Phase 4) ----
            // Kill-switch (app.ims-review.writeback-enabled=false) keeps the
            // pilot behavior intact while we iterate on dry runs.
            if (writebackEnabled && r.responseEvent != null) {
                try {
                    // Agile write-back uses only the first uploaded file (the
                    // revised IMS doc in the Needs-Change flow). Need-Help
                    // doesn't trigger write-back so the extras are irrelevant
                    // there. Multi-file Needs-Change isn't a thing in the
                    // legacy path; rich DCO form has its own multi-attachment
                    // handling.
                    MultipartFile firstUpload = (uploadFiles == null || uploadFiles.isEmpty())
                            ? null : uploadFiles.get(0);
                    runAgileWriteBack(r.responseEvent, ctx, v, firstUpload);
                } catch (RuntimeException wbErr) {
                    LOG.warning("[IMS-REVIEW] Agile write-back failed for "
                            + ctx.docNumber + ": " + wbErr.getMessage());
                    activityLogger.log(v.email, v.displayName, "IMS_REVIEW_AGILE_FAILED",
                            "doc=" + ctx.docNumber + " | err=" + wbErr.getMessage());
                }
            }

            r.success = true;
            r.queueItem = after;
            r.verify = v;
            activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_TOKEN_REDEEM",
                    "doc=" + ctx.docNumber + " | drr=" + nvl(ctx.drrNumber)
                  + " | action=" + action + " | role=" + ctx.role
                  + " | verifiedEmail=" + verifiedEmail + " | ip=" + nvl(clientIp));
            return r;
        } catch (SecurityException sec) {
            r.errorReason = "Access denied: " + sec.getMessage();
            return r;
        } catch (RuntimeException ex) {
            r.errorReason = "Submission failed: " + ex.getMessage();
            return r;
        } finally {
            activeStamp.remove();
            deferredDccEmail.remove();
        }
    }

    /** Run the Agile write-back cascade for one terminal-or-intermediate
     *  response event. Stamps results on the in-memory event (so the response
     *  page can surface DCO# / DRR#) AND appends a separate AGILE_WRITEBACK
     *  audit event to queue.jsonl for the persistent record. Never throws —
     *  errors are captured into {@code agileError}/{@code agileErrorAt}.
     *
     *  <p>Behavior per response type:
     *  <ul>
     *    <li>DO_RESPONSE_NO_CHANGE → history line on DRR (DO attestation +
     *        cc'd recipients). No DCO; cascade continues to DM elsewhere.</li>
     *    <li>DO_RESPONSE_NEEDS_CHANGE → find DRR, attach revised file, history,
     *        create+submit DCO with IMS Doc as Affected Item + bumped rev +
     *        auto-close relationship rule.</li>
     *    <li>DO_RESPONSE_NEED_HELP → history line (DCC notified out-of-band).</li>
     *    <li>DM_RESPONSE_APPROVED → history line + push DRR to "Review".</li>
     *    <li>DM_RESPONSE_SEND_BACK → history line ("DM sent back to DO").</li>
     *  </ul>
     */
    private void runAgileWriteBack(ImsReviewQueueStore.Event ev, TokenContext ctx,
                                   LdapAuthService.VerifyResult v,
                                   org.springframework.web.multipart.MultipartFile uploadFile) {
        String corrId = java.util.UUID.randomUUID().toString();
        ev.agileCorrId = corrId;
        ev.agileSteps = new ArrayList<>();
        // Clear any prior error stamp — necessary for the replay path where
        // ev carries a failure record from a previous attempt. Without this
        // reset, the post-history bail-out below ("if ev.agileError != null
        // return") fires on the stale error and short-circuits subsequent
        // steps (closeNoChange / createDco) even though the current run is
        // progressing. The live response handler always passes a fresh event
        // so this is a no-op there. Bibi's DRR-0018775 replay on 2026-06-05.
        ev.agileError = null;
        ev.agileErrorAt = null;
        String roleLabel = ctx.role == null ? "?" : ctx.role;
        String signer = v == null ? "(unknown)" : (nvl(v.displayName) + " <" + nvl(v.email) + ">");

        try {
            // 1) Find pending DRR — always the first step.
            AgileWriteBackClient.Result drrLookup = agileWriteBack.findPendingDrr(ctx.docNumber, corrId);
            if (!drrLookup.ok) {
                stampAgileFailure(ev, "find-drr", drrLookup.errorReason);
                appendWriteBackAuditEvent(ev, ctx);
                // 2026-06-05 — enqueue auto-retry when find-drr failed for a
                // transport-class reason (plm-agile-service unreachable). Nothing
                // committed in Agile yet, so blind-retry from scratch is safe.
                // Later step-level failures need per-step idempotency tracking
                // before they can be auto-retried — operator uses /replay-writeback
                // until then. The retry runner re-uses replayWriteback().
                if (isTransientTransportError(drrLookup.errorReason)) {
                    try {
                        ImsReviewRetryQueue.Entry queued = retryQueue.enqueue(
                                ctx.docNumber, ctx.drrNumber,
                                ev.type == null ? null : ev.type.name(),
                                ev.ts,
                                drrLookup.errorReason);
                        activityLogger.log(
                                v == null ? "(system)" : v.email,
                                v == null ? "PLM Toolkit" : v.displayName,
                                "IMS_REVIEW_RETRY_ENQUEUED",
                                "doc=" + ctx.docNumber + " | drr=" + nvl(ctx.drrNumber)
                              + " | eventType=" + (ev.type == null ? "?" : ev.type.name())
                              + " | retryId=" + queued.id
                              + " | reason=" + drrLookup.errorReason);
                    } catch (Exception qErr) {
                        LOG.warning("[IMS-REVIEW] retry enqueue failed: " + qErr.getMessage());
                    }
                }
                return;
            }
            String drr = (String) drrLookup.body.get("drrNumber");
            ev.agileDrr = drr;
            ev.agileSteps.add("find-drr=" + drr);

            switch (ev.type) {
                case DO_RESPONSE_NO_CHANGE: {
                    // Attach the DO's signed attestation PDF to the DRR before
                    // the history line, so the order in Agile reads attach→history.
                    attachAttestationPdfToChanges(readPdfBytes(ev.pdfPath), ctx, null, "DO", corrId);
                    String comment = "Document Owner response via Toolkit for yearly review: No Change Needed.\n"
                            + "Signed by: " + signer + ".\n"
                            + "Notifications triggered via Toolkit to: "
                            + emailService.managersDl + ", " + emailService.dccDl + ".";
                    callHistory(ev, drr, comment, recipientsFor(ev), corrId);
                    break;
                }
                case DO_RESPONSE_NEEDS_CHANGE: {
                    // 2) attach the uploaded file
                    if (uploadFile != null && !uploadFile.isEmpty()) {
                        try {
                            AgileWriteBackClient.Result att = agileWriteBack.attachFile(drr,
                                    uploadFile.getBytes(),
                                    uploadFile.getOriginalFilename(),
                                    "Revised IMS Document submitted by " + signer,
                                    corrId);
                            if (att.ok) {
                                ev.agileSteps.add("attach-file=ok");
                            } else {
                                stampAgileFailure(ev, "attach-file", att.errorReason);
                                appendWriteBackAuditEvent(ev, ctx);
                                return;
                            }
                        } catch (java.io.IOException ioe) {
                            stampAgileFailure(ev, "attach-file", "could not read multipart bytes: " + ioe.getMessage());
                            appendWriteBackAuditEvent(ev, ctx);
                            return;
                        }
                    } else {
                        ev.agileSteps.add("attach-file=skip(no-file)");
                    }
                    // 3) history line
                    String comment = "Document Owner response via Toolkit for yearly review: Needs Change.\n"
                            + "Signed by: " + signer + ".\n"
                            + "Revised file attached to this DRR.\n"
                            + "Notifications triggered via Toolkit to: "
                            + emailService.dccDl + ", " + emailService.managersDl + ".";
                    callHistory(ev, drr, comment, recipientsFor(ev), corrId);
                    if (ev.agileError != null) { appendWriteBackAuditEvent(ev, ctx); return; }

                    // 4) create + submit DCO
                    DocRow d = lookupDoc(ctx.docNumber);
                    String currentRev = d == null ? "" : d.rev;
                    AgileWriteBackClient.Result dco = agileWriteBack.createDco(drr,
                            ctx.docNumber, currentRev,
                            "IMS Doc revised version submitted via PLM Toolkit IMS Review.",
                            "IMS yearly review — Document Owner submitted revised file. See DRR attachments.",
                            recipientsFor(ev),
                            java.util.Collections.singletonList("IMS-Doc-Managers-Agile"),
                            true, ev.verifiedSamAccount, corrId);
                    if (dco.ok && dco.body != null) {
                        Object dcoNum = dco.body.get("dcoNumber");
                        ev.agileDco = dcoNum == null ? null : dcoNum.toString();
                        ev.agileSteps.add("create-dco=" + ev.agileDco);
                    } else {
                        stampAgileFailure(ev, "create-dco", dco.errorReason);
                    }
                    break;
                }
                case DO_RESPONSE_NEED_HELP: {
                    String comment = "Document Owner response via Toolkit for yearly review: Need Help.\n"
                            + "Signed by: " + signer + ".\n"
                            + "Doc Control has been notified to step in.\n"
                            + "Notifications triggered via Toolkit to: " + emailService.dccDl + ".";
                    callHistory(ev, drr, comment, recipientsFor(ev), corrId);
                    break;
                }
                case DM_RESPONSE_APPROVED: {
                    // Attach the DM's signed attestation PDF to the DRR before
                    // the closure history line + status transition.
                    attachAttestationPdfToChanges(readPdfBytes(ev.pdfPath), ctx, null, "DM", corrId);
                    String comment = "Document Owner's Manager response via Toolkit for yearly review: Approved — No Change Confirmed.\n"
                            + "Signed by: " + signer + ".\n"
                            + "Notifications triggered via Toolkit to: " + emailService.dccDl + ".";
                    callHistory(ev, drr, comment, recipientsFor(ev), corrId);
                    if (ev.agileError != null) { appendWriteBackAuditEvent(ev, ctx); return; }
                    // DM-Approve cascade — Pending → Submit → CCB.
                    // Done in one atomic service call (close-no-change) because
                    // the workflow doesn't allow Pending → CCB directly.
                    // Sets updateRequired=No so the DocumentReview PX skips
                    // DCO creation (this is the no-change closure path).
                    //
                    // PT-80 (2026-06-03): the Requestor we stamp on the DRR is
                    // the DO who confirmed No Change, NOT the DM who approved
                    // — previously we passed ev.verifiedSamAccount (the DM's
                    // login), which overwrote the DRR Requestor with the
                    // manager's name. Prefer the DO_RESPONSE_NO_CHANGE
                    // signer's sAMAccount; fall back to the original
                    // SEND_TO_DO recipient email; if neither resolves we pass
                    // null and let closeNoChange skip the Requestor step (the
                    // existing DRR Requestor stays in place).
                    String drrRequestor = findDoSignerIdentity(ctx.docNumber, drr);
                    AgileWriteBackClient.Result st = agileWriteBack.closeNoChange(
                            drr, ctx.docNumber, drrRequestor,
                            "Both DO and DM confirmed No Change Needed via PLM Toolkit IMS Review.",
                            corrId);
                    if (st.ok) {
                        ev.agileSteps.add("close-no-change=" + drrReviewStatus);
                    } else {
                        stampAgileFailure(ev, "close-no-change", st.errorReason);
                        // Alert PLM IT — the "Approved" email is sent optimistically
                        // and doesn't reflect this; without this the stuck DRR is silent.
                        String failedStep = (st.body != null && st.body.get("stepFailedAt") != null)
                                ? String.valueOf(st.body.get("stepFailedAt")) : "(unknown)";
                        emailService.sendCloseNoChangeFailedAlert(ctx, drr, st.errorReason, failedStep, corrId);
                    }
                    break;
                }
                case DM_RESPONSE_SEND_BACK: {
                    String comment = "Document Owner's Manager response via Toolkit for yearly review: Send Back to DO.\n"
                            + "Signed by: " + signer + ".\n"
                            + "DO will revise and resubmit.";
                    callHistory(ev, drr, comment, recipientsFor(ev), corrId);
                    break;
                }
                default:
                    LOG.info("[IMS-REVIEW] writeback skipped for event type " + ev.type);
                    return;
            }
        } catch (RuntimeException rt) {
            stampAgileFailure(ev, "(uncaught)", rt.getClass().getSimpleName() + ":" + rt.getMessage());
        } finally {
            appendWriteBackAuditEvent(ev, ctx);
            activityLogger.log(v == null ? "(unknown)" : v.email,
                    v == null ? "(unknown)" : v.displayName,
                    "IMS_REVIEW_AGILE_WRITEBACK",
                    "corrId=" + corrId + " | doc=" + ctx.docNumber
                  + " | drr=" + nvl(ev.agileDrr) + " | dco=" + nvl(ev.agileDco)
                  + " | role=" + roleLabel + " | steps=" + ev.agileSteps
                  + (ev.agileError == null ? "" : " | errorAt=" + ev.agileErrorAt + " | err=" + ev.agileError));
        }
    }

    /**
     * Admin tool: replay the Agile write-back for a previously-failed response
     * event. Used when the initial cascade aborted at {@code find-drr} (or
     * any other transport step) because plm-agile-service was down during a
     * redeploy window — the event landed in the queue, the email went out,
     * but Agile never received the attestation attach / history / status
     * push. Running this rebuilds the (event, ctx, verify) tuple from the
     * queue and calls the same {@link #runAgileWriteBack} the live response
     * handler does.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Find the {@link ImsReviewQueueStore.QueueItem} for
     *       (docNumber, drrNumber).</li>
     *   <li>Walk history backward for the latest event matching
     *       {@code eventType} (or the last response event if null).</li>
     *   <li>Restore {@code pdfPath} on that event from a sibling
     *       {@code PDF_GENERATED} event with the same role — needed because
     *       the original in-memory mutation at the response handler is lost
     *       on JVM restart, so a fresh load sees pdfPath=null on the
     *       response event itself.</li>
     *   <li>Reconstruct {@link TokenContext} from the queue item + a
     *       {@code DocRow} lookup.</li>
     *   <li>Reconstruct {@link LdapAuthService.VerifyResult} from the
     *       response event's stamped identity fields ({@code verifiedSamAccount}
     *       / {@code verifiedDisplayName} / {@code verifiedEmail}).</li>
     *   <li>Call {@link #runAgileWriteBack} — same code as the live path.
     *       It emits its own {@code AGILE_WRITEBACK} event with the new
     *       corrId so the audit trail shows both attempts.</li>
     * </ol>
     *
     * <p>Returns a result map: {@code {ok, agileDrr, agileDco, agileSteps,
     * agileError, agileErrorAt, corrId}}. The caller (admin UI / curl) sees
     * the same shape the live writeback emits.
     *
     * <p>Idempotency caveat: re-running a successful writeback would
     * re-attach the same PDF and re-append the same history line, producing
     * duplicates in Agile. The caller is expected to invoke this only after
     * confirming the original writeback failed (e.g. queue item's last
     * {@code AGILE_WRITEBACK} event has {@code agileError} set). No
     * server-side gate here — trust the admin.
     */
    public Map<String, Object> replayWriteback(String docNumber, String drrNumber,
                                                String eventTypeStr,
                                                String invokedByEmail, String invokedByDisplay) {
        Map<String, Object> out = new LinkedHashMap<>();
        ImsReviewQueueStore.QueueItem q = queueStore.find(docNumber, drrNumber);
        if (q == null) {
            out.put("ok", false);
            out.put("error", "No queue item for doc=" + docNumber + " drr=" + drrNumber);
            return out;
        }
        // Pick the target event. If a type was specified, find the latest
        // matching event in history; otherwise pick the latest response-type
        // event (DO_RESPONSE_* or DM_RESPONSE_*).
        ImsReviewQueueStore.EventType targetType = null;
        if (eventTypeStr != null && !eventTypeStr.trim().isEmpty()) {
            try { targetType = ImsReviewQueueStore.EventType.valueOf(eventTypeStr.trim()); }
            catch (IllegalArgumentException e) {
                out.put("ok", false);
                out.put("error", "Unknown eventType: " + eventTypeStr);
                return out;
            }
        }
        ImsReviewQueueStore.Event target = null;
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (targetType != null) {
                if (ev.type == targetType) { target = ev; break; }
            } else {
                String tn = ev.type == null ? "" : ev.type.name();
                if (tn.startsWith("DO_RESPONSE_") || tn.startsWith("DM_RESPONSE_")) {
                    target = ev; break;
                }
            }
        }
        if (target == null) {
            out.put("ok", false);
            out.put("error", "No replayable event found in history (type=" + eventTypeStr + ")");
            return out;
        }

        // Restore pdfPath from sibling PDF_GENERATED with the same actor role
        // if the response event's own pdfPath wasn't persisted (JVM restart
        // lost the in-memory mutation). Match on actor.role for DO vs DM.
        if (target.pdfPath == null || target.pdfPath.isEmpty()) {
            String wantRole = target.actor == null ? null : target.actor.role;
            for (int i = q.history.size() - 1; i >= 0; i--) {
                ImsReviewQueueStore.Event ev = q.history.get(i);
                if (ev.type == ImsReviewQueueStore.EventType.PDF_GENERATED
                        && ev.actor != null
                        && (wantRole == null || wantRole.equals(ev.actor.role))
                        && ev.pdfPath != null) {
                    target.pdfPath = ev.pdfPath;
                    if (target.pdfSha256 == null) target.pdfSha256 = ev.pdfSha256;
                    break;
                }
            }
        }

        // Reconstruct TokenContext (only the fields runAgileWriteBack reads).
        DocRow d = lookupDoc(docNumber);
        TokenContext ctx = new TokenContext();
        ctx.docNumber = docNumber;
        ctx.drrNumber = drrNumber;
        ctx.description = d == null ? "" : nvl(d.description);
        ctx.rev = d == null ? "" : nvl(d.rev);
        ctx.lifecyclePhase = d == null ? "" : nvl(d.lifecyclePhase);
        ctx.documentType = d == null ? "" : nvl(d.documentType);
        ctx.nextReviewDate = d == null ? "" : nvl(d.nextReviewDate);
        ctx.role = target.actor == null ? "" : nvl(target.actor.role);

        // Reconstruct VerifyResult from the stamped identity on the response event.
        LdapAuthService.VerifyResult v = LdapAuthService.VerifyResult.success(
                nvl(target.verifiedSamAccount),
                nvl(target.verifiedDisplayName),
                nvl(target.verifiedEmail));

        long t0 = System.currentTimeMillis();
        runAgileWriteBack(target, ctx, v, /* uploadFile */ null);
        long elapsed = System.currentTimeMillis() - t0;

        // runAgileWriteBack mutates target.agileSteps / agileError / etc.
        // AND appends a fresh AGILE_WRITEBACK event with the new corrId.
        boolean ok = target.agileError == null;
        out.put("ok", ok);
        out.put("corrId", target.agileCorrId);
        out.put("agileDrr", target.agileDrr);
        out.put("agileDco", target.agileDco);
        out.put("agileSteps", target.agileSteps);
        out.put("agileErrorAt", target.agileErrorAt);
        out.put("agileError", target.agileError);
        out.put("elapsedMs", elapsed);
        out.put("replayedEventType", target.type == null ? null : target.type.name());
        out.put("pdfPath", target.pdfPath);

        activityLogger.log(invokedByEmail, invokedByDisplay,
                "IMS_REVIEW_AGILE_WRITEBACK_REPLAYED",
                "doc=" + docNumber + " | drr=" + drrNumber
              + " | replayedEvent=" + (target.type == null ? "?" : target.type.name())
              + " | corrId=" + target.agileCorrId
              + " | ok=" + ok
              + (target.agileError == null ? "" : " | errorAt=" + target.agileErrorAt + " | err=" + target.agileError));
        return out;
    }

    /** Read the signed attestation PDF off disk by its queue-event relPath.
     *  Returns null on any error — every caller is best-effort and tolerates
     *  a missing PDF (the email still went out with it attached). */
    private byte[] readPdfBytes(String relPath) {
        if (relPath == null || relPath.isEmpty()) return null;
        try {
            return pdfService.readPdf(relPath);
        } catch (Exception e) {
            LOG.warning("[IMS-REVIEW] could not read attestation PDF " + relPath + ": " + e.getMessage());
            return null;
        }
    }

    /** Attach the signed attestation PDF to the DRR (always, when DRR is known)
     *  and to the DCO (when {@code dcoNumber} is non-null — i.e. the rich DCO
     *  form flow). Best-effort: failures are logged but never block the caller.
     *  Filename pattern: {@code IMS-Review-Attestation-{ROLE}-{DOC}.pdf} so the
     *  Agile attachment list reads at a glance.
     *
     *  @param pdfBytes  the signed PDF; null = skip (e.g. PDF generation failed)
     *  @param ctx       token context (doc + drr)
     *  @param dcoNumber non-null → also attach to this DCO (Needs-Change flow)
     *  @param role      "DO" or "DM" — stamped into the filename
     *  @param corrId    correlation id for plm-agile-service log tagging
     */
    private void attachAttestationPdfToChanges(byte[] pdfBytes, TokenContext ctx,
                                               String dcoNumber, String role, String corrId) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            LOG.info("[IMS-REVIEW] skip attestation attach — no PDF bytes (doc=" + (ctx == null ? "?" : ctx.docNumber) + ")");
            return;
        }
        String docNum = ctx == null ? "" : nvl(ctx.docNumber);
        String drr    = ctx == null ? "" : nvl(ctx.drrNumber);
        String filename = "IMS-Review-Attestation-" + nvl(role) + "-" + docNum + ".pdf";
        String desc = "Signed IMS Review attestation (" + nvl(role) + ") generated by PLM Toolkit.";

        if (!drr.isEmpty()) {
            try {
                AgileWriteBackClient.Result r = agileWriteBack.attachFileToChange(drr, pdfBytes, filename, desc, corrId);
                if (r.ok) {
                    LOG.info("[IMS-REVIEW] attached attestation PDF to DRR " + drr
                            + " (role=" + role + " bytes=" + pdfBytes.length + ")");
                } else {
                    LOG.warning("[IMS-REVIEW] DRR attestation attach failed for " + drr + ": " + r.errorReason);
                }
            } catch (Exception e) {
                LOG.warning("[IMS-REVIEW] DRR attestation attach threw for " + drr + ": " + e.getMessage());
            }
        }
        if (dcoNumber != null && !dcoNumber.trim().isEmpty()) {
            try {
                AgileWriteBackClient.Result r = agileWriteBack.attachFileToChange(dcoNumber.trim(), pdfBytes, filename, desc, corrId);
                if (r.ok) {
                    LOG.info("[IMS-REVIEW] attached attestation PDF to DCO " + dcoNumber
                            + " (role=" + role + " bytes=" + pdfBytes.length + ")");
                } else {
                    LOG.warning("[IMS-REVIEW] DCO attestation attach failed for " + dcoNumber + ": " + r.errorReason);
                }
            } catch (Exception e) {
                LOG.warning("[IMS-REVIEW] DCO attestation attach threw for " + dcoNumber + ": " + e.getMessage());
            }
        }
    }

    /** Wrap history-call result-handling so each terminal handler stays
     *  one-line readable. */
    private void callHistory(ImsReviewQueueStore.Event ev, String drr, String comment,
                             List<String> recipients, String corrId) {
        AgileWriteBackClient.Result hist = agileWriteBack.appendHistory(drr, comment, recipients, null, corrId);
        if (hist.ok) {
            Object delta = hist.body == null ? null : hist.body.get("rowDelta");
            ev.agileSteps.add("history=ok(delta=" + delta + ")");
        } else {
            // The History line is best-effort. A failure here must NOT abort the
            // write-back cascade — the callers guard on ev.agileError and would
            // otherwise `return` before the critical step (DM-Approve's
            // close-no-change status advance, or create-DCO). 2026-07-15: a
            // recurring errorCode=407 "Insufficient privilege" on appendHistory
            // was short-circuiting DM-Approve, so DRRs never reached Review/CCB
            // even though the status change itself was fine. Record it as a
            // non-fatal step instead of stamping ev.agileError.
            ev.agileSteps.add("history=FAIL(non-fatal): " + nvl(hist.errorReason));
            LOG.warning("[IMS-REVIEW] appendHistory failed (non-fatal, cascade continues) for "
                    + drr + ": " + hist.errorReason);
        }
    }

    /** Best-effort recipient list for the History notification — currently just
     *  the actor + admin DL; can be expanded once we know what DCC wants. */
    private List<String> recipientsFor(ImsReviewQueueStore.Event ev) {
        List<String> out = new ArrayList<>();
        if (ev.verifiedEmail != null && !ev.verifiedEmail.isEmpty()) out.add(ev.verifiedEmail);
        return out;
    }

    private void stampAgileFailure(ImsReviewQueueStore.Event ev, String step, String err) {
        ev.agileErrorAt = step;
        ev.agileError = err == null ? "(unknown)" : err;
        ev.agileSteps.add(step + "=FAIL");
    }

    /** Append an AGILE_WRITEBACK audit event so the queue.jsonl alone tells the
     *  whole write-back story for this action. */
    private void appendWriteBackAuditEvent(ImsReviewQueueStore.Event src, TokenContext ctx) {
        ImsReviewQueueStore.Event audit = baseEvent(ImsReviewQueueStore.EventType.AGILE_WRITEBACK,
                ctx.docNumber, ctx.drrNumber,
                src.actor == null ? null : src.actor.email,
                src.actor == null ? null : src.actor.displayName,
                src.actor == null ? null : src.actor.role);
        audit.agileCorrId = src.agileCorrId;
        audit.agileDrr    = src.agileDrr;
        audit.agileDco    = src.agileDco;
        audit.agileSteps  = src.agileSteps == null ? null : new ArrayList<>(src.agileSteps);
        audit.agileError  = src.agileError;
        audit.agileErrorAt = src.agileErrorAt;
        audit.redeemsToken = src.redeemsToken;
        queueStore.append(audit);
    }

    /** Human-readable action label for the compliance PDF. */
    private String actionLabelFor(ImsReviewQueueStore.EventType et) {
        switch (et) {
            case DO_RESPONSE_NO_CHANGE:     return "No Change Needed";
            case DO_RESPONSE_NEEDS_CHANGE:  return "Needs Change — Revised File Submitted";
            case DO_RESPONSE_NEED_HELP:     return "Need Help — Routed to Doc Control";
            case DM_RESPONSE_APPROVED:      return "DM Approved — No Change Confirmed";
            default:                         return et.name();
        }
    }

    private ImsReviewQueueStore.EventType mapAction(String action) {
        switch (action == null ? "" : action) {
            case "NO_CHANGE":     return ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE;
            case "UPLOAD":        return ImsReviewQueueStore.EventType.DO_RESPONSE_NEEDS_CHANGE;
            case "HELP":          return ImsReviewQueueStore.EventType.DO_RESPONSE_NEED_HELP;
            case "DM_APPROVE":    return ImsReviewQueueStore.EventType.DM_RESPONSE_APPROVED;
            case "DM_SEND_BACK":  return ImsReviewQueueStore.EventType.DM_RESPONSE_SEND_BACK;
            default: throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    /** Parse the SEND event timestamp into millis. Tolerates the ISO format
     *  emitted by {@link Instant#toString()} which is what the store writes. */
    private long parseTsMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (Exception ignored) { return 0L; }
    }

    // ------------------------------------------------------------------
    // Flow internals
    // ------------------------------------------------------------------
    private ImsReviewQueueStore.QueueItem handleDoNoChange(ImsReviewQueueStore.QueueItem q,
                                                           DocRow d, String actorEmail, String actorDisplay) {
        // 1. Append DO_RESPONSE_NO_CHANGE
        ImsReviewQueueStore.Event doEv = baseEvent(ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE,
                q.docNumber, q.drrNumber, actorEmail, actorDisplay, "DO");
        applyTokenStamp(doEv);
        queueStore.append(doEv);

        // 2. Resolve DM via LDAP (best-effort), fall back to managers DL
        LdapManagerLookup.Result mgr = managerLookup.findManagerByUserEmail(actorEmail);
        String dmEmail = mgr.fallback ? emailService.managersDl : mgr.email;
        boolean dmFallback = mgr.fallback;

        // 3. Fetch attachments (same code path)
        AgileDocumentAttachmentsClient.Bundle bundle = agileAttachments.fetch(q.docNumber);

        // 4. Mint the SEND_TO_DM token first so the email's action buttons
        // carry it.
        String dmToken = java.util.UUID.randomUUID().toString();
        ImsReviewEmailService.Payload p = emailService.payloadForDmEmail(
                q.docNumber, d == null ? "" : d.description, d == null ? "" : d.rev,
                d == null ? "" : d.documentType, d == null ? "" : d.lifecyclePhase,
                d == null ? "" : d.nextReviewDate,
                q.drrNumber, dmEmail, actorEmail, actorDisplay, todayLabel(),
                bundle.bytes, bundle.filename, bundle.fileCount, bundle.totalBytes, dmFallback);
        p.token = dmToken;
        emailService.send(p);

        // 5. Append SEND_TO_DM with the same token so the click-time lookup
        //    can find this row.
        ImsReviewQueueStore.Event sentEv = baseEvent(ImsReviewQueueStore.EventType.SEND_TO_DM,
                q.docNumber, q.drrNumber, actorEmail, actorDisplay, "DO");
        sentEv.recipients = Collections.singletonList(dmEmail);
        sentEv.fileCount = bundle.fileCount;
        sentEv.fileBytes = bundle.totalBytes;
        sentEv.attachedToEmail = bundle.ok && bundle.totalBytes <= ImsReviewEmailService.ATTACH_LIMIT_BYTES;
        sentEv.dmFallback = dmFallback;
        sentEv.token = dmToken;
        queueStore.append(sentEv);

        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_DO_RESPONSE",
                "doc=" + q.docNumber + " | response=no_change | dmFallback=" + dmFallback);
        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_SEND",
                "doc=" + q.docNumber + " | role=DM | recipient=" + dmEmail);
        return queueStore.find(q.docNumber, q.drrNumber);
    }

    private ImsReviewQueueStore.QueueItem handleDoTerminal(ImsReviewQueueStore.QueueItem q,
                                                           DocRow d, String actorEmail, String actorDisplay,
                                                           ImsReviewQueueStore.EventType type, String note,
                                                           String relPath, byte[] uploadBytes, String uploadName,
                                                           List<ImsReviewEmailService.NamedBlob> extraBlobs,
                                                           List<String> allRelPaths,
                                                           String emailTemplate, String emailSubject) {
        // DCC email: defer to respondViaToken when a token flow is active so
        // the signed attestation PDF can be attached. Legacy session-based
        // respond (admin act-as) still sends here.
        if (activeStamp.get() == null) {
            ImsReviewEmailService.Payload p = emailService.payloadForDccEmail(
                    emailTemplate, emailSubject, q.docNumber,
                    d == null ? "" : d.description, q.drrNumber,
                    actorDisplay, actorEmail, note, uploadBytes, uploadName);
            // Files 2..N ride as extraAttachments on the same email.
            if (extraBlobs != null && !extraBlobs.isEmpty()) {
                if (p.extraAttachments == null) p.extraAttachments = new ArrayList<>();
                p.extraAttachments.addAll(extraBlobs);
            }
            emailService.send(p);
        }

        // Persist event — stash the template + subject + upload bytes/name on
        // the event so respondViaToken can re-issue the DCC email with the
        // PDF attached after the attestation PDF is generated.
        ImsReviewQueueStore.Event ev = baseEvent(type, q.docNumber, q.drrNumber, actorEmail, actorDisplay, "DO");
        ev.note = note;
        ev.uploadFile = relPath;
        if (allRelPaths != null && !allRelPaths.isEmpty()) {
            ev.uploadFiles = new ArrayList<>(allRelPaths);
        }
        ev.recipients = Collections.singletonList(emailService.dccDl);
        applyTokenStamp(ev);
        queueStore.append(ev);
        if (activeStamp.get() != null) {
            // Cache the per-call email parameters on a ThreadLocal so the
            // token-flow PDF block can rebuild the DCC email.
            DeferredDccEmail d2 = new DeferredDccEmail();
            d2.emailTemplate = emailTemplate;
            d2.emailSubject = emailSubject;
            d2.docDescription = d == null ? "" : d.description;
            d2.note = note;
            d2.uploadBytes = uploadBytes;
            d2.uploadName = uploadName;
            d2.extraBlobs = extraBlobs;
            deferredDccEmail.set(d2);
        }

        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_DO_RESPONSE",
                "doc=" + q.docNumber + " | response=" + type.name().replace("DO_RESPONSE_", "").toLowerCase()
                + " | uploaded=" + (uploadBytes != null)
                + " | extras=" + (extraBlobs == null ? 0 : extraBlobs.size()));
        return queueStore.find(q.docNumber, q.drrNumber);
    }

    /** Carries DCC-email build params from handleDoTerminal up to
     *  respondViaToken so the closure email can re-fire with the PDF. */
    private static final class DeferredDccEmail {
        String emailTemplate;
        String emailSubject;
        String docDescription;
        String note;
        byte[] uploadBytes;
        String uploadName;
        /** Files 2..N from a multi-file Need Help / Needs Change upload —
         *  attached on the deferred email after the singular uploadBytes. */
        List<ImsReviewEmailService.NamedBlob> extraBlobs;
    }
    private final ThreadLocal<DeferredDccEmail> deferredDccEmail = new ThreadLocal<>();

    private ImsReviewQueueStore.QueueItem handleDmApproved(ImsReviewQueueStore.QueueItem q,
                                                           DocRow d, String actorEmail, String actorDisplay) {
        ImsReviewQueueStore.Event ev = baseEvent(ImsReviewQueueStore.EventType.DM_RESPONSE_APPROVED,
                q.docNumber, q.drrNumber, actorEmail, actorDisplay, "DM");
        ev.recipients = Collections.singletonList(emailService.dccDl);
        applyTokenStamp(ev);
        queueStore.append(ev);

        // Closure email to DCC. In the token flow, respondViaToken sends a
        // richer version with both signed PDFs attached AFTER the DM PDF is
        // generated — skip here to avoid a duplicate. In the legacy session
        // path (admin act-as testing), send the no-PDF version so DCC still
        // gets a notification.
        if (activeStamp.get() == null) {
            ImsReviewEmailService.Payload p = emailService.payloadForDccEmail(
                    "ims-review-dm-approved",
                    "[Approved] IMS Document " + q.docNumber + " — DM Approved",
                    q.docNumber, d == null ? "" : d.description, q.drrNumber,
                    actorDisplay, actorEmail, null, null, null);
            // Cc the current Document Owners (the actorEmail here is the DM,
            // not the DO) so the folks responsible for the doc see the
            // closure.
            for (String doMail : getCurrentDocOwnerEmails(q.docNumber)) {
                addCcDedupOnPayload(p, doMail);
            }
            emailService.send(p);
        }

        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_DM_RESPONSE",
                "doc=" + q.docNumber + " | response=approved");
        return queueStore.find(q.docNumber, q.drrNumber);
    }

    private ImsReviewQueueStore.QueueItem handleDmSendBack(ImsReviewQueueStore.QueueItem q,
                                                           DocRow d, String actorEmail, String actorDisplay) {
        // 1. Append the send-back event
        ImsReviewQueueStore.Event back = baseEvent(ImsReviewQueueStore.EventType.DM_RESPONSE_SEND_BACK,
                q.docNumber, q.drrNumber, actorEmail, actorDisplay, "DM");
        applyTokenStamp(back);
        queueStore.append(back);

        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_DM_RESPONSE",
                "doc=" + q.docNumber + " | response=send_back");

        // 2. Cascade: re-send to the original DO (whoever was on the prior SEND_TO_DO)
        String doEmail = findOriginalDoEmail(q);
        if (doEmail == null) {
            // No prior DO — shouldn't happen, but degrade gracefully
            return queueStore.find(q.docNumber, q.drrNumber);
        }
        return send(q.docNumber, q.drrNumber, actorEmail, actorDisplay);
    }

    private String findOriginalDoEmail(ImsReviewQueueStore.QueueItem q) {
        for (ImsReviewQueueStore.Event ev : q.history) {
            if (ev.type == ImsReviewQueueStore.EventType.SEND_TO_DO
                    && ev.recipients != null && !ev.recipients.isEmpty()) {
                return ev.recipients.get(0);
            }
        }
        return null;
    }

    /**
     * Best-effort lookup of "who is the DO whose response started the No-Change
     * cascade". Used by the DM-Approve closeNoChange step so the DRR's
     * Requestor cell gets stamped with the DO's identity, not the DM's.
     *
     * <p>Prefers the {@code verifiedSamAccount} of the DO_RESPONSE_NO_CHANGE
     * event (the actual signer who confirmed). Falls back to the original
     * SEND_TO_DO recipient email when the queue item lacks a No-Change event.
     * Returns null when neither resolves — callers should pass that to
     * {@link AgileWriteBackClient#closeNoChange} which treats null/blank as
     * "leave the DRR Requestor alone".
     */
    /**
     * Collect every current Document Owner email for {@code docNumber} from
     * the docs cache. Used to Cc all DOs on outbound IMS Review emails so the
     * folks responsible for the document are kept in the loop even when
     * they're not the primary recipient (e.g. on DM-approved closure emails
     * sent To DCC, or on the IT diagnostic for a deferred DCO submit).
     *
     * <p>Returns an empty list when the cache doesn't have the doc or the
     * owner records lack email addresses. Callers should treat empty as
     * "no-op, do not Cc anyone extra".
     */
    /** Add an address to {@code p.cc} only if not already present in To or Cc
     *  (case-insensitive). Mirrors the same helper inside ImsReviewEmailService;
     *  duplicated here so the closure-email + IT-diagnostic call sites in this
     *  class can use it without exposing the package-private one. */
    private static void addCcDedupOnPayload(ImsReviewEmailService.Payload p, String addr) {
        if (p == null || addr == null) return;
        String norm = addr.trim().toLowerCase();
        if (norm.isEmpty() || !norm.contains("@")) return;
        if (p.to != null) for (String t : p.to) if (t != null && norm.equalsIgnoreCase(t)) return;
        if (p.cc == null) p.cc = new java.util.ArrayList<>();
        for (String c : p.cc) if (c != null && norm.equalsIgnoreCase(c)) return;
        p.cc.add(norm);
    }

    public java.util.List<String> getCurrentDocOwnerEmails(String docNumber) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (docNumber == null || docNumber.isEmpty()) return out;
        DocRow d = findCachedDoc(docNumber);
        if (d == null || d.owners == null) return out;
        for (OwnerRef o : d.owners) {
            if (o == null || o.email == null) continue;
            String e = o.email.trim();
            if (e.isEmpty() || !e.contains("@")) continue;
            out.add(e);
        }
        return out;
    }

    private String findDoSignerIdentity(String docNumber, String drrNumber) {
        if (docNumber == null || docNumber.isEmpty()) return null;
        ImsReviewQueueStore.QueueItem q = queueStore.find(docNumber, drrNumber == null ? "" : drrNumber);
        if (q == null || q.history == null) return null;
        // Walk backwards so that after a Send Back + re-confirm cycle we use
        // the LATEST DO confirmation, not the first one in history.
        for (int i = q.history.size() - 1; i >= 0; i--) {
            ImsReviewQueueStore.Event ev = q.history.get(i);
            if (ev.type == ImsReviewQueueStore.EventType.DO_RESPONSE_NO_CHANGE
                    && ev.verifiedSamAccount != null && !ev.verifiedSamAccount.isEmpty()) {
                return ev.verifiedSamAccount;
            }
        }
        return findOriginalDoEmail(q);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    /** Stream the admin view as .xlsx. Same columns as the on-screen table plus
     *  the queue metadata for downstream tracking. */
    public void writeExcel(OutputStream out, int daysAhead) throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) dataForAdmin(daysAhead).get("rows");
        String[] headers = {
            "Number", "Description", "Lifecycle", "Rev", "Document Type",
            "Owner(s)", "Next Review Date", "DRR", "Status",
            "Current Recipient", "Last Update"
        };
        String[] keys = {
            "docNumber", "description", "lifecyclePhase", "rev", "documentType",
            "ownerDisplay", "nextReviewDate", "drrNumber", "status",
            "currentRecipient", "lastUpdated"
        };

        SXSSFWorkbook wb = new SXSSFWorkbook(null, 500, false, false);
        try {
            Sheet sheet = wb.createSheet("IMS Review");
            CellStyle hdr = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            hdr.setFont(hf);
            hdr.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            hdr.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle alt = wb.createCellStyle();
            alt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            alt.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(hdr);
            }
            int r = 1;
            for (Map<String, Object> row : rows) {
                Row x = sheet.createRow(r);
                for (int i = 0; i < keys.length; i++) {
                    Object v = row.get(keys[i]);
                    x.createCell(i).setCellValue(v == null ? "" : v.toString());
                }
                if (r % 2 == 0) {
                    for (int c = 0; c < keys.length; c++) x.getCell(c).setCellStyle(alt);
                }
                r++;
            }
            int[] widths = { 4500, 9000, 3000, 2000, 3500, 8000, 3500, 4500, 4500, 6000, 5000 };
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
            sheet.createFreezePane(0, 1);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
            }
            wb.write(out);
        } finally {
            wb.close();
            wb.dispose();
        }
    }

    public AgileDocumentAttachmentsClient.Bundle getFile(String docNumber, String actorEmail, String actorDisplay) {
        AgileDocumentAttachmentsClient.Bundle b = agileAttachments.fetch(docNumber);
        activityLogger.log(actorEmail, actorDisplay, "IMS_REVIEW_GET_FILE",
                "doc=" + docNumber + " | files=" + b.fileCount + " | bytes=" + b.totalBytes
                + " | ok=" + b.ok);
        return b;
    }

    private ImsReviewQueueStore.Event baseEvent(ImsReviewQueueStore.EventType type,
                                                String docNumber, String drrNumber,
                                                String actorEmail, String actorDisplay,
                                                String actorRole) {
        ImsReviewQueueStore.Event e = new ImsReviewQueueStore.Event();
        e.ts = Instant.now().toString();
        e.type = type;
        e.docNumber = docNumber;
        e.drrNumber = drrNumber;
        e.actor = new ImsReviewQueueStore.Actor();
        e.actor.email = actorEmail;
        e.actor.displayName = actorDisplay;
        e.actor.role = actorRole;
        return e;
    }

    private static String todayLabel() {
        return java.time.LocalDate.now().toString();
    }

    private static String ownerDisplay(List<OwnerRef> owners) {
        if (owners == null || owners.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (OwnerRef o : owners) parts.add(o.displayName == null ? o.email : o.displayName);
        return String.join("; ", parts);
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isEmpty()) return "upload.bin";
        String base = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------------
    // SQL: pull docs due-for-review with owner emails
    // ------------------------------------------------------------------
    /** Mirrors the DocReviewService SQL but returns owner emails too. */
    private List<DocRow> pullDocsDueWithin(int daysAhead) {
        String sql =
            "WITH qi AS ( " +
            "  SELECT i.id, i.item_number, i.description, i.subclass, i.default_change, " +
            "         p3.date32 AS next_review_date " +
            "  FROM   agile.item i " +
            "  JOIN   agile.page_three p3 ON p3.id = i.id " +
            "  WHERE  i.subclass = 9141 " +
            "    AND  NVL(i.delete_flag, 0) != 1 " +
            "    AND  p3.date32 <= TRUNC(SYSDATE) + ? " +
            "), " +
            "qp AS ( " +
            "  SELECT qi.*, r_curr.rev_number, lcp.description AS lifecycle_phase " +
            "  FROM   qi " +
            "  JOIN   agile.rev       r_curr ON r_curr.item = qi.id AND r_curr.change = qi.default_change AND r_curr.site = 0 " +
            "  JOIN   agile.nodetable lcp    ON lcp.id = r_curr.release_type " +
            "  WHERE  lcp.description NOT IN ('OBS','OBS-SKU','Preliminary') " +
            "), " +
            "pc AS ( " +
            "  SELECT DISTINCT r.item AS item_id, c.change_number, sub.name AS change_type, " +
            "         sn.name AS drr_status, " +
            "         NVL(orig.last_name, '') || ', ' || NVL(orig.first_name, '') AS drr_owner, " +
            "         TO_CHAR(c.create_date, 'YYYY-MM-DD') AS drr_create_date " +
            "  FROM   agile.rev    r " +
            "  JOIN   agile.change c   ON c.id = r.change AND c.statustype IN (0,1,2) AND NVL(c.delete_flag,0) != 1 " +
            "  JOIN   agile.nodetable sub ON sub.id = c.subclass " +
            "  LEFT JOIN agile.nodetable sn   ON sn.id = c.status " +
            "  LEFT JOIN agile.agileuser orig ON orig.id = c.originator " +
            "  WHERE  r.site = 0 AND r.item IN (SELECT id FROM qp) " +
            "    AND  c.change_number LIKE 'DRR-%' " +
            "), " +
            "ow AS ( " +
            "  SELECT af.id AS item_id, u.loginid AS loginid, u.last_name AS last_name, " +
            "         u.first_name AS first_name, u.email AS email " +
            "  FROM   agile.agile_flex af " +
            "  JOIN   agile.agileuser  u ON af.text LIKE '%,' || u.id || ',%' " +
            "  WHERE  af.attid = 251733512 " +
            "    AND  af.id IN (SELECT id FROM qp) " +
            ") " +
            "SELECT qp.item_number AS doc_number, " +
            "       qp.description AS description, " +
            "       qp.lifecycle_phase AS lifecycle_phase, " +
            "       qp.rev_number AS rev, " +
            "       sub.name AS document_type, " +
            "       dstyle.entryvalue AS document_style, /* Document Style: PAGE_THREE.LIST36 -> LISTENTRY (langid 0), same source as the Shop-Floor tab (SdsmDocumentsService). */ " +
            "       TO_CHAR(qp.next_review_date, 'YYYY-MM-DD') AS next_review_date, " +
            "       pc.change_number AS drr_number, " +
            "       pc.drr_status AS drr_status, " +
            "       pc.drr_owner AS drr_owner, " +
            "       pc.drr_create_date AS drr_create_date, " +
            "       ow.loginid, ow.last_name, ow.first_name, ow.email " +
            "FROM      qp " +
            "JOIN      agile.nodetable sub ON sub.id = qp.subclass " +
            "LEFT JOIN agile.page_three p3s ON p3s.id = qp.id " +
            "LEFT JOIN (SELECT entryid, MIN(entryvalue) AS entryvalue FROM agile.listentry WHERE langid = 0 GROUP BY entryid) dstyle ON dstyle.entryid = p3s.list36 " +
            "LEFT JOIN pc ON pc.item_id = qp.id " +
            "LEFT JOIN ow ON ow.item_id = qp.id " +
            "ORDER BY  qp.item_number, pc.change_number";

        Map<String, DocRow> byKey = new LinkedHashMap<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(180);
            ps.setInt(1, daysAhead);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String doc = rs.getString("doc_number");
                    String drr = nvl(rs.getString("drr_number"));
                    String k = doc + "|" + drr;
                    DocRow d = byKey.computeIfAbsent(k, kk -> {
                        DocRow x = new DocRow();
                        try {
                            x.docNumber = rs.getString("doc_number");
                            x.drrNumber = nvl(rs.getString("drr_number"));
                            x.description = nvl(rs.getString("description"));
                            x.lifecyclePhase = nvl(rs.getString("lifecycle_phase"));
                            x.rev = nvl(rs.getString("rev"));
                            x.documentType = nvl(rs.getString("document_type"));
                            x.documentStyle = nvl(rs.getString("document_style"));
                            x.nextReviewDate = nvl(rs.getString("next_review_date"));
                            x.drrStatus = nvl(rs.getString("drr_status"));
                            x.drrOwner = nvl(rs.getString("drr_owner"));
                            x.drrCreateDate = nvl(rs.getString("drr_create_date"));
                        } catch (SQLException ignored) {}
                        return x;
                    });
                    String loginId = rs.getString("loginid");
                    if (loginId != null) {
                        OwnerRef o = new OwnerRef();
                        o.loginId = loginId;
                        o.email = rs.getString("email");
                        String ln = rs.getString("last_name"), fn = rs.getString("first_name");
                        o.displayName = (ln == null ? "" : ln) + ", " + (fn == null ? "" : fn);
                        d.owners.add(o);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[IMS-REVIEW] pull-docs SQL failed: " + e.getMessage());
        }
        return new ArrayList<>(byKey.values());
    }

    /** Pull DocRows for a specific set of document numbers, IGNORING the
     *  review-date window. Used to keep toolkit-tracked reviews (queueStore
     *  entries) visible on the dashboard after they close: implementing a DCO
     *  advances the document's Next Review Date years out, which drops it from
     *  {@link #pullDocsDueWithin} — so a closed review would otherwise vanish
     *  from every tile, including Closed.
     *
     *  <p>Two differences from {@link #pullDocsDueWithin}: (1) the base filter
     *  is {@code item_number IN (...)} instead of the date window; (2) the DRR
     *  sub-query takes the LATEST DRR per item at ANY statustype — the window
     *  query's {@code statustype IN (0,1,2)} deliberately excludes released
     *  changes, but an <b>Implemented</b> DRR (statustype 4) is exactly the one
     *  we need here so {@code hasDrr} stays true and the review classifies as
     *  closed rather than "need DRR". */
    private List<DocRow> pullDocsByNumbers(java.util.Collection<String> docNumbers) {
        List<String> nums = new ArrayList<>();
        for (String n : docNumbers) { if (n != null && !n.trim().isEmpty()) nums.add(n.trim()); }
        if (nums.isEmpty()) return new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < nums.size(); i++) in.append(i == 0 ? "?" : ",?");
        String sql =
            "WITH qi AS ( " +
            "  SELECT i.id, i.item_number, i.description, i.subclass, i.default_change, " +
            "         p3.date32 AS next_review_date " +
            "  FROM   agile.item i " +
            "  JOIN   agile.page_three p3 ON p3.id = i.id " +
            "  WHERE  i.subclass = 9141 " +
            "    AND  NVL(i.delete_flag, 0) != 1 " +
            "    AND  i.item_number IN (" + in + ") " +
            "), " +
            "qp AS ( " +
            "  SELECT qi.*, r_curr.rev_number, lcp.description AS lifecycle_phase " +
            "  FROM   qi " +
            "  JOIN   agile.rev       r_curr ON r_curr.item = qi.id AND r_curr.change = qi.default_change AND r_curr.site = 0 " +
            "  JOIN   agile.nodetable lcp    ON lcp.id = r_curr.release_type " +
            "), " +
            "pc AS ( " +
            "  SELECT item_id, change_number, drr_status, drr_owner, drr_create_date FROM ( " +
            "    SELECT r.item AS item_id, c.change_number, sn.name AS drr_status, " +
            "           NVL(orig.last_name, '') || ', ' || NVL(orig.first_name, '') AS drr_owner, " +
            "           TO_CHAR(c.create_date, 'YYYY-MM-DD') AS drr_create_date, " +
            "           ROW_NUMBER() OVER (PARTITION BY r.item ORDER BY c.create_date DESC) rn " +
            "    FROM   agile.rev    r " +
            "    JOIN   agile.change c   ON c.id = r.change AND NVL(c.delete_flag,0) != 1 " +
            "    LEFT JOIN agile.nodetable sn   ON sn.id = c.status " +
            "    LEFT JOIN agile.agileuser orig ON orig.id = c.originator " +
            "    WHERE  r.site = 0 AND r.item IN (SELECT id FROM qp) " +
            "      AND  c.change_number LIKE 'DRR-%' " +
            "  ) WHERE rn = 1 " +
            "), " +
            "ow AS ( " +
            "  SELECT af.id AS item_id, u.loginid AS loginid, u.last_name AS last_name, " +
            "         u.first_name AS first_name, u.email AS email " +
            "  FROM   agile.agile_flex af " +
            "  JOIN   agile.agileuser  u ON af.text LIKE '%,' || u.id || ',%' " +
            "  WHERE  af.attid = 251733512 " +
            "    AND  af.id IN (SELECT id FROM qp) " +
            ") " +
            "SELECT qp.item_number AS doc_number, qp.description AS description, " +
            "       qp.lifecycle_phase AS lifecycle_phase, qp.rev_number AS rev, " +
            "       sub.name AS document_type, dstyle.entryvalue AS document_style, " +
            "       TO_CHAR(qp.next_review_date, 'YYYY-MM-DD') AS next_review_date, " +
            "       pc.change_number AS drr_number, pc.drr_status AS drr_status, " +
            "       pc.drr_owner AS drr_owner, pc.drr_create_date AS drr_create_date, " +
            "       ow.loginid, ow.last_name, ow.first_name, ow.email " +
            "FROM      qp " +
            "JOIN      agile.nodetable sub ON sub.id = qp.subclass " +
            "LEFT JOIN agile.page_three p3s ON p3s.id = qp.id " +
            "LEFT JOIN (SELECT entryid, MIN(entryvalue) AS entryvalue FROM agile.listentry WHERE langid = 0 GROUP BY entryid) dstyle ON dstyle.entryid = p3s.list36 " +
            "LEFT JOIN pc ON pc.item_id = qp.id " +
            "LEFT JOIN ow ON ow.item_id = qp.id " +
            "ORDER BY  qp.item_number, pc.change_number";

        Map<String, DocRow> byKey = new LinkedHashMap<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            for (int i = 0; i < nums.size(); i++) ps.setString(i + 1, nums.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String doc = rs.getString("doc_number");
                    String drr = nvl(rs.getString("drr_number"));
                    String k = doc + "|" + drr;
                    DocRow d = byKey.computeIfAbsent(k, kk -> {
                        DocRow x = new DocRow();
                        try {
                            x.docNumber = rs.getString("doc_number");
                            x.drrNumber = nvl(rs.getString("drr_number"));
                            x.description = nvl(rs.getString("description"));
                            x.lifecyclePhase = nvl(rs.getString("lifecycle_phase"));
                            x.rev = nvl(rs.getString("rev"));
                            x.documentType = nvl(rs.getString("document_type"));
                            x.documentStyle = nvl(rs.getString("document_style"));
                            x.nextReviewDate = nvl(rs.getString("next_review_date"));
                            x.drrStatus = nvl(rs.getString("drr_status"));
                            x.drrOwner = nvl(rs.getString("drr_owner"));
                            x.drrCreateDate = nvl(rs.getString("drr_create_date"));
                        } catch (SQLException ignored) {}
                        return x;
                    });
                    String loginId = rs.getString("loginid");
                    if (loginId != null) {
                        OwnerRef o = new OwnerRef();
                        o.loginId = loginId;
                        o.email = rs.getString("email");
                        String ln = rs.getString("last_name"), fn = rs.getString("first_name");
                        o.displayName = (ln == null ? "" : ln) + ", " + (fn == null ? "" : fn);
                        d.owners.add(o);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[IMS-REVIEW] pull-docs-by-numbers SQL failed: " + e.getMessage());
        }
        return new ArrayList<>(byKey.values());
    }

    /** Look up a single doc (for hydrating DO/DM cards + send context).
     *  Used to scan 365 days of docs to find one, which fired the heavy
     *  Agile query every time a DO opened their card view. Now runs a
     *  targeted query filtered by item_number — sub-second regardless of
     *  whether the admin /data cache is warm. */
    DocRow lookupDoc(String docNumber) {
        if (docNumber == null || docNumber.isEmpty()) return null;
        String sql =
            "WITH qi AS ( " +
            "  SELECT i.id, i.item_number, i.description, i.subclass, i.default_change, " +
            "         p3.date32 AS next_review_date " +
            "  FROM   agile.item i " +
            "  JOIN   agile.page_three p3 ON p3.id = i.id " +
            "  WHERE  i.item_number = ? " +
            "    AND  NVL(i.delete_flag, 0) != 1 " +
            "), " +
            "qp AS ( " +
            "  SELECT qi.*, r_curr.rev_number, lcp.description AS lifecycle_phase " +
            "  FROM   qi " +
            "  JOIN   agile.rev       r_curr ON r_curr.item = qi.id AND r_curr.change = qi.default_change AND r_curr.site = 0 " +
            "  JOIN   agile.nodetable lcp    ON lcp.id = r_curr.release_type " +
            "), " +
            "pc AS ( " +
            "  SELECT DISTINCT r.item AS item_id, c.change_number, sub.name AS change_type " +
            "  FROM   agile.rev    r " +
            "  JOIN   agile.change c   ON c.id = r.change AND c.statustype IN (0,1,2) AND NVL(c.delete_flag,0) != 1 " +
            "  JOIN   agile.nodetable sub ON sub.id = c.subclass " +
            "  WHERE  r.site = 0 AND r.item IN (SELECT id FROM qp) " +
            "    AND  c.change_number LIKE 'DRR-%' " +
            "), " +
            "ow AS ( " +
            "  SELECT af.id AS item_id, u.loginid AS loginid, u.last_name AS last_name, " +
            "         u.first_name AS first_name, u.email AS email " +
            "  FROM   agile.agile_flex af " +
            "  JOIN   agile.agileuser  u ON af.text LIKE '%,' || u.id || ',%' " +
            "  WHERE  af.attid = 251733512 " +
            "    AND  af.id IN (SELECT id FROM qp) " +
            ") " +
            "SELECT qp.item_number AS doc_number, " +
            "       qp.description AS description, " +
            "       qp.lifecycle_phase AS lifecycle_phase, " +
            "       qp.rev_number AS rev, " +
            "       sub.name AS document_type, " +
            "       TO_CHAR(qp.next_review_date, 'YYYY-MM-DD') AS next_review_date, " +
            "       pc.change_number AS drr_number, " +
            "       ow.loginid, ow.last_name, ow.first_name, ow.email " +
            "FROM      qp " +
            "JOIN      agile.nodetable sub ON sub.id = qp.subclass " +
            "LEFT JOIN pc ON pc.item_id = qp.id " +
            "LEFT JOIN ow ON ow.item_id = qp.id " +
            "ORDER BY  pc.change_number";

        DocRow d = null;
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, docNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (d == null) {
                        d = new DocRow();
                        d.docNumber = rs.getString("doc_number");
                        d.drrNumber = nvl(rs.getString("drr_number"));
                        d.description = nvl(rs.getString("description"));
                        d.lifecyclePhase = nvl(rs.getString("lifecycle_phase"));
                        d.rev = nvl(rs.getString("rev"));
                        d.documentType = nvl(rs.getString("document_type"));
                        d.nextReviewDate = nvl(rs.getString("next_review_date"));
                    }
                    String loginId = rs.getString("loginid");
                    if (loginId != null) {
                        OwnerRef o = new OwnerRef();
                        o.loginId = loginId;
                        o.email = rs.getString("email");
                        String ln = rs.getString("last_name"), fn = rs.getString("first_name");
                        o.displayName = (ln == null ? "" : ln) + ", " + (fn == null ? "" : fn);
                        d.owners.add(o);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warning("[IMS-REVIEW] single-doc lookup failed for " + docNumber + ": " + e.getMessage());
        }
        return d;
    }

    // ==================================================================
    // Phase 5 — DCO form submission flow
    // ==================================================================

    @org.springframework.beans.factory.annotation.Value("${app.ims-review.dco-form-enabled:false}")
    private boolean dcoFormEnabled;

    /** Surfaced via /token/info so ims-respond.html knows whether to open
     *  the drawer or fall back to the inline file+notes UI. */
    public boolean isDcoFormEnabled() { return dcoFormEnabled; }

    /** Self-study training-guide URL (config: app.ims-review.training-doc-url).
     *  Surfaced on the token/info payload so the response-page DCO form can
     *  link it next to the Training Requirement field. */
    public String getTrainingDocUrl() { return trainingDocUrl == null ? "" : trainingDocUrl; }

    /** Result carrier for /token/submit-dco. */
    public static final class DcoSubmitResult {
        public boolean success;
        public String errorReason;
        public String dcoNumber;
        public String newRev;
        public int attachmentsAttached;
        public int approvers;
        public int observers;
        public int documentOwners;
        public int stakeholdersNotified;
        public String signedBy;
        public String signedEmail;
        public String pdfPath;
        public String pdfSha256;
        public String corrId;
        public Map<String, Object> fieldErrors;
        /** Whether the DCO actually advanced to Submit status in Agile. False
         *  when {@code DcoRichCreationService.create()} aborted the submit
         *  (DRR-blocked) or the {@code changeStatus(Submit)} call threw (e.g.
         *  another DCOAudit Pre-Submit check). The toolkit JS uses this to
         *  render an honest success card instead of the misleading "created
         *  and submitted" message. */
        public boolean submitted;
        /** Cause label when {@link #submitted} is false: {@code DRR_BLOCKED}
         *  (DRR couldn't be advanced Pending → Submit) or {@code AUDIT_BLOCKED}
         *  (the DCO changeStatus itself threw, typically a Pre-Submit audit
         *  hit). Null when {@link #submitted} is true. */
        public String submitBlockedReason;
    }

    /** Validates a DCO form payload without burning the token or writing to
     *  Agile. Returns the plm-agile-service response body verbatim — the
     *  toolkit JS displays the field/form errors inline. */
    public Map<String, Object> validateDcoForm(String token, Map<String, Object> form) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            out.put("ok", false);
            out.put("formErrors", Collections.singletonList("Missing token"));
            return out;
        }
        TokenContext ctx = describeToken(token);
        if (!ctx.valid) {
            out.put("ok", false);
            out.put("formErrors", Collections.singletonList(
                    ctx.errorReason == null ? "Invalid token" : ctx.errorReason));
            return out;
        }
        Map<String, Object> stamped = new LinkedHashMap<>(form == null ? Collections.<String, Object>emptyMap() : form);
        stamped.put("docNumber", ctx.docNumber);

        AgileWriteBackClient.Result r = agileWriteBack.validateForm(stamped, null);
        activityLogger.log("(token-pre-validate)", "(token-pre-validate)",
                "IMS_REVIEW_DCO_VALIDATE",
                "tokenId=" + tokenLabel(token) + " | ok=" + r.ok
              + " | http=" + r.httpStatus
              + " | corrId=" + r.corrId);
        if (r.body != null) return r.body;
        out.put("ok", false);
        out.put("formErrors", Collections.singletonList(
                "Validation service unreachable: " + nvl(r.errorReason)));
        return out;
    }

    /** Full token-based submit for the rich DCO form. LDAP verify → cross-
     *  check email matches recipient → re-validate (defense in depth) →
     *  append DO_RESPONSE_NEEDS_CHANGE event (token burn) → PDF →
     *  create-dco-rich → stakeholder SMTP → DCC alert on any failure path. */
    public synchronized DcoSubmitResult respondViaTokenWithDcoForm(
            String token, String username, String password,
            String formJson,
            org.springframework.web.multipart.MultipartFile fileRedline,
            org.springframework.web.multipart.MultipartFile fileFinal,
            java.util.List<org.springframework.web.multipart.MultipartFile> filesEmail,
            java.util.List<org.springframework.web.multipart.MultipartFile> filesOther,
            String clientIp) {

        DcoSubmitResult res = new DcoSubmitResult();

        if (!dcoFormEnabled) {
            res.errorReason = "DCO form is disabled (app.ims-review.dco-form-enabled=false).";
            return res;
        }
        if (token == null || token.isEmpty()) {
            res.errorReason = "Missing token.";
            return res;
        }

        // 1. Describe token + role guard
        TokenContext ctx = describeToken(token);
        if (!ctx.valid) {
            res.errorReason = ctx.errorReason == null ? "Invalid token" : ctx.errorReason;
            return res;
        }
        if (!"DO".equals(ctx.role)) {
            res.errorReason = "DCO form is only available for the DO Needs Change action.";
            return res;
        }
        // Guard against empty DRR number — the queue item was created when
        // the docs query returned no pending DRR for this IMS doc. Without
        // a DRR we can't create-dco-rich (the URL becomes /api/drr//create-
        // dco-rich which Spring returns as a 404 with an HTML body, and the
        // toolkit can only report it as a generic "DCO creation failed").
        // Fail fast with a clean message before touching LDAP / PDF / SDK.
        if (ctx.drrNumber == null || ctx.drrNumber.trim().isEmpty()) {
            res.errorReason = "No DRR is currently linked to document " + ctx.docNumber
                    + ". Doc Control needs to ensure a Pending DRR exists for this document"
                    + " (the yearly-audit job creates one). Contact pdl-plm-admin@sandisk.com.";
            return res;
        }

        // 2a. Wrong-recipient pre-check (mirrors respondViaToken — see comment there)
        String wrongRecipient = wrongRecipientHint(username, ctx);
        if (wrongRecipient != null) {
            recordTokenFail(token);
            res.errorReason = wrongRecipient;
            return res;
        }
        // 2b. LDAP verify
        LdapAuthService.VerifyResult v = ldapAuthService.verifyCredentials(username, password);
        if (!v.success) {
            recordTokenFail(token);
            res.errorReason = maybeWrongRecipientFollowup(username, ctx, v.message);
            return res;
        }
        // Verified — clear any accumulated fail counter (same as the non-rich path).
        clearTokenFail(token);
        String verifiedEmail = v.email == null ? "" : v.email.trim().toLowerCase();
        boolean inAllowed = ctx.allowedActors != null && ctx.allowedActors.contains(verifiedEmail);
        if (!inAllowed) {
            boolean dmDlCase = ctx.allowedActor != null
                    && ctx.allowedActor.equalsIgnoreCase(emailService.managersDl);
            if (!dmDlCase) {
                recordTokenFail(token);
                String who = (ctx.allowedActors == null || ctx.allowedActors.isEmpty())
                        ? ctx.allowedActor
                        : String.join(" or ", ctx.allowedActors);
                res.errorReason = "This link was sent to " + who + " — sign in as one of those people.";
                return res;
            }
        }

        // 3. Parse form payload + stamp docNumber from token context (don't
        //    trust the caller). Re-serialize so the enriched payload is what
        //    plm-agile-service sees — passing the original (un-stamped)
        //    formJson string downstream would land null docNumber on the
        //    server and the IItem lookup would throw APIException:Invalid
        //    parameter at step=affectedItem.
        Map<String, Object> form;
        String enrichedFormJson;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = om.readValue(formJson, Map.class);
            form = parsed;
            form.put("docNumber", ctx.docNumber);
            // 2026-06-03: stamp the verified signer as the DCO Requestor so
            // plm-agile-service can set the Cover Page Requestor cell to the
            // person who actually signed the form. We send the sAMAccountName
            // (e.g. "8252"), NOT the email — the Agile SDK's
            // session.getObject(IUser.OBJECT_TYPE, ...) call keys by user-id
            // in this environment and returns null when given an email.
            // Confirmed against DCO-525540: first attempt sent
            // "vikas.jindal@sandisk.com" and the IUser lookup returned null,
            // leaving the Requestor as Administrator; the closeNoChange path
            // (line ~1685) uses ev.verifiedSamAccount and succeeds. Same
            // identifier pattern there. If the signer isn't a valid Agile
            // user, plm-agile-service silently falls back to the SDK
            // session-user default ("Administrator"), which is what we want
            // in that case (per Jimmy/Vikas's call).
            if (v != null && v.samAccount != null && !v.samAccount.isEmpty()) {
                form.put("requestorEmail", v.samAccount);
            }
            // Identity of the person who actually SIGNED this form (LDAP-verified
            // moments ago), passed verbatim so plm-agile-service can stamp the
            // document's Agile History table with who made the SDSM attribute
            // edits and under which DCO. Deliberately NOT the Cover Page
            // Requestor IUser — that's a separate lookup that silently falls back
            // to "Administrator" when the signer isn't a registered Agile user.
            if (v != null) {
                if (v.displayName != null && !v.displayName.isEmpty()) form.put("signedByName", v.displayName);
                if (v.samAccount != null && !v.samAccount.isEmpty())   form.put("signedByLogin", v.samAccount);
            }
            enrichedFormJson = om.writeValueAsString(form);
        } catch (Exception parseErr) {
            res.errorReason = "Bad form JSON: " + parseErr.getMessage();
            return res;
        }

        // 4. Re-validate (defense in depth — payload may have mutated since pre-validate)
        AgileWriteBackClient.Result vr = agileWriteBack.validateForm(form, null);
        if (!vr.ok) {
            res.errorReason = "Form failed re-validation. Please reload and retry.";
            if (vr.body != null && vr.body.get("fieldErrors") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fe = (Map<String, Object>) vr.body.get("fieldErrors");
                res.fieldErrors = fe;
            }
            return res;
        }

        DocRow d = lookupDoc(ctx.docNumber);

        // 5. Append DO_RESPONSE_NEEDS_CHANGE event (TOKEN BURN HERE)
        ImsReviewQueueStore.Event ev = baseEvent(
                ImsReviewQueueStore.EventType.DO_RESPONSE_NEEDS_CHANGE,
                ctx.docNumber, ctx.drrNumber,
                verifiedEmail, v.displayName, "DO");
        ev.recipients = Collections.singletonList(emailService.dccDl);
        ev.redeemsToken = token;
        ev.verifiedSamAccount = v.samAccount;
        ev.verifiedDisplayName = v.displayName;
        ev.verifiedEmail = v.email;
        ev.verifyIp = clientIp;
        ev.dcoForm = form;
        ev.dcoFormChecksum = sha256(canonicalize(form));
        List<Map<String, Object>> manifest = new ArrayList<>();
        addManifestEntry(manifest, "Redline", fileRedline);
        addManifestEntry(manifest, "Final", fileFinal);
        if (filesEmail != null) for (org.springframework.web.multipart.MultipartFile mf : filesEmail) addManifestEntry(manifest, "Email", mf);
        if (filesOther != null) for (org.springframework.web.multipart.MultipartFile mf : filesOther) addManifestEntry(manifest, "Other", mf);
        ev.dcoAttachmentsManifest = manifest;
        ev.note = "Rich DCO form submission";
        ev.fileCount = manifest.size();
        queueStore.append(ev);

        activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_FORM_SUBMIT",
                "tokenId=" + tokenLabel(token)
              + " | doc=" + ctx.docNumber
              + " | drr=" + nvl(ctx.drrNumber)
              + " | dcoFormChecksum=" + (ev.dcoFormChecksum == null ? "" : ev.dcoFormChecksum.substring(0, Math.min(16, ev.dcoFormChecksum.length())) + "…")
              + " | attachmentCount=" + manifest.size());

        // 6. Generate compliance PDF
        byte[] pdfBytes = null;
        try {
            ImsReviewPdfService.ApprovalInput in = new ImsReviewPdfService.ApprovalInput();
            in.docNumber = ctx.docNumber;
            in.description = ctx.description;
            in.rev = ctx.rev;
            in.lifecyclePhase = ctx.lifecyclePhase;
            in.documentType = ctx.documentType;
            in.nextReviewDate = ctx.nextReviewDate;
            in.drrNumber = ctx.drrNumber;
            in.role = "DO";
            in.actionLabel = "Needs Change — DCO Submitted via Toolkit";
            in.signerDisplayName = v.displayName;
            in.signerEmail = v.email;
            in.signerSamAccount = v.samAccount;
            in.signedAtUtc = ev.ts;
            in.signerIp = clientIp;
            in.sendEventUuid = token;
            in.responseEventTs = ev.ts;
            in.dcoForm = form;
            in.dcoFormChecksum = ev.dcoFormChecksum;
            ImsReviewPdfService.GeneratedPdf pdf = pdfService.generate(in);
            ev.pdfPath = pdf.relPath;
            ev.pdfSha256 = pdf.sha256;
            res.pdfPath = pdf.relPath;
            res.pdfSha256 = pdf.sha256;

            // Append PDF_GENERATED audit event
            ImsReviewQueueStore.Event pdfEv = baseEvent(
                    ImsReviewQueueStore.EventType.PDF_GENERATED,
                    ctx.docNumber, ctx.drrNumber, verifiedEmail, "PLM Toolkit", "DO");
            pdfEv.redeemsToken = token;
            pdfEv.pdfPath = pdf.relPath;
            pdfEv.pdfSha256 = pdf.sha256;
            pdfEv.verifiedSamAccount = v.samAccount;
            pdfEv.verifiedDisplayName = v.displayName;
            pdfEv.verifiedEmail = v.email;
            queueStore.append(pdfEv);

            try { pdfBytes = pdfService.readPdf(pdf.relPath); } catch (Exception ignored) {}
        } catch (Exception pdfErr) {
            LOG.warning("[IMS-REVIEW] PDF generation failed: " + pdfErr.getMessage());
            // Non-fatal — proceed to Agile write
        }

        // 7. Call create-dco-rich
        List<AgileWriteBackClient.DcoNamedBlob> blobs = new ArrayList<>();
        addBlob(blobs, "file_redline", fileRedline);
        addBlob(blobs, "file_final", fileFinal);
        if (filesEmail != null) for (org.springframework.web.multipart.MultipartFile mf : filesEmail) addBlob(blobs, "file_email", mf);
        if (filesOther != null) for (org.springframework.web.multipart.MultipartFile mf : filesOther) addBlob(blobs, "file_other", mf);
        // Email Notification (hard DCO-submit audit): the signed compliance PDF
        // (populated with the form data) is ALWAYS attached as an "Email
        // Notification" type on the DCO, BEFORE submit. DCOAudit hard-blocks the
        // DCO submit unless at least one "Email Notification" attachment is
        // present, so attaching the auto PDF here guarantees the audit passes
        // whether or not the user also uploaded their own Email Copy file(s) —
        // file_email parts map to attachment type "Email Notification" in
        // DcoRichCreationService.mapTypeToAgile. Previously this only fired when
        // NO Email Copy was uploaded, which left the auto PDF on the DCO with no
        // attachment type (flagged 2026-06-17 on DCO-523111).
        if (pdfBytes != null && pdfBytes.length > 0) {
            blobs.add(new AgileWriteBackClient.DcoNamedBlob(
                    "file_email", "IMS-Stakeholder-Notification-" + nvl(ctx.docNumber) + ".pdf", pdfBytes));
        }

        AgileWriteBackClient.Result cr = agileWriteBack.createDcoRich(
                ctx.drrNumber, enrichedFormJson,   // pass enriched (docNumber-stamped) JSON
                d == null ? "" : nvl(d.rev),
                blobs, null);

        ev.agileCorrId = cr.corrId;
        res.corrId = cr.corrId;

        if (!cr.ok || cr.body == null) {
            res.errorReason = "DCO creation failed: " + nvl(cr.errorReason);
            ev.agileError = res.errorReason;
            ev.agileErrorAt = cr.body == null ? "(transport)" : String.valueOf(cr.body.get("stepFailedAt"));
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_FAILED",
                    "tokenId=" + tokenLabel(token)
                  + " | drr=" + nvl(ctx.drrNumber)
                  + " | corrId=" + cr.corrId
                  + " | agileErrorAt=" + nvl(ev.agileErrorAt)
                  + " | agileError=" + nvl(ev.agileError));
            appendDcoAgileAudit(ev, ctx);
            try {
                emailService.sendDcoFailedAlert(ctx, v, cr);
            } catch (Exception alertErr) {
                LOG.warning("[IMS-REVIEW] DCC alert send failed: " + alertErr.getMessage());
            }
            return res;
        }

        // Success — stamp DCO# and counts
        ev.agileDco = (String) cr.body.get("dcoNumber");
        res.dcoNumber = ev.agileDco;
        res.newRev = (String) cr.body.get("newRev");
        res.attachmentsAttached = intOf(cr.body.get("attachmentsAttached"));
        res.approvers = intOf(cr.body.get("approvers"));
        res.observers = intOf(cr.body.get("observers"));
        res.documentOwners = intOf(cr.body.get("documentOwners"));

        @SuppressWarnings("unchecked")
        List<String> stepsOk = (List<String>) cr.body.get("stepsOk");
        ev.agileSteps = stepsOk;

        activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_CREATED",
                "tokenId=" + tokenLabel(token)
              + " | corrId=" + cr.corrId
              + " | dcoNumber=" + res.dcoNumber
              + " | drr=" + nvl(ctx.drrNumber)
              + " | newRev=" + nvl(res.newRev)
              + " | attachments=" + res.attachmentsAttached);

        // The DCO was created but plm-agile-service may not have completed
        // the auto-submit. Two distinct failure modes both leave the DCO in
        // Pending:
        //   (a) dcoSubmitSkippedDueToDrr=true — the DRR couldn't be advanced
        //       Pending → Submit (so DCO submit was deliberately skipped to
        //       avoid the Pending-DRR audit error on the DCO).
        //   (b) submitted=false WITHOUT (a) — DCO.changeStatus(Submit) was
        //       attempted but threw (typically DCOAudit hitting another
        //       missing-cell check that the toolkit didn't pre-populate).
        //       DcoRichCreationService catches this and returns ok=true with
        //       currentStatus="(submit failed: <error>)".
        //
        // Both cases need to (i) notify the signer + PLM IT so nobody is
        // left wondering why the DCO didn't move, and (ii) suppress the
        // [New DCO] stakeholder email below (gated on dcoActuallySubmitted).
        //
        // History: gate originally checked dcoSubmitSkippedDueToDrr only —
        // when changeStatus failed for a non-DRR reason (e.g. blank Training
        // Requirement audit on DCO-530023, 2026-06-08) the [New DCO] email
        // still fired even though the DCO was stuck in Pending. Fixed by
        // switching to !dcoActuallySubmitted as the canonical "DCO didn't
        // submit" signal.
        boolean dcoSubmitSkippedDueToDrr = Boolean.TRUE.equals(cr.body.get("dcoSubmitSkippedDueToDrr"));
        boolean dcoActuallySubmitted = Boolean.TRUE.equals(cr.body.get("submitted"));
        String currentStatus = nvl((String) cr.body.get("currentStatus"));
        res.submitted = dcoActuallySubmitted;
        res.submitBlockedReason = dcoActuallySubmitted ? null
                : (dcoSubmitSkippedDueToDrr ? "DRR_BLOCKED" : "AUDIT_BLOCKED");
        if (dcoSubmitSkippedDueToDrr) {
            String drrSubmitErr = nvl((String) cr.body.get("drrSubmitErrorDetail"));
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_SUBMIT_DEFERRED",
                    "tokenId=" + tokenLabel(token)
                  + " | corrId=" + cr.corrId
                  + " | dcoNumber=" + res.dcoNumber
                  + " | drr=" + nvl(ctx.drrNumber)
                  + " | reason=" + drrSubmitErr);
            try {
                emailService.sendDcoSubmitDeferredEmails(ctx, v, res.dcoNumber, drrSubmitErr, cr.corrId);
            } catch (Exception notifyErr) {
                LOG.warning("[IMS-REVIEW] DCO-submit-deferred notify failed: " + notifyErr.getMessage());
            }
        } else if (!dcoActuallySubmitted) {
            // changeStatus failed after the DCO was fully populated — extract the
            // SDK error message from the "(submit failed: <error>)" envelope
            // DcoRichCreationService writes into currentStatus.
            String submitErr = currentStatus;
            if (submitErr.startsWith("(submit failed: ") && submitErr.endsWith(")")) {
                submitErr = submitErr.substring("(submit failed: ".length(), submitErr.length() - 1);
            }
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_SUBMIT_FAILED",
                    "tokenId=" + tokenLabel(token)
                  + " | corrId=" + cr.corrId
                  + " | dcoNumber=" + res.dcoNumber
                  + " | drr=" + nvl(ctx.drrNumber)
                  + " | reason=" + submitErr);
            try {
                emailService.sendDcoSubmitFailedEmails(ctx, v, res.dcoNumber, submitErr, cr.corrId);
            } catch (Exception notifyErr) {
                LOG.warning("[IMS-REVIEW] DCO-submit-failed notify failed: " + notifyErr.getMessage());
            }
        }

        // 7.5. Attach the DO's signed attestation PDF to BOTH the DRR and the
        // newly-created DCO so the audit trail in Agile mirrors what the email
        // recipients received. Best-effort: each call logs its own failure;
        // neither blocks the success return (the DCO is already created and
        // submitted by this point).
        // DRR only (dcoNumber=null): the DCO already received this exact PDF
        // pre-submit as the "Email Notification" attachment (blob assembly
        // above), so re-attaching it here would leave a second, untyped copy on
        // the DCO. The DRR has no Email Notification audit, so it keeps the
        // attestation-named copy.
        attachAttestationPdfToChanges(pdfBytes, ctx, null, "DO", cr.corrId);

        appendDcoAgileAudit(ev, ctx);

        // 8. Stakeholder notification SMTP (best-effort)
        //
        // 2026-06-05 — skip the "[New DCO]" stakeholder notification when the
        // DCO submit was deferred. The email subject + body imply the DCO was
        // created AND submitted; sending it while the DCO is still Pending
        // (because the DRR couldn't be submitted, e.g. Requestor=Administrator)
        // confused Bibi Kolam — three notifications for one event with
        // contradictory state. The deferred-notice email (sent above when
        // dcoSubmitSkipped=true) carries the truthful "PLM IT is tracking
        // the submission" wording. Once the DRR-side issue is resolved and
        // the DCO actually submits, Agile's own workflow fires its standard
        // [New DCO] notification to approvers/observers — so stakeholders
        // are not left in the dark.
        if (!dcoActuallySubmitted) {
            String skipReason = dcoSubmitSkippedDueToDrr
                    ? "dcoSubmitSkippedDueToDrr"
                    : "dcoSubmitFailed:" + currentStatus;
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_STAKEHOLDER_NOTIFY_SKIPPED",
                    "tokenId=" + tokenLabel(token)
                  + " | dco=" + res.dcoNumber
                  + " | reason=" + skipReason + " — Agile will fire its own notify on actual submit");
        } else {
            try {
                int notified = emailService.sendStakeholderNotify(ctx, v, form,
                        res.dcoNumber, res.newRev, res.attachmentsAttached, pdfBytes);
                res.stakeholdersNotified = notified;
                activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_STAKEHOLDER_NOTIFY",
                        "tokenId=" + tokenLabel(token)
                      + " | dco=" + res.dcoNumber
                      + " | recipients=" + notified
                      + " | ok=true");
            } catch (Exception notifyErr) {
                LOG.warning("[IMS-REVIEW] stakeholder notify failed: " + notifyErr.getMessage());
                activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_STAKEHOLDER_NOTIFY_FAILED",
                        "tokenId=" + tokenLabel(token)
                      + " | dco=" + res.dcoNumber
                      + " | err=" + notifyErr.getMessage());
                try { emailService.sendStakeholderNotifyFailedAlert(ctx, res.dcoNumber, form, notifyErr); }
                catch (Exception ignored) {}
            }
        }

        res.success = true;
        res.signedBy = v.displayName;
        res.signedEmail = v.email;
        return res;
    }

    // ------------------------------------------------------------------
    // Phase 5 helpers
    // ------------------------------------------------------------------
    private static int intOf(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return 0;
    }

    private static void addManifestEntry(List<Map<String, Object>> manifest,
                                         String type,
                                         org.springframework.web.multipart.MultipartFile f) {
        if (f == null || f.isEmpty()) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("filename", f.getOriginalFilename());
        e.put("sizeBytes", f.getSize());
        manifest.add(e);
    }

    private static void addBlob(List<AgileWriteBackClient.DcoNamedBlob> blobs,
                                String partName,
                                org.springframework.web.multipart.MultipartFile f) {
        if (f == null || f.isEmpty()) return;
        try {
            blobs.add(new AgileWriteBackClient.DcoNamedBlob(
                    partName, f.getOriginalFilename(), f.getBytes()));
        } catch (Exception ignored) {}
    }

    /** Stable JSON serialization for checksumming — keys sorted, no whitespace. */
    private static String canonicalize(Map<String, Object> form) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(form);
            return om.writeValueAsString(sorted);
        } catch (Exception e) {
            return String.valueOf(form);
        }
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "(hash-failed)";
        }
    }

    private static String tokenLabel(String token) {
        if (token == null || token.length() < 8) return "(short)";
        return token.substring(0, 8) + "…";
    }

    /** Append an AGILE_WRITEBACK audit event mirroring the cascade outcome. */
    private void appendDcoAgileAudit(ImsReviewQueueStore.Event src, TokenContext ctx) {
        ImsReviewQueueStore.Event audit = baseEvent(
                ImsReviewQueueStore.EventType.AGILE_WRITEBACK,
                ctx.docNumber, ctx.drrNumber,
                src.actor == null ? null : src.actor.email,
                src.actor == null ? null : src.actor.displayName,
                src.actor == null ? null : src.actor.role);
        audit.agileCorrId = src.agileCorrId;
        audit.agileDrr = src.agileDrr;
        audit.agileDco = src.agileDco;
        audit.agileSteps = src.agileSteps == null ? null : new ArrayList<>(src.agileSteps);
        audit.agileError = src.agileError;
        audit.agileErrorAt = src.agileErrorAt;
        audit.redeemsToken = src.redeemsToken;
        queueStore.append(audit);
    }

    // ==================================================================
    // Auto-retry of transient transport failures (find-drr Connection refused)
    // ==================================================================

    /** Whether an Agile write-back error string looks like a transport-class
     *  failure (plm-agile-service unreachable). These are safe to blind-retry
     *  because no Agile state was mutated yet. The classifier matches both
     *  the AgileWriteBackClient transport-error stamp
     *  ({@code ConnectException:Connection refused: getsockopt}) and the
     *  Spring/Java network exception class names. Permanent errors (e.g.
     *  {@code APIException: DRR not found}) are NOT matched — retrying those
     *  would never succeed and would just clutter the queue. */
    private static boolean isTransientTransportError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase();
        return s.contains("connectexception")
            || s.contains("connection refused")
            || s.contains("connection reset")
            || s.contains("sockettimeout")
            || s.contains("read timed out")
            || s.contains("connect timed out")
            || s.contains("nohttpresponseexception")
            || s.contains("unknownhostexception");
    }

    /** Scheduled retry runner — fires every minute, processes any retry
     *  queue entries whose nextAttemptAtMs has elapsed.
     *
     *  <p>For each due entry, calls {@link #replayWriteback} (the same
     *  code the admin endpoint uses). Result drives:
     *  <ul>
     *    <li>Success → entry removed from queue, IMS_REVIEW_RETRY_SUCCEEDED
     *        logged.</li>
     *    <li>Still failing with a transport error → reschedule per the
     *        BACKOFF_MS ladder (1m → 5m → 15m → 60m → give up).</li>
     *    <li>Non-transient failure → give up immediately (a non-transport
     *        error means the cause is structural — retrying won't help).</li>
     *  </ul>
     *
     *  <p>On give-up, fires one alert email to pdl-plm-admin so we know to
     *  step in. Local dev runs with {@code app.scheduling.disabled=true} so
     *  this never fires on the Mac.
     */
    @Scheduled(fixedDelayString = "${app.ims-review.retry-runner-interval-ms:60000}",
               initialDelayString = "${app.ims-review.retry-runner-initial-delay-ms:30000}")
    public void runRetryQueue() {
        List<ImsReviewRetryQueue.Entry> due = retryQueue.dueNow();
        if (due.isEmpty()) return;
        LOG.info("[IMS-RETRY] runner picking up " + due.size() + " due entries");
        for (ImsReviewRetryQueue.Entry e : due) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> result;
            try {
                result = replayWriteback(e.docNumber, e.drrNumber, e.eventType,
                        "(system)", "PLM Toolkit Retry Runner");
            } catch (Exception ex) {
                LOG.warning("[IMS-RETRY] replay threw for id=" + e.id + ": " + ex.getMessage());
                retryQueue.markAttempted(e, false, ex.getClass().getSimpleName() + ":" + ex.getMessage(), null);
                continue;
            }
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            String corrId = (String) result.get("corrId");
            String err = (String) result.get("agileError");
            long elapsed = System.currentTimeMillis() - t0;
            if (ok) {
                retryQueue.markAttempted(e, true, null, corrId);
                activityLogger.log("(system)", "PLM Toolkit Retry Runner",
                        "IMS_REVIEW_RETRY_SUCCEEDED",
                        "doc=" + e.docNumber + " | drr=" + nvl(e.drrNumber)
                      + " | eventType=" + nvl(e.eventType)
                      + " | retryId=" + e.id
                      + " | attempts=" + (e.attempts + 1)
                      + " | corrId=" + nvl(corrId)
                      + " | elapsedMs=" + elapsed);
                continue;
            }
            // Still failing — classify
            boolean transient_ = isTransientTransportError(err);
            retryQueue.markAttempted(e, false, err, corrId);
            String tag = transient_ ? "IMS_REVIEW_RETRY_ATTEMPT_TRANSIENT_FAIL"
                                    : "IMS_REVIEW_RETRY_ATTEMPT_PERMANENT_FAIL";
            activityLogger.log("(system)", "PLM Toolkit Retry Runner", tag,
                    "doc=" + e.docNumber + " | drr=" + nvl(e.drrNumber)
                  + " | eventType=" + nvl(e.eventType)
                  + " | retryId=" + e.id
                  + " | attempts=" + e.attempts
                  + " | gaveUp=" + e.gaveUp
                  + " | error=" + nvl(err));
            // Non-transient failure → give up immediately and alert (skip the
            // remaining backoff schedule — retrying would never succeed)
            if (!transient_ && !e.gaveUp) {
                // Force a give-up by exhausting attempts. Future markAttempted
                // calls would set gaveUp=true, but we want it now.
                while (!e.gaveUp) {
                    retryQueue.markAttempted(e, false, err, corrId);
                }
                fireGaveUpAlert(e);
            } else if (e.gaveUp) {
                fireGaveUpAlert(e);
            }
        }
    }

    /** Email pdl-plm-admin when a retry entry is given up on. Keeps the
     *  failure visible so a human can manually replay via the admin endpoint
     *  after fixing the root cause. */
    private void fireGaveUpAlert(ImsReviewRetryQueue.Entry e) {
        try {
            String subject = "[PLM IT] IMS Review retry gave up — " + e.docNumber + " / " + nvl(e.drrNumber);
            StringBuilder body = new StringBuilder();
            body.append("<p>The toolkit's auto-retry runner gave up on an IMS Review Agile write-back ")
                .append("after exhausting the backoff schedule.</p>")
                .append("<table cellpadding='4' style='border-collapse:collapse;'>")
                .append("<tr><td><strong>Doc</strong></td><td><code>").append(esc(e.docNumber)).append("</code></td></tr>")
                .append("<tr><td><strong>DRR</strong></td><td><code>").append(esc(nvl(e.drrNumber))).append("</code></td></tr>")
                .append("<tr><td><strong>Event</strong></td><td>").append(esc(nvl(e.eventType))).append("</td></tr>")
                .append("<tr><td><strong>Retry ID</strong></td><td><code>").append(esc(e.id)).append("</code></td></tr>")
                .append("<tr><td><strong>Attempts</strong></td><td>").append(e.attempts).append("</td></tr>")
                .append("<tr><td><strong>Enqueued</strong></td><td>").append(esc(nvl(e.enqueuedAt))).append("</td></tr>")
                .append("<tr><td><strong>Last attempt</strong></td><td>").append(esc(nvl(e.lastAttemptAt))).append("</td></tr>")
                .append("<tr><td><strong>Last error</strong></td><td>").append(esc(nvl(e.lastErrorReason))).append("</td></tr>")
                .append("</table>")
                .append("<p>Fix the underlying cause (agile-service down? Agile rejecting the event?) ")
                .append("then manually replay via:</p>")
                .append("<pre>POST /api/ims-review/admin/replay-writeback\n")
                .append("{\"docNumber\":\"").append(esc(e.docNumber)).append("\",")
                .append("\"drrNumber\":\"").append(esc(nvl(e.drrNumber))).append("\",")
                .append("\"eventType\":\"").append(esc(nvl(e.eventType))).append("\"}</pre>");
            emailService.sendAdminAlertHtml(subject, body.toString());
        } catch (Exception alertErr) {
            LOG.warning("[IMS-RETRY] give-up alert email failed: " + alertErr.getMessage());
        }
    }

    /** Exposed for the admin diagnostic endpoint. */
    public Map<String, Object> retryQueueSummary() {
        return retryQueue.summary();
    }

    /** Exposed for the admin "clear stuck entry" endpoint. */
    public boolean removeRetryEntry(String id) {
        return retryQueue.removeById(id);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
