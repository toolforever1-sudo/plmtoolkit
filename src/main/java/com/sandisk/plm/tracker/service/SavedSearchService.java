package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class SavedSearchService {

    private static final Logger logger = Logger.getLogger(SavedSearchService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.saved-searches.file:./data/saved-searches.json}")
    private String filePath;

    // username -> list of saved searches
    private final ConcurrentHashMap<String, List<SavedSearch>> userSearches = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    public List<SavedSearch> getSearches(String username) {
        return userSearches.getOrDefault(username, new ArrayList<>());
    }

    public List<SavedSearch> getSharedSearches(String excludeUsername) {
        List<SavedSearch> result = new ArrayList<>();
        for (Map.Entry<String, List<SavedSearch>> entry : userSearches.entrySet()) {
            if (entry.getKey().equals(excludeUsername)) continue;
            for (SavedSearch s : entry.getValue()) {
                if (s.shared) result.add(s);
            }
        }
        return result;
    }

    public SavedSearch toggleShare(String username, String searchId, String displayName) {
        List<SavedSearch> list = userSearches.get(username);
        if (list == null) return null;
        for (SavedSearch s : list) {
            if (s.id != null && s.id.equals(searchId)) {
                s.shared = !s.shared;
                if (s.shared) {
                    s.owner = username;
                    s.ownerDisplay = displayName;
                } else {
                    s.owner = null;
                    s.ownerDisplay = null;
                }
                saveToDisk();
                return s;
            }
        }
        return null;
    }

    public void saveSearch(String username, SavedSearch search) {
        userSearches.computeIfAbsent(username, k -> new ArrayList<>()).add(search);
        saveToDisk();
    }

    public void deleteSearch(String username, String searchId) {
        List<SavedSearch> list = userSearches.get(username);
        if (list != null) {
            list.removeIf(s -> s.id != null && s.id.equals(searchId));
            saveToDisk();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, List<SavedSearch>> data = mapper.readValue(f,
                new TypeReference<Map<String, List<SavedSearch>>>() {});
            // Backfill IDs for searches saved before the ID field was added
            boolean needsSave = false;
            for (Map.Entry<String, List<SavedSearch>> entry : data.entrySet()) {
                for (SavedSearch s : entry.getValue()) {
                    if (s.id == null || s.id.isEmpty()) {
                        s.id = UUID.randomUUID().toString().substring(0, 8);
                        needsSave = true;
                    }
                    if (s.shared && s.owner == null) {
                        s.owner = entry.getKey();
                        needsSave = true;
                    }
                }
            }
            userSearches.putAll(data);
            if (needsSave) saveToDisk();
            logger.info("[SAVED SEARCHES] Loaded searches for " + data.size() + " users");
        } catch (Exception e) {
            logger.warning("[SAVED SEARCHES] Failed to load: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, userSearches);
        } catch (Exception e) {
            logger.warning("[SAVED SEARCHES] Failed to save: " + e.getMessage());
        }
    }

    public static class SavedSearch {
        public String id;
        public String name;
        public String tab; // "changes", "bom", "parts"
        public Map<String, String> params;
        public long createdAt;
        public boolean shared;
        public String owner;        // username of creator
        public String ownerDisplay; // display name of creator

        public SavedSearch() {}

        public SavedSearch(String name, String tab, Map<String, String> params) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.name = name;
            this.tab = tab;
            this.params = params;
            this.createdAt = System.currentTimeMillis();
            this.shared = false;
        }
    }
}
