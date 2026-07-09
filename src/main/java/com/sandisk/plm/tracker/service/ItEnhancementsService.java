package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Read service for the IT Enhancements tab.
 *
 * <p>Read source: {@code agprod} SQL (~1s, single bucket — all IT-Enhancement
 * universe rows), replacing the Excel-generating Agile-SDK job in
 * {@code ~/git/ccbdetailsreport/CreateReportFromTemplate.java}.
 *
 * <p>Field mappings validated 2026-06-04 by the MCP-connected schema probe
 * (see {@code ~/Downloads/IT_Enhancements_Field_Mapping.md}). Key gotchas:
 *
 * <ul>
 *   <li>{@code IT Owner} = {@code page_three.LIST53} (user-ref slot, NOT {@code change.OWNER})</li>
 *   <li>{@code IT Status} = {@code page_three.TEXT36} (free-text string, NOT a {@code listNN} slot)</li>
 *   <li>{@code Priority} = {@code page_three.LIST55} (parent 251747752), NOT {@code change.CATEGORY}</li>
 *   <li>{@code Category / Request Classification} = {@code page_three.LIST35} (parent 251739712)</li>
 *   <li>{@code Effort (Hours)} = {@code page_three.TEXT37} (string; parse with regex)</li>
 *   <li>{@code Target UAT Date} = {@code page_three.DATE36} — THE editable cell</li>
 *   <li>{@code Target Go Live Date} = {@code page_three.DATE37}</li>
 *   <li>{@code Project / IT Actions Taken / Rework Reason / Problem Statement / IT Log}
 *       live in {@code agile_flex} (multi: comma-list in TEXT, single: NUMBER1)
 *       and {@code agile_flex_clob} (CLOB join key is OBJID, not ID)</li>
 * </ul>
 *
 * <p>The "IT ECN's" universe filter is a two-branch OR validated against the
 * saved search definition:
 * <pre>
 *   (workflow = 'Review' AND request_classification IN (six IT IDs))
 *   OR (it_status IS NOT NULL AND release_date >= TRUNC(SYSDATE) - 7)
 * </pre>
 * Branch 2 captures recently-released items so the dashboard can verify
 * go-live within the 7-day window.
 *
 * <h2>Cache</h2>
 *
 * <p>Cache mirrors {@link ImsReviewService}'s docs-cache pattern:
 * <ol>
 *   <li>In-memory snapshot ({@code cachedRows}) keyed by load time.</li>
 *   <li>JSON snapshot on disk at {@code ./data/it-enhancements-cache.json}
 *       so the cache survives JVM restarts; loaded in {@link #loadFromDisk()}.</li>
 *   <li>Hourly @Scheduled rebuild keeps the snapshot ahead of the TTL so users
 *       effectively never see a cold call. Gated by {@code app.scheduling.disabled}
 *       via {@code Application.SchedulingConfig}.</li>
 * </ol>
 *
 * <p>The {@code _kind} band classification is recomputed on every read
 * (in {@link #serveRows()}) instead of being cached, so the bands stay
 * correct across midnight boundaries even if the snapshot is hours old.
 */
@Service
public class ItEnhancementsService {

    private static final Logger LOG = Logger.getLogger(ItEnhancementsService.class.getName());

    private static final long CACHE_TTL_MS = 60L * 60 * 1000;        // 60 min
    private static final String CACHE_FILE = "./data/it-enhancements-cache.json";
    private static final int CACHE_SNAPSHOT_VERSION = 1;

    /** Six leaf entry IDs under listentry.parentid=251739712 that define the
     *  IT-Enhancement universe (Agile CCB Application Matrix Update / Agile IT
     *  Enhancement / Agile IT Project / Agile New Part Class Request / Agile
     *  Report Request / Agile System Errors-Bug Issues). */
    private static final String IT_REQUEST_CLASSIFICATION_IDS =
            "3911766, 3277462, 3277463, 3911767, 3271900, 3901351";

    private final DataSource agileDataSource;
    private final ObjectMapper cacheMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // Cache state — guarded by `this`
    private List<Row> cachedRows = null;
    private long cachedAt = 0L;

    // PT-IT-ENH cell-options cache. Agile-side dropdown values (Assigned IT
    // Owner / Project / IT Actions Taken). Refreshed alongside the rows
    // cache. Stays warm across rows-refreshes; only re-fetched when stale.
    //
    // Two TTLs: a 6h cache for successful results (Agile lists rarely change),
    // and a much shorter 60s cache for empty results so a transient failure
    // at boot time doesn't poison the cache and leave the popover with no
    // values to choose from for 6h. Empty here means "we got a usable
    // response back but at least one of the requested cells came back with
    // no values OR the call itself failed".
    private Map<String, List<String>> cachedCellOptions = null;
    private long cellOptionsAt = 0L;
    private boolean cellOptionsLastWasGood = false;
    private static final long CELL_OPTIONS_TTL_MS       = 6 * 60 * 60 * 1000L;  // 6h on good fetch
    private static final long CELL_OPTIONS_EMPTY_TTL_MS = 60 * 1000L;           // 60s on bad
    private static final String CELL_OPTIONS_PIPE =
            "Page Three.Assigned IT Owner|Page Three.Project|Page Three.IT Actions Taken";

    @Autowired(required = false)
    private AgileWriteBackClient agileWriteBackClient;

    public ItEnhancementsService(@Qualifier("dataSource") DataSource agileDataSource) {
        this.agileDataSource = agileDataSource;
    }

    /** Structured IT Log entry — author + timestamp + body text. Parsed from
     *  the raw HTML-wrapped CLOB in {@code agile_flex_clob} (ATTID 2000025513).
     *  The legacy SQL in {@link #pullFromSql} strips all HTML so the row's
     *  {@code itLog} field is plain text only; this is the on-demand structured
     *  variant used by the Meeting Mode "Latest update by …" preview. */
    public static class ItLogEntry {
        public String author;       // "Manna, Kuntal"
        public String loginId;      // "7329134"
        public String timestamp;    // "04/09/2026 04:18:58 AM PDT" (as Agile stored it)
        public String date;         // "2026-04-09" (ISO; parsed from timestamp)
        public String body;         // plain-text body (HTML stripped)
    }

    /** Fetch structured IT Log entries for an ECN — most recent first.
     *
     *  <p>Two-pass strategy:
     *  <ol>
     *    <li><b>Inline header parse</b> of the raw CLOB. Some Agile clients
     *        emit a bold author-band into the CLOB itself
     *        ({@code <b>Last, First (loginid) MM/DD/YYYY HH:MM:SS AM/PM TZ</b>});
     *        when present, {@link #parseItLogHtml} pulls multiple entries
     *        out cleanly.</li>
     *    <li><b>CHANGE_HISTORY fallback</b>. Modern Agile stores attribution
     *        as a separate audit row, not inline. Probed on prod with
     *        ECN-127173-PROJ + ECN-117043-PROJ (the user's screenshot
     *        examples) — the CLOB carries only the body, no author band.
     *        We query {@code agile.CHANGE_HISTORY} for the latest row whose
     *        {@code DETAILS} mentions "IT Log", combine it with the CLOB
     *        body (HTML stripped), and surface a single attributed entry.</li>
     *  </ol>
     *
     *  Returns an empty list when neither approach yields anything (no
     *  CLOB row, no CHANGE_HISTORY match, or both queries failed). */
    public List<ItLogEntry> fetchItLogHistory(String ecnNumber) {
        if (ecnNumber == null || ecnNumber.trim().isEmpty()) return java.util.Collections.emptyList();
        List<ItLogEntry> fromClob = fetchItLogFromClob(ecnNumber);
        if (!fromClob.isEmpty()) return fromClob;
        return fetchItLogFromChangeHistory(ecnNumber);
    }

    /** Pass 1: read the raw IT Log CLOB and parse Agile's HTML entry format
     *  (inline {@code <b>…</b>} author-band header). Returns empty if the
     *  CLOB is missing or the regex finds no header bands. */
    private List<ItLogEntry> fetchItLogFromClob(String ecnNumber) {
        String sql =
            "SELECT fc.text " +
            "  FROM agile.change c " +
            "  JOIN agile.agile_flex_clob fc ON fc.objid = c.id AND fc.attid = 2000025513 " +
            " WHERE c.change_number = ?";
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(20);
            ps.setString(1, ecnNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return parseItLogHtml(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[IT-ENH] fetchItLogFromClob failed for " + ecnNumber + ": " + e.getMessage(), e);
        }
        return java.util.Collections.emptyList();
    }

    /** Pass 2: query CHANGE_HISTORY for the most recent IT Log edit and
     *  synthesize one entry combining that audit row's author + timestamp
     *  with the current CLOB body (HTML stripped). The {@code DETAILS} LIKE
     *  filter is broad — Agile's wording varies ("IT Log", "Page Three.IT
     *  Log", etc.) — so we cast a wide net and let the most-recent ordering
     *  pick the right row. Skipped silently on any SQL error. */
    private List<ItLogEntry> fetchItLogFromChangeHistory(String ecnNumber) {
        String sql =
            "SELECT h.USER_NAME, " +
            "       h.LOCAL_DATE, " +
            "       (SELECT REGEXP_REPLACE(fc.text, '<[^>]+>', ' ') " +
            "          FROM agile.agile_flex_clob fc " +
            "         WHERE fc.objid = c.id AND fc.attid = 2000025513) AS log_body " +
            "  FROM agile.change c " +
            "  JOIN agile.CHANGE_HISTORY h ON h.CHANGE_ID = c.id " +
            " WHERE c.change_number = ? " +
            "   AND DBMS_LOB.SUBSTR(h.DETAILS, 2000, 1) LIKE '%IT Log%' " +
            " ORDER BY h.LOCAL_DATE DESC " +
            " FETCH FIRST 1 ROWS ONLY";
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, ecnNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String userRaw = rs.getString("USER_NAME");
                    java.sql.Timestamp ts = rs.getTimestamp("LOCAL_DATE");
                    String body = rs.getString("LOG_BODY");
                    ItLogEntry e = new ItLogEntry();
                    // CHANGE_HISTORY.USER_NAME is "Last, First (loginid)";
                    // split it for the structured response so the client
                    // can render the loginid in the muted style.
                    if (userRaw != null) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("^(.*?)\\s*\\((\\d+)\\)\\s*$").matcher(userRaw.trim());
                        if (m.matches()) {
                            e.author = m.group(1).trim();
                            e.loginId = m.group(2);
                        } else {
                            e.author = userRaw.trim();
                        }
                    }
                    if (ts != null) {
                        e.timestamp = new java.text.SimpleDateFormat("MM/dd/yyyy hh:mm:ss a z").format(ts);
                        e.date = new java.text.SimpleDateFormat("yyyy-MM-dd").format(ts);
                    }
                    e.body = body == null ? "" : body.replaceAll("\\s+", " ").trim();
                    java.util.ArrayList<ItLogEntry> out = new java.util.ArrayList<>(1);
                    out.add(e);
                    return out;
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[IT-ENH] fetchItLogFromChangeHistory failed for " + ecnNumber + ": " + e.getMessage(), e);
        }
        return java.util.Collections.emptyList();
    }

    /** Parser for Agile's IT Log HTML format. Each entry has a bold header
     *  band like {@code <b>Last, First (loginid) MM/DD/YYYY HH:MM:SS AM PDT</b>}
     *  followed by the body. Splits on these headers; everything between two
     *  consecutive header positions belongs to the first one's entry. The
     *  resulting list is in document order — Agile prepends new entries so
     *  position 0 is the most recent. */
    static List<ItLogEntry> parseItLogHtml(String html) {
        List<ItLogEntry> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        // Match <b ...>Last, First (12345) MM/DD/YYYY HH:MM:SS AM/PM TZ</b>.
        // Author = anything up to the open-paren; loginid = the digits in (…);
        // timestamp = MM/DD/YYYY HH:MM:SS AM/PM TZ. <b[^>]*> so Agile-emitted
        // attribute variants (e.g. <b style="…">) still match. (?s) makes . match
        // newlines for HTML wrapping. Timezone is anything non-whitespace, so
        // "PDT" / "PST" / "GMT+0530" / etc. all work.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?s)<b[^>]*>\\s*([^<()]+?)\\s*\\((\\d+)\\)\\s+" +
            "(\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}:\\d{2}\\s+(?:AM|PM)\\s+\\S+)" +
            "\\s*</b>",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(html);
        List<int[]> bounds = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (m.find()) {
            bounds.add(new int[]{m.start(), m.end()});
            heads.add(new String[]{m.group(1).trim(), m.group(2), m.group(3).trim()});
        }
        if (bounds.isEmpty()) return out;
        for (int i = 0; i < bounds.size(); i++) {
            int bodyStart = bounds.get(i)[1];
            int bodyEnd = (i + 1 < bounds.size()) ? bounds.get(i + 1)[0] : html.length();
            String bodyHtml = html.substring(bodyStart, bodyEnd);
            ItLogEntry e = new ItLogEntry();
            e.author = heads.get(i)[0];
            e.loginId = heads.get(i)[1];
            e.timestamp = heads.get(i)[2];
            e.date = isoDateFromAgileTimestamp(e.timestamp);
            e.body = stripHtml(bodyHtml).trim();
            out.add(e);
        }
        return out;
    }

    /** Convert "MM/DD/YYYY HH:MM:SS AM PDT" → "2026-04-09". Best-effort —
     *  if parsing fails, returns null and the caller falls back to the raw
     *  timestamp string. */
    private static String isoDateFromAgileTimestamp(String ts) {
        if (ts == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(ts);
        if (m.find()) {
            String mm = m.group(1); if (mm.length() == 1) mm = "0" + mm;
            String dd = m.group(2); if (dd.length() == 1) dd = "0" + dd;
            return m.group(3) + "-" + mm + "-" + dd;
        }
        return null;
    }

    /** Strip HTML tags and normalize whitespace. Preserves linebreaks across
     *  &lt;br&gt; and &lt;/p&gt; so multi-paragraph IT Log entries don't
     *  collapse into a single line. */
    private static String stripHtml(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"");
        // Collapse runs of whitespace within lines but keep newlines.
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /** Lazy-cached dropdown values from Agile (via plm-agile-service /cell-options).
     *  Returns {} on any failure — the frontend popover then shows no values
     *  for the affected cell. Logs WHY at every failure point so we can
     *  diagnose without poking at the wire. */
    public synchronized Map<String, List<String>> getCellOptions() {
        long now = System.currentTimeMillis();
        long ttl = cellOptionsLastWasGood ? CELL_OPTIONS_TTL_MS : CELL_OPTIONS_EMPTY_TTL_MS;
        if (cachedCellOptions != null && now - cellOptionsAt < ttl) {
            return cachedCellOptions;
        }
        if (agileWriteBackClient == null) {
            LOG.warning("[IT-ENH] cellOptions skip: AgileWriteBackClient not autowired");
            return cacheEmptyAndReturn(now);
        }
        String sampleEcn = pickSampleEcn();
        if (sampleEcn == null) sampleEcn = "ECN-128313-PROJ";
        long t0 = System.currentTimeMillis();
        try {
            AgileWriteBackClient.Result r = agileWriteBackClient.cellOptions(
                    sampleEcn, CELL_OPTIONS_PIPE,
                    java.util.UUID.randomUUID().toString());
            long elapsed = System.currentTimeMillis() - t0;
            if (r == null) {
                LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn
                        + " null result after " + elapsed + " ms");
                return cacheEmptyAndReturn(now);
            }
            if (!r.ok) {
                LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn
                        + " result.ok=false httpStatus=" + r.httpStatus
                        + " reason=" + r.errorReason + " elapsed=" + elapsed + " ms");
                return cacheEmptyAndReturn(now);
            }
            if (r.body == null) {
                LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn
                        + " result.body null (httpStatus=" + r.httpStatus
                        + " elapsed=" + elapsed + " ms)");
                return cacheEmptyAndReturn(now);
            }
            // The agile-service wrapping envelope is {ok, ecn, cellOptions, …}.
            // ok=false from agile-service itself means the SDK couldn't resolve
            // the ECN (e.g. permission, network), which is the path we're most
            // likely to hit on first call after a partial deploy.
            Object envelopeOk = r.body.get("ok");
            if (envelopeOk instanceof Boolean && !((Boolean) envelopeOk)) {
                LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn
                        + " envelope ok=false error=" + r.body.get("error")
                        + " elapsed=" + elapsed + " ms");
                return cacheEmptyAndReturn(now);
            }
            Object raw = r.body.get("cellOptions");
            if (!(raw instanceof Map)) {
                LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn
                        + " no cellOptions map in body (keys=" + r.body.keySet()
                        + " elapsed=" + elapsed + " ms)");
                return cacheEmptyAndReturn(now);
            }
            Map<String, List<String>> out = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMap = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> e : rawMap.entrySet()) {
                if (e.getValue() instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) e.getValue();
                    List<String> vals = new java.util.ArrayList<>(list.size());
                    for (Object o : list) if (o != null) vals.add(o.toString());
                    out.put(e.getKey(), vals);
                }
            }
            int total = 0;
            for (List<String> v : out.values()) total += v.size();
            // Treat an entirely-empty response (no values across all cells) as
            // a failure for caching purposes — it almost certainly means the
            // ECN we sampled was wrong or the agile-service hit a soft error.
            boolean good = total > 0;
            cachedCellOptions = out;
            cellOptionsAt = now;
            cellOptionsLastWasGood = good;
            LOG.info("[IT-ENH] cellOptions sampleEcn=" + sampleEcn + " "
                    + (good ? "OK" : "EMPTY")
                    + " (" + out.size() + " cells, " + total + " total values, "
                    + elapsed + " ms)" + (good ? "" : " — will retry in " + (CELL_OPTIONS_EMPTY_TTL_MS / 1000) + "s"));
            return cachedCellOptions;
        } catch (Exception e) {
            LOG.warning("[IT-ENH] cellOptions sampleEcn=" + sampleEcn + " threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return cacheEmptyAndReturn(now);
        }
    }

    private Map<String, List<String>> cacheEmptyAndReturn(long now) {
        cachedCellOptions = java.util.Collections.emptyMap();
        cellOptionsAt = now;
        cellOptionsLastWasGood = false;
        return cachedCellOptions;
    }

    /** Pick any ECN from the cached rows whose IT Status looks like an
     *  active workflow row — these tend to have Project / IT Actions filled,
     *  giving the cell-options probe a representative sample. */
    private synchronized String pickSampleEcn() {
        if (cachedRows == null) return null;
        for (Row r : cachedRows) {
            if (r.ecnNumber == null) continue;
            if (r.project != null && !r.project.isEmpty()) return r.ecnNumber;
        }
        for (Row r : cachedRows) if (r.ecnNumber != null) return r.ecnNumber;
        return null;
    }

    /** Single row matching the Excel "All Enhancements" sheet. */
    public static final class Row {
        public String ecnNumber;
        public String priority;
        public String status;              // page_three.TEXT36 (IT Status)
        public String workflowStatus;      // change.STATUS → nodetable (separate from IT Status)
        public String itOwner;
        public String itOwnerLoginId;
        public String requestor;
        public String requestorLoginId;
        public String category;            // Request Classification value
        public String problemStatement;    // agile_flex 251747661
        public String proposal;            // change.description
        public String hours;               // page_three.TEXT37 (raw string)
        public String project;             // agile_flex 251747921 (multi)
        public String itActions;           // agile_flex 251748003 (multi)
        public String itLog;               // agile_flex_clob 2000025513
        public String targetUAT;           // page_three.DATE36 — editable
        public String targetGoLive;        // page_three.DATE37
        public String reworkReason;        // agile_flex 251754725 (single)
        public String submitDate;          // change.SUBMIT_DATE
        public String releaseDate;         // change.RELEASE_DATE
        /** Computed per-read (NOT cached). "overdue_uat" | "overdue_golive" | "approaching_golive" | "ok". */
        public String _kind;
    }

    /** On-disk snapshot envelope. */
    public static final class CacheSnapshot {
        public int version;
        public String savedAt;
        public long cachedAt;
        public List<Row> rows = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // Lifecycle: load on startup, rebuild hourly, serve fresh or cached
    // ------------------------------------------------------------------

    @PostConstruct
    public void loadFromDisk() {
        File f = new File(CACHE_FILE);
        if (!f.exists()) {
            LOG.info("[IT-ENH-CACHE] No on-disk snapshot at " + CACHE_FILE
                    + " — cache will warm on first /data call");
            return;
        }
        try {
            CacheSnapshot snap = cacheMapper.readValue(f, CacheSnapshot.class);
            if (snap == null || snap.rows == null) return;
            synchronized (this) {
                cachedRows = snap.rows;
                cachedAt = snap.cachedAt;
            }
            LOG.info("[IT-ENH-CACHE] Loaded snapshot from " + CACHE_FILE
                    + " — rows=" + snap.rows.size() + " savedAt=" + snap.savedAt);
        } catch (Exception e) {
            LOG.warning("[IT-ENH-CACHE] Failed to load snapshot — will rebuild on first /data call: " + e.getMessage());
        }
    }

    /**
     * Hourly rebuild. Always re-pulls from agprod — there is only one bucket
     * and the SQL is sub-second, so we don't need the "only rebuild buckets
     * that have been queried" gating that {@link ImsReviewService} uses.
     *
     * <p>Gated by {@code app.scheduling.disabled} via {@code SchedulingConfig}
     * — local dev never fires this. On prod it ticks at minute 0 of every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduledRebuild() {
        long t0 = System.currentTimeMillis();
        try {
            pullAndCache();
            LOG.info("[IT-ENH-CACHE] Hourly rebuild done in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            LOG.warning("[IT-ENH-CACHE] Hourly rebuild failed: " + e.getMessage());
        }
    }

    /** Force-refresh entrypoint used by the UI "Refresh now" button.
     *  Returns the freshly-pulled rows (with _kind classified). */
    public List<Row> refreshNow() {
        long t0 = System.currentTimeMillis();
        List<Row> rows = pullAndCache();
        LOG.info("[IT-ENH-CACHE] Forced refresh returned " + rows.size()
                + " rows in " + (System.currentTimeMillis() - t0) + " ms");
        return serveRows();
    }

    /**
     * Primary read entrypoint. Returns cached rows if fresh; otherwise pulls
     * + caches + returns. Either way, {@code _kind} is recomputed at serve
     * time so band classifications stay correct across midnight.
     *
     * <p>Fallback: if the SQL fails (timeout, network, etc.) AND we still
     * have a stale snapshot loaded, serve the stale snapshot rather than
     * 500ing — the user sees old data with a hover-warning instead of an
     * error. The hourly rebuild will keep trying.
     */
    public List<Row> readAll() {
        synchronized (this) {
            if (cachedRows != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
                return serveRows();
            }
        }
        try {
            pullAndCache();
        } catch (RuntimeException pullErr) {
            // If we have a stale snapshot (cache present but TTL-expired), serve it.
            synchronized (this) {
                if (cachedRows != null) {
                    LOG.warning("[IT-ENH-CACHE] SQL pull failed; serving stale snapshot from "
                            + new Date(cachedAt) + " — " + pullErr.getMessage());
                    return serveRows();
                }
            }
            // No cache at all — propagate so the UI can show "agprod unreachable".
            throw pullErr;
        }
        return serveRows();
    }

    /** Timestamp of the cache snapshot (epoch ms), or 0 if cache is cold. */
    public synchronized long getCachedAt() { return cachedAt; }

    /** Pulls from agprod, replaces the cache, persists to disk. Returns the
     *  newly-pulled rows (without _kind set — callers should go through
     *  {@link #serveRows()} for that). */
    private List<Row> pullAndCache() {
        List<Row> fresh = pullFromSql();
        long now = System.currentTimeMillis();
        synchronized (this) {
            cachedRows = fresh;
            cachedAt = now;
        }
        persistToDisk();
        return fresh;
    }

    /** Returns a copy of the cached rows with {@code _kind} recomputed against
     *  today's date. Callers receive a list they may not mutate the
     *  underlying Row objects of (we hand back the same Row instances so
     *  Jackson serialization stays cheap — Row is immutable in practice). */
    private synchronized List<Row> serveRows() {
        if (cachedRows == null) return new ArrayList<>();
        Date today = new Date();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd");
        for (Row r : cachedRows) {
            r._kind = classifyRow(r.status, parseIso(iso, r.targetUAT), parseIso(iso, r.targetGoLive), today);
        }
        return cachedRows;
    }

    private static Date parseIso(SimpleDateFormat iso, String s) {
        if (s == null || s.isEmpty()) return null;
        try { return iso.parse(s); } catch (Exception e) { return null; }
    }

    /**
     * Persist the current in-memory cache to disk. Atomic via temp-file + move
     * so a half-written file can never be loaded on the next startup.
     */
    private synchronized void persistToDisk() {
        try {
            CacheSnapshot snap = new CacheSnapshot();
            snap.version = CACHE_SNAPSHOT_VERSION;
            snap.savedAt = Instant.now().toString();
            snap.cachedAt = cachedAt;
            snap.rows = cachedRows == null ? new ArrayList<>() : cachedRows;
            File out = new File(CACHE_FILE);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(CACHE_FILE + ".tmp");
            cacheMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, snap);
            Files.move(tmp.toPath(), out.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warning("[IT-ENH-CACHE] Failed to persist snapshot to " + CACHE_FILE + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // SQL — the heavy read
    // ------------------------------------------------------------------

    private List<Row> pullFromSql() {
        // PT-IT-ENH (Vikas, 2026-06-12): the previous SQL had two
        // LISTAGG sub-selects that joined agile.listentry on
        //     f.text LIKE '%,' || le.entryid || ',%'
        // for the Project + IT Actions Taken multi-list columns. That LIKE
        // pattern cannot use any index on listentry; Oracle does a full
        // listentry scan PER agile_flex row PER outer change. With ~120
        // ECNs × ~10K agile_flex rows × tens-of-thousands listentry rows,
        // the query was taking minutes.
        //
        // New shape: the main query returns the RAW comma-id-comma text
        // for each multi-list cell (a tiny varchar fetch per row, fast and
        // indexable). After we have all rows in memory, we parse out every
        // unique entryid across all rows and resolve them with ONE batched
        // listentry lookup keyed on indexed entryid. Time complexity drops
        // from O(rows × flex × listentry) to O(rows + listentryHits).
        String sql =
            "SELECT c.change_number, " +
            "       sn.description AS workflow_status, " +
            "       requ.last_name || ', ' || requ.first_name AS requestor, " +
            "       requ.loginid AS requestor_loginid, " +
            "       itown.last_name || ', ' || itown.first_name AS it_owner, " +
            "       itown.loginid AS it_owner_loginid, " +
            "       pri.entryvalue AS priority, " +
            "       cat.entryvalue AS category, " +
            "       c.description AS proposal, " +
            "       p3.text36 AS it_status, " +
            "       p3.text37 AS hours_raw, " +
            "       p3.date36 AS target_uat_date, " +
            "       p3.date37 AS target_go_live, " +
            "       c.submit_date AS submit_date, " +
            "       c.release_date AS release_date, " +
            // Multi-list raw text — comma-id-comma. Resolved in Java.
            "       ( SELECT f.text FROM agile.agile_flex f " +
            "          WHERE f.id = c.id AND f.attid = 251747921 ) AS project_raw, " +
            "       ( SELECT f.text FROM agile.agile_flex f " +
            "          WHERE f.id = c.id AND f.attid = 251748003 ) AS it_actions_raw, " +
            // Single-value list — already an indexed equality join, fast.
            "       ( SELECT le.entryvalue " +
            "           FROM agile.agile_flex f " +
            "           JOIN agile.listentry le ON le.entryid = f.number1 AND le.langid = 0 " +
            "          WHERE f.id = c.id AND f.attid = 251754725 ) AS rework_reason, " +
            "       ( SELECT REGEXP_REPLACE(fc.text, '<[^>]+>', ' ') " +
            "           FROM agile.agile_flex_clob fc " +
            "          WHERE fc.objid = c.id AND fc.attid = 2000025513 ) AS it_log, " +
            "       ( SELECT f.text FROM agile.agile_flex f " +
            "          WHERE f.id = c.id AND f.attid = 251747661 ) AS problem_statement " +
            "FROM agile.change c " +
            "JOIN      agile.nodetable sn   ON sn.id = c.status " +
            "LEFT JOIN agile.page_three p3  ON p3.id = c.id AND p3.class = c.class " +
            "LEFT JOIN agile.agileuser requ  ON requ.id  = c.originator " +
            "LEFT JOIN agile.agileuser itown ON itown.id = p3.list53 " +
            "LEFT JOIN agile.listentry pri   ON pri.entryid = p3.list55 AND pri.langid = 0 " +
            "LEFT JOIN agile.listentry cat   ON cat.entryid = p3.list35 AND cat.langid = 0 " +
            "WHERE NVL(c.delete_flag, 0) != 1 " +
            // Drop non-ECN change numbers (e.g. MCO-…, DCO-…, ACO-…) so the
            // grid only shows IT Enhancement ECNs. Earlier the grid carried
            // a handful of stray numbers whose front-half matched IT
            // classification IDs but weren't ECNs proper. Vikas asked for
            // this hard filter on 2026-06-12.
            "  AND c.change_number LIKE 'ECN-%' " +
            "  AND ( " +
            "        ( sn.description = 'Review' " +
            "          AND p3.list35 IN (" + IT_REQUEST_CLASSIFICATION_IDS + ") ) " +
            "     OR ( p3.text36 IS NOT NULL AND c.release_date >= TRUNC(SYSDATE) - 7 ) " +
            "      ) " +
            "ORDER BY c.change_number";

        List<Row> out = new ArrayList<>();
        // Map each row index → its raw multi-list text so we can resolve
        // after the batched listentry lookup. Keeping these as arrays
        // (parallel to `out`) avoids allocating extra wrapper objects.
        List<String> projectRawByRow = new ArrayList<>();
        List<String> itActionsRawByRow = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try (Connection conn = agileDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(180);
            long querySql = System.currentTimeMillis();
            try (ResultSet rs = ps.executeQuery()) {
                long firstRowReady = System.currentTimeMillis();
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd");
                while (rs.next()) {
                    Row r = new Row();
                    r.ecnNumber = rs.getString("change_number");
                    r.workflowStatus = rs.getString("workflow_status");
                    r.requestor = stripIdSuffix(rs.getString("requestor"));
                    r.requestorLoginId = rs.getString("requestor_loginid");
                    r.itOwner = stripIdSuffix(rs.getString("it_owner"));
                    r.itOwnerLoginId = rs.getString("it_owner_loginid");
                    r.priority = rs.getString("priority");
                    r.category = rs.getString("category");
                    r.proposal = rs.getString("proposal");
                    r.status = rs.getString("it_status");
                    r.hours = rs.getString("hours_raw");
                    r.reworkReason = rs.getString("rework_reason");
                    r.itLog = rs.getString("it_log");
                    r.problemStatement = rs.getString("problem_statement");

                    Date tuat = rs.getDate("target_uat_date");
                    r.targetUAT = tuat == null ? null : iso.format(tuat);
                    Date tgl = rs.getDate("target_go_live");
                    r.targetGoLive = tgl == null ? null : iso.format(tgl);
                    Date sub = rs.getTimestamp("submit_date");
                    r.submitDate = sub == null ? null : iso.format(sub);
                    Date rel = rs.getTimestamp("release_date");
                    r.releaseDate = rel == null ? null : iso.format(rel);
                    // Project / IT Actions Taken — raw comma-id text. Resolution
                    // happens in the second pass.
                    String projectRaw = rs.getString("project_raw");
                    String itActionsRaw = rs.getString("it_actions_raw");
                    projectRawByRow.add(projectRaw);
                    itActionsRawByRow.add(itActionsRaw);
                    out.add(r);
                }
                LOG.info("[IT-ENH] main query: " + out.size() + " rows in "
                        + (System.currentTimeMillis() - querySql) + " ms"
                        + " (first row " + (firstRowReady - querySql) + " ms)");
            }

            // Pass 2 — collect every unique entryid across all rows, then
            // resolve display values with ONE batched listentry lookup.
            java.util.Set<Long> allIds = new java.util.HashSet<>();
            for (String s : projectRawByRow)   for (long id : parseFlexIds(s)) allIds.add(id);
            for (String s : itActionsRawByRow) for (long id : parseFlexIds(s)) allIds.add(id);
            long lookupT0 = System.currentTimeMillis();
            Map<Long, String> entryValues = fetchListEntryValues(conn, allIds);
            LOG.info("[IT-ENH] listentry resolve: " + entryValues.size() + " / " + allIds.size()
                    + " ids in " + (System.currentTimeMillis() - lookupT0) + " ms");

            for (int i = 0; i < out.size(); i++) {
                Row r = out.get(i);
                r.project = resolveMultiListDisplay(projectRawByRow.get(i), entryValues);
                r.itActions = resolveMultiListDisplay(itActionsRawByRow.get(i), entryValues);
            }
        } catch (Exception e) {
            LOG.warning("[IT-ENH] pullFromSql failed: " + e.getMessage());
            throw new RuntimeException("IT Enhancements read failed: " + e.getMessage(), e);
        }
        LOG.info("[IT-ENH] pullFromSql returned " + out.size() + " rows in "
                + (System.currentTimeMillis() - t0) + " ms");
        return out;
    }

    /** Parse Agile's comma-id-comma multi-list encoding. Input shape is
     *  {@code ",251747922,251747923,"}; output is a list of longs. Empty /
     *  null input → empty list. */
    private static List<Long> parseFlexIds(String raw) {
        if (raw == null) return java.util.Collections.emptyList();
        String t = raw.trim();
        if (t.isEmpty()) return java.util.Collections.emptyList();
        if (t.charAt(0) == ',') t = t.substring(1);
        if (!t.isEmpty() && t.charAt(t.length() - 1) == ',') t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return java.util.Collections.emptyList();
        List<Long> ids = new ArrayList<>(4);
        for (String tok : t.split(",")) {
            String s = tok.trim();
            if (s.isEmpty()) continue;
            try { ids.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    /** Resolve a raw multi-list text (",id1,id2,") to a sorted display string
     *  ("Code Change, Java Client Configuration Change") using the prefetched
     *  entryid → entryvalue map. Returns null when nothing resolves. */
    private static String resolveMultiListDisplay(String raw, Map<Long, String> entryValues) {
        List<Long> ids = parseFlexIds(raw);
        if (ids.isEmpty()) return null;
        List<String> vals = new ArrayList<>(ids.size());
        for (Long id : ids) {
            String v = entryValues.get(id);
            if (v != null && !v.isEmpty()) vals.add(v);
        }
        if (vals.isEmpty()) return null;
        java.util.Collections.sort(vals, String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", vals);
    }

    /** One batched lookup against agile.listentry for every entryid we'll
     *  need to render this snapshot. Chunked to stay under Oracle's 1000-
     *  element IN-list cap. */
    private static Map<Long, String> fetchListEntryValues(Connection conn, java.util.Set<Long> idSet) throws java.sql.SQLException {
        if (idSet == null || idSet.isEmpty()) return java.util.Collections.emptyMap();
        Map<Long, String> out = new HashMap<>(idSet.size() * 2);
        List<Long> ids = new ArrayList<>(idSet);
        int CHUNK = 900;
        for (int start = 0; start < ids.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, ids.size());
            List<Long> chunk = ids.subList(start, end);
            StringBuilder sql = new StringBuilder(
                "SELECT entryid, entryvalue FROM agile.listentry WHERE langid = 0 AND entryid IN (");
            for (int i = 0; i < chunk.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setQueryTimeout(60);
                for (int i = 0; i < chunk.size(); i++) ps.setLong(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getLong("entryid"), rs.getString("entryvalue"));
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Mirrors the Excel "Overdue & At-Risk" sheet's classification logic
     *  (and CreateReportFromTemplate.java:294-462). Status comparison is
     *  case-insensitive because the IT Status column has known case variants
     *  in prod ("Completed"/"completed", "Live"/"Live in Production"/"Live in
     *  PROD" per the field-mapping report's data-quality warning). */
    private static String classifyRow(String itStatus, Date targetUat, Date targetGoLive, Date today) {
        String s = itStatus == null ? "" : itStatus.trim().toUpperCase();
        long todayMs = today.getTime();
        long sevenDaysMs = 7L * 24 * 60 * 60 * 1000;
        // Overdue UAT — status is WIP, target UAT in the past
        if ("WIP".equals(s) && targetUat != null && targetUat.getTime() < todayMs) {
            return "overdue_uat";
        }
        // Overdue Go-Live — status is UAT (or "UAT complete, CAB Prep"), go-live in the past
        if (("UAT".equals(s) || s.startsWith("UAT COMPLETE")) && targetGoLive != null
                && targetGoLive.getTime() < todayMs) {
            return "overdue_golive";
        }
        // Approaching Go-Live — status is UAT, go-live within the next 7 days
        if ("UAT".equals(s) && targetGoLive != null
                && targetGoLive.getTime() >= todayMs
                && targetGoLive.getTime() <= todayMs + sevenDaysMs) {
            return "approaching_golive";
        }
        return "ok";
    }

    private static String stripIdSuffix(String s) {
        if (s == null) return null;
        String t = s.trim();
        // Last-name-only edge case where last_name is null produces ", First"
        if (t.startsWith(", ")) t = t.substring(2);
        if (t.endsWith(",")) t = t.substring(0, t.length() - 1);
        return t.trim();
    }

    // ------------------------------------------------------------------
    // Diagnostic — kept for the test endpoint
    // ------------------------------------------------------------------

    public Map<String, Object> readSummary() {
        List<Row> rows = readAll();
        int total = rows.size();
        int withItStatus = 0, withRequestor = 0, withItOwner = 0, withPriority = 0,
            withTargetUat = 0, withTargetGoLive = 0, withProject = 0,
            withItActions = 0, withItLog = 0, withProposal = 0, withHours = 0;
        int overdueUat = 0, overdueGoLive = 0, approachingGoLive = 0;
        Map<String, Integer> statusCounts = new java.util.LinkedHashMap<>();
        for (Row r : rows) {
            if (r.status != null && !r.status.isEmpty()) withItStatus++;
            if (r.requestor != null && !r.requestor.isEmpty()) withRequestor++;
            if (r.itOwner != null && !r.itOwner.isEmpty()) withItOwner++;
            if (r.priority != null && !r.priority.isEmpty()) withPriority++;
            if (r.targetUAT != null) withTargetUat++;
            if (r.targetGoLive != null) withTargetGoLive++;
            if (r.project != null && !r.project.isEmpty()) withProject++;
            if (r.itActions != null && !r.itActions.isEmpty()) withItActions++;
            if (r.itLog != null && !r.itLog.isEmpty()) withItLog++;
            if (r.proposal != null && !r.proposal.isEmpty()) withProposal++;
            if (r.hours != null && !r.hours.isEmpty()) withHours++;
            if ("overdue_uat".equals(r._kind)) overdueUat++;
            if ("overdue_golive".equals(r._kind)) overdueGoLive++;
            if ("approaching_golive".equals(r._kind)) approachingGoLive++;
            String k = (r.status == null || r.status.isEmpty()) ? "(blank)" : r.status;
            statusCounts.merge(k, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRows", total);
        Map<String, Integer> pop = new LinkedHashMap<>();
        pop.put("itStatus", withItStatus); pop.put("requestor", withRequestor);
        pop.put("itOwner", withItOwner); pop.put("priority", withPriority);
        pop.put("targetUat", withTargetUat); pop.put("targetGoLive", withTargetGoLive);
        pop.put("project", withProject); pop.put("itActions", withItActions);
        pop.put("itLog", withItLog); pop.put("proposal", withProposal); pop.put("hours", withHours);
        out.put("populated", pop);
        out.put("overdueUat", overdueUat);
        out.put("overdueGoLive", overdueGoLive);
        out.put("approachingGoLive", approachingGoLive);
        out.put("itStatusDistribution", statusCounts);
        out.put("cachedAt", getCachedAt());
        return out;
    }
}
