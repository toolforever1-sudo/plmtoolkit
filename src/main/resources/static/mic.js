/**
 * Mic input helper — uses the browser-native Web Speech API
 * (webkitSpeechRecognition) to transcribe speech into a text input/textarea.
 *
 * Markup:
 *   <button type="button" class="micBtn"
 *     onclick="micToggle('myInputId', this, '#optionalSubmitBtnSelector')">🎤</button>
 *
 * UX:
 *   - Click to start, click again to stop.
 *   - When `submitBtnSelector` is provided AND the transcript passes a lightweight
 *     "makes sense" gate, the second click also clicks that submit button — saves
 *     a hop. Without the third arg, the legacy behavior (user still clicks Ask)
 *     applies; calls existing in the wild stay backward-compatible.
 *   - While recording: button gets .micBtn-active (red pulse), a "Listening…" pill
 *     appears, and a 5-bar audio level meter inside the pill dances with your
 *     speech so you can SEE the mic is picking you up.
 *   - Transcript streams into the input as the user speaks.
 *
 * Security context:
 *   Chrome/Edge require a secure context (HTTPS or localhost) for the API to work.
 *   Plain http on intranet hostnames blocks start() with "not-allowed". When that's
 *   the case we mark the button as disabled with a tooltip explaining why — instead
 *   of looking dead.
 */
(function () {
    var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    var SUPPORTED = !!SR;
    // window.isSecureContext is true on https:// and on http://localhost/127.0.0.1.
    // On http://intranet.example.com it's false → SR.start() will throw 'not-allowed'.
    var SECURE = !!window.isSecureContext;
    var active = null; // { recognition, btn, inputEl, baseValue, statusEl }

    // Languages offered in the per-mic picker. BCP-47 codes per Web Speech API.
    // Add entries here to expand the list — Chrome supports 100+ langs.
    var MIC_LANGS = [
        { code: 'en-US', label: 'EN' },
        { code: 'zh-CN', label: '\u4E2D' },        // Mandarin (Simplified)
        { code: 'ms-MY', label: 'MY' },             // Malay (Malaysia)
        { code: 'hi-IN', label: '\u0939\u093F' },   // Hindi (हि)
        { code: 'bn-IN', label: '\u09AC\u0982' },   // Bengali (বং)
        { code: 'ta-IN', label: '\u0BA4\u0BAE\u0BBF' }, // Tamil (தமி)
        { code: 'te-IN', label: '\u0C24\u0C46' },   // Telugu (తె)
        { code: 'kn-IN', label: '\u0C95\u0CA8' },   // Kannada (ಕನ)
        { code: 'he-IL', label: '\u05E2\u05D1' },   // Hebrew (עב)
        { code: 'ja-JP', label: '\u65E5' }          // Japanese
    ];
    var LANG_KEY = 'plmtMicLang';

    function getCurrentLang() {
        try { return localStorage.getItem(LANG_KEY) || 'en-US'; }
        catch (e) { return 'en-US'; }
    }
    function setCurrentLang(code) {
        try { localStorage.setItem(LANG_KEY, code); } catch (e) {}
        // Sync any other .micLang selects on the page so they show the same value.
        document.querySelectorAll('select.micLang').forEach(function (s) {
            if (s.value !== code) s.value = code;
        });
    }

    /** Populate any empty .micLang <select> on the page with the language options
     *  and set its value to the user's saved preference. Idempotent — safe to call
     *  multiple times (e.g. after the help sidebar mounts its dynamic markup). */
    function populateMicLangSelects() {
        var saved = getCurrentLang();
        document.querySelectorAll('select.micLang').forEach(function (sel) {
            if (sel.options.length === 0) {
                MIC_LANGS.forEach(function (l) {
                    var o = document.createElement('option');
                    o.value = l.code; o.textContent = l.label;
                    o.title = l.code;
                    sel.appendChild(o);
                });
            }
            sel.value = saved;
            // Avoid double-binding — set a flag once.
            if (!sel.dataset.micBound) {
                sel.addEventListener('change', function () { setCurrentLang(this.value); });
                sel.dataset.micBound = '1';
            }
        });
    }
    // Expose so dynamic markup (help-sidebar) can refresh after mounting.
    window.micRefresh = populateMicLangSelects;
    document.addEventListener('DOMContentLoaded', populateMicLangSelects);

    function disableAllMicButtons(reason) {
        document.querySelectorAll('.micBtn').forEach(function (b) {
            b.classList.add('micBtn-disabled');
            b.title = reason;
            b.setAttribute('aria-disabled', 'true');
        });
    }

    if (!SUPPORTED) {
        document.addEventListener('DOMContentLoaded', function () {
            disableAllMicButtons('Mic input not supported in this browser (try Chrome, Edge, or Safari).');
        });
        window.micToggle = function (id, btn) {
            if (btn) flashTip(btn, 'Mic input not supported in this browser');
        };
        return;
    }

    if (!SECURE) {
        // Don't hide — disable visibly with a tooltip explaining why.
        document.addEventListener('DOMContentLoaded', function () {
            disableAllMicButtons('Mic requires HTTPS — currently on http:// (browser blocks SpeechRecognition outside secure contexts).');
        });
        window.micToggle = function (id, btn) {
            if (btn) flashTip(btn, 'Mic blocked: page must be served over HTTPS');
        };
        return;
    }

    /** Briefly attach a small floating tip just above the button, auto-removes after 3s.
     *  Used for one-shot error feedback when start() fails or the API is unsupported.
     *  Uses position:fixed so it works regardless of the button parent's positioning. */
    function flashTip(btn, msg) {
        var existing = document.querySelector('.micFlashTip');
        if (existing) existing.remove();
        var tip = document.createElement('div');
        tip.className = 'micFlashTip';
        tip.style.cssText = 'position:fixed; background:#2c3e50; color:#fff; padding:5px 10px; border-radius:4px; font-size:11.5px; font-weight:500; box-shadow:0 2px 8px rgba(0,0,0,0.25); z-index:9999; max-width:280px; white-space:normal; line-height:1.4; pointer-events:none;';
        tip.textContent = msg;
        document.body.appendChild(tip);
        var rect = btn.getBoundingClientRect();
        // Anchor above the button, right-aligned to it
        tip.style.left = Math.max(8, rect.right - 280) + 'px';
        tip.style.top = Math.max(8, rect.top - tip.offsetHeight - 6) + 'px';
        setTimeout(function () { if (tip.parentNode) tip.remove(); }, 3000);
    }

    /** Show or hide the "Listening…" pill (with 5-bar audio meter) next to the input.
     *  Includes the active language code so the user can verify they picked the right
     *  one before speaking. The meter is created inert; startMeter() drives it once
     *  the parallel getUserMedia stream is up. */
    function setStatus(inputEl, on) {
        if (!inputEl || !inputEl.parentNode) return null;
        var existing = inputEl.parentNode.querySelector('.micStatus');
        if (on) {
            if (existing) return existing;
            var s = document.createElement('span');
            s.className = 'micStatus';
            // Label + bar container, inline-flex so they sit beside each other.
            s.style.cssText = 'display:inline-flex; align-items:center; gap:8px;';
            var lbl = document.createElement('span');
            lbl.textContent = 'Listening (' + getCurrentLang() + ')\u2026';
            s.appendChild(lbl);
            var bars = document.createElement('span');
            bars.className = 'micBars';
            bars.style.cssText = 'display:inline-flex; align-items:flex-end; gap:2px; height:18px;';
            for (var i = 0; i < 5; i++) {
                var b = document.createElement('span');
                b.style.cssText = 'display:inline-block; width:3px; height:4px; background:#4a6fa5; border-radius:1px; transition:height 60ms linear;';
                bars.appendChild(b);
            }
            s.appendChild(bars);
            var hint = document.createElement('span');
            hint.style.cssText = 'opacity:0.7;';
            hint.textContent = 'click mic to stop';
            s.appendChild(hint);
            inputEl.parentNode.appendChild(s);
            return s;
        }
        if (existing) existing.remove();
        return null;
    }

    /**
     * Drive the 5-bar meter inside the Listening pill using ONLY events from
     * SpeechRecognition itself — `onsoundstart` / `onresult` / `onsoundend`.
     * No parallel getUserMedia stream is opened, so we can't accidentally
     * starve SR's internal mic capture on Chrome (which is what burned us
     * on 2026-05-12 when an earlier "raw audio level" version of this meter
     * caused audio to stop reaching SR on some hardware).
     *
     * The bars represent "SR is hearing speech right now" rather than raw
     * volume — which is what users actually want to know.
     */
    function startMeter(session) {
        if (!session || !session.statusEl) return;
        var bars = session.statusEl.querySelectorAll('.micBars > span');
        if (!bars || bars.length === 0) return;

        var IDLE_H = 4, ACTIVE_MAX_H = 18;
        var lastActivityMs = 0;

        // Per-bar slight phase offset so they don't move in lockstep —
        // looks like a meter, not like a single bar duplicated 5 times.
        var phaseOffsets = [0, 0.6, 1.2, 0.4, 0.9];

        function setBars(level) {
            // level is 0..1. Animate each bar with its own phase-shifted sine
            // around the level so the meter "breathes" while audio is active.
            var now = performance.now() / 1000;
            for (var i = 0; i < bars.length; i++) {
                var jitter = Math.sin(now * 9 + phaseOffsets[i]) * 0.15;
                var l = Math.max(0, Math.min(1, level + jitter));
                bars[i].style.height = (IDLE_H + Math.round(l * (ACTIVE_MAX_H - IDLE_H))) + 'px';
            }
        }

        function flattenBars() {
            for (var i = 0; i < bars.length; i++) bars[i].style.height = IDLE_H + 'px';
        }

        // Decay loop: once activity stops, smoothly drop bars back to idle.
        // Without this, the bars would just stick at the last animated frame.
        function tick() {
            if (!active || active !== session || !session.meter) return;
            var elapsed = performance.now() - lastActivityMs;
            if (elapsed < 250) {
                // Recent activity — animate at "high" level.
                setBars(0.8);
            } else if (elapsed < 1500) {
                // Decaying — taper bar height toward idle.
                var t = 1 - ((elapsed - 250) / 1250);
                setBars(0.8 * t);
            } else {
                flattenBars();
            }
            session.meter.raf = requestAnimationFrame(tick);
        }

        session.meter = {
            raf: requestAnimationFrame(tick),
            bump: function () { lastActivityMs = performance.now(); }
        };
    }

    function stopMeter(session) {
        if (!session || !session.meter) return;
        try { cancelAnimationFrame(session.meter.raf); } catch (e) {}
        session.meter = null;
    }

    /**
     * Lightweight "did the user actually say something usable?" check, run before
     * auto-submitting. We deliberately keep this client-side and dumb — the heavy
     * gibberish check is for the bug-report queue, not every help question. A real
     * mis-speak just leaves the input populated for the user to edit. */
    function transcriptLooksUsable(text) {
        if (!text) return false;
        var t = text.trim();
        if (t.length < 4) return false;
        // At least one letter — avoids "..." / "1234" auto-submitting.
        if (!/[a-zA-Z\u00C0-\u017F\u0400-\u04FF\u0590-\u05FF\u0600-\u06FF\u0900-\u097F\u4E00-\u9FFF]/.test(t)) return false;
        // Reject pure single-char repetition like "a a a a".
        var tokens = t.toLowerCase().split(/\s+/).filter(Boolean);
        if (tokens.length >= 2) {
            var first = tokens[0];
            var allSame = tokens.every(function (x) { return x === first; });
            if (allSame && first.length <= 2) return false;
        }
        return true;
    }

    window.micToggle = function (inputId, btnEl, submitBtnSelector) {
        if (active) {
            try { active.recognition.stop(); } catch (e) {}
            return;
        }
        var inputEl = document.getElementById(inputId);
        if (!inputEl) return;

        var rec = new SR();
        rec.lang = getCurrentLang();
        rec.continuous = true;
        rec.interimResults = true;

        var baseValue = inputEl.value;
        var finalChunk = '';

        rec.onresult = function (ev) {
            var interim = '';
            for (var i = ev.resultIndex; i < ev.results.length; i++) {
                var r = ev.results[i];
                if (r.isFinal) finalChunk += r[0].transcript;
                else            interim += r[0].transcript;
            }
            var combined = (baseValue ? baseValue + (baseValue.match(/\s$/) ? '' : ' ') : '') +
                           (finalChunk + interim).trim();
            inputEl.value = combined;
            if (inputEl.tagName === 'TEXTAREA') inputEl.scrollTop = inputEl.scrollHeight;
            // Bump the meter — bars stay "active" for ~250ms after each result.
            if (active && active.meter) active.meter.bump();
        };

        // Bar activity driven by SR's own audio-detection events. Each event
        // bumps the meter's lastActivity timestamp; the tick loop decays bars
        // back to idle after ~1.5s of silence. No parallel mic stream needed.
        rec.onsoundstart  = function () { if (active && active.meter) active.meter.bump(); };
        rec.onspeechstart = function () { if (active && active.meter) active.meter.bump(); };

        rec.onerror = function (ev) {
            console.warn('[mic] recognition error:', ev.error);
            var msg = 'Mic error: ' + (ev.error || 'unknown');
            if (ev.error === 'not-allowed')      msg = 'Microphone permission denied — check the address-bar mic icon.';
            else if (ev.error === 'no-speech')    msg = 'Didn\u2019t catch that — try again.';
            else if (ev.error === 'audio-capture') msg = 'No microphone found.';
            else if (ev.error === 'network')      msg = 'Speech service unreachable — likely network/firewall blocks Google\u2019s endpoint.';
            if (btnEl) flashTip(btnEl, msg);
        };

        rec.onend = function () {
            var session = active;
            active = null;
            stopMeter(session);
            if (btnEl) {
                btnEl.classList.remove('micBtn-active');
                btnEl.title = 'Speak your message';
            }
            setStatus(inputEl, false);

            // Auto-submit if the caller wired a submit button AND the transcript
            // looks usable. Use the post-flush value of the input (rec fires its
            // last onresult before onend, so finalChunk is up to date).
            if (submitBtnSelector && transcriptLooksUsable(inputEl.value)) {
                var submitBtn = document.querySelector(submitBtnSelector);
                if (submitBtn && !submitBtn.disabled) {
                    // Slight defer so any focus/blur cascade from setStatus removal
                    // settles before the click handler fires (some submit handlers
                    // read document.activeElement).
                    setTimeout(function () { try { submitBtn.click(); } catch (e) {} }, 0);
                    return;
                }
            }
            try { inputEl.focus(); } catch (e) {}
        };

        try {
            rec.start();
            var statusEl = setStatus(inputEl, true);
            active = {
                recognition: rec, btn: btnEl, inputEl: inputEl,
                baseValue: baseValue, statusEl: statusEl, meter: null
            };
            startMeter(active);
            if (btnEl) {
                btnEl.classList.add('micBtn-active');
                btnEl.title = 'Listening\u2026 click to stop';
            }
        } catch (e) {
            console.warn('[mic] start failed:', e);
            if (btnEl) flashTip(btnEl, 'Mic start failed: ' + (e.message || e.name || 'unknown'));
            active = null;
        }
    };
})();
