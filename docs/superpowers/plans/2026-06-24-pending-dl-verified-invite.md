# Pending DL Verified Invite Send — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop auto-sending DL welcome emails on tab load; make invite sending a manual, per-row/bulk action gated on a live AD DL-membership check, with awaiting/checking/completed/failed states and a result banner.

**Architecture:** Remove the auto-send loop from `users()`. Add a direct (uncached) `isUserInAccessGroup` LDAP check. Add `POST /api/permissions/dl-requests/send-invites` that, per user, verifies DL membership live and only then emails + marks sent; replaces the old `/welcome` endpoint. Rework the pending panel JS for multi-select + live-check states.

**Tech Stack:** Java 11 / Spring Boot, JUnit 5, vanilla JS, v2 design tokens.

**Spec:** `docs/superpowers/specs/2026-06-24-pending-dl-verified-invite-design.md`

**Build command (Java 11 — required):**
`JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn ...`
(There are 3 PRE-EXISTING `ChangesControllerTest` errors from a @WebMvcTest/SessionRegistry issue — expected; ignore. No OTHER failures allowed.)

---

## File Structure

**Modify:**
- `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java` — add static `inviteReason`; remove the auto-send loop in `users()`; remove old `sendWelcomeInvite`; add `sendInvites` endpoint.
- `src/main/java/com/sandisk/plm/tracker/service/LdapAuthService.java` — add `isUserInAccessGroup`.
- `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java` — enhance `markWelcomeSent` (status nudge + activity log).
- `src/main/resources/static/index.html` — pending-panel header (bulk buttons) + banner + select-all bar.
- `src/main/resources/static/user-permissions.js` — rework `permsRenderPending`, replace `permsSendInvite`, add multi-select + `permsSendInvites`.
- `src/main/resources/static/whats-new.js` — changelog entry.

**Create:**
- `src/test/java/com/sandisk/plm/tracker/controller/InviteReasonTest.java`

---

## Task 1: `inviteReason` classifier (TDD)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`
- Test: `src/test/java/com/sandisk/plm/tracker/controller/InviteReasonTest.java`

A pure static decision function: given (request found?, user in DL?, email sent ok?), return the failure reason or `null` when the invite was sent.

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InviteReasonTest {

    @Test
    void notFound() {
        assertEquals("NOT_FOUND", UserPermissionsController.inviteReason(false, false, false));
        assertEquals("NOT_FOUND", UserPermissionsController.inviteReason(false, true, true));
    }

    @Test
    void foundButNotInDl() {
        assertEquals("NOT_IN_DL", UserPermissionsController.inviteReason(true, false, false));
    }

    @Test
    void inDlButEmailFailed() {
        assertEquals("EMAIL_FAILED", UserPermissionsController.inviteReason(true, true, false));
    }

    @Test
    void sentReturnsNull() {
        assertNull(UserPermissionsController.inviteReason(true, true, true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=InviteReasonTest`
Expected: FAIL — `inviteReason` not defined.

- [ ] **Step 3: Add the method**

Add this static method to `UserPermissionsController` (e.g. near the other private static helpers like `strOf`):

```java
    /**
     * Classify a single send-invite attempt. Returns null when the invite was
     * sent; otherwise a failure reason for the response.
     */
    static String inviteReason(boolean found, boolean inDl, boolean emailedOk) {
        if (!found) return "NOT_FOUND";
        if (!inDl) return "NOT_IN_DL";
        if (!emailedOk) return "EMAIL_FAILED";
        return null;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=InviteReasonTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java \
        src/test/java/com/sandisk/plm/tracker/controller/InviteReasonTest.java
git commit -m "feat(user-mgmt): inviteReason classifier for verified DL invites"
```

---

## Task 2: Remove auto-send + add live DL check

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`
- Modify: `src/main/java/com/sandisk/plm/tracker/service/LdapAuthService.java`

No new unit test (deletion + an LDAP-IO method). Compile + confirm no auto-send caller remains.

- [ ] **Step 1: Remove the auto-send loop** in `UserPermissionsController.users()`. Delete exactly this block (currently ~lines 197–205):

```java
        // For each request that just auto-completed AND the user has 0 logins,
        // send a welcome invite email. We do this OUTSIDE the service's
        // synchronized block so SMTP latency doesn't hold the lock.
        for (PendingRequest p : autoCompleted) {
            if (p.welcomeSentAt != null && !p.welcomeSentAt.isEmpty()) continue;
            String key = p.sAMAccountName == null ? "" : p.sAMAccountName.toLowerCase();
            if (logins.containsKey(key) && logins.get(key).loginCount > 0) continue;
            if (dlWelcomeEmailService.sendWelcome(p)) {
                permissionsService.markWelcomeSent(p.sAMAccountName, "system", "PLM Toolkit");
            }
        }
```

Keep the preceding `List<PendingRequest> autoCompleted = permissionsService.reconcilePendingAgainstDl(dlUsernamesLower);` line — `autoCompleted` is now unused, so also change that line to drop the assignment:

```java
        permissionsService.reconcilePendingAgainstDl(dlUsernamesLower);
```

- [ ] **Step 2: Add the live access-group check** to `LdapAuthService`, immediately after the `isUserInAdminGroup(...)` method (around line 927). It mirrors that method but matches the access-group CN (first entry of `requiredGroups`) in `memberOf`:

```java
    /**
     * Live (uncached) check: is {@code username} a member of the access DL
     * (first entry of {@code ldap.required.groups}, e.g. IT-APP-Agile-admin)?
     * Queries AD directly so a just-added member is recognized immediately.
     * Fail-closed: returns false on no-match, missing user, unconfigured group,
     * or any error — callers must never email when membership can't be confirmed.
     */
    public boolean isUserInAccessGroup(String username) {
        if (username == null || username.isEmpty()) return false;
        String accessGroupCn = null;
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            accessGroupCn = requiredGroups.split(",")[0].trim();
        }
        if (accessGroupCn == null || accessGroupCn.isEmpty()) return false;
        String needle = accessGroupCn.toUpperCase();
        try {
            Hashtable<String, String> svcEnv = new Hashtable<>();
            svcEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            svcEnv.put(Context.PROVIDER_URL, ldapUrl);
            svcEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
            svcEnv.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
            svcEnv.put(Context.SECURITY_CREDENTIALS, servicePassword);
            svcEnv.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
            svcEnv.put("com.sun.jndi.ldap.connect.timeout", "5000");
            svcEnv.put("com.sun.jndi.ldap.read.timeout", "5000");

            DirContext ctx = null;
            try {
                ctx = new InitialDirContext(svcEnv);
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(new String[]{"memberOf"});
                controls.setCountLimit(1);

                String filter = "(&(objectClass=user)(sAMAccountName=" + escLdap(username) + "))";
                NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);
                if (!results.hasMore()) return false;

                SearchResult sr = results.next();
                Attribute memberOf = sr.getAttributes().get("memberOf");
                if (memberOf == null) return false;

                for (int i = 0; i < memberOf.size(); i++) {
                    String groupDn = (String) memberOf.get(i);
                    if (groupDn != null && groupDn.toUpperCase().contains(needle)) return true;
                }
                return false;
            } finally {
                close(ctx);
            }
        } catch (Exception e) {
            logger.warning("[LDAP] Access group check failed for " + username + ": " + e.getMessage());
            return false;
        }
    }
```

- [ ] **Step 3: Compile + confirm no auto-send caller remains**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.
Run: `grep -n "sendWelcome(" src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`
Expected: exactly ONE remaining match — inside `sendWelcomeInvite` (removed in Task 3). The auto-send caller is gone.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java \
        src/main/java/com/sandisk/plm/tracker/service/LdapAuthService.java
git commit -m "feat(user-mgmt): remove DL invite auto-send; add live access-group check"
```

---

## Task 3: Verified send-invites endpoint (+ status nudge, remove old endpoint)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`

- [ ] **Step 1: Enhance `markWelcomeSent`** in `UserPermissionsService` so stamping an invite also flips the request to `completed` and logs it. Replace the existing `markWelcomeSent` method body with:

```java
    public synchronized boolean markWelcomeSent(String sAMAccountName, String sentByUsername, String sentByDisplay) {
        if (sAMAccountName == null) return false;
        String key = normalizeKey(sAMAccountName);
        for (PendingRequest p : state.pendingDLRequests) {
            if (normalizeKey(p.sAMAccountName).equals(key)) {
                p.welcomeSentAt = nowStamp();
                p.welcomeSentBy = sentByUsername;
                p.welcomeSentByDisplay = sentByDisplay;
                if (!"completed".equals(p.status)) {
                    p.status = "completed";
                    if (p.completedAt == null || p.completedAt.isEmpty()) p.completedAt = nowStamp();
                }
                save();
                activityLogger.log(sentByUsername, sentByDisplay, "PERMISSIONS_DL_INVITE",
                    "user=" + sAMAccountName);
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 2: Remove the old single endpoint** in `UserPermissionsController`. Delete the entire `sendWelcomeInvite` method (the `@PostMapping("/dl-request/{sAMAccountName}/welcome")` method, ~lines 409–429).

- [ ] **Step 3: Add the new endpoint** to `UserPermissionsController` (e.g. where the old one was). It uses `inviteReason` (Task 1), `ldapAuthService.isUserInAccessGroup` (Task 2), the existing `dlWelcomeEmailService`, `findPendingByUsername`, `markWelcomeSent`, and `listOf`:

```java
    /**
     * Verified, manual invite send. For each username: live-check DL membership,
     * and only email + mark sent when the user is actually in the access DL.
     * Body: {@code {usernames:[sAMAccountName,...]}}. One failure never aborts
     * the batch. Returns {@code {sent:[{username,displayName}],
     * failed:[{username,displayName,reason}]}} (reason ∈ NOT_FOUND/NOT_IN_DL/EMAIL_FAILED).
     */
    @PostMapping("/dl-requests/send-invites")
    public ResponseEntity<?> sendInvites(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        List<String> usernames = listOf(body.get("usernames"));
        if (usernames.isEmpty()) return ResponseEntity.badRequest().body(err("No requests selected."));

        List<Map<String, Object>> sent = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String u : usernames) {
            PendingRequest req = permissionsService.findPendingByUsername(u);
            boolean found = req != null;
            boolean inDl = found && ldapAuthService.isUserInAccessGroup(u);
            boolean emailedOk = false;
            if (found && inDl) {
                emailedOk = dlWelcomeEmailService.sendWelcome(req);
                if (emailedOk) permissionsService.markWelcomeSent(u, username(session), displayName(session));
            }
            String reason = inviteReason(found, inDl, emailedOk);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", u);
            row.put("displayName", found ? req.displayName : u);
            if (reason == null) {
                sent.add(row);
            } else {
                row.put("reason", reason);
                failed.add(row);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("sent", sent);
        resp.put("failed", failed);
        return ResponseEntity.ok(resp);
    }
```

- [ ] **Step 4: Compile + full suite**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.
Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test`
Expected: only the 3 pre-existing `ChangesControllerTest` errors; everything else (incl. `InviteReasonTest`) passes. Report totals.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java \
        src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java
git commit -m "feat(user-mgmt): verified send-invites endpoint; mark-sent flips status"
```

---

## Task 4: Pending panel markup (header + banner + select-all)

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Replace the pending-panel header + list block.** Find the `permsPendingPanel` div (line ~2490) and replace its inner content (header row + `permsPendingList`) so it has bulk buttons, a banner, and a select-all bar. Replace from `<div id="permsPendingPanel" ...>` through its closing `</div>` with:

```html
    <div id="permsPendingPanel" style="display:none; background:var(--surface,#fff); border:1px solid var(--line,#E8E6DF); border-radius:8px; padding:16px; margin-bottom:20px;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; gap:8px; flex-wrap:wrap;">
        <h3 style="margin:0; font-size:14px; color:#2c3e50; text-transform:uppercase; letter-spacing:.5px;">Pending DL Requests <span id="permsPendingCount" style="font-size:11px; color:#6B7280; font-weight:normal;"></span></h3>
        <div style="display:flex; gap:8px; align-items:center;">
          <button id="permsSendSelectedBtn" onclick="permsSendSelected()" disabled
                  style="font-size:12px; background:var(--accent); color:#fff; border:none; padding:5px 12px; border-radius:6px; cursor:pointer; font-weight:600;">Send <span id="permsSelCount">0</span> selected &rarr;</button>
          <button id="permsSendAllBtn" onclick="permsSendAll()"
                  style="font-size:12px; background:none; border:1px solid var(--accent); color:var(--accent); padding:5px 12px; border-radius:6px; cursor:pointer; font-weight:600;">Send all invites &rarr;</button>
          <button onclick="permsClearCompleted()" style="font-size:12px; background:none; border:1px solid var(--line-2,#E8E6DF); color:#6B7280; padding:5px 10px; border-radius:6px; cursor:pointer;">Clear completed</button>
        </div>
      </div>
      <div id="permsPendingBanner" style="display:none; margin-bottom:10px; padding:9px 12px; border-radius:6px; font-size:12px;"></div>
      <div id="permsPendingSelectAllBar" style="display:none; padding:4px 8px; margin-bottom:6px;">
        <label style="font-size:12px; color:#6B7280; cursor:pointer;"><input type="checkbox" id="permsPendingSelectAll" onclick="permsToggleSelectAllPending(this)" style="margin-right:6px;">Select all pending</label>
      </div>
      <div id="permsPendingList"></div>
    </div>
```

- [ ] **Step 2: Sanity-check**

Run: `grep -c "permsPendingBanner\|permsSendSelectedBtn\|permsPendingSelectAll" src/main/resources/static/index.html` → expect ≥3
Run: `grep -c "id=\"permsPendingPanel\"" src/main/resources/static/index.html` → expect 1

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(user-mgmt): pending-panel header, banner, select-all bar"
```

---

## Task 5: Pending panel JS (states, multi-select, verified send)

**Files:**
- Modify: `src/main/resources/static/user-permissions.js`

Replace `permsRenderPending` (lines ~160–221) and `permsSendInvite` (lines ~223–235) with the block below. `permsClearCompleted` is unchanged. Selection/transient state lives on `permsState`. Reuses `permsEsc`, `appAlert`, `appConfirm`, `permsLoadUsers`, and `permsState.{pending, me, adAddUrl, accessGroupName}`.

- [ ] **Step 1: Replace the two functions**

```javascript
function permsRenderPending() {
    var pending = permsState.pending || [];
    var panel = document.getElementById('permsPendingPanel');
    var list = document.getElementById('permsPendingList');
    var count = document.getElementById('permsPendingCount');
    if (!panel || !list) return;
    if (!pending.length) { panel.style.display = 'none'; return; }
    panel.style.display = '';
    if (count) count.textContent = '(' + pending.length + ')';

    if (!permsState.pendingSel) permsState.pendingSel = {};
    if (!permsState.pendingChecking) permsState.pendingChecking = {};
    if (!permsState.pendingFailed) permsState.pendingFailed = {};

    var adAddUrl = permsState.adAddUrl || '';
    var groupName = permsState.accessGroupName || 'access DL';

    // Awaiting = not yet invited (no welcomeSentAt). Those are the selectable targets.
    var anyAwaiting = pending.some(function (p) { return !p.welcomeSentAt; });
    document.getElementById('permsPendingSelectAllBar').style.display = anyAwaiting ? '' : 'none';

    var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;"><tbody>';
    pending.forEach(function (p) {
        var sam = p.sAMAccountName;
        var invited = !!p.welcomeSentAt;
        var checking = !!permsState.pendingChecking[sam];
        var failed = !!permsState.pendingFailed[sam];
        var selected = !!permsState.pendingSel[sam];

        var pill, actions = '', checkbox = '';
        if (invited) {
            pill = '<span style="background:var(--good-bg,#d4edda); color:var(--good-ink,#155724); padding:2px 8px; border-radius:10px; font-size:11px;">Invite sent</span>';
            actions = '<button onclick="permsSendInvites([\'' + permsEsc(sam) + '\'])" title="Last sent: ' + permsEsc(p.welcomeSentAt || '') + (p.welcomeSentByDisplay ? ' by ' + permsEsc(p.welcomeSentByDisplay) : '') + '"'
                + ' style="background:none; border:1px solid var(--line-2,#1F8A4C); color:#1F8A4C; padding:4px 10px; border-radius:6px; font-size:11px; font-weight:600; cursor:pointer; white-space:nowrap;">Resend invite</button>';
        } else if (checking) {
            pill = '<span style="background:var(--accent-2,#e8f0fe); color:var(--accent-ink,#1a3a5c); padding:2px 8px; border-radius:10px; font-size:11px;">Checking DL…</span>';
        } else if (failed) {
            pill = '<span style="background:#fdeaea; color:#B8342B; padding:2px 8px; border-radius:10px; font-size:11px;">Not in DL yet</span>';
            checkbox = '<input type="checkbox" ' + (selected ? 'checked ' : '') + 'onclick="permsTogglePendingSel(\'' + permsEsc(sam) + '\')" style="margin-right:6px;">';
            actions = permsAwaitingActions(sam, adAddUrl, groupName);
        } else {
            pill = '<span style="background:var(--warn-bg,#fff3cd); color:#856404; padding:2px 8px; border-radius:10px; font-size:11px;">Awaiting DL add</span>';
            checkbox = '<input type="checkbox" ' + (selected ? 'checked ' : '') + 'onclick="permsTogglePendingSel(\'' + permsEsc(sam) + '\')" style="margin-right:6px;">';
            actions = permsAwaitingActions(sam, adAddUrl, groupName);
        }

        var tabsLabel = (p.requestedTabs || []).map(permsEsc).join(', ') || '(none)';
        html += '<tr style="border-bottom:1px solid var(--line,#E8E6DF);">'
            + '<td style="padding:8px; white-space:nowrap;">' + checkbox + '<strong>' + permsEsc(p.displayName || sam) + '</strong> '
                + '<span style="color:#6B7280; font-size:11px;">' + permsEsc(sam) + '</span></td>'
            + '<td style="padding:8px;">' + pill + '</td>'
            + '<td style="padding:8px; color:#6B7280; font-size:11px;">' + permsEsc(p.requestedAt || '') + '<br>by ' + permsEsc(p.requestedByDisplay || '') + '</td>'
            + '<td style="padding:8px; color:#6B7280; font-size:11px;">tabs: ' + tabsLabel + '</td>'
            + '<td style="padding:8px; text-align:right; white-space:nowrap;">' + actions + '</td>'
            + '</tr>';
    });
    html += '</tbody></table>';
    list.innerHTML = html;
    permsUpdateSelCount();
}

function permsAwaitingActions(sam, adAddUrl, groupName) {
    var addLink = adAddUrl
        ? '<a href="' + permsEsc(adAddUrl) + '" target="_blank" rel="noopener" title="Open AD self-service to add ' + permsEsc(sam) + ' to ' + permsEsc(groupName) + '" style="font-size:11px; color:var(--accent); margin-right:10px;">Add to DL ↗</a>'
        : '';
    var sendBtn = '<button onclick="permsSendInvites([\'' + permsEsc(sam) + '\'])" style="background:var(--accent); color:#fff; border:none; padding:4px 10px; border-radius:6px; font-size:11px; font-weight:600; cursor:pointer; white-space:nowrap;">Send invite</button>';
    return addLink + sendBtn;
}

function permsTogglePendingSel(sam) {
    if (!permsState.pendingSel) permsState.pendingSel = {};
    if (permsState.pendingSel[sam]) delete permsState.pendingSel[sam];
    else permsState.pendingSel[sam] = true;
    permsUpdateSelCount();
}

function permsAwaitingUsernames() {
    return (permsState.pending || []).filter(function (p) { return !p.welcomeSentAt; })
        .map(function (p) { return p.sAMAccountName; });
}

function permsToggleSelectAllPending(cb) {
    if (!permsState.pendingSel) permsState.pendingSel = {};
    var targets = permsAwaitingUsernames();
    if (cb.checked) targets.forEach(function (u) { permsState.pendingSel[u] = true; });
    else permsState.pendingSel = {};
    permsRenderPending();
}

function permsUpdateSelCount() {
    var n = Object.keys(permsState.pendingSel || {}).length;
    var el = document.getElementById('permsSelCount');
    if (el) el.textContent = n;
    var btn = document.getElementById('permsSendSelectedBtn');
    if (btn) btn.disabled = n === 0;
    var allBtn = document.getElementById('permsSendAllBtn');
    if (allBtn) allBtn.disabled = permsAwaitingUsernames().length === 0;
}

function permsSendSelected() {
    var us = Object.keys(permsState.pendingSel || {});
    if (us.length) permsSendInvites(us);
}

function permsSendAll() {
    var us = permsAwaitingUsernames();
    if (us.length) permsSendInvites(us);
}

function permsShowPendingBanner(kind, html) {
    var b = document.getElementById('permsPendingBanner');
    if (!b) return;
    var bg = kind === 'success' ? 'var(--good-bg,#e8f5e9)' : kind === 'warn' ? 'var(--warn-bg,#fff8e1)' : '#fdeaea';
    var fg = kind === 'success' ? 'var(--good-ink,#1F8A4C)' : kind === 'warn' ? '#856404' : '#B8342B';
    b.style.display = '';
    b.style.background = bg;
    b.style.color = fg;
    b.innerHTML = html;
}

function permsSendInvites(usernames) {
    if (!usernames || !usernames.length) return;
    if (!permsState.pendingChecking) permsState.pendingChecking = {};
    if (!permsState.pendingFailed) permsState.pendingFailed = {};
    usernames.forEach(function (u) { permsState.pendingChecking[u] = true; delete permsState.pendingFailed[u]; });
    permsRenderPending();
    fetch('/api/permissions/dl-requests/send-invites', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usernames: usernames })
    }).then(function (r) { return r.json(); })
      .then(function (resp) {
          usernames.forEach(function (u) { delete permsState.pendingChecking[u]; });
          if (!resp.success) { permsShowPendingBanner('error', 'Send failed: ' + permsEsc(resp.error || 'unknown')); permsRenderPending(); return; }
          var sent = resp.sent || [], failed = resp.failed || [];
          var notInDl = failed.filter(function (f) { return f.reason === 'NOT_IN_DL'; });
          var otherFail = failed.filter(function (f) { return f.reason !== 'NOT_IN_DL'; });
          // Persist NOT_IN_DL as a red retryable state; clear selection for handled rows.
          failed.forEach(function (f) { if (f.reason === 'NOT_IN_DL') permsState.pendingFailed[f.username] = true; });
          sent.forEach(function (s) { delete permsState.pendingFailed[s.username]; delete permsState.pendingSel[s.username]; });
          notInDl.forEach(function (f) { delete permsState.pendingSel[f.username]; });

          var names = function (arr) { return arr.map(function (x) { return permsEsc(x.displayName || x.username); }).join(', '); };
          if (failed.length === 0) {
              permsShowPendingBanner('success', '✓ ' + sent.length + ' invite' + (sent.length === 1 ? '' : 's') + ' sent.');
          } else if (sent.length === 0 && otherFail.length === 0) {
              permsShowPendingBanner('warn', '⚠ ' + names(notInDl) + ' not in the ' + permsEsc(permsState.accessGroupName || 'access') + ' DL yet. Add them, then try Send again. No email was sent.');
          } else {
              var parts = [];
              if (sent.length) parts.push('✓ ' + sent.length + ' sent');
              if (notInDl.length) parts.push('⚠ not in DL yet: ' + names(notInDl) + ' — add and retry, no email sent');
              if (otherFail.length) parts.push('✕ failed: ' + names(otherFail));
              permsShowPendingBanner('warn', parts.join(' · '));
          }
          permsLoadUsers();   // refresh server truth (sent rows now show "Invite sent")
      })
      .catch(function (err) {
          usernames.forEach(function (u) { delete permsState.pendingChecking[u]; });
          permsShowPendingBanner('error', 'Send failed: ' + permsEsc(String(err)));
          permsRenderPending();
      });
}
```

- [ ] **Step 2: Sanity-check**

Run: `node --check src/main/resources/static/user-permissions.js` → exit 0
Run: `grep -c "function permsSendInvites\|function permsSendAll\|function permsToggleSelectAllPending" src/main/resources/static/user-permissions.js` → expect 3
Run: `grep -c "dl-request/.*welcome" src/main/resources/static/user-permissions.js` → expect 0 (old single endpoint call gone)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/user-permissions.js
git commit -m "feat(user-mgmt): pending panel verified-invite states + bulk send"
```

---

## Task 6: Changelog, build, local smoke test

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a What's New entry** at the TOP of `WHATS_NEW_RELEASES` (match `{date, title, items:[{badge,text}]}`; date `'June 24, 2026'`):

```javascript
  {
    date: 'June 24, 2026',
    title: 'User Management &middot; DL invites are now manual &amp; verified',
    items: [
      { badge: 'fix', text: '<strong>Invite emails no longer fire on restart.</strong> They used to send automatically when the Permissions tab loaded after a deploy &mdash; that startup side-effect is removed.' },
      { badge: 'new', text: '<strong>Send invites on demand, with a live DL check.</strong> Each pending request has a <em>Send invite</em> action (plus bulk <em>Send all / Send selected</em>). The invite only goes out if the person is actually in the IT-APP-Agile-admin DL right now.' },
      { badge: 'improve', text: '<strong>Clear states &amp; results.</strong> Rows show Awaiting / Checking DL&hellip; / Invite sent / Not in DL yet, with an &ldquo;Add to DL ↗&rdquo; link and a summary banner naming anyone who still needs to be added.' }
    ]
  },
```

- [ ] **Step 2: Validate + tests**

Run: `node --check src/main/resources/static/whats-new.js` → exit 0
Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=InviteReasonTest` → PASS.

- [ ] **Step 3: Build the JAR**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests package`
Expected: `target/plm-field-tracker-1.0.1.jar`.

- [ ] **Step 4: Hand off for local restart + smoke test**

Do NOT restart the local server from a subagent. Report DONE and let the main session restart `:8090` and smoke-test:
- `/api/permissions/users` load sends no `[DL-WELCOME]` email (auto-send gone).
- `POST /dl-requests/send-invites {usernames:[<a not-in-DL user>]}` → `failed: NOT_IN_DL`, no email.
- The actual in-DL → real-email path is verified on the server by Vikas.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): manual verified DL invite send"
```

---

## Self-Review

**Spec coverage:**
- Remove auto-send → Task 2 (delete the `autoCompleted` loop). ✅
- Live uncached DL check `isUserInAccessGroup` → Task 2. ✅
- `POST /dl-requests/send-invites`, per-user verify→email→mark, reasons NOT_FOUND/NOT_IN_DL/EMAIL_FAILED, batch isolation → Task 1 (`inviteReason`) + Task 3. ✅
- Mark-sent flips status to completed → Task 3 (`markWelcomeSent` enhancement). ✅
- Remove old `/welcome` endpoint → Task 3. ✅
- Panel states (awaiting/checking/completed/failed), per-row send/resend, Add-to-DL link, select-all + bulk send, summary banner, v2 tokens → Tasks 4 & 5. ✅
- Fail-closed on AD error → Task 2 (`isUserInAccessGroup` returns false on error) → renders NOT_IN_DL. ✅
- Testing: pure `inviteReason` unit + local smoke (NOT_IN_DL path) → Task 1 + Task 6. ✅

**Placeholder scan:** none — every step has full code.

**Type consistency:** endpoint `/api/permissions/dl-requests/send-invites`, body `{usernames:[]}`, response `{sent:[{username,displayName}], failed:[{username,displayName,reason}]}`; `inviteReason(found,inDl,emailedOk)`; `isUserInAccessGroup(username)`; JS `permsState.{pendingSel,pendingChecking,pendingFailed}`, `permsSendInvites(usernames[])` — consistent across Tasks 1/3/5. Frontend posts exactly the Task-3 shape and reads `sent`/`failed`/`reason`.
