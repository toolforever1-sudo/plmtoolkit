package com.sandisk.plm.tracker.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Reference-data endpoints used by filter pickers on the BOM Explorer (and
 * potentially other tabs). Values are cached per-process for 30 minutes — the
 * underlying tables don't change between deploys often enough to warrant a hit
 * per page load.
 */
@RestController
@RequestMapping("/api/refdata")
public class RefdataController {

    private static final Logger logger = Logger.getLogger(RefdataController.class.getName());

    private final DataSource customDataSource;

    public RefdataController(@Qualifier("customDataSource") DataSource customDataSource) {
        this.customDataSource = customDataSource;
    }

    private volatile long cachedAt = 0;
    private volatile Map<String, Object> cached = null;
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(30);

    @GetMapping("/bom-filters")
    public Map<String, Object> bomFilters() {
        long now = System.nanoTime();
        Map<String, Object> c = cached;
        if (c != null && (now - cachedAt) < TTL_NANOS) return c;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lifecycles", distinct("LIFECYCLE_PHASE"));
        out.put("partTypes",  distinct("NEW_PART_CLASS"));
        cached = out;
        cachedAt = now;
        return out;
    }

    private List<String> distinct(String col) {
        // item_extract has ~778K rows; DISTINCT on these low-cardinality columns
        // is sub-second and we cache for 30 min. Oracle treats '' as NULL, so the
        // IS NOT NULL guard is sufficient — don't add a TRIM(col) <> '' compare,
        // it always evaluates to UNKNOWN and silently nukes every row.
        String sql = "SELECT DISTINCT " + col + " AS v FROM item_extract WHERE "
                + col + " IS NOT NULL ORDER BY 1";
        List<String> values = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(20);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString("v");
                    if (v != null && !v.trim().isEmpty()) values.add(v.trim());
                }
            }
        } catch (SQLException e) {
            logger.warning("[REFDATA] distinct " + col + " failed: " + e.getMessage());
            return Collections.emptyList();
        }
        return values;
    }
}
