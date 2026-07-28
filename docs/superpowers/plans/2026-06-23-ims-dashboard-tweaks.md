# IMS Dashboard & Flow — Three Tweaks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) Restrict the dashboard 🔑 Unlock button to PLM IT (`isPlmAdmin`) only; (2) stop hiding pre-go-live DRRs by default and color-code them as "legacy"; (3) make every item/change/DCO number a clickable Agile link across emails, the response page, and the DCO-created success message.

**Architecture:** Small additive changes on branch `feat/ims-review-revamp`. No DCO `collectForm()` / submit-contract changes. Frontend is vanilla JS (`imsreview.js`, `ims-respond.html`, `imsreview-dco-form.js`); backend is Spring Boot Java 11 (`ImsReviewController.java`, `ImsReviewEmailService.java`) + email templates. The response page is opened from an email link (no app session), so the Agile webclient base is delivered via the `token/info` and `dco-form-metadata` JSON payloads.

**Tech Stack:** Java 11 (Corretto 11), Spring Boot, vanilla JS, IBM Plex / SanDisk palette. **No JS test harness** — "verify" means grep + reading + the post-build visual/email check. Spec: `docs/superpowers/specs/2026-06-23-ims-dashboard-tweaks-design.md`.

---

## Ground rules
1. Branch `feat/ims-review-revamp`. Commit only the files each task names. Don't touch the untracked `data/` or `.gitignore`.
2. Don't build per-task — one `mvn package` at the end (Part 4). Java edits are validated by the final compile; JS by grep + `node --check`.
3. Preserve the existing dashed-underline Agile link style: `color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;` opening `target="_blank" rel="noopener"`.
4. Fail-soft on links: if no webclient base is available client-side, render the plain (escaped) number, never a broken `<a>`.
5. End every commit body with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

| File | Responsibility | Part |
|---|---|---|
| `src/main/resources/static/imsreview.js` | Unlock button gate (→`isAdmin`); default-show-all + legacy DRR badge/accent | 1, 2 |
| `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java` | strict `isPlmAdmin` guard on `/admin/unlock-token`; add `agileWebclientUrl` to `token/info` + `dco-form-metadata` payloads | 1, 3b |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` | wrap plain number vars through `agileLinkItem`/`agileLinkChange` | 3a |
| `src/main/resources/templates/email/ims-review-*.html` | confirm number vars render linked | 3a |
| `src/main/resources/static/ims-respond.html` | client `agileLink()` helper; link summary + ref-panel numbers | 3c |
| `src/main/resources/static/imsreview-dco-form.js` | link the created-DCO number in the success message | 3d |
| `src/main/resources/static/whats-new.js` | release entry | 4 |

---

## PART 1 — Unlock button → PLM IT only

### Task 1: Frontend gate + backend guard

**Files:**
- Modify: `src/main/resources/static/imsreview.js` (~L845, the Unlock button)
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java` (`/admin/unlock-token`, ~L179-195)

- [ ] **Step 1: Locate the Unlock button.** Run:

```
grep -n "imsUnlockToken\|canSeeAdminView\|Unlock\|isAdmin" src/main/resources/static/imsreview.js | head -30
```

Expected: the Unlock button built with `_state.meta.canSeeAdminView && (r.status === 'SENT_TO_DO' || r.status === 'SENT_TO_DM')`.

- [ ] **Step 2: Narrow the gate to `isAdmin`.** In the `unlockBtn` assignment, change the leading condition from `_state.meta.canSeeAdminView` to `_state.meta.isAdmin`. Leave the status condition and everything else identical. Resulting condition:

```javascript
var unlockBtn = (_state.meta && _state.meta.isAdmin
        && (r.status === 'SENT_TO_DO' || r.status === 'SENT_TO_DM'))
    ? '<button onclick="imsUnlockToken(\'' + doc + '\')" '
      + 'title="Clear the password-attempt lockout on this doc\'s active link(s)" '
      + 'style="padding:4px 8px; font-size:11px; background:#fff; color:#5B21B6; border:1px solid #E8E6DF; border-radius:4px; margin-left:3px; cursor:pointer;">&#x1F511; Unlock</button>'
    : '';
```

> Do NOT touch `resetBtn` (it keeps `canSeeAdminView`). `isAdmin` is already in the `/api/ims-review/role` payload, stored at `_state.meta`.

- [ ] **Step 3: Tighten the backend guard.** In `ImsReviewController.java`, the `/admin/unlock-token` handler currently does `if (!hasAdminOrDccAccess(session)) return 403…`. Replace that block with a strict admin check:

```java
        if (!isLoggedIn(session)) return ResponseEntity.status(401).body(err("Login required."));
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) {
            return ResponseEntity.status(403).body(err("PLM Admin access required."));
        }
```

(Keep the rest of the method — the token/docNumber validation and `service.adminClearTokenLockouts(...)` — unchanged.)

- [ ] **Step 4: Verify.** Run:

```
grep -n "isAdmin\b" src/main/resources/static/imsreview.js | head
grep -n "PLM Admin access required\|hasAdminOrDccAccess" src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java | head
```

Expected: the Unlock button now keys off `_state.meta.isAdmin`; `/admin/unlock-token` shows the strict `isPlmAdmin` check (no longer `hasAdminOrDccAccess` in that method — confirm by reading the method).

- [ ] **Step 5: Commit.**

```bash
git add src/main/resources/static/imsreview.js src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java
git commit -m "feat(ims-review): restrict Unlock button + endpoint to PLM admins only"
```

---

## PART 2 — Legacy DRR coloring + show-all default

### Task 2: Default show-all and mark pre-go-live DRRs

**Files:**
- Modify: `src/main/resources/static/imsreview.js` (state ~L30, `renderAdminTable` row rendering, the `drrCreated` cell ~L660)

- [ ] **Step 1: Flip the default filter off.** In the `_state` initializer (~L30), change:

```javascript
        drrCreatedFilterOn: true,
```
to
```javascript
        drrCreatedFilterOn: false,
```

Leave `drrCreatedAfter: '2026-07-05'` as-is (the toggle UI and filter logic still work; they're just off by default so all DRRs show).

- [ ] **Step 2: Add the legacy helper.** Near the other small helpers (e.g. by `overdueLabel`/`fmtDate`), add:

```javascript
    // A DRR is "legacy" if it was created before the go-live anchor
    // (drrCreatedAfter). Rows with no DRR yet are never legacy.
    function isLegacyDrr(r) {
        var c = (r && r.drrCreated || '').substring(0, 10);
        return !!c && c < _state.drrCreatedAfter;
    }
```

- [ ] **Step 3: Render the legacy badge + accent.** Find the DRR Created cell in `renderAdminTable()` (the `<td>` that renders `fmtDate((r.drrCreated||'').substring(0,10))`, ~L660). Replace that single `<td>` with a version that adds the badge when legacy:

```javascript
                 + '<td style="padding:7px 10px; color:#6B7280; font-size:11px;' + (isLegacyDrr(r) ? ' box-shadow: inset 3px 0 0 #cbd5e1;' : '') + '">'
                 +   (r.drrCreated ? fmtDate((r.drrCreated || '').substring(0, 10)) : '<span style="color:#9CA3AF;">—</span>')
                 +   (isLegacyDrr(r)
                        ? ' <span title="Created before go-live (' + esc(_state.drrCreatedAfter) + ')" style="background:#eef0f3; color:#6B7280; padding:1px 6px; border-radius:8px; font-size:10px; font-weight:600; margin-left:4px;">legacy</span>'
                        : '')
                 + '</td>'
```

> Match the existing string-concatenation indentation/`+` style in that function. The accent is a 3px inset left shadow on the cell; the badge reuses the neutral pill palette (`#eef0f3`/`#6B7280`).

- [ ] **Step 4: Bump the cache-buster** for imsreview.js if the page references it with a `?v=`. Run:

```
grep -n "imsreview.js?v=" src/main/resources/static/*.html
```

If found, bump the version (e.g. `?v=20260623a`); if there's no versioned reference, skip.

- [ ] **Step 5: Verify.** Run:

```
grep -n "drrCreatedFilterOn: false\|isLegacyDrr\|>legacy<" src/main/resources/static/imsreview.js
```

Expected: default-off filter, the helper, and the badge markup present. Re-read the modified `<td>` to confirm valid concatenation (no dangling `+`/quotes).

- [ ] **Step 6: Commit.**

```bash
git add src/main/resources/static/imsreview.js
git commit -m "feat(ims-review): show all DRRs by default and tag pre-go-live ones as legacy"
```

---

## PART 3 — Hyperlink all item/change/DCO numbers

### Task 3a: Link the plain numbers in emails

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java`
- Possibly modify: `src/main/resources/templates/email/ims-review-dcc-needs-change.html`, `ims-review-dm.html`, `ims-review-dco-stakeholder-notify.html`, `ims-review-dm-approved.html`, `ims-review-owner-reassigned.html`, `ims-review-dcc-need-help.html`

- [ ] **Step 1: Audit which number vars are plain vs linked.** Run:

```
grep -n "v.put(\"docNumber\"\|v.put(\"drrNumber\"\|put(\"dcoNumber\"\|vars.put(\"docNumber\"\|vars.put(\"drrNumber\"\|agileLinkItem\|agileLinkChange" src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java
```

Identify every place a `docNumber`/`drrNumber`/`dcoNumber` template var is `put(...)` as a raw string rather than via `agileLinkItem(...)` / `agileLinkChange(...)`.

- [ ] **Step 2: Wrap the DCC needs-change vars.** The needs-change payload puts plain values:

```java
        v.put("docNumber", docNumber);
        v.put("drrNumber", nvl(drrNumber));
```
Change to:
```java
        v.put("docNumber", agileLinkItem(docNumber));
        v.put("drrNumber", agileLinkChange(drrNumber));
```

(`agileLinkItem`/`agileLinkChange` already return `""` for null/empty, so this is null-safe and matches the DO email's treatment.)

- [ ] **Step 3: Ensure the DM email shows linked numbers.** Read `payloadForDmEmail(...)` and `templates/email/ims-review-dm.html`. The DM payload is built from the DO payload (which already links `docNumber`/`drrNumber`). Confirm the DM template uses the same `${docNumber}`/`${drrNumber}` vars and that the payload does NOT re-`put` plain values over them. If it does re-put plain, wrap with the helpers; if it inherits the linked DO values, no change is needed — state which in your report.

- [ ] **Step 4: Audit the remaining IMS templates.** For each of `ims-review-dco-stakeholder-notify.html`, `ims-review-dm-approved.html`, `ims-review-owner-reassigned.html`, `ims-review-dcc-need-help.html`: grep the template for `${docNumber}`/`${drrNumber}`/`${dcoNumber}` and confirm the corresponding `put(...)` in the service wraps them via the helpers. Wrap any that are plain. (Stakeholder-notify should already wrap all three — verify, don't double-wrap.)

- [ ] **Step 5: Verify no double-wrapping.** A value must be wrapped exactly once. `agileLinkItem`/`agileLinkChange` produce a full `<a>…</a>`; never pass an already-wrapped value back through them. Re-read each changed `put` to confirm the argument is the raw number string.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java src/main/resources/templates/email/
git commit -m "feat(ims-review): hyperlink doc/DRR/DCO numbers in all IMS emails"
```

### Task 3b: Expose the Agile webclient base to the response page

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java` (the `token/info` builder `describeToken`, and the `dco-form-metadata` / `dco-form-preview-metadata` builders)

- [ ] **Step 1: Find the webclient base in scope.** Run:

```
grep -n "agile-webclient-url\|agileWebclientUrl\|describeToken\|dco-form-metadata\|dcoFormMetadata\|preview-metadata" src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java | head
```

The base URL lives in `ImsReviewEmailService` as `agileWebclientUrl` (`@Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")`). Determine how the controller can read the same value — either it already injects the email service / a config value, or add an `@Value` field to the controller:

```java
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.agile-webclient-url:https://plm.sandisk.com/Agile}")
    private String agileWebclientUrl;
```

- [ ] **Step 2: Add `agileWebclientUrl` to the token/info payload.** In `describeToken(...)` where the response `Map` is assembled (alongside `docNumber`, `drrNumber`, etc.), add:

```java
        out.put("agileWebclientUrl", agileWebclientUrl);
```

(Use the actual map variable name in that method.)

- [ ] **Step 3: Add it to the dco-form-metadata payload(s).** In the `dco-form-metadata` and `dco-form-preview-metadata` response builders, add the same key to the returned map:

```java
        out.put("agileWebclientUrl", agileWebclientUrl);
```

- [ ] **Step 4: Verify.** Run:

```
grep -n "agileWebclientUrl" src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java
```

Expected: the `@Value` field (if added) + at least 2 `out.put("agileWebclientUrl", ...)` (token/info and the metadata endpoint(s)).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java
git commit -m "feat(ims-review): expose agileWebclientUrl to token/info + dco-form-metadata"
```

### Task 3c: Link numbers on the response page

**Files:**
- Modify: `src/main/resources/static/ims-respond.html` (IIFE: add `agileLink` helper; summary table; `refRow`/`renderRefPanel`)

- [ ] **Step 1: Add the client helper** (top-level in the IIFE, near `esc`):

```javascript
  // Build an Agile webclient deep-link for a number; fail-soft to plain text
  // when no base URL is available (the response page has no app session, so the
  // base arrives via token/info → STATE.info.agileWebclientUrl).
  function agileBase() {
    return (STATE.info && STATE.info.agileWebclientUrl) || window.AGILE_WEBCLIENT_URL || '';
  }
  function agileLink(number, kind) { // kind: 'item' | 'change'
    if (!number) return '';
    var base = agileBase();
    if (!base) return esc(number);
    var href = kind === 'item'
      ? base + '/object/Part/' + encodeURIComponent(number) + '/tab/13'
      : base + '/object/' + encodeURIComponent((String(number).split('-')[0] || '')) + '/' + encodeURIComponent(number);
    return '<a href="' + href + '" target="_blank" rel="noopener" '
         + 'style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(number) + '</a>';
  }
```

- [ ] **Step 2: Link the summary table.** In `renderForm()`, the summary builds the **Number** and **Related DRR** rows with `esc(info.docNumber)` / `esc(info.drrNumber)`. Change those two values to links:

```javascript
    meta += '<div class="docmeta-row"><div class="k">Number</div><div class="v docno">' + agileLink(info.docNumber, 'item') + '</div></div>';
```
and
```javascript
      meta += '<div class="docmeta-row"><div class="k">Related DRR</div><div class="v">' + agileLink(info.drrNumber, 'change') + '</div></div>';
```

(Leave Description and Rev/Lifecycle as plain `esc()`.)

- [ ] **Step 3: Link the reference-panel doc number.** `refRow(label, val, kind)` currently escapes `val`. Extend it so the `'docno'` kind renders an item link when a value is present:

```javascript
  function refRow(label, val, kind) {
    var has = !(val == null || val === '');
    var inner;
    if (!has) inner = '—';
    else if (kind === 'docno') inner = agileLink(val, 'item');
    else if (kind === 'change') inner = agileLink(val, 'change');
    else inner = esc(val);
    var cls = 'co-ref2-v' + (!has ? ' empty' : (kind === 'docno' ? ' docno' : ''));
    return '<div class="co-ref2-row"><div class="co-ref2-k">' + esc(label) +
           '</div><div class="' + cls + '">' + inner + '</div></div>';
  }
```

Then in `renderRefPanel`, the **Number** row already passes `'docno'` so it becomes a link automatically. For **Old Document Number**, leave as plain text unless it is known to be a valid Agile item number — keep it plain (out of scope to guess). (No DRR field exists in the ref panel today.)

- [ ] **Step 4: Bump the cache-buster.** In `ims-respond.html`, the page already loads `imsreview-dco-form.js?v=20260623a`. If `ims-respond.html` itself is referenced with a `?v=` anywhere, bump it; otherwise the HTML is served fresh on each load. (No action usually needed for the HTML itself.)

- [ ] **Step 5: Verify.** Run:

```
grep -n "function agileLink\|agileLink(info.docNumber\|agileLink(info.drrNumber\|kind === 'docno'" src/main/resources/static/ims-respond.html
```

Expected: helper defined; summary Number + DRR linked; refRow handles `'docno'`. Confirm no other `esc(info.docNumber)`/`esc(info.drrNumber)` remain in the summary rows.

- [ ] **Step 6: Commit.**

```bash
git add src/main/resources/static/ims-respond.html
git commit -m "feat(ims-review): hyperlink doc/DRR numbers on the response page + reference panel"
```

### Task 3d: Link the created-DCO number in the success message

**Files:**
- Modify: `src/main/resources/static/imsreview-dco-form.js` (the DCO success rendering, ~L1288-1310)

- [ ] **Step 1: Add a DCO-form agileLink helper** (near the top helpers in the IIFE). The DCO form already fetched metadata; read the base from `DCO.meta.agileWebclientUrl` (added in Task 3b) or the global:

```javascript
  function dcoAgileChangeLink(number) {
    if (!number) return '';
    var base = (DCO.meta && DCO.meta.agileWebclientUrl) || window.AGILE_WEBCLIENT_URL || '';
    if (!base) return esc(number);
    var href = base + '/object/' + encodeURIComponent((String(number).split('-')[0] || '')) + '/' + encodeURIComponent(number);
    return '<a href="' + href + '" target="_blank" rel="noopener" '
         + 'style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(number) + '</a>';
  }
```

(Confirm an `esc()` helper exists in this file; if it's named differently, use the file's own escaper.)

- [ ] **Step 2: Convert the success message to linked HTML.** Find the success rendering that does `successMsgEl.textContent = 'DCO ' + dcoNumber + ' created…'`. Switch to `innerHTML`, linking the DCO number and **escaping** the other interpolated parts:

```javascript
      var dcoNumber = resp.body.dcoNumber || '(unknown)';
      var msg = 'DCO ' + dcoAgileChangeLink(dcoNumber) + ' created and submitted.';
      if (attachedCount != null) msg += ' ' + esc(String(attachedCount)) + ' file(s) attached.';
      if (resp.body.stakeholdersNotified != null) msg += ' ' + esc(String(resp.body.stakeholdersNotified)) + ' stakeholder(s) notified.';
      successMsgEl.innerHTML = msg;
```

- [ ] **Step 3: Do the same for the auto-submit-blocked line.** The `line1.textContent = 'DCO ' + dcoNumber + ' was created…'` becomes:

```javascript
      line1.innerHTML = 'DCO ' + dcoAgileChangeLink(dcoNumber) + ' was created' /* + rest of the existing copy, esc()'d if it interpolates data */;
```

Preserve the exact remaining copy; only swap `dcoNumber` for the link and ensure any interpolated data is `esc()`'d now that it's `innerHTML`.

- [ ] **Step 4: Bump the script cache-buster.** In `ims-respond.html`, change `imsreview-dco-form.js?v=20260623a` → `?v=20260623b` so the linked success message ships.

- [ ] **Step 5: Verify.** Run:

```
grep -n "dcoAgileChangeLink\|successMsgEl.innerHTML\|line1.innerHTML" src/main/resources/static/imsreview-dco-form.js
grep -n "imsreview-dco-form.js?v=20260623b" src/main/resources/static/ims-respond.html
node --check src/main/resources/static/imsreview-dco-form.js
```

Expected: helper + innerHTML assignments present; cache-buster bumped; `node --check` passes (SYNTAX_OK).

- [ ] **Step 6: Commit.**

```bash
git add src/main/resources/static/imsreview-dco-form.js src/main/resources/static/ims-respond.html
git commit -m "feat(ims-review): hyperlink the created DCO number in the success message"
```

---

## PART 4 — Changelog, build, stage

### Task 4: Release

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Prepend a What's New entry** at the top of `WHATS_NEW_RELEASES` (match the file's `{date, title, items:[{badge,text,admin?}]}` format):

```javascript
    {
        date: 'June 23, 2026',
        title: 'IMS Review &middot; PLM-IT-only Unlock, legacy DRR tagging, and clickable numbers',
        items: [
            { badge: 'improve', text: '<strong>Older DRRs are now visible and clearly tagged.</strong> The dashboard shows all DRRs by default; those created before go-live (2026-07-05) are marked with a muted <em>legacy</em> badge so you can tell them apart at a glance.' },
            { badge: 'improve', text: '<strong>Item, change, DRR, and DCO numbers are now clickable everywhere.</strong> In the review emails, the response page, the document-details panel, and the change-order confirmation, every number links straight to the object in Agile PLM.' },
            { badge: 'fix', admin: true, text: '<strong>Implementation:</strong> Unlock button + <code>/admin/unlock-token</code> restricted to <code>isPlmAdmin</code> (was admin-or-ims-review-grant). <code>imsreview.js</code> defaults <code>drrCreatedFilterOn:false</code> + <code>isLegacyDrr</code> badge. Email number vars routed through <code>agileLinkItem/agileLinkChange</code>; <code>agileWebclientUrl</code> added to <code>token/info</code> + <code>dco-form-metadata</code> so the response page (<code>agileLink</code>) and DCO success message build deep-links client-side, fail-soft to plain text. Cache-bust <code>imsreview-dco-form.js?v=20260623b</code>.' }
        ]
    },
```

- [ ] **Step 2: Commit.**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): IMS dashboard tweaks release entry"
```

- [ ] **Step 3: Build.**

```
cd ~/git/plm-field-tracker && JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn -q -DskipTests package
```

Expected: BUILD SUCCESS, `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 4: Verify the JAR bundled the changes.** Run:

```
unzip -p target/plm-field-tracker-1.0.1.jar BOOT-INF/classes/static/imsreview.js | grep -c "isLegacyDrr"
unzip -p target/plm-field-tracker-1.0.1.jar BOOT-INF/classes/static/ims-respond.html | grep -c "function agileLink"
```

Expected: both > 0.

- [ ] **Step 5: Copy to local + stage to QSS** (staging only — never the live root; verify size parity).

```
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-eq-agliqss/plm-toolkit/staging/plm-field-tracker-1.0.1.jar
```

If `/Volumes/uls-eq-agliqss/` is not mounted, stop and tell Vikas. Verify both copies' `stat -f "%z"` equal the `target/` size.

- [ ] **Step 6: Hand off.** Tell Vikas the staged JAR is ready and list the live checks: Unlock hidden for non-admins (visible for `pdl-plm-admin`); dashboard shows all DRRs with legacy badges on pre-2026-07-05 rows; numbers click through to Agile in the DM + DCC emails, the response page, and the DCO success message.

---

## Self-Review (against the spec)

**Spec coverage:** Feature 1 → Task 1 (frontend gate + backend guard, `isPlmAdmin`, Unlock-only). Feature 2 → Task 2 (default-off filter + `isLegacyDrr` badge/accent, neutral palette, no-DRR rows unmarked). Feature 3a → Task 3a (email vars wrapped). 3b → Task 3b (`agileWebclientUrl` in token/info + dco-form-metadata). 3c → Task 3c (`agileLink` helper, summary + ref-panel). 3d → Task 3d (DCO success innerHTML link + XSS escaping). Release → Task 4.

**Placeholder scan:** Task 3a Step 3 and 3d Step 3 ask the implementer to verify-and-adapt against the real code (DM payload inheritance; exact success-line copy) rather than quoting code that may not match — this is deliberate (the exact surrounding text must be read live), with the required outcome stated explicitly. No "TBD"/"handle edge cases" placeholders.

**Type/name consistency:** `agileLink(number, kind)` (kinds `'item'`/`'change'`) and `agileBase()` in `ims-respond.html`; `dcoAgileChangeLink(number)` in `imsreview-dco-form.js`; `isLegacyDrr(r)` in `imsreview.js`; `agileWebclientUrl` key consistent across backend payloads and both client readers (`STATE.info.agileWebclientUrl` / `DCO.meta.agileWebclientUrl`). Link style string identical everywhere.

**Risk:** moving the DCO success node from `textContent`→`innerHTML` requires escaping the other interpolated values — called out explicitly in Task 3d Step 2/3.
