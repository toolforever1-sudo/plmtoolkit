package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * Monthly scheduled run of the Single/Sole Source report.
 * Honors the global {@code app.scheduling.disabled} flag and skips when
 * MaintenanceService reports the app is in maintenance mode.
 */
@Service
public class SingleSoleSourceScheduler {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceScheduler.class.getName());

    @Autowired private SingleSoleSourceService service;
    @Autowired(required = false) private MaintenanceService maintenanceService;

    @Scheduled(cron = "${app.singlesole.schedule.cron}")
    public void monthlyRun() {
        if (maintenanceService != null && maintenanceService.isInMaintenanceMode()) {
            logger.info("[SS-CRON] skipping — maintenance mode");
            return;
        }
        logger.info("[SS-CRON] starting monthly Single/Sole Source report");
        SingleSoleSourceRunResult r = service.runReport("schedule", "system", true, true);
        logger.info("[SS-CRON] done — needed=" + r.designationNeededCount
                + " provided=" + r.designationProvidedCount
                + " (single=" + r.singleSourceCount + " sole=" + r.soleSourceCount + ")"
                + " sharepoint=" + r.sharepointUploaded
                + " email=" + r.emailSent
                + " duration=" + r.durationMs + "ms");
    }
}
