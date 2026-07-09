package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.BomRaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.http.HttpHeaders;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/bomrace")
public class BomRaceController {

    @Autowired private BomRaceService raceService;
    @Autowired private ActivityLogger activityLogger;

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));

        if (!raceService.isAgileServiceUp()) {
            return ResponseEntity.status(503).body(mapOf(
                "ok", false, "reason", "agile-service-unreachable",
                "message", "Agile lookup service is not reachable. The race can't start without it."));
        }

        String mode = String.valueOf(body.getOrDefault("mode", "random"));
        int n = ((Number) body.getOrDefault("n", raceService.getMaxItems())).intValue();
        int maxDepth = ((Number) body.getOrDefault("maxDepth", 20)).intValue();
        Integer year = body.get("year") instanceof Number ? ((Number) body.get("year")).intValue() : null;

        List<String> items;
        if ("upload".equals(mode)) {
            @SuppressWarnings("unchecked")
            List<String> up = (List<String>) body.getOrDefault("items", Collections.emptyList());
            items = up.subList(0, Math.min(up.size(), raceService.getMaxItems()));
        } else {
            items = raceService.sampleRandomItemsWithBoms(n, year);
        }
        if (items.isEmpty()) {
            return ResponseEntity.status(400).body(err("No items to race."));
        }

        String runId = UUID.randomUUID().toString();
        raceService.preStage(runId, items, maxDepth, s(session, "username"));

        activityLogger.log(s(session, "username"), s(session, "displayName"),
            "BOM_RACE_START", "runId=" + runId + " mode=" + mode + " n=" + items.size());

        return ResponseEntity.ok(mapOf("runId", runId, "items", items));
    }

    /** Last N completed runs for the leaderboard. Default 5, max 50. */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(defaultValue = "5") int limit, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        return ResponseEntity.ok(raceService.recentRuns(Math.min(Math.max(1, limit), 50)));
    }

    /** xlsx download. `which` is one of: "input" (single sheet of raced part numbers) or
     *  "results" (two sheets: Toolkit output + Agile SDK output). Available for ~10 minutes
     *  after race-done (until the lazy run-TTL sweep evicts the run). */
    @GetMapping("/{runId}/download/{which}")
    public ResponseEntity<?> download(@PathVariable String runId, @PathVariable String which,
                                      HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(err("Admin access required."));
        byte[] body;
        switch (which == null ? "" : which) {
            case "input":   body = raceService.exportInputXlsx(runId);   break;
            case "results": body = raceService.exportResultsXlsx(runId); break;
            default:        return ResponseEntity.status(400).body(err("'which' must be 'input' or 'results'."));
        }
        if (body == null) {
            return ResponseEntity.status(404).body(err("Run not found or expired."));
        }
        String shortId = runId.substring(0, Math.min(8, runId.length()));
        String filename = "bom-race-" + which + "-" + shortId + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(body);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, HttpSession session) {
        if (!isAdmin(session)) {
            SseEmitter em = new SseEmitter(5_000L);
            try { em.send(SseEmitter.event().name("error").data(err("Admin access required."))); }
            catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return raceService.startStagedRace(runId);
    }

    private static boolean isAdmin(HttpSession s) {
        Boolean v = (Boolean) s.getAttribute("isPlmAdmin");
        return v != null && v;
    }
    private static String s(HttpSession s, String k) {
        Object v = s.getAttribute(k); return v == null ? "" : v.toString();
    }
    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("error", msg); return m;
    }
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
