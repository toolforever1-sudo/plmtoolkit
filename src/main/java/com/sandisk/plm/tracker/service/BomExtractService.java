package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class BomExtractService {

    private static final Logger logger = Logger.getLogger(BomExtractService.class.getName());

    private static final String EXTRACT_DIR = "./data/extracts";
    private static final String[] COLUMNS = {
        "BOM_NUMBER", "COMPONENT_NUMBER", "REF", "QTY", "TYPE", "REMARK", "NOTES",
        "FORECAST", "OP", "REFERENCE_DESIGNATOR", "BOM_REV", "SEQ", "STATUS",
        "BOM_GREEN_COMPLIANCE", "PRIMARY_PN"
    };

    private static final String SQL =
        "SELECT BOM_NUMBER, COMPONENT_NUMBER, REF, QTY, TYPE, REMARK, NOTES, FORECAST, OP, " +
        "REFERENCE_DESIGNATOR, BOM_REV, SEQ, STATUS, BOM_GREEN_COMPLIANCE, PRIMARY_PN " +
        "FROM BOM_EXTRACT";

    private volatile boolean running = false;
    private volatile boolean notesRunning = false;
    private volatile int notesRowCount = 0;
    private volatile int notesBomCount = 0;
    private volatile int notesBomProcessed = 0;

    private final DataSource customDataSource;

    public BomExtractService(@Qualifier("customDataSource") DataSource customDataSource) {
        this.customDataSource = customDataSource;
    }

    public boolean isRunning() { return running; }
    public boolean isNotesRunning() { return notesRunning; }

    /**
     * Trigger extract asynchronously if not already running and no file exists.
     * Returns true if extract was started, false if already running or file exists.
     */
    public boolean triggerExtractIfNeeded() {
        if (running) return false;
        File existing = getExtractFile();
        if (existing != null && existing.exists()) return false;

        new Thread(() -> runDailyExtract(), "bom-extract-on-demand").start();
        return true;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void runDailyExtract() {
        if (running) {
            logger.info("[BOM EXTRACT] Already running, skipping");
            return;
        }
        running = true;
        long startTime = System.currentTimeMillis();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String csvFilename = "bom-full-extract-" + date + ".csv";
        String zipFilename = csvFilename + ".zip";

        logger.info("BOM Full Extract started at " + new java.util.Date());

        try {
            // Ensure directory exists
            Path extractDir = Paths.get(EXTRACT_DIR);
            Files.createDirectories(extractDir);

            Path csvPath = extractDir.resolve(csvFilename);
            Path zipPath = extractDir.resolve(zipFilename);

            long rowCount = 0;

            // Stream results to CSV
            try (Connection conn = customDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.setQueryTimeout(600);
                stmt.setFetchSize(5000);

                try (ResultSet rs = stmt.executeQuery(SQL);
                     BufferedWriter writer = new BufferedWriter(new FileWriter(csvPath.toFile()))) {

                    // Write header
                    writer.write(String.join(",", COLUMNS));
                    writer.newLine();

                    // Write rows
                    while (rs.next()) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 0; i < COLUMNS.length; i++) {
                            if (i > 0) line.append(',');
                            String value = rs.getString(COLUMNS[i]);
                            line.append(csvEscape(value));
                        }
                        writer.write(line.toString());
                        writer.newLine();
                        rowCount++;

                        if (rowCount % 100000 == 0) {
                            logger.info("BOM Extract progress: " + rowCount + " rows written");
                        }
                    }
                }
            }

            logger.info("BOM Extract CSV complete: " + rowCount + " total rows, file: " + csvPath);

            // Compress to zip
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
                 FileInputStream fis = new FileInputStream(csvPath.toFile())) {
                zos.putNextEntry(new ZipEntry(csvFilename));
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }

            // Delete uncompressed CSV
            Files.deleteIfExists(csvPath);

            long zipSize = Files.size(zipPath);
            long elapsed = System.currentTimeMillis() - startTime;

            logger.info("BOM Extract complete: " + rowCount + " rows, zip size: " +
                (zipSize / 1024 / 1024) + " MB, elapsed: " + (elapsed / 1000) + "s");

            // Clean up old extracts (older than 7 days)
            cleanupOldExtracts(extractDir);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "BOM Extract failed", e);
        } finally {
            running = false;
        }
    }

    private void cleanupOldExtracts(Path extractDir) {
        try {
            long cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
            Files.list(extractDir)
                .filter(p -> p.getFileName().toString().startsWith("bom-full-extract-")
                          && p.getFileName().toString().endsWith(".csv.zip"))
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis() < cutoff;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        logger.info("Deleted old extract: " + p.getFileName());
                    } catch (IOException e) {
                        logger.warning("Failed to delete old extract: " + p.getFileName());
                    }
                });
        } catch (IOException e) {
            logger.warning("Failed to clean up old extracts: " + e.getMessage());
        }
    }

    public Map<String, Object> getLatestExtract() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generating", running);
        File file = getExtractFile();
        if (file != null && file.exists()) {
            result.put("available", true);
            result.put("filename", file.getName());
            result.put("size", file.length());
            // Extract date from filename: bom-full-extract-YYYY-MM-DD.csv.zip
            String name = file.getName();
            String dateStr = name.replace("bom-full-extract-", "").replace(".csv.zip", "");
            result.put("date", dateStr);
            result.put("path", file.getAbsolutePath());
        } else {
            result.put("available", false);
            result.put("filename", "");
            result.put("size", 0L);
            result.put("date", "");
            result.put("path", "");
        }
        return result;
    }

    public File getExtractFile() {
        Path extractDir = Paths.get(EXTRACT_DIR);
        if (!Files.isDirectory(extractDir)) {
            return null;
        }
        try {
            return Files.list(extractDir)
                .filter(p -> p.getFileName().toString().startsWith("bom-full-extract-")
                          && p.getFileName().toString().endsWith(".csv.zip"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .map(Path::toFile)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            logger.warning("Failed to list extract files: " + e.getMessage());
            return null;
        }
    }

    // Use EXISTS instead of IN with subquery — much faster for large tables
    private static final String BOM_NOTES_SQL =
        "SELECT a.BOM_NUMBER, a.COMPONENT_NUMBER, a.REF, a.QTY, a.TYPE, a.REMARK, a.NOTES, a.FORECAST, a.OP, " +
        "a.REFERENCE_DESIGNATOR, a.BOM_REV, a.SEQ, a.STATUS, a.BOM_GREEN_COMPLIANCE, a.PRIMARY_PN " +
        "FROM BOM_EXTRACT a WHERE EXISTS (" +
        "SELECT 1 FROM BOM_EXTRACT b WHERE b.BOM_NUMBER = a.BOM_NUMBER AND b.NOTES IS NOT NULL AND TRIM(b.NOTES) IS NOT NULL" +
        ") ORDER BY a.BOM_NUMBER, a.SEQ";

    /**
     * Trigger async BOM Notes extract if not already running.
     */
    public boolean triggerBomNotesExtract() {
        if (notesRunning) return false;
        new Thread(() -> runBomNotesExtract(), "bom-notes-extract").start();
        return true;
    }

    /**
     * Scheduled daily BOM Notes extract at 3:00 AM — runs an hour after the full
     * BOM extract (2:00 AM) so the two heavy queries don't compete for the DB.
     * Also cleans up notes XLSX files older than 7 days.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void runDailyNotesExtract() {
        if (notesRunning) {
            logger.info("[BOM NOTES EXTRACT] Already running, skipping scheduled run");
            return;
        }
        runBomNotesExtract();
        try {
            cleanupOldNotesExtracts(Paths.get(EXTRACT_DIR));
        } catch (Exception e) {
            logger.warning("[BOM NOTES EXTRACT] Cleanup failed: " + e.getMessage());
        }
    }

    private void cleanupOldNotesExtracts(Path extractDir) {
        try {
            long cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
            Files.list(extractDir)
                .filter(p -> p.getFileName().toString().startsWith("BOM-Notes-Extract-")
                          && p.getFileName().toString().endsWith(".xlsx"))
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis() < cutoff;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        logger.info("Deleted old notes extract: " + p.getFileName());
                    } catch (IOException e) {
                        logger.warning("Failed to delete old notes extract: " + p.getFileName());
                    }
                });
        } catch (IOException e) {
            logger.warning("Failed to clean up old notes extracts: " + e.getMessage());
        }
    }

    private void runBomNotesExtract() {
        if (notesRunning) return;
        notesRunning = true;
        notesRowCount = 0;
        notesBomCount = 0;
        notesBomProcessed = 0;
        logger.info("[BOM NOTES EXTRACT] Starting...");
        long startTime = System.currentTimeMillis();

        try {
            Path extractDir = Paths.get(EXTRACT_DIR);
            Files.createDirectories(extractDir);

            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String filename = "BOM-Notes-Extract-" + date + ".xlsx";
            Path filePath = extractDir.resolve(filename);

            // Delete previous notes extracts
            Files.list(extractDir)
                .filter(p -> p.getFileName().toString().startsWith("BOM-Notes-Extract-"))
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });

            // Streaming + inline strings — flushes rows to disk and avoids the
            // O(N²) SharedStringsTable.addEntry blow-up.
            org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(null, 1000, false, false);
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("BOM Notes Extract");

            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.CellStyle evenStyle = workbook.createCellStyle();
            evenStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            evenStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.CellStyle notesStyle = workbook.createCellStyle();
            notesStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            notesStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowCount = 0;
            try (Connection conn = customDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(600);
                stmt.setFetchSize(5000);

                // First, count how many BOMs have notes
                try (ResultSet countRs = stmt.executeQuery(
                        "SELECT COUNT(DISTINCT BOM_NUMBER) FROM BOM_EXTRACT WHERE NOTES IS NOT NULL AND TRIM(NOTES) IS NOT NULL")) {
                    if (countRs.next()) {
                        notesBomCount = countRs.getInt(1);
                        logger.info("[BOM NOTES EXTRACT] Found " + notesBomCount + " BOMs with notes");
                    }
                }

                String lastBomNumber = "";
                try (ResultSet rs = stmt.executeQuery(BOM_NOTES_SQL)) {
                    while (rs.next()) {
                        rowCount++;
                        notesRowCount = rowCount;
                        if (rowCount > 1000000) {
                            logger.warning("[BOM NOTES EXTRACT] Exceeded 1M row limit, stopping");
                            break;
                        }
                        org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowCount);
                        String notes = null;
                        String bomNum = null;
                        for (int i = 0; i < COLUMNS.length; i++) {
                            String val = rs.getString(COLUMNS[i]);
                            if (val != null) val = val.trim();
                            row.createCell(i).setCellValue(val != null ? val : "");
                            if ("NOTES".equals(COLUMNS[i])) notes = val;
                            if ("BOM_NUMBER".equals(COLUMNS[i])) bomNum = val;
                        }
                        if (bomNum != null && !bomNum.equals(lastBomNumber)) {
                            notesBomProcessed++;
                            lastBomNumber = bomNum;
                        }
                        boolean hasNotes = notes != null && !notes.isEmpty();
                        if (hasNotes) {
                            for (int c = 0; c < COLUMNS.length; c++) row.getCell(c).setCellStyle(notesStyle);
                        } else if (rowCount % 2 == 0) {
                            for (int c = 0; c < COLUMNS.length; c++) row.getCell(c).setCellStyle(evenStyle);
                        }
                        if (rowCount % 50000 == 0) {
                            logger.info("[BOM NOTES EXTRACT] Progress: " + rowCount + " rows");
                        }
                    }
                }
            }

            // SXSSFWorkbook doesn't support autoSizeColumn — set fixed widths
            sheet.setColumnWidth(0, 5000);  // BOM_NUMBER
            sheet.setColumnWidth(1, 5000);  // COMPONENT_NUMBER
            for (int i = 2; i < COLUMNS.length; i++) {
                sheet.setColumnWidth(i, 4000);
            }
            // NOTES column wider
            sheet.setColumnWidth(6, 8000);
            sheet.createFreezePane(0, 1);
            if (rowCount > 0) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, COLUMNS.length - 1));
            }

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                workbook.write(fos);
            }
            workbook.close();
            workbook.dispose(); // clean up temp files

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("[BOM NOTES EXTRACT] Complete: " + rowCount + " rows, file: " + filePath +
                    ", size: " + (Files.size(filePath) / 1024) + " KB, elapsed: " + (elapsed / 1000) + "s");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[BOM NOTES EXTRACT] Failed", e);
        } finally {
            notesRunning = false;
        }
    }

    // A valid notes extract is always multi-MB. Anything below this is treated as empty/corrupt.
    private static final long NOTES_EXTRACT_MIN_VALID_BYTES = 10 * 1024L; // 10 KB

    public Map<String, Object> getLatestNotesExtract() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generating", notesRunning);
        result.put("bomCount", notesBomCount);
        result.put("bomProcessed", notesBomProcessed);
        result.put("rowCount", notesRowCount);
        File file = getNotesExtractFile();
        if (file != null && file.exists()) {
            long size = file.length();
            // Treat as not-available if (a) regeneration is in progress — the on-disk file is
            // stale/being replaced, or (b) the file is suspiciously small (empty/corrupt).
            boolean tooSmall = size < NOTES_EXTRACT_MIN_VALID_BYTES;
            boolean valid = !notesRunning && !tooSmall;
            result.put("available", valid);
            result.put("tooSmall", tooSmall);
            result.put("filename", file.getName());
            result.put("size", size);
            String name = file.getName();
            String dateStr = name.replace("BOM-Notes-Extract-", "").replace(".xlsx", "");
            result.put("date", dateStr);
            long lastModified = file.lastModified();
            long minutesAgo = (System.currentTimeMillis() - lastModified) / 60000;
            result.put("minutesAgo", minutesAgo);
        } else {
            result.put("available", false);
            result.put("tooSmall", false);
            result.put("filename", "");
            result.put("size", 0L);
            result.put("date", "");
        }
        return result;
    }

    public File getNotesExtractFile() {
        Path extractDir = Paths.get(EXTRACT_DIR);
        if (!Files.isDirectory(extractDir)) return null;
        try {
            return Files.list(extractDir)
                .filter(p -> p.getFileName().toString().startsWith("BOM-Notes-Extract-")
                          && p.getFileName().toString().endsWith(".xlsx"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .map(Path::toFile)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
