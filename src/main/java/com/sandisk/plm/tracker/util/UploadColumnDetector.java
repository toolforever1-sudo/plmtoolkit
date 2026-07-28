package com.sandisk.plm.tracker.util;

import com.sandisk.plm.tracker.service.PortkeyClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects which column of an uploaded spreadsheet holds the item number.
 *
 * <p>Two-tier strategy:</p>
 * <ol>
 *   <li><strong>Header heuristic</strong> (fast, no network) — matches row-1 cells against a
 *       priority-ordered list of known item-number header keywords (item number, part number,
 *       sku, mpn, pn, etc.). Covers the vast majority of files.</li>
 *   <li><strong>AI fallback</strong> — only fires when the heuristic finds 0 matches.
 *       Sends the headers + 2 sample rows to a small LLM (Claude Haiku via Portkey) and
 *       asks for the column index. Bounded to ~150 tokens out and ~30s timeout.</li>
 * </ol>
 *
 * <p>If both fail (e.g. AI disabled and no header match), defaults to column 0 with
 * {@code method = "default-col-a"} so the legacy behavior is preserved.</p>
 */
@Component
public class UploadColumnDetector {

    private static final Logger logger = Logger.getLogger(UploadColumnDetector.class.getName());

    /** Priority order — earlier entries win when multiple columns match.
     *  All entries are normalized (lowercased, spaces and underscores stripped). */
    private static final List<String> PRIORITY_KEYWORDS = Arrays.asList(
        "itemnumber", "itemno", "itemnum", "itemid",
        "partnumber", "partno", "partnum", "partid",
        "componentnumber", "componentno", "componentid",
        "sku", "skunumber", "skuid",
        "mpn", "manufacturerpartnumber",
        "pn", "pnumber",
        "materialnumber", "material",
        "item", "part", "component",
        "number"
    );

    /** Public read-only view so callers (e.g. SmartSheetPicker) can score columns
     *  with the same priority order the rest of the detector uses. */
    public static List<String> priorityKeywords() {
        return java.util.Collections.unmodifiableList(PRIORITY_KEYWORDS);
    }

    /** Public-visible normalize (used by SmartSheetPicker for sheet scoring). */
    public static String normalizeKey(String s) { return normalize(s); }

    @Autowired(required = false) private PortkeyClient portkeyClient;
    @Value("${portkey.model:@anthropic-eastus2/claude-haiku-4-5-20251001}") private String aiModel;
    @Value("${app.upload-column-detect.ai-fallback:true}") private boolean aiFallbackEnabled;

    /**
     * @param headerRow  row-1 cell values, in column order. Cells past the data range may be
     *                    empty strings; trailing empties are fine.
     * @param sampleRows up to 3 rows of data (row 2..4) for the AI fallback prompt. May be
     *                    empty if the file has only headers.
     * @return non-null result; method indicates which tier succeeded.
     */
    public DetectionResult detect(List<String> headerRow, List<List<String>> sampleRows) {
        // Tier 1: header heuristic.
        DetectionResult byHeader = detectByHeader(headerRow);
        if (byHeader != null) return byHeader;

        // Tier 2: AI fallback (only when heuristic returns no match).
        if (aiFallbackEnabled && portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                DetectionResult byAi = detectByAi(headerRow, sampleRows);
                if (byAi != null) return byAi;
            } catch (Exception e) {
                logger.log(Level.WARNING, "[UPLOAD-DETECT] AI fallback failed; defaulting to col A: "
                        + e.getMessage());
            }
        }

        // Tier 3: legacy default (column A, treat row 1 as header if it looks like one).
        return new DetectionResult(0,
                headerRow.isEmpty() ? "" : nz(headerRow.get(0)),
                "default-col-a",
                isLikelyHeader(headerRow.isEmpty() ? "" : headerRow.get(0)) ? 0 : -1);
    }

    private DetectionResult detectByHeader(List<String> headerRow) {
        if (headerRow == null || headerRow.isEmpty()) return null;

        // Find the priority-best match across all columns.
        int bestPriority = Integer.MAX_VALUE;
        int bestColumn = -1;
        for (int c = 0; c < headerRow.size(); c++) {
            String norm = normalize(headerRow.get(c));
            if (norm.isEmpty()) continue;
            int p = PRIORITY_KEYWORDS.indexOf(norm);
            if (p >= 0 && p < bestPriority) {
                bestPriority = p;
                bestColumn = c;
            }
        }
        if (bestColumn < 0) return null;
        return new DetectionResult(bestColumn, headerRow.get(bestColumn).trim(), "header-match", 0);
    }

    private DetectionResult detectByAi(List<String> headerRow, List<List<String>> sampleRows) throws Exception {
        // Build a tight prompt. Keep input small — headers + first 2 rows.
        StringBuilder prompt = new StringBuilder();
        prompt.append("You're given the headers and first few rows of a spreadsheet uploaded by a ");
        prompt.append("PLM user. Identify which column holds the **item / part / SKU number**.\n\n");
        prompt.append("HEADERS (0-indexed): ");
        for (int i = 0; i < headerRow.size(); i++) {
            if (i > 0) prompt.append(" | ");
            prompt.append('[').append(i).append("]=").append(nz(headerRow.get(i)));
        }
        prompt.append("\n\nSAMPLE ROWS:\n");
        int n = Math.min(3, sampleRows == null ? 0 : sampleRows.size());
        for (int r = 0; r < n; r++) {
            prompt.append("row ").append(r + 2).append(": ");
            List<String> row = sampleRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) prompt.append(" | ");
                prompt.append('[').append(i).append("]=").append(nz(row.get(i)));
            }
            prompt.append('\n');
        }
        prompt.append("\nReply with **JSON only**, no prose: ");
        prompt.append("{\"column\": <0-indexed integer>, \"header\": \"<header text>\", \"reasoning\": \"<one short sentence>\"}");

        String system = "You are a column-detection helper for a PLM tool. Reply with JSON only.";
        String resp = portkeyClient.chat(aiModel, system, prompt.toString(), 200);
        if (resp == null) return null;

        // Best-effort JSON extract — model output may contain stray text.
        int braceStart = resp.indexOf('{');
        int braceEnd = resp.lastIndexOf('}');
        if (braceStart < 0 || braceEnd < braceStart) return null;
        String json = resp.substring(braceStart, braceEnd + 1);
        Integer col = extractInt(json, "column");
        if (col == null || col < 0 || col >= headerRow.size()) return null;
        String header = nz(headerRow.get(col));
        return new DetectionResult(col, header, "ai-fallback", 0);
    }

    /**
     * Whether a string looks like a header rather than an item number — used as a final
     * fallback when there's no detectable header column.
     */
    public static boolean isLikelyHeader(String value) {
        if (value == null) return false;
        String norm = normalize(value);
        if (norm.isEmpty()) return false;
        if (PRIORITY_KEYWORDS.contains(norm)) return true;
        // common header-ish words
        return norm.equals("description") || norm.equals("type") || norm.equals("category")
                || norm.equals("status") || norm.equals("rev") || norm.equals("revision")
                || norm.equals("lifecyclephase") || norm.equals("changenumber");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replaceAll("[\\s_\\-#]+", "")  // spaces, underscores, hyphens, hash
                .replaceAll("[\\(\\)\\[\\]]", "");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Integer extractInt(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        // find next non-whitespace
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int start = j;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        if (j == start) return null;
        try { return Integer.parseInt(json.substring(start, j)); }
        catch (NumberFormatException e) { return null; }
    }

    /** Result POJO. */
    public static class DetectionResult {
        public final int column;        // 0-indexed
        public final String header;
        public final String method;     // "header-match" | "ai-fallback" | "default-col-a"
        public final int headerRow;     // 0-indexed; -1 if no header was detected

        public DetectionResult(int column, String header, String method, int headerRow) {
            this.column = column;
            this.header = header;
            this.method = method;
            this.headerRow = headerRow;
        }
    }
}
