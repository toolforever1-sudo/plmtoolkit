# PLM Toolkit — by-the-numbers brief

**Hand-off:** to a design AI (or human designer) — pick whatever sections turn into the slide / one-pager / blog post you're making.
**Audience:** SanDisk PLM leadership + cross-functional reviewers.
**Project name:** PLM Toolkit (a.k.a. plm-field-tracker)
**Built by:** Vikas Jindal with Claude Code (Anthropic CLI) as the implementation partner.
**Built between:** 2026-04-12 → 2026-05-14 (32 calendar days)

---

## The headline number

> **An enterprise Spring Boot application normally takes 2 developers ~10 months to build was built in 32 days by 1 person + Claude.**

Roughly a **10× productivity multiplier** over a strong 2-developer team.

---

## Code stats (as of 2026-05-14)

| Category | Lines | Files |
|---|---:|---:|
| **Java** (production) | 49,804 | 141 |
| **JavaScript** | 26,038 | 34 |
| **HTML** | 7,278 | 7 |
| **CSS** | 685 | 2 |
| **Properties / config** | 263 | — |
| **Agile-service** (companion microservice) | 894 | 7 |
| **Code subtotal** | **84,962** | **191** |
| Design specs / hand-offs | 13,764 | 28 |
| Deploy scripts (`.bat`) | 351 | 4 |
| **Grand total** | **~99,100** | |

**157 git commits.** First commit: April 12. Last commit: May 10. Average ~5 commits per day.

---

## The 2-dev estimate, broken down

Industry rate for non-trivial enterprise production code is **30–50 finished LOC/dev/day** (including design, testing, iteration, code review, infrastructure debugging). This codebase is on the high-complexity end:

- Spring Boot + Oracle SQL + LDAP/AD + Agile SDK + LLM (Portkey/Anthropic/Azure-AI) + Excel I/O + multi-service architecture + Windows-server deploy pipeline + custom analytics

| Feature group | 2-dev weeks |
|---|---:|
| Spring Boot scaffold + auth/session + LDAP integration | 3–4 |
| Change History (SQL, filters, export, paging) | 4–5 |
| BOM tab + BOM Compare + BOM Explorer | 4–5 |
| ECN Report (multi-panel, Python sidecar, cycle-time, returns tracker) | 4–6 |
| Items + Agile Lookup (with companion microservice) + SKU Lookup + Part Extract | 5–6 |
| AI Eval tab (LLM integration, personas, eval runs) | 3–4 |
| Single/Sole Source report | 2 |
| Feedback Queue + Help drawer + Help docs + What's New | 3 |
| Admin tools (logs viewer, file archive, user permissions, maintenance mode) | 3–4 |
| Volume Reports + Team Reports (Excel exports, charts) | 3 |
| Scheduled jobs, watchdog, deploy pipeline, prod ops, monitoring | 3–4 |
| Polish, testing, iteration, requirements changes, fires | 4–6 |
| **Total** | **41–52 weeks** |

Calendar time for 2 devs in parallel: **~9–12 months**.

---

## What's in it (feature inventory)

15 top-level tabs, each non-trivial:

- **Items** → Field Changes, Part Extract, Agile Lookup, SKU Lookup
- **BOM** → BOM Compare (same-part + different-parts modes), BOM Explorer, Where-Used, BOM Race
- **Change History** → SQL-backed search with filters, exports, user popovers
- **Utilities** → AD Bulk Lookup (with Smart Fill), Activity Log viewer, Data Compare
- **ECN Report** → Cycle Time + Returns Tracker views, monthly trends, POM KPIs, per-team breakdown, Python-sidecar analytics
- **Single/Sole Source** → risk surfacing report
- **Items / BOM / Change History** quick search via the **Help drawer** chatbot
- **Ask AI** + **Run Eval (admin)** sub-tabs
- **Single/Sole Source** report
- **Labs (admin)** — experimental features

Cross-cutting:

- **Feedback Queue** — every issue/feature request tracked with a PT-XXXX ID, attachments, AI-auto-grading
- **Admin tools** — Server Logs viewer, File Archive (uploads kept 30 days, downloads metadata-only for 90 days), maintenance mode, user permissions, scheduled-reports manager
- **AI integration** — Portkey gateway for Anthropic / Azure / Vertex routing, LLM-backed help chatbot, AI Eval framework, expectation validator, gibberish gate, smart header/column detection
- **Persistence** — file-archive with daily purge job, activity log JSONL, scheduled report state, item-cache file-serialised, LDAP/cred caches
- **Operations** — Windows watchdog (`run-loop.bat`), atomic deploy (`deploy.bat`), maintenance mode with scheduled `System.exit`, file-archive auto-purge at 03:00, full audit trail of admin actions

---

## Highlight reel — shipped in this 30-day window

Each of these is its own "amazing thing" — pick whichever ones fit the slide.

### 1. The self-improvement loop
A user grades an AI answer C/D/F + types what they expected → the system auto-files a bug, the poller picks it up within minutes, the agent edits the code + builds + deploys (with a maintenance window) + re-runs the original query + emails a before/after verdict. Humans set the goal; the agent carries the rope.
*"The user's expectation is the spec."*

### 2. Smart Fill for AD lookups
Drop a Bibi-Anita-style access-certification spreadsheet, click one button, get back **10,052 cells filled** across **6 sheets** with Job Title / Department / Status from AD. **3,336 of 3,355 people resolved (99.4%).** Manual cost: ~30 sec/row × 3,355 = **~28 hours per BPO per certification cycle.** New cost: ~1 minute. **~500–1,000 hours/year saved across all BPOs.**

### 3. File Archive
Every upload kept on disk for 30 days; every download fingerprinted via SHA-256 metadata for 90 days. Captured at the toolkit/user boundary via one centralized service + a servlet filter — so all 18+ upload/download paths get coverage without per-controller wiring. Admin viewer with filter + pin-permanent + on-demand purge.

### 4. Audio bars + voice auto-submit
Tap the mic on Ask AI or Help → 5 small bars dance in time with the speech recognizer's events (no parallel mic stream — avoids starving Chrome's internal capture). Tap again to stop; if what you said is usable, the Ask button auto-clicks. Saves the extra hop.

### 5. Gibberish gate
Two layers (heuristic + LLM) so that gibberish bug reports never reach the triage queue. Vowel-ratio + consonant-run + token-level vowel checks — Latin-only, so CJK / Hindi / Arabic / Hebrew script users are never falsely flagged.

### 6. Smart xlsx parsing
- Auto-detect the header row by scoring the first 10 rows (handles "title row + blank row + actual header" pattern).
- Auto-detect the part-number / login-id column by blending header-name match with value-shape.
- Ambiguous case → asks the user with a modal showing 3 sample values per candidate, then retries with the chosen column. No re-upload needed.

### 7. Operational resilience
- Portkey 429 / network errors → automatic retry with classification (transport vs. semantic) at both the client and per-team levels.
- Agile-service HTTP 500s now surface the actual SDK error instead of "HTTP 500" with no body.
- Multipart cap raised from Spring's default 1 MB → 25 MB after a real-world Agile Lookup failure.
- Watchdog auto-respawns the JVM unless an admin sets a stop sentinel; atomic deploy script handles the JVM swap without orphaning processes.
- Maintenance mode: schedule a 2-min shutdown via an admin endpoint; users get an in-app banner + email before the JVM exits cleanly.

### 8. Admin Server Logs viewer
View any `.log` file in the prod logs directory from the UI — tail the last N lines, grep with optional ±context, jump-to-bottom auto-scroll. Path-traversal blocked, RandomAccessFile-based seek (so even an 87 MB `watchdog.log` reads at most ~1 MB).

### 9. Feedback Queue auto-fixer
A "/poll-feedback" loop reads the prod feedback queue, classifies each new item as MINOR (auto-fix), NEEDS-CONFIRM (ask in chat), or UNCLEAR (email Vikas). For MINOR items, the agent runs the full build → deploy local → smoke-test → email round trip without needing approval.

### 10. AI Eval framework
Run synthetic personas (Manufacturing analyst, BOM editor, etc.) against the help chatbot, grade the answers with another LLM, surface regressions. Used to validate that toolkit-side help responses don't drift between model versions.

---

## Architectural one-liner

> **Single Spring Boot JVM + companion Agile-service microservice + Python sidecar for analytics + SMB-share-based deploys + Windows watchdog + Portkey LLM gateway + Oracle Agile SDK + LDAP/AD direct.** All admin actions audited. All file I/O archived. All AI responses retried.

---

## Visual direction suggestions

(Match the existing email design guide in `~/git/CLAUDE.md` — same palette / typography so anything you produce reads as a sibling of the automated emails the toolkit sends.)

### Palette

| Role | Hex |
|---|---|
| Primary accent | `#4a6fa5` |
| Header / dark surface | `#2c3e50` |
| Page background | `#FAFAF7` |
| Card / node fill | `#ffffff` |
| Border / hairline | `#E8E6DF` |
| Ink | `#0F1720` |
| Muted text | `#6B7280` |
| Success | `#1F8A4C` |
| Warning | `#C7801B` |
| Error | `#B8342B` |

### Typography

- **Slide titles** / serif accent: `IBM Plex Serif` bold
- **Body / table headers**: `IBM Plex Sans` regular/semibold
- **Numbers / timestamps / KPI tiles**: `IBM Plex Mono` 11–14pt

### Suggested KPI tiles (for the top of any leadership-grade slide)

```
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ 32 days      │ 84,962       │ 157 commits  │ ~10×         │
│ Built in     │ Lines of     │ Across 32    │ Productivity │
│              │ production   │ days         │ multiplier   │
│              │ code         │              │ over 2 devs  │
└──────────────┴──────────────┴──────────────┴──────────────┘
```

OR the per-feature ROI numbers:

```
┌──────────────┬──────────────┬──────────────┐
│ 10,052       │ 99.4 %       │ ~28 hrs      │
│ Cells        │ AD resolution│ Saved per    │
│ auto-filled  │ rate on the  │ BPO per      │
│ in 1 minute  │ Silicon file │ cert cycle   │
└──────────────┴──────────────┴──────────────┘
```

### Don't

- Don't use the WDC brand or refer to the company as Western Digital anywhere in user-facing material. Everything in this project belongs to **SanDisk**.
- Don't use emojis unless the slide explicitly calls for them.
- No drop shadows. Flat 1px borders only. Single accent color on arrows / links.

---

## Caveats / fine print (for honesty)

- The 9–12 month estimate for 2 humans assumes both devs already have full access to SanDisk's AD, Oracle Agile, and LLM infrastructure from day one. Realistic on-boarding tax: another 1–2 months.
- "Done" here means *currently shipping in prod with daily user feedback*, not "feature complete." Daily iterations continue.
- Spec docs (~14K lines, 28 files) wouldn't typically be written by humans on a project this size — that's overhead the AI adds for its own continuity across sessions. Discount that from the comparison if needed.
- Some of the 84,962 code lines are denser (SQL queries, AI prompts, multi-step controllers); some are shallower (HTML markup, Excel POI plumbing). Net effect roughly cancels out around the industry rate.

---

## File location

This brief lives at `docs/handoffs/plm-toolkit-by-the-numbers-brief.md`.
The companion AI self-improvement loop brief is at `docs/handoffs/ai-self-improvement-loop-slide-brief.md`.
