package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.DeltaReportService;
import com.sandisk.plm.tracker.service.ReportService;
import com.sandisk.plm.tracker.service.WhatsNewDigestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final Logger logger = Logger.getLogger(ReportController.class.getName());

    @Autowired
    private ReportService reportService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private WhatsNewDigestService whatsNewDigestService;

    @Autowired
    private DeltaReportService deltaReportService;

    @Autowired
    private com.sandisk.plm.tracker.service.ItemCacheService itemCacheService;

    @GetMapping
    public Object listReports(HttpSession session) {
        String username = s(session, "username");
        boolean admin = isAdmin(session);
        List<Map<String, String>> reports = new ArrayList<>(reportService.getReportsForUser(username, admin));

        // Add built-in admin reports
        if (admin) {
            Map<String, String> whatsNew = new LinkedHashMap<>();
            whatsNew.put("id", "whats-new-digest");
            whatsNew.put("name", "What's New Digest");
            whatsNew.put("description", "AI-written email highlighting recent PLM Toolkit updates. Runs daily at 10:00 automatically. On-demand sends the full history.");
            whatsNew.put("estimatedTime", "~30 sec");
            whatsNew.put("owner", "System");
            whatsNew.put("hasAccess", "true");
            whatsNew.put("recipientMode", "broadcast");
            whatsNew.put("recipientDesc", "everyone who has logged in");
            reports.add(whatsNew);

            Map<String, String> sendStats = new LinkedHashMap<>();
            sendStats.put("id", "send-stats");
            sendStats.put("name", "Activity Stats");
            sendStats.put("description", "Email the admin activity digest (user counts, action breakdown, AI insights from logs). Same as the scheduled 9 AM / 9 PM report.");
            sendStats.put("estimatedTime", "~10 sec");
            sendStats.put("owner", "System");
            sendStats.put("hasAccess", "true");
            reports.add(sendStats);

            Map<String, String> downloadLog = new LinkedHashMap<>();
            downloadLog.put("id", "download-log");
            downloadLog.put("name", "Download Server Log");
            downloadLog.put("description", "Download the current server log file (plm-toolkit.log) for debugging. No email — direct download.");
            downloadLog.put("estimatedTime", "instant");
            downloadLog.put("owner", "System");
            downloadLog.put("hasAccess", "true");
            reports.add(downloadLog);

            Map<String, String> dryRun = new LinkedHashMap<>();
            dryRun.put("id", "dry-run");
            dryRun.put("name", "Dry Run (Health Check)");
            dryRun.put("description", "Verify DB, LDAP, Agile proxy, Python, and filesystem paths. Opens results in a new window.");
            dryRun.put("estimatedTime", "~5 sec");
            dryRun.put("owner", "System");
            dryRun.put("hasAccess", "true");
            reports.add(dryRun);

            Map<String, String> ecnReport = new LinkedHashMap<>();
            ecnReport.put("id", "ecn-report");
            ecnReport.put("name", "ECN Report Generator");
            ecnReport.put("description", "Queries Oracle Agile PLM for ECN change orders, applies KPI targets from the SLA Excel template, generates an Excel report with analysis charts, and optionally emails it. Uses the ECN Request Classification matrix for target days.");
            ecnReport.put("estimatedTime", "~30 sec");
            ecnReport.put("owner", "System");
            ecnReport.put("hasAccess", "true");
            reports.add(ecnReport);

            Map<String, String> adLookup = new LinkedHashMap<>();
            adLookup.put("id", "ad-bulk-lookup");
            adLookup.put("name", "AD Bulk Lookup");
            adLookup.put("description", "Upload a file with login IDs (one per line or in column A). Resolves each against Active Directory to find email, manager, department, title, country. Not-found users are highlighted in red.");
            adLookup.put("estimatedTime", "~1 sec per 10 users");
            adLookup.put("owner", "System");
            adLookup.put("hasAccess", "true");
            reports.add(adLookup);

            Map<String, String> itemSeed = new LinkedHashMap<>();
            itemSeed.put("id", "item-cache-seed");
            itemSeed.put("name", "Item Cache \u2014 Full Seed");
            Map<String, Object> cacheStatus = itemCacheService.getStatus();
            String cacheDesc = "Rebuild the item_extract JSON cache (~778K items, 25 columns). ";
            if ((boolean) cacheStatus.get("loaded")) {
                cacheDesc += "Currently loaded: " + cacheStatus.get("totalRecords") + " records, " + cacheStatus.get("fileSizeMB") + " MB.";
            } else {
                cacheDesc += "Not loaded yet \u2014 run Seed to generate.";
            }
            itemSeed.put("description", cacheDesc);
            itemSeed.put("estimatedTime", "~1-3 min");
            itemSeed.put("owner", "System");
            itemSeed.put("hasAccess", "true");
            reports.add(itemSeed);

            Map<String, String> itemDelta = new LinkedHashMap<>();
            itemDelta.put("id", "item-cache-delta");
            itemDelta.put("name", "Item Cache \u2014 Delta Update");
            itemDelta.put("description", "Incremental update: adds items released in the last 3 days to the JSON cache. Auto-runs daily at 2:30 AM.");
            itemDelta.put("estimatedTime", "~5-15 sec");
            itemDelta.put("owner", "System");
            itemDelta.put("hasAccess", "true");
            reports.add(itemDelta);
        }

        if (reports.isEmpty() && !admin) return forbidden();

        // Enrich each report with last-run status
        for (Map<String, String> report : reports) {
            String id = report.get("id");
            if (id != null) {
                Map<String, String> lastRun = reportService.getLastRunInfo(id);
                if (lastRun != null) {
                    report.putAll(lastRun);
                }
            }
        }

        return reports;
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> runReport(@PathVariable String id,
            @RequestParam(value = "emailMe", required = false, defaultValue = "false") boolean emailMe,
            @RequestParam(value = "injectMyEmail", required = false, defaultValue = "false") boolean injectMyEmail,
            HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = s(session, "username");
        boolean admin = isAdmin(session);
        String displayName = (String) session.getAttribute("displayName");

        // Handle built-in reports (admin-only)
        if ("whats-new-digest".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            reportService.markBuiltinRunning(id, displayName);
            new Thread(() -> {
                try {
                    whatsNewDigestService.sendDigestToAllUsers(true);
                    reportService.markBuiltinCompleted(id);
                } catch (Exception e) {
                    reportService.markBuiltinFailed(id, e.getMessage());
                    logger.warning("[REPORT] What's New digest failed: " + e.getMessage());
                }
            }).start();
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered What's New digest (full history)");
            response.put("success", true);
            response.put("message", "What's New digest is being sent to all users.");
            return response;
        }

        if ("send-stats".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            String email = (String) session.getAttribute("email");
            String recipientEmail = (email != null && !email.isEmpty()) ? email : "pdl-plm-admin@sandisk.com";
            reportService.markBuiltinRunning(id, displayName);
            new Thread(() -> {
                try {
                    deltaReportService.sendDeltaReportTo(recipientEmail);
                    reportService.markBuiltinCompleted(id);
                } catch (Exception e) {
                    reportService.markBuiltinFailed(id, e.getMessage());
                    logger.warning("[REPORT] Send Stats failed: " + e.getMessage());
                }
            }).start();
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered Activity Stats to " + recipientEmail);
            response.put("success", true);
            response.put("message", "Activity stats being sent to " + recipientEmail);
            return response;
        }

        if ("download-log".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            response.put("success", true);
            response.put("message", "REDIRECT:/api/admin/download-log");
            activityLogger.log(username, displayName, "REPORT_RUN", "Downloaded server log");
            return response;
        }

        if ("dry-run".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            response.put("success", true);
            response.put("message", "REDIRECT:/api/admin/dry-run");
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered Dry Run");
            return response;
        }

        if ("ecn-report-upload-sla".equals(id)) {
            // Upload new SLA template
            response.put("success", true);
            response.put("message", "REDIRECT:ecn-report-upload-sla");
            return response;
        }

        if ("ecn-report".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            reportService.markBuiltinRunning(id, displayName);
            String userEmail = (String) session.getAttribute("email");
            new Thread(() -> {
                try {
                    // Run Python script
                    String dataDir = "./data/ecn-report";
                    String configPath = dataDir + "/ecn_report.properties";
                    String outputPath = dataDir + "/output/ECN_Report.xlsx";
                    String python = reportService.getPythonExe();

                    ProcessBuilder pb = new ProcessBuilder(python,
                        "ecn_report_generator.py",
                        "--config", "ecn_report.properties",
                        "--output", "output/ECN_Report.xlsx",
                        "--no-email");
                    pb.redirectErrorStream(true);
                    pb.directory(new java.io.File(dataDir));
                    pb.environment().put("PYTHONIOENCODING", "utf-8");
                    // Pass user email for later use
                    pb.environment().put("PLM_USER_EMAIL", userEmail != null ? userEmail : "");

                    Process proc = pb.start();
                    StringBuilder output = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            output.append(line).append("\n");
                            reportService.appendLog(id, line);
                        }
                    }
                    int exitCode = proc.waitFor();
                    if (exitCode == 0) {
                        reportService.markBuiltinCompleted(id);
                        // Set output file
                        reportService.setOutputFile(id, outputPath);
                        logger.info("[ECN-REPORT] Completed: " + outputPath);
                    } else {
                        reportService.markBuiltinFailed(id, "Exit code " + exitCode);
                        logger.warning("[ECN-REPORT] Failed: " + output.toString().substring(Math.max(0, output.length() - 500)));
                    }
                } catch (Exception e) {
                    reportService.markBuiltinFailed(id, e.getMessage());
                    logger.warning("[ECN-REPORT] Error: " + e.getMessage());
                }
            }).start();
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered ECN Report Generator");
            response.put("success", true);
            response.put("message", "ECN Report generation started. This takes ~30 seconds.");
            return response;
        }

        if ("ad-bulk-lookup".equals(id)) {
            // This is handled client-side with a special UI — just return success
            response.put("success", true);
            response.put("message", "REDIRECT:ad-bulk-lookup");
            return response;
        }

        if ("item-cache-seed".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            reportService.markBuiltinRunning(id, displayName);
            new Thread(() -> {
                try {
                    int count = itemCacheService.seed();
                    reportService.markBuiltinCompleted(id);
                    logger.info("[ITEM-CACHE] Seed triggered by " + displayName + ": " + count + " records");
                } catch (Throwable t) {
                    String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "(no message)");
                    reportService.markBuiltinFailed(id, msg);
                    logger.warning("[ITEM-CACHE] Seed failed: " + msg);
                }
            }).start();
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered Item Cache Seed");
            response.put("success", true);
            response.put("message", "Item cache seed started. This takes 1-3 minutes.");
            return response;
        }

        if ("item-cache-delta".equals(id)) {
            if (!admin) { response.put("success", false); response.put("message", "Admin access required."); return response; }
            reportService.markBuiltinRunning(id, displayName);
            new Thread(() -> {
                try {
                    int count = itemCacheService.delta();
                    reportService.markBuiltinCompleted(id);
                    logger.info("[ITEM-CACHE] Delta triggered by " + displayName + ": " + count + " records updated");
                } catch (Throwable t) {
                    String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "(no message)");
                    reportService.markBuiltinFailed(id, msg);
                    logger.warning("[ITEM-CACHE] Delta failed: " + msg);
                }
            }).start();
            activityLogger.log(username, displayName, "REPORT_RUN", "Triggered Item Cache Delta");
            response.put("success", true);
            response.put("message", "Item cache delta started.");
            return response;
        }

        if (!reportService.isUserAllowed(id, username, admin)) { response.put("success", false); response.put("message", "You do not have access to this report."); return response; }

        String email = (String) session.getAttribute("email");

        boolean started = reportService.runReport(id, email, displayName, emailMe, injectMyEmail);
        if (started) {
            activityLogger.log(username, displayName, "REPORT_RUN", "Started report: " + id);
            response.put("success", true);
            response.put("message", "Report generation started.");
        } else {
            response.put("success", false);
            response.put("message", "Report is already running or not found.");
        }
        return response;
    }

    /**
     * GET /{id}/email-fields — preview which config keys will be patched with the user's email.
     */
    @GetMapping("/{id}/email-fields")
    public Map<String, Object> getEmailFields(@PathVariable String id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fields", reportService.scanEmailFields(id));
        return result;
    }

    @GetMapping("/{id}/status")
    public Object getStatus(@PathVariable String id, HttpSession session) {
        if (!reportService.isUserAllowed(id, s(session, "username"), isAdmin(session))) return forbidden();
        return reportService.getStatus(id);
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable String id, HttpSession session, HttpServletResponse response) throws IOException {
        if (!reportService.isUserAllowed(id, s(session, "username"), isAdmin(session))) { response.sendError(403); return; }
        File file = reportService.getOutputFile(id);
        if (file == null || !file.exists()) { response.sendError(404, "No report output available"); return; }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + file.getName());
        response.setContentLengthLong(file.length());
        try (InputStream in = new FileInputStream(file)) {
            org.springframework.util.StreamUtils.copy(in, response.getOutputStream());
        }

        activityLogger.log(s(session, "username"), (String) session.getAttribute("displayName"),
            "REPORT_DOWNLOAD", "Downloaded: " + file.getName());
    }

    @PostMapping("/analyze-script")
    public Map<String, Object> analyzeScript(@RequestParam("file") MultipartFile file, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".py") && !filename.endsWith(".java") && !filename.endsWith(".jar"))) {
            response.put("success", false);
            response.put("message", "Only .py, .java, and .jar files are supported.");
            return response;
        }

        try {
            Map<String, Object> analysis;
            if (filename.endsWith(".jar")) {
                analysis = reportService.analyzeJar(file.getBytes(), filename);
            } else {
                String scriptContent = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                analysis = reportService.analyzeScript(scriptContent, filename);
            }
            response.put("success", true);
            response.putAll(analysis);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Analysis failed: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/utility/{id}/file/{filename:.+}")
    public void downloadUtilityFile(@PathVariable String id, @PathVariable String filename,
                                     HttpSession session, HttpServletResponse response) throws IOException {
        if (!isAdmin(session)) { response.sendError(403); return; }
        File f = new File("./data/utilities/" + id + "/" + filename);
        if (!f.exists() || !f.isFile()) { response.sendError(404, "File not found"); return; }

        String contentType = filename.endsWith(".properties") || filename.endsWith(".txt") || filename.endsWith(".csv")
            ? "text/plain; charset=utf-8" : "application/octet-stream";
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
        response.setContentLengthLong(f.length());
        try (InputStream in = new FileInputStream(f)) {
            org.springframework.util.StreamUtils.copy(in, response.getOutputStream());
        }
    }

    @GetMapping("/utility/{id}/env")
    public Map<String, Object> getUtilityEnv(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }
        try {
            File envFile = new File("./data/utilities/" + id + "/env.json");
            if (!envFile.exists()) {
                response.put("success", true);
                response.put("envVars", new LinkedHashMap<>());
                return response;
            }
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, String> vars = m.readValue(envFile,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            response.put("success", true);
            response.put("envVars", vars);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    @PostMapping("/utility/{id}/env")
    public Map<String, Object> saveUtilityEnv(@PathVariable String id,
            @RequestBody Map<String, String> envVars, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }
        try {
            File envFile = new File("./data/utilities/" + id + "/env.json");
            if (!envFile.getParentFile().exists()) {
                response.put("success", false);
                response.put("message", "Utility not found");
                return response;
            }
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            m.writerWithDefaultPrettyPrinter().writeValue(envFile, envVars);
            response.put("success", true);
            response.put("message", "Environment variables saved.");
            activityLogger.log(s(session, "username"),
                (String) session.getAttribute("displayName"),
                "UTILITY_ENV_UPDATE", "Updated env vars for " + id);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    @PostMapping("/utility/{id}/cli-args")
    public Map<String, Object> saveUtilityFields(@PathVariable String id,
            @RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }
        try {
            String username = s(session, "username");
            String displayName = (String) session.getAttribute("displayName");
            List<String> changes = new ArrayList<>();

            if (body.containsKey("cliArgs")) {
                reportService.updateUtilityField(id, "cliArgs", body.get("cliArgs"));
                changes.add("CLI args");
            }
            if (body.containsKey("name")) {
                reportService.updateUtilityField(id, "name", body.get("name"));
                changes.add("name → " + body.get("name"));
            }
            if (body.containsKey("description")) {
                reportService.updateUtilityField(id, "description", body.get("description"));
                changes.add("description");
            }
            if (body.containsKey("javaPath")) {
                reportService.updateUtilityField(id, "javaPath", body.get("javaPath"));
                changes.add("Java path");
            }
            if (body.containsKey("useJdk8")) {
                reportService.updateUtilityField(id, "useJdk8", body.get("useJdk8"));
                changes.add("JDK 1.8 = " + body.get("useJdk8"));
            }
            if (body.containsKey("removeDepJar")) {
                reportService.removeDepJar(id, body.get("removeDepJar"));
                changes.add("removed dep JAR: " + body.get("removeDepJar"));
            }
            if (body.containsKey("removeSupportFile")) {
                reportService.removeSupportFile(id, body.get("removeSupportFile"));
                changes.add("removed file: " + body.get("removeSupportFile"));
            }

            if (!changes.isEmpty()) {
                activityLogger.log(username, displayName, "UTILITY_EDIT",
                    "Edited utility " + id + ": " + String.join(", ", changes));
            }
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    @PostMapping("/validate-properties")
    public Map<String, Object> validateProperties(@RequestParam("file") MultipartFile file, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }
        try {
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<Map<String, String>> warnings = reportService.validatePropertiesFile(content);
            response.put("success", true);
            response.put("warnings", warnings);
            response.put("warningCount", warnings.size());
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Validation failed: " + e.getMessage());
        }
        return response;
    }

    @PostMapping("/upload-script")
    public Map<String, Object> uploadScript(@RequestParam("file") MultipartFile file,
                                             @RequestParam("name") String name,
                                             @RequestParam("description") String description,
                                             @RequestParam(value = "envVars", required = false, defaultValue = "{}") String envVars,
                                             @RequestParam(value = "cliArgs", required = false, defaultValue = "") String cliArgs,
                                             @RequestParam(value = "entryPoint", required = false, defaultValue = "") String entryPoint,
                                             @RequestParam(value = "supportFiles", required = false) MultipartFile[] supportFiles,
                                             @RequestParam(value = "depJars", required = false) MultipartFile[] depJars,
                                             HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null ||
            (!originalFilename.endsWith(".py") && !originalFilename.endsWith(".java") && !originalFilename.endsWith(".jar"))) {
            response.put("success", false);
            response.put("message", "Only .py, .java, and .jar files are supported.");
            return response;
        }

        try {
            String username = s(session, "username");
            String displayName = (String) session.getAttribute("displayName");
            Map<String, Object> result = reportService.addUtility(name, description,
                    originalFilename, file.getBytes(), envVars, cliArgs, entryPoint,
                    displayName, username, supportFiles, depJars);
            activityLogger.log(username, displayName, "UTILITY_UPLOAD",
                    "Uploaded utility '" + name + "' (" + originalFilename + ") id=" + result.get("id"));
            response.put("success", true);
            response.put("message", "Utility '" + name + "' uploaded successfully.");
            response.put("id", result.get("id"));
            return response;
        } catch (Exception e) {
            logger.warning("[REPORTS] Upload script failed: " + e.getMessage());
            response.put("success", false);
            response.put("message", "Upload failed: " + e.getMessage());
            return response;
        }
    }

    @PostMapping("/{id}/replace-files")
    public Map<String, Object> replaceFiles(@PathVariable String id,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "depJars", required = false) MultipartFile[] depJars,
            HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }

        try {
            if (files != null && files.length > 0) {
                reportService.replaceSupportFiles(id, files);
            }
            if (depJars != null && depJars.length > 0) {
                reportService.replaceDepJars(id, depJars);
            }
            int fileCount = (files != null ? files.length : 0) + (depJars != null ? depJars.length : 0);
            String username = s(session, "username");
            String displayName = (String) session.getAttribute("displayName");
            activityLogger.log(username, displayName, "UTILITY_FILES_REPLACED",
                    "Replaced " + fileCount + " file(s) for utility id=" + id);
            response.put("success", true);
            response.put("message", "Replaced " + files.length + " file(s).");
            return response;
        } catch (Exception e) {
            logger.warning("[REPORTS] Replace files failed for " + id + ": " + e.getMessage());
            response.put("success", false);
            response.put("message", "Replace failed: " + e.getMessage());
            return response;
        }
    }

    @DeleteMapping("/utility/{id}")
    public Map<String, Object> deleteUtility(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }

        // Only the owner can delete their utility
        String currentUser = s(session, "username");
        String owner = reportService.getUtilityOwner(id);
        if (owner != null && !owner.isEmpty() && !owner.equals(currentUser)) {
            response.put("success", false);
            response.put("message", "Only the owner (" + owner + ") can delete this utility.");
            return response;
        }

        try {
            reportService.deleteUtility(id);
            String username = s(session, "username");
            String displayName = (String) session.getAttribute("displayName");
            activityLogger.log(username, displayName, "UTILITY_DELETE", "Deleted utility id=" + id);
            response.put("success", true);
            response.put("message", "Utility deleted.");
            return response;
        } catch (Exception e) {
            logger.warning("[REPORTS] Delete utility failed: " + e.getMessage());
            response.put("success", false);
            response.put("message", "Delete failed: " + e.getMessage());
            return response;
        }
    }

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
    }

    private Map<String, Object> forbidden() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", "Admin access required.");
        return r;
    }

    private String s(HttpSession session, String key) {
        Object v = session != null ? session.getAttribute(key) : null;
        return v != null ? v.toString() : "unknown";
    }
}
