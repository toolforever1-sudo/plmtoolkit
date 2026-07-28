package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class AgileFieldNameService {

    private static final Logger logger = Logger.getLogger(AgileFieldNameService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.agile-field-names.file:./data/agile-field-names.json}")
    private String filePath;

    private volatile List<Map<String, String>> fieldNames = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
        logger.info("[AGILE FIELDS] Loaded " + fieldNames.size() + " field names");
    }

    public List<Map<String, String>> getFieldNames() {
        return fieldNames;
    }

    /**
     * Parse an uploaded Agile Classes Report CSV and rebuild the field names JSON.
     * Returns the count of fields extracted.
     */
    public int importFromCsv(InputStream csvInput) throws Exception {
        // Read with latin-1 encoding (Agile exports use it)
        BufferedReader reader = new BufferedReader(new InputStreamReader(csvInput, "ISO-8859-1"));

        // Use a simple CSV parser that handles quoted fields
        Map<String, String> fields = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            List<String> row = parseCsvLine(line);
            if (row.size() >= 8
                && "Parts".equals(row.get(0))
                && isRelevantTab(row.get(4))
                && "Yes".equals(row.get(5))
                && "Yes".equals(row.get(7))) {

                String name = row.get(6).trim();
                String tab = normalizeTab(row.get(4).trim());
                if (!name.isEmpty() && !fields.containsKey(name)) {
                    fields.put(name, tab);
                }
            }
        }

        List<Map<String, String>> result = new ArrayList<>();
        fields.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .forEach(e -> {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", e.getKey());
                entry.put("tab", e.getValue());
                result.add(entry);
            });

        fieldNames = result;
        saveToDisk();
        logger.info("[AGILE FIELDS] Imported " + result.size() + " field names from CSV");
        return result.size();
    }

    private static final Set<String> PAGE_TWO_TABS = new HashSet<>(Arrays.asList(
            "Inv/Planning", "More Info"));
    private static final Set<String> PAGE_THREE_TABS = new HashSet<>(Arrays.asList(
            "Page Three", "PDM Attributes", "PDA Attributes", "Product Data Attributes",
            "BOM Automation Fields", "Test Data Attributes (TDA)", "Product Data Management"));
    private static final Set<String> TITLE_BLOCK_TABS = new HashSet<>(Arrays.asList(
            "Title Block"));

    private boolean isRelevantTab(String tab) {
        return TITLE_BLOCK_TABS.contains(tab) || PAGE_TWO_TABS.contains(tab) || PAGE_THREE_TABS.contains(tab);
    }

    private String normalizeTab(String tab) {
        if (PAGE_TWO_TABS.contains(tab)) return "Page Two";
        if (PAGE_THREE_TABS.contains(tab)) return "Page Three";
        return "Title Block";
    }

    /** Simple CSV line parser that handles quoted fields with commas and escaped quotes */
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
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            fieldNames = mapper.readValue(f, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            logger.warning("[AGILE FIELDS] Failed to load: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, fieldNames);
        } catch (Exception e) {
            logger.warning("[AGILE FIELDS] Failed to save: " + e.getMessage());
        }
    }
}
