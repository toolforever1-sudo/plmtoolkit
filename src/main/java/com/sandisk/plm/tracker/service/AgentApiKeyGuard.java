package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the {@code X-API-Key} header for the Agent API against one or more
 * configured keys ({@code app.agent.api-keys}, comma-separated for overlap
 * rotation). Blank config → NOT_CONFIGURED (endpoints must 503, never open by
 * default). On match, resolves a per-key audit label from
 * {@code app.agent.api-key-labels} (index-aligned; falls back to "keyN").
 * Comparison is constant-time per key.
 */
@Service
public class AgentApiKeyGuard {

    public enum Result { OK, NOT_CONFIGURED, UNAUTHORIZED }

    public static final class CheckResult {
        public final Result result;
        public final String label; // non-null only when result == OK
        public CheckResult(Result result, String label) { this.result = result; this.label = label; }
    }

    private final List<String> keys = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    public AgentApiKeyGuard(@Value("${app.agent.api-keys:}") String keysCsv,
                            @Value("${app.agent.api-key-labels:}") String labelsCsv) {
        List<String> ks = splitCsv(keysCsv);
        List<String> ls = splitCsv(labelsCsv);
        for (int i = 0; i < ks.size(); i++) {
            keys.add(ks.get(i));
            labels.add(i < ls.size() && !ls.get(i).isEmpty() ? ls.get(i) : "key" + (i + 1));
        }
    }

    public CheckResult check(String provided) {
        if (keys.isEmpty()) return new CheckResult(Result.NOT_CONFIGURED, null);
        if (provided == null) return new CheckResult(Result.UNAUTHORIZED, null);
        byte[] b = provided.trim().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < keys.size(); i++) {
            byte[] a = keys.get(i).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(a, b)) return new CheckResult(Result.OK, labels.get(i));
        }
        return new CheckResult(Result.UNAUTHORIZED, null);
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
