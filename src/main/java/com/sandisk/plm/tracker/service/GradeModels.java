package com.sandisk.plm.tracker.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain shapes for the Backlog Grade dispute/audit system (see
 * {@code docs/superpowers/plans/2026-06-30-backlog-grade-revamp.md}). Plain
 * public-field POJOs with no-arg constructors, Jackson-friendly, mirroring
 * {@link MeetingModels}. Persisted as JSON by {@link GradeStorageService}.
 */
public final class GradeModels {

    private GradeModels() {}

    /** Runtime-mutable grading config. Default = current behavior. */
    public static class RuleConfig {
        public int graceDays = 0;
        public boolean includeNoOwner = true;
        public int missingPenalty = 6;
    }

    /**
     * One audit record — an accepted item dispute ({@code kind="item"}) or a
     * runtime grading-logic change ({@code kind="rule"}). Stored under
     * {@code <gradeDir>/disputes/<id>.json} or {@code <gradeDir>/logic-changes/<id>.json}.
     */
    public static class GradeRecord {
        public String id;
        public String kind;            // "item" | "rule"
        public String action;          // item only: "exclude" (default/null) | "include"
        public String sessionId;       // meeting session id if live, else ""
        public String targetEcn;       // for kind=item
        public String ruleKey;         // "overdue" | "noowner" | "missing" for kind=rule
        public String title;           // ECN title (item) or signal label (rule)
        public String summary;         // human one-liner
        public String detail;          // e.g. "overdue grace 0d -> 7d"
        public String userReason;
        public String verifiedBy;      // "agile_record" | "attachment" | "cited_evidence"
        public String fieldQuote;      // quoted Agile field value when verifiedBy=agile_record
        public String attachmentRef;   // FileArchive id when verifiedBy=attachment
        public String aiReason;        // model/heuristic decision text
        public String gradeBefore;
        public String gradeAfter;
        public int scoreBefore;
        public int scoreAfter;
        public String actorAd;
        public String actorName;
        public String emailedTo;
        public long createdAt;
        public String model;           // grading model slug used ("" = default)
        public String ruleConfigJson;  // for kind=rule: serialized resulting RuleConfig (replay/revert)
        public boolean reverted;       // rule changes only
    }

    /** One sent email, appended to {@code <gradeDir>/outbox/outbox.jsonl}. */
    public static class OutboxEntry {
        public String id;
        public String to;
        public String subject;
        public List<String> body = new ArrayList<>();
        public long sentAt;
        public boolean it;             // true = IT-team (rule change); false = admin (item)
    }

    /** Result of evaluating a dispute (LLM or deterministic fallback). */
    public static class EvaluateResult {
        public String status;          // "accept" | "need_file" | "reject"
        public String verifiedBy;      // "agile_record" | "attachment" | "cited_evidence"
        public String fieldQuote;
        public String reason;
        public List<String> evidence = new ArrayList<>();
    }

    /** One resolved action from a free-form Ask-AI chat turn. */
    public static class ChatAction {
        public String action;          // "include" | "exclude"
        public String ecn;
        public String reason;
        public String decision;        // excludes: "accept" | "need_file" | "reject"; includes: "accept"
        public String verifiedBy;      // excludes only
        public String fieldQuote;      // excludes only
    }

    /** Parsed + adjudicated result of an Ask-AI chat message. */
    public static class ChatResult {
        public String reply;           // natural-language summary shown in the chat
        public List<ChatAction> actions = new ArrayList<>();
        public PolicyProposal policy;  // set when the message states a standing theme/policy
    }

    /**
     * A standing grading policy over a THEME of ECNs (not one specific item),
     * e.g. "exclude any ECN whose description says no Agile IT help is needed."
     * Persisted and auto-re-applied on every grade until reverted.
     */
    public static class ThemePolicy {
        public String id;
        public String predicate;       // normalized one-line rule
        public String userReason;      // original wording the user typed
        public String aiRationale;     // why the AI accepted it as a grading policy
        public String actorAd, actorName;
        public long createdAt;
        public boolean active = true;
    }

    /** AI adjudication of a proposed theme policy (soundness + the matching ECNs). */
    public static class PolicyProposal {
        public boolean accept;
        public String predicate;
        public String rationale;
        public String reason;          // when accept=false: what's wrong / what proof is needed
        public List<String> matched = new ArrayList<>();   // ECNs in the current backlog that match
    }
}
