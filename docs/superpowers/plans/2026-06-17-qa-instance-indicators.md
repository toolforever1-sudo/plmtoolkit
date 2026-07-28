# QA Instance Indicators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make the QA box (ULS-EQ-AGLIQSS) unmistakable — every outbound email comes from a `-qa` sender (done, config-only), and the UI (both authenticated pages and the pre-login page) shows a red `TEST` pill plus an amber warning ribbon, all gated on config so prod stays untouched.

**Architecture:** Two new config properties (`app.instance.label`, `app.instance.banner`) default empty in the bundled JAR and are set only in QA's external `application.properties`. `AuthController./api/auth/session` returns them unconditionally (pre-auth safe — that path is already whitelisted). A new shared static module `instance-badge.js` renders the pill + ribbon; `app.js` drives it on authenticated pages, and `login.html` auto-loads it (self-fetch) on the pre-login page, where it also corrects two hardcoded "Production"/"prod" signals.

**Tech Stack:** Java 1.8 / Spring Boot (Maven), vanilla JS static frontend. No unit-test framework exists for controllers; verification is via `mvn package` + running the local instance and `curl`-ing the endpoint, per project CLAUDE.md.

---

## File Structure

- **Modify** `src/main/java/com/sandisk/plm/tracker/controller/AuthController.java` — add two `@Value` fields + emit them in `/session` unconditionally.
- **Modify** `src/main/resources/application.properties` (bundled) — add the two keys with empty defaults + doc comment.
- **Create** `src/main/resources/static/instance-badge.js` — shared render module (pill + ribbon, self-contained CSS, version-keyed dismissal, login self-fetch).
- **Modify** `src/main/resources/static/index.html` — include `instance-badge.js`.
- **Modify** `src/main/resources/static/app.js` — call `renderInstanceIndicator(...)` from the existing `/api/auth/session` handler.
- **Modify** `src/main/resources/static/login.html` — include `instance-badge.js`, add autoload attribute.
- **Modify** `src/main/resources/static/whats-new.js` — new release entry (pre-build requirement).
- **Modify (QA box only, not git)** `/Volumes/uls-eq-agliqss/plm-toolkit/config/application.properties` — set `app.instance.label` / `app.instance.banner`.

---

## Task 1: Backend — expose instance label/banner from /api/auth/session

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AuthController.java`
- Modify: `src/main/resources/application.properties`

- [x] **Step 1: Add the bundled defaults (empty) with a doc comment**

In `src/main/resources/application.properties`, append:

```properties

# ---------------------------------------------------------------------
# Instance indicator (UI). Empty on prod -> nothing renders. Set on a
# QA/test box (external application.properties) to show a TEST pill +
# warning ribbon. See docs/superpowers/specs/2026-06-17-qa-instance-indicators-design.md
app.instance.label=
app.instance.banner=
```

- [x] **Step 2: Add the two `@Value` fields to AuthController**

In `AuthController.java`, alongside the other injected fields (e.g. just after `private ConfigBanner configBanner;` at line 22), add:

```java
    @org.springframework.beans.factory.annotation.Value("${app.instance.label:}")
    private String instanceLabel;

    @org.springframework.beans.factory.annotation.Value("${app.instance.banner:}")
    private String instanceBanner;
```

- [x] **Step 3: Emit them unconditionally in `/session`**

In the `getSession(...)` method, the response is built inside an `if (username != null) { ... } else { response.put("authenticated", false); }`. Add the two fields **after** the if/else, just before `return response;`, so they are present for both authenticated and anonymous callers:

```java
        // Instance indicator — returned regardless of auth state so login.html
        // (anonymous) can read it too. Empty on prod.
        response.put("instanceLabel", instanceLabel == null ? "" : instanceLabel);
        response.put("instanceBanner", instanceBanner == null ? "" : instanceBanner);
        return response;
```

(Replace the existing trailing `return response;` of `getSession` with the block above.)

- [x] **Step 4: Compile to verify it builds**

Run: `cd /Users/vikasjindal/git/plm-field-tracker && mvn -q -o compile 2>&1 | tail -20`
Expected: BUILD SUCCESS (no compile errors). If offline `-o` fails for missing artifacts, drop `-o`.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/AuthController.java src/main/resources/application.properties
git commit -m "feat(qa-indicators): expose app.instance.label/banner from /api/auth/session"
```

---

## Task 2: Create the shared instance-badge.js module

**Files:**
- Create: `src/main/resources/static/instance-badge.js`

- [x] **Step 1: Write the module**

Create `src/main/resources/static/instance-badge.js` with exactly:

```js
// instance-badge.js — QA/TEST instance indicator, shared by index.html (app)
// and login.html (login). Driven by app.instance.label / app.instance.banner,
// exposed via /api/auth/session. Empty values render nothing (prod).
(function () {
  'use strict';

  var PILL_BG = '#B8342B';    // red pill
  var RIBBON_BG = '#C7801B';  // amber warning ribbon

  function el(tag, css, text) {
    var e = document.createElement(tag);
    if (css) e.style.cssText = css;
    if (text != null) e.textContent = text;
    return e;
  }

  function makePill(label) {
    return el('span',
      'display:inline-flex;align-items:center;gap:5px;height:20px;padding:0 9px;margin-left:8px;' +
      'border-radius:999px;background:' + PILL_BG + ';color:#fff;font-weight:700;font-size:10.5px;' +
      "letter-spacing:.06em;font-family:'IBM Plex Mono',monospace;vertical-align:middle;",
      '● ' + label);
  }

  function dismissKey(version) { return 'plm-qa-ribbon-dismissed-' + (version || 'unknown'); }

  function ribbonDismissed(version) {
    try { return localStorage.getItem(dismissKey(version)) === '1'; } catch (e) { return false; }
  }

  function makeRibbon(text, version, dismissible) {
    var bar = el('div',
      'position:relative;background:' + RIBBON_BG + ';color:#fff;text-align:center;font-weight:600;' +
      "font-size:12px;padding:6px 38px;font-family:'IBM Plex Sans','Segoe UI',Arial,sans-serif;", text);
    if (dismissible) {
      var x = el('button',
        'position:absolute;right:8px;top:50%;transform:translateY(-50%);background:transparent;border:none;' +
        'color:#fff;cursor:pointer;font-size:14px;line-height:1;padding:2px 8px;border-radius:4px;', '✕');
      x.setAttribute('aria-label', 'Dismiss');
      x.onclick = function () {
        try { localStorage.setItem(dismissKey(version), '1'); } catch (e) {}
        if (bar.parentNode) bar.parentNode.removeChild(bar);
      };
      bar.appendChild(x);
    }
    return bar;
  }

  // Public entry point. opts: { label, banner, mode:'app'|'login', version }
  window.renderInstanceIndicator = function (opts) {
    opts = opts || {};
    var label = (opts.label || '').trim();
    var banner = (opts.banner || '').trim();
    var mode = opts.mode || 'app';
    var version = opts.version || window.appBuildVersion || '';
    if (!label && !banner) return; // prod: render nothing

    if (mode === 'login') {
      if (banner) document.body.insertBefore(makeRibbon(banner, version, false), document.body.firstChild);
      if (label) {
        var brand = document.querySelector('.topbar .brand-mark');
        if (brand) brand.appendChild(makePill(label));
        // Correct the hardcoded green "Production" crumb pill.
        var crumbPill = document.querySelector('.left-pane .crumb .pill');
        if (crumbPill) {
          crumbPill.innerHTML = '<span style="width:6px;height:6px;border-radius:50%;background:' +
            PILL_BG + ';display:inline-block"></span>' + label;
        }
        // Correct the hardcoded "· prod" suffix in the topbar version line.
        var tv = document.getElementById('topbarVersion');
        if (tv) tv.innerHTML = tv.innerHTML.replace(/prod/i, label.toLowerCase());
      }
    } else {
      if (banner && !ribbonDismissed(version)) {
        var nav = document.querySelector('.navbar');
        if (nav && nav.parentNode) nav.parentNode.insertBefore(makeRibbon(banner, version, true), nav);
      }
      if (label) {
        var b = document.querySelector('.navbar .brand');
        if (b) b.appendChild(makePill(label));
      }
    }
  };

  // Login page self-loads (no session bootstrap there). Opt in via a body attribute.
  if (document.body && document.body.getAttribute('data-instance-autoload') === 'login') {
    fetch('/api/auth/session', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        window.renderInstanceIndicator({ label: d.instanceLabel, banner: d.instanceBanner, mode: 'login' });
      })
      .catch(function () { /* server down -> render nothing, same as today */ });
  }
})();
```

- [x] **Step 2: Commit**

```bash
git add src/main/resources/static/instance-badge.js
git commit -m "feat(qa-indicators): shared instance-badge.js render module"
```

---

## Task 3: Wire authenticated pages (index.html + app.js)

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/app.js:362` (inside the existing `/api/auth/session` handler)

- [x] **Step 1: Include the script in index.html**

In `index.html`, find where `app.js` is included (search `app.js`) and add the module **before** it so `renderInstanceIndicator` is defined when `app.js` runs:

```html
<script src="instance-badge.js?v=20260617"></script>
```

- [x] **Step 2: Call the renderer from the session handler**

In `app.js`, inside the `.then(function(data) { ... })` of the `fetch('/api/auth/session')` block, locate (near line 362):

```js
                window.appBuildVersion = ver;
                if (typeof npApplyBetaState === 'function') npApplyBetaState(ver);
            }
```

Immediately **after** that closing `}` of the `if (data.buildLabel) { ... }` block, add:

```js
            if (typeof renderInstanceIndicator === 'function') {
                renderInstanceIndicator({
                    label: data.instanceLabel,
                    banner: data.instanceBanner,
                    mode: 'app',
                    version: window.appBuildVersion || ''
                });
            }
```

- [x] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/app.js
git commit -m "feat(qa-indicators): render TEST pill/ribbon on authenticated pages"
```

---

## Task 4: Wire the pre-login page (login.html)

**Files:**
- Modify: `src/main/resources/static/login.html`

- [x] **Step 1: Add the autoload attribute to `<body>`**

In `login.html`, change `<body>` (line ~175) to:

```html
<body data-instance-autoload="login">
```

- [x] **Step 2: Include the module after the existing scripts**

In `login.html`, after the existing `<script src="whats-new.js"></script>` (line ~282), add:

```html
<script src="instance-badge.js?v=20260617"></script>
```

(The module self-fetches and renders because of the body attribute. It runs after the DOM exists since the tag is at the end of `<body>`.)

- [x] **Step 3: Commit**

```bash
git add src/main/resources/static/login.html
git commit -m "feat(qa-indicators): show TEST indicator + fix hardcoded prod labels on login page"
```

---

## Task 5: Update What's New changelog (pre-build requirement)

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [x] **Step 1: Add a new entry at the TOP of `WHATS_NEW_RELEASES`**

Open `whats-new.js`, find the `WHATS_NEW_RELEASES` array, and insert as the first element (match the existing entry's exact object shape — verify keys before editing):

```js
  {
    date: '2026-06-17',
    title: 'QA / Test instance indicators',
    items: {
      new: [
        'Test instances now show a red TEST pill and an amber warning ribbon (login and in-app) so it is obvious when you are not on production.',
        'Outbound email from a test instance is sent from a -qa sender address.'
      ],
      improve: [
        'The sign-in page reflects the real environment instead of always showing "Production".'
      ],
      fix: []
    }
  },
```

- [x] **Step 2: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): QA/test instance indicators entry"
```

---

## Task 6: Build the JAR and stage it

**Files:** none (build artifacts only)

- [x] **Step 1: Build with Corretto 8**

Run:
```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home \
  mvn -q clean package -DskipTests 2>&1 | tail -25
```
Expected: BUILD SUCCESS, `target/plm-field-tracker-1.0.1.jar` produced.

- [x] **Step 2: Verify the jar exists and note its size**

Run: `stat -f "%z  %N" /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar`
Expected: a size in the ~50 MB range printed.

---

## Task 7: Local end-to-end verification

**Files:** none (runtime verification). Requires the local setup at `~/Documents/plm-toolkit 2/`.

- [x] **Step 1: Stage the freshly built jar locally**

Run:
```bash
cp /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
   ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```

- [x] **Step 2: Add the two instance props to the LOCAL config (temporary, for the test)**

Append to `~/Documents/plm-toolkit 2/config/application.properties`:
```properties
app.instance.label=TEST
app.instance.banner=⚠ QA / TEST — PROD data, write-back ON
```

- [x] **Step 3: Start the local instance**

Run (background):
```bash
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar \
  --spring.config.additional-location=file:./config/application.properties
```
Wait for `Started` in the log / port 8090 to answer.

- [x] **Step 4: Verify the endpoint returns the fields ANONYMOUSLY (pre-auth)**

Run:
```bash
curl -sS http://localhost:8090/api/auth/session | python3 -m json.tool
```
Expected: JSON containing `"authenticated": false`, `"instanceLabel": "TEST"`, and `"instanceBanner": "⚠ QA / TEST — PROD data, write-back ON"`.

- [x] **Step 5: Verify login.html renders the indicator**

Log in via curl to get a cookie, then fetch login.html is not needed — instead confirm the static asset is served:
```bash
curl -sS http://localhost:8090/instance-badge.js | head -3
curl -sS "http://localhost:8090/login.html" | grep -i 'data-instance-autoload\|instance-badge.js'
```
Expected: the module source prints; login.html contains both the `data-instance-autoload="login"` attribute and the `instance-badge.js` script tag. (Optional visual: open http://localhost:8090/login.html in a browser and confirm the amber ribbon at top, the red TEST pill, and the left-pane crumb reading "TEST" instead of green "Production".)

- [x] **Step 6: Confirm prod parity (no props -> nothing)**

Comment out / remove the two `app.instance.*` lines from the local config, restart, and re-run the Step-4 curl. Expected: `instanceLabel` and `instanceBanner` are empty strings; no pill/ribbon. Then restore the test lines if continuing to test, or leave removed.

- [x] **Step 7: Stop the local instance**

Stop the background java process (Ctrl-C / kill the PID).

---

## Task 8: Stage to the prod share and set QA config

**Files:**
- Modify (QA box, not git): `/Volumes/uls-eq-agliqss/plm-toolkit/config/application.properties`

- [x] **Step 1: Copy the jar to staging on the prod share**

Run (verify the share is mounted first; if `/Volumes/uls-ep-aglipccb/` is missing, stop and report):
```bash
cp /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
   /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
stat -f "%z" /Users/vikasjindal/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar
stat -f "%z" /Volumes/uls-ep-aglipccb/plm-toolkit/staging/plm-field-tracker-1.0.1.jar
```
Expected: the two sizes match.

- [x] **Step 2: Set the instance props in the QSS config**

Append to `/Volumes/uls-eq-agliqss/plm-toolkit/config/application.properties` (the `mail.from`/`-qa` lines are already there from earlier):
```properties
app.instance.label=TEST
app.instance.banner=⚠ QA / TEST — PROD data, write-back ON
```

- [x] **Step 3: Hand-off note**

The QA box (QSS) does not run the new jar until Vikas copies the staged jar into `F:\plm-toolkit\` and relaunches the watchdog (`run-loop.bat`). Prod (PCCB) is unaffected: it keeps its current jar, and even after a future prod deploy it stays clean because `app.instance.*` are unset there.

---

## Self-Review Notes

- **Spec coverage:** email `-qa` (done, Task 8 sets nothing new — already applied); backend fields (Task 1); shared module (Task 2); app pages (Task 3); login page incl. "Production" crumb fix (Task 4) + bonus `#topbarVersion` "· prod" fix; whats-new (Task 5); build/stage/prod-safety (Tasks 6–8). All spec sections map to a task.
- **Prod safety:** every render path early-returns when both values are empty; bundled defaults are empty; verified in Task 7 Step 6.
- **UTF-8:** the `⚠` lives in `application.properties` (Spring reads UTF-8) and is passed through as-is; verify rendering on the Windows box during cutover.
