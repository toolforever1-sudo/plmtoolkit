package com.sandisk.plm.tracker.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binds {@code restartEcn.attachment-types.<ext>=v1,v2} and
 * {@code restartEcn.attachment-max-mb} for the Restart ECN attachments feature (§2).
 * The extension → "IT Attachment Type" list-values map drives the dialog's
 * per-file type dropdown and the "type required" gate; extensions absent from
 * the map don't require a type.
 */
@Component
@ConfigurationProperties(prefix = "restart-ecn")
public class RestartEcnAttachmentConfig {

    /** ext (lowercase, no dot) -> comma-joined "IT Attachment Type" list values. */
    private Map<String, String> attachmentTypes = new LinkedHashMap<>();
    private int attachmentMaxMb = 100;

    public Map<String, String> getAttachmentTypes() { return attachmentTypes; }
    public void setAttachmentTypes(Map<String, String> m) { this.attachmentTypes = m; }
    public int getAttachmentMaxMb() { return attachmentMaxMb; }
    public void setAttachmentMaxMb(int v) { this.attachmentMaxMb = v; }

    /** ext -> list of allowed type values (split from the comma-joined config). */
    public Map<String, List<String>> typeOptions() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : attachmentTypes.entrySet()) {
            List<String> vals = new ArrayList<>();
            for (String v : e.getValue().split(",")) {
                String t = v.trim();
                if (!t.isEmpty()) vals.add(t);
            }
            out.put(e.getKey().toLowerCase(Locale.ROOT), vals);
        }
        return out;
    }

    /** Allowed type values for a filename's extension, or empty if the extension needs no type. */
    public List<String> typesForFile(String filename) {
        String ext = extensionOf(filename);
        List<String> v = typeOptions().get(ext);
        return v == null ? new ArrayList<>() : v;
    }

    /** True when the filename's extension requires an IT Attachment Type (has options). */
    public boolean requiresType(String filename) {
        return !typesForFile(filename).isEmpty();
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
