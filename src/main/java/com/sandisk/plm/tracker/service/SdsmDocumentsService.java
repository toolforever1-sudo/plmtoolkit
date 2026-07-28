package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SdsmAttachment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.sandisk.plm.tracker.service.SdsmConstants.*;

/**
 * SDSM "Documents" pass — replaces the {@code FindAttachmentsForSDSM_DocumentStyle}
 * Agile saved search and the per-item revision/attachment walk in
 * {@code GetAndSummarizeAttachments.getContent(...)}.
 *
 * <p>Logic mirror of the Java SDK version, all in one SQL pass + in-memory resolve:
 * <ol>
 *   <li>Find every Document (CLASS=9000) whose {@code PAGE_THREE.LIST36} (Document Style)
 *       resolves to one of the SDSM-relevant styles.</li>
 *   <li>Pin each item to the latest <b>Implemented DCO</b> from REV+CHANGE.</li>
 *   <li>Pull all attachments tied to that item+change via {@code ATTACHMENT_FULL_MAP}.</li>
 *   <li>Filter to PDFs only; skip filenames containing "FAI"; skip zero-byte files.</li>
 *   <li>Resolve Product Group / Product / Spec from {@code AGILE_FLEX} TEXT CSVs.</li>
 *   <li>Drop OBS / OBS-SKU referenced products (read-only — no Agile write-back, see doc §6.5).</li>
 * </ol>
 *
 * <p>One {@link SdsmAttachment} record per surviving (item × spec × file) combination
 * is produced — but here we keep ONE record per file with the spec list, and let the
 * UI/exporter explode if needed (matches what the factory MES wants — a part has docs,
 * each doc has applicable specs).
 */
@Service
public class SdsmDocumentsService {

    private static final Logger LOG = Logger.getLogger(SdsmDocumentsService.class.getName());

    @Autowired private DataSource dataSource;
    @Autowired private SdsmLookups lookups;

    /**
     * Run the documents pass and return one {@link SdsmAttachment} per surviving
     * file. Limit and itemNumberFilter are optional — when both are null we run
     * the full population (~5K rows; SDK version takes ~2-3 hours, this is seconds).
     *
     * @param itemNumberFilter optional ITEM_NUMBER prefix or exact match — when provided,
     *                         restricts the pass to that single item (for the operator-view UI)
     * @param limit            max rows; 0 = unlimited
     */
    public List<SdsmAttachment> run(String itemNumberFilter, int limit) {
        long t0 = System.currentTimeMillis();
        List<SdsmAttachment> out = new ArrayList<>(limit > 0 ? limit : 4096);

        try {
            // Pre-warm the LISTENTRY / NODETABLE / lifecycle maps so the per-row resolvers
            // hit the hot path. ensureLoaded is idempotent — second call costs a volatile read.
            lookups.warmUp();

            // ----- Stage 1: items + their latest implemented DCO -----
            List<DocRow> docRows = fetchDocsAndLatestDco(itemNumberFilter, limit);
            LOG.info("[SDSM-DOC] Fetched " + docRows.size() + " documents with latest DCO");

            if (docRows.isEmpty()) return out;

            // ----- Stage 2: AGILE_FLEX (Product Group / Product / Spec) for those items -----
            Map<Long, FlexBundle> flexByItem = fetchFlexBundle(docRows);

            // ----- Stage 3: ATTACHMENT_FULL_MAP for (item × change) pairs -----
            Map<String, List<AttRow>> attsByKey = fetchAttachments(docRows);

            // ----- Stage 4: Resolve & filter -----
            for (DocRow d : docRows) {
                String docStyle = lookups.resolveListEntry(d.docStyleEntryId);
                if (docStyle == null || !DOC_STYLE_WHITELIST.contains(docStyle)) continue;

                FlexBundle flex = flexByItem.getOrDefault(d.itemId, FlexBundle.EMPTY);
                List<String> specs = lookups.resolveCsvListEntries(flex.specCsv);
                if (specs.isEmpty()) continue;  // matches Java's "No spec code define yet" guard

                String productGroup = String.join(", ", lookups.resolveCsvListEntries(flex.productGroupCsv));
                String product = resolveProductCsvSkippingObs(flex.productCsv);
                if (product.isEmpty()) continue;  // all referenced products were OBS

                String key = d.itemId + ":" + d.changeId;
                List<AttRow> atts = attsByKey.get(key);
                if (atts == null) continue;

                String itemTypeName = lookups.resolveSubclassName(d.subclassId);
                String lifecycle = lookups.resolveLifecycle(d.releaseType);

                for (AttRow a : atts) {
                    if (!"pdf".equalsIgnoreCase(a.fileType)) continue;
                    if (a.fileName != null && a.fileName.contains("FAI")) continue;
                    if (a.fileSize <= 0) continue;

                    SdsmAttachment rec = new SdsmAttachment();
                    rec.source            = "document";
                    rec.parentNumber      = d.itemNumber;
                    rec.parentDescription = d.description;
                    rec.documentStyle     = docStyle;
                    rec.itemType          = itemTypeName;
                    rec.lifecyclePhase    = lifecycle;
                    rec.rev               = d.revNumber;
                    rec.changeNumber      = d.changeNumber;
                    rec.productGroup      = productGroup;
                    rec.product           = product;
                    rec.specs             = specs;
                    rec.attachId          = a.attachId;
                    rec.fileId            = a.fileId;
                    rec.fileName          = a.fileName;
                    rec.fileType          = a.fileType;
                    rec.fileSize          = a.fileSize;
                    rec.vaultPath         = a.vaultPath;
                    out.add(rec);
                }
            }

            LOG.info("[SDSM-DOC] Built " + out.size() + " attachment records in " +
                    (System.currentTimeMillis() - t0) + " ms");
            return out;

        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[SDSM-DOC] Query failed", e);
            throw new RuntimeException("SDSM Documents pass failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Stage 1 — items + latest implemented DCO (per-item latest, all in one query)
    // -----------------------------------------------------------------------

    private List<DocRow> fetchDocsAndLatestDco(String itemNumberFilter, int limit) throws SQLException {
        // Per-item latest implemented DCO via ROW_NUMBER() — Oracle 12c+ has FETCH FIRST
        // but ROW_NUMBER() generalizes cleanly to N most-recent if we ever want history.
        StringBuilder sb = new StringBuilder()
            .append("SELECT * FROM (")
            .append("  SELECT i.ID itemId, i.ITEM_NUMBER itemNumber, i.DESCRIPTION description, ")
            .append("         i.SUBCLASS subclassId, p3.LIST36 docStyleEntryId, ")
            .append("         r.REV_NUMBER revNumber, r.RELEASE_TYPE releaseType, r.RELEASE_DATE releaseDate, ")
            .append("         c.ID changeId, c.CHANGE_NUMBER changeNumber, ")
            .append("         ROW_NUMBER() OVER (PARTITION BY i.ID ORDER BY r.RELEASE_DATE DESC) rn ")
            .append("    FROM agile.ITEM i ")
            .append("    JOIN agile.PAGE_THREE p3 ON p3.ID = i.ID AND p3.CLASS = i.CLASS ")
            .append("    JOIN agile.REV r ON r.ITEM = i.ID AND r.SITE = 0 ")
            .append("    JOIN agile.CHANGE c ON c.ID = r.CHANGE ")
            .append("   WHERE i.CLASS = ").append(CLASS_DOCUMENTS).append(' ')
            .append("     AND NVL(i.DELETE_FLAG, 0) != 1 ")
            .append("     AND p3.LIST36 IS NOT NULL ")
            // Filter by string label, not hardcoded NODETABLE IDs — different change
            // subclasses use different "Implemented" status node IDs (DCO=251733425,
            // ECO=23131, etc.). The label is stable across the family.
            .append("     AND c.STATUS   IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'Implemented') ")
            .append("     AND c.SUBCLASS IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'DCO') ");
        if (itemNumberFilter != null && !itemNumberFilter.trim().isEmpty()) {
            sb.append("     AND i.ITEM_NUMBER = ? ");
        }
        sb.append(") WHERE rn = 1");
        if (limit > 0) sb.append(" AND ROWNUM <= ").append(limit);

        List<DocRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sb.toString())) {
            ps.setFetchSize(1000);
            if (itemNumberFilter != null && !itemNumberFilter.trim().isEmpty()) {
                ps.setString(1, itemNumberFilter.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DocRow d = new DocRow();
                    d.itemId           = rs.getLong("itemId");
                    d.itemNumber       = rs.getString("itemNumber");
                    d.description      = rs.getString("description");
                    d.subclassId       = rs.getLong("subclassId");
                    d.docStyleEntryId  = rs.getLong("docStyleEntryId");
                    d.revNumber        = rs.getString("revNumber");
                    d.releaseType      = rs.getLong("releaseType");
                    d.changeId         = rs.getLong("changeId");
                    d.changeNumber     = rs.getString("changeNumber");
                    rows.add(d);
                }
            }
        }
        return rows;
    }

    // -----------------------------------------------------------------------
    //  Stage 2 — AGILE_FLEX bundle (Product Group / Product / Spec) per item
    // -----------------------------------------------------------------------

    private Map<Long, FlexBundle> fetchFlexBundle(List<DocRow> docRows) throws SQLException {
        Map<Long, FlexBundle> out = new HashMap<>();
        if (docRows.isEmpty()) return out;

        // Build the IN list. The SDK version walks one item at a time which is what
        // makes it slow; here we bulk-pull.
        List<Long> itemIds = new ArrayList<>(docRows.size());
        for (DocRow d : docRows) itemIds.add(d.itemId);

        String sql =
            "SELECT ID, ATTID, TEXT FROM agile.AGILE_FLEX " +
            " WHERE CLASS = " + CLASS_DOCUMENTS +
            "   AND ATTID IN (" + ATTID_DOC_PRODUCT_GROUP + ", " +
                                  ATTID_DOC_PRODUCT       + ", " +
                                  ATTID_DOC_SPEC          + ") " +
            "   AND ID IN (" + inClause(itemIds) + ")";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(5_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("ID");
                    long attId = rs.getLong("ATTID");
                    String text = rs.getString("TEXT");
                    FlexBundle b = out.computeIfAbsent(id, k -> new FlexBundle());
                    if (attId == ATTID_DOC_PRODUCT_GROUP) b.productGroupCsv = text;
                    else if (attId == ATTID_DOC_PRODUCT)  b.productCsv      = text;
                    else if (attId == ATTID_DOC_SPEC)     b.specCsv         = text;
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    //  Stage 3 — attachments via ATTACHMENT_FULL_MAP
    // -----------------------------------------------------------------------

    private Map<String, List<AttRow>> fetchAttachments(List<DocRow> docRows) throws SQLException {
        Map<String, List<AttRow>> out = new HashMap<>();
        if (docRows.isEmpty()) return out;

        // (itemId, changeId) IN (...) — use a synthetic key for the join
        StringBuilder pairs = new StringBuilder();
        for (DocRow d : docRows) {
            if (pairs.length() > 0) pairs.append(',');
            pairs.append('(').append(d.itemId).append(',').append(d.changeId).append(')');
        }

        String sql =
            "SELECT afm.PARENT_ID itemId, afm.PARENT_ID2 changeId, " +
            "       afm.ATTACH_ID attachId, afm.FILEID fileId, " +
            "       afm.FILEFILENAME fileName, afm.FILEFILE_TYPE fileType, " +
            "       afm.FILEFILE_SIZE fileSize, fi.IFS_FILEPATH vaultPath " +
            "  FROM agile.ATTACHMENT_FULL_MAP afm " +
            "  LEFT JOIN agile.FILE_INFO fi ON fi.FILE_ID = afm.FILEID " +
            " WHERE (afm.PARENT_ID, afm.PARENT_ID2) IN (" + pairs + ")";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(5_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AttRow a = new AttRow();
                    long itemId = rs.getLong("itemId");
                    long changeId = rs.getLong("changeId");
                    a.attachId  = rs.getLong("attachId");
                    a.fileId    = rs.getLong("fileId");
                    a.fileName  = rs.getString("fileName");
                    a.fileType  = rs.getString("fileType");
                    a.fileSize  = rs.getLong("fileSize");
                    a.vaultPath = rs.getString("vaultPath");
                    out.computeIfAbsent(itemId + ":" + changeId, k -> new ArrayList<>()).add(a);
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    //  OBS-product filter — resolve referenced ITEM.IDs and exclude OBS lifecycles
    //  (no write-back to Agile per SDSM-DB-REPLACEMENT.md §6.5)
    // -----------------------------------------------------------------------

    private String resolveProductCsvSkippingObs(String csv) throws SQLException {
        List<Long> itemIds = lookups.parseCsvLongs(csv);
        if (itemIds.isEmpty()) return "";

        // LEFT JOIN to REV so items WITHOUT a current-effective change (e.g. the "ALL"
        // placeholder item commonly used as Page Three.Product on documents) are still
        // kept — the SDK version only drops products whose lifecycle resolves to an
        // explicit OBS/OBS-SKU value, not products whose lifecycle is null.
        String sql =
            "SELECT i.ITEM_NUMBER, n.DESCRIPTION lifecycle " +
            "  FROM agile.ITEM i " +
            "  LEFT JOIN agile.REV r ON r.ITEM = i.ID AND i.DEFAULT_CHANGE = r.CHANGE AND r.SITE = 0 " +
            "  LEFT JOIN agile.NODETABLE n ON n.ID = r.RELEASE_TYPE " +
            " WHERE i.ID IN (" + inClause(itemIds) + ")";

        List<String> kept = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String num = rs.getString(1);
                String lc  = rs.getString(2);
                if (lc != null && OBS_LIFECYCLES.contains(lc)) continue;
                kept.add(num);
            }
        }
        return String.join(", ", kept);
    }

    private static String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Inner row holders
    // -----------------------------------------------------------------------

    private static class DocRow {
        long itemId;
        String itemNumber, description;
        long subclassId;
        long docStyleEntryId;
        String revNumber;
        long releaseType;
        long changeId;
        String changeNumber;
    }

    private static class FlexBundle {
        static final FlexBundle EMPTY = new FlexBundle();
        String productGroupCsv;
        String productCsv;
        String specCsv;
    }

    private static class AttRow {
        long attachId, fileId, fileSize;
        String fileName, fileType, vaultPath;
    }
}
