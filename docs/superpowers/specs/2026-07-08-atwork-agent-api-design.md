# Agent API — read-only gateway for the Atwork AI agent

**Date:** 2026-07-08
**Status:** Approved (design) — pending spec review
**Author:** Vikas Jindal (with Claude)

## Background

The Atwork team (Harsh Patel et al.) is building a **generic, self-navigating AI agent**
that discovers available read-only endpoints, retrieves relevant documents/data based on
user intent, and answers questions dynamically. They asked for a single API key that reaches
"the available read-only APIs and document retrieval endpoints" rather than a narrowly scoped
set of queries.

We are **not** exposing every read-only GET on the toolkit behind one key. Many GETs return
per-user permission data, activity/audit logs, AD/employee lookups, feedback-queue contents,
and admin diagnostics — a blanket key would be an anonymous admin session. Instead we build a
**dedicated, deny-by-default agent surface** with a machine-readable catalog for discovery.

### Decisions locked in the Teams thread

- **Audience:** all of SanDisk. The agent may serve any SanDisk employee, so the exposed data
  is scoped to business data any employee could already see in Agile — no per-user-gated data.
- **Scope of retrieval:** both **structured data** (queries) **and file/attachment downloads**
  are in v1.
- **Preferred auth:** static API key (not human AD login). Confirmed by Harsh.
- **Environment:** stand up on QA (QSS) first, validate, then promote to prod.

## Goals

1. Give Atwork a self-navigating surface: a discovery catalog + a stable set of read-only
   data and file endpoints under one key-gated path prefix.
2. Fail closed: no key configured ⇒ every endpoint returns 503. A leaked key exposes only
   all-SanDisk-visible business data, rate-limited and fully audited.
3. Reuse existing service beans in-process — no duplication of query logic, no refactor of
   internal endpoints, no drift between what the catalog advertises and what actually runs.

## Non-goals

- No write/mutation endpoints, no email triggers, no report/extract job launches.
- No xlsx/export endpoints — the agent consumes JSON; xlsx generation is compute-heavy and
  useless to an LLM consumer. (File **attachment** bytes are different — those are in scope.)
- No per-user permission enforcement inside the agent surface (audience is all-of-SanDisk by
  decision). The allowlist itself is the security boundary.
- No exposure of: user-permissions, activity/audit logs, feedback queue, AI-eval/grade,
  auth, maintenance, meeting mode, IT enhancements, IMS review, support/debug, admin, or
  saved-searches (user-pref) endpoints.

## Architecture

**Approach: registry-driven gateway controller.** A new `AgentApiController` under
`/api/agent/*` exposes explicit wrapper endpoints, each delegating to an existing service or
controller bean in-process — mirroring how `ObaController` delegates to `ObaResolverService`.
A static `AgentEndpointRegistry` describes every exposed endpoint and drives both the
`/catalog` response and the audit labels, so an endpoint cannot exist without appearing in the
catalog and vice versa.

Rejected alternatives:
- **Forwarding filter** (rewrite `/api/agent/changes` → `/api/changes`): existing handlers
  assume session attributes (username for logging, in-session result caches); a refactor of
  any internal endpoint would silently change the agent contract; too easy to leak a
  side-effecting endpoint through a path pattern.
- **Hand-written catalog doc:** rots out of sync with the code.

### Components (each a focused unit)

| Unit | Responsibility | Depends on |
|---|---|---|
| `AgentApiKeyGuard` (service) | Constant-time validation of `X-API-Key` against a set of configured keys. `NOT_CONFIGURED`/`UNAUTHORIZED`/`OK` with a resolved key **label** for audit. | `app.agent.api-keys` config |
| `AgentEndpointRegistry` (component) | Static, ordered list of `AgentEndpoint` descriptors (method, path, params, description, returns, domain). Single source of truth for the catalog. | none |
| `AgentRateLimiter` (service) | Per-key in-memory token bucket; separate buckets for data vs file endpoints. | config |
| `AgentApiController` (controller) | One thin wrapper per exposed endpoint + `GET /catalog`. Gates every call (key → rate limit → delegate), logs via `ActivityLogger`, returns uniform error envelope. | guard, registry, limiter, existing services |
| `AuthFilter` (edit) | Add `path.startsWith("/api/agent/")` to the session-auth exemption list. | — |

### Request flow (every agent endpoint)

```
X-API-Key header
  → AgentApiKeyGuard.check()  →  NOT_CONFIGURED ⇒ 503 | UNAUTHORIZED ⇒ 401
  → AgentRateLimiter.tryAcquire(keyLabel, bucket)  →  exceeded ⇒ 429 + Retry-After
  → delegate to existing service bean (read-only)
  → ActivityLogger.log("agent:<keyLabel>", "<keyLabel>", "AGENT_API", "<method> <path> <params>")
  → JSON response  (or uniform error envelope on failure)
```

## API surface

### Discovery

`GET /api/agent/catalog` (key-gated) returns:

```json
{
  "version": "1",
  "generatedAt": "<ISO-8601>",
  "endpoints": [
    { "method": "GET", "path": "/api/agent/changes",
      "domain": "Changes",
      "description": "Field-level change history search",
      "params": [ { "name": "item", "type": "string", "required": false,
                    "description": "Item number to filter by" }, ... ],
      "returns": "Array of change records {item, field, oldValue, newValue, user, revNumber, date}" }
  ]
}
```

The catalog is rendered from `AgentEndpointRegistry`. Its `generatedAt` is stamped at
request time (not persisted). No auth/session assumptions in the payload.

The catalog also advertises the **rate-limit contract** so a self-navigating agent discovers
it programmatically rather than by trial and error:

```json
"rateLimitContract": {
  "dataPerMin": 60,
  "filesPerMin": 10,
  "onExceed": "HTTP 429 with Retry-After header and a machine-readable body.",
  "clientObligation": "A 429 means the requested data was NOT returned. If the agent was gathering data to answer an end-user question, it MUST tell the end user the answer is incomplete (throttled) rather than answering from partial data. Back off per Retry-After and retry."
}
```

### v1 allowlist (~25 endpoints, all confirmed read-only)

All under `/api/agent/`. Data endpoints prefer `GET`; endpoints whose natural input is a large
item list also accept `POST` with a JSON body (mirrors how the internal endpoints already work).

| Domain | Agent endpoint(s) | Delegates to | Backing store |
|---|---|---|---|
| Items/Parts | `items/columns`, `items/distinct`, `items/search` (POST), `parts/search` | `ItemsSearchService`, `PartExtractController` service | Oracle |
| Changes | `changes` (field-change search), `history/search` | `ChangeQueryService`, `ChangeHistoryService` | Oracle |
| BOM | `bom/explode`, `bom/implode`, `bom/compare`, `bom/filters` | `BomDataService`, `RefdataController` service | Oracle (custom schema) |
| Revisions | `rev-compare/revs`, `rev-compare/compare` | `RevCompareService` | Oracle |
| ECO Timeline | `eco-timeline/query` | `EcoTimelineService` | Oracle |
| Change Review | `change-reviews/users`, `.../detail`, `.../dashboard` | `ChangeReviewService` | Oracle |
| Documents | `doc-review/data`, `sdsm/search`, `sdsm/facets`, `sdsm/active-deviations` | `DocReviewService`, `SdsmDocumentsService` | Oracle |
| **Files** | `files/list?item=` (attachments for an item/doc), `files/download?item=&name=` (bytes), `sdsm/file/{attachId}` (bytes) | `AgileItemFilesClient`, `SdsmFileService` | plm-agile-service (8081) / SDSM share |
| SKU | `sku/search`, `sku/combined-search`, `sku/fields` | `SkuDataService` | JSON cache |
| Reports data | `ecn-report/data`, `ecn-report/kpi-classifications`, `ecn-report/status`, `returns/data`, `returns/periods`, `returns/explain/{eventId}`, `overdue/data` | `EcnReportService`, `RejectionTrackerService`, `OverdueTrackerService` | JSON caches |

**File endpoints** proxy bytes through the existing `AgileItemFilesClient` / `SdsmFileService`
(the toolkit already has no local Agile vault — it always proxies to plm-agile-service on
8081, except SDSM files that live on the local share). If plm-agile-service is unreachable,
the endpoint returns **503 with a clear message**, never hangs. SDSM files hit the local share
first and only fall back to 8081.

Explicitly **excluded** from v1 (side effects or out-of-scope data): anything named
`/email`, `/run`, `/refresh`, `/recalculate`, `/trigger`, `/seed`, `/delta`, `/rebuild`,
`/snapshot`, `/generate`, `/start`; all `/export` and `/download`-of-generated-file endpoints;
all `PUT`/`DELETE`/mutating `POST`; and every controller listed under Non-goals.

## Configuration (external server config only — never committed)

```properties
# Agent API — comma-separated keys (multiple ⇒ overlap-rotation). Blank ⇒ all endpoints 503.
app.agent.api-keys=
# Optional per-key label for audit, aligned by index to app.agent.api-keys. Falls back to key #N.
app.agent.api-key-labels=
# Rate limits (per key). Data bucket and file-download bucket are independent.
app.agent.rate.data-per-min=60
app.agent.rate.files-per-min=10
```

`application.properties` (in the JAR) ships these **blank / with the defaults above** so the
surface is closed by default and only opens when the external config on a given server sets a
key. Matches the OBA pattern exactly.

## Error handling

Uniform JSON envelope on every non-2xx: `{"error": "<message>", "status": <code>}`.

| Condition | Status |
|---|---|
| No key configured | 503 |
| Missing/invalid key | 401 |
| Rate limit exceeded | 429 + `Retry-After` header (see below) |
| Bad/missing params | 400 |
| Upstream unavailable (plm-agile-service / DB) | 503 |
| Unexpected server error | 500 (generic message; no stack trace, no internal hostnames) |

### Incomplete-response signalling (rate limits)

The end user talks to Atwork's agent, not to us — so the only way a human learns their answer
was cut short by throttling is if our 429 is impossible for the agent to mistake for "no more
data" or to silently swallow. Two rules make that reliable:

1. **A throttled call returns no data.** Our endpoints never return HTTP 200 with a partially
   built or truncated body because of rate limiting. Either the full result comes back (200) or
   the call is rejected (429). So "incomplete because of rate limits" always means *a distinct
   call was 429'd* — never a quietly-shortened 200. (Result-set size caps such as
   `app.max-results` are a separate concern, handled under Result caps below.)

2. **The 429 body is self-describing and relayable.** Beyond the standard `Retry-After` header,
   the 429 envelope carries explicit fields the agent can surface to its end user verbatim:

   ```json
   {
     "error": "Rate limit exceeded — this data was not returned. Any answer built without it is incomplete.",
     "status": 429,
     "reason": "rate_limit",
     "retryable": true,
     "retryAfterSeconds": 12,
     "endUserMessage": "I couldn't retrieve all the information needed to answer this fully because the PLM system is rate-limiting requests. Please try again in a few seconds."
   }
   ```

   `endUserMessage` is a ready-made string the agent can show its human directly. The
   `clientObligation` line in the catalog's `rateLimitContract` states the expectation in
   prose. We can't force Atwork's UI to display it, but we make ignoring it a deliberate choice
   rather than an accident, and we call it out explicitly in the integration handoff to Harsh.

### Result caps (separate from rate limits)

Data queries inherit the toolkit's existing caps (e.g. `app.max-results`). When a result set is
truncated by a cap, the response is a **200 that says so**: it includes `"truncated": true` and
`"returnedRows"` / `"cap"` fields (and, where the underlying service already knows it,
`"totalAvailable"`). This keeps the "your answer may be missing rows" signal orthogonal to the
rate-limit signal — the agent can distinguish "throttled, retry" (429) from "too many matches,
narrow your query" (200 + `truncated`).

## Audit

Every agent call is logged through the existing `ActivityLogger.log(username, displayName,
action, details)` → `./data/activity-log.jsonl`, with `username = "agent:<keyLabel>"`,
`action = "AGENT_API"`, `details = "<method> <path> <sanitized params>"`. This makes agent
traffic visible in the same admin activity view already in use. (OBA endpoints are unlogged
today; the agent surface deliberately is not.)

## Testing

- **`AgentApiKeyGuard`**: blank config ⇒ NOT_CONFIGURED; single & multi-key match/mismatch;
  constant-time path exercised; label resolution by index and fallback.
- **`AgentEndpointRegistry` / catalog consistency**: every registered endpoint has a non-empty
  description, `returns`, and fully-described params; the set of registered paths equals the
  set of `@RequestMapping` paths on `AgentApiController` (guards against drift).
- **`AgentRateLimiter`**: data vs file buckets independent; refill over time; 429 boundary.
- **Controller**: 401 without key, 503 when unconfigured, 200 + delegation for one data
  endpoint (mocked service), 503 when the file client reports upstream down.
- **429 body contract**: exceeding a bucket returns 429 with `Retry-After` header **and** a body
  carrying `reason: "rate_limit"`, `retryable: true`, `retryAfterSeconds`, and a non-empty
  `endUserMessage`; and the `/catalog` payload includes `rateLimitContract.clientObligation`.
- **Result-cap signalling**: a query that exceeds `app.max-results` returns 200 with
  `truncated: true` and `returnedRows`/`cap`, distinct from the 429 path.
- **Smoke (local :8090)**: with a scratch key in local external config — `curl` the catalog,
  one data query, and one file download; confirm audit lines land in `activity-log.jsonl`.

## Rollout

1. Build with the pre-build **What's New** changelog entry (per project CLAUDE.md).
2. Copy JAR to the **QSS (QA) staging** share; Vikas does staging→live cutover.
3. Generate the real key(s) in the QA server's **external** config (not git).
4. Hand the key + catalog URL to Harsh **outside the Teams chat** (no credential in chat/email
   per project security policy). In the handoff, call out the **rate-limit contract**
   explicitly: a 429 means the data was not returned and the agent must tell its end user the
   answer is incomplete rather than answering from partial data.
5. Atwork validates against QA; promote to prod (PCCB) once stable, with a prod-specific key.

## Prerequisite / follow-up (not blocking this spec)

The toolkit listens on plain HTTP :8090 today, so an API key travels unencrypted on the wire.
`HttpsConnectorConfig.java` already supports a dual HTTP/HTTPS port (off by default). Enabling
HTTPS on the server config is a sensible prerequisite before machine traffic carries keys.
Tracked separately from this build.
