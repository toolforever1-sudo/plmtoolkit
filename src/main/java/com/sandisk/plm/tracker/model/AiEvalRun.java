package com.sandisk.plm.tracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level run record persisted to ./cache/ai-eval-runs.json. Field names
 * match the JSON schema in docs/superpowers/specs/2026-05-03-ai-eval-tab-design.md.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AiEvalRun {

    public String runId;
    public String createdAt;       // ISO-8601 UTC, e.g. "2026-05-03T07:42:11Z"
    public String createdBy;       // username
    public String status;          // "RUNNING" | "DONE" | "FAILED"
    public String parentRunId;     // null unless this run was started via Rerun
    public String regradeOfRunId;  // V3 — set when this run is a Regrade (reuses parent's Q/A)

    public RunConfig config = new RunConfig();
    public List<Result> results = new ArrayList<>();
    public Summary summary = new Summary();
    public List<String> errors = new ArrayList<>();

    public static class RunConfig {
        public Persona persona = new Persona();
        public String chatbotModel;    // null = use production default; otherwise override the AI Help model for this run only
        public String testerModel;     // "@anthropic-eastus2/claude-sonnet-4-6"
        public String evaluatorModel;  // "@openai-eastus2/gpt-4o"
        public int questionCount;      // 5 | 10 | 20 | 50
        public boolean forceRerunAiHelp; // regrade flag: true = AI Help re-ran even though chatbotModel matched parent (used after prompt/KB edits)
    }

    public static class Persona {
        public String role;        // CIO | Director | Peer engineer | New hire | Power user
        public String team;        // PLM IT | Quality | Engineering | Operations | Other-text
        public String experience;  // None | Some | Daily user
        public String goal;        // free text, max 200 chars

        // V2 — only set when Simulate Real Person was used
        public String simulatedUsername;       // sAMAccountName
        public String simulatedDisplayName;    // full name from AD
        public String realTitle;               // verbatim AD title attribute
        public String realDepartment;          // verbatim AD department attribute
        public String realAccountAge;          // human-readable, e.g. "5y 3mo"
        public Integer realLoginCount90d;      // count from ActivityLogger
        public Boolean realIsPlmAdmin;         // group membership
    }

    public static class Result {
        public int qIndex;             // 1-based
        public String question;
        public String answer;
        public String grade;           // A | B | C | D | F | ERR (current effective)
        public String reason;          // current effective reason
        public long answerLatencyMs;
        public long gradeLatencyMs;

        // V2 — only set when human has overridden the AI grade. Original AI grade/reason
        // are preserved on first override and never overwritten by subsequent edits.
        public String aiGrade;
        public String aiReason;
        public String overriddenBy;
        public String overriddenAt;
        public String overrideNote;
    }

    public static class Summary {
        public double avgGradeNumeric; // A=4 B=3 C=2 D=1 F=0; 0 if all errored
        public String avgGradeLetter;  // A | B | C | D | F
        public int failureCount;       // count where grade ∈ {C,D,F,ERR}
        public long totalLatencyMs;
        public long avgAnswerLatencyMs; // mean of result.answerLatencyMs across non-ERR results
    }
}
