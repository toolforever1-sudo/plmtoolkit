package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgileItemFilesClientTest {

    @Test
    void parsesFilesJson() {
        String json = "{\"itemNumber\":\"D026-002282-L1\",\"found\":true,\"files\":["
                + "{\"fileName\":\"spec _Final.pdf\",\"fileDescription\":\"Label Spec _Final\","
                + "\"fileType\":\"pdf\",\"byteSize\":297313},"
                + "{\"fileName\":\"spec _Final.docx\",\"fileDescription\":\"Label Spec _Final\","
                + "\"fileType\":\"docx\",\"byteSize\":111104}]}";
        AgileItemFilesClient.FilesResult r = AgileItemFilesClient.parseFilesJson(json);
        assertTrue(r.found);
        assertNull(r.error);
        assertEquals(2, r.files.size());
        assertEquals("spec _Final.pdf", r.files.get(0).fileName);
        assertEquals("Label Spec _Final", r.files.get(0).fileDescription);
        assertEquals("pdf", r.files.get(0).fileType);
        assertEquals(297313L, r.files.get(0).byteSize);
    }

    @Test
    void parsesNotFound() {
        AgileItemFilesClient.FilesResult r =
                AgileItemFilesClient.parseFilesJson("{\"itemNumber\":\"X\",\"found\":false,\"files\":[]}");
        assertFalse(r.found);
        assertTrue(r.files.isEmpty());
    }
}
