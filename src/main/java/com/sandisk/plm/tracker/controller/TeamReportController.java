package com.sandisk.plm.tracker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.MemoryGuard;
import com.sandisk.plm.tracker.service.PortkeyClient;
import com.sandisk.plm.tracker.service.ReportService;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wraps {@code build_team_report.py} so the Volume Reports tab can produce next
 * month's Team Report XLSX without leaving the app or staging files on disk by
 * hand. The script runs synchronously per request — typical wall-time is ~25
 * seconds for a single month of data; we cap at 2 minutes.
 *
 * Inputs come in over multipart: the user uploads the prior month's Team Report
 * XLSX; the volume-report XLSX is generated server-side from the criteria the
 * user last ran (no binary round-trip through the browser).
 *
 * Outputs (the generated Team Report XLSX + a discrepancies DOCX) are stashed
 * on disk and returned as download URLs in a single JSON envelope (base64 only
 * as a fallback when the stash write fails) so the front-end can offer both as
 * downloads from one response without a ~10 MB humongous allocation per run.
 */
@RestController
@RequestMapping("/api/team-report")
public class TeamReportController {

    private static final Logger LOG = Logger.getLogger(TeamReportController.class.getName());
    private static final Pattern MONTH_RE = Pattern.compile("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)_\\d{4}$");
    private static final long SCRIPT_TIMEOUT_SECS = 120;
    private static final int LOG_TAIL_BYTES = 4000;

    @Autowired private ReportService reportService;
    @Autowired private VolumeReportController volumeReportController;
    @Autowired private ActivityLogger activityLogger;
    @Autowired private PortkeyClient portkeyClient;
    @Autowired private com.sandisk.plm.tracker.service.UploadQuarantineService quarantineService;
    @Autowired private com.sandisk.plm.tracker.service.LdapAuthService ldapAuthService;
    @Autowired private MemoryGuard memoryGuard;

    /** Usernames with a generation currently running — one run at a time per user. */
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ============================================================
    // Workbook-scan guardrails (2026-07-08 OOM fix). A DOM open
    // (XSSFWorkbook) of a stashed Team Report cost ~1 GB of heap because
    // XSSF parses every sheet eagerly — the 7.7 MB file holds 131 MB of
    // XML, and /data ran one per tab open. Reads are now SAX streams
    // (scanWorkbookData / scanGroupedForMonth), the derived /data payload
    // is cached per stashed file, and scans are single-flight behind a
    // MemoryGuard gate. Analysis:
    // docs/superpowers/plans/2026-07-08-prod-oom-team-report-analysis.md
    // ============================================================

    /** Heap too full (or scanner busy) to start another workbook scan —
     *  endpoints map this to HTTP 503 instead of risking an OOM. */
    static class HeapBusyException extends RuntimeException {
        HeapBusyException(String msg) { super(msg); }
    }

    /** One workbook scan at a time JVM-wide: concurrent tabs/users queue
     *  here instead of stacking transient allocations on a hot heap. */
    private static final java.util.concurrent.Semaphore SCAN_PERMIT =
            new java.util.concurrent.Semaphore(1);

    /** LRU cache of the workbook-derived /data payload keyed by
     *  path|mtime|size. Values are treated as immutable once inserted —
     *  callers copy the top level before adding per-request keys. */
    private static final int WB_CACHE_MAX = 6;
    private static final Map<String, Map<String, Object>> WB_CACHE =
        java.util.Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return size() > WB_CACHE_MAX;
            }
        });

    private static String cacheKey(Path p) {
        try {
            return p.toAbsolutePath() + "|" + Files.getLastModifiedTime(p).toMillis() + "|" + Files.size(p);
        } catch (IOException e) {
            return p.toAbsolutePath() + "|unknown";
        }
    }

    /** Refuses new scans when the heap is already under pressure, then takes
     *  the single-flight permit (callers MUST release SCAN_PERMIT in finally). */
    private void acquireScanPermit() {
        if (memoryGuard != null && memoryGuard.isUnderPressure()) {
            throw new HeapBusyException("Server memory is under pressure ("
                + memoryGuard.usedPercent() + "% heap used) — try again in a minute.");
        }
        try {
            if (!SCAN_PERMIT.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new HeapBusyException("Another report scan is in progress — try again in a moment.");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new HeapBusyException("Interrupted while waiting for the report scanner.");
        }
    }

    @Value("${portkey.provider:@anthropic-eastus2}")
    private String portkeyProvider;

    @Value("${portkey.model:claude-sonnet-4-6}")
    private String portkeyModel;

    private static final ObjectMapper JSON = new ObjectMapper();

    @PostMapping(value = "/generate", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam("month") String month,
            @RequestParam("previousMonth") MultipartFile previousMonth,
            @RequestParam("managerUsername") String managerUsername,
            @RequestParam("fromDate") String fromDate,
            @RequestParam("toDate") String toDate,
            @RequestParam(value = "transitive", defaultValue = "false") boolean transitive,
            @RequestParam(value = "includeManager", defaultValue = "false") boolean includeManager,
            HttpSession session) {

        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        Map<String, String> qParams = new java.util.LinkedHashMap<>();
        qParams.put("month", month);
        qParams.put("managerUsername", managerUsername);
        qParams.put("fromDate", fromDate);
        qParams.put("toDate", toDate);
        qParams.put("transitive", String.valueOf(transitive));
        qParams.put("includeManager", String.valueOf(includeManager));
        String qTicket = quarantineService.quarantine(previousMonth,
                (String) session.getAttribute("username"),
                "/api/team-report/generate", qParams);
        if (month == null || !MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest().body(err("Invalid month format (expected MMM_YYYY, e.g. Mar_2026)"));
        }
        if (previousMonth == null || previousMonth.isEmpty()) {
            return ResponseEntity.badRequest().body(err("Previous month XLSX file is required"));
        }
        String prevName = previousMonth.getOriginalFilename();
        if (prevName == null || !prevName.toLowerCase().endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(err("Previous month file must be .xlsx"));
        }
        if (managerUsername == null || managerUsername.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(err("managerUsername is required (run the volume report first)"));
        }
        if (fromDate == null || toDate == null) {
            return ResponseEntity.badRequest().body(err("fromDate and toDate are required"));
        }

        // Build the volume-report XLSX server-side from the criteria the user last
        // ran. Hits the session cache when criteria match; falls through to a
        // fresh query otherwise. No binary uploaded from the browser.
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("managerUsername", managerUsername);
        criteria.put("fromDate", fromDate);
        criteria.put("toDate", toDate);
        criteria.put("transitive", transitive);
        criteria.put("includeManager", includeManager);

        byte[] prevBytes;
        try {
            prevBytes = previousMonth.getBytes();
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to read uploaded file: " + e.getMessage()));
        }

        ResponseEntity<Map<String, Object>> resp =
                generateCore(month, prevBytes, criteria, session, "TEAM_REPORT_GENERATE", null);
        if (resp.getStatusCode().is2xxSuccessful()) quarantineService.release(qTicket);
        return resp;
    }

    /**
     * Shared core of team-report generation: builds the volume XLSX from the
     * criteria, runs build_team_report.py, AI post-process, stashes the result
     * and returns the download envelope. A per-user in-flight set turns
     * concurrent runs (double-click, second tab) into a 429 — each run costs
     * 1-4 minutes of CPU plus Portkey tokens.
     */
    private ResponseEntity<Map<String, Object>> generateCore(
            String month, byte[] prevBytes, Map<String, Object> criteria,
            HttpSession session, String activityAction, Map<String, Object> defaults) {
        if (memoryGuard != null && memoryGuard.isUnderPressure()) {
            return ResponseEntity.status(503).body(errCode("HEAP_BUSY",
                    "Server memory is under pressure (" + memoryGuard.usedPercent()
                    + "% heap used) — try again in a minute."));
        }
        String username = (String) session.getAttribute("username");
        if (!inFlight.add(username)) {
            return ResponseEntity.status(429).body(errCode("ALREADY_RUNNING",
                    "A Team Report generation is already running for your account — wait for it to finish."));
        }
        try {
            return generateCoreInner(month, prevBytes, criteria, session, activityAction, defaults);
        } finally {
            inFlight.remove(username);
        }
    }

    private ResponseEntity<Map<String, Object>> generateCoreInner(
            String month, byte[] prevBytes, Map<String, Object> criteria,
            HttpSession session, String activityAction, Map<String, Object> defaults) {
        String managerUsername = String.valueOf(criteria.get("managerUsername"));

        // Whose team the report covers — recorded in the stash meta sidecar,
        // shown in the tab header, and appended to download filenames.
        String managerDisplay = null;
        try { managerDisplay = ldapAuthService.findDisplayName(managerUsername); } catch (Exception ignored) {}
        if (managerDisplay == null || managerDisplay.trim().isEmpty()) managerDisplay = managerUsername;
        Map<String, String> managerMeta = new LinkedHashMap<>();
        managerMeta.put("managerUsername", managerUsername);
        managerMeta.put("managerDisplayName", managerDisplay);

        byte[] volumeXlsx;
        try {
            volumeXlsx = volumeReportController.generateVolumeXlsxBytes(criteria, session);
        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT] Volume xlsx build failed: " + e.getMessage());
            return ResponseEntity.status(500).body(err("Volume report build failed: " + e.getMessage()));
        }

        Path tmp;
        try {
            tmp = Files.createTempDirectory("team-report-");
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to create temp dir: " + e.getMessage()));
        }

        // Concurrency guard: only one heavy Python report may run at a time
        // server-wide (shared with the ECN report generator). Wait briefly for a
        // running one to finish, then reject rather than stacking a second
        // python.exe and pegging the box CPU (prod incident).
        final String pySlotLabel = "team-report:" + session.getAttribute("username");
        boolean pySlotHeld = reportService.tryAcquirePythonSlot(pySlotLabel, 60);
        if (!pySlotHeld) {
            try {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
            String holder = reportService.getPythonSlotHolder();
            return ResponseEntity.status(429).body(err("A report is already generating"
                    + (holder != null ? " (" + holder + ")" : "")
                    + ". Please try again in a moment."));
        }

        try {
            // Filename matches the sample shipped with the handoff: Team_Report_<YEAR>_<MMM>.xlsx
            String[] mp = month.split("_");
            String outName = "Team_Report_" + mp[1] + "_" + mp[0] + ".xlsx";

            Path prevPath = tmp.resolve("prev.xlsx");
            Path volPath  = tmp.resolve("volume.xlsx");
            Path outPath  = tmp.resolve(outName);

            Files.write(prevPath, prevBytes);
            Files.write(volPath, volumeXlsx);

            String python = reportService.getPythonExe();
            Path scriptPath = Paths.get("./data/team-report/build_team_report.py").toAbsolutePath().normalize();
            if (!Files.exists(scriptPath)) {
                return ResponseEntity.status(500).body(err("Script missing at " + scriptPath
                        + " — install it on the server before using this feature."));
            }

            ProcessBuilder pb = new ProcessBuilder(
                    python, scriptPath.toString(),
                    "--previous-month", prevPath.toString(),
                    "--volume-report",  volPath.toString(),
                    "--month",          month,
                    "--output",         outPath.toString());
            pb.directory(tmp.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            long t0 = System.currentTimeMillis();
            Process process = pb.start();
            StringBuilder stdoutBuf = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdoutBuf.append(line).append('\n');
                }
            }
            boolean done = process.waitFor(SCRIPT_TIMEOUT_SECS, TimeUnit.SECONDS);
            long durMs = System.currentTimeMillis() - t0;
            if (!done) {
                process.destroyForcibly();
                LOG.warning("[TEAM-REPORT] Script timeout after " + SCRIPT_TIMEOUT_SECS + "s");
                return ResponseEntity.status(500).body(err(
                        "Script timed out after " + SCRIPT_TIMEOUT_SECS + " seconds"));
            }
            int exit = process.exitValue();
            String log = stdoutBuf.toString();
            String tail = log.length() > LOG_TAIL_BYTES ? log.substring(log.length() - LOG_TAIL_BYTES) : log;

            if (exit != 0) {
                LOG.warning("[TEAM-REPORT] Script failed (exit " + exit + "): " + tail);
                Map<String, Object> r = err("build_team_report failed (exit " + exit + ")");
                r.put("stderr", tail);
                return ResponseEntity.status(500).body(r);
            }

            if (!Files.exists(outPath)) {
                return ResponseEntity.status(500).body(err("Script reported success but output not found at "
                        + outPath.getFileName()));
            }

            String stem = outName.substring(0, outName.length() - 5); // drop .xlsx
            Path discPath = tmp.resolve(stem + "_discrepancies.docx");

            // ---- AI post-processing ----
            // Best-effort: if anything fails (Portkey down, Python missing, AI parse error)
            // we log + ship the un-postprocessed XLSX so the user still gets the core report.
            long aiMs = 0;
            String aiNote = null;
            Path analysisJsonPath = null;
            try {
                long aiT0 = System.currentTimeMillis();
                analysisJsonPath = runAiPostProcess(outPath, tmp, python, month);
                aiMs = System.currentTimeMillis() - aiT0;
                LOG.info("[TEAM-REPORT] AI post-process OK in " + aiMs + "ms");
            } catch (Exception aiEx) {
                aiNote = "AI post-process skipped: " + aiEx.getMessage();
                LOG.warning("[TEAM-REPORT] " + aiNote);
            }

            byte[] outBytes = Files.readAllBytes(outPath);
            byte[] discBytes = Files.exists(discPath) ? Files.readAllBytes(discPath) : null;

            // HANDOFF #4: keep the last few generated reports per user on disk so
            // the drawer's "Recent reports" list can offer re-downloads. Best-effort.
            // Also stash the analysis.json sidecar (Team Report in-app reads it
            // directly — never re-parses the xlsx prose) and the discrepancies
            // docx, so the envelope can carry download URLs instead of base64.
            String storedAs = null, discStoredAs = null;
            try {
                byte[] analysisBytes = analysisJsonPath != null && Files.exists(analysisJsonPath)
                        ? Files.readAllBytes(analysisJsonPath) : null;
                String[] stashedNames = stashRecent((String) session.getAttribute("username"),
                        outName, outBytes, analysisBytes, managerMeta, discBytes);
                storedAs = stashedNames[0];
                discStoredAs = stashedNames[1];
            } catch (Exception stashEx) {
                LOG.warning("[TEAM-REPORT] stashRecent failed: " + stashEx.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            // Download filename carries the manager so it's obvious whose
            // team the file covers. The STASHED name stays canonical
            // (Team_Report_YYYY_Mmm.xlsx) — monthFromStoredName depends on it.
            String stemOnly = outName.substring(0, outName.length() - 5);
            String outputFilename = stemOnly + "__" + fileSlug(managerDisplay) + ".xlsx";
            response.put("outputFilename", outputFilename);
            response.put("manager", managerMeta);
            // Download URL against the stash instead of a ~10 MB base64 blob;
            // base64 only when the stash write failed (legacy fallback path).
            if (storedAs != null) {
                response.put("outputUrl", recentDownloadUrl(storedAs, outputFilename));
                response.put("outputBase64", null);
            } else {
                response.put("outputBase64", Base64.getEncoder().encodeToString(outBytes));
            }
            if (discBytes != null) {
                response.put("discrepancyFilename", discPath.getFileName().toString());
                if (discStoredAs != null) {
                    response.put("discrepancyUrl", recentDownloadUrl(discStoredAs, null));
                    response.put("discrepancyBase64", null);
                } else {
                    response.put("discrepancyBase64", Base64.getEncoder().encodeToString(discBytes));
                }
            } else {
                response.put("discrepancyFilename", null);
                response.put("discrepancyBase64", null);
            }
            response.put("discrepancyCount", countDiscrepancies(log));
            response.put("durationMs", durMs);
            response.put("aiDurationMs", aiMs);
            if (aiNote != null) response.put("aiNote", aiNote);
            response.put("log", tail);
            if (defaults != null) response.put("defaults", defaults);

            activityLogger.log(
                    (String) session.getAttribute("username"),
                    (String) session.getAttribute("displayName"),
                    activityAction,
                    "month=" + month + " manager=" + managerUsername
                            + " volBytes=" + volumeXlsx.length
                            + " outBytes=" + outBytes.length
                            + " durMs=" + durMs);
            LOG.info("[TEAM-REPORT] OK month=" + month + " out=" + outBytes.length + "B in " + durMs + "ms");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT] Unexpected error: " + e.getMessage());
            return ResponseEntity.status(500).body(err("Unexpected error: " + e.getMessage()));
        } finally {
            reportService.releasePythonSlot(pySlotLabel);
            // Best-effort temp cleanup. Doesn't matter if a stray file lingers.
            try {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    /** Best-effort discrepancy count from the script's stdout — used for the toast text. */
    private static int countDiscrepancies(String log) {
        Matcher m = Pattern.compile("(\\d+)\\s+discrepanc", Pattern.CASE_INSENSITIVE).matcher(log);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("error", msg);
        return r;
    }

    private static Map<String, Object> errCode(String code, String msg) {
        Map<String, Object> r = err(msg);
        r.put("code", code);
        return r;
    }

    /**
     * One-click variant of {@link #generate}: zero required inputs. The report
     * month defaults to the last complete calendar month; the previous-month
     * workbook is sourced from the newest stashed report (the stash IS the
     * AI-postprocessed file the drawer flow asks users to re-upload — the
     * postprocess saves in place before the stash happens); volume-report
     * criteria are derived from the target month. Optional overrides:
     * month (MMM_YYYY), managerUsername (generate for another manager's team),
     * force=true to proceed when the newest stash leaves gap months.
     */
    @PostMapping("/generate-last-month")
    public ResponseEntity<Map<String, Object>> generateLastMonth(
            @RequestParam(value = "month", required = false) String monthParam,
            @RequestParam(value = "managerUsername", required = false) String managerParam,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return ResponseEntity.status(401).body(err("Not authenticated"));

        String month = (monthParam == null || monthParam.trim().isEmpty())
                ? lastCompleteMonth(java.time.LocalDate.now()) : monthParam.trim();
        if (!MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest().body(err("Invalid month format (expected MMM_YYYY, e.g. Jun_2026)"));
        }
        String manager = (managerParam == null || managerParam.trim().isEmpty())
                ? username : managerParam.trim();

        // A manager with zero direct reports would yield a structurally valid
        // but empty report (the volume query "succeeds" with 0 rows) — block it
        // before any heavy work.
        try {
            if (ldapAuthService.findDirectReports(manager, false).isEmpty()) {
                return ResponseEntity.status(409).body(errCode("NOT_A_MANAGER",
                        "No direct reports found in AD for '" + manager
                        + "'. Pick a manager in the Volume Reports dropdown and use the drawer shortcut instead."));
            }
        } catch (Exception e) {
            return ResponseEntity.status(502).body(err("AD lookup failed: " + e.getMessage()));
        }

        // Previous-month source: newest stashed report strictly older than the
        // target month, from the target manager's stash or the caller's own.
        Path prior = findNewestStashOlderThan(new String[]{ manager, username }, month);
        if (prior == null) {
            return ResponseEntity.status(409).body(errCode("NO_PRIOR_REPORT",
                    "No saved Team Report found to roll forward. Generate once via the Generate Team Report drawer "
                    + "(uploading last month's file) — after that, one-click works every month."));
        }
        String priorMonth = monthFromStoredName(prior.getFileName().toString());
        if (!force && monthOrdinal(priorMonth) < monthOrdinal(month) - 1) {
            Map<String, Object> r = errCode("STALE_PRIOR_REPORT",
                    "Newest saved report is " + priorMonth.replace('_', ' ') + " — months between it and "
                    + month.replace('_', ' ') + " would be missing from the roll-forward. "
                    + "Generate the gap month(s) first, or continue anyway.");
            r.put("priorMonth", priorMonth);
            r.put("targetMonth", month);
            return ResponseEntity.status(409).body(r);
        }

        byte[] prevBytes;
        try {
            prevBytes = Files.readAllBytes(prior);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to read saved report "
                    + prior.getFileName() + ": " + e.getMessage()));
        }

        String[] mp = month.split("_");
        java.time.LocalDate first = java.time.LocalDate.of(
                Integer.parseInt(mp[1]), MONTH_INDEX.get(mp[0]) + 1, 1);
        String fromDate = first.toString();
        // VolumeReportController.run() treats toDate as inclusive (bumps +1 day
        // internally), so the last day of the month reproduces the manual flow.
        String toDate = first.withDayOfMonth(first.lengthOfMonth()).toString();

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("managerUsername", manager);
        criteria.put("fromDate", fromDate);
        criteria.put("toDate", toDate);
        criteria.put("transitive", false);
        criteria.put("includeManager", false);

        // Echo the server-chosen inputs so the UI can show what a zero-input
        // run actually did.
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("month", month);
        defaults.put("previousSource", prior.getFileName().toString());
        defaults.put("previousMonth", priorMonth);
        defaults.put("managerUsername", manager);
        defaults.put("fromDate", fromDate);
        defaults.put("toDate", toDate);

        LOG.info("[TEAM-REPORT] One-click: month=" + month + " manager=" + manager
                + " prior=" + prior.getFileName());
        return generateCore(month, prevBytes, criteria, session, "TEAM_REPORT_GENERATE_ONECLICK", defaults);
    }

    /**
     * Preflight for the one-click flow: who a report for this manager would
     * cover and which saved workbook rolls forward — so the UI can show a
     * confirm dialog (with a manager picker) BEFORE burning a 1-4 minute
     * generation. Cheap: one AD lookup + a directory listing, no report work.
     * Motivated by 2026-07-07: an admin one-clicked as himself and produced an
     * all-zeros June report — his AD reports aren't the PCM team.
     */
    @GetMapping("/generate-last-month/preflight")
    public ResponseEntity<Map<String, Object>> oneClickPreflight(
            @RequestParam(value = "month", required = false) String monthParam,
            @RequestParam(value = "managerUsername", required = false) String managerParam,
            HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return ResponseEntity.status(401).body(err("Not authenticated"));

        String month = (monthParam == null || monthParam.trim().isEmpty())
                ? lastCompleteMonth(java.time.LocalDate.now()) : monthParam.trim();
        if (!MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest().body(err("Invalid month format (expected MMM_YYYY, e.g. Jun_2026)"));
        }
        String manager = (managerParam == null || managerParam.trim().isEmpty())
                ? username : managerParam.trim();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("month", month);
        out.put("managerUsername", manager);
        out.put("managerIsSelf", manager.equals(username));

        List<Map<String, String>> reports = new ArrayList<>();
        try {
            for (com.sandisk.plm.tracker.service.LdapAuthService.ReportingUser u
                    : ldapAuthService.findDirectReports(manager, false)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("displayName", u.displayName);
                m.put("username", u.samAccountName);
                reports.add(m);
            }
        } catch (Exception e) {
            out.put("ldapError", e.getMessage());
        }
        out.put("directReports", reports);
        out.put("isManager", !reports.isEmpty());

        Path prior = findNewestStashOlderThan(new String[]{ manager, username }, month);
        String priorMonth = prior == null ? null : monthFromStoredName(prior.getFileName().toString());
        out.put("priorSource", prior == null ? null : prior.getFileName().toString());
        out.put("priorMonth", priorMonth);
        out.put("staleWarning", priorMonth != null && monthOrdinal(priorMonth) < monthOrdinal(month) - 1);
        return ResponseEntity.ok(out);
    }

    /** "Jun_2026" for any date in July 2026 — the last complete calendar month. */
    static String lastCompleteMonth(java.time.LocalDate today) {
        java.time.LocalDate prev = today.minusMonths(1);
        return PCM_MONTHS[prev.getMonthValue() - 1] + "_" + prev.getYear();
    }

    /** MMM_YYYY → comparable ordinal (year*12 + monthIdx); -1 when unparseable. */
    static int monthOrdinal(String month) {
        if (month == null) return -1;
        String[] p = month.split("_");
        if (p.length != 2 || !MONTH_INDEX.containsKey(p[0])) return -1;
        try { return Integer.parseInt(p[1]) * 12 + MONTH_INDEX.get(p[0]); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Newest stashed xlsx strictly older than targetMonth across the given
     *  users' stash dirs (newest month wins; ties broken by newest stamp). */
    private static Path findNewestStashOlderThan(String[] usernames, String targetMonth) {
        int target = monthOrdinal(targetMonth);
        Path best = null;
        int bestOrd = -1;
        String bestName = "";
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String u : usernames) {
            if (u == null || !seen.add(sanitizeUser(u))) continue;
            Path dir = recentDirFor(u);
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String fn = p.getFileName().toString();
                    int ord = monthOrdinal(monthFromStoredName(fn));
                    if (ord < 0 || ord >= target) continue;
                    if (ord > bestOrd || (ord == bestOrd && fn.compareTo(bestName) > 0)) {
                        best = p;
                        bestOrd = ord;
                        bestName = fn;
                    }
                }
            } catch (IOException ignored) {}
        }
        return best;
    }

    // ============================================================
    // HANDOFF #4: per-user "Recent reports" cache for the drawer.
    // Stash the last 3 generated XLSX files under
    //   ./data/team-report/recent/<username>/<timestamp>__<filename>.xlsx
    // Older files for the same user are pruned. /recent lists the
    // remaining files; /recent/{filename}/download streams one back.
    // ============================================================
    // 5 (was 3): the one-click flow rolls forward from the newest stashed
    // prior-month report, so repeated same-month regenerations must not be
    // able to evict the roll-forward base.
    private static final int RECENT_KEEP = 5;
    private static final java.time.format.DateTimeFormatter STAMP_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Path recentDirFor(String username) {
        String safe = sanitizeUser(username);
        return Paths.get("./data/team-report/recent/" + safe).toAbsolutePath().normalize();
    }
    private static String sanitizeUser(String u) {
        if (u == null || u.isEmpty()) return "_anon";
        return u.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    private static String sanitizeFile(String f) {
        if (f == null) return "report.xlsx";
        return f.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Builds the caller-scoped stash download URL, optionally overriding the
     *  saved-as filename via ?name= (the stash name is canonical, the download
     *  name carries the manager slug). */
    private static String recentDownloadUrl(String storedAs, String downloadName) {
        String url = "/api/team-report/recent/"
                + java.net.URLEncoder.encode(storedAs, java.nio.charset.StandardCharsets.UTF_8)
                + "/download";
        if (downloadName != null && !downloadName.isEmpty()) {
            url += "?name=" + java.net.URLEncoder.encode(downloadName, java.nio.charset.StandardCharsets.UTF_8);
        }
        return url;
    }

    /** Returns {xlsxStoredAs, docxStoredAs-or-null} — the on-disk stash names. */
    private String[] stashRecent(String username, String outName, byte[] bytes, byte[] analysisJsonBytes,
                             Map<String, String> managerMeta, byte[] discrepancyDocxBytes) throws IOException {
        Path dir = recentDirFor(username);
        Files.createDirectories(dir);
        String stamp = java.time.LocalDateTime.now().format(STAMP_FMT);
        String safeName = sanitizeFile(outName);
        Path target = dir.resolve(stamp + "__" + safeName);
        Files.write(target, bytes);
        // Discrepancies docx sidecar: shares the "<stamp>__" prefix so pruning
        // removes it together with its xlsx. Best-effort like the other sidecars.
        String docxStored = null;
        if (discrepancyDocxBytes != null && discrepancyDocxBytes.length > 0) {
            String stemD = safeName.toLowerCase().endsWith(".xlsx")
                    ? safeName.substring(0, safeName.length() - 5) : safeName;
            docxStored = stamp + "__" + stemD + "_discrepancies.docx";
            try {
                Files.write(dir.resolve(docxStored), discrepancyDocxBytes);
            } catch (Exception docxEx) {
                LOG.warning("[TEAM-REPORT] discrepancy docx stash failed: " + docxEx.getMessage());
                docxStored = null;
            }
        }
        // Manager metadata sidecar: <stamp>__<stem>.meta.json — records whose
        // team the report covers, for the UI header and download filenames.
        // Shares the "<stamp>__" prefix so pruning removes it with the xlsx.
        if (managerMeta != null && !managerMeta.isEmpty()) {
            String stemName = safeName.toLowerCase().endsWith(".xlsx")
                    ? safeName.substring(0, safeName.length() - 5) : safeName;
            try {
                Files.write(dir.resolve(stamp + "__" + stemName + ".meta.json"),
                        JSON.writeValueAsBytes(managerMeta));
            } catch (Exception metaEx) {
                LOG.warning("[TEAM-REPORT] meta sidecar write failed: " + metaEx.getMessage());
            }
        }
        // Sidecar: <stamp>__<stem>.analysis.json — read by the in-app Team
        // Report tab via /api/team-report/{month}. Best-effort; nothing
        // hard-fails if AI was skipped or the postprocess never wrote it.
        if (analysisJsonBytes != null && analysisJsonBytes.length > 0) {
            String stem = safeName.toLowerCase().endsWith(".xlsx")
                    ? safeName.substring(0, safeName.length() - 5) : safeName;
            Path sidecar = dir.resolve(stamp + "__" + stem + ".analysis.json");
            Files.write(sidecar, analysisJsonBytes);
        }
        // Prune to RECENT_KEEP newest XLSX files (each xlsx + its sidecar share
        // the same <stamp>__ prefix, so the sidecar follows the xlsx into the
        // retention window automatically). Sidecar count never exceeds xlsx
        // count, so pruning by xlsx age is sufficient.
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            java.util.List<Path> xlsxes = s
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .collect(java.util.stream.Collectors.toList());
            for (int i = RECENT_KEEP; i < xlsxes.size(); i++) {
                Path oldXlsx = xlsxes.get(i);
                String fn = oldXlsx.getFileName().toString();
                String prefix = fn.length() >= 17 ? fn.substring(0, 17) : fn; // "<stamp>__"
                try { Files.deleteIfExists(oldXlsx); } catch (IOException ignored) {}
                // Drop any sidecar that shares the prefix.
                try (java.util.stream.Stream<Path> s2 = Files.list(dir)) {
                    s2.filter(p -> p.getFileName().toString().startsWith(prefix)
                                && !p.equals(oldXlsx))
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
        return new String[]{ target.getFileName().toString(), docxStored };
    }

    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> listRecent(
            @RequestParam(value = "limit", defaultValue = "3") int limit,
            HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        String username = (String) session.getAttribute("username");
        Path dir = recentDirFor(username);
        Map<String, Object> body = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> items = new ArrayList<>();
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                items = s
                    .filter(Files::isRegularFile)
                    // .xlsx only — the stash dir can also hold .analysis.json
                    // sidecars, .meta.json and quarantined *.bak files, none
                    // of which should be listed or downloadable.
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .limit(Math.max(1, Math.min(limit, RECENT_KEEP)))
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        String n = p.getFileName().toString();
                        // Strip the "<stamp>__" prefix for display.
                        String displayName = n;
                        int sep = n.indexOf("__");
                        if (sep > 0) displayName = n.substring(sep + 2);
                        // Parse the stamp into an ISO timestamp.
                        String iso = "";
                        try {
                            String stamp = sep > 0 ? n.substring(0, sep) : "";
                            if (!stamp.isEmpty()) {
                                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(stamp, STAMP_FMT);
                                iso = ldt.toString();
                            }
                        } catch (Exception ignored) {}
                        long size = 0L;
                        try { size = Files.size(p); } catch (IOException ignored) {}
                        m.put("filename", displayName);
                        m.put("storedAs", n);
                        m.put("sizeBytes", size);
                        m.put("generatedAt", iso);
                        Map<String, String> mgr = readStashMeta(p);
                        if (mgr != null) m.put("manager", mgr);
                        m.put("downloadUrl", "/api/team-report/recent/" + java.net.URLEncoder.encode(n, java.nio.charset.StandardCharsets.UTF_8) + "/download");
                        return m;
                    })
                    .collect(java.util.stream.Collectors.toList());
            } catch (IOException e) {
                LOG.warning("[TEAM-REPORT] listRecent error: " + e.getMessage());
            }
        }
        body.put("success", true);
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/recent/{storedAs}/download")
    public ResponseEntity<byte[]> downloadRecent(@org.springframework.web.bind.annotation.PathVariable String storedAs,
                                                  @RequestParam(value = "name", required = false) String name,
                                                  HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).build();
        }
        String username = (String) session.getAttribute("username");
        // Only allow filenames that stay inside the per-user dir; refuse anything
        // else. .docx covers the stashed discrepancies sidecar the generate
        // envelope now links to instead of embedding as base64.
        boolean isDocx = storedAs != null && storedAs.toLowerCase().endsWith(".docx");
        if (storedAs == null || storedAs.contains("/") || storedAs.contains("\\") || storedAs.contains("..")
                || (!storedAs.toLowerCase().endsWith(".xlsx") && !isDocx)) {
            return ResponseEntity.badRequest().build();
        }
        Path dir = recentDirFor(username);
        Path target = dir.resolve(storedAs).normalize();
        if (!target.startsWith(dir) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String displayName = storedAs;
            int sep = storedAs.indexOf("__");
            if (sep > 0) displayName = storedAs.substring(sep + 2);
            // Optional caller-supplied download name (e.g. with the manager
            // slug) — same charset rules as the stash names, same extension.
            if (name != null && !name.contains("/") && !name.contains("\\") && !name.contains("..")
                    && !name.contains("\"")
                    && name.toLowerCase().endsWith(storedAs.substring(storedAs.lastIndexOf('.')).toLowerCase())) {
                displayName = name;
            }
            String contentType = isDocx
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Content-Disposition", "attachment; filename=\"" + displayName + "\"")
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    // ============================================================
    // In-app Team Report endpoints (Noraida 2026-06-11 handoff).
    // Backed by the same stash that drives the "Recent reports" list.
    // GET /months              → which months have a generated report on disk
    // GET /data?month=May_2026 → the full data shape the React mock + UI use
    // ============================================================

    @GetMapping("/months")
    public ResponseEntity<Map<String, Object>> listMonths(HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        java.util.Set<String> months = new java.util.TreeSet<>();
        Path root = Paths.get("./data/team-report/recent").toAbsolutePath().normalize();
        if (Files.exists(root)) {
            try (java.util.stream.Stream<Path> subdirs = Files.list(root)) {
                subdirs.filter(Files::isDirectory).forEach(sub -> {
                    try (java.util.stream.Stream<Path> files = Files.list(sub)) {
                        files.filter(Files::isRegularFile)
                             .map(p -> p.getFileName().toString())
                             .map(TeamReportController::monthFromStoredName)
                             .filter(java.util.Objects::nonNull)
                             .forEach(months::add);
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
        resp.put("success", true);
        resp.put("months", new ArrayList<>(months));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{month}/regenerate-ai")
    public ResponseEntity<Map<String, Object>> regenerateAi(
            @org.springframework.web.bind.annotation.PathVariable("month") String month,
            HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        if (month == null || !MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest().body(err("Invalid month — expected MMM_YYYY"));
        }
        StoredReport sr = findLatestForMonth(month);
        if (sr == null) {
            return ResponseEntity.status(404).body(err(
                    "No Team Report on disk for " + month
                    + ". Generate the workbook from Volume Reports first."));
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, List<RowData>> grouped = extractGroupedForMonth(sr.xlsxPath, month);
            LOG.info("[TEAM-REPORT-REGEN-AI] " + month + " teams=" + grouped.size()
                    + " rows=" + grouped.values().stream().mapToInt(List::size).sum());

            List<Map<String, Object>> analysisGroups = grouped.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> CompletableFuture.supplyAsync(
                            () -> analyzeTeam(e.getKey(), e.getValue(), month)))
                    .collect(Collectors.toList())
                    .stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            Map<String, Object> pcmData = extractTeamReportData(sr, month);
            Map<String, Object> pcmWorkload = analyzePcmWorkload(pcmData, month);
            Map<String, Object> reportSuggestions = analyzeReportSuggestions(pcmData, analysisGroups, month);

            // Persist sidecar next to the existing xlsx so subsequent /data
            // calls (and future tab opens) read the cached result instead of
            // burning Portkey tokens again. Same <stamp>__<stem>.analysis.json
            // naming the stash flow uses.
            String storedName = sr.xlsxPath.getFileName().toString();
            int sep = storedName.indexOf("__");
            String stem = storedName.toLowerCase().endsWith(".xlsx")
                    ? storedName.substring(0, storedName.length() - 5) : storedName;
            String sidecarName = (sep > 0 ? storedName.substring(0, sep + 2) : "")
                    + (sep > 0 ? stem.substring(sep + 2) : stem) + ".analysis.json";
            Path sidecar = sr.xlsxPath.resolveSibling(sidecarName);

            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("month", month);
            wrap.put("groups", analysisGroups);
            wrap.put("pcmWorkload", pcmWorkload);
            wrap.put("reportSuggestions", reportSuggestions);
            JSON.writerWithDefaultPrettyPrinter().writeValue(sidecar.toFile(), wrap);
            LOG.info("[TEAM-REPORT-REGEN-AI] " + month + " sidecar=" + sidecar.getFileName()
                    + " elapsedMs=" + (System.currentTimeMillis() - t0));

            activityLogger.log(
                    (String) session.getAttribute("username"),
                    (String) session.getAttribute("displayName"),
                    "TEAM_REPORT_REGEN_AI",
                    "month=" + month + " teams=" + analysisGroups.size()
                            + " durMs=" + (System.currentTimeMillis() - t0));

            // Hand back the analysis array in the same shape the /data
            // endpoint returns it, so the frontend can splice it in
            // without re-fetching.
            List<Map<String, Object>> analysisForUi = new ArrayList<>();
            for (Map<String, Object> g : analysisGroups) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("team", g.get("team"));
                entry.put("count", g.getOrDefault("count", 0));
                entry.put("urgentPct", g.getOrDefault("urgentPct", 0));
                entry.put("urgent", g.getOrDefault("urgent", "0% urgent"));
                entry.put("risk", g.getOrDefault("risk", "low"));
                entry.put("summary", g.getOrDefault("summary", ""));
                entry.put("callout", g.getOrDefault("callout", ""));
                entry.put("themes", g.getOrDefault("themes", ""));
                entry.put("callouts", g.getOrDefault("callouts", ""));
                analysisForUi.add(entry);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("analysis", analysisForUi);
            resp.put("pcmWorkload", pcmWorkload);
            resp.put("reportSuggestions", reportSuggestions);
            resp.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(resp);
        } catch (HeapBusyException hb) {
            return ResponseEntity.status(503).body(errCode("HEAP_BUSY", hb.getMessage()));
        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT-REGEN-AI] " + month + " failed: " + e.getMessage());
            return ResponseEntity.status(500).body(err(
                    "AI regen failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @PostMapping("/{month}/pptx")
    public ResponseEntity<byte[]> exportPptx(
            @org.springframework.web.bind.annotation.PathVariable("month") String month,
            HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).build();
        }
        if (month == null || !MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest()
                    .body(("{\"error\":\"Invalid month — expected MMM_YYYY\"}").getBytes());
        }
        StoredReport sr = findLatestForMonth(month);
        if (sr == null) {
            return ResponseEntity.status(404).body(
                    ("{\"error\":\"No Team Report on disk for " + month + "\"}").getBytes());
        }
        Path tmp = null;
        try {
            Map<String, Object> data = extractTeamReportData(sr, month);
            tmp = Files.createTempDirectory("tr-pptx-");
            Path dataJson = tmp.resolve("data.json");
            JSON.writeValue(dataJson.toFile(), data);
            Path outPath = tmp.resolve("Team_Report_" + month + ".pptx");
            // python-pptx can't open the raw .potx (different OOXML content type);
            // we keep a content-type-swapped .pptx copy alongside it. Load order:
            // (1) the swapped .pptx if present (preferred), (2) fall back to .potx
            // for environments where someone dropped a real template by hand.
            Path template = Paths.get("./data/team-report/Sandisk_PPT_Template_v3_1.pptx")
                    .toAbsolutePath().normalize();
            if (!Files.exists(template)) {
                template = Paths.get("./data/team-report/Sandisk_PPT_Template_v3_1.potx")
                        .toAbsolutePath().normalize();
            }
            if (!Files.exists(template)) {
                return ResponseEntity.status(500).body(
                        ("{\"error\":\"SanDisk template missing at " + template
                                + " — install the .potx alongside the python script.\"}").getBytes());
            }
            Path script = Paths.get("./data/team-report/team_report_pptx_generator.py")
                    .toAbsolutePath().normalize();
            if (!Files.exists(script)) {
                return ResponseEntity.status(500).body(
                        ("{\"error\":\"PPTX generator script missing at " + script + "\"}").getBytes());
            }
            String python = reportService.getPythonExe();
            ProcessBuilder pb = new ProcessBuilder(
                    python, script.toString(),
                    "--data", dataJson.toString(),
                    "--template", template.toString(),
                    "--out", outPath.toString());
            pb.directory(tmp.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            StringBuilder buf = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) buf.append(line).append('\n');
            }
            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                LOG.warning("[TEAM-REPORT-PPTX] generator timeout for " + month);
                return ResponseEntity.status(500).body(
                        ("{\"error\":\"PPTX generator timeout\"}").getBytes());
            }
            if (p.exitValue() != 0 || !Files.exists(outPath)) {
                String tail = buf.toString();
                LOG.warning("[TEAM-REPORT-PPTX] failed exit=" + p.exitValue() + " out="
                        + (tail.length() > 600 ? tail.substring(tail.length() - 600) : tail));
                // Pattern-match the script's explicit one-line marker for the
                // "python-pptx not installed" case (exit code 3). Operator gets
                // a clean install hint in the toolkit modal instead of the raw
                // Python traceback from the error path.
                String body;
                if (p.exitValue() == 3 && tail.contains("PPTX_GENERATOR_MISSING_DEPENDENCY")) {
                    body = "{\"error\":\"PowerPoint export needs the python-pptx package, "
                            + "which isn't installed on the toolkit server yet. Open a "
                            + "command prompt on the server and run:  pip install -r "
                            + "data/team-report/requirements.txt  — then retry the export. "
                            + "(One-time setup; the existing Excel pipeline already uses "
                            + "openpyxl from the same Python install.)\"}";
                } else {
                    String snippet = tail.length() > 600 ? tail.substring(tail.length() - 600) : tail;
                    body = "{\"error\":\"PPTX generator failed (exit " + p.exitValue() + "): "
                            + snippet.replace("\"", "'") + "\"}";
                }
                return ResponseEntity.status(500).body(body.getBytes());
            }
            byte[] pptxBytes = Files.readAllBytes(outPath);
            String filename = "Team_Report_" + month + ".pptx";
            activityLogger.log(
                    (String) session.getAttribute("username"),
                    (String) session.getAttribute("displayName"),
                    "TEAM_REPORT_PPTX",
                    "month=" + month + " bytes=" + pptxBytes.length);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .body(pptxBytes);
        } catch (HeapBusyException hb) {
            return ResponseEntity.status(503).body(
                    ("{\"error\":\"" + hb.getMessage().replace("\"", "'") + "\"}").getBytes());
        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT-PPTX] " + month + " unexpected: " + e.getMessage());
            return ResponseEntity.status(500).body(
                    ("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}").getBytes());
        } finally {
            if (tmp != null) {
                try {
                    Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> teamReportData(
            @RequestParam("month") String month, HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        if (month == null || !MONTH_RE.matcher(month).matches()) {
            return ResponseEntity.badRequest().body(err("Invalid month — expected MMM_YYYY (e.g. May_2026)"));
        }
        StoredReport sr = findLatestForMonth(month);
        if (sr == null) {
            Map<String, Object> body = err("No Team Report on disk for " + month
                    + ". Use Generate Team Report → to create one.");
            return ResponseEntity.status(404).body(body);
        }
        try {
            Map<String, Object> data = extractTeamReportData(sr, month);
            Map<String, String> mgr = readStashMeta(sr.xlsxPath);
            if (mgr != null) data.put("manager", mgr);
            return ResponseEntity.ok(data);
        } catch (HeapBusyException hb) {
            return ResponseEntity.status(503).body(errCode("HEAP_BUSY", hb.getMessage()));
        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT-DATA] " + month + " failed: " + e.getMessage());
            return ResponseEntity.status(500).body(err("Failed to read report: " + e.getMessage()));
        }
    }

    /** Reads the manager meta sidecar next to a stashed xlsx
     *  ("<stamp>__<stem>.meta.json") → {managerUsername, managerDisplayName},
     *  or null when absent/unreadable (pre-meta reports). */
    private static Map<String, String> readStashMeta(Path xlsxPath) {
        try {
            String fn = xlsxPath.getFileName().toString();
            if (!fn.toLowerCase().endsWith(".xlsx")) return null;
            Path meta = xlsxPath.resolveSibling(fn.substring(0, fn.length() - 5) + ".meta.json");
            if (!Files.exists(meta)) return null;
            Map<String, String> m = JSON.readValue(Files.readAllBytes(meta),
                    new TypeReference<Map<String, String>>() {});
            return (m == null || m.isEmpty()) ? null : m;
        } catch (Exception e) {
            return null;
        }
    }

    /** "Nazri, Noraida (22828)" → "Nazri_Noraida_22828" — filename-safe slug. */
    static String fileSlug(String s) {
        if (s == null) return "";
        String slug = s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "team" : slug;
    }

    /**
     * Cross-user Excel download for a month — serves the SAME newest-on-disk
     * report the in-app tab renders (findLatestForMonth), unlike
     * /recent/{storedAs}/download which is scoped to the caller's own stash.
     * Filename carries the manager from the meta sidecar when known.
     */
    @GetMapping("/{month}/xlsx")
    public ResponseEntity<byte[]> downloadMonthXlsx(
            @org.springframework.web.bind.annotation.PathVariable String month,
            HttpSession session) {
        if (session.getAttribute("username") == null) return ResponseEntity.status(401).build();
        if (month == null || !MONTH_RE.matcher(month).matches()) return ResponseEntity.badRequest().build();
        StoredReport sr = findLatestForMonth(month);
        if (sr == null) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = Files.readAllBytes(sr.xlsxPath);
            String name = sr.displayName;
            Map<String, String> meta = readStashMeta(sr.xlsxPath);
            if (meta != null && meta.get("managerDisplayName") != null
                    && name.toLowerCase().endsWith(".xlsx")) {
                name = name.substring(0, name.length() - 5)
                        + "__" + fileSlug(meta.get("managerDisplayName")) + ".xlsx";
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /** Parses "20260611-150832__Team_Report_2026_May.xlsx" → "May_2026"
     *  (or null when the filename doesn't match the expected stamp/stem). */
    static String monthFromStoredName(String storedName) {
        if (storedName == null) return null;
        String fn = storedName.toLowerCase();
        if (!fn.endsWith(".xlsx")) return null;
        // Strip the "<stamp>__" prefix.
        int sep = storedName.indexOf("__");
        String stem = sep > 0 ? storedName.substring(sep + 2) : storedName;
        // Stem looks like "Team_Report_2026_May.xlsx".
        Matcher m = Pattern.compile("Team_Report_(\\d{4})_([A-Za-z]{3,4})\\.xlsx$",
                Pattern.CASE_INSENSITIVE).matcher(stem);
        if (!m.find()) return null;
        String mon = m.group(2);
        // Normalise to Mmm (first letter upper, rest lower).
        mon = mon.substring(0, 1).toUpperCase() + mon.substring(1).toLowerCase();
        return mon + "_" + m.group(1);
    }

    private static class StoredReport {
        Path xlsxPath;
        Path analysisJsonPath; // may be null if AI was skipped
        String storedName;     // "<stamp>__Team_Report_2026_May.xlsx"
        String displayName;    // "Team_Report_2026_May.xlsx"
        java.time.LocalDateTime generatedAt;
        String generatedBy;    // username inferred from parent dir name
    }

    /** Walks every user's recent/ subdir, picks the newest xlsx for {month}. */
    private StoredReport findLatestForMonth(String month) {
        Path root = Paths.get("./data/team-report/recent").toAbsolutePath().normalize();
        if (!Files.exists(root)) return null;
        StoredReport best = null;
        try (java.util.stream.Stream<Path> subdirs = Files.list(root)) {
            for (Path sub : subdirs.filter(Files::isDirectory)
                                   .collect(java.util.stream.Collectors.toList())) {
                try (java.util.stream.Stream<Path> files = Files.list(sub)) {
                    for (Path p : files.filter(Files::isRegularFile)
                                       .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                                       .collect(java.util.stream.Collectors.toList())) {
                        String fn = p.getFileName().toString();
                        if (!month.equalsIgnoreCase(monthFromStoredName(fn))) continue;
                        if (best == null || fn.compareTo(best.storedName) > 0) {
                            StoredReport sr = new StoredReport();
                            sr.xlsxPath = p;
                            sr.storedName = fn;
                            int sep = fn.indexOf("__");
                            sr.displayName = sep > 0 ? fn.substring(sep + 2) : fn;
                            sr.generatedBy = sub.getFileName().toString();
                            try {
                                String stamp = sep > 0 ? fn.substring(0, sep) : "";
                                if (!stamp.isEmpty()) sr.generatedAt = java.time.LocalDateTime.parse(stamp, STAMP_FMT);
                            } catch (Exception ignored) {}
                            // Sidecar: <stamp>__<stem>.analysis.json
                            String stem = sr.displayName.toLowerCase().endsWith(".xlsx")
                                    ? sr.displayName.substring(0, sr.displayName.length() - 5) : sr.displayName;
                            String sidecarName = fn.substring(0, fn.length() - 5) + ".analysis.json";
                            // Actually the sidecar prefix is <stamp>__<stem>.analysis.json (stem without .xlsx)
                            sidecarName = (sep > 0 ? fn.substring(0, sep + 2) : "")
                                    + stem + ".analysis.json";
                            Path sidecar = sub.resolve(sidecarName);
                            if (Files.exists(sidecar)) sr.analysisJsonPath = sidecar;
                            best = sr;
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return best;
    }

    private static final String[] PCM_MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    private static final java.util.Map<String, Integer> MONTH_INDEX;
    static {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < PCM_MONTHS.length; i++) m.put(PCM_MONTHS[i], i);
        MONTH_INDEX = java.util.Collections.unmodifiableMap(m);
    }

    /** Reads the Team Report xlsx + analysis.json sidecar and assembles the
     *  shape Noraida's report-data.js mock specifies. The workbook-derived
     *  portion comes from {@link #scanWorkbookData} via {@link #WB_CACHE}
     *  (keyed by path+mtime, so a regenerated file re-scans and a re-opened
     *  tab doesn't); the sidecars are read fresh on every call because
     *  regenerate-AI rewrites analysis.json without touching the xlsx. */
    private Map<String, Object> extractTeamReportData(StoredReport sr, String month) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("monthLabel", monthLabel(month));
        String stampedAt = sr.generatedAt == null ? "" :
                sr.generatedAt.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"));
        out.put("generated", "Generated " + (stampedAt.isEmpty() ? "?" : stampedAt)
                + " · " + sr.generatedBy + " · " + sr.displayName);

        String key = cacheKey(sr.xlsxPath);
        Map<String, Object> core = WB_CACHE.get(key);
        if (core == null) {
            acquireScanPermit();
            try {
                core = WB_CACHE.get(key); // double-check: another request may have scanned while we waited
                if (core == null) {
                    long t0 = System.currentTimeMillis();
                    core = java.util.Collections.unmodifiableMap(
                            scanWorkbookData(sr.xlsxPath.toFile(), month));
                    WB_CACHE.put(key, core);
                    LOG.info("[TEAM-REPORT-DATA] scanned " + sr.xlsxPath.getFileName()
                            + " in " + (System.currentTimeMillis() - t0) + "ms (cached)");
                }
            } finally {
                SCAN_PERMIT.release();
            }
        }
        out.putAll(core);

        // analysis from sidecar JSON (if present)
        java.util.List<Map<String, Object>> analysis = new ArrayList<>();
        if (sr.analysisJsonPath != null && Files.exists(sr.analysisJsonPath)) {
            try {
                Map<String, Object> aj = JSON.readValue(sr.analysisJsonPath.toFile(),
                        new TypeReference<Map<String, Object>>(){});
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> groups = (List<Map<String, Object>>) aj.get("groups");
                if (groups != null) {
                    for (Map<String, Object> g : groups) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("team", g.get("team"));
                        entry.put("count", g.getOrDefault("count", 0));
                        entry.put("urgentPct", g.getOrDefault("urgentPct", 0));
                        entry.put("urgent", g.getOrDefault("urgent", "0% urgent"));
                        entry.put("risk", g.getOrDefault("risk", "low"));
                        entry.put("summary", g.getOrDefault("summary", ""));
                        entry.put("callout", g.getOrDefault("callout", ""));
                        // Pass through the long-form fields too in case the UI
                        // wants an "expand" affordance later.
                        entry.put("themes", g.getOrDefault("themes", ""));
                        entry.put("callouts", g.getOrDefault("callouts", ""));
                        analysis.add(entry);
                    }
                }
                Object pw = aj.get("pcmWorkload");
                if (pw != null) out.put("pcmWorkload", pw);
                Object rs = aj.get("reportSuggestions");
                if (rs != null) out.put("reportSuggestions", rs);
            } catch (Exception ajErr) {
                LOG.warning("[TEAM-REPORT-DATA] analysis.json parse failed: " + ajErr.getMessage());
            }
        }
        out.put("analysis", analysis);
        return out;
    }

    // ============================================================
    // SAX streaming workbook access. Every read below is a forward row
    // scan over known sheets/columns, so the event model replaces the
    // ~1 GB XSSF DOM with a <50 MB streaming pass.
    // ============================================================

    /** Per-row callback for {@link #streamSheets}. {@code cells} maps 0-based
     *  column index → formatted cell string (blank cells absent). */
    private interface RowSink { void onRow(int rowIdx, Map<Integer, String> cells); }

    /** Streams the requested sheets of an xlsx through POI's event model.
     *  Sheets without a sink are skipped without parsing their XML. Returns
     *  the names of ALL sheets present (for missing-sheet checks). */
    private static java.util.Set<String> streamSheets(java.io.File xlsx, Map<String, RowSink> sinks)
            throws IOException {
        java.util.Set<String> present = new java.util.HashSet<>();
        try (OPCPackage pkg = OPCPackage.open(xlsx, PackageAccess.READ)) {
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (java.io.InputStream is = it.next()) {
                    String name = it.getSheetName();
                    present.add(name);
                    RowSink sink = sinks.get(name);
                    if (sink == null) continue;
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(
                            styles, null, strings, new SheetRowCollector(sink),
                            new DataFormatter(), false));
                    parser.parse(new InputSource(is));
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to stream " + xlsx.getName() + ": " + e.getMessage(), e);
        }
        return present;
    }

    /** Collects each row's non-blank formatted cells into a small map. */
    private static class SheetRowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final RowSink sink;
        private Map<Integer, String> cells = new java.util.HashMap<>();
        private int lastCol = -1;
        SheetRowCollector(RowSink sink) { this.sink = sink; }
        @Override public void startRow(int rowNum) { cells = new java.util.HashMap<>(); lastCol = -1; }
        @Override public void endRow(int rowNum) { sink.onRow(rowNum, cells); }
        @Override public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            // cellReference is null for producers that omit r= attributes;
            // those emit cells in order, so fall back to a running counter.
            int col = cellReference != null
                    ? new CellReference(cellReference).getCol()
                    : lastCol + 1;
            lastCol = col;
            if (formattedValue != null && !formattedValue.isEmpty()) cells.put(col, formattedValue);
        }
    }

    /** Numeric cell text → int. The event model hands us formatted strings,
     *  so tolerate "12", "12.0" and "1,234"; anything else counts as 0
     *  (matches the old DOM readInt's exception-swallowing). */
    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) return 0;
        try {
            return (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Single streaming pass deriving the workbook-backed /data payload:
     *  - pcms: 'Total Changes Process' rows D5..D10 (trimmed)
     *  - changes/volume: per-PCM per-month [AML, ECO, MCO, ECN] from 4-col
     *    blocks (changes start col E on the same rows; volume rows 3..8
     *    starting col B on 'Total Volume Process2026')
     *  - ytd rollup
     *  - activityTypes: col-P classifications for `month` mapped through the
     *    'Classification Change Type' lookup (replicates col Q's VLOOKUP)
     *  - ecnByProductLine: per-month product-line counts, col G split on '|'
     * Row/column coordinates are identical to the old DOM implementation.
     */
    static Map<String, Object> scanWorkbookData(java.io.File xlsx, String month) throws IOException {
        String[] monthParts = month.split("_");
        int monthIdx = MONTH_INDEX.getOrDefault(monthParts[0], 4); // 0-based; May=4
        java.util.List<String> monthLabels = new ArrayList<>();
        for (int i = 0; i <= monthIdx; i++) monthLabels.add(PCM_MONTHS[i]);
        String targetYear = month.contains("_") ? month.substring(month.indexOf('_') + 1) : "";

        // Collected during the pass. changesRows/volumeRows hold only the
        // handful of summary rows; the big Raw data sheet aggregates in place
        // except classifications, which need the lookup sheet (sheet order in
        // the file is not guaranteed, so resolve after the pass).
        Map<Integer, Map<Integer, String>> changesRows = new java.util.HashMap<>();
        Map<Integer, Map<Integer, String>> volumeRows = new java.util.HashMap<>();
        Map<String, String> clsToCat = new java.util.HashMap<>();
        java.util.List<String> monthClassifications = new ArrayList<>();
        Map<String, Map<String, Integer>> plByMonth = new LinkedHashMap<>();
        for (String m : monthLabels) plByMonth.put(m, new java.util.HashMap<>());

        Map<String, RowSink> sinks = new java.util.HashMap<>();
        sinks.put("Total Changes Process", (r, cells) -> {
            if (r >= 4 && r <= 9) changesRows.put(r, cells);
        });
        sinks.put("Total Volume Process2026", (r, cells) -> {
            if (r >= 2 && r <= 7) volumeRows.put(r, cells);
        });
        sinks.put("Classification Change Type", (r, cells) -> {
            if (r == 0) return;
            String cls = cells.getOrDefault(0, "").trim();
            String cat = cells.getOrDefault(1, "").trim();
            if (!cls.isEmpty() && !cat.isEmpty()) clsToCat.put(cls, normalizeCategory(cat));
        });
        sinks.put("Raw data-No Dup", (r, cells) -> {
            if (r == 0) return;
            String rowMonth = cells.getOrDefault(14, "").trim();
            if (rowMonth.isEmpty() || !rowMonth.contains("_")) return;
            String mmm = rowMonth.substring(0, rowMonth.indexOf('_'));
            String yyyy = rowMonth.substring(rowMonth.indexOf('_') + 1);
            if (rowMonth.equalsIgnoreCase(month)) {
                String cls = cells.getOrDefault(15, "").trim();
                if (!cls.isEmpty()) monthClassifications.add(cls);
            }
            if (yyyy.equals(targetYear) && plByMonth.containsKey(mmm)) {
                String plField = cells.getOrDefault(6, "");
                for (String pl : plField.split("\\|")) {
                    String p = pl.trim();
                    if (!p.isEmpty()) plByMonth.get(mmm).merge(p, 1, Integer::sum);
                }
            }
        });

        java.util.Set<String> present = streamSheets(xlsx, sinks);
        if (!present.contains("Total Changes Process")) throw new IOException("Sheet 'Total Changes Process' missing");
        if (!present.contains("Total Volume Process2026")) throw new IOException("Sheet 'Total Volume Process2026' missing");

        // PCMs: rows 5..10 (1-based) col D (idx 3). Trim trailing space
        // (sample has "Daeren Hong ").
        java.util.List<String> pcms = new ArrayList<>();
        for (int r = 4; r <= 9; r++) {
            Map<Integer, String> row = changesRows.get(r);
            if (row == null) continue;
            String pcm = row.getOrDefault(3, "");
            if (!pcm.trim().isEmpty()) pcms.add(pcm.trim());
        }

        // changes: per PCM per month [AML, ECO, MCO, ECN]; col E (idx 4) =
        // Jan AML, F=Jan ECO, G=Jan MCO, H=Jan ECN → monthOffset = 4 + mi*4.
        Map<String, Map<String, int[]>> changesMap = new LinkedHashMap<>();
        for (int i = 0; i < pcms.size(); i++) {
            String pcm = pcms.get(i);
            Map<String, int[]> perMonth = new LinkedHashMap<>();
            Map<Integer, String> row = changesRows.get(4 + i);
            if (row == null) { changesMap.put(pcm, perMonth); continue; }
            for (int mi = 0; mi < monthLabels.size(); mi++) {
                int baseCol = 4 + mi * 4;
                int[] vals = new int[4];
                for (int k = 0; k < 4; k++) vals[k] = parseIntSafe(row.get(baseCol + k));
                perMonth.put(monthLabels.get(mi), vals);
            }
            changesMap.put(pcm, perMonth);
        }

        // volume: PCM rows 3..8 (1-based), 4-col groups starting col B (idx 1).
        Map<String, Map<String, int[]>> volumeMap = new LinkedHashMap<>();
        for (int i = 0; i < pcms.size(); i++) {
            String pcm = pcms.get(i);
            Map<String, int[]> perMonth = new LinkedHashMap<>();
            Map<Integer, String> row = volumeRows.get(2 + i);
            if (row == null) { volumeMap.put(pcm, perMonth); continue; }
            for (int mi = 0; mi < monthLabels.size(); mi++) {
                int baseCol = 1 + mi * 4;
                int[] vals = new int[4];
                for (int k = 0; k < 4; k++) vals[k] = parseIntSafe(row.get(baseCol + k));
                perMonth.put(monthLabels.get(mi), vals);
            }
            volumeMap.put(pcm, perMonth);
        }

        // YTD = sum across the months we read (per PCM)
        // [affected items (AML+ECO+MCO), xCOs (AML+ECO+MCO), ECN]
        Map<String, int[]> ytd = new LinkedHashMap<>();
        for (String pcm : pcms) {
            int items = 0, xco = 0, ecn = 0;
            Map<String, int[]> vMonths = volumeMap.get(pcm);
            Map<String, int[]> cMonths = changesMap.get(pcm);
            if (vMonths != null) {
                for (int[] v : vMonths.values()) { items += v[0] + v[1] + v[2]; ecn += v[3]; }
            }
            if (cMonths != null) {
                for (int[] c : cMonths.values()) { xco += c[0] + c[1] + c[2]; }
            }
            ytd.put(pcm, new int[] { items, xco, ecn });
        }

        // activityTypes: classification → category counts, sorted desc.
        Map<String, Integer> activity = new java.util.HashMap<>();
        for (String cls : monthClassifications) {
            String cat = clsToCat.get(cls);
            if (cat != null && !cat.isEmpty()) activity.merge(cat, 1, Integer::sum);
        }
        java.util.List<Map<String, Object>> activityList = new ArrayList<>();
        activity.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    activityList.add(m);
                });

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("months", monthLabels);
        core.put("pcms", pcms);
        core.put("changes", changesMap);
        core.put("volume", volumeMap);
        core.put("ytd", ytd);
        core.put("activityTypes", activityList);
        core.put("ecnByProductLine", plByMonth);
        return core;
    }

    private static String normalizeCategory(String c) {
        if (c == null) return "";
        String t = c.trim();
        if (t.equalsIgnoreCase("Data Aligment")) return "Data Alignment";
        return t;
    }

    private static String monthLabel(String month) {
        if (month == null || !month.contains("_")) return month;
        String[] p = month.split("_");
        String mmm = p[0];
        java.util.Map<String, String> full = new java.util.HashMap<>();
        full.put("Jan", "January"); full.put("Feb", "February"); full.put("Mar", "March");
        full.put("Apr", "April"); full.put("May", "May"); full.put("Jun", "June");
        full.put("Jul", "July"); full.put("Aug", "August"); full.put("Sep", "September");
        full.put("Oct", "October"); full.put("Nov", "November"); full.put("Dec", "December");
        return full.getOrDefault(mmm, mmm) + " " + p[1];
    }

    // ============================================================
    // AI post-processing: scan 'Raw data-affected item' rows for the
    // input month, group by Program Team, ask the LLM for themes +
    // risk callouts per team, then run the Python postprocessor that
    // patches column R (#N/A -> "Mix") and writes the AI Analysis tab.
    // ============================================================

    /** Returns the path of the analysis.json that fed the postprocess, so the
     *  caller can persist it as a sidecar to the xlsx (Team Report in-app
     *  reads this directly — never re-parses the xlsx prose). Null on any
     *  failure before the JSON is written. */
    private Path runAiPostProcess(Path outPath, Path tmp, String python, String month) throws Exception {
        // 1. Read rows + Program Team lookup from the freshly-built XLSX.
        Map<String, List<RowData>> grouped = extractGroupedForMonth(outPath, month);
        LOG.info("[TEAM-REPORT] AI post-process: " + grouped.size() + " teams in " + month
                + " (rows: " + grouped.values().stream().mapToInt(List::size).sum() + ")");

        // 2. Per-team LLM call. Run in parallel (Java common ForkJoinPool) since
        // each call is ~5s and there are typically 4-7 teams. Sort by team name
        // so the resulting tab has a deterministic, human-friendly row order.
        List<Map<String, Object>> analysisGroups = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> CompletableFuture.supplyAsync(() -> analyzeTeam(e.getKey(), e.getValue(), month)))
                .collect(Collectors.toList())
                .stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        // 3. Write analysis JSON for the postprocessor to consume.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("month", month);
        json.put("groups", analysisGroups);
        Path analysisPath = tmp.resolve("analysis.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(analysisPath.toFile(), json);

        // 4. Run team_report_postprocess.py to apply the column-R fix + write the AI Analysis sheet.
        Path postPath = Paths.get("./data/team-report/team_report_postprocess.py").toAbsolutePath().normalize();
        if (!Files.exists(postPath)) {
            throw new RuntimeException("Postprocess script missing at " + postPath);
        }
        ProcessBuilder pb = new ProcessBuilder(
                python, postPath.toString(),
                "--xlsx", outPath.toString(),
                "--analysis-json", analysisPath.toString(),
                "--month", month);
        pb.directory(tmp.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        StringBuilder buf = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) buf.append(line).append('\n');
        }
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("Postprocess script timeout");
        }
        if (p.exitValue() != 0) {
            String tailOut = buf.toString();
            if (tailOut.length() > 1000) tailOut = tailOut.substring(tailOut.length() - 1000);
            throw new RuntimeException("Postprocess exit " + p.exitValue() + ": " + tailOut);
        }
        return analysisPath;
    }

    /** Heap-gated, single-flight wrapper around {@link #scanGroupedForMonth}. */
    private Map<String, List<RowData>> extractGroupedForMonth(Path xlsx, String month) throws IOException {
        acquireScanPermit();
        try {
            return scanGroupedForMonth(xlsx.toFile(), month);
        } finally {
            SCAN_PERMIT.release();
        }
    }

    /**
     * Read 'Raw data-affected item' rows where column O (Month) == input month, group by
     * Program Team. Team is computed from column G (Product Lines) via the lookup table
     * in the 'Program Team' sheet; multi-line G or empty G or unmapped product line all
     * collapse to "Mix" (matches the IFERROR rule the postprocessor applies in column R).
     * Streaming pass; sheet order isn't guaranteed, so rows buffer their product-line
     * string and the team resolves after the scan.
     */
    static Map<String, List<RowData>> scanGroupedForMonth(java.io.File xlsx, String month) throws IOException {
        Map<String, String> plToTeam = new LinkedHashMap<>();
        List<RowData> monthRows = new ArrayList<>();

        Map<String, RowSink> sinks = new java.util.HashMap<>();
        sinks.put("Program Team", (r, cells) -> {
            if (r == 0) return;
            String pl = cells.getOrDefault(0, "");
            String team = cells.getOrDefault(1, "");
            if (!pl.isEmpty() && !team.isEmpty()) plToTeam.put(pl.trim(), team.trim());
        });
        sinks.put("Raw data-affected item", (r, cells) -> {
            if (r == 0) return;
            String monthCell = cells.getOrDefault(14, ""); // O = "Month"
            if (!month.equalsIgnoreCase(monthCell.trim())) return;
            RowData rd = new RowData();
            rd.description = cells.getOrDefault(2, "");     // C = Description of Change
            rd.status = cells.getOrDefault(3, "");          // D = Status
            rd.changeType = cells.getOrDefault(5, "");      // F = Change Type
            rd.productLines = cells.getOrDefault(6, "");    // G = Product Line(s)
            rd.priority = cells.getOrDefault(7, "");        // H = Priority
            rd.classifications = cells.getOrDefault(15, ""); // P = Classifications
            monthRows.add(rd);
        });

        streamSheets(xlsx, sinks);

        Map<String, List<RowData>> result = new LinkedHashMap<>();
        for (RowData rd : monthRows) {
            String team = computeTeam(rd.productLines, plToTeam);
            result.computeIfAbsent(team, k -> new ArrayList<>()).add(rd);
        }
        return result;
    }

    private static String computeTeam(String productLines, Map<String, String> map) {
        if (productLines == null || productLines.trim().isEmpty()) return "Mix";
        // ChangeSearchService produces ';'-joined product line strings for multi-line
        // changes — those are exactly the rows that VLOOKUP fails on, so collapse to Mix.
        if (productLines.contains(";")) return "Mix";
        String t = map.get(productLines.trim());
        return (t != null && !t.isEmpty()) ? t : "Mix";
    }

    /**
     * Build the LLM prompt for one team and parse its JSON response. Themes target
     * anomalies, spikes, and major contributions; callouts target Urgent priority,
     * missed deliveries, and CAPA / quality issues. Returns an analysis row map.
     */
    private Map<String, Object> analyzeTeam(String team, List<RowData> rows, String month) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("team", team);
        g.put("count", rows.size());

        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, Integer> byPriority = new TreeMap<>();
        Map<String, Integer> byChangeType = new TreeMap<>();
        Map<String, Integer> byClassification = new TreeMap<>();
        Map<String, Integer> byProductLine = new TreeMap<>();
        for (RowData r : rows) {
            if (!r.status.isEmpty()) byStatus.merge(r.status, 1, Integer::sum);
            if (!r.priority.isEmpty()) byPriority.merge(r.priority, 1, Integer::sum);
            if (!r.changeType.isEmpty()) byChangeType.merge(r.changeType, 1, Integer::sum);
            if (!r.classifications.isEmpty() && !"#N/A".equals(r.classifications)) byClassification.merge(r.classifications, 1, Integer::sum);
            if (!r.productLines.isEmpty()) byProductLine.merge(r.productLines, 1, Integer::sum);
        }

        // Per Team Report in-app handoff (Noraida): the UI needs `urgent`
        // (formatted percent string), `urgentPct` (raw int) and `risk`
        // ("high" / "med" / "low") per team. Derive directly from byPriority
        // so the AI doesn't have to repeat it. Risk heuristic matches the
        // sample values in report-data.js: large-volume teams with a heavy
        // urgent share are "high"; tiny teams are "low" regardless of share;
        // everything else is "med".
        int urgentCount = byPriority.getOrDefault("Urgent", 0);
        int urgentPct = rows.isEmpty() ? 0 : (int) Math.round(100.0 * urgentCount / rows.size());
        String risk;
        if (rows.size() < 50) risk = "low";
        else if (urgentPct >= 40 && rows.size() >= 100) risk = "high";
        else risk = "med";
        g.put("urgentPct", urgentPct);
        g.put("urgent", urgentPct + "% urgent");
        g.put("risk", risk);

        StringBuilder user = new StringBuilder();
        user.append("Program Team: ").append(team).append('\n');
        user.append("Month: ").append(month).append('\n');
        user.append("Total affected-item rows: ").append(rows.size()).append('\n');
        user.append("\nBreakdowns (count : value):\n");
        user.append("  By status: ").append(formatMap(byStatus)).append('\n');
        user.append("  By priority: ").append(formatMap(byPriority)).append('\n');
        user.append("  By change type: ").append(formatMap(byChangeType)).append('\n');
        user.append("  Top product lines: ").append(formatMapTop(byProductLine, 6)).append('\n');
        user.append("  Top classifications: ").append(formatMapTop(byClassification, 6)).append('\n');
        user.append("\nSample of change descriptions (up to 50):\n");
        int maxDesc = Math.min(50, rows.size());
        for (int i = 0; i < maxDesc; i++) {
            RowData r = rows.get(i);
            String d = r.description.replace('\n', ' ').replace("\r", "");
            if (d.length() > 280) d = d.substring(0, 280) + "...";
            user.append(i + 1).append(". [").append(r.priority.isEmpty() ? "?" : r.priority)
                .append("|").append(r.changeType.isEmpty() ? "?" : r.changeType)
                .append("] ").append(d).append('\n');
        }

        String system =
                "You are analysing Agile PLM monthly change activity for the SanDisk PCM team. "
                + "Identify TWO things from the data and descriptions:\n"
                + "  themes  : 2-4 bullets surfacing ANOMALIES, SPIKES, and MAJOR CONTRIBUTIONS \u2014 "
                + "things that take a huge slice of the volume (cite counts), unusual concentrations of "
                + "one classification or product line, or sudden category shifts.\n"
                + "  callouts: 2-4 bullets flagging RISK \u2014 anything Urgent priority, anything mentioning "
                + "or implying missed deliveries, CAPA, quality issues, line-down, expedites, or recalls. "
                + "If none observed, write 'None observed.'\n"
                + "Be concrete. Cite numbers and example xCO# / item where useful. No fluff.\n"
                + "Return ONLY a JSON object with three string keys: themes, callouts, summary. "
                + "themes and callouts are bullet lists separated by '\\n' newlines, each line starting with '\u2022 '. "
                + "summary is one or two sentences in plain prose, NOT a list. "
                + "Return the JSON object directly without code fences.";

        if (!portkeyClient.isEnabled()) {
            g.put("themes", "(AI unavailable: portkey.enabled=false)");
            g.put("callouts", "");
            g.put("summary", "");
            return g;
        }
        // Vortex/Portkey requires the full provider/model slug, e.g.
        // "@anthropic-eastus2/claude-sonnet-4-6". The two are separate
        // properties (`portkey.provider`, `portkey.model`); join here.
        String model = portkeyProvider + "/" + portkeyModel;
        // 1500 tokens is enough for ~3 themes + ~3 callouts + summary even on the
        // largest teams. 800 was getting truncated mid-string for high-volume teams
        // (CS hit the cap on the first run with 1,279 rows of EOL/OBS detail).
        //
        // Per-team retry sits on top of PortkeyClient's own one-retry on transient
        // transport errors. The combination handles two failure modes:
        //   (a) brief network/gateway hiccup mid-call — PortkeyClient handles.
        //   (b) gateway returns a real error (e.g. parse fail, content-safety reject)
        //       on attempt 1 but succeeds on a fresh re-roll — handled here.
        // A short pause between attempts gives Portkey/Azure time to recover.
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = portkeyClient.chat(model, system, user.toString(), 1500);
                String json = stripCodeFences(raw);
                Map<String, Object> parsed = JSON.readValue(json, new TypeReference<Map<String, Object>>(){});
                String themesStr = String.valueOf(parsed.getOrDefault("themes", ""));
                String calloutsStr = String.valueOf(parsed.getOrDefault("callouts", ""));
                String summaryStr = String.valueOf(parsed.getOrDefault("summary", ""));
                g.put("themes", themesStr);
                g.put("callouts", calloutsStr);
                g.put("summary", summaryStr);
                // Single-line `callout` (first non-empty bullet, prefix stripped)
                // so the in-app UI can render it inline without re-parsing the
                // bullet markdown. Mirrors the report-data.js sample shape.
                g.put("callout", firstCallout(calloutsStr));
                return g;
            } catch (Exception e) {
                lastErr = e;
                if (attempt < 2) {
                    LOG.warning("[TEAM-REPORT] AI call attempt " + attempt + " failed for team "
                        + team + " (" + e.getMessage() + ") — retrying once after pause");
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // Both attempts failed — log the full exception message for diagnosis,
        // but show end users a friendly cell value that tells them what to do
        // (re-run the report) instead of leaking the raw exception text.
        LOG.warning("[TEAM-REPORT] AI call failed for team " + team + " after 2 attempts: "
            + (lastErr == null ? "unknown" : lastErr.getMessage()));
        g.put("themes", "(AI temporarily unavailable for this team — re-run the report)");
        g.put("callouts", "");
        g.put("summary", "");
        g.put("callout", "");
        return g;
    }

    /** AI exec summary + recommendations over per-PCM workload (one Portkey call).
     *  data is the map from extractTeamReportData (has "pcms" + "ytd"[items,xco,ecn]). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzePcmWorkload(Map<String, Object> data, String month) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", ""); out.put("recommendations", "");
        if (!portkeyClient.isEnabled()) { out.put("summary", "(AI unavailable: portkey.enabled=false)"); return out; }

        List<String> pcms = (List<String>) data.getOrDefault("pcms", new ArrayList<>());
        Map<String, Object> ytd = (Map<String, Object>) data.getOrDefault("ytd", new LinkedHashMap<>());
        if (pcms.isEmpty()) return out;

        long grand = 0;
        for (String p : pcms) { int[] y = (int[]) ytd.get(p); if (y != null && y.length > 1) grand += y[1]; }
        StringBuilder stats = new StringBuilder();
        for (String p : pcms) {
            int[] y = (int[]) ytd.get(p);
            long items = (y != null && y.length > 0) ? y[0] : 0;
            long xco = (y != null && y.length > 1) ? y[1] : 0;
            long ecn = (y != null && y.length > 2) ? y[2] : 0;
            int pct = grand > 0 ? (int) Math.round(100.0 * xco / grand) : 0;
            stats.append("  ").append(p).append(": xCO ").append(xco).append(", affected items ")
                 .append(items).append(", ECN ").append(ecn).append(", ").append(pct).append("% of total\n");
        }
        String system =
            "You are an operations analyst summarising PCM (change-manager) workload for SanDisk PLM. "
            + "Given per-PCM volume stats for the month, return ONLY a JSON object with two string keys: "
            + "\"summary\" (2-4 sentences of executive prose: who carries the load, imbalance, notable concentration) and "
            + "\"recommendations\" (3-5 newline-separated bullets covering workload balancing, resource planning, "
            + "efficiency, and risk areas). Be concrete and cite the PCM names/percentages.";
        String user = "Month: " + month + "\nPer-PCM workload (ranked input):\n" + stats;
        String model = portkeyProvider + "/" + portkeyModel;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Map<String, Object> parsed = JSON.readValue(stripCodeFences(portkeyClient.chat(model, system, user, 1200)),
                        new TypeReference<Map<String, Object>>(){});
                out.put("summary", String.valueOf(parsed.getOrDefault("summary", "")));
                out.put("recommendations", String.valueOf(parsed.getOrDefault("recommendations", "")));
                return out;
            } catch (Exception e) {
                if (attempt < 2) { try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
                else LOG.warning("[TEAM-REPORT] PCM workload AI failed: " + e.getMessage());
            }
        }
        out.put("summary", "(AI temporarily unavailable — re-run the report)");
        return out;
    }

    /** AI suggestions to improve the report itself (one Portkey call). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeReportSuggestions(Map<String, Object> data, List<Map<String, Object>> groups, String month) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestions", "");
        if (!portkeyClient.isEnabled()) { out.put("suggestions", "(AI unavailable: portkey.enabled=false)"); return out; }
        List<String> pcms = (List<String>) data.getOrDefault("pcms", new ArrayList<>());
        Map<String, Object> ytd = (Map<String, Object>) data.getOrDefault("ytd", new LinkedHashMap<>());
        long grand = 0;
        for (String p : pcms) { int[] y = (int[]) ytd.get(p); if (y != null && y.length > 1) grand += y[1]; }
        StringBuilder stats = new StringBuilder();
        for (String p : pcms) {
            int[] y = (int[]) ytd.get(p);
            long xco = (y != null && y.length > 1) ? y[1] : 0;
            int pct = grand > 0 ? (int) Math.round(100.0 * xco / grand) : 0;
            stats.append("  ").append(p).append(": xCO ").append(xco).append(", ").append(pct).append("% of total\n");
        }
        String system =
            "You are a reporting/analytics advisor. The SanDisk PLM monthly Team Report already shows: per-PCM "
            + "processing tables, volume-by-PCM and by-month charts, a change-activities pie, ECN-by-product-line, "
            + "a yearly trend, and per-Program-Team AI analysis. Suggest concrete improvements to the report's "
            + "insights and usability. Return ONLY a JSON object with one string key \"suggestions\" = 3-5 "
            + "newline-separated bullets. Be specific and actionable; avoid generic advice.";
        String user = "Month: " + month + "\nProgram-team analyses: " + (groups == null ? 0 : groups.size())
            + "\nPer-PCM workload:\n" + stats;
        String model = portkeyProvider + "/" + portkeyModel;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Map<String, Object> parsed = JSON.readValue(stripCodeFences(portkeyClient.chat(model, system, user, 900)),
                        new TypeReference<Map<String, Object>>(){});
                out.put("suggestions", String.valueOf(parsed.getOrDefault("suggestions", "")));
                return out;
            } catch (Exception e) {
                if (attempt < 2) { try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
                else LOG.warning("[TEAM-REPORT] report-suggestions AI failed: " + e.getMessage());
            }
        }
        out.put("suggestions", "(AI temporarily unavailable — re-run the report)");
        return out;
    }

    /** Pull the first non-empty bullet out of the AI's `callouts` string and
     *  strip the leading "• " / "* " / "- " bullet markers so it can be
     *  rendered inline. Returns empty string if there are no bullets or only
     *  "None observed.". Used by the in-app Team Report tab. */
    private static String firstCallout(String calloutsStr) {
        if (calloutsStr == null || calloutsStr.trim().isEmpty()) return "";
        String[] lines = calloutsStr.split("\\r?\\n");
        for (String raw : lines) {
            String s = raw == null ? "" : raw.trim();
            // Strip leading bullet markers (•, *, -) plus any whitespace after.
            s = s.replaceFirst("^[\\u2022\\*\\-]\\s*", "").trim();
            if (s.isEmpty()) continue;
            if (s.equalsIgnoreCase("None observed.") || s.equalsIgnoreCase("None observed")) return "";
            return s;
        }
        return "";
    }

    private static String stripCodeFences(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String formatMap(Map<String, Integer> m) {
        if (m.isEmpty()) return "(none)";
        return m.entrySet().stream()
                .map(e -> e.getValue() + ":" + e.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String formatMapTop(Map<String, Integer> m, int n) {
        if (m.isEmpty()) return "(none)";
        return m.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> e.getValue() + ":" + e.getKey())
                .collect(Collectors.joining(", "));
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> teamReportHistory(HttpSession session) {
        if (session.getAttribute("username") == null) {
            return ResponseEntity.status(401).body(err("Not authenticated"));
        }
        try {
            return ResponseEntity.ok(loadYearlyHistory());
        } catch (Exception e) {
            LOG.warning("[TEAM-REPORT-HISTORY] failed: " + e.getMessage());
            return ResponseEntity.status(500).body(err("Failed to read yearly history: " + e.getMessage()));
        }
    }

    /**
     * Yearly history seed. External override at ./data/team-report/yearly-history.json wins
     * (hot-swappable with Noraida's real file); otherwise the bundled classpath default.
     */
    private Map<String, Object> loadYearlyHistory() throws IOException {
        java.nio.file.Path override = java.nio.file.Paths.get("data", "team-report", "yearly-history.json");
        if (java.nio.file.Files.exists(override)) {
            return JSON.readValue(override.toFile(), new TypeReference<Map<String, Object>>(){});
        }
        try (java.io.InputStream in = getClass().getResourceAsStream("/team-report/yearly-history.json")) {
            if (in == null) throw new IOException("bundled yearly-history.json not found on classpath");
            return JSON.readValue(in, new TypeReference<Map<String, Object>>(){});
        }
    }

    static class RowData {
        String description = "";
        String status = "";
        String changeType = "";
        String priority = "";
        String productLines = "";
        String classifications = "";
    }
}
