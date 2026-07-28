# AI Eval Tab — Overview for SanDisk Email Brief

> **Purpose of this file:** hand to Claude chat as input so it can produce a SanDisk-templated leadership-grade HTML email announcing the new tab.

---

## What it is

A new **AI Eval** tab in the PLM Toolkit (admin-only). It is a chatbot quiz harness for the AI Help drawer: an LLM **Tester** generates persona-realistic questions, those questions are fired at AI Help, and a second LLM **Evaluator** grades each answer A–F with a one-sentence reason. Past runs persist; you can compare grades across changes to AI Help, swap evaluators on the same Q/A, or override grades with your own judgement.

In one line: **a way to know, with numbers, whether a change to AI Help made it better — instead of guessing.**

## Why it matters

Until now, "is the AI Help chatbot any good?" was vibes-based. After every prompt tweak, knowledge-base edit, or model swap, you'd ask the same three questions you always ask, get vaguely-OK answers, and ship. There was no signal on the *systemic* quality of the assistant.

AI Eval gives you:
- **A repeatable score** per AI Help configuration (avg grade A–F, % failures).
- **A regression signal** when you change something (Δ-grade vs. previous run with the same persona).
- **A failure dossier** you can hand to Claude (or another engineer) to diagnose and fix.
- **A way to A/B different evaluators** on identical Q/A — so "this evaluator is harsh" vs "this answer is bad" becomes testable.

## Who it's for

PLM admins (`pdl-plm-admin` group). The tab is hidden for non-admins.

Primary user is the team that owns AI Help quality — today that's me + whoever inherits the prompt and knowledge-base maintenance.

## How a run works (V1 baseline)

1. **Configure persona.** Pick four fields:
   - **Role** (CIO / Director / Power user / Peer engineer / New hire)
   - **Team** (Engineering / Operations / IT / Compliance / etc.)
   - **Experience** (None / Some / Daily user)
   - **Goal** (free-text, e.g. "explore the BOM Explorer for the first time")

2. **Pick a Tester model and an Evaluator model** from three Vortex providers:
   - Claude Sonnet 4.6 (`@anthropic-eastus2`)
   - GPT-4o (`@openai-eastus2`)
   - Gemini 2.5 Pro (`@vertexai-global`)

   The Tester and Evaluator must be different — enforced in the UI and the backend.

3. **Pick a question count** (5 / 10 / 20 / 50) and click **Run eval.**

4. **Watch it stream live.** The page renders one card per question:
   - Question text appears first.
   - "Asking AI Help…" spinner.
   - Answer streams in.
   - "Grading…" spinner.
   - Grade pill (A green → F red) and one-sentence reason.

5. **Past Runs table** appears below with avg grade, fail count, and a Δ vs. the previous run with the same persona. Each row has Rerun, Regrade, Export, and an expand chevron that shows every Q/A/grade with full reasoning.

Runs persist to `./cache/ai-eval-runs.json` so they survive restarts.

## What ships in this release

The tab launched in three iterations over two days. All of them are live now.

### V1 — Base evaluation harness
- Persona configuration, two-model run, A–F grading, live SSE streaming, persistent past runs, Δ-grade column.
- **Export button** writes a focused failure-only markdown brief — system prompt snapshot + every failed Q/A — that you can hand to Claude to identify systemic issues.

### V2 — Override grades + simulate real users
- **Override any grade.** AI evaluators sometimes get it wrong. Click *✎ edit grade* on any graded card; popover lets you change A–F + reason + an optional explanatory note. The original AI grade is preserved (audit trail) and visible on hover of a 👤 You badge. Run summary recalculates immediately.
- **Simulate real person.** Below the Tester block, check *Simulate real person* and pick any PLM Toolkit user. The form auto-fills from their AD profile (title, department, account age) plus their actual usage history (login count, last seen, admin flag). The Tester model gets these verbatim demographics so questions match that person's actual voice — a hands-on senior PLM admin asks operational/edge-case questions; a 6-month-tenure analyst asks discoverability questions. Picker has two modes: just-past-loggers (default, smaller list) and search-all-AD-users (toggle).

### V3 — Regrade with a different evaluator
- **🔄 Regrade button** on every past-runs row. Click it, pick a different evaluator (the original evaluator and the Tester are greyed out), and the system reuses the SAME questions and AI Help answers from the original run — only the new Evaluator runs. Lets you compare evaluator calibration on identical Q/A.
- Regrade rows display a clear "🔄 Regrade · date" label, an "of *parent date* — was graded by *X*" second line, and a banner inside the expand-row showing original avg → new avg with Δ. The expand-row also shows side-by-side "Was" grades per question with a purple border on disagreements.
- Regrade is faster than a fresh run (~35–40% less wall-clock) because question generation and AI Help calls are skipped.

### Recent fixes (May 4)
- **Export now downloads to your browser** (`Content-Disposition: attachment`) instead of writing `eval-latest.md` to the server's filesystem. No more SSH or volume-mounting just to read the brief.
- **Persona inference now sees historical login counts.** The activity-log lookup was short-circuiting on the in-memory cache (last 7 days only after restart) and never falling back to the JSONL file. Users like Jimmy Sessumes were showing 0 logins despite having logged in repeatedly. Fixed.
- **Tab clicks now log as `AI Eval`** instead of the raw key `aieval` in the activity log.
- **Cleaner evaluator-failure message.** When the evaluator returned malformed JSON, the message used to splice in raw model output (sometimes containing literal `\u0027` escape sequences and code-fence markers). Now it shows a clean message and logs the raw text server-side.
- **Helper text under "Simulate real person" renders properly.** The `\u0027` JS escape was mistakenly placed inside HTML — browsers render those literally. Replaced with `&#39;`.

## Smoke test result that motivated V3

Took a 5-question run originally graded by GPT-4o, regraded with Gemini-2.5-pro:

| | GPT-4o (original) | Gemini (regrade) |
|---|---|---|
| Avg grade | C (2.0) | A (4.0) |
| Failures | 3 of 5 | 0 of 5 |

Same questions, same AI Help answers — completely different verdict. **The difference is the evaluator's calibration, not the AI Help content.** That's the kind of insight V3 unlocks: when you see a regression in scores, you can now ask "is AI Help worse, or is the evaluator just stricter today?" and answer it in 60 seconds.

## What it costs

- **One run (5 Q's):** ~$0.05–0.10 in Vortex tokens, ~90s wall-clock.
- **One regrade (5 Q's):** ~$0.02–0.04, ~55s (no Tester or AI Help calls).
- **Storage:** ~5 KB per run in `cache/ai-eval-runs.json`.

Negligible at this scale; even 100 runs/day is under $10/day.

## What's intentionally not in scope

- **No automatic scheduling.** You hit Run when you want a measurement; we don't poll continuously.
- **No alerting.** No "ping me if avg grade drops below B" — the past-runs table makes regressions visible at a glance and that's enough for now.
- **No auto-fix loop.** The Export button hands you a failure dossier; the human (or Claude in chat) decides what to change in the AI Help prompt.

## Where the work lives

- **Spec:** `docs/superpowers/specs/2026-05-03-ai-eval-tab-design.md` (V1), `2026-05-04-ai-eval-v2-overrides-and-simulate-person.md` (V2)
- **Plan:** `docs/superpowers/plans/2026-05-03-ai-eval-tab.md` (16 tasks)
- **Backend:** `AiEvalService.java`, `AiEvalController.java`, `PersonaInferenceService.java`, `PortkeyClient.java` (refactored to consolidate 7 copies of Vortex-call code)
- **Frontend:** `static/ai-eval.js`, `static/index.html` (panel markup), `static/whats-new.js` (release notes)
- **Persistence:** `./cache/ai-eval-runs.json` (Jackson POJO serialization)

## Suggested email framing

For the leadership announcement:

- **Subject:** `PLM Toolkit: 🆕 AI Eval tab — measure, regress-test, and regrade the AI Help chatbot`
- **Eyebrow:** `New tab · admin-only`
- **Hero title (serif):** "We can finally tell when AI Help got better."
- **Sub:** "AI Eval gives admins a numerical, repeatable signal on AI Help quality — across personas, across changes, across evaluator models."
- **3 callouts to highlight:** (1) score every change; (2) simulate real users; (3) regrade with a different evaluator on identical Q/A.
- **CTA:** "Open the AI Eval tab on PLM Toolkit and click Run. Default persona + 5 questions = first score in ~90 seconds."

Per CLAUDE.md email design guidelines: IBM Plex fonts, SanDisk palette (Primary `#4a6fa5`, Header BG `#2c3e50`, Body BG `#FAFAF7`), nav header with breadcrumb + status badge, hero block, KPI/callout tiles where useful, sandisk pill in footer, dark-mode CSS classes, no "Dear User" greeting, no sign-off.
