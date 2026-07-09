package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ObaApiKeyGuardTest {

    @Test
    void notConfiguredWhenKeyBlank() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("");
        assertEquals(ObaApiKeyGuard.Result.NOT_CONFIGURED, g.check("anything"));
    }

    @Test
    void unauthorizedWhenMissingOrWrong() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("s3cret-key");
        assertEquals(ObaApiKeyGuard.Result.UNAUTHORIZED, g.check(null));
        assertEquals(ObaApiKeyGuard.Result.UNAUTHORIZED, g.check("wrong"));
    }

    @Test
    void okWhenMatches() {
        ObaApiKeyGuard g = new ObaApiKeyGuard("s3cret-key");
        assertEquals(ObaApiKeyGuard.Result.OK, g.check("  s3cret-key  "));
    }
}
