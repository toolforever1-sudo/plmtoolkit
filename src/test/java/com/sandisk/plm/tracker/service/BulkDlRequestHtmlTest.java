package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.PendingRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BulkDlRequestHtmlTest {

    private PendingRequest req(String name, String user, String email, List<String> tabs) {
        PendingRequest p = new PendingRequest();
        p.sAMAccountName = user;
        p.displayName = name;
        p.email = email;
        p.requestedTabs = tabs;
        p.requestedByDisplay = "Vikas Jindal";
        p.requestedBy = "vikas.jindal";
        p.requestedAt = "2026-06-24 10:00:00";
        return p;
    }

    @Test
    void listsEveryUserAndEscapes() {
        UserPermissionsService svc = new UserPermissionsService();
        List<PendingRequest> reqs = Arrays.asList(
            req("Philip Tam", "philip.tam", "philip.tam@sandisk.com", Arrays.asList("fields", "bom")),
            req("Eva <Lu>", "eva.lu", "eva.lu@sandisk.com", Collections.singletonList("history")));
        String html = svc.buildBulkDLRequestHtml(reqs);
        assertTrue(html.contains("Philip Tam"));
        assertTrue(html.contains("eva.lu@sandisk.com"));
        assertTrue(html.contains("Eva &lt;Lu&gt;"));   // escaped
        assertTrue(html.contains("2 ") || html.contains(">2<")); // count surfaced somewhere
        assertTrue(html.toLowerCase().contains("access"));
    }
}
