package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.GoLiveSignoffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Token-gated endpoints for the Go-Live / Target-UAT sign-off flow.
 *
 * <p>{@code /api/golive-signoff/data} and {@code /api/golive-signoff/submit}
 * are exempted from {@code AuthFilter} (the token is the authorization).
 * {@code /api/golive-signoff/trigger} requires an admin session — same
 * pattern as other admin-only IT-ENH endpoints.
 */
@RestController
@RequestMapping("/api/golive-signoff")
public class GoLiveSignoffController {

    private static final Logger LOG = Logger.getLogger(GoLiveSignoffController.class.getName());

    @Autowired private GoLiveSignoffService service;

    /** GET — sign-off page data; token-only auth. */
    @GetMapping("/data")
    public ResponseEntity<?> data(@RequestParam("token") String token) {
        Map<String, Object> resp = service.dataForToken(token);
        Object status = resp.get("status");
        if (status instanceof Integer) {
            int code = (Integer) status;
            // Strip the integer status field from the body before returning.
            Map<String, Object> body = new LinkedHashMap<>(resp);
            body.remove("status");
            return ResponseEntity.status(code).body(body);
        }
        return ResponseEntity.ok(resp);
    }

    /** POST — write the new date(s) to Agile as the signer. Body:
     *  <pre>{ "token":"…", "samAccountName":"…", "password":"…",
     *         "changes":[ { "ecnNumber":"…", "newDate":"YYYY-MM-DD" } ] }</pre>
     *
     *  <p>Password travels only in the POST body — never echoed back, never
     *  logged. Wrong passwords increment failedAttempts on the token; 5
     *  wrong tries locks it.
     */
    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body) {
        String token = body == null ? null : strOf(body.get("token"));
        String sam = body == null ? null : strOf(body.get("samAccountName"));
        String pass = body == null ? null : strOf(body.get("password"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = body == null ? null : (List<Map<String, Object>>) body.get("changes");

        GoLiveSignoffService.SubmitResult sr = service.submit(token, sam, pass, changes);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", sr.ok);
        if (sr.error != null) resp.put("error", sr.error);
        if (sr.tokenLocked) resp.put("tokenLocked", true);
        if (sr.needsAgileSignin) resp.put("needsAgileSignin", true);
        resp.put("results", sr.results);
        int code = sr.httpStatus > 0 ? sr.httpStatus : 200;
        return ResponseEntity.status(code).body(resp);
    }

    /** Admin-only manual trigger — fires the digest for one loginId now.
     *  Body: {@code {"flow":"GOLIVE"|"UAT", "loginId":"8252"}}. */
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", "Admin access required.");
            return ResponseEntity.status(403).body(err);
        }
        String flow = body == null ? null : strOf(body.get("flow"));
        String loginId = body == null ? null : strOf(body.get("loginId"));
        Map<String, Object> resp = new LinkedHashMap<>();
        if (flow == null || (!GoLiveSignoffService.FLOW_GOLIVE.equals(flow) && !GoLiveSignoffService.FLOW_UAT.equals(flow))) {
            resp.put("ok", false);
            resp.put("error", "flow must be GOLIVE or UAT");
            return ResponseEntity.badRequest().body(resp);
        }
        if (loginId == null || loginId.isEmpty()) {
            resp.put("ok", false);
            resp.put("error", "loginId required");
            return ResponseEntity.badRequest().body(resp);
        }
        try {
            int sent = service.runFlow(flow, loginId);
            resp.put("ok", true);
            resp.put("sent", sent);
            if (sent == 0) {
                resp.put("note", "No qualifying ECNs for that loginId, or all upcoming items were already sent in the last digest. "
                        + "Manual triggers bypass dedupe, so an empty result means there is nothing in the window.");
            }
            LOG.info("[GLS] manual trigger flow=" + flow + " loginId=" + loginId + " sent=" + sent
                    + " by=" + session.getAttribute("username"));
        } catch (Exception e) {
            LOG.warning("[GLS] manual trigger failed: " + e.getMessage());
            resp.put("ok", false);
            resp.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    private static boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object v = session.getAttribute("isPlmAdmin");
        return Boolean.TRUE.equals(v);
    }

    private static String strOf(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
