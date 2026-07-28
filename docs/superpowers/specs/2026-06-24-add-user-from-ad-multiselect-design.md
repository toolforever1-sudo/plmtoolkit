# "Add user from AD" — multi-select + preset tab grants — Design

**Date:** 2026-06-24
**Surface:** PLM Toolkit → More → User Management → Users → **+ Add user from AD** modal
**Source handoff:** `~/Downloads/ADD_USER_FROM_AD_HANDOFF.md`
**Status:** Approved (design), pending implementation plan

## Problem

Two PCM-reported workflow problems with the current Add-from-AD modal:

1. **Single-user only.** Picking a person in AD search *replaces* the prior pick; you re-open the modal once per user to add a batch.
2. **Tabs start fully checked.** All ~18 grantable tabs are ticked by default, so granting a small set means unchecking many.

## What it becomes

- Search **appends** people to a running "Selected users" list (add many before submitting).
- Tab grants start from a **preset** (default **Viewer**, a minimal set) with one-click presets, per-group Select-all/Clear, and individual ticks.
- The chosen tab set is **shared across all selected users** (confirmed: no per-user override).
- One submit sends a single consolidated IT request covering everyone who needs DL access.

## Decisions (from brainstorming)

- **Groups & presets** approved as proposed (below).
- **Default preset on open:** Viewer.
- **Backend:** reuse `submitBulkDLRequest` (already does shared-tabs-per-user + one consolidated IT email from the Excel-import work).
- **Separate surface** from the Excel-import modal; they share the backend only.

### Groups (order shown in the modal)

| Group | Tab keys |
|---|---|
| Items | fields, parts, agile, sku |
| BOM | bom, bomcompare |
| Change & ECO | history, ecotimeline, ecnreport, changereviews, docreview, ims-review |
| Reports & Docs | singlesole, sdsm |
| More / Tools | helpcenter, compare, aieval, it-enhancements |

All 18 grantable tabs appear in exactly one group. Admin-only / permissions-admin
tabs (`extensions`, `reports`, `adhealth`, `permissions`) are returned by the
catalog flagged non-grantable and are **not** shown in the picker.

### Presets

| Preset | Tab keys |
|---|---|
| Blank | (none) |
| Viewer | agile, sku, history, ecotimeline, ecnreport, ims-review |
| Items team | fields, parts, agile, sku, bom, bomcompare |
| Reporting | history, ecotimeline, ecnreport, singlesole, docreview |
| Full access | all 18 grantable keys |

## Architecture

Three backend additions (all small, mostly pure/testable) + a frontend modal rebuild.

### Existing building blocks (reused)

- `GET /api/permissions/ad-search?q=` → `[{sAMAccountName, displayName, email, alreadyInDL}]` (unchanged).
- `UserPermissionsService.submitBulkDLRequest(users, allowedTabs, actor, actorDisplay)` → `List<BulkOutcome>` — upserts tab grants per user, creates pending DL requests, sends ONE consolidated IT email. **Enhanced here** (see below).
- `TAB_CATALOG` (22 tabs, `grantableTabKeys()` excludes admin/perms-admin).
- v2 design tokens in `tokens.css` are `:root`-scoped (global), so `var(--accent)` etc. resolve in the modal even though `<body>` is not `.v2`.

### Backend unit 1 — groups & presets catalog (in `UserPermissionsService`)

Kept as data in the service (not a field on every `TabDef`, to avoid touching all
22 entries; a test enforces every grantable tab is mapped):

- `static LinkedHashMap<String,String> TAB_GROUPS` — tab key → group name.
- `static List<String> GROUP_ORDER` — `["Items","BOM","Change & ECO","Reports & Docs","More / Tools"]`.
- `static List<Preset> PRESETS` — ordered; each `Preset{ id, label, List<String> tabKeys }` (Full access stored as the resolved full grantable list).
- `Preset` POJO (public static, JSON-serializable).
- Getters: `getTabGroups()`, `getGroupOrder()`, `getPresets()`.

### Backend unit 2 — DL partition + submit refinement

To match single-add semantics (a user **already in the access DL** just gets their
tabs saved — no pending request, no IT email):

- New pure static helper `partitionByDl(List<Map<String,String>> users, Set<String> dlUsernamesLower)` → `{ needsDl: List<...>, alreadyInDl: List<...> }` (keys lower-cased via `normalizeKey`). **Unit-tested.**
- `submitBulkDLRequest` enhanced: compute the DL set once (`listAccessGroupCandidates`), partition, then:
  - **All** users → `upsertUser(...)` (tabs saved).
  - **Only `needsDl`** users → create a `PendingRequest` + include in the consolidated email.
  - `BulkOutcome` gains a boolean `emailedToIt` so the caller can report the count.
  - The Excel-import path is unaffected (it already filters already-access rows before submit, so its inputs are all `needsDl`).

### Backend unit 3 — endpoints (`UserPermissionsController`)

- `GET /api/permissions/tab-catalog` (admin-gated) →
  ```json
  {
    "tabs":   [ { "key":"agile","label":"Items → Agile Lookup","group":"Items","grantable":true }, ... ],
    "groups": ["Items","BOM","Change & ECO","Reports & Docs","More / Tools"],
    "presets":[ { "id":"viewer","label":"Viewer","tabKeys":["agile","sku","history","ecotimeline","ecnreport","ims-review"] }, ... ]
  }
  ```
  The existing `GET /api/permissions/tabs` is **left unchanged** (other consumers depend on its flat-array shape).
- `POST /api/permissions/request-add-bulk` (admin-gated) → body `{ users:[{sAMAccountName,displayName,email}], allowedTabs:[] }` → calls `submitBulkDLRequest`, returns `{ success, submitted, ok, failed, emailed, outcomes }`.

### Frontend — modal rebuild (`index.html` + `user-permissions.js`)

Rebuild the `permsAddModal` body and its JS for multi-select, styled with v2 tokens
(no new colors; brand red stays logo-only). New state on `permsState.add`:
```
{ query, selectedUsers: [{sAMAccountName,displayName,email,alreadyInDL}],
  granted: {tabKey:true}, activePreset: 'viewer'|null, catalog: {tabs,groups,presets} }
```

1. **Search → add.** Reuse `permsTypeahead`; clicking a result **appends** to
   `selectedUsers`, clears + refocuses the input. Results exclude already-selected
   users (or show them disabled "Added"). Each result keeps its In-DL hint.
2. **Selected users (N).** Header `Selected users (N)` + **Clear all** (when N>0).
   Rows: initials avatar, name, `id · email` (mono), DL pill (green "In access DL"
   / amber "Email IT"), `×` remove. Empty state when N=0. **Submit disabled until N≥1.**
3. **Tabs to grant (shared).** Heading `Tabs to grant on first login` + live counter
   `X of {total} selected`. Preset pills (Blank/Viewer/Items team/Reporting/Full
   access); **Viewer active on open**. Active preset highlighted; toggling any tab
   clears the highlight → "custom". Grouped list (GROUP_ORDER) with per-group
   `selected/total` + Select-all/Clear; each tab row toggles on click.
4. **Footer.** Left summary `N users · X tabs each · K will be emailed to IT`
   (K = count of selected users not in the DL). Right: `Cancel` + `Submit request
   to IT (N)` (accent), disabled when N=0.
5. **Submit.** POST `/api/permissions/request-add-bulk` with `users` (sAMAccountName/
   displayName/email) + `allowedTabs` (checked keys). On success: close, reload the
   user list, `appAlert` summary (added / emailed-to-IT counts). Uses
   `appAlert`/`appConfirm`, never native dialogs.

## Data flow

```
search → /ad-search → click result → selectedUsers.push (dedup, exclude in results)
preset pill → set granted{} from catalog.presets[id]; activePreset=id
group/tab toggle → mutate granted{}; activePreset=null (custom)
submit → /request-add-bulk { users:[...], allowedTabs:[checked] }
        → submitBulkDLRequest: upsert tabs ALL; pending+email only needsDl
        → { ok, emailed } → appAlert
```

## Error handling

- `/tab-catalog` or `/ad-search` failure → inline error in the modal; submit stays disabled.
- Submit failure → `appAlert` with the server error; button re-enabled.
- A row whose `upsertUser` throws (e.g. target is an admin) → collected in `outcomes` as `ok:false`; the rest still go through; summary reports failures.
- AD unreachable → search returns empty (existing behavior); the DL partition treats an empty DL set as "all need DL" (fail-safe: IT gets emailed rather than silently skipping).

## Testing

- **Backend unit (pure, no IO):**
  - Catalog integrity: every grantable tab is in exactly one group; `GROUP_ORDER` covers all groups; every preset references only grantable keys; Full access == `grantableTabKeys()`; Viewer / Items team / Reporting memberships exact.
  - `partitionByDl`: in-DL users go to `alreadyInDl`, others to `needsDl`; case-insensitive; empty DL set → all `needsDl`.
- **Backend wiring:** `/tab-catalog` and `/request-add-bulk` compile + admin-gated (verified by local smoke test, like the import endpoints).
- **Frontend:** local smoke test on `:8090` — add 2+ users, presets/group toggles update counts, footer summary correct, submit posts the shared tab set. (`/ad-search` works locally against live AD.)

## Out of scope (YAGNI)

- Per-user tab overrides (shared set only — confirmed).
- Editing the Import-from-Excel modal.
- Reshaping the existing `/api/permissions/tabs` response.
- Persisting/reordering custom presets.
