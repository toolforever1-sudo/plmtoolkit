package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.ItEnhancementsService.Row;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Eligibility + re-validation for Restart ECN creation. No SDK — pure logic
 *  over the cached IT-Enhancements rows. */
@Service
public class RestartEcnService {

    @Value("${restartEcn.eligibleItStatus:UAT complete, CAB Prep}") String eligibleItStatus;

    public static final class Candidate {
        public String ecn, proposal, itOwner, itOwnerLoginId, workflowStatus;
    }

    public static final class Validated {
        public boolean ok;
        public String error;
        public String itOwnerLogin;
        public List<String> acceptedEcns = new ArrayList<>();
        public List<String> droppedEcns = new ArrayList<>();
    }

    /** An assignable IT owner: {@code login} is the Agile loginid (what we send
     *  to the SDK and re-validate against), {@code name} is the display name. */
    public static final class ItOwner {
        public String login, name;
        public ItOwner(String login, String name) { this.login = login; this.name = name; }
    }

    /** The assignable "PLM IT team" roster: distinct IT owners across ALL cached
     *  rows (not just the eligible subset), keyed by login (case-insensitive),
     *  blank-login rows skipped, sorted by display name. Derived from the rows
     *  the toolkit already caches so it needs no Agile cell-options lookup
     *  (which is unavailable/slow when plm-agile-service is down). */
    public List<ItOwner> itOwnerRoster(List<Row> rows) {
        java.util.Map<String, ItOwner> byLogin = new java.util.LinkedHashMap<>();
        if (rows != null) for (Row r : rows) {
            if (r == null) continue;
            String login = r.itOwnerLoginId == null ? "" : r.itOwnerLoginId.trim();
            if (login.isEmpty()) continue;
            String key = login.toLowerCase();
            if (!byLogin.containsKey(key)) {
                String name = (r.itOwner == null || r.itOwner.trim().isEmpty()) ? login : r.itOwner.trim();
                byLogin.put(key, new ItOwner(login, name));
            }
        }
        List<ItOwner> out = new ArrayList<>(byLogin.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private boolean eligible(Row r) {
        return r != null && r.status != null
                && r.status.trim().equalsIgnoreCase(eligibleItStatus.trim());
    }

    public List<Candidate> candidates(List<Row> rows) {
        List<Candidate> out = new ArrayList<>();
        if (rows == null) return out;
        for (Row r : rows) {
            if (!eligible(r)) continue;
            Candidate c = new Candidate();
            c.ecn = r.ecnNumber; c.proposal = r.proposal;
            c.itOwner = r.itOwner; c.itOwnerLoginId = r.itOwnerLoginId;
            c.workflowStatus = r.workflowStatus;
            out.add(c);
        }
        return out;
    }

    /** Re-check submitted ECNs are still eligible (drop + report the rest) and
     *  that the chosen owner owns none of the accepted ECNs. */
    public Validated revalidate(List<Row> rows, List<String> requestedEcns, String itOwnerLogin) {
        Validated v = new Validated();
        v.itOwnerLogin = itOwnerLogin;
        java.util.Set<String> eligibleSet = new java.util.HashSet<>();
        java.util.Map<String, String> ownerByEcn = new java.util.HashMap<>();
        if (rows != null) for (Row r : rows) {
            if (eligible(r)) {
                eligibleSet.add(r.ecnNumber);
                ownerByEcn.put(r.ecnNumber, lc(r.itOwnerLoginId));
            }
        }
        if (requestedEcns != null) for (String e : requestedEcns) {
            if (e == null) continue;
            String ecn = e.trim();
            if (eligibleSet.contains(ecn)) v.acceptedEcns.add(ecn);
            else v.droppedEcns.add(ecn);
        }
        if (v.acceptedEcns.isEmpty()) {
            v.ok = false;
            v.error = "No eligible ECNs remain (all were dropped as not '" + eligibleItStatus + "').";
            return v;
        }
        if (itOwnerLogin == null || itOwnerLogin.trim().isEmpty()) {
            v.ok = false; v.error = "IT Owner is required."; return v;
        }
        String owner = lc(itOwnerLogin);
        for (String ecn : v.acceptedEcns) {
            if (owner.equals(ownerByEcn.get(ecn))) {
                v.ok = false;
                v.error = "IT Owner '" + itOwnerLogin + "' owns related ECN " + ecn
                        + " — pick an IT owner who does not own any bundled ECN.";
                return v;
            }
        }
        v.ok = true;
        return v;
    }

    private static String lc(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    /** Agile File Description for a Restart ECN attachment: the comma-joined ECN
     *  references followed by " — " and either the note (when the uploader typed
     *  one) or the filename. E.g. {@code "ECN-0245719, ECN-0245801 — jar for
     *  extensions"}, or with no note {@code "ECN-0245719 — build.properties"}.
     *  <p>2026-07-20 (Vikas): the filename fallback REPLACES the earlier
     *  "ECN number(s) only" rule, so a description is self-describing even when no
     *  note was typed. This is also what the attachments dialog previews live —
     *  the two must stay in step. */
    public static String buildFileDescription(List<String> ecns, String note, String filename) {
        StringBuilder sb = new StringBuilder();
        if (ecns != null) {
            boolean first = true;
            for (String e : ecns) {
                if (e == null || e.trim().isEmpty()) continue;
                if (!first) sb.append(", ");
                sb.append(e.trim());
                first = false;
            }
        }
        String tail = (note != null && !note.trim().isEmpty())
                ? note.trim()
                : (filename == null ? "" : filename.trim());
        if (!tail.isEmpty()) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(tail);
        }
        return sb.toString();
    }
}
