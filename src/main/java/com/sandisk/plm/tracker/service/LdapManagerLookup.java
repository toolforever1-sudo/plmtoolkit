package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Best-effort LDAP lookup of a user's manager email + display name. Fails
 * gracefully — caller falls back to {@code vikas.singh3@sandisk.com} (the
 * placeholder for the IMS-Doc-Managers DL).
 */
@Service
public class LdapManagerLookup {

    private static final Logger LOG = Logger.getLogger(LdapManagerLookup.class.getName());

    @Value("${ldap.url:}")               private String ldapUrl;
    @Value("${ldap.service.username:}")  private String serviceUsername;
    @Value("${ldap.service.password:}")  private String servicePassword;
    @Value("${ldap.search.base:}")       private String searchBase;
    @Value("${ldap.domain:}")            private String domain;

    public static final class Result {
        public String email;        // manager's email
        public String displayName;  // manager's display name
        public boolean fallback;    // true → caller should use the DM-DL fallback
        public boolean disabled;    // employee userAccountControl indicates disabled
    }

    /** Three-way LDAP status used by the IMS Review admin grid to flag
     *  problem owners. ACTIVE = healthy. DISABLED = AD record exists but
     *  ACCOUNTDISABLE bit is set. NOT_FOUND = no AD record at all (most
     *  likely the person left the company). UNKNOWN = LDAP call failed —
     *  fail-open so we don't false-positive every owner when LDAP is briefly
     *  down. */
    public enum LdapStatus { ACTIVE, DISABLED, NOT_FOUND, UNKNOWN }

    /** Cached LDAP-status probe used by the admin grid. Keyed on lowercased
     *  email, 30-min TTL — slower-changing than agileuser.enabled so a longer
     *  TTL is fine. */
    private static final class CachedLdap {
        final LdapStatus status; final long fetchedAtMs;
        CachedLdap(LdapStatus s, long t) { this.status = s; this.fetchedAtMs = t; }
    }
    private final java.util.concurrent.ConcurrentHashMap<String, CachedLdap> ldapStatusCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LDAP_STATUS_TTL_MS = 30L * 60 * 1000;

    /** Probe LDAP for a user's status. Returns the three-way enum. */
    public LdapStatus checkUserStatus(String userEmail) {
        if (userEmail == null || userEmail.isEmpty()
                || ldapUrl == null || ldapUrl.isEmpty()) return LdapStatus.UNKNOWN;
        String key = userEmail.trim().toLowerCase();
        CachedLdap c = ldapStatusCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.fetchedAtMs) < LDAP_STATUS_TTL_MS) return c.status;

        LdapStatus s = LdapStatus.UNKNOWN;
        Hashtable<String, String> env = ldapEnv();
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setReturningAttributes(new String[]{"userAccountControl"});
            NamingEnumeration<SearchResult> rs = ctx.search(searchBase,
                    "(mail=" + escape(userEmail) + ")", sc);
            if (!rs.hasMore()) {
                s = LdapStatus.NOT_FOUND;
            } else {
                String uac = strAttr(rs.next().getAttributes(), "userAccountControl");
                if (uac == null) {
                    s = LdapStatus.ACTIVE;
                } else {
                    try {
                        int v = Integer.parseInt(uac);
                        s = ((v & 2) != 0) ? LdapStatus.DISABLED : LdapStatus.ACTIVE;
                    } catch (NumberFormatException nfe) {
                        s = LdapStatus.ACTIVE;
                    }
                }
            }
        } catch (Exception e) {
            LOG.info("[LDAP-MGR] checkUserStatus failed for " + userEmail
                    + " (UNKNOWN): " + e.getMessage());
            s = LdapStatus.UNKNOWN;
        } finally {
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) {}
        }
        ldapStatusCache.put(key, new CachedLdap(s, now));
        return s;
    }

    /**
     * Returns {@code true} if the user's AD profile is disabled (ACCOUNTDISABLE
     * bit set in {@code userAccountControl}) OR the user can't be found in
     * LDAP at all. Returns {@code false} when LDAP confirms the user is active.
     * When the LDAP call itself fails (network, bind, etc.), returns
     * {@code false} so we don't false-positive every doc into "DO inactive" mode
     * just because LDAP is briefly unavailable.
     */
    public boolean isUserInactive(String userEmail) {
        if (userEmail == null || userEmail.isEmpty()
                || ldapUrl == null || ldapUrl.isEmpty()) return false;
        Hashtable<String, String> env = ldapEnv();
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            SearchControls c = new SearchControls();
            c.setSearchScope(SearchControls.SUBTREE_SCOPE);
            c.setReturningAttributes(new String[]{"userAccountControl"});
            NamingEnumeration<SearchResult> rs = ctx.search(searchBase,
                    "(mail=" + escape(userEmail) + ")", c);
            if (!rs.hasMore()) {
                LOG.info("[LDAP-MGR] user not found by mail=" + userEmail + " → treating as inactive");
                return true;
            }
            String uac = strAttr(rs.next().getAttributes(), "userAccountControl");
            if (uac == null) return false;
            try {
                int v = Integer.parseInt(uac);
                boolean disabled = (v & 2) != 0;
                if (disabled) LOG.info("[LDAP-MGR] " + userEmail + " has ACCOUNTDISABLE flag set");
                return disabled;
            } catch (NumberFormatException nfe) {
                return false;
            }
        } catch (Exception e) {
            LOG.info("[LDAP-MGR] isUserInactive check failed for " + userEmail
                    + " (treating as active): " + e.getMessage());
            return false;
        } finally {
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Look up the manager of the user identified by {@code userEmail}.
     * Returns {@code fallback=true} if the LDAP call failed, the user has no
     * manager attribute, or the manager DN doesn't resolve to a mail address.
     */
    public Result findManagerByUserEmail(String userEmail) {
        Result r = new Result();
        r.fallback = true;
        if (userEmail == null || userEmail.isEmpty()
                || ldapUrl == null || ldapUrl.isEmpty()
                || serviceUsername == null || serviceUsername.isEmpty()) {
            return r;
        }

        Hashtable<String, String> env = ldapEnv();
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);

            // Step 1: find the user by mail = userEmail; pull manager DN + userAccountControl
            SearchControls c = new SearchControls();
            c.setSearchScope(SearchControls.SUBTREE_SCOPE);
            c.setReturningAttributes(new String[]{"manager", "userAccountControl"});

            NamingEnumeration<SearchResult> rs = ctx.search(searchBase,
                    "(mail=" + escape(userEmail) + ")", c);
            if (!rs.hasMore()) {
                LOG.info("[LDAP-MGR] user not found by mail=" + userEmail);
                return r;
            }
            SearchResult sr = rs.next();
            Attributes a = sr.getAttributes();
            String mgrDn = strAttr(a, "manager");
            String uac = strAttr(a, "userAccountControl");
            if (uac != null) {
                try {
                    int v = Integer.parseInt(uac);
                    r.disabled = (v & 2) != 0;  // ACCOUNTDISABLE bit
                } catch (NumberFormatException ignored) {}
            }
            if (mgrDn == null || mgrDn.isEmpty()) {
                LOG.info("[LDAP-MGR] no manager attribute for " + userEmail);
                return r;
            }

            // Step 2: resolve manager DN → mail + displayName
            try {
                Attributes mgr = ctx.getAttributes(mgrDn, new String[]{"mail", "displayName"});
                r.email = strAttr(mgr, "mail");
                r.displayName = strAttr(mgr, "displayName");
                if (r.email != null && !r.email.isEmpty()) r.fallback = false;
            } catch (Exception e) {
                LOG.info("[LDAP-MGR] failed to resolve manager DN " + mgrDn + ": " + e.getMessage());
            }
            return r;
        } catch (Exception e) {
            LOG.info("[LDAP-MGR] lookup failed for " + userEmail + ": " + e.getMessage());
            return r;
        } finally {
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    private Hashtable<String, String> ldapEnv() {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, Objects.requireNonNullElse(ldapUrl, ""));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        env.put(Context.SECURITY_CREDENTIALS, Objects.requireNonNullElse(servicePassword, ""));
        env.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
        env.put("com.sun.jndi.ldap.connect.timeout", "8000");
        env.put("com.sun.jndi.ldap.read.timeout", "8000");
        return env;
    }

    private static String strAttr(Attributes a, String key) {
        if (a == null) return null;
        javax.naming.directory.Attribute attr = a.get(key);
        if (attr == null) return null;
        try { Object v = attr.get(); return v == null ? null : v.toString(); }
        catch (Exception e) { return null; }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29");
    }
}
