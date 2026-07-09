package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a Backlog-grade dispute through the three-tier evidence gate
 * (DB-verified against the ECN's Agile record → attachment → independent textual
 * evidence → reject). The primary path asks an LLM via {@link PortkeyClient};
 * on ANY failure (Portkey disabled, transport/parse error, timeout) it falls
 * back to {@link #evaluateDeterministic} — the on-prem path the handoff requires
 * and a faithful port of the JS module ({@code grade-logic.js evaluateDispute}).
 */
@Service
public class GradeEvaluatorService {

    private static final Logger LOG = Logger.getLogger(GradeEvaluatorService.class.getName());

    /** Production default model when the caller picks no explicit slug. */
    @Value("${plm.grade.model.default:@vertexai-global/anthropic.claude-opus-4-8}")
    private String defaultModel;

    @Autowired(required = false)
    private PortkeyClient portkeyClient;

    private final ObjectMapper json = new ObjectMapper();

    // Same not-required pattern as the JS _dbCheck and the prototype heuristic.
    private static final Pattern NOT_REQUIRED = Pattern.compile(
            "(agile )?it\\b[^.]*\\b(not|no|isn'?t|never)\\b[^.]*(need|requir|necess|involv)"
          + "|no it (help|involv|support)|it (help|support) not (need|requir)"
          + "|not an it (item|change|ecn)|business[- ]?(side|led|only)");
    private static final Pattern DOC_CLAIM = Pattern.compile(
            "descriptio|\\bnotes?\\b|comment|\\bfield\\b|screenshot|attach|\\bstates?\\b|\\bsays\\b"
          + "|not required|help not|per the ecn|agile (say|show|note)");

    /**
     * Evaluate a dispute. {@code recordDesc/Title/Status} are the readable Agile
     * fields for an item dispute (null for a rule dispute). Returns a never-null
     * {@link GradeModels.EvaluateResult}.
     */
    public GradeModels.EvaluateResult evaluate(String kind, String reason, boolean hasFile,
                                               String model, String recordDesc,
                                               String recordTitle, String recordStatus) {
        String slug = (model == null || model.isEmpty()) ? defaultModel : model;
        if (portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                GradeModels.EvaluateResult r = evaluateViaLlm(slug, kind, reason, hasFile, recordDesc, recordTitle, recordStatus);
                if (r != null && r.status != null) {
                    // A screenshot is trusted human-provided proof the model can't see — it must
                    // never ask for a file that is already attached. Coerce need_file → accept.
                    if (hasFile && "need_file".equals(r.status)) {
                        r.status = "accept";
                        if (r.verifiedBy == null || r.verifiedBy.isEmpty()) r.verifiedBy = "attachment";
                        r.reason = "";
                    }
                    return r;
                }
            } catch (Exception e) {
                LOG.warning("[GRADE-EVAL] LLM path failed (" + e.getMessage() + "); using deterministic fallback");
            }
        }
        return evaluateDeterministic(reason, hasFile, recordDesc);
    }

    // ------------------------------------------------------------------
    // LLM path
    // ------------------------------------------------------------------

    private GradeModels.EvaluateResult evaluateViaLlm(String slug, String kind, String reason, boolean hasFile,
                                                      String recordDesc, String recordTitle, String recordStatus) throws Exception {
        String system =
            "You are the adjudicator for a PLM backlog-grade dispute. A dispute is NEVER accepted on pure opinion, but a "
          + "reviewer who rests a claim on the ECN's own wording must be given the chance to PROVE it with a screenshot "
          + "rather than be rejected outright. Decide in this priority order:\n"
          + "(1) DB-VERIFIED — if the ECN's Agile record (Description/fields below) ALREADY substantiates the claim, "
          + "accept with verifiedBy=\"agile_record\" and put the exact supporting quote in fieldQuote.\n"
          + "(2) ATTACHMENT — if ATTACHMENT PRESENT is true AND the claim rests on the ECN's own wording, ACCEPT with "
          + "verifiedBy=\"attachment\". The attachment is trusted human-provided proof; you are NOT shown its contents "
          + "and must NOT ask for it again or reject for lack of it.\n"
          + "(3) NEED FILE — if the claim rests on the ECN's own wording (it references the description/notes/what the "
          + "ECN \"says\" or \"states\"), NO attachment is present, and the record does not already substantiate it, "
          + "return status=\"need_file\" to REQUEST a screenshot. Do NOT reject a wording-based claim merely because the "
          + "current record text doesn't show it — the reviewer may attach proof.\n"
          + "(4) INDEPENDENT EVIDENCE — a date, an Agile/ECN reference, a new owner, a CCB/policy/SOP note, or a linked "
          + "ticket → accept with verifiedBy=\"cited_evidence\".\n"
          + "Otherwise (pure opinion, no proof, not resting on retrievable wording) → status=\"reject\".\n"
          + "Mentioning an ECN number (including the disputed ECN's own id) is NOT evidence by itself.\n"
          + "Respond with ONLY a compact JSON object: {\"status\":\"accept|need_file|reject\","
          + "\"verifiedBy\":\"agile_record|attachment|cited_evidence|\",\"fieldQuote\":\"...\",\"reason\":\"short guidance\"}.";
        StringBuilder user = new StringBuilder();
        user.append("DISPUTE KIND: ").append(kind == null ? "item" : kind).append("\n");
        user.append("ATTACHMENT PRESENT: ").append(hasFile).append("\n");
        if (recordTitle != null && !recordTitle.isEmpty()) user.append("ECN TITLE: ").append(recordTitle).append("\n");
        if (recordStatus != null && !recordStatus.isEmpty()) user.append("ECN IT STATUS: ").append(recordStatus).append("\n");
        user.append("ECN AGILE RECORD (Description): ").append(recordDesc == null ? "(none)" : recordDesc).append("\n\n");
        user.append("DISPUTE RATIONALE: ").append(reason == null ? "" : reason);

        String raw = portkeyClient.chat(slug, system, user.toString(), 320);
        return parse(raw);
    }

    /** Lenient JSON parse — pull the four fields out of the model's reply. */
    private GradeModels.EvaluateResult parse(String raw) {
        if (raw == null) return null;
        GradeModels.EvaluateResult r = new GradeModels.EvaluateResult();
        r.status = firstGroup(raw, "\"status\"\\s*:\\s*\"([^\"]*)\"");
        r.verifiedBy = firstGroup(raw, "\"verifiedBy\"\\s*:\\s*\"([^\"]*)\"");
        r.fieldQuote = firstGroup(raw, "\"fieldQuote\"\\s*:\\s*\"([^\"]*)\"");
        r.reason = firstGroup(raw, "\"reason\"\\s*:\\s*\"([^\"]*)\"");
        if (r.status == null) return null;
        if (!"accept".equals(r.status) && !"need_file".equals(r.status) && !"reject".equals(r.status)) return null;
        if (r.verifiedBy != null && r.verifiedBy.isEmpty()) r.verifiedBy = null;
        return r;
    }

    private static String firstGroup(String s, String regex) {
        java.util.regex.Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------
    // Deterministic fallback (parity with grade-logic.js evaluateDispute)
    // ------------------------------------------------------------------

    public GradeModels.EvaluateResult evaluateDeterministic(String reason, boolean hasFile, String recordDesc) {
        GradeModels.EvaluateResult r = new GradeModels.EvaluateResult();
        String text = reason == null ? "" : reason.trim();
        String t = text.toLowerCase();
        List<String> ev = new ArrayList<>();
        if (Pattern.compile("\\b\\d{1,2}/\\d{1,2}(/\\d{2,4})?\\b").matcher(t).find()) ev.add("a date");
        // A bare ECN number is the dispute's subject, not proof — do NOT count it.
        if (Pattern.compile("\\bccb\\b").matcher(t).find()) ev.add("a CCB decision");
        if (Pattern.compile("owner|assign|reassign").matcher(t).find()) ev.add("an ownership change");
        if (Pattern.compile("verif|confirm|signed|approv|complete|closed|done").matcher(t).find()) ev.add("a verification");
        if (Pattern.compile("policy|sop|standard|process|cycle").matcher(t).find()) ev.add("a policy/SOP");
        if (Pattern.compile("http|link|ticket|jira|servicenow|snow").matcher(t).find()) ev.add("a linked record");
        if (Pattern.compile("uat|go-?live|deploy|release|prod").matcher(t).find()) ev.add("a release/UAT status");
        r.evidence = ev;

        boolean itNotRequired = recordDesc != null && NOT_REQUIRED.matcher(recordDesc.toLowerCase()).find();
        if (itNotRequired && NOT_REQUIRED.matcher(t).find()) {
            r.status = "accept"; r.verifiedBy = "agile_record"; r.fieldQuote = recordDesc; r.reason = "";
            return r;
        }
        if (hasFile) {
            ev.add("an attached screenshot of the ECN");
            r.status = "accept"; r.verifiedBy = "attachment"; r.reason = "";
            return r;
        }
        boolean docClaim = DOC_CLAIM.matcher(t).find();
        if (text.length() >= 24 && !ev.isEmpty()) {
            r.status = "accept"; r.verifiedBy = "cited_evidence"; r.reason = "";
            return r;
        }
        if (docClaim) {
            r.status = "need_file";
            r.reason = "That rests on the ECN's own wording, so I can't take it on your word — upload a screenshot of the ECN description showing that and I'll verify it and re-grade.";
            return r;
        }
        r.status = "reject";
        r.reason = text.length() < 24
            ? "I can't override on this alone — give me at least a full sentence of context and one piece of proof: a date, an Agile/ECN reference, the new owner, a CCB/policy note, or a screenshot."
            : "There's no verifiable evidence in that. Cite a date, an Agile/ECN reference, the new owner, a CCB/policy decision, or attach a screenshot and I'll re-grade.";
        return r;
    }

    // ------------------------------------------------------------------
    // Ask-AI chat — free-form, two-way (include/exclude), reason-logged
    // ------------------------------------------------------------------

    /**
     * Parse a free-form chat message into resolved include/exclude actions and
     * adjudicate each exclude through the evidence gate. {@code atRisk} items each
     * carry {id,title,desc,status,kind}; {@code excludedIds} are already excluded.
     * LLM-driven (resolves categories / counts / phrasing); deterministic fallback
     * on any error. Never persists — the client applies + re-grades.
     */
    public GradeModels.ChatResult chat(String message, List<Map<String, Object>> atRisk,
                                       List<String> excludedIds, String model) {
        String slug = (model == null || model.isEmpty()) ? defaultModel : model;
        if (portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                GradeModels.ChatResult r = chatViaLlm(slug, message, atRisk, excludedIds);
                if (r != null && r.reply != null) return r;
            } catch (Exception e) {
                LOG.warning("[GRADE-CHAT] LLM path failed (" + e.getMessage() + "); using deterministic fallback");
            }
        }
        return chatDeterministic(message, atRisk, excludedIds);
    }

    private GradeModels.ChatResult chatViaLlm(String slug, String message,
                                              List<Map<String, Object>> atRisk, List<String> excludedIds) throws Exception {
        String system =
            "You are the meeting leader's assistant for an IT-backlog grade. The user will ask, in free form, to "
          + "EXCLUDE at-risk items (drop them as not-at-risk / fine) and/or INCLUDE items (keep or put one back in "
          + "scope). They may refer to items by ECN id, by category (\"the no-owner ones\", \"the overdue ones\"), or "
          + "by count (\"exclude 3 of them, keep 1\"). Resolve their intent to specific ECN ids from the AT-RISK list "
          + "below.\n"
          + "RULES:\n"
          + "• Every action needs a reason (so the team understands why). If the user gave no reason at all, return NO "
          + "actions and ask for one in the reply.\n"
          + "• EXCLUDE is gated by evidence — set decision: \"accept\" only if the reason is backed by (1) the ECN's own "
          + "Agile record/description substantiating it (verifiedBy=\"agile_record\", quote it in fieldQuote), or (3) "
          + "independent evidence — a date, a new owner, a CCB/policy/SOP note, or a linked ticket (verifiedBy="
          + "\"cited_evidence\"). If the claim rests on the ECN's wording but the record doesn't confirm it, decision="
          + "\"need_file\". Otherwise decision=\"reject\". A bare ECN number is NOT evidence.\n"
          + "• INCLUDE is not evidence-gated — decision is always \"accept\" as long as a reason is given.\n"
          + "• If a count is ambiguous (which specific items), pick none and ask the user to name them in the reply — "
          + "do NOT loop or repeat a generic prompt; list the candidate ECNs.\n"
          + "• If the user states a STANDING RULE about a whole CLASS/THEME of ECNs (e.g. \"don't count any ECN whose "
          + "description says no Agile IT help is needed\") rather than naming specific items, return NO actions and "
          + "instead a top-level \"policy\" object with a normalized predicate, a rationale, and every backlog ECN that "
          + "matches it now. Judge a theme on common sense — it needs no per-ECN screenshot.\n"
          + "Return ONLY compact JSON: {\"reply\":\"short summary\",\"actions\":[{\"action\":\"include|exclude\","
          + "\"ecn\":\"ECN-...\",\"reason\":\"...\",\"decision\":\"accept|need_file|reject\",\"verifiedBy\":"
          + "\"agile_record|cited_evidence|\",\"fieldQuote\":\"...\"}],\"policy\":{\"predicate\":\"...\","
          + "\"rationale\":\"...\",\"matched\":[\"ECN-...\"]}}. Omit \"policy\" unless it's a standing theme rule.";
        StringBuilder user = new StringBuilder();
        user.append("USER MESSAGE: ").append(message == null ? "" : message).append("\n\n");
        user.append("AT-RISK ITEMS (JSON): ").append(json.writeValueAsString(atRisk)).append("\n");
        user.append("ALREADY EXCLUDED (ids): ").append(json.writeValueAsString(excludedIds == null ? new ArrayList<>() : excludedIds));
        String raw = portkeyClient.chat(slug, system, user.toString(), 700);
        return parseChat(raw);
    }

    private GradeModels.ChatResult parseChat(String raw) throws Exception {
        if (raw == null) return null;
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        JsonNode root = json.readTree(raw.substring(a, b + 1));
        GradeModels.ChatResult res = new GradeModels.ChatResult();
        res.reply = root.path("reply").asText("");
        JsonNode acts = root.path("actions");
        if (acts.isArray()) {
            for (JsonNode n : acts) {
                GradeModels.ChatAction ca = new GradeModels.ChatAction();
                ca.action = n.path("action").asText("");
                ca.ecn = n.path("ecn").asText("");
                ca.reason = n.path("reason").asText("");
                ca.decision = n.path("decision").asText("include".equals(ca.action) ? "accept" : "reject");
                ca.verifiedBy = emptyToNull(n.path("verifiedBy").asText(""));
                ca.fieldQuote = emptyToNull(n.path("fieldQuote").asText(""));
                if (ca.ecn != null && !ca.ecn.isEmpty() && ("include".equals(ca.action) || "exclude".equals(ca.action)))
                    res.actions.add(ca);
            }
        }
        JsonNode pol = root.path("policy");
        if (pol.isObject() && !pol.path("predicate").asText("").isEmpty()) {
            GradeModels.PolicyProposal pp = new GradeModels.PolicyProposal();
            pp.accept = true;
            pp.predicate = pol.path("predicate").asText("");
            pp.rationale = pol.path("rationale").asText("");
            for (JsonNode e : pol.path("matched")) { String s = e.asText(""); if (!s.isEmpty()) pp.matched.add(s); }
            res.policy = pp;
        }
        return res.reply.isEmpty() && res.actions.isEmpty() && res.policy == null ? null : res;
    }

    /** Deterministic fallback: id-match + include/exclude intent + the evidence gate. */
    private GradeModels.ChatResult chatDeterministic(String message, List<Map<String, Object>> atRisk, List<String> excludedIds) {
        GradeModels.ChatResult res = new GradeModels.ChatResult();
        String msg = message == null ? "" : message, low = msg.toLowerCase();
        Set<String> known = new LinkedHashSet<>();
        java.util.Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        if (atRisk != null) for (Map<String, Object> it : atRisk) { String id = str(it.get("id")); if (id != null) { known.add(id); byId.put(id, it); } }
        if (excludedIds != null) known.addAll(excludedIds);
        // match ids (full or bare number)
        List<String> matched = new ArrayList<>();
        for (String id : known) {
            String tail = id.replace("ECN-", "").replace("-PROJ", "");
            if ((low.contains(id.toLowerCase()) || low.contains(tail.toLowerCase())) && !matched.contains(id)) matched.add(id);
        }
        boolean include = Pattern.compile("\\b(include|keep|re-?include|in[ -]?scope|don'?t exclude|do not exclude|should (be )?(kept|considered|included|count|stay|remain)|still (count|consider|matter))\\b").matcher(low).find();
        boolean exclude = Pattern.compile("\\b(exclude|drop|remove|not at[ -]?risk|isn'?t at[ -]?risk|shouldn'?t count|should not count|off the list|fine|on track)\\b").matcher(low).find();
        if (matched.isEmpty()) {
            StringBuilder sb = new StringBuilder("Which item(s)? Name the ECN and whether to include or exclude it, with a reason. At-risk now: ");
            int n = 0; for (String id : byId.keySet()) { if (n++ > 0) sb.append(", "); sb.append(id.replace("-PROJ", "")); if (n >= 8) break; }
            res.reply = sb.toString();
            return res;
        }
        boolean doInclude = include && !exclude;
        for (String id : matched) {
            GradeModels.ChatAction ca = new GradeModels.ChatAction();
            ca.ecn = id; ca.reason = msg;
            if (doInclude) { ca.action = "include"; ca.decision = "accept"; }
            else {
                ca.action = "exclude";
                Map<String, Object> it = byId.get(id);
                GradeModels.EvaluateResult ev = evaluateDeterministic(msg, false, it == null ? null : str(it.get("desc")));
                ca.decision = ev.status; ca.verifiedBy = ev.verifiedBy; ca.fieldQuote = ev.fieldQuote;
            }
            res.actions.add(ca);
        }
        res.reply = "Processed " + matched.size() + " item(s).";
        return res;
    }

    // ------------------------------------------------------------------
    // Standing theme policies — validate a proposed policy + match ECNs
    // ------------------------------------------------------------------

    /**
     * Judge a proposed grading policy about a THEME of ECNs (not one specific
     * item) and, if sound, return the ECNs in {@code items} that match it. A
     * theme policy is judged on common-sense/policy grounds — it does NOT require
     * a per-ECN screenshot. Deterministic fallback on any error.
     */
    public GradeModels.PolicyProposal evaluatePolicy(String reason, List<Map<String, Object>> items, String model) {
        String slug = (model == null || model.isEmpty()) ? defaultModel : model;
        if (portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                String system =
                    "You decide whether a proposed BACKLOG-GRADING POLICY is a sound CONTENT/THEME rule. A content theme "
                  + "classifies ECNs by WHAT THEY ARE or WHAT THEIR RECORD SAYS (e.g. \"exclude any ECN whose description "
                  + "says no Agile IT help is needed\", \"drop documentation-only ECNs\") — NOT one specific ECN. It does "
                  + "NOT require a per-ECN screenshot; judge it on common sense (does it describe items that genuinely "
                  + "aren't IT-backlog risks?).\n"
                  + "CRITICAL: accept=false for arguments about NUMERIC RULES — overdue grace periods, days-overdue "
                  + "thresholds (\"under 7 days\"), high-priority/owner weighting, or the missing-target-date penalty. "
                  + "Those are handled by a separate numeric toggle, so return accept=false (reason: \"numeric rule — use "
                  + "the toggle\") and DO NOT turn them into a policy.\n"
                  + "If it IS a sound content theme: accept=true, normalize to a concise one-line predicate, and list every "
                  + "ECN in the backlog whose fields (description/title/status) match it. If incoherent, a single-ECN "
                  + "unverifiable wording claim, or pure opinion: accept=false with a short reason. Return ONLY JSON: "
                  + "{\"accept\":true|false,\"predicate\":\"...\",\"rationale\":\"why it's sound\",\"reason\":\"if rejected\",\"matched\":[\"ECN-...\"]}.";
                StringBuilder user = new StringBuilder();
                user.append("PROPOSED POLICY: ").append(reason == null ? "" : reason).append("\n\n");
                user.append("BACKLOG (JSON): ").append(json.writeValueAsString(items));
                String raw = portkeyClient.chat(slug, system, user.toString(), 900);
                GradeModels.PolicyProposal p = parsePolicy(raw);
                if (p != null) return p;
            } catch (Exception e) {
                LOG.warning("[GRADE-POLICY] LLM path failed (" + e.getMessage() + "); using deterministic fallback");
            }
        }
        return evaluatePolicyDeterministic(reason, items);
    }

    private GradeModels.PolicyProposal parsePolicy(String raw) throws Exception {
        if (raw == null) return null;
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        JsonNode root = json.readTree(raw.substring(a, b + 1));
        GradeModels.PolicyProposal p = new GradeModels.PolicyProposal();
        p.accept = root.path("accept").asBoolean(false);
        p.predicate = root.path("predicate").asText("");
        p.rationale = root.path("rationale").asText("");
        p.reason = root.path("reason").asText("");
        JsonNode m = root.path("matched");
        if (m.isArray()) for (JsonNode n : m) { String e = n.asText(""); if (!e.isEmpty()) p.matched.add(e); }
        return p;
    }

    /** Fallback: recognizes the "no Agile IT help needed" theme via the shared regex. */
    private GradeModels.PolicyProposal evaluatePolicyDeterministic(String reason, List<Map<String, Object>> items) {
        GradeModels.PolicyProposal p = new GradeModels.PolicyProposal();
        String r = (reason == null ? "" : reason).toLowerCase();
        boolean itTheme = NOT_REQUIRED.matcher(r).find() || (r.contains("description") && r.contains("it") && (r.contains("not") || r.contains("no")));
        if (!itTheme) { p.accept = false; p.reason = "I couldn't turn that into a clear grading policy — state a rule like \"exclude ECNs whose description says no Agile IT help is needed.\""; return p; }
        p.accept = true;
        p.predicate = "Exclude ECNs whose description indicates no Agile IT help is needed";
        p.rationale = "Items the description marks as not needing Agile IT help are not IT-backlog risks, so they shouldn't count toward the grade.";
        if (items != null) for (Map<String, Object> it : items) {
            String desc = str(it.get("desc")), id = str(it.get("id"));
            if (id != null && desc != null && NOT_REQUIRED.matcher(desc.toLowerCase()).find()) p.matched.add(id);
        }
        return p;
    }

    /**
     * Re-apply active policies on a fresh grade: for each policy, which ECNs in
     * {@code items} match it now. Returns policyId → matched ECN ids. Deterministic
     * fallback on any error.
     */
    public Map<String, List<String>> matchPolicies(List<Map<String, Object>> items, List<GradeModels.ThemePolicy> policies, String model) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        if (policies == null || policies.isEmpty() || items == null || items.isEmpty()) return out;
        String slug = (model == null || model.isEmpty()) ? defaultModel : model;
        if (portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                List<Map<String, String>> preds = new ArrayList<>();
                for (GradeModels.ThemePolicy pol : policies) { Map<String, String> m = new java.util.LinkedHashMap<>(); m.put("policyId", pol.id); m.put("predicate", pol.predicate); preds.add(m); }
                String system = "For each POLICY predicate, list which ECNs in the BACKLOG match it (by description/title/status). "
                  + "Return ONLY JSON: {\"matches\":[{\"policyId\":\"...\",\"ecns\":[\"ECN-...\"]}]}.";
                String userMsg = "POLICIES (JSON): " + json.writeValueAsString(preds) + "\n\nBACKLOG (JSON): " + json.writeValueAsString(items);
                String raw = portkeyClient.chat(slug, system, userMsg, 900);
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    JsonNode root = json.readTree(raw.substring(a, b + 1));
                    for (JsonNode m : root.path("matches")) {
                        String pid = m.path("policyId").asText("");
                        List<String> ecns = new ArrayList<>();
                        for (JsonNode e : m.path("ecns")) { String s = e.asText(""); if (!s.isEmpty()) ecns.add(s); }
                        if (!pid.isEmpty()) out.put(pid, ecns);
                    }
                    return out;
                }
            } catch (Exception e) {
                LOG.warning("[GRADE-POLICY] match LLM failed (" + e.getMessage() + "); deterministic fallback");
            }
        }
        // fallback: only the IT-not-required theme is machine-matchable
        for (GradeModels.ThemePolicy pol : policies) {
            List<String> ecns = new ArrayList<>();
            boolean itTheme = NOT_REQUIRED.matcher((pol.predicate == null ? "" : pol.predicate).toLowerCase()).find();
            if (itTheme) for (Map<String, Object> it : items) {
                String desc = str(it.get("desc")), id = str(it.get("id"));
                if (id != null && desc != null && NOT_REQUIRED.matcher(desc.toLowerCase()).find()) ecns.add(id);
            }
            out.put(pol.id, ecns);
        }
        return out;
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
