package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

@Service
public class ActivityLogger {

    private static final Logger logger = Logger.getLogger(ActivityLogger.class.getName());

    // Store activities in memory (thread-safe)
    private final ConcurrentLinkedDeque<ActivityEntry> activities = new ConcurrentLinkedDeque<>();
    private static final long RETENTION_MS = 24 * 60 * 60 * 1000; // 24 hours for in-memory
    private static final long LOAD_WINDOW_MS = 7L * 24 * 60 * 60 * 1000; // 7 days for file load

    @Value("${app.activity-log.file:./data/activity-log.jsonl}")
    private String logFilePath;

    public String getLogFilePath() { return logFilePath; }

    @PostConstruct
    public void init() {
        loadRecentFromFile();
    }

    private void loadRecentFromFile() {
        File f = new File(logFilePath);
        if (!f.exists()) {
            logger.info("[ACTIVITY] No activity log file found at " + logFilePath);
            return;
        }
        long cutoff = System.currentTimeMillis() - LOAD_WINDOW_MS;
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    ActivityEntry entry = parseJsonLine(line);
                    if (entry != null && entry.timestamp >= cutoff) {
                        activities.addLast(entry);
                        loaded++;
                    }
                } catch (Exception e) {
                    // skip malformed lines
                }
            }
        } catch (Exception e) {
            logger.warning("[ACTIVITY] Failed to load activity log: " + e.getMessage());
        }
        logger.info("[ACTIVITY] Loaded " + loaded + " recent entries from " + logFilePath);
    }

    public void log(String username, String displayName, String action, String details) {
        long now = System.currentTimeMillis();
        // Purge old entries from in-memory list
        while (!activities.isEmpty() && (now - activities.peekFirst().timestamp) > RETENTION_MS) {
            activities.pollFirst();
        }
        ActivityEntry entry = new ActivityEntry(now, username, displayName, action, details);
        activities.addLast(entry);
        logger.info("[ACTIVITY] " + displayName + " | " + action + " | " + details);

        // Persist to JSONL file
        appendToFile(entry);
    }

    private synchronized void appendToFile(ActivityEntry entry) {
        try {
            File f = new File(logFilePath);
            f.getParentFile().mkdirs();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            String json = "{\"timestamp\":\"" + sdf.format(new Date(entry.timestamp)) + "\"," +
                "\"epochMs\":" + entry.timestamp + "," +
                "\"username\":\"" + escJson(entry.username) + "\"," +
                "\"displayName\":\"" + escJson(entry.displayName) + "\"," +
                "\"action\":\"" + escJson(entry.action) + "\"," +
                "\"details\":\"" + escJson(entry.details) + "\"}";
            try (FileWriter writer = new FileWriter(f, true)) {
                writer.write(json + "\n");
            }
        } catch (Exception e) {
            logger.warning("[ACTIVITY] Failed to write to log file: " + e.getMessage());
        }
    }

    private ActivityEntry parseJsonLine(String line) {
        // Simple JSON parser for our known format
        long epochMs = extractLong(line, "epochMs");
        if (epochMs <= 0) {
            // Try parsing from timestamp string if epochMs not present
            String ts = extractString(line, "timestamp");
            if (ts != null) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    epochMs = sdf.parse(ts).getTime();
                } catch (Exception e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        String username = extractString(line, "username");
        String displayName = extractString(line, "displayName");
        String action = extractString(line, "action");
        String details = extractString(line, "details");
        return new ActivityEntry(epochMs, username, displayName, action, details);
    }

    private String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0) break;
        }
        try { return Long.parseLong(sb.toString()); } catch (Exception e) { return 0; }
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public List<ActivityEntry> getLast24Hours() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        List<ActivityEntry> result = new ArrayList<>();
        for (ActivityEntry e : activities) {
            if (e.timestamp >= cutoff) result.add(e);
        }
        return result;
    }

    public List<ActivityEntry> getActivitiesSince(long epochMs) {
        // In-memory only holds the last LOAD_WINDOW_MS at startup, then RETENTION_MS thereafter.
        // If the caller asks for older entries, the in-memory list is incomplete — read the file.
        // The file is authoritative because appendToFile() is synchronous on every log() call.
        long oldestInMem = activities.isEmpty() ? Long.MAX_VALUE : activities.peekFirst().timestamp;
        if (epochMs >= oldestInMem) {
            List<ActivityEntry> result = new ArrayList<>();
            for (ActivityEntry e : activities) {
                if (e.timestamp > epochMs) result.add(e);
            }
            return result;
        }

        // Need older data — read the full file.
        List<ActivityEntry> result = new ArrayList<>();
        File f = new File(logFilePath);
        if (!f.exists()) return result;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    ActivityEntry entry = parseJsonLine(line);
                    if (entry != null && entry.timestamp > epochMs) {
                        result.add(entry);
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        } catch (Exception e) {
            logger.warning("[ACTIVITY] Failed to read log file for getActivitiesSince: " + e.getMessage());
        }
        return result;
    }

    public String formatAsHtml() {
        List<ActivityEntry> entries = getLast24Hours();
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, HH:mm:ss");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Calibri, Arial, sans-serif;'>");
        html.append("<h2 style='color:#1a3a5c;'>PLM Toolkit - Activity Report (Last 24 Hours)</h2>");
        html.append("<p>Generated: ").append(sdf.format(new Date())).append(" | Total activities: ").append(entries.size()).append("</p>");

        if (entries.isEmpty()) {
            html.append("<p style='color:#888;'>No activity recorded in the last 24 hours.</p>");
        } else {
            // Summary by user
            Map<String, Integer> userCounts = new LinkedHashMap<>();
            Map<String, Integer> actionCounts = new LinkedHashMap<>();
            for (ActivityEntry e : entries) {
                userCounts.merge(e.displayName, 1, Integer::sum);
                actionCounts.merge(e.action, 1, Integer::sum);
            }

            html.append("<h3>Summary by User</h3>");
            html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse; border-color:#ddd;'>");
            html.append("<tr style='background:#2c3e50; color:white;'><th>User</th><th>Actions</th></tr>");
            for (Map.Entry<String, Integer> e : userCounts.entrySet()) {
                html.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue()).append("</td></tr>");
            }
            html.append("</table>");

            html.append("<h3>Summary by Action</h3>");
            html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse; border-color:#ddd;'>");
            html.append("<tr style='background:#2c3e50; color:white;'><th>Action</th><th>Count</th></tr>");
            for (Map.Entry<String, Integer> e : actionCounts.entrySet()) {
                html.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue()).append("</td></tr>");
            }
            html.append("</table>");

            html.append("<h3>Activity Log</h3>");
            html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse; border-color:#ddd; font-size:13px;'>");
            html.append("<tr style='background:#2c3e50; color:white;'><th>Time</th><th>User</th><th>Action</th><th>Details</th></tr>");
            // Show most recent first
            for (int i = entries.size() - 1; i >= 0; i--) {
                ActivityEntry e = entries.get(i);
                html.append("<tr>");
                html.append("<td style='white-space:nowrap;'>").append(sdf.format(new Date(e.timestamp))).append("</td>");
                html.append("<td>").append(e.displayName).append("</td>");
                html.append("<td>").append(e.action).append("</td>");
                html.append("<td>").append(e.details).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        html.append("<br><p style='color:#999; font-size:12px;'>PLM Toolkit - Activity Logger</p>");
        html.append("</body></html>");
        return html.toString();
    }

    public static class ActivityEntry {
        public final long timestamp;
        public final String username;
        public final String displayName;
        public final String action;
        public final String details;

        public ActivityEntry(long timestamp, String username, String displayName, String action, String details) {
            this.timestamp = timestamp;
            this.username = username;
            this.displayName = displayName;
            this.action = action;
            this.details = details;
        }
    }
}
