# PLM Toolkit — Self-Healing Feedback Loop

**Audience:** engineers who want to graft a similar "users file tickets, AI fixes the small ones, humans approve the rest" loop onto their own application.
**Author:** Vikas Jindal · PLM IT, SanDisk
**Last updated:** 2026-05-20

---

## 1. What the loop does, in one paragraph

A user clicks **Send Feedback** in the toolkit. The ticket lands in a persistent queue with a sequential `PT-####` id. A Claude Code agent — running on a developer's laptop, on a `/loop` timer — polls the queue every few minutes. For each new ticket it asks a Haiku model to grade the work: `easy`, `hard`, `have_questions`, or `gibberish`. The grade lands on the ticket as a coloured pill in the admin UI. When the admin clicks **Approve** (or when an `easy`-graded ticket comes in during an interactive session), the same agent reads the relevant source, makes the edit, builds the JAR locally, stamps the JAR's SHA-256 onto the ticket, and emails the admin "fix is staged — please deploy." When the admin runs `deploy.bat` on the prod Windows server, the new JVM boots, computes its own JAR's SHA, sees a ticket in `in_progress` whose stamped SHA matches, flips the ticket to `done`, and fires a "ready to test" email back to the original requestor. End to end, with no human typing the fix.

The interesting part is that there are **two independent loops** holding hands across a JAR-deploy boundary: a poller loop running in Claude Code on a laptop, and a cron loop running inside the JVM on prod. They synchronise on one piece of state — a SHA-256 hex string stamped onto the ticket.

---

## 2. State machine

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
              │      cron (in-JVM, every 5 min):                    │ agent builds JAR,
              │      running JAR SHA == ticket SHA?   ┌── yes ──►   │ stamps agentBuildJarSha
              └────────────────────────────────────►  │             │
                                                      └── no  ──►   │ stays in_progress
                                                                    ▼
                                                                 done
                                                            (auto-resolved
                                                             email sent)

Any state ─► dismissed   (admin Dismiss button, or AI gibberish auto-dismiss)
Any state ─► open        (admin Reopen button)
```

Six terminal-able states. `dismissed` and `done` are the only ones a ticket should end up in; everything else is in motion.

---

## 3. The four moving parts

### 3.1 The queue (persistent, JSON on disk)

One file at `./data/feedback-queue.json`. The data shape is intentionally boring — a wrapper with `nextId` plus a `List<FeedbackItem>`:

```json
{
  "nextId": 73,
  "items": [
    {
      "ptId": "PT-72",
      "category": "Bug",
      "text": "the export button on the ECR tab throws 500 …",
      "reporterUsername": "1000296585",
      "reporterDisplayName": "Vikas Singh",
      "reporterEmail": "vikas.singh3@sandisk.com",
      "submittedAt": "2026-05-20T18:11:04Z",
      "attachmentPaths": ["data/feedback-attachments/PT-72/trace.txt"],

      "status": "in_progress",

      "aiTag": "easy",
      "aiAssessment": "Null-pointer in ExportService when description is missing; one-line guard.",
      "aiEffortHours": 0.25,
      "aiQuestions": [],
      "aiAnswers": [],
      "aiTriagedAt": "2026-05-20T18:13:51Z",

      "agentBuildJarSha": "a3f12e5c7b89c1d4…",
      "agentBuildAt": "2026-05-20T18:39:12Z",
      "agentStarted": true,

      "resolvedBy": null,
      "resolvedAt": null
    }
  ]
}
```

Why JSON-on-disk instead of a real DB: this toolkit already had two other JSON-on-disk service patterns (`SavedSearchService`, `UserPermissionsService`); reusing the pattern meant no schema migration to coordinate with DBA. A single `synchronized` writer is enough — the toolkit runs as one Spring Boot process.

The class is `FeedbackQueueService` (Spring `@Service`). All mutators are `synchronized` and write through to disk in the same call. Reads are cheap because the whole file fits in memory.

### 3.2 The submission path

In the toolkit's existing `SupportController`, the **Send Feedback** form does two things on POST:

1. Email `pdl-plm-admin@sandisk.com` and the reporter (legacy fire-and-forget — kept so behaviour didn't change overnight).
2. Call `feedbackQueueService.addItem(...)` with the same `PT-####` id that was used in the outgoing email.

The id moved from a one-shot generator into `FeedbackQueueService.allocateNextId()` so the email body and the queue row share an id. That id is the only handle the user ever needs to talk about their ticket.

Attachments are saved to `./data/feedback-attachments/<PT-id>/<filename>` and the relative paths are stored in `attachmentPaths`. They're served back via `GET /api/feedback/queue/{ptId}/attachment` (admin OR reporter only).

### 3.3 The AI triage layer

This is the piece that turns a queue into a graded queue.

**Where it runs.** Outside the JVM. A Claude Code agent — usually running on a developer's laptop in a `/loop 5m` timer — polls `GET /api/feedback/queue` every few minutes. The toolkit JVM itself does **not** call any LLM for triage. Keeping the LLM call out-of-process means no API key inside the JVM, no rate-limit blast radius if a model goes down, and easy A/B-ing of prompts without redeploying. (The toolkit does have its own LLM calls elsewhere — see `AiEvalService` — but those are separate features.)

**Loop body** (full text in `.claude/commands/poll-feedback.md`):

1. Fetch the queue. Handle transient prod states (401 → re-login once; 503 → "in maintenance, skip"; 404 → "endpoint not deployed yet, skip"; network error → "VPN?, skip"). **Quiet ticks** are the norm; alerts are reserved for actual problems.
2. Diff against `state.seen` (a JSON file on the laptop tracking ptId → last-known-status).
3. For every ticket where the agent can confidently classify, push the verdict back to the server:
   ```
   POST /api/feedback/queue/PT-72/triage
   {
     "tag": "easy",
     "assessment": "Null-pointer in ExportService when description is missing; one-line guard.",
     "effortHours": 0.25,
     "questions": []
   }
   ```
   The server records the verdict on the ticket and the UI re-renders with a coloured pill (green = easy, amber = hard, blue = have_questions, gibberish auto-dismisses).
4. For tickets graded `easy` (or larger ones explicitly approved in chat), run the **maintenance fix flow** — described below.

The Haiku call returns this JSON shape:

```json
{ "tag": "easy" | "hard" | "have_questions" | "gibberish",
  "assessment": "one-line summary of approach or root cause",
  "effortHours": 0.25,
  "questions": [] }
```

`effortHours` lives in coarse buckets — `0.25 / 1 / 4 / 16`. It's a sort key, not a contract.

`have_questions` emits 1–3 questions back to the requestor. The toolkit emails the requestor; they answer in the **My Feedback** drawer; the status flips back to `triaging`; the next poll cycle re-runs the LLM with the answers in scope. The loop can take multiple turns before landing on `easy` / `hard`.

### 3.4 The auto-mark-done cron (inside the JVM)

A Spring `@Scheduled` job on `FeedbackQueueService`, default `0 */5 * * * *` (every 5 minutes, plus once on startup). It reads the running JAR's SHA-256 from `BuildInfoService`, then walks the queue:

```
for each ticket where status == "in_progress"
                AND agentBuildJarSha != null
                AND agentBuildJarSha == runningJarSha:
  ticket.status = "done"
  ticket.resolvedBy = "agent (auto)"
  ticket.resolvedAt = now
  send "ready to test" email to requestor (CC admin DL)
  log FEEDBACK_AUTO_DONE
persist queue
```

`BuildInfoService` computes the SHA once at startup. The JAR path is derived from `Application.class.getProtectionDomain().getCodeSource().getLocation()`. If the toolkit happens to be running unpacked (e.g. in an IDE), the SHA is `"unknown"` and nothing ever matches — fail-safe by default.

The cron running **on startup** is the bit that makes this loop satisfying: as soon as the new JVM finishes booting, it catches up on every ticket that was waiting for this deploy.

---

## 4. End-to-end: one ticket's life

Concrete walk-through, no hand-waving:

1. **18:11** — Vikas Singh files PT-72 via the in-app form: "export button throws 500". Trace file attached. Ticket lands `status: open`.
2. **18:13** — The poller running on Vikas Jindal's laptop wakes up, calls Haiku with the ticket text + the attached trace. Haiku says `easy`, 0.25h. The poller POSTs to `/triage`. UI now shows a green "easy" pill.
3. **18:13** (continued) — Because the chat session was interactive and Vikas said "go," the poller also POSTs to `/approve` (skipping the admin gate) and then `/agent-pickup` (anchors a timer used for "actual hours" reporting). Status is now `in_progress`.
4. **18:15** — The poller reads `controller/ExportController.java`, finds the NPE, adds a one-line guard, updates `whats-new.js`, runs `mvn -B -q clean package -DskipTests` with Corretto 8.
5. **18:39** — Build succeeds. Poller computes `shasum -a 256 target/plm-field-tracker-1.0.1.jar`, POSTs the hex to `/attach-build-sha`. Ticket now carries `agentBuildJarSha=a3f12e5c…`. Poller emails Vikas: "PT-72 staged at SHA a3f12e5c, please deploy."
6. **18:42** — Vikas runs `deploy.bat` on the prod Windows server. The watchdog stops the JVM, swaps in the new JAR, relaunches.
7. **18:43** — New JVM boots. `BuildInfoService` computes its own SHA: `a3f12e5c…`. The auto-mark-done cron runs once on startup, finds PT-72, marks it done, sends the "ready to test" email to Vikas Singh with the admin DL on CC.
8. **18:44** — Vikas Singh clicks the link in the email, retests, confirms.

No human typed the fix. Two humans were involved: the requestor (filed it, retested it) and the admin (clicked Deploy). Everything else was machine.

---

## 5. The meta-self-test

The pattern is recursive. When this AI-triage layer itself was implemented, the team filed a ticket on prod with `status: in_progress`, `agentBuildJarSha: <SHA of the staged JAR>`. When `deploy.bat` ran the new JAR, the new JVM's startup cron — the cron the ticket was implementing — found the ticket, matched the SHA, and resolved it.

The ticket about adding the auto-done logic was closed by the auto-done logic it added.

This is a useful smoke test of the whole thing: if you can self-resolve your own implementation ticket, every link in the chain works.

---

## 6. Why this works (the design choices that matter)

A few decisions that are non-obvious but load-bearing:

**The LLM lives outside the JVM.** Keeps the production process boring: it's still a Spring Boot app, no LLM SDK dependency, no API key in the runtime, no model-down outage on the critical path. The toolkit only knows about ticket states; it has no opinion about how those states were assigned.

**SHA is the synchronisation primitive, not a ticket id.** SHA-of-JAR is a hash of the artifact that's about to run. There's no clock skew, no "was this the actual build?" ambiguity, no race condition. The agent stamps "this is the build with the fix"; the JVM stamps "this is the build I'm running"; equality is the contract.

**The cron also runs on startup.** Without this, you have to wait up to 5 minutes after a deploy before the loop closes. With it, the first cron tick fires before anyone notices the deploy, and tickets resolve as a side-effect of the deploy itself.

**Status flow is linear with one optional Q&A side-trip.** No assignees, no priorities, no sub-tickets. The fewer fields a ticket has, the less an LLM can confuse itself about. Anything that doesn't fit on the linear path stays `open` and gets surfaced to a human.

**The "ready to test" email is sent by the JVM, never by the poller.** The poller knows when a fix is *staged*; only the JVM knows when a fix is actually *running in prod*. The email goes to the requestor only after the JVM has confirmed it owns the matching SHA. This is what gives the requestor a credible "yes, your bug is fixed" signal — not a "the bot says it's fixed" signal.

**Anything that touches auth, permissions, LDAP, DB schemas, scheduled jobs, or Python report scripts is a hard no-auto-fix.** This is in the skill prompt as a hard rule and the agent will not override it. The blast radius of a wrong call in those areas is too large; humans see them and decide.

---

## 7. Components inventory (for a port)

If you wanted to graft this onto a different application, here's the bill of materials:

| Piece | Where it lives | What you'd port |
|---|---|---|
| Persistent queue | `service/FeedbackQueueService.java` + `model/FeedbackItem.java` + `model/FeedbackQueueFile.java` | The JSON-on-disk pattern, the synchronized writer, the `allocateNextId` counter |
| Submission UI hook | `controller/SupportController.feedback` (existing endpoint, gets one new call to `addItem`) | One line to push every feedback submission into the queue with a stable id |
| Admin UI | `static/feedback-queue.js` (sub-section inside the User Permissions tab) | Three filter pills (Open / Dismissed / Done), Approve / Dismiss / Attach buttons, AI tag pills |
| Reporter UI | "My Feedback" drawer in the user-name dropdown | Read-only list of own tickets, answer-questions modal |
| AI triage layer | `.claude/commands/poll-feedback.md` (a Claude Code skill) | The poll-and-classify loop. **Runs outside the app**, not inside it. |
| LLM model | Portkey → `@anthropic-eastus2/claude-haiku-4-5-20251001` | Any small model that can do JSON-mode classification. Haiku is cheap and fast enough. |
| Build-info endpoint | `BuildInfoService` + `GET /api/admin/build-info` | Returns `{jarSha256, builtAt, version}` of the running artifact |
| Auto-mark-done cron | `@Scheduled` method on `FeedbackQueueService`, fires every 5 min and once on startup | Match running SHA against `agentBuildJarSha`, flip status, fire resolve email |
| Resolve email | `service/FeedbackResolveEmailService.java` | Uses the toolkit's `EmailTemplateService.wrap(...)` and the SanDisk-styled palette in `CLAUDE.md` |
| Activity log entries | Existing activity-log service, new event codes: `FEEDBACK_TRIAGE`, `FEEDBACK_APPROVE`, `FEEDBACK_AUTO_DONE`, `FEEDBACK_AUTO_DISMISS_GIBBERISH`, `FEEDBACK_ATTACH`, `FEEDBACK_ANSWER` | For audit, debugging, weekly digest emails |

The total Java surface is ~600 LOC. The skill file is ~250 lines of prose. Everything else is existing toolkit machinery (auth, email templating, activity log) used unchanged.

---

## 8. What's deliberately not in this loop

If you're tempted to reach for any of these, push back first — they were considered and rejected in the May-13 brainstorm:

- **No bulk admin actions.** One card, one decision. Bulk-approve is how you end up auto-fixing things that shouldn't have been auto-fixed.
- **No multi-admin assignment.** One admin DL receives the digest; the first admin to act wins.
- **No Slack / Teams notifications.** Email is the only out-of-band signal. Adding more channels multiplies the test surface.
- **No vision support for attached screenshots.** Haiku gets the ticket text + text-extractable attachments only. PNG/JPG screenshots are stored, served, but not fed to the LLM. (Could be lifted later; the cost wasn't worth it for v1.)
- **No cross-ticket batching during build.** Each ticket gets its own SHA stamp. If three tickets are built into one JAR, all three resolve on the same deploy — which is fine — but the agent doesn't try to optimise build count.
- **No re-opening with a new SHA stamp.** Once a ticket is `done`, it's terminal. If the fix doesn't work, the requestor files a new ticket. Keeps the audit trail linear.

---

## 9. References inside this repo

- Original queue design: `docs/superpowers/specs/2026-05-05-feedback-queue-design.md`
- AI-triage + auto-mark-done design: `docs/superpowers/specs/2026-05-13-feedback-queue-ai-triage-design.md`
- Prod poller skill: `.claude/commands/poll-feedback.md`
- Local poller skill (mirrors prod, plus stops/starts the local JVM during smoke tests): `.claude/commands/poll-local-feedback.md`
- Server-side code: `service/FeedbackQueueService.java`, `service/FeedbackResolveEmailService.java`, `controller/FeedbackQueueController.java`, `model/FeedbackItem.java`
- Frontend: `src/main/resources/static/feedback-queue.js`
