package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.logging.Logger;

@Service
public class AgileBomRaceClient {

    private static final Logger logger = Logger.getLogger(AgileBomRaceClient.class.getName());

    @Value("${agile.service.url:http://localhost:8081}")
    private String agileServiceUrl;

    private final RestTemplate http = new RestTemplate();

    /** Pre-flight. Returns true only if /api/lookup/bom/health responds 200 with ok=true. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean healthCheck() {
        try {
            ResponseEntity<Map> resp = http.getForEntity(
                agileServiceUrl + "/api/lookup/bom/health", Map.class);
            return resp.getStatusCode() == HttpStatus.OK
                && Boolean.TRUE.equals(resp.getBody() != null ? resp.getBody().get("ok") : null);
        } catch (ResourceAccessException e) {
            logger.warning("[BOM_RACE] agile-service unreachable: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warning("[BOM_RACE] agile-service health check failed: " + e.getMessage());
            return false;
        }
    }

    /** POST /api/lookup/bom/explode. Returns the raw response map; caller unpacks perItem[]. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> explode(List<String> items, int maxDepth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("maxDepth", maxDepth);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = http.exchange(
            agileServiceUrl + "/api/lookup/bom/explode",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        return resp.getBody();
    }
}
