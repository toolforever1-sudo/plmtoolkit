# IMS Dashboard & Flow — Three Tweaks (design)

**Date:** 2026-06-23
**Branch:** `feat/ims-review-revamp` (continues the unmerged IMS work)
**Status:** Approved — proceed to plan.

Three independent, additive changes to the IMS Document Review dashboard and flow. Verification is the build + the user's server/local smoke test (no JS test harness; Mac can't reach the Agile SDK).

---

## Feature 1 — Restrict the 🔑 Unlock button to PLM IT only

**Today:** The Unlock button (clears a recipient's password-attempt lockout) renders for `_state.meta.canSeeAdminView` = *admin OR explicit `ims-review` tab grant*. The backend `/admin/unlock-token` is guarded by `hasAdminOrDccAccess()` (same broad set).

**Change:**
- **Frontend** `src/main/resources/static/imsreview.js` (~L845): the Unlock button's render condition changes from `_state.meta.canSeeAdminView` to `_state.meta.isAdmin`. The `isAdmin` field is already returned by `GET /api/ims-review/role`. The ⟲ Reset button and all other row actions are **unchanged** (decision: "Unlock only").
- **Backend** `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java` `/admin/unlock-token` (~L184): replace the `hasAdminOrDccAccess(session)` guard with a strict `isPlmAdmin` session check; return `403 "PLM Admin access required."` otherwise. (Defense in depth — UI gate alone is not security.)

**"PLM IT"** = `isPlmAdmin` (AD group `pdl-plm-admin`, already set on the session at login). No new config.

**Out of scope:** Reset and other admin actions keep `canSeeAdminView`.

---

## Feature 2 — Color-code legacy (pre-go-live) DRRs; show all by default

**Today:** `imsreview.js` state has `drrCreatedAfter: '2026-07-05'` (go-live anchor) and `drrCreatedFilterOn: true`, which **hides** every row whose `drrCreated < 2026-07-05`. A toggle (`renderDrrFilter`) lets a user turn the filter on/off and edit the date.

**Change (frontend only, `imsreview.js`):**
- Default `drrCreatedFilterOn: true → false` so **all DRRs show by default**. The existing toggle stays so anyone can re-enable the hide-filter.
- **Legacy treatment** for rows where `drrCreated` is present and `< drrCreatedAfter`:
  - A muted pill badge **`legacy`** next to the DRR Created date, styled with the existing neutral palette: `background:#eef0f3; color:#6B7280;` (same pill shape as `statusPill`), title tooltip "Created before go-live (2026-07-05)".
  - A faint gray left-accent on the row's first cell (`box-shadow: inset 3px 0 0 #cbd5e1;` or a 3px left border) so legacy rows are scannable.
  - Rows with **no** `drrCreated` (no DRR yet) are **not** marked.
- No new colors; understated, matching the house "subdued, not flashy" style.
- Helper: `isLegacyDrr(r)` → `var c=(r.drrCreated||'').substring(0,10); return c && c < _state.drrCreatedAfter;`. Applied in `renderAdminTable()` row rendering.

**Out of scope:** changing the cutoff date value or making it configurable beyond the existing toggle.

---

## Feature 3 — Hyperlink all item/change/DCO numbers (emails + response pages + DCO success)

Every item/doc number, DRR/change number, and DCO number that currently renders as plain text becomes a clickable Agile webclient link. People-name fields stay plain (only **numbers** get linked).

**Link pattern (existing):** items → `<base>/object/Part/<number>/tab/13`; changes (DRR/DCO/ECN) → `<base>/object/<typePrefix>/<number>` where `typePrefix` is the text before the first hyphen. Link style: `color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;` opening in a new tab.

**3a — Emails** (`ImsReviewEmailService.java` + `templates/email/*.html`): standardize so every number var passes through `agileLinkItem()` / `agileLinkChange()`. Audit and fix the plain ones:
- `ims-review-dm.html` — confirm `${docNumber}`/`${drrNumber}` resolve to linked values (the DM payload should reuse the linked DO values; if it re-puts plain, wrap them).
- `ims-review-dcc-needs-change.html` — service puts plain `docNumber`/`drrNumber`; wrap with the helpers.
- `ims-review-dco-stakeholder-notify.html` — verify `${dcoNumber}`, `${docNumber}`, `${drrNumber}` are all wrapped.
- Spot-check the other IMS templates (`ims-review-dm-approved`, `ims-review-owner-reassigned`, `ims-review-dcc-need-help`) for any plain number vars and wrap them.

**3b — Backend enabler** (`ImsReviewController.java`): the standalone response page is opened from an email link with no app session, so `window.AGILE_WEBCLIENT_URL` is not set there. Add `agileWebclientUrl` (the same `app.ims-review.agile-webclient-url` value) to:
- the `GET /token/info` JSON payload, and
- the `GET /token/dco-form-metadata` (and `dco-form-preview-metadata`) JSON payload.

**3c — Response page** (`ims-respond.html`): add a small helper:
```js
function agileLink(number, kind) { // kind: 'item' | 'change'
  if (!number) return '';
  var base = (STATE.info && STATE.info.agileWebclientUrl) || window.AGILE_WEBCLIENT_URL || '';
  if (!base) return esc(number); // fail-soft: plain text when no base
  var href = kind === 'item'
    ? base + '/object/Part/' + encodeURIComponent(number) + '/tab/13'
    : base + '/object/' + encodeURIComponent((number.split('-')[0]||'')) + '/' + encodeURIComponent(number);
  return '<a href="' + href + '" target="_blank" rel="noopener" style="color:#4a6fa5; text-decoration:none; border-bottom:1px dashed #a0b8d0;">' + esc(number) + '</a>';
}
```
Apply to: the summary table **Number** (`agileLink(info.docNumber,'item')`) and **Related DRR** (`agileLink(info.drrNumber,'change')`); and the reference panel's **Number** row (and **Old Document Number** if it holds a doc number). The `refRow` 'docno' kind becomes a linked value when a base is available (extend `refRow` to accept a pre-built href or a `link:{number,kind}`).

**3d — DCO success** (`imsreview-dco-form.js`): the created-DCO success message currently sets `successMsgEl.textContent = 'DCO ' + dcoNumber + ' created…'`. Switch that node to `innerHTML` with the DCO number wrapped via an `agileLink(dcoNumber,'change')` (the DCO form reads the base from `DCO.meta.agileWebclientUrl` or `window.AGILE_WEBCLIENT_URL`, fail-soft to plain). Apply to both the success line and the auto-submit-blocked line. **Escape all other interpolated text** when moving from `textContent`→`innerHTML` (XSS safety).

**Out of scope:** linking person names, descriptions, or non-number fields.

---

## Cross-cutting

- All three are additive; none change the DCO `collectForm()` contract or any submit endpoint.
- Cache-bust the touched static files (`imsreview.js`, `ims-respond.html`, `imsreview-dco-form.js`) as needed (`?v=` bumps) so browsers fetch the new versions.
- Pre-build: add a "What's New" entry. Build (JDK 11), local copy, stage to QSS (`/Volumes/uls-eq-agliqss/plm-toolkit/staging/`), hand off for server verification.

## Verification
No automated tests. Confirm via: successful `mvn package`; visual check of the dashboard (Unlock hidden for non-admins, legacy DRRs tinted, all DRRs shown); response page + DCO success links resolve to Agile; rendered email HTML shows linked numbers.
