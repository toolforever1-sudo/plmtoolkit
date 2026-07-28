# IMS Document Review Revamp — Email → Landing → Change-Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Revamp the three IMS Document Review surfaces — the DO notification email (single CTA), the response landing page (4-option picker + IMS Document Details reference panel), and the "Create the change order" DCO form (flatten the 3-step wizard into one scrollable, two-column tabular form with 6 numbered collapsible sections) — per `~/downloads/handoff-package/IMS Review Revamp - Claude Code Handoff.md`.

**Architecture:** This is a **UI restructure** layered on the current working tree (which already contains unrelated in-flight A73/A74/A75 work — attachment download, owner row, clickable info popovers, BU toggles, CID editor). The backend submit contract is **unchanged** (`collectForm()` shape, `validate-dco-form`, `submit-dco`, `dco-form-metadata`, `user-search`, `ad-search`, `token/submit`, `token/attachments` all stay identical). The only backend edit is one new email template variable (`${documentOwner}`). Two items are **explicitly stubbed / deferred** (handoff §7): the section-04 "IMS Document — Edit" write path, and the dedicated OBS obsolete/retire flow.

**Tech Stack:** Static HTML + vanilla JS (no JS test harness in this repo), Spring Boot Java 11 (Corretto 11) backend, IBM Plex font stack, SanDisk palette. Verification is **visual via the local instance** (`~/Documents/plm-toolkit 2/`, http://localhost:8090) using the DCO form's admin preview mode `ims-respond.html?preview=1`, plus reading the rendered email HTML. There are no unit tests for these static assets; "verify it fails / passes" steps below mean *load the page and observe*.

**Source of truth for look-and-feel:** the prototype at `~/downloads/handoff-package/Change Order Revamp.html` + `form-body.jsx`. Open it side-by-side while implementing each section — copy text, colors, and spacing are quoted below but the prototype is authoritative.

---

## Ground rules (read before any task)

1. **Never hardcode LoV-backed values.** Priority, Training Requirement, Business Unit, Product Lines, Subcontractors come from `dco-form-metadata`. The prototype's literal option lists (`Standard/Custom/OEM`, `Yes/No/N/A`, etc.) are *placeholders only*.
2. **`collectForm()` return shape is frozen.** Every key (`priority`, `descriptionOfChange`, `reasonForChange`, `productLines`, `subcontractors`, `trainingRequirement`, `businessUnit`, `changeImpactDisposition`, `changeImpactRows`, `documentOwners`, `approvers`, `observers`, `notifyStakeholders`, `attachmentManifest`) and the `submit-dco` FormData keys (`token`, `username`, `password`, `form`, `file_redline`, `file_final`, `file_email`, `file_other`) must stay byte-identical.
3. **Global functions referenced from `ims-respond.html` must keep their names:** `dcoOpen(info)`, `dcoMaybeClose()`, `dcoClose()`, `dcoSubmit()`, `downloadAttachments(btnId, statusId)`. The wizard-only globals (`dcoStepNext`, `dcoStepBack`, `dcoStepJump`) are removed when the wizard goes — the drawer footer buttons that call them must be removed/rewired in the same task.
4. **Do not commit the pre-existing uncommitted diff** as part of this work unless Vikas asks. Stage only the files this plan changes, and only after he okays a commit. (CLAUDE.md git policy.)
5. **Pre-build changelog:** before any JAR build, add a `whats-new.js` entry (handoff is a user-facing release). Not needed for pure local preview verification, only before a real build/deploy.
6. **No Agile write-back on this Mac** (memory: `feedback_no_agile_writeback_on_this_machine`). Section-04 write path is code-only here; Vikas deploys + tests on the server.

---

## File Structure

| File | Responsibility | Change type |
|---|---|---|
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` | Stamp `${documentOwner}` var for the DO email | Modify (small) |
| `src/main/resources/templates/email/ims-review-do.html` | Single-CTA email + trimmed table | Rewrite body section |
| `src/main/resources/static/ims-respond.html` | Landing-page CSS (reference panel, 4-card picker, OBS, Stuck divider, tabular form, numbered sections) + `renderForm()` rewrite + drawer markup (remove stepper, add footer status) | Rewrite chunks |
| `src/main/resources/static/imsreview-dco-form.js` | Flatten 3-step wizard → single tabular form; 6 numbered collapsible sections; reference panel; section-04 stub; footer status line | Major rewrite of render layer, **keep** state + collectForm + endpoints |
| `src/main/resources/static/whats-new.js` | Release note | Prepend entry (pre-build only) |

---

## PART A — The DO Email (single CTA)

Handoff §2. Two files: `ImsReviewEmailService.java` (add owner var) + `ims-review-do.html` (rewrite body).

### Task A1: Add `${documentOwner}` email variable

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` (in the per-recipient var-building block around line 393, next to `recipientEmail`)

The email currently has no owner var; the table will show **Document Owner**. Reuse the already-resolved owner display name. The greeting parse already extracts a display name from `firstOwnerDisplayName`; expose the full display name as a new var, stripped of any `(系统ID)` suffix per CLAUDE.md Data Display rules ("show 'Zhu, Peter' not 'Zhu, Peter (14759)'").

- [ ] **Step 1: Locate the owner display source.** Run:

```
grep -n "firstOwnerDisplayName\|ownerNames\|greetingName\|recipientEmail\"" src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java
```

Expected: find where `greetingName` / `firstOwnerDisplayName` is computed (~lines 462–487) and where vars are put (~line 393).

- [ ] **Step 2: Add the var.** Immediately after the `v.put("recipientEmail", ...)` line, add (using whatever owner-display field is in scope — adapt name to the actual local variable; if only `recipientStr` exists, fall back to it):

```java
// Document Owner — shown in the trimmed summary table of the single-CTA
// email (handoff §2). Strip any "(systemId)" suffix per the email design
// rules (show "Zhu, Peter", not "Zhu, Peter (14759)").
String ownerDisplay = firstOwnerDisplayName != null && !firstOwnerDisplayName.isEmpty()
        ? firstOwnerDisplayName.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim()
        : (recipientStr.length() == 0 ? "(unknown)" : recipientStr.toString());
v.put("documentOwner", ownerDisplay);
```

> If `firstOwnerDisplayName` is out of scope at that point, move the `v.put` to just after that variable is assigned (near the greeting parse). The goal is a non-empty `${documentOwner}`.

- [ ] **Step 3: Compile-check.** Run:

```
cd ~/git/plm-field-tracker && /Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home/bin/javac -version
```

(Only confirms JDK present; full build happens once before deploy — Java compiles as part of `mvn package`.) Defer the real compile to Part E so we don't rebuild per-task.

- [ ] **Step 4: Commit** (only when Vikas approves a commit; otherwise leave staged-in-tree):

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java
git commit -m "feat(ims-review): add documentOwner var for single-CTA DO email"
```

### Task A2: Rewrite the DO email body — single CTA + trimmed table

**Files:**
- Modify: `src/main/resources/templates/email/ims-review-do.html:30-87`

Replace (a) the summary table rows and (b) the three-button block.

- [ ] **Step 1: Replace the summary table rows.** In the `<table ... id-less summary>` (lines 31–48), the rows become exactly these six, in order: **Number · Description · Rev / Lifecycle · Next Review Date · Related DRR · Document Owner**. Rename the first label from "Document" to **Number**. Drop the **Type**, **Sent to**, and **Link expires in** rows. New table body:

```html
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="font-size:13px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px;">
          <tr><td style="padding:10px 14px 6px 14px; color:#6B7280; width:35%; font-weight:600;">Number</td>
              <td style="padding:10px 14px 6px 0; font-family:'IBM Plex Mono',Consolas,monospace; color:#4a6fa5; font-weight:600;">${docNumber}</td></tr>
          <tr><td style="padding:6px 14px; color:#6B7280; font-weight:600;">Description</td>
              <td style="padding:6px 14px 6px 0;">${docDescription}</td></tr>
          <tr><td style="padding:6px 14px; color:#6B7280; font-weight:600;">Rev / Lifecycle</td>
              <td style="padding:6px 14px 6px 0;">${docRev} / ${docLifecycle}</td></tr>
          <tr><td style="padding:6px 14px; color:#6B7280; font-weight:600;">Next Review Date</td>
              <td style="padding:6px 14px 6px 0; font-family:'IBM Plex Mono',Consolas,monospace;">${nextReviewDate}</td></tr>
          <tr><td style="padding:6px 14px; color:#6B7280; font-weight:600;">Related DRR</td>
              <td style="padding:6px 14px 6px 0; font-family:'IBM Plex Mono',Consolas,monospace; color:#4a6fa5;">${drrNumber}</td></tr>
          <tr><td style="padding:6px 14px 10px 14px; color:#6B7280; font-weight:600;">Document Owner</td>
              <td style="padding:6px 14px 10px 0;">${documentOwner}</td></tr>
        </table>
```

- [ ] **Step 2: Replace the three-button block with one green CTA.** Replace the entire `<tr>` containing the three stacked `<a>` buttons (lines 59–72) with a single centered button wired to `${responseUrl}` (token-only — `respondUrl(token, null)`, already stamped). Keep the explanatory copy + lock/VPN notes below it but drop the "Pick the option that fits — each button…" sentence (there is one button now):

```html
      <tr><td style="padding:18px 20px 4px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
          <tr><td align="center">
            <a href="${responseUrl}" style="display:inline-block; background:#1F8A4C; color:#ffffff; font-size:15px; font-weight:600; text-decoration:none; padding:15px 38px; border-radius:9px; box-shadow:0 4px 12px rgba(31,138,76,0.30); letter-spacing:0.01em;">Submit Response&nbsp;&nbsp;&rarr;</a>
          </td></tr>
          <tr><td align="center" style="padding-top:10px; font-family:'IBM Plex Mono',Consolas,monospace; font-size:11px; color:#9aa1ab;">Single-use secure link &middot; valid 30 days</td></tr>
        </table>

        <div style="font-size:12.5px; color:#0F1720; margin-top:18px; line-height:1.55;">
          You'll choose your response &mdash; no change, start a change order, or get help &mdash; on the secure response page.
          To manage the DRR directly within Agile, <a href="${drrAgileUrl}" target="_blank" style="color:#4a6fa5;">click here</a>.
          If you need support at any time, please reach out to
          <a href="mailto:IMS-Doc-Managers-Agile@sandisk.com" style="color:#4a6fa5;">IMS-Doc-Managers-Agile@sandisk.com</a>.
        </div>

        <div style="font-size:11.5px; color:#6B7280; margin-top:12px;">
          &#x1F512; The response page confirms your AD password before submitting.
        </div>
        <div style="font-size:11.5px; color:#6B7280; margin-top:4px;">
          &#x1F310; You need to be on the <strong>SanDisk network or VPN</strong> (Cisco AnyConnect) for the page to load.
        </div>
      </td></tr>
```

> Keep everything else: nav header row, `${doInactiveBanner}`, eyebrow + `${statusHeadline}` + intro, footer copy block, footer logo strip. `${responseUrlNoChange/Upload/Help}` are now unused by *this* template but leave the Java `v.put`s in place (other templates / future use).

- [ ] **Step 3: Verify no dangling vars.** Run:

```
grep -nE '\$\{(docType|recipientEmail|linkExpiresIn|responseUrlNoChange|responseUrlUpload|responseUrlHelp)\}' src/main/resources/templates/email/ims-review-do.html
```

Expected: **no matches** (those vars are no longer referenced in this template).

- [ ] **Step 4: Verify the new var is present.** Run:

```
grep -nE '\$\{(documentOwner|responseUrl)\}' src/main/resources/templates/email/ims-review-do.html
```

Expected: `${documentOwner}` (1 hit) and `${responseUrl}` (1 hit).

- [ ] **Step 5: Commit** (on approval):

```bash
git add src/main/resources/templates/email/ims-review-do.html
git commit -m "feat(ims-review): single Submit-Response CTA + trimmed table in DO email"
```

---

## PART B — The Response Landing Page

Handoff §3 + §5. All in `ims-respond.html`: CSS additions, `renderForm()` rewrite (trimmed table + header download + reference panel + 4-option picker), and the OBS routing. The reference panel CSS/markup is shared with the DCO form (Part C) — define it once here.

### Task B1: Add landing + reference-panel + 4-card CSS

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` (`<style>` block, after the existing `.action-card` rules ~line 80 and the `.dco-*` rules)

- [ ] **Step 1: Add the 4-option card + OBS + Stuck-divider CSS.** Add near the existing `.action-card` rules:

```css
  /* Revamp: richer 4-option picker (icon chip + title + sub) — handoff §3 */
  .opt-card { display: flex; gap: 13px; align-items: flex-start;
    border: 1px solid #d8d5cc; border-radius: 9px; padding: 13px 16px;
    cursor: pointer; background: #fff; text-align: left; width: 100%;
    transition: border-color .15s, background .15s; }
  .opt-card:hover { background: #f6f8fb; border-color: #c7cdd6; }
  .opt-chip { width: 27px; height: 27px; border-radius: 7px; flex-shrink: 0;
    display: flex; align-items: center; justify-content: center; font-size: 15px; }
  .opt-body { display: flex; flex-direction: column; gap: 2px; }
  .opt-title { font-weight: 600; font-size: 13px; }
  .opt-sub { font-size: 11.5px; color: var(--muted); }
  .opt-card.no-change .opt-chip { background:#e3f3ea; color:#1F8A4C; }
  .opt-card.no-change .opt-title { color:#1F8A4C; }
  .opt-card.change .opt-chip { background:#eaf1fa; color:#4a6fa5; }
  .opt-card.change .opt-title { color:#4a6fa5; }
  .opt-card.obs .opt-chip { background:#f8e9e8; color:#b0413a; }
  .opt-card.obs .opt-title { color:#9e3b34; }
  .lp-help-sep { font-family:'IBM Plex Mono',Consolas,monospace; font-size:10px;
    letter-spacing:0.08em; text-transform:uppercase; color:#b3aea2; margin:15px 0 3px; }
  .opt-card.help { border:0; border-radius:7px; padding:9px 8px; background:none; }
  .opt-card.help:hover { background:#f4f3ef; }
  .opt-card.help .opt-chip { width:22px; height:22px; background:none; color:var(--muted); }
  .opt-card.help .opt-title { color:var(--ink); }
  .opt-card.help .opt-sub { color:#9aa1ab; }
```

- [ ] **Step 2: Add the shared "IMS Document Details" reference-panel CSS** (used on both landing + form). Add a clearly-marked block:

```css
  /* Revamp: shared "IMS Document Details" reference panel — handoff §5 */
  .co-ref2 { border:1px solid var(--border); border-radius:8px; overflow:hidden; margin:14px 0; }
  .co-ref2 > summary { list-style:none; cursor:pointer; padding:11px 16px;
    background:#e7eef9; border-bottom:1px solid #d2ddf0; box-shadow: inset 3px 0 0 #4a6fa5;
    display:flex; gap:9px; align-items:center; }
  .co-ref2 > summary::-webkit-details-marker { display:none; }
  .co-ref2-title { font-family:'IBM Plex Mono',Consolas,monospace; font-size:10.5px;
    letter-spacing:0.08em; text-transform:uppercase; color:#2c3e50; font-weight:600; }
  .co-ref2-expand { margin-left:auto; display:inline-flex; gap:6px; align-items:center;
    font-size:11px; color:#9aa1ab; font-weight:500; }
  .co-ref2 .chev { color:#b3aea2; font-size:10px; transition:transform .15s; }
  .co-ref2[open] > summary .chev { transform:rotate(90deg); }
  .co-ref2-inner { padding:6px 16px 14px; background:#fff; }
  .co-ref2-grp { border:1px solid var(--border); border-radius:7px; margin-top:10px; overflow:hidden; }
  .co-ref2-grp > summary { list-style:none; cursor:pointer; padding:7px 12px;
    background:#FAFAF7; border-bottom:1px solid var(--border);
    display:flex; gap:8px; align-items:center; font-size:11.5px; font-weight:600; color:#2c3e50; }
  .co-ref2-grp > summary::-webkit-details-marker { display:none; }
  .co-ref2-grp .ct { margin-left:auto; font-family:'IBM Plex Mono',Consolas,monospace;
    font-size:10px; color:#9aa1ab; font-weight:400; }
  .co-ref2-grp[open] > summary .chev { transform:rotate(90deg); }
  .co-ref2-body { display:grid; grid-template-columns:1fr 1fr; column-gap:26px; padding:4px 14px 8px; }
  .co-ref2-row { display:grid; grid-template-columns:42% 1fr; gap:12px; align-items:start;
    padding:6px 0; border-bottom:1px solid #f3f1eb; }
  .co-ref2-k { text-align:right; font-size:11px; line-height:1.4; color:#8a8f98; font-weight:600; }
  .co-ref2-v { font-size:12px; line-height:1.4; color:#2b323b; word-break:break-word; }
  .co-ref2-v.empty { color:#cdc8bb; }
  .co-ref2-v.docno { font-family:'IBM Plex Mono',Consolas,monospace; color:#4a6fa5; font-weight:600; }
  @media (max-width:560px) { .co-ref2-body { grid-template-columns:1fr; } }
```

- [ ] **Step 3: Verify.** Run:

```
grep -nc "opt-card\|co-ref2" src/main/resources/static/ims-respond.html
```

Expected: a count > 20 (both rule blocks present).

### Task B2: Build the reference-panel renderer (shared helper)

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` (inside the main `<script>` IIFE, add a top-level `function renderRefPanel(info)`)

The panel binds to whatever `token/info` provides today; fields not yet in the payload render a faint `—`. **The full Agile title-block (Document Category, Create User/Date, Old Doc Number, Referenced Documents, Rev Release Date, Site Location, Function/Sub Function, Subcontractors, Document Style, etc.) is NOT in `token/info` yet** — see Part D / handoff §5; this renderer is forward-compatible (reads `info.<field>` and shows `—` when absent).

- [ ] **Step 1: Add the renderer.** Insert this function in the IIFE (near `renderForm`). It returns an HTML string for a `<details class="co-ref2">`:

```javascript
  // Shared "IMS Document Details" reference panel (handoff §5). Read-only,
  // collapsed by default. Binds to token/info fields that exist today; any
  // not yet wired from Agile render as a faint "—" (Part D backend follow-up).
  function refRow(label, val, kind) {
    var v = (val == null || val === '') ? '—' : esc(val);
    var cls = 'co-ref2-v' + (v === '—' ? ' empty' : (kind ? ' ' + kind : ''));
    return '<div class="co-ref2-row"><div class="co-ref2-k">' + esc(label) +
           '</div><div class="' + cls + '">' + v + '</div></div>';
  }
  function refGroup(title, rows) {
    return '<details class="co-ref2-grp" open><summary><span class="chev">&#9656;</span>' +
           esc(title) + '<span class="ct">' + rows.length + ' fields</span></summary>' +
           '<div class="co-ref2-body">' + rows.join('') + '</div></details>';
  }
  window.renderRefPanel = function (info) {
    info = info || {};
    var titleBlock = [
      refRow('Number', info.docNumber, 'docno'),
      refRow('Document Type', info.documentType),
      refRow('Lifecycle Phase', info.lifecyclePhase),
      refRow('Description', info.description),
      refRow('Document Category', info.documentCategory),
      refRow('Product Line', info.productLine),
      refRow('Rev Release Date', info.revReleaseDate)
    ];
    var moreInfo = [
      refRow('Old Document Number', info.oldDocumentNumber),
      refRow('Referenced Documents', info.referencedDocuments),
      refRow('Owner', (info.ownerNames || []).join('; ')),
      refRow('Create User', info.createUser),
      refRow('Create Date', info.createDate)
    ];
    var docDetails = [
      refRow('Automotive Product Development Standard Applicability?', info.apdsApplicability),
      refRow('Last Reviewed Date', info.lastReviewedDate),
      refRow('Next Review Date', info.nextReviewDate),
      refRow('Site Location', info.siteLocation),
      refRow('Function / Sub Function', info.functionSubFunction),
      refRow('Document Owner(s)', (info.ownerNames || []).join('; ')),
      refRow('Subcontractors', info.subcontractorsDisplay),
      refRow('Document Style', info.documentStyle),
      refRow('VAR Type', info.varType),
      refRow('Product Group', info.productGroup),
      refRow('Applies to all Part Numbers in Model list', info.appliesAllParts),
      refRow('Part Number', info.partNumber),
      refRow('Support document category/type', info.supportDocCategory),
      refRow('Spec', info.spec),
      refRow('Support document Number', info.supportDocNumber),
      refRow('Document Classification', info.documentClassification),
      refRow('Product', info.product),
      refRow('PM Checklist', info.pmChecklist)
    ];
    return '<details class="co-ref2"><summary><span class="chev">&#9656;</span>' +
           '<span class="co-ref2-title">IMS Document Details</span>' +
           '<span class="co-ref2-expand">Expand for more details</span></summary>' +
           '<div class="co-ref2-inner">' +
             refGroup('Title Block', titleBlock) +
             refGroup('More Info', moreInfo) +
             refGroup('Document Details', docDetails) +
           '</div></details>';
  };
```

- [ ] **Step 2: Verify** (deferred to B5 visual check — the panel renders once `renderForm` calls it).

### Task B3: Trim the landing summary table + add header download

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` `renderForm()` (~lines 605–644), and the `#formCard` header markup (~lines 403–407)

Handoff §3: summary shows only **Number · Description · Rev / Lifecycle · Related DRR**; the **Download document attachment(s)** button moves into the header (top-right of the title block); the reference panel sits between the summary and the picker.

- [ ] **Step 1: Add a header download button** in `#formCard`. Change the title block (lines 404–407) to a flex row with the download button on the right:

```html
      <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:16px;">
        <div>
          <div class="eyebrow" id="roleEyebrow">Loading…</div>
          <h1 id="formTitle" style="margin-bottom:6px;">Review</h1>
        </div>
        <div style="text-align:right; flex-shrink:0;">
          <button type="button" id="dlAttachBtn" onclick="downloadAttachments('dlAttachBtn','dlAttachStatus')"
            style="background:#eaf1fa; border:1px solid #b9cde8; color:#4a6fa5; padding:9px 15px; border-radius:7px; font-size:12.5px; font-weight:600; cursor:pointer; white-space:nowrap;">
            &#8615; Download document attachment(s)</button>
          <div id="dlAttachStatus" style="font-size:11px; color:var(--muted); margin-top:4px;"></div>
        </div>
      </div>

      <div class="docmeta" id="docMeta"></div>
      <div id="refPanelMount"></div>
```

- [ ] **Step 2: Trim the `meta` builder** in `renderForm()`. Replace the whole `var meta = '...'; ... $('docMeta').innerHTML = meta;` block (lines 606–644) with only four rows — and remove the in-`docMeta` Attachments button (it's in the header now):

```javascript
    // Summary — four rows only (handoff §3). Owner/Sent-to/Type/dates live in
    // the collapsible reference panel below, not in this top summary.
    var meta = '';
    meta += '<div class="docmeta-row"><div class="k">Number</div><div class="v docno">' + esc(info.docNumber || '') + '</div></div>';
    meta += '<div class="docmeta-row"><div class="k">Description</div><div class="v">' + esc(info.description || '') + '</div></div>';
    meta += '<div class="docmeta-row"><div class="k">Rev / Lifecycle</div><div class="v">' + esc(info.rev || '') + ' / ' + esc(info.lifecyclePhase || '') + '</div></div>';
    if (info.drrNumber) {
      meta += '<div class="docmeta-row"><div class="k">Related DRR</div><div class="v">' + esc(info.drrNumber) + '</div></div>';
    }
    $('docMeta').innerHTML = meta;
    $('refPanelMount').innerHTML = window.renderRefPanel(info);
```

- [ ] **Step 3: Verify** the old multi-row builder is gone. Run:

```
grep -nc "Sent to\|Sent on\|Link expires in\|Download document attachment" src/main/resources/static/ims-respond.html
```

Expected: "Download document attachment" appears (header button + drawer button), but "Sent to"/"Sent on"/"Link expires in" no longer appear inside `renderForm` (they may still exist elsewhere — confirm by reading the function).

### Task B4: Rebuild the action picker as 4 option cards

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` `renderForm()` action-grid block (~lines 647–672) and `onPickAction`/`onContinue` (~lines 750–790)

Handoff §3: four options in order — **No change needed / Needs change — start a change order / Don't need this — OBS it / I need help** — with the help card de-emphasized under a "Stuck?" divider. DM role keeps its existing two actions (Approve / Send Back) unchanged.

- [ ] **Step 1: Replace the DO action list + grid renderer.** For `info.role !== 'DM'`, build the four cards (keep DM branch as-is). Replace the `actions = [...]` DO branch and the `grid` builder:

```javascript
    var grid = '';
    if (info.role === 'DM') {
      // (unchanged) — keep the existing DM_APPROVE / DM_SEND_BACK action-card markup.
      var dmActions = [
        { key:'DM_APPROVE', cls:'no-change', icon:'✓', title:'Confirm No Change',
          sub:'Closes the review. Doc Control closes the DRR with both signed PDFs attached. ~30 sec.' },
        { key:'DM_SEND_BACK', cls:'change', icon:'↩', title:'Send Back to DO',
          sub:'DO needs to revise. Reopens the DO step. No PDF generated for send-back. ~15 sec.' }
      ];
      dmActions.forEach(function (a) {
        grid += optCardHtml(a);
      });
    } else {
      var doActions = [
        { key:'NO_CHANGE', cls:'no-change', icon:'✓', title:'No change needed',
          sub:'This document is current and needs no revision. Generates an approval PDF and notifies your manager. ~30 sec.' },
        { key:'UPLOAD', cls:'change', icon:'✎', title:'Needs change — start a change order',
          sub:'I have a revised file. Opens the change-order form; Doc Control takes it from there. ~3 min.' },
        { key:'OBS', cls:'obs', icon:'⊙', title:"Don't need this — OBS it",
          sub:'This document is no longer needed. Starts an obsolete (OBS) change order to retire it in Agile.' }
      ];
      doActions.forEach(function (a) { grid += optCardHtml(a); });
      // Help, de-emphasized under a quiet "Stuck?" divider (handoff §3, opt 4).
      grid += '<div class="lp-help-sep">Stuck?</div>';
      grid += optCardHtml({ key:'HELP', cls:'help', icon:'?', title:'I need help',
        sub:"I'm blocked and need Doc Control's assistance. ~30 sec." });
    }
    $('actionGrid').innerHTML = grid;
```

- [ ] **Step 2: Add the `optCardHtml` helper** (top-level in the IIFE):

```javascript
  function optCardHtml(a) {
    return '<button class="opt-card ' + a.cls + '" data-action="' + esc(a.key) + '" onclick="onPickAction(\'' + a.key + '\')">' +
      '<span class="opt-chip">' + a.icon + '</span>' +
      '<span class="opt-body"><span class="opt-title">' + esc(a.title) + '</span>' +
      '<span class="opt-sub">' + esc(a.sub) + '</span></span></button>';
  }
```

- [ ] **Step 3: Route OBS to the DCO drawer.** In `onContinue()` (~line 765), OBS opens the same DCO form as UPLOAD for now (handoff §3/§7.1 interim). Change the drawer-open guard:

```javascript
    // UPLOAD or OBS with the rich-form flag → open the change-order drawer.
    // OBS is interim-routed to the standard DCO form (handoff §7.1); a
    // dedicated obsolete/retire variant comes later.
    if ((key === 'UPLOAD' || key === 'OBS') && STATE.info && STATE.info.dcoFormEnabled) {
      if (typeof window.dcoOpen === 'function') {
        window.dcoOpen(STATE.info);
        return;
      }
    }
```

> Note: NO_CHANGE / HELP still flow through the inline sign card + `token/submit` with their action keys (unchanged). OBS does **not** submit a server action in the interim — it just opens the DCO drawer, whose own `submit-dco` handles the rest. If `dcoFormEnabled` is false, OBS falls through to the inline sign card with `action=OBS`; since the backend may not accept `OBS` yet, guard it: if `!dcoFormEnabled`, treat OBS like UPLOAD's legacy inline path is risky — instead, when `dcoFormEnabled` is false, hide the OBS card entirely (add `&& info.dcoFormEnabled` around pushing the OBS action).

- [ ] **Step 4: Conditionally include OBS only when the DCO form is enabled.** Wrap the OBS push:

```javascript
      if (info.dcoFormEnabled) {
        doActions.push({ key:'OBS', cls:'obs', icon:'⊙', title:"Don't need this — OBS it",
          sub:'This document is no longer needed. Starts an obsolete (OBS) change order to retire it in Agile.' });
      }
```

(Restructure Step 1's `doActions` to start with NO_CHANGE + UPLOAD, then conditionally push OBS, then the Stuck divider + HELP.)

- [ ] **Step 5: Verify in the local instance.** Build is not required for static-asset changes IF testing the file the running JAR serves — but the JAR serves its *bundled* copy. Use preview mode for the form (Part C); for the landing picker, the cleanest check is to load the page against a real token. Since tokens need the server, do a **DOM smoke test** instead: open `~/git/plm-field-tracker/src/main/resources/static/ims-respond.html` directly in a browser with `?preview=1` won't exercise renderForm. So verification here is **code review + the Part E deploy smoke test**. Mark this step done after re-reading `renderForm` to confirm: 4 cards for DO, DM branch intact, OBS guarded by `dcoFormEnabled`, help under the divider.

- [ ] **Step 6: Commit** (on approval):

```bash
git add src/main/resources/static/ims-respond.html
git commit -m "feat(ims-review): 4-option landing picker, ref panel, header download, trimmed summary"
```

---

## PART C — Flatten the DCO Form into a Tabular Single Page

Handoff §4 + §6. This is the largest task: `imsreview-dco-form.js` becomes a single scrollable form with 6 numbered collapsible sections in the two-column `co-row` pattern, plus the reference panel, header download, and a footer status line. **Keep** the `DCO` state object, `collectForm()`, all fetch endpoints, all typeahead/picker/file/CID/BU/info-popover helpers, and `dcoSubmit()`. **Remove** the stepper model (`STEPS`, `currentStep`, `renderStepper`, `renderStep`, `dcoStepNext/Back/Jump`, per-step gating) and render everything at once.

> Because the form code is ~1500 lines and shares many helpers, the strategy is **replace the render/navigation layer, reuse the field helpers**. Build incrementally and verify each section in preview mode (`?preview=1`).

### Task C1: Add the tabular form CSS to `ims-respond.html`

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` `<style>` (after the existing `.dco-*` rules)

- [ ] **Step 1: Add the `co-*` form-layout CSS** (two-column rows, numbered collapsible sections, file slots, footer). Scope under `.dco-drawer-body` where it overrides existing wizard CSS:

```css
  /* Revamp: tabular single-page DCO form (handoff §4). The drawer body now
     hosts numbered collapsible sections of label|value rows. */
  .co-head { padding:16px 22px 12px; border-bottom:1px solid var(--border);
    display:flex; justify-content:space-between; align-items:flex-start; gap:16px; }
  .co-dl-btn { background:#eaf1fa; border:1px solid #b9cde8; color:#4a6fa5;
    padding:9px 15px; border-radius:7px; font-size:12.5px; font-weight:600; cursor:pointer; white-space:nowrap; }
  .co-dl-btn:hover { background:#dfeaf8; border-color:#9fbce0; }
  .co-dl-cap { font-size:10.5px; color:#9aa1ab; margin-top:5px; max-width:210px; text-align:right; }
  .co-fsec { border-bottom:1px solid var(--border); }
  .co-fsec > summary { list-style:none; cursor:pointer; padding:9px 22px; background:#F4F2EC;
    border-top:1px solid var(--border); display:flex; gap:9px; align-items:center;
    font-family:'IBM Plex Mono',Consolas,monospace; font-size:10.5px; letter-spacing:0.1em;
    text-transform:uppercase; color:#2c3e50; font-weight:600; }
  .co-fsec > summary::-webkit-details-marker { display:none; }
  .co-fsec .chev { color:#b3aea2; font-size:10px; transition:transform .15s; }
  .co-fsec[open] > summary .chev { transform:rotate(90deg); }
  .co-fsec .n { color:#b3aea2; }
  .co-fsec .note { margin-left:auto; font-family:'IBM Plex Sans',sans-serif; font-size:10px;
    letter-spacing:0; text-transform:none; color:#9aa1ab; font-weight:500; }
  .co-row { display:grid; grid-template-columns:var(--labelw,188px) 1fr; border-bottom:1px solid var(--border); }
  .co-row:last-child { border-bottom:0; }
  .co-k { background:#FAFAF7; border-right:1px solid var(--border); padding:12px 14px;
    font-size:12.5px; font-weight:600; color:#3c4654; display:flex; gap:5px;
    align-items:flex-start; justify-content:space-between; }
  .co-k .req { color:var(--error); }
  .co-v { background:#fff; padding:10px 14px; }
  .co-help { font-size:10.5px; color:#9aa1ab; margin-top:5px; }
  .co-radios { display:flex; gap:16px; flex-wrap:wrap; }
  .co-radio { display:inline-flex; align-items:center; gap:5px; font-size:13px; cursor:pointer; }
  .co-radio input { width:15px; height:15px; accent-color:#4a6fa5; }
  .co-foot { padding:12px 22px; border-top:1px solid var(--border); background:#FAFAF7;
    display:flex; gap:14px; align-items:center; }
  .co-foot-status { flex:1; font-size:11px; color:#9aa1ab; line-height:1.45; }
  @media (max-width:560px) { .co-row { grid-template-columns:1fr; } .co-k { border-right:0; border-bottom:1px solid var(--border); } }
```

- [ ] **Step 2: Verify.** `grep -nc "co-row\|co-fsec\|co-k\b" src/main/resources/static/ims-respond.html` → > 5.

### Task C2: Widen the drawer + replace stepper header with tabular header; replace footer

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` drawer markup (~lines 481–517)

- [ ] **Step 1: Widen the drawer.** The two-column form needs width; change `.dco-drawer { width: 560px; ... }` to `width: 720px;` (line ~139) and the `@media (max-width:768px)` rule already collapses to `100vw`.

- [ ] **Step 2: Replace the stepper** in the drawer header. Remove `<div class="dco-stepper" id="dcoStepper"></div>` (line 501). The header keeps the eyebrow + serif title + close X + the existing persistent download control (already present from the in-flight A73 work — keep it; it satisfies handoff §4's header download).

- [ ] **Step 3: Replace the footer nav.** Replace the three wizard buttons (Back / Next / Sign & submit) at lines 509–516 with the status line + single Sign-&-submit button:

```html
  <div class="dco-drawer-ft co-foot">
    <div id="dcoStatusLine" class="co-foot-status">Loading…</div>
    <button class="btn btn-primary" id="dcoSubmitBtn" type="button" onclick="dcoSubmit()" disabled>&#x1F512; Sign &amp; submit</button>
  </div>
```

- [ ] **Step 4: Bump the script cache-buster.** Change `imsreview-dco-form.js?v=20260619d` → `?v=20260623a` (line 518) so the browser reloads the rewritten JS.

### Task C3: Replace the wizard render layer with single-page section rendering

**Files:**
- Modify: `src/main/resources/static/imsreview-dco-form.js`

This is the core. Keep the `DCO` state object, all field helpers (`fslotHtml`, `mfslotHtml`, picker/typeahead wiring, `infoIcon`/`openDcoTip`, CID editor, BU toggles, product-line mode), `collectForm()`, `dcoSubmit()`, `dcoMaybeClose/Close`, metadata fetch. Replace: `STEPS`/`currentStep`/`renderStepper`/`renderStep`/`renderStep1Change`/`renderStep3Files`/`renderStep4Sign`/`dcoStepNext`/`dcoStepBack`/`dcoStepJump`/`stepComplete`/`missingFields(step)`/`refreshNavButtons` with a single `renderForm()` that emits all six sections + a `recomputeStatus()` that drives the footer.

- [ ] **Step 1: Add row/section helpers** near the other helpers:

```javascript
  function coRow(label, required, valueHtml, infoTip, helpHtml) {
    var k = '<div class="co-k"><span>' + label + (required ? ' <span class="req">*</span>' : '') +
            (infoTip ? infoIcon(infoTip) : '') + '</span></div>';
    var v = '<div class="co-v">' + valueHtml + (helpHtml ? '<div class="co-help">' + helpHtml + '</div>' : '') + '</div>';
    return '<div class="co-row">' + k + v + '</div>';
  }
  function coSection(num, title, rowsHtml, note) {
    return '<details class="co-fsec" open><summary><span class="chev">&#9656;</span>' +
           '<span class="n">' + num + '</span>' + title +
           (note ? '<span class="note">' + note + '</span>' : '') + '</summary>' +
           rowsHtml + '</details>';
  }
```

- [ ] **Step 2: Write the new `renderForm()`** that replaces `renderStep()`. It builds the reference panel (reuse `window.renderRefPanel` from `ims-respond.html`) + 6 sections into `#dcoFormContent`, then wires all the dynamic controls (call the existing wiring functions for pickers, typeaheads, file slots, CID, BU). Structure:

```javascript
  function renderForm() {
    var info = DCO.info || {};
    var m = DCO.meta || {};
    var html = '';
    // Reference panel (handoff §5) — reuse the shared renderer from ims-respond.html.
    if (typeof window.renderRefPanel === 'function') html += window.renderRefPanel(info);

    // 01 Change details
    var s1 = '';
    s1 += coRow('Description of Change', true, '<textarea class="co-ta" id="dco-descriptionOfChange" maxlength="4000" rows="3"></textarea>', 'Agile field: Description of Change.');
    s1 += coRow('Reason for Change', true, '<textarea class="co-ta" id="dco-reasonForChange" maxlength="4000" rows="3"></textarea>', 'Agile field: Reason for Change.');
    s1 += coRow('Priority', true, priorityControlHtml(m.priority), 'Agile field: Priority.', 'Options load from the Agile Priority list; Standard is the default.');
    html += coSection('01', 'Change details', s1);

    // 02 Scope
    var s2 = '';
    s2 += coRow('Product Line(s)', true, productLineControlHtml(), 'Agile field: Product Line(s).');
    s2 += coRow('Subcontractors', true, pickerHtml('subcontractors', 'e.g. C002…'), 'Agile field: Subcontractors.');
    s2 += coRow('Training Requirement', true, radioGroupHtml('trainingRequirement', m.trainingRequirement, null), 'Agile field: Training Requirement.', 'No default — the owner picks one.');
    s2 += coRow('Change Impact Disposition', true, changeImpactControlHtml(), 'Agile field: Change Impact Disposition.', "Pick 'Yes' to fill the impact table.");
    s2 += coRow('Business Unit', false, businessUnitControlHtml(m.businessUnit), 'Agile field: Business Unit.', 'Multi-select. Optional — becomes required for SDSM documents (number contains -SM-).');
    html += coSection('02', 'Scope', s2);

    // 03 People
    var s3 = '';
    s3 += coRow('Document Owner(s)', true, typeaheadHtml('documentOwners', 'Type 2+ chars to search active Agile users'), null, 'Pre-filled from the document. Add or remove as needed.');
    s3 += coRow('Approvers', true, typeaheadHtml('approvers', 'Type 2+ chars to search active Agile users'));
    s3 += coRow('Observers', true, typeaheadHtml('observers', 'Type 2+ chars to search active Agile users'));
    html += coSection('03', 'People', s3);

    // 04 IMS Document — Edit (STUBBED — handoff §6 / §7.4). No LoV + no write
    // path yet; render the five rows disabled with a pending note.
    html += coSection('04', 'IMS Document — Edit', imsEditStubHtml(info), 'editable Agile attributes · saved with the change order');

    // 05 Documents
    var s5 = '';
    s5 += coRow('Redline Version', true, fslotHtml('Marked-up version', 'Shows what changed', 'redline'));
    s5 += coRow('Final Version', true, fslotHtml('Finished document', 'With changes accepted', 'final'));
    s5 += coRow('Stakeholder Notification', true, stakeholderControlHtml(), null, 'Provide at least one: an uploaded email/sign-off, or a list of email addresses.');
    s5 += coRow('Other Supporting Files', false, mfslotHtml('Additional files', 'Anything else for the change order', 'other'), null, 'Optional.');
    html += coSection('05', 'Documents', s5, 'renamed with the doc number automatically');

    // 06 Review & sign
    var s6 = '';
    s6 += coRow('AD Username', true, '<input class="co-in" id="dcoUsername" type="text" autocomplete="username" placeholder="your AD login ID">');
    s6 += coRow('AD Password', true, '<input class="co-in" id="dcoPassword" type="password" autocomplete="current-password" placeholder="••••••••">');
    s6 += '<div class="co-attest" style="margin:12px 22px; padding:11px 14px; background:#FAFAF7; border:1px solid var(--border); border-radius:7px; font-size:11.5px; color:#5a5f66; line-height:1.55;">By signing, you confirm this IMS document was reviewed and that you have the authority and information to update it. We record your verified identity, the action, a UTC timestamp and your IP on a tamper-evident PDF.</div>';
    html += coSection('06', 'Review & sign', s6);

    document.getElementById('dcoFormContent').innerHTML = html;
    wireAllControls();   // bind pickers, typeaheads, file inputs, CID, BU, priority, etc.
    prefillForm();       // owners from info.owners, product-line/subcontractor docPrefill, default priority
    recomputeStatus();
  }
```

> The helper names above (`priorityControlHtml`, `productLineControlHtml`, `pickerHtml`, `radioGroupHtml`, `changeImpactControlHtml`, `businessUnitControlHtml`, `typeaheadHtml`, `stakeholderControlHtml`, `fslotHtml`, `mfslotHtml`, `imsEditStubHtml`, `wireAllControls`, `prefillForm`, `recomputeStatus`) are the existing rendering helpers **renamed/adapted** — in the current file they are inlined inside `renderStep1Change`/`renderStep3Files`/`renderStep4Sign`. **Extract each control's markup into a named helper** (Step 3 below) so `renderForm` can compose them in the new order. Keep their internal IDs (`dco-priority`, `dco-pick-subcontractors-*`, `dco-businessUnit-group`, `dco-file-redline`, `dcoUsername`, etc.) identical so the wiring + `collectForm()` still find them.

- [ ] **Step 3: Extract control helpers.** For each control, lift the markup currently built inside `renderStep1Change`/`renderStep3Files`/`renderStep4Sign` into a pure `*Html()` function returning the inner-`co-v` markup. Do them one at a time, verifying the form still renders in preview after each:
  - `priorityControlHtml(list)` — radios when ≤4 options + hidden `#dco-priority`, else `<select id="dco-priority">`; default Standard handled in `prefillForm`.
  - `radioGroupHtml(key, list, defaultVal)` — generic radios writing to a hidden `#dco-<key>` (used for Training Requirement; no default).
  - `productLineControlHtml()` — Specific/N-A radios + the `dco-pick-productLines-*` picker (existing markup).
  - `pickerHtml(key, placeholder)` — the multi-pick chips+filter+list block (existing).
  - `changeImpactControlHtml()` — No/Yes radios writing `#dco-changeImpactDisposition` + the `#dco-cid-details` CID table (existing).
  - `businessUnitControlHtml(list)` — the `.dco-bu-group` toggle buttons (existing SDSM markup); for non-SDSM keep optional select; either way write `#dco-businessUnit`.
  - `typeaheadHtml(key, placeholder)` — the user-search chips+input+results block (existing).
  - `fslotHtml/mfslotHtml` — already exist; reuse as-is.
  - `stakeholderControlHtml()` — the email-copy file slot + `#dco-notifyStakeholders` textarea + AD-search results (existing).
  - `imsEditStubHtml(info)` — NEW; see Task C4.

- [ ] **Step 4: Write `wireAllControls()`** — call the existing per-control wiring (event listeners for pickers, typeaheads, file inputs, CID inputs, BU toggles, priority change, stakeholder AD-search, plus `recomputeStatus` on every relevant input). This replaces the per-step wiring previously done at the end of each `renderStepN`.

- [ ] **Step 5: Write `recomputeStatus()`** — replaces `refreshNavButtons` + the stepper. Reuse the existing `missingFields` logic but evaluate **all** required fields at once (no per-step split): description, reason, priority, productLines (or N/A), subcontractors, trainingRequirement, businessUnit (only if SDSM), changeImpactDisposition (+ ≥1 CID row if Yes), documentOwners, approvers, observers, redline, final, (email file OR notify addresses), username, password. Build the "Still needed: …" string into `#dcoStatusLine`; enable `#dcoSubmitBtn` only when nothing is missing (or always in preview mode, but keep submit disabled in preview):

```javascript
  function recomputeStatus() {
    captureFormFromDom();              // pull current DOM values into DCO.form
    var missing = missingFieldsAll();  // array of human labels
    var line = document.getElementById('dcoStatusLine');
    var btn = document.getElementById('dcoSubmitBtn');
    if (DCO.preview) { line.textContent = 'Preview mode — submit disabled.'; if (btn) btn.disabled = true; return; }
    if (missing.length === 0) { line.textContent = '✓ All required fields complete.'; line.classList.add('ok'); if (btn) btn.disabled = false; }
    else { line.textContent = 'Still needed: ' + missing.join(', '); line.classList.remove('ok'); if (btn) btn.disabled = true; }
  }
```

- [ ] **Step 6: Update `dcoOpen()`** — remove `currentStep = 0`; call `fetchMetadataThenRender()` which now calls `renderForm()` (single page) instead of `renderStep()`. Keep preview-mode handling (`info.preview`). Keep the persistent header download button (already wired to `downloadAttachments`).

- [ ] **Step 7: Delete dead wizard code** — `STEPS`, `renderStepper`, `renderStep`, `renderStep1Change`, `renderStep3Files`, `renderStep4Sign`, `dcoStepNext`, `dcoStepBack`, `dcoStepJump`, `stepComplete`, and the `#dcoStepper`/`#dcoNextBtn`/`#dcoBackBtn` references. (The drawer markup for those buttons was removed in C2.)

- [ ] **Step 8: Verify in preview mode** after the whole rewrite. Start the local instance and open the preview:

```
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```

Then (in another shell) confirm it's up and log in, and open `http://localhost:8090/ims-respond.html?preview=1`. Expected: the drawer opens showing the reference panel + 6 numbered collapsible sections in the two-column layout, Priority/Training as radios, Business Unit checkboxes, the footer "Still needed: …" line, submit disabled. **This requires the rewritten JS to be the one the server serves** — see Part E (the running JAR serves bundled assets, so you must rebuild OR point the local config at the working-tree `static/` dir if supported). If a rebuild is too heavy for iterative checks, verify by opening the static file's JS logic against the prototype and doing the full check post-build in Part E.

### Task C4: Section-04 "IMS Document — Edit" stub

**Files:**
- Modify: `src/main/resources/static/imsreview-dco-form.js` (`imsEditStubHtml`)

Handoff §6 + §7.4 + build order: **build the UI shell, stub the write path.** There is no LoV data for VAR Type / Product Group / Spec / Product / PM Checklist, and no `AgileWriteBackClient` method to write them. So render the five rows **disabled** with a clear pending note, and do **not** add them to `collectForm()`.

- [ ] **Step 1: Add the stub renderer:**

```javascript
  // Section 04 is intentionally a non-functional shell for now (handoff §6/§7.4):
  // no Agile LoV feed for these five attributes and no document-attribute write
  // path on AgileWriteBackClient yet. Render the rows disabled with current
  // values (when token/info supplies them) so the layout is real; wiring the
  // edits is deferred to the backend follow-up. These are NOT collected on submit.
  function imsEditStubHtml(info) {
    function ro(label, val) {
      return coRow(label, false,
        '<input class="co-in" type="text" value="' + esc(val || '') + '" disabled placeholder="—">');
    }
    return '<div class="co-row"><div class="co-k" style="grid-column:1 / -1; background:#fff8e1; border-right:0; color:#5a4a1f; font-weight:500;">' +
      '&#9888; Editing these five attributes is coming soon. They are shown read-only until the Agile write path ships.' +
      '</div></div>' +
      ro('VAR Type', info.varType) +
      ro('Product Group', info.productGroup) +
      ro('Spec', info.spec) +
      ro('Product', info.product) +
      ro('PM Checklist', info.pmChecklist);
  }
```

- [ ] **Step 2: Confirm `collectForm()` is untouched** — `grep -n "varType\|productGroup\|pmChecklist" src/main/resources/static/imsreview-dco-form.js` should show only the stub renderer, never inside `collectForm`.

- [ ] **Step 3: Commit Part C** (on approval):

```bash
git add src/main/resources/static/imsreview-dco-form.js src/main/resources/static/ims-respond.html
git commit -m "feat(ims-review): flatten DCO wizard into tabular single-page form (6 sections + ref panel + section-04 stub)"
```

---

## PART D — Deferred / backend follow-ups (DO NOT implement now; document only)

These are gated on handoff §7 decisions and/or missing backend support. **Leave stubbed; flag to Vikas.** Do not block Parts A–C on them.

- [ ] **D1 (decision §7.1): Dedicated OBS obsolete/retire flow.** Interim = OBS opens the standard DCO form (done in B4). A trimmed obsolete variant (obsolete reason + sign-off, no redline/final) needs its own Agile change-type path + required fields. Deferred.
- [ ] **D2 (decision §7.4 / §6): Section-04 write path.** Needs (a) a `dco-form-metadata` extension returning LoV for VAR Type / Product Group / Spec / Product / PM Checklist, and (b) a new `AgileWriteBackClient.updateItemCells(...)` + agile-service endpoint to persist them as part of the change-order submit. UI is a disabled stub until then.
- [ ] **D3 (handoff §5): Full reference-panel data.** `token/info` currently returns only ~8 fields. The reference panel renders `—` for the rest (Document Category, Product Line value, Create User/Date, Old Doc Number, Referenced Documents, Rev Release Date, Site Location, Function/Sub Function, Subcontractors display, Document Style, Document Classification, etc.). Populating them requires extending `lookupDoc()`'s SQL + the `describeToken()` payload in `ImsReviewService.java`/`ImsReviewController.java`. The renderer (`renderRefPanel`) is already forward-compatible — when the payload gains a field, it shows automatically.
- [ ] **D4 (decisions §7.2 / §7.3): confirm Priority LoV values + Business Unit's 3 real values + the exact SDSM rule.** Currently keyed off doc number containing `-SM-`; values come from `dco-form-metadata`. No code change needed if the live lists are already correct — confirm with Vikas.

---

## PART E — Build, changelog, deploy-to-staging, verify

Per `plm-field-tracker/CLAUDE.md`. Do this once, after Parts A–C are code-complete and self-reviewed.

- [ ] **E1: Update `whats-new.js`.** Prepend a new entry at the top of `WHATS_NEW_RELEASES` dated 2026-06-23, e.g.:

```javascript
  {
    date: '2026-06-23',
    title: 'IMS Document Review — streamlined response flow',
    items: {
      improve: [
        'DO notification email now has a single "Submit Response" button — pick your response on the secure page.',
        'Response page: cleaner 4-option picker (No change / Needs change / OBS / Help) with a collapsible "IMS Document Details" reference panel.',
        'Change-order form flattened from a step wizard into one scrollable, tabular form with numbered sections.'
      ],
      new: [
        'Download the document attachment(s) right from the response-page header and the change-order form.',
        '"Don\'t need this — OBS it" option to start an obsolete change order.'
      ]
    }
  },
```

- [ ] **E2: Build the JAR.** Java 11 (Corretto 11 — memory `reference_build_jdk`):

```
cd ~/git/plm-field-tracker && JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn -q package -DskipTests
```

Expected: `target/plm-field-tracker-1.0.1.jar` built, BUILD SUCCESS.

- [ ] **E3: Local smoke test.** Copy to the local setup, run, and verify in preview:

```
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```

Log in (plmadmin — password from private memory; never echo it) and open `http://localhost:8090/ims-respond.html?preview=1`. Confirm: 6 numbered sections render in the two-column layout, reference panel collapses/expands, Priority & Training are radios, Business Unit checkboxes sit after Change Impact Disposition, section-04 shows the disabled stub, footer status line works, submit stays disabled in preview. Open `whats-new` to confirm the entry shows.

- [ ] **E4: Copy to staging on the prod share** (never the live folder — CLAUDE.md):

```
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
```

If `/Volumes/uls-ep-aglipccb/` isn't mounted, **stop and tell Vikas** — don't skip silently. Verify size parity with `stat -f "%z"` on source + staging copy.

- [ ] **E5: Hand off for server test.** The email + real token flow (and any Agile write-back) can only be verified on the server (memory: `feedback_agile_sdk_remote_testing`, `feedback_no_agile_writeback_on_this_machine`). Tell Vikas the staged JAR is ready, list what to test live (DO email single CTA, landing 4-option picker, OBS → DCO form, full DCO submit), and that section-04 edit + full reference-panel data are deferred backend follow-ups (Part D).

---

## Self-Review (completed against the handoff)

**Spec coverage:** §2 email → A1+A2. §3 landing (4 options, OBS routing, help de-emphasis, header download, trimmed table, reference panel) → B2–B4. §4 form flatten (tabular rows, 6 numbered collapsible sections, Priority/Training radios, BU checkboxes after Change Impact, header download, footer) → C1–C3. §5 reference panel (groups + fields, collapsed, blue accent) → B1+B2 (shared renderer), with the data gap flagged in D3. §6 section-04 Edit (5 fields) → C4 stub + D2 write path. §7 open decisions → D1–D4. §8 styling tokens → CSS in B1/C1. §1 file map → File Structure table. Build order (UI-first, gated last) → Parts A–C then D deferred.

**Removed invented fields:** Change Category, Site, and Priority Critical/High/Low are never introduced (Priority/Training/BU come only from `dco-form-metadata`). ✔

**Placeholder scan:** control-helper extraction in C3 references existing markup rather than re-quoting ~600 lines — acceptable because the executor has both the current file and the prototype open; each helper's required IDs are named explicitly so `collectForm()` keeps working.

**Type/contract consistency:** `collectForm()` keys, `submit-dco` FormData keys, and the global function names (`dcoOpen`, `dcoSubmit`, `dcoMaybeClose`, `downloadAttachments`, `renderRefPanel`) are pinned in Ground Rules and reused identically across tasks.

**Known risk:** static-asset changes are only exercised by the *bundled* JAR, so per-task browser verification during C3 may require a rebuild; the plan routes definitive verification to Part E and recommends code-review + prototype-diffing between rebuilds.
