# Reports Tab — Design Spec

**Date:** 2026-04-14
**Scope:** New "Reports" tab for running Python-based reports with async execution and email delivery

## Overview

A new admin-only tab that presents a list of configurable reports as cards. Users click to generate a report, which runs a Python script as an external process in the background. When complete, the output file is available for download and emailed to the user. Reports are defined in a JSON config file — no code changes needed to add new reports.

## Access Control

- Tab visible only to users with `isPlmAdmin = true` (membership in `pdl-plm-admin` AD group)
- All endpoints gated by the same admin check
- Non-admin users do not see the tab at all

## UI

### Tab
- "Reports" tab added after "Change History" in the tab bar
- Only rendered in the DOM when `isPlmAdmin` is true (set during session check on page load)

### Report Cards
- Grid layout, one card per report defined in `./data/reports.json`
- Each card displays:
  - Report name (bold)
  - Description (1-2 lines)
  - Estimated time
  - "Generate" button
- Card states:
  - **Idle**: Green "Generate" button
  - **Running**: Disabled button showing "Generating..." with elapsed time, pulsing border
  - **Completed**: Download link + "Generated X minutes ago" text + option to re-run
  - **Failed**: Red error message with option to retry

### Polling
- When a report is running, frontend polls `/api/reports/{id}/status` every 5 seconds
- Stops polling when status is `completed` or `failed`

## Backend

### ReportService.java
- Loads report definitions from `./data/reports.json` on startup
- `runReport(reportId, userEmail, displayName)`:
  - Validates report exists and is not already running
  - Spawns a background thread that executes: `python <script> --config <config> --no-email --output ./data/reports/output/`
  - Tracks status in a `ConcurrentHashMap<String, ReportStatus>`:
    - `status`: idle / running / completed / failed
    - `startTime`: epoch ms
    - `endTime`: epoch ms
    - `outputFile`: path to generated file
    - `error`: error message if failed
    - `userEmail`: who requested it
  - On completion: emails the output file to the requesting user via `EmailService.sendBomReport()`
  - On failure: logs the error, updates status
- `getStatus(reportId)`: returns current status
- `getOutputFile(reportId)`: returns the File for download
- `getReports()`: returns list of report definitions

### ReportController.java
- `GET /api/reports` — returns report definitions (admin only)
- `POST /api/reports/{id}/run` — triggers report execution, returns immediate response
- `GET /api/reports/{id}/status` — returns current status (running/completed/failed, elapsed time, output file info)
- `GET /api/reports/{id}/download` — streams the output file to the browser

All endpoints check `isPlmAdmin` from session.

### Report Config (`./data/reports.json`)
```json
[
  {
    "id": "ecn-report",
    "name": "ECN Change Order Report",
    "description": "ECN changes created this year with analysis, cycle times, and team breakdown",
    "script": "./reports/ecn_report_generator.py",
    "config": "./reports/ecn_report.properties",
    "outputPattern": "ECN_Report_*.xlsx",
    "estimatedTime": "2-5 minutes"
  }
]
```

### Python Execution
- Command: `python <script> --config <config> --output <output-dir>`
- Working directory: the reports directory
- Process stdout/stderr captured and logged with `[REPORT]` prefix
- Timeout: 10 minutes (configurable)
- Only one instance of each report can run at a time
- The Python script handles its own email delivery (richer context, charts, formatting)
- Java does NOT send a separate email — it only serves the download link in the UI

## Server Directory Structure
```
./reports/                          # Python scripts and configs
  ecn_report_generator.py
  ecn_report.properties
./data/reports/output/              # Generated output files
  ECN_Report_2026-04-14.xlsx
./data/reports.json                 # Report definitions
```

## Frontend

### reports.js
- `loadReports()` — fetches report list from `/api/reports`, renders cards
- `runReport(id)` — POST to trigger, starts polling
- `pollReportStatus(id)` — polls every 5 seconds, updates card UI
- `downloadReport(id)` — triggers file download

### index.html
- Tab button: `<button class="tab" id="tabReports" onclick="switchTab('reports')">Reports</button>` — hidden by default, shown when `isPlmAdmin`
- Panel: `<div id="panelReports">` with card grid container
- Script tag: `<script src="reports.js"></script>`

## Configuration Properties
```properties
# Python executable path (default: python)
app.reports.python=python
# Report execution timeout in seconds
app.reports.timeout=600
```

## Files to Create
1. `src/main/java/com/sandisk/plm/tracker/service/ReportService.java`
2. `src/main/java/com/sandisk/plm/tracker/controller/ReportController.java`
3. `src/main/resources/static/reports.js`

## Files to Modify
1. `src/main/resources/static/index.html` — add tab button + panel
2. `src/main/resources/static/app.js` — add tab switching, admin visibility
3. `src/main/resources/application.properties` — add python/timeout config

## Dependencies
- Python 3.x installed on the server
- pip packages: `oracledb`, `openpyxl` (and optionally `tqdm`)
- The Python script handles its own email delivery (no `--no-email` flag needed)
