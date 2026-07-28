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
 * SDSM "Parts" pass — replaces the {@code FindAttachmentsForSDSM_FromPartsDocumentStyle}
 * Agile saved search.
 *
 * <p>Differences from the Documents pass (see {@link SdsmDocumentsService}):
 * <ul>
 *   <li>Items class is {@link SdsmConstants#CLASS_ITEMS} (10000); SKU subclass is excluded.</li>
 *   <li>Document Style is on {@code PAGE_TWO.LIST72} (uniform across every Parts subclass).</li>
 *   <li>Latest implemented change is an <b>ECO</b> (subclass {@link SdsmConstants#SUBCLASS_ECO}),
 *       not DCO.</li>
 *   <li>Product Group / Product / Spec live as PAGE_TWO multilist columns
 *       (MULTILIST11 / MULTILIST09 / MULTILIST10), not AGILE_FLEX.</li>
 * </ul>
 */
@Service
public class SdsmPartsService {

    private static final Logger LOG = Logger.getLogger(SdsmPartsService.class.getName());

    @Autowired private DataSource dataSource;
    @Autowired private SdsmLookups lookups;

    public List<SdsmAttachment> run(String itemNumberFilter, int limit) {
        long t0 = System.currentTimeMillis();
        List<SdsmAttachment> out = new ArrayList<>(512);
        try {
            lookups.warmUp();
            List<PartRow> partRows = fetchPartsAndLatestEco(itemNumberFilter, limit);
            LOG.info("[SDSM-PART] Fetched " + partRows.size() + " parts with latest ECO");

            if (partRows.isEmpty()) return out;

            Map<String, List<AttRow>> attsByKey = fetchAttachments(partRows);

            for (PartRow p : partRows) {
                String docStyle = lookups.resolveListEntry(p.docStyleEntryId);
                if (docStyle == null || !DOC_STYLE_WHITELIST.contains(docStyle)) continue;

                List<String> specs = lookups.resolveCsvListEntries(p.specCsv);
                if (specs.isEmpty()) continue;

                String productGroup = String.join(", ", lookups.resolveCsvListEntries(p.productGroupCsv));
                String product = resolveProductCsvSkippingObs(p.productCsv);
                if (product.isEmpty()) continue;

                List<AttRow> atts = attsByKey.get(p.itemId + ":" + p.changeId);
                if (atts == null) continue;

                String itemTypeName = lookups.resolveSubclassName(p.subclassId);
                String lifecycle = lookups.resolveLifecycle(p.releaseType);

                for (AttRow a : atts) {
                    if (!"pdf".equalsIgnoreCase(a.fileType)) continue;
                    if (a.fileName != null && a.fileName.contains("FAI")) continue;
                    if (a.fileSize <= 0) continue;

                    SdsmAttachment rec = new SdsmAttachment();
                    rec.source            = "part";
                    rec.parentNumber      = p.itemNumber;
                    rec.parentDescription = p.description;
                    rec.documentStyle     = docStyle;
                    rec.itemType          = itemTypeName;
                    rec.lifecyclePhase    = lifecycle;
                    rec.rev               = p.revNumber;
                    rec.changeNumber      = p.changeNumber;
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

            LOG.info("[SDSM-PART] Built " + out.size() + " records in " +
                    (System.currentTimeMillis() - t0) + " ms");
            return out;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[SDSM-PART] Query failed", e);
            throw new RuntimeException("SDSM Parts pass failed: " + e.getMessage(), e);
        }
    }

    private List<PartRow> fetchPartsAndLatestEco(String itemNumberFilter, int limit) throws SQLException {
        StringBuilder sb = new StringBuilder()
            .append("SELECT * FROM (")
            .append("  SELECT i.ID itemId, i.ITEM_NUMBER itemNumber, i.DESCRIPTION description, ")
            .append("         i.SUBCLASS subclassId, ")
            .append("         p2.LIST72 docStyleEntryId, ")
            .append("         p2.MULTILIST11 productGroupCsv, ")
            .append("         p2.MULTILIST09 productCsv, ")
            .append("         p2.MULTILIST10 specCsv, ")
            .append("         r.REV_NUMBER revNumber, r.RELEASE_TYPE releaseType, r.RELEASE_DATE releaseDate, ")
            .append("         c.ID changeId, c.CHANGE_NUMBER changeNumber, ")
            .append("         ROW_NUMBER() OVER (PARTITION BY i.ID ORDER BY r.RELEASE_DATE DESC) rn ")
            .append("    FROM agile.ITEM i ")
            .append("    JOIN agile.PAGE_TWO p2 ON p2.ID = i.ID AND p2.CLASS = i.CLASS ")
            .append("    JOIN agile.REV r ON r.ITEM = i.ID AND r.SITE = 0 ")
            .append("    JOIN agile.CHANGE c ON c.ID = r.CHANGE ")
            .append("   WHERE i.CLASS = ").append(CLASS_ITEMS).append(' ')
            .append("     AND i.SUBCLASS != ").append(SUBCLASS_SKU).append(' ')
            .append("     AND NVL(i.DELETE_FLAG, 0) != 1 ")
            .append("     AND p2.LIST72 IS NOT NULL ")
            .append("     AND p2.MULTILIST11 IS NOT NULL ")
            // See SdsmDocumentsService for why we use the string label instead of a
            // hardcoded NODETABLE.ID. ECO's "Implemented" is 23131, NOT 251733425.
            .append("     AND c.STATUS   IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'Implemented') ")
            .append("     AND c.SUBCLASS IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'ECO') ");
        if (itemNumberFilter != null && !itemNumberFilter.trim().isEmpty()) {
            sb.append("     AND i.ITEM_NUMBER = ? ");
        }
        sb.append(") WHERE rn = 1");
        if (limit > 0) sb.append(" AND ROWNUM <= ").append(limit);

        List<PartRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sb.toString())) {
            ps.setFetchSize(1000);
            if (itemNumberFilter != null && !itemNumberFilter.trim().isEmpty()) {
                ps.setString(1, itemNumberFilter.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PartRow p = new PartRow();
                    p.itemId           = rs.getLong("itemId");
                    p.itemNumber       = rs.getString("itemNumber");
                    p.description      = rs.getString("description");
                    p.subclassId       = rs.getLong("subclassId");
                    p.docStyleEntryId  = rs.getLong("docStyleEntryId");
                    p.productGroupCsv  = rs.getString("productGroupCsv");
                    p.productCsv       = rs.getString("productCsv");
                    p.specCsv          = rs.getString("specCsv");
                    p.revNumber        = rs.getString("revNumber");
                    p.releaseType      = rs.getLong("releaseType");
                    p.changeId         = rs.getLong("changeId");
                    p.changeNumber     = rs.getString("changeNumber");
                    rows.add(p);
                }
            }
        }
        return rows;
    }

    private Map<String, List<AttRow>> fetchAttachments(List<PartRow> rows) throws SQLException {
        Map<String, List<AttRow>> out = new HashMap<>();
        if (rows.isEmpty()) return out;

        StringBuilder pairs = new StringBuilder();
        for (PartRow r : rows) {
            if (pairs.length() > 0) pairs.append(',');
            pairs.append('(').append(r.itemId).append(',').append(r.changeId).append(')');
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
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ps.setFetchSize(5_000);
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
        return out;
    }

    private String resolveProductCsvSkippingObs(String csv) throws SQLException {
        List<Long> itemIds = lookups.parseCsvLongs(csv);
        if (itemIds.isEmpty()) return "";

        // LEFT JOIN to REV — see SdsmDocumentsService for the rationale (placeholder
        // items like "ALL" have no current-effective change but should still be kept).
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

    private static class PartRow {
        long itemId;
        String itemNumber, description;
        long subclassId, docStyleEntryId, releaseType, changeId;
        String productGroupCsv, productCsv, specCsv;
        String revNumber, changeNumber;
    }

    private static class AttRow {
        long attachId, fileId, fileSize;
        String fileName, fileType, vaultPath;
    }
}
