package com.sandisk.plm.tracker.util;

import com.sandisk.plm.tracker.service.PortkeyClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides which spreadsheet column holds the person's Name and which holds the
 * Email. Two tiers (same shape as {@link UploadColumnDetector}):
 *   1. Header heuristic (no network) — keyword match on header text.
 *   2. AI fallback (Claude Haiku via Portkey) — only when the heuristic can't
 *      confidently find a Name column. Returns JSON we parse best-effort.
 * If both fail, returns a non-confident Mapping carrying a {@code question}
 * the UI surfaces so the admin can pick columns manually.
 */
@Component
public class UserColumnMapper {

    private static final Logger logger = Logger.getLogger(UserColumnMapper.class.getName());

    @Autowired(required = false) private PortkeyClient portkeyClient;
    @Value("${portkey.model:@anthropic-eastus2/claude-haiku-4-5-20251001}") private String aiModel;
    @Value("${app.upload-column-detect.ai-fallback:true}") private boolean aiEnabled;

    public static class Mapping {
        public int nameColumn = -1;
        public int emailColumn = -1;
        public boolean confident = false;
        public String method = "none";   // "heuristic" | "ai" | "none"
        public String question;           // non-null when !confident
    }

    public Mapping map(List<String> headers, List<List<String>> sampleRows) {
        Mapping h = byHeuristic(headers);
        if (h.confident) return h;

        if (aiEnabled && portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                Mapping ai = byAi(headers, sampleRows);
                if (ai != null && ai.nameColumn >= 0) { ai.method = "ai"; return ai; }
            } catch (Exception e) {
                logger.log(Level.WARNING, "[USER-IMPORT] AI column mapping failed: " + e.getMessage());
            }
        }
        // Fall back to whatever the heuristic found (may be partial), not confident.
        if (h.question == null) {
            h.question = "Couldn't tell which column is the person's name. Pick the Name column"
                + (h.emailColumn < 0 ? " and the Email column." : ".");
        }
        return h;
    }

    private Mapping byHeuristic(List<String> headers) {
        Mapping m = new Mapping();
        for (int c = 0; c < headers.size(); c++) {
            String n = normalize(headers.get(c));
            if (m.emailColumn < 0 && (n.contains("email") || n.contains("mail") || n.equals("e"))) m.emailColumn = c;
        }
        for (int c = 0; c < headers.size(); c++) {
            String n = normalize(headers.get(c));
            if (c == m.emailColumn) continue;
            if (m.nameColumn < 0 && (n.equals("name") || n.contains("fullname") || n.contains("displayname")
                    || n.contains("employeename") || n.equals("user") || n.contains("username"))) m.nameColumn = c;
        }
        m.method = "heuristic";
        // Confident if we found a name column. Email may legitimately be absent.
        m.confident = m.nameColumn >= 0;
        if (!m.confident) m.question = "Couldn't find a Name column. Which column holds the person's name?";
        return m;
    }

    private Mapping byAi(List<String> headers, List<List<String>> sampleRows) throws Exception {
        StringBuilder p = new StringBuilder();
        p.append("A PLM admin uploaded a spreadsheet of people to grant tool access to. ");
        p.append("Identify which column holds the person's NAME and which holds their EMAIL.\n\n");
        p.append("HEADERS (0-indexed): ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) p.append(" | ");
            p.append('[').append(i).append("]=").append(nz(headers.get(i)));
        }
        p.append("\n\nSAMPLE ROWS:\n");
        int n = Math.min(3, sampleRows == null ? 0 : sampleRows.size());
        for (int r = 0; r < n; r++) {
            p.append("row ").append(r + 1).append(": ");
            List<String> row = sampleRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) p.append(" | ");
                p.append('[').append(i).append("]=").append(nz(row.get(i)));
            }
            p.append('\n');
        }
        p.append("\nReply with JSON only, no prose: ");
        p.append("{\"nameColumn\": <int>, \"emailColumn\": <int or -1 if none>, ");
        p.append("\"confident\": <true|false>, \"reasoning\": \"<one short sentence>\"}");

        String system = "You map spreadsheet columns for a PLM tool. Reply with JSON only.";
        String resp = portkeyClient.chat(aiModel, system, p.toString(), 200);
        if (resp == null) return null;
        int s = resp.indexOf('{'), e = resp.lastIndexOf('}');
        if (s < 0 || e < s) return null;
        String json = resp.substring(s, e + 1);
        Integer nameCol = extractInt(json, "nameColumn");
        Integer emailCol = extractInt(json, "emailColumn");
        if (nameCol == null || nameCol < 0 || nameCol >= headers.size()) return null;
        Mapping m = new Mapping();
        m.nameColumn = nameCol;
        m.emailColumn = (emailCol != null && emailCol >= 0 && emailCol < headers.size()) ? emailCol : -1;
        m.confident = extractBool(json, "confident");
        return m;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[\\s_\\-#.]+", "");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Integer extractInt(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int start = j;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        if (j == start) return null;
        try { return Integer.parseInt(json.substring(start, j)); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean extractBool(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return false;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return false;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        return json.regionMatches(true, j, "true", 0, 4);
    }
}
