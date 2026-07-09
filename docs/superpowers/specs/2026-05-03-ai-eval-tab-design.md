# AI Eval Tab — Design

**Date:** 2026-05-03
**Author:** Vikas Jindal (PLM IT) + Claude
**Driver:** Vikas — POC to demo AI evaluation patterns to the team and use the output as structured feedback to improve the AI Help chatbot
**Audience:** Vikas (admin-only feature)

## Problem

The AI Help chatbot in this app is a black box once it ships. There's no systematic way to know whether it actually answers the questions real users would ask — let alone whether a fix made it better or worse. Ad-hoc prompting catches obvious bugs but misses persona-specific gaps (a CIO and a new-hire engineer fail at very different things), and there's no record of what was tested or how it scored.

## Goal

A new admin-only tab that:

1. Lets the admin configure a "Tester" persona (role, team, experience, goal) and an "Evaluator" model.
2. Auto-generates N realistic questions from the persona, fires them at the existing AI Help endpoint, and grades each answer A–F with a one-sentence reason.
3. Persists every run, computes per-run aggregates (avg grade, failure count), and shows past runs in a table with a Δ-grade column vs prior runs of the same config.
4. Provides a one-click "Rerun" to re-execute a past config (after a fix) for direct before/after comparison.
5. Exports the failure-only subset as a focused markdown brief (`./debug-output/eval-latest.md`) so Claude in VS Code can read it and propose code/prompt changes that address the patterns.

## Non-goals

- Multi-turn conversations between Tester and AI Help (single-turn only — POC simplicity).
- Manual question banks or curated test corpora — the Tester generates fresh questions each run.
- Annotation/ticketing on individual failures (mark-as-fixed toggles, etc.) — the rerun-and-compare loop is the source of truth.
- Parameterizing AI Help itself (different models on the chatbot side) — we grade AI Help as it exists today.
- Cross-run analytics dashboards (week-over-week trendlines, etc.) — JSON file is greppable; build dashboards if/when they're needed.
- Multi-user concurrent runs — single run per server at a time.

## Decisions made during brainstorming

| # | Decision | Choice |
|---|---|---|
| 1 | How does the Tester come up with questions? | Autonomous generation per run, with full Q-bank logged |
| 2 | Single- or multi-turn evaluation? | Single-turn |
| 3 | Persona shape | Structured fields (Role, Team, Experience, Goal) |
| 4 | Evaluator output shape | Letter grade + one-sentence reason |
| 5 | Persistence + history UX | JSON log + "Past runs" table + Δ-grade column |
| 6 | Access control | Admin-only |
| 7 | Run size | Dropdown: 5 / 10 / 20 / 50 questions |
| 8 | Sync vs async runs | Sync with SSE progress streaming |
| 9 | Tester vs Evaluator model | Must be different models (validated frontend + backend) |
| 10 | Grade scale | 5 grades (A/B/C/D/F), no `+/-` modifiers |
| 11 | Latency capture | Stored per question (answer + grade latency) |
| 12 | "Errored question continues" rule | Continue and mark `grade: "ERR"`, count toward failures |
| 13 | "Export for Claude" loop closure | Markdown brief written to `./debug-output/` |

## Architecture

### Approach: Lean POC (no async machinery, no DB, no MCP server)

Three new pieces, plus one targeted refactor of existing code.

#### New backend (Java)

| File | Purpose |
|---|---|
| `controller/AiEvalController.java` | HTTP endpoints (see Endpoints section below) |
| `service/AiEvalService.java` | Orchestrator — runs the generate→answer→grade loop |
| `service/PortkeyClient.java` | **Refactored from existing duplicated code** — single helper for all Vortex calls |

#### Refactor in scope

The existing `HttpURLConnection`-based Portkey/Vortex call code is duplicated across **7 service classes** today (`AiHelpController`, `RejectionTrackerEmailService`, `ReportService`, `MonitorAnalysisService`, `WhatsNewDigestService`, `DeltaReportService`, `DebugAssistantService`). The eval loop calls Vortex 3× per question (Tester gen, AI Help, Evaluator) and duplicating the boilerplate an 8th time is silly. Extract once into `PortkeyClient.chat(model, systemPrompt, userMessage, maxTokens)`. All 7 existing call sites swap to the new helper as part of this work.

#### New frontend

| File | Purpose |
|---|---|
| `static/ai-eval.js` | Page controller — form submit, EventSource handling, table render |
| `static/index.html` (edited, not new) | Add a new tab section inline — matches existing convention (Reports, Monitor, ChangeReview tabs all live inside `index.html`) |

#### No new infra

No DB, no message queue, no new ports, no new external services. Cache file lives at `./cache/ai-eval-runs.json`. Reuses the existing Vortex routing (`portkey.base-url` config) added during the prior session.

### Provider/model strings (verified against Vortex AI Providers)

| Friendly name | Full slug |
|---|---|
| Claude Sonnet 4.6 | `@anthropic-eastus2/claude-sonnet-4-6` |
| GPT-4o | `@openai-eastus2/gpt-4o` |
| Gemini 2.5 Pro | `@vertexai-global/gemini-2.5-pro` |

Implementation will sanity-check the exact model names within each provider via Vortex's `/models` endpoint or smoke test before wiring the dropdown — but the provider slugs above come directly from the Vortex AI Providers screen.

## Data flow — one run, end to end

1. **Frontend submits** `POST /api/ai-eval/runs` with body:
   ```json
   {
     "persona": {"role":"CIO","team":"PLM IT","experience":"None","goal":"..."},
     "testerModel": "@anthropic-eastus2/claude-sonnet-4-6",
     "evaluatorModel": "@openai-eastus2/gpt-4o",
     "questionCount": 10,
     "parentRunId": "uuid-or-null"
   }
   ```
   Backend validates, generates `runId` (UUID v4), persists a placeholder row with `status=RUNNING`, returns `{runId}`. (`PENDING` is not used — runs go straight to `RUNNING` since execution kicks off in the same request handler.)

2. **Frontend opens** `EventSource('/api/ai-eval/runs/{runId}/stream')`. Listens for events: `questions-ready`, `answer-received`, `graded`, `run-complete`, `run-failed`.

3. **Backend (`AiEvalService`) starts the loop in a worker thread:**

   - **Step A — Generate questions.** One Vortex call to the Tester model. System prompt: *"You are simulating a {role} on the {team} team with {experience} experience, trying to {goal}. Generate {N} distinct questions you'd ask the AI Help chatbot. Output as a JSON array of strings — no prose, no markdown."* Emit SSE event `questions-ready`. ~3–5s.

   - **Step B — For each question (sequential):**
     - Call **AI Help** via `PortkeyClient` with the same system prompt AI Help uses today. Emit `answer-received` with `{qIndex, question, answer, latencyMs}`. ~2–4s each.
     - Call the **Evaluator** with system prompt: *"You are grading a chatbot answer for a {role} user trying to {goal}. Output JSON: {grade: 'A'|'B'|'C'|'D'|'F', reason: '...'}. No prose outside the JSON."* User message embeds the Q/A pair. Each grading call is **stateless** — no shared conversation between gradings within a run. Emit `graded`. ~2–3s each.

   - **Step C — Finalize.** Compute `summary` (avg grade as numeric, failureCount = grades ≤ B). Update persisted run to `status=DONE`. Emit `run-complete`.

4. **Frontend renders** results in real time as events arrive — a growing table with progress bar.

**Why sequential, not parallel:** token-budget caution, AI Help may rate-limit, ordered SSE is trivial to render. 10 questions × ~7s = ~70s. If 50-question runs feel painful, parallelize the inner loop with a thread pool of 3–5 in a future iteration.

**Why worker thread:** Spring's `SseEmitter` blocks if work runs inline. Fire-and-forget the worker; controller returns the emitter immediately.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/ai-eval/runs` | Start a run. Returns `{runId}`. Validates same-model-rejection + persona fields. Admin-only. |
| `GET` | `/api/ai-eval/runs/{runId}/stream` | SSE stream for one run's progress. Closes on `run-complete` or `run-failed`. |
| `GET` | `/api/ai-eval/runs/{runId}` | Snapshot of one run (used by SSE-fallback polling). |
| `GET` | `/api/ai-eval/runs` | List all past runs (newest first), excluding heavy `results[]` fields — list view only. |
| `GET` | `/api/ai-eval/runs/{runId}/results` | Full results for one run (used to populate the expand-row view). |
| `POST` | `/api/ai-eval/runs/{runId}/export` | Write `./debug-output/eval-{runId}.md` and overwrite `./debug-output/eval-latest.md`. Returns the file path. |

All endpoints behind the existing admin-only filter (same gate as Debug Assistant).

## UI layout

One tab, **AI Eval (admin)**, single page, three vertically-stacked sections.

### Section A — Configure run (always visible, top)

Compact card, side-by-side two columns.

**Tester column (left):**
- Role dropdown: *CIO · Director · Peer engineer · New hire · Power user*
- Team dropdown: *PLM IT · Quality · Engineering · Operations · Other (text)*
- Experience radio: *None / Some / Daily user*
- Goal: single-line free text (required, non-empty after trim, max 200 chars)
- Model dropdown: *Claude Sonnet 4.6 (default) · GPT-4o · Gemini 2.5 Pro*

**Evaluator column (right):**
- Model dropdown: *GPT-4o (default) · Claude Sonnet 4.6 · Gemini 2.5 Pro* — option matching Tester's current model is **disabled** with tooltip *"Can't grade with the same model used to generate."*
- Question count dropdown: *5 / 10 / 20 / 50*

**Primary button:** **Run eval**. Disables on submit, re-enables on `run-complete` or `run-failed`.

### Section B — Live run (visible during/after a run)

- Progress bar at top: *"Question 4 of 10 — grading…"*
- Growing table, one row per question:
  | # | Question | Answer | Grade | Reason |
  |---|---|---|---|---|
  - Question/Answer columns are truncated; click expands inline.
  - Grade column uses colored pills: A/B = green (`#1F8A4C`), C = amber (`#C7801B`), D/F/ERR = red (`#B8342B`).
- Rows append in real time as SSE events arrive.
- After completion: a summary strip — *"10 questions • avg grade B • 3 failures (≤B)"*.

### Section C — Past runs (always visible, bottom)

Table of every recorded run, newest first. No pagination — full list rendered (typical use is dozens of runs; if it ever exceeds ~500, add pagination then).

| Date | Persona | Eval model | # Q's | Avg grade | # fails | Δ vs prev | Actions |
|---|---|---|---|---|---|---|---|

- **Persona** column compact: e.g., `CIO · PLM IT · None`.
- **Δ vs prev:** computed at read time. "Prev" = closest prior run with **identical** `persona + testerModel + evaluatorModel + questionCount` (strict match). Renders as `↑ +0.4` (green) or `↓ -0.3` (red) or `—` if no prior match.
- **Actions:** three icons per row:
  - **Rerun** — fires `POST /api/ai-eval/runs` with the same config + `parentRunId`. New run appears at the top.
  - **Export for Claude** — fires `POST /api/ai-eval/runs/{runId}/export`. Toast: *"Exported to ./debug-output/eval-latest.md — open in VS Code and ask Claude to review."*
  - **Expand chevron** — expands the row inline to show the full Q/A/grade/reason table read-only.

### Look & feel

Matches existing tabs. Uses CLAUDE.md email-design palette as the visual baseline:
- Primary `#4a6fa5`, page bg `#FAFAF7`, card bg `#fff`, border `#E8E6DF`, header bg `#2c3e50`.
- IBM Plex Sans for body, IBM Plex Mono for grades and timestamps.
- No new visual language.

## Persistence schema

One JSON file: `./cache/ai-eval-runs.json`. Append-only. Loaded into memory on startup (small — 1000 runs × 50 questions ≈ 50MB worst case; typical use is dozens of runs at most).

### Top-level

```json
{
  "version": 1,
  "runs": [ /* array of run objects, newest first */ ]
}
```

### Run object

```json
{
  "runId": "uuid-v4",
  "createdAt": "2026-05-03T07:42:11Z",
  "createdBy": "plmadmin",
  "status": "DONE",
  "parentRunId": "uuid-v4-or-null",
  "config": {
    "persona": {
      "role": "CIO",
      "team": "PLM IT",
      "experience": "None",
      "goal": "trying to find what changed on ECN-12345"
    },
    "testerModel": "@anthropic-eastus2/claude-sonnet-4-6",
    "evaluatorModel": "@openai-eastus2/gpt-4o",
    "questionCount": 10
  },
  "results": [
    {
      "qIndex": 1,
      "question": "How do I see all changes made by Peter Zhu last week?",
      "answer": "You can use the Activity tab and filter by user...",
      "grade": "B",
      "reason": "Mentioned the Activity tab but didn't explain the date range picker; CIO would not know it's there.",
      "answerLatencyMs": 2340,
      "gradeLatencyMs": 1820
    }
  ],
  "summary": {
    "avgGradeNumeric": 3.2,
    "avgGradeLetter": "B",
    "failureCount": 3,
    "totalLatencyMs": 67890
  },
  "errors": []
}
```

**Status values:** `RUNNING` · `DONE` · `FAILED`. (`PENDING` reserved for a future async-runs feature; not currently used.)

**Grade values:** `A` · `B` · `C` · `D` · `F` · `ERR` (only for individual question results, never in summary).

**Numeric mapping for `avgGradeNumeric`:** `A=4 · B=3 · C=2 · D=1 · F=0`. `ERR` rows are excluded from the numeric average but counted in `failureCount`. If **all** questions errored, `avgGradeNumeric=0` and `avgGradeLetter="F"` (worst-case display, not a real grade).

**Failure threshold:** `failureCount` = count of results where `grade ∈ {C, D, F, ERR}` (i.e., numeric ≤ 2 or ERR). This matches the user-facing rule "anything B or below" — note that `B` itself is *passing*, only **strictly below B** counts as a failure.

### Concurrency

Writes guarded by a `synchronized` block in `AiEvalService`. Single Spring bean instance. Only one run per server at a time (POC scope).

### Δ-vs-previous

Computed at read time (not stored). Scan past runs for the closest prior run with identical `persona + testerModel + evaluatorModel + questionCount` and subtract `summary.avgGradeNumeric`.

## Export-for-Claude markdown format

Written to `./debug-output/eval-{runId}.md` and overwritten to `./debug-output/eval-latest.md`. Both gitignored.

```markdown
# AI Help Eval — Failures from run {runId}

**Run date:** 2026-05-03 07:42 UTC
**Persona:** CIO · PLM IT · No prior experience
**Goal:** trying to find what changed on ECN-12345
**Tester model:** Claude Sonnet 4.6
**Evaluator model:** GPT-4o
**Score:** avg B, 3 of 10 failed (≤B)

## Current AI Help system prompt (snapshotted at export time)

<verbatim copy of the system prompt currently used by AiHelpController.callPortkey for AI Help>

## Failed questions (grades ≤ B)

### Q1 — Grade B
**Question:** How do I see all changes made by Peter Zhu last week?
**Answer:** You can use the Activity tab and filter by user...
**Reason:** Mentioned the Activity tab but didn't explain the date range picker; CIO would not know it's there.

### Q4 — Grade D
...

### Q7 — Grade ERR
...

## What I'd like you to do

Identify systemic issues across these failures (not one-off fixes). Propose changes
to the AI Help system prompt or controller logic. After my fix, I'll click Rerun
in the AI Eval tab — `parentRunId: {runId}` — and the Δ-grade column will tell us
whether the change helped.
```

## Error handling

| Failure mode | Behavior |
|---|---|
| Tester call fails (questions never generated) | Run fails. `status=FAILED`, error in `errors[]`, SSE emits `run-failed`, row in table shows red. |
| AI Help call fails on question N | That result → `grade: "ERR"`, `reason: "AI Help failed: <message>"`. Continue to question N+1. Counts as a failure in summary. |
| Evaluator call fails on question N | Same as above. `grade: "ERR"`, reason captures the error. Continue. |
| Model returns malformed JSON | Parse strict first; on fail retry once with stricter prompt (*"YOU MUST OUTPUT ONLY VALID JSON, NO PROSE"*); on second fail treat as call failure. |
| Server restarts mid-run | On `AiEvalService` startup, scan for any `RUNNING` runs → mark `FAILED` with reason `"server-restart-orphan"`. No resume attempt. |
| SSE connection drops | Worker thread keeps running on backend. Frontend `EventSource.onerror` falls back to polling `GET /api/ai-eval/runs/{runId}` every 3s until status ≠ `RUNNING`. |
| Tester == Evaluator model | Frontend disables the matching dropdown option. Backend returns HTTP 400 + `{error: "Tester and Evaluator must use different models"}`. |
| Invalid persona (empty `goal` etc.) | Backend HTTP 400 + helpful message. Frontend mirrors with inline form validation. |
| Cache file corrupted | Back up with `.broken-<timestamp>` suffix, start fresh with empty `runs[]`, log loudly (matches existing cache-file convention in this repo). |
| Cache file write fails (disk full) | In-memory state still good. Log error, surface in UI as transient toast: *"Could not persist run; refresh to lose results."* No retry loop. |

### Observability

Every Vortex call logs one greppable line in the existing `[AI ...]` style:

```
[AI EVAL] runId=<uuid> stage=tester|help|grade qIndex=<n-or-omitted> model=<slug> ms=<n> status=ok|err
```

`qIndex` is omitted for `stage=tester` (the question-generation call has no per-question index); included for `stage=help` and `stage=grade`.

## Testing strategy

**This feature is itself a test harness.** Extensive unit tests for a tester-of-testers is recursive and low-value. The feature self-validates the moment one run → fix → rerun cycle shows a Δ-grade improvement.

### Manual smoke checklist (run before shipping the JAR)

- [ ] Happy path: CIO persona, 5 questions, Tester=Claude, Evaluator=GPT-4o → 5 rows render with grades, summary shows avg + failure count.
- [ ] Same-model rejection (frontend): pick Claude on Tester → Claude option in Evaluator dropdown becomes disabled.
- [ ] Same-model rejection (backend): bypass via curl → backend returns HTTP 400.
- [ ] Mid-run Vortex failure: temporarily block `ai.vortex.sandisk.com` (e.g., add to `/etc/hosts` as `127.0.0.1`) during a run → that question shows `ERR`, run continues, finishes with partial results.
- [ ] Malformed JSON retry: tweak Tester prompt locally to encourage prose → verify retry-with-stricter-prompt path triggers (greppable in logs).
- [ ] Server restart mid-run: start a 20-question run, kill the JVM at question ~5, restart → run is marked `FAILED` with reason `server-restart-orphan`, no zombie state.
- [ ] Rerun: click Rerun on a row → new run appears with `parentRunId` set, Δ column populated.
- [ ] Export for Claude: click Export → `./debug-output/eval-latest.md` exists, contains failed rows only + system prompt + ask.
- [ ] SSE drop: kill the browser tab mid-run → backend run completes (verify in JSON), reopen tab → past-runs table shows the completed run with full data.

### One JUnit test worth writing

`src/test/java/com/sandisk/plm/tracker/service/AiEvalServiceTest.java`: JSON round-trip — serialize a sample `Run` object to JSON, deserialize, assert equality. Catches schema breakage. ~15 lines.

### Production validation plan

After deploying the JAR to prod:
1. Fire a single 5-question run as plmadmin against a CIO persona.
2. Verify `./cache/ai-eval-runs.json` is created.
3. Verify one log line per Vortex call in the prod log.
Done.

## Deployment plan

**Local first:**

1. Build JAR per CLAUDE.md instructions.
2. `cp` to `~/Documents/plm-toolkit\ 2/` (per the post-build hook in project CLAUDE.md).
3. Restart local instance.
4. Run the manual smoke checklist above.
5. Demo to team using local instance.

**Production rollout (later in the week):**

1. `cp` JAR to `/Volumes/uls-ep-aglipccb/plm-toolkit/`.
2. No new config — `portkey.base-url` is already wired from the prior session.
3. Restart prod service.
4. Run production validation plan above.

No prod-only configuration changes. Cache file `./cache/ai-eval-runs.json` is created on first run.

## Out of scope (explicit future work)

- **Async/background runs** — keep sync until 50-Q runs feel too slow.
- **Parallel question execution** — same trigger as above.
- **Multi-turn Tester↔AI Help conversations** — only if single-turn evals miss real bugs.
- **MCP server (`plm-eval`)** exposing runs to any Claude session — only if file-based export feels tedious.
- **Per-failure annotations** (mark-as-fixed, notes) — only if rerun-and-compare workflow proves insufficient.
- **A/B AI Help across models** — separate feature; this tab grades AI Help-as-it-exists.
- **Cross-run analytics dashboards** — JSON file is greppable; build only on real demand.

## Open questions for implementation

1. **Exact model names within each Vortex provider** — provider slugs are confirmed (`anthropic-eastus2`, `openai-eastus2`, `vertexai-global`); model names within each (`claude-sonnet-4-6`, `gpt-4o`, `gemini-2.5-pro`) need a one-shot smoke verification against Vortex's `/models` endpoint or a single chat call before the dropdown ships. If any model is not routable, fall back to a Claude-only dropdown and surface a TODO in the spec for a follow-up.
