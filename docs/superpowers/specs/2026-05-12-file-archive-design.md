# File Archive — Track every upload and download

**Status:** Approved (knobs locked 2026-05-12)
**Owner:** Vikas Jindal
**Date:** 2026-05-12

## Problem

When something goes wrong with a user-supplied file (e.g. the Agile Lookup HTTP 500 on PT-X earlier today), admins have no way to reproduce the failure without going back to the user and asking *"send me the xlsx again."* Same for generated downloads: if a user says *"the export you gave me is wrong,"* we can re-run the report but we can't verify which exact bytes they got.

## Goal

Capture every file that crosses the toolkit's user boundary — uploads AND downloads — so any admin can identify what file was involved, and (for uploads) replay it. Without burning the disk.

## Decisions locked

| # | Decision | Value |
|---|---|---|
| 1 | Bytes vs metadata | Bytes for **uploads**; metadata-only (filename + size + SHA-256) for **downloads** |
| 2 | Retention — upload bytes | 30 days, then bytes purged; index row kept |
| 3 | Retention — index rows | 90 days, then row dropped |
| 4 | Scope | Phase 1 (uploads) + Phase 2 (downloads), both shipped in this iteration |

## Architecture

### Storage layout

```
D:\plm-toolkit\file-archive\
├── uploads\
│   └── 2026\05\12\<username>\<HHMMSS-mmm>__<8-char-id>__<sanitized-original-name>
└── archive-index.jsonl
```

- One bucket per day per user. Predictable nesting, easy to inspect on the share.
- Filename inside the bucket: `<HHMMSS-mmm>__<id>__<original>` — sortable, unique, preserves what the user named it (sanitized for filesystem safety).
- Index is a single newline-delimited JSON file. Rotated by the daily purge job when entries are dropped.

### Index entry

```json
{
  "id": "a7c3f9b2",
  "ts": "2026-05-12T20:14:23Z",
  "direction": "upload",
  "user": "8252",
  "displayName": "Vikas Jindal",
  "route": "/api/items/lookup-from-xlsx",
  "feature": "agile-lookup",
  "filename": "SKU list of W30 SKU status.xlsx",
  "bytes": 1248327,
  "sha256": "a3f12e5c7b89...",
  "ptId": null,
  "archivedPath": "uploads/2026/05/12/8252/201423-577__a7c3f9b2__SKU_list_of_W30_SKU_status.xlsx",
  "purged": false,
  "permanent": false
}
```

Download entries differ:
- `direction: "download"`
- No `archivedPath` (metadata-only)
- `purged` always `false` (no bytes to purge)
- All other fields populated

### `FileArchiveService` (new)

```java
public class FileArchiveService {
    /** Persist the uploaded bytes + index entry. Returns the assigned id. */
    String recordUpload(MultipartFile file, String user, String displayName,
                        String route, String feature, String ptId);

    /** Compute SHA-256 + index entry only — no bytes stored. Returns the assigned id. */
    String recordDownload(String filename, byte[] bytes, String user, String displayName,
                          String route, String feature);

    /** For streamed exports: feed a chunked stream, get back a "tap" that hashes as it flows. */
    OutputStream tapDownload(String filename, OutputStream sink, String user, ...);

    /** Admin: list events with filters. */
    List<Map<String,Object>> list(String userFilter, LocalDate from, LocalDate to,
                                  String featureFilter, String directionFilter, int limit);

    /** Admin: open an archived upload for download. Throws if purged. */
    InputStream openArchive(String id);

    /** Admin: mark an upload permanent (skip purge). */
    void markPermanent(String id, boolean flag);

    /** Scheduled: purge bytes older than 30 days; drop index rows older than 90 days. */
    PurgeResult runPurge();
}
```

Design notes:
- **Append-only writes** to the index file via a single `synchronized` block. JSONL means partial writes are recoverable (each line is independent).
- **Hash computed via `MessageDigest.getInstance("SHA-256")`** — fast enough that hashing 50 MB on the way through is negligible compared to the disk I/O.
- **Failed uploads ARE captured.** The capture happens before the downstream handler runs, so even uploads that 500 in the agile-service are preserved — that's the whole point.
- **Purge is a single scheduled task** at 03:00 daily. Reads the index, decides per-row what to do (delete bytes / drop row), rewrites the index atomically (write to `.new`, fsync, rename).

### Capture points

**Phase 1 — uploads** (call `recordUpload` at the top of each handler, before any work):

| Endpoint | Controller | Feature tag |
|---|---|---|
| `/api/items/lookup-from-xlsx` | `AgileLookupController` | `agile-lookup` |
| `/api/sku-data/lookup-from-xlsx` | `SkuDataController` | `sku-lookup` |
| `/api/bom/compare/upload` (and variants) | `BomController` | `bom-compare` |
| `/api/part-extract/upload` | `PartExtractController` | `part-extract` |
| `/api/support/feedback` (per attachment) | `SupportController` | `feedback-attachment` |
| `/api/data-compare/*` upload paths | `DataCompareController` | `data-compare` |
| Any other multipart endpoint (grep for `MultipartFile`) | — | — |

**Phase 2 — downloads** (call `recordDownload` right before returning the bytes):

| Endpoint | Feature tag |
|---|---|
| All BOM Excel exports | `bom-export` |
| Change History export | `change-history-export` |
| Field Changes export | `field-changes-export` |
| ECN Report Excel | `ecn-report-export` |
| Volume Report Excel | `volume-report-export` |
| Team Report Excel | `team-report-export` |
| Single/Sole Source Excel | `single-sole-source-export` |
| AI Eval run export | `ai-eval-export` |
| Part Extract result download | `part-extract-export` |
| Feedback attachment download | `feedback-attachment-download` |
| Delta report download | `delta-report-export` |

For each, the simplest wrapper:

```java
byte[] xlsx = svc.generateXlsx(...);
fileArchive.recordDownload(filename, xlsx, username, displayName, route, "bom-export");
return ResponseEntity.ok().headers(...).body(xlsx);
```

For streamed exports (rare but exist for very large files), use `tapDownload(...)` so we don't have to buffer the whole thing in memory.

### Admin UI

New "**Admin → File Archive**" menu link → modal viewer:

- **Filters row:** user, date range, feature, direction (upload/download), free-text filename search
- **Table columns:** timestamp · user · feature · filename · size · direction · SHA-256 (truncated) · [Download] (uploads only, hidden if purged) · [Pin] (mark permanent)
- **Pagination:** 50 entries per page, "Load more" button
- **Actions:** Download (uploads), Pin/Unpin, Copy SHA-256 to clipboard

Path: `/api/admin/file-archive` (list), `/api/admin/file-archive/{id}` (download bytes), `/api/admin/file-archive/{id}/pin` (toggle permanent).

All gated by `isPlmAdmin`. Every view + download recorded as `ADMIN_FILE_ARCHIVE_VIEW` / `ADMIN_FILE_ARCHIVE_DOWNLOAD` activity entries.

## Configuration

Added to `application.properties`:

```
app.file-archive.dir=./file-archive
app.file-archive.retention-bytes-days=30
app.file-archive.retention-index-days=90
app.file-archive.max-upload-bytes=26214400      # 25 MB — matches multipart caps
```

External prod config will override `app.file-archive.dir=D:/plm-toolkit/file-archive`.

## Security / privacy

- Archive dir not statically served (no `/static/file-archive/` route exists).
- Admin-only access via `isPlmAdmin` check on every endpoint.
- Filenames sanitised before being written to disk (`Pattern.compile("[^A-Za-z0-9._-]")` → `_`).
- Path traversal blocked at retrieval: `id` lookup goes through the JSONL index, never accepts paths.
- SHA-256 logging is privacy-friendly — admins can identify duplicate submissions without seeing contents.

## Capacity sizing

With locked knobs:
- 50 users × 5 uploads/day × 10 MB avg = 2.5 GB/day uploads
- 30-day retention = **75 GB peak** disk for upload bytes
- Index growth ≈ 1 KB per event × 50 users × 15 events/day × 90 days = **~70 MB** for the index file
- Comfortably under typical Windows D:\ free space; if it ever pinches, lower `retention-bytes-days` to 14 in the external config without a rebuild.

## Out of scope

- Encryption at rest (the share is access-controlled; if needed later, BitLocker covers it)
- Cross-instance replication (single-prod deployment)
- Per-feature retention overrides (one global retention number is sufficient for now)
- Auto-link archive entries to Bug Reports (today the link is manual — user puts the archive id in the bug; later we can hook this up)
- Search by SHA-256 (easy to add later; not in the v1 admin viewer)

## Testing

Manual smoke tests after deploy:
1. Upload an xlsx to Agile Lookup → verify file appears in `file-archive/uploads/.../...` and in the index
2. Same for BOM Compare, SKU Lookup, Part Extract, Feedback (single + multi attachment)
3. Download a BOM Excel export → verify a `direction: download` entry appears, no bytes saved
4. Open the admin viewer → confirm filters work, download an upload, copy SHA-256
5. Try to access `/api/admin/file-archive` as a non-admin → 403
6. Try to access `/api/admin/file-archive/../../etc/passwd` → rejected (id is looked up in the index, not a path)
7. Run purge manually (admin endpoint or wait for the daily 03:00 tick) → confirm old upload bytes deleted, index row updated with `purged: true`

## Files changed (planned)

| File | Type |
|---|---|
| `service/FileArchiveService.java` | new |
| `controller/AdminFileArchiveController.java` | new |
| `static/admin-file-archive.js` | new |
| `static/index.html` | menu link + script tag |
| `static/whats-new.js` | release entry |
| `application.properties` | new config keys |
| `AgileLookupController.java` | call `recordUpload` |
| `SkuDataController.java` | call `recordUpload` |
| `BomController.java` (compare upload) | call `recordUpload` |
| `PartExtractController.java` | call `recordUpload` |
| `SupportController.java` (feedback attachments) | call `recordUpload` |
| `DataCompareController.java` | call `recordUpload` |
| `~10 export controllers` | call `recordDownload` |
| `ScheduledTasksService` (or equivalent) | daily purge tick |
