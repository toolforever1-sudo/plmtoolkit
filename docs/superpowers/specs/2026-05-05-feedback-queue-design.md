# Feedback Queue — Design Spec

**Date:** 2026-05-05
**Author:** Vikas Jindal (with Claude)
**Status:** Approved — proceeding to implementation

## Problem

User feedback submitted through the in-app "Send Feedback" form is currently **fire-and-forget email**. `SupportController./api/support/feedback` emails the admin (`pdl-plm-admin@sandisk.com`) and the reporter, then drops the data on the floor. Each email carries a random `PT-####` ticket ID generated at send time and never persisted. Attachments are emailed and discarded.

This makes triage hard:

- No central list of open requests.
- No way to mark something "done" or "won't fix".
- Reporters never hear back automatically when their request ships.
- Claude (or any other admin) has no place to read the queue.

## Goal

A persistent, admin-managed feedback queue with:

1. A central UI for the admin (`isPlmAdmin`) to see Open / Dismissed / Done items, mark them done with one click, and dismiss those that won't ship.
2. Marking an item "done" sends a styled "ready to test" email to the original requestor (CC the admin DL).
3. Non-admin reporters can see the status of their own submissions (read-only) via the user-name dropdown.
4. A clean REST surface so Claude can hit `/api/feedback/queue` with `plmadmin` credentials and triage from a chat session.

## Non-goals

- Comment threads on items.
- A full state machine (in-progress, blocked, etc.) — only Open / Done / Dismissed.
- Multi-admin collaboration affordances.
- Notifications on dismiss.
- Migrating prior PT-#### IDs from existing emails — those emails are orphans; the queue starts fresh with sequential IDs.

## Architecture

### Storage

One JSON file at `./data/feedback-queue.json` (path configurable via `app.feedback.queue.file`). Mirrors the `SavedSearchService` / `UserPermissionsService` pattern (single-file JSON with a `synchronized` writer).

```json
{
  "nextId": 17,
  "items": [
    {
      "ptId": "PT-12",
      "category": "Feature Request",
      "text": "Can we have a 30 day running traffic graph...",
      "reporterUsername": "1000296585",
      "reporterDisplayName": "Vikas Singh",
      "reporterEmail": "vikas.singh3@sandisk.com",
      "submittedAt": "2026-05-05T13:27:09Z",
      "attachmentPath": "data/feedback-attachments/PT-12/screenshot.png",
      "status": "open",
      "adminNote": null,
      "dismissReason": null,
      "resolvedBy": null,
      "resolvedAt": null
    }
  ]
}
```

- `status` ∈ {`open`, `done`, `dismissed`}.
- `nextId` is the sequential counter (PT-1, PT-2, …).
- All three statuses live in one file; UI filters in JS, not on disk.
- Attachments saved at `./data/feedback-attachments/<PT-id>/<filename>`. Path stored relative.

### New backend files

- `model/FeedbackItem.java` — POJO matching the JSON record above.
- `model/FeedbackQueueFile.java` — wrapper with `nextId` + `List<FeedbackItem>`.
- `service/FeedbackQueueService.java` —
  - `@PostConstruct load()` reads the JSON; if the file is absent and `app.feedback.queue.import-on-init=true`, runs `importFromActivityLog()` once and writes the seeded file.
  - `addItem(category, text, username, displayName, email, attachmentSourcePath)` — assigns next sequential `PT-####`, copies the attachment to the per-item dir, appends, saves.
  - `markDone(ptId, resolverUsername, adminNote)` — flips status, stamps `resolvedBy/resolvedAt`, saves; returns the updated item so the controller can email.
  - `markDismissed(ptId, resolverUsername, reason)` — same as above but `status=dismissed`, no email.
  - `listForAdmin()` — all items.
  - `listForUser(username)` — reporter-scoped read.
  - `findById(ptId)` — for attachment streaming.
  - All mutators `synchronized` and write through to disk.

### Controller

`controller/FeedbackQueueController.java` (admin gate uses the same pattern as `UserPermissionsController.isPermsAdmin` — `session.getAttribute("isPlmAdmin")`):

| Method | Path | Auth | Body | Returns |
|---|---|---|---|---|
| GET | `/api/feedback/queue` | logged in | — | admin → all; non-admin → own only |
| POST | `/api/feedback/queue/{ptId}/done` | admin only | `{ "adminNote": "…" }` | updated item; triggers resolve email |
| POST | `/api/feedback/queue/{ptId}/dismiss` | admin only | `{ "reason": "…" }` | updated item; no email |
| GET | `/api/feedback/queue/{ptId}/attachment` | admin OR reporter | — | streams the file |

### Modifications to `SupportController.feedback`

After the existing email-send block:

1. Save the attachment (if present) to `./data/feedback-attachments/<PT-id>/<original-filename>`.
2. Call `feedbackQueueService.addItem(...)` with the same `PT-####` ID that was used in the outgoing email.

The PT-#### ID generation moves from a one-shot `EmailTemplateService.generateRequestId("PT")` call to `feedbackQueueService.allocateNextId()` so the email and the persisted record share the same ID.

### Activity-log importer

On first startup with no queue file present, scan `./data/activity-log.jsonl` for rows where `action == "FEEDBACK"`. For each row:

- Parse `details` as `"<Category>: <text>"` (split on the first `": "`).
- `reporterUsername` ← `username`.
- `reporterDisplayName` ← `displayName`.
- `reporterEmail` ← look up in `UserPermissionsService` by username; if not found, leave `null`.
- `submittedAt` ← `timestamp`.
- `attachmentPath` ← `null` (never logged).
- `status` ← `open`.

Assign sequential PT-#### IDs in chronological order. Save once; subsequent restarts skip the import.

### Email on resolve

`service/FeedbackResolveEmailService.java` — uses `EmailTemplateService.wrap(...)`:

- **Subject:** `Feedback · ${category} · ${ptId} ready to test`
- **From:** `mail.from` (`PLM-Toolkit@sandisk.com`)
- **To:** `reporterEmail`
- **CC:** `app.admin-email` (`pdl-plm-admin@sandisk.com`)
- **Body:** eyebrow `READY TO TEST · ${ptId}`, serif title echoes the original (or "Your feedback is ready to test"), callout with the original request text, optional second callout with `adminNote` if present, footer matches the SanDisk pill pattern from CLAUDE.md.

If `reporterEmail` is null (backfilled item with no email lookup), the resolve flow records the status change and **skips the send** with a warning log line. The UI's ✅ button shows a sub-label "no email — won't notify" for those items.

If `app.feedback.email.outbound=false`, the resolve flow logs the rendered HTML to the application log instead of calling `Transport.send()`. Default `true` in `application.properties`; will be set to `false` in the local config during testing.

### Frontend — admin

A sub-section inside the existing **User Permissions** tab. Three filter pills `Open (n) · Dismissed · Done`. Each row renders the same card layout the feedback emails use (eyebrow, serif title, From / Email / Category rows, attachment chip if `attachmentPath != null`). Right side actions: ✅ Mark done | 🚫 Dismiss | 1-line note input (used for both actions).

### Frontend — non-admin "My feedback"

A new entry "My feedback" added to the user-name dropdown in the top-right (next to "Visible Tabs"). Opens a small modal listing the user's own items with status badge (Open / Done / Dismissed) and submitted date. Read-only.

## Data flow

```
User submits feedback
  → SupportController.feedback
      → emails admin DL (existing)
      → feedbackQueueService.addItem(...)
          → allocates next PT-####
          → saves attachment
          → appends to feedback-queue.json

Admin opens User Permissions tab → Feedback sub-section
  → GET /api/feedback/queue → renders rows

Admin clicks ✅ done (with optional note)
  → POST /api/feedback/queue/PT-12/done
      → service marks status=done, persists
      → resolve-email service sends "ready to test" email
      → returns updated item

Admin clicks 🚫 dismiss (with optional reason)
  → POST /api/feedback/queue/PT-12/dismiss
      → service marks status=dismissed, persists
      → no email sent

Reporter opens user-name dropdown → My feedback
  → GET /api/feedback/queue (non-admin → own only)
      → renders read-only modal
```

## Error handling

- Missing `data/` dir on first save → `mkdirs()` (matches `SavedSearchService.saveToDisk`).
- Concurrent writes → `synchronized` on the service singleton; one Spring Boot process is sufficient for current deployment.
- Activity-log row with malformed `details` (no `": "` separator) → import that row with `category="General"` and the full string as `text`.
- Resolve attempt on already-resolved item → 409 Conflict, no double-email.
- Attachment file missing on disk when `GET /attachment` is called → 404.

## Testing

Local-only test plan against `~/Documents/plm-toolkit 2/`:

1. **Backfill seed.** Move any existing `feedback-queue.json` aside, restart the local service, verify the importer creates the file with sequential PT-#### IDs corresponding to the 16 `FEEDBACK` rows in `activity-log.jsonl`.
2. **New submission.** With `app.feedback.email.outbound=false`, log in as `plmadmin`, submit feedback through the existing form, verify a new item lands in the JSON file and the queue UI.
3. **Mark done.** Click ✅ with a note. Verify item flips to `done` in JSON, resolve-email body is logged to console, no real email goes out.
4. **Dismiss.** Click 🚫 with a reason. Verify status flips to `dismissed`, no email.
5. **Backfilled item with null email.** Mark one of the imported items done. Verify the UI shows "no email — won't notify" sub-label, status flips, no email attempt.
6. **Non-admin scope.** Log in as a non-admin user, open the dropdown → My feedback, verify only that user's items show up. Verify `POST /done` returns 403 for non-admin.
7. **Real-send smoke test.** Flip `app.feedback.email.outbound=true`, submit one feedback item using `vikas.jindal@sandisk.com` as the reporter, mark done, confirm a real email arrives.
8. **Activity-log importer with no FEEDBACK rows.** Temporarily move `activity-log.jsonl` aside, ensure the queue starts empty without crashing.

## Configuration

Additions to `application.properties`:

```properties
app.feedback.queue.file=./data/feedback-queue.json
app.feedback.queue.import-on-init=true
app.feedback.attachments.dir=./data/feedback-attachments
app.feedback.email.outbound=true
```

For the local config, `app.feedback.email.outbound=false` will be set during testing.

## Out of scope / future

- Webhook out to Slack on new feedback.
- Per-tab feedback context (which tab the user was on when they submitted).
- Bulk operations on items.
- Multi-admin assignment.
