# IMS Dashboard / DCO Form Revamp #2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply Vikas Singh's 2026-06-30 feedback to the IMS Dashboard, the Create-DCO popup, and the DO/DM review emails (8 actionable items).

**Architecture:** Toolkit-only (`plm-field-tracker`). Backend adds a `documentStyle` + `drrExempt` signal to each dashboard row and a training-doc URL to config; the pure classifier gains a `no_drr_required` bucket; the dashboard JS reorders tiles and renders an exempt tag; the DCO-form JS does the renames, makes Observers optional, slims the OBS form, links the training doc, and drops a redundant placeholder; the email service drops the footer logo and ships an Outlook-safe button.

**Tech Stack:** Java 11 (Spring Boot), Oracle (Agile schema, read-only), vanilla JS (UMD classifier with `node --test`), Thymeleaf-ish string templates for email.

**Design doc:** `docs/superpowers/specs/2026-06-30-ims-review-revamp2-design.md`

**Conventions for every commit in this plan:**
- Commit only the files named in the task. Trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Build JDK: Corretto 11 (`reference_build_jdk` — toolkit is Java 11, NOT the 1.8 PX rule).
- Run JS tests with `node --test test/<file>.test.js` from repo root.

---

## Task B1: Backend — `documentStyle` + `drrExempt` on each dashboard row

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` (`DocRow` ~384, SQL ~2785-2839, row read ~2854-2863, row map ~481-574)
- Modify: `src/main/resources/application.properties` (after the `app.ims-review.*` block ~331)
- Test: `src/test/java/com/sandisk/plm/tracker/service/ImsReviewExemptTest.java` (new)

### Discovery sub-step (do FIRST, against live Agile — the Mac can reach the DB read-only)

The exempt rule keys off the document **style** (`Constants.DOC_STYLE` in the PX). Its attid is in the batch server's `AuditDocuments.properties` (not in git) and the style is sparsely populated. Resolve the column before writing the SQL:

- [ ] **Step 0a: Try to find the style cell.** Run these (connection `agile_prod` via the universal-db MCP, or `sqlplus`):
  ```sql
  -- (a) Does any 9141 doc carry a style-like flex value? (single-value lists only)
  SELECT af.attid, nt.name attr_label, vt.name value_name, COUNT(*) c
  FROM agile.agile_flex af
  JOIN agile.item i ON i.id=af.id AND i.subclass=9141
  JOIN agile.nodetable vt ON vt.id = TO_NUMBER(REGEXP_SUBSTR(af.text,'\d+'))
  LEFT JOIN agile.nodetable nt ON nt.id=af.attid
  WHERE af.text LIKE ',%,' AND REGEXP_LIKE(af.text,'^,\d+,$')
    AND (vt.name LIKE '%Spec%' OR vt.name LIKE '%Drawing%' OR vt.name LIKE '%Record%')
  GROUP BY af.attid, nt.name, vt.name ORDER BY c DESC;
  -- (b) Scan page_three free-text columns for a style word on a due doc set:
  SELECT 'TEXT'||lvl col, val FROM (
    SELECT p3.text31 t31,p3.text32 t32,p3.text33 t33,p3.text34 t34,p3.text35 t35 FROM agile.item i
    JOIN agile.page_three p3 ON p3.id=i.id WHERE i.subclass=9141 AND p3.date32 IS NOT NULL AND ROWNUM<=200
  ) UNPIVOT (val FOR lvl IN (t31 AS '31',t32 AS '32',t33 AS '33',t34 AS '34',t35 AS '35'))
  WHERE val IS NOT NULL AND ROWNUM<=20;
  ```
- [ ] **Step 0b: Record the result in this task.** If a column is found (e.g. `p2.list07` or `af` attid N), use it as `document_style` below. **If nothing resolves, set `document_style` to a literal `NULL`** — the feature ships fail-open (no rows tagged) and a `// TODO(style-attid): resolve from AuditDocuments.properties DOC_STYLE` comment is left at the SQL. Do not block the rest of the plan on this.

### Implementation (TDD on the exempt predicate; SQL wiring verified manually)

- [ ] **Step 1: Write the failing test for the exempt predicate.**

```java
// src/test/java/com/sandisk/plm/tracker/service/ImsReviewExemptTest.java
package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ImsReviewExemptTest {
    private final java.util.List<String> styles = java.util.Arrays.asList(
        "Specs", "Drawing", "Record - for dead document - buy off report");

    @Test void matchesSpecsCaseAndSpaceInsensitive() {
        assertTrue(ImsReviewService.isDrrExempt("D001 Specs", styles));
        assertTrue(ImsReviewService.isDrrExempt("d025productoutlinedrawing", styles));
        assertTrue(ImsReviewService.isDrrExempt("Record  -  for dead document - buy off report", styles));
    }
    @Test void nonExemptForOrdinaryStyles() {
        assertFalse(ImsReviewService.isDrrExempt("Test Plan", styles));
        assertFalse(ImsReviewService.isDrrExempt("", styles));
        assertFalse(ImsReviewService.isDrrExempt(null, styles));
    }
    @Test void emptyConfigNeverExempt() {
        assertFalse(ImsReviewService.isDrrExempt("Specs", java.util.Collections.emptyList()));
    }
}
```

- [ ] **Step 2: Run it — verify it fails to compile (method missing).**

Run: `mvn -q -Dtest=ImsReviewExemptTest test`
Expected: compile error `cannot find symbol: method isDrrExempt`.

- [ ] **Step 3: Add the predicate + config field.** In `ImsReviewService.java`:

Add the static helper (mirrors `DocumentReview.java`: `docStyle.replace(" ","_").contains(token)`, made case-insensitive and whitespace-collapsed so config tokens with spaces still match):
```java
/** True when a document's style marks it as never needing a DRR — mirrors
 *  DocumentReview.java (Specs / Drawing / Record-for-dead-document). Match is
 *  case-insensitive and collapses runs of whitespace/underscores so the
 *  config token "Record - for dead document - buy off report" matches the
 *  Agile value regardless of spacing. Fail-open: null/blank style => false. */
public static boolean isDrrExempt(String style, java.util.List<String> exemptTokens) {
    if (style == null || exemptTokens == null || exemptTokens.isEmpty()) return false;
    String norm = style.toLowerCase().replaceAll("[\\s_]+", " ").trim();
    for (String tok : exemptTokens) {
        if (tok == null || tok.trim().isEmpty()) continue;
        String t = tok.toLowerCase().replaceAll("[\\s_]+", " ").trim();
        if (norm.contains(t)) return true;
    }
    return false;
}
```
Add the injected config near the other `@Value` fields:
```java
@org.springframework.beans.factory.annotation.Value("${app.ims-review.drr-exempt-styles:Specs,Drawing,Record - for dead document - buy off report}")
private String drrExemptStylesRaw;

private java.util.List<String> drrExemptStyles() {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String s : (drrExemptStylesRaw == null ? "" : drrExemptStylesRaw).split(",")) {
        if (!s.trim().isEmpty()) out.add(s.trim());
    }
    return out;
}
```

- [ ] **Step 4: Run the test — verify PASS.**

Run: `mvn -q -Dtest=ImsReviewExemptTest test`
Expected: 3 tests pass.

- [ ] **Step 5: Add `documentStyle` to `DocRow` + SQL + read.**

In `DocRow` (~385) add the field:
```java
public String docNumber, drrNumber, description, lifecyclePhase, rev, documentType, documentStyle;
```
In `pullDocsDueWithin` SQL `SELECT` list (~2828, after `sub.name AS document_type,`) add the resolved style column from Step 0b (example shows the fail-open NULL form — replace `NULL` with the real column if found):
```java
"       sub.name AS document_type, " +
"       CAST(NULL AS VARCHAR2(150)) AS document_style, /* TODO(style-attid): resolve from AuditDocuments.properties DOC_STYLE */ " +
```
In the row-hydration block (~2859, after `x.documentType = ...`) add:
```java
x.documentStyle = nvl(rs.getString("document_style"));
```

- [ ] **Step 6: Emit `documentStyle` + `drrExempt` in the row map.** In `dataForAdmin` row build (~486, after `row.put("documentType", d.documentType);`) add:
```java
row.put("documentStyle", d.documentStyle);
row.put("drrExempt", isDrrExempt(d.documentStyle, drrExemptStyles()));
```
> Also mirror this into the DO/DM card payload if that path builds its own row map (search `row.put("documentType"` — there is one site at ~486 and the card builder at ~960 uses `d.documentType`; add `drrExempt` only where a Send-New-DRR action could render, i.e. the admin path is sufficient — the card view has no Send New DRR button).

- [ ] **Step 7: Add the config key (documented, with the real seeded list).** Append to `application.properties` after the `app.ims-review.*` block:
```properties
# Document styles that never require a DRR (mirrors DocumentReview.java). A dash-
# board row whose Document Style contains any of these (case/space-insensitive)
# shows "No DRR required" instead of the Send New DRR button. Comma-separated.
app.ims-review.drr-exempt-styles=Specs,Drawing,Record - for dead document - buy off report
```

- [ ] **Step 8: Full module test + commit.**

Run: `mvn -q -Dtest=ImsReviewExemptTest test`
Expected: PASS.
```bash
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java \
        src/test/java/com/sandisk/plm/tracker/service/ImsReviewExemptTest.java \
        src/main/resources/application.properties
git commit -m "feat(ims): expose documentStyle + drrExempt on dashboard rows (mirror DocumentReview exemption)"
```

---

## Task B2: Backend — training-doc URL config + expose to UI + DO email link

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` (admin data `out` map ~597)
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` (DO email vars ~595-611)
- Modify: `src/main/resources/templates/email/ims-review-do.html`

- [ ] **Step 1: Add the config key.** Append to `application.properties`:
```properties
# Self-study training material (Bibi's SharePoint deck) linked from the DCO form
# (next to Training Requirement) and the DO review email.
app.ims-review.training-doc-url=https://corpsd-my.sharepoint.com/:p:/g/personal/bibianita_kolam_sandisk_com/EZqsQ8YhxE1GgxapFr1_LUEBUJ0xuD5LHYTUmiFhqwmtTw?e=x9Vqxf
```
> Use the trimmed share URL above (drop the `&xsdata=…&ovuser=…` tracking query — it is per-open and not needed to resolve the file).

- [ ] **Step 2: Inject into `ImsReviewService` and add to admin data.** Add a `@Value("${app.ims-review.training-doc-url:}")` field `trainingDocUrl`, and in `dataForAdmin`'s `out` map (~597) add:
```java
out.put("trainingDocUrl", trainingDocUrl == null ? "" : trainingDocUrl);
```

- [ ] **Step 3: Add the link to the DO email.** In `ImsReviewEmailService` add a `@Value("${app.ims-review.training-doc-url:}")` field `trainingDocUrl`; where the DO email vars are stamped (~595-611, near `logoUrl`), add:
```java
p.vars.putIfAbsent("trainingDocUrl", trainingDocUrl == null ? "" : trainingDocUrl);
```
In `templates/email/ims-review-do.html`, below the response button block, add (only renders when set):
```html
<!--/* training-doc link */-->
<tr><td style="padding:6px 0 0; font-size:12px; color:#6B7280;" th:if="${trainingDocUrl != null and trainingDocUrl != ''}">
  New to document reviews? <a th:href="${trainingDocUrl}" target="_blank" style="color:#4a6fa5;">View the training guide</a>.
</td></tr>
```
> Match the template's actual syntax — if it uses `${var}` string substitution rather than Thymeleaf `th:`, mirror the existing pattern in that file (grep the file for `${responseUrl}` and copy its style; render the row unconditionally if there is no conditional mechanism, since the URL is always configured).

- [ ] **Step 4: Manual verify + commit.** (Email rendering verified in Task Z local smoke.)
```bash
git add src/main/resources/application.properties \
        src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java \
        src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java \
        src/main/resources/templates/email/ims-review-do.html
git commit -m "feat(ims): training-doc URL config, surfaced in DCO form data + DO email"
```

---

## Task B3: Email — remove footer logo + Outlook-Classic button

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` (logo `<img>` at ~1509, 1548, 1613, 1652)
- Modify: `src/main/resources/templates/email/ims-review-do.html` (the `${responseUrl}` button)

- [ ] **Step 1: Remove the logo `<img>` from all four footers.** Each site is:
```java
+ "<img src='" + esc(toolkitBaseUrl) + "/sandisk-logo-red.png' alt='SanDisk' style='height:14px; vertical-align:middle; border:0;'>"
```
Delete the four `+ "<img …sandisk-logo-red.png… >"` fragments. If a fragment sits between a leading text node and a trailing one (e.g. a "sandisk" pill row), keep the surrounding text pill and remove only the `<img>` concatenation so the string still compiles. After editing, grep to confirm zero remain:
```
grep -n "sandisk-logo-red.png" src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java
```
Expected: no matches. (The asset file may stay in `static/`; it is simply no longer referenced.)

- [ ] **Step 2: Make the "Submit your response" button Outlook-Classic safe.** In `ims-review-do.html`, locate the response button (`grep -n 'responseUrl' src/main/resources/templates/email/ims-review-do.html`). Replace the single `<a>` button with the bulletproof (VML) pattern — Outlook renders the `<v:roundrect>`, every other client renders the `<a>`:
```html
<!--[if mso]>
<v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="urn:schemas-microsoft-com:office:word"
  href="${responseUrl}" style="height:44px;v-text-anchor:middle;width:230px;" arcsize="14%" strokecolor="#4a6fa5" fillcolor="#4a6fa5">
  <w:anchorlock/>
  <center style="color:#ffffff;font-family:'Segoe UI',Arial,sans-serif;font-size:14px;font-weight:bold;">Submit your response</center>
</v:roundrect>
<![endif]-->
<!--[if !mso]><!-- -->
<a href="${responseUrl}" target="_blank"
   style="display:inline-block;background:#4a6fa5;color:#ffffff;text-decoration:none;padding:12px 26px;border-radius:6px;font-weight:600;font-size:14px;font-family:'Segoe UI',Arial,sans-serif;">Submit your response</a>
<!--<![endif]-->
```
> Preserve the template's actual variable token for the URL (it may be `${responseUrl}` or a Thymeleaf `th:href` — keep whatever the file already uses; only the markup pattern changes). Keep the button inside its existing centering `<td align="center">`.

- [ ] **Step 3: Commit.**
```bash
git add src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java \
        src/main/resources/templates/email/ims-review-do.html
git commit -m "fix(ims-email): drop footer logo; Outlook-Classic bulletproof response button"
```

---

## Task F1: Classifier — `no_drr_required` bucket for exempt rows

**Files:**
- Modify: `src/main/resources/static/imsreview-classify.js` (`imsClassifyTile` ~49)
- Test: `test/imsreview-classify.test.js` (existing — add cases)

- [ ] **Step 1: Add the failing test.** Append to `test/imsreview-classify.test.js`:
```javascript
test('exempt doc with no DRR -> need_drr.no_drr_required', () => {
  const c = ImsClassify.imsClassifyTile({ hasDrr: false, drrExempt: true });
  assert.deepEqual(c, { group: 'need_drr', tile: 'no_drr_required' });
});
test('non-exempt doc with no DRR still -> need_drr.drr_missing', () => {
  const c = ImsClassify.imsClassifyTile({ hasDrr: false, drrExempt: false });
  assert.deepEqual(c, { group: 'need_drr', tile: 'drr_missing' });
});
test('exempt flag ignored once a DRR exists', () => {
  const c = ImsClassify.imsClassifyTile({ hasDrr: true, drrExempt: true, status: 'SENT_TO_DO' });
  assert.equal(c.group, 'new');
});
```

- [ ] **Step 2: Run — verify the first two fail.**

Run: `node --test test/imsreview-classify.test.js`
Expected: FAIL (exempt returns `drr_missing`).

- [ ] **Step 3: Implement.** In `imsClassifyTile`, change the no-DRR early return (l.50) to honor the exempt flag:
```javascript
if (!hasDrrOf(r)) {
    return r.drrExempt
        ? { group: 'need_drr', tile: 'no_drr_required' }
        : { group: 'need_drr', tile: 'drr_missing' };
}
```

- [ ] **Step 4: Run — verify PASS (all existing + new).**

Run: `node --test test/imsreview-classify.test.js`
Expected: all pass.

- [ ] **Step 5: Commit.**
```bash
git add src/main/resources/static/imsreview-classify.js test/imsreview-classify.test.js
git commit -m "feat(ims): classify exempt docs into need_drr.no_drr_required"
```

---

## Task F2: Dashboard — tile reorder (#8), exempt tile + tag (#1)

**Files:**
- Modify: `src/main/resources/static/imsreview.js` (`TILE_GROUPS` ~393-408; `segmentActionHtml` ~697-716; cache-bust tag in `index.html`)

- [ ] **Step 1: Reorder the sub-tiles in `TILE_GROUPS` and add the exempt tile.** Replace the `new` and `legacy` `tiles` arrays so the order matches the spec, and add the exempt tile to `need_drr`:
```javascript
{ group: 'new', label: 'New DRR', hint: 'after go-live / via dashboard', tiles: [
    { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB' },
    { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'awaiting owner response' },
    { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' },
    { tile: 'need_help',        label: 'Need Help',        color: '#5B21B6', sub: 'owner asked for help' },
    { tile: 'closed',           label: 'Closed',           color: '#0F1720', sub: 'DRR closed' }
] },
{ group: 'legacy', label: 'Legacy DRR', hint: 'via old process', tiles: [
    { tile: 'in_process',       label: 'In Process',       color: '#1F8A4C', sub: 'DRR/DCO at Submit / CCB' },
    { tile: 'pending_response', label: 'Pending Response', color: '#C7801B', sub: 'DRR still at Pending' },
    { tile: 'need_owner',       label: 'Need Owner',       color: '#B8342B', sub: 'all owners inactive' }
] },
{ group: 'need_drr', label: 'Need DRR', hint: 'IMS doc due, no DRR', tiles: [
    { tile: 'drr_missing',      label: 'DRR Missing',      color: '#6B7280', sub: 'trigger a new DRR' },
    { tile: 'no_drr_required',  label: 'No DRR Required',  color: '#9aa1ab', sub: 'exempt doc style' }
] },
```

- [ ] **Step 2: Render the exempt tag instead of the Send New DRR button.** In `segmentActionHtml` (~697), at the top of the `!hasDrr` branch (before the `hasValidOwner` check that emits the Send New DRR button), short-circuit exempt rows:
```javascript
if (r.drrExempt) {
    return '<span style="display:inline-block; padding:2px 9px; font-size:11px; background:#eef0f2; color:#6B7280; border:1px solid #E8E6DF; border-radius:10px;">No DRR required</span>';
}
```
> Place this so it runs only on the no-DRR path (exempt is meaningless once a DRR exists). If `segmentActionHtml` branches on `imsClassifyTile(...)`/`hasDrr`, put the guard inside the no-DRR branch.

- [ ] **Step 3: Bump the cache-bust query on the script tag.** In `src/main/resources/static/index.html`, find `imsreview.js?v=` and `imsreview-classify.js?v=` and bump to `?v=20260630a` (grep both; bump every IMS-review static asset touched this round, incl. `imsreview-dco-form.js` for Task F3).

- [ ] **Step 4: Re-run the classifier tests (sanity) + manual check deferred to Task Z.**

Run: `node --test test/imsreview-classify.test.js`
Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add src/main/resources/static/imsreview.js src/main/resources/static/index.html
git commit -m "feat(ims): reorder sub-tiles (In Process first); No-DRR-required tile + row tag"
```

---

## Task F3: DCO form — renames (#3), Observers optional (#4), OBS slim docs (#5), training link (#6), placeholder removal (#10)

**Files:**
- Modify: `src/main/resources/static/imsreview-dco-form.js`

- [ ] **Step 1: SDSM renames (#3).**
  - Business Unit label (l.354): change the `coRow('Business Unit', …)` first arg to `'SDSM Business Unit'`.
  - §04 header (l.369): change `coSection('04', 'IMS Document — Edit', …)` second arg to `'SDSM IMS Document — Edit'`.

- [ ] **Step 2: Observers optional (#4).**
  - Render (l.366): `coRow('Observers', true, typeaheadHtml('observers'))` → change the `true` (required flag) to `false`.
  - Validation (l.772): remove the block
    ```javascript
    if (!DCO.selectedUsers.observers.length) m.push('observer');
    ```

- [ ] **Step 3: OBS slimmed Documents section (#5).** OBS currently drops §05 entirely (l.399 `isObs ? '' : (sec04 + sec05)`). Build an OBS-only documents section with Final + Stakeholder (no Redline):
  - Add, just after `sec05` is defined (~389):
    ```javascript
    // OBS variant of §05: Final + Stakeholder only, no Redline (per Vikas Singh 2026-06-30)
    var sec05Obs = coSection('05', 'Documents',
        coRow('Final Version', true, fslotHtml('Final Version',
            'The finished document with changes accepted', 'final'))
      + coRow('Stakeholder Notification', true,
            mfslotHtml('Email Copy',
                'Upload the email / sign-off proving stakeholders were notified (add one or more)', 'email')
          + '<div style="margin-top:8px;">' + stakeholderControlHtml(f.notifyStakeholders) + '</div>',
            null,
            'Type 2+ chars to search the SanDisk directory, or paste emails comma-/newline-separated. Each address is emailed the DCO number, a signed PDF, and a link to view it in Agile.'),
        'renamed with the ' + esc(docNo) + ' number automatically');
    ```
  - Change the assembly (l.399) so OBS shows the slim docs section (still hides §04):
    ```javascript
    refPanel + sec01 + sec02 + sec03 + (isObs ? sec05Obs : (sec04 + sec05)) + sec06 + '<div id="dcoBanner"></div>';
    ```
  - Submit-collect gate (l.773-774): today `if (DCO.action !== 'OBS')` skips ALL file collection. Change so OBS still collects Final + Stakeholder (skips only Redline). Replace the gate with:
    ```javascript
    // Redline is collected for non-OBS only; Final + Stakeholder always collected.
    if (DCO.action !== 'OBS' && DCO.files.redline) fd.append('file_redline', DCO.files.redline);
    ```
    and ensure the Final/email/notify appends run for OBS too (move them outside the `!== 'OBS'` block if they are inside it — read l.773-800 and lift the Final + email + notify appends out so they execute for both; keep Redline inside the non-OBS guard).
  - OBS validation: ensure `missingFields` for OBS requires Final + Stakeholder but NOT Redline. At l.775-777 the redline/final/stakeholder requirements currently sit under `if (DCO.action !== 'OBS')`. Restructure:
    ```javascript
    if (DCO.action !== 'OBS' && !DCO.files.redline) m.push('redline version');
    if (!DCO.files.final) m.push('final version');
    if (!(DCO.files.email && DCO.files.email.length) && !f.notifyStakeholders.trim())
        m.push('stakeholder notification (file or email addresses)');
    ```
    (The Final + Stakeholder checks now apply to OBS as well; only Redline is gated out.)

- [ ] **Step 4: Training-doc link (#6, form side).** Next to the Training Requirement coRow (l.347-349), append a link line. The URL comes from the admin data payload (`trainingDocUrl`, Task B2). Stash it where the form can read it — `imsreview.js` already holds the admin response; expose it as `window.ImsReview && window.ImsReview.trainingDocUrl` OR read `DCO.info.trainingDocUrl` if the open-panel call threads it. Concretely:
  - In `imsreview.js`, where the admin data is received and cached, add `_state.trainingDocUrl = (data && data.trainingDocUrl) || '';` and when opening the DCO form pass it: set `DCO.trainingDocUrl = _state.trainingDocUrl` in the open path (grep `imsreview-dco-form` open call / `DCO.action =`).
  - In `imsreview-dco-form.js`, change the Training Requirement coRow value to append the link when present:
    ```javascript
    + coRow('Training Requirement', true,
          trainingRadiosHtml(m.trainingRequirement || [], f.trainingRequirement)
          + (DCO.trainingDocUrl
              ? '<div style="margin-top:6px; font-size:11.5px;"><a href="' + esc(DCO.trainingDocUrl) + '" target="_blank" style="color:#4a6fa5;">View the self-study training guide &#8599;</a></div>'
              : ''),
          'Agile field: Training Requirement.')
    ```

- [ ] **Step 5: Remove the in-field placeholder (#10).** In `notifyStakeholdersHtml` (l.909) remove the `placeholder="…"` attribute from the `<textarea>` (keep everything else). The explanatory note above the control (the coRow tip / grey div) stays.

- [ ] **Step 6: Lint-load + commit.** Quick syntax check:
```bash
node -e "require('fs').readFileSync('src/main/resources/static/imsreview-dco-form.js','utf8'); new Function(require('fs').readFileSync('src/main/resources/static/imsreview-dco-form.js','utf8')); console.log('parse-ok')"
```
Expected: `parse-ok` (no SyntaxError).
```bash
git add src/main/resources/static/imsreview-dco-form.js src/main/resources/static/imsreview.js
git commit -m "feat(ims-dco-form): SDSM renames, optional observers, OBS Final+Stakeholder (no redline), training link, drop notify placeholder"
```

---

## Task Z: Changelog → build → local smoke → stage → email

**Files:**
- Modify: `src/main/resources/static/whats-new.js`
- Build artifact: `target/plm-field-tracker-1.0.1.jar`

- [ ] **Step 1: Update What's New (CLAUDE.md pre-build rule).** Add a new entry at the TOP of `WHATS_NEW_RELEASES` dated `2026-06-30` titled e.g. *"IMS Dashboard & DCO form — Vikas Singh feedback round 2"* with `new`/`improve`/`fix` items covering: No-DRR-required exemption tag, tile reorder, SDSM renames, optional observers, OBS slimmed form, training-doc link, footer-logo removal + Outlook button, notify placeholder cleanup. Include one `admin:true` implementation note.

- [ ] **Step 2: Build.**

Run:
```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-11* mvn -q -DskipTests package
```
Expected: `BUILD SUCCESS`, `target/plm-field-tracker-1.0.1.jar` produced. (Unit tests already run per-task; full `mvn test` optional.)

- [ ] **Step 3: Local smoke test (non-Agile-SDK paths).** Vikas OK'd using the local instance. Restart it on the new jar, log in with `plmadmin` (password from `secret_plmadmin_password.md`, never echoed), and verify:
  - `cp target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/` then restart per `local_setup` (`java -Xmx4g -jar … --spring.config.additional-location=file:./config/application.properties`).
  - `GET /api/ims-review/data?days=30` returns rows carrying `drrExempt` + `documentStyle` keys and `trainingDocUrl` in the payload.
  - Load the IMS Dashboard: tiles render **In Process first**; a `No DRR Required` tile exists under Need DRR.
  - Open a Create-DCO drawer: label reads **SDSM Business Unit**, §04 header **SDSM IMS Document — Edit**, Observers has no `*`, Training Requirement shows the training link, the notify textarea has no grey placeholder.
  - (Agile write-back / actual DCO submit is NOT testable locally — note as deferred to QA, per `feedback_no_agile_writeback_on_this_machine`.)
  > Use one login attempt to avoid lockout.

- [ ] **Step 4: Stage (never the live folder).** Per CLAUDE.md:
```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-eq-agliqss/plm-toolkit/staging/
```
Verify size parity (`stat -f "%z"` source vs staged). If `/Volumes/uls-eq-agliqss/` isn't mounted, STOP and report — don't skip silently. Also `cp` to `~/Documents/plm-toolkit\ 2/` for local smoke (already done in Step 3).

- [ ] **Step 5: Commit the changelog.**
```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): IMS revamp round 2 (Vikas Singh feedback)"
```

- [ ] **Step 6: Email Vikas the completion summary** (`vikas.jindal@sandisk.com`, per CLAUDE.md long-running-work rule) — what shipped (the 8 items), what was verified locally, the one open item (#1 style-attid resolution status from Task B1 Step 0b), and that QA deploy + Agile-write testing is his next step. Follow the Email Design Guidelines (IBM Plex, SanDisk palette, dark-mode meta, sandisk footer pill). **No credentials in the body** (`feedback_never_share_credentials`).

---

## Self-review (against the design doc)

- **#1 exempt** → Tasks B1 (signal), F1 (classify), F2 (tile + tag). Fail-open documented. ✅
- **#2 multi-select** → intentionally absent. ✅
- **#3 SDSM rename** → F3 Step 1. ✅
- **#4 observers optional** → F3 Step 2. ✅
- **#5 OBS slim docs** → F3 Step 3 (render + collect + validate). ✅
- **#6 training link** → B2 (config + email) + F3 Step 4 (form). ✅
- **#7 Bibi defs** → deferred (no task). ✅ (intentional)
- **#8 tile order** → F2 Step 1. ✅
- **#9 logo + button** → B3. ✅
- **#10 placeholder** → F3 Step 5. ✅
- **Type consistency:** `drrExempt` (boolean) flows B1→row map→classifier (`r.drrExempt`)→`segmentActionHtml`; `no_drr_required` tile key defined in F1 and consumed in F2 `TILE_GROUPS`; `trainingDocUrl` defined B2, read as `DCO.trainingDocUrl` (F3) / `${trainingDocUrl}` (email). Consistent. ✅
- **Placeholder scan:** the only deliberate "discovery" is B1 Step 0 (style attid) — bounded, with concrete queries and a fail-open default, not a vague TODO. ✅
