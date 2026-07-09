package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ObaResolution;
import com.sandisk.plm.tracker.service.AgileItemFilesClient;
import com.sandisk.plm.tracker.service.ObaApiKeyGuard;
import com.sandisk.plm.tracker.service.ObaResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The app registers several @Component servlet Filters (AuthFilter,
// DownloadArchiveFilter, ...) that pull in services outside this MVC slice and would
// fail the context load. Exclude the config-package filter components and disable
// filter execution so the controller is tested in isolation; the API-key gate is
// exercised via the mocked ObaApiKeyGuard, not the servlet filters.
@WebMvcTest(controllers = ObaController.class, excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "com\\.sandisk\\.plm\\.tracker\\.config\\..*"))
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
public class ObaControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ObaResolverService resolver;
    @MockBean private AgileItemFilesClient filesClient;
    @MockBean private ObaApiKeyGuard guard;

    private static AgileItemFilesClient.FilesResult oneFile(String name, String type) {
        AgileItemFilesClient.FilesResult r = new AgileItemFilesClient.FilesResult();
        r.found = true;
        AgileItemFilesClient.FileMeta m = new AgileItemFilesClient.FileMeta();
        m.fileName = name; m.fileType = type; m.byteSize = 100;
        r.files.add(m);
        return r;
    }

    private static AgileItemFilesClient.FilesResult noFiles() {
        AgileItemFilesClient.FilesResult r = new AgileItemFilesClient.FilesResult();
        r.found = true;   // item exists in Agile, but it has zero attachment files
        return r;
    }

    private static AgileItemFilesClient.FilesResult fileNoContent(String name, String type) {
        AgileItemFilesClient.FilesResult r = oneFile(name, type);
        r.files.get(0).contentAvailable = Boolean.FALSE;   // row exists, blob not in vault
        return r;
    }

    private static AgileItemFilesClient.FilesResult agileItemNotFound() {
        AgileItemFilesClient.FilesResult r = new AgileItemFilesClient.FilesResult();
        r.found = false;
        r.error = "item not found";
        return r;
    }

    private static AgileItemFilesClient.FileStream stream(int status, byte[] bytes,
                                                          String contentType, String filename) {
        AgileItemFilesClient.FileStream fs = new AgileItemFilesClient.FileStream();
        fs.httpStatus = status;
        fs.bytes = bytes;
        fs.contentType = contentType;
        fs.filename = filename;
        return fs;
    }

    @Test
    void notConfiguredReturns503() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.NOT_CONFIGURED);
        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void badKeyReturns401() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.UNAUTHORIZED);
        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void happyPathReturnsDocuments() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        ObaResolution res = new ObaResolution();
        res.c039Item = "C0390TS2670";
        res.labelProofItem = "L000-000420-L1";
        res.labelProofDesc = "Outer Carton Shipping Label";
        res.labelSpecItem = "D026-002282-L1";
        when(resolver.resolve(eq("0TS2670"))).thenReturn(res);
        when(filesClient.listFiles("L000-000420-L1")).thenReturn(oneFile("proof.docx", "docx"));
        when(filesClient.listFiles("D026-002282-L1")).thenReturn(oneFile("spec.pdf", "pdf"));
        when(filesClient.listFiles("25-07-SM-03-00006")).thenReturn(oneFile("checklist.pdf", "pdf"));

        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("0TS2670"))
                .andExpect(jsonPath("$.checklist").value("25-07-SM-03-00006"))
                .andExpect(jsonPath("$.documents[0].role").value("label-proof"))
                .andExpect(jsonPath("$.documents[0].itemNumber").value("L000-000420-L1"))
                .andExpect(jsonPath("$.documents[0].files[0].downloadUrl",
                        containsString("/api/oba/file?item=L000-000420-L1")))
                .andExpect(jsonPath("$.documents[0].status").value("ok"))
                .andExpect(jsonPath("$.documents[1].role").value("label-spec"))
                .andExpect(jsonPath("$.documents[1].files[0].isPdf").value(true))
                .andExpect(jsonPath("$.documents[2].role").value("oba-checklist"))
                .andExpect(jsonPath("$.attachmentMissing").value(false))
                .andExpect(jsonPath("$.missingRoles").isEmpty());
    }

    @Test
    void attachmentMissingWhenItemHasNoFiles() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        ObaResolution res = new ObaResolution();
        res.labelProofItem = "L000-000420-L1";
        res.labelSpecItem = "D026-002282-L1";   // exists in QA but no attachment yet
        when(resolver.resolve(eq("0TS2670"))).thenReturn(res);
        when(filesClient.listFiles("L000-000420-L1")).thenReturn(oneFile("proof.docx", "docx"));
        when(filesClient.listFiles("D026-002282-L1")).thenReturn(noFiles());
        when(filesClient.listFiles("25-07-SM-03-00006")).thenReturn(oneFile("checklist.pdf", "pdf"));

        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentMissing").value(true))
                .andExpect(jsonPath("$.missingRoles[0]").value("label-spec"))
                .andExpect(jsonPath("$.documents[1].status").value("attachment-missing"))
                .andExpect(jsonPath("$.documents[1].found").value(false))
                .andExpect(jsonPath("$.documents[1].message", containsString("Attachment missing")));
    }

    @Test
    void contentUnavailableWhenBlobMissingFromVault() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        ObaResolution res = new ObaResolution();
        res.labelProofItem = "L000-000420-L1";
        res.labelSpecItem = "D026-002282-L1";
        when(resolver.resolve(eq("0TS2670"))).thenReturn(res);
        when(filesClient.listFiles("L000-000420-L1")).thenReturn(oneFile("proof.docx", "docx"));
        when(filesClient.listFiles("D026-002282-L1")).thenReturn(fileNoContent("spec.pdf", "pdf")); // QA gap
        when(filesClient.listFiles("25-07-SM-03-00006")).thenReturn(oneFile("checklist.pdf", "pdf"));

        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentMissing").value(true))
                .andExpect(jsonPath("$.missingRoles[0]").value("label-spec"))
                .andExpect(jsonPath("$.documents[1].status").value("content-unavailable"))
                .andExpect(jsonPath("$.documents[1].found").value(false))
                .andExpect(jsonPath("$.documents[1].message", containsString("not available")))
                .andExpect(jsonPath("$.documents[1].files[0].contentAvailable").value(false));
    }

    @Test
    void fileUpstream422ReturnsContentUnavailable() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        AgileItemFilesClient.FileStream fs = stream(422, null, null, null);
        fs.error = "content not retrievable for 'spec.pdf'";
        when(filesClient.fetchFile("D026-002282-L1", "spec.pdf")).thenReturn(fs);

        mockMvc.perform(get("/api/oba/file").param("item", "D026-002282-L1")
                        .param("name", "spec.pdf").header("X-API-Key", "good"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-OBA-Reason", containsString("not retrievable")));
    }

    @Test
    void agileItemNotFoundFlaggedMissing() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        ObaResolution res = new ObaResolution();
        res.labelProofItem = "L000-000420-L1";
        res.labelSpecItem = "D026-002282-L1";
        when(resolver.resolve(eq("0TS2670"))).thenReturn(res);
        when(filesClient.listFiles("L000-000420-L1")).thenReturn(agileItemNotFound());
        when(filesClient.listFiles("D026-002282-L1")).thenReturn(oneFile("spec.pdf", "pdf"));
        when(filesClient.listFiles("25-07-SM-03-00006")).thenReturn(oneFile("checklist.pdf", "pdf"));

        mockMvc.perform(get("/api/oba/required-docs").param("sku", "0TS2670")
                        .header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentMissing").value(true))
                .andExpect(jsonPath("$.documents[0].status").value("agile-item-not-found"))
                .andExpect(jsonPath("$.documents[0].message", containsString("not found in Agile")));
    }

    @Test
    void fileDownloadReturns200WithBytes() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        byte[] body = "%PDF-1.4 hello".getBytes("UTF-8");
        when(filesClient.fetchFile("D026-002282-L1", "spec.pdf"))
                .thenReturn(stream(200, body, "application/pdf", "spec.pdf"));

        mockMvc.perform(get("/api/oba/file").param("item", "D026-002282-L1")
                        .param("name", "spec.pdf").header("X-API-Key", "good"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(content().bytes(body));
    }

    @Test
    void fileUpstream404Returns404() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        when(filesClient.fetchFile("D026-002282-L1", "missing.pdf"))
                .thenReturn(stream(404, null, null, null));

        mockMvc.perform(get("/api/oba/file").param("item", "D026-002282-L1")
                        .param("name", "missing.pdf").header("X-API-Key", "good"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileUpstream500Returns502() throws Exception {
        when(guard.check(any())).thenReturn(ObaApiKeyGuard.Result.OK);
        when(filesClient.fetchFile("D026-002282-L1", "boom.pdf"))
                .thenReturn(stream(500, null, null, null));

        mockMvc.perform(get("/api/oba/file").param("item", "D026-002282-L1")
                        .param("name", "boom.pdf").header("X-API-Key", "good"))
                .andExpect(status().isBadGateway());
    }
}
