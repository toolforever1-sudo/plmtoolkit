package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class RevCompareService {

    private static final Logger logger = Logger.getLogger(RevCompareService.class.getName());

    private final DataSource dataSource;

    public RevCompareService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // =========================================================================
    // Query A — rev dropdown for a given part number
    // =========================================================================

    public List<Map<String, String>> getRevisions(String partNumber) {
        // Three Agile-aware behaviours encoded in this query:
        //   1. Pending revs are wrapped in parens — Agile's UI convention for
        //      "this rev is proposed by an unreleased change". Detected via
        //      r.RELEASE_DATE IS NULL on a rev that has a REV_NUMBER.
        //   2. Soft-deleted changes are excluded — Agile keeps deleted rows in
        //      the `change` table with DELETE_FLAG set; those should never
        //      appear as selectable revs. Introductory rows (r.CHANGE = 0) have
        //      no parent change row so the DELETE_FLAG check uses an OR.
        //   3. Order DESC by **creation** time, not release time. Agile rev IDs
        //      are monotonically assigned in creation order — a rev created
        //      later always has a larger ID, regardless of when (or whether)
        //      it released. Sorting by r.ID DESC therefore gives us "newest
        //      created first" for both released and pending revs, which means
        //      a pending rev created after the most recent released rev shows
        //      up at the top of the dropdown (what users expect).
        //      Introductory (CHANGE=0) is forced to the bottom.
        String sql =
            "SELECT " +
            "    CASE WHEN r.REV_NUMBER IS NULL AND r.CHANGE = 0 THEN 'Introductory' " +
            "         WHEN r.RELEASE_DATE IS NULL THEN '(' || r.REV_NUMBER || ')' " +
            "         ELSE r.REV_NUMBER END AS rev_label, " +
            "    c.CHANGE_NUMBER AS change_number, " +
            "    c.RELEASE_DATE  AS change_release_date, " +
            "    r.RELEASE_DATE  AS rev_release_date, " +
            "    r.ID            AS rev_id, " +
            "    r.CHANGE        AS change_id " +
            "FROM item i " +
            "JOIN rev r ON r.ITEM = i.ID " +
            "LEFT JOIN change c ON c.ID = r.CHANGE " +
            "WHERE UPPER(i.ITEM_NUMBER) = UPPER(?) " +
            "  AND (r.REV_NUMBER IS NOT NULL OR r.CHANGE = 0) " +
            "  AND (r.CHANGE = 0 OR c.DELETE_FLAG IS NULL) " +
            "ORDER BY " +
            "    CASE WHEN r.CHANGE = 0 THEN 1 ELSE 0 END, " +
            "    r.ID DESC";

        List<Map<String, String>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, partNumber.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("revLabel", nvl(rs.getString("rev_label")));
                    row.put("changeNumber", nvl(rs.getString("change_number")));
                    row.put("changeReleaseDate", nvl(rs.getString("change_release_date")));
                    row.put("revReleaseDate", nvl(rs.getString("rev_release_date")));
                    row.put("revId", nvl(rs.getString("rev_id")));
                    row.put("changeId", nvl(rs.getString("change_id")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            logger.warning("Rev query failed for " + partNumber + ": " + e.getMessage());
            throw new RuntimeException("Rev query failed: " + e.getMessage(), e);
        }
        return results;
    }

    // =========================================================================
    // Query B — BOM detail for a specific (part, rev, change) tuple
    //
    // Optimised vs. the handoff SQL:
    //   1. Removed params CTE — bind variables used directly.
    //   2. Replaced EXISTS/NOT EXISTS in scoped_bom with LEFT JOINs
    //      (lets Oracle use hash joins instead of correlated probes).
    //   3. Eliminated comp_tip and ref_des_agg CTEs entirely — replaced
    //      with inline scalar subqueries that do one index probe per
    //      BOM row instead of materializing intermediate result sets.
    // =========================================================================

    public Map<String, Object> getRevDetail(String partNumber, String revLabel, String changeNumber) {
        // Bind positions:  1 = part_number
        //                  2 = rev_label (Introductory check)
        //                  3 = rev_label (REV_NUMBER match)
        //                  4 = change_number_opt (NULL check)
        //                  5 = change_number_opt (CHANGE_NUMBER match)
        String sql =
            "WITH parent_item AS ( " +
            "    SELECT i.ID AS item_id, i.ITEM_NUMBER " +
            "    FROM item i " +
            "    WHERE UPPER(i.ITEM_NUMBER) = UPPER(?) " +
            "), " +
            "parent_rev AS ( " +
            "    SELECT r.ID AS rev_id, r.ITEM AS item_id, r.REV_NUMBER, " +
            "           r.CHANGE AS change_id, r.RELEASE_DATE, " +
            "           r.DESCRIPTION AS rev_description, " +
            "           r.RELEASE_TYPE AS lifecycle_node_id, " +
            "           r.INCORPORATED, c.CHANGE_NUMBER " +
            "    FROM rev r " +
            "    JOIN parent_item pi ON pi.item_id = r.ITEM " +
            "    LEFT JOIN change c ON c.ID = r.CHANGE " +
            "    WHERE (? = 'Introductory' " +
            "           AND r.REV_NUMBER IS NULL AND r.CHANGE = 0) " +
            "       OR (r.REV_NUMBER = ? " +
            "           AND (? IS NULL " +
            "                OR c.CHANGE_NUMBER = ?)) " +
            "), " +
            // LEFT JOIN change for CHANGE_IN/CHANGE_OUT instead of EXISTS subqueries.
            // PT-37 diagnostics: also project change_in/out id, number, release_date
            // up through the CTE so the outer SELECT can see them.
            "scoped_bom AS ( " +
            "    SELECT b.ID, b.ITEM, b.ITEM_NUMBER, b.COMPONENT, b.FIND_NUMBER, " +
            "           b.QUANTITY, b.DESCRIPTION, b.NOTES, b.TEXT01, b.LIST01, " +
            "           b.CHANGE_IN AS change_in_id_raw, " +
            "           b.CHANGE_OUT AS change_out_id_raw, " +
            "           ci.CHANGE_NUMBER AS change_in_num_raw, " +
            "           co.CHANGE_NUMBER AS change_out_num_raw, " +
            "           ci.RELEASE_DATE AS change_in_release_date_raw, " +
            "           co.RELEASE_DATE AS change_out_release_date_raw " +
            "    FROM parent_item pi " +
            "    JOIN parent_rev pr ON pr.item_id = pi.item_id " +
            "    JOIN bom b ON b.ITEM = pi.item_id " +
            "    LEFT JOIN change ci ON ci.ID = b.CHANGE_IN " +
            "    LEFT JOIN change co ON co.ID = b.CHANGE_OUT " +
            // Three branches:
            //   (1) Introductory rev (no rev number, no parent change) — pre-create rows only.
            //   (2) Released rev — rows that were CHANGE_IN'd by pr.RELEASE_DATE
            //       and not yet CHANGE_OUT'd. Note: removed earlier hacks like
            //       "OR b.CHANGE_IN = pr.change_id" — those incorrectly
            //       included same-ECO net-removes (validated against
            //       SDSDUW3-064G-JNJIN rev 3 / ECO-61678-A / component 54-55-09518).
            //   (3) Unreleased rev (pending change, pr.RELEASE_DATE IS NULL) —
            //       project the BOM as it WILL be when the pending change
            //       releases: include rows that already existed (CHANGE_IN = 0 or
            //       a released ECO) plus rows added by THIS pending change; exclude
            //       rows whose CHANGE_OUT is either a released ECO or this pending
            //       change. Without branch (3) the query returned 0 rows for any
            //       unreleased rev because every "RELEASE_DATE <= pr.RELEASE_DATE"
            //       comparison evaluates UNKNOWN against a NULL pr.RELEASE_DATE.
            "    WHERE (pr.REV_NUMBER IS NULL AND pr.change_id = 0 AND b.CHANGE_IN = 0) " +
            "       OR (pr.change_id <> 0 AND pr.RELEASE_DATE IS NOT NULL " +
            "           AND (b.CHANGE_IN = 0 " +
            "                OR (ci.RELEASE_DATE IS NOT NULL " +
            "                    AND ci.RELEASE_DATE <= pr.RELEASE_DATE)) " +
            "           AND (b.CHANGE_OUT = 0 " +
            "                OR co.RELEASE_DATE IS NULL " +
            "                OR co.RELEASE_DATE > pr.RELEASE_DATE)) " +
            "       OR (pr.change_id <> 0 AND pr.RELEASE_DATE IS NULL " +
            "           AND (b.CHANGE_IN = 0 " +
            "                OR ci.RELEASE_DATE IS NOT NULL " +
            "                OR b.CHANGE_IN = pr.change_id) " +
            "           AND (b.CHANGE_OUT = 0 " +
            "                OR (co.RELEASE_DATE IS NULL AND b.CHANGE_OUT <> pr.change_id)) " +
            // PT-37 fix: exclude rows that this same pending ECO has staged
            // for redline-removal. Agile records the removal by inserting a
            // replacement row with CHANGE_IN = pending ECO and PRIOR_BOM =
            // id-of-row-being-retired. CHANGE_OUT on the doomed row only flips
            // at release time, so during the pending window the only signal
            // is the new row's PRIOR_BOM link. Confirmed via DB MCP probe by
            // Vikas's other agent (2026-05-11) — see
            // docs/handoffs/2026-05-11-pt37-agile-redline-schema.md and
            // ~/Downloads/PT-37-findings.md for the full schema discovery.
            "           AND NOT EXISTS ( " +
            "                SELECT 1 FROM bom bnew " +
            "                 WHERE bnew.CHANGE_IN = pr.change_id " +
            "                   AND bnew.PRIOR_BOM = b.ID)) " +
            ") " +
            "SELECT " +
            "    pi.ITEM_NUMBER AS parent_pn, " +
            "    CASE WHEN pr.REV_NUMBER IS NULL AND pr.change_id = 0 " +
            "         THEN 'Introductory' ELSE pr.REV_NUMBER END AS parent_rev, " +
            "    pr.CHANGE_NUMBER AS parent_change, " +
            "    pr.rev_description AS parent_description, " +
            "    COALESCE(nt_life.DESCRIPTION, 'Preliminary') AS parent_lifecycle, " +
            "    CASE WHEN pr.INCORPORATED = 1 THEN 'Incorporated' " +
            "         ELSE 'Unincorporated' END AS parent_incorporation, " +
            "    b.ITEM_NUMBER AS component_pn, " +
            "    COALESCE(b.DESCRIPTION, comp_item.DESCRIPTION) AS component_description, " +
            // Inline scalar subqueries — one index probe per row, no CTE materialization
            "    (SELECT r2.REV_NUMBER FROM rev r2 " +
            "     WHERE r2.ITEM = b.COMPONENT AND r2.LATEST_FLAG = 1 " +
            "       AND r2.REV_NUMBER IS NOT NULL AND ROWNUM = 1) AS component_rev, " +
            "    (SELECT c2.CHANGE_NUMBER FROM rev r2 " +
            "     JOIN change c2 ON c2.ID = r2.CHANGE " +
            "     WHERE r2.ITEM = b.COMPONENT AND r2.LATEST_FLAG = 1 " +
            "       AND r2.REV_NUMBER IS NOT NULL AND ROWNUM = 1) AS component_change, " +
            "    b.QUANTITY AS qty_per, " +
            "    le_type.ENTRYVALUE AS bom_type, " +
            "    b.FIND_NUMBER AS seq_num, " +
            "    b.TEXT01 AS primary_pn, " +
            // PT-37 diagnostics: how Agile records this row's lifecycle. Lets us
            // see whether a "removed by pending ECO" row has b.CHANGE_OUT set
            // yet or whether the removal is staged in a different table.
            "    b.ID AS bom_row_id, " +
            "    b.change_in_id_raw AS change_in_id, " +
            "    b.change_out_id_raw AS change_out_id, " +
            "    b.change_in_num_raw AS change_in_num, " +
            "    b.change_out_num_raw AS change_out_num, " +
            "    b.change_in_release_date_raw AS change_in_release_date, " +
            "    b.change_out_release_date_raw AS change_out_release_date, " +
            // Inline ref des aggregation — only for this specific BOM row
            "    (SELECT LISTAGG(rd.LABEL, ',' ON OVERFLOW TRUNCATE '...' WITHOUT COUNT) " +
            "     WITHIN GROUP (ORDER BY rd.LABEL) " +
            "     FROM refdesig rd WHERE rd.BOM = b.ID) AS ref_des, " +
            "    b.NOTES AS bom_notes " +
            "FROM parent_item pi " +
            "JOIN parent_rev pr ON pr.item_id = pi.item_id " +
            "LEFT JOIN scoped_bom b ON b.ITEM = pi.item_id " +
            "LEFT JOIN item comp_item ON comp_item.ID = b.COMPONENT " +
            "LEFT JOIN nodetable nt_life ON nt_life.ID = pr.lifecycle_node_id " +
            "                           AND nt_life.PARENTID = 1514 " +
            "LEFT JOIN listentry le_type ON le_type.ENTRYID = b.LIST01 " +
            "                           AND le_type.LANGID = 0 " +
            "ORDER BY b.FIND_NUMBER, b.ID";

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> header = new LinkedHashMap<>();
        List<Map<String, String>> rows = new ArrayList<>();

        String changeParam = (changeNumber == null || changeNumber.trim().isEmpty()) ? null : changeNumber.trim();

        // The frontend sends rev labels as the dropdown displays them — pending revs
        // come through wrapped in parens like "(B)" (see getRevisions for where the
        // wrap happens). The DB stores the bare REV_NUMBER ("B"), so strip the parens
        // before binding. Released revs are unaffected (no parens to strip).
        String revBind = revLabel == null ? "" : revLabel.trim();
        if (revBind.length() >= 2 && revBind.charAt(0) == '(' && revBind.charAt(revBind.length() - 1) == ')') {
            revBind = revBind.substring(1, revBind.length() - 1).trim();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, partNumber.trim());        // parent_item
            ps.setString(2, revBind);                   // Introductory check
            ps.setString(3, revBind);                   // REV_NUMBER match
            if (changeParam != null) {
                ps.setString(4, changeParam);           // NULL check branch
                ps.setString(5, changeParam);           // CHANGE_NUMBER match
            } else {
                ps.setNull(4, Types.VARCHAR);
                ps.setNull(5, Types.VARCHAR);
            }

            try (ResultSet rs = ps.executeQuery()) {
                boolean headerPopulated = false;
                while (rs.next()) {
                    if (!headerPopulated) {
                        header.put("parent_pn", nvl(rs.getString("parent_pn")));
                        header.put("parent_rev", nvl(rs.getString("parent_rev")));
                        header.put("parent_change", nvl(rs.getString("parent_change")));
                        header.put("parent_description", nvl(rs.getString("parent_description")));
                        header.put("parent_lifecycle", nvl(rs.getString("parent_lifecycle")));
                        header.put("parent_incorporation", nvl(rs.getString("parent_incorporation")));
                        headerPopulated = true;
                    }
                    String componentPn = rs.getString("component_pn");
                    if (componentPn != null) {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("component", componentPn);
                        row.put("description", nvl(rs.getString("component_description")));
                        row.put("componentRev", nvl(rs.getString("component_rev")));
                        row.put("componentChange", nvl(rs.getString("component_change")));
                        row.put("qty", nvl(rs.getString("qty_per")));
                        row.put("bomType", nvl(rs.getString("bom_type")));
                        row.put("seqNum", nvl(rs.getString("seq_num")));
                        row.put("primaryPn", nvl(rs.getString("primary_pn")));
                        row.put("refDes", nvl(rs.getString("ref_des")));
                        row.put("notes", nvl(rs.getString("bom_notes")));
                        // PT-37 diagnostics — see service comment above the SELECT.
                        row.put("_diagBomRowId", nvl(rs.getString("bom_row_id")));
                        row.put("_diagChangeInId", nvl(rs.getString("change_in_id")));
                        row.put("_diagChangeOutId", nvl(rs.getString("change_out_id")));
                        row.put("_diagChangeInNum", nvl(rs.getString("change_in_num")));
                        row.put("_diagChangeOutNum", nvl(rs.getString("change_out_num")));
                        row.put("_diagChangeInReleaseDate", nvl(rs.getString("change_in_release_date")));
                        row.put("_diagChangeOutReleaseDate", nvl(rs.getString("change_out_release_date")));
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.warning("Rev detail query failed for " + partNumber + " rev " + revLabel + ": " + e.getMessage());
            throw new RuntimeException("Rev detail query failed: " + e.getMessage(), e);
        }

        result.put("header", header);
        result.put("rows", rows);
        return result;
    }

    private String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
