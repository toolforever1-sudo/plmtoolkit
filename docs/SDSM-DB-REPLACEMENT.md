# SDSM Attachment-Pull — DB-Only Replacement (Discovery Notes)

Source of truth being replaced: `~/documents/sdsm/sdsmDataUpload_5_31_2023.jar` (`com.wdc.sdsmattachments.GetAndSummarizeAttachments`), invoked nightly on a Windows server via `call_run.bat → RunSDSMFileDownload.bat`. The job runs three Agile saved searches, downloads PDF attachments to a network share, and writes a `summary_<yyyyMMdd>.xlsx` mapping file consumed by the Penang factory MES (`ulssvmsapip01d-498-d02.corp.sandisk.com`).

This doc captures everything discovered against `agile_prod` (`uls-dp-oraagile.wdc.com:1521:agprod`, `agile`/`tartan`).
- 2026-05-13 first pass (gaps captured in §6).
- 2026-05-13 follow-up: §6.1–§6.4 verified live against agprod via the universal-db MCP and an Agile Java Client screenshot of the Document Style attribute editor; corrections folded in below. See §10 for the diff.

Goal: implement the same summary file via direct SQL inside `plm-field-tracker`, dropping the Agile SDK dependency.

> Companion to `SKU-DATA-HANDOFF.md`. Where the SKU doc covered the SKU subclass on `ITEM`, this one covers the **Document, Parts, and Deviation** subclasses plus **`ATTACHMENT_FULL_MAP`** (the attachment vault).

---

## 1. The three saved searches and their SQL equivalents

| Saved search | Object scope | UI filter | SQL equivalent (predicates only) |
|---|---|---|---|
| `/Personal Searches/SDSM/FindAttachmentsForSDSM_DocumentStyle` | Items / Document subclass | `Document Style IS NOT NULL AND Filename IS NOT NULL` | `i.CLASS=9000 AND p3.LIST36 IS NOT NULL AND EXISTS(...ATTACHMENT_FULL_MAP join...)` |
| `/Personal Searches/SDSM/FindAttachmentsForSDSM_FromPartsDocumentStyle` | Items / Parts subclasses | `Document Style IS NOT NULL AND Filename IS NOT NULL AND Product Group IS NOT NULL` | `i.CLASS=10000 AND i.SUBCLASS != 251736330 AND p2.LIST72 IS NOT NULL AND p2.MULTILIST11 IS NOT NULL AND EXISTS(...attachment...)` ✅ §6.1 verified universal across all Parts subclasses |
| `/Personal Searches/SDSM/Deviations` | Changes / Deviation subclass | `Status IN (QN.Executed,…) AND Effective dates ≤ today AND Customers Impacted/Product Group/Product/Spec/Document Style IS NOT NULL AND Attachment Type IN (Quality Notice)` | `c.CLASS=8000 AND c.SUBCLASS=20336 AND c.STATUS=251745989 AND c.WORKFLOW_ID=251745973 AND c.EFFECTIVE_FROM<=SYSDATE AND c.EFFECTIVE_TO>=SYSDATE AND attachment join filtered to Quality Notice (ATTACHMENTTYPE=3566238)` |

## 2. Class / subclass / NODETABLE constants

| Concept | NODETABLE.ID | Meaning |
|---|---|---|
| ITEM.CLASS = Documents | `9000` | Documents class |
| ITEM.CLASS = Items (Parts parent) | `10000` | All Parts subclasses live under this |
| ITEM.SUBCLASS = SKU | `251736330` | **Exclude** in Parts pass |
| Common Parts subclass = "Part" | `10141` | Most populous Parts subclass |
| CHANGE.CLASS = Changes | `8000` | All change types |
| CHANGE.SUBCLASS = DCO | `251733391` | Document Change Order (24K rows) |
| CHANGE.SUBCLASS = ECO | `6141` | Engineering Change Order (288K rows — the dominant one; do not use 251731131 / 251735258 / 20055) |
| CHANGE.SUBCLASS = Deviation | `20336` | Used for QN deviations (13K rows) |
| CHANGE.STATUS = Implemented | `251733425` | For DCO/ECO history filter |
| CHANGE.STATUS = Executed (QN) | `251745989` | "Released/Executed" under QN workflow |
| CHANGE.WORKFLOW_ID = Quality Notice Workflow | `251745973` | Use to scope Deviation status filter |
| LISTENTRY.PARENTID = Document Style vocabulary | `251746283` | The "Document category/type list" (per Java Client admin view). Always join with this to filter to legitimate Document Style values. |
| LISTENTRY.PARENTID = Attachment Type vocabulary | `4682` | The attachment-type list (Quality Notice, Training Record, Final Document, Email Notification, Drawing, VIC, etc.). |
| LISTENTRY ENTRYID = Quality Notice | `3566238` | The attachment-type filter value the Deviations pass needs. |
| LISTENTRY ENTRYID = QN (doc style) | `3914115` | The Document Style value used by every QN deviation. |
| Lifecycle Phase parent | (lookup via REV.RELEASE_TYPE → NODETABLE.DESCRIPTION) | Same pattern as SKU doc §"Where Lifecycle Phase Lives" |

To safely match by name (in case constants drift):
```sql
SELECT ID FROM NODETABLE WHERE DESCRIPTION = 'ECO'
SELECT ENTRYID FROM LISTENTRY WHERE ENTRYVALUE = 'Quality Notice' AND LANGID = 0
```

## 2a. The +30 offset rule (PAGE_THREE attribute storage)

Confirmed live via the Agile Java Client admin dialog for the Document subclass "Document Style" attribute:

- **Name** = "Document Style"
- **API Name** = `list06`
- **List** = "Document category/type list" (List ID 251746283)
- **Attribute** (physical column) = `PAGE_THREE.LIST36`

Agile applies a **+30 offset** when an attribute whose API name is `listNN` (or `multiListNN`, `textNN`, etc.) is stored on a PAGE_THREE-resident node. So `list06` on a PAGE_THREE attribute → `PAGE_THREE.LIST36`.

Implications for this doc:
- Documents (ITEM.CLASS=9000, SUBCLASS=9141): API Name `list06` → `PAGE_THREE.LIST36` ✓
- Deviations (CHANGE.SUBCLASS=20336, attributes parent 20337): NODETABLE 251748092 has API Name `list06` and lives on PAGE_THREE → `PAGE_THREE.LIST36` (NOT PAGE_TWO.LIST06 as originally guessed). 676 of 13,232 deviations have it populated.
- Parts (ITEM.CLASS=10000): the Document Style attribute is configured on PAGE_TWO directly — there's no +30 offset on PAGE_TWO native columns. Empirically `PAGE_TWO.LIST72` for every subclass that uses it.

When in doubt, the Java Client → Admin → Classes → <subclass> → Attributes → <attribute> dialog shows both the API Name AND the resolved physical column ("Attribute" field). That's the authoritative source.

## 3. Attribute storage map

The Java reads "base IDs" from `AgileSDSM.properties`. Each one is a NODETABLE entry whose `DESCRIPTION` describes the logical attribute and whose physical storage depends on (a) class, (b) page, and (c) the +30 offset rule (see §2a).

### Documents (ITEM.CLASS=9000)

| Logical field | Storage | Resolve | Confirmed via |
|---|---|---|---|
| Document Style | `PAGE_THREE.LIST36` (NODETABLE 251746284, PARENTID 1628 = subclass 9141 attributes, API name `list06`) | `LISTENTRY` LANGID=0, PARENTID=251746283 | `06-05-WW-02-00002` LIST36=3910043 → "VIC" ✓ |
| Product Group | `AGILE_FLEX` ATTID `251746287`, `TEXT` is CSV of LISTENTRY IDs | `LISTENTRY` per token | `06-05-WW-02-00002` TEXT=`,3572920,` |
| Product | `AGILE_FLEX` ATTID `251747721`, `TEXT` is CSV of **`ITEM.ID`s** (multi-Item-reference, NOT LISTENTRY) | `ITEM.ITEM_NUMBER` per token, then `ITEM.LIFECYCLE` for OBS pruning | `06-05-WW-02-00002` TEXT=`,542680927,` |
| Spec (step code) | `AGILE_FLEX` ATTID `251746291`, `TEXT` is CSV of LISTENTRY IDs (can be very long — 142 tokens for the VIC doc) | `LISTENTRY` per token | matches |
| Item Type display | `NODETABLE.DESCRIPTION WHERE ID = ITEM.SUBCLASS` | direct | "Document" / "Part" / "L000-Label Printing Inst" |
| Lifecycle Phase | `REV.RELEASE_TYPE → NODETABLE.DESCRIPTION` | same as SKU doc | works |
| Rev letter | `REV.REV_NUMBER` | direct (string column "1","2","A","B"...) | works |

### Parts (ITEM.CLASS=10000, exclude SUBCLASS 251736330)

| Logical field | Storage | Confirmed via |
|---|---|---|
| Document Style | `PAGE_TWO.LIST72` — **uniform across every Parts subclass** (see §6.1 sweep) | Parts: 6,413 hits in subclass 10141 (`Part`), plus 447 in D026, 121 in E007-PCB, etc. — total 7,066 items across 9 subclasses. No Parts subclass uses any other LIST column. |
| Product Group | `PAGE_TWO.MULTILIST11` (ATTID 2000008070) | ML11=`,3903062,` → "OMAHA HHHL" |
| Product | `PAGE_TWO.MULTILIST09` (ATTID 2000008068, **Item-ref CSV**) | ML09=`,542680927,` (an ITEM.ID) |
| Spec | `PAGE_TWO.MULTILIST10` (ATTID 2000008069, LISTENTRY CSV) | 22 spec codes resolved |

✅ **Document Style column is uniform** — earlier worry that it varied by subclass turned out to be unfounded. PAGE_TWO.LIST72 holds it for every Parts subclass that uses Document Style at all (Part, E006-Substrate, L000-Label Printing Inst, D026-Work Instructions, E007-PCB, D000-Procedure, D006-Schematic, D010-Assembly/Placement Drawing, D017-Specifications). No per-subclass column map needed.

### Deviations (CHANGE.SUBCLASS=20336)

| Logical field | Storage | Confirmed via |
|---|---|---|
| Document Style | `PAGE_THREE.LIST36` (NODETABLE 251748092, PARENTID 20337 = Deviation attributes, API name `list06`, +30 offset rule). **NOT** PAGE_TWO.LIST06 / ATTID 1544. | D16785-SDSM-QN: `PAGE_THREE.LIST36 = 3914115` → "QN". 676 / 13,232 deviations have a value; QN deviations all use ENTRYID 3914115. |
| Product Group | `AGILE_FLEX` ATTID `251748095`, TEXT CSV LISTENTRY | D16785 TEXT=`,4012020,` |
| Product | `AGILE_FLEX` ATTID `1566` (MULTILIST03) — Item-ref CSV | (D16785 had no flex row for 1566 — likely on PAGE_TWO.MULTILIST03 directly) |
| Spec | `AGILE_FLEX` ATTID `251748093`, TEXT CSV LISTENTRY | D16785 TEXT=`,3913744,` |
| Customers Impacted | `PAGE_TWO.MULTILIST02` (NODETABLE 20950 `CustomerImpactedNew`, PARENTID 8005, INHERIT 2091 = MultiList02). CSV of LISTENTRY ENTRYIDs. | D16785 ML02=`,3259392,` → "Western Digital" ✓ |

> **Deprecated**: NODETABLE 23266 `*Customers Impacted - old` (INHERIT 1332 = MultiText32) — ignore. The active attribute is 20950 (MultiList02).

## 4. The attachment chain — `ATTACHMENT_FULL_MAP`

This is the breakthrough. Agile maintains a denormalized view that ties everything together — **use it for both reading file metadata and finding vault paths**:

```sql
SELECT afm.PARENT_ID  AS item_id,        -- == ITEM.ID
       afm.PARENT_ID2 AS change_id,      -- == CHANGE.ID (the change that introduced the attachment; 0 for Deviations)
       afm.ATTACH_ID,                    -- == ATTACHMENT.ID
       afm.ATTACHMENT_NUMBER,            -- e.g. 'FOLDER2127953'
       afm.ATTACHMENTTYPE,               -- LISTENTRY ENTRYID for the attachment-type list (PARENTID 4682)
       afm.FILEID,                       -- == FILES.ID
       afm.FILEFILENAME,                 -- e.g. '06-05-WW-02-00002_..._Rev 22 Final Document.pdf'
       afm.FILEFILE_TYPE,                -- e.g. 'pdf', 'docx', 'eml', 'xlsx'
       afm.FILEFILE_SIZE,                -- bytes
       fi.IFS_FILEPATH                   -- vault path e.g. '005/782/899/agile578289967.pdf'
  FROM ATTACHMENT_FULL_MAP afm
  LEFT JOIN FILE_INFO fi ON fi.FILE_ID = afm.FILEID
 WHERE afm.PARENT_ID = :item_or_change_id
```

**ATTACHMENTTYPE resolution** (LISTENTRY PARENTID = 4682). Verified live; the original doc had two of these reversed:

| ENTRYID | ENTRYVALUE | Used by |
|---|---|---|
| **3566238** | **Quality Notice** | Deviations pass attachment filter |
| **3566239** | **Training Record** | (not the QN filter — easy mistake) |
| 2441735 | Final Document | seen on Documents' PDFs |
| 2441734 | Email Notification | seen on D16785's `.eml` row |
| 3903017 | Drawing | doc-style attachment subtype |
| 3903019 | VAR | doc-style attachment subtype |
| 3903020 | VIC | doc-style attachment subtype |
| 3903021 | WI | doc-style attachment subtype |

⚠ Earlier draft had `3566239 = Quality Notice` — that's wrong. The Deviations pass needs `ATTACHMENTTYPE = 3566238`. On D16785-SDSM-QN, that filter returns the `.pdf` + `.xlsx` Quality Notice pair (correct). The doc's §9 had named the wrong file as the Quality Notice PDF — see updated §9.

**Vault path convention**: `IFS_FILEPATH` like `005/782/899/agile578289967.pdf` is rooted in the Agile File Vault filesystem on the Agile app server. The replacement either:
- mounts that filesystem and reads bytes directly, OR
- calls the Agile File Vault HTTP API by attachment ID, OR
- keeps the existing SDK call **just for byte streaming** and uses SQL for everything else.

**For the Java SDK's `attachmentrow.getValue(ATT_ATTACHMENTS_FILE_NAME)` / `FILE_TYPE` / `FILE_SIZE`**: that maps to `FILEFILENAME` / `FILEFILE_TYPE` / `FILEFILE_SIZE` on `ATTACHMENT_FULL_MAP`.

**Per-revision filtering**: the Java pins the item to the latest implemented DCO/ECO and then iterates `loItem.getAttachmentTable()`. In SQL, `PARENT_ID2 = <change_id>` selects the attachments tied to a specific change.

## 5. End-to-end SQL: latest implemented DCO + its PDFs for a Document

This reproduces what the Java does for the Documents pass, for one item:

```sql
WITH latest_dco AS (
  SELECT r.REV_NUMBER, r.RELEASE_DATE, r.CHANGE, r.ITEM
    FROM REV r
    JOIN CHANGE c ON c.ID = r.CHANGE
   WHERE r.ITEM    = :item_id
     AND c.STATUS  = 251733425                  -- Implemented
     AND c.SUBCLASS = 251733391                 -- DCO
   ORDER BY r.RELEASE_DATE DESC
   FETCH FIRST 1 ROW ONLY
)
SELECT ld.REV_NUMBER,
       afm.FILEFILENAME,
       afm.FILEFILE_TYPE,
       afm.FILEFILE_SIZE,
       fi.IFS_FILEPATH
  FROM latest_dco ld
  JOIN ATTACHMENT_FULL_MAP afm
    ON afm.PARENT_ID  = ld.ITEM
   AND afm.PARENT_ID2 = ld.CHANGE
  LEFT JOIN FILE_INFO fi ON fi.FILE_ID = afm.FILEID
 WHERE afm.FILEFILE_TYPE = 'pdf';
```

**Validated on `06-05-WW-02-00002`** — returned `06-05-WW-02-00002_..._Rev 22 Final Document.pdf` from change `DCO-521415-SDSM`, vault path `005/782/899/agile578289983.pdf`. This file matches the most recent PDF in `~/documents/sdsm/ProductionFolder/Files/` for that item.

### 5a. Deviations pass — Quality Notice attachments for active QN deviations

Putting the verified pieces together:

```sql
SELECT c.CHANGE_NUMBER,
       le_doc.ENTRYVALUE        AS document_style,        -- PAGE_THREE.LIST36
       le_cust.ENTRYVALUE       AS customer_impacted,     -- PAGE_TWO.MULTILIST02 (token, after split)
       afm.FILEFILENAME,
       afm.FILEFILE_TYPE,
       afm.FILEFILE_SIZE,
       fi.IFS_FILEPATH
  FROM CHANGE c
  JOIN PAGE_THREE p3        ON p3.ID = c.ID
  JOIN PAGE_TWO   p2        ON p2.ID = c.ID
  JOIN ATTACHMENT_FULL_MAP afm
    ON afm.PARENT_ID = c.ID
   AND afm.ATTACHMENTTYPE = 3566238                       -- Quality Notice (was 3566239, corrected)
  LEFT JOIN FILE_INFO fi    ON fi.FILE_ID = afm.FILEID
  LEFT JOIN LISTENTRY le_doc
              ON le_doc.ENTRYID = p3.LIST36
             AND le_doc.LANGID  = 0
             AND le_doc.PARENTID = 251746283              -- Document category/type list
  LEFT JOIN LISTENTRY le_cust
              ON INSTR(p2.MULTILIST02, ',' || TO_CHAR(le_cust.ENTRYID) || ',') > 0
             AND le_cust.LANGID = 0
 WHERE c.SUBCLASS    = 20336                              -- Deviation
   AND c.STATUS      = 251745989                          -- Executed
   AND c.WORKFLOW_ID = 251745973                          -- Quality Notice Workflow
   AND c.EFFECTIVE_FROM <= SYSDATE
   AND c.EFFECTIVE_TO   >= SYSDATE
   AND p3.LIST36 IS NOT NULL                              -- Document Style filter
   AND p2.MULTILIST02 IS NOT NULL                         -- Customers Impacted filter
```

> The `,ENTRYID,` comma-anchored INSTR pattern prevents the substring-collision trap (e.g. ENTRYID `925` falsely matching inside `3259392`). `MULTILIST02` is `VARCHAR2`, so plain `INSTR` works — do not use `DBMS_LOB.INSTR`.

## 6. Open items — status after live verification

✅ = closed  /  ⚠ = needs decision (not a SQL question)  /  ⏳ = still open

### 6.1 Parts Document Style column per subclass — ✅ resolved

**Result**: `PAGE_TWO.LIST72`, **uniform across every Parts subclass**.

Method: swept every `LIST*` column on PAGE_TWO and PAGE_THREE for every Parts item (excluding SKU 251736330) and joined to LISTENTRY filtered to PARENTID 251746283 (Document Style vocabulary). Only LIST72 produced any matches. 9 subclasses × 7,066 items use it; no other column does.

### 6.2 Deviation Document Style column — ✅ resolved

**Result**: `PAGE_THREE.LIST36` (not PAGE_TWO.LIST06).

Method: NODETABLE 251748092 (PARENTID 20337 = Deviation attributes) has API name `list06`. Per the +30 offset rule confirmed in §2a, that maps to `PAGE_THREE.LIST36`. Empirically: 676 deviations have a value there; D16785-SDSM-QN reads `3914115` → "QN".

### 6.3 ATTACHMENTTYPE resolution — ✅ resolved (and corrected)

**Result**: `3566238 = Quality Notice`, `3566239 = Training Record`. Earlier draft had them swapped.

Filter for the Deviations pass:
```sql
WHERE afm.ATTACHMENTTYPE IN (
  SELECT ENTRYID FROM LISTENTRY WHERE ENTRYVALUE = 'Quality Notice' AND LANGID = 0
)
-- evaluates to ENTRYID = 3566238
```

### 6.4 "Customers Impacted" attribute on Deviations — ✅ resolved

**Result**: `PAGE_TWO.MULTILIST02` (CSV of LISTENTRY ENTRYIDs).

Backed by NODETABLE 20950 (`CustomerImpactedNew`, DESCRIPTION `*Customers Impacted`, PARENTID 8005, INHERIT 2091 = MultiList02). On D16785: `,3259392,` → "Western Digital". Resolve via standard LANGID=0 LISTENTRY join with `DBMS_LOB.INSTR` substring test.

(NODETABLE 23266 `*Customers Impacted - old`, INHERIT 1332 = MultiText32, is the deprecated predecessor — ignore.)

### 6.5 OBS-product write-back behavior — ⚠ closed by decision

**Decision (Vikas, 2026-05-13)**: do not write back to Agile.

The replacement will exclude OBS / OBS-SKU products from the summary on read and leave Agile data untouched. Any Agile-side cleanup is somebody else's problem. This means the Java's `cell.setValue()` mutation in `GetAndSummarizeAttachments` is dropped entirely. Pure-SQL replacement is straightforward:

```sql
-- Filter out OBS products when expanding the Product CSV (Documents pass example).
-- REV is joined via DEFAULT_CHANGE = r.CHANGE per SKU-DATA-HANDOFF.md — never via LATEST_FLAG.
JOIN ITEM target ON target.ITEM_NUMBER = product_token
JOIN REV  target_rev
       ON target_rev.ITEM   = target.ID
      AND target.DEFAULT_CHANGE = target_rev.CHANGE
      AND target_rev.SITE   = 0
LEFT JOIN NODETABLE life
       ON life.ID = target_rev.RELEASE_TYPE
WHERE life.DESCRIPTION NOT IN ('OBS','OBS-SKU') OR life.DESCRIPTION IS NULL
```

### 6.6 Vault file streaming — ⏳ still open (design decision)

`FILE_INFO.IFS_FILEPATH` (e.g. `005/782/899/agile578289967.pdf`) gives the path inside the Agile vault. Streaming bytes is **not a SQL operation** — pick one:

- **(a) Mount the vault filesystem** on whatever runs the replacement and read directly.
- **(b) Use the Agile File Vault HTTP endpoint** (`http://<vault>/Filemgr/...`) by attachment ID.
- **(c) Keep `IAttachmentFile.getFile()` SDK call for downloads only** — everything else stays SQL.

Closest existing reference in the toolkit: `~/git/plm-agile-service` already wraps SDK calls. Could expose a `GET /file/{attachId}` shim there if option (c) is preferred.

## 7. What's locked down

- All three search WHERE clauses → SQL predicates ✅ (§6.1, §6.2, §6.3, §6.4 all closed)
- All five attribute base IDs in `AgileSDSM.properties` decoded to physical columns ✅
- Latest-implemented-DCO / latest-implemented-ECO logic → straight `REV+CHANGE` join with `STATUS=251733425` and `SUBCLASS IN (DCO,ECO)` ✅
- Attachment chain → single `ATTACHMENT_FULL_MAP` view with all metadata ✅
- ATTACHMENTTYPE filter constants ✅ (3566238 = Quality Notice; corrected from earlier draft)
- Spec/Product/Product-Group multi-list explosion (one summary row per spec token) → `regexp_substr` on the CSV in SQL
- OBS-SKU exclusion on referenced products → `JOIN ITEM target ON target.ITEM_NUMBER = ... AND lifecycle_phase NOT IN ('OBS','OBS-SKU')` (read-only filter; no write-back per §6.5)

Outstanding: §6.6 (vault file streaming) only.

## 8. Recommended implementation shape (for reference, not yet coded)

Mirror `SkuDataService` from the SKU handoff. One service per pass:

```
SdsmDocumentsService.run()   →  emits rows for the Documents pass
SdsmPartsService.run()       →  emits rows for the Parts pass
SdsmDeviationsService.run()  →  emits rows for the Deviations pass
SdsmSummaryService.write()   →  composes summary_<yyyyMMdd>.xlsx (POI), prepends sentinel row, mirrors to staging share
```

Reuse the SKU-style `pre-load LISTENTRY into HashMap` trick (one query, ~100K rows, sub-second) instead of correlated-subquery resolution per row.

Spring `@Scheduled` on the 2 AM slot (matching the existing Windows Task Scheduler cadence) once verified.

Note: no write-back to Agile in any service — see §6.5.

## 9. Sample data for sanity-checks

Item we used to verify Documents pass:
- `06-05-WW-02-00002` (`ITEM.ID=514897371`, SUBCLASS=9141)
- Latest implemented DCO: `DCO-521415-SDSM` (Rev 22), released 2026-03-03
- Document Style: VIC (`PAGE_THREE.LIST36` = 3910043, vocabulary 251746283)
- Latest PDF filename: `06-05-WW-02-00002_..._Rev 22 Final Document.pdf` (6.2 MB) at vault path `005/782/899/agile578289983.pdf`

Item we used to verify Parts pass:
- `30-08-60003` (`ITEM.ID=513286441`, SUBCLASS=10141)
- Document Style: Drawing (`PAGE_TWO.LIST72` = 3572877, vocabulary 251746283)
- Product Group: OMAHA HHHL (PAGE_TWO.MULTILIST11)
- Spec list: 22 step codes (PAGE_TWO.MULTILIST10)

Deviation we used to verify Deviations pass:
- `D16785-SDSM-QN` (`CHANGE.ID=579873850`, SUBCLASS=20336)
- Status: Executed (`251745989`), Workflow: Quality Notice Workflow (`251745973`)
- Effective: 2026-04-23 → 2026-06-03
- Document Style: QN (`PAGE_THREE.LIST36` = 3914115, vocabulary 251746283) — corrected: doc style IS populated; was looking at wrong column originally
- Product Group: 4012020 (LISTENTRY) via AGILE_FLEX ATTID 251748095
- Spec: 3913744 (LISTENTRY) via AGILE_FLEX ATTID 251748093
- Customers Impacted: Western Digital (ENTRYID 3259392) at `PAGE_TWO.MULTILIST02 = ',3259392,'`
- Quality Notice attachments (filter `ATTACHMENTTYPE = 3566238`):
  - `D16785-SDSM QUALITY NOTICE FOR GUIDELINE TO INSPECT EXCESS SOLDER AND SLEEVE BURN.pdf` (310 KB)
  - `D16785-SDSM QUALITY NOTICE FOR GUIDELINE TO INSPECT EXCESS SOLDER AND SLEEVE BURN.xlsx` (1.9 MB)
- (For reference, the file `D16785-SDSM - Guideline to Inspect Excess Solder and Sleeve Burn.pdf` that earlier drafts named as the QN PDF is actually a **Training Record** — `ATTACHMENTTYPE = 3566239`. Don't pick it up in the Deviations pass.)

Confirm any new SQL the MCP/agent writes against these three.

## 10. Diff vs. earlier draft (for the next reviewer)

What changed in the 2026-05-13 verification pass:

| Section | Before | After |
|---|---|---|
| §1 row 3 | "filter to Quality Notice" (no constant) | adds explicit `ATTACHMENTTYPE = 3566238` |
| §2 | no LISTENTRY-vocabulary constants | adds 251746283 (Doc Style list), 4682 (Attach Type list), 3566238 (Quality Notice ENTRYID), 3914115 (QN doc-style ENTRYID) |
| §2a | (didn't exist) | new section: documents the +30 offset rule confirmed via Java Client admin screenshot |
| §3 Deviations / Document Style | "PAGE_TWO.LIST06 (ATTID 1544) — needs confirmation" | corrected to `PAGE_THREE.LIST36` (NODETABLE 251748092, +30 offset) |
| §3 Deviations / Customers Impacted | "TBD" | resolved to `PAGE_TWO.MULTILIST02` (NODETABLE 20950, INHERIT 2091) |
| §3 Parts | "Document Style column may vary by Parts subclass" warning | warning removed; uniform `PAGE_TWO.LIST72` confirmed across all 9 used subclasses |
| §4 ATTACHMENTTYPE table | 3566239 = Quality Notice; 2441735 = "some doc-attach type" | 3566238 = Quality Notice (was swapped); 3566239 = Training Record; 2441735 = Final Document |
| §5a | (didn't exist) | new end-to-end Deviations-pass query putting all the verified mappings together |
| §6.1, §6.2, §6.3, §6.4 | open verification asks | all marked ✅ resolved with method + sample |
| §6.5 | open question | closed by decision: no write-back to Agile |
| §6.6 | open | unchanged — still a design choice |
| §9 | named the Training Record PDF as "Quality Notice PDF" | corrected — lists the actual QN .pdf+.xlsx pair, and notes the misattribution |
