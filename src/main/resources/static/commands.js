/**
 * Voice / text command recognizer for the toolkit's chat inputs.
 *
 * Hooks: wraps window.askHelp (help sidebar) and window.askSubmit (AI Eval Ask form).
 * Before the chat sends to the AI backend, we try to match the input against a
 * curated rule set. If matched → execute the action(s) in-page (switch tab, click
 * a button, fill a field, etc.) and skip the AI call. If not matched → fall through
 * to the original chat handler (the AI answers normally).
 *
 * Rules use simple regex matching for speed and predictability. Multi-step commands
 * are handled by splitting on " and " / " then " / "," and trying each fragment.
 *
 * Examples that work:
 *   - "go to BOM Race"
 *   - "show me the field changes tab"
 *   - "open BOM Explorer"
 *   - "I want to run a where used"
 *   - "export it" / "download this" / "save the file"
 *   - "start a standard race"
 *   - "go to BOM Race and start standard race and export"
 */
(function () {

    // --------------------------------------------------------------
    // Tab navigation map. Key = tab id used in switchTab(), value = list of phrases
    // (lowercase) that should route to that tab. First-match wins.
    // --------------------------------------------------------------
    var TABS = {
        'changes':       ['field changes', 'field change', 'changes tab', 'items tab', 'changes screen'],
        'bom':           ['bom explorer', 'bom explore', 'bom tab', 'explore bom', 'where used', 'where-used', 'whereused', 'implode', 'explode', 'bom explosion', 'bom implosion'],
        'parts':         ['part extract', 'parts extract', 'part lookup', 'parts tab'],
        'agile':         ['agile lookup', 'agile tab'],
        'sku':           ['sku lookup', 'sku tab'],
        'history':       ['change history', 'history tab', 'changehistory'],
        'reports':       ['utilities', 'reports tab', 'utilities tab'],
        'compare':       ['data compare', 'compare tab'],
        'extensions':    ['extensions', 'extensions tab'],
        'bomcompare':    ['bom compare', 'compare bom', 'compare boms'],
        'changereviews': ['change reviews'],
        'ecnreport':     ['ecn report', 'ecn tab'],
        'singlesole':    ['single source', 'sole source', 'single sole', 'single/sole'],
        'helpcenter':    ['work instructions', 'help center', 'helpcenter'],
        'permissions':   ['user management', 'permissions', 'access management'],
        'labs':          ['labs tab', 'open labs'],
        'bomrace':       ['bom race', 'race tab', 'race screen'],
        'adhealth':      ['ad health', 'ad health check', 'active directory health'],
        'aieval':        ['ai eval', 'ai evaluation', 'ai evals']
    };

    // Phrase patterns that indicate the user wants to navigate (followed by a tab phrase).
    var NAV_PREFIX = /^(?:please\s+)?(?:can\s+you\s+)?(?:go\s+to|open|show\s+me|take\s+me\s+to|switch\s+to|navigate\s+to|let'?s\s+go\s+to|i\s+want\s+to\s+(?:see|open|view|run|do)|get\s+me|bring\s+me\s+to|jump\s+to|view)\s+(?:the\s+)?/i;

    // Soft "keyword" map for the multi-language fallback. When a non-English voice
    // command doesn't hit the English NAV_PREFIX rules but DOES contain a known tab
    // noun (e.g. Malay "...butiran setiap bom" → contains "bom" → BOM Explorer), we
    // navigate. Word-boundary matched to avoid hijacking "bombast" or "race-track".
    // Sorted by length desc at runtime to prefer specific phrases over single words.
    var TAB_KEYWORDS = {
        'bom':           ['bom explorer', 'bom'],
        'bomrace':       ['bom race', 'race'],
        'parts':         ['part extract', 'parts'],
        'agile':         ['agile lookup', 'agile'],
        'sku':           ['sku lookup', 'sku'],
        'history':       ['change history', 'history'],
        'reports':       ['utilities', 'reports'],
        'compare':       ['data compare'],
        'extensions':    ['extensions'],
        'bomcompare':    ['bom compare'],
        'changereviews': ['change reviews'],
        'ecnreport':     ['ecn report', 'ecn'],
        'singlesole':    ['single source', 'sole source'],
        'helpcenter':    ['work instructions', 'helpcenter'],
        'permissions':   ['user management', 'permissions'],
        'labs':          ['labs'],
        'adhealth':      ['ad health'],
        'aieval':        ['ai eval', 'ai evaluation']
    };

    // English question words — short input containing one of these is treated as a
    // question, not a navigation. Non-English question words could be added per language
    // if they trip false navigations; for now most short non-English commands won't
    // include English interrogatives.
    var QUESTION_RE = /\b(?:what|how|why|when|where|who|which|tell\s+me|explain|describe)\b/i;

    // Action keywords for export-style intents (no tab change implied).
    // Anchored at the start of the fragment so questions like "what does export do?"
    // don't trip the action. Verb at start = action; verb anywhere else = ignored.
    var EXPORT_RE  = /^\s*(?:please\s+|just\s+|can\s+you\s+)?(?:export|download|save)\b/i;
    var EMAIL_RE   = /^\s*(?:please\s+|just\s+|can\s+you\s+)?(?:email|send)\b/i;
    // "start the race", "start a standard race", "run the race"
    var START_RACE_RE = /\b(?:start|run|fire|kick\s*off)\s+(?:the\s+|a\s+|an?\s+)?(?:standard|quick|stress)?\s*race\b/i;
    // "replay last", "replay the race", "play it again"
    var REPLAY_RE     = /\b(?:replay|play\s+again|play\s+it\s+again|do\s+it\s+again)\b/i;

    // Multi-step splitter: "go to X and start Y" → ["go to X", "start Y"].
    function splitMultiStep(text) {
        return text
            .split(/\s+(?:and\s+then|then|and|,)\s+/i)
            .map(function (s) { return s.trim(); })
            .filter(function (s) { return s.length > 0; });
    }

    // --------------------------------------------------------------
    // Recognizer — returns array of intents, or [] if nothing matched.
    // Each intent: { verb, target?, value?, raw? }
    // --------------------------------------------------------------
    /** Normalize common voice-transcription artifacts before pattern matching.
     *  "BOM" is routinely mis-heard as "bomb"; "ECN" as "EC and" / "easy and"; etc.
     *  Add more rules here as we hit them in the wild. */
    function normalizeForVoice(t) {
        return t
            .replace(/\bbomb\b/gi, 'bom')      // "bomb explorer" → "bom explorer"
            .replace(/\bbombs\b/gi, 'boms')
            .replace(/\bec\s+and\b/gi, 'ecn')   // "EC and report" → "ecn report"
            .replace(/\beasy\s+and\b/gi, 'ecn') // "easy and report" → "ecn report"
            .replace(/\bs\.?\s*k\.?\s*u\b/gi, 'sku')
            .replace(/\ba\.?\s*i\b/gi, 'ai')
            .replace(/\ba\.?\s*d\b/gi, 'ad');
    }

    function recognizeFragment(text) {
        if (!text) return null;
        var t = normalizeForVoice(text.trim().toLowerCase());
        if (!t) return null;

        // 1) Export / download / save intents (don't require a leading verb-phrase).
        if (EXPORT_RE.test(t))  return { verb: 'export',  raw: text };
        if (EMAIL_RE.test(t))   return { verb: 'email',   raw: text };
        if (REPLAY_RE.test(t) && /race|bom\s+race/i.test(t)) {
            return { verb: 'replay-race', raw: text };
        }
        if (START_RACE_RE.test(t)) {
            // Detect preset (quick/standard/stress) if mentioned
            var preset = null;
            if (/\bquick\b/.test(t))    preset = { n: 5,  label: 'Quick' };
            if (/\bstandard\b/.test(t)) preset = { n: 10, label: 'Standard' };
            if (/\bstress\b/.test(t))   preset = { n: 25, label: 'Stress' };
            return { verb: 'start-race', preset: preset, raw: text };
        }

        // 2) Tab navigation. Try with the NAV_PREFIX first, then plain.
        var stripped = t.replace(NAV_PREFIX, '').trim();
        // Also strip trailing "tab" / "screen" / "page" / "view" if present.
        stripped = stripped.replace(/\s+(?:tab|screen|page|view)$/i, '').trim();
        for (var tabId in TABS) {
            var phrases = TABS[tabId];
            for (var i = 0; i < phrases.length; i++) {
                var p = phrases[i];
                // Match if stripped equals the phrase OR the original text contains a strong nav prefix + the phrase.
                if (stripped === p || stripped.replace(/\s+/g, ' ') === p) {
                    return { verb: 'navigate', target: tabId, raw: text };
                }
                // Also catch "where used" / "explode" anywhere in nav-like sentences.
                if (NAV_PREFIX.test(text) && t.indexOf(p) !== -1) {
                    return { verb: 'navigate', target: tabId, raw: text };
                }
            }
        }
        // Special-case bare "where used" / "implode" / "explode" without nav prefix → BOM tab.
        if (/^(?:where[- ]?used|implode|explode|bom\s+explosion|bom\s+implosion)$/i.test(t)) {
            return { verb: 'navigate', target: 'bom', raw: text };
        }
        return null;
    }

    /** Soft keyword fallback for non-English / unstructured navigation requests.
     *  Fires only when:
     *    - The input is short enough to be a command (≤ 100 chars, ≤ 6 words).
     *    - It doesn't contain an English question word ("what", "how", etc.).
     *    - It doesn't end with "?".
     *    - It contains a known tab keyword (word-boundary matched).
     *  Bare single-word keywords (like 'parts', 'history') additionally require
     *  the input to be ≤ 3 words — otherwise a sentence like "show me parts
     *  changed last week" hijacks to the Parts tab just because 'parts' shows
     *  up. Multi-word phrases like 'change history' can match up to 6 words.
     *  This catches Malay "...butiran setiap bom", Mandarin "...BOM", Tamil
     *  sentences containing "BOM" — without eating English questions. */
    function recognizeKeyword(text) {
        var t = normalizeForVoice(text.trim().toLowerCase());
        if (!t) return null;
        if (t.length > 100) return null;
        if (t.indexOf('?') !== -1) return null;
        if (QUESTION_RE.test(t)) return null;
        var wordCount = t.split(/\s+/).filter(function (w) { return w.length > 0; }).length;
        if (wordCount > 6) return null;
        // Build a flat list of {tab, phrase} sorted by phrase length descending so
        // "bom race" wins over bare "bom" and "ai eval" wins over bare "ai".
        var all = [];
        for (var tab in TAB_KEYWORDS) {
            TAB_KEYWORDS[tab].forEach(function (p) { all.push({ tab: tab, phrase: p }); });
        }
        all.sort(function (a, b) { return b.phrase.length - a.phrase.length; });
        for (var i = 0; i < all.length; i++) {
            var p = all[i].phrase;
            var isSingleWord = p.indexOf(' ') === -1;
            // Bare single-word keywords only fire on very short inputs; bug
            // PT-31 came from "show me parts changed last week" matching the
            // bare 'parts' keyword and eating the user's question.
            if (isSingleWord && wordCount > 3) continue;
            // Word-boundary check — \b doesn't always work for non-ASCII so use a manual
            // check that's safe for tab keywords (which are ASCII).
            var re = new RegExp('(^|[^a-z0-9])' + p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') +
                                '([^a-z0-9]|$)', 'i');
            if (re.test(t)) return { verb: 'navigate', target: all[i].tab, raw: text };
        }
        return null;
    }

    function recognizeCommands(text) {
        if (!text) return [];
        var fragments = splitMultiStep(text);
        var intents = [];
        for (var i = 0; i < fragments.length; i++) {
            var intent = recognizeFragment(fragments[i]);
            if (intent) intents.push(intent);
        }
        // Single-fragment fallback: if the strict English-prefix rules didn't catch it,
        // try the soft keyword recognizer (catches non-English navigation commands).
        if (intents.length === 0 && fragments.length === 1) {
            var kw = recognizeKeyword(text);
            if (kw) return [kw];
        }
        if (intents.length === 0) return [];
        if (intents.length < fragments.length && fragments.length === 1) return [];
        return intents;
    }

    // --------------------------------------------------------------
    // Toast notification — small floating pill near top of viewport.
    // --------------------------------------------------------------
    function toast(msg, opts) {
        opts = opts || {};
        var t = document.createElement('div');
        t.className = 'cmdToast' + (opts.error ? ' cmdToast-error' : '');
        t.textContent = msg;
        document.body.appendChild(t);
        // Force reflow then animate in
        void t.offsetWidth;
        t.classList.add('cmdToast-show');
        setTimeout(function () {
            t.classList.remove('cmdToast-show');
            setTimeout(function () { if (t.parentNode) t.remove(); }, 300);
        }, opts.duration || 2500);
    }

    // --------------------------------------------------------------
    // Action dispatch — one verb at a time. Some actions need a delay
    // before the next step (e.g. switchTab triggers DOM re-render).
    // --------------------------------------------------------------
    function executeIntents(intents) {
        if (!intents.length) return false;
        // If the chain contains BOTH start-race AND export/email, defer the export until
        // race-done fires (the scoreboard with download buttons appears). Multi-step
        // splits don't normally wait for async work — this is a special-case for the
        // common "go to BOM Race, start a standard race, export the output files" flow.
        var hasStartRace = intents.some(function (x) { return x.verb === 'start-race'; });
        intents.forEach(function (x) {
            if (hasStartRace && (x.verb === 'export' || x.verb === 'email')) x.afterRace = true;
        });
        var i = 0;
        function next() {
            if (i >= intents.length) return;
            var intent = intents[i++];
            executeOne(intent);
            // Most actions need ~250-400ms before the next step's DOM is ready.
            setTimeout(next, 350);
        }
        next();
        return true;
    }

    /** Watch #brScoreboard for becoming visible (race-done revealed it), then run cb.
     *  Auto-disconnects after 5 minutes so it never leaks. */
    function whenRaceDone(cb) {
        var board = document.getElementById('brScoreboard');
        if (!board) { cb(false); return; }
        // Already visible? fire immediately.
        if (window.getComputedStyle(board).display !== 'none') { cb(true); return; }
        var done = false;
        var obs = new MutationObserver(function () {
            if (done) return;
            if (window.getComputedStyle(board).display !== 'none') {
                done = true;
                obs.disconnect();
                clearTimeout(timeoutId);
                // Tiny grace period so the download buttons render inside the card.
                setTimeout(function () { cb(true); }, 200);
            }
        });
        obs.observe(board, { attributes: true, attributeFilter: ['style'] });
        var timeoutId = setTimeout(function () {
            if (done) return;
            done = true;
            obs.disconnect();
            cb(false);
        }, 5 * 60 * 1000);
    }

    function executeOne(intent) {
        switch (intent.verb) {
            case 'navigate': {
                var label = humanTabLabel(intent.target);
                toast('Opening ' + label + '\u2026');
                if (typeof window.switchTab === 'function') window.switchTab(intent.target);
                break;
            }
            case 'export': {
                if (intent.afterRace) {
                    toast('Will export when the race finishes\u2026', { duration: 3500 });
                    whenRaceDone(function (ok) {
                        if (!ok) { toast('Race didn\u2019t finish in time \u2014 export skipped.', { error: true }); return; }
                        var n = clickAllExportButtons();
                        toast(n > 0 ? 'Downloading ' + n + ' file' + (n === 1 ? '' : 's') + '\u2026'
                                    : 'Race finished but no download buttons visible.', { error: n === 0 });
                    });
                } else {
                    var n = clickAllExportButtons();
                    toast(n > 0 ? 'Downloading ' + n + ' file' + (n === 1 ? '' : 's') + '\u2026'
                                : 'No export button visible on this screen.', { error: n === 0 });
                }
                break;
            }
            case 'email': {
                if (intent.afterRace) {
                    toast('Will email when the race finishes\u2026', { duration: 3500 });
                    whenRaceDone(function (ok) {
                        if (!ok) { toast('Race didn\u2019t finish in time \u2014 email skipped.', { error: true }); return; }
                        var sent = clickFirstButtonMatching(/email|send\s+to\s+me/i);
                        toast(sent ? 'Emailing\u2026' : 'No email/send button visible on this screen.', { error: !sent });
                    });
                } else {
                    var found2 = clickFirstButtonMatching(/email|send\s+to\s+me/i);
                    toast(found2 ? 'Emailing\u2026' : 'No email/send button visible on this screen.', { error: !found2 });
                }
                break;
            }
            case 'start-race': {
                if (typeof window.switchTab === 'function') window.switchTab('bomrace');
                if (intent.preset) {
                    setTimeout(function () {
                        if (typeof window.brApplyPreset === 'function') {
                            window.brApplyPreset(intent.preset.n, intent.preset.label);
                        }
                        setTimeout(function () {
                            var btn = document.getElementById('brStart');
                            if (btn) btn.click();
                            toast('Starting ' + (intent.preset ? intent.preset.label + ' \u00b7 N=' + intent.preset.n : 'race') + '\u2026');
                        }, 200);
                    }, 400);
                } else {
                    setTimeout(function () {
                        var btn = document.getElementById('brStart');
                        if (btn) btn.click();
                        toast('Starting race\u2026');
                    }, 400);
                }
                break;
            }
            case 'replay-race': {
                if (typeof window.switchTab === 'function') window.switchTab('bomrace');
                setTimeout(function () {
                    var btn = document.getElementById('brReplay');
                    if (btn && !btn.disabled) { btn.click(); toast('Replaying last race\u2026'); }
                    else toast('No replay available \u2014 run a race first.', { error: true });
                }, 400);
                break;
            }
        }
    }

    /** Click EVERY visible export-style button or link on the active screen.
     *  Returns the number clicked. Used by the "export" verb so that "the output
     *  FILES" plural triggers both the Input items + Race results downloads. */
    function clickAllExportButtons() {
        var EXPORT_TXT = /^(?:\u2B07\s*)?(?:export|download|save)\b/i;
        var els = document.querySelectorAll('button, a');
        var clicked = 0;
        // Stagger clicks slightly so the browser's "site is trying to download
        // multiple files" prompt doesn't suppress later ones.
        var queue = [];
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (!isVisible(el)) continue;
            var txt = (el.textContent || '').trim();
            if (EXPORT_TXT.test(txt)) queue.push(el);
        }
        queue.forEach(function (el, idx) {
            setTimeout(function () { el.click(); }, idx * 250);
            clicked++;
        });
        return clicked;
    }

    function clickFirstButtonMatching(re) {
        // Get every visible button + anchor on the page, filter by visibility + text match.
        var els = document.querySelectorAll('button, a');
        var found = null;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (!isVisible(el)) continue;
            var txt = (el.textContent || '').trim();
            if (re.test(txt)) { found = el; break; }
        }
        if (found) {
            found.click();
            return true;
        }
        return false;
    }

    function isVisible(el) {
        if (!el) return false;
        if (el.disabled) return false;
        var rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return false;
        var style = window.getComputedStyle(el);
        return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
    }

    function humanTabLabel(tabId) {
        var labels = {
            changes: 'Field Changes', bom: 'BOM Explorer', parts: 'Part Extract',
            agile: 'Agile Lookup', sku: 'SKU Lookup', history: 'Change History',
            reports: 'Utilities', compare: 'Data Compare', extensions: 'Extensions',
            bomcompare: 'BOM Compare', changereviews: 'Change Reviews',
            ecnreport: 'ECN Dashboard', singlesole: 'Single/Sole Source',
            helpcenter: 'Work Instructions', permissions: 'User Management',
            labs: 'Labs', bomrace: 'BOM Race', adhealth: 'AD Health', aieval: 'AI Eval'
        };
        return labels[tabId] || tabId;
    }

    // --------------------------------------------------------------
    // Wrap the chat submit handlers. If the input matches a command,
    // dispatch it and skip the AI call. Otherwise let the original
    // handler do its thing.
    // --------------------------------------------------------------
    function tryDispatch(inputId) {
        var inp = document.getElementById(inputId);
        if (!inp) return false;
        var text = (inp.value || '').trim();
        if (!text) return false;
        var intents = recognizeCommands(text);
        if (!intents.length) return false;
        // Clear the input so the user can issue another command.
        inp.value = '';
        executeIntents(intents);
        return true;
    }

    // Patch askHelp + askSubmit. If they're not yet defined when commands.js
    // first runs, retry on DOMContentLoaded; if still not defined, keep waiting
    // (help sidebar is created lazily on first open).
    function patchHandlers() {
        if (typeof window.askHelp === 'function' && !window.askHelp._cmdWrapped) {
            var origHelp = window.askHelp;
            window.askHelp = function () {
                if (tryDispatch('helpInput')) return;
                return origHelp.apply(this, arguments);
            };
            window.askHelp._cmdWrapped = true;
        }
        if (typeof window.askSubmit === 'function' && !window.askSubmit._cmdWrapped) {
            var origAsk = window.askSubmit;
            window.askSubmit = function () {
                if (tryDispatch('askQuestion')) return;
                return origAsk.apply(this, arguments);
            };
            window.askSubmit._cmdWrapped = true;
        }
    }
    document.addEventListener('DOMContentLoaded', patchHandlers);
    // Help sidebar creates its handlers lazily — re-attempt periodically until both are wrapped.
    var attempts = 0;
    var iv = setInterval(function () {
        patchHandlers();
        attempts++;
        if ((window.askHelp && window.askHelp._cmdWrapped &&
             window.askSubmit && window.askSubmit._cmdWrapped) || attempts > 60) {
            clearInterval(iv);
        }
    }, 500);
})();
