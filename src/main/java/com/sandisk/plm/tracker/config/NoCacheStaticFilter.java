package com.sandisk.plm.tracker.config;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Stop the browser from caching the toolkit's shell assets across sessions.
 *
 * <p>Why: index.html, login.html, app.js and the per-tab JS files don't carry
 * content-hashed filenames, so when the server ships a UI string change (e.g.
 * the IMS Review &rarr; IMS Dashboard rename on 2026-06-04), users who don't
 * hard-refresh keep seeing the old text after log-out / log-in because their
 * browser served the cached HTML / JS bytes. Vikas reported this directly.
 *
 * <p>This filter sets {@code Cache-Control: no-cache, must-revalidate} on
 * every {@code .html}, {@code .js}, and {@code .css} response. Browsers will
 * still hit our cached copy but they MUST revalidate with the server first
 * (cheap conditional GET against Spring's auto-generated ETag), so any byte
 * change picks up on the next page load with no hard-refresh required. PNG /
 * SVG / font assets are left untouched &mdash; they rarely change and the
 * extra revalidation traffic isn't worth it.
 *
 * <p>Order: runs before {@link AuthFilter} and {@link MaintenanceModeFilter}
 * so the header is on the response no matter how the request is later
 * handled (redirect to login, 503 maintenance page, etc.).
 */
@Component
@Order(-1)
public class NoCacheStaticFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;
            String path = req.getRequestURI();
            if (path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css") || path.equals("/")) {
                // no-cache = "use cached copy only after revalidating with server".
                // Combined with must-revalidate, stale copies cannot be used at all.
                res.setHeader("Cache-Control", "no-cache, must-revalidate");
                res.setHeader("Pragma", "no-cache");      // HTTP/1.0 fallback for ancient proxies
            }
        }
        chain.doFilter(request, response);
    }
}
