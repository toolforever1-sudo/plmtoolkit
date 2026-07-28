package com.sandisk.plm.tracker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Records one metrics line per {@code /api/agent/*} request — timestamp, method,
 * path, query, HTTP status, and elapsed ms — to {@code app.agent.metrics.file}
 * (JSONL). This is what the twice-daily Agent-API usage digest reads; the activity
 * log can't provide latency/status because it logs at call start, not completion.
 * Best-effort: a metrics write failure never affects the request.
 */
@Component
@Order(2) // after AuthFilter (@Order 1), so we time the handled request
public class AgentMetricsFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(AgentMetricsFilter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String metricsFile;

    public AgentMetricsFilter(@Value("${app.agent.metrics.file:./data/agent-metrics.jsonl}") String metricsFile) {
        this.metricsFile = metricsFile;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();
        if (!path.startsWith("/api/agent/")) {
            chain.doFilter(request, response);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = System.currentTimeMillis() - t0;
            int status = ((HttpServletResponse) response).getStatus();
            record(req.getMethod(), path, req.getQueryString(), status, ms);
        }
    }

    private synchronized void record(String method, String path, String query, int status, long ms) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", Instant.now().toString());
            m.put("method", method);
            m.put("path", path);
            m.put("query", query == null ? "" : query);
            m.put("status", status);
            m.put("ms", ms);
            File f = new File(metricsFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(MAPPER.writeValueAsString(m));
                w.write("\n");
            }
        } catch (Exception e) {
            LOG.warning("[AGENT-METRICS] could not record: " + e);
        }
    }
}
