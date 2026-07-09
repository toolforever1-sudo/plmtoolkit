package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight write-only activity endpoints invoked from the browser.
 *
 * Currently only handles tab navigation (every tab switch fires
 * {@code POST /api/activity/page} from {@code switchTab()} in app.js).
 *
 * Auth is enforced by AuthFilter — only logged-in sessions reach this handler.
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    @Autowired private ActivityLogger activityLogger;

    private String username(HttpSession s) {
        Object u = s.getAttribute("username");
        return u != null ? u.toString() : "unknown";
    }
    private String displayName(HttpSession s) {
        return (String) s.getAttribute("displayName");
    }

    /**
     * Record a tab-view event. Body: {"tab": "ECN Report", "view": "Returns Tracker"}
     * (view is optional — used by tabs that have sub-modes, e.g., the ECN Report
     * pill toggle between Cycle Time and Returns Tracker).
     */
    @PostMapping("/page")
    public Map<String, Object> logPage(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> r = new LinkedHashMap<>();
        String tab = body != null && body.get("tab") != null ? body.get("tab").toString() : "(unknown)";
        String view = body != null && body.get("view") != null ? body.get("view").toString() : null;
        String detail = view != null && !view.isEmpty()
            ? "Opened " + tab + " → " + view
            : "Opened " + tab;
        activityLogger.log(username(session), displayName(session), "PAGE_VIEW", detail);
        r.put("success", true);
        return r;
    }
}
