package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.AgentDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

/**
 * In-memory index of Agile documents that have attachments, serving the Agent
 * API document endpoints (filter/search, aggregate/facet, fetch-by-number).
 *
 * <p>Loaded from a JSON seed ({@code app.documents.index.file}, default
 * {@code ./data/documents-index.json}) produced by
 * {@code data/agent-docs/build_document_index.py}. The corpus is small
 * (~10K docs) so all filtering/faceting is done in memory. Fail-soft: a missing
 * or unreadable file leaves an empty index (endpoints return empty results and
 * report {@code loaded=false}); it never blocks startup. Call {@link #reload()}
 * to pick up a refreshed file without a restart.
 */
@Service
public class DocumentIndexService {

    private static final Logger LOG = Logger.getLogger(DocumentIndexService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Filter key (agent-facing) -> which AgentDocument field it matches. */
    private static final Map<String, java.util.function.Function<AgentDocument, String>> FIELD = new LinkedHashMap<>();
    static {
        FIELD.put("lifecycle",      d -> d.lifecyclePhase);
        FIELD.put("style",          d -> d.style);
        FIELD.put("function",       d -> d.function);
        FIELD.put("classification", d -> d.classification);
        FIELD.put("owner",          d -> d.owners);
        FIELD.put("product",        d -> d.product);
        FIELD.put("type",           d -> d.documentType);
        FIELD.put("rev",            d -> d.rev);
        FIELD.put("number",         d -> d.number);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IndexFile {
        public String generatedAt;
        public String source;
        public int count;
        public List<AgentDocument> documents = new ArrayList<>();
    }

    public static class SearchResult {
        public final int total;              // distinct docs matched
        public final int page;
        public final int size;
        public final List<AgentDocument> documents; // page slice
        public SearchResult(int total, int page, int size, List<AgentDocument> documents) {
            this.total = total; this.page = page; this.size = size; this.documents = documents;
        }
    }

    private final String indexFile;
    private final DataSource agileDataSource;   // null in unit tests / when DB unavailable
    private volatile List<AgentDocument> docs = Collections.emptyList();
    private volatile Map<String, AgentDocument> byNumber = Collections.emptyMap();
    private volatile String generatedAt = "";
    private volatile boolean loaded = false;

    @Value("${app.scheduling.disabled:false}") private boolean schedulingDisabled;

    @Autowired
    public DocumentIndexService(@Value("${app.documents.index.file:./data/documents-index.json}") String indexFile,
                                @Qualifier("dataSource") DataSource agileDataSource) {
        this.indexFile = indexFile;
        this.agileDataSource = agileDataSource;
    }

    /** Test constructor — no datasource (refreshFromDb unavailable). */
    public DocumentIndexService(String indexFile) {
        this(indexFile, null);
    }

    @PostConstruct
    public void reload() {
        try {
            File f = new File(indexFile);
            if (!f.isFile()) {
                LOG.warning("[DOCS] document index file not found: " + f.getAbsolutePath() + " — index empty");
                installDocs(new ArrayList<>(), "");
                this.loaded = false;
                return;
            }
            IndexFile idx = MAPPER.readValue(f, IndexFile.class);
            List<AgentDocument> list = idx.documents == null ? new ArrayList<>() : idx.documents;
            installDocs(list, idx.generatedAt == null ? "" : idx.generatedAt);
            LOG.info("[DOCS] loaded " + list.size() + " documents from " + f.getAbsolutePath()
                    + " (generatedAt=" + generatedAt + ")");
        } catch (Exception e) {
            LOG.warning("[DOCS] failed to load document index: " + e + " — index empty");
            installDocs(new ArrayList<>(), "");
            this.loaded = false;
        }
    }

    /** Swap the in-memory index atomically. */
    private void installDocs(List<AgentDocument> list, String genAt) {
        Map<String, AgentDocument> map = new HashMap<>(Math.max(16, list.size() * 2));
        for (AgentDocument d : list) if (d.number != null) map.put(d.number.toUpperCase(), d);
        this.docs = list;
        this.byNumber = map;
        this.generatedAt = genAt;
        this.loaded = true;
    }

    public boolean isLoaded() { return loaded; }
    public int size() { return docs.size(); }
    public String generatedAt() { return generatedAt; }
    public Set<String> filterKeys() { return FIELD.keySet(); }

    public AgentDocument get(String number) {
        if (number == null) return null;
        return byNumber.get(number.trim().toUpperCase());
    }

    /** Filtered, free-text-searched, paged document search (deduped by Number). */
    public SearchResult search(Map<String, String> filters, String q, int page, int size) {
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        List<AgentDocument> matched = new ArrayList<>();
        for (AgentDocument d : docs) {
            if (!matchesFilters(d, filters)) continue;
            if (!needle.isEmpty() && !matchesText(d, needle)) continue;
            matched.add(d);
        }
        int total = matched.size();
        int from = Math.max(0, page) * size;
        int to = Math.min(total, from + size);
        List<AgentDocument> slice = (from >= total) ? Collections.emptyList() : matched.subList(from, to);
        return new SearchResult(total, Math.max(0, page), size, new ArrayList<>(slice));
    }

    /** Facet counts: distinct documents per value of groupBy field, after filters + q. */
    public Map<String, Object> aggregate(String groupBy, Map<String, String> filters, String q) {
        java.util.function.Function<AgentDocument, String> accessor = FIELD.get(normalizeKey(groupBy));
        if (accessor == null) {
            throw new IllegalArgumentException("groupBy must be one of " + FIELD.keySet() + " (got: " + groupBy + ")");
        }
        String needle = (q == null) ? "" : q.trim().toLowerCase();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (AgentDocument d : docs) {
            if (!matchesFilters(d, filters)) continue;
            if (!needle.isEmpty() && !matchesText(d, needle)) continue;
            String v = accessor.apply(d);
            v = (v == null || v.isEmpty()) ? "(blank)" : v;
            counts.merge(v, 1, Integer::sum);
            total++;
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", e.getKey());
                    m.put("count", e.getValue());
                    buckets.add(m);
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupBy", normalizeKey(groupBy));
        out.put("matchedDocuments", total);
        out.put("buckets", buckets);
        return out;
    }

    private boolean matchesFilters(AgentDocument d, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Map.Entry<String, String> e : filters.entrySet()) {
            String key = normalizeKey(e.getKey());
            String val = e.getValue();
            if (val == null || val.trim().isEmpty()) continue;
            val = val.trim();
            // Reserved date-range keys (inclusive; dates are YYYY-MM-DD so string compare = chronological).
            switch (key) {
                case "createdfrom":  if (!dateGe(d.createDate, val))      return false; continue;
                case "createdto":    if (!dateLe(d.createDate, val))      return false; continue;
                case "releasedfrom": if (!dateGe(d.revReleaseDate, val))  return false; continue;
                case "releasedto":   if (!dateLe(d.revReleaseDate, val))  return false; continue;
                default: break;
            }
            java.util.function.Function<AgentDocument, String> acc = FIELD.get(key);
            if (acc == null) continue; // ignore unknown filter keys
            String field = acc.apply(d);
            if (field == null || !field.equalsIgnoreCase(val)) return false;
        }
        return true;
    }

    /** date (YYYY-MM-DD, first 10 chars) >= bound. Missing date fails the filter. */
    private static boolean dateGe(String date, String bound) {
        if (date == null || date.length() < 10) return false;
        return date.substring(0, 10).compareTo(bound) >= 0;
    }
    private static boolean dateLe(String date, String bound) {
        if (date == null || date.length() < 10) return false;
        return date.substring(0, 10).compareTo(bound) <= 0;
    }

    private boolean matchesText(AgentDocument d, String needleLower) {
        if (d.description != null && d.description.toLowerCase().contains(needleLower)) return true;
        if (d.number != null && d.number.toLowerCase().contains(needleLower)) return true;
        if (d.attachments != null) {
            for (AgentDocument.Attachment a : d.attachments) {
                if (a.fileName != null && a.fileName.toLowerCase().contains(needleLower)) return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String k) {
        return k == null ? "" : k.trim().toLowerCase();
    }

    // ==================================================================
    //  Live refresh from the Agile DB (replaces the manual xlsx→python seed)
    //  SQL validated against agile_prod 2026-07-10 (reproduces the export:
    //  9,567 docs, columns match cell-for-cell). Attribute/attachment mappings:
    //    Style=PAGE_THREE.LIST36 (list 251746283) · Classification=LIST38 (251747780)
    //    Function/Sub=LIST32 (251733300) + PARENT_ENTRY walk · Owners=AGILE_FLEX 251733512
    //    Product=AGILE_FLEX 251747721 · Attachments=ATTACHMENT_FULL_MAP.PARENT_ID
    //  page_three has occasional duplicate rows (one populated, one blank) — the
    //  Java merge below keeps the populated one (mergeDocRow).
    // ==================================================================

    private static final String DOCS_SQL =
        // Owners/Product resolved SET-BASED in CTEs (own/prod), computed once for the whole
        // doc set — the earlier per-row correlated LISTAGG subqueries timed out at 240s on the
        // full 9.5K-doc run. This version returns in seconds.
        "WITH doc AS (SELECT it.id FROM agile.item it WHERE it.subclass=9141 " +
        "  AND EXISTS(SELECT 1 FROM agile.attachment_full_map a WHERE a.parent_id=it.id)), " +
        "own AS (SELECT fx.id item_id, LISTAGG(au.last_name||', '||au.first_name||' ('||au.loginid||')','; ') " +
        "        WITHIN GROUP (ORDER BY au.last_name) owners " +
        "  FROM agile.agile_flex fx JOIN doc ON doc.id=fx.id " +
        "  CROSS JOIN (SELECT LEVEL lvl FROM dual CONNECT BY LEVEL<=50) lv " +
        "  JOIN agile.agileuser au ON au.id=TO_NUMBER(TRIM(REGEXP_SUBSTR(fx.text,'[^,]+',1,lv.lvl))) " +
        "  WHERE fx.attid=251733512 AND REGEXP_SUBSTR(fx.text,'[^,]+',1,lv.lvl) IS NOT NULL GROUP BY fx.id), " +
        "prod AS (SELECT fx.id item_id, LISTAGG(pi.item_number,'; ') WITHIN GROUP (ORDER BY pi.item_number) product " +
        "  FROM agile.agile_flex fx JOIN doc ON doc.id=fx.id " +
        "  CROSS JOIN (SELECT LEVEL lvl FROM dual CONNECT BY LEVEL<=50) lv " +
        "  JOIN agile.item pi ON pi.id=TO_NUMBER(TRIM(REGEXP_SUBSTR(fx.text,'[^,]+',1,lv.lvl))) " +
        "  WHERE fx.attid=251747721 AND REGEXP_SUBSTR(fx.text,'[^,]+',1,lv.lvl) IS NOT NULL GROUP BY fx.id) " +
        "SELECT i.item_number, i.description, c.rev_number, " +
        "  TO_CHAR(c.release_date,'YYYY-MM-DD') AS rev_release_date, " +
        "  TO_CHAR(i.created,'YYYY-MM-DD') AS create_date, " +
        "  COALESCE(lc.description,'Preliminary') AS lifecycle, COALESCE(sub.name,'Document') AS doc_type, " +
        "  styl.entryvalue AS style, cls.entryvalue AS classification, " +
        "  CASE WHEN fnc.entryid IS NULL THEN NULL WHEN fnp.entryvalue IS NULL THEN fnc.entryvalue " +
        "       ELSE fnp.entryvalue||'|'||fnc.entryvalue END AS function_sub, " +
        "  own.owners AS owners, prod.product AS product " +
        "FROM agile.item i JOIN doc ON doc.id=i.id " +
        "JOIN agile.rev c ON c.id = (SELECT r2.id FROM agile.rev r2 WHERE r2.item=i.id " +
        "     ORDER BY (CASE WHEN r2.release_date IS NULL THEN 0 ELSE 1 END) DESC, r2.release_date DESC, r2.id DESC " +
        "     FETCH FIRST 1 ROW ONLY) " +
        "LEFT JOIN agile.nodetable lc ON lc.id=c.release_type " +
        "LEFT JOIN agile.nodetable sub ON sub.id=i.subclass " +
        "LEFT JOIN agile.page_three p3 ON p3.id=i.id " +
        "LEFT JOIN agile.listentry styl ON styl.entryid=p3.list36 AND styl.langid=0 AND styl.parentid=251746283 " +
        "LEFT JOIN agile.listentry cls  ON cls.entryid=p3.list38  AND cls.langid=0 AND cls.parentid=251747780 " +
        "LEFT JOIN agile.listentry fnc  ON fnc.entryid=p3.list32  AND fnc.langid=0 AND fnc.parentid=251733300 " +
        "LEFT JOIN agile.listentry fnp  ON fnp.entryid=fnc.parent_entry AND fnp.langid=0 " +
        "LEFT JOIN own ON own.item_id=i.id " +
        "LEFT JOIN prod ON prod.item_id=i.id";

    private static final String ATTACH_SQL =
        "SELECT i.item_number, afm.FILEFILENAME AS filename " +
        "FROM agile.item i JOIN agile.attachment_full_map afm ON afm.parent_id=i.id " +
        "WHERE i.subclass=9141 AND afm.FILEFILENAME IS NOT NULL";

    public boolean canRefreshFromDb() { return agileDataSource != null; }

    /** Rebuild the index live from the Agile DB and persist it to the index file.
     *  Returns the document count. Synchronized so concurrent triggers don't overlap. */
    public synchronized int refreshFromDb() {
        if (agileDataSource == null) throw new IllegalStateException("Agile datasource not available");
        long t0 = System.currentTimeMillis();
        JdbcTemplate jt = new JdbcTemplate(agileDataSource);
        jt.setQueryTimeout(240);

        Map<String, AgentDocument> map = new LinkedHashMap<>(16384);
        jt.query(DOCS_SQL, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> mergeDocRow(map, rowToDoc(rs)));

        Map<String, Set<String>> seen = new HashMap<>(16384);
        jt.query(ATTACH_SQL, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String num = rs.getString("item_number");
            String fn = rs.getString("filename");
            if (num == null || fn == null) return;
            AgentDocument d = map.get(num.toUpperCase());
            if (d == null) return;
            Set<String> s = seen.computeIfAbsent(num.toUpperCase(), k -> new HashSet<>());
            if (s.add(fn)) {
                AgentDocument.Attachment a = new AgentDocument.Attachment();
                a.fileName = fn;
                d.attachments.add(a);
            }
        });

        List<AgentDocument> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing(x -> x.number == null ? "" : x.number));
        String genAt = LocalDate.now().toString();
        installDocs(list, genAt);
        writeIndexFile(list, genAt);
        LOG.info("[DOCS] refreshFromDb loaded " + list.size() + " documents in "
                + (System.currentTimeMillis() - t0) + "ms (generatedAt=" + genAt + ")");
        return list.size();
    }

    private static AgentDocument rowToDoc(ResultSet rs) throws java.sql.SQLException {
        AgentDocument d = new AgentDocument();
        d.number = rs.getString("item_number");
        d.description = rs.getString("description");
        d.rev = rs.getString("rev_number");
        d.revReleaseDate = rs.getString("rev_release_date");
        d.createDate = rs.getString("create_date");
        d.lifecyclePhase = rs.getString("lifecycle");
        d.documentType = rs.getString("doc_type");
        d.style = rs.getString("style");
        d.classification = rs.getString("classification");
        d.function = rs.getString("function_sub");
        d.owners = rs.getString("owners");
        d.product = rs.getString("product");
        return d;
    }

    /** Merge a doc row keyed by number; if a duplicate arrives (page_three can have a
     *  populated + a blank row for the same item) keep the one with attributes populated. */
    static void mergeDocRow(Map<String, AgentDocument> map, AgentDocument d) {
        if (d.number == null) return;
        String key = d.number.toUpperCase();
        AgentDocument existing = map.get(key);
        if (existing == null) { map.put(key, d); return; }
        // prefer the row that actually carries attributes
        if (isBlank(existing.style) && isBlank(existing.classification) && isBlank(existing.function)
                && (!isBlank(d.style) || !isBlank(d.classification) || !isBlank(d.function))) {
            d.attachments = existing.attachments; // keep any attachments already collected
            map.put(key, d);
        }
    }

    private void writeIndexFile(List<AgentDocument> list, String genAt) {
        try {
            IndexFile idx = new IndexFile();
            idx.generatedAt = genAt;
            idx.source = "agile_prod refresh";
            idx.count = list.size();
            idx.documents = list;
            File f = new File(indexFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            MAPPER.writeValue(f, idx);
        } catch (Exception e) {
            LOG.warning("[DOCS] refreshFromDb: could not persist index file " + indexFile + ": " + e
                    + " (in-memory index still updated)");
        }
    }

    /** Nightly self-refresh (skipped when scheduling is disabled or no DB). */
    @Scheduled(cron = "${app.documents.refresh.cron:0 30 3 * * *}")
    public void scheduledRefresh() {
        if (schedulingDisabled || agileDataSource == null) return;
        try {
            refreshFromDb();
        } catch (Exception e) {
            LOG.warning("[DOCS] scheduled refresh failed: " + e);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
