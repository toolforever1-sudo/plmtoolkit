# Team Report Plan 6 — PPTX deck: pie (#6), ECN-by-PL (#7), PCM workload+AI (#10), AI suggestions (#9)

> **Hybrid build (user choice 2026-06-23):** build the 4 slides **programmatically** (python-pptx append) so the deck works end-to-end now, AND ship a **template-placeholder polish spec** (§Polish) for the on-brand upgrade later.

**Goal:** Append four new slides to the Team Report PowerPoint export — Change-Activities pie (#6), ECN-by-Product-Line stacked (#7), PCM workload table + AI summary (#10), and AI report-improvement suggestions (#9) — consuming the Plan 2-4 keys already present in the `--data` payload.

**Architecture:** `team_report_pptx_generator.py` (python-pptx) gains an `append_plan6_slides(prs, data)` pass called at the end of `build_pcm_deck_from_template`, before `prs.save()`. Each new slide is appended via `prs.slides.add_slide(blank_layout)` + native `add_chart`/`add_table`/`add_textbox`. Slides self-skip when their data key is absent (e.g. `pcmWorkload`/`reportSuggestions` only exist after `regenerate-ai` has run). No Java change (the keys are already in the JSON). The generator is **server-only** (`~/Documents/plm-toolkit 2/data/team-report/`, gitignored) — edits go to the local copy, locally structure-verified, then **handed to Vikas to deploy + visually check** (no local LibreOffice to render slides).

**Tech Stack:** python-pptx ≥0.6.21 (install locally to test: `pip install python-pptx`). Verification = run generator + unzip/inspect the .pptx (slide count, chart parts, titles); visual check is Vikas's in PowerPoint.

---

## Reconciliation (what the deck has vs. this adds)

- **Already AUTO charts:** #2 (slide 6), #3 (slide 5), #4 ECO/MCO/AML by month (slide 7), #6 *as tables* (slide 4).
- **This plan adds (programmatic append):** #6 **pie**, #7 stacked, #10 workload table+AI, #9 suggestions.
- **Excluded:** #5 yearly (needs a Java step to merge `/history` into the `--data` JSON — follow-up), #8 yearly-PL (blocked, no data).

Data sources in `--data`: `activityTypes` (always), `ecnByProductLine` (always), `ytd`/`pcms`/`months` (always), `pcmWorkload`/`reportSuggestions` (only after `regenerate-ai`).

---

## Task 1: Generator — append the 4 slides (server-only file)

**File:** `~/Documents/plm-toolkit 2/data/team-report/team_report_pptx_generator.py` (NOT in git).

Existing facts to reuse: `_add_text(slide,l,t,w,h,text,*,size,bold,color,align,...)`; brand colors `DECK_RED/DECK_GRAY/DECK_MUTED/DECK_INK`; `from pptx.util import Emu, Pt`; `from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION`; `CategoryChartData`; slide is 12192000×6858000 EMU. `re` is imported.

- [ ] **Step 1: Add a palette + blank-slide helper + the 4 append functions**

Add near the other helpers (e.g. after `_replace_picture_with_chart`):

```python
# --- Plan 6: extra slides (pie #6, ECN-by-PL #7, PCM workload #10, AI suggestions #9) ---

PLAN6_PALETTE = [
    RGBColor(0x4a,0x6f,0xa5), RGBColor(0x1F,0x8A,0x4C), RGBColor(0xC7,0x80,0x1B),
    RGBColor(0x7C,0x3A,0xED), RGBColor(0xB8,0x34,0x2B), RGBColor(0x0F,0x76,0x6E),
    RGBColor(0xD9,0x77,0x06), RGBColor(0x2c,0x3e,0x50), RGBColor(0x6B,0x72,0x80),
    RGBColor(0x15,0x80,0x3D), RGBColor(0x93,0x33,0xEA), RGBColor(0x0E,0x74,0x90),
    RGBColor(0xA1,0x62,0x07),
]

def _append_blank_slide(prs):
    """Append a slide on the cleanest available layout, stripping any
    auto-injected placeholders so we control the canvas."""
    layout = None
    for lo in prs.slide_layouts:
        if lo.name.strip().lower() == "blank":
            layout = lo
            break
    if layout is None:
        layout = prs.slide_layouts[-1]
    slide = prs.slides.add_slide(layout)
    for ph in list(slide.placeholders):
        ph._element.getparent().remove(ph._element)
    return slide

def append_activity_pie_slide(prs, data):
    items = data.get("activityTypes") or []
    if not items:
        return
    slide = _append_blank_slide(prs)
    _add_text(slide, 600000, 350000, 11000000, 800000,
              "Change Activities by Type\n" + data.get("monthLabel", ""),
              size=26, bold=True, color=DECK_INK)
    cd = CategoryChartData()
    cd.categories = [it.get("name", "") for it in items]
    cd.add_series("Changes", [it.get("count", 0) for it in items])
    cs = slide.shapes.add_chart(XL_CHART_TYPE.DOUGHNUT,
                                Emu(1400000), Emu(1500000), Emu(9300000), Emu(4700000), cd)
    ch = cs.chart
    ch.has_title = False
    ch.has_legend = True
    ch.legend.position = XL_LEGEND_POSITION.RIGHT
    ch.legend.include_in_layout = False
    try:
        plot = ch.plots[0]
        plot.has_data_labels = True
        plot.data_labels.number_format = "0.0%"
        plot.data_labels.number_format_is_linked = False
        plot.data_labels.show_percentage = True
        plot.data_labels.show_value = False
        for i, pt in enumerate(plot.series[0].points):
            pt.format.fill.solid()
            pt.format.fill.fore_color.rgb = PLAN6_PALETTE[i % len(PLAN6_PALETTE)]
    except Exception:
        pass

def _plan6_top_n_pl(ecn_by_month, months, n=12):
    totals = {}
    for m in months:
        for pl, c in (ecn_by_month.get(m) or {}).items():
            totals[pl] = totals.get(pl, 0) + (c or 0)
    ranked = sorted(totals, key=lambda p: -totals[p])
    top = ranked[:n]
    has_other = len(ranked) > n
    lines = top + (["Other"] if has_other else [])
    by_line = {pl: [] for pl in lines}
    for m in months:
        row = ecn_by_month.get(m) or {}
        for pl in top:
            by_line[pl].append(row.get(pl, 0))
        if has_other:
            by_line["Other"].append(sum(v for k, v in row.items() if k not in top))
    return lines, by_line

def append_ecn_by_pl_slide(prs, data):
    ecn = data.get("ecnByProductLine") or {}
    months = data.get("months") or []
    if not ecn or not months:
        return
    lines, by_line = _plan6_top_n_pl(ecn, months, 12)
    if not lines:
        return
    slide = _append_blank_slide(prs)
    yy = data.get("month", "_").split("_")[-1][-2:]
    _add_text(slide, 600000, 350000, 11000000, 800000,
              "ECN Processed by Product Line, by Month\n" + data.get("monthLabel", ""),
              size=26, bold=True, color=DECK_INK)
    cd = CategoryChartData()
    cd.categories = [m + "'" + yy for m in months]
    for pl in lines:
        cd.add_series(pl, by_line[pl])
    cs = slide.shapes.add_chart(XL_CHART_TYPE.COLUMN_STACKED,
                                Emu(600000), Emu(1500000), Emu(11000000), Emu(4700000), cd)
    ch = cs.chart
    ch.has_title = False
    ch.has_legend = True
    ch.legend.position = XL_LEGEND_POSITION.BOTTOM
    ch.legend.include_in_layout = False
    try:
        for i, ser in enumerate(ch.series):
            ser.format.fill.solid()
            ser.format.fill.fore_color.rgb = (RGBColor(0xCB, 0xD5, 0xE1)
                                              if ser.name == "Other"
                                              else PLAN6_PALETTE[i % len(PLAN6_PALETTE)])
    except Exception:
        pass

def append_pcm_workload_slide(prs, data):
    pcms = data.get("pcms") or []
    ytd = data.get("ytd") or {}
    if not pcms:
        return
    months = max(1, len(data.get("months") or []))
    grand = sum((ytd.get(p) or [0, 0, 0])[1] for p in pcms)
    rows = sorted(([p] + list(ytd.get(p) or [0, 0, 0]) for p in pcms), key=lambda r: -r[2])
    slide = _append_blank_slide(prs)
    _add_text(slide, 600000, 300000, 11000000, 600000,
              "PCM Workload Analytics\n" + data.get("monthLabel", ""),
              size=26, bold=True, color=DECK_INK)
    nrows = len(rows) + 1
    tbl = slide.shapes.add_table(nrows, 5, Emu(600000), Emu(1250000),
                                 Emu(6100000), Emu(min(360000, 300000) * nrows)).table
    for j, h in enumerate(["PCM", "Total xCO", "Affected", "Avg/mo", "% total"]):
        tbl.cell(0, j).text = h
    for i, r in enumerate(rows, start=1):
        p, items, xco = r[0], r[1], r[2]
        vals = [str(p), str(xco), "{:,}".format(items), str(round(xco / months)),
                (str(round(xco / grand * 100)) + "%" if grand else "0%")]
        for j, v in enumerate(vals):
            tbl.cell(i, j).text = v
    pw = data.get("pcmWorkload") or {}
    txt = (pw.get("summary") or "").strip()
    if (pw.get("recommendations") or "").strip():
        txt += "\n\nRecommendations:\n" + pw.get("recommendations").strip()
    if txt:
        _add_text(slide, 6900000, 1250000, 4700000, 4900000, txt,
                  size=11, color=DECK_INK)
    else:
        _add_text(slide, 6900000, 1250000, 4700000, 700000,
                  "Run AI analysis (open the Team Report tab) to add the exec summary + recommendations.",
                  size=11, color=DECK_MUTED)

def append_report_suggestions_slide(prs, data):
    rs = data.get("reportSuggestions") or {}
    sug = (rs.get("suggestions") or "").strip()
    if not sug:
        return
    slide = _append_blank_slide(prs)
    _add_text(slide, 600000, 350000, 11000000, 700000,
              "AI Suggestions to Improve This Report", size=26, bold=True, color=DECK_INK)
    lines = [re.sub(r"^\s*[•\*\-\d\.\)]+\s*", "", l).strip()
             for l in sug.split("\n") if l.strip()]
    body = "\n".join("•  " + l for l in lines)
    _add_text(slide, 700000, 1450000, 10800000, 4900000, body, size=14, color=DECK_INK)
```

- [ ] **Step 2: Wire the append pass into `build_pcm_deck_from_template`**

In `build_pcm_deck_from_template`, immediately BEFORE `out_path.parent.mkdir(...)` / `prs.save(str(out_path))` (the end of the function), add:

```python
    # Plan 6 — append the extra slides (each self-skips if its data is absent).
    for _fn in (append_activity_pie_slide, append_ecn_by_pl_slide,
                append_pcm_workload_slide, append_report_suggestions_slide):
        try:
            _fn(prs, data)
        except Exception as e:
            print(f"WARN: Plan6 slide {_fn.__name__} failed: {e}", file=sys.stderr)
```

(Leave the `print(f"PPTX OK …")` line; optionally update its slide count to `len(prs.slides)`.)

---

## Verification (orchestrator, local — structural)

1. `pip install python-pptx` into the python3 the toolkit uses.
2. Get a real `--data` JSON: from a running instance, `curl -b cookie /api/team-report/data?month=May_2026 > /tmp/tr-data.json` (the May sidecar already has `pcmWorkload`/`reportSuggestions` from the Plan 4 regen, so those slides populate).
3. Run the generator directly:
   ```bash
   cd "~/Documents/plm-toolkit 2/data/team-report"
   python3 team_report_pptx_generator.py --data /tmp/tr-data.json \
     --template ./Pcm_Workload_Template.pptx --out /tmp/tr-plan6.pptx
   ```
4. Structural checks on `/tmp/tr-plan6.pptx`:
   - `unzip -l /tmp/tr-plan6.pptx | grep -c ppt/slides/slide` → **19** (15 + 4).
   - `unzip -l /tmp/tr-plan6.pptx | grep -c ppt/charts/chart` → at least +2 (pie + stacked).
   - Confirm the 4 new slide titles appear (grep slide XML for "Change Activities by Type", "ECN Processed by Product Line", "PCM Workload Analytics", "AI Suggestions to Improve").
5. **Hand off to Vikas:** deploy the edited generator to the server, then **open the .pptx in PowerPoint** to visually confirm the new slides (charts render, table readable, text fits). No local LibreOffice → the visual pass is Vikas's.

---

## Polish (the hybrid's second half — template-placeholder spec, for the on-brand upgrade)

When you want the new slides to match the deck's design language instead of the programmatic blank canvas:

1. In `Pcm_Workload_Template.pptx` (PowerPoint), duplicate an existing AUTO chart slide (e.g. slide 6) **four times** to get on-brand layouts with a title placeholder + a **picture placeholder** (the generator swaps `shape_type == 13` pictures for charts). Place them at known indexes (e.g. 15–18). For the table/text slides, add a title + an empty content placeholder.
2. Add index constants in the generator (e.g. `AUTO_PIE_IDX = 15`, …) and convert each `append_*` function to a `refresh_*` function that finds the slide's title textbox + picture placeholder and uses `_replace_picture_with_chart` (the same pattern slides 5–7 use) instead of `_append_blank_slide`.
3. Add them to the `auto_steps` list (not `CARRY_SLIDE_INDEXES`).
4. Bump the template's slide count check.

This keeps the exact data wiring from Task 1 — only the canvas changes from "appended blank" to "on-brand placeholder."

## Notes
- `#5` yearly slide: a follow-up — have the Java `/pptx` endpoint also load `/history` and merge a `years` array into the `--data` JSON, then add an `append_yearly_slide` (clustered column from `data["years"]`). Small, but its own task.
- The generator is server-only; mirror this change to the production server when deploying (same hand-off as `build_team_report.py`).
