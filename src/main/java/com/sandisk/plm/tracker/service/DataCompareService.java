package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class DataCompareService {

    private static final Logger logger = Logger.getLogger(DataCompareService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_FILTERED_ROWS = 500;
    private static final int BATCH_SIZE = 100;
    private static final long SESSION_TTL_MS = 30 * 60 * 1000; // 30 minutes

    private final ConcurrentHashMap<String, CompareSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Autowired
    private AgileFieldNameService agileFieldNameService;

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    // ---- Session data classes ----

    public static class CompareSession {
        public String id;
        public long lastAccess;
        public List<String> headers;
        public List<Map<String, String>> allRows;
        public List<Map<String, String>> filteredRows;
        public String partNumberColumn;
        public List<FieldMapping> mappings;
        public String status = "idle"; // idle, running, completed, failed
        public List<Map<String, Object>> results;
        public int progress;
        public int total;
        public String errorMessage;
    }

    public static class FieldMapping {
        public String excelColumn;
        public String agileField;   // null if action is "include" or "skip"
        public String action;        // "compare", "include", "skip"
    }

    public static class MappingSuggestion {
        public String excelColumn;
        public String suggestedAgileField;
        public String confidence; // "high", "medium", "none"
        public List<String> alternatives;

        public MappingSuggestion(String excelColumn, String suggestedAgileField,
                                 String confidence, List<String> alternatives) {
            this.excelColumn = excelColumn;
            this.suggestedAgileField = suggestedAgileField;
            this.confidence = confidence;
            this.alternatives = alternatives;
        }
    }

    // ---- Upload ----

    public Map<String, Object> handleUpload(InputStream inputStream, String filename, long fileSize) throws Exception {
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large (max 10MB). Size: " + (fileSize / 1024 / 1024) + "MB");
        }

        String lowerName = filename.toLowerCase();
        boolean isCsv = lowerName.endsWith(".csv");
        boolean isExcel = lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls");

        if (!isCsv && !isExcel) {
            throw new IllegalArgumentException("Unsupported file type. Please upload .xlsx, .xls, or .csv");
        }

        List<String> headers;
        List<Map<String, String>> allRows;

        if (isCsv) {
            Object[] parsed = parseCsv(inputStream);
            headers = (List<String>) parsed[0];
            allRows = (List<Map<String, String>>) parsed[1];
        } else {
            Object[] parsed = parseExcel(inputStream);
            headers = (List<String>) parsed[0];
            allRows = (List<Map<String, String>>) parsed[1];
        }

        // Create session
        CompareSession session = new CompareSession();
        session.id = UUID.randomUUID().toString();
        session.lastAccess = System.currentTimeMillis();
        session.headers = headers;
        session.allRows = allRows;
        session.filteredRows = allRows; // initially no filter

        sessions.put(session.id, session);

        // Build preview (first 5 rows)
        List<Map<String, String>> preview = allRows.subList(0, Math.min(5, allRows.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.id);
        result.put("headers", headers);
        result.put("preview", preview);
        result.put("totalRows", allRows.size());
        result.put("columnCount", headers.size());
        result.put("fileName", filename);

        logger.info("[DATA COMPARE] Uploaded " + filename + ": " + allRows.size() + " rows, "
                + headers.size() + " columns, session=" + session.id);
        return result;
    }

    // ---- Filter ----

    public Map<String, Object> applyFilter(String sessionId, String filterColumn, String filterValue) {
        CompareSession session = getSession(sessionId);

        if (filterColumn == null || filterColumn.isEmpty() || filterValue == null || filterValue.isEmpty()) {
            // No filter — use all rows
            session.filteredRows = session.allRows;
        } else {
            String lowerValue = filterValue.trim().toLowerCase();
            session.filteredRows = session.allRows.stream()
                    .filter(row -> {
                        String cellVal = row.getOrDefault(filterColumn, "");
                        return cellVal.toLowerCase().contains(lowerValue);
                    })
                    .collect(Collectors.toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filteredCount", session.filteredRows.size());
        result.put("totalCount", session.allRows.size());

        if (session.filteredRows.size() > MAX_FILTERED_ROWS) {
            result.put("warning", "Filtered set has " + session.filteredRows.size()
                    + " rows. Maximum is " + MAX_FILTERED_ROWS
                    + ". Only the first " + MAX_FILTERED_ROWS + " will be compared.");
        }

        return result;
    }

    // ---- Suggest Mappings ----

    public List<MappingSuggestion> suggestMappings(String sessionId) {
        CompareSession session = getSession(sessionId);

        List<String> agileNames = agileFieldNameService.getFieldNames().stream()
                .map(m -> m.get("name"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<MappingSuggestion> suggestions = new ArrayList<>();
        for (String header : session.headers) {
            suggestions.add(findBestMatch(header, agileNames));
        }
        return suggestions;
    }

    // ---- Run Comparison (async) ----

    public void runComparison(String sessionId, String partNumberColumn, List<FieldMapping> mappings) {
        CompareSession session = getSession(sessionId);

        if ("running".equals(session.status)) {
            throw new IllegalStateException("Comparison already running for this session.");
        }

        session.partNumberColumn = partNumberColumn;
        session.mappings = mappings;
        session.status = "running";
        session.progress = 0;
        session.results = null;
        session.errorMessage = null;

        // Cap filtered rows
        List<Map<String, String>> rowsToProcess = session.filteredRows;
        if (rowsToProcess.size() > MAX_FILTERED_ROWS) {
            rowsToProcess = rowsToProcess.subList(0, MAX_FILTERED_ROWS);
        }
        session.total = rowsToProcess.size();

        final List<Map<String, String>> finalRows = new ArrayList<>(rowsToProcess);

        executor.submit(() -> {
            try {
                executeComparison(session, finalRows);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[DATA COMPARE] Comparison failed for session " + sessionId, e);
                session.status = "failed";
                session.errorMessage = e.getMessage();
            }
        });
    }

    // ---- Status ----

    public Map<String, Object> getStatus(String sessionId) {
        CompareSession session = getSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", session.status);
        result.put("progress", session.progress);
        result.put("total", session.total);
        if (session.errorMessage != null) {
            result.put("error", session.errorMessage);
        }
        return result;
    }

    // ---- Results ----

    public Map<String, Object> getResults(String sessionId) {
        CompareSession session = getSession(sessionId);
        if (!"completed".equals(session.status)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", session.status);
            result.put("error", "Comparison not yet completed. Status: " + session.status);
            return result;
        }

        // Build summary
        int matchCount = 0, differCount = 0, notFoundCount = 0;
        for (Map<String, Object> row : session.results) {
            String rowStatus = (String) row.get("rowStatus");
            if ("match".equals(rowStatus)) matchCount++;
            else if ("notfound".equals(rowStatus)) notFoundCount++;
            else differCount++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("totalItems", session.results.size());
        result.put("matchCount", matchCount);
        result.put("differCount", differCount);
        result.put("notFoundCount", notFoundCount);
        result.put("mappings", session.mappings);
        result.put("partNumberColumn", session.partNumberColumn);
        result.put("results", session.results);
        return result;
    }

    // ---- Export Excel ----

    public void exportExcel(String sessionId, OutputStream out) throws IOException {
        CompareSession session = getSession(sessionId);
        if (!"completed".equals(session.status) || session.results == null) {
            throw new IllegalStateException("No completed results to export.");
        }

        // Inline strings — no shared-strings table (OOM-safety).
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, false, false)) {
            SXSSFSheet sheet = (SXSSFSheet) workbook.createSheet("Data Compare");

            // Styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle greenStyle = createCellStyle(workbook, new byte[]{(byte) 0xC6, (byte) 0xEF, (byte) 0xCE}); // green
            CellStyle redStyle = createCellStyle(workbook, new byte[]{(byte) 0xF4, (byte) 0xCC, (byte) 0xCC});   // red
            CellStyle amberStyle = createCellStyle(workbook, new byte[]{(byte) 0xFF, (byte) 0xF3, (byte) 0xCD}); // amber
            CellStyle defaultStyle = workbook.createCellStyle();

            // Build column list from mappings
            List<FieldMapping> compareMappings = new ArrayList<>();
            List<FieldMapping> includeMappings = new ArrayList<>();
            if (session.mappings != null) {
                for (FieldMapping m : session.mappings) {
                    if ("compare".equals(m.action)) compareMappings.add(m);
                    else if ("include".equals(m.action)) includeMappings.add(m);
                }
            }

            // Header row
            Row headerRow = sheet.createRow(0);
            int col = 0;
            Cell c = headerRow.createCell(col++);
            c.setCellValue("Part Number");
            c.setCellStyle(headerStyle);

            for (FieldMapping m : includeMappings) {
                c = headerRow.createCell(col++);
                c.setCellValue(m.excelColumn);
                c.setCellStyle(headerStyle);
            }

            for (FieldMapping m : compareMappings) {
                c = headerRow.createCell(col++);
                c.setCellValue(m.excelColumn + " (Excel)");
                c.setCellStyle(headerStyle);

                c = headerRow.createCell(col++);
                c.setCellValue(m.agileField + " (Agile)");
                c.setCellStyle(headerStyle);
            }

            c = headerRow.createCell(col);
            c.setCellValue("Status");
            c.setCellStyle(headerStyle);

            // Data rows
            int rowIdx = 1;
            for (Map<String, Object> rowData : session.results) {
                Row row = sheet.createRow(rowIdx++);
                int dc = 0;

                row.createCell(dc++).setCellValue(str(rowData.get("partNumber")));

                // Include columns
                Map<String, String> excelValues = (Map<String, String>) rowData.get("excelValues");
                for (FieldMapping m : includeMappings) {
                    row.createCell(dc++).setCellValue(
                            excelValues != null ? excelValues.getOrDefault(m.excelColumn, "") : "");
                }

                // Compare columns
                Map<String, String> agileValues = (Map<String, String>) rowData.get("agileValues");
                Map<String, String> statuses = (Map<String, String>) rowData.get("statuses");

                for (FieldMapping m : compareMappings) {
                    String exVal = excelValues != null ? excelValues.getOrDefault(m.excelColumn, "") : "";
                    String agVal = agileValues != null ? agileValues.getOrDefault(m.agileField, "") : "";
                    String cellStatus = statuses != null ? statuses.getOrDefault(m.agileField, "unknown") : "unknown";

                    CellStyle style;
                    switch (cellStatus) {
                        case "match":
                            style = greenStyle;
                            break;
                        case "differ":
                            style = redStyle;
                            break;
                        case "partial":
                            style = amberStyle;
                            break;
                        default:
                            style = defaultStyle;
                            break;
                    }

                    Cell exCell = row.createCell(dc++);
                    exCell.setCellValue(exVal);
                    exCell.setCellStyle(style);

                    Cell agCell = row.createCell(dc++);
                    agCell.setCellValue(agVal);
                    agCell.setCellStyle(style);
                }

                String rowStatus = str(rowData.get("rowStatus"));
                row.createCell(dc).setCellValue(
                        "match".equals(rowStatus) ? "Match" :
                        "notfound".equals(rowStatus) ? "Not Found" : "Differs");
            }

            workbook.write(out);
        }
    }

    // ---- Session cleanup ----

    @Scheduled(fixedRate = 300000) // every 5 minutes
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CompareSession> entry : sessions.entrySet()) {
            if (now - entry.getValue().lastAccess > SESSION_TTL_MS) {
                sessions.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            logger.info("[DATA COMPARE] Cleaned up " + removed + " expired session(s).");
        }
    }

    // ===================== Internal Methods =====================

    private CompareSession getSession(String sessionId) {
        CompareSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found or expired: " + sessionId);
        }
        session.lastAccess = System.currentTimeMillis();
        return session;
    }

    @SuppressWarnings("unchecked")
    private void executeComparison(CompareSession session, List<Map<String, String>> rows) throws Exception {
        // Collect fields to look up from "compare" mappings
        List<FieldMapping> compareMappings = new ArrayList<>();
        for (FieldMapping m : session.mappings) {
            if ("compare".equals(m.action) && m.agileField != null) {
                compareMappings.add(m);
            }
        }

        List<String> agileFields = compareMappings.stream()
                .map(m -> m.agileField)
                .collect(Collectors.toList());

        logger.info("[DATA COMPARE] Compare mappings: " + compareMappings.size() + ", Agile fields: " + agileFields);

        if (agileFields.isEmpty()) {
            logger.warning("[DATA COMPARE] No Agile fields to compare — all mappings may be 'include' or 'skip'");
            session.results = rows.stream().map(row -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("partNumber", row.getOrDefault(session.partNumberColumn, ""));
                r.put("excelValues", new LinkedHashMap<>(row));
                r.put("agileValues", new LinkedHashMap<>());
                r.put("statuses", new LinkedHashMap<>());
                r.put("rowStatus", "match");
                return r;
            }).collect(Collectors.toList());
            session.status = "completed";
            session.progress = session.total;
            return;
        }

        // Collect all part numbers
        List<String> partNumbers = rows.stream()
                .map(row -> row.getOrDefault(session.partNumberColumn, "").trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Batch lookup against Agile microservice
        logger.info("[DATA COMPARE] Looking up " + partNumbers.size() + " items against Agile for fields: " + agileFields);
        Map<String, Map<String, String>> agileData = new LinkedHashMap<>();

        for (int i = 0; i < partNumbers.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, partNumbers.size());
            List<String> batch = partNumbers.subList(i, end);

            try {
                logger.info("[DATA COMPARE] Calling Agile for batch " + (i/BATCH_SIZE+1) + ": " + batch.size() + " items");
                Map<String, Object> lookupResult = callAgileLookup(batch, agileFields);
                logger.info("[DATA COMPARE] Agile response keys: " + (lookupResult != null ? lookupResult.keySet() : "null"));
                List<Map<String, String>> results = (List<Map<String, String>>) lookupResult.get("results");
                if (results != null) {
                    if (!results.isEmpty()) {
                        logger.info("[DATA COMPARE] First result record keys: " + results.get(0).keySet());
                    }
                    for (Map<String, String> record : results) {
                        // Try common key names for item number
                        String itemNumber = record.get("ITEM_NUMBER");
                        if (itemNumber == null) itemNumber = record.get("Item Number");
                        if (itemNumber == null) itemNumber = record.get("item_number");
                        // Also try first column value
                        if (itemNumber == null && !record.isEmpty()) {
                            itemNumber = record.values().iterator().next();
                        }
                        if (itemNumber != null) {
                            agileData.put(itemNumber.trim(), record);
                        }
                    }
                    logger.info("[DATA COMPARE] Agile data loaded: " + agileData.size() + " items matched");
                }
            } catch (Exception e) {
                logger.warning("[DATA COMPARE] Batch lookup failed (items " + i + "-" + end + "): " + e.getMessage());
                // Continue with remaining batches
            }

            session.progress = end;
        }

        // Now compare
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String partNumber = row.getOrDefault(session.partNumberColumn, "").trim();
            Map<String, String> agileRecord = agileData.get(partNumber);

            Map<String, Object> resultRow = new LinkedHashMap<>();
            resultRow.put("partNumber", partNumber);

            // Excel values for all non-skipped columns
            Map<String, String> excelVals = new LinkedHashMap<>();
            for (FieldMapping m : session.mappings) {
                if (!"skip".equals(m.action)) {
                    excelVals.put(m.excelColumn, row.getOrDefault(m.excelColumn, ""));
                }
            }
            resultRow.put("excelValues", excelVals);

            if (agileRecord == null) {
                resultRow.put("agileValues", new LinkedHashMap<>());
                resultRow.put("statuses", new LinkedHashMap<>());
                resultRow.put("rowStatus", "notfound");
            } else {
                Map<String, String> agileVals = new LinkedHashMap<>();
                Map<String, String> statuses = new LinkedHashMap<>();
                boolean allMatch = true;

                for (FieldMapping m : compareMappings) {
                    String exVal = row.getOrDefault(m.excelColumn, "").trim();
                    String agVal = agileRecord.getOrDefault(m.agileField, "").trim();
                    agileVals.put(m.agileField, agVal);

                    String cellStatus;
                    if (exVal.isEmpty() && agVal.isEmpty()) {
                        cellStatus = "match";
                    } else if (exVal.isEmpty() || agVal.isEmpty()) {
                        cellStatus = "partial";
                        allMatch = false;
                    } else if (exVal.equalsIgnoreCase(agVal)) {
                        cellStatus = "match";
                    } else {
                        cellStatus = "differ";
                        allMatch = false;
                    }
                    statuses.put(m.agileField, cellStatus);
                }

                resultRow.put("agileValues", agileVals);
                resultRow.put("statuses", statuses);
                resultRow.put("rowStatus", allMatch ? "match" : "differ");
            }

            results.add(resultRow);
        }

        session.results = results;
        session.status = "completed";
        session.progress = session.total;
        logger.info("[DATA COMPARE] Comparison completed for session " + session.id
                + ": " + results.size() + " items");
    }

    /**
     * Calls the Agile microservice directly (same approach as AgileLookupController.manualLookup).
     * Builds an in-memory Excel with item numbers and field names, then POSTs it.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callAgileLookup(List<String> items, List<String> fields) throws Exception {
        // Build in-memory Excel workbook (streaming + inline strings for OOM-safety).
        byte[] excelBytes;
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 1000, false, false);
        try {
            Sheet sheet = workbook.createSheet("Lookup");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Item Number");
            for (int i = 0; i < fields.size(); i++) {
                headerRow.createCell(i + 1).setCellValue(fields.get(i));
            }
            for (int r = 0; r < items.size(); r++) {
                Row row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(items.get(r));
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            excelBytes = baos.toByteArray();
        } finally {
            workbook.dispose();
            workbook.close();
        }

        // POST to agile microservice (same as AgileLookupService.forwardLookup)
        String boundary = "----FormBoundary" + System.currentTimeMillis();
        URL url = new URL(agileServiceUrl + "/api/lookup/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(600000);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"compare-lookup.xlsx\"\r\n").getBytes());
            out.write("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".getBytes());
            out.write(excelBytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        int responseCode = conn.getResponseCode();
        InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readStream(is);
        conn.disconnect();

        if (responseCode != 200) {
            // Include the response body in the exception so the underlying SDK
            // error surfaces to the caller (mirrors AgileLookupService).
            String snippet = body == null ? "" : body.trim();
            if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "…";
            throw new RuntimeException("Agile service returned HTTP " + responseCode
                + (snippet.isEmpty() ? "" : ": " + snippet));
        }

        return mapper.readValue(body, Map.class);
    }

    // ---- Excel / CSV parsing ----

    @SuppressWarnings("unchecked")
    private Object[] parseExcel(InputStream inputStream) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIter = sheet.iterator();

            if (!rowIter.hasNext()) {
                throw new IllegalArgumentException("Excel file is empty.");
            }

            // Read headers from first row
            Row headerRow = rowIter.next();
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                String val = getCellStringValue(cell);
                headers.add(val.isEmpty() ? "Column " + (i + 1) : val);
            }

            // Read data rows
            List<Map<String, String>> allRows = new ArrayList<>();
            while (rowIter.hasNext()) {
                Row row = rowIter.next();
                // Skip completely empty rows
                boolean hasData = false;
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.getCell(i);
                    String val = getCellStringValue(cell);
                    rowMap.put(headers.get(i), val);
                    if (!val.isEmpty()) hasData = true;
                }
                if (hasData) {
                    allRows.add(rowMap);
                }
            }

            return new Object[]{headers, allRows};
        }
    }

    @SuppressWarnings("unchecked")
    private Object[] parseCsv(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String headerLine = reader.readLine();
        if (headerLine == null || headerLine.trim().isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty.");
        }

        List<String> headers = parseCsvLine(headerLine);

        List<Map<String, String>> allRows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            List<String> values = parseCsvLine(line);
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                rowMap.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            allRows.add(rowMap);
        }

        return new Object[]{headers, allRows};
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    try {
                        double fd = cell.getNumericCellValue();
                        if (fd == Math.floor(fd)) return String.valueOf((long) fd);
                        return String.valueOf(fd);
                    } catch (Exception e2) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }

    // ---- Fuzzy matching ----

    MappingSuggestion findBestMatch(String excelColumn, List<String> agileNames) {
        String normalized = normalize(excelColumn);

        // 1. Exact match after normalization
        for (String agile : agileNames) {
            if (normalize(agile).equals(normalized)) {
                return new MappingSuggestion(excelColumn, agile, "high", Collections.emptyList());
            }
        }

        // 2. Contains match or Levenshtein ≤ 3
        String bestContains = null;
        String bestLevenshtein = null;
        int bestDist = Integer.MAX_VALUE;

        // Also collect top 3 by Levenshtein for "none" case
        List<String[]> scored = new ArrayList<>();

        for (String agile : agileNames) {
            String normAgile = normalize(agile);

            // Contains check
            if (bestContains == null) {
                if (normAgile.contains(normalized) || normalized.contains(normAgile)) {
                    bestContains = agile;
                }
            }

            // Levenshtein
            int dist = levenshtein(normalized, normAgile);
            scored.add(new String[]{agile, String.valueOf(dist)});
            if (dist < bestDist) {
                bestDist = dist;
                bestLevenshtein = agile;
            }
        }

        if (bestContains != null) {
            return new MappingSuggestion(excelColumn, bestContains, "medium", Collections.emptyList());
        }

        if (bestDist <= 3 && bestLevenshtein != null) {
            return new MappingSuggestion(excelColumn, bestLevenshtein, "medium", Collections.emptyList());
        }

        // No match — return top 3 suggestions
        scored.sort(Comparator.comparingInt(a -> Integer.parseInt(a[1])));
        List<String> top3 = scored.stream()
                .limit(3)
                .map(a -> a[0])
                .collect(Collectors.toList());

        return new MappingSuggestion(excelColumn, null, "none", top3);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace('_', ' ').toLowerCase().trim();
    }

    private int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[la][lb];
    }

    // ---- Helpers ----

    private CellStyle createHeaderStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(
                new byte[]{(byte) 0x2c, (byte) 0x3e, (byte) 0x50}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createCellStyle(SXSSFWorkbook workbook, byte[] rgb) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(rgb, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
