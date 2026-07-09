# Shared Searches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to share saved searches so they appear in a "Shared" section of every user's My Searches dropdown.

**Architecture:** Add a `shared` boolean + `owner` string to the `SavedSearch` model. The GET endpoint returns the user's own searches plus all shared searches from other users. A new PUT toggle endpoint flips the shared flag. The frontend dropdown splits into "My Searches" and "Shared" sections, with a share icon on each owned search and the owner name displayed on shared entries.

**Tech Stack:** Java 8 / Spring Boot (backend), vanilla JavaScript (frontend), JSON file storage.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/sandisk/plm/tracker/service/SavedSearchService.java` | Modify | Add `shared`/`owner`/`ownerDisplay` fields to `SavedSearch`, add `getSharedSearches()` and `toggleShare()` methods |
| `src/main/java/com/sandisk/plm/tracker/controller/SavedSearchController.java` | Modify | Add `PUT /{id}/share` endpoint, update `GET` to return `{mine: [...], shared: [...]}` |
| `src/main/resources/static/app.js` | Modify | Update `loadSavedSearches()` to render two sections, add share toggle button, update `deleteSavedSearch` for shared items |
| `src/main/resources/static/index.html` | Modify | Add `sharedSearchesList` div in dropdown |

---

### Task 1: Add `shared`, `owner`, `ownerDisplay` fields to SavedSearch model

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/SavedSearchService.java:83-99`

- [ ] **Step 1: Add fields to SavedSearch inner class**

In `SavedSearchService.java`, replace the `SavedSearch` inner class (lines 83-99) with:

```java
    public static class SavedSearch {
        public String id;
        public String name;
        public String tab; // "changes", "bom", "parts"
        public Map<String, String> params;
        public long createdAt;
        public boolean shared;
        public String owner;        // username of creator
        public String ownerDisplay; // display name of creator

        public SavedSearch() {}

        public SavedSearch(String name, String tab, Map<String, String> params) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.name = name;
            this.tab = tab;
            this.params = params;
            this.createdAt = System.currentTimeMillis();
            this.shared = false;
        }
    }
```

Jackson will deserialize `shared` as `false` for existing records that lack the field, and `owner`/`ownerDisplay` as `null`. No migration needed.

- [ ] **Step 2: Verify the app compiles**

Run from `/Users/vikasjindal/git/plm-field-tracker`:
```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS (no errors).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SavedSearchService.java
git commit -m "feat: add shared/owner/ownerDisplay fields to SavedSearch model"
```

---

### Task 2: Add `getSharedSearches()` and `toggleShare()` to service

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/SavedSearchService.java:31-46`

- [ ] **Step 1: Add `getSharedSearches` method**

Add this method after the existing `getSearches()` method (after line 33):

```java
    public List<SavedSearch> getSharedSearches(String excludeUsername) {
        List<SavedSearch> result = new ArrayList<>();
        for (Map.Entry<String, List<SavedSearch>> entry : userSearches.entrySet()) {
            if (entry.getKey().equals(excludeUsername)) continue;
            for (SavedSearch s : entry.getValue()) {
                if (s.shared) result.add(s);
            }
        }
        return result;
    }
```

- [ ] **Step 2: Add `toggleShare` method**

Add this method after `getSharedSearches`:

```java
    public SavedSearch toggleShare(String username, String searchId, String displayName) {
        List<SavedSearch> list = userSearches.get(username);
        if (list == null) return null;
        for (SavedSearch s : list) {
            if (s.id != null && s.id.equals(searchId)) {
                s.shared = !s.shared;
                if (s.shared) {
                    s.owner = username;
                    s.ownerDisplay = displayName;
                } else {
                    s.owner = null;
                    s.ownerDisplay = null;
                }
                saveToDisk();
                return s;
            }
        }
        return null;
    }
```

- [ ] **Step 3: Backfill owner on existing shared searches during load**

In the `loadFromDisk()` method, inside the backfill loop (after the ID backfill block around line 60), add owner backfill:

```java
                    if (s.shared && s.owner == null) {
                        s.owner = entry.getKey();
                        needsSave = true;
                    }
```

This requires changing the outer loop to iterate `entrySet()` instead of `values()`. Replace the backfill loop (lines 57-64) with:

```java
            for (Map.Entry<String, List<SavedSearch>> entry : data.entrySet()) {
                for (SavedSearch s : entry.getValue()) {
                    if (s.id == null || s.id.isEmpty()) {
                        s.id = UUID.randomUUID().toString().substring(0, 8);
                        needsSave = true;
                    }
                    if (s.shared && s.owner == null) {
                        s.owner = entry.getKey();
                        needsSave = true;
                    }
                }
            }
```

- [ ] **Step 4: Verify the app compiles**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SavedSearchService.java
git commit -m "feat: add getSharedSearches and toggleShare service methods"
```

---

### Task 3: Update controller — new toggle endpoint and response shape

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/SavedSearchController.java`

- [ ] **Step 1: Change GET response to return `{mine: [...], shared: [...]}`**

Replace the `list` method (lines 23-27) with:

```java
    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        String username = (String) session.getAttribute("username");
        Map<String, Object> result = new LinkedHashMap<>();
        if (username == null) {
            result.put("mine", new ArrayList<>());
            result.put("shared", new ArrayList<>());
            return result;
        }
        result.put("mine", savedSearchService.getSearches(username));
        result.put("shared", savedSearchService.getSharedSearches(username));
        return result;
    }
```

- [ ] **Step 2: Populate owner/ownerDisplay on save**

In the `save` method, after `if (username == null)` check (around line 39), add owner fields before saving:

```java
        search.owner = username;
        search.ownerDisplay = (String) session.getAttribute("displayName");
```

So lines 38-40 become:

```java
        search.owner = username;
        search.ownerDisplay = (String) session.getAttribute("displayName");
        savedSearchService.saveSearch(username, search);
```

- [ ] **Step 3: Add PUT toggle endpoint**

Add this method after the `save` method:

```java
    @PutMapping("/{id}/share")
    public Map<String, Object> toggleShare(@PathVariable String id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String displayName = (String) session.getAttribute("displayName");
        Map<String, Object> response = new LinkedHashMap<>();
        if (username == null) {
            response.put("success", false);
            response.put("message", "Not logged in.");
            return response;
        }
        SavedSearchService.SavedSearch updated = savedSearchService.toggleShare(username, id, displayName);
        if (updated == null) {
            response.put("success", false);
            response.put("message", "Search not found.");
            return response;
        }
        response.put("success", true);
        response.put("shared", updated.shared);
        return response;
    }
```

- [ ] **Step 4: Verify the app compiles**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/SavedSearchController.java
git commit -m "feat: update searches API — split mine/shared response, add toggle endpoint"
```

---

### Task 4: Update dropdown HTML to have two sections

**Files:**
- Modify: `src/main/resources/static/index.html:42-46`

- [ ] **Step 1: Replace dropdown inner HTML**

Replace lines 42-46 (the `savedSearchesDropdown` div and its contents, up to and including the closing `</div>` of the dropdown) with:

```html
            <div id="savedSearchesDropdown" style="display:none; position:absolute; right:0; top:24px; background:white; border:1px solid #d0d5dd; border-radius:8px; box-shadow:0 4px 16px rgba(0,0,0,0.15); z-index:200; min-width:300px; max-height:400px; overflow-y:auto;">
                <div style="padding:8px 12px; border-bottom:1px solid #eee; font-size:12px; font-weight:600; color:#555;">My Searches</div>
                <div id="savedSearchesList" style="padding:4px 0;"></div>
                <div id="sharedSearchesSection" style="display:none;">
                    <div style="padding:8px 12px; border-top:1px solid #eee; border-bottom:1px solid #eee; font-size:12px; font-weight:600; color:#555;">Shared</div>
                    <div id="sharedSearchesList" style="padding:4px 0;"></div>
                </div>
                <div style="padding:8px 12px; border-top:1px solid #eee; font-size:11px; color:#999;">Click to load, X to delete</div>
            </div>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add shared searches section to dropdown HTML"
```

---

### Task 5: Update `loadSavedSearches()` to render both sections

**Files:**
- Modify: `src/main/resources/static/app.js:1135-1170`

- [ ] **Step 1: Replace `loadSavedSearches` function**

Replace the entire `loadSavedSearches` function (lines 1135-1170) with:

```javascript
function loadSavedSearches() {
    fetch('/api/searches')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            var mine = data.mine || [];
            var shared = data.shared || [];

            // Render user's own searches
            var list = document.getElementById('savedSearchesList');
            list.innerHTML = '';
            if (mine.length === 0) {
                list.innerHTML = '<div style="padding:12px; color:#999; font-size:12px; text-align:center;">No saved searches yet.<br>Click the star button to save one.</div>';
            } else {
                mine.forEach(function(s) {
                    list.appendChild(buildSearchRow(s, true));
                });
            }

            // Render shared searches
            var sharedSection = document.getElementById('sharedSearchesSection');
            var sharedList = document.getElementById('sharedSearchesList');
            sharedList.innerHTML = '';
            if (shared.length === 0) {
                sharedSection.style.display = 'none';
            } else {
                sharedSection.style.display = 'block';
                shared.forEach(function(s) {
                    sharedList.appendChild(buildSearchRow(s, false));
                });
            }
        });
}
```

- [ ] **Step 2: Add `buildSearchRow` helper function**

Add this function right before `loadSavedSearches`:

```javascript
function buildSearchRow(s, isOwned) {
    var div = document.createElement('div');
    div.style.cssText = 'padding:6px 12px; display:flex; justify-content:space-between; align-items:center; cursor:pointer; font-size:12px;';
    div.onmouseover = function() { div.style.background = '#f0f4ff'; };
    div.onmouseout = function() { div.style.background = ''; };

    var tabLabel = s.tab === 'changes' ? 'FC' : s.tab === 'bom' ? 'BOM' : s.tab === 'history' ? 'HIST' : s.tab === 'parts' ? 'Parts' : s.tab === 'sku' ? 'SKU' : 'Agile';

    var nameSpan = document.createElement('span');
    var labelHtml = '<span style="background:#e8f0fe; color:#1a3a5c; padding:1px 5px; border-radius:3px; font-size:10px; font-weight:600; margin-right:6px;">' + tabLabel + '</span>';
    if (!isOwned && s.ownerDisplay) {
        labelHtml += '<span style="color:#999; font-size:10px; margin-right:4px;">' + esc(s.ownerDisplay) + ':</span>';
    }
    nameSpan.innerHTML = labelHtml + esc(s.name);
    nameSpan.style.cssText = 'flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
    nameSpan.onclick = function() { loadSavedSearch(s); };

    var actions = document.createElement('span');
    actions.style.cssText = 'display:flex; align-items:center; gap:2px; flex-shrink:0;';

    if (isOwned) {
        // Share toggle button
        var shareBtn = document.createElement('span');
        shareBtn.textContent = s.shared ? '\u{1F517}' : '\u{1F513}';
        shareBtn.title = s.shared ? 'Shared — click to unshare' : 'Private — click to share';
        shareBtn.style.cssText = 'cursor:pointer; padding:4px 6px; font-size:12px; line-height:1; border-radius:3px; opacity:' + (s.shared ? '1' : '0.4') + ';';
        shareBtn.onmouseover = function() { shareBtn.style.background = '#e8f0fe'; shareBtn.style.opacity = '1'; };
        shareBtn.onmouseout = function() { shareBtn.style.background = 'transparent'; shareBtn.style.opacity = s.shared ? '1' : '0.4'; };
        shareBtn.onclick = function(e) { e.stopPropagation(); toggleShareSearch(s.id); };
        actions.appendChild(shareBtn);

        // Delete button
        var delBtn = document.createElement('span');
        delBtn.textContent = '\u2715';
        delBtn.title = 'Delete this saved search';
        delBtn.style.cssText = 'color:#ccc; cursor:pointer; padding:4px 6px; font-size:13px; line-height:1; border-radius:3px;';
        delBtn.onmouseover = function() { delBtn.style.color = '#dc3545'; delBtn.style.background = '#fde8e8'; };
        delBtn.onmouseout = function() { delBtn.style.color = '#ccc'; delBtn.style.background = 'transparent'; };
        delBtn.onclick = function(e) { e.stopPropagation(); e.preventDefault(); deleteSavedSearch(s.id); };
        actions.appendChild(delBtn);
    }

    div.appendChild(nameSpan);
    div.appendChild(actions);
    return div;
}
```

- [ ] **Step 3: Add `toggleShareSearch` function**

Add this function after `buildSearchRow`:

```javascript
function toggleShareSearch(id) {
    fetch('/api/searches/' + encodeURIComponent(id) + '/share', { method: 'PUT' })
        .then(function(res) { return res.json(); })
        .then(function(data) {
            if (data.success) {
                loadSavedSearches();
            } else {
                showCustomAlert('PLM Toolkit', data.message || 'Could not toggle sharing.');
            }
        })
        .catch(function(err) {
            showCustomAlert('PLM Toolkit', 'Error: ' + (err.message || 'network error'));
        });
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/app.js
git commit -m "feat: render shared searches section in dropdown with share toggle"
```

---

### Task 6: Manual testing

- [ ] **Step 1: Restart the app**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && ./mvnw spring-boot:run
```

- [ ] **Step 2: Test as first user**

1. Log in and open My Searches dropdown — existing searches should appear under "My Searches" with a lock icon and X button.
2. The "Shared" section should be hidden (no shared searches yet).
3. Click the lock icon on a search — it should change to a link icon and the search should appear in the Shared section for other users.
4. Click the link icon again — it should revert to a lock (unshared).

- [ ] **Step 3: Test as second user**

1. Log in as a different user (or in an incognito window).
2. Open My Searches — the "Shared" section should show the search shared by the first user, with the first user's display name prefix.
3. Clicking the shared search should load the search parameters and switch to the correct tab.
4. The second user should NOT see a delete or share button on searches they don't own.

- [ ] **Step 4: Verify backward compatibility**

1. Check that the `data/saved-searches.json` file now has `shared`, `owner`, `ownerDisplay` fields on toggled searches.
2. Verify existing searches that were never toggled still work — they should have `shared: false` and `owner: null` by default (Jackson defaults).

- [ ] **Step 5: Commit any final fixes if needed**
