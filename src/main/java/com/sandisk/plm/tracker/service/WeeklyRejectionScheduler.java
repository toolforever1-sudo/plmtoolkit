package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduled job for the Weekly Rejection Report.
 *
 * <p>Monday 07:00 server-local: re-runs {@code weekly_rejection_report.py} for the
 * previous 7 days with email enabled, so the script emails the report (HTML summary +
 * Excel) to the configured owner. Recipients management is a follow-up — this iteration
 * sends to a single owner address.
 *
 * <p>Gated by {@code app.scheduling.disabled} at the {@code @EnableScheduling} level
 * (local dev runs with scheduling off, so this never fires there) and additionally
 * skipped during maintenance mode.
 */
@Service
public class WeeklyRejectionScheduler {

    private static final Logger logger = Logger.getLogger(WeeklyRejectionScheduler.class.getName());

    @Autowired private WeeklyRejectionService weeklyService;
    @Autowired private MaintenanceService maintenanceService;

    @Value("${app.rejection-report.lookback-days:7}") private int lookbackDays;
    @Value("${app.rejection-report.weekly-recipient:vikas.jindal@sandisk.com}") private String weeklyRecipient;

    @Scheduled(cron = "0 0 7 * * MON")
    public void weeklyEmail() {
        if (maintenanceService.isInMaintenanceMode()) {
            logger.info("[REJECT-REPORT-CRON] Skipping weekly email — app is in maintenance mode.");
            return;
        }
        try {
            logger.info("[REJECT-REPORT-CRON] Starting weekly run (" + lookbackDays + "d) → " + weeklyRecipient);
            int exit = weeklyService.runReport(lookbackDays, weeklyRecipient,
                    line -> logger.info("[REJECT-REPORT-CRON] " + line));
            if (exit == 0) {
                logger.info("[REJECT-REPORT-CRON] Weekly run complete, emailed " + weeklyRecipient);
            } else {
                logger.warning("[REJECT-REPORT-CRON] weekly_rejection_report.py exited " + exit);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[REJECT-REPORT-CRON] Weekly job failed", e);
        }
    }
}
