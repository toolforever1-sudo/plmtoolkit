package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live "IMS Document Details" reference-panel backing service.
 *
 * <p>The response-page panel (ims-respond.html → {@code renderRefPanel}) shows the
 * document's Title Block / More Info / Document Details attributes. Those values used
 * to render as a faint "&mdash;" because the token only carried a handful of fields.
 * This service reads the rest <b>live</b> from the Agile schema so the panel matches
 * what the operator sees in the Agile web client.
 *
 * <p>One self-contained SQL pass keyed by {@code ITEM_NUMBER}, resolving list entries,
 * users, cascades and multi-value CSV lists to their display labels in-database. It
 * runs against the primary {@code dataSource} — which points to <b>agqa</b> on the QA
 * deployment and <b>agprod</b> in production, so the panel always reflects the same
 * Agile instance the rest of the toolkit reads.
 *
 * <p><b>Column map</b> (verified live against {@code agile_qa} on 2026-07-09 for
 * document {@code 03-32-WW-02-00074}, cross-checked against its Agile Excel export):
 * <ul>
 *   <li>Title Block: {@code ITEM.CATEGORY} &rarr; Document Category,
 *       {@code ITEM.PRODUCT_LINES} (CSV of LISTENTRY) &rarr; Product Line,
 *       {@code REV.RELEASE_DATE} (LATEST_FLAG=1) &rarr; Rev Release Date.</li>
 *   <li>More Info: {@code PAGE_TWO.TEXT11} &rarr; Old Document Number,
 *       {@code PAGE_TWO.CREATE_USER} &rarr; Create User,
 *       {@code PAGE_TWO.DATE01} &rarr; Create Date.</li>
 *   <li>Document Details: {@code AGILE_FLEX(ATTID 251753655)} &rarr; APDS,
 *       {@code PAGE_THREE.DATE31/DATE32} &rarr; Last/Next Review,
 *       {@code PAGE_THREE.LIST31} &rarr; Site Location,
 *       {@code PAGE_THREE.LIST32} (cascade) &rarr; Function / Sub Function,
 *       {@code AGILE_FLEX(ATTID 251733512)} &rarr; Document Owner(s),
 *       {@code PAGE_THREE.MULTILIST32} (CSV) &rarr; Subcontractors,
 *       {@code PAGE_THREE.LIST36} &rarr; Document Style,
 *       {@code PAGE_THREE.LIST38} &rarr; Document Classification,
 *       {@code PAGE_THREE.LIST37} &rarr; VAR Type,
 *       {@code PAGE_THREE.LIST34} &rarr; Support document category/type,
 *       {@code PAGE_THREE.TEXT32} &rarr; Support document Number,
 *       {@code PAGE_THREE.LIST39} &rarr; PM Checklist,
 *       {@code PAGE_THREE.LIST35} &rarr; Applies to all Part Numbers in Model list,
 *       {@code PAGE_THREE.TEXT33} &rarr; Part Number,
 *       {@code PAGE_TWO.MULTILIST01} (CSV of ITEM.IDs) &rarr; Referenced Documents,
 *       {@code AGILE_FLEX(251746287/251746291/251747721)} &rarr; Product Group / Spec / Product.</li>
 * </ul>
 *
 * <p>The LIST34/35/37/39 and TEXT32/TEXT33 mappings were verified on 2026-07-09 against
 * documents {@code 28-01-SM-03-00089/00090}, {@code 28-01-SM-02-00250} and
 * {@code 03-32-WW-02-00074} and their Agile Excel exports. Every attribute shown in the
 * panel is now backed by a verified column.
 *
 * <p>Timestamps are converted UTC&rarr;America/Los_Angeles in-SQL so they match the
 * Agile web client's rendering (e.g. {@code 10/20/2022 06:52:54 PM PDT}).
 *
 * <p><b>Fail-soft:</b> any SQLException or missing row returns an empty map — the panel
 * simply keeps rendering "&mdash;" for the affected fields. A live-lookup failure must
 * never break the response page.
 */
@Service
public class ImsDocDetailsService {

    private static final Logger LOG = Logger.getLogger(ImsDocDetailsService.class.getName());

    @Autowired private DataSource dataSource;

    /** AGILE_FLEX ATTIDs (see {@link SdsmConstants}) resolved inline in the SQL. */
    private static final String SQL =
        "WITH it AS (\n" +
        "  SELECT i.ID, i.ITEM_NUMBER, i.SUBCLASS, i.CATEGORY, i.PRODUCT_LINES\n" +
        "    FROM agile.ITEM i WHERE i.ITEM_NUMBER = ? AND NVL(i.DELETE_FLAG,0) <> 1\n" +
        ")\n" +
        "SELECT\n" +
        "  (SELECT ce.ENTRYVALUE FROM agile.LISTENTRY ce\n" +
        "     WHERE ce.ENTRYID = it.CATEGORY AND ce.LANGID = 0 AND ROWNUM = 1) AS documentCategory,\n" +
        "  (SELECT LISTAGG(le.ENTRYVALUE, ';') WITHIN GROUP (ORDER BY INSTR(it.PRODUCT_LINES, ','||le.ENTRYID||','))\n" +
        "     FROM agile.LISTENTRY le WHERE le.LANGID = 0\n" +
        "      AND INSTR(','||it.PRODUCT_LINES||',', ','||le.ENTRYID||',') > 0) AS productLine,\n" +
        "  (SELECT TO_CHAR(FROM_TZ(CAST(r.RELEASE_DATE AS TIMESTAMP),'UTC') AT TIME ZONE 'America/Los_Angeles',\n" +
        "                  'MM/DD/YYYY hh:MI:SS AM TZD')\n" +
        "     FROM agile.REV r WHERE r.ITEM = it.ID AND r.LATEST_FLAG = 1 AND r.SITE = 0 AND ROWNUM = 1) AS revReleaseDate,\n" +
        "  p2.TEXT11 AS oldDocumentNumber,\n" +
        "  (SELECT u.LAST_NAME||', '||u.FIRST_NAME||' ('||u.LOGINID||')'\n" +
        "     FROM agile.AGILEUSER u WHERE u.ID = p2.CREATE_USER) AS createUser,\n" +
        "  TO_CHAR(FROM_TZ(CAST(p2.DATE01 AS TIMESTAMP),'UTC') AT TIME ZONE 'America/Los_Angeles',\n" +
        "          'MM/DD/YYYY hh:MI:SS AM TZD') AS createDate,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.LANGID = 0 AND ROWNUM = 1 AND le.ENTRYID =\n" +
        "     (SELECT TO_NUMBER(TRIM(BOTH ',' FROM af.TEXT)) FROM agile.AGILE_FLEX af\n" +
        "        WHERE af.ID = it.ID AND af.ATTID = 251753655)) AS apdsApplicability,\n" +
        "  TO_CHAR(FROM_TZ(CAST(p3.DATE31 AS TIMESTAMP),'UTC') AT TIME ZONE 'America/Los_Angeles',\n" +
        "          'MM/DD/YYYY hh:MI:SS AM TZD') AS lastReviewedDate,\n" +
        "  TO_CHAR(FROM_TZ(CAST(p3.DATE32 AS TIMESTAMP),'UTC') AT TIME ZONE 'America/Los_Angeles',\n" +
        "          'MM/DD/YYYY hh:MI:SS AM TZD') AS nextReviewDate,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST31 AND le.LANGID = 0 AND ROWNUM = 1) AS siteLocation,\n" +
        "  (SELECT NVL2(par.ENTRYVALUE, par.ENTRYVALUE||' / ', '')||le.ENTRYVALUE\n" +
        "     FROM agile.LISTENTRY le\n" +
        "     LEFT JOIN agile.LISTENTRY par ON par.ENTRYID = le.PARENT_ENTRY AND par.LANGID = 0 AND le.PARENT_ENTRY <> 0\n" +
        "    WHERE le.ENTRYID = p3.LIST32 AND le.LANGID = 0 AND ROWNUM = 1) AS functionSubFunction,\n" +
        "  (SELECT u.LAST_NAME||', '||u.FIRST_NAME||' ('||u.LOGINID||')'\n" +
        "     FROM agile.AGILEUSER u WHERE u.ID =\n" +
        "     (SELECT TO_NUMBER(TRIM(BOTH ',' FROM af.TEXT)) FROM agile.AGILE_FLEX af\n" +
        "        WHERE af.ID = it.ID AND af.ATTID = 251733512)) AS documentOwners,\n" +
        "  (SELECT LISTAGG(le.ENTRYVALUE, ';') WITHIN GROUP (ORDER BY INSTR(p3.MULTILIST32, ','||le.ENTRYID||','))\n" +
        "     FROM agile.LISTENTRY le WHERE le.LANGID = 0 AND p3.MULTILIST32 IS NOT NULL\n" +
        "      AND INSTR(p3.MULTILIST32, ','||le.ENTRYID||',') > 0) AS subcontractorsDisplay,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST36 AND le.LANGID = 0 AND ROWNUM = 1) AS documentStyle,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST38 AND le.LANGID = 0 AND ROWNUM = 1) AS documentClassification,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST37 AND le.LANGID = 0 AND ROWNUM = 1) AS varType,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST34 AND le.LANGID = 0 AND ROWNUM = 1) AS supportDocCategory,\n" +
        "  p3.TEXT32 AS supportDocNumber,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST39 AND le.LANGID = 0 AND ROWNUM = 1) AS pmChecklist,\n" +
        "  (SELECT le.ENTRYVALUE FROM agile.LISTENTRY le WHERE le.ENTRYID = p3.LIST35 AND le.LANGID = 0 AND ROWNUM = 1) AS appliesAllParts,\n" +
        "  p3.TEXT33 AS partNumber,\n" +
        "  (SELECT LISTAGG(ri.ITEM_NUMBER, ';') WITHIN GROUP (ORDER BY INSTR(p2.MULTILIST01, ','||ri.ID||','))\n" +
        "     FROM agile.ITEM ri WHERE p2.MULTILIST01 IS NOT NULL\n" +
        "      AND INSTR(p2.MULTILIST01, ','||ri.ID||',') > 0) AS referencedDocuments,\n" +
        "  (SELECT LISTAGG(le.ENTRYVALUE, ';') WITHIN GROUP (ORDER BY le.ENTRYVALUE)\n" +
        "     FROM agile.LISTENTRY le, agile.AGILE_FLEX af\n" +
        "    WHERE af.ID = it.ID AND af.ATTID = 251746287 AND le.LANGID = 0\n" +
        "      AND INSTR(af.TEXT, ','||le.ENTRYID||',') > 0) AS productGroup,\n" +
        "  (SELECT LISTAGG(le.ENTRYVALUE, ';') WITHIN GROUP (ORDER BY le.ENTRYVALUE)\n" +
        "     FROM agile.LISTENTRY le, agile.AGILE_FLEX af\n" +
        "    WHERE af.ID = it.ID AND af.ATTID = 251746291 AND le.LANGID = 0\n" +
        "      AND INSTR(af.TEXT, ','||le.ENTRYID||',') > 0) AS spec,\n" +
        "  (SELECT LISTAGG(pi.ITEM_NUMBER, ';') WITHIN GROUP (ORDER BY pi.ITEM_NUMBER)\n" +
        "     FROM agile.ITEM pi, agile.AGILE_FLEX af\n" +
        "    WHERE af.ID = it.ID AND af.ATTID = 251747721\n" +
        "      AND INSTR(af.TEXT, ','||pi.ID||',') > 0) AS product\n" +
        "FROM it\n" +
        "LEFT JOIN agile.PAGE_TWO   p2 ON p2.ID = it.ID\n" +
        "LEFT JOIN agile.PAGE_THREE p3 ON p3.ID = it.ID";

    /** Ordered list of the SELECT aliases — also the JSON keys the panel reads. */
    private static final String[] FIELDS = {
        "documentCategory", "productLine", "revReleaseDate",
        "oldDocumentNumber", "createUser", "createDate",
        "apdsApplicability", "lastReviewedDate", "nextReviewDate",
        "siteLocation", "functionSubFunction", "documentOwners",
        "subcontractorsDisplay", "documentStyle", "documentClassification",
        "varType", "supportDocCategory", "supportDocNumber", "pmChecklist",
        "referencedDocuments", "appliesAllParts", "partNumber",
        "productGroup", "spec", "product"
    };

    /**
     * Live-read the extended IMS Document Details for one document.
     *
     * @param itemNumber the Agile document number (e.g. {@code 03-32-WW-02-00074})
     * @return a map of panel field &rarr; display value; only non-blank values are
     *         included so unset attributes keep rendering "&mdash;". Never null; empty
     *         on any error or unknown item.
     */
    public Map<String, String> fetch(String itemNumber) {
        Map<String, String> out = new LinkedHashMap<>();
        if (itemNumber == null || itemNumber.trim().isEmpty()) return out;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setString(1, itemNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (String f : FIELDS) {
                        String v = rs.getString(f);
                        if (v != null && !v.trim().isEmpty()) out.put(f, v.trim());
                    }
                }
            }
        } catch (Exception e) {
            // Fail-soft: the panel keeps its "—" placeholders; never break the page.
            LOG.log(Level.WARNING, "IMS doc-details live lookup failed for " + itemNumber, e);
        }
        return out;
    }
}
