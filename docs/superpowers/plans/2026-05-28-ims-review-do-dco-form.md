# IMS Review — DO Needs-Change DCO Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a side-panel DCO form to the DO "Needs Change — Upload" flow that lets the DO submit a fully-populated DCO from the toolkit response page, with the toolkit creating + auto-submitting the DCO in Agile via plm-agile-service.

**Architecture:** Two-phase submit (pre-validate → submit), drawer-style UI on `ims-respond.html`, new `create-dco-rich` endpoint on plm-agile-service that orchestrates an 11-step DCO-creation cascade with rollback on failure. Robust `[AGILE-WRITE]` structured logging at every SDK call site (corrId-tagged, greppable). Two independent kill-switches: `app.ims-review.dco-form-enabled` (rich form) and `app.ims-review.writeback-enabled` (legacy Phase-4 cascade).

**Tech Stack:** Java 8 (Spring Boot 2.x), vanilla JS (no framework), Agile SDK 9.3.6, javax.mail SMTP.

**Spec:** `docs/superpowers/specs/2026-05-28-ims-review-do-dco-form-design.md`

**Critical constraint:** Local Mac cannot reach Agile SDK. All Agile-side validation happens via deploy → log-inspection → iterate. Logging IS the test surface.

---

## Repository layout

Two repos involved:
- `~/git/plm-field-tracker` — Spring Boot port 8090 (the toolkit)
- `~/git/plm-agile-service` — Spring Boot port 8081 (Agile SDK wrapper)

## File-by-file map

### plm-agile-service (NEW files)
| Path | Purpose |
|---|---|
| `src/main/java/com/sandisk/plm/agile/controller/AgileFormController.java` | 4 new endpoints (list-values, users/search, validate-form, create-dco-rich) |
| `src/main/java/com/sandisk/plm/agile/service/AdminListCacheService.java` | `IListLibrary.getAdminList(name).getValues()` with 1h TTL |
| `src/main/java/com/sandisk/plm/agile/service/UserSearchService.java` | Live SQL on `agile.agileuser` |
| `src/main/java/com/sandisk/plm/agile/service/DcoFormValidator.java` | Resolves users + checks list values for pre-validate |
| `src/main/java/com/sandisk/plm/agile/service/DcoRichCreationService.java` | 11-step orchestrator with rollback |

### plm-agile-service (MODIFIED)
| Path | Change |
|---|---|
| `src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java` | Add 6 new request/response POJOs |
| `src/main/resources/application.properties` | Add `agile.dco.list.*` keys + `agile.dco.cell.*` keys (defaults TBD) + cache TTL |

### plm-field-tracker (NEW files)
| Path | Purpose |
|---|---|
| `src/main/resources/static/imsreview-dco-form.js` | Drawer rendering + typeahead + pre-validate + submit |
| `src/main/resources/templates/email/ims-review-dco-stakeholder-notify.html` | Post-submit stakeholder notification template |

### plm-field-tracker (MODIFIED)
| Path | Change |
|---|---|
| `src/main/resources/static/ims-respond.html` | Drawer markup + CSS, JS include, action handler swap when `UPLOAD` chosen |
| `src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java` | 4 new endpoints (§6.1–6.4 of spec) |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java` | `validateDcoForm`, `respondViaTokenWithDcoForm`, stakeholder notify helper |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewQueueStore.java` | 3 new Event fields (`dcoForm`, `dcoAttachmentsManifest`, `dcoFormChecksum`) |
| `src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java` | 4 new methods (`listValues`, `searchUsers`, `validateForm`, `createDcoRich`) |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewPdfService.java` | "Submitted DCO request" section |
| `src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java` | `payloadForStakeholderNotify(...)` |
| `src/main/resources/application.properties` | `app.ims-review.dco-form-enabled=false` + agile-service URL keys |
| `src/main/resources/static/whats-new.js` | Release entry |

---

## Phase A — plm-agile-service: read-only endpoints

### Task A1 — Add model classes to `WriteBackModels.java`

**Files:**
- Modify: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/model/WriteBackModels.java`

Append the following inside the `WriteBackModels` final class (after the existing `CreateDcoResponse`):

```java
    // --- list-values (cached admin-list dropdowns) ---
    public static final class ListValuesResponse {
        public boolean ok = true;
        public java.util.Map<String, java.util.List<String>> lists = new java.util.LinkedHashMap<>();
        public String cachedAt;          // ISO-8601 UTC
        public long   cacheTtlSec;
        public String errorReason;       // populated on lookup failure (still ok=false)
    }

    // --- user search (typeahead) ---
    public static final class UserHit {
        public String loginId;
        public String displayName;       // "Last, First"
        public String email;
    }
    public static final class UserSearchResponse {
        public boolean ok = true;
        public java.util.List<UserHit> hits = new java.util.ArrayList<>();
        public String errorReason;
    }

    // --- validate-form (pre-submit dry run) ---
    public static final class ValidateFormRequest {
        public String docNumber;                 // for context only
        public String priority;
        public String descriptionOfChange;
        public String reasonForChange;
        public java.util.List<String> productLines = new java.util.ArrayList<>();
        public java.util.List<String> subcontractors = new java.util.ArrayList<>();
        public String trainingRequirement;
        public String businessUnit;
        public String changeImpactDisposition;
        public String changeImpactDetails;
        public java.util.List<String> documentOwners = new java.util.ArrayList<>();
        public java.util.List<String> approvers = new java.util.ArrayList<>();
        public java.util.List<String> observers = new java.util.ArrayList<>();
        public java.util.List<String> notifyStakeholders = new java.util.ArrayList<>();
        public java.util.List<AttachmentManifestEntry> attachmentManifest = new java.util.ArrayList<>();
    }
    public static final class AttachmentManifestEntry {
        public String type;              // "Redline" | "Final" | "Others"
        public String filename;
        public long   sizeBytes;
    }
    public static final class ValidateFormResponse {
        public boolean ok;
        public java.util.Map<String, String> fieldErrors = new java.util.LinkedHashMap<>();
        public java.util.List<String> formErrors = new java.util.ArrayList<>();
    }

    // --- create-dco-rich (the big one) ---
    public static final class CreateDcoRichRequest {
        public ValidateFormRequest form;   // same shape as validate
        public String currentRev;          // for rev-bump sanity check
        // Multipart file parts arrive as RequestParam, not in this JSON object.
    }
    public static final class CreateDcoRichResponse {
        public boolean ok;
        public String dcoNumber;
        public String newRev;
        public String revBumpKind;          // "INTEGER" | "ALPHA" | "FIRST_REV" | "UNRECOGNIZED"
        public int    attachmentsAttached;
        public int    approvers;
        public int    observers;
        public int    documentOwners;
        public boolean submitted;
        public String currentStatus;
        public java.util.List<String> stepsOk = new java.util.ArrayList<>();
        public String stepFailedAt;
        public String errorReason;
        public String rollback;             // "ok" | "failed" | null
        public String rollbackError;
        public String orphanDco;            // non-null only on rollback=failed
    }
```

- [ ] **Step A1.1** — Add the 6 POJOs above to `WriteBackModels.java`.
- [ ] **Step A1.2** — Run `cd ~/git/plm-agile-service && mvn compile -q` to verify it compiles.

### Task A2 — `AdminListCacheService` + GET `/api/agile/dco/list-values`

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/AdminListCacheService.java`
- Modify: `~/git/plm-agile-service/src/main/resources/application.properties`

#### `AdminListCacheService.java`

```java
package com.sandisk.plm.agile.service;

import com.agile.api.IAdmin;
import com.agile.api.IAdminList;
import com.agile.api.IAgileList;
import com.agile.api.IAgileSession;
import com.agile.api.IListLibrary;
import com.sandisk.plm.agile.support.AgileObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * In-memory cache of Agile IAdminList permissible values for the DCO form
 * dropdowns. List names come from application.properties so renames on the
 * Agile side are a config change, not a code change.
 *
 * <p>Single TTL applied to all lists (default 1h). First lookup of the day
 * fans out to ~6 SDK calls; everyone else gets sub-millisecond reads. Cache
 * invalidates by full snapshot (not per-list) so the response is always
 * internally consistent.
 *
 * <p>Logging: every refresh emits a [AGILE-LIST-CACHE] line with per-list
 * value count and elapsed time. Lookup failures DO NOT throw — they leave
 * the corresponding list as an empty array with errorReason populated, so a
 * broken admin-list config doesn't break the whole form-metadata response.
 */
@Service
public class AdminListCacheService {

    private static final Logger LOG = Logger.getLogger(AdminListCacheService.class.getName());

    @Value("${agile.dco.list.priority:Priority}")               private String listPriority;
    @Value("${agile.dco.list.productLines:Product Lines}")       private String listProductLines;
    @Value("${agile.dco.list.subcontractors:Subcontractors}")    private String listSubcontractors;
    @Value("${agile.dco.list.trainingRequirement:Training Requirement}") private String listTrainingRequirement;
    @Value("${agile.dco.list.businessUnit:Business Unit}")       private String listBusinessUnit;
    @Value("${agile.dco.list.changeImpactDisposition:Change Impact Disposition}") private String listChangeImpactDisposition;
    @Value("${agile.dco.list.cache-ttl-sec:3600}")               private long cacheTtlSec;

    private final AgileObject agObject;

    public AdminListCacheService(AgileObject agObject) {
        this.agObject = agObject;
    }

    /** Returned snapshot. All lists populated, even ones that failed lookup
     *  (those have empty value arrays + their name listed in {@link #lastErrors}). */
    public static final class Snapshot {
        public Map<String, List<String>> lists = new LinkedHashMap<>();
        public List<String> errors = new ArrayList<>();
        public Instant fetchedAt;
    }

    private volatile Snapshot cached = null;

    public synchronized Snapshot get() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAt.toEpochMilli()) < (cacheTtlSec * 1000L)) {
            LOG.fine("[AGILE-LIST-CACHE] hit, age=" + ((now - cached.fetchedAt.toEpochMilli()) / 1000) + "s");
            return cached;
        }
        return refresh();
    }

    public synchronized Snapshot refresh() {
        long t0 = System.currentTimeMillis();
        Snapshot s = new Snapshot();
        // Order matters — same order as the form renders.
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("priority",                listPriority);
        wanted.put("productLines",            listProductLines);
        wanted.put("subcontractors",          listSubcontractors);
        wanted.put("trainingRequirement",     listTrainingRequirement);
        wanted.put("businessUnit",            listBusinessUnit);
        wanted.put("changeImpactDisposition", listChangeImpactDisposition);

        IAgileSession session = agObject.getSession();
        IAdmin admin = session.getAdminInstance();
        IListLibrary lib = admin.getListLibrary();

        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String key = e.getKey();
            String listName = e.getValue();
            long stepT0 = System.currentTimeMillis();
            try {
                IAdminList al = lib.getAdminList(listName);
                if (al == null) {
                    s.lists.put(key, Collections.emptyList());
                    s.errors.add(key + ":not-found(" + listName + ")");
                    LOG.warning("[AGILE-LIST-CACHE] miss list=" + listName + " (getAdminList returned null)");
                    continue;
                }
                IAgileList agl = (IAgileList) al.getValues();
                List<String> vals = new ArrayList<>();
                if (agl != null) {
                    Collection<?> children = agl.getChildNodes();
                    if (children != null) {
                        for (Object child : children) {
                            IAgileList node = (IAgileList) child;
                            Object v = node.getValue();
                            if (v != null) vals.add(v.toString());
                        }
                    }
                }
                s.lists.put(key, vals);
                LOG.info("[AGILE-LIST-CACHE] loaded list=" + listName + " count=" + vals.size()
                        + " elapsedMs=" + (System.currentTimeMillis() - stepT0));
            } catch (Exception ex) {
                s.lists.put(key, Collections.emptyList());
                s.errors.add(key + ":err(" + ex.getClass().getSimpleName() + ":" + ex.getMessage() + ")");
                LOG.warning("[AGILE-LIST-CACHE] FAIL list=" + listName
                        + " err=" + ex.getClass().getName() + ":" + ex.getMessage()
                        + " elapsedMs=" + (System.currentTimeMillis() - stepT0));
            }
        }
        s.fetchedAt = Instant.now();
        cached = s;
        LOG.info("[AGILE-LIST-CACHE] refresh complete lists=" + wanted.size()
                + " errorCount=" + s.errors.size()
                + " totalMs=" + (System.currentTimeMillis() - t0));
        return s;
    }

    @PostConstruct
    public void warm() {
        // Eager warm on startup so the first user doesn't pay the ~3-5 sec fetch.
        try {
            LOG.info("[AGILE-LIST-CACHE] startup warm beginning");
            refresh();
        } catch (Throwable t) {
            LOG.warning("[AGILE-LIST-CACHE] startup warm failed: " + t.getMessage());
        }
    }

    public long getCacheTtlSec() { return cacheTtlSec; }
}
```

**Note**: This calls `AgileObject.getSession()` — confirm that method exists. If not, dispatch a quick grep before writing. If `AgileObject` doesn't expose `getSession()`, use whatever the existing `AgileWriteBackService` uses (likely `agObject.connect()` returning IAgileSession or similar).

#### `application.properties` additions

Append at the end of `~/git/plm-agile-service/src/main/resources/application.properties`:

```properties

# -----------------------------------------------------------------------
# DCO form metadata — admin-list names + cache TTL (Phase 5)
# See plm-field-tracker docs/superpowers/specs/2026-05-28-ims-review-do-dco-form-design.md
# These names must match the AdminList names in your Agile environment.
# Cache refreshes hourly; @PostConstruct warm makes the first user's form
# open sub-200ms (cache hit) instead of ~3-5 sec (live IListLibrary fan-out).
# -----------------------------------------------------------------------
agile.dco.list.priority=Priority
agile.dco.list.productLines=Product Lines
agile.dco.list.subcontractors=Subcontractors
agile.dco.list.trainingRequirement=Training Requirement
agile.dco.list.businessUnit=Business Unit
agile.dco.list.changeImpactDisposition=Change Impact Disposition
agile.dco.list.cache-ttl-sec=3600
```

- [ ] **Step A2.1** — Verify `AgileObject.getSession()` exists; if not, adapt the call to whatever pattern the existing services use.
- [ ] **Step A2.2** — Create `AdminListCacheService.java` with the content above.
- [ ] **Step A2.3** — Append the 7 properties lines to `application.properties`.
- [ ] **Step A2.4** — Run `mvn compile -q`. Expected: clean compile, no warnings beyond legacy Agile SDK deprecations.

### Task A3 — `UserSearchService` for typeahead

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/UserSearchService.java`

```java
package com.sandisk.plm.agile.service;

import com.sandisk.plm.agile.support.AgileObject;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Live user-search backing the typeahead pickers on the DCO form (Document
 * Owners / Approvers / Observers).
 *
 * <p>Queries Agile's user table directly via JDBC instead of going through the
 * SDK because:
 *   - SDK has no bulk "search users LIKE x" method
 *   - SDK iteration of all users + client-side filter would 100ms+ per keystroke
 *   - JDBC + indexed columns is sub-100ms
 *
 * <p>Active-user gate is the SDK's "inactive_flag = 0" — same definition as
 * the rest of the toolkit's user lookups use.
 *
 * <p>Returns up to {@code limit} hits (caller-capped at 50). Display name is
 * "Last, First" to match the existing toolkit conventions.
 */
@Service
public class UserSearchService {

    private static final Logger LOG = Logger.getLogger(UserSearchService.class.getName());

    private static final String SQL =
        "SELECT loginid, first_name, last_name, email " +
        "FROM   agile.agileuser " +
        "WHERE  inactive_flag = 0 " +
        "  AND (UPPER(first_name) LIKE UPPER(?) " +
        "   OR  UPPER(last_name)  LIKE UPPER(?) " +
        "   OR  UPPER(loginid)    LIKE UPPER(?) " +
        "   OR  UPPER(email)      LIKE UPPER(?)) " +
        "ORDER  BY last_name, first_name " +
        "FETCH  FIRST ? ROWS ONLY";

    private final DataSource dataSource;

    public UserSearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static final class Hit {
        public String loginId;
        public String displayName;
        public String email;
    }

    public List<Hit> search(String q, int limit) {
        long t0 = System.currentTimeMillis();
        List<Hit> out = new ArrayList<>();
        if (q == null || q.trim().length() < 2) {
            LOG.info("[USER-SEARCH] short query rejected q=\"" + q + "\"");
            return out;
        }
        int cap = Math.max(1, Math.min(50, limit));
        String like = "%" + q.trim() + "%";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setInt(5, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Hit h = new Hit();
                    h.loginId = rs.getString("loginid");
                    String ln = rs.getString("last_name"), fn = rs.getString("first_name");
                    h.displayName = (ln == null ? "" : ln) + ", " + (fn == null ? "" : fn);
                    h.email = rs.getString("email");
                    out.add(h);
                }
            }
            LOG.info("[USER-SEARCH] q=\"" + q + "\" limit=" + cap
                    + " hits=" + out.size() + " elapsedMs=" + (System.currentTimeMillis() - t0));
        } catch (Exception ex) {
            LOG.warning("[USER-SEARCH] FAIL q=\"" + q + "\""
                    + " err=" + ex.getClass().getName() + ":" + ex.getMessage()
                    + " elapsedMs=" + (System.currentTimeMillis() - t0));
        }
        return out;
    }
}
```

**Pre-task verification:** Check whether plm-agile-service has a `DataSource` bean. If not (the service is SDK-only today), this needs an Oracle JDBC driver dep + DataSource configuration. Quick grep: `grep -rn "DataSource\|spring.datasource\|jdbc:oracle" ~/git/plm-agile-service/` — if 0 hits, this task expands to include adding the dep + config (the toolkit's existing `agile.host`/`agile.port` DB config can be borrowed as a model).

- [ ] **Step A3.1** — Verify whether plm-agile-service has a configured JDBC DataSource. If not, add `ojdbc8` to `pom.xml` and configure `spring.datasource.*` properties pointing at the same DB the toolkit uses (steal connection string from plm-field-tracker's `application.properties`).
- [ ] **Step A3.2** — Create `UserSearchService.java` with the content above.
- [ ] **Step A3.3** — Run `mvn compile -q`. Expected: clean compile.

### Task A4 — Create `AgileFormController` with the two read endpoints

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/AgileFormController.java`

```java
package com.sandisk.plm.agile.controller;

import com.sandisk.plm.agile.model.WriteBackModels.*;
import com.sandisk.plm.agile.service.AdminListCacheService;
import com.sandisk.plm.agile.service.UserSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IMS Review DCO-form HTTP surface — read endpoints for dropdown sourcing
 * and typeahead. The write endpoints (validate-form, create-dco-rich) live
 * here too once Tasks B1-B3 land.
 *
 * <p>Same wrapper pattern as {@link AgileWriteBackController} —
 * {@code { corrId, body }} envelope so the toolkit can correlate logs.
 */
@RestController
@RequestMapping("/api")
public class AgileFormController {

    @Autowired private AdminListCacheService adminLists;
    @Autowired private UserSearchService userSearch;

    private static final String CORR_ID_HEADER = "X-Toolkit-Action-Id";

    // ------------------------------------------------------------------
    // GET /api/agile/dco/list-values
    // ------------------------------------------------------------------
    @GetMapping("/agile/dco/list-values")
    public ResponseEntity<?> listValues(
            @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        String cid = orNew(corrId);
        AdminListCacheService.Snapshot snap = adminLists.get();
        ListValuesResponse r = new ListValuesResponse();
        r.ok = snap.errors.isEmpty();
        r.lists = snap.lists;
        r.cachedAt = snap.fetchedAt == null ? Instant.now().toString() : snap.fetchedAt.toString();
        r.cacheTtlSec = adminLists.getCacheTtlSec();
        if (!r.ok) r.errorReason = "Some lists failed: " + String.join("; ", snap.errors);
        return ResponseEntity.ok(withCorr(r, cid));
    }

    // ------------------------------------------------------------------
    // GET /api/agile/users/search?q=…&limit=20
    // ------------------------------------------------------------------
    @GetMapping("/agile/users/search")
    public ResponseEntity<?> searchUsers(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        String cid = orNew(corrId);
        UserSearchResponse r = new UserSearchResponse();
        for (UserSearchService.Hit h : userSearch.search(q, limit)) {
            UserHit uh = new UserHit();
            uh.loginId = h.loginId;
            uh.displayName = h.displayName;
            uh.email = h.email;
            r.hits.add(uh);
        }
        r.ok = true;
        return ResponseEntity.ok(withCorr(r, cid));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static String orNew(String corrId) {
        return (corrId == null || corrId.trim().isEmpty()) ? UUID.randomUUID().toString() : corrId.trim();
    }

    private static Map<String, Object> withCorr(Object body, String corrId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("corrId", corrId);
        m.put("body", body);
        return m;
    }
}
```

- [ ] **Step A4.1** — Create `AgileFormController.java`.
- [ ] **Step A4.2** — Run `mvn package -DskipTests -q`. Expected: BUILD SUCCESS.
- [ ] **Step A4.3** — Smoke-grep for `[AGILE-LIST-CACHE]` and `[USER-SEARCH]` log prefix to confirm they'll appear correctly.

---

## Phase B — plm-agile-service: write endpoints

### Task B1 — `DcoFormValidator` + POST `/api/agile/dco/validate-form`

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/DcoFormValidator.java`
- Modify: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/controller/AgileFormController.java` (add endpoint)

#### `DcoFormValidator.java`

```java
package com.sandisk.plm.agile.service;

import com.sandisk.plm.agile.model.WriteBackModels.*;
import com.sandisk.plm.agile.support.AgileObject;
import com.agile.api.IAgileSession;
import com.agile.api.IUser;
import com.agile.api.UserConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Pre-validates the rich DCO form payload BEFORE the toolkit calls the heavy
 * create-dco-rich endpoint. Resolves each picked user to an active Agile
 * user; cross-checks each picked list value against the cached IAdminList
 * catalog; regex-checks Notify Stakeholders entries; enforces length caps.
 *
 * <p>Errors are returned per-field-key — toolkit JS surfaces them at the
 * matching field in the drawer. No SDK writes occur here.
 */
@Service
public class DcoFormValidator {

    private static final Logger LOG = Logger.getLogger(DcoFormValidator.class.getName());

    private static final Pattern EMAIL_RE =
        Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    /** DL pattern matches any of: prefix pdl-/ims-/dl-, suffix -dl, contains "dl-" */
    private static final Pattern DL_RE =
        Pattern.compile("^(pdl-|ims-|dl-).+$|^.+-dl$", Pattern.CASE_INSENSITIVE);

    private static final int MAX_TEXT = 200;
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;     // 25 MB per file
    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;   // 100 MB total

    @Value("${agile.dco.list.priority:Priority}")               private String listPriority;
    // (other list names not needed — we read from the snapshot)

    private final AdminListCacheService lists;
    private final AgileObject agObject;

    public DcoFormValidator(AdminListCacheService lists, AgileObject agObject) {
        this.lists = lists;
        this.agObject = agObject;
    }

    public ValidateFormResponse validate(ValidateFormRequest req, AgileWriteBackLogger log) {
        long t0 = System.currentTimeMillis();
        ValidateFormResponse out = new ValidateFormResponse();
        if (req == null) {
            out.formErrors.add("Request body is empty.");
            out.ok = false;
            return out;
        }

        AdminListCacheService.Snapshot snap = lists.get();

        // -- mandatory text fields --
        requireText(out, "priority", req.priority);
        requireText(out, "descriptionOfChange", req.descriptionOfChange);
        capLength(out, "descriptionOfChange", req.descriptionOfChange);
        requireText(out, "reasonForChange", req.reasonForChange);
        capLength(out, "reasonForChange", req.reasonForChange);
        capLength(out, "changeImpactDetails", req.changeImpactDetails);   // optional but capped
        requireText(out, "trainingRequirement", req.trainingRequirement);

        // -- mandatory list-bound dropdowns --
        requireListValue(out, "priority", req.priority, snap.lists.get("priority"));
        requireListValue(out, "trainingRequirement", req.trainingRequirement, snap.lists.get("trainingRequirement"));
        requireListValueIfPresent(out, "businessUnit", req.businessUnit, snap.lists.get("businessUnit"));
        requireListValueIfPresent(out, "changeImpactDisposition", req.changeImpactDisposition, snap.lists.get("changeImpactDisposition"));

        // -- mandatory multi-selects --
        requireListMulti(out, "productLines", req.productLines, snap.lists.get("productLines"));
        requireListMulti(out, "subcontractors", req.subcontractors, snap.lists.get("subcontractors"));

        // -- mandatory user pickers --
        validateUsers(out, "documentOwners", req.documentOwners, true);
        validateUsers(out, "approvers",      req.approvers,      true);
        validateUsers(out, "observers",      req.observers,      true);

        // -- notify stakeholders --
        if (req.notifyStakeholders == null || req.notifyStakeholders.isEmpty()) {
            out.fieldErrors.put("notifyStakeholders", "At least one stakeholder is required.");
        } else {
            for (int i = 0; i < req.notifyStakeholders.size(); i++) {
                String tok = req.notifyStakeholders.get(i) == null ? "" : req.notifyStakeholders.get(i).trim();
                if (tok.isEmpty()) continue;
                if (!EMAIL_RE.matcher(tok).matches() && !DL_RE.matcher(tok).matches()) {
                    out.fieldErrors.put("notifyStakeholders[" + i + "]",
                            "'" + tok + "' is not a valid email address or DL name");
                }
            }
        }

        // -- attachments --
        long total = 0;
        if (req.attachmentManifest != null) {
            for (int i = 0; i < req.attachmentManifest.size(); i++) {
                AttachmentManifestEntry a = req.attachmentManifest.get(i);
                if (a == null) continue;
                if (!"Redline".equals(a.type) && !"Final".equals(a.type) && !"Others".equals(a.type)) {
                    out.fieldErrors.put("attachment[" + i + "].type",
                            "Unknown attachment type '" + a.type + "' — must be Redline, Final, or Others");
                }
                if (a.sizeBytes > MAX_FILE_BYTES) {
                    out.fieldErrors.put("attachment[" + i + "].size",
                            a.filename + " is " + (a.sizeBytes / 1024 / 1024) + " MB (max 25 MB)");
                }
                total += a.sizeBytes;
            }
        }
        if (total > MAX_TOTAL_BYTES) {
            out.formErrors.add("Total attachment size " + (total / 1024 / 1024) + " MB exceeds 100 MB cap.");
        }

        out.ok = out.fieldErrors.isEmpty() && out.formErrors.isEmpty();
        log.note("validate-form ok=" + out.ok
                + " fieldErrors=" + out.fieldErrors.size()
                + " formErrors=" + out.formErrors.size()
                + " elapsedMs=" + (System.currentTimeMillis() - t0));
        return out;
    }

    // ------------------------------------------------------------------
    private static void requireText(ValidateFormResponse out, String key, String val) {
        if (val == null || val.trim().isEmpty()) {
            out.fieldErrors.put(key, "Required.");
        }
    }
    private static void capLength(ValidateFormResponse out, String key, String val) {
        if (val != null && val.length() > MAX_TEXT) {
            out.fieldErrors.put(key, "Too long (" + val.length() + " > " + MAX_TEXT + " chars).");
        }
    }
    private static void requireListValue(ValidateFormResponse out, String key, String val, List<String> catalog) {
        if (val == null || val.trim().isEmpty()) return;  // requireText already complained
        if (catalog == null || catalog.isEmpty()) {
            out.fieldErrors.put(key, "Catalog unavailable — try again in a moment.");
            return;
        }
        if (!catalog.contains(val)) {
            out.fieldErrors.put(key, "'" + val + "' is not a valid choice.");
        }
    }
    private static void requireListValueIfPresent(ValidateFormResponse out, String key, String val, List<String> catalog) {
        if (val == null || val.trim().isEmpty()) return;  // optional
        if (catalog == null) return;                        // optional + catalog missing → don't reject
        if (!catalog.contains(val)) {
            out.fieldErrors.put(key, "'" + val + "' is not a valid choice.");
        }
    }
    private static void requireListMulti(ValidateFormResponse out, String key, List<String> vals, List<String> catalog) {
        if (vals == null || vals.isEmpty()) {
            out.fieldErrors.put(key, "At least one required.");
            return;
        }
        if (catalog == null || catalog.isEmpty()) {
            out.fieldErrors.put(key, "Catalog unavailable — try again in a moment.");
            return;
        }
        for (int i = 0; i < vals.size(); i++) {
            if (!catalog.contains(vals.get(i))) {
                out.fieldErrors.put(key + "[" + i + "]", "'" + vals.get(i) + "' is not a valid choice.");
            }
        }
    }

    private void validateUsers(ValidateFormResponse out, String key, List<String> emails, boolean required) {
        if (emails == null || emails.isEmpty()) {
            if (required) out.fieldErrors.put(key, "At least one required.");
            return;
        }
        try {
            IAgileSession session = agObject.getSession();
            for (int i = 0; i < emails.size(); i++) {
                String email = emails.get(i) == null ? "" : emails.get(i).trim();
                if (email.isEmpty()) continue;
                if (!EMAIL_RE.matcher(email).matches()) {
                    out.fieldErrors.put(key + "[" + i + "]", email + " is not a valid email format");
                    continue;
                }
                IUser u = resolveActiveUser(session, email);
                if (u == null) {
                    out.fieldErrors.put(key + "[" + i + "]", email + " — not an active Agile user");
                }
            }
        } catch (Exception ex) {
            LOG.warning("[DCO-VALIDATE] user-resolve failed for " + key
                    + " err=" + ex.getClass().getName() + ":" + ex.getMessage());
            out.fieldErrors.put(key, "User catalog lookup failed: " + ex.getMessage());
        }
    }

    /** Find one IUser by email, only if active. Returns null otherwise. */
    private static IUser resolveActiveUser(IAgileSession session, String email) {
        try {
            // SDK doesn't expose a direct getUserByEmail. Standard pattern: iterate
            // the AdminInstance users — but that's slow. Toolkit-side already has
            // a JDBC user-search that hits agile.agileuser; for validate, we accept
            // a higher per-user cost since this only runs on submit, not per
            // keystroke. If this becomes a bottleneck, swap to JDBC for the active
            // check too (same query pattern as UserSearchService).
            //
            // Try the SDK convention IUser.OBJECT_TYPE + the email-as-key first;
            // production SanDisk Agile uses email as the LoginID, so this usually
            // hits on first try.
            IUser u = (IUser) session.getObject(IUser.OBJECT_TYPE, email);
            if (u == null) return null;
            Object inactive = u.getValue(UserConstants.ATT_GENERAL_INFO_STATUS);
            // Status values in our env: "Active" / "Inactive" / "Disabled".
            // Anything not exactly "Active" → reject.
            String s = inactive == null ? "" : inactive.toString();
            return "Active".equalsIgnoreCase(s) ? u : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
```

#### Endpoint addition to `AgileFormController.java`

Add field + endpoint inside `AgileFormController`:

```java
    @Autowired private DcoFormValidator validator;

    // ------------------------------------------------------------------
    // POST /api/agile/dco/validate-form
    // ------------------------------------------------------------------
    @PostMapping(value = "/agile/dco/validate-form",
                 consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateForm(
            @RequestBody ValidateFormRequest req,
            @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        com.sandisk.plm.agile.service.AgileWriteBackLogger log =
                new com.sandisk.plm.agile.service.AgileWriteBackLogger(orNew(corrId), "validate-form");
        if (req != null) log.doc(req.docNumber);
        try {
            ValidateFormResponse r = validator.validate(req, log);
            return ResponseEntity.ok(withCorr(r, log.getCorrId()));
        } finally {
            log.summary();
        }
    }
```

- [ ] **Step B1.1** — Create `DcoFormValidator.java` with the content above.
- [ ] **Step B1.2** — Add the `@Autowired DcoFormValidator` field + the `validate-form` endpoint to `AgileFormController.java`.
- [ ] **Step B1.3** — Run `mvn compile -q`. Expected: clean.

### Task B2 — `DcoRichCreationService` skeleton + cover-page + attachments

**Files:**
- Create: `~/git/plm-agile-service/src/main/java/com/sandisk/plm/agile/service/DcoRichCreationService.java`
- Modify: `~/git/plm-agile-service/src/main/resources/application.properties` (add new cell-ID keys)

The orchestrator is long. Reference implementation patterns from `AgileWriteBackService.java`'s existing `createDco(...)` method (lines covering create + cover-page cells + relationships).

```java
package com.sandisk.plm.agile.service;

import com.agile.api.*;
import com.sandisk.plm.agile.model.WriteBackModels.*;
import com.sandisk.plm.agile.support.AgileObject;
import com.sandisk.plm.agile.support.AgileChange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/**
 * 11-step orchestration that creates a fully-populated DCO from the rich DO
 * form, attaches files with per-file Attachment Type, adds reviewers, and
 * submits. On mid-cascade failure, soft-deletes the half-created DCO.
 *
 * <p>Every step emits a structured [AGILE-WRITE] log line (see
 * {@link AgileWriteBackLogger}). The corrId on every line is the same UUID
 * the toolkit sent in X-Toolkit-Action-Id — paste it into any log and the
 * full chain is visible.
 *
 * <p>Cell base IDs come from application.properties. Defaults are best-guess
 * (matching the existing AgileWriteBackService defaults where they overlap).
 * Unconfirmed cells default to TBD = -1; the orchestrator skips those steps
 * with a [AGILE-WRITE-NOTE] line so dry runs surface the cell-ID gap clearly.
 */
@Service
public class DcoRichCreationService {

    private static final Logger LOG = Logger.getLogger(DcoRichCreationService.class.getName());

    @Value("${agile.dco.workflow.name:DCO Workflow}")        private String dcoWorkflowName;
    @Value("${agile.dco.subclass.name:Document Change Order}") private String dcoSubclassName;

    // Existing cell IDs (already in AgileWriteBackService)
    @Value("${agile.cell.changeAnalyst:1099}")           private int cellChangeAnalyst;
    @Value("${agile.cell.coverPageDescription:1052}")     private int cellDescription;
    @Value("${agile.cell.coverPageReason:1053}")          private int cellReason;
    @Value("${agile.cell.drrDcoNumber:1575}")             private int cellDrrDcoNumber;
    @Value("${agile.cell.affectedNewRev:1063}")           private int cellAffectedNewRev;

    // New cell IDs for rich form (TBD = -1 means skip step; log clearly)
    @Value("${agile.cell.dcoPriority:-1}")                private int cellPriority;
    @Value("${agile.cell.dcoProductLines:-1}")            private int cellProductLines;
    @Value("${agile.cell.dcoSubcontractors:-1}")          private int cellSubcontractors;
    @Value("${agile.cell.dcoTrainingRequirement:-1}")     private int cellTrainingRequirement;
    @Value("${agile.cell.dcoBusinessUnit:-1}")            private int cellBusinessUnit;
    @Value("${agile.cell.dcoChangeImpactDisposition:-1}") private int cellChangeImpactDisposition;
    @Value("${agile.cell.dcoChangeImpactDetails:-1}")     private int cellChangeImpactDetails;
    @Value("${agile.cell.dcoDocumentOwners:-1}")          private int cellDocumentOwners;
    @Value("${agile.cell.dcoAttachmentType:-1}")          private int cellAttachmentType;

    private final AgileObject agObject;
    private final RevBumper revBumper;
    private final AdminListCacheService lists;

    public DcoRichCreationService(AgileObject agObject, RevBumper revBumper, AdminListCacheService lists) {
        this.agObject = agObject;
        this.revBumper = revBumper;
        this.lists = lists;
    }

    public CreateDcoRichResponse create(String drrNumber,
                                        CreateDcoRichRequest req,
                                        List<MultipartFile> attachments,
                                        AgileWriteBackLogger log) {
        log.drr(drrNumber).doc(req.form == null ? null : req.form.docNumber);
        CreateDcoRichResponse r = new CreateDcoRichResponse();
        IChange dco = null;
        IChange drr = null;
        boolean dcoCreated = false;

        try {
            IAgileSession session = agObject.getSession();
            IAdmin admin = session.getAdminInstance();

            // STEP 1: find the DRR
            long t = System.currentTimeMillis();
            drr = (IChange) session.getObject(IChange.OBJECT_TYPE, drrNumber);
            if (drr == null) {
                log.stepFailed("findDrr", new RuntimeException("DRR not found: " + drrNumber),
                        System.currentTimeMillis() - t);
                r.stepFailedAt = "findDrr";
                r.errorReason = "DRR not found: " + drrNumber;
                return r;
            }
            log.step("findDrr", "drr", drrNumber, System.currentTimeMillis() - t);

            // STEP 2: create the DCO
            t = System.currentTimeMillis();
            IAgileClass dcoClass = admin.getAgileClass(dcoSubclassName);
            HashMap<Integer, Object> dcoParams = new HashMap<>();
            dcoParams.put(ChangeConstants.ATT_COVER_PAGE_WORKFLOW,
                    admin.getAgileClass(dcoSubclassName).getWorkflows()[0]);   // first workflow; tighten if needed
            dco = (IChange) session.createObject(dcoClass, dcoParams);
            dcoCreated = true;
            r.dcoNumber = dco.getName();
            r.stepsOk.add("createDco=" + r.dcoNumber);
            log.dco(r.dcoNumber).step("createDco", "subclass", dcoSubclassName,
                    System.currentTimeMillis() - t);

            // STEP 3: cover-page cells
            t = System.currentTimeMillis();
            ValidateFormRequest f = req.form;
            setCellAlways(dco, cellDescription, f.descriptionOfChange, "description", log);
            setCellAlways(dco, cellReason, f.reasonForChange, "reason", log);
            setCellIfConfigured(dco, cellPriority, f.priority, "priority", log);
            setListCellIfConfigured(dco, cellProductLines, f.productLines, "productLines", log);
            setListCellIfConfigured(dco, cellSubcontractors, f.subcontractors, "subcontractors", log);
            setCellIfConfigured(dco, cellTrainingRequirement, f.trainingRequirement, "trainingRequirement", log);
            setCellIfConfigured(dco, cellBusinessUnit, f.businessUnit, "businessUnit", log);
            setCellIfConfigured(dco, cellChangeImpactDisposition, f.changeImpactDisposition, "changeImpactDisposition", log);
            setCellIfConfigured(dco, cellChangeImpactDetails, f.changeImpactDetails, "changeImpactDetails", log);
            // Change Analyst — reuse existing default
            try {
                ICell ca = dco.getCell(cellChangeAnalyst);
                IAgileList caList = ca.getAvailableValues();
                caList.setSelection(new Object[]{"Change Analyst"});
                ca.setValue(caList);
            } catch (Exception ignore) {
                log.note("setChangeAnalyst skipped: " + ignore.getMessage());
            }
            r.stepsOk.add("coverPageCells=ok");
            log.step("coverPageCells", System.currentTimeMillis() - t);

            // STEP 4: Document Owners (multi-user cell)
            if (cellDocumentOwners > 0 && f.documentOwners != null && !f.documentOwners.isEmpty()) {
                t = System.currentTimeMillis();
                List<IUser> docOwners = resolveUsers(session, f.documentOwners);
                if (!docOwners.isEmpty()) {
                    dco.getCell(cellDocumentOwners).setValue(docOwners.toArray(new IUser[0]));
                    r.documentOwners = docOwners.size();
                    r.stepsOk.add("setDocumentOwners=" + docOwners.size());
                    log.step("setDocumentOwners", "count", docOwners.size(),
                            System.currentTimeMillis() - t);
                }
            } else {
                log.note("setDocumentOwners skipped (cell not configured or empty list)");
            }

            // STEP 5: Affected Items — IMS Doc with bumped New Rev
            t = System.currentTimeMillis();
            ITable affected = dco.getTable(ChangeConstants.TABLE_AFFECTEDITEMS);
            IItem imsDoc = (IItem) session.getObject(IItem.OBJECT_TYPE, f.docNumber);
            if (imsDoc == null) {
                log.stepFailed("affectedItem", new RuntimeException("IMS Doc not found: " + f.docNumber),
                        System.currentTimeMillis() - t);
                throw new RuntimeException("IMS Doc not found: " + f.docNumber);
            }
            IRow affRow = affected.createRow(imsDoc);
            String currentRev = req.currentRev == null ? "" : req.currentRev;
            RevBumper.Result bump = revBumper.next(currentRev);
            r.newRev = bump.next;
            r.revBumpKind = bump.kind.name();
            if (cellAffectedNewRev > 0) {
                affRow.getCell(cellAffectedNewRev).setValue(bump.next);
            }
            r.stepsOk.add("affectedItem=" + f.docNumber + " rev=" + bump.next);
            log.step("affectedItem", new LinkedHashMap<String, Object>() {{
                put("doc", f.docNumber);
                put("oldRev", currentRev);
                put("newRev", bump.next);
                put("kind", bump.kind.name());
            }}, System.currentTimeMillis() - t);

            // STEP 6: Relationships — add DRR with auto-close rule
            t = System.currentTimeMillis();
            ITable rels = dco.getTable(ChangeConstants.TABLE_RELATIONSHIPS);
            IRow relRow = rels.createRow(drr);
            try {
                HashMap<Integer, Object> rule = new HashMap<>();
                rule.put(ChangeConstants.ATT_RELATIONSHIPS_RULE_CONTROLOBJECT, dco);
                rule.put(ChangeConstants.ATT_RELATIONSHIPS_RULE_AFFECTEDOBJECT, drr);
                rule.put(ChangeConstants.ATT_RELATIONSHIPS_RULE_CONTROLOBJECTSTATUS,
                        findStatus(dco, "Implemented"));
                rule.put(ChangeConstants.ATT_RELATIONSHIPS_RULE_AFFECTEDOBJECTSTATUS,
                        findStatus(drr, "Implemented"));
                relRow.setValue(ChangeConstants.ATT_RELATIONSHIPS_RULE, rule);
                r.stepsOk.add("relationshipRule=DCO@Impl->DRR@Impl");
            } catch (Exception relErr) {
                log.note("relationshipRule failed: " + relErr.getMessage());
            }
            log.step("relationshipRow", "drr", drrNumber, System.currentTimeMillis() - t);

            // STEP 7: stamp DCO# onto DRR page-3 DCO Number cell (1575)
            t = System.currentTimeMillis();
            try {
                drr.getCell(cellDrrDcoNumber).setValue(r.dcoNumber);
                r.stepsOk.add("stampDcoOnDrr=" + cellDrrDcoNumber);
                log.step("stampDcoOnDrr", "cell", cellDrrDcoNumber, System.currentTimeMillis() - t);
            } catch (Exception ex) {
                log.note("stampDcoOnDrr failed (cell " + cellDrrDcoNumber + "): " + ex.getMessage());
            }

            // STEP 8: attachments with per-file type
            if (attachments != null && !attachments.isEmpty()) {
                t = System.currentTimeMillis();
                ITable attTable = dco.getAttachments();
                int attached = 0;
                for (MultipartFile mf : attachments) {
                    if (mf == null || mf.isEmpty()) continue;
                    File tmp = null;
                    try {
                        tmp = File.createTempFile("dco-att-", "-" + sanitize(mf.getOriginalFilename()));
                        try (FileOutputStream fos = new FileOutputStream(tmp)) {
                            fos.write(mf.getBytes());
                        }
                        IRow row = (IRow) attTable.createRow(tmp);
                        // Attachment Type cell (e.g. "Redline Copy" / "Final Version" / "Others")
                        // The Type comes from the multipart field name (file_redline / file_final / file_others)
                        // Controller maps that to the human-readable Agile type string.
                        String typeKey = extractType(mf.getName());      // "Redline" | "Final" | "Others"
                        String agileTypeValue = mapTypeToAgile(typeKey); // "Redline Copy" | "Final Version" | "Others"
                        if (cellAttachmentType > 0) {
                            try {
                                ICell typeCell = row.getCell(cellAttachmentType);
                                IAgileList tlist = typeCell.getAvailableValues();
                                tlist.setSelection(new Object[]{ agileTypeValue });
                                typeCell.setValue(tlist);
                            } catch (Exception typeErr) {
                                log.note("setAttachmentType failed for " + mf.getOriginalFilename()
                                        + " type=" + agileTypeValue + " err=" + typeErr.getMessage());
                            }
                        }
                        attached++;
                    } finally {
                        if (tmp != null) try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignored) {}
                    }
                }
                r.attachmentsAttached = attached;
                r.stepsOk.add("attachments=" + attached);
                log.step("attachments", "count", attached, System.currentTimeMillis() - t);
            }

            // STEPS 9-10: addReviewers (approvers + observers) + change status — Task B3 lands these.

            r.submitted = false;
            r.currentStatus = "(unsubmitted — Task B3 wires up the submit cascade)";
            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.stepFailedAt = r.stepsOk.isEmpty() ? "(pre-step)" : r.stepsOk.get(r.stepsOk.size() - 1) + ":next";
            r.errorReason = t.getClass().getName() + ":" + t.getMessage();
            LOG.warning("[DCO-CREATE-RICH] FAIL " + r.errorReason);

            // ROLLBACK — soft-delete the half-created DCO
            if (dcoCreated && dco != null) {
                try {
                    long rt = System.currentTimeMillis();
                    dco.getCell(ChangeConstants.ATT_GENERAL_INFO_DELETE_FLAG).setValue("Yes");
                    r.rollback = "ok";
                    log.note("rollback soft-deleted dco=" + r.dcoNumber
                            + " elapsedMs=" + (System.currentTimeMillis() - rt));
                } catch (Throwable rb) {
                    r.rollback = "failed";
                    r.rollbackError = rb.getClass().getName() + ":" + rb.getMessage();
                    r.orphanDco = r.dcoNumber;
                    log.note("rollback FAILED for dco=" + r.dcoNumber + " err=" + rb.getMessage());
                }
            }
            return r;
        }
    }

    // ------------------------------------------------------------------
    private void setCellAlways(IChange dco, int cellId, String value, String name, AgileWriteBackLogger log) {
        if (cellId <= 0) return;
        try {
            dco.getCell(cellId).setValue(value == null ? "" : value);
        } catch (Exception ex) {
            log.note("setCell '" + name + "' cell=" + cellId + " failed: " + ex.getMessage());
        }
    }

    private void setCellIfConfigured(IChange dco, int cellId, String value, String name, AgileWriteBackLogger log) {
        if (cellId <= 0) {
            log.note("setCell '" + name + "' skipped (cellId not configured)");
            return;
        }
        if (value == null || value.trim().isEmpty()) return;
        try {
            ICell cell = dco.getCell(cellId);
            IAgileList l = cell.getAvailableValues();
            if (l != null) {
                l.setSelection(new Object[]{value});
                cell.setValue(l);
            } else {
                cell.setValue(value);
            }
        } catch (Exception ex) {
            log.note("setCell '" + name + "' cell=" + cellId + " value=" + value
                    + " failed: " + ex.getMessage());
        }
    }

    private void setListCellIfConfigured(IChange dco, int cellId, List<String> values, String name, AgileWriteBackLogger log) {
        if (cellId <= 0) {
            log.note("setListCell '" + name + "' skipped (cellId not configured)");
            return;
        }
        if (values == null || values.isEmpty()) return;
        try {
            ICell cell = dco.getCell(cellId);
            IAgileList l = cell.getAvailableValues();
            l.setSelection(values.toArray(new Object[0]));
            cell.setValue(l);
        } catch (Exception ex) {
            log.note("setListCell '" + name + "' cell=" + cellId + " values=" + values
                    + " failed: " + ex.getMessage());
        }
    }

    private List<IUser> resolveUsers(IAgileSession session, List<String> emails) {
        List<IUser> out = new ArrayList<>();
        for (String e : emails) {
            if (e == null || e.trim().isEmpty()) continue;
            try {
                IUser u = (IUser) session.getObject(IUser.OBJECT_TYPE, e.trim());
                if (u != null) out.add(u);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static IStatus findStatus(IChange change, String statusName) throws APIException {
        for (IStatus s : change.getStatusObjects()) {
            if (statusName.equalsIgnoreCase(s.getName())) return s;
        }
        return null;
    }

    private static String sanitize(String name) {
        if (name == null) return "file.bin";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String extractType(String paramName) {
        if (paramName == null) return "Others";
        if (paramName.contains("redline")) return "Redline";
        if (paramName.contains("final")) return "Final";
        return "Others";
    }

    private static String mapTypeToAgile(String typeKey) {
        if ("Redline".equals(typeKey)) return "Redline Copy";
        if ("Final".equals(typeKey))   return "Final Version";
        return "Others";
    }
}
```

**Add to `application.properties`:**

```properties

# DCO-form rich-form cell IDs. Defaults are -1 (not-configured) so the
# orchestrator skips the step with a [AGILE-WRITE-NOTE] line — dry run #3
# surfaces what to set them to.
agile.cell.dcoPriority=-1
agile.cell.dcoProductLines=-1
agile.cell.dcoSubcontractors=-1
agile.cell.dcoTrainingRequirement=-1
agile.cell.dcoBusinessUnit=-1
agile.cell.dcoChangeImpactDisposition=-1
agile.cell.dcoChangeImpactDetails=-1
agile.cell.dcoDocumentOwners=-1
agile.cell.dcoAttachmentType=-1
```

- [ ] **Step B2.1** — Create `DcoRichCreationService.java`.
- [ ] **Step B2.2** — Append the new cell-ID keys to `application.properties`.
- [ ] **Step B2.3** — Run `mvn compile -q`. Expected: clean.

### Task B3 — `DcoRichCreationService` — addReviewers + changeStatus + endpoint wiring

Extend the `create(...)` method in `DcoRichCreationService.java` after the attachments step (step 8) — before the `r.submitted = false;` placeholder — with these steps:

```java
            // STEP 9: addReviewers — approvers + observers
            t = System.currentTimeMillis();
            List<IUser> approverUsers = req.form.approvers == null ? new ArrayList<>() : resolveUsers(session, req.form.approvers);
            List<IUser> observerUsers = req.form.observers == null ? new ArrayList<>() : resolveUsers(session, req.form.observers);
            try {
                IStatus current = dco.getStatus();
                if (!approverUsers.isEmpty() || !observerUsers.isEmpty()) {
                    IDataObject[] approverArr = approverUsers.toArray(new IDataObject[0]);
                    IDataObject[] observerArr = observerUsers.toArray(new IDataObject[0]);
                    dco.addReviewers(current, approverArr, observerArr,
                            null, false,
                            "Added via PLM Toolkit IMS Review (rich-form DCO submission)");
                    r.approvers = approverUsers.size();
                    r.observers = observerUsers.size();
                    r.stepsOk.add("addReviewers approvers=" + r.approvers + " observers=" + r.observers);
                    log.step("addReviewers", new LinkedHashMap<String, Object>() {{
                        put("approvers", approverUsers.size());
                        put("observers", observerUsers.size());
                    }}, System.currentTimeMillis() - t);
                }
            } catch (Exception revErr) {
                log.note("addReviewers failed: " + revErr.getMessage());
                // Non-fatal — DCC can add reviewers manually if this breaks.
            }

            // STEP 10: changeStatus → Submitted (or whichever the workflow's Submit state is)
            t = System.currentTimeMillis();
            try {
                IStatus submitTarget = findStatus(dco, "Submitted");
                if (submitTarget == null) submitTarget = findStatus(dco, "Submit");
                if (submitTarget != null) {
                    dco.changeStatus(submitTarget,
                            false, "", false, false,
                            null, null, null, null, false);
                    r.submitted = true;
                    r.currentStatus = submitTarget.getName();
                    r.stepsOk.add("changeStatus=" + submitTarget.getName());
                    log.step("changeStatus", "to", submitTarget.getName(),
                            System.currentTimeMillis() - t);
                } else {
                    r.currentStatus = "(no Submitted/Submit status found)";
                    log.note("changeStatus skipped — no 'Submitted' or 'Submit' status on workflow");
                }
            } catch (Exception subErr) {
                log.note("changeStatus FAIL: " + subErr.getMessage());
                r.stepFailedAt = "changeStatus";
                r.errorReason = subErr.getClass().getName() + ":" + subErr.getMessage();
                // DON'T roll back — DCO is fully populated, just not Submitted.
                // DCC can submit it manually from Agile.
            }

            r.ok = true;
            return r;
```

Remove the previous placeholder lines:
```java
            r.submitted = false;
            r.currentStatus = "(unsubmitted — Task B3 wires up the submit cascade)";
            r.ok = true;
            return r;
```

Then add the endpoint to `AgileFormController.java`:

```java
    @Autowired private DcoRichCreationService dcoRich;

    // ------------------------------------------------------------------
    // POST /api/drr/{drr}/create-dco-rich  (multipart)
    //   parts: form (JSON string), file_redline, file_final, file_others (repeated)
    // ------------------------------------------------------------------
    @PostMapping(value = "/drr/{drr}/create-dco-rich",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createDcoRich(
            @org.springframework.web.bind.annotation.PathVariable("drr") String drr,
            @RequestParam("form") String formJson,
            @RequestParam(value = "currentRev", required = false) String currentRev,
            @RequestParam(value = "file_redline", required = false) org.springframework.web.multipart.MultipartFile fileRedline,
            @RequestParam(value = "file_final", required = false)   org.springframework.web.multipart.MultipartFile fileFinal,
            @RequestParam(value = "file_others", required = false)  org.springframework.web.multipart.MultipartFile[] filesOthers,
            @RequestHeader(value = CORR_ID_HEADER, required = false) String corrId) {
        com.sandisk.plm.agile.service.AgileWriteBackLogger log =
                new com.sandisk.plm.agile.service.AgileWriteBackLogger(orNew(corrId), "create-dco-rich");
        log.drr(drr);
        try {
            // Parse form JSON
            ValidateFormRequest form;
            try {
                form = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(formJson, ValidateFormRequest.class);
            } catch (Exception parseErr) {
                CreateDcoRichResponse r = new CreateDcoRichResponse();
                r.ok = false;
                r.errorReason = "Bad form JSON: " + parseErr.getMessage();
                log.note(r.errorReason);
                return ResponseEntity.badRequest().body(withCorr(r, log.getCorrId()));
            }
            log.doc(form.docNumber);

            CreateDcoRichRequest req = new CreateDcoRichRequest();
            req.form = form;
            req.currentRev = currentRev;

            java.util.List<org.springframework.web.multipart.MultipartFile> atts = new java.util.ArrayList<>();
            if (fileRedline != null && !fileRedline.isEmpty()) atts.add(fileRedline);
            if (fileFinal   != null && !fileFinal.isEmpty())   atts.add(fileFinal);
            if (filesOthers != null) for (org.springframework.web.multipart.MultipartFile o : filesOthers)
                if (o != null && !o.isEmpty()) atts.add(o);

            CreateDcoRichResponse r = dcoRich.create(drr, req, atts, log);
            return r.ok
                    ? ResponseEntity.ok(withCorr(r, log.getCorrId()))
                    : ResponseEntity.status(500).body(withCorr(r, log.getCorrId()));
        } finally {
            log.summary();
        }
    }
```

- [ ] **Step B3.1** — Add steps 9 + 10 to `DcoRichCreationService.create(...)` per above.
- [ ] **Step B3.2** — Remove the placeholder block.
- [ ] **Step B3.3** — Add the `createDcoRich` endpoint to `AgileFormController.java`.
- [ ] **Step B3.4** — Run `mvn package -DskipTests -q`. Expected: BUILD SUCCESS.

---

## Phase C — plm-field-tracker: backend

### Task C1 — Extend `ImsReviewQueueStore.Event` with 3 new fields

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewQueueStore.java`

Inside the `Event` class (after the existing `agileError` field around line 120), add:

```java
        // ----- DCO-form rich-payload fields (Phase 5) -----
        /** Full DCO form payload — captures DO intent for audit even if Agile rejects. */
        public java.util.Map<String, Object> dcoForm;
        /** Per-file metadata for attachments included in the DCO submission. */
        public java.util.List<java.util.Map<String, Object>> dcoAttachmentsManifest;
        /** SHA-256 of the canonicalized DCO form payload. Printed on the
         *  compliance PDF to bind the PDF 1:1 to the exact form the DO signed. */
        public String dcoFormChecksum;
```

- [ ] **Step C1.1** — Add the 3 fields to `Event`.
- [ ] **Step C1.2** — Run `cd ~/git/plm-field-tracker && mvn compile -q`. Expected: clean.

### Task C2 — Extend `AgileWriteBackClient` with 4 new methods

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/AgileWriteBackClient.java`

Append after the existing `createDco(...)` method:

```java
    // ------------------------------------------------------------------
    // 6) GET /api/agile/dco/list-values   (Phase 5 — DCO form metadata)
    // ------------------------------------------------------------------
    public Result listValues(String corrId) {
        return getWithRetry(serviceUrl + "/api/agile/dco/list-values", orNew(corrId), "list-values");
    }

    // ------------------------------------------------------------------
    // 7) GET /api/agile/users/search?q=…&limit=…   (Phase 5 — typeahead)
    // ------------------------------------------------------------------
    public Result searchUsers(String q, int limit, String corrId) {
        try {
            String url = serviceUrl + "/api/agile/users/search?q="
                    + URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8)
                    + "&limit=" + Math.max(1, Math.min(50, limit));
            return getWithRetry(url, orNew(corrId), "users-search");
        } catch (Exception e) {
            return clientError(orNew(corrId), "users-search", e, 0);
        }
    }

    // ------------------------------------------------------------------
    // 8) POST /api/agile/dco/validate-form   (Phase 5 — pre-submit validate)
    // ------------------------------------------------------------------
    public Result validateForm(Map<String, Object> form, String corrId) {
        return postJson("/api/agile/dco/validate-form", form, orNew(corrId), "validate-form");
    }

    // ------------------------------------------------------------------
    // 9) POST /api/drr/{drr}/create-dco-rich   (Phase 5 — the meaty one, multipart)
    // ------------------------------------------------------------------
    public Result createDcoRich(String drrNumber,
                                String formJson,
                                String currentRev,
                                java.util.List<NamedBlob> attachments,
                                String corrId) {
        String cid = orNew(corrId);
        String url = serviceUrl + "/api/drr/"
                + URLEncoder.encode(drrNumber, StandardCharsets.UTF_8) + "/create-dco-rich";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String boundary = "----PLMToolkitBoundary-" + System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs * 5);  // create-dco-rich can take 30s
            conn.setRequestProperty("X-Toolkit-Action-Id", cid);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                writeTextPart(out, boundary, "form", formJson);
                if (currentRev != null) writeTextPart(out, boundary, "currentRev", currentRev);
                if (attachments != null) {
                    for (NamedBlob a : attachments) {
                        if (a == null || a.bytes == null) continue;
                        writeFilePart(out, boundary, a.partName, a.filename, a.bytes);
                    }
                }
                out.writeBytes("--" + boundary + "--\r\n");
            }
            return readResponse(conn, cid, "create-dco-rich", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return clientError(cid, "create-dco-rich", e, System.currentTimeMillis() - t0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** File blob with a multipart-part-name + filename (the part name selects
     *  the attachment-type slot: file_redline / file_final / file_others). */
    public static final class NamedBlob {
        public final String partName;     // "file_redline" | "file_final" | "file_others"
        public final String filename;
        public final byte[] bytes;
        public NamedBlob(String partName, String filename, byte[] bytes) {
            this.partName = partName; this.filename = filename; this.bytes = bytes;
        }
    }

    private static void writeTextPart(DataOutputStream out, String boundary,
                                      String name, String value) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }
    private static void writeFilePart(DataOutputStream out, String boundary,
                                      String name, String filename, byte[] bytes) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + (filename == null ? "file.bin" : filename) + "\"\r\n");
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        out.write(bytes);
        out.writeBytes("\r\n");
    }
```

**Note:** There may be a naming collision — `ImsReviewEmailService` already has a `NamedBlob` class. To avoid confusion, the new one is on `AgileWriteBackClient.NamedBlob` (qualified usage).

- [ ] **Step C2.1** — Append the 4 new methods + `NamedBlob` inner class + multipart helpers.
- [ ] **Step C2.2** — Run `mvn compile -q`. Expected: clean.

### Task C3 — Add `validateDcoForm` to `ImsReviewService`

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java`

Add a new section before the existing `respondViaToken` method:

```java
    // ------------------------------------------------------------------
    // DCO-form pre-validate (Phase 5)
    // ------------------------------------------------------------------

    /** Validates a DCO form payload without burning the token or writing to
     *  Agile. Returns the plm-agile-service response body verbatim — the
     *  toolkit JS displays the field/form errors inline. */
    public Map<String, Object> validateDcoForm(String token, Map<String, Object> form) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            out.put("ok", false);
            out.put("formErrors", java.util.Collections.singletonList("Missing token"));
            return out;
        }
        TokenContext ctx = describeToken(token);
        if (!ctx.valid) {
            out.put("ok", false);
            out.put("formErrors", java.util.Collections.singletonList(
                    ctx.errorReason == null ? "Invalid token" : ctx.errorReason));
            return out;
        }
        // Stamp docNumber from token context (defense — don't trust caller)
        Map<String, Object> stamped = new java.util.LinkedHashMap<>(form == null ? java.util.Collections.emptyMap() : form);
        stamped.put("docNumber", ctx.docNumber);

        AgileWriteBackClient.Result r = agileWriteBack.validateForm(stamped, null);
        activityLogger.log("(token-pre-validate)", "(token-pre-validate)",
                "IMS_REVIEW_DCO_VALIDATE",
                "tokenId=" + tokenLabel(token) + " | ok=" + r.ok
              + " | http=" + r.httpStatus
              + " | corrId=" + r.corrId);
        if (r.body != null) return r.body;
        out.put("ok", false);
        out.put("formErrors", java.util.Collections.singletonList(
                "Validation service unreachable: " + r.errorReason));
        return out;
    }

    /** Shortened token for log lines (don't dump the full UUID — it's secret-ish). */
    private static String tokenLabel(String token) {
        if (token == null || token.length() < 8) return "(short)";
        return token.substring(0, 8) + "…";
    }
```

- [ ] **Step C3.1** — Add the `validateDcoForm` method + `tokenLabel` helper.
- [ ] **Step C3.2** — Run `mvn compile -q`. Expected: clean.

### Task C4 — Add `respondViaTokenWithDcoForm` orchestrator

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewService.java`

This is the big orchestrator method. Place it after `respondViaToken`:

```java
    @org.springframework.beans.factory.annotation.Value("${app.ims-review.dco-form-enabled:false}")
    private boolean dcoFormEnabled;

    /** Result carrier for the DCO-form submit flow. */
    public static final class DcoSubmitResult {
        public boolean success;
        public String errorReason;
        public String dcoNumber;
        public String newRev;
        public int attachmentsAttached;
        public int approvers;
        public int observers;
        public int documentOwners;
        public int stakeholdersNotified;
        public String signedBy;
        public String signedEmail;
        public String pdfPath;
        public String pdfSha256;
        public String corrId;
        public Map<String, Object> fieldErrors;
    }

    /** Full submit path: LDAP-verify → re-validate → token-burn → PDF →
     *  create-dco-rich → stakeholder notify → DCC closure. Mirrors
     *  {@link #respondViaToken} for the UPLOAD action but with the rich form
     *  payload + multi-file attachments + new Agile cascade. */
    public synchronized DcoSubmitResult respondViaTokenWithDcoForm(
            String token, String username, String password,
            String formJson,
            org.springframework.web.multipart.MultipartFile fileRedline,
            org.springframework.web.multipart.MultipartFile fileFinal,
            java.util.List<org.springframework.web.multipart.MultipartFile> filesOthers,
            String clientIp) {

        DcoSubmitResult res = new DcoSubmitResult();

        if (!dcoFormEnabled) {
            res.errorReason = "DCO form is disabled (app.ims-review.dco-form-enabled=false).";
            return res;
        }
        if (token == null || token.isEmpty()) {
            res.errorReason = "Missing token.";
            return res;
        }

        // 1. Describe token
        TokenContext ctx = describeToken(token);
        if (!ctx.valid) {
            res.errorReason = ctx.errorReason == null ? "Invalid token" : ctx.errorReason;
            return res;
        }
        if (!"DO".equals(ctx.role)) {
            res.errorReason = "DCO form is only available for the DO Needs Change action.";
            return res;
        }

        // 2. LDAP verify
        LdapAuthService.VerifyResult v = ldapAuthService.verifyCredentials(username, password);
        if (!v.success) {
            recordTokenFail(token);
            res.errorReason = v.message;
            return res;
        }
        String verifiedEmail = v.email == null ? "" : v.email.trim().toLowerCase();
        if (!verifiedEmail.equals(ctx.allowedActor)) {
            boolean dmDlCase = ctx.allowedActor != null
                    && ctx.allowedActor.equalsIgnoreCase(ImsReviewEmailService.DM_DL_CC);
            if (!dmDlCase) {
                recordTokenFail(token);
                res.errorReason = "This link was sent to " + ctx.allowedActor + " — sign in as that person.";
                return res;
            }
        }

        // 3. Parse form payload
        Map<String, Object> form;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = om.readValue(formJson, Map.class);
            form = parsed;
            form.put("docNumber", ctx.docNumber);  // stamp from token
        } catch (Exception parseErr) {
            res.errorReason = "Bad form JSON: " + parseErr.getMessage();
            return res;
        }

        // 4. Re-validate (defense in depth — caller may have mutated since pre-validate)
        AgileWriteBackClient.Result vr = agileWriteBack.validateForm(form, null);
        if (!vr.ok) {
            res.errorReason = "Form failed re-validation. Please reload and retry.";
            if (vr.body != null && vr.body.get("fieldErrors") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fe = (Map<String, Object>) vr.body.get("fieldErrors");
                res.fieldErrors = fe;
            }
            return res;
        }

        DocRow d = lookupDoc(ctx.docNumber);

        // 5. Append DO_RESPONSE_NEEDS_CHANGE event (TOKEN BURN)
        ImsReviewQueueStore.Event ev = baseEvent(
                ImsReviewQueueStore.EventType.DO_RESPONSE_NEEDS_CHANGE,
                ctx.docNumber, ctx.drrNumber,
                verifiedEmail, v.displayName, "DO");
        ev.recipients = java.util.Collections.singletonList(ImsReviewEmailService.ADMIN_CC);
        ev.redeemsToken = token;
        ev.verifiedSamAccount = v.samAccount;
        ev.verifiedDisplayName = v.displayName;
        ev.verifiedEmail = v.email;
        ev.verifyIp = clientIp;
        ev.dcoForm = form;
        ev.dcoFormChecksum = sha256(canonicalize(form));
        // Attachments manifest (filename + type + size — bytes go to Agile, not to disk here)
        java.util.List<java.util.Map<String, Object>> manifest = new java.util.ArrayList<>();
        addManifestEntry(manifest, "Redline", fileRedline);
        addManifestEntry(manifest, "Final", fileFinal);
        if (filesOthers != null) for (org.springframework.web.multipart.MultipartFile o : filesOthers)
            addManifestEntry(manifest, "Others", o);
        ev.dcoAttachmentsManifest = manifest;
        ev.note = "Rich DCO form submission";
        ev.fileCount = manifest.size();
        queueStore.append(ev);

        activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_FORM_SUBMIT",
                "tokenId=" + tokenLabel(token)
              + " | doc=" + ctx.docNumber
              + " | drr=" + nvl(ctx.drrNumber)
              + " | dcoFormChecksum=" + ev.dcoFormChecksum.substring(0, 16) + "…"
              + " | attachmentCount=" + manifest.size());

        // 6. Generate compliance PDF (existing PDF service, with new DCO section)
        try {
            ImsReviewPdfService.ApprovalInput in = new ImsReviewPdfService.ApprovalInput();
            in.docNumber = ctx.docNumber;
            in.description = ctx.description;
            in.rev = ctx.rev;
            in.lifecyclePhase = ctx.lifecyclePhase;
            in.documentType = ctx.documentType;
            in.nextReviewDate = ctx.nextReviewDate;
            in.drrNumber = ctx.drrNumber;
            in.role = "DO";
            in.actionLabel = "Needs Change — DCO Submitted via Toolkit";
            in.signerDisplayName = v.displayName;
            in.signerEmail = v.email;
            in.signerSamAccount = v.samAccount;
            in.signedAtUtc = ev.ts;
            in.signerIp = clientIp;
            in.sendEventUuid = token;
            in.responseEventTs = ev.ts;
            in.dcoForm = form;                 // NEW — rendered by ImsReviewPdfService
            in.dcoFormChecksum = ev.dcoFormChecksum;
            ImsReviewPdfService.GeneratedPdf pdf = pdfService.generate(in);
            ev.pdfPath = pdf.relPath;
            ev.pdfSha256 = pdf.sha256;
            res.pdfPath = pdf.relPath;
            res.pdfSha256 = pdf.sha256;

            // Append PDF_GENERATED audit event
            ImsReviewQueueStore.Event pdfEv = baseEvent(
                    ImsReviewQueueStore.EventType.PDF_GENERATED,
                    ctx.docNumber, ctx.drrNumber, verifiedEmail, "PLM Toolkit", "DO");
            pdfEv.redeemsToken = token;
            pdfEv.pdfPath = pdf.relPath;
            pdfEv.pdfSha256 = pdf.sha256;
            pdfEv.verifiedSamAccount = v.samAccount;
            pdfEv.verifiedDisplayName = v.displayName;
            pdfEv.verifiedEmail = v.email;
            queueStore.append(pdfEv);
        } catch (Exception pdfErr) {
            LOG.warning("[IMS-REVIEW] PDF generation failed: " + pdfErr.getMessage());
            // Non-fatal — proceed to Agile write.
        }

        // 7. Call create-dco-rich
        java.util.List<AgileWriteBackClient.NamedBlob> blobs = new java.util.ArrayList<>();
        addBlob(blobs, "file_redline", fileRedline);
        addBlob(blobs, "file_final", fileFinal);
        if (filesOthers != null) for (org.springframework.web.multipart.MultipartFile o : filesOthers)
            addBlob(blobs, "file_others", o);

        AgileWriteBackClient.Result cr = agileWriteBack.createDcoRich(
                ctx.drrNumber, formJson,
                d == null ? "" : nvl(d.rev),
                blobs, null);

        ev.agileCorrId = cr.corrId;
        res.corrId = cr.corrId;

        if (!cr.ok || cr.body == null) {
            res.errorReason = "DCO creation failed: " + nvl(cr.errorReason);
            ev.agileError = res.errorReason;
            ev.agileErrorAt = cr.body == null ? "(transport)" : String.valueOf(cr.body.get("stepFailedAt"));
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_FAILED",
                    "tokenId=" + tokenLabel(token)
                  + " | drr=" + nvl(ctx.drrNumber)
                  + " | corrId=" + cr.corrId
                  + " | agileErrorAt=" + ev.agileErrorAt
                  + " | agileError=" + ev.agileError);
            // Append AGILE_WRITEBACK audit event with the failure
            appendDcoAgileAudit(ev, ctx);
            // Also fire DCC alert email (best-effort)
            try {
                sendDcoFailedAlert(ctx, v, cr);
            } catch (Exception alertErr) {
                LOG.warning("[IMS-REVIEW] DCC alert send failed: " + alertErr.getMessage());
            }
            return res;
        }

        // Success path — stamp DCO# and counts
        ev.agileDco = (String) cr.body.get("dcoNumber");
        res.dcoNumber = ev.agileDco;
        res.newRev = (String) cr.body.get("newRev");
        Object aa = cr.body.get("attachmentsAttached");
        Object ap = cr.body.get("approvers");
        Object ob = cr.body.get("observers");
        Object dow = cr.body.get("documentOwners");
        res.attachmentsAttached = aa instanceof Number ? ((Number) aa).intValue() : 0;
        res.approvers = ap instanceof Number ? ((Number) ap).intValue() : 0;
        res.observers = ob instanceof Number ? ((Number) ob).intValue() : 0;
        res.documentOwners = dow instanceof Number ? ((Number) dow).intValue() : 0;

        @SuppressWarnings("unchecked")
        java.util.List<String> stepsOk = (java.util.List<String>) cr.body.get("stepsOk");
        ev.agileSteps = stepsOk;

        activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_DCO_CREATED",
                "tokenId=" + tokenLabel(token)
              + " | corrId=" + cr.corrId
              + " | dcoNumber=" + res.dcoNumber
              + " | drr=" + nvl(ctx.drrNumber)
              + " | newRev=" + nvl(res.newRev)
              + " | attachments=" + res.attachmentsAttached);

        appendDcoAgileAudit(ev, ctx);

        // 8. Stakeholder notification SMTP (best-effort)
        try {
            int notified = sendStakeholderNotification(ctx, v, form, res.dcoNumber, res.newRev,
                    res.attachmentsAttached, ev.pdfPath);
            res.stakeholdersNotified = notified;
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_STAKEHOLDER_NOTIFY",
                    "tokenId=" + tokenLabel(token)
                  + " | dco=" + res.dcoNumber
                  + " | recipients=" + notified
                  + " | ok=true");
        } catch (Exception notifyErr) {
            LOG.warning("[IMS-REVIEW] stakeholder notify failed: " + notifyErr.getMessage());
            activityLogger.log(verifiedEmail, v.displayName, "IMS_REVIEW_STAKEHOLDER_NOTIFY_FAILED",
                    "tokenId=" + tokenLabel(token)
                  + " | dco=" + res.dcoNumber
                  + " | err=" + notifyErr.getMessage());
            // Send DCC alert about the notify failure
            try { sendStakeholderNotifyFailedAlert(ctx, res.dcoNumber, form, notifyErr); }
            catch (Exception ignored) {}
        }

        res.success = true;
        res.signedBy = v.displayName;
        res.signedEmail = v.email;
        return res;
    }

    // ------------------------------------------------------------------
    // helpers for the DCO-form path
    // ------------------------------------------------------------------
    private static void addManifestEntry(java.util.List<java.util.Map<String, Object>> manifest,
                                         String type,
                                         org.springframework.web.multipart.MultipartFile f) {
        if (f == null || f.isEmpty()) return;
        java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("type", type);
        e.put("filename", f.getOriginalFilename());
        e.put("sizeBytes", f.getSize());
        manifest.add(e);
    }

    private static void addBlob(java.util.List<AgileWriteBackClient.NamedBlob> blobs,
                                String partName,
                                org.springframework.web.multipart.MultipartFile f) {
        if (f == null || f.isEmpty()) return;
        try {
            blobs.add(new AgileWriteBackClient.NamedBlob(partName, f.getOriginalFilename(), f.getBytes()));
        } catch (Exception ignored) {}
    }

    private static String canonicalize(Map<String, Object> form) {
        // Stable JSON serialization for checksumming — keys sorted, no whitespace.
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(form);
            return om.writeValueAsString(sorted);
        } catch (Exception e) {
            return String.valueOf(form);
        }
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "(hash-failed)";
        }
    }

    /** Append an AGILE_WRITEBACK audit event for the DCO-form cascade. */
    private void appendDcoAgileAudit(ImsReviewQueueStore.Event src, TokenContext ctx) {
        ImsReviewQueueStore.Event audit = baseEvent(
                ImsReviewQueueStore.EventType.AGILE_WRITEBACK,
                ctx.docNumber, ctx.drrNumber,
                src.actor == null ? null : src.actor.email,
                src.actor == null ? null : src.actor.displayName,
                src.actor == null ? null : src.actor.role);
        audit.agileCorrId = src.agileCorrId;
        audit.agileDrr = src.agileDrr;
        audit.agileDco = src.agileDco;
        audit.agileSteps = src.agileSteps == null ? null : new java.util.ArrayList<>(src.agileSteps);
        audit.agileError = src.agileError;
        audit.agileErrorAt = src.agileErrorAt;
        audit.redeemsToken = src.redeemsToken;
        queueStore.append(audit);
    }
```

The `sendStakeholderNotification(...)` and alert-email helpers are added in Task C7 (the email service work). Their signatures referenced here:

```java
    private int sendStakeholderNotification(TokenContext ctx, LdapAuthService.VerifyResult v,
                                            Map<String, Object> form, String dcoNumber, String newRev,
                                            int attachmentsCount, String pdfRelPath) throws Exception {
        return emailService.sendStakeholderNotify(ctx, v, form, dcoNumber, newRev, attachmentsCount,
                pdfRelPath == null ? null : pdfService.readPdf(pdfRelPath));
    }

    private void sendDcoFailedAlert(TokenContext ctx, LdapAuthService.VerifyResult v,
                                    AgileWriteBackClient.Result cr) {
        emailService.sendDcoFailedAlert(ctx, v, cr);
    }

    private void sendStakeholderNotifyFailedAlert(TokenContext ctx, String dcoNumber,
                                                  Map<String, Object> form, Exception err) {
        emailService.sendStakeholderNotifyFailedAlert(ctx, dcoNumber, form, err);
    }
```

- [ ] **Step C4.1** — Add the `dcoFormEnabled` `@Value`, `DcoSubmitResult` class, `respondViaTokenWithDcoForm` method, and helpers per above.
- [ ] **Step C4.2** — Run `mvn compile -q`. Expected: compilation fails because of the email helper signatures (`emailService.sendStakeholderNotify`, etc.) — these are stubbed in C7. Continue regardless; Task C7 closes the gap.

### Task C5 — Add 4 new endpoints to `ImsReviewController`

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/controller/ImsReviewController.java`

Append these endpoints after the existing `tokenSubmit` method:

```java
    // ------------------------------------------------------------------
    // DCO-form endpoints (Phase 5)
    // ------------------------------------------------------------------

    /** Pass-through to plm-agile-service's list-values endpoint. Fetches the
     *  6 admin-list dropdowns the DCO form needs. Token-based — no session. */
    @GetMapping("/dco-form-metadata")
    public ResponseEntity<?> dcoFormMetadata(@RequestParam("token") String token) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err(c.errorReason == null ? "Invalid token" : c.errorReason));
        }
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r =
                writeBackClient.listValues(null);
        if (r.body == null) {
            return ResponseEntity.status(503).body(err("Form metadata service unreachable: " + r.errorReason));
        }
        return ResponseEntity.ok(r.body);
    }

    /** Typeahead for user pickers. Token-required. */
    @GetMapping("/user-search")
    public ResponseEntity<?> userSearch(@RequestParam("token") String token,
                                        @RequestParam("q") String q,
                                        @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        ImsReviewService.TokenContext c = service.describeToken(token);
        if (!c.valid && !c.alreadyRedeemed) {
            return ResponseEntity.status(401).body(err("Invalid token"));
        }
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        com.sandisk.plm.tracker.service.AgileWriteBackClient.Result r =
                writeBackClient.searchUsers(q, limit, null);
        if (r.body == null) {
            return ResponseEntity.status(503).body(err("User search service unreachable: " + r.errorReason));
        }
        return ResponseEntity.ok(r.body);
    }

    /** Pre-validate the DCO form. Does NOT burn the token. */
    @PostMapping(value = "/token/validate-dco-form", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateDcoForm(@RequestBody Map<String, Object> body) {
        String token = body == null ? null : (String) body.get("token");
        @SuppressWarnings("unchecked")
        Map<String, Object> form = body == null ? null : (Map<String, Object>) body.get("form");
        Map<String, Object> result = service.validateDcoForm(token, form);
        return ResponseEntity.ok(result);
    }

    /** Submit the DCO form. Burns the token + runs the full Agile cascade. */
    @PostMapping(value = "/token/submit-dco", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitDco(javax.servlet.http.HttpServletRequest req,
                                       @RequestParam("token") String token,
                                       @RequestParam("username") String username,
                                       @RequestParam("password") String password,
                                       @RequestParam("form") String formJson,
                                       @RequestParam(value = "file_redline", required = false) MultipartFile fileRedline,
                                       @RequestParam(value = "file_final",   required = false) MultipartFile fileFinal,
                                       @RequestParam(value = "file_others",  required = false) MultipartFile[] filesOthers) {
        String ip = clientIp(req);
        ImsReviewService.DcoSubmitResult r = service.respondViaTokenWithDcoForm(
                token, username, password, formJson,
                fileRedline, fileFinal,
                filesOthers == null ? null : java.util.Arrays.asList(filesOthers),
                ip);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("success", r.success);
        if (!r.success) {
            out.put("error", r.errorReason);
            if (r.fieldErrors != null) out.put("fieldErrors", r.fieldErrors);
            if (r.corrId != null) out.put("corrId", r.corrId);
            int code = r.errorReason != null && r.errorReason.toLowerCase().contains("password") ? 401 : 400;
            if (r.corrId != null && r.errorReason != null && r.errorReason.startsWith("DCO creation failed")) {
                code = 500;
            }
            return ResponseEntity.status(code).body(out);
        }
        out.put("dcoNumber", r.dcoNumber);
        out.put("newRev", r.newRev);
        out.put("attachmentsCount", r.attachmentsAttached);
        out.put("stakeholdersNotified", r.stakeholdersNotified);
        out.put("signedBy", r.signedBy);
        out.put("signedEmail", r.signedEmail);
        out.put("pdfPath", r.pdfPath);
        out.put("corrId", r.corrId);
        return ResponseEntity.ok(out);
    }
```

Plus the new field at the top of the class:

```java
    @Autowired private com.sandisk.plm.tracker.service.AgileWriteBackClient writeBackClient;
```

- [ ] **Step C5.1** — Add the 4 endpoints + the `writeBackClient` field.
- [ ] **Step C5.2** — Run `mvn compile -q`. Still expected to fail until C7 closes email-helper gap.

### Task C6 — Extend `ImsReviewPdfService` with DCO request section

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewPdfService.java`

Add to `ApprovalInput`:

```java
        /** Phase-5 rich-form payload; rendered as the "Submitted DCO Request"
         *  section when present. Null for non-DCO-form actions. */
        public java.util.Map<String, Object> dcoForm;
        public String dcoFormChecksum;
```

In the `generate(ApprovalInput in)` method, after the existing content is written but before the provenance footer, add a conditional section:

```java
        // -- Submitted DCO request section (Phase 5) --
        if (in.dcoForm != null && !in.dcoForm.isEmpty()) {
            content.append("\n=== Submitted DCO Request ===\n");
            appendKv(content, "Priority", in.dcoForm.get("priority"));
            appendKv(content, "Description of Change", in.dcoForm.get("descriptionOfChange"));
            appendKv(content, "Reason for Change", in.dcoForm.get("reasonForChange"));
            appendKv(content, "Product Lines", in.dcoForm.get("productLines"));
            appendKv(content, "Subcontractors", in.dcoForm.get("subcontractors"));
            appendKv(content, "Training Requirement", in.dcoForm.get("trainingRequirement"));
            appendKv(content, "Business Unit", in.dcoForm.get("businessUnit"));
            appendKv(content, "Change Impact Disposition", in.dcoForm.get("changeImpactDisposition"));
            appendKv(content, "Change Impact Details", in.dcoForm.get("changeImpactDetails"));
            appendKv(content, "Document Owner(s)", in.dcoForm.get("documentOwners"));
            appendKv(content, "Approvers", in.dcoForm.get("approvers"));
            appendKv(content, "Observers", in.dcoForm.get("observers"));
            appendKv(content, "Notify Stakeholders", in.dcoForm.get("notifyStakeholders"));
            if (in.dcoFormChecksum != null) {
                content.append("\nForm checksum (SHA-256): ").append(in.dcoFormChecksum).append("\n");
            }
        }
```

Add helper:

```java
    private static void appendKv(StringBuilder content, String label, Object value) {
        if (value == null) return;
        String s;
        if (value instanceof java.util.List) {
            s = String.join(", ", ((java.util.List<?>) value).stream().map(String::valueOf).toArray(String[]::new));
        } else {
            s = String.valueOf(value);
        }
        if (s.isEmpty()) return;
        content.append(label).append(": ").append(s).append("\n");
    }
```

**Note**: The existing PDF generator's exact content-building style needs to match — the snippet above assumes a `StringBuilder content` pattern but the actual file may use iText or similar. Adapt to whatever pattern `ImsReviewPdfService` already uses (peek at the existing `generate(...)` to see whether it's PDFBox/iText/text-only).

- [ ] **Step C6.1** — Add the two `ApprovalInput` fields.
- [ ] **Step C6.2** — Read existing `generate(...)` to understand the content-rendering pattern.
- [ ] **Step C6.3** — Insert the DCO request section matching that pattern.
- [ ] **Step C6.4** — Add the `appendKv` helper if appropriate (or inline the formatting if the existing code uses a different style).

### Task C7 — Add stakeholder notify methods to `ImsReviewEmailService` + template

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/java/com/sandisk/plm/tracker/service/ImsReviewEmailService.java`
- Create: `~/git/plm-field-tracker/src/main/resources/templates/email/ims-review-dco-stakeholder-notify.html`

Add these public methods to `ImsReviewEmailService` (referenced by C4):

```java
    /** Send the Phase-5 stakeholder notification email. Recipients =
     *  (DocumentOwners ∪ Approvers ∪ Observers ∪ NotifyStakeholders) \ {submitter},
     *  Cc = IMS-Doc-Managers-Agile. Returns the count of unique To recipients. */
    public int sendStakeholderNotify(ImsReviewService.TokenContext ctx,
                                     LdapAuthService.VerifyResult v,
                                     java.util.Map<String, Object> form,
                                     String dcoNumber, String newRev,
                                     int attachmentsCount,
                                     byte[] attestationPdfBytes) throws Exception {
        Payload p = new Payload();
        p.templateName = "ims-review-dco-stakeholder-notify";

        // Recipients
        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        addAllLower(to, form.get("documentOwners"));
        addAllLower(to, form.get("approvers"));
        addAllLower(to, form.get("observers"));
        addAllLower(to, form.get("notifyStakeholders"));
        String submitter = v == null || v.email == null ? "" : v.email.trim().toLowerCase();
        to.remove(submitter);
        p.to = new java.util.ArrayList<>(to);
        p.cc.add(DM_DL_CC);

        String prio = String.valueOf(form.getOrDefault("priority", "Standard"));
        p.subject = "[New DCO] " + dcoNumber + " · " + ctx.docNumber + " · " + prio;

        java.util.Map<String, String> vars = p.vars;
        vars.put("dcoNumber", nvl(dcoNumber));
        vars.put("docNumber", nvl(ctx.docNumber));
        vars.put("docDescription", nvl(ctx.description));
        vars.put("drrNumber", nvl(ctx.drrNumber));
        vars.put("newRev", nvl(newRev));
        vars.put("priority", prio);
        vars.put("descriptionOfChange", String.valueOf(form.getOrDefault("descriptionOfChange", "")));
        vars.put("reasonForChange", String.valueOf(form.getOrDefault("reasonForChange", "")));
        vars.put("productLines", joinList(form.get("productLines")));
        vars.put("subcontractors", joinList(form.get("subcontractors")));
        vars.put("trainingRequirement", String.valueOf(form.getOrDefault("trainingRequirement", "")));
        vars.put("businessUnit", String.valueOf(form.getOrDefault("businessUnit", "")));
        vars.put("changeImpactDisposition", String.valueOf(form.getOrDefault("changeImpactDisposition", "")));
        vars.put("changeImpactDetails", String.valueOf(form.getOrDefault("changeImpactDetails", "")));
        vars.put("documentOwners", joinList(form.get("documentOwners")));
        vars.put("approvers", joinList(form.get("approvers")));
        vars.put("observers", joinList(form.get("observers")));
        vars.put("notifyStakeholders", joinList(form.get("notifyStakeholders")));
        vars.put("attachmentsCount", String.valueOf(attachmentsCount));
        vars.put("signedBy", nvl(v == null ? null : v.displayName));
        vars.put("signedEmail", nvl(v == null ? null : v.email));
        vars.put("submittedAt", java.time.Instant.now().toString());

        if (attestationPdfBytes != null && attestationPdfBytes.length > 0) {
            p.extraAttachments.add(new NamedBlob(
                    "ims-attestation-" + dcoNumber + ".pdf",
                    attestationPdfBytes, "application/pdf"));
        }
        send(p);
        return to.size();
    }

    /** DCC alert for create-dco-rich failure. Tiny, no template — inline HTML. */
    public void sendDcoFailedAlert(ImsReviewService.TokenContext ctx,
                                   LdapAuthService.VerifyResult v,
                                   AgileWriteBackClient.Result cr) {
        try {
            String body = "<html><body style=\"font-family:'IBM Plex Sans',sans-serif; color:#0F1720;\">"
                + "<h2 style=\"font-family:'IBM Plex Serif',serif;color:#B8342B;\">IMS Review — DCO creation failed</h2>"
                + "<p>The toolkit could not create the DCO for the DO Needs-Change submission.</p>"
                + "<table cellpadding=\"6\" style=\"border-collapse:collapse;\">"
                + tr("Doc", ctx.docNumber)
                + tr("DRR", ctx.drrNumber)
                + tr("DO", v.displayName + " &lt;" + v.email + "&gt;")
                + tr("Correlation ID", cr.corrId)
                + tr("Failed at step", cr.body == null ? "(transport)" : String.valueOf(cr.body.get("stepFailedAt")))
                + tr("Error", cr.errorReason == null ? "(none)" : cr.errorReason)
                + "</table>"
                + "<p style=\"color:#6B7280;font-size:11px;\">Search the plm-agile-service log for corrId="
                + cr.corrId + " to see every step that ran. The DO's signed PDF is recorded in queue.jsonl.</p>"
                + "</body></html>";
            sendAlert("[IMS Review] DCO creation FAILED — " + ctx.docNumber, body);
        } catch (Exception ignored) {}
    }

    public void sendStakeholderNotifyFailedAlert(ImsReviewService.TokenContext ctx,
                                                 String dcoNumber,
                                                 java.util.Map<String, Object> form,
                                                 Exception err) {
        try {
            String body = "<html><body style=\"font-family:'IBM Plex Sans',sans-serif;\">"
                + "<h2 style=\"color:#C7801B;\">Stakeholder notification failed</h2>"
                + "<p>DCO <strong>" + nvl(dcoNumber) + "</strong> was created OK in Agile, but the toolkit "
                + "couldn't deliver the stakeholder notification email.</p>"
                + "<p>Recipients that should have received it:</p>"
                + "<pre>" + esc(joinList(form.get("notifyStakeholders"))) + "</pre>"
                + "<p>Error: <code>" + esc(err.getMessage()) + "</code></p>"
                + "</body></html>";
            sendAlert("[IMS Review] Stakeholder notify failed — " + nvl(dcoNumber), body);
        } catch (Exception ignored) {}
    }

    private void sendAlert(String subject, String htmlBody) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        javax.mail.Session session = javax.mail.Session.getInstance(props);
        javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(session);
        msg.setFrom(new javax.mail.internet.InternetAddress(FROM_ADDR));
        msg.setSubject(subject + (isRedirected() ? " [LOCAL TEST]" : ""), "UTF-8");
        String to = isRedirected() ? redirectTo : ADMIN_CC;
        msg.addRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress(to));
        msg.setContent(htmlBody, "text/html; charset=UTF-8");
        javax.mail.Transport.send(msg);
    }

    private boolean isRedirected() {
        return redirectTo != null && !redirectTo.trim().isEmpty();
    }

    private static void addAllLower(java.util.Set<String> out, Object listObj) {
        if (!(listObj instanceof java.util.List)) return;
        for (Object o : (java.util.List<?>) listObj) {
            if (o == null) continue;
            String s = o.toString().trim().toLowerCase();
            if (!s.isEmpty()) out.add(s);
        }
    }

    private static String joinList(Object listObj) {
        if (!(listObj instanceof java.util.List)) return "";
        java.util.List<?> list = (java.util.List<?>) listObj;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i) == null ? "" : list.get(i).toString());
        }
        return sb.toString();
    }

    private static String tr(String k, String v) {
        return "<tr><td style=\"color:#6B7280;font-weight:600;\">" + esc(k) + "</td><td>" + esc(v) + "</td></tr>";
    }
```

#### `ims-review-dco-stakeholder-notify.html`

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="color-scheme" content="light dark">
<title>IMS Review — New DCO Submitted</title>
</head>
<body class="email-body" style="margin:0; padding:0; background:#FAFAF7; font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif; color:#0F1720;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0" align="center" width="600" style="max-width:600px; margin:24px auto; background:#ffffff; border:1px solid #E8E6DF; border-radius:8px;">
  <tr><td style="padding:14px 20px; font-size:11px; color:#6B7280; border-bottom:1px solid #E8E6DF;">
    Agile PLM &middot; IMS Review &middot; New DCO Submitted
    <span style="float:right; background:#d4edda; color:#155724; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600;">DCO Submitted</span>
  </td></tr>
  <tr><td style="padding:18px 20px 6px;">
    <div style="text-transform:uppercase; letter-spacing:0.6px; color:#6B7280; font-size:11px; font-weight:600;">IMS REVIEW &middot; DOC OWNER ACTION</div>
    <h1 style="font-family:'IBM Plex Serif',Georgia,serif; font-size:22px; font-weight:500; margin:6px 0 4px;">A new DCO was submitted for review</h1>
    <p style="color:#6B7280; margin:0 0 16px; font-size:13px;">Document Owner ${signedBy} submitted ${dcoNumber} via PLM Toolkit for IMS Document ${docNumber}. Doc Control will drive it through the Agile workflow.</p>
  </td></tr>
  <tr><td style="padding:0 20px 12px;">
    <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%">
      <tr>
        <td width="25%" style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px; text-align:center;">
          <div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">DCO</div>
          <div style="font-size:14px; font-weight:600; color:#4a6fa5; font-family:'IBM Plex Mono',Consolas,monospace;">${dcoNumber}</div>
        </td>
        <td width="25%" style="padding:0 4px;"></td>
        <td width="25%" style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px; text-align:center;">
          <div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">Doc</div>
          <div style="font-size:14px; font-weight:600; color:#4a6fa5; font-family:'IBM Plex Mono',Consolas,monospace;">${docNumber}</div>
        </td>
        <td width="25%" style="background:#FAFAF7; border:1px solid #E8E6DF; border-radius:6px; padding:10px; text-align:center;">
          <div style="font-size:11px; text-transform:uppercase; letter-spacing:0.5px; color:#6B7280;">New Rev</div>
          <div style="font-size:18px; font-weight:600; color:#0F1720;">${newRev}</div>
        </td>
      </tr>
    </table>
  </td></tr>

  <tr><td style="padding:14px 20px 0;">
    <div style="font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">What changed</div>
    <p style="margin:8px 0;"><strong>Priority:</strong> ${priority}</p>
    <p style="margin:8px 0;"><strong>Description:</strong> ${descriptionOfChange}</p>
    <p style="margin:8px 0;"><strong>Reason:</strong> ${reasonForChange}</p>
  </td></tr>

  <tr><td style="padding:14px 20px 0;">
    <div style="font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">Product impact</div>
    <p style="margin:8px 0;"><strong>Product Lines:</strong> ${productLines}</p>
    <p style="margin:8px 0;"><strong>Subcontractors:</strong> ${subcontractors}</p>
    <p style="margin:8px 0;"><strong>Training Requirement:</strong> ${trainingRequirement}</p>
    <p style="margin:8px 0;"><strong>Business Unit:</strong> ${businessUnit}</p>
    <p style="margin:8px 0;"><strong>Change Impact Disposition:</strong> ${changeImpactDisposition}</p>
    <p style="margin:8px 0;"><strong>Change Impact Details:</strong> ${changeImpactDetails}</p>
  </td></tr>

  <tr><td style="padding:14px 20px 0;">
    <div style="font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">Who's involved</div>
    <p style="margin:8px 0;"><strong>Document Owners:</strong> <span style="color:#4a6fa5;">${documentOwners}</span></p>
    <p style="margin:8px 0;"><strong>Approvers:</strong> <span style="color:#4a6fa5;">${approvers}</span></p>
    <p style="margin:8px 0;"><strong>Observers:</strong> <span style="color:#4a6fa5;">${observers}</span></p>
    <p style="margin:8px 0;"><strong>Additional stakeholders:</strong> ${notifyStakeholders}</p>
  </td></tr>

  <tr><td style="padding:14px 20px 0;">
    <div style="font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">Attachments</div>
    <p style="margin:8px 0;">${attachmentsCount} file(s) attached to the DCO in Agile. The signed attestation PDF is included with this email.</p>
  </td></tr>

  <tr><td style="padding:14px 20px 0;">
    <div style="font-size:12px; color:#6B7280; text-transform:uppercase; letter-spacing:0.5px; border-bottom:1px solid #E8E6DF; padding-bottom:4px;">Signed by</div>
    <p style="margin:8px 0;"><strong style="color:#4a6fa5;">${signedBy}</strong> &lt;${signedEmail}&gt; at ${submittedAt}</p>
  </td></tr>

  <tr><td style="padding:14px 20px; font-family:'IBM Plex Mono',Consolas,monospace; font-size:11px; color:#6B7280; border-top:1px solid #E8E6DF;">${submittedAt}</td></tr>
  <tr><td style="padding:14px 20px; background:#FAFAF7; border-top:1px solid #E8E6DF; text-align:center;">
    <div style="display:inline-block; padding:2px 10px; border:1px solid #ececec; border-radius:20px; font-size:11px; color:#6B7280;">sandisk</div>
    <div style="font-size:11px; color:#6B7280; margin-top:6px;">PLM Toolkit &middot; IMS Review</div>
    <div style="font-size:11px; color:#6B7280;">This is an automated notification. Please do not reply.</div>
  </td></tr>
</table>
</body>
</html>
```

- [ ] **Step C7.1** — Add the 3 public methods + helpers to `ImsReviewEmailService`.
- [ ] **Step C7.2** — Create the new template file.
- [ ] **Step C7.3** — Run `mvn compile -q`. Expected: clean (closes the C4 gap).

---

## Phase D — plm-field-tracker: frontend

### Task D1 — Drawer markup + CSS in `ims-respond.html`

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/ims-respond.html`

Two changes:
1. Add drawer markup + CSS for it (inside `<style>` and `<body>`)
2. Wire `onPickAction('UPLOAD')` to open the drawer (instead of showing inline `followupUpload`) when `app.ims-review.dco-form-enabled=true`

For brevity in this plan, the drawer markup + JS go to a separate file `imsreview-dco-form.js` and `<style>` additions are kept inline.

**Add to `<style>`:**

```css
/* ----- DCO Form Drawer (Phase 5) ----- */
.dco-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.35);
  display: none; z-index: 9000;
}
.dco-backdrop.show { display: block; }
.dco-drawer {
  position: fixed; top: 0; right: 0; bottom: 0;
  width: 560px; max-width: 100vw;
  background: var(--card); border-left: 1px solid var(--border);
  box-shadow: -4px 0 16px rgba(0,0,0,0.08);
  transform: translateX(100%); transition: transform 0.2s ease-out;
  z-index: 9001; display: flex; flex-direction: column;
}
.dco-drawer.show { transform: translateX(0); }
.dco-drawer-hd {
  padding: 14px 20px; border-bottom: 1px solid var(--border);
  display: flex; justify-content: space-between; align-items: center;
}
.dco-drawer-hd .x { background: none; border: 0; font-size: 22px; cursor: pointer; color: var(--muted); }
.dco-drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.dco-drawer-ft {
  padding: 14px 20px; border-top: 1px solid var(--border);
  background: #FAFAF7;
}
.dco-section { margin: 18px 0 8px; padding-bottom: 4px; border-bottom: 1px solid var(--border);
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.6px; color: var(--muted); }
.dco-field { display: block; margin: 10px 0; }
.dco-field .lbl { display: block; font-size: 12px; color: var(--muted); margin-bottom: 4px; font-weight: 600; }
.dco-field .lbl.req::after { content: ' *'; color: var(--error); }
.dco-field input, .dco-field textarea, .dco-field select {
  width: 100%; padding: 8px 10px; border: 1px solid var(--border);
  border-radius: 5px; font-size: 13px; font-family: inherit;
}
.dco-field .helper { font-size: 11px; color: var(--muted); margin-top: 2px; }
.dco-field.err input, .dco-field.err textarea, .dco-field.err select { border-color: var(--error); }
.dco-field.err .helper { color: var(--error); }
.dco-disclaimer {
  background: #fff8e1; border-left: 4px solid var(--warn);
  border-radius: 0 6px 6px 0; padding: 10px 14px; margin-bottom: 14px;
  font-size: 12.5px; color: #5a4a1f;
}
.dco-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.dco-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: #eef3fa; border: 1px solid #b4d0fe; border-radius: 12px;
  padding: 2px 8px; font-size: 11px; color: #1a3a5c;
}
.dco-chip .rm { cursor: pointer; color: #1a3a5c; opacity: 0.6; }
.dco-typeahead { position: relative; }
.dco-typeahead-results {
  position: absolute; top: 100%; left: 0; right: 0; z-index: 10;
  background: #fff; border: 1px solid var(--border); border-radius: 5px;
  max-height: 200px; overflow-y: auto; box-shadow: 0 4px 8px rgba(0,0,0,0.06);
  display: none;
}
.dco-typeahead-results.show { display: block; }
.dco-typeahead-results .hit {
  padding: 6px 10px; cursor: pointer; font-size: 12px;
  border-bottom: 1px solid #f0eee8;
}
.dco-typeahead-results .hit:hover { background: #eef3fa; }
.dco-optional-toggle { color: var(--primary); cursor: pointer; font-size: 12px; margin: 8px 0; }
.dco-optional { display: none; }
.dco-optional.show { display: block; }
.dco-att-slot { display: flex; align-items: center; gap: 8px; margin: 6px 0; }
.dco-att-slot .typ { width: 110px; font-size: 11px; color: var(--muted); font-weight: 600; }
.dco-att-files { list-style: none; padding: 0; margin: 4px 0 0; }
.dco-att-files li { display: flex; gap: 8px; align-items: center; font-size: 11px; color: var(--muted); padding: 2px 0; }
.dco-att-files .rm { color: var(--error); cursor: pointer; }
@media (max-width: 768px) {
  .dco-drawer { width: 100vw; }
}
```

**Add to `<body>`** (before `</body>`):

```html
<!-- DCO Form Drawer (Phase 5) -->
<div id="dcoBackdrop" class="dco-backdrop" onclick="dcoMaybeClose()"></div>
<aside id="dcoDrawer" class="dco-drawer" aria-hidden="true">
  <div class="dco-drawer-hd">
    <div>
      <div style="font-size:11px; text-transform:uppercase; letter-spacing:0.6px; color:var(--muted); font-weight:600;">Document Owner Response &middot; Needs Change</div>
      <div style="font-family:'IBM Plex Serif',Georgia,serif; font-size:18px; font-weight:500;">Submit a Document Change Order</div>
    </div>
    <button class="x" onclick="dcoMaybeClose()" aria-label="Close">&times;</button>
  </div>
  <div class="dco-drawer-body" id="dcoBody">
    <div class="dco-disclaimer">
      <strong>Disclaimer.</strong> This IMS Document is outdated. I have sufficient information to update this document.
    </div>
    <div id="dcoFormContent">Loading form…</div>
  </div>
  <div class="dco-drawer-ft">
    <label class="dco-field">
      <span class="lbl req">AD Username</span>
      <input type="text" id="dcoUsername" autocomplete="username">
    </label>
    <label class="dco-field">
      <span class="lbl req">AD Password</span>
      <input type="password" id="dcoPassword" autocomplete="current-password">
    </label>
    <div id="dcoBanner"></div>
    <div style="display:flex; gap:10px; align-items:center; margin-top:10px;">
      <button class="btn btn-primary" id="dcoSubmitBtn" onclick="dcoSubmit()" disabled>Sign &amp; Submit</button>
      <span id="dcoSubmitStatus" style="font-size:12px; color:var(--muted);"></span>
    </div>
  </div>
</aside>
<script src="/imsreview-dco-form.js"></script>
```

**Wire the action card to open the drawer**: in the existing `onPickAction` function, replace:

```javascript
    $('followupUpload').classList.toggle('show', key === 'UPLOAD');
```

with:

```javascript
    if (key === 'UPLOAD' && window.imsDcoFormEnabled) {
      $('followupUpload').classList.remove('show');
      dcoOpen(STATE.info);
      return;
    }
    $('followupUpload').classList.toggle('show', key === 'UPLOAD');
```

And add at the top of the IIFE (where `TOKEN` is defined):

```javascript
  // Phase-5 feature gate fed by /role response or a top-level config endpoint.
  window.imsDcoFormEnabled = false;
  fetch('/api/ims-review/token/info?token=' + encodeURIComponent(TOKEN) + '&dco-form-check=1',
        { credentials: 'omit' })
    .then(function (r) { return r.json(); })
    .then(function (info) {
      window.imsDcoFormEnabled = !!info.dcoFormEnabled;
    })
    .catch(function () {});
```

**Server-side gate exposure**: add `dcoFormEnabled` to the token-info response in `ImsReviewController.tokenInfo` (one extra `out.put(...)` line):

```java
        out.put("dcoFormEnabled", service.isDcoFormEnabled());
```

And add the getter to `ImsReviewService`:

```java
    public boolean isDcoFormEnabled() { return dcoFormEnabled; }
```

- [ ] **Step D1.1** — Add the `<style>` block to `ims-respond.html`.
- [ ] **Step D1.2** — Add the drawer markup + script include to `<body>`.
- [ ] **Step D1.3** — Update `onPickAction` to open the drawer when UPLOAD + flag enabled.
- [ ] **Step D1.4** — Add `dcoFormEnabled` to token-info response.
- [ ] **Step D1.5** — Add `isDcoFormEnabled()` getter to `ImsReviewService`.

### Task D2 — Build `imsreview-dco-form.js` — state, render, dropdowns

**Files:**
- Create: `~/git/plm-field-tracker/src/main/resources/static/imsreview-dco-form.js`

```javascript
// ===============================================================================
// imsreview-dco-form.js — Phase 5 DCO-form drawer for ims-respond.html
//
// Renders the DCO submission form, fetches dropdown metadata from
// /api/ims-review/dco-form-metadata, runs typeahead on user fields, validates
// via /api/ims-review/token/validate-dco-form (no token burn), submits via
// /api/ims-review/token/submit-dco (token burn + Agile cascade).
// ===============================================================================
(function () {
  'use strict';

  var DCO = {
    open: false,
    token: null,
    info: null,
    meta: null,            // { priority: [...], productLines: [...], ... }
    userCache: {},         // q -> { hits: [...], at: ms }
    selectedUsers: {       // emails per field
      documentOwners: [],
      approvers: [],
      observers: []
    },
    files: {
      redline: null,
      final:   null,
      others:  []
    },
    submitting: false
  };
  window.DCO = DCO;   // exposed for debugging in browser console

  function esc(s) { return (s == null ? '' : String(s)).replace(/[&<>"']/g, function (c) {
    return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]; }); }
  function $(id) { return document.getElementById(id); }

  // -------------------------------------------------------------------------
  // Open / close
  // -------------------------------------------------------------------------
  window.dcoOpen = function (info) {
    DCO.token = (new URLSearchParams(window.location.search)).get('token');
    DCO.info = info;
    DCO.open = true;
    $('dcoBackdrop').classList.add('show');
    $('dcoDrawer').classList.add('show');
    $('dcoDrawer').setAttribute('aria-hidden', 'false');
    fetchMetadataThenRender();
    refreshSubmitButton();
  };

  window.dcoClose = function () {
    DCO.open = false;
    $('dcoBackdrop').classList.remove('show');
    $('dcoDrawer').classList.remove('show');
    $('dcoDrawer').setAttribute('aria-hidden', 'true');
  };

  window.dcoMaybeClose = function () {
    if (anyFieldFilled()) {
      if (!confirm('Discard your DCO form and close?')) return;
    }
    dcoClose();
  };

  function anyFieldFilled() {
    var ids = ['priority', 'descriptionOfChange', 'reasonForChange', 'trainingRequirement',
               'businessUnit', 'changeImpactDisposition', 'changeImpactDetails', 'notifyStakeholders'];
    for (var i = 0; i < ids.length; i++) {
      var el = $('dco-' + ids[i]);
      if (el && el.value && el.value.trim()) return true;
    }
    if (DCO.selectedUsers.documentOwners.length || DCO.selectedUsers.approvers.length || DCO.selectedUsers.observers.length) return true;
    if (DCO.files.redline || DCO.files.final || DCO.files.others.length) return true;
    return false;
  }

  // -------------------------------------------------------------------------
  // Metadata + render
  // -------------------------------------------------------------------------
  function fetchMetadataThenRender() {
    fetch('/api/ims-review/dco-form-metadata?token=' + encodeURIComponent(DCO.token),
          { credentials: 'omit' })
      .then(function (r) { return r.json(); })
      .then(function (body) {
        var data = body && body.body ? body.body : body;   // service wraps, we expose inner
        DCO.meta = data.lists || data;
        render();
      })
      .catch(function (e) {
        $('dcoFormContent').innerHTML =
          '<div style="color:#B8342B;padding:14px;background:#fdeaea;border-radius:6px;">'
          + 'Could not load form data: ' + esc(e.message) + '</div>';
      });
  }

  function render() {
    var m = DCO.meta || {};
    var html = '';

    // Auto-filled (Number + DRR)
    html += '<div class="dco-section">Auto-filled</div>';
    html += field('Number', '<input value="Will be generated by Agile" readonly>');
    html += field('DRR Change#', '<input id="dco-drr" value="' + esc(DCO.info.drrNumber || '') + '" readonly>');

    // Change details
    html += '<div class="dco-section">Change details</div>';
    html += fieldReq('Priority', selectHtml('dco-priority', m.priority, '— pick —'));
    html += fieldReq('Description of Change', '<textarea id="dco-descriptionOfChange" maxlength="200" rows="2"></textarea><div class="helper">Max 200 chars.</div>');
    html += fieldReq('Reason For Change', '<textarea id="dco-reasonForChange" maxlength="200" rows="2"></textarea><div class="helper">Max 200 chars.</div>');
    html += fieldReq('Product Line(s)', multiSelectHtml('dco-productLines', m.productLines));
    html += fieldReq('Subcontractors', multiSelectHtml('dco-subcontractors', m.subcontractors));
    html += fieldReq('Training Requirement', selectHtml('dco-trainingRequirement', m.trainingRequirement, '— pick —'));

    // Optional (collapsed)
    html += '<a class="dco-optional-toggle" onclick="dcoToggleOptional()">Show optional fields ▾</a>';
    html += '<div id="dco-optional" class="dco-optional">';
    html += field('Business Unit', selectHtml('dco-businessUnit', m.businessUnit, '— pick —'));
    html += field('Change Impact Disposition', selectHtml('dco-changeImpactDisposition', m.changeImpactDisposition, '— pick —'));
    html += field('Change Impact Details', '<textarea id="dco-changeImpactDetails" maxlength="200" rows="2"></textarea><div class="helper">Max 200 chars.</div>');
    html += '</div>';

    // People (typeahead)
    html += '<div class="dco-section">People</div>';
    html += fieldReq('Document Owner(s)', typeaheadHtml('documentOwners'));
    html += fieldReq('Approvers', typeaheadHtml('approvers'));
    html += fieldReq('Observers', typeaheadHtml('observers'));

    // Notifications
    html += '<div class="dco-section">Notifications</div>';
    html += fieldReq('Notify Stakeholders',
        '<textarea id="dco-notifyStakeholders" rows="3" placeholder="alice@sandisk.com, dl-team@sandisk.com"></textarea>'
      + '<div class="helper">Comma or newline separated. Each entry gets the auto-submit notification.</div>');

    // Attachments
    html += '<div class="dco-section">Attachments (optional)</div>';
    html += attachmentSlotHtml('Redline Copy', 'redline');
    html += attachmentSlotHtml('Final Version', 'final');
    html += attachmentSlotHtml('Others', 'others', /* multi */ true);

    $('dcoFormContent').innerHTML = html;

    // Wire input listeners for the submit-button gate
    document.querySelectorAll('#dcoFormContent input, #dcoFormContent select, #dcoFormContent textarea')
      .forEach(function (el) { el.addEventListener('input', refreshSubmitButton); });
    $('dcoUsername').addEventListener('input', refreshSubmitButton);
    $('dcoPassword').addEventListener('input', refreshSubmitButton);

    wireTypeahead('documentOwners');
    wireTypeahead('approvers');
    wireTypeahead('observers');
    wireFileInputs();
  }

  function field(label, inner) {
    return '<label class="dco-field"><span class="lbl">' + esc(label) + '</span>' + inner + '</label>';
  }
  function fieldReq(label, inner) {
    return '<label class="dco-field"><span class="lbl req">' + esc(label) + '</span>' + inner + '</label>';
  }
  function selectHtml(id, vals, placeholder) {
    var opts = '<option value="">' + esc(placeholder) + '</option>';
    (vals || []).forEach(function (v) { opts += '<option value="' + esc(v) + '">' + esc(v) + '</option>'; });
    return '<select id="' + id + '">' + opts + '</select>';
  }
  function multiSelectHtml(id, vals) {
    var opts = '';
    (vals || []).forEach(function (v) { opts += '<option value="' + esc(v) + '">' + esc(v) + '</option>'; });
    return '<select id="' + id + '" multiple size="4">' + opts + '</select>'
         + '<div class="helper">Hold Ctrl/Cmd to select multiple.</div>';
  }
  function typeaheadHtml(key) {
    return '<div class="dco-typeahead">'
         + '  <input id="dco-' + key + '-input" placeholder="Type 2+ chars to search active Agile users" autocomplete="off">'
         + '  <div class="dco-typeahead-results" id="dco-' + key + '-results"></div>'
         + '  <div class="dco-chips" id="dco-' + key + '-chips"></div>'
         + '</div>';
  }
  function attachmentSlotHtml(label, key, multi) {
    var idSuffix = key;
    return '<div class="dco-att-slot">'
         + '  <span class="typ">' + esc(label) + '</span>'
         + '  <input type="file" id="dco-file-' + idSuffix + '" ' + (multi ? 'multiple' : '') + '>'
         + '</div>'
         + '<ul class="dco-att-files" id="dco-files-' + idSuffix + '"></ul>';
  }

  // -------------------------------------------------------------------------
  // Optional section toggle
  // -------------------------------------------------------------------------
  window.dcoToggleOptional = function () {
    var el = $('dco-optional');
    el.classList.toggle('show');
    var t = document.querySelector('.dco-optional-toggle');
    if (t) t.textContent = el.classList.contains('show') ? 'Hide optional fields ▴' : 'Show optional fields ▾';
  };

  // Typeahead + file wiring + submit live in Task D3 + D4 below.
  // For D2 the render scaffold is complete — typeahead and file/submit
  // handlers are stubs that the next two tasks fill in.
  function wireTypeahead(key) { /* D3 */ }
  function wireFileInputs() { /* D3 */ }
  function refreshSubmitButton() {
    if (!$('dcoSubmitBtn')) return;
    var hasReq = $('dco-priority') && $('dco-priority').value
              && $('dco-descriptionOfChange') && $('dco-descriptionOfChange').value.trim()
              && $('dco-reasonForChange') && $('dco-reasonForChange').value.trim()
              && $('dco-trainingRequirement') && $('dco-trainingRequirement').value
              && multiHasValue('dco-productLines')
              && multiHasValue('dco-subcontractors')
              && DCO.selectedUsers.documentOwners.length > 0
              && DCO.selectedUsers.approvers.length > 0
              && DCO.selectedUsers.observers.length > 0
              && $('dco-notifyStakeholders') && $('dco-notifyStakeholders').value.trim();
    var hasAuth = $('dcoUsername').value.trim() && $('dcoPassword').value;
    $('dcoSubmitBtn').disabled = !(hasReq && hasAuth);
  }
  function multiHasValue(id) {
    var el = $(id);
    if (!el) return false;
    for (var i = 0; i < el.options.length; i++) if (el.options[i].selected) return true;
    return false;
  }
  window.dcoSubmit = function () { /* D4 */ };
})();
```

- [ ] **Step D2.1** — Create `imsreview-dco-form.js` with the content above.
- [ ] **Step D2.2** — Hit the page in browser, click UPLOAD, confirm drawer opens with form rendered (will fail typeahead and submit; that's expected — D3/D4).

### Task D3 — Typeahead + file slot handlers

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/imsreview-dco-form.js`

Replace `wireTypeahead` and `wireFileInputs` stubs with real implementations:

```javascript
  function wireTypeahead(key) {
    var input = $('dco-' + key + '-input');
    var results = $('dco-' + key + '-results');
    var chips = $('dco-' + key + '-chips');
    if (!input) return;

    var debounceTimer = null;
    input.addEventListener('input', function () {
      if (debounceTimer) clearTimeout(debounceTimer);
      var q = input.value.trim();
      if (q.length < 2) {
        results.classList.remove('show');
        return;
      }
      debounceTimer = setTimeout(function () {
        runSearch(q);
      }, 200);
    });

    input.addEventListener('blur', function () {
      // Allow click on result to fire first
      setTimeout(function () { results.classList.remove('show'); }, 150);
    });

    function runSearch(q) {
      var cached = DCO.userCache[q];
      if (cached && (Date.now() - cached.at) < 60000) {
        renderHits(cached.hits);
        return;
      }
      fetch('/api/ims-review/user-search?token=' + encodeURIComponent(DCO.token)
            + '&q=' + encodeURIComponent(q) + '&limit=20',
            { credentials: 'omit' })
        .then(function (r) { return r.json(); })
        .then(function (body) {
          var inner = body && body.body ? body.body : body;
          var hits = (inner && inner.hits) || [];
          DCO.userCache[q] = { hits: hits, at: Date.now() };
          renderHits(hits);
        })
        .catch(function () {
          renderHits([]);
        });
    }

    function renderHits(hits) {
      if (!hits.length) {
        results.innerHTML = '<div class="hit" style="color:var(--muted);">No matches.</div>';
      } else {
        results.innerHTML = hits.map(function (h) {
          return '<div class="hit" data-email="' + esc(h.email) + '">'
               + '<strong style="color:#4a6fa5;">' + esc(h.displayName) + '</strong>'
               + ' <span style="color:var(--muted);">&lt;' + esc(h.email) + '&gt;</span>'
               + '</div>';
        }).join('');
        results.querySelectorAll('.hit').forEach(function (el) {
          el.addEventListener('mousedown', function () {  // mousedown fires before blur
            var email = el.getAttribute('data-email');
            addChip(key, email);
            input.value = '';
            results.classList.remove('show');
          });
        });
      }
      results.classList.add('show');
    }

    function addChip(key, email) {
      if (DCO.selectedUsers[key].indexOf(email) >= 0) return;
      DCO.selectedUsers[key].push(email);
      renderChips(key);
      refreshSubmitButton();
    }
  }

  function renderChips(key) {
    var chips = $('dco-' + key + '-chips');
    chips.innerHTML = DCO.selectedUsers[key].map(function (email) {
      return '<span class="dco-chip">' + esc(email)
           + '<span class="rm" onclick="dcoRemoveChip(\'' + esc(key) + '\', \'' + esc(email) + '\')">&times;</span>'
           + '</span>';
    }).join('');
  }
  window.dcoRemoveChip = function (key, email) {
    DCO.selectedUsers[key] = DCO.selectedUsers[key].filter(function (e) { return e !== email; });
    renderChips(key);
    refreshSubmitButton();
  };

  function wireFileInputs() {
    bindFileSlot('redline', false);
    bindFileSlot('final',   false);
    bindFileSlot('others',  true);
  }

  function bindFileSlot(key, multi) {
    var inp = $('dco-file-' + key);
    var list = $('dco-files-' + key);
    if (!inp) return;
    inp.addEventListener('change', function () {
      var picked = inp.files;
      if (!multi) {
        DCO.files[key] = picked && picked.length > 0 ? picked[0] : null;
      } else {
        var existing = DCO.files.others || [];
        for (var i = 0; i < picked.length; i++) existing.push(picked[i]);
        DCO.files.others = existing;
        inp.value = '';   // reset so the same file can be re-picked
      }
      renderFileList(key, multi);
    });
  }

  function renderFileList(key, multi) {
    var list = $('dco-files-' + key);
    var files = multi ? DCO.files.others : (DCO.files[key] ? [DCO.files[key]] : []);
    list.innerHTML = files.map(function (f, i) {
      return '<li>'
           + esc(f.name) + ' <span style="color:var(--muted);">(' + (f.size / 1024 / 1024).toFixed(2) + ' MB)</span>'
           + ' <span class="rm" onclick="dcoRemoveFile(\'' + key + '\', ' + i + ')">remove</span>'
           + '</li>';
    }).join('');
  }
  window.dcoRemoveFile = function (key, idx) {
    if (key === 'others') {
      DCO.files.others.splice(idx, 1);
    } else {
      DCO.files[key] = null;
      var inp = $('dco-file-' + key);
      if (inp) inp.value = '';
    }
    renderFileList(key, key === 'others');
  };
```

- [ ] **Step D3.1** — Replace `wireTypeahead`, `wireFileInputs` stubs with the implementations above; also add `renderChips`, `dcoRemoveChip`, `bindFileSlot`, `renderFileList`, `dcoRemoveFile`.
- [ ] **Step D3.2** — Reload page in browser, test typeahead in DO field (requires plm-agile-service running; if not, the search call returns 503 — that's fine, the field still works with manual entry once D4 lands).

### Task D4 — Wire pre-validate + submit

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/static/imsreview-dco-form.js`

Replace the `dcoSubmit` stub:

```javascript
  window.dcoSubmit = function () {
    if (DCO.submitting) return;
    DCO.submitting = true;
    var btn = $('dcoSubmitBtn');
    var status = $('dcoSubmitStatus');
    var banner = $('dcoBanner');
    banner.innerHTML = '';
    btn.disabled = true;
    btn.textContent = 'Validating…';

    var form = collectForm();

    // Phase 1 — pre-validate (no token burn)
    fetch('/api/ims-review/token/validate-dco-form', {
      method: 'POST', credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: DCO.token, form: form })
    })
      .then(function (r) { return r.json(); })
      .then(function (vbody) {
        if (!vbody.ok) {
          surfaceErrors(vbody);
          throw new Error('validate-failed');
        }
        // Phase 2 — heavy submit
        btn.textContent = 'Creating DCO…';
        cycleStatus(['Creating DCO…', 'Attaching files…', 'Notifying stakeholders…']);

        var fd = new FormData();
        fd.append('token', DCO.token);
        fd.append('username', $('dcoUsername').value.trim());
        fd.append('password', $('dcoPassword').value);
        fd.append('form', JSON.stringify(form));
        if (DCO.files.redline) fd.append('file_redline', DCO.files.redline);
        if (DCO.files.final)   fd.append('file_final',   DCO.files.final);
        (DCO.files.others || []).forEach(function (f) { fd.append('file_others', f); });

        return fetch('/api/ims-review/token/submit-dco', { method: 'POST', credentials: 'omit', body: fd });
      })
      .then(function (r) {
        if (!r) return;
        return r.json().then(function (body) { return { status: r.status, body: body }; });
      })
      .then(function (resp) {
        if (!resp) return;
        stopStatusCycle();
        if (resp.body.success) {
          dcoClose();
          // Reuse the existing success card on the host page
          document.getElementById('formCard').style.display = 'none';
          document.getElementById('successCard').style.display = '';
          var msg = 'DCO ' + (resp.body.dcoNumber || '') + ' created and submitted.'
                  + ' ' + (resp.body.attachmentsCount || 0) + ' file(s) attached.'
                  + ' ' + (resp.body.stakeholdersNotified || 0) + ' stakeholders notified.';
          document.getElementById('successMsg').textContent = msg;
          if (resp.body.signedBy) {
            document.getElementById('successPdf').innerHTML =
              'Signed by <strong>' + esc(resp.body.signedBy) + '</strong>'
              + (resp.body.signedEmail ? ' &lt;' + esc(resp.body.signedEmail) + '&gt;' : '') + '.';
          }
        } else {
          if (resp.body.fieldErrors) surfaceErrors({ fieldErrors: resp.body.fieldErrors });
          var b = document.createElement('div');
          b.className = 'banner error';
          b.style.padding = '10px 12px'; b.style.margin = '8px 0'; b.style.borderRadius = '6px';
          b.style.background = '#fdeaea'; b.style.color = '#6b1f1c';
          b.innerHTML = esc(resp.body.error || 'Submit failed')
                      + (resp.body.corrId ? '<br><span style="font-size:11px;">Reference: corrId=' + esc(resp.body.corrId) + '</span>' : '');
          banner.innerHTML = '';
          banner.appendChild(b);
        }
      })
      .catch(function (e) {
        if (e.message !== 'validate-failed') {
          stopStatusCycle();
          banner.innerHTML = '<div style="color:#B8342B;">Submit failed: ' + esc(e.message) + '</div>';
        }
      })
      .finally(function () {
        DCO.submitting = false;
        btn.disabled = false;
        btn.textContent = 'Sign & Submit';
        status.textContent = '';
      });
  };

  function collectForm() {
    return {
      priority:                  $('dco-priority').value,
      descriptionOfChange:       $('dco-descriptionOfChange').value.trim(),
      reasonForChange:           $('dco-reasonForChange').value.trim(),
      productLines:              multiVal('dco-productLines'),
      subcontractors:            multiVal('dco-subcontractors'),
      trainingRequirement:       $('dco-trainingRequirement').value,
      businessUnit:              ($('dco-businessUnit')              || {}).value || '',
      changeImpactDisposition:   ($('dco-changeImpactDisposition')    || {}).value || '',
      changeImpactDetails:       ($('dco-changeImpactDetails')        || {}).value || '',
      documentOwners:            DCO.selectedUsers.documentOwners.slice(),
      approvers:                 DCO.selectedUsers.approvers.slice(),
      observers:                 DCO.selectedUsers.observers.slice(),
      notifyStakeholders:        tokenize($('dco-notifyStakeholders').value),
      attachmentManifest:        buildManifest()
    };
  }
  function multiVal(id) {
    var el = $(id), out = [];
    if (!el) return out;
    for (var i = 0; i < el.options.length; i++) if (el.options[i].selected) out.push(el.options[i].value);
    return out;
  }
  function tokenize(s) {
    if (!s) return [];
    return s.split(/[,\n;]/).map(function (x) { return x.trim(); }).filter(Boolean);
  }
  function buildManifest() {
    var out = [];
    if (DCO.files.redline) out.push({ type: 'Redline', filename: DCO.files.redline.name, sizeBytes: DCO.files.redline.size });
    if (DCO.files.final)   out.push({ type: 'Final',   filename: DCO.files.final.name,   sizeBytes: DCO.files.final.size });
    (DCO.files.others || []).forEach(function (f) {
      out.push({ type: 'Others', filename: f.name, sizeBytes: f.size });
    });
    return out;
  }

  function surfaceErrors(body) {
    // Clear prior
    document.querySelectorAll('.dco-field.err').forEach(function (el) { el.classList.remove('err'); });
    if (body.fieldErrors) {
      Object.keys(body.fieldErrors).forEach(function (k) {
        // strip [index] suffix for field lookup
        var base = k.replace(/\[\d+\].*$/, '');
        var input = $('dco-' + base);
        if (input) {
          var label = input.closest('.dco-field');
          if (label) label.classList.add('err');
          var helper = label && label.querySelector('.helper');
          if (helper) helper.textContent = body.fieldErrors[k];
        }
      });
    }
    var banner = $('dcoBanner');
    if (body.formErrors && body.formErrors.length) {
      banner.innerHTML = '<div style="color:#B8342B; padding:8px 0;">' + body.formErrors.map(esc).join('<br>') + '</div>';
    } else if (body.fieldErrors && Object.keys(body.fieldErrors).length) {
      banner.innerHTML = '<div style="color:#B8342B; padding:8px 0;">Please fix the highlighted fields.</div>';
    }
  }

  // -------------------------------------------------------------------------
  // Submit-button status cycle (cosmetic progress feedback)
  // -------------------------------------------------------------------------
  var _statusTimer = null;
  function cycleStatus(messages) {
    var i = 0;
    var btn = $('dcoSubmitBtn');
    if (_statusTimer) clearInterval(_statusTimer);
    _statusTimer = setInterval(function () {
      i = (i + 1) % messages.length;
      btn.textContent = messages[i];
    }, 2500);
  }
  function stopStatusCycle() {
    if (_statusTimer) { clearInterval(_statusTimer); _statusTimer = null; }
  }
```

- [ ] **Step D4.1** — Replace `dcoSubmit` stub + add `collectForm`, `surfaceErrors`, `cycleStatus`, helpers.
- [ ] **Step D4.2** — Browser-test the form opens, dropdowns populate (will fail until A/B deployed), validate-form returns errors inline, submit button enables/disables based on required fields.

### Task D5 — Properties + whats-new + scope sanity

**Files:**
- Modify: `~/git/plm-field-tracker/src/main/resources/application.properties`
- Modify: `~/git/plm-field-tracker/src/main/resources/static/whats-new.js`

Append to `application.properties`:

```properties

# IMS Review DCO form (Phase 5) — see docs/superpowers/specs/2026-05-28-ims-review-do-dco-form-design.md
# Independent of writeback-enabled. When false, UPLOAD falls back to the legacy inline file+notes UI.
app.ims-review.dco-form-enabled=false
```

Prepend to `whats-new.js` `WHATS_NEW_RELEASES` array (newest first):

```javascript
  {
    date: '2026-05-28',
    title: 'IMS Review · Rich DCO form for Needs Change',
    items: [
      { kind: 'new', text: 'DO can now submit a fully-populated DCO directly from the IMS Review response page when picking "Needs Change — Upload". A side panel mirrors the Agile DCO cover-page fields (Priority, Product Lines, Subcontractors, Document Owners, Approvers, Observers, Notify Stakeholders, attachments with per-file Type, etc.) and auto-submits the DCO in Agile.' },
      { kind: 'new', text: 'Pre-validation pass catches typos and bad list values before the heavy Agile write, so the token isn\'t burned on form errors.' },
      { kind: 'new', text: 'On submit, stakeholders get a styled HTML email with the DCO summary, attachments list, and the signed attestation PDF.' },
      { kind: 'improve', text: 'Every Agile write step emits a structured [AGILE-WRITE] log line correlated by X-Toolkit-Action-Id — full cascade is greppable by one UUID.' },
      { kind: 'improve', text: 'New kill-switch app.ims-review.dco-form-enabled lets us toggle the drawer independently of the legacy Phase-4 cascade.' }
    ]
  },
```

- [ ] **Step D5.1** — Append the property.
- [ ] **Step D5.2** — Prepend the WHATS_NEW entry.

---

## Phase E — Build, deploy, email

### Task E1 — Full build, both jars

- [ ] **Step E1.1** — Build plm-agile-service:
  ```
  cd ~/git/plm-agile-service && mvn package -DskipTests -q 2>&1 | tail -30
  ```
  Expected: `BUILD SUCCESS`. Jar at `target/plm-agile-service-1.0.1.jar` (or whatever the existing artifact name is).
- [ ] **Step E1.2** — Build plm-field-tracker:
  ```
  cd ~/git/plm-field-tracker && mvn package -DskipTests -q 2>&1 | tail -30
  ```
  Expected: `BUILD SUCCESS`. Jar at `target/plm-field-tracker-1.0.1.jar`.

### Task E2 — Copy to staging share + local setup

- [ ] **Step E2.1** — Verify the share is mounted: `ls /Volumes/uls-ep-aglipccb/plm-toolkit/staging/`. If not mounted, halt and ask user to mount.
- [ ] **Step E2.2** — Copy toolkit jar to staging:
  ```
  cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar /Volumes/uls-ep-aglipccb/plm-toolkit/staging/
  ```
- [ ] **Step E2.3** — Mirror to local-setup copy:
  ```
  cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
  ```
- [ ] **Step E2.4** — Copy plm-agile-service jar to its deploy location (whichever share/path Vikas uses — check `~/git/plm-agile-service/README.md` or recent deploy commit for the canonical destination).
- [ ] **Step E2.5** — Verify file sizes match between source `target/` and destination using `stat -f "%z"`.

### Task E3 — Email Vikas

- [ ] **Step E3.1** — Send Vikas a build-ready email per CLAUDE.md's long-running-work notification rules. Subject: `plm-field-tracker: ✅ IMS Review DCO form ready for dry-run`. Body should include: what was built, where the jars are, how to enable the feature flag, the 5 dry-run checklists, what logs to watch.

  Use Python+SMTP per CLAUDE.md:
  ```python
  import smtplib
  from email.mime.text import MIMEText
  body = """<see template below>"""
  msg = MIMEText(body, 'html', 'utf-8')
  msg['From'] = 'PLM-Toolkit@sandisk.com'
  msg['To'] = 'vikas.jindal@sandisk.com'
  msg['Subject'] = 'plm-field-tracker: ✅ IMS Review DCO form ready for dry-run'
  with smtplib.SMTP('mailrelay.sandisk.com', 25) as s:
      s.send_message(msg)
  ```

  HTML body should follow the email design system per `CLAUDE.md`:
  - Nav header: "Agile PLM / IMS Review — Phase 5 build"
  - Hero: "DCO form for Needs Change is built and staged"
  - Section 1: What's in the build (file count, lines added, key behaviors)
  - Section 2: Pre-deploy checklist (deploy plm-agile-service first, then toolkit, then flip `app.ims-review.dco-form-enabled=true`)
  - Section 3: 5 dry-run checklists from spec §15 with checkboxes
  - Section 4: Logging cheat sheet — `grep "AGILE-WRITE.*corrId=<uuid>"` and `grep "AGILE-LIST-CACHE\|USER-SEARCH"`
  - Section 5: Kill-switch reminder — `app.ims-review.dco-form-enabled=false` reverts to legacy UI without redeploy

---

## Self-review

After completing all tasks, do a final pass:

1. **Spec coverage:**
   - §3 Field inventory → covered in C5 (request shape) + B1 (validation) + D2 (rendering)
   - §4 Drawer UI → D1 + D2 + D3 + D4
   - §5 Architecture → A1-A4, B1-B3, C1-C7
   - §6 Endpoint contracts → A2, A3, A4, B1, B3, C5
   - §7 Submit flow → C3 + C4 + D4
   - §8 Queue-event shape → C1
   - §9 PDF additions → C6
   - §10 Activity log → C3 + C4 (event types added)
   - §11 Failure handling → C4 (error paths) + C7 (alert emails) + B2/B3 (rollback)
   - §12 Stakeholder email → C7
   - §13 Kill-switches → C4 (dcoFormEnabled) + D5 (property)
   - §14 File surface → all phases
   - §15 Dry-runs → E3 (email checklists)
   - §16 Rollout → E1-E3
   - §17 Out of scope → confirmed not implemented

2. **Placeholder scan:** All `TBD` references are in agile-cell-IDs (intentional — dry-run-discoverable). No TODO/fix-me/coming-soon left in the actual code.

3. **Type consistency:** `DcoSubmitResult` (Java) ⇆ JSON response keys ⇆ JS expectations in D4 — all match. `NamedBlob.partName` selects which multipart slot — matches the server-side `@RequestParam` names in B3.

4. **Kill-switch:** `app.ims-review.dco-form-enabled=false` (default) — feature ships in OFF state. Vikas flips it on after deploy.

---

## Out of scope (deferred to follow-on plans)

- Unit tests for the new services. Project has no existing test harness; adding one expands scope significantly. The structured logging is the diagnostic surface for the dry runs.
- Front-end automated tests (Cypress / Playwright). Same reason.
- Live-data testing on this Mac (Agile connectivity is unreliable). Verification is Vikas-side after deploy.
- Plan for the inverse rollout (turning the old inline UI off permanently) once the rich form is proven.
