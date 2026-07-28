package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AgentApiController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.sandisk\\.plm\\.tracker\\.config\\..*"))
public class AgentApiControllerTest {

    @Autowired MockMvc mvc;

    @MockBean AgentApiKeyGuard guard;
    @MockBean AgentRateLimiter rateLimiter;
    @MockBean AgentEndpointRegistry registry;
    @MockBean ActivityLogger activityLogger;
    @MockBean ItemsSearchService itemsSearchService;
    @MockBean BomDataService bomDataService;
    @MockBean ChangeQueryService changeQueryService;
    @MockBean ChangeHistoryService changeHistoryService;
    @MockBean RevCompareService revCompareService;
    @MockBean EcoTimelineService ecoTimelineService;
    @MockBean ChangeReviewService changeReviewService;
    @MockBean DocReviewService docReviewService;
    @MockBean SdsmDocumentsService sdsmDocumentsService;
    @MockBean SdsmPartsService sdsmPartsService;
    @MockBean SdsmDeviationsService sdsmDeviationsService;
    @MockBean SdsmContextIndex sdsmContextIndex;
    @MockBean SdsmFileService sdsmFileService;
    @MockBean SkuDataService skuDataService;
    @MockBean EcnReportService ecnReportService;
    @MockBean KpiClassificationService kpiClassificationService;
    @MockBean ReportService reportService;
    @MockBean RejectionTrackerService rejectionTrackerService;
    @MockBean RejectionSnapshotService rejectionSnapshotService;
    @MockBean RejectionTrackerEmailService rejectionTrackerEmailService;
    @MockBean OverdueTrackerService overdueTrackerService;
    @MockBean AgileItemFilesClient agileItemFilesClient;
    @MockBean DocumentIndexService documentIndexService;
    @MockBean AttachmentTextExtractor textExtractor;
    @MockBean AttachmentCache attachmentCache;

    private void keyOk() {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new AgentRateLimiter.Decision(true, 0));
    }

    @Test
    void catalog503WhenNotConfigured() throws Exception {
        when(guard.check(any())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.NOT_CONFIGURED, null));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "x"))
           .andExpect(status().is(503));
    }

    @Test
    void catalog401WhenBadKey() throws Exception {
        when(guard.check(any())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.UNAUTHORIZED, null));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "bad"))
           .andExpect(status().is(401));
    }

    @Test
    void catalog429WithBodyAndHeaderWhenThrottled() throws Exception {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new AgentRateLimiter.Decision(false, 12));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "k"))
           .andExpect(status().is(429))
           .andExpect(header().string("Retry-After", "12"))
           .andExpect(jsonPath("$.reason").value("rate_limit"))
           .andExpect(jsonPath("$.retryable").value(true))
           .andExpect(jsonPath("$.retryAfterSeconds").value(12))
           .andExpect(jsonPath("$.endUserMessage").isNotEmpty());
    }

    @Test
    void catalogReturnsContractAndEndpoints() throws Exception {
        keyOk();
        when(registry.all()).thenReturn(java.util.Collections.singletonList(
            new AgentEndpoint("GET", "/api/agent/items/columns", "Items", "desc", "returns", null)));
        mvc.perform(get("/api/agent/catalog").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.version").value("1"))
           .andExpect(jsonPath("$.rateLimitContract.clientObligation").isNotEmpty())
           .andExpect(jsonPath("$.endpoints[0].path").value("/api/agent/items/columns"));
    }

    @Test
    void changesSearchDelegatesAndReturnsTruncationFlag() throws Exception {
        keyOk();
        ChangeQueryService.SearchResult sr = new ChangeQueryService.SearchResult(
            java.util.Collections.emptyList(), 0, 0, 5L, true, false, "2026-07-08");
        when(changeQueryService.search(any(), any(), any(), anyInt(), any(), any(), anyBoolean()))
            .thenReturn(sr);
        mvc.perform(get("/api/agent/changes").header("X-API-Key", "k").param("item", "ABC"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.truncated").value(true))
           .andExpect(jsonPath("$.dataAsOf").value("2026-07-08"));
    }

    @Test
    void itemsDistinctDelegates() throws Exception {
        keyOk();
        when(itemsSearchService.distinctValues("lifecycle"))
            .thenReturn(java.util.Arrays.asList("Prototype", "Production"));
        mvc.perform(get("/api/agent/items/distinct").header("X-API-Key", "k").param("column", "lifecycle"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.column").value("lifecycle"))
           .andExpect(jsonPath("$.values[1]").value("Production"));
    }

    @Test
    void bomExplodeDelegates() throws Exception {
        keyOk();
        when(bomDataService.explodeMultiple(any(), anyInt(), any()))
            .thenReturn(java.util.Collections.emptyList());
        mvc.perform(get("/api/agent/bom/explode").header("X-API-Key", "k")
                .param("items", "ABC").param("maxDepth", "5"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void ecoTimelineParsesDates() throws Exception {
        keyOk();
        when(ecoTimelineService.query(eq("ABC"), any(), any(), anyInt()))
            .thenReturn(java.util.Collections.singletonMap("ok", true));
        mvc.perform(get("/api/agent/eco-timeline").header("X-API-Key", "k")
                .param("item", "ABC").param("from", "2026-01-01").param("to", "2026-06-30"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void skuFieldsDelegates() throws Exception {
        keyOk();
        when(skuDataService.getAvailableFields()).thenReturn(java.util.Arrays.asList("SKU", "Status"));
        mvc.perform(get("/api/agent/sku/fields").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0]").value("SKU"));
    }

    @Test
    void ecnReportDataDelegates() throws Exception {
        keyOk();
        when(ecnReportService.getEcnData()).thenReturn(java.util.Collections.singletonMap("rows", 5));
        mvc.perform(get("/api/agent/ecn-report/data").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows").value(5));
    }

    @Test
    void filesListDelegates() throws Exception {
        keyOk();
        AgileItemFilesClient.FilesResult fr = new AgileItemFilesClient.FilesResult();
        fr.found = true;
        when(agileItemFilesClient.listFiles("ABC")).thenReturn(fr);
        mvc.perform(get("/api/agent/files/list").header("X-API-Key", "k").param("item", "ABC"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.found").value(true));
    }

    @Test
    void filesDownloadReturnsBytes() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 200; fs.bytes = new byte[]{1,2,3};
        fs.filename = "spec.pdf"; fs.contentType = "application/pdf";
        when(agileItemFilesClient.fetchFile("ABC", "spec.pdf")).thenReturn(fs);
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "spec.pdf"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("spec.pdf")));
    }

    @Test
    void filesDownload503WhenUpstreamDown() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        // Simulate an upstream failure whose error text carries an internal hostname.
        fs.httpStatus = 0; fs.error = "UnknownHostException: internal-agile-host.example.com";
        when(agileItemFilesClient.fetchFile(anyString(), any())).thenReturn(fs);
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.pdf"))
           .andExpect(status().is(503))
           // The upstream error detail (and any internal hostname it carries) must NOT
           // leak into the client body — it is logged server-side only.
           .andExpect(content().string(org.hamcrest.Matchers.not(
                   org.hamcrest.Matchers.containsString("internal-agile-host"))));
    }

    @Test
    void filesDownloadUsesFilesBucket() throws Exception {
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire("atwork", AgentRateLimiter.Bucket.FILES))
            .thenReturn(new AgentRateLimiter.Decision(false, 30));
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.pdf"))
           .andExpect(status().is(429));
    }

    // --- Regression: items/search must tolerate columns as a CSV string (was ClassCastException -> 503) ---
    @Test
    void itemsSearchAcceptsColumnsAsCsvString() throws Exception {
        keyOk();
        ItemsSearchService.RunResult rr = new ItemsSearchService.RunResult();
        rr.rows = java.util.Collections.emptyList();
        rr.columns = java.util.Arrays.asList("PART_NUMBER", "DESCRIPTION");
        rr.matchedCount = 0; rr.truncated = false; rr.elapsedMs = 1;
        when(itemsSearchService.run(any(), any())).thenReturn(rr);
        // columns sent as a plain string, not an array — previously 503'd
        mvc.perform(post("/api/agent/items/search").header("X-API-Key", "k")
                .contentType("application/json")
                .content("{\"conditions\":[],\"columns\":\"PART_NUMBER,DESCRIPTION\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.columns[0]").value("PART_NUMBER"));
    }

    @Test
    void itemsSearchRejectsStringConditionsWith400() throws Exception {
        keyOk();
        mvc.perform(post("/api/agent/items/search").header("X-API-Key", "k")
                .contentType("application/json")
                .content("{\"conditions\":\"lifecycle=ACT\"}"))
           .andExpect(status().is(400))
           .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- Regression: eco-timeline / returns with no dates default to a window (was NPE -> 503) ---
    @Test
    void ecoTimelineDefaultsDatesWhenMissing() throws Exception {
        keyOk();
        when(ecoTimelineService.query(eq("ABC"), any(), any(), anyInt()))
            .thenReturn(java.util.Collections.singletonMap("ok", true));
        mvc.perform(get("/api/agent/eco-timeline").header("X-API-Key", "k").param("item", "ABC"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void returnsDataDefaultsDatesWhenMissing() throws Exception {
        keyOk();
        when(rejectionTrackerService.getEventsInRange(any(), any()))
            .thenReturn(java.util.Collections.emptyList());
        mvc.perform(get("/api/agent/returns/data").header("X-API-Key", "k"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
    }

    // --- Documents endpoints ---
    @Test
    void documentsSearchDelegatesWithDownloadUrls() throws Exception {
        keyOk();
        com.sandisk.plm.tracker.model.AgentDocument d = new com.sandisk.plm.tracker.model.AgentDocument();
        d.number = "00-00-WW-02-00007"; d.description = "Quality Control Plan"; d.style = "Guideline";
        com.sandisk.plm.tracker.model.AgentDocument.Attachment a = new com.sandisk.plm.tracker.model.AgentDocument.Attachment();
        a.fileName = "x.docx"; a.fileDescription = "Final"; d.attachments.add(a);
        when(documentIndexService.search(any(), any(), anyInt(), anyInt()))
            .thenReturn(new DocumentIndexService.SearchResult(1, 0, 50, java.util.Arrays.asList(d)));
        when(documentIndexService.generatedAt()).thenReturn("2026-07-10");
        mvc.perform(get("/api/agent/documents").header("X-API-Key", "k").param("q", "quality"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total").value(1))
           .andExpect(jsonPath("$.documents[0].number").value("00-00-WW-02-00007"))
           .andExpect(jsonPath("$.documents[0].attachments[0].downloadUrl")
                   .value(org.hamcrest.Matchers.containsString("/api/agent/files/download?item=00-00-WW-02-00007&name=x.docx")));
    }

    @Test
    void documentsAggregate400OnBadGroupBy() throws Exception {
        keyOk();
        when(documentIndexService.aggregate(eq("bogus"), any(), any()))
            .thenThrow(new IllegalArgumentException("groupBy must be one of [...]"));
        mvc.perform(get("/api/agent/documents/aggregate").header("X-API-Key", "k").param("groupBy", "bogus"))
           .andExpect(status().is(400));
    }

    @Test
    void filesTextReturnsExtractedText() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 200; fs.bytes = new byte[]{1,2,3}; fs.filename = "policy.docx";
        when(agileItemFilesClient.fetchFile("14-01-WW-00-00027", "policy.docx")).thenReturn(fs);
        when(textExtractor.extract(any(), eq("policy.docx")))
            .thenReturn(new AttachmentTextExtractor.Result("ok", "extracted policy text", false, null));
        mvc.perform(get("/api/agent/files/text").header("X-API-Key", "k")
                .param("item", "14-01-WW-00-00027").param("name", "policy.docx"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.extractionStatus").value("ok"))
           .andExpect(jsonPath("$.text").value("extracted policy text"));
    }

    @Test
    void filesDownloadServesFromCacheWithoutRateLimit() throws Exception {
        // Key OK, but the rate limiter is set to DENY the FILES bucket. A cache HIT must
        // still succeed (hits skip the bucket) and must NOT call Agile.
        when(guard.check(anyString())).thenReturn(new AgentApiKeyGuard.CheckResult(AgentApiKeyGuard.Result.OK, "atwork"));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new AgentRateLimiter.Decision(false, 30));
        when(attachmentCache.get("ABC", "x.pdf"))
            .thenReturn(new AttachmentCache.Entry(new byte[]{1,2,3}, "application/pdf", "x.pdf", 0L));
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.pdf"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("x.pdf")));
        verify(agileItemFilesClient, never()).fetchFile(anyString(), any());  // hit → no Agile round-trip
    }

    @Test
    void filesDownloadCachesFetchedBytes() throws Exception {
        keyOk();
        when(attachmentCache.get(anyString(), any())).thenReturn(null); // miss
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 200; fs.bytes = new byte[]{9,9}; fs.filename = "d.pdf"; fs.contentType = "application/pdf";
        when(agileItemFilesClient.fetchFile("ABC", "d.pdf")).thenReturn(fs);
        mvc.perform(get("/api/agent/files/download").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "d.pdf"))
           .andExpect(status().isOk());
        verify(attachmentCache).put("ABC", "d.pdf", fs.bytes, "application/pdf", "d.pdf"); // clean 200 cached
    }

    @Test
    void filesText503WhenUpstreamDown() throws Exception {
        keyOk();
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = 0; fs.error = "connection refused";
        when(agileItemFilesClient.fetchFile(anyString(), any())).thenReturn(fs);
        mvc.perform(get("/api/agent/files/text").header("X-API-Key", "k")
                .param("item", "ABC").param("name", "x.docx"))
           .andExpect(status().is(503));
    }

    @Test
    void documentByNumber404WhenMissing() throws Exception {
        keyOk();
        when(documentIndexService.get("NOPE")).thenReturn(null);
        mvc.perform(get("/api/agent/documents/NOPE").header("X-API-Key", "k"))
           .andExpect(status().is(404));
    }
}
