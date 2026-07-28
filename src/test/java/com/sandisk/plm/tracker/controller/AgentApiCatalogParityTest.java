package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.AgentEndpointRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry (which backs /catalog) must list exactly the paths the controller
 * maps — no more, no less — excluding /catalog itself. Pure reflection, no Spring
 * context needed.
 */
public class AgentApiCatalogParityTest {

    @Test
    void registryPathsMatchControllerMappings() {
        Set<String> mapped = new LinkedHashSet<>();
        String base = "/api/agent";
        for (Method m : AgentApiController.class.getDeclaredMethods()) {
            String sub = subPath(m);
            if (sub == null) continue;
            String full = base + sub;
            if (full.equals("/api/agent/catalog")) continue;
            mapped.add(full);
        }
        Set<String> registered = new LinkedHashSet<>(new AgentEndpointRegistry().paths());
        assertEquals(new java.util.TreeSet<>(registered), new java.util.TreeSet<>(mapped),
            "Registry paths must equal controller-mapped paths (excluding /catalog)");
    }

    private static String subPath(Method m) {
        GetMapping g = AnnotatedElementUtils.findMergedAnnotation(m, GetMapping.class);
        if (g != null && g.value().length > 0) return g.value()[0];
        PostMapping p = AnnotatedElementUtils.findMergedAnnotation(m, PostMapping.class);
        if (p != null && p.value().length > 0) return p.value()[0];
        return null;
    }
}
