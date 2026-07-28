# OwnerChangeAuditService — Tuned SQL (verified on agprod)

All findings below were measured live on **agprod** (user `AGILE`) on 2026-06-14, not assumed.

## Root cause (what actually makes it hang)

The job's cost is dominated by `DBMS_LOB.SUBSTR(DETAILS,...)` — a CLOB read. The number
of CLOB reads is what blows past the timeout, and **both** the old shape *and* the shipped
"new shape" let the optimizer evaluate that CLOB filter on the **entire 365-day history
slice**, before restricting to IMS items.

Measured row counts:

| Set | Rows |
|---|---|
| `ITEM_HISTORY`, all rows | 46,430,267 |
| `ITEM_HISTORY`, last 365 days (all subclasses) | **9,092,047** |
| IMS items (`SUBCLASS=9141`, not deleted) | 16,289 |
| `ITEM_HISTORY` for IMS items, all time | 2,120,090 |
| `ITEM_HISTORY` for IMS items, last 365 days | **97,554** |

So the correct working set is **97,554 rows**, but the current plan touches the CLOB on up
to **9.09M** — ~93× too many. That is the timeout.

I proved the pushdown on prod. With the shipped inline-view shape, `EXPLAIN`/cursor plan
shows the CLOB `LIKE` applied at the `ITEM_HISTORY` table-access step *below* the join:

```
HASH JOIN  H.ITEM = I.ID
  TABLE ACCESS FULL              ITEM            (SUBCLASS=9141 ...)
  TABLE ACCESS BY ROWID BATCHED  ITEM_HISTORY    filter: DBMS_LOB.SUBSTR(...) LIKE 'Owners up...   <-- CLOB on 9M
    INDEX RANGE SCAN             ITEM_HISTORY_IDX4  (TIMESTAMP >= SYSDATE-365)
```

`NO_MERGE` alone does **not** fix it — Oracle still pushes the `LIKE` predicate into the
view and down onto the history table (it under-costs `DBMS_LOB.SUBSTR`, so it always wants
to filter early).

## Answers to the doc's open questions (§2)

1. **Stats** are fresh: both tables `LAST_ANALYZED 2026-06-13 ~05:09`. `ITEM` num_rows
   1,049,578; `ITEM_HISTORY` 46,430,267. Not a stats-drift problem.
2. **Indexes on `ITEM_HISTORY`:** `ITEM_HISTORY_IDX(ITEM)`, `ITEM_HISTORY_IDX1(LOCAL_DATE)`,
   `ITEM_HISTORY_IDX2(ACTION)`, `ITEM_HISTORY_IDX4(TIMESTAMP)`, PK on `ID`. There is an
   index on `ITEM` and one on `TIMESTAMP`, **but no composite `(ITEM, TIMESTAMP)`** — that
   gap is why the optimizer can't go straight from items to their dated rows.
3. **Index on `ITEM.SUBCLASS`:** **none.** The IMS-items filter is a full scan of `ITEM`
   (35,052 blocks). That's only ~1s, so it's not the bottleneck — but see optional index below.
4. **Plan flip / hint:** the `LEADING(i h) USE_HASH(h)` hint *is* honored for the join, but
   it doesn't matter, because the CLOB is filtered before the join regardless.
5. **CLOB I/O dominates** — confirmed. The join itself is cheap; the SUBSTR count is everything.

Note: `ITEM_HISTORY` has a real `LOCAL_DATE` column **and** a `TIMESTAMP` column (both DATE,
both indexed). The query filters/sorts on `TIMESTAMP` but aliases it `AS LOCAL_DATE` in the
output — i.e. the output column named `LOCAL_DATE` actually carries the `TIMESTAMP` value, not
the table's `LOCAL_DATE`. Preserved below to keep output identical, but worth a look.

---

## Recommended fix #1 — query only, no DDL (drop-in for `pullFromSql`)

Forces the CLOB filter to run **after** the join, on ~97K rows instead of ~9M. The `ROWNUM`
acts as an optimization barrier that blocks predicate pushdown (this is what `NO_MERGE`
failed to do); the hint set keeps a clean hash join with the small IMS set as the build side.

```sql
SELECT ITEM_NUMBER, DESCRIPTION, det AS DETAILS, USER_NAME, REVNUMBER, LOCAL_DATE
FROM (
  SELECT /*+ LEADING(i h) USE_HASH(h) FULL(i) */
         i.ITEM_NUMBER,
         i.DESCRIPTION,
         h.USER_NAME,
         h.REVNUMBER,
         h."TIMESTAMP"                       AS LOCAL_DATE,
         DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) AS det,
         ROWNUM                              AS rn        -- barrier: blocks LIKE pushdown
  FROM   AGILE.ITEM         i
  JOIN   AGILE.ITEM_HISTORY h ON h.ITEM = i.ID
  WHERE  i.SUBCLASS = :subclass                           -- 9141
    AND  NVL(i.DELETE_FLAG, 0) != 1
    AND  h."TIMESTAMP" >= TRUNC(SYSDATE) - :days          -- 365
)
WHERE det LIKE :p1                                        -- 'Owners updated via PLM Toolkit%'
   OR det LIKE :p2                                        -- '%Document Owner%'
ORDER BY ITEM_NUMBER, LOCAL_DATE;
```

Verified-on-prod plan (the important part — CLOB filter is now *above* the join):

```
SORT AGGREGATE / SELECT
  VIEW                          filter: DET LIKE 'Owners updated...' OR DET LIKE '%Document Owner%'   <-- CLOB on ~97K
    COUNT
      HASH JOIN
        TABLE ACCESS FULL                ITEM              (SUBCLASS=9141 ...)        build = 16,289 rows
        TABLE ACCESS BY ROWID BATCHED    ITEM_HISTORY      (no CLOB filter here)
          INDEX RANGE SCAN               ITEM_HISTORY_IDX4 (TIMESTAMP >= SYSDATE-365)
```

`DBMS_LOB.SUBSTR` is now computed once per row, only on post-join IMS rows. Functional
result is identical to the original (validated on a 7-day window: 6 toolkit-marker rows +
70 cell-change rows = 76 matches, same set the old predicate returns).

This is a straight replacement for the SQL string in `pullFromSql(int days)` — same binds
(`:subclass`, `:days`, `:p1`, `:p2`), same columns, same order. No Java/caller changes.

---

## Recommended fix #2 — add one index, then it's trivially fast (best long-term)

The only structural gap is the missing composite index. Adding it lets the plan go directly
from the 16,289 IMS items to their 97,554 dated rows — no 9M scan at all.

```sql
CREATE INDEX AGILE.IX_ITEMHIST_ITEM_TS
    ON AGILE.ITEM_HISTORY (ITEM, "TIMESTAMP") ONLINE;
-- optional, minor: CREATE INDEX AGILE.IX_ITEM_SUBCLASS ON AGILE.ITEM (SUBCLASS) ONLINE;
```

With that index in place, this NL form is optimal:

```sql
SELECT ITEM_NUMBER, DESCRIPTION, det AS DETAILS, USER_NAME, REVNUMBER, LOCAL_DATE
FROM (
  SELECT /*+ LEADING(i h) USE_NL(h) INDEX(h IX_ITEMHIST_ITEM_TS) */
         i.ITEM_NUMBER, i.DESCRIPTION, h.USER_NAME, h.REVNUMBER,
         h."TIMESTAMP"                       AS LOCAL_DATE,
         DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) AS det,
         ROWNUM                              AS rn
  FROM   AGILE.ITEM         i
  JOIN   AGILE.ITEM_HISTORY h ON h.ITEM = i.ID
  WHERE  i.SUBCLASS = :subclass
    AND  NVL(i.DELETE_FLAG, 0) != 1
    AND  h."TIMESTAMP" >= TRUNC(SYSDATE) - :days
)
WHERE det LIKE :p1
   OR det LIKE :p2
ORDER BY ITEM_NUMBER, LOCAL_DATE;
```

Each of the 16,289 items does a range scan on `(ITEM, TIMESTAMP)` returning only its
in-window rows → ~97K rows total, CLOB on those only, no 9M anything. Expected sub-10s.

---

## Verdict on the doc's other ideas

- **`setQueryTimeout` 120→240s:** keep it as a safety margin, but it's not the fix — it just
  lets a 9M-CLOB plan run longer before failing.
- **UNION ALL split (§2):** unnecessary. The `ROWNUM` barrier already gets the CLOB off the
  9M slice; splitting into two passes adds a second scan for no benefit here.
- **Plan B / delta watermark (§3):** still the right long-term architecture (don't re-pull a
  year every hour), but **not needed to clear the timeout** — fix #1 alone does that, and
  fix #2 makes it comfortable. Keep Plan B as the separate ticket it already is.

## Acceptance criteria (§6) mapping

- Completes well under 60s for `days=365` — fix #1 caps CLOB at ~97K reads; fix #2 also caps the join scan. ✓ (expected)
- Plan shows IMS items leading + hash join — confirmed (build = `ITEM` SUBCLASS filter). ✓
- CLOB SUBSTR once per row, above the join — confirmed in cursor plan. ✓
- No `ORA-01013` / log line under 60000ms — to confirm after deploy.
