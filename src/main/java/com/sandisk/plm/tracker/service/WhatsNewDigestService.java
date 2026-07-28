package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends a daily "What's New" digest email to all users who have ever logged in.
 * First run includes everything; subsequent runs include only delta since last send.
 * AI writes the email body in a warm conversational tone.
 */
@Service
public class WhatsNewDigestService {

    private static final Logger logger = Logger.getLogger(WhatsNewDigestService.class.getName());
    private static final String LAST_DIGEST_FILE = "./data/last-whatsnew-digest.txt";

    @Autowired private ActivityLogger activityLogger;
    @Autowired private LdapAuthService ldapAuthService;
    @Autowired private EmailTemplateService emailTemplate;
    @Autowired private PortkeyClient portkeyClient;
    @Autowired private MaintenanceService maintenanceService;

    @Value("${mail.smtp.host}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from}") private String fromAddress;
    @Value("${ai.help.api-key:}") private String aiApiKey;
    @Value("${ai.help.enabled:false}") private boolean aiEnabled;
    @Value("${portkey.enabled:false}") private boolean portkeyEnabled;
    @Value("${portkey.api-key:}") private String portkeyApiKey;
    @Value("${portkey.provider:@anthropic-eastus2}") private String portkeyProvider;
    @Value("${portkey.model:claude-sonnet-4-6}") private String portkeyModel;
    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}") private String portkeyBaseUrl;

    // The What's New content — mirrors whats-new.js entries
    // Each entry: date, title, items (badge + text)
    private List<Map<String, Object>> releases = new ArrayList<>();
    private String lastSentVersion = "";

    @PostConstruct
    public void init() {
        loadLastSentVersion();
        loadReleasesFromJs();
    }

    // =========================================================================
    // Scheduled: every day at 10 AM
    // =========================================================================

    @Scheduled(cron = "0 0 10 * * *")
    public void sendDailyDigest() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[WHATS-NEW] Skipping daily digest — app is in maintenance mode.");
            return;
        }
        logger.info("[WHATS-NEW] Starting daily What's New digest...");
        try {
            sendDigestToAllUsers(false);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[WHATS-NEW] Failed to send daily digest", e);
        }
    }

    /**
     * On-demand trigger (from Reports tab).
     * @param forceAll if true, sends all releases, not just delta
     */
    public void sendDigestToAllUsers(boolean forceAll) {
        // Reload releases in case whats-new.js was updated
        loadReleasesFromJs();

        if (releases.isEmpty()) {
            logger.info("[WHATS-NEW] No releases found in whats-new.js. Skipping.");
            return;
        }

        String currentVersion = getVersionFingerprint();
        List<Map<String, Object>> toSend;

        if (forceAll || lastSentVersion.isEmpty()) {
            toSend = releases;
            logger.info("[WHATS-NEW] Sending ALL " + releases.size() + " releases.");
        } else {
            toSend = getDeltaReleases();
            if (toSend.isEmpty()) {
                logger.info("[WHATS-NEW] No new releases since last digest. Skipping.");
                return;
            }
            logger.info("[WHATS-NEW] Sending " + toSend.size() + " new release(s).");
        }

        // Get all unique users who have ever logged in
        Map<String, String> users = getAllLoggedInUsers(); // username -> displayName
        logger.info("[WHATS-NEW] Found " + users.size() + " unique users to notify.");

        int sent = 0, failed = 0;
        for (Map.Entry<String, String> user : users.entrySet()) {
            try {
                String username = user.getKey();
                String displayName = user.getValue();

                // Look up email from AD
                LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(username);
                if (info == null || info.email == null || info.email.isEmpty()) {
                    logger.info("[WHATS-NEW] No email found for " + displayName + " (" + username + "), skipping.");
                    continue;
                }

                String firstName = extractFirstName(displayName);
                // Check if this user is a PLM admin — admins see AI/Utilities items
                boolean isAdmin = isPlmAdmin(username);
                List<Map<String, Object>> userReleases = filterReleasesForAudience(toSend, isAdmin);
                if (userReleases.isEmpty()) {
                    logger.info("[WHATS-NEW] No visible items for " + displayName + " (non-admin), skipping.");
                    continue;
                }
                String emailBody = buildDigestEmail(firstName, userReleases, forceAll, isAdmin);
                sendEmail(info.email, firstName, userReleases, emailBody);
                sent++;
                logger.info("[WHATS-NEW] Sent to " + displayName + " (" + username + ") at " + info.email);
            } catch (Exception e) {
                failed++;
                logger.warning("[WHATS-NEW] Failed to send to " + user.getValue() + " (" + user.getKey() + "): " + e.getMessage());
            }
        }

        // Update last sent version
        lastSentVersion = currentVersion;
        saveLastSentVersion();
        logger.info("[WHATS-NEW] Digest complete. Sent: " + sent + ", Failed: " + failed);
    }

    // =========================================================================
    // Build the email
    // =========================================================================

    private String buildDigestEmail(String firstName, List<Map<String, Object>> releasesToSend, boolean isFullHistory, boolean isAdmin) {
        // Try AI-written intro
        String aiIntro = generateAiIntro(firstName, releasesToSend);

        // Build the structured content
        StringBuilder body = new StringBuilder();

        // AI intro or fallback
        if (aiIntro != null && !aiIntro.isEmpty()) {
            body.append(emailTemplate.callout("A note from PLM Toolkit", aiIntro, null));
        }

        // Release entries
        for (Map<String, Object> release : releasesToSend) {
            String date = (String) release.get("date");
            String title = (String) release.get("title");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) release.get("items");

            body.append("<table width='100%' cellpadding='0' cellspacing='0' style='margin:16px 0 0;font-family:\"IBM Plex Sans\",\"Segoe UI\",Calibri,Arial,sans-serif;'>");
            body.append("<tr><td style='font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:10px;letter-spacing:0.12em;text-transform:uppercase;color:#6B7280;font-weight:500;padding:0 0 6px;'>");
            body.append(esc(date));
            body.append("</td></tr>");
            body.append("<tr><td style='font-size:16px;font-weight:500;color:#0F1720;padding:0 0 10px;'>");
            body.append(esc(title));
            body.append("</td></tr>");

            if (items != null) {
                for (Map<String, String> item : items) {
                    String badge = item.get("badge");
                    String text = item.get("text");
                    String badgeColor, badgeBg;
                    if ("new".equals(badge)) { badgeColor = "#1F8A4C"; badgeBg = "#eef6f1"; }
                    else if ("improve".equals(badge)) { badgeColor = "#2C6FBB"; badgeBg = "#eef3fa"; }
                    else { badgeColor = "#C7801B"; badgeBg = "#faf3e5"; } // fix

                    body.append("<tr><td style='padding:4px 0 4px 0;font-size:13px;color:#2a2f37;line-height:1.6;'>");
                    body.append("<span style='display:inline-block;font-family:\"IBM Plex Mono\",Consolas,monospace;font-size:9px;font-weight:600;letter-spacing:0.1em;text-transform:uppercase;padding:2px 6px;border-radius:3px;background:").append(badgeBg).append(";color:").append(badgeColor).append(";margin-right:6px;vertical-align:middle;'>");
                    body.append(esc(badge));
                    body.append("</span>");
                    body.append(text); // already contains <strong> tags from whats-new.js
                    body.append("</td></tr>");
                }
            }
            body.append("</table>");
        }

        // Count items for KPI
        int newCount = 0, improveCount = 0, fixCount = 0;
        for (Map<String, Object> rel : releasesToSend) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) rel.get("items");
            if (items != null) for (Map<String, String> item : items) {
                String badge = item.get("badge");
                if ("new".equals(badge)) newCount++;
                else if ("improve".equals(badge)) improveCount++;
                else fixCount++;
            }
        }

        String kpis = emailTemplate.kpiRow(
            emailTemplate.kpiTile("New features", String.valueOf(newCount), "added"),
            emailTemplate.kpiTile("Improvements", String.valueOf(improveCount), "enhanced"),
            emailTemplate.kpiTile("Bug fixes", String.valueOf(fixCount), "resolved")
        );

        String heroTitle = isFullHistory
            ? "Everything that\u2019s new in PLM Toolkit."
            : releasesToSend.size() == 1
                ? ((Map<String, Object>) releasesToSend.get(0)).get("title").toString()
                : releasesToSend.size() + " updates since your last digest.";

        return emailTemplate.wrap(
            "What\u2019s New",
            isFullHistory ? "Full history" : "Update",
            releasesToSend.get(0).get("date").toString(),
            heroTitle,
            "Hi " + firstName + " \u2014 here\u2019s what shipped recently.",
            kpis,
            body.toString(),
            "Open PLM Toolkit",
            "http://uls-ep-aglipccb:8090/index.html",
            "See it live",
            null,
            null,
            "You received this because you\u2019ve used PLM Toolkit"
        );
    }

    // =========================================================================
    // AI-generated conversational intro
    // =========================================================================

    private String generateAiIntro(String firstName, List<Map<String, Object>> releasesToSend) {
        if (!aiEnabled || aiApiKey == null || aiApiKey.isEmpty()) {
            return "<p style='font-size:13px;color:#2a2f37;line-height:1.7;margin:0;'>Hey " + esc(firstName) + "! We\u2019ve been busy making PLM Toolkit better for you. Here\u2019s what\u2019s new.</p>";
        }

        try {
            // Build a summary of changes for the AI
            StringBuilder changesSummary = new StringBuilder();
            for (Map<String, Object> rel : releasesToSend) {
                changesSummary.append("Release: ").append(rel.get("date")).append(" - ").append(rel.get("title")).append("\n");
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) rel.get("items");
                if (items != null) for (Map<String, String> item : items) {
                    String text = item.get("text").replaceAll("<[^>]+>", ""); // strip HTML
                    changesSummary.append("  [").append(item.get("badge")).append("] ").append(text).append("\n");
                }
            }

            String systemPrompt = "You are writing a short, warm email intro for a PLM engineering tool called PLM Toolkit. " +
                "Address the recipient by their first name ('" + escJson(firstName) + "'). " +
                "Write 2-3 sentences in a conversational, friendly tone — like a colleague sharing good news. " +
                "Mention 1-2 specific highlights from the changes that would excite an engineer. " +
                "Do NOT use marketing speak, emojis, or exclamation marks excessively. " +
                "Keep it under 60 words. Return ONLY the paragraph text, no greeting or sign-off.";

            String userContent = "Changes to summarize:\n" + changesSummary.toString();
            String model = portkeyProvider + "/" + portkeyModel;
            String answer = portkeyClient.chat(model, systemPrompt, userContent, 200);

            return "<p style='font-size:13px;color:#2a2f37;line-height:1.7;margin:0;'>" + answer + "</p>";

        } catch (Exception e) {
            logger.warning("[WHATS-NEW] AI intro generation failed: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Send the email
    // =========================================================================

    private void sendEmail(String toEmail, String firstName, List<Map<String, Object>> releases, String html) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        javax.mail.Session session = javax.mail.Session.getInstance(props);
        javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(session);
        message.setFrom(new javax.mail.internet.InternetAddress(fromAddress));
        message.setRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress(toEmail));

        // Count total items
        int totalItems = 0;
        for (Map<String, Object> rel : releases) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) rel.get("items");
            if (items != null) totalItems += items.size();
        }

        String date = releases.get(0).get("date").toString();
        message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("What\u2019s new \u00b7 " + totalItems + " updates \u00b7 " + date));
        message.setContent(html, "text/html; charset=utf-8");
        javax.mail.Transport.send(message);
    }

    // =========================================================================
    // User discovery — get all users who have ever logged in
    // =========================================================================

    private Map<String, String> getAllLoggedInUsers() {
        Map<String, String> users = new LinkedHashMap<>();
        try {
            // Read entire activity log file
            File logFile = new File(activityLogger.getLogFilePath());
            if (!logFile.exists()) return users;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("\"LOGIN\"")) continue;
                    // Extract username and displayName from JSONL
                    String username = extractJsonField(line, "username");
                    String displayName = extractJsonField(line, "displayName");
                    if (username != null && !username.isEmpty()) {
                        users.put(username, displayName != null ? displayName : username);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[WHATS-NEW] Failed to read activity log: " + e.getMessage());
        }
        return users;
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    // =========================================================================
    // Parse whats-new.js to extract release data
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void loadReleasesFromJs() {
        releases.clear();
        try {
            // Try classpath first (inside JAR), then filesystem fallback
            String content = null;
            try (InputStream is = getClass().getResourceAsStream("/static/whats-new.js")) {
                if (is != null) {
                    byte[] bytes = new byte[is.available()];
                    int total = 0;
                    while (total < bytes.length) {
                        int read = is.read(bytes, total, bytes.length - total);
                        if (read < 0) break;
                        total += read;
                    }
                    content = new String(bytes, 0, total, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}

            // Filesystem fallback for development
            if (content == null) {
                File jsFile = new File("src/main/resources/static/whats-new.js");
                if (jsFile.exists()) {
                    content = new String(Files.readAllBytes(jsFile.toPath()), StandardCharsets.UTF_8);
                }
            }

            if (content == null || content.isEmpty()) {
                logger.warning("[WHATS-NEW] whats-new.js not found in classpath or filesystem");
                return;
            }

            // Extract the array between WHATS_NEW_RELEASES = [ ... ];
            int arrStart = content.indexOf("var WHATS_NEW_RELEASES = [");
            if (arrStart < 0) return;
            arrStart = content.indexOf("[", arrStart);

            // Find the matching close bracket
            int depth = 0;
            int arrEnd = -1;
            for (int i = arrStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { arrEnd = i + 1; break; } }
            }
            if (arrEnd < 0) return;

            String arrayStr = content.substring(arrStart, arrEnd);

            // Convert JS object notation to valid JSON:
            // - Add quotes around keys (date, title, items, badge, text)
            // - Handle single quotes → double quotes in values
            // - Handle unquoted keys
            arrayStr = arrayStr.replaceAll("(\\w+)\\s*:", "\"$1\":");
            // Fix escaped single quotes in values like "What\'s New"
            arrayStr = arrayStr.replace("\\'", "'");
            // The 'text' values contain HTML with <strong> etc — these are already in single-quoted JS strings
            // We need to handle the JS string delimiters (single quotes)
            // Instead of complex regex, let's parse it manually

            // Simpler approach: parse each release block manually
            releases = parseReleasesManually(content, arrStart, arrEnd);

            logger.info("[WHATS-NEW] Loaded " + releases.size() + " releases from whats-new.js");
        } catch (Exception e) {
            logger.log(Level.WARNING, "[WHATS-NEW] Failed to parse whats-new.js", e);
        }
    }

    private List<Map<String, Object>> parseReleasesManually(String content, int arrStart, int arrEnd) {
        List<Map<String, Object>> result = new ArrayList<>();
        String block = content.substring(arrStart, arrEnd);

        // Split on top-level { } blocks within the array
        int depth = 0;
        int blockStart = -1;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '{' && depth == 0) { blockStart = i; depth++; }
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0 && blockStart >= 0) {
                    String releaseBlock = block.substring(blockStart, i + 1);
                    Map<String, Object> release = parseOneRelease(releaseBlock);
                    if (release != null) result.add(release);
                    blockStart = -1;
                }
            }
        }
        return result;
    }

    private Map<String, Object> parseOneRelease(String block) {
        Map<String, Object> release = new LinkedHashMap<>();

        // Extract date
        String date = extractJsString(block, "date");
        String title = extractJsString(block, "title");
        if (date == null || title == null) return null;
        release.put("date", date);
        release.put("title", title);

        // Extract items array
        List<Map<String, String>> items = new ArrayList<>();
        int itemsStart = block.indexOf("items:");
        if (itemsStart < 0) itemsStart = block.indexOf("items :");
        if (itemsStart >= 0) {
            int arrS = block.indexOf("[", itemsStart);
            if (arrS >= 0) {
                // Find each { badge: '...', text: '...' } within items
                int d = 0;
                int is = -1;
                for (int i = arrS; i < block.length(); i++) {
                    char c = block.charAt(i);
                    if (c == '{' && d == 0) { is = i; d++; }
                    else if (c == '{') d++;
                    else if (c == '}') {
                        d--;
                        if (d == 0 && is >= 0) {
                            String itemBlock = block.substring(is, i + 1);
                            String badge = extractJsString(itemBlock, "badge");
                            String text = extractJsString(itemBlock, "text");
                            if (badge != null && text != null) {
                                Map<String, String> item = new LinkedHashMap<>();
                                item.put("badge", badge);
                                item.put("text", text);
                                // Check for admin: true flag
                                if (itemBlock.contains("admin:") && itemBlock.contains("true")) {
                                    item.put("admin", "true");
                                }
                                items.add(item);
                            }
                            is = -1;
                        }
                    }
                    if (c == ']' && d == -1) break; // end of items array
                }
            }
        }
        release.put("items", items);
        return release;
    }

    private String extractJsString(String block, String key) {
        // Look for key: 'value' or key: "value"
        int idx = block.indexOf(key + ":");
        if (idx < 0) idx = block.indexOf(key + " :");
        if (idx < 0) return null;

        int start = idx + key.length() + 1;
        // Skip whitespace
        while (start < block.length() && (block.charAt(start) == ' ' || block.charAt(start) == ':')) start++;
        if (start >= block.length()) return null;

        char quote = block.charAt(start);
        if (quote != '\'' && quote != '"') return null;
        start++;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '\\' && i + 1 < block.length()) {
                char next = block.charAt(i + 1);
                if (next == quote) { sb.append(quote); i++; continue; }
                if (next == '\\') { sb.append('\\'); i++; continue; }
                if (next == 'n') { sb.append('\n'); i++; continue; }
                if (next == 't') { sb.append('\t'); i++; continue; }
                if (next == 'r') { sb.append('\r'); i++; continue; }
                if (next == '/') { sb.append('/'); i++; continue; }
                // Backslash-u-HHHH (JS / JSON unicode escape). Consume all 6 chars and
                // emit the actual codepoint. Without this branch the literal escape sequences
                // for apostrophe, em-dash, ellipsis, etc. from whats-new.js leak into the
                // digest email body as visible text.
                if (next == 'u' && i + 5 < block.length()) {
                    String hex = block.substring(i + 2, i + 6);
                    try {
                        int cp = Integer.parseInt(hex, 16);
                        sb.append((char) cp);
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // fall through to literal append below
                    }
                }
                sb.append(c);
            } else if (c == quote) {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Version tracking
    // =========================================================================

    private String getVersionFingerprint() {
        if (releases.isEmpty()) return "";
        Map<String, Object> latest = releases.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) latest.get("items");
        return latest.get("date") + "-" + (items != null ? items.size() : 0);
    }

    private List<Map<String, Object>> getDeltaReleases() {
        List<Map<String, Object>> delta = new ArrayList<>();
        for (Map<String, Object> rel : releases) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) rel.get("items");
            String fp = rel.get("date") + "-" + (items != null ? items.size() : 0);
            if (fp.equals(lastSentVersion)) break; // everything before this was already sent
            delta.add(rel);
        }
        return delta;
    }

    private void loadLastSentVersion() {
        try {
            File f = new File(LAST_DIGEST_FILE);
            if (f.exists()) {
                lastSentVersion = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            logger.warning("[WHATS-NEW] Failed to load last digest version: " + e.getMessage());
        }
    }

    private void saveLastSentVersion() {
        try {
            File f = new File(LAST_DIGEST_FILE);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), lastSentVersion.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warning("[WHATS-NEW] Failed to save last digest version: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String extractFirstName(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "there";
        // Handle "Last, First" format
        if (displayName.contains(",")) {
            String[] parts = displayName.split(",");
            return parts.length > 1 ? parts[1].trim().split(" ")[0] : parts[0].trim();
        }
        return displayName.split(" ")[0];
    }

    // =========================================================================
    // Audience filtering — admin vs regular users
    // =========================================================================

    /**
     * Check if a user is in the pdl-plm-admin group by looking up their AD groups.
     */
    private boolean isPlmAdmin(String username) {
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(username);
            if (info == null) return false;
            // Check if the user's groups include pdl-plm-admin
            // Since UserInfo doesn't have groups, we check via auth attempt
            // Simpler: check if the username appears in the admin activity patterns
            // For now, use a dedicated LDAP check
            return ldapAuthService.isUserInAdminGroup(username);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Filter releases for the given audience.
     * Non-admins: remove items with admin=true (from code or server flags)
     * Admins: keep all items, admin items sorted to top
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterReleasesForAudience(List<Map<String, Object>> releases, boolean isAdmin) {
        // Load server-side admin flags
        Map<String, Boolean> serverFlags = loadServerAdminFlags();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rel : releases) {
            List<Map<String, String>> items = (List<Map<String, String>>) rel.get("items");
            if (items == null) continue;
            String date = (String) rel.get("date");

            List<Map<String, String>> filtered;
            if (isAdmin) {
                List<Map<String, String>> adminItems = new ArrayList<>();
                List<Map<String, String>> userItems = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Map<String, String> item = items.get(i);
                    boolean isAdminItem = "true".equals(item.get("admin")) || Boolean.TRUE.equals(serverFlags.get(date + ":" + i));
                    if (isAdminItem) adminItems.add(item);
                    else userItems.add(item);
                }
                filtered = new ArrayList<>(adminItems);
                filtered.addAll(userItems);
            } else {
                filtered = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Map<String, String> item = items.get(i);
                    boolean isAdminItem = "true".equals(item.get("admin")) || Boolean.TRUE.equals(serverFlags.get(date + ":" + i));
                    if (!isAdminItem) filtered.add(item);
                }
            }

            if (!filtered.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(rel);
                copy.put("items", filtered);
                result.add(copy);
            }
        }
        return result;
    }

    private Map<String, Boolean> loadServerAdminFlags() {
        try {
            File f = new File("./data/whatsnew-admin-flags.json");
            if (!f.exists()) return new LinkedHashMap<>();
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.readValue(f, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Boolean>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
