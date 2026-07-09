package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.*;
import javax.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

@Service
public class DeltaReportService {

    private static final Logger logger = Logger.getLogger(DeltaReportService.class.getName());

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private EmailTemplateService emailTemplate;

    @Autowired
    private PortkeyClient portkeyClient;

    @Autowired
    private MaintenanceService maintenanceService;

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}")
    private String adminEmail;

    @Value("${mail.from}")
    private String fromAddress;

    @Value("${ai.help.api-key:}")
    private String aiApiKey;

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

    @Value("${ai.help.enabled:false}")
    private boolean aiEnabled;

    private long lastReportTime = 0;
    private static final String LAST_REPORT_FILE = "./data/last-report-time.txt";

    @PostConstruct
    public void init() {
        try {
            File f = new File(LAST_REPORT_FILE);
            if (f.exists()) {
                lastReportTime = Long.parseLong(new String(Files.readAllBytes(f.toPath())).trim());
                logger.info("[DELTA REPORT] Last report time loaded: " + new Date(lastReportTime));
            }
        } catch (Exception e) {
            logger.warning("[DELTA REPORT] Could not load last report time: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 9,21 * * *")
    public void sendDeltaReport() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[DELTA REPORT] Skipping scheduled report — app is in maintenance mode.");
            return;
        }
        sendDeltaReportTo(adminEmail);
    }

    /** Send delta report to a specific recipient (used by Send Stats button) */
    public void sendDeltaReportTo(String recipientEmail) {
        logger.info("[DELTA REPORT] Generating delta report for " + recipientEmail + "...");
        long since = lastReportTime;
        long now = System.currentTimeMillis();

        List<ActivityLogger.ActivityEntry> activities = activityLogger.getActivitiesSince(since);
        if (activities.isEmpty()) {
            logger.info("[DELTA REPORT] No new activity since last report. Skipping.");
            lastReportTime = now;
            saveLastReportTime();
            return;
        }

        try {
            String html = buildReportHtml(activities, since, now);
            sendEmail(html, since, now, recipientEmail);
            lastReportTime = now;
            saveLastReportTime();
            logger.info("[DELTA REPORT] Report sent to " + recipientEmail + " with " + activities.size() + " activities.");
        } catch (Exception e) {
            logger.warning("[DELTA REPORT] Failed to send: " + e.getMessage());
        }
    }

    private String buildReportHtml(List<ActivityLogger.ActivityEntry> activities, long since, long now) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy h:mm a");
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a");
        String periodShort = new SimpleDateFormat("MMM d").format(new Date(since)) + " \u2013 " + new SimpleDateFormat("MMM d").format(new Date(now));

        // Group by user
        Map<String, List<ActivityLogger.ActivityEntry>> byUser = new LinkedHashMap<>();
        for (ActivityLogger.ActivityEntry a : activities) {
            byUser.computeIfAbsent(a.displayName, k -> new ArrayList<>()).add(a);
        }

        // Count by action type
        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        for (ActivityLogger.ActivityEntry a : activities) {
            actionCounts.merge(a.action, 1, Integer::sum);
        }

        // KPI tiles
        String kpis = emailTemplate.kpiRow(
            emailTemplate.kpiTile("Active users", String.valueOf(byUser.size()), "across " + actionCounts.size() + " features"),
            emailTemplate.kpiTile("Actions", String.valueOf(activities.size()), periodShort),
            emailTemplate.kpiTile("Errors", "0", "clean window")
        );

        // Body content
        StringBuilder body = new StringBuilder();

        // AI insights callout (light paper, not heavy blue)
        String aiInsights = getAiLogAnalysis(since);
        if (aiInsights != null && !aiInsights.isEmpty()) {
            String cleanInsights = markdownToHtml(aiInsights);
            body.append(emailTemplate.callout("AI insight \u00b7 generated from logs", cleanInsights, null));
        }

        // Per-user breakdown as rows
        body.append("<table width='100%' cellpadding='0' cellspacing='0' style='margin:18px 0;font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif;'>");
        body.append("<tr><td style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:10px;letter-spacing:0.12em;text-transform:uppercase;color:#6B7280;font-weight:500;padding:0 0 8px;border-bottom:1px solid #ececec;'>By user</td></tr>");
        int userIdx = 0;
        List<Map.Entry<String, List<ActivityLogger.ActivityEntry>>> userList = new ArrayList<>(byUser.entrySet());
        // Sort by activity count descending
        userList.sort((a, b) -> b.getValue().size() - a.getValue().size());
        int showUsers = Math.min(userList.size(), 4);
        for (int u = 0; u < showUsers; u++) {
            Map.Entry<String, List<ActivityLogger.ActivityEntry>> entry = userList.get(u);
            String user = entry.getKey();
            List<ActivityLogger.ActivityEntry> userActs = entry.getValue();
            String initials = getInitials(user);
            Map<String, Integer> userActionCounts = new LinkedHashMap<>();
            for (ActivityLogger.ActivityEntry a : userActs) {
                userActionCounts.merge(friendlyAction(a.action), 1, Integer::sum);
            }
            StringBuilder tags = new StringBuilder();
            for (Map.Entry<String, Integer> ac : userActionCounts.entrySet()) {
                tags.append("<span style='display:inline-block;margin-right:8px;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:11px;color:#6B7280;'><b style='color:#0F1720;font-weight:500;'>").append(ac.getValue()).append("</b> ").append(esc(ac.getKey())).append("</span>");
            }
            body.append("<tr><td style='padding:10px 0;border-bottom:1px solid #ececec;'>");
            body.append("<table cellpadding='0' cellspacing='0' width='100%'><tr>");
            body.append("<td width='24' valign='top' style='padding-right:12px;'><div style='width:24px;height:24px;border-radius:50%;background:#0F1720;color:#FAFAF7;font-size:10px;font-weight:600;text-align:center;line-height:24px;font-family:\"IBM Plex Sans\",sans-serif;'>").append(initials).append("</div></td>");
            body.append("<td valign='top'><div style='font-size:13px;color:#0F1720;font-weight:500;'>").append(esc(user)).append("</div>");
            body.append("<div style='margin-top:2px;'>").append(tags).append("</div></td>");
            body.append("<td width='60' align='right' valign='top' style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:18px;font-weight:500;color:#0F1720;'>").append(userActs.size()).append("<div style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:9px;color:#6B7280;letter-spacing:0.1em;text-transform:uppercase;text-align:right;'>actions</div></td>");
            body.append("</tr></table></td></tr>");
        }
        if (userList.size() > showUsers) {
            int remaining = userList.size() - showUsers;
            int remainingActions = 0;
            for (int u = showUsers; u < userList.size(); u++) remainingActions += userList.get(u).getValue().size();
            body.append("<tr><td style='padding:10px 0;'>");
            body.append("<table cellpadding='0' cellspacing='0' width='100%'><tr>");
            body.append("<td width='24' valign='top' style='padding-right:12px;'><div style='width:24px;height:24px;border-radius:50%;background:#0F1720;color:#FAFAF7;font-size:9px;font-weight:600;text-align:center;line-height:24px;font-family:\"IBM Plex Sans\",sans-serif;'>+").append(remaining).append("</div></td>");
            body.append("<td valign='top'><div style='font-size:13px;color:#0F1720;font-weight:500;'>").append(remaining).append(" other users</div></td>");
            body.append("<td width='60' align='right' valign='top' style='font-family:\"IBM Plex Serif\",Georgia,serif;font-size:18px;font-weight:500;color:#0F1720;'>").append(remainingActions).append("<div style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:9px;color:#6B7280;letter-spacing:0.1em;text-transform:uppercase;text-align:right;'>actions</div></td>");
            body.append("</tr></table></td></tr>");
        }
        body.append("</table>");

        // Full activity table (all entries, most recent first)
        String[] logHeaders = {"Time", "Who", "Action", "Details"};
        String[][] logRows = new String[activities.size()][];
        for (int i = 0; i < activities.size(); i++) {
            ActivityLogger.ActivityEntry a = activities.get(activities.size() - 1 - i);
            String details = a.details != null && a.details.length() > 80 ? a.details.substring(0, 77) + "\u2026" : (a.details != null ? a.details : "");
            logRows[i] = new String[]{timeFmt.format(new Date(a.timestamp)), esc(a.displayName), friendlyAction(a.action), esc(details)};
        }
        body.append(emailTemplate.previewTable(logHeaders, logRows, null));

        // Wrap it all in the shared envelope
        return emailTemplate.wrap(
            "Admin",                                                     // section
            "Activity",                                                  // tag
            "Digest \u00b7 " + periodShort,                              // eyebrow
            byUser.size() + " users active, " + activities.size() + " actions.", // heroTitle
            "No errors. Window: " + sdf.format(new Date(since)) + " \u2013 " + sdf.format(new Date(now)), // heroSub
            kpis,                                                        // kpiHtml
            body.toString(),                                             // bodyHtml
            "Open Utilities tab",                                        // ctaText
            "http://uls-ep-aglipccb:8090/index.html#utilities",          // ctaHref
            "Re-run Activity Stats for the latest data",                 // ctaHint
            null,                                                        // attachHtml
            null,                                                        // metaStripHtml
            "admin-only digest"                                          // footerLine
        );
    }

    private String getInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.split("[\\s,]+");
        if (parts.length >= 2) return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
        return ("" + parts[0].charAt(0)).toUpperCase();
    }

    /** Convert Markdown formatting to HTML — handles **bold**, # headers, and line breaks */
    private String markdownToHtml(String md) {
        if (md == null) return "";
        // Remove # headers — convert to bold text
        md = md.replaceAll("(?m)^#{1,3}\\s*(.+)$", "<strong>$1</strong>");
        // Convert **bold** to <strong>
        md = md.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        // Convert *italic* to <em>
        md = md.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        // Convert `code` to styled span
        md = md.replaceAll("`([^`]+)`", "<code style='background:#d4e8f7; padding:1px 5px;'>$1</code>");
        // Line breaks
        md = md.replace("\n\n", "<br><br>");
        md = md.replace("\n", "<br>");
        return md;
    }

    private String friendlyAction(String action) {
        if (action == null) return "Other";
        switch (action) {
            case "LOGIN": return "Logins";
            case "FIELD_SEARCH": return "Field Change Searches";
            case "FIELD_EXPORT": return "Field Change Exports";
            case "FIELD_EMAIL": return "Field Change Emails";
            case "BOM_EXPLODE": return "BOM Explosions";
            case "BOM_IMPLODE": return "BOM Implosions";
            case "BOM_EXPORT": return "BOM Exports";
            case "BOM_EMAIL": return "BOM Emails";
            case "PARTS_SEARCH": return "Part Searches";
            case "PARTS_EXPORT": return "Part Exports";
            case "PARTS_EMAIL": return "Part Emails";
            case "PARTS_UPLOAD": return "Part File Uploads";
            case "AGILE_LOOKUP": return "Agile Lookups";
            case "HISTORY_SEARCH": return "Change History Searches";
            case "HISTORY_EXPORT": return "Change History Exports";
            case "HISTORY_EMAIL": return "Change History Emails";
            case "RAISE_ISSUE": return "Issues Raised";
            case "FEEDBACK": return "Feedback Submitted";
            default: return action;
        }
    }

    private void sendEmail(String html, long since, long now, String recipientEmail) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail));

        // Group by user for subject line
        List<ActivityLogger.ActivityEntry> acts = activityLogger.getActivitiesSince(since);
        Set<String> users = new LinkedHashSet<>();
        for (ActivityLogger.ActivityEntry a : acts) users.add(a.displayName);
        String window = new SimpleDateFormat("MMM d h:mma").format(new Date(since)) + "\u2013" + new SimpleDateFormat("h:mma").format(new Date(now));
        message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("Activity \u00b7 " + users.size() + " users \u00b7 " + acts.size() + " actions \u00b7 " + window));

        message.setContent(html, "text/html; charset=utf-8");
        Transport.send(message);
    }

    private void saveLastReportTime() {
        try {
            File f = new File(LAST_REPORT_FILE);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), String.valueOf(lastReportTime).getBytes());
        } catch (Exception e) {
            logger.warning("[DELTA REPORT] Failed to save last report time: " + e.getMessage());
        }
    }

    @Value("${app.log-file:}")
    private String configuredLogFile;

    /** Parse a Spring Boot log line's leading timestamp (yyyy-MM-dd HH:mm:ss.SSS). Returns 0 if absent/invalid. */
    private long parseLogTimestamp(String line, SimpleDateFormat fmt) {
        if (line == null || line.length() < 23) return 0;
        // Quick shape check: digits at the right positions to avoid SDF.parse on every continuation line.
        if (line.charAt(4) != '-' || line.charAt(7) != '-' || line.charAt(10) != ' '
                || line.charAt(13) != ':' || line.charAt(16) != ':' || line.charAt(19) != '.') return 0;
        try {
            return fmt.parse(line.substring(0, 23)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private String getAiLogAnalysis(long since) {
        if (!aiEnabled || aiApiKey == null || aiApiKey.isEmpty()) return null;

        try {
            // Read log file — try configured path first, then common locations
            File logFile = null;
            String[] candidates = {
                configuredLogFile,
                "./logs/plm-toolkit.log",
                "./plm-toolkit.log",
                "F:/plm-toolkit/logs/plm-toolkit.log",
                "F:\\plm-toolkit\\logs\\plm-toolkit.log",
                System.getProperty("user.home") + "/documents/plm-toolkit.log",
                System.getProperty("user.home") + "/Documents/plm-toolkit.log"
            };
            for (String path : candidates) {
                if (path != null && !path.isEmpty()) {
                    File f = new File(path);
                    if (f.exists() && f.length() > 0) { logFile = f; break; }
                }
            }
            if (logFile == null) {
                logger.info("[DELTA REPORT] Could not find log file. Tried: " + String.join(", ", candidates));
                return null;
            }
            logger.info("[DELTA REPORT] Reading log file: " + logFile.getAbsolutePath());

            // Read all lines, but only keep those at or after `since` (the last digest send time).
            // Lines without a leading timestamp (wrapped messages / stack traces) inherit the previous
            // line's timestamp so multi-line exceptions stay attached to their parent line.
            List<String> allLines = new ArrayList<>();
            int totalLinesRead = 0;
            int linesInWindow = 0;
            SimpleDateFormat logTsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            long currentTs = 0;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    totalLinesRead++;
                    long ts = parseLogTimestamp(line, logTsFmt);
                    if (ts > 0) currentTs = ts;
                    // Keep only lines whose effective timestamp is within the digest window.
                    // currentTs == 0 means we haven't seen any timestamped line yet (file preamble) — skip.
                    if (currentTs >= since && currentTs > 0) {
                        allLines.add(line);
                        linesInWindow++;
                    }
                }
            }
            logger.info("[DELTA REPORT] Read " + totalLinesRead + " lines, " + linesInWindow + " in digest window since " + new Date(since));

            if (allLines.isEmpty()) return "No log entries within the digest window.";

            // Filter window lines for interesting events (errors, warnings, crashes, key operations)
            StringBuilder logExcerpt = new StringBuilder();
            for (String line : allLines) {
                String upper = line.toUpperCase();
                if (upper.contains("ERROR") || upper.contains("EXCEPTION") ||
                    upper.contains("OUTOFMEMORY") || upper.contains("FAILED") || upper.contains("TIMEOUT") ||
                    upper.contains("CRASH") || upper.contains("HEAP SPACE") ||
                    upper.contains("[SCHEDULER]") || upper.contains("[ACCESS]") ||
                    upper.contains("STARTED APPLICATION") || upper.contains("BOM EXTRACT") ||
                    upper.contains("BOM NOTES") || upper.contains("76271") ||
                    upper.contains("ROWS:") || upper.contains("ROWS EXPORTED") ||
                    upper.contains("AUTHENTICATED SUCCESSFULLY") ||
                    upper.contains("NOT IN REQUIRED GROUP")) {
                    logExcerpt.append(line).append("\n");
                }
            }

            if (logExcerpt.length() == 0) return "No notable events in the server log.";

            // Prioritize critical lines — always include errors/crashes, then fill with activity
            String fullExcerpt = logExcerpt.toString();
            String excerpt;
            if (fullExcerpt.length() > 5000) {
                // Split into critical (errors/crashes) and normal lines
                StringBuilder critical = new StringBuilder();
                StringBuilder normal = new StringBuilder();
                for (String line : fullExcerpt.split("\n")) {
                    String upper = line.toUpperCase();
                    if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("OUTOFMEMORY") ||
                        upper.contains("HEAP SPACE") || upper.contains("CRASH") || upper.contains("FAILED")) {
                        critical.append(line).append("\n");
                    } else {
                        normal.append(line).append("\n");
                    }
                }
                // Always include all critical lines, fill remainder with recent normal lines
                String criticalStr = critical.toString();
                String normalStr = normal.toString();
                int remainingBudget = 5000 - criticalStr.length();
                if (remainingBudget > 0 && normalStr.length() > remainingBudget) {
                    normalStr = normalStr.substring(normalStr.length() - remainingBudget);
                }
                excerpt = "=== ERRORS & WARNINGS ===\n" + criticalStr + "\n=== RECENT ACTIVITY ===\n" + normalStr;
            } else {
                excerpt = fullExcerpt;
            }
            logger.info("[DELTA REPORT] Log excerpt: " + excerpt.length() + " chars (" + fullExcerpt.split("\n").length + " lines filtered)");

            // Call Haiku
            String systemPrompt = "You are a friendly IT operations analyst for the PLM Toolkit application. " +
                "Analyze the server log excerpt and write a scannable summary for the IT admin team. " +
                "Use this exact format with bullet points:\n\n" +
                "Start with a one-line status (e.g., 'Overall: Healthy with 1 issue to address')\n\n" +
                "Then use these sections with bullet points:\n" +
                "CRITICAL (only if errors/crashes found):\n- bullet point per issue with specific fix\n\n" +
                "WINS:\n- bullet point per positive item (successful tasks, active users)\n\n" +
                "WATCH (only if minor concerns):\n- bullet point per concern\n\n" +
                "Keep each bullet to one line. Use specific numbers, user names, and timestamps from the log. " +
                "Be warm but concise. Skip sections if empty. Max 8 bullets total.";

            String model = portkeyProvider + "/" + portkeyModel;
            String answer = portkeyClient.chat(model, systemPrompt, "Server log excerpt:\n" + excerpt, 400);
            return answer.replace("\n", "<br>");

        } catch (Exception e) {
            logger.warning("[DELTA REPORT] AI log analysis failed: " + e.getMessage());
            return null;
        }
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
