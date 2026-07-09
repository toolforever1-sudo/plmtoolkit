# Create Restart ECN from the IT Enhancements tab — Design

**Date:** 2026-07-01
**Status:** Approved design, ready for implementation plan
**Repos touched:** `plm-field-tracker` (toolkit), `plm-agile-service` (SDK writer)

## 1. Purpose

Today, a "Restart ECN" (a deployment ECN that groups the IT-enhancement ECNs going
live in a given deployment) is created by hand in the Agile PLM web client — roughly
15 clicks across the Create dialog, Cover Page, ECN Details, and Relationships tab.
The manual flow was captured in a screen recording (2026-07-01). This feature adds a
one-click **Create Restart ECN** action to the toolkit's **IT Enhancements** tab that
reproduces that flow via the SDK.

The defining characteristic of a Restart ECN: it is an **ECN whose Request
Classification = `Restart Agile Service`**, carrying mostly-fixed boilerplate field
values, a date-based Proposal/title, an IT Owner, and a set of **Relationships** to the
enhancement ECNs being deployed.

## 2. Manual process being automated (from the recording)

1. **Create New → Change Requests**, Type = `ECN`. Number auto-generates
   (`ECN-137667` → `ECN-137667-PROJ`).
2. Create-dialog fields: Priority `Standard`, Category `NA`, Disposition `N/A`,
   Product Line/Program Name `N/A`, **Request Classification `Restart Agile Service`**.
3. **Save** → ECN created in **Pending** on **ECR - Workflow**; *Reason For Proposal*
   auto-populates.
4. **Cover Page**: Proposal = `Deployment ECN MM/DD/YY`, Analyst = `Administrator`,
   Product Line(s) = `N/A`.
5. **ECN Details**: Priority Level = `3-Low`, Problem Statement = deployment note.
6. **Relationships tab**: Add one or more enhancement ECNs as relationship rows
   (recording added `ECN-134394-PROJ`).
7. (Manual submit through the workflow — not shown in the recording.)

## 3. Requirements (decided during brainstorming)

| # | Decision |
|---|---|
| R1 | **Create + auto-submit**: create the ECN, set fields, add relationships, then advance **one** status step (standard Submit from Pending). Auto-submit is **non-fatal**. |
| R2 | **Deploy date is user-entered.** The toolkit builds two text fields from it plus the related ECNs: **Proposal** = header `Deployment MM/DD/YY:` followed by one bullet per related ECN, each an **AI-condensed** one-liner of that ECN's description; **Problem Statement** = `Deploying ECNs:` followed by the related ECN numbers. |
| R3 | Created under the **logged-in user's Agile credentials** (Meeting-Mode-style sign-in). Requestor = that user (Agile sets it from the acting session). |
| R4 | All non-date cover fields are **hardcoded, config-driven constants** (see §5). |
| R5 | **Relationships**: user must select **≥1** eligible ECN. Eligible = IT-enhancement ECN tracked by the toolkit whose **IT Status (`page_three.TEXT36`) = `UAT complete, CAB Prep`** (exact, case-insensitive, config constant). |
| R6 | **Partial relationship failure is fail-soft**: create/submit still proceeds with the relationships that attached; failures are reported. |
| R7 | **IT Owner** field on the Restart ECN is user-selected and **required**. Eligible = Agile *Assigned IT Owner* (LIST53) available values **minus** the IT owner of any selected related ECN. |

## 4. Architecture

Mirrors the existing **Send New DRR** two-repo pattern
(`AgileWriteBackService.createDrr` ↔ `AgileWriteBackClient.createDrr`).

### 4.1 plm-agile-service (SDK writer)

New service method `AgileWriteBackService.createRestartEcn(req, log)` and endpoint
`POST /api/change/create-restart-ecn`. Runs entirely in **one per-user SDK session**
(credentials `asUsername`/`asPassword` in the request — same mechanism as the existing
`update-cell-as-user`).

Sequence (each step logged; per-cell/per-relationship fail-soft where noted):

1. Resolve `ChangeConstants.CLASS_ECO` → the ECN subclass (config `agile.restartEcn.subclass=ECN`).
2. `session.createObject(ecnCls, ecnCls.getAutoNumberSources()[0])` → capture the number.
3. `setWorkflow` to `ECR - Workflow` (config `agile.restartEcn.workflow`).
4. Set cover-page cells (config-driven cell IDs, fail-soft per cell) — see §5.
5. Set **IT Owner** (`Page Three.Assigned IT Owner`, LIST53) to the chosen user via the
   DRR user-list `setSelection` pattern (single user).
6. **Add Relationships**: for each selected ECN number, resolve the change and add a row
   to the Restart ECN's Relationships table. `disableAllWarnings()` wrapped (see
   `feedback_no_disable_warnings`); benign "already related"/warnings treated non-fatal;
   per-ECN fail-soft.
7. **Auto-submit** one status step (standard Submit). Non-fatal.

Relationship SDK note: an `IChange` exposes its Relationships via the Relationships
table / relationship container; the exact constant (`ChangeConstants.TABLE_RELATIONSHIPS`
vs the relationship API) is confirmed during implementation against the decompiled
`AgileAPI936.jar`. Referent for each row is the related `IChange`.

**Request DTO** (`WriteBackModels.CreateRestartEcnRequest`):
```
String asUsername;          // acting Agile user
String asPassword;          // never logged
String proposalText;        // toolkit-built: "Deployment MM/DD/YY:" + AI bullets
String problemStatement;    // toolkit-built: "Deploying ECNs:" + related ECN numbers
String itOwnerLogin;        // chosen IT owner login
List<String> relatedEcns;   // eligible ECN numbers to relate
```
The service sets Proposal/Problem Statement cells verbatim from `proposalText` /
`problemStatement`; all title/summary construction (incl. the AI condensation) happens
toolkit-side, where the LLM integration and cached ECN descriptions live.

**Response DTO** (`CreateRestartEcnResponse`):
```
boolean ok;
String  ecnNumber;              // auto-generated
boolean submitted;              // did the auto-submit take
List<String> stepsOk;
String  stepFailedAt;
String  errorReason;
List<String> relatedOk;                       // ECN numbers attached
List<Map<String,String>> relatedFailed;       // [{ecn, reason}]
```

### 4.2 plm-field-tracker (toolkit)

- **`AgileWriteBackClient.createRestartEcn(asUsername, asPassword, deployDateIso, itOwnerLogin, relatedEcns, corrId)`**
  → `POST /api/change/create-restart-ecn` on :8081 (base URL `agile.service.url`).
- **`ItEnhancementsController`**:
  - `GET /api/it-enhancements/restart-ecn-candidates` → returns eligible ECNs
    (`{ecn, proposal, itOwner, itOwnerLoginId, workflowStatus}`) by filtering the rows
    `ItEnhancementsService` already caches to IT Status = `UAT complete, CAB Prep`.
    Also returns the eligible IT-owner roster (Agile Assigned-IT-Owner list values).
  - `POST /api/it-enhancements/create-restart-ecn` → reuses the cached-Agile-password /
    `needsAgileSignin` flow (same as `save-cell`); **server-side re-validation** (§6);
    **builds the Proposal + Problem Statement text** (§5.1); forwards to the client; logs
    `IT_ENH_CREATE_RESTART_ECN` via `ActivityLogger`.
- Authorization gate: the existing `it-enhancements` tab allowlist.

### 4.3 Frontend (`it-enhancements.js`)

- **Create Restart ECN** button in the tab toolbar (next to Refresh).
- Click → modal (existing `ui-modal.js` / inline-popover conventions) with:
  - **Deploy date** picker (default today) → live preview of the Proposal **header**
    (`Deployment MM/DD/YY:`). The AI-condensed bullets are generated server-side at create
    time and shown back in the success toast/log, not previewed live.
  - **Eligible-ECN checklist** — searchable multi-select (ECN + short proposal + IT
    owner), "Select all", live count. Sourced from `/restart-ecn-candidates`.
  - **IT Owner** dropdown (required) — Agile Assigned-IT-Owner values minus the IT
    owner(s) of the currently-checked ECNs (exclude by `itOwnerLoginId`, fallback
    normalized `Last, First`). Recomputes live as the checklist changes; clears if the
    selected owner becomes excluded.
  - **Create** disabled until: date valid AND ≥1 ECN checked AND an IT owner selected.
    If owner-exclusion empties the roster, Create stays disabled with a message.
  - If no cached Agile password → existing Agile sign-in modal first, then proceed.
- On success → toast e.g. *"Created ECN-137667-PROJ, related 3 ECNs, submitted"*; warn
  toast if `submitted:false` or any `relatedFailed`.
- No grid auto-refresh — a Restart ECN's classification isn't `Agile IT Enhancement`, so
  it won't appear in this grid.

## 5. Field mapping (config constants + inputs)

All values except the three inputs live in `application.properties` as
`agile.restartEcn.*`, retunable on QA without a rebuild.

| Cover field | Value | Type | Source |
|---|---|---|---|
| Request Classification | `Restart Agile Service` | multi-list | constant |
| Category | `NA` | list | constant |
| Disposition | `N/A` | list | constant |
| Product Line(s) | `N/A` | multi-list | constant |
| Priority | `Standard` | list | constant |
| Priority Level | `3-Low` | list | constant |
| Analyst | `Administrator` (login configurable) | user-list | constant |
| Proposal | `Deployment MM/DD/YY:` + AI bullets | text | **deploy date + related ECNs** (§5.1) |
| Problem Statement | `Deploying ECNs:` + ECN numbers | text | **deploy date + related ECNs** (§5.1) |
| Assigned IT Owner | chosen user | user-list (LIST53) | **input** |
| Relationships | selected ECNs | relationship rows | **input** |
| Workflow | `ECR - Workflow` | — | constant |

### 5.1 Toolkit-built Proposal & Problem Statement

Built server-side (toolkit) at create time from the deploy date + the (re-validated)
related ECNs, using the descriptions already in the cached `ItEnhancementsService` rows.

- **Proposal**
  ```
  Deployment 07/04/26:
  • ECN-134394-PROJ: <AI-condensed one-liner of that ECN's description>
  • ECN-XXXXXX-PROJ: <AI-condensed one-liner>
  ```
  Header uses the user-picked date (`Deployment MM/DD/YY:`, config-driven prefix). One
  bullet per related ECN. The condensation reuses the toolkit's existing LLM integration
  (as in `DebugAssistantService` / `GradeEvaluatorService`): the source text is the row's
  proposal/description; the AI returns a short single-line summary. **Fail-soft**: if the
  AI call fails or times out for an ECN, fall back to that ECN's raw (truncated)
  description so the Proposal is always built.

- **Problem Statement**
  ```
  Deploying ECNs:
  ECN-134394-PROJ, ECN-XXXXXX-PROJ, ...
  ```
  Header config-driven; body is the related ECN numbers (no AI).

Both finished strings are passed to agile-service and set verbatim; both are captured in
the `ActivityLogger` entry for traceability.

## 6. Server-side re-validation (defense against a stale browser)

Before forwarding to the SDK, the toolkit controller re-checks against the freshly
cached `ItEnhancementsService` rows:

1. Every `relatedEcns` entry is still at IT Status `UAT complete, CAB Prep`. Ineligible
   entries are **dropped and reported**; if that leaves zero, return 422.
2. `itOwnerLogin` is in the Agile Assigned-IT-Owner list AND is **not** the IT owner of
   any (post-filter) related ECN. Otherwise 422 with a clear reason.

## 7. Error handling

- Per-cell and per-relationship failures are fail-soft; the response names the failing
  step/cell/ECN (mirrors DRR's `[AGILE-WRITE]` logging).
- Auto-submit failure is non-fatal — the ECN remains created (Pending) and the toast
  tells the user to submit it in Agile.
- Invalid Agile credentials (errorCode 60062) clears the cached password and re-prompts,
  same as `save-cell`.

## 8. Testing

- **agile-service**: unit-test `createRestartEcn` request/response mapping and the
  step/ordering logic with a mocked session (SDK write itself can't run on the Mac — see
  `feedback_agile_sdk_remote_testing`; Vikas deploys both jars to QA and tests the live
  write).
- **toolkit**: unit-test `/restart-ecn-candidates` filtering, the server-side
  re-validation (ineligible ECN dropped, ineligible/owner-conflict owner rejected),
  `AgileWriteBackClient` payload shape, and the Proposal/Problem Statement builder —
  including the AI fail-soft fallback (bad/timeout AI call → raw truncated description).
- **frontend**: verify the dialog gating (date + ≥1 ECN + owner), the dynamic
  owner-exclusion, and empty-roster disable.

## 9. Out of scope / deferred

- Multi-step workflow routing beyond the single Submit.
- Editing the boilerplate constants from the UI (config-only for now).
- Auto-refreshing the grid to show the new Restart ECN.
- Idempotency / duplicate-Restart-ECN detection (a new number is minted each click).

## 10. Config keys (new)

```
agile.restartEcn.subclass=ECN
agile.restartEcn.workflow=ECR - Workflow
agile.restartEcn.requestClassification=Restart Agile Service
agile.restartEcn.category=NA
agile.restartEcn.disposition=N/A
agile.restartEcn.productLine=N/A
agile.restartEcn.priority=Standard
agile.restartEcn.priorityLevel=3-Low
agile.restartEcn.analystLogin=administrator
agile.restartEcn.eligibleItStatus=UAT complete, CAB Prep
# cover-page cell IDs (mirror agile.cell.* used by createDrr) resolved during implementation

# Toolkit-side (plm-field-tracker) — Proposal/Problem Statement construction
restartEcn.proposalHeader=Deployment {date}:        # {date} = MM/DD/YY
restartEcn.problemStatementHeader=Deploying ECNs:
```
