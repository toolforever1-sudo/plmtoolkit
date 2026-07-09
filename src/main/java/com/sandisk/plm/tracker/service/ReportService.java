package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class ReportService {

    private static final Logger logger = Logger.getLogger(ReportService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PortkeyClient portkeyClient;

    @Value("${app.reports.python:python}")
    private String pythonExe;

    @Value("${app.reports.java:java}")
    private String javaExe;

    @Value("${app.reports.javac:javac}")
    private String javacExe;

    @Value("${app.reports.java8:}")
    private String java8Exe;

    @Value("${app.reports.timeout:600}")
    private int timeoutSeconds;

    @Value("${ai.help.api-key:}")
    private String aiApiKey;

    @Value("${ai.help.enabled:false}")
    private boolean aiEnabled;

    @Value("${portkey.enabled:false}")
    private boolean portkeyEnabled;

    @Value("${portkey.api-key:}")
    private String portkeyApiKey;

    @Value("${portkey.provider:@anthropic-eastus2}")
    private String portkeyProvider;

    @Value("${portkey.model:claude-sonnet-4-6}")
    private String portkeyModel;

    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}")
    private String portkeyBaseUrl;

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from}")
    private String fromAddress;

    private static final String UTILITIES_DIR = "./data/utilities";
    private static final String STATUSES_FILE = "./data/report-statuses.json";

    private List<Map<String, String>> reportDefinitions = new ArrayList<>();
    private final ConcurrentHashMap<String, ReportStatus> statuses = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadReportDefinitions();
        migrateOldOwners();
        loadStatuses();
    }

    /** One-time migration: fix utilities that have "Uploaded" as owner */
    private void migrateOldOwners() {
        boolean changed = false;
        for (Map<String, String> r : reportDefinitions) {
            if ("utility".equals(r.get("type")) && "Uploaded".equals(r.get("owner"))) {
                r.put("owner", "Vikas Jindal");
                r.put("ownerUsername", "8252");
                changed = true;
                logger.info("[REPORTS] Migrated owner for utility: " + r.get("name"));
            }
        }
        if (changed) {
            try { saveReportDefinitions(); } catch (Exception e) {
                logger.warning("[REPORTS] Failed to save migrated owners: " + e.getMessage());
            }
        }
    }

    private void loadReportDefinitions() {
        File f = new File("./data/reports.json");
        if (!f.exists()) {
            logger.info("[REPORTS] No reports.json found at " + f.getAbsolutePath());
            return;
        }
        try {
            reportDefinitions = mapper.readValue(f, new TypeReference<List<Map<String, String>>>() {});
            logger.info("[REPORTS] Loaded " + reportDefinitions.size() + " report definitions");
        } catch (Exception e) {
            logger.warning("[REPORTS] Failed to load reports.json: " + e.getMessage());
        }
    }

    public List<Map<String, String>> getReports() {
        return reportDefinitions;
    }

    /** Return only reports this user is allowed to see */
    public List<Map<String, String>> getReportsForUser(String username, boolean isAdmin) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> r : reportDefinitions) {
            if (isUserAllowed(r, username, isAdmin)) result.add(r);
        }
        return result;
    }

    /** Check if a user can access a specific report */
    public boolean isUserAllowed(String reportId, String username, boolean isAdmin) {
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id"))) return isUserAllowed(r, username, isAdmin);
        }
        return false;
    }

    private boolean isUserAllowed(Map<String, String> report, String username, boolean isAdmin) {
        // Admins (pdl-plm-admin members) always see all reports
        if (isAdmin) return true;
        // Non-admins only see reports where they're in allowedUsers
        String allowed = report.get("allowedUsers");
        if (allowed == null || allowed.trim().isEmpty()) return false; // no allowedUsers = admin-only
        for (String u : allowed.split(",")) {
            if (u.trim().equalsIgnoreCase(username)) return true;
        }
        return false;
    }

    public Map<String, Object> getStatus(String reportId) {
        ReportStatus status = statuses.get(reportId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (status == null) {
            result.put("status", "idle");
            return result;
        }
        result.put("status", status.status);
        result.put("startTime", status.startTime);
        result.put("endTime", status.endTime);
        result.put("error", status.error);
        if (status.outputFile != null) {
            File f = new File(status.outputFile);
            if (f.exists()) {
                result.put("filename", f.getName());
                result.put("size", f.length());
            }
        }
        if (status.startTime > 0) {
            long elapsed = (status.endTime > 0 ? status.endTime : System.currentTimeMillis()) - status.startTime;
            result.put("elapsedSeconds", elapsed / 1000);
        }
        // Include live stdout logs for the stream panel
        result.put("logs", new ArrayList<String>(status.logs));
        return result;
    }

    /**
     * Returns last-run info for a report, or null if never run.
     */
    public Map<String, String> getLastRunInfo(String reportId) {
        ReportStatus status = statuses.get(reportId);
        if (status == null || status.startTime == 0) return null;
        Map<String, String> info = new LinkedHashMap<>();
        long endMs = status.endTime > 0 ? status.endTime : status.startTime;
        long elapsed = (endMs - status.startTime) / 1000;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM d, HH:mm");
        if ("running".equals(status.status)) {
            info.put("lastRun", "Running now");
            info.put("lastRunBy", status.displayName != null ? status.displayName : "");
        } else if ("completed".equals(status.status)) {
            info.put("lastRun", sdf.format(new java.util.Date(endMs)));
            info.put("lastRunBy", (status.displayName != null ? status.displayName : "") + " \u00b7 " + elapsed + "s");
            if (status.outputFile != null) {
                File f = new File(status.outputFile);
                if (f.exists()) info.put("lastRunBy", info.get("lastRunBy") + " \u00b7 " + (f.length() / 1024) + " KB");
            }
        } else if ("failed".equals(status.status)) {
            info.put("lastRun", "Failed " + sdf.format(new java.util.Date(endMs)));
            info.put("lastRunBy", status.displayName != null ? status.displayName : "");
        }
        return info;
    }

    public File getOutputFile(String reportId) {
        ReportStatus status = statuses.get(reportId);
        if (status == null || status.outputFile == null) return null;
        File f = new File(status.outputFile);
        return f.exists() ? f : null;
    }

    // ---- Built-in report status management ----

    public void markBuiltinRunning(String id, String displayName) {
        ReportStatus status = new ReportStatus();
        status.status = "running";
        status.startTime = System.currentTimeMillis();
        status.displayName = displayName;
        statuses.put(id, status);
    }

    public void markBuiltinCompleted(String id) {
        ReportStatus status = statuses.get(id);
        if (status == null) { status = new ReportStatus(); statuses.put(id, status); }
        status.status = "completed";
        status.endTime = System.currentTimeMillis();
        long elapsed = (status.endTime - status.startTime) / 1000;
        status.addLog("\u2713 Completed in " + elapsed + "s");
        activityLogger.log("system", status.displayName != null ? status.displayName : "system",
            "REPORT_COMPLETED", id + " completed in " + elapsed + "s");
        saveStatuses();
    }

    public void markBuiltinFailed(String id, String error) {
        ReportStatus status = statuses.get(id);
        if (status == null) { status = new ReportStatus(); statuses.put(id, status); }
        status.status = "failed";
        status.endTime = System.currentTimeMillis();
        status.error = error;
        status.addLog("\u2717 Failed: " + error);
        activityLogger.log("system", status.displayName != null ? status.displayName : "system",
            "REPORT_FAILED", id + " failed: " + error);
        saveStatuses();
    }

    public boolean runReport(String reportId, String userEmail, String displayName, boolean emailMeOnComplete) {
        return runReport(reportId, userEmail, displayName, emailMeOnComplete, false);
    }

    public boolean runReport(String reportId, String userEmail, String displayName, boolean emailMeOnComplete, boolean injectMyEmail) {
        // Find report definition
        Map<String, String> report = null;
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id"))) { report = r; break; }
        }
        if (report == null) return false;

        // Check if already running
        ReportStatus existing = statuses.get(reportId);
        if (existing != null && "running".equals(existing.status)) return false;

        // Start async execution
        final Map<String, String> reportDef = report;
        ReportStatus status = new ReportStatus();
        status.status = "running";
        status.startTime = System.currentTimeMillis();
        status.userEmail = userEmail;
        status.displayName = displayName;
        status.emailMeOnComplete = emailMeOnComplete;
        status.injectMyEmail = injectMyEmail;
        statuses.put(reportId, status);

        new Thread(() -> executeReport(reportId, reportDef, status), "report-" + reportId).start();
        return true;
    }

    private void executeReport(String reportId, Map<String, String> report, ReportStatus status) {
        String script = report.get("script");
        String config = report.get("config");
        String outputDir = "./data/reports/output";
        boolean isUtility = "utility".equals(report.get("type"));

        try {
            // Ensure output directory exists
            new File(outputDir).mkdirs();

            // Build command
            List<String> cmd = new ArrayList<>();

            if (isUtility) {
                String filename = report.get("filename");
                File scriptFile = new File(script);
                File scriptDir = scriptFile.getParentFile();

                // Use JDK 1.8 if flagged, or per-utility path, or default
                String effectiveJava = javaExe;
                if ("true".equals(report.get("useJdk8")) && java8Exe != null && !java8Exe.isEmpty()) {
                    effectiveJava = java8Exe;
                } else {
                    String utilJava = report.get("javaPath");
                    if (utilJava != null && !utilJava.isEmpty()) effectiveJava = utilJava;
                }

                if (filename != null && filename.endsWith(".jar")) {
                    // Build classpath: the JAR itself + any dependency JARs in lib/
                    String entryPoint = report.get("entryPoint");
                    String depJarsList = report.get("depJars");
                    StringBuilder cp = new StringBuilder(scriptFile.getAbsolutePath());
                    if (depJarsList != null && !depJarsList.isEmpty()) {
                        File libDir = new File(scriptDir, "lib");
                        String sep = System.getProperty("os.name", "").toLowerCase().contains("win") ? ";" : ":";
                        for (String dj : depJarsList.split(",")) {
                            File djFile = new File(libDir, dj.trim());
                            if (djFile.exists()) cp.append(sep).append(djFile.getAbsolutePath());
                        }
                    }

                    if (entryPoint != null && !entryPoint.isEmpty()) {
                        cmd.add(effectiveJava);
                        cmd.add("-cp");
                        cmd.add(cp.toString());
                        cmd.add(entryPoint);
                    } else if (depJarsList != null && !depJarsList.isEmpty()) {
                        cmd.add(effectiveJava);
                        cmd.add("-cp");
                        cmd.add(cp.toString());
                        status.addLog("\u26A0 No entry point specified \u2014 trying Main-Class from manifest");
                        cmd.add("???");
                    } else {
                        cmd.add(effectiveJava);
                        cmd.add("-jar");
                        cmd.add(scriptFile.getAbsolutePath());
                    }
                    if (!effectiveJava.equals(javaExe)) {
                        status.addLog("\u2699 Using custom Java: " + effectiveJava);
                        logger.info("[REPORTS] Using custom Java path: " + effectiveJava);
                    }
                } else if (filename != null && filename.endsWith(".java")) {
                    // Compile Java first
                    String className = filename.replace(".java", "");
                    logger.info("[REPORTS] Compiling Java utility: " + filename);
                    status.addLog("\u2699 Compiling " + filename + "...");
                    ProcessBuilder compilePb = new ProcessBuilder(javacExe, filename);
                    compilePb.directory(scriptDir);
                    compilePb.redirectErrorStream(true);
                    Process compileProc = compilePb.start();
                    StringBuilder compileOutput = new StringBuilder();
                    try (BufferedReader cr = new BufferedReader(new InputStreamReader(compileProc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String cline;
                        while ((cline = cr.readLine()) != null) {
                            compileOutput.append(cline).append("\n");
                            status.addLog("javac: " + cline);
                            logger.info("[REPORTS] javac: " + cline);
                        }
                    }
                    boolean compiled = compileProc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                    if (!compiled || compileProc.exitValue() != 0) {
                        status.status = "failed";
                        status.error = "Java compilation failed: " + compileOutput.toString();
                        status.addLog("\u2717 Compilation failed");
                        status.endTime = System.currentTimeMillis();
                        saveStatuses();
                        return;
                    }
                    status.addLog("\u2713 Compiled successfully");

                    cmd.add(effectiveJava);
                    cmd.add("-cp");
                    cmd.add(scriptDir.getAbsolutePath());
                    cmd.add(className);
                } else {
                    // Python script
                    cmd.add(pythonExe);
                    cmd.add(script);
                }

                // Add CLI args from definition
                String cliArgs = report.get("cliArgs");
                if (cliArgs != null && !cliArgs.trim().isEmpty()) {
                    for (String arg : cliArgs.trim().split("\\s+")) {
                        cmd.add(arg);
                    }
                } else {
                    // Auto-detect: if no CLI args but supporting files exist,
                    // pass them as positional arguments (common pattern for Java/Python scripts)
                    String supportFilesStr = report.get("supportFiles");
                    if (supportFilesStr != null && !supportFilesStr.isEmpty()) {
                        String[] sfNames = supportFilesStr.split(",");
                        for (String sf : sfNames) {
                            String sfTrimmed = sf.trim();
                            if (!sfTrimmed.isEmpty()) {
                                cmd.add(sfTrimmed);
                            }
                        }
                        status.addLog("\u2139 No CLI args set \u2014 auto-passing " + sfNames.length + " supporting file(s) as arguments");
                        logger.info("[REPORTS] Auto-passing supporting files as CLI args: " + supportFilesStr);
                    }
                }
            } else {
                cmd.add(pythonExe);
                cmd.add(script);
                if (config != null && !config.isEmpty()) {
                    cmd.add("--config");
                    cmd.add(config);
                }
                // Send email only to the invoking user, not the full DL
                if (status.userEmail != null && !status.userEmail.isEmpty()) {
                    cmd.add("--send-to");
                    cmd.add(status.userEmail);
                }
            }

            String cmdStr = String.join(" ", cmd);
            logger.info("[REPORTS] ──────────────────────────────────────────");
            logger.info("[REPORTS] Utility: " + report.getOrDefault("name", reportId));
            logger.info("[REPORTS] ID: " + reportId);
            logger.info("[REPORTS] Command: " + cmdStr);
            logger.info("[REPORTS] CLI args: " + report.getOrDefault("cliArgs", "(none)"));
            logger.info("[REPORTS] Entry point: " + report.getOrDefault("entryPoint", "(default)"));
            logger.info("[REPORTS] Script: " + script);
            logger.info("[REPORTS] Working dir: " + (isUtility ? new File(script).getParent() : "."));
            logger.info("[REPORTS] User: " + status.displayName + " (" + status.userEmail + ")");
            logger.info("[REPORTS] Email on complete: " + status.emailMeOnComplete);
            status.addLog("\u25B6 " + cmdStr);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Fix Unicode output on Windows
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            // Always inject current user's email and name so scripts can use them
            if (status.userEmail != null) pb.environment().put("PLM_USER_EMAIL", status.userEmail);
            if (status.displayName != null) pb.environment().put("PLM_USER_NAME", status.displayName);
            // Load utility env vars
            if (isUtility) {
                loadEnvVars(reportId, pb.environment());
                // Inject user email into config files only if user opted in
                if (status.injectMyEmail) {
                    injectUserEmail(reportId, status.userEmail, status);
                }
                // Log env var keys (not values — they may be sensitive)
                File envFile = new File(UTILITIES_DIR + "/" + reportId + "/env.json");
                if (envFile.exists()) {
                    try {
                        Map<String, String> vars = mapper.readValue(envFile, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                        List<String> keys = new ArrayList<>(vars.keySet());
                        logger.info("[REPORTS] Env vars: " + keys.size() + " keys: " + String.join(", ", keys));
                        status.addLog("\u2699 Env: " + keys.size() + " vars loaded (" + String.join(", ", keys) + ")");
                    } catch (Exception ignored) {}
                }
            }
            // Log support files
            String supportFiles = report.get("supportFiles");
            if (supportFiles != null && !supportFiles.isEmpty()) {
                logger.info("[REPORTS] Support files: " + supportFiles);
                status.addLog("\u2699 Files: " + supportFiles);
            }
            logger.info("[REPORTS] ──────────────────────────────────────────");
            pb.redirectErrorStream(true);
            // Set working directory to the utility's folder so supporting files are accessible
            if (isUtility) {
                pb.directory(new File(script).getParentFile());
            } else {
                pb.directory(new File("."));
            }
            Process process = pb.start();

            // Capture output — stream to both log and ReportStatus.logs for live UI
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    status.addLog(line);
                    logger.info("[REPORTS] " + line);
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                status.status = "failed";
                status.error = "Report timed out after " + timeoutSeconds + " seconds";
                status.addLog("\u26A0 TIMEOUT after " + timeoutSeconds + "s — process killed");
                status.endTime = System.currentTimeMillis();
                logger.warning("[REPORTS] " + reportId + " timed out");
                activityLogger.log("system", status.displayName != null ? status.displayName : "system",
                    "REPORT_FAILED", reportId + " timed out after " + timeoutSeconds + "s");
                saveStatuses();
                return;
            }

            int exitCode = process.exitValue();
            status.endTime = System.currentTimeMillis();

            if (exitCode == 0) {
                // Find the output file
                String outputPattern = report.getOrDefault("outputPattern", "*.xlsx");
                String prefix = outputPattern.replace("*", "").replace(".xlsx", "");
                File outDir = new File(outputDir);
                final long runStart = status.startTime;

                if (!isUtility && !prefix.isEmpty()) {
                    // Non-utility: match by prefix pattern
                    File[] files = outDir.listFiles((dir, name) -> name.contains(prefix) && name.endsWith(".xlsx"));
                    if (files != null && files.length > 0) {
                        File newest = files[0];
                        for (File f : files) {
                            if (f.lastModified() > newest.lastModified()) newest = f;
                        }
                        status.outputFile = newest.getAbsolutePath();
                    }
                }

                // For utilities (or if no match yet), find any xlsx created AFTER the run started
                if (status.outputFile == null) {
                    // Check output dir
                    File[] files = outDir.listFiles((dir, name) -> name.endsWith(".xlsx"));
                    if (files != null) {
                        File newest = null;
                        for (File f : files) {
                            if (f.lastModified() >= runStart && (newest == null || f.lastModified() > newest.lastModified())) {
                                newest = f;
                            }
                        }
                        if (newest != null) status.outputFile = newest.getAbsolutePath();
                    }
                }
                // Also check the working directory for output files created during this run
                if (status.outputFile == null) {
                    File[] rootFiles = new File(".").listFiles((dir, name) -> name.endsWith(".xlsx"));
                    if (rootFiles != null) {
                        File newest = null;
                        for (File f : rootFiles) {
                            if (f.lastModified() >= runStart && (newest == null || f.lastModified() > newest.lastModified())) {
                                newest = f;
                            }
                        }
                        if (newest != null) {
                            // Move to output dir
                            File dest = new File(outDir, newest.getName());
                            newest.renameTo(dest);
                            status.outputFile = dest.getAbsolutePath();
                        }
                    }
                }

                status.status = "completed";
                status.addLog("\u2713 Completed successfully");
                long elapsed = (status.endTime - status.startTime) / 1000;
                logger.info("[REPORTS] " + reportId + " completed in " + elapsed + "s. Output: " + status.outputFile);
                activityLogger.log("system", status.displayName != null ? status.displayName : "system",
                    "REPORT_COMPLETED", reportId + " completed in " + elapsed + "s" +
                    (status.outputFile != null ? " · output: " + new File(status.outputFile).getName() : ""));
                saveStatuses();

                // Email AI-analyzed run summary to user if they requested it
                if (status.emailMeOnComplete && status.userEmail != null) {
                    sendUtilityRunEmail(report, status, true);
                }
            } else {
                status.status = "failed";
                String fullOutput = output.toString();
                String tail = fullOutput.length() > 2000 ? fullOutput.substring(fullOutput.length() - 2000) : fullOutput;
                status.error = "Script exited with code " + exitCode + ". Output (tail): " + tail;
                status.addLog("\u2717 FAILED — exit code " + exitCode);
                logger.warning("[REPORTS] " + reportId + " failed with exit code " + exitCode);
                long failElapsed = (status.endTime - status.startTime) / 1000;
                activityLogger.log("system", status.displayName != null ? status.displayName : "system",
                    "REPORT_FAILED", reportId + " failed (exit " + exitCode + ") after " + failElapsed + "s");
                saveStatuses();
                // Email failure report
                if (status.emailMeOnComplete && status.userEmail != null) {
                    sendUtilityRunEmail(report, status, false);
                }
            }

        } catch (Exception e) {
            status.status = "failed";
            status.error = e.getMessage();
            status.addLog("\u2717 ERROR: " + e.getMessage());
            status.endTime = System.currentTimeMillis();
            logger.log(Level.WARNING, "[REPORTS] " + reportId + " execution error", e);
            activityLogger.log("system", status.displayName != null ? status.displayName : "system",
                "REPORT_FAILED", reportId + " error: " + e.getMessage());
            saveStatuses();
            // Email error report
            if (status.emailMeOnComplete && status.userEmail != null) {
                sendUtilityRunEmail(report, status, false);
            }
        }
        // Always restore patched config files so next user gets a clean state
        if (isUtility) restoreConfigBackups(reportId);
    }

    // =========================================================================
    // Utilities — upload / delete / persist
    // =========================================================================

    // =========================================================================
    // Utility Run Email — AI-analyzed summary
    // =========================================================================

    @Autowired
    private EmailTemplateService emailTemplateService;

    private void sendUtilityRunEmail(Map<String, String> report, ReportStatus status, boolean success) {
        try {
            String reportName = report.getOrDefault("name", "Utility");
            long elapsed = (status.endTime - status.startTime) / 1000;
            String firstName = status.displayName != null && status.displayName.contains(",")
                ? status.displayName.split(",")[0].trim()
                : (status.displayName != null ? status.displayName.split(" ")[0] : "");

            // Build log excerpt for AI
            List<String> logLines = new ArrayList<>(status.logs);
            String logExcerpt = "";
            for (String line : logLines) logExcerpt += line + "\n";

            // Get AI insights
            String aiInsights = getAiRunAnalysis(logExcerpt, reportName, success);

            // Build KPI tiles
            String kpis = emailTemplateService.kpiRow(
                emailTemplateService.kpiTile("Status", success ? "Success" : "Failed", elapsed + "s runtime"),
                emailTemplateService.kpiTile("Log lines", String.valueOf(logLines.size()), success ? "clean run" : "check errors"),
                emailTemplateService.kpiTile("Output", status.outputFile != null ? "Ready" : "None", status.outputFile != null ? new File(status.outputFile).getName() : "no file generated")
            );

            // Build body
            StringBuilder body = new StringBuilder();

            // AI callout
            if (aiInsights != null && !aiInsights.isEmpty()) {
                body.append(emailTemplateService.callout("AI run analysis", aiInsights, success ? null : "bad"));
            }

            // Log preview
            int previewLines = Math.min(logLines.size(), 15);
            if (previewLines > 0) {
                String[] headers = {"#", "Output"};
                String[][] rows = new String[previewLines][];
                for (int i = 0; i < previewLines; i++) {
                    rows[i] = new String[]{String.valueOf(i + 1), logLines.get(logLines.size() - previewLines + i)};
                }
                body.append(emailTemplateService.previewTable(headers, rows,
                    logLines.size() > previewLines ? "+ " + (logLines.size() - previewLines) + " more lines in full log" : null));
            }

            // Attachment strip if there's an output file
            String attachHtml = null;
            if (status.outputFile != null) {
                File outFile = new File(status.outputFile);
                if (outFile.exists()) {
                    attachHtml = emailTemplateService.attachmentStrip(outFile.getName(),
                        (outFile.length() / 1024) + " KB");
                }
            }

            String heroTitle = success
                ? reportName + " completed in " + elapsed + "s."
                : reportName + " failed.";

            String html = emailTemplateService.wrap(
                "Utilities",
                success ? "Completed" : "Failed",
                success ? "Run completed" : "Run failed",
                heroTitle,
                "Hi " + firstName + " \u2014 here\u2019s what happened.",
                kpis,
                body.toString(),
                "Open in PLM Toolkit",
                "http://uls-ep-aglipccb:8090/index.html",
                null,
                attachHtml,
                null,
                null
            );

            // Send via SMTP
            java.util.Properties props = new java.util.Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            javax.mail.Session session = javax.mail.Session.getInstance(props);
            javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(session);
            message.setFrom(new javax.mail.internet.InternetAddress(fromAddress));
            message.setRecipient(javax.mail.Message.RecipientType.TO,
                new javax.mail.internet.InternetAddress(status.userEmail));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag((success ? "\u2713 " : "\u2717 ") + reportName + " \u00b7 " + elapsed + "s \u00b7 " +
                (success ? "completed" : "failed")));

            // If there's an output file, attach it
            if (status.outputFile != null && new File(status.outputFile).exists()) {
                javax.mail.internet.MimeMultipart multipart = new javax.mail.internet.MimeMultipart();
                // HTML body
                javax.mail.internet.MimeBodyPart htmlPart = new javax.mail.internet.MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=utf-8");
                multipart.addBodyPart(htmlPart);
                // File attachment
                javax.mail.internet.MimeBodyPart filePart = new javax.mail.internet.MimeBodyPart();
                filePart.attachFile(new File(status.outputFile));
                multipart.addBodyPart(filePart);
                message.setContent(multipart);
            } else {
                message.setContent(html, "text/html; charset=utf-8");
            }

            javax.mail.Transport.send(message);
            status.addLog("\u2709 Run summary emailed to " + status.userEmail);
            logger.info("[REPORTS] Run summary emailed to " + status.userEmail);

        } catch (Exception e) {
            status.addLog("\u26A0 Failed to email run summary: " + e.getMessage());
            logger.warning("[REPORTS] Failed to email run summary: " + e.getMessage());
        }
    }

    private String getAiRunAnalysis(String logOutput, String utilityName, boolean success) {
        if (!aiEnabled || aiApiKey == null || aiApiKey.isEmpty()) {
            // Fallback: simple summary without AI
            if (success) return "<p style='font-size:13px;color:#2a2f37;line-height:1.7;margin:0;'>The script ran successfully and completed without errors.</p>";
            return "<p style='font-size:13px;color:#2a2f37;line-height:1.7;margin:0;'>The script failed. Check the log output below for error details.</p>";
        }

        try {
            String excerpt = logOutput.length() > 3000 ? logOutput.substring(logOutput.length() - 3000) : logOutput;

            String systemPrompt = "You are analyzing the output log of a utility script called '" + escJson(utilityName) + "' " +
                "that ran in PLM Toolkit. The run " + (success ? "succeeded" : "FAILED") + ". " +
                "Write a brief summary (3-5 sentences) for the admin who ran it. Be warm but concise. " +
                "Highlight: what worked, what failed, any specific error messages, and suggested next steps if it failed. " +
                "Return ONLY an HTML paragraph (<p> tag), no markdown.";

            String responseStr = callClaude(systemPrompt, "Log output:\n" + excerpt, 300, 30000);
            if (responseStr == null) return null;
            return responseStr.replace("\n", "<br>");

        } catch (Exception e) {
            logger.warning("[REPORTS] AI run analysis failed: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // AI Script Analysis (Phase 2)
    // =========================================================================

    /**
     * Sends script content to Claude Haiku for analysis. Returns a structured map with:
     * - suggestedName: a short name for the script
     * - suggestedDescription: what the script does
     * - envVars: list of {key, defaultValue, description} maps
     * - dependencies: list of required packages
     * - cliArgs: suggested CLI arguments string
     * - warnings: any concerns about the script
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeScript(String scriptContent, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Truncate very large scripts to ~8000 chars for the AI
        String excerpt = scriptContent.length() > 8000
            ? scriptContent.substring(0, 4000) + "\n\n... [truncated] ...\n\n" + scriptContent.substring(scriptContent.length() - 4000)
            : scriptContent;

        if (!aiEnabled || aiApiKey == null || aiApiKey.isEmpty()) {
            // Fallback: basic regex-based analysis
            result.putAll(analyzeScriptFallback(scriptContent, filename));
            return result;
        }

        try {
            String systemPrompt = "You are analyzing a script that will be uploaded to an internal engineering tool called PLM Toolkit. " +
                "The script will be run via ProcessBuilder on a Windows server. " +
                "Analyze the script and return ONLY a JSON object (no markdown, no code fences) with these fields:\n\n" +
                "{\n" +
                "  \"suggestedName\": \"short human-friendly name (3-5 words)\",\n" +
                "  \"suggestedDescription\": \"one sentence describing what the script does\",\n" +
                "  \"envVars\": [\n" +
                "    {\"key\": \"ENV_VAR_NAME\", \"defaultValue\": \"default or empty string\", \"description\": \"what this var controls\", \"sensitive\": true/false}\n" +
                "  ],\n" +
                "  \"dependencies\": [\"package1\", \"package2\"],\n" +
                "  \"cliArgs\": \"suggested CLI args for typical use, or empty string\",\n" +
                "  \"warnings\": [\"any concerns about running this script\"],\n" +
                "  \"fileArgs\": [{\"name\": \"arg_name\", \"label\": \"human description\", \"required\": true}],\n" +
                "  \"configProperties\": [{\"key\": \"prop.key\", \"defaultValue\": \"default\", \"sensitive\": false}]\n" +
                "}\n\n" +
                "IMPORTANT: Separate env vars from config properties:\n" +
                "- envVars: for os.environ, os.getenv, System.getenv — real environment variables\n" +
                "- configProperties: for .getProperty(), Properties file keys — these go into a .properties file, NOT env vars\n" +
                "Do NOT put Java Properties keys into envVars. They are different.\n\n" +
                "Look for: os.environ, os.getenv, System.getenv, argparse, @Value annotations, import statements for external packages, " +
                "config file references, DB connection strings, file paths, SMTP settings. " +
                "For fileArgs: detect arguments that expect file paths (properties files, input data files, config files). " +
                "Look for args[0], args[1], argparse file arguments, Properties.load(FileInputStream), etc. " +
                "For sensitive vars like passwords, API keys, tokens — set sensitive: true. " +
                "Return ONLY the JSON object, nothing else.";

            String jsonText = callClaude(systemPrompt, "Filename: " + filename + "\n\nScript content:\n" + excerpt, 1000, 45000);
            if (jsonText == null) {
                result.putAll(analyzeScriptFallback(scriptContent, filename));
                return result;
            }
            jsonText = jsonText.replace("\\/", "/");

            // Strip markdown code fences if AI wrapped the JSON in ```json ... ```
            jsonText = jsonText.trim();
            if (jsonText.startsWith("```")) {
                int firstNewline = jsonText.indexOf("\n");
                if (firstNewline > 0) jsonText = jsonText.substring(firstNewline + 1);
                if (jsonText.endsWith("```")) jsonText = jsonText.substring(0, jsonText.length() - 3);
                jsonText = jsonText.trim();
            }

            // Parse the JSON from the AI response
            Map<String, Object> aiResult = mapper.readValue(jsonText, new TypeReference<Map<String, Object>>() {});
            result.put("aiAnalyzed", true);
            result.putAll(aiResult);

            logger.info("[REPORTS] AI script analysis complete for " + filename +
                ": " + ((List<?>) aiResult.getOrDefault("envVars", Collections.emptyList())).size() + " env vars detected");

        } catch (Exception e) {
            logger.warning("[REPORTS] AI analysis failed for " + filename + ": " + e.getMessage());
            result.putAll(analyzeScriptFallback(scriptContent, filename));
        }

        return result;
    }

    /**
     * Fallback regex-based analysis when AI is unavailable.
     * Handles both Python and Java patterns.
     */
    /**
     * Shared helper: call Claude (via Portkey or direct) and return the text content.
     * Returns null on any failure.
     */
    private String callClaude(String systemPrompt, String userMessage, int maxTokens, int readTimeoutMs) {
        try {
            String model = portkeyProvider + "/" + portkeyModel;
            return portkeyClient.chat(model, systemPrompt, userMessage, maxTokens);
        } catch (Exception e) {
            logger.warning("[REPORTS] AI call failed: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> analyzeScriptFallback(String content, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiAnalyzed", false);
        boolean isJava = filename.endsWith(".java");

        // Suggest name from filename
        String name = filename.replace(".py", "").replace(".java", "").replace("_", " ").replace("-", " ");
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.length() > 0) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        result.put("suggestedName", sb.toString().trim());

        // Try to extract description from docstring/javadoc
        String desc = "Uploaded script: " + filename;
        if (isJava) {
            // Javadoc: /** ... */
            java.util.regex.Matcher jdoc = java.util.regex.Pattern.compile("/\\*\\*\\s*\\n\\s*\\*\\s*(.+?)\\n", java.util.regex.Pattern.DOTALL).matcher(content);
            if (jdoc.find()) desc = jdoc.group(1).replaceAll("\\s*\\*\\s*", " ").trim();
        } else {
            // Python docstring: """...""" or '''...'''
            java.util.regex.Matcher pdoc = java.util.regex.Pattern.compile("(?:\"\"\"|\\'\\'\\')\n?(.+?)(?:\n|\\.)").matcher(content);
            if (pdoc.find()) desc = pdoc.group(1).trim();
        }
        result.put("suggestedDescription", desc);

        // ---- Detect environment variables vs properties ----
        List<Map<String, Object>> envVars = new ArrayList<>();
        List<Map<String, Object>> configProperties = new ArrayList<>(); // Java .properties file keys
        Set<String> seen = new HashSet<>();
        Set<String> propsSeen = new HashSet<>();
        java.util.regex.Matcher m;

        // Python: os.environ.get("KEY", "default"), os.getenv("KEY", "default"), os.environ["KEY"]
        java.util.regex.Pattern pyEnv = java.util.regex.Pattern.compile(
            "os\\.environ\\.get\\([\"']([A-Za-z_][A-Za-z0-9_]*)[\"'](?:\\s*,\\s*[\"']([^\"']*)[\"'])?\\)|" +
            "os\\.getenv\\([\"']([A-Za-z_][A-Za-z0-9_]*)[\"'](?:\\s*,\\s*[\"']([^\"']*)[\"'])?\\)|" +
            "os\\.environ\\[[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\]");
        m = pyEnv.matcher(content);
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : (m.group(3) != null ? m.group(3) : m.group(5));
            String def = m.group(2) != null ? m.group(2) : (m.group(4) != null ? m.group(4) : "");
            addDetectedEnvVar(envVars, seen, key, def);
        }

        // Java: System.getenv("KEY") — real env vars
        m = java.util.regex.Pattern.compile("System\\.getenv\\([\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\)").matcher(content);
        while (m.find()) addDetectedEnvVar(envVars, seen, m.group(1), "");

        // Java: @Value("${property.name}") — Spring properties (env vars)
        m = java.util.regex.Pattern.compile("@Value\\([\"']\\$\\{([A-Za-z_.\\-]+)(?::([^}]*))?\\}[\"']\\)").matcher(content);
        while (m.find()) addDetectedEnvVar(envVars, seen, m.group(1), m.group(2) != null ? m.group(2) : "");

        // Java: Properties.getProperty("key", "default") — goes into configProperties, NOT envVars
        m = java.util.regex.Pattern.compile("\\.getProperty\\([\"']([A-Za-z_.\\-][A-Za-z0-9_.\\-]*)[\"'](?:\\s*,\\s*[\"']([^\"']*)[\"'])?\\)").matcher(content);
        while (m.find()) {
            String key = m.group(1);
            String def = m.group(2) != null ? m.group(2) : "";
            if (!propsSeen.contains(key)) {
                propsSeen.add(key);
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("key", key);
                prop.put("defaultValue", def);
                String upper = key.toUpperCase();
                prop.put("sensitive", upper.contains("PASSWORD") || upper.contains("SECRET") ||
                    upper.contains("API_KEY") || upper.contains("TOKEN") || upper.contains("CREDENTIAL"));
                configProperties.add(prop);
            }
        }

        // JDBC connection strings
        m = java.util.regex.Pattern.compile("getConnection\\([\"'](jdbc:[^\"']+)[\"']").matcher(content);
        if (m.find() && !seen.contains("JDBC_URL") && !propsSeen.contains("JDBC_URL")) {
            addDetectedEnvVar(envVars, seen, "JDBC_URL", m.group(1));
        }

        result.put("envVars", envVars);
        if (!configProperties.isEmpty()) {
            result.put("configProperties", configProperties);
        }

        // ---- Detect dependencies ----
        List<String> deps = new ArrayList<>();
        Set<String> depsSeen = new HashSet<>();

        if (isJava) {
            // Java imports — filter out java.* and javax.*
            m = java.util.regex.Pattern.compile("^\\s*import\\s+((?!java\\.|javax\\.)[a-zA-Z][a-zA-Z0-9_.]+)", java.util.regex.Pattern.MULTILINE).matcher(content);
            while (m.find()) {
                String imp = m.group(1);
                // Extract the top-level package (e.g., "com.oracle" from "com.oracle.jdbc.Driver")
                String[] parts = imp.split("\\.");
                String pkg;
                if (parts.length >= 2) {
                    pkg = parts[0] + "." + parts[1]; // e.g., "com.oracle", "org.apache"
                } else {
                    pkg = parts[0];
                }
                if (!depsSeen.contains(pkg)) {
                    depsSeen.add(pkg);
                    // Map common packages to friendly names
                    String friendly = mapJavaPackage(pkg, imp);
                    deps.add(friendly);
                }
            }
        } else {
            // Python imports
            m = java.util.regex.Pattern.compile("^\\s*(?:import|from)\\s+(\\w+)", java.util.regex.Pattern.MULTILINE).matcher(content);
            Set<String> stdlibs = new HashSet<>(Arrays.asList("os", "sys", "re", "json", "csv", "io", "math", "time",
                "datetime", "pathlib", "logging", "argparse", "smtplib", "email", "collections", "functools",
                "itertools", "subprocess", "shutil", "tempfile", "hashlib", "base64", "struct", "textwrap",
                "string", "typing", "abc", "copy", "pprint", "unittest", "socket", "http", "urllib",
                "threading", "multiprocessing", "queue", "signal", "contextlib", "dataclasses",
                "enum", "decimal", "fractions", "random", "statistics", "glob", "fnmatch", "linecache",
                "pickle", "shelve", "sqlite3", "zipfile", "gzip", "bz2", "lzma", "tarfile", "configparser",
                "xml", "html", "webbrowser", "cgi", "wsgiref", "xmlrpc", "ftplib", "poplib", "imaplib",
                "nntplib", "mailbox", "mimetypes", "encodings", "codecs", "unicodedata", "locale",
                "gettext", "operator", "inspect", "dis", "traceback", "warnings", "atexit",
                "builtins", "types", "importlib", "pkgutil", "platform", "errno", "ctypes",
                "__future__", "concurrent"));
            while (m.find()) {
                String pkg = m.group(1);
                if (!stdlibs.contains(pkg) && !depsSeen.contains(pkg)) {
                    depsSeen.add(pkg);
                    deps.add(pkg);
                }
            }
        }
        result.put("dependencies", deps);

        // ---- Detect CLI args ----
        List<String> flags = new ArrayList<>();
        if (content.contains("argparse")) {
            // Python argparse
            m = java.util.regex.Pattern.compile("add_argument\\([\"'](--[a-zA-Z][a-zA-Z0-9-]*)[\"']").matcher(content);
            while (m.find()) flags.add(m.group(1));
        }
        if (content.contains("args4j") || content.contains("@Option") || content.contains("picocli")) {
            // Java args4j / picocli
            m = java.util.regex.Pattern.compile("name\\s*=\\s*[\"'](--?[a-zA-Z][a-zA-Z0-9-]*)[\"']").matcher(content);
            while (m.find()) flags.add(m.group(1));
        }
        // Java: simple args[] parsing — if (args[i].equals("--flag"))
        m = java.util.regex.Pattern.compile("args\\[\\w+\\]\\.equals\\([\"'](--?[a-zA-Z][a-zA-Z0-9-]*)[\"']\\)").matcher(content);
        while (m.find()) flags.add(m.group(1));

        result.put("cliArgs", "");
        if (!flags.isEmpty()) result.put("detectedFlags", flags);

        // ---- Warnings ----
        List<String> warnings = new ArrayList<>();
        if (content.contains("Runtime.getRuntime().exec")) warnings.add("Script uses Runtime.exec() — may spawn external processes");
        if (content.contains("ProcessBuilder")) warnings.add("Script uses ProcessBuilder — spawns child processes");
        if (content.contains("deleteOnExit") || content.contains("File.delete") || content.contains("os.remove") || content.contains("shutil.rmtree"))
            warnings.add("Script deletes files — verify paths before running");
        if (content.contains("DROP TABLE") || content.contains("TRUNCATE") || content.contains("DELETE FROM"))
            warnings.add("Script contains destructive SQL (DROP/TRUNCATE/DELETE)");
        if (isJava && !content.contains("class ")) warnings.add("No class definition found — may not compile standalone");
        result.put("warnings", warnings);

        return result;
    }

    private void addDetectedEnvVar(List<Map<String, Object>> envVars, Set<String> seen, String key, String defaultValue) {
        if (key == null || seen.contains(key)) return;
        seen.add(key);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("key", key);
        ev.put("defaultValue", defaultValue != null ? defaultValue : "");
        ev.put("description", "");
        String upper = key.toUpperCase();
        ev.put("sensitive", upper.contains("PASSWORD") || upper.contains("SECRET") || upper.contains("API_KEY") ||
            upper.contains("TOKEN") || upper.contains("CREDENTIAL") || upper.contains("PRIVATE"));
        envVars.add(ev);
    }

    private String mapJavaPackage(String pkg, String fullImport) {
        if (fullImport.startsWith("com.oracle") || fullImport.contains("oracle.jdbc")) return "Oracle JDBC (ojdbc)";
        if (fullImport.startsWith("org.apache.poi")) return "Apache POI (Excel)";
        if (fullImport.startsWith("org.apache.http")) return "Apache HttpClient";
        if (fullImport.startsWith("org.apache.commons")) return "Apache Commons";
        if (fullImport.startsWith("com.google.gson")) return "Gson";
        if (fullImport.startsWith("com.fasterxml.jackson")) return "Jackson JSON";
        if (fullImport.startsWith("org.springframework")) return "Spring Framework";
        if (fullImport.startsWith("org.slf4j") || fullImport.startsWith("ch.qos.logback")) return "SLF4J/Logback";
        if (fullImport.startsWith("com.mysql")) return "MySQL Connector";
        if (fullImport.startsWith("org.postgresql")) return "PostgreSQL JDBC";
        if (fullImport.startsWith("com.microsoft.sqlserver")) return "SQL Server JDBC";
        if (fullImport.startsWith("org.junit") || fullImport.startsWith("junit")) return "JUnit";
        if (fullImport.startsWith("org.mockito")) return "Mockito";
        if (fullImport.startsWith("com.opencsv")) return "OpenCSV";
        if (fullImport.startsWith("org.jsoup")) return "Jsoup (HTML parser)";
        if (fullImport.contains("agile.px") || fullImport.contains("agile.api")) return "Agile SDK";
        return pkg;
    }

    // =========================================================================
    // JAR Analysis — extract .java source files and analyze them
    // =========================================================================

    /**
     * Analyzes a .jar file. If .java source files are found inside, extracts and
     * analyzes them. If only .class files exist, returns manifest info + guided prompts.
     */
    public Map<String, Object> analyzeJar(byte[] jarBytes, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Write to temp file so JarFile can read it
            File tempJar = File.createTempFile("util-analyze-", ".jar");
            tempJar.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tempJar)) {
                fos.write(jarBytes);
            }

            java.util.jar.JarFile jar = new java.util.jar.JarFile(tempJar);

            // Read manifest
            java.util.jar.Manifest manifest = jar.getManifest();
            String mainClass = null;
            if (manifest != null && manifest.getMainAttributes() != null) {
                mainClass = manifest.getMainAttributes().getValue("Main-Class");
            }

            // Collect .java and .properties files
            StringBuilder javaSource = new StringBuilder();
            List<String> javaFiles = new ArrayList<>();
            List<String> propertiesFiles = new ArrayList<>();
            Map<String, String> javaSourceByFile = new LinkedHashMap<>();
            List<String> topLevelClassNames = new ArrayList<>(); // FQCNs of .class files (excluding inner classes)
            int classFileCount = 0;

            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.endsWith(".java")) {
                    javaFiles.add(entryName);
                    StringBuilder fileSrc = new StringBuilder();
                    try (InputStream is = jar.getInputStream(entry);
                         BufferedReader br = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            fileSrc.append(line).append("\n");
                        }
                    }
                    javaSourceByFile.put(entryName, fileSrc.toString());
                    javaSource.append("// === ").append(entryName).append(" ===\n");
                    javaSource.append(fileSrc).append("\n");
                } else if (entryName.endsWith(".properties")) {
                    propertiesFiles.add(entryName);
                } else if (entryName.endsWith(".class")) {
                    classFileCount++;
                    if (!entryName.contains("$")) {
                        // Top-level class — store FQCN
                        topLevelClassNames.add(entryName.replace("/", ".").replace(".class", ""));
                    }
                }
            }
            jar.close();
            tempJar.delete();

            // Detect all entry points (classes with public static void main)
            List<Map<String, String>> entryPoints = new ArrayList<>();
            Set<String> entryPointClasses = new HashSet<>();

            // 1. Scan .java source files for main() methods
            for (Map.Entry<String, String> srcEntry : javaSourceByFile.entrySet()) {
                String srcFile = srcEntry.getKey();
                String src = srcEntry.getValue();
                if (src.contains("public static void main")) {
                    Map<String, String> ep = new LinkedHashMap<>();
                    String className = srcFile.replace("/", ".").replace(".java", "");
                    ep.put("className", className);
                    ep.put("file", srcFile);
                    ep.put("source", "java");
                    java.util.regex.Matcher descM = java.util.regex.Pattern.compile(
                        "(?:/\\*\\*\\s*\\n\\s*\\*\\s*(.+?)\\n|//\\s*(.+?)\\n)").matcher(src);
                    String classDesc = descM.find() ? (descM.group(1) != null ? descM.group(1).trim() : descM.group(2).trim()) : "";
                    ep.put("description", classDesc);
                    boolean needsConfig = src.contains("Properties") && (src.contains("args[") || src.contains("args.length"));
                    ep.put("needsConfig", String.valueOf(needsConfig));
                    entryPoints.add(ep);
                    entryPointClasses.add(className);
                }
            }

            // 2. Find .class files that have NO matching .java source —
            //    these are potential entry points we can't verify from source
            Set<String> sourceClassNames = new HashSet<>();
            for (String jf : javaFiles) {
                sourceClassNames.add(jf.replace("/", ".").replace(".java", ""));
            }
            for (String fqcn : topLevelClassNames) {
                if (!sourceClassNames.contains(fqcn) && !entryPointClasses.contains(fqcn)) {
                    Map<String, String> ep = new LinkedHashMap<>();
                    ep.put("className", fqcn);
                    ep.put("file", fqcn.replace(".", "/") + ".class");
                    ep.put("source", "class-only");
                    ep.put("description", "No source available \u2014 bytecode only");
                    ep.put("needsConfig", "unknown");
                    entryPoints.add(ep);
                    entryPointClasses.add(fqcn);
                }
            }

            // JAR metadata
            result.put("jarInfo", true);
            result.put("mainClass", mainClass != null ? mainClass : "(not specified in manifest)");
            result.put("javaFileCount", javaFiles.size());
            result.put("classFileCount", classFileCount);
            result.put("propertiesFiles", propertiesFiles);
            result.put("javaFiles", javaFiles);
            result.put("allClasses", topLevelClassNames);

            if (javaSource.length() > 0) {
                // We have source — run the full analysis on it
                logger.info("[REPORTS] JAR " + filename + " contains " + javaFiles.size() +
                    " .java files (" + javaSource.length() + " chars), " +
                    entryPoints.size() + " entry point(s). Running analysis.");
                Map<String, Object> analysis = analyzeScript(javaSource.toString(), filename);
                result.putAll(analysis);

                // Entry points
                result.put("entryPoints", entryPoints);
                if (entryPoints.size() > 1) {
                    result.put("multipleEntryPoints", true);
                }

                // If main class from manifest, use it; otherwise suggest first entry point
                if (mainClass != null) {
                    result.put("suggestedDescription",
                        result.getOrDefault("suggestedDescription", "") +
                        " (Main-Class: " + mainClass + ")");
                    result.put("runtimeCommand", "java -jar " + filename);
                } else if (!entryPoints.isEmpty()) {
                    String firstClass = entryPoints.get(0).get("className");
                    result.put("runtimeCommand", "java -cp " + filename + " " + firstClass);
                } else {
                    result.put("runtimeCommand", "java -jar " + filename);
                }
            } else {
                // No source files — bytecode only
                logger.info("[REPORTS] JAR " + filename + " has " + classFileCount +
                    " .class files but no .java source. Limited analysis.");
                result.put("aiAnalyzed", false);
                result.put("suggestedName", filename.replace(".jar", "").replace("-", " ").replace("_", " "));
                result.put("suggestedDescription", "Compiled JAR" +
                    (mainClass != null ? " — Main-Class: " + mainClass : " — no Main-Class in manifest"));
                result.put("envVars", new ArrayList<>());
                result.put("dependencies", new ArrayList<>());
                result.put("cliArgs", mainClass != null ? "" : "<properties_file>");

                List<String> warnings = new ArrayList<>();
                warnings.add("No .java source files found in JAR — cannot auto-detect env vars or dependencies. Please configure manually.");
                if (mainClass == null) {
                    warnings.add("No Main-Class in MANIFEST.MF — you may need to specify the class to run.");
                }
                if (!propertiesFiles.isEmpty()) {
                    warnings.add("Found properties file(s) inside JAR: " + String.join(", ", propertiesFiles) +
                        " — the script may expect an external config file path as a CLI argument.");
                }
                result.put("warnings", warnings);
                result.put("runtimeCommand", mainClass != null
                    ? "java -jar " + filename
                    : "java -cp " + filename + " <MainClass>");
            }

        } catch (Exception e) {
            logger.warning("[REPORTS] JAR analysis failed for " + filename + ": " + e.getMessage());
            result.put("aiAnalyzed", false);
            result.put("suggestedName", filename.replace(".jar", ""));
            result.put("suggestedDescription", "JAR file — analysis failed: " + e.getMessage());
            result.put("envVars", new ArrayList<>());
            result.put("dependencies", new ArrayList<>());
            result.put("warnings", Arrays.asList("JAR analysis failed: " + e.getMessage()));
        }

        return result;
    }

    // =========================================================================
    // Properties file validation — check for bad paths, platform mismatches
    // =========================================================================

    /**
     * Validates a .properties file content. Checks path-like values for existence
     * and platform mismatches (Linux paths on Windows, etc.).
     * Returns a list of warning maps: {key, value, issue, suggestion}
     */
    public List<Map<String, String>> validatePropertiesFile(String content) {
        List<Map<String, String>> warnings = new ArrayList<>();
        if (content == null || content.isEmpty()) return warnings;

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String serverCwd = new File(".").getAbsolutePath();

        Properties props = new Properties();
        try {
            props.load(new java.io.StringReader(content));
        } catch (Exception e) {
            Map<String, String> w = new LinkedHashMap<>();
            w.put("key", "(parse error)");
            w.put("value", "");
            w.put("issue", "Could not parse properties file: " + e.getMessage());
            w.put("suggestion", "Check the file format — each line should be key=value");
            warnings.add(w);
            return warnings;
        }

        for (String key : props.stringPropertyNames()) {
            String val = props.getProperty(key).trim();
            if (val.isEmpty()) continue;

            // Detect path-like values
            boolean isPath = false;
            if (val.startsWith("/") && !val.startsWith("//")) isPath = true;  // Unix absolute
            if (val.matches("[A-Za-z]:[/\\\\].*")) isPath = true;             // Windows absolute
            if (val.startsWith("./") || val.startsWith(".\\")) isPath = true; // Relative
            if (val.contains("/home/") || val.contains("/apps/") || val.contains("/var/") ||
                val.contains("/tmp/") || val.contains("/opt/") || val.contains("/usr/")) isPath = true;
            if (val.contains("\\Users\\") || val.contains("\\Program Files")) isPath = true;
            // Also catch path-like keys
            String keyLower = key.toLowerCase();
            if (keyLower.contains("path") || keyLower.contains("file") || keyLower.contains("dir") ||
                keyLower.contains("folder") || keyLower.contains("location") || keyLower.contains("output") ||
                keyLower.contains("input") || keyLower.contains("template") || keyLower.contains("log")) {
                if (val.contains("/") || val.contains("\\")) isPath = true;
            }

            if (!isPath) continue;

            // Check platform mismatch
            boolean isUnixPath = val.startsWith("/") || val.contains("/home/") || val.contains("/apps/");
            boolean isWinPath = val.matches("[A-Za-z]:[/\\\\].*") || val.contains("\\");

            if (isWindows && isUnixPath) {
                // Linux path on Windows server
                Map<String, String> w = new LinkedHashMap<>();
                w.put("key", key);
                w.put("value", val);
                w.put("issue", "Linux path detected but server is Windows");
                // Suggest a Windows equivalent
                String suggested = suggestWindowsPath(val, serverCwd);
                w.put("suggestion", suggested);
                warnings.add(w);
            } else if (!isWindows && isWinPath) {
                // Windows path on Linux server
                Map<String, String> w = new LinkedHashMap<>();
                w.put("key", key);
                w.put("value", val);
                w.put("issue", "Windows path detected but server is Linux");
                String suggested = val.replaceAll("[A-Za-z]:", "").replace("\\", "/");
                w.put("suggestion", suggested);
                warnings.add(w);
            }

            // Check if path exists on this server
            File f = new File(val);
            if (!f.exists()) {
                // Only warn if it's an absolute path (relative paths might be fine once we cd)
                boolean isAbsolute = val.startsWith("/") || val.matches("[A-Za-z]:[/\\\\].*");
                if (isAbsolute) {
                    // Check if we already have a platform mismatch warning for this key
                    boolean alreadyWarned = false;
                    for (Map<String, String> existing : warnings) {
                        if (key.equals(existing.get("key"))) { alreadyWarned = true; break; }
                    }
                    if (!alreadyWarned) {
                        Map<String, String> w = new LinkedHashMap<>();
                        w.put("key", key);
                        w.put("value", val);
                        w.put("issue", "Path does not exist on this server");
                        w.put("suggestion", suggestAlternativePath(val, serverCwd, isWindows));
                        warnings.add(w);
                    }
                }
            }
        }

        return warnings;
    }

    private String suggestWindowsPath(String linuxPath, String serverCwd) {
        // Common Linux → Windows path mappings
        if (linuxPath.startsWith("/apps/agile/")) {
            return "D:\\agile\\" + linuxPath.substring("/apps/agile/".length()).replace("/", "\\");
        }
        if (linuxPath.startsWith("/home/")) {
            return "C:\\Users\\" + linuxPath.substring("/home/".length()).replace("/", "\\");
        }
        if (linuxPath.startsWith("/tmp/")) {
            return "D:\\temp\\" + linuxPath.substring("/tmp/".length()).replace("/", "\\");
        }
        // Default: use server's working directory
        String filename = linuxPath.substring(linuxPath.lastIndexOf('/') + 1);
        return serverCwd.replace(File.separator + ".", "") + File.separator + filename;
    }

    private String suggestAlternativePath(String path, String serverCwd, boolean isWindows) {
        // Extract just the filename and suggest placing it in the working directory
        String sep = isWindows ? "\\" : "/";
        String filename = path;
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSep >= 0) filename = path.substring(lastSep + 1);
        return serverCwd.replace(File.separator + ".", "") + sep + filename;
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public synchronized Map<String, Object> addUtility(String name, String description,
                                                        String originalFilename, byte[] fileBytes,
                                                        String envVarsJson, String cliArgs,
                                                        String entryPoint, String ownerDisplayName, String ownerUsername,
                                                        org.springframework.web.multipart.MultipartFile[] supportFiles,
                                                        org.springframework.web.multipart.MultipartFile[] depJars) throws IOException {
        String id = "util-" + System.currentTimeMillis();
        File dir = new File(UTILITIES_DIR, id);
        dir.mkdirs();

        // Write the script file
        File scriptFile = new File(dir, originalFilename);
        try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
            fos.write(fileBytes);
        }

        // Write supporting files
        List<String> supportFileNames = new ArrayList<>();
        if (supportFiles != null) {
            for (org.springframework.web.multipart.MultipartFile sf : supportFiles) {
                if (sf != null && !sf.isEmpty() && sf.getOriginalFilename() != null) {
                    File dest = new File(dir, sf.getOriginalFilename());
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(sf.getBytes());
                    }
                    supportFileNames.add(sf.getOriginalFilename());
                    logger.info("[REPORTS] Stored supporting file: " + sf.getOriginalFilename());
                }
            }
        }

        // Write dependency JARs to a lib/ subdirectory
        File libDir = new File(dir, "lib");
        List<String> depJarNames = new ArrayList<>();
        if (depJars != null) {
            for (org.springframework.web.multipart.MultipartFile dj : depJars) {
                if (dj != null && !dj.isEmpty() && dj.getOriginalFilename() != null) {
                    libDir.mkdirs();
                    File dest = new File(libDir, dj.getOriginalFilename());
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(dj.getBytes());
                    }
                    depJarNames.add(dj.getOriginalFilename());
                    logger.info("[REPORTS] Stored dependency JAR: " + dj.getOriginalFilename());
                }
            }
        }

        // Write env vars
        File envFile = new File(dir, "env.json");
        mapper.writeValue(envFile, mapper.readValue(envVarsJson, new TypeReference<Map<String, String>>() {}));

        // Build definition
        Map<String, String> def = new LinkedHashMap<>();
        def.put("id", id);
        def.put("name", name);
        def.put("description", description);
        def.put("script", scriptFile.getAbsolutePath());
        def.put("type", "utility");
        def.put("filename", originalFilename);
        def.put("owner", ownerDisplayName != null && !ownerDisplayName.isEmpty() ? ownerDisplayName : "Unknown");
        def.put("ownerUsername", ownerUsername != null ? ownerUsername : "");
        def.put("estimatedTime", "~varies");
        if (cliArgs != null && !cliArgs.trim().isEmpty()) {
            def.put("cliArgs", cliArgs.trim());
        }
        if (entryPoint != null && !entryPoint.trim().isEmpty()) {
            def.put("entryPoint", entryPoint.trim());
        }
        if (!supportFileNames.isEmpty()) {
            def.put("supportFiles", String.join(",", supportFileNames));
        }
        if (!depJarNames.isEmpty()) {
            def.put("depJars", String.join(",", depJarNames));
        }
        reportDefinitions.add(def);
        saveReportDefinitions();

        logger.info("[REPORTS] Added utility '" + name + "' (id=" + id + ", file=" + originalFilename + ")");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        return result;
    }

    public void replaceSupportFiles(String reportId, org.springframework.web.multipart.MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) return;
        File dir = new File(UTILITIES_DIR, reportId);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Utility directory not found: " + reportId);
        }

        // Find the utility definition
        Map<String, String> utilDef = null;
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                utilDef = r;
                break;
            }
        }
        if (utilDef == null) {
            throw new IllegalArgumentException("Utility not found: " + reportId);
        }

        String mainFilename = utilDef.get("filename"); // e.g., "SandiskCCBDetails.jar"

        for (org.springframework.web.multipart.MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) continue;

            // Check if this is the main script/JAR being replaced
            boolean isMainScript = filename.equals(mainFilename) ||
                (filename.endsWith(".jar") && mainFilename != null && mainFilename.endsWith(".jar")) ||
                (filename.endsWith(".py") && mainFilename != null && mainFilename.endsWith(".py")) ||
                (filename.endsWith(".java") && mainFilename != null && mainFilename.endsWith(".java"));

            File dest;
            if (isMainScript) {
                // Replace the main script — might have a different name
                dest = new File(dir, mainFilename);
                logger.info("[REPORTS] Replacing main script '" + mainFilename + "' with '" + filename + "' for utility " + reportId);
            } else {
                dest = new File(dir, filename);
            }

            boolean isReplace = dest.exists();
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(file.getBytes());
            }
            if (!isMainScript) {
                logger.info("[REPORTS] " + (isReplace ? "Replaced" : "Added") + " supporting file '" +
                        filename + "' for utility " + reportId);
            }
        }

        // Update the supportFiles list in the definition if new files were added
        Set<String> allFiles = new LinkedHashSet<>();
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id"))) {
                String existing = r.get("supportFiles");
                if (existing != null && !existing.isEmpty()) {
                    for (String f : existing.split(",")) {
                        allFiles.add(f.trim());
                    }
                }
                for (org.springframework.web.multipart.MultipartFile file : files) {
                    if (file != null && !file.isEmpty() && file.getOriginalFilename() != null) {
                        allFiles.add(file.getOriginalFilename());
                    }
                }
                r.put("supportFiles", join(allFiles, ","));
                break;
            }
        }
        saveReportDefinitions();
    }

    public void replaceDepJars(String reportId, org.springframework.web.multipart.MultipartFile[] depJars) throws IOException {
        File libDir = new File(UTILITIES_DIR + "/" + reportId + "/lib");
        libDir.mkdirs();

        // Build the new dep jars list — uploaded files replace existing ones with matching names
        Set<String> newJarNames = new LinkedHashSet<>();
        for (org.springframework.web.multipart.MultipartFile dj : depJars) {
            if (dj == null || dj.isEmpty()) continue;
            String filename = dj.getOriginalFilename();
            if (filename == null) continue;
            newJarNames.add(filename);
            File dest = new File(libDir, filename);
            boolean existed = dest.exists();
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(dj.getBytes());
            }
            logger.info("[REPORTS] " + (existed ? "Replaced" : "Added") + " dep JAR '" + filename + "' for utility " + reportId);
        }

        // Update depJars in definition: keep existing ones that weren't replaced, add new ones
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id"))) {
                Set<String> finalJars = new LinkedHashSet<>();
                String existing = r.get("depJars");
                if (existing != null) {
                    for (String j : existing.split(",")) {
                        String jt = j.trim();
                        if (!jt.isEmpty()) finalJars.add(jt);
                    }
                }
                finalJars.addAll(newJarNames);
                r.put("depJars", join(finalJars, ","));
                break;
            }
        }
        saveReportDefinitions();
    }

    public synchronized void removeSupportFile(String reportId, String fileName) throws IOException {
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                String existing = r.get("supportFiles");
                if (existing == null) return;
                Set<String> files = new LinkedHashSet<>();
                for (String f : existing.split(",")) {
                    String ft = f.trim();
                    if (!ft.isEmpty() && !ft.equals(fileName)) files.add(ft);
                }
                if (files.isEmpty()) r.remove("supportFiles");
                else r.put("supportFiles", join(files, ","));
                saveReportDefinitions();
                // Delete the file from disk
                File file = new File(UTILITIES_DIR + "/" + reportId + "/" + fileName);
                if (file.exists()) {
                    file.delete();
                    logger.info("[REPORTS] Removed support file '" + fileName + "' for utility " + reportId);
                }
                return;
            }
        }
    }

    public synchronized void removeDepJar(String reportId, String jarName) throws IOException {
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                String existing = r.get("depJars");
                if (existing == null) return;
                Set<String> jars = new LinkedHashSet<>();
                for (String j : existing.split(",")) {
                    String jt = j.trim();
                    if (!jt.isEmpty() && !jt.equals(jarName)) jars.add(jt);
                }
                r.put("depJars", join(jars, ","));
                saveReportDefinitions();
                // Delete the file from lib/
                File jarFile = new File(UTILITIES_DIR + "/" + reportId + "/lib/" + jarName);
                if (jarFile.exists()) {
                    jarFile.delete();
                    logger.info("[REPORTS] Removed dep JAR '" + jarName + "' for utility " + reportId);
                }
                return;
            }
        }
    }

    private String join(Set<String> set, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(delimiter);
            sb.append(s);
        }
        return sb.toString();
    }

    public synchronized void updateUtilityField(String reportId, String field, String value) throws IOException {
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                if (value == null || value.isEmpty()) {
                    r.remove(field);
                } else {
                    r.put(field, value);
                }
                saveReportDefinitions();
                logger.info("[REPORTS] Updated " + field + " for utility " + reportId);
                return;
            }
        }
        throw new IllegalArgumentException("Utility not found: " + reportId);
    }

    public String getUtilityOwner(String reportId) {
        for (Map<String, String> r : reportDefinitions) {
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                return r.get("ownerUsername");
            }
        }
        return null;
    }

    public synchronized void deleteUtility(String reportId) throws IOException {
        Iterator<Map<String, String>> it = reportDefinitions.iterator();
        boolean found = false;
        while (it.hasNext()) {
            Map<String, String> r = it.next();
            if (reportId.equals(r.get("id")) && "utility".equals(r.get("type"))) {
                it.remove();
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Utility not found: " + reportId);
        }

        // Delete the utility directory recursively
        File dir = new File(UTILITIES_DIR, reportId);
        if (dir.exists()) {
            deleteRecursively(dir);
        }

        saveReportDefinitions();
        logger.info("[REPORTS] Deleted utility " + reportId);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public synchronized void saveReportDefinitions() {
        try {
            File f = new File("./data/reports.json");
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, reportDefinitions);
            logger.info("[REPORTS] Saved " + reportDefinitions.size() + " report definitions to " + f.getAbsolutePath());
        } catch (Exception e) {
            logger.warning("[REPORTS] Failed to save reports.json: " + e.getMessage());
        }
    }

    private void loadEnvVars(String reportId, Map<String, String> environment) {
        File envFile = new File(UTILITIES_DIR + "/" + reportId + "/env.json");
        if (!envFile.exists()) return;
        try {
            Map<String, String> vars = mapper.readValue(envFile, new TypeReference<Map<String, String>>() {});
            environment.putAll(vars);
        } catch (Exception e) {
            logger.warning("[REPORTS] Failed to load env vars for " + reportId + ": " + e.getMessage());
        }
    }

    /**
     * Returns a list of email recipient fields found in a utility's config files.
     * Each entry: {file, key, currentValue, action} where action is "append" or "replace".
     */
    public List<Map<String, String>> scanEmailFields(String reportId) {
        List<Map<String, String>> fields = new ArrayList<>();
        File dir = new File(UTILITIES_DIR, reportId);
        if (!dir.exists()) return fields;

        File[] configFiles = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".properties") || lower.endsWith(".cfg")
                || lower.endsWith(".ini") || lower.endsWith(".conf");
        });
        if (configFiles == null) return fields;

        for (File cf : configFiles) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(cf.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                    int eq = trimmed.indexOf('=');
                    String key = trimmed.substring(0, eq).trim().toLowerCase();
                    String val = trimmed.substring(eq + 1).trim();

                    boolean isEmailKey = key.equals("email") || key.equals("to")
                        || key.endsWith(".to") || key.endsWith(".cc") || key.endsWith(".bcc")
                        || key.endsWith(".recipient") || key.endsWith(".recipients")
                        || key.equals("send_to") || key.equals("mail_to")
                        || key.equals("recipient") || key.equals("notify_email")
                        || key.endsWith("_to") || key.endsWith("_cc");

                    if (isEmailKey) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("file", cf.getName());
                        entry.put("key", trimmed.substring(0, eq).trim());
                        entry.put("currentValue", val);
                        boolean hasExisting = !val.isEmpty() && !val.contains("example.com")
                            && !val.equals("None") && !val.equals("none") && !val.contains("TODO");
                        entry.put("action", hasExisting ? "append" : "set");
                        fields.add(entry);
                    }
                }
            } catch (Exception ignored) {}
        }
        return fields;
    }

    /**
     * Scans config/properties files in a utility's folder for email-related keys.
     * If found and currently blank or a placeholder, injects the running user's email.
     * A backup is created so the original is restorable.
     */
    private void injectUserEmail(String reportId, String userEmail, ReportStatus status) {
        if (userEmail == null || userEmail.isEmpty()) return;
        File dir = new File(UTILITIES_DIR, reportId);
        if (!dir.exists()) return;

        // Scan .properties, .cfg, .ini, .conf files
        File[] configFiles = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".properties") || lower.endsWith(".cfg")
                || lower.endsWith(".ini") || lower.endsWith(".conf");
        });
        if (configFiles == null || configFiles.length == 0) return;

        for (File cf : configFiles) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(cf.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                String original = content;
                boolean changed = false;

                // Match lines like: email=, to_email=, recipient=, notify_email=, send_to=, mail_to=
                // Only inject if value is blank, a placeholder, or contains "example"
                String[] lines = content.split("\n");
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("#") && trimmed.contains("=")) {
                        int eq = trimmed.indexOf('=');
                        String key = trimmed.substring(0, eq).trim().toLowerCase();
                        String val = trimmed.substring(eq + 1).trim();

                        // Only match recipient-type keys, NOT email.enabled, email.from, email.smtp_*
                        boolean isEmailKey = key.equals("email") || key.equals("to")
                            || key.endsWith(".to") || key.endsWith(".cc") || key.endsWith(".bcc")
                            || key.endsWith(".recipient") || key.endsWith(".recipients")
                            || key.equals("send_to") || key.equals("mail_to")
                            || key.equals("recipient") || key.equals("notify_email")
                            || key.endsWith("_to") || key.endsWith("_cc");
                        boolean isBlankOrPlaceholder = val.isEmpty()
                            || val.contains("example.com") || val.contains("@change")
                            || val.contains("your") || val.contains("TODO")
                            || val.equals("None") || val.equals("none");

                        if (isEmailKey) {
                            if (isBlankOrPlaceholder) {
                                // Blank/placeholder — set to user email
                                line = trimmed.substring(0, eq + 1) + " " + userEmail;
                                changed = true;
                            } else if (!val.toLowerCase().contains(userEmail.toLowerCase())) {
                                // Already has an email — append user's email if not already there
                                line = trimmed + "," + userEmail;
                                changed = true;
                            }
                        }
                    }
                    sb.append(line).append("\n");
                }

                if (changed) {
                    // Backup original
                    File backup = new File(cf.getAbsolutePath() + ".bak");
                    java.nio.file.Files.write(backup.toPath(), original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    // Write patched version
                    java.nio.file.Files.write(cf.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    status.addLog("\u2709 Injected your email into " + cf.getName());
                    logger.info("[REPORTS] Injected user email into " + cf.getName() + " for " + reportId);
                }
            } catch (Exception e) {
                logger.warning("[REPORTS] Failed to scan config " + cf.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Restores any .bak config files after a utility run completes,
     * so the next user gets a clean config.
     */
    private void restoreConfigBackups(String reportId) {
        File dir = new File(UTILITIES_DIR, reportId);
        if (!dir.exists()) return;
        File[] backups = dir.listFiles((d, name) -> name.endsWith(".bak"));
        if (backups == null) return;
        for (File bak : backups) {
            File orig = new File(bak.getAbsolutePath().replace(".bak", ""));
            try {
                java.nio.file.Files.copy(bak.toPath(), orig.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                bak.delete();
            } catch (Exception e) {
                logger.warning("[REPORTS] Failed to restore " + orig.getName() + ": " + e.getMessage());
            }
        }
    }

    public String getPythonExe() { return pythonExe; }

    public void appendLog(String reportId, String line) {
        ReportStatus status = statuses.get(reportId);
        if (status != null) status.addLog(line);
    }

    public void setOutputFile(String reportId, String path) {
        ReportStatus status = statuses.get(reportId);
        if (status != null) status.outputFile = path;
    }

    public static class ReportStatus {
        public String status = "idle"; // idle, running, completed, failed
        public long startTime = 0;
        public long endTime = 0;
        public String outputFile = null;
        public String error = null;
        public String userEmail = null;
        public String displayName = null;
        public boolean emailMeOnComplete = false;
        public boolean injectMyEmail = false;
        public List<String> logs = Collections.synchronizedList(new ArrayList<String>());

        public ReportStatus() {}

        public void addLog(String line) {
            logs.add(line);
            while (logs.size() > 200) logs.remove(0);
        }
    }

    private void loadStatuses() {
        File f = new File(STATUSES_FILE);
        if (!f.exists()) return;
        try {
            List<Map<String, Object>> saved = mapper.readValue(f,
                new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : saved) {
                String id = (String) entry.get("id");
                if (id == null) continue;
                ReportStatus s = new ReportStatus();
                s.status = (String) entry.getOrDefault("status", "idle");
                s.startTime = entry.get("startTime") != null ? ((Number) entry.get("startTime")).longValue() : 0;
                s.endTime = entry.get("endTime") != null ? ((Number) entry.get("endTime")).longValue() : 0;
                s.outputFile = (String) entry.get("outputFile");
                s.error = (String) entry.get("error");
                s.displayName = (String) entry.get("displayName");
                // Don't restore "running" state after restart — it's stale
                if ("running".equals(s.status)) s.status = "failed";
                statuses.put(id, s);
            }
            logger.info("[REPORTS] Loaded " + saved.size() + " report statuses from disk");
        } catch (Exception e) {
            logger.warning("[REPORTS] Failed to load statuses: " + e.getMessage());
        }
    }

    private void saveStatuses() {
        try {
            List<Map<String, Object>> toSave = new ArrayList<>();
            for (Map.Entry<String, ReportStatus> entry : statuses.entrySet()) {
                ReportStatus s = entry.getValue();
                if (s.startTime == 0) continue; // skip never-run
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", entry.getKey());
                m.put("status", s.status);
                m.put("startTime", s.startTime);
                m.put("endTime", s.endTime);
                m.put("outputFile", s.outputFile);
                m.put("error", s.error);
                m.put("displayName", s.displayName);
                toSave.add(m);
            }
            File f = new File(STATUSES_FILE);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, toSave);
        } catch (Exception e) {
            logger.warning("[REPORTS] Failed to save statuses: " + e.getMessage());
        }
    }
}
