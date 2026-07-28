# AI Eval Tab V2 — Human Overrides + Simulate Real Person

**Date:** 2026-05-04
**Author:** Vikas Jindal (PLM IT) + Claude
**Driver:** Vikas — V1 (commits `a0b5103`–`5746366`) ships abstract personas + AI-only grading. Two gaps surfaced from real use: (1) the AI evaluator sometimes grades wrong and the human needs to override, and (2) the abstract persona dropdowns don't capture real users' actual context.
**Audience:** Vikas (admin-only feature; same gate as V1)
**Builds on:** `docs/superpowers/specs/2026-05-03-ai-eval-tab-design.md`

## Problems

1. **Human can't disagree with the AI grade.** After V1 ships, the admin reviewing past runs has no way to say "the AI graded this D but I think it's a B" — the AI grade is the final word, which is wrong since the human is the source of truth on whether the chatbot answer was actually useful.

2. **Abstract personas miss real-user nuance.** The 5-bucket Role dropdown (CIO / Director / Peer engineer / New hire / Power user) is a coarse approximation. A real user has an actual title, department, account age, and tool-usage history that produce much more realistic test questions than "CIO from PLM IT with no experience".

## Goals

### Feature 1 — Override grades

- Let the admin change any question's grade and reason after the run completes.
- Preserve the original AI grade + reason as an audit trail (visible on hover).
- Recompute the run's avg grade + failure count to reflect the new effective values.
- Override UI in two places: live cards (after run completes) AND past-runs expand-row.

### Feature 2 — Simulate real person

- Let the admin pick a real AD user instead of (or in addition to) filling the abstract persona dropdowns.
- Auto-derive role / team / experience from AD attributes + ActivityLogger usage history.
- Pass the verbatim AD context to the Tester model so it generates questions in that person's actual voice and concern level — not just the bucketed-role approximation.
- The user picker has two modes: just-past-loggers (default, smaller list) and search-all-AD-users (toggle).

## Non-goals

- Multi-step / batched override workflow (e.g., "regrade all C's as B's"). One override at a time.
- Override audit history beyond the most recent change (the original AI value is preserved; intermediate human edits are not).
- Manual recompute trigger — recompute happens automatically on save.
- Persona override note as a required field (it's optional; admin can leave blank).
- Re-querying AD on every Rerun — the persona snapshot is taken at start-of-run and frozen.
- Modifying AI Help itself based on overrides (overrides are *grading* corrections, not chatbot training data).

## Design

### Schema additions

#### `AiEvalRun.Result` — V2 fields (all nullable for backward compat with V1 cache files)

```java
public String aiGrade;         // original AI grade — set on first override only
public String aiReason;        // original AI reason — set on first override only
public String overriddenBy;    // username (admin who overrode)
public String overriddenAt;    // ISO-8601 UTC timestamp
public String overrideNote;    // optional human note (why they disagreed)
```

**Override semantics:**
- On the **first** override, `aiGrade` and `aiReason` are populated from the current `grade`/`reason`. Subsequent overrides do NOT overwrite these (we always preserve what the AI originally said).
- The effective `grade`/`reason` are replaced with the human values.
- `overriddenBy` and `overriddenAt` are updated on every override.
- `finalizeSummary()` is called after every override — recomputes avg + failure count using the new effective `grade` field. Summary is consistent with what's displayed.

#### `AiEvalRun.Persona` — V2 fields (all nullable; only set when Simulate Person is used)

```java
public String simulatedUsername;       // sAMAccountName of the real user being simulated
public String simulatedDisplayName;    // full name from AD (for UI display)
public String realTitle;               // verbatim AD `title` attribute
public String realDepartment;          // verbatim AD `department` attribute
public String realAccountAge;          // human-readable, e.g. "5y 3mo"
public Integer realLoginCount90d;      // count from ActivityLogger
public Boolean realIsPlmAdmin;         // group membership
```

The existing `role`, `team`, `experience`, `goal` fields stay required — they get auto-filled from inference when Simulate Person is used, but the admin can still adjust before clicking Run. This preserves rerun semantics (same persona = strict config match).

### Backend additions

#### New endpoints

| Method | Path | Purpose |
|---|---|---|
| `PATCH` | `/api/ai-eval/runs/{runId}/results/{qIndex}` | Save a grade override. Body: `{grade, reason, note?}`. Validates grade ∈ {A,B,C,D,F}. Returns updated `Result` + `summary`. Admin-only. |
| `GET` | `/api/ai-eval/users` | List users who've logged into the toolkit (derived from `ActivityLogger.getActivities()`). Returns `[{username, displayName, lastSeen, loginCount90d}]`, sorted by recent activity. Default picker source. |
| `GET` | `/api/ai-eval/users/all` | List ALL users in the toolkit's AD access group via existing `LdapAuthService.searchUsers()`. Returns `[{username, displayName}]`. Used by the "Search wider" toggle. |
| `GET` | `/api/ai-eval/users/{username}/persona` | Inferred persona for that user. Returns `PersonaSuggestion` (see below). Admin-only. |

#### `LdapAuthService` change

Add `whenCreated` to the attribute query at line ~442:

```java
controls.setReturningAttributes(new String[]{
    "displayName", "mail", "department", "title", "manager", "co", "l", "whenCreated"
});
```

Add `String accountCreatedDate` to `UserInfo`. Parse the LDAP timestamp (format: `yyyyMMddHHmmss.0Z`) into ISO-8601.

#### New service `PersonaInferenceService`

```java
public class PersonaInferenceService {
    @Autowired LdapAuthService ldap;
    @Autowired ActivityLogger activityLogger;

    public PersonaSuggestion inferPersona(String username) {
        UserInfo ad = ldap.lookupUser(username);
        UsageStats usage = computeUsageStats(username);  // from ActivityLogger
        return new PersonaSuggestion(
            username, ad.displayName, ad.title, ad.department,
            humanAccountAge(ad.accountCreatedDate),       // "5y 3mo"
            usage.loginCount90d, usage.totalLoginCount,
            isPlmAdmin(ad),                                // group membership check
            mapRole(ad.title, isPlmAdmin(ad), usage),
            mapTeam(ad.department),
            mapExperience(usage.totalLoginCount, ad.accountCreatedDate)
        );
    }
}
```

**Mapping rules (deterministic, documented):**

- **Role** (case-insensitive title scan, first match wins):
  | Title contains | → Role |
  |---|---|
  | "CIO", "CTO", "CEO", "EVP", "SVP", "VP" | CIO |
  | "Director" | Director |
  | (PLM admin group AND total logins ≥ 20) | Power user |
  | (account age < 1 year AND total logins < 5) | New hire |
  | "Engineer", "Analyst", "Developer", "Specialist", "Architect", "Programmer" | Peer engineer |
  | (default) | Peer engineer |

- **Team** (department exact match against the 4 known dropdowns):
  | Department | → Team |
  |---|---|
  | "PLM IT" | PLM IT |
  | "Quality" or contains "Quality" | Quality |
  | "Engineering" or contains "Engineering" | Engineering |
  | "Operations" or contains "Operations" | Operations |
  | (anything else) | Other (text = department) |

- **Experience** (combines login count + account age):
  | Condition | → Experience |
  |---|---|
  | totalLoginCount = 0 | None |
  | totalLoginCount 1–10 OR account age < 6 months | Some |
  | totalLoginCount ≥ 11 AND account age ≥ 6 months | Daily user |

#### `AiEvalService` changes

- New method `applyOverride(runId, qIndex, grade, reason, note, byUsername)` — handles the audit-trail-preserve + summary-recalc logic.
- `executeRun` reads `config.persona.simulatedUsername` (and friends) and enriches the Tester system prompt when present:

```
You are simulating a real PLM Toolkit user.

DEMOGRAPHICS:
- Display name: Vikas Jindal
- Title: Sr. Manager, PLM Software
- Department: PLM IT
- Account age at SanDisk: 5y 3mo
- Tool usage: 47 logins in the last 90 days (active power user)
- PLM admin group membership: YES
- Closest abstract bucket: Director / PLM IT / Daily user

GOAL THIS SESSION: <persona.goal>

Generate exactly N distinct, realistic questions you would ask the AI Help chatbot
inside this app. Use the demographics above to shape your questions in the actual
voice and concern level of THIS specific person — not just the abstract role.
For example, a hands-on senior PLM admin asks operational/edge-case questions
about features they use daily; a 6-month-tenure analyst in Operations asks
discoverability questions about features they're new to.

OUTPUT REQUIREMENTS: Return ONLY a JSON array of N strings. No prose, no markdown
fences, no preamble. Example: ["q1", "q2", "q3"]
```

When `simulatedUsername` is null, the existing abstract-persona prompt is used unchanged.

### Frontend additions

#### Override UI (Section B and Section C)

- **Live cards** (Section B, after run completes): each card gets a small `✎ Edit grade` link in the bottom-right of the grade row.
- **Past-runs expand-row** (Section C): same `✎ Edit` link per question row in the expanded sub-table.
- Click → an inline popover (or modal) with:
  - Grade dropdown (A / B / C / D / F)
  - Reason textarea (pre-filled with current effective reason)
  - Optional "note" textarea (pre-filled with `overrideNote` if any) — placeholder: *"Optional: why are you changing this?"*
  - Save / Cancel buttons
- After save:
  - The card/row updates in place: grade pill changes to the new value, badge appears next to it: `👤 You · D` (purple `#7C3AED` accent).
  - Hover the badge → tooltip shows `Original AI grade: B — <ai reason>` plus `Changed by <username> on <date>` and the optional note.
  - Section B summary strip recalculates (avg grade letter, failure count).
  - Section C avg-grade pill + Δ column also recalculate (without page refresh).

#### Simulate Person UI (Section A)

Below the existing Tester block, before the Evaluator column, a new collapsible section:

```html
<label>
  <input type="checkbox" id="aieSimulatePerson"> Simulate real person
</label>

<div id="aieSimulatePersonBody" style="display:none">
  <div class="aie-picker-row">
    <input id="aieUserSearch" placeholder="Type to search…" list="aieUserList">
    <datalist id="aieUserList"></datalist>
    <label><input type="checkbox" id="aieSearchAllAd"> Search all AD users (vs just past loggers)</label>
  </div>

  <div id="aieInferenceCard" style="display:none">
    <!-- Populated by GET /api/ai-eval/users/{username}/persona -->
  </div>
</div>
```

When the checkbox is unchecked: behaves like V1 (abstract persona).
When checked + user picked: GET `/users/{username}/persona`, render the inference card, auto-fill the Role/Team/Experience dropdowns and pre-fill Goal placeholder.
The admin can still tweak the dropdowns before clicking Run (their tweaks override the inference for that run).

#### Past-runs persona column

When `simulatedDisplayName` is present, render: `🧑 Vikas Jindal (Director · PLM IT)`
Otherwise render the existing abstract: `Director · PLM IT · Daily user`
Click "Rerun" on a simulated-person row → re-fetches inference for that username (in case AD or activity changed) and re-fires.

## Cross-cutting

- **Backward compatibility:** V1 cache files load unchanged — all V2 fields default to null. Old runs in the table show empty override badges and no `🧑` prefix. No migration needed.
- **Concurrent overrides:** override endpoint is `synchronized` on the `AiEvalService` instance, same as V1 writes. Single-user app; no realistic concurrent-edit risk.
- **Local-vs-prod activity log:** The "past loggers" picker reads from the local `ActivityLogger`, which is backed by `./data/activity-log.jsonl`. To get a realistic list locally, copy `activity-log.jsonl` from prod (`/Volumes/uls-ep-aglipccb/plm-toolkit/data/activity-log.jsonl`) to the local `~/Documents/plm-toolkit 2/data/`.
- **AD reachability from local:** "Search all AD users" requires LDAP to be reachable from the user's machine. If LDAP times out, fall back to the past-loggers list with a warning toast: *"AD search unavailable — showing past loggers only."*

## Error handling

| Failure | Behavior |
|---|---|
| Override save: invalid grade (not A/B/C/D/F) | HTTP 400 + error message; popover shows inline error, doesn't close |
| Override save: run/qIndex doesn't exist | HTTP 404; toast "Run or question not found" |
| Persona inference: LDAP lookup fails for that username | Return cached/partial: `{title:null, department:null, accountAge:"unknown", ...}` plus default abstract role; UI shows warning *"AD lookup failed — using activity-only inference"* |
| Persona inference: ActivityLogger has no entries for that username | Treat as 0 logins → maps to "None" experience |
| User picker: LDAP "search all" times out | Falls back to past-loggers; warning toast as above |

## Testing strategy

Same approach as V1 — manual smoke checklist, no full test mocking infra.

### Manual smoke checklist (run before shipping the JAR)

- [ ] Override happy path: pick a graded run, expand, click Edit on a row, change C → A, save → row updates with `👤 You · A` badge, summary recalculates, hover badge shows original AI grade.
- [ ] Override preserves original AI grade after second edit: edit same row again C → B → save → tooltip still shows the *original* AI grade (not C), confirming `aiGrade` is set once.
- [ ] Override visible in live cards: complete a fresh run, click Edit on a card, save → card updates inline.
- [ ] Override survives page refresh: refresh browser, re-open same run → override still there with badge.
- [ ] Simulate Person happy path: check the Simulate box, type "vikas" in picker → match appears, click → inference card renders, Role/Team/Experience dropdowns auto-fill, click Run → run uses enriched system prompt (verify by grepping log for `Real-world context`).
- [ ] Simulate Person — manual override of inferred role: pick a user → inference auto-fills "Director" → manually change to "Peer engineer" → click Run → run uses Peer engineer (admin override wins).
- [ ] Search all AD: toggle the "Search all AD users" checkbox → picker dropdown re-populates from `/users/all`; type "krat" → matches show users not in the local activity log.
- [ ] Past-runs: simulated runs show with `🧑 Vikas Jindal (...)` prefix; non-simulated show abstract persona.
- [ ] Rerun a simulated person: click Rerun → new run fires with the same simulatedUsername; persona is re-inferred (so if AD changed, picks up the new title).
- [ ] Backward compat: an existing V1 run (no override fields, no simulatedUsername) renders fine — empty badges, no `🧑` prefix.

## Deployment plan

Local first (same flow as V1). Prod rollout when convenient. No new config keys; reuses `portkey.base-url` from V1, reuses LDAP config that already exists.

## Out of scope (explicit future work)

- Bulk override ("apply this grade change to all rows where AI graded D but my override was B" — pattern learning)
- Per-user persona snapshot history (track how the same person's title/team changes over time)
- Persona override comparison (run the same person at two points in time to see if their question patterns shifted as they gained experience)
- Calibration UI: show the AI's grade-distribution drift after N overrides (e.g., "the evaluator tends to grade 0.4 letters lower than humans for CIO persona — apply correction?")
