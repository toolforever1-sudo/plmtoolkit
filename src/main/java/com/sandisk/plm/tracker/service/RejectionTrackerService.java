package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Backs the Returns Tracker view inside the ECN Report tab.
 *
 * Reads the JSON files produced by {@code rejection_tracker.py}:
 *   - rejection-cache.json     append-only list of categorized rejection events
 *   - rejection-narrative.json AI-generated narrative + anomalies, keyed by window
 *   - rejection-recipients.json email distribution list (separate from the existing ECN report list)
 *
 * Aggregations are computed on demand from the cache. The cache is loaded once at
 * startup and re-loaded when the file's mtime advances (i.e. after the Python job
 * appends new events).
 */
@Service
public class RejectionTrackerService {

    private static final Logger logger = Logger.getLogger(RejectionTrackerService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    private EcnReportService ecnReportService;

    @org.springframework.beans.factory.annotation.Autowired
    private KpiClassificationService kpiClassifications;

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String CACHE_FILE = DATA_DIR + "/rejection-cache.json";
    private static final String NARRATIVE_FILE = DATA_DIR + "/rejection-narrative.json";
    private static final String RECIPIENTS_FILE = DATA_DIR + "/rejection-recipients.json";

    private static final List<String> SEED_RECIPIENTS = Arrays.asList(
        "jimmy.sessumes@sandisk.com",
        "noraida.nazri@sandisk.com",
        "kathy.ashe@sandisk.com",
        "andy.kuver@sandisk.com",
        "aiyappa.cheppudira@sandisk.com",
        "vikas.jindal@sandisk.com"
    );

    /**
     * Active classification taxonomy (PT-61, May 13, 2026 — final per Jimmy + Noraida).
     * <ul>
     *   <li>{@code Returned by Owner} — auto-detected when the ECN creator returns
     *       their own change to Pending. Same concept as the legacy
     *       "Returned By Requestor" category, just renamed.</li>
     *   <li>{@code Incomplete Documentation} (code: <b>ID</b>) — missing required
     *       artifacts (MDDS, specs, etc.).</li>
     *   <li>{@code Insufficient Information} (code: <b>II</b>) — unclear or missing
     *       required information.</li>
     *   <li>{@code Wrong Information} (code: <b>WI</b>) — provided info doesn't
     *       support the change or conflicts with process requirements.</li>
     *   <li>{@code Duplicate Request} (code: <b>DR</b>) — already addressed by
     *       another ECN.</li>
     *   <li>{@code Return Requested} (code: <b>RR</b>) — analyst returned at the
     *       requestor's request (e.g. requestor wants to pull back the change).</li>
     * </ul>
     * The convention is: the audit comment must START with the full label or its
     * short code, followed by a colon (e.g. {@code "WI: Input PN doesn't exist"}).
     * Case-insensitive match. {@link #CODE_TO_CATEGORY} below holds the mapping.
     *
     * <p>{@code Unknown} stays as a final fallback for events whose comment
     * doesn't match any code AND whose AI-supplied category isn't in the active
     * list (e.g. legacy {@code Ambiguous Request} from the pre-PT-61 taxonomy).
     */
    public static final List<String> CATEGORIES = Arrays.asList(
        "Returned by Owner",
        "Incomplete Documentation",
        "Insufficient Information",
        "Wrong Information",
        "Duplicate Request",
        "Return Requested",
        "Unknown"
    );

    /** Audit view has no AI fallback — uncoded returns bucket here. */
    public static final String NO_AUDIT_CODE = "No audit code";

    /** Category seed order for the audit-enforced view (adds the No-audit-code bucket). */
    public static final List<String> AUDIT_CATEGORY_ORDER = Arrays.asList(
        "Returned by Owner",
        "Incomplete Documentation",
        "Insufficient Information",
        "Wrong Information",
        "Duplicate Request",
        "Return Requested",
        NO_AUDIT_CODE
    );

    /**
     * PT-94 (Jimmy, 2026-06-12): Product Line → Team fallback map for ECNs
     * that aren't yet in {@code ecn_data.json} (i.e. very fresh ECNs that
     * landed after the most recent ECN Dashboard run). Keep in sync with
     * {@code TEAM_MAP} in {@code ecn_report_generator.py}. The Python script
     * also emits this map into {@code ecn_data.json#teamMap} so the JSON is
     * the long-term source of truth; this static copy is the immediate
     * fallback when the JSON-side map isn't present or the lookup misses.
     *
     * <p>Order of team-resolution precedence (see {@link #resolveProductTeam}):
     * <ol>
     *   <li>{@code teamOverride} annotation on the ECN (admin-set)</li>
     *   <li>{@code productTeam} pre-resolved on the ECN row in {@code ecn_data.json}</li>
     *   <li>This map, keyed by the first segment of the event's Product Line string</li>
     * </ol>
     */
    public static final Map<String, String> PRODUCT_LINE_TEAM_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1000 - CompactFlash Products", "CS");
        m.put("1003 - FlashChip Products", "AME");
        m.put("1006 - Accessories & Adapters", "CS");
        m.put("1007 - Other Products", "Engineering");
        m.put("1009 - Readers & Writers", "CS");
        m.put("1013 - Secure Digital Products", "CS");
        m.put("1022 - USB Drives", "CS");
        m.put("1023 - MP3 Player", "CS");
        m.put("1024 - MicroSD", "CS");
        m.put("1025 - iNAND", "AME");
        m.put("1032 - Client - SATA", "CSSD");
        m.put("1035 - Apple NAND", "AME");
        m.put("1037 - Client - PCIe", "CSSD");
        m.put("1040 - Enterprise - SAS", "ESSD");
        m.put("1044 - Enterprise - PCIe", "ESSD");
        m.put("1047 - Apple Mobile Embed", "AME");
        m.put("1049 - Client External - SATA", "CS");
        m.put("1050 - Dual Drive", "CS");
        m.put("1051 - iXpand", "CS");
        m.put("1054 - Enterprise Components", "AME");
        m.put("2001 - Service and Support", "CS");
        m.put("9000 - Mfg Eng CIP", "Test Equipment");
        m.put("9001 - EVB ENGG", "AME");
        m.put("9700 - Quads", "CS");
        m.put("9701 - Wafer Purchase/Die Parts", "AME");
        m.put("9703 - Tested Memory Assemblies", "AME");
        m.put("9705 - Remnant Die", "AME");
        m.put("9706 - Generic Tested Reclaim", "CS");
        m.put("9997 - Customer Owned Material", "Factory (SDSS/SM)");
        m.put("9998 - Common Purchased Parts", "CS");
        m.put("9999 - Expensed Purchased Parts", "Factory (SDSS/SM)");
        PRODUCT_LINE_TEAM_MAP = Collections.unmodifiableMap(m);
    }

    /** Comment-prefix → canonical category. Both the code (e.g. "WI") and the
     *  full label (e.g. "Wrong Information") are recognized; the lookup is
     *  case-insensitive (keys are lowercased). */
    public static final Map<String, String> CODE_TO_CATEGORY;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Incomplete Documentation");
        m.put("incomplete documentation", "Incomplete Documentation");
        m.put("ii", "Insufficient Information");
        m.put("insufficient information", "Insufficient Information");
        m.put("wi", "Wrong Information");
        m.put("wrong information", "Wrong Information");
        m.put("dr", "Duplicate Request");
        m.put("duplicate request", "Duplicate Request");
        m.put("rr", "Return Requested");
        m.put("return requested", "Return Requested");
        CODE_TO_CATEGORY = Collections.unmodifiableMap(m);
    }

    /** Legacy → active category aliases. Pre-PT-61 events stored "Returned By
     *  Requestor" (lowercase 'b') and "Ambiguous Request" — both are remapped on
     *  read so the dashboard categories stay consistent without requiring a
     *  Python re-categorization pass. */
    private static final Map<String, String> LEGACY_CATEGORY_ALIAS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("Returned By Requestor", "Returned by Owner");
        m.put("Ambiguous Request",     "Unknown");  // dropped; map to Unknown
        LEGACY_CATEGORY_ALIAS = Collections.unmodifiableMap(m);
    }

    private long cacheMtime = 0L;
    private Map<String, Map<String, Object>> events = new LinkedHashMap<>();
    private String lastRunTs;
    private String cacheModel;

    @PostConstruct
    public void init() {
        new File(DATA_DIR).mkdirs();
        if (!new File(RECIPIENTS_FILE).exists()) {
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("recipients", SEED_RECIPIENTS);
            saveJson(RECIPIENTS_FILE, seed);
            logger.info("[REJECT-TRACKER] Seeded recipients list with " + SEED_RECIPIENTS.size() + " addresses");
        }
        loadCacheIfStale();
    }

    // =========================================================================
    // Cache loading
    // =========================================================================

    @SuppressWarnings("unchecked")
    private synchronized void loadCacheIfStale() {
        File f = new File(CACHE_FILE);
        if (!f.exists()) {
            events = new LinkedHashMap<>();
            cacheMtime = 0L;
            return;
        }
        long m = f.lastModified();
        if (m == cacheMtime) return;
        try {
            Map<String, Object> top = mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
            this.lastRunTs = (String) top.get("lastRunTs");
            this.cacheModel = (String) top.get("model");
            Object ev = top.get("events");
            if (ev instanceof Map) {
                this.events = (Map<String, Map<String, Object>>) ev;
            } else {
                this.events = new LinkedHashMap<>();
            }
            cacheMtime = m;
            logger.info("[REJECT-TRACKER] Loaded cache: " + this.events.size() + " events, model=" + this.cacheModel);
        } catch (Exception e) {
            logger.warning("[REJECT-TRACKER] Failed to load cache: " + e.getMessage());
        }
    }

    // =========================================================================
    // Public read API
    // =========================================================================

    public boolean hasData() {
        loadCacheIfStale();
        return !events.isEmpty();
    }

    public String getLastRunTs() {
        loadCacheIfStale();
        return lastRunTs;
    }

    /** Min/max event day present in the cache, as {min,max}; null when empty. */
    public LocalDate[] getEventDateSpan() {
        loadCacheIfStale();
        LocalDate min = null, max = null;
        for (Map<String, Object> e : events.values()) {
            String ts = (String) e.get("ts");
            if (ts == null || ts.length() < 10) continue;
            LocalDate d = LocalDate.parse(ts.substring(0, 10));
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        return min == null ? null : new LocalDate[]{min, max};
    }

    /**
     * Filter events to the given inclusive date range, enrich each one with the
     * PT-61 fields (productTeam, changeType, comment-prefix re-classification),
     * and return sorted descending by timestamp.
     */
    public List<Map<String, Object>> getEventsInRange(LocalDate from, LocalDate to) {
        loadCacheIfStale();
        String fromIso = from.toString();
        String toIso = to.toString();
        Map<String, Object> annotations = ecnReportService != null
            ? ecnReportService.getAnnotations() : Collections.emptyMap();
        Map<String, Map<String, Object>> ecnLookup = ecnReportService != null
            ? ecnReportService.getEcnLookup() : Collections.emptyMap();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events.values()) {
            String ts = (String) e.get("ts");
            if (ts == null) continue;
            String day = ts.substring(0, 10);
            if (day.compareTo(fromIso) >= 0 && day.compareTo(toIso) <= 0) {
                out.add(enrichEvent(e, annotations, ecnLookup));
            }
        }
        out.sort((a, b) -> ((String) b.get("ts")).compareTo((String) a.get("ts")));
        return out;
    }

    /**
     * Returns a copy of the event with the PT-61 derived fields filled in:
     * <ul>
     *   <li>{@code category} — re-classified from the comment prefix when the
     *       analyst followed the {@code "WI: ..."} convention; legacy values
     *       (Returned By Requestor / Ambiguous Request) are aliased to the new
     *       taxonomy; otherwise the AI-supplied category is kept if it's still
     *       in the active list.</li>
     *   <li>{@code productTeam} — from the ECN's {@code teamOverride} annotation
     *       (admin-set on the Cycle Time data table) or the first product line
     *       as a fallback.</li>
     *   <li>{@code changeType} — read from the cached ECN row (same value the
     *       Cycle Time KPI panel shows).</li>
     * </ul>
     * The original cached event is NOT mutated — we always copy.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichEvent(Map<String, Object> e,
                                            Map<String, Object> annotations,
                                            Map<String, Map<String, Object>> ecnLookup) {
        Map<String, Object> out = new LinkedHashMap<>(e);
        String comment = (String) e.get("comment");
        String aiCategory = (String) e.get("category");

        // 1) Comment-prefix wins (the new audit-enforced convention).
        String resolved = classifyByCommentPrefix(comment);

        // 2) Legacy alias (e.g. "Returned By Requestor" → "Returned by Owner").
        if (resolved == null && aiCategory != null) {
            String aliased = LEGACY_CATEGORY_ALIAS.get(aiCategory);
            if (aliased != null) resolved = aliased;
        }

        // 3) AI-supplied category if it's still in the active list.
        if (resolved == null && aiCategory != null && CATEGORIES.contains(aiCategory)) {
            resolved = aiCategory;
        }

        // 4) Final fallback.
        if (resolved == null) resolved = "Unknown";
        out.put("category", resolved);

        // AI-vs-audit split (Jul 2026 — Jimmy/Noraida). Persist both classifications
        // and the authoritative source alongside the existing default `category`.
        out.put("aiCategory", aiView(aiCategory));
        out.put("auditCategory", auditView(comment, aiCategory));
        out.put("categorySource", categorySource(comment, aiCategory));

        // Resolve productTeam + changeType from the ECN annotations / lookup.
        String ecnNum = (String) e.get("ecnNumber");
        String productLine = (String) e.get("productLine");
        out.put("productTeam", resolveProductTeam(ecnNum, productLine, annotations, ecnLookup));
        out.put("changeType", resolveChangeType(e, ecnNum, ecnLookup));
        return out;
    }

    /** Parse {@code "WI: ..."} / {@code "Wrong Information: ..."} prefixes.
     *  Returns null when the comment doesn't match any known code or label. */
    static String classifyByCommentPrefix(String comment) {
        if (comment == null) return null;
        int colon = comment.indexOf(':');
        if (colon <= 0) return null;
        String prefix = comment.substring(0, colon).trim().toLowerCase();
        if (prefix.isEmpty()) return null;
        return CODE_TO_CATEGORY.get(prefix);
    }

    /** AI-inferred view: alias legacy names, keep active categories, else Unknown. */
    public static String aiView(String rawAiCategory) {
        if (rawAiCategory == null) return "Unknown";
        String aliased = LEGACY_CATEGORY_ALIAS.getOrDefault(rawAiCategory, rawAiCategory);
        return CATEGORIES.contains(aliased) ? aliased : "Unknown";
    }

    /** Audit-enforced view: comment prefix wins; else structural owner-return; else no code. */
    public static String auditView(String comment, String rawAiCategory) {
        String code = classifyByCommentPrefix(comment);
        if (code != null) return code;
        if ("Returned by Owner".equals(aiView(rawAiCategory))) return "Returned by Owner";
        return NO_AUDIT_CODE;
    }

    /** Which source is authoritative for an event: audit | owner | ai | unknown. */
    public static String categorySource(String comment, String rawAiCategory) {
        if (classifyByCommentPrefix(comment) != null) return "audit";
        String av = aiView(rawAiCategory);
        if ("Returned by Owner".equals(av)) return "owner";
        return "Unknown".equals(av) ? "unknown" : "ai";
    }

    @SuppressWarnings("unchecked")
    private String resolveProductTeam(String ecnNum, String productLine,
                                      Map<String, Object> annotations,
                                      Map<String, Map<String, Object>> ecnLookup) {
        // PT-90 + PT-94: 3-tier fallback so the Returns Tracker's Product Team
        // column matches what the Cycle Time tab shows AND stays populated for
        // very fresh ECNs (the ones at the top of the events list — the ones
        // users are most likely to be looking at — that landed after the most
        // recent ECN Dashboard run and therefore aren't in ecn_data.json yet).
        //
        // Order of precedence:
        //   1. teamOverride annotation (admin override on the Cycle Time data table)
        //   2. productTeam from ecn_data.json (Python TEAM_MAP lookup at report-gen time)
        //   3. PRODUCT_LINE_TEAM_MAP (static fallback — Python TEAM_MAP mirrored
        //      in Java, applied to the event's own Product Line string when the
        //      ECN isn't in ecn_data.json yet)
        //   4. Empty → bucketed as "Unknown" by the caller

        // (1) Admin override on the cycle-time data table.
        if (ecnNum != null) {
            Object ann = annotations.get(ecnNum);
            if (ann instanceof Map) {
                Object t = ((Map<String, Object>) ann).get("teamOverride");
                if (t != null) {
                    String s = t.toString().trim();
                    if (!s.isEmpty()) return s;
                }
            }

            // (2) ecn_data.json's productTeam — same source the Cycle Time tab uses.
            Map<String, Object> row = ecnLookup.get(ecnNum);
            if (row != null) {
                Object pt = row.get("productTeam");
                if (pt != null) {
                    String s = pt.toString().trim();
                    if (!s.isEmpty()) return s;
                }
            }
        }

        // (3) Resolve via PRODUCT_LINE_TEAM_MAP applied to the event's own
        // Product Line string. Mirrors Python's resolve_team(): splits on ";"
        // (multi-PL ECNs), looks each segment up in the team map, joins the
        // distinct results with "; " in sorted order. ECNs with no mapped PL
        // segment fall through to step 4 below.
        if (productLine != null && !productLine.isEmpty()) {
            java.util.TreeSet<String> teams = new java.util.TreeSet<>();
            for (String seg : productLine.split(";")) {
                String key = seg.trim();
                if (key.isEmpty()) continue;
                String t = PRODUCT_LINE_TEAM_MAP.get(key);
                if (t != null) teams.add(t);
            }
            if (!teams.isEmpty()) return String.join("; ", teams);
        }

        // (4) No team known.
        return "";
    }

    /** Change Type resolution (item C, Vikas Singh 2026-07-06). The per-ECN
     *  changeType recorded by the nightly Python is often blank for recently-created
     *  ECNs. Derive it from the KPI Master lookup (D029-00006) using the ECN's
     *  Request Classification + Subclass — the authoritative source — and fall back
     *  to the Python-recorded value only when the pair isn't mapped. */
    private String resolveChangeType(Map<String, Object> event, String ecnNum,
                                     Map<String, Map<String, Object>> ecnLookup) {
        // 1) Prefer the event's OWN Request Classification + Subclass, captured at
        //    rejection time by weekly_rejection_report.py. This resolves Change Type
        //    even for in-flight ECNs that aren't in the completed-ECN ecn_data.json.
        if (kpiClassifications != null && event != null) {
            String cls = str(event.get("requestClassification"));
            String sub = str(event.get("requestSubclass"));
            if (!cls.isEmpty() && !sub.isEmpty()) {
                String mapped = kpiClassifications.changeTypeFor(cls, sub);
                if (mapped != null) return mapped;
            }
        }
        // 2) Fall back to the completed-ECN row's class/subclass -> KPI Master.
        if (ecnNum == null) return "";
        Map<String, Object> row = ecnLookup.get(ecnNum);
        if (row == null) return "";
        if (kpiClassifications != null) {
            String cls = str(row.get("requestClassification"));
            String sub = str(row.get("requestSubclass"));
            String mapped = kpiClassifications.changeTypeFor(cls, sub);
            if (mapped != null) return mapped;
        }
        Object ct = row.get("changeType");
        return ct == null ? "" : ct.toString();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /**
     * Computed aggregates for a window (used by the dashboard). Counts are derived
     * client-side too, but pre-computing here keeps the JSON over the wire small
     * and avoids client-side O(N) loops on every render.
     */
    /** Back-compat: aggregate by the default {@code category} field + active taxonomy. */
    public Map<String, Object> getAggregates(List<Map<String, Object>> evs) {
        return getAggregatesFor(evs, "category", CATEGORIES);
    }

    /**
     * Aggregate counts keyed on {@code field} (e.g. "auditCategory" / "aiCategory" /
     * "category"). Category counts are seeded from {@code order}; any event whose
     * value is null or not in {@code order} is bucketed into the LAST element of
     * {@code order}. All other rollups (product line/team/requestor/theme/trend)
     * are source-independent.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAggregatesFor(List<Map<String, Object>> evs,
                                                String field, List<String> order) {
        // Events arriving here have already been enriched by getEventsInRange()
        // (category re-classified per PT-61 prefix rules, productTeam + changeType
        // resolved from EcnReportService). We just count.
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (String c : order) categoryCounts.put(c, 0);
        Map<String, Integer> productLineCounts = new HashMap<>();
        Map<String, Integer> productTeamCounts = new HashMap<>();
        Map<String, Integer> requestorCounts = new HashMap<>();
        Map<String, Integer> themeCounts = new HashMap<>();
        Map<String, Set<String>> ecnsPerRequestor = new HashMap<>();
        Map<String, Integer> dailyCounts = new TreeMap<>();
        Set<String> uniqueEcns = new HashSet<>();
        int returnedByRequestor = 0;
        for (Map<String, Object> e : evs) {
            String cat = (String) e.get(field);
            String fallback = order.get(order.size() - 1);
            if (cat == null || !categoryCounts.containsKey(cat)) cat = fallback;
            categoryCounts.merge(cat, 1, Integer::sum);
            boolean selfReturn = "Returned by Owner".equals(cat);
            if (selfReturn) returnedByRequestor++;
            String pl = (String) e.get("productLine");
            if (pl != null && !pl.isEmpty()) {
                for (String p : pl.split(";")) {
                    p = p.trim();
                    if (!p.isEmpty()) productLineCounts.merge(p, 1, Integer::sum);
                }
            }
            String team = (String) e.get("productTeam");
            if (team == null || team.isEmpty()) team = "Unknown";
            productTeamCounts.merge(team, 1, Integer::sum);
            // Self-returns aren't analyst rejections — they're the requestor
            // correcting their own work. Excluding them from the requestor counts
            // keeps "Top Repeat Requestors" honest (counts only events that an
            // analyst actually pushed back) and aligns the count with the AI
            // pattern analysis, which only sees non-self-return events.
            if (!selfReturn) {
                String req = (String) e.get("requestor");
                if (req != null && !req.isEmpty()) {
                    requestorCounts.merge(req, 1, Integer::sum);
                    ecnsPerRequestor.computeIfAbsent(req, k -> new HashSet<>())
                        .add((String) e.get("ecnNumber"));
                }
                // Themes are AI-generated; self-returns have no theme so skip them too.
                String theme = (String) e.get("theme");
                if (theme != null && !theme.isEmpty()) themeCounts.merge(theme, 1, Integer::sum);
            }
            String ts = (String) e.get("ts");
            if (ts != null && ts.length() >= 10) dailyCounts.merge(ts.substring(0, 10), 1, Integer::sum);
            String ecn = (String) e.get("ecnNumber");
            if (ecn != null) uniqueEcns.add(ecn);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEvents", evs.size());
        out.put("uniqueEcns", uniqueEcns.size());
        out.put("categories", categoryCounts);
        out.put("topProductLines", topN(productLineCounts, 10));
        out.put("topProductTeams", topN(productTeamCounts, 10));
        out.put("topRequestors", topN(requestorCounts, 10));
        out.put("topThemes", topN(themeCounts, 10));
        out.put("dailyCounts", dailyCounts);
        // Repeat requestors = requestors with ≥3 events in window. (Renamed from
        // "repeatOffenders" — the prior wording felt judgmental; same metric.)
        long repeatRequestors = requestorCounts.values().stream().filter(v -> v >= 3).count();
        out.put("repeatRequestors", repeatRequestors);
        // Backwards-compat: keep the old key too in case any caller still reads it.
        out.put("repeatOffenders", repeatRequestors);
        // Self-returns excluded from AI analysis (Returned By Requestor count).
        out.put("excludedFromAi", returnedByRequestor);
        return out;
    }

    private List<Map<String, Object>> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(n)
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("count", e.getValue());
                return m;
            })
            .collect(Collectors.toList());
    }

    // =========================================================================
    // AI vs audit agreement
    // =========================================================================

    /** The five audit reason-code categories (exclude structural owner/unknown). */
    private static final Set<String> CODED_CATEGORIES = new HashSet<>(Arrays.asList(
        "Incomplete Documentation", "Insufficient Information", "Wrong Information",
        "Duplicate Request", "Return Requested"));

    /**
     * AI↔audit agreement over events that carry a real reason code in BOTH sources.
     * Returns coded (denominator), matched, agreementPct (Integer 0-100 or null when
     * coded==0), and mismatches (capped at 100) for the drill-down panel.
     */
    public Map<String, Object> computeAgreement(List<Map<String, Object>> evs) {
        int coded = 0, matched = 0;
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (Map<String, Object> e : evs) {
            String ai = (String) e.get("aiCategory");
            String audit = (String) e.get("auditCategory");
            if (!CODED_CATEGORIES.contains(ai) || !CODED_CATEGORIES.contains(audit)) continue;
            coded++;
            if (ai.equals(audit)) {
                matched++;
            } else if (mismatches.size() < 100) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ecnNumber", e.get("ecnNumber"));
                m.put("ts", e.get("ts"));
                m.put("aiCategory", ai);
                m.put("auditCategory", audit);
                m.put("comment", e.get("comment"));
                mismatches.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coded", coded);
        out.put("matched", matched);
        out.put("agreementPct", coded > 0 ? (Integer) Math.round(100f * matched / coded) : null);
        out.put("mismatches", mismatches);
        return out;
    }

    // =========================================================================
    // Narrative
    // =========================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> getNarrative(String windowKey) {
        File f = new File(NARRATIVE_FILE);
        if (!f.exists()) return Collections.emptyMap();
        try {
            Map<String, Object> all = mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object n = all.get(windowKey);
            return n instanceof Map ? (Map<String, Object>) n : Collections.emptyMap();
        } catch (Exception e) {
            logger.warning("[REJECT-TRACKER] Failed to load narrative: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // =========================================================================
    // Recipients
    // =========================================================================

    @SuppressWarnings("unchecked")
    public synchronized List<String> getRecipients() {
        Map<String, Object> data = loadJson(RECIPIENTS_FILE);
        List<String> r = (List<String>) data.get("recipients");
        return r != null ? new ArrayList<>(r) : new ArrayList<>();
    }

    public synchronized void setRecipients(List<String> recipients) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipients", recipients);
        saveJson(RECIPIENTS_FILE, data);
        logger.info("[REJECT-TRACKER] Updated recipients: " + recipients.size() + " entries");
    }

    // =========================================================================
    // Window helpers (for narrative key naming + email subject formatting)
    // =========================================================================

    public static String windowKeyForRange(LocalDate from, LocalDate to) {
        // Match the keys produced by rejection_tracker.py --narrative-window.
        // Python (and the JS preset buttons) compute `from = today - N days, to = today`,
        // which yields ChronoUnit.DAYS.between(from, to) == N exactly. Don't add +1.
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days == 7) return "7d";
        if (days == 30) return "30d";
        if (days == 90) return "90d";
        // Calendar-month window
        if (from.getDayOfMonth() == 1 && to.getDayOfMonth() == to.lengthOfMonth()
            && from.getMonthValue() == to.getMonthValue() && from.getYear() == to.getYear()) {
            return from.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        return "custom-" + from + "-" + to;
    }

    // =========================================================================
    // JSON helpers
    // =========================================================================

    private LinkedHashMap<String, Object> loadJson(String path) {
        File f = new File(path);
        if (!f.exists()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            logger.warning("[REJECT-TRACKER] Failed to load " + path + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private synchronized void saveJson(String path, Map<String, Object> data) {
        try {
            File f = new File(path);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (Exception e) {
            logger.warning("[REJECT-TRACKER] Failed to save " + path + ": " + e.getMessage());
        }
    }
}
