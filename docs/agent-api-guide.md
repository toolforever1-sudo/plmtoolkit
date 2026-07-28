# PLM Toolkit — Agent API Guide (for AtWork)

**A read-only, key-gated HTTP API for retrieving SanDisk PLM (Agile) data and documents — designed for AI agents to discover endpoints and pull grounding/context on demand.**

- **Base URL:** `https://plmtoolkit.sandisk.com`
- **All endpoints are under:** `/api/agent/`
- **Auth:** one shared API key in the `X-API-Key` header (obtain from Vikas Jindal — see [Authentication](#2-authentication))
- **Read-only:** every endpoint is a query or a file download. Nothing writes, emails, or mutates Agile.
- **Audience scope:** exposes business data any SanDisk employee could see in Agile. It intentionally does **not** expose user-permission data, activity/audit logs, or admin functions.

---

## 1. Quick start

```bash
# Discover everything the key can reach:
curl --insecure -H "X-API-Key: <YOUR_KEY>" \
  https://plmtoolkit.sandisk.com/api/agent/catalog

# A real query — recent field changes in the last day:
curl --insecure -H "X-API-Key: <YOUR_KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/changes?days=1"
```

`--insecure` (curl) is needed because of the TLS cert — see [section 3](#3-tls--trusting-the-certificate). Replace `<YOUR_KEY>` with the key from Vikas.

---

## 2. Authentication

Send the key on **every** request as an HTTP header:

```
X-API-Key: <YOUR_KEY>
```

- The key is distributed by **Vikas Jindal** out-of-band. It is not published in this document. Access is controlled on your side via your Azure AD access group; only granted execs' agents should hold the key.
- **Missing or wrong key → `401`**:
  ```json
  { "error": "invalid or missing X-API-Key", "status": 401 }
  ```
- If the server ever returns `503 {"error":"Agent API not configured"}`, the key isn't loaded on that environment yet — tell Vikas.

There is **no login/session flow** for this API. The key alone authorizes the call. (Do not use the `/api/auth/login` flow — that's for the human web app.)

---

## 3. TLS / trusting the certificate

The toolkit terminates HTTPS with an **internal self-signed certificate**. A normal HTTPS client will reject it with an error like *"self-signed certificate in certificate chain."* You have two options:

**A. Trust the cert (recommended for a real integration).** Export the toolkit's certificate and add it to your client's trust store:
```bash
# Grab the server cert:
openssl s_client -connect plmtoolkit.sandisk.com:443 -servername plmtoolkit.sandisk.com </dev/null 2>/dev/null \
  | openssl x509 > plmtoolkit.crt
# Then point your client at it (examples):
curl --cacert plmtoolkit.crt -H "X-API-Key: <YOUR_KEY>" https://plmtoolkit.sandisk.com/api/agent/catalog
```
```python
# Python requests:
requests.get(url, headers={"X-API-Key": key}, verify="plmtoolkit.crt")
```

**B. Disable verification (fine for a spike/demo, not for production):**
- curl: `--insecure` / `-k`
- Python `requests`: `verify=False`
- Node `fetch`/axios: set `NODE_TLS_REJECT_UNAUTHORIZED=0` (process-wide) or a custom `https.Agent({rejectUnauthorized:false})`

> A CA-signed certificate may be issued later; when it is, drop the `--cacert`/`verify` override and it will just work.

---

## 4. Discovery — the catalog (start here)

`GET /api/agent/catalog` returns the full, machine-readable list of every endpoint the key can call, plus the rate-limit contract. **Build your agent to read this at startup and navigate from it** rather than hard-coding endpoints — the list grows over time and the catalog is always the source of truth.

```bash
curl --insecure -H "X-API-Key: <YOUR_KEY>" \
  https://plmtoolkit.sandisk.com/api/agent/catalog
```

Response shape (trimmed):
```json
{
  "version": "1",
  "generatedAt": "2026-07-08T22:34:26Z",
  "rateLimitContract": {
    "dataPerMin": 600,
    "filesPerMin": 120,
    "onExceed": "HTTP 429 with Retry-After header and a machine-readable body.",
    "clientObligation": "A 429 means the requested data was NOT returned. If the agent was gathering data to answer an end-user question, it MUST tell the end user the answer is incomplete (throttled) rather than answering from partial data. Back off per Retry-After and retry."
  },
  "endpoints": [
    {
      "method": "GET",
      "path": "/api/agent/items/distinct",
      "domain": "Items",
      "description": "Distinct values for a categorical item column.",
      "returns": "{ column, values: [string] }",
      "params": [
        { "name": "column", "type": "string", "required": true,
          "description": "Column key from /items/columns (categorical only)" }
      ]
    }
    // ... 32 endpoints total
  ]
}
```

Each endpoint entry gives you `method`, `path`, `domain`, `description`, `returns`, and typed `params` (with `required` flags) — enough to call it without any other documentation.

---

## 5. Rate limits & the incomplete-response contract

Two independent per-key budgets, each a fixed 60-second window:

| Bucket | Limit | Applies to |
|---|---|---|
| **DATA** | 60 / min | all query endpoints |
| **FILES** | 10 / min | the three file-download endpoints (`files/list`, `files/download`, `sdsm/file/{id}`) |

When a budget is exceeded the call returns **HTTP 429** with a `Retry-After` header (seconds) and this body:

```json
{
  "error": "Rate limit exceeded — this data was not returned. Any answer built without it is incomplete.",
  "status": 429,
  "reason": "rate_limit",
  "retryable": true,
  "retryAfterSeconds": 56,
  "endUserMessage": "I couldn't retrieve all the information needed to answer this fully because the PLM system is rate-limiting requests. Please try again in a few seconds."
}
```

**Contract your agent must honor:** a `429` means the data was **not** returned. If you were gathering context to answer a user, **tell the user the answer is incomplete** (you can surface `endUserMessage` verbatim) rather than answering from partial data. Then back off for `retryAfterSeconds` and retry. A throttled call never returns a partial `200` — incompleteness always shows up as a `429`.

---

## 6. Endpoints by domain

All examples assume `-H "X-API-Key: <YOUR_KEY>"` and `--insecure` (omitted for brevity). Responses are trimmed.

### Items / Parts
| Endpoint | Notes |
|---|---|
| `GET /api/agent/items/columns` | column catalog + allowed operators (call before building a search) |
| `GET /api/agent/items/distinct?column=<COL>` | distinct values for a categorical column |
| `POST /api/agent/items/search` | attribute search (JSON body) |
| `GET /api/agent/parts/search?items=<csv>` | quick part lookup by item number(s) |

```bash
curl "…/api/agent/items/distinct?column=LIFECYCLE_PHASE"
```
```json
{ "column": "LIFECYCLE_PHASE",
  "values": ["ACT","C-ACT","CPROD","DEAD","DEV","EOL","OBS","PPROD","PROTO","QUAL","RISK", "..."] }
```

`items/search` (POST) — conditions come from `items/columns` (`operators` list per column):
```bash
curl -X POST "…/api/agent/items/search" -H "Content-Type: application/json" -d '{
  "conditions": [
    { "connector": "AND", "column": "PRODUCTLINE", "operator": "eq", "value": "Client SSD" },
    { "connector": "AND", "column": "LIFECYCLE_PHASE", "operator": "eq", "value": "PROD" }
  ],
  "columns": ["PART_NUMBER","DESCRIPTION","LIFECYCLE_PHASE","REV"]
}'
```
```json
{ "rows": [ {"PART_NUMBER":"…","DESCRIPTION":"…"} ], "columns":[…], "matchedCount": 128, "truncated": false, "elapsedMs": 240 }
```
> If a result set hits the server cap, `"truncated": true` — narrow your query. This is separate from rate limiting.

### Changes / History
| Endpoint | Notes |
|---|---|
| `GET /api/agent/changes?field=&item=&user=&days=7&oldContains=&newContains=&netFilter=` | field-level change history |
| `GET /api/agent/history/search?items=<csv>&lifecyclePhases=&changeTypes=&partTypes=&releaseDateFrom=&releaseDateTo=&entryMode=ALL` | item change/release history |

```bash
curl "…/api/agent/changes?item=SDSSDE70-4T00-G50&days=30"
```
```json
{ "results": [
    { "itemNumber":"SDSSDE70-4T00-G50","fieldName":"PDM Attributes.Marking Location",
      "oldValue":"Back","newValue":"(blank)","timestamp":"2026-07-08T16:09:24Z",
      "userName":"Cheppudira, Aiyappa","revNumber":"B  MCO-P000015498-A" }
  ],
  "totalCount": 12, "uniqueItems": 1, "truncated": false, "dbOffline": false, "dataAsOf": "2026-07-08" }
```

### BOM
| Endpoint | Notes |
|---|---|
| `GET /api/agent/bom/explode?items=<csv>&maxDepth=20` | multi-level BOM (children) |
| `GET /api/agent/bom/implode?items=<csv>&maxDepth=20` | where-used (parents) |
| `GET /api/agent/bom/components?parent=<item>` | single-level components |

Optional filters on explode/implode: `lifecycles`, `lifecyclesMode` (`include`/`exclude`), `partTypes`, `partTypesMode`, `prefixes`, `prefixesMode`.
```bash
curl "…/api/agent/bom/explode?items=SDSSDE70-4T00-G50&maxDepth=3"
```
```json
{ "count": 122, "data": [
  { "level":1, "parent":"SDSSDE70-4T00-G50", "component":"R10SSDE70-4T00-G50",
    "quantity":"1", "description":"BC,SanDisk Extreme Portable SSD…", "status":"OBS-SKU",
    "rev":"C", "findNumber":"40", "itemType":"Part", "path":"SDSSDE70-4T00-G50> R10SSDE70-4T00-G50" }
]}
```

### Revisions
| Endpoint | Notes |
|---|---|
| `GET /api/agent/rev-compare/revs?part=<item>` | list a part's revisions |
| `GET /api/agent/rev-compare/detail?part=<item>&rev=<label>&change=<optional>` | attribute/BOM detail at a rev |

```bash
curl "…/api/agent/rev-compare/revs?part=SDSSDE70-4T00-G50"
```
```json
{ "count": 6, "data": [
  { "revLabel":"B", "changeNumber":"MCO-P000015498-A", "revReleaseDate":"2026-07-08 16:08:56", "revId":"580734044", "changeId":"580097672" }
]}
```

### ECO Timeline
`GET /api/agent/eco-timeline?item=<item>&from=YYYY-MM-DD&to=YYYY-MM-DD&maxDepth=25`

> **Provide `from` and `to`.** Without a date range this endpoint currently returns `503`; with a range it returns the timeline. (A future update will make the range optional / return `400` instead.)
```bash
curl "…/api/agent/eco-timeline?item=SDSSDE70-4T00-G50&from=2025-01-01&to=2026-07-08"
```
```json
{ "rows": [ { "level":9, "parentAssembly":"N001-05241-10KKMV1", "path":"SDSSDE70-4T00-G50 / R10SSDE70-4T00-G50 / …", "…":"…" } ] }
```

### Change Review
| Endpoint | Notes |
|---|---|
| `GET /api/agent/change-reviews/analysts` | reviewer roster |
| `GET /api/agent/change-reviews/detail?change=<changeNumber>` | sign-off detail for a change |
| `GET /api/agent/change-reviews/dashboard?days=30` | changes in review over a window |

### Documents — review status & shop-floor (SDSM)
| Endpoint | Notes |
|---|---|
| `GET /api/agent/doc-review/data?window=&from=&to=` | document-review dataset |
| `GET /api/agent/sdsm/search?q=<item-number>` | shop-floor doc search — **pass a non-empty `q`** (empty `q` currently returns `503`) |
| `GET /api/agent/sdsm/specs` · `/product-groups` · `/products` | SDSM facet lists |
| `GET /api/agent/sdsm/active-deviations` | active deviations |

### SKU
| Endpoint | Notes |
|---|---|
| `GET /api/agent/sku/fields` | available SKU fields (24 of them) |
| `GET /api/agent/sku/search?items=<csv>` | SKU records by item number |

```bash
curl "…/api/agent/sku/fields"
```
```json
{ "count": 24, "data": ["number","description","lifecyclePhase","rev","productLine","pm","revReleaseDate","category","productCustomer","bomType","warrantyMonths","familyName","subcontractors", "..."] }
```

### Reports (data)
| Endpoint | Notes |
|---|---|
| `GET /api/agent/ecn-report/data` | ECN KPI/SLA dataset (large — multiple MB) |
| `GET /api/agent/ecn-report/kpi-classifications` | KPI classification reference |
| `GET /api/agent/returns/data?from=&to=` | returns/rejection events in a date range |
| `GET /api/agent/returns/periods` | available frozen snapshot periods |
| `GET /api/agent/returns/explain/{eventId}` | AI explanation of one returns event |
| `GET /api/agent/overdue/data?minOver=&maxOver=&classifications=` | overdue-change tracker |

### Files — attachments & documents (FILES rate bucket)
This is the path for **policy documents, specs, checklists, and other attachments** — pull the list, then download bytes.

| Endpoint | Notes |
|---|---|
| `GET /api/agent/files/list?item=<item-or-doc-number>` | attachments on an item/document, each with a ready `downloadUrl` |
| `GET /api/agent/files/download?item=<>&name=<fileName>` | raw file bytes (`Content-Disposition: attachment`) |
| `GET /api/agent/sdsm/file/{attachId}?fileName=&rev=&parentNumber=` | SDSM shop-floor document bytes |

```bash
curl "…/api/agent/files/list?item=25-07-SM-03-00006"
```
```json
{ "item":"25-07-SM-03-00006", "found":true, "files":[
  { "fileName":"…OBA_2_Checklist_Final.pdf", "fileDescription":"Final Document",
    "fileType":"pdf", "byteSize":178416, "contentAvailable":true,
    "downloadUrl":"/api/agent/files/download?item=25-07-SM-03-00006&name=…Checklist_Final%20%28Final%20Document%29.pdf" }
]}
```
Then download using the `downloadUrl` (already URL-encoded):
```bash
curl -OJ "…/api/agent/files/download?item=25-07-SM-03-00006&name=…Checklist_Final%20%28Final%20Document%29.pdf"
# → 200, Content-Type: application/pdf, Content-Length: 178416
```
> `files/list` returns `"found":true, "files":[]` when the item exists but has no attachments. If the document service is momentarily unreachable you get `503` — retry.

---

## 7. Worked examples (grounding / RAG flows)

**"What changed on part X in the last month, and who changed it?"**
1. `GET /api/agent/changes?item=X&days=30` → field-level diffs with user + timestamp + change number.

**"Pull the OBA checklist document to ground my answer."**
1. `GET /api/agent/files/list?item=<docNumber>` → find the PDF and its `downloadUrl`.
2. `GET <downloadUrl>` → the PDF bytes; feed to your document parser.

**"Give me the bill of materials for assembly X down 3 levels."**
1. `GET /api/agent/bom/explode?items=X&maxDepth=3` → structured rows with `level`, `parent`, `component`, `quantity`, `path`.

**"Summarize an SSD SKU's key attributes."**
1. `GET /api/agent/sku/search?items=X` → SKU record; combine with `sku/fields` for labels.

**General grounding pattern:** read `/catalog` once → pick the endpoints that match the user's intent → fetch → cite the item/change/document number in your answer → respect the rate-limit contract (surface incompleteness on `429`).

---

## 8. Error model

Every non-2xx response is a uniform JSON envelope:
```json
{ "error": "<message>", "status": <code> }
```

| Status | Meaning | What to do |
|---|---|---|
| `400` | bad or missing parameter (e.g. `Unknown column: …`) | fix the request |
| `401` | missing/invalid `X-API-Key` | check the key |
| `429` | rate limited — **data not returned** | honor `Retry-After`; tell the user the answer is incomplete |
| `503` | key not configured, or an upstream/data source momentarily unavailable | retry with backoff; if persistent, contact Vikas |

Known param requirements (until a future update): **`eco-timeline` needs `from`+`to`**, and **`sdsm/search` needs a non-empty `q`** — otherwise they currently surface as `503`.

Error messages are intentionally generic and never include internal hostnames, SQL, or credentials.

---

## 9. Good-citizen notes

- **Read-only:** you cannot change anything in Agile through this API by design.
- **Cache the catalog** and reuse it; don't fetch it on every call.
- **Batch by item list** where the endpoint accepts a CSV (`items=A,B,C`) instead of many single calls — it's cheaper and easier on the rate budget.
- **Large responses:** `ecn-report/data` is multiple MB; request it sparingly and cache.
- **Provenance:** every response carries the identifiers (item/change/rev/document numbers) — cite them so answers are auditable.
- **Every call is logged** server-side against your key label for audit.

---

*Questions, a CA-signed cert, additional endpoints, or a higher rate limit → contact Vikas Jindal (PLM IT).*
