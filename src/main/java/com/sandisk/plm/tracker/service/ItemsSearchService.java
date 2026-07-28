package com.sandisk.plm.tracker.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Query-builder backend for the Items / Search sub-tab (PT-67).
 *
 * <p>Composes a parameterized {@code SELECT ... FROM item_extract WHERE ...}
 * from a list of {@link Condition} rows. Every column name and operator is
 * checked against a server-side allow-list before being interpolated into the
 * SQL string — values always flow through {@code PreparedStatement} bind
 * variables — so user input never touches the SQL parser as identifiers or
 * literals.</p>
 *
 * <p>VARCHAR date columns ({@code REV_RELEASE_DATE}, {@code CREATE_DATE},
 * {@code RELEASE_DATE}) are wrapped with {@code TO_DATE(SUBSTR(col, 1, N),
 * '<format>')} per column so the user never sees the format gotcha.</p>
 *
 * <p><b>PT-95 (derived Agile columns):</b> CCB Category / Customer / Program +
 * Group live on the Agile item {@code PAGE_TWO} list columns (the "Inv/Planning"
 * tab), not in {@code item_extract}. {@code item_extract} (owner CUSTOM_USER)
 * and the {@code agile} schema live in the same Oracle instance but have no
 * cross-schema grants, so a single-connection join is impossible. These columns
 * are therefore resolved by a second query on the primary ({@code agile})
 * datasource, keyed by part number, and merged into the result rows in Java.
 * They are result-only: not filterable, no distinct probe, and only resolved
 * when the user actually selects them.</p>
 */
@Service
public class ItemsSearchService {

    private static final Logger LOG = Logger.getLogger(ItemsSearchService.class.getName());

    /** item_extract lives here (owner CUSTOM_USER). */
    private final DataSource customDataSource;
    /** agile.* schema (owner AGILE) — used only to resolve the PT-95 derived columns. */
    private final DataSource agileDataSource;

    public ItemsSearchService(@Qualifier("customDataSource") DataSource customDataSource,
                              @Qualifier("dataSource") DataSource agileDataSource) {
        this.customDataSource = customDataSource;
        this.agileDataSource = agileDataSource;
    }

    public enum ColType { STRING, CATEGORICAL, DATE, MULTI }

    /** Column metadata — drives the dropdown order, type-aware operators, and
     *  the "common 15 vs More fields" split. {@code common=true} means the
     *  column appears in the top-level dropdown; the rest are nested under
     *  "More fields…". */
    public static final class ColumnDef {
        public final String name;
        public final ColType type;
        public final boolean common;
        public final String label;
        /** Derived columns are resolved from the Agile schema on a second
         *  datasource rather than being a physical item_extract column. They are
         *  result-only: not filterable and no distinct-value probe. */
        public final boolean derived;
        /** Derived only: the Agile PAGE_TWO column that holds the value(s),
         *  e.g. {@code MULTILIST06} or {@code LIST19}. */
        public final String agileCol;
        /** Derived only: the LISTENTRY.PARENTID for this attribute's value list. */
        public final long agileListParent;
        /** Derived only: true for multi-value (MULTILIST, comma-wrapped ids),
         *  false for single-value (LIST, bare id). */
        public final boolean agileMulti;

        /** Physical item_extract column. */
        public ColumnDef(String name, ColType type, boolean common, String label) {
            this.name = name; this.type = type; this.common = common; this.label = label;
            this.derived = false; this.agileCol = null; this.agileListParent = 0L; this.agileMulti = false;
        }
        /** Derived Agile column. */
        public ColumnDef(String name, ColType type, boolean common, String label,
                         String agileCol, long agileListParent, boolean agileMulti) {
            this.name = name; this.type = type; this.common = common; this.label = label;
            this.derived = true; this.agileCol = agileCol;
            this.agileListParent = agileListParent; this.agileMulti = agileMulti;
        }
    }

    /** Allow-list. Order here drives the UI dropdown order (common first). */
    public static final List<ColumnDef> COLUMNS = Collections.unmodifiableList(Arrays.asList(
        // -------- Common 15 (shown by default in the dropdown) --------
        new ColumnDef("PART_NUMBER",        ColType.STRING,      true,  "Part Number"),
        new ColumnDef("DESCRIPTION",        ColType.STRING,      true,  "Description"),
        new ColumnDef("REV",                ColType.STRING,      true,  "Rev"),
        new ColumnDef("STATUSCODE",         ColType.CATEGORICAL, true,  "Status Code"),
        new ColumnDef("LIFECYCLE_PHASE",    ColType.CATEGORICAL, true,  "Lifecycle Phase"),
        new ColumnDef("NEW_PART_CLASS",     ColType.CATEGORICAL, true,  "Part Class"),
        new ColumnDef("MATERIAL_GROUP",     ColType.CATEGORICAL, true,  "Material Group"),
        new ColumnDef("MATERIAL_TYPE",      ColType.CATEGORICAL, true,  "Material Type"),
        new ColumnDef("PRODUCTLINE",        ColType.CATEGORICAL, true,  "Product Line"),
        new ColumnDef("SUBCONTRACTORS",     ColType.MULTI,       true,  "Subcontractors"),
        new ColumnDef("ACTUAL_BUILD_PLANT", ColType.CATEGORICAL, true,  "Build Plant"),
        new ColumnDef("PM",                 ColType.CATEGORICAL, true,  "PM"),
        new ColumnDef("FAMILYNAME",         ColType.STRING,      true,  "Family Name"),
        new ColumnDef("REV_RELEASE_DATE",   ColType.DATE,        true,  "Rev Release Date"),
        new ColumnDef("CREATE_DATE",        ColType.DATE,        true,  "Create Date"),
        // -------- "More fields…" (the other 15) --------
        new ColumnDef("PRODUCT_TYPE",          ColType.CATEGORICAL, false, "Product Type"),
        new ColumnDef("CATEGORY",              ColType.CATEGORICAL, false, "Category"),
        new ColumnDef("CAPACITY",              ColType.STRING,      false, "Capacity"),
        new ColumnDef("CUSTOMER_PN",           ColType.STRING,      false, "Customer PN"),
        new ColumnDef("RELEASE_DATE",          ColType.DATE,        false, "Release Date"),
        new ColumnDef("PRODUCTDIVISION",       ColType.CATEGORICAL, false, "Product Division"),
        new ColumnDef("PROGRAM_NAME",          ColType.STRING,      false, "Program Name"),
        new ColumnDef("MARKETING_PROGRAM",     ColType.STRING,      false, "Marketing Program"),
        new ColumnDef("AS_SOLD",               ColType.CATEGORICAL, false, "As Sold"),
        new ColumnDef("GOLDEN_PART",           ColType.CATEGORICAL, false, "Golden Part"),
        new ColumnDef("ECCN",                  ColType.STRING,      false, "ECCN"),
        new ColumnDef("CLASSIFICATION_PDM",    ColType.CATEGORICAL, false, "Classification PDM"),
        new ColumnDef("CONTROLLER_TECHNOLOGY", ColType.CATEGORICAL, false, "Controller Technology"),
        new ColumnDef("MEMORY_PACKAGE",        ColType.CATEGORICAL, false, "Memory Package"),
        new ColumnDef("PKG_TYPE",              ColType.CATEGORICAL, false, "Package Type"),
        new ColumnDef("BOM_TYPE",              ColType.CATEGORICAL, false, "BOM Type"),
        // -------- Derived from Agile schema (PT-95, Jimmy Sessumes 2026-07-02) --------
        // Resolved on the agile datasource, keyed by part number. Mapping
        // validated 300/300 against Jimmy's SKU extract:
        //   CCB Category = PAGE_TWO.MULTILIST06 (multi, list 251737028)
        //   CCB Customer = PAGE_TWO.MULTILIST07 (multi, list 251736797)
        //   CCB Program  = PAGE_TWO.MULTILIST05 (multi, list 251740480)
        //   Group        = PAGE_TWO.LIST19      (single, list 20254)
        new ColumnDef("CCB_CATEGORY",   ColType.MULTI,       false, "CCB Category", "MULTILIST06", 251737028L, true),
        new ColumnDef("CCB_CUSTOMER",   ColType.MULTI,       false, "CCB Customer", "MULTILIST07", 251736797L, true),
        new ColumnDef("CCB_PROGRAM",    ColType.MULTI,       false, "CCB Program",  "MULTILIST05", 251740480L, true),
        new ColumnDef("PLANNING_GROUP", ColType.CATEGORICAL, false, "Group",        "LIST19",      20254L,     false)
    ));

    private static final Map<String, ColumnDef> COLUMNS_BY_NAME;
    static {
        Map<String, ColumnDef> m = new LinkedHashMap<>();
        for (ColumnDef c : COLUMNS) m.put(c.name, c);
        COLUMNS_BY_NAME = Collections.unmodifiableMap(m);
    }

    /** Hard cap on screen results. Export bypasses this. */
    public static final int SCREEN_ROW_CAP = 1000;
    /** Safety cap on export as well — 778K rows is too many for a single sheet. */
    public static final int EXPORT_ROW_CAP = 100_000;
    /** IN-list batch size for the derived-column resolution query. */
    private static final int DERIVED_BATCH = 1000;

    /** One condition row coming in from the UI. */
    public static final class Condition {
        public String connector;   // "AND" | "OR" — ignored on first row
        public String column;
        public String operator;    // see operators per type below
        public String value;       // single-value ops (string, contains, ...) or comma-list (in_list) or ISO date
        public List<String> values;// preferred for in_list (checkbox dropdown)
    }

    /** Operators per column type. Server enforces — anything else → 400. */
    private static final Map<ColType, Set<String>> OPS_BY_TYPE;
    static {
        Map<ColType, Set<String>> m = new HashMap<>();
        m.put(ColType.STRING, new LinkedHashSet<>(Arrays.asList(
            "eq", "neq", "contains", "starts_with", "ends_with", "is_empty", "is_not_empty")));
        m.put(ColType.CATEGORICAL, new LinkedHashSet<>(Arrays.asList(
            "eq", "neq", "in_list", "is_empty", "is_not_empty")));
        m.put(ColType.DATE, new LinkedHashSet<>(Arrays.asList(
            "on_or_before", "on_or_after", "between", "is_empty", "is_not_empty")));
        m.put(ColType.MULTI, new LinkedHashSet<>(Arrays.asList(
            "contains", "is_empty", "is_not_empty")));
        OPS_BY_TYPE = Collections.unmodifiableMap(m);
    }

    public static Set<String> opsFor(ColType t) {
        return OPS_BY_TYPE.getOrDefault(t, Collections.emptySet());
    }

    public static final class CompiledSql {
        public String sql;
        public List<Object> params = new ArrayList<>();
    }

    /** Build a WHERE clause from the conditions. Public for unit tests. */
    public CompiledSql compileWhere(List<Condition> conditions) {
        CompiledSql out = new CompiledSql();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < conditions.size(); i++) {
            Condition c = conditions.get(i);
            ColumnDef col = COLUMNS_BY_NAME.get(c.column);
            if (col == null) throw new IllegalArgumentException("Unknown column: " + c.column);
            if (col.derived) throw new IllegalArgumentException("Column not filterable: " + col.name);
            String op = c.operator == null ? "" : c.operator.toLowerCase().trim();
            if (!opsFor(col.type).contains(op)) {
                throw new IllegalArgumentException("Operator '" + op + "' not valid for column " + col.name);
            }
            String frag = fragmentFor(col, op, c, out.params);
            if (frag == null) continue;
            if (first) {
                sb.append(frag);
                first = false;
            } else {
                String conn = "OR".equalsIgnoreCase(c.connector) ? " OR " : " AND ";
                sb.append(conn).append(frag);
            }
        }
        out.sql = sb.toString();
        return out;
    }

    /** Compose one column predicate. Adds bind values to {@code params}. */
    private String fragmentFor(ColumnDef col, String op, Condition c, List<Object> params) {
        switch (op) {
            case "is_empty":     return col.name + " IS NULL";
            case "is_not_empty": return col.name + " IS NOT NULL";
            default: break;
        }
        switch (col.type) {
            case STRING:
            case CATEGORICAL: {
                switch (op) {
                    case "eq":          params.add(c.value); return "UPPER(" + col.name + ") = UPPER(?)";
                    case "neq":         params.add(c.value); return "UPPER(" + col.name + ") <> UPPER(?)";
                    case "contains":    params.add("%" + c.value + "%"); return "UPPER(" + col.name + ") LIKE UPPER(?)";
                    case "starts_with": params.add(c.value + "%"); return "UPPER(" + col.name + ") LIKE UPPER(?)";
                    case "ends_with":   params.add("%" + c.value); return "UPPER(" + col.name + ") LIKE UPPER(?)";
                    case "in_list":
                        List<String> vs = c.values;
                        if (vs == null || vs.isEmpty()) return null;
                        StringBuilder qs = new StringBuilder();
                        for (int i = 0; i < vs.size(); i++) {
                            if (i > 0) qs.append(",");
                            qs.append("?");
                            params.add(vs.get(i));
                        }
                        return col.name + " IN (" + qs + ")";
                    default: throw new IllegalArgumentException("Bad op " + op + " for " + col.name);
                }
            }
            case MULTI: {
                // SUBCONTRACTORS — comma-separated, e.g. 'C002,R010,C039'. Use
                // LIKE on the column wrapped in commas so partial matches don't
                // false-positive ('C00' shouldn't match 'C002').
                if ("contains".equals(op)) {
                    params.add("%," + c.value + ",%");
                    return "(',' || " + col.name + " || ',') LIKE ?";
                }
                throw new IllegalArgumentException("Bad op " + op + " for MULTI " + col.name);
            }
            case DATE: {
                String subExpr = dateSubstrExpr(col.name);
                switch (op) {
                    case "on_or_before":
                        params.add(c.value);
                        return col.name + " IS NOT NULL AND " + subExpr + " <= TO_DATE(?, 'YYYY-MM-DD')";
                    case "on_or_after":
                        params.add(c.value);
                        return col.name + " IS NOT NULL AND " + subExpr + " >= TO_DATE(?, 'YYYY-MM-DD')";
                    case "between":
                        // value comes as "YYYY-MM-DD,YYYY-MM-DD" or values=[start,end]
                        String[] range = parseDateRange(c);
                        params.add(range[0]);
                        params.add(range[1]);
                        return col.name + " IS NOT NULL AND " + subExpr
                             + " BETWEEN TO_DATE(?, 'YYYY-MM-DD') AND TO_DATE(?, 'YYYY-MM-DD')";
                    default: throw new IllegalArgumentException("Bad op " + op + " for DATE " + col.name);
                }
            }
        }
        return null;
    }

    /** TO_DATE wrapper per column. Each VARCHAR date column uses a different
     *  literal format — getting this wrong silently breaks queries. */
    private static String dateSubstrExpr(String col) {
        switch (col) {
            case "REV_RELEASE_DATE":
            case "RELEASE_DATE":
                return "TO_DATE(SUBSTR(" + col + ", 1, 11), 'DD-MON-YYYY')";
            case "CREATE_DATE":
                return "TO_DATE(SUBSTR(" + col + ", 1, 10), 'MM-DD-YYYY')";
            default:
                throw new IllegalArgumentException("Not a date column: " + col);
        }
    }

    private static String[] parseDateRange(Condition c) {
        if (c.values != null && c.values.size() == 2) {
            return new String[]{ c.values.get(0), c.values.get(1) };
        }
        if (c.value != null && c.value.contains(",")) {
            String[] parts = c.value.split(",", 2);
            return new String[]{ parts[0].trim(), parts[1].trim() };
        }
        throw new IllegalArgumentException("between requires two dates");
    }

    /** Distinct values for a categorical column (powers the checkbox UI).
     *  Allow-list enforced — caller can't probe arbitrary identifiers. */
    public List<String> distinctValues(String column) {
        ColumnDef col = COLUMNS_BY_NAME.get(column);
        if (col == null) throw new IllegalArgumentException("Unknown column: " + column);
        if (col.derived) throw new IllegalArgumentException("No distinct values for derived column: " + column);
        if (col.type != ColType.CATEGORICAL) {
            throw new IllegalArgumentException("Not a categorical column: " + column);
        }
        String sql = "SELECT DISTINCT " + col.name + " AS v FROM item_extract WHERE "
                   + col.name + " IS NOT NULL ORDER BY 1";
        List<String> out = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(20);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString("v");
                    if (v != null && !v.trim().isEmpty()) out.add(v.trim());
                }
            }
        } catch (SQLException e) {
            LOG.warning("[ITEMS-SEARCH] distinct " + col.name + " failed: " + e.getMessage());
        }
        return out;
    }

    public static final class RunResult {
        public List<Map<String, Object>> rows = new ArrayList<>();
        public List<String> columns = new ArrayList<>();
        public int matchedCount;        // total matches (uncapped)
        public boolean truncated;
        public long elapsedMs;
        public String errorMessage;
    }

    /** Run the search. Returns up to {@link #SCREEN_ROW_CAP} rows plus a
     *  total-count probe so the UI can display "Showing 1,000 of N". */
    public RunResult run(List<Condition> conditions, List<String> selectedColumns) {
        long t0 = System.currentTimeMillis();
        RunResult r = new RunResult();
        // Default columns if user didn't pass any
        List<String> cols = (selectedColumns == null || selectedColumns.isEmpty())
                ? defaultResultColumns(conditions) : sanitizeColumns(selectedColumns);
        r.columns.addAll(cols);

        // Split into physical (item_extract) vs derived (Agile) columns.
        List<ColumnDef> derivedCols = derivedOf(cols);
        List<String> fetchCols = physicalFetchColumns(cols, !derivedCols.isEmpty());

        CompiledSql where;
        try {
            where = compileWhere(conditions);
        } catch (IllegalArgumentException iae) {
            r.errorMessage = iae.getMessage();
            r.elapsedMs = System.currentTimeMillis() - t0;
            return r;
        }

        // 1) Total count (cheap-ish — no fetch, just COUNT)
        String countSql = "SELECT COUNT(*) FROM item_extract" + whereSuffix(where);
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bind(ps, where.params);
            ps.setQueryTimeout(60);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) r.matchedCount = rs.getInt(1);
            }
        } catch (SQLException e) {
            r.errorMessage = "Query failed: " + e.getMessage();
            r.elapsedMs = System.currentTimeMillis() - t0;
            return r;
        }

        // 2) Capped fetch of the physical columns
        String selectSql = "SELECT " + String.join(", ", fetchCols)
                + " FROM item_extract" + whereSuffix(where)
                + " FETCH FIRST " + SCREEN_ROW_CAP + " ROWS ONLY";
        List<Map<String, String>> raw = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            bind(ps, where.params);
            ps.setQueryTimeout(120);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String c : fetchCols) row.put(c, rs.getString(c));
                    raw.add(row);
                }
            }
        } catch (SQLException e) {
            r.errorMessage = "Fetch failed: " + e.getMessage();
            r.elapsedMs = System.currentTimeMillis() - t0;
            return r;
        }

        // 3) Resolve derived (Agile) columns for the fetched part numbers, merge.
        Map<String, Map<String, String>> derivedVals = Collections.emptyMap();
        if (!derivedCols.isEmpty()) {
            List<String> pns = new ArrayList<>();
            for (Map<String, String> row : raw) pns.add(row.get("PART_NUMBER"));
            derivedVals = resolveDerived(pns, derivedCols);
        }
        for (Map<String, String> fr : raw) {
            r.rows.add(assembleRow(cols, fr, derivedVals));
        }

        r.truncated = r.matchedCount > r.rows.size();
        r.elapsedMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** Stream the full match set to Excel (up to EXPORT_ROW_CAP). */
    public void writeExcel(OutputStream out, List<Condition> conditions, List<String> selectedColumns)
            throws IOException {
        List<String> cols = (selectedColumns == null || selectedColumns.isEmpty())
                ? defaultResultColumns(conditions) : sanitizeColumns(selectedColumns);
        CompiledSql where = compileWhere(conditions);

        List<ColumnDef> derivedCols = derivedOf(cols);
        List<String> fetchCols = physicalFetchColumns(cols, !derivedCols.isEmpty());

        // Derived columns can't be joined in the item_extract query (different
        // schema / no grant), and SXSSF is write-forward, so pre-resolve them:
        // first collect the match set's part numbers, then batch-resolve on the
        // agile datasource into a lookup map before streaming.
        Map<String, Map<String, String>> derivedVals = Collections.emptyMap();
        if (!derivedCols.isEmpty()) {
            List<String> pns = collectPartNumbers(where);
            derivedVals = resolveDerived(pns, derivedCols);
        }
        final Map<String, Map<String, String>> dv = derivedVals;

        String sql = "SELECT " + String.join(", ", fetchCols)
                + " FROM item_extract" + whereSuffix(where)
                + " FETCH FIRST " + EXPORT_ROW_CAP + " ROWS ONLY";

        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 500, false, false)) {
            Sheet sheet = wb.createSheet("Items Search");
            CellStyle hdr = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); hf.setColor(IndexedColors.WHITE.getIndex());
            hdr.setFont(hf);
            hdr.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            hdr.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(labelFor(cols.get(i)));
                c.setCellStyle(hdr);
            }

            try (Connection conn = customDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, where.params);
                ps.setQueryTimeout(600);
                ps.setFetchSize(500);
                try (ResultSet rs = ps.executeQuery()) {
                    int rowIdx = 1;
                    while (rs.next()) {
                        Map<String, String> fr = new HashMap<>();
                        for (String c : fetchCols) fr.put(c, rs.getString(c));
                        String pn = fr.get("PART_NUMBER");
                        Map<String, String> dvals = dv.get(pn);
                        Row r = sheet.createRow(rowIdx++);
                        for (int i = 0; i < cols.size(); i++) {
                            ColumnDef d = COLUMNS_BY_NAME.get(cols.get(i));
                            String val = (d != null && d.derived)
                                    ? (dvals == null ? null : dvals.get(d.name))
                                    : fr.get(cols.get(i));
                            r.createCell(i).setCellValue(nvl(val));
                        }
                    }
                }
            } catch (SQLException e) {
                LOG.warning("[ITEMS-SEARCH] export SQL failed: " + e.getMessage());
                throw new IOException("Export query failed: " + e.getMessage(), e);
            }
            wb.write(out);
            wb.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Derived (Agile) column resolution — PT-95
    // ------------------------------------------------------------------

    /** Derived ColumnDefs among the selected column names, in selection order. */
    private static List<ColumnDef> derivedOf(List<String> cols) {
        List<ColumnDef> out = new ArrayList<>();
        for (String c : cols) {
            ColumnDef d = COLUMNS_BY_NAME.get(c);
            if (d != null && d.derived) out.add(d);
        }
        return out;
    }

    /** Physical columns to fetch from item_extract. PART_NUMBER is always
     *  included when derived columns are present so the merge has a key. */
    private static List<String> physicalFetchColumns(List<String> cols, boolean needKey) {
        List<String> out = new ArrayList<>();
        for (String c : cols) {
            ColumnDef d = COLUMNS_BY_NAME.get(c);
            if (d == null || !d.derived) out.add(c);
        }
        if (needKey && !out.contains("PART_NUMBER")) out.add(0, "PART_NUMBER");
        if (out.isEmpty()) out.add("PART_NUMBER");
        return out;
    }

    /** Assemble one output row in the requested column order, pulling physical
     *  values from the item_extract row and derived values from the Agile map. */
    private static Map<String, Object> assembleRow(List<String> cols, Map<String, String> physical,
                                                   Map<String, Map<String, String>> derivedVals) {
        Map<String, Object> row = new LinkedHashMap<>();
        String pn = physical.get("PART_NUMBER");
        Map<String, String> dvals = derivedVals.get(pn);
        for (String c : cols) {
            ColumnDef d = COLUMNS_BY_NAME.get(c);
            if (d != null && d.derived) {
                row.put(c, dvals == null ? null : dvals.get(c));
            } else {
                row.put(c, physical.get(c));
            }
        }
        return row;
    }

    /** Collect the match set's part numbers (capped) for pre-resolving derived
     *  columns on export. */
    private List<String> collectPartNumbers(CompiledSql where) {
        String sql = "SELECT PART_NUMBER FROM item_extract" + whereSuffix(where)
                + " FETCH FIRST " + EXPORT_ROW_CAP + " ROWS ONLY";
        List<String> out = new ArrayList<>();
        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, where.params);
            ps.setQueryTimeout(120);
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.warning("[ITEMS-SEARCH] part-number pass failed: " + e.getMessage());
        }
        return out;
    }

    /** Resolve derived (Agile PAGE_TWO list) columns for a set of part numbers.
     *  Runs on the agile datasource in batches of {@link #DERIVED_BATCH}. Returns
     *  a map partNumber → (columnName → resolved value). Fail-soft: on error the
     *  affected cells are simply left blank. */
    private Map<String, Map<String, String>> resolveDerived(List<String> partNumbers, List<ColumnDef> derivedCols) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (partNumbers == null || partNumbers.isEmpty() || derivedCols.isEmpty()) return out;

        // Distinct, non-null part numbers.
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String p : partNumbers) if (p != null && !p.isEmpty()) distinct.add(p);
        List<String> pns = new ArrayList<>(distinct);

        StringBuilder sel = new StringBuilder("SELECT i.ITEM_NUMBER");
        for (ColumnDef d : derivedCols) {
            sel.append(", ").append(derivedSelectExpr(d)).append(" AS ").append(d.name);
        }
        String base = sel + " FROM agile.ITEM i "
                + "JOIN agile.PAGE_TWO p2 ON p2.ID = i.ID AND p2.CLASS = i.CLASS "
                + "WHERE i.ITEM_NUMBER IN (";

        for (int start = 0; start < pns.size(); start += DERIVED_BATCH) {
            List<String> batch = pns.subList(start, Math.min(start + DERIVED_BATCH, pns.size()));
            StringBuilder qs = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) { if (i > 0) qs.append(','); qs.append('?'); }
            String sql = base + qs + ")";
            try (Connection conn = agileDataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                ps.setQueryTimeout(180);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pn = rs.getString("ITEM_NUMBER");
                        Map<String, String> m = out.computeIfAbsent(pn, k -> new HashMap<>());
                        for (ColumnDef d : derivedCols) m.put(d.name, rs.getString(d.name));
                    }
                }
            } catch (SQLException e) {
                LOG.warning("[ITEMS-SEARCH] derived resolve batch failed (" + batch.size()
                        + " parts): " + e.getMessage());
                // fail-soft — leave this batch's cells blank
            }
        }
        return out;
    }

    /** SQL expression (relative to the {@code p2} PAGE_TWO alias) that resolves a
     *  derived list column to its label text. Multi-value columns LISTAGG the
     *  comma-wrapped LISTENTRY ids; single-value columns MAX the one entry. */
    private static String derivedSelectExpr(ColumnDef d) {
        if (d.agileMulti) {
            // ON OVERFLOW TRUNCATE (Oracle 12.2+/19c) guards the 4000-char LISTAGG
            // cap so an unusually long program/customer list can't fail the query.
            return "(SELECT LISTAGG(le.ENTRYVALUE, '; ' ON OVERFLOW TRUNCATE) WITHIN GROUP (ORDER BY le.ENTRYVALUE) "
                 + "FROM agile.LISTENTRY le WHERE le.PARENTID = " + d.agileListParent + " AND le.LANGID = 0 "
                 + "AND INSTR(p2." + d.agileCol + ", ',' || le.ENTRYID || ',') > 0)";
        }
        return "(SELECT MAX(le.ENTRYVALUE) FROM agile.LISTENTRY le "
             + "WHERE le.PARENTID = " + d.agileListParent + " AND le.LANGID = 0 "
             + "AND le.ENTRYID = p2." + d.agileCol + ")";
    }

    /** Default screen columns: PT-86 (Jimmy Sessumes 2026-06-11) expanded the
     *  starter set from the bare 5 (Part/Desc/Rev/Status/Lifecycle) to 12 so
     *  the most-asked-for fields show up without users having to pop the
     *  "More fields…" picker every time. Order matches Jimmy's listing.
     *  Any column referenced in a condition is auto-pinned so the user sees
     *  what filtered each row. */
    public List<String> defaultResultColumns(List<Condition> conditions) {
        LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(
            "PART_NUMBER", "DESCRIPTION", "REV", "STATUSCODE", "LIFECYCLE_PHASE",
            "SUBCONTRACTORS", "ACTUAL_BUILD_PLANT", "CREATE_DATE", "PM",
            "MATERIAL_TYPE", "MATERIAL_GROUP", "PRODUCTLINE"));
        if (conditions != null) {
            for (Condition c : conditions) {
                if (c.column != null && COLUMNS_BY_NAME.containsKey(c.column)) set.add(c.column);
            }
        }
        return new ArrayList<>(set);
    }

    private List<String> sanitizeColumns(List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String c : cols) {
            if (COLUMNS_BY_NAME.containsKey(c)) out.add(c);
        }
        if (out.isEmpty()) out.add("PART_NUMBER");
        return out;
    }

    private static String whereSuffix(CompiledSql w) {
        return (w.sql == null || w.sql.isEmpty()) ? "" : " WHERE " + w.sql;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    public static String labelFor(String col) {
        ColumnDef d = COLUMNS_BY_NAME.get(col);
        return d == null ? col : d.label;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
