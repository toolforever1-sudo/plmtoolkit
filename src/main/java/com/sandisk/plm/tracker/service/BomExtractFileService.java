package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Offline BOM-Extract.txt fallback for deep explodes/implodes whose live SQL
 * traversal blows past the 50K-rows-per-item cap. Lazy-loaded into an in-memory
 * parent→children + child→parents index on first use; component-level metadata
 * (description / rev / status / item type) is enriched from item_extract on
 * each call so the file rows match the shape of the SQL path.
 *
 * <p>File format (tab-separated, header on first row): BOM_NUMBER ·
 * COMPONENT_NUMBER · REF · QTY · TYPE · REMARK · NOTES · FORECAST · OP ·
 * REFERENCE_DESIGNATOR · BOM_REV · SEQ · STATUS · TYPE · BOM_Green_Compliance ·
 * Primary_PN.
 */
@Service
public class BomExtractFileService {

    private static final Logger logger = Logger.getLogger(BomExtractFileService.class.getName());

    private final String filePath;
    private final DataSource customDataSource;
    /** In-memory item_extract cache (778K rows, all the columns enrich needs).
     *  Used to enrich file-fallback rows without per-1000 DB round-trips — a
     *  247K-assembly where-used doing 248 IN-list scans blew the run timeout. */
    private final ItemCacheService itemCache;

    private final Object loadLock = new Object();
    private volatile boolean loaded = false;
    private volatile String dataAsOf = "";
    private volatile int rowCount = 0;
    private volatile int uniqueItems = 0;
    /** Set by the most recent walk when it hit {@link #MAX_OUT_ROWS}. Read by the
     *  caller to flag the result as truncated. Not thread-safe across concurrent
     *  walks on the same instance, but BOM runs are serialized per request. */
    private volatile boolean lastTruncated = false;

    private Map<String, List<FileRow>> parentToChildren;
    private Map<String, List<FileRow>> childToParents;

    /** Small daemon pool to parallelize the per-1000-part item_extract enrichment
     *  round-trips. Sequential lookups for a 247K-assembly where-used (≈248
     *  round-trips) blew the 120s BOM run timeout; fanning them out keeps a large
     *  distinct result inside the budget. */
    private final java.util.concurrent.ExecutorService enrichPool =
        java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "bom-file-enrich"); t.setDaemon(true); return t;
        });

    public BomExtractFileService(
            @Value("${app.bom.fallback-file:}") String filePath,
            @Qualifier("customDataSource") DataSource customDataSource,
            ItemCacheService itemCache) {
        this.filePath = (filePath == null) ? "" : filePath.trim();
        this.customDataSource = customDataSource;
        this.itemCache = itemCache;
    }

    public boolean isAvailable() {
        if (filePath.isEmpty()) return false;
        try {
            return Files.exists(Paths.get(filePath));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isLoaded() { return loaded; }
    /** True if the most recent explode/implode walk hit the row cap. */
    public boolean wasTruncated() { return lastTruncated; }
    /** True if {@code part} appears as a component somewhere (has ≥1 parent
     *  assembly) per the in-memory graph — i.e. it is NOT a top-level assembly.
     *  Lets top-level marking skip the per-500-parent bom_extract scans. */
    public boolean hasParents(String part) {
        if (!loaded || part == null || childToParents == null) return false;
        List<FileRow> p = childToParents.get(part.trim());
        return p != null && !p.isEmpty();
    }
    public String getDataAsOf() { return dataAsOf; }
    public int getRowCount() { return rowCount; }
    public int getUniqueItems() { return uniqueItems; }
    public String getFilePath() { return filePath; }

    public List<BomResult> explodeFromFile(String item, int maxDepth) {
        ensureLoaded();
        if (!loaded) return Collections.emptyList();
        return walkPaths(item, maxDepth, /*explode=*/true);
    }

    public List<BomResult> implodeFromFile(String item, int maxDepth) {
        ensureLoaded();
        if (!loaded) return Collections.emptyList();
        // PT: where-used is de-duplicated to DISTINCT assemblies. Path-expansion
        // (every root→node route) explodes combinatorially for hub parts — e.g.
        // 90-02-00194 yields ~6.4M path rows but only ~247K distinct assemblies —
        // which overflows Excel's 1,048,576-row sheet and balloons memory. BFS
        // emits each assembly once, at its shallowest level, with one representative
        // path, so the report lists every assembly that uses the part and fits Excel.
        return walkDistinct(item, maxDepth);
    }

    /** Excel tops out at 1,048,576 rows/sheet; keep the cap safely under it so a
     *  full result set still writes without a POI overflow. */
    private static final int MAX_OUT_ROWS = 1_000_000;

    // ----- Explode: full path expansion (unchanged multi-level BOM tree) -----
    private List<BomResult> walkPaths(String root, int maxDepth, boolean explode) {
        String r = root == null ? "" : root.trim();
        if (r.isEmpty()) return Collections.emptyList();
        lastTruncated = false;
        Map<String, List<FileRow>> idx = explode ? parentToChildren : childToParents;
        List<BomResult> out = new ArrayList<>();
        List<String> ancestry = new ArrayList<>();
        ancestry.add(r);
        walkRec(r, 1, maxDepth, idx, out, ancestry, explode, r);
        if (!out.isEmpty()) enrich(out, explode);
        return out;
    }

    // ----- Implode: distinct assemblies (BFS, shallowest level, one path) -----
    private List<BomResult> walkDistinct(String root, int maxDepth) {
        String r = root == null ? "" : root.trim();
        if (r.isEmpty()) return Collections.emptyList();
        lastTruncated = false;
        List<BomResult> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(r);
        // queue entries: [node, level, pathSoFar]
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{ r, 0, r });
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            String node = (String) cur[0];
            int level = (Integer) cur[1];
            String path = (String) cur[2];
            if (level + 1 > maxDepth) continue;   // parents would sit at level+1
            List<FileRow> parents = childToParents.get(node);
            if (parents == null) continue;
            for (FileRow fr : parents) {
                String par = fr.parent;            // the assembly that uses `node`
                if (par == null || par.isEmpty() || visited.contains(par)) continue;
                visited.add(par);
                String childPath = path + " > " + par;
                BomResult br = new BomResult(
                    level + 1, par, node, fr.qty, "", fr.notes, "", "", fr.refDes, fr.seq, "");
                br.setPath(childPath);
                br.setInputItem(r);
                out.add(br);
                if (out.size() >= MAX_OUT_ROWS) {
                    lastTruncated = true;
                    if (!out.isEmpty()) enrich(out, /*explode=*/false);
                    return out;
                }
                queue.add(new Object[]{ par, level + 1, childPath });
            }
        }
        if (!out.isEmpty()) enrich(out, /*explode=*/false);
        return out;
    }

    private void walkRec(String node, int level, int maxDepth,
                         Map<String, List<FileRow>> idx, List<BomResult> out,
                         List<String> ancestry, boolean explode, String inputRoot) {
        if (level > maxDepth) return;
        if (out.size() >= MAX_OUT_ROWS) { lastTruncated = true; return; }
        List<FileRow> next = idx.get(node);
        if (next == null) return;
        for (FileRow fr : next) {
            if (out.size() >= MAX_OUT_ROWS) { lastTruncated = true; return; }
            String childKey      = explode ? fr.component : fr.parent;
            String parentLabel   = explode ? node         : fr.parent;
            String componentLabel= explode ? fr.component : node;

            if (ancestry.contains(childKey)) continue;

            StringBuilder sb = new StringBuilder();
            for (String a : ancestry) sb.append(a).append(" > ");
            sb.append(childKey);

            BomResult br = new BomResult(
                level,
                parentLabel,
                componentLabel,
                fr.qty,
                "",         // description (enriched later)
                fr.notes,
                "",         // status (enriched)
                "",         // rev (enriched)
                fr.refDes,
                fr.seq,
                ""          // itemType (enriched)
            );
            br.setPath(sb.toString());
            br.setInputItem(inputRoot);
            out.add(br);

            ancestry.add(childKey);
            walkRec(childKey, level + 1, maxDepth, idx, out, ancestry, explode, inputRoot);
            ancestry.remove(ancestry.size() - 1);
        }
    }

    /** Sink for streaming a walk row-by-row (no in-memory materialization).
     *  {@code meta} is the item cache record for the enriched part (may be null). */
    public interface BomRowSink {
        void accept(int level, String input, String parent, String component,
                    String qty, String refDes, String seq, String notes,
                    Map<String, String> meta, String path) throws java.io.IOException;
    }

    /** Very high backstop so a pathological cycle can't produce an unbounded stream
     *  (NOCYCLE within a path already prevents normal cycles). */
    private static final long CSV_SAFETY_CAP = 100_000_000L;

    /** Stream a FULL path-expanded walk (explode or implode) through {@code sink}
     *  without building a list — used by the CSV export so a 21M-row explosion
     *  streams straight to the (zipped) response at flat memory. Enriches each row
     *  from the in-memory item cache. Returns the number of rows emitted, or -1 if
     *  the graph isn't loaded (caller should fall back to the DB path). */
    public long streamWalk(String root, int maxDepth, boolean explode, BomRowSink sink)
            throws java.io.IOException {
        ensureLoaded();
        if (!loaded) return -1;
        String r = root == null ? "" : root.trim();
        if (r.isEmpty()) return 0;
        Map<String, List<FileRow>> idx = explode ? parentToChildren : childToParents;
        long[] count = { 0 };
        // onPath: O(1) cycle check. path: running "a > b > c" built incrementally
        // (append on descend, truncate on return) so we never rebuild it per row.
        HashSet<String> onPath = new HashSet<>();
        onPath.add(r);
        StringBuilder path = new StringBuilder(256).append(r);
        streamRec(r, 1, maxDepth, idx, explode, r, onPath, path, sink, count);
        return count[0];
    }

    private void streamRec(String node, int level, int maxDepth, Map<String, List<FileRow>> idx,
                           boolean explode, String root, HashSet<String> onPath, StringBuilder path,
                           BomRowSink sink, long[] count) throws java.io.IOException {
        if (level > maxDepth || count[0] >= CSV_SAFETY_CAP) return;
        List<FileRow> next = idx.get(node);
        if (next == null) return;
        boolean cacheReady = itemCache != null && itemCache.isLoaded();
        for (FileRow fr : next) {
            if (count[0] >= CSV_SAFETY_CAP) return;
            String childKey       = explode ? fr.component : fr.parent;
            String parentLabel    = explode ? node         : fr.parent;
            String componentLabel = explode ? fr.component : node;
            if (childKey == null || childKey.isEmpty() || onPath.contains(childKey)) continue;

            int len = path.length();
            path.append(" > ").append(childKey);

            String enrichKey = explode ? componentLabel : parentLabel;
            Map<String, String> meta = cacheReady ? itemCache.getRecord(enrichKey) : null;
            sink.accept(level, root, parentLabel, componentLabel,
                    fr.qty, fr.refDes, fr.seq, fr.notes, meta, path.toString());
            count[0]++;

            onPath.add(childKey);
            streamRec(childKey, level + 1, maxDepth, idx, explode, root, onPath, path, sink, count);
            onPath.remove(childKey);
            path.setLength(len);
        }
    }

    private void enrich(List<BomResult> rows, boolean explode) {
        Set<String> parts = new HashSet<>();
        for (BomResult br : rows) {
            // For explode the component column is what the UI cares about; for
            // implode it's the parent. Pull both to be safe — extra rows in the
            // IN list are cheap.
            if (br.getComponent() != null && !br.getComponent().isEmpty()) parts.add(br.getComponent());
            if (br.getParent() != null && !br.getParent().isEmpty()) parts.add(br.getParent());
        }
        if (parts.isEmpty()) return;

        // Prefer the in-memory item_extract cache (778K rows, all needed columns):
        // O(1) lookups instead of 248 IN-list DB scans for a 247K-assembly result.
        // Fall back to direct DB batches only if the cache isn't seeded yet.
        Map<String, String[]> meta = (itemCache != null && itemCache.isLoaded())
                ? enrichFromCache(parts)
                : enrichFromDb(parts);

        // For explode, attach meta to the component column. For implode, attach
        // to the parent column (which is the assembly being surfaced).
        for (BomResult br : rows) {
            String key = explode ? br.getComponent() : br.getParent();
            String[] m = meta.get(key);
            if (m == null) continue;
            br.setDescription(m[0]);
            br.setStatus(m[1]);
            br.setRev(m[2]);
            br.setItemType(m[3]);
            br.setLifecyclePhase(m[4]);
            br.setProductLine(m[5]);
            br.setSubcontractors(m[6]);
            br.setActualBuildPlant(m[7]);
        }
    }

    /** Enrich from the in-memory item cache — O(1) per part, no DB round-trips. */
    private Map<String, String[]> enrichFromCache(Set<String> parts) {
        Map<String, String[]> meta = new HashMap<>(parts.size() * 2);
        for (String p : parts) {
            Map<String, String> rec = itemCache.getRecord(p);
            if (rec == null) continue;
            meta.put(p, new String[] {
                nvl(rec.get("DESCRIPTION")),
                nvl(rec.get("STATUSCODE")),
                nvl(rec.get("REV")),
                nvl(rec.get("NEW_PART_CLASS")),
                nvl(rec.get("LIFECYCLE_PHASE")),
                nvl(rec.get("PRODUCTLINE")),
                nvl(rec.get("SUBCONTRACTORS")),
                nvl(rec.get("ACTUAL_BUILD_PLANT"))
            });
        }
        return meta;
    }

    /** Fallback enrichment straight from item_extract when the cache isn't seeded.
     *  Parallelized per-1000-part IN-list batches (Oracle's IN cap is 1000). */
    private Map<String, String[]> enrichFromDb(Set<String> parts) {
        java.util.concurrent.ConcurrentHashMap<String, String[]> meta =
            new java.util.concurrent.ConcurrentHashMap<>(parts.size() * 2);
        List<String> list = new ArrayList<>(parts);
        int CHUNK = 1000;
        List<java.util.concurrent.Future<?>> futs = new ArrayList<>();
        for (int s = 0; s < list.size(); s += CHUNK) {
            final int from = s, to = Math.min(s + CHUNK, list.size());
            futs.add(enrichPool.submit(() -> {
                List<String> batch = list.subList(from, to);
                String in = String.join(",", Collections.nCopies(batch.size(), "?"));
                String sql = "SELECT PART_NUMBER, DESCRIPTION, STATUSCODE, REV, NEW_PART_CLASS, " +
                    "LIFECYCLE_PHASE, PRODUCTLINE, SUBCONTRACTORS, ACTUAL_BUILD_PLANT " +
                    "FROM item_extract WHERE PART_NUMBER IN (" + in + ")";
                try (Connection c = customDataSource.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    for (String p : batch) ps.setString(i++, p);
                    ps.setQueryTimeout(60);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String pn = rs.getString("PART_NUMBER");
                            meta.put(pn, new String[] {
                                nvl(rs.getString("DESCRIPTION")),
                                nvl(rs.getString("STATUSCODE")),
                                nvl(rs.getString("REV")),
                                nvl(rs.getString("NEW_PART_CLASS")),
                                nvl(rs.getString("LIFECYCLE_PHASE")),
                                nvl(rs.getString("PRODUCTLINE")),
                                nvl(rs.getString("SUBCONTRACTORS")),
                                nvl(rs.getString("ACTUAL_BUILD_PLANT"))
                            });
                        }
                    }
                } catch (SQLException ex) {
                    logger.warning("[BOM FILE] enrich batch failed: " + ex.getMessage());
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futs) {
            try { f.get(90, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ex) { logger.warning("[BOM FILE] enrich await failed: " + ex.getMessage()); }
        }
        return meta;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private void ensureLoaded() {
        if (loaded) return;
        if (filePath.isEmpty()) return;
        synchronized (loadLock) {
            if (loaded) return;
            try {
                load();
                loaded = true;
            } catch (Exception e) {
                logger.warning("[BOM FILE] load failed for " + filePath + ": " + e.getMessage());
            }
        }
    }

    private void load() throws IOException {
        long t0 = System.currentTimeMillis();
        Path p = Paths.get(filePath);
        if (!Files.exists(p)) throw new IOException("file not found: " + filePath);

        File f = p.toFile();
        dataAsOf = new SimpleDateFormat("MMM d, yyyy").format(new java.util.Date(f.lastModified()));

        Map<String, List<FileRow>> pToC = new HashMap<>(2_000_000);
        Map<String, List<FileRow>> cToP = new HashMap<>(2_000_000);
        Map<String, String> intern = new HashMap<>(3_000_000);
        int n = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(filePath), StandardCharsets.ISO_8859_1), 1 << 20)) {
            String line = br.readLine();  // skip header
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 12) continue;
                String parent = internOf(intern, parts[0].trim());
                String comp   = internOf(intern, parts[1].trim());
                if (parent.isEmpty() || comp.isEmpty()) continue;
                String qty    = parts[3].trim();
                String notes  = parts.length > 6  ? parts[6].trim()  : "";
                String refDes = parts.length > 9  ? parts[9].trim()  : "";
                String seq    = parts.length > 11 ? parts[11].trim() : "";
                FileRow fr = new FileRow(parent, comp, qty, refDes, seq, notes);
                pToC.computeIfAbsent(parent, k -> new ArrayList<>(4)).add(fr);
                cToP.computeIfAbsent(comp,   k -> new ArrayList<>(2)).add(fr);
                n++;
            }
        }
        rowCount = n;
        uniqueItems = intern.size();
        parentToChildren = pToC;
        childToParents = cToP;
        intern.clear();
        long ms = System.currentTimeMillis() - t0;
        logger.info("[BOM FILE] loaded " + n + " rows / " + uniqueItems
            + " unique items from " + filePath + " in " + ms + " ms (data as of " + dataAsOf + ")");
    }

    private static String internOf(Map<String, String> map, String s) {
        if (s == null) return "";
        String x = map.get(s);
        if (x != null) return x;
        map.put(s, s);
        return s;
    }

    private static final class FileRow {
        final String parent, component, qty, refDes, seq, notes;
        FileRow(String parent, String component, String qty, String refDes, String seq, String notes) {
            this.parent = parent;
            this.component = component;
            this.qty = qty;
            this.refDes = refDes;
            this.seq = seq;
            this.notes = notes;
        }
    }
}
