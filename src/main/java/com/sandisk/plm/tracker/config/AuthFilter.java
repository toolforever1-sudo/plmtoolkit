package com.sandisk.plm.tracker.config;

import com.sandisk.plm.tracker.service.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

@Component
@Order(1)
public class AuthFilter implements Filter {

    @Autowired
    private SessionRegistry sessionRegistry;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        // Allow: login page, auth endpoints, monitor API (server-to-server, has its own key auth), static resources
        if (path.equals("/login.html") ||
            // IMS Review compliance-PDF approval landing page. The URL token in
            // the email is the authorization; the AD password the user types
            // there is the signature. No session involved.
            path.equals("/ims-respond.html") ||
            path.startsWith("/api/ims-review/token/") ||
            // Go-Live / Target-UAT sign-off page. The URL token in the email
            // is the authorization; the Agile password the user types on the
            // page is the signature. No toolkit session is involved.
            // /trigger is NOT exempted — it's admin-only and goes through the
            // session check below.
            path.startsWith("/golive-signoff") ||
            path.equals("/api/golive-signoff/data") ||
            path.equals("/api/golive-signoff/submit") ||
            path.startsWith("/api/auth/") ||
            path.startsWith("/api/monitor/") ||
            // OBA Required-Doc API (Auto OBA Buyoff System, server-to-server). No toolkit
            // session — the endpoints enforce their own X-API-Key gate in ObaController.
            path.startsWith("/api/oba/") ||
            // Agent API (Atwork AI agent, server-to-server). No toolkit session —
            // the endpoints enforce their own X-API-Key gate + rate limiting in
            // AgentApiController.
            path.startsWith("/api/agent/") ||
            // PT-56 fix: deploy.bat hits this without a session (the controller
            // gates it to loopback addresses only — see MaintenanceController.scheduleFromDeploy).
            path.equals("/api/maintenance/schedule-from-deploy") ||
            // deploy.bat polls this (no session) to wait out end-user extensions;
            // the controller gates it to loopback addresses only.
            path.equals("/api/maintenance/deploy-ready") ||
            path.endsWith(".css") ||
            path.endsWith(".js") ||
            path.endsWith(".ico") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".svg")) {
            chain.doFilter(request, response);
            return;
        }

        // Check session for all other requests
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("username") == null) {
            // API calls get 401, page requests get redirected
            if (path.startsWith("/api/")) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Not authenticated\"}");
            } else {
                // Preserve the deep-linked URL (path + query string) so login.html
                // can bounce us back after auth. Without this, anyone clicking
                // an IMS Review email link (which carries ?tab=ims-review&asDO=true)
                // would land on /index.html with no params after login, losing
                // the email-link routing.
                String qs = req.getQueryString();
                String originalUrl = (qs == null || qs.isEmpty()) ? path : (path + "?" + qs);
                String redirect = "/login.html?returnTo="
                        + java.net.URLEncoder.encode(originalUrl, "UTF-8");
                res.sendRedirect(redirect);
            }
            return;
        }

        // Track who's currently active (used by MaintenanceService to know who to notify
        // when a shutdown is scheduled). Skip the maintenance-status endpoint itself so
        // a single browser polling every 10s doesn't keep "you" alive forever after you've
        // walked away. Real interactions (any other endpoint, or page loads) still count.
        if (sessionRegistry != null && !path.startsWith("/api/maintenance/status")) {
            String username = (String) session.getAttribute("username");
            String displayName = (String) session.getAttribute("displayName");
            String email = (String) session.getAttribute("email");
            Boolean isAdmin = (Boolean) session.getAttribute("isPlmAdmin");
            sessionRegistry.touch(username, displayName, email, isAdmin != null && isAdmin);
        }

        chain.doFilter(request, response);
    }
}
