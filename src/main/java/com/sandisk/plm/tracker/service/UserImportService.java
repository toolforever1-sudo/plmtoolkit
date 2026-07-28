package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resolves imported {name,email} rows against AD and classifies each one so the
 * import preview can show matched / ambiguous / no-match / already-has-access.
 * Dependencies are constructor-injected so the classifier is unit-testable.
 */
@Service
public class UserImportService {

    private final LdapAuthService ldap;
    private final UserPermissionsService perms;

    @Autowired
    public UserImportService(LdapAuthService ldap, UserPermissionsService perms) {
        this.ldap = ldap;
        this.perms = perms;
    }

    /** One resolved AD candidate, JSON-serializable for the frontend. */
    public static class Match {
        public String sAMAccountName;
        public String displayName;
        public String email;
        public Match() {}
        public Match(DirectoryUser u) {
            this.sAMAccountName = u.username;
            this.displayName = u.displayName;
            this.email = u.email;
        }
    }

    public static class PreviewRow {
        public String name;
        public String email;
        public String status;           // matched | ambiguous | nomatch | already-access | blank
        public Match match;             // non-null when matched / already-access
        public List<Match> candidates = new ArrayList<>();
        public String message;          // warning/error text for the UI
    }

    public static class PreviewResult {
        public List<PreviewRow> rows = new ArrayList<>();
        public Map<String, Integer> summary = new LinkedHashMap<>();
    }

    public PreviewResult resolveAll(List<Map<String, String>> rawRows) {
        PreviewResult res = new PreviewResult();
        int matched = 0, ambiguous = 0, nomatch = 0, already = 0, blank = 0;
        for (Map<String, String> r : rawRows) {
            PreviewRow row = resolveRow(nz(r.get("name")), nz(r.get("email")));
            res.rows.add(row);
            switch (row.status) {
                case "matched": matched++; break;
                case "ambiguous": ambiguous++; break;
                case "nomatch": nomatch++; break;
                case "already-access": already++; break;
                case "blank": blank++; break;
                default: break;
            }
        }
        res.summary.put("matched", matched);
        res.summary.put("ambiguous", ambiguous);
        res.summary.put("nomatch", nomatch);
        res.summary.put("alreadyAccess", already);
        res.summary.put("blank", blank);
        return res;
    }

    public PreviewRow resolveRow(String name, String email) {
        PreviewRow row = new PreviewRow();
        row.name = name;
        row.email = email;

        // Blank rows (both fields empty after trim) are a distinct outcome from a real no-match:
        // no AD search is attempted, and the UI can filter/hide them without confusing them with
        // rows that were searched but not found.
        if (name.isEmpty() && email.isEmpty()) {
            row.status = "blank";
            row.message = "Empty row — skipped.";
            return row;
        }

        DirectoryUser matched = null;
        List<DirectoryUser> candidates = new ArrayList<>();

        if (!email.isEmpty()) {
            List<DirectoryUser> hits = safeSearch(email);
            List<DirectoryUser> exact = new ArrayList<>();
            for (DirectoryUser u : hits) {
                if (u.email != null && u.email.equalsIgnoreCase(email)) exact.add(u);
            }
            if (exact.size() == 1) matched = exact.get(0);
            else if (exact.size() > 1) candidates = exact;
            else if (hits.size() == 1) matched = hits.get(0);
            else if (hits.size() > 1) candidates = hits; // name fallback intentionally skipped: email already produced candidates
        }
        if (matched == null && candidates.isEmpty() && !name.isEmpty()) {
            List<DirectoryUser> hits = safeSearch(name);
            if (hits.size() == 1) matched = hits.get(0);
            else if (hits.size() > 1) candidates = hits;
        }

        if (matched != null) {
            row.match = new Match(matched);
            if (hasAccess(matched.username)) {
                row.status = "already-access";
                row.message = (matched.displayName == null ? matched.username : matched.displayName)
                    + " already has access — skipped.";
            } else {
                row.status = "matched";
            }
        } else if (!candidates.isEmpty()) {
            row.status = "ambiguous";
            for (DirectoryUser u : candidates) row.candidates.add(new Match(u));
            row.message = "Multiple AD matches — pick the right person.";
        } else {
            row.status = "nomatch";
            row.message = "No AD user found for this row.";
        }
        return row;
    }

    private boolean hasAccess(String username) {
        if (username == null) return false;
        String key = username.trim().toLowerCase();
        // Already a managed user record?
        if (perms.allRecords().containsKey(key)) return true;
        // Already in the access DL?
        for (DirectoryUser u : safeDl()) {
            if (u.username != null && u.username.trim().toLowerCase().equals(key)) return true;
        }
        return false;
    }

    private List<DirectoryUser> safeSearch(String q) {
        try {
            List<DirectoryUser> hits = ldap.searchDirectory(q, 5);
            return hits == null ? Collections.emptyList() : hits;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<DirectoryUser> safeDl() {
        try {
            List<DirectoryUser> dl = ldap.listAccessGroupCandidates();
            return dl == null ? Collections.emptyList() : dl;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
