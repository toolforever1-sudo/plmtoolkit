# Data Compare Tab — Design Spec

**Date:** 2026-04-15
**Scope:** New "Data Compare" tab for comparing external data (Excel/CSV) against live Agile PLM values

## Overview

A new tab that lets users upload an Excel/CSV file (e.g., SAP data), map columns to Agile field names, filter to a manageable subset, and run a side-by-side comparison against live Agile data. Results are color-coded to highlight matches, differences, and missing values. Non-compared columns from the Excel can be passed through for context.

## Access Control

- Available to all logged-in users (not admin-restricted)
- Uses the existing Agile Lookup microservice for field lookups

## Workflow (5-Step Wizard)

### Step 1: Upload
- User uploads an Excel (.xlsx) or CSV file
- System reads all column headers and first 5 rows as preview
- User selects which column is the **Part Number** key (dropdown of all headers)
- Display: file name, total row count, column count

### Step 2: Filter
- Optional — user can skip to proceed with all rows
- Filter UI: "Compare rows where [Column dropdown] = [Value input]"
- Shows filtered count: "Filtered to 185 of 57,634 rows"
- Maximum 500 items after filtering (hard cap with warning)
- User clicks "Next" to proceed

### Step 3: Map Fields
- System auto-suggests matches between Excel headers and Agile field names using fuzzy matching:
  - Strip underscores, normalize case
  - Match against the 1,250+ Agile field names from `agile-field-names.json`
  - Confidence: High (exact match after normalization), Medium (close match), None
- Each Excel column shown as a row with:
  - Excel column name
  - Action dropdown: **Compare** / **Include (no compare)** / **Skip**
  - Agile field dropdown (only shown when action = Compare, pre-filled with suggestion)
  - Confidence badge (green/amber/gray)
- User confirms or adjusts mappings
- For unmatched columns: suggest closest Agile field names via dropdown
- Part number column is automatically set to "Include" and not editable

### Step 4: Compare
- Agile Lookup runs for all filtered part numbers
- Batched: 100 items per API call to the Agile microservice
- Progress bar: "Looking up 185 items... 100/185 done"
- For each item × each mapped field: compare Excel value vs Agile value
- Matching logic: case-insensitive string comparison after trimming whitespace

### Step 5: Results
- **Summary bar**: "185 items compared. 142 match fully. 38 have differences. 5 not found in Agile."
- **Filter buttons**: All / Differences Only / Matches Only / Not Found
- **Table layout**:
  - Column 1: Part Number (from Excel)
  - Column 2: SAP Material / Excel key (if different from Part Number column)
  - Compared fields: paired sub-columns [Excel Value | Agile Value] with color coding
  - Included fields: single column (pass-through from Excel, no comparison)
  - Status column: Match / Differs / Not Found
- **Color coding per cell pair**:
  - Green background: values match
  - Red background: values differ (both have data)
  - Amber background: value in one source but blank in the other
  - White: not compared (included/pass-through columns)
- **Items not found in Agile**: shown with "Not Found" status, Excel data displayed, Agile columns blank
- **Export Excel**: download comparison with same color coding (SXSSFWorkbook for large datasets)
- **Email Me**: send the comparison Excel to the user

## Backend

### DataCompareController.java
Endpoints:
- `POST /api/compare/upload` — multipart file upload, returns: headers array, preview rows (first 5), total row count, generated session ID
- `POST /api/compare/filter` — body: {sessionId, filterColumn, filterValue}, returns: filtered row count
- `POST /api/compare/suggest-mappings` — body: {sessionId, headers[]}, returns: mapping suggestions with confidence scores
- `POST /api/compare/run` — body: {sessionId, partNumberColumn, mappings[], filterColumn?, filterValue?}, triggers async comparison, returns immediately
- `GET /api/compare/status` — returns progress (items completed / total, status)
- `GET /api/compare/results` — returns comparison results
- `GET /api/compare/export` — downloads comparison as Excel with color coding

### DataCompareService.java
- Stores uploaded file data in memory (per session, cleaned up after 30 min)
- Parses Excel using Apache POI
- Fuzzy field matching:
  - Normalize: strip `_`, lowercase, trim
  - Exact match after normalization → High confidence
  - Contains match or Levenshtein distance ≤ 3 → Medium confidence
  - No match → suggest top 3 closest
- Comparison execution:
  - Reads filtered rows from stored Excel data
  - Batches part numbers (100 per call) to existing Agile Lookup microservice (`/api/agile-lookup/manual-lookup`)
  - Merges results: for each item × each mapped field, compares Excel value vs Agile value
  - Stores comparison results in memory for the session
- Excel export: SXSSFWorkbook with conditional formatting (green/red/amber fills)

### Field Name Normalization Examples
| Excel Header | Normalized | Agile Field | Confidence |
|---|---|---|---|
| PRODUCT_LINE | product line | Product Line | High |
| MEMORY_TECHNOLOGY_CODE | memory technology code | Memory Technology | Medium |
| AS_SOLD_MB | as sold mb | As Sold | Medium |
| SUPER_FAMILY | super family | (no match) | None |
| MATERIAL | material | (no match — it's a key, not a field) | None |

## Frontend

### compare.js
- Multi-step wizard with step indicator bar at the top
- Step 1: File upload with drag & drop, preview table, part number column selector
- Step 2: Filter row with column/value inputs, filtered count display
- Step 3: Mapping table with action dropdowns and Agile field dropdowns (searchable)
- Step 4: Progress bar during comparison
- Step 5: Results table with color coding, filter buttons, export/email

### index.html
- Tab: "Data Compare" — visible to all users
- Panel with wizard container
- Script tag for `compare.js`

### CSS
- Color classes: `.cell-match` (green), `.cell-diff` (red), `.cell-partial` (amber)
- Step indicator bar
- Night mode support

## Data Flow
```
Upload Excel → Parse headers + preview → User picks part number column
→ User filters rows (optional) → System suggests field mappings
→ User confirms mappings → System looks up Agile values (batched)
→ System compares cell-by-cell → Results displayed with color coding
→ User exports or emails comparison report
```

## Files to Create
1. `src/main/java/com/sandisk/plm/tracker/service/DataCompareService.java`
2. `src/main/java/com/sandisk/plm/tracker/controller/DataCompareController.java`
3. `src/main/resources/static/compare.js`

## Files to Modify
1. `src/main/resources/static/index.html` — add tab + panel
2. `src/main/resources/static/app.js` — add tab switching
3. `src/main/resources/static/style.css` — add comparison styles

## Limits
- Max file size: 10 MB
- Max rows after filtering: 500
- Agile lookup batch size: 100 items per call
- Session data cleanup: 30 minutes after last access
- Max columns to compare: 20
