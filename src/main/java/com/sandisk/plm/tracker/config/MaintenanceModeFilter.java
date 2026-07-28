package com.sandisk.plm.tracker.config;

import com.sandisk.plm.tracker.service.MaintenanceService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * When the app is in maintenance mode (set by {@link MaintenanceService} after
 * a scheduled shutdown fires and the watchdog respawns the JVM with the same
 * JAR), every page request is short-circuited to the static
 * {@code /maintenance-mode.html} page and every API call gets a 503 with
 * Retry-After. Allows just enough through (auth, status poll, exit-mode admin
 * endpoint, static assets) for the page to render and for an allowlisted admin
 * to manually exit without a redeploy.
 *
 * <p>Ordered before {@link AuthFilter} so unauthenticated visitors see the
 * maintenance page instead of being redirected to the login page.
 */
@Component
@Order(0)
public class MaintenanceModeFilter implements Filter {

    private static final Logger logger = Logger.getLogger(MaintenanceModeFilter.class.getName());

    @Autowired
    private MaintenanceService maintenanceService;

    private volatile byte[] cachedHtml;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!maintenanceService.isInMaintenanceMode()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // Endpoints that must keep working even in maintenance mode:
        //   - /api/auth/*           : an admin needs to log in to call exit-mode
        //   - /api/monitor/*        : server-to-server with its own key auth, never user-facing
        //   - /api/maintenance/*    : status polling + exit-mode admin endpoint
        //   - /maintenance-mode.html: the page itself
        //   - static assets (.css, .js, .png, .jpg, .svg, .ico) : the page may load them
        if (path.startsWith("/api/auth/") ||
            path.startsWith("/api/monitor/") ||
            path.startsWith("/api/maintenance/") ||
            path.equals("/maintenance-mode.html") ||
            path.endsWith(".css") ||
            path.endsWith(".js") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".svg") ||
            path.endsWith(".ico") ||
            path.endsWith(".woff") ||
            path.endsWith(".woff2")) {
            chain.doFilter(request, response);
            return;
        }

        // Block API calls cleanly so any open browser polling gets a structured 503
        if (path.startsWith("/api/")) {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            res.setContentType("application/json");
            res.setHeader("Retry-After", "30");
            res.getWriter().write("{\"error\":\"PLM Toolkit is in maintenance mode\",\"retryAfter\":30}");
            return;
        }

        // Page request — serve the maintenance page directly (no redirect, URL stays the same)
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setContentType("text/html; charset=utf-8");
        res.setHeader("Retry-After", "30");
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write(loadMaintenancePage());
    }

    private String loadMaintenancePage() {
        byte[] html = cachedHtml;
        if (html == null) {
            try (InputStream is = getClass().getResourceAsStream("/static/maintenance-mode.html")) {
                if (is == null) {
                    logger.warning("[MAINT-FILTER] /static/maintenance-mode.html not on classpath");
                    return "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding:60px;\">"
                        + "<h1>PLM Toolkit is in maintenance mode</h1>"
                        + "<p>Please check back in a few minutes.</p></body></html>";
                }
                html = is.readAllBytes();
                cachedHtml = html;
            } catch (IOException e) {
                logger.warning("[MAINT-FILTER] Failed to read maintenance page: " + e.getMessage());
                return "<h1>PLM Toolkit is in maintenance mode</h1>";
            }
        }
        return new String(html, StandardCharsets.UTF_8);
    }
}
