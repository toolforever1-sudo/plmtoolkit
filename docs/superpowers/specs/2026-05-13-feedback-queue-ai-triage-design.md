# Feedback Queue — AI triage + admin sign-off + auto-mark-done

**Status:** Approved (design 2026-05-13)
**Owner:** Vikas Jindal
**Date:** 2026-05-13

## Problem

The feedback queue today lets anyone submit a ticket, but every ticket — gibberish, trivial typo fix, large new feature — competes equally for admin attention. The `/poll-feedback` skill auto-fixes MINOR items without a human gate. There's no machine-readable signal on a ticket's complexity, no place for a structured back-and-forth with the requestor before scoping, and the "Mark done" step is fully manual even when the fix is already running in prod.

## Goal

Add a thin AI triage layer in front of the queue, an explicit admin sign-off gate before development starts, a structured Q&A loop with the requestor when the AI needs more info, and an automatic "mark done" mechanism when the deployed JAR demonstrably contains the fix.

## Decisions locked (from the brainstorm)

| # | Decision | Value |
|---|---|---|
| 1 | Status flow | New `triaging` + `awaiting_approval` statuses inserted between `open` and `in_progress` |
| 2 | AI tag values | `easy`, `hard`, `have_questions` (and `gibberish` → auto-dismiss) |
| 3 | Have-Questions loop | Auto re-triage when requestor answers (no admin re-trigger needed) |
| 4 | Approval = green light | Admin clicks Approve → status `in_progress` → next poll cycle, the agent runs the Maintenance Fix Flow |
| 5 | Attach spec | New button on every card (admin + requestor) → upload file → status flips to `triaging` → re-triage with attachment in scope |
| 6 | Auto-mark-done | Match the running JVM's JAR SHA-256 against the `agentBuildJarSha` recorded on the ticket; exact match → `done` |

## Status state machine

```
                                                      ┌── easy ─────┐
                                                      │             │
submit ─► open ─► triaging ─► awaiting_approval ──────┼── hard ─────┤── admin
              ▲           ▲                           │             │   Approve
              │           │                           └── have_qs ──┤
              │           │                                         │
              │           └─ attachment uploaded ──────────────────┘
              │              (re-triage on upload)                  │
              │                                                     ▼
              │                                                in_progress
              │                                                     │
              │      poller (or internal cron):                     │ agent does work,
              │      running JAR SHA == ticket SHA?    ┌── yes ──►  │ records agentBuildJarSha
              └─────────────────────────────────────►  │             │
                                                       └── no  ──►   │ stays in_progress
                                                                     ▼
                                                                  done
                                                              (auto-resolved
                                                               email sent)

Any state ─► dismissed   (admin Dismiss button, or gibberish auto-dismiss)
Any state ─► open        (admin Reopen button)
```

## Components

### Model — `FeedbackItem`

New fields (all optional, all default to null/false):
- `aiTag` — `"easy" | "hard" | "have_questions" | "gibberish" | null`
- `aiAssessment` — string. One-line suggested approach for easy/hard; cause for gibberish.
- `aiEffortHours` — number. Coarse estimate (0.25 / 1 / 4 / 16 buckets) — only for `easy` / `hard`.
- `aiQuestions` — `List<String>`. 1–3 questions when tag is `have_questions`; empty otherwise.
- `aiAnswers` — `List<String>`. Same length as `aiQuestions`, parallel.
- `aiTriagedAt` — ISO-8601 string. Last AI assessment time.
- `agentBuildJarSha` — string. SHA-256 of the JAR the agent built for this ticket.
- `agentBuildAt` — ISO-8601 string. When the agent committed the fix.
- `agentStarted` — boolean. True once the agent began the Maintenance Fix Flow (so the poller doesn't double-process).

Existing fields (`status`, `attachmentPaths`, etc.) unchanged.

The `status` field stays a string for back-compat but the allowed values grow:
`open` | `triaging` | `awaiting_approval` | `in_progress` | `dismissed` | `done`.

### Endpoints

| Method + path | Auth | What it does |
|---|---|---|
| `POST /api/feedback/{ptId}/approve` | admin | `awaiting_approval` → `in_progress` |
| `POST /api/feedback/{ptId}/answer` | requestor or admin | Attach `aiAnswers` to the ticket; flip status → `triaging` |
| `POST /api/feedback/{ptId}/attach` | requestor or admin | Add a new file to `attachmentPaths`; flip status → `triaging` (unless `skipReTriage=true` flag) |
| `POST /api/feedback/{ptId}/attach-build-sha` | admin / agent | Set `agentBuildJarSha` + `agentBuildAt`; status stays `in_progress` |
| `GET  /api/admin/build-info` | logged-in user | Returns `{ jarSha256, builtAt, version }` for the running JVM |

Existing `start` / `done` / `dismiss` / `reopen` endpoints all stay. `start` becomes a back-compat alias for `approve` (same status transition).

### `BuildInfoService`

New `@Service` that computes the SHA-256 of the running JAR once at startup and caches it. The JAR path is derived from `ProtectionDomain.getCodeSource().getLocation()` for the Application class. Falls back to `"unknown"` if computation fails (e.g. running unpacked in IDE).

Exposed via `GET /api/admin/build-info`:
```json
{
  "jarSha256": "a3f12e5c7b89...",
  "builtAt": "2026-05-13T03:14:22Z",
  "version": "1.0.1"
}
```

### Auto-mark-done scheduled job

New `@Scheduled(cron = "${app.feedback.auto-done-cron:0 */5 * * * *}")` method on `FeedbackQueueService` — runs every 5 min.

Logic:
1. Read current JAR SHA from `BuildInfoService`.
2. For every ticket with `status == "in_progress"` AND `agentBuildJarSha != null` AND `agentBuildJarSha == runningJarSha`:
   - Set `status = "done"`, `resolvedBy = "agent (auto)"`, `resolvedAt = now`.
   - Send the same "ready to test" email the manual Mark Done button sends today (via `SupportController.sendReadyToTestEmail` or equivalent).
   - Log `FEEDBACK_AUTO_DONE` activity event with the ptId + matched SHA.
3. Persist the queue.

Runs ON STARTUP too — so when Vikas deploys the new JAR, the very first cron tick within 5 min sees any pre-stamped tickets and resolves them.

### `/poll-feedback` skill rewrite

Replaces today's MINOR / NEEDS-CONFIRM / UNCLEAR branches.

New cycle:

**Step 0 — Fetch queue + login** (unchanged).

**Step 1 — Auto-done sweep.** For every `in_progress` ticket with `agentBuildJarSha`, GET `/api/admin/build-info`, match SHA. Auto-resolve. *(This is also done by the in-JVM cron; the skill duplicating it gives faster feedback during active work.)*

**Step 2 — Triage new items.** For every ticket with `status == "open"`:
1. Run `looksLikeGibberish(text)` heuristic.
   - True → status → `dismissed`, `dismissReason` set. Skip to next ticket.
2. Build an AI prompt with the ticket text + any attachment text + the toolkit's high-level feature inventory (so the AI knows what code areas the ask touches).
3. Call Haiku via `PortkeyClient`. Expected JSON:
   ```json
   { "tag": "easy" | "hard" | "have_questions",
     "assessment": "one-line summary of approach or complexity",
     "effortHours": 0.25,
     "questions": [] }
   ```
4. Persist via new admin endpoint `POST /api/admin/feedback/{ptId}/triage` (admin-only; accepts the AI's verdict). Status → `awaiting_approval`.

**Step 3 — Process approved items.** For every ticket with `status == "in_progress"` AND `agentStarted == false`:
1. Set `agentStarted = true` (POST to admin endpoint).
2. Run the existing Maintenance Fix Flow (read code → edit → build → stage → restart local → smoke-test → email admin).
3. After build, compute the staged JAR's SHA-256 → POST to `/api/feedback/{ptId}/attach-build-sha`.

**Step 4 — Re-triage tickets that asked for it.** For every ticket with `status == "triaging"` (got there via answer/attach):
1. Same as Step 2.2–2.4.

### Frontend

`feedback-queue.js`:
- New AI tag pill on every `awaiting_approval` and `in_progress` card. Color: green for `easy`, amber for `hard`, blue for `have_questions`. Includes `aiAssessment` text and effort estimate.
- New buttons:
  - `[Approve]` on `awaiting_approval` cards (becomes the existing `start` action).
  - `[View Questions]` on `have_questions` cards → opens questions modal.
  - `[📎 Attach]` on every non-final card (open, triaging, awaiting_approval, in_progress).
- `Done` tab cards show an "✨ auto-resolved on YYYY-MM-DD" badge when `resolvedBy == "agent (auto)"`.

Questions modal:
- Renders `aiQuestions` with a textarea per question.
- `Send answers` button POSTs `aiAnswers` to `/api/feedback/{ptId}/answer`.
- Same modal opens from the My Feedback drawer for the requestor.

`myFeedback` drawer (existing `myFeedbackOpen()` flow):
- New ❓ badge on tickets where `aiTag == "have_questions"`, sorted to top.
- Same Attach button as the admin queue.

### Email notifications

| Trigger | To | Subject |
|---|---|---|
| AI tags a ticket `have_questions` | requestor | `PT-X — Claude has N questions before we can scope this` |
| AI auto-dismisses (gibberish) | — | (no email) |
| Admin clicks Approve | requestor | `PT-X — approved, work starting` |
| Agent completes build + stages JAR | admin | `PT-X — fix staged at SHA <8 chars>, deploy when ready` |
| Auto-mark-done fires | requestor | `PT-X — ready to test, fix is now live` |
| Daily 09:00 digest | `pdl-plm-admin@sandisk.com` | `Feedback queue digest — N awaiting approval` |

Daily digest is a `@Scheduled(cron = "0 0 9 * * *")` job. Includes the count per tag + a list of `awaiting_approval` ptIds with their AI tags.

## Configuration (new keys in `application.properties`)

```
app.feedback.auto-done-cron=0 */5 * * * *
app.feedback.daily-digest-cron=0 0 9 * * *
app.feedback.ai-triage.model=@anthropic-eastus2/claude-haiku-4-5-20251001
```

## Security

- All `/feedback/*` endpoints require an authenticated session (existing behavior).
- `approve`, `dismiss`, `attach-build-sha`, and the admin triage endpoint require `isPlmAdmin`.
- `attach` and `answer` are allowed for the original requestor (`createdBy == username`) OR any admin.
- Auto-mark-done runs as the system; activity log entries show `resolvedBy = "agent (auto)"`.
- All status changes recorded in the activity log: `FEEDBACK_TRIAGE`, `FEEDBACK_APPROVE`, `FEEDBACK_AUTO_DONE`, `FEEDBACK_AUTO_DISMISS_GIBBERISH`, `FEEDBACK_ATTACH`, `FEEDBACK_ANSWER`.

## Testing

Manual smoke tests post-deploy:
1. Submit a ticket via the in-app form → it lands in `open`.
2. Wait ≤5 min OR run `/poll-feedback` → AI tag appears, status → `awaiting_approval`.
3. Click Approve → status → `in_progress`. Agent run starts on the next poll cycle.
4. Agent stages a JAR with the fix. Ticket has `agentBuildJarSha` stamped.
5. Run `deploy.bat`. After ≤5 min, the auto-done cron in the new JVM matches the SHA → ticket auto-resolves → requestor gets the "ready to test" email.
6. Submit `bcfkjdfbkdbkdbkd` → next poll cycle auto-dismisses with gibberish reason.
7. Submit an ambiguous request → AI tags `have_questions` → questions modal works → answer in My Feedback drawer → next poll cycle re-triages and lands on `easy` or `hard`.

## Self-test (the meta-validation)

Once this spec is implemented and the JAR is staged for prod, file a feedback ticket on prod with:
- `text`: "Implement feedback-queue AI triage workflow + auto-mark-done detection (this ticket)"
- `status`: `in_progress`
- `agentBuildJarSha`: SHA-256 of the staged JAR
- `agentStarted`: true
- `aiTag`: `hard`, `aiAssessment`: "Cross-cutting workflow change; touches FeedbackQueueService, /poll-feedback skill, frontend modals; ~600–800 LOC."

When `deploy.bat` runs, the new JVM boots → auto-done cron sees the in_progress ticket with matching SHA → marks done → emails Vikas. The ticket about adding auto-done is resolved by the auto-done logic it added. End-to-end self-test in one round trip.

## Out of scope

- Vision support for image attachments (AI can't see attached PNGs/screenshots; text/PDF only)
- Re-opening with a new SHA stamp (after Mark Done, the ticket is terminal)
- Cross-ticket batching during build (the SHA stamp is per-ticket; multiple tickets built in one JAR all get the same stamp)
- Bulk admin actions (approve N at once) — single-card workflow only in v1
- Slack / Teams notifications (email only)
