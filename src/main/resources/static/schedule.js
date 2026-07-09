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
    loadScheduleList();
}

function closeScheduleModal() {
    document.getElementById('scheduleModal').style.display = 'none';
}

function saveSchedule() {
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
            searchParams: searchParams,
            frequency: freq,
            dayOfWeek: dayOfWeek,
            timeOfDay: timeOfDay
        })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.success) {
            closeScheduleModal();
            showCustomAlert('PLM Toolkit', 'Report scheduled successfully!');
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
