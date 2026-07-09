package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduled jobs for the Returns Tracker leadership emails.
 *
 * Both jobs:
 *   1. Spawn rejection_tracker.py to fetch any new rejection events + AI-categorize
 *      + regenerate the narrative for the relevant window
 *   2. Send the leadership email via {@link RejectionTrackerEmailService}
 *
 * Gated by {@code app.scheduling.disabled} (the entire scheduling subsystem is
 * disabled on local dev — this service simply doesn't run there).
 *
 * Cron expressions are 6-field Spring style (sec min hour dom mon dow). 7 AM SGT
 * is server-local time; the prod box runs in SGT, so this matches Noraida's morning.
 */
@Service
public class RejectionTrackerScheduler {

    private static final Logger logger = Logger.getLogger(RejectionTrackerScheduler.class.getName());

    @Autowired private RejectionTrackerEmailService emailService;
    @Autowired private MaintenanceService maintenanceService;
    @Autowired private RejectionSnapshotService snapshotService;

    @Value("${app.reports.python:python}") private String pythonExe;

    /**
     * Weekly Monday 7:00 AM. Window = previous 7 days (Sun→Sat or Mon→Sun depending
     * on convention; we use rolling 7 days back from "today" for simplicity, which
     * lines up with "events from last week" the way leadership reads it).
     */
    @Scheduled(cron = "0 0 7 * * MON")
    public void weeklyEmail() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[RETURNS-CRON] Skipping weekly email — app is in maintenance mode.");
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(7);
        LocalDate to = today.minusDays(1);
        runAndSend("7d", from, to, "");
        snapshotService.writeSnapshot(RejectionSnapshotService.weekId(to), from, to);
    }

    /**
     * Monthly 1st of month 7:00 AM. Window = previous calendar month.
     */
    @Scheduled(cron = "0 0 7 1 * *")
    public void monthlyEmail() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[RETURNS-CRON] Skipping monthly email — app is in maintenance mode.");
            return;
        }
        LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate to = firstOfThisMonth.minusDays(1);
        LocalDate from = to.withDayOfMonth(1);
        String key = from.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        runAndSend(key, from, to, "");
        snapshotService.writeSnapshot(RejectionSnapshotService.monthId(from), from, to);
        // On quarter boundaries (Jan/Apr/Jul/Oct run → prior month is Mar/Jun/Sep/Dec),
        // also freeze the just-completed quarter.
        if (to.getMonthValue() % 3 == 0) {
            LocalDate[] qb = RejectionSnapshotService.quarterBounds(
                to.getYear(), (to.getMonthValue() - 1) / 3 + 1);
            snapshotService.writeSnapshot(RejectionSnapshotService.quarterId(qb[0]), qb[0], qb[1]);
        }
    }

    private void runAndSend(String windowKey, LocalDate from, LocalDate to, String subjectSuffix) {
        try {
            logger.info("[RETURNS-CRON] Starting " + windowKey + " job for " + from + " → " + to);
            // Phase 1: spawn rejection_tracker.py incremental + narrative for this window
            ProcessBuilder pb = new ProcessBuilder(
                pythonExe, "rejection_tracker.py",
                "--config", "ecn_report.properties",
                "--narrative-window", windowKey
            );
            pb.directory(new File("./data/ecn-report"));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    logger.info("[RETURNS-CRON] " + line);
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                logger.warning("[RETURNS-CRON] rejection_tracker.py exited " + exit
                    + " — sending email anyway with whatever's in cache");
            }
            // Phase 2: send the email (uses configured recipients)
            int sent = emailService.sendForWindow(from, to, null, subjectSuffix);
            logger.info("[RETURNS-CRON] Sent " + sent + " emails for window " + windowKey);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[RETURNS-CRON] " + windowKey + " job failed", e);
        }
    }
}
