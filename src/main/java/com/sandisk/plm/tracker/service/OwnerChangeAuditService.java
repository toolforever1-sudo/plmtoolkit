package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner Change Audit — Jimmy Sessumes's chat ask 2026-06-05:
 *
 * <blockquote>"When we update the doc owner in agile is it possible to identify
 * that action in the history of the part in a way we can run reports on or
 * pull data automatically into the tool? Maybe have action add a comment
 * 'updated via PLM Toolkit' or something that we can latch onto."</blockquote>
 *
 * <p>Reads Agile's {@code ITEM_HISTORY} table for IMS documents (subclass 9141)
 * and surfaces every Document Owner change with:
 * <ul>
 *   <li>doc number + description (resolved against {@code ITEM})</li>
 *   <li>parsed old / new owner sets (extracted from the DETAILS CLOB)</li>
 *   <li>when the change happened</li>
 *   <li>who instigated it &mdash; toolkit user (from the marker line written
 *       by {@code ItemOwnerService.updateOwners}) or "Direct in Agile" when
 *       no marker is found</li>
 * </ul>
 *
 * <p>Toolkit-driven changes write two rows to ITEM_HISTORY:
 * <ol>
 *   <li>Agile's auto cell-change row (action: Modify, DETAILS contains
 *       "Document Owner" cell name + old/new value pair)</li>
 *   <li>The toolkit's {@code logActionWOVersionChange()} marker row whose
 *       DETAILS begins with {@code "Owners updated via PLM Toolkit"} and
 *       names the AD user.</li>
 * </ol>
 *
 * <p>Matching: we pull both row types and pair each cell-change with its
 * closest marker row (same ITEM, timestamp within {@link #MARKER_PAIR_WINDOW_SEC}).
 * Unpaired cell-change rows are surfaced as "Direct in Agile".
 */
@Service
public class OwnerChangeAuditService {

    private static final Logger LOG = Logger.getLogger(OwnerChangeAuditService.class.getName());

    /** Seconds — how close a "marker" row needs to be (in time) to a cell-change
     *  row to be considered the same toolkit action. Agile commits both
     *  within the same SDK call so they typically land within 1-2 seconds;
     *  10s is generous and accounts for slow DB write fan-out. */
    private static final int MARKER_PAIR_WINDOW_SEC = 10;

    /** Substring filter for the cell-change rows. Agile's DETAILS for an
     *  owners-cell modify reads something like:
     *  {@code "Modified Cover Page.Document Owner(s) from 'A' to 'B'"}. The
     *  substring is permissive so phrasing variants match too. */
    private static final String OWNER_CELL_DETAILS_LIKE = "%Document Owner%";

    /** Prefix the toolkit writes via {@code ItemOwnerService.logActionWOVersionChange}. */
    private static final String TOOLKIT_MARKER_PREFIX = "Owners updated via PLM Toolkit";

    /** Pattern parses {@code "by Vikas Singh (vikas.singh3)"} out of the
     *  marker line so the UI can show just the human name. */
    private static final Pattern MARKER_USER_PATTERN =
            Pattern.compile("by\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** Agile's actual DETAILS format for an owner-cell change is:
     *  {@code "HISTORY0:<Document Details.Document Owner(s)>was<OLD>is<NEW>HISTORY"}
     *  where OLD and NEW are comma-separated owner-name lists (each
     *  rendered as "Last, First (loginid)"). The regex captures the two
     *  bracketed values that follow {@code "was"} and {@code "is"}.
     *  The angle-brackets are literal — no quoting. */
    private static final Pattern OWNERS_WAS_IS_PATTERN =
            Pattern.compile("Document Owner.*?was<(.*?)>is<(.*?)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Hardcoded IMS subclass id — matches {@code ImsReviewService.pullDocsDueWithin}. */
    private static final int IMS_SUBCLASS_ID = 9141;

    private final DataSource agileDataSource;
    private final ObjectMapper cacheMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Value("${app.ims-review.owner-audit-default-days:90}")
    private int defaultDays;

    /** Maximum window we cache. Anything larger than this falls through to a
     *  live SQL pull and won't benefit from the snapshot. 365 covers the
     *  Days dropdown's "Last 365" option. */
    private static final int CACHE_DAYS = 365;
    private static final long CACHE_TTL_MS = 60L * 60 * 1000;   // 60 min
    private static final String CACHE_FILE = "./data/ims-review/owner-audit-cache.json";

    /** Guarded by {@code this}. */
    private List<Row> cachedRows = null;
    private long cachedAt = 0L;

    public OwnerChangeAuditService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    /** On-disk snapshot envelope. */
    public static final class CacheSnapshot {
        public int version = 1;
        public String savedAt;
        public long cachedAt;
        public int days;
        public List<Row> rows = new ArrayList<>();
    }

    @PostConstruct
    public void loadFromDisk() {
        File f = new File(CACHE_FILE);
        if (!f.exists()) {
            LOG.info("[OWNER-AUDIT] no snapshot at " + CACHE_FILE + " — will warm on first /owner-audit call");
            return;
        }
        try {
            CacheSnapshot snap = cacheMapper.readValue(f, CacheSnapshot.class);
            if (snap == null || snap.rows == null) return;
            synchronized (this) {
                cachedRows = snap.rows;
                cachedAt = snap.cachedAt;
            }
            LOG.info("[OWNER-AUDIT] loaded snapshot from " + CACHE_FILE
                    + " rows=" + snap.rows.size() + " savedAt=" + snap.savedAt);
        } catch (Exception e) {
            LOG.warning("[OWNER-AUDIT] snapshot load failed: " + e.getMessage());
        }
    }

    /** Hourly background rebuild — same gating pattern as IMS Docs and IT
     *  Enhancements cache. Honors {@code app.scheduling.disabled} via
     *  {@code Application.SchedulingConfig} so local dev never fires it. */
    @Scheduled(cron = "0 30 * * * *")
    public void scheduledRebuild() {
        long t0 = System.currentTimeMillis();
        try {
            List<Row> fresh = pullFromSql(CACHE_DAYS);
            long now = System.currentTimeMillis();
            synchronized (this) {
                cachedRows = fresh;
                cachedAt = now;
            }
            persistToDisk();
            LOG.info("[OWNER-AUDIT] hourly rebuild done rows=" + fresh.size()
                    + " in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            LOG.warning("[OWNER-AUDIT] hourly rebuild failed (keeping prior snapshot): " + e.getMessage());
        }
    }

    private synchronized void persistToDisk() {
        try {
            CacheSnapshot snap = new CacheSnapshot();
            snap.version = 1;
            snap.savedAt = Instant.now().toString();
            snap.cachedAt = cachedAt;
            snap.days = CACHE_DAYS;
            snap.rows = cachedRows == null ? new ArrayList<>() : cachedRows;
            File out = new File(CACHE_FILE);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(CACHE_FILE + ".tmp");
            cacheMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, snap);
            Files.move(tmp.toPath(), out.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("[OWNER-AUDIT] persist snapshot failed: " + e.getMessage());
        }
    }

    /** Returns the cache state for the freshness indicator in the UI. */
    public synchronized long getCachedAt() { return cachedAt; }

    /** One surfaced row in the Owner Change Audit grid. */
    public static final class Row {
        public String docNumber;
        public String description;
        public String oldOwners;       // semicolon-joined; raw from Agile DETAILS
        public String newOwners;       // semicolon-joined
        public String changedAt;       // ISO local time (yyyy-MM-dd HH:mm:ss)
        public String changedAtUtc;    // ISO UTC for sortable reads
        public String instigatedBy;    // toolkit user OR "Direct in Agile"
        public String source;          // "Toolkit" | "Direct in Agile"
        public String agileUser;       // always Administrator for SDK; surfaced for completeness
        public String revNumber;
        public String detailsRaw;      // first 500 chars of DETAILS for UI tooltip
    }

    /** Pull owner-change rows for the last {@code days} days.
     *
     *  <p>Service-layer cache: the SQL takes 40-90s on prod because
     *  {@code DBMS_LOB.SUBSTR(h.DETAILS,...) LIKE} can't use an index. We
     *  keep a 365-day snapshot in memory + on disk, rebuilt hourly. Any
     *  query for {@code days <= CACHE_DAYS} is served by filtering the
     *  snapshot in Java (sub-millisecond). Queries larger than the cache
     *  window fall through to a live SQL pull. */
    public List<Row> readAll(int days) {
        if (days <= 0) days = defaultDays;
        // Snapshot path — sub-ms when warm.
        if (days <= CACHE_DAYS) {
            List<Row> snap;
            long age;
            synchronized (this) {
                snap = cachedRows;
                age = (cachedAt == 0L) ? Long.MAX_VALUE
                                       : System.currentTimeMillis() - cachedAt;
            }
            if (snap != null) {
                // Trim to the requested window by changedAtUtc, descending.
                long cutoffMs;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_MONTH, -days);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                cutoffMs = cal.getTimeInMillis();
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                List<Row> out = new ArrayList<>(snap.size());
                for (Row r : snap) {
                    if (r.changedAtUtc == null) { out.add(r); continue; }
                    try {
                        if (iso.parse(r.changedAtUtc).getTime() >= cutoffMs) out.add(r);
                    } catch (Exception parseErr) {
                        out.add(r);  // keep on parse trouble — rare
                    }
                }
                // Stale-warm: if older than TTL, kick off a background rebuild
                // but still serve immediately.
                if (age > CACHE_TTL_MS) triggerAsyncRebuild();
                return out;
            }
            // Cold cache — try sync warm. If it fails, we'll throw to caller.
            try {
                List<Row> fresh = pullFromSql(CACHE_DAYS);
                long now = System.currentTimeMillis();
                synchronized (this) { cachedRows = fresh; cachedAt = now; }
                persistToDisk();
                return readAll(days);  // re-enter snapshot path
            } catch (RuntimeException e) {
                LOG.warning("[OWNER-AUDIT] cold warm failed: " + e.getMessage());
                throw e;
            }
        }
        // Live pull — caller asked for a window beyond our cache horizon.
        return pullFromSql(days);
    }

    /** One-shot background rebuild guard. Prevents multiple concurrent
     *  request threads from each kicking off a 40-90s SQL when the cache
     *  is stale — only the first one fires; subsequent ones short-circuit. */
    private volatile boolean asyncRebuildInFlight = false;
    private void triggerAsyncRebuild() {
        synchronized (this) {
            if (asyncRebuildInFlight) return;
            asyncRebuildInFlight = true;
        }
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                List<Row> fresh = pullFromSql(CACHE_DAYS);
                long now = System.currentTimeMillis();
                synchronized (this) { cachedRows = fresh; cachedAt = now; }
                persistToDisk();
                LOG.info("[OWNER-AUDIT] async rebuild done rows=" + fresh.size()
                        + " in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Exception e) {
                LOG.warning("[OWNER-AUDIT] async rebuild failed: " + e.getMessage());
            } finally {
                synchronized (this) { asyncRebuildInFlight = false; }
            }
        }, "owner-audit-rebuild");
        t.setDaemon(true);
        t.start();
    }

    /** Force-refresh entrypoint for an admin "Refresh now" button — bypasses
     *  the snapshot, runs the SQL, replaces the cache. Returns the freshly
     *  trimmed rows for the requested window. */
    public List<Row> refreshNow(int days) {
        long t0 = System.currentTimeMillis();
        List<Row> fresh = pullFromSql(CACHE_DAYS);
        long now = System.currentTimeMillis();
        synchronized (this) { cachedRows = fresh; cachedAt = now; }
        persistToDisk();
        LOG.info("[OWNER-AUDIT] forced refresh rows=" + fresh.size()
                + " in " + (System.currentTimeMillis() - t0) + " ms");
        return readAll(days);
    }

    /** Direct SQL pull — bypasses the cache. Used by the scheduler, the
     *  cold-warm path inside {@link #readAll(int)}, and the live-pull
     *  fallback when the requested window exceeds CACHE_DAYS.
     *
     *  <p>Retuned 2026-06-14 (round 2) after DB-MCP analysis on agprod
     *  proved that the prior CTE+inline-view shape still let Oracle push
     *  the {@code DBMS_LOB.SUBSTR(...) LIKE} predicate down onto the
     *  full 9.09M-row 365-day history slice — the optimizer under-costs
     *  CLOB SUBSTR and ignores {@code NO_MERGE} as a fence. The cursor
     *  plan confirmed the LIKE was applied at the {@code TABLE ACCESS
     *  BY ROWID BATCHED ITEM_HISTORY} step below the hash join. That
     *  is what was driving ORA-01013 every hour from 21:32 Jun 13.
     *
     *  <p>Fix: a {@code ROWNUM} pseudo-column inside the inline view
     *  acts as an optimization barrier and blocks the LIKE pushdown.
     *  The CLOB filter then runs in the outer query on the post-join
     *  result (~97K IMS-only rows over 365d) instead of the pre-join
     *  9.09M-row slice — ~93× fewer CLOB reads.
     *  Hint set {@code LEADING(i h) USE_HASH(h) FULL(i)} keeps the
     *  16K-row IMS subclass set as the hash-join build side. Cursor
     *  plan post-fix shows the LIKE filter sitting on the VIEW node
     *  above the hash join — confirmed on prod.
     *
     *  <p>Timeout kept at 240s as a safety margin; happy-path runtime
     *  should be well under 60s. */
    private List<Row> pullFromSql(int days) {
        if (days <= 0) days = defaultDays;
        long t0 = System.currentTimeMillis();

        // ROWNUM inside the inline view is the optimization barrier — it
        // prevents Oracle from pushing the outer LIKE predicates down onto
        // ITEM_HISTORY. Without it the CLOB SUBSTR runs on 9M rows; with
        // it, it runs on ~97K post-join rows. NO_MERGE alone does not work
        // here because Oracle under-costs DBMS_LOB.SUBSTR.
        String sql =
            "SELECT ITEM_NUMBER, DESCRIPTION, det AS DETAILS, USER_NAME, REVNUMBER, LOCAL_DATE " +
            "FROM ( " +
            "  SELECT /*+ LEADING(i h) USE_HASH(h) FULL(i) */ " +
            "         i.ITEM_NUMBER, " +
            "         i.DESCRIPTION, " +
            "         h.USER_NAME, " +
            "         h.REVNUMBER, " +
            "         h.\"TIMESTAMP\"                       AS LOCAL_DATE, " +
            "         DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1)   AS det, " +
            "         ROWNUM                                 AS rn " +
            "    FROM AGILE.ITEM         i " +
            "    JOIN AGILE.ITEM_HISTORY h ON h.ITEM = i.ID " +
            "   WHERE i.SUBCLASS = ? " +
            "     AND NVL(i.DELETE_FLAG, 0) != 1 " +
            "     AND h.\"TIMESTAMP\" >= TRUNC(SYSDATE) - ? " +
            ") " +
            "WHERE det LIKE ? " +     // toolkit-marker prefix — leading-wildcard-free, more selective
            "   OR det LIKE ? " +     // generic owner-cell match (legacy fallback)
            "ORDER BY ITEM_NUMBER, LOCAL_DATE";

        // Phase 1: read raw rows
        List<RawRow> raw = new ArrayList<>();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(240);
            ps.setInt(1, IMS_SUBCLASS_ID);
            ps.setInt(2, days);
            ps.setString(3, TOOLKIT_MARKER_PREFIX + "%");
            ps.setString(4, OWNER_CELL_DETAILS_LIKE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RawRow rr = new RawRow();
                    rr.itemNumber = rs.getString("ITEM_NUMBER");
                    rr.description = rs.getString("DESCRIPTION");
                    rr.details = rs.getString("DETAILS");
                    rr.userName = rs.getString("USER_NAME");
                    rr.revNumber = rs.getString("REVNUMBER");
                    rr.tsLocal = rs.getTimestamp("LOCAL_DATE");
                    rr.tsUtc = rs.getTimestamp("LOCAL_DATE", utcCal());
                    rr.isMarker = rr.details != null && rr.details.startsWith(TOOLKIT_MARKER_PREFIX);
                    raw.add(rr);
                }
            }
        } catch (SQLException sqle) {
            LOG.warning("[OWNER-AUDIT] SQL failed: " + sqle.getMessage());
            throw new RuntimeException("Owner-change audit read failed: " + sqle.getMessage(), sqle);
        }

        // Phase 2: pair markers to cell-change rows. Same item, within window.
        // Strategy: bucket markers by item; for each cell-change, scan its
        // bucket for the closest marker. Each marker may be consumed by at
        // most one cell-change (avoid double-attribution if two changes
        // happened within the window — unusual but possible).
        Map<String, List<RawRow>> markersByItem = new HashMap<>();
        for (RawRow rr : raw) {
            if (rr.isMarker) {
                markersByItem.computeIfAbsent(rr.itemNumber, k -> new ArrayList<>()).add(rr);
            }
        }

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        SimpleDateFormat isoUtc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        isoUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        List<Row> out = new ArrayList<>();
        for (RawRow rr : raw) {
            if (rr.isMarker) continue;  // markers surface only via pairing
            Row r = new Row();
            r.docNumber = rr.itemNumber;
            r.description = rr.description;
            r.agileUser = rr.userName;
            r.revNumber = rr.revNumber;
            r.changedAt = rr.tsLocal == null ? null : iso.format(rr.tsLocal);
            r.changedAtUtc = rr.tsUtc == null ? null : isoUtc.format(rr.tsUtc);
            r.detailsRaw = rr.details == null ? null
                    : rr.details.substring(0, Math.min(500, rr.details.length()));
            // Parse old/new owners from DETAILS
            if (rr.details != null) {
                Matcher m = OWNERS_WAS_IS_PATTERN.matcher(rr.details);
                if (m.find()) {
                    r.oldOwners = cleanOwnerList(m.group(1));
                    r.newOwners = cleanOwnerList(m.group(2));
                }
            }
            // Find paired marker within window
            RawRow paired = popClosestMarker(markersByItem.get(rr.itemNumber), rr.tsLocal);
            // Classification — 2026-06-05 refinement after Vikas pointed out
            // that only the toolkit's service-account SDK session uses the
            // "administrator" login. So if the Agile actor is administrator,
            // we KNOW the change was toolkit-driven (even without a marker
            // row), but for historical rows from before the marker shipped
            // we can't recover WHICH AD user clicked Save. Three buckets:
            //   1. actor=administrator + marker present → Toolkit, instigator
            //      from marker (post-2026-06-05 deploy)
            //   2. actor=administrator + no marker → Toolkit (legacy),
            //      instigator = "(pre-marker; user unknown)"
            //   3. actor != administrator → Direct in Agile, instigator =
            //      the real user name from USER_NAME (they signed into the
            //      Agile UI to make the change)
            boolean actorIsAdmin = isAdministrator(rr.userName);
            if (paired != null) {
                r.source = "Toolkit";
                r.instigatedBy = extractMarkerUser(paired.details);
            } else if (actorIsAdmin) {
                r.source = "Toolkit (legacy)";
                r.instigatedBy = "(pre-marker; user unknown)";
            } else {
                r.source = "Direct in Agile";
                r.instigatedBy = cleanOwnerList(rr.userName);
            }
            out.add(r);
        }
        // Sort newest first (cell-change rows came back item-sorted from SQL)
        out.sort((a, b) -> {
            String ax = a.changedAtUtc == null ? "" : a.changedAtUtc;
            String bx = b.changedAtUtc == null ? "" : b.changedAtUtc;
            return bx.compareTo(ax);
        });
        LOG.info("[OWNER-AUDIT] read " + out.size() + " rows over last " + days
                + " days in " + (System.currentTimeMillis() - t0) + " ms");
        return out;
    }

    /** Picks the marker closest in time to {@code refTs} from the per-item
     *  bucket, IF within the matching window. Removes it from the bucket so
     *  it can't be paired with a second cell-change. */
    private RawRow popClosestMarker(List<RawRow> bucket, Timestamp refTs) {
        if (bucket == null || bucket.isEmpty() || refTs == null) return null;
        long refMs = refTs.getTime();
        long windowMs = MARKER_PAIR_WINDOW_SEC * 1000L;
        RawRow best = null;
        long bestDiff = Long.MAX_VALUE;
        for (RawRow rr : bucket) {
            if (rr.tsLocal == null) continue;
            long diff = Math.abs(rr.tsLocal.getTime() - refMs);
            if (diff > windowMs) continue;
            if (diff < bestDiff) { best = rr; bestDiff = diff; }
        }
        if (best != null) bucket.remove(best);
        return best;
    }

    /** Drop the trailing employee-id parens from each owner so the audit
     *  table renders just "Kolam, Bibi Anita" not "Kolam, Bibi Anita (22695)".
     *  Empty / blank lists become "(none)" so the UI doesn't show an empty
     *  cell when an owner was removed without a replacement. */
    private static String cleanOwnerList(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return "(none)";
        // Strip the trailing "(NNNNN)" employee-id suffix from each owner entry.
        // Don't try to split on commas because the "Last, First" pairs contain
        // their own commas — leave the raw string structure intact and just
        // remove the parenthesized IDs.
        return t.replaceAll("\\s*\\(\\d+\\)", "");
    }

    /** True when the {@code USER_NAME} column from ITEM_HISTORY is the
     *  Agile service account the toolkit uses. Format is typically
     *  {@code "Administrator, Administrator (administrator)"} — match by
     *  the bracketed loginid substring (case-insensitive) so wording
     *  variants don't slip through. */
    private static boolean isAdministrator(String userName) {
        if (userName == null) return false;
        String u = userName.toLowerCase();
        return u.contains("(administrator)") || u.equals("administrator");
    }

    private static String extractMarkerUser(String details) {
        if (details == null) return "Toolkit (no user)";
        Matcher m = MARKER_USER_PATTERN.matcher(details);
        if (m.find()) return m.group(1).trim();
        return "Toolkit (no user)";
    }

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static final class RawRow {
        String itemNumber;
        String description;
        String details;
        String userName;
        String revNumber;
        Timestamp tsLocal;
        Timestamp tsUtc;
        boolean isMarker;
    }

    /** Diagnostic summary for the controller's /summary endpoint. */
    public Map<String, Object> summary(int days) {
        List<Row> rows = readAll(days);
        int toolkitWithUser = 0, toolkitLegacy = 0, direct = 0;
        Map<String, Integer> byUser = new LinkedHashMap<>();
        for (Row r : rows) {
            if ("Toolkit".equals(r.source)) toolkitWithUser++;
            else if ("Toolkit (legacy)".equals(r.source)) toolkitLegacy++;
            else direct++;
            String u = r.instigatedBy == null ? "(unknown)" : r.instigatedBy;
            byUser.merge(u, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCount", rows.size());
        out.put("days", days);
        out.put("toolkitWithUser", toolkitWithUser);
        out.put("toolkitLegacy", toolkitLegacy);
        // Back-compat alias for the legacy frontend tile — sum of both
        // toolkit-driven buckets (with-user + legacy) so the "Toolkit-driven"
        // headline number reflects every toolkit-attributable change.
        out.put("toolkitDriven", toolkitWithUser + toolkitLegacy);
        out.put("directInAgile", direct);
        out.put("byInstigator", byUser);
        out.put("cachedAt", getCachedAt());
        out.put("rows", rows);
        return out;
    }
}
