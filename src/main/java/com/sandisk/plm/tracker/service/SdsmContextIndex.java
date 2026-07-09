package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SdsmAttachment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import static com.sandisk.plm.tracker.service.SdsmConstants.*;

/**
 * Operator-first ("Find by Station / Product") index of every in-scope SDSM item.
 *
 * <p>The Shop-Floor Docs tab's primary entry mode lets the operator pick their
 * Spec / Step Code, Product Group, and Product instead of the doc number — that
 * matches Camstar's mental model. To answer "give me every controlled doc that
 * matches this (spec, productGroup, product)" we maintain a small in-memory
 * inverted index of every Document and Part that survives the SDSM gates.
 *
 * <p>Index shape:
 * <ul>
 *   <li>{@link #items} — itemId → {@link IndexedItem} with all the resolved
 *       attributes + attachment summary.</li>
 *   <li>{@link #specToItems}, {@link #productGroupToItems}, {@link #productToItems}
 *       — inverted lists keyed by display string.</li>
 *   <li>{@link #specs}, {@link #productGroups}, {@link #products}
 *       — sorted dropdown universe (just the keysets of the inverted indexes).</li>
 * </ul>
 *
 * <p>Lazy-loaded on first access. Rebuild ~4-hour TTL by default; admin can
 * force-refresh via {@link #invalidate()}.
 *
 * <p>"ALL" handling: when a doc has Product Group = "ALL" or Product = "ALL", it
 * applies to every PG / Product the operator might pick. The matching logic
 * unions the operator's selection with "ALL" before intersecting.
 */
@Service
public class SdsmContextIndex {

    private static final Logger LOG = Logger.getLogger(SdsmContextIndex.class.getName());

    /** ITEM.ID for the singleton "ALL" product item. Probed against agprod 2026-05-14. */
    public static final String ALL_LITERAL = "ALL";

    /** TTL for the cached index. After this, the next read triggers a rebuild. */
    private static final long INDEX_TTL_MS = 4L * 60 * 60 * 1000;  // 4 hours

    @Autowired private DataSource dataSource;
    @Autowired private SdsmLookups lookups;
    @Autowired private SdsmFileService fileService;  // unused here but reserved for batched attachment caching

    public static class IndexedItem {
        public long itemId;
        public String itemNumber;
        public String description;
        public String docStyle;       // e.g. WI / VIC / Drawing / Offline VAR
        public String itemType;       // subclass display name
        public String lifecyclePhase;
        public String rev;
        public long changeId;
        public String changeNumber;
        public String source;         // "document" or "part"
        public Set<String> specs        = Collections.emptySet();
        public Set<String> productGroups = Collections.emptySet();
        public Set<String> products      = Collections.emptySet();
    }

    private volatile Map<Long, IndexedItem> items = Collections.emptyMap();
    private volatile Map<String, Set<Long>> specToItems         = Collections.emptyMap();
    private volatile Map<String, Set<Long>> productGroupToItems = Collections.emptyMap();
    private volatile Map<String, Set<Long>> productToItems      = Collections.emptyMap();
    private volatile List<String> specs         = Collections.emptyList();
    private volatile List<String> productGroups = Collections.emptyList();
    private volatile List<String> products      = Collections.emptyList();
    private volatile long lastBuiltAt = 0L;
    private volatile long lastBuildMs = 0L;
    private final Object buildLock = new Object();

    public void invalidate() {
        synchronized (buildLock) { lastBuiltAt = 0L; }
    }

    /**
     * Kick off the first index build on a background thread at JVM startup.
     * The build takes ~60s against agprod (10K docs + 600 parts × ~30 specs avg
     * resolved via in-memory LISTENTRY map) — too slow for an inline HTTP call.
     * By warming on startup, the dropdowns are ready by the time the operator
     * opens the tab. Failures are swallowed (logged) so a transient agprod
     * outage doesn't crash app startup; the next dropdown call will retry.
     */
    @PostConstruct
    void prewarm() {
        Thread t = new Thread(() -> {
            try {
                LOG.info("[SDSM-IDX] background pre-warm starting…");
                ensureFresh();
            } catch (Throwable e) {
                LOG.warning("[SDSM-IDX] pre-warm failed (will retry on first dropdown call): " + e.getMessage());
            }
        }, "sdsm-context-prewarm");
        t.setDaemon(true);
        t.start();
    }

    public List<String> getSpecs()         { ensureFresh(); return specs; }
    public List<String> getProductGroups() { ensureFresh(); return productGroups; }
    public List<String> getProducts()      { ensureFresh(); return products; }
    public long getLastBuiltAt()           { return lastBuiltAt; }
    public long getLastBuildMs()           { return lastBuildMs; }
    public int getItemCount()              { return items.size(); }

    /**
     * Find every in-scope item whose attributes match the operator's context.
     * Spec match is exact. Product Group / Product match is "exact OR ALL"
     * (i.e. a doc tagged with Product Group = ALL applies to any operator-picked PG).
     * Style filter is null/empty = any.
     */
    public List<IndexedItem> findByContext(String spec, String productGroup, String product, Set<String> styleFilter) {
        ensureFresh();
        if (spec == null || spec.trim().isEmpty()) return Collections.emptyList();
        if (productGroup == null || productGroup.trim().isEmpty()) return Collections.emptyList();
        if (product == null || product.trim().isEmpty()) return Collections.emptyList();

        Set<Long> bySpec = specToItems.getOrDefault(spec.trim(), Collections.emptySet());
        Set<Long> byPg   = unionWithAll(productGroupToItems, productGroup.trim());
        Set<Long> byProd = unionWithAll(productToItems, product.trim());

        Set<Long> intersection = new HashSet<>(bySpec);
        intersection.retainAll(byPg);
        intersection.retainAll(byProd);

        List<IndexedItem> out = new ArrayList<>();
        for (Long id : intersection) {
            IndexedItem it = items.get(id);
            if (it == null) continue;
            if (styleFilter != null && !styleFilter.isEmpty() && !styleFilter.contains(it.docStyle)) continue;
            out.add(it);
        }
        // Stable order: by docStyle then itemNumber
        out.sort(Comparator
            .comparing((IndexedItem x) -> x.docStyle == null ? "" : x.docStyle)
            .thenComparing(x -> x.itemNumber == null ? "" : x.itemNumber));
        return out;
    }

    public static class Options {
        public List<String> specs         = Collections.emptyList();
        public List<String> productGroups = Collections.emptyList();
        public List<String> products      = Collections.emptyList();
        /** docStyle → count of items still matching after applying all filters EXCEPT the style filter. */
        public Map<String, Integer> styleCounts = Collections.emptyMap();
        public int totalCandidates;
    }

    /**
     * Cascading-filter source. Returns the available values for each axis given
     * the current selections — the basis for "narrow the dropdowns + grey out
     * impossible style chips" UX. Each axis is computed by intersecting the
     * sets for the OTHER three axes (so the user's typed-in value on axis X
     * doesn't restrict the universe of axis X).
     */
    public Options options(String spec, String productGroup, String product, Set<String> styleFilter) {
        ensureFresh();
        Options out = new Options();

        // Pre-compute the per-axis candidate sets (or full universe = null sentinel).
        Set<Long> bySpec    = (spec         == null) ? null : specToItems.getOrDefault(spec, Collections.emptySet());
        Set<Long> byPg      = (productGroup == null) ? null : unionWithAll(productGroupToItems, productGroup);
        Set<Long> byProduct = (product      == null) ? null : unionWithAll(productToItems, product);

        // Axis-specific intersections — "everyone except me."
        Set<Long> forSpecAxis    = intersect(byPg, byProduct);
        Set<Long> forPgAxis      = intersect(bySpec, byProduct);
        Set<Long> forProductAxis = intersect(bySpec, byPg);
        Set<Long> forStyleAxis   = intersect(bySpec, byPg, byProduct);

        out.specs         = collectAxisValues(forSpecAxis,    it -> it.specs,         styleFilter);
        out.productGroups = collectAxisValues(forPgAxis,      it -> it.productGroups, styleFilter);
        out.products      = collectAxisValues(forProductAxis, it -> it.products,      styleFilter);

        // Style counts — one count per docStyle of items still matching. The
        // style filter is intentionally NOT applied here (the user can only
        // SEE the style chip count if it's NOT yet filtering by that style).
        Map<String, Integer> sc = new TreeMap<>();
        for (String s : DOC_STYLE_WHITELIST) sc.put(s, 0);
        Iterable<Long> ids = (forStyleAxis == null) ? items.keySet() : forStyleAxis;
        for (Long id : ids) {
            IndexedItem it = items.get(id);
            if (it == null) continue;
            sc.merge(it.docStyle, 1, Integer::sum);
        }
        out.styleCounts = sc;

        // Total candidates = intersection of all four filters (this drives the
        // "would my search return anything?" preview before the user clicks).
        Set<Long> all = (forStyleAxis == null) ? items.keySet() : forStyleAxis;
        if (styleFilter == null || styleFilter.isEmpty()) {
            out.totalCandidates = all.size();
        } else {
            int n = 0;
            for (Long id : all) {
                IndexedItem it = items.get(id);
                if (it != null && styleFilter.contains(it.docStyle)) n++;
            }
            out.totalCandidates = n;
        }
        return out;
    }

    /** Project an item-id set onto an axis (e.g. spec values), filtered by docStyle. */
    private List<String> collectAxisValues(Set<Long> candidates,
                                            java.util.function.Function<IndexedItem, Set<String>> axis,
                                            Set<String> styleFilter) {
        Iterable<Long> ids = (candidates == null) ? items.keySet() : candidates;
        Set<String> out = new TreeSet<>();
        for (Long id : ids) {
            IndexedItem it = items.get(id);
            if (it == null) continue;
            if (styleFilter != null && !styleFilter.isEmpty() && !styleFilter.contains(it.docStyle)) continue;
            for (String v : axis.apply(it)) out.add(v);
        }
        return new ArrayList<>(out);
    }

    /** Intersection that treats null operands as "full universe" (no filter on that axis). */
    private static Set<Long> intersect(Set<Long>... sets) {
        Set<Long> result = null;
        for (Set<Long> s : sets) {
            if (s == null) continue;
            if (result == null) result = new HashSet<>(s);
            else result.retainAll(s);
            if (result.isEmpty()) return result;
        }
        return result;  // null = no filters applied = full universe
    }

    private Set<Long> unionWithAll(Map<String, Set<Long>> map, String key) {
        Set<Long> a = map.getOrDefault(key, Collections.emptySet());
        Set<Long> b = map.getOrDefault(ALL_LITERAL, Collections.emptySet());
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        Set<Long> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    // -----------------------------------------------------------------------
    //  Index build
    // -----------------------------------------------------------------------

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (lastBuiltAt > 0 && (now - lastBuiltAt) < INDEX_TTL_MS) return;
        synchronized (buildLock) {
            if (lastBuiltAt > 0 && (now - lastBuiltAt) < INDEX_TTL_MS) return;
            try { build(); }
            catch (SQLException e) {
                LOG.warning("[SDSM-IDX] build failed: " + e.getMessage());
                throw new RuntimeException("SDSM context index build failed: " + e.getMessage(), e);
            }
        }
    }

    private void build() throws SQLException {
        long t0 = System.currentTimeMillis();
        lookups.warmUp();

        // Stage 1 — Documents pass: every Document with non-null DocStyle + latest implemented DCO
        Map<Long, IndexedItem> built = new HashMap<>();
        loadDocuments(built);
        loadDocumentFlex(built);
        loadParts(built);   // Parts class — Document Style + PG/Spec/Product all on PAGE_TWO multilists

        // Drop any item that didn't end up with a docStyle in the whitelist (e.g. "Drawing"
        // is a value but "PCB Layout" isn't — keeps the index tight).
        built.entrySet().removeIf(e -> !DOC_STYLE_WHITELIST.contains(e.getValue().docStyle));
        // Also drop items that have zero specs — operator-mode can't surface them
        // (they'd never appear in a search).
        built.entrySet().removeIf(e -> e.getValue().specs.isEmpty());

        // Stage 2 — invert
        Map<String, Set<Long>> specMap = new HashMap<>();
        Map<String, Set<Long>> pgMap   = new HashMap<>();
        Map<String, Set<Long>> prMap   = new HashMap<>();
        for (IndexedItem it : built.values()) {
            for (String s : it.specs)         specMap.computeIfAbsent(s, k -> new HashSet<>()).add(it.itemId);
            for (String s : it.productGroups) pgMap.computeIfAbsent(s,   k -> new HashSet<>()).add(it.itemId);
            for (String s : it.products)      prMap.computeIfAbsent(s,   k -> new HashSet<>()).add(it.itemId);
        }

        List<String> specList = new ArrayList<>(specMap.keySet()); Collections.sort(specList);
        List<String> pgList   = new ArrayList<>(pgMap.keySet());   Collections.sort(pgList);
        List<String> prList   = new ArrayList<>(prMap.keySet());   Collections.sort(prList);

        // Atomic publish — readers see the prior view until this assignment lands.
        this.items                = Collections.unmodifiableMap(built);
        this.specToItems          = Collections.unmodifiableMap(specMap);
        this.productGroupToItems  = Collections.unmodifiableMap(pgMap);
        this.productToItems       = Collections.unmodifiableMap(prMap);
        this.specs                = Collections.unmodifiableList(specList);
        this.productGroups        = Collections.unmodifiableList(pgList);
        this.products             = Collections.unmodifiableList(prList);
        this.lastBuildMs          = System.currentTimeMillis() - t0;
        this.lastBuiltAt          = System.currentTimeMillis();

        LOG.info(String.format(
            "[SDSM-IDX] built in %d ms — %d items (specs=%d, productGroups=%d, products=%d)",
            lastBuildMs, items.size(), specs.size(), productGroups.size(), products.size()));
    }

    private void loadDocuments(Map<Long, IndexedItem> out) throws SQLException {
        // One ROW_NUMBER pass to get every Document + its latest implemented DCO
        // (string-based status/subclass match for cross-NODETABLE-id stability).
        String sql =
            "SELECT * FROM (" +
            "  SELECT i.ID itemId, i.ITEM_NUMBER num, i.DESCRIPTION descr, " +
            "         i.SUBCLASS subclassId, p3.LIST36 docStyleEntryId, " +
            "         r.REV_NUMBER revNumber, r.RELEASE_TYPE releaseType, " +
            "         c.ID changeId, c.CHANGE_NUMBER changeNumber, " +
            "         ROW_NUMBER() OVER (PARTITION BY i.ID ORDER BY r.RELEASE_DATE DESC) rn " +
            "    FROM agile.ITEM i " +
            "    JOIN agile.PAGE_THREE p3 ON p3.ID = i.ID AND p3.CLASS = i.CLASS " +
            "    JOIN agile.REV r  ON r.ITEM = i.ID AND r.SITE = 0 " +
            "    JOIN agile.CHANGE c ON c.ID = r.CHANGE " +
            "   WHERE i.CLASS = " + CLASS_DOCUMENTS +
            "     AND NVL(i.DELETE_FLAG, 0) != 1 " +
            "     AND p3.LIST36 IS NOT NULL " +
            "     AND c.STATUS   IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'Implemented') " +
            "     AND c.SUBCLASS IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'DCO') " +
            ") WHERE rn = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(2000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexedItem it = new IndexedItem();
                    it.itemId        = rs.getLong("itemId");
                    it.itemNumber    = rs.getString("num");
                    it.description   = rs.getString("descr");
                    it.docStyle      = lookups.resolveListEntry(rs.getLong("docStyleEntryId"));
                    it.itemType      = lookups.resolveSubclassName(rs.getLong("subclassId"));
                    it.lifecyclePhase = lookups.resolveLifecycle(rs.getLong("releaseType"));
                    it.rev           = rs.getString("revNumber");
                    it.changeId      = rs.getLong("changeId");
                    it.changeNumber  = rs.getString("changeNumber");
                    it.source        = "document";
                    out.put(it.itemId, it);
                }
            }
        }
    }

    private void loadDocumentFlex(Map<Long, IndexedItem> out) throws SQLException {
        // Pull every relevant AGILE_FLEX row in one pass; resolve text in memory.
        String sql =
            "SELECT ID, ATTID, TEXT FROM agile.AGILE_FLEX " +
            " WHERE CLASS = " + CLASS_DOCUMENTS +
            "   AND ATTID IN (" + ATTID_DOC_PRODUCT_GROUP + ", " +
                                  ATTID_DOC_PRODUCT       + ", " +
                                  ATTID_DOC_SPEC          + ")";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("ID");
                    IndexedItem it = out.get(id);
                    if (it == null) continue;
                    long attid = rs.getLong("ATTID");
                    String csv = rs.getString("TEXT");
                    if (attid == ATTID_DOC_PRODUCT_GROUP) {
                        it.productGroups = new HashSet<>(lookups.resolveCsvListEntries(csv));
                    } else if (attid == ATTID_DOC_SPEC) {
                        it.specs = new HashSet<>(lookups.resolveCsvListEntries(csv));
                    } else if (attid == ATTID_DOC_PRODUCT) {
                        // Product is a CSV of ITEM.IDs. Resolve to ITEM_NUMBER strings
                        // so the operator-facing dropdown shows readable values.
                        List<Long> ids = lookups.parseCsvLongs(csv);
                        if (!ids.isEmpty()) it.products = resolveItemNumbers(ids);
                    }
                }
            }
        }
    }

    private void loadParts(Map<Long, IndexedItem> out) throws SQLException {
        String sql =
            "SELECT * FROM (" +
            "  SELECT i.ID itemId, i.ITEM_NUMBER num, i.DESCRIPTION descr, " +
            "         i.SUBCLASS subclassId, " +
            "         p2.LIST72 docStyleEntryId, " +
            "         p2.MULTILIST11 productGroupCsv, " +
            "         p2.MULTILIST09 productCsv, " +
            "         p2.MULTILIST10 specCsv, " +
            "         r.REV_NUMBER revNumber, r.RELEASE_TYPE releaseType, " +
            "         c.ID changeId, c.CHANGE_NUMBER changeNumber, " +
            "         ROW_NUMBER() OVER (PARTITION BY i.ID ORDER BY r.RELEASE_DATE DESC) rn " +
            "    FROM agile.ITEM i " +
            "    JOIN agile.PAGE_TWO p2 ON p2.ID = i.ID AND p2.CLASS = i.CLASS " +
            "    JOIN agile.REV r ON r.ITEM = i.ID AND r.SITE = 0 " +
            "    JOIN agile.CHANGE c ON c.ID = r.CHANGE " +
            "   WHERE i.CLASS = " + CLASS_ITEMS +
            "     AND i.SUBCLASS != " + SUBCLASS_SKU +
            "     AND NVL(i.DELETE_FLAG, 0) != 1 " +
            "     AND p2.LIST72       IS NOT NULL " +
            "     AND p2.MULTILIST11  IS NOT NULL " +
            "     AND c.STATUS   IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'Implemented') " +
            "     AND c.SUBCLASS IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'ECO') " +
            ") WHERE rn = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexedItem it = new IndexedItem();
                    it.itemId         = rs.getLong("itemId");
                    it.itemNumber     = rs.getString("num");
                    it.description    = rs.getString("descr");
                    it.docStyle       = lookups.resolveListEntry(rs.getLong("docStyleEntryId"));
                    it.itemType       = lookups.resolveSubclassName(rs.getLong("subclassId"));
                    it.lifecyclePhase = lookups.resolveLifecycle(rs.getLong("releaseType"));
                    it.rev            = rs.getString("revNumber");
                    it.changeId       = rs.getLong("changeId");
                    it.changeNumber   = rs.getString("changeNumber");
                    it.source         = "part";
                    it.productGroups  = new HashSet<>(lookups.resolveCsvListEntries(rs.getString("productGroupCsv")));
                    it.specs          = new HashSet<>(lookups.resolveCsvListEntries(rs.getString("specCsv")));
                    List<Long> productIds = lookups.parseCsvLongs(rs.getString("productCsv"));
                    if (!productIds.isEmpty()) it.products = resolveItemNumbers(productIds);
                    out.put(it.itemId, it);
                }
            }
        }
    }

    /**
     * Bulk-resolve ITEM.IDs to ITEM_NUMBERs for the Product field. Returns a
     * Set of strings; unresolved ids are silently dropped (better than returning
     * "542680927" as a literal product name).
     */
    private Set<String> resolveItemNumbers(List<Long> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) return Collections.emptySet();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) { if (i > 0) in.append(','); in.append(ids.get(i)); }
        Set<String> out = new HashSet<>(ids.size());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT ITEM_NUMBER FROM agile.ITEM WHERE ID IN (" + in + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String n = rs.getString(1);
                if (n != null && !n.trim().isEmpty()) out.add(n.trim());
            }
        }
        return out;
    }

    /**
     * Convert an {@link IndexedItem} into a fully-attributed {@link SdsmAttachment}
     * row by attaching the actual file metadata for the latest change. Used by
     * the controller after {@link #findByContext} narrows the candidate set.
     */
    public List<SdsmAttachment> hydrateAttachments(List<IndexedItem> items) throws SQLException {
        List<SdsmAttachment> out = new ArrayList<>();
        if (items == null || items.isEmpty()) return out;

        // Build a (itemId, changeId) pair list and pull all attachments in one query.
        StringBuilder pairs = new StringBuilder();
        for (IndexedItem it : items) {
            if (pairs.length() > 0) pairs.append(',');
            pairs.append('(').append(it.itemId).append(',').append(it.changeId).append(')');
        }

        // attachments key = itemId:changeId → list of file rows
        Map<String, List<AttRow>> byKey = new HashMap<>();
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
            while (rs.next()) {
                AttRow a = new AttRow();
                long iid = rs.getLong("itemId");
                long cid = rs.getLong("changeId");
                a.attachId  = rs.getLong("attachId");
                a.fileId    = rs.getLong("fileId");
                a.fileName  = rs.getString("fileName");
                a.fileType  = rs.getString("fileType");
                a.fileSize  = rs.getLong("fileSize");
                a.vaultPath = rs.getString("vaultPath");
                byKey.computeIfAbsent(iid + ":" + cid, k -> new ArrayList<>()).add(a);
            }
        }

        for (IndexedItem it : items) {
            List<AttRow> atts = byKey.get(it.itemId + ":" + it.changeId);
            if (atts == null) continue;
            List<String> sortedSpecs = new ArrayList<>(it.specs);
            Collections.sort(sortedSpecs);
            String pg  = String.join(", ", sorted(it.productGroups));
            String prd = String.join(", ", sorted(it.products));
            for (AttRow a : atts) {
                if (!"pdf".equalsIgnoreCase(a.fileType)) continue;
                if (a.fileName != null && a.fileName.contains("FAI")) continue;
                if (a.fileSize <= 0) continue;
                SdsmAttachment rec = new SdsmAttachment();
                rec.source            = it.source;
                rec.parentNumber      = it.itemNumber;
                rec.parentDescription = it.description;
                rec.documentStyle     = it.docStyle;
                rec.itemType          = it.itemType;
                rec.lifecyclePhase    = it.lifecyclePhase;
                rec.rev               = it.rev;
                rec.changeNumber      = it.changeNumber;
                rec.productGroup      = pg;
                rec.product           = prd;
                rec.specs             = sortedSpecs;
                rec.attachId          = a.attachId;
                rec.fileId            = a.fileId;
                rec.fileName          = a.fileName;
                rec.fileType          = a.fileType;
                rec.fileSize          = a.fileSize;
                rec.vaultPath         = a.vaultPath;
                out.add(rec);
            }
        }
        return out;
    }

    private static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        return l;
    }

    private static class AttRow {
        long attachId, fileId, fileSize;
        String fileName, fileType, vaultPath;
    }
}
