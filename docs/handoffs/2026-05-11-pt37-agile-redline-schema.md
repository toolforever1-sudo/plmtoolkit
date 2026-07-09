# PT-37 handoff — find the Agile pending-redline removal table

**For:** an agent with MCP DB access to the Agile Oracle DB (jdbc:oracle:thin:@uls-dp-oraagile.wdc.com:1521:agprod, schema `AGILE`, user `custom_user`).
**Asker:** Vikas Jindal (`Vikas.Jindal@sandisk.com`)
**Owner of the resulting fix:** Claude (`plm-field-tracker` repo at `~/git/plm-field-tracker/`)

## TL;DR — what we need from you

We need to identify the Agile table that stores **pending BOM-row removals** (redline-removes) for unreleased ECOs. The toolkit's BOM-Compare query reads the `bom` table directly and applies `CHANGE_IN`/`CHANGE_OUT` filters, but Agile only sets `b.CHANGE_OUT` when an ECO is actually **released** — not while the ECO is pending. So during the pending window, the row's removal is staged in a different table we haven't identified.

Please run the discovery queries in §3 and reply with: **table name + columns + a sample row for ECO-135471-A**.

## 1. The reproducible test case

| Field | Value |
|---|---|
| Parent BOM | `SDFPNVL-1T00-1006` |
| Released rev (control) | rev `3`, change `ECO-132883-A`, released `2026-03-06 02:27:30` |
| Pending rev (failing) | rev `(4)`, change `ECO-135471-A`, **no release date yet** |
| Row that should be REMOVED in rev (4) | `F006-S715077000T0` at seq 55, qty 0, status ACT, type D |
| Row that should be ADDED in rev (4) | `F006-S7150RMC87ALZ` at seq 55, qty 0, status MKT, type D |
| Agile's behavior (correct) | Redline view shows `F006-S715077000T0` struck through (removed) and `F006-S7150RMC87ALZ` red italic (added). |
| Toolkit's current behavior (bug) | Both sides return `F006-S715077000T0` → diff sees it as `MATCH` → renders on both columns. Only `F006-S7150RMC87ALZ` is correctly flagged ADDED. |

## 2. What we already know from the `bom` table

From a JDBC query against the live DB (we extended the rev-compare SQL with diagnostic CHANGE_IN/CHANGE_OUT fields — see `RevCompareService.java` lines ~165-200 for the existing CTE):

**Row `F006-S715077000T0`** (the soon-to-be-removed one):
- `bom.ID` = `579638427` (same row returned for both rev 3 query AND rev (4) query)
- `b.CHANGE_IN` = change_id of `ECO-132131-A` (released `2026-03-02 08:27:29`)
- **`b.CHANGE_OUT` = 0** — i.e. no removal marker yet, even though Agile shows it redlined-out by ECO-135471-A

**Row `F006-S7150RMC87ALZ`** (the new addition):
- `bom.ID` = `579933305` (only returned for rev (4))
- `b.CHANGE_IN` = change_id of `ECO-135471-A` (pending, release_date IS NULL)
- `b.CHANGE_OUT` = 0

So Agile **does** stage the "add" half of the redline directly on the `bom` table while the ECO is pending — by inserting a new row with `CHANGE_IN = <pending ECO id>`. But it does **not** stage the "remove" half by setting `CHANGE_OUT` on the doomed row. The remove must live somewhere else.

## 3. Discovery queries to run

These are intentionally read-only and bounded. Run them via your MCP DB access and paste back the output.

### 3a. Find candidate tables

```sql
SELECT owner, table_name, num_rows
FROM all_tables
WHERE owner IN ('AGILE','SYSTEM')   -- both, in case the bom-modify table is system-side
  AND (table_name LIKE '%REDLINE%'
       OR table_name LIKE '%MODIFY%'
       OR table_name LIKE '%CHANGE_ITEM%'
       OR table_name LIKE '%CHANGE_BOM%'
       OR table_name LIKE '%CHANGE_LINE%'
       OR table_name LIKE '%PEND%'
       OR table_name LIKE '%REMOVE%'
       OR table_name LIKE '%DELETE%')
ORDER BY owner, table_name;
```

### 3b. Inspect columns of likely candidates

For each candidate name from 3a, dump columns:

```sql
SELECT column_name, data_type, nullable
FROM all_tab_columns
WHERE owner = '<owner>' AND table_name = '<candidate>'
ORDER BY column_id;
```

We're looking for a table with at least: `CHANGE` (or `CHANGE_ID`), `BOM` (or `BOM_ID`), and some kind of action/operation indicator (REMOVE / DELETE / NET_CHANGE).

### 3c. Probe for the test row

Once you have a candidate, probe it directly for our test case:

```sql
-- Replace <CHANGE_BOM_TABLE> with the candidate. ECO-135471-A's change.ID is 579918495.
-- F006-S715077000T0's bom.ID is 579638427.
SELECT t.*
FROM <CHANGE_BOM_TABLE> t
WHERE t.CHANGE = 579918495        -- or t.CHANGE_ID, depending on column
   OR t.BOM    = 579638427;        -- or t.BOM_ID
```

Expected: at least one row joining `change_id=579918495` to `bom_id=579638427`, with a column value that indicates "remove" / "delete" / "net out".

### 3d. Confirm the relationship is general (not test-only)

Pick any released remove from history:

```sql
-- Find a recently-released ECO that removed a bom row
SELECT b.ID AS bom_row_id, b.CHANGE_OUT, c.CHANGE_NUMBER, c.RELEASE_DATE
FROM bom b
JOIN change c ON c.ID = b.CHANGE_OUT
WHERE c.RELEASE_DATE >= SYSDATE - 7
  AND ROWNUM <= 5;
```

Then check whether those rows ALSO have a record in the candidate table (they should, if the table is the staging area that persists post-release).

## 4. Reply format we'd love back

```text
Table:   AGILE.<NAME>
Columns: <list of columns relevant to BOM redline-remove>
Sample row for our test case:
  CHANGE = 579918495 (ECO-135471-A)
  BOM    = 579638427 (F006-S715077000T0)
  <action_column> = <REMOVE / NET_OUT / DELETE / etc.>
  <other relevant column values>
```

If you can also confirm whether the table holds **only pending** redlines or both pending and released, that's useful — it tells us whether we need to filter by `release_date` when joining.

## 5. What I'll do once I have the table name

Update `RevCompareService.java`'s `scoped_bom` CTE branch (3) — the pending-rev branch around lines 158-163 — to add a `NOT EXISTS` against the new table:

```sql
AND NOT EXISTS (
    SELECT 1 FROM <REDLINE_REMOVE_TABLE> rrm
    WHERE rrm.CHANGE = pr.change_id        -- the pending ECO
      AND rrm.BOM    = b.ID                -- this row's bom.ID
      AND rrm.<action_col> = '<REMOVE>'    -- if the table mixes add/remove actions
)
```

Then re-run the local test (SDFPNVL-1T00-1006 rev 3 vs (4)/ECO-135471-A). Expected: `F006-S715077000T0` no longer comes back for rev (4) → diff flags it as REMOVED → renders only on the rev 3 side with strikethrough.

I'll also remove the diagnostic banner once verified, and the `_diag*` fields from the response (they were a stepping stone, not meant to live in the public API).

## 6. Current state on prod

- The most-recent staged JAR carries: a yellow banner on the BOM-Compare page for pending revs warning users about this gap; the `_diag*` fields visible in the rev-compare JSON response.
- The bug is **not** masking data — both rows are visible in the toolkit's output. They're just on the wrong sides of the comparison.

## 7. Repo coordinates

- `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/RevCompareService.java` — the SQL lives here, around lines 105-220.
- `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/controller/RevCompareController.java` — the diff logic at lines 94-145.
- DB connection comes from `customDataSource` (`config/application.properties`, `custom.datasource.*` keys).
