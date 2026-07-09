# Scheduled Field Change Reports

**Date:** 2026-04-13
**Scope:** Field Changes tab only (pilot)

## Overview

Users can schedule recurring email reports of field change searches. A clock icon next to the existing star (save search) icon opens a modal where the user configures frequency and delivery time. The backend runs a scheduler that checks for due jobs, executes the saved query, and emails the Excel report. No database required — schedules persist in a JSON file.

## UI

### Trigger
- Clock icon button placed immediately to the right of the star icon in the Field Changes toolbar row (next to Export Excel / Email Me / star)
- Icon style matches the existing star button (same size, padding, hover behavior)
- Disabled state when no search has been performed (same pattern as Export Excel / Email Me)

### Schedule Modal
- Title: "Schedule Report"
- Pre-filled from the current search form values (read directly from DOM, no dependency on saved searches)

**Fields:**
| Field | Type | Details |
|-------|------|---------|
| Email | Text input | Pre-filled from session email |
| Search Summary | Read-only display | Shows the search params being scheduled (e.g., "Field: PDA,PDM / Days: 7") |
| Frequency | Radio buttons | Daily, Weekly |
| Day of Week | Dropdown | Monday-Sunday. Visible only when Weekly selected |
| Time | Dropdown | 30-minute intervals: 12:00 AM, 12:30 AM, ... 11:30 PM. Default: 7:00 AM |

**Buttons:** Save Schedule / Cancel

**Below the form:** "Your Schedules" section — a compact table listing the user's existing schedules with columns: Search Summary, Frequency, Time, Active toggle, Delete (X) button. Shows "No scheduled reports yet" when empty.

### Search Parameters Captured
These are read from the form at the moment the user clicks Save:
- `field` (field name filter)
- `item` (item number filter)
- `oldContains` (old value contains)
- `newContains` (new value contains)
- `user` (changed by)
- `days` (days back)
- `netFilter` (net-change filter toggle state)

## Backend Storage

### File
`./data/scheduled-reports.json`

Configured via `application.properties`:
```
app.scheduled-reports.file=./data/scheduled-reports.json
```

### Schema
Top-level: JSON object keyed by username. Each user has an array of schedule objects.

```json
{
  "vjindal": [
    {
      "id": "a1b2c3d4",
      "email": "vikas.jindal@sandisk.com",
      "searchParams": {
        "field": "PDA,PDM",
        "item": "",
        "oldContains": "",
        "newContains": "",
        "user": "",
        "days": "7",
        "netFilter": true
      },
      "frequency": "daily",
      "dayOfWeek": null,
      "timeOfDay": "07:00",
      "active": true,
      "lastRun": null,
      "createdAt": 1713020400000
    }
  ]
}
```

- `id`: 8-character random hex string (same pattern as saved searches)
- `dayOfWeek`: "MONDAY" through "SUNDAY", null for daily schedules
- `timeOfDay`: "HH:mm" in 24-hour format
- `lastRun`: epoch milliseconds of last execution, null if never run

## REST Endpoints

### GET /api/schedules
Returns the current user's schedules array.

### POST /api/schedules
Creates a new schedule. Request body:
```json
{
  "email": "vikas.jindal@sandisk.com",
  "searchParams": { ... },
  "frequency": "daily",
  "dayOfWeek": "MONDAY",
  "timeOfDay": "07:00"
}
```
Returns the created schedule object with generated `id`, `active: true`, `lastRun: null`, `createdAt`.

### DELETE /api/schedules/{id}
Deletes a schedule by ID. Returns `{ "success": true }`.

## Backend Scheduler

### Execution Loop
- Spring `@Scheduled(fixedDelay = 300000)` — runs every 5 minutes
- On each tick:
  1. Load all schedules from file
  2. For each active schedule, check if it's due (see Due Logic below)
  3. If due: execute the search, handle results, update `lastRun`, save file

### Due Logic
A schedule is due when:
1. Current time >= scheduled `timeOfDay` (within the current day for daily, or the matching dayOfWeek for weekly)
2. `lastRun` is null (never run) OR `lastRun` was before today's scheduled time (for daily) or before this week's scheduled time (for weekly)

Edge case: if the server was down during a scheduled time, the job runs on the next tick after startup (catches up once, does not send multiple backlog emails).

### Execution Steps (when due)
1. Run `ChangeQueryService` with the saved `searchParams`
2. If results > 0:
   - Generate Excel via `ExcelExportService`
   - Email via `EmailService.sendExcelReport()` to the schedule's email address
   - Log: `[SCHEDULE] Schedule {id} for {user}: {count} results emailed to {email}`
3. If results == 0:
   - Log: `[SCHEDULE] Schedule {id} for {user}: 0 results, email skipped`
4. Update `lastRun` to current timestamp
5. Persist updated schedules to file

### Logging
All schedule activity logged via `java.util.logging.Logger`:
- Schedule created/deleted
- Each execution: result count, email sent or skipped
- Errors (query failure, email failure) — schedule stays active, retries next cycle

## Reused Components
- `ChangeQueryService.search()` — runs the field change query
- `ExcelExportService` — generates the Excel attachment
- `EmailService.sendExcelReport()` — sends email with attachment
- Session email/username — for pre-filling modal and associating schedules

## Files to Create
1. `src/main/resources/static/schedule.js` — modal UI, CRUD calls, form handling
2. `src/main/java/.../service/ScheduledReportService.java` — file I/O, due-checking, scheduler loop
3. `src/main/java/.../controller/ScheduleController.java` — REST endpoints
4. `src/main/java/.../model/ScheduledReport.java` — data model

## Files to Modify
1. `index.html` — add clock icon button, schedule modal markup
2. `app.js` — wire up clock button click to open modal (or delegate to schedule.js)
3. `application.properties` — add `app.scheduled-reports.file` config
4. `style.css` — modal styling for schedule form (reuse existing modal patterns)
