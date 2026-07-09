package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRow;
import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@Service
public class SingleSoleSourceService {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceService.class.getName());

    @Autowired private DataSource dataSource;
    @Autowired private SingleSoleSourceExcelService excelService;
    @Autowired private SingleSoleSourceRunHistory runHistory;
    @Autowired(required = false) private SingleSoleSourceSharePointUploader sharepointUploader;
    @Autowired(required = false) private SingleSoleSourceEmailService emailService;

    @Value("${app.singlesole.output.dir}")
    private String outputDir;

    /**
     * Main query. Uses scalar MIN() subqueries for LISTENTRY/NODETABLE lookups instead of
     * LEFT JOIN — LISTENTRY ENTRYIDs are not unique across PARENTIDs (e.g. PREFER_STATUS=1
     * matches 321 different list rows), so a direct LEFT JOIN explodes the row count by
     * hundreds. Scalar MIN matches the documented Agile-PLM resolution semantics
     * (see docs/SKU-DATA-HANDOFF.md "List Value Resolution").
     *
     * Product line is left as a raw CSV ID string and resolved in Java via a targeted
     * LISTENTRY lookup keyed on the IDs we actually saw.
     */
    private static final String QUERY =
        "SELECT i.ID AS item_id, i.ITEM_NUMBER AS number_,\n" +
        "       i.DESCRIPTION AS description,\n" +
        "       i.PRODUCT_LINES AS product_lines,\n" +
        "       (SELECT MIN(n.DESCRIPTION) FROM AGILE.NODETABLE n WHERE n.ID = r.RELEASE_TYPE) AS lifecycle_phase,\n" +
        "       r.REV_NUMBER AS rev,\n" +
        "       (SELECT MIN(n.DESCRIPTION) FROM AGILE.NODETABLE n WHERE n.ID = i.SUBCLASS) AS part_type,\n" +
        "       TO_NUMBER(NULLIF(TRIM(p2.TEXT67), '')) AS mpn_count,\n" +
        "       (SELECT MIN(le.ENTRYVALUE) FROM AGILE.LISTENTRY le WHERE le.ENTRYID = p2.LIST77 AND le.LANGID = 0) AS single_sole_source,\n" +
        "       (SELECT MIN(le.ENTRYVALUE) FROM AGILE.LISTENTRY le WHERE le.ENTRYID = p2.LIST20 AND le.LANGID = 0) AS material_group,\n" +
        "       TRUNC(p2.DATE04) AS create_date,\n" +
        "       TRUNC(r.RELEASE_DATE) AS rev_release_date,\n" +
        "       mfr.NAME AS mfr_name,\n" +
        "       mp.PART_NUMBER AS mfr_part_number,\n" +
        "       (SELECT MIN(le.ENTRYVALUE) FROM AGILE.LISTENTRY le WHERE le.ENTRYID = mb.PREFER_STATUS AND le.PARENTID = 2249 AND le.LANGID = 0) AS preferred_status\n" +
        "  FROM AGILE.ITEM i\n" +
        "  JOIN AGILE.REV r ON r.ITEM = i.ID AND r.CHANGE = i.DEFAULT_CHANGE AND r.SITE = 0\n" +
        "  JOIN AGILE.PAGE_TWO p2 ON p2.ID = i.ID\n" +
        "  LEFT JOIN AGILE.MANU_BY mb ON mb.AGILE_PART = i.ID AND mb.CHANGE_OUT = 0 AND mb.MANU_PART > 0\n" +
        "  LEFT JOIN AGILE.MANU_PARTS mp ON mp.ID = mb.MANU_PART\n" +
        "  LEFT JOIN AGILE.MANUFACTURERS mfr ON mfr.ID = mp.MANU_ID\n" +
        " WHERE NVL(i.DELETE_FLAG, 0) <> 1\n" +
        "   AND i.SUBCLASS IN (SELECT ID FROM AGILE.NODETABLE WHERE PARENTID = 10004)\n" +
        "   AND p2.LIST77 IN (4026098, 4026102, 4026103)\n" +
        "   AND (r.RELEASE_TYPE IS NULL\n" +
        "        OR r.RELEASE_TYPE NOT IN (SELECT ID FROM AGILE.NODETABLE WHERE DESCRIPTION IN ('OBS','OBS-SKU','Preliminary')))\n" +
        " ORDER BY TRUNC(r.RELEASE_DATE) DESC NULLS LAST, i.ITEM_NUMBER";

    /**
     * Returns the rows used for the report. Granularity:
     *   - Designation Needed: one row per item (first MPN if multiple — dedup by item_id).
     *   - Single Source / Sole Source: one row per active MPN (item rows repeat).
     */
    public List<SingleSoleSourceRow> fetch() throws Exception {
        long t0 = System.currentTimeMillis();
        List<SingleSoleSourceRow> rows = new ArrayList<>();
        java.util.Set<Long> productLineIds = new java.util.HashSet<>();
        java.util.Map<Long, String[]> rawProductLines = new java.util.HashMap<>();  // rowIdx -> id[]

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(QUERY)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                // PT-65 follow-up: dedup all three categories by item_id (was previously
                // dedup'd only for Designation Needed; Single/Sole repeated per MPN row).
                // Shruthi's reference is Agile Advanced Search which returns one row per
                // item across all three values, so matching that granularity makes the
                // toolkit's summary counts and Excel rows align with Agile UI exactly
                // (299 / 762 / 31 on her May-27 snapshot).
                Set<Long> seenItem = new HashSet<>();
                long rowKey = 0;
                long skippedNonprod = 0;
                long skippedNeededMpnNot1 = 0;
                while (rs.next()) {
                    String src = rs.getString("single_sole_source");
                    long itemId = rs.getLong("item_id");
                    String materialGroup = rs.getString("material_group");
                    int mpnInt = rs.getInt("mpn_count");
                    Integer mpnCount = rs.wasNull() ? null : mpnInt;

                    // PT-65: Agile Advanced Search (the field team's reference) filters
                    // Material Group NOT IN ('NONPROD') across all three SSS values.
                    // Mirror that here so any future NONPROD items don't drift the count
                    // above Agile's baseline. (Current snapshot has 0 NONPROD items in
                    // the SSS scope; logged for visibility.)
                    if ("NONPROD".equalsIgnoreCase(materialGroup)) {
                        skippedNonprod++;
                        continue;
                    }

                    // PT-65 follow-up: Agile Advanced Search for "Designation Needed"
                    // adds "Manufacturer/MPN Count = 1" — only items with a single MPN
                    // get flagged for designation, since multi-MPN items already have
                    // an implicit choice. Shruthi confirmed (May 27) the Agile UI shows
                    // 299 with this filter; without it the toolkit was reporting ~319.
                    // The mpn_count column is TEXT67 (stored), which trial-matched
                    // Agile's count within noise — TEXT67 is what Advanced Search reads.
                    if ("Designation Needed".equals(src) && (mpnCount == null || mpnCount != 1)) {
                        skippedNeededMpnNot1++;
                        continue;
                    }

                    if (!seenItem.add(itemId)) {
                        continue;
                    }
                    SingleSoleSourceRow r = new SingleSoleSourceRow();
                    r.itemId = itemId;
                    r.number = rs.getString("number_");
                    String desc = rs.getString("description");
                    r.description = desc == null ? null : desc.replaceAll("[\\r\\n\\t]+", " ");
                    String pl = rs.getString("product_lines");
                    String[] plIds = parseCsvIds(pl);
                    if (plIds != null) {
                        rawProductLines.put(rowKey, plIds);
                        for (String s : plIds) {
                            try { productLineIds.add(Long.parseLong(s)); } catch (NumberFormatException ignore) {}
                        }
                    }
                    r.lifecyclePhase = rs.getString("lifecycle_phase");
                    r.rev = rs.getString("rev");
                    r.partType = rs.getString("part_type");
                    r.mpnCount = mpnCount;
                    r.singleSoleSource = src;
                    r.materialGroup = materialGroup;
                    java.sql.Date cd = rs.getDate("create_date");
                    r.createDate = cd == null ? null : new Date(cd.getTime());
                    java.sql.Date rd = rs.getDate("rev_release_date");
                    r.revReleaseDate = rd == null ? null : new Date(rd.getTime());
                    r.mfrName = rs.getString("mfr_name");
                    r.mfrPartNumber = rs.getString("mfr_part_number");
                    r.preferredStatus = rs.getString("preferred_status");
                    rows.add(r);
                    rowKey++;
                }
                logger.info("[SS-REPORT] PT-65 NONPROD filter skipped " + skippedNonprod + " rows");
                logger.info("[SS-REPORT] PT-65 Designation-Needed MPN!=1 filter skipped " + skippedNeededMpnNot1 + " rows");
            }
        }
        long tQuery = System.currentTimeMillis();
        logger.info("[SS-REPORT] main query returned " + rows.size() + " rows in " + (tQuery - t0) + " ms");

        // Resolve product line ENTRYIDs in one shot.
        java.util.Map<Long, String> plMap = resolveListEntries(productLineIds);
        for (int i = 0; i < rows.size(); i++) {
            String[] ids = rawProductLines.get((long) i);
            if (ids == null) continue;
            java.util.TreeSet<String> values = new java.util.TreeSet<>();
            for (String s : ids) {
                try {
                    String v = plMap.get(Long.parseLong(s));
                    if (v != null) values.add(v);
                } catch (NumberFormatException ignore) {}
            }
            rows.get(i).productLine = values.isEmpty() ? null : String.join("; ", values);
        }
        logger.info("[SS-REPORT] total fetch+resolve: " + (System.currentTimeMillis() - t0) + " ms");
        return rows;
    }

    /** Bulk-resolve a set of LISTENTRY ENTRYIDs to their (MIN) ENTRYVALUE strings. */
    private java.util.Map<Long, String> resolveListEntries(java.util.Set<Long> ids) throws Exception {
        java.util.HashMap<Long, String> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        // Oracle IN-list cap is 1000; chunk if needed.
        java.util.List<Long> all = new java.util.ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += 1000) {
            int end = Math.min(start + 1000, all.size());
            java.util.List<Long> chunk = all.subList(start, end);
            StringBuilder sb = new StringBuilder("SELECT ENTRYID, MIN(ENTRYVALUE) FROM AGILE.LISTENTRY WHERE LANGID=0 AND ENTRYID IN (");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('?');
            }
            sb.append(") GROUP BY ENTRYID");
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sb.toString())) {
                for (int i = 0; i < chunk.size(); i++) ps.setLong(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    private static String[] parseCsvIds(String csv) {
        if (csv == null) return null;
        String trimmed = csv.trim();
        if (trimmed.isEmpty()) return null;
        // strip leading/trailing commas
        if (trimmed.startsWith(",")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith(","))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return null;
        return trimmed.split(",");
    }

    /**
     * End-to-end run: SQL → xlsx → optional SharePoint upload + email.
     * Always appends a row to the run-history JSON, even on failure.
     */
    public SingleSoleSourceRunResult runReport(String trigger, String userId,
                                               boolean uploadSharePoint, boolean sendEmail) {
        long t0 = System.currentTimeMillis();
        SingleSoleSourceRunResult res = new SingleSoleSourceRunResult();
        res.runId = Instant.now().toString();
        res.trigger = trigger;
        res.userId = userId;
        File out = null;
        try {
            List<SingleSoleSourceRow> rows = fetch();

            File dir = new File(outputDir);
            if (!dir.exists()) dir.mkdirs();
            String fname = "Single-Sole Source Report ("
                    + new SimpleDateFormat("MM-dd-yyyy").format(new Date()) + ").xlsx";
            out = new File(dir, fname);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                // counts shape (post ECN-128313 alignment): [needed, provided, single, sole]
                int[] counts = excelService.write(rows, fos);
                res.designationNeededCount   = counts[0];
                res.designationProvidedCount = counts[1];
                res.singleSourceCount        = counts[2];
                res.soleSourceCount          = counts[3];
            }
            res.xlsxPath = out.getAbsolutePath();
            res.xlsxSizeBytes = out.length();

            if (uploadSharePoint && sharepointUploader != null) {
                try {
                    String url = sharepointUploader.upload(out);
                    res.sharepointUploaded = url != null;
                    res.sharepointUrl = url;
                } catch (Exception e) {
                    res.sharepointUploaded = false;
                    res.sharepointError = e.getMessage();
                    logger.warning("[SS-REPORT] sharepoint upload failed: " + e.getMessage());
                }
            }
            if (sendEmail && emailService != null) {
                try {
                    emailService.send(out, res);
                    res.emailSent = true;
                } catch (Exception e) {
                    res.emailSent = false;
                    res.emailError = e.getMessage();
                    logger.warning("[SS-REPORT] email failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            res.error = e.getMessage();
            logger.severe("[SS-REPORT] run failed: " + e.getMessage());
        } finally {
            res.durationMs = System.currentTimeMillis() - t0;
            try { runHistory.append(res); } catch (Exception ignore) {}
        }
        return res;
    }
}
