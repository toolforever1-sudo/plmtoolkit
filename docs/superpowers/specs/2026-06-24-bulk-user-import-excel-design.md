# Bulk User Import from Excel — Design

**Date:** 2026-06-24
**Branch context:** plm-field-tracker, User Management ("Add user from AD")
**Status:** Approved (design), pending implementation plan

## Problem

User Management today adds access one person at a time: search org-wide AD,
pick a user, pre-configure tabs, and submit a DL-add request that emails IT.
Admins frequently have a spreadsheet of people to onboard (e.g. a sheet with
just **Name** and **Email** columns). There is no way to import them in bulk.

We want an Excel/CSV import that:
- Lets the **AI infer the column mapping** from the sheet's headers/sample rows.
- **Only asks the admin to clarify when the AI is unsure** — otherwise it
  proceeds silently.
- Resolves each row against AD (the sheet has no `sAMAccountName`, which the
  add-request requires), flagging rows it can't confidently match.
- Applies one tab set to the whole batch and notifies IT with a single
  consolidated email.

## Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| AD matching | Auto-match by email (fall back to name); flag ambiguous/no-match rows for the admin to fix/skip |
| Tab access | One tab set picked once, applied to the whole batch (pre-checked to the same defaults as single-add) |
| IT notification | One consolidated DL-request email listing all users + tabs |
| AI mapping UX | Auto-proceed when confident (no mapping step shown); only interrupt to ask when a column is ambiguous |
| Duplicates | Rows already a user / already in the DL are flagged "already has access" and excluded from submit by default (admin can re-include to update tabs) |
| Email vs records | Consolidated email to IT, but **one `PendingRequest` record per user** so each person's own login still completes their request + welcome email |
| File formats | `.xlsx`, `.xls`, `.csv` — whatever `FileItemParser` already accepts |

## Existing building blocks (reused, not rebuilt)

- **`PortkeyClient.chat(model, system, user, maxTokens)`** — the AI client. Same
  pattern as `UploadColumnDetector.detectByAi()` (Haiku, JSON-only prompt,
  best-effort brace extraction).
- **`StreamingExcelProbe.probe()`** — headers + sample rows without loading the
  full DOM. **`FileItemParser.validateFileType()`** — file-type gate.
- **`LdapAuthService.searchDirectory(q, n)`** — prefix match on
  displayName / sAMAccountName / mail; returns `DirectoryUser{username,
  displayName, email}`. Already used by `/api/permissions/ad-search`.
- **`UserPermissionsService.upsertUser(...)`** and **`submitDLRequest(...)`** —
  persistence + per-user DL request + IT email. Bulk path reuses `upsertUser`
  and the `PendingRequest` model, but replaces the per-user email with one
  consolidated send.
- **Tab catalog** in `UserPermissionsService.TAB_CATALOG` — same grantable keys
  as single-add; admin-only/permissions-admin tabs stay non-grantable.

## User flow (wizard)

A new **"Import from Excel"** button on the User Management page, next to
**+ Add user from AD**, opens a modal wizard (app modal helpers — `appAlert` /
`appConfirm`, never native dialogs):

1. **Upload** — drag/drop or pick `.xlsx` / `.xls` / `.csv`.
2. **AI column mapping (usually invisible)** — server probes headers + ~3
   sample rows and asks the AI which columns are **Name** and **Email**.
   - Confident → skip straight to the preview.
   - Unsure → show a compact mapping step with the AI's question and dropdowns
     pre-filled to its best guess; admin confirms, then continues.
3. **Row preview grid** — each row shows Name, Email, matched AD user
   (`sAMAccountName`), and a status badge. Admin picks the **batch tab set**
   once here. Ambiguous rows expose an inline AD-candidate picker.
4. **Submit** — one consolidated DL request to IT; success summary
   (added / skipped / failed counts).

## Row status model

For each parsed row, the server resolves against AD (email first, name
fallback) and classifies:

| Status | Meaning | Included in submit? |
|---|---|---|
| ✅ Matched | exactly one confident AD hit (mail equals row email, or single name hit) | yes |
| ⚠️ Ambiguous | multiple AD candidates | no — inline picker → becomes Matched once chosen |
| ❌ No match | no AD user found | no — shown as error |
| 🔵 Already has access | already a `UserRecord` or already in the access DL | no by default — shown as a **warning** ("already has access — skipped"); admin may toggle to re-include and update tabs |

The "already has access" rows are surfaced as a non-blocking **warning** in the
preview (amber badge + message), not an error — the admin is simply informed
and the row is skipped unless they choose to re-include it.

"Confident match" = AD search by the row email returns a result whose `mail`
equals the row email (case-insensitive). If email is blank, fall back to a name
prefix search: a single hit → Matched; multiple → Ambiguous; none → No match.

## Backend

New **`UserImportController`** (gated on the existing permissions-admin role,
same guard as `UserPermissionsController`):

- `POST /api/permissions/import/analyze` — multipart `file`.
  Probes the workbook, runs AI column mapping, returns:
  ```json
  {
    "mapping": { "nameColumn": 0, "emailColumn": 1 },
    "confident": true,
    "mappingQuestion": null,
    "columns": ["Name", "Email"],
    "sampleRows": [["Philip Tam", "Philip.Tam@sandisk.com"]],
    "rows": [ { "name": "Philip Tam", "email": "Philip.Tam@sandisk.com" } ]
  }
  ```
  When `confident=false`, `mappingQuestion` carries a short prompt and the
  candidate columns for the UI to render the mapping step.

- `POST /api/permissions/import/resolve` — `{ "rows": [{name,email}, ...] }`.
  Resolves each row against AD + dedupes, returns preview rows:
  ```json
  {
    "rows": [
      {
        "name": "Philip Tam", "email": "Philip.Tam@sandisk.com",
        "status": "matched",
        "match": { "sAMAccountName": "philip.tam", "displayName": "Philip Tam", "email": "Philip.Tam@sandisk.com" },
        "candidates": []
      }
    ],
    "summary": { "matched": 12, "ambiguous": 1, "nomatch": 1, "alreadyAccess": 0 }
  }
  ```
  (`analyze` may inline the resolve result when the mapping is confident, to
  jump straight to the preview; `resolve` is also called standalone after the
  admin fixes a mapping or an ambiguous pick.)

- `POST /api/permissions/import/submit` —
  `{ "rows": [{sAMAccountName, displayName, email}, ...], "allowedTabs": [...] }`.
  Calls new **`UserPermissionsService.submitBulkDLRequest(rows, allowedTabs,
  actor)`**:
  - For each row: `upsertUser(...)` (strips non-grantable tabs, rejects admins)
    + create one `PendingRequest` (status `pending`), idempotently replacing any
    prior request for that user.
  - Send **one** consolidated HTML email to `pdl-plm-admin` listing every user
    and the shared tab set (follows the project Email Design Guidelines).
  - Log a single `PERMISSIONS_BULK_IMPORT` activity with
    added/skipped/failed counts.
  - Return per-row outcome so the UI can show a summary and any failures.

## AI column mapping

System prompt mirrors `UploadColumnDetector` ("Reply with JSON only"). User
prompt lists 0-indexed headers + up to 3 sample rows and asks for:
```json
{ "nameColumn": <int>, "emailColumn": <int>, "confident": <bool>, "reasoning": "<one sentence>" }
```
Parse with best-effort brace extraction. `confident=false` (or an
out-of-range/missing column) triggers the mapping step. Email column may be
absent in a sheet — that's allowed; rows then resolve by name only.

## Graceful degradation & errors

- **AI off / Portkey disabled / AI unsure or unparseable** → heuristic fallback:
  pick the column whose header contains "email"/"e-mail"/"mail" as Email and
  "name"/"full name"/"display" as Name. If still ambiguous, show the manual
  mapping step. The feature never hard-depends on AI.
- **AD unreachable** → resolve returns a clear error with retry; rows can't be
  classified without it.
- **Empty sheet / no usable columns / unsupported file type** → validation error
  surfaced before any AI call.
- **Partial submit failure** → collect per-row failures, still commit the rest,
  report both in the summary.

## Testing

- **Unit (local, mocked `PortkeyClient` + `LdapAuthService`):**
  - AI mapping prompt build + JSON parse, including out-of-range/garbage responses.
  - Heuristic fallback header matching.
  - Row classification: confident email match, exact-mail tie-break, name
    fallback (single/multiple/none), dedup against existing users + DL.
  - `submitBulkDLRequest`: N records created, non-grantable tabs stripped,
    admins rejected, one consolidated email, activity logged with correct counts.
- **Server-only (handed to Vikas):** live LDAP resolution and live Portkey
  mapping — neither is reachable from the Mac, consistent with this project's
  server-only-dependency handling.

## Out of scope (YAGNI)

- Per-user tab configuration in the grid (batch tab set only).
- Importing additional profile fields beyond name/email.
- Editing/removing users via Excel (import = add only).
- Scheduled/recurring imports.
