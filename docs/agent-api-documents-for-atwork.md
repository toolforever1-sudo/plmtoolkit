# Agent API — Document discovery & retrieval (for AtWork)

This addresses the document flow you laid out — **discover → filter/search → fetch** — for Agile documents that have attachments. All three steps are **live on prod now** under the Agent API. Every example below is a real call against prod with the real response.

> **Base URL:** `https://plmtoolkit.sandisk.com`
> **Auth:** header `X-API-Key: <key from Vikas>` on every call
> **Discovery:** `GET https://plmtoolkit.sandisk.com/api/agent/catalog` (35 endpoints; the 3 document ones are listed there too)
> **TLS:** self-signed cert — trust it or disable verification (see main Agent API Guide §3; examples here use `curl --insecure`)
> **Rate limits (per key):** 600 data calls/min (queries + attachment listing), 120 file byte/text fetches/min. Check `/catalog`'s `rateLimitContract` for the live values. A `429` means the data was *not* returned — surface incompleteness, honor `Retry-After`.

**Coverage:** **~9,567 distinct documents** (deduped by number) with **~39K attachments** — every Agile document that has a file, not just the ~165 policy subset. Scales beyond the policy set already, as requested. The index refreshes live from Agile (nightly + on demand), so counts drift slightly as documents change.

---

## Ask #1 — Aggregation / facets ✅  `GET /api/agent/documents/aggregate`

Counts grouped by a field, with optional pre-filters — "see what's out there" without pulling everything. (You said this was optional; it's built.)

`groupBy` and all filters accept: `lifecycle`, `style`, `function`, `classification`, `owner`, `product`, `type`, `rev`, `number`. Optional `q` free-text pre-filter.

**"Summary by Business Function" for active documents:**
```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/documents/aggregate?groupBy=function&lifecycle=ACT"
```
```json
{ "groupBy":"function", "matchedDocuments":4118, "buckets":[
  {"value":"SDSM (SSD - Equipment Engineering)|Front End","count":169},
  {"value":"Design and Development|Assembly/Packaging Engineering","count":169},
  {"value":"Quality Management Systems|FMEA","count":109}, … ]}
```

**Learn the real taxonomy before filtering** (use these instead of guessing values):
```
groupBy=lifecycle → ACT 4118 · OBS 3150 · Preliminary 2295
groupBy=style     → WI 997 · Template… 926 · System Map/Procedure 816 · VAR 552 · Guideline 547 · FMEA 284 · Specs 219 … (+ (blank) 3906)
```
> **There is no bare `style=Policy`.** Policy documents carry compound style values like `"Management System Manual, Policy"`. So **filter policy content by free-text `q` or by `function=Legal|…`**, not by an exact `style=Policy`. `aggregate` is how you discover the real values.

---

## Ask #2 — Search / filter (metadata only) ✅  `GET /api/agent/documents`  ← the priority

Distinct documents (deduped by number), metadata + attachment filenames, **no bytes**, free-text + filters + paging.

- **Filters:** `lifecycle`, `style`, `function`, `classification`, `owner`, `product`, `type` (exact, case-insensitive)
- **Free-text `q`:** matches Description, Document Number, and attachment filename
- **Paging:** `page` (0-based), `size` (default 50, max 200); response carries `total`
- **Per-doc fields:** `number`, `description`, `lifecyclePhase`, `rev`, `revReleaseDate`, `documentType`, `classification`, `owners`, `style`, `function`, `product`, and `attachments[]` = `{fileName, fileDescription, downloadUrl}`

**"Documents with 'compliance' in the description"** — the demo query:
```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/documents?q=compliance&size=50"
```
```json
{ "total":29, "page":0, "size":50, "returned":29, "indexGeneratedAt":"2026-07-10",
  "documents":[
    {"number":"00-01-WW-01-00042","description":"Product Environmental Compliance (PEC) Supplier Risk Assessment", …,
     "attachments":[{"fileName":"…","downloadUrl":"/api/agent/files/download?item=00-01-WW-01-00042&name=…"}]},
    {"number":"00-01-WW-01-00044","description":"Guidelines for Compliance MAP Software Tool", …},
    … 27 more … ]}
```

**Filter example** (active Guideline documents):
```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/documents?style=Guideline&lifecycle=ACT&size=50"
# → total 441
```

`downloadUrl` values are relative; prefix with `https://plmtoolkit.sandisk.com`.

---

## Ask #3 — Fetch content ✅

**a) Per-document detail** — `GET /api/agent/documents/{number}`: metadata + every attachment with a ready `downloadUrl`:
```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/documents/14-01-WW-00-00027"
```
```json
{ "number":"14-01-WW-00-00027",
  "description":"Global Business Courtesies Policy (Gifts, Meals, and Entertainment)",
  "lifecyclePhase":"ACT","rev":"8","style":"Management System Manual, Policy",
  "function":"Legal|Legal","classification":"Non-Automotive","owners":"Koumaka, Larissa",
  "attachments":[
    {"fileName":"14-01-WW-00-00027 Global Business Courtesies Policy (EN) - Rev 8.docx",
     "fileDescription":"…",
     "downloadUrl":"/api/agent/files/download?item=14-01-WW-00-00027&name=14-01-WW-00-00027%20Global%20Business%20Courtesies%20Policy%20%28EN%29%20-%20Rev%208.docx"},
    {"fileName":"…(CN) - Rev 8.docx", …}, {"fileName":"…(JP) - Rev 8.docx", …}, … ]}
```

**b) Download bytes** — `GET /api/agent/files/download?item=<number>&name=<fileName>` (the `downloadUrl`, already URL-encoded):
```bash
curl --insecure -OJ -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/files/download?item=14-01-WW-00-00027&name=14-01-WW-00-00027%20Global%20Business%20Courtesies%20Policy%20%28EN%29%20-%20Rev%208.docx"
# → HTTP 200, Content-Type: application/…wordprocessingml.document, Content-Length: 200097
```
Multiple attachments → call once per `fileName`.

**c) Read the text (for reasoning/summarizing)** — `GET /api/agent/files/text?item=<number>&name=<fileName>` returns the attachment's **extracted plain text** instead of bytes, so your agent can read/compare/summarize without doing its own file parsing:
```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/files/text?item=14-01-WW-00-00027&name=14-01-WW-00-00027%20Global%20Business%20Courtesies%20Policy%20%28EN%29%20-%20Rev%208.docx"
```
```json
{ "item":"14-01-WW-00-00027", "fileName":"…(EN) - Rev 8.docx",
  "byteSize":200097, "extractionStatus":"ok", "truncated":false, "chars":17913,
  "text":"Global Business Courtesies Policy (Gifts, Meals, and Entertainment) …" }
```
- Supported: **PDF and Word `.docx`** (the active policy docs). Plain text passes through.
- `extractionStatus`: `ok` · `empty` (no extractable text) · `unsupported` (e.g. legacy `.doc`, `.xls` → download the raw file instead, see the `message`) · `error`.
- Text is capped at 400,000 chars (`truncated:true` if longer).

**Two deviations from your exact spec:**
1. **Downloads/text are keyed by `document number + fileName`, not `attachmentId`** (Agile attachment IDs aren't in the index; the filename is the stable key and round-trips fine). If you want a single "all attachments as a zip" call, say the word — I can add `…/documents/{number}/attachments?zip=true`.
2. **Extracted text is available for PDF/.docx** via `/files/text` (above). Legacy `.doc`/`.xls` still come as bytes only — download and parse your side.

---

## Document-discovery cookbook (sample calls)

All calls take `-H "X-API-Key: <KEY>"` + `--insecure` (omitted below). Filters combine with AND; add `page`/`size` (max 200) for paging; every `/documents` response carries `total`. Counts shown are live at time of writing.

**By date — created in a year:**
```
GET /api/agent/documents?createdFrom=2026-01-01&createdTo=2026-12-31&size=50
# → total 148  (documents created in 2026)
```
**By date — released since a date (current revision):**
```
GET /api/agent/documents?releasedFrom=2025-01-01&size=50
# → total 2819  (documents whose current rev was released on/after 2025-01-01)
```
Dates are inclusive, `YYYY-MM-DD`; `createdFrom/createdTo` filter the document's created date, `releasedFrom/releasedTo` filter the current revision's release date. Both fields are also returned on every document (`createDate`, `revReleaseDate`).

**Only active docs, released in a window:**
```
GET /api/agent/documents?lifecycle=ACT&releasedFrom=2025-01-01&releasedTo=2025-12-31
```
**By business function (exact — discover values via aggregate first):**
```
GET /api/agent/documents?function=Quality Management Systems|General&lifecycle=ACT
```
**By document style / classification / owner:**
```
GET /api/agent/documents?style=Guideline&lifecycle=ACT            # 441 active Guidelines
GET /api/agent/documents?classification=Automotive
GET /api/agent/documents?owner=Koumaka, Larissa (1000323446)
```
**Free-text + a filter (policy-style discovery):**
```
GET /api/agent/documents?q=gift                                    # 5 docs incl. the Business Courtesies Policy
GET /api/agent/documents?q=compliance&lifecycle=ACT&size=100       # active docs mentioning "compliance"
GET /api/agent/documents?q=audit&createdFrom=2025-01-01            # recent audit-related docs
```
**Read a document's text (to summarize/compare):**
```
GET /api/agent/documents/14-01-WW-00-00027                         # find the latest-rev attachment
GET /api/agent/files/text?item=14-01-WW-00-00027&name=<fileName>   # → extracted text (PDF/.docx)
```

**"What's out there?" — aggregate first, then drill in.** Facet the whole corpus (optionally pre-filtered), pick a bucket, then list it:
```
GET /api/agent/documents/aggregate?groupBy=style                          # counts per style
GET /api/agent/documents/aggregate?groupBy=function&lifecycle=ACT         # active docs per function
GET /api/agent/documents/aggregate?groupBy=owner&classification=Automotive
GET /api/agent/documents/aggregate?groupBy=lifecycle&createdFrom=2026-01-01  # 2026-created docs by lifecycle
# then:
GET /api/agent/documents?function=<a bucket value>&lifecycle=ACT
```
**Paging through a large result:**
```
GET /api/agent/documents?lifecycle=ACT&size=200&page=0
GET /api/agent/documents?lifecycle=ACT&size=200&page=1   # total tells you how many pages
```
> Tip for the agent: for an open-ended question, call `/aggregate` to learn the real values (styles, functions, classifications), then issue a precise `/documents` filter — cheaper and more accurate than guessing filter values or scanning free-text.

---

## The end-to-end flow

1. `GET /api/agent/catalog` once — endpoints + rate-limit contract.
2. **Discover** (optional): `GET /documents/aggregate?groupBy=style` (or `function`) to learn real values.
3. **Narrow:** `GET /documents?q=<intent>&<filters>&page=…` → doc numbers + attachment filenames + `downloadUrl`s.
4. **Fetch / read:** for each file, either `GET <downloadUrl>` (bytes) or `GET /api/agent/files/text?item=&name=` (**extracted text**, PDF/.docx) → ground the answer, cite the document number + rev.

> **For policy/compliance questions, load the [Policy-Document Guidance skill](agent-policy-guidance-skill.md)** as your agent's guidance — it codifies the rules PLM wants followed: ground on the active/latest revision, flag obsolete/superseded copies, surface conflicts between documents rather than resolving them silently, and cite doc# + rev. Pair it with `/files/text` and this API and the agent stays true to those goals.

---

## Handling policy-reasoning questions (the World Cup gift hypothetical)

**"I'm a VP; if I gift a $75 ticket for the upcoming World Cup, am I violating any conduct?"**

The API doesn't answer policy questions — it **retrieves the governing documents** so *your* agent reasons over them and answers with a citation. Recommended pattern:

**1. Map the question to policy topics, then search each.** Your agent should expand the intent ("gift", "entertainment", "business courtesy", "conduct", "bribery") and search — free-text `q`, because policy lives under compound styles, not a literal `Policy` filter. Real prod results today:

| `q` | Top hit (real) |
|---|---|
| `gift` | **`14-01-WW-00-00027` — Global Business Courtesies Policy (Gifts, Meals, and Entertainment)** |
| `code of conduct` / `ethics` | `14-01-WW-*` — Worldwide Code of Business Conduct and Ethics (+ localized CN/JP/HE) |
| `anti-corruption` | `14-01-WW-00-00014` (FCPA & Anti-Corruption), `14-01-WW-00-00023` (Global Anti-Bribery & Anti-Corruption) |

```bash
curl --insecure -H "X-API-Key: <KEY>" \
  "https://plmtoolkit.sandisk.com/api/agent/documents?q=gift"
#  → includes 14-01-WW-00-00027 "Global Business Courtesies Policy (Gifts, Meals, and Entertainment)", ACT, rev 8, Legal
```

**2. Read the governing policy's current revision.** From `/documents/14-01-WW-00-00027`, take the **latest-rev EN** attachment (`… (EN) - Rev 8.docx`) and pull its text via `GET /api/agent/files/text?item=14-01-WW-00-00027&name=…(EN)%20-%20Rev%208.docx` (→ ~17,900 chars of policy text). Ground on the ACTIVE, newest rev — not an OBS/older-rev copy (the index lists older revs too).

**3. Extract the relevant clause and answer with a citation — don't assert a verdict the doc doesn't support.** Your agent reads the Business Courtesies Policy (and, if relevant, the Anti-Bribery policy for the recipient/gov't-official angle), finds the gift/entertainment thresholds and approval rules, and answers like:

> *"Per the **Global Business Courtesies Policy (14-01-WW-00-00027, Rev 8)**, gifts/entertainment above $X require pre-approval and gifts to government officials are restricted. A $75 ticket [is/again isn't] within the pre-approval threshold, but [conditions]. See §… of the policy."* — with a link to the source document.

**Guidance to bake into your agent for these questions:**
- **Ground, then answer — never answer a conduct question from the model's own knowledge.** If retrieval finds no governing policy, say so ("I couldn't locate the applicable policy") rather than guessing. This is Harsh's "graceful degradation" — a `documents?q=…` returning `total:0`, or a fetch returning `404`, is the signal to say "I can't answer that from the documented policies."
- **Prefer ACTIVE, latest-rev, English** attachments for grounding; ignore OBS and superseded revs.
- **Cite the document number + rev** in the answer so it's auditable, and offer the source link.
- **Pull more than one policy when the topic spans them** (gifts + anti-bribery + code of conduct) and reconcile — the strictest rule governs.
- **Recency caveat:** document *metadata* is a periodic snapshot (`indexGeneratedAt`, currently 2026-07-10); file *bytes are always live*. For a policy answer, the fetched file is authoritative.

---

## Notes

- **Metadata freshness:** metadata is a refreshable snapshot of "Agile documents with attachments" (`indexGeneratedAt` on every `/documents` response). File bytes are always live from Agile. Ask Vikas for a re-extract if you need newer metadata.
- **Errors (JSON `{error,status}`):** `400` bad `groupBy` / malformed request · `404` unknown document number · `429` rate-limited · `401` bad key · `503` upstream momentarily down.
- **Not literal "Policy":** discover real `style`/`function` values via `aggregate`; happy to pin the exact filter that reproduces Apsha's active-policy list.

*Live on prod. Contact Vikas Jindal (PLM IT) for the key, a metadata refresh, a zip-download variant, or a tuned policy filter.*
