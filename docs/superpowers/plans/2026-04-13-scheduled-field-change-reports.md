# Scheduled Field Change Reports — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users schedule recurring field change report emails from the Field Changes tab, with schedules stored in a JSON file and a backend scheduler that runs them.

**Architecture:** A new `schedule.js` handles the modal UI and CRUD API calls. A `ScheduleController` exposes 3 REST endpoints. A `ScheduledReportService` manages JSON file persistence and runs a `@Scheduled` loop every 5 minutes that checks for due jobs, executes the field change query via the existing `ChangeQueryService`, and emails results via `EmailService`.

**Tech Stack:** Spring Boot 2.7, Java 11, vanilla JavaScript, Jackson for JSON, existing SMTP email pipeline.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/.../model/ScheduledReport.java` | Data model for a scheduled report |
| Create | `src/main/java/.../service/ScheduledReportService.java` | JSON file I/O, due-checking, scheduler loop |
| Create | `src/main/java/.../controller/ScheduleController.java` | REST endpoints: list, create, delete |
| Create | `src/main/resources/static/schedule.js` | Modal UI, CRUD calls, schedule list rendering |
| Modify | `src/main/resources/static/index.html` | Clock icon button, schedule modal HTML, script tag |
| Modify | `src/main/resources/application.properties` | Add `app.scheduled-reports.file` config |

---

### Task 1: Data Model — ScheduledReport.java

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/model/ScheduledReport.java`

- [ ] **Step 1: Create the model class**

```java
package com.sandisk.plm.tracker.model;

import java.util.Map;
import java.util.UUID;

public class ScheduledReport {

    public String id;
    public String username;
    public String email;
    public Map<String, String> searchParams;
    public String frequency;   // "daily" or "weekly"
    public String dayOfWeek;   // "MONDAY".."SUNDAY", null for daily
    public String timeOfDay;   // "HH:mm" 24-hour format
    public boolean active;
    public Long lastRun;       // epoch ms, null if never run
    public long createdAt;

    public ScheduledReport() {}

    public ScheduledReport(String username, String email, Map<String, String> searchParams,
                           String frequency, String dayOfWeek, String timeOfDay) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.username = username;
        this.email = email;
        this.searchParams = searchParams;
        this.frequency = frequency;
        this.dayOfWeek = dayOfWeek;
        this.timeOfDay = timeOfDay;
        this.active = true;
        this.lastRun = null;
        this.createdAt = System.currentTimeMillis();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/model/ScheduledReport.java
git commit -m "feat: add ScheduledReport data model"
```

---

### Task 2: ScheduledReportService — File I/O and Scheduler Loop

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/ScheduledReportService.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add config property to application.properties**

Add this line after the existing `app.saved-searches.file` line (line 49):

```properties
# Scheduled reports
app.scheduled-reports.file=./data/scheduled-reports.json
```

- [ ] **Step 2: Create ScheduledReportService**

This follows the same pattern as `SavedSearchService.java` — ConcurrentHashMap keyed by username, Jackson file I/O, `@PostConstruct` load.

```java
package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.ChangeRecord;
import com.sandisk.plm.tracker.model.ScheduledReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class ScheduledReportService {

    private static final Logger logger = Logger.getLogger(ScheduledReportService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.scheduled-reports.file:./data/scheduled-reports.json}")
    private String filePath;

    @Autowired
    private ChangeQueryService changeQueryService;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private EmailService emailService;

    // username -> list of scheduled reports
    private final ConcurrentHashMap<String, List<ScheduledReport>> userSchedules = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
        logger.info("[SCHEDULER] Loaded " + countAll() + " scheduled reports");
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    public List<ScheduledReport> getSchedules(String username) {
        return userSchedules.getOrDefault(username, new ArrayList<>());
    }

    public ScheduledReport createSchedule(String username, ScheduledReport schedule) {
        schedule.username = username;
        userSchedules.computeIfAbsent(username, k -> new ArrayList<>()).add(schedule);
        saveToDisk();
        logger.info("[SCHEDULER] Created schedule " + schedule.id + " for " + username +
                " (" + schedule.frequency + " at " + schedule.timeOfDay + ")");
        return schedule;
    }

    public boolean deleteSchedule(String username, String scheduleId) {
        List<ScheduledReport> list = userSchedules.get(username);
        if (list != null) {
            boolean removed = list.removeIf(s -> s.id.equals(scheduleId));
            if (removed) {
                saveToDisk();
                logger.info("[SCHEDULER] Deleted schedule " + scheduleId + " for " + username);
            }
            return removed;
        }
        return false;
    }

    // =========================================================================
    // Scheduler loop — runs every 5 minutes
    // =========================================================================

    @Scheduled(fixedDelay = 300000)
    public void runDueSchedules() {
        LocalDateTime now = LocalDateTime.now();
        int ran = 0;

        for (Map.Entry<String, List<ScheduledReport>> entry : userSchedules.entrySet()) {
            for (ScheduledReport schedule : entry.getValue()) {
                if (!schedule.active) continue;
                if (!isDue(schedule, now)) continue;

                ran++;
                executeSchedule(schedule);
            }
        }

        if (ran > 0) {
            saveToDisk(); // persist updated lastRun timestamps
        }
    }

    boolean isDue(ScheduledReport schedule, LocalDateTime now) {
        // Parse scheduled time
        String[] parts = schedule.timeOfDay.split(":");
        int scheduledHour = Integer.parseInt(parts[0]);
        int scheduledMinute = Integer.parseInt(parts[1]);

        // Not yet reached the scheduled time today?
        LocalTime scheduledTime = LocalTime.of(scheduledHour, scheduledMinute);
        if (now.toLocalTime().isBefore(scheduledTime)) return false;

        // For weekly: check day of week
        if ("weekly".equals(schedule.frequency)) {
            DayOfWeek targetDay = DayOfWeek.valueOf(schedule.dayOfWeek.toUpperCase());
            if (now.getDayOfWeek() != targetDay) return false;
        }

        // Check if already ran today (daily) or this week (weekly) at/after the scheduled time
        if (schedule.lastRun != null) {
            LocalDateTime lastRunTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(schedule.lastRun), ZoneId.systemDefault());
            LocalDateTime todayScheduled = now.toLocalDate().atTime(scheduledTime);

            if ("daily".equals(schedule.frequency)) {
                // Already ran today at or after scheduled time
                if (!lastRunTime.isBefore(todayScheduled)) return false;
            } else {
                // Weekly: already ran this week on the target day
                if (!lastRunTime.isBefore(todayScheduled)) return false;
            }
        }

        return true;
    }

    private void executeSchedule(ScheduledReport schedule) {
        try {
            Map<String, String> p = schedule.searchParams;
            String field = p.getOrDefault("field", null);
            String item = p.getOrDefault("item", null);
            String user = p.getOrDefault("user", null);
            int days = Integer.parseInt(p.getOrDefault("days", "7"));
            String oldContains = p.getOrDefault("oldContains", null);
            String newContains = p.getOrDefault("newContains", null);
            boolean netFilter = Boolean.parseBoolean(p.getOrDefault("netFilter", "true"));

            // Treat empty strings as null (same as controller)
            if (field != null && field.isEmpty()) field = null;
            if (item != null && item.isEmpty()) item = null;
            if (user != null && user.isEmpty()) user = null;
            if (oldContains != null && oldContains.isEmpty()) oldContains = null;
            if (newContains != null && newContains.isEmpty()) newContains = null;

            ChangeQueryService.SearchResult result =
                    changeQueryService.search(field, item, user, days, oldContains, newContains, netFilter);

            List<ChangeRecord> records = result.getResults();

            if (records.isEmpty()) {
                logger.info("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                        ": 0 results, email skipped");
            } else {
                // Build filter summary for email body
                StringBuilder filters = new StringBuilder();
                if (field != null) filters.append("Field: ").append(field).append("; ");
                if (item != null) filters.append("Item: ").append(item).append("; ");
                if (oldContains != null) filters.append("Old: ").append(oldContains).append("; ");
                if (newContains != null) filters.append("New: ").append(newContains).append("; ");
                if (user != null) filters.append("By: ").append(user).append("; ");
                filters.append("Days: ").append(days);

                // Use the schedule's username as display name fallback
                String displayName = schedule.username;
                emailService.sendExcelReport(schedule.email, displayName, records, filters.toString());

                logger.info("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                        ": " + records.size() + " results emailed to " + schedule.email);
            }

            schedule.lastRun = System.currentTimeMillis();

        } catch (Exception e) {
            logger.warning("[SCHEDULER] Schedule " + schedule.id + " for " + schedule.username +
                    " failed: " + e.getMessage());
            // Update lastRun even on failure to prevent retry storm
            schedule.lastRun = System.currentTimeMillis();
        }
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, List<ScheduledReport>> data = mapper.readValue(f,
                    new TypeReference<Map<String, List<ScheduledReport>>>() {});
            userSchedules.putAll(data);
        } catch (Exception e) {
            logger.warning("[SCHEDULER] Failed to load schedules: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, userSchedules);
        } catch (Exception e) {
            logger.warning("[SCHEDULER] Failed to save schedules: " + e.getMessage());
        }
    }

    private int countAll() {
        int count = 0;
        for (List<ScheduledReport> list : userSchedules.values()) count += list.size();
        return count;
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/ScheduledReportService.java src/main/resources/application.properties
git commit -m "feat: add ScheduledReportService with file I/O and scheduler loop"
```

---

### Task 3: ScheduleController — REST Endpoints

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/ScheduleController.java`

- [ ] **Step 1: Create the controller**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.ScheduledReport;
import com.sandisk.plm.tracker.service.ScheduledReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    @Autowired
    private ScheduledReportService scheduledReportService;

    @GetMapping
    public List<ScheduledReport> getSchedules(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return new ArrayList<>();
        return scheduledReportService.getSchedules(username);
    }

    @PostMapping
    public Map<String, Object> createSchedule(@RequestBody ScheduledReport schedule, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        String email = (String) session.getAttribute("email");

        if (username == null) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }

        // Use session email if not provided
        if (schedule.email == null || schedule.email.isEmpty()) {
            schedule.email = email;
        }

        ScheduledReport created = scheduledReportService.createSchedule(username, schedule);
        response.put("success", true);
        response.put("schedule", created);
        return response;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable String id, HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = (String) session.getAttribute("username");
        if (username == null) {
            response.put("success", false);
            return response;
        }
        boolean deleted = scheduledReportService.deleteSchedule(username, id);
        response.put("success", deleted);
        return response;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/ScheduleController.java
git commit -m "feat: add ScheduleController with list/create/delete endpoints"
```

---

### Task 4: Frontend — Schedule Modal HTML

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Add clock icon button to the Field Changes toolbar**

In `index.html`, find the star button (line 88):
```html
            <button class="btn-clear" onclick="saveCurrentSearch('changes')" title="Save this search" style="font-size:14px;">&#9733;</button>
```

Add the clock button immediately after it:
```html
            <button class="btn-clear" onclick="saveCurrentSearch('changes')" title="Save this search" style="font-size:14px;">&#9733;</button>
            <button class="btn-clear" id="scheduleBtn" onclick="openScheduleModal()" title="Schedule this report" style="font-size:14px;">&#128339;</button>
```

- [ ] **Step 2: Add schedule modal HTML**

In `index.html`, find the custom modal div (line 488). Add the schedule modal immediately before it (before the `<!-- Custom Modal -->` comment on line 487):

```html
<!-- Schedule Modal -->
<div id="scheduleModal" class="modal-overlay" style="display:none;" onclick="if(event.target===this)closeScheduleModal()">
    <div class="modal-content" style="max-width:520px;">
        <div class="modal-header">
            <h2>Schedule Report</h2>
            <button onclick="closeScheduleModal()" class="modal-close">&times;</button>
        </div>
        <div class="modal-body">
            <div id="scheduleSearchSummary" style="background:#f0f4f8; padding:10px 14px; border-radius:6px; font-size:12px; color:#555; margin-bottom:16px;"></div>

            <div style="margin-bottom:12px;">
                <label style="font-size:13px; font-weight:600; color:#333;">Email</label>
                <input type="email" id="scheduleEmail" style="width:100%; height:36px; border:1px solid #d0d5dd; border-radius:6px; padding:0 10px; font-size:13px; margin-top:4px;">
            </div>

            <div style="margin-bottom:12px;">
                <label style="font-size:13px; font-weight:600; color:#333;">Frequency</label>
                <div style="display:flex; gap:16px; margin-top:6px;">
                    <label style="font-size:13px; cursor:pointer;"><input type="radio" name="scheduleFreq" value="daily" checked onchange="toggleDayOfWeek()"> Daily</label>
                    <label style="font-size:13px; cursor:pointer;"><input type="radio" name="scheduleFreq" value="weekly" onchange="toggleDayOfWeek()"> Weekly</label>
                </div>
            </div>

            <div id="scheduleDayRow" style="margin-bottom:12px; display:none;">
                <label style="font-size:13px; font-weight:600; color:#333;">Day of Week</label>
                <select id="scheduleDayOfWeek" style="width:100%; height:36px; border:1px solid #d0d5dd; border-radius:6px; padding:0 8px; font-size:13px; margin-top:4px;">
                    <option value="MONDAY">Monday</option>
                    <option value="TUESDAY">Tuesday</option>
                    <option value="WEDNESDAY">Wednesday</option>
                    <option value="THURSDAY">Thursday</option>
                    <option value="FRIDAY">Friday</option>
                    <option value="SATURDAY">Saturday</option>
                    <option value="SUNDAY">Sunday</option>
                </select>
            </div>

            <div style="margin-bottom:16px;">
                <label style="font-size:13px; font-weight:600; color:#333;">Time</label>
                <select id="scheduleTime" style="width:100%; height:36px; border:1px solid #d0d5dd; border-radius:6px; padding:0 8px; font-size:13px; margin-top:4px;">
                </select>
            </div>

            <div style="display:flex; justify-content:flex-end; gap:8px; margin-bottom:20px;">
                <button onclick="closeScheduleModal()" style="height:36px; padding:0 16px; background:#e9ecef; color:#555; border:none; border-radius:6px; font-size:13px; cursor:pointer;">Cancel</button>
                <button onclick="saveSchedule()" style="height:36px; padding:0 20px; background:#1a3a5c; color:white; border:none; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Save Schedule</button>
            </div>

            <div style="border-top:1px solid #e0e0e0; padding-top:16px;">
                <h3 style="font-size:13px; color:#555; margin:0 0 8px;">Your Scheduled Reports</h3>
                <div id="scheduleList" style="font-size:12px; color:#666;">Loading...</div>
            </div>
        </div>
    </div>
</div>
```

- [ ] **Step 3: Add schedule.js script tag**

In `index.html`, find the script tags at the bottom (line 505-508):
```html
<script src="app.js"></script>
<script src="bom.js"></script>
<script src="parts.js"></script>
<script src="agile.js"></script>
```

Add `schedule.js` after `app.js`:
```html
<script src="app.js"></script>
<script src="schedule.js"></script>
<script src="bom.js"></script>
<script src="parts.js"></script>
<script src="agile.js"></script>
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add schedule modal HTML and clock icon button"
```

---

### Task 5: Frontend — schedule.js

**Files:**
- Create: `src/main/resources/static/schedule.js`

- [ ] **Step 1: Create schedule.js with all modal logic**

```javascript
// === Scheduled Reports ===

// Populate the time dropdown with 30-minute intervals on load
(function() {
    var sel = document.getElementById('scheduleTime');
    if (!sel) return;
    for (var h = 0; h < 24; h++) {
        for (var m = 0; m < 60; m += 30) {
            var val = (h < 10 ? '0' + h : h) + ':' + (m === 0 ? '00' : '30');
            var hour12 = h === 0 ? 12 : (h > 12 ? h - 12 : h);
            var ampm = h < 12 ? 'AM' : 'PM';
            var label = hour12 + ':' + (m === 0 ? '00' : '30') + ' ' + ampm;
            var opt = document.createElement('option');
            opt.value = val;
            opt.textContent = label;
            if (val === '07:00') opt.selected = true;
            sel.appendChild(opt);
        }
    }
})();

function toggleDayOfWeek() {
    var freq = document.querySelector('input[name="scheduleFreq"]:checked').value;
    document.getElementById('scheduleDayRow').style.display = freq === 'weekly' ? 'block' : 'none';
}

function openScheduleModal() {
    // Build search summary from current form
    var parts = [];
    var field = document.getElementById('fieldInput').value.trim();
    var item = document.getElementById('itemInput').value.trim();
    var oldVal = document.getElementById('oldInput').value.trim();
    var newVal = document.getElementById('newInput').value.trim();
    var user = document.getElementById('userInput').value.trim();
    var days = document.getElementById('daysSelect').value;
    if (field) parts.push('Field: ' + field);
    if (item) parts.push('Item: ' + item);
    if (oldVal) parts.push('Old Value: ' + oldVal);
    if (newVal) parts.push('New Value: ' + newVal);
    if (user) parts.push('Changed By: ' + user);
    parts.push('Days Back: ' + days);
    var netFilter = document.getElementById('netFilterToggle').checked;
    if (netFilter) parts.push('Net-change filter: ON');

    document.getElementById('scheduleSearchSummary').textContent = parts.join(' | ');
    document.getElementById('scheduleModal').style.display = 'flex';

    // Pre-fill email from the page (userGreeting has display name, email comes from session)
    // We'll fetch schedules which also confirms session
    loadScheduleList();
}

function closeScheduleModal() {
    document.getElementById('scheduleModal').style.display = 'none';
}

function saveSchedule() {
    var email = document.getElementById('scheduleEmail').value.trim();
    if (!email) {
        showCustomAlert('PLM Toolkit', 'Please enter an email address.');
        return;
    }

    var freq = document.querySelector('input[name="scheduleFreq"]:checked').value;
    var dayOfWeek = freq === 'weekly' ? document.getElementById('scheduleDayOfWeek').value : null;
    var timeOfDay = document.getElementById('scheduleTime').value;

    var searchParams = {
        field: document.getElementById('fieldInput').value.trim(),
        item: document.getElementById('itemInput').value.trim(),
        oldContains: document.getElementById('oldInput').value.trim(),
        newContains: document.getElementById('newInput').value.trim(),
        user: document.getElementById('userInput').value.trim(),
        days: document.getElementById('daysSelect').value,
        netFilter: String(document.getElementById('netFilterToggle').checked)
    };

    fetch('/api/schedules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            email: email,
            searchParams: searchParams,
            frequency: freq,
            dayOfWeek: dayOfWeek,
            timeOfDay: timeOfDay
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.success) {
            showCustomAlert('PLM Toolkit', 'Report scheduled successfully!');
            loadScheduleList();
        } else {
            showCustomAlert('PLM Toolkit', data.message || 'Failed to save schedule.');
        }
    })
    .catch(function(err) {
        showCustomAlert('PLM Toolkit', 'Failed: ' + err.message);
    });
}

function loadScheduleList() {
    fetch('/api/schedules')
        .then(function(res) { return res.json(); })
        .then(function(schedules) {
            var container = document.getElementById('scheduleList');
            if (!schedules || schedules.length === 0) {
                container.innerHTML = '<div style="color:#999; padding:8px 0;">No scheduled reports yet.</div>';
                return;
            }

            // Pre-fill email from first schedule if email field is empty
            var emailInput = document.getElementById('scheduleEmail');
            if (!emailInput.value && schedules.length > 0) {
                emailInput.value = schedules[0].email || '';
            }

            var html = '<table style="width:100%; border-collapse:collapse;">';
            html += '<tr style="border-bottom:1px solid #e0e0e0;">' +
                '<th style="text-align:left; padding:4px 6px; font-size:11px; color:#888;">Search</th>' +
                '<th style="text-align:left; padding:4px 6px; font-size:11px; color:#888;">Frequency</th>' +
                '<th style="text-align:left; padding:4px 6px; font-size:11px; color:#888;">Time</th>' +
                '<th style="padding:4px 6px;"></th>' +
                '</tr>';

            schedules.forEach(function(s) {
                var summary = buildScheduleSummary(s.searchParams);
                var freqLabel = s.frequency === 'weekly' ? 'Weekly (' + capitalize(s.dayOfWeek) + ')' : 'Daily';
                var timeLabel = formatTime12(s.timeOfDay);
                html += '<tr style="border-bottom:1px solid #f0f0f0;">' +
                    '<td style="padding:6px; font-size:11px; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + esc(summary) + '">' + esc(summary) + '</td>' +
                    '<td style="padding:6px; font-size:11px;">' + freqLabel + '</td>' +
                    '<td style="padding:6px; font-size:11px;">' + timeLabel + '</td>' +
                    '<td style="padding:6px; text-align:center;">' +
                    '<span style="color:#ccc; cursor:pointer; font-size:11px;" ' +
                    'onmouseover="this.style.color=\'#dc3545\'" onmouseout="this.style.color=\'#ccc\'" ' +
                    'onclick="deleteSchedule(\'' + s.id + '\')" title="Delete">&#10005;</span></td>' +
                    '</tr>';
            });
            html += '</table>';
            container.innerHTML = html;
        })
        .catch(function() {
            document.getElementById('scheduleList').innerHTML =
                '<div style="color:#999; padding:8px 0;">Could not load schedules.</div>';
        });
}

function deleteSchedule(id) {
    fetch('/api/schedules/' + id, { method: 'DELETE' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) loadScheduleList();
        });
}

function buildScheduleSummary(params) {
    if (!params) return '(all changes)';
    var parts = [];
    if (params.field) parts.push('Field: ' + params.field);
    if (params.item) parts.push('Item: ' + params.item);
    if (params.user) parts.push('By: ' + params.user);
    parts.push(params.days + 'd');
    return parts.join(', ') || '(all changes)';
}

function formatTime12(time24) {
    if (!time24) return '';
    var parts = time24.split(':');
    var h = parseInt(parts[0], 10);
    var m = parts[1];
    var ampm = h < 12 ? 'AM' : 'PM';
    var h12 = h === 0 ? 12 : (h > 12 ? h - 12 : h);
    return h12 + ':' + m + ' ' + ampm;
}

function capitalize(str) {
    if (!str) return '';
    return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
}
```

- [ ] **Step 2: Verify the app loads (manual)**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn spring-boot:run` then open browser to `http://localhost:8080`. The clock icon should appear next to the star. Clicking it should open the schedule modal.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/schedule.js
git commit -m "feat: add schedule.js with modal UI and CRUD logic"
```

---

### Task 6: Verify End-to-End Flow

- [ ] **Step 1: Build the full JAR**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn clean package -DskipTests 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 2: Manual smoke test checklist**

Start the app and verify:
1. Clock icon appears next to star on Field Changes tab
2. Clicking clock opens the Schedule Report modal
3. Modal shows current search params in summary
4. Frequency toggle shows/hides Day of Week
5. Time dropdown has 30-minute intervals from 12:00 AM to 11:30 PM
6. Saving a schedule succeeds (check `./data/scheduled-reports.json` file appears)
7. Schedule list shows in the modal
8. Deleting a schedule removes it from the list and JSON file
9. Server logs show `[SCHEDULER]` entries every 5 minutes

- [ ] **Step 3: Commit all files together**

```bash
git add -A
git commit -m "feat: scheduled field change reports — complete implementation"
```
