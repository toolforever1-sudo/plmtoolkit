package com.sandisk.plm.tracker.util;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads only row 1 (header) and the first 3 data rows of each sheet in an Excel
 * workbook, without ever loading the full DOM. Heap usage stays roughly constant
 * regardless of how big the file is — a 72 MB BOM-Implosion export probes in ~50 MB
 * of heap instead of the ~700 MB an XSSFWorkbook DOM would need.
 *
 * <p>For .xlsx files this uses POI's event-model SAX reader. For .xls (legacy)
 * and .csv/.txt we fall back to {@link FileItemParser}-style logic since those
 * formats are usually small enough that DOM is fine.</p>
 */
public class StreamingExcelProbe {

    private static final Logger logger = Logger.getLogger(StreamingExcelProbe.class.getName());

    // Capture an extra row up front so we can shift past a banner/title in row 0
    // without losing samples. After the shift the caller still gets 3 samples.
    private static final int MAX_SAMPLE_ROWS = 4;
    private static final int MAX_COLS = 64;       // sanity cap — header rows wider than this are weird
    private static final int MAX_ROW_SCAN = 5;    // we only need rows 0..4

    static {
        // POI 5.x defaults to a 100 MB cap on individual record byte arrays. Real
        // BOM-Implosion exports have shared-strings tables larger than that. Bump to
        // 1 GB so the streaming probe can still open them. Same call is also issued
        // at app startup but we set it here too in case anything called us first.
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(1024 * 1024 * 1024);
    }

    public static class SheetProbe {
        public String sheetName;
        public List<String> headerRow = new ArrayList<>();
        public List<List<String>> sampleRows = new ArrayList<>();
        public int totalRows;            // total rows in the sheet (best-effort)
        /** 0-indexed. Usually 0; bumped to 1 (or 2) when row 0 is a title/banner
         *  with only a single non-empty cell and the next row looks like real headers. */
        public int headerRowIndex;
    }

    public static class WorkbookProbe {
        public List<SheetProbe> sheets = new ArrayList<>();
    }

    public WorkbookProbe probe(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null) name = "";
        String lower = name.toLowerCase();

        if (lower.endsWith(".xlsx")) {
            return probeXlsxStreaming(file);
        }
        // .xls and .csv/.txt: fall back to DOM (these are usually small).
        return probeWithDom(file);
    }

    /**
     * Streams every value in a single column of a single sheet, skipping the
     * header row. Heap usage scales with the size of the column (not the whole
     * sheet). Used by the upload path to extract item numbers from huge xlsx
     * files without ever building the DOM.
     *
     * @param file        the uploaded file
     * @param sheetName   pick the sheet by name (from a previous probe call)
     * @param colIndex    0-indexed column to read
     * @param headerRowIndex  0-indexed row to skip (everything before AND on this row is dropped)
     */
    public List<String> streamColumn(MultipartFile file, String sheetName, int colIndex, int headerRowIndex)
            throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             OPCPackage pkg = OPCPackage.open(is)) {
            XSSFReader reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            SharedStrings sst = reader.getSharedStringsTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (InputStream sheetStream = it.next()) {
                    if (sheetName != null && !sheetName.equals(it.getSheetName())) continue;
                    ColumnExtractor handler = new ColumnExtractor(sst, colIndex, headerRowIndex);
                    XMLReader xr = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
                    xr.setContentHandler(handler);
                    xr.parse(new InputSource(sheetStream));
                    out = handler.values;
                    return out;
                } catch (Exception e) {
                    throw new IOException("Failed to stream column " + colIndex + " from sheet " +
                            sheetName + ": " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to stream column from xlsx: " + e.getMessage(), e);
        }
        return out;
    }

    /** SAX-based probe for .xlsx — minimal heap. */
    private WorkbookProbe probeXlsxStreaming(MultipartFile file) throws IOException {
        WorkbookProbe out = new WorkbookProbe();
        try (InputStream is = file.getInputStream();
             OPCPackage pkg = OPCPackage.open(is)) {
            XSSFReader reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            SharedStrings sst;
            try {
                sst = reader.getSharedStringsTable();
            } catch (Exception e) {
                throw new IOException("Cannot read shared strings table: " + e.getMessage(), e);
            }

            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (InputStream sheetStream = it.next()) {
                    SheetProbe sp = new SheetProbe();
                    sp.sheetName = it.getSheetName();

                    SheetSamplingHandler handler = new SheetSamplingHandler(sst, MAX_SAMPLE_ROWS);
                    XMLReader xr = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
                    xr.setContentHandler(handler);
                    try {
                        xr.parse(new InputSource(sheetStream));
                    } catch (EarlyExit ignore) {
                        // Handler signals early exit once it has enough rows.
                    }

                    sp.headerRow = handler.headerRow;
                    sp.sampleRows = handler.sampleRows;
                    sp.totalRows = handler.totalRowsSeen;
                    sp.headerRowIndex = 0;
                    shiftPastBanner(sp);
                    out.sheets.add(sp);
                } catch (Exception e) {
                    // Skip unreadable sheets but don't fail the whole probe.
                    logger.log(Level.WARNING, "[PROBE] skipping sheet: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to probe xlsx: " + e.getMessage(), e);
        }
        return out;
    }

    /** DOM probe for .xls / small files. */
    private WorkbookProbe probeWithDom(MultipartFile file) throws IOException {
        WorkbookProbe out = new WorkbookProbe();
        try (InputStream is = file.getInputStream();
             Workbook wb = WorkbookFactory.create(is)) {
            DataFormatter fmt = new DataFormatter();
            int nSheets = wb.getNumberOfSheets();
            for (int s = 0; s < nSheets; s++) {
                Sheet sheet = wb.getSheetAt(s);
                if (sheet == null) continue;
                SheetProbe sp = new SheetProbe();
                sp.sheetName = sheet.getSheetName();
                sp.totalRows = sheet.getLastRowNum() + 1;
                int lastCol = 0;
                for (int r = 0; r <= Math.min(MAX_ROW_SCAN, sheet.getLastRowNum()); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) continue;
                    lastCol = Math.max(lastCol, Math.min(MAX_COLS, row.getLastCellNum()));
                }
                org.apache.poi.ss.usermodel.Row hdr = sheet.getRow(0);
                List<String> headerRow = new ArrayList<>();
                for (int c = 0; c < lastCol; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = hdr == null ? null
                            : hdr.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    headerRow.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                }
                sp.headerRow = headerRow;
                for (int r = 1; r <= Math.min(MAX_SAMPLE_ROWS, sheet.getLastRowNum()); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) { sp.sampleRows.add(new ArrayList<>()); continue; }
                    List<String> samp = new ArrayList<>();
                    for (int c = 0; c < lastCol; c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c,
                                org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        samp.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                    }
                    sp.sampleRows.add(samp);
                }
                sp.headerRowIndex = 0;
                shiftPastBanner(sp);
                out.sheets.add(sp);
            }
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new IOException("Cannot read encrypted Excel file.");
        }
        return out;
    }

    /**
     * If row 0 is a banner / merged title (only one non-empty cell, no priority
     * keywords) and row 1 looks like real headers (≥3 non-empty cells), shift
     * down by one row. Repeat once more in case row 1 is also a sub-banner.
     * BOM-Implosion exports do this — first row is "BOM Implosion (43 items)",
     * actual headers ("Level", "Item Number", ...) live on row 2.
     */
    private static void shiftPastBanner(SheetProbe sp) {
        for (int attempt = 0; attempt < 2; attempt++) {
            int nonEmpty = 0;
            for (String h : sp.headerRow) if (h != null && !h.trim().isEmpty()) nonEmpty++;
            // Already looks like a real header row.
            if (nonEmpty >= 2) return;
            // Need at least one sample row to promote.
            if (sp.sampleRows.isEmpty()) return;
            List<String> next = sp.sampleRows.get(0);
            int nextNonEmpty = 0;
            for (String h : next) if (h != null && !h.trim().isEmpty()) nextNonEmpty++;
            if (nextNonEmpty < 3) return;
            // Promote sampleRows[0] to header.
            sp.headerRow = next;
            sp.sampleRows.remove(0);
            sp.headerRowIndex++;
        }
    }

    /**
     * Picks the "best" sheet from a multi-sheet workbook using the same
     * priority-keyword scoring that {@link FileItemParser} uses. Returns the
     * index into {@code probe.sheets}.
     */
    public static int pickBestSheet(WorkbookProbe probe) {
        if (probe.sheets.isEmpty()) return 0;
        if (probe.sheets.size() == 1) return 0;
        List<String> keywords = UploadColumnDetector.priorityKeywords();
        int bestPriority = Integer.MAX_VALUE;
        int bestIdx = 0;
        boolean anyMatch = false;
        for (int s = 0; s < probe.sheets.size(); s++) {
            SheetProbe sp = probe.sheets.get(s);
            for (String h : sp.headerRow) {
                String norm = UploadColumnDetector.normalizeKey(h == null ? "" : h.trim());
                if (norm.isEmpty()) continue;
                int p = keywords.indexOf(norm);
                if (p >= 0 && p < bestPriority) {
                    bestPriority = p;
                    bestIdx = s;
                    anyMatch = true;
                }
            }
        }
        return anyMatch ? bestIdx : 0;
    }

    /** Marker exception used by the SAX handler to abort parsing once it has enough rows. */
    private static class EarlyExit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * SAX handler that extracts a single column of values from a sheet, skipping
     * everything up to and including {@code skipRowsThrough}. Caps individual
     * cell values at 200 chars (matches the legacy parser).
     */
    private static class ColumnExtractor extends DefaultHandler {
        private final SharedStrings sst;
        private final int wantedCol;
        private final int skipRowsThrough;  // 0-indexed; all rows ≤ this are dropped

        public List<String> values = new ArrayList<>();

        private int currentRow = -1;
        private int currentCol = -1;
        private String cellType = null;
        private StringBuilder cellValue = new StringBuilder();
        private String capturedThisRow = null;

        ColumnExtractor(SharedStrings sst, int wantedCol, int skipRowsThrough) {
            this.sst = sst;
            this.wantedCol = wantedCol;
            this.skipRowsThrough = skipRowsThrough;
        }

        @Override
        public void startElement(String uri, String localName, String name, Attributes atts) {
            if ("row".equals(name)) {
                String r = atts.getValue("r");
                currentRow = r == null ? currentRow + 1 : Integer.parseInt(r) - 1;
                capturedThisRow = null;
            } else if ("c".equals(name)) {
                String ref = atts.getValue("r");
                currentCol = ref == null ? currentCol + 1 : columnIndexFromRef(ref);
                cellType = atts.getValue("t");
                cellValue.setLength(0);
            } else if ("v".equals(name) || "t".equals(name)) {
                cellValue.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            cellValue.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            if ("v".equals(name) || ("t".equals(name) && currentCol >= 0)) {
                if (currentRow > skipRowsThrough && currentCol == wantedCol) {
                    String raw = cellValue.toString();
                    String resolved = raw;
                    if ("s".equals(cellType)) {
                        try {
                            int idx = Integer.parseInt(raw.trim());
                            RichTextString rts = sst.getItemAt(idx);
                            resolved = rts == null ? "" : rts.getString();
                        } catch (NumberFormatException ignore) {}
                    } else if ("b".equals(cellType)) {
                        resolved = "1".equals(raw.trim()) ? "TRUE" : "FALSE";
                    }
                    String trimmed = resolved == null ? "" : resolved.trim();
                    if (!trimmed.isEmpty() && trimmed.length() <= 200) {
                        capturedThisRow = trimmed;
                    }
                }
            } else if ("c".equals(name)) {
                cellType = null;
            } else if ("row".equals(name)) {
                if (currentRow > skipRowsThrough && capturedThisRow != null) {
                    values.add(capturedThisRow);
                }
            }
        }

        private static int columnIndexFromRef(String ref) {
            int idx = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch < 'A' || ch > 'Z') break;
                idx = idx * 26 + (ch - 'A' + 1);
            }
            return idx - 1;
        }
    }

    /**
     * SAX handler that collects row 0 (headers) and the first {@code maxSamples}
     * data rows, then throws {@link EarlyExit} to short-circuit the parser.
     */
    private static class SheetSamplingHandler extends DefaultHandler {
        private final SharedStrings sst;
        private final int maxSamples;

        public List<String> headerRow = new ArrayList<>();
        public List<List<String>> sampleRows = new ArrayList<>();
        public int totalRowsSeen = 0;

        // Per-cell parsing state.
        private int currentRow = -1;
        private int currentCol = -1;
        private String cellType = null;
        private String cellStyle = null;
        private StringBuilder cellValue = new StringBuilder();
        private List<String> currentRowValues = new ArrayList<>();
        private int rowMaxCol = -1;

        SheetSamplingHandler(SharedStrings sst, int maxSamples) {
            this.sst = sst;
            this.maxSamples = maxSamples;
        }

        @Override
        public void startElement(String uri, String localName, String name, Attributes atts) {
            if ("dimension".equals(name)) {
                // <dimension ref="A1:F2800"/> — pulled before any rows. Gives us the
                // total row count without scanning. Most xlsx writers emit it; some don't.
                String ref = atts.getValue("ref");
                if (ref != null && ref.contains(":")) {
                    String tail = ref.substring(ref.indexOf(':') + 1);
                    int i = 0;
                    while (i < tail.length() && Character.isLetter(tail.charAt(i))) i++;
                    try {
                        int last = Integer.parseInt(tail.substring(i));
                        if (last > totalRowsSeen) totalRowsSeen = last;
                    } catch (NumberFormatException ignore) {}
                }
            } else if ("row".equals(name)) {
                String r = atts.getValue("r");
                currentRow = r == null ? currentRow + 1 : Integer.parseInt(r) - 1;
                currentRowValues = new ArrayList<>();
                rowMaxCol = -1;
            } else if ("c".equals(name)) {
                String ref = atts.getValue("r");
                currentCol = ref == null ? currentCol + 1 : columnIndexFromRef(ref);
                cellType = atts.getValue("t");
                cellStyle = atts.getValue("s");
                cellValue.setLength(0);
                // Pad row up to currentCol with empty strings so column indices stay aligned.
                while (currentRowValues.size() <= currentCol) currentRowValues.add("");
                rowMaxCol = Math.max(rowMaxCol, currentCol);
            } else if ("v".equals(name) || "t".equals(name)) {
                cellValue.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            cellValue.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            if ("v".equals(name) || ("t".equals(name) && currentCol >= 0)) {
                String raw = cellValue.toString();
                String resolved = raw;
                if ("s".equals(cellType)) {
                    // Shared string index.
                    try {
                        int idx = Integer.parseInt(raw.trim());
                        RichTextString rts = sst.getItemAt(idx);
                        resolved = rts == null ? "" : rts.getString();
                    } catch (NumberFormatException ignore) { /* leave raw */ }
                } else if ("inlineStr".equals(cellType)) {
                    resolved = raw;
                } else if ("b".equals(cellType)) {
                    resolved = "1".equals(raw.trim()) ? "TRUE" : "FALSE";
                }
                if (currentCol >= 0 && currentCol < MAX_COLS) {
                    if (currentRowValues.size() <= currentCol) {
                        while (currentRowValues.size() <= currentCol) currentRowValues.add("");
                    }
                    currentRowValues.set(currentCol, resolved == null ? "" : resolved.trim());
                }
            } else if ("c".equals(name)) {
                cellType = null;
                cellStyle = null;
            } else if ("row".equals(name)) {
                if (currentRow == 0) {
                    headerRow = new ArrayList<>(currentRowValues);
                } else if (sampleRows.size() < maxSamples) {
                    sampleRows.add(new ArrayList<>(currentRowValues));
                }
                if (currentRow > maxSamples) {
                    // Got enough — bail. totalRowsSeen was set from <dimension> at sheet start
                    // (most writers emit it). If absent, callers will see the lower bound.
                    throw new EarlyExit();
                }
            }
        }

        /** "B7" → 1; "AA12" → 26. */
        private static int columnIndexFromRef(String ref) {
            int idx = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch < 'A' || ch > 'Z') break;
                idx = idx * 26 + (ch - 'A' + 1);
            }
            return idx - 1;
        }
    }

    /** 0-indexed → "A", "B", … "AA", … */
    public static String columnLetter(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = (n / 26) - 1;
        }
        return sb.toString();
    }

}
