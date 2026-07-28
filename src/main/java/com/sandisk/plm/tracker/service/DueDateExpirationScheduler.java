package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * Daily refresh for the Due Date Expiration view. Re-runs the operational query,
 * refreshes the cache, and appends today's backlog snapshot (which feeds the
 * backlog-over-time chart).
 *
 * <p>Gated globally by {@code app.scheduling.disabled} — the whole scheduling
 * subsystem is off on local dev (via {@code SchedulingConfig} / {@code Application}),
 * so this never fires there. The cron is server-local time.
 */
@Service
public class DueDateExpirationScheduler {

    private static final Logger logger = Logger.getLogger(DueDateExpirationScheduler.class.getName());

    @Autowired private DueDateExpirationService dueDateService;
    @Autowired(required = false) private MaintenanceService maintenanceService;

    /** Daily 6:00 AM server-local. Refreshes the operational (non-PROJ) population
     *  and writes one snapshot per day. */
    @Scheduled(cron = "${app.duedate.refresh-cron:0 0 6 * * *}")
    public void dailyRefresh() {
        if (maintenanceService != null && maintenanceService.isInMaintenanceMode()) {
            logger.info("[DUEDATE-CRON] Skipping — app is in maintenance mode.");
            return;
        }
        try {
            dueDateService.scheduledRefresh(); // operational view + authoritative daily snapshot
            logger.info("[DUEDATE-CRON] daily refresh + snapshot complete.");
        } catch (Exception e) {
            logger.warning("[DUEDATE-CRON] daily refresh failed: " + e.getMessage());
        }
    }
}
