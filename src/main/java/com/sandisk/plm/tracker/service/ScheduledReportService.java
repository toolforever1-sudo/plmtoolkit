package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.ChangeRecord;
import com.sandisk.plm.tracker.model.ScheduledReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class ScheduledReportService {

    private static final Logger logger = Logger.getLogger(ScheduledReportService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.scheduled-reports.file:./data/scheduled-reports.json}")
    private String filePath;

    @Autowired
    private ChangeQueryService changeQueryService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MaintenanceService maintenanceService;

    // username -> list of scheduled reports
    private final ConcurrentHashMap<String, List<ScheduledReport>> userSchedules = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
        logger.info("[SCHEDULER] Loaded " + countAll() + " scheduled reports");
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    public List<ScheduledReport> getSchedules(String username) {
        return userSchedules.getOrDefault(username, new ArrayList<>());
    }

    public ScheduledReport createSchedule(String username, ScheduledReport schedule) {
        schedule.id = java.util.UUID.randomUUID().toString().substring(0, 8);
        schedule.username = username;
        schedule.active = true;
        schedule.createdAt = System.currentTimeMillis();
        userSchedules.computeIfAbsent(username, k -> new ArrayList<>()).add(schedule);
        saveToDisk();
        logger.info("[SCHEDULER] Created schedule " + schedule.id + " for " + username +
                " (" + schedule.frequency + " at " + schedule.timeOfDay + ")");
        return schedule;
    }

    public boolean deleteSchedule(String username, String scheduleId) {
        List<ScheduledReport> list = userSchedules.get(username);
        if (list != null) {
            boolean removed = list.removeIf(s -> s.id.equals(scheduleId));
            if (removed) {
                saveToDisk();
                logger.info("[SCHEDULER] Deleted schedule " + scheduleId + " for " + username);
            }
            return removed;
        }
        return false;
    }

    // =========================================================================
    // Scheduler loop — runs every 5 minutes
    // =========================================================================

    @Scheduled(fixedDelay = 300000)
    public void runDueSchedules() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[SCHEDULER] Skipping tick — app is in maintenance mode (no emails sent).");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int totalSchedules = countAll();
        if (totalSchedules > 0) {
            logger.info("[SCHEDULER] Tick at " + now + " — checking " + totalSchedules + " schedules");
        }
        int ran = 0;

        for (Map.Entry<String, List<ScheduledReport>> entry : userSchedules.entrySet()) {
            for (ScheduledReport schedule : entry.getValue()) {
                if (!schedule.active) continue;
                if (!isDue(schedule, now)) continue;

                ran++;
                executeSchedule(schedule);
            }
        }

        if (ran > 0) {
            saveToDisk(); // persist updated lastRun timestamps
        }
    }

    boolean isDue(ScheduledReport schedule, LocalDateTime now) {
        // Parse scheduled time
        String[] parts = schedule.timeOfDay.split(":");
        int scheduledHour = Integer.parseInt(parts[0]);
        int scheduledMinute = Integer.parseInt(parts[1]);

        // Not yet reached the scheduled time today?
        LocalTime scheduledTime = LocalTime.of(scheduledHour, scheduledMinute);
        if (now.toLocalTime().isBefore(scheduledTime)) return false;

        // For weekly: check day of week
        if ("weekly".equals(schedule.frequency)) {
            DayOfWeek targetDay = DayOfWeek.valueOf(schedule.dayOfWeek.toUpperCase());
            if (now.getDayOfWeek() != targetDay) return false;
        }

        // Check if already ran today (daily) or this week (weekly) at/after the scheduled time
        if (schedule.lastRun != null) {
            LocalDateTime lastRunTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(schedule.lastRun), ZoneId.systemDefault());
            LocalDateTime todayScheduled = now.toLocalDate().atTime(scheduledTime);

            if ("daily".equals(schedule.frequency)) {
                // Already ran today at or after scheduled time
                if (!lastRunTime.isBefore(todayScheduled)) return false;
            } else {
                // Weekly: already ran this week on the target day
                if (!lastRunTime.isBefore(todayScheduled)) return false;
            }
        }

        return true;
    }

    private void executeSchedule(ScheduledReport schedule) {
        try {
            Map<String, String> p = schedule.searchParams;
            String field = p.getOrDefault("field", null);
            String item = p.getOrDefault("item", null);
            String user = p.getOrDefault("user", null);
            int days = Integer.parseInt(p.getOrDefault("days", "7"));
            String oldContains = p.getOrDefault("oldContains", null);
            String newContains = p.getOrDefault("newContains", null);
            boolean netFilter = Boolean.parseBoolean(p.getOrDefault("netFilter", "true"));

            // Treat empty strings as null (same as controller)
            if (field != null && field.isEmpty()) field = null;
            if (item != null && item.isEmpty()) item = null;
            if (user != null && user.isEmpty()) user = null;
            if (oldContains != null && oldContains.isEmpty()) oldContains = null;
            if (newContains != null && newContains.isEmpty()) newContains = null;

            ChangeQueryService.SearchResult result =
                    changeQueryService.search(field, item, user, days, oldContains, newContains, netFilter);

            List<ChangeRecord> records = result.getResults();

            if (records.isEmpty()) {
                logger.info("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                        ": 0 results, email skipped");
            } else {
                // Build filter summary for email body
                StringBuilder filters = new StringBuilder();
                if (field != null) filters.append("Field: ").append(field).append("; ");
                if (item != null) filters.append("Item: ").append(item).append("; ");
                if (oldContains != null) filters.append("Old: ").append(oldContains).append("; ");
                if (newContains != null) filters.append("New: ").append(newContains).append("; ");
                if (user != null) filters.append("By: ").append(user).append("; ");
                filters.append("Days: ").append(days);

                String displayName = schedule.displayName != null ? schedule.displayName : schedule.username;
                emailService.sendExcelReport(schedule.email, displayName, records, filters.toString());

                logger.info("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                        ": " + records.size() + " results emailed to " + schedule.email);
            }

            schedule.lastRun = System.currentTimeMillis();

        } catch (Exception e) {
            logger.warning("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                    " failed: " + e.getMessage());
            // Update lastRun even on failure to prevent retry storm
            schedule.lastRun = System.currentTimeMillis();
        }
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, List<ScheduledReport>> data = mapper.readValue(f,
                    new TypeReference<Map<String, List<ScheduledReport>>>() {});
            userSchedules.putAll(data);
        } catch (Exception e) {
            logger.warning("[SCHEDULER] Failed to load schedules: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, userSchedules);
        } catch (Exception e) {
            logger.warning("[SCHEDULER] Failed to save schedules: " + e.getMessage());
        }
    }

    private int countAll() {
        int count = 0;
        for (List<ScheduledReport> list : userSchedules.values()) count += list.size();
        return count;
    }
}
