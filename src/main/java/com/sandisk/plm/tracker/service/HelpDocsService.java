package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Document knowledge base for the Help Guide tab.
 * Extracts text from PPTX/PDF/XLSX, indexes for keyword search.
 * Works without AI — pure keyword matching.
 */
@Service
public class HelpDocsService {

    private static final Logger LOG = Logger.getLogger(HelpDocsService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DOCS_DIR = "./data/help-docs";
    private static final String INDEX_FILE = "./data/help-docs-index.json";

    // In-memory index: list of chunks with metadata
    private volatile List<DocChunk> chunks = new ArrayList<>();
    private volatile List<DocMeta> docs = new ArrayList<>();

    @PostConstruct
    public void init() {
        new File(DOCS_DIR).mkdirs();
        loadIndex();
    }

    // ── Data classes ──

    public static class DocMeta {
        public String id;
        public String filename;
        public String title;
        public String uploadedBy;
        public String uploadedAt;
        public int chunkCount;
        public long fileSize;
    }

    public static class DocChunk {
        public String docId;
        public String docTitle;
        public String text;          // the chunk text
        public int slideOrPage;      // slide number, page number, or sheet number
        public String[] keywords;    // pre-computed lowercase keywords for fast matching
    }

    // ── Public API ──

    public List<DocMeta> listDocs() { return docs; }

    public int getDocCount() { return docs.size(); }
    public int getChunkCount() { return chunks.size(); }

    /**
     * Search for chunks matching a query. Returns top N matches sorted by relevance.
     * Pure keyword matching — no AI needed.
     */
    public List<Map<String, Object>> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty() || chunks.isEmpty()) return Collections.emptyList();

        String[] queryWords = query.toLowerCase().split("\\s+");
        // Remove common stop words
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "can", "could", "of", "in", "to", "for",
            "with", "on", "at", "by", "from", "as", "into", "about", "what",
            "how", "which", "who", "when", "where", "why", "this", "that", "it",
            "and", "or", "but", "not", "no", "if", "then", "than", "so", "very",
            "i", "me", "my", "we", "you", "your", "they", "them", "their"
        ));
        List<String> searchTerms = new ArrayList<>();
        for (String w : queryWords) {
            if (w.length() > 1 && !stopWords.contains(w)) searchTerms.add(w);
        }
        if (searchTerms.isEmpty()) return Collections.emptyList();

        // Score each chunk
        List<Map<String, Object>> results = new ArrayList<>();
        for (DocChunk chunk : chunks) {
            int score = 0;
            String textLower = chunk.text.toLowerCase();
            Set<String> matchedTerms = new HashSet<>();

            for (String term : searchTerms) {
                // Exact word match in keywords array
                if (chunk.keywords != null) {
                    for (String kw : chunk.keywords) {
                        if (kw.contains(term)) { score += 3; matchedTerms.add(term); break; }
                    }
                }
                // Substring match in full text
                if (textLower.contains(term)) { score += 1; matchedTerms.add(term); }
            }

            // Bonus for matching multiple terms
            if (matchedTerms.size() > 1) score += matchedTerms.size() * 2;

            // Bonus for phrase match (consecutive words from query found together)
            String queryPhrase = String.join(" ", searchTerms);
            if (textLower.contains(queryPhrase)) score += 10;

            if (score > 0) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("score", score);
                result.put("docTitle", chunk.docTitle);
                result.put("docId", chunk.docId);
                result.put("slideOrPage", chunk.slideOrPage);
                result.put("text", chunk.text);
                result.put("matchedTerms", matchedTerms);
                // Total significant query terms (after stopword removal) — lets
                // callers (AiHelpController) compute matched-term coverage
                // without re-tokenising the original question.
                result.put("queryTermsTotal", searchTerms.size());
                results.add(result);
            }
        }

        // Sort by score descending
        results.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));

        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    /**
     * Upload and index a new document. Extracts text, creates chunks, saves to disk.
     */
    public DocMeta uploadAndIndex(String filename, byte[] fileData, String uploadedBy) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();

        // Save the original file
        File savedFile = new File(DOCS_DIR, id + "-" + filename);
        try (FileOutputStream fos = new FileOutputStream(savedFile)) {
            fos.write(fileData);
        }

        // Extract text
        List<String> pages;
        switch (ext) {
            case "pptx": pages = extractPptx(fileData); break;
            case "docx": pages = extractDocx(fileData); break;
            case "pdf": pages = extractPdf(fileData); break;
            case "xlsx": case "xls": pages = extractExcel(fileData); break;
            case "txt": case "csv": pages = Arrays.asList(new String(fileData, StandardCharsets.UTF_8)); break;
            default: throw new IllegalArgumentException("Unsupported file type: " + ext);
        }

        // Create title from filename
        String title = filename.replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ");

        // Create chunks from pages/slides
        List<DocChunk> newChunks = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String pageText = pages.get(i).trim();
            if (pageText.isEmpty()) continue;

            // Split large pages into smaller chunks (~500 chars each)
            List<String> subChunks = splitIntoChunks(pageText, 500);
            for (String sub : subChunks) {
                DocChunk chunk = new DocChunk();
                chunk.docId = id;
                chunk.docTitle = title;
                chunk.text = sub;
                chunk.slideOrPage = i + 1;
                chunk.keywords = extractKeywords(sub);
                newChunks.add(chunk);
            }
        }

        // Create metadata
        DocMeta meta = new DocMeta();
        meta.id = id;
        meta.filename = filename;
        meta.title = title;
        meta.uploadedBy = uploadedBy;
        meta.uploadedAt = java.time.Instant.now().toString();
        meta.chunkCount = newChunks.size();
        meta.fileSize = fileData.length;

        // Add to in-memory index
        docs.add(meta);
        chunks.addAll(newChunks);

        // Save index to disk
        saveIndex();

        LOG.info("[HELP-DOCS] Indexed '" + filename + "': " + pages.size() + " pages, " + newChunks.size() + " chunks");
        return meta;
    }

    /**
     * Delete a document and its chunks.
     */
    public void deleteDoc(String docId) {
        docs.removeIf(d -> d.id.equals(docId));
        chunks.removeIf(c -> c.docId.equals(docId));
        // Delete the file
        File dir = new File(DOCS_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith(docId + "-"));
        if (files != null) {
            for (File f : files) f.delete();
        }
        saveIndex();
        LOG.info("[HELP-DOCS] Deleted doc " + docId);
    }

    // ── Text extraction ──

    private List<String> extractPptx(byte[] data) throws Exception {
        List<String> slides = new ArrayList<>();
        try (org.apache.poi.xslf.usermodel.XMLSlideShow pptx =
                new org.apache.poi.xslf.usermodel.XMLSlideShow(new ByteArrayInputStream(data))) {
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : pptx.getSlides()) {
                StringBuilder sb = new StringBuilder();
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        sb.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                slides.add(sb.toString());
            }
        }
        return slides;
    }

    private List<String> extractDocx(byte[] data) throws Exception {
        List<String> pages = new ArrayList<>();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isEmpty()) {
                    sb.append(text).append("\n");
                    // Split into pages roughly every 3000 chars
                    if (sb.length() > 3000) {
                        pages.add(sb.toString());
                        sb = new StringBuilder();
                    }
                }
            }
            // Also extract from tables
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (text != null && !text.isEmpty()) sb.append(text).append("\t");
                    }
                    sb.append("\n");
                }
            }
            if (sb.length() > 0) pages.add(sb.toString());
        }
        return pages;
    }

    private List<String> extractPdf(byte[] data) throws Exception {
        List<String> pages = new ArrayList<>();
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                org.apache.pdfbox.pdmodel.PDDocument.load(new ByteArrayInputStream(data))) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(stripper.getText(doc));
            }
        }
        return pages;
    }

    private List<String> extractExcel(byte[] data) throws Exception {
        List<String> sheets = new ArrayList<>();
        try (org.apache.poi.ss.usermodel.Workbook wb =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(data))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(s);
                StringBuilder sb = new StringBuilder();
                sb.append("Sheet: ").append(sheet.getSheetName()).append("\n");
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                        if (cell != null) {
                            try {
                                String val = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                                    ? String.valueOf(cell.getNumericCellValue())
                                    : cell.getStringCellValue();
                                if (val != null && !val.isEmpty()) sb.append(val).append("\t");
                            } catch (Exception ignored) {}
                        }
                    }
                    sb.append("\n");
                }
                sheets.add(sb.toString());
            }
        }
        return sheets;
    }

    // ── Helpers ──

    private List<String> splitIntoChunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.length() + para.length() > maxChars && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        if (chunks.isEmpty()) chunks.add(text);
        return chunks;
    }

    private String[] extractKeywords(String text) {
        // Simple keyword extraction: split, lowercase, deduplicate, filter short words
        Set<String> keywords = new LinkedHashSet<>();
        for (String word : text.toLowerCase().split("[\\s,;:.!?()\\[\\]{}\"']+")) {
            if (word.length() > 2) keywords.add(word);
        }
        return keywords.toArray(new String[0]);
    }

    // ── Persistence ──

    private void loadIndex() {
        File f = new File(INDEX_FILE);
        if (!f.exists()) return;
        try {
            Map<String, Object> data = mapper.readValue(f, new TypeReference<Map<String, Object>>() {});
            docs = mapper.convertValue(data.get("docs"), new TypeReference<List<DocMeta>>() {});
            chunks = mapper.convertValue(data.get("chunks"), new TypeReference<List<DocChunk>>() {});
            if (docs == null) docs = new ArrayList<>();
            if (chunks == null) chunks = new ArrayList<>();
            LOG.info("[HELP-DOCS] Loaded " + docs.size() + " docs, " + chunks.size() + " chunks");
        } catch (Exception e) {
            LOG.warning("[HELP-DOCS] Failed to load index: " + e.getMessage());
        }
    }

    private synchronized void saveIndex() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("docs", docs);
            data.put("chunks", chunks);
            File f = new File(INDEX_FILE);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (Exception e) {
            LOG.warning("[HELP-DOCS] Failed to save index: " + e.getMessage());
        }
    }
}
