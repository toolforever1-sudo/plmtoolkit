package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates AI-powered analysis of scheduled job errors from the Monitor.
 * For each error: checks cache, fetches source from Bitbucket, calls AI via Portkey,
 * generates .docx document, and caches the result.
 * Also detects cross-error themes and resolved-job transitions.
 */
@Service
public class MonitorAnalysisService {

    private static final Logger logger = Logger.getLogger(MonitorAnalysisService.class.getName());

    @Autowired
    private DebugAssistantService debugService;

    @Autowired
    private MonitorAnalysisCacheService cacheService;

    @Autowired
    private MonitorDocumentService docService;

    @Autowired
    private PortkeyClient portkeyClient;

    @Value("${portkey.api-key:}")
    private String portkeyApiKey;

    @Value("${portkey.provider:@anthropic-eastus2}")
    private String portkeyProvider;

    @Value("${portkey.model:claude-sonnet-4-6}")
    private String portkeyModel;

    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}")
    private String portkeyBaseUrl;

    @Value("${portkey.enabled:false}")
    private boolean portkeyEnabled;

    @Value("${ai.help.api-key:}")
    private String claudeApiKey;

    @Value("${bitbucket.project:PLM}")
    private String defaultBitbucketProject;

    /**
     * Analyze a batch of errors from the monitor.
     * Returns a map with keys: analyses, themes, resolved, unmappedNotes
     *
     * @param cachedOnly  if true, only return results that are already cached —
     *                    skip AI calls entirely. Uncached task names are returned
     *                    in the "uncachedTasks" list so the caller can re-submit
     *                    them in a second request.
     */
    public Map<String, Object> analyzeBatch(List<Map<String, String>> errors,
                                             List<Map<String, String>> previousErrors,
                                             boolean cachedOnly) {
        List<Map<String, Object>> analyses = new ArrayList<>();
        List<Map<String, String>> unmappedNotes = new ArrayList<>();
        List<String> allSummaries = new ArrayList<>(); // for theme detection
        List<String> uncachedTasks = new ArrayList<>(); // tasks skipped in cached-only mode

        for (Map<String, String> error : errors) {
            String taskName = error.getOrDefault("taskName", "Unknown");
            String stackTrace = error.getOrDefault("stackTrace", "");
            String errorMessage = error.getOrDefault("errorMessage", "");
            String javaClass = error.getOrDefault("javaClass", "");
            String repo = error.getOrDefault("repo", "");
            String commitInfo = error.getOrDefault("commitInfo", "");
            String excerptText = error.getOrDefault("excerptText", "");

            logger.info("[MONITOR-AI] Analyzing error for task: " + taskName);

            // Double-check SanDisk filter (monitor should already filter, but be safe)
            if (!isSandiskClass(javaClass) && !isSandiskClass(stackTrace)) {
                logger.info("[MONITOR-AI] Skipping non-SanDisk class: " + javaClass);
                continue;
            }

            // Use excerpt text as full trace if stackTrace is sparse
            String fullTrace = stackTrace;
            if (excerptText != null && !excerptText.isEmpty() && excerptText.length() > fullTrace.length()) {
                fullTrace = excerptText;
            }

            // Check cache
            String normalized = cacheService.normalizeTrace(fullTrace);
            String traceHash = cacheService.computeHash(normalized);
            String cached = cacheService.getCached(traceHash);

            if (cached != null) {
                logger.info("[MONITOR-AI] Using cached analysis for " + taskName);
                Map<String, Object> cachedResult = parseCachedAnalysis(cached, taskName);
                analyses.add(cachedResult);
                String summary = (String) cachedResult.getOrDefault("summary", errorMessage);
                allSummaries.add(taskName + ": " + summary);
                continue;
            }

            // In cached-only mode, skip AI calls for uncached errors
            if (cachedOnly) {
                logger.info("[MONITOR-AI] Skipping uncached task (cached-only mode): " + taskName);
                uncachedTasks.add(taskName);
                continue;
            }

            // Fetch source from Bitbucket
            String sourceCode = null;
            String repoInfoStr = "";
            boolean isUnmapped = repo.isEmpty() || repo.equals("unmapped");

            if (!isUnmapped) {
                sourceCode = fetchSourceForError(fullTrace, repo);
                repoInfoStr = "Repository: " + repo + "\n" + commitInfo;
            } else if (!javaClass.isEmpty()) {
                // Try cross-repo search
                sourceCode = searchSourceAcrossRepos(fullTrace, javaClass);
                if (sourceCode != null) {
                    isUnmapped = false;
                    repoInfoStr = "Source found via cross-repo search for " + javaClass;
                } else {
                    repoInfoStr = "Repository mapping not found for class: " + javaClass
                            + "\nAI analysis includes inference based on class names and error patterns.";
                }
            }

            // Call AI for analysis
            String aiAnalysis = analyzeErrorWithAI(taskName, fullTrace, errorMessage,
                    sourceCode, repoInfoStr, isUnmapped);

            // Extract severity from AI response
            String severity = extractSeverity(aiAnalysis);

            // Extract one-line summary from AI response
            String summary = extractSummary(aiAnalysis, errorMessage);
            allSummaries.add(taskName + ": " + summary);

            // Generate .docx
            String docxBase64 = docService.generateDebugDocument(
                    taskName, errorMessage, fullTrace, aiAnalysis,
                    sourceCode, repoInfoStr, severity, isUnmapped);

            // Extract structured fields from AI response
            String diagnosis = extractSection(aiAnalysis, "DIAGNOSIS");
            String evidence = extractSection(aiAnalysis, "EVIDENCE");
            String related = extractSection(aiAnalysis, "RELATED");
            String quickCommand = extractSection(aiAnalysis, "QUICK_COMMAND");
            List<Map<String, String>> fixSteps = extractFixSteps(aiAnalysis);

            // Build result
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskName", taskName);
            result.put("summary", summary);
            result.put("severity", severity);
            result.put("analysis", aiAnalysis);
            result.put("diagnosis", diagnosis);
            result.put("evidence", evidence);
            result.put("fixSteps", fixSteps);
            result.put("related", related);
            result.put("quickCommand", quickCommand);
            result.put("docxBase64", docxBase64 != null ? docxBase64 : "");
            result.put("traceHash", traceHash);

            analyses.add(result);

            // Handle unmapped repo AI guesses
            if (isUnmapped && aiAnalysis != null) {
                Map<String, String> note = new LinkedHashMap<>();
                note.put("taskName", taskName);
                note.put("javaClass", javaClass);
                note.put("aiGuess", extractUnmappedGuess(aiAnalysis));
                unmappedNotes.add(note);
            }

            // Cache the result
            String cacheJson = buildCacheJson(taskName, summary, severity, aiAnalysis,
                    docxBase64, traceHash, diagnosis, evidence, related, quickCommand, fixSteps);
            cacheService.putCache(traceHash, cacheJson);
        }

        // Detect themes across all errors
        List<Map<String, Object>> themes = detectThemes(allSummaries, errors);

        // Detect resolved jobs
        List<Map<String, String>> resolved = detectResolved(errors, previousErrors);

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("analyses", analyses);
        response.put("themes", themes);
        response.put("resolved", resolved);
        response.put("unmappedNotes", unmappedNotes);
        if (!uncachedTasks.isEmpty()) {
            response.put("uncachedTasks", uncachedTasks);
        }

        return response;
    }

    // =========================================================================
    // AI Analysis
    // =========================================================================

    private String analyzeErrorWithAI(String taskName, String stackTrace, String errorMessage,
                                       String sourceCode, String repoInfo, boolean isUnmapped) {
        boolean usePortkey = portkeyEnabled && portkeyApiKey != null && !portkeyApiKey.isEmpty();
        if (!usePortkey && (claudeApiKey == null || claudeApiKey.isEmpty())) {
            return "AI analysis unavailable — no API key configured.";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a Java debugging expert for Agile PLM (Oracle) integrations at SanDisk/Western Digital.\n");
        prompt.append("You are analyzing a failure in the scheduled job: **").append(taskName).append("**\n\n");

        prompt.append("## Error Message\n```\n").append(errorMessage).append("\n```\n\n");
        prompt.append("## Stack Trace\n```\n").append(stackTrace).append("\n```\n\n");

        if (sourceCode != null && !sourceCode.isEmpty()) {
            prompt.append("## Source Code\n```java\n").append(sourceCode).append("\n```\n\n");
        }

        if (repoInfo != null && !repoInfo.isEmpty()) {
            prompt.append("## Repository Info\n").append(repoInfo).append("\n\n");
        }

        prompt.append("## Instructions\n");
        prompt.append("Return your analysis in this EXACT format with these labeled sections:\n\n");
        prompt.append("SUMMARY: <one line, max 100 chars>\n");
        prompt.append("SEVERITY: <CRITICAL|HIGH|MEDIUM|LOW>\n");
        prompt.append("DIAGNOSIS: <single paragraph — root cause in plain language, classify as infrastructure/code bug/configuration>\n");
        prompt.append("EVIDENCE: <single paragraph — what in the stack trace proves your diagnosis>\n");
        prompt.append("FIX_STEPS:\n");
        prompt.append("1. <step text> [COMMAND: <shell command if applicable>]\n");
        prompt.append("2. <step text> [COMMAND: <shell command if applicable>]\n");
        prompt.append("...(as many steps as needed)\n");
        prompt.append("RELATED: <single paragraph — connections to other failures, caveats, what to check if this fix doesn't work>\n");
        prompt.append("QUICK_COMMAND: <the single most useful diagnostic command, e.g. tnsping <alias> or netstat -an | grep 7001>\n\n");
        if (sourceCode != null && !sourceCode.isEmpty()) {
            prompt.append("Reference specific source lines where relevant.\n");
            prompt.append("If line numbers don't match the stack trace, note the deployed code may be out of sync.\n");
        }
        if (isUnmapped) {
            prompt.append("Source code was NOT available. Make educated guesses based on class names, package structure, and common Agile PLM patterns.\n");
            prompt.append("Prefix guesses with 'UNMAPPED INFERENCE: '\n");
        }
        prompt.append("\nBe specific to this SanDisk/Agile PLM context. No generic advice.\n");

        try {
            return callAI(prompt.toString(), usePortkey, 2000);
        } catch (Exception e) {
            logger.warning("[MONITOR-AI] AI analysis failed for " + taskName + ": " + e.getMessage());
            return "AI analysis failed: " + e.getMessage();
        }
    }

    // =========================================================================
    // Theme Detection
    // =========================================================================

    private List<Map<String, Object>> detectThemes(List<String> allSummaries,
                                                     List<Map<String, String>> errors) {
        if (allSummaries.size() < 2) {
            // Only one error — no cross-cutting themes to detect
            return Collections.emptyList();
        }

        boolean usePortkey = portkeyEnabled && portkeyApiKey != null && !portkeyApiKey.isEmpty();
        if (!usePortkey && (claudeApiKey == null || claudeApiKey.isEmpty())) {
            return Collections.emptyList();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are analyzing ").append(allSummaries.size())
              .append(" scheduled job failures from the Agile PLM Monitor at SanDisk.\n\n");
        prompt.append("## Error Summaries\n");
        for (String s : allSummaries) {
            prompt.append("- ").append(s).append("\n");
        }
        prompt.append("\n## Full Error Messages\n");
        for (Map<String, String> err : errors) {
            prompt.append("**").append(err.getOrDefault("taskName", "")).append("**: ");
            prompt.append(err.getOrDefault("errorMessage", "")).append("\n");
        }

        prompt.append("\n## Instructions\n");
        prompt.append("Group these errors by their UNDERLYING CAUSE (not by surface symptom).\n");
        prompt.append("For each theme, output exactly this format:\n");
        prompt.append("THEME: <short theme name>\n");
        prompt.append("JOBS: <comma-separated job names>\n");
        prompt.append("DESCRIPTION: <1-2 sentence explanation>\n");
        prompt.append("REMEDIATION: <single actionable step>\n\n");
        prompt.append("If all errors are unrelated, output: NO_COMMON_THEMES\n");
        prompt.append("Be specific to SanDisk Agile PLM infrastructure.\n");

        try {
            String response = callAI(prompt.toString(), usePortkey, 1000);
            return parseThemeResponse(response);
        } catch (Exception e) {
            logger.warning("[MONITOR-AI] Theme detection failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // Resolved Detection
    // =========================================================================

    private List<Map<String, String>> detectResolved(List<Map<String, String>> currentErrors,
                                                       List<Map<String, String>> previousErrors) {
        if (previousErrors == null || previousErrors.isEmpty()) return Collections.emptyList();

        // Build set of currently-erroring task names
        Set<String> currentErrorTasks = new HashSet<>();
        for (Map<String, String> err : currentErrors) {
            currentErrorTasks.add(err.getOrDefault("taskName", ""));
        }

        List<Map<String, String>> resolved = new ArrayList<>();
        for (Map<String, String> prev : previousErrors) {
            String taskName = prev.getOrDefault("taskName", "");
            String traceHash = prev.getOrDefault("traceHash", "");

            if (!taskName.isEmpty() && !currentErrorTasks.contains(taskName)) {
                // This task was failing before but isn't now — it's resolved
                String wasFailing = cacheService.getCachedSummary(traceHash);
                if (wasFailing == null) wasFailing = "unknown error";

                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("taskName", taskName);
                entry.put("wasFailing", wasFailing);
                entry.put("summary", "Previously failing with '" + wasFailing + "' — now running clean");
                resolved.add(entry);

                logger.info("[MONITOR-AI] Resolved: " + taskName + " (was: " + wasFailing + ")");
            }
        }
        return resolved;
    }

    // =========================================================================
    // Bitbucket Source Fetching
    // =========================================================================

    private String fetchSourceForError(String stackTrace, String repo) {
        List<DebugAssistantService.SourceRef> refs = debugService.extractSourceRefs(stackTrace);
        if (refs.isEmpty()) return null;

        StringBuilder allSource = new StringBuilder();
        // Fetch source for first few SanDisk refs
        int fetched = 0;
        for (DebugAssistantService.SourceRef ref : refs) {
            if (fetched >= 3) break; // limit to avoid huge docs

            String[] repoParts = repo.split("/", 2);
            String project = repoParts.length > 1 ? repoParts[0] : defaultBitbucketProject;
            String repoSlug = repoParts.length > 1 ? repoParts[1] : repo;

            String[] prefixes = {"src/", "src/main/java/", ""};
            for (String prefix : prefixes) {
                String path = prefix + ref.packagePath + "/" + ref.fileName;
                String content = debugService.fetchSourceFromBitbucket(project, repoSlug, path);
                if (content != null && content.length() > 10) {
                    allSource.append("// ").append(ref.fullClass).append(" (line ").append(ref.lineNumber).append(")\n");
                    // Extract ~20 lines around the error line
                    allSource.append(extractLinesAround(content, ref.lineNumber, 10));
                    allSource.append("\n\n");
                    fetched++;
                    break;
                }
            }
        }
        return allSource.length() > 0 ? allSource.toString() : null;
    }

    private String searchSourceAcrossRepos(String stackTrace, String javaClass) {
        List<DebugAssistantService.SourceRef> refs = debugService.extractSourceRefs(stackTrace);
        if (refs.isEmpty()) return null;

        // Try first SanDisk ref via cross-repo search
        for (DebugAssistantService.SourceRef ref : refs) {
            Map<String, String> found = debugService.findSourceAcrossRepos(defaultBitbucketProject, ref);
            if (found != null) {
                String content = found.get("content");
                return "// Found in " + found.get("repo") + "/" + found.get("path") + "\n"
                        + extractLinesAround(content, ref.lineNumber, 10);
            }
        }
        return null;
    }

    private String extractLinesAround(String content, int targetLine, int context) {
        String[] lines = content.split("\n");
        int start = Math.max(0, targetLine - context - 1);
        int end = Math.min(lines.length, targetLine + context);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            String marker = (i + 1 == targetLine) ? ">>> " : "    ";
            sb.append(marker).append(String.format("%4d", i + 1)).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    // =========================================================================
    // AI HTTP Call (Portkey / Anthropic dual-stack)
    // =========================================================================

    private String callAI(String prompt, boolean usePortkey, int maxTokens) throws Exception {
        String model = portkeyProvider + "/" + portkeyModel;
        return portkeyClient.chat(model, null, prompt, maxTokens);
    }

    // =========================================================================
    // Response Parsing (same pattern as DebugAssistantService)
    // =========================================================================

    private String extractAnthropicContent(String response) {
        int textIdx = response.indexOf("\"text\"");
        if (textIdx < 0) return "AI analysis returned an unexpected format.";
        int colonIdx = response.indexOf(":", textIdx + 5);
        if (colonIdx < 0) return "AI analysis returned an unexpected format.";
        int startQuote = response.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "AI analysis returned an unexpected format.";
        int endQuote = startQuote + 1;
        while (endQuote < response.length()) {
            char c = response.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        if (endQuote <= startQuote + 1) return "AI analysis returned an unexpected format.";
        return unescapeJson(response.substring(startQuote + 1, endQuote));
    }

    private String extractOpenAIContent(String response) {
        int msgIdx = response.indexOf("\"message\"");
        if (msgIdx < 0) return extractAnthropicContent(response);
        int contentIdx = response.indexOf("\"content\"", msgIdx);
        if (contentIdx < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int colonIdx = response.indexOf(":", contentIdx + 9);
        if (colonIdx < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int startQuote = response.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return "AI analysis returned an unexpected format (OpenAI).";
        int endQuote = startQuote + 1;
        while (endQuote < response.length()) {
            char c = response.charAt(endQuote);
            if (c == '\\') { endQuote += 2; continue; }
            if (c == '"') break;
            endQuote++;
        }
        if (endQuote <= startQuote + 1) return "AI analysis returned an unexpected format (OpenAI).";
        return unescapeJson(response.substring(startQuote + 1, endQuote));
    }

    // =========================================================================
    // Parsing Helpers
    // =========================================================================

    private boolean isSandiskClass(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("sandisk") || lower.contains("com.sandisk");
    }

    private String extractSeverity(String analysis) {
        if (analysis == null) return "UNKNOWN";
        // When the AI call itself failed, don't dress the analysis up with a
        // severity badge — the keyword scan would otherwise pick up "HIGH" /
        // "CRITICAL" out of the error string and mislabel the doc.
        if (analysis.startsWith("AI analysis failed")
                || analysis.startsWith("AI analysis unavailable")
                || analysis.startsWith("AI analysis returned an unexpected format")) {
            return "UNKNOWN";
        }
        // Look for "SEVERITY: HIGH" pattern
        int idx = analysis.indexOf("SEVERITY:");
        if (idx < 0) idx = analysis.indexOf("Severity:");
        if (idx < 0) idx = analysis.indexOf("severity:");
        if (idx >= 0) {
            String after = analysis.substring(idx + 9).trim();
            // Take first word
            String[] tokens = after.split("[\\s,.\n]", 2);
            if (tokens.length > 0) {
                String sev = tokens[0].replaceAll("[^A-Za-z]", "").toUpperCase();
                if (sev.equals("CRITICAL") || sev.equals("HIGH") || sev.equals("MEDIUM") || sev.equals("LOW")) {
                    return sev;
                }
            }
        }
        // Fallback: check for keywords
        String upper = analysis.toUpperCase();
        if (upper.contains("CRITICAL")) return "CRITICAL";
        if (upper.contains("HIGH")) return "HIGH";
        return "MEDIUM";
    }

    private String extractSummary(String analysis, String fallback) {
        if (analysis == null) return fallback;
        // Look for "SUMMARY: ..." pattern
        int idx = analysis.indexOf("SUMMARY:");
        if (idx < 0) idx = analysis.indexOf("Summary:");
        if (idx >= 0) {
            String after = analysis.substring(idx + 8).trim();
            int newline = after.indexOf("\n");
            if (newline > 0) after = after.substring(0, newline);
            if (after.length() > 100) after = after.substring(0, 100) + "...";
            return after.trim();
        }
        // Fallback: use first non-empty line
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.length() > 100 ? fallback.substring(0, 100) + "..." : fallback;
        }
        return "Error analysis available in attached document";
    }

    private String extractUnmappedGuess(String analysis) {
        if (analysis == null) return "";
        int idx = analysis.indexOf("UNMAPPED INFERENCE:");
        if (idx >= 0) {
            String after = analysis.substring(idx + 19).trim();
            int endIdx = after.indexOf("\n\n");
            if (endIdx > 0) return after.substring(0, endIdx).trim();
            return after.length() > 300 ? after.substring(0, 300) + "..." : after;
        }
        return "";
    }

    private List<Map<String, Object>> parseThemeResponse(String response) {
        List<Map<String, Object>> themes = new ArrayList<>();
        if (response == null || response.contains("NO_COMMON_THEMES")) return themes;

        String[] blocks = response.split("THEME:");
        for (int i = 1; i < blocks.length; i++) { // skip first empty block
            String block = blocks[i].trim();
            Map<String, Object> theme = new LinkedHashMap<>();

            // Extract theme name (first line)
            int firstNewline = block.indexOf("\n");
            theme.put("theme", firstNewline > 0 ? block.substring(0, firstNewline).trim() : block.trim());

            // Extract JOBS
            int jobsIdx = block.indexOf("JOBS:");
            if (jobsIdx >= 0) {
                String after = block.substring(jobsIdx + 5);
                int nl = after.indexOf("\n");
                String jobsStr = nl > 0 ? after.substring(0, nl).trim() : after.trim();
                List<String> jobs = new ArrayList<>();
                for (String j : jobsStr.split(",")) {
                    j = j.trim();
                    if (!j.isEmpty()) jobs.add(j);
                }
                theme.put("jobs", jobs);
            }

            // Extract DESCRIPTION
            int descIdx = block.indexOf("DESCRIPTION:");
            if (descIdx >= 0) {
                String after = block.substring(descIdx + 12);
                int nl = after.indexOf("\n");
                theme.put("description", (nl > 0 ? after.substring(0, nl) : after).trim());
            }

            // Extract REMEDIATION
            int remIdx = block.indexOf("REMEDIATION:");
            if (remIdx >= 0) {
                String after = block.substring(remIdx + 12);
                int nl = after.indexOf("\n");
                theme.put("remediation", (nl > 0 ? after.substring(0, nl) : after).trim());
            }

            themes.add(theme);
        }
        return themes;
    }

    private Map<String, Object> parseCachedAnalysis(String json, String taskName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskName", taskName);
        result.put("summary", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "summary")));
        result.put("severity", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "severity")));
        result.put("analysis", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "analysis")));
        result.put("diagnosis", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "diagnosis")));
        result.put("evidence", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "evidence")));
        result.put("related", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "related")));
        result.put("quickCommand", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "quickCommand")));
        // fixSteps from cache — return empty list (cache doesn't store array well with simple parser)
        result.put("fixSteps", new ArrayList<>());
        result.put("docxBase64", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "docxBase64")));
        result.put("traceHash", nonNull(MonitorAnalysisCacheService.extractJsonField(json, "traceHash")));
        return result;
    }

    // =========================================================================
    // Structured Section Extraction
    // =========================================================================

    private String extractSection(String analysis, String sectionName) {
        if (analysis == null) return "";
        int idx = analysis.indexOf(sectionName + ":");
        if (idx < 0) idx = analysis.indexOf(sectionName.toLowerCase() + ":");
        if (idx < 0) return "";
        String after = analysis.substring(idx + sectionName.length() + 1).trim();
        String[] nextSections = {"SUMMARY:", "SEVERITY:", "DIAGNOSIS:", "EVIDENCE:", "FIX_STEPS:", "RELATED:", "QUICK_COMMAND:", "UNMAPPED"};
        int endIdx = after.length();
        for (String ns : nextSections) {
            int nsi = after.indexOf("\n" + ns);
            if (nsi > 0 && nsi < endIdx) endIdx = nsi;
            nsi = after.indexOf("\n" + ns.toLowerCase());
            if (nsi > 0 && nsi < endIdx) endIdx = nsi;
        }
        return after.substring(0, endIdx).trim();
    }

    private List<Map<String, String>> extractFixSteps(String analysis) {
        List<Map<String, String>> steps = new ArrayList<>();
        String fixSection = extractSection(analysis, "FIX_STEPS");
        if (fixSection.isEmpty()) return steps;
        String[] lines = fixSection.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            line = line.replaceAll("^\\d+\\.\\s*", "");
            if (line.isEmpty()) continue;
            Map<String, String> step = new LinkedHashMap<>();
            int cmdIdx = line.indexOf("[COMMAND:");
            if (cmdIdx >= 0) {
                int cmdEnd = line.indexOf("]", cmdIdx);
                if (cmdEnd > cmdIdx) {
                    step.put("command", line.substring(cmdIdx + 9, cmdEnd).trim());
                    step.put("text", line.substring(0, cmdIdx).trim());
                } else {
                    step.put("text", line);
                }
            } else {
                step.put("text", line);
            }
            steps.add(step);
        }
        return steps;
    }

    // =========================================================================
    // JSON Helpers (no external lib — matches existing codebase pattern)
    // =========================================================================

    private String buildCacheJson(String taskName, String summary, String severity,
                                   String analysis, String docxBase64, String traceHash,
                                   String diagnosis, String evidence, String related,
                                   String quickCommand, List<Map<String, String>> fixSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"taskName\": ").append(escapeJsonString(taskName)).append(",\n");
        sb.append("  \"summary\": ").append(escapeJsonString(summary)).append(",\n");
        sb.append("  \"severity\": ").append(escapeJsonString(severity)).append(",\n");
        sb.append("  \"analysis\": ").append(escapeJsonString(analysis != null ? analysis : "")).append(",\n");
        sb.append("  \"diagnosis\": ").append(escapeJsonString(diagnosis)).append(",\n");
        sb.append("  \"evidence\": ").append(escapeJsonString(evidence)).append(",\n");
        sb.append("  \"related\": ").append(escapeJsonString(related)).append(",\n");
        sb.append("  \"quickCommand\": ").append(escapeJsonString(quickCommand)).append(",\n");
        sb.append("  \"fixSteps\": [");
        for (int i = 0; i < fixSteps.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, String> step = fixSteps.get(i);
            sb.append("{\"text\":").append(escapeJsonString(step.getOrDefault("text", "")));
            if (step.containsKey("command")) {
                sb.append(",\"command\":").append(escapeJsonString(step.get("command")));
            }
            sb.append("}");
        }
        sb.append("],\n");
        sb.append("  \"docxBase64\": ").append(escapeJsonString(docxBase64 != null ? docxBase64 : "")).append(",\n");
        sb.append("  \"traceHash\": ").append(escapeJsonString(traceHash)).append(",\n");
        sb.append("  \"cachedAt\": ").append(escapeJsonString(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()))).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String nonNull(String s) {
        return s != null ? s : "";
    }
}
