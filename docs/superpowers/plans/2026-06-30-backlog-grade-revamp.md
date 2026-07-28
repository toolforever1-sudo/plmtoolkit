# Backlog Grade Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the read-only "Backlog grade" drawer in Meeting Mode with an explainable, **disputable** grade that re-computes live, verifies disputes against the ECN's Agile record / an uploaded screenshot via a real LLM (with a deterministic fallback), changes grading logic at run time when justified, emails on every accepted action, and keeps a persisted audit trail.

**Architecture:** Frontend is vanilla-JS in `meeting-mode.js` (state `S`, handler map `H`, full-innerHTML `render()`). We port prototype option **2a** into that idiom as a centered modal. Pure scoring + the deterministic dispute evaluator are extracted into a new `grade-logic.js` UMD module (browser global `window.GradeLogic` + CommonJS export) so they can be unit-tested under `node --test`, mirroring `imsreview-classify.js`. New backend endpoints live under `/api/meeting/grade/*`, persisted as JSON files via a new `GradeStorageService` (mirrors `MeetingStorageService`: atomic temp+rename, Jackson). Email reuses `MeetingEmailService`'s SMTP pattern; attachments reuse `FileArchiveService`; the LLM evaluator calls `PortkeyClient` (same path as `AiEvalController`) and falls back to a Java port of the deterministic evaluator.

**Tech Stack:** Java 11 / Spring Boot 1.0.1 (no DB — file-based JSON), javax.mail SMTP (`mailrelay.sandisk.com:25`), Portkey gateway via `PortkeyClient`, vanilla ES5 JS, `node:test` for JS units, JUnit for Java.

---

## Conventions (read once)

- **JS units:** pure module `grade-logic.js`, no `window`/`document` refs, dual export (copy the IIFE wrapper from `imsreview-classify.js`). Tests in `test/js/grade-logic.test.js`. Run: `node --test test/js/*.test.js`.
- **Render layer:** HTML-render functions in `meeting-mode.js` are verified **manually** against `http://localhost:8090` (per `test/js/README.md`), not unit-tested. Steps for those show the key markup/handlers and a manual verification checklist.
- **Java units:** JUnit in `src/test/java/...`. Run a single test: `cd ~/git/plm-field-tracker && mvn -q -Dtest=GradeStorageServiceTest test`.
- **Local run after build:** `cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties` → http://localhost:8090. Login `plmadmin` / (password in Claude private memory). Agile-service (8081) is NOT running locally, so the LLM DB-verify path will exercise the **deterministic fallback** locally — that's expected and must work.
- **Identity on the server:** `request.getSession().getAttribute("username")` = actorAd; `"displayName"`; `"isPlmAdmin"`. Copy the `currentAd(...)` helper pattern from `MeetingController`.
- **Commit policy (CLAUDE.md):** only stage `.java`, `.js`, `.properties`, `.md`, `.bat` that we actually changed — no IDE/OS files.
- **Pre-build (CLAUDE.md):** update `src/main/resources/static/whats-new.js` (`WHATS_NEW_RELEASES`, new entry at top) before `mvn package`.
- **Never put credentials/PII in emails** (CLAUDE.md plm-field-tracker rule).

---

## Data shapes (single source of truth — referenced by many tasks)

```js
// rule config (runtime-mutable). Default = current behavior.
rule = { graceDays: 0, includeNoOwner: true, missingPenalty: 6 }

// computeGrade input "counts" (derived from S.items, see Task 2):
counts = {
  total,            // active (non-closed) item count, e.g. 65
  overdue,          // [{id,title,meta,status,daysOverdue,desc,kind:'overdue'}]
  unownedHi,        // [{id,title,meta,status,desc,kind:'noowner'}]
  soon,             // count only (momentum, no penalty)
  missingCount,     // count of items with no UAT or no Go-Live
  onTrackBase       // items neither overdue nor unowned-hi (before rule shifts)
}

// computeGrade output:
{ letter, score, penalty, atRiskN, summary,
  drivers:[{key,label,count,pct,color,items}],  // ontrack|overdue|noowner|soon|missing
  trend:{arrow,text,color,bg} }                  // vs last review
```

```java
// GradeRecord (one JSON file per record under ./data/grade-disputes or grade-logic-changes)
class GradeRecord {
  String id;                 // "gd_" / "gl_" + epoch + rand
  String kind;               // "item" | "rule"
  String sessionId;          // meeting session id if live, else ""
  String targetEcn;          // for kind=item
  String ruleKey;            // "overdue" | "noowner" | "missing" for kind=rule
  String summary;            // human one-liner
  String detail;             // e.g. "overdue grace 0d -> 7d"
  String userReason;
  String verifiedBy;         // "agile_record" | "attachment" | "cited_evidence"
  String fieldQuote;         // quoted Agile field value when verifiedBy=agile_record
  String attachmentRef;      // FileArchive id when verifiedBy=attachment
  String aiReason;           // model/heuristic decision text
  String gradeBefore, gradeAfter;   // letters
  int    scoreBefore, scoreAfter;
  String actorAd, actorName;
  String emailedTo;
  long   createdAt;          // epoch millis
  String ruleConfigJson;     // for kind=rule: serialized resulting rule, for revert/replay
  boolean reverted;          // rule changes only
}

// OutboxEntry (JSONL append under ./data/grade-outbox/<sessionOrGlobal>.jsonl)
class OutboxEntry { String id, to, subject; List<String> body; long sentAt; boolean it; }

// EvaluateResult (LLM or fallback)
class EvaluateResult { String status; /* accept|need_file|reject */ String verifiedBy, fieldQuote, reason; List<String> evidence; }
```

---

## File Structure

**Create:**
- `src/main/resources/static/grade-logic.js` — pure scoring + deterministic evaluator (UMD).
- `test/js/grade-logic.test.js` — node:test units for the above.
- `src/main/java/com/sandisk/plm/tracker/service/GradeModels.java` — `GradeRecord`, `OutboxEntry`, `EvaluateResult`, `RuleConfig`.
- `src/main/java/com/sandisk/plm/tracker/service/GradeStorageService.java` — JSON persistence (mirror `MeetingStorageService`).
- `src/main/java/com/sandisk/plm/tracker/service/GradeEvaluatorService.java` — LLM call via `PortkeyClient` + deterministic Java fallback.
- `src/main/java/com/sandisk/plm/tracker/service/GradeEmailService.java` — admin/IT emails (reuse SMTP from `MeetingEmailService`).
- `src/main/java/com/sandisk/plm/tracker/controller/GradeController.java` — `/api/meeting/grade/*` endpoints.
- `src/test/java/com/sandisk/plm/tracker/service/GradeStorageServiceTest.java`
- `src/test/java/com/sandisk/plm/tracker/service/GradeEvaluatorServiceTest.java` — deterministic fallback tiers.

**Modify:**
- `src/main/resources/static/meeting-mode.js` — replace `gradeDrawer()`/`renderDrawers()` grade branch with the 2a modal; add `S.gradeUi` state, evaluator wiring, dispute/rule/history handlers, model selector; extend `H` + `window.MeetingMode` export.
- `src/main/resources/static/index.html` — bump `meeting-mode.js?v=` cache-bust; add `grade-logic.js` script tag before `meeting-mode.js`.
- `src/main/resources/static/whats-new.js` — changelog entry.
- `src/main/resources/application.properties` — `plm.grade.notify.admin`, `plm.grade.notify.it`, `plm.grade.dir`.
- `config/application.properties` (local) and `config/application-prod.properties` — same keys for local/prod overrides if they differ.

---

## Phase 1 — Pure scoring module + modal shell + explainer + trend

### Task 1: Extract pure grade-logic module (scoring + letter bands)

**Files:**
- Create: `src/main/resources/static/grade-logic.js`
- Test: `test/js/grade-logic.test.js`

- [ ] **Step 1: Write the failing test**

```js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const G = require('../../src/main/resources/static/grade-logic.js');

const DEF = { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
function counts(o) {
  return Object.assign({ total: 65, overdue: [], unownedHi: [], soon: 0, missingCount: 0, onTrackBase: [] }, o);
}
function mkOverdue(n, days) { return Array.from({length:n}, (_,i)=>({id:'ECN-'+(1000+i),daysOverdue:days||10,kind:'overdue'})); }
function mkNo(n){ return Array.from({length:n}, (_,i)=>({id:'ECN-'+(2000+i),kind:'noowner'})); }

test('letterFor bands', () => {
  assert.strictEqual(G.letterFor(95), 'A');
  assert.strictEqual(G.letterFor(68), 'D+');
  assert.strictEqual(G.letterFor(50), 'F');
});

test('baseline D+ 68 — 13 overdue + 4 no-owner + 25 missing of 65', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), DEF);
  assert.strictEqual(g.atRiskN, 17);
  assert.strictEqual(g.penalty, 32);       // round(17/65*100)=26, +6
  assert.strictEqual(g.score, 68);
  assert.strictEqual(g.letter, 'D+');
});

test('graceDays>0 moves sub-grace overdue out of at-risk', () => {
  const od = mkOverdue(10, 12).concat(mkOverdue(3, 4)); // 3 under 7d grace
  const g = G.computeGrade(counts({ overdue: od, unownedHi: mkNo(4), missingCount: 25 }), { graceDays:7, includeNoOwner:true, missingPenalty:6 });
  assert.strictEqual(g.atRiskN, 14);       // 10 overdue + 4 no-owner
});

test('includeNoOwner=false drops the no-owner bucket', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), { graceDays:0, includeNoOwner:false, missingPenalty:6 });
  assert.strictEqual(g.atRiskN, 13);
  assert.strictEqual(g.drivers.find(d=>d.key==='noowner').count, 0);
});

test('missingPenalty=0 makes missing fully informational', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(13), unownedHi: mkNo(4), missingCount: 25 }), { graceDays:0, includeNoOwner:true, missingPenalty:0 });
  assert.strictEqual(g.penalty, 26);
});

test('score clamps to [0,100]', () => {
  const g = G.computeGrade(counts({ overdue: mkOverdue(65), missingCount: 0 }), DEF);
  assert.strictEqual(g.score, 0);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/git/plm-field-tracker && node --test test/js/grade-logic.test.js`
Expected: FAIL — "Cannot find module '.../grade-logic.js'".

- [ ] **Step 3: Write minimal implementation**

```js
/* Pure backlog-grade scoring + deterministic dispute evaluator. No window/
 * document refs so it can be unit-tested under `node --test`. Loaded in the
 * browser before meeting-mode.js (exposes window.GradeLogic); required directly
 * in tests. Mirrors imsreview-classify.js's dual-export wrapper. */
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.GradeLogic = api;
})(this, function () {
  'use strict';

  function letterFor(s) {
    return s >= 93 ? 'A' : s >= 90 ? 'A−' : s >= 87 ? 'B+' : s >= 83 ? 'B' : s >= 80 ? 'B−'
      : s >= 77 ? 'C+' : s >= 73 ? 'C' : s >= 70 ? 'C−' : s >= 65 ? 'D+' : s >= 55 ? 'D' : 'F';
  }
  function colorFor(s) { return s >= 80 ? 'var(--good)' : s >= 70 ? 'var(--warn)' : 'var(--bad)'; }

  function computeGrade(counts, rule) {
    rule = rule || { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
    var total = counts.total || 1;
    var liveOver = (counts.overdue || []).filter(function (x) { return (x.daysOverdue || 0) >= rule.graceDays; });
    var graceOver = (counts.overdue || []).filter(function (x) { return (x.daysOverdue || 0) < rule.graceDays; });
    var liveNo = rule.includeNoOwner ? (counts.unownedHi || []) : [];
    var ruleNo = rule.includeNoOwner ? [] : (counts.unownedHi || []);
    var atRiskN = liveOver.length + liveNo.length;
    var onTrackN = (counts.onTrackBase || []).length + graceOver.length + ruleNo.length;
    var penalty = Math.round(atRiskN / total * 100) + (rule.missingPenalty || 0);
    var score = Math.max(0, Math.min(100, 100 - penalty));
    var pct = function (n) { return Math.round(n / total * 100); };
    var summary = liveOver.length + ' overdue and ' + liveNo.length + ' unowned high-priority item'
      + (liveNo.length === 1 ? '' : 's') + ' are the main risks'
      + (counts.missingCount ? '; ' + counts.missingCount + ' still need a target date (informational)' : '') + '.';
    var drivers = [
      { key: 'ontrack', label: 'On track', count: onTrackN, color: 'var(--good)', pct: pct(onTrackN), items: counts.onTrackBase || [] },
      { key: 'overdue', label: 'Overdue UAT / Go-Live', count: liveOver.length, color: 'var(--bad)', pct: pct(liveOver.length), items: liveOver },
      { key: 'noowner', label: 'High priority · no owner', count: liveNo.length, color: 'var(--bad)', pct: pct(liveNo.length), items: liveNo },
      { key: 'soon', label: 'Going live ≤ 7 days', count: counts.soon || 0, color: 'var(--warn)', pct: pct(counts.soon || 0), items: [] },
      { key: 'missing', label: 'Missing target dates', count: counts.missingCount || 0, color: 'var(--warn)', pct: pct(counts.missingCount || 0), items: [] }
    ];
    return { letter: letterFor(score), score: score, penalty: penalty, atRiskN: atRiskN,
      scoreColor: colorFor(score), summary: summary, drivers: drivers };
  }

  function trend(score, lastScore, lastLetter) {
    if (lastScore == null) return { arrow: '—', text: 'no prior review', color: 'var(--ink-3)', bg: 'var(--surface-2)' };
    var d = score - lastScore;
    return {
      arrow: d > 0 ? '▲' : d < 0 ? '▼' : '—',
      text: (d >= 0 ? '+' : '') + d + ' vs last review · ' + lastLetter + ' ' + lastScore,
      color: d > 0 ? 'var(--good-ink)' : d < 0 ? 'var(--bad-ink)' : 'var(--ink-3)',
      bg: d > 0 ? 'var(--good-bg)' : d < 0 ? 'var(--bad-bg)' : 'var(--surface-2)'
    };
  }

  // evaluateDispute added in Task 8 (deterministic fallback). Stub keeps export stable.
  return { letterFor: letterFor, colorFor: colorFor, computeGrade: computeGrade, trend: trend };
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/git/plm-field-tracker && node --test test/js/grade-logic.test.js`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/grade-logic.js test/js/grade-logic.test.js
git commit -m "feat(grade): pure scoring module with runtime rule config + trend"
```

### Task 2: Wire grade-logic into meeting-mode + build counts from S.items

**Files:**
- Modify: `src/main/resources/static/index.html` (add `grade-logic.js` script tag before `meeting-mode.js`; bump `meeting-mode.js?v=`)
- Modify: `src/main/resources/static/meeting-mode.js` (replace `computeGrade()` body to delegate; add `buildCounts()`)

- [ ] **Step 1: Add the script tag.** In `index.html`, find the line `<script src="meeting-mode.js?v=20260626a"></script>` and insert immediately before it:

```html
<script src="grade-logic.js?v=20260630a"></script>
```
Then bump the meeting-mode tag to `<script src="meeting-mode.js?v=20260630a"></script>`.

- [ ] **Step 2: Add `buildCounts()` in meeting-mode.js** (near the existing `computeGrade` at ~line 966). It maps `S.items` (built by `buildItems()`) into the `counts` shape. `daysOverdue` comes from `-daysUntil(uat/goLive)` of whichever is overdue. Keep the existing stage-aware `overdueUat`/`overdueGoLive`/`anyOverdue`/`doneStage`/`isUnassigned` helpers.

```js
function buildCounts() {
  var rows = S.items || [], total = 0;
  var overdue = [], unownedHi = [], onTrackBase = [], soon = 0, missingCount = 0;
  rows.forEach(function (it) {
    if (doneStage(it.status)) return;                 // closed don't count
    total++;
    var dg = daysUntil(it.goLive), du = daysUntil(it.uat);
    var od = anyOverdue(it);
    var noUat = !it.uat, noGo = !it.goLive;
    var daysOverdue = 0;
    if (overdueUat(it) && du !== null) daysOverdue = Math.max(daysOverdue, -du);
    if (overdueGoLive(it) && dg !== null) daysOverdue = Math.max(daysOverdue, -dg);
    var meta = overdueUat(it) ? ('UAT · ' + (-du) + 'd overdue') : overdueGoLive(it) ? ('Go-Live · ' + (-dg) + 'd overdue') : (it.status || '');
    var desc = it.title || '';                          // problemStatement/proposal already folded into it.title in buildItems
    if (od) overdue.push({ id: it.id, title: it.title, meta: meta, status: it.status, daysOverdue: daysOverdue, desc: desc, kind: 'overdue' });
    if (isUnassigned(it.owner) && it.hiPri) unownedHi.push({ id: it.id, title: it.title, meta: 'High priority · no IT owner', status: it.status, desc: desc, kind: 'noowner' });
    if (dg !== null && dg >= 0 && dg <= 7) soon++;
    if (noUat || noGo) missingCount++;
    if (!od && !(isUnassigned(it.owner) && it.hiPri)) onTrackBase.push({ id: it.id, meta: (it.uat ? 'UAT ' + (fmtMD(it.uat) || '') : (it.goLive ? 'Go-Live ' + (fmtMD(it.goLive) || '') : '')) });
  });
  return { total: total || 1, overdue: overdue, unownedHi: unownedHi, soon: soon, missingCount: missingCount, onTrackBase: onTrackBase };
}
```

- [ ] **Step 3: Replace `computeGrade()` to delegate** to the module with the live rule, preserving the old `{letter,score,line,drivers}` consumers until the modal lands:

```js
function computeGrade() {
  var g = window.GradeLogic.computeGrade(buildCounts(), gradeRule());
  g.line = g.summary;                 // back-compat with old drawer markup
  return g;
}
function gradeRule() {
  return (S.gradeUi && S.gradeUi.rule) || { graceDays: 0, includeNoOwner: true, missingPenalty: 6 };
}
```

- [ ] **Step 4: Manual verify** — `mvn -q package -DskipTests` is NOT needed here; just copy these two static files into the running local copy and hard-refresh, OR run the full local build later. For a fast check without rebuild: serve isn't required — the existing drawer still renders. Confirm `node --test test/js/grade-logic.test.js` still green and open the existing grade drawer locally to confirm D+/68 unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/meeting-mode.js src/main/resources/static/index.html
git commit -m "feat(grade): delegate computeGrade to grade-logic, add buildCounts"
```

### Task 3: Centered modal shell — left column (letter/score/trend/explainer/model) + right tabbed pane

**Files:**
- Modify: `src/main/resources/static/meeting-mode.js`

This replaces the grade branch of `renderDrawers()`/`gradeDrawer()` with the 2a centered modal. Render layer — verified manually.

- [ ] **Step 1: Add modal UI state** to `S` (near `drawer` at line 37):

```js
gradeUi: {
  rule: { graceDays: 0, includeNoOwner: true, missingPenalty: 6 },
  tab: 'breakdown',          // breakdown | atrisk | chat | history
  explainer: false,
  model: '',                 // '' = production default; else a slug from /api/ai-eval/models
  models: [],                // fetched catalog [{slug,label}]
  excluded: {},              // ecn -> true (accepted item disputes this session)
  disputeFor: null,          // ecn (item) | 'rule:overdue' | null
  disputeText: '', disputeFile: null, aiReply: null, busy: false,
  flash: null,               // {text,to}
  history: { logic: [], accepts: [], outbox: [] }, historyLoaded: false,
  chat: [], lastScore: null, lastLetter: null
},
```

- [ ] **Step 2: Replace the grade branch of `renderDrawers()`** so the grade view renders a centered modal (scrim + centered card), while `agenda` keeps the right drawer:

```js
function renderDrawers() {
  if (!S.drawer) return '';
  if (S.drawer === 'grade') {
    var scrim = '<div onclick="MeetingMode._closeDrawer()" style="position:fixed;inset:0;background:rgba(15,23,32,.38);z-index:40"></div>';
    return scrim + gradeModal();
  }
  var scrim2 = '<div onclick="MeetingMode._closeDrawer()" style="position:fixed;inset:0;background:rgba(15,23,32,.38);z-index:40"></div>';
  return scrim2 + agendaDrawer();
}
```

- [ ] **Step 3: Implement `gradeModal()`** (new function; replaces `gradeDrawer()` which can be deleted). Outer card is centered & fixed:

```js
function gradeModal() {
  var open = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);width:768px;max-width:calc(100vw - 32px);height:632px;max-height:calc(100vh - 32px);background:#fff;border:1px solid var(--line);border-radius:14px;box-shadow:var(--shadow-pop);z-index:45;display:flex;flex-direction:column;overflow:hidden;animation:mmIn .2s ease';
  if (S.grade === 'refreshing') return '<div style="' + open + '">' + gradeHead() + gradeRefreshing() + '</div>';
  var g = S.gradeData || computeGrade();
  return '<div style="' + open + '">' + gradeHead() + gradeFlash() +
    '<div style="display:flex;flex:1;min-height:0">' + gradeLeft(g) + gradeRight(g) + '</div></div>';
}
```

- [ ] **Step 4: Implement `gradeHead()`, `gradeRefreshing()`, `gradeFlash()`.** Head mirrors the existing drawer head (title "Backlog grade", AI pill, "↻ Refreshed all N · graded just now ✓", × → `_closeDrawer`). Refreshing reuses the existing pulse+bar markup from the old `gradeDrawer`'s refreshing branch (ids `mm-grade-bar`/`mm-grade-read`). Flash:

```js
function gradeFlash() {
  var f = S.gradeUi.flash; if (!f) return '';
  return '<div class="mm-flash" style="display:flex;align-items:center;gap:10px;padding:9px 20px;border-bottom:1px solid var(--line);background:var(--good-bg)">'
    + '<span style="color:var(--good-ink);font-size:13px">✓</span>'
    + '<span style="flex:1;font-size:11.5px;color:var(--good-ink);line-height:1.4">' + esc(f.text) + ' <span style="font-family:var(--font-mono)">✉ ' + esc(f.to) + '</span> notified.</span>'
    + '<span onclick="MeetingMode._gradeTab(\'history\')" style="cursor:pointer;font-family:var(--font-mono);font-size:10.5px;color:var(--accent);white-space:nowrap">View history →</span></div>';
}
```
Add a `@keyframes mmFlash` + `.mm-flash{animation:mmFlash 1s var(--ease,ease)}` style (inject once where the other mm keyframes live — search `mmIn`/`mmPulse` in the file and add alongside).

- [ ] **Step 5: Implement `gradeLeft(g)`** — 270px column: serif letter (80px, `g.scoreColor`), `score/100`, "Meeting readiness", trend pill (`window.GradeLogic.trend(g.score, S.gradeUi.lastScore, S.gradeUi.lastLetter)`), summary, collapsible explainer (`_gradeExplainer`), and the model selector chips at the bottom (`S.gradeUi.models`, `_gradeModel(slug)`). Explainer formula uses live numbers:

```
100 − round(<g.atRiskN>/<total> × 100) − <rule.missingPenalty> = <g.score> → <g.letter>
```
(Use `buildCounts().total` for the denominator, not a hardcoded 65.)

- [ ] **Step 6: Implement `gradeRight(g)`** — flex column: tab strip (`_gradeTab`) for `Breakdown`, `At-risk · <atRiskN>`, `Ask AI`, `History · <n>`; a scrollable body that switches on `S.gradeUi.tab`. For Task 3, only **Breakdown** is populated (port the existing bar markup from the old drawer, plus per-rule "⚖ Dispute this rule →" affordance that calls `_gradeDisputeRule(key)` for the three penalty signals — form itself lands in Phase 6). At-risk/Ask AI/History bodies render a "coming up" placeholder until later tasks.

- [ ] **Step 7: Add handlers** to `H` and export in `window.MeetingMode`:

```js
_gradeTab: function (t) { S.gradeUi.tab = t; if (t === 'history') loadGradeHistory(); render(); },
_gradeExplainer: function () { S.gradeUi.explainer = !S.gradeUi.explainer; render(); },
_gradeModel: function (slug) { S.gradeUi.model = slug; persistGradeModel(slug); render(); },
_gradeDriver: function (key) { S.gradeOpen[key] = !S.gradeOpen[key]; render(); }, // keep existing
```
(`loadGradeHistory`, `persistGradeModel`, dispute handlers added in later phases — add no-op stubs now so the modal renders without ReferenceErrors: `function loadGradeHistory(){} function persistGradeModel(){}`.)

- [ ] **Step 8: Fetch the model catalog** when the modal first opens. In `gradeNow()` success (and on `_openGradeModal`), if `!S.gradeUi.models.length` call `api('GET','/api/ai-eval/models')` → map to `[{slug,label}]`, prepend `{slug:'',label:'Production default'}`, store, `render()`. Guard against the endpoint 404 (older JAR): on empty just show the default chip.

- [ ] **Step 9: Manual verify.** Build+run locally (Task end-of-phase build) OR copy static files into `~/Documents/plm-toolkit 2/` if it serves static from an exploded dir (it serves from the JAR, so a `mvn package` is needed to see it). Open IT Enhancements → Meeting Mode → "Grade now". Confirm: centered 768px modal; D+/68; trend pill; explainer expands with correct live formula; model chips show; Breakdown bars match; clicking a bar expands ECNs; tabs switch (others show placeholder). No console errors.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/static/meeting-mode.js
git commit -m "feat(grade): centered modal shell — left grade/trend/explainer/model, right tabs"
```

---

## Phase 2 — At-risk tab, per-item disputes, deterministic evaluator, live re-grade

### Task 4: At-risk tab list + excluded-by-you + restore (client state)

**Files:** Modify `src/main/resources/static/meeting-mode.js`

- [ ] **Step 1:** Add `gradeAtRisk(g)` returning the merged `overdue.concat(unownedHi)` from `buildCounts()` minus `S.gradeUi.excluded`. Each row renders id, kind badge (`OVERDUE`/`NO OWNER`), title, meta, and a **Dispute** button → `_gradeOpenDispute(ecn)`.
- [ ] **Step 2:** Below the list, when `Object.keys(excluded).length`, render an "Excluded by you" section with strikethrough ids + **restore** (`_gradeRestore(ecn)`).
- [ ] **Step 3:** `computeGrade()` must subtract excluded items. Update `buildCounts()` to drop `S.gradeUi.excluded[id]` from `overdue`/`unownedHi` and add them to `onTrackBase` (so totals stay consistent and the grade rises). Re-test: excluding an at-risk item raises the score.
- [ ] **Step 4:** Handlers: `_gradeOpenDispute`, `_gradeRestore` (delete from excluded, `S.gradeData=null`, render).
- [ ] **Step 5: Manual verify** the At-risk tab lists 17 items, Dispute opens the form region (next task), restore round-trips, grade recomputes live.
- [ ] **Step 6: Commit** `git commit -m "feat(grade): at-risk tab + excluded/restore with live re-grade"`

### Task 5: Inline per-item dispute form + textarea value preservation

**Files:** Modify `src/main/resources/static/meeting-mode.js`

The modal re-renders via full innerHTML, which wipes textarea content. Mirror the `applyRailSearch` no-rerender pattern: the textarea has `id="mm-dispute-ta"` and `oninput="MeetingMode._gradeDraft(this.value)"` storing into `S.gradeUi.disputeText` **without** calling `render()`; on any real re-render, the textarea's `value` is seeded from `S.gradeUi.disputeText`.

- [ ] **Step 1:** Render the form inside the disputed item card when `S.gradeUi.disputeFor === ecn`: the "On record in Agile · Description" block (shows `it.desc`), the textarea (seeded from `disputeText`), an "Attach screenshot" `<label><input type=file accept=image/* onChange=_gradeFile></label>`, the selected-file chip with clear, the AI reply box (need_file = accent, reject = bad), and Submit/Cancel.
- [ ] **Step 2:** Handlers: `_gradeDraft(v)` sets `S.gradeUi.disputeText=v` (no render); `_gradeFile(input)` stores `{name}` + clears `aiReply`, render; `_gradeClearFile`; `_gradeCancelDispute` (clear disputeFor/text/file/reply, render); `_gradeSubmitDispute` (Task 7).
- [ ] **Step 3: Manual verify** typing persists across the file-select re-render; cancel clears.
- [ ] **Step 4: Commit** `git commit -m "feat(grade): inline item dispute form with focus-safe textarea"`

### Task 6: Ask-AI chat tab

**Files:** Modify `src/main/resources/static/meeting-mode.js`

- [ ] **Step 1:** Seed `S.gradeUi.chat` with the AI intro (one bubble summarizing the grade) when the modal opens. Render bubble list (user right/accent, ai left/surface-2). Input row at the bottom with `id="mm-grade-chat"` + Send (`_gradeChatSend`), Enter-to-send.
- [ ] **Step 2:** `_gradeChatSend` pushes the user bubble, matches an ECN id in the text; if matched & at-risk, calls the evaluator (Task 7/8) with the typed reason (no file) and either accepts (exclude + re-grade + email + history) or replies need_file/reject in-chat. If no ECN matched, replies asking which ECN. Reuse the same accept path as the form.
- [ ] **Step 3: Manual verify** chat matches `ECN-119003`, weak text rejected, strong/db-verified accepted.
- [ ] **Step 4: Commit** `git commit -m "feat(grade): Ask-AI chat tab routes through the evaluator"`

### Task 7: Submit-dispute accept path (client) — exclude, re-grade, persist, email, history, flash

**Files:** Modify `src/main/resources/static/meeting-mode.js`

- [ ] **Step 1:** `_gradeSubmitDispute()` reads `disputeText`+`disputeFile`, sets `busy`, and calls `POST /api/meeting/grade/evaluate` (Task 11) with `{kind:'item'|'rule', ecn|ruleKey, reason, hasFile, model, sessionId, record:{desc,title,status}}`. Response `{status, verifiedBy, fieldQuote, reason}`.
- [ ] **Step 2:** If `status!=='accept'` → set `aiReply`, render, return (need_file/reject). If accept:
  - **item:** if a file was attached, first `POST /api/meeting/grade/disputes/<tmpId>/attachment` (multipart, Task 12) to store it and get `attachmentRef`. Add ecn to `excluded`, `S.gradeData=null`, compute before/after via `computeGrade()` around the mutation, then `POST /api/meeting/grade/disputes` with the full `GradeRecord` payload (Task 11) — server emails admin + persists + appends outbox, returns the saved record. Push to `history.accepts`, set `flash`, clear the form, render.
  - **rule:** apply the rule change locally (Task 13), before/after, `POST /api/meeting/grade/logic-changes`, push `history.logic`, flash to IT, render.
- [ ] **Step 3:** Compute before/after letters/scores around the state mutation so the email + record carry the real delta.
- [ ] **Step 4: Manual verify** an accept path end-to-end against the local server (deterministic fallback): grade moves, flash shows, History gets the record + outbox entry, email attempt logged server-side.
- [ ] **Step 5: Commit** `git commit -m "feat(grade): accept path — exclude, re-grade, persist, email, flash"`

### Task 8: Deterministic evaluator in grade-logic.js (TDD) — used as the offline fallback both client+server-side reference

**Files:**
- Modify: `src/main/resources/static/grade-logic.js`
- Modify: `test/js/grade-logic.test.js`

- [ ] **Step 1: Write failing tests** (append):

```js
const REC = { desc: 'Process-documentation update only. Business-led; Agile IT help not required.', itNotRequired: true };
const REC2 = { desc: 'IT-led; go-live slipped.', itNotRequired: false };

test('db-verified: IT-not-required claim matching the record → accept, no file', () => {
  const ev = G.evaluateDispute({ text: 'The ECN description says Agile IT help not required.', hasFile: false, record: REC });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'agile_record');
});
test('doc-claim with no record support → need_file', () => {
  const ev = G.evaluateDispute({ text: 'The description says this is not an IT change.', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'need_file');
});
test('doc-claim with attachment → accept (attachment)', () => {
  const ev = G.evaluateDispute({ text: 'The description says this is not an IT change.', hasFile: true, record: REC2 });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'attachment');
});
test('independent evidence (date/CCB/owner) → accept (cited_evidence)', () => {
  const ev = G.evaluateDispute({ text: 'Reassigned to A. Rivera on 6/15 per CCB-2026-114; UAT signed off.', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'accept');
  assert.strictEqual(ev.verifiedBy, 'cited_evidence');
});
test('weak opinion → reject', () => {
  const ev = G.evaluateDispute({ text: 'this is unfair', hasFile: false, record: REC2 });
  assert.strictEqual(ev.status, 'reject');
});
```

- [ ] **Step 2: Run** `node --test test/js/grade-logic.test.js` → FAIL (`evaluateDispute` undefined).

- [ ] **Step 3: Implement `evaluateDispute`** (port of the prototype `evaluate`+`_dbCheck`, returning `verifiedBy`):

```js
function _dbCheck(rec, t) {
  if (!rec) return { matched: false };
  var re = /(agile )?it\b[^.]*\b(not|no|isn'?t|never)\b[^.]*(need|requir|necess|involv)|no it (help|involv|support)|it (help|support) not (need|requir)|not an it (item|change|ecn)|business[- ]?(side|led|only)/;
  if (rec.itNotRequired && re.test(t)) return { matched: true, field: 'Description', quote: rec.desc };
  return { matched: false };
}
function evaluateDispute(o) {
  var text = (o.text || '').trim(), t = text.toLowerCase(), hasFile = !!o.hasFile, rec = o.record;
  var ev = [];
  if (/\b\d{1,2}\/\d{1,2}(\/\d{2,4})?\b/.test(t)) ev.push('a date');
  if (/ecn-?\d{3,}/.test(t)) ev.push('an ECN reference');
  if (/\bccb\b/.test(t)) ev.push('a CCB decision');
  if (/owner|assign|reassign/.test(t)) ev.push('an ownership change');
  if (/verif|confirm|signed|approv|complete|closed|done/.test(t)) ev.push('a verification');
  if (/policy|sop|standard|process|cycle/.test(t)) ev.push('a policy/SOP');
  if (/http|link|ticket|jira|servicenow|snow/.test(t)) ev.push('a linked record');
  if (/uat|go-?live|deploy|release|prod/.test(t)) ev.push('a release/UAT status');
  var db = _dbCheck(rec, t);
  if (db.matched) return { status: 'accept', verifiedBy: 'agile_record', fieldQuote: db.quote, evidence: ev, reason: '' };
  if (hasFile) { ev.push('an attached screenshot of the ECN'); return { status: 'accept', verifiedBy: 'attachment', evidence: ev, reason: '' }; }
  var docClaim = /descriptio|\bnotes?\b|comment|\bfield\b|screenshot|attach|\bstates?\b|\bsays\b|not required|help not|per the ecn|agile (say|show|note)/.test(t);
  if (text.length >= 24 && ev.length >= 1) return { status: 'accept', verifiedBy: 'cited_evidence', evidence: ev, reason: '' };
  if (docClaim) return { status: 'need_file', evidence: ev, reason: "That rests on the ECN's own wording, so I can't take it on your word — upload a screenshot of the ECN description showing that and I'll verify it and re-grade." };
  return { status: 'reject', evidence: ev, reason: (text.length < 24
    ? "I can't override on this alone — give me at least a full sentence of context and one piece of proof: a date, an Agile/ECN reference, the new owner, a CCB/policy note, or a screenshot."
    : "There's no verifiable evidence in that. Cite a date, an Agile/ECN reference, the new owner, a CCB/policy decision, or attach a screenshot and I'll re-grade.") };
}
```
Add `evaluateDispute: evaluateDispute` to the return object.

- [ ] **Step 4: Run** `node --test test/js/grade-logic.test.js` → PASS (all).
- [ ] **Step 5: Commit** `git add src/main/resources/static/grade-logic.js test/js/grade-logic.test.js && git commit -m "feat(grade): deterministic three-tier dispute evaluator (TDD)"`

---

## Phase 3 — Backend persistence + History tab

### Task 9: GradeModels + GradeStorageService (TDD)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/GradeModels.java`
- Create: `src/main/java/com/sandisk/plm/tracker/service/GradeStorageService.java`
- Create: `src/test/java/com/sandisk/plm/tracker/service/GradeStorageServiceTest.java`

- [ ] **Step 1: Write `GradeModels.java`** with `RuleConfig`, `GradeRecord`, `OutboxEntry`, `EvaluateResult` as plain POJOs with public fields + no-arg ctors (Jackson-friendly, mirror `MeetingModels`). Shapes per the "Data shapes" section above.

- [ ] **Step 2: Write the failing test** `GradeStorageServiceTest`:

```java
package com.sandisk.plm.tracker.service;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GradeStorageServiceTest {
  Path dir;
  GradeStorageService svc;
  @BeforeEach void setup() throws Exception {
    dir = Files.createTempDirectory("grade-test");
    svc = new GradeStorageService(dir.toString());
  }
  @Test void saveAndList_roundTrips() {
    GradeModels.GradeRecord r = new GradeModels.GradeRecord();
    r.id = "gd_1"; r.kind = "item"; r.targetEcn = "ECN-119003"; r.gradeBefore = "D+"; r.gradeAfter = "C";
    r.scoreBefore = 68; r.scoreAfter = 74; r.actorAd = "8252"; r.createdAt = 1000L;
    svc.saveRecord(r);
    List<GradeModels.GradeRecord> all = svc.listRecords();
    assertEquals(1, all.size());
    assertEquals("ECN-119003", all.get(0).targetEcn);
  }
  @Test void outbox_appendsAndReads() {
    GradeModels.OutboxEntry e = new GradeModels.OutboxEntry();
    e.id = "o1"; e.to = "pdl-plm-admin@sandisk.com"; e.subject = "x"; e.body = Arrays.asList("a","b"); e.sentAt = 1L;
    svc.appendOutbox(e);
    assertEquals(1, svc.listOutbox().size());
  }
  @Test void deleteRecord_removes() {
    GradeModels.GradeRecord r = new GradeModels.GradeRecord(); r.id="gd_2"; r.kind="rule"; r.ruleKey="overdue";
    svc.saveRecord(r); svc.deleteRecord("gd_2");
    assertTrue(svc.listRecords().isEmpty());
  }
}
```

- [ ] **Step 3: Run** `mvn -q -Dtest=GradeStorageServiceTest test` → FAIL (class missing).

- [ ] **Step 4: Implement `GradeStorageService`** mirroring `MeetingStorageService`: ctor takes the base dir (Spring: `@Value("${plm.grade.dir:./data/grade}")`), creates `disputes/`, `logic-changes/`, `outbox/`. `saveRecord` routes by `kind` to the right subdir as `<id>.json` (atomic temp+rename, Jackson `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`). `listRecords()` reads both subdirs, sorts by `createdAt` desc. `deleteRecord(id)` removes from whichever subdir. `appendOutbox`/`listOutbox` use a single `outbox/outbox.jsonl` (append one JSON line; read+parse lines). Add a `@Component` annotation and a test-friendly package-private ctor `GradeStorageService(String dir)`.

- [ ] **Step 5: Run** `mvn -q -Dtest=GradeStorageServiceTest test` → PASS.
- [ ] **Step 6: Commit** `git add src/main/java/.../GradeModels.java src/main/java/.../GradeStorageService.java src/test/java/.../GradeStorageServiceTest.java && git commit -m "feat(grade): GradeModels + file-based GradeStorageService (TDD)"`

### Task 10: GradeEmailService

**Files:** Create `src/main/java/com/sandisk/plm/tracker/service/GradeEmailService.java`

- [ ] **Step 1:** Implement `sendAdminAccept(GradeRecord)` and `sendItLogicChange(GradeRecord)` building the exact body copy from the prototype `_email()` (who, ECN/rule, quoted rationale, how-verified line `Verified against Agile ECN record — Description: "…"` OR `Attached proof: <file>`, AI decision, grade delta `D+ (68) → C (74)`, timestamp; rule adds "applies to every future grade until reverted"). Recipients come from config (`plm.grade.notify.admin`, `plm.grade.notify.it`), NOT hard-coded. Reuse the SMTP send pattern from `MeetingEmailService` (javax.mail `Session.getInstance(props)`, `mail.smtp.host/port`, `mail.from`, `EmailEnvTag.tag(subject)`, `Transport.send`). Each method returns the `OutboxEntry` it sent (to/subject/body) so the controller can persist it even if SMTP throws (catch, log, still return the entry with a best-effort flag). **No credentials/PII beyond the AD name + ECN.**
- [ ] **Step 2:** Build with `mvn -q -DskipTests compile` → success.
- [ ] **Step 3: Commit** `git commit -m "feat(grade): GradeEmailService (config-driven recipients, reuses SMTP)"`

### Task 11: GradeController — disputes, logic-changes, history endpoints

**Files:** Create `src/main/java/com/sandisk/plm/tracker/controller/GradeController.java`

Endpoints (all require the `it-enhancements` tab grant — copy the auth guard from `MeetingController`):

- `POST /api/meeting/grade/disputes` — body = the item `GradeRecord` (minus server-set fields). Server sets `id`, `actorAd/Name` from session, `createdAt`, persists via `GradeStorageService.saveRecord`, calls `GradeEmailService.sendAdminAccept`, appends the returned `OutboxEntry`, returns `{record, emailedTo, mailError?}`.
- `POST /api/meeting/grade/logic-changes` — same for rule changes → `sendItLogicChange`. Persists the resulting `ruleConfigJson`.
- `DELETE /api/meeting/grade/logic-changes/{id}` — revert: mark `reverted=true` (or delete) so it stops applying; returns `{ok}`.
- `GET /api/meeting/grade/history` — `{logic:[...], accepts:[...], outbox:[...]}` from storage, newest first.
- `POST /api/meeting/grade/evaluate` — body `{kind,ecn|ruleKey,reason,hasFile,model,record}` → delegates to `GradeEvaluatorService` (Task 14), returns `EvaluateResult`.

- [ ] **Step 1:** Implement the controller with the `currentAd`/`isAdmin`/tab-guard helper copied from `MeetingController`. Use constructor injection of `GradeStorageService`, `GradeEmailService`, `GradeEvaluatorService`.
- [ ] **Step 2:** `mvn -q -DskipTests compile` → success.
- [ ] **Step 3:** Wire the frontend `loadGradeHistory()`/`persistGradeModel()` stubs from Task 3 to the real endpoints: `loadGradeHistory()` → `GET /api/meeting/grade/history` populating `S.gradeUi.history`; `persistGradeModel(slug)` → `localStorage.setItem('mm-grade-model', slug)` (and read it back on modal open).
- [ ] **Step 4: Manual verify** History tab loads persisted records after an accept; revert removes a logic change.
- [ ] **Step 5: Commit** `git commit -m "feat(grade): GradeController + history/revert wiring"`

### Task 12: Attachment upload endpoint

**Files:** Modify `GradeController.java`

- [ ] **Step 1:** Add `POST /api/meeting/grade/disputes/attachment` (multipart `file`) → `FileArchiveService.recordUpload(file, user, "grade-dispute", route)` returning the archive `id`; respond `{attachmentRef, filename, size, contentType}`. Reject non-image content types and >10MB.
- [ ] **Step 2:** Frontend `_gradeSubmitDispute` posts the file here first (when present) before the disputes POST, threading `attachmentRef` into the record. Use `FormData` + `fetch` (not the JSON `api()` helper).
- [ ] **Step 3: Manual verify** uploading a screenshot in the dispute form stores a file that appears in the admin file-archive; record carries `attachmentRef`.
- [ ] **Step 4: Commit** `git commit -m "feat(grade): dispute screenshot upload via FileArchiveService"`

---

## Phase 4 — Real LLM evaluator + model selector

### Task 13: Apply/revert rule changes in the live grade (client)

**Files:** Modify `src/main/resources/static/meeting-mode.js`

- [ ] **Step 1:** `_gradeDisputeRule(key)` sets `disputeFor='rule:'+key`, renders the rule dispute form (textarea + attach + submit) inline under the Breakdown row, with the rule summary text per key (`overdue`→"Items overdue < 7 days no longer count"; `noowner`→"Unowned high-priority no longer penalized"; `missing`→"Missing target dates set to 0 penalty").
- [ ] **Step 2:** On accept (from Task 7's rule branch): apply `graceDays=7` / `includeNoOwner=false` / `missingPenalty=0` to `S.gradeUi.rule`, `S.gradeData=null`, re-grade. Show "✓ logic adjusted for this signal" on that Breakdown row.
- [ ] **Step 3:** History "Logic changes" rows get a **revert** link → `_gradeRevertRule(id,key)` → restore the rule default, `DELETE /api/meeting/grade/logic-changes/{id}`, re-grade.
- [ ] **Step 4: Manual verify** rule dispute with proof changes the grade and the Breakdown badge; revert restores it.
- [ ] **Step 5: Commit** `git commit -m "feat(grade): per-rule disputes with runtime logic change + revert"`

### Task 14: GradeEvaluatorService — Portkey call + deterministic fallback (TDD on fallback)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/GradeEvaluatorService.java`
- Create: `src/test/java/com/sandisk/plm/tracker/service/GradeEvaluatorServiceTest.java`

- [ ] **Step 1: Write failing tests** for the deterministic Java fallback `evaluateDeterministic(reason, hasFile, recordDesc, itNotRequired)` — port the same four tiers as the JS (Task 8). Assert `agile_record`/`need_file`/`attachment`/`cited_evidence`/`reject` like the JS tests.
- [ ] **Step 2: Run** `mvn -q -Dtest=GradeEvaluatorServiceTest test` → FAIL.
- [ ] **Step 3: Implement** `evaluateDeterministic` (regex port) and `evaluate(req)`: build a strict prompt for `PortkeyClient` (pass rationale, the ECN record fields, whether an attachment is present; instruct it to return JSON `{status,verifiedBy,fieldQuote,reason}` and to default to `reject`/`need_file` when unsure). Parse the JSON from the model reply; on ANY error/timeout/disabled-Portkey → return `evaluateDeterministic(...)`. Respect the requested `model` slug (validate against `/api/ai-eval/models` catalog; blank = production default).
- [ ] **Step 4: Run** `mvn -q -Dtest=GradeEvaluatorServiceTest test` → PASS.
- [ ] **Step 5:** Confirm `POST /api/meeting/grade/evaluate` now returns model-backed verdicts when Portkey is reachable, deterministic otherwise. Locally (no agile-service, Portkey may be reachable): verify the fallback path works by forcing an error (blank reason).
- [ ] **Step 6: Commit** `git commit -m "feat(grade): LLM evaluator via Portkey with deterministic fallback (TDD)"`

### Task 15: Model selector live wiring

**Files:** Modify `meeting-mode.js`

- [ ] **Step 1:** Confirm the chips from Task 3 set `S.gradeUi.model` and persist (localStorage). Pass `model` in every `/api/meeting/grade/evaluate` call. Highlight the active chip; show `· on-prem` style only if a deterministic/on-prem slug exists in the catalog (else omit).
- [ ] **Step 2: Manual verify** switching the model is reflected in the evaluate request (Network tab) and persists across reloads.
- [ ] **Step 3: Commit** `git commit -m "feat(grade): persist + send selected grading model"`

---

## Phase 5 — Trend persistence, config, changelog, build, stage

### Task 16: Trend vs last review (persist final grade per session)

**Files:** Modify `GradeController.java`, `meeting-mode.js`

- [ ] **Step 1:** On modal open, `GET /api/meeting/grade/history` already returns records; add `lastGrade:{letter,score}` to that response computed as the most recent persisted `gradeAfter` for a *prior* session (or store a tiny `./data/grade/last-grade.json` updated whenever a meeting session is sent). Simplest: write `{letter,score,at}` to `last-grade.json` from `MeetingController`'s `/send` using the current computed grade snapshot the client passes in the send body; read it here.
- [ ] **Step 2:** Frontend sets `S.gradeUi.lastScore/lastLetter` from the response so `GradeLogic.trend` renders a real delta.
- [ ] **Step 3: Manual verify** trend pill shows "▲ +N vs last review · C 74" after a prior session exists; "no prior review" otherwise.
- [ ] **Step 4: Commit** `git commit -m "feat(grade): trend vs last review (persisted last grade)"`

### Task 17: Config keys

**Files:** Modify `src/main/resources/application.properties`, `config/application.properties`, `config/application-prod.properties`

- [ ] **Step 1:** Add to `src/main/resources/application.properties`:

```properties
# Backlog grade — dispute/audit persistence + notify recipients
plm.grade.dir=./data/grade
plm.grade.notify.admin=pdl-plm-admin@sandisk.com
plm.grade.notify.it=pdl-plm-it@sandisk.com
```
Mirror the two notify keys into `config/application-prod.properties` (override if prod differs). Local `config/application.properties` inherits; no change needed unless overriding.

- [ ] **Step 2: Commit** `git commit -m "chore(grade): config keys for grade dir + notify recipients"`

### Task 18: What's New changelog (REQUIRED before build)

**Files:** Modify `src/main/resources/static/whats-new.js`

- [ ] **Step 1:** Add a new entry at the TOP of `WHATS_NEW_RELEASES` dated `2026-06-30`, title "Backlog grade, revamped", with `new`/`improve` items: explainable grade with live formula; trend vs last review; At-risk tab with evidence-gated disputes; runtime grading-logic changes with revert; History/Outbox audit trail; screenshot attachments; per-user grading-model selector; emails to admin (item) / IT (rule) on every accepted change.
- [ ] **Step 2: Commit** `git commit -m "docs(grade): What's New entry for Backlog Grade revamp"`

### Task 19: Build, full test, stage

- [ ] **Step 1:** `cd ~/git/plm-field-tracker && node --test test/js/*.test.js` → all JS green.
- [ ] **Step 2:** `mvn -q clean package` → BUILD SUCCESS (runs JUnit too). Artifact: `target/plm-field-tracker-1.0.1.jar`.
- [ ] **Step 3:** Smoke locally: `cp target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/ && cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties` → open http://localhost:8090, run through the acceptance checklist (below).
- [ ] **Step 4:** Stage to the prod share (per CLAUDE.md, staging only): `cp target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/` (report if the share isn't mounted; verify size with `stat -f "%z"` on both).
- [ ] **Step 5: Commit any final tweaks**, then summarize for Vikas (email per the CLAUDE.md long-running-work rule since this is a >5min build).

---

## Acceptance checks (from the handoff §12 — run in Step 3 above)

- [ ] Grade math matches the prototype: **D+ / 68** at baseline; excluding at-risk items raises it; bands correct.
- [ ] Weak dispute ("this is unfair") → rejected, no change.
- [ ] Claim matching the ECN's Description ("Agile IT help not required") on an item whose record says so → **accepted with no upload**, cites the field.
- [ ] Same claim on an item whose record does **not** say so → **asks for a screenshot**; accepts after upload.
- [ ] Independent evidence (date/CCB/owner) → accepted without DB match or upload.
- [ ] Rule dispute with proof → config changes, grade moves, **revert** restores it.
- [ ] Item accept emails admin; rule change emails IT; both appear in History/Outbox with the verification method + grade delta.
- [ ] Recipients are config-driven, not hard-coded.
- [ ] Model selector switches the provider and persists.

---

## Self-review notes

- **Spec coverage:** §2 scoring→Task 1; §2 runtime rule→Tasks 1,13; §3 trend→Tasks 1,16; §4 explainer→Task 3; §5 evaluator (3 tiers)→Tasks 8,14; §6 attachments→Task 12; §7 email→Tasks 10,11; §8 audit/History→Tasks 9,11; §9 model selector→Tasks 3,15; §10 build order→phases; §11 visual rules→Task 3 (tokens only, Lucide paperclip SVG already in prototype markup, serif letter); §12 acceptance→checklist.
- **Local caveat:** agile-service (8081) and possibly Portkey are unreachable locally → the DB-verify + model paths exercise the **deterministic fallback**; the fallback is fully tested (Tasks 8,14) and is the on-prem path the handoff calls for. Real model-backed DB-verify is validated on the server after staging (handoff "agile-sdk remote testing" pattern).
- **Type consistency:** `evaluateDispute({text,hasFile,record})` (JS) ↔ `evaluate(req)`/`evaluateDeterministic(...)` (Java) both return `{status, verifiedBy, fieldQuote, reason}`. `verifiedBy ∈ {agile_record, attachment, cited_evidence}`. `rule={graceDays,includeNoOwner,missingPenalty}` everywhere.
