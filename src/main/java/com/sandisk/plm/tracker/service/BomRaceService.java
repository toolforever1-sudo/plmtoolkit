package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRaceRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class BomRaceService {

    private static final Logger logger = Logger.getLogger(BomRaceService.class.getName());

    private final DataSource customDataSource;
    private final BomDataService bomDataService;
    private final AgileBomRaceClient agileClient;

    @Value("${app.bomrace.max-items:10}")               private int maxItems;
    @Value("${app.bomrace.sdk.item-timeout-ms:60000}")  private long sdkItemTimeoutMs;
    @Value("${app.bomrace.race-timeout-ms:300000}")     private long raceTimeoutMs;
    @Value("${app.bomrace.run-ttl-ms:600000}")          private long runTtlMs;

    private final Map<String, BomRaceRun> runs = new ConcurrentHashMap<>();

    public BomRaceService(@Qualifier("customDataSource") DataSource customDataSource,
                          BomDataService bomDataService,
                          AgileBomRaceClient agileClient) {
        this.customDataSource = customDataSource;
        this.bomDataService = bomDataService;
        this.agileClient = agileClient;
    }

    /** Best-effort schema bootstrap for the bomrace_run history table. Idempotent —
     *  swallows ORA-00955 (name already in use) so we don't fight existing schema. */
    @javax.annotation.PostConstruct
    void initHistoryTable() {
        String ddl =
            "CREATE TABLE bomrace_run (" +
            "  id VARCHAR2(40) PRIMARY KEY, " +
            "  started_at TIMESTAMP NOT NULL, " +
            "  n NUMBER NOT NULL, " +
            "  toolkit_ms NUMBER NOT NULL, " +
            "  sdk_ms NUMBER NOT NULL, " +
            "  speedup NUMBER, " +
            "  set_ok NUMBER(1) NOT NULL, " +
            "  struct_ok NUMBER(1) NOT NULL, " +
            "  username VARCHAR2(80)" +
            ")";
        try (Connection c = customDataSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute(ddl);
            logger.info("[BOM_RACE] created bomrace_run table");
        } catch (SQLException e) {
            // ORA-00955: name is already used by an existing object — that's fine
            if (e.getErrorCode() == 955) {
                logger.fine("[BOM_RACE] bomrace_run table already exists");
            } else {
                logger.warning("[BOM_RACE] bomrace_run init skipped: " + e.getMessage());
            }
        }
    }

    /** Persist a completed run summary. Called from finishRace. Soft-fails on DB errors. */
    void recordHistory(String runId, int n, long toolkitMs, long sdkMs, double speedup,
                       boolean setOk, boolean structOk, String username) {
        String sql = "INSERT INTO bomrace_run (id, started_at, n, toolkit_ms, sdk_ms, speedup, set_ok, struct_ok, username) " +
                     "VALUES (?, SYSTIMESTAMP, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = customDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setInt(2, n);
            ps.setLong(3, toolkitMs);
            ps.setLong(4, sdkMs);
            ps.setDouble(5, speedup);
            ps.setInt(6, setOk ? 1 : 0);
            ps.setInt(7, structOk ? 1 : 0);
            ps.setString(8, username == null ? "" : username);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[BOM_RACE] history insert failed: " + e.getMessage());
        }
    }

    /** Last N completed runs, newest first. Returns LinkedHashMap rows with the columns
     *  the leaderboard UI expects: when, n, toolkitMs, sdkMs, speedup, setOk, structOk, by. */
    public List<Map<String, Object>> recentRuns(int limit) {
        String sql = "SELECT id, started_at, n, toolkit_ms, sdk_ms, speedup, set_ok, struct_ok, username " +
                     "  FROM bomrace_run ORDER BY started_at DESC FETCH FIRST ? ROWS ONLY";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = customDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",         rs.getString("id"));
                    row.put("when",       rs.getTimestamp("started_at") == null ? null
                                          : rs.getTimestamp("started_at").getTime());
                    row.put("n",          rs.getInt("n"));
                    row.put("toolkitMs",  rs.getLong("toolkit_ms"));
                    row.put("sdkMs",      rs.getLong("sdk_ms"));
                    row.put("speedup",    rs.getDouble("speedup"));
                    row.put("setOk",      rs.getInt("set_ok") == 1);
                    row.put("structOk",   rs.getInt("struct_ok") == 1);
                    row.put("by",         rs.getString("username"));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("[BOM_RACE] history query failed: " + e.getMessage());
        }
        return out;
    }

    /** Lazy sweep of expired runs. Called at the top of every startRace. */
    void sweepExpired() {
        long cutoff = System.currentTimeMillis() - runTtlMs;
        runs.entrySet().removeIf(e -> e.getValue().createdAtMillis < cutoff);
    }

    /** Random sample from items that actually have BOMs (otherwise most picks would be 0-row leaves).
     *  Optional createYear filter narrows to items created in that calendar year (e.g. 2026).
     *  CREATE_DATE is stored as a VARCHAR in 'MM-DD-YYYY HH:MI:SS AM' format
     *  (see AiHelpController for the format spec) — characters 7-10 are the year. */
    public List<String> sampleRandomItemsWithBoms(int n, Integer createYear) {
        int capped = Math.min(n, maxItems);
        StringBuilder sql = new StringBuilder()
            .append("SELECT i.PART_NUMBER FROM item_extract i ")
            .append("WHERE i.PART_NUMBER IN (SELECT DISTINCT BOM_NUMBER FROM bom_extract) ");
        if (createYear != null) sql.append("AND SUBSTR(i.CREATE_DATE, 7, 4) = ? ");
        sql.append("ORDER BY DBMS_RANDOM.VALUE FETCH FIRST ? ROWS ONLY");
        List<String> out = new ArrayList<>(capped);
        try (Connection c = customDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            if (createYear != null) ps.setString(p++, String.valueOf(createYear));
            ps.setInt(p, capped);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            logger.warning("[BOM_RACE] sample query failed: " + e.getMessage());
        }
        return out;
    }

    public boolean isAgileServiceUp() { return agileClient.healthCheck(); }

    public int getMaxItems() { return maxItems; }

    /** xlsx with one sheet ("Input items") listing the raced part numbers. */
    public byte[] exportInputXlsx(String runId) {
        BomRaceRun run = runs.get(runId);
        if (run == null) return null;
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(null, 200, false, false);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet s = wb.createSheet("Input items");
            org.apache.poi.ss.usermodel.CellStyle hdr = headerStyle(wb);
            org.apache.poi.ss.usermodel.Row h = s.createRow(0);
            org.apache.poi.ss.usermodel.Cell hc = h.createCell(0);
            hc.setCellValue("Item Number"); hc.setCellStyle(hdr);
            int r = 1;
            for (String it : run.items) {
                org.apache.poi.ss.usermodel.Row row = s.createRow(r++);
                row.createCell(0).setCellValue(it == null ? "" : it);
            }
            s.setColumnWidth(0, 28 * 256);
            wb.write(bos); wb.dispose();
            return bos.toByteArray();
        } catch (Exception e) {
            logger.warning("[BOM_RACE] exportInputXlsx failed: " + e.getMessage());
            return null;
        }
    }

    /** xlsx with two sheets: "Toolkit output" and "Agile SDK output", side-by-side for diffing. */
    public byte[] exportResultsXlsx(String runId) {
        BomRaceRun run = runs.get(runId);
        if (run == null) return null;
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(null, 1000, false, false);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.CellStyle hdr = headerStyle(wb);

            // Sheet 1: Toolkit output
            org.apache.poi.ss.usermodel.Sheet sTk = wb.createSheet("Toolkit output");
            String[] tkCols = {"Level", "Parent", "Component", "Quantity", "RefDesignator", "FindNumber", "Description", "Input item"};
            org.apache.poi.ss.usermodel.Row tkH = sTk.createRow(0);
            for (int i = 0; i < tkCols.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = tkH.createCell(i);
                c.setCellValue(tkCols[i]); c.setCellStyle(hdr);
            }
            int r = 1;
            for (com.sandisk.plm.tracker.model.BomResult b : run.toolkitRows) {
                org.apache.poi.ss.usermodel.Row row = sTk.createRow(r++);
                row.createCell(0).setCellValue(b.getLevel());
                row.createCell(1).setCellValue(nz(b.getParent()));
                row.createCell(2).setCellValue(nz(b.getComponent()));
                row.createCell(3).setCellValue(nz(b.getQuantity()));
                row.createCell(4).setCellValue(nz(b.getRefDesignator()));
                row.createCell(5).setCellValue(nz(b.getFindNumber()));
                row.createCell(6).setCellValue(nz(b.getDescription()));
                row.createCell(7).setCellValue(nz(b.getInputItem()));
            }
            int[] tkWidths = {6, 22, 22, 10, 16, 12, 40, 22};
            for (int i = 0; i < tkWidths.length; i++) sTk.setColumnWidth(i, tkWidths[i] * 256);

            // Sheet 2: Agile SDK output
            org.apache.poi.ss.usermodel.Sheet sSdk = wb.createSheet("Agile SDK output");
            String[] sdkCols = {"Level", "Parent", "Component", "Quantity", "RefDesignator", "Seq"};
            org.apache.poi.ss.usermodel.Row sdkH = sSdk.createRow(0);
            for (int i = 0; i < sdkCols.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = sdkH.createCell(i);
                c.setCellValue(sdkCols[i]); c.setCellStyle(hdr);
            }
            int r2 = 1;
            for (Map<String, Object> rec : run.sdkRows) {
                org.apache.poi.ss.usermodel.Row row = sSdk.createRow(r2++);
                Object lvl = rec.get("level");
                if (lvl instanceof Number) row.createCell(0).setCellValue(((Number) lvl).intValue());
                else                       row.createCell(0).setCellValue(s(lvl));
                row.createCell(1).setCellValue(s(rec.get("parent")));
                row.createCell(2).setCellValue(s(rec.get("component")));
                row.createCell(3).setCellValue(s(rec.get("qty")));
                row.createCell(4).setCellValue(s(rec.get("refdes")));
                row.createCell(5).setCellValue(s(rec.get("seq")));
            }
            int[] sdkWidths = {6, 22, 22, 10, 16, 8};
            for (int i = 0; i < sdkWidths.length; i++) sSdk.setColumnWidth(i, sdkWidths[i] * 256);

            wb.write(bos); wb.dispose();
            return bos.toByteArray();
        } catch (Exception e) {
            logger.warning("[BOM_RACE] exportResultsXlsx failed: " + e.getMessage());
            return null;
        }
    }

    private static String nz(String v) { return v == null ? "" : v; }

    private static org.apache.poi.ss.usermodel.CellStyle headerStyle(org.apache.poi.xssf.streaming.SXSSFWorkbook wb) {
        org.apache.poi.ss.usermodel.CellStyle st = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font f = wb.createFont();
        f.setBold(true);
        f.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        st.setFont(f);
        st.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.getIndex());
        st.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        return st;
    }

    /** Per-input row counts on the toolkit side, derived from BomResult.inputItem.
     *  Lets the UI show an apples-to-apples per-item table even though the toolkit lane
     *  doesn't have per-item timings (it's a single batched call). */
    public Map<String, Integer> toolkitRowCountsByInput(String runId) {
        BomRaceRun run = runs.get(runId);
        if (run == null) return java.util.Collections.emptyMap();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String it : run.items) out.put(it, 0); // preserve input order, ensure all items present
        for (com.sandisk.plm.tracker.model.BomResult b : run.toolkitRows) {
            String input = b.getInputItem();
            if (input == null || input.isEmpty()) continue;
            out.merge(input, 1, Integer::sum);
        }
        return out;
    }

    private final Map<String, List<String>> staged = new ConcurrentHashMap<>();
    private final Map<String, Integer> stagedDepth = new ConcurrentHashMap<>();
    private final Map<String, String> stagedUser = new ConcurrentHashMap<>();

    public void preStage(String runId, List<String> items, int maxDepth, String username) {
        staged.put(runId, items);
        stagedDepth.put(runId, maxDepth);
        stagedUser.put(runId, username == null ? "" : username);
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter startStagedRace(String runId) {
        List<String> items = staged.remove(runId);
        Integer depth = stagedDepth.remove(runId);
        String username = stagedUser.remove(runId);
        if (items == null) {
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter em =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(5_000L);
            try { em.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                .name("error").data(java.util.Collections.singletonMap("error", "unknown runId"))); }
            catch (Exception ignored) {}
            em.complete();
            return em;
        }
        return startRace(runId, items, depth == null ? 20 : depth, username);
    }

    private final java.util.concurrent.ExecutorService laneExec =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bom-race-lane");
            t.setDaemon(true);
            return t;
        });

    private static final java.util.concurrent.ScheduledExecutorService TIMER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bom-race-timeout");
            t.setDaemon(true);
            return t;
        });

    /** Create a run, register an emitter, fire both lanes from the same instant.
     *  Returns the SseEmitter for the controller to hand back to the browser. */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter startRace(
            String runId, List<String> items, int maxDepth, String username) {

        sweepExpired();
        long t0 = System.nanoTime();
        BomRaceRun run = new BomRaceRun(runId, items, maxDepth, t0);
        run.username = username == null ? "" : username;
        run.emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(raceTimeoutMs + 30_000L);
        runs.put(runId, run);

        run.emitter.onCompletion(() -> cancel(run));
        run.emitter.onTimeout(()    -> cancel(run));
        run.emitter.onError(err     -> cancel(run));

        emit(run, "race-start", mapOf("runId", runId, "items", items, "startedAt", System.currentTimeMillis()));

        run.toolkitFuture = java.util.concurrent.CompletableFuture.runAsync(() -> runToolkitLane(run), laneExec);
        run.sdkFuture     = java.util.concurrent.CompletableFuture.runAsync(() -> runSdkLane(run),     laneExec);

        java.util.concurrent.CompletableFuture<Void> all =
            java.util.concurrent.CompletableFuture.allOf(run.toolkitFuture, run.sdkFuture);
        TIMER.schedule(() -> all.completeExceptionally(
                new java.util.concurrent.TimeoutException("race-timeout")),
            raceTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        all.whenComplete((v, err) -> finishRace(run, err));

        return run.emitter;
    }

    private void runToolkitLane(BomRaceRun run) {
        long t = System.nanoTime();
        try {
            // The batched path is all-or-nothing — no per-item progress to honestly emit.
            // The UI just shows a spinner on the toolkit lane until lane-done flips it to 100%.
            List<com.sandisk.plm.tracker.model.BomResult> rows =
                bomDataService.explodeMultiple(run.items, run.maxDepth);
            long ms = (System.nanoTime() - t) / 1_000_000L;
            run.toolkitRows = rows;
            emit(run, "lane-done", mapOf("lane", "toolkit", "totalMs", ms,
                "totalRows", rows.size(), "errorCount", 0));
        } catch (Exception ex) {
            long ms = (System.nanoTime() - t) / 1_000_000L;
            logger.warning("[BOM_RACE] toolkit lane failed: " + ex.getMessage());
            emit(run, "lane-done", mapOf("lane", "toolkit", "totalMs", ms,
                "totalRows", 0, "errorCount", 1, "error", ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void runSdkLane(BomRaceRun run) {
        long t = System.nanoTime();
        try {
            for (String item : run.items) {
                long elapsed = (System.nanoTime() - run.startedAtNanos) / 1_000_000L;
                emit(run, "item-start", mapOf("lane", "sdk", "item", item, "t", elapsed));

                // Per-item timeout via CompletableFuture wrap. Falls back to item-error on timeout.
                final String thisItem = item;
                java.util.concurrent.CompletableFuture<Map<String, Object>> call =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> agileClient.explode(java.util.Collections.singletonList(thisItem), run.maxDepth),
                        laneExec);
                Map<String, Object> resp;
                try {
                    resp = call.get(sdkItemTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    call.cancel(true);
                    run.sdkErrorCount++;
                    emit(run, "item-error", mapOf("lane", "sdk", "item", item,
                        "errorMs", sdkItemTimeoutMs, "message", "per-item timeout"));
                    continue;
                }

                List<Map<String, Object>> perItem = (List<Map<String, Object>>) resp.get("perItem");
                if (perItem != null && !perItem.isEmpty()) {
                    Map<String, Object> rec = perItem.get(0);
                    if (rec.get("error") != null) {
                        run.sdkErrorCount++;
                        emit(run, "item-error", mapOf("lane", "sdk", "item", item,
                            "errorMs", rec.get("durationMs"), "message", rec.get("error")));
                    } else {
                        List<Map<String, Object>> rows = (List<Map<String, Object>>) rec.get("rows");
                        emit(run, "item-done", mapOf("lane", "sdk", "item", item,
                            "rows", rows == null ? 0 : rows.size(), "durationMs", rec.get("durationMs")));
                        if (rows != null) run.sdkRows.addAll(rows);
                    }
                }
            }
            long ms = (System.nanoTime() - t) / 1_000_000L;
            emit(run, "lane-done", mapOf("lane", "sdk", "totalMs", ms,
                "totalRows", run.sdkRows.size(), "errorCount", run.sdkErrorCount));
        } catch (Exception ex) {
            long ms = (System.nanoTime() - t) / 1_000_000L;
            logger.warning("[BOM_RACE] sdk lane failed: " + ex.getMessage());
            emit(run, "lane-done", mapOf("lane", "sdk", "totalMs", ms,
                "totalRows", 0, "errorCount", 1, "error", ex.getMessage()));
        }
    }

    private void finishRace(BomRaceRun run, Throwable err) {
        long totalMs = (System.nanoTime() - run.startedAtNanos) / 1_000_000L;
        if (err != null) {
            logger.warning("[BOM_RACE] runId=" + run.runId + " ended with error: " + err.getMessage());
        }
        long toolkitMs = run.toolkitTotalMs > 0 ? run.toolkitTotalMs : totalMs;
        long sdkMs     = run.sdkTotalMs     > 0 ? run.sdkTotalMs     : totalMs;
        double speedup = toolkitMs == 0 ? 0.0 : (double) sdkMs / (double) toolkitMs;

        MatchScore setM = computeSetMatch(run.toolkitRows, run.sdkRows);
        MatchScore strM = computeStructuralMatch(run.toolkitRows, run.sdkRows);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("toolkitMs", toolkitMs);
        done.put("sdkMs", sdkMs);
        done.put("speedup", speedup);
        done.put("setMatch", mapOf("ok", setM.ok, "onlyToolkit", setM.onlyToolkit, "onlySdk", setM.onlySdk));
        done.put("structuralMatch", mapOf("ok", strM.ok,
            "onlyToolkit", strM.onlyToolkit, "onlySdk", strM.onlySdk));
        done.put("toolkitPerItem", toolkitRowCountsByInput(run.runId)); // for the per-item table
        emit(run, "race-done", done);
        run.finished = true; // must be set before complete() — cancel() checks this flag
        try { run.emitter.complete(); } catch (Exception ignored) {}
        // Persist for the leaderboard. Soft-fails on DB errors so a write hiccup
        // doesn't block the live demo.
        recordHistory(run.runId, run.items.size(), toolkitMs, sdkMs, speedup,
                      setM.ok, strM.ok, run.username);
        logger.info("[BOM_RACE] runId=" + run.runId + " finished · toolkit=" + toolkitMs +
            "ms · sdk=" + sdkMs + "ms · setMatch=" + (setM.ok ? "ok" : "diff") +
            " · structMatch=" + (strM.ok ? "ok" : "diff"));
    }

    private void cancel(BomRaceRun run) {
        if (run.toolkitFuture != null) run.toolkitFuture.cancel(true);
        if (run.sdkFuture != null) run.sdkFuture.cancel(true);
        // Only evict if the race did NOT finish normally — otherwise downloads need the run alive
        // until the lazy TTL sweep. cancel() is also wired to onCompletion (which fires after our
        // own emitter.complete() in finishRace), so we must distinguish the two paths.
        if (!run.finished) {
            runs.remove(run.runId);
        }
    }

    private void emit(BomRaceRun run, String event, Object data) {
        try {
            run.emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                .name(event).data(data));
            if ("lane-done".equals(event) && data instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) data;
                if ("toolkit".equals(m.get("lane"))) run.toolkitTotalMs = ((Number) m.get("totalMs")).longValue();
                else if ("sdk".equals(m.get("lane"))) run.sdkTotalMs = ((Number) m.get("totalMs")).longValue();
            }
        } catch (java.io.IOException ignored) {
            // Browser disconnected. cancel() will fire via emitter callbacks.
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static class MatchScore {
        public boolean ok;
        public Set<String> onlyToolkit = new LinkedHashSet<>();
        public Set<String> onlySdk = new LinkedHashSet<>();
    }

    /** Set-match: forgiving headline. "Did both find the same set of distinct component part numbers?" */
    public static MatchScore computeSetMatch(List<com.sandisk.plm.tracker.model.BomResult> toolkit,
                                             List<Map<String, Object>> sdk) {
        Set<String> tkSet = new LinkedHashSet<>();
        for (com.sandisk.plm.tracker.model.BomResult b : toolkit) {
            if (b.getComponent() != null && !b.getComponent().isEmpty())
                tkSet.add(b.getComponent().trim().toUpperCase());
        }
        Set<String> sdkSet = new LinkedHashSet<>();
        for (Map<String, Object> r : sdk) {
            Object c = r.get("component");
            if (c != null && !c.toString().isEmpty())
                sdkSet.add(c.toString().trim().toUpperCase());
        }
        MatchScore s = new MatchScore();
        s.onlyToolkit = new LinkedHashSet<>(tkSet); s.onlyToolkit.removeAll(sdkSet);
        s.onlySdk     = new LinkedHashSet<>(sdkSet); s.onlySdk.removeAll(tkSet);
        s.ok = s.onlyToolkit.isEmpty() && s.onlySdk.isEmpty();
        return s;
    }

    /** Structural-match: stricter. "Did both agree on every parent->child edge AND quantity?" */
    public static MatchScore computeStructuralMatch(List<com.sandisk.plm.tracker.model.BomResult> toolkit,
                                                    List<Map<String, Object>> sdk) {
        Set<String> tkEdges = new LinkedHashSet<>();
        for (com.sandisk.plm.tracker.model.BomResult b : toolkit) {
            tkEdges.add(edgeKey(b.getParent(), b.getComponent(), b.getQuantity()));
        }
        Set<String> sdkEdges = new LinkedHashSet<>();
        for (Map<String, Object> r : sdk) {
            sdkEdges.add(edgeKey(s(r.get("parent")), s(r.get("component")), s(r.get("qty"))));
        }
        MatchScore s = new MatchScore();
        s.onlyToolkit = new LinkedHashSet<>(tkEdges); s.onlyToolkit.removeAll(sdkEdges);
        s.onlySdk     = new LinkedHashSet<>(sdkEdges); s.onlySdk.removeAll(tkEdges);
        s.ok = s.onlyToolkit.isEmpty() && s.onlySdk.isEmpty();
        return s;
    }

    private static String edgeKey(String parent, String child, String qty) {
        return up(parent) + "→" + up(child) + "@" + (qty == null ? "" : qty.trim());
    }
    private static String up(String v) { return v == null ? "" : v.trim().toUpperCase(); }
    private static String s(Object o)  { return o == null ? "" : o.toString(); }
}
