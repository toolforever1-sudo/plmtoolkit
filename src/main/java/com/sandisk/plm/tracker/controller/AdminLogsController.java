package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Admin-only access to server log files. The toolkit JVM and the companion
 * plm-agile-service JVM both write to the same logs directory on the prod
 * Windows server (D:\plm-toolkit\logs\), so a single endpoint here can surface
 * both sets of logs without needing the user to RDP in or rely on the SMB
 * share mount being present on their workstation.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/admin/logs} &mdash; list available log files (name, size, mtime).</li>
 *   <li>{@code GET /api/admin/logs/{name}?lines=N&q=keyword&context=K} &mdash;
 *       return the last N lines of the named file, optionally filtered to lines
 *       containing {@code q} (case-insensitive) with K lines of context.</li>
 * </ul>
 *
 * <p>Security:
 * <ul>
 *   <li>{@code isPlmAdmin} session attribute required (403 otherwise).</li>
 *   <li>The {@code name} path parameter is matched against the allow-list of
 *       {@code *.log} files actually present in the logs directory &mdash; any
 *       attempt at path traversal ({@code ../}, absolute paths, drive letters)
 *       is rejected by the contains-check, not just by validation of the input.</li>
 *   <li>Each request is recorded to the activity log so we can see who looked
 *       at what after the fact.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogsController {

    // Derived from app.log-file by stripping the trailing filename. The toolkit
    // writes its own log here and the agile-service JVM (same process owner)
    // shares the directory on prod.
    @Value("${app.log-file:./logs/plm-toolkit.log}")
    private String logFileSetting;

    @Autowired
    private ActivityLogger activityLogger;

    /** Resolve the logs directory once per request based on the configured log-file path. */
    private Path logsDir() {
        Path p = Paths.get(logFileSetting).toAbsolutePath().normalize();
        return p.getParent() != null ? p.getParent() : Paths.get(".").toAbsolutePath().normalize();
    }

    @GetMapping("")
    public ResponseEntity<?> list(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        Path dir = logsDir();
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(Map.of("success", false, "message",
                "Logs directory not found: " + dir));
        }
        List<Map<String, Object>> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getFileName().toString());
                    try {
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                        m.put("sizeBytes", a.size());
                        m.put("modifiedUtc", a.lastModifiedTime().toInstant().toString());
                    } catch (IOException ioe) {
                        m.put("sizeBytes", -1);
                        m.put("modifiedUtc", null);
                    }
                    return m;
                })
                .sorted((a, b) -> ((String) b.get("name")).compareToIgnoreCase((String) a.get("name")))
                .collect(Collectors.toList());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to list logs: " + e.getMessage()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("directory", dir.toString());
        resp.put("logs", entries);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> read(@PathVariable String name,
                                  @RequestParam(defaultValue = "500") int lines,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int context,
                                  HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        // Hard caps so a single request can't OOM us.
        if (lines > 5000) lines = 5000;
        if (lines < 1) lines = 1;
        if (context > 20) context = 20;
        if (context < 0) context = 0;

        // Resolve + validate the requested filename strictly against the
        // contents of the logs directory. Anything not in that dir (including
        // path-traversal attempts like "../something") is rejected.
        Path dir = logsDir();
        Path requested = dir.resolve(name).normalize();
        if (!requested.getParent().equals(dir) || !Files.isRegularFile(requested)) {
            return ResponseEntity.status(404).body(err("Unknown log file: " + name));
        }
        if (!name.toLowerCase().endsWith(".log")) {
            return ResponseEntity.status(400).body(err("Only .log files are accessible."));
        }

        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        activityLogger.log(username, displayName, "ADMIN_LOG_VIEW",
            "file=" + name + " lines=" + lines + (q == null ? "" : " q=" + truncate(q, 40)));

        try {
            List<String> result;
            if (q != null && !q.isEmpty()) {
                result = searchLines(requested, q, lines, context);
            } else {
                result = tailLines(requested, lines);
            }
            long totalSize = Files.size(requested);
            Instant mtime = Files.getLastModifiedTime(requested).toInstant();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("name", name);
            resp.put("sizeBytes", totalSize);
            resp.put("modifiedUtc", mtime.toString());
            resp.put("returnedLines", result.size());
            resp.put("requestedLines", lines);
            resp.put("filter", q);
            resp.put("contextLines", context);
            resp.put("text", String.join("\n", result));
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(err("Failed to read log: " + e.getMessage()));
        }
    }

    /**
     * Tail the last {@code lines} lines from a file by seeking near the end and
     * reading forward. We over-read by a heuristic (lines &times; 220 bytes/line)
     * to make sure we land before the desired window; if the file is smaller
     * than that, we just read all of it. Always drops the first partial line
     * read after a mid-line seek.
     */
    private List<String> tailLines(Path file, int lines) throws IOException {
        long size = Files.size(file);
        long bytesToRead = Math.min(size, (long) lines * 220L);
        long startAt = size - bytesToRead;

        byte[] buf = new byte[(int) bytesToRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(startAt);
            int n = 0;
            while (n < buf.length) {
                int r = raf.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        String[] all = text.split("\n", -1);
        // If we seeked into the middle of a line, the first element is a partial
        // line that should be discarded — UNLESS we read from byte 0 of the file
        // (in which case the first element is the real first line).
        if (startAt > 0 && all.length > 0) {
            all = Arrays.copyOfRange(all, 1, all.length);
        }
        if (all.length <= lines) return Arrays.asList(all);
        return new ArrayList<>(Arrays.asList(all).subList(all.length - lines, all.length));
    }

    /**
     * Stream the file line-by-line, keep all lines that match {@code q}
     * (case-insensitive substring) plus {@code context} lines of trailing /
     * leading context, then return at most {@code lines} matches (most recent).
     * Uses a sliding pre-context window so we don't have to read the file twice.
     */
    private List<String> searchLines(Path file, String q, int lines, int context) throws IOException {
        String needle = q.toLowerCase();
        // Cap on stored matches so a runaway grep can't blow the heap. Each
        // match keeps up to (1 + 2*context) lines.
        int maxMatches = lines;
        ArrayDeque<String> preCtx = new ArrayDeque<>(context);
        List<String> out = new ArrayList<>();
        int trailingNeeded = 0;
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            Iterator<String> it = stream.iterator();
            while (it.hasNext()) {
                String line = it.next();
                String lineLow = line.toLowerCase();
                if (lineLow.contains(needle)) {
                    // Flush pre-context.
                    if (context > 0) {
                        for (String c : preCtx) out.add(c);
                    }
                    out.add(line);
                    trailingNeeded = context;
                } else if (trailingNeeded > 0) {
                    out.add(line);
                    trailingNeeded--;
                }
                // Maintain sliding pre-context window.
                if (context > 0) {
                    preCtx.addLast(line);
                    if (preCtx.size() > context) preCtx.removeFirst();
                }
                // Soft cap on total emitted lines so we don't keep growing forever
                // on a query that matches everything. We accept N matches *worth*
                // of lines roughly — actual count may exceed by context lines.
                if (out.size() > maxMatches * (1 + 2 * context) + 100) break;
            }
        }
        // Trim to the most recent N "matches" worth of lines.
        int cap = maxMatches * (1 + 2 * context);
        if (out.size() > cap) {
            return new ArrayList<>(out.subList(out.size() - cap, out.size()));
        }
        return out;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
