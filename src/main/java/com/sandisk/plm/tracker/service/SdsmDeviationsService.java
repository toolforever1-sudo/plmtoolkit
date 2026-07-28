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
 * SDSM "Deviations" pass — replaces the {@code Deviations} Agile saved search.
 *
 * <p>Returns active Quality Notice deviations whose effective window covers today,
 * with their Quality Notice attachments (filtered by ATTACHMENTTYPE = 3566238).
 *
 * <p>Schema notes (see doc §3 Deviations):
 * <ul>
 *   <li>Deviation Document Style is on {@code PAGE_THREE.LIST36} (NOT PAGE_TWO.LIST06).</li>
 *   <li>Product Group / Spec are AGILE_FLEX TEXT CSVs.</li>
 *   <li>Customers Impacted is {@code PAGE_TWO.MULTILIST02} — comma-anchored INSTR test.</li>
 *   <li>Attachments hang off the change directly (PARENT_ID = CHANGE.ID, PARENT_ID2 = 0).</li>
 * </ul>
 */
@Service
public class SdsmDeviationsService {

    private static final Logger LOG = Logger.getLogger(SdsmDeviationsService.class.getName());

    @Autowired private DataSource dataSource;
    @Autowired private SdsmLookups lookups;

    public List<SdsmAttachment> run(String changeNumberFilter) {
        long t0 = System.currentTimeMillis();
        List<SdsmAttachment> out = new ArrayList<>();
        try {
            lookups.warmUp();
            List<DevRow> devs = fetchActiveDeviations(changeNumberFilter);
            LOG.info("[SDSM-DEV] Fetched " + devs.size() + " active QN deviations");
            if (devs.isEmpty()) return out;

            Map<Long, FlexBundle> flexById = fetchFlexBundle(devs);
            Map<Long, List<AttRow>> attsById = fetchQualityNoticeAttachments(devs);

            for (DevRow d : devs) {
                String docStyle = lookups.resolveListEntry(d.docStyleEntryId);
                FlexBundle flex = flexById.getOrDefault(d.changeId, FlexBundle.EMPTY);

                List<String> specs = lookups.resolveCsvListEntries(flex.specCsv);
                String productGroup = String.join(", ", lookups.resolveCsvListEntries(flex.productGroupCsv));
                String customers = String.join(", ", lookups.resolveCsvListEntries(d.customersImpactedCsv));

                List<AttRow> atts = attsById.get(d.changeId);
                if (atts == null) continue;

                for (AttRow a : atts) {
                    if (!"pdf".equalsIgnoreCase(a.fileType)) continue;
                    if (a.fileSize <= 0) continue;

                    SdsmAttachment rec = new SdsmAttachment();
                    rec.source            = "deviation";
                    rec.parentNumber      = d.changeNumber;
                    rec.parentDescription = d.description;
                    rec.documentStyle     = docStyle != null ? docStyle : "Quality Notice";
                    rec.itemType          = "Deviation";
                    rec.lifecyclePhase    = "ACT";
                    rec.rev               = null;
                    rec.changeNumber      = d.changeNumber;
                    rec.productGroup      = productGroup;
                    rec.product           = "";  // Deviation product list is on PAGE_TWO.MULTILIST03 — not exposed yet
                    rec.specs             = specs;
                    rec.attachId          = a.attachId;
                    rec.fileId            = a.fileId;
                    rec.fileName          = a.fileName;
                    rec.fileType          = a.fileType;
                    rec.fileSize          = a.fileSize;
                    rec.vaultPath         = a.vaultPath;
                    rec.effectiveFrom     = d.effectiveFrom == null ? null : d.effectiveFrom.toString();
                    rec.effectiveTo       = d.effectiveTo == null ? null : d.effectiveTo.toString();
                    rec.customersImpacted = customers;
                    out.add(rec);
                }
            }

            LOG.info("[SDSM-DEV] Built " + out.size() + " records in " +
                    (System.currentTimeMillis() - t0) + " ms");
            return out;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[SDSM-DEV] Query failed", e);
            throw new RuntimeException("SDSM Deviations pass failed: " + e.getMessage(), e);
        }
    }

    private List<DevRow> fetchActiveDeviations(String changeNumberFilter) throws SQLException {
        StringBuilder sb = new StringBuilder()
            .append("SELECT c.ID changeId, c.CHANGE_NUMBER changeNumber, c.DESCRIPTION description, ")
            .append("       c.EFFECTIVE_FROM effectiveFrom, c.EFFECTIVE_TO effectiveTo, ")
            .append("       p3.LIST36 docStyleEntryId, p2.MULTILIST02 customersImpactedCsv ")
            .append("  FROM agile.CHANGE c ")
            .append("  LEFT JOIN agile.PAGE_THREE p3 ON p3.ID = c.ID ")
            .append("  LEFT JOIN agile.PAGE_TWO   p2 ON p2.ID = c.ID ")
            .append(" WHERE c.CLASS    = ").append(CLASS_CHANGES).append(' ')
            .append("   AND c.SUBCLASS = ").append(SUBCLASS_DEVIATION).append(' ')
            .append("   AND c.STATUS   = ").append(STATUS_QN_EXECUTED).append(' ')
            .append("   AND c.WORKFLOW_ID = ").append(WORKFLOW_QN).append(' ')
            .append("   AND c.EFFECTIVE_FROM <= SYSDATE ")
            .append("   AND c.EFFECTIVE_TO   >= SYSDATE ")
            .append("   AND p3.LIST36       IS NOT NULL ")
            .append("   AND p2.MULTILIST02  IS NOT NULL ");
        if (changeNumberFilter != null && !changeNumberFilter.trim().isEmpty()) {
            sb.append("   AND c.CHANGE_NUMBER = ? ");
        }

        List<DevRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sb.toString())) {
            if (changeNumberFilter != null && !changeNumberFilter.trim().isEmpty()) {
                ps.setString(1, changeNumberFilter.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DevRow d = new DevRow();
                    d.changeId             = rs.getLong("changeId");
                    d.changeNumber         = rs.getString("changeNumber");
                    d.description          = rs.getString("description");
                    d.effectiveFrom        = rs.getTimestamp("effectiveFrom");
                    d.effectiveTo          = rs.getTimestamp("effectiveTo");
                    d.docStyleEntryId      = rs.getLong("docStyleEntryId");
                    d.customersImpactedCsv = rs.getString("customersImpactedCsv");
                    rows.add(d);
                }
            }
        }
        return rows;
    }

    private Map<Long, FlexBundle> fetchFlexBundle(List<DevRow> devs) throws SQLException {
        Map<Long, FlexBundle> out = new HashMap<>();
        if (devs.isEmpty()) return out;
        List<Long> ids = new ArrayList<>(devs.size());
        for (DevRow d : devs) ids.add(d.changeId);

        String sql =
            "SELECT ID, ATTID, TEXT FROM agile.AGILE_FLEX " +
            " WHERE CLASS = " + CLASS_CHANGES +
            "   AND ATTID IN (" + ATTID_DEV_PRODUCT_GROUP + ", " + ATTID_DEV_SPEC + ") " +
            "   AND ID IN (" + inClause(ids) + ")";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("ID");
                long attId = rs.getLong("ATTID");
                String text = rs.getString("TEXT");
                FlexBundle b = out.computeIfAbsent(id, k -> new FlexBundle());
                if (attId == ATTID_DEV_PRODUCT_GROUP) b.productGroupCsv = text;
                else if (attId == ATTID_DEV_SPEC)     b.specCsv         = text;
            }
        }
        return out;
    }

    private Map<Long, List<AttRow>> fetchQualityNoticeAttachments(List<DevRow> devs) throws SQLException {
        Map<Long, List<AttRow>> out = new HashMap<>();
        if (devs.isEmpty()) return out;
        List<Long> ids = new ArrayList<>(devs.size());
        for (DevRow d : devs) ids.add(d.changeId);

        String sql =
            "SELECT afm.PARENT_ID changeId, afm.ATTACH_ID attachId, afm.FILEID fileId, " +
            "       afm.FILEFILENAME fileName, afm.FILEFILE_TYPE fileType, " +
            "       afm.FILEFILE_SIZE fileSize, fi.IFS_FILEPATH vaultPath " +
            "  FROM agile.ATTACHMENT_FULL_MAP afm " +
            "  LEFT JOIN agile.FILE_INFO fi ON fi.FILE_ID = afm.FILEID " +
            " WHERE afm.PARENT_ID    IN (" + inClause(ids) + ") " +
            "   AND afm.ATTACHMENTTYPE = " + LE_QUALITY_NOTICE;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                AttRow a = new AttRow();
                long changeId = rs.getLong("changeId");
                a.attachId  = rs.getLong("attachId");
                a.fileId    = rs.getLong("fileId");
                a.fileName  = rs.getString("fileName");
                a.fileType  = rs.getString("fileType");
                a.fileSize  = rs.getLong("fileSize");
                a.vaultPath = rs.getString("vaultPath");
                out.computeIfAbsent(changeId, k -> new ArrayList<>()).add(a);
            }
        }
        return out;
    }

    private static String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static class DevRow {
        long changeId;
        String changeNumber, description, customersImpactedCsv;
        Timestamp effectiveFrom, effectiveTo;
        long docStyleEntryId;
    }

    private static class FlexBundle {
        static final FlexBundle EMPTY = new FlexBundle();
        String productGroupCsv;
        String specCsv;
    }

    private static class AttRow {
        long attachId, fileId, fileSize;
        String fileName, fileType, vaultPath;
    }
}
