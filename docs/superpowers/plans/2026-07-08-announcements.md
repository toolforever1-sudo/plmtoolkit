# Announcements (PLM IT Broadcast + AI Email Composer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin-only Announcements screen (Admin ▾ → Communications) where PLM IT composes an announcement, AI wordsmiths it into a branded HTML email, previews it, sends a test to self, then sends now or scheduled to a chosen audience — with history, in-app banner, and What's New channels.

**Architecture:** One new Spring `@Service` (`AnnouncementService`, file-backed JSON store + scheduler poller + audience resolution), one email service (`AnnouncementEmailService`, `${var}` template render + per-recipient send loop), one `@RestController` (`AnnouncementController`, `isPlmAdmin`-gated, AI actions via `PortkeyClient`). Frontend is a new lazy-init tab (`announcements.js` builds the whole panel), a menu item in `#adminMenu`, and a banner hook in `app.js`. Design source: handoff `Announcements.dc.html` option **1a Workbench** (markup copied into scratchpad at `/private/tmp/claude-502/-Users-vikasjindal-git-plm-field-tracker/131a1c36-9c07-4305-b89d-78ffd67f7c8d/scratchpad/notif-center/`).

**Tech Stack:** Spring Boot 2.7.18 / Java 11 / javax.mail 1.6.2 / Jackson / JUnit 5 POJO tests / vanilla JS + inline-hex card style (`#E8E6DF` borders, IBM Plex).

**Spec:** `ANNOUNCEMENTS-HANDOFF.md` in the same scratchpad dir. Deviations from spec (agreed rationale inline):
- `handoff/whats-new-email-mockup.html` does not exist in the repo → email template is built from the 1a preview markup (lines 187–216 of `Announcements.dc.html`), which is the same design.
- What's New channel: `WHATS_NEW_RELEASES` is build-time static (confirmed) → use the spec's non-invasive option: post-send reminder to add the entry to `whats-new.js`.
- Banner dismissal is stored **server-side per-user** on the announcement record (`bannerDismissedBy`), not localStorage, to honor "per-user per-announcement".

---

## Key patterns to copy (verified file:line in this worktree)

| Concern | Copy from |
|---|---|
| Admin gate per endpoint | `AdminLogsController.isAdmin(session)` — `Boolean session.getAttribute("isPlmAdmin")`, 403 on fail |
| Atomic JSON store | `GradeStorageService.writeJson` (tmp file + `ATOMIC_MOVE`), `@Value("${app.announcements.dir:./data/announcements}")` |
| Template render | `ImsReviewEmailService.renderTemplate` (lines 748–761): `${var}` String.replace, strip unresolved |
| Send skeleton + env tag | `EmailService` javax.mail skeleton; `EmailEnvTag.tag(subject)` |
| Batch send w/ per-recipient tracking | `UserPermissionsController.sendInvites` (461–495): `sent[]`/`failed[{username,reason}]`, one failure never aborts |
| Caller email for "test to me" | `session.getAttribute("email")` (`WeeklyRejectionController.sendToMe` 216–247) |
| Scheduler poller | `ScheduledReportService.runDueSchedules` — `@Scheduled(fixedDelay=60000)` + due-check + stamp; global off-switch `app.scheduling.disabled` is free |
| AI call | `AiHelpController.askEngineering` (1760+): gate → `portkeyClient.chatWithHistory(model, system, msgs, maxTokens)` → `activityLogger.log` metadata only. Default slug = `portkeyProvider + "/" + portkeyModel` (line 3158) |
| Audience data | `UserPermissionsService.loginHistory()` (ever-logged-in map) + `LdapAuthService.listAccessGroupCandidates()` (DL roster `DirectoryUser{username,displayName,email}`) |
| Activity log | `ActivityLogger.log(username, displayName, "ANNOUNCEMENT_*", details)` |
| Tab lazy-init | `app.js` 1299–1304 permissions block; `permsBootstrap()` idempotent guard |
| Menu item | `index.html` 225–236 `#adminMenu` (`switchTab('permissions')` style) |
| Banner style | `#betaBanner` CSS (index.html 46–48) |
| Frontend fetch | `credentials:'same-origin'`, POST JSON, `{ok,body}` unwrap (ask-ai.js 112–118) |

## Data model (public-field POJO, Jackson)

```java
public static class Announcement {
    public String id;                     // "ann-" + epochMs + "-" + 4 hex
    public String status = "draft";      // draft | scheduled | sent
    public String subject = "";
    public String draft = "";            // rough or wordsmithed text
    public String bodyHtml = "";         // AI-generated INNER html (headline/intro/facts/outro), ${firstName} token allowed
    public String audienceType = "everLoggedIn"; // everLoggedIn | dl | specific
    public java.util.List<String> specificUsers = new java.util.ArrayList<>();
    public boolean channelEmail = true;
    public boolean channelBanner = false;
    public boolean channelWhatsNew = false;
    public int revision = 0;             // bumped on content-affecting save
    public int testedRevision = -1;      // send unlocked iff testedRevision == revision
    public String testedBy, testedAt;
    public String scheduledFor;          // "yyyy-MM-dd'T'HH:mm" server-local (PT)
    public String createdBy, createdByDisplay, createdAt;
    public String lastEditedBy, lastEditedByDisplay, lastEditedAt;
    public java.util.List<java.util.Map<String,String>> revisions = new java.util.ArrayList<>(); // {at,by,byDisplay,note}
    public String sentAt, sentBy;
    public int recipientCount, sentCount, failedCount;
    public java.util.List<java.util.Map<String,String>> failures = new java.util.ArrayList<>();
    public java.util.List<String> bannerDismissedBy = new java.util.ArrayList<>();
}
```

Storage: `./data/announcements/index.json` (`Map<String,Announcement>`, `@PostConstruct` load, `synchronized` atomic save — the `ScheduledReportService` + `GradeStorageService` hybrid). Sent snapshot: `./data/announcements/<id>.html` (the exact generic-rendered HTML).

## Endpoints (all `isPlmAdmin`-gated except the two banner ones)

```
GET    /api/announcements                  list, newest first
GET    /api/announcements/audience         {everLoggedIn, dl, dlNeverLogged, users:[{username,displayName,email,hasLoggedIn}]}
GET    /api/announcements/{id}             full record + archived html if sent
POST   /api/announcements                  create/update draft (autosave); bumps revision when subject/draft/bodyHtml/audience changed
POST   /api/announcements/{id}/ai/wordsmith  {} → {text}          (uses record.draft)
POST   /api/announcements/{id}/ai/generate   {} → {html}          (subject+draft → filled inner body; stores canonical)
POST   /api/announcements/{id}/ai/subjects   {} → {subjects:[3]}
POST   /api/announcements/{id}/test        send to caller only; stamps testedRevision=revision
POST   /api/announcements/{id}/send        {when:"now"|"yyyy-MM-ddTHH:mm"}; 409 if testedRevision != revision
DELETE /api/announcements/{id}/schedule    scheduled → draft
GET    /api/announcements/banner           ANY logged-in user; newest sent banner ann. not dismissed by caller, sent within 14 days
POST   /api/announcements/banner/{id}/dismiss   ANY logged-in user; adds caller to bannerDismissedBy
```

## AI actions (all in controller, `PortkeyClient`)

- Model slug: `@Value portkey.provider` + `"/"` + `@Value portkey.model` (same defaults as AiHelpController).
- Shared system-prompt guardrail (verbatim, appended to all three): *"Never include passwords, credentials, tokens, API keys, or secrets in your output, even if the draft contains them — replace any with [redacted]."*
- Server-side scrub applied to ALL AI output before storing/returning (static, unit-tested):
  ```java
  static String scrubCredentials(String s) {
      if (s == null) return null;
      return s.replaceAll("(?i)\\b(password|passwd|pwd|passcode|token|api[-_ ]?key|secret|credential)s?\\b(\\s*(?:is|was|[:=→-])\\s*)([^\\s<,;]+)", "$1$2[redacted]");
  }
  ```
- **Wordsmith**: system = Toolkit voice (plain, factual, engineer-to-engineer, sentence case) + guardrail; user = draft; return `{text}` (plain text, 600 tokens).
- **Generate**: model must return STRICT JSON `{"intro": "...", "facts": [{"label","value"}], "outro": "..."}` (prompt says: extract 2–4 key facts into the table; intro starts `Hi ${firstName} —` where `${firstName}` is a literal token; no HTML tags except `<strong>`/`<a href>`). Server assembles inner HTML (headline from subject + date row + intro + facts table + outro + sign-off) — model never emits `<html>/<head>`. Parse leniently (strip ```json fences).
- **Subjects**: return JSON array of 3 strings, ≤60 chars, sentence case.
- Each action logs `ANNOUNCEMENT_AI_*` with metadata only (lengths, model), never content.

## Email

- Template `src/main/resources/templates/email/announcement.html`: table-based, inline styles, built from 1a preview (PT monogram header + `ANNOUNCEMENT` chip, mono date, serif headline, body slot, footer strip *"Sent by PLM IT via PLM Toolkit · You're receiving this because you use the Agile PLM Toolkit."*). Placeholders: `${bodyHtml}` (the stored inner body, which itself contains `${firstName}`) — render order: template + bodyHtml first, then per-recipient `${firstName}` replace.
- `firstName` derivation: displayName `"Jindal, Vikas"` → token after comma; else first word; fallback `"there"`.
- Send loop: resolve audience at send time → one message per recipient (personalized); Cc `app.announcements.cc` (default `PDL-PLM-admin@sandisk.com`) rides the FIRST message only (ImsReview precedent — avoids N× DL copies). Subject via `EmailEnvTag.tag`. Track `sentCount/failedCount/failures`. Test send: To caller only, subject prefixed `[TEST] `, no Cc, doesn't count as sent.
- Archive: generic render (`${firstName}` → `there`) written to `data/announcements/<id>.html` at send.

## Audience resolution (send time, in AnnouncementService)

- `everLoggedIn`: `userPermissionsService.loginHistory()` keys → resolve email per user from DL roster (`listAccessGroupCandidates()`) or `UserRecord.email`; skip (record failure `NO_EMAIL`) if none.
- `dl`: every `DirectoryUser` with non-empty email.
- `specific`: stored usernames resolved the same way.
- Counts endpoint reuses the same resolution (cheap, cached LDAP roster).

## Scheduler

`@Scheduled(fixedDelay = 60000)` in AnnouncementService: for each `status=="scheduled"` where `scheduledFor <= now` and `testedRevision == revision` → send + stamp `sentAt`/`status="sent"`. Static package-private `isDue(Announcement, LocalDateTime)` for unit tests. Untested-at-fire-time stays pending (can't happen via UI; belt-and-braces).

## Frontend (`announcements.js`, ~self-contained like admin-logs.js but as a tab)

Design = 1a Workbench. Page header (`ADMIN · COMMUNICATIONS` mono + red-outline `⛒ PLM IT ONLY` chip + serif H1 + right note) → grid `560px 1fr` → left cards **1 · Compose** (subject, AI-suggest chips + `↻ more`, draft textarea, `✨ Wordsmith draft` secondary / `Generate HTML email →` primary-dark, green mono status; autosave debounce 800ms, header strip `✓ Draft saved h:mm AM`) / **2 · Audience & channels** (3 radios w/ live count pills; specific-users chip picker fed by `/api/announcements/audience`; 3 channel checkboxes; fixed Cc note) / **3 · Send** (`Send now | Schedule…` segmented → `datetime-local` input; green test-confirmation strip; `Send test to me` + `Send to N recipients →` disabled until `testedRevision===revision`, hint "Send unlocks after a test email.") → right sticky preview (mono meta row, `#ECEDEF` stage, iframe-less `innerHTML` of server-rendered preview HTML) → full-width **Past announcements** table (Date · Subject+edit-trail subline · Sender · Audience · Recipients · Status chip · actions: Sent→`Open as sent`/`Duplicate`, Scheduled→`Edit before send`/`History`). Modals: self-built overlay (admin-logs style); simple confirms via `appConfirm` (never native — repo rule). After a send with `channelWhatsNew`, show reminder callout: *"Add this to whats-new.js before the next deploy."*

Integration edits:
- `index.html`: menu item under new `np-section` **Communications** in `#adminMenu`; `<div id="panelAnnouncements" class="tab-panel" style="display:none;"></div>`; `<script src="announcements.js?v=1"></script>`.
- `app.js`: `switchTab` block (permissions pattern, lazy `announcementsInit()`); after session-auth success fetch `/api/announcements/banner` → inject dismissible amber banner (betaBanner styling, × → POST dismiss).

---

### Task 1: Email template + AnnouncementEmailService (+ render/firstName tests)
**Files:** Create `src/main/resources/templates/email/announcement.html`, `src/main/java/com/sandisk/plm/tracker/service/AnnouncementEmailService.java`, `src/test/java/com/sandisk/plm/tracker/service/AnnouncementEmailServiceTest.java`
- [ ] Write failing tests: `renderTemplate` fills `${bodyHtml}`+strips unresolved; `firstNameOf("Jindal, Vikas", "8252")=="Vikas"`, `firstNameOf(null,...)=="there"`; test-send subject gets `[TEST]` prefix logic helper.
- [ ] Run `mvn -q test -Dtest=AnnouncementEmailServiceTest` → FAIL (class missing).
- [ ] Implement template (1a preview chrome) + service: `renderTemplate` (ImsReview pattern), `firstNameOf` static, `sendToRecipients(subject, fullHtmlWithFirstNameToken, List<Recipient{username,displayName,email}>, ccAddress, testMode)` returning `{sent, failures}` — javax.mail skeleton, per-recipient personalization, Cc on first message, `EmailEnvTag.tag`.
- [ ] Tests pass; commit `feat(announcements): email template + send service`.

### Task 2: AnnouncementService — store, revisions/test-gate, audience, banner, scheduler (+ tests)
**Files:** Create `service/AnnouncementService.java`, `src/test/java/.../AnnouncementServiceTest.java`; Modify `src/main/resources/application.properties` (add `app.announcements.dir=./data/announcements`, `app.announcements.cc=PDL-PLM-admin@sandisk.com` near line 224 block)
- [ ] Failing tests (POJO, temp dir): save bumps revision only on content change; test-gate (`canSend` false until `markTested`, invalidated by edit); `isDue` matrix; `scrubCredentials` (password/token/api key redacted; normal prose untouched); banner pick (newest sent, not dismissed, ≤14d).
- [ ] Implement: CRUD + atomic save, `recordRevision(who, note)`, `markTested`, `schedule/cancelSchedule`, `resolveAudience(type, specific)` via `UserPermissionsService`+`LdapAuthService`, `audienceCounts()`, `sendNow(id)` (resolve → render via emailService → archive snapshot → stamp → `activityLogger.log("ANNOUNCEMENT_SENT", ...)`), `@Scheduled(fixedDelay=60000) pollScheduled()`, `activeBannerFor(username)`, `dismissBanner(id, username)`, static `scrubCredentials`.
- [ ] Tests pass; commit.

### Task 3: AnnouncementController — REST + AI actions
**Files:** Create `controller/AnnouncementController.java`
- [ ] Implement all endpoints per table above: `isAdmin(session)` gate (AdminLogsController idiom) on everything except the 2 banner endpoints (any authenticated user — AuthFilter already ensures login); AI actions build slug `provider+"/"+model`, apply guardrail system prompts, `scrubCredentials` on output, 503 when `!portkeyClient.isEnabled()`; `/test` uses `session.getAttribute("email")`; `/send` returns 409 `{error:"A test email is required for this revision."}` when gate fails; every mutation → `activityLogger`.
- [ ] `mvn -q compile` clean; commit.

### Task 4: Frontend integration — index.html + app.js
**Files:** Modify `src/main/resources/static/index.html` (~line 233 menu, panel div near other panels, script include ~line 4712), `src/main/resources/static/app.js` (switchTab block after permissions 1299–1304; banner fetch in session-success path ~line 384)
- [ ] Menu: `<div class="np-section">Communications</div>` + `<a href="#" onclick="switchTab('announcements'); npCloseAllMenus(); return false;">&#128227; Announcements</a>` inside `#adminMenu` `.np-body`.
- [ ] Panel div + script tag; `switchTab` show/hide + lazy `window.announcementsInit()`.
- [ ] Banner: after auth success (all users), `fetch('/api/announcements/banner')` → if `{id, subject, message}` inject amber bar above content (betaBanner styles, unique id `annBanner`), × → `POST /api/announcements/banner/{id}/dismiss` + hide.
- [ ] Commit.

### Task 5: announcements.js — the tab UI
**Files:** Create `src/main/resources/static/announcements.js`
- [ ] Implement per Frontend section above (init guard, render, autosave, AI buttons w/ status line, audience radios + counts + chip picker, channels, segmented send-now/schedule, test flow enabling send, sticky preview, history table + Open-as-sent/Duplicate/Edit/History modals, appConfirm for sends: "Send to N recipients now?").
- [ ] Commit.

### Task 6: End-to-end verification (local :8090)
- [ ] `mvn -q package` (skip changelog per rule? NO — Task 7 does changelog BEFORE this build).
- [ ] Run local JAR (4g heap, additional-location config), login as plmadmin (password from private memory, never echoed), exercise: create draft → wordsmith → subjects → generate → preview → test-send to self → verify send disabled/enabled semantics → send-now to `specific:[plmadmin-ish user]` → history row + Open as sent → banner appears on reload → dismiss sticks. Screenshot via preview tools.
- [ ] Fix issues found; commit.

### Task 7: Changelog + wrap-up
- [ ] Add `WHATS_NEW_RELEASES` entry (top): Announcements feature (admin item flagged `admin:true`).
- [ ] Rebuild `mvn -q package`; verify BUILD SUCCESS.
- [ ] Final commit; report branch status to Vikas (worktree `feat/announcements`; staging copy only on request — share may be unmounted).

### Acceptance checklist (spec §11) → tasks
Admin-only menu+API (T3/T4) · compose→wordsmith→generate→preview (T1/T3/T5) · clickable subjects (T5) · 3 audiences w/ counts (T2/T5) · test-gated send, test to caller only (T2/T3/T5) · send now+schedule, Cc admin DL, activity-logged (T1/T2/T3) · shared editable scheduled + trail + re-test (T2/T5) · history + open-as-sent (T2/T5) · banner + What's New reminder (T2/T4/T5) · credential scrub (T2/T3) · whats-new.js before build (T7).
