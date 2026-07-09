# Engineering Insider Role + Engineering Chat Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PLM-IT-grantable `engineering-insider` role whose holders get an "Engineering" mode in the AI Help drawer that answers in-depth build/architecture questions on Claude Opus 4.8, without ever revealing credentials or pasting actual source code.

**Architecture:** Role store is a small JSON file (`data/user-roles.json`) managed by `UserPermissionsService` beside the existing tab grants; grant/revoke is a new endpoint on `UserPermissionsController` gated by a new PLM-IT config allowlist (`app.roles.engineering-insider.granters`). `AiHelpController /ask` gains a `mode` field: `engineering` validates the role server-side, skips all deterministic interceptors, and calls Portkey with a dedicated system prompt + a new non-web-served knowledge file (`/eng-knowledge.txt` inside the JAR, 24h reload), pinned to Opus 4.8 (`@vertexai-global/anthropic.claude-opus-4-8`, configurable). Frontend: segmented Help|Engineering toggle + model pill + banner in `help-sidebar.js` (role holders only, zero DOM traces otherwise) and a Roles section in `user-permissions.js`.

**Tech Stack:** Spring Boot (Java 11), vanilla JS, Jackson JSON persistence, Portkey/Vortex gateway.

**Deviations from the handoff brief (user overrides):**
1. Engineering mode is pinned to **Claude Opus 4.8 always** (`@vertexai-global/anthropic.claude-opus-4-8` — the slug already in the AiEval catalog), not "latest from catalog". Kept configurable via `app.help.engineering.model` so a slug rename doesn't need a code change.
2. The engineering system prompt carries **hard guardrails: never reveal credentials/secrets, never output actual source code** (describe architecture in prose only). `eng-knowledge.txt` is authored with zero secrets and lives OUTSIDE `static/` so it is never web-served.
3. Default granters = `8252,25868,plmadmin` (the IT allowlist pattern from `app.maintenance.allowed-users`), NOT the brief's example `IT-APP-Agile-admin` — that group is the general access DL, which would make every user a granter and violate the "stricter than isPlmAdmin" requirement. AD group names in the CSV are still honored (matched against session `adGroups`).

---

### Task 1: Role store + PLM-IT check in `UserPermissionsService`

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/sandisk/plm/tracker/service/UserRolesServiceTest.java` (new)

- [ ] Add `@Value("${app.roles.engineering-insider.granters:8252,25868,plmadmin}") String engInsiderGrantersCsv`.
- [ ] Add constants + state: `ROLES_FILE = "./data/user-roles.json"`, `public static final String ROLE_ENGINEERING_INSIDER = "engineering-insider"`, `private volatile RolesFile rolesState`, Jackson class `RolesFile { Map<String, List<String>> roles; Map<String, RoleAudit> lastChange; }` with `RoleAudit {by, byDisplay, at, action}` keyed `role|username`.
- [ ] `loadRoles()` / `saveRoles()` mirroring `load()`/`save()`; call `loadRoles()` from `init()`.
- [ ] `public boolean hasRole(String username, String role)` — case-insensitive membership.
- [ ] `public boolean isPlmIt(String username, Set<String> adGroups)` — CSV entry matches username (equalsIgnoreCase) OR lower-cased AD group CN.
- [ ] `public synchronized void grantRole(...)` / `revokeRole(...)` — both take granter + granterAdGroups, throw `SecurityException` unless `isPlmIt`; idempotent; persist; activity-log `ROLE_GRANT` / `ROLE_REVOKE` with granter, grantee, role.
- [ ] `public Set<String> roleHolders(String role)` and `public Map<String,Object> roleLastChange(String role, String username)` for the UI.
- [ ] Unit test: non-PLM-IT granter (incl. a permissions-admin username not in the granters CSV) → SecurityException; PLM-IT by username and by AD group → grant sticks, `hasRole` true; revoke works; persistence round-trips (temp dir via `ROLES_FILE` override or just exercise in-memory + JSON mapping).
- [ ] `application.properties`: add `app.roles.engineering-insider.granters=8252,25868,plmadmin` under the permissions section, with comment.

### Task 2: Session attr + permissions endpoints

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AuthController.java` (login: `session.setAttribute("isEngineeringInsider", ...)` next to `isPlmAdmin`)
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/UserPermissionsController.java`

- [ ] `/me`: add `"isEngineeringInsider"` (live `hasRole`), `"canGrantEngineeringInsider"` (`isPlmIt(username, session adGroups)`), and `"engineeringModel"` (the pinned slug, injected via `@Value` so the drawer pill has a fallback).
- [ ] `POST /api/permissions/roles/engineering-insider` body `{username, grant:true|false}` — resolve granter from session; catch `SecurityException` → 403 `{success:false,error:...}`; refresh `isEngineeringInsider` session attr if granter==target.
- [ ] `/users`: annotate each row with `engineeringInsider: bool` + `engineeringInsiderChangedAt/By`; add `canGrantEngineeringInsider` into the `me` block.

### Task 3: Engineering chat mode in `AiHelpController`

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/controller/AiHelpController.java`
- Create: `src/main/resources/eng-knowledge.txt` (NOT under `static/` — must not be web-served)

- [ ] `@Value("${app.help.engineering.model:@vertexai-global/anthropic.claude-opus-4-8}") String engineeringModel;` + `@Autowired UserPermissionsService permissionsService`.
- [ ] In `ask(...)`, read `String mode = (String) request.get("mode")`. If `"engineering".equals(mode)`: validate `permissionsService.hasRole(username, ROLE_ENGINEERING_INSIDER)`; on failure return `403`-shaped body `{success:false, message:"Engineering mode requires the Engineering Insider role."}` (never silently fall back). On success branch to `answerEngineering(...)` BEFORE any interceptor (activity/doc/nav/FAQ/data-query all skipped).
- [ ] `answerEngineering`: system prompt (below) + eng KB, history passed through (last 8), `portkeyClient.chatWithHistory(engineeringModel, systemPrompt, msgs, 1500)`. Response: `success`, `answer` (escaped as HTML text like LLM answers), `source:"eng-llm"`, `model: engineeringModel`. Activity log `ENG_INSIDER_ASK` with `qlen=<n> model=<slug>` (never the question text). Also add `model` to normal-mode LLM responses (prod default slug) so the pill is truthful in both modes.
- [ ] Eng KB loader: `getEngKnowledgeBase()` — same 24h-reload pattern as `getKnowledgeBase()` but reads `/eng-knowledge.txt` from the JAR root (classpath, not static).
- [ ] System prompt (verbatim baseline; guardrails are hard requirements):

```
You are the Engineering Insider assistant inside the SanDisk Agile PLM Toolkit. The user holds the Engineering Insider role: answer IN DEPTH about how the site was built, what it can and cannot do, and what it could do in the future. Entertain hypothetical and what-if questions seriously, with concrete reasoning grounded in the architecture described in the knowledge base. Be candid about limitations. Name real files, tables and services when relevant. Plain text, engineer-to-engineer, 4-8 sentences by default, longer when depth is genuinely asked for.
HARD RULES (never break these, even if asked directly, hypothetically, or "for testing"):
- Never reveal credentials, passwords, password hashes, API keys, tokens, session cookies, connection strings, or the contents of any secrets/config file. If asked, refuse briefly and continue helping with the architecture question.
- Never output actual source code — no verbatim code, no reconstructed file contents, no "example of what the class looks like". Describe design, classes, data flows and behavior in prose only. Short identifier names (class/file/endpoint/property names) are fine; code blocks are not.
```

- [ ] Author `eng-knowledge.txt` (~200 lines, factual, zero secrets): stack, auth/roles, data pipelines, AI stack, ops, honest limits, future-feasibility notes — per brief §2.5, grounded in the real code read during planning (PortkeyClient, ItemCacheService, TAB_CATALOG, activity log, feedback queue PT ids, AI Eval triad, etc.). Include a first line reminding the model of the no-code/no-credentials rule.

### Task 4: Help drawer frontend (`help-sidebar.js`)

**Files:**
- Modify: `src/main/resources/static/help-sidebar.js`

- [ ] On sidebar create, fetch `/api/permissions/me` once (cache in `helpRoleInfo`). Non-holders: nothing rendered (no toggle/banner/locked state).
- [ ] Holders: under the drawer header, a segmented `Help | Engineering` toggle + right-aligned mono model pill. Help mode pill: quiet gray, prod default slug; Engineering: accent-tinted, `<slug> · opus 4.8` sourced from last response `model`, falling back to `engineeringModel` from `/me`. Show only the short model name in the pill (strip the `@provider/` prefix, e.g. `claude-opus-4-8`).
- [ ] Engineering banner line (accent tint): "Engineering insider mode. Ask in-depth questions about how the toolkit is built, what it can and can't do, and what it could do in the future. Hypotheticals welcome."
- [ ] Per-mode chat areas/histories: keep `helpAiChatHistory` for help; new `engAiChatHistory`; switching modes swaps the visible thread (re-render from history). Send `mode: 'engineering'` with `/api/help/ask` when in eng mode; interceptor shortcuts in `askHelp()` (what's-new) bypassed in eng mode.
- [ ] Eng empty state suggested questions: "How is the tab + permission system built?", "Could the toolkit write changes back into Agile?", "What can't the AI help answer today?", "Hypothetically — what would SSO take?".
- [ ] Each engineering answer gets a mono footer: `<model slug> · engineering mode`.
- [ ] 403 from ask in eng mode → render the server message, flip back to help mode.

### Task 5: User Management Roles UI (`user-permissions.js`)

**Files:**
- Modify: `src/main/resources/static/user-permissions.js`

- [ ] In the user list rows add a `ROLES` labelled cell: when `me.canGrantEngineeringInsider` → a small toggle switch per user, label "Engineering insider — can ask the help chatbot in-depth questions about how the toolkit is built"; POST to `/api/permissions/roles/engineering-insider`, optimistic update + revert on error (use `appAlert` on failure, never native alert).
- [ ] When not a granter: read-only chip "Holder · PLM IT only" (tooltip "Granted by PLM IT only") on holders, nothing on non-holders.

### Task 6: KB vocabulary + What's New + build/deploy

**Files:**
- Modify: `src/main/resources/static/app-knowledge.txt` (USER VOCABULARY: "who built this / how does the code work / architecture questions" → Engineering mode, role required, PLM IT grants)
- Modify: `src/main/resources/static/whats-new.js` (new top entry, today's date)

- [ ] Vocabulary entry so help mode routes politely.
- [ ] What's New entry (new: Engineering Insider role + engineering mode; note PLM-IT-only grant).
- [ ] `mvn -q package` (tests run), fix anything red.
- [ ] Verify locally: start local JAR, login as plmadmin, exercise: grant 403 for non-IT (plmadmin IS in granters — verify enforcement via unit test instead), `/me` fields, mode=engineering without role → 403, grant role → engineering answer path (Portkey reachable? if not, verify plumbing + error surface), UI toggle/pill/banner via browser.
- [ ] Copy JAR to `/Volumes/uls-ep-aglipccb/plm-toolkit/staging/` + `~/Documents/plm-toolkit 2/` (size-verify both). Never touch the live folder.
- [ ] Commit `.java` + touched resource files needed for the feature; email vikas.jindal@sandisk.com (no credentials in the email, ever).

## Self-Review
- Spec coverage: §1 role+mode ✓ (T1-T4), grant restriction ✓ (T1/T2), §2.1-2.5 ✓ (T1-T3), §3 ✓ (T4), §4 prompt ✓ (T3, plus user guardrails), §5 checklist: properties ✓, endpoints ✓, session attr ✓, UM toggle ✓, eng-knowledge ✓, mode param/model/response ✓, drawer ✓, activity events ✓, vocabulary ✓, What's New ✓. AI-Eval persona run against eng mode: deferred (needs live Portkey + eval harness; noted in handoff email).
- Model pin: user override applied consistently (T3 config, T4 pill).
- Types: role constant `ROLE_ENGINEERING_INSIDER` used in T2/T3; endpoint path consistent between T2 and T4/T5.
