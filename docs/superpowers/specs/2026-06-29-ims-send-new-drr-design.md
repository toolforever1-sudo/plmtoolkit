# Send New DRR — Design

**Date:** 2026-06-29
**Goal:** Turn the IMS Dashboard "Need DRR" row action (currently muted "needs DRR" text) into a **Send New DRR** button that creates a DRR in Agile for the document and sends the review request to the document's owner(s) — moving the row from **Need DRR → New DRR / Pending Response**.

**Reference classes (mimic):** `~/plm_java_extract_20260622_110000/agilerestcall/DocumentClass/src/com/sandisk/docclass/AuditDocuments.java` (`creatingSpecificChange()` + `getDocs()`), `DocConstants.java`. Existing write-back template to mirror: the DCO path (`plm-agile-service` `AgileWriteBackController`/`AgileWriteBackService` `createDco`; toolkit `AgileWriteBackClient.createDco` + `ImsReviewService.runAgileWriteBack`).

## Decisions (confirmed with Vikas 2026-06-29)
- **Create + send**: create the DRR (Pending) AND immediately send the review to the owner via the existing send flow.
- **Owner**: the document's current Document Owner(s) — set as the DRR owner and the review recipient. **Block** the action when all owners have left (no valid owner) — DCC must reassign first.
- **Number suffix**: derive `-SDSM` / `-SDSS` from the document's subclass (mirrors the audit jobs), config-driven with defaults.
- **Constraint**: Agile write-back can't run on this Mac — build both JARs locally, hand off to Vikas for QA deploy + test. The exact DRR **workflow name**, **subclass→suffix mapping**, and any list-value spellings are confirmed against QA during build.

## Architecture — three units

### Unit A — plm-agile-service: `POST /api/document/{doc}/create-drr`
New endpoint + service method mirroring the DCO ones. Uses the existing long-lived `administrator` SDK session and the config-driven `@Value` cell-id pattern.

`AgileWriteBackService.createDrr(String docNumber, CreateDrrRequest req, log)`:
1. **Resolve** `IItem item = session.getObject(IItem.OBJECT_TYPE, docNumber)`; error if null.
2. **Idempotency**: if the item already has an open DRR (reuse the existing pending-DRR lookup), return `{ok:true, drrNumber:<existing>, alreadyExisted:true}` — never create a duplicate.
3. **Subclass**: `IAgileClass changeClass = admin.getAgileClass(ChangeConstants.CLASS_CHANGE_REQUESTS_CLASS)`; find subclass whose name equals the configured DRR subclass (`agile.drr.subclass:DRR`).
4. **Create**: `IChange drr = (IChange) session.createObject(drrSubclass, drrSubclass.getAutoNumberSources()[0]);`
5. **Workflow**: find workflow named `agile.drr.workflow` and `drr.setWorkflow(wf)` → initial status **Pending**.
6. **Suffix** (optional): if a suffix is resolved for the item's subclass, `drr.getCell(cellDrrNumberSuffix /*1047*/).setValue(drr.toString() + suffix)`.
7. **Cover cells** (each config-driven id, fail-soft per cell, recorded in stepsOk):
   - 1099 Change Analyst → select `"Change Analyst"`.
   - 1052 Description → standard DRR review text (config-defaulted).
   - 1053 Reason → `"IMS document yearly review requirement."`.
   - 1060 Priority → `"Standard"`.
   - 1564 Document Owner → resolve each `req.ownerLogins` to `IUser` and set selection (multi).
8. **Affected Items**: `ITable t = drr.getTable(ChangeConstants.TABLE_AFFECTEDITEMS); IRow r = t.createRow(item); r.getCell(cellAffectedRev /*1056*/).setValue(item.getRevision());`
9. Return `CreateDrrResponse {ok, drrNumber, alreadyExisted, stepsOk[], stepFailedAt, errorReason}` — same envelope/corrId tracing as DCO.

New config (`application.properties`, with defaults): `agile.drr.subclass=DRR`, `agile.drr.workflow=<confirm on QA>`, `agile.cell.changeAnalyst=1099` (exists), `agile.cell.coverPageDescription=1052` (exists), `agile.cell.coverPageReason=1053` (exists), `agile.cell.priority=1060`, `agile.cell.documentOwner=1564`, `agile.cell.drrNumberSuffix=1047`, `agile.cell.affectedRev=1056`, `agile.drr.description=<standard text>`, `agile.drr.suffix.default=-SDSM`, `agile.drr.suffix.bySubclass.<name>=-SDSS` (map).

New models in `WriteBackModels.java`: `CreateDrrRequest {docNumber, List<String> ownerLogins, requestorEmail}`, `CreateDrrResponse {ok, drrNumber, alreadyExisted, List<String> stepsOk, stepFailedAt, errorReason}`.

### Unit B — toolkit: orchestrate create → record → send
- `AgileWriteBackClient.createDrr(String docNumber, List<String> ownerLogins, String requestorEmail, String corrId)` → `POST /api/document/{enc(doc)}/create-drr` with `{ownerLogins, requestorEmail}`; returns the standard `Result`.
- `ImsReviewService.sendNewDrr(String docNumber, String dccUser)`:
  1. `DocRow d = lookupDoc(docNumber)`; if `d.drrNumber` non-empty → `{ok:false, reason:"Document already has a DRR ("+d.drrNumber+")"}`.
  2. Compute valid owners (ldapStatus ACTIVE/UNKNOWN/null). If none → `{ok:false, reason:"All document owners have left — reassign an owner before sending a DRR."}`.
  3. `Result r = agileWriteBack.createDrr(docNumber, validOwnerLogins, dccUser, corrId)`. On failure → stamp + return `{ok:false, reason:r.errorReason}`.
  4. On success, take `drrNumber` and invoke the **existing send path** (the same service method `/api/ims-review/send` uses) with `(docNumber, drrNumber, recipients=validOwners)` → queue advances to `SENT_TO_DO`, review email sent. Record `agileSteps "create-drr="+drrNumber` on the event.
  5. Return `{ok:true, drrNumber}`. If create succeeded but send failed: `{ok:true, drrNumber, sendWarning:<reason>}` (DRR exists; DCC can send manually).
- `ImsReviewController`: `POST /api/ims-review/create-drr` `{docNumber}` — admin/DCC gated (same guard as `/send`); returns the result.

### Unit C — frontend (`imsreview.js`)
- In `segmentActionHtml(r)`, replace the `!hasDrr` branch (currently muted `"needs DRR"`):
  - valid owner present → `<button onclick="imsSendNewDrr('<doc>')">＋ Send New DRR</button>`.
  - no valid owner → muted `"reassign owner first"`.
- `window.imsSendNewDrr(doc)`: `appConfirm("Create a new DRR for <doc> and send it to its owner(s)?")` → `POST /api/ims-review/create-drr {docNumber}` → on `ok` `showToast("DRR "+drrNumber+" created & sent")` + `imsReviewRefresh(true)`; on failure `appAlert(reason)`. (Uses ui-modal helpers, not native dialogs.)
- Cache-bust bump.

## Data flow
```
[Need DRR row] --click Send New DRR--> POST /api/ims-review/create-drr {docNumber}
  toolkit ImsReviewService.sendNewDrr:
    lookupDoc -> validate (no DRR, ≥1 valid owner)
    AgileWriteBackClient.createDrr --HTTP--> plm-agile-service POST /api/document/{doc}/create-drr
        SDK: createObject(DRR subclass, autonumber) -> setWorkflow(Pending)
             -> cover cells -> affected item(+rev) -> return DRR-####(-SDSM)
    record queue + existing send-to-owner -> status SENT_TO_DO
  -> row reclassifies to New DRR / Pending Response on refresh
```

## Error handling
- Per-cell fail-soft in the SDK service (one bad list value doesn't abort the create); `stepFailedAt`/`stepsOk` report partials.
- Idempotent create (no duplicate DRRs on double-click / retry).
- Toolkit fail-soft if agile-service down (same as DCO: `{ok:false, errorReason}`); the UI shows the reason, nothing is half-written on the toolkit side.
- Create-ok / send-fail → DRR persists, surfaced as a warning.

## Testing
- **Local**: compile both projects; `node --check` + JS tests for the frontend; the SDK create path is **not** runnable on this Mac.
- **QA (Vikas)**: deploy both JARs; click Send New DRR on a Need-DRR doc with a live owner; confirm a `DRR-####-SDSM` is created (Pending, affected item = the doc with its rev, owner set), the review email is sent, and the row moves to New DRR / Pending Response. Confirm idempotency (second click returns the same DRR).

## Out of scope
- Bulk "Send New DRR" for many Need-DRR docs at once (single-row only for now).
- Attaching the DRR training material (the audit job attaches a server-side `F:\` pptx; skip unless requested).
- Subcontractor (2090) / Product Line (1003) cover fields — optional in the reference; omit unless QA shows they're required for a clean create.
