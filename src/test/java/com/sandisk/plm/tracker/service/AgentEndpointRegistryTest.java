package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

public class AgentEndpointRegistryTest {

    @Test
    void everyEndpointIsFullyDescribed() {
        AgentEndpointRegistry reg = new AgentEndpointRegistry();
        assertFalse(reg.all().isEmpty(), "registry must not be empty");
        for (AgentEndpoint e : reg.all()) {
            assertNotNull(e.method, "method null on " + e.path);
            assertTrue(e.path != null && e.path.startsWith("/api/agent/"), "bad path: " + e.path);
            assertTrue(e.domain != null && !e.domain.isEmpty(), "no domain on " + e.path);
            assertTrue(e.description != null && !e.description.isEmpty(), "no description on " + e.path);
            assertTrue(e.returns != null && !e.returns.isEmpty(), "no returns on " + e.path);
            assertNotNull(e.params, "params null on " + e.path);
            for (AgentEndpoint.Param p : e.params) {
                assertTrue(p.name != null && !p.name.isEmpty(), "param no name on " + e.path);
                assertTrue(p.type != null && !p.type.isEmpty(), "param no type on " + e.path);
                assertTrue(p.description != null && !p.description.isEmpty(), "param no desc on " + e.path);
            }
        }
    }

    @Test
    void pathsAreUnique() {
        AgentEndpointRegistry reg = new AgentEndpointRegistry();
        Set<String> paths = reg.paths();
        assertEquals(reg.all().size(), paths.size(), "duplicate paths in registry");
    }
}
