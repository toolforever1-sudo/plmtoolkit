package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomFilters;
import com.sandisk.plm.tracker.model.BomResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class BomDataService {

    private static final Logger logger = Logger.getLogger(BomDataService.class.getName());

    private final DataSource customDataSource;
    private final BomExtractFileService fileFallback;

    public BomDataService(@Qualifier("customDataSource") DataSource customDataSource,
                          BomExtractFileService fileFallback) {
        this.customDataSource = customDataSource;
        this.fileFallback = fileFallback;
    }

    // Items whose SQL traversal exceeded the row cap and were re-served from the
    // offline BOM-Extract file. Surfaced to the UI as a "data as of …" callout
    // so users know they're not looking at live data for those branches.
    private volatile List<String> bomFromFileItems = new ArrayList<>();
    private volatile String bomFromFileAsOf = "";
    public List<String> getBomFromFileItems() { return bomFromFileItems; }
    public String getBomFromFileAsOf() { return bomFromFileAsOf; }

    /** Threshold over which we'll attempt the offline-file fallback when SQL caps out. */
    private static final int FILE_FALLBACK_MIN_DEPTH = 10;

    // =========================================================================
    // BOM Explosion (top-down) using CONNECT BY
    // =========================================================================

    public List<BomResult> explode(String itemNumber, int maxDepth) {
        // Deep-explode short-circuit: bom_extract recursion at depth>10 routinely
        // hits the 50K-row cap (or times out) and returns nothing useful. Route
        // straight to the offline BOM-Extract index when configured — it loads
        // once, walks the tree in-memory, and enriches per-component from
        // item_extract afterward.
        if (maxDepth > FILE_FALLBACK_MIN_DEPTH && fileFallback != null && fileFallback.isAvailable()) {
            List<BomResult> r = tryFileFallback(itemNumber.trim(), maxDepth, /*explode=*/true);
            if (r != null) return r;
        }
        // Two-step: first get the BOM tree, then enrich with item_extract data
        // CONNECT BY with JOIN can be problematic in Oracle, so we do it in two steps
        // Level matches Agile convention: input item = 0, direct children = 1, grandchildren = 2
        String sql =
            "SELECT LEVEL AS lvl, " +
            "       PRIOR b.BOM_NUMBER AS parent, " +
            "       b.COMPONENT_NUMBER AS component, " +
            "       b.QTY, " +
            "       b.REFERENCE_DESIGNATOR, " +
            "       b.SEQ, " +
            "       b.NOTES, " +
            "       i.DESCRIPTION, " +
            "       i.STATUSCODE, " +
            "       i.REV, " +
            "       i.NEW_PART_CLASS, " +
            "       i.LIFECYCLE_PHASE, " +
            "       i.PRODUCTLINE, " +
            "       i.SUBCONTRACTORS, " +
            "       i.ACTUAL_BUILD_PLANT, " +
            "       SYS_CONNECT_BY_PATH(b.COMPONENT_NUMBER, ' > ') AS bom_path " +
            "FROM bom_extract b " +
            "LEFT JOIN item_extract i ON i.PART_NUMBER = b.COMPONENT_NUMBER " +
            "START WITH b.BOM_NUMBER = ? " +
            "CONNECT BY NOCYCLE PRIOR b.COMPONENT_NUMBER = b.BOM_NUMBER " +
            "AND LEVEL <= ?";

        List<BomResult> results = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, itemNumber.trim());
            ps.setInt(2, maxDepth);
            logger.info("[BOM EXPLODE] SQL: " + sql.replace("\n", " ") + " | params: [" + itemNumber.trim() + ", " + maxDepth + "]");

            int MAX_ROWS_PER_ITEM = 50000;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (results.size() >= MAX_ROWS_PER_ITEM) {
                        logger.warning("[BOM EXPLODE] " + itemNumber + " exceeded " + MAX_ROWS_PER_ITEM + " rows via SQL");
                        results.clear();
                        List<BomResult> fileResults = tryFileFallback(itemNumber.trim(), maxDepth, /*explode=*/true);
                        if (fileResults != null) {
                            results.addAll(fileResults);
                        } else {
                            bomTruncated = true;
                            bomSkippedItems.add(itemNumber.trim());
                        }
                        break;
                    }
                    int level = rs.getInt("lvl");
                    String parent = rs.getString("parent");
                    if (parent == null) parent = itemNumber.trim();
                    String path = itemNumber.trim() + nvl(rs.getString("bom_path"));
                    BomResult br = new BomResult(
                        level,
                        parent,
                        rs.getString("component"),
                        nvl(rs.getString("QTY")),
                        nvl(rs.getString("DESCRIPTION")),
                        nvl(rs.getString("NOTES")),
                        nvl(rs.getString("STATUSCODE")),
                        nvl(rs.getString("REV")),
                        nvl(rs.getString("REFERENCE_DESIGNATOR")),
                        nvl(rs.getString("SEQ")),
                        nvl(rs.getString("NEW_PART_CLASS"))
                    );
                    br.setPath(path);
                    br.setLifecyclePhase(nvl(rs.getString("LIFECYCLE_PHASE")));
                    br.setProductLine(nvl(rs.getString("PRODUCTLINE")));
                    br.setSubcontractors(nvl(rs.getString("SUBCONTRACTORS")));
                    br.setActualBuildPlant(nvl(rs.getString("ACTUAL_BUILD_PLANT")));
                    results.add(br);
                }
            }
        } catch (SQLException e) {
            logger.warning("BOM explode failed: " + e.getMessage());
            throw new RuntimeException("BOM query failed: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * When the SQL traversal blows past the per-item row cap and the request is
     * for a deep explode/implode (depth &gt; FILE_FALLBACK_MIN_DEPTH), retry
     * from the offline BOM-Extract.txt index. Returns {@code null} if the
     * fallback wasn't attempted or yielded nothing — the caller then falls back
     * to the existing "skipped" behavior.
     */
    private List<BomResult> tryFileFallback(String itemNumber, int maxDepth, boolean explode) {
        if (maxDepth <= FILE_FALLBACK_MIN_DEPTH) return null;
        if (fileFallback == null || !fileFallback.isAvailable()) return null;
        try {
            long t0 = System.currentTimeMillis();
            List<BomResult> r = explode
                ? fileFallback.explodeFromFile(itemNumber, maxDepth)
                : fileFallback.implodeFromFile(itemNumber, maxDepth);
            long ms = System.currentTimeMillis() - t0;
            if (r == null || r.isEmpty()) {
                logger.warning("[BOM FILE FALLBACK] " + itemNumber + " (depth " + maxDepth + ", "
                    + (explode ? "explode" : "implode") + ") returned 0 rows from file in " + ms + " ms");
                return null;
            }
            logger.info("[BOM FILE FALLBACK] " + itemNumber + " (depth " + maxDepth + ", "
                + (explode ? "explode" : "implode") + ") served " + r.size() + " rows from file in " + ms + " ms"
                + (fileFallback.wasTruncated() ? " [TRUNCATED at cap]" : ""));
            bomFromFileItems.add(itemNumber);
            bomFromFileAsOf = fileFallback.getDataAsOf();
            if (fileFallback.wasTruncated()) bomTruncated = true;
            return r;
        } catch (Exception e) {
            logger.warning("[BOM FILE FALLBACK] " + itemNumber + " failed: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // BOM Implosion (bottom-up) using CONNECT BY
    // =========================================================================

    public List<BomResult> implode(String itemNumber, int maxDepth) {
        // Same deep-implode short-circuit as explode (see comment above).
        if (maxDepth > FILE_FALLBACK_MIN_DEPTH && fileFallback != null && fileFallback.isAvailable()) {
            List<BomResult> r = tryFileFallback(itemNumber.trim(), maxDepth, /*explode=*/false);
            if (r != null) {
                markTopLevel(r);
                return r;
            }
        }
        // Level matches Agile: input item = 0, immediate parent = 1, grandparent = 2
        String sql =
            "SELECT LEVEL AS lvl, " +
            "       b.BOM_NUMBER AS parent, " +
            "       b.COMPONENT_NUMBER AS component, " +
            "       b.QTY, " +
            "       b.REFERENCE_DESIGNATOR, " +
            "       b.SEQ, " +
            "       b.NOTES, " +
            "       i.DESCRIPTION, " +
            "       i.STATUSCODE, " +
            "       i.REV, " +
            "       i.NEW_PART_CLASS, " +
            "       i.LIFECYCLE_PHASE, " +
            "       i.PRODUCTLINE, " +
            "       i.SUBCONTRACTORS, " +
            "       i.ACTUAL_BUILD_PLANT, " +
            "       SYS_CONNECT_BY_PATH(b.BOM_NUMBER, ' > ') AS bom_path " +
            "FROM bom_extract b " +
            "LEFT JOIN item_extract i ON i.PART_NUMBER = b.BOM_NUMBER " +
            "START WITH b.COMPONENT_NUMBER = ? " +
            "CONNECT BY NOCYCLE PRIOR b.BOM_NUMBER = b.COMPONENT_NUMBER " +
            "AND LEVEL <= ?";

        List<BomResult> results = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, itemNumber.trim());
            ps.setInt(2, maxDepth);
            logger.info("[BOM IMPLODE] SQL: " + sql.replace("\n", " ") + " | params: [" + itemNumber.trim() + ", " + maxDepth + "]");

            int MAX_ROWS_PER_ITEM = 50000;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (results.size() >= MAX_ROWS_PER_ITEM) {
                        logger.warning("[BOM IMPLODE] " + itemNumber + " exceeded " + MAX_ROWS_PER_ITEM + " rows via SQL");
                        results.clear();
                        List<BomResult> fileResults = tryFileFallback(itemNumber.trim(), maxDepth, /*explode=*/false);
                        if (fileResults != null) {
                            results.addAll(fileResults);
                        } else {
                            bomTruncated = true;
                            bomSkippedItems.add(itemNumber.trim());
                        }
                        break;
                    }
                    int level = rs.getInt("lvl");
                    String component = rs.getString("component");
                    if (component == null) component = itemNumber.trim();
                    String path = itemNumber.trim() + nvl(rs.getString("bom_path"));
                    BomResult br = new BomResult(
                        level,
                        rs.getString("parent"),
                        component,
                        nvl(rs.getString("QTY")),
                        nvl(rs.getString("DESCRIPTION")),
                        nvl(rs.getString("NOTES")),
                        nvl(rs.getString("STATUSCODE")),
                        nvl(rs.getString("REV")),
                        nvl(rs.getString("REFERENCE_DESIGNATOR")),
                        nvl(rs.getString("SEQ")),
                        nvl(rs.getString("NEW_PART_CLASS"))
                    );
                    br.setPath(path);
                    br.setLifecyclePhase(nvl(rs.getString("LIFECYCLE_PHASE")));
                    br.setProductLine(nvl(rs.getString("PRODUCTLINE")));
                    br.setSubcontractors(nvl(rs.getString("SUBCONTRACTORS")));
                    br.setActualBuildPlant(nvl(rs.getString("ACTUAL_BUILD_PLANT")));
                    br.setInputItem(itemNumber.trim());
                    results.add(br);
                }
            }
        } catch (SQLException e) {
            logger.warning("BOM implode failed: " + e.getMessage());
            throw new RuntimeException("BOM query failed: " + e.getMessage(), e);
        }
        markTopLevel(results);
        return results;
    }

    // =========================================================================
    // Top-level detection for implode results
    // =========================================================================

    private void markTopLevel(List<BomResult> results) {
        Set<String> parents = new HashSet<>();
        for (BomResult r : results) {
            if (r.getParent() != null && !r.getParent().isEmpty()) {
                parents.add(r.getParent());
            }
        }
        if (parents.isEmpty()) return;

        // Fast path: when the in-memory BOM graph is loaded (it is, whenever the
        // file fallback served the walk), decide top-level with O(1) lookups
        // instead of ~500-parent-per-query DISTINCT scans of bom_extract — which
        // for a 247K-assembly where-used was ~500 full scans and blew the timeout.
        if (fileFallback != null && fileFallback.isLoaded()) {
            for (BomResult r : results) {
                r.setTopLevel(!fileFallback.hasParents(r.getParent()));
            }
            return;
        }

        // Query which of these parents appear as components somewhere
        // (i.e., they have a parent themselves, so they're NOT top-level)
        Set<String> hasParent = new HashSet<>();
        List<String> parentList = new ArrayList<>(parents);

        // Batch in groups of 500 to avoid Oracle IN clause limit
        int batchSize = 500;
        for (int start = 0; start < parentList.size(); start += batchSize) {
            int end = Math.min(start + batchSize, parentList.size());
            List<String> batch = parentList.subList(start, end);
            String inClause = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT DISTINCT COMPONENT_NUMBER FROM bom_extract WHERE COMPONENT_NUMBER IN (" + inClause + ")";

            try (Connection conn = customDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(30);
                int idx = 1;
                for (String p : batch) {
                    ps.setString(idx++, p);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hasParent.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                logger.warning("Top-level check failed: " + e.getMessage());
            }
        }

        // Mark top-level
        for (BomResult r : results) {
            r.setTopLevel(!hasParent.contains(r.getParent()));
        }
    }

    // =========================================================================
    // BOM Compare — flat component lists from bom_extract only
    // =========================================================================

    public List<Map<String, String>> getBomComponents(String parentItem) {
        String sql =
            "SELECT b.COMPONENT_NUMBER, b.QTY, b.REFERENCE_DESIGNATOR, b.SEQ, b.NOTES, " +
            "       i.DESCRIPTION, i.STATUSCODE, i.REV, i.NEW_PART_CLASS " +
            "FROM bom_extract b " +
            "LEFT JOIN item_extract i ON i.PART_NUMBER = b.COMPONENT_NUMBER " +
            "WHERE b.BOM_NUMBER = ? " +
            "ORDER BY b.SEQ, b.COMPONENT_NUMBER";

        List<Map<String, String>> results = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, parentItem.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("component", nvl(rs.getString("COMPONENT_NUMBER")));
                    row.put("qty", nvl(rs.getString("QTY")));
                    row.put("refDes", nvl(rs.getString("REFERENCE_DESIGNATOR")));
                    row.put("findNum", nvl(rs.getString("SEQ")));
                    row.put("notes", nvl(rs.getString("NOTES")));
                    row.put("description", nvl(rs.getString("DESCRIPTION")));
                    row.put("status", nvl(rs.getString("STATUSCODE")));
                    row.put("rev", nvl(rs.getString("REV")));
                    row.put("itemType", nvl(rs.getString("NEW_PART_CLASS")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("BOM compare query failed for " + parentItem + ": " + e.getMessage());
            throw new RuntimeException("BOM query failed: " + e.getMessage(), e);
        }
        return results;
    }

    // =========================================================================
    // Deep top-level assembly finder (ignores user maxDepth)
    // =========================================================================

    private static final int TOP_LEVEL_MAX_DEPTH = 99;

    public List<BomResult> findTopLevelAssemblies(List<String> itemNumbers) {
        return findTopLevelAssemblies(itemNumbers, BomFilters.none());
    }

    public List<BomResult> findTopLevelAssemblies(List<String> itemNumbers, BomFilters filters) {
        if (filters == null) filters = BomFilters.none();
        // Bulk path: iterative IN-list widening, exactly the same shape as runBatched
        // but at TOP_LEVEL_MAX_DEPTH (99) and with the post-walk filter to keep only
        // the top-most parents per input. One IN query per level instead of N CONNECT
        // BY queries at depth 99 — the difference between seconds and minutes on a
        // 2,884-input upload.
        if (itemNumbers != null && itemNumbers.size() >= BATCH_THRESHOLD) {
            List<BomResult> tops = findTopLevelAssembliesBatched(itemNumbers);
            return capTopLevelPerInput(applyRowFilters(tops, filters), filters.getMaxTopLevelParents());
        }
        // Small inputs keep the legacy parallel-N path.
        List<java.util.concurrent.Future<List<BomResult>>> futures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> uniqueItems = new ArrayList<>();
        for (String item : itemNumbers) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed.toUpperCase())) {
                uniqueItems.add(trimmed);
            }
        }

        for (String item : uniqueItems) {
            final String trimmed = item.trim();
            futures.add(bomExecutor.submit(() -> {
                try {
                    List<BomResult> deep = implode(trimmed, TOP_LEVEL_MAX_DEPTH);
                    List<BomResult> tops = new ArrayList<>();
                    for (BomResult r : deep) {
                        if (r.isTopLevel()) tops.add(r);
                    }
                    return tops;
                } catch (Exception e) {
                    logger.warning("[TOP-LEVEL] Deep search failed for " + trimmed + ": " + e.getMessage());
                    return new ArrayList<BomResult>();
                }
            }));
        }

        List<BomResult> all = new ArrayList<>();
        for (java.util.concurrent.Future<List<BomResult>> f : futures) {
            try {
                all.addAll(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                logger.warning("[TOP-LEVEL] Timeout: " + e.getMessage());
            }
        }
        return capTopLevelPerInput(applyRowFilters(all, filters), filters.getMaxTopLevelParents());
    }

    /** Batched top-level finder. Walks parents level by level via IN-list queries
     *  until no new parents are found, then emits one BomResult per (input, terminal-parent)
     *  pair where terminal-parent has no parent of its own. */
    private List<BomResult> findTopLevelAssembliesBatched(List<String> itemNumbers) {
        long t0 = System.currentTimeMillis();
        LinkedHashSet<String> uniqueInputs = new LinkedHashSet<>();
        for (String s : itemNumbers) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) uniqueInputs.add(t);
        }
        if (uniqueInputs.isEmpty()) return new ArrayList<>();

        Map<String, List<EdgeRow>> walkMap = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (String s : uniqueInputs) visited.add(s.toUpperCase());

        // For implode in the controller, this method runs after the user-facing query
        // finishes — it walks "all the way up" to mark terminal top-level assemblies.
        // On a 9097-input upload it can take longer than the actual query (depth 99 walk),
        // so we surface its level + chunk progress under a dedicated stage instead of
        // showing a frozen "..." spinner for 30+ seconds.
        bomStage = "top-walk";
        bomMaxDepth = TOP_LEVEL_MAX_DEPTH;

        Set<String> currentLevel = new LinkedHashSet<>(uniqueInputs);
        int totalEdges = 0;
        int safetyMax = TOP_LEVEL_MAX_DEPTH;
        for (int level = 1; level <= safetyMax; level++) {
            if (currentLevel.isEmpty()) break;
            bomCurrentLevel = level;
            List<EdgeRow> edges = queryEdges(currentLevel, false);
            if (edges.isEmpty()) break;
            totalEdges += edges.size();
            Set<String> nextLevel = new LinkedHashSet<>();
            for (EdgeRow e : edges) {
                walkMap.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
                String upTo = e.to == null ? "" : e.to.toUpperCase();
                if (!upTo.isEmpty() && visited.add(upTo)) nextLevel.add(e.to);
            }
            currentLevel = nextLevel;
            if (visited.size() > VISITED_CAP) {
                logger.warning("[TOP-LEVEL BATCH] visited cap hit at level " + level);
                break;
            }
        }

        // For each input, DFS to find terminal parents (nodes with no further parents
        // in the walkMap). Emit one BomResult per (input, terminalParent) pair.
        // Also reconstruct the chain `input > p1 > p2 > ... > terminal` per terminal so
        // the "Top-Level Assemblies" Excel sheet's Path column isn't empty.
        List<BomResult> all = new ArrayList<>();
        for (String input : uniqueInputs) {
            Set<String> terminals = new LinkedHashSet<>();
            Map<String, EdgeRow> terminalEdge = new HashMap<>();
            Map<String, String> terminalPath = new HashMap<>();
            List<String> chain = new ArrayList<>();
            chain.add(input);
            collectTerminals(input, walkMap, terminals, terminalEdge, terminalPath,
                    new HashSet<>(Collections.singleton(input)), chain);
            for (String terminal : terminals) {
                EdgeRow e = terminalEdge.get(terminal);
                if (e == null) continue;
                BomResult br = new BomResult(/*level*/ -1, terminal, input,
                        e.qty, e.description, e.notes, e.statusCode, e.rev,
                        e.refDesig, e.seq, e.newPartClass);
                br.setLifecyclePhase(e.lifecyclePhase);
                br.setProductLine(e.productLine);
                br.setSubcontractors(e.subcontractors);
                br.setActualBuildPlant(e.actualBuildPlant);
                br.setInputItem(input);
                br.setTopLevel(true);
                String p = terminalPath.get(terminal);
                if (p != null) br.setPath(p);
                all.add(br);
            }
        }
        logger.info("[TOP-LEVEL BATCH] inputs=" + uniqueInputs.size()
                + " edges=" + totalEdges + " terminals=" + all.size()
                + " durMs=" + (System.currentTimeMillis() - t0));
        bomStage = "";
        bomCurrentLevel = 0;
        return all;
    }

    private void collectTerminals(String node, Map<String, List<EdgeRow>> walkMap,
                                  Set<String> terminals, Map<String, EdgeRow> terminalEdge,
                                  Map<String, String> terminalPath,
                                  Set<String> onPath, List<String> chain) {
        List<EdgeRow> edges = walkMap.get(node);
        if (edges == null || edges.isEmpty()) {
            return;
        }
        for (EdgeRow e : edges) {
            if (e.to == null || onPath.contains(e.to)) continue;
            List<EdgeRow> further = walkMap.get(e.to);
            chain.add(e.to);
            if (further == null || further.isEmpty()) {
                // e.to is a terminal — keep it, and remember the path that reached it.
                terminals.add(e.to);
                terminalEdge.putIfAbsent(e.to, e);
                terminalPath.putIfAbsent(e.to, String.join(" > ", chain));
            } else {
                onPath.add(e.to);
                collectTerminals(e.to, walkMap, terminals, terminalEdge, terminalPath, onPath, chain);
                onPath.remove(e.to);
            }
            chain.remove(chain.size() - 1);
        }
    }

    // =========================================================================
    // Multi-item support
    // =========================================================================

    // Thread pool for parallel BOM queries
    private final java.util.concurrent.ExecutorService bomExecutor =
        java.util.concurrent.Executors.newFixedThreadPool(3);

    // Progress and truncation tracking
    private volatile boolean bomTruncated = false;
    private volatile List<String> bomSkippedItems = new ArrayList<>();
    public boolean isBomTruncated() { return bomTruncated; }
    public List<String> getBomSkippedItems() { return bomSkippedItems; }

    // Progress tracking for multi-item queries
    private volatile int bomTotalItems = 0;
    private volatile int bomCompletedItems = 0;
    // Batched-path progress (the level-by-level Oracle walk). When > 0, the front-end
    // should show "Querying level N · M edges so far" instead of "Processing item X of Y"
    // — the per-item counter only moves during the final emit phase, which kicks in after
    // all the slow query work is done.
    private volatile int bomCurrentLevel = 0;
    private volatile int bomMaxDepth = 0;
    private volatile int bomEdgeCount = 0;
    // Chunk progress within the current level — Oracle IN-list cap forces us to
    // run 1 query per 1000 inputs, so a 9097-input level walks in 10 chunks. The
    // user wanted to see those tick by instead of just "level 1 of 1" for 90 s.
    private volatile int bomChunksDone = 0;
    private volatile int bomChunksTotal = 0;
    private volatile String bomStage = "";  // "parsing" | "queries" | "emit" | "" (idle)

    public int getBomCurrentLevel() { return bomCurrentLevel; }
    public int getBomMaxDepthCap() { return bomMaxDepth; }
    public int getBomEdgeCount() { return bomEdgeCount; }
    public int getBomChunksDone() { return bomChunksDone; }
    public int getBomChunksTotal() { return bomChunksTotal; }
    public String getBomStage() { return bomStage; }

    /**
     * Reset all progress counters to a clean "no work in flight" state. The upload
     * controller calls this BEFORE the slow file-parse step so the front-end poller
     * doesn't briefly flash stale values from a prior query (e.g. "9097 of 9097"
     * carried over from the run that just completed).
     */
    public void resetBomProgress() {
        bomTotalItems = 0;
        bomCompletedItems = 0;
        bomCurrentLevel = 0;
        bomMaxDepth = 0;
        bomEdgeCount = 0;
        bomChunksDone = 0;
        bomChunksTotal = 0;
        bomStage = "parsing";
    }

    public List<BomResult> explodeMultiple(List<String> itemNumbers, int maxDepth) {
        return explodeMultiple(itemNumbers, maxDepth, BomFilters.none());
    }

    public List<BomResult> implodeMultiple(List<String> itemNumbers, int maxDepth) {
        return implodeMultiple(itemNumbers, maxDepth, BomFilters.none());
    }

    public List<BomResult> explodeMultiple(List<String> itemNumbers, int maxDepth, BomFilters filters) {
        if (filters == null) filters = BomFilters.none();
        // Bulk path: one IN-list query per level, dedup parents at each level so
        // assemblies that show up under multiple inputs aren't queried again.
        if (itemNumbers != null && itemNumbers.size() >= BATCH_THRESHOLD) {
            return runBatched(itemNumbers, maxDepth, true, filters);
        }
        return runParallel(itemNumbers, maxDepth, true, filters);
    }

    public List<BomResult> implodeMultiple(List<String> itemNumbers, int maxDepth, BomFilters filters) {
        if (filters == null) filters = BomFilters.none();
        if (itemNumbers != null && itemNumbers.size() >= BATCH_THRESHOLD) {
            return runBatched(itemNumbers, maxDepth, false, filters);
        }
        return runParallel(itemNumbers, maxDepth, false, filters);
    }

    /** Above this input count, switch from N parallel CONNECT BY queries to a single
     *  IN-list query per level. Single-item or small lists keep the legacy path. */
    private static final int BATCH_THRESHOLD = 5;
    private static final int IN_CHUNK = 1000;        // Oracle IN cap
    private static final int VISITED_CAP = 500_000;  // protect against runaway recursion

    // Per-batch cache: avoids re-querying the same item at the same depth
    private final java.util.concurrent.ConcurrentHashMap<String, List<BomResult>> bomQueryCache = new java.util.concurrent.ConcurrentHashMap<>();

    private List<BomResult> runParallel(List<String> itemNumbers, int maxDepth, boolean isExplode, BomFilters filters) {
        bomTotalItems = itemNumbers.size();
        bomCompletedItems = 0;
        bomQueryCache.clear(); // fresh cache per batch
        bomTruncated = false;
        bomSkippedItems = new ArrayList<>();
        bomFromFileItems = new ArrayList<>();
        bomFromFileAsOf = "";

        // Deduplicate input items
        List<String> uniqueItems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : itemNumbers) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed.toUpperCase())) {
                uniqueItems.add(trimmed);
            }
        }
        int skipped = itemNumbers.size() - uniqueItems.size();
        if (skipped > 0) {
            logger.info("[BOM CACHE] Deduplicated " + skipped + " duplicate items from input (" + itemNumbers.size() + " -> " + uniqueItems.size() + ")");
        }
        bomTotalItems = uniqueItems.size();

        List<java.util.concurrent.Future<List<BomResult>>> futures = new ArrayList<>();
        for (String item : uniqueItems) {
            final String trimmed = item.trim();
            final String cacheKey = (isExplode ? "E:" : "I:") + trimmed.toUpperCase() + ":" + maxDepth;
            futures.add(bomExecutor.submit(() -> {
                try {
                    // Check cache first
                    List<BomResult> cached = bomQueryCache.get(cacheKey);
                    if (cached != null) {
                        bomCompletedItems++;
                        logger.info("[BOM CACHE] Cache hit for " + trimmed + " (" + cached.size() + " rows)");
                        return cached;
                    }
                    List<BomResult> result = isExplode ? explode(trimmed, maxDepth) : implode(trimmed, maxDepth);
                    bomQueryCache.put(cacheKey, result);
                    bomCompletedItems++;
                    logger.info("[BOM PROGRESS] " + bomCompletedItems + " of " + bomTotalItems + " items complete (" + result.size() + " rows)");
                    return result;
                } catch (Exception e) {
                    bomCompletedItems++;
                    logger.warning("[BOM ERROR] Item " + trimmed + ": " + e.getMessage());
                    return new ArrayList<BomResult>();
                }
            }));
        }

        int MAX_TOTAL_ROWS = 500000;
        List<BomResult> all = new ArrayList<>();
        boolean totalCapHit = false;
        for (int fi = 0; fi < futures.size(); fi++) {
            if (totalCapHit) {
                // Add remaining items to skipped list
                if (fi < uniqueItems.size()) bomSkippedItems.add(uniqueItems.get(fi));
                continue;
            }
            java.util.concurrent.Future<List<BomResult>> f = futures.get(fi);
            try {
                List<BomResult> batch = f.get(120, java.util.concurrent.TimeUnit.SECONDS);
                all.addAll(batch);
                if (all.size() > MAX_TOTAL_ROWS) {
                    bomTruncated = true;
                    totalCapHit = true;
                    logger.warning("[BOM] Total results exceeded " + MAX_TOTAL_ROWS + " rows (" + all.size() + "), stopping. Remaining items skipped.");
                }
            } catch (Exception e) {
                logger.warning("[BOM TIMEOUT] " + e.getMessage());
            }
        }
        bomQueryCache.clear(); // free memory after batch
        return applyRowFilters(all, filters);
    }

    /** Post-walk row filter shared by runParallel, runBatched, and findTopLevelAssemblies.
     *  Reads each row's lifecycle/part-type/component to decide whether to keep it.
     *  Pre-filters here drop rows from the result; the walk still descended into them. */
    private static List<BomResult> applyRowFilters(List<BomResult> rows, BomFilters filters) {
        if (filters == null || filters.isEmpty()) return rows;
        List<BomResult> kept = new ArrayList<>(rows.size());
        for (BomResult r : rows) {
            if (filters.accept(r.getLifecyclePhase(), r.getItemType(), r.getComponent())) {
                kept.add(r);
            }
        }
        return kept;
    }

    /** Limit top-level results to the first N per input item. Applied after row filters. */
    private static List<BomResult> capTopLevelPerInput(List<BomResult> rows, int cap) {
        if (cap <= 0 || rows.isEmpty()) return rows;
        Map<String, Integer> perInput = new HashMap<>();
        List<BomResult> out = new ArrayList<>(rows.size());
        for (BomResult r : rows) {
            String key = r.getInputItem() == null ? "" : r.getInputItem();
            int n = perInput.getOrDefault(key, 0);
            if (n >= cap) continue;
            perInput.put(key, n + 1);
            out.add(r);
        }
        return out;
    }

    // =========================================================================
    // Batched IN-list path: O(maxDepth) Oracle queries instead of O(N items × maxDepth)
    //
    // Strategy: at each level, run ONE query "WHERE COMPONENT_NUMBER IN (set)" (for
    // implode, or BOM_NUMBER IN (set) for explode) chunked at the Oracle 1000-IN cap.
    // Results give us all parent→child (or parent→component) edges for that level.
    // Distinct parents become the input set for the next level. Cycles are blocked
    // by a global visited-set.
    //
    // After all levels are queried, we DFS from each original input through the
    // edge map to reconstruct paths and emit BomResult rows. An assembly that's
    // a parent of many inputs is queried exactly once at each level — the user's
    // exact ask.
    // =========================================================================

    private List<BomResult> runBatched(List<String> itemNumbers, int maxDepth, boolean isExplode, BomFilters filters) {
        bomTruncated = false;
        bomSkippedItems = new ArrayList<>();
        bomFromFileItems = new ArrayList<>();
        bomFromFileAsOf = "";
        bomQueryCache.clear();

        // Dedupe inputs (preserve order).
        LinkedHashSet<String> uniqueInputs = new LinkedHashSet<>();
        for (String s : itemNumbers) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) uniqueInputs.add(t);
        }
        bomTotalItems = uniqueInputs.size();
        bomCompletedItems = 0;
        bomCurrentLevel = 0;
        bomMaxDepth = maxDepth;
        bomEdgeCount = 0;
        bomStage = "queries";
        if (uniqueInputs.isEmpty()) { bomStage = ""; return new ArrayList<>(); }

        long t0 = System.currentTimeMillis();
        logger.info("[BOM BATCH] " + (isExplode ? "EXPLODE" : "IMPLODE") + " starting: "
                + uniqueInputs.size() + " unique inputs, maxDepth=" + maxDepth);

        // child -> list of (parent + edge metadata) for implode mode;
        // parent -> list of (component + edge metadata) for explode mode.
        // i.e. always keyed by the THING we walked FROM.
        Map<String, List<EdgeRow>> walkMap = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (String s : uniqueInputs) visited.add(s.toUpperCase());

        Set<String> currentLevel = new LinkedHashSet<>(uniqueInputs);
        int totalEdges = 0;
        for (int level = 1; level <= maxDepth; level++) {
            if (currentLevel.isEmpty()) break;
            bomCurrentLevel = level;
            List<EdgeRow> edges = queryEdges(currentLevel, isExplode);
            if (edges.isEmpty()) {
                logger.info("[BOM BATCH] level=" + level + " no more edges; stopping at "
                        + (level - 1));
                break;
            }
            totalEdges += edges.size();
            // bomEdgeCount is kept cumulative inside queryEdges (per-chunk), so no
            // need to reassign here — leaving it would also be correct, just redundant.

            Set<String> nextLevel = new LinkedHashSet<>();
            for (EdgeRow e : edges) {
                walkMap.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
                String upTo = e.to == null ? "" : e.to.toUpperCase();
                if (!upTo.isEmpty() && visited.add(upTo)) nextLevel.add(e.to);
            }
            logger.info("[BOM BATCH] level=" + level + " edges=" + edges.size()
                    + " nextLevelInputs=" + nextLevel.size());
            currentLevel = nextLevel;
            if (visited.size() > VISITED_CAP) {
                bomTruncated = true;
                logger.warning("[BOM BATCH] visited-set exceeded " + VISITED_CAP + " — truncating");
                break;
            }
        }

        // DFS from each original input, emitting one BomResult per edge along the path.
        bomStage = "emit";
        bomCurrentLevel = 0;
        List<BomResult> all = new ArrayList<>();
        int totalCap = 500_000;
        for (String input : uniqueInputs) {
            if (all.size() > totalCap) {
                bomTruncated = true;
                bomSkippedItems.add(input);
                continue;
            }
            // path holds the chain of node IDs from input outward; level 0 is the input itself.
            List<String> path = new ArrayList<>();
            path.add(input);
            walkAndEmit(input, input, 0, maxDepth, walkMap, isExplode, path, all, totalCap, filters);
            bomCompletedItems++;
        }

        // Top-level marking is the bottleneck on bulk runs (one COMPONENT_NUMBER IN
        // query per 500-parent chunk; ~33 sequential queries for a 16K-edge result).
        // For runs with a manageable parent count, parallelize the chunks; for very
        // large result sets, skip it — the UI hint isn't worth a 60s+ wait on a bulk
        // upload, and the user can drill into individual items if they want it.
        long tMark = System.currentTimeMillis();
        Set<String> distinctParents = new HashSet<>();
        for (BomResult r : all) {
            if (r.getParent() != null && !r.getParent().isEmpty()) distinctParents.add(r.getParent());
        }
        if (distinctParents.size() <= 5000) {
            markTopLevelParallel(all, distinctParents);
        } else {
            logger.info("[BOM BATCH] skipping top-level mark (" + distinctParents.size()
                    + " distinct parents > 5000); rows have isTopLevel=false");
        }
        long durMs = System.currentTimeMillis() - t0;
        logger.info("[BOM BATCH] done: inputs=" + uniqueInputs.size()
                + " edges=" + totalEdges + " rows=" + all.size()
                + " markMs=" + (System.currentTimeMillis() - tMark)
                + " durMs=" + durMs + (bomTruncated ? " TRUNCATED" : ""));
        bomStage = "";
        bomCurrentLevel = 0;
        return all;
    }

    /** Same goal as markTopLevel(), but parallelizes the per-chunk COMPONENT_NUMBER IN
     *  queries instead of running them sequentially. Used by the batched path. */
    private void markTopLevelParallel(List<BomResult> results, Set<String> distinctParents) {
        if (distinctParents.isEmpty()) return;
        List<String> parents = new ArrayList<>(distinctParents);
        java.util.Set<String> hasParent = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<java.util.concurrent.Future<?>> futs = new ArrayList<>();

        // Tell the front-end we've moved on to the top-level marking phase. This often
        // takes longer than the level walk on a wide implode (one query per 1000-parent
        // chunk against the full bom_extract table) and was previously a silent stall.
        bomStage = "top-level";
        int chunkCount = (parents.size() + IN_CHUNK - 1) / IN_CHUNK;
        bomChunksTotal = chunkCount;
        bomChunksDone = 0;
        java.util.concurrent.atomic.AtomicInteger doneRef = new java.util.concurrent.atomic.AtomicInteger();

        for (int start = 0; start < parents.size(); start += IN_CHUNK) {
            final int s = start;
            final int e = Math.min(start + IN_CHUNK, parents.size());
            futs.add(bomExecutor.submit(() -> {
                List<String> chunk = parents.subList(s, e);
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < chunk.size(); i++) in.append(i == 0 ? "?" : ",?");
                String sql = "SELECT DISTINCT COMPONENT_NUMBER FROM bom_extract WHERE COMPONENT_NUMBER IN (" + in + ")";
                try (Connection conn = customDataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(60);
                    int idx = 1;
                    for (String p : chunk) ps.setString(idx++, p);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) hasParent.add(rs.getString(1));
                    }
                } catch (SQLException sqle) {
                    logger.warning("[BOM BATCH] top-level chunk failed: " + sqle.getMessage());
                }
                bomChunksDone = doneRef.incrementAndGet();
            }));
        }
        for (java.util.concurrent.Future<?> f : futs) {
            try { f.get(60, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignore) {}
        }
        for (BomResult r : results) {
            r.setTopLevel(!hasParent.contains(r.getParent()));
        }
    }

    /** One IN-list query per level. Result: distinct parent→child rows for implode,
     *  or parent→component rows for explode, with item_extract metadata about the
     *  "outward" node (the parent for implode, the child for explode). */
    private List<EdgeRow> queryEdges(Set<String> currentLevelIds, boolean isExplode) {
        if (currentLevelIds.isEmpty()) return new ArrayList<>();
        // For implode: from = component (input we walk FROM), to = parent.
        // For explode: from = bom (parent we walk FROM),    to = component.
        // i columns are about "to" (what we found at this level).
        String fromCol = isExplode ? "BOM_NUMBER" : "COMPONENT_NUMBER";
        String toCol   = isExplode ? "COMPONENT_NUMBER" : "BOM_NUMBER";
        String iJoinKey = "i.PART_NUMBER = b." + toCol;

        List<EdgeRow> all = new ArrayList<>();
        List<String> ids = new ArrayList<>(currentLevelIds);
        int chunkCount = (ids.size() + IN_CHUNK - 1) / IN_CHUNK;
        bomChunksTotal = chunkCount;
        bomChunksDone = 0;
        int chunkIdx = 0;
        int edgesAtStart = bomEdgeCount;  // queryEdges *adds* to the cumulative total
        for (int start = 0; start < ids.size(); start += IN_CHUNK) {
            int end = Math.min(start + IN_CHUNK, ids.size());
            List<String> chunk = ids.subList(start, end);
            chunkIdx++;
            StringBuilder sql = new StringBuilder()
                .append("SELECT b.").append(fromCol).append(" AS from_id, ")
                .append("       b.").append(toCol)  .append(" AS to_id, ")
                .append("       b.QTY, b.REFERENCE_DESIGNATOR, b.SEQ, b.NOTES, ")
                .append("       i.DESCRIPTION, i.STATUSCODE, i.REV, i.NEW_PART_CLASS, ")
                .append("       i.LIFECYCLE_PHASE, i.PRODUCTLINE, i.SUBCONTRACTORS, i.ACTUAL_BUILD_PLANT ")
                .append("  FROM bom_extract b ")
                .append("  LEFT JOIN item_extract i ON ").append(iJoinKey).append(" ")
                .append(" WHERE b.").append(fromCol).append(" IN (");
            for (int i = 0; i < chunk.size(); i++) sql.append(i == 0 ? "?" : ",?");
            sql.append(")");

            try (Connection conn = customDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setQueryTimeout(120);
                ps.setFetchSize(2000);
                int idx = 1;
                for (String s : chunk) ps.setString(idx++, s);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EdgeRow e = new EdgeRow();
                        e.from = rs.getString("from_id");
                        e.to   = rs.getString("to_id");
                        e.qty  = nvl(rs.getString("QTY"));
                        e.refDesig = nvl(rs.getString("REFERENCE_DESIGNATOR"));
                        e.seq      = nvl(rs.getString("SEQ"));
                        e.notes    = nvl(rs.getString("NOTES"));
                        e.description = nvl(rs.getString("DESCRIPTION"));
                        e.statusCode  = nvl(rs.getString("STATUSCODE"));
                        e.rev         = nvl(rs.getString("REV"));
                        e.newPartClass = nvl(rs.getString("NEW_PART_CLASS"));
                        e.lifecyclePhase = nvl(rs.getString("LIFECYCLE_PHASE"));
                        e.productLine    = nvl(rs.getString("PRODUCTLINE"));
                        e.subcontractors = nvl(rs.getString("SUBCONTRACTORS"));
                        e.actualBuildPlant = nvl(rs.getString("ACTUAL_BUILD_PLANT"));
                        all.add(e);
                    }
                }
            } catch (SQLException sqle) {
                logger.warning("[BOM BATCH] level query failed (chunk " + start + ".." + end + "): "
                        + sqle.getMessage());
                throw new RuntimeException("BOM batch query failed: " + sqle.getMessage(), sqle);
            }
            // Live progress — bump after each chunk so the UI sees N/10 ticking by
            // instead of "level 1 of 1" frozen for 90s while all chunks finish.
            bomChunksDone = chunkIdx;
            bomEdgeCount = edgesAtStart + all.size();
        }
        return all;
    }

    private void walkAndEmit(String origin, String currentNode, int currentLevel, int maxDepth,
                             Map<String, List<EdgeRow>> walkMap, boolean isExplode,
                             List<String> path, List<BomResult> out, int cap,
                             BomFilters filters) {
        if (currentLevel >= maxDepth) return;
        if (out.size() > cap) { bomTruncated = true; return; }
        List<EdgeRow> edges = walkMap.get(currentNode);
        if (edges == null) return;
        for (EdgeRow e : edges) {
            // Cycle guard — don't walk back through a node already on this path.
            if (path.contains(e.to)) continue;

            int level = currentLevel + 1;
            // Build path string
            path.add(e.to);
            String pathStr;
            if (isExplode) {
                // explode path: input > child > grandchild ...
                pathStr = String.join(" > ", path);
            } else {
                // implode path matches legacy: input > parent1 > parent2 ...
                pathStr = String.join(" > ", path);
            }

            // Map to the legacy BomResult shape:
            //   implode: parent = e.to, component = e.from (or origin at level 1)
            //   explode: parent = e.from (or origin at level 1), component = e.to
            String parent    = isExplode ? (currentLevel == 0 ? origin : currentNode) : e.to;
            String component = isExplode ? e.to : (currentLevel == 0 ? origin : currentNode);

            // Filter at emit. EdgeRow metadata describes the "to" node — child for
            // explode, parent for where-used. Use that node's part number when testing
            // the prefix filter so a "exclude 95-*" rule drops the right side.
            if (!filters.isEmpty() && !filters.accept(e.lifecyclePhase, e.newPartClass, e.to)) {
                walkAndEmit(origin, e.to, level, maxDepth, walkMap, isExplode, path, out, cap, filters);
                path.remove(path.size() - 1);
                continue;
            }

            BomResult br = new BomResult(level, parent, component,
                    e.qty, e.description, e.notes, e.statusCode, e.rev,
                    e.refDesig, e.seq, e.newPartClass);
            br.setPath(pathStr);
            br.setLifecyclePhase(e.lifecyclePhase);
            br.setProductLine(e.productLine);
            br.setSubcontractors(e.subcontractors);
            br.setActualBuildPlant(e.actualBuildPlant);
            br.setInputItem(origin);
            out.add(br);

            if (out.size() > cap) { bomTruncated = true; path.remove(path.size() - 1); return; }

            walkAndEmit(origin, e.to, level, maxDepth, walkMap, isExplode, path, out, cap, filters);
            path.remove(path.size() - 1);
        }
    }

    /** Compact edge row for the batched walk. */
    private static class EdgeRow {
        String from;            // node we walked from (component for implode, bom for explode)
        String to;              // node we found    (parent for implode, child for explode)
        String qty;
        String refDesig;
        String seq;
        String notes;
        String description;
        String statusCode;
        String rev;
        String newPartClass;
        String lifecyclePhase;
        String productLine;
        String subcontractors;
        String actualBuildPlant;
    }

    public int getBomTotalItems() { return bomTotalItems; }
    public int getBomCompletedItems() { return bomCompletedItems; }

    // =========================================================================
    // Part Extract queries
    // =========================================================================

    // Columns from ITEM_EXTRACT DDL (hardcoded for reliability)
    private static final String[] ITEM_EXTRACT_COLUMNS = {
        "PART_NUMBER", "REV", "PM", "PRODUCTLINE", "HIST", "CIS", "CREATE_USER",
        "EFF_DATE_CHANGE", "FAMILYNAME", "CAPACITY", "GROUPV", "IMAGE", "MATL_COST",
        "MC_DEPTH", "MC_HEIGHT", "MC_QTY", "MC_WEIGHT", "MC_WIDTH", "NEW_EFF_DATE",
        "OLD_EFF_DATE", "PRODUCTDIVISION", "PROMOGROUP", "ROLLSTATUS", "SIDENSITY",
        "SPDEPTH", "SPHEIGHT", "SPWEIGHT", "SPWIDTH", "STATUSCODE", "STATUSROLLDATE",
        "SUBCONTRACTORS", "MATERIAL_GROUP", "UM", "WEIGHTUM", "CUSTOMER_PN",
        "GREEN_COMPLIANCE", "MATERIAL_TYPE", "ALT_BOM_NUMBER", "ALT_BOM_PARENT",
        "CONTROLLER_TECHNOLOGY", "GROSS_DIE_WAFER", "PRODUCT_TYPE", "MARKETING_PROGRAM",
        "MEMORY_PIN_COUNT", "MEMORY_PACKAGE", "CONTROLLER_PIN_COUNT", "CONTROLLER_PACKAGE",
        "SIP_PIN_COUNT", "SIP_PACKAGE", "DESCRIPTION", "RELEASE_DATE", "AS_SOLD",
        "MARKING_SPEC", "EAN", "CARTON_AUM", "CARTON_QTY", "UPDATE_DATE", "PKG_TYPE",
        "PART_CLASS", "CATEGORY", "WIP", "CUSTOM_REV", "DLE_MODEL_NAME",
        "CUSTOMER_FIRMWARE_VERSION", "MST_FIRMWARE_VERSION", "ATA_FW_IDENTIFY",
        "USER_CAPACITY_LBA", "OLD_MATERIAL_NUMBER", "MARKETING_PROGRAM_CATEGORY",
        "PRODUCT_CUSTOMER", "PROGRAM_NAME", "IDB_PROGRAM", "SOURCE_PART_NUMBER",
        "ACTUAL_BUILD_PLANT", "REGULATORY_COMPLIANCE", "BOM_TYPE", "GOLDEN_PART",
        "KGD_TEST_FLOW", "ECCN", "STORAGE_CONDITION", "GLOBAL_OPN", "MFG_ID",
        "NEW_PART_CLASS", "CLASSIFICATION_PDM", "REV_RELEASE_DATE", "CREATE_DATE",
        "LIFECYCLE_PHASE", "SINGLE_SOLE_SOURCE"
    };

    // Part search progress tracking
    private volatile int partsRowCount = 0;
    private volatile boolean partsSearchRunning = false;
    private volatile boolean partsTruncated = false;

    public int getPartsRowCount() { return partsRowCount; }
    public boolean isPartsSearchRunning() { return partsSearchRunning; }
    public boolean isPartsTruncated() { return partsTruncated; }

    public String[] getPartExtractHeaders() {
        return ITEM_EXTRACT_COLUMNS;
    }

    public List<Map<String, String>> searchParts(String itemFilter, List<String> selectedColumns,
                                                   String releaseDateFrom, String releaseDateTo) {
        boolean hasItems = itemFilter != null && !itemFilter.trim().isEmpty() && !"*".equals(itemFilter.trim());
        boolean hasDates = (releaseDateFrom != null && !releaseDateFrom.isEmpty()) ||
                (releaseDateTo != null && !releaseDateTo.isEmpty());

        if (!hasItems && !hasDates) {
            return new ArrayList<>();
        }

        // Always include PART_NUMBER and DESCRIPTION
        List<String> queryCols = new ArrayList<>(selectedColumns);
        if (!queryCols.contains("PART_NUMBER")) queryCols.add(0, "PART_NUMBER");
        if (!queryCols.contains("DESCRIPTION")) queryCols.add("DESCRIPTION");

        // Build SQL dynamically based on selected columns
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < queryCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(queryCols.get(i));
        }
        sql.append(" FROM item_extract WHERE ");

        // Build WHERE clause for item filter
        List<String> conditions = new ArrayList<>();
        List<String> params = new ArrayList<>();

        if (hasItems) {
            String[] tokens = itemFilter.split(",");
            List<String> likeConditions = new ArrayList<>();
            List<String> exactItems = new ArrayList<>();
            for (String token : tokens) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                if (t.contains("*")) {
                    likeConditions.add("UPPER(PART_NUMBER) LIKE UPPER(?)");
                    params.add(t.replace("*", "%"));
                } else {
                    exactItems.add(t.toUpperCase());
                }
            }
            // Use IN clause for exact items (much faster than LIKE '%x%' for large lists)
            if (!exactItems.isEmpty()) {
                // Batch in groups of 500 to avoid Oracle IN clause limit
                int batchSize = 500;
                List<String> inClauses = new ArrayList<>();
                for (int start = 0; start < exactItems.size(); start += batchSize) {
                    int end = Math.min(start + batchSize, exactItems.size());
                    List<String> batch = exactItems.subList(start, end);
                    String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                    inClauses.add("UPPER(PART_NUMBER) IN (" + placeholders + ")");
                    params.addAll(batch);
                }
                likeConditions.addAll(inClauses);
            }
            if (!likeConditions.isEmpty()) {
                conditions.add("(" + String.join(" OR ", likeConditions) + ")");
            }
        }

        // Date filter on REV_RELEASE_DATE (VARCHAR "DD-MON-YYYY HH:MI:SS AM")
        // When items are specified, fetch by items first (fast), then filter dates in Java
        // When date-only (no items), we must use SQL date filter
        boolean dateFilterInJava = hasItems &&
            ((releaseDateFrom != null && !releaseDateFrom.isEmpty()) ||
             (releaseDateTo != null && !releaseDateTo.isEmpty()));
        if (!dateFilterInJava) {
            if (releaseDateFrom != null && !releaseDateFrom.isEmpty()) {
                conditions.add("REV_RELEASE_DATE IS NOT NULL");
                conditions.add("TO_DATE(SUBSTR(REV_RELEASE_DATE,1,11), 'DD-MON-YYYY') >= TO_DATE(?, 'YYYY-MM-DD')");
                params.add(releaseDateFrom);
            }
            if (releaseDateTo != null && !releaseDateTo.isEmpty()) {
                if (releaseDateFrom == null || releaseDateFrom.isEmpty()) conditions.add("REV_RELEASE_DATE IS NOT NULL");
                conditions.add("TO_DATE(SUBSTR(REV_RELEASE_DATE,1,11), 'DD-MON-YYYY') <= TO_DATE(?, 'YYYY-MM-DD') + 1");
                params.add(releaseDateTo);
            }
        }

        sql.append(String.join(" AND ", conditions));
        sql.append(" ORDER BY PART_NUMBER");
        // Safety limit for broad queries
        if (!hasItems) sql.append(" FETCH FIRST 5000 ROWS ONLY");

        List<Map<String, String>> results = new ArrayList<>();
        partsRowCount = 0;
        partsSearchRunning = true;
        partsTruncated = false;
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(300);
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            logger.info("[PART SEARCH] SQL: " + sql.toString().replace("\n", " ") + " | params: " + params);

            int MAX_PARTS_ROWS = 50000;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (results.size() >= MAX_PARTS_ROWS) {
                        partsTruncated = true;
                        logger.warning("[PART SEARCH] Exceeded " + MAX_PARTS_ROWS + " rows, truncating");
                        break;
                    }
                    Map<String, String> record = new LinkedHashMap<>();
                    for (String col : queryCols) {
                        try {
                            String val = rs.getString(col);
                            record.put(col, val != null ? val.trim() : "");
                        } catch (SQLException colErr) {
                            record.put(col, "");
                        }
                    }
                    results.add(record);
                    partsRowCount = results.size();
                }
            }
        } catch (SQLException e) {
            logger.warning("Part search failed: " + e.getMessage());
            throw new RuntimeException("Part query failed: " + e.getMessage(), e);
        } finally {
            partsSearchRunning = false;
        }

        // Java-side date filtering (much faster than TO_DATE on VARCHAR in SQL)
        if (dateFilterInJava) {
            java.time.LocalDate fromDate = (releaseDateFrom != null && !releaseDateFrom.isEmpty())
                ? java.time.LocalDate.parse(releaseDateFrom) : null;
            java.time.LocalDate toDate = (releaseDateTo != null && !releaseDateTo.isEmpty())
                ? java.time.LocalDate.parse(releaseDateTo).plusDays(1) : null;

            results = results.stream().filter(record -> {
                String revDate = record.get("REV_RELEASE_DATE");
                if (revDate == null || revDate.isEmpty()) return false;
                try {
                    java.time.LocalDate rd = parseAgileDate(revDate);
                    if (rd == null) return false;
                    if (fromDate != null && rd.isBefore(fromDate)) return false;
                    if (toDate != null && !rd.isBefore(toDate)) return false;
                    return true;
                } catch (Exception e) { return false; }
            }).collect(java.util.stream.Collectors.toList());
            logger.info("[PART SEARCH] Java date filter: " + results.size() + " rows after filtering");
        }

        return results;
    }

    private static final java.util.Map<String, Integer> MONTH_MAP_JAVA = new java.util.HashMap<>();
    static {
        MONTH_MAP_JAVA.put("JAN", 1); MONTH_MAP_JAVA.put("FEB", 2); MONTH_MAP_JAVA.put("MAR", 3);
        MONTH_MAP_JAVA.put("APR", 4); MONTH_MAP_JAVA.put("MAY", 5); MONTH_MAP_JAVA.put("JUN", 6);
        MONTH_MAP_JAVA.put("JUL", 7); MONTH_MAP_JAVA.put("AUG", 8); MONTH_MAP_JAVA.put("SEP", 9);
        MONTH_MAP_JAVA.put("OCT", 10); MONTH_MAP_JAVA.put("NOV", 11); MONTH_MAP_JAVA.put("DEC", 12);
    }

    private java.time.LocalDate parseAgileDate(String val) {
        if (val == null || val.length() < 11) return null;
        // Handle "DD-MON-YYYY ..." format
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("^(\\d{1,2})-([A-Z]{3})-(\\d{4})")
                .matcher(val.toUpperCase());
        if (m1.find()) {
            Integer month = MONTH_MAP_JAVA.get(m1.group(2));
            if (month == null) return null;
            return java.time.LocalDate.of(Integer.parseInt(m1.group(3)), month, Integer.parseInt(m1.group(1)));
        }
        // Handle "MM-DD-YYYY ..." format
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{4})")
                .matcher(val);
        if (m2.find()) {
            return java.time.LocalDate.of(Integer.parseInt(m2.group(3)), Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)));
        }
        return null;
    }

    // =========================================================================
    // Status / metadata
    // =========================================================================

    private String nvl(String val) { return val != null ? val.trim() : ""; }

    public String getDataAsOf() { return "item_extract table (refreshed periodically)"; }
    public boolean isDataLoaded() { return true; }

    public Map<String, String> getItemInfo(String partNumber) {
        Map<String, String> info = new LinkedHashMap<>();
        String sql = "SELECT DESCRIPTION, REV FROM item_extract WHERE PART_NUMBER = ?";
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    info.put("description", nvl(rs.getString("DESCRIPTION")));
                    info.put("rev", nvl(rs.getString("REV")));
                }
            }
        } catch (SQLException e) {
            logger.warning("Item info lookup failed for " + partNumber + ": " + e.getMessage());
        }
        return info;
    }

    public String getDescription(String partNumber) {
        String sql = "SELECT DESCRIPTION FROM item_extract WHERE PART_NUMBER = ?";
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.warning("Description lookup failed: " + e.getMessage());
        }
        return "";
    }
}
