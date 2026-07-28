package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-API-Key} header for the OBA endpoints against
 * {@code app.oba.api-key}. Blank config → NOT_CONFIGURED (endpoint must 503, never
 * open by default). Comparison is constant-time.
 */
@Service
public class ObaApiKeyGuard {

    public enum Result { OK, NOT_CONFIGURED, UNAUTHORIZED }

    private final String configuredKey;

    public ObaApiKeyGuard(@Value("${app.oba.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    public Result check(String provided) {
        if (configuredKey.isEmpty()) return Result.NOT_CONFIGURED;
        if (provided == null) return Result.UNAUTHORIZED;
        byte[] a = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b) ? Result.OK : Result.UNAUTHORIZED;
    }
}
