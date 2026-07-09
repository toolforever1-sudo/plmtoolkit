package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Read-only, X-API-Key-gated gateway for the Atwork AI agent.
 * Session auth is bypassed for /api/agent/* in AuthFilter; this controller
 * enforces the key + per-key rate limits and logs every call via ActivityLogger.
 * See docs/superpowers/specs/2026-07-08-atwork-agent-api-design.md.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApiController {

    private static final Logger LOG = Logger.getLogger(AgentApiController.class.getName());

    private final AgentApiKeyGuard guard;
    private final AgentRateLimiter rateLimiter;
    private final AgentEndpointRegistry registry;
    private final ActivityLogger activityLogger;

    // Delegated read-only service beans.
    private final ItemsSearchService itemsSearchService;
    private final BomDataService bomDataService;
    private final ChangeQueryService changeQueryService;
    private final ChangeHistoryService changeHistoryService;
    private final RevCompareService revCompareService;
    private final EcoTimelineService ecoTimelineService;
    private final ChangeReviewService changeReviewService;
    private final DocReviewService docReviewService;
    private final SdsmDocumentsService sdsmDocumentsService;
    private final SdsmPartsService sdsmPartsService;
    private final SdsmDeviationsService sdsmDeviationsService;
    private final SdsmContextIndex sdsmContextIndex;
    private final SdsmFileService sdsmFileService;
    private final SkuDataService skuDataService;
    private final EcnReportService ecnReportService;
    private final KpiClassificationService kpiClassificationService;
    private final ReportService reportService;
    private final RejectionTrackerService rejectionTrackerService;
    private final RejectionSnapshotService rejectionSnapshotService;
    private final RejectionTrackerEmailService rejectionTrackerEmailService;
    private final OverdueTrackerService overdueTrackerService;
    private final AgileItemFilesClient agileItemFilesClient;

    @Value("${app.agent.rate.data-per-min:60}") private int dataPerMin;
    @Value("${app.agent.rate.files-per-min:10}") private int filesPerMin;

    public AgentApiController(AgentApiKeyGuard guard, AgentRateLimiter rateLimiter,
                              AgentEndpointRegistry registry, ActivityLogger activityLogger,
                              ItemsSearchService itemsSearchService, BomDataService bomDataService,
                              ChangeQueryService changeQueryService, ChangeHistoryService changeHistoryService,
                              RevCompareService revCompareService, EcoTimelineService ecoTimelineService,
                              ChangeReviewService changeReviewService, DocReviewService docReviewService,
                              SdsmDocumentsService sdsmDocumentsService, SdsmPartsService sdsmPartsService,
                              SdsmDeviationsService sdsmDeviationsService, SdsmContextIndex sdsmContextIndex,
                              SdsmFileService sdsmFileService, SkuDataService skuDataService,
                              EcnReportService ecnReportService, KpiClassificationService kpiClassificationService,
                              ReportService reportService, RejectionTrackerService rejectionTrackerService,
                              RejectionSnapshotService rejectionSnapshotService,
                              RejectionTrackerEmailService rejectionTrackerEmailService,
                              OverdueTrackerService overdueTrackerService,
                              AgileItemFilesClient agileItemFilesClient) {
        this.guard = guard; this.rateLimiter = rateLimiter; this.registry = registry;
        this.activityLogger = activityLogger;
        this.itemsSearchService = itemsSearchService; this.bomDataService = bomDataService;
        this.changeQueryService = changeQueryService; this.changeHistoryService = changeHistoryService;
        this.revCompareService = revCompareService; this.ecoTimelineService = ecoTimelineService;
        this.changeReviewService = changeReviewService; this.docReviewService = docReviewService;
        this.sdsmDocumentsService = sdsmDocumentsService; this.sdsmPartsService = sdsmPartsService;
        this.sdsmDeviationsService = sdsmDeviationsService; this.sdsmContextIndex = sdsmContextIndex;
        this.sdsmFileService = sdsmFileService; this.skuDataService = skuDataService;
        this.ecnReportService = ecnReportService; this.kpiClassificationService = kpiClassificationService;
        this.reportService = reportService; this.rejectionTrackerService = rejectionTrackerService;
        this.rejectionSnapshotService = rejectionSnapshotService;
        this.rejectionTrackerEmailService = rejectionTrackerEmailService;
        this.overdueTrackerService = overdueTrackerService; this.agileItemFilesClient = agileItemFilesClient;
    }

    // ---- Discovery ----

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<Map<String, Object>> deny = gate(apiKey, AgentRateLimiter.Bucket.DATA, "/api/agent/catalog", null);
        if (deny != null) return deny;

        List<Map<String, Object>> eps = new ArrayList<>();
        for (AgentEndpoint e : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", e.method);
            m.put("path", e.path);
            m.put("domain", e.domain);
            m.put("description", e.description);
            m.put("returns", e.returns);
            List<Map<String, Object>> ps = new ArrayList<>();
            for (AgentEndpoint.Param p : e.params) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name); pm.put("type", p.type);
                pm.put("required", p.required); pm.put("description", p.description);
                ps.add(pm);
            }
            m.put("params", ps);
            eps.add(m);
        }

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("dataPerMin", dataPerMin);
        contract.put("filesPerMin", filesPerMin);
        contract.put("onExceed", "HTTP 429 with Retry-After header and a machine-readable body.");
        contract.put("clientObligation", "A 429 means the requested data was NOT returned. If the agent "
                + "was gathering data to answer an end-user question, it MUST tell the end user the answer "
                + "is incomplete (throttled) rather than answering from partial data. Back off per "
                + "Retry-After and retry.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "1");
        body.put("generatedAt", Instant.now().toString());
        body.put("rateLimitContract", contract);
        body.put("endpoints", eps);
        return ResponseEntity.ok(body);
    }

    // ---- Items / Parts ----

    @GetMapping("/items/columns")
    public ResponseEntity<?> itemsColumns(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/items/columns", null, () -> {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ItemsSearchService.ColumnDef c : ItemsSearchService.COLUMNS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name);
                m.put("label", c.label);
                m.put("type", c.type.name());
                m.put("operators", ItemsSearchService.opsFor(c.type));
                cols.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("columns", cols);
            return body;
        });
    }

    @GetMapping("/items/distinct")
    public ResponseEntity<?> itemsDistinct(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("column") String column) {
        return data(apiKey, "/api/agent/items/distinct", "column=" + column, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("column", column);
            body.put("values", itemsSearchService.distinctValues(column));
            return body;
        });
    }

    @PostMapping("/items/search")
    public ResponseEntity<?> itemsSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestBody Map<String, Object> req) {
        return data(apiKey, "/api/agent/items/search", null, () -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawConds = (List<Map<String, Object>>) req.getOrDefault("conditions", Collections.emptyList());
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) req.getOrDefault("columns", Collections.emptyList());
            List<ItemsSearchService.Condition> conds = new ArrayList<>();
            for (Map<String, Object> rc : rawConds) {
                ItemsSearchService.Condition c = new ItemsSearchService.Condition();
                c.connector = str(rc.get("connector"));
                c.column = str(rc.get("column"));
                c.operator = str(rc.get("operator"));
                c.value = str(rc.get("value"));
                Object vs = rc.get("values");
                if (vs instanceof List) {
                    List<String> lv = new ArrayList<>();
                    for (Object o : (List<?>) vs) lv.add(String.valueOf(o));
                    c.values = lv;
                }
                conds.add(c);
            }
            ItemsSearchService.RunResult r = itemsSearchService.run(conds, cols);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", r.rows);
            body.put("columns", r.columns);
            body.put("matchedCount", r.matchedCount);
            body.put("truncated", r.truncated);
            body.put("elapsedMs", r.elapsedMs);
            if (r.errorMessage != null) body.put("errorMessage", r.errorMessage);
            return body;
        });
    }

    @GetMapping("/parts/search")
    public ResponseEntity<?> partsSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam("items") String items,
                                         @RequestParam(value = "columns", required = false) String columns,
                                         @RequestParam(value = "releaseDateFrom", required = false) String releaseDateFrom,
                                         @RequestParam(value = "releaseDateTo", required = false) String releaseDateTo) {
        return data(apiKey, "/api/agent/parts/search", "items=" + items, () ->
            listBody(bomDataService.searchParts(items, csv(columns), releaseDateFrom, releaseDateTo)));
    }

    // ---- Changes / History ----

    @GetMapping("/changes")
    public ResponseEntity<?> changes(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                     @RequestParam(value = "field", required = false) String field,
                                     @RequestParam(value = "item", required = false) String item,
                                     @RequestParam(value = "user", required = false) String user,
                                     @RequestParam(value = "days", defaultValue = "7") int days,
                                     @RequestParam(value = "oldContains", required = false) String oldContains,
                                     @RequestParam(value = "newContains", required = false) String newContains,
                                     @RequestParam(value = "netFilter", defaultValue = "false") boolean netFilter) {
        return data(apiKey, "/api/agent/changes", "item=" + item + " days=" + days, () -> {
            ChangeQueryService.SearchResult r =
                changeQueryService.search(field, item, user, days, oldContains, newContains, netFilter);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("results", r.getResults());
            body.put("totalCount", r.getTotalCount());
            body.put("uniqueItems", r.getUniqueItems());
            body.put("queryTimeMs", r.getQueryTimeMs());
            body.put("truncated", r.isTruncated());
            body.put("dbOffline", r.isDbOffline());
            body.put("dataAsOf", r.getDataAsOf());
            return body;
        });
    }

    @GetMapping("/history/search")
    public ResponseEntity<?> historySearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("items") String items,
                                           @RequestParam(value = "lifecyclePhases", required = false) String lifecyclePhases,
                                           @RequestParam(value = "changeTypes", required = false) String changeTypes,
                                           @RequestParam(value = "partTypes", required = false) String partTypes,
                                           @RequestParam(value = "releaseDateFrom", required = false) String releaseDateFrom,
                                           @RequestParam(value = "releaseDateTo", required = false) String releaseDateTo,
                                           @RequestParam(value = "entryMode", defaultValue = "ALL") String entryMode) {
        return data(apiKey, "/api/agent/history/search", "items=" + items, () -> {
            ChangeHistoryService.HistoryFilters f = new ChangeHistoryService.HistoryFilters();
            f.lifecyclePhases = csv(lifecyclePhases);
            f.changeTypes = csv(changeTypes);
            f.partTypes = csv(partTypes);
            f.releaseDateFrom = releaseDateFrom;
            f.releaseDateTo = releaseDateTo;
            try {
                f.entryMode = ChangeHistoryService.EntryMode.valueOf(entryMode.trim().toUpperCase());
            } catch (Exception ignore) {
                f.entryMode = ChangeHistoryService.EntryMode.ALL;
            }
            return listBody(changeHistoryService.getHistoryFiltered(csv(items), f));
        });
    }

    // ---- BOM ----

    @GetMapping("/bom/explode")
    public ResponseEntity<?> bomExplode(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam("items") String items,
                                        @RequestParam(value = "maxDepth", defaultValue = "20") int maxDepth,
                                        @RequestParam(value = "lifecycles", required = false) String lifecycles,
                                        @RequestParam(value = "lifecyclesMode", required = false) String lifecyclesMode,
                                        @RequestParam(value = "partTypes", required = false) String partTypes,
                                        @RequestParam(value = "partTypesMode", required = false) String partTypesMode,
                                        @RequestParam(value = "prefixes", required = false) String prefixes,
                                        @RequestParam(value = "prefixesMode", required = false) String prefixesMode,
                                        @RequestParam(value = "maxTopLevelParents", required = false) Integer maxTopLevelParents) {
        return data(apiKey, "/api/agent/bom/explode", "items=" + items, () -> {
            com.sandisk.plm.tracker.model.BomFilters filters = com.sandisk.plm.tracker.model.BomFilters.parse(
                lifecycles, lifecyclesMode, partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
            return listBody(bomDataService.explodeMultiple(csv(items), maxDepth, filters));
        });
    }

    @GetMapping("/bom/implode")
    public ResponseEntity<?> bomImplode(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam("items") String items,
                                        @RequestParam(value = "maxDepth", defaultValue = "20") int maxDepth,
                                        @RequestParam(value = "lifecycles", required = false) String lifecycles,
                                        @RequestParam(value = "lifecyclesMode", required = false) String lifecyclesMode,
                                        @RequestParam(value = "partTypes", required = false) String partTypes,
                                        @RequestParam(value = "partTypesMode", required = false) String partTypesMode,
                                        @RequestParam(value = "prefixes", required = false) String prefixes,
                                        @RequestParam(value = "prefixesMode", required = false) String prefixesMode,
                                        @RequestParam(value = "maxTopLevelParents", required = false) Integer maxTopLevelParents) {
        return data(apiKey, "/api/agent/bom/implode", "items=" + items, () -> {
            com.sandisk.plm.tracker.model.BomFilters filters = com.sandisk.plm.tracker.model.BomFilters.parse(
                lifecycles, lifecyclesMode, partTypes, partTypesMode, prefixes, prefixesMode, maxTopLevelParents);
            return listBody(bomDataService.implodeMultiple(csv(items), maxDepth, filters));
        });
    }

    @GetMapping("/bom/components")
    public ResponseEntity<?> bomComponents(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("parent") String parent) {
        return data(apiKey, "/api/agent/bom/components", "parent=" + parent, () ->
            listBody(bomDataService.getBomComponents(parent)));
    }

    // ---- Revisions ----

    @GetMapping("/rev-compare/revs")
    public ResponseEntity<?> revs(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                  @RequestParam("part") String part) {
        return data(apiKey, "/api/agent/rev-compare/revs", "part=" + part, () ->
            listBody(revCompareService.getRevisions(part)));
    }

    @GetMapping("/rev-compare/detail")
    public ResponseEntity<?> revDetail(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("part") String part,
                                       @RequestParam("rev") String rev,
                                       @RequestParam(value = "change", required = false) String change) {
        return data(apiKey, "/api/agent/rev-compare/detail", "part=" + part + " rev=" + rev, () ->
            revCompareService.getRevDetail(part, rev, change));
    }

    // ---- ECO Timeline ----

    @GetMapping("/eco-timeline")
    public ResponseEntity<?> ecoTimeline(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam("item") String item,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to,
                                         @RequestParam(value = "maxDepth", defaultValue = "25") int maxDepth) {
        return data(apiKey, "/api/agent/eco-timeline", "item=" + item, () -> {
            java.time.LocalDate f = parseDate(from);
            java.time.LocalDate t = parseDate(to);
            return ecoTimelineService.query(item, f, t, maxDepth);
        });
    }

    // ---- Change Review ----

    @GetMapping("/change-reviews/analysts")
    public ResponseEntity<?> reviewAnalysts(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/change-reviews/analysts", null, () ->
            listBody(changeReviewService.getAnalysts()));
    }

    @GetMapping("/change-reviews/detail")
    public ResponseEntity<?> reviewDetail(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                          @RequestParam("change") String change) {
        return data(apiKey, "/api/agent/change-reviews/detail", "change=" + change, () ->
            changeReviewService.getSignoffDetail(change));
    }

    @GetMapping("/change-reviews/dashboard")
    public ResponseEntity<?> reviewDashboard(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                             @RequestParam(value = "days", defaultValue = "30") int days) {
        return data(apiKey, "/api/agent/change-reviews/dashboard", "days=" + days, () ->
            listBody(changeReviewService.getAllChangesInReview(days)));
    }

    // ---- Doc Review ----

    @GetMapping("/doc-review/data")
    public ResponseEntity<?> docReviewData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam(value = "window", required = false) String window,
                                           @RequestParam(value = "from", required = false) String from,
                                           @RequestParam(value = "to", required = false) String to) {
        return data(apiKey, "/api/agent/doc-review/data", "window=" + window, () ->
            listBody(docReviewService.search(DocReviewService.parseWindow(window), from, to, false)));
    }

    // ---- SDSM (document search + facets) ----

    @GetMapping("/sdsm/search")
    public ResponseEntity<?> sdsmSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                        @RequestParam(value = "q", required = false) String q) {
        return data(apiKey, "/api/agent/sdsm/search", "q=" + q, () -> {
            String filter = (q == null) ? "" : q.trim();
            List<com.sandisk.plm.tracker.model.SdsmAttachment> out = new ArrayList<>();
            out.addAll(sdsmDocumentsService.run(filter, 0));
            out.addAll(sdsmPartsService.run(filter, 0));
            return listBody(out);
        });
    }

    @GetMapping("/sdsm/specs")
    public ResponseEntity<?> sdsmSpecs(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/specs", null, () -> listBody(sdsmContextIndex.getSpecs()));
    }

    @GetMapping("/sdsm/product-groups")
    public ResponseEntity<?> sdsmProductGroups(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/product-groups", null, () -> listBody(sdsmContextIndex.getProductGroups()));
    }

    @GetMapping("/sdsm/products")
    public ResponseEntity<?> sdsmProducts(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/products", null, () -> listBody(sdsmContextIndex.getProducts()));
    }

    @GetMapping("/sdsm/active-deviations")
    public ResponseEntity<?> sdsmActiveDeviations(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sdsm/active-deviations", null, () -> listBody(sdsmDeviationsService.run(null)));
    }

    // ---- SKU ----

    @GetMapping("/sku/fields")
    public ResponseEntity<?> skuFields(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/sku/fields", null, () -> listBody(skuDataService.getAvailableFields()));
    }

    @GetMapping("/sku/search")
    public ResponseEntity<?> skuSearch(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("items") String items) {
        return data(apiKey, "/api/agent/sku/search", "items=" + items, () -> {
            List<Object> records = new ArrayList<>();
            for (String it : csv(items)) {
                Object rec = skuDataService.getRecord(it);
                if (rec != null) records.add(rec);
            }
            return listBody(records);
        });
    }

    // ---- ECN report data ----

    @GetMapping("/ecn-report/data")
    public ResponseEntity<?> ecnReportData(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/ecn-report/data", null, () -> ecnReportService.getEcnData());
    }

    @GetMapping("/ecn-report/kpi-classifications")
    public ResponseEntity<?> ecnKpi(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/ecn-report/kpi-classifications", null, () ->
            listBody(kpiClassificationService.getEntries()));
    }

    // ---- Returns / Rejection tracker ----

    @GetMapping("/returns/data")
    public ResponseEntity<?> returnsData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to) {
        return data(apiKey, "/api/agent/returns/data", "from=" + from + " to=" + to, () ->
            listBody(rejectionTrackerService.getEventsInRange(parseDate(from), parseDate(to))));
    }

    @GetMapping("/returns/periods")
    public ResponseEntity<?> returnsPeriods(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return data(apiKey, "/api/agent/returns/periods", null, () -> listBody(rejectionSnapshotService.listPeriods()));
    }

    @GetMapping("/returns/explain/{eventId}")
    public ResponseEntity<?> returnsExplain(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                            @PathVariable String eventId) {
        return data(apiKey, "/api/agent/returns/explain/" + eventId, null, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", eventId);
            try {
                body.put("explanation", rejectionTrackerEmailService.explainEvent(eventId));
            } catch (Exception e) {
                throw new RuntimeException(e); // → 503 via data(); explain is best-effort
            }
            return body;
        });
    }

    // ---- Overdue tracker ----

    @GetMapping("/overdue/data")
    public ResponseEntity<?> overdueData(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @RequestParam(value = "minOver", required = false) Integer minOver,
                                         @RequestParam(value = "maxOver", required = false) Integer maxOver,
                                         @RequestParam(value = "classifications", required = false) String classifications) {
        return data(apiKey, "/api/agent/overdue/data", null, () ->
            overdueTrackerService.getData(minOver, maxOver, null, null, null, null, null, null, classifications));
    }

    // ---- Files (attachment metadata + bytes; FILES rate bucket) ----

    @GetMapping("/files/list")
    public ResponseEntity<?> filesList(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                       @RequestParam("item") String item) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/files/list", "item=" + item);
        if (deny != null) return deny;
        try {
            AgileItemFilesClient.FilesResult fr = agileItemFilesClient.listFiles(item);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("item", item);
            body.put("found", fr.found);
            List<Map<String, Object>> files = new ArrayList<>();
            if (fr.files != null) {
                for (AgileItemFilesClient.FileMeta m : fr.files) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fileName", m.fileName);
                    f.put("fileDescription", m.fileDescription);
                    f.put("fileType", m.fileType);
                    f.put("byteSize", m.byteSize);
                    f.put("contentAvailable", m.contentAvailable);
                    if (m.fileName != null) {
                        f.put("downloadUrl", "/api/agent/files/download?item="
                            + enc(item) + "&name=" + enc(m.fileName));
                    }
                    files.add(f);
                }
            }
            body.put("files", files);
            if (fr.error != null) body.put("error", fr.error);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            LOG.warning("[AGENT] files/list failed: " + e);
            return err(503, "document service temporarily unavailable");
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<?> filesDownload(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestParam("item") String item,
                                           @RequestParam(value = "name", required = false) String name) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/files/download", "item=" + item + " name=" + name);
        if (deny != null) return deny;

        AgileItemFilesClient.FileStream fs;
        try {
            fs = agileItemFilesClient.fetchFile(item, name);
        } catch (Exception e) {
            LOG.warning("[AGENT] files/download failed: " + e);
            return err(503, "document service temporarily unavailable");
        }
        if (fs.httpStatus == 200 && fs.bytes != null) {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(parseContentType(fs.contentType));
            h.setContentDisposition(org.springframework.http.ContentDisposition
                    .builder("attachment").filename(fs.filename == null ? "download" : fs.filename).build());
            h.setContentLength(fs.bytes.length);
            return new ResponseEntity<>(fs.bytes, h, org.springframework.http.HttpStatus.OK);
        }
        if (fs.httpStatus == 404) return err(404, "attachment not found");
        if (fs.httpStatus == 422) return err(422, "attachment content not available in this environment");
        if (fs.httpStatus == 400) return err(400, "bad attachment request");
        // Upstream (plm-agile-service) unreachable/errored. Do NOT surface fs.error to
        // the client — it can carry the internal agile-service hostname/URL (e.g. on
        // UnknownHostException). It's already logged server-side by AgileItemFilesClient.
        LOG.warning("[AGENT] files/download upstream status " + fs.httpStatus + " for " + item
            + (fs.error != null ? " (" + fs.error + ")" : ""));
        return err(503, "document service temporarily unavailable");
    }

    @GetMapping("/sdsm/file/{attachId}")
    public ResponseEntity<?> sdsmFile(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                      @PathVariable long attachId,
                                      @RequestParam(value = "fileName", required = false) String fileName,
                                      @RequestParam(value = "rev", required = false) String rev,
                                      @RequestParam(value = "parentNumber", required = false) String parentNumber) {
        ResponseEntity<Map<String, Object>> deny =
            gate(apiKey, AgentRateLimiter.Bucket.FILES, "/api/agent/sdsm/file/" + attachId, null);
        if (deny != null) return deny;
        try {
            SdsmFileService.Result r = sdsmFileService.fetch(parentNumber, rev, fileName, attachId);
            if (r == null || r.bytes == null) return err(404, "SDSM file not found");
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(parseContentType(r.contentType));
            h.setContentDisposition(org.springframework.http.ContentDisposition
                    .builder("attachment").filename(r.filename == null ? "download" : r.filename).build());
            h.setContentLength(r.bytes.length);
            return new ResponseEntity<>(r.bytes, h, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            LOG.warning("[AGENT] sdsm/file failed: " + e);
            return err(503, "SDSM file service temporarily unavailable");
        }
    }

    // ---- Shared helpers (used by every wrapper) ----

    /** Key check -> rate limit -> audit. Returns a deny ResponseEntity, or null when allowed. */
    private ResponseEntity<Map<String, Object>> gate(String apiKey, AgentRateLimiter.Bucket bucket,
                                                     String path, String detail) {
        AgentApiKeyGuard.CheckResult cr = guard.check(apiKey);
        switch (cr.result) {
            case NOT_CONFIGURED: return err(503, "Agent API not configured");
            case UNAUTHORIZED:   return err(401, "invalid or missing X-API-Key");
            default: break;
        }
        AgentRateLimiter.Decision d = rateLimiter.tryAcquire(cr.label, bucket);
        if (!d.allowed) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("error", "Rate limit exceeded — this data was not returned. Any answer built without it is incomplete.");
            b.put("status", 429);
            b.put("reason", "rate_limit");
            b.put("retryable", true);
            b.put("retryAfterSeconds", d.retryAfterSeconds);
            b.put("endUserMessage", "I couldn't retrieve all the information needed to answer this fully "
                    + "because the PLM system is rate-limiting requests. Please try again in a few seconds.");
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(d.retryAfterSeconds)).body(b);
        }
        activityLogger.log("agent:" + cr.label, cr.label, "AGENT_API",
                path + (detail == null || detail.isEmpty() ? "" : " " + detail));
        return null;
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", message);
        b.put("status", status);
        return ResponseEntity.status(status).body(b);
    }

    /** Wrap a read-only data supplier: gate (DATA bucket) then run, mapping failures to the envelope. */
    private ResponseEntity<?> data(String apiKey, String path, String detail, Supplier<Object> body) {
        ResponseEntity<Map<String, Object>> deny = gate(apiKey, AgentRateLimiter.Bucket.DATA, path, detail);
        if (deny != null) return deny;
        try {
            return ResponseEntity.ok(body.get());
        } catch (IllegalArgumentException e) {
            return err(400, e.getMessage() == null ? "bad request" : e.getMessage());
        } catch (Exception e) {
            LOG.warning("[AGENT] " + path + " failed: " + e);
            return err(503, "data source temporarily unavailable");
        }
    }

    /** Wrap a List result as { data:[...], count:N }. */
    private static Map<String, Object> listBody(List<?> list) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", list == null ? 0 : list.size());
        m.put("data", list == null ? Collections.emptyList() : list);
        return m;
    }

    /** Split a CSV param into a trimmed, non-empty list (empty list when null/blank). */
    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) { return s; }
    }

    private static org.springframework.http.MediaType parseContentType(String contentType) {
        if (contentType == null) return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        try {
            return org.springframework.http.MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static java.time.LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad date (expected YYYY-MM-DD): " + s);
        }
    }
}
