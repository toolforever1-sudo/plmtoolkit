---
status: draft
date: 2026-05-08
author: Vikas Jindal
implements: ECN-128313-PROJ
replaces: SingleSourceReport.jar (Windows batch, F:\Batch\Shruthi\)
---

# Single/Sole Source Report — Design

## 1. Goal

Replace the standalone `SingleSourceReport.jar` Windows batch job (`F:\Batch\Shruthi\`) with a tab inside the PLM Toolkit. The new tab serves the **monthly Single/Sole Source review report** sent to PLM stakeholders.

**Hard constraints**
- **Zero Agile SDK calls.** All data comes from direct Oracle reads against `agprod` (the toolkit's existing primary `DataSource`).
- **Read-only.** No writes to Agile.
- **Take over the whole job** — on-demand UI download **and** the monthly scheduled email + SharePoint upload. The Windows batch is decommissioned.

## 2. Output Shape

One `.xlsx` written into the existing template `Single-Sole Source Report Template.xlsx` (kept under `src/main/resources/templates/`). The template owns formatting; the service writes data starting at row 3 of each tab.

| Tab | Filter | Row granularity |
|-----|--------|-----------------|
| **Designation Needed** | `single_sole_source = 'Designation Needed'` | one row per item (first MPN if multiple) |
| **Single Source**      | `single_sole_source = 'Single Source'`     | one row **per active MPN** (item rows repeat) |
| **Sole Source**        | `single_sole_source = 'Sole Source'`       | one row **per active MPN** (item rows repeat) |
| Single-Sole Source Impacted SKU | (skipped — future ECN) | n/a |

Common filters across all three:
- Item is a **Part** (`SUBCLASS` is a child of `NODETABLE.ID = 10004`)
- `NVL(ITEM.DELETE_FLAG, 0) <> 1`
- `Lifecycle Phase` **NOT IN** `('OBS', 'OBS-SKU', 'Preliminary')`

Sort: `Rev Release Date DESC, Number ASC`.

### Column mapping (15 columns, columns A–O)

| # | Header | DB source |
|---|--------|-----------|
| A | Product Line | `ITEM.PRODUCT_LINES` (CSV of LISTENTRY ENTRYIDs → `LISTENTRY.ENTRYVALUE` joined with `; `) |
| B | Number | `ITEM.ITEM_NUMBER` |
| C | Description | `ITEM.DESCRIPTION` (newlines/tabs squashed to space) |
| D | Lifecycle | `REV.RELEASE_TYPE` → `NODETABLE.DESCRIPTION` |
| E | Rev | `REV.REV_NUMBER` |
| F | MPN Count | `PAGE_TWO.TEXT67` (parsed to integer) |
| G | Single/Sole Source (Current) | `PAGE_TWO.LIST77` → `LISTENTRY.ENTRYVALUE` |
| H | Single/Sole Source (To Be) | **left blank** (stakeholders fill in) |
| I | Part Type | `ITEM.SUBCLASS` → `NODETABLE.DESCRIPTION` |
| J | Material Group | `PAGE_TWO.LIST20` → `LISTENTRY.ENTRYVALUE` |
| K | Create Date | `TRUNC(PAGE_TWO.DATE04)` |
| L | Last Release Date | `TRUNC(REV.RELEASE_DATE)` |
| M | Manufacturer Name | `MANUFACTURERS.NAME` (via `MANU_BY → MANU_PARTS → MANUFACTURERS`, only `MANU_BY.CHANGE_OUT = 0`) |
| N | Manufacturer Part Number | `MANU_PARTS.PART_NUMBER` |
| O | Preferred Status | `MANU_BY.PREFER_STATUS` → `LISTENTRY.ENTRYVALUE` |

**Picklist key constants**
- `Single/Sole Source` parent: `LISTENTRY.PARENTID = 251754508` → entries: `4026098 Designation Needed`, `4026100 Multi Source`, `4026101 NA`, `4026102 Single Source`, `4026103 Sole Source`. Service filters on `LIST77 IN (4026098, 4026102, 4026103)`.

## 3. SQL

One query, returned as a flat result set; the service partitions rows into three tabs in Java by `single_sole_source`.

```sql
WITH base AS (
  SELECT
    i.ID                AS item_id,
    i.ITEM_NUMBER       AS number_,
    REGEXP_REPLACE(i.DESCRIPTION, '[\r\n\t]+', ' ')  AS description,
    (SELECT LISTAGG(le.ENTRYVALUE, '; ')
              WITHIN GROUP (ORDER BY le.ENTRYVALUE)
       FROM AGILE.LISTENTRY le
      WHERE le.LANGID = 0
        AND INSTR(i.PRODUCT_LINES, ',' || le.ENTRYID || ',') > 0)   AS product_line,
    n_lcp.DESCRIPTION   AS lifecycle_phase,
    r.REV_NUMBER        AS rev,
    n_sub.DESCRIPTION   AS part_type,
    TO_NUMBER(NULLIF(TRIM(p2.TEXT67), ''))            AS mpn_count,
    le_ss.ENTRYVALUE    AS single_sole_source,
    le_mg.ENTRYVALUE    AS material_group,
    TRUNC(p2.DATE04)    AS create_date,
    TRUNC(r.RELEASE_DATE) AS rev_release_date
  FROM AGILE.ITEM i
  JOIN AGILE.REV       r     ON r.ITEM = i.ID
                            AND r.CHANGE = i.DEFAULT_CHANGE
                            AND r.SITE = 0
  JOIN AGILE.PAGE_TWO  p2    ON p2.ID = i.ID
  LEFT JOIN AGILE.NODETABLE  n_lcp ON n_lcp.ID = r.RELEASE_TYPE
  LEFT JOIN AGILE.NODETABLE  n_sub ON n_sub.ID = i.SUBCLASS
  LEFT JOIN AGILE.LISTENTRY  le_ss ON le_ss.ENTRYID = p2.LIST77 AND le_ss.LANGID = 0
  LEFT JOIN AGILE.LISTENTRY  le_mg ON le_mg.ENTRYID = p2.LIST20 AND le_mg.LANGID = 0
 WHERE NVL(i.DELETE_FLAG, 0) <> 1
   AND i.SUBCLASS IN (SELECT ID FROM AGILE.NODETABLE WHERE PARENTID = 10004)
   AND p2.LIST77 IN (4026098, 4026102, 4026103)
   AND NVL(n_lcp.DESCRIPTION, 'X') NOT IN ('OBS', 'OBS-SKU', 'Preliminary')
)
SELECT
  b.item_id, b.number_, b.description, b.product_line, b.lifecycle_phase, b.rev,
  b.part_type, b.mpn_count, b.single_sole_source, b.material_group,
  b.create_date, b.rev_release_date,
  mfr.NAME           AS mfr_name,
  mp.PART_NUMBER     AS mfr_part_number,
  le_ps.ENTRYVALUE   AS preferred_status
  FROM base b
  LEFT JOIN AGILE.MANU_BY       mb  ON mb.AGILE_PART = b.item_id AND mb.CHANGE_OUT = 0
  LEFT JOIN AGILE.MANU_PARTS    mp  ON mp.ID = mb.MANU_PART
  LEFT JOIN AGILE.MANUFACTURERS mfr ON mfr.ID = mp.MANU_ID
  LEFT JOIN AGILE.LISTENTRY     le_ps ON le_ps.ENTRYID = mb.PREFER_STATUS AND le_ps.LANGID = 0
 ORDER BY b.rev_release_date DESC NULLS LAST, b.number_
```

**Manufacturer expansion semantics**
- `MANU_BY` is `LEFT JOIN`ed, so items with no manufacturer still produce one row (with empty Mfr columns).
- For `Single Source` / `Sole Source`: every active MPN row is emitted (this is the one-row-per-MPN behavior).
- For `Designation Needed`: in Java, after grouping, take the first MPN row per `item_id` (matches existing batch behavior).

## 4. Component Inventory

```
src/main/java/com/sandisk/plm/tracker/
  controller/
    SingleSoleSourceController.java       NEW   REST: status, run-now, download, send-test
  service/
    SingleSoleSourceService.java          NEW   SQL → DTO list
    SingleSoleSourceExcelService.java     NEW   Template-based POI writer
    SingleSoleSourceScheduler.java        NEW   @Scheduled monthly run
    SingleSoleSourceSharePointUploader.java NEW Graph upload (ported verbatim from SSReport.java)
  model/
    SingleSoleSourceRow.java              NEW   POJO mirroring SQL columns
    SingleSoleSourceRunResult.java        NEW   { rowCounts, xlsxPath, sharepointUrl, emailSentTo, errors }
src/main/resources/
  templates/Single-Sole Source Report Template.xlsx   NEW   copied from /Users/vikasjindal/Documents/SingleSourceReport/
  static/
    index.html                            EDIT  add tab button + panel
    single-sole-source.js                 NEW   tab JS (status, run, download buttons)
    style.css                             (only if needed)
    whats-new.js                          EDIT  release entry per CLAUDE.md
  application.properties                  EDIT  config keys (see §6)
```

No existing code is refactored. The SharePoint Graph upload code is **copied** from `SSReport.java` verbatim (already battle-tested) into a dedicated service rather than rewritten — the Windows batch is being decommissioned, so the original is the right reference.

## 5. UI

A new top-level tab **"Single/Sole Source"** between **ECN Report** and **Reports/Utilities**. Tab is gated by the same admin/PLM permissions that gate ECN Report.

Tab layout (one panel, three subsections):

```
┌─ Single/Sole Source Report ────────────────────────────────────┐
│                                                                  │
│  [ Run Now ]   [ Download Latest .xlsx ]   [ Send Test Email ]   │
│                                                                  │
│  Last run:    2026-05-08 02:00:14                                │
│  Row counts:  Designation Needed 308 · Single Source 838 · Sole  │
│               Source 31  (1,177 items, 2,727 MPN-expanded rows)  │
│  Output:      Single-Sole Source Report (05-08-2026).xlsx        │
│  SharePoint:  Reports/Single_Sole_Source_Report/  ↗               │
│  Email sent:  jimmy.sessumes@sandisk.com (cc vikas.singh3@…)     │
│  Next scheduled run: 2026-06-01 02:00 PT                         │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

`Run Now` POSTs to `/api/single-sole-source/run`, which: (1) executes the SQL, (2) renders `.xlsx` from the template, (3) writes to `app.singlesole.output.dir`, (4) optionally uploads to SharePoint + sends email per checkboxes (default off for ad-hoc runs).

`Download Latest .xlsx` GETs the most recent file from the output dir.

## 6. Configuration

Appended to `application.properties` (with prod overrides in `application-prod.properties`):

```properties
# Single/Sole Source report
app.singlesole.output.dir=./data/singlesole-reports
app.singlesole.template=classpath:/templates/Single-Sole Source Report Template.xlsx
app.singlesole.schedule.cron=0 0 2 1 * *          # 2am on the 1st of every month
app.singlesole.email.from=PLM-Toolkit@sandisk.com
app.singlesole.email.to=jimmy.sessumes@sandisk.com
app.singlesole.email.cc=vikas.singh3@sandisk.com
app.singlesole.email.subject=Single-Sole Source Report
app.singlesole.sharepoint.enabled=true
app.singlesole.sharepoint.drive.id=b!s3afVAuLU0y-0ik5IkJjOu4fu-sgxu5LgB52XB0ag8-2qc69aZkMSKr6p8JdQyu2
app.singlesole.sharepoint.folder=Reports/Single_Sole_Source_Report
app.singlesole.graph.tenant.id=…  graph.client.id=…  graph.client.secret=…
app.singlesole.graph.username=svc-agile-connect@sandisk.com
app.singlesole.graph.password=…  graph.scope=…  graph.authority=…
```

Secrets (graph.client.secret, graph.password) live only in `application-prod.properties` on the server. **Local dev** sets `app.scheduling.disabled=true` (already the convention) so the monthly job does not fire.

## 7. Scheduling

A single `@Scheduled(cron = "${app.singlesole.schedule.cron}")` method on `SingleSoleSourceScheduler` calls the service. Honors the existing `app.scheduling.disabled` flag.

The job:
1. Builds output filename `Single-Sole Source Report (MM-dd-yyyy).xlsx` (matches the current Windows batch convention).
2. Runs the SQL, renders the workbook from the template.
3. Saves to `app.singlesole.output.dir`.
4. If `app.singlesole.sharepoint.enabled=true`, uploads to SharePoint via Graph (ROPC, copied from `SSReport.graphGetAccessTokenROPC` / `httpPutFile`).
5. Sends the email via the existing `EmailService` with the file as attachment (HTML body following `CLAUDE.md` Email Design Guidelines — KPI tile per tab, link to SharePoint, link to toolkit tab).
6. Records a run-history entry (next section).

## 8. Run History

Persisted as JSON at `./data/singlesole-runs.json` — one append per run. Schema:

```json
{
  "runId": "2026-05-08T02:00:14Z",
  "trigger": "schedule" | "ui",
  "userId": "8252",
  "rowCounts": { "designationNeeded": 308, "singleSource": 838, "soleSource": 31 },
  "xlsxPath": "./data/singlesole-reports/Single-Sole Source Report (05-08-2026).xlsx",
  "xlsxSizeBytes": 287340,
  "sharepoint": { "uploaded": true, "url": "https://…", "error": null },
  "email": { "sent": true, "to": "jimmy.sessumes@sandisk.com", "cc": "vikas.singh3@…", "error": null },
  "durationMs": 12480
}
```

Last 60 entries kept; older rolled off. Powers the "Last run" banner on the tab.

## 9. Error Handling

- **DB query fails** → bubble up; UI shows error; scheduled job emails `vikas.jindal@sandisk.com` per the long-running-work convention in `CLAUDE.md`.
- **SharePoint upload fails** → workbook is still written locally and emailed; SharePoint failure is logged into the run-history `sharepoint.error` and surfaced on the tab. The job is **not** considered failed in that case.
- **Email send fails** → run history records it; still considered partially successful (file is on SharePoint).

## 10. Local-Dev Notes

- Toolkit's local instance already connects to `agprod`, so the SQL works end-to-end on `localhost:8090`.
- `app.scheduling.disabled=true` keeps the monthly cron quiet locally.
- The "Run Now" button still works locally (manual trigger doesn't go through `@Scheduled`).
- SharePoint and email default to **off** for `Run Now` — only the scheduled monthly job uploads/emails by default. That keeps local testing safe.

## 11. Migration / Decommission

After the toolkit feature ships and the first monthly run on the toolkit-side completes successfully:

1. Disable the Windows scheduled task on the batch box (`Task Scheduler → SingleSourceReport`).
2. Move `F:\Batch\Shruthi\SingleSourceReport\` to `F:\Batch\_decommissioned\`.
3. Update `Reports/Single_Sole_Source_Report/` SharePoint folder description to note the new source.

No code in the existing toolkit modules is touched as part of decommission.

## 12. Open Questions

None at design time. Q1/Q2/Q3 resolved per session — `Designation Needed` filter is S/S only (no MPN Count constraint), MPN expansion is one-row-per-MPN for Single/Sole tabs only, toolkit takes over scheduling and notifications.
