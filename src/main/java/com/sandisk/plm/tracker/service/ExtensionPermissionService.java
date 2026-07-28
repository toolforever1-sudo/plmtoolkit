package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages the small set of non-admin users who are allowed to add/upload/generate
 * external sources from the Extensions tab. Admins (members of the {@code pdl-plm-admin}
 * AD group) implicitly have all rights — they are NOT stored here.
 *
 * <p>Persistence: {@code ./data/extension-permissions.json}, shape:
 * <pre>{
 *   "contributors": ["vsingh", ...]
 * }</pre>
 */
@Service
public class ExtensionPermissionService {

    private static final Logger logger = Logger.getLogger(ExtensionPermissionService.class.getName());
    private static final String FILE_PATH = "./data/extension-permissions.json";

    private final ObjectMapper mapper = new ObjectMapper();
    // Stored lowercase for case-insensitive matching against AD usernames.
    private final Set<String> contributors = Collections.synchronizedSet(new LinkedHashSet<>());

    @PostConstruct
    public void load() {
        File f = new File(FILE_PATH);
        if (!f.exists()) {
            logger.info("[EXT-PERM] No permissions file yet at " + FILE_PATH + " (none granted)");
            return;
        }
        try {
            Map<String, Object> data = mapper.readValue(f, new TypeReference<Map<String, Object>>() {});
            Object raw = data.get("contributors");
            if (raw instanceof List) {
                contributors.clear();
                for (Object o : (List<?>) raw) {
                    if (o != null) contributors.add(o.toString().trim().toLowerCase());
                }
            }
            logger.info("[EXT-PERM] Loaded " + contributors.size() + " contributor(s)");
        } catch (IOException e) {
            logger.warning("[EXT-PERM] Failed to load " + FILE_PATH + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File f = new File(FILE_PATH);
            f.getParentFile().mkdirs();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contributors", new ArrayList<>(contributors));
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (IOException e) {
            logger.warning("[EXT-PERM] Failed to save " + FILE_PATH + ": " + e.getMessage());
        }
    }

    public boolean isContributor(String username) {
        if (username == null) return false;
        return contributors.contains(username.trim().toLowerCase());
    }

    public List<String> getContributors() {
        synchronized (contributors) {
            return new ArrayList<>(contributors);
        }
    }

    /** Returns true if the username was newly added (false if it was already a contributor). */
    public boolean addContributor(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        boolean added = contributors.add(username.trim().toLowerCase());
        if (added) save();
        return added;
    }

    /** Returns true if the username was actually removed. */
    public boolean removeContributor(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        boolean removed = contributors.remove(username.trim().toLowerCase());
        if (removed) save();
        return removed;
    }
}
