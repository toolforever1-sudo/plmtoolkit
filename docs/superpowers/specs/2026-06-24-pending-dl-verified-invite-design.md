# Pending DL requests — verified, manual invite send — Design

**Date:** 2026-06-24
**Surface:** PLM Toolkit → More → User Management → Users → **Pending DL requests** panel
**Source handoff:** `~/Downloads/ADD_USER_FROM_AD_HANDOFF (1).md` (Part 2; Part 1 already shipped)
**Status:** Approved (design), pending implementation plan

## Problem

Welcome/invite emails currently fire **as a side effect** of loading the User
Management tab after a restart: `UserPermissionsController.users()` calls
`reconcilePendingAgainstDl(...)` and then, for each request it auto-completes,
calls `dlWelcomeEmailService.sendWelcome(...)`. That's the wrong trigger — the
admin adds people to the `IT-APP-Agile-admin` DL out-of-band, and the invite
should go out **on demand, only after confirming the person is actually in the
DL**, never as a deploy side effect.

## What it becomes

- **No automatic send.** The auto-send block is removed; no startup/page-load/
  scheduler path emails invites.
- Each pending request has a manual **Send invite** action (+ bulk Send all /
  Send selected).
- On send, the backend does a **live DL-membership check** per user:
  in DL → email + mark sent; not in DL → no email, "Not in DL yet", retryable.
- An **"Add to DL ↗"** link per awaiting row + a summary banner after each send.

## Decisions (from brainstorming)

- **Live, uncached** AD membership check per user (new `isUserInAccessGroup`),
  so someone added to the DL seconds ago is recognized immediately. Fail-closed:
  if the check can't confirm membership (incl. AD error), no email.
- **Panel state derived from `welcomeSentAt`**: set → "Invite sent" (completed),
  unset → "Awaiting" (send + add-to-DL). The in-DL-or-not distinction is resolved
  live at Send time (checking → sent, or "Not in DL yet" → retry).

## Existing code touched (exact anchors)

- `UserPermissionsController.users()` — the auto-send loop at **lines 197–205**
  (`for (PendingRequest p : autoCompleted) { ... sendWelcome(p) ... markWelcomeSent(..., "system", ...) }`).
- `UserPermissionsController.sendWelcomeInvite()` — the old single endpoint
  `POST /dl-request/{sAMAccountName}/welcome` at **lines 409–429** (gated on
  `status=="completed"`, no DL check).
- `DlWelcomeEmailService.sendWelcome(PendingRequest)` → `boolean` — reused as-is
  (does not itself check DL membership).
- `LdapAuthService.isUserInAdminGroup(String)` (lines 885–926) — the template for
  the new access-group check; `fetchCandidatesFromLdap()` shows how the access
  group CN is derived (`ldap.required.groups` first entry).
- Frontend: `permsRenderPending()` (js 160–221), `permsSendInvite()` (223–235),
  `permsClearCompleted()` (237–244); panel markup `index.html` line 2490.
- `PendingRequest` fields: `sAMAccountName, displayName, email, requestedTabs,
  requestedBy, requestedByDisplay, requestedAt, status ("pending"|"completed"),
  completedAt, welcomeSentAt, welcomeSentBy, welcomeSentByDisplay`.

## Backend

### B1 — Remove auto-send
Delete lines 197–205 of `users()` (the `for (PendingRequest p : autoCompleted)`
welcome-email loop). Keep `reconcilePendingAgainstDl(...)` and the `autoCompleted`
call (still flips `status` for the dashboard counts; just no email). After the
change, the only remaining `sendWelcome(...)` caller is the new endpoint (B3).
The old `sendWelcomeInvite` endpoint (B3) is replaced, so its `sendWelcome` call
goes away too.

### B2 — Live DL membership check (`LdapAuthService`)
Add `public boolean isUserInAccessGroup(String username)`, mirroring
`isUserInAdminGroup` but matching the **access group CN** (first entry of
`ldap.required.groups`, e.g. `IT-APP-Agile-admin`) in the user's `memberOf`:
- Direct LDAP query (service-bind, 5s connect/read timeouts), `countLimit(1)`,
  filter `(&(objectClass=user)(sAMAccountName=<esc>))`, read `memberOf`,
  case-insensitive `contains` on the access group CN.
- Returns `false` on no-match, no-user, or any exception (logged). The access
  group CN is read from `requiredGroups` (already an injected field); if it's
  blank, returns `false`.

### B3 — New endpoint (replaces the single `/welcome`)
`POST /api/permissions/dl-requests/send-invites` (admin-gated via `isPermsAdmin`),
body `{ "usernames": ["jane.doe", ...] }` (sAMAccountName identifiers — requests
have no separate id; deviation from the handoff's `/api/dl-requests/...`+
`requestIds` is intentional, keeping the existing `/api/permissions` auth gate).

Per username:
1. `findPendingByUsername(u)` → null → `failed: NOT_FOUND`.
2. `ldapAuthService.isUserInAccessGroup(u)`:
   - **false** → no email → `failed: NOT_IN_DL`.
   - **true** → `dlWelcomeEmailService.sendWelcome(req)`:
     - success → `markWelcomeSent(u, actorUsername, actorDisplay)`; if
       `status != "completed"`, set it (so the row reads "Invite sent") → `sent`.
     - false → `failed: EMAIL_FAILED`.

One user's failure never aborts the batch. Activity-log a single summary
(`PERMISSIONS_DL_INVITES sent=N failed=M`). Response:
```json
{ "sent":   [ { "username":"jane.doe", "displayName":"Jane Doe" } ],
  "failed": [ { "username":"manav", "displayName":"Manav Hirani", "reason":"NOT_IN_DL" } ] }
```
Reasons: `NOT_IN_DL`, `NOT_FOUND`, `EMAIL_FAILED`. Remove the old
`sendWelcomeInvite` endpoint + route.

> To set status to completed when an invite is sent to an in-DL user whose
> request was still "pending", add a small service method
> `markInviteSent(username, actor, actorDisplay)` that does
> `markWelcomeSent` + ensures `status="completed"`/`completedAt` — or extend the
> endpoint to call `markWelcomeSent` then a status nudge. Keep the mutation in the
> service (synchronized), not the controller.

## Frontend (panel rework, v2 tokens)

`permsRenderPending()` rebuilt. Per-row state from `p.welcomeSentAt`:

| State | Condition | Pill | Actions |
|---|---|---|---|
| completed | `welcomeSentAt` set | green "Invite sent" (`--good-bg/--good-ink`) | **Resend invite** (ghost) |
| awaiting | `welcomeSentAt` unset | amber "Awaiting DL add" (`--warn-bg`) | checkbox · **Send invite** (accent) · **Add to DL ↗** (adAddUrl) |
| checking | transient (send in flight) | blue "Checking DL…" (`--accent-2/--accent-ink`) + spinner | — |
| failed | transient, reason NOT_IN_DL | red "Not in DL yet" (bad tokens) | checkbox · **Send invite** (retry) · **Add to DL ↗** |

- **Header:** title + count, **Clear completed**, and bulk **Send all invites →**
  / **Send N selected →** (disabled when 0 awaiting targets). A "Select all
  pending" checkbox toggles all awaiting/failed rows.
- New `permsSendInvites(usernames[])`: set those rows to `checking`, POST the new
  endpoint, then render per-row sent/failed from the response and a **summary
  banner** (in-panel `#permsPendingBanner`, not native):
  - all sent → success: "✓ N invites sent."
  - all NOT_IN_DL → warning: "⚠ {names} not in the IT-APP-Agile-admin DL yet. Add
    them, then try Send again. No email was sent."
  - mixed → "✓ N sent · ⚠ not in DL yet: {names}. Add them and retry — no email
    sent to those."
  - then `permsLoadUsers()` to refresh from the server.
- `permsSendInvite(sam)` (single, per-row) delegates to `permsSendInvites([sam])`.
- Selection state held on `permsState` (e.g. `permsPendingSel` set); cleared on reload.
- All affordances use v2 tokens; brand red logo-only (bad pill uses the bad/error token).

## Error handling

- Per-user failures isolated and reported with a reason; the batch always returns 200 with sent/failed lists.
- Endpoint-level exception → 500 `{success:false,error}`.
- AD unreachable during the live check → `isUserInAccessGroup` returns `false` →
  reason `NOT_IN_DL` (fail-closed: never email when membership can't be
  confirmed). The banner tells the admin to add + retry.
- Empty `usernames` → 400 "No requests selected."

## Testing

This part is IO/UI-heavy (LDAP + SMTP + DOM) with little pure-unit surface.

- **Backend unit (pure):** if a small pure helper falls out (e.g. classifying a
  per-user outcome into a reason, or composing the summary counts), unit-test it.
  Otherwise none — do not write Spring-context tests for the IO paths.
- **Local smoke test (`:8090`, live AD reachable):**
  - The auto-send block is gone — loading `/api/permissions/users` sends no email
    (verify no `[DL-WELCOME]` send in the log on tab load).
  - `POST /dl-requests/send-invites` for a user **not** in the DL → `failed:
    NOT_IN_DL`, no email, `welcomeSentAt` unchanged. (Safe — no email leaves the box.)
  - `isUserInAccessGroup` returns the right boolean for an in-DL vs not-in-DL user.
- **Server (handed to Vikas):** the actual in-DL → real invite email path (it
  sends a live email), since we don't want to email real users from the Mac.

## Out of scope (YAGNI)

- Per-request unique ids (sAMAccountName is the key).
- Changing `DlWelcomeEmailService` internals or the email template.
- Reworking the single-add/`request-add` flow (Part 1, already shipped).
- Persisting transient checking/failed states server-side (UI-only).
