package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * KPI Master — the single, authoritative lookup for ECN Classification, Change
 * Type, and SLA targets keyed by Request Classification + Request Subclass. Loaded
 * from {@code ./data/ecn-report/kpi-classifications.json} (sourced from the Agile
 * D029-00006 "KPI Classifications Master" sheet). All dashboards derive Change Type
 * and ECN Classification from here instead of re-computing them per report.
 *
 * <p>File shape: {@code { source, entries: [ { requestClassification,
 * requestSubclass, standard, urgent, ecnClassification, changeType, comments } ] }}.
 * The list order is the D029 order (grouped by classification) and is preserved for
 * the KPI Master tab display.</p>
 */
@Service
public class KpiClassificationService {

    private static final Logger logger = Logger.getLogger(KpiClassificationService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String FILE = "./data/ecn-report/kpi-classifications.json";

    private volatile long loadedMtime = -1L;
    private volatile List<Map<String, Object>> entries = Collections.emptyList();
    // "<class>|<subclass>" (lowercased, whitespace-collapsed) -> entry
    private volatile Map<String, Map<String, Object>> byKey = Collections.emptyMap();

    /** Normalize a classification/subclass token for keying: collapse whitespace,
     *  strip non-breaking spaces, lowercase. Matches how baselineTargets keys are
     *  compared elsewhere so the lookup is resilient to spacing differences. */
    static String norm(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim().toLowerCase();
    }

    static String key(String cls, String subclass) {
        return norm(cls) + "|" + norm(subclass);
    }

    @SuppressWarnings("unchecked")
    private synchronized void reloadIfNeeded() {
        File f = new File(FILE);
        long mtime = f.exists() ? f.lastModified() : 0L;
        if (mtime == loadedMtime) return;
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Map<String, Object>> map = new HashMap<>();
        if (f.exists()) {
            try {
                Map<String, Object> root = mapper.readValue(f, Map.class);
                Object es = root.get("entries");
                if (es instanceof List) {
                    for (Object o : (List<Object>) es) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> e = (Map<String, Object>) o;
                        list.add(e);
                        map.put(key(str(e.get("requestClassification")), str(e.get("requestSubclass"))), e);
                    }
                }
                logger.info("[KPI-MASTER] loaded " + list.size() + " classification entries from " + FILE);
            } catch (Exception ex) {
                logger.warning("[KPI-MASTER] failed to load " + FILE + ": " + ex.getMessage());
            }
        } else {
            logger.info("[KPI-MASTER] " + FILE + " not present — Change Type/ECN Classification lookup disabled");
        }
        this.entries = Collections.unmodifiableList(list);
        this.byKey = Collections.unmodifiableMap(map);
        this.loadedMtime = mtime;
    }

    /** Ordered entries for the KPI Master tab display. */
    public List<Map<String, Object>> getEntries() {
        reloadIfNeeded();
        return entries;
    }

    public boolean isLoaded() {
        reloadIfNeeded();
        return !entries.isEmpty();
    }

    /** Change Type for a Request Classification + Subclass, or null if unmapped. */
    public String changeTypeFor(String requestClassification, String requestSubclass) {
        reloadIfNeeded();
        Map<String, Object> e = byKey.get(key(requestClassification, requestSubclass));
        return e == null ? null : emptyToNull(str(e.get("changeType")));
    }

    /** ECN Classification for a Request Classification + Subclass, or null. */
    public String ecnClassificationFor(String requestClassification, String requestSubclass) {
        reloadIfNeeded();
        Map<String, Object> e = byKey.get(key(requestClassification, requestSubclass));
        return e == null ? null : emptyToNull(str(e.get("ecnClassification")));
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
