package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class LdapAuthService {

    private static final Logger logger = Logger.getLogger(LdapAuthService.class.getName());

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.service.username}")
    private String serviceUsername;

    @Value("${ldap.service.password}")
    private String servicePassword;

    @Value("${ldap.search.base}")
    private String searchBase;

    @Value("${ldap.domain}")
    private String domain;

    @Value("${ldap.required.groups:}")
    private String requiredGroups;

    // Credential cache: username -> CachedUser (password hash + user info)
    private final ConcurrentHashMap<String, CachedUser> credentialCache = new ConcurrentHashMap<>();

    @Value("${app.credential-cache.file:./data/credential-cache.json}")
    private String credentialCacheFile;

    // Emergency local admin account
    @Value("${app.emergency.username:}")
    private String emergencyUsername;

    @Value("${app.emergency.password-hash:}")
    private String emergencyPasswordHash;

    @Value("${app.emergency.display-name:Emergency Admin}")
    private String emergencyDisplayName;

    @Value("${app.emergency.email:}")
    private String emergencyEmail;

    @javax.annotation.PostConstruct
    public void initCredentialCache() {
        loadCredentialCacheFromDisk();
    }

    /**
     * Authenticates user against AD by attempting to bind with their credentials.
     * Falls back to cached credentials if AD is unreachable.
     */
    public AuthResult authenticate(String username, String password) {
        if (username == null) username = "";
        username = username.trim();

        // Accept three input shapes: sAMAccountName ("vikas.jindal" or "8252"),
        // email ("vikas.jindal@sandisk.com"), or emergency-admin shortname.
        // For an email input, the public mail suffix ("sandisk.com") usually
        // differs from the internal AD UPN suffix ("corp.sandisk.com"), so we
        // can't just bind with the raw input. Resolve email → sAMAccountName
        // via a service-account lookup first, then bind with sam@ldap.domain.
        boolean inputIsEmail = username.contains("@");
        if (inputIsEmail && !matchesEmergencyAccount(username)) {
            String resolvedSam = resolveSamFromEmail(username);
            if (resolvedSam != null && !resolvedSam.isEmpty()) {
                logger.info("[AUTH] Resolved email " + username + " → sAMAccountName " + resolvedSam);
                username = resolvedSam;
            }
            // If we couldn't resolve, keep the email and let the bind fail
            // naturally — the catch block will give us the right error message.
        }

        // Step 1: Bind as the user to verify credentials
        String userPrincipal = username.contains("@") ? username : (username + "@" + domain);
        Hashtable<String, String> userEnv = new Hashtable<>();
        userEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        userEnv.put(Context.PROVIDER_URL, ldapUrl);
        userEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        userEnv.put(Context.SECURITY_PRINCIPAL, userPrincipal);
        userEnv.put(Context.SECURITY_CREDENTIALS, password);
        userEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        userEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        userEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        DirContext userCtx = null;
        try {
            userCtx = new InitialDirContext(userEnv);
        } catch (javax.naming.AuthenticationException e) {
            // AD reachable but wrong password -- check emergency account before failing
            if (matchesEmergencyAccount(username)) {
                String hash = hashPassword(password);
                if (hash != null && hash.equals(emergencyPasswordHash)) {
                    logger.info("[AUTH] Emergency local admin login: " + username);
                    return AuthResult.emergencySuccess(emergencyUsername, emergencyDisplayName, emergencyEmail);
                }
            }
            logger.info("Authentication failed for user: " + username);
            return AuthResult.failure("Invalid username or password.");
        } catch (NamingException e) {
            // AD unreachable -- try cached credentials
            logger.log(Level.WARNING, "LDAP connection error (" + e.getMessage() + "), trying cached credentials for: " + username);
            return authenticateFromCache(username, password);
        } finally {
            close(userCtx);
        }

        // Step 2: Use service account to look up user details and group membership
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        DirContext svcCtx = null;
        try {
            svcCtx = new InitialDirContext(svcEnv);

            // Search for the user
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"mail", "displayName", "sAMAccountName", "memberOf"});

            // By this point `username` has been normalized to a sAMAccountName
            // (the email-input case resolved it via resolveSamFromEmail above).
            NamingEnumeration<SearchResult> results = svcCtx.search(
                    searchBase, "(sAMAccountName=" + escLdap(username) + ")", controls);

            if (!results.hasMore()) {
                return AuthResult.failure("User not found in directory.");
            }

            SearchResult sr = results.next();
            Attributes attrs = sr.getAttributes();

            String email = getAttr(attrs, "mail");
            String displayName = getAttr(attrs, "displayName");
            String samAccount = getAttr(attrs, "sAMAccountName");

            // Extract all group memberships
            Set<String> userGroups = new HashSet<>();
            Attribute memberOf = attrs.get("memberOf");
            if (memberOf != null) {
                for (int i = 0; i < memberOf.size(); i++) {
                    String groupDn = (String) memberOf.get(i);
                    if (groupDn.toUpperCase().startsWith("CN=")) {
                        String cn = groupDn.substring(3);
                        int comma = cn.indexOf(',');
                        if (comma > 0) cn = cn.substring(0, comma);
                        userGroups.add(cn.toLowerCase());
                    }
                }
            }
            logger.info("[AD GROUPS] User " + username + " is member of " + userGroups.size() + " groups");

            boolean isPlmAdmin = userGroups.contains("pdl-plm-admin");

            // Check DL group membership if required
            if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {

                String[] required = requiredGroups.split(",");
                boolean inGroup = false;
                String missingGroup = "";
                for (String g : required) {
                    String trimmed = g.trim();
                    if (!trimmed.isEmpty()) {
                        if (userGroups.contains(trimmed.toLowerCase())) {
                            inGroup = true;
                            break;
                        }
                        missingGroup = trimmed;
                    }
                }

                if (!inGroup && !missingGroup.isEmpty()) {
                    logger.info("User " + username + " not in required group: " + missingGroup);
                    return AuthResult.notInGroup(samAccount, displayName, email, missingGroup);
                }
            }

            logger.info("User authenticated successfully: " + username + " (" + email + ")");

            // Cache credentials for offline fallback
            cacheCredentials(username, password, samAccount, displayName, email);

            return AuthResult.success(samAccount, displayName, email, isPlmAdmin, userGroups);

        } catch (NamingException e) {
            logger.log(Level.WARNING, "LDAP lookup error for user: " + username, e);
            return AuthResult.failure("Error looking up user details.");
        } finally {
            close(svcCtx);
        }
    }

    // =========================================================================
    // Credential Cache (offline fallback)
    // =========================================================================

    private void cacheCredentials(String username, String password,
                                   String samAccount, String displayName, String email) {
        String hash = hashPassword(password);
        if (hash != null) {
            // Cache under every shape we could see the same user log in with —
            // the raw input, sAMAccountName, and email — so an offline login
            // resolves whether they type "8252", "vikas.jindal", or the email.
            CachedUser entry = new CachedUser(hash, samAccount, displayName, email);
            if (username != null && !username.isEmpty()) credentialCache.put(username.toLowerCase(), entry);
            if (samAccount != null && !samAccount.isEmpty()) credentialCache.put(samAccount.toLowerCase(), entry);
            if (email != null && !email.isEmpty()) credentialCache.put(email.toLowerCase(), entry);
            logger.info("Cached credentials for user: " + samAccount + (email != null && !email.isEmpty() ? " (" + email + ")" : ""));
            saveCredentialCacheToDisk();
        }
    }

    /** True if the input matches the emergency-admin account, accepting either
     *  the bare username (e.g. {@code plmadmin}) or any {@code plmadmin@...}
     *  form so users can type {@code plmadmin@sandisk.com} or
     *  {@code plmadmin@corp.sandisk.com} interchangeably. */
    private boolean matchesEmergencyAccount(String input) {
        if (emergencyUsername == null || emergencyUsername.isEmpty() || input == null) return false;
        if (input.equalsIgnoreCase(emergencyUsername)) return true;
        int at = input.indexOf('@');
        return at > 0 && input.substring(0, at).equalsIgnoreCase(emergencyUsername);
    }

    /** Service-account lookup: given an email address, return the user's
     *  sAMAccountName so we can bind with {@code sam@<ldap.domain>}. Returns
     *  null if the user can't be found or AD is unreachable. */
    private String resolveSamFromEmail(String email) {
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"sAMAccountName"});
            String filter = "(|(mail=" + escLdap(email) + ")(userPrincipalName=" + escLdap(email) + "))";
            NamingEnumeration<SearchResult> rs = ctx.search(searchBase, filter, controls);
            if (rs.hasMore()) {
                return getAttr(rs.next().getAttributes(), "sAMAccountName");
            }
        } catch (NamingException e) {
            logger.log(Level.WARNING, "[AUTH] resolveSamFromEmail failed for " + email + ": " + e.getMessage());
        } finally {
            close(ctx);
        }
        return null;
    }

    /** Slim "is this password right" check for the IMS Review approval flow.
     *
     *  <p>Identical bind logic to {@link #authenticate} but with three deliberate
     *  differences: no cached-credential fallback (we want a fresh, attested
     *  bind for every electronic signature), no group-membership check (the
     *  one-time email token is the authorization; the password is the
     *  signature), and no Session creation (the caller is recording an action
     *  not granting access).</p>
     *
     *  <p>Used by {@code POST /api/auth/verify}. The returned {@link VerifyResult}
     *  carries the verified sAMAccountName / display name / email so the caller
     *  can stamp them onto the audit record and the compliance PDF.</p>
     */
    public VerifyResult verifyCredentials(String username, String password) {
        if (username == null) username = "";
        username = username.trim();
        if (username.isEmpty() || password == null || password.isEmpty()) {
            return VerifyResult.failure("Username and password are both required.");
        }

        // Accept email input — same resolution path as authenticate().
        boolean inputIsEmail = username.contains("@");
        if (inputIsEmail && !matchesEmergencyAccount(username)) {
            String resolvedSam = resolveSamFromEmail(username);
            if (resolvedSam != null && !resolvedSam.isEmpty()) username = resolvedSam;
        }

        String userPrincipal = username.contains("@") ? username : (username + "@" + domain);
        Hashtable<String, String> userEnv = new Hashtable<>();
        userEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        userEnv.put(Context.PROVIDER_URL, ldapUrl);
        userEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        userEnv.put(Context.SECURITY_PRINCIPAL, userPrincipal);
        userEnv.put(Context.SECURITY_CREDENTIALS, password);
        userEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        userEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        userEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        DirContext userCtx = null;
        try {
            userCtx = new InitialDirContext(userEnv);
        } catch (javax.naming.AuthenticationException ae) {
            return VerifyResult.failure("Invalid username or password.");
        } catch (NamingException ne) {
            logger.log(Level.WARNING, "[VERIFY] LDAP unreachable for " + username + ": " + ne.getMessage());
            // No cached fallback — for a signature we must have a live attestation.
            return VerifyResult.failure("Directory unreachable — try again in a minute.");
        } finally {
            close(userCtx);
        }

        // Bind succeeded. Look up the verified user's display name + email via
        // the service account so the caller doesn't have to trust client-side
        // input for the audit record.
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        DirContext svcCtx = null;
        try {
            svcCtx = new InitialDirContext(svcEnv);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"mail", "displayName", "sAMAccountName"});
            NamingEnumeration<SearchResult> results = svcCtx.search(
                    searchBase, "(sAMAccountName=" + escLdap(username) + ")", controls);
            if (results.hasMore()) {
                Attributes attrs = results.next().getAttributes();
                return VerifyResult.success(
                        getAttr(attrs, "sAMAccountName"),
                        getAttr(attrs, "displayName"),
                        getAttr(attrs, "mail"));
            }
        } catch (NamingException ne) {
            logger.log(Level.WARNING, "[VERIFY] service-account lookup failed for " + username + ": " + ne.getMessage());
        } finally {
            close(svcCtx);
        }
        // Bind worked but lookup didn't — return success with sam only.
        return VerifyResult.success(username, username, null);
    }

    /** Outcome of {@link #verifyCredentials}. Carries the verified identity
     *  attributes so callers can audit them without trusting client input. */
    public static final class VerifyResult {
        public final boolean success;
        public final String message;
        public final String samAccount;
        public final String displayName;
        public final String email;
        private VerifyResult(boolean s, String m, String sam, String dn, String em) {
            success = s; message = m; samAccount = sam; displayName = dn; email = em;
        }
        public static VerifyResult success(String sam, String dn, String em) {
            return new VerifyResult(true, "ok", sam, dn, em);
        }
        public static VerifyResult failure(String msg) {
            return new VerifyResult(false, msg, null, null, null);
        }
    }

    private AuthResult authenticateFromCache(String username, String password) {
        // Try credential cache first
        CachedUser cached = credentialCache.get(username.toLowerCase());
        if (cached != null) {
            String hash = hashPassword(password);
            if (hash != null && hash.equals(cached.passwordHash)) {
                logger.info("[AUTH] Offline login from cache for user: " + username);
                return AuthResult.cachedSuccess(cached.samAccount, cached.displayName, cached.email);
            }
            logger.info("[AUTH] Cached credential mismatch for user: " + username);
            return AuthResult.failure("Invalid username or password.");
        }

        // Try emergency local account
        if (matchesEmergencyAccount(username)) {
            String hash = hashPassword(password);
            if (hash != null && hash.equals(emergencyPasswordHash)) {
                logger.info("[AUTH] Emergency local admin login: " + username);
                return AuthResult.emergencySuccess(emergencyUsername, emergencyDisplayName, emergencyEmail);
            }
            logger.info("[AUTH] Emergency account password mismatch");
            return AuthResult.failure("Invalid username or password.");
        }

        logger.info("[AUTH] No cached credentials and no emergency match for user: " + username);
        return AuthResult.failure(
                "Authentication server is unavailable and you have not logged in before. " +
                "Please connect to VPN and try again.");
    }

    // =========================================================================
    // Group Member Enumeration (used by Extensions tab contributor typeahead)
    // =========================================================================

    /** Returned to controllers as a lightweight DTO for typeahead suggestions. */
    public static class DirectoryUser {
        public final String username;     // sAMAccountName
        public final String displayName;
        public final String email;

        public DirectoryUser(String username, String displayName, String email) {
            this.username = username;
            this.displayName = displayName;
            this.email = email;
        }
    }

    private volatile List<DirectoryUser> candidatesCache = null;
    private volatile long candidatesCacheLoaded = 0;
    private static final long CANDIDATES_CACHE_TTL_MS = 60L * 60L * 1000L; // 1 hour

    /**
     * Returns all users in the access group ({@code IT-APP-Agile-admin}) who are NOT
     * also in {@code pdl-plm-admin}. These are the candidates a PLM admin can promote
     * to "Extension Contributor" — admins themselves don't need to be granted anything.
     *
     * <p>Result is cached for one hour. Returns an empty list if AD is unreachable
     * (callers should treat that as "no suggestions" rather than fail loudly).
     */
    /**
     * Returns ALL users in the access group (including admins) by combining
     * the candidates cache with admin users from the credential cache.
     * For use in user-picker dropdowns where any group member should be selectable.
     */
    public List<DirectoryUser> listAllGroupMembers() {
        List<DirectoryUser> candidates = listAccessGroupCandidates();
        // Add admins from the credential cache (they're excluded from candidates)
        Set<String> seen = new HashSet<>();
        List<DirectoryUser> all = new ArrayList<>();
        for (DirectoryUser u : candidates) {
            seen.add(u.username.toLowerCase());
            all.add(u);
        }
        for (Map.Entry<String, CachedUser> e : credentialCache.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (!seen.contains(key)) {
                CachedUser cu = e.getValue();
                all.add(new DirectoryUser(cu.samAccount != null ? cu.samAccount : e.getKey(),
                        cu.displayName, cu.email));
                seen.add(key);
            }
        }
        all.sort(Comparator.comparing(u -> (u.displayName == null ? u.username : u.displayName).toLowerCase()));
        return all;
    }

    public List<DirectoryUser> listAccessGroupCandidates() {
        long now = System.currentTimeMillis();
        if (candidatesCache != null && (now - candidatesCacheLoaded) < CANDIDATES_CACHE_TTL_MS) {
            return candidatesCache;
        }
        List<DirectoryUser> fresh = fetchCandidatesFromLdap();
        if (fresh != null) {
            candidatesCache = fresh;
            candidatesCacheLoaded = now;
            return fresh;
        }
        // On failure, return whatever we had cached previously (may be null → empty list)
        return candidatesCache != null ? candidatesCache : Collections.emptyList();
    }

    private List<DirectoryUser> fetchCandidatesFromLdap() {
        // Determine the access group CN (first entry in ldap.required.groups)
        String accessGroupCn = null;
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            accessGroupCn = requiredGroups.split(",")[0].trim();
        }
        if (accessGroupCn == null || accessGroupCn.isEmpty()) {
            logger.warning("[EXT-CANDIDATES] No required group configured — cannot enumerate");
            return Collections.emptyList();
        }

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);

            // Find DNs of both groups so we can filter via memberOf
            String accessGroupDn = findGroupDn(ctx, accessGroupCn);
            String adminGroupDn = findGroupDn(ctx, "pdl-plm-admin");
            if (accessGroupDn == null) {
                logger.warning("[EXT-CANDIDATES] Group '" + accessGroupCn + "' not found in directory");
                return Collections.emptyList();
            }

            // Search users in the access group, excluding admins and disabled accounts.
            // userAccountControl bit 2 (0x02) = ACCOUNTDISABLE → exclude with bitwise filter.
            StringBuilder filter = new StringBuilder();
            filter.append("(&(objectClass=user)(objectCategory=person)");
            filter.append("(memberOf=").append(escLdap(accessGroupDn)).append(")");
            if (adminGroupDn != null) {
                filter.append("(!(memberOf=").append(escLdap(adminGroupDn)).append("))");
            }
            filter.append("(!(userAccountControl:1.2.840.113556.1.4.803:=2))");
            filter.append(")");

            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"sAMAccountName", "displayName", "mail"});
            controls.setCountLimit(2000);

            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter.toString(), controls);

            List<DirectoryUser> out = new ArrayList<>();
            while (results.hasMore()) {
                SearchResult sr = results.next();
                Attributes attrs = sr.getAttributes();
                String sam = getAttr(attrs, "sAMAccountName");
                if (sam == null || sam.isEmpty()) continue;
                out.add(new DirectoryUser(sam, getAttr(attrs, "displayName"), getAttr(attrs, "mail")));
            }
            // Sort by display name for predictable typeahead order
            out.sort(Comparator.comparing(u -> (u.displayName == null ? u.username : u.displayName).toLowerCase()));
            logger.info("[EXT-CANDIDATES] Loaded " + out.size() + " candidate user(s) from AD");
            return out;
        } catch (NamingException e) {
            logger.log(Level.WARNING, "[EXT-CANDIDATES] LDAP enumeration failed", e);
            return null; // signal failure → caller keeps any prior cache
        } finally {
            close(ctx);
        }
    }

    /**
     * Org-wide AD typeahead search for the User Permissions admin UI. Matches
     * against {@code displayName}, {@code sAMAccountName}, or {@code mail}
     * starts-with (case-insensitive). Excludes disabled accounts. Capped at 25
     * results regardless of how many AD returns — prevents the UI flooding
     * when someone types two letters.
     *
     * <p>This is a different search than {@link #fetchCandidatesFromLdap()} —
     * that one is scoped to the access DL members. This one searches the entire
     * directory so the admin can find a user who is NOT yet in the DL and
     * request IT to add them.
     */
    public List<DirectoryUser> searchDirectory(String query, int limit) {
        if (query == null || query.trim().length() < 3) return Collections.emptyList();
        if (limit <= 0 || limit > 50) limit = 25;
        String q = escLdap(query.trim());

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);

            // Match prefix on any of: displayName, sAMAccountName, mail.
            // userAccountControl bit 2 (0x02) = ACCOUNTDISABLE → exclude.
            String filter = "(&(objectClass=user)(objectCategory=person)"
                + "(!(userAccountControl:1.2.840.113556.1.4.803:=2))"
                + "(|(displayName=" + q + "*)(sAMAccountName=" + q + "*)(mail=" + q + "*)))";

            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"sAMAccountName", "displayName", "mail"});
            controls.setCountLimit(limit);
            controls.setTimeLimit(8000);

            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);

            List<DirectoryUser> out = new ArrayList<>();
            while (results.hasMore() && out.size() < limit) {
                SearchResult sr = results.next();
                Attributes attrs = sr.getAttributes();
                String sam = getAttr(attrs, "sAMAccountName");
                if (sam == null || sam.isEmpty()) continue;
                out.add(new DirectoryUser(sam, getAttr(attrs, "displayName"), getAttr(attrs, "mail")));
            }
            return out;
        } catch (javax.naming.SizeLimitExceededException sle) {
            // Expected when the user typed something very common — what we got is fine
            return Collections.emptyList();
        } catch (Exception e) {
            logger.warning("[AD-SEARCH] Search failed for q='" + query + "': " + e.getMessage());
            return Collections.emptyList();
        } finally {
            close(ctx);
        }
    }

    /** Finds the distinguished name of a group given its CN (case-insensitive). */
    private String findGroupDn(DirContext ctx, String groupCn) throws NamingException {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"distinguishedName"});
        controls.setCountLimit(1);

        String filter = "(&(objectClass=group)(cn=" + escLdap(groupCn) + "))";
        NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
        if (!results.hasMore()) return null;
        SearchResult sr = results.next();
        return sr.getNameInNamespace();
    }

    // =========================================================================
    // User Info Lookup by Employee ID (for user popover cards)
    // =========================================================================

    /** Lightweight DTO for user info popover. */
    public static class UserInfo {
        public String displayName;
        public String email;
        public String department;
        public String title;
        public String manager;
        public String country;
        public String city;
        /** ISO-8601 UTC of AD account creation (parsed from whenCreated). Null if unavailable. */
        public String accountCreatedDate;
    }

    /**
     * Parse an LDAP generalized-time value like "20190315143025.0Z" into ISO-8601
     * "2019-03-15T14:30:25Z". Returns null on any parse failure.
     */
    private static String parseLdapTimestamp(String raw) {
        if (raw == null || raw.length() < 14) return null;
        try {
            return raw.substring(0,4) + "-" + raw.substring(4,6) + "-" + raw.substring(6,8)
                + "T" + raw.substring(8,10) + ":" + raw.substring(10,12) + ":" + raw.substring(12,14) + "Z";
        } catch (Exception e) { return null; }
    }

    private final ConcurrentHashMap<String, UserInfo> userInfoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> userInfoCacheTime = new ConcurrentHashMap<>();
    private static final long USER_INFO_CACHE_TTL_MS = 4L * 60L * 60L * 1000L; // 4 hours

    /**
     * Looks up user info from AD by employee ID (the numeric ID in parentheses
     * in the "Changed By" column, e.g. "7323685"). Results are cached for 4 hours.
     */
    public UserInfo lookupUserById(String employeeId) {
        if (employeeId == null || employeeId.isEmpty()) return null;

        // Check cache
        Long cachedAt = userInfoCacheTime.get(employeeId);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < USER_INFO_CACHE_TTL_MS) {
            return userInfoCache.get(employeeId);
        }

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);

            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{
                "displayName", "mail", "department", "title", "manager", "co", "l", "whenCreated"
            });
            controls.setCountLimit(1);

            // Search by employeeID (the Agile user ID stored in AD)
            String filter = "(&(objectClass=user)(employeeID=" + escLdap(employeeId) + "))";
            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);

            if (!results.hasMore()) {
                // Cache a miss too, so we don't re-query for unknown IDs
                UserInfo empty = new UserInfo();
                empty.displayName = "";
                userInfoCache.put(employeeId, empty);
                userInfoCacheTime.put(employeeId, System.currentTimeMillis());
                return empty;
            }

            SearchResult sr = results.next();
            Attributes attrs = sr.getAttributes();

            UserInfo info = new UserInfo();
            info.displayName = getAttr(attrs, "displayName");
            info.email = getAttr(attrs, "mail");
            info.department = getAttr(attrs, "department");
            info.title = getAttr(attrs, "title");
            info.country = getAttr(attrs, "co");
            info.city = getAttr(attrs, "l");
            info.accountCreatedDate = parseLdapTimestamp(getAttr(attrs, "whenCreated"));

            // Manager is a DN like "CN=Jindal\, Vikas,OU=Users,..." - extract just the name
            String managerDn = getAttr(attrs, "manager");
            if (managerDn != null && managerDn.startsWith("CN=")) {
                String cn = managerDn.substring(3);
                int comma = cn.indexOf(",OU=");
                if (comma < 0) comma = cn.indexOf(",DC=");
                if (comma > 0) cn = cn.substring(0, comma);
                info.manager = cn.replace("\\,", ",").replace("\\", "");
            } else {
                info.manager = "";
            }

            userInfoCache.put(employeeId, info);
            userInfoCacheTime.put(employeeId, System.currentTimeMillis());
            return info;

        } catch (NamingException e) {
            logger.log(Level.WARNING, "[USER-INFO] LDAP lookup failed for employeeID: " + employeeId, e);
            return null;
        } finally {
            close(ctx);
        }
    }

    /**
     * Looks up user info by sAMAccountName (login username).
     * Returns UserInfo with email, displayName, etc. or null if not found.
     */
    public UserInfo lookupUserByUsername(String username) {
        if (username == null || username.isEmpty()) return null;

        String cacheKey = "sam:" + username.toLowerCase();
        Long cachedAt = userInfoCacheTime.get(cacheKey);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < USER_INFO_CACHE_TTL_MS) {
            return userInfoCache.get(cacheKey);
        }

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"displayName", "mail", "department", "title", "manager", "co", "l", "whenCreated"});
            controls.setCountLimit(1);

            String filter = "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))";
            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);

            if (!results.hasMore()) return null;
            SearchResult sr = results.next();
            Attributes attrs = sr.getAttributes();

            UserInfo info = new UserInfo();
            info.displayName = getAttr(attrs, "displayName");
            info.email = getAttr(attrs, "mail");
            info.department = getAttr(attrs, "department");
            info.title = getAttr(attrs, "title");
            info.country = getAttr(attrs, "co");
            info.city = getAttr(attrs, "l");
            info.accountCreatedDate = parseLdapTimestamp(getAttr(attrs, "whenCreated"));
            String managerDn = getAttr(attrs, "manager");
            if (managerDn != null && managerDn.startsWith("CN=")) {
                String cn = managerDn.substring(3);
                int comma = cn.indexOf(",OU=");
                if (comma < 0) comma = cn.indexOf(",DC=");
                if (comma > 0) cn = cn.substring(0, comma);
                info.manager = cn.replace("\\,", ",").replace("\\", "");
            } else { info.manager = ""; }

            userInfoCache.put(cacheKey, info);
            userInfoCacheTime.put(cacheKey, System.currentTimeMillis());
            return info;
        } catch (NamingException e) {
            logger.log(Level.WARNING, "[USER-INFO] LDAP lookup by username failed: " + username, e);
            return null;
        } finally {
            close(ctx);
        }
    }

    // Cached set of admin sAMAccountNames, keyed for fast contains() during the
    // /api/permissions/users render. TTL matches the candidates cache so they
    // refresh on the same cadence.
    private volatile Set<String> adminUsernamesCache = null;
    private volatile long adminCacheLoaded = 0;

    /**
     * Returns the lowercased sAMAccountName of every member of the
     * {@code pdl-plm-admin} group. Used by the User Permissions admin UI to
     * render the "PLM Admin" badge and to lock the Edit modal so a permissions-
     * admin can't restrict an admin's tabs. Cached for one hour.
     */
    public Set<String> listAdminUsernames() {
        long now = System.currentTimeMillis();
        if (adminUsernamesCache != null && (now - adminCacheLoaded) < CANDIDATES_CACHE_TTL_MS) {
            return adminUsernamesCache;
        }
        Set<String> fresh = fetchAdminUsernamesFromLdap();
        if (fresh != null) {
            adminUsernamesCache = fresh;
            adminCacheLoaded = now;
            return fresh;
        }
        return adminUsernamesCache != null ? adminUsernamesCache : Collections.emptySet();
    }

    private Set<String> fetchAdminUsernamesFromLdap() {
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            String adminGroupDn = findGroupDn(ctx, "pdl-plm-admin");
            if (adminGroupDn == null) {
                logger.warning("[ADMIN-CACHE] pdl-plm-admin group not found in directory");
                return Collections.emptySet();
            }
            String filter = "(&(objectClass=user)(objectCategory=person)"
                + "(memberOf=" + escLdap(adminGroupDn) + ")"
                + "(!(userAccountControl:1.2.840.113556.1.4.803:=2)))";
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"sAMAccountName"});
            controls.setCountLimit(500);

            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
            Set<String> out = new HashSet<>();
            while (results.hasMore()) {
                SearchResult sr = results.next();
                String sam = getAttr(sr.getAttributes(), "sAMAccountName");
                if (sam != null && !sam.isEmpty()) out.add(sam.toLowerCase());
            }
            return out;
        } catch (Exception e) {
            logger.warning("[ADMIN-CACHE] Failed to enumerate admins: " + e.getMessage());
            return null;
        } finally {
            close(ctx);
        }
    }

    /**
     * Check if a user is in the pdl-plm-admin group.
     */
    public boolean isUserInAdminGroup(String username) {
        if (username == null || username.isEmpty()) return false;
        try {
            Hashtable<String, String> svcEnv = new Hashtable<>();
            svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            svcEnv.put(Context.PROVIDER_URL, ldapUrl);
            svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
            svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
            svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
            svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
            svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
            svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");

            DirContext ctx = null;
            try {
                ctx = new InitialDirContext(svcEnv);
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(new String[]{"memberOf"});
                controls.setCountLimit(1);

                String filter = "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))";
                NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
                if (!results.hasMore()) return false;

                SearchResult sr = results.next();
                Attribute memberOf = sr.getAttributes().get("memberOf");
                if (memberOf == null) return false;

                for (int i = 0; i < memberOf.size(); i++) {
                    String groupDn = (String) memberOf.get(i);
                    if (groupDn.toUpperCase().contains("PDL-PLM-ADMIN")) return true;
                }
                return false;
            } finally {
                close(ctx);
            }
        } catch (Exception e) {
            logger.warning("[LDAP] Admin group check failed for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Live (uncached) check: is {@code username} a member of the access DL
     * (first entry of {@code ldap.required.groups}, e.g. IT-APP-Agile-admin)?
     * Queries AD directly so a just-added member is recognized immediately.
     * Fail-closed: returns false on no-match, missing user, unconfigured group,
     * or any error — callers must never email when membership can't be confirmed.
     */
    public boolean isUserInAccessGroup(String username) {
        if (username == null || username.isEmpty()) return false;
        String accessGroupCn = null;
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            accessGroupCn = requiredGroups.split(",")[0].trim();
        }
        if (accessGroupCn == null || accessGroupCn.isEmpty()) return false;
        String needle = accessGroupCn.toUpperCase();
        try {
            Hashtable<String, String> svcEnv = new Hashtable<>();
            svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            svcEnv.put(Context.PROVIDER_URL, ldapUrl);
            svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
            svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
            svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
            svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
            svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
            svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");

            DirContext ctx = null;
            try {
                ctx = new InitialDirContext(svcEnv);
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(new String[]{"memberOf"});
                controls.setCountLimit(1);

                String filter = "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))";
                NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
                if (!results.hasMore()) return false;

                SearchResult sr = results.next();
                Attribute memberOf = sr.getAttributes().get("memberOf");
                if (memberOf == null) return false;

                for (int i = 0; i < memberOf.size(); i++) {
                    String groupDn = (String) memberOf.get(i);
                    if (groupDn != null && groupDn.toUpperCase().contains(needle)) return true;
                }
                return false;
            } finally {
                close(ctx);
            }
        } catch (Exception e) {
            logger.warning("[LDAP] Access group check failed for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Tests AD connectivity and bind speed without caching. Returns timing details.
     */
    public Map<String, Object> testAdHealth(String username, String password) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ldapUrl", ldapUrl);
        result.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));

        // Step 1: Test user bind
        String userPrincipal = username + "@" + domain;
        Hashtable<String, String> userEnv = new Hashtable<>();
        userEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        userEnv.put(Context.PROVIDER_URL, ldapUrl);
        userEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        userEnv.put(Context.SECURITY_PRINCIPAL, userPrincipal);
        userEnv.put(Context.SECURITY_CREDENTIALS, password);
        userEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        userEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        userEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        long bindStart = System.currentTimeMillis();
        DirContext userCtx = null;
        try {
            userCtx = new InitialDirContext(userEnv);
            long bindMs = System.currentTimeMillis() - bindStart;
            result.put("userBindMs", bindMs);
            result.put("userBindStatus", "OK");
        } catch (javax.naming.AuthenticationException e) {
            long bindMs = System.currentTimeMillis() - bindStart;
            result.put("userBindMs", bindMs);
            result.put("userBindStatus", "AUTH_FAILED");
            result.put("userBindError", "Invalid credentials");
            result.put("totalMs", bindMs);
            result.put("verdict", "AUTH_FAILED");
            return result;
        } catch (NamingException e) {
            long bindMs = System.currentTimeMillis() - bindStart;
            result.put("userBindMs", bindMs);
            result.put("userBindStatus", "UNREACHABLE");
            result.put("userBindError", e.getMessage());
            result.put("totalMs", bindMs);
            result.put("verdict", "UNREACHABLE");
            return result;
        } finally {
            close(userCtx);
        }

        // Step 2: Test service account bind + group lookup
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "10000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        long svcStart = System.currentTimeMillis();
        DirContext svcCtx = null;
        try {
            svcCtx = new InitialDirContext(svcEnv);
            long svcBindMs = System.currentTimeMillis() - svcStart;
            result.put("svcBindMs", svcBindMs);
            result.put("svcBindStatus", "OK");

            // Step 3: User lookup + group enumeration
            long lookupStart = System.currentTimeMillis();
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"displayName", "mail", "memberOf"});
            NamingEnumeration<SearchResult> results = svcCtx.search(
                    searchBase, "(sAMAccountName=" + escLdap(username) + ")", controls);

            int groupCount = 0;
            String displayName = "";
            if (results.hasMore()) {
                SearchResult sr = results.next();
                Attributes attrs = sr.getAttributes();
                displayName = getAttr(attrs, "displayName");
                Attribute memberOf = attrs.get("memberOf");
                if (memberOf != null) groupCount = memberOf.size();
            }
            long lookupMs = System.currentTimeMillis() - lookupStart;
            result.put("groupLookupMs", lookupMs);
            result.put("groupCount", groupCount);
            result.put("displayName", displayName);
            result.put("groupLookupStatus", "OK");

        } catch (NamingException e) {
            long svcBindMs = System.currentTimeMillis() - svcStart;
            result.put("svcBindMs", svcBindMs);
            result.put("svcBindStatus", "FAILED");
            result.put("svcBindError", e.getMessage());
        } finally {
            close(svcCtx);
        }

        // Overall assessment
        long totalMs = System.currentTimeMillis() - bindStart;
        result.put("totalMs", totalMs);
        String verdict;
        if (totalMs < 2000) verdict = "FAST";
        else if (totalMs < 5000) verdict = "NORMAL";
        else if (totalMs < 10000) verdict = "SLOW";
        else verdict = "VERY_SLOW";
        result.put("verdict", verdict);

        return result;
    }

    // =========================================================================
    // Persistent credential cache (survives restarts)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void loadCredentialCacheFromDisk() {
        java.io.File f = new java.io.File(credentialCacheFile);
        if (!f.exists()) {
            logger.info("[CRED-CACHE] No persistent cache file at " + credentialCacheFile);
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Map<String, String>> data = mapper.readValue(f,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
            for (Map.Entry<String, Map<String, String>> entry : data.entrySet()) {
                Map<String, String> u = entry.getValue();
                credentialCache.put(entry.getKey(),
                        new CachedUser(u.get("hash"), u.get("sam"), u.get("name"), u.get("email")));
            }
            logger.info("[CRED-CACHE] Loaded " + data.size() + " cached credentials from disk");
        } catch (Exception e) {
            logger.warning("[CRED-CACHE] Failed to load: " + e.getMessage());
        }
    }

    private synchronized void saveCredentialCacheToDisk() {
        try {
            java.io.File f = new java.io.File(credentialCacheFile);
            f.getParentFile().mkdirs();
            Map<String, Map<String, String>> data = new LinkedHashMap<>();
            for (Map.Entry<String, CachedUser> entry : credentialCache.entrySet()) {
                Map<String, String> u = new LinkedHashMap<>();
                u.put("hash", entry.getValue().passwordHash);
                u.put("sam", entry.getValue().samAccount);
                u.put("name", entry.getValue().displayName);
                u.put("email", entry.getValue().email);
                data.put(entry.getKey(), u);
            }
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValue(f, data);
        } catch (Exception e) {
            logger.warning("[CRED-CACHE] Failed to save: " + e.getMessage());
        }
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warning("Failed to hash password: " + e.getMessage());
            return null;
        }
    }

    private static class CachedUser {
        final String passwordHash;
        final String samAccount;
        final String displayName;
        final String email;

        CachedUser(String passwordHash, String samAccount, String displayName, String email) {
            this.passwordHash = passwordHash;
            this.samAccount = samAccount;
            this.displayName = displayName;
            this.email = email;
        }
    }

    /**
     * Batch lookup of country for a list of login IDs. Single LDAP query.
     * Returns map of loginId → country name (e.g., "India", "United States", "Malaysia").
     * Returns empty map on AD failure — callers should treat missing as "Unknown".
     */
    public Map<String, String> batchLookupCountry(List<String> loginIds) {
        if (loginIds == null || loginIds.isEmpty()) return Collections.emptyMap();

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);

            // Build OR filter: (|(sAMAccountName=id1)(sAMAccountName=id2)...)
            StringBuilder filter = new StringBuilder();
            filter.append("(&(objectClass=user)(objectCategory=person)(|");
            for (String id : loginIds) {
                filter.append("(sAMAccountName=").append(escLdap(id)).append(")");
            }
            filter.append("))");

            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"sAMAccountName", "co"});

            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter.toString(), controls);
            Map<String, String> countryMap = new HashMap<>();
            while (results.hasMore()) {
                SearchResult sr = results.next();
                Attributes attrs = sr.getAttributes();
                String sam = getAttr(attrs, "sAMAccountName");
                String country = getAttr(attrs, "co"); // "co" = country name in AD
                if (sam != null && !sam.isEmpty()) {
                    countryMap.put(sam, country.isEmpty() ? "Unknown" : country);
                }
            }
            logger.info("[AD-COUNTRY] Resolved country for " + countryMap.size() + "/" + loginIds.size() + " analysts");
            return countryMap;
        } catch (Exception e) {
            logger.warning("[AD-COUNTRY] Batch country lookup failed: " + e.getMessage());
            return Collections.emptyMap();
        } finally {
            close(ctx);
        }
    }

    // =========================================================================
    // Direct-reports lookup (for Volume Reports tab under ECN Report)
    // =========================================================================

    /** Lightweight DTO representing one direct or indirect report of a manager. */
    public static class ReportingUser {
        public final String samAccountName;
        public final String displayName;
        public final String email;
        public final String employeeId;   // = Agile LOGINID for the change-search SQL

        public ReportingUser(String sam, String displayName, String email, String employeeId) {
            this.samAccountName = sam;
            this.displayName = displayName;
            this.email = email;
            this.employeeId = employeeId;
        }
    }

    /**
     * Find users who report (directly, or transitively) to {@code managerUsername}.
     * Search anchor is the manager's distinguishedName, which we look up by sAMAccountName.
     * If transitive=true, BFS the org tree with safety caps (max 500 users, max depth 6).
     *
     * @return list of reports sorted by displayName, plus an empty list if AD is unreachable.
     */
    public List<ReportingUser> findDirectReports(String managerUsername, boolean transitive) {
        if (managerUsername == null || managerUsername.isEmpty()) return Collections.emptyList();

        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "15000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            String managerDn = findUserDn(ctx, managerUsername);
            if (managerDn == null) {
                logger.info("[REPORTS] Manager not found in AD: " + managerUsername);
                return Collections.emptyList();
            }
            logger.info("[REPORTS] Manager DN: " + managerDn);

            Map<String, ReportingUser> bySam = new LinkedHashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(managerDn);
            int depth = 0;
            int maxDepth = transitive ? 6 : 1;
            int maxUsers = 500;

            while (!queue.isEmpty() && depth < maxDepth && bySam.size() < maxUsers) {
                int levelSize = queue.size();
                for (int i = 0; i < levelSize && bySam.size() < maxUsers; i++) {
                    String parentDn = queue.poll();
                    List<ReportingUser> reports = fetchReportsByManagerDn(ctx, parentDn, maxUsers - bySam.size());
                    for (ReportingUser r : reports) {
                        if (bySam.containsKey(r.samAccountName.toLowerCase())) continue;
                        bySam.put(r.samAccountName.toLowerCase(), r);
                        if (transitive) {
                            // For BFS, we need each report's DN to query their reports next level
                            String childDn = findUserDn(ctx, r.samAccountName);
                            if (childDn != null) queue.add(childDn);
                        }
                    }
                }
                depth++;
            }

            List<ReportingUser> out = new ArrayList<>(bySam.values());
            out.sort(Comparator.comparing(u ->
                (u.displayName == null || u.displayName.isEmpty() ? u.samAccountName : u.displayName).toLowerCase()));
            logger.info("[REPORTS] " + managerUsername + " (transitive=" + transitive + "): " + out.size() + " reports");
            return out;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[REPORTS] Failed for manager=" + managerUsername, e);
            return Collections.emptyList();
        } finally {
            close(ctx);
        }
    }

    /** Resolve a user's AD displayName by sAMAccountName via the service
     *  account. Returns null on any failure or when the user isn't found —
     *  callers should fall back to the username. Used by the Team Report
     *  stash so each generated report records whose team it covers. */
    public String findDisplayName(String username) {
        if (username == null || username.isEmpty()) return null;
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "15000");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"displayName"});
            controls.setCountLimit(1);
            NamingEnumeration<SearchResult> results = ctx.search(
                    searchBase,
                    "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))",
                    controls);
            if (results.hasMore()) {
                Attributes attrs = results.next().getAttributes();
                String dn = getAttr(attrs, "displayName");
                return (dn == null || dn.isEmpty()) ? null : dn;
            }
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[DISPLAY-NAME] lookup failed for " + username, e);
            return null;
        } finally {
            close(ctx);
        }
    }

    /** Subset of {@code usernames} (sAMAccountName) whose AD record has at
     *  least one direct report (someone whose {@code manager} attribute
     *  points to them). One LDAP context for the whole batch: per user we
     *  do a DN lookup + a countLimit=1 search for any report.
     *
     *  <p>Used by the Volume Report manager dropdown so we only list people
     *  the user could actually run a report for. Result is cached in
     *  {@link VolumeReportController} with a 1-hour TTL.
     *
     *  <p>Empty list on AD failure (callers should treat that as "no
     *  filtering" — i.e. show the full DL — rather than break the page).
     *  Order of the returned list matches the input order. */
    public Set<String> filterUsernamesWithDirectReports(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return Collections.emptySet();
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "10000");

        Set<String> withReports = new LinkedHashSet<>();
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            for (String username : usernames) {
                if (username == null || username.isEmpty()) continue;
                try {
                    String dn = findUserDn(ctx, username);
                    if (dn == null) continue;
                    SearchControls controls = new SearchControls();
                    controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                    controls.setReturningAttributes(new String[]{"sAMAccountName"});
                    controls.setCountLimit(1);
                    // DN comes from getNameInNamespace() — already RFC 4514
                    // canonical form; don't re-escape (would break manager
                    // matches; same trap noted in fetchReportsByManagerDn).
                    NamingEnumeration<SearchResult> results = ctx.search(searchBase,
                            "(&(objectClass=user)(manager=" + dn + "))", controls);
                    if (results.hasMore()) withReports.add(username);
                } catch (Exception perUser) {
                    // One user's lookup failure shouldn't poison the rest —
                    // skip them and continue (they'll just be filtered out).
                    logger.fine("[REPORTS] hasReports lookup failed for "
                            + username + ": " + perUser.getMessage());
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[REPORTS] filterUsernamesWithDirectReports failed", e);
            return Collections.emptySet();
        } finally {
            close(ctx);
        }
        logger.info("[REPORTS] " + withReports.size() + " of " + usernames.size()
                + " DL members have direct reports");
        return withReports;
    }

    /** Look up a user's employeeID (= Agile LOGINID) by sAMAccountName. Returns "" if not found. */
    public String lookupEmployeeIdByUsername(String username) {
        if (username == null || username.isEmpty()) return "";
        Hashtable<String, String> svcEnv = new Hashtable<>();
        svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        svcEnv.put(Context.PROVIDER_URL, ldapUrl);
        svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
        svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
        svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
        svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(svcEnv);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"employeeID"});
            controls.setCountLimit(1);
            NamingEnumeration<SearchResult> results = ctx.search(searchBase,
                "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))", controls);
            if (!results.hasMore()) return "";
            return getAttr(results.next().getAttributes(), "employeeID");
        } catch (Exception e) {
            logger.warning("[REPORTS] employeeID lookup failed for " + username + ": " + e.getMessage());
            return "";
        } finally {
            close(ctx);
        }
    }

    /** Find the distinguishedName of a user given their sAMAccountName. Null if not found. */
    private String findUserDn(DirContext ctx, String username) throws NamingException {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"distinguishedName"});
        controls.setCountLimit(1);
        String filter = "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))";
        NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
        if (!results.hasMore()) return null;
        SearchResult sr = results.next();
        return sr.getNameInNamespace();
    }

    /** Search for users whose {@code manager} attribute equals the given DN. */
    private List<ReportingUser> fetchReportsByManagerDn(DirContext ctx, String managerDn, int limit) throws NamingException {
        if (limit <= 0) return Collections.emptyList();
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"sAMAccountName", "displayName", "mail", "employeeID"});
        controls.setCountLimit(Math.min(limit, 500));
        // Exclude disabled accounts (userAccountControl bit 2 = ACCOUNTDISABLE).
        // Note: DN comes from getNameInNamespace() and is already in canonical
        // RFC 4514 form (escaped commas in CN preserved as \,). Do NOT pass it
        // through escLdap() — that would re-escape the leading backslash and
        // turn \,Andy into \5c,Andy, which matches nothing.
        String filter = "(&(objectClass=user)(objectCategory=person)"
            + "(manager=" + managerDn + ")"
            + "(!(userAccountControl:1.2.840.113556.1.4.803:=2)))";
        NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
        List<ReportingUser> out = new ArrayList<>();
        while (results.hasMore()) {
            SearchResult sr = results.next();
            Attributes attrs = sr.getAttributes();
            String sam = getAttr(attrs, "sAMAccountName");
            if (sam == null || sam.isEmpty()) continue;
            out.add(new ReportingUser(sam, getAttr(attrs, "displayName"),
                    getAttr(attrs, "mail"), getAttr(attrs, "employeeID")));
        }
        return out;
    }

    private String getAttr(Attributes attrs, String name) throws NamingException {
        Attribute attr = attrs.get(name);
        if (attr == null) return "";
        Object val = attr.get();
        return val == null ? "" : val.toString();
    }

    private String escLdap(String input) {
        // Escape special LDAP filter characters
        return input.replace("\\", "\\5c")
                .replace("*", "\\2a")
                .replace("(", "\\28")
                .replace(")", "\\29")
                .replace("\0", "\\00");
    }

    private void close(DirContext ctx) {
        if (ctx != null) {
            try { ctx.close(); } catch (NamingException ignored) {}
        }
    }

    // =========================================================================
    // Auth Result
    // =========================================================================

    public static class AuthResult {
        private final boolean authenticated;
        private final boolean groupCheckFailed;
        private final boolean plmAdmin;
        private final String username;
        private final String displayName;
        private final String email;
        private final String errorMessage;
        private final String missingGroup;
        private final String loginSource; // "ad", "cache", "emergency", or null
        /** Lower-cased CN of every AD group this user is a member of. Populated
         *  for "ad" loginSource (we have the data); empty for cache / emergency
         *  (we never re-checked AD). Used downstream by UserPermissionsService
         *  for AD-group-driven tab auto-grants (e.g. PDL-PLM-admin → IT
         *  Enhancements tab). */
        private final Set<String> adGroups;

        private AuthResult(boolean authenticated, boolean groupCheckFailed, boolean plmAdmin,
                           String username, String displayName, String email,
                           String errorMessage, String missingGroup, String loginSource,
                           Set<String> adGroups) {
            this.authenticated = authenticated;
            this.groupCheckFailed = groupCheckFailed;
            this.plmAdmin = plmAdmin;
            this.username = username;
            this.displayName = displayName;
            this.email = email;
            this.errorMessage = errorMessage;
            this.missingGroup = missingGroup;
            this.loginSource = loginSource;
            this.adGroups = adGroups == null ? java.util.Collections.<String>emptySet() : adGroups;
        }

        public static AuthResult success(String username, String displayName, String email,
                                         boolean isPlmAdmin, Set<String> adGroups) {
            return new AuthResult(true, false, isPlmAdmin, username, displayName, email,
                    null, null, "ad", adGroups);
        }

        public static AuthResult cachedSuccess(String username, String displayName, String email) {
            return new AuthResult(true, false, false, username, displayName, email,
                    null, null, "cache", null);
        }

        public static AuthResult emergencySuccess(String username, String displayName, String email) {
            return new AuthResult(true, false, true, username, displayName, email,
                    null, null, "emergency", null);
        }

        public static AuthResult failure(String message) {
            return new AuthResult(false, false, false, null, null, null, message, null, null, null);
        }

        public static AuthResult notInGroup(String username, String displayName, String email, String missingGroup) {
            return new AuthResult(false, true, false, username, displayName, email,
                    "Your credentials are valid but you are not a member of the required group '" + missingGroup + "'.",
                    missingGroup, null, null);
        }

        public boolean isAuthenticated() { return authenticated; }
        public boolean isGroupCheckFailed() { return groupCheckFailed; }
        public boolean isPlmAdmin() { return plmAdmin; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public String getEmail() { return email; }
        public String getErrorMessage() { return errorMessage; }
        public String getMissingGroup() { return missingGroup; }
        public String getLoginSource() { return loginSource; }
        /** Lower-cased CN of every AD group the user is a member of. Empty for
         *  cache / emergency login sources. See field doc above. */
        public Set<String> getAdGroups() { return adGroups; }
    }
}
