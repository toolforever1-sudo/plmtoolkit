package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.config.ConfigBanner;
import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.AccessRequestService;
import com.sandisk.plm.tracker.service.ExtensionPermissionService;
import com.sandisk.plm.tracker.service.LdapAuthService;
import com.sandisk.plm.tracker.service.SessionRegistry;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private ConfigBanner configBanner;

    @org.springframework.beans.factory.annotation.Value("${app.instance.label:}")
    private String instanceLabel;

    @org.springframework.beans.factory.annotation.Value("${app.instance.banner:}")
    private String instanceBanner;

    @org.springframework.beans.factory.annotation.Value("${app.instance.banner-nonprod:}")
    private String instanceBannerNonprod;

    // Single source of truth for the Agile webclient base URL across every tab's
    // deep-links (Items/ECN/DRR/DCO). Prod default; override per environment
    // (e.g. https://plmqa.sandisk.com/Agile on QA). Same property the IMS
    // dashboard + emails already read.
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")
    private String agileWebclientUrl;

    @Autowired
    private com.sandisk.plm.tracker.service.InstanceEnvService instanceEnvService;

    @Autowired
    private LdapAuthService ldapAuthService;

    @Autowired
    private ActivityLogger activityLogger;

    @Autowired
    private ExtensionPermissionService extensionPermissionService;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private UserPermissionsService userPermissionsService;

    @Autowired
    private com.sandisk.plm.tracker.service.ImsReviewQueueStore imsReviewQueueStore;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> credentials,
                                      HttpSession session) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Map<String, Object> response = new LinkedHashMap<>();

        if (username == null || username.trim().isEmpty() ||
            password == null || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "Username and password are required.");
            return response;
        }

        LdapAuthService.AuthResult result = ldapAuthService.authenticate(username.trim(), password);

        if (result.isAuthenticated()) {
            session.setAttribute("username", result.getUsername());
            session.setAttribute("displayName", result.getDisplayName());
            session.setAttribute("email", result.getEmail());
            // Store admin status for conditional UI elements
            session.setAttribute("isPlmAdmin", result.isPlmAdmin());
            // 2026-06-04: stash the user's AD group memberships on the session
            // so AD-group-driven tab grants (e.g. PDL-PLM-admin → IT Enhancements
            // tab) can decide visibility without a per-request LDAP probe.
            // Cached/emergency logins get an empty set — those paths don't
            // re-validate group membership and shouldn't grant AD-driven tabs.
            session.setAttribute("adGroups", result.getAdGroups());
            // Engineering-insider role (AI Help Engineering mode) — cheap
            // session flag; the chat endpoint re-validates via hasRole so a
            // mid-session grant/revoke also takes effect without re-login.
            session.setAttribute("isEngineeringInsider",
                userPermissionsService.hasRole(result.getUsername(),
                    com.sandisk.plm.tracker.service.UserPermissionsService.ROLE_ENGINEERING_INSIDER));

            // 2026-06-04: we *used* to stash the plaintext AD password here for
            // per-user Agile write-back (IT Enhancements tab). Turned out Agile
            // in this environment is NOT AD-integrated — it has its own user
            // store with its own passwords — so the AD password was useless for
            // SDK auth. We now prompt the user for their Agile password the
            // first time they save (see ItEnhancementsController#agileSignin)
            // and stash it as `agilePassword`. The AD password no longer lives
            // on the session at all, which is a small security win.

            activityLogger.log(result.getUsername(), result.getDisplayName(), "LOGIN", "Logged in");

            // If this user was on the "please add to DL" pending list, mark
            // that request completed — IT did their job and the user is now in.
            userPermissionsService.notifyLogin(result.getUsername());

            response.put("success", true);
            response.put("displayName", result.getDisplayName());
            response.put("email", result.getEmail());
            if (result.getLoginSource() != null && !"ad".equals(result.getLoginSource())) {
                response.put("loginSource", result.getLoginSource());
                session.setAttribute("loginSource", result.getLoginSource());
            }
        } else {
            response.put("success", false);
            response.put("message", result.getErrorMessage());
            response.put("groupCheckFailed", result.isGroupCheckFailed());
            if (result.isGroupCheckFailed()) {
                response.put("displayName", result.getDisplayName());
                response.put("email", result.getEmail());
                response.put("username", result.getUsername());
                response.put("missingGroup", result.getMissingGroup());
            }
        }

        return response;
    }

    /** Slim "is this password right" endpoint for the IMS Review approval flow.
     *
     *  <p>Differs from {@code /login} in three ways: no session is created, no
     *  group membership is required, and the response only signals
     *  pass/fail + the verified identity attributes (sAMAccountName, display
     *  name, email). The caller (typically the IMS Review respond page) treats
     *  the one-time URL token as the authorization, and uses the verified
     *  identity as the signature on a compliance PDF.</p>
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, String> request) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = request == null ? "" : request.getOrDefault("username", "");
        String password = request == null ? "" : request.getOrDefault("password", "");
        LdapAuthService.VerifyResult v = ldapAuthService.verifyCredentials(username, password);
        out.put("success", v.success);
        if (v.success) {
            out.put("samAccount", v.samAccount);
            out.put("displayName", v.displayName == null ? "" : v.displayName);
            out.put("email", v.email == null ? "" : v.email);
        } else {
            out.put("message", v.message);
        }
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username != null) sessionRegistry.remove(username);
        session.invalidate();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    @Autowired
    private AccessRequestService accessRequestService;

    @PostMapping("/request-access")
    public Map<String, Object> requestAccess(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = request.get("username");
        String displayName = request.get("displayName");
        String email = request.get("email");
        String justification = request.get("justification");

        if (username == null || username.isEmpty() || justification == null || justification.isEmpty()) {
            response.put("success", false);
            response.put("message", "Username and justification are required.");
            return response;
        }

        try {
            accessRequestService.submitRequest(username, displayName, email, justification);
            response.put("success", true);
            response.put("message", "Your access request has been submitted. You will receive an email when access is granted.");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to submit request: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/user-info/{employeeId}")
    public Map<String, Object> getUserInfo(@PathVariable String employeeId, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (session.getAttribute("username") == null) {
            response.put("success", false);
            return response;
        }
        LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(employeeId);
        if (info == null) {
            response.put("success", false);
            response.put("message", "Lookup failed");
            return response;
        }
        response.put("success", true);
        response.put("displayName", info.displayName);
        response.put("email", info.email);
        response.put("department", info.department);
        response.put("title", info.title);
        response.put("manager", info.manager);
        response.put("country", info.country);
        response.put("city", info.city);
        return response;
    }

    /**
     * Public (pre-auth) endpoint: returns the running JAR's version + build timestamp
     * so the login page can show real values instead of hard-coded placeholders. Lives
     * under /api/auth/* which AuthFilter already allows pre-login.
     */
    @GetMapping("/build-info")
    public Map<String, Object> getBuildInfo() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", configBanner.getBuildVersion());
        response.put("buildTimestamp", configBanner.getBuildTimestamp());
        response.put("buildLabel", configBanner.getBuildLabel());
        return response;
    }

    @GetMapping("/session")
    public Map<String, Object> getSession(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        if (username != null) {
            response.put("authenticated", true);
            response.put("username", username);
            response.put("displayName", session.getAttribute("displayName"));
            response.put("email", session.getAttribute("email"));
            boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
            response.put("isPlmAdmin", isAdmin);
            // Admins implicitly have contributor rights; non-admins must be explicitly granted.
            response.put("isExtensionContributor", isAdmin || extensionPermissionService.isContributor(username));
            // Per-user tab visibility — frontend uses this to render only the allowed tabs.
            // Admins always get every tab; non-admins get the default (all non-admin tabs)
            // unless a per-user record narrows them.
            @SuppressWarnings("unchecked")
            java.util.Set<String> adGroups = (java.util.Set<String>) session.getAttribute("adGroups");
            java.util.List<String> allowedTabs =
                    new java.util.ArrayList<>(userPermissionsService.getAllowedTabs(username, isAdmin,
                            adGroups == null ? java.util.Collections.<String>emptySet() : adGroups));
            // PT-IMS-REVIEW pilot: if this user isn't otherwise allowed into the
            // IMS Review tab but has pending queue items targeting their email,
            // grant `ims-review` for THIS session only. Not persisted to the
            // permissions catalog. Evaporates on session timeout. See spec
            // docs/superpowers/specs/2026-05-18-ims-review-pilot-design.md
            if (!allowedTabs.contains("ims-review")) {
                String email = (String) session.getAttribute("email");
                if (email != null && imsReviewQueueStore.hasPendingItemsFor(email)) {
                    allowedTabs.add("ims-review");
                    session.setAttribute("imsReviewAutoGranted", Boolean.TRUE);
                }
            }
            session.setAttribute("allowedTabs", allowedTabs);
            response.put("allowedTabs", allowedTabs);
            response.put("imsReviewAutoGranted",
                    Boolean.TRUE.equals(session.getAttribute("imsReviewAutoGranted")));
            response.put("isPermissionsAdmin", isAdmin || userPermissionsService.isPermissionsAdmin(username));
            response.put("isItAdmin", userPermissionsService.isItAdmin(username));
            String loginSource = (String) session.getAttribute("loginSource");
            if (loginSource != null) {
                response.put("loginSource", loginSource);
            }
            response.put("buildLabel", configBanner.getBuildLabel());
        } else {
            response.put("authenticated", false);
        }
        // Instance indicator — returned regardless of auth state so login.html
        // (anonymous) can read it too. Empty on prod.
        response.put("instanceLabel", instanceLabel == null ? "" : instanceLabel);
        response.put("instanceBanner", instanceBanner == null ? "" : instanceBanner);
        response.put("instanceBannerNonprod", instanceBannerNonprod == null ? "" : instanceBannerNonprod);
        // True if pointed at prod data (DB or Agile). Drives which banner text
        // the frontend shows; only false when BOTH DB and Agile are non-prod.
        response.put("instanceProdData", instanceEnvService.isProdData());
        // Agile webclient base URL so every tab's deep-links honor the environment.
        response.put("agileWebclientUrl", agileWebclientUrl);
        return response;
    }
}
