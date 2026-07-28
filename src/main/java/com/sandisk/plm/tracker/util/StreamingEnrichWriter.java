package com.sandisk.plm.tracker.util;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streaming (SAX read → SXSSF write) equivalent of the Change-History enrich workbook
 * rewrite, for files too large to hold as an XSSF DOM. Reads the input sheet row by row
 * and writes the output row by row, inserting 4 columns right after the item-number
 * column — so heap stays roughly flat (a few hundred MB) regardless of row count, instead
 * of the ~2.4 GB DOM a 48K-row file needs (PT-124).
 *
 * <p><strong>Preserved:</strong> cell values with their types (numbers stay numbers,
 * dates stay dates), per-cell styles (the shared styles table is cloned into the output
 * once and referenced by index), and column widths.</p>
 *
 * <p><strong>Not preserved</strong> (they live in sheet-level XML a streaming writer has
 * already flushed past): merged regions, conditional formatting, charts/images, data
 * validation. For a tabular data export this is an acceptable trade for not OOM-ing the
 * server; small files still go through the full-fidelity DOM path.</p>
 */
public class StreamingEnrichWriter {

    /** Supplies the 4 inserted values for a given item number, or {@code null} for no match. */
    public interface RowEnricher {
        /** @return {@code [lifecyclePhase, releaseDate("MM-dd-yyyy hh:mm:ss a"), changeNumber, changeType]} or null. */
        String[] valuesFor(String itemNumber);
    }

    private StreamingEnrichWriter() {}

    static {
        // Match StreamingExcelProbe: big shared-strings tables exceed POI's default 100 MB cap.
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(1024 * 1024 * 1024);
    }

    /**
     * @param file            uploaded .xlsx
     * @param sourceColumn    0-indexed item-number column; 4 columns are inserted right after it
     * @param headerRowIndex  0-indexed header row (gets the 4 header labels)
     * @param newHeaders      the 4 header labels
     * @param enricher        item-number → 4 values (or null)
     * @param out             destination stream (the HTTP response)
     * @return number of data rows that matched a history record
     */
    public static int write(MultipartFile file, int sourceColumn, int headerRowIndex,
                            String[] newHeaders, RowEnricher enricher, OutputStream out) throws IOException {
        try (InputStream is = file.getInputStream();
             OPCPackage pkg = OPCPackage.open(is);
             SXSSFWorkbook wb = new SXSSFWorkbook(null, 100, true, false)) {  // window=100, compress tmp, inline strings
            XSSFReader reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            SharedStrings sst = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!it.hasNext()) throw new IOException("workbook has no sheets");
            try (InputStream sheetStream = it.next()) {
                Sheet outSheet = wb.createSheet(safeName(it.getSheetName()));
                RowWriter handler = new RowWriter(wb, outSheet, sst, styles,
                        sourceColumn, headerRowIndex, newHeaders, enricher);
                XMLReader xr = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
                xr.setContentHandler(handler);
                xr.parse(new InputSource(sheetStream));
                handler.applyColumnWidths();
                wb.write(out);
                return handler.matched;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("streaming enrich failed: " + e.getMessage(), e);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("streaming enrich failed to open workbook: " + e.getMessage(), e);
        }
    }

    private static String safeName(String n) {
        if (n == null || n.trim().isEmpty()) return "Sheet1";
        String s = n.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    /** SAX handler that rewrites each input row into the SXSSF output, shifting cells past
     *  {@code sourceColumn} right by 4 and filling the inserted columns. */
    private static final class RowWriter extends DefaultHandler {
        private final SXSSFWorkbook wb;
        private final Sheet outSheet;
        private final SharedStrings sst;
        private final StylesTable styles;
        private final int sourceColumn;
        private final int headerRowIndex;
        private final String[] newHeaders;
        private final RowEnricher enricher;
        private static final int NEW = 4;

        private final Map<Short, CellStyle> styleCache = new HashMap<>();
        private final CellStyle dateStyle;
        private final Map<Integer, Integer> colWidths = new TreeMap<>();  // input col → width (1/256 char)
        int matched = 0;

        // per-row state
        private int currentRow = -1;
        private final TreeMap<Integer, CellData> rowCells = new TreeMap<>();
        // per-cell state
        private int curCol = -1;
        private String curType;
        private String curStyleIdx;
        private final StringBuilder val = new StringBuilder();
        private boolean inV;

        RowWriter(SXSSFWorkbook wb, Sheet outSheet, SharedStrings sst, StylesTable styles,
                  int sourceColumn, int headerRowIndex, String[] newHeaders, RowEnricher enricher) {
            this.wb = wb; this.outSheet = outSheet; this.sst = sst; this.styles = styles;
            this.sourceColumn = sourceColumn; this.headerRowIndex = headerRowIndex;
            this.newHeaders = newHeaders; this.enricher = enricher;
            this.dateStyle = wb.createCellStyle();
            this.dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        }

        private static final class CellData {
            String type; String styleIdx; String value;
            CellData(String t, String s, String v) { type = t; styleIdx = s; value = v; }
        }

        @Override
        public void startElement(String uri, String localName, String name, Attributes atts) {
            switch (name) {
                case "col": {
                    // <col min="1" max="3" width="10.5" customWidth="1"/> — 1-based inclusive range.
                    String w = atts.getValue("width");
                    if (w != null) {
                        try {
                            int width = (int) Math.round(Double.parseDouble(w) * 256);
                            int min = intAttr(atts, "min", 1), max = intAttr(atts, "max", min);
                            for (int c = min; c <= max && c - 1 < 16384; c++) colWidths.put(c - 1, width);
                        } catch (NumberFormatException ignore) {}
                    }
                    break;
                }
                case "row":
                    currentRow = intAttr(atts, "r", currentRow + 2) - 1;
                    rowCells.clear();
                    break;
                case "c":
                    curCol = colFromRef(atts.getValue("r"), curCol + 1);
                    curType = atts.getValue("t");
                    curStyleIdx = atts.getValue("s");
                    val.setLength(0);
                    break;
                case "v":
                case "t":
                    inV = true;
                    val.setLength(0);
                    break;
                default:
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inV) val.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            switch (name) {
                case "v":
                case "t":
                    inV = false;
                    if (curCol >= 0) rowCells.put(curCol, new CellData(curType, curStyleIdx, val.toString()));
                    break;
                case "c":
                    // self-closed / empty cell still carries a style (e.g. formatted blank) — keep it
                    if (curCol >= 0 && !rowCells.containsKey(curCol) && curStyleIdx != null) {
                        rowCells.put(curCol, new CellData(curType, curStyleIdx, ""));
                    }
                    curType = null; curStyleIdx = null;
                    break;
                case "row":
                    writeRow();
                    break;
                default:
            }
        }

        private void writeRow() {
            if (currentRow < 0) return;
            Row outRow = outSheet.createRow(currentRow);

            // 1. copy input cells, shifting anything past the source column right by NEW.
            String itemValue = "";
            for (Map.Entry<Integer, CellData> e : rowCells.entrySet()) {
                int inCol = e.getKey();
                CellData cd = e.getValue();
                String resolved = resolve(cd);
                if (inCol == sourceColumn) itemValue = resolved.trim();
                int outCol = inCol <= sourceColumn ? inCol : inCol + NEW;
                writeTypedCell(outRow, outCol, cd, resolved);
            }

            // 2. fill the 4 inserted columns.
            if (currentRow == headerRowIndex) {
                CellData hdr = rowCells.get(sourceColumn);
                for (int i = 0; i < NEW; i++) {
                    Cell c = outRow.createCell(sourceColumn + 1 + i);
                    c.setCellValue(newHeaders[i]);
                    if (hdr != null && hdr.styleIdx != null) c.setCellStyle(styleFor(hdr.styleIdx));
                }
            } else if (currentRow > headerRowIndex && !itemValue.isEmpty()) {
                String[] v = enricher.valuesFor(itemValue);
                if (v != null) {
                    matched++;
                    setStr (outRow, sourceColumn + 1, v[0]);
                    setDate(outRow, sourceColumn + 2, v[1]);
                    setStr (outRow, sourceColumn + 3, v[2]);
                    setStr (outRow, sourceColumn + 4, v[3]);
                }
                // no match → leave the 4 cells blank
            }
        }

        /** SAX-resolve a cell to its display string (shared strings, booleans). */
        private String resolve(CellData cd) {
            String raw = cd.value == null ? "" : cd.value;
            if ("s".equals(cd.type)) {
                try { return sst.getItemAt(Integer.parseInt(raw.trim())).getString(); }
                catch (Exception ignore) { return ""; }
            }
            if ("b".equals(cd.type)) return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
            return raw;
        }

        /** Write a copied input cell preserving its type + style. */
        private void writeTypedCell(Row outRow, int outCol, CellData cd, String resolved) {
            Cell c = outRow.createCell(outCol);
            String t = cd.type;
            if (t == null || "n".equals(t)) {
                // numeric (styles carry date/number formatting)
                try { c.setCellValue(Double.parseDouble(cd.value == null ? "" : cd.value.trim())); }
                catch (NumberFormatException e) { if (!resolved.isEmpty()) c.setCellValue(resolved); }
            } else if ("b".equals(t)) {
                c.setCellValue("1".equals((cd.value == null ? "" : cd.value).trim()));
            } else {
                // s / inlineStr / str / e → string
                c.setCellValue(resolved);
            }
            if (cd.styleIdx != null) c.setCellStyle(styleFor(cd.styleIdx));
        }

        /** Clone the input's cell style (by index) into the output workbook once, cached. */
        private CellStyle styleFor(String idxStr) {
            try {
                short idx = Short.parseShort(idxStr.trim());
                CellStyle cached = styleCache.get(idx);
                if (cached != null) return cached;
                CellStyle out = wb.createCellStyle();
                XSSFCellStyle src = styles.getStyleAt(idx);
                if (src != null) out.cloneStyleFrom(src);
                styleCache.put(idx, out);
                return out;
            } catch (Exception e) {
                return null;
            }
        }

        private void setStr(Row row, int col, String value) {
            row.createCell(col).setCellValue(value == null ? "" : value);
        }

        private void setDate(Row row, int col, String value) {
            if (value == null || value.isEmpty()) return;
            Cell c = row.createCell(col);
            try {
                Date d = new SimpleDateFormat("MM-dd-yyyy hh:mm:ss a").parse(value);
                c.setCellValue(d);
                c.setCellStyle(dateStyle);
            } catch (ParseException e) {
                c.setCellValue(value);
            }
        }

        void applyColumnWidths() {
            for (Map.Entry<Integer, Integer> e : colWidths.entrySet()) {
                int inCol = e.getKey();
                int outCol = inCol <= sourceColumn ? inCol : inCol + NEW;
                try { outSheet.setColumnWidth(outCol, Math.min(e.getValue(), 255 * 256)); }
                catch (IllegalArgumentException ignore) {}
            }
        }

        private static int intAttr(Attributes a, String n, int dflt) {
            String v = a.getValue(n);
            if (v == null) return dflt;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return dflt; }
        }

        private static int colFromRef(String ref, int dflt) {
            if (ref == null) return dflt;
            int idx = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch < 'A' || ch > 'Z') break;
                idx = idx * 26 + (ch - 'A' + 1);
            }
            return idx - 1;
        }
    }
}
