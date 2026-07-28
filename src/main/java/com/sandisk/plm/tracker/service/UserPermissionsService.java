package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Per-user tab visibility + DL-add request workflow.
 *
 * <p><b>Permission model</b>
 * <ul>
 *   <li>Admins (AD group {@code pdl-plm-admin}) always see every tab. Cannot be
 *       restricted via this service.</li>
 *   <li>Non-admins default to seeing every <i>non-admin</i> tab. An entry in
 *       {@code data/user-permissions.json} can narrow that to an explicit
 *       allowlist of tab keys.</li>
 *   <li>Admin-only tabs are NEVER grantable to non-admins. Even the
 *       permissions-admin allowlist (currently Vikas Singh) cannot grant them.
 *       This is enforced server-side in every mutation path, not just the UI.</li>
 *   <li>The "User Permissions" tab itself is admin-only-ish: visible to admins
 *       and to anyone in {@code app.permissions.allowed-users} (Vikas Singh).</li>
 * </ul>
 *
 * <p><b>DL-add workflow</b>: a permissions-admin can search org-wide AD for a
 * user who is NOT yet in the access DL, pre-configure their tab allowlist, and
 * submit a request to IT. We immediately persist the permissions record (so the
 * user gets the right tabs the moment they first log in after IT adds them to
 * the DL) and email {@code pdl-plm-admin@sandisk.com} with the ask. IT's only
 * job is the AD DL membership change.
 */
@Service
public class UserPermissionsService {

    private static final Logger logger = Logger.getLogger(UserPermissionsService.class.getName());

    private static final String PERMS_FILE = "./data/user-permissions.json";
    /** Instance field (not a constant) so unit tests can point it at a temp file. */
    private String rolesFilePath = "./data/user-roles.json";

    /**
     * Per-user role (NOT a tab): holders get the "Engineering" mode inside the
     * AI Help drawer — in-depth answers about how the toolkit is built. Grants
     * and revokes are restricted to PLM IT ({@code app.roles.engineering-insider.granters}),
     * which is deliberately STRICTER than isPlmAdmin / isPermissionsAdmin.
     */
    public static final String ROLE_ENGINEERING_INSIDER = "engineering-insider";

    /**
     * Catalog of every tab in the app. {@code adminOnly=true} → only PLM admins
     * see it (and it's never grantable). {@code permissionsAdmin=true} → also
     * visible to the {@code app.permissions.allowed-users} allowlist (Vikas
     * Singh) but otherwise behaves like an admin-only tab (not grantable). Keep
     * this list in sync with {@code TAB_REGISTRY} in app.js.
     */
    // Labels reflect the post-consolidation nav: four "Items → ..." sub-tabs
    // and two "BOM → ..." sub-tabs roll up under their parent in the top nav.
    // Admins toggle these keys individually here in User Management; the
    // user-facing nav hides each sub-tab button when its key isn't allowed.
    public static final List<TabDef> TAB_CATALOG = Collections.unmodifiableList(Arrays.asList(
        new TabDef("fields",        "Items \u2192 Field Changes", false, false),
        new TabDef("parts",         "Items \u2192 Part Extract",  false, false),
        new TabDef("agile",         "Items \u2192 Agile Lookup",  false, false),
        new TabDef("sku",           "Items \u2192 SKU Lookup",    false, false),
        new TabDef("bom",           "BOM \u2192 Explorer",        false, false),
        new TabDef("bomcompare",    "BOM \u2192 Compare",         false, false),
        new TabDef("history",       "Change History",   false, false),
        new TabDef("ecotimeline",   "ECO Timeline",     false, false),   // 2026-06-18: visible to all (Planning group); per-user grantable in User Management
        new TabDef("ecnreport",     "ECN Dashboard",    false, false),
        new TabDef("docreview",     "Review Tracker",   false, false),
        new TabDef("ims-review",    "IMS Review",       false, false),
        // owner-audit was a separate key briefly (2026-06-05); folded into
        // ims-review as a sub-view per Vikas's recommendation that anyone
        // with IMS Dashboard access should see the owner-change audit.
        new TabDef("sdsm",          "Shop-Floor Docs",  false, false),
        new TabDef("singlesole",    "Single/Sole Source Report", false, false),  // 2026-05-11: released to all PLM admins; 2026-06-02: opened to per-user grant (server-side gate moved to tab-permission check on SingleSoleSourceController)
        new TabDef("helpcenter",    "More \u2192 Work Instructions", false, false),
        new TabDef("permissions",   "More \u2192 User Management",   false, true),
        new TabDef("extensions",    "More \u2192 Extensions",         true,  false),
        new TabDef("compare",       "More \u2192 Data Compare",       false, false),
        new TabDef("changereviews", "More \u2192 Change Reviews",     false, false),
        new TabDef("reports",       "Utilities",        true,  false),
        new TabDef("adhealth",      "AD Health",        true,  false),
        new TabDef("aieval",        "AI Eval",          false, false),
        new TabDef("it-enhancements", "IT Enhancements", false, false)   // 2026-06-04: per-user grantable + auto-grant for the PDL-PLM-admin AD group
    ));

    /** Display groups for the Add-from-AD picker, in render order. */
    public static final List<String> GROUP_ORDER = Collections.unmodifiableList(Arrays.asList(
        "Items", "BOM", "Change & ECO", "Reports & Docs", "More / Tools"));

    /** Tab key -> group name. A unit test enforces every grantable tab is mapped,
     *  so a newly added tab that's left out here fails the build. */
    private static final LinkedHashMap<String, String> TAB_GROUPS = buildTabGroups();

    private static LinkedHashMap<String, String> buildTabGroups() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String k : new String[]{"fields", "parts", "agile", "sku"}) m.put(k, "Items");
        for (String k : new String[]{"bom", "bomcompare"}) m.put(k, "BOM");
        for (String k : new String[]{"history", "ecotimeline", "ecnreport", "changereviews", "docreview", "ims-review"}) m.put(k, "Change & ECO");
        for (String k : new String[]{"singlesole", "sdsm"}) m.put(k, "Reports & Docs");
        for (String k : new String[]{"helpcenter", "compare", "aieval", "it-enhancements"}) m.put(k, "More / Tools");
        return m;
    }

    /** One-click tab bundle for the Add-from-AD picker. */
    public static class Preset {
        public final String id;
        public final String label;
        public final List<String> tabKeys;
        public Preset(String id, String label, List<String> tabKeys) {
            this.id = id;
            this.label = label;
            this.tabKeys = Collections.unmodifiableList(new ArrayList<>(tabKeys));
        }
    }

    public Map<String, String> getTabGroups() {
        return Collections.unmodifiableMap(TAB_GROUPS);
    }

    public List<String> getGroupOrder() {
        return GROUP_ORDER;
    }

    /** Presets in render order. "Full access" resolves to every grantable tab. */
    public List<Preset> getPresets() {
        List<Preset> p = new ArrayList<>();
        p.add(new Preset("blank", "Blank", new ArrayList<>()));
        p.add(new Preset("viewer", "Viewer",
            Arrays.asList("agile", "sku", "history", "ecotimeline", "ecnreport", "ims-review")));
        p.add(new Preset("items", "Items team",
            Arrays.asList("fields", "parts", "agile", "sku", "bom", "bomcompare")));
        p.add(new Preset("reporting", "Reporting",
            Arrays.asList("history", "ecotimeline", "ecnreport", "singlesole", "docreview")));
        p.add(new Preset("full", "Full access", new ArrayList<>(grantableTabKeys())));
        return p;
    }

    private final ObjectMapper om = new ObjectMapper();
    private volatile PermissionsFile state = new PermissionsFile();
    private volatile RolesFile rolesState = new RolesFile();

    /** PLM IT allowlist for granting/revoking the engineering-insider role.
     *  Comma-separated usernames and/or AD group CNs (matched lower-cased against
     *  the session's adGroups). Same config pattern as app.maintenance.allowed-users.
     *  Deliberately NOT defaulted to a broad AD group: general PLM admins and
     *  permissions admins must not be able to grant this role. */
    @Value("${app.roles.engineering-insider.granters:8252,25868,plmadmin}")
    private String engInsiderGrantersCsv;

    @Value("${app.permissions.allowed-users:}")
    private String permissionsAllowedUsersCsv;

    /** IT allowlist — same property used by MaintenanceService. Members can see itOnly tabs
     *  (pre-release features under IT review). Defaults to Vikas Jindal + Krati Jain + emergency admin. */
    @Value("${app.maintenance.allowed-users:8252,25868,plmadmin}")
    private String itAllowedUsersCsv;

    /** AD group whose members get the {@code it-enhancements} tab auto-granted
     *  (no User Management click required). Lower-cased CN matched against the
     *  user's session {@code adGroups} set populated at login. Defaults to the
     *  PLM IT team DL. Configurable on prod without redeploy. */
    @Value("${app.it-enhancements.auto-grant-ad-group:pdl-plm-admin}")
    private String itEnhancementsAutoGrantGroup;

    @Value("${app.admin-email:pdl-plm-admin@sandisk.com}")
    private String itEmail;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String mailFrom;

    @Value("${app.maintenance.app-url:http://uls-ep-aglipccb:8090}")
    private String appUrl;

    /** Same property the old AccessRequest email uses — the access DL is the first entry. */
    @Value("${ldap.required.groups:}")
    private String requiredGroups;

    /** AD self-service base URL for the "Add to group" button. Override per env if it changes. */
    @Value("${app.permissions.ad-self-service-url:https://anywhere.sandisk.com/ad-group-info/}")
    private String adSelfServiceBaseUrl;

    @Autowired private ActivityLogger activityLogger;
    @Autowired private LdapAuthService ldapAuthService;
    @Autowired private ExtensionPermissionService extensionPermissionService;

    @PostConstruct
    public void init() {
        load();
        loadRoles();
    }

    // ------------------------------------------------------------------
    // Public API — per-user roles (engineering-insider)
    // ------------------------------------------------------------------

    /** True if the user holds the given role. Case-insensitive on username. */
    public boolean hasRole(String username, String role) {
        if (username == null || username.isEmpty() || role == null) return false;
        List<String> holders = rolesState.roles.get(role);
        if (holders == null) return false;
        String key = normalizeKey(username);
        for (String h : holders) {
            if (normalizeKey(h).equals(key)) return true;
        }
        return false;
    }

    /**
     * True if the user is PLM IT for role-granting purposes. Checks the
     * {@code app.roles.engineering-insider.granters} CSV against the username
     * AND against the caller's AD group CNs (lower-cased, as captured in the
     * session at login). This is intentionally stricter than isPlmAdmin /
     * isPermissionsAdmin — neither of those implies PLM IT.
     */
    public boolean isPlmIt(String username, Set<String> adGroups) {
        if (engInsiderGrantersCsv == null || engInsiderGrantersCsv.trim().isEmpty()) return false;
        Set<String> groupsLower = new LinkedHashSet<>();
        if (adGroups != null) {
            for (String g : adGroups) {
                if (g != null) groupsLower.add(g.trim().toLowerCase());
            }
        }
        for (String entry : engInsiderGrantersCsv.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            if (username != null && username.equalsIgnoreCase(e)) return true;
            if (groupsLower.contains(e.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Grant a role. PLM IT only — throws {@link SecurityException} otherwise
     * (enforced HERE, server-side, so no UI or crafted POST can bypass it).
     * Idempotent: re-granting an existing holder just refreshes the audit stamp.
     */
    public synchronized void grantRole(String granter, Set<String> granterAdGroups,
                                       String granterDisplay, String username, String role) {
        requirePlmIt(granter, granterAdGroups, role);
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        List<String> holders = rolesState.roles.computeIfAbsent(role, k -> new ArrayList<>());
        if (!hasRole(username, role)) holders.add(username.trim());
        stampRoleChange(role, username, granter, granterDisplay, "grant");
        saveRoles();
        activityLogger.log(granter, granterDisplay, "ROLE_GRANT",
            "role=" + role + " user=" + username + " granter=" + granter);
    }

    /** Revoke a role. PLM IT only — throws {@link SecurityException} otherwise. */
    public synchronized void revokeRole(String granter, Set<String> granterAdGroups,
                                        String granterDisplay, String username, String role) {
        requirePlmIt(granter, granterAdGroups, role);
        List<String> holders = rolesState.roles.get(role);
        String key = normalizeKey(username);
        boolean removed = holders != null && holders.removeIf(h -> normalizeKey(h).equals(key));
        if (removed) {
            stampRoleChange(role, username, granter, granterDisplay, "revoke");
            saveRoles();
        }
        activityLogger.log(granter, granterDisplay, "ROLE_REVOKE",
            "role=" + role + " user=" + username + " granter=" + granter);
    }

    /** Current holders of a role (usernames as stored). */
    public Set<String> roleHolders(String role) {
        List<String> holders = rolesState.roles.get(role);
        return holders == null ? Collections.emptySet() : new LinkedHashSet<>(holders);
    }

    /** Last grant/revoke audit for a role+user, or null. Keys: at, by, byDisplay, action. */
    public Map<String, Object> roleLastChange(String role, String username) {
        RoleAudit a = rolesState.lastChange.get(role + "|" + normalizeKey(username));
        if (a == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", a.at);
        out.put("by", a.by);
        out.put("byDisplay", a.byDisplay);
        out.put("action", a.action);
        return out;
    }

    private void requirePlmIt(String granter, Set<String> granterAdGroups, String role) {
        if (!isPlmIt(granter, granterAdGroups)) {
            throw new SecurityException("Only PLM IT can grant or revoke the " + role + " role.");
        }
    }

    private void stampRoleChange(String role, String username, String by, String byDisplay, String action) {
        RoleAudit a = new RoleAudit();
        a.by = by;
        a.byDisplay = byDisplay;
        a.at = nowStamp();
        a.action = action;
        rolesState.lastChange.put(role + "|" + normalizeKey(username), a);
    }

    private synchronized void loadRoles() {
        File f = new File(rolesFilePath);
        if (!f.exists()) {
            rolesState = new RolesFile();
            return;
        }
        try {
            rolesState = om.readValue(f, RolesFile.class);
            if (rolesState == null) rolesState = new RolesFile();
            if (rolesState.roles == null) rolesState.roles = new LinkedHashMap<>();
            if (rolesState.lastChange == null) rolesState.lastChange = new LinkedHashMap<>();
            logger.info("[PERMS] Loaded roles: " + rolesState.roles.size() + " role(s).");
        } catch (Exception e) {
            logger.warning("[PERMS] Failed to load " + rolesFilePath + ": " + e.getMessage()
                + " — starting fresh.");
            rolesState = new RolesFile();
        }
    }

    private synchronized void saveRoles() {
        try {
            File f = new File(rolesFilePath);
            f.getParentFile().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(f, rolesState);
        } catch (Exception e) {
            logger.warning("[PERMS] roles save failed: " + e.getMessage());
        }
    }

    /** Test hook: override the granters CSV without Spring. */
    void setEngInsiderGrantersCsvForTest(String csv) {
        this.engInsiderGrantersCsv = csv;
    }

    /** Test hook: point the roles store at a temp file so tests never touch ./data. */
    void setRolesFilePathForTest(String path) {
        this.rolesFilePath = path;
    }

    // ------------------------------------------------------------------
    // Public API — querying permissions
    // ------------------------------------------------------------------

    /**
     * The set of tab keys this user is allowed to see, AFTER applying admin
     * status, the per-user record (if any), and the hard rule that admin-only
     * tabs require admin role. Result includes the "permissions" tab when
     * appropriate.
     *
     * <p><b>Admins always see every tab.</b> Per-user records are completely
     * ignored when {@code isAdmin} is true — defense against a permissions-admin
     * (Vikas Singh) writing a record for an admin user that would otherwise
     * narrow their non-admin tabs. The mutation paths block this too, but we
     * also defend on read so a stale or hand-crafted record can't restrict an
     * admin's view.
     */
    public Set<String> getAllowedTabs(String username, boolean isAdmin) {
        return getAllowedTabs(username, isAdmin, Collections.<String>emptySet());
    }

    /**
     * Overload that accepts the caller's AD group membership (lower-cased CNs)
     * so AD-group-driven tab grants can be applied. Stays opt-in: callers that
     * don't have the group list pass an empty set (or use the legacy 2-arg
     * overload) and AD-group-only tabs are simply not granted in that case.
     */
    public Set<String> getAllowedTabs(String username, boolean isAdmin, Set<String> adGroups) {
        if (adGroups == null) adGroups = Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        boolean isPermsAdmin = isAdmin || isPermissionsAdmin(username);
        boolean isIt = isItAdmin(username);

        boolean isExtContributor = !isAdmin && extensionPermissionService != null
            && extensionPermissionService.isContributor(username);

        // 2026-06-04: AD-group-driven auto-grants. Lower-cased CN check against
        // the session-captured group list (populated at login by AuthController
        // from LdapAuthService.AuthResult.getAdGroups). Today this drives one
        // tab; future additions are one-liners here + a matching @Value knob.
        boolean isInItEnhancementsGroup = itEnhancementsAutoGrantGroup != null
                && !itEnhancementsAutoGrantGroup.trim().isEmpty()
                && adGroups.contains(itEnhancementsAutoGrantGroup.trim().toLowerCase());

        for (TabDef t : TAB_CATALOG) {
            // itOnly tabs: even admins are blocked unless they're in the IT allowlist.
            // Used for pre-release features under internal review.
            if (t.itOnly && !isIt) continue;
            if (t.adminOnly) {
                if (isAdmin) {
                    out.add(t.key);
                } else if ("extensions".equals(t.key) && isExtContributor) {
                    // Extensions tab is admin-only by default, but explicit
                    // contributors (added via /external-sources/permissions) also
                    // need to see it so they can add/manage their own sources.
                    out.add(t.key);
                }
                continue;
            }
            if (t.permissionsAdmin) {
                if (isPermsAdmin) out.add(t.key);
                continue;
            }
            // Regular tab.
            if (isAdmin) {
                out.add(t.key);                 // admins always see every non-admin tab
                continue;
            }
            // AD-group auto-grant for IT Enhancements (PDL-PLM-admin members).
            // This wins over a managedExplicitly record that omits the tab —
            // the group membership is the authoritative source for this tab.
            if ("it-enhancements".equals(t.key) && isInItEnhancementsGroup) {
                out.add(t.key);
                continue;
            }
            UserRecord rec = state.users.get(normalizeKey(username));
            if (rec != null && rec.managedExplicitly) {
                Set<String> allow = rec.allowedTabs == null ? Collections.emptySet()
                                                            : new LinkedHashSet<>(rec.allowedTabs);
                if (allow.contains(t.key)) out.add(t.key);
            } else {
                out.add(t.key);
            }
        }
        return out;
    }

    public boolean isPermissionsAdmin(String username) {
        if (username == null || username.isEmpty()) return false;
        if (permissionsAllowedUsersCsv == null) return false;
        for (String a : permissionsAllowedUsersCsv.split(",")) {
            if (username.equalsIgnoreCase(a.trim())) return true;
        }
        return false;
    }

    /** True if the user is in the IT allowlist (same as Maintenance allowlist).
     *  Members can see itOnly tabs (pre-release features under IT review). */
    public boolean isItAdmin(String username) {
        if (username == null || username.isEmpty()) return false;
        if (itAllowedUsersCsv == null || itAllowedUsersCsv.trim().isEmpty()) return false;
        for (String a : itAllowedUsersCsv.split(",")) {
            if (username.equalsIgnoreCase(a.trim())) return true;
        }
        return false;
    }

    public List<TabDef> getTabCatalog() {
        return TAB_CATALOG;
    }

    /**
     * Returns the AD self-service "Add to {group}" URL used in the DL-request
     * email's CTA, so the User Permissions tab can show admins the same button.
     * Empty string when {@code ldap.required.groups} isn't configured.
     */
    public String getAdAddUrl() {
        String groupName = "";
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            groupName = requiredGroups.split(",")[0].trim();
        }
        if (groupName.isEmpty()) return "";
        return adSelfServiceBaseUrl + groupName;
    }

    /** Returns the access-DL group name (first entry of ldap.required.groups). */
    public String getAccessGroupName() {
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            return requiredGroups.split(",")[0].trim();
        }
        return "";
    }

    /** Best-effort email lookup by AD username; null if user not in our records. */
    public String getEmailForUsername(String username) {
        if (username == null || username.isEmpty()) return null;
        UserRecord rec = state.users.get(normalizeKey(username));
        if (rec == null) return null;
        return (rec.email == null || rec.email.isEmpty()) ? null : rec.email;
    }

    /** Best-effort displayName lookup by AD username; null if user not in our records. */
    public String getDisplayNameForUsername(String username) {
        if (username == null || username.isEmpty()) return null;
        UserRecord rec = state.users.get(normalizeKey(username));
        if (rec == null) return null;
        return rec.displayName;
    }

    /** Tab keys that a non-admin can be granted via the UI — admin-only tabs filtered out. */
    public Set<String> grantableTabKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (TabDef t : TAB_CATALOG) {
            if (!t.adminOnly && !t.permissionsAdmin) out.add(t.key);
        }
        return out;
    }

    public Map<String, UserRecord> allRecords() {
        return Collections.unmodifiableMap(state.users);
    }

    public List<PendingRequest> pendingRequests() {
        return Collections.unmodifiableList(state.pendingDLRequests);
    }

    /**
     * Scan the activity log JSONL file for unique LOGIN events and return one
     * entry per user with their last-login timestamp. Cheap enough for the
     * 50-100 active users we have; if it grows past a few thousand we'd want
     * a tail-only index but we're nowhere near that.
     */
    public Map<String, LoginInfo> loginHistory() {
        Map<String, LoginInfo> out = new LinkedHashMap<>();
        File f = new File("./data/activity-log.jsonl");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || !line.contains("\"action\":\"LOGIN\"")) continue;
                try {
                    Map<String, Object> ev = om.readValue(line, new TypeReference<Map<String, Object>>() {});
                    String username = (String) ev.get("username");
                    if (username == null || username.isEmpty()) continue;
                    String ts = (String) ev.get("timestamp");
                    String displayName = (String) ev.get("displayName");
                    String key = normalizeKey(username);
                    LoginInfo prev = out.get(key);
                    if (prev == null || (ts != null && ts.compareTo(prev.lastLoginTs) > 0)) {
                        LoginInfo li = new LoginInfo();
                        li.username = username;
                        li.displayName = displayName;
                        li.lastLoginTs = ts == null ? "" : ts;
                        li.loginCount = (prev == null ? 0 : prev.loginCount) + 1;
                        out.put(key, li);
                    } else {
                        prev.loginCount += 1;
                    }
                } catch (Exception parseErr) {
                    // skip malformed line — activity log appends are append-only and very stable
                }
            }
        } catch (Exception e) {
            logger.warning("[PERMS] Failed to read activity log: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Public API — mutating permissions
    // ------------------------------------------------------------------

    /**
     * Save (or replace) a user's tab allowlist. Silently strips admin-only
     * tabs from the requested list — the hard rule that admin tabs are not
     * grantable to non-admins is enforced here, not just in the UI, so a
     * crafted POST can't bypass it.
     */
    public synchronized UserRecord upsertUser(String sAMAccountName, String displayName, String email,
                                              List<String> requestedTabs,
                                              String actorUsername, String actorDisplayName) {
        if (sAMAccountName == null || sAMAccountName.isEmpty()) {
            throw new IllegalArgumentException("sAMAccountName is required");
        }
        // Admin tabs are non-editable (period). PLM admins always see every tab
        // and a permissions-admin (Vikas Singh) cannot narrow that. Reject the
        // call entirely rather than silently dropping it — the UI should never
        // attempt this and an API client that does deserves an explicit error.
        if (isTargetAdmin(sAMAccountName)) {
            throw new IllegalArgumentException(
                "PLM admins always see every tab and cannot have their tab visibility restricted. "
                + "Remove them from the pdl-plm-admin AD group to revoke admin access.");
        }
        Set<String> safe = new LinkedHashSet<>();
        Set<String> grantable = grantableTabKeys();
        if (requestedTabs != null) {
            for (String t : requestedTabs) {
                if (grantable.contains(t)) safe.add(t);
            }
        }
        String key = normalizeKey(sAMAccountName);
        UserRecord rec = state.users.get(key);
        String now = nowStamp();
        if (rec == null) {
            rec = new UserRecord();
            rec.sAMAccountName = sAMAccountName;
            rec.createdAt = now;
            rec.createdBy = actorUsername;
            rec.createdByDisplay = actorDisplayName;
            state.users.put(key, rec);
        }
        if (displayName != null && !displayName.isEmpty()) rec.displayName = displayName;
        if (email != null && !email.isEmpty()) rec.email = email;
        rec.managedExplicitly = true;
        rec.allowedTabs = new ArrayList<>(safe);
        rec.updatedAt = now;
        rec.updatedBy = actorUsername;
        rec.updatedByDisplay = actorDisplayName;
        save();
        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_UPDATE",
            "user=" + sAMAccountName + " allowed=" + String.join(",", safe));
        return rec;
    }

    /**
     * Reset a user back to defaults (sees all non-admin tabs). Removes the
     * record entirely so it doesn't clutter the file with empty no-op rows.
     */
    public synchronized boolean resetUser(String sAMAccountName,
                                          String actorUsername, String actorDisplayName) {
        // Reset is allowed for everyone — even an admin row (which shouldn't
        // exist, but cleaning up a stale one is fine).
        UserRecord rec = state.users.remove(normalizeKey(sAMAccountName));
        if (rec == null) return false;
        save();
        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_RESET",
            "user=" + sAMAccountName);
        return true;
    }

    /**
     * Cheap admin-membership check using the cached set from
     * {@link LdapAuthService#listAdminUsernames()}. Falls back to false if
     * the LDAP cache is empty (e.g. service first boot before AD reachable);
     * in that case the upsert proceeds, which is a fail-open we accept because
     * the alternative (blocking all upserts when AD is briefly unreachable)
     * would lock out the entire User Permissions tab.
     */
    private boolean isTargetAdmin(String sAMAccountName) {
        if (sAMAccountName == null) return false;
        try {
            Set<String> admins = ldapAuthService.listAdminUsernames();
            return admins != null && admins.contains(sAMAccountName.toLowerCase());
        } catch (Exception e) {
            logger.warning("[PERMS] admin check failed for " + sAMAccountName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Submit a "please add this user to the access DL" request. Saves the
     * pre-configured tab allowlist immediately (so the user gets the right
     * tabs on first login) and emails IT. Idempotent on the pending list:
     * a second submit for the same user replaces the existing pending entry.
     */
    public synchronized PendingRequest submitDLRequest(String sAMAccountName, String displayName,
                                                       String email, List<String> requestedTabs,
                                                       String actorUsername, String actorDisplayName) {
        // 1. Save permissions record so they have the right tabs on first login.
        //    upsertUser strips admin-only tabs from the list.
        UserRecord saved = upsertUser(sAMAccountName, displayName, email, requestedTabs,
                                       actorUsername, actorDisplayName);

        // 2. Pending request entry (replaces any prior entry for this user).
        //    Mirror the SAVED tabs (post-filter) into the request, so the
        //    pending-list view + the IT email show what will actually be
        //    granted, not what was requested. Otherwise an admin who casually
        //    checked an admin-only box would see it in the pending list and
        //    in the IT email even though it was silently dropped.
        String key = normalizeKey(sAMAccountName);
        state.pendingDLRequests.removeIf(p -> normalizeKey(p.sAMAccountName).equals(key));
        PendingRequest req = new PendingRequest();
        req.sAMAccountName = sAMAccountName;
        req.displayName = displayName;
        req.email = email;
        req.requestedTabs = saved.allowedTabs == null ? new ArrayList<>() : new ArrayList<>(saved.allowedTabs);
        req.requestedBy = actorUsername;
        req.requestedByDisplay = actorDisplayName;
        req.requestedAt = nowStamp();
        req.status = "pending";
        state.pendingDLRequests.add(req);
        save();

        // 3. Email IT
        try {
            sendDLRequestEmail(req);
        } catch (Exception e) {
            logger.warning("[PERMS] DL request email failed: " + e.getMessage());
        }

        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_DL_REQUEST",
            "user=" + sAMAccountName + " tabs=" + String.join(",", req.requestedTabs));
        return req;
    }

    /** Per-user result of a bulk import submit. */
    public static class BulkOutcome {
        public String sAMAccountName;
        public String displayName;
        public boolean ok;
        public boolean emailedToIt;   // true = a DL-add request went to IT for this user
        public String error;   // null when ok
    }

    /** Result of splitting a submit batch by current access-DL membership. */
    public static class DlPartition {
        public List<Map<String, String>> needsDl = new ArrayList<>();
        public List<Map<String, String>> alreadyInDl = new ArrayList<>();
    }

    /**
     * Split users into those already in the access DL (tabs only — IT has nothing
     * to do) and those who still need a DL add (tabs + pending request + IT email).
     * {@code dlUsernamesLower} is a lowercase set; a null/empty set fail-safes to
     * "everyone needs DL" so we never silently skip emailing IT.
     */
    static DlPartition partitionByDl(List<Map<String, String>> users, Set<String> dlUsernamesLower) {
        DlPartition out = new DlPartition();
        if (users == null) return out;
        for (Map<String, String> u : users) {
            String sam = u == null ? "" : u.getOrDefault("sAMAccountName", "");
            String key = normalizeKey(sam == null ? "" : sam);
            if (dlUsernamesLower != null && dlUsernamesLower.contains(key)) out.alreadyInDl.add(u);
            else out.needsDl.add(u);
        }
        return out;
    }

    /**
     * Bulk variant of {@link #submitDLRequest}. Tab grants are saved for EVERY user
     * in the batch regardless of DL membership. A pending DL request and inclusion
     * in the single consolidated IT email happen ONLY for users not already in the
     * access DL. DL membership is determined via
     * {@link LdapAuthService#listAccessGroupCandidates()} — the same source the
     * {@code /ad-search} endpoint uses to set the {@code alreadyInDL} flag — so the
     * server's email decision matches the "In access DL" / "Email IT" pill the admin
     * sees in the UI. Do NOT switch to a different membership source, or that pill
     * and the actual email behaviour would diverge. Fail-safe: if the DL lookup
     * throws or returns empty (AD down), all users fall to needs-DL so IT is still
     * emailed rather than silently skipped. Rows that fail individually (e.g. target
     * is a PLM admin) are collected in the result; the rest still go through.
     *
     * @param users       list of maps with keys sAMAccountName, displayName, email
     * @param allowedTabs shared tab set applied to every user
     */
    public synchronized List<BulkOutcome> submitBulkDLRequest(List<Map<String, String>> users,
                                                              List<String> allowedTabs,
                                                              String actorUsername, String actorDisplayName) {
        // Authoritative DL membership (cached ~1h). Fail-safe: empty set => everyone needs DL.
        Set<String> dlLower = new LinkedHashSet<>();
        try {
            for (com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser m : ldapAuthService.listAccessGroupCandidates()) {
                if (m != null && m.username != null) dlLower.add(m.username.trim().toLowerCase());
            }
        } catch (Exception e) {
            logger.warning("[PERMS] DL membership lookup failed; treating all as needs-DL: " + e.getMessage());
        }
        DlPartition part = partitionByDl(users == null ? new ArrayList<>() : users, dlLower);

        List<BulkOutcome> outcomes = new ArrayList<>();
        List<PendingRequest> created = new ArrayList<>();

        // Already in DL: save tabs only, no pending request, no IT email.
        for (Map<String, String> u : part.alreadyInDl) {
            outcomes.add(applyOne(u, allowedTabs, actorUsername, actorDisplayName, false, null));
        }
        // Needs DL: save tabs + create pending request (collected for one consolidated email).
        for (Map<String, String> u : part.needsDl) {
            outcomes.add(applyOne(u, allowedTabs, actorUsername, actorDisplayName, true, created));
        }
        save();

        if (!created.isEmpty()) {
            try {
                sendBulkDLRequestEmail(created);
            } catch (Exception e) {
                logger.warning("[PERMS] bulk DL request email failed: " + e.getMessage());
            }
        }

        int ok = 0, emailed = 0;
        for (BulkOutcome o : outcomes) { if (o.ok) ok++; if (o.emailedToIt) emailed++; }
        int total = part.alreadyInDl.size() + part.needsDl.size();
        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_BULK_IMPORT",
            "submitted=" + total + " ok=" + ok + " emailed=" + emailed + " failed=" + (total - ok)
            + " tabs=" + String.join(",", allowedTabs == null ? new ArrayList<>() : allowedTabs));
        return outcomes;
    }

    /** Upsert one user's tabs; when {@code needsDl}, also create+collect a pending request. */
    private BulkOutcome applyOne(Map<String, String> u, List<String> allowedTabs,
                                 String actorUsername, String actorDisplayName,
                                 boolean needsDl, List<PendingRequest> collect) {
        String sam = u.get("sAMAccountName");
        String name = u.get("displayName");
        String email = u.get("email");
        BulkOutcome oc = new BulkOutcome();
        oc.sAMAccountName = sam;
        oc.displayName = name;
        try {
            UserRecord saved = upsertUser(sam, name, email, allowedTabs, actorUsername, actorDisplayName);
            if (needsDl) {
                String key = normalizeKey(sam);
                state.pendingDLRequests.removeIf(p -> normalizeKey(p.sAMAccountName).equals(key));
                PendingRequest req = new PendingRequest();
                req.sAMAccountName = sam;
                req.displayName = name;
                req.email = email;
                req.requestedTabs = saved.allowedTabs == null ? new ArrayList<>() : new ArrayList<>(saved.allowedTabs);
                req.requestedBy = actorUsername;
                req.requestedByDisplay = actorDisplayName;
                req.requestedAt = nowStamp();
                req.status = "pending";
                state.pendingDLRequests.add(req);
                if (collect != null) collect.add(req);
                oc.emailedToIt = true;
            }
            oc.ok = true;
        } catch (Exception e) {
            oc.ok = false;
            oc.error = e.getMessage();
        }
        return oc;
    }

    private void sendBulkDLRequestEmail(List<PendingRequest> reqs) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(mailFrom));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(itEmail));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(
            "PLM Toolkit · Please add " + reqs.size() + " user" + (reqs.size() == 1 ? "" : "s") + " to the access DL"));
        msg.setContent(buildBulkDLRequestHtml(reqs), "text/html; charset=utf-8");
        javax.mail.Transport.send(msg);
    }

    /**
     * Called from AuthController on successful login. If the logged-in user
     * appears in the pending DL request list, mark that request "completed" —
     * IT did their part and the user can now log in.
     */
    public synchronized void notifyLogin(String sAMAccountName) {
        if (sAMAccountName == null) return;
        String key = normalizeKey(sAMAccountName);
        boolean changed = false;
        for (PendingRequest p : state.pendingDLRequests) {
            if (normalizeKey(p.sAMAccountName).equals(key) && "pending".equals(p.status)) {
                p.status = "completed";
                p.completedAt = nowStamp();
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Reconcile pending DL requests against the current access-DL roster from
     * LDAP (cached ~1 hour by {@link LdapAuthService#listAccessGroupCandidates}).
     * Any pending request whose target user already appears in the DL gets
     * flipped to "completed" so the panel doesn't keep nagging IT after they've
     * actually done the add — even before the user logs in for the first time.
     *
     * <p>Caller passes a normalised lowercase set so we don't pay the lowercase
     * cost N times here. Returns the number of requests closed.
     */
    public synchronized List<PendingRequest> reconcilePendingAgainstDl(Set<String> dlUsernamesLower) {
        List<PendingRequest> closedNow = new ArrayList<>();
        if (dlUsernamesLower == null || dlUsernamesLower.isEmpty()) return closedNow;
        for (PendingRequest p : state.pendingDLRequests) {
            if (!"pending".equals(p.status)) continue;
            if (dlUsernamesLower.contains(normalizeKey(p.sAMAccountName))) {
                p.status = "completed";
                p.completedAt = nowStamp();
                closedNow.add(p);
                activityLogger.log("system", "PLM Toolkit", "PERMISSIONS_DL_AUTO_COMPLETED",
                    "user=" + p.sAMAccountName + " — detected in access DL via LDAP");
            }
        }
        if (!closedNow.isEmpty()) save();
        return closedNow;
    }

    /**
     * Stamp welcomeSentAt/welcomeSentBy on a completed pending request after
     * the welcome invite email goes out. Idempotent: most-recent send wins.
     * Returns true if a matching request was found and stamped.
     */
    public synchronized boolean markWelcomeSent(String sAMAccountName, String sentByUsername, String sentByDisplay) {
        if (sAMAccountName == null) return false;
        String key = normalizeKey(sAMAccountName);
        for (PendingRequest p : state.pendingDLRequests) {
            if (normalizeKey(p.sAMAccountName).equals(key)) {
                p.welcomeSentAt = nowStamp();
                p.welcomeSentBy = sentByUsername;
                p.welcomeSentByDisplay = sentByDisplay;
                if (!"completed".equals(p.status)) {
                    p.status = "completed";
                    if (p.completedAt == null || p.completedAt.isEmpty()) p.completedAt = nowStamp();
                }
                save();
                activityLogger.log(sentByUsername, sentByDisplay, "PERMISSIONS_DL_INVITE",
                    "user=" + sAMAccountName);
                return true;
            }
        }
        return false;
    }

    /** Lookup a pending request by AD username; returns null if none. */
    public synchronized PendingRequest findPendingByUsername(String sAMAccountName) {
        if (sAMAccountName == null) return null;
        String key = normalizeKey(sAMAccountName);
        for (PendingRequest p : state.pendingDLRequests) {
            if (normalizeKey(p.sAMAccountName).equals(key)) return p;
        }
        return null;
    }

    public synchronized void clearCompletedRequests(String actorUsername, String actorDisplayName) {
        int before = state.pendingDLRequests.size();
        state.pendingDLRequests.removeIf(p -> "completed".equals(p.status));
        if (state.pendingDLRequests.size() != before) {
            save();
            activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_CLEAR_COMPLETED",
                "removed=" + (before - state.pendingDLRequests.size()));
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private synchronized void load() {
        File f = new File(PERMS_FILE);
        if (!f.exists()) {
            state = new PermissionsFile();
            return;
        }
        try {
            state = om.readValue(f, PermissionsFile.class);
            if (state == null) state = new PermissionsFile();
            if (state.users == null) state.users = new LinkedHashMap<>();
            if (state.pendingDLRequests == null) state.pendingDLRequests = new ArrayList<>();
            logger.info("[PERMS] Loaded " + state.users.size() + " user records, "
                + state.pendingDLRequests.size() + " pending DL requests.");
        } catch (Exception e) {
            logger.warning("[PERMS] Failed to load " + PERMS_FILE + ": " + e.getMessage()
                + " — starting fresh.");
            state = new PermissionsFile();
        }
    }

    private synchronized void save() {
        try {
            File f = new File(PERMS_FILE);
            f.getParentFile().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(f, state);
        } catch (Exception e) {
            logger.warning("[PERMS] save failed: " + e.getMessage());
        }
    }

    private void sendDLRequestEmail(PendingRequest req) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(mailFrom));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(itEmail));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("PLM Toolkit \u00b7 Please add " + req.displayName + " to the access DL"));
        msg.setContent(buildDLRequestHtml(req), "text/html; charset=utf-8");
        javax.mail.Transport.send(msg);
    }

    private String buildDLRequestHtml(PendingRequest req) {
        StringBuilder tabsHtml = new StringBuilder();
        // Map tab keys back to friendly labels
        Map<String, String> labels = new LinkedHashMap<>();
        for (TabDef t : TAB_CATALOG) labels.put(t.key, t.label);
        for (String key : req.requestedTabs) {
            String label = labels.getOrDefault(key, key);
            tabsHtml.append("<li>").append(esc(label)).append("</li>");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<title>PLM Toolkit \u00b7 DL add request</title></head>"
            + "<body class=\"email-body\" style=\"margin:0;padding:24px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" class=\"email-card\" "
            + "style=\"max-width:600px;background:#fff;border:1px solid #E8E6DF;border-radius:8px;\">"
            + "<tr><td style=\"padding:18px 24px;border-bottom:1px solid #E8E6DF;font-size:12px;color:#6B7280;\">"
            + "<strong>PLM Toolkit</strong> &nbsp;/&nbsp; Access request "
            + "<span style=\"float:right;background:#e8f0fe;color:#1a3a5c;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:600;\">Action needed</span>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;\">Please add to AD DL</div>"
            + "<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;margin-bottom:14px;\">"
            + esc(req.displayName) + " needs PLM Toolkit access</div>"
            + "<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" style=\"font-size:13px;width:100%;border-collapse:collapse;margin-bottom:18px;\">"
            + detailRow("Name", req.displayName)
            + detailRow("AD username", req.sAMAccountName)
            + detailRow("Email", req.email)
            + detailRow("Requested by", req.requestedByDisplay + " (" + req.requestedBy + ")")
            + detailRow("Requested at", req.requestedAt)
            + "</table>"
            + "<div style=\"font-size:13px;color:#0F1720;line-height:1.55;margin-bottom:8px;\"><strong>Pre-configured tabs</strong> (already saved in PLM Toolkit; effective the moment AD propagates):</div>"
            + "<ul style=\"font-size:13px;color:#0F1720;margin:0 0 18px 22px;line-height:1.6;\">" + tabsHtml + "</ul>"
            + "<div style=\"background:#FAFAF7;border-left:3px solid #4a6fa5;border-radius:0 6px 6px 0;padding:14px 18px;font-size:13px;color:#0F1720;line-height:1.55;\">"
            + "<strong>What IT needs to do:</strong> add this user to the PLM Toolkit access DL in AD. "
            + "Nothing else \u2014 PLM Toolkit already has the tab visibility set up. The user will see the right tabs on their first login."
            + "</div>"
            + buildAdGroupCta()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;border-top:1px solid #E8E6DF;font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;\">"
            + nowStamp()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;background:#FAFAF7;border-top:1px solid #E8E6DF;\">"
            + "<span style=\"display:inline-block;border:1px solid #ececec;border-radius:20px;padding:3px 10px;font-size:11px;color:#6B7280;\">sandisk</span>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit \u00b7 Access request notification</div>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:2px;\">This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    /**
     * One consolidated "please add these users to the access DL" email body for
     * a bulk import. Lists each user + the shared tab set. Package-private so it
     * can be unit-tested without SMTP.
     */
    String buildBulkDLRequestHtml(List<PendingRequest> reqs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (TabDef t : TAB_CATALOG) labels.put(t.key, t.label);

        StringBuilder rows = new StringBuilder();
        for (PendingRequest req : reqs) {
            StringBuilder tabs = new StringBuilder();
            List<String> rt = req.requestedTabs == null ? new ArrayList<>() : req.requestedTabs;
            for (int i = 0; i < rt.size(); i++) {
                if (i > 0) tabs.append(", ");
                tabs.append(esc(labels.getOrDefault(rt.get(i), rt.get(i))));
            }
            rows.append("<tr>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:13px;\">")
                .append("<span style=\"color:#4a6fa5;font-weight:600;\">").append(esc(req.displayName)).append("</span>")
                .append("<div style=\"font-size:11px;color:#6B7280;\">").append(esc(req.sAMAccountName)).append("</div></td>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:13px;\">")
                .append(esc(req.email)).append("</td>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:12px;color:#0F1720;\">")
                .append(tabs.length() == 0 ? "<span style=\"color:#6B7280;\">(none)</span>" : tabs).append("</td>")
                .append("</tr>");
        }

        String reqBy = reqs.isEmpty() ? "" : (reqs.get(0).requestedByDisplay + " (" + reqs.get(0).requestedBy + ")");
        String when = reqs.isEmpty() ? nowStamp() : reqs.get(0).requestedAt;

        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<title>PLM Toolkit · Bulk DL add request</title></head>"
            + "<body class=\"email-body\" style=\"margin:0;padding:24px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" class=\"email-card\" "
            + "style=\"max-width:600px;background:#fff;border:1px solid #E8E6DF;border-radius:8px;\">"
            + "<tr><td style=\"padding:18px 24px;border-bottom:1px solid #E8E6DF;font-size:12px;color:#6B7280;\">"
            + "<strong>PLM Toolkit</strong> &nbsp;/&nbsp; Access request "
            + "<span style=\"float:right;background:#e8f0fe;color:#1a3a5c;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:600;\">Action needed</span>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;\">Please add to AD DL</div>"
            + "<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;margin-bottom:14px;\">"
            + reqs.size() + " user" + (reqs.size() == 1 ? "" : "s") + " need PLM Toolkit access</div>"
            + "<div style=\"font-size:13px;color:#0F1720;line-height:1.55;margin-bottom:12px;\">Requested by " + esc(reqBy)
            + " on " + esc(when) + ". Tabs are already saved in PLM Toolkit; each user sees them on first login.</div>"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;margin-bottom:18px;\">"
            + "<tr>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">User</th>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">Email</th>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">Tabs</th>"
            + "</tr>" + rows + "</table>"
            + "<div style=\"background:#FAFAF7;border-left:3px solid #4a6fa5;border-radius:0 6px 6px 0;padding:14px 18px;font-size:13px;color:#0F1720;line-height:1.55;\">"
            + "<strong>What IT needs to do:</strong> add these users to the PLM Toolkit access DL in AD. "
            + "PLM Toolkit already has tab visibility set up for each."
            + "</div>"
            + buildAdGroupCta()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;border-top:1px solid #E8E6DF;font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;\">"
            + nowStamp()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;background:#FAFAF7;border-top:1px solid #E8E6DF;\">"
            + "<span style=\"display:inline-block;border:1px solid #ececec;border-radius:20px;padding:3px 10px;font-size:11px;color:#6B7280;\">sandisk</span>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit · Access request notification</div>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:2px;\">This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String detailRow(String label, String value) {
        return "<tr><td style=\"width:35%;font-weight:600;color:#6B7280;white-space:nowrap;padding:6px 10px;border-bottom:1px solid #E8E6DF;\">"
            + esc(label) + "</td><td style=\"word-break:break-word;padding:6px 10px;border-bottom:1px solid #E8E6DF;\">"
            + esc(value) + "</td></tr>";
    }

    /**
     * Renders a centered "Add to &lt;groupName&gt;" call-to-action button just
     * like the old AccessRequest email had. Links to the AD self-service page
     * for that group so IT can confirm membership and add the user with one
     * click. Empty string when {@code ldap.required.groups} isn't configured.
     */
    private String buildAdGroupCta() {
        String groupName = "";
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            groupName = requiredGroups.split(",")[0].trim();
        }
        if (groupName.isEmpty()) return "";
        String href = adSelfServiceBaseUrl + groupName;
        return "<div style=\"text-align:center;margin:22px 0 4px;\">"
            + "<a href=\"" + esc(href) + "\" "
            + "style=\"display:inline-block;background:#2c3e50;color:#fff;padding:12px 28px;"
            + "border-radius:6px;font-size:14px;font-weight:600;text-decoration:none;"
            + "font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;\">"
            + "Add to " + esc(groupName)
            + "</a>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:8px;\">Opens anywhere.sandisk.com</div>"
            + "</div>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String normalizeKey(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ------------------------------------------------------------------
    // Data classes (Jackson-serialized)
    // ------------------------------------------------------------------

    public static class TabDef {
        public final String key;
        public final String label;
        public final boolean adminOnly;
        public final boolean permissionsAdmin;
        /** When true, only members of {@code app.maintenance.allowed-users} (the IT allowlist)
         *  can see this tab. Used for IT-only / pre-release features that even PLM admins
         *  shouldn't see during testing. Implies adminOnly. */
        public final boolean itOnly;

        public TabDef(String key, String label, boolean adminOnly, boolean permissionsAdmin) {
            this(key, label, adminOnly, permissionsAdmin, false);
        }
        public TabDef(String key, String label, boolean adminOnly, boolean permissionsAdmin, boolean itOnly) {
            this.key = key;
            this.label = label;
            this.adminOnly = adminOnly;
            this.permissionsAdmin = permissionsAdmin;
            this.itOnly = itOnly;
        }
    }

    public static class UserRecord {
        public String sAMAccountName;
        public String displayName;
        public String email;
        public boolean managedExplicitly;
        public List<String> allowedTabs;
        public String createdAt;
        public String createdBy;
        public String createdByDisplay;
        public String updatedAt;
        public String updatedBy;
        public String updatedByDisplay;
    }

    public static class PendingRequest {
        public String sAMAccountName;
        public String displayName;
        public String email;
        public List<String> requestedTabs;
        public String requestedBy;
        public String requestedByDisplay;
        public String requestedAt;
        public String status;       // pending | completed
        public String completedAt;
        /** When the welcome email was sent (ISO timestamp) — null if never sent. */
        public String welcomeSentAt;
        /** Who triggered the welcome email — "system" for auto, AD username for manual. */
        public String welcomeSentBy;
        public String welcomeSentByDisplay;
    }

    public static class LoginInfo {
        public String username;
        public String displayName;
        public String lastLoginTs;
        public int loginCount;
    }

    private static class PermissionsFile {
        public Map<String, UserRecord> users = new LinkedHashMap<>();
        public List<PendingRequest> pendingDLRequests = new ArrayList<>();
    }

    /** {@code data/user-roles.json}: {"roles": {"engineering-insider": ["8252", ...]}} */
    static class RolesFile {
        public Map<String, List<String>> roles = new LinkedHashMap<>();
        /** Audit of the most recent grant/revoke, keyed "role|username" (lowercase user). */
        public Map<String, RoleAudit> lastChange = new LinkedHashMap<>();
    }

    static class RoleAudit {
        public String by;
        public String byDisplay;
        public String at;
        public String action;   // grant | revoke
    }
}
