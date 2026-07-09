package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.DlWelcomeEmailService;
import com.sandisk.plm.tracker.service.LdapAuthService;
import com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import com.sandisk.plm.tracker.service.UserPermissionsService.LoginInfo;
import com.sandisk.plm.tracker.service.UserPermissionsService.PendingRequest;
import com.sandisk.plm.tracker.service.UserPermissionsService.TabDef;
import com.sandisk.plm.tracker.service.UserPermissionsService.UserRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for the User Permissions admin tab.
 *
 * <p>Authorization: every endpoint except {@code /me} requires either PLM admin
 * role OR membership in {@code app.permissions.allowed-users} (Vikas Singh).
 * Admin-only tabs are NEVER grantable to non-admins — that rule is enforced in
 * {@link UserPermissionsService#upsertUser}, not just here, so a crafted POST
 * can't bypass the check.
 */
@RestController
@RequestMapping("/api/permissions")
public class UserPermissionsController {

    @Autowired private UserPermissionsService permissionsService;
    @Autowired private LdapAuthService ldapAuthService;
    @Autowired private DlWelcomeEmailService dlWelcomeEmailService;

    /** Slug for AI Help Engineering mode — surfaced to the drawer for its model pill. */
    @org.springframework.beans.factory.annotation.Value("${app.help.engineering.model:@vertexai-global/anthropic.claude-opus-4-8}")
    private String engineeringModel;

    /**
     * Used by the frontend on every page load to know which tabs to render
     * for the current user. Open to any authenticated user (returns just
     * THEIR own allowed tabs — no global view).
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        String username = (String) session.getAttribute("username");
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("isPlmAdmin", isAdmin);
        out.put("isPermissionsAdmin", isAdmin || permissionsService.isPermissionsAdmin(username));
        @SuppressWarnings("unchecked")
        java.util.Set<String> adGroups = (java.util.Set<String>) session.getAttribute("adGroups");
        // Engineering-insider role: live check (not the login-time session attr)
        // so a grant made mid-session shows up on the next page load.
        out.put("isEngineeringInsider", permissionsService.hasRole(username,
                UserPermissionsService.ROLE_ENGINEERING_INSIDER));
        out.put("canGrantEngineeringInsider", permissionsService.isPlmIt(username, adGroups));
        out.put("engineeringModel", engineeringModel);
        out.put("allowedTabs", permissionsService.getAllowedTabs(username, isAdmin,
                adGroups == null ? java.util.Collections.<String>emptySet() : adGroups));
        return out;
    }

    /**
     * Grant or revoke the engineering-insider role. Body: {@code {username, grant:bool}}.
     * PLM IT only ({@code app.roles.engineering-insider.granters}) — the service throws
     * SecurityException for anyone else, INCLUDING PLM admins and permissions admins.
     * The UI gate is cosmetic; this is the real check.
     */
    @PostMapping("/roles/engineering-insider")
    public ResponseEntity<?> setEngineeringInsider(@RequestBody Map<String, Object> body, HttpSession session) {
        String granter = username(session);
        if (granter.isEmpty()) return ResponseEntity.status(401).body(err("Login required."));
        @SuppressWarnings("unchecked")
        java.util.Set<String> adGroups = (java.util.Set<String>) session.getAttribute("adGroups");
        String target = strOf(body.get("username"));
        boolean grant = Boolean.TRUE.equals(body.get("grant"));
        try {
            if (grant) {
                permissionsService.grantRole(granter, adGroups, displayName(session), target,
                    UserPermissionsService.ROLE_ENGINEERING_INSIDER);
            } else {
                permissionsService.revokeRole(granter, adGroups, displayName(session), target,
                    UserPermissionsService.ROLE_ENGINEERING_INSIDER);
            }
            // Keep the granter's own session flag fresh if they changed themselves.
            if (target.equalsIgnoreCase(granter)) {
                session.setAttribute("isEngineeringInsider", grant);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("username", target);
            resp.put("engineeringInsider", grant);
            return ResponseEntity.ok(resp);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(err(se.getMessage()));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(err(iae.getMessage()));
        }
    }

    /**
     * Catalog of every tab + its admin-only flag, so the editor UI knows which
     * checkboxes to lock. Admin/permissions-admin only.
     */
    @GetMapping("/tabs")
    public ResponseEntity<?> tabs(HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TabDef t : permissionsService.getTabCatalog()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", t.key);
            row.put("label", t.label);
            row.put("adminOnly", t.adminOnly);
            row.put("permissionsAdmin", t.permissionsAdmin);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Richer catalog for the Add-from-AD picker: grantable tabs tagged with their
     * display group, the group render order, and the one-click presets. Separate
     * from {@code /tabs} (whose flat-array shape other code depends on).
     */
    @GetMapping("/tab-catalog")
    public ResponseEntity<?> tabCatalog(HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        Set<String> grantable = permissionsService.grantableTabKeys();
        Map<String, String> groups = permissionsService.getTabGroups();

        List<Map<String, Object>> tabs = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.UserPermissionsService.TabDef t : permissionsService.getTabCatalog()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", t.key);
            row.put("label", t.label);
            row.put("group", groups.get(t.key));     // null for non-grantable tabs
            row.put("grantable", grantable.contains(t.key));
            tabs.add(row);
        }

        List<Map<String, Object>> presets = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.UserPermissionsService.Preset p : permissionsService.getPresets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("label", p.label);
            row.put("tabKeys", p.tabKeys);
            presets.add(row);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tabs", tabs);
        resp.put("groups", permissionsService.getGroupOrder());
        resp.put("presets", presets);
        return ResponseEntity.ok(resp);
    }

    /**
     * Multi-user variant of {@code /request-add}: persist the same tab set for every
     * selected user; one consolidated IT email for the subset not already in the DL.
     * Body: {@code {users:[{sAMAccountName,displayName,email}], allowedTabs:[]}}.
     */
    @PostMapping("/request-add-bulk")
    public ResponseEntity<?> requestAddBulk(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> users = usersOf(body.get("users"));
            List<String> tabs = listOf(body.get("allowedTabs"));
            if (users.isEmpty()) return ResponseEntity.badRequest().body(err("No users to submit."));
            List<com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome> outcomes =
                permissionsService.submitBulkDLRequest(users, tabs, username(session), displayName(session));
            int ok = 0, emailed = 0;
            for (com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome o : outcomes) {
                if (o.ok) ok++;
                if (o.emailedToIt) emailed++;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("submitted", users.size());
            resp.put("ok", ok);
            resp.put("failed", users.size() - ok);
            resp.put("emailed", emailed);
            resp.put("outcomes", outcomes);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> usersOf(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("sAMAccountName", strOf(m.get("sAMAccountName")));
                    row.put("displayName", strOf(m.get("displayName")));
                    row.put("email", strOf(m.get("email")));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /**
     * The combined list of users the admin needs to manage:
     *   - Users who have logged in (from activity log) — with last-login + count
     *   - Users in the access DL who have NEVER logged in — with email
     *   - Plus any per-user permission record (whether they fit either bucket or not)
     *
     * Each row carries: identity, login info (if any), DL membership flag,
     * current permissions record (allowed tabs, default vs explicit, who set it).
     */
    @GetMapping("/users")
    public ResponseEntity<?> users(HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        Map<String, LoginInfo> logins = permissionsService.loginHistory();
        List<DirectoryUser> dlMembers = ldapAuthService.listAccessGroupCandidates();
        Map<String, UserRecord> records = permissionsService.allRecords();
        Set<String> adminUsernames = ldapAuthService.listAdminUsernames();

        // Auto-close any pending DL request whose target user is already in the
        // DL roster (cached LDAP data — no extra round trip). This catches the
        // gap where IT added the user but the user hasn't logged in yet.
        Set<String> dlUsernamesLower = new LinkedHashSet<>();
        for (DirectoryUser d : dlMembers) {
            if (d.username != null && !d.username.isEmpty()) dlUsernamesLower.add(d.username.toLowerCase());
        }
        if (adminUsernames != null) dlUsernamesLower.addAll(adminUsernames);
        permissionsService.reconcilePendingAgainstDl(dlUsernamesLower);

        // Index everyone by lowercase username so we can dedupe across sources
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();

        // 1. Users who have logged in
        for (Map.Entry<String, LoginInfo> e : logins.entrySet()) {
            LoginInfo li = e.getValue();
            indexed.computeIfAbsent(e.getKey(), k -> baseRow(li.username, li.displayName, null))
                   .put("hasLoggedIn", true);
            Map<String, Object> row = indexed.get(e.getKey());
            row.put("lastLoginTs", li.lastLoginTs);
            row.put("loginCount", li.loginCount);
        }
        // 2. Users in DL (mark dlMember=true; some overlap with logins, some not)
        for (DirectoryUser d : dlMembers) {
            String key = d.username == null ? "" : d.username.toLowerCase();
            if (key.isEmpty()) continue;
            Map<String, Object> row = indexed.computeIfAbsent(key, k -> baseRow(d.username, d.displayName, d.email));
            row.put("dlMember", true);
            // Always overwrite with the freshest DL data we have
            if (d.displayName != null && !d.displayName.isEmpty()) row.put("displayName", d.displayName);
            if (d.email != null && !d.email.isEmpty()) row.put("email", d.email);
        }
        // 3. Per-user permission records (might be from a request-add that hasn't logged in yet)
        for (Map.Entry<String, UserRecord> e : records.entrySet()) {
            UserRecord r = e.getValue();
            Map<String, Object> row = indexed.computeIfAbsent(e.getKey(),
                k -> baseRow(r.sAMAccountName, r.displayName, r.email));
            if (r.displayName != null && !r.displayName.isEmpty()) row.put("displayName", r.displayName);
            if (r.email != null && !r.email.isEmpty()) row.put("email", r.email);
            row.put("managedExplicitly", r.managedExplicitly);
            row.put("allowedTabs", r.allowedTabs);
            row.put("permsUpdatedAt", r.updatedAt);
            row.put("permsUpdatedBy", r.updatedByDisplay);
        }

        // Annotate every row with admin status so the UI can render the badge
        // and lock the Edit modal. Cheap (in-memory set lookup).
        Set<String> engHolders = new LinkedHashSet<>();
        for (String h : permissionsService.roleHolders(UserPermissionsService.ROLE_ENGINEERING_INSIDER)) {
            engHolders.add(h.toLowerCase());
        }
        for (Map.Entry<String, Map<String, Object>> entry : indexed.entrySet()) {
            entry.getValue().put("isPlmAdmin",
                adminUsernames != null && adminUsernames.contains(entry.getKey()));
            boolean isHolder = engHolders.contains(entry.getKey());
            entry.getValue().put("engineeringInsider", isHolder);
            if (isHolder) {
                Map<String, Object> audit = permissionsService.roleLastChange(
                    UserPermissionsService.ROLE_ENGINEERING_INSIDER, entry.getKey());
                if (audit != null) entry.getValue().put("engineeringInsiderChange", audit);
            }
        }
        // Admins from the LDAP enumeration that aren't in any of the other
        // sources (haven't logged in, not in candidate list because candidates
        // explicitly excludes admins, no perm record). Add them so the table
        // surfaces them with the admin badge.
        if (adminUsernames != null) {
            for (String adminKey : adminUsernames) {
                if (indexed.containsKey(adminKey)) continue;
                Map<String, Object> row = baseRow(adminKey, adminKey, null);
                row.put("isPlmAdmin", true);
                row.put("dlMember", true);   // admin DL is part of the broader access set
                indexed.put(adminKey, row);
            }
        }

        // Sort: logged-in users first (by last-login desc), then DL-only by name
        List<Map<String, Object>> sorted = new ArrayList<>(indexed.values());
        sorted.sort((a, b) -> {
            boolean al = Boolean.TRUE.equals(a.get("hasLoggedIn"));
            boolean bl = Boolean.TRUE.equals(b.get("hasLoggedIn"));
            if (al != bl) return al ? -1 : 1;
            if (al) {
                String at = (String) a.getOrDefault("lastLoginTs", "");
                String bt = (String) b.getOrDefault("lastLoginTs", "");
                return bt.compareTo(at);
            }
            String an = String.valueOf(a.getOrDefault("displayName", a.getOrDefault("username", "")));
            String bn = String.valueOf(b.getOrDefault("displayName", b.getOrDefault("username", "")));
            return an.compareToIgnoreCase(bn);
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("users", sorted);
        resp.put("pendingRequests", permissionsService.pendingRequests());
        resp.put("counts", counts(sorted, permissionsService.pendingRequests()));
        // Carry the current viewer's admin status + the AD self-service URL so
        // the pending-DL panel can render an "Add to {group}" button for IT
        // admins (matching the CTA in the DL-request email).
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", username(session));
        me.put("isPlmAdmin", Boolean.TRUE.equals(session.getAttribute("isPlmAdmin")));
        me.put("isPermissionsAdmin", isPermsAdmin(session));
        @SuppressWarnings("unchecked")
        java.util.Set<String> meAdGroups = (java.util.Set<String>) session.getAttribute("adGroups");
        me.put("canGrantEngineeringInsider", permissionsService.isPlmIt(username(session), meAdGroups));
        resp.put("me", me);
        resp.put("adAddUrl", permissionsService.getAdAddUrl());
        resp.put("accessGroupName", permissionsService.getAccessGroupName());
        return ResponseEntity.ok(resp);
    }

    /**
     * Update a user's tab allowlist. Body: {@code {sAMAccountName, displayName,
     * email, allowedTabs:[]}}. Service strips admin-only tabs from the list.
     */
    @PostMapping("/user")
    public ResponseEntity<?> upsert(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            String sam = strOf(body.get("sAMAccountName"));
            String displayName = strOf(body.get("displayName"));
            String email = strOf(body.get("email"));
            List<String> tabs = listOf(body.get("allowedTabs"));
            UserRecord rec = permissionsService.upsertUser(sam, displayName, email, tabs,
                username(session), displayName(session));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("record", rec);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException valErr) {
            return ResponseEntity.badRequest().body(err(valErr.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /** Reset a user back to defaults (sees all non-admin tabs). */
    @DeleteMapping("/user/{sAMAccountName}")
    public ResponseEntity<?> reset(@PathVariable String sAMAccountName, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        boolean removed = permissionsService.resetUser(sAMAccountName,
            username(session), displayName(session));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("removed", removed);
        return ResponseEntity.ok(resp);
    }

    /**
     * Org-wide AD typeahead. Returns up to 25 users matching by displayName,
     * sAMAccountName, or mail (starts-with). Triggered on 3+ chars.
     */
    @GetMapping("/ad-search")
    public ResponseEntity<?> adSearch(@RequestParam("q") String q, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        if (q == null || q.trim().length() < 3) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("results", new ArrayList<>());
            resp.put("note", "Type at least 3 characters.");
            return ResponseEntity.ok(resp);
        }
        List<DirectoryUser> hits = ldapAuthService.searchDirectory(q.trim(), 25);
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> existingDl = new LinkedHashSet<>();
        for (DirectoryUser m : ldapAuthService.listAccessGroupCandidates()) {
            existingDl.add((m.username == null ? "" : m.username.toLowerCase()));
        }
        for (DirectoryUser u : hits) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sAMAccountName", u.username);
            r.put("displayName", u.displayName);
            r.put("email", u.email);
            r.put("alreadyInDL", existingDl.contains(u.username == null ? "" : u.username.toLowerCase()));
            rows.add(r);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("results", rows);
        return ResponseEntity.ok(resp);
    }

    /**
     * Submit a "please add this user to the access DL" request to IT.
     * Saves the per-user permissions record AND emails IT in one step.
     * Body: {@code {sAMAccountName, displayName, email, allowedTabs:[]}}.
     */
    @PostMapping("/request-add")
    public ResponseEntity<?> requestAdd(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            String sam = strOf(body.get("sAMAccountName"));
            String displayName = strOf(body.get("displayName"));
            String email = strOf(body.get("email"));
            List<String> tabs = listOf(body.get("allowedTabs"));
            if (sam.isEmpty()) return ResponseEntity.badRequest().body(err("sAMAccountName is required"));
            if (displayName.isEmpty()) return ResponseEntity.badRequest().body(err("displayName is required"));
            if (email.isEmpty()) return ResponseEntity.badRequest().body(err("email is required"));
            PendingRequest req = permissionsService.submitDLRequest(sam, displayName, email, tabs,
                username(session), displayName(session));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("request", req);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    /** Clear all completed pending-request entries. */
    @PostMapping("/clear-completed")
    public ResponseEntity<?> clearCompleted(HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        permissionsService.clearCompletedRequests(username(session), displayName(session));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Verified, manual invite send. For each username: live-check DL membership,
     * and only email + mark sent when the user is actually in the access DL.
     * Body: {@code {usernames:[sAMAccountName,...]}}. One failure never aborts
     * the batch. Returns {@code {sent:[{username,displayName}],
     * failed:[{username,displayName,reason}]}} (reason ∈ NOT_FOUND/NOT_IN_DL/EMAIL_FAILED).
     */
    @PostMapping("/dl-requests/send-invites")
    public ResponseEntity<?> sendInvites(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        List<String> usernames = listOf(body.get("usernames"));
        if (usernames.isEmpty()) return ResponseEntity.badRequest().body(err("No requests selected."));
        if (usernames.size() > 50) return ResponseEntity.badRequest().body(err("Too many at once — send 50 or fewer per request."));

        List<Map<String, Object>> sent = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String u : usernames) {
            PendingRequest req = permissionsService.findPendingByUsername(u);
            boolean found = req != null;
            boolean inDl = found && ldapAuthService.isUserInAccessGroup(u);
            boolean emailedOk = false;
            if (found && inDl) {
                emailedOk = dlWelcomeEmailService.sendWelcome(req);
                if (emailedOk) permissionsService.markWelcomeSent(u, username(session), displayName(session));
            }
            String reason = inviteReason(found, inDl, emailedOk);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", u);
            row.put("displayName", found ? req.displayName : u);
            if (reason == null) {
                sent.add(row);
            } else {
                row.put("reason", reason);
                failed.add(row);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("sent", sent);
        resp.put("failed", failed);
        return ResponseEntity.ok(resp);
    }

    private boolean isPermsAdmin(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return true;
        return permissionsService.isPermissionsAdmin(username(session));
    }

    private String username(HttpSession session) {
        Object o = session.getAttribute("username");
        return o == null ? "" : o.toString();
    }

    private String displayName(HttpSession session) {
        Object o = session.getAttribute("displayName");
        return o == null ? username(session) : o.toString();
    }

    private static Map<String, Object> baseRow(String username, String displayName, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("username", username);
        row.put("displayName", displayName);
        row.put("email", email);
        row.put("hasLoggedIn", false);
        row.put("dlMember", false);
        row.put("managedExplicitly", false);
        row.put("loginCount", 0);
        row.put("lastLoginTs", "");
        return row;
    }

    private static Map<String, Object> counts(List<Map<String, Object>> rows, Collection<PendingRequest> pending) {
        int loggedIn = 0, dlNeverLogged = 0, explicit = 0, pendingCount = 0;
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("hasLoggedIn"))) loggedIn++;
            if (Boolean.TRUE.equals(r.get("dlMember")) && !Boolean.TRUE.equals(r.get("hasLoggedIn"))) dlNeverLogged++;
            if (Boolean.TRUE.equals(r.get("managedExplicitly"))) explicit++;
        }
        for (PendingRequest p : pending) if ("pending".equals(p.status)) pendingCount++;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("loggedIn", loggedIn);
        c.put("dlNeverLogged", dlNeverLogged);
        c.put("explicit", explicit);
        c.put("pending", pendingCount);
        return c;
    }

    /**
     * Classify a single send-invite attempt. Returns null when the invite was
     * sent; otherwise a failure reason for the response.
     */
    static String inviteReason(boolean found, boolean inDl, boolean emailedOk) {
        if (!found) return "NOT_FOUND";
        if (!inDl) return "NOT_IN_DL";
        if (!emailedOk) return "EMAIL_FAILED";
        return null;
    }

    private static String strOf(Object o) { return o == null ? "" : o.toString().trim(); }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object o) {
        if (o instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object x : (List<Object>) o) {
                if (x != null) out.add(x.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static ResponseEntity<?> forbidden() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", "User Permissions admin access required.");
        return ResponseEntity.status(403).body(e);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", msg);
        return e;
    }
}
