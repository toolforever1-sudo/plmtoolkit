package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Decides whether this instance is pointed at PRODUCTION data, so the QA/TEST
 * banner can soften its wording when it is not.
 *
 * <p>"Prod data" is true if <em>either</em> the database <em>or</em> the Agile
 * write target is production; it is only non-prod when <em>both</em> are
 * non-prod. This is deliberately conservative — it never claims "non-prod"
 * while writes could still reach the prod Agile server. If the agile-service
 * is unreachable, the Agile side is treated as prod.
 *
 * <p>Detection is by hostname/SID marker:
 * <ul>
 *   <li>DB prod  — the toolkit's own {@code spring.datasource.jdbc-url} contains
 *       {@code agprod} (prod SID) or {@code uls-dp} (prod DB host).</li>
 *   <li>Agile prod — the agile-service's {@code agileUrl} (read from its
 *       {@code /api/lookup/health} endpoint) contains {@code uls-ep} (prod app host).</li>
 * </ul>
 */
@Service
public class InstanceEnvService {

    @Value("${spring.datasource.jdbc-url:}")
    private String jdbcUrl;

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    private final RestTemplate http;
    /** Null until the first successful probe; cached thereafter (config is static). */
    private volatile Boolean agileProdCached;

    public InstanceEnvService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1500);
        f.setReadTimeout(1500);
        this.http = new RestTemplate(f);
    }

    /** True if this instance reads/writes PRODUCTION data (DB or Agile). */
    public boolean isProdData() {
        return isDbProd() || isAgileProd();
    }

    boolean isDbProd() {
        String u = jdbcUrl == null ? "" : jdbcUrl.toLowerCase();
        return u.contains("agprod") || u.contains("uls-dp");
    }

    boolean isAgileProd() {
        Boolean cached = agileProdCached;
        if (cached != null) return cached;
        try {
            String base = agileServiceUrl == null ? "" : agileServiceUrl.replaceAll("/+$", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.getForObject(base + "/api/lookup/health", Map.class);
            Object su = resp == null ? null : resp.get("agileUrl");
            String serverUrl = su == null ? "" : su.toString().toLowerCase();
            boolean prod = serverUrl.contains("uls-ep");
            agileProdCached = prod; // cache only on a successful probe
            return prod;
        } catch (Exception e) {
            return true; // unreachable -> conservative; not cached, so retried next call
        }
    }
}
