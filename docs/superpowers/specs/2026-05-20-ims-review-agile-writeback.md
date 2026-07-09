# IMS Review — Agile Write-Back Design

**Status:** Draft (Phase 4 follow-on to the IMS Review pilot)
**Owner:** Vikas Jindal
**Spec sources:** `ECN-129414-PROJ_CRD.docx`, `Process Flow Diagram.png`, the in-conversation Q&A on 2026-05-20

---

## 1. Problem

The IMS Review pilot (shipped 2026-05-18, `2026-05-18-ims-review-pilot-design.md`) gets the toolkit through DO + DM electronic-signature responses with signed compliance PDFs. But the *last mile* — closing out the related DRR in Agile and creating a DCO when the DO needs to revise the doc — is still manual: DCC opens the closure email, manually drags PDFs into the DRR, manually creates the DCO, manually adds the affected item, manually links the DRR.

This design closes that gap by writing back to Agile from the toolkit, via the existing `plm-agile-service` Spring Boot companion at `:8081`. Five new endpoints on the service expose the writes the toolkit needs; `ImsReviewService.respondViaToken` cascades into them after each terminal action.

## 2. Decisions locked

| # | Decision | Rationale |
|---|---|---|
| 1 | **Option B: toolkit-owned write-back.** The plm-agile-service mirrors the proven DCO-creation logic from `DocumentReview.java` (lines 231-320 in the plm_java_extract) rather than relying on the existing PX firing when DRR is submitted. | Gives the toolkit control over rev bump + stakeholder notify list, decouples from PX configuration drift. |
| 2 | **DCO side panel = minimal fields.** Only what's needed to submit the DCO; everything else gets defaulted or left blank for DCC to fill. | Per Vikas — dry runs will reveal what else Agile demands. |
| 3 | **Rev bump rule** — integer→next integer (`5`→`6`); single alpha→next alpha (`A`→`B`). | Stated requirement. |
| 4 | **DM Needs Change = kick back to DO** (no DCO panel for DM). | Stated requirement. |
| 5 | **DRR closure stays manual** — toolkit pushes DRR to `Review`; DCC drives to `Implemented`. | Stated requirement. When DCC implements the DCO later, the auto-close relationship rule (see §6.5) closes the DRR. |
| 6 | **DRR is pre-existing.** The yearly-audit job (`DRRReport.java` pattern) creates the DRR before the toolkit gets involved. Toolkit finds it by `(doc, change_type='DRR', status='Pending')` — does NOT create a fresh one. | Stated requirement. |
| 7 | **Service account = `administrator`** (same as DocumentAttachmentsService uses today). | Stated requirement. Already configured in plm-agile-service `application.properties`. |
| 8 | **Stakeholder emails go on the DCO's Notify List cell**, not as separate toolkit-side outbound. | Stated requirement. |
| 9 | **DRR History writes go through `IChange.send(IUser[], String comment)`**. | Direct `TABLE_HISTORY.createRow()` does not exist in any of the 30+ reference projects — it's a system-maintained table. `change.send()` is the SDK-blessed way to land a row of type "Notification" + comment text. Used by `DocumentReview.java:313` itself. |
| 10 | **commoncodebase classes copied (not Maven-installed) into plm-agile-service.** | Cloning + Maven-installing on every dev machine breaks the clone-and-build story. We own a snapshot. Upstream syncs are manual but rare. |
| 11 | **Super-robust Agile logging.** Every SDK call site emits a structured `[AGILE-WRITE]` log line with the action, doc#, DRR#, DCO#, cell base IDs, before/after values, elapsedMs, and exception class+message on failure. Plus a per-request summary line. | "Strong chance we may go thru a couple of iterations" — logs are the only diagnosis surface since this machine can't reach the Agile SDK. |

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│ plm-field-tracker (port 8090)                                           │
│                                                                         │
│  ImsReviewService.respondViaToken()                                     │
│    ├─→ on DO_RESPONSE_NEEDS_CHANGE → AgileWriteBackClient cascade:      │
│    │     1. GET  /pending-drr                                           │
│    │     2. POST /drr/{drr}/attach-file                                 │
│    │     3. POST /drr/{drr}/history                                     │
│    │     4. POST /drr/{drr}/create-dco                                  │
│    ├─→ on DO_RESPONSE_NO_CHANGE → history only                          │
│    ├─→ on DM_RESPONSE_APPROVED → history + /drr/{drr}/status=Review     │
│    └─→ on DM_RESPONSE_SEND_BACK → history only                          │
│                                                                         │
│  Each step's result lands on queue.jsonl event:                         │
│    agileDrr, agileDco, agileSteps[], agileErrorAt, agileCorrelationId   │
└─────────────────────────────────────────────────────────────────────────┘
                              │ HTTP
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ plm-agile-service (port 8081)  ─ runs as `administrator` Agile session  │
│                                                                         │
│  AgileWriteBackController                                               │
│    GET    /api/document/{doc}/pending-drr                               │
│    POST   /api/drr/{drr}/attach-file        (multipart)                 │
│    POST   /api/drr/{drr}/history            (JSON: comment, recipients) │
│    POST   /api/drr/{drr}/status             (JSON: newStatus)           │
│    POST   /api/drr/{drr}/create-dco         (JSON: see §5.5)            │
│                                                                         │
│  AgileWriteBackLogger emits [AGILE-WRITE] for every SDK step.           │
│  AgileWriteBackService orchestrates:                                    │
│    - DrrLookup       ← reuses existing pending-changes SQL idea         │
│    - DrrAttachment   ← createRow on TABLE_ATTACHMENTS                   │
│    - DrrHistory      ← IChange.send(IUser[], comment)                   │
│    - DrrStatus       ← IChange.changeStatus(IStatus)                    │
│    - DcoCreation     ← mirror of DocumentReview.java:231-320            │
│                                                                         │
│  commoncodebase snapshot:                                               │
│    com.sandisk.plm.agile.support.{AgileObject,AgileChange,AgileItem,    │
│                                   AgileTable,Util}                      │
└─────────────────────────────────────────────────────────────────────────┘
```

## 4. Correlation IDs

Every toolkit-side IMS Review action that touches Agile generates a UUID and:
1. Stamps it onto the queue Event (`agileCorrelationId`)
2. Sends it as the `X-Toolkit-Action-Id` HTTP header to plm-agile-service
3. Logs it on the toolkit side in `activity-log.jsonl` (`IMS_REVIEW_AGILE_*` events)
4. plm-agile-service echoes it as a tag on every `[AGILE-WRITE]` line and in the response body

Diagnosing a failed iteration becomes: `grep <corrId> activity-log.jsonl queue.jsonl plm-agile-service.log` and the whole chain shows up.

## 5. Endpoints

### 5.1 `GET /api/document/{doc}/pending-drr`

Find the DRR currently in `Pending` status against the given Document. There should be exactly one (the yearly-audit job is idempotent and only creates one at a time).

**Response 200**
```json
{
  "drrNumber": "DRR-37291",
  "status": "Pending",
  "drrInternalId": 18394502,
  "createdAt": "2026-04-12T03:00:00Z",
  "defaultChange": false
}
```
**Response 404** — no Pending DRR found. Toolkit must error-out (DCC needs to chase the audit job).
**Response 409** — multiple Pending DRRs found; ambiguous. Body lists candidates.

### 5.2 `POST /api/drr/{drr}/attach-file` (multipart)

Attach a file the DO uploaded to the DRR's Attachments table. Toolkit caller already saved the upload under `data/ims-review/uploads/`; this endpoint re-receives the bytes via multipart so the service doesn't have to share a filesystem with the toolkit.

**Form parts**
- `file` — the file (multipart)
- `description` — optional, ≤200 chars, becomes the Attachments-row Description cell

**Response 200**
```json
{ "ok": true, "fileFolderNumber": "FOLDER-00128471", "attachedAs": "revised_iso9001.docx" }
```

### 5.3 `POST /api/drr/{drr}/history`

Write a History entry to the DRR. Implementation = `change.send(IUser[], String comment)`. Recipient list is the AD users whose presence should be recorded; the comment text is what shows up in the History column.

**Request**
```json
{
  "comment": "Document Owner response via Toolkit for yearly review: No Change Needed.\nNotifications triggered via Toolkit to: alice@sandisk.com, dl-doc-owners@sandisk.com",
  "recipientEmails": ["alice@sandisk.com"],
  "recipientGroups": []
}
```

**Response 200**
```json
{ "ok": true, "historyRowsBefore": 14, "historyRowsAfter": 15, "rowDelta": 1 }
```

(`rowDelta=1` is the toolkit's confirmation the History row actually landed. If `rowDelta=0`, the service logs a warning and the toolkit retries with `recipientEmails` set to just the service account — the SDK sometimes rejects an empty user array.)

### 5.4 `POST /api/drr/{drr}/status`

Move the DRR to a named status via `IChange.changeStatus(IStatus)`. Used to push to `Review` on DM No-Change.

**Request**
```json
{ "newStatus": "Review", "comment": "Both DO and DM confirmed No Change Needed via Toolkit." }
```

**Response 200**
```json
{ "ok": true, "previousStatus": "Pending", "newStatus": "Review" }
```

If the SDK throws on signoff/approval requirements, the service catches and returns 422 with the SDK error message. Toolkit surfaces it to the DCC user (the action was electronic so DCC sees the failure on the dashboard, not the DM).

### 5.5 `POST /api/drr/{drr}/create-dco`

The big one — mirrors `DocumentReview.java:231-320`. Creates a new DCO, sets Change Analyst group, adds the IMS Doc as Affected Item with the bumped New Rev, links the DRR into the DCO's Relationships table with the auto-close-on-Implement rule, sets DCO Notify List, optionally submits.

**Request**
```json
{
  "docNumber": "33-05-SM-03-00011",
  "currentRev": "5",
  "description": "Updated audit checklist per ISO 9001:2025.",
  "reasonForChange": "IMS yearly review — DO submitted revised version via PLM Toolkit.",
  "notifyEmails": ["alice@sandisk.com", "bob@sandisk.com"],
  "notifyGroups": ["IMS-Doc-Managers-Agile"],
  "autoSubmit": true
}
```

The service computes the bumped rev (integer→+1, alpha→next letter) internally; that's authoritative even if the toolkit sent one too. If both are sent and they disagree, the service uses its own value and logs a warning.

**Response 200**
```json
{
  "ok": true,
  "dcoNumber": "DCO-00524891",
  "newRev": "6",
  "affectedItemAdded": true,
  "relationshipRuleApplied": true,
  "notifyListSet": true,
  "submitted": true,
  "currentStatus": "Submitted"
}
```

**Response 422** — partial success; the JSON body lists `stepsOk[]` and `stepFailedAt` so the toolkit can decide whether to retry the whole thing or just resume from the failed step. The DCO# (if any was created before failure) is returned so the toolkit can record it on queue.jsonl and avoid double-creating on retry.

## 6. The DCO creation walk-through

Code mirrors `DocumentReview.java:240-282` exactly. Annotated to call out what we change vs the reference.

```java
// Step 1 — create the DCO (auto-numbered)
AgileChange dco = agObject.creatingSpecificChange(
    props.dcoWorkflow,                               // from application.properties
    ChangeConstants.CLASS_CHANGE_ORDERS_CLASS,
    props.dcoSubclass);                              // from application.properties
log.step("createChange", dco.returnChange().getName());

// Step 2 — set Change Analyst group (cell 1099)
IAgileList caList = dco.returnChange().getCell(1099).getAvailableValues();
caList.setSelection(new Object[]{"Change Analyst"});
dco.returnChange().getCell(1099).setValue(caList);
log.step("setChangeAnalyst", "Change Analyst");

// Step 3 — add IMS Doc as Affected Item
ITable affected = dco.getTable(ChangeConstants.TABLE_AFFECTEDITEMS).returnTable();
IRow affRow = affected.createRow(item);
log.step("affectedItemRow", "added " + item.getName());

// Step 3a — bump New Rev (this is NEW vs the reference PX, which leaves rev unset)
String oldRev = item.getRevision();
String newRev = computeNextRev(oldRev);
ICell newRevCell = affRow.getCell(ATT_AFFECTED_ITEMS_NEW_REV);   // base ID confirmed during dry run #1
newRevCell.setValue(newRev);
log.step("setNewRev", "oldRev=" + oldRev + " newRev=" + newRev);

// Step 4 — stamp DCO# onto DRR's page-3 "DCO Number" cell (1575)
drr.setValue(1575, dco.returnChange().getName());
log.step("stampDcoOnDrr", dco.returnChange().getName());

// Step 5 — relationship table + auto-close rule
ITable relations = dco.getTable(ChangeConstants.TABLE_RELATIONSHIPS).returnTable();
IRow relRow = relations.createRow(drr.returnChange());
HashMap<Integer, Object> map = new HashMap<>();
map.put(ATT_RELATIONSHIPS_RULE_CONTROLOBJECT,        dco.returnChange());
map.put(ATT_RELATIONSHIPS_RULE_AFFECTEDOBJECT,       drr.returnChange());
map.put(ATT_RELATIONSHIPS_RULE_CONTROLOBJECTSTATUS,  findStatus(dco, "Implemented"));
map.put(ATT_RELATIONSHIPS_RULE_AFFECTEDOBJECTSTATUS, findStatus(drr, "Implemented"));
relRow.setValue(ATT_RELATIONSHIPS_RULE, map);
log.step("relationshipRule", "control=DCO@Implemented affected=DRR@Implemented");

// Step 6 — Notify List (NEW vs reference; CRD stakeholder notification)
// Cell ID confirmed during dry run #2
if (req.notifyEmails != null && !req.notifyEmails.isEmpty()) {
    setNotifyList(dco, req.notifyEmails, req.notifyGroups);
    log.step("notifyList", emails + " + " + groups);
}

// Step 7 — description + reason cells (1052, 1053)
dco.returnChange().setValue(1052, req.reasonForChange);
dco.returnChange().setValue(1053, req.description);
log.step("setDescription", "ok");

// Step 8 — submit if requested
if (req.autoSubmit) {
    IStatus submitted = findStatus(dco, "Submitted");
    ((IStateful) dco.returnChange()).changeStatus(submitted, null, null,
            "Auto-submitted by PLM Toolkit IMS Review write-back", null, null, null, false, null);
    log.step("submit", "status=Submitted");
}
```

The function-by-function step logs make it trivial to see exactly which call breaks on the first dry run.

## 7. Logging contract (the core observability commitment)

Every step emits one line via `AgileWriteBackLogger.step(...)`:

```
[AGILE-WRITE] corrId=<uuid> action=<action> doc=<doc> drr=<drr> dco=<dco>
              step=<step> param=<k=v;k=v> result=<ok|FAIL>
              elapsedMs=<n> err=<exClass:msg>?
```

On failure (catch in the controller layer), one summary line is added:

```
[AGILE-WRITE-SUMMARY] corrId=<uuid> action=<action> doc=<doc> drr=<drr> dco=<dco>
                      totalMs=<n> stepsOk=<n> stepsFailed=<n>
                      failedAt=<lastStep> err=<exClass:msg>
```

These lines are designed to be **greppable from a single corrId** — paste a UUID into your terminal and you see exactly what happened.

Plus on toolkit side, the `activity-log.jsonl` gets new event types so the chain is visible from the toolkit end too:

- `IMS_REVIEW_AGILE_FIND_DRR`
- `IMS_REVIEW_AGILE_ATTACH_FILE`
- `IMS_REVIEW_AGILE_HISTORY`
- `IMS_REVIEW_AGILE_STATUS`
- `IMS_REVIEW_AGILE_CREATE_DCO`

Each carries `corrId`, the queue event UUID it's tied to, and the service response body (or error).

## 8. Failure handling + idempotency

| Endpoint | Idempotent? | What if toolkit retries? |
|---|---|---|
| GET pending-drr | Yes (pure read) | Same answer — safe to retry |
| POST attach-file | **No** — would create a duplicate attachment | Toolkit records `attached=ok` on queue Event after first success; on retry, checks the flag and skips. |
| POST history | **No** — would create a duplicate History row | Same flag approach. Idempotency key = `(corrId, action, recipient-set)`; service rejects with 409 if it sees the same key twice within 5 min. |
| POST status | **No** — already-applied status throws | Service catches the SDK error and returns 200 with `idempotent=true` if `previousStatus == newStatus`. |
| POST create-dco | **No** — would double-create | Toolkit stores `agileDco` on queue Event; if non-null, skips this call entirely on retry. Service also de-dups: if a DRR already has a non-null cell-1575 (DCO Number), returns 409 with the existing DCO#. |

The toolkit's retry policy on transient errors (HTTP 5xx, connect-refused): exponential backoff, max 3 attempts, 2/4/8 sec. After that, the event is marked `agileError`-stamped and DCC sees a red banner on the row with "Agile write-back failed — see logs / contact Vikas".

## 9. Commoncodebase snapshot

Classes copied (filename unchanged, package becomes `com.sandisk.plm.agile.support`):

| Source | Destination | Used for |
|---|---|---|
| `~/git/commoncodebase/src/sandisk/agile/AgileObject.java` | plm-agile-service `support/AgileObject.java` | `creatingSpecificChange()` helper |
| `~/git/commoncodebase/src/sandisk/agile/AgileChange.java` | plm-agile-service `support/AgileChange.java` | wraps IChange |
| `~/git/commoncodebase/src/sandisk/agile/AgileItem.java` | plm-agile-service `support/AgileItem.java` | wraps IItem |
| `~/git/commoncodebase/src/sandisk/agile/AgileTable.java` | plm-agile-service `support/AgileTable.java` | wraps ITable |
| `~/git/commoncodebase/src/sandisk/agile/Util.java` | plm-agile-service `support/Util.java` | misc helpers (getUsersList etc.) |
| `~/git/commoncodebase/src/sandisk/agile/exception/AgileException.java` | plm-agile-service `support/AgileException.java` | (only if transitively required) |

Each file gets a header:
```java
/**
 * Snapshot from ~/git/commoncodebase/src/sandisk/agile/<Filename>.java
 * taken on 2026-05-20 for the IMS Review Agile write-back work.
 * Re-sync manually if upstream changes meaningfully.
 */
```

## 10. Dry-run checklist

The local Mac can't talk to the Agile SDK, so the actual verification is by Vikas after deploy. To make iteration efficient, each deploy is paired with a checklist:

**Dry run #1 — DRR lookup + attach + history**
- [ ] `GET /api/document/<known-doc>/pending-drr` returns the expected DRR#
- [ ] `POST /api/drr/<drr>/attach-file` adds the file to the DRR Attachments tab (verify in Agile UI)
- [ ] `POST /api/drr/<drr>/history` adds a row to the History tab (verify in Agile UI)
- [ ] Log line: `step=send` shows comment + recipient

**Dry run #2 — DRR status push**
- [ ] `POST /api/drr/<drr>/status {newStatus: "Review"}` moves the DRR (verify in Agile UI)
- [ ] If signoff blocks, log line shows exact error → adjust call args

**Dry run #3 — DCO creation (the meaty one)**
- [ ] `POST /api/drr/<drr>/create-dco` creates a DCO (verify in Agile UI)
- [ ] DCO has IMS Doc as Affected Item
- [ ] Affected Item row has the bumped New Rev
- [ ] DRR's page-3 "DCO Number" cell = new DCO#
- [ ] DCO Relationships tab shows DRR with the auto-close rule
- [ ] DCO Notify List has the requested emails + groups
- [ ] DCO is in `Submitted` status (if autoSubmit was true)

**Dry run #4 — auto-close rule end-to-end**
- [ ] DCC manually drives the new DCO to `Implemented` in Agile
- [ ] The linked DRR auto-closes to `Implemented` (verifies the relationship rule works)

Each dry run produces a log file; we iterate on the issues found before moving to the next.

## 11. What changes in the toolkit

| File | Change |
|---|---|
| `service/AgileWriteBackClient.java` (NEW) | HTTP client over the 5 endpoints, retry logic, corrId generation |
| `service/ImsReviewService.java` | In `respondViaToken()`, after PDF generation, call `AgileWriteBackClient` per the action type. Stamp results on queue Event. |
| `service/ImsReviewQueueStore.java` | Add 5 new fields to `Event`: `agileCorrelationId`, `agileDrr`, `agileDco`, `agileSteps` (List<String>), `agileError` |
| `static/imsreview.js` | On success modal, surface DCO# and "DRR pushed to Review" pill |
| `static/whats-new.js` | New release entry |
| `application.properties` | New keys for plm-agile-service URL (already present) and write-back retry config |

## 12. What changes in plm-agile-service

| File | Change |
|---|---|
| `controller/AgileWriteBackController.java` (NEW) | 5 endpoints |
| `service/AgileWriteBackService.java` (NEW) | Orchestrator |
| `service/DrrLookupService.java` (NEW) | `findPendingDrr(doc)` |
| `service/DrrAttachmentService.java` (NEW) | `attachFile(drr, bytes, filename, desc)` |
| `service/DrrHistoryService.java` (NEW) | `appendHistory(drr, comment, recipients)` via `change.send()` |
| `service/DrrStatusService.java` (NEW) | `changeStatus(drr, newStatusName, comment)` |
| `service/DcoCreationService.java` (NEW) | `createDco(req)` — the meaty function |
| `service/AgileWriteBackLogger.java` (NEW) | Structured logger |
| `service/RevBumper.java` (NEW) | `nextRev(current)` — pure function, unit-testable |
| `support/{AgileObject,AgileChange,AgileItem,AgileTable,Util}.java` (NEW snapshots) | commoncodebase classes |
| `application.properties` | New keys for DCO workflow + subclass names, DRR workflow + subclass names |
| `pom.xml` | No new dependencies needed (already has agile-api 9.3.6) |

## 13. Rollout plan

1. Code lands, fat jars build clean (toolkit + plm-agile-service)
2. Email Vikas: "ready to deploy for dry run #1" with the 4 checklists attached
3. Vikas deploys both jars to the appropriate env
4. Vikas runs through dry-run #1; sends back the log file
5. We iterate on whatever broke; new jar built; repeat
6. Once all 4 dry runs pass, write-back goes live with a kill-switch (`app.ims-review.writeback-enabled=false` flips back to PDF-email-only behavior)

## 14. Out of scope (deferred)

- **Retrigger emails with new toolkit URL** — separate small feature, will spec separately. Not blocking write-back.
- **DCC override of the manager** — separate UI affordance, not write-back per se.
- **Stakeholder follow-on notifications after DCO release** — Agile workflow notifications + DCC-driven, no toolkit involvement.

---

## Appendix A — Agile cell base IDs we'll need

| Cell | Confirmed? | Source |
|---|---|---|
| 1099 (Change Analyst group on all change classes) | Yes | DocumentReview.java:245 |
| 1052 (Description on change cover page) | Yes | DRRReport.java:190 |
| 1053 (Reason for Change on change cover page) | Yes | DRRReport.java:192 |
| 1056 (Old Rev on DRR Affected Items) | Yes | DRRReport.java:201 |
| 1575 (DCO Number on DRR page 3) | Yes | DocumentReview.java:252 |
| ATT_RELATIONSHIPS_RULE on Relationships table | Yes | DocumentReview.java:281 |
| ATT_AFFECTED_ITEMS_NEW_REV on DCO Affected Items | **NO — dry run #3** | Will log all cell IDs on the row, identify by name |
| Notify List on DCO | **NO — dry run #3** | Same approach — log cell-iteration result |

## Appendix B — DCO/DRR workflow + subclass names

These come from a properties file in production. Toolkit will read them from `application.properties` keys:

```properties
agile.dco.workflow.name=DCO Workflow
agile.dco.subclass.name=Document Change Order
agile.drr.workflow.name=DRR Workflow
agile.drr.subclass.name=Document Review Request
```

If these are wrong in our env, dry run #3 will fail at `creatingSpecificChange()` with a clear error.

## Appendix C — Rev bump truth table

| Current | Next | Rule |
|---|---|---|
| `0`, `1`, … `9` | `1`, `2`, … `10` | parseInt + 1 |
| `10`, `11`, … | `11`, `12`, … | parseInt + 1 |
| `A`, `B`, … `Y` | `B`, `C`, … `Z` | single-letter `+1` |
| `Z` | `AA` | rollover |
| `AA` | `AB` | second-letter `+1` |
| `AZ` | `BA` | rollover |
| (empty) | `A` | first rev defaults to A |
| `null` | `A` | first rev defaults to A |
| anything else (e.g. `5.1`) | log warning, return `current + "+1"` placeholder — DCC fixes manually | safety net |
