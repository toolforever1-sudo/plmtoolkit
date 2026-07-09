package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Logger;

@Service
public class EcoTimelineService {

    private static final Logger logger = Logger.getLogger(EcoTimelineService.class.getName());
    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    private static final int MAX_ASSEMBLIES = 5000;   // safety cap on the union walk

    private final DataSource dataSource;
    private final EcoTimelineClassifier classifier = new EcoTimelineClassifier();
    private final I2PrimaryLookupService i2Lookup;

    public EcoTimelineService(@Qualifier("dataSource") DataSource dataSource,
                              I2PrimaryLookupService i2Lookup) {
        this.dataSource = dataSource;
        this.i2Lookup = i2Lookup;
    }

    /** Main entry: returns { rows, ecoCount, componentCount, queryTimeMs,
     *  truncated, depthLimitReached, item, from, to } or { error }. */
    public Map<String, Object> query(String item, LocalDate from, LocalDate to, int maxDepth) {
        long start = System.currentTimeMillis();
        Map<String, Object> resp = new LinkedHashMap<>();

        Timestamp fromTs = Timestamp.from(from.atStartOfDay(PT).toInstant());
        // inclusive end-of-day: midnight of (to+1) minus 1 ms
        Timestamp toTs = Timestamp.from(to.plusDays(1).atStartOfDay(PT).toInstant().minusMillis(1));

        try {
            Long rootId = resolveItemId(item.trim());
            if (rootId == null) {
                resp.put("error", "Item not found: " + item);
                return resp;
            }

            // Step 1 — evolved-union tree: assembly id -> min indent level, + part number + breadcrumb path.
            LinkedHashMap<Long, int[]> levelById = new LinkedHashMap<>(); // value[0]=level
            Map<Long, String> pnById = new HashMap<>();
            Map<Long, String> pathById = new HashMap<>();  // root→assembly breadcrumb, e.g. "SKU / SubA / SubB"
            boolean[] truncatedOut = { false };  // set true only if the walk hit the cap with rows still pending
            int deepest = walkUnionTree(rootId, fromTs, toTs, maxDepth, levelById, pnById, pathById, truncatedOut);
            boolean truncated = truncatedOut[0];

            // Step 2+3 — per assembly redline -> classify.
            List<EcoTimelineRow> rows = new ArrayList<>();
            for (Map.Entry<Long, int[]> e : levelById.entrySet()) {
                List<BomRedlineRow> redline = fetchRedline(e.getKey(), fromTs, toTs);
                rows.addAll(classifier.classifyAssembly(
                        pnById.get(e.getKey()), pathById.get(e.getKey()), e.getValue()[0], redline, fromTs, toTs));
            }

            // Sort: ECO release date asc, then indent level, then parent assembly.
            rows.sort(Comparator
                    .comparingLong(EcoTimelineRow::getReleaseTsMillis)
                    .thenComparingInt(EcoTimelineRow::getLevel)
                    .thenComparing(EcoTimelineRow::getParentAssembly));

            // Enrich with the i2 / Blue Yonder primary component(s) for each component.
            // Fail-soft: an empty map just leaves the Primary # column blank.
            Set<String> components = new HashSet<>();
            for (EcoTimelineRow r : rows) if (!r.getComponent().isEmpty()) components.add(r.getComponent());
            Map<String, String> primaryByItem = i2Lookup.primaryByItem(components);
            for (EcoTimelineRow r : rows) {
                String p = primaryByItem.get(r.getComponent());
                if (p != null) r.setPrimaryNumber(p);
            }

            Set<String> ecos = new HashSet<>();
            Set<String> comps = new HashSet<>();
            for (EcoTimelineRow r : rows) { ecos.add(r.getEcoNumber()); comps.add(r.getComponent()); }

            resp.put("rows", rows);
            resp.put("ecoCount", ecos.size());
            resp.put("componentCount", comps.size());
            resp.put("queryTimeMs", System.currentTimeMillis() - start);
            resp.put("truncated", truncated);
            resp.put("depthLimitReached", deepest >= maxDepth);
            resp.put("item", item.trim());
            resp.put("from", from.toString());
            resp.put("to", to.toString());
        } catch (Exception e) {
            logger.warning("ECO timeline query failed for " + item + ": " + e.getMessage());
            resp.put("error", "Query failed: " + e.getMessage());
        }
        return resp;
    }

    private Long resolveItemId(String item) throws SQLException {
        String sql = "SELECT ID FROM item WHERE UPPER(ITEM_NUMBER) = UPPER(?) AND ROWNUM = 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setString(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("ID") : null;
            }
        }
    }

    /** Recursive walk over window-active BOM edges. Fills levelById (assembly id ->
     *  [minLevel]) and pnById; returns the deepest level reached. Sets truncatedOut[0]
     *  true only if the result set held MORE than MAX_ASSEMBLIES rows (i.e. genuine
     *  truncation — not merely exactly the cap). */
    private int walkUnionTree(long rootId, Timestamp fromTs, Timestamp toTs, int maxDepth,
                              LinkedHashMap<Long, int[]> levelById, Map<Long, String> pnById,
                              Map<Long, String> pathById, boolean[] truncatedOut)
            throws SQLException {
        // edges = BOM lines active at any point in [from,to]:
        //   added on/before window end  AND  not removed before window start.
        // The parent's item number rides along on each edge so SYS_CONNECT_BY_PATH
        // can build the root→assembly breadcrumb during the walk. We keep the path
        // from the SHALLOWEST occurrence of each assembly (KEEP DENSE_RANK FIRST).
        String sql =
            "SELECT t.parent_id, MIN(t.lvl) AS lvl, MAX(t.pn) AS pn, " +
            "       MIN(t.path) KEEP (DENSE_RANK FIRST ORDER BY t.lvl) AS path FROM ( " +
            "  SELECT LEVEL AS lvl, e.parent_id, e.child_id, e.pn, " +
            "         SYS_CONNECT_BY_PATH(e.pn, ' / ') AS path FROM ( " +
            "    SELECT b.ITEM AS parent_id, b.COMPONENT AS child_id, ip.ITEM_NUMBER AS pn " +
            "    FROM bom b " +
            "    JOIN item ip ON ip.ID = b.ITEM " +
            "    LEFT JOIN change ci ON ci.ID = b.CHANGE_IN " +
            "    LEFT JOIN change co ON co.ID = b.CHANGE_OUT " +
            "    WHERE b.COMPONENT IS NOT NULL " +
            "      AND (b.CHANGE_IN = 0 OR (ci.RELEASE_DATE IS NOT NULL AND ci.RELEASE_DATE <= ?)) " +
            "      AND (b.CHANGE_OUT = 0 OR co.RELEASE_DATE IS NULL OR co.RELEASE_DATE >= ?) " +
            "  ) e " +
            "  START WITH e.parent_id = ? " +
            "  CONNECT BY NOCYCLE PRIOR e.child_id = e.parent_id AND LEVEL <= ? " +
            ") t " +
            "GROUP BY t.parent_id";

        int deepest = 0;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(120);
            ps.setTimestamp(1, toTs);     // CHANGE_IN <= window end
            ps.setTimestamp(2, fromTs);   // CHANGE_OUT >= window start
            ps.setLong(3, rootId);
            ps.setInt(4, maxDepth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Stop before exceeding the cap; a row still pending here means
                    // the structure is genuinely larger than we'll report.
                    if (levelById.size() >= MAX_ASSEMBLIES) { truncatedOut[0] = true; break; }
                    long id = rs.getLong("parent_id");
                    int lvl = rs.getInt("lvl");
                    levelById.put(id, new int[]{ lvl });
                    pnById.put(id, rs.getString("pn"));
                    // strip the leading " / " SYS_CONNECT_BY_PATH prepends
                    String p = rs.getString("path");
                    if (p != null && p.startsWith(" / ")) p = p.substring(3);
                    pathById.put(id, p);
                    if (lvl > deepest) deepest = lvl;
                }
            }
        }
        return deepest;
    }

    /** Redline rows for one assembly whose CHANGE_IN or CHANGE_OUT released in window. */
    private List<BomRedlineRow> fetchRedline(long assemblyId, Timestamp fromTs, Timestamp toTs)
            throws SQLException {
        String sql =
            "SELECT b.ID, b.PRIOR_BOM, b.ITEM_NUMBER, " +
            "       COALESCE(b.DESCRIPTION, ci_item.DESCRIPTION) AS comp_desc, " +
            "       b.QUANTITY, b.FIND_NUMBER, b.NOTES, " +
            "       cin.CHANGE_NUMBER AS cin_num, cin.RELEASE_DATE AS cin_rd, " +
            "       cin.DESCRIPTION AS cin_desc, cin_st.DESCRIPTION AS cin_status, " +
            "       cout.CHANGE_NUMBER AS cout_num, cout.RELEASE_DATE AS cout_rd, " +
            "       cout.DESCRIPTION AS cout_desc, cout_st.DESCRIPTION AS cout_status " +
            "FROM bom b " +
            "LEFT JOIN item ci_item ON ci_item.ID = b.COMPONENT " +
            "LEFT JOIN change cin ON cin.ID = b.CHANGE_IN " +
            "LEFT JOIN nodetable cin_st ON cin_st.ID = cin.STATUS " +
            "LEFT JOIN change cout ON cout.ID = b.CHANGE_OUT " +
            "LEFT JOIN nodetable cout_st ON cout_st.ID = cout.STATUS " +
            "WHERE b.ITEM = ? " +
            "  AND ( (cin.RELEASE_DATE IS NOT NULL AND cin.RELEASE_DATE BETWEEN ? AND ? " +
            "         AND NVL(cin.DELETE_FLAG,0) <> 1) " +
            "     OR (cout.RELEASE_DATE IS NOT NULL AND cout.RELEASE_DATE BETWEEN ? AND ? " +
            "         AND NVL(cout.DELETE_FLAG,0) <> 1) )";

        List<BomRedlineRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(120);
            ps.setLong(1, assemblyId);
            ps.setTimestamp(2, fromTs);
            ps.setTimestamp(3, toTs);
            ps.setTimestamp(4, fromTs);
            ps.setTimestamp(5, toTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BomRedlineRow r = new BomRedlineRow();
                    r.bomRowId = rs.getLong("ID");
                    r.priorBom = rs.getLong("PRIOR_BOM");   // 0 if NULL
                    r.componentPn = rs.getString("ITEM_NUMBER");
                    r.componentDesc = rs.getString("comp_desc");
                    r.quantity = rs.getString("QUANTITY");
                    r.findNumber = rs.getString("FIND_NUMBER");
                    r.notes = rs.getString("NOTES");
                    r.changeInNum = rs.getString("cin_num");
                    r.changeInRd = rs.getTimestamp("cin_rd");
                    r.changeInDesc = rs.getString("cin_desc");
                    r.changeInStatus = rs.getString("cin_status");
                    r.changeOutNum = rs.getString("cout_num");
                    r.changeOutRd = rs.getTimestamp("cout_rd");
                    r.changeOutDesc = rs.getString("cout_desc");
                    r.changeOutStatus = rs.getString("cout_status");
                    out.add(r);
                }
            }
        }
        return out;
    }
}
