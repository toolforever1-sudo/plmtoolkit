package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ScheduledReport;
import com.sandisk.plm.tracker.service.SavedSearchService;
import com.sandisk.plm.tracker.service.ScheduledReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/searches")
public class SavedSearchController {

    @Autowired
    private SavedSearchService savedSearchService;

    @Autowired
    private ScheduledReportService scheduledReportService;

    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        String username = (String) session.getAttribute("username");
        Map<String, Object> result = new LinkedHashMap<>();
        if (username == null) {
            result.put("mine", new ArrayList<>());
            result.put("shared", new ArrayList<>());
            return result;
        }
        result.put("mine", savedSearchService.getSearches(username));
        result.put("shared", savedSearchService.getSharedSearches(username));
        return result;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody SavedSearchService.SavedSearch search,
                                     HttpSession session) {
        String username = (String) session.getAttribute("username");
        Map<String, Object> response = new LinkedHashMap<>();
        if (username == null) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }
        search.owner = username;
        search.ownerDisplay = (String) session.getAttribute("displayName");
        savedSearchService.saveSearch(username, search);
        response.put("success", true);
        response.put("id", search.id);
        return response;
    }

    @PutMapping("/{id}/share")
    public Map<String, Object> toggleShare(@PathVariable String id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        Map<String, Object> response = new LinkedHashMap<>();
        if (username == null) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }
        SavedSearchService.SavedSearch updated = savedSearchService.toggleShare(username, id, displayName);
        if (updated == null) {
            response.put("success", false);
            response.put("message", "Search not found.");
            return response;
        }
        response.put("success", true);
        response.put("shared", updated.shared);
        return response;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        Map<String, Object> response = new LinkedHashMap<>();
        if (username == null) {
            response.put("success", false);
            return response;
        }

        // Look up the saved search params BEFORE deleting, so we can match scheduled reports
        SavedSearchService.SavedSearch toDelete = null;
        for (SavedSearchService.SavedSearch s : savedSearchService.getSearches(username)) {
            if (s.id != null && s.id.equals(id)) {
                toDelete = s;
                break;
            }
        }

        savedSearchService.deleteSearch(username, id);

        // Clean up any scheduled reports with matching search params
        int schedulesRemoved = 0;
        if (toDelete != null && toDelete.params != null) {
            List<ScheduledReport> schedules = scheduledReportService.getSchedules(username);
            for (ScheduledReport sr : new ArrayList<>(schedules)) {
                if (sr.searchParams != null && sr.searchParams.equals(toDelete.params)) {
                    scheduledReportService.deleteSchedule(username, sr.id);
                    schedulesRemoved++;
                }
            }
        }

        response.put("success", true);
        if (schedulesRemoved > 0) {
            response.put("schedulesRemoved", schedulesRemoved);
            response.put("message", "Saved search and " + schedulesRemoved +
                " scheduled report" + (schedulesRemoved > 1 ? "s" : "") + " deleted.");
        }
        return response;
    }
}
