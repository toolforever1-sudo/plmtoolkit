/* ---------------------------------------------------------------------------
 * Announcements — Admin > Communications (design: HANDOFF option 1a Workbench)
 *
 * Admin-only compose surface: rough draft -> AI wordsmith / HTML email ->
 * live preview -> test-to-self -> send now or scheduled, with a shared
 * editable queue for scheduled items and a full send history. All endpoints
 * are additionally gated server-side on pdl-plm-admin (isPlmAdmin session
 * attribute) — this file is presentation only.
 *
 * Lazy-init contract: switchTab('announcements') calls window.announcementsInit()
 * which renders once into #panelAnnouncements and refreshes data on re-entry.
 * ------------------------------------------------------------------------- */
(function() {
    'use strict';

    var MONO = "'IBM Plex Mono',Consolas,monospace";
    var SERIF = "'IBM Plex Serif',Georgia,serif";

    var annState = {
        rendered: false,
        current: null,        // the announcement being composed/edited
        audience: null,       // /audience payload
        list: [],
        saveTimer: null,
        savePending: false,
        selectedUsers: [],    // for audienceType=specific
        lastTestStrip: ''
    };

    function esc(s) {
        if (s === null || s === undefined) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function api(path, opts) {
        opts = opts || {};
        opts.credentials = 'same-origin';
        if (opts.body && !opts.headers) opts.headers = { 'Content-Type': 'application/json' };
        return fetch('/api/announcements' + path, opts)
            .then(function(r) { return r.json().then(function(j) { return { ok: r.ok, status: r.status, body: j }; }); });
    }

    function fmtTime(iso) {
        if (!iso) return '';
        try {
            var d = new Date(iso);
            return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
        } catch (e) { return iso; }
    }

    function fmtDate(iso) {
        if (!iso) return '';
        try {
            var d = new Date(iso);
            return d.toLocaleDateString([], { month: 'short', day: '2-digit', year: 'numeric' });
        } catch (e) { return iso; }
    }

    function fmtDateTime(iso) {
        if (!iso) return '';
        return fmtDate(iso) + ' · ' + fmtTime(iso);
    }

    // ------------------------------------------------------------------ label helpers

    function audienceLabel(t) {
        return t === 'dl' ? 'All DL users' : t === 'specific' ? 'Specific users' : 'Ever logged in';
    }

    function shortName(display, username) {
        if (!display) return username || '';
        var s = display.replace(/\([^)]*\)/g, '').trim();
        if (s.indexOf(',') >= 0) {
            var last = s.split(',')[0].trim();
            var first = s.split(',')[1].trim().split(/\s+/)[0];
            return (first ? first.charAt(0) + '. ' : '') + last;
        }
        return s;
    }

    function recipientCountFor(type) {
        var a = annState.audience || {};
        if (type === 'dl') return a.dl || 0;
        if (type === 'specific') return annState.selectedUsers.length;
        return a.everLoggedIn || 0;
    }

    // ------------------------------------------------------------------ init + render

    window.announcementsInit = function() {
        var panel = document.getElementById('panelAnnouncements');
        if (!panel) return;
        if (!annState.rendered) {
            panel.innerHTML = shellHtml();
            wireCompose();
            annState.rendered = true;
        }
        refreshAudience();
        refreshList();
        if (!annState.current) newDraft();
    };

    function shellHtml() {
        return '' +
        '<div style="padding:22px 32px 0; max-width:1400px;">' +
          '<div style="display:flex; align-items:center; gap:10px;">' +
            '<div style="font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280;">Admin &middot; Communications</div>' +
            '<span style="display:inline-block; padding:2px 8px; border-radius:8px; background:#fdf1ee; color:#B8342B; border:1px solid #f5d4cc; font-size:9.5px; font-weight:600; font-family:' + MONO + '; letter-spacing:0.08em; text-transform:uppercase;">&#x26D2; PLM IT only</span>' +
          '</div>' +
          '<div style="display:flex; align-items:baseline; justify-content:space-between; margin-top:6px; flex-wrap:wrap;">' +
            '<h1 style="font-family:' + SERIF + '; font-size:28px; font-weight:500; letter-spacing:-0.01em; color:#0F1720; margin:0;">Announcements</h1>' +
            '<div style="font-size:12px; color:#6B7280;">Members of <span style="font-family:' + MONO + '; font-size:11px;">pdl-plm-admin</span> can send. Every send is logged.</div>' +
          '</div>' +
          '<p style="font-size:13px; color:#4a4f58; margin:4px 0 0;">Send an update to Toolkit users by email, with an optional in-app banner and What&rsquo;s New entry.</p>' +
        '</div>' +
        '<div style="display:grid; grid-template-columns:560px 1fr; gap:20px; padding:18px 32px 8px; align-items:start; max-width:1400px;">' +
          '<div style="display:flex; flex-direction:column; gap:14px;">' + composeCard() + audienceCard() + sendCard() + '</div>' +
          '<div style="display:flex; flex-direction:column; gap:8px; position:sticky; top:12px;">' +
            '<div style="display:flex; align-items:center; justify-content:space-between;">' +
              '<span style="font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.12em; text-transform:uppercase; color:#6B7280;">Preview &middot; as received in Outlook / Gmail</span>' +
              '<span id="annPreviewMeta" style="font-family:' + MONO + '; font-size:11px; color:#6B7280;"></span>' +
            '</div>' +
            '<div style="background:#ECEDEF; border:1px solid #E8E6DF; border-radius:8px; padding:24px 20px; min-height:420px;">' +
              '<div id="annPreviewStage" style="max-width:640px; margin:0 auto;">' +
                '<div style="text-align:center; color:#9CA3AF; font-size:12.5px; padding:80px 20px;">Generate the HTML email to see the preview.</div>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div style="padding:0 32px 32px; max-width:1400px;">' +
          '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;">' +
            '<div style="background:#f6f5f0; border-bottom:1px solid #E8E6DF; padding:9px 16px; display:flex; align-items:center; justify-content:space-between;">' +
              '<span style="font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; font-weight:500;">Past announcements</span>' +
              '<span id="annHistCounts" style="font-family:' + MONO + '; font-size:11px; color:#6B7280;"></span>' +
            '</div>' +
            '<div id="annHistBody" style="overflow-x:auto;"><div style="padding:16px; color:#9CA3AF; font-size:12.5px;">Loading&hellip;</div></div>' +
          '</div>' +
        '</div>';
    }

    function cardHead(label, rightId) {
        return '<div style="background:#f6f5f0; border-bottom:1px solid #E8E6DF; padding:9px 16px; display:flex; align-items:center; justify-content:space-between;">' +
            '<span style="font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; font-weight:500;">' + label + '</span>' +
            (rightId ? '<span id="' + rightId + '" style="font-family:' + MONO + '; font-size:11px; color:#1F8A4C;"></span>' : '') +
          '</div>';
    }

    var LBL = 'display:block; font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; margin-bottom:6px;';
    var BTN2 = 'background:#fff; border:1px solid #E8E6DF; border-radius:6px; padding:7px 14px; font-size:12.5px; font-weight:600; color:#0F1720; cursor:pointer; font-family:inherit;';
    var BTN1 = 'background:#0F1720; border:1px solid #0F1720; border-radius:6px; padding:7px 14px; font-size:12.5px; font-weight:600; color:#fff; cursor:pointer; font-family:inherit;';

    function composeCard() {
        return '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;">' +
          cardHead('1 &middot; Compose', 'annSaveStatus') +
          '<div style="padding:16px; display:flex; flex-direction:column; gap:14px;">' +
            '<div>' +
              '<label style="' + LBL + '">Subject</label>' +
              '<input id="annSubject" style="width:100%; box-sizing:border-box; height:34px; padding:0 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:13px; color:#0F1720; font-family:inherit; background:#fff;">' +
              '<div id="annSubjectChips" style="display:flex; align-items:center; gap:6px; margin-top:8px; flex-wrap:wrap;"></div>' +
            '</div>' +
            '<div>' +
              '<label style="' + LBL + '">Your draft &mdash; rough is fine</label>' +
              '<textarea id="annDraft" rows="5" style="width:100%; box-sizing:border-box; padding:10px; border:1px solid #E8E6DF; border-radius:4px; font-size:13px; line-height:1.55; color:#0F1720; font-family:inherit; resize:vertical;"></textarea>' +
            '</div>' +
            '<div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">' +
              '<button id="annWordsmithBtn" style="' + BTN2 + '">&#10024; Wordsmith draft</button>' +
              '<button id="annGenerateBtn" style="' + BTN1 + '">Generate HTML email &rarr;</button>' +
              '<span id="annAiStatus" style="font-family:' + MONO + '; font-size:11px; color:#1F8A4C; margin-left:auto;"></span>' +
            '</div>' +
          '</div>' +
        '</div>';
    }

    function audienceCard() {
        function radio(val, labelHtml) {
            return '<label style="display:flex; align-items:center; gap:9px; font-size:13px; color:#0F1720; cursor:pointer;">' +
                '<input type="radio" name="annAudience" value="' + val + '" style="margin:0;">' + labelHtml + '</label>';
        }
        function check(id, label, hint) {
            return '<label style="display:flex; align-items:center; gap:9px; font-size:12.5px; color:#0F1720; cursor:pointer;">' +
                '<input type="checkbox" id="' + id + '" style="margin:0;">' + label +
                (hint ? ' <span style="font-size:11px; color:#9CA3AF;">' + hint + '</span>' : '') + '</label>';
        }
        return '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; overflow:hidden;">' +
          cardHead('2 &middot; Audience &amp; channels') +
          '<div style="padding:16px; display:flex; flex-direction:column; gap:12px;">' +
            '<div style="display:flex; flex-direction:column; gap:8px;">' +
              radio('everLoggedIn', 'Everyone who has ever logged in <span id="annCntEver" style="font-family:' + MONO + '; font-size:11px; color:#6B7280; background:#f3f3ee; border:1px solid #E8E6DF; border-radius:10px; padding:1px 8px;">&hellip;</span>') +
              radio('dl', 'All users in the DL <span id="annCntDl" style="font-family:' + MONO + '; font-size:11px; color:#6B7280; background:#f3f3ee; border:1px solid #E8E6DF; border-radius:10px; padding:1px 8px;">&hellip;</span> <span id="annCntDlNote" style="font-size:11px; color:#9CA3AF;"></span>') +
              radio('specific', 'Specific users&hellip;') +
              '<div id="annUserPicker" style="display:none; margin-left:24px;">' +
                '<div id="annUserChips" style="display:flex; gap:6px; flex-wrap:wrap; margin-bottom:6px;"></div>' +
                '<input id="annUserSearch" placeholder="Type a name or loginid&hellip;" style="width:100%; box-sizing:border-box; height:30px; padding:0 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:12.5px; font-family:inherit;">' +
                '<div id="annUserResults" style="border:1px solid #E8E6DF; border-top:none; border-radius:0 0 4px 4px; max-height:160px; overflow-y:auto; display:none; background:#fff;"></div>' +
              '</div>' +
            '</div>' +
            '<div style="border-top:1px solid #f0efe9; padding-top:12px; display:flex; flex-direction:column; gap:7px;">' +
              check('annChEmail', 'Email (HTML)') +
              check('annChBanner', 'In-app banner on next login', 'dismissible, shows once') +
              check('annChWhatsNew', 'Add to What&rsquo;s New') +
              '<div style="font-size:11.5px; color:#6B7280; margin-top:2px;">Cc <span style="font-family:' + MONO + '; font-size:11px;">pdl-plm-admin</span> &mdash; always on for announcements.</div>' +
            '</div>' +
          '</div>' +
        '</div>';
    }

    function sendCard() {
        return '<div style="background:#fff; border:1px solid #E8E6DF; border-radius:8px; padding:14px 16px; display:flex; flex-direction:column; gap:12px;">' +
          '<div style="display:flex; align-items:center; gap:10px;">' +
            '<span style="font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280;">3 &middot; Send</span>' +
            '<div id="annWhenToggle" style="display:inline-flex; background:#FAFAF7; border:1px solid #E8E6DF; border-radius:18px; padding:3px; margin-left:auto;">' +
              '<span data-when="now" style="background:#fff; color:#0F1720; font-size:12px; font-weight:600; padding:5px 12px; border-radius:14px; box-shadow:0 1px 2px rgba(0,0,0,0.06); cursor:pointer;">Send now</span>' +
              '<span data-when="schedule" style="color:#6B7280; font-size:12px; font-weight:600; padding:5px 12px; border-radius:14px; cursor:pointer;">Schedule&hellip;</span>' +
            '</div>' +
          '</div>' +
          '<div id="annScheduleRow" style="display:none;">' +
            '<label style="' + LBL + '">Send at (Pacific Time)</label>' +
            '<input id="annScheduleAt" type="datetime-local" style="height:32px; padding:0 10px; border:1px solid #E8E6DF; border-radius:4px; font-size:12.5px; font-family:inherit;">' +
          '</div>' +
          '<div id="annTestStrip" style="display:none; background:#eef6f1; border:1px solid #d8ebdf; border-radius:6px; padding:8px 12px; font-size:12.5px; color:#155724; font-family:' + MONO + ';"></div>' +
          '<div style="display:flex; align-items:center; gap:8px;">' +
            '<button id="annTestBtn" style="' + BTN2 + '">Send test to me</button>' +
            '<button id="annSendBtn" style="' + BTN1 + ' margin-left:auto; opacity:0.45; cursor:not-allowed;" disabled>Send &rarr;</button>' +
          '</div>' +
          '<div id="annSendHint" style="font-size:11px; color:#9CA3AF;">Send unlocks after a test email.</div>' +
        '</div>';
    }

    // ------------------------------------------------------------------ compose state

    function newDraft() {
        annState.current = null;
        annState.selectedUsers = [];
        setVal('annSubject', '');
        setVal('annDraft', '');
        var radios = document.getElementsByName('annAudience');
        for (var i = 0; i < radios.length; i++) radios[i].checked = radios[i].value === 'everLoggedIn';
        setChecked('annChEmail', true);
        setChecked('annChBanner', false);
        setChecked('annChWhatsNew', false);
        document.getElementById('annUserPicker').style.display = 'none';
        document.getElementById('annTestStrip').style.display = 'none';
        setText('annSaveStatus', '');
        setText('annAiStatus', '');
        renderPreviewHtml(null);
        renderUserChips();
        updateSendUi();
    }

    function loadIntoComposer(a, previewHtml) {
        annState.current = a;
        annState.selectedUsers = (a.specificUsers || []).slice();
        setVal('annSubject', a.subject || '');
        setVal('annDraft', a.draft || '');
        var radios = document.getElementsByName('annAudience');
        for (var i = 0; i < radios.length; i++) radios[i].checked = radios[i].value === (a.audienceType || 'everLoggedIn');
        setChecked('annChEmail', a.channelEmail !== false);
        setChecked('annChBanner', !!a.channelBanner);
        setChecked('annChWhatsNew', !!a.channelWhatsNew);
        document.getElementById('annUserPicker').style.display = a.audienceType === 'specific' ? '' : 'none';
        renderUserChips();
        renderPreviewHtml(previewHtml || null);
        if (a.testedRevision === a.revision && a.testedAt) {
            showTestStrip('✓ Test sent to ' + (a.testedBy || 'you') + ' · ' + fmtTime(a.testedAt));
        } else {
            document.getElementById('annTestStrip').style.display = 'none';
        }
        updateSendUi();
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    function setVal(id, v) { var el = document.getElementById(id); if (el) el.value = v; }
    function setText(id, v) { var el = document.getElementById(id); if (el) el.textContent = v; }
    function setChecked(id, v) { var el = document.getElementById(id); if (el) el.checked = v; }

    function gatherPatch() {
        var type = 'everLoggedIn';
        var radios = document.getElementsByName('annAudience');
        for (var i = 0; i < radios.length; i++) if (radios[i].checked) type = radios[i].value;
        return {
            id: annState.current ? annState.current.id : null,
            subject: (document.getElementById('annSubject') || {}).value || '',
            draft: (document.getElementById('annDraft') || {}).value || '',
            audienceType: type,
            specificUsers: annState.selectedUsers,
            channelEmail: !!(document.getElementById('annChEmail') || {}).checked,
            channelBanner: !!(document.getElementById('annChBanner') || {}).checked,
            channelWhatsNew: !!(document.getElementById('annChWhatsNew') || {}).checked
        };
    }

    function scheduleSave() {
        annState.savePending = true;
        if (annState.saveTimer) clearTimeout(annState.saveTimer);
        annState.saveTimer = setTimeout(saveNow, 800);
    }

    function saveNow() {
        if (annState.saveTimer) { clearTimeout(annState.saveTimer); annState.saveTimer = null; }
        var patch = gatherPatch();
        if (!patch.subject && !patch.draft) { annState.savePending = false; return Promise.resolve(null); }
        return api('', { method: 'POST', body: JSON.stringify(patch) }).then(function(r) {
            annState.savePending = false;
            if (!r.ok) {
                setText('annSaveStatus', '');
                if (r.status === 409) { appAlert(r.body.error || 'This announcement is read-only.', { title: 'Announcements' }); }
                return null;
            }
            annState.current = r.body.announcement;
            if (r.body.previewHtml) renderPreviewHtml(r.body.previewHtml);
            var el = document.getElementById('annSaveStatus');
            if (el) el.innerHTML = '&#10003; Draft saved ' + new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
            updateSendUi();
            refreshList();
            return r.body.announcement;
        });
    }

    function ensureSaved() {
        if (annState.savePending || !annState.current) return saveNow();
        return Promise.resolve(annState.current);
    }

    // ------------------------------------------------------------------ wiring

    function wireCompose() {
        ['annSubject', 'annDraft'].forEach(function(id) {
            document.getElementById(id).addEventListener('input', function() {
                scheduleSave();
                if (id === 'annSubject') updateSendUi();
            });
        });
        var radios = document.getElementsByName('annAudience');
        for (var i = 0; i < radios.length; i++) {
            radios[i].addEventListener('change', function() {
                document.getElementById('annUserPicker').style.display = this.value === 'specific' ? '' : 'none';
                scheduleSave();
                updateSendUi();
            });
        }
        ['annChEmail', 'annChBanner', 'annChWhatsNew'].forEach(function(id) {
            document.getElementById(id).addEventListener('change', scheduleSave);
        });

        document.getElementById('annWordsmithBtn').addEventListener('click', doWordsmith);
        document.getElementById('annGenerateBtn').addEventListener('click', doGenerate);
        document.getElementById('annTestBtn').addEventListener('click', doTest);
        document.getElementById('annSendBtn').addEventListener('click', doSend);

        var toggle = document.getElementById('annWhenToggle');
        toggle.addEventListener('click', function(e) {
            var t = e.target.getAttribute && e.target.getAttribute('data-when');
            if (!t) return;
            var spans = toggle.querySelectorAll('span[data-when]');
            for (var i = 0; i < spans.length; i++) {
                var on = spans[i].getAttribute('data-when') === t;
                spans[i].style.background = on ? '#fff' : 'transparent';
                spans[i].style.color = on ? '#0F1720' : '#6B7280';
                spans[i].style.boxShadow = on ? '0 1px 2px rgba(0,0,0,0.06)' : 'none';
            }
            document.getElementById('annScheduleRow').style.display = t === 'schedule' ? '' : 'none';
            updateSendUi();
        });

        // Subject-suggestion "more" + user picker
        var search = document.getElementById('annUserSearch');
        search.addEventListener('input', function() { renderUserResults(this.value); });
        search.addEventListener('focus', function() { renderUserResults(this.value); });
        document.addEventListener('click', function(e) {
            var res = document.getElementById('annUserResults');
            if (res && e.target !== search && !res.contains(e.target)) res.style.display = 'none';
        });
    }

    function whenMode() {
        var spans = document.querySelectorAll('#annWhenToggle span[data-when]');
        for (var i = 0; i < spans.length; i++) {
            if (spans[i].style.background === 'rgb(255, 255, 255)' || spans[i].style.background === '#fff') {
                return spans[i].getAttribute('data-when');
            }
        }
        return 'now';
    }

    function updateSendUi() {
        var a = annState.current;
        var btn = document.getElementById('annSendBtn');
        var hint = document.getElementById('annSendHint');
        if (!btn) return;
        var type = gatherPatch().audienceType;
        var n = recipientCountFor(type);
        var mode = whenMode();
        var tested = a && a.testedRevision === a.revision && a.bodyHtml;
        btn.innerHTML = mode === 'schedule'
            ? 'Schedule for ' + n + ' recipients &rarr;'
            : 'Send to ' + n + ' recipients &rarr;';
        btn.disabled = !tested;
        btn.style.opacity = tested ? '1' : '0.45';
        btn.style.cursor = tested ? 'pointer' : 'not-allowed';
        if (hint) {
            hint.textContent = tested
                ? 'Send unlocks after a test email — you already sent one.'
                : 'Send unlocks after a test email.';
        }
        var meta = document.getElementById('annPreviewMeta');
        if (meta) meta.textContent = 'To: ' + n + ' recipients · Cc: pdl-plm-admin';
    }

    function showTestStrip(text) {
        var strip = document.getElementById('annTestStrip');
        if (!strip) return;
        strip.style.display = '';
        strip.innerHTML = esc(text);
    }

    function renderPreviewHtml(html) {
        var stage = document.getElementById('annPreviewStage');
        if (!stage) return;
        if (!html) {
            stage.innerHTML = '<div style="text-align:center; color:#9CA3AF; font-size:12.5px; padding:80px 20px;">Generate the HTML email to see the preview.</div>';
            return;
        }
        // Server-rendered template — extract the body content so we don't nest <html> docs.
        var m = html.match(/<body[^>]*>([\s\S]*)<\/body>/i);
        stage.innerHTML = m ? m[1] : html;
    }

    // ------------------------------------------------------------------ AI actions

    function aiStatus(msg, ok) {
        var el = document.getElementById('annAiStatus');
        if (!el) return;
        el.style.color = ok === false ? '#B8342B' : '#1F8A4C';
        el.innerHTML = msg;
    }

    function doWordsmith() {
        var started = Date.now();
        aiStatus('&#8987; Wordsmithing&hellip;');
        ensureSaved().then(function(a) {
            if (!a) { aiStatus('Type a draft first', false); return; }
            api('/' + a.id + '/ai/wordsmith', { method: 'POST', body: '{}' }).then(function(r) {
                if (!r.ok) { aiStatus(esc(r.body.error || 'AI failed'), false); return; }
                setVal('annDraft', r.body.text || '');
                aiStatus('&#10003; Wordsmithed · ' + ((Date.now() - started) / 1000).toFixed(1) + 's');
                scheduleSave();
            });
        });
    }

    function doGenerate() {
        var started = Date.now();
        aiStatus('&#8987; Generating&hellip;');
        ensureSaved().then(function(a) {
            if (!a) { aiStatus('Type a draft first', false); return; }
            api('/' + a.id + '/ai/generate', { method: 'POST', body: '{}' }).then(function(r) {
                if (!r.ok) { aiStatus(esc(r.body.error || 'AI failed'), false); return; }
                annState.current = r.body.announcement;
                renderPreviewHtml(r.body.previewHtml);
                aiStatus('&#10003; Generated · ' + ((Date.now() - started) / 1000).toFixed(1) + 's');
                document.getElementById('annTestStrip').style.display = 'none';
                updateSendUi();
                fetchSubjectChips(a.id);
            });
        });
    }

    function fetchSubjectChips(id) {
        var wrap = document.getElementById('annSubjectChips');
        if (!wrap) return;
        wrap.innerHTML = '<span style="font-family:' + MONO + '; font-size:10px; letter-spacing:0.08em; text-transform:uppercase; color:#9CA3AF;">AI suggests</span>' +
                         '<span style="font-size:11.5px; color:#9CA3AF;">&#8987;</span>';
        api('/' + id + '/ai/subjects', { method: 'POST', body: '{}' }).then(function(r) {
            if (!r.ok || !r.body.subjects || !r.body.subjects.length) { wrap.innerHTML = ''; return; }
            var html = '<span style="font-family:' + MONO + '; font-size:10px; letter-spacing:0.08em; text-transform:uppercase; color:#9CA3AF;">AI suggests</span>';
            r.body.subjects.slice(0, 3).forEach(function(s) {
                html += '<span class="ann-subj-chip" data-subj="' + esc(s) + '" style="font-size:11.5px; color:#1a3a5c; background:#e8f0fe; border:1px solid #d3e2f7; border-radius:12px; padding:3px 10px; cursor:pointer;">' + esc(s) + '</span>';
            });
            html += '<span id="annSubjMore" style="font-size:11.5px; color:#6B7280; cursor:pointer;">&#8635; more</span>';
            wrap.innerHTML = html;
            wrap.querySelectorAll('.ann-subj-chip').forEach(function(chip) {
                chip.addEventListener('click', function() {
                    setVal('annSubject', this.getAttribute('data-subj'));
                    scheduleSave();
                    updateSendUi();
                });
            });
            var more = document.getElementById('annSubjMore');
            if (more) more.addEventListener('click', function() { fetchSubjectChips(id); });
        });
    }

    // ------------------------------------------------------------------ test + send

    function doTest() {
        ensureSaved().then(function(a) {
            if (!a) { appAlert('Nothing to test yet — write a draft and generate the email first.', { title: 'Announcements' }); return; }
            api('/' + a.id + '/test', { method: 'POST', body: '{}' }).then(function(r) {
                if (!r.ok) { appAlert(r.body.error || 'Test send failed.', { title: 'Announcements' }); return; }
                annState.current = r.body.announcement;
                showTestStrip('✓ Test sent to ' + r.body.sentTo + ' · ' + new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }));
                var btn = document.getElementById('annTestBtn');
                if (btn) btn.textContent = 'Send test to me again';
                updateSendUi();
            });
        });
    }

    function doSend() {
        var a = annState.current;
        if (!a) return;
        var mode = whenMode();
        var patch = gatherPatch();
        var n = recipientCountFor(patch.audienceType);
        var when = 'now';
        if (mode === 'schedule') {
            when = (document.getElementById('annScheduleAt') || {}).value || '';
            if (!when) { appAlert('Pick a date and time first.', { title: 'Announcements' }); return; }
        }
        var msg = mode === 'schedule'
            ? 'Schedule this announcement for ' + when.replace('T', ' ') + ' to ' + n + ' recipients (' + audienceLabel(patch.audienceType) + ')?'
            : 'Send this announcement NOW to ' + n + ' recipients (' + audienceLabel(patch.audienceType) + ')?\nCc pdl-plm-admin · logged to the activity log.';
        appConfirm(msg, { title: 'Announcements', okText: mode === 'schedule' ? 'Schedule' : 'Send' }).then(function(ok) {
            if (!ok) return;
            ensureSaved().then(function() {
                api('/' + a.id + '/send', { method: 'POST', body: JSON.stringify({ when: when }) }).then(function(r) {
                    if (!r.ok) { appAlert(r.body.error || 'Send failed.', { title: 'Announcements' }); return; }
                    refreshList();
                    if (mode === 'schedule') {
                        appAlert('Scheduled. Any PLM IT member can edit it until it sends.', { title: 'Announcements' });
                        annState.current = r.body.announcement;
                        updateSendUi();
                    } else {
                        var sent = r.body.sent, failed = r.body.failed;
                        var note = 'Sent to ' + sent + ' recipient' + (sent === 1 ? '' : 's') + (failed ? ' · ' + failed + ' failed (see history)' : '') + '.';
                        if (patch.channelWhatsNew) {
                            note += '\n\nReminder: add this to whats-new.js before the next deploy (the What’s New modal is build-time).';
                        }
                        appAlert(note, { title: 'Announcements' });
                        newDraft();
                    }
                });
            });
        });
    }

    // ------------------------------------------------------------------ audience data

    function refreshAudience() {
        api('/audience').then(function(r) {
            if (!r.ok) return;
            annState.audience = r.body;
            var el = document.getElementById('annCntEver');
            if (el) el.textContent = (r.body.everLoggedIn || 0) + ' users';
            el = document.getElementById('annCntDl');
            if (el) el.textContent = (r.body.dl || 0) + ' users';
            el = document.getElementById('annCntDlNote');
            if (el) el.textContent = 'incl. ' + (r.body.dlNeverLogged || 0) + ' who never logged in';
            updateSendUi();
        });
    }

    function renderUserChips() {
        var wrap = document.getElementById('annUserChips');
        if (!wrap) return;
        wrap.innerHTML = '';
        annState.selectedUsers.forEach(function(u) {
            var chip = document.createElement('span');
            chip.style.cssText = 'display:inline-flex; align-items:center; gap:5px; font-size:11.5px; color:#0F1720; background:#f3f3ee; border:1px solid #E8E6DF; border-radius:12px; padding:3px 6px 3px 10px; font-family:' + MONO + ';';
            chip.textContent = u;
            var x = document.createElement('span');
            x.textContent = '×';
            x.style.cssText = 'cursor:pointer; color:#6B7280; padding:0 3px;';
            x.onclick = function() {
                annState.selectedUsers = annState.selectedUsers.filter(function(s) { return s !== u; });
                renderUserChips();
                scheduleSave();
                updateSendUi();
            };
            chip.appendChild(x);
            wrap.appendChild(chip);
        });
    }

    function renderUserResults(q) {
        var box = document.getElementById('annUserResults');
        if (!box || !annState.audience) return;
        q = (q || '').toLowerCase();
        var hits = (annState.audience.users || []).filter(function(u) {
            if (annState.selectedUsers.indexOf(u.username) >= 0) return false;
            return !q || (u.username || '').toLowerCase().indexOf(q) >= 0 ||
                   (u.displayName || '').toLowerCase().indexOf(q) >= 0;
        }).slice(0, 12);
        if (!hits.length) { box.style.display = 'none'; return; }
        box.innerHTML = '';
        hits.forEach(function(u) {
            var row = document.createElement('div');
            row.style.cssText = 'padding:6px 10px; font-size:12.5px; cursor:pointer; display:flex; justify-content:space-between; gap:8px;';
            row.onmouseover = function() { row.style.background = '#f6f5f0'; };
            row.onmouseout = function() { row.style.background = ''; };
            var nm = document.createElement('span');
            nm.textContent = u.displayName || u.username;
            var id = document.createElement('span');
            id.textContent = u.username + (u.hasLoggedIn ? '' : ' · never logged in');
            id.style.cssText = 'font-family:' + MONO + '; font-size:11px; color:#9CA3AF;';
            row.appendChild(nm);
            row.appendChild(id);
            row.onclick = function() {
                annState.selectedUsers.push(u.username);
                renderUserChips();
                setVal('annUserSearch', '');
                box.style.display = 'none';
                scheduleSave();
                updateSendUi();
            };
            box.appendChild(row);
        });
        box.style.display = '';
    }

    // ------------------------------------------------------------------ history

    function refreshList() {
        api('').then(function(r) {
            if (!r.ok) return;
            annState.list = r.body.announcements || [];
            renderHistory();
        });
    }

    function statusChip(a) {
        var cfg = a.status === 'sent'
            ? { txt: 'Sent', fg: '#155724', bg: '#eef6f1', bd: '#d8ebdf', dot: '#1F8A4C' }
            : a.status === 'scheduled'
            ? { txt: 'Scheduled', fg: '#856404', bg: '#fff3cd', bd: '#ffe69c', dot: '#e0a800' }
            : { txt: 'Draft', fg: '#4a4f58', bg: '#f3f3ee', bd: '#E8E6DF', dot: '#9CA3AF' };
        return '<span style="display:inline-flex; align-items:center; gap:6px; font-family:' + MONO + '; font-size:10.5px; letter-spacing:0.06em; text-transform:uppercase; color:' + cfg.fg + '; background:' + cfg.bg + '; border:1px solid ' + cfg.bd + '; border-radius:10px; padding:2px 8px;">' +
               '<span style="width:6px; height:6px; border-radius:50%; background:' + cfg.dot + ';"></span>' + cfg.txt + '</span>';
    }

    var TH = 'text-align:left; padding:8px 16px; font-family:' + MONO + '; font-size:10px; letter-spacing:0.1em; text-transform:uppercase; color:#6B7280; font-weight:500; border-bottom:1px solid #E8E6DF;';
    var TD = 'padding:9px 16px; border-bottom:1px solid #f0efe9;';

    function renderHistory() {
        var body = document.getElementById('annHistBody');
        var counts = document.getElementById('annHistCounts');
        if (!body) return;
        var sent = 0, sched = 0;
        annState.list.forEach(function(a) {
            if (a.status === 'sent') sent++;
            if (a.status === 'scheduled') sched++;
        });
        if (counts) counts.textContent = sent + ' sent · ' + sched + ' scheduled';
        if (!annState.list.length) {
            body.innerHTML = '<div style="padding:16px; color:#9CA3AF; font-size:12.5px;">No announcements yet.</div>';
            return;
        }
        var html = '<table style="width:100%; border-collapse:collapse; font-size:12.5px; color:#0F1720;"><thead><tr>' +
            '<th style="' + TH + '">Date</th><th style="' + TH + '">Subject</th><th style="' + TH + '">Sender</th>' +
            '<th style="' + TH + '">Audience</th><th style="' + TH + ' text-align:right;">Recipients</th>' +
            '<th style="' + TH + '">Status</th><th style="' + TH + '"></th></tr></thead><tbody>';
        annState.list.forEach(function(a) {
            var when = a.status === 'scheduled' ? fmtDateTime(a.scheduledFor)
                     : a.status === 'sent' ? fmtDate(a.sentAt) : fmtDate(a.lastEditedAt || a.createdAt);
            var subline = '';
            if (a.status === 'scheduled') {
                subline = '<span style="display:block; font-size:11px; color:#9CA3AF; margin-top:2px;">last edited by ' +
                          esc(shortName(a.lastEditedByDisplay, a.lastEditedBy)) + ' · ' + esc(fmtTime(a.lastEditedAt)) +
                          ' · editable by PLM IT until it sends' +
                          (a.testedRevision !== a.revision ? ' · <span style="color:#B8342B;">needs a new test</span>' : '') + '</span>';
            }
            var actions = [];
            if (a.status === 'sent') {
                actions.push('<a href="#" data-act="open" data-id="' + a.id + '" style="font-size:12px; color:#4a6fa5;">Open as sent</a>');
                actions.push('<a href="#" data-act="dup" data-id="' + a.id + '" style="font-size:12px; color:#4a6fa5;">Duplicate</a>');
            } else if (a.status === 'scheduled') {
                actions.push('<a href="#" data-act="edit" data-id="' + a.id + '" style="font-size:12px; color:#4a6fa5;">Edit before send</a>');
                actions.push('<a href="#" data-act="hist" data-id="' + a.id + '" style="font-size:12px; color:#4a6fa5;">History</a>');
                actions.push('<a href="#" data-act="cancel" data-id="' + a.id + '" style="font-size:12px; color:#B8342B;">Cancel</a>');
            } else {
                actions.push('<a href="#" data-act="edit" data-id="' + a.id + '" style="font-size:12px; color:#4a6fa5;">Open draft</a>');
            }
            var recips = a.status === 'sent' ? String(a.sentCount || 0)
                       : String(recipientCountFor(a.audienceType));
            html += '<tr>' +
                '<td style="' + TD + ' font-family:' + MONO + '; font-size:11.5px; color:#6B7280; white-space:nowrap;">' + esc(when) + '</td>' +
                '<td style="' + TD + '"><span style="font-weight:600;">' + esc(a.subject || '(no subject)') + '</span>' + subline + '</td>' +
                '<td style="' + TD + ' color:#4a4f58;">' + esc(shortName(a.createdByDisplay, a.createdBy)) + '</td>' +
                '<td style="' + TD + ' color:#4a4f58;">' + esc(audienceLabel(a.audienceType)) + '</td>' +
                '<td style="' + TD + ' text-align:right; font-family:' + MONO + '; font-size:11.5px;">' + recips + '</td>' +
                '<td style="' + TD + '">' + statusChip(a) + '</td>' +
                '<td style="' + TD + ' text-align:right; white-space:nowrap;">' + actions.join(' &nbsp; ') + '</td>' +
                '</tr>';
        });
        html += '</tbody></table>';
        body.innerHTML = html;
        body.querySelectorAll('a[data-act]').forEach(function(link) {
            link.addEventListener('click', function(e) {
                e.preventDefault();
                historyAction(this.getAttribute('data-act'), this.getAttribute('data-id'));
            });
        });
    }

    function historyAction(act, id) {
        if (act === 'open') {
            api('/' + id).then(function(r) {
                if (!r.ok) return;
                openAsSentModal(r.body.announcement, r.body.archivedHtml);
            });
        } else if (act === 'dup') {
            api('/' + id).then(function(r) {
                if (!r.ok) return;
                var src = r.body.announcement;
                var copy = {
                    id: null,
                    subject: src.subject,
                    draft: src.draft,
                    bodyHtml: src.bodyHtml,
                    audienceType: src.audienceType,
                    specificUsers: src.specificUsers,
                    channelEmail: src.channelEmail,
                    channelBanner: src.channelBanner,
                    channelWhatsNew: src.channelWhatsNew
                };
                api('', { method: 'POST', body: JSON.stringify(copy) }).then(function(r2) {
                    if (!r2.ok) return;
                    loadIntoComposer(r2.body.announcement, r2.body.previewHtml);
                    refreshList();
                });
            });
        } else if (act === 'edit') {
            api('/' + id).then(function(r) {
                if (!r.ok) return;
                loadIntoComposer(r.body.announcement, r.body.previewHtml);
            });
        } else if (act === 'hist') {
            api('/' + id).then(function(r) {
                if (!r.ok) return;
                revisionModal(r.body.announcement);
            });
        } else if (act === 'cancel') {
            appConfirm('Cancel this scheduled send? It goes back to a draft.', { title: 'Announcements', okText: 'Cancel schedule', danger: true }).then(function(ok) {
                if (!ok) return;
                api('/' + id + '/schedule', { method: 'DELETE' }).then(function() { refreshList(); });
            });
        }
    }

    // ------------------------------------------------------------------ modals (admin-logs style overlay)

    function overlay(titleHtml, bodyNode) {
        var ov = document.createElement('div');
        ov.style.cssText = 'position:fixed; inset:0; background:rgba(15,23,32,0.55); z-index:20000; display:flex; align-items:center; justify-content:center; padding:24px;';
        var card = document.createElement('div');
        card.style.cssText = 'background:#fff; width:720px; max-width:95vw; max-height:88vh; border-radius:8px; border:1px solid #E8E6DF; box-shadow:0 10px 40px rgba(0,0,0,0.18); display:flex; flex-direction:column; overflow:hidden;';
        var head = document.createElement('div');
        head.style.cssText = 'display:flex; align-items:center; justify-content:space-between; padding:12px 18px; border-bottom:1px solid #E8E6DF;';
        head.innerHTML = '<span style="font-size:14px; font-weight:600; color:#0F1720;">' + titleHtml + '</span>';
        var x = document.createElement('button');
        x.innerHTML = '&times;';
        x.style.cssText = 'background:transparent; border:none; font-size:20px; color:#6B7280; cursor:pointer; padding:0 4px;';
        x.onclick = function() { document.body.removeChild(ov); };
        head.appendChild(x);
        var body = document.createElement('div');
        body.style.cssText = 'overflow-y:auto; padding:18px 20px; background:#FAFAF7;';
        body.appendChild(bodyNode);
        card.appendChild(head);
        card.appendChild(body);
        ov.appendChild(card);
        ov.addEventListener('click', function(e) { if (e.target === ov) document.body.removeChild(ov); });
        document.body.appendChild(ov);
    }

    function openAsSentModal(a, archivedHtml) {
        var wrap = document.createElement('div');
        var meta = document.createElement('div');
        meta.style.cssText = 'font-family:' + MONO + '; font-size:11px; color:#6B7280; margin-bottom:12px; line-height:1.7;';
        meta.textContent = 'Sent ' + fmtDateTime(a.sentAt) + ' by ' + shortName(a.createdByDisplay, a.sentBy) +
            ' · ' + audienceLabel(a.audienceType) + ' · ' + (a.sentCount || 0) + ' delivered' +
            (a.failedCount ? ' · ' + a.failedCount + ' failed' : '') + ' · Cc pdl-plm-admin';
        wrap.appendChild(meta);
        if (a.failures && a.failures.length) {
            var f = document.createElement('div');
            f.style.cssText = 'background:#fdeaea; border-left:4px solid #B8342B; border-radius:0 6px 6px 0; padding:8px 12px; font-size:12px; color:#721c24; margin-bottom:12px;';
            f.textContent = 'Failures: ' + a.failures.map(function(x) { return x.username + ' (' + x.reason + ')'; }).join(', ');
            wrap.appendChild(f);
        }
        var stage = document.createElement('div');
        stage.style.cssText = 'background:#ECEDEF; border:1px solid #E8E6DF; border-radius:8px; padding:18px;';
        if (archivedHtml) {
            var inner = document.createElement('div');
            var m = archivedHtml.match(/<body[^>]*>([\s\S]*)<\/body>/i);
            inner.innerHTML = m ? m[1] : archivedHtml;
            stage.appendChild(inner);
        } else {
            stage.innerHTML = '<div style="color:#9CA3AF; font-size:12.5px;">No archived HTML found.</div>';
        }
        wrap.appendChild(stage);
        overlay('Announcement &mdash; as sent', wrap);
    }

    function revisionModal(a) {
        var wrap = document.createElement('div');
        var intro = document.createElement('div');
        intro.style.cssText = 'font-size:12.5px; color:#4a4f58; margin-bottom:10px;';
        intro.textContent = 'Shared draft — any pdl-plm-admin member can edit until it sends. Every edit requires a fresh test email.';
        wrap.appendChild(intro);
        var tbl = document.createElement('table');
        tbl.style.cssText = 'width:100%; border-collapse:collapse; font-size:12.5px; background:#fff; border:1px solid #E8E6DF; border-radius:6px;';
        var rows = (a.revisions || []).slice().reverse();
        tbl.innerHTML = '<thead><tr><th style="' + TH + '">When</th><th style="' + TH + '">Who</th><th style="' + TH + '">What</th></tr></thead>' +
            '<tbody>' + rows.map(function(r) {
                return '<tr><td style="' + TD + ' font-family:' + MONO + '; font-size:11.5px; color:#6B7280; white-space:nowrap;">' + esc(fmtDateTime(r.at)) + '</td>' +
                       '<td style="' + TD + '">' + esc(shortName(r.byDisplay, r.by)) + '</td>' +
                       '<td style="' + TD + ' color:#4a4f58;">' + esc(r.note || '') + '</td></tr>';
            }).join('') + '</tbody>';
        wrap.appendChild(tbl);
        overlay('Edit history &mdash; ' + esc(a.subject || ''), wrap);
    }
})();
