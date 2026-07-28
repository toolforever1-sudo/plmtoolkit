# All-SKUs Data Service — Handoff Document

> **Context**: This document describes new files added to `plm-field-tracker` that implement a seed-once + daily-delta JSON cache of all SKU items from the Agile PLM Oracle database. Three new Java files and one config update were created by a prior Claude session and need integration into the existing Spring Boot app.

---

## 1. What Was Built

### New Files (already in the repo)

| File | Path | Purpose |
|------|------|---------|
| `SkuRecord.java` | `src/main/java/com/sandisk/plm/tracker/model/` | POJO with all 39 SKU fields, Jackson `@JsonProperty` annotations |
| `SkuDataService.java` | `src/main/java/com/sandisk/plm/tracker/service/` | Core service: seed, delta, status. Pre-loads lookup maps, runs flat SQL, resolves in Java |
| `SkuDataController.java` | `src/main/java/com/sandisk/plm/tracker/controller/` | REST: `POST /api/sku-data/seed`, `POST /api/sku-data/delta`, `GET /api/sku-data/status` |

### Modified Files

| File | Change |
|------|--------|
| `src/main/resources/application.properties` | Added 3 lines at bottom: `sku.data.file`, `sku.data.delta.days`, `sku.data.query.timeout` |

### Output File (created at runtime)

`./data/all-skus.json` — JSON with this structure:
```json
{
  "generatedAt": "2026-04-15 14:30:00",
  "lastDeltaAt": "2026-04-16 06:00:00",
  "totalRecords": 47993,
  "records": {
    "SDDDC3-2T00-GM46GY": {
      "number": "SDDDC3-2T00-GM46GY",
      "description": "...",
      "lifecyclePhase": "ACT",
      "rev": "1",
      "productLine": "1050 - Dual Drive",
      "category": "G-RETAIL",
      ...all 39 fields...
    },
    "NEXT-ITEM-NUMBER": { ... }
  }
}
```

The `records` map is keyed by `ITEM_NUMBER` so delta upserts are O(1) — existing items get overwritten, new ones get appended.

---

## 2. Agile PLM Database Schema (agile_prod)

This section documents **everything discovered** about the Oracle Agile PLM 9.3.x schema, so you don't have to reverse-engineer it again.

### Core Tables and Joins

The canonical join pattern comes from the `ITEM_P2P3_QUERY` view:

```sql
FROM agile.ITEM i
  JOIN agile.REV r      ON r.ITEM = i.ID AND i.DEFAULT_CHANGE = r.CHANGE AND r.SITE = 0
  JOIN agile.PAGE_TWO p2  ON p2.ID = i.ID AND p2.CLASS = i.CLASS
  JOIN agile.PAGE_THREE p3 ON p3.ID = i.ID AND p3.CLASS = i.CLASS
```

**Critical**: REV is joined via `DEFAULT_CHANGE = r.CHANGE`, NOT via `LATEST_FLAG`. The `DEFAULT_CHANGE` column on ITEM points to the current effective change (ECO).

### Key Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| SKU Subclass ID | `251736330` | `ITEM.SUBCLASS` for SKU items |
| Items Class ID | `10000` | `ITEM.CLASS` for all Items (parent class of SKU) |
| F-Family Category | `200001` | `ITEM.CATEGORY` ENTRYID for "F - Family" (excluded from results) |
| Lifecycle Phase Parent | `1514` | `NODETABLE.PARENTID` for all lifecycle phase nodes |

### Where Lifecycle Phase Lives

**This was the hardest thing to find.** Lifecycle Phase is stored in `REV.RELEASE_TYPE` as a NODETABLE ID (NOT a LISTENTRY ID).

```sql
-- Resolve lifecycle phase:
SELECT n.DESCRIPTION FROM agile.NODETABLE n WHERE n.ID = r.RELEASE_TYPE
-- All phases are under NODETABLE.PARENTID = 1514
```

The 11 active lifecycle phases for SKUs:
```
OBS-SKU (35,348)  ACT (3,519)  EOL (3,024)  DEV (1,941)  C-ACT (1,264)
DEAD (1,126)  FA (920)  PPROD (449)  QUAL (230)  OBS (120)  MKT (52)
```

**What doesn't work**: There is an `ITEM_LCP` table with `(ITEM_NUMBER, LIFECYCLE_PHASE)` but it's incomplete — only covers ~70K of 74K SKU items. Do NOT use it.

### Three Storage Mechanisms for Attributes

#### 1. Standard Flex Fields (PAGE_TWO / PAGE_THREE)

PAGE_TWO = "Inv/Planning" tab. PAGE_THREE = "PDM Attributes" tab.
Columns: `LIST01-LIST30`, `TEXT01-TEXT30`, `DATE01-DATE20`, `MULTILIST01-MULTILIST15`, etc.

**Important**: For SKU subclass, PAGE_THREE LIST columns have a +30 offset (e.g., what the UI calls "LIST01" for SKUs is actually `PAGE_THREE.LIST31` in the database).

#### 2. Extended Attributes (AGILE_FLEX)

Entity-Attribute-Value pattern: `(ID, ATTID, CLASS, NUMBER1, TEXT1, ...)`
- `ID` = ITEM.ID
- `ATTID` = attribute node ID from NODETABLE
- `CLASS` = 10000 (Items class)
- `NUMBER1` = stores a LISTENTRY ENTRYID for list-type attributes

#### 3. System Attributes (ITEM / REV tables)

Direct columns like `ITEM.ITEM_NUMBER`, `ITEM.DESCRIPTION`, `ITEM.CATEGORY`, `REV.REV_NUMBER`, `REV.RELEASE_DATE`, `REV.RELEASE_TYPE`.

### List Value Resolution

**Single-value lists**: `LISTENTRY.ENTRYID` → `LISTENTRY.ENTRYVALUE` where `LANGID = 0`.

**Ambiguity warning**: The same `ENTRYID` can appear in multiple lists with different `PARENTID` values and different display text. The Java code handles this with `MIN(ENTRYVALUE)` grouping, which matches the original SQL behavior.

**Multi-value lists**: Stored as comma-delimited ENTRYID strings like `",12345,67890,"`. Parse the commas, look up each ID.

### Complete Column Mapping (all 39 fields)

#### Title Block (ITEM + REV)

| UI Field | Source | Column/Mechanism |
|----------|--------|-----------------|
| Number | ITEM | `ITEM_NUMBER` |
| Description | ITEM | `DESCRIPTION` |
| Lifecycle Phase | REV → NODETABLE | `REV.RELEASE_TYPE` → `NODETABLE.DESCRIPTION` |
| Rev | REV | `REV_NUMBER` |
| Product Line | ITEM | `PRODUCT_LINES` (multi-value → LISTENTRY) |
| P/M | ITEM → LISTENTRY | `CATEGORY` → LISTENTRY |
| Rev Release Date | REV | `RELEASE_DATE` |

#### Inv/Planning (PAGE_TWO)

| UI Field | Column | Type |
|----------|--------|------|
| As Build Capacity (Binary) | `LIST22` | Single list → LISTENTRY |
| As Marketed Capacity (Decimal) | `TEXT26` | Direct text |
| As Marketed Capacity UOM | `LIST29` | Single list → LISTENTRY |
| Family Name | `LIST24` | Single list → LISTENTRY |
| Subcontractors | `MULTILIST01` | Multi-value → LISTENTRY |
| Actual Build Plant | `MULTILIST03` | Multi-value → LISTENTRY |
| Material Type | `LIST11` | Single list → LISTENTRY |
| Group | `LIST19` | Single list → LISTENTRY |
| Material Group | `LIST20` | Single list → LISTENTRY |
| Prod Division | `LIST14` | Single list → LISTENTRY |
| Create Date | `DATE04` | Direct timestamp |

#### PDM Attributes (PAGE_THREE)

| UI Field | Column | Type |
|----------|--------|------|
| Product Line/Program Name | `MULTILIST33` | Multi-value → LISTENTRY |
| Category | `MULTILIST32` | Multi-value → LISTENTRY |
| Product Customer | `LIST32` | Single list → LISTENTRY |
| BOM Type | `LIST36` | Single list → LISTENTRY |
| Warranty (Months) | `LIST46` | Single list → LISTENTRY |
| PROG Program Name | `LIST53` | Single list → LISTENTRY |
| Program Short Name | `LIST31` | Single list → LISTENTRY |
| External Marketing Name | `LIST52` | Single list → LISTENTRY |

#### Extended Attributes (AGILE_FLEX)

All use `CLASS = 10000`. The `NUMBER1` column holds a LISTENTRY ENTRYID.

| UI Field | ATTID | 
|----------|-------|
| As Sold Capacity (Binary) | `251735792` |
| Business Segment/Sub-Segment | `251751610` |
| Product Segment | `251747312` |
| Product Sub-Segment | `251753716` |
| Interface Type | `251747308` |
| Form Factor Type | `251747310` |
| Product Brand | `251747314` |
| Brand | `251752458` |
| Partnership | `251752463` |
| Product Swimlane | `251752461` |
| Stamp Serialization Flag | `251752319` |
| Retailer | `251753719` |
| Item Class | `251747317` |

---

## 3. Performance Architecture

### Why the SQL Approach Was Slow (2–10 min)

The original validated SQL uses ~30 correlated scalar subqueries per row:
- 15 single-value LISTENTRY lookups (`SELECT MIN(le.ENTRYVALUE) ...`)
- 5 LISTAGG with INSTR for multi-value fields (can't use indexes)
- 12 AGILE_FLEX nested subqueries
- 1 NODETABLE lookup

For 48K rows × 30 subqueries = ~1.4M subquery executions.

### How the Java Service Solves This (15–30 sec)

The service runs **4 flat queries** and resolves in memory:

1. **LISTENTRY map** (1 query): `SELECT ENTRYID, MIN(ENTRYVALUE) FROM agile.LISTENTRY WHERE LANGID=0 GROUP BY ENTRYID` → ~100K entries in a HashMap. Under 1 second.

2. **NODETABLE lifecycle phases** (1 query): 26 rows from `NODETABLE WHERE PARENTID = 1514`. Instant.

3. **Base 4-table JOIN** (1 query): Flat SELECT returning raw IDs — no subqueries. ~48K rows in ~5 seconds.

4. **AGILE_FLEX batch** (1 query): All 13 ATTIDs for all SKU items in one pass. Builds `Map<itemId, Map<attId, number1>>`. ~10 seconds.

5. **Java resolution**: Iterate rows, look up each ID in the HashMap. Under 1 second.

### Delta Strategy

For incremental updates, add `AND r.RELEASE_DATE > SYSDATE - 2` to both the base query and the flex query. Returns ~50–200 rows in under 2 seconds.

**What delta catches**: New SKU releases, lifecycle phase transitions (these create new REV records with fresh RELEASE_DATE through ECOs).

**What delta might miss**: Attribute-only changes that don't go through an ECO (rare for SKUs). Hedge with a weekly full re-seed.

---

## 4. Integration Checklist

The new files are already placed in the correct packages and follow existing project conventions (java.util.logging, @Service/@RestController, DataSource injection, ReentrantLock pattern matching ChangeQueryService). Here's what may need attention:

### 4.1 Verify Spring Component Scan

The new classes are in the same package hierarchy (`com.sandisk.plm.tracker.*`) as existing code, so `@ComponentScan` should pick them up automatically. Verify `Application.java` doesn't restrict scanning.

### 4.2 DataSource Injection

`SkuDataService` uses `@Autowired DataSource` (the primary datasource), same as `ChangeQueryService`. This connects to `agile_prod` with the `agile` user. No additional datasource config needed.

### 4.3 Authentication

The endpoints are under `/api/sku-data/*`. Check if `AuthFilter.java` restricts `/api/*` paths. If so, these new endpoints need to be either included in the filter or whitelisted depending on requirements.

### 4.4 Scheduled Delta

To wire up the daily automatic delta, you could either:

**Option A**: Add a `@Scheduled` method in `SkuDataService`:
```java
@Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
public void scheduledDelta() {
    delta();
}
```
(Requires `@EnableScheduling` on `Application.java`)

**Option B**: Use the existing `ScheduledReportService` pattern to let users configure the schedule via the UI.

### 4.5 Initial Seed

After deployment, call `POST /api/sku-data/seed` once to generate the initial JSON. This will take ~15–30 seconds. Subsequent calls to `/api/sku-data/delta` will be fast (~1–2 sec).

### 4.6 Config Properties Added

These were appended to `application.properties`:
```properties
sku.data.file=./data/all-skus.json     # Where the JSON is written
sku.data.delta.days=2                   # Delta lookback window
sku.data.query.timeout=120              # SQL query timeout in seconds
```

---

## 5. Validated SQL Query (Reference)

A standalone `.sql` file with the original scalar-subquery version (useful for ad-hoc querying or validation) is at:

**`/Users/vikasjindal/Downloads/All_SKUs_Query.sql`**

This query was validated against the Agile PLM UI export (Excel screenshot) — every field matched for items including SDDDC3-2T00-GM46GY, SDGDC-2T00B8HMED, SDSDQAS5 series, SDSDXDM series, and others.

Current row count: **47,993** (as of 2026-04-15).

---

## 6. Errors and Gotchas Discovered

These are documented so you don't hit them again:

1. **LISTENTRY ambiguity**: Same `ENTRYID` can map to different `ENTRYVALUE` in different lists (different `PARENTID`). Solution: `MIN(ENTRYVALUE)` grouping. This matches what the Agile UI shows.

2. **DELETE_FLAG is usually NULL**: Most items have `DELETE_FLAG = NULL`, not 0. Filter with `NVL(DELETE_FLAG, 0) != 1`.

3. **REV.LIFECYCLE_PHASE doesn't exist**: The REV table has no such column. Lifecycle phase is `REV.RELEASE_TYPE` resolved through `NODETABLE`.

4. **ITEM_LCP table is incomplete**: Covers ~70K of 74K SKUs. Do not use as the primary lifecycle phase source.

5. **PAGE_THREE LIST offset**: SKU subclass LIST columns are offset by +30 in PAGE_THREE (e.g., "List01" in UI = `LIST31` in DB).

6. **AGILE_FLEX for list attributes**: The `NUMBER1` column stores the LISTENTRY ENTRYID, not the display text. Must resolve through LISTENTRY.

7. **LEFT JOIN to LISTENTRY causes cartesian products**: Never LEFT JOIN LISTENTRY directly — use scalar subqueries or in-memory map resolution instead.

---

## 7. AGILE.BOM Table — Schema and Stats

The `AGILE.BOM` table is the parent → component mapping for every assembly. One row = one component-line on a parent's BOM. Confirmed against `agile_prod` on 2026-05-05.

### Key Columns

| Column | Type | Meaning |
|--------|------|---------|
| `ID` | NUMBER (PK) | Row primary key |
| `ITEM` | NUMBER | **Parent assembly's `ITEM.ID`** (FK to ITEM) |
| `ITEM_NUMBER` | VARCHAR2(300) | Component item number (string, NOT a FK) |
| `COMPONENT` | NUMBER | Component's `ITEM.ID` (FK to ITEM) |
| `FIND_NUMBER` | VARCHAR2(32) | Find number on the BOM |
| `SEQ` | NUMBER | Sequence/order |
| `QUANTITY` | VARCHAR2(40) | Qty per — **string, not numeric** (can be "1.5", expressions, etc.) |
| `CHANGE_IN` | NUMBER | `CHANGE.ID` that introduced this row. `0` = pre-existing at item creation |
| `CHANGE_OUT` | NUMBER | `CHANGE.ID` that retired this row. **`0` = currently active** |
| `PRIOR_BOM` | NUMBER | Link to predecessor BOM row (redline lineage) |
| `SITE` | NUMBER | Site ID for multi-site BOMs (default site = 0) |
| `LIST01-15`, `TEXT01-15`, `NUMERIC01-15`, `DATE01-15`, `MULTILIST01-10` | flex | Attribute storage following the same pattern as PAGE_TWO/PAGE_THREE |
| `IS_OPTIONAL`, `IS_MUTUALLY_EXCLUSIVE` | NUMBER | Optional/alt-component flags |
| `MINIMUM_NUMBER`, `MAXIMUM_NUMBER` | NUMBER | Min/max qty for optional groups |
| `CREATED`, `LAST_UPD` | DATE | Audit timestamps |

### "Active BOM Line" Filter

The convention used by the `bom_extract_delta.py` extractor and the released-BOM view:

```sql
b.CHANGE_OUT = 0                       -- still on the current BOM
AND (b.CHANGE_IN = 0
     OR EXISTS (SELECT 1 FROM CHANGE c
                 WHERE c.ID = b.CHANGE_IN
                   AND c.RELEASE_DATE IS NOT NULL))   -- introduced by a released change
```

`CHANGE_OUT = 0` alone is sufficient for "currently active". The `CHANGE_IN` filter only matters if you also want "released-only" semantics (e.g., excluding pending-change additions).

### Released-Assembly Filter (Pair with BOM)

To restrict to assemblies that are released parts (not changes/SKUs/etc.):

```sql
i.SUBCLASS IN (SELECT ID FROM NODETABLE WHERE PARENTID = 10004)   -- Parts subclasses
AND EXISTS (SELECT 1 FROM REV r
             WHERE r.ITEM = i.ID
               AND r.REV_NUMBER  IS NOT NULL
               AND r.RELEASE_DATE IS NOT NULL)
```

### BOM Size Distribution (agile_prod, 2026-05-05)

622,304 assemblies have at least one active BOM line.

| Stat | Components |
|------|-----------|
| Min | 1 |
| Avg | 11 (10.99) |
| p50 (median) | 8 |
| p75 | 15 |
| p90 | 23 |
| p95 | 31 |
| p99 | 66 |
| Max | **452** |

Buckets:

| Range | # BOMs | % |
|-------|--------|---|
| ≤ 10 components | 366,787 | 58.9% |
| 11 – 25 | 207,302 | 33.3% |
| 26 – 50 | 36,640 | 5.9% |
| 51 – 100 | 9,750 | 1.6% |
| > 100 | 1,825 | 0.3% |

**Top 10 largest BOMs** (by active line count):

| Assembly | Components |
|----------|-----------|
| 10-51-01887-CIS-I | 452 |
| RG-001643 | 398 |
| MR-002373 | 382 |
| SDGDC-512GB53IEC | 366 |
| SDGDC-512GB53DEC | 354 |
| 10-51-01887-CIS-J | 345 |
| SDSCR-512GB53DEC | 305 |
| 10-51-01887-CIS-C | 297 |
| SDSCR-512GB53IEC | 283 |
| A139-000546-02 | 282 |

### Affected Items per Change Distribution (agile_prod, 2026-05-05)

Computed from `REV` grouped by `CHANGE` (excluding `CHANGE = 0`, the no-change initial-creation row). 704,088 changes total.

| Stat | Affected Items |
|------|---------------|
| Min | 1 |
| Avg | 9 (8.65) |
| p50 (median) | 3 |
| p75 | 8 |
| p90 | 24 |
| p95 | 39 |
| p99 | 57 |
| Max | **17,427** (bulk migration AMR-01250000) |

Buckets:

| Range | # Changes | % |
|-------|-----------|---|
| 1 affected item | 225,172 | 32.0% |
| 2 – 10 | 332,474 | 47.2% |
| 11 – 25 | 82,769 | 11.8% |
| 26 – 50 | 51,912 | 7.4% |
| 51 – 100 | 9,966 | 1.4% |
| > 100 | 1,795 | 0.3% |

The very high outliers (>5,000 affected items) are bulk migration / mass-data-load changes (AMR, MCO-PDMROLLDOWN, LEGACY_001) — not representative of typical business changes. For day-to-day ECO/MCO traffic, **p99 ≈ 57**, which aligns with the empirical "50, sometimes 100" cap.

### Useful Queries

```sql
-- Components on a specific assembly's current BOM
SELECT b.ITEM_NUMBER  AS component,
       b.FIND_NUMBER, b.QUANTITY, b.SEQ
  FROM AGILE.BOM b
  JOIN AGILE.ITEM i ON i.ID = b.ITEM
 WHERE i.ITEM_NUMBER = :assembly
   AND b.CHANGE_OUT  = 0
 ORDER BY b.SEQ;

-- Largest BOMs
SELECT i.ITEM_NUMBER, COUNT(*) AS components
  FROM AGILE.BOM b
  JOIN AGILE.ITEM i ON i.ID = b.ITEM
 WHERE b.CHANGE_OUT = 0
 GROUP BY i.ITEM_NUMBER
 ORDER BY components DESC
 FETCH FIRST 10 ROWS ONLY;

-- Affected items count for a change
SELECT COUNT(DISTINCT r.ITEM) AS affected_items
  FROM AGILE.REV r
  JOIN AGILE.CHANGE c ON c.ID = r.CHANGE
 WHERE c.CHANGE_NUMBER = :change_number;
```

### Gotchas

1. **`QUANTITY` is VARCHAR2, not NUMBER.** Don't `SUM(QUANTITY)` directly — cast or parse first.
2. **`ITEM_NUMBER` is denormalized.** It's the component number as a string; the FK to the actual component item is `COMPONENT` (→ `ITEM.ID`). If a component item is renamed in PLM, only the `ITEM_NUMBER` string on its own item row updates; BOM rows referencing it via `ITEM_NUMBER` may still hold the old string. Prefer joining on `COMPONENT` for accurate component-side lookups.
3. **`SITE` defaults to 0** for single-site setups. Most SanDisk BOM rows have `SITE = 0`; if you ever see non-zero values, it's multi-site data and you may double-count without filtering.
4. **`CHANGE_OUT != 0` rows are not deleted, just retired.** They're history. The same parent + component pair can have many BOM rows over time (CHANGE_IN/CHANGE_OUT chain via PRIOR_BOM).
5. **Counting "components" vs "BOM lines"**: Each row is a line, not a distinct component. The same component can appear at multiple find numbers. If you want "distinct components on a BOM", use `COUNT(DISTINCT b.COMPONENT)` instead of `COUNT(*)`.
