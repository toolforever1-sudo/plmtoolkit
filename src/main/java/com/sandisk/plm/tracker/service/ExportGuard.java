package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server-side guard limiting concurrent Excel exports to 1 at a time.
 * Prevents OOM from multiple SXSSFWorkbook instances in parallel.
 */
@Service
public class ExportGuard {

    private static final Logger logger = Logger.getLogger(ExportGuard.class.getName());
    private final Semaphore permit = new Semaphore(1);

    /**
     * Try to acquire the export lock. Returns true if acquired, false if another
     * export is already running (caller should return 429 / busy message).
     */
    public boolean tryAcquire() {
        try {
            boolean got = permit.tryAcquire(0, TimeUnit.SECONDS);
            if (!got) {
                logger.info("[EXPORT-GUARD] Blocked concurrent export — another export is in progress");
            }
            return got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Release the export lock. Must be called in a finally block.
     */
    public void release() {
        permit.release();
    }
}
