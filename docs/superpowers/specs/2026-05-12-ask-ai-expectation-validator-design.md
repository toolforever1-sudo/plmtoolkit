# Ask AI — Expectation Validator

**Status:** Draft
**Owner:** Vikas Jindal
**Date:** 2026-05-12

## Problem

When a user grades an Ask AI answer C/D/F, the "What did you expect?" modal accepts any non-empty string and files it as a Bug Report. There is no quality gate — gibberish like `gibebjvpdfslvnksfdbkfsb` or content-free strings like `this is wrong` reach the PLM admin team's feedback queue and waste triage time.

## Goal

Reject obviously-useless expectation text at Save time and prompt the user to write something actionable, so only triage-worthy items become Bug Reports.

Non-goals:
- Validating the **question** itself (separate problem).
- Grading the *quality* of valid expectations (out of scope — even a terse-but-real expectation is fine).
- Adding telemetry on rejection rates.

## Solution Overview

Add a small AI-backed validator that fires when the user clicks **Save expectation**. If the validator says the expectation is unusable, keep the modal open with an inline error so the user can fix it. Cancel always bypasses (no bug is filed on Cancel, so there's nothing to gate).

## Components

### Backend: `POST /api/ai-eval/ask/validate-expectation`

New endpoint on `AiEvalController` (sibling of `/ask/question` and `/ask/submit-session`).

**Auth:** any logged-in user (same gate as `/ask/question`).

**Request body:**
```json
{
  "question": "how many skus were created last week?",
  "answer": "0 items match your query. Filter: NEW_PART_CLASS contains 'SKU' AND CREATE_DATE >= '2026-05-05' …",
  "expectation": "gibebjvpdfslvnksfdbkfsb"
}
```

`answer` is the plain-text (HTML-stripped) AI answer the user is rejecting. Caller may pass `null`/empty if unavailable; validator handles that case.

**Response body:**
```json
{ "valid": false, "reason": "Please describe what you expected the AI to say." }
```

When `valid: true`, `reason` is `null`. Frontend uses `reason` verbatim as the inline error message, so the backend owns the UX copy.

**Behavior:**
1. If `expectation` is null/empty after trim → `{ valid: false, reason: "Please enter what you expected." }` (defense in depth — the frontend short-circuits and won't normally call validate on empty, but the endpoint is callable directly).
2. Backend truncates the `answer` to ~1000 chars and the `expectation` to 2000 chars before building the prompt. Bug Reports already cap expectations at 2000; the answer cap keeps the prompt small and cheap.
3. Build a short prompt for Haiku that includes the question, the truncated answer, and the expectation. System message asks for strict JSON: `{"valid": true|false, "reason": "<short user-facing message or null>"}`. Reject criteria spelled out in the prompt:
   - Gibberish / keyboard mashing / non-words
   - Content-free ("this is wrong", "bad", "no", "fix it")
   - Insulting or off-topic with no actionable signal
4. Call `PortkeyClient.chat(haikuModel, system, user, 150)` — same client/model pattern as `UploadColumnDetector` (`@anthropic-eastus2/claude-haiku-4-5-20251001`).
5. Parse JSON best-effort. On any parse failure or LLM error → **fail open**: return `{ valid: true, reason: null }` and log a warning. Never trap the user behind a broken validator.
6. Activity log entry (`AI_EVAL_EXPECTATION_VALIDATE`) records: username, `valid` outcome, expectation length, and the first 80 chars of the reason (if any). No full expectation text.

**File:** `src/main/java/com/sandisk/plm/tracker/controller/AiEvalController.java` (add `@PostMapping("/ask/validate-expectation")` method) and optionally a thin `ExpectationValidatorService` if the prompt-building grows past ~30 lines.

### Frontend: `ask-ai.js`

Modify `window.askExpectationClose(save)` in `src/main/resources/static/ask-ai.js`:

1. When `save === true` and the trimmed expectation is non-empty:
   - Disable the "Save expectation" button; change label to `Checking…`.
   - POST to `/api/ai-eval/ask/validate-expectation` with `{ question, answer: stripHtml(answer), expectation }`.
   - On `valid: true` → save & close modal as today.
   - On `valid: false` → re-enable button, set label back to "Save expectation", show `reason` in a red inline error div directly below the textarea. Modal stays open. User can edit and click Save again.
   - On HTTP/network error → fail open: save & close as if validation passed. Log to console.
2. When `save === false` (Cancel) → close immediately, no validation call.
3. Reopening the modal (a re-grade) clears any previous inline error.

**HTML change:** add an empty error placeholder `<div id="askExpectationError" style="margin-top:8px; color:#B8342B; font-size:12px; display:none;"></div>` between the textarea and the button row in `src/main/resources/static/index.html`. The JS toggles `display`/`textContent`.

### What's New

Add a `fix` entry to the top of `WHATS_NEW_RELEASES` in `src/main/resources/static/whats-new.js` before building:
> Ask AI Bug Reports — gibberish or content-free "what did you expect?" entries are now rejected with a prompt to rewrite, so the triage queue only sees actionable feedback.

## Data Flow

```
User clicks Save expectation
   │
   ▼
ask-ai.js → POST /api/ai-eval/ask/validate-expectation
   │              { question, answer, expectation }
   ▼
AiEvalController.validateExpectation
   │
   ▼
PortkeyClient.chat(@anthropic-eastus2/claude-haiku-4-5-20251001, system, user, 150)
   │
   ▼  JSON: {"valid": false, "reason": "Please describe what you expected the AI to say."}
   │
   ▼
ask-ai.js renders inline error, modal stays open
```

## Error Handling

| Failure | Behavior |
|---|---|
| LLM call throws / times out | Backend returns `{ valid: true }`. Warning in server log. |
| LLM returns non-JSON | Backend returns `{ valid: true }`. Warning in server log. |
| Frontend network error | Frontend treats as `valid: true`, saves and closes. Console warning. |
| User not logged in | 401 (matches `/ask/question`). Frontend treats as fail-open. |
| Empty expectation after trim | Backend returns `{ valid: false, reason: "Please enter what you expected." }`. |

Rationale for fail-open: a broken validator must not block users from filing legitimate bugs. A few junk reports getting through during an outage is a smaller cost than blocking real ones.

## Testing

Manual test plan (no automated tests — this is a small UX gate):

1. Grade an answer F, type `gibebjvpdfslvnksfdbkfsb`, click Save → expect inline error, modal stays open.
2. Grade F, type `this is wrong`, click Save → expect inline error.
3. Grade F, type a real description (e.g. `should have returned the count of SKUs created since 2026-05-05`), click Save → expect modal closes, expectation badge shown.
4. Grade F, click Cancel immediately → modal closes, no API call (verify in Network tab).
5. Grade F, type gibberish, click Save, then edit to a real description, click Save → expect success.
6. Re-grade same question (F again) after a prior reject → inline error should be cleared on reopen.
7. With backend stopped: grade F, type junk, click Save → fail-open, modal closes, junk saved. Confirms the validator doesn't trap the user.

## Out of Scope

- Validator metrics / dashboard
- A "save anyway" override button (Cancel is the escape hatch)
- Validating C/D grades differently from F
- Rate limiting (admin-grade traffic, low volume)
- Validating the question text or the grade choice
