package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AgentApiKeyGuardTest {

    @Test
    void notConfiguredWhenBlank() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("", "");
        assertEquals(AgentApiKeyGuard.Result.NOT_CONFIGURED, g.check("anything").result);
    }

    @Test
    void unauthorizedWhenNullOrWrong() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork,ci");
        assertEquals(AgentApiKeyGuard.Result.UNAUTHORIZED, g.check(null).result);
        assertEquals(AgentApiKeyGuard.Result.UNAUTHORIZED, g.check("nope").result);
    }

    @Test
    void okResolvesLabelPerKey() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork,ci");
        AgentApiKeyGuard.CheckResult r1 = g.check("  k1  ");
        assertEquals(AgentApiKeyGuard.Result.OK, r1.result);
        assertEquals("atwork", r1.label);
        assertEquals("ci", g.check("k2").label);
    }

    @Test
    void labelFallsBackWhenMissing() {
        AgentApiKeyGuard g = new AgentApiKeyGuard("k1,k2", "atwork"); // only one label
        assertEquals("atwork", g.check("k1").label);
        assertEquals("key2", g.check("k2").label);
    }
}
