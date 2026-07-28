package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.DeltaReportService;
import com.sandisk.plm.tracker.service.LdapAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.mail.*;
import javax.mail.internet.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private LdapAuthService ldapAuthService;

    @Autowired
    private com.sandisk.plm.tracker.service.BuildInfoService buildInfoService;

    @Autowired
    private DeltaReportService deltaReportService;

    @Autowired
    private com.sandisk.plm.tracker.service.DocumentIndexService documentIndexService;

    @Autowired
    private com.sandisk.plm.tracker.service.AgentUsageReportService agentUsageReportService;

    /** Send the Agent-API usage digest now (admin only) — for testing / off-cycle sends. */
    @PostMapping("/agent-usage-report/send-now")
    public ResponseEntity<?> sendAgentUsageReport(
            @RequestParam(value = "to", required = false) String to,
            HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            return ResponseEntity.status(403).body(Collections.singletonMap("error", "Admin only"));
        }
        try {
            // Optional ?to= overrides recipients for this send only (private validation send);
            // when absent, the configured app.agent.usage-report.recipients list is used.
            java.util.List<String> override = null;
            if (to != null && !to.trim().isEmpty()) {
                override = new java.util.ArrayList<>();
                for (String s : to.split("[,;]")) { String t = s.trim(); if (!t.isEmpty()) override.add(t); }
            }
            return ResponseEntity.ok(agentUsageReportService.sendDigest(override));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", String.valueOf(e.getMessage())));
        }
    }

    /** Current usage-digest recipients + whether they come from the hot-editable file or config (admin only). */
    @GetMapping("/agent-usage-report/recipients")
    public ResponseEntity<?> getAgentUsageRecipients(HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            return ResponseEntity.status(403).body(Collections.singletonMap("error", "Admin only"));
        }
        return ResponseEntity.ok(agentUsageReportService.recipientsStatus());
    }

    /** Set the usage-digest recipients — hot-editable, no restart (admin only).
     *  Body: {"recipients":["a@x.com","b@x.com"]} or a comma/semicolon list in "recipients".
     *  An empty list clears the override so the configured default applies again. */
    @PutMapping("/agent-usage-report/recipients")
    public ResponseEntity<?> setAgentUsageRecipients(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            return ResponseEntity.status(403).body(Collections.singletonMap("error", "Admin only"));
        }
        try {
            java.util.List<String> addrs = new java.util.ArrayList<>();
            Object r = body == null ? null : body.get("recipients");
            if (r instanceof java.util.List) {
                for (Object o : (java.util.List<?>) r) if (o != null) addrs.add(String.valueOf(o));
            } else if (r instanceof String) {
                for (String s : ((String) r).split("[,;\\n\\r]")) if (!s.trim().isEmpty()) addrs.add(s.trim());
            }
            return ResponseEntity.ok(agentUsageReportService.setRecipients(addrs));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", String.valueOf(e.getMessage())));
        }
    }

    /** Rebuild the Agent API document index live from the Agile DB (admin only).
     *  Replaces the manual Agile-export → seed loop; the nightly cron does this too. */
    @PostMapping("/agent-docs/refresh")
    public ResponseEntity<?> refreshAgentDocs(HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            return ResponseEntity.status(403).body(Collections.singletonMap("error", "Admin only"));
        }
        if (!documentIndexService.canRefreshFromDb()) {
            return ResponseEntity.status(503).body(Collections.singletonMap("error", "Agile datasource not available"));
        }
        long t0 = System.currentTimeMillis();
        int count = documentIndexService.refreshFromDb();
        activityLogger.log((String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "AGENT_DOCS_REFRESH", "count=" + count);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", count);
        resp.put("elapsedMs", System.currentTimeMillis() - t0);
        resp.put("generatedAt", documentIndexService.generatedAt());
        return ResponseEntity.ok(resp);
    }

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from}")
    private String fromAddress;

    @Value("${app.log-file:./logs/plm-toolkit.log}")
    private String logFilePath;

    @PostMapping("/send-delta-report")
    public Map<String, Object> sendDeltaReport(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }
        String email = (String) session.getAttribute("email");
        try {
            deltaReportService.sendDeltaReportTo(email != null ? email : "pdl-plm-admin@sandisk.com");
            response.put("success", true);
            response.put("message", "Delta report with AI insights sent to " + email);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed: " + e.getMessage());
        }
        return response;
    }

    @PostMapping("/send-stats")
    public Map<String, Object> sendStats(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();

        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            response.put("success", false);
            response.put("message", "Admin access required.");
            return response;
        }

        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        try {
            String htmlBody = activityLogger.formatAsHtml();

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            Session mailSession = Session.getInstance(props);

            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(email));

            String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("PLM Toolkit Activity Report - " + date));
            message.setContent(htmlBody, "text/html; charset=utf-8");

            Transport.send(message);

            activityLogger.log(
                (String) session.getAttribute("username"), displayName,
                "ADMIN_STATS", "Activity report emailed to " + email);

            int count = activityLogger.getLast24Hours().size();
            response.put("success", true);
            response.put("message", "Activity report (" + count + " entries) sent to " + email);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed: " + e.getMessage());
        }
        return response;
    }

    /**
     * Admin-only: download the current server log file (plm-toolkit.log).
     * Browsers save it as an attachment rather than render it.
     */
    @GetMapping("/download-log")
    public ResponseEntity<Resource> downloadLog(HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            return ResponseEntity.status(403).build();
        }

        File logFile = new File(logFilePath);
        if (!logFile.exists() || !logFile.isFile()) {
            return ResponseEntity.notFound().build();
        }

        String date = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String downloadName = "plm-toolkit-" + date + ".log";

        Resource body = new FileSystemResource(logFile);

        activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "ADMIN_LOG_DOWNLOAD",
                "Downloaded server log (" + logFile.length() + " bytes)");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(logFile.length())
                .body(body);
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();

        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (!Boolean.TRUE.equals(isAdmin)) {
            response.put("success", false);
            return response;
        }

        List<ActivityLogger.ActivityEntry> entries = activityLogger.getLast24Hours();
        response.put("totalActivities", entries.size());

        Map<String, Integer> userCounts = new LinkedHashMap<>();
        for (ActivityLogger.ActivityEntry e : entries) {
            userCounts.merge(e.displayName, 1, Integer::sum);
        }
        response.put("userCounts", userCounts);
        response.put("success", true);
        return response;
    }

    // =========================================================================
    // What's New admin flags — toggle items as admin-only
    // =========================================================================

    private static final String ADMIN_FLAGS_FILE = "./data/whatsnew-admin-flags.json";
    private static final ObjectMapper flagsMapper = new ObjectMapper();

    @GetMapping("/whatsnew-flags")
    public Map<String, Object> getWhatsNewFlags(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }
        response.put("success", true);
        response.put("flags", loadFlags());
        return response;
    }

    @PostMapping("/whatsnew-flags")
    public Map<String, Object> setWhatsNewFlag(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) { response.put("success", false); return response; }

        String itemKey = (String) body.get("key");
        Boolean adminOnly = (Boolean) body.get("adminOnly");
        if (itemKey == null || adminOnly == null) {
            response.put("success", false);
            response.put("message", "Missing key or adminOnly");
            return response;
        }

        try {
            Map<String, Boolean> flags = loadFlags();
            if (adminOnly) {
                flags.put(itemKey, true);
            } else {
                flags.remove(itemKey);
            }
            saveFlags(flags);
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    private Map<String, Boolean> loadFlags() {
        File f = new File(ADMIN_FLAGS_FILE);
        if (!f.exists()) return new LinkedHashMap<>();
        try {
            return flagsMapper.readValue(f, new TypeReference<Map<String, Boolean>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveFlags(Map<String, Boolean> flags) throws Exception {
        File f = new File(ADMIN_FLAGS_FILE);
        f.getParentFile().mkdirs();
        flagsMapper.writerWithDefaultPrettyPrinter().writeValue(f, flags);
    }

    @PostMapping("/ad-health")
    public Map<String, Object> testAdHealth(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            response.put("error", "Admin access required.");
            return response;
        }
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            response.put("error", "Username and password are required.");
            return response;
        }
        activityLogger.log(
            (String) session.getAttribute("username"),
            (String) session.getAttribute("displayName"),
            "AD_HEALTH_CHECK", "Testing AD for user: " + username.trim());
        return ldapAuthService.testAdHealth(username.trim(), password);
    }

    @Autowired
    private com.sandisk.plm.tracker.service.ItemCacheService itemCacheService;

    @PostMapping("/ecn-report/upload-sla")
    public Map<String, Object> uploadEcnSla(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpSession session) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (!isAdmin(session)) { result.put("error", "Admin only"); return result; }
        try {
            java.io.File dest = new java.io.File("./data/ecn-report/ecn-kpi-template.xlsx");
            dest.getParentFile().mkdirs();
            file.transferTo(dest);
            result.put("ok", true);
            result.put("message", "SLA template updated (" + (file.getSize() / 1024) + " KB)");
            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "ECN_SLA_UPLOAD", "Updated ECN KPI template");
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/ecn-report/download")
    public void downloadEcnReport(HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        java.io.File f = new java.io.File("./data/ecn-report/output/ECN_Report.xlsx");
        if (!f.exists()) { response.sendError(404, "No report generated yet"); return; }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"ECN_Report_" +
            new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx\"");
        java.nio.file.Files.copy(f.toPath(), response.getOutputStream());
    }

    /**
     * POST /api/admin/ad-lookup-bulk — upload a file with login IDs, resolve each against AD.
     * Returns JSON array with login ID, display name, email, manager, department, title, country, status.
     */
    @PostMapping("/ad-lookup-bulk")
    public java.util.List<java.util.Map<String, String>> adLookupBulk(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpSession session) throws Exception {

        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        // Parse the file — extract login IDs from the first column
        java.util.List<String> loginIds = new java.util.ArrayList<>();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";

        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            // Excel
            org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream());
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row == null) continue;
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(0);
                if (cell == null) continue;
                String val = "";
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    val = String.valueOf((long) cell.getNumericCellValue());
                } else {
                    val = cell.getStringCellValue();
                }
                if (val != null && !val.trim().isEmpty()) loginIds.add(val.trim());
            }
            wb.close();
        } else {
            // CSV / TXT — one login ID per line (or comma-separated)
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("[\\n\\r,]+")) {
                String val = line.trim();
                // Skip header-like lines
                if (val.isEmpty() || val.equalsIgnoreCase("loginid") || val.equalsIgnoreCase("login_id")
                    || val.equalsIgnoreCase("username") || val.equalsIgnoreCase("user_id")
                    || val.equalsIgnoreCase("id") || val.equalsIgnoreCase("employee_id")) continue;
                loginIds.add(val);
            }
        }

        java.util.logging.Logger.getLogger(getClass().getName()).info("[AD-LOOKUP] Bulk lookup for " + loginIds.size() + " IDs by " + username);

        // Resolve each against AD
        java.util.List<java.util.Map<String, String>> results = new java.util.ArrayList<>();
        for (String id : loginIds) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("LOGIN_ID", id);

            // Try by employee ID first, then by username
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(id);
            if (info == null || (info.displayName == null || info.displayName.isEmpty())) {
                info = ldapAuthService.lookupUserByUsername(id);
            }

            if (info != null && info.displayName != null && !info.displayName.isEmpty()) {
                row.put("DISPLAY_NAME", info.displayName != null ? info.displayName : "");
                row.put("EMAIL", info.email != null ? info.email : "");
                row.put("DEPARTMENT", info.department != null ? info.department : "");
                row.put("TITLE", info.title != null ? info.title : "");
                row.put("MANAGER", info.manager != null ? info.manager : "");
                row.put("COUNTRY", info.country != null ? info.country : "");
                row.put("CITY", info.city != null ? info.city : "");
                row.put("STATUS", "Found");
            } else {
                row.put("DISPLAY_NAME", "");
                row.put("EMAIL", "");
                row.put("DEPARTMENT", "");
                row.put("TITLE", "");
                row.put("MANAGER", "");
                row.put("COUNTRY", "");
                row.put("CITY", "");
                row.put("STATUS", "NOT FOUND");
            }
            results.add(row);
        }

        activityLogger.log(username, displayName, "AD_BULK_LOOKUP",
            loginIds.size() + " IDs, " + results.stream().filter(r -> "Found".equals(r.get("STATUS"))).count() + " found");

        return results;
    }

    /**
     * POST /api/admin/ad-lookup-export — same as ad-lookup-bulk but returns Excel.
     */
    @PostMapping("/ad-lookup-export")
    public void adLookupExport(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {

        java.util.List<java.util.Map<String, String>> results = adLookupBulk(file, session);
        String[] cols = {"LOGIN_ID", "DISPLAY_NAME", "EMAIL", "DEPARTMENT", "TITLE", "MANAGER", "COUNTRY", "CITY", "STATUS"};

        org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("AD Lookup");

        // Header style
        org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
        hdrFont.setBold(true); hdrStyle.setFont(hdrFont);

        // Not found style — red background
        org.apache.poi.ss.usermodel.CellStyle notFoundStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font redFont = wb.createFont();
        redFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
        notFoundStyle.setFont(redFont);

        // Header row
        org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = hdrRow.createCell(i);
            cell.setCellValue(cols[i]); cell.setCellStyle(hdrStyle);
        }

        // Data rows
        for (int r = 0; r < results.size(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            java.util.Map<String, String> data = results.get(r);
            boolean notFound = "NOT FOUND".equals(data.get("STATUS"));
            for (int c = 0; c < cols.length; c++) {
                org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                cell.setCellValue(data.getOrDefault(cols[c], ""));
                if (notFound) cell.setCellStyle(notFoundStyle);
            }
        }

        String fn = "AD-Lookup-" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fn + "\"");
        wb.write(response.getOutputStream());
        wb.dispose();
        wb.close();
    }

    // ===================================================================
    //  Smart AD Fill — reads the caller's header row, fills the AD-mapped
    //  columns from LDAP for each row, leaves un-mapped columns untouched.
    //  Multi-sheet aware. Designed for access-certification spreadsheets
    //  like "Agile Silicon - Manual review-<BPO>.xlsx" where each sheet
    //  has the same shape (User ID | First | Last | Email | Roles | BPO |
    //  Certify/Revoke | Status | Job Title | Department).
    // ===================================================================

    /**
     * Header text → canonical AD field. Lowercased + whitespace-normalised.
     * Order matters within {@link #adFieldFor(String)} below: more-specific
     * synonyms must be checked before less-specific ones (e.g. "job title"
     * before "title" alone).
     */
    private static String adFieldFor(String header) {
        if (header == null) return null;
        String h = header.trim().toLowerCase().replaceAll("[_\\-/]+", " ").replaceAll("\\s+", " ");
        if (h.isEmpty()) return null;

        // Key column (used to LOOK UP the person, not filled from AD).
        if (h.equals("user id") || h.equals("userid") || h.equals("login") || h.equals("login id")
            || h.equals("employee id") || h.equals("employeeid") || h.equals("username")
            || h.equals("sam") || h.equals("sam account") || h.equals("id")) {
            return "_KEY";
        }
        // Fields filled from AD.
        if (h.contains("first name") || h.equals("firstname") || h.equals("given name") || h.equals("givenname")) return "givenName";
        if (h.contains("last name")  || h.equals("lastname")  || h.equals("surname") || h.equals("family name")) return "sn";
        if (h.equals("display name") || h.equals("name") || h.equals("full name") || h.equals("displayname")) return "displayName";
        if (h.equals("email") || h.equals("e mail") || h.equals("mail") || h.contains("email address")) return "mail";
        if (h.equals("department") || h.equals("dept") || h.contains("department name")) return "department";
        if (h.equals("job title") || h.equals("title") || h.equals("position") || h.equals("job position")) return "title";
        if (h.equals("manager") || h.contains("manager name") || h.equals("reports to")) return "manager";
        if (h.equals("phone") || h.equals("telephone") || h.equals("mobile") || h.contains("office phone")) return "telephone";
        if (h.equals("country") || h.equals("country code")) return "country";
        if (h.equals("city")) return "city";
        if (h.equals("office") || h.equals("location") || h.contains("office location")) return "office";
        if (h.equals("status") || h.equals("active") || h.equals("enabled") || h.equals("account status")) return "_STATUS";
        return null;
    }

    /**
     * POST /api/admin/ad-smart-fill — multi-sheet xlsx in, same xlsx out with
     * empty AD-mapped cells filled. Persons not found get "Not found" in the
     * AD-mapped cells; the rest of the row is left alone.
     */
    @PostMapping("/ad-smart-fill")
    public void adSmartFill(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        if (!isAdmin(session)) {
            response.sendError(403, "Admin access required.");
            return;
        }
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");

        org.apache.poi.ss.usermodel.Workbook wb;
        try {
            wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream());
        } catch (Exception e) {
            response.sendError(400, "Couldn't read the uploaded file: " + e.getMessage());
            return;
        }

        int sheetsProcessed = 0, rowsProcessed = 0, found = 0, notFound = 0, cellsFilled = 0;
        Map<String, com.sandisk.plm.tracker.service.LdapAuthService.UserInfo> cache = new HashMap<>();

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(s);
            if (sheet.getLastRowNum() < 1) continue;

            // Find header row using the same heuristic as the Agile Lookup
            // (well-known title-row pattern protection).
            int headerRowIdx = findHeaderRow(sheet);
            if (headerRowIdx < 0) continue;
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(headerRowIdx);
            if (headerRow == null) continue;

            // Build the column → AD-field map for THIS sheet.
            int keyCol = -1;
            Map<Integer, String> colToField = new LinkedHashMap<>();
            int lastCol = headerRow.getLastCellNum();
            for (int c = 0; c < lastCol; c++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c);
                if (cell == null) continue;
                String h = getCellAsString(cell).trim();
                String field = adFieldFor(h);
                if (field == null) continue;
                if ("_KEY".equals(field)) { if (keyCol < 0) keyCol = c; continue; }
                colToField.put(c, field);
            }
            if (keyCol < 0) {
                // No key column — skip this sheet (e.g. "Sheet1" with no header).
                continue;
            }
            if (colToField.isEmpty()) continue;

            sheetsProcessed++;
            org.apache.poi.ss.usermodel.CellStyle notFoundStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font notFoundFont = wb.createFont();
            notFoundFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            notFoundFont.setItalic(true);
            notFoundStyle.setFont(notFoundFont);

            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                org.apache.poi.ss.usermodel.Cell keyCell = row.getCell(keyCol);
                if (keyCell == null) continue;
                String key = getCellAsString(keyCell).trim();
                if (key.isEmpty()) continue;
                rowsProcessed++;

                com.sandisk.plm.tracker.service.LdapAuthService.UserInfo info = cache.get(key);
                if (info == null) {
                    info = ldapAuthService.lookupUserById(key);
                    if (info == null || info.displayName == null || info.displayName.isEmpty()) {
                        info = ldapAuthService.lookupUserByUsername(key);
                    }
                    if (info == null) {
                        info = new com.sandisk.plm.tracker.service.LdapAuthService.UserInfo(); // marker for not-found
                    }
                    cache.put(key, info);
                }
                boolean isFound = info.displayName != null && !info.displayName.isEmpty();
                if (isFound) found++; else notFound++;

                // Fill mapped columns (only when currently empty — never overwrite).
                for (Map.Entry<Integer, String> e : colToField.entrySet()) {
                    int c = e.getKey();
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    boolean empty = (cell == null) || (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK)
                        || getCellAsString(cell).trim().isEmpty();
                    if (!empty) continue;
                    String value = isFound ? valueFor(info, e.getValue()) : "Not found";
                    if (value == null) continue;
                    if (cell == null) cell = row.createCell(c);
                    cell.setCellValue(value);
                    if (!isFound) cell.setCellStyle(notFoundStyle);
                    cellsFilled++;
                }
            }
        }

        // Stream out the same workbook (preserves all formatting, untouched cells).
        String origName = file.getOriginalFilename() == null ? "ad-fill" : file.getOriginalFilename();
        String outBase = origName.toLowerCase().endsWith(".xlsx")
            ? origName.substring(0, origName.length() - 5)
            : (origName.toLowerCase().endsWith(".xls") ? origName.substring(0, origName.length() - 4) : origName);
        String outName = outBase + "__filled.xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + outName + "\"");
        wb.write(response.getOutputStream());
        wb.close();

        activityLogger.log(username, displayName, "AD_SMART_FILL",
            "sheets=" + sheetsProcessed + " rows=" + rowsProcessed
                + " found=" + found + " notFound=" + notFound + " cellsFilled=" + cellsFilled
                + " file=" + origName);
    }

    /** Pull the right field off the UserInfo DTO by canonical name. Returns "" for unknown / unsupported. */
    private static String valueFor(com.sandisk.plm.tracker.service.LdapAuthService.UserInfo info, String field) {
        if (info == null || field == null) return "";
        switch (field) {
            case "displayName": return nz(info.displayName);
            case "givenName":   return splitFirst(info.displayName);
            case "sn":          return splitLast(info.displayName);
            case "mail":        return nz(info.email);
            case "department":  return nz(info.department);
            case "title":       return nz(info.title);
            case "manager":     return nz(info.manager);
            case "country":     return nz(info.country);
            case "city":        return nz(info.city);
            case "office":      return nz(info.city); // closest fit until we expose physicalDeliveryOfficeName
            case "_STATUS":     return "Active";       // we only resolve enabled accounts; disabled show as "Not found"
            case "telephone":   return ""; // not exposed on UserInfo DTO yet
            default: return "";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String splitFirst(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "";
        // AD displayName is typically "First Last". Some are "Last, First (id)" — handle both.
        if (displayName.contains(",")) {
            String[] parts = displayName.split(",", 2);
            return parts.length > 1 ? parts[1].trim().split("\\s*\\(")[0].trim() : "";
        }
        return displayName.split("\\s+")[0];
    }
    private static String splitLast(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "";
        if (displayName.contains(",")) {
            return displayName.split(",", 2)[0].trim();
        }
        String[] parts = displayName.split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    /** Cell → string regardless of underlying numeric/string type. */
    private static String getCellAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue(); }
                catch (Exception e) { try { return String.valueOf((long) cell.getNumericCellValue()); }
                                       catch (Exception e2) { return ""; } }
            default: return "";
        }
    }

    /**
     * Header-row heuristic — same shape as the agile-service one. Scans
     * the first 10 rows, scores by (non-empty + short-text + keyword hits
     * - numeric-only), picks the highest.
     */
    private static int findHeaderRow(org.apache.poi.ss.usermodel.Sheet sheet) {
        int last = Math.min(9, sheet.getLastRowNum());
        int bestRow = -1, bestScore = 0;
        String[] kw = {"user", "id", "name", "email", "department", "title", "manager",
            "role", "status", "bpo", "phone", "country", "city", "first", "last"};
        for (int i = 0; i <= last; i++) {
            org.apache.poi.ss.usermodel.Row r = sheet.getRow(i);
            if (r == null) continue;
            int nonEmpty = 0, shortText = 0, keywordHits = 0;
            int lastCol = r.getLastCellNum();
            for (int c = 0; c < lastCol; c++) {
                org.apache.poi.ss.usermodel.Cell cell = r.getCell(c);
                if (cell == null) continue;
                String v = getCellAsString(cell).trim();
                if (v.isEmpty()) continue;
                nonEmpty++;
                if (v.length() <= 30) shortText++;
                String vLow = v.toLowerCase();
                for (String k : kw) if (vLow.contains(k)) { keywordHits++; break; }
            }
            int score = nonEmpty * 2 + shortText * 3 + keywordHits * 8;
            if (nonEmpty == 1) score = 5;
            if (score > bestScore) { bestScore = score; bestRow = i; }
        }
        return bestScore >= 10 ? bestRow : (bestRow >= 0 ? bestRow : 0);
    }

    /**
     * GET /api/admin/build-info — return the SHA-256 + build timestamp of the
     * running JAR. Used by the feedback-queue auto-mark-done logic and by
     * external pollers to identify what version is currently live.
     *
     * Open to any logged-in user (NOT admin-only) because the queue UI may
     * show "auto-resolved by deploy on YYYY-MM-DD" badges to anyone viewing
     * a Done ticket.
     */
    @GetMapping("/build-info")
    public Map<String, Object> buildInfo(HttpSession session) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (session.getAttribute("username") == null) {
            r.put("success", false);
            r.put("message", "Not authenticated.");
            return r;
        }
        r.put("success", true);
        r.put("jarSha256", buildInfoService.getJarSha256());
        r.put("builtAt", buildInfoService.getBuiltAt());
        r.put("version", buildInfoService.getVersion());
        r.put("jarSizeBytes", buildInfoService.getJarSizeBytes());
        return r;
    }

    @GetMapping("/item-cache/status")
    public Map<String, Object> itemCacheStatus() {
        return itemCacheService.getStatus();
    }

    @GetMapping("/item-cache/sample-dates")
    public List<Map<String, String>> itemCacheSampleDates() {
        return itemCacheService.getSampleDates(5);
    }

    @PostMapping("/item-cache/seed")
    public Map<String, Object> itemCacheSeed(HttpSession session) {
        if (!isAdmin(session)) return Map.of("error", "Admin only");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            int count = itemCacheService.seed();
            result.put("ok", true);
            result.put("records", count);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/item-cache/delta")
    public Map<String, Object> itemCacheDelta(HttpSession session) {
        if (!isAdmin(session)) return Map.of("error", "Admin only");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            int count = itemCacheService.delta();
            result.put("ok", true);
            result.put("updated", count);
            result.put("total", itemCacheService.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
    }
}
