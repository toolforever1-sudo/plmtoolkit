// Single/Sole Source Report tab — UI controller.
// Endpoints: /api/single-sole-source/{status,run,download/latest,send-test}

function ssEsc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function(c) {
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
}

async function ssRefreshStatus() {
    var el = document.getElementById('ssStatus');
    if (!el) return;
    el.textContent = 'Loading status…';
    try {
        var r = await fetch('/api/single-sole-source/status');
        if (!r.ok) {
            el.textContent = 'Failed to load status: HTTP ' + r.status;
            return;
        }
        var j = await r.json();
        var last = j.lastRun;
        if (!last) {
            el.innerHTML = '<em>No runs yet. Click <strong>Run Now</strong> to generate the first report.</em>';
            return;
        }
        var counts = 'Designation Needed <strong>' + last.designationNeededCount + '</strong> · ' +
                     'Single Source <strong>' + last.singleSourceCount + '</strong> · ' +
                     'Sole Source <strong>' + last.soleSourceCount + '</strong>';
        var totalRows = last.designationNeededCount + last.singleSourceCount + last.soleSourceCount;
        var sizeKb = last.xlsxSizeBytes ? (last.xlsxSizeBytes / 1024).toFixed(0) + ' KB' : '?';
        var html = '';
        html += '<div><strong>Last run:</strong> ' + ssEsc(last.runId) + ' (' + ssEsc(last.trigger || '?') + ', ' + last.durationMs + ' ms)</div>';
        html += '<div><strong>Row counts:</strong> ' + counts + ' &nbsp;<span style="color:#6B7280;">(' + totalRows + ' total)</span></div>';
        if (last.xlsxPath) {
            html += '<div><strong>Output file:</strong> <code>' + ssEsc(last.xlsxPath) + '</code> (' + sizeKb + ')</div>';
        }
        if (last.sharepointUploaded === true) {
            html += '<div style="color:#1F8A4C;"><strong>SharePoint:</strong> uploaded ✓ ';
            if (last.sharepointUrl) html += '<a href="' + ssEsc(last.sharepointUrl) + '" target="_blank" rel="noopener">view file</a>';
            html += '</div>';
        } else if (last.sharepointError) {
            html += '<div style="color:#B8342B;"><strong>SharePoint error:</strong> ' + ssEsc(last.sharepointError) + '</div>';
        }
        if (last.emailSent === true) {
            html += '<div style="color:#1F8A4C;"><strong>Email sent</strong> ✓</div>';
        } else if (last.emailError) {
            html += '<div style="color:#B8342B;"><strong>Email error:</strong> ' + ssEsc(last.emailError) + '</div>';
        }
        if (last.error) {
            html += '<div style="color:#B8342B;"><strong>Run error:</strong> ' + ssEsc(last.error) + '</div>';
        }
        el.innerHTML = html;
    } catch (e) {
        el.textContent = 'Status fetch failed: ' + e.message;
    }
}

async function ssRun() {
    var btn = document.getElementById('ssRunBtn');
    var orig = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Running…';
    try {
        var upload = document.getElementById('ssOptUpload').checked;
        var email  = document.getElementById('ssOptEmail').checked;
        var r = await fetch('/api/single-sole-source/run', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({uploadSharePoint: upload, sendEmail: email})
        });
        if (!r.ok) {
            appAlert('Run failed: HTTP ' + r.status);
        } else {
            var j = await r.json();
            if (j.error) {
                appAlert('Run failed: ' + j.error);
            } else {
                var msg = 'Done in ' + j.durationMs + ' ms\n' +
                          'Designation Needed: ' + j.designationNeededCount + '\n' +
                          'Single Source: ' + j.singleSourceCount + '\n' +
                          'Sole Source: ' + j.soleSourceCount;
                if (j.sharepointError) msg += '\nSharePoint error: ' + j.sharepointError;
                if (j.emailError)      msg += '\nEmail error: ' + j.emailError;
                appAlert(msg);
            }
        }
        await ssRefreshStatus();
    } catch (e) {
        appAlert('Run failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = orig;
    }
}

function ssDownload() {
    window.location = '/api/single-sole-source/download/latest';
}

async function ssSendTest() {
    var btn = document.getElementById('ssSendTestBtn');
    btn.disabled = true;
    var orig = btn.textContent;
    btn.textContent = 'Sending…';
    try {
        var r = await fetch('/api/single-sole-source/send-test', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: '{}'
        });
        var j = await r.json();
        appAlert(j.ok ? ('Email sent to ' + j.sentTo) : ('Failed: ' + (j.error || 'unknown')));
    } catch (e) {
        appAlert('Failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = orig;
    }
}
