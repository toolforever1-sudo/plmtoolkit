package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.FileArchiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Admin-only access to the file archive (every upload + download metadata
 * captured by {@link FileArchiveService}).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/admin/file-archive} &mdash; list events (most recent first), filterable</li>
 *   <li>{@code GET /api/admin/file-archive/{id}} &mdash; download archived upload bytes</li>
 *   <li>{@code POST /api/admin/file-archive/{id}/pin} &mdash; mark / unmark permanent</li>
 *   <li>{@code POST /api/admin/file-archive/purge} &mdash; run the daily purge on demand</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/file-archive")
public class AdminFileArchiveController {

    @Autowired
    private FileArchiveService archive;

    @Autowired
    private ActivityLogger activityLogger;

    @GetMapping("")
    public ResponseEntity<?> list(@RequestParam(required = false) String user,
                                  @RequestParam(required = false) String feature,
                                  @RequestParam(required = false) String direction,
                                  @RequestParam(required = false) String filename,
                                  @RequestParam(defaultValue = "500") int limit,
                                  HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        FileArchiveService.ListResult r = archive.list(user, feature, direction, filename, limit);

        String me = (String) session.getAttribute("username");
        String meDisplay = (String) session.getAttribute("displayName");
        activityLogger.log(me, meDisplay, "ADMIN_FILE_ARCHIVE_VIEW",
            "returned=" + r.entries.size() + "/" + r.totalScanned
                + (user == null ? "" : " user=" + user)
                + (feature == null ? "" : " feature=" + feature)
                + (direction == null ? "" : " direction=" + direction));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("entries", r.entries);
        resp.put("totalScanned", r.totalScanned);
        resp.put("returnedLimit", r.entries.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> download(@PathVariable String id, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        Map<String, Object> entry = archive.findById(id);
        if (entry == null) return ResponseEntity.status(404).body(err("Not found: " + id));

        Path p;
        try {
            p = archive.resolveArchivedPath(id);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        }
        if (p == null || !Files.isRegularFile(p)) {
            return ResponseEntity.status(404).body(err("Archived file missing: " + id));
        }

        String me = (String) session.getAttribute("username");
        String meDisplay = (String) session.getAttribute("displayName");
        activityLogger.log(me, meDisplay, "ADMIN_FILE_ARCHIVE_DOWNLOAD",
            "id=" + id + " file=" + entry.get("filename") + " origUser=" + entry.get("user"));

        String filename = String.valueOf(entry.getOrDefault("filename", "archived.bin"));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeHeader(filename) + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new FileSystemResource(p.toFile()));
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pin(@PathVariable String id,
                                 @RequestBody(required = false) Map<String, Object> body,
                                 HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        boolean flag = body == null || !Boolean.FALSE.equals(body.get("permanent"));
        boolean ok = archive.markPermanent(id, flag);
        if (!ok) return ResponseEntity.status(404).body(err("Not found: " + id));

        String me = (String) session.getAttribute("username");
        String meDisplay = (String) session.getAttribute("displayName");
        activityLogger.log(me, meDisplay, "ADMIN_FILE_ARCHIVE_PIN", "id=" + id + " permanent=" + flag);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("id", id);
        resp.put("permanent", flag);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/purge")
    public ResponseEntity<?> purge(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        FileArchiveService.PurgeResult r = archive.runPurge();

        String me = (String) session.getAttribute("username");
        String meDisplay = (String) session.getAttribute("displayName");
        activityLogger.log(me, meDisplay, "ADMIN_FILE_ARCHIVE_PURGE",
            "bytesPurged=" + r.bytesPurged + " rowsDropped=" + r.rowsDropped + " kept=" + r.totalAfter);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("bytesPurged", r.bytesPurged);
        resp.put("rowsDropped", r.rowsDropped);
        resp.put("totalAfter", r.totalAfter);
        return ResponseEntity.ok(resp);
    }

    private static boolean isAdmin(HttpSession session) {
        Boolean v = (Boolean) session.getAttribute("isPlmAdmin");
        return v != null && v;
    }

    private static Map<String, String> err(String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", message == null ? "unknown" : message);
        return m;
    }

    /** Strip quotes / control chars from a filename before putting it in a header value. */
    private static String safeHeader(String s) {
        if (s == null) return "archived.bin";
        return s.replaceAll("[\"\\r\\n]", "_");
    }
}
