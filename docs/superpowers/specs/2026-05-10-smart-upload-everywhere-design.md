# Smart Upload Everywhere — Design

**Date:** 2026-05-10
**Status:** Approved, in implementation

## Goal

Bring the AI-powered item-column detection that exists today on the **Change History** and **BOM** tabs to every other tab that takes an `.xlsx` / `.xls` upload, plus add the **"Download enriched file"** button to those tabs. When detection is uncertain, ask the user which column to use instead of silently picking column A.

Two extra asks layered in during brainstorming:

1. **Dedup awareness** — when an uploaded file has the same item number repeated across many rows (typical of a re-uploaded BOM-Implosion export), the parser should dedup before querying and surface "X unique items from Y rows" so the user understands.
2. **Heap preflight** — the JVM should know its own heap size and refuse a file that would OOM the server, with a friendly business-language message asking the user to contact IT, rather than crashing.

## Scope (which tabs)

| Tab | Smart detector | Enrich button |
|---|---|---|
| BOM Explode | already smart | **skip** (one→many) |
| BOM Implode (Where Used) | already smart | **NEW**: append "Top-Level Parent(s)" |
| Field Changes | no upload | n/a |
| Part Extract | **NEW** | **NEW**: append the user's selected display columns |
| Agile Lookup | out of scope (microservice owns its own parser) | out of scope |
| SKU Lookup | **NEW** | **NEW**: append the user's selected display columns |
| Change History | already done | already done |
| Compare | custom 5-step wizard, manual column picker | n/a |

## Architecture

### Backend

**Three new utilities + one new controller. Existing endpoints gain an optional `?itemColumn=N` override.**

1. `HeapGuard` (util) — given `MultipartFile.getSize()` and an op hint (`PROBE` / `QUERY` / `ENRICH`), checks `Runtime.maxMemory() − usedMemory − 200MB` against `fileSize × multiplier` (probe=1, query=3, enrich=8 for xlsx). Returns `OK` or a friendly message.
2. `StreamingExcelProbe` (util) — opens xlsx via POI's `XSSFReader` + `OPCPackage` SAX path, reads only row 1 + first 3 data rows of each sheet. ~50MB heap on a 72MB file. Reuses `UploadColumnDetector` for actual scoring.
3. `UploadProbeController` — new `POST /api/upload/probe`. Accepts the file once, runs heap-guard then streaming probe, returns one of:
   - `{confident:true,  column:{index,letter,header,method}, totalRows, uniqueItems, sourceSheet}`
   - `{confident:false, columns:[{index,letter,header,sample:[…]}], totalRows, sourceSheet}`
   - `{tooLarge:true,   message:"…"}`
4. **Existing endpoints get an `?itemColumn=<int>` query/form param** — `/api/bom/upload`, `/api/parts/upload`, `/api/sku/searchFile`, `/api/history/upload`, plus the new enrich endpoints. When present, detection is skipped and the override column is used.
5. **New enrich endpoints** mirroring `ChangeHistoryEnrichService`:
   - `POST /api/parts/enrich` — appends the user's selected display columns
   - `POST /api/sku/enrich` — appends the user's selected display columns
   - `POST /api/bom/enrich-implode` — appends one column "Top-Level Parent(s)" (comma-joined)
6. **Smart-detector retrofit** — `PartExtractController.upload` and `SkuDataController.searchFile` switch from legacy `parseItems(file)` to `parseItemsWithDetection(file, columnDetector)` so they too get header → AI → fallback.
7. **Dedup awareness** — `FileItemParser.ParseResult` already has `totalRows`; add `uniqueItems` (size of the deduped item list). Probe + upload responses surface both.

### Frontend

One shared helper, one shared modal, four pages call the helper.

1. `smart-upload.js` (new) exposes:
   - `smartUpload(file, opts)` → probe → optional modal → real upload (calls `opts.onResult(payload)`).
   - `smartEnrich(file, opts)` → probe → optional modal → enrich endpoint (downloads response blob).
2. `#columnPickerModal` (new in `index.html`) — single block of markup all four tabs reuse. Shows file name, sheet name, totalRows / uniqueItems chips, and a small table: column letter | header | sample values | radio. "Use this column" button + Cancel.
3. `bom.js` / `parts.js` / `sku.js` / `history.js` are migrated to call the helper. Each page keeps its own data-rendering logic; only the *upload bridge* changes.
4. **Enrich buttons** — added to the status-bar of BOM (Implode only — hidden in Explode mode), Part Extract, SKU Lookup. Same pattern as the existing Change History button.

## Behavior details

- **Detection confidence** — "confident" iff `method ∈ {header-match, ai-fallback}` AND the AI response wasn't empty. `default-col-a` is never confident.
- **Modal trigger** — only when probe says `confident:false`. The modal lists all columns of the chosen sheet, marks the detector's best guess as the default radio. User can pick a different one.
- **Item-column override** — the chosen column index is passed as `itemColumn=N` to whichever real endpoint runs next (upload or enrich). All endpoints honor it the same way.
- **Dedup** — silent on the query side (`SELECT … WHERE item IN (…)` works fine with duplicates, so we just dedup the list before joining). The chip "47 unique items from 2,800 rows" appears in the status bar so the user knows.
- **Heap preflight** — runs at probe time for every file, runs again at enrich time (since enrich needs a higher multiplier). On rejection, message reads: *"This file is ~72MB and would need roughly 600MB of server memory for enrichment, but only 350MB is currently available on this server. Please contact IT to increase the toolkit's memory, or split the file into smaller chunks."*
- **Streaming reader** — used by probe and by the smart-detector path of the existing upload controllers. Enrich keeps using the DOM reader (it has to write every cell back); heap-guard at enrich time protects this.

## Test cases

1. **72MB BOM-Implosion** at `/Users/vikasjindal/Documents/inputfiles_test/gigantic/BOM-Implosion-2026-04-14.xlsx` — uploaded to BOM Implode, Change History, Part Extract. Expect: probe < 5s, modal not shown (header confident), dedup chip shown, query runs, enrich heap-rejects with the friendly message on a 4g heap (or runs successfully on 16g).
2. **Small file, header column = "Item Number"** — confident, no modal, query runs.
3. **Small file with no header row** (just numbers) — header-heuristic fails, AI fires, returns col 0 with "ai-fallback" method, confident, no modal.
4. **Weird file (random text in row 1, numbers below)** — both tiers fail, modal opens with sample values per column.
5. **`?itemColumn=2` override** — detection skipped, column 2 used directly.

## Out of scope

- Agile Lookup (microservice).
- BOM Compare wizard (already has manual picker).
- Extensions / ECN-SLA / Help docs uploads (not item-list shaped).
- Field Changes (no upload).
