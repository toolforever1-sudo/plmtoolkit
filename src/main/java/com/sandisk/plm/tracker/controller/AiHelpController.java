package com.sandisk.plm.tracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.service.ActivityLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/help")
public class AiHelpController {

    private static final Logger logger = Logger.getLogger(AiHelpController.class.getName());

    @Value("${ai.help.api-key:}")
    private String apiKey;

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

    @Value("${mail.smtp.host:}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from:}")
    private String fromAddress;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private com.sandisk.plm.tracker.service.SessionRegistry sessionRegistry;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("customDataSource")
    private javax.sql.DataSource customDataSource;

    @Autowired
    private com.sandisk.plm.tracker.service.ExternalSourceService externalSourceService;

    @Autowired
    private com.sandisk.plm.tracker.service.SavedChatQueryService savedChatQueryService;

    @Autowired
    private com.sandisk.plm.tracker.service.HelpDocsService helpDocsService;

    @Autowired
    private com.sandisk.plm.tracker.service.ItemCacheService itemCacheService;

    @Autowired
    private com.sandisk.plm.tracker.service.SkuDataService skuDataService;

    @Autowired
    private com.sandisk.plm.tracker.service.PortkeyClient portkeyClient;

    @Autowired
    private com.sandisk.plm.tracker.service.UserPermissionsService userPermissionsService;

    /** Model for Engineering mode (engineering-insider role) — pinned to Claude
     *  Opus 4.8. Full Portkey/Vortex slug; config-overridable if the slug changes. */
    @Value("${app.help.engineering.model:@vertexai-global/anthropic.claude-opus-4-8}")
    private String engineeringModel;

    // Last data query SQL per user — for follow-up context
    private final java.util.concurrent.ConcurrentHashMap<String, String> lastDataSql = new java.util.concurrent.ConcurrentHashMap<>();
    // Last external source query per user — for follow-up context (sourceId|column|filterValue)
    private final java.util.concurrent.ConcurrentHashMap<String, String> lastExtQuery = new java.util.concurrent.ConcurrentHashMap<>();
    // Last looked-up item numbers per user — for follow-ups like "also show X for these"
    private final java.util.concurrent.ConcurrentHashMap<String, List<String>> lastItemLookup = new java.util.concurrent.ConcurrentHashMap<>();

    // Chat interaction log for hourly digest
    private final java.util.List<ChatInteraction> chatLog = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile long lastChatDigestTime = System.currentTimeMillis();

    static class ChatInteraction {
        String username;
        String displayName;
        String tab;
        String question;
        String answer;
        boolean wasAi; // true = Haiku answered, false = keyword fallback
        long timestamp;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000) // every hour
    public void sendChatDigest() {
        if (chatLog.isEmpty()) return;

        // Grab and clear
        java.util.List<ChatInteraction> batch;
        synchronized (chatLog) {
            batch = new java.util.ArrayList<>(chatLog);
            chatLog.clear();
        }

        try {
            StringBuilder html = new StringBuilder();
            html.append("<html><body style='font-family:Calibri,Arial,sans-serif; max-width:700px;'>");
            html.append("<div style='background:#1a3a5c; color:white; padding:16px 24px;'>");
            html.append("<h2 style='margin:0;'>Help Chatbot Interactions</h2>");
            html.append("<p style='margin:4px 0 0; color:#8bb8e8; font-size:13px;'>").append(batch.size()).append(" interactions in the last hour</p>");
            html.append("</div>");
            html.append("<div style='padding:16px 24px; background:white;'>");

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a");
            for (ChatInteraction ci : batch) {
                String modeTag = ci.wasAi ?
                    "<span style='background:#28a745; color:white; padding:1px 6px; border-radius:3px; font-size:10px;'>AI</span>" :
                    "<span style='background:#6c757d; color:white; padding:1px 6px; border-radius:3px; font-size:10px;'>Keyword</span>";
                html.append("<div style='border-bottom:1px solid #eee; padding:12px 0;'>");
                html.append("<div style='font-size:12px; color:#888;'>").append(sdf.format(new java.util.Date(ci.timestamp)));
                html.append(" &bull; <strong style='color:#1a3a5c;'>").append(esc(ci.displayName)).append("</strong>");
                html.append(" &bull; Tab: ").append(esc(ci.tab)).append(" &bull; ").append(modeTag).append("</div>");
                html.append("<div style='margin-top:6px;'><strong>Q:</strong> ").append(esc(ci.question)).append("</div>");
                String answerText = ci.answer != null && ci.answer.length() > 200 ? ci.answer.substring(0, 200) + "..." : ci.answer;
                html.append("<div style='margin-top:4px; color:#555;'><strong>A:</strong> ").append(esc(answerText)).append("</div>");
                html.append("</div>");
            }

            html.append("</div>");
            html.append("<div style='text-align:center; padding:12px; color:#999; font-size:11px;'>PLM Toolkit - Chatbot Digest</div>");
            html.append("</body></html>");

            // Send email
            java.util.Properties props = new java.util.Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            javax.mail.Session mailSession = javax.mail.Session.getInstance(props);
            javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(mailSession);
            message.setFrom(new javax.mail.internet.InternetAddress(fromAddress));
            message.setRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress("vikas.jindal@sandisk.com"));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("PLM Toolkit Chatbot Digest - " + batch.size() + " interactions"));
            message.setContent(html.toString(), "text/html; charset=utf-8");
            javax.mail.Transport.send(message);

            logger.info("[AI HELP] Chat digest sent: " + batch.size() + " interactions");
        } catch (Exception e) {
            logger.warning("[AI HELP] Failed to send chat digest: " + e.getMessage());
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private volatile String knowledgeBase = null;
    private volatile long knowledgeBaseLastLoaded = 0;
    private static final long KB_RELOAD_INTERVAL = 24 * 60 * 60 * 1000; // 24 hours

    private String getKnowledgeBase() {
        long now = System.currentTimeMillis();
        // Reload if not loaded or older than 24 hours
        if (knowledgeBase == null || (now - knowledgeBaseLastLoaded) > KB_RELOAD_INTERVAL) {
            loadKnowledgeBase();
        }
        return knowledgeBase;
    }

    private synchronized void loadKnowledgeBase() {
        // Always read the bundled KB from inside the JAR. This guarantees the bot
        // is in sync with whatever shipped in the deployed JAR — no stale external
        // file can override it.
        try (InputStream is = getClass().getResourceAsStream("/static/app-knowledge.txt")) {
            if (is != null) {
                knowledgeBase = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                logger.info("[AI HELP] Loaded knowledge base from JAR: " + knowledgeBase.length() + " chars");
            } else {
                logger.warning("[AI HELP] Bundled knowledge base /static/app-knowledge.txt not found in JAR");
                if (knowledgeBase == null) knowledgeBase = "";
            }
            knowledgeBaseLastLoaded = System.currentTimeMillis();
        } catch (Exception e) {
            logger.warning("[AI HELP] Failed to load knowledge base: " + e.getMessage());
            if (knowledgeBase == null) knowledgeBase = "";
        }
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("aiEnabled", aiEnabled && apiKey != null && !apiKey.isEmpty());
        return response;
    }

    /**
     * POST /api/help/export-query — re-runs a data query SQL and returns Excel.
     */
    @PostMapping("/export-query")
    public void exportQuery(@RequestBody Map<String, String> request,
                            HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        String sql = request.get("sql");
        if (sql == null || sql.isEmpty()) { response.sendError(400, "No SQL"); return; }

        // Safety: same checks as tryDataQuery
        String upper = sql.toUpperCase().trim();
        if (!upper.startsWith("SELECT") || upper.contains("UPDATE ") || upper.contains("DELETE ")
            || upper.contains("INSERT ") || upper.contains("DROP ") || upper.contains("ALTER ")) {
            response.sendError(400, "Invalid query");
            return;
        }

        // Remove FETCH FIRST limit for full export, add a 10K cap
        sql = sql.replaceAll("(?i)FETCH\\s+FIRST\\s+\\d+\\s+ROWS\\s+ONLY", "FETCH FIRST 10000 ROWS ONLY");

        List<String> columns = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        try (java.sql.Connection conn = customDataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));
                while (rs.next() && rows.size() < 10000) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        row.put(columns.get(i - 1), val != null ? val : "");
                    }
                    rows.add(row);
                }
            }
        }

        // Build Excel
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.createSheet("Query Results");

        // Header row
        org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        for (int r = 0; r < rows.size(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            Map<String, String> data = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                row.createCell(c).setCellValue(data.getOrDefault(columns.get(c), ""));
            }
        }

        String filename = "PLM-Query-" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        wb.write(response.getOutputStream());
        wb.close();

        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        String displayName = session != null ? (String) session.getAttribute("displayName") : "unknown";
        activityLogger.log(username, displayName, "AI_DATA_EXPORT", "Exported " + rows.size() + " rows");
        logger.info("[AI HELP] Data export: " + rows.size() + " rows by " + username);
    }

    /**
     * POST /api/help/clear-query — clears the user's data query context so next question starts fresh.
     */
    /**
     * POST /api/help/export-ext — export external source search results to Excel.
     */
    @PostMapping("/export-ext")
    public void exportExtSource(@RequestBody Map<String, String> request,
                                 HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        String paramsB64 = request.get("params");
        if (paramsB64 == null) { response.sendError(400); return; }
        String decoded = new String(java.util.Base64.getDecoder().decode(paramsB64), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", -1);
        String sourceId = parts[0];
        String column = parts.length > 1 ? parts[1] : "";
        String filterValue = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;

        List<Map<String, String>> rows = externalSourceService.searchRecords(sourceId, column, filterValue, 50000);
        if (rows.isEmpty()) { response.sendError(404, "No results"); return; }

        List<String> columns = new ArrayList<>(rows.get(0).keySet());

        org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.createSheet("Results");
        org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
        hdrFont.setBold(true); hdrStyle.setFont(hdrFont);

        org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            org.apache.poi.ss.usermodel.Cell cell = hdrRow.createCell(i);
            cell.setCellValue(columns.get(i)); cell.setCellStyle(hdrStyle);
        }
        for (int r = 0; r < rows.size(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            Map<String, String> data = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                row.createCell(c).setCellValue(data.getOrDefault(columns.get(c), ""));
            }
        }

        String filename = "PLM-ExtSource-" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        wb.write(response.getOutputStream());
        wb.close();

        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        activityLogger.log(username, (String) session.getAttribute("displayName"),
            "AI_EXT_EXPORT", "Exported " + rows.size() + " rows from " + sourceId);
    }

    /**
     * POST /api/help/feedback — user reports an unhelpful AI response.
     * Sends email to admin team with the question, answer, and context.
     */
    @PostMapping("/feedback")
    public Map<String, String> reportUnhelpful(@RequestBody Map<String, String> body, HttpSession session) {
        String question = body.getOrDefault("question", "");
        String answer = body.getOrDefault("answer", "");
        String tab = body.getOrDefault("tab", "");
        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        String displayName = session != null ? (String) session.getAttribute("displayName") : "unknown";
        String userEmail = session != null ? (String) session.getAttribute("email") : "";

        try {
            // Send email to admin team
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            javax.mail.Session mailSession = javax.mail.Session.getInstance(props);
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(mailSession);
            msg.setFrom(new javax.mail.internet.InternetAddress(fromAddress));
            msg.setRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress("pdl-plm-admin@sandisk.com"));
            if (userEmail != null && !userEmail.isEmpty()) {
                msg.setRecipient(javax.mail.Message.RecipientType.CC, new javax.mail.internet.InternetAddress(userEmail));
            }
            msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("AI Help Feedback \u00b7 " + displayName + " \u00b7 needs improvement"));

            String html = "<div style='font-family:IBM Plex Sans,sans-serif;max-width:600px;margin:0 auto;'>" +
                "<div style='background:#1a3a5c;color:#fff;padding:12px 20px;border-radius:8px 8px 0 0;font-size:14px;font-weight:600;'>AI Help \u2014 Needs Improvement</div>" +
                "<div style='border:1px solid #e0e0e0;border-top:none;padding:20px;border-radius:0 0 8px 8px;'>" +
                "<p style='margin:0 0 6px;font-size:11px;color:#888;text-transform:uppercase;letter-spacing:.08em;'>User</p>" +
                "<p style='margin:0 0 16px;font-weight:600;'>" + escHtml(displayName) + " (" + escHtml(username) + ") on <strong>" + escHtml(tab) + "</strong> tab</p>" +
                "<p style='margin:0 0 6px;font-size:11px;color:#888;text-transform:uppercase;letter-spacing:.08em;'>Question</p>" +
                "<div style='background:#f5f5f5;padding:10px 14px;border-radius:6px;margin:0 0 16px;font-size:13px;'>" + escHtml(question) + "</div>" +
                "<p style='margin:0 0 6px;font-size:11px;color:#888;text-transform:uppercase;letter-spacing:.08em;'>AI Answer (flagged as unhelpful)</p>" +
                "<div style='background:#fff3f3;border-left:3px solid #dc3545;padding:10px 14px;border-radius:0 6px 6px 0;margin:0 0 16px;font-size:13px;color:#333;'>" + answer + "</div>" +
                "<p style='font-size:12px;color:#888;'>Improve the knowledge base or AI prompt to handle this query better.</p>" +
                "</div></div>";

            msg.setContent(html, "text/html; charset=utf-8");
            javax.mail.Transport.send(msg);
            logger.info("[AI HELP] Feedback sent by " + username + ": " + question.substring(0, Math.min(question.length(), 60)));
        } catch (Exception e) {
            logger.warning("[AI HELP] Feedback email failed: " + e.getMessage());
        }

        activityLogger.log(username, displayName, "AI_FEEDBACK", "Q: " + question.substring(0, Math.min(question.length(), 80)));
        return Map.of("ok", "Feedback sent to admin team");
    }

    // ── Saved Chat Queries CRUD ──

    @GetMapping("/queries")
    public Map<String, Object> getQueries(HttpSession session) {
        String username = session != null ? (String) session.getAttribute("username") : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mine", username != null ? savedChatQueryService.getQueries(username) : Collections.emptyList());
        result.put("shared", username != null ? savedChatQueryService.getSharedQueries(username) : Collections.emptyList());
        return result;
    }

    @PostMapping("/queries")
    public Map<String, Object> saveQuery(@RequestBody Map<String, String> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        String name = body.getOrDefault("name", "Untitled query");
        String sql = body.get("sql");
        if (sql == null || sql.isEmpty()) return Map.of("error", "No SQL provided");

        com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery q =
            savedChatQueryService.saveQuery(username, displayName, name, sql);
        activityLogger.log(username, displayName, "CHAT_QUERY_SAVE", "Saved: " + name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("query", q);
        return result;
    }

    @DeleteMapping("/queries/{id}")
    public Map<String, String> deleteQuery(@PathVariable String id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        savedChatQueryService.deleteQuery(username, id);
        return Map.of("ok", "Deleted");
    }

    @PostMapping("/queries/{id}/share")
    public Map<String, Object> toggleShareQuery(@PathVariable String id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery q = savedChatQueryService.toggleShare(username, id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (q != null) { result.put("ok", true); result.put("shared", q.shared); }
        else result.put("error", "Not found");
        return result;
    }

    @PostMapping("/queries/{id}/schedule")
    public Map<String, Object> scheduleQuery(@PathVariable String id, @RequestBody Map<String, String> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String email = (String) session.getAttribute("email");
        com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery q = savedChatQueryService.setSchedule(
            username, id,
            body.getOrDefault("freq", ""),
            body.getOrDefault("day", ""),
            body.getOrDefault("time", ""),
            body.getOrDefault("email", email)
        );
        Map<String, Object> result = new LinkedHashMap<>();
        if (q != null) { result.put("ok", true); result.put("query", q); }
        else result.put("error", "Not found");
        return result;
    }

    /**
     * POST /api/help/queries/{id}/run — executes a saved query and returns JSON results (for chat display).
     */
    @PostMapping("/queries/{id}/run")
    @SuppressWarnings("unchecked")
    public Map<String, Object> runSavedQuery(@PathVariable String id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery q = savedChatQueryService.findById(id);
        if (q == null) { result.put("error", "Query not found"); return result; }

        String username = session != null ? (String) session.getAttribute("username") : null;

        // ── Item Cache path ──
        if (q.sql != null && q.sql.startsWith("JSON_CACHE:") && itemCacheService.isLoaded()) {
            try {
                String filterJson = q.sql.substring(11);
                Map<String, Object> spec = new ObjectMapper().readValue(filterJson, Map.class);
                List<String> selectCols = (List<String>) spec.getOrDefault("select", Arrays.asList("PART_NUMBER", "DESCRIPTION", "REV"));
                List<Map<String, Object>> filters = (List<Map<String, Object>>) spec.getOrDefault("filters", Collections.emptyList());
                Map<String, String> sort = (Map<String, String>) spec.get("sort");

                // Try indexed lookup first, fall back to scan
                Set<String> indexedMatches = tryIndexedLookup(filters);
                int totalCount;
                List<Map<String, String>> allMatches;
                if (indexedMatches != null) {
                    totalCount = indexedMatches.size();
                    allMatches = new ArrayList<>();
                    for (String pn : indexedMatches) {
                        Map<String, String> rec = itemCacheService.getRecord(pn);
                        if (rec != null) allMatches.add(rec);
                        if (allMatches.size() >= 50) break;
                    }
                } else {
                    java.util.function.BiPredicate<String, Map<String, String>> predicate = buildCachePredicate(filters);
                    totalCount = itemCacheService.count(predicate);
                    allMatches = itemCacheService.search(predicate, 50);
                }

                if (sort != null && sort.get("col") != null) {
                    String sortCol = sort.get("col").toUpperCase();
                    boolean desc = "desc".equalsIgnoreCase(sort.get("dir"));
                    allMatches.sort((a, b) -> {
                        String va = a.getOrDefault(sortCol, "");
                        String vb = b.getOrDefault(sortCol, "");
                        return desc ? vb.compareToIgnoreCase(va) : va.compareToIgnoreCase(vb);
                    });
                }

                List<Map<String, String>> preview = allMatches;
                result.put("columns", selectCols);
                result.put("rows", preview);
                result.put("totalCount", totalCount);
                result.put("sql", q.sql);
                result.put("name", q.name);
                result.put("source", "cache");
                if (username != null) lastDataSql.put(username, q.sql);
                return result;
            } catch (Exception e) {
                result.put("error", "Cache query failed: " + e.getMessage());
                return result;
            }
        }

        // ── SQL path ──
        String sql = q.sql;
        sql = sql.replaceAll("(?i)FETCH\\s+FIRST\\s+\\d+\\s+ROWS\\s+ONLY", "FETCH FIRST 50 ROWS ONLY");
        if (!sql.toUpperCase().contains("FETCH FIRST")) sql += " FETCH FIRST 50 ROWS ONLY";

        try {
            List<String> columns = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();

            try (java.sql.Connection conn = customDataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(30);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));
                    while (rs.next() && rows.size() < 50) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            String val = rs.getString(i);
                            row.put(columns.get(i - 1), val != null ? val : "");
                        }
                        rows.add(row);
                    }
                }
            }

            int totalCount = rows.size();
            if (rows.size() >= 50) {
                try {
                    String countSql = "SELECT COUNT(*) FROM (" + q.sql.replaceAll("(?i)FETCH\\s+FIRST\\s+\\d+\\s+ROWS\\s+ONLY", "") + ")";
                    try (java.sql.Connection conn2 = customDataSource.getConnection();
                         java.sql.PreparedStatement ps2 = conn2.prepareStatement(countSql)) {
                        ps2.setQueryTimeout(15);
                        try (java.sql.ResultSet rs2 = ps2.executeQuery()) {
                            if (rs2.next()) totalCount = rs2.getInt(1);
                        }
                    }
                } catch (Exception ignored) {}
            }

            result.put("columns", columns);
            result.put("rows", rows);
            result.put("totalCount", totalCount);
            result.put("sql", q.sql);
            result.put("name", q.name);
            result.put("source", "sql");
            if (username != null) lastDataSql.put(username, q.sql);

        } catch (Exception e) {
            result.put("error", "Query failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * POST /api/help/export-cache — export Item Cache query results to Excel.
     */
    @PostMapping("/export-cache")
    public void exportCacheQuery(@RequestBody Map<String, String> request,
                                  HttpSession session, javax.servlet.http.HttpServletResponse response) throws Exception {
        String filterB64 = request.get("filter");
        String colsB64 = request.get("cols");
        if (filterB64 == null || colsB64 == null) { response.sendError(400); return; }

        long exportT0 = System.currentTimeMillis();
        String filterJson = new String(java.util.Base64.getDecoder().decode(filterB64), java.nio.charset.StandardCharsets.UTF_8);
        String colsStr = new String(java.util.Base64.getDecoder().decode(colsB64), java.nio.charset.StandardCharsets.UTF_8);
        List<String> selectCols = new ArrayList<>(Arrays.asList(colsStr.split(",")));

        // Parse filter spec and build predicate (same logic as tryCacheQuery)
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = new ObjectMapper().readValue(filterJson, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) spec.getOrDefault("filters", Collections.emptyList());

        // Try indexed lookup first for speed, fall back to scan
        List<Map<String, String>> rows;
        Set<String> indexedMatches = tryIndexedLookup(filters);
        if (indexedMatches != null) {
            rows = new ArrayList<>(indexedMatches.size());
            for (String pn : indexedMatches) {
                Map<String, String> rec = itemCacheService.getRecord(pn);
                if (rec != null) rows.add(rec);
            }
            logger.info("[AI HELP] Cache export: indexed lookup (" + rows.size() + " rows)");
        } else {
            java.util.function.BiPredicate<String, Map<String, String>> predicate = buildCachePredicate(filters);
            rows = itemCacheService.search(predicate, Integer.MAX_VALUE);
            logger.info("[AI HELP] Cache export: full scan (" + rows.size() + " rows)");
        }

        // Sort if requested
        @SuppressWarnings("unchecked")
        Map<String, String> sort = (Map<String, String>) spec.get("sort");
        if (sort != null && sort.get("col") != null) {
            String sortCol = sort.get("col").toUpperCase();
            boolean desc = "desc".equalsIgnoreCase(sort.get("dir"));
            rows.sort((a, b) -> {
                String va = a.getOrDefault(sortCol, "");
                String vb = b.getOrDefault(sortCol, "");
                return desc ? vb.compareToIgnoreCase(va) : va.compareToIgnoreCase(vb);
            });
        }

        // Build Excel using streaming (SXSSFWorkbook) for speed
        org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Query Results");
        org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
        hdrFont.setBold(true); hdrStyle.setFont(hdrFont);

        org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
        for (int i = 0; i < selectCols.size(); i++) {
            org.apache.poi.ss.usermodel.Cell cell = hdrRow.createCell(i);
            cell.setCellValue(selectCols.get(i)); cell.setCellStyle(hdrStyle);
        }
        for (int r = 0; r < rows.size(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            Map<String, String> data = rows.get(r);
            for (int c = 0; c < selectCols.size(); c++) {
                row.createCell(c).setCellValue(data.getOrDefault(selectCols.get(c).toUpperCase(), ""));
            }
        }

        long exportMs = System.currentTimeMillis() - exportT0;
        int rowCount = rows.size();
        String filename = "PLM-Query-" + rowCount + "rows-" + exportMs + "ms-" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setHeader("X-Export-Time-Ms", String.valueOf(exportMs));
        response.setHeader("X-Export-Rows", String.valueOf(rowCount));
        response.setHeader("Access-Control-Expose-Headers", "X-Export-Time-Ms, X-Export-Rows");
        wb.write(response.getOutputStream());
        wb.dispose(); // clean up temp files
        wb.close();

        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        activityLogger.log(username, (String) session.getAttribute("displayName"),
            "AI_DATA_EXPORT", "Exported " + rowCount + " rows from Item Cache in " + exportMs + "ms");
        logger.info("[AI HELP] Cache export: " + rowCount + " rows in " + exportMs + "ms");
    }

    /** Build a zero-result message with filter details and teaching prompt. */
    @SuppressWarnings("unchecked")
    private String buildZeroResultMessage(List<Map<String, Object>> filters, long cacheMs) {
        String filterSummary = buildFilterSummary(filters);
        StringBuilder html = new StringBuilder();
        html.append("<p><strong>0</strong> items match your query.</p>");
        html.append("<div style='background:#fff8e1;border:1px solid #e6a817;border-radius:6px;padding:12px 16px;margin:8px 0;'>");
        html.append("<p style='margin:0 0 6px;font-weight:600;color:#856404;'>\u26A0 No matches found</p>");
        html.append("<p style='margin:0 0 8px;font-size:12px;color:#555;font-family:monospace;'>").append(escHtml(filterSummary)).append("</p>");
        html.append("<p style='margin:0;font-size:12.5px;color:#333;'>The filter values might not match what's in the database. You can:</p>");
        html.append("<ul style='margin:6px 0 0;font-size:12.5px;color:#555;'>");
        html.append("<li>Tell me the correct value, e.g. <em>\"resistor means E011-Resistor\"</em> and I'll remember it permanently</li>");
        html.append("<li>Try a broader search, e.g. <em>\"use like/contains instead of exact match\"</em></li>");
        html.append("<li>Ask <em>\"what are the unique values for NEW_PART_CLASS?\"</em> to see what's available</li>");
        html.append("</ul></div>");
        html.append("<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>");
        html.append("<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>");
        html.append("<span style='font-size:11px;color:#28a745;'>\u26A1 Item Cache \u00b7 ").append(cacheMs).append("ms \u00b7 indexed</span></div>");
        return html.toString();
    }

    /** Build a human-readable summary of the filter spec. */
    @SuppressWarnings("unchecked")
    private String buildFilterSummary(List<Map<String, Object>> filters) {
        StringBuilder sb = new StringBuilder("Filter: ");
        for (int i = 0; i < filters.size(); i++) {
            if (i > 0) sb.append(" AND ");
            Map<String, Object> f = filters.get(i);
            String col = (String) f.get("col");
            String op = (String) f.get("op");
            Object val = f.get("val");
            sb.append(col).append(" ");
            switch (op) {
                case "eq": sb.append("= '").append(val).append("'"); break;
                case "neq": sb.append("!= '").append(val).append("'"); break;
                case "like": sb.append("contains '").append(val).append("'"); break;
                case "in": sb.append("in ").append(val); break;
                case "notin": sb.append("not in ").append(val); break;
                case "gte": sb.append(">= '").append(val).append("'"); break;
                case "gt": sb.append("> '").append(val).append("'"); break;
                case "lte": sb.append("<= '").append(val).append("'"); break;
                case "lt": sb.append("< '").append(val).append("'"); break;
                case "notnull": sb.append("is not empty"); break;
                case "null": sb.append("is empty"); break;
                default: sb.append(op).append(" '").append(val).append("'");
            }
        }
        return sb.toString();
    }

    // ── Learnings persistence ──
    private static final String LEARNINGS_FILE = "./data/ai-learnings.json";

    private Map<String, String> loadLearnings() {
        try {
            java.io.File f = new java.io.File(LEARNINGS_FILE);
            if (f.exists()) {
                return new ObjectMapper().readValue(f, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            }
        } catch (Exception ignored) {}
        return new LinkedHashMap<>();
    }

    private void saveLearning(String key, String value) {
        try {
            Map<String, String> learnings = loadLearnings();
            learnings.put(key.toLowerCase(), value);
            java.io.File f = new java.io.File(LEARNINGS_FILE);
            f.getParentFile().mkdirs();
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(f, learnings);
            logger.info("[AI HELP] Saved learning: " + key + " → " + value);
        } catch (Exception e) {
            logger.warning("[AI HELP] Failed to save learning: " + e.getMessage());
        }
    }

    private String getLearningsContext() {
        Map<String, String> learnings = loadLearnings();
        if (learnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nUser-defined mappings (use these for queries):\n");
        for (Map.Entry<String, String> e : learnings.entrySet()) {
            sb.append("- '").append(e.getKey()).append("' means NEW_PART_CLASS like '").append(e.getValue()).append("'\n");
        }
        return sb.toString();
    }

    /** Try to resolve filters using indexes. Returns matching part numbers, or null if not fully indexable. */
    @SuppressWarnings("unchecked")
    private Set<String> tryIndexedLookup(List<Map<String, Object>> filters) {
        Set<String> result = null;
        for (Map<String, Object> f : filters) {
            String col = ((String) f.get("col")).toUpperCase();
            String op = (String) f.get("op");
            Object val = f.get("val");
            Set<String> filterResult = null;

            if ("eq".equals(op) && itemCacheService.isIndexed(col)) {
                filterResult = itemCacheService.indexedLookup(col, String.valueOf(val));
            } else if ("like".equals(op) && itemCacheService.isIndexed(col)) {
                // Scan index values for contains match
                filterResult = itemCacheService.indexedLookupLike(col, String.valueOf(val));
            } else if ("in".equals(op) && itemCacheService.isIndexed(col)) {
                filterResult = itemCacheService.indexedLookupIn(col, (List<String>) val);
            } else if ("like".equals(op) && "PART_NUMBER".equals(col)) {
                // Prefix search on part number
                filterResult = itemCacheService.prefixLookup(String.valueOf(val));
            } else if (("gte".equals(op) || "gt".equals(op) || "lt".equals(op) || "lte".equals(op)) && ("REV_RELEASE_DATE".equals(col) || "CREATE_DATE".equals(col))) {
                long filterEpoch = com.sandisk.plm.tracker.service.ItemCacheService.parseFilterDateToEpoch(String.valueOf(val));
                if (filterEpoch > 0) {
                    if ("gte".equals(op) || "gt".equals(op)) {
                        filterResult = itemCacheService.dateRangeLookup(col, filterEpoch);
                    } else {
                        // lt/lte: all dated records MINUS those >= filterEpoch
                        Set<String> gte = itemCacheService.dateRangeLookup(col, filterEpoch);
                        Set<String> allDated = itemCacheService.dateRangeLookup(col, 0); // all records with a date
                        if (gte != null && allDated != null) {
                            filterResult = new HashSet<>(allDated);
                            filterResult.removeAll(gte);
                            if ("lte".equals(op)) {
                                // lte includes the boundary — add back exact matches
                                // (handled by the gte set already excluding them, so just use the set as-is)
                            }
                        }
                    }
                }
            } else if ("neq".equals(op) && itemCacheService.isIndexed(col)) {
                Set<String> excluded = itemCacheService.indexedLookup(col, String.valueOf(val));
                if (excluded != null) {
                    filterResult = new HashSet<>(itemCacheService.allPartNumbers());
                    filterResult.removeAll(excluded);
                }
            } else if ("notin".equals(op) && itemCacheService.isIndexed(col)) {
                Set<String> excluded = itemCacheService.indexedLookupIn(col, (List<String>) val);
                if (excluded != null) {
                    filterResult = new HashSet<>(itemCacheService.allPartNumbers());
                    filterResult.removeAll(excluded);
                }
            } else {
                return null; // not fully indexable
            }

            if (filterResult != null) {
                result = (result == null) ? new HashSet<>(filterResult) : intersect(result, filterResult);
            } else {
                return null;
            }
        }
        return result;
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        if (a.size() > b.size()) { Set<String> tmp = a; a = b; b = tmp; }
        Set<String> result = new HashSet<>();
        for (String s : a) { if (b.contains(s)) result.add(s); }
        return result;
    }

    /**
     * Enrich records with external source data. Checks if any selectCols exist in external sources
     * but not in the item cache. If found, joins external data by PART_NUMBER.
     * Returns the enriched selectCols list (may add columns).
     */
    private List<String> enrichWithExternalSources(List<Map<String, String>> records, List<String> selectCols, String question) {
        List<String> enrichedCols = new ArrayList<>(selectCols);
        String lower = question.toLowerCase();

        // Check if user mentions external source fields or source names
        List<Map<String, String>> sources = externalSourceService.getSourceList();
        Map<String, String> colsToEnrich = new LinkedHashMap<>(); // column -> sourceId

        for (Map<String, String> src : sources) {
            String srcId = src.get("id");
            String srcName = src.get("name");
            List<String> headers = externalSourceService.getSourceHeaders(srcId);

            // Check if user mentioned this source name
            boolean mentionedSource = lower.contains(srcName.toLowerCase()) || lower.contains(srcId.toLowerCase());

            for (String h : headers) {
                String hLower = h.toLowerCase();
                String hFriendly = hLower.replace("_", " ");
                // Add column if user mentioned the source or the specific column
                if (mentionedSource || lower.contains(hFriendly) || lower.contains(hLower)) {
                    if (!itemCacheService.hasColumn(h) && !enrichedCols.contains(h)) {
                        colsToEnrich.put(h, srcId);
                    }
                }
            }
        }

        if (colsToEnrich.isEmpty()) return enrichedCols;

        // Add external columns to select list
        for (String col : colsToEnrich.keySet()) {
            enrichedCols.add(col);
        }

        // Enrich each record
        for (Map<String, String> rec : records) {
            String pn = rec.get("PART_NUMBER");
            if (pn == null) continue;
            for (Map.Entry<String, String> entry : colsToEnrich.entrySet()) {
                Map<String, String> extRec = externalSourceService.getRecord(entry.getValue(), pn);
                if (extRec != null) {
                    String val = extRec.get(entry.getKey());
                    if (val != null) rec.put(entry.getKey(), val);
                }
            }
        }

        return enrichedCols;
    }

    @SuppressWarnings("unchecked")
    private String buildCacheResultHtml(List<Map<String, String>> preview, List<String> selectCols,
            int totalCount, long cacheMs, String filterJson, String username, String displayName,
            String question, Map<String, String> sort) {
        // Enrich with external source data if user asked for columns from external sources
        selectCols = enrichWithExternalSources(preview, selectCols, question);

        // Sort preview if requested (numeric sort for REV, CAPACITY, etc.)
        if (sort != null && sort.get("col") != null) {
            String sortCol = sort.get("col").toUpperCase();
            boolean desc = "desc".equalsIgnoreCase(sort.get("dir"));
            boolean numericSort = "REV".equals(sortCol) || "CAPACITY".equals(sortCol);
            preview.sort((a, b) -> {
                String va = a.getOrDefault(sortCol, "");
                String vb = b.getOrDefault(sortCol, "");
                if (numericSort) {
                    try {
                        int na = va.isEmpty() ? 0 : Integer.parseInt(va.trim());
                        int nb = vb.isEmpty() ? 0 : Integer.parseInt(vb.trim());
                        return desc ? Integer.compare(nb, na) : Integer.compare(na, nb);
                    } catch (NumberFormatException e) { /* fall through to string compare */ }
                }
                if (sortCol.contains("DATE")) {
                    try {
                        long ea = com.sandisk.plm.tracker.service.ItemCacheService.parseDateToEpoch(va);
                        long eb = com.sandisk.plm.tracker.service.ItemCacheService.parseDateToEpoch(vb);
                        return desc ? Long.compare(eb, ea) : Long.compare(ea, eb);
                    } catch (Exception e) { /* fall through */ }
                }
                return desc ? vb.compareToIgnoreCase(va) : va.compareToIgnoreCase(vb);
            });
        }

        StringBuilder html = new StringBuilder();
        html.append("<p><strong>");
        if (totalCount > preview.size()) {
            html.append(totalCount).append("</strong> total results (showing first ").append(preview.size()).append("):");
        } else {
            html.append(preview.size()).append("</strong> results:");
        }
        html.append("</p>");
        // Show filter summary
        try {
            Map<String, Object> specForSummary = new ObjectMapper().readValue(filterJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filtersForSummary = (List<Map<String, Object>>) specForSummary.getOrDefault("filters", Collections.emptyList());
            html.append("<p style='font-size:11px;color:#888;margin:0 0 8px;font-family:monospace;'>").append(escHtml(buildFilterSummary(filtersForSummary))).append("</p>");
        } catch (Exception ignored) {}

        html.append("<div style='overflow-x:auto;margin:8px 0;'>");
        html.append("<table style='border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;'>");
        html.append("<tr>");
        for (String col : selectCols) {
            html.append("<th style='text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;white-space:nowrap;font-size:10px;color:#666;'>")
                .append(escHtml(col)).append("</th>");
        }
        html.append("</tr>");
        for (Map<String, String> row : preview) {
            html.append("<tr>");
            for (String col : selectCols) {
                String val = row.getOrDefault(col.toUpperCase(), "");
                html.append("<td style='padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;'>")
                    .append(escHtml(val)).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table></div>");

        String filterB64 = java.util.Base64.getEncoder().encodeToString(filterJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String selectB64 = java.util.Base64.getEncoder().encodeToString(String.join(",", selectCols).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        html.append("<div style='margin-top:10px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;'>");
        html.append("<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>");
        html.append("<button onclick=\"saveChatQuery(this.dataset.sql,this)\" data-sql=\"")
            .append(java.util.Base64.getEncoder().encodeToString(("JSON_CACHE:" + filterJson).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .append("\" style='font-size:12px;padding:5px 12px;background:#fff;color:#e6a817;border:1px solid #e6a817;border-radius:5px;cursor:pointer;'>\u2605 Save</button>");
        html.append("<button onclick=\"exportCacheQuery(this.dataset.filter,this.dataset.cols,this)\" data-filter=\"")
            .append(filterB64).append("\" data-cols=\"").append(selectB64)
            .append("\" style='font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;'>\u2913 Export all ")
            .append(totalCount).append(" to Excel</button>");
        boolean hasExtCols = false;
for (String sc : selectCols) { if (!itemCacheService.hasColumn(sc)) { hasExtCols = true; break; } }
html.append("<span style='font-size:11px;color:#28a745;'>\u26A1 Item Cache").append(hasExtCols ? " + External Sources" : "").append(" \u00b7 ").append(cacheMs).append("ms \u00b7 indexed</span>");
        html.append("</div>");

        activityLogger.log(username, displayName, "AI_DATA_QUERY",
            "Q: " + question.substring(0, Math.min(question.length(), 80)) + " | Rows: " + totalCount + " | Source: JSON indexed");

        return html.toString();
    }

    /** Build a predicate from filter spec — shared by tryCacheQuery and exportCacheQuery */
    @SuppressWarnings("unchecked")
    private java.util.function.BiPredicate<String, Map<String, String>> buildCachePredicate(List<Map<String, Object>> filters) {
        return (key, rec) -> {
            for (Map<String, Object> f : filters) {
                String col = ((String) f.get("col")).toUpperCase();
                String op = (String) f.get("op");
                Object val = f.get("val");
                String recVal = rec.getOrDefault(col, "");

                switch (op) {
                    case "eq": if (!recVal.equalsIgnoreCase(String.valueOf(val))) return false; break;
                    case "neq": if (recVal.equalsIgnoreCase(String.valueOf(val))) return false; break;
                    case "like": if (!recVal.toUpperCase().contains(String.valueOf(val).toUpperCase())) return false; break;
                    case "in": {
                        List<String> vals = (List<String>) val;
                        boolean found = false;
                        for (String v : vals) { if (recVal.equalsIgnoreCase(v)) { found = true; break; } }
                        if (!found) return false;
                        break;
                    }
                    case "notin": {
                        List<String> vals = (List<String>) val;
                        for (String v : vals) { if (recVal.equalsIgnoreCase(v)) return false; }
                        break;
                    }
                    case "notnull": if (recVal.isEmpty()) return false; break;
                    case "null": if (!recVal.isEmpty()) return false; break;
                    case "gt": case "gte": case "lt": case "lte": {
                        // Use pre-computed epoch for instant comparison (no parsing during scan)
                        String epochKey = "_" + col + "_EPOCH";
                        String epochStr = rec.get(epochKey);
                        if (epochStr == null || epochStr.isEmpty()) {
                            // No pre-computed epoch — try parsing (slower fallback)
                            if (recVal.isEmpty()) return false;
                            long recEpoch = com.sandisk.plm.tracker.service.ItemCacheService.parseDateToEpoch(recVal);
                            if (recEpoch == 0) return false;
                            epochStr = String.valueOf(recEpoch);
                        }
                        long recEpoch = Long.parseLong(epochStr);
                        long filterEpoch = com.sandisk.plm.tracker.service.ItemCacheService.parseFilterDateToEpoch(String.valueOf(val));
                        if (filterEpoch == 0) return false;
                        if ("gt".equals(op) && recEpoch <= filterEpoch) return false;
                        if ("gte".equals(op) && recEpoch < filterEpoch) return false;
                        if ("lt".equals(op) && recEpoch >= filterEpoch) return false;
                        if ("lte".equals(op) && recEpoch > filterEpoch) return false;
                        break;
                    }
                }
            }
            return true;
        };
    }

    @PostMapping("/clear-query")
    public Map<String, String> clearQuery(HttpSession session) {
        String username = session != null ? (String) session.getAttribute("username") : null;
        if (username != null) {
            lastDataSql.remove(username);
            lastExtQuery.remove(username);
            lastItemLookup.remove(username);
        }
        return Map.of("ok", "Query context cleared");
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> request, HttpSession session,
                                   javax.servlet.http.HttpServletResponse httpResponse) {
        Map<String, Object> response = new LinkedHashMap<>();

        String question = (String) request.get("question");
        String currentTab = (String) request.get("currentTab");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> chatHistory = (List<Map<String, String>>) request.getOrDefault("history", new ArrayList<>());

        if (question == null || question.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "No question provided.");
            return response;
        }

        String displayName = session != null ? (String) session.getAttribute("displayName") : "User";
        String username = session != null ? (String) session.getAttribute("username") : "unknown";
        boolean isAdmin = session != null && Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));

        // Engineering mode (engineering-insider role holders): every question goes
        // straight to the LLM with the dedicated system prompt — ALL deterministic
        // interceptors below (activity log, doc list, nav how-to, FAQ, data query)
        // are skipped. Role is validated server-side on every call; no silent
        // fallback to help mode.
        if ("engineering".equals(request.get("mode"))) {
            return askEngineering(question, chatHistory, username, displayName, httpResponse);
        }

        // Admin-only intercept: questions like "what did Prakash do?" are answered
        // directly from the activity log, bypassing the LLM. Persistent across restarts
        // because ActivityLogger reloads from ./data/activity-log.jsonl on boot.
        if (isAdmin) {
            String activityAnswer = tryAdminActivityQuery(question);
            if (activityAnswer != null) {
                response.put("success", true);
                response.put("answer", activityAnswer);
                response.put("source", "activity-log");
                ChatInteraction adminCi = new ChatInteraction();
                adminCi.username = username;
                adminCi.displayName = displayName;
                adminCi.tab = currentTab;
                adminCi.question = question;
                adminCi.answer = stripHtml(activityAnswer);
                adminCi.wasAi = false; // deterministic admin tool, not the LLM
                adminCi.timestamp = System.currentTimeMillis();
                chatLog.add(adminCi);
                return response;
            }
        }

        // Document listing: "what documents do you have", "list docs", "work instructions" / "help center"
        String qLower = question.toLowerCase();
        if (qLower.contains("what document") || qLower.contains("list doc") || qLower.contains("which document")
            || qLower.contains("available document") || qLower.contains("work instruction") || qLower.contains("help center") || qLower.contains("knowledge base")) {
            List<com.sandisk.plm.tracker.service.HelpDocsService.DocMeta> docList = helpDocsService.listDocs();
            if (!docList.isEmpty()) {
                StringBuilder dhtml = new StringBuilder();
                dhtml.append("<p style='font-weight:600;color:#1a3a5c;margin:0 0 8px;'>").append(docList.size()).append(" documents in the Knowledge Base:</p>");
                dhtml.append("<div style='margin:0 0 12px;'>");
                for (com.sandisk.plm.tracker.service.HelpDocsService.DocMeta doc : docList) {
                    String ext = doc.filename.substring(doc.filename.lastIndexOf('.') + 1).toUpperCase();
                    dhtml.append("<div style='display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid #eee;'>");
                    dhtml.append("<span style='font-size:10px;font-weight:600;background:").append("PDF".equals(ext) ? "#dc3545" : "PPTX".equals(ext) ? "#e67e22" : "#28a745")
                        .append(";color:#fff;padding:2px 5px;border-radius:3px;'>").append(ext).append("</span>");
                    dhtml.append("<span style='flex:1;font-size:13px;'>").append(escHtml(doc.title)).append("</span>");
                    dhtml.append("<a href='/api/help-docs/").append(doc.id).append("/download' style='font-size:11px;color:#4a6fa5;text-decoration:none;'>\u2913 Download</a>");
                    dhtml.append("</div>");
                }
                dhtml.append("</div>");
                dhtml.append("<p style='font-size:12px;color:#888;'>Ask me about any of these topics, or go to the <strong>Work Instructions</strong> tab to search and upload more.</p>");
                response.put("success", true);
                response.put("answer", dhtml.toString());
                response.put("source", "doc-list");
                return response;
            }
        }

        // Navigation "how do I" intercept — answers common procedural questions from a
        // curated lookup so the chat doesn't rely on RAG retrieval over uploaded docs
        // (which can match the wrong slide deck via keyword overlap). Triggered for
        // PT-55 ("how to do a where used of a part to SKU" returned Single/Sole Source
        // slides — wrong feature) and PT-57 ("parts history change" said Utilities tab
        // — wrong tab post the Audit Trail reorg).
        String navAnswer = tryNavHowToAnswer(qLower);
        if (navAnswer != null) {
            response.put("success", true);
            response.put("answer", navAnswer);
            response.put("source", "nav-howto");
            return response;
        }

        // Help docs keyword search — runs FIRST, no AI needed, works when Portkey is down
        if (helpDocsService.getDocCount() > 0) {
            // Only search docs for process/procedure questions, not data queries
            boolean isProcessQuestion = qLower.contains("process") || qLower.contains("procedure") || qLower.contains("how to")
                || qLower.contains("how do i") || qLower.contains("instructions") || qLower.contains("what is a")
                || qLower.contains("what is an") || qLower.contains("what is the") || qLower.contains("explain")
                || qLower.contains("guide") || qLower.contains("cheat sheet") || qLower.contains("definition")
                || (qLower.contains("lifecycle") && !qLower.contains("how many"))
                || qLower.contains("ecn") || qLower.contains("ecr") || qLower.contains("pdr") || qLower.contains("aml")
                || qLower.contains("pcm") || qLower.contains("sole source") || qLower.contains("single source")
                || qLower.contains("import") || qLower.contains("attribute change");
            // Toolkit-feature questions — skip the help-PDF search entirely and
            // let the LLM answer from app-knowledge.txt. The PDF help guides
            // (Agile Import/Export, training decks, etc.) routinely score high
            // on words like "meeting" / "guide" / "import" / "use" that aren't
            // about toolkit features at all, and were drowning out the KB.
            // Listed terms are the canonical names of toolkit features that
            // live in app-knowledge.txt; if the question mentions one of
            // these, the PDF search is bypassed.
            boolean isToolkitFeatureQuestion = qLower.contains("meeting mode")
                || qLower.contains("my enhancements") || qLower.contains("all enhancements")
                || qLower.contains("it enhancements") || qLower.contains("it enh")
                || qLower.contains("wrap up") || qLower.contains("wrap-up")
                || qLower.contains("scorecard") || qLower.contains("backlog scorecard")
                || qLower.contains("consolidated email") || qLower.contains("consolidated emails")
                || qLower.contains("volume report") || qLower.contains("volume reports")
                || qLower.contains("bom explorer") || qLower.contains("bom race")
                || qLower.contains("change history tab") || qLower.contains("field changes tab")
                || qLower.contains("ims dashboard") || qLower.contains("ims review")
                || qLower.contains("returns tracker") || qLower.contains("overdue tracker")
                || qLower.contains("cycle time view") || qLower.contains("ecn dashboard")
                || qLower.contains("ecn report")
                || qLower.contains("shop-floor docs") || qLower.contains("shop floor docs")
                || qLower.contains("agile lookup") || qLower.contains("part extract")
                || qLower.contains("user management") || qLower.contains("user permissions")
                || qLower.contains("focus pill") || qLower.contains("focus pills")
                || qLower.contains("rail dot") || qLower.contains("rail dots")
                || qLower.contains("agile sign-in modal") || qLower.contains("send sign-off")
                || qLower.contains("save batch") || qLower.contains("save-batch")
                || qLower.contains("autosave") || qLower.contains("draft recovery")
                || qLower.contains("plm toolkit") || qLower.contains("plm-toolkit")
                || qLower.contains("this toolkit") || qLower.contains("the toolkit")
                || qLower.contains("this tool") || qLower.contains("this app")
                || qLower.contains("ecns where i am") || qLower.contains("ecns i own")
                || qLower.contains("rows i own") || qLower.contains("things i own");
            logger.info("[AI HELP] Process question check: " + isProcessQuestion
                + ", toolkit-feature: " + isToolkitFeatureQuestion + " for: " + question);
            if (isProcessQuestion && !isToolkitFeatureQuestion) {
                List<Map<String, Object>> docResults = helpDocsService.search(question, 5);
                Map<String, Object> top = docResults.isEmpty() ? null : docResults.get(0);
                int topScore = top == null ? 0 : (int) top.get("score");
                int matchedTermsCount = 0;
                int queryTermsTotal = 0;
                if (top != null) {
                    Object mt = top.get("matchedTerms");
                    if (mt instanceof java.util.Set) matchedTermsCount = ((java.util.Set<?>) mt).size();
                    Object qtt = top.get("queryTermsTotal");
                    if (qtt instanceof Integer) queryTermsTotal = (Integer) qtt;
                }
                int requiredMatches = Math.max(1, (int) Math.ceil(queryTermsTotal * 0.5));
                // Gate has TWO independent conditions, both must pass:
                //   1. score >= 10 — excludes weak matches (single-word substring,
                //      generic terms like "compare" appearing in unrelated docs).
                //      Score 10 typically requires either a phrase match (+10) OR
                //      a multi-keyword match with 2+ matched terms.
                //   2. matchedTerms covers at least half the question's significant
                //      terms — rejects results that match a popular word but miss
                //      the rest of the question's intent. Single-term queries
                //      ("what is an ECN") still pass since 1 of 1 = 100%.
                // Together this routes weak/unrelated matches to the LLM (which has
                // app-knowledge.txt + the chat history) instead of silently
                // returning irrelevant doc snippets.
                boolean passesScore = topScore >= 10;
                boolean passesCoverage = (queryTermsTotal == 0) || (matchedTermsCount >= requiredMatches);
                logger.info("[AI HELP] Help docs search: " + docResults.size() + " results, top score: "
                    + topScore + ", matched " + matchedTermsCount + "/" + queryTermsTotal
                    + " (need " + requiredMatches + ") — gate "
                    + (passesScore && passesCoverage ? "PASS" : "FAIL (deferring to LLM)"));
                if (top != null && passesScore && passesCoverage) {
                    StringBuilder docHtml = new StringBuilder();
                    docHtml.append("<p style='font-size:12px;color:#1a3a5c;font-weight:600;margin:0 0 8px;'>Found in Help Guide documents:</p>");
                    for (Map<String, Object> dr : docResults) {
                        String text = (String) dr.get("text");
                        String docTitle = (String) dr.get("docTitle");
                        int page = (int) dr.get("slideOrPage");
                        if (text.length() > 300) text = text.substring(0, 297) + "...";
                        docHtml.append("<div style='background:#f8f9fa;border-left:3px solid #4a6fa5;padding:8px 12px;margin:0 0 8px;border-radius:0 6px 6px 0;font-size:12.5px;'>");
                        docHtml.append("<div style='font-size:11px;color:#888;margin-bottom:4px;'>")
                            .append(escHtml(docTitle)).append(" \u00b7 Slide/Page ").append(page).append("</div>");
                        docHtml.append("<div>").append(escHtml(text)).append("</div>");
                        docHtml.append("</div>");
                    }
                    docHtml.append("<p style='font-size:11px;color:#28a745;'>\u26A1 From Help Guide docs (no AI needed)</p>");
                    response.put("success", true);
                    response.put("answer", docHtml.toString());
                    response.put("source", "help-docs");
                    return response;
                }
            }
        }

        // Learning intercept: "resistor means E011-Resistor", "remember that X is Y"
        String qLow = question.toLowerCase();
        if (qLow.contains("means") || qLow.contains("remember that") || qLow.contains("remember:") || qLow.contains("part type is")) {
            // Try to extract key=value mapping
            java.util.regex.Matcher learnMatcher = java.util.regex.Pattern.compile(
                "(?:remember (?:that )?|)(\\w[\\w\\s]*?)\\s+(?:means|is|=|refers to|maps to)\\s+['\"]?([\\w-]+)['\"]?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question);
            if (learnMatcher.find()) {
                String learnKey = learnMatcher.group(1).trim();
                String learnVal = learnMatcher.group(2).trim();
                saveLearning(learnKey, learnVal);

                // Auto-rerun the last query with the new learning
                String prevQuery = lastDataSql.get(username);
                if (prevQuery != null && prevQuery.startsWith("JSON_CACHE:") && itemCacheService.isLoaded()) {
                    // Re-ask the original question context
                    String rerunResult = tryCacheQuery("how many " + learnKey + "s", username, displayName,
                        portkeyEnabled && portkeyApiKey != null && !portkeyApiKey.isEmpty());
                    if (rerunResult != null) {
                        response.put("success", true);
                        response.put("answer", "\u2705 Learned: <strong>" + escHtml(learnKey) +
                            "</strong> → <code>" + escHtml(learnVal) + "</code> (saved permanently)\n\n" +
                            "<hr style='border:none;border-top:1px solid #eee;margin:12px 0;'>" + rerunResult);
                        return response;
                    }
                }

                response.put("success", true);
                response.put("answer", "\u2705 Got it! I'll remember that <strong>" + escHtml(learnKey) +
                    "</strong> → <code>" + escHtml(learnVal) + "</code> in NEW_PART_CLASS. This is saved permanently. Ask your question again and I'll use this mapping.");
                return response;
            }
        }

        // Column metadata query: "which columns", "what columns", "available columns"
        if (qLow.contains("which column") || qLow.contains("what column") || qLow.contains("available column")
            || qLow.contains("can i ask about") || qLow.contains("columns can i") || qLow.contains("what fields")) {
            StringBuilder mhtml = new StringBuilder();

            // Item Cache columns
            mhtml.append("<p style='font-weight:600;color:#1a3a5c;margin:0 0 8px;'>Item Cache (").append(itemCacheService.size()).append(" records)</p>");
            mhtml.append("<div style='display:flex;flex-wrap:wrap;gap:4px;margin:0 0 16px;'>");
            for (String col : com.sandisk.plm.tracker.service.ItemCacheService.CACHED_COLUMNS) {
                mhtml.append("<code style='font-size:10.5px;padding:2px 6px;background:#f0f4ff;border:1px solid #d0e0f0;border-radius:3px;'>")
                    .append(col.toLowerCase().replace("_", " ")).append("</code>");
            }
            mhtml.append("</div>");

            // External source columns
            try {
                List<Map<String, String>> sources = externalSourceService.getSourceList();
                for (Map<String, String> src : sources) {
                    List<String> headers = externalSourceService.getSourceHeaders(src.get("id"));
                    if (!headers.isEmpty()) {
                        mhtml.append("<p style='font-weight:600;color:#1a3a5c;margin:0 0 6px;'>").append(escHtml(src.get("name"))).append(" (External JSON)</p>");
                        mhtml.append("<div style='display:flex;flex-wrap:wrap;gap:4px;margin:0 0 12px;'>");
                        for (String h : headers) {
                            mhtml.append("<code style='font-size:10.5px;padding:2px 6px;background:#f0fff4;border:1px solid #c0e8d0;border-radius:3px;'>")
                                .append(escHtml(h.toLowerCase().replace("_", " "))).append("</code>");
                        }
                        mhtml.append("</div>");
                    }
                }
            } catch (Exception ignored) {}

            mhtml.append("<p style='font-size:12px;color:#888;margin:8px 0 0;'>Ask about any of these columns. You can also say <em>\"unique values for [column]\"</em> to explore the data.</p>");
            response.put("success", true);
            response.put("answer", mhtml.toString());
            response.put("source", "column-metadata");
            return response;
        }

        // Unique/distinct/possible values query
        if (qLow.contains("unique value") || qLow.contains("distinct value") || qLow.contains("possible value")
            || qLow.contains("what values") || qLow.contains("list of values") || qLow.contains("available values")) {

            // Check item cache columns (match by column name or friendly name)
            String[][] colMappings = {
                {"GOLDEN_PART", "golden part", "golden"},
                {"NEW_PART_CLASS", "part type", "item type", "part class"},
                {"LIFECYCLE_PHASE", "lifecycle", "lifecycle phase"},
                {"MATERIAL_GROUP", "material group"},
                {"MATERIAL_TYPE", "material type"},
                {"STATUSCODE", "status", "statuscode"},
                {"PRODUCTLINE", "product line", "productline"},
                {"ACTUAL_BUILD_PLANT", "build plant", "plant"},
                {"PRODUCT_TYPE", "product type"},
                {"CATEGORY", "category"},
                {"PRODUCTDIVISION", "division", "product division"},
                {"CLASSIFICATION_PDM", "classification", "pdm"},
                {"PM", "pm", "product manager"},
                {"ECCN", "eccn"},
                {"CAPACITY", "capacity"},
                {"FAMILYNAME", "family", "family name"},
                {"SUBCONTRACTORS", "subcon", "subcontractor"},
            };

            for (String[] mapping : colMappings) {
                String col = mapping[0];
                for (int mi = 1; mi < mapping.length; mi++) {
                    if (qLow.contains(mapping[mi])) {
                        Set<String> vals = itemCacheService.distinctValues(col);
                        if (!vals.isEmpty()) {
                            // Sort values
                            List<String> sorted = new ArrayList<>(vals);
                            sorted.sort(String.CASE_INSENSITIVE_ORDER);
                            StringBuilder vhtml = new StringBuilder();
                            vhtml.append("<p><strong>").append(sorted.size()).append("</strong> unique values for <code>")
                                .append(col).append("</code> (Item Cache):</p>");
                            vhtml.append("<div style='max-height:300px;overflow-y:auto;font-size:11px;font-family:monospace;columns:3;column-gap:16px;margin:8px 0;'>");
                            for (String v : sorted) vhtml.append("<div style='padding:1px 0;'>").append(escHtml(v)).append("</div>");
                            vhtml.append("</div>");
                            vhtml.append("<p style='font-size:11px;color:#28a745;'>\u26A1 Item Cache \u00b7 ").append(itemCacheService.size()).append(" records</p>");
                            response.put("success", true);
                            response.put("answer", vhtml.toString());
                            response.put("source", "unique-values-item-cache");
                            return response;
                        }
                    }
                }
            }

            // Check external source columns
            try {
                List<Map<String, String>> sources = externalSourceService.getSourceList();
                for (Map<String, String> src : sources) {
                    List<String> headers = externalSourceService.getSourceHeaders(src.get("id"));
                    for (String h : headers) {
                        if (qLow.contains(h.toLowerCase()) || qLow.contains(h.toLowerCase().replace("_", " "))) {
                            // Get distinct values from external source
                            List<Map<String, String>> allRecs = externalSourceService.searchRecords(src.get("id"), h, null, 50000);
                            Set<String> vals = new LinkedHashSet<>();
                            for (Map<String, String> r : allRecs) {
                                String v = r.get(h);
                                if (v == null) { for (Map.Entry<String, String> e : r.entrySet()) { if (e.getKey().equalsIgnoreCase(h)) { v = e.getValue(); break; } } }
                                if (v != null && !v.isEmpty()) vals.add(v);
                            }
                            if (!vals.isEmpty()) {
                                List<String> sorted = new ArrayList<>(vals);
                                sorted.sort(String.CASE_INSENSITIVE_ORDER);
                                StringBuilder vhtml = new StringBuilder();
                                vhtml.append("<p><strong>").append(sorted.size()).append("</strong> unique values for <code>")
                                    .append(h).append("</code> (").append(escHtml(src.get("name"))).append("):</p>");
                                vhtml.append("<div style='max-height:300px;overflow-y:auto;font-size:11px;font-family:monospace;columns:3;column-gap:16px;margin:8px 0;'>");
                                for (String v : sorted) vhtml.append("<div style='padding:1px 0;'>").append(escHtml(v)).append("</div>");
                                vhtml.append("</div>");
                                vhtml.append("<p style='font-size:11px;color:#28a745;'>\u26A1 ").append(escHtml(src.get("name"))).append("</p>");
                                response.put("success", true);
                                response.put("answer", vhtml.toString());
                                response.put("source", "unique-values-external");
                                return response;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // If nothing matched, tell user what's available
            StringBuilder avail = new StringBuilder();
            avail.append("<p>I can show unique values for these columns:</p>");
            avail.append("<p style='font-size:12px;'><strong>Item Cache:</strong> ");
            for (String[] m : colMappings) avail.append("<code>").append(m[1]).append("</code> ");
            avail.append("</p>");
            try {
                List<Map<String, String>> sources = externalSourceService.getSourceList();
                for (Map<String, String> src : sources) {
                    List<String> headers = externalSourceService.getSourceHeaders(src.get("id"));
                    if (!headers.isEmpty()) {
                        avail.append("<p style='font-size:12px;'><strong>").append(escHtml(src.get("name"))).append(":</strong> ");
                        for (String h : headers) avail.append("<code>").append(escHtml(h.toLowerCase().replace("_"," "))).append("</code> ");
                        avail.append("</p>");
                    }
                }
            } catch (Exception ignored) {}
            response.put("success", true);
            response.put("answer", avail.toString());
            return response;
        }

        // Disagreement detection: "that's wrong", "not correct", "incorrect results"
        if (qLow.contains("that's wrong") || qLow.contains("thats wrong") || qLow.contains("not correct")
            || qLow.contains("incorrect") || qLow.contains("wrong results") || qLow.contains("should be")
            || qLow.contains("not what i") || qLow.contains("not right")) {
            response.put("success", true);
            response.put("answer", "<div style='background:#f0f4ff;border:1px solid #4a6fa5;border-radius:6px;padding:12px 16px;'>" +
                "<p style='margin:0 0 8px;font-weight:600;color:#1a3a5c;'>Let me learn from this!</p>" +
                "<p style='margin:0 0 6px;font-size:12.5px;'>Tell me the correct mapping and I'll remember it permanently. Examples:</p>" +
                "<ul style='margin:4px 0 0;font-size:12.5px;color:#555;'>" +
                "<li><em>\"resistor means E011-Resistor\"</em></li>" +
                "<li><em>\"capacitor means E001-Capacitor\"</em></li>" +
                "<li><em>\"SKU means OBS-SKU\"</em> (or whatever the correct value is)</li>" +
                "</ul>" +
                "<p style='margin:8px 0 0;font-size:12px;color:#888;'>You can also ask <em>\"what are the unique values for NEW_PART_CLASS\"</em> to see all available values.</p>" +
                "</div>");
            return response;
        }

        // Saved queries intercept: "show my saved queries", "my queries", "saved queries"
        if (qLow.contains("saved quer") || qLow.contains("my quer") || (qLow.contains("saved") && qLow.contains("search"))) {
            List<com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery> mine = savedChatQueryService.getQueries(username);
            List<com.sandisk.plm.tracker.service.SavedChatQueryService.ChatQuery> shared = savedChatQueryService.getSharedQueries(username);
            if (mine.isEmpty() && shared.isEmpty()) {
                response.put("success", true);
                response.put("answer", "You don't have any saved queries yet. Run a data query and click the <strong>★ Save</strong> button to save it. You can also click <strong>★ My Queries</strong> in the chatbot header.");
                return response;
            }
            response.put("success", true);
            response.put("answer", "showSavedQueries");
            response.put("action", "showSavedQueries");
            return response;
        }

        // SKU / external source lookup: "what's the UPC on SDCZ410-064G?"
        String skuAnswer = trySkuLookup(question, username, displayName);
        if (skuAnswer != null) {
            response.put("success", true);
            response.put("answer", skuAnswer);
            response.put("source", "sku-lookup");
            ChatInteraction sci = new ChatInteraction();
            sci.username = username; sci.displayName = displayName;
            sci.tab = currentTab; sci.question = question;
            sci.answer = stripHtml(skuAnswer); sci.wasAi = true;
            sci.timestamp = System.currentTimeMillis();
            chatLog.add(sci);
            return response;
        }

        // Data query intercept: questions that want to look up data from item_extract
        if (isDataQuery(question, username)) {
            String dataAnswer = tryDataQuery(question, username, displayName);
            if (dataAnswer != null) {
                response.put("success", true);
                response.put("answer", dataAnswer);
                response.put("source", "data-query");
                ChatInteraction dci = new ChatInteraction();
                dci.username = username;
                dci.displayName = displayName;
                dci.tab = currentTab;
                dci.question = question;
                dci.answer = stripHtml(dataAnswer);
                dci.wasAi = true;
                dci.timestamp = System.currentTimeMillis();
                chatLog.add(dci);
                return response;
            }
        }

        // Help docs search already ran earlier — this is the AI fallback position
        if (false && helpDocsService.getDocCount() > 0) {
            List<Map<String, Object>> docResults = helpDocsService.search(question, 5);
            if (!docResults.isEmpty() && (int) docResults.get(0).get("score") >= 3) {
                StringBuilder docHtml = new StringBuilder();
                docHtml.append("<p style='font-size:12px;color:#1a3a5c;font-weight:600;margin:0 0 8px;'>Found in Help Guide documents:</p>");
                for (Map<String, Object> dr : docResults) {
                    String text = (String) dr.get("text");
                    String docTitle = (String) dr.get("docTitle");
                    int page = (int) dr.get("slideOrPage");
                    // Truncate
                    if (text.length() > 300) text = text.substring(0, 297) + "...";
                    docHtml.append("<div style='background:#f8f9fa;border-left:3px solid #4a6fa5;padding:8px 12px;margin:0 0 8px;border-radius:0 6px 6px 0;font-size:12.5px;'>");
                    docHtml.append("<div style='font-size:11px;color:#888;margin-bottom:4px;'>")
                        .append(escHtml(docTitle)).append(" · Slide/Page ").append(page).append("</div>");
                    docHtml.append("<div>").append(escHtml(text)).append("</div>");
                    docHtml.append("</div>");
                }
                docHtml.append("<p style='font-size:11px;color:#28a745;'>\u26A1 From Help Guide docs (no AI needed)</p>");
                response.put("success", true);
                response.put("answer", docHtml.toString());
                return response;
            }
        }

        if (!aiEnabled || apiKey == null || apiKey.isEmpty()) {
            response.put("success", false);
            response.put("fallback", true);
            response.put("message", "AI help is not configured.");
            return response;
        }

        try {
            // Optional model override (used by /api/ai-eval/ask/question User-Ask
            // flow — lets end users pick a non-default chatbot model for the LLM
            // fallback only; interceptors above always run with prod default).
            String modelOverride = (String) request.get("modelOverride");
            String answer = callHaiku(question, currentTab, chatHistory, displayName, isAdmin, modelOverride);
            response.put("success", true);
            response.put("answer", answer);
            response.put("source", "llm");
            // Model slug actually used — lets the drawer render a truthful pill.
            response.put("model", (modelOverride != null && !modelOverride.isEmpty())
                ? modelOverride : portkeyProvider + "/" + portkeyModel);

            // Log interaction
            ChatInteraction ci = new ChatInteraction();
            ci.username = username;
            ci.displayName = displayName;
            ci.tab = currentTab;
            ci.question = question;
            ci.answer = answer;
            ci.wasAi = true;
            ci.timestamp = System.currentTimeMillis();
            chatLog.add(ci);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
            logger.warning("[AI HELP] AI call failed: " + errMsg);
            response.put("success", false);
            if (errMsg.contains("timed out") || errMsg.contains("Timeout") || errMsg.contains("connect")) {
                response.put("message", "AI service is not responding (Portkey/Claude timed out). Please try again in a moment.");
            } else if (errMsg.contains("401") || errMsg.contains("403")) {
                response.put("message", "AI service authentication failed. Please contact admin.");
            } else {
                response.put("message", "AI service is temporarily unavailable (" + errMsg + "). Please try again.");
            }
            response.put("fallback", true);
        }

        return response;
    }

    /**
     * Returns the knowledge base filtered for the user's role.
     * Lines prefixed with [ADMIN] are included only for admins (with the prefix stripped).
     * Non-admins get those lines removed entirely.
     */
    private String getFilteredKnowledgeBase(boolean isAdmin) {
        String kb = getKnowledgeBase();
        if (kb == null || kb.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : kb.split("\n")) {
            if (line.startsWith("[ADMIN]")) {
                if (isAdmin) {
                    // Strip the prefix, keep the content
                    sb.append(line.substring(7)).append("\n");
                }
                // else: skip the line entirely for non-admins
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String callHaiku(String question, String currentTab, List<Map<String, String>> history, String userName) throws Exception {
        // Default to non-admin (callHaikuForAdmin overload handles admin case)
        return callHaiku(question, currentTab, history, userName, false);
    }

    /**
     * Returns the system prompt currently used by the AI Help chatbot for a given
     * tab + admin context. Used by AiEvalService for grading and for export.
     * NOT used by the chat endpoint itself — that one builds it inline.
     */
    public String getSystemPromptForEval(String currentTab, String userName, boolean isAdmin) {
        return getSystemPromptForEval(currentTab, userName, isAdmin, false, null);
    }

    /**
     * Same as the 3-arg variant, but with eval-harness aware framing.
     *
     * <p>When {@code evalMode} is true the chatbot is told the question is an evaluation
     * question that may concern any tab in the app, and is explicitly instructed not to
     * filter the answer through the literal {@code currentTab} (which is always 'ai-eval'
     * during eval runs and would otherwise bias every answer). The persona's stated
     * {@code goalHint} is added as soft context if present.
     */
    public String getSystemPromptForEval(String currentTab, String userName, boolean isAdmin,
                                          boolean evalMode, String goalHint) {
        String kb = getFilteredKnowledgeBase(isAdmin);
        String adminNote = isAdmin ? "The user is a PLM admin with full access." :
            "The user is NOT an admin. If they ask about admin-only features (uploading scripts, editing environment variables, deleting utilities, managing dependencies), " +
            "tell them that feature is available to PLM admins only and suggest contacting pdl-plm-admin@sandisk.com for help.";
        String tabContext;
        if (evalMode) {
            StringBuilder sb = new StringBuilder("This is an evaluation question. ")
                .append("Use the knowledge base to identify which tab or feature the question is about; ")
                .append("do NOT assume the user is on any particular tab. ");
            if (goalHint != null && !goalHint.isEmpty()) {
                sb.append("The user's stated goal in this session is: \"").append(goalHint).append("\". ");
            }
            tabContext = sb.toString();
        } else {
            tabContext = "The user is currently on the '" + (currentTab != null ? currentTab : "unknown") + "' tab. ";
        }
        return "You are a helpful assistant for the PLM Toolkit web application. " +
            "Answer questions concisely based on the knowledge base below. " +
            tabContext +
            "Keep answers short (2-4 sentences). If you don't know, say so and suggest contacting pdl-plm-admin@sandisk.com. " +
            "The user's name is " + (userName != null ? userName : "User") + ". " + adminNote + "\n\n" +
            "KNOWLEDGE BASE:\n" + kb;
    }

    /**
     * Public single-shot AI Help call for the eval harness. Takes one user question,
     * uses the same system prompt the chat endpoint would build, and returns
     * the assistant's answer. No history.
     */
    public String askAiHelpForEval(String question, String currentTab, String userName, boolean isAdmin) throws Exception {
        return askAiHelpForEval(question, currentTab, userName, isAdmin, null, null);
    }

    /**
     * Same as {@link #askAiHelpForEval(String, String, String, boolean)} but with an
     * explicit model override and an optional persona-goal hint.
     *
     * <p>When {@code chatbotModelOverride} is null/empty, the production default
     * ({@code portkeyProvider}/{@code portkeyModel}) is used so production AI Help
     * behavior is unchanged. When set, the eval harness can A/B test "what if AI Help
     * ran on a different model?" without touching prod config.
     *
     * <p>{@code goalHint} is the persona's free-text goal for the eval session — it
     * triggers eval-mode framing in the system prompt so the chatbot doesn't assume
     * the user is on the 'ai-eval' tab.
     */
    public String askAiHelpForEval(String question, String currentTab, String userName, boolean isAdmin,
                                    String chatbotModelOverride, String goalHint) throws Exception {
        String systemPrompt = getSystemPromptForEval(currentTab, userName, isAdmin, true, goalHint);
        String model = (chatbotModelOverride != null && !chatbotModelOverride.isEmpty())
            ? chatbotModelOverride
            : portkeyProvider + "/" + portkeyModel;
        return portkeyClient.chat(model, systemPrompt, question, 300);
    }

    private String callHaiku(String question, String currentTab, List<Map<String, String>> history, String userName, boolean isAdmin) throws Exception {
        return callHaiku(question, currentTab, history, userName, isAdmin, null);
    }

    private String callHaiku(String question, String currentTab, List<Map<String, String>> history, String userName, boolean isAdmin, String modelOverride) throws Exception {
        String kb = getFilteredKnowledgeBase(isAdmin);

        String adminNote = isAdmin ? "The user is a PLM admin with full access." :
            "The user is NOT an admin. If they ask about admin-only features (uploading scripts, editing environment variables, deleting utilities, managing dependencies), " +
            "tell them that feature is available to PLM admins only and suggest contacting pdl-plm-admin@sandisk.com for help.";

        // For admin users asking activity-shaped questions, attach a structured
        // recent-activity summary so the LLM can answer fuzzy questions without
        // a full pivot ("is anyone using BOM Compare more than usual?",
        // "who's been quiet this week?", etc.).
        String activityContext = "";
        if (isAdmin && question != null && isActivityShapedForLlm(question.toLowerCase())) {
            String summary = buildActivityContextForLlm(question.toLowerCase());
            if (summary != null && !summary.isEmpty()) {
                activityContext = "\n\nRECENT ACTIVITY DATA (from the toolkit's activity log — answer questions about this concretely, citing user names and counts):\n"
                    + summary;
            }
        }

        String systemPrompt = "You are a helpful assistant for the PLM Toolkit web application. " +
            "Answer questions concisely based on the knowledge base below. " +
            "The user is currently on the '" + (currentTab != null ? currentTab : "unknown") + "' tab. " +
            "Keep answers short (2-4 sentences). If you don't know, say so and suggest contacting pdl-plm-admin@sandisk.com. " +
            "The user's name is " + (userName != null ? userName : "User") + ". " + adminNote + "\n\n" +
            "KNOWLEDGE BASE:\n" + kb +
            activityContext;

        // Build messages list for PortkeyClient.chatWithHistory.
        // Last 4 exchanges max (8 messages) to keep context small.
        java.util.List<java.util.Map<String, String>> chatMessages = new java.util.ArrayList<>();
        int startIdx = Math.max(0, history.size() - 8);
        for (int i = startIdx; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
            chatMessages.add(m);
        }
        Map<String, String> userMsg = new java.util.LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", question);
        chatMessages.add(userMsg);

        String model = (modelOverride != null && !modelOverride.isEmpty())
            ? modelOverride
            : portkeyProvider + "/" + portkeyModel;
        logger.info("[AI HELP] Using Portkey gateway (" + model + ") via " + portkeyBaseUrl
            + (modelOverride != null && !modelOverride.isEmpty() ? " [override]" : ""));
        return portkeyClient.chatWithHistory(model, systemPrompt, chatMessages, 300);
    }

    // ------------------------------------------------------------------
    // Engineering mode (engineering-insider role)
    // ------------------------------------------------------------------

    private volatile String engKnowledgeBase = null;
    private volatile long engKnowledgeBaseLastLoaded = 0;

    /** Same 24h-reload pattern as {@link #getKnowledgeBase()}, but for the
     *  engineering KB. The file lives at the classpath ROOT (/eng-knowledge.txt),
     *  deliberately NOT under /static so it is never web-served — access is
     *  role-gated through this endpoint only. */
    private String getEngKnowledgeBase() {
        long now = System.currentTimeMillis();
        if (engKnowledgeBase == null || (now - engKnowledgeBaseLastLoaded) > KB_RELOAD_INTERVAL) {
            synchronized (this) {
                try (InputStream is = getClass().getResourceAsStream("/eng-knowledge.txt")) {
                    if (is != null) {
                        engKnowledgeBase = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        logger.info("[AI HELP] Loaded engineering KB from JAR: " + engKnowledgeBase.length() + " chars");
                    } else {
                        logger.warning("[AI HELP] /eng-knowledge.txt not found in JAR");
                        if (engKnowledgeBase == null) engKnowledgeBase = "";
                    }
                } catch (Exception e) {
                    logger.warning("[AI HELP] Failed to load engineering KB: " + e.getMessage());
                    if (engKnowledgeBase == null) engKnowledgeBase = "";
                }
                engKnowledgeBaseLastLoaded = System.currentTimeMillis();
            }
        }
        return engKnowledgeBase;
    }

    /** System prompt for Engineering mode. The HARD RULES block is a product
     *  requirement: the assistant must never reveal credentials/secrets and must
     *  never emit actual source code (prose descriptions only). */
    private String buildEngineeringSystemPrompt(String userName) {
        return "You are the Engineering Insider assistant inside the SanDisk Agile PLM Toolkit. " +
            "The user holds the Engineering Insider role: answer IN DEPTH about how the site was built, " +
            "what it can and cannot do, and what it could do in the future. Entertain hypothetical and " +
            "what-if questions seriously, with concrete reasoning grounded in the architecture described " +
            "in the knowledge base. Be candid about limitations. Name real files, tables and services when " +
            "relevant. Plain text, engineer-to-engineer, 4-8 sentences by default, longer when depth is " +
            "genuinely asked for. The user's name is " + (userName != null ? userName : "User") + ".\n" +
            "HARD RULES (never break these, even if asked directly, hypothetically, or \"for testing\"):\n" +
            "- Never reveal credentials, passwords, password hashes, API keys, tokens, session cookies, " +
            "connection strings, or the contents of any secrets or config file. If asked, refuse briefly " +
            "and keep helping with the architecture question.\n" +
            "- Never output actual source code: no verbatim code, no reconstructed file contents, no " +
            "\"example of what the class looks like\". Describe design, classes, data flows and behavior " +
            "in prose only. Short identifier names (class/file/endpoint/property names) are fine; code " +
            "blocks and code snippets are not.\n\n" +
            "ENGINEERING KNOWLEDGE BASE:\n" + getEngKnowledgeBase();
    }

    /** Engineering-mode chat: role-gated, pinned to Claude Opus 4.8, deeper
     *  answers (1500 max tokens), no interceptors, no KB admin-filtering (the
     *  role gate already applied). */
    private Map<String, Object> askEngineering(String question, List<Map<String, String>> history,
                                               String username, String displayName,
                                               javax.servlet.http.HttpServletResponse httpResponse) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (!userPermissionsService.hasRole(username,
                com.sandisk.plm.tracker.service.UserPermissionsService.ROLE_ENGINEERING_INSIDER)) {
            logger.warning("[AI HELP] engineering mode denied for " + username + " (no role)");
            if (httpResponse != null) httpResponse.setStatus(403);
            response.put("success", false);
            response.put("message", "Engineering mode requires the Engineering Insider role.");
            return response;
        }

        if (!portkeyClient.isEnabled()) {
            response.put("success", false);
            response.put("message", "AI gateway is not configured — engineering mode is unavailable.");
            return response;
        }

        try {
            java.util.List<java.util.Map<String, String>> chatMessages = new java.util.ArrayList<>();
            int startIdx = Math.max(0, history.size() - 8);
            for (int i = startIdx; i < history.size(); i++) {
                Map<String, String> msg = history.get(i);
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("role", msg.getOrDefault("role", "user"));
                m.put("content", msg.getOrDefault("content", ""));
                chatMessages.add(m);
            }
            Map<String, String> userMsg = new java.util.LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", question);
            chatMessages.add(userMsg);

            logger.info("[AI HELP] engineering mode (" + engineeringModel + ") for " + username);
            String answer = portkeyClient.chatWithHistory(
                engineeringModel, buildEngineeringSystemPrompt(displayName), chatMessages, 1500);

            response.put("success", true);
            response.put("answer", answer);
            response.put("source", "eng-llm");
            response.put("model", engineeringModel);

            // Question length + model only — never the question text.
            activityLogger.log(username, displayName, "ENG_INSIDER_ASK",
                "qlen=" + (question == null ? 0 : question.length()) + " model=" + engineeringModel);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
            logger.warning("[AI HELP] engineering AI call failed: " + errMsg);
            response.put("success", false);
            if (errMsg.contains("timed out") || errMsg.contains("Timeout") || errMsg.contains("connect")) {
                response.put("message", "AI service is not responding (Portkey/Claude timed out). Please try again in a moment.");
            } else {
                response.put("message", "AI service is temporarily unavailable (" + errMsg + "). Please try again.");
            }
        }
        return response;
    }

    private int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') return i;
        }
        return s.length();
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // =========================================================================
    // Admin Activity Query — answers "what did <user> do" from the activity log.
    // Only called when the requesting user is in the pdl-plm-admin group.
    // =========================================================================

    private static final String[] ACTIVITY_KEYWORDS = {
        "activit", "action", "recent", "usage", "audit", "history",
        "did ", " did", "done", "doing", "performed", "log ",
        // Login-shaped questions ("anyone logged in?", "login today", "signed in")
        "logged", "login", "signed in", "sign-in",
        // Tab-named questions: "who used the AI eval tab"
        "used the ", "uses the ", "tab today", "tab last", "tab this", "tab yesterday",
        // Activity-volume questions
        "heaviest", "busiest", "most active", "least active", "quietest",
        // Compute-intensive specifically
        "compute", "intensive", "heavy",
        // Casual phrasings: "what was Vikas Singh upto?", "what has X been up to?",
        // "what's X been working on?" — these used to fall through to the LLM and got
        // hallucinated "I don't have access to the activity log" answers.
        "upto", "up to", "working on", "been on", "been at"
    };

    /**
     * Returns an HTML-formatted activity report for various admin-only query patterns:
     * <ul>
     *   <li>Per-user: "what did Prakash do in the last 2 hours"</li>
     *   <li>Aggregate: "who logged in the last 4 hours", "who uploaded today"</li>
     * </ul>
     * Supports time windows ("last N hours/minutes", "today") and action filters
     * (login, upload, search/lookup, export, report, feedback, compare).
     *
     * <p>Returns null when the question isn't an activity query — the LLM path then
     * handles it.
     */
    // =========================================================================
    // SKU + External source lookup — answers "what's the UPC on X?"
    // =========================================================================

    // Known external source field names for detection
    private static final String[] EXT_FIELD_KEYWORDS = {
        "upc", "gtin", "ean", "sap", "cost", "price", "mrp", "vendor",
        "msrp", "wholesale", "retail", "barcode", "sku data",
        "design_group", "design group", "outer_upc", "inner_upc",
        "ean_category", "material_status", "outer_qty", "inner_qty",
        "gross_weight", "wun", "outer_aum", "inner_aum"
    };

    private String trySkuLookup(String question, String username, String displayName) {
        if (question == null) return null;
        String lower = question.toLowerCase();

        // ── If user has an active SQL query context, don't intercept with external source ──
        // Follow-ups like "also include product line" should modify the SQL, not search external sources
        boolean hasActiveSqlContext = username != null && lastDataSql.containsKey(username);
        if (hasActiveSqlContext) {
            // Check if the question is clearly about an external source field
            boolean asksAboutExternal = false;
            for (String kw : EXT_FIELD_KEYWORDS) {
                if (lower.contains(kw)) { asksAboutExternal = true; break; }
            }
            // Also check dynamic external source column names
            if (!asksAboutExternal) {
                try {
                    for (Map<String, String> src : externalSourceService.getSourceList()) {
                        for (String h : externalSourceService.getSourceHeaders(src.get("id"))) {
                            if (lower.contains(h.toLowerCase().replace("_", " ")) || lower.contains(h.toLowerCase())) {
                                asksAboutExternal = true; break;
                            }
                        }
                        if (asksAboutExternal) break;
                    }
                } catch (Exception ignored) {}
            }
            if (!asksAboutExternal) {
                // Not about external sources — stay on SQL/cache path
                return null;
            }
            // Clear SQL context so external source query can proceed
        }

        // ── Check if question mentions specific item numbers ──
        java.util.regex.Matcher earlyItemCheck = java.util.regex.Pattern.compile(
            "\\b([A-Z0-9]{2,}[-][A-Z0-9]+[-]?[A-Z0-9]*)\\b", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(question);
        boolean hasSpecificItems = false;
        while (earlyItemCheck.find()) {
            String c = earlyItemCheck.group(1);
            if (c.length() >= 6 && c.contains("-") && !c.toUpperCase().startsWith("HTTP")) {
                hasSpecificItems = true;
                break;
            }
        }

        // ── Check if question is primarily about item cache data (with external source as enrichment) ──
        boolean hasCacheIntent = lower.contains("released") || lower.contains("created")
            || lower.contains("lifecycle") || lower.contains("status")
            || lower.contains("material group") || lower.contains("product line")
            || lower.contains("part type") || lower.contains("item type")
            || (lower.contains("sku") && (lower.contains("released") || lower.contains("created") || lower.contains("list")));

        // ── Bulk external source queries (only when NO specific items, no active SQL context, and not primarily cache-oriented) ──
        if (!hasSpecificItems && !hasActiveSqlContext && !hasCacheIntent) {
            // Check hardcoded keywords first
            for (String kw : EXT_FIELD_KEYWORDS) {
                if (lower.contains(kw)) {
                    String bulkResult = tryExternalBulkQuery(question, lower, kw, username, displayName);
                    if (bulkResult != null) return bulkResult;
                }
            }
            // Dynamic check: scan all external source column names for a match
            // BUT skip if the matched phrase also maps to an ITEM_EXTRACT column
            // (e.g. "material group" → MATERIAL_GROUP in item_extract takes priority
            //  over "MATERIAL" in an external source)
            boolean matchesItemExtract = false;
            for (String iec : ITEM_EXTRACT_COLUMNS) {
                String iecFriendly = iec.toLowerCase().replace("_", " ");
                if (lower.contains(iecFriendly)) {
                    matchesItemExtract = true;
                    break;
                }
            }
            if (!matchesItemExtract) {
                try {
                    List<Map<String, String>> srcList = externalSourceService.getSourceList();
                    for (Map<String, String> src : srcList) {
                        List<String> headers = externalSourceService.getSourceHeaders(src.get("id"));
                        for (String h : headers) {
                            // Match column name or its friendly version (DESIGN_GROUP → "design group")
                            String friendly = h.toLowerCase().replace("_", " ");
                            if (lower.contains(friendly) || lower.contains(h.toLowerCase())) {
                                String bulkResult = tryExternalBulkQuery(question, lower, friendly.split(" ")[0], username, displayName);
                                if (bulkResult != null) return bulkResult;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── Follow-up on previous external source query ──
        // But NOT if the question is primarily about item cache data
        boolean hasCacheIntent2 = lower.contains("released") || lower.contains("created")
            || lower.contains("lifecycle") || lower.contains("material group")
            || (lower.contains("sku") && (lower.contains("released") || lower.contains("created")));
        if (username != null && lastExtQuery.containsKey(username) && !hasCacheIntent2) {
            boolean wantsFollowUp = lower.contains("sample") || lower.contains("show them")
                || lower.contains("list them") || lower.contains("list all")
                || lower.contains("give me") || lower.contains("display them")
                || lower.contains("show me") || lower.contains("show those") || lower.contains("list those")
                || lower.contains("please list") || lower.contains("show all");
            if (wantsFollowUp) {
                String[] ctx = lastExtQuery.get(username).split("\\|", -1);
                String srcId = ctx[0];
                String col = ctx.length > 1 ? ctx[1] : "";
                String fv = ctx.length > 2 && !ctx[2].isEmpty() ? ctx[2] : null;
                // Directly render results from stored context — no fake query
                try {
                    List<Map<String, String>> sources = externalSourceService.getSourceList();
                    String srcName = srcId;
                    for (Map<String, String> s : sources) { if (srcId.equals(s.get("id"))) { srcName = s.get("name"); break; } }
                    int total = externalSourceService.countRecords(srcId, col, fv);
                    List<Map<String, String>> sample = externalSourceService.searchRecords(srcId, col, fv, 50);

                    StringBuilder ehtml = new StringBuilder();
                    ehtml.append("<p><strong>").append(total).append("</strong> items with <strong>")
                        .append(escHtml(col)).append("</strong> in <strong>").append(escHtml(srcName)).append("</strong>.");
                    ehtml.append(" Showing first ").append(Math.min(sample.size(), 50)).append(":</p>");
                    if (!sample.isEmpty()) {
                        List<String> eCols = new ArrayList<>(sample.get(0).keySet());
                        ehtml.append("<div style='overflow-x:auto;margin:8px 0;'><table style='border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;'><tr>");
                        for (String c : eCols) ehtml.append("<th style='text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;font-size:10px;color:#666;'>").append(escHtml(c)).append("</th>");
                        ehtml.append("</tr>");
                        for (Map<String, String> row : sample) {
                            ehtml.append("<tr>");
                            for (String c : eCols) ehtml.append("<td style='padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;'>").append(escHtml(row.getOrDefault(c, ""))).append("</td>");
                            ehtml.append("</tr>");
                        }
                        ehtml.append("</table></div>");
                        String exportParams = java.util.Base64.getEncoder().encodeToString((srcId + "|" + col + "|" + (fv != null ? fv : "")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        ehtml.append("<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>");
                        ehtml.append("<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>");
                        ehtml.append("<button onclick=\"exportExtSource(this.dataset.params,this)\" data-params=\"").append(exportParams)
                            .append("\" style='font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;'>\u2913 Export all ").append(total).append(" to Excel</button>");
                        ehtml.append("<span style='font-size:11px;color:#28a745;'>\u26A1 ").append(escHtml(srcName)).append("</span>");
                        ehtml.append("</div>");
                    }
                    activityLogger.log(username, displayName, "AI_EXT_LOOKUP", "Follow-up: " + srcName + " | " + col + " | " + total + " rows");
                    return ehtml.toString();
                } catch (Exception ignored) {}
            }
        }

        // ── Single-item lookups ──
        // Extract item numbers from the question (patterns like SDCZ410-064G, A190-008600-4096G, etc.)
        java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile(
            "\\b([A-Z0-9]{2,}[-][A-Z0-9]+[-]?[A-Z0-9]*)\\b", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(question);

        // Collect known source names to exclude from item number detection
        Set<String> sourceNames = new HashSet<>();
        try {
            for (Map<String, String> s : externalSourceService.getSourceList()) {
                sourceNames.add(s.get("name").toUpperCase().replace(" ", "-"));
                sourceNames.add(s.get("id").toUpperCase());
            }
        } catch (Exception ignored) {}

        List<String> itemNumbers = new ArrayList<>();
        while (itemMatcher.find()) {
            String candidate = itemMatcher.group(1).toUpperCase();
            if (candidate.length() >= 6 && candidate.contains("-") && !candidate.startsWith("HTTP")
                && !sourceNames.contains(candidate)) {
                itemNumbers.add(candidate);
            }
        }

        // Follow-up: "also show X for these skus" — reuse last looked-up items
        if (itemNumbers.isEmpty() && username != null && lastItemLookup.containsKey(username)) {
            boolean refersToSameItems = lower.contains("these") || lower.contains("those")
                || lower.contains("same sku") || lower.contains("same part") || lower.contains("same item")
                || lower.contains("for them") || lower.contains("for those");
            if (refersToSameItems) {
                itemNumbers = new ArrayList<>(lastItemLookup.get(username));
            }
        }

        if (itemNumbers.isEmpty()) return null;

        // Check if question asks about external source fields (UPC, SAP, cost, price, etc.)
        boolean wantsExternal = lower.contains("upc") || lower.contains("sap") || lower.contains("cost")
            || lower.contains("price") || lower.contains("gtin") || lower.contains("ean")
            || lower.contains("mrp") || lower.contains("plant") || lower.contains("vendor")
            || lower.contains("external") || lower.contains("extension");

        // Also trigger for general "tell me about" or "what is" type questions about specific items
        boolean wantsInfo = lower.contains("what") || lower.contains("tell me") || lower.contains("info")
            || lower.contains("details") || lower.contains("look up") || lower.contains("lookup")
            || lower.contains("give me") || lower.contains("show me") || lower.contains("also")
            || lower.contains("these") || lower.contains("those");

        if (!wantsExternal && !wantsInfo) return null;

        // Get available external sources
        List<Map<String, String>> sources = externalSourceService.getSourceList();

        StringBuilder html = new StringBuilder();
        int itemCount = 0;

        for (String itemNum : itemNumbers) {
            if (itemCount >= 5) { html.append("<p style='color:#888;'>Showing first 5 items only.</p>"); break; }

            // 1. Look up from Item Cache first (778K records — all items)
            Map<String, String> itemCacheRec = itemCacheService.getRecord(itemNum);

            // 2. Look up from SKU cache (Agile data — SKU items only)
            @SuppressWarnings("unchecked")
            Map<String, Object> skuRec = itemCacheRec == null ? (Map<String, Object>) skuDataService.getRecord(itemNum) : null;

            // 3. Look up from each external source
            Map<String, Map<String, String>> extData = new LinkedHashMap<>();
            for (Map<String, String> src : sources) {
                Map<String, String> rec = externalSourceService.getRecord(src.get("id"), itemNum);
                if (rec != null && !rec.isEmpty()) {
                    extData.put(src.get("name"), rec);
                }
            }

            if (itemCacheRec == null && skuRec == null && extData.isEmpty()) continue;
            itemCount++;

            html.append("<div style='margin-bottom:16px;'>");
            html.append("<p style='font-weight:600;font-size:14px;margin:0 0 8px;'>").append(escHtml(itemNum)).append("</p>");

            // Item cache data table (primary — covers all items)
            if (itemCacheRec != null) {
                html.append("<div style='font-size:10.5px;color:#888;font-family:monospace;text-transform:uppercase;margin-bottom:4px;'>Item Cache</div>");
                html.append("<table style='border-collapse:collapse;font-size:11.5px;width:100%;margin-bottom:10px;'>");
                for (Map.Entry<String, String> e : itemCacheRec.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) continue;
                    if (e.getKey().startsWith("_")) continue; // skip internal epoch fields
                    html.append("<tr><td style='padding:3px 8px;border-bottom:1px solid #eee;color:#666;font-weight:500;white-space:nowrap;width:180px;'>")
                        .append(escHtml(e.getKey())).append("</td><td style='padding:3px 8px;border-bottom:1px solid #eee;'>")
                        .append(escHtml(e.getValue())).append("</td></tr>");
                }
                html.append("</table>");
            }

            // SKU data table (fallback if not in item cache)
            if (itemCacheRec == null && skuRec != null) {
                html.append("<div style='font-size:10.5px;color:#888;font-family:monospace;text-transform:uppercase;margin-bottom:4px;'>Agile PLM (SKU)</div>");
                html.append("<table style='border-collapse:collapse;font-size:11.5px;width:100%;margin-bottom:10px;'>");
                for (Map.Entry<String, Object> e : skuRec.entrySet()) {
                    if (e.getValue() == null || e.getValue().toString().isEmpty()) continue;
                    String key = e.getKey();
                    if (wantsExternal && !isCommonField(key)) continue;
                    html.append("<tr><td style='padding:3px 8px;border-bottom:1px solid #eee;color:#666;font-weight:500;white-space:nowrap;width:180px;'>")
                        .append(escHtml(key)).append("</td><td style='padding:3px 8px;border-bottom:1px solid #eee;'>")
                        .append(escHtml(String.valueOf(e.getValue()))).append("</td></tr>");
                }
                html.append("</table>");
            }

            // External source data
            for (Map.Entry<String, Map<String, String>> srcEntry : extData.entrySet()) {
                html.append("<div style='font-size:10.5px;color:#888;font-family:monospace;text-transform:uppercase;margin-bottom:4px;'>")
                    .append(escHtml(srcEntry.getKey())).append("</div>");
                html.append("<table style='border-collapse:collapse;font-size:11.5px;width:100%;margin-bottom:10px;'>");
                for (Map.Entry<String, String> field : srcEntry.getValue().entrySet()) {
                    if (field.getValue() == null || field.getValue().isEmpty()) continue;
                    html.append("<tr><td style='padding:3px 8px;border-bottom:1px solid #eee;color:#666;font-weight:500;white-space:nowrap;width:180px;'>")
                        .append(escHtml(field.getKey())).append("</td><td style='padding:3px 8px;border-bottom:1px solid #eee;'>")
                        .append(escHtml(field.getValue())).append("</td></tr>");
                }
                html.append("</table>");
            }
            html.append("</div>");
        }

        if (itemCount == 0) {
            // Items not found in any cache — try SQL as last resort
            try {
                for (String pn : itemNumbers) {
                    String sql = "SELECT PART_NUMBER, DESCRIPTION, REV, STATUSCODE, LIFECYCLE_PHASE, NEW_PART_CLASS, PRODUCTLINE, SUBCONTRACTORS FROM item_extract WHERE PART_NUMBER = '" + pn.replace("'", "''") + "'";
                    try (java.sql.Connection conn = customDataSource.getConnection();
                         java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setQueryTimeout(5);
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                java.sql.ResultSetMetaData meta = rs.getMetaData();
                                html.append("<div style='margin-bottom:16px;'>");
                                html.append("<p style='font-weight:600;font-size:14px;margin:0 0 8px;'>").append(escHtml(pn)).append("</p>");
                                html.append("<div style='font-size:10.5px;color:#888;font-family:monospace;text-transform:uppercase;margin-bottom:4px;'>Database (item_extract)</div>");
                                html.append("<table style='border-collapse:collapse;font-size:11.5px;width:100%;margin-bottom:10px;'>");
                                for (int i = 1; i <= meta.getColumnCount(); i++) {
                                    String val = rs.getString(i);
                                    if (val != null && !val.isEmpty()) {
                                        html.append("<tr><td style='padding:3px 8px;border-bottom:1px solid #eee;color:#666;font-weight:500;white-space:nowrap;width:180px;'>")
                                            .append(escHtml(meta.getColumnLabel(i))).append("</td><td style='padding:3px 8px;border-bottom:1px solid #eee;'>")
                                            .append(escHtml(val)).append("</td></tr>");
                                    }
                                }
                                html.append("</table></div>");
                                itemCount++;
                            }
                        }
                    }
                }
            } catch (Exception sqlEx) {
                logger.info("[AI HELP] SQL fallback lookup failed: " + sqlEx.getMessage());
            }
        }
        if (itemCount == 0) {
            if (wantsExternal) {
                StringBuilder notFound = new StringBuilder();
                notFound.append("<p>I looked up <strong>").append(itemNumbers.size()).append("</strong> item(s) but couldn't find data in any source.</p>");
                notFound.append("<p style='font-size:12px;'>Items searched: <code>").append(escHtml(String.join(", ", itemNumbers))).append("</code></p>");
                return notFound.toString();
            }
            return null;
        }

        // Save looked-up items for follow-ups
        if (username != null) lastItemLookup.put(username, itemNumbers);

        activityLogger.log(username, displayName, "AI_SKU_LOOKUP",
            "Items: " + String.join(", ", itemNumbers.subList(0, Math.min(itemNumbers.size(), 5))));

        return html.toString();
    }

    private String tryExternalBulkQuery(String question, String lower, String keyword, String username, String displayName) {
        // Find which external source has a column matching the keyword
        List<Map<String, String>> sources = externalSourceService.getSourceList();
        String matchedSourceId = null;
        String matchedSourceName = null;
        String matchedColumn = null;

        for (Map<String, String> src : sources) {
            List<String> headers = externalSourceService.getSourceHeaders(src.get("id"));
            for (String h : headers) {
                String hLower = h.toLowerCase();
                String hFriendly = hLower.replace("_", " ");
                // Match: keyword in column name, or column friendly name in question
                if (hLower.contains(keyword) || keyword.contains(hLower)
                    || lower.contains(hFriendly) || lower.contains(hLower)) {
                    matchedSourceId = src.get("id");
                    matchedSourceName = src.get("name");
                    matchedColumn = h;
                    break;
                }
            }
            if (matchedSourceId != null) break;
        }

        if (matchedSourceId == null) return null;

        // Determine if user wants count, list, or both
        boolean wantsCount = lower.contains("how many") || lower.contains("count");
        boolean wantsList = lower.contains("list") || lower.contains("show") || lower.contains("give me")
            || lower.contains("sample") || lower.contains("display") || lower.contains("find");

        // Extract a filter value if specified (e.g., "UPC value starting with 619...")
        String filterValue = null;
        java.util.regex.Matcher valMatcher = java.util.regex.Pattern.compile(
            "(?:value|=|equals|containing|with|of)\\s+['\"]?([A-Za-z0-9]+)['\"]?", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(question);
        if (valMatcher.find()) {
            String candidate = valMatcher.group(1);
            if (!candidate.equalsIgnoreCase("a") && !candidate.equalsIgnoreCase("the")
                && !candidate.equalsIgnoreCase("and") && candidate.length() > 1) {
                filterValue = candidate;
            }
        }

        try {
            int total = externalSourceService.countRecords(matchedSourceId, matchedColumn, filterValue);
            List<Map<String, String>> sample = (wantsList || !wantsCount)
                ? externalSourceService.searchRecords(matchedSourceId, matchedColumn, filterValue, 50)
                : Collections.emptyList();

            StringBuilder html = new StringBuilder();
            html.append("<p><strong>").append(total).append("</strong> items have a <strong>")
                .append(escHtml(matchedColumn)).append("</strong> value in <strong>")
                .append(escHtml(matchedSourceName)).append("</strong>");
            if (filterValue != null) html.append(" containing '").append(escHtml(filterValue)).append("'");
            html.append(".</p>");
            html.append("<p style='font-size:11px;color:#28a745;'>\u26A1 Source: ").append(escHtml(matchedSourceName)).append(" (External JSON)</p>");

            if (!sample.isEmpty()) {
                int showCount = Math.min(sample.size(), 50);
                html.append("<p>").append(total > showCount ? "Showing first " + showCount + " of " + total + ":" : showCount + " results:").append("</p>");

                // Get column headers from first record
                List<String> cols = new ArrayList<>(sample.get(0).keySet());

                html.append("<div style='overflow-x:auto;margin:8px 0;'>");
                html.append("<table style='border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;'>");
                html.append("<tr>");
                for (String col : cols) {
                    html.append("<th style='text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;white-space:nowrap;font-size:10px;color:#666;'>")
                        .append(escHtml(col)).append("</th>");
                }
                html.append("</tr>");
                for (Map<String, String> row : sample) {
                    html.append("<tr>");
                    for (String col : cols) {
                        String val = row.getOrDefault(col, "");
                        html.append("<td style='padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;'>")
                            .append(escHtml(val)).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table></div>");

                // Export button — encode the query params for the export endpoint
                String exportParams = java.util.Base64.getEncoder().encodeToString(
                    (matchedSourceId + "|" + matchedColumn + "|" + (filterValue != null ? filterValue : "")).getBytes(StandardCharsets.UTF_8));
                html.append("<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>");
                html.append("<button onclick=\"clearChatQueryContext(this)\" ")
                    .append("style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>");
                html.append("<button onclick=\"exportExtSource(this.dataset.params,this)\" data-params=\"").append(exportParams).append("\" ")
                    .append("style='font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;'>")
                    .append("\u2913 Export all ").append(total).append(" to Excel</button>");
                html.append("</div>");
            }

            // Save context for follow-ups
            lastExtQuery.put(username, matchedSourceId + "|" + matchedColumn + "|" + (filterValue != null ? filterValue : ""));

            activityLogger.log(username, displayName, "AI_EXT_LOOKUP",
                "Source: " + matchedSourceName + " | Column: " + matchedColumn + " | Count: " + total);

            return html.toString();
        } catch (Exception e) {
            logger.warning("[AI HELP] External bulk query failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isCommonField(String key) {
        String k = key.toUpperCase();
        return k.equals("PART_NUMBER") || k.equals("DESCRIPTION") || k.equals("REV")
            || k.equals("STATUSCODE") || k.equals("LIFECYCLE_PHASE") || k.equals("NEW_PART_CLASS")
            || k.equals("PRODUCTLINE") || k.equals("SUBCONTRACTORS") || k.equals("MATERIAL_GROUP")
            || k.equals("ACTUAL_BUILD_PLANT") || k.equals("RELEASE_DATE") || k.equals("CATEGORY");
    }

    // =========================================================================
    // Data query — AI generates SQL against item_extract, executes read-only
    // =========================================================================

    private static final String[] ITEM_EXTRACT_COLUMNS = {
        "PART_NUMBER", "DESCRIPTION", "REV", "STATUSCODE", "LIFECYCLE_PHASE",
        "NEW_PART_CLASS", "MATERIAL_GROUP", "MATERIAL_TYPE", "PRODUCTLINE",
        "SUBCONTRACTORS", "ACTUAL_BUILD_PLANT", "PRODUCT_TYPE", "CATEGORY",
        "PM", "CAPACITY", "FAMILYNAME", "CUSTOMER_PN", "RELEASE_DATE",
        "CREATE_DATE", "PRODUCTDIVISION", "PROGRAM_NAME", "MARKETING_PROGRAM",
        "AS_SOLD", "GOLDEN_PART", "ECCN", "CLASSIFICATION_PDM",
        "CONTROLLER_TECHNOLOGY", "MEMORY_PACKAGE", "PKG_TYPE", "BOM_TYPE"
    };

    private boolean isDataQuery(String q, String username) {
        if (q == null) return false;
        String lower = q.toLowerCase();
        // Match questions that want to look up or search PLM data
        boolean isNewQuery = (lower.contains("list") || lower.contains("find") || lower.contains("show")
            || lower.contains("give me") || lower.contains("how many") || lower.contains("count")
            || lower.contains("which parts") || lower.contains("what parts")
            || lower.contains("items with") || lower.contains("items where")
            || lower.contains("parts with") || lower.contains("parts where")
            || lower.contains("look up") || lower.contains("lookup") || lower.contains("query"))
            && (lower.contains("part") || lower.contains("item") || lower.contains("material")
                || lower.contains("product") || lower.contains("sku") || lower.contains("subcon")
                || lower.contains("lifecycle") || lower.contains("status") || lower.contains("plant")
                || lower.contains("capacity") || lower.contains("golden") || lower.contains("eccn")
                || lower.contains("released") || lower.contains("created") || lower.contains("obsolete")
                || lower.contains("active") || lower.contains("resistor") || lower.contains("capacitor")
                || lower.contains("die") || lower.contains("assembly") || lower.contains("connector")
                || lower.matches(".*\\b[a-z]*\\d{2}-\\d{2}.*") || lower.matches(".*\\b[a-z]\\d{3}.*"));
        if (isNewQuery) return true;

        // Follow-up detection: if this user has an active data query, treat almost
        // everything as a follow-up UNLESS it's clearly a general help question
        if (username != null && lastDataSql.containsKey(username)) {
            boolean isGeneralHelp = (lower.contains("what does") && lower.contains("tab"))
                || (lower.contains("how do i use"))
                || lower.contains("help me with")
                || lower.contains("what is plm")
                || lower.contains("raise issue")
                || lower.contains("saved quer")
                || lower.contains("my quer")
                || lower.contains("feedback");
            return !isGeneralHelp;
        }
        return false;
    }

    // =========================================================================
    // Item Cache query — asks AI for filter spec, evaluates against in-memory cache
    // =========================================================================

    private String tryCacheQuery(String question, String username, String displayName, boolean usePortkey) {
        try {
            String cachedCols = String.join(", ", com.sandisk.plm.tracker.service.ItemCacheService.CACHED_COLUMNS);
            java.util.Date now = new java.util.Date();
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(now);
            String nowTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(now);

            // Include previous query context for follow-ups
            String prevSql = lastDataSql.get(username);
            String contextHint = "";
            if (prevSql != null && !prevSql.isEmpty()) {
                String prevContext = prevSql;
                // Strip JSON_CACHE: prefix for cleaner context
                if (prevContext.startsWith("JSON_CACHE:")) prevContext = prevContext.substring(11);
                contextHint = "\n\nPrevious query filter (for follow-ups): " + prevContext +
                    "\nIf this is a follow-up, keep previous filters and add/modify as requested." +
                    "\nIf user says 'list them'/'show them', set count_only to false and return the same filters." +
                    "\nIf user says 'also include X column', add it to select list." +
                    "\nIf user says 'only ACT', add that filter to the existing ones.";
            }

            String systemPrompt = "Current date and time is " + nowTime + ". " +
                "You are a data filter assistant. The user wants to query an in-memory item database. " +
                "Available columns: " + cachedCols + ". " +
                "Return ONLY a JSON object with these fields:\n" +
                "{\n" +
                "  \"select\": [\"PART_NUMBER\", \"DESCRIPTION\", ...],  // columns to show\n" +
                "  // IMPORTANT: Always include these default columns: PART_NUMBER, DESCRIPTION, REV, LIFECYCLE_PHASE, NEW_PART_CLASS, PRODUCTLINE, CREATE_DATE, REV_RELEASE_DATE — plus any columns the user specifically asks about or filters on\n" +
                "  \"filters\": [\n" +
                "    {\"col\": \"COLUMN_NAME\", \"op\": \"eq|neq|like|in|notin|gt|lt|gte|lte|notnull|null\", \"val\": \"value or [array]\"}\n" +
                "  ],\n" +
                "  \"sort\": {\"col\": \"COLUMN_NAME\", \"dir\": \"asc|desc\"},  // optional\n" +
                "  \"count_only\": false  // true if user only wants a count\n" +
                "}\n\n" +
                "Rules:\n" +
                "- 'like' op: val is a prefix/contains pattern. The engine will do case-insensitive contains matching.\n" +
                "- 'in' op: val is a JSON array of values, e.g. [\"ACT\",\"C-ACT\"]\n" +
                "- 'notin' op: val is a JSON array to exclude\n" +
                "- For date filters: REV_RELEASE_DATE is VARCHAR 'DD-MON-YYYY HH:MI:SS AM' (e.g. '15-APR-2026 02:30:00 PM'). CREATE_DATE is VARCHAR 'MM-DD-YYYY HH:MI:SS AM' (e.g. '04-15-2026 02:30:00 PM'). The cache engine handles the format internally — just pass 'YYYY-MM-DD' for the val.\n" +
                "- Use 'gt'/'gte' with val as 'YYYY-MM-DD HH:mm' for date+time or just 'YYYY-MM-DD' for date-only\n" +
                "- For 'last 1 hour': val = current datetime minus 1 hour. For 'last 3 hours': minus 3 hours. Use format 'YYYY-MM-DD HH:mm'\n" +
                "- 'last week' = " + today + " minus 7 days, 'last month' = minus 30, 'last 6 months' = minus 180, 'this year' = 'YYYY-01-01'\n" +
                "- Always include every filtered column in the select list\n" +
                "- When user says 'item type'/'part type' → NEW_PART_CLASS, 'product line' → PRODUCTLINE, 'build plant' → ACTUAL_BUILD_PLANT\n" +
                "- Common NEW_PART_CLASS values: SKU, Part, Resistor, Capacitor, Connector, IC, Diode, Assembly, PCB, Module\n" +
                "- Common LIFECYCLE_PHASE values: DEV, PROTO, PPROD, C-ACT, ACT, MKT, EOL, OBS\n" +
                "- When user says 'SKUs' → filter NEW_PART_CLASS = 'SKU'. When 'resistors' → NEW_PART_CLASS = 'Resistor'\n" +
                "- SUBCONTRACTORS is comma-separated: use 'like' op for partial match\n" +
                "- PRODUCTLINE is semicolon-separated multi-value (e.g. '1049 - Client External - SATA;1032 - Client - SATA'). Use 'like' with the code number (e.g. '1049') not 'eq'.\n" +
                getLearningsContext() +
                "- Part number patterns: '54-90s' → PART_NUMBER like '54-90', 'A190s' → like 'A190'\n" +
                "- Return ONLY the JSON, nothing else — no markdown, no explanation" +
                contextHint;

            String filterJson = callAiForSql(systemPrompt, question, usePortkey);
            if (filterJson == null || filterJson.isEmpty()) return null;

            // Clean up
            filterJson = filterJson.trim();
            if (filterJson.startsWith("```")) {
                int nl = filterJson.indexOf('\n');
                filterJson = nl > 0 ? filterJson.substring(nl + 1) : filterJson.substring(3);
                if (filterJson.endsWith("```")) filterJson = filterJson.substring(0, filterJson.length() - 3);
                filterJson = filterJson.trim();
            }

            // Parse the filter spec
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = new ObjectMapper().readValue(filterJson, Map.class);
            @SuppressWarnings("unchecked")
            List<String> selectCols = (List<String>) spec.getOrDefault("select", Arrays.asList("PART_NUMBER", "DESCRIPTION", "REV"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filters = (List<Map<String, Object>>) spec.getOrDefault("filters", Collections.emptyList());
            boolean countOnly = Boolean.TRUE.equals(spec.get("count_only"));
            @SuppressWarnings("unchecked")
            Map<String, String> sort = (Map<String, String>) spec.get("sort");

            // Safety: if AI returned empty filters but there was a previous query, reuse previous filters
            if (filters.isEmpty() && prevSql != null && prevSql.startsWith("JSON_CACHE:")) {
                try {
                    Map<String, Object> prevSpec = new ObjectMapper().readValue(prevSql.substring(11), Map.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> prevFilters = (List<Map<String, Object>>) prevSpec.get("filters");
                    if (prevFilters != null && !prevFilters.isEmpty()) {
                        filters.addAll(prevFilters);
                        logger.info("[AI HELP] Empty filter from AI — reusing previous filters");
                    }
                } catch (Exception ignored) {}
            }

            // Auto-correct filters
            filters.removeIf(f -> {
                String fCol = ((String) f.getOrDefault("col", "")).toUpperCase();
                String fVal = String.valueOf(f.getOrDefault("val", ""));
                // Remove STATUSCODE='Released' — it's always wrong
                if ("STATUSCODE".equals(fCol) && "Released".equalsIgnoreCase(fVal)) {
                    logger.info("[AI HELP] Auto-removed STATUSCODE='Released' filter (invalid value)");
                    return true;
                }
                return false;
            });
            for (Map<String, Object> f : filters) {
                String fCol = ((String) f.getOrDefault("col", "")).toUpperCase();
                String fOp = (String) f.getOrDefault("op", "");
                // Force 'like' for NEW_PART_CLASS (values are prefixed like E011-Resistor)
                if ("NEW_PART_CLASS".equals(fCol) && "eq".equals(fOp)) {
                    f.put("op", "like");
                    logger.info("[AI HELP] Auto-corrected NEW_PART_CLASS filter from 'eq' to 'like'");
                }
                // PM values are stored as 'P - Purchase', 'M - Make', etc.
                if ("PM".equals(fCol) && "eq".equals(fOp)) {
                    f.put("op", "like");
                    logger.info("[AI HELP] Auto-corrected PM filter from 'eq' to 'like' (values are 'P - Purchase' format)");
                }
                // PRODUCTLINE is semicolon-delimited multi-value (e.g. "1049 - Client External - SATA;1032 - Client - SATA")
                if ("PRODUCTLINE".equals(fCol) && "eq".equals(fOp)) {
                    f.put("op", "like");
                    logger.info("[AI HELP] Auto-corrected PRODUCTLINE filter from 'eq' to 'like' (multi-value field)");
                }
            }

            // Check all columns are in cache
            for (String c : selectCols) {
                if (!itemCacheService.hasColumn(c)) {
                    logger.info("[AI HELP] Cache miss on column " + c + " — falling back to SQL");
                    return null; // fall back to SQL
                }
            }
            for (Map<String, Object> f : filters) {
                if (!itemCacheService.hasColumn((String) f.get("col"))) {
                    logger.info("[AI HELP] Cache miss on filter column " + f.get("col") + " — falling back to SQL");
                    return null;
                }
            }

            logger.info("[AI HELP] Query source: Item Cache | Filter: " + filterJson.replace("\n", " "));

            // Build the predicate
            java.util.function.BiPredicate<String, Map<String, String>> predicate = (key, rec) -> {
                for (Map<String, Object> f : filters) {
                    String col = ((String) f.get("col")).toUpperCase();
                    String op = (String) f.get("op");
                    Object val = f.get("val");
                    String recVal = rec.getOrDefault(col, "");

                    switch (op) {
                        case "eq": if (!recVal.equalsIgnoreCase(String.valueOf(val))) return false; break;
                        case "neq": if (recVal.equalsIgnoreCase(String.valueOf(val))) return false; break;
                        case "like": if (!recVal.toUpperCase().contains(String.valueOf(val).toUpperCase())) return false; break;
                        case "in": {
                            @SuppressWarnings("unchecked")
                            List<String> vals = (List<String>) val;
                            boolean found = false;
                            for (String v : vals) { if (recVal.equalsIgnoreCase(v)) { found = true; break; } }
                            if (!found) return false;
                            break;
                        }
                        case "notin": {
                            @SuppressWarnings("unchecked")
                            List<String> vals = (List<String>) val;
                            for (String v : vals) { if (recVal.equalsIgnoreCase(v)) return false; }
                            break;
                        }
                        case "notnull": if (recVal.isEmpty()) return false; break;
                        case "null": if (!recVal.isEmpty()) return false; break;
                        case "gt": case "gte": case "lt": case "lte": {
                            // Date comparison for REV_RELEASE_DATE / CREATE_DATE
                            if (recVal.isEmpty()) return false;
                            try {
                                String dateStr = recVal.length() >= 11 ? recVal.substring(0, 11) : recVal;
                                java.util.Date recDate = new java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH).parse(dateStr);
                                java.util.Date filterDate = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(String.valueOf(val));
                                int cmp = recDate.compareTo(filterDate);
                                if ("gt".equals(op) && cmp <= 0) return false;
                                if ("gte".equals(op) && cmp < 0) return false;
                                if ("lt".equals(op) && cmp >= 0) return false;
                                if ("lte".equals(op) && cmp > 0) return false;
                            } catch (Exception e) { return false; }
                            break;
                        }
                    }
                }
                return true;
            };

            // Execute with timing — try indexed lookup first, fall back to scan
            long cacheT0 = System.currentTimeMillis();

            // Attempt indexed execution
            Set<String> indexedResults = tryIndexedLookup(filters);

            if (indexedResults != null) {
                logger.info("[AI HELP] Using indexed lookup (" + indexedResults.size() + " matches)");
                if (countOnly) {
                    int total = indexedResults.size();
                    long cacheMs = System.currentTimeMillis() - cacheT0;
                    lastDataSql.put(username, "JSON_CACHE:" + filterJson);
                    if (total == 0) return buildZeroResultMessage(filters, cacheMs);
                    String filterSummary = buildFilterSummary(filters);
                    return "<p><strong>" + total + "</strong> items match your query.</p>" +
                        "<p style='font-size:11px;color:#888;margin:4px 0 8px;font-family:monospace;'>" + escHtml(filterSummary) + "</p>" +
                        "<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>" +
                        "<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>" +
                        "<span style='font-size:11px;color:#28a745;'>\u26A1 Item Cache \u00b7 " + cacheMs + "ms \u00b7 indexed</span></div>";
                }
                int totalCount = indexedResults.size();
                List<Map<String, String>> preview = new ArrayList<>();
                for (String pn : indexedResults) {
                    preview.add(itemCacheService.getRecord(pn));
                    if (preview.size() >= 50) break;
                }
                long cacheMs = System.currentTimeMillis() - cacheT0;
                lastDataSql.put(username, "JSON_CACHE:" + filterJson);
                return buildCacheResultHtml(preview, selectCols, totalCount, cacheMs, filterJson, username, displayName, question, sort);
            }

            // Fall back to predicate scan
            if (countOnly) {
                int total = itemCacheService.count(predicate);
                long cacheMs = System.currentTimeMillis() - cacheT0;
                String pseudoSql = "JSON_CACHE:" + filterJson;
                lastDataSql.put(username, pseudoSql);

                String scanFilterSummary = buildFilterSummary(filters);
                if (total == 0) return buildZeroResultMessage(filters, cacheMs);
                return "<p><strong>" + total + "</strong> items match your query.</p>" +
                    "<p style='font-size:10.5px;color:#888;margin:4px 0 8px;font-family:monospace;word-break:break-word;'>" + escHtml(scanFilterSummary) + "</p>" +
                    "<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>" +
                    "<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>" +
                    "<span style='font-size:11px;color:#28a745;'>\u26A1 Item Cache \u00b7 " + cacheMs + "ms \u00b7 " + itemCacheService.size() + " records scanned</span></div>";
            }

            // Get rows (preview 50)
            // Get total count first (fast scan, no object creation)
            int totalCount = itemCacheService.count(predicate);
            // Get preview rows (only 50 for display)
            List<Map<String, String>> allMatches = itemCacheService.search(predicate, 50);

            // Sort if requested
            if (sort != null && sort.get("col") != null) {
                String sortCol = sort.get("col").toUpperCase();
                boolean desc = "desc".equalsIgnoreCase(sort.get("dir"));
                allMatches.sort((a, b) -> {
                    String va = a.getOrDefault(sortCol, "");
                    String vb = b.getOrDefault(sortCol, "");
                    // Try date sort for date columns
                    if (sortCol.contains("DATE")) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH);
                            java.util.Date da = sdf.parse(va.length() >= 11 ? va.substring(0, 11) : va);
                            java.util.Date db = sdf.parse(vb.length() >= 11 ? vb.substring(0, 11) : vb);
                            return desc ? db.compareTo(da) : da.compareTo(db);
                        } catch (Exception e) {}
                    }
                    return desc ? vb.compareToIgnoreCase(va) : va.compareToIgnoreCase(vb);
                });
            }

            List<Map<String, String>> preview = allMatches;

            long cacheMs = System.currentTimeMillis() - cacheT0;

            if (preview.isEmpty()) {
                lastDataSql.put(username, "JSON_CACHE:" + filterJson);
                return buildZeroResultMessage(filters, cacheMs);
            }

            // Save context
            lastDataSql.put(username, "JSON_CACHE:" + filterJson);

            // Use shared result builder (includes filter summary, enrichment, source label)
            return buildCacheResultHtml(preview, selectCols, totalCount, cacheMs, filterJson, username, displayName, question, sort);

        } catch (Exception e) {
            logger.info("[AI HELP] Cache query failed (" + e.getMessage() + ") — falling back to SQL");
            return null; // fall back to SQL
        }
    }

    /**
     * Detect compare-period intent ("compare 2025 to 2026", "YTD vs same period last year",
     * "year over year", "same window in 2026"). These queries need two-subquery SQL —
     * the single-filter JSON cache path can't represent them.
     */
    private static final java.util.regex.Pattern COMPARE_PERIOD_PATTERN = java.util.regex.Pattern.compile(
        "\\bcompare\\b|\\bvs\\b|\\bversus\\b|year[\\s-]?over[\\s-]?year|\\byoy\\b|" +
        "same\\s+(period|window|time)|prior\\s+(period|year|quarter)|previous\\s+(period|year|quarter)|" +
        "ytd\\s+vs|year[\\s-]?on[\\s-]?year",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private boolean isComparePeriodQuestion(String question) {
        if (question == null) return false;
        return COMPARE_PERIOD_PATTERN.matcher(question).find();
    }

    private String tryDataQuery(String question, String username, String displayName) {
        boolean usePortkey = portkeyEnabled && portkeyApiKey != null && !portkeyApiKey.isEmpty();
        if (!usePortkey && (apiKey == null || apiKey.isEmpty())) return null;

        // Compare-style queries (e.g. "compare 2025 to 2026", "YTD vs same period last year")
        // can't be expressed in the single-filter JSON cache spec — they need SQL with two
        // count subqueries from DUAL. Skip the cache for those and go straight to SQL.
        boolean isCompareQuery = isComparePeriodQuestion(question);
        if (isCompareQuery) {
            logger.info("[AI HELP] Compare-period question detected — skipping cache, using SQL");
        }

        // Try Item Cache first (if loaded and not a compare query)
        if (itemCacheService.isLoaded() && !isCompareQuery) {
            String cacheResult = tryCacheQuery(question, username, displayName, usePortkey);
            if (cacheResult != null) return cacheResult;
        }

        // Hoisted so the catch block can reference them for retry-with-fix
        String systemPrompt = null;
        String sql = null;

        try {
            // Fallback: SQL path (for columns not in cache or if cache query fails)
            // Step 1: Ask AI to generate SQL
            String colList = String.join(", ", ITEM_EXTRACT_COLUMNS);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
            systemPrompt = "Today's date is " + today + ". " +
                "You are a SQL assistant for an Oracle database. " +
                "The user wants data from the ITEM_EXTRACT table. " +
                "Available columns: " + colList + ". " +
                "Generate ONLY a SELECT statement. Rules:\n" +
                "- Table name: item_extract\n" +
                "- Always include PART_NUMBER in SELECT\n" +
                "- Use UPPER() for case-insensitive string comparisons\n" +
                "- Use LIKE with % for partial matches\n" +
                "- Max 50 rows: add FETCH FIRST 50 ROWS ONLY\n" +
                "- For counts, use SELECT COUNT(*) FROM item_extract WHERE ...\n" +
                "- NO UPDATE, DELETE, INSERT, DROP, ALTER, CREATE, or any DDL/DML\n" +
                "- NO semicolons\n" +
                "- Common LIFECYCLE_PHASE values: DEV, PROTO, PPROD, C-ACT, ACT, MKT, EOL, OBS\n" +
                "- Common STATUSCODE values: ACT, Preliminary, Pending, Hold (NOT 'Released' — STATUSCODE is never 'Released')\n" +
                "- IMPORTANT: When user says 'released', NEVER use STATUSCODE. ALWAYS use REV_RELEASE_DATE with a date filter built per the date-handling rules below.\n" +
                "- MATERIAL_GROUP examples: DIEAABIN, DIEMMKGD, DIEMEM, HALM, HALB, etc.\n" +
                "- IMPORTANT: For 'released' or 'release date' queries, use the REV_RELEASE_DATE column (NOT RELEASE_DATE). RELEASE_DATE may be empty.\n" +
                "- DATE COLUMNS — REV_RELEASE_DATE and CREATE_DATE are VARCHAR strings, not real DATE/TIMESTAMP columns. They use DIFFERENT formats:\n" +
                "    REV_RELEASE_DATE → 'DD-MON-YYYY HH:MI:SS AM' (e.g. '15-APR-2026 02:30:00 PM') — month is the 3-letter English abbreviation\n" +
                "    CREATE_DATE → 'MM-DD-YYYY HH:MI:SS AM' (e.g. '04-15-2026 02:30:00 PM') — month is a numeric digit pair\n" +
                "- DATE FILTERING (mandatory) — for ANY comparison, BETWEEN, ORDER BY, or arithmetic on these columns, ALWAYS wrap them with TO_DATE(SUBSTR(col, 1, N), '<format>') using the matching format for that column:\n" +
                "    REV_RELEASE_DATE → TO_DATE(SUBSTR(REV_RELEASE_DATE, 1, 11), 'DD-MON-YYYY')\n" +
                "    CREATE_DATE → TO_DATE(SUBSTR(CREATE_DATE, 1, 10), 'MM-DD-YYYY')\n" +
                "  NEVER use the column directly with =, <, >, BETWEEN, EXTRACT, or TRUNC. NEVER swap the two formats — using 'DD-MON-YYYY' on CREATE_DATE triggers ORA-01843 ('not a valid month') because '17' is not a month name.\n" +
                "- DATE LITERALS — write as TO_DATE('YYYY-MM-DD','YYYY-MM-DD'). Do NOT use bare 'YYYY-MM-DD' string literals against these columns.\n" +
                "- ALWAYS prefix any date filter with `col IS NOT NULL` to skip empty rows that would break TO_DATE.\n" +
                "- CANONICAL EXAMPLES — copy these patterns exactly:\n" +
                "    REV_RELEASE_DATE IS NOT NULL AND TO_DATE(SUBSTR(REV_RELEASE_DATE,1,11),'DD-MON-YYYY') >= TO_DATE('2025-01-01','YYYY-MM-DD')\n" +
                "    CREATE_DATE IS NOT NULL AND TO_DATE(SUBSTR(CREATE_DATE,1,10),'MM-DD-YYYY') BETWEEN TO_DATE('2025-01-01','YYYY-MM-DD') AND TO_DATE('2025-12-31','YYYY-MM-DD')\n" +
                "    ORDER BY TO_DATE(SUBSTR(REV_RELEASE_DATE,1,11),'DD-MON-YYYY') DESC\n" +
                "- 'this year' = current calendar year. 'last year' = previous calendar year. 'last week' = SYSDATE - 7. 'last month' = SYSDATE - 30. 'last 6 months' = SYSDATE - 180.\n" +
                "- COMPARE / YEAR-OVER-YEAR — when the user asks to compare two periods (e.g. 'compare 2025 to 2026', 'YTD vs same period last year'), return ONE SELECT producing both counts as named columns from DUAL using subqueries. Shape: SELECT (SELECT COUNT(*) FROM item_extract WHERE <period A>) AS PARTS_2025, (SELECT COUNT(*) FROM item_extract WHERE <period B>) AS PARTS_2026_YTD FROM DUAL. Never UNION two SELECTs. Never use ;.\n" +
                "- NEVER include apostrophes (') inside SQL string literals — they break Oracle parsing (ORA-01756). If the user's term contains an apostrophe, drop it.\n" +
                "- When sorting or ordering, always include the sorted column in SELECT so results show it\n" +
                "- IMPORTANT: Always include every column used in the WHERE clause in the SELECT list so the user can see what was filtered\n" +
                "- SUBCONTRACTORS is a comma-separated multi-value field (e.g. 'C002,R010,C039'). Always use LIKE '%value%' not = 'value'\n" +
                "- Column name mappings (user says → actual column): " +
                "'item type' or 'part type' or 'type' → NEW_PART_CLASS, " +
                "'product line' or 'program' → PRODUCTLINE, " +
                "'build plant' or 'plant' → ACTUAL_BUILD_PLANT, " +
                "'class' or 'classification' → CLASSIFICATION_PDM, " +
                "'customer part' or 'customer pn' → CUSTOMER_PN, " +
                "'division' → PRODUCTDIVISION, " +
                "'program name' → PROGRAM_NAME, " +
                "'weight' → MC_WEIGHT, " +
                "'golden' → GOLDEN_PART, " +
                "'capacity' or 'size' → CAPACITY, " +
                "'family' → FAMILYNAME, " +
                "'PM' or 'product manager' → PM\n" +
                "- NEW_PART_CLASS values are prefixed with codes like 'E011-Resistor', 'E001-Capacitor', 'OBS-SKU', 'E022-Connector', etc.\n" +
                "- IMPORTANT: For NEW_PART_CLASS, ALWAYS use 'like' op (not 'eq') because values have prefixes. Example: user says 'resistors' → {\"col\":\"NEW_PART_CLASS\",\"op\":\"like\",\"val\":\"Resistor\"}\n" +
                "- When user says 'SKUs', use like with val 'SKU'. When 'resistors' → like 'Resistor'. When 'capacitors' → like 'Capacitor'.\n" +
                "- 'finished goods' or 'FG' → try MATERIAL_TYPE like 'FERT' or NEW_PART_CLASS like 'SKU' (ask if unsure)\n" +
                "- If unsure what a term maps to, use 'like' with the user's word — it's better to get partial matches than 0\n" +
                "- When user asks 'which has the most' or 'highest rev' or 'most iterations', sort by that column descending and set count_only to false so they see the top results\n" +
                "- For 'PM code P' or 'PM = P', use exact match (eq), not contains (like). PM codes are short codes like 'P', 'B', 'D'.\n" +
                "- Part number patterns: '54-90s' means PART_NUMBER LIKE '54-90%', 'A190s' means PART_NUMBER LIKE 'A190%'\n" +
                "- When user says '54-90s/A190s', use OR: (PART_NUMBER LIKE '54-90%' OR PART_NUMBER LIKE 'A190%')\n" +
                "- 'last 6 months' = SYSDATE - 180, 'last 3 months' = SYSDATE - 90, 'last month' = SYSDATE - 30\n" +
                "- When user says 'active' or 'ACT' parts, use LIFECYCLE_PHASE IN ('ACT','C-ACT')\n" +
                "- When user says 'exclude OBS' or 'not obsolete', add AND LIFECYCLE_PHASE != 'OBS'\n" +
                "- Return ONLY the SQL, nothing else — no explanation, no markdown";

            // Include previous query as context for follow-ups like "exclude OBS" or "add lifecycle filter"
            String prevSql = lastDataSql.get(username);
            String userPrompt = question;
            if (prevSql != null && !prevSql.isEmpty() && !prevSql.startsWith("JSON_CACHE:")) {
                // If previous query was a COUNT and user wants rows, convert it to a SELECT in the context
                String prevForContext = prevSql;
                String qLowerCheck = question.toLowerCase();
                boolean userWantsRows = qLowerCheck.contains("list") || qLowerCheck.contains("show")
                    || qLowerCheck.contains("give") || qLowerCheck.contains("display")
                    || qLowerCheck.contains("them") || qLowerCheck.contains("those");
                if (prevForContext.toUpperCase().trim().startsWith("SELECT COUNT") && userWantsRows) {
                    // Convert COUNT to SELECT for context
                    String whereClause = "";
                    int whereIdx = prevForContext.toUpperCase().indexOf("WHERE");
                    if (whereIdx > 0) whereClause = prevForContext.substring(whereIdx);
                    prevForContext = "SELECT PART_NUMBER, DESCRIPTION, REV, STATUSCODE, LIFECYCLE_PHASE, REV_RELEASE_DATE, NEW_PART_CLASS FROM item_extract " + whereClause;
                    if (!prevForContext.toUpperCase().contains("FETCH")) prevForContext += " FETCH FIRST 50 ROWS ONLY";
                }

                userPrompt = "Previous query was:\n" + prevForContext + "\n\nNew request: " + question +
                    "\n\nIf this is a follow-up (amend, exclude, add filter, change, only include, list them, show them), modify the previous SELECT query. " +
                    "Keep the same SELECT columns. Do NOT change it to a COUNT query — always return a SELECT with data rows. " +
                    "If the user says 'list them' or 'show them', return the previous query as a SELECT (not COUNT). " +
                    "If it's a completely new question, generate a fresh query.";
            }

            sql = callAiForSql(systemPrompt, userPrompt, usePortkey);
            if (sql == null || sql.isEmpty()) return null;

            // Clean up AI response
            sql = sql.trim();
            if (sql.startsWith("```")) {
                int nl = sql.indexOf('\n');
                sql = nl > 0 ? sql.substring(nl + 1) : sql.substring(3);
                if (sql.endsWith("```")) sql = sql.substring(0, sql.length() - 3);
                sql = sql.trim();
            }
            if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();

            // If AI returned COUNT but user wanted rows, rebuild from previous SELECT
            String qLower = question.toLowerCase();
            boolean wantsJustCount = qLower.contains("how many") || qLower.contains("count of");
            if (!wantsJustCount && sql.toUpperCase().trim().startsWith("SELECT COUNT") && prevSql != null) {
                // Extract the WHERE clause from the COUNT query
                int countWhereIdx = sql.toUpperCase().indexOf("WHERE");
                if (countWhereIdx > 0) {
                    String newWhere = sql.substring(countWhereIdx).replaceAll("(?i)FETCH\\s+FIRST.*", "").trim();
                    // Take SELECT + FROM from previous query, replace WHERE
                    String prev = prevSql.replaceAll("(?i)FETCH\\s+FIRST.*", "").trim();
                    int prevWhereIdx = prev.toUpperCase().indexOf("WHERE");
                    String selectFrom = prevWhereIdx > 0 ? prev.substring(0, prevWhereIdx).trim() : prev;
                    sql = selectFrom + " " + newWhere + " FETCH FIRST 50 ROWS ONLY";
                    logger.info("[AI HELP] Rebuilt SELECT from COUNT: " + sql.replace("\n", " "));
                }
            }

            // Safety: only allow SELECT
            String upper = sql.toUpperCase().trim();
            if (!upper.startsWith("SELECT")) {
                logger.warning("[AI HELP] Rejected non-SELECT SQL: " + sql);
                return null;
            }
            // Block dangerous keywords
            if (upper.contains("UPDATE ") || upper.contains("DELETE ") || upper.contains("INSERT ")
                || upper.contains("DROP ") || upper.contains("ALTER ") || upper.contains("CREATE ")
                || upper.contains("TRUNCATE") || upper.contains("MERGE ") || upper.contains("GRANT ")) {
                logger.warning("[AI HELP] Rejected dangerous SQL: " + sql);
                return null;
            }

            // Log full SQL — don't truncate
            logger.info("[AI HELP] Data query SQL: " + sql.replace("\n", " "));

            // Step 2: Execute — SQL with logging of cache availability
            List<Map<String, String>> rows = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            boolean usedCache = false;

            // Check if all columns are in the Item Cache
            // Parse SELECT columns from the SQL
            String upperSql = sql.toUpperCase();
            boolean cacheAvailable = itemCacheService.isLoaded();
            if (cacheAvailable && !upperSql.contains("COUNT(*)")) {
                // Extract column names between SELECT and FROM
                int selIdx = upperSql.indexOf("SELECT") + 6;
                int fromIdx = upperSql.indexOf("FROM");
                if (selIdx > 6 && fromIdx > selIdx) {
                    String colPart = sql.substring(selIdx, fromIdx).trim();
                    String[] requestedCols = colPart.split(",");
                    String missingCol = null;
                    for (String rc : requestedCols) {
                        String col = rc.trim().replaceAll("(?i)\\s+AS\\s+.*", "").trim();
                        if (!col.equals("*") && !itemCacheService.hasColumn(col)) {
                            missingCol = col;
                            break;
                        }
                    }
                    if (missingCol != null) {
                        logger.info("[AI HELP] Query source: SQL (column " + missingCol + " not in Item Cache)");
                    } else {
                        logger.info("[AI HELP] Query source: SQL (Item Cache has all columns, but using SQL for WHERE/ORDER flexibility)");
                    }
                }
            } else if (!cacheAvailable) {
                logger.info("[AI HELP] Query source: SQL (Item Cache not loaded — run Item Cache Seed)");
            } else {
                logger.info("[AI HELP] Query source: SQL (COUNT query)");
            }

            try (java.sql.Connection conn = customDataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(15);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));

                    int rowCount = 0;
                    while (rs.next() && rowCount < 50) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            String val = rs.getString(i);
                            row.put(columns.get(i - 1), val != null ? val : "");
                        }
                        rows.add(row);
                        rowCount++;
                    }
                }
            }

            // Run a count query to show total (not just the 50-row preview)
            int totalCount = rows.size();
            if (rows.size() >= 50) {
                try {
                    String countSql = "SELECT COUNT(*) FROM (" + sql.replaceAll("(?i)FETCH\\s+FIRST\\s+\\d+\\s+ROWS\\s+ONLY", "") + ")";
                    try (java.sql.Connection conn2 = customDataSource.getConnection();
                         java.sql.PreparedStatement ps2 = conn2.prepareStatement(countSql)) {
                        ps2.setQueryTimeout(15);
                        try (java.sql.ResultSet rs2 = ps2.executeQuery()) {
                            if (rs2.next()) totalCount = rs2.getInt(1);
                        }
                    }
                } catch (Exception ce) {
                    logger.warning("[AI HELP] Count query failed: " + ce.getMessage());
                }
            }

            // Step 3: Format results as HTML
            if (columns.size() == 1 && columns.get(0).toUpperCase().contains("COUNT")) {
                // Pure count query (user asked "how many")
                String count = rows.isEmpty() ? "0" : rows.get(0).values().iterator().next();
                // Save the COUNT SQL so "show me those items" can reference it
                lastDataSql.put(username, sql);
                return "<p><strong>" + count + "</strong> items match your query.</p>" +
                    "<p style='font-size:10.5px;color:#888;margin:4px 0 8px;font-family:monospace;word-break:break-word;'>" + escHtml(sql) + "</p>" +
                    "<div style='margin-top:10px;display:flex;gap:10px;align-items:center;'>" +
                    "<button onclick=\"clearChatQueryContext(this)\" style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;'>New Query</button>" +
                    "<span style='font-size:11px;color:#888;font-family:monospace;'>" + escHtml(sql) + "</span></div>";
            }

            if (rows.isEmpty()) {
                return "<p>No results found for your query.</p>" +
                    "<p style='font-size:11px;color:#888;margin-top:8px;font-family:monospace;'>" +
                    escHtml(sql) + "</p>";
            }

            StringBuilder html = new StringBuilder();
            html.append("<p><strong>");
            if (totalCount > rows.size()) {
                html.append(totalCount).append("</strong> total results (showing first ").append(rows.size()).append("):");
            } else {
                html.append(rows.size()).append("</strong> results:");
            }
            html.append("</p>");
            html.append("<div style='overflow-x:auto;margin:8px 0;'>");
            html.append("<table style='border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;'>");
            html.append("<tr>");
            for (String col : columns) {
                html.append("<th style='text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;white-space:nowrap;font-size:10px;color:#666;'>")
                    .append(escHtml(col)).append("</th>");
            }
            html.append("</tr>");
            for (Map<String, String> row : rows) {
                html.append("<tr>");
                for (String col : columns) {
                    String val = row.getOrDefault(col, "");
                    html.append("<td style='padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;'>")
                        .append(escHtml(val)).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table></div>");
            // Export button + SQL (use data attribute to avoid quote-escaping issues in onclick)
            // Base64-encode the SQL to safely embed it in HTML
            String sqlB64 = java.util.Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8));
            html.append("<div style='margin-top:10px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>");
            html.append("<button onclick=\"clearChatQueryContext(this)\" ")
                .append("style='font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;flex-shrink:0;'>")
                .append("New Query</button>");
            html.append("<button onclick=\"saveChatQuery(this.dataset.sql,this)\" data-sql=\"").append(sqlB64).append("\" ")
                .append("style='font-size:12px;padding:5px 12px;background:#fff;color:#e6a817;border:1px solid #e6a817;border-radius:5px;cursor:pointer;flex-shrink:0;'>")
                .append("\u2605 Save</button>");
            html.append("<button onclick=\"exportChatQuery(this.dataset.sql,this)\" data-sql=\"").append(sqlB64).append("\" ")
                .append("style='font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;flex-shrink:0;'>")
                .append("\u2913 Export all to Excel</button>");
            html.append("<span style='font-size:11px;color:#888;font-family:monospace;word-break:break-all;'>").append(escHtml(sql)).append("</span>");
            html.append("</div>");

            // Store last successful SQL for follow-up context
            lastDataSql.put(username, sql);

            activityLogger.log(username, displayName, "AI_DATA_QUERY",
                "Q: " + question.substring(0, Math.min(question.length(), 80)) + " | Rows: " + rows.size());

            return html.toString();

        } catch (java.sql.SQLException e) {
            String msg = e.getMessage();
            logger.warning("[AI HELP] Data query SQL error: " + msg);

            // Invalid column — suggest similar valid columns
            if (msg != null && msg.contains("ORA-00904")) {
                // Extract the invalid column name from the error
                java.util.regex.Matcher colMatch = java.util.regex.Pattern.compile("\"(\\w+)\"").matcher(msg);
                String badCol = colMatch.find() ? colMatch.group(1) : "";

                // Find close matches from ITEM_EXTRACT_COLUMNS
                List<String> suggestions = new ArrayList<>();
                String badLower = badCol.toLowerCase();
                for (String col : ITEM_EXTRACT_COLUMNS) {
                    if (col.toLowerCase().contains(badLower) || badLower.contains(col.toLowerCase().substring(0, Math.min(4, col.length())))) {
                        suggestions.add(col);
                    }
                }
                // If no substring match, show all columns
                if (suggestions.isEmpty()) {
                    // Pick 10 most likely based on simple relevance
                    for (String col : ITEM_EXTRACT_COLUMNS) {
                        suggestions.add(col);
                        if (suggestions.size() >= 10) break;
                    }
                }

                // Check if the column exists in an external source
                String extSourceId = externalSourceService.findSourceByColumn(badCol);
                if (extSourceId != null) {
                    // Column is in an external source — try the external query instead
                    String extResult = tryExternalBulkQuery(question, question.toLowerCase(), badCol.toLowerCase(), username, displayName);
                    if (extResult != null) return extResult;
                }

                return "<p>Column <code>" + escHtml(badCol) + "</code> does not exist in the item_extract table.</p>" +
                    "<p style='font-size:12.5px;'>Did you mean one of these?</p>" +
                    "<div style='display:flex;flex-wrap:wrap;gap:4px;margin:8px 0;'>" +
                    suggestions.stream().map(s -> "<code style='font-size:11px;padding:2px 6px;background:#f0f4ff;border:1px solid #d0e0f0;border-radius:3px;cursor:pointer;'>" + s + "</code>")
                        .reduce("", (a, b) -> a + b) +
                    "</div>" +
                    "<p style='color:#888;font-size:11.5px;'>Try asking again using one of these column names, or use the friendly name (e.g. 'item type' instead of 'PART_CLASS').</p>";
            }

            // Recoverable Oracle errors — ask the LLM to fix the SQL and retry once
            if (msg != null && (msg.contains("ORA-01756") || msg.contains("ORA-01843")
                || msg.contains("ORA-00911") || msg.contains("ORA-00936") || msg.contains("ORA-00933")
                || msg.contains("ORA-01722") || msg.contains("ORA-00907") || msg.contains("ORA-00920"))) {
                String retryHtml = retryWithFix(systemPrompt, sql, msg, question, username, displayName, usePortkey);
                if (retryHtml != null) return retryHtml;
            }

            return "<p>Query failed: <code>" + escHtml(msg) + "</code></p>" +
                "<p style='font-size:11px;color:#888;font-family:monospace;word-break:break-all;margin:6px 0;'>" + escHtml(sql) + "</p>" +
                "<p style='color:#888;font-size:12px;'>Try rephrasing your question. " +
                "I can search by: part number, material group, product line, lifecycle phase, status, description, subcontractors, and more.</p>";
        } catch (Exception e) {
            logger.warning("[AI HELP] Data query failed: " + e.getMessage());
            return null; // fall through to normal AI
        }
    }

    /**
     * Asks the LLM to correct a SQL that failed with a recoverable Oracle error,
     * then executes the corrected SQL and renders results. Returns null if the
     * retry can't produce a working query so the caller falls through to its
     * normal error response.
     */
    private String retryWithFix(String systemPrompt, String badSql, String oracleError,
                                 String question, String username, String displayName,
                                 boolean usePortkey) {
        try {
            String shortError = oracleError.split("\\n")[0];
            String fixPrompt = "The following Oracle SQL failed with error: " + shortError + "\n\n" +
                "FAILED SQL:\n" + badSql + "\n\n" +
                "ORIGINAL USER QUESTION: " + question + "\n\n" +
                "Generate a CORRECTED SELECT statement. Common fixes:\n" +
                "- ORA-01843 (not a valid month) → wrong TO_DATE format. REV_RELEASE_DATE is 'DD-MON-YYYY HH:MI:SS AM' (use TO_DATE(SUBSTR(col,1,11),'DD-MON-YYYY')). CREATE_DATE is 'MM-DD-YYYY HH:MI:SS AM' (use TO_DATE(SUBSTR(col,1,10),'MM-DD-YYYY')). The two columns have DIFFERENT formats — do not use 'DD-MON-YYYY' on CREATE_DATE.\n" +
                "- ORA-01756 (quoted string not properly terminated) → an unbalanced apostrophe in a string literal. Remove apostrophes from string values.\n" +
                "- ORA-00911 / ORA-00933 / ORA-00936 → syntax error. Remove semicolons. Avoid UNION; use subqueries from DUAL for compare-style queries.\n" +
                "- ORA-01722 (invalid number) → comparing a string column to a number. Wrap with TO_NUMBER or quote the value.\n" +
                "Return ONLY the corrected SQL, no explanation, no markdown.";

            String fixedSql = callAiForSql(systemPrompt, fixPrompt, usePortkey);
            if (fixedSql == null || fixedSql.isEmpty()) return null;

            fixedSql = fixedSql.trim();
            if (fixedSql.startsWith("```")) {
                int nl = fixedSql.indexOf('\n');
                fixedSql = nl > 0 ? fixedSql.substring(nl + 1) : fixedSql.substring(3);
                if (fixedSql.endsWith("```")) fixedSql = fixedSql.substring(0, fixedSql.length() - 3);
                fixedSql = fixedSql.trim();
            }
            if (fixedSql.endsWith(";")) fixedSql = fixedSql.substring(0, fixedSql.length() - 1).trim();

            String upper = fixedSql.toUpperCase().trim();
            if (!upper.startsWith("SELECT")) {
                logger.warning("[AI HELP] Retry produced non-SELECT — abandoning: " + fixedSql);
                return null;
            }
            if (upper.contains("UPDATE ") || upper.contains("DELETE ") || upper.contains("INSERT ")
                || upper.contains("DROP ") || upper.contains("ALTER ") || upper.contains("CREATE ")
                || upper.contains("TRUNCATE") || upper.contains("MERGE ") || upper.contains("GRANT ")) {
                logger.warning("[AI HELP] Retry produced dangerous SQL — abandoning: " + fixedSql);
                return null;
            }

            logger.info("[AI HELP] Retry SQL: " + fixedSql.replace("\n", " "));

            List<Map<String, String>> rows = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            try (java.sql.Connection conn = customDataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(fixedSql)) {
                ps.setQueryTimeout(15);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));
                    int rowCount = 0;
                    while (rs.next() && rowCount < 50) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            String val = rs.getString(i);
                            row.put(columns.get(i - 1), val != null ? val : "");
                        }
                        rows.add(row);
                        rowCount++;
                    }
                }
            }

            lastDataSql.put(username, fixedSql);
            activityLogger.log(username, displayName, "AI_DATA_QUERY",
                "Q: " + question.substring(0, Math.min(question.length(), 80)) + " | Retry rows: " + rows.size());

            StringBuilder html = new StringBuilder();
            html.append("<p style='font-size:11px;color:#1F8A4C;margin:0 0 6px;'>Auto-corrected after Oracle error.</p>");
            if (columns.size() == 1 && columns.get(0).toUpperCase().contains("COUNT")) {
                String count = rows.isEmpty() ? "0" : rows.get(0).values().iterator().next();
                html.append("<p><strong>").append(count).append("</strong> items match your query.</p>");
            } else if (rows.isEmpty()) {
                html.append("<p>No results found for your query.</p>");
            } else {
                html.append("<p><strong>").append(rows.size()).append("</strong> result").append(rows.size() == 1 ? "" : "s").append(":</p>");
                html.append("<div style='overflow-x:auto;margin:8px 0;'>");
                html.append("<table style='border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;'>");
                html.append("<tr>");
                for (String col : columns) {
                    html.append("<th style='text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;white-space:nowrap;font-size:10px;color:#666;'>")
                        .append(escHtml(col)).append("</th>");
                }
                html.append("</tr>");
                for (Map<String, String> row : rows) {
                    html.append("<tr>");
                    for (String col : columns) {
                        String val = row.getOrDefault(col, "");
                        html.append("<td style='padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;'>")
                            .append(escHtml(val)).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table></div>");
            }
            html.append("<p style='font-size:11px;color:#888;font-family:monospace;word-break:break-all;margin:6px 0;'>")
                .append(escHtml(fixedSql)).append("</p>");
            return html.toString();

        } catch (java.sql.SQLException e) {
            logger.warning("[AI HELP] Retry SQL also failed: " + e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warning("[AI HELP] Retry path failed: " + e.getMessage());
            return null;
        }
    }

    private String callAiForSql(String systemPrompt, String userQuestion, boolean usePortkey) throws Exception {
        String model = portkeyProvider + "/" + portkeyModel;
        logger.info("[AI HELP/SQL] Using Portkey gateway (" + model + ") via " + portkeyBaseUrl);
        try {
            return portkeyClient.chat(model, systemPrompt, userQuestion, 300);
        } catch (Exception e) {
            logger.warning("[AI HELP/SQL] Portkey call failed: " + e.getMessage());
            return null; // preserve original "return null on failure" semantics for caller
        }
    }

    /**
     * Curated navigation answers for the most common "how do I" / "where do I"
     * procedural questions. Returns null if the question doesn't match a known
     * pattern (chat falls through to RAG / LLM as normal).
     *
     * <p>Background: the help-docs RAG keyword-matches against uploaded slide
     * decks, which sometimes returns the wrong feature when terms overlap.
     * Example from PT-55: "where used of a part to SKU" matched the Single/Sole
     * Source slide deck because both mention "SKU" and "components". Example
     * from PT-57: "parts history change" pointed users at Utilities, but
     * post-PT-50 reorg the right tab is Change History &rarr; Audit Trail.
     *
     * <p>This intercept short-circuits with a hand-curated answer so users get
     * the right tab + steps deterministically.
     */
    private String tryNavHowToAnswer(String qLower) {
        if (qLower == null || qLower.isEmpty()) return null;
        boolean isHowQuestion = qLower.contains("how ") || qLower.contains("where do i")
                || qLower.contains("where can i") || qLower.contains("which tab")
                || qLower.contains("what tab");

        // Where-used / SKU look-up routing → BOM tab → Implode mode.
        boolean whereUsedHit = qLower.contains("where used") || qLower.contains("where-used")
                || qLower.contains("whereused") || qLower.contains("implode")
                || qLower.contains("what uses this") || qLower.contains("what products use")
                || qLower.contains("what skus use") || qLower.contains("which sku")
                || (qLower.contains("part to sku") || qLower.contains("part used in"));
        if (whereUsedHit && (isHowQuestion || qLower.startsWith("where") || qLower.startsWith("which"))) {
            return ""
                + "<p style='font-weight:600;color:#1a3a5c;margin:0 0 8px;'>BOM &rarr; Where-Used (Implode)</p>"
                + "<p style='margin:0 0 10px;'>To find out which higher-level assemblies (including SKUs) use a given part:</p>"
                + "<ol style='margin:0 0 10px 20px; padding:0;'>"
                + "<li>Open the <strong>BOM</strong> tab.</li>"
                + "<li>Pick the <strong>Where-Used (Implode)</strong> sub-tab.</li>"
                + "<li>Enter the part number(s) you want to look up &mdash; comma/space/newline separated, or drag-drop an Excel file.</li>"
                + "<li>Click <strong>Search</strong>. Top-level parents get the green <em>TOP</em> badge; SKU-level parents show up there too.</li>"
                + "<li>Need an Excel back with a new <em>Top-Level Parent(s)</em> column? Use <strong>Enhanced Where-Used file</strong> (Items tab &rarr; Part Extract has the same enrich button) &mdash; the toolkit reads your file, runs the where-used, and gives back the same sheet with one new column.</li>"
                + "</ol>"
                + "<p style='font-size:12px;color:#888;margin:0;'>Hint: in the BOM results, type <code>sku</code> in the Item Type filter to narrow to SKU-level parents only.</p>";
        }

        // Parts / item history routing → Change History tab.
        // PT-57: chat used to send people to Utilities; the right answer now points
        // at Change History &rarr; Audit Trail (PT-50 reorg) or &rarr; Revision History.
        boolean partsHistoryHit =
                qLower.contains("parts history") || qLower.contains("part history")
                || qLower.contains("item history") || qLower.contains("history of a part")
                || qLower.contains("history of an item") || qLower.contains("history of the part")
                || qLower.contains("history of the item") || qLower.contains("audit trail")
                || qLower.contains("what changed on") || qLower.contains("when did this change");
        if (partsHistoryHit) {
            return ""
                + "<p style='font-weight:600;color:#1a3a5c;margin:0 0 8px;'>Change History tab</p>"
                + "<p style='margin:0 0 10px;'>To see the change history of a part or item, head to the <strong>Change History</strong> tab (top-level nav) &mdash; not Utilities. The tab has three sub-tabs:</p>"
                + "<ul style='margin:0 0 10px 20px; padding:0;'>"
                + "<li><strong>Revision History</strong> &mdash; one row per revision/change order on an item, with lifecycle phase + release dates. Default sub-tab. Enter item numbers, click Search.</li>"
                + "<li><strong>Field Changes</strong> &mdash; field-level edits on items (e.g. <em>Inv/Planning.BOM Parameter List was &rarr; is Yes</em>). Pulled from <code>item_history</code>.</li>"
                + "<li><strong>Audit Trail</strong> &mdash; query the live Agile change-history with date / username / status filters. Pick <em>Item History</em> source for item-level events, or <em>Change History</em> source for ECN/ECR workflow events. Excel export available.</li>"
                + "</ul>"
                + "<p style='font-size:12px;color:#888;margin:0;'>Heads-up: prior versions of this chat pointed at Utilities. That was stale &mdash; the right home for item history is now Change History after the recent reorg.</p>";
        }

        return null;
    }

    /**
     * Live "who's actually on the toolkit right now" answer, sourced from
     * SessionRegistry — same 5-minute active-window the deploy.bat workflow uses.
     * Excludes nobody (just shows everyone active); the deploy endpoint does its
     * own admin-allowlist filtering. Returns a small table sorted by lastSeen.
     */
    /**
     * Curated answer for "who built this / how is AI used / what model does
     * this use"-style meta questions about the toolkit itself. Returns null if
     * the question isn't a meta question (caller continues with normal
     * activity-shaped routing).
     */
    private String tryMetaAboutToolkitAnswer(String q) {
        boolean asksWhoBuilt =
               q.contains("who built")
            || q.contains("who created")
            || q.contains("who made this")
            || q.contains("who made the")
            || q.contains("who developed")
            || q.contains("who wrote this")
            || q.contains("who designed this")
            || q.contains("who is behind this")
            || q.contains("who's behind this");
        boolean asksAboutAi =
               q.contains("how is ai used")
            || q.contains("how is the ai used")
            || q.contains("how does ai work")
            || q.contains("how does the ai")
            || q.contains("how ai is used")
            || q.contains("what ai does this")
            || q.contains("what ai model")
            || q.contains("what model does this")
            || q.contains("what models does this")
            || q.contains("what llm")
            || q.contains("which ai model")
            || q.contains("which llm")
            || q.contains("ai used in this")
            || q.contains("ai used here");
        boolean asksAboutStack =
               q.contains("what powers this")
            || q.contains("what tech stack")
            || q.contains("what's the stack")
            || q.contains("tech stack")
            || q.contains("what is this app")
            || q.contains("what is this site")
            || q.contains("what is the toolkit")
            || q.contains("about this site")
            || q.contains("about the toolkit");
        if (!asksWhoBuilt && !asksAboutAi && !asksAboutStack) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-size:13px; line-height:1.5;'>");

        if (asksWhoBuilt) {
            sb.append("<p><strong>Who built this</strong>: the PLM Toolkit was built by ")
              .append("<strong>Vikas Jindal</strong> (SanDisk PLM IT lead) with ")
              .append("<a href='https://claude.com/claude-code' target='_blank' style='color:#4a6fa5;'>Claude Code</a> ")
              .append("(Anthropic CLI) as the implementation partner. ")
              .append("First commit was on April 12, 2026.</p>");
        }
        if (asksAboutAi) {
            sb.append("<p><strong>How AI is used</strong>:</p>")
              .append("<ul style='margin:6px 0 10px 18px; padding:0;'>")
              .append("<li><strong>Help chatbot</strong> (this drawer) — KB-backed, with an LLM fallback for free-form questions.</li>")
              .append("<li><strong>Ask AI</strong> tab — natural-language queries over the activity log and Agile data.</li>")
              .append("<li><strong>AI Eval</strong> — synthetic personas + LLM grader to regression-test toolkit answers.</li>")
              .append("<li><strong>Smart upload</strong> — header-row + column detection on uploaded spreadsheets.</li>")
              .append("<li><strong>Feedback queue triage</strong> — auto-grades incoming feedback as easy/hard/have-questions and surfaces an effort estimate.</li>")
              .append("<li><strong>Gibberish gate</strong> — blocks low-signal text before it reaches the LLM.</li>")
              .append("</ul>")
              .append("<p>All LLM calls go through the <strong>Portkey</strong> gateway, which routes to Anthropic, Azure OpenAI, or Google Vertex depending on the task. No keys are embedded in client code.</p>");
        }
        if (asksAboutStack) {
            sb.append("<p><strong>Stack</strong>: Spring Boot 2.7 (Java 11) · Oracle (Agile <code>agprod</code>) · LDAP/AD · Apache POI for Excel · Python sidecar for the ECN report · Portkey LLM gateway · vanilla JS frontend. ")
              .append("Single JVM on a Windows server with a watchdog + atomic-deploy script; companion <code>plm-agile-service</code> microservice wraps the live Agile SDK.</p>");
        }

        sb.append("<p style='font-size:11px; color:#888; margin-top:8px;'>Built between April 12, 2026 and today &middot; ~99,000 lines of code &middot; 15 top-level tabs.</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String renderActiveSessionsAnswer() {
        final long fiveMin = 5L * 60L * 1000L;
        List<com.sandisk.plm.tracker.service.SessionRegistry.ActiveUser> active = sessionRegistry.listActive(fiveMin);
        if (active.isEmpty()) {
            return "<p>No one has an active session right now (last 5 minutes).</p>";
        }
        // Sort by most-recent activity first.
        active.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        long now = System.currentTimeMillis();
        StringBuilder html = new StringBuilder();
        html.append("<p>").append(active.size()).append(" user(s) with an <strong>active session</strong> (touched the toolkit in the last 5 min):</p>");
        html.append("<div style='overflow-x:auto;'><table style='border-collapse:collapse; width:100%; font-size:13px;'>");
        html.append("<thead><tr style='background:#2c3e50; color:#fff;'>")
            .append("<th style='text-align:left; padding:6px 10px;'>User</th>")
            .append("<th style='text-align:left; padding:6px 10px;'>Last activity</th>")
            .append("</tr></thead><tbody>");
        for (int i = 0; i < active.size(); i++) {
            com.sandisk.plm.tracker.service.SessionRegistry.ActiveUser u = active.get(i);
            long secsAgo = Math.max(0, (now - u.lastSeen) / 1000L);
            String label = u.displayName != null && !u.displayName.isEmpty() ? u.displayName : u.username;
            String bg = (i % 2 == 0) ? "#FAFAF7" : "#ffffff";
            html.append("<tr style='background:").append(bg).append(";'>")
                .append("<td style='padding:6px 10px; border-bottom:1px solid #E8E6DF;'>").append(escHtml(label)).append("</td>")
                .append("<td style='padding:6px 10px; border-bottom:1px solid #E8E6DF;'>").append(secsAgo).append("s ago</td>")
                .append("</tr>");
        }
        html.append("</tbody></table></div>");
        html.append("<p style='font-size:11px; color:#888; margin-top:6px;'>Source: in-memory <code>SessionRegistry</code> (live), not the 24-hr login-history table. A user counts as active if they\u2019ve touched the server in the last 5 min.</p>");
        return html.toString();
    }

    private String tryAdminActivityQuery(String question) {
        // Normalize spelled-out small numbers BEFORE the rest of the parser sees the
        // question. Without this, "logged in the last one hour" defaults to 24h
        // (parseTimeWindowMs regex requires \d+), then triggers the 90-day "last
        // time" widen-out (hasExplicitTimeWindow doesn't recognize "the last one
        // hour" either) — so the user asking about "one hour" gets back 884 logins
        // from 90 days. Mapping word→digit upstream fixes all three parsers at once.
        String q = normalizeWordNumbers(question.toLowerCase());

        // Meta-question intercept: "who built this site / how is AI used / what
        // model does this use" are about the toolkit itself, not about user
        // activity. They look superficially activity-shaped (start with "who",
        // contain "ai use") and would otherwise route to the user-activity
        // widget — which is what caused PT-62 (demo-time embarrassment). Bail
        // out with a curated answer before any activity routing fires.
        String meta = tryMetaAboutToolkitAnswer(q);
        if (meta != null) return meta;

        // Fast path: "who's logged in RIGHT NOW" / "currently active" / "active session"
        // questions want the live SessionRegistry (last 5 min of real activity), NOT the
        // 24-hr login-history table. Without this, the chat would answer "9 users with
        // logins in the last 24 hours" — useless when admin asks "who's actually on the
        // toolkit right now before I deploy?". Trigger phrases stay narrow so generic
        // "who logged in" questions still go to the historical-log path below.
        boolean asksRightNow = (q.contains("right now") || q.contains("currently")
                || q.contains("active session") || q.contains("active right now")
                || q.contains("in right now") || q.contains("on right now")
                || q.contains("active users") || q.contains("active user")
                || q.contains("still in the site") || q.contains("still in the toolkit")
                || q.contains("who is on") || q.contains("who's on")
                || q.contains("who is in") || q.contains("who's in"));
        boolean asksLoggedIn = q.contains("logged in") || q.contains("logged on") || q.contains("signed in");
        if (asksRightNow && asksLoggedIn) {
            return renderActiveSessionsAnswer();
        }

        // Is this an activity-shaped question at all?
        boolean hasActivityKeyword = false;
        for (String kw : ACTIVITY_KEYWORDS) {
            if (q.contains(kw)) { hasActivityKeyword = true; break; }
        }
        // "who" / "which user" — direct phrasing.
        // "anyone" / "any user" — semantically a who-question ("has anyone logged in?").
        boolean asksWho = q.contains("who ") || q.startsWith("who")
                || q.contains("which user")
                || q.contains("anyone ") || q.contains(" anyone")
                || q.contains("any user") || q.contains("any users");
        boolean asksLast = looksLikeLastTimeQuestion(q);
        if (!hasActivityKeyword && !asksWho && !asksLast) return null;

        // Parse time window from phrases like "last 4 hours", "last 30 minutes", "today".
        long windowMs = parseTimeWindowMs(q);
        String windowLabel = describeTimeWindow(q, windowMs);
        // For "last time / when was the last X" questions with no explicit time
        // qualifier, the default 24h is wrong — those questions want the most-recent
        // event regardless of when. Widen to 90 days so a report run from a few days
        // ago still surfaces. Explicit qualifiers (today, this week, last 4 hours,
        // this month) override this.
        if (asksLast && !hasExplicitTimeWindow(q)) {
            windowMs = 90L * 86400_000L;
            windowLabel = "the last 90 days";
        }

        long cutoff = System.currentTimeMillis() - windowMs;
        List<ActivityLogger.ActivityEntry> recent = activityLogger.getActivitiesSince(cutoff);
        if (recent.isEmpty()) {
            return "<p>No activity recorded in " + esc(windowLabel) + ".</p>";
        }

        // Parse action filter if one is implied by the question ("who logged in" → LOGIN).
        ActionFilter filter = parseActionFilter(q);

        // Build name index for per-user match.
        Map<String, String> nameIndex = new LinkedHashMap<>();
        for (ActivityLogger.ActivityEntry e : recent) {
            String dn = e.displayName == null ? "" : e.displayName.trim();
            String un = e.username == null ? "" : e.username.trim();
            // Skip system/scheduled-job pseudonyms — these write displayName like
            // "ECN Report" or "Returns Tracker" which would otherwise collide with
            // report-name filters (parseActionFilter) and shadow real users.
            if ("system".equalsIgnoreCase(un)) continue;
            String canonical = !dn.isEmpty() ? dn : un;
            if (canonical.isEmpty()) continue;
            if (!dn.isEmpty()) {
                nameIndex.put(dn.toLowerCase(), canonical);
                String[] parts = dn.split("\\s+");
                if (parts.length > 0 && parts[0].length() >= 3) nameIndex.put(parts[0].toLowerCase(), canonical);
                if (parts.length > 1 && parts[parts.length - 1].length() >= 3) nameIndex.put(parts[parts.length - 1].toLowerCase(), canonical);
            }
            if (!un.isEmpty() && un.length() >= 3) nameIndex.put(un.toLowerCase(), canonical);
        }
        String matchedUser = null;
        for (Map.Entry<String, String> me : nameIndex.entrySet()) {
            if (containsWord(q, me.getKey())) {
                matchedUser = me.getValue();
                break;
            }
        }

        // Case 1: specific user named — per-user report
        if (matchedUser != null) {
            List<ActivityLogger.ActivityEntry> userActivity = new ArrayList<>();
            for (ActivityLogger.ActivityEntry e : recent) {
                if ((matchedUser.equalsIgnoreCase(e.displayName) || matchedUser.equalsIgnoreCase(e.username))
                        && (filter == null || filter.matches(e))) {
                    userActivity.add(e);
                }
            }
            return formatActivityReport(matchedUser, userActivity, windowLabel, filter);
        }

        // Case 2: "who …" question — aggregate report across all users
        if (asksWho) {
            List<ActivityLogger.ActivityEntry> filtered = new ArrayList<>();
            for (ActivityLogger.ActivityEntry e : recent) {
                if (filter == null || filter.matches(e)) filtered.add(e);
            }
            return formatWhoReport(filtered, recent, windowLabel, filter, q);
        }

        // Case 3: "heaviest / busiest / most active user" — same as a "who" question
        // but tagged so we can render a more focused header.
        if (q.contains("heaviest") || q.contains("busiest") || q.contains("most active")
                || q.contains("least active") || q.contains("quietest")) {
            return formatWhoReport(recent, recent, windowLabel, filter, q);
        }

        // Case 4: "last time / when was the last X" — no specific user, no "who" verb,
        // but a clear filter is implied. formatWhoReport prepends the singular headline
        // and renders the supporting ranking table beneath it.
        if (asksLast && filter != null) {
            List<ActivityLogger.ActivityEntry> filtered = new ArrayList<>();
            for (ActivityLogger.ActivityEntry e : recent) {
                if (filter.matches(e)) filtered.add(e);
            }
            return formatWhoReport(filtered, recent, windowLabel, filter, q);
        }

        return null; // no user named, not a "who" question — let the LLM handle it
    }

    // ---------- time-window parsing ----------

    /** Map "one", "two", ..., "twelve" → "1".."12" when they appear before a time
     *  unit (hour / minute / day / week / month / year, with or without trailing s).
     *  Lets the digit-based parsers downstream handle "the last one hour" the same
     *  way they handle "the last 1 hour".
     *
     *  <p>Scope is deliberately narrow: only word-numbers immediately before a time
     *  noun get rewritten. "Anyone uploaded a file" stays untouched even though
     *  "one" appears in "anyone".
     */
    static String normalizeWordNumbers(String q) {
        if (q == null || q.isEmpty()) return q;
        String[] words = {"one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve"};
        String out = q;
        for (int i = 0; i < words.length; i++) {
            // Match: word-number + space + time-unit-with-optional-s. Preserve the unit verbatim.
            out = out.replaceAll(
                "\\b" + words[i] + "\\s+((?:hour|hr|minute|min|day|week|month|year|second)s?)\\b",
                (i + 1) + " $1");
        }
        return out;
    }

    /** Parses phrases like "last 4 hours" / "last 30 minutes" / "today".
     *  Defaults to 24h if no explicit window is found. */
    private long parseTimeWindowMs(String q) {
        // "last N hours" / "last N hour" / "past N hours"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:last|past)\\s+(\\d+)\\s*(hour|hr)s?")
                .matcher(q);
        if (m.find()) return Long.parseLong(m.group(1)) * 3600_000L;

        m = java.util.regex.Pattern
                .compile("(?:last|past)\\s+(\\d+)\\s*(minute|min)s?")
                .matcher(q);
        if (m.find()) return Long.parseLong(m.group(1)) * 60_000L;

        m = java.util.regex.Pattern
                .compile("(?:last|past)\\s+(\\d+)\\s*days?")
                .matcher(q);
        if (m.find()) return Long.parseLong(m.group(1)) * 86400_000L;

        if (q.contains("today") || q.contains("this morning") || q.contains("this afternoon")) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            return System.currentTimeMillis() - c.getTimeInMillis();
        }
        if (q.contains("yesterday")) {
            // Window from start-of-yesterday to now (covers both yesterday + today partial)
            // Strict yesterday-only is harder to express in a single windowMs; the report
            // still surfaces yesterday's activity prominently and "today" data is small noise.
            return 48L * 3600_000L;
        }
        if (q.contains("this week") || q.contains("last week") || q.contains("past week") || q.contains("week")) {
            return 7L * 86400_000L;
        }
        if (q.contains("this month") || q.contains("last month") || q.contains("past month") || q.contains("month")) {
            return 30L * 86400_000L;
        }
        if (q.contains("this hour") || q.contains("in the last hour") || q.contains("past hour")) {
            return 3600_000L;
        }
        return 86400_000L; // default 24h
    }

    private String describeTimeWindow(String q, long windowMs) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:last|past)\\s+(\\d+)\\s*(hour|hr|minute|min|day)s?").matcher(q);
        if (m.find()) {
            String unit = m.group(2);
            if (unit.startsWith("hr") || unit.startsWith("hour")) unit = "hour";
            else if (unit.startsWith("min")) unit = "minute";
            else unit = "day";
            int n = Integer.parseInt(m.group(1));
            return "the last " + n + " " + unit + (n == 1 ? "" : "s");
        }
        if (q.contains("today") || q.contains("this morning") || q.contains("this afternoon")) return "today (since midnight)";
        if (q.contains("yesterday")) return "the last 48 hours (covers yesterday + today)";
        if (q.contains("this week") || q.contains("last week") || q.contains("past week") || q.contains("week")) return "the last 7 days";
        if (q.contains("this month") || q.contains("last month") || q.contains("past month") || q.contains("month")) return "the last 30 days";
        if (windowMs == 3600_000L) return "the last hour";
        return "the last 24 hours";
    }

    // ---------- action filter parsing ----------

    private static class ActionFilter {
        final String label;                 // human-readable: "logins", "uploads", ...
        final String[] substrings;          // match if action contains any of these (uppercase)
        final String[] detailSubstrings;    // optional: when set, the entry's details must also contain one (lowercase)
        ActionFilter(String label, String... substrings) {
            this(label, substrings, null);
        }
        ActionFilter(String label, String[] substrings, String[] detailSubstrings) {
            this.label = label;
            this.substrings = substrings;
            this.detailSubstrings = detailSubstrings;
        }
        boolean matches(ActivityLogger.ActivityEntry e) {
            if (e == null || e.action == null) return false;
            String upper = e.action.toUpperCase();
            boolean actionOk = false;
            for (String s : substrings) if (upper.contains(s)) { actionOk = true; break; }
            if (!actionOk) return false;
            if (detailSubstrings == null || detailSubstrings.length == 0) return true;
            if (e.details == null) return false;
            String d = e.details.toLowerCase();
            for (String s : detailSubstrings) if (d.contains(s)) return true;
            return false;
        }
    }

    private ActionFilter parseActionFilter(String q) {
        // Tab-named filters (most specific first to avoid e.g. "ai" matching before "ai eval").
        if (q.contains("ai eval"))
            return new ActionFilter("AI Eval tab", "AI_EVAL");
        if (q.contains("ai data") || q.contains("data query") || q.contains("ai help") || q.contains("ai chat"))
            return new ActionFilter("AI Help / data queries", "AI_DATA", "AI_EXT_LOOKUP");
        // Most-specific report names first.
        if (q.contains("returns tracker") || q.contains("rejection tracker"))
            return new ActionFilter("Returns Tracker", "RETURNS_");
        if (q.contains("ecn report") || q.contains("cycle time") || q.contains("ecn cycle"))
            return new ActionFilter("ECN Report", "ECN_REPORT_");
        // ReportController multiplexes several reports onto action=REPORT_RUN; disambiguate via details substring.
        if (q.contains("what's new") || q.contains("whats new"))
            return new ActionFilter("What's New digest",
                    new String[]{"REPORT_RUN"}, new String[]{"what's new", "whats new"});
        if (q.contains("activity stats"))
            return new ActionFilter("Activity Stats email",
                    new String[]{"REPORT_RUN"}, new String[]{"activity stats"});
        if (q.contains("dry run"))
            return new ActionFilter("Dry Run",
                    new String[]{"REPORT_RUN"}, new String[]{"dry run"});
        if (q.contains("item cache"))
            return new ActionFilter("Item Cache (seed/delta)",
                    new String[]{"REPORT_RUN"}, new String[]{"item cache"});
        if (q.contains("server log"))
            return new ActionFilter("Server Log download",
                    new String[]{"REPORT_RUN"}, new String[]{"server log"});
        // Legacy fallback: bare "returns " (with trailing space) maps to Returns Tracker.
        if (q.contains("returns "))
            return new ActionFilter("Returns Tracker", "RETURNS_");
        if (q.contains("change review") || q.contains("reviews tab"))
            return new ActionFilter("Change Reviews tab", "CHANGE_REVIEW");
        if (q.contains("change history") || q.contains("history tab"))
            return new ActionFilter("Change History tab", "HISTORY_SEARCH");
        if (q.contains("bom compare"))
            return new ActionFilter("BOM \u2192 Compare", "BOM_COMPARE", "REV_COMPARE");
        if (q.contains("bom explorer") || q.contains("bom explode") || q.contains("bom implode") || q.contains("bom export"))
            return new ActionFilter("BOM \u2192 Explorer", "BOM_EXPLODE", "BOM_IMPLODE", "BOM_EXPORT");
        if (q.contains("bom"))
            return new ActionFilter("BOM tab (Explorer + Compare)", "BOM_");
        if (q.contains("agile lookup") || q.contains("agile tab"))
            return new ActionFilter("Items \u2192 Agile Lookup", "AGILE");
        if (q.contains("part extract") || q.contains("parts tab") || q.contains("parts search"))
            return new ActionFilter("Items \u2192 Part Extract", "PARTS_SEARCH");
        if (q.contains("sku"))
            return new ActionFilter("Items \u2192 SKU Lookup", "SKU_LOOKUP");
        if (q.contains("data compare"))
            return new ActionFilter("Data Compare tab", "DATA_COMPARE", "COMPARE");
        if (q.contains("permissions") || q.contains("user permissions") || q.contains("user management"))
            return new ActionFilter("User Management tab", "PERMISSIONS");
        if (q.contains("ad health"))
            return new ActionFilter("AD Health tab", "AD_HEALTH");
        if (q.contains("extension"))
            return new ActionFilter("Extensions tab", "EXTENSION");
        if (q.contains("debug") || q.contains("debug analyze") || q.contains("debug analyzer"))
            return new ActionFilter("Debug Assistant", "DEBUG_ANALYZE");
        if (q.contains("field change") || q.contains("field search"))
            return new ActionFilter("Field Changes tab", "FIELD_SEARCH");
        // Compute-intensive: actions that hit the LLM, run a Python report, or do
        // expensive DB work. Used by questions like "most compute activity today".
        // "ai use" was too loose — matched "AI used" in passive-voice meta
        // questions like "how is AI used?" and routed them to the user-activity
        // widget. Require "ai usage" / "ai activity" / "ai calls" instead so
        // only volume-shaped phrasings hit this branch.
        if (q.contains("compute") || q.contains("intensive") || q.contains("expensive")
                || q.contains("ai usage") || q.contains("ai activity") || q.contains("ai calls"))
            return new ActionFilter("compute-intensive actions",
                "AI_DATA", "AI_EXT_LOOKUP", "AI_EVAL", "DEBUG_ANALYZE",
                "BOM_EXPLODE", "BOM_IMPLODE", "REPORT_RUN", "HISTORY_SEARCH");

        // Generic verb-based filters (kept after the tab-specific ones).
        if (q.contains("logged in") || q.contains("log in") || q.contains("login")
                || q.contains("signed in") || q.contains("sign in") || q.contains("signin"))
            return new ActionFilter("logins", "LOGIN");
        if (q.contains("upload") || q.contains("added a source") || q.contains("added source"))
            return new ActionFilter("uploads", "UPLOAD");
        if (q.contains("search") || q.contains("lookup") || q.contains("looked up"))
            return new ActionFilter("searches / lookups", "SEARCH", "LOOKUP");
        if (q.contains("export") || q.contains("download"))
            return new ActionFilter("exports / downloads", "EXPORT", "DOWNLOAD");
        if (q.contains("report"))
            return new ActionFilter("reports", "REPORT");
        if (q.contains("feedback"))
            return new ActionFilter("feedback", "FEEDBACK");
        if (q.contains("compare"))
            return new ActionFilter("compare actions", "COMPARE");
        if (q.contains("raise") || q.contains("issue"))
            return new ActionFilter("issues raised", "RAISE_ISSUE");
        return null; // no filter → count everything
    }

    /** Word-boundary contains check to avoid spurious substring matches (e.g. "ed" in "edit"). */
    private boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (from < haystack.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            char before = idx == 0 ? ' ' : haystack.charAt(idx - 1);
            int afterIdx = idx + needle.length();
            char after = afterIdx >= haystack.length() ? ' ' : haystack.charAt(afterIdx);
            boolean leftOK = !Character.isLetterOrDigit(before);
            boolean rightOK = !Character.isLetterOrDigit(after);
            if (leftOK && rightOK) return true;
            from = idx + 1;
        }
        return false;
    }

    private String formatActivityReport(String displayName, List<ActivityLogger.ActivityEntry> entries,
                                         String windowLabel, ActionFilter filter) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-size:13px;'>");
        String filterNote = filter != null ? " (" + esc(filter.label) + ")" : "";
        if (entries.isEmpty()) {
            sb.append("<p>No activity recorded for <strong>").append(esc(displayName))
              .append("</strong>").append(filterNote).append(" in ").append(esc(windowLabel)).append(".</p>");
            sb.append("</div>");
            return sb.toString();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a");

        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        for (ActivityLogger.ActivityEntry e : entries) {
            actionCounts.merge(e.action, 1, Integer::sum);
        }

        sb.append("<p><strong>").append(esc(displayName)).append("</strong>").append(filterNote)
          .append(" &mdash; ").append(entries.size()).append(" action(s) in ").append(esc(windowLabel)).append(".</p>");

        sb.append("<p style='margin:8px 0 4px;'><strong>Action breakdown:</strong></p>");
        sb.append("<ul style='margin:0 0 10px; padding-left:20px;'>");
        for (Map.Entry<String, Integer> me : actionCounts.entrySet()) {
            sb.append("<li>").append(esc(me.getKey())).append(" &mdash; ").append(me.getValue()).append("</li>");
        }
        sb.append("</ul>");

        sb.append("<p style='margin:10px 0 4px;'><strong>Recent (newest first):</strong></p>");
        sb.append("<table style='border-collapse:collapse; font-size:12px; width:100%; border:1px solid #ddd;'>");
        sb.append("<tr style='background:#2c3e50; color:white;'>")
          .append("<th style='padding:4px 6px; text-align:left;'>Time</th>")
          .append("<th style='padding:4px 6px; text-align:left;'>Action</th>")
          .append("<th style='padding:4px 6px; text-align:left;'>Details</th></tr>");
        // Show most recent first
        for (int i = entries.size() - 1; i >= 0; i--) {
            ActivityLogger.ActivityEntry e = entries.get(i);
            sb.append("<tr style='border-top:1px solid #eee;'>");
            sb.append("<td style='padding:4px 6px; white-space:nowrap; color:#666;'>")
              .append(esc(sdf.format(new Date(e.timestamp)))).append("</td>");
            sb.append("<td style='padding:4px 6px; font-weight:600; color:#1a3a5c;'>").append(esc(e.action)).append("</td>");
            sb.append("<td style='padding:4px 6px;'>").append(esc(e.details == null ? "" : e.details)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        sb.append("</div>");
        return sb.toString();
    }

    /** Aggregate "who …" report — lists all users with counts for the given window
     *  and optional action filter, sorted most-active first.
     *
     *  @param entries       events matching the filter (used for ranking)
     *  @param allEntries    full unfiltered window (used for the "and what they did" drilldown)
     *  @param windowLabel   "today (since midnight)", "the last 7 days", etc.
     *  @param filter        optional action filter (logins, BOM, etc.) — null = all actions
     *  @param question      lowercased original question — drives drilldown + heading copy
     */
    private String formatWhoReport(List<ActivityLogger.ActivityEntry> entries,
                                    List<ActivityLogger.ActivityEntry> allEntries,
                                    String windowLabel, ActionFilter filter, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-size:13px;'>");
        String filterLabel = filter != null ? filter.label : "actions";

        // If user asked for the heaviest user with no other filter, ranking is by ALL
        // events not just LOGIN. Use allEntries when the question is about "heaviest/
        // busiest/most active" and no explicit action filter was applied.
        boolean heaviest = question != null
                && (question.contains("heaviest") || question.contains("busiest")
                    || question.contains("most active"));
        boolean quietest = question != null
                && (question.contains("least active") || question.contains("quietest"));
        boolean wantDrilldown = question != null
                && (question.contains("what they did") || question.contains("what did they do")
                    || question.contains("and what they") || question.contains("and what did")
                    || question.contains("most compute") || question.contains("compute activity"));
        List<ActivityLogger.ActivityEntry> rankSource =
                ((heaviest || quietest) && filter == null) ? allEntries : entries;

        if (rankSource.isEmpty()) {
            sb.append("<p>No <em>").append(esc(filterLabel)).append("</em> recorded in ")
              .append(esc(windowLabel)).append(".</p>");
            sb.append("</div>");
            return sb.toString();
        }

        // "Last time / most recent" headline. Prepended above the ranking table when
        // the question shape is asking for the singular most-recent occurrence.
        // Uses `entries` (the filtered list) so the headline reflects the actual
        // filtered intent, even when rankSource is allEntries (heaviest case).
        if (looksLikeLastTimeQuestion(question) && !entries.isEmpty()) {
            ActivityLogger.ActivityEntry latest = null;
            for (ActivityLogger.ActivityEntry e : entries) {
                if (latest == null || e.timestamp > latest.timestamp) latest = e;
            }
            if (latest != null) {
                String who = (latest.displayName != null && !latest.displayName.isEmpty())
                        ? latest.displayName : latest.username;
                if (who == null || who.isEmpty()) who = "(unknown)";
                SimpleDateFormat headlineSdf = new SimpleDateFormat("MMM d, h:mm a");
                sb.append("<p style='font-size:14px; margin:0 0 6px;'>")
                  .append("<strong>").append(esc(who)).append("</strong> last generated <em>")
                  .append(esc(filterLabel)).append("</em> on <strong>")
                  .append(esc(headlineSdf.format(new Date(latest.timestamp))))
                  .append("</strong>.</p>");
                if (latest.details != null && !latest.details.isEmpty()) {
                    String d = latest.details.length() > 120
                            ? latest.details.substring(0, 120) + "\u2026" : latest.details;
                    sb.append("<p style='font-size:12px; color:#666; margin:0 0 10px;'>Action: <code>")
                      .append(esc(latest.action == null ? "" : latest.action))
                      .append("</code> &mdash; \"").append(esc(d)).append("\"</p>");
                }
            }
        }

        // Group by canonical displayName (falling back to username) with counts,
        // most-recent timestamp, and an action-frequency map.
        java.util.Map<String, int[]> userCounts = new java.util.LinkedHashMap<>(); // count
        java.util.Map<String, Long> latestPerUser = new java.util.HashMap<>();
        java.util.Map<String, java.util.Map<String, Integer>> actionsPerUser = new java.util.HashMap<>();
        for (ActivityLogger.ActivityEntry e : rankSource) {
            String who = (e.displayName != null && !e.displayName.isEmpty()) ? e.displayName : e.username;
            if (who == null || who.isEmpty()) who = "(unknown)";
            userCounts.computeIfAbsent(who, k -> new int[1])[0]++;
            Long prev = latestPerUser.get(who);
            if (prev == null || e.timestamp > prev) latestPerUser.put(who, e.timestamp);
            actionsPerUser.computeIfAbsent(who, k -> new java.util.LinkedHashMap<>())
                          .merge(e.action == null ? "(unknown)" : e.action, 1, Integer::sum);
        }

        // Sort: ascending for "quietest", descending for everything else.
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(userCounts.entrySet());
        sorted.sort((a, b) -> {
            int c = quietest
                    ? Integer.compare(a.getValue()[0], b.getValue()[0])
                    : Integer.compare(b.getValue()[0], a.getValue()[0]);
            if (c != 0) return c;
            return Long.compare(latestPerUser.getOrDefault(b.getKey(), 0L),
                                latestPerUser.getOrDefault(a.getKey(), 0L));
        });

        // Heading
        if (heaviest && !sorted.isEmpty()) {
            Map.Entry<String, int[]> top = sorted.get(0);
            sb.append("<p><strong>").append(esc(top.getKey())).append("</strong> was the most active")
              .append(filter != null ? " (" + esc(filterLabel) + ")" : "")
              .append(" in ").append(esc(windowLabel)).append(" with <strong>")
              .append(top.getValue()[0]).append("</strong> action").append(top.getValue()[0] == 1 ? "" : "s")
              .append(". Full ranking:</p>");
        } else if (quietest && !sorted.isEmpty()) {
            Map.Entry<String, int[]> bottom = sorted.get(0);
            sb.append("<p><strong>").append(esc(bottom.getKey())).append("</strong> was the quietest in ")
              .append(esc(windowLabel)).append(" with only <strong>")
              .append(bottom.getValue()[0]).append("</strong> action").append(bottom.getValue()[0] == 1 ? "" : "s")
              .append(". Full ranking (quietest first):</p>");
        } else {
            sb.append("<p>").append(userCounts.size()).append(" user")
              .append(userCounts.size() == 1 ? "" : "s")
              .append(" with <em>").append(esc(filterLabel)).append("</em> in ")
              .append(esc(windowLabel)).append(" (").append(rankSource.size()).append(" total):</p>");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a");
        sb.append("<table style='border-collapse:collapse; font-size:12px; width:100%; border:1px solid #ddd;'>");
        sb.append("<tr style='background:#2c3e50; color:white;'>")
          .append("<th style='padding:4px 6px; text-align:left;'>User</th>")
          .append("<th style='padding:4px 6px; text-align:right;'>Count</th>")
          .append("<th style='padding:4px 6px; text-align:left;'>Most recent</th>");
        if (wantDrilldown) sb.append("<th style='padding:4px 6px; text-align:left;'>Top actions</th>");
        sb.append("</tr>");
        for (Map.Entry<String, int[]> me : sorted) {
            String who = me.getKey();
            int count = me.getValue()[0];
            Long latest = latestPerUser.get(who);
            sb.append("<tr style='border-top:1px solid #eee;'>");
            sb.append("<td style='padding:4px 6px; font-weight:600; color:#1a3a5c;'>").append(esc(who)).append("</td>");
            sb.append("<td style='padding:4px 6px; text-align:right;'>").append(count).append("</td>");
            sb.append("<td style='padding:4px 6px; color:#666;'>")
              .append(latest != null ? esc(sdf.format(new Date(latest))) : "")
              .append("</td>");
            if (wantDrilldown) {
                Map<String, Integer> actions = actionsPerUser.getOrDefault(who, java.util.Collections.emptyMap());
                List<Map.Entry<String, Integer>> topActions = new ArrayList<>(actions.entrySet());
                topActions.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                StringBuilder cell = new StringBuilder();
                int shown = 0;
                for (Map.Entry<String, Integer> ae : topActions) {
                    if (shown >= 4) break;
                    if (cell.length() > 0) cell.append(", ");
                    cell.append(esc(ae.getKey())).append(" (").append(ae.getValue()).append(")");
                    shown++;
                }
                if (topActions.size() > 4) cell.append(", \u2026 +").append(topActions.size() - 4).append(" more");
                sb.append("<td style='padding:4px 6px; font-size:11px; color:#333;'>").append(cell).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        sb.append("</div>");
        return sb.toString();
    }

    /** True when the question carries an explicit time qualifier (today, last 4 hours,
     *  this week, this month, etc.). Used to decide whether "last time" questions
     *  should widen their default search window. */
    private boolean hasExplicitTimeWindow(String q) {
        if (q == null) return false;
        if (q.matches(".*\\b(?:last|past)\\s+\\d+\\s*(hour|hr|minute|min|day|days?).*")) return true;
        if (q.contains("today") || q.contains("this morning") || q.contains("this afternoon")) return true;
        if (q.contains("yesterday")) return true;
        if (q.contains("this week") || q.contains("last week") || q.contains("past week")
                || q.contains(" week ") || q.endsWith(" week")) return true;
        if (q.contains("this month") || q.contains("last month") || q.contains("past month")
                || q.contains(" month ") || q.endsWith(" month")) return true;
        if (q.contains("this hour") || q.contains("in the last hour") || q.contains("past hour")
                || q.contains("the last hour")) return true;
        return false;
    }

    /** True when the question is asking for the singular most-recent occurrence
     *  of an activity (e.g. "who generated X last time", "when was the last Y",
     *  "most recent run", "who triggered the last Z"). Used to gate the headline
     *  above the ranking table. */
    private boolean looksLikeLastTimeQuestion(String q) {
        if (q == null) return false;
        if (q.contains("last time") || q.contains("most recent") || q.contains("most-recent")
                || q.contains("most recently") || q.contains("latest")
                || q.contains("when was the last") || q.contains("when was last")) {
            return true;
        }
        // "when … last …" / "when … recent …" — separate keywords in the same question.
        if (q.contains("when") && (q.contains("last ") || q.contains(" last") || q.contains("recent"))) {
            return true;
        }
        // "the last <thing>" — but only when "<thing>" is a noun (event/report),
        // not a time unit. "the last hour" / "the last 30 minutes" stay windows.
        int idx = q.indexOf("the last ");
        if (idx >= 0) {
            String rest = q.substring(idx + 9).trim();
            String firstWord = rest.isEmpty() ? "" : rest.split("\\s+", 2)[0];
            String fw = firstWord.replaceAll("[^a-z0-9]", "");
            String[] timeUnits = {"hour", "hours", "hr", "hrs", "minute", "minutes", "min",
                                  "mins", "day", "days", "week", "weeks", "month", "months",
                                  "year", "years", "second", "seconds", "fortnight", "quarter"};
            boolean isTimeUnit = fw.matches("\\d+");
            if (!isTimeUnit) {
                for (String u : timeUnits) if (fw.equals(u)) { isTimeUnit = true; break; }
            }
            if (!isTimeUnit) return true;
        }
        return false;
    }

    /**
     * Returns true if the question looks like it's asking about user activity,
     * loosely. Used to decide whether to inject the activity-data context block
     * into the LLM system prompt for fuzzy questions the deterministic router
     * didn't handle.
     */
    private boolean isActivityShapedForLlm(String q) {
        if (q == null || q.isEmpty()) return false;
        for (String kw : ACTIVITY_KEYWORDS) if (q.contains(kw)) return true;
        if (q.contains("who ") || q.startsWith("who")) return true;
        // "has anyone X" / "any users X" — semantically a who-question.
        if (q.contains("anyone ") || q.contains(" anyone")
                || q.contains("any user") || q.contains("any users")) return true;
        // Tab-named questions ("anyone using BOM Compare?") that aren't caught
        // by the keyword set above.
        String[] tabHints = {
            "bom explorer", "bom compare", "ai eval", "ai help", "field changes",
            "change history", "change review", "data compare", "sku lookup",
            "agile lookup", "part extract", "ecn report", "returns tracker",
            "user permissions", "ad health", "feedback queue"
        };
        for (String h : tabHints) if (q.contains(h)) return true;
        return false;
    }

    /**
     * Build a compact, structured summary of recent activity that we attach to
     * the LLM system prompt for activity-shaped questions. Sized to stay well
     * under model token budget — top users + top actions + top tab usage +
     * login summary + the most-recent 60 events. The model uses this as facts
     * to answer fuzzy questions ("anyone using AI Eval more than usual?").
     */
    private String buildActivityContextForLlm(String q) {
        // Same word-number normalization as the deterministic router (see
        // tryAdminActivityQuery) so "the last one hour" parses to 1h, not 24h.
        q = normalizeWordNumbers(q);
        long windowMs = parseTimeWindowMs(q);
        long cutoff = System.currentTimeMillis() - windowMs;
        List<ActivityLogger.ActivityEntry> recent = activityLogger.getActivitiesSince(cutoff);
        if (recent.isEmpty()) return "(no activity recorded in window)";

        String windowLabel = describeTimeWindow(q, windowMs);
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d HH:mm");

        // Aggregations
        java.util.Map<String, Integer> perUser = new java.util.HashMap<>();
        java.util.Map<String, Integer> perAction = new java.util.HashMap<>();
        java.util.Map<String, java.util.Set<String>> usersPerAction = new java.util.HashMap<>();
        java.util.Map<String, Integer> loginsPerUser = new java.util.HashMap<>();
        for (ActivityLogger.ActivityEntry e : recent) {
            String who = (e.displayName != null && !e.displayName.isEmpty()) ? e.displayName : e.username;
            if (who == null || who.isEmpty()) who = "(unknown)";
            perUser.merge(who, 1, Integer::sum);
            String act = e.action == null ? "(unknown)" : e.action;
            perAction.merge(act, 1, Integer::sum);
            usersPerAction.computeIfAbsent(act, k -> new java.util.HashSet<>()).add(who);
            if ("LOGIN".equals(act)) loginsPerUser.merge(who, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Window: ").append(windowLabel)
          .append(" \u00b7 ").append(recent.size()).append(" events \u00b7 ")
          .append(perUser.size()).append(" unique users\n\n");

        // Top users by total events
        sb.append("TOP USERS (by total actions):\n");
        appendTopMap(sb, perUser, 10);

        // Top actions
        sb.append("\nTOP ACTIONS:\n");
        appendTopMap(sb, perAction, 12);

        // Logins per user
        if (!loginsPerUser.isEmpty()) {
            sb.append("\nLOGINS PER USER:\n");
            appendTopMap(sb, loginsPerUser, 15);
        }

        // Tab taxonomy so the LLM knows action prefixes ↔ tab names.
        // Top-level nav (after May 6 2026 consolidation):
        //   Items (parent) → Field Changes / Part Extract / Agile Lookup / SKU Lookup
        //   BOM   (parent) → Explorer / Compare
        //   Change History · Data Compare · Change Reviews · ECN Report ·
        //   More\u2026 (parent) → Work Instructions / User Management / Extensions / Data Compare / Change Reviews ·
        //   AI Eval · admin tabs (Utilities, AD Health). PT-26.
        sb.append("\nNAV LAYOUT:\n");
        sb.append("  Top-level tabs: Items · BOM · Change History · ECN Dashboard · More\u2026 · AI Eval · Utilities (admin) · AD Health (admin)\n");
        sb.append("  More\u2026 parent  -> sub-tabs: Work Instructions / User Management / Extensions / Data Compare / Change Reviews\n");
        sb.append("  Items parent → sub-tabs: Field Changes / Part Extract / Agile Lookup / SKU Lookup\n");
        sb.append("  BOM parent   → sub-tabs: Explorer / Compare\n");
        sb.append("  User Management was previously called \"User Permissions\".\n");

        sb.append("\nACTION → TAB MAPPING:\n");
        sb.append("  LOGIN = login event\n");
        sb.append("  FIELD_SEARCH = Items → Field Changes\n");
        sb.append("  BOM_EXPLODE / BOM_IMPLODE / BOM_EXPORT = BOM → Explorer\n");
        sb.append("  BOM_COMPARE / REV_COMPARE = BOM → Compare\n");
        sb.append("  PARTS_SEARCH = Items → Part Extract\n");
        sb.append("  AGILE_MANUAL_LOOKUP = Items → Agile Lookup\n");
        sb.append("  HISTORY_SEARCH = Change History\n");
        sb.append("  CHANGE_REVIEW_* = Change Reviews\n");
        sb.append("  SKU_LOOKUP = Items → SKU Lookup\n");
        sb.append("  DATA_COMPARE_UPLOAD / COMPARE = Data Compare\n");
        sb.append("  AI_DATA_QUERY / AI_DATA_EXPORT / AI_EXT_LOOKUP = AI Help / data queries\n");
        sb.append("  AI_EVAL_* = AI Eval\n");
        sb.append("  REPORT_RUN / REPORT_COMPLETED / REPORT_FAILED = Utilities (Reports)\n");
        sb.append("  RETURNS_* = ECN Report / Returns Tracker\n");
        sb.append("  PERMISSIONS_* = User Management (formerly User Permissions)\n");
        sb.append("  AD_HEALTH_* = AD Health\n");
        sb.append("  EXTENSION_* = Extensions\n");
        sb.append("  DEBUG_ANALYZE = Debug Assistant\n");
        sb.append("  FEEDBACK / FEEDBACK_RESOLVE / FEEDBACK_DISMISS / FEEDBACK_START / FEEDBACK_REOPEN = Feedback Queue\n");
        sb.append("  RAISE_ISSUE = Raise Issue button\n");
        sb.append("  PAGE_VIEW = generic tab navigation\n");
        sb.append("  Compute-intensive = AI_*, BOM_EXPLODE, BOM_IMPLODE, REPORT_RUN, DEBUG_ANALYZE, HISTORY_SEARCH\n");

        // Recent timeline (newest first, capped)
        sb.append("\nTIMELINE (newest first, last 60 events):\n");
        int max = Math.min(60, recent.size());
        for (int i = recent.size() - 1, n = 0; i >= 0 && n < max; i--, n++) {
            ActivityLogger.ActivityEntry e = recent.get(i);
            String who = (e.displayName != null && !e.displayName.isEmpty()) ? e.displayName : e.username;
            String details = e.details == null ? "" : e.details;
            if (details.length() > 80) details = details.substring(0, 80) + "...";
            sb.append("  ").append(sdf.format(new Date(e.timestamp)))
              .append(" | ").append(who == null ? "(unknown)" : who)
              .append(" | ").append(e.action == null ? "" : e.action);
            if (!details.isEmpty()) sb.append(" | ").append(details);
            sb.append("\n");
        }

        return sb.toString();
    }

    private static void appendTopMap(StringBuilder sb, java.util.Map<String, Integer> m, int limit) {
        List<java.util.Map.Entry<String, Integer>> entries = new ArrayList<>(m.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int shown = Math.min(limit, entries.size());
        for (int i = 0; i < shown; i++) {
            sb.append("  ").append(entries.get(i).getKey()).append(" \u2014 ")
              .append(entries.get(i).getValue()).append("\n");
        }
    }

    /** Strip HTML tags for the chatLog summary digest (which is plain text). */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
