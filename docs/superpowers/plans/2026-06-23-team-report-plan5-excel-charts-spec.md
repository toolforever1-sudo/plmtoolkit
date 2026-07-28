# Team Report Plan 5 — Native Excel Charts (spec for manual Excel template edits)

**Date:** 2026-06-23
**Type:** Spec for Vikas to apply in Excel (no server Python change required).
**Why manual:** `build_team_report.py` can't *create* Excel charts (openpyxl drops charts on save). Instead, `restore_charts_from_source()` copies **all** `xl/charts/*` from the prior-month workbook verbatim, and charts auto-update because they point at fixed cell ranges the script refills each month. So the way to add a native Excel chart is: **add it once, by hand, to the master/template workbook; the script then carries it forward forever with no code change.**

---

## What already exists vs. what this spec adds

| Noraida item | Excel status |
|---|---|
| #2 Volume by PCM | ✅ exists (`chart8`, "Total Volume Process from Jan-…") |
| #3 Volume by Month | ✅ exists (`chart9`, "Total Volume Process by Month FY 2026") |
| #6 Change-Activities pie | ✅ exists (`chart10`, pivot pie) |
| **#4 Changes by Month, ECO/MCO/AML split + affected line** | **ADD — Chart A below** |
| **#7 ECN by Product Line, per month (stacked)** | **ADD — Chart B below** |
| #5 Changes by Year (2014-2026) | ❌ blocked — no yearly data in the workbook (only in the Plan 3 JSON seed). Needs a yearly-data sheet seeded into the workbook first; out of scope here. |
| #8 ECN by PL by Year | ❌ blocked — same (no yearly per-PL data). |

> Do these edits on the **template** workbook that gets uploaded as `--previous-month` for the next run (i.e. the latest `Team_Report_2026_*.xlsx` you roll forward). Once added, every future month inherits them automatically.

---

## Chart A — "Total Changes Processed by Month" (#4)

Mirrors Noraida's image008: clustered columns for **Total ECO / Total MCO / Total AML** per month + a **Total xCO Affected Items** line on a secondary axis.

### A1. Add a helper block (clean, contiguous, formula-driven)

On the **`Total Changes Process`** sheet, in a free area to the right of the month matrix — use columns **BA–BF, rows 3–9** (adjust if occupied). Enter:

| Cell | Content |
|---|---|
| BA3 | `Month` |  BB3 | `ECO` |  BC3 | `MCO` |  BD3 | `AML` |  BE3 | `Affected items` |
| BA4 | `Jan` | BA5 | `Feb` | BA6 | `Mar` | BA7 | `Apr` | BA8 | `May` |

Formulas (row 4 = Jan; the per-month ECO/MCO/AML columns in the matrix are: **ECO** = F/J/N/R/V, **MCO** = G/K/O/S/W, **AML** = E/I/M/Q/U for Jan–May; PCM rows are 5–10):

```
BB4 = SUM(F5:F10)     ' ECO Jan      BC4 = SUM(G5:G10)  ' MCO Jan   BD4 = SUM(E5:E10)  ' AML Jan
BB5 = SUM(J5:J10)                    BC5 = SUM(K5:K10)              BD5 = SUM(I5:I10)
BB6 = SUM(N5:N10)                    BC6 = SUM(O5:O10)              BD6 = SUM(M5:M10)
BB7 = SUM(R5:R10)                    BC7 = SUM(S5:S10)              BD7 = SUM(Q5:Q10)
BB8 = SUM(V5:V10)                    BC8 = SUM(W5:W10)              BD8 = SUM(U5:U10)
```

For **Affected items** (BE4:BE8), point at the existing monthly affected-items totals on **`Total Volume Process2026`** (the same row `chart9` uses for its line — the row 33 `=B9,C9,…` "total xCO affected items" block). Use:
```
BE4 = 'Total Volume Process2026'!B33   ' Jan affected items
BE5 = 'Total Volume Process2026'!C33   ' Feb   (verify these are the Jan..May affected-item cells chart9's line uses; adjust column if the block starts elsewhere)
BE6 = 'Total Volume Process2026'!D33
BE7 = 'Total Volume Process2026'!E33
BE8 = 'Total Volume Process2026'!F33
```
> These are formula cells: when the script refills the PCM matrix each month, the SUMs and the affected-items references recompute on open. The helper block carries forward (openpyxl preserves untouched sheets/cells).

### A2. Insert the chart

- Select `BA3:BD8` → **Insert → Clustered Column**.
- Add the affected-items line: right-click chart → **Select Data → Add** series `Affected items` = `BE4:BE8`; then select that series → **Format Data Series → Secondary Axis → Change Chart Type → Line**.
- Title: **"Total Changes Processed by Month"**.
- Colors to match the deck: ECO `#4a6fa5` (blue), MCO `#9CA3AF` (gray), AML `#C7801B` (amber), line `#6f42c1`.
- Place it on the `Total Changes Process` sheet (or wherever you keep the monthly charts).

---

## Chart B — "ECN Processed by Product Line, by Month" (#7)

The **`ECN process by PL`** sheet is already a PivotTable: **Row Labels = Product Line, Column Labels = Month, Values = Count of ECN#** — currently filtered to a single month (`Apr_2026`/`May_2026`). Just expand it to all months and add a stacked PivotChart.

### B1. Expand the pivot to all months
- Click into the pivot → in the **Column Labels** / **Month** field, clear the single-month filter so it shows **Jan_2026 … May_2026** as columns (Grand Total stays).
- The pivotCache already holds every month, so no data change is needed.

### B2. Add the stacked PivotChart
- With the pivot selected → **Insert → PivotChart → Stacked Column**.
- This gives a column per month, segmented by product line (image007's shape).
- Title: **"ECN Processed by Product Line, by Month"**.
- Optional: to avoid a 30-line legend, in the pivot's Row Labels apply a **Top 12 by Grand Total** value filter (Row Labels → Value Filters → Top 10/12) — matches the in-app "top 12 + Other" treatment.

### B3. Monthly-refresh caveat (verify on first roll-forward)
`restore_charts_from_source()` carries the PivotChart XML forward automatically, and `restore_and_patch_pivots()` already refreshes pivot caches. **After you add this and run the next month, open the output and confirm the new PivotChart advanced to include the new month.** If it does NOT (i.e. this pivot needs its range/filter advanced like `chart10`'s pivot does), tell me — that's the one spot where a small `build_team_report.py` patch (extend the pivot-filter advance to this pivot) may be needed. I can write + locally-test that patch if the verification shows it's required.

---

## After editing the template

1. Save the template workbook (keep its canonical `Team_Report_2026_<Month>.xlsx` name).
2. Run the normal monthly generation (or a test run: `python build_team_report.py --previous-month <your-edited>.xlsx --volume-report <vol>.xlsx --month <Next>_2026 --output /tmp/out.xlsx --skip-pivot-refresh`).
3. Open the output and confirm **both new charts are present and show the latest month's data** (Chart A's helper SUMs recompute; Chart B's pivot includes the new month).
4. From then on, every roll-forward inherits both charts with zero further effort.

## Out of scope (blocked)
- **#5 / #8 yearly Excel charts** need 2014–2026 data *in the workbook*. Today that history exists only in the Plan 3 JSON seed (`yearly-history.json`), not in any workbook sheet. To chart it in Excel you'd first seed a `Yearly History` sheet into the workbook (year rows × ECO/MCO/AML/affected, and year × product-line) — and that needs Noraida's real per-PCM/per-PL historical numbers. Revisit once her file lands.
