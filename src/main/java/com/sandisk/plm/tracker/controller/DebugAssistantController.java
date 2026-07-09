package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.DebugAssistantService;
import com.sandisk.plm.tracker.service.DebugAssistantService.SourceRef;
import com.sandisk.plm.tracker.service.DebugHistoryService;
import com.sandisk.plm.tracker.service.EmailService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/debug")
public class DebugAssistantController {

    private static final Logger logger = Logger.getLogger(DebugAssistantController.class.getName());

    private final DebugAssistantService debugService;
    private final ActivityLogger activityLogger;
    private final EmailService emailService;
    private final DebugHistoryService historyService;

    public DebugAssistantController(DebugAssistantService debugService, ActivityLogger activityLogger,
                                     EmailService emailService, DebugHistoryService historyService) {
        this.debugService = debugService;
        this.activityLogger = activityLogger;
        this.emailService = emailService;
        this.historyService = historyService;
    }

    /**
     * POST /api/debug/analyze — streams progress via SSE, then sends the full result.
     */
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) MultipartFile sourceFile,
            @RequestParam(required = false, defaultValue = "") String pastedTrace,
            @RequestParam(required = false, defaultValue = "") String repo,
            @RequestParam(required = false, defaultValue = "PLM") String project,
            HttpSession session) {

        SseEmitter emitter = new SseEmitter(180_000L); // 3 min timeout
        emitter.onTimeout(emitter::complete);

        // Admin-only check
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (isAdmin == null || !isAdmin) {
            emit(emitter, "error", Map.of("error", "Admin access required."));
            return emitter;
        }

        // Read file bytes now — multipart data won't survive past the request thread
        byte[] fileBytes = null;
        String fileName = null;
        try {
            if (file != null && !file.isEmpty()) {
                fileBytes = file.getBytes();
                fileName = file.getOriginalFilename();
            }
        } catch (Exception e) {
            emit(emitter, "error", Map.of("error", "Failed to read uploaded file: " + e.getMessage()));
            return emitter;
        }

        // Read optional source JAR / .java file
        byte[] sourceFileBytes = null;
        String sourceFileName = null;
        try {
            if (sourceFile != null && !sourceFile.isEmpty()) {
                sourceFileBytes = sourceFile.getBytes();
                sourceFileName = sourceFile.getOriginalFilename();
            }
        } catch (Exception e) {
            emit(emitter, "error", Map.of("error", "Failed to read source file: " + e.getMessage()));
            return emitter;
        }

        final byte[] fBytes = fileBytes;
        final String fName = fileName;
        final byte[] sfBytes = sourceFileBytes;
        final String sfName = sourceFileName;
        final String username = (String) session.getAttribute("username");
        final String displayName = (String) session.getAttribute("displayName");

        Thread worker = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                // ── Step 1: Parse input ──────────────────────────────
                emit(emitter, "progress", Map.of("step", 1, "label", "Parsing input..."));

                StringBuilder rawText = new StringBuilder();
                String inputLabel = "";
                if (fBytes != null) {
                    String fn = fName != null ? fName.toLowerCase() : "";
                    if (fn.endsWith(".eml")) {
                        rawText.append(debugService.parseEmlFile(fBytes));
                        inputLabel = fName + " (email)";
                    } else {
                        rawText.append(new String(fBytes, StandardCharsets.UTF_8));
                        inputLabel = fName;
                    }
                }
                if (!pastedTrace.isEmpty()) {
                    rawText.append("\n").append(pastedTrace);
                    if (inputLabel.isEmpty()) inputLabel = "pasted text";
                }

                if (rawText.length() == 0) {
                    emit(emitter, "error", Map.of("error", "No input provided. Upload a file or paste a stack trace."));
                    return;
                }
                emit(emitter, "progress", Map.of("step", 1, "label", "Parsed " + inputLabel, "done", true));

                // ── Step 2: Extract stack traces & source refs ───────
                emit(emitter, "progress", Map.of("step", 2, "label", "Extracting stack traces..."));

                List<String> traces = debugService.parseStackTraces(rawText.toString());
                if (traces.isEmpty()) {
                    emit(emitter, "error", Map.of("error", "No stack traces found in the input."));
                    return;
                }

                String allTraces = String.join("\n\n---\n\n", traces);
                List<SourceRef> refs = debugService.extractSourceRefs(allTraces);

                emit(emitter, "progress", Map.of(
                    "step", 2,
                    "label", "Found " + traces.size() + " stack traces, " + refs.size() + " source references",
                    "done", true));

                // ── Step 3: Resolve source code ──────────────────────
                Map<String, String> sourceFiles = new LinkedHashMap<>();
                List<Map<String, String>> sourceResults = new ArrayList<>();
                int foundCount = 0;
                boolean noJavaSources = false;
                String sourceMode = repo.isEmpty() ? "auto" : repo;

                if (sfBytes != null) {
                    // ── Local source file — skip Bitbucket ───────────
                    String sfn = sfName != null ? sfName.toLowerCase() : "";
                    sourceMode = "local:" + sfName;

                    if (sfn.endsWith(".java")) {
                        emit(emitter, "progress", Map.of("step", 3, "label", "Reading uploaded .java file..."));
                        String content = new String(sfBytes, StandardCharsets.UTF_8);
                        String simpleName = sfName.contains("/")
                                ? sfName.substring(sfName.lastIndexOf('/') + 1)
                                : sfName.contains("\\")
                                    ? sfName.substring(sfName.lastIndexOf('\\') + 1)
                                    : sfName;

                        for (SourceRef ref : refs) {
                            Map<String, String> result = new LinkedHashMap<>();
                            result.put("class", ref.fullClass);
                            result.put("file", ref.fileName);
                            result.put("line", String.valueOf(ref.lineNumber));

                            if (ref.fileName.equalsIgnoreCase(simpleName)) {
                                sourceFiles.put(ref.fullClass + " (uploaded:" + ref.lineNumber + ")", content);
                                result.put("found", "true");
                                result.put("source", "Uploaded file");
                                String[] lines = content.split("\n");
                                if (ref.lineNumber <= lines.length) {
                                    result.put("sourceLine", lines[ref.lineNumber - 1].trim());
                                } else {
                                    result.put("syncWarning", "Line " + ref.lineNumber +
                                        " exceeds file length (" + lines.length + " lines).");
                                }
                                foundCount++;
                            } else {
                                result.put("found", "false");
                                result.put("note", "Does not match uploaded file " + simpleName);
                            }
                            sourceResults.add(result);
                        }
                        emit(emitter, "progress", Map.of(
                            "step", 3,
                            "label", "Matched " + foundCount + " of " + refs.size() + " refs from uploaded file",
                            "done", true));

                    } else if (sfn.endsWith(".jar")) {
                        emit(emitter, "progress", Map.of("step", 3, "label", "Scanning JAR for .java source files..."));
                        Map<String, String> jarSources = debugService.extractJavaSources(sfBytes);

                        if (jarSources.isEmpty()) {
                            noJavaSources = true;
                            for (SourceRef ref : refs) {
                                Map<String, String> result = new LinkedHashMap<>();
                                result.put("class", ref.fullClass);
                                result.put("file", ref.fileName);
                                result.put("line", String.valueOf(ref.lineNumber));
                                result.put("found", "false");
                                result.put("note", "JAR has no .java source files");
                                sourceResults.add(result);
                            }
                            emit(emitter, "progress", Map.of(
                                "step", 3,
                                "label", "No .java source files found in " + sfName,
                                "done", true));
                        } else {
                            emit(emitter, "progress", Map.of(
                                "step", 3,
                                "label", "Found " + jarSources.size() + " .java files in JAR, matching..."));

                            for (int i = 0; i < refs.size(); i++) {
                                SourceRef ref = refs.get(i);
                                Map<String, String> result = new LinkedHashMap<>();
                                result.put("class", ref.fullClass);
                                result.put("file", ref.fileName);
                                result.put("line", String.valueOf(ref.lineNumber));

                                String lookupSuffix = ref.packagePath + "/" + ref.fileName;
                                String matchedContent = null;
                                String matchedPath = null;

                                for (Map.Entry<String, String> je : jarSources.entrySet()) {
                                    if (je.getKey().endsWith(lookupSuffix)) {
                                        matchedContent = je.getValue();
                                        matchedPath = je.getKey();
                                        break;
                                    }
                                }
                                // Fallback: match by filename only
                                if (matchedContent == null) {
                                    for (Map.Entry<String, String> je : jarSources.entrySet()) {
                                        String entryName = je.getKey();
                                        String simpleName = entryName.substring(entryName.lastIndexOf('/') + 1);
                                        if (simpleName.equals(ref.fileName)) {
                                            matchedContent = je.getValue();
                                            matchedPath = entryName;
                                            break;
                                        }
                                    }
                                }

                                if (matchedContent != null) {
                                    sourceFiles.put(ref.fullClass + " (jar:" + ref.lineNumber + ")", matchedContent);
                                    result.put("found", "true");
                                    result.put("source", "JAR: " + matchedPath);
                                    String[] lines = matchedContent.split("\n");
                                    if (ref.lineNumber <= lines.length) {
                                        result.put("sourceLine", lines[ref.lineNumber - 1].trim());
                                    } else {
                                        result.put("syncWarning", "Line " + ref.lineNumber +
                                            " exceeds file length (" + lines.length + " lines).");
                                    }
                                    foundCount++;
                                } else {
                                    result.put("found", "false");
                                    result.put("note", "Not found in JAR");
                                }
                                sourceResults.add(result);
                            }
                            emit(emitter, "progress", Map.of(
                                "step", 3,
                                "label", "Resolved " + foundCount + " of " + refs.size() + " from JAR",
                                "done", true));
                        }
                    }
                } else {
                    // ── Bitbucket source resolution ──────────────────
                    for (int i = 0; i < refs.size(); i++) {
                        SourceRef ref = refs.get(i);

                        emit(emitter, "progress", Map.of(
                            "step", 3,
                            "label", "Searching Bitbucket for source code...",
                            "detail", ref.fileName,
                            "current", i + 1,
                            "total", refs.size()));

                        Map<String, String> result = new LinkedHashMap<>();
                        result.put("class", ref.fullClass);
                        result.put("file", ref.fileName);
                        result.put("line", String.valueOf(ref.lineNumber));

                        // Skip Agile SDK classes — Oracle code, never in Bitbucket
                        if (ref.fullClass.startsWith("com.agile.api.")) {
                            result.put("found", "false");
                            result.put("note", "Agile SDK class");
                            sourceResults.add(result);
                            continue;
                        }

                        boolean resolved = false;

                        if (!repo.isEmpty()) {
                            String[] prefixes = {"src/", "src/main/java/", ""};
                            for (String prefix : prefixes) {
                                String path = prefix + ref.packagePath + "/" + ref.fileName;
                                String content = debugService.fetchSourceFromBitbucket(project, repo, path);
                                if (content != null && content.length() > 10) {
                                    sourceFiles.put(ref.fullClass + " (" + repo + ":" + ref.lineNumber + ")", content);
                                    result.put("repo", repo);
                                    result.put("path", path);
                                    result.put("found", "true");
                                    String[] lines = content.split("\n");
                                    if (ref.lineNumber <= lines.length) {
                                        result.put("sourceLine", lines[ref.lineNumber - 1].trim());
                                    } else {
                                        result.put("syncWarning", "Line " + ref.lineNumber +
                                            " exceeds file length (" + lines.length + " lines). Code may be out of sync.");
                                    }
                                    resolved = true;
                                    break;
                                }
                            }
                            if (!resolved) {
                                result.put("found", "false");
                                result.put("note", "Not found in " + repo);
                            }
                        } else {
                            Map<String, String> crossResult = debugService.findSourceAcrossRepos(project, ref);
                            if (crossResult != null) {
                                sourceFiles.put(ref.fullClass + " (" + crossResult.get("repo") + ":" + ref.lineNumber + ")",
                                        crossResult.get("content"));
                                result.put("repo", crossResult.get("repo"));
                                result.put("path", crossResult.get("path"));
                                result.put("found", "true");
                                resolved = true;
                            } else {
                                result.put("found", "false");
                            }
                        }

                        if (resolved) foundCount++;
                        sourceResults.add(result);
                    }

                    emit(emitter, "progress", Map.of(
                        "step", 3,
                        "label", "Resolved " + foundCount + " of " + refs.size() + " source files",
                        "done", true));
                }

                // Build source info summary
                StringBuilder ri = new StringBuilder();
                for (Map<String, String> sr : sourceResults) {
                    ri.append(sr.get("class")).append(" \u2192 ");
                    if ("true".equals(sr.get("found"))) {
                        String loc = sr.getOrDefault("source",
                                sr.getOrDefault("repo", sr.getOrDefault("foundInRepo", "?")));
                        ri.append("Found in ").append(loc);
                        if (sr.containsKey("syncWarning")) ri.append(" \u26A0 ").append(sr.get("syncWarning"));
                    } else {
                        ri.append("NOT FOUND");
                        if (sr.containsKey("note")) ri.append(" (").append(sr.get("note")).append(")");
                    }
                    ri.append("\n");
                }

                // ── Step 4: AI Analysis ──────────────────────────────
                String analysis;
                if (noJavaSources) {
                    emit(emitter, "progress", Map.of(
                        "step", 4,
                        "label", "Skipped \u2014 no .java source files in JAR",
                        "done", true));
                    analysis = "Source analysis skipped \u2014 the uploaded JAR (" + sfName
                        + ") does not contain .java source files. It may only contain compiled .class files.\n\n"
                        + "To use this feature, provide a JAR that bundles source code, "
                        + "upload individual .java files, or leave the source field blank to search Bitbucket.";
                } else {
                    emit(emitter, "progress", Map.of("step", 4, "label", "Running AI analysis..."));
                    analysis = debugService.analyzeWithAI(allTraces, sourceFiles, ri.toString());
                    emit(emitter, "progress", Map.of("step", 4, "label", "AI analysis complete", "done", true));
                }

                // ── Send full result ─────────────────────────────────
                Map<String, Object> response = new LinkedHashMap<>();
                if (fName != null) response.put("inputFile", fName);
                if (sfName != null) response.put("sourceFile", sfName);
                response.put("tracesFound", traces.size());
                response.put("stackTrace", allTraces);
                response.put("sourceRefs", refs.size());
                response.put("sources", sourceResults);
                response.put("analysis", analysis);
                response.put("queryTimeMs", System.currentTimeMillis() - start);

                emit(emitter, "result", response);

                // Save to history
                try {
                    DebugHistoryService.DebugEntry entry = new DebugHistoryService.DebugEntry();
                    entry.id = UUID.randomUUID().toString().substring(0, 8);
                    entry.timestamp = java.time.Instant.now().toString();
                    entry.inputFile = fName != null ? fName : "Pasted text";
                    entry.tracesFound = traces.size();
                    entry.sourceRefs = refs.size();
                    entry.sourcesResolved = foundCount;
                    entry.queryTimeMs = System.currentTimeMillis() - start;
                    entry.analysis = analysis;
                    entry.stackTrace = allTraces;
                    entry.sources = sourceResults;
                    // Extract root cause for the history list
                    String exc = "";
                    java.util.regex.Matcher rcm = java.util.regex.Pattern
                        .compile("([\\w.]+(?:Exception|Error))")
                        .matcher(allTraces);
                    if (rcm.find()) exc = rcm.group(1);
                    entry.rootCause = exc.isEmpty() ? "Analysis" : exc;
                    historyService.addEntry(username, entry);
                } catch (Exception he) {
                    logger.warning("[DEBUG-HISTORY] Failed to save: " + he.getMessage());
                }

                activityLogger.log(username, displayName, "DEBUG_ANALYZE",
                    "Traces: " + traces.size() + " | Sources: " + refs.size() +
                    " | Repo: " + sourceMode);

            } catch (Exception e) {
                logger.warning("[DEBUG] Analysis thread failed: " + e.getMessage());
                emit(emitter, "error", Map.of("error", "Analysis failed: " + e.getMessage()));
            } finally {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }, "debug-analysis");
        worker.setDaemon(true);
        worker.start();

        return emitter;
    }

    /** Send an SSE event; swallows IOExceptions (client may have disconnected). */
    private void emit(SseEmitter emitter, String eventName, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) { }
    }

    /**
     * POST /api/debug/email-report — email the analysis result to the user.
     */
    @PostMapping("/email-report")
    public Map<String, String> emailReport(@RequestBody Map<String, Object> body, HttpSession session) {
        Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
        if (isAdmin == null || !isAdmin) {
            return Map.of("error", "Admin access required.");
        }

        String email = (String) body.get("email");
        if (email == null || !email.contains("@")) {
            return Map.of("error", "Valid email required.");
        }

        try {
            String rootCause = (String) body.getOrDefault("rootCause", "Debug Analysis Report");
            int tracesFound = ((Number) body.getOrDefault("tracesFound", 0)).intValue();
            int sourceRefs = ((Number) body.getOrDefault("sourceRefs", 0)).intValue();
            int sourcesResolved = ((Number) body.getOrDefault("sourcesResolved", 0)).intValue();
            long queryTimeMs = ((Number) body.getOrDefault("queryTimeMs", 0)).longValue();
            String analysis = (String) body.getOrDefault("analysis", "");

            String subject = "Debug Report \u00b7 " + rootCause;
            if (subject.length() > 78) subject = subject.substring(0, 75) + "...";

            emailService.sendDebugReport(email, subject, rootCause,
                    tracesFound, sourcesResolved, sourceRefs, queryTimeMs, analysis);

            activityLogger.log(
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                "DEBUG_EMAIL", "Report emailed to " + email);

            return Map.of("ok", "Report sent to " + email);
        } catch (Exception e) {
            logger.warning("[DEBUG] Email failed: " + e.getMessage());
            return Map.of("error", "Failed to send: " + e.getMessage());
        }
    }

    /**
     * GET /api/debug/history — user's past analyses (newest first).
     */
    @GetMapping("/history")
    public List<DebugHistoryService.DebugEntry> getHistory(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return Collections.emptyList();
        return historyService.getHistory(username);
    }

    /**
     * DELETE /api/debug/history — clear all history for the current user.
     */
    @DeleteMapping("/history")
    public Map<String, String> clearHistory(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return Map.of("error", "Not logged in");
        historyService.clearHistory(username);
        return Map.of("ok", "History cleared");
    }

    /**
     * GET /api/debug/repos — list available repos in the project
     */
    @GetMapping("/repos")
    public List<String> listRepos(@RequestParam(defaultValue = "PLM") String project) {
        return debugService.listRepos(project);
    }
}
