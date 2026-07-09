package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages external data sources (e.g. SAP Excel exports) that can be joined
 * with the Agile SKU data in the SKU Lookup tab.
 *
 * <p>Source definitions live in {@code ./data/external-sources.json}.
 * Each source's Excel file is parsed, deduplicated by a configured key column
 * (first occurrence wins), and cached as a JSON file for instant lookups.
 */
@Service
public class ExternalSourceService {

    private static final Logger LOG = Logger.getLogger(ExternalSourceService.class.getName());

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String SOURCES_CONFIG = "./data/external-sources.json";

    // In-memory cache of deserialized CacheEnvelope keyed by sourceId. Without
    // this, every lookup reparses the full JSON from disk — a 762 MB sdss-pda
    // cache turned a 1-SKU lookup into a 26 s wait. Populated lazily on first
    // lookup and refreshed by rebuildSource/removeSource.
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEnvelope> cacheMemory =
            new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    private MemoryGuard memoryGuard;

    /**
     * Caches > this many MB on disk are considered "big" and require a heap-
     * pressure check before loading. Smaller caches (catalogs, lookups) load
     * regardless. Tuned so a typical SAP-Mem-Tech (6 MB) is unguarded but
     * Cost-File-SAP (660 MB) goes through the gate.
     */
    private static final long BIG_CACHE_THRESHOLD_MB = 50;

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    @PostConstruct
    public void init() {
        File cfg = new File(SOURCES_CONFIG);
        if (!cfg.exists()) {
            LOG.info("[EXT-SRC] No external-sources.json found at " + cfg.getAbsolutePath() + " — skipping init");
            return;
        }
        LOG.info("[EXT-SRC] Found external-sources.json — missing caches will be queued for background rebuild");
        // Note: we deliberately do NOT call rebuildSource() directly here. The actual
        // submission happens from JobQueueService.enqueueStartupRebuilds() (called by
        // an ApplicationReadyEvent listener) so the bean wiring isn't tangled and
        // startup never blocks on heavy POI parsing.
    }

    /**
     * Lists IDs of sources whose JSON cache file is missing. Called by JobQueueService
     * after Spring is fully initialized to enqueue rebuilds without blocking startup.
     */
    public List<String> listSourcesMissingCache() {
        try {
            List<SourceDef> defs = loadSourceDefs();
            List<String> missing = new ArrayList<>();
            for (SourceDef def : defs) {
                File cache = new File(def.cacheFile != null ? def.cacheFile : "./data/external-source-" + def.id + ".json");
                if (!cache.exists()) missing.add(def.id);
            }
            return missing;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[EXT-SRC] listSourcesMissingCache failed", e);
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    //  Scheduled rebuild — daily at 9 AM
    // -----------------------------------------------------------------------

    /**
     * List of (sourceId, sourceName) pairs whose configured schedule hour matches
     * the current hour. Called by JobQueueService once an hour to enqueue rebuilds
     * via the same bounded queue as user-triggered rebuilds — no parallel POI parses.
     */
    public List<String[]> listScheduledForCurrentHour() {
        int currentHour = java.time.LocalTime.now().getHour();
        List<String[]> due = new ArrayList<>();
        try {
            List<SourceDef> defs = loadSourceDefs();
            for (SourceDef def : defs) {
                String scheduleHour = def.scheduleHour;
                if (scheduleHour != null && !scheduleHour.isEmpty()) {
                    try {
                        if (Integer.parseInt(scheduleHour) == currentHour) {
                            due.add(new String[]{def.id, def.name});
                        }
                    } catch (NumberFormatException nfe) {
                        LOG.warning("[EXT-SRC] Invalid scheduleHour for " + def.id + ": " + scheduleHour);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[EXT-SRC] listScheduledForCurrentHour failed", e);
        }
        return due;
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Returns list of available external sources (id, name, file, keyColumn, scheduleHour).
     */
    public List<Map<String, String>> getSourceList() {
        try {
            List<SourceDef> defs = loadSourceDefs();
            List<Map<String, String>> result = new ArrayList<>();
            for (SourceDef def : defs) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", def.id);
                m.put("name", def.name);
                m.put("file", def.file);
                m.put("keyColumn", def.keyColumn);
                if (def.scheduleHour != null) m.put("scheduleHour", def.scheduleHour);
                m.put("addedBy", def.addedBy != null ? def.addedBy : "");
                m.put("addedByUsername", def.addedByUsername != null ? def.addedByUsername : "");
                m.put("addedAt", def.addedAt != null ? def.addedAt : "");
                m.put("lastRefreshedBy", def.lastRefreshedBy != null ? def.lastRefreshedBy : "");
                m.put("lastRefreshedByUsername", def.lastRefreshedByUsername != null ? def.lastRefreshedByUsername : "");
                m.put("lastRefreshedAt", def.lastRefreshedAt != null ? def.lastRefreshedAt : "");
                // Record count from last successful rebuild (null/empty if never built)
                m.put("recordCount", def.recordCount != null ? def.recordCount.toString() : "");

                // Cache freshness — use the cache file's mtime as the generation time.
                // Avoids parsing potentially-huge JSON envelopes just to read one field.
                String cachePath = def.cacheFile != null ? def.cacheFile : ("./data/external-source-" + def.id + ".json");
                File cf = new File(cachePath);
                if (cf.exists()) {
                    m.put("cacheGeneratedAt",
                            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date(cf.lastModified())));
                    m.put("cacheGeneratedAtMs", String.valueOf(cf.lastModified()));
                    m.put("cacheSizeBytes", String.valueOf(cf.length()));
                } else {
                    m.put("cacheGeneratedAt", "");
                    m.put("cacheGeneratedAtMs", "0");
                    m.put("cacheSizeBytes", "0");
                }
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[EXT-SRC] Failed to load source list", e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns the column headers for a given source from its cached JSON.
     */
    public List<String> getSourceHeaders(String sourceId) {
        CacheEnvelope env = readCache(sourceId);
        if (env == null) return Collections.emptyList();
        return env.headers != null ? env.headers : Collections.emptyList();
    }

    /**
     * Looks up a single record from the cached JSON by key value.
     */
    public Map<String, String> getRecord(String sourceId, String key) {
        CacheEnvelope env = readCache(sourceId);
        if (env == null || env.records == null) return null;
        // Try exact match first, then case-insensitive
        Map<String, String> rec = env.records.get(key);
        if (rec != null) return rec;
        if (key != null) {
            rec = env.records.get(key.toUpperCase());
            if (rec != null) return rec;
            rec = env.records.get(key.toLowerCase());
            if (rec != null) return rec;
            // Full scan as last resort
            for (Map.Entry<String, Map<String, String>> entry : env.records.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Search all records in a source where a given column has a non-empty value,
     * or optionally contains a specific value. Returns up to maxResults records.
     */
    public List<Map<String, String>> searchRecords(String sourceId, String column, String containsValue, int maxResults) {
        CacheEnvelope env = readCache(sourceId);
        if (env == null || env.records == null) return Collections.emptyList();

        List<Map<String, String>> results = new ArrayList<>();
        String upperContains = containsValue != null ? containsValue.toUpperCase() : null;

        for (Map.Entry<String, Map<String, String>> entry : env.records.entrySet()) {
            Map<String, String> rec = entry.getValue();
            String val = null;
            // Case-insensitive column match
            for (Map.Entry<String, String> field : rec.entrySet()) {
                if (field.getKey().equalsIgnoreCase(column)) {
                    val = field.getValue();
                    break;
                }
            }
            if (val != null && !val.isEmpty()) {
                if (upperContains == null || val.toUpperCase().contains(upperContains)) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("ITEM_NUMBER", entry.getKey());
                    row.putAll(rec);
                    results.add(row);
                    if (results.size() >= maxResults) break;
                }
            }
        }
        return results;
    }

    /**
     * Count records in a source where a given column has a non-empty value.
     */
    public int countRecords(String sourceId, String column, String containsValue) {
        CacheEnvelope env = readCache(sourceId);
        if (env == null || env.records == null) return 0;

        int count = 0;
        String upperContains = containsValue != null ? containsValue.toUpperCase() : null;

        for (Map.Entry<String, Map<String, String>> entry : env.records.entrySet()) {
            Map<String, String> rec = entry.getValue();
            for (Map.Entry<String, String> field : rec.entrySet()) {
                if (field.getKey().equalsIgnoreCase(column)) {
                    String val = field.getValue();
                    if (val != null && !val.isEmpty()) {
                        if (upperContains == null || val.toUpperCase().contains(upperContains)) {
                            count++;
                        }
                    }
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Find which source contains a given column name (case-insensitive).
     * Returns the source ID, or null if not found.
     */
    public String findSourceByColumn(String columnName) {
        try {
            List<SourceDef> defs = loadSourceDefs();
            for (SourceDef def : defs) {
                List<String> headers = getSourceHeaders(def.id);
                for (String h : headers) {
                    if (h.equalsIgnoreCase(columnName)) return def.id;
                }
            }
        } catch (Exception e) {
            LOG.warning("[EXT-SRC] findSourceByColumn failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Manually trigger rebuild of a single external source.
     * @return summary map with status info
     */
    public Map<String, Object> rebuildSource(String sourceId) {
        return rebuildSource(sourceId, null);
    }

    /**
     * Rebuild with an optional progress callback. The callback receives the running
     * count of data rows scanned and is invoked roughly every 1,000 rows. Called by
     * {@link JobQueueService} so the UI can show "12,450 rows so far" while parsing.
     */
    public Map<String, Object> rebuildSource(String sourceId, java.util.function.Consumer<Integer> rowProgressCallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        try {
            SourceDef def = findSourceDef(sourceId);
            if (def == null) {
                result.put("status", "error");
                result.put("message", "Unknown source: " + sourceId);
                return result;
            }

            File excelFile = new File(def.file);
            if (!excelFile.exists()) {
                result.put("status", "error");
                result.put("message", "Source file not found: " + excelFile.getAbsolutePath());
                return result;
            }

            long fileBytes = excelFile.length();
            String fileNameLower = excelFile.getName().toLowerCase();
            boolean isXlsx = fileNameLower.endsWith(".xlsx");

            LOG.info("[EXT-SRC] Rebuilding '" + def.id + "' from " + excelFile.getAbsolutePath()
                    + " (" + (fileBytes / (1024 * 1024)) + " MB, "
                    + (isXlsx ? "streaming XSSF" : "in-memory HSSF/legacy") + ")");

            // POI's default 100 MB per-record cap (zip-bomb guard) trips on legitimate
            // dense files. Raise it for our admin-uploaded sources.
            org.apache.poi.util.IOUtils.setByteArrayMaxOverride(1_000_000_000);

            List<String> headers;
            Map<String, Map<String, String>> records;
            int totalRows;

            if (isXlsx) {
                // Streaming SAX parse — constant memory regardless of file size.
                StreamingResult sr = parseXlsxStreaming(excelFile, def.keyColumn, rowProgressCallback);
                if (sr.error != null) {
                    result.put("status", "error");
                    result.put("message", sr.error);
                    return result;
                }
                headers = sr.headers;
                records = sr.records;
                totalRows = sr.totalRows;
            } else {
                // Legacy .xls fallback — still uses in-memory reader. Apply a heap pre-flight.
                long requiredHeap = fileBytes * 6L;
                Runtime rt = Runtime.getRuntime();
                long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
                if (requiredHeap > freeHeap) {
                    result.put("status", "error");
                    result.put("message", String.format(java.util.Locale.US,
                            ".xls (legacy) file too large for available heap: %.1f MB file would need ~%d MB heap, only %d MB available. "
                                    + "Convert to .xlsx (which uses streaming) or shrink the file.",
                            fileBytes / (1024.0 * 1024.0),
                            requiredHeap / (1024 * 1024),
                            freeHeap / (1024 * 1024)));
                    return result;
                }
                LegacyResult lr = parseLegacyXls(excelFile, def.keyColumn, rowProgressCallback);
                if (lr.error != null) {
                    result.put("status", "error");
                    result.put("message", lr.error);
                    return result;
                }
                headers = lr.headers;
                records = lr.records;
                totalRows = lr.totalRows;
            }

            // Build cache envelope
            CacheEnvelope env = new CacheEnvelope();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            env.generatedAt = sdf.format(new Date());
            env.sourceId = def.id;
            env.sourceName = def.name;
            env.keyColumn = def.keyColumn;
            env.totalRecords = records.size();
            env.headers = headers;
            env.records = records;

            // Write cache file
            File cacheFile = new File(def.cacheFile);
            cacheFile.getParentFile().mkdirs();
            mapper.writeValue(cacheFile, env);

            // Drop the just-built envelope straight into the in-memory cache —
            // first post-rebuild lookup would otherwise re-parse the file we just
            // wrote (painful for a 762 MB source).
            cacheMemory.put(def.id, env);

            // Persist the record count back into external-sources.json so the
            // Extensions UI can show it without re-parsing the (potentially huge)
            // cache JSON on every list call.
            updateRecordCount(def.id, records.size());

            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("[EXT-SRC] Rebuilt '" + def.id + "': " + records.size() + " unique records from " +
                    totalRows + " rows in " + elapsed + " ms (" +
                    String.format("%.1f", cacheFile.length() / (1024.0 * 1024.0)) + " MB)");

            result.put("status", "success");
            result.put("sourceId", def.id);
            result.put("totalRows", totalRows);
            result.put("uniqueRecords", records.size());
            result.put("elapsedMs", elapsed);
            return result;

        } catch (OutOfMemoryError oom) {
            // Catching OOM here is intentional and safe: we hold no live state across
            // this method besides local POI objects, which are GC-eligible after we
            // bubble out. Letting it propagate killed the JVM on @PostConstruct startup.
            LOG.severe("[EXT-SRC] OOM rebuilding '" + sourceId + "' — file too large for current heap");
            result.put("status", "error");
            result.put("message", "Out of memory while parsing the Excel file. Restart the JAR with a larger -Xmx (e.g. -Xmx4g) or remove this source.");
            return result;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[EXT-SRC] Rebuild failed for '" + sourceId + "'", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
            return result;
        }
    }

    // -----------------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------------

    /**
     * Reads only the header row from an Excel file and validates that the key column
     * exists (case-insensitive). Returns the actual header name if found, or null with
     * an error message if not found.
     */
    public String[] validateKeyColumn(String filePath, String keyColumn) {
        File f = new File(filePath);
        if (!f.exists()) return new String[]{null, "File not found: " + filePath};

        List<String> headers = new ArrayList<>();
        String lowerFileName = filePath.toLowerCase();

        try {
            if (lowerFileName.endsWith(".xlsx")) {
                try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                             org.apache.poi.openxml4j.opc.OPCPackage.open(f, org.apache.poi.openxml4j.opc.PackageAccess.READ)) {
                    org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable sst =
                            new org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable(pkg);
                    org.apache.poi.xssf.eventusermodel.XSSFReader xssfReader =
                            new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
                    org.apache.poi.xssf.model.StylesTable styles = xssfReader.getStylesTable();

                    // Use a handler that only captures the header row
                    HeaderOnlyHandler headerHandler = new HeaderOnlyHandler();
                    org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
                    org.xml.sax.ContentHandler handler =
                            new org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler(
                                    styles, sst, headerHandler, formatter, false);

                    org.xml.sax.XMLReader sheetParser = org.apache.poi.util.XMLHelper.newXMLReader();
                    sheetParser.setContentHandler(handler);

                    java.util.Iterator<java.io.InputStream> sheets = xssfReader.getSheetsData();
                    if (sheets.hasNext()) {
                        try (java.io.InputStream sheetStream = sheets.next()) {
                            try { sheetParser.parse(new org.xml.sax.InputSource(sheetStream)); }
                            catch (Exception spe) {
                                if (!(spe instanceof HeaderOnlyHandler.StopParsingException)
                                    && !(spe.getCause() instanceof HeaderOnlyHandler.StopParsingException))
                                    throw spe;
                            }
                        }
                    }
                    headers = headerHandler.headers;
                }
            } else {
                // Legacy .xls
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
                     org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {
                    org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
                    org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
                    org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                            org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c,
                                    org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                            headers.add(formatter.formatCellValue(cell).trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            return new String[]{null, "Failed to read file headers: " + e.getMessage()};
        }

        // Case-insensitive search for the key column
        for (String h : headers) {
            if (h.equalsIgnoreCase(keyColumn)) {
                return new String[]{h, null}; // Return the actual header name
            }
        }
        return new String[]{null, "Key column '" + keyColumn + "' not found in file headers: " + headers};
    }

    /** SAX handler that captures only the header row (row 0), then aborts parsing. */
    private static class HeaderOnlyHandler implements org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler {
        final List<String> headers = new ArrayList<>();
        private boolean isHeaderRow;

        static class StopParsingException extends RuntimeException {
            StopParsingException() { super("Header row captured"); }
        }

        @Override public void startRow(int rowNum) { isHeaderRow = (rowNum == 0); }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (!isHeaderRow) return;
            if (formattedValue == null) formattedValue = "";
            int colIdx = StreamingSheetHandler.colIndexFromRef(cellReference);
            if (colIdx < 0) return;
            while (headers.size() <= colIdx) headers.add("");
            headers.set(colIdx, formattedValue.trim());
        }

        @Override
        public void endRow(int rowNum) {
            if (isHeaderRow) throw new StopParsingException();
        }
    }

    // -----------------------------------------------------------------------
    //  CRUD for external sources
    // -----------------------------------------------------------------------

    /** Back-compat 5-arg overload — callers that don't have a username still work; ownership-based delete will fall through to admin-only. */
    public void addSource(String name, String filePath, String keyColumn, String scheduleHour, String addedBy) throws Exception {
        addSource(name, filePath, keyColumn, scheduleHour, addedBy, null);
    }

    public void addSource(String name, String filePath, String keyColumn, String scheduleHour, String addedBy, String addedByUsername) throws Exception {
        // Generate ID from name (lowercase, replace non-alphanumeric with hyphens)
        String id = name.toLowerCase().replaceAll("[^a-z0-9]", "-").replaceAll("-+", "-");
        String cacheFile = "./data/external-source-" + id + ".json";

        List<SourceDef> defs = new ArrayList<>(loadSourceDefs());

        // Check for duplicate
        for (SourceDef src : defs) {
            if (id.equals(src.id)) throw new IllegalArgumentException("Source with this ID already exists.");
        }

        // Check file exists
        if (!new File(filePath).exists()) throw new IllegalArgumentException("File not found: " + filePath);

        SourceDef newSource = new SourceDef();
        newSource.id = id;
        newSource.name = name;
        newSource.file = filePath;
        newSource.keyColumn = keyColumn;
        newSource.cacheFile = cacheFile;
        if (scheduleHour != null && !scheduleHour.isEmpty()) {
            newSource.scheduleHour = scheduleHour;
        }
        newSource.addedBy = (addedBy != null && !addedBy.isEmpty()) ? addedBy : "unknown";
        newSource.addedByUsername = (addedByUsername != null && !addedByUsername.isEmpty()) ? addedByUsername : null;
        newSource.addedAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date());

        defs.add(newSource);
        saveConfig(defs);
        LOG.info("[EXT-SRC] Added source: " + name + " (" + id + ") by " + newSource.addedBy
            + (newSource.addedByUsername != null ? " (" + newSource.addedByUsername + ")" : ""));
    }

    /** Look up a source by id; null if not found. Used by the controller to make owner-based delete decisions. */
    public SourceDef findSource(String id) {
        try {
            for (SourceDef d : loadSourceDefs()) {
                if (id != null && id.equals(d.id)) return d;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Replace the underlying file for an existing source. Original addedBy /
     * addedByUsername / addedAt are preserved as authorship history; the
     * lastRefreshed* fields are updated to record who swapped the file. The
     * caller decides where the new file is stored (typically a timestamped
     * path so the previous file stays on disk in case the rebuild fails).
     *
     * @param id existing source id
     * @param newFilePath path to the freshly uploaded file
     * @param newKeyColumn optional new key column; pass null/empty to keep the existing one
     * @param refreshedBy display name of the uploader
     * @param refreshedByUsername AD username of the uploader
     * @throws IllegalArgumentException if the source doesn't exist or the file is missing
     */
    public synchronized void replaceFile(String id, String newFilePath, String newKeyColumn,
                                         String refreshedBy, String refreshedByUsername) throws Exception {
        if (newFilePath == null || newFilePath.isEmpty()) {
            throw new IllegalArgumentException("newFilePath is required");
        }
        if (!new File(newFilePath).exists()) {
            throw new IllegalArgumentException("File not found: " + newFilePath);
        }
        List<SourceDef> defs = new ArrayList<>(loadSourceDefs());
        SourceDef target = null;
        for (SourceDef d : defs) {
            if (id != null && id.equals(d.id)) { target = d; break; }
        }
        if (target == null) {
            throw new IllegalArgumentException("Source not found: " + id);
        }
        target.file = newFilePath;
        if (newKeyColumn != null && !newKeyColumn.trim().isEmpty()) {
            target.keyColumn = newKeyColumn.trim();
        }
        target.lastRefreshedBy = (refreshedBy != null && !refreshedBy.isEmpty()) ? refreshedBy : "unknown";
        target.lastRefreshedByUsername = (refreshedByUsername != null && !refreshedByUsername.isEmpty())
            ? refreshedByUsername : null;
        target.lastRefreshedAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date());
        saveConfig(defs);
        LOG.info("[EXT-SRC] Replaced file for '" + id + "' → " + newFilePath
            + " (refreshed by " + target.lastRefreshedBy
            + (target.lastRefreshedByUsername != null ? " / " + target.lastRefreshedByUsername : "") + ")");
    }

    /** Persist the latest unique-record count for a source so the UI can display it
     *  without re-parsing the cache file. Called after every successful rebuild. */
    private synchronized void updateRecordCount(String id, int count) {
        try {
            List<SourceDef> defs = loadSourceDefs();
            boolean changed = false;
            for (SourceDef d : defs) {
                if (id.equals(d.id)) {
                    d.recordCount = count;
                    changed = true;
                    break;
                }
            }
            if (changed) saveConfig(defs);
        } catch (Exception e) {
            LOG.warning("[EXT-SRC] Failed to persist record count for " + id + ": " + e.getMessage());
        }
    }

    public void removeSource(String id) {
        try {
            List<SourceDef> defs = loadSourceDefs();
            defs.removeIf(s -> id.equals(s.id));
            saveConfig(defs);
            // Delete cache file
            File cacheFile = new File("./data/external-source-" + id + ".json");
            if (cacheFile.exists()) cacheFile.delete();
            // Evict from in-memory cache so the records become GC-eligible
            cacheMemory.remove(id);
            LOG.info("[EXT-SRC] Removed source: " + id);
        } catch (Exception e) {
            LOG.warning("[EXT-SRC] Failed to remove source: " + e.getMessage());
        }
    }

    public void setScheduleHour(String id, String hour) {
        try {
            List<SourceDef> defs = loadSourceDefs();
            for (SourceDef src : defs) {
                if (id.equals(src.id)) {
                    src.scheduleHour = hour;
                    saveConfig(defs);
                    LOG.info("[EXT-SRC] Schedule set for " + id + ": daily at " + hour + ":00");
                    return;
                }
            }
            throw new IllegalArgumentException("Source not found: " + id);
        } catch (IOException e) {
            throw new RuntimeException("Failed to update schedule: " + e.getMessage(), e);
        }
    }

    private void saveConfig(List<SourceDef> defs) {
        try {
            File f = new File(SOURCES_CONFIG);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, defs);
        } catch (Exception e) {
            LOG.warning("[EXT-SRC] Failed to save config: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------------

    private List<SourceDef> loadSourceDefs() throws IOException {
        File f = new File(SOURCES_CONFIG);
        if (!f.exists()) return Collections.emptyList();
        try {
            return mapper.readValue(f, new TypeReference<List<SourceDef>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            // Loud, specific log so a malformed external-sources.json after a manual edit
            // doesn't silently look like "no sources configured" in the UI.
            LOG.severe("[EXT-SRC] external-sources.json is malformed at " + f.getAbsolutePath()
                    + " — error: " + jpe.getOriginalMessage()
                    + (jpe.getLocation() != null ? " (line " + jpe.getLocation().getLineNr()
                            + ", col " + jpe.getLocation().getColumnNr() + ")" : "")
                    + ". Fix the JSON or restore from backup; sources will appear empty until then.");
            throw jpe;
        }
    }

    private SourceDef findSourceDef(String sourceId) throws IOException {
        List<SourceDef> defs = loadSourceDefs();
        for (SourceDef d : defs) {
            if (d.id.equals(sourceId)) return d;
        }
        return null;
    }

    private CacheEnvelope readCache(String sourceId) {
        // Fast path: already loaded for this process lifetime.
        CacheEnvelope cached = cacheMemory.get(sourceId);
        if (cached != null) return cached;

        // Slow path: first access since startup (or after an eviction). Parse the
        // JSON once and stash it. Synchronized on the map so two concurrent first
        // lookups don't both spend 20 s parsing a 762 MB file.
        synchronized (cacheMemory) {
            cached = cacheMemory.get(sourceId);
            if (cached != null) return cached;
            try {
                SourceDef def = findSourceDef(sourceId);
                if (def == null) return null;
                File f = new File(def.cacheFile);
                if (!f.exists()) return null;

                // Heap-pressure circuit breaker for "big" caches. ObjectMapper
                // readValue() inflates the entire JSON plus per-record HashMap
                // overhead; a 660 MB on-disk cache balloons to ~1 GB transient
                // heap during inflation. If we're already at threshold, that
                // spike OOMs the JVM and takes down the whole web server. Refuse
                // with a clear runtime exception so the caller can surface 503.
                // System.gc() hint clears dead objects so MemoryGuard reads
                // accurately before the verdict.
                long fileSizeMb = f.length() / (1024L * 1024L);
                if (fileSizeMb >= BIG_CACHE_THRESHOLD_MB && memoryGuard != null
                        && memoryGuard.isUnderPressure()) {
                    System.gc();
                    if (memoryGuard.isUnderPressure()) {
                        String msg = "External source '" + sourceId + "' cache is too big to load right now ("
                            + fileSizeMb + " MB) — server heap is " + memoryGuard.usedPercent()
                            + "% full (threshold " + Math.round(memoryGuard.getThreshold() * 100)
                            + "%). Please retry in a minute.";
                        LOG.warning("[EXT-SRC] " + msg);
                        throw new IllegalStateException(msg);
                    }
                }

                long t0 = System.currentTimeMillis();
                CacheEnvelope env = mapper.readValue(f, CacheEnvelope.class);
                long elapsed = System.currentTimeMillis() - t0;
                LOG.info("[EXT-SRC] Loaded '" + sourceId + "' cache into memory ("
                        + (env.records != null ? env.records.size() : 0) + " records, "
                        + String.format("%.1f", f.length() / (1024.0 * 1024.0)) + " MB, " + elapsed + " ms)");
                cacheMemory.put(sourceId, env);
                return env;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[EXT-SRC] Failed to read cache for '" + sourceId + "'", e);
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Inner types
    // -----------------------------------------------------------------------

    /** Source definition from external-sources.json */
    public static class SourceDef {
        public String id;
        public String name;
        public String file;
        public String keyColumn;
        public String cacheFile;
        public String scheduleHour;
        public String addedBy;       // displayName of the user who added the source (null for legacy entries)
        public String addedByUsername; // AD username of the user who added the source (null for legacy entries) — used for owner-based delete gate
        public String addedAt;       // ISO-8601 timestamp (null for legacy entries)
        // Last-refreshed metadata: set by replaceFile() each time the underlying
        // file is swapped for a fresh upload. Original addedBy / addedByUsername
        // / addedAt are kept untouched as historical authorship.
        public String lastRefreshedBy;          // displayName of the latest replace-file uploader
        public String lastRefreshedByUsername;  // AD username of the latest replace-file uploader
        public String lastRefreshedAt;          // ISO-8601 timestamp of the latest replace-file
        public Integer recordCount;  // unique records in last successful rebuild (null if never built)
    }

    /** JSON cache envelope */
    public static class CacheEnvelope {
        public String generatedAt;
        public String sourceId;
        public String sourceName;
        public String keyColumn;
        public int totalRecords;
        public List<String> headers;
        public Map<String, Map<String, String>> records;
    }

    // -----------------------------------------------------------------------
    //  Streaming XLSX parser — constant memory
    // -----------------------------------------------------------------------

    /** Internal carrier for results from the streaming parse. */
    private static class StreamingResult {
        List<String> headers;
        Map<String, Map<String, String>> records;
        int totalRows;
        String error;
    }

    /** Internal carrier for results from the legacy in-memory parse. */
    private static class LegacyResult {
        List<String> headers;
        Map<String, Map<String, String>> records;
        int totalRows;
        String error;
    }

    /**
     * SAX-driven XLSX read using POI's event API. Memory stays roughly constant
     * regardless of file size — the only large allocation is the deduped records
     * map, which scales with output (unique rows × column count), not input.
     *
     * <p>Implementation outline:
     * <ol>
     *   <li>Open the file as an OPCPackage.</li>
     *   <li>Use ReadOnlySharedStringsTable so the SST isn't fully expanded into POJOs.</li>
     *   <li>Get an InputStream for the first sheet via XSSFReader.</li>
     *   <li>Parse with XSSFSheetXMLHandler, dispatching per-cell to our SheetContentsHandler.</li>
     * </ol>
     */
    private StreamingResult parseXlsxStreaming(File excelFile, String keyColumn,
                                                java.util.function.Consumer<Integer> rowProgressCallback) {
        StreamingResult sr = new StreamingResult();
        try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                     org.apache.poi.openxml4j.opc.OPCPackage.open(excelFile, org.apache.poi.openxml4j.opc.PackageAccess.READ)) {

            org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable sst =
                    new org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable(pkg);
            org.apache.poi.xssf.eventusermodel.XSSFReader xssfReader =
                    new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
            org.apache.poi.xssf.model.StylesTable styles = xssfReader.getStylesTable();

            StreamingSheetHandler myHandler = new StreamingSheetHandler(keyColumn, rowProgressCallback);
            // The 4th arg is the DataFormatter POI uses to format numeric/date cells.
            // Passing null here causes an NPE deep inside POI the moment it hits any
            // formatted numeric cell (formatRawCellContents on a null formatter).
            // Use a fresh DataFormatter — cheap to construct, handles every style.
            org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            org.xml.sax.ContentHandler handler =
                    new org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler(
                            styles, sst, myHandler, formatter, /*formulasNotResults*/ false);

            org.xml.sax.XMLReader sheetParser = org.apache.poi.util.XMLHelper.newXMLReader();
            sheetParser.setContentHandler(handler);

            // We only care about the first sheet, matching the legacy behavior.
            java.util.Iterator<java.io.InputStream> sheets = xssfReader.getSheetsData();
            if (!sheets.hasNext()) {
                sr.error = "Workbook has no sheets.";
                return sr;
            }
            try (java.io.InputStream sheetStream = sheets.next()) {
                sheetParser.parse(new org.xml.sax.InputSource(sheetStream));
            }

            if (myHandler.keyColIndex < 0) {
                sr.error = "Key column '" + keyColumn + "' not found in headers: " + myHandler.headers;
                return sr;
            }

            sr.headers = myHandler.headers;
            sr.records = myHandler.records;
            sr.totalRows = myHandler.totalRowsScanned;
            // Final progress notification so the UI shows the true total
            if (rowProgressCallback != null) rowProgressCallback.accept(myHandler.totalRowsScanned);
            return sr;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[EXT-SRC] Streaming XLSX parse failed", e);
            sr.error = "Streaming parse failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return sr;
        }
    }

    /**
     * Legacy .xls reader using the in-memory WorkbookFactory. Kept for the rare
     * case of an old binary Excel file. The streaming path covers .xlsx, which
     * is what the tool actually generates.
     */
    private LegacyResult parseLegacyXls(File excelFile, String keyColumn,
                                         java.util.function.Consumer<Integer> rowProgressCallback) {
        LegacyResult lr = new LegacyResult();
        List<String> headers = new ArrayList<>();
        Map<String, Map<String, String>> records = new LinkedHashMap<>();
        int totalRows = 0;
        int keyColIndex = -1;

        try (java.io.FileInputStream fis = new java.io.FileInputStream(excelFile);
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {

            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();

            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                lr.error = "No header row found in Excel file";
                return lr;
            }
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String hdr = formatter.formatCellValue(cell).trim();
                headers.add(hdr);
                if (hdr.equalsIgnoreCase(keyColumn)) keyColIndex = c;
            }
            if (keyColIndex < 0) {
                lr.error = "Key column '" + keyColumn + "' not found in headers: " + headers;
                return lr;
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                totalRows++;
                if (rowProgressCallback != null && totalRows % 1000 == 0) {
                    rowProgressCallback.accept(totalRows);
                }

                org.apache.poi.ss.usermodel.Cell keyCell = row.getCell(keyColIndex, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String keyValue = formatter.formatCellValue(keyCell).trim();
                if (keyValue.isEmpty()) continue;

                if (records.containsKey(keyValue)) {
                    // Merge: append unique values from this row to the existing record
                    Map<String, String> existing = records.get(keyValue);
                    for (int c = 0; c < headers.size(); c++) {
                        if (c == keyColIndex) continue;
                        String hdr = headers.get(c);
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        String newVal = formatter.formatCellValue(cell).trim();
                        if (newVal.isEmpty()) continue;
                        String oldVal = existing.get(hdr);
                        if (oldVal == null || oldVal.isEmpty()) {
                            existing.put(hdr, newVal);
                        } else if (!oldVal.equals(newVal) && !containsCsvValue(oldVal, newVal)) {
                            existing.put(hdr, oldVal + ", " + newVal);
                        }
                    }
                } else {
                    Map<String, String> rec = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        rec.put(headers.get(c), formatter.formatCellValue(cell).trim());
                    }
                    records.put(keyValue, rec);
                }
            }

            if (rowProgressCallback != null) rowProgressCallback.accept(totalRows);
            lr.headers = headers;
            lr.records = records;
            lr.totalRows = totalRows;
            return lr;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[EXT-SRC] Legacy .xls parse failed", e);
            lr.error = "Legacy .xls parse failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return lr;
        }
    }

    /**
     * SAX content handler for the XLSX reader. Captures the first row as headers
     * (using the cell-letter prefix to compute the column index, so missing/empty
     * cells don't shift the layout) and accumulates deduped records keyed by the
     * configured key column.
     */
    private static class StreamingSheetHandler implements org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler {
        private final String keyColumn;
        private final java.util.function.Consumer<Integer> progressCallback;
        final List<String> headers = new ArrayList<>();
        final Map<String, Map<String, String>> records = new LinkedHashMap<>();
        int keyColIndex = -1;
        int totalRowsScanned = 0;
        private boolean isHeaderRow;
        private Map<Integer, String> currentRow;

        StreamingSheetHandler(String keyColumn, java.util.function.Consumer<Integer> progressCallback) {
            this.keyColumn = keyColumn;
            this.progressCallback = progressCallback;
        }

        @Override
        public void startRow(int rowNum) {
            isHeaderRow = (rowNum == 0);
            currentRow = isHeaderRow ? null : new LinkedHashMap<>();
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (formattedValue == null) formattedValue = "";
            formattedValue = formattedValue.trim();

            int colIdx = colIndexFromRef(cellReference);
            if (colIdx < 0) return;

            if (isHeaderRow) {
                while (headers.size() <= colIdx) headers.add("");
                headers.set(colIdx, formattedValue);
                if (formattedValue.equalsIgnoreCase(keyColumn)) keyColIndex = colIdx;
            } else {
                currentRow.put(colIdx, formattedValue);
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (isHeaderRow) return;
            totalRowsScanned++;
            if (progressCallback != null && totalRowsScanned % 1000 == 0) {
                progressCallback.accept(totalRowsScanned);
            }
            if (keyColIndex < 0) return;

            String keyValue = currentRow.get(keyColIndex);
            if (keyValue == null || keyValue.isEmpty()) return;

            if (records.containsKey(keyValue)) {
                // Merge: append unique values from this row to the existing record
                Map<String, String> existing = records.get(keyValue);
                for (int c = 0; c < headers.size(); c++) {
                    if (c == keyColIndex) continue; // skip the key column itself
                    String hdr = headers.get(c);
                    String newVal = currentRow.get(c);
                    if (newVal == null || newVal.isEmpty()) continue;
                    String oldVal = existing.get(hdr);
                    if (oldVal == null || oldVal.isEmpty()) {
                        existing.put(hdr, newVal);
                    } else if (!oldVal.equals(newVal) && !containsCsvValue(oldVal, newVal)) {
                        existing.put(hdr, oldVal + ", " + newVal);
                    }
                }
            } else {
                Map<String, String> rec = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String hdr = headers.get(c);
                    String val = currentRow.get(c);
                    rec.put(hdr, val == null ? "" : val);
                }
                records.put(keyValue, rec);
            }
            currentRow = null;
        }

        /** "A1" -> 0, "B5" -> 1, "AA10" -> 26, "AC100" -> 28 */
        static int colIndexFromRef(String ref) {
            if (ref == null || ref.isEmpty()) return -1;
            int result = 0;
            for (int i = 0; i < ref.length(); i++) {
                char c = ref.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    result = result * 26 + (c - 'A' + 1);
                } else {
                    break;
                }
            }
            return result - 1;
        }
    }

    /** Checks if a comma-separated string already contains a specific value. */
    private static boolean containsCsvValue(String csv, String value) {
        for (String part : csv.split(", ")) {
            if (part.equals(value)) return true;
        }
        return false;
    }
}
