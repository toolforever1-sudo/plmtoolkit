package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live "IMS Document Details" reference-panel backing service.
 *
 * <p>The response-page panel (ims-respond.html → {@code renderRefPanel}) and the
 * change-order drawer show the document's Title Block / More Info / Document Details
 * attributes, read <b>live</b> from the Agile schema so they match the Agile web client.
 *
 * <h3>Access path (why it is shaped this way)</h3>
 * Agile stores multi-value attributes as comma-wrapped id CSVs ({@code ,1234,5678,}).
 * The obvious way to resolve them — {@code WHERE INSTR(csv, ','||le.ENTRYID||',') > 0} —
 * puts the indexed column inside a function, so Oracle cannot use an index and instead
 * <b>full-scans</b> the lookup table once per attribute. With LISTENTRY at ~263K rows and
 * ITEM at ~1.04M, the previous single-statement version scanned ~3.1M rows on every page
 * load and took tens of seconds (observed on agqa 2026-07-09).
 *
 * <p>So this service does the opposite: <b>one indexed row-read</b> for the document
 * (ITEM by ITEM_NUMBER, PAGE_TWO / PAGE_THREE by PK, AGILE_FLEX by ID+ATTID), which
 * returns the raw ids and CSVs; then <b>three batched lookups</b> that resolve those ids
 * through their indexes ({@code LISTENTRY.ENTRYID IN (…)}, {@code ITEM.ID IN (…)},
 * {@code AGILEUSER.ID IN (…)}). Display strings are assembled in Java, preserving CSV
 * order. Four indexed statements instead of six full scans.
 *
 * <p><b>Column map</b> (verified live against {@code agile_qa} for {@code 03-32-WW-02-00074},
 * the {@code 28-01-SM-*} PM-checklist docs and {@code 33-04-SM-03-00057}, cross-checked
 * against their Agile Excel exports):
 * <ul>
 *   <li>Title Block: {@code ITEM.CATEGORY} &rarr; Document Category,
 *       {@code ITEM.PRODUCT_LINES} (CSV of LISTENTRY) &rarr; Product Line,
 *       {@code REV.RELEASE_DATE} (LATEST_FLAG=1) &rarr; Rev Release Date.</li>
 *   <li>More Info: {@code PAGE_TWO.TEXT11} &rarr; Old Document Number,
 *       {@code PAGE_TWO.CREATE_USER} &rarr; Create User,
 *       {@code PAGE_TWO.DATE01} &rarr; Create Date,
 *       {@code PAGE_TWO.MULTILIST01} (CSV of ITEM.IDs) &rarr; Referenced Documents.</li>
 *   <li>Document Details: {@code AGILE_FLEX(251753655)} &rarr; APDS,
 *       {@code PAGE_THREE.DATE31/DATE32} &rarr; Last/Next Review,
 *       {@code PAGE_THREE.LIST31} &rarr; Site Location,
 *       {@code PAGE_THREE.LIST32} (cascade) &rarr; Function / Sub Function,
 *       {@code AGILE_FLEX(251733512)} &rarr; Document Owner(s),
 *       {@code PAGE_THREE.MULTILIST32} (CSV) &rarr; Subcontractors,
 *       {@code PAGE_THREE.LIST36} &rarr; Document Style,
 *       {@code PAGE_THREE.LIST38} &rarr; Document Classification,
 *       {@code PAGE_THREE.LIST37} &rarr; VAR Type,
 *       {@code PAGE_THREE.LIST34} &rarr; Support document category/type,
 *       {@code PAGE_THREE.TEXT32} &rarr; Support document Number,
 *       {@code PAGE_THREE.LIST39} &rarr; PM Checklist,
 *       {@code PAGE_THREE.LIST35} &rarr; Applies to all Part Numbers in Model list,
 *       {@code PAGE_THREE.TEXT33} &rarr; Part Number,
 *       {@code AGILE_FLEX(251746287/251746291/251747721)} &rarr; Product Group / Spec / Product.</li>
 * </ul>
 *
 * <p>Timestamps are converted UTC&rarr;America/Los_Angeles in-SQL (cheap — one row) so
 * they match the Agile web client, e.g. {@code 10/20/2022 06:52:54 PM PDT}.
 *
 * <p><b>Fail-soft:</b> any SQLException or missing row returns an empty map — the panel
 * keeps rendering "&mdash;" for the affected fields. A live-lookup failure must never
 * break the response page.
 */
@Service
public class ImsDocDetailsService {

    private static final Logger LOG = Logger.getLogger(ImsDocDetailsService.class.getName());

    @Autowired private DataSource dataSource;

    /** Agile date format matching the web client, e.g. {@code 10/20/2022 06:52:54 PM PDT}. */
    private static final String TZ_FMT =
        "'MM/DD/YYYY hh:MI:SS AM TZD'";
    private static String pdt(String col) {
        return "TO_CHAR(FROM_TZ(CAST(" + col + " AS TIMESTAMP),'UTC') AT TIME ZONE 'America/Los_Angeles'," + TZ_FMT + ")";
    }

    /** Step 1 — one indexed row-read. Returns raw ids + CSVs, no lookup-table scans. */
    private static final String SQL_MAIN =
        "SELECT i.ID itemId, i.CATEGORY categoryId, i.PRODUCT_LINES productLinesCsv,\n" +
        "       p2.TEXT11 oldDocumentNumber, p2.CREATE_USER createUserId,\n" +
        "       p2.MULTILIST01 referencedDocsCsv,\n" +
        "       " + pdt("p2.DATE01") + " createDate,\n" +
        "       " + pdt("p3.DATE31") + " lastReviewedDate,\n" +
        "       " + pdt("p3.DATE32") + " nextReviewDate,\n" +
        "       p3.LIST31 siteLocationId, p3.LIST32 functionSubFunctionId,\n" +
        "       p3.LIST34 supportDocCategoryId, p3.LIST35 appliesAllPartsId,\n" +
        "       p3.LIST36 documentStyleId, p3.LIST37 varTypeId,\n" +
        "       p3.LIST38 documentClassificationId, p3.LIST39 pmChecklistId,\n" +
        "       p3.TEXT32 supportDocNumber, p3.TEXT33 partNumber,\n" +
        "       p3.MULTILIST32 subcontractorsCsv,\n" +
        "       (SELECT " + pdt("r.RELEASE_DATE") + " FROM agile.REV r\n" +
        "         WHERE r.ITEM = i.ID AND r.LATEST_FLAG = 1 AND r.SITE = 0 AND ROWNUM = 1) revReleaseDate,\n" +
        "       (SELECT af.TEXT FROM agile.AGILE_FLEX af WHERE af.ID = i.ID AND af.ATTID = 251753655) apdsCsv,\n" +
        "       (SELECT af.TEXT FROM agile.AGILE_FLEX af WHERE af.ID = i.ID AND af.ATTID = 251733512) ownersCsv,\n" +
        "       (SELECT af.TEXT FROM agile.AGILE_FLEX af WHERE af.ID = i.ID AND af.ATTID = 251746287) productGroupCsv,\n" +
        "       (SELECT af.TEXT FROM agile.AGILE_FLEX af WHERE af.ID = i.ID AND af.ATTID = 251746291) specCsv,\n" +
        "       (SELECT af.TEXT FROM agile.AGILE_FLEX af WHERE af.ID = i.ID AND af.ATTID = 251747721) productCsv\n" +
        "FROM agile.ITEM i\n" +
        "LEFT JOIN agile.PAGE_TWO   p2 ON p2.ID = i.ID\n" +
        "LEFT JOIN agile.PAGE_THREE p3 ON p3.ID = i.ID\n" +
        "WHERE i.ITEM_NUMBER = ? AND NVL(i.DELETE_FLAG,0) <> 1";

    /** Panel field &rarr; JSON key. Only non-blank values are emitted. */
    public Map<String, String> fetch(String itemNumber) {
        Map<String, String> out = new LinkedHashMap<>();
        if (itemNumber == null || itemNumber.trim().isEmpty()) return out;
        long t0 = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            Row row = readMain(c, itemNumber.trim());
            if (row == null) return out;

            // ---- gather every id we must resolve, then batch-resolve by index ----
            Set<Long> listIds = new LinkedHashSet<>();
            addId(listIds, row.categoryId);
            addId(listIds, row.siteLocationId);
            addId(listIds, row.functionSubFunctionId);
            addId(listIds, row.supportDocCategoryId);
            addId(listIds, row.appliesAllPartsId);
            addId(listIds, row.documentStyleId);
            addId(listIds, row.varTypeId);
            addId(listIds, row.documentClassificationId);
            addId(listIds, row.pmChecklistId);
            listIds.addAll(csvIds(row.productLinesCsv));
            listIds.addAll(csvIds(row.subcontractorsCsv));
            listIds.addAll(csvIds(row.apdsCsv));
            listIds.addAll(csvIds(row.productGroupCsv));
            listIds.addAll(csvIds(row.specCsv));

            Set<Long> itemIds = new LinkedHashSet<>();
            itemIds.addAll(csvIds(row.productCsv));
            itemIds.addAll(csvIds(row.referencedDocsCsv));

            Set<Long> userIds = new LinkedHashSet<>();
            addId(userIds, row.createUserId);
            userIds.addAll(csvIds(row.ownersCsv));

            Map<Long, String> lists  = resolveListEntries(c, listIds);
            Map<Long, String> cascade = resolveCascadeParents(c, listIds);
            Map<Long, String> items  = resolveById(c, itemIds,
                    "SELECT ID, ITEM_NUMBER v FROM agile.ITEM WHERE ID IN ");
            Map<Long, String> users  = resolveById(c, userIds,
                    "SELECT ID, LAST_NAME||', '||FIRST_NAME||' ('||LOGINID||')' v FROM agile.AGILEUSER WHERE ID IN ");

            // ---- assemble (CSV order preserved) ----
            put(out, "documentCategory",       lists.get(row.categoryId));
            put(out, "productLine",            joinCsv(row.productLinesCsv, lists));
            put(out, "revReleaseDate",         row.revReleaseDate);
            put(out, "oldDocumentNumber",      row.oldDocumentNumber);
            put(out, "createUser",             users.get(row.createUserId));
            put(out, "createDate",             row.createDate);
            put(out, "referencedDocuments",    joinCsv(row.referencedDocsCsv, items));
            put(out, "apdsApplicability",      joinCsv(row.apdsCsv, lists));
            put(out, "lastReviewedDate",       row.lastReviewedDate);
            put(out, "nextReviewDate",         row.nextReviewDate);
            put(out, "siteLocation",           lists.get(row.siteLocationId));
            put(out, "functionSubFunction",    cascadeLabel(row.functionSubFunctionId, lists, cascade));
            put(out, "documentOwners",         joinCsv(row.ownersCsv, users));
            put(out, "subcontractorsDisplay",  joinCsv(row.subcontractorsCsv, lists));
            put(out, "documentStyle",          lists.get(row.documentStyleId));
            put(out, "documentClassification", lists.get(row.documentClassificationId));
            put(out, "varType",                lists.get(row.varTypeId));
            put(out, "supportDocCategory",     lists.get(row.supportDocCategoryId));
            put(out, "supportDocNumber",       row.supportDocNumber);
            put(out, "pmChecklist",            lists.get(row.pmChecklistId));
            put(out, "appliesAllParts",        lists.get(row.appliesAllPartsId));
            put(out, "partNumber",             row.partNumber);
            put(out, "productGroup",           joinCsv(row.productGroupCsv, lists));
            put(out, "spec",                   joinCsv(row.specCsv, lists));
            put(out, "product",                joinCsv(row.productCsv, items));
        } catch (Exception e) {
            // Fail-soft: the panel keeps its "—" placeholders; never break the page.
            LOG.log(Level.WARNING, "IMS doc-details live lookup failed for " + itemNumber, e);
            return new LinkedHashMap<>();
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("[IMS-DOC-DETAILS] " + itemNumber + " resolved in "
                    + (System.currentTimeMillis() - t0) + "ms");
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Step 1 — indexed row-read
    // ------------------------------------------------------------------

    private static final class Row {
        Long categoryId, createUserId, siteLocationId, functionSubFunctionId,
             supportDocCategoryId, appliesAllPartsId, documentStyleId, varTypeId,
             documentClassificationId, pmChecklistId;
        String productLinesCsv, referencedDocsCsv, subcontractorsCsv,
               apdsCsv, ownersCsv, productGroupCsv, specCsv, productCsv;
        String oldDocumentNumber, supportDocNumber, partNumber,
               createDate, lastReviewedDate, nextReviewDate, revReleaseDate;
    }

    private Row readMain(Connection c, String itemNumber) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(SQL_MAIN)) {
            ps.setQueryTimeout(30);
            ps.setString(1, itemNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Row r = new Row();
                r.categoryId                = getLong(rs, "categoryId");
                r.createUserId              = getLong(rs, "createUserId");
                r.siteLocationId            = getLong(rs, "siteLocationId");
                r.functionSubFunctionId     = getLong(rs, "functionSubFunctionId");
                r.supportDocCategoryId      = getLong(rs, "supportDocCategoryId");
                r.appliesAllPartsId         = getLong(rs, "appliesAllPartsId");
                r.documentStyleId           = getLong(rs, "documentStyleId");
                r.varTypeId                 = getLong(rs, "varTypeId");
                r.documentClassificationId  = getLong(rs, "documentClassificationId");
                r.pmChecklistId             = getLong(rs, "pmChecklistId");
                r.productLinesCsv    = rs.getString("productLinesCsv");
                r.referencedDocsCsv  = rs.getString("referencedDocsCsv");
                r.subcontractorsCsv  = rs.getString("subcontractorsCsv");
                r.apdsCsv            = rs.getString("apdsCsv");
                r.ownersCsv          = rs.getString("ownersCsv");
                r.productGroupCsv    = rs.getString("productGroupCsv");
                r.specCsv            = rs.getString("specCsv");
                r.productCsv         = rs.getString("productCsv");
                r.oldDocumentNumber  = rs.getString("oldDocumentNumber");
                r.supportDocNumber   = rs.getString("supportDocNumber");
                r.partNumber         = rs.getString("partNumber");
                r.createDate         = rs.getString("createDate");
                r.lastReviewedDate   = rs.getString("lastReviewedDate");
                r.nextReviewDate     = rs.getString("nextReviewDate");
                r.revReleaseDate     = rs.getString("revReleaseDate");
                return r;
            }
        }
    }

    // ------------------------------------------------------------------
    //  Step 2 — batched, index-driven resolvers
    // ------------------------------------------------------------------

    /** {@code LISTENTRY.ENTRYID IN (…)} — the label for every single- and multi-list id. */
    private Map<Long, String> resolveListEntries(Connection c, Set<Long> ids) throws Exception {
        return resolveById(c, ids,
                "SELECT ENTRYID ID, ENTRYVALUE v FROM agile.LISTENTRY WHERE LANGID = 0 AND ENTRYID IN ");
    }

    /** Parent label for cascading lists (Function / Sub Function). Same index. */
    private Map<Long, String> resolveCascadeParents(Connection c, Set<Long> ids) throws Exception {
        return resolveById(c, ids,
                "SELECT le.ENTRYID ID, (SELECT p.ENTRYVALUE FROM agile.LISTENTRY p" +
                " WHERE p.ENTRYID = le.PARENT_ENTRY AND p.LANGID = 0 AND ROWNUM = 1) v" +
                " FROM agile.LISTENTRY le WHERE le.LANGID = 0 AND le.PARENT_ENTRY <> 0 AND le.ENTRYID IN ");
    }

    /**
     * Run {@code sqlPrefix + (?,?,…)} for the given ids and collect {@code ID -> v}.
     * Chunked at 900 to stay under Oracle's 1000-element IN-list limit.
     */
    private Map<Long, String> resolveById(Connection c, Set<Long> ids, String sqlPrefix) throws Exception {
        Map<Long, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<Long> all = new ArrayList<>(ids);
        final int CHUNK = 900;
        for (int i = 0; i < all.size(); i += CHUNK) {
            List<Long> batch = all.subList(i, Math.min(all.size(), i + CHUNK));
            StringBuilder in = new StringBuilder("(");
            for (int j = 0; j < batch.size(); j++) in.append(j == 0 ? "?" : ",?");
            in.append(')');
            try (PreparedStatement ps = c.prepareStatement(sqlPrefix + in)) {
                ps.setQueryTimeout(30);
                for (int j = 0; j < batch.size(); j++) ps.setLong(j + 1, batch.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String v = rs.getString("v");
                        if (v != null && !v.trim().isEmpty()) out.put(rs.getLong("ID"), v.trim());
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Split Agile's comma-wrapped id CSV ({@code ,12,34,}) into ids, in order. */
    private static List<Long> csvIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try { out.add(Long.valueOf(t)); } catch (NumberFormatException ignored) { /* skip junk */ }
        }
        return out;
    }

    /** Resolve a CSV of ids to a ';'-joined label list, preserving CSV order. */
    private static String joinCsv(String csv, Map<Long, String> labels) {
        StringBuilder sb = new StringBuilder();
        for (Long id : csvIds(csv)) {
            String v = labels.get(id);
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(v);
        }
        return sb.toString();
    }

    /** "Parent / Child" for a cascading list value; bare child when it has no parent. */
    private static String cascadeLabel(Long id, Map<Long, String> lists, Map<Long, String> parents) {
        if (id == null) return null;
        String child = lists.get(id);
        if (child == null) return null;
        String parent = parents.get(id);
        return (parent == null || parent.isEmpty()) ? child : parent + " / " + child;
    }

    private static void addId(Set<Long> into, Long id) { if (id != null) into.add(id); }

    private static Long getLong(ResultSet rs, String col) throws Exception {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static void put(Map<String, String> out, String key, String val) {
        if (val != null && !val.trim().isEmpty()) out.put(key, val.trim());
    }
}
