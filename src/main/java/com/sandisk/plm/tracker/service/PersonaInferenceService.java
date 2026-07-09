package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a "persona suggestion" for a real PLM Toolkit user by combining
 * AD attributes (title, department, account age, group membership) with
 * ActivityLogger usage stats (login count, recency).
 *
 * Used by the AI Eval V2 "Simulate Real Person" feature so the Tester model
 * can generate questions in that user's actual voice rather than from an
 * abstract dropdown bucket.
 */
@Service
public class PersonaInferenceService {

    @Autowired
    private LdapAuthService ldap;

    @Autowired
    private ActivityLogger activityLogger;

    /** Lightweight DTO mirroring the JSON the frontend consumes. */
    public static class PersonaSuggestion {
        public String username;
        public String displayName;
        public String title;             // verbatim AD title (may be null)
        public String department;        // verbatim AD department (may be null)
        public String accountAge;        // human-readable e.g. "5y 3mo", "unknown"
        public Integer loginCount90d;
        public Integer totalLoginCount;
        public Boolean isPlmAdmin;
        public String mappedRole;        // one of CIO/Director/Peer engineer/New hire/Power user
        public String mappedTeam;        // one of PLM IT/Quality/Engineering/Operations/Other(text)
        public String mappedExperience;  // None/Some/Daily user
    }

    /** Light dropdown DTO. */
    public static class UserListItem {
        public String username;
        public String displayName;
        public Long lastSeenMs;
        public Integer loginCount90d;
    }

    /**
     * Returns users who have logged into the toolkit at least once. Sorted by
     * most-recently-active first. Backed by ActivityLogger's persistent log.
     */
    public List<UserListItem> listPastLoggers() {
        long allTime = 0L; // since beginning of time -> reads the full file
        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(allTime);
        long ninetyDayCutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000;

        // Group by username, track latest seen + counts
        Map<String, UserListItem> byUser = new LinkedHashMap<>();
        Map<String, Integer> count90d = new HashMap<>();
        for (ActivityLogger.ActivityEntry e : all) {
            if (e.username == null || e.username.isEmpty()) continue;
            UserListItem item = byUser.get(e.username);
            if (item == null) {
                item = new UserListItem();
                item.username = e.username;
                item.displayName = e.displayName == null || e.displayName.isEmpty() ? e.username : e.displayName;
                item.lastSeenMs = e.timestamp;
                item.loginCount90d = 0;
                byUser.put(e.username, item);
            } else if (e.timestamp > item.lastSeenMs) {
                item.lastSeenMs = e.timestamp;
                if (e.displayName != null && !e.displayName.isEmpty()) item.displayName = e.displayName;
            }
            // Count "LOGIN" actions in the last 90 days as login count
            if (e.timestamp >= ninetyDayCutoff && e.action != null && e.action.toUpperCase().contains("LOGIN")) {
                count90d.merge(e.username, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, UserListItem> en : byUser.entrySet()) {
            en.getValue().loginCount90d = count90d.getOrDefault(en.getKey(), 0);
        }
        List<UserListItem> out = new ArrayList<>(byUser.values());
        out.sort((a, b) -> Long.compare(b.lastSeenMs == null ? 0 : b.lastSeenMs,
                                        a.lastSeenMs == null ? 0 : a.lastSeenMs));
        return out;
    }

    /**
     * Compute a persona suggestion. Falls back gracefully when AD lookup fails or
     * the user has zero activity. Never throws.
     */
    public PersonaSuggestion inferPersona(String username) {
        PersonaSuggestion s = new PersonaSuggestion();
        s.username = username;

        // --- AD attributes ---
        LdapAuthService.UserInfo ad = null;
        try {
            ad = ldap.lookupUserByUsername(username);
        } catch (Exception ignored) { /* AD may be unreachable */ }
        if (ad != null) {
            s.displayName = ad.displayName;
            s.title = ad.title;
            s.department = ad.department;
            s.accountAge = humanAccountAge(ad.accountCreatedDate);
        } else {
            s.accountAge = "unknown";
        }
        if (s.displayName == null || s.displayName.isEmpty()) s.displayName = username;

        // --- Admin group ---
        try {
            s.isPlmAdmin = ldap.isUserInAdminGroup(username);
        } catch (Exception e) {
            s.isPlmAdmin = false;
        }

        // --- Activity stats ---
        UsageStats usage = computeUsageStats(username);
        s.loginCount90d = usage.loginCount90d;
        s.totalLoginCount = usage.totalLoginCount;

        // --- Mapping ---
        s.mappedRole = mapRole(s.title, Boolean.TRUE.equals(s.isPlmAdmin), usage,
                                ad == null ? null : ad.accountCreatedDate);
        s.mappedTeam = mapTeam(s.department);
        s.mappedExperience = mapExperience(usage.totalLoginCount,
                                           ad == null ? null : ad.accountCreatedDate);
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────

    private static class UsageStats {
        int totalLoginCount;
        int loginCount90d;
        long lastSeenMs;
    }

    private UsageStats computeUsageStats(String username) {
        UsageStats u = new UsageStats();
        long allTime = 0L;
        long ninetyDayCutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000;
        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(allTime);
        for (ActivityLogger.ActivityEntry e : all) {
            if (e.username == null || !e.username.equalsIgnoreCase(username)) continue;
            if (e.action != null && e.action.toUpperCase().contains("LOGIN")) {
                u.totalLoginCount++;
                if (e.timestamp >= ninetyDayCutoff) u.loginCount90d++;
            }
            if (e.timestamp > u.lastSeenMs) u.lastSeenMs = e.timestamp;
        }
        return u;
    }

    static String humanAccountAge(String iso) {
        if (iso == null || iso.length() < 10) return "unknown";
        try {
            LocalDate created = LocalDate.parse(iso.substring(0, 10));
            LocalDate now = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
            Period p = Period.between(created, now);
            int years = p.getYears();
            int months = p.getMonths();
            if (years == 0 && months == 0) return "<1mo";
            if (years == 0) return months + "mo";
            if (months == 0) return years + "y";
            return years + "y " + months + "mo";
        } catch (Exception e) { return "unknown"; }
    }

    static String mapRole(String title, boolean isAdmin, UsageStats usage, String accountCreatedIso) {
        String t = title == null ? "" : title.toUpperCase();
        if (containsAny(t, "CIO", "CTO", "CEO", "EVP", "SVP", " VP ", "VP,", "VP "))
            return "CIO";
        if (t.contains("DIRECTOR")) return "Director";
        if (isAdmin && usage != null && usage.totalLoginCount >= 20) return "Power user";
        if (usage != null && usage.totalLoginCount < 5 && isYoungAccount(accountCreatedIso, 12))
            return "New hire";
        if (containsAny(t, "ENGINEER", "ANALYST", "DEVELOPER", "SPECIALIST", "ARCHITECT", "PROGRAMMER"))
            return "Peer engineer";
        return "Peer engineer";
    }

    static String mapTeam(String department) {
        if (department == null || department.trim().isEmpty()) return "Other";
        String d = department.trim();
        String dUp = d.toUpperCase();
        if (dUp.equals("PLM IT") || dUp.contains("PLM")) return "PLM IT";
        if (dUp.contains("QUALITY")) return "Quality";
        if (dUp.contains("ENGINEERING")) return "Engineering";
        if (dUp.contains("OPERATIONS")) return "Operations";
        return "Other";
    }

    static String mapExperience(int totalLoginCount, String accountCreatedIso) {
        if (totalLoginCount == 0) return "None";
        boolean newish = isYoungAccount(accountCreatedIso, 6);
        if (totalLoginCount <= 10 || newish) return "Some";
        return "Daily user";
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) return false;
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static boolean isYoungAccount(String iso, int monthsThreshold) {
        if (iso == null || iso.length() < 10) return false;
        try {
            LocalDate created = LocalDate.parse(iso.substring(0, 10));
            LocalDate now = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
            Period p = Period.between(created, now);
            int monthsTotal = p.getYears() * 12 + p.getMonths();
            return monthsTotal < monthsThreshold;
        } catch (Exception e) { return false; }
    }
}
