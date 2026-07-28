# Send New DRR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Send New DRR" action to the IMS Dashboard "Need DRR" rows that creates a DRR in Agile for the document and sends the review request to the document's owner(s), moving the row Need DRR → New DRR / Pending Response.

**Architecture:** Two repos. `plm-agile-service` gets a new SDK endpoint `POST /api/document/{doc}/create-drr` that creates the DRR change directly (mirroring `AuditDocuments.creatingSpecificChange()`), parallel to the existing `create-dco`. The toolkit (`plm-field-tracker`) adds a client method + an orchestrator (`sendNewDrr`) that validates, calls the service, then reuses the existing `send()` flow, plus a controller endpoint and a frontend button.

**Tech Stack:** Java 8 (plm-agile-service) / Java 11 (plm-field-tracker), Spring Boot 2.7, Agile SDK 9.3.6, vanilla ES5 JS.

**Reference spec:** `docs/superpowers/specs/2026-06-29-ims-send-new-drr-design.md`

---

## Constraints & verification reality (read first)

- **Agile SDK write-back cannot run on this Mac.** Both projects compile locally; the DRR-creation path is verified on the QA server by Vikas after deploy. There is no DB-/SDK-backed test harness.
- **QA-confirm items** (defaults provided, confirm against QA during build): DRR **subclass name** (`agile.drr.subclass.name` — default `DRR`, mirroring DCO which was `DCO` not the long name), DRR **workflow name** (`agile.drr.workflow.name` — default `DRR-Workflow`), the SDSM/SDSS **suffix mapping** (`agile.drr.suffix.*`), and cover-cell ids 1060/1564/1047/1056 (defaults from the `AuditDocuments` reference).
- Both JARs build to `target/<artifactId>-<version>.jar` (`plm-agile-service-1.0.0.jar`, `plm-field-tracker-1.0.1.jar`) and stage to the QSS share `plm-toolkit/staging/`.
- Pre-build: update `whats-new.js` (toolkit only).

## File structure

| Repo / File | Change | Task |
|---|---|---|
| plm-agile-service `model/WriteBackModels.java` | add `CreateDrrRequest` / `CreateDrrResponse` | 1 |
| plm-agile-service `service/AgileWriteBackService.java` | add `@Value` config + `createDrr()` + helpers | 2 |
| plm-agile-service `src/main/resources/application.properties` | add `agile.drr.*` + cell config | 2 |
| plm-agile-service `controller/AgileWriteBackController.java` | add `POST /api/document/{doc}/create-drr` | 3 |
| plm-field-tracker `service/AgileWriteBackClient.java` | add `createDrr()` | 4 |
| plm-field-tracker `service/ImsReviewService.java` | add `sendNewDrr()` | 5 |
| plm-field-tracker `controller/ImsReviewController.java` | add `POST /api/ims-review/create-drr` | 5 |
| plm-field-tracker `static/imsreview.js` | "Send New DRR" button + `imsSendNewDrr` | 6 |
| plm-field-tracker `static/whats-new.js` + both builds | changelog + build + stage | 7 |

---

## Task 1: plm-agile-service — request/response models

**Files:** Modify `/Users/vikasjindal/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java`

- [ ] **Step 1: Add the model classes**

After the existing `CreateDcoResponse` class (it ends near line 109, before the final closing `}` of `WriteBackModels`), add:

```java
    public static final class CreateDrrRequest {
        public String docNumber;
        /** Document owner login ids — set as the DRR's Document Owner and
         *  used by the toolkit as the review recipients. */
        public java.util.List<String> ownerLogins = new java.util.ArrayList<>();
        /** AD username/email of the DCC who triggered this (for traceability). */
        public String requestorEmail;
    }

    public static final class CreateDrrResponse {
        public boolean ok;
        public String drrNumber;
        public boolean alreadyExisted;
        public java.util.List<String> stepsOk = new java.util.ArrayList<>();
        public String stepFailedAt;
        public String errorReason;
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home mvn -q -DskipTests compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java
git commit -m "feat(drr): add CreateDrrRequest/Response models

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: plm-agile-service — `createDrr()` SDK method + config

**Files:**
- Modify `/Users/vikasjindal/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java`
- Modify `/Users/vikasjindal/git/plm-agile-service/src/main/resources/application.properties`

This mirrors `AuditDocuments.creatingSpecificChange()`. The service already imports `IChange, ICell, IAgileList, IUser, ITable, IRow, ChangeConstants` and has `describeError(...)` and `safe(...)` helpers and `IAgileSession session`.

- [ ] **Step 1: Add imports**

At the top of `AgileWriteBackService.java`, ensure these Agile API imports exist (add any missing):

```java
import com.agile.api.IAdmin;
import com.agile.api.IAgileClass;
import com.agile.api.IAutoNumber;
import com.agile.api.IWorkflow;
import com.agile.api.IItem;
```

- [ ] **Step 2: Add `@Value` config fields**

In the `@Value` block (next to `cellChangeAnalyst` etc., around lines 52-75), add:

```java
    @Value("${agile.drr.workflow.name:DRR-Workflow}")     private String drrWorkflow;
    @Value("${agile.cell.priority:1060}")                 private int cellPriority;
    @Value("${agile.cell.documentOwner:1564}")            private int cellDocumentOwner;
    @Value("${agile.cell.drrNumberSuffix:1047}")          private int cellDrrNumberSuffix;
    @Value("${agile.cell.affectedRev:1056}")              private int cellAffectedRev;
    @Value("${agile.drr.description:To review document to ensure the contents are still relevant and valid per current product, process and system requirements.}") private String drrDescription;
    @Value("${agile.drr.reason:IMS document yearly review requirement.}") private String drrReason;
    @Value("${agile.drr.suffix.default:-SDSM}")           private String drrSuffixDefault;
    @Value("${agile.drr.suffix.sdss:-SDSS}")              private String drrSuffixSdss;
    @Value("${agile.drr.suffix.sdssSubclasses:}")         private String drrSuffixSdssSubclasses;
```

(`drrSubclass` already exists as `@Value("${agile.drr.subclass.name:Document Review Request}")`.)

- [ ] **Step 3: Add the `createDrr()` method + helpers**

Add immediately after the existing `createDco(...)` method:

```java
    /** Create a DRR Change for a document and link the document as an affected
     *  item. Mirrors AuditDocuments.creatingSpecificChange() from the
     *  documentclass reference. Leaves the DRR at its workflow's initial
     *  status (Pending) — the toolkit sends the review separately. Per-cell
     *  fail-soft: a bad list value records a note and continues; only failing
     *  to create the change or link the document aborts. */
    public CreateDrrResponse createDrr(String docNumber, CreateDrrRequest req, AgileWriteBackLogger log) {
        CreateDrrResponse out = new CreateDrrResponse();
        log.doc(docNumber);
        long t0 = System.currentTimeMillis();
        try {
            // 0) Resolve the document
            IItem item = (IItem) session.getObject(IItem.OBJECT_TYPE, docNumber);
            if (item == null) {
                out.errorReason = "Document not found: " + docNumber;
                out.stepFailedAt = "getItem";
                return out;
            }
            out.stepsOk.add("getItem");

            // 1) Resolve the DRR subclass under Change Requests
            IAdmin admin = session.getAdminInstance();
            IAgileClass changeClass = admin.getAgileClass(ChangeConstants.CLASS_CHANGE_REQUESTS_CLASS);
            IAgileClass drrCls = null;
            IAgileClass[] subs = changeClass.getSubclasses();
            if (subs != null) {
                for (IAgileClass sub : subs) {
                    if (sub != null && sub.getName().equalsIgnoreCase(drrSubclass)) { drrCls = sub; break; }
                }
            }
            if (drrCls == null) {
                out.errorReason = "DRR subclass '" + drrSubclass + "' not found under Change Requests";
                out.stepFailedAt = "findSubclass";
                return out;
            }
            out.stepsOk.add("findSubclass");

            // 2) Create the DRR change using the subclass autonumber source
            IChange drr;
            try {
                IAutoNumber[] autos = drrCls.getAutoNumberSources();
                if (autos == null || autos.length == 0) {
                    out.errorReason = "DRR subclass has no autonumber source";
                    out.stepFailedAt = "createObject";
                    return out;
                }
                drr = (IChange) session.createObject(drrCls, autos[0]);
            } catch (Throwable t) {
                out.errorReason = "Create DRR failed: " + describeError(t);
                out.stepFailedAt = "createObject";
                return out;
            }
            String drrNumber = drr.toString();
            out.drrNumber = drrNumber;
            log.drr(drrNumber);
            out.stepsOk.add("createObject=" + drrNumber);

            // 3) Workflow -> initial status (Pending). Non-fatal.
            try {
                IWorkflow chosen = null;
                IWorkflow[] wfs = drr.getWorkflows();
                if (wfs != null) {
                    for (IWorkflow wf : wfs) {
                        if (wf != null && wf.getName().equalsIgnoreCase(drrWorkflow)) { chosen = wf; break; }
                    }
                }
                if (chosen != null) { drr.setWorkflow(chosen); out.stepsOk.add("setWorkflow"); }
                else log.note("DRR workflow '" + drrWorkflow + "' not found; left on default");
            } catch (Throwable t) { log.note("setWorkflow failed (non-fatal): " + describeError(t)); }

            // 4) Cover-page cells (each fail-soft)
            setDrrListCell(drr, cellChangeAnalyst, "Change Analyst", out, log, "setChangeAnalyst");
            setDrrTextCell(drr, cellCoverPageDescription, drrDescription, out, log, "setDescription");
            setDrrTextCell(drr, cellCoverPageReason, drrReason, out, log, "setReason");
            setDrrListCell(drr, cellPriority, "Standard", out, log, "setPriority");

            // 4a) Document Owner (multi) — resolve owner logins to IUser
            try {
                ICell ownerCell = drr.getCell(cellDocumentOwner);
                IAgileList vals = ownerCell.getAvailableValues();
                java.util.List<Object> users = new java.util.ArrayList<>();
                if (req.ownerLogins != null) {
                    for (String login : req.ownerLogins) {
                        if (login == null || login.trim().isEmpty()) continue;
                        try {
                            IUser u = (IUser) session.getObject(IUser.OBJECT_TYPE, login.trim());
                            if (u != null) users.add(u);
                        } catch (Exception ignored) {}
                    }
                }
                if (!users.isEmpty()) { vals.setSelection(users.toArray()); ownerCell.setValue(vals); out.stepsOk.add("setOwner"); }
                else log.note("no owner IUser resolved from ownerLogins");
            } catch (Throwable t) { log.note("setOwner failed (non-fatal): " + describeError(t)); }

            // 5) Number suffix (-SDSM / -SDSS) from the item subclass. Non-fatal.
            try {
                String suffix = drrSuffixFor(item);
                if (suffix != null && !suffix.isEmpty()) {
                    drr.getCell(cellDrrNumberSuffix).setValue(drrNumber + suffix);
                    out.drrNumber = drrNumber + suffix;
                    out.stepsOk.add("setSuffix=" + suffix);
                }
            } catch (Throwable t) { log.note("setSuffix failed (non-fatal): " + describeError(t)); }

            // 6) Affected Items — add the document + its revision. Fatal if it fails.
            try {
                ITable aff = drr.getTable(ChangeConstants.TABLE_AFFECTEDITEMS);
                IRow row = aff.createRow(item);
                try { row.getCell(cellAffectedRev).setValue(safe(item.getRevision())); }
                catch (Exception revErr) { log.note("set affected rev failed (non-fatal): " + revErr.getMessage()); }
                out.stepsOk.add("addAffectedItem");
            } catch (Throwable t) {
                out.errorReason = "Could not add document to DRR affected items: " + describeError(t);
                out.stepFailedAt = "addAffectedItem";
                return out;
            }

            out.ok = true;
            return out;
        } catch (Throwable t) {
            log.stepFailed("createDrr", t, System.currentTimeMillis() - t0);
            out.errorReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            return out;
        }
    }

    private void setDrrListCell(IChange drr, int cellId, String value,
                                CreateDrrResponse out, AgileWriteBackLogger log, String step) {
        try {
            ICell c = drr.getCell(cellId);
            IAgileList vals = c.getAvailableValues();
            vals.setSelection(new Object[]{ value });
            c.setValue(vals);
            out.stepsOk.add(step);
        } catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }

    private void setDrrTextCell(IChange drr, int cellId, String value,
                                CreateDrrResponse out, AgileWriteBackLogger log, String step) {
        try { drr.getCell(cellId).setValue(value); out.stepsOk.add(step); }
        catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }

    /** Pick -SDSM (default) or -SDSS based on the item's subclass name. The
     *  SDSS subclass list is config (comma-separated, case-insensitive);
     *  confirm the real names on QA and fill agile.drr.suffix.sdssSubclasses. */
    private String drrSuffixFor(IItem item) {
        try {
            String subclass = (item.getAgileClass() == null) ? "" : item.getAgileClass().getName();
            String key = subclass.trim().toLowerCase();
            if (drrSuffixSdssSubclasses != null && !drrSuffixSdssSubclasses.trim().isEmpty()) {
                for (String s : drrSuffixSdssSubclasses.split(",")) {
                    if (!s.trim().isEmpty() && key.equals(s.trim().toLowerCase())) return drrSuffixSdss;
                }
            }
            return drrSuffixDefault;
        } catch (Exception e) { return drrSuffixDefault; }
    }
```

> If `safe(...)` does not exist in this class, use `(item.getRevision() == null ? "" : item.getRevision())` inline instead. Confirm by grep: `grep -n "private.*safe(" AgileWriteBackService.java`.

- [ ] **Step 4: Add config to application.properties**

In `/Users/vikasjindal/git/plm-agile-service/src/main/resources/application.properties`, change the DRR subclass line and add the DRR block (after the existing `agile.cell.updateRequired` line):

Change:
```properties
agile.drr.subclass.name=Document Review Request
```
to:
```properties
# DRR subclass — "DRR" mirrors how the DCO subclass turned out to be "DCO"
# (not the long display name). Confirm on QA; createDrr dumps the available
# subclass names to the log if this name doesn't match.
agile.drr.subclass.name=DRR
```

Add:
```properties
# Send New DRR (create-drr endpoint). Defaults from AuditDocuments.java
# (documentclass reference). Confirm workflow name + suffix mapping on QA.
agile.drr.workflow.name=DRR-Workflow
agile.cell.priority=1060
agile.cell.documentOwner=1564
agile.cell.drrNumberSuffix=1047
agile.cell.affectedRev=1056
agile.drr.reason=IMS document yearly review requirement.
agile.drr.suffix.default=-SDSM
agile.drr.suffix.sdss=-SDSS
# comma-separated item subclass names that should get -SDSS instead of -SDSM
agile.drr.suffix.sdssSubclasses=
```

- [ ] **Step 5: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home mvn -q -DskipTests compile 2>&1 | tail -20`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java src/main/resources/application.properties
git commit -m "feat(drr): createDrr() SDK method + config (mirror AuditDocuments)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: plm-agile-service — controller endpoint

**Files:** Modify `/Users/vikasjindal/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/AgileWriteBackController.java`

- [ ] **Step 1: Add the endpoint**

Immediately after the `createDco(...)` @PostMapping method (ends ~line 197), add:

```java
    @PostMapping(value = "/document/{doc}/create-drr", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createDrr(@PathVariable("doc") String doc,
                                       @RequestBody CreateDrrRequest req,
                                       @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        AgileWriteBackLogger log = new AgileWriteBackLogger(orNew(corrId), "create-drr");
        try {
            if (doc == null || doc.trim().isEmpty()) {
                CreateDrrResponse r = new CreateDrrResponse();
                r.ok = false;
                r.errorReason = "docNumber is required";
                log.note(r.errorReason);
                return ResponseEntity.badRequest().body(withCorr(r, log));
            }
            if (req == null) req = new CreateDrrRequest();
            req.docNumber = doc;
            CreateDrrResponse r = service.createDrr(doc, req, log);
            return r.ok ? ResponseEntity.ok(withCorr(r, log))
                        : ResponseEntity.status(422).body(withCorr(r, log));
        } finally {
            log.summary();
        }
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home mvn -q -DskipTests compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/controller/AgileWriteBackController.java
git commit -m "feat(drr): POST /api/document/{doc}/create-drr endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: toolkit — `AgileWriteBackClient.createDrr()`

**Files:** Modify `/Users/vikasjindal/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java`

- [ ] **Step 1: Add the client method**

Immediately after the existing `createDco(...)` method (ends ~line 204), add:

```java
    /** Create a DRR for a document (no DRR yet). Returns the standard Result
     *  whose body carries {ok, drrNumber, alreadyExisted, ...}. */
    public Result createDrr(String docNumber, List<String> ownerLogins,
                            String requestorEmail, String corrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docNumber", docNumber);
        body.put("ownerLogins", ownerLogins == null ? Collections.emptyList() : ownerLogins);
        if (requestorEmail != null && !requestorEmail.trim().isEmpty()) {
            body.put("requestorEmail", requestorEmail.trim());
        }
        return postJson("/api/document/" + URLEncoder.encode(docNumber, StandardCharsets.UTF_8) + "/create-drr",
                body, orNew(corrId), "create-drr");
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile 2>&1 | tail -15`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java
git commit -m "feat(ims): AgileWriteBackClient.createDrr client method

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: toolkit — `sendNewDrr()` orchestrator + controller endpoint

**Files:**
- Modify `/Users/vikasjindal/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java`
- Modify `/Users/vikasjindal/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java`

- [ ] **Step 1: Add `sendNewDrr()` to ImsReviewService**

Add immediately after the existing `send(...)` method (ends ~line 1090; find it by `public ImsReviewQueueStore.QueueItem send(`):

```java
    /** Create a DRR for a doc that has none, then send the review to its
     *  owner(s) via the existing send() flow. Validates first: the doc must
     *  not already have a DRR and must have at least one active owner.
     *  Returns {ok, drrNumber, status?, reason?, sendWarning?}. */
    public java.util.Map<String, Object> sendNewDrr(String docNumber, String actorEmail, String actorDisplay) {
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        DocRow d = lookupDoc(docNumber);
        if (d == null) { res.put("ok", false); res.put("reason", "Document not found: " + docNumber); return res; }
        if (d.drrNumber != null && !d.drrNumber.trim().isEmpty()) {
            res.put("ok", false);
            res.put("reason", "Document already has a DRR (" + d.drrNumber + ").");
            return res;
        }
        // Valid owners = ACTIVE / UNKNOWN / null ldapStatus (can receive the review).
        java.util.List<String> ownerLogins = new java.util.ArrayList<>();
        for (OwnerRef o : d.owners) {
            String st = o.ldapStatus;
            boolean valid = (st == null || "ACTIVE".equals(st) || "UNKNOWN".equals(st));
            if (valid && o.loginId != null && !o.loginId.trim().isEmpty()) ownerLogins.add(o.loginId);
        }
        if (ownerLogins.isEmpty()) {
            res.put("ok", false);
            res.put("reason", "All document owners have left — reassign an owner before sending a DRR.");
            return res;
        }
        String corrId = java.util.UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBack.createDrr(docNumber, ownerLogins, actorEmail, corrId);
        Object drrObj = (r.body == null) ? null : r.body.get("drrNumber");
        if (!r.ok || drrObj == null || String.valueOf(drrObj).trim().isEmpty()) {
            res.put("ok", false);
            res.put("reason", r.errorReason != null ? r.errorReason : "DRR creation failed");
            return res;
        }
        String drrNumber = String.valueOf(drrObj).trim();
        res.put("drrNumber", drrNumber);
        // Send the review to the owner(s) via the existing flow.
        try {
            ImsReviewQueueStore.QueueItem q = send(docNumber, drrNumber, actorEmail, actorDisplay);
            res.put("ok", true);
            res.put("status", q == null ? null : q.status.name());
        } catch (RuntimeException sendErr) {
            // DRR exists; only the send failed. Surface as partial success.
            res.put("ok", true);
            res.put("sendWarning", sendErr.getMessage());
        }
        return res;
    }
```

> Confirm the field name for the write-back client in this class: `grep -n "AgileWriteBackClient" ImsReviewService.java` — the DCO cascade calls `agileWriteBack.createDco(...)`, so the field is `agileWriteBack`. Use the same field.

- [ ] **Step 2: Add the controller endpoint**

In `ImsReviewController.java`, immediately after the `/send` @PostMapping (ends ~line 145), add:

```java
    /** DCC clicks "Send New DRR" on a Need-DRR row: create the DRR + send it. */
    @PostMapping("/create-drr")
    public ResponseEntity<?> createDrr(HttpSession session,
                                       @RequestParam("docNumber") String docNumber) {
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!hasAdminOrDccAccess(session)) return ResponseEntity.status(403).body(err("Admin/DCC access required."));
        try {
            java.util.Map<String, Object> r = service.sendNewDrr(docNumber,
                    s(session, "email"), s(session, "displayName"));
            boolean ok = Boolean.TRUE.equals(r.get("ok"));
            return ok ? ResponseEntity.ok(r) : ResponseEntity.status(422).body(r);
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }
```

- [ ] **Step 3: Compile**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile 2>&1 | tail -20`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java
git commit -m "feat(ims): sendNewDrr orchestrator + POST /api/ims-review/create-drr

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: toolkit frontend — "Send New DRR" button

**Files:** Modify `/Users/vikasjindal/git/plm-field-tracker/src/main/resources/static/imsreview.js` and `index.html` (cache-bust)

- [ ] **Step 1: Replace the no-DRR action branch in `segmentActionHtml`**

Find the `!hasDrr` branch (grep `needs DRR`): it currently reads
```javascript
        if (!hasDrr) return '<span style="color:#6B7280; font-size:11px;">needs DRR</span>';
```
Replace with:
```javascript
        if (!hasDrr) {
            var hv = (typeof r.hasValidOwner === 'boolean') ? r.hasValidOwner : false;
            return hv
                ? '<button onclick="imsSendNewDrr(\'' + doc + '\')" style="padding:4px 10px; font-size:11px; background:#2c3e50; color:#fff; border:0; border-radius:4px; font-weight:600; cursor:pointer;">&#10010; Send New DRR</button>'
                : '<span style="color:#6B7280; font-size:11px;">reassign owner first</span>';
        }
```
(`doc` is already defined at the top of `segmentActionHtml` as `esc(r.docNumber).replace(/'/g, "\\'")`.)

- [ ] **Step 2: Add the `imsSendNewDrr` handler**

First check the existing confirm helper signature: `grep -n "appConfirm" src/main/resources/static/*.js | head -3`. Mirror that call style. Assuming the callback style `appConfirm(message, function(ok){...})`, add near the other `window.ims*` handlers:

```javascript
    window.imsSendNewDrr = function (doc) {
        appConfirm('Create a new DRR for ' + doc + ' and send it to its owner(s)?', function (ok) {
            if (!ok) return;
            fetch('/api/ims-review/create-drr?docNumber=' + encodeURIComponent(doc), { method: 'POST' })
                .then(function (resp) { return resp.json().then(function (j) { return { ok: resp.ok, j: j }; }); })
                .then(function (x) {
                    if (x.ok && x.j && x.j.ok) {
                        showToast('DRR ' + (x.j.drrNumber || '') + ' created & sent'
                            + (x.j.sendWarning ? ' (created; send had a warning)' : ''));
                        imsReviewRefresh(true);
                    } else {
                        appAlert((x.j && (x.j.reason || x.j.error)) || 'Failed to create DRR.');
                    }
                })
                .catch(function () { appAlert('Failed to create DRR — service unreachable.'); });
        });
    };
```
If `appConfirm` is promise-based instead, adapt to `appConfirm(msg).then(function(ok){...})` — match what the grep shows.

- [ ] **Step 3: Syntax check + bump cache-bust**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && node --check src/main/resources/static/imsreview.js && echo OK`
Then bump the version in `index.html`:
`sed -i '' 's/imsreview.js?v=20260629c/imsreview.js?v=20260629d/' src/main/resources/static/index.html`
Confirm: `grep -n "imsreview.js?v=" src/main/resources/static/index.html`

- [ ] **Step 4: Commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/resources/static/imsreview.js src/main/resources/static/index.html
git commit -m "feat(ims): Send New DRR button on Need-DRR rows

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: changelog + build both + stage

- [ ] **Step 1: What's New entry (toolkit)**

Add a new entry at the TOP of `WHATS_NEW_RELEASES` in `src/main/resources/static/whats-new.js` (match the existing `{date, title, items:[{badge,text}]}` shape):

```javascript
    {
        date: 'June 29, 2026',
        title: 'Send New DRR from the dashboard',
        items: [
            { badge: 'new', text: '<strong>Send New DRR</strong> button on &ldquo;Need DRR&rdquo; rows creates the DRR in Agile and sends the review to the document owner &mdash; the row moves to New DRR / Pending Response.' }
        ]
    },
```
Verify: `node --check src/main/resources/static/whats-new.js`.

- [ ] **Step 2: Build plm-agile-service**

Run: `cd /Users/vikasjindal/git/plm-agile-service && JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home mvn -q -DskipTests package 2>&1 | tail -8 && ls -la target/plm-agile-service-1.0.0.jar`
Expected: BUILD SUCCESS, jar present.

- [ ] **Step 3: Build plm-field-tracker + run JS tests**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && node --test test/js/*.test.js 2>&1 | grep -E "(tests|pass|fail) [0-9]" && JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests package 2>&1 | tail -8 && ls -la target/plm-field-tracker-1.0.1.jar`
Expected: tests pass, BUILD SUCCESS, jar present.

- [ ] **Step 4: Stage both to QSS + local copy**

```bash
cp /Users/vikasjindal/git/plm-agile-service/target/plm-agile-service-1.0.0.jar /Volumes/uls-eq-agliqss/plm-toolkit/staging/
cp /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-eq-agliqss/plm-toolkit/staging/
cp /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```
Verify size parity with `stat -f "%z"` on source vs staged for each. If `/Volumes/uls-eq-agliqss/` isn't mounted, report and stop — don't write elsewhere.

- [ ] **Step 5: Commit changelog**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
git add src/main/resources/static/whats-new.js
git commit -m "chore(ims): What's New entry for Send New DRR

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## QA verification handoff (Vikas, after deploying BOTH jars)

The SDK path can't run on this Mac. On QA after deploying `plm-agile-service-1.0.0.jar` **and** `plm-field-tracker-1.0.1.jar`:
1. On a "Need DRR" row with a live owner, click **Send New DRR** → confirm.
2. Confirm a `DRR-####-SDSM` (or `-SDSS`) is created in Agile: Pending status, the document on the Affected Items tab with its current rev, Document Owner set, Change Analyst/Description/Reason/Priority populated.
3. Confirm the review email goes to the owner and the row moves to **New DRR / Pending Response** (status Sent to DO).
4. If creation fails, read the `[AGILE-WRITE]` / `[AGILE-WRITE-SUMMARY]` lines in `plm-agile-service.log` (grep the corrId) — they name the exact failing step/cell. Most likely tunables: `agile.drr.subclass.name`, `agile.drr.workflow.name`, cover-cell ids — adjust in `application.properties` and restart the service (no rebuild needed).

## Out of scope
- **Service-side idempotency** (duplicate-DRR guard inside `createDrr`). The toolkit's `d.drrNumber`-empty check plus the UI guard cover the real double-click risk; add a service-side "does this item already have an open DRR" check later if QA shows duplicates slipping through.
- Bulk Send New DRR (one row at a time).
- DRR training-material attachment (server-side `F:\` pptx).
- Subcontractor (2090) / Product Line (1003) cover fields — add only if QA shows they're required for a clean Submit later.
