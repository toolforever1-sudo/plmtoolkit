# Create Restart ECN from IT Enhancements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-click "Create Restart ECN" action to the toolkit's IT Enhancements tab that creates a deployment ECN in Agile (via the SDK), bundling the enhancement ECNs at IT Status "UAT complete, CAB Prep" as Relationships, then auto-submits it.

**Architecture:** Two repos mirroring the existing Send New DRR feature. `plm-agile-service` gains a `createRestartEcn` SDK method (per-user transient session, like `update-cell-as-user`) + a REST endpoint. `plm-field-tracker` gains an AI proposal builder, a re-validation/orchestration service, two controller endpoints, an `AgileWriteBackClient` method, and a create dialog in `it-enhancements.js`.

**Tech Stack:** Java 11 / Spring Boot 2.7 (both repos, `mvn`), Agile PLM SDK (`com.agile.api.*`), PortkeyClient (LLM gateway), vanilla JS frontend.

**Spec:** `docs/superpowers/specs/2026-07-01-restart-ecn-it-enhancements-design.md`

---

## Reality checks (read before starting)

- **The SDK write cannot run on this Mac.** `plm-agile-service` opens a live Agile session; it can only be exercised on the QA server. So Phase A tasks are **compile + deploy-to-QA + Vikas-verifies**, not local TDD. This matches how Send New DRR shipped (`project_ims_send_new_drr`).
- **`plm-agile-service` has no test suite** (`src/test` does not exist). Do not invent one; verify Phase A with `mvn clean package` (compile) and QA smoke.
- **Cell base IDs for ECN cover fields are confirmed on QA.** We wire them as `agile.restartEcn.cell.*` config with best-guess defaults (some known: Priority 1060, Change Analyst 1099, Cover Description 1052, Problem Statement flex 251747661). Vikas confirms/tunes on QA without a rebuild — same approach the DRR feature used for "TBD" cells.
- **The toolkit logic IS unit-testable** (proposal builder + AI fail-soft, candidate filtering, server-side re-validation). Phase B uses real TDD.
- **Per CLAUDE.md**: update `whats-new.js` before the build (Phase D); commit only `.java`/code files relevant to the feature; stage the JARs to the QSS/PCCB `staging/` folders, never live.

---

## File Structure

### plm-agile-service (`/Users/vikasjindal/git/plm-agile-service`)
- **Modify** `src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java` — add `CreateRestartEcnRequest`, `CreateRestartEcnResponse`.
- **Modify** `src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java` — add `createRestartEcn(...)`, its `@Value` config fields, and a user-cell helper.
- **Modify** `src/main/java/com/sandisk/plm/agile/controller/AgileWriteBackController.java` — add `POST /api/change/create-restart-ecn`.
- **Modify** `src/main/resources/application.properties.template` (and the gitignored `application.properties` on QA) — add `agile.restartEcn.*`.

### plm-field-tracker (`/Users/vikasjindal/git/plm-field-tracker`)
- **Create** `src/main/java/com/sandisk/plm/tracker/service/RestartEcnProposalBuilder.java` — builds Proposal + Problem Statement (AI-condensed bullets, fail-soft).
- **Create** `src/main/java/com/sandisk/plm/tracker/service/LineSummarizer.java` — functional interface for the AI condense seam.
- **Create** `src/main/java/com/sandisk/plm/tracker/service/PortkeyLineSummarizer.java` — Spring impl using `PortkeyClient` (fail-soft returns null).
- **Create** `src/main/java/com/sandisk/plm/tracker/service/RestartEcnService.java` — candidate listing, server-side re-validation, orchestration.
- **Modify** `src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java` — add `createRestartEcn(...)`.
- **Modify** `src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java` — add `GET /restart-ecn-candidates`, `POST /create-restart-ecn`.
- **Modify** `src/main/resources/application.properties` — add `restartEcn.*` keys.
- **Modify** `src/main/resources/static/it-enhancements.js` — toolbar button, create dialog, submit, toast.
- **Modify** `src/main/resources/static/index.html` — button element + bump `it-enhancements.js?v=`.
- **Modify** `src/main/resources/static/whats-new.js` — new release entry (Phase D).
- **Create** tests under `src/test/java/com/sandisk/plm/tracker/service/`:
  `RestartEcnProposalBuilderTest.java`, `RestartEcnServiceTest.java`.

---

## Phase A — plm-agile-service (SDK writer)

> Verify with `cd /Users/vikasjindal/git/plm-agile-service && mvn clean package -q`. No unit tests here.

### Task A1: Add request/response DTOs

**Files:**
- Modify: `src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java`

- [ ] **Step 1: Add the two classes** (place next to `CreateDrrRequest`/`CreateDrrResponse`)

```java
    public static final class CreateRestartEcnRequest {
        /** Acting Agile user (per-user PX session) — Requestor becomes this user. */
        public String asUsername;
        /** Acting user's password — never logged. */
        public String asPassword;
        /** Final Proposal text, built toolkit-side (header + AI bullets). */
        public String proposalText;
        /** Final Problem Statement text, built toolkit-side. */
        public String problemStatement;
        /** Chosen IT Owner login (Agile Assigned-IT-Owner list member). */
        public String itOwnerLogin;
        /** Enhancement ECN numbers to add as Relationships. */
        public java.util.List<String> relatedEcns = new java.util.ArrayList<>();
    }

    public static final class CreateRestartEcnResponse {
        public boolean ok;
        public String ecnNumber;
        public boolean submitted;
        public java.util.List<String> stepsOk = new java.util.ArrayList<>();
        public String stepFailedAt;
        public String errorReason;
        /** ECN numbers successfully related. */
        public java.util.List<String> relatedOk = new java.util.ArrayList<>();
        /** [{ecn, reason}] for relationships that failed (fail-soft). */
        public java.util.List<java.util.Map<String,String>> relatedFailed = new java.util.ArrayList<>();
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /Users/vikasjindal/git/plm-agile-service
git add src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java
git commit -m "feat(restart-ecn): add CreateRestartEcn request/response DTOs"
```

---

### Task A2: Add `createRestartEcn` config fields + user-cell helper

**Files:**
- Modify: `src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java`

Reference: the existing `@Value` block (lines 47–94), `setDrrListCell`/`setDrrTextCell` (lines 1244–1264), and the Document-Owner user block inside `createDrr` (the `IUser` + `setSelection` pattern).

- [ ] **Step 1: Add config fields** (place after the existing `agile.drr.*` `@Value` fields)

```java
    // --- Restart ECN (deployment ECN) config ---
    @Value("${agile.restartEcn.subclass:ECN}")                 private String restartEcnSubclass;
    @Value("${agile.restartEcn.workflow:ECR - Workflow}")      private String restartEcnWorkflow;
    @Value("${agile.restartEcn.requestClassification:Restart Agile Service}") private String restartEcnClassification;
    @Value("${agile.restartEcn.category:NA}")                  private String restartEcnCategory;
    @Value("${agile.restartEcn.disposition:N/A}")             private String restartEcnDisposition;
    @Value("${agile.restartEcn.productLine:N/A}")            private String restartEcnProductLine;
    @Value("${agile.restartEcn.priority:Standard}")           private String restartEcnPriority;
    @Value("${agile.restartEcn.priorityLevel:3-Low}")         private String restartEcnPriorityLevel;
    @Value("${agile.restartEcn.analystLogin:administrator}")  private String restartEcnAnalystLogin;
    // Cover-page cell base IDs — CONFIRM ON QA (defaults are best guesses / shared with DRR).
    @Value("${agile.restartEcn.cell.requestClassification:1564}") private int cellReqClassification;
    @Value("${agile.restartEcn.cell.category:1561}")          private int cellCategory;
    @Value("${agile.restartEcn.cell.disposition:1562}")       private int cellDisposition;
    @Value("${agile.restartEcn.cell.productLine:1563}")       private int cellProductLine;
    @Value("${agile.restartEcn.cell.priorityLevel:1794}")     private int cellPriorityLevel;
    @Value("${agile.restartEcn.cell.proposal:1052}")          private int cellProposal;
    @Value("${agile.restartEcn.cell.problemStatement:251747661}") private int cellProblemStatement;
    // Assigned IT Owner is set by cell NAME (LIST53) — stable across environments.
    @Value("${agile.restartEcn.cell.itOwnerName:Page Three.Assigned IT Owner}") private String cellItOwnerName;
```

> The IT Owner is set by cell **name** (like `update-cell-as-user`) because the name `Page Three.Assigned IT Owner` is stable, whereas its base id varies. Analyst reuses the existing `cellChangeAnalyst` (1099).

- [ ] **Step 2: Add a reusable user-cell helper** (place next to `setDrrListCell`)

```java
    /** Set a single-user selection on a change cell resolved by base id.
     *  Fail-soft: logs and continues on any error. */
    private void setUserCellById(IChange ch, int cellId, String login,
                                 CreateRestartEcnResponse out, AgileWriteBackLogger log, String step) {
        try {
            if (login == null || login.trim().isEmpty()) { log.note(step + " skipped (no login)"); return; }
            IUser u = (IUser) session.getObject(IUser.OBJECT_TYPE, login.trim());
            if (u == null) { log.note(step + " skipped (user not found: " + login + ")"); return; }
            ICell c = ch.getCell(cellId);
            IAgileList vals = c.getAvailableValues();
            vals.setSelection(new Object[]{ u });
            c.setValue(vals);
            out.stepsOk.add(step);
        } catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }

    /** Set a single-user selection on a change cell resolved by cell NAME. */
    private void setUserCellByName(IChange ch, String cellName, String login,
                                   CreateRestartEcnResponse out, AgileWriteBackLogger log, String step) {
        try {
            if (login == null || login.trim().isEmpty()) { log.note(step + " skipped (no login)"); return; }
            IUser u = (IUser) session.getObject(IUser.OBJECT_TYPE, login.trim());
            if (u == null) { log.note(step + " skipped (user not found: " + login + ")"); return; }
            ICell c = ch.getCell(cellName);
            if (c == null) { log.note(step + " skipped (cell not present: " + cellName + ")"); return; }
            IAgileList vals = c.getAvailableValues();
            vals.setSelection(new Object[]{ u });
            c.setValue(vals);
            out.stepsOk.add(step);
        } catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }
```

> `setDrrListCell`/`setDrrTextCell` take a `CreateDrrResponse`. Add sibling overloads that take `CreateRestartEcnResponse`, OR (simpler) make small copies `setEcnListCell`/`setEcnTextCell` with the identical body but `CreateRestartEcnResponse out`. Do the copies — the two response types are unrelated classes, and copying keeps DRR untouched:

```java
    private void setEcnListCell(IChange ch, int cellId, String value,
                                CreateRestartEcnResponse out, AgileWriteBackLogger log, String step) {
        try {
            ICell c = ch.getCell(cellId);
            IAgileList vals = c.getAvailableValues();
            vals.setSelection(new Object[]{ value });
            c.setValue(vals);
            out.stepsOk.add(step);
        } catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }

    private void setEcnTextCell(IChange ch, int cellId, String value,
                                CreateRestartEcnResponse out, AgileWriteBackLogger log, String step) {
        try { ch.getCell(cellId).setValue(value); out.stepsOk.add(step); }
        catch (Throwable t) { log.note(step + " failed (non-fatal): " + describeError(t)); }
    }
```

- [ ] **Step 3: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q compile`
Expected: BUILD SUCCESS (helpers unused yet — OK, no unused-warning failure in this project's config).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java
git commit -m "feat(restart-ecn): config fields + user/list/text cell helpers"
```

---

### Task A3: Implement `createRestartEcn` (per-user session, create → cells → relationships → submit)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java`

> **Session model:** unlike `createDrr` (shared service session), this opens a **transient per-user PX session** (from `req.asUsername`/`req.asPassword`) so the Requestor is the acting user, then closes it — exactly the pattern in the controller's `update-cell-as-user`. Because we open a local session, use it (not the field `session`) for all SDK calls in this method.

- [ ] **Step 1: Add the method** (place after `createDrr`)

```java
    public CreateRestartEcnResponse createRestartEcn(CreateRestartEcnRequest req, AgileWriteBackLogger log) {
        CreateRestartEcnResponse out = new CreateRestartEcnResponse();
        long t0 = System.currentTimeMillis();
        IAgileSession userSession = null;
        try {
            if (req == null || req.asUsername == null || req.asUsername.trim().isEmpty()
                    || req.asPassword == null || req.asPassword.isEmpty()) {
                out.errorReason = "asUsername/asPassword required";
                out.stepFailedAt = "auth";
                return out;
            }
            // Per-user transient session (PX token flow — same as update-cell-as-user).
            HashMap<Object, Object> params = new HashMap<>();
            params.put(AgileSessionFactory.PX_USERNAME, req.asUsername.trim());
            params.put(AgileSessionFactory.PX_PASSWORD, req.asPassword);
            userSession = AgileSessionFactory.getInstance(agileUrl).createSession(params);
            out.stepsOk.add("login=" + req.asUsername.trim());

            try { userSession.disableAllWarnings(); log.note("disableAllWarnings() OK"); }
            catch (Throwable w) { log.note("disableAllWarnings failed (continuing): " + describeError(w)); }

            // 1) Resolve the ECN subclass under the ECO (Changes) class.
            IAdmin admin = userSession.getAdminInstance();
            IAgileClass ecoClass = admin.getAgileClass(ChangeConstants.CLASS_ECO);
            if (ecoClass == null) { out.errorReason = "ECO class not found"; out.stepFailedAt = "findSubclass"; return out; }
            IAgileClass ecnCls = null;
            IAgileClass[] subs = ecoClass.getSubclasses();
            if (subs != null) for (IAgileClass sub : subs) {
                if (sub != null && sub.getName().equalsIgnoreCase(restartEcnSubclass)) { ecnCls = sub; break; }
            }
            if (ecnCls == null) { out.errorReason = "ECN subclass '" + restartEcnSubclass + "' not found"; out.stepFailedAt = "findSubclass"; return out; }
            out.stepsOk.add("findSubclass");

            // 2) Create via subclass autonumber.
            IChange ecn;
            try {
                IAutoNumber[] autos = ecnCls.getAutoNumberSources();
                if (autos == null || autos.length == 0) { out.errorReason = "ECN subclass has no autonumber source"; out.stepFailedAt = "createObject"; return out; }
                ecn = (IChange) userSession.createObject(ecnCls, autos[0]);
            } catch (Throwable t) { out.errorReason = "Create ECN failed: " + describeError(t); out.stepFailedAt = "createObject"; return out; }
            out.ecnNumber = ecn.toString();
            log.drr(out.ecnNumber);           // reuse the logger's change-id field
            out.stepsOk.add("createObject=" + out.ecnNumber);

            // 3) Workflow (non-fatal).
            try {
                IWorkflow chosen = null;
                IWorkflow[] wfs = ecn.getWorkflows();
                if (wfs != null) for (IWorkflow wf : wfs)
                    if (wf != null && wf.getName().equalsIgnoreCase(restartEcnWorkflow)) { chosen = wf; break; }
                if (chosen != null) { ecn.setWorkflow(chosen); out.stepsOk.add("setWorkflow"); }
                else log.note("workflow '" + restartEcnWorkflow + "' not found; left on default");
            } catch (Throwable t) { log.note("setWorkflow failed (non-fatal): " + describeError(t)); }

            // 4) Cover-page constants + toolkit-built text (each fail-soft).
            setEcnListCell(ecn, cellReqClassification, restartEcnClassification, out, log, "setRequestClassification");
            setEcnListCell(ecn, cellCategory, restartEcnCategory, out, log, "setCategory");
            setEcnListCell(ecn, cellDisposition, restartEcnDisposition, out, log, "setDisposition");
            setEcnListCell(ecn, cellProductLine, restartEcnProductLine, out, log, "setProductLine");
            setEcnListCell(ecn, cellPriority, restartEcnPriority, out, log, "setPriority");
            setEcnListCell(ecn, cellPriorityLevel, restartEcnPriorityLevel, out, log, "setPriorityLevel");
            setEcnTextCell(ecn, cellProposal, req.proposalText == null ? "" : req.proposalText, out, log, "setProposal");
            setEcnTextCell(ecn, cellProblemStatement, req.problemStatement == null ? "" : req.problemStatement, out, log, "setProblemStatement");
            setUserCellById(ecn, cellChangeAnalyst, restartEcnAnalystLogin, out, log, "setAnalyst");
            setUserCellByName(ecn, cellItOwnerName, req.itOwnerLogin, out, log, "setItOwner");

            // 5) Relationships — add each related ECN (fail-soft per ECN).
            try {
                ITable rels = ecn.getTable(ChangeConstants.TABLE_RELATIONSHIPS);
                if (req.relatedEcns != null) for (String rel : req.relatedEcns) {
                    if (rel == null || rel.trim().isEmpty()) continue;
                    String relNum = rel.trim();
                    try {
                        IChange relCh = (IChange) userSession.getObject(IChange.OBJECT_TYPE, relNum);
                        if (relCh == null) { out.relatedFailed.add(failEntry(relNum, "not found")); continue; }
                        rels.createRow(relCh);
                        out.relatedOk.add(relNum);
                    } catch (Throwable rt) {
                        String d = describeError(rt);
                        // "already related" is benign — Agile still links it.
                        if (d != null && d.toLowerCase().contains("already")) { out.relatedOk.add(relNum); }
                        else out.relatedFailed.add(failEntry(relNum, d));
                    }
                }
                out.stepsOk.add("addRelationships=" + out.relatedOk.size());
            } catch (Throwable t) { log.note("relationships table failed (non-fatal): " + describeError(t)); }

            // 6) Auto-submit one step (default next status). Non-fatal.
            try {
                IStatus next = ecn.getDefaultNextStatus();
                if (next != null) {
                    ecn.changeStatus(next, true, "", true, true, null, new IUser[0], null, false);
                    out.submitted = true;
                    out.stepsOk.add("submit=" + next.getName());
                } else log.note("no default next status; left in Pending");
            } catch (Throwable t) { log.note("auto-submit failed (non-fatal): " + describeError(t)); }

            out.ok = true;
            return out;
        } catch (Throwable t) {
            log.stepFailed("createRestartEcn", t, System.currentTimeMillis() - t0);
            out.errorReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            return out;
        } finally {
            if (userSession != null) { try { userSession.close(); } catch (Exception ignored) {} }
        }
    }

    private static java.util.Map<String,String> failEntry(String ecn, String reason) {
        java.util.Map<String,String> m = new java.util.LinkedHashMap<>();
        m.put("ecn", ecn); m.put("reason", reason == null ? "unknown" : reason);
        return m;
    }
```

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q compile`
Expected: BUILD SUCCESS. If `IStatus`/`getDefaultNextStatus`/`TABLE_RELATIONSHIPS` are unresolved, confirm they exist in the SDK (they are used by `changeStatus`/`DcoRichCreationService` already) and that `com.agile.api.*` is imported.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/agile/service/AgileWriteBackService.java
git commit -m "feat(restart-ecn): createRestartEcn — per-user create, cells, relationships, auto-submit"
```

---

### Task A4: Add the controller endpoint

**Files:**
- Modify: `src/main/java/com/sandisk/plm/agile/controller/AgileWriteBackController.java`

Reference: the existing `createDrr` handler (lines 199–220) and `orNew`/`withCorr` helpers.

- [ ] **Step 1: Add the endpoint** (place next to `createDrr`)

```java
    @PostMapping(value = "/change/create-restart-ecn", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createRestartEcn(@RequestBody CreateRestartEcnRequest req,
                                              @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        AgileWriteBackLogger log = new AgileWriteBackLogger(orNew(corrId), "create-restart-ecn");
        try {
            if (req == null) req = new CreateRestartEcnRequest();
            CreateRestartEcnResponse r = service.createRestartEcn(req, log);
            return r.ok ? ResponseEntity.ok(withCorr(r, log))
                        : ResponseEntity.status(422).body(withCorr(r, log));
        } finally {
            log.summary();
        }
    }
```

> Ensure `CreateRestartEcnRequest`/`CreateRestartEcnResponse` are imported (the class already imports `WriteBackModels.*` if it uses `CreateDrrRequest`; if it imports them individually, add the two new ones).

- [ ] **Step 2: Compile**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q clean package`
Expected: BUILD SUCCESS, jar produced under `target/`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/agile/controller/AgileWriteBackController.java
git commit -m "feat(restart-ecn): POST /api/change/create-restart-ecn endpoint"
```

---

### Task A5: Add config to `application.properties.template`

**Files:**
- Modify: `src/main/resources/application.properties.template`

- [ ] **Step 1: Append the block**

```properties
# --- Restart ECN (deployment ECN) ---
agile.restartEcn.subclass=ECN
agile.restartEcn.workflow=ECR - Workflow
agile.restartEcn.requestClassification=Restart Agile Service
agile.restartEcn.category=NA
agile.restartEcn.disposition=N/A
agile.restartEcn.productLine=N/A
agile.restartEcn.priority=Standard
agile.restartEcn.priorityLevel=3-Low
agile.restartEcn.analystLogin=administrator
# Cover-page cell base IDs — CONFIRM ON QA
agile.restartEcn.cell.requestClassification=1564
agile.restartEcn.cell.category=1561
agile.restartEcn.cell.disposition=1562
agile.restartEcn.cell.productLine=1563
agile.restartEcn.cell.priorityLevel=1794
agile.restartEcn.cell.proposal=1052
agile.restartEcn.cell.problemStatement=251747661
agile.restartEcn.cell.itOwnerName=Page Three.Assigned IT Owner
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.properties.template
git commit -m "config(restart-ecn): agile.restartEcn.* defaults (cell ids TBD on QA)"
```

> On the QA server, mirror these into the live gitignored `application.properties` before restarting the service.

---

## Phase B — plm-field-tracker backend (TDD)

> Verify with `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q test`.

### Task B1: `LineSummarizer` seam + fail-soft Portkey impl

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/LineSummarizer.java`
- Create: `src/main/java/com/sandisk/plm/tracker/service/PortkeyLineSummarizer.java`

- [ ] **Step 1: Create the interface**

```java
package com.sandisk.plm.tracker.service;

/** Condenses an ECN description to a single short line. Returns null/blank on
 *  failure so callers can fall back to the raw text. */
@FunctionalInterface
public interface LineSummarizer {
    String summarize(String ecnNumber, String description);
}
```

- [ ] **Step 2: Create the Portkey-backed impl**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/** Default {@link LineSummarizer} using the Portkey LLM gateway. Fail-soft:
 *  returns null when Portkey is disabled/unconfigured or the call errors. */
@Component
public class PortkeyLineSummarizer implements LineSummarizer {

    private static final Logger LOG = Logger.getLogger(PortkeyLineSummarizer.class.getName());

    @Autowired private PortkeyClient portkeyClient;
    @Value("${portkey.enabled:false}")       private boolean portkeyEnabled;
    @Value("${portkey.api-key:}")            private String portkeyApiKey;
    @Value("${portkey.provider:@anthropic-eastus2}") private String portkeyProvider;
    @Value("${portkey.model:claude-sonnet-4-6}")     private String portkeyModel;

    @Override
    public String summarize(String ecnNumber, String description) {
        if (description == null || description.trim().isEmpty()) return null;
        if (!portkeyEnabled || portkeyApiKey == null || portkeyApiKey.isEmpty()) return null;
        try {
            String system = "You condense Agile PLM ECN descriptions into a single, plain, "
                    + "under-18-word summary line. No preamble, no trailing period, no markdown.";
            String user = "Condense this ECN description to one short line:\n\n" + description;
            String model = portkeyProvider + "/" + portkeyModel;
            String out = portkeyClient.chat(model, system, user, 80);
            if (out == null) return null;
            out = out.trim().replaceAll("\\s+", " ");
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            LOG.warning("[RESTART-ECN] summarize failed for " + ecnNumber + ": " + e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/LineSummarizer.java \
        src/main/java/com/sandisk/plm/tracker/service/PortkeyLineSummarizer.java
git commit -m "feat(restart-ecn): LineSummarizer seam + Portkey fail-soft impl"
```

---

### Task B2: `RestartEcnProposalBuilder` (TDD)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/RestartEcnProposalBuilder.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RestartEcnProposalBuilderTest.java`

The builder takes the deploy date + a list of `(ecn, description)` and produces the Proposal and Problem Statement strings. AI is behind `LineSummarizer`; on null it falls back to a truncated raw description.

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RestartEcnProposalBuilderTest {

    private RestartEcnProposalBuilder builder(LineSummarizer s) {
        RestartEcnProposalBuilder b = new RestartEcnProposalBuilder();
        b.summarizer = s;
        b.proposalHeader = "Deployment {date}:";
        b.problemHeader = "Deploying ECNs:";
        return b;
    }

    private Map<String,String> descs() {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("ECN-134394-PROJ", "Apple Audit & Automation: gap in automation implemented via ECN-122260.");
        m.put("ECN-136664-PROJ", "Single/Sole Source: update rules to retain manual Multi Source assignment.");
        return m;
    }

    @Test
    public void proposalUsesAiSummaryPerEcnWithDateHeader() {
        LineSummarizer ai = (ecn, d) -> "AI[" + ecn + "]";
        RestartEcnProposalBuilder.Built out =
                builder(ai).build(LocalDate.of(2026, 7, 4), descs());
        assertEquals(
            "Deployment 07/04/26:\n"
          + "• ECN-134394-PROJ: AI[ECN-134394-PROJ]\n"
          + "• ECN-136664-PROJ: AI[ECN-136664-PROJ]",
            out.proposal);
    }

    @Test
    public void problemStatementListsEcnNumbers() {
        RestartEcnProposalBuilder.Built out =
                builder((e, d) -> "x").build(LocalDate.of(2026, 7, 4), descs());
        assertEquals("Deploying ECNs:\nECN-134394-PROJ, ECN-136664-PROJ", out.problemStatement);
    }

    @Test
    public void fallsBackToTruncatedDescriptionWhenAiReturnsNull() {
        LineSummarizer ai = (ecn, d) -> null;   // AI unavailable
        Map<String,String> one = new LinkedHashMap<>();
        String longDesc = "This is a long description that should be truncated to a bounded length "
                + "so the proposal never becomes enormous even without any AI summary at all here.";
        one.put("ECN-1", longDesc);
        RestartEcnProposalBuilder.Built out =
                builder(ai).build(LocalDate.of(2026, 1, 2), one);
        assertTrue(out.proposal.startsWith("Deployment 01/02/26:\n• ECN-1: This is a long description"));
        // Bounded (header + bullet), and never the full untruncated text.
        assertTrue(out.proposal.length() < longDesc.length() + 40);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RestartEcnProposalBuilderTest test`
Expected: FAIL/compile error — `RestartEcnProposalBuilder` does not exist.

- [ ] **Step 3: Implement the builder**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Builds the Restart ECN Proposal (date header + one AI-condensed bullet per
 *  related ECN) and Problem Statement ("Deploying ECNs:" + numbers). */
@Service
public class RestartEcnProposalBuilder {

    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final int FALLBACK_MAX = 120;

    @Autowired LineSummarizer summarizer;
    @Value("${restartEcn.proposalHeader:Deployment {date}:}") String proposalHeader;
    @Value("${restartEcn.problemStatementHeader:Deploying ECNs:}") String problemHeader;

    public static final class Built {
        public final String proposal;
        public final String problemStatement;
        Built(String p, String ps) { this.proposal = p; this.problemStatement = ps; }
    }

    /** @param descriptions ordered map of ECN number -> raw description. */
    public Built build(LocalDate deployDate, Map<String, String> descriptions) {
        String date = deployDate.format(MDY);
        StringBuilder proposal = new StringBuilder(proposalHeader.replace("{date}", date));
        StringBuilder nums = new StringBuilder();
        for (Map.Entry<String, String> e : descriptions.entrySet()) {
            String ecn = e.getKey();
            String line = summarizer.summarize(ecn, e.getValue());
            if (line == null || line.trim().isEmpty()) line = truncate(e.getValue());
            proposal.append("\n• ").append(ecn).append(": ").append(line);
            if (nums.length() > 0) nums.append(", ");
            nums.append(ecn);
        }
        String problem = problemHeader + "\n" + nums;
        return new Built(proposal.toString(), problem);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() <= FALLBACK_MAX ? s : s.substring(0, FALLBACK_MAX).trim() + "…";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RestartEcnProposalBuilderTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RestartEcnProposalBuilder.java \
        src/test/java/com/sandisk/plm/tracker/service/RestartEcnProposalBuilderTest.java
git commit -m "feat(restart-ecn): proposal/problem-statement builder with AI fail-soft (TDD)"
```

---

### Task B3: `AgileWriteBackClient.createRestartEcn`

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java`

Reference: `createDrr` (lines 208–218), `postJson` (lines 252–271), `orNew`.

- [ ] **Step 1: Add the method** (place next to `createDrr`)

```java
    /** Create a Restart (deployment) ECN under the acting user's Agile session.
     *  Body carries {ok, ecnNumber, submitted, relatedOk, relatedFailed, ...}. */
    public Result createRestartEcn(String asUsername, String asPassword,
                                   String proposalText, String problemStatement,
                                   String itOwnerLogin, java.util.List<String> relatedEcns,
                                   String corrId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("asUsername", asUsername);
        body.put("asPassword", asPassword);           // never logged
        body.put("proposalText", proposalText == null ? "" : proposalText);
        body.put("problemStatement", problemStatement == null ? "" : problemStatement);
        body.put("itOwnerLogin", itOwnerLogin);
        body.put("relatedEcns", relatedEcns == null ? java.util.Collections.emptyList() : relatedEcns);
        return postJson("/api/change/create-restart-ecn", body, orNew(corrId), "create-restart-ecn");
    }
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java
git commit -m "feat(restart-ecn): AgileWriteBackClient.createRestartEcn"
```

---

### Task B4: `RestartEcnService` — candidates + re-validation (TDD)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/RestartEcnService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/RestartEcnServiceTest.java`

Pure logic (no SDK): filter cached rows to eligible candidates, and re-validate a create request. `ItEnhancementsService` and its `Row` are the inputs; we pass in a `List<Row>` and the IT-owner roster so the service is unit-testable without Spring.

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.ItEnhancementsService.Row;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class RestartEcnServiceTest {

    private static final String ELIGIBLE = "UAT complete, CAB Prep";

    private Row row(String ecn, String status, String ownerLogin, String ownerName, String proposal) {
        Row r = new Row();
        r.ecnNumber = ecn; r.status = status;
        r.itOwnerLoginId = ownerLogin; r.itOwner = ownerName; r.proposal = proposal;
        return r;
    }

    private RestartEcnService svc() {
        RestartEcnService s = new RestartEcnService();
        s.eligibleItStatus = ELIGIBLE;
        return s;
    }

    @Test
    public void candidatesKeepOnlyEligibleItStatus_caseInsensitive() {
        List<Row> rows = Arrays.asList(
                row("ECN-1", "UAT COMPLETE, CAB PREP", "a", "Alice", "d1"),
                row("ECN-2", "WIP", "b", "Bob", "d2"),
                row("ECN-3", ELIGIBLE, "c", "Carol", "d3"));
        List<RestartEcnService.Candidate> c = svc().candidates(rows);
        assertEquals(2, c.size());
        assertEquals("ECN-1", c.get(0).ecn);
        assertEquals("ECN-3", c.get(1).ecn);
    }

    @Test
    public void revalidateDropsIneligibleEcnAndReportsIt() {
        List<Row> rows = Arrays.asList(
                row("ECN-1", ELIGIBLE, "a", "Alice", "d1"),
                row("ECN-2", "WIP", "b", "Bob", "d2"));
        RestartEcnService.Validated v =
                svc().revalidate(rows, Arrays.asList("ECN-1", "ECN-2"), "z");
        assertTrue(v.ok);
        assertEquals(Arrays.asList("ECN-1"), v.acceptedEcns);
        assertEquals(Arrays.asList("ECN-2"), v.droppedEcns);
    }

    @Test
    public void revalidateFailsWhenNoEligibleEcnRemains() {
        List<Row> rows = Arrays.asList(row("ECN-2", "WIP", "b", "Bob", "d2"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-2"), "z");
        assertFalse(v.ok);
        assertNotNull(v.error);
    }

    @Test
    public void revalidateRejectsOwnerWhoOwnsARelatedEcn() {
        List<Row> rows = Arrays.asList(row("ECN-1", ELIGIBLE, "alice", "Alice", "d1"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-1"), "alice");
        assertFalse(v.ok);
        assertTrue(v.error.toLowerCase().contains("owner"));
    }

    @Test
    public void revalidateAcceptsOwnerWhoOwnsNoRelatedEcn() {
        List<Row> rows = Arrays.asList(row("ECN-1", ELIGIBLE, "alice", "Alice", "d1"));
        RestartEcnService.Validated v = svc().revalidate(rows, Arrays.asList("ECN-1"), "dave");
        assertTrue(v.ok);
        assertEquals("dave", v.itOwnerLogin);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RestartEcnServiceTest test`
Expected: FAIL/compile error — `RestartEcnService` does not exist.

- [ ] **Step 3: Implement the service**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.ItEnhancementsService.Row;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Eligibility + re-validation for Restart ECN creation. No SDK — pure logic
 *  over the cached IT-Enhancements rows. */
@Service
public class RestartEcnService {

    @Value("${restartEcn.eligibleItStatus:UAT complete, CAB Prep}") String eligibleItStatus;

    public static final class Candidate {
        public String ecn, proposal, itOwner, itOwnerLoginId, workflowStatus;
    }

    public static final class Validated {
        public boolean ok;
        public String error;
        public String itOwnerLogin;
        public List<String> acceptedEcns = new ArrayList<>();
        public List<String> droppedEcns = new ArrayList<>();
    }

    private boolean eligible(Row r) {
        return r != null && r.status != null
                && r.status.trim().equalsIgnoreCase(eligibleItStatus.trim());
    }

    public List<Candidate> candidates(List<Row> rows) {
        List<Candidate> out = new ArrayList<>();
        if (rows == null) return out;
        for (Row r : rows) {
            if (!eligible(r)) continue;
            Candidate c = new Candidate();
            c.ecn = r.ecnNumber; c.proposal = r.proposal;
            c.itOwner = r.itOwner; c.itOwnerLoginId = r.itOwnerLoginId;
            c.workflowStatus = r.workflowStatus;
            out.add(c);
        }
        return out;
    }

    /** Re-check submitted ECNs are still eligible (drop + report the rest) and
     *  that the chosen owner owns none of the accepted ECNs. */
    public Validated revalidate(List<Row> rows, List<String> requestedEcns, String itOwnerLogin) {
        Validated v = new Validated();
        v.itOwnerLogin = itOwnerLogin;
        java.util.Set<String> eligibleSet = new java.util.HashSet<>();
        java.util.Map<String, String> ownerByEcn = new java.util.HashMap<>();
        if (rows != null) for (Row r : rows) {
            if (eligible(r)) {
                eligibleSet.add(r.ecnNumber);
                ownerByEcn.put(r.ecnNumber, lc(r.itOwnerLoginId));
            }
        }
        if (requestedEcns != null) for (String e : requestedEcns) {
            if (e == null) continue;
            String ecn = e.trim();
            if (eligibleSet.contains(ecn)) v.acceptedEcns.add(ecn);
            else v.droppedEcns.add(ecn);
        }
        if (v.acceptedEcns.isEmpty()) {
            v.ok = false;
            v.error = "No eligible ECNs remain (all were dropped as not '" + eligibleItStatus + "').";
            return v;
        }
        if (itOwnerLogin == null || itOwnerLogin.trim().isEmpty()) {
            v.ok = false; v.error = "IT Owner is required."; return v;
        }
        String owner = lc(itOwnerLogin);
        for (String ecn : v.acceptedEcns) {
            if (owner.equals(ownerByEcn.get(ecn))) {
                v.ok = false;
                v.error = "IT Owner '" + itOwnerLogin + "' owns related ECN " + ecn
                        + " — pick an IT owner who does not own any bundled ECN.";
                return v;
            }
        }
        v.ok = true;
        return v;
    }

    private static String lc(String s) { return s == null ? "" : s.trim().toLowerCase(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RestartEcnServiceTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/RestartEcnService.java \
        src/test/java/com/sandisk/plm/tracker/service/RestartEcnServiceTest.java
git commit -m "feat(restart-ecn): eligibility + server-side re-validation service (TDD)"
```

---

### Task B5: Controller endpoints (`/restart-ecn-candidates`, `/create-restart-ecn`)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java`

Reference: `save-cell` (auth gate, cached `agilePassword`, `needsAgileSignin`, `ActivityLogger`), and `service.readAll()` / `service.getCellOptions()`.

- [ ] **Step 1: Add field injections** (with the other `@Autowired` fields at the class top)

```java
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnService restartEcnService;
    @Autowired private com.sandisk.plm.tracker.service.RestartEcnProposalBuilder proposalBuilder;
```

- [ ] **Step 2: Add the candidates endpoint**

```java
    /** Eligible enhancement ECNs (IT Status = eligible value) + the IT-owner roster
     *  (Agile Assigned-IT-Owner list values) for the Restart ECN dialog. */
    @GetMapping("/restart-ecn-candidates")
    public ResponseEntity<?> restartEcnCandidates(HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();
        List<com.sandisk.plm.tracker.service.RestartEcnService.Candidate> cands =
                restartEcnService.candidates(service.readAll());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.sandisk.plm.tracker.service.RestartEcnService.Candidate c : cands) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ecn", c.ecn); m.put("proposal", c.proposal);
            m.put("itOwner", c.itOwner); m.put("itOwnerLoginId", c.itOwnerLoginId);
            m.put("workflowStatus", c.workflowStatus);
            out.add(m);
        }
        resp.put("candidates", out);
        List<String> owners = service.getCellOptions().get("Page Three.Assigned IT Owner");
        resp.put("itOwnerRoster", owners == null ? java.util.Collections.emptyList() : owners);
        return ResponseEntity.ok(resp);
    }
```

- [ ] **Step 3: Add the create endpoint**

```java
    @PostMapping("/create-restart-ecn")
    public ResponseEntity<?> createRestartEcn(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!authorized(session)) return forbidden();
        Map<String, Object> resp = new LinkedHashMap<>();

        String deployDateIso = body == null ? null : (String) body.get("deployDate"); // yyyy-MM-dd
        String itOwnerLogin  = body == null ? null : (String) body.get("itOwnerLogin");
        @SuppressWarnings("unchecked")
        List<String> relatedEcns = body == null ? null : (List<String>) body.get("relatedEcns");

        if (deployDateIso == null || deployDateIso.trim().isEmpty()) {
            resp.put("ok", false); resp.put("error", "deployDate required"); return ResponseEntity.ok(resp);
        }
        java.time.LocalDate deployDate;
        try { deployDate = java.time.LocalDate.parse(deployDateIso.trim()); }
        catch (Exception e) { resp.put("ok", false); resp.put("error", "bad deployDate: " + deployDateIso); return ResponseEntity.ok(resp); }

        // Per-user Agile creds (same gate as save-cell).
        String username = (String) session.getAttribute("username");
        String agilePassword = (String) session.getAttribute("agilePassword");
        if (agilePassword == null || agilePassword.isEmpty()) {
            resp.put("ok", false); resp.put("needsAgileSignin", true);
            resp.put("error", "Sign in to Agile to create the Restart ECN.");
            return ResponseEntity.ok(resp);
        }

        List<ItEnhancementsService.Row> rows = service.readAll();
        com.sandisk.plm.tracker.service.RestartEcnService.Validated v =
                restartEcnService.revalidate(rows, relatedEcns, itOwnerLogin);
        if (!v.ok) { resp.put("ok", false); resp.put("error", v.error); return ResponseEntity.ok(resp); }

        // Build Proposal/Problem Statement from the ACCEPTED ECNs' descriptions.
        java.util.Map<String, String> descs = new LinkedHashMap<>();
        for (ItEnhancementsService.Row r : rows) {
            if (v.acceptedEcns.contains(r.ecnNumber)) descs.put(r.ecnNumber, r.proposal);
        }
        com.sandisk.plm.tracker.service.RestartEcnProposalBuilder.Built built =
                proposalBuilder.build(deployDate, descs);

        String corrId = UUID.randomUUID().toString();
        AgileWriteBackClient.Result r = agileWriteBackClient.createRestartEcn(
                username, agilePassword, built.proposal, built.problemStatement,
                v.itOwnerLogin, v.acceptedEcns, corrId);

        resp.put("ok", r.ok);
        resp.put("corrId", corrId);
        resp.put("droppedEcns", v.droppedEcns);
        if (r.ok && r.body != null) {
            resp.put("ecnNumber", r.body.get("ecnNumber"));
            resp.put("submitted", r.body.get("submitted"));
            resp.put("relatedOk", r.body.get("relatedOk"));
            resp.put("relatedFailed", r.body.get("relatedFailed"));
            activityLogger.log(username, (String) session.getAttribute("displayName"),
                    "IT_ENH_CREATE_RESTART_ECN",
                    r.body.get("ecnNumber") + " · related " + v.acceptedEcns.size()
                            + " · owner " + v.itOwnerLogin);
        } else {
            String err = r.errorReason != null ? r.errorReason
                    : (r.body != null && r.body.get("errorReason") != null ? r.body.get("errorReason").toString() : "create failed");
            resp.put("error", err);
            if (isInvalidCredsError(err)) { session.removeAttribute("agilePassword"); resp.put("needsAgileSignin", true); }
        }
        return ResponseEntity.ok(resp);
    }
```

- [ ] **Step 4: Compile + run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS; B2 + B4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/ItEnhancementsController.java
git commit -m "feat(restart-ecn): candidates + create-restart-ecn controller endpoints"
```

---

### Task B6: Toolkit config keys

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Append**

```properties
# --- Restart ECN (IT Enhancements) ---
restartEcn.eligibleItStatus=UAT complete, CAB Prep
restartEcn.proposalHeader=Deployment {date}:
restartEcn.problemStatementHeader=Deploying ECNs:
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "config(restart-ecn): eligible status + proposal/problem headers"
```

---

## Phase C — Frontend (`it-enhancements.js` + `index.html`)

> No JS test harness in the repo — verify by exercising the running app locally (`~/Documents/plm-toolkit 2`, `:8090`) after Phase D's build. These tasks are code + manual verification.

### Task C1: Toolbar button

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Add the button** next to `iteForceRefreshBtn` (search for `iteForceRefreshBtn` in `index.html`)

```html
<button id="iteRestartEcnBtn" onclick="openRestartEcnDialog()" title="Create a Restart (deployment) ECN bundling UAT-complete/CAB-Prep enhancements" style="padding:7px 14px; font-size:13px; background:#fff; border:1px solid #E8E6DF; border-radius:4px; cursor:pointer;">Create Restart ECN</button>
```

- [ ] **Step 2: Commit** (bump happens in Task C4)

```bash
git add src/main/resources/static/index.html
git commit -m "feat(restart-ecn): toolbar button in IT Enhancements"
```

---

### Task C2: The create dialog (date + ECN checklist + IT owner)

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js`

Build one function `openRestartEcnDialog()` modeled on `openMultiListPopover` (same `ite2-modal-back` backdrop + inline styling). It fetches candidates, renders the form, and wires dynamic owner exclusion.

- [ ] **Step 1: Add the dialog code** (place near `openMultiListPopover`)

```javascript
    // ---- Restart ECN creation dialog ----
    function fmtMDY(iso) {                       // "2026-07-04" -> "07/04/26"
        var p = (iso || '').split('-');
        return p.length === 3 ? (p[1] + '/' + p[2] + '/' + p[0].slice(2)) : '';
    }
    function todayIso() {
        var d = new Date(), z = function (n) { return (n < 10 ? '0' : '') + n; };
        return d.getFullYear() + '-' + z(d.getMonth() + 1) + '-' + z(d.getDate());
    }

    function openRestartEcnDialog() {
        fetch('/api/it-enhancements/restart-ecn-candidates', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) { renderRestartEcnDialog(data); })
            .catch(function () { showToast({ kind: 'error', text: 'Could not load eligible ECNs.' }); });
    }

    function renderRestartEcnDialog(data) {
        var candidates = (data && data.candidates) || [];
        var roster = (data && data.itOwnerRoster) || [];
        if (!candidates.length) {
            showToast({ kind: 'warn', text: 'No ECNs at "UAT complete, CAB Prep" to bundle.' });
            return;
        }
        var selected = {};                       // ecn -> true
        var chosenOwner = '';

        var back = document.createElement('div');
        back.className = 'ite2-modal-back';
        back.id = 'iteRestartBack';
        var pop = document.createElement('div');
        pop.style.cssText = 'background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:18px 22px; min-width:520px; max-width:640px; max-height:86vh; display:flex; flex-direction:column; box-shadow:0 12px 40px rgba(0,0,0,0.25); font-family:inherit;';
        pop.innerHTML =
            '<div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Agile PLM &middot; IT Enhancements</div>' +
            '<div style="font-family:Georgia,serif; font-size:20px; margin:4px 0 12px;">Create Restart ECN</div>' +
            '<label style="font-size:12px; color:#6B7280;">Deployment date</label>' +
            '<input type="date" id="reDate" value="' + todayIso() + '" style="margin:4px 0 4px; padding:6px 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:13px;">' +
            '<div id="rePreview" style="font-family:Georgia,serif; font-size:13px; color:#0F1720; margin-bottom:12px;"></div>' +
            '<label style="font-size:12px; color:#6B7280;">Bundle these enhancement ECNs (UAT complete, CAB Prep)</label>' +
            '<div style="margin:4px 0 4px;"><button type="button" id="reSelAll" style="background:transparent; border:1px solid #E8E6DF; padding:3px 10px; border-radius:12px; font-size:11px; cursor:pointer;">Select all</button> <span id="reCount" style="color:#6B7280; font-size:11px;">0 selected</span></div>' +
            '<div id="reList" style="overflow-y:auto; border:1px solid #E8E6DF; border-radius:4px; padding:4px; max-height:30vh; background:#FAFAF7;"></div>' +
            '<label style="font-size:12px; color:#6B7280; margin-top:12px;">IT Owner (PLM IT team, not an owner of a bundled ECN)</label>' +
            '<select id="reOwner" style="margin:4px 0 4px; padding:6px 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:13px;"></select>' +
            '<div id="reOwnerMsg" style="color:#C7801B; font-size:11px; min-height:14px;"></div>' +
            '<div style="display:flex; justify-content:flex-end; margin-top:14px;">' +
                '<button type="button" id="reCancel" style="background:transparent; border:1px solid #E8E6DF; padding:6px 12px; border-radius:4px; font-size:12px; cursor:pointer; margin-right:6px;">Cancel</button>' +
                '<button type="button" id="reCreate" disabled style="background:#4a6fa5; color:#fff; border:1px solid #4a6fa5; padding:6px 14px; border-radius:4px; font-size:12px; cursor:pointer; font-weight:600; opacity:0.5;">Create</button>' +
            '</div>';
        back.appendChild(pop);
        document.body.appendChild(back);

        var dateEl = pop.querySelector('#reDate');
        var previewEl = pop.querySelector('#rePreview');
        var listEl = pop.querySelector('#reList');
        var countEl = pop.querySelector('#reCount');
        var ownerEl = pop.querySelector('#reOwner');
        var ownerMsg = pop.querySelector('#reOwnerMsg');
        var createBtn = pop.querySelector('#reCreate');

        function excludedOwnerLogins() {         // logins of selected ECNs' owners
            var set = {};
            candidates.forEach(function (c) {
                if (selected[c.ecn] && c.itOwnerLoginId) set[c.itOwnerLoginId.toLowerCase()] = c.itOwner || c.itOwnerLoginId;
            });
            return set;
        }
        function rebuildOwners() {
            var excl = excludedOwnerLogins();
            // Roster values are display names ("Last, First"); exclude by matching
            // the display name of any selected ECN's owner (loginId not in roster).
            var exclNames = {};
            Object.keys(excl).forEach(function (k) { exclNames[(excl[k] || '').trim().toLowerCase()] = true; });
            var prev = ownerEl.value;
            var opts = ['<option value="">— choose IT owner —</option>'];
            roster.forEach(function (name) {
                if (exclNames[(name || '').trim().toLowerCase()]) return;
                opts.push('<option value="' + escapeAttr(name) + '">' + escapeHtml(name) + '</option>');
            });
            ownerEl.innerHTML = opts.join('');
            if (prev && ownerEl.querySelector('option[value="' + cssEscape(prev) + '"]')) ownerEl.value = prev;
            else { ownerEl.value = ''; chosenOwner = ''; }
            var remaining = ownerEl.options.length - 1;
            ownerMsg.textContent = remaining === 0
                ? 'No eligible IT owner — every PLM IT member owns a selected ECN.' : '';
            refreshCreate();
        }
        function refreshCreate() {
            var n = Object.keys(selected).filter(function (k) { return selected[k]; }).length;
            countEl.textContent = n + ' selected';
            var ok = dateEl.value && n > 0 && ownerEl.value;
            createBtn.disabled = !ok;
            createBtn.style.opacity = ok ? '1' : '0.5';
        }
        function updatePreview() { previewEl.textContent = dateEl.value ? ('Deployment ' + fmtMDY(dateEl.value) + ':') : ''; }

        // Render the checklist.
        listEl.innerHTML = candidates.map(function (c) {
            var sub = (c.proposal || '').slice(0, 80);
            return '<label style="display:block; padding:5px 8px; cursor:pointer; border-radius:3px;">' +
                '<input type="checkbox" data-ecn="' + escapeAttr(c.ecn) + '" style="margin-right:8px; vertical-align:middle;">' +
                '<b>' + escapeHtml(c.ecn) + '</b> <span style="color:#6B7280; font-size:11px;">' +
                escapeHtml(c.itOwner || '') + '</span><br>' +
                '<span style="color:#6B7280; font-size:11px; margin-left:24px;">' + escapeHtml(sub) + '</span></label>';
        }).join('');
        Array.prototype.forEach.call(listEl.querySelectorAll('input[type="checkbox"]'), function (cb) {
            cb.addEventListener('change', function () {
                var e = cb.getAttribute('data-ecn');
                if (cb.checked) selected[e] = true; else delete selected[e];
                rebuildOwners();
            });
        });

        pop.querySelector('#reSelAll').addEventListener('click', function () {
            Array.prototype.forEach.call(listEl.querySelectorAll('input[type="checkbox"]'), function (cb) {
                cb.checked = true; selected[cb.getAttribute('data-ecn')] = true;
            });
            rebuildOwners();
        });
        dateEl.addEventListener('input', function () { updatePreview(); refreshCreate(); });
        ownerEl.addEventListener('change', function () { chosenOwner = ownerEl.value; refreshCreate(); });
        pop.querySelector('#reCancel').addEventListener('click', function () { closeRestartEcnDialog(); });
        back.addEventListener('mousedown', function (ev) { if (ev.target === back) closeRestartEcnDialog(); });
        createBtn.addEventListener('click', function () {
            submitRestartEcn({
                deployDate: dateEl.value,
                itOwnerLogin: ownerEl.value,       // display name; server matches roster/login
                relatedEcns: Object.keys(selected).filter(function (k) { return selected[k]; })
            }, createBtn);
        });

        updatePreview(); rebuildOwners(); refreshCreate();
        RESTART_BACK = back;
    }

    var RESTART_BACK = null;
    function closeRestartEcnDialog() { if (RESTART_BACK) { RESTART_BACK.remove(); RESTART_BACK = null; } }
    function cssEscape(s) { return String(s).replace(/["\\]/g, '\\$&'); }
```

> `escapeHtml`/`escapeAttr` already exist in this file (used by `openMultiListPopover`). `cssEscape` is a tiny local helper added here.

> **IT Owner login vs display name:** the roster values are Agile Assigned-IT-Owner **display names** ("Last, First"). The dialog sends the display name as `itOwnerLogin`. The agile-service resolves an `IUser` by login. **Confirm on QA** whether `session.getObject(IUser.OBJECT_TYPE, name)` accepts the display name; if not, extend `/restart-ecn-candidates` to also return a name→login map and send the login instead. Flag this in the QA handoff.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/it-enhancements.js
git commit -m "feat(restart-ecn): create dialog (date + ECN checklist + dynamic IT owner)"
```

---

### Task C3: Submit handler (with Agile sign-in fallback + toast)

**Files:**
- Modify: `src/main/resources/static/it-enhancements.js`

Reuse the existing per-user Agile sign-in flow. This file already opens a sign-in modal for `save-cell` (`openAgileSigninModal`). Reuse it: on `needsAgileSignin`, prompt, then retry.

- [ ] **Step 1: Add the submit function**

```javascript
    function submitRestartEcn(payload, btn) {
        if (btn) { btn.disabled = true; btn.style.opacity = '0.5'; }
        showToast({ kind: 'pending', text: 'Creating Restart ECN…' });
        fetch('/api/it-enhancements/create-restart-ecn', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(function (r) { return r.json(); })
        .then(function (res) {
            if (res && res.needsAgileSignin) {
                // Prompt for Agile creds, then retry once.
                openAgileSigninModal(function () { submitRestartEcn(payload, btn); });
                return;
            }
            if (!res || !res.ok) {
                showToast({ kind: 'error', text: (res && res.error) ? res.error : 'Create failed.' });
                if (btn) { btn.disabled = false; btn.style.opacity = '1'; }
                return;
            }
            closeRestartEcnDialog();
            var relOk = (res.relatedOk || []).length;
            var relBad = (res.relatedFailed || []).length;
            var dropped = (res.droppedEcns || []).length;
            var msg = 'Created ' + res.ecnNumber + ' · related ' + relOk
                    + (res.submitted ? ' · submitted' : ' · left in Pending');
            var kind = 'success';
            if (relBad || dropped || !res.submitted) {
                kind = 'warn';
                if (relBad) msg += ' · ' + relBad + ' relationship(s) failed';
                if (dropped) msg += ' · ' + dropped + ' dropped (no longer eligible)';
            }
            showToast({ kind: kind, text: msg });
        })
        .catch(function () {
            showToast({ kind: 'error', text: 'Create failed (network).' });
            if (btn) { btn.disabled = false; btn.style.opacity = '1'; }
        });
    }
```

> **Verify the sign-in modal signature.** This file already has an Agile sign-in modal used by `save-cell` (referenced as `openAgileSigninModal(edits)` in the tab). Before wiring, open `it-enhancements.js`, find that function, and confirm it accepts a success callback. If its current signature is `openAgileSigninModal(edits)` that re-runs a save, **generalize it** to accept an `onSuccess` callback (add an optional 2nd param invoked after a verified sign-in) and pass `submitRestartEcn`'s retry as that callback. Keep the existing `save-cell` call sites working. Commit that refactor as its own step if needed.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/it-enhancements.js
git commit -m "feat(restart-ecn): submit handler with Agile sign-in retry + result toast"
```

---

### Task C4: Cache-bust the JS

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Bump the version** — find `it-enhancements.js?v=` and set today's stamp

```html
<script src="it-enhancements.js?v=20260701a"></script>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "chore(restart-ecn): cache-bust it-enhancements.js"
```

---

## Phase D — What's New, build, stage

### Task D1: What's New entry (REQUIRED before build per CLAUDE.md)

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a new entry at the TOP of `WHATS_NEW_RELEASES`**

```javascript
  {
    date: '2026-07-01',
    title: 'Create Restart ECN from IT Enhancements',
    items: [
      { type: 'new', text: 'New "Create Restart ECN" button bundles UAT-complete / CAB-Prep enhancement ECNs into a deployment ECN in Agile — pick a deploy date, the ECNs to include, and an IT owner; the ECN is created under your Agile sign-in, related, and auto-submitted.' },
      { type: 'new', text: 'Proposal is auto-written as "Deployment <date>:" with an AI-condensed one-line summary per bundled ECN; Problem Statement lists the deployed ECNs.' }
    ]
  },
```

> Match the exact object shape already used in `WHATS_NEW_RELEASES` — open the file and mirror the existing entries' keys (`date`/`title`/`items` with `type`/`text`) precisely; adjust if the real shape differs.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): Create Restart ECN release entry"
```

---

### Task D2: Build both JARs

- [ ] **Step 1: Build agile-service**

Run: `cd /Users/vikasjindal/git/plm-agile-service && mvn -q clean package`
Expected: BUILD SUCCESS, `target/plm-agile-service-1.0.0.jar`.

- [ ] **Step 2: Build toolkit**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q clean package`
Expected: BUILD SUCCESS + all tests green, `target/plm-field-tracker-1.0.1.jar`.

---

### Task D3: Stage (never live) + local smoke copy

Per CLAUDE.md: copy to `staging/` on the prod share and to the local setup; verify sizes. **If a share isn't mounted, report it — don't skip silently.**

- [ ] **Step 1: Stage the toolkit JAR**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
stat -f "%z" ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar
```

- [ ] **Step 2: Stage the agile-service JAR** to the QA staging area the DRR feature used (QSS `staging/`). Confirm the exact share path with Vikas before copying.

- [ ] **Step 3: QA handoff note.** SDK write is untested on Mac. Vikas: deploy both jars to QA, confirm the `agile.restartEcn.cell.*` base IDs against a real ECN (History tab / cover-page field ids), verify the `IUser` lookup accepts the IT-owner value the dialog sends (name vs login — see Task C2 note), then create one Restart ECN end-to-end and confirm Relationships + auto-submit.

---

## Self-Review

**Spec coverage:**
- R1 create+auto-submit → A3 (steps 5–6). ✓
- R2 date + AI Proposal + Problem Statement → B2, B5. ✓
- R3 per-user Agile session → A3 (PX session), B5 (cached password gate). ✓
- R4 hardcoded constants → A2/A5 config, A3 cover cells. ✓
- R5 eligible ≥1 → B4 candidates/revalidate, C2 gating. ✓
- R6 fail-soft relationships → A3 (step 5), C3 (warn toast). ✓
- R7 IT Owner picker minus related owners → B4 revalidate, C2 rebuildOwners. ✓
- Server-side re-validation (§6) → B4/B5. ✓
- No grid auto-refresh (§4.3) → C3 (no refresh call). ✓

**Placeholder scan:** No "TBD" in code steps. Cell base IDs are explicit config defaults flagged for QA confirmation (a legitimate deferral requiring the live SDK, not a plan gap). Two verify-on-QA notes (owner name-vs-login; sign-in modal signature) name the exact check and the fix.

**Type consistency:** `CreateRestartEcnRequest/Response` fields match across A1/A3/A4/B3. `RestartEcnService.Candidate/Validated` fields match B4 test + B5 usage. `RestartEcnProposalBuilder.Built{proposal,problemStatement}` matches B2 test + B5. `Row` fields (`ecnNumber`, `status`, `itOwner`, `itOwnerLoginId`, `proposal`, `workflowStatus`) match the verified model. Client method arg order matches B3 ↔ B5 call.

---

## Execution Handoff — see end of session
