package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

@Service
public class EcnReportService {

    private static final Logger logger = Logger.getLogger(EcnReportService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String PROFILES_FILE = DATA_DIR + "/sla-profiles.json";
    private static final String ANNOTATIONS_FILE = DATA_DIR + "/annotations.json";
    private static final String DIST_LIST_FILE = DATA_DIR + "/distribution-list.json";
    private static final String ECN_DATA_FILE = DATA_DIR + "/ecn_data.json";

    private Map<String, Object> profilesData;
    private Map<String, Object> annotationsData;
    private Map<String, Object> distListData;

    @PostConstruct
    public void init() {
        new File(DATA_DIR).mkdirs();
        new File(DATA_DIR + "/output").mkdirs();
        new File(DATA_DIR + "/output/charts").mkdirs();

        profilesData = loadJson(PROFILES_FILE);
        if (!profilesData.containsKey("profiles")) {
            profilesData.put("profiles", new ArrayList<>());
        }
        // Seed default editors only when there are none configured yet
        // (key missing OR existing empty array — both treated as "fresh install")
        Object existingEditors = profilesData.get("editors");
        boolean isEmpty = existingEditors == null
                || (existingEditors instanceof List && ((List<?>) existingEditors).isEmpty());
        if (isEmpty) {
            List<String> defaultEditors = new ArrayList<>();
            defaultEditors.add("jimmy.sessumes");
            defaultEditors.add("vikas.singh");
            profilesData.put("editors", defaultEditors);
            logger.info("[ECN-REPORT] No editors configured — seeding defaults: " + defaultEditors);
        }
        saveJson(PROFILES_FILE, profilesData);

        annotationsData = loadJson(ANNOTATIONS_FILE);
        saveJson(ANNOTATIONS_FILE, annotationsData);

        distListData = loadJson(DIST_LIST_FILE);
        if (!distListData.containsKey("recipients")) {
            distListData.put("recipients", new ArrayList<>());
        }
        saveJson(DIST_LIST_FILE, distListData);

        logger.info("[ECN-REPORT] Initialized — " + getProfiles().size() + " profiles, "
                + getEditors().size() + " editors");
    }

    // =========================================================================
    // Role checks
    // =========================================================================

    @SuppressWarnings("unchecked")
    public boolean isEditor(String username) {
        return isEditor(username, null);
    }

    /**
     * Matches the user's identity against the editor list using any of:
     * SAM/employee-id (username), email prefix (e.g. "jimmy.sessumes"), or full email.
     * SanDisk SAM accounts are numeric employee IDs, but editors are usually
     * configured by their email handle — so we accept either form.
     */
    @SuppressWarnings("unchecked")
    public boolean isEditor(String username, String email) {
        List<String> editors = (List<String>) profilesData.get("editors");
        if (editors == null) return false;
        String emailLower = email != null ? email.toLowerCase() : null;
        String emailPrefix = (emailLower != null && emailLower.contains("@"))
                ? emailLower.substring(0, emailLower.indexOf('@')) : null;
        for (String e : editors) {
            if (e == null) continue;
            String norm = e.trim().toLowerCase();
            if (norm.isEmpty()) continue;
            if (username != null && norm.equalsIgnoreCase(username)) return true;
            if (emailPrefix != null && norm.equals(emailPrefix)) return true;
            if (emailLower != null && norm.equals(emailLower)) return true;
        }
        return false;
    }

    public boolean canEdit(boolean isAdmin, String username) {
        return isAdmin || isEditor(username, null);
    }

    public boolean canEdit(boolean isAdmin, String username, String email) {
        return isAdmin || isEditor(username, email);
    }

    // =========================================================================
    // Profile CRUD
    // =========================================================================

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getProfiles() {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        return profiles != null ? profiles : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> saveProfile(Map<String, Object> profile) {
        profile.put("id", UUID.randomUUID().toString().substring(0, 8));
        profile.put("createdAt", System.currentTimeMillis());
        if (!profile.containsKey("active")) {
            profile.put("active", false);
        }
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        if (profiles == null) {
            profiles = new ArrayList<>();
            profilesData.put("profiles", profiles);
        }
        profiles.add(profile);
        saveJson(PROFILES_FILE, profilesData);
        logger.info("[ECN-REPORT] Created profile " + profile.get("id") + ": " + profile.get("name"));
        return profile;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> updateProfile(String id, Map<String, Object> updates) {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        if (profiles == null) return null;
        for (Map<String, Object> p : profiles) {
            if (id.equals(p.get("id"))) {
                if (updates.containsKey("name")) p.put("name", updates.get("name"));
                if (updates.containsKey("overrides")) p.put("overrides", updates.get("overrides"));
                p.put("updatedAt", System.currentTimeMillis());
                saveJson(PROFILES_FILE, profilesData);
                logger.info("[ECN-REPORT] Updated profile " + id);
                return p;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean deleteProfile(String id) {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        if (profiles == null) return false;
        boolean removed = profiles.removeIf(p -> id.equals(p.get("id")));
        if (removed) {
            saveJson(PROFILES_FILE, profilesData);
            logger.info("[ECN-REPORT] Deleted profile " + id);
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> activateProfile(String id) {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        if (profiles == null) return null;
        Map<String, Object> activated = null;
        for (Map<String, Object> p : profiles) {
            if (id.equals(p.get("id"))) {
                p.put("active", true);
                activated = p;
            } else {
                p.put("active", false);
            }
        }
        if (activated != null) {
            saveJson(PROFILES_FILE, profilesData);
            logger.info("[ECN-REPORT] Activated profile " + id);
        }
        return activated;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getActiveProfile() {
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesData.get("profiles");
        if (profiles == null) return null;
        for (Map<String, Object> p : profiles) {
            Object active = p.get("active");
            if (Boolean.TRUE.equals(active)) {
                return p;
            }
        }
        return null;
    }

    // =========================================================================
    // Editors
    // =========================================================================

    @SuppressWarnings("unchecked")
    public synchronized List<String> getEditors() {
        List<String> editors = (List<String>) profilesData.get("editors");
        return editors != null ? new ArrayList<>(editors) : new ArrayList<>();
    }

    public synchronized void setEditors(List<String> editors) {
        profilesData.put("editors", editors);
        saveJson(PROFILES_FILE, profilesData);
        logger.info("[ECN-REPORT] Updated editors list: " + editors);
    }

    // =========================================================================
    // Annotations
    // =========================================================================

    public synchronized Map<String, Object> getAnnotations() {
        return new LinkedHashMap<>(annotationsData);
    }

    @SuppressWarnings("unchecked")
    public synchronized void saveAnnotation(String ecnNumber, Map<String, Object> annotation) {
        annotation.put("updatedAt", System.currentTimeMillis());
        annotationsData.put(ecnNumber, annotation);
        saveJson(ANNOTATIONS_FILE, annotationsData);
        logger.info("[ECN-REPORT] Saved annotation for " + ecnNumber);
    }

    // =========================================================================
    // Distribution List
    // =========================================================================

    public synchronized Map<String, Object> getDistributionList() {
        return new LinkedHashMap<>(distListData);
    }

    public synchronized void setDistributionList(Map<String, Object> distList) {
        this.distListData = new LinkedHashMap<>(distList);
        saveJson(DIST_LIST_FILE, distListData);
        logger.info("[ECN-REPORT] Updated distribution list");
    }

    // =========================================================================
    // ECN Data
    // =========================================================================

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getEcnData() {
        return loadJson(ECN_DATA_FILE);
    }

    public boolean hasEcnData() {
        return new File(ECN_DATA_FILE).exists();
    }

    /**
     * Returns a map keyed by ECN number → ECN context. Used by RejectionTrackerEmailService
     * to enrich rejection events with analyst/requestor/classification/date fields that
     * the rejection cache doesn't carry.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getEcnLookup() {
        Map<String, Object> data = getEcnData();
        Object ecnsObj = data.get("ecns");
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (ecnsObj instanceof List) {
            for (Object o : (List<Object>) ecnsObj) {
                if (o instanceof Map) {
                    Map<String, Object> ecn = (Map<String, Object>) o;
                    String num = (String) ecn.get("number");
                    if (num != null) out.put(num, ecn);
                }
            }
        }
        return out;
    }

    // =========================================================================
    // JSON helpers
    // =========================================================================

    private LinkedHashMap<String, Object> loadJson(String path) {
        File f = new File(path);
        if (!f.exists()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(f,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            logger.warning("[ECN-REPORT] Failed to load " + path + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private synchronized void saveJson(String path, Map<String, Object> data) {
        try {
            File f = new File(path);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (Exception e) {
            logger.warning("[ECN-REPORT] Failed to save " + path + ": " + e.getMessage());
        }
    }
}
