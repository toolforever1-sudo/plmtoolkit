# OBA Required-Doc Retrieval API — Design

**Date:** 2026-06-26
**Status:** Approved (design); pending implementation plan
**Repos touched:** `plm-field-tracker` (orchestration), `plm-agile-service` (SDK)

## Problem

The Auto OBA (Out-of-Box Audit) Buyoff System needs two documents per SKU, retrieved
once daily, but it cannot reach Agile directly. Today this is a manual task: a person
navigates the Agile UI to pull the Outer Shipping Label template and the General SSD
OBA Checklist, then feeds them into the Auto OBA system.

Source: *"Step to review OBA Required Doc in Agile.docx"*. The manual flow is:

1. **Shipping Label Template** — Search SKU → BOM → expand the **C039 assembly**
   (e.g. `C0390TS2670`) → find the **L000-XXXX** item described
   *"Label-Printing-Instruction, Outer Carton Shipping Label"* (e.g. `L000-000420-L1`).
   Its **"label proof"** attachment is the template; its child **D026-XXXX** item
   (e.g. `D026-002282-L1`) holds the **PDF label specification**.
2. **OBA Checklist** — fixed document number `25-07-SM-03-00006`
   ("General SSD OBA 2 Checklist") → Attachments → the `..._Checklist_Final.pdf`.

## Objective

Expose this retrieval as a machine-facing toolkit API so the Auto OBA system can pull
the documents programmatically. No UI tabs. BOM navigation comes from the Agile custom
schema (DB); attachment bytes come from the Agile SDK via the existing
`plm-agile-service` on port 8081.

## Non-goals

- No UI / tab in the toolkit.
- No new Agile **write** operations — read-only retrieval.
- No caching layer in this iteration (daily, low volume — 3 docs/SKU).

## Architecture

```
Auto OBA System
   │  GET /api/oba/required-docs?sku=...     (X-API-Key)
   ▼
plm-field-tracker
   ├─ ObaController         ── API-key gate, builds JSON, proxies file streams
   ├─ ObaResolverService    ── SKU → item numbers via BomDataService explode + BFS
   └─ AgileItemFilesClient  ── HTTP to plm-agile-service (:8081)
         │  GET /api/document/{item}/files
         │  GET /api/document/{item}/file?name=...
         ▼
   plm-agile-service (live Agile SDK)
         └─ DocumentAttachmentsService (extended): listFiles(), fetchOne()
```

**Split of testability:** SKU→item resolution is pure DB (custom schema) and is
testable locally. Attachment fetch requires the live Agile SDK and is testable only
on QA (the :8081 call chain runs on the server).

## Components

### plm-agile-service (SDK side) — 2 new endpoints

Reuse `DocumentAttachmentsService`'s existing traversal
(`IItem` → `getTable(TABLE_ATTACHMENTS)` → `IFileFolder` → `getTable(TABLE_FILES)` →
`IAttachmentFile.getFile()`). Add two methods + a controller:

- `GET /api/document/{item}/files` →
  ```json
  { "itemNumber": "L000-000420-L1", "found": true,
    "files": [
      { "fileName": "...btw",        "fileDescription": "...btw",        "fileType": "btw",  "byteSize": 12345 },
      { "fileName": "...label proof.docx", "fileDescription": "...Label proof", "fileType": "docx", "byteSize": 67890 }
    ] }
  ```
  No file bytes. `byteSize` is best-effort (from the file row's size attribute if the
  SDK exposes one cheaply; otherwise `0`/omitted — never read the full stream just to
  size it). `404` if the item is not found; `200` with `found:true, files:[]` if the
  item exists but has no attachments.

- `GET /api/document/{item}/file?name=<fileName>` → streams one file's bytes with
  `Content-Disposition` and a correct `Content-Type`. Exact filename match. If `name`
  is omitted and the item has exactly one file, stream it; if multiple files and no
  `name`, return `400`. `404` if the named file is not present.

### plm-field-tracker (orchestration side)

- **`ObaResolverService`** — `resolve(sku)` returns the resolved item numbers
  (label-proof L000, label-spec D026) plus warnings. Uses `BomDataService` explode to
  get the SKU sub-tree, builds a parent→children adjacency map, and BFS-searches the
  sub-tree at each level (see Resolution logic).
- **`AgileItemFilesClient`** — `listFiles(itemNumber)` and
  `fetchFile(itemNumber, fileName)` calling the two new agile-service endpoints. Same
  `HttpURLConnection` pattern as the existing `AgileDocumentAttachmentsClient`
  (configurable timeouts, reads from `agile.service.url`).
- **`ObaController`** — the two public endpoints + the API-key gate.

## Public API contract

### `GET /api/oba/required-docs?sku=<SKU>[&checklist=<docNum>]`

Headers: `X-API-Key: <key>`. Returns `200`:

```json
{
  "sku": "0TS2670",
  "generatedAt": "2026-06-26T10:30:00Z",
  "checklist": "25-07-SM-03-00006",
  "documents": [
    {
      "role": "label-proof",
      "itemNumber": "L000-000420-L1",
      "itemDescription": "Label-Printing-Instruction,Outer Carton Shipping Label (Google)",
      "found": true,
      "files": [
        { "fileName": "L000-000420-L1 Rev2 label proof.docx",
          "fileDescription": "L000-000420-L1 Rev2 Label proof",
          "fileType": "docx", "isPdf": false,
          "downloadUrl": "/api/oba/file?item=L000-000420-L1&name=L000-000420-L1%20Rev2%20label%20proof.docx" },
        { "fileName": "L000-000420-L1 Rev2 Label-Printing-Instruction,Outer Carton Shipping Label (Google).btw",
          "fileDescription": "L000-000420-L1 Rev2 btw", "fileType": "btw", "isPdf": false,
          "downloadUrl": "/api/oba/file?item=L000-000420-L1&name=..." }
      ]
    },
    {
      "role": "label-spec",
      "itemNumber": "D026-002282-L1",
      "found": true,
      "files": [
        { "fileName": "D026-002282-L1 Rev2 Label Specifications eSSD Shipping Label for Google _Final.pdf",
          "fileType": "pdf", "isPdf": true, "downloadUrl": "..." }
      ]
    },
    {
      "role": "oba-checklist",
      "itemNumber": "25-07-SM-03-00006",
      "found": true,
      "files": [
        { "fileName": "25-07-SM-03-00006_Rev2_General_SSD_OBA_2_Checklist_Final.pdf",
          "fileType": "pdf", "isPdf": true, "downloadUrl": "..." }
      ]
    }
  ],
  "warnings": [ "Multiple C039 assemblies under 0TS2670; using first C0390TS2670 (2 found)" ]
}
```

- `documents` always contains the three roles in order: `label-proof`, `label-spec`,
  `oba-checklist`. A role that could not be resolved has `found:false`, no `files`, and
  a corresponding `warnings` entry.
- `isPdf` is a convenience flag (filename ends `.pdf`) so the consumer can grab the PDF
  without parsing.
- `downloadUrl` is a **relative, key-less** path. The consumer reuses its `X-API-Key`
  header on the file call — the key never appears in a URL (avoids leaking into access
  logs).

### `GET /api/oba/file?item=<item>&name=<fileName>`

Headers: `X-API-Key: <key>`. Streams the single file (proxying the agile-service
`/file` endpoint), preserving `Content-Disposition` and `Content-Type`.

## Resolution logic (`ObaResolverService`)

1. `BomDataService` explode of the SKU → flat rows (`parent`, `component`,
   `description`, level). Build a `Map<parent, List<child>>` adjacency map.
2. **C039 assembly:** BFS from SKU; collect components whose number starts with `C039`
   (case-insensitive). Use the first; if >1, record an ambiguity warning with the count.
3. **L000 outer shipping label:** BFS from the chosen C039's sub-tree; collect
   components whose number starts with `L000` **and** whose description matches
   `/outer.*shipping label/i`. First wins; >1 → warning.
4. **D026 spec:** BFS from the chosen L000's sub-tree; collect components whose number
   starts with `D026`. First wins; >1 → warning.
5. Any level with zero matches → that downstream role(s) `found:false` + warning
   (e.g. "No C039 assembly found under SKU 0TS2670").

"Search the whole sub-tree at each level" (not just direct children) was chosen for
robustness against BOM nesting variation; the first-match-wins + ambiguity-count keeps
it from silently picking wrong when duplicates exist.

## Auth & configuration

- **API key:** `X-API-Key` request header, **constant-time** compared against
  `app.oba.api-key`.
  - Config value blank/absent → endpoint returns **503** ("OBA API not configured") so
    it is **never open by default**.
  - Header missing or mismatched → **401**.
  - The gate is implemented inside `ObaController` (both endpoints) only — it does not
    add a global servlet filter and does not affect any other `/api/*` endpoint.
- **Config keys** (in `application.properties`):
  - `app.oba.api-key=` — empty in git; the real key is supplied via external config on
    the server (e.g. `--spring.config.additional-location`). A throwaway dev key may be
    set in the local config only.
  - `app.oba.checklist-default=25-07-SM-03-00006`.
- The agile-service base URL reuses the existing `agile.service.url` property.

**Security note:** per the repo's credential policy, no key/password is ever written to
git, chat, email, or any rendered output. The API key lives only in server-side
external config.

## Error handling (fail-soft, per-role)

- **Unknown SKU** (not in BOM extract): `200` with `label-proof` and `label-spec`
  `found:false` + a warning. Not a 5xx — the daily job gets consistent JSON it can alert
  on. The checklist role is still attempted.
- **Agile-service unreachable / errors for one item:** that role becomes `found:false`
  with an `error` note in its entry; the other roles are still returned (a label failure
  must not drop the checklist).
- **`/file` proxy:** upstream agile-service error → `502`; named file not found → `404`.
- **`/required-docs` server-side failures** (DB down, etc.) → `500` with an error body.

## Testing strategy

- **Local (DB reachable):**
  - `ObaResolverService` resolves known SKU `0TS2670` → expects `L000-000420-L1`
    (label-proof) and `D026-002282-L1` (label-spec) from the custom schema.
  - Ambiguity-warning and not-found paths (synthetic adjacency maps).
  - API-key gate: 503 when unconfigured, 401 on missing/bad key, pass on good key.
  - TDD throughout (test first, then implement).
- **QA only (live SDK on :8081):**
  - agile-service `/files` and `/file?name=` against `L000-000420-L1`,
    `D026-002282-L1`, `25-07-SM-03-00006`.
  - Full `/api/oba/required-docs?sku=0TS2670` → follow each `downloadUrl` → verify bytes
    (the D026 PDF and the checklist PDF open correctly).

## Build / deploy

- **Pre-build:** update `src/main/resources/static/whats-new.js` with a new top entry
  (per project rule), then build.
- **plm-field-tracker:** `mvn package` → copy to prod-share **staging** and to the local
  `~/Documents/plm-toolkit 2/` copy (per project rules). Vikas does the staging→live
  cutover on the Windows server.
- **plm-agile-service:** built and deployed to QA (`uls-eq-aglapp01`) for SDK testing;
  follow that project's deploy flow.

## Addendum (2026-06-26): explicit "attachment missing" status

QA does not hold every attachment that prod has, so the response must make a missing
file unambiguous (the operator manually adds the file to the part in Agile when flagged).
Each `documents[]` entry carries a `status` and, when not `ok`, a human `message`:

- `ok` — item resolved and at least one attachment file present (`found:true`).
- `attachment-missing` — item exists in Agile but has **zero** attachment files
  (`found:false`). Message: "Attachment missing — add the file to item <n> in Agile".
- `agile-item-not-found` — the resolved item number does not exist in Agile (e.g. QA
  lacks the part). Message: "Item <n> not found in Agile — create the part and add the attachment".
- `item-not-resolved` — the BOM yielded no item number for this role.
- `lookup-error` — the attachment lookup failed (agile-service down/error).

Top-level summary fields: `attachmentMissing` (boolean — true if any role is not `ok`)
and `missingRoles` (array of role names). `found` is retained for back-compat
(`found == (status=="ok")`).

## Open items / follow-ups

- `byteSize` is best-effort; if the SDK has no cheap size attribute, it may be `0`. Not
  a blocker (the consumer downloads by name regardless).
- If the Auto OBA system later needs to confirm a single "the" PDF per role rather than a
  file list, we can add server-side preferred-file selection — out of scope for v1.
