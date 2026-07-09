package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.MonitorAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.logging.Logger;

/**
 * REST endpoint for the Scheduled Job Monitor to submit error batches for AI analysis.
 * Server-to-server authentication via X-Monitor-Api-Key header.
 *
 * POST /api/monitor/analyze-errors
 *   Request body (JSON):
 *   {
 *     "errors": [
 *       { "taskName", "stackTrace", "errorMessage", "javaClass", "repo", "commitInfo", "excerptText" }
 *     ],
 *     "previousErrors": [
 *       { "taskName", "traceHash" }
 *     ]
 *   }
 *
 *   Response (JSON):
 *   {
 *     "analyses": [ { "taskName", "summary", "severity", "analysis", "docxBase64", "traceHash" } ],
 *     "themes": [ { "theme", "jobs", "description", "remediation" } ],
 *     "resolved": [ { "taskName", "wasFailing", "summary" } ],
 *     "unmappedNotes": [ { "taskName", "javaClass", "aiGuess" } ]
 *   }
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorAnalysisController {

    private static final Logger logger = Logger.getLogger(MonitorAnalysisController.class.getName());

    @Autowired
    private MonitorAnalysisService analysisService;

    @Autowired
    private ActivityLogger activityLogger;

    @Value("${monitor.api-key:monitor-default-key}")
    private String expectedApiKey;

    @PostMapping("/analyze-errors")
    public ResponseEntity<?> analyzeErrors(@RequestBody Map<String, Object> request,
                                           @RequestHeader(value = "X-Monitor-Api-Key", required = false) String apiKey,
                                           @RequestParam(value = "cachedOnly", required = false, defaultValue = "false") boolean cachedOnly) {

        // Server-to-server auth
        if (apiKey == null || !apiKey.equals(expectedApiKey)) {
            logger.warning("[MONITOR-API] Unauthorized request — invalid or missing API key");
            return ResponseEntity.status(401).body(Collections.singletonMap("error", "Unauthorized"));
        }

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> errors = (List<Map<String, String>>) request.get("errors");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> previousErrors = (List<Map<String, String>>) request.get("previousErrors");

            if (errors == null) errors = Collections.emptyList();

            // Allow empty errors when previousErrors exist (resolved detection)
            if (errors.isEmpty() && (previousErrors == null || previousErrors.isEmpty())) {
                return ResponseEntity.ok(Collections.singletonMap("message", "No errors to analyze"));
            }

            logger.info("[MONITOR-API] Received " + errors.size() + " errors for analysis"
                    + (cachedOnly ? " (CACHED-ONLY mode)" : "")
                    + (previousErrors != null ? " (" + previousErrors.size() + " previous)" : ""));

            // Log activity
            activityLogger.log("MonitorLog", "Scheduled Job Monitor", "monitor-ai-analysis",
                    errors.size() + " errors submitted" + (cachedOnly ? " (cached-only)" : " for AI analysis"));

            Map<String, Object> result = analysisService.analyzeBatch(errors, previousErrors, cachedOnly);

            List<?> analyses = (List<?>) result.get("analyses");
            List<?> themes = (List<?>) result.get("themes");
            List<?> resolved = (List<?>) result.get("resolved");
            List<?> uncached = (List<?>) result.get("uncachedTasks");

            logger.info("[MONITOR-API] Analysis complete: "
                    + (analyses != null ? analyses.size() : 0) + " analyses, "
                    + (themes != null ? themes.size() : 0) + " themes, "
                    + (resolved != null ? resolved.size() : 0) + " resolved"
                    + (uncached != null && !uncached.isEmpty() ? ", " + uncached.size() + " uncached" : ""));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.warning("[MONITOR-API] Analysis failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Collections.singletonMap("error", "Analysis failed: " + e.getMessage()));
        }
    }
}
