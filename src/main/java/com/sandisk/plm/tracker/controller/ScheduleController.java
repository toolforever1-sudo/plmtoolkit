package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ScheduledReport;
import com.sandisk.plm.tracker.service.ScheduledReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    @Autowired
    private ScheduledReportService scheduledReportService;

    @GetMapping
    public List<ScheduledReport> getSchedules(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return new ArrayList<>();
        return scheduledReportService.getSchedules(username);
    }

    @PostMapping
    public Map<String, Object> createSchedule(@RequestBody ScheduledReport schedule, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        String email = (String) session.getAttribute("email");
        String displayName = (String) session.getAttribute("displayName");

        if (username == null) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }

        // Use session email and displayName
        if (schedule.email == null || schedule.email.isEmpty()) {
            schedule.email = email;
        }
        schedule.displayName = displayName;

        ScheduledReport created = scheduledReportService.createSchedule(username, schedule);
        response.put("success", true);
        response.put("schedule", created);
        return response;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        if (username == null) {
            response.put("success", false);
            return response;
        }
        boolean deleted = scheduledReportService.deleteSchedule(username, id);
        response.put("success", deleted);
        return response;
    }
}
