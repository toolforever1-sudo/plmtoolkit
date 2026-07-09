# Add-from-AD Multi-Select + Presets — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the "+ Add user from AD" modal so an admin can queue multiple AD users and grant them a shared, preset-based set of tabs in one submit, sending a single consolidated IT request for only the users who still need DL access.

**Architecture:** Backend gains a tab groups/presets catalog (data + getters, exposed via a new `/tab-catalog` endpoint) and a DL-partition refinement to the existing `submitBulkDLRequest` (tabs saved for all; pending+email only for users not already in the DL). The frontend modal is rebuilt for multi-select with preset pills and grouped per-tab toggles, styled with the global v2 `:root` tokens. Reuses `/ad-search` and `submitBulkDLRequest`.

**Tech Stack:** Java 11 / Spring Boot, JUnit 5 (spring-boot-starter-test), vanilla JS frontend, v2 design tokens (`tokens.css`).

**Spec:** `docs/superpowers/specs/2026-06-24-add-user-from-ad-multiselect-design.md`

**Build command (Java 11 — required):**
`JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn ...`

---

## File Structure

**Modify:**
- `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java` — add `TAB_GROUPS` / `GROUP_ORDER` / `Preset` / `getPresets()` / `getTabGroups()` / `getGroupOrder()`; add static `partitionByDl` + `DlPartition`; add `emailedToIt` to `BulkOutcome`; refine `submitBulkDLRequest`.
- `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java` — add `GET /api/permissions/tab-catalog` and `POST /api/permissions/request-add-bulk`.
- `src/main/resources/static/index.html` — replace the `permsAddModal` markup.
- `src/main/resources/static/user-permissions.js` — replace the Add-modal JS section.
- `src/main/resources/static/whats-new.js` — changelog entry.

**Create:**
- `src/test/java/com/sandisk/plm/tracker/service/TabCatalogTest.java`
- `src/test/java/com/sandisk/plm/tracker/service/DlPartitionTest.java`

---

## Task 1: Tab groups + presets catalog (backend data)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/TabCatalogTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.Preset;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TabCatalogTest {

    private final UserPermissionsService svc = new UserPermissionsService();

    private Preset preset(String id) {
        for (Preset p : svc.getPresets()) if (p.id.equals(id)) return p;
        throw new AssertionError("no preset " + id);
    }

    @Test
    void everyGrantableTabIsInExactlyOneGroup() {
        Set<String> grantable = svc.grantableTabKeys();
        Map<String, String> groups = svc.getTabGroups();
        for (String k : grantable) {
            assertTrue(groups.containsKey(k), "grantable tab not grouped: " + k);
        }
    }

    @Test
    void noGroupReferencesANonGrantableTab() {
        Set<String> grantable = svc.grantableTabKeys();
        for (String k : svc.getTabGroups().keySet()) {
            assertTrue(grantable.contains(k), "grouped tab is not grantable: " + k);
        }
    }

    @Test
    void everyGroupNameIsInGroupOrder() {
        List<String> order = svc.getGroupOrder();
        for (String g : svc.getTabGroups().values()) {
            assertTrue(order.contains(g), "group not in GROUP_ORDER: " + g);
        }
    }

    @Test
    void presetsReferenceOnlyGrantableKeys() {
        Set<String> grantable = svc.grantableTabKeys();
        for (Preset p : svc.getPresets()) {
            for (String k : p.tabKeys) {
                assertTrue(grantable.contains(k), "preset " + p.id + " references non-grantable: " + k);
            }
        }
    }

    @Test
    void fullAccessEqualsAllGrantable() {
        assertEquals(new HashSet<>(svc.grantableTabKeys()), new HashSet<>(preset("full").tabKeys));
    }

    @Test
    void presetMembershipsAreExact() {
        assertTrue(preset("blank").tabKeys.isEmpty());
        assertEquals(Arrays.asList("agile", "sku", "history", "ecotimeline", "ecnreport", "ims-review"),
                preset("viewer").tabKeys);
        assertEquals(Arrays.asList("fields", "parts", "agile", "sku", "bom", "bomcompare"),
                preset("items").tabKeys);
        assertEquals(Arrays.asList("history", "ecotimeline", "ecnreport", "singlesole", "docreview"),
                preset("reporting").tabKeys);
    }

    @Test
    void presetOrderIsBlankViewerItemsReportingFull() {
        List<String> ids = new ArrayList<>();
        for (Preset p : svc.getPresets()) ids.add(p.id);
        assertEquals(Arrays.asList("blank", "viewer", "items", "reporting", "full"), ids);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=TabCatalogTest`
Expected: FAIL — `Preset`, `getPresets`, `getTabGroups`, `getGroupOrder` not defined.

- [ ] **Step 3: Add the catalog data + getters**

Insert into `UserPermissionsService` right after the `TAB_CATALOG` declaration (after line ~100, the closing `));`):

```java
    /** Display groups for the Add-from-AD picker, in render order. */
    public static final List<String> GROUP_ORDER = Collections.unmodifiableList(Arrays.asList(
        "Items", "BOM", "Change & ECO", "Reports & Docs", "More / Tools"));

    /** Tab key -> group name. A unit test enforces every grantable tab is mapped,
     *  so a newly added tab that's left out here fails the build. */
    private static final LinkedHashMap<String, String> TAB_GROUPS = buildTabGroups();

    private static LinkedHashMap<String, String> buildTabGroups() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String k : new String[]{"fields", "parts", "agile", "sku"}) m.put(k, "Items");
        for (String k : new String[]{"bom", "bomcompare"}) m.put(k, "BOM");
        for (String k : new String[]{"history", "ecotimeline", "ecnreport", "changereviews", "docreview", "ims-review"}) m.put(k, "Change & ECO");
        for (String k : new String[]{"singlesole", "sdsm"}) m.put(k, "Reports & Docs");
        for (String k : new String[]{"helpcenter", "compare", "aieval", "it-enhancements"}) m.put(k, "More / Tools");
        return m;
    }

    /** One-click tab bundle for the Add-from-AD picker. */
    public static class Preset {
        public final String id;
        public final String label;
        public final List<String> tabKeys;
        public Preset(String id, String label, List<String> tabKeys) {
            this.id = id;
            this.label = label;
            this.tabKeys = tabKeys;
        }
    }

    public Map<String, String> getTabGroups() {
        return Collections.unmodifiableMap(TAB_GROUPS);
    }

    public List<String> getGroupOrder() {
        return GROUP_ORDER;
    }

    /** Presets in render order. "Full access" resolves to every grantable tab. */
    public List<Preset> getPresets() {
        List<Preset> p = new ArrayList<>();
        p.add(new Preset("blank", "Blank", new ArrayList<>()));
        p.add(new Preset("viewer", "Viewer",
            Arrays.asList("agile", "sku", "history", "ecotimeline", "ecnreport", "ims-review")));
        p.add(new Preset("items", "Items team",
            Arrays.asList("fields", "parts", "agile", "sku", "bom", "bomcompare")));
        p.add(new Preset("reporting", "Reporting",
            Arrays.asList("history", "ecotimeline", "ecnreport", "singlesole", "docreview")));
        p.add(new Preset("full", "Full access", new ArrayList<>(grantableTabKeys())));
        return p;
    }
```

(`grantableTabKeys()` returns a `LinkedHashSet` in catalog order, so `new ArrayList<>(...)` preserves order.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=TabCatalogTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java \
        src/test/java/com/sandisk/plm/tracker/service/TabCatalogTest.java
git commit -m "feat(user-mgmt): tab groups + presets catalog for Add-from-AD"
```

---

## Task 2: DL partition + submitBulkDLRequest refinement

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/DlPartitionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.DlPartition;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DlPartitionTest {

    private Map<String, String> u(String sam) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sAMAccountName", sam);
        m.put("displayName", sam + " Name");
        m.put("email", sam + "@sandisk.com");
        return m;
    }

    @Test
    void splitsInDlFromNeedsDlCaseInsensitive() {
        List<Map<String, String>> users = Arrays.asList(u("alice"), u("BOB"), u("carol"));
        Set<String> dl = new HashSet<>(Arrays.asList("bob"));   // stored lowercase
        DlPartition p = UserPermissionsService.partitionByDl(users, dl);
        assertEquals(1, p.alreadyInDl.size());
        assertEquals("BOB", p.alreadyInDl.get(0).get("sAMAccountName"));
        assertEquals(2, p.needsDl.size());
    }

    @Test
    void emptyDlSetMeansEveryoneNeedsDl() {
        List<Map<String, String>> users = Arrays.asList(u("alice"), u("bob"));
        DlPartition p = UserPermissionsService.partitionByDl(users, Collections.emptySet());
        assertEquals(2, p.needsDl.size());
        assertTrue(p.alreadyInDl.isEmpty());
    }

    @Test
    void nullDlSetMeansEveryoneNeedsDl() {
        List<Map<String, String>> users = Collections.singletonList(u("alice"));
        DlPartition p = UserPermissionsService.partitionByDl(users, null);
        assertEquals(1, p.needsDl.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=DlPartitionTest`
Expected: FAIL — `DlPartition` / `partitionByDl` not defined.

- [ ] **Step 3: Add `DlPartition` + `partitionByDl`, add `emailedToIt` to `BulkOutcome`, refine `submitBulkDLRequest`**

3a. Add the `emailedToIt` field to the existing `BulkOutcome` class:

```java
    public static class BulkOutcome {
        public String sAMAccountName;
        public String displayName;
        public boolean ok;
        public boolean emailedToIt;   // true = a DL-add request went to IT for this user
        public String error;   // null when ok
    }
```

3b. Add the partition helper + class (place just above `submitBulkDLRequest`):

```java
    /** Result of splitting a submit batch by current access-DL membership. */
    public static class DlPartition {
        public List<Map<String, String>> needsDl = new ArrayList<>();
        public List<Map<String, String>> alreadyInDl = new ArrayList<>();
    }

    /**
     * Split users into those already in the access DL (tabs only — IT has nothing
     * to do) and those who still need a DL add (tabs + pending request + IT email).
     * {@code dlUsernamesLower} is a lowercase set; a null/empty set fail-safes to
     * "everyone needs DL" so we never silently skip emailing IT.
     */
    static DlPartition partitionByDl(List<Map<String, String>> users, Set<String> dlUsernamesLower) {
        DlPartition out = new DlPartition();
        if (users == null) return out;
        for (Map<String, String> u : users) {
            String sam = u == null ? "" : u.getOrDefault("sAMAccountName", "");
            String key = normalizeKey(sam == null ? "" : sam);
            if (dlUsernamesLower != null && dlUsernamesLower.contains(key)) out.alreadyInDl.add(u);
            else out.needsDl.add(u);
        }
        return out;
    }
```

3c. Replace the body of `submitBulkDLRequest` with the DL-aware version:

```java
    public synchronized List<BulkOutcome> submitBulkDLRequest(List<Map<String, String>> users,
                                                              List<String> allowedTabs,
                                                              String actorUsername, String actorDisplayName) {
        // Authoritative DL membership (cached ~1h). Fail-safe: empty set => everyone needs DL.
        Set<String> dlLower = new LinkedHashSet<>();
        try {
            for (com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser m : ldapAuthService.listAccessGroupCandidates()) {
                if (m != null && m.username != null) dlLower.add(m.username.trim().toLowerCase());
            }
        } catch (Exception e) {
            logger.warning("[PERMS] DL membership lookup failed; treating all as needs-DL: " + e.getMessage());
        }
        DlPartition part = partitionByDl(users == null ? new ArrayList<>() : users, dlLower);

        List<BulkOutcome> outcomes = new ArrayList<>();
        List<PendingRequest> created = new ArrayList<>();

        // Already in DL: save tabs only, no pending request, no IT email.
        for (Map<String, String> u : part.alreadyInDl) {
            outcomes.add(applyOne(u, allowedTabs, actorUsername, actorDisplayName, false, null));
        }
        // Needs DL: save tabs + create pending request (collected for one consolidated email).
        for (Map<String, String> u : part.needsDl) {
            outcomes.add(applyOne(u, allowedTabs, actorUsername, actorDisplayName, true, created));
        }
        save();

        if (!created.isEmpty()) {
            try {
                sendBulkDLRequestEmail(created);
            } catch (Exception e) {
                logger.warning("[PERMS] bulk DL request email failed: " + e.getMessage());
            }
        }

        int ok = 0, emailed = 0;
        for (BulkOutcome o : outcomes) { if (o.ok) ok++; if (o.emailedToIt) emailed++; }
        int total = part.alreadyInDl.size() + part.needsDl.size();
        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_BULK_IMPORT",
            "submitted=" + total + " ok=" + ok + " emailed=" + emailed + " failed=" + (total - ok)
            + " tabs=" + String.join(",", allowedTabs == null ? new ArrayList<>() : allowedTabs));
        return outcomes;
    }

    /** Upsert one user's tabs; when {@code needsDl}, also create+collect a pending request. */
    private BulkOutcome applyOne(Map<String, String> u, List<String> allowedTabs,
                                 String actorUsername, String actorDisplayName,
                                 boolean needsDl, List<PendingRequest> collect) {
        String sam = u.get("sAMAccountName");
        String name = u.get("displayName");
        String email = u.get("email");
        BulkOutcome oc = new BulkOutcome();
        oc.sAMAccountName = sam;
        oc.displayName = name;
        try {
            UserRecord saved = upsertUser(sam, name, email, allowedTabs, actorUsername, actorDisplayName);
            if (needsDl) {
                String key = normalizeKey(sam);
                state.pendingDLRequests.removeIf(p -> normalizeKey(p.sAMAccountName).equals(key));
                PendingRequest req = new PendingRequest();
                req.sAMAccountName = sam;
                req.displayName = name;
                req.email = email;
                req.requestedTabs = saved.allowedTabs == null ? new ArrayList<>() : new ArrayList<>(saved.allowedTabs);
                req.requestedBy = actorUsername;
                req.requestedByDisplay = actorDisplayName;
                req.requestedAt = nowStamp();
                req.status = "pending";
                state.pendingDLRequests.add(req);
                if (collect != null) collect.add(req);
                oc.emailedToIt = true;
            }
            oc.ok = true;
        } catch (Exception e) {
            oc.ok = false;
            oc.error = e.getMessage();
        }
        return oc;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=DlPartitionTest,TabCatalogTest`
Expected: PASS. Then compile-check the whole module:
Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java \
        src/test/java/com/sandisk/plm/tracker/service/DlPartitionTest.java
git commit -m "feat(user-mgmt): DL-aware bulk submit (tabs for all, email only needs-DL)"
```

---

## Task 3: Endpoints — /tab-catalog + /request-add-bulk

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`

No new unit test (HTTP wiring; verified by the local smoke test in Task 6). The controller already has `isPermsAdmin`, `forbidden`, `err`, `username`, `displayName`, `strOf`, `listOf` helpers.

- [ ] **Step 1: Add the two endpoints**

Insert into `UserPermissionsController` (e.g. just after the existing `tabs(...)` method). Add the imports `com.sandisk.plm.tracker.service.UserPermissionsService.Preset` and `com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome` at the top if not already present (or use fully-qualified names as below).

```java
    /**
     * Richer catalog for the Add-from-AD picker: grantable tabs tagged with their
     * display group, the group render order, and the one-click presets. Separate
     * from {@code /tabs} (whose flat-array shape other code depends on).
     */
    @GetMapping("/tab-catalog")
    public ResponseEntity<?> tabCatalog(HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        Set<String> grantable = permissionsService.grantableTabKeys();
        Map<String, String> groups = permissionsService.getTabGroups();

        List<Map<String, Object>> tabs = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.UserPermissionsService.TabDef t : permissionsService.getTabCatalog()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", t.key);
            row.put("label", t.label);
            row.put("group", groups.get(t.key));     // null for non-grantable tabs
            row.put("grantable", grantable.contains(t.key));
            tabs.add(row);
        }

        List<Map<String, Object>> presets = new ArrayList<>();
        for (com.sandisk.plm.tracker.service.UserPermissionsService.Preset p : permissionsService.getPresets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("label", p.label);
            row.put("tabKeys", p.tabKeys);
            presets.add(row);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tabs", tabs);
        resp.put("groups", permissionsService.getGroupOrder());
        resp.put("presets", presets);
        return ResponseEntity.ok(resp);
    }

    /**
     * Multi-user variant of {@code /request-add}: persist the same tab set for every
     * selected user; one consolidated IT email for the subset not already in the DL.
     * Body: {@code {users:[{sAMAccountName,displayName,email}], allowedTabs:[]}}.
     */
    @PostMapping("/request-add-bulk")
    public ResponseEntity<?> requestAddBulk(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> users = usersOf(body.get("users"));
            List<String> tabs = listOf(body.get("allowedTabs"));
            if (users.isEmpty()) return ResponseEntity.badRequest().body(err("No users to submit."));
            List<com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome> outcomes =
                permissionsService.submitBulkDLRequest(users, tabs, username(session), displayName(session));
            int ok = 0, emailed = 0;
            for (com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome o : outcomes) {
                if (o.ok) ok++;
                if (o.emailedToIt) emailed++;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("submitted", users.size());
            resp.put("ok", ok);
            resp.put("failed", users.size() - ok);
            resp.put("emailed", emailed);
            resp.put("outcomes", outcomes);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> usersOf(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("sAMAccountName", strOf(m.get("sAMAccountName")));
                    row.put("displayName", strOf(m.get("displayName")));
                    row.put("email", strOf(m.get("email")));
                    out.add(row);
                }
            }
        }
        return out;
    }
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java
git commit -m "feat(user-mgmt): /tab-catalog + /request-add-bulk endpoints"
```

---

## Task 4: Modal markup rebuild

**Files:**
- Modify: `src/main/resources/static/index.html`

Replace the entire `permsAddModal` block with the multi-select layout. Styling uses global v2 tokens (`var(--accent)`, `var(--line)`, `var(--surface-2)`, `var(--good-bg)`, `var(--good-ink)`, `var(--warn-bg)`, `var(--font-serif)`, `var(--font-mono)`); no new colors.

- [ ] **Step 1: Replace the modal**

Find the block that starts with `<!-- Add User from AD Modal -->` and the `<div id="permsAddModal"` element, through its matching closing `</div>` (the modal currently ends just before the `<!-- Removed 2026-05-14 ... -->` comment). Replace the whole block with:

```html
<!-- Add User from AD Modal (multi-select + preset tab grants) -->
<div id="permsAddModal" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.5); z-index:10000; align-items:flex-start; justify-content:center; padding-top:48px;">
  <div style="background:var(--surface); max-width:760px; width:94%; border-radius:10px; box-shadow:0 10px 40px rgba(0,0,0,0.2); padding:24px; max-height:88vh; overflow-y:auto;">
    <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:14px;">
      <div>
        <div style="font-family:var(--font-serif); font-size:20px; font-weight:600;">Add users from AD</div>
        <div style="font-size:12px; color:var(--muted, #6B7280); margin-top:2px;">Search and click to queue people &mdash; pick a tab preset &mdash; submit one request to IT.</div>
      </div>
      <button onclick="permsCloseAdd()" style="background:none; border:none; font-size:22px; color:#6B7280; cursor:pointer; line-height:1;">&times;</button>
    </div>

    <!-- Search -->
    <div style="position:relative;">
      <input id="permsAddSearch" type="text" placeholder="Search AD by name, username, or email (min 3 chars)" oninput="permsTypeahead(this.value)" autocomplete="off"
             style="width:100%; padding:9px 12px; border:1px solid var(--line-2); border-radius:8px; font-size:13px;">
      <div id="permsAddResults" style="max-height:200px; overflow-y:auto; border:1px solid var(--line); border-radius:8px; margin-top:6px; display:none; box-shadow:0 6px 20px rgba(0,0,0,0.08);"></div>
    </div>

    <!-- Selected users -->
    <div style="margin-top:16px;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
        <span style="font-family:var(--font-mono); font-size:11px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280;">Selected users (<span id="permsAddCount">0</span>)</span>
        <a id="permsAddClearAll" href="javascript:void(0)" onclick="permsAddClearUsers()" style="font-size:12px; color:var(--accent); display:none;">Clear all</a>
      </div>
      <div id="permsAddSelectedList" style="border:1px solid var(--line); border-radius:8px; min-height:56px; max-height:200px; overflow-y:auto;"></div>
    </div>

    <!-- Tabs to grant -->
    <div style="margin-top:18px;">
      <div style="display:flex; justify-content:space-between; align-items:baseline; margin-bottom:4px;">
        <span style="font-family:var(--font-mono); font-size:11px; letter-spacing:0.06em; text-transform:uppercase; color:#6B7280;">Tabs to grant on first login</span>
        <span style="font-size:12px; color:#6B7280;"><span id="permsAddTabCount">0</span> of <span id="permsAddTabTotal">0</span> selected</span>
      </div>
      <div style="font-size:12px; color:#6B7280; margin-bottom:8px;">Applies to all selected users. Start from a preset, then fine-tune.</div>
      <div id="permsAddPresets" style="display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;"></div>
      <div id="permsAddTabGroups" style="border:1px solid var(--line); border-radius:8px; padding:6px 10px; max-height:260px; overflow-y:auto;"></div>
    </div>

    <!-- Footer -->
    <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; margin-top:18px;">
      <div id="permsAddSummary" style="font-size:12px; color:#6B7280;"></div>
      <div style="display:flex; gap:8px; flex-shrink:0;">
        <button onclick="permsCloseAdd()" style="background:none; border:1px solid var(--line-2); color:#6B7280; padding:8px 14px; border-radius:8px; font-size:13px; cursor:pointer;">Cancel</button>
        <button onclick="permsAddSubmit()" id="permsAddSubmitBtn" disabled
                style="background:var(--accent); color:#fff; border:none; padding:8px 16px; border-radius:8px; font-size:13px; font-weight:600; cursor:pointer;">Submit request to IT (<span id="permsAddSubmitCount">0</span>)</button>
      </div>
    </div>
  </div>
</div>
```

- [ ] **Step 2: Sanity-check** the block has balanced tags and the old single-select ids (`permsAddSelected`, `permsAddTabList`, `permsAddSelectedDetails`) are gone:

Run: `grep -c "permsAddSelectedList\|permsAddPresets\|permsAddTabGroups" src/main/resources/static/index.html` → expect ≥3
Run: `grep -c "permsAddTabList\|permsAddSelectedDetails" src/main/resources/static/index.html` → expect 0

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(user-mgmt): multi-select Add-from-AD modal markup"
```

---

## Task 5: Modal JS logic

**Files:**
- Modify: `src/main/resources/static/user-permissions.js`

Replace the Add-modal JS section (the functions `permsOpenAddModal`, `permsCloseAdd`, `permsTypeahead`, `permsPickUser`, `permsRenderAddTabList`, `permsSubmitDLRequest` — the block under the `// Add user from AD modal` banner) with the new multi-select implementation below. The edit-modal code and `permsState.tabs` are untouched. Reuses `permsEsc`, `appAlert`, `appConfirm`, `permsLoadUsers`, `permsState.typeaheadTimer`.

- [ ] **Step 1: Replace the section**

```javascript
// ---------------------------------------------------------------------------
// Add users from AD modal (multi-select + preset tab grants)
// ---------------------------------------------------------------------------

function permsOpenAddModal() {
    permsState.add = { selectedUsers: [], granted: {}, activePreset: 'viewer', catalog: null, results: [] };
    document.getElementById('permsAddSearch').value = '';
    document.getElementById('permsAddResults').style.display = 'none';
    document.getElementById('permsAddResults').innerHTML = '';
    document.getElementById('permsAddModal').style.display = 'flex';
    permsRenderSelectedUsers();
    // Load the rich catalog (tabs+groups+presets), then apply the default preset.
    fetch('/api/permissions/tab-catalog').then(function (r) { return r.json(); }).then(function (cat) {
        permsState.add.catalog = cat;
        document.getElementById('permsAddTabTotal').textContent = (cat.tabs || []).filter(function (t) { return t.grantable; }).length;
        permsApplyPreset('viewer');
        permsRenderPresets();
    }).catch(function (e) {
        document.getElementById('permsAddTabGroups').innerHTML = '<div style="padding:12px; color:#B8342B; font-size:12px;">Failed to load tab catalog: ' + permsEsc(String(e)) + '</div>';
    });
    setTimeout(function () { document.getElementById('permsAddSearch').focus(); }, 50);
}

function permsCloseAdd() {
    document.getElementById('permsAddModal').style.display = 'none';
    permsState.add = null;
}

function permsTypeahead(query) {
    if (permsState.typeaheadTimer) clearTimeout(permsState.typeaheadTimer);
    var resultsEl = document.getElementById('permsAddResults');
    if (!query || query.trim().length < 3) { resultsEl.style.display = 'none'; resultsEl.innerHTML = ''; return; }
    resultsEl.style.display = 'block';
    resultsEl.innerHTML = '<div style="padding:12px; text-align:center; color:#6B7280; font-size:12px;">Searching AD&hellip;</div>';
    permsState.typeaheadTimer = setTimeout(function () {
        fetch('/api/permissions/ad-search?q=' + encodeURIComponent(query.trim()))
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                permsState.add.results = resp.results || [];
                permsRenderResults();
            })
            .catch(function (e) {
                resultsEl.innerHTML = '<div style="padding:12px; color:#B8342B; font-size:12px;">Search failed: ' + permsEsc(String(e)) + '</div>';
            });
    }, 300);
}

function permsRenderResults() {
    var resultsEl = document.getElementById('permsAddResults');
    var rows = permsState.add.results || [];
    var selected = {};
    permsState.add.selectedUsers.forEach(function (u) { selected[u.sAMAccountName] = true; });
    if (!rows.length) { resultsEl.innerHTML = '<div style="padding:12px; text-align:center; color:#6B7280; font-size:12px;">No matches.</div>'; return; }
    var html = '';
    rows.forEach(function (r, i) {
        var added = selected[r.sAMAccountName];
        var dl = r.alreadyInDL
            ? '<span style="background:var(--good-bg); color:var(--good-ink); padding:1px 7px; border-radius:8px; font-size:10px; margin-left:6px;">In access DL</span>'
            : '';
        html += '<div ' + (added ? '' : 'onclick="permsAddPickUser(' + i + ')" ') + 'style="padding:8px 12px; border-bottom:1px solid var(--surface-2); ' + (added ? 'opacity:0.5;' : 'cursor:pointer;') + '" '
            + (added ? '' : 'onmouseover="this.style.background=\'var(--surface-2)\'" onmouseout="this.style.background=\'transparent\'"') + '>'
            + '<div style="font-size:13px; color:#0F1720; font-weight:600;">' + permsEsc(r.displayName || r.sAMAccountName) + dl + (added ? '<span style="font-size:10px; color:#6B7280; margin-left:6px;">Added</span>' : '') + '</div>'
            + '<div style="font-size:11px; color:#6B7280; font-family:var(--font-mono);">' + permsEsc(r.sAMAccountName) + (r.email ? ' &bull; ' + permsEsc(r.email) : '') + '</div>'
            + '</div>';
    });
    resultsEl.innerHTML = html;
}

function permsAddPickUser(idx) {
    var r = (permsState.add.results || [])[idx];
    if (!r) return;
    if (permsState.add.selectedUsers.some(function (u) { return u.sAMAccountName === r.sAMAccountName; })) return;
    permsState.add.selectedUsers.push({ sAMAccountName: r.sAMAccountName, displayName: r.displayName, email: r.email, alreadyInDL: !!r.alreadyInDL });
    var input = document.getElementById('permsAddSearch');
    input.value = '';
    document.getElementById('permsAddResults').style.display = 'none';
    document.getElementById('permsAddResults').innerHTML = '';
    permsState.add.results = [];
    permsRenderSelectedUsers();
    input.focus();
}

function permsAddRemoveUser(sam) {
    permsState.add.selectedUsers = permsState.add.selectedUsers.filter(function (u) { return u.sAMAccountName !== sam; });
    permsRenderSelectedUsers();
}

function permsAddClearUsers() {
    permsState.add.selectedUsers = [];
    permsRenderSelectedUsers();
}

function permsInitials(name) {
    var parts = (name || '?').trim().split(/\s+/);
    var a = parts[0] ? parts[0].charAt(0) : '?';
    var b = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (a + b).toUpperCase();
}

function permsRenderSelectedUsers() {
    var listEl = document.getElementById('permsAddSelectedList');
    var users = permsState.add.selectedUsers;
    document.getElementById('permsAddCount').textContent = users.length;
    document.getElementById('permsAddClearAll').style.display = users.length ? '' : 'none';
    if (!users.length) {
        listEl.innerHTML = '<div style="padding:16px; text-align:center; color:#6B7280; font-size:12px;">No users yet &mdash; search above and click people to add them.</div>';
    } else {
        var html = '';
        users.forEach(function (u) {
            var pill = u.alreadyInDL
                ? '<span style="background:var(--good-bg); color:var(--good-ink); padding:1px 8px; border-radius:8px; font-size:10px; white-space:nowrap;">In access DL</span>'
                : '<span style="background:var(--warn-bg); color:#7a5200; padding:1px 8px; border-radius:8px; font-size:10px; white-space:nowrap;">Email IT</span>';
            html += '<div style="display:flex; align-items:center; gap:10px; padding:8px 12px; border-bottom:1px solid var(--surface-2);">'
                + '<div style="width:28px; height:28px; border-radius:50%; background:var(--accent-2); color:var(--accent-ink); font-size:11px; font-weight:600; display:flex; align-items:center; justify-content:center; flex-shrink:0;">' + permsEsc(permsInitials(u.displayName)) + '</div>'
                + '<div style="flex:1; min-width:0;"><div style="font-size:13px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + permsEsc(u.displayName || u.sAMAccountName) + '</div>'
                + '<div style="font-size:11px; color:#6B7280; font-family:var(--font-mono); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + permsEsc(u.sAMAccountName) + (u.email ? ' &middot; ' + permsEsc(u.email) : '') + '</div></div>'
                + pill
                + '<button onclick="permsAddRemoveUser(\'' + permsEsc(u.sAMAccountName) + '\')" title="Remove" style="background:none; border:none; color:#6B7280; font-size:16px; cursor:pointer; line-height:1; flex-shrink:0;">&times;</button>'
                + '</div>';
        });
        listEl.innerHTML = html;
    }
    permsRenderAddFooter();
}

function permsGrantedKeys() {
    var g = permsState.add.granted || {};
    return Object.keys(g).filter(function (k) { return g[k]; });
}

function permsApplyPreset(id) {
    var cat = permsState.add.catalog;
    if (!cat) return;
    var preset = (cat.presets || []).filter(function (p) { return p.id === id; })[0];
    if (!preset) return;
    var g = {};
    (preset.tabKeys || []).forEach(function (k) { g[k] = true; });
    permsState.add.granted = g;
    permsState.add.activePreset = id;
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsToggleTab(key) {
    var g = permsState.add.granted;
    g[key] = !g[key];
    permsState.add.activePreset = null;   // hand-edit => custom
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsToggleGroup(group, selectAll) {
    var cat = permsState.add.catalog;
    (cat.tabs || []).forEach(function (t) {
        if (t.grantable && t.group === group) permsState.add.granted[t.key] = selectAll;
    });
    permsState.add.activePreset = null;
    permsRenderTabGroups();
    permsRenderPresets();
    permsRenderAddFooter();
}

function permsRenderPresets() {
    var cat = permsState.add.catalog;
    if (!cat) return;
    var el = document.getElementById('permsAddPresets');
    var active = permsState.add.activePreset;
    var html = '';
    (cat.presets || []).forEach(function (p) {
        var on = active === p.id;
        html += '<button onclick="permsApplyPreset(\'' + permsEsc(p.id) + '\')" '
            + 'style="padding:5px 12px; border-radius:14px; font-size:12px; cursor:pointer; '
            + (on ? 'background:var(--accent); color:#fff; border:1px solid var(--accent);' : 'background:var(--surface); color:#0F1720; border:1px solid var(--line-2);') + '">'
            + permsEsc(p.label) + '</button>';
    });
    if (!active) {
        html += '<span style="padding:5px 10px; font-size:12px; color:#6B7280;">Custom</span>';
    }
    el.innerHTML = html;
}

function permsRenderTabGroups() {
    var cat = permsState.add.catalog;
    if (!cat) return;
    var g = permsState.add.granted;
    var html = '';
    (cat.groups || []).forEach(function (group) {
        var tabs = (cat.tabs || []).filter(function (t) { return t.grantable && t.group === group; });
        if (!tabs.length) return;
        var sel = tabs.filter(function (t) { return g[t.key]; }).length;
        var allOn = sel === tabs.length;
        html += '<div style="padding:8px 2px; border-bottom:1px solid var(--surface-2);">'
            + '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">'
            + '<span style="font-size:12px; font-weight:600; color:#0F1720;">' + permsEsc(group) + ' <span style="color:#6B7280; font-weight:400;">' + sel + '/' + tabs.length + '</span></span>'
            + '<a href="javascript:void(0)" onclick="permsToggleGroup(\'' + permsEsc(group) + '\', ' + (allOn ? 'false' : 'true') + ')" style="font-size:11px; color:var(--accent);">' + (allOn ? 'Clear group' : 'Select all') + '</a>'
            + '</div>';
        tabs.forEach(function (t) {
            var on = !!g[t.key];
            html += '<label style="display:flex; align-items:center; gap:9px; padding:4px 2px; cursor:pointer;">'
                + '<span onclick="permsToggleTab(\'' + permsEsc(t.key) + '\')" style="width:16px; height:16px; border-radius:4px; flex-shrink:0; display:inline-flex; align-items:center; justify-content:center; '
                + (on ? 'background:var(--accent); border:1px solid var(--accent); color:#fff;' : 'background:var(--surface); border:1px solid var(--line-2); color:transparent;') + ' font-size:11px;">&#10003;</span>'
                + '<span onclick="permsToggleTab(\'' + permsEsc(t.key) + '\')" style="font-size:13px;">' + permsEsc(t.label) + '</span>'
                + '</label>';
        });
        html += '</div>';
    });
    document.getElementById('permsAddTabGroups').innerHTML = html;
    document.getElementById('permsAddTabCount').textContent = permsGrantedKeys().length;
}

function permsRenderAddFooter() {
    var n = permsState.add.selectedUsers.length;
    var tabsN = permsGrantedKeys().length;
    var emailN = permsState.add.selectedUsers.filter(function (u) { return !u.alreadyInDL; }).length;
    var summary = n === 0
        ? ''
        : n + ' user' + (n === 1 ? '' : 's') + ' &middot; ' + tabsN + ' tab' + (tabsN === 1 ? '' : 's') + ' each &middot; '
          + emailN + ' will be emailed to IT to add to the access DL';
    document.getElementById('permsAddSummary').innerHTML = summary;
    document.getElementById('permsAddSubmitCount').textContent = n;
    document.getElementById('permsAddSubmitBtn').disabled = n === 0;
}

function permsAddSubmit() {
    var users = permsState.add.selectedUsers;
    if (!users.length) { appAlert('Add at least one user first.'); return; }
    var allowed = permsGrantedKeys();
    var emailN = users.filter(function (u) { return !u.alreadyInDL; }).length;
    appConfirm('Submit ' + users.length + ' user' + (users.length === 1 ? '' : 's') + ' with ' + allowed.length + ' tab' + (allowed.length === 1 ? '' : 's') + ' each? ' + emailN + ' will be emailed to IT.', { okText: 'Submit' }).then(function (ok) {
        if (!ok) return;
        var btn = document.getElementById('permsAddSubmitBtn');
        btn.disabled = true;
        var payloadUsers = users.map(function (u) { return { sAMAccountName: u.sAMAccountName, displayName: u.displayName, email: u.email }; });
        fetch('/api/permissions/request-add-bulk', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ users: payloadUsers, allowedTabs: allowed })
        }).then(function (r) { return r.json(); })
          .then(function (resp) {
              if (!resp.success) { btn.disabled = false; appAlert('Submit failed: ' + (resp.error || 'unknown')); return; }
              permsCloseAdd();
              permsLoadUsers();
              appAlert('Added ' + resp.ok + ' user' + (resp.ok === 1 ? '' : 's') + '. ' + resp.emailed + ' emailed to IT to add to the access DL'
                  + (resp.failed ? '. ' + resp.failed + ' failed.' : '.'));
          })
          .catch(function (e) { btn.disabled = false; appAlert('Submit failed: ' + e); });
    });
}
```

- [ ] **Step 2: Sanity-check syntax + that old function names are gone**

Run: `node --check src/main/resources/static/user-permissions.js` → expect exit 0
Run: `grep -c "permsPickUser\|permsRenderAddTabList\|permsSubmitDLRequest" src/main/resources/static/user-permissions.js` → expect 0
Run: `grep -c "permsAddPickUser\|permsApplyPreset\|permsAddSubmit" src/main/resources/static/user-permissions.js` → expect ≥3

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/user-permissions.js
git commit -m "feat(user-mgmt): multi-select Add-from-AD modal logic (presets, groups, bulk submit)"
```

---

## Task 6: Changelog, build, local smoke test

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a What's New entry** at the top of `WHATS_NEW_RELEASES` (match the existing `{ date, title, items:[{badge,text}] }` shape; date `'June 24, 2026'`):

```javascript
  {
    date: 'June 24, 2026',
    title: 'User Management &middot; add several people at once, with tab presets',
    items: [
      { badge: 'improve', text: '<strong>&ldquo;+ Add user from AD&rdquo; now queues multiple people.</strong> Search and click to add several users, then submit them together instead of one at a time.' },
      { badge: 'improve', text: '<strong>Tab presets.</strong> Start from <em>Viewer</em>, <em>Items team</em>, <em>Reporting</em>, <em>Full access</em> or <em>Blank</em>, then fine-tune by group or individual tab &mdash; granting one tab is now two clicks instead of unchecking everything.' },
      { badge: 'improve', text: '<strong>One request to IT.</strong> A single consolidated email covers everyone who still needs to be added to the access DL; people already in the DL just get their tabs set.' }
    ]
  },
```

- [ ] **Step 2: Run the new + touched tests**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q test -Dtest=TabCatalogTest,DlPartitionTest`
Expected: all PASS.

- [ ] **Step 3: Build the JAR**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -DskipTests package`
Expected: `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 4: Hand off for local restart + smoke test**

Do NOT restart the local instance from within a subagent. Report DONE and let the controller (main session) handle the local `:8090` restart and the smoke test of `/tab-catalog` + the modal (the controller has the credentials and the restart procedure).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): multi-select Add-from-AD with tab presets"
```

---

## Self-Review

**Spec coverage:**
- Groups/presets sourced from backend registry → Task 1 (`TAB_GROUPS`/`getPresets`) + Task 3 (`/tab-catalog`). ✅
- Default preset Viewer → Task 5 (`permsOpenAddModal` → `permsApplyPreset('viewer')`). ✅
- Multi-select append, exclude already-added, clear+refocus → Task 5 (`permsAddPickUser`, `permsRenderResults`). ✅
- Selected list with initials, id·email mono, DL pill, remove, Clear all, empty state, submit-disabled-until-N≥1 → Task 4 markup + Task 5 (`permsRenderSelectedUsers`, `permsRenderAddFooter`). ✅
- Preset pills + active highlight + custom; grouped list with per-group select/clear + counts → Task 5 (`permsRenderPresets`, `permsRenderTabGroups`, `permsToggleGroup`). ✅
- Footer `N users · X tabs each · K emailed` → Task 5 (`permsRenderAddFooter`). ✅
- Shared tabs for all; consolidated email only for needs-DL; tabs saved for already-in-DL → Task 2 (`submitBulkDLRequest` + `partitionByDl`). ✅
- New `/request-add-bulk`, `/tab-catalog`; `/tabs` unchanged → Task 3. ✅
- v2 tokens, no new colors, red logo-only → Task 4 markup uses `var(--*)`. ✅
- Tests: catalog integrity + partition → Tasks 1 & 2. ✅

**Placeholder scan:** none — all steps contain full code.

**Type consistency:** `Preset{id,label,tabKeys}`, `DlPartition{needsDl,alreadyInDl}`, `BulkOutcome{...,emailedToIt}`, `partitionByDl(users, dlLower)`, catalog JSON `{tabs:[{key,label,group,grantable}],groups,presets:[{id,label,tabKeys}]}`, JS `permsState.add{selectedUsers,granted,activePreset,catalog,results}` — names consistent across Tasks 1/2/3/5. Endpoint paths `/tab-catalog`, `/request-add-bulk` consistent between Task 3 and Task 5.

**Note on local testing:** the controller (main session) restarts `:8090` and smoke-tests `/tab-catalog` + the modal; `/ad-search` works locally against live AD, so the multi-select flow can be exercised end-to-end except the final `/request-add-bulk` (which writes data + emails IT — exercise read paths, hold the live submit).
