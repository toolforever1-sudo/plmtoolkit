package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.util.HeapGuard;
import com.sandisk.plm.tracker.util.StreamingExcelProbe;
import com.sandisk.plm.tracker.util.UploadColumnDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Pre-flight probe used by the smart-upload front-end. Given an uploaded file,
 * runs three checks and returns one of:
 *
 * <ul>
 *   <li><strong>{tooLarge: true, message}</strong> — heap-guard says the JVM cannot
 *       safely process this file. Friendly business-language message included.</li>
 *   <li><strong>{confident: true, column, totalRows, sourceSheet}</strong> — the
 *       detector found the item-number column with high confidence (header match
 *       or AI fallback). Front-end should proceed straight to the real upload
 *       endpoint, passing {@code itemColumn=N} so the column is locked in.</li>
 *   <li><strong>{confident: false, columns: [{index,letter,header,sample}…],
 *       sourceSheet, totalRows}</strong> — the detector wasn't sure. Front-end
 *       opens the column-picker modal so the user can choose which column holds
 *       the item number.</li>
 * </ul>
 *
 * <p>Heap-side: the probe uses a SAX streaming reader, so a 72 MB BOM-Implosion
 * export is probed in roughly 50 MB of heap. The "real" upload that follows still
 * has to read the whole file, but that's protected by its own heap-guard call.</p>
 */
@RestController
@RequestMapping("/api/upload")
public class UploadProbeController {

    @Autowired private UploadColumnDetector columnDetector;
    @Autowired private HeapGuard heapGuard;

    @PostMapping("/probe")
    public Map<String, Object> probe(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "op", defaultValue = "QUERY") String op)
            throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();

        if (file == null || file.isEmpty()) {
            resp.put("error", "No file uploaded.");
            return resp;
        }

        // Heap pre-flight. Use the operation hint the caller passed in (QUERY by default;
        // ENRICH if they're heading to one of the enrich endpoints).
        HeapGuard.Op gOp;
        try { gOp = HeapGuard.Op.valueOf(op.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { gOp = HeapGuard.Op.QUERY; }
        HeapGuard.Verdict v = heapGuard.check(file.getSize(), gOp);
        if (!v.ok) {
            resp.put("tooLarge", true);
            resp.put("message", v.message);
            resp.put("fileSizeBytes", v.fileSize);
            resp.put("operation", gOp.name());
            return resp;
        }

        // Streaming probe.
        StreamingExcelProbe probe = new StreamingExcelProbe();
        StreamingExcelProbe.WorkbookProbe wp;
        try {
            wp = probe.probe(file);
        } catch (IOException e) {
            resp.put("error", "Could not read this file: " + e.getMessage());
            return resp;
        }

        if (wp.sheets.isEmpty()) {
            // Probably a .txt / .csv — single column always, no detection needed.
            resp.put("confident", true);
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("index", 0);
            col.put("letter", "A");
            col.put("header", "");
            col.put("method", "text");
            resp.put("column", col);
            resp.put("totalRows", -1);  // unknown without scanning
            return resp;
        }

        int sheetIdx = StreamingExcelProbe.pickBestSheet(wp);
        StreamingExcelProbe.SheetProbe sp = wp.sheets.get(sheetIdx);
        resp.put("sourceSheet", sp.sheetName);
        resp.put("totalRows", sp.totalRows);  // best-effort from <dimension>
        resp.put("headerRowIndex", sp.headerRowIndex);

        // Row-count gate. The size-based HeapGuard estimate doesn't capture
        // POI XSSF DOM's per-cell overhead, so a 72 MB / 825K-row workbook
        // can pass the size check (864 MB estimated need) but actually need
        // 2-3 GB at runtime and OOM the JVM. Cap rows by op so the JS rejects
        // these before sending the actual upload.
        if (sp.totalRows > gOp.maxRows) {
            resp.clear();
            resp.put("tooLarge", true);
            String msg = "This file has about " + sp.totalRows + " rows, but " + gOp.label
                    + " is capped at " + gOp.maxRows + " rows to protect server memory. "
                    + "Please split the file into smaller chunks (or use the database query if you don't need a workbook export).";
            resp.put("message", msg);
            resp.put("fileSizeBytes", file.getSize());
            resp.put("operation", gOp.name());
            resp.put("totalRows", sp.totalRows);
            return resp;
        }

        // Run the same detector the upload endpoint will run. confident iff the detector
        // says header-match OR ai-fallback. default-col-a means we should ask the user.
        UploadColumnDetector.DetectionResult det = columnDetector.detect(sp.headerRow, sp.sampleRows);
        boolean confident = "header-match".equals(det.method) || "ai-fallback".equals(det.method);

        if (confident) {
            resp.put("confident", true);
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("index", det.column);
            col.put("letter", StreamingExcelProbe.columnLetter(det.column));
            col.put("header", det.header);
            col.put("method", det.method);
            resp.put("column", col);
            return resp;
        }

        // Not confident — surface every column with sample values so the user can pick.
        resp.put("confident", false);
        // Best-guess defaults to col 0 if nothing was matched; the modal will show this preselected.
        resp.put("suggestedColumn", det.column);
        resp.put("suggestedMethod", det.method);

        List<Map<String, Object>> cols = new ArrayList<>();
        int colCount = sp.headerRow.size();
        for (int c = 0; c < colCount; c++) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("index", c);
            col.put("letter", StreamingExcelProbe.columnLetter(c));
            col.put("header", sp.headerRow.get(c));
            List<String> sample = new ArrayList<>();
            for (List<String> row : sp.sampleRows) {
                sample.add(c < row.size() ? row.get(c) : "");
            }
            col.put("sample", sample);
            cols.add(col);
        }
        resp.put("columns", cols);
        return resp;
    }
}
