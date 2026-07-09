# IMS Dashboard / DCO Form Revamp #2 — Design

> Source: Vikas Singh feedback (Teams, 2026-06-30 screenshot) on the IMS Dashboard,
> the Create-DCO popup, and the DO/DM review emails. Clarified with Vikas Jindal
> 2026-06-30. This is the second revamp pass after the 2026-06-29 dashboard work
> ([[project_ims_dashboard_refinements]], [[project_ims_send_new_drr]]).

## Scope

**One subsystem: the plm-field-tracker toolkit.** No `plm-agile-service` changes —
the OBS form simply submits fewer files, and the existing `createDco` already
treats redline as optional. Renames, links, tile order, and email are all toolkit.

## Locked requirements

| # | Item | Decision |
|---|------|----------|
| 1 | Spec/Record docs that never need a DRR | Mirror `DocumentReview.java:136` — a doc whose **style** (spaces→underscores, case-insensitive) contains `Specs`, `Drawing`, or `Record_-_for_dead_document_-_buy_off_report` is exempt. Exempt rows show a grey **"No DRR required"** tag instead of the Send New DRR button, and bucket into a new `need_drr.no_drr_required` tile. Exempt-style list is config (`app.ims-review.drr-exempt-styles`), seeded from the code's real list. **Fail-open:** if the style can't be read, treat as non-exempt (current behavior). |
| 2 | Training Requirement multi-select | **Dropped** — no change. |
| 3 | SDSM prefix | Field label `Business Unit` → **"SDSM Business Unit"**; Create-DCO section header `04 IMS Document — Edit` → **"04 SDSM IMS Document — Edit"**. |
| 4 | Observers optional | Remove the required asterisk + drop from `missingFields` validation. |
| 5 | OBS (Obsolete) form | Replace the "trim entire Documents section" behavior: OBS shows a slimmed §05 requiring **Final Version + Stakeholder Notification only** — no Redline slot, no Redline validation, and the OBS submit collects Final + Stakeholder. |
| 6 | Training-doc link | Bibi's SharePoint deck. New config `app.ims-review.training-doc-url`. Linked (a) next to the Training Requirement field in the DCO form, and (b) in the DO review email. |
| 7 | Final field definitions (Bibi) | **Deferred** — not in this batch. |
| 8 | Tile order | New DRR sub-tiles: **In Process → Pending Response → Need Owner → Need Help → Closed**. Legacy sub-tiles: **In Process → Pending Response → Need Owner**. (Outer New→Legacy→Need-DRR order unchanged.) Reorder the `tiles` arrays in `TILE_GROUPS`. |
| 9 | Email cleanup | Remove the red SanDisk logo `<img>` from all 4 email footers in `ImsReviewEmailService`. Rebuild the "Submit your response" button in `ims-review-do.html` as an Outlook-Classic **VML bulletproof button**. |
| 10 | Stakeholder text | Remove the in-field grey **placeholder** on the notify textarea (`imsreview-dco-form.js:909`); keep the explanatory note above it. |

## Key code anchors (verified 2026-06-30)

- **Exempt logic source:** `~/plm_java_extract_20260622_110000/agilerestcall/DocumentClass/src/com/sandisk/docclass/px/DocumentReview.java:136-140`.
- **Backend row build:** `ImsReviewService.java` — `DocRow` (l.384), row map (l.481-574), SQL `pullDocsDueWithin` (l.2785-2839). `document_type = sub.name` is **constant "Document"** for subclass 9141 (useless as a style proxy → need a real style source).
- **Style source (unresolved at plan time):** `Document Style` is read by the PX via `Constants.DOC_STYLE` (attid lives in the batch server's `AuditDocuments.properties`, not in git). It is **not** a `nodetable` list value (confirmed: `Specs`/`Drawing`/`Record - for dead document…` absent from `agile.nodetable`) and is sparsely populated. **Backend Task B1 resolves it against the live schema; until resolved, the column selects NULL and every row is non-exempt (fail-open).**
- **Tile classify:** `imsreview-classify.js` (pure, node-tested) — `imsClassifyTile` (l.49). `TILE_GROUPS` render order: `imsreview.js:393-408`.
- **Row action:** `segmentActionHtml` `imsreview.js:697-716` (the Send New DRR button).
- **DCO form:** `imsreview-dco-form.js` — Training Requirement coRow (l.347), Business Unit coRow (l.354), §04 header (l.369), §05 Documents (l.374), OBS branch (l.397-399), OBS submit-collect gate (l.773-774), notify placeholder (l.909).
- **Email:** `ImsReviewEmailService.java` logo `<img>` at l.1509/1548/1613/1652; DO template `templates/email/ims-review-do.html` (the `${responseUrl}` button).
- **Config:** `application.properties` `app.ims-review.*` block (l.301-331).

## Risks

- **#1 style source** is the only real unknown. Mitigation: fail-open + config + a verification query set in Task B1; the feature degrades to "no rows tagged" rather than mis-tagging.
- **#9 VML button** must be wrapped in `<!--[if mso]>…<![endif]-->` so non-Outlook clients keep the CSS button. Standard bulletproof-button pattern.
