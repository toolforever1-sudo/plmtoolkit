# QA Instance Indicators — Design

**Date:** 2026-06-17
**Status:** Approved (pending spec review)
**Box:** ULS-EQ-AGLIQSS (QA), points at PROD Agile URL + PROD DB, IMS write-back ON, scheduled jobs OFF.

## Problem

A second toolkit instance now runs on the QA box (ULS-EQ-AGLIQSS) against **live production Agile data** with write-back enabled. Users and recipients need an unmistakable signal that they are on the test instance, not prod, so that:

1. Outbound emails are recognizable as coming from QA, not prod.
2. Anyone looking at the UI immediately knows they are on the test box before taking an action that writes to production.

Both prod and QA run the **same JAR**, so every difference must be driven by external `application.properties` — no prod-only code paths.

## Scope

In scope:
- Tag all QA outbound email with a `-qa` sender. **(already done — config only)**
- A visual TEST indicator in the toolkit UI (authenticated pages), shown only on the QA box.
- The same indicator on the **pre-login page** (`login.html`), including correcting its hardcoded "Production" pill so it reflects the actual environment.

Out of scope:
- Any change to prod behavior or appearance.
- SDSM share relocation, HTTPS cert reissue, firewall (tracked separately as QA standup tasks).
- A general multi-environment framework. This is a single config-gated label/banner; nothing more.

## Part 1 — Email sender tagging (DONE)

Every email sender resolves its From address from one of two Spring properties:
- `mail.from` — used by ~10 services (AdminController, EcnReportController, ReportService, AccessRequestService, SupportController, WeeklyRejectionService, UploadQuarantineService, HeapPressureMonitor, AiHelpController, etc.)
- `app.singlesole.email.from` — SingleSoleSourceEmailService only.

Setting both in the QA `application.properties` tags every outbound message. No code change.

```properties
mail.from=PLM-Toolkit-qa@sandisk.com
app.singlesole.email.from=PLM-Toolkit-qa@sandisk.com
```

These are already written to `F:\plm-toolkit\config\application.properties` on QSS. No mailbox needs to exist — the SanDisk relay accepts unauthenticated mail with any From; recipients simply see the `-qa` sender.

## Part 2 — UI TEST indicator (Option D: ribbon + pill)

### Config surface

Two new properties, both **default empty**. Empty on prod → nothing renders. Set only in QA's external `application.properties`.

```properties
app.instance.label=TEST
app.instance.banner=⚠ QA / TEST — PROD data, write-back ON
```

Behavior matrix:

| `label` | `banner` | Result |
|---|---|---|
| empty | (any) | Nothing renders (prod default) |
| set | empty | Pill only |
| set | set | Pill + ribbon (Option D, QA) |

The pill is gated on `label`; the ribbon is gated on `banner`. The pill is the permanent signal; the ribbon is the loud, dismissible warning.

### Backend

`AuthController` `/api/auth/session` is the bootstrap the frontend already calls on every page load (and on a 5-minute heartbeat). Add two fields to its response map:

- `instanceLabel` ← `@Value("${app.instance.label:}")`
- `instanceBanner` ← `@Value("${app.instance.banner:}")`

No new endpoint. Empty strings when unset. **The two fields are returned unconditionally — regardless of authentication state** — so the pre-login page can read them. This is safe and free: `AuthFilter` already whitelists `/api/auth/*` (line 44) for anonymous access, and an environment label is not sensitive.

### Frontend

To avoid duplicating render logic across the two pages, factor the indicator into a small shared module **`instance-badge.js`** (new static file, included by both `index.html` and `login.html`). It exposes one function:

```js
renderInstanceIndicator({ label, banner, mode })   // mode: 'app' | 'login'
```

It injects the pill + (optional) ribbon, scaled to the page. Both the shared pill/ribbon CSS lives in this module's own `<style>` injection (or a tiny shared CSS), so neither page duplicates it.

**Authenticated pages (`index.html`, `mode:'app'`)** — called from the existing `/api/auth/session` success handler in `app.js` that already populates `#buildPill` (~line 349), passing the data already in hand:

- **Pill** — if `label` non-empty, inject a red pill (`● <label>`) into the `.brand` area of the navbar, immediately after "Agile PLM Toolkit". Solid `#B8342B`, white bold, reusing the `.np-pill` shape. **Always visible; not dismissible.**
- **Ribbon** — if `banner` non-empty, render the amber warning bar above `.navbar`. Solid `#C7801B` fill, white text, centered, full width, dismiss X on the right (mirrors `#betaBanner`). **Dismissible**, dismissal keyed to the running app version (same mechanism as `#betaBanner`); reappears on every new deploy. The pill stays after the ribbon is dismissed.

**Pre-login page (`login.html`, `mode:'login'`)** — `instance-badge.js` does its own `fetch('/api/auth/session')` on load (the endpoint is public), then:

- **Ribbon** — if `banner` non-empty, render the same amber bar at the very top of the page, above `.topbar`. On this page it is **non-dismissible** — the login screen is a brief gate, so we keep the warning at maximum signal.
- **Pill** — if `label` non-empty, show the red `● <label>` pill in the `.topbar` brand-mark area.
- **Correct the misleading "Production" crumb** — `login.html` (~line 193) hardcodes a green-dot pill reading "Production" in the left pane. When `label` is non-empty, flip that pill to read the label (e.g. "TEST") with the warning color and a red/amber dot instead of green. When `label` is empty (prod), it stays the green "Production" pill unchanged.

Markup is created client-side, consistent with how `#buildPill` and `#betaBanner` already work; each page ships only an empty mount point (and its existing structure), and the shared module fills them once the data arrives.

### Visual reference

- **App pages:** Option D from the brainstorm mockups — amber ribbon above a white navbar carrying the SanDisk brand + red `● TEST` pill, existing clock/version pills on the right.
- **Login page:** amber ribbon at the very top, red `● TEST` pill in the top bar, and the left-pane environment crumb showing "TEST" (warning color) instead of green "Production".

## Deploy mechanics

This is a code change → JAR rebuild, but it is fully config-gated and **prod-safe** (prod sets neither property → byte-for-byte identical appearance/behavior). Same artifact deploys everywhere.

Per project CLAUDE.md:
1. Update `src/main/resources/static/whats-new.js` (new entry, today's date) **before** building.
2. `mvn package` → `target/plm-field-tracker-1.0.1.jar`.
3. Copy to `staging/` on the prod share and to local `~/Documents/plm-toolkit 2/` (never the live folder).
4. Vikas does the QSS cutover and adds the two `app.instance.*` lines to QSS config (label/banner) — the `mail.from` lines are already present.
5. Prod is untouched until its next normal deploy; even then it stays clean because the properties are unset there.

## Testing

- **Unit/local:** start the local toolkit with `app.instance.label=TEST` and `app.instance.banner=...` set; confirm `/api/auth/session` returns both fields and the authenticated UI shows pill + dismissible ribbon. Restart without the properties; confirm nothing renders (prod parity).
- **Pre-login (unauthenticated):** load `login.html` while logged out; confirm `instance-badge.js` fetches `/api/auth/session` anonymously, the amber ribbon shows at top (non-dismissible), the top-bar pill shows, and the left-pane crumb reads "TEST" in warning color instead of green "Production". With properties unset, confirm the crumb still reads green "Production" and no ribbon/pill appear.
- **Dismiss behavior (app pages):** dismiss the ribbon, reload — ribbon stays gone for the same version; pill still present. Bump the version key — ribbon returns.
- **Email:** with `mail.from=PLM-Toolkit-qa@sandisk.com` set, trigger a Send-to-me email and confirm the From header carries `-qa`.

## Risks / notes

- The indicator depends on a successful `/api/auth/session` call. On `login.html` this is an anonymous fetch (endpoint is whitelisted); if it fails (server down), the page simply renders without the indicator — no worse than today.
- Banner text contains a non-ASCII `⚠`; ensure the properties file is read as UTF-8 (Spring default) — verify rendering on the Windows box.
- `login.html` and `index.html` must both include `instance-badge.js`. If a future page is added that bypasses both, it won't show the indicator — acceptable; the two entry points cover all real access.

## Addendum (2026-06-17) — auto prod/non-prod banner wording

The ribbon now auto-adapts its "PROD data" wording instead of hardcoding it.

- **`InstanceEnvService`** computes `isProdData()` = `dbProd || agileProd`:
  - `dbProd` — toolkit's own `spring.datasource.jdbc-url` contains `agprod` or `uls-dp`.
  - `agileProd` — fetched from agile-service `GET /api/lookup/health` (existing endpoint already returns `agileUrl`); prod if it contains `uls-ep`. Unreachable → treated as prod (conservative). Cached after first success.
  - Conservative by design: only non-prod when **both** sides are non-prod, so it never under-warns while writes could still hit prod Agile.
- `/api/auth/session` adds `instanceProdData` (bool) + `instanceBannerNonprod` (string).
- New config `app.instance.banner-nonprod` (default empty) holds the softer text; the frontend shows it when `instanceProdData` is false, falling back to `app.instance.banner` if unset.
- No `plm-agile-service` change required — its existing `/api/lookup/health` already exposes `agileUrl`.
- Covered by `InstanceEnvServiceTest` (7 cases). The pre-existing `ChangesControllerTest` `@WebMvcTest` slice failure (missing `SessionRegistry` bean) is unrelated and predates this work; project builds with `-DskipTests` as usual.
