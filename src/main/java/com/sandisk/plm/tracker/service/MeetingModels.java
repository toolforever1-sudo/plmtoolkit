package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain model for IT Enhancements <b>Meeting Mode</b> (live sessions, bubbles,
 * meeting records). See {@code MEETING-MODE-IMPLEMENTATION.md} §3.
 *
 * <p>This app has no relational store — like {@link DraftStorageService} the
 * meeting domain is persisted as JSON files (one per session / record) by
 * {@link MeetingStorageService}. These are plain POJOs so Jackson round-trips
 * them verbatim. Timestamps are epoch-millis; display clocks ({@code time})
 * are the leader's local wall-clock, stamped client-side per the spec (§9).
 *
 * <p>All nested types are {@code public static} so the controller and the
 * email service can reference them without a separate file per class.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class MeetingModels {

    private MeetingModels() {}

    /** One live meeting instance: a leader, attendees, start/end, and bubbles. */
    public static class Session {
        public String id;
        public String title;
        public String leaderAd;
        public String leaderName;
        public long startedAt;
        public Long endedAt;          // null = still LIVE
        public String status;         // LIVE | ENDED
        public String createdByAd;
        public List<Attendee> attendees = new ArrayList<>();
        public List<Bubble> bubbles = new ArrayList<>();
    }

    /** Attendee present "in the room". {@code ad} is the AD loginid used for
     *  both notify membership checks and email resolution. */
    public static class Attendee {
        public String ad;
        public String name;
        public String email;          // optional; resolved lazily for the recap
        public boolean leader;
    }

    /** A captured update. {@code AGILE} writes to the enhancement's Agile IT
     *  Log / target-date cell; {@code NOTE} is meeting-only (optional assignee
     *  = action item). */
    public static class Bubble {
        public String id;
        public String ecn;
        public String ecnTitle;       // change description/proposal — for email context
        public String kind;           // AGILE | NOTE
        public String text;
        public String field;          // TARGET_UAT | TARGET_GOLIVE | IT_LOG_NOTE (AGILE structured)
        public String oldValue;
        public String newValue;
        public String assigneeAd;     // NOTE action items
        public String assigneeName;
        public String authorAd;       // leader
        public String authorName;
        public long createdAt;
        public String time;           // leader-local display clock, e.g. "12:07"
        public String agileWriteStatus; // PENDING | WRITTEN | FAILED (AGILE only)
        public String agileError;     // non-null when agileWriteStatus = FAILED
        public boolean ownerNotInRoom;
        public String ownerAd;        // enhancement IT owner — for notify
        public String ownerName;
    }

    /** First-class action item derived from a NOTE bubble with an assignee.
     *  Status is the one mutable field on a frozen record (follow-through). */
    public static class ActionItem {
        public String id;
        public String bubbleId;
        public String assigneeAd;
        public String assigneeName;
        public String text;
        public String ecn;
        public String status;         // OPEN | DONE
        public Long closedAt;
    }

    /** Summary counts shown on the record's stat strip and the records list. */
    public static class Counts {
        public int agile;
        public int notes;
        public int actions;
        public int actionsOpen;
        public int notified;
    }

    /** Someone emailed at wrap-up (recap recipient or separate-notify owner). */
    public static class Recipient {
        public String name;
        public String email;
        public String type;           // ROOM | NOTIFIED
    }

    /** The frozen, read-only session created on send. Immutable except for
     *  action-item status (follow-through). */
    public static class Record {
        public String id;             // == session id
        public String title;
        public long date;             // endedAt, for sort + display
        public long endedAt;
        public String leaderAd;
        public String leaderName;
        public List<Attendee> attendees = new ArrayList<>();
        public Counts counts = new Counts();
        public List<ActionItem> actionItems = new ArrayList<>();
        public List<Bubble> log = new ArrayList<>();
        public List<Recipient> recipients = new ArrayList<>();
        // ---- v3 record management ----
        public boolean deleted;            // soft-delete: file kept for audit, hidden from lists
        public String deletedBy;
        public Long deletedAt;
        public boolean consolidated;       // produced by merging same-day records
        public List<String> sourceIds;     // provenance — record ids merged into this one
    }
}
