package com.sandisk.plm.tracker.config;

import com.sandisk.plm.tracker.service.ExportGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter that limits concurrent Excel exports to 1 server-wide.
 * Matches any request path containing "/export" under /api/.
 */
@Component
public class ExportRateLimitFilter implements Filter {

    @Autowired
    private ExportGuard exportGuard;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // Only guard export endpoints
        if (path.startsWith("/api/") && path.contains("/export")) {
            if (!exportGuard.tryAcquire()) {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(429);
                resp.setContentType("application/json");
                resp.getWriter().write("{\"error\":\"Another export is in progress. Please wait a moment and try again.\"}");
                return;
            }
            try {
                chain.doFilter(request, response);
            } finally {
                exportGuard.release();
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
