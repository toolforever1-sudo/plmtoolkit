// AI Eval tab controller. Admin-only.

(function () {
    var resultsByIdx = {};   // qIndex -> result object (live updates)
    var simulatedPersonState = null;  // { username, displayName, ...mapped/real fields } when Simulate Person is active
    var currentRunId = null;          // tracks the most recent run for live-card override edits

    // Inject spinner keyframes once.
    if (!document.getElementById('aieSpinStyle')) {
        var s = document.createElement('style');
        s.id = 'aieSpinStyle';
        s.textContent = '@keyframes aieSpin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }';
        document.head.appendChild(s);
    }

    function el(id) { return document.getElementById(id); }
    function gradeColor(g) {
        if (g === 'A' || g === 'B') return '#1F8A4C';
        if (g === 'C') return '#C7801B';
        return '#B8342B'; // D, F, ERR
    }
    function gradePill(g) {
        return '<span style="display:inline-block; min-width:28px; padding:2px 8px; border-radius:10px; '
             + 'background:' + gradeColor(g) + '; color:#fff; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px; font-weight:600;">'
             + (g || '?') + '</span>';
    }
    function escHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
    }
    function trunc(s, n) { s = s || ''; return s.length <= n ? s : s.substring(0, n) + '\u2026'; }

    // Floating toast for any user-facing feedback (export confirmation, errors).
    // Lives at top-right of the viewport so it's visible regardless of scroll position.
    function showToast(message, kind) {
        var container = document.getElementById('aieToastContainer');
        if (!container) {
            container = document.createElement('div');
            container.id = 'aieToastContainer';
            container.style.cssText = 'position:fixed; top:80px; right:24px; z-index:9999; display:flex; flex-direction:column; gap:8px; max-width:380px;';
            document.body.appendChild(container);
        }
        var bg = kind === 'error' ? '#fdeaea' : (kind === 'warn' ? '#fff8e1' : '#e8f5e9');
        var border = kind === 'error' ? '#B8342B' : (kind === 'warn' ? '#C7801B' : '#1F8A4C');
        var toast = document.createElement('div');
        toast.style.cssText = 'background:' + bg + '; border-left:4px solid ' + border + '; border-radius:0 6px 6px 0; padding:12px 14px; box-shadow:0 4px 12px rgba(0,0,0,0.12); font-size:13px; color:#0F1720; line-height:1.4; opacity:0; transform:translateX(20px); transition:opacity .2s ease, transform .2s ease;';
        toast.innerHTML = message;
        container.appendChild(toast);
        // Animate in
        setTimeout(function () { toast.style.opacity = '1'; toast.style.transform = 'translateX(0)'; }, 10);
        // Auto-dismiss
        setTimeout(function () {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(20px)';
            setTimeout(function () { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 250);
        }, 6000);
    }
    function fmtDate(iso) {
        if (!iso) return '';
        var d = new Date(iso);
        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    }
    function personaCompact(p) {
        if (!p) return '';
        // V2: when a real person was simulated, show their name + the bucket they mapped to.
        if (p.simulatedDisplayName) {
            return '\ud83e\uddd1 <strong>' + escHtml(p.simulatedDisplayName) + '</strong> '
                 + '<span style="color:#6B7280; font-size:11px;">(' + escHtml(p.role) + ' \u00b7 ' + escHtml(p.team) + ')</span>';
        }
        return escHtml(p.role) + ' \u00b7 ' + escHtml(p.team) + ' \u00b7 ' + escHtml(p.experience);
    }
    function modelShort(m) {
        if (!m) return '';
        var slash = m.lastIndexOf('/');
        return slash >= 0 ? m.substring(slash + 1) : m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Model picker: catalog + Custom… + validation
    // ─────────────────────────────────────────────────────────────────────
    var MODEL_CATALOG = null;     // {groups:[{label,options:[{slug,label}]}], placeholder}
    var CUSTOM_VALUE = '__custom__';
    var DEFAULT_VALUE = '__default__'; // chatbot-only: use prod default
    // Tracks the last validated custom slug per picker; cleared when text changes.
    var validatedCustom = { aieChatbotCustom: null, aieTesterCustom: null, aieEvaluatorCustom: null };

    function fillSelectFromCatalog(selectId, opts) {
        opts = opts || {};
        var sel = el(selectId);
        if (!sel) return;
        sel.innerHTML = '';
        if (opts.includeDefault) {
            // Chatbot picker: first entry = "Production default"
            var def = document.createElement('option');
            def.value = DEFAULT_VALUE;
            def.textContent = 'Production default (Claude Sonnet 4.6)';
            sel.appendChild(def);
        }
        (MODEL_CATALOG.groups || []).forEach(function (g) {
            var og = document.createElement('optgroup');
            og.label = g.label;
            (g.options || []).forEach(function (o) {
                var opt = document.createElement('option');
                opt.value = o.slug;
                opt.textContent = o.label;
                og.appendChild(opt);
            });
            sel.appendChild(og);
        });
        var custom = document.createElement('option');
        custom.value = CUSTOM_VALUE;
        custom.textContent = 'Custom\u2026';
        sel.appendChild(custom);
        if (opts.preferred) {
            var found = false;
            for (var i = 0; i < sel.options.length; i++) {
                if (sel.options[i].value === opts.preferred) { found = true; break; }
            }
            if (found) sel.value = opts.preferred;
        }
    }

    /** Resolve a select's current effective slug (handles Default and Custom). */
    function resolveModelValue(selectId, customInputId) {
        var sel = el(selectId);
        if (!sel) return null;
        var v = sel.value;
        if (v === DEFAULT_VALUE) return null;          // null = use prod default (chatbot only)
        if (v === CUSTOM_VALUE) {
            var raw = el(customInputId).value.trim();
            return raw || null;
        }
        return v;
    }

    function setCustomRowVisibility(selectId) {
        var sel = el(selectId);
        var rowMap = {
            'aieChatbotModel': 'aieChatbotCustomRow',
            'aieTesterModel':  'aieTesterCustomRow',
            'aieEvaluatorModel':'aieEvaluatorCustomRow'
        };
        var rowId = rowMap[selectId];
        if (!rowId) return;
        el(rowId).style.display = (sel.value === CUSTOM_VALUE) ? '' : 'none';
    }

    /** Disable any catalog option in the Tester/Evaluator pickers that's already used elsewhere. */
    function syncModelDropdowns() {
        var chatbot = resolveModelValue('aieChatbotModel', 'aieChatbotCustom');
        var tester  = resolveModelValue('aieTesterModel',  'aieTesterCustom');
        var evlu    = resolveModelValue('aieEvaluatorModel','aieEvaluatorCustom');

        function disableMatches(selectId, used) {
            var sel = el(selectId);
            Array.prototype.forEach.call(sel.options, function (opt) {
                if (opt.value === CUSTOM_VALUE || opt.value === DEFAULT_VALUE) return;
                opt.disabled = (used.indexOf(opt.value) >= 0);
            });
        }
        // For each picker, every other picker's effective slug is "used" and should be disabled here.
        var others;
        others = [tester, evlu].filter(function (x) { return x; });
        disableMatches('aieChatbotModel', others);
        others = [chatbot, evlu].filter(function (x) { return x; });
        disableMatches('aieTesterModel', others);
        others = [chatbot, tester].filter(function (x) { return x; });
        disableMatches('aieEvaluatorModel', others);
    }

    function loadModelCatalog() {
        return fetch('/api/ai-eval/models', { credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (cat) {
                MODEL_CATALOG = cat;
                fillSelectFromCatalog('aieChatbotModel', { includeDefault: true,  preferred: DEFAULT_VALUE });
                fillSelectFromCatalog('aieTesterModel',  { includeDefault: false, preferred: '@anthropic-eastus2/claude-sonnet-4-6' });
                fillSelectFromCatalog('aieEvaluatorModel', { includeDefault: false, preferred: '@openai-eastus2/gpt-4o' });
                ['aieChatbotModel','aieTesterModel','aieEvaluatorModel'].forEach(setCustomRowVisibility);
                syncModelDropdowns();
            })
            .catch(function (e) {
                showToast('<strong>Could not load model catalog:</strong> ' + escHtml(e.message), 'error');
            });
    }

    /**
     * "Validate" button handler shared by all three custom-entry rows. Pings the slug
     * via /api/ai-eval/models/validate and renders pass/fail next to the input.
     * Caches the last validated text so the run button can require a green check.
     */
    window.aieValidateCustom = function (inputId, statusId) {
        var raw = el(inputId).value.trim();
        var status = el(statusId);
        if (!raw) {
            status.innerHTML = '<span style="color:#B8342B;">Enter a slug first.</span>';
            return;
        }
        validatedCustom[inputId] = null;   // reset until ping returns
        status.innerHTML = '<span style="color:#6B7280;">Validating\u2026</span>';
        fetch('/api/ai-eval/models/validate', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: raw })
        })
            .then(function (r) { return r.json().then(function (j) { return { http: r.status, body: j }; }); })
            .then(function (resp) {
                if (resp.body.ok) {
                    validatedCustom[inputId] = raw;
                    status.innerHTML = '<span style="color:#1F8A4C;">\u2713 reachable \u00b7 ' + resp.body.latencyMs + 'ms</span>';
                } else {
                    status.innerHTML = '<span style="color:#B8342B;">\u2717 ' + escHtml(resp.body.error || ('HTTP ' + resp.http)) + '</span>';
                }
            })
            .catch(function (e) {
                status.innerHTML = '<span style="color:#B8342B;">\u2717 ' + escHtml(e.message || 'network error') + '</span>';
            });
    };

    /** True when every Custom-selected picker has a freshly-validated slug. */
    function customsAreValidated() {
        var pairs = [
            ['aieChatbotModel',  'aieChatbotCustom'],
            ['aieTesterModel',   'aieTesterCustom'],
            ['aieEvaluatorModel','aieEvaluatorCustom']
        ];
        for (var i = 0; i < pairs.length; i++) {
            var sel = el(pairs[i][0]);
            if (sel && sel.value === CUSTOM_VALUE) {
                var raw = el(pairs[i][1]).value.trim();
                if (!raw) return { ok: false, why: 'A Custom slug is empty.' };
                if (validatedCustom[pairs[i][1]] !== raw) {
                    return { ok: false, why: 'Click Validate on the Custom slug first.' };
                }
            }
        }
        return { ok: true };
    }

    /**
     * Restore a model value to a picker. If the slug is in the catalog, just set the
     * select. Otherwise, switch to "Custom..." and pre-fill the input — and pre-mark
     * it validated, on the assumption that a slug that produced grades on a past run
     * is reachable. (If it's since been broken, the actual run will surface the error
     * with a clearer message than a forced revalidation step.)
     */
    function applyModelToPicker(selectId, customInputId, statusId, slug) {
        var sel = el(selectId);
        if (!sel) return;
        if (slug === DEFAULT_VALUE) {
            sel.value = DEFAULT_VALUE;
            setCustomRowVisibility(selectId);
            return;
        }
        var inCatalog = false;
        for (var i = 0; i < sel.options.length; i++) {
            if (sel.options[i].value === slug) { inCatalog = true; break; }
        }
        if (inCatalog) {
            sel.value = slug;
        } else {
            sel.value = CUSTOM_VALUE;
            el(customInputId).value = slug;
            validatedCustom[customInputId] = slug;
            if (el(statusId)) el(statusId).innerHTML = '<span style="color:#9CA3AF;">previously used \u2014 click Validate to confirm</span>';
        }
        setCustomRowVisibility(selectId);
    }

    function bindForm() {
        ['aieChatbotModel','aieTesterModel','aieEvaluatorModel'].forEach(function (id) {
            el(id).addEventListener('change', function () {
                setCustomRowVisibility(id);
                syncModelDropdowns();
            });
        });
        ['aieChatbotCustom','aieTesterCustom','aieEvaluatorCustom'].forEach(function (id) {
            el(id).addEventListener('input', function () {
                validatedCustom[id] = null;
                var statusId = id.replace('Custom', 'Status');
                if (el(statusId)) el(statusId).innerHTML = '';
            });
        });
        el('aieTeam').addEventListener('change', function () {
            var sel = el('aieTeam').value;
            el('aieTeamOther').style.display = (sel === '__other') ? '' : 'none';
        });
        el('aieRunBtn').addEventListener('click', startRun);
        loadModelCatalog();
        bindSimulatePersonForm();
    }

    // ─────────────────────────────────────────────────────────────────────
    // V2: Simulate Person picker
    // ─────────────────────────────────────────────────────────────────────
    var userListLoaded = { past: false, all: false };
    var userListByName = {};   // displayName "(username)" -> {username, displayName}

    function bindSimulatePersonForm() {
        var checkbox = el('aieSimulatePerson');
        var body = el('aieSimulatePersonBody');
        checkbox.addEventListener('change', function () {
            body.style.display = checkbox.checked ? '' : 'none';
            if (checkbox.checked) {
                loadUserList(false);
            } else {
                simulatedPersonState = null;
                el('aieInferenceCard').style.display = 'none';
                el('aieUserSearch').value = '';
            }
        });
        el('aieSearchAllAd').addEventListener('change', function () {
            loadUserList(el('aieSearchAllAd').checked);
        });
        el('aieUserSearch').addEventListener('change', function () {
            handleUserPick(el('aieUserSearch').value);
        });
        el('aieUserSearch').addEventListener('blur', function () {
            handleUserPick(el('aieUserSearch').value);
        });
    }

    function loadUserList(searchAll) {
        var url = searchAll ? '/api/ai-eval/users/all' : '/api/ai-eval/users';
        var key = searchAll ? 'all' : 'past';
        if (userListLoaded[key]) {
            // Re-populate datalist from cached users (we keep userListByName fresh per-load)
            return;
        }
        el('aieUserSearch').placeholder = 'Loading users\u2026';
        fetch(url, { credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (users) {
                userListLoaded[key] = true;
                userListByName = {};
                var dl = el('aieUserList');
                dl.innerHTML = '';
                users.forEach(function (u) {
                    var label = u.displayName + ' (' + u.username + ')';
                    userListByName[label.toLowerCase()] = u;
                    userListByName[u.username.toLowerCase()] = u;
                    var opt = document.createElement('option');
                    opt.value = label;
                    dl.appendChild(opt);
                });
                el('aieUserSearch').placeholder = users.length + ' users \u2014 type to filter\u2026';
            })
            .catch(function (e) {
                el('aieUserSearch').placeholder = 'Failed to load users';
                showToast('<strong>User list failed:</strong> ' + escHtml(e.message), 'error');
            });
    }

    function handleUserPick(value) {
        if (!value) return;
        var key = value.toLowerCase();
        var picked = userListByName[key];
        if (!picked) {
            // Try suffix match: "Display Name (username)"
            var m = value.match(/\(([^)]+)\)\s*$/);
            if (m) picked = userListByName[m[1].toLowerCase()];
        }
        if (!picked) return;
        loadAndApplyPersona(picked.username);
    }

    function loadAndApplyPersona(username) {
        el('aieInferenceCard').style.display = '';
        el('aieInferenceCard').innerHTML = '<em style="color:#6B7280;">Loading inference for ' + escHtml(username) + '\u2026</em>';
        fetch('/api/ai-eval/users/' + encodeURIComponent(username) + '/persona', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (p) {
                simulatedPersonState = p;
                renderInferenceCard(p);
                applyInferenceToForm(p);
            })
            .catch(function (e) {
                el('aieInferenceCard').innerHTML = '<span style="color:#B8342B;">Inference failed: ' + escHtml(e.message) + '</span>';
            });
    }

    function renderInferenceCard(p) {
        var c = el('aieInferenceCard');
        var rows = [];
        if (p.title)         rows.push('<strong>Title:</strong> ' + escHtml(p.title));
        if (p.department)    rows.push('<strong>Dept:</strong> ' + escHtml(p.department));
        rows.push('<strong>Account age:</strong> ' + escHtml(p.accountAge || 'unknown'));
        rows.push('<strong>Logins (90d):</strong> ' + (p.loginCount90d == null ? '?' : p.loginCount90d)
                + ' &middot; <strong>Total:</strong> ' + (p.totalLoginCount == null ? '?' : p.totalLoginCount));
        if (p.isPlmAdmin) rows.push('<strong style="color:#1F8A4C;">PLM Admin: yes</strong>');
        c.innerHTML = '<div style="margin-bottom:8px; color:#0F1720;"><strong>' + escHtml(p.displayName || p.username) + '</strong></div>'
                    + '<div style="margin-bottom:8px; color:#6B7280; line-height:1.6;">' + rows.join(' &middot; ') + '</div>'
                    + '<div style="padding:8px 10px; background:#FAFAF7; border-radius:4px; color:#0F1720; font-size:12px;">'
                    +   '\u2192 Auto-filled: <strong>' + escHtml(p.mappedRole || '?') + '</strong> &middot; '
                    +   '<strong>' + escHtml(p.mappedTeam || '?') + '</strong> &middot; '
                    +   '<strong>' + escHtml(p.mappedExperience || '?') + '</strong>'
                    +   '<br><span style="color:#6B7280; font-size:11px;">You can still tweak the dropdowns above before clicking Run.</span>'
                    + '</div>';
    }

    function applyInferenceToForm(p) {
        if (p.mappedRole && el('aieRole')) {
            // Match value or option text
            var rs = el('aieRole'); var found = false;
            for (var i = 0; i < rs.options.length; i++) {
                if (rs.options[i].text === p.mappedRole) { rs.selectedIndex = i; found = true; break; }
            }
            if (!found && rs.options.length > 0) rs.value = rs.options[0].value;
        }
        if (p.mappedTeam && el('aieTeam')) {
            var ts = el('aieTeam'); var matched = false;
            for (var j = 0; j < ts.options.length; j++) {
                if (ts.options[j].text === p.mappedTeam) { ts.selectedIndex = j; matched = true; break; }
            }
            if (!matched) {
                ts.value = '__other';
                el('aieTeamOther').style.display = '';
                el('aieTeamOther').value = p.mappedTeam || (p.department || '');
            } else {
                el('aieTeamOther').style.display = 'none';
            }
        }
        if (p.mappedExperience) {
            Array.prototype.forEach.call(document.getElementsByName('aieExp'), function (r) {
                r.checked = (r.value === p.mappedExperience);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // V2: Override grade
    // ─────────────────────────────────────────────────────────────────────
    window.aieOpenOverride = function (runId, qIdx, source) {
        // Fetch current full result so we have the most up-to-date reason + override state
        fetch('/api/ai-eval/runs/' + runId + '/results', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var current = (data.results || []).filter(function (r) { return r.qIndex === qIdx; })[0];
                if (!current) { showToast('Question not found.', 'error'); return; }
                showOverridePopover(runId, qIdx, current, source);
            });
    };

    function showOverridePopover(runId, qIdx, current, source) {
        // Modal-style popover overlay.
        var overlay = document.createElement('div');
        overlay.id = 'aieOverrideOverlay';
        overlay.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:10000; display:flex; align-items:center; justify-content:center; padding:20px;';
        overlay.addEventListener('click', function (e) { if (e.target === overlay) overlay.remove(); });

        var grades = ['A','B','C','D','F'];
        var gradeOpts = grades.map(function (g) {
            return '<option value="' + g + '"' + (g === current.grade ? ' selected' : '') + '>' + g + '</option>';
        }).join('');

        var aiInfo = '';
        if (current.aiGrade) {
            aiInfo = '<div style="background:#FAFAF7; border-left:4px solid #7C3AED; padding:8px 12px; border-radius:0 4px 4px 0; margin-bottom:12px; font-size:12px; color:#6B7280;">'
                   + 'AI originally graded: <strong style="color:#0F1720;">' + escHtml(current.aiGrade) + '</strong>'
                   + (current.aiReason ? ' \u2014 ' + escHtml(current.aiReason) : '')
                   + '</div>';
        }

        var modal = document.createElement('div');
        modal.style.cssText = 'background:#fff; border-radius:8px; max-width:560px; width:100%; max-height:85vh; overflow:auto; padding:24px; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';
        modal.innerHTML =
            '<h3 style="margin:0 0 12px; font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; color:#0F1720;">Override grade for Q' + qIdx + '</h3>'
          + '<div style="font-size:13px; color:#6B7280; margin-bottom:14px; padding:10px 12px; background:#FAFAF7; border-radius:4px;"><strong>Question:</strong> ' + escHtml(current.question || '') + '</div>'
          + aiInfo
          + '<label style="display:block; font-size:12px; color:#6B7280; margin-bottom:6px;">Your grade</label>'
          + '<select id="aieOvGrade" style="width:120px; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; margin-bottom:12px;">' + gradeOpts + '</select>'
          + '<label style="display:block; font-size:12px; color:#6B7280; margin-bottom:6px;">Reason (replaces AI reason)</label>'
          + '<textarea id="aieOvReason" rows="3" style="width:100%; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-family:inherit; font-size:13px; resize:vertical; margin-bottom:12px;">' + escHtml(current.reason || '') + '</textarea>'
          + '<label style="display:block; font-size:12px; color:#6B7280; margin-bottom:6px;">Note (optional, why are you changing this?)</label>'
          + '<textarea id="aieOvNote" rows="2" style="width:100%; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px; font-family:inherit; font-size:13px; resize:vertical; margin-bottom:16px;">' + escHtml(current.overrideNote || '') + '</textarea>'
          + '<div style="display:flex; justify-content:flex-end; gap:8px;">'
          +   '<button id="aieOvCancel" style="padding:8px 16px; background:#fff; border:1px solid #E8E6DF; border-radius:4px; color:#6B7280; cursor:pointer;">Cancel</button>'
          +   '<button id="aieOvSave" style="padding:8px 16px; background:#4a6fa5; border:none; border-radius:4px; color:#fff; font-weight:600; cursor:pointer;">Save override</button>'
          + '</div>';

        overlay.appendChild(modal);
        document.body.appendChild(overlay);

        document.getElementById('aieOvCancel').addEventListener('click', function () { overlay.remove(); });
        document.getElementById('aieOvSave').addEventListener('click', function () {
            var newGrade = document.getElementById('aieOvGrade').value;
            var newReason = document.getElementById('aieOvReason').value;
            var note = document.getElementById('aieOvNote').value;
            submitOverride(runId, qIdx, newGrade, newReason, note, current, source, overlay);
        });
    }

    function submitOverride(runId, qIdx, grade, reason, note, current, source, overlay) {
        fetch('/api/ai-eval/runs/' + runId + '/results/' + qIdx, {
            method: 'PATCH',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ grade: grade, reason: reason, note: note })
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (resp) {
            if (!resp.ok) {
                showToast('<strong>Override failed:</strong> ' + escHtml(resp.body.error || 'unknown'), 'error');
                return;
            }
            overlay.remove();
            var updated = resp.body.result;
            // Update live card if we're showing this run.
            if (source === 'live' && currentRunId === runId) {
                renderCard(qIdx, updated.question, updated.answer, updated.grade, updated.reason, updated);
                showSummary(resp.body.summary);
            }
            // Refresh past-runs (Δ + summary may have changed).
            loadPastRuns();
            // Also refresh expanded sub-table if open.
            var expand = document.getElementById('aieExpand-' + runId);
            if (expand && expand.style.display !== 'none') {
                renderExpandedRowResults(runId, expand);
            }
            showToast('<strong>Saved.</strong> Q' + qIdx + ' is now <strong>' + escHtml(grade) + '</strong>.', 'info');
        }).catch(function (e) {
            showToast('<strong>Override failed:</strong> ' + escHtml(e.message || 'network error'), 'error');
        });
    }

    function readPersonaConfig() {
        var teamSel = el('aieTeam').value;
        var team = teamSel === '__other' ? (el('aieTeamOther').value || '').trim() : teamSel;
        var exp = '';
        var radios = document.getElementsByName('aieExp');
        for (var i = 0; i < radios.length; i++) if (radios[i].checked) { exp = radios[i].value; break; }
        return {
            persona: {
                role: el('aieRole').value,
                team: team,
                experience: exp,
                goal: (el('aieGoal').value || '').trim()
            },
            chatbotModel: resolveModelValue('aieChatbotModel', 'aieChatbotCustom'),   // null = production default
            testerModel: resolveModelValue('aieTesterModel', 'aieTesterCustom'),
            evaluatorModel: resolveModelValue('aieEvaluatorModel', 'aieEvaluatorCustom'),
            questionCount: parseInt(el('aieQCount').value, 10),
            parentRunId: window.__aieParentRunId || null
        };
    }

    function startRun() {
        var cfg = readPersonaConfig();
        if (!cfg.persona.team) { appAlert('Team is required.'); return; }
        if (!cfg.persona.goal) { appAlert('Goal is required.'); return; }
        if (!cfg.testerModel || !cfg.evaluatorModel) { appAlert('Tester and Evaluator models are required.'); return; }
        if (cfg.testerModel === cfg.evaluatorModel) { appAlert('Tester and Evaluator must use different models.'); return; }
        if (cfg.chatbotModel && cfg.chatbotModel === cfg.testerModel) { appAlert('Chatbot and Tester must use different models.'); return; }
        if (cfg.chatbotModel && cfg.chatbotModel === cfg.evaluatorModel) { appAlert('Chatbot and Evaluator must use different models.'); return; }
        var v = customsAreValidated();
        if (!v.ok) { appAlert(v.why); return; }

        // V2: when Simulate Person is checked, embed the AD/usage demographics in persona.
        if (el('aieSimulatePerson').checked && simulatedPersonState && simulatedPersonState.username) {
            cfg.persona.simulatedUsername = simulatedPersonState.username;
            cfg.persona.simulatedDisplayName = simulatedPersonState.displayName;
            cfg.persona.realTitle = simulatedPersonState.title;
            cfg.persona.realDepartment = simulatedPersonState.department;
            cfg.persona.realAccountAge = simulatedPersonState.accountAge;
            cfg.persona.realLoginCount90d = simulatedPersonState.loginCount90d;
            cfg.persona.realIsPlmAdmin = simulatedPersonState.isPlmAdmin;
        }

        el('aieRunBtn').disabled = true;
        el('aieRunMsg').textContent = 'Starting\u2026';
        resultsByIdx = {};
        el('aieResultsBody').innerHTML = '';
        el('aieSummary').style.display = 'none';
        el('aieLiveSection').style.display = '';
        el('aieProgress').textContent = 'Generating questions\u2026';
        currentRunId = null;

        fetch('/api/ai-eval/runs', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(cfg)
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (resp) {
            if (!resp.ok) throw new Error(resp.body.error || 'failed to start');
            window.__aieParentRunId = null;
            currentRunId = resp.body.runId;
            openStream(resp.body.runId);
        }).catch(function (e) {
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Error: ' + e.message;
        });
    }

    function openStream(runId) {
        var es = new EventSource('/api/ai-eval/runs/' + runId + '/stream');
        var fellBackToPolling = false;

        es.addEventListener('questions-ready', function (e) {
            var d = JSON.parse(e.data);
            el('aieProgress').textContent = 'Generated ' + d.count + ' questions; running\u2026';
            el('aieRunMsg').textContent = '';
        });
        es.addEventListener('question-asked', function (e) {
            var d = JSON.parse(e.data);
            // Render card with question only — answer + grade still pending.
            renderCard(d.qIndex, d.question, undefined, null, null);
            el('aieProgress').textContent = 'Question ' + d.qIndex + ' \u2014 asking AI Help\u2026';
        });
        es.addEventListener('answer-received', function (e) {
            var d = JSON.parse(e.data);
            renderCard(d.qIndex, d.question, d.answer, null, null);
            el('aieProgress').textContent = 'Question ' + d.qIndex + ' \u2014 grading\u2026';
        });
        es.addEventListener('graded', function (e) {
            var d = JSON.parse(e.data);
            renderCard(d.qIndex, d.question, d.answer, d.grade, d.reason);
            el('aieProgress').textContent = 'Question ' + d.qIndex + ' graded.';
        });
        es.addEventListener('run-complete', function (e) {
            var d = JSON.parse(e.data);
            es.close();
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Done.';
            showSummary(d);
            loadPastRuns();
        });
        es.addEventListener('run-failed', function (e) {
            var d = JSON.parse(e.data);
            es.close();
            el('aieRunBtn').disabled = false;
            el('aieRunMsg').textContent = 'Run failed: ' + (d.error || 'unknown');
            loadPastRuns();
        });
        es.onerror = function () {
            if (fellBackToPolling) return;
            fellBackToPolling = true;
            es.close();
            pollUntilDone(runId);
        };
    }

    function pollUntilDone(runId) {
        var iv = setInterval(function () {
            fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' })
                .then(function (r) { return r.json(); })
                .then(function (run) {
                    if (run.status === 'RUNNING') return;
                    clearInterval(iv);
                    el('aieRunBtn').disabled = false;
                    if (run.status === 'DONE') {
                        el('aieResultsBody').innerHTML = '';
                        run.results.forEach(function (r) { renderCard(r.qIndex, r.question, r.answer, r.grade, r.reason, r); });
                        showSummary(run.summary);
                    } else {
                        el('aieRunMsg').textContent = 'Run failed.';
                    }
                    loadPastRuns();
                });
        }, 3000);
    }

    /**
     * Render or update a per-question card. Pass `undefined` for fields that
     * haven't arrived yet ("Asking AI Help…", "Grading…" placeholders shown).
     * Pass `null` or empty string for fields that errored.
     */
    function renderCard(qIdx, q, a, grade, reason, overrideMeta) {
        resultsByIdx[qIdx] = { qIndex: qIdx, question: q, answer: a, grade: grade, reason: reason };
        if (overrideMeta) {
            resultsByIdx[qIdx].aiGrade = overrideMeta.aiGrade;
            resultsByIdx[qIdx].aiReason = overrideMeta.aiReason;
            resultsByIdx[qIdx].overriddenBy = overrideMeta.overriddenBy;
            resultsByIdx[qIdx].overriddenAt = overrideMeta.overriddenAt;
            resultsByIdx[qIdx].overrideNote = overrideMeta.overrideNote;
        }
        var existing = document.getElementById('aieCard-' + qIdx);
        var html = renderCardHtml(qIdx, q, a, grade, reason, overrideMeta);
        if (existing) {
            existing.outerHTML = html;
        } else {
            var wrap = document.createElement('div');
            wrap.innerHTML = html;
            el('aieResultsBody').appendChild(wrap.firstElementChild);
            var newCard = document.getElementById('aieCard-' + qIdx);
            if (newCard && newCard.scrollIntoView) {
                newCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }
    }

    function renderCardHtml(qIdx, q, a, grade, reason, overrideMeta) {
        var answerBlock;
        if (a === undefined) {
            answerBlock = '<div style="margin-top:10px; font-style:italic; color:#6B7280; font-size:13px;">'
                        + '<span style="display:inline-block; width:14px; height:14px; border:2px solid #E8E6DF; border-top-color:#4a6fa5; border-radius:50%; vertical-align:middle; margin-right:6px; animation:aieSpin 0.8s linear infinite;"></span>'
                        + 'Asking AI Help\u2026</div>';
        } else if (!a) {
            answerBlock = '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.5px; margin:12px 0 4px;">Answer</div>'
                        + '<div style="color:#B8342B; font-size:13px; font-style:italic;">(no answer \u2014 see grade reason)</div>';
        } else {
            answerBlock = '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.5px; margin:12px 0 4px;">Answer</div>'
                        + '<div style="color:#0F1720; font-size:14px; line-height:1.5; white-space:pre-wrap;">' + escHtml(a) + '</div>';
        }

        var headerGrade = grade ? renderGradeBadge(grade, overrideMeta) : '';
        var editLink = (grade && currentRunId) ?
            ' <a href="javascript:void(0)" onclick="aieOpenOverride(\'' + currentRunId + '\',' + qIdx + ',\'live\')" style="font-size:11px; color:#4a6fa5; text-decoration:none; margin-left:8px;">\u270e edit grade</a>' : '';

        var gradeBlock;
        if (grade) {
            gradeBlock = '<div style="margin-top:14px; padding-top:12px; border-top:1px solid #E8E6DF; color:#6B7280; font-size:13px; line-height:1.5;">' + escHtml(reason || '') + '</div>';
        } else if (a !== undefined) {
            gradeBlock = '<div style="margin-top:12px; padding-top:10px; border-top:1px solid #E8E6DF; font-style:italic; color:#6B7280; font-size:13px;">'
                       + '<span style="display:inline-block; width:14px; height:14px; border:2px solid #E8E6DF; border-top-color:#C7801B; border-radius:50%; vertical-align:middle; margin-right:6px; animation:aieSpin 0.8s linear infinite;"></span>'
                       + 'Grading\u2026</div>';
        } else {
            gradeBlock = '';
        }

        return '<div id="aieCard-' + qIdx + '" style="border:1px solid #E8E6DF; border-radius:6px; padding:14px 16px; margin-bottom:12px; background:#fff;">'
             + '<div style="display:flex; justify-content:space-between; align-items:baseline; margin-bottom:10px; gap:12px;">'
             +   '<div style="font-size:11px; color:#6B7280; text-transform:uppercase; letter-spacing:.5px; font-weight:600;">Question ' + qIdx + '</div>'
             +   '<div style="display:flex; align-items:center;">' + headerGrade + editLink + '</div>'
             + '</div>'
             + '<div style="color:#0F1720; font-size:14px; line-height:1.5; white-space:pre-wrap; font-weight:500;">' + escHtml(q || '') + '</div>'
             + answerBlock
             + gradeBlock
             + '</div>';
    }

    /**
     * Render the grade pill plus, when the result was human-overridden, an extra
     * purple "You" badge. Hovering the badge shows the original AI grade + reason.
     */
    function renderGradeBadge(grade, overrideMeta) {
        var pill = gradePill(grade);
        if (!overrideMeta || !overrideMeta.overriddenBy) return pill;
        var tooltip = 'AI originally graded: ' + (overrideMeta.aiGrade || '?')
                    + (overrideMeta.aiReason ? ' \u2014 ' + overrideMeta.aiReason : '')
                    + '\nChanged by ' + overrideMeta.overriddenBy + ' at ' + (overrideMeta.overriddenAt || '?')
                    + (overrideMeta.overrideNote ? '\nNote: ' + overrideMeta.overrideNote : '');
        return pill
             + ' <span title="' + escHtml(tooltip) + '" style="display:inline-block; padding:2px 8px; background:#7C3AED; color:#fff; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; font-weight:600; border-radius:10px; margin-left:4px; cursor:help;">\ud83d\udc64 You</span>';
    }

    function showSummary(s) {
        el('aieSummary').style.display = '';
        var total = Object.keys(resultsByIdx).length;
        el('aieSummary').innerHTML = '<strong>' + total + ' questions</strong> \u00b7 '
            + 'avg grade <strong>' + escHtml(s.avgGradeLetter) + '</strong> (' + escHtml(String(s.avgGradeNumeric)) + ') \u00b7 '
            + '<strong>' + s.failureCount + '</strong> failures (\u2264B)';
    }

    function loadPastRuns() {
        fetch('/api/ai-eval/runs', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (runs) { renderPastRuns(runs); })
            .catch(function () { el('aiePastBody').innerHTML = '<tr><td colspan="8" style="padding:16px; text-align:center; color:#B8342B;">Failed to load past runs.</td></tr>'; });
    }

    function deltaForRun(run, allRuns) {
        if (!run.summary) return null;
        for (var i = 0; i < allRuns.length; i++) {
            var prev = allRuns[i];
            if (prev.runId === run.runId) continue;
            if (new Date(prev.createdAt) >= new Date(run.createdAt)) continue;
            if (!prev.config || !prev.summary) continue;
            if (configsMatch(prev.config, run.config)) {
                return run.summary.avgGradeNumeric - prev.summary.avgGradeNumeric;
            }
        }
        return null;
    }

    function configsMatch(a, b) {
        return (a.chatbotModel || null) === (b.chatbotModel || null)
            && a.testerModel === b.testerModel
            && a.evaluatorModel === b.evaluatorModel
            && a.questionCount === b.questionCount
            && a.persona && b.persona
            && a.persona.role === b.persona.role
            && a.persona.team === b.persona.team
            && a.persona.experience === b.persona.experience
            && a.persona.goal === b.persona.goal;
    }

    /** Three-line cell: Chatbot (with override flag), Tester, Evaluator. */
    function modelsCell(cfg) {
        if (!cfg) return '';
        var evShort = escHtml(modelShort(cfg.evaluatorModel));
        var teShort = escHtml(modelShort(cfg.testerModel));
        var chatLine;
        if (cfg.chatbotModel) {
            chatLine = '<span title="Chatbot override (non-default AI Help model for this run)" style="color:#7C3AED;">\u2731 ' + escHtml(modelShort(cfg.chatbotModel)) + '</span>';
        } else {
            chatLine = '<span style="color:#9CA3AF;" title="Production default chatbot (Claude Sonnet 4.6)">prod default</span>';
        }
        return '<div style="font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; line-height:1.6;">'
             +   '<div><span style="color:#6B7280;">chat:</span> ' + chatLine + '</div>'
             +   '<div><span style="color:#6B7280;">test:</span> ' + teShort + '</div>'
             +   '<div><span style="color:#6B7280;">eval:</span> ' + evShort + '</div>'
             + '</div>';
    }

    /** Format ms as "1.2s" or "850ms" for compactness in the table. */
    function fmtMs(ms) {
        if (ms == null || ms === 0) return '\u2014';
        if (ms < 1000) return ms + 'ms';
        return (ms / 1000).toFixed(1) + 's';
    }

    function renderPastRuns(runs) {
        if (!runs || !runs.length) {
            el('aiePastBody').innerHTML = '<tr><td colspan="9" style="padding:16px; text-align:center; color:#6B7280;">No runs yet \u2014 start one above.</td></tr>';
            return;
        }
        // V3: build runsById so we can resolve regrade->parent relationships at render time.
        var runsById = {};
        runs.forEach(function (r) { runsById[r.runId] = r; });
        var html = '';
        runs.forEach(function (run) {
            var delta = deltaForRun(run, runs);
            var deltaCell = '\u2014';
            if (delta != null && !isNaN(delta)) {
                var arrow = delta >= 0 ? '\u2191' : '\u2193';
                var color = delta >= 0 ? '#1F8A4C' : '#B8342B';
                deltaCell = '<span style="color:' + color + ';">' + arrow + ' ' + (delta >= 0 ? '+' : '') + delta.toFixed(1) + '</span>';
            }
            var statusBadge = run.status === 'DONE' ? '' :
                (run.status === 'FAILED' ? '<span style="color:#B8342B; font-size:11px;"> (failed)</span>' :
                 '<span style="color:#6B7280; font-size:11px;"> (running)</span>');
            // V3: explicit "Regrade · <date>" label when this run is a regrade.
            var dateCell;
            var personaSecondLine = '';
            if (run.regradeOfRunId) {
                dateCell = '<span title="Regrade of an earlier run \u2014 same Q/A, different evaluator" style="color:#7C3AED; font-weight:600;">\ud83d\udd04 Regrade</span> &middot; '
                         + escHtml(fmtDate(run.createdAt)) + statusBadge;
                var parent = runsById[run.regradeOfRunId];
                if (parent) {
                    personaSecondLine = '<div style="font-size:11px; color:#6B7280; margin-top:2px;">'
                                      + 'of ' + escHtml(fmtDate(parent.createdAt))
                                      + ' \u2014 was graded by <code style="background:#FAFAF7; padding:1px 4px; border-radius:3px; font-family:\'IBM Plex Mono\',Consolas,monospace;">' + escHtml(modelShort(parent.config && parent.config.evaluatorModel)) + '</code>'
                                      + '</div>';
                }
            } else {
                dateCell = escHtml(fmtDate(run.createdAt)) + statusBadge;
            }
            html += '<tr>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + dateCell + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + personaCompact(run.config && run.config.persona) + personaSecondLine + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF;">' + modelsCell(run.config) + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.config ? run.config.questionCount : '') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.summary ? gradePill(run.summary.avgGradeLetter) : '\u2014') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + (run.summary ? run.summary.failureCount : '\u2014') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; color:#6B7280;">' + (run.summary ? fmtMs(run.summary.avgAnswerLatencyMs) : '\u2014') + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">' + deltaCell + '</td>'
                + '<td style="padding:8px; border-bottom:1px solid #E8E6DF; text-align:center;">'
                +   '<button onclick="aieRerun(\'' + run.runId + '\')" title="Rerun this config (fresh questions, same evaluator)" style="background:#fff; border:1px solid #4a6fa5; color:#4a6fa5; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;">Rerun</button>'
                +   '<button onclick="aieOpenRegrade(\'' + run.runId + '\')" title="Regrade these same questions with a different evaluator and/or chatbot" style="background:#fff; border:1px solid #7C3AED; color:#7C3AED; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;">\ud83d\udd04 Regrade</button>'
                +   '<button onclick="aieExport(\'' + run.runId + '\')" title="Export markdown for Claude" style="background:#fff; border:1px solid #6B7280; color:#6B7280; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;">Export</button>'
                +   '<button onclick="aieToggleExpand(\'' + run.runId + '\')" title="Show all questions" style="background:#fff; border:1px solid #E8E6DF; color:#6B7280; padding:3px 8px; border-radius:3px; font-size:11px; cursor:pointer;">\u25be</button>'
                + '</td>'
                + '</tr>'
                + '<tr id="aieExpand-' + run.runId + '" style="display:none;"><td colspan="9" style="padding:0; background:#FAFAF7;"><div style="padding:12px;"><em style="color:#6B7280;">Loading\u2026</em></div></td></tr>';
        });
        el('aiePastBody').innerHTML = html;
    }

    window.aieRerun = function (runId) {
        fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (run) {
                if (!run.config) return;
                var p = run.config.persona;
                el('aieRole').value = p.role;
                if (['PLM IT','Quality','Engineering','Operations'].indexOf(p.team) >= 0) {
                    el('aieTeam').value = p.team;
                    el('aieTeamOther').style.display = 'none';
                } else {
                    el('aieTeam').value = '__other';
                    el('aieTeamOther').style.display = '';
                    el('aieTeamOther').value = p.team;
                }
                Array.prototype.forEach.call(document.getElementsByName('aieExp'), function (r) {
                    r.checked = (r.value === p.experience);
                });
                el('aieGoal').value = p.goal;
                applyModelToPicker('aieChatbotModel', 'aieChatbotCustom', 'aieChatbotStatus', run.config.chatbotModel || DEFAULT_VALUE);
                applyModelToPicker('aieTesterModel', 'aieTesterCustom', 'aieTesterStatus', run.config.testerModel);
                applyModelToPicker('aieEvaluatorModel', 'aieEvaluatorCustom', 'aieEvaluatorStatus', run.config.evaluatorModel);
                el('aieQCount').value = String(run.config.questionCount);
                syncModelDropdowns();
                window.__aieParentRunId = runId;
                // V2: if this was a simulated-person run, restore that state so the demographics
                // are re-fetched (in case AD or activity changed since the original run).
                if (p.simulatedUsername) {
                    el('aieSimulatePerson').checked = true;
                    el('aieSimulatePersonBody').style.display = '';
                    el('aieUserSearch').value = (p.simulatedDisplayName || p.simulatedUsername) + ' (' + p.simulatedUsername + ')';
                    loadAndApplyPersona(p.simulatedUsername);
                    // startRun runs after persona loads (race ok — applyInferenceToForm runs first because fetch is queued)
                    setTimeout(startRun, 800);
                } else {
                    el('aieSimulatePerson').checked = false;
                    el('aieSimulatePersonBody').style.display = 'none';
                    simulatedPersonState = null;
                    startRun();
                }
            });
    };

    window.aieOpenRegrade = function (runId) {
        // Fetch run config to know what models are already in use, then show picker.
        fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (run) {
                if (!run.config) { showToast('Run not found.', 'error'); return; }
                if (run.status !== 'DONE') { showToast('Run must be DONE before regrading.', 'warn'); return; }
                showRegradePopover(runId, run);
            });
    };

    /**
     * Build a select fragment for the regrade modal. Tester isn't a constraint here
     * (questions are frozen — Tester doesn't run during regrade), so the only slugs
     * that get disabled are those already in use by the other picker in this modal.
     * The first option is "Keep original" when allowKeep is true (used for Chatbot picker).
     */
    function regradeSelectHtml(selectId, customRowId, customInputId, statusId,
                                alreadyUsed, allowKeep, keepLabel, preselect) {
        var html = '<select id="' + selectId + '" style="width:100%; padding:6px 8px; border:1px solid #E8E6DF; border-radius:4px;">';
        if (allowKeep) {
            html += '<option value="__keep__"' + (preselect === '__keep__' ? ' selected' : '') + '>' + escHtml(keepLabel || 'Keep original') + '</option>';
        }
        (MODEL_CATALOG && MODEL_CATALOG.groups || []).forEach(function (g) {
            html += '<optgroup label="' + escHtml(g.label) + '">';
            (g.options || []).forEach(function (o) {
                var disabled = (alreadyUsed && alreadyUsed.indexOf(o.slug) >= 0);
                var note = disabled ? ' \u2014 in use elsewhere' : '';
                html += '<option value="' + escHtml(o.slug) + '"' + (disabled ? ' disabled' : '')
                      + (preselect === o.slug ? ' selected' : '') + '>' + escHtml(o.label) + escHtml(note) + '</option>';
            });
            html += '</optgroup>';
        });
        html += '<option value="__custom__"' + (preselect === '__custom__' ? ' selected' : '') + '>Custom\u2026</option>';
        html += '</select>';
        html += '<div id="' + customRowId + '" style="display:none; margin-top:6px;">'
              + '<input id="' + customInputId + '" type="text" placeholder="@anthropic-eastus2/claude-haiku-4-5" style="width:100%; padding:6px 8px; margin-bottom:4px; border:1px solid #E8E6DF; border-radius:4px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px;">'
              + '<button type="button" onclick="aieValidateCustom(\'' + customInputId + '\',\'' + statusId + '\')" style="font-size:11px; padding:3px 10px; background:#fff; border:1px solid #6B7280; color:#6B7280; border-radius:3px; cursor:pointer;">Validate</button>'
              + '<span id="' + statusId + '" style="margin-left:8px; font-size:11px; color:#6B7280;"></span>'
              + '</div>';
        return html;
    }

    function showRegradePopover(runId, parent) {
        if (!MODEL_CATALOG) {
            showToast('Model catalog not loaded yet \u2014 try again in a moment.', 'warn');
            return;
        }
        var tester = parent.config.testerModel;
        var origEval = parent.config.evaluatorModel;
        var origChat = parent.config.chatbotModel; // may be null = production default

        var keepChatLabel = origChat ? ('Keep original chatbot (' + modelShort(origChat) + ')')
                                      : 'Keep original chatbot (Production default)';

        var overlay = document.createElement('div');
        overlay.id = 'aieRegradeOverlay';
        overlay.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:10000; display:flex; align-items:center; justify-content:center; padding:20px;';
        overlay.addEventListener('click', function (e) { if (e.target === overlay) overlay.remove(); });

        var modal = document.createElement('div');
        modal.style.cssText = 'background:#fff; border-radius:8px; max-width:520px; width:100%; padding:24px; font-family:\'IBM Plex Sans\',\'Segoe UI\',Calibri,Arial,sans-serif;';
        modal.innerHTML =
            '<h3 style="margin:0 0 8px; font-family:\'IBM Plex Serif\',Georgia,serif; font-size:18px; color:#0F1720;">\ud83d\udd04 Regrade</h3>'
          + '<p id="aieRgBody" style="margin:0 0 16px; color:#6B7280; font-size:13px; line-height:1.5;">Reuses the same <strong>' + parent.config.questionCount + ' questions</strong> from the original run (written by <code style="background:#FAFAF7; padding:1px 4px; border-radius:3px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px;">' + escHtml(modelShort(tester)) + '</code> \u2014 Tester doesn\u0027t re-run here). Pick a new evaluator below; optionally swap the chatbot too.</p>'

          + '<label style="display:block; margin-bottom:6px; font-size:12px; color:#6B7280;">Chatbot</label>'
          + regradeSelectHtml('aieRgChatbot', 'aieRgChatbotCustomRow', 'aieRgChatbotCustom', 'aieRgChatbotStatus',
                              [], true, keepChatLabel, '__keep__')

          + '<label style="display:block; margin:14px 0 6px; font-size:12px; color:#6B7280;">Evaluator <span style="color:#9CA3AF;">(must differ from Chatbot)</span></label>'
          + regradeSelectHtml('aieRgEval', 'aieRgEvalCustomRow', 'aieRgEvalCustom', 'aieRgEvalStatus',
                              origChat ? [origChat] : [], false, null, null)

          + '<div id="aieRgRerunRow" style="margin-top:14px; padding:10px 12px; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:4px;">'
          +   '<label style="display:flex; gap:8px; align-items:flex-start; font-size:12px; color:#0F1720; cursor:pointer;">'
          +     '<input type="checkbox" id="aieRgForceRerun" style="margin-top:2px;">'
          +     '<span><strong>Re-ask AI Help (don\u0027t reuse parent answers).</strong> '
          +     '<span style="color:#6B7280;">Use this when you\u0027ve edited the AI Help system prompt or knowledge base since the parent run \u2014 the chatbot model is the same, but its inputs changed, so the answers should be re-fetched. Slower (one AI Help call per question) but produces a fair before/after comparison.</span></span>'
          +   '</label>'
          + '</div>'

          + '<div style="margin-top:18px; display:flex; justify-content:flex-end; gap:8px;">'
          +   '<button id="aieRgCancel" style="padding:8px 16px; background:#fff; border:1px solid #E8E6DF; border-radius:4px; color:#6B7280; cursor:pointer;">Cancel</button>'
          +   '<button id="aieRgSubmit" style="padding:8px 16px; background:#7C3AED; border:none; border-radius:4px; color:#fff; font-weight:600; cursor:pointer;">Regrade</button>'
          + '</div>';

        overlay.appendChild(modal);
        document.body.appendChild(overlay);

        // Show/hide custom rows + adapt body copy when chatbot selection changes.
        // Also hide the Re-ask checkbox when the chatbot is being swapped (re-ask is implicit then).
        function syncRgBodyAndCustomRows() {
            document.getElementById('aieRgChatbotCustomRow').style.display =
                (document.getElementById('aieRgChatbot').value === CUSTOM_VALUE) ? '' : 'none';
            document.getElementById('aieRgEvalCustomRow').style.display =
                (document.getElementById('aieRgEval').value === CUSTOM_VALUE) ? '' : 'none';
            var chatVal = document.getElementById('aieRgChatbot').value;
            var rerunRow = document.getElementById('aieRgRerunRow');
            var rerunChk = document.getElementById('aieRgForceRerun');
            var keepingChatbot = (chatVal === '__keep__');
            rerunRow.style.display = keepingChatbot ? '' : 'none';
            if (!keepingChatbot) rerunChk.checked = false;  // Implicit when chatbot changes.
            var bodyEl = document.getElementById('aieRgBody');
            if (keepingChatbot && rerunChk.checked) {
                bodyEl.innerHTML = 'Reuses the same <strong>' + parent.config.questionCount + ' questions</strong>, then <strong>re-asks AI Help</strong> with the same chatbot (so any prompt or knowledge-base edits take effect) and grades the fresh answers.';
            } else if (keepingChatbot) {
                bodyEl.innerHTML = 'Reuses the same <strong>' + parent.config.questionCount + ' questions and AI Help answers</strong> from the original run. Only the Evaluator runs.';
            } else {
                bodyEl.innerHTML = 'Reuses the same <strong>' + parent.config.questionCount + ' questions</strong>, but <strong>re-runs AI Help</strong> with the new chatbot, then grades with the new evaluator.';
            }
        }
        document.getElementById('aieRgChatbot').addEventListener('change', syncRgBodyAndCustomRows);
        document.getElementById('aieRgEval').addEventListener('change', syncRgBodyAndCustomRows);
        document.getElementById('aieRgForceRerun').addEventListener('change', syncRgBodyAndCustomRows);
        ['aieRgChatbotCustom', 'aieRgEvalCustom'].forEach(function (id) {
            document.getElementById(id).addEventListener('input', function () {
                validatedCustom[id] = null;
                var statusId = id.replace('Custom', 'Status');
                if (document.getElementById(statusId)) document.getElementById(statusId).innerHTML = '';
            });
        });

        document.getElementById('aieRgCancel').addEventListener('click', function () { overlay.remove(); });
        document.getElementById('aieRgSubmit').addEventListener('click', function () {
            var chatSel = document.getElementById('aieRgChatbot');
            var evSel = document.getElementById('aieRgEval');

            var chatPicked;
            if (chatSel.value === '__keep__') chatPicked = null;
            else if (chatSel.value === CUSTOM_VALUE) chatPicked = (document.getElementById('aieRgChatbotCustom').value || '').trim() || null;
            else chatPicked = chatSel.value;

            var evPicked;
            if (evSel.value === CUSTOM_VALUE) evPicked = (document.getElementById('aieRgEvalCustom').value || '').trim() || null;
            else evPicked = evSel.value;

            if (!evPicked) { showToast('Pick an evaluator first.', 'warn'); return; }
            if (chatPicked && chatPicked === evPicked) { showToast('Chatbot and Evaluator must use different models.', 'warn'); return; }

            // Validate custom slugs were validated.
            if (chatSel.value === CUSTOM_VALUE && validatedCustom['aieRgChatbotCustom'] !== chatPicked) {
                showToast('Click Validate on the custom Chatbot slug first.', 'warn'); return;
            }
            if (evSel.value === CUSTOM_VALUE && validatedCustom['aieRgEvalCustom'] !== evPicked) {
                showToast('Click Validate on the custom Evaluator slug first.', 'warn'); return;
            }

            var forceRerun = document.getElementById('aieRgForceRerun').checked;
            submitRegrade(runId, evPicked, chatPicked, forceRerun, overlay);
        });
    }

    function submitRegrade(parentRunId, evaluatorModel, chatbotModel, forceRerunAiHelp, overlay) {
        var payload = { evaluatorModel: evaluatorModel };
        if (chatbotModel) payload.chatbotModel = chatbotModel;
        if (forceRerunAiHelp) payload.forceRerunAiHelp = true;
        fetch('/api/ai-eval/runs/' + parentRunId + '/regrade', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (resp) {
            if (!resp.ok) {
                showToast('<strong>Regrade failed:</strong> ' + escHtml(resp.body.error || 'unknown'), 'error');
                return;
            }
            overlay.remove();
            // Open the live section so the user sees streaming progress.
            resultsByIdx = {};
            el('aieResultsBody').innerHTML = '';
            el('aieSummary').style.display = 'none';
            el('aieLiveSection').style.display = '';
            el('aieProgress').textContent = 'Regrading\u2026';
            el('aieRunMsg').textContent = '';
            currentRunId = resp.body.runId;
            showToast('<strong>Regrade started.</strong> Streaming below \u2014 questions + answers shown immediately, only grading runs.', 'info');
            openStream(resp.body.runId);
        }).catch(function (e) {
            showToast('<strong>Regrade failed:</strong> ' + escHtml(e.message || 'network error'), 'error');
        });
    }

    window.aieExport = function (runId) {
        showToast('<strong>Preparing download\u2026</strong>', 'info');
        fetch('/api/ai-eval/runs/' + runId + '/export/download', { credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) {
                    return r.json().then(function (j) { throw new Error(j.error || ('HTTP ' + r.status)); });
                }
                return r.blob().then(function (blob) {
                    var url = URL.createObjectURL(blob);
                    var a = document.createElement('a');
                    a.href = url;
                    a.download = 'eval-' + runId + '.md';
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
                    showToast('<strong>Downloaded</strong> eval-' + runId.substring(0, 8) + '\u2026.md', 'info');
                });
            })
            .catch(function (e) {
                showToast('<strong>Export failed:</strong> ' + escHtml(e.message || 'network error'), 'error');
            });
    };

    window.aieToggleExpand = function (runId) {
        var row = document.getElementById('aieExpand-' + runId);
        if (!row) return;
        if (row.style.display !== 'none') { row.style.display = 'none'; return; }
        row.style.display = '';
        renderExpandedRowResults(runId, row);
    };

    function renderExpandedRowResults(runId, row) {
        // First fetch the run summary + the results.
        Promise.all([
            fetch('/api/ai-eval/runs/' + runId, { credentials: 'same-origin' }).then(function (r) { return r.json(); }),
            fetch('/api/ai-eval/runs/' + runId + '/results', { credentials: 'same-origin' }).then(function (r) { return r.json(); })
        ]).then(function (parts) {
            var run = parts[0];
            var data = parts[1];
            // V3: if this is a regrade, also fetch parent metadata + parent results
            // so we can render the banner + side-by-side grade column.
            if (run && run.regradeOfRunId) {
                Promise.all([
                    fetch('/api/ai-eval/runs/' + run.regradeOfRunId, { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : null; }),
                    fetch('/api/ai-eval/runs/' + run.regradeOfRunId + '/results', { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : null; })
                ]).then(function (pp) {
                    renderExpandHtml(row, runId, run, data, pp[0], pp[1]);
                });
            } else {
                renderExpandHtml(row, runId, run, data, null, null);
            }
        });
    }

    function renderExpandHtml(row, runId, run, data, parent, parentData) {
        var banner = '';
        var parentResultsByQ = {};
        if (parent && parentData) {
            (parentData.results || []).forEach(function (r) { parentResultsByQ[r.qIndex] = r; });
            var parentSummary = parent.summary || {};
            var newSummary = run.summary || {};
            var deltaNum = (newSummary.avgGradeNumeric || 0) - (parentSummary.avgGradeNumeric || 0);
            var deltaStr = (deltaNum >= 0 ? '+' : '') + deltaNum.toFixed(1);
            var deltaColor = deltaNum > 0 ? '#1F8A4C' : (deltaNum < 0 ? '#B8342B' : '#6B7280');

            // Detect what actually changed between parent and this regrade.
            var parentChat = (parent.config && parent.config.chatbotModel) || null;
            var newChat    = (run.config && run.config.chatbotModel) || null;
            var parentEval = (parent.config && parent.config.evaluatorModel) || null;
            var newEval    = (run.config && run.config.evaluatorModel) || null;
            var chatbotChanged   = (parentChat || '') !== (newChat || '');
            var evaluatorChanged = (parentEval || '') !== (newEval || '');
            var rerunSameChatbot = !chatbotChanged && !!(run.config && run.config.forceRerunAiHelp);

            function code(s) {
                return '<code style="background:#fff; padding:1px 6px; border-radius:3px; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:12px;">'
                     + escHtml(modelShort(s) || 'production default') + '</code>';
            }

            var sentence;
            if (chatbotChanged && evaluatorChanged) {
                sentence = 'Same <strong>' + (data.results || []).length + ' questions</strong>, but AI Help re-answered with chatbot ' + code(newChat) + ' (was ' + code(parentChat) + '), then graded with ' + code(newEval) + ' (was ' + code(parentEval) + ').';
            } else if (chatbotChanged) {
                sentence = 'Same <strong>' + (data.results || []).length + ' questions</strong>, but AI Help re-answered with chatbot ' + code(newChat) + ' (was ' + code(parentChat) + '), then graded with the same evaluator ' + code(newEval) + '.';
            } else if (rerunSameChatbot) {
                sentence = 'Same <strong>' + (data.results || []).length + ' questions</strong>, AI Help <strong>re-asked</strong> with the same chatbot ' + code(newChat) + ' (so any prompt or knowledge-base edits since the parent run take effect)' + (evaluatorChanged ? ', then graded with ' + code(newEval) + ' (was ' + code(parentEval) + ')' : ', graded with the same evaluator ' + code(newEval)) + '.';
            } else {
                // Pure regrade — only the evaluator differs.
                sentence = 'Same <strong>' + (data.results || []).length + ' questions + AI Help answers</strong> (chatbot ' + code(parentChat) + '), regraded with ' + code(newEval) + ' instead of ' + code(parentEval) + '.';
            }

            banner = '<div style="margin:0 12px 12px; padding:10px 14px; background:#f3ecff; border-left:4px solid #7C3AED; border-radius:0 6px 6px 0; font-size:13px; color:#0F1720;">'
                   + '<strong>\ud83d\udd04 This is a regrade of an earlier run.</strong> ' + sentence
                   + ' Original avg: <strong>' + escHtml(parentSummary.avgGradeLetter || '?') + '</strong> ('
                   + (parentSummary.avgGradeNumeric == null ? '?' : parentSummary.avgGradeNumeric) + ')'
                   + ' &rarr; New avg: <strong>' + escHtml(newSummary.avgGradeLetter || '?') + '</strong> ('
                   + (newSummary.avgGradeNumeric == null ? '?' : newSummary.avgGradeNumeric) + ')'
                   + ' &middot; <span style="color:' + deltaColor + '; font-weight:600;">\u0394 ' + deltaStr + '</span>'
                   + '</div>';
        }

        var hasParent = parent != null;
        var wasHeader = hasParent ? '<th style="padding:6px; width:60px; text-align:center; color:#7C3AED;">Was</th>' : '';
        var inner = banner
            + '<div style="padding:0 12px 12px;"><table style="width:100%; border-collapse:collapse; font-size:12px;">'
            + '<thead><tr style="color:#6B7280; text-align:left;"><th style="padding:6px;">#</th><th style="padding:6px;">Question</th><th style="padding:6px;">Answer</th>'
            + wasHeader
            + '<th style="padding:6px; width:120px; text-align:center;">Grade</th><th style="padding:6px;">Reason</th><th style="padding:6px; width:80px;"></th></tr></thead><tbody>';
        (data.results || []).forEach(function (r) {
            var wasCell = '';
            if (hasParent) {
                var parentR = parentResultsByQ[r.qIndex];
                if (parentR && parentR.grade) {
                    var changed = parentR.grade !== r.grade;
                    wasCell = '<td style="padding:6px; vertical-align:top; text-align:center;">'
                            + '<span title="Original grade by ' + escHtml(modelShort(parent.config.evaluatorModel)) + '" style="display:inline-block; min-width:24px; padding:2px 6px; border-radius:8px; background:' + (changed ? '#FAFAF7' : '#fff') + '; color:#6B7280; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:11px; border:1px solid ' + (changed ? '#7C3AED' : '#E8E6DF') + ';">'
                            + escHtml(parentR.grade) + '</span></td>';
                } else {
                    wasCell = '<td style="padding:6px; vertical-align:top; text-align:center; color:#aaa;">\u2014</td>';
                }
            }
            inner += '<tr><td style="padding:6px; vertical-align:top;">' + r.qIndex + '</td>'
                  +  '<td style="padding:6px; vertical-align:top;">' + escHtml(r.question) + '</td>'
                  +  '<td style="padding:6px; vertical-align:top;">' + escHtml(r.answer || '') + '</td>'
                  +  wasCell
                  +  '<td style="padding:6px; vertical-align:top; text-align:center;">' + renderGradeBadge(r.grade, r) + '</td>'
                  +  '<td style="padding:6px; vertical-align:top; color:#6B7280;">' + escHtml(r.reason || '') + '</td>'
                  +  '<td style="padding:6px; vertical-align:top; text-align:right;">'
                  +    '<a href="javascript:void(0)" onclick="aieOpenOverride(\'' + runId + '\',' + r.qIndex + ',\'past\')" style="font-size:11px; color:#4a6fa5; text-decoration:none;">\u270e edit</a>'
                  +  '</td></tr>';
        });
        inner += '</tbody></table></div>';
        row.firstChild.innerHTML = inner;
    }

    var initialized = false;
    window.aieInit = function () {
        if (initialized) return;
        initialized = true;
        bindForm();
        loadPastRuns();
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            if (document.getElementById('panelAiEval') && document.getElementById('panelAiEval').style.display !== 'none') {
                window.aieInit();
            }
        });
    }
})();
