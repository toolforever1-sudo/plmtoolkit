# OwnerChangeAuditService — Hourly Rebuild Hanging on agprod

**Status:** failing every hour with ORA-01013 (query cancelled) since **21:32 Jun 13 2026**. 12+ consecutive failures observed in the Sun Jun 14 09:00 admin-activity digest. Prior snapshot is being retained each time, so the in-app surface shows stale data but no user-visible error.

**Symptom in toolkit logs:**

```
[OWNER-AUDIT] hourly rebuild failing
ORA-01013: user requested cancel of current operation
  at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(...)
```

That stack trace appearing inline in the activity log every ~30 min is what trips the heuristic. The job is hitting the JDBC `setQueryTimeout(120)` ceiling.

**Audience:** the Claude Code agent with MCP access to the agprod Oracle DB. The toolkit-side fix is already shipped (see "What changed in code" below) — use that as v1, then walk this doc to confirm the plan, look for index opportunities, and decide whether to also wire in the delta strategy.

---

## 1. The query

Source: `src/main/java/com/sandisk/plm/tracker/service/OwnerChangeAuditService.java#pullFromSql(int days)`.

### Old shape (what was timing out)

```sql
SELECT i.ITEM_NUMBER, i.DESCRIPTION,
       DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) AS DETAILS,
       h.USER_NAME, h.REVNUMBER, h."TIMESTAMP" AS LOCAL_DATE
FROM   AGILE.ITEM_HISTORY h
JOIN   AGILE.ITEM         i ON h.ITEM = i.ID
WHERE  i.SUBCLASS = :subclass               -- 9141 (IMS Documents)
  AND  NVL(i.DELETE_FLAG, 0) != 1
  AND  h."TIMESTAMP" >= TRUNC(SYSDATE) - :days       -- :days = 365
  AND  ( DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) LIKE :p1   -- '%Document Owner%'
         OR DBMS_LOB.SUBSTR(h.DETAILS, 4000, 1) LIKE :p2 )  -- 'Owners updated via PLM Toolkit%'
ORDER BY i.ITEM_NUMBER, h."TIMESTAMP";
```

### New shape (shipped 2026-06-14)

```sql
WITH ims_items AS (
  SELECT i.ID, i.ITEM_NUMBER, i.DESCRIPTION
    FROM AGILE.ITEM i
   WHERE i.SUBCLASS = :subclass                 -- 9141
     AND NVL(i.DELETE_FLAG, 0) != 1
)
SELECT /*+ LEADING(i h) USE_HASH(h) */
       i.ITEM_NUMBER, i.DESCRIPTION,
       h.det AS DETAILS,
       h.USER_NAME, h.REVNUMBER, h.LOCAL_DATE
FROM ims_items i
JOIN (
  SELECT hh.ITEM, hh.USER_NAME, hh.REVNUMBER,
         hh."TIMESTAMP" AS LOCAL_DATE,
         DBMS_LOB.SUBSTR(hh.DETAILS, 4000, 1) AS det
    FROM AGILE.ITEM_HISTORY hh
   WHERE hh."TIMESTAMP" >= TRUNC(SYSDATE) - :days        -- 365
) h ON h.ITEM = i.ID
WHERE h.det LIKE :p1     -- 'Owners updated via PLM Toolkit%' (leading-wildcard-free, more selective)
   OR h.det LIKE :p2     -- '%Document Owner%'                (generic fallback)
ORDER BY i.ITEM_NUMBER, h.LOCAL_DATE;
```

`setQueryTimeout` raised from 120s → 240s as belt-and-braces.

### Constants

| Bind | Source | Value |
|---|---|---|
| `:subclass` | `IMS_SUBCLASS_ID` | `9141` |
| `:days` | `CACHE_DAYS` for the scheduler | `365` (live calls may use `defaultDays=90`) |
| `:p1` | `TOOLKIT_MARKER_PREFIX + '%'` | `'Owners updated via PLM Toolkit%'` |
| `:p2` | `OWNER_CELL_DETAILS_LIKE` | `'%Document Owner%'` |

The two LIKE patterns match different concepts:
- `:p1` — toolkit-written marker row (DETAILS starts with a literal sentinel)
- `:p2` — Agile-side cell modify row (DETAILS contains "Modified ...Document Owner(s) ...")

Both are needed to surface a full owner-change event — the toolkit pairs them in Java post-processing.

---

## 2. What we already know vs. what we want you to verify

### Known properties

- `AGILE.ITEM` is on the order of single-digit millions of rows.
- IMS-subclass items (`SUBCLASS = 9141`) is a few thousand — **highly selective**.
- `AGILE.ITEM_HISTORY` is on the order of tens of millions of rows, growing ~10K/day across all subclasses.
- The DETAILS column is `CLOB`. `DBMS_LOB.SUBSTR(...)` is the only legal way to LIKE-match it in a WHERE clause; the CLOB read dominates if the row count being CLOB-touched is large.
- Earlier the in-file comment said the SQL "takes 40-90s on prod". That was tolerable for an hourly job; the recent timeout suggests either stats drift, plan flip, or row growth pushed past the 120s edge.

### Open questions for you

1. **Run `EXPLAIN PLAN FOR` against the new SQL.** Does Oracle honor the `LEADING(i h) USE_HASH(h)` hint? If the plan still leads with `ITEM_HISTORY` (or an INDEX SCAN on it), the hint is being ignored and a HASH JOIN with `ims_items` as build is the right shape to force. Report:
   - The plan operation tree.
   - Estimated rows at each step.
   - Whether stats look fresh on `AGILE.ITEM` and `AGILE.ITEM_HISTORY` (check `LAST_ANALYZED` on `ALL_TABLES`).
2. **Index check on `AGILE.ITEM_HISTORY`.** Is there an index on:
   - `(TIMESTAMP)` — the date predicate would use it for a range scan.
   - `(ITEM)` or `(ITEM, TIMESTAMP)` — would let the hash-join probe side go to the index.
   - Report indexes via `ALL_INDEXES` + `ALL_IND_COLUMNS` filtered on `OWNER = 'AGILE'` and `TABLE_NAME = 'ITEM_HISTORY'`.
3. **Index check on `AGILE.ITEM`.** Is there an index on `SUBCLASS`? With ~few thousand IMS docs out of millions, an index range scan should be sub-second. If the IMS-items CTE is doing a full table scan, that's the first bottleneck.
4. **Long-running cancel evidence.** Cross-check `V$SQL` / `V$SESSION_LONGOPS` for sessions whose `SQL_TEXT` matches the OWNER-AUDIT shape with `LAST_CALL_ET > 60s`. If you see multiple sessions stuck at the same point in the plan, that pinpoints the operator to hint differently.
5. **CLOB I/O sanity.** `V$SQL_WORKAREA` plus session-level `events` for the failing SID — was the time spent on the CLOB read, or on a join? If CLOB I/O dominates, the next optimization is moving the LIKE filter into a function index or a non-CLOB indicator column (see Plan B below).

If you find the new shape's plan is still bad, the next safe code-level move is to switch the predicate ordering so the more-selective `:p1` is evaluated alone, then UNION the `:p2` results separately:

```sql
WITH ims_items AS (...),
hist AS (
  SELECT hh.ITEM, hh.USER_NAME, hh.REVNUMBER,
         hh."TIMESTAMP" AS LOCAL_DATE,
         DBMS_LOB.SUBSTR(hh.DETAILS, 4000, 1) AS det
    FROM AGILE.ITEM_HISTORY hh
   WHERE hh."TIMESTAMP" >= TRUNC(SYSDATE) - :days
)
SELECT i.ITEM_NUMBER, i.DESCRIPTION, h.det, h.USER_NAME, h.REVNUMBER, h.LOCAL_DATE
FROM ims_items i JOIN hist h ON h.ITEM = i.ID
WHERE h.det LIKE :p1
UNION ALL
SELECT i.ITEM_NUMBER, i.DESCRIPTION, h.det, h.USER_NAME, h.REVNUMBER, h.LOCAL_DATE
FROM ims_items i JOIN hist h ON h.ITEM = i.ID
WHERE h.det LIKE :p2 AND h.det NOT LIKE :p1
ORDER BY 1, 6;
```

UNION ALL with a NOT LIKE de-dup is uglier but lets the optimizer plan the two halves independently — and the first half is cheap (leading-wildcard-free).

---

## 3. Plan B — delta strategy (bigger change)

If the new SQL still times out, the right long-term answer is to stop pulling 365 days of history every hour. The pattern is already used by `ChangeQueryService` in this same repo (look at `queryDeltaFromDb(Timestamp since)`).

Outline:

1. Add a `lastSeenTimestamp` watermark to the cache snapshot.
2. On scheduled rebuild, query only `WHERE hh."TIMESTAMP" > :watermark` — typically a handful of rows per hour.
3. Merge new rows into the cached list; advance the watermark.
4. Keep a weekly full re-pull (cron `0 0 5 * * SUN`) as a safety net to catch out-of-order writes.

The cache merge needs to handle the marker/cell-change pairing logic (`Phase 2` in `pullFromSql`) — that pairing is item-scoped and time-bounded, so any item touched by the delta has to be re-paired against the FULL retained set for that item. The cleanest implementation is:

1. Pull the delta.
2. For every item present in the delta, drop that item's existing rows from the cache.
3. Re-pull the last 365 days FOR THOSE ITEMS ONLY (small WHERE-IN list), re-pair, merge.

That avoids the brittleness of incremental pairing logic.

---

## 4. What changed in code

File: `src/main/java/com/sandisk/plm/tracker/service/OwnerChangeAuditService.java`

- `pullFromSql(int)` body rewritten: CTE-first item filter, hash-join hint, single CLOB SUBSTR per row, more-selective predicate first.
- JDBC `setQueryTimeout` 120s → 240s.
- Behavior, cache shape, and row output are unchanged. No JSON contract changes, no callers touched.

Built and staged JAR is at `/Volumes/uls-ep-aglipccb/plm-toolkit/staging/plm-field-tracker-1.0.1.jar`. After `deploy.bat` runs the new SQL will be live; first hourly rebuild after that will tell us whether the new plan clears 240s.

---

## 5. Quick diagnostic commands to run on agprod

```sql
-- 5.1 Stats freshness for the two tables involved
SELECT table_name, last_analyzed, num_rows
  FROM all_tables
 WHERE owner = 'AGILE' AND table_name IN ('ITEM','ITEM_HISTORY');

-- 5.2 Indexes on ITEM_HISTORY
SELECT i.index_name, i.uniqueness, c.column_position, c.column_name
  FROM all_indexes i
  JOIN all_ind_columns c
    ON c.index_owner = i.owner AND c.index_name = i.index_name
 WHERE i.owner = 'AGILE' AND i.table_name = 'ITEM_HISTORY'
 ORDER BY i.index_name, c.column_position;

-- 5.3 Indexes on ITEM
SELECT i.index_name, i.uniqueness, c.column_position, c.column_name
  FROM all_indexes i
  JOIN all_ind_columns c
    ON c.index_owner = i.owner AND c.index_name = i.index_name
 WHERE i.owner = 'AGILE' AND i.table_name = 'ITEM'
   AND EXISTS (
     SELECT 1 FROM all_ind_columns c2
      WHERE c2.index_owner = i.owner AND c2.index_name = i.index_name
        AND c2.column_name = 'SUBCLASS'
   );

-- 5.4 EXPLAIN the new SQL (paste the body from §1 above with binds)
EXPLAIN PLAN FOR
WITH ims_items AS (
  SELECT i.ID, i.ITEM_NUMBER, i.DESCRIPTION
    FROM AGILE.ITEM i
   WHERE i.SUBCLASS = 9141
     AND NVL(i.DELETE_FLAG, 0) != 1
)
SELECT /*+ LEADING(i h) USE_HASH(h) */
       i.ITEM_NUMBER, i.DESCRIPTION,
       h.det AS DETAILS, h.USER_NAME, h.REVNUMBER, h.LOCAL_DATE
FROM ims_items i
JOIN (
  SELECT hh.ITEM, hh.USER_NAME, hh.REVNUMBER,
         hh."TIMESTAMP" AS LOCAL_DATE,
         DBMS_LOB.SUBSTR(hh.DETAILS, 4000, 1) AS det
    FROM AGILE.ITEM_HISTORY hh
   WHERE hh."TIMESTAMP" >= TRUNC(SYSDATE) - 365
) h ON h.ITEM = i.ID
WHERE h.det LIKE 'Owners updated via PLM Toolkit%'
   OR h.det LIKE '%Document Owner%'
ORDER BY i.ITEM_NUMBER, h.LOCAL_DATE;

SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format=>'ALL ALLSTATS LAST'));

-- 5.5 Any sessions currently running this shape?
SELECT s.sid, s.serial#, s.status, s.last_call_et, s.event, s.sql_id,
       SUBSTR(q.sql_text, 1, 200) AS sql_text_head
  FROM v$session s
  JOIN v$sql q ON q.sql_id = s.sql_id
 WHERE q.sql_text LIKE '%AGILE.ITEM_HISTORY%'
   AND q.sql_text LIKE '%Document Owner%'
   AND s.status = 'ACTIVE';

-- 5.6 LONGOPS detail for any active session running this shape
SELECT sid, opname, target, sofar, totalwork, units, elapsed_seconds,
       time_remaining, sql_id
  FROM v$session_longops
 WHERE time_remaining > 0
 ORDER BY elapsed_seconds DESC;
```

---

## 6. Acceptance criteria for "fixed"

- [ ] New SQL completes under 60s for `days=365` on agprod cold-cache.
- [ ] Plan shows IMS items as the leading rowsource AND a hash join.
- [ ] CLOB SUBSTR appears ONCE per row in the plan (search for `INLIST ITERATOR` or `FILTER` re-evaluating the function).
- [ ] No `ORA-01013` in plm-toolkit.log for 24 hours after deploy.
- [ ] Hourly rebuild log line: `[OWNER-AUDIT] hourly rebuild done rows=N in <ms>` with `<ms>` reliably under 60000.

If we miss any of these, escalate to Plan B (delta strategy) — separate ticket.
