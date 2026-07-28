package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.Preset;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TabCatalogTest {

    private final UserPermissionsService svc = new UserPermissionsService();

    private Preset preset(String id) {
        for (Preset p : svc.getPresets()) if (p.id.equals(id)) return p;
        throw new AssertionError("no preset " + id);
    }

    @Test
    void everyGrantableTabIsInExactlyOneGroup() {
        Set<String> grantable = svc.grantableTabKeys();
        Map<String, String> groups = svc.getTabGroups();
        for (String k : grantable) {
            assertTrue(groups.containsKey(k), "grantable tab not grouped: " + k);
        }
    }

    @Test
    void noGroupReferencesANonGrantableTab() {
        Set<String> grantable = svc.grantableTabKeys();
        for (String k : svc.getTabGroups().keySet()) {
            assertTrue(grantable.contains(k), "grouped tab is not grantable: " + k);
        }
    }

    @Test
    void everyGroupNameIsInGroupOrder() {
        List<String> order = svc.getGroupOrder();
        for (String g : svc.getTabGroups().values()) {
            assertTrue(order.contains(g), "group not in GROUP_ORDER: " + g);
        }
    }

    @Test
    void presetsReferenceOnlyGrantableKeys() {
        Set<String> grantable = svc.grantableTabKeys();
        for (Preset p : svc.getPresets()) {
            for (String k : p.tabKeys) {
                assertTrue(grantable.contains(k), "preset " + p.id + " references non-grantable: " + k);
            }
        }
    }

    @Test
    void fullAccessEqualsAllGrantable() {
        assertEquals(new HashSet<>(svc.grantableTabKeys()), new HashSet<>(preset("full").tabKeys));
    }

    @Test
    void presetMembershipsAreExact() {
        assertTrue(preset("blank").tabKeys.isEmpty());
        assertEquals(Arrays.asList("agile", "sku", "history", "ecotimeline", "ecnreport", "ims-review"),
                preset("viewer").tabKeys);
        assertEquals(Arrays.asList("fields", "parts", "agile", "sku", "bom", "bomcompare"),
                preset("items").tabKeys);
        assertEquals(Arrays.asList("history", "ecotimeline", "ecnreport", "singlesole", "docreview"),
                preset("reporting").tabKeys);
    }

    @Test
    void presetOrderIsBlankViewerItemsReportingFull() {
        List<String> ids = new ArrayList<>();
        for (Preset p : svc.getPresets()) ids.add(p.id);
        assertEquals(Arrays.asList("blank", "viewer", "items", "reporting", "full"), ids);
    }
}
