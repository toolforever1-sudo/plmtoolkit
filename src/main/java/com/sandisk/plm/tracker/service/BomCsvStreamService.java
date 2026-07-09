package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a FULL, path-expanded BOM explode/implode to a zipped CSV — the escape
 * hatch for results too large for Excel's 1,048,576-row sheet (e.g. a bundle SKU
 * that explodes to ~21M path rows). Everything streams: no in-memory row list,
 * no giant JSON — memory stays flat regardless of size.
 *
 * <p>Two row sources, picked at runtime:
 * <ul>
 *   <li><b>In-memory BOM graph</b> ({@link BomExtractFileService}) when the offline
 *       file is loaded — fast, zero DB load, enriched from the item cache.</li>
 *   <li><b>DB CONNECT BY</b> with a forward-only streaming {@code ResultSet}
 *       otherwise — works on any environment that has {@code bom_extract}, no file
 *       needed. The query already LEFT JOINs {@code item_extract}, so rows arrive
 *       enriched.</li>
 * </ul>
 */
@Service
public class BomCsvStreamService {

    private static final Logger logger = Logger.getLogger(BomCsvStreamService.class.getName());

    private final DataSource customDataSource;
    private final BomExtractFileService fileFallback;

    public BomCsvStreamService(@Qualifier("customDataSource") DataSource customDataSource,
                               BomExtractFileService fileFallback) {
        this.customDataSource = customDataSource;
        this.fileFallback = fileFallback;
    }

    private static final String[] HEADERS = {
        "Level", "Input PN", "Parent", "Component", "Qty", "Ref Des", "Seq", "Notes",
        "Description", "Status", "Rev", "Part Class", "Lifecycle", "Product Line",
        "Subcontractors", "Build Plant", "BOM Path"
    };

    private static final String EXPLODE_SQL =
        "SELECT LEVEL AS lvl, PRIOR b.BOM_NUMBER AS parent, b.COMPONENT_NUMBER AS component, " +
        "       b.QTY, b.REFERENCE_DESIGNATOR, b.SEQ, b.NOTES, i.DESCRIPTION, i.STATUSCODE, " +
        "       i.REV, i.NEW_PART_CLASS, i.LIFECYCLE_PHASE, i.PRODUCTLINE, i.SUBCONTRACTORS, " +
        "       i.ACTUAL_BUILD_PLANT, SYS_CONNECT_BY_PATH(b.COMPONENT_NUMBER, ' > ') AS bom_path " +
        "FROM bom_extract b LEFT JOIN item_extract i ON i.PART_NUMBER = b.COMPONENT_NUMBER " +
        "START WITH b.BOM_NUMBER = ? " +
        "CONNECT BY NOCYCLE PRIOR b.COMPONENT_NUMBER = b.BOM_NUMBER AND LEVEL <= ?";

    private static final String IMPLODE_SQL =
        "SELECT LEVEL AS lvl, b.BOM_NUMBER AS parent, b.COMPONENT_NUMBER AS component, " +
        "       b.QTY, b.REFERENCE_DESIGNATOR, b.SEQ, b.NOTES, i.DESCRIPTION, i.STATUSCODE, " +
        "       i.REV, i.NEW_PART_CLASS, i.LIFECYCLE_PHASE, i.PRODUCTLINE, i.SUBCONTRACTORS, " +
        "       i.ACTUAL_BUILD_PLANT, SYS_CONNECT_BY_PATH(b.BOM_NUMBER, ' > ') AS bom_path " +
        "FROM bom_extract b LEFT JOIN item_extract i ON i.PART_NUMBER = b.BOM_NUMBER " +
        "START WITH b.COMPONENT_NUMBER = ? " +
        "CONNECT BY NOCYCLE PRIOR b.BOM_NUMBER = b.COMPONENT_NUMBER AND LEVEL <= ?";

    /** Stream all items' full walk into a single zipped CSV written to {@code out}.
     *  Returns total rows written. */
    public long streamZip(List<String> items, int maxDepth, boolean explode,
                          String csvBaseName, OutputStream out) throws IOException {
        long total = 0;
        long t0 = System.currentTimeMillis();
        // isAvailable() (file configured/on disk), NOT isLoaded() — the graph
        // lazy-loads, so isLoaded() is false until the first walk. streamWalk()
        // calls ensureLoaded() and returns -1 only if the file truly can't load,
        // in which case we fall through to the DB path per item.
        boolean useFile = fileFallback != null && fileFallback.isAvailable();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16))) {
            // BEST_SPEED: BOM CSV is highly repetitive (part numbers, " > " paths) so
            // it still compresses ~5-8x, but at a fraction of the CPU of the default
            // level — the difference between minutes and tens of minutes on a 20M-row
            // explosion where DEFLATE, not the walk, is the bottleneck.
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED);
            zos.putNextEntry(new ZipEntry(csvBaseName + ".csv"));
            Writer w = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8), 1 << 16);
            w.write(String.join(",", HEADERS));
            w.write("\r\n");
            for (String item : items) {
                String it = item == null ? "" : item.trim();
                if (it.isEmpty()) continue;
                long n = -1;
                if (useFile) n = fileFallback.streamWalk(it, maxDepth, explode, sink(w, it));
                if (n < 0) n = streamFromDb(it, maxDepth, explode, w);   // file not loaded → DB
                total += n;
            }
            w.flush();
            zos.closeEntry();
        }
        logger.info("[BOM CSV] " + (explode ? "explode" : "implode") + " " + items
                + " depth=" + maxDepth + " source=" + (useFile ? "file" : "db")
                + " rows=" + total + " in " + (System.currentTimeMillis() - t0) + " ms");
        return total;
    }

    /** Adapter: file-graph walk row → CSV line. */
    private BomExtractFileService.BomRowSink sink(Writer w, String input) {
        return (level, in, parent, component, qty, refDes, seq, notes, meta, path) ->
            writeRow(w, level, in, parent, component, qty, refDes, seq, notes,
                mv(meta, "DESCRIPTION"), mv(meta, "STATUSCODE"), mv(meta, "REV"),
                mv(meta, "NEW_PART_CLASS"), mv(meta, "LIFECYCLE_PHASE"), mv(meta, "PRODUCTLINE"),
                mv(meta, "SUBCONTRACTORS"), mv(meta, "ACTUAL_BUILD_PLANT"), path);
    }

    private long streamFromDb(String item, int maxDepth, boolean explode, Writer w) throws IOException {
        String sql = explode ? EXPLODE_SQL : IMPLODE_SQL;
        long n = 0;
        try (Connection c = customDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(5000);
            ps.setQueryTimeout(600);
            ps.setString(1, item);
            ps.setInt(2, maxDepth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String parent = rs.getString("parent");
                    if (parent == null) parent = item;
                    String path = item + nvl(rs.getString("bom_path"));
                    writeRow(w, rs.getInt("lvl"), item, parent, rs.getString("component"),
                        nvl(rs.getString("QTY")), nvl(rs.getString("REFERENCE_DESIGNATOR")),
                        nvl(rs.getString("SEQ")), nvl(rs.getString("NOTES")),
                        nvl(rs.getString("DESCRIPTION")), nvl(rs.getString("STATUSCODE")),
                        nvl(rs.getString("REV")), nvl(rs.getString("NEW_PART_CLASS")),
                        nvl(rs.getString("LIFECYCLE_PHASE")), nvl(rs.getString("PRODUCTLINE")),
                        nvl(rs.getString("SUBCONTRACTORS")), nvl(rs.getString("ACTUAL_BUILD_PLANT")),
                        path);
                    n++;
                }
            }
        } catch (SQLException e) {
            logger.warning("[BOM CSV] DB stream failed for " + item + ": " + e.getMessage());
            throw new IOException("BOM CSV query failed: " + e.getMessage(), e);
        }
        return n;
    }

    private void writeRow(Writer w, int level, String input, String parent, String component,
                          String qty, String refDes, String seq, String notes,
                          String desc, String status, String rev, String partClass,
                          String lifecycle, String productLine, String subcon, String buildPlant,
                          String path) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append(level).append(',');
        csv(sb, input).append(',');
        csv(sb, parent).append(',');
        csv(sb, component).append(',');
        csv(sb, qty).append(',');
        csv(sb, refDes).append(',');
        csv(sb, seq).append(',');
        csv(sb, notes).append(',');
        csv(sb, desc).append(',');
        csv(sb, status).append(',');
        csv(sb, rev).append(',');
        csv(sb, partClass).append(',');
        csv(sb, lifecycle).append(',');
        csv(sb, productLine).append(',');
        csv(sb, subcon).append(',');
        csv(sb, buildPlant).append(',');
        csv(sb, path).append("\r\n");
        w.write(sb.toString());
    }

    /** Append a CSV-escaped field (RFC-4180: quote if it has comma/quote/newline). */
    private static StringBuilder csv(StringBuilder sb, String v) {
        if (v == null || v.isEmpty()) return sb;
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needQuote) return sb.append(v);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"') sb.append('"');
            sb.append(ch);
        }
        return sb.append('"');
    }

    private static String mv(Map<String, String> meta, String k) {
        return meta == null ? "" : nvl(meta.get(k));
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
