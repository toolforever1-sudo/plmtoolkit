package com.sandisk.plm.tracker.model;

public class FeedbackItem {
    public String ptId;
    public String category;
    public String text;
    public String reporterUsername;
    public String reporterDisplayName;
    public String reporterEmail;
    public String submittedAt;
    public String attachmentPath;        // first attachment, for back-compat with old UI
    public String attachmentFilename;    // first attachment's filename, for back-compat
    public java.util.List<String> attachmentPaths;      // PT-38: all attachments (paths)
    public java.util.List<String> attachmentFilenames;  // PT-38: all attachments (filenames)
    public String status;
    public String adminNote;
    public String dismissReason;
    public String resolvedBy;
    public String resolvedByDisplay;
    public String resolvedAt;

    // === AI triage + admin sign-off + auto-mark-done ===
    // See docs/superpowers/specs/2026-05-13-feedback-queue-ai-triage-design.md
    // All fields optional / null-by-default — older tickets without them
    // continue to work; missing fields just mean "not triaged yet" / "no
    // pending build" depending on the field.
    public String aiTag;                       // easy | hard | have_questions | gibberish | null
    public String aiAssessment;                // one-line summary of approach / complexity / dismissal reason
    public Double aiEffortHours;               // 0.25 / 1 / 4 / 16 buckets (null when unknown)
    public java.util.List<String> aiQuestions; // 1–3 questions when aiTag == "have_questions"
    public java.util.List<String> aiAnswers;   // parallel to aiQuestions, populated by the requestor
    public String aiTriagedAt;                 // ISO-8601 of the last triage call
    public String agentBuildJarSha;            // SHA-256 of the JAR the agent built to fix this ticket
    public String agentBuildAt;                // ISO-8601 when the agent stamped the SHA
    public boolean agentStarted;               // true once the agent's Maintenance Fix Flow began

    // === Effort tracking (estimated vs actual) ===
    // agentPolledAt marks the moment a poll cycle actually picked this ticket up
    // (NOT when admin approved — admin approval can sit unworked for hours/days
    // until the agent gets around to a poll cycle). actualHours is computed at
    // the moment the ticket transitions to done, comparing pickup to build-stamp
    // (or to resolved-at if no build was stamped). The ready-to-test email shows
    // estimated vs actual so the team can calibrate the AI's effort estimates
    // over time.
    public String agentPolledAt;               // ISO-8601 when /agent-pickup was first called
    public Double actualHours;                 // (agentBuildAt|resolvedAt - agentPolledAt) / 3600, frozen at done

    public FeedbackItem() {}
}
