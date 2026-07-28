# IMS Review tab — pilot design

**Status**: approved 2026-05-18 · pilot scope (P0 + P1 + attachment upload)
**Source brief**: ECN-129414-PROJ CRD ("New DRR Process Flow") + Process Flow Diagram.png
**Author**: Vikas Jindal (PLM IT)

## Why

Today, when a Document Owner (DO) needs to approve / update an IMS document for its periodic review, the Agile SDK weekly job emails them, and they have to log into Agile to approve or reject. The CRD asks for a toolkit-driven flow where the email carries the document attachment + a deep link, and DO/DM act through the toolkit. The full CRD is multi-phase. This pilot covers the *manually-triggered* version of the flow that lets DCC try the end-to-end UX without decommissioning the SDK job.

## In-pilot scope

| | |
|---|---|
| DCC manually triggers email from a per-row button in the new **IMS Review** tab (admin view) | ✓ |
| Email to DO with the doc attachments inline (≤10 MB) or "review in Agile" placeholder | ✓ |
| Deep link in email → toolkit IMS Review tab (DO/DM card view) | ✓ |
| Three DO responses: No Change Needed · Needs Change (file upload via side panel) · Need Help | ✓ |
| DM second-stage confirmation flow (Confirm No Change · Send Back to DO) | ✓ |
| Get File per row (lazy fetch via plm-agile-service, same code path as Shop-Floor Docs) | ✓ |
| Auto-grant of IMS Review tab to LDAP-authed DO/DMs (session-only, no permissions catalog write) | ✓ |
| DCC analysts granted via existing User Management (`ims-review` key) | ✓ |
| Admins always see the tab | ✓ |
| All emails Cc `vikas.singh3@sandisk.com` (placeholder for `IMS-Doc-Managers-Agile@sandisk.com`) + `pdl-plm-admin@sandisk.com` | ✓ |
| Inactive-DO path: To flips to `vikas.singh3@sandisk.com`, banner in body, Cc keeps `pdl-plm-admin@sandisk.com` | ✓ |
| Local-test email redirect (config flag → all outbound mail to `vikas.jindal@sandisk.com` with original-To/Cc shown in body) | ✓ |
| Activity-log events for every state transition (`IMS_REVIEW_*`) | ✓ |

## Out-of-pilot (phase 2+)

- Toolkit auto-creates a DCO in Agile when DO clicks Needs Change (Agile-side write integration)
- DCC Dashboard (operational dashboard is folded into the admin view of IMS Review for now)
- History writeback to the DRR in Agile
- Auto-followup cadence (manual Resend by DCC during pilot)
- Decommission of the existing Agile SDK weekly DRR job

## Architecture

```
Browser ── HTTPS ──▶ PLM Toolkit JAR (Spring Boot)
                     ├── ImsReviewController          (/api/ims-review/*)
                     ├── ImsReviewService
                     ├── ImsReviewQueueStore          (JSONL persistence)
                     ├── ImsReviewEmailRenderer       (HTML templates)
                     ├── ActivityLogger              (existing)
                     ├── LDAP                        (existing — auth + manager lookup)
                     ├── Oracle dataSource           (existing — doc + owner lookup)
                     ├── SMTP relay                  (existing — mailrelay.sandisk.com:25)
                     └── AgileServiceClient          (existing) ──▶ plm-agile-service
                                                                    └── new: GET /agile/document/{n}/attachments

State on disk:
  data/ims-review/queue.jsonl       — append-only event log
  data/ims-review/uploads/           — DO uploads from "Needs Change"
  data/activity-log.jsonl           — existing; gets IMS_REVIEW_* events

Config (local-only override):
  app.ims-review.email-redirect-to=vikas.jindal@sandisk.com
```

No DB schema changes. No new infrastructure. Reuses every existing service.

## Data model

JSONL event log is the source of truth, replayed into an in-memory index on JVM start.

**Event shape:**

```jsonc
{
  "ts": "2026-05-18T15:22:18Z",
  "type": "SEND_TO_DO",
  "docNumber": "33-05-SM-03-00011",
  "drrNumber": "DRR-0015516",
  "actor":      { "email": "...", "displayName": "...", "role": "DCC" },
  "recipients": ["mahadi.abdulrahim@sandisk.com"],
  "note":       "...",
  "uploadFile": "uploads/33-05-SM-03-00011-1779169305-updated.docx",
  "fileCount":  3,
  "attachedToEmail": true,
  "doInactive": false,
  "dmFallback": false
}
```

**Event types** map 1:1 to state transitions:

| Type | Trigger | Result status |
|---|---|---|
| `SEND_TO_DO` | DCC clicks Send / Resend / round-2 cascade | `SENT_TO_DO` |
| `DO_RESPONSE_NO_CHANGE` | DO clicks "No Change Needed" | cascades into `SEND_TO_DM` |
| `SEND_TO_DM` | auto after DO No Change | `SENT_TO_DM` |
| `DM_RESPONSE_APPROVED` | DM clicks "Confirm No Change" | `DM_APPROVED` (terminal) |
| `DM_RESPONSE_SEND_BACK` | DM clicks "Send Back to DO" | cascades into fresh `SEND_TO_DO` |
| `DO_RESPONSE_NEEDS_CHANGE` | DO submits Needs Change side panel | `DO_NEEDS_CHANGE` (terminal) |
| `DO_RESPONSE_NEED_HELP` | DO submits Need Help | `DO_NEED_HELP` (terminal) |
| `CANCEL` | DCC clicks Cancel | `CANCELLED` (terminal) |

## Backend flows

Eight flows, all under `/api/ims-review/`:

| Endpoint | Method | Role | Purpose |
|---|---|---|---|
| `/data` | GET | admin/DCC | Admin view rows (all docs in window + status) |
| `/my-queue` | GET | DO/DM | Card view rows (only docs assigned to the session user) |
| `/role` | GET | any | Returns user's effective role + queue size; JS uses to pick admin vs DO/DM view |
| `/send` | POST | admin/DCC | Send to DO (or Resend) for a single doc |
| `/respond` | POST (multipart) | DO/DM | Submit one of the 3+2 response options; multipart for Needs Change file |
| `/cancel` | POST | admin/DCC | Cancel an in-flight queue item |
| `/file` | GET | any | Get File (proxies plm-agile-service) |
| `/export` | GET | admin/DCC | Excel export of the admin view |

Defense-in-depth: every `/respond` call cross-checks `session.email == queueItem.currentRecipient` before accepting.

DM identification: LDAP `manager` attribute on the DO. On lookup failure, fall back to `vikas.singh3@sandisk.com` with `dmFallback=true` on the event row.

## Frontend

**Tab placement**: top-level, between *ECN Report* and *Shop-Floor Docs*, with `tabImsReview` button + `panelImsReview` div in `index.html`.

**Same tab, three views** — JS reads `GET /api/ims-review/role` on tab open:
- `ADMIN` or `DCC` → admin table view (sortable columns, status pills, KPI strip, per-row Send/Resend/Cancel/GetFile, Excel export)
- `DO_DM` → card view (one card per pending doc, three response buttons for DO cards / two for DM cards, Get File per card)

**Side panel** (`<aside id="imsNeedsChangePanel">`) for both Needs Change and Need Help. Single file dropzone (25 MB cap) + optional notes textarea.

**Email template** has two variants (due-in N days · overdue N days, with Important flag) baked into one HTML file with conditional substitution. Inactive-DO banner is a conditional block at the top.

## Auth & permissions

- `ims-review` key added to `UserPermissionsService.TAB_CATALOG`
- Existing auth flow unchanged at the LDAP layer
- After successful LDAP bind, an `AuthFilter` hook checks `QueueStore.hasPendingItemsFor(userEmail)`; on hit, sets `session.imsReviewAutoGranted=true` and adds `ims-review` to the session's `allowedTabs`. Not persisted; evaporates on session timeout.
- Admins always see the tab via existing admin-role behavior.

## Email rendering & delivery

Plain HTML templates under `src/main/resources/templates/email/ims-review-*.html`. Variable substitution via `String.replace` (matches existing `EmailService` pattern). SanDisk-styled per `CLAUDE.md` email guidelines.

Recipients always Cc `vikas.singh3@sandisk.com` + `pdl-plm-admin@sandisk.com`. Recipient list de-duped so an address never appears in both `To:` and `Cc:`.

**Local-test redirect**: when `app.ims-review.email-redirect-to` is set (only in `config/application.properties` on the local dev machine), the EmailService:
1. Overrides `To:` and `Cc:` to that single address
2. Prepends a yellow banner to the HTML body:
   > ⚠ Local test mode — would have gone to **`<original-To>`**, Cc **`<original-Cc>`**
3. Suffixes the subject with `[LOCAL TEST]`

So the user can verify routing during dev without spamming real DOs. Empty in prod (default) → real send.

## plm-agile-service additions

One new endpoint: `GET /agile/document/{number}/attachments?asZip={auto|true|false}` — returns single file or zipped multi, with `X-Attachment-Count` + `X-Attachment-Total-Bytes` headers. Reuses Shop-Floor Docs SDK code paths. Service-token auth (existing).

Toolkit-side `AgileServiceClient.fetchDocumentAttachments(docNumber, mode)` wraps the call.

## Cross-cutting

**Activity log events**: `IMS_REVIEW_SEND` · `IMS_REVIEW_RESEND` · `IMS_REVIEW_CANCEL` · `IMS_REVIEW_DO_RESPONSE` · `IMS_REVIEW_DM_RESPONSE` · `IMS_REVIEW_GET_FILE` · `IMS_REVIEW_AUTO_GRANT`.

**Error handling** — fail loudly to the UI but non-destructively in the audit trail. SMTP failure does NOT append an event (avoids stale SENT status). Agile attachment failure DOES append the event with `attachedToEmail=false`.

**Testing**:
- Unit tests for `QueueStore`, `EmailRenderer`, `LdapManagerLookup`
- Integration tests on `ImsReviewController` with mocked SDK + email
- Manual smoke checklist in the PR description (due-in + overdue + inactive-DO + >10MB attachment + each button)

**Rollback**: revert the JAR. No DB migrations, no infra.

## File-level surface area

**New files (toolkit):**
- `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java`
- `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java`
- `src/main/java/com/sandisk/plm/tracker/service/ImsReviewQueueStore.java`
- `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailRenderer.java`
- `src/main/resources/static/imsreview.js`
- `src/main/resources/templates/email/ims-review-do.html`
- `src/main/resources/templates/email/ims-review-dm.html`
- `src/main/resources/templates/email/ims-review-dcc-needs-change.html`
- `src/main/resources/templates/email/ims-review-dcc-need-help.html`
- `src/main/resources/templates/email/ims-review-dm-approved.html`

**Modified files (toolkit):**
- `src/main/resources/static/index.html` — nav button + panel + side panel + script include
- `src/main/resources/static/app.js` — `switchTab('ims-review')` plumbing + permissions + log name + TAB_PREFS_CONFIG
- `src/main/resources/static/whats-new.js` — release entry
- `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java` — add `ims-review` to TAB_CATALOG
- `src/main/java/com/sandisk/plm/tracker/config/AuthFilter.java` — session-only grant hook
- `src/main/java/com/sandisk/plm/tracker/service/AgileServiceClient.java` (or equivalent) — new `fetchDocumentAttachments` method
- `src/main/resources/application.properties` — new `app.ims-review.email-redirect-to` key (default empty)

**plm-agile-service** (separate repo): one new controller method for `/agile/document/{n}/attachments`.

**Local config override** (`~/Documents/plm-toolkit 2/config/application.properties`):
- `app.ims-review.email-redirect-to=vikas.jindal@sandisk.com`
