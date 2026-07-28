// === What's New — Release Timeline ===
// Add new releases at the TOP of this array. Each deploy gets one entry.

var WHATS_NEW_RELEASES = [
    {
        date: 'July 23, 2026',
        title: 'ECN Dashboard &mdash; Cycle Time (Product Team) YTD flags sub-90% on-target in red',
        items: [
            { badge: 'improve', text: '<strong>On the Cycle Time (Product Team) YTD panel, any <em>% on Target</em> value below 90% now shows in red</strong> (90%+ stays green). Makes it obvious at a glance which teams are missing the bar. Requested by Jimmy Sessumes (PT-125).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> added a team-panel-local <code>ecnTeamPctColor</code> helper (red&nbsp;&lt;&nbsp;90, green&nbsp;&ge;&nbsp;90) used only by <code>ecnRenderTeamPanel</code> for its three % on Target cells (team + Standard/Urgent). The shared <code>ecnPctColor</code> (80% threshold) is deliberately unchanged, so the General/PDR cycle-time panels, POM, headline strip, and the emailed report keep their existing coloring.' }
        ]
    },
    {
        date: 'July 23, 2026',
        title: 'ECN Dashboard &mdash; Due Date Expiration now breaks down at-risk ECNs by change type',
        items: [
            { badge: 'new', text: '<strong>The Due Date Expiration view now shows what <em>kind</em> of changes are at risk.</strong> A new <em>By ECN Change Type</em> tile ranks every in-flight overdue / due-soon ECN by its change classification (high&nbsp;&rarr;&nbsp;low) in a horizontal bar graph, so you can see at a glance whether the backlog is dominated by one type of change. Click a bar &mdash; or one of the matching filter buttons above it &mdash; to filter the whole table (and the analyst tiles) to just that change type; click again or pick <em>All types</em> to clear.' },
            { badge: 'improve', text: '<strong>Change-type filtering stacks with the analyst filter.</strong> Pick an analyst tile and a change type together to answer &ldquo;which of Priya&rsquo;s at-risk ECNs are firmware changes?&rdquo; The table caption spells out both active filters, and the Excel export respects them.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the backend <code>DueDateExpirationService</code> now derives each ECN&rsquo;s change type from the shared <code>KpiClassificationService</code> map and returns a sorted <code>changeTypeCounts</code> roll-up alongside the existing rows. Frontend <code>duedateexpiration.js</code> renders the bar graph on a Chart.js horizontal bar (canvas height scales to the row count), plus pill-style filter buttons bound via <code>data-ct</code> attributes so change-type labels with quotes/specials never get inlined into a JS string. New <code>dueDateState.changeTypeFilter</code> composes with the existing <code>analystFilter</code> in <code>dueDateRenderTable</code>; the selected bar recolors to the accent while the rest mute. Cache-bust on the JS include.' }
        ]
    },
    {
        date: 'July 23, 2026',
        title: 'IMS Dashboard &mdash; removed the DO/DM &ldquo;documents waiting for your review&rdquo; banner and the Action History summary tiles',
        items: [
            { badge: 'improve', text: '<strong>Removed the &ldquo;You have N documents waiting for your review as a DO/DM &mdash; Open your queue&rdquo; banner</strong> from the top of the IMS Dashboard. Requested by Vikas (PT-102).' },
            { badge: 'improve', text: '<strong>Removed the &ldquo;Where documents go&rdquo; summary tiles</strong> at the top of the <em>Action History</em> tab. The action table, filters and search below are unchanged. Requested by Vikas (PT-102).' }
        ]
    },
    {
        date: 'July 23, 2026',
        title: 'IMS Dashboard &mdash; Reset keeps docs in New&nbsp;&gt;&nbsp;Pending, new global Doc/DRR/DCO search &amp; a RISK lifecycle pill',
        items: [
            { badge: 'fix', text: '<strong>Reset no longer throws a document back to Legacy.</strong> Previously, resetting a sent document removed its queue entry entirely &mdash; which also stripped the DRR link, so the doc dropped into the <em>Legacy</em> bucket (and vanished from <em>Need Owner</em>) instead of returning to <em>New&nbsp;&gt;&nbsp;Pending</em> with status <em>Not&nbsp;Sent</em>. Reset now reverts the item in place, preserving the DRR number and full audit history, so the row stays put and DCC can Send again. Reported by Vikas 2026-07-23.' },
            { badge: 'new', text: '<strong>Global document locator.</strong> A new search box in the IMS Dashboard toolbar finds a document across <em>all</em> tiles by Doc Number, DRR number, or DCO number. Previously the per-tile view hid any doc not in the tile you were looking at, so a document could seem to disappear. While a search is active, tile filters are ignored and a banner shows the match count with a one-click Clear.' },
            { badge: 'new', text: '<strong>RISK lifecycle pill in Change History &gt; Revision History.</strong> Documents in the Agile <em>RISK</em> lifecycle phase now render a distinct pill (and are filterable/colored in the Lifecycle Journey) instead of an unstyled badge.' }
        ]
    },
    {
        date: 'July 21, 2026',
        title: 'Create Change (DCO) form &mdash; refreshed tooltips on Product Line(s), Subcontractors, Change Impact Disposition, Approvers, Observers &amp; Stakeholder Notification',
        items: [
            { badge: 'improve', text: '<strong>Product Line(s) and Subcontractors tooltips are now plain-language.</strong> Both now read &ldquo;Auto-filled from Document Title Block, may be added or removed as needed.&rdquo; instead of the previous wording about a suggestion you can change or clear.' },
            { badge: 'improve', text: '<strong>Change Impact Disposition tooltip now explains the ISO basis.</strong> It reads &ldquo;Derived from ISO 9001:2015 Clause 6.3, requiring planned changes with consideration of impact, QMS integrity, resources, and responsibilities.&rdquo;' },
            { badge: 'new', text: '<strong>Approvers and Observers now have tooltips</strong> (previously neither had one). Approvers: &ldquo;To ensure the document is accurate, compliant with IMS and business requirements, and fit for implementation.&rdquo; Observers: &ldquo;To ensure awareness of changes affecting their function or responsibilities and promote cross-functional alignment and understanding.&rdquo;' },
            { badge: 'improve', text: '<strong>Stakeholder Notification tooltip reframed around why it matters.</strong> Now opens with &ldquo;To keep the right people informed and aligned when changes occur&rdquo; ahead of the existing Email Copy / Email Addresses guidance.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> six targeted edits to the <code>coRow(...)</code> tooltip strings in <code>imsreview-dco-form.js</code> for Product Line(s), Subcontractors, Change Impact Disposition, Stakeholder Notification, plus new 4th-argument <code>infoTip</code> calls added to the Approvers and Observers rows (which previously omitted the argument entirely). Text sourced from Bibi Kolam&rsquo;s reviewed tooltip spreadsheet (column F); all other DCO form tooltips left unchanged (column F blank = keep current text).' }
        ]
    },
    {
        date: 'July 20, 2026',
        title: 'IMS Review &mdash; new Action History view + &ldquo;which bucket&rdquo; alerts',
        items: [
            { badge: 'new', text: '<strong>A new <em>Action History</em> tab on the IMS Dashboard shows every action taken on your documents.</strong> At the top, a <strong>flow map</strong> shows how documents move between stages over the last 30 days &mdash; how many are Not Sent, Sent to DO, Sent to DM, and DM&nbsp;Approved&nbsp;&middot;&nbsp;Closed, with the number of sends, responses, and approvals along the way, plus off-path counts for Needs Change, Need Help, Resets, and Cancellations. Below it, a full <strong>action log</strong> lists every send, resend, owner response, DM approval, reset, and cancellation &mdash; newest first &mdash; with who did it, when, and how the document&rsquo;s status changed.' },
            { badge: 'new', text: '<strong>Click any document number to trace its journey.</strong> A side panel opens a step-by-step timeline of everything that has happened to that document, from the DRR being created through to its current status, including how long it sat in each state.' },
            { badge: 'new', text: '<strong>Filter the log fast.</strong> Chips narrow it to Sends, Responses, Resets&nbsp;&amp;&nbsp;admin, or System actions; a dropdown filters by person; and a search box filters by document or DRR number.' },
            { badge: 'improve', text: '<strong>After you Send, Resend, Reset, or Cancel a document, a second alert now tells you which tile it moved into.</strong> Right after the usual confirmation, a small card names the destination bucket (for example <em>New DRR&nbsp;&rsaquo;&nbsp;Pending Response &mdash; awaiting owner response</em>) so it&rsquo;s obvious where to find the document next. A bulk send shows one summary alert for the whole batch.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>GET /api/ims-review/action-log?windowDays=30</code> (admin/DCC-gated) derives the feed entirely from the existing <code>queue.jsonl</code> event log &mdash; no new logging at mutation points. <code>ImsActionLogService</code> replays all events (read fresh from disk so RESET-dropped docs still appear), maps each <code>EventType</code> to an action + from/to status, folds the system <code>SEND_TO_DM</code> bridge into the preceding DO No-Change row, and returns events, per-state counts, per-transition counts, and per-doc journeys. The bucket toast reuses <code>ImsClassify.imsTileKey</code> on the post-action row (no hard-coded mapping). Fail-soft: an empty store renders gracefully.' }
        ]
    },
    {
        date: 'July 20, 2026',
        title: 'Restart ECN &mdash; attachments are far easier to tag',
        items: [
            { badge: 'improve', text: '<strong>Files now collapse to one compact row each, so several .jar files no longer overflow the dialog.</strong> Each pending file shows as a single line &mdash; name, size, and a summary of what it&rsquo;s tagged with (<em>ECN #, ECN # &middot; jar file1</em>). Only the file you&rsquo;re editing is expanded, and the form automatically opens the next file that still needs attention. Anything incomplete stays outlined in red with a plain reason: <em>needs ECN #</em>, <em>&middot; needs type</em>, or <em>re-tag &mdash; ECNs left the bundle</em>.' },
            { badge: 'improve', text: '<strong>New files start pre-tagged.</strong> A file you add is automatically referenced to every ECN you&rsquo;ve selected (or, in the history drawer, every ECN bundled on the Restart ECN) &mdash; just untoggle the ones that don&rsquo;t apply. Drop two files with two ECNs selected and the descriptions are already right, with no clicks.' },
            { badge: 'new', text: '<strong>&ldquo;apply to all files&rdquo;.</strong> When one file is tagged the way you want, a small link copies its ECN references onto every other pending file &mdash; the usual case where several jars belong to the same ECNs.' },
            { badge: 'improve', text: '<strong>Attachment descriptions now name the file when you don&rsquo;t type a note.</strong> A file uploaded without a description note is now written to Agile as <em>ECN #, ECN # &mdash; filename.jar</em> instead of just the ECN numbers, so the Attachments tab is self-describing. Typing a note still overrides the filename, exactly as before.' }
        ]
    },
    {
        date: 'July 15, 2026',
        title: 'IMS Review &mdash; DRR now reaches Review when DO and DM both confirm No Change',
        items: [
            { badge: 'fix', text: '<strong>When the Document Owner and their manager both confirm No Change Needed, the DRR now advances to Review (CCB) as intended.</strong> Previously, if the best-effort History-line write hit a permissions hiccup, it silently aborted the entire close-out, leaving the DRR stuck in Pending &mdash; even though the status change itself was fine. The History line is now best-effort and no longer blocks the advance.' },
            { badge: 'new', text: '<strong>If a No-Change close-out still can&rsquo;t advance the DRR, PLM IT now gets an alert.</strong> When both owner and manager confirm No Change but the toolkit can&rsquo;t move the DRR to Review, a short email now goes to PLM IT naming the DRR and the step that failed &mdash; so a stuck DRR no longer goes unnoticed (the &ldquo;Approved&rdquo; confirmation is sent before the advance, so it can&rsquo;t flag this on its own).' },
            { badge: 'improve', text: '<strong>The &ldquo;Your response is recorded&rdquo; confirmation now shows the DRR number as a link.</strong> After you submit a response, the confirmation page lists the DRR you actioned as a clickable link straight to it in Agile.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>callHistory</code> no longer stamps <code>ev.agileError</code> on an <code>appendHistory</code> failure (e.g. <code>errorCode=407 Insufficient privilege</code>) &mdash; it records a non-fatal step so the DM-Approve <code>close-no-change</code> cascade proceeds to the Pending&rarr;Submit&rarr;CCB advance. Root-caused from the QA agile-service log (DRR-0011070): every run showed <code>attach-file</code> ok + <code>history</code> FAIL 407 and <strong>no <code>close-no-change</code> step at all</strong>. If the status change itself also returns 407 after this, that is a separate Agile privilege grant for the service account, not a code fix.' }
        ]
    },
    {
        date: 'July 14, 2026',
        title: 'IMS Dashboard &mdash; numbers now refresh to live automatically',
        items: [
            { badge: 'fix', text: '<strong>The dashboard tiles no longer show stale numbers on first open.</strong> When you open the IMS Dashboard it paints the fast cached snapshot immediately, then automatically re-queries Agile live in the background and updates the tiles &mdash; so you don&rsquo;t have to click Refresh to trust the counts. A small freshness chip by the Refresh button shows <em>cached &middot; updating to live&hellip;</em> and then <em>live &middot; as of HH:MM</em>. Previously the numbers could change when you clicked Refresh because the first paint was served from an up-to-60-minute cache.' },
            { badge: 'improve', text: '<strong>The &ldquo;Need Owner&rdquo; count settles on its own.</strong> The owner &ldquo;left the company&rdquo; check that drives Need Owner is computed in the background after a live pull; the dashboard now does one quiet follow-up read a few seconds later so that tile lands on its final number without a manual refresh.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> admin open now calls <code>imsReviewRefresh(false, {thenRevalidate})</code> &mdash; cached paint, then a background <code>refresh=true</code> live pull, then one silent cached re-read to pick up the async LDAP owner-status enrichment (which writes back into the docs cache). No blocking spinner on the auto pulls; a freshness chip reflects state. Root cause was two compounding staleness sources: the 60-min docs cache and asynchronous owner-status enrichment.' }
        ]
    },
    {
        date: 'July 14, 2026',
        title: 'IMS Review &mdash; &ldquo;Confirm No Change&rdquo; button fixed in Outlook',
        items: [
            { badge: 'fix', text: '<strong>The &ldquo;Confirm No Change&rdquo; button in the manager-approval email now renders as a proper button in Outlook.</strong> It previously showed as a flat box in classic Outlook; it now uses the same Outlook-safe button treatment as the &ldquo;Submit Response&rdquo; button in the document-owner email.' }
        ]
    },
    {
        date: 'July 14, 2026',
        title: 'IMS Dashboard &mdash; drag &amp; drop files onto the DCO form',
        items: [
            { badge: 'improve', text: '<strong>You can now drag a file straight onto any file slot on the DCO / OBS form.</strong> Drop a document onto the Redline, Final, Email&nbsp;Copy, or Other&nbsp;Supporting&nbsp;Files box &mdash; it highlights as you hover and picks up the file the same way the <em>Choose file</em> button does (single slots take one file, the multi-file slots add every file you drop). The picker button still works exactly as before.' }
        ]
    },
    {
        date: 'July 14, 2026',
        title: 'IMS Dashboard &mdash; DCO submit-failure email now tells owners who to contact',
        items: [
            { badge: 'improve', text: '<strong>When a DCO can&rsquo;t auto-submit, the email to the document owner now has a clear &ldquo;Who to contact&rdquo; section.</strong> It lists <strong>Document Control (DCC)</strong> for help submitting the DCO or questions about the document, and <strong>PLM IT</strong> for the technical error or toolkit behaviour &mdash; each with the right email address &mdash; so owners aren&rsquo;t left guessing who to reach out to. (Requested by DCC.)' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new &ldquo;Who to contact&rdquo; block in <code>buildDcoSubmitFailedUserHtml</code> (DO-facing failure email) replaces the old buried one-liner. The two addresses reuse existing config &mdash; <code>app.ims-review.always-cc</code> for DCC (<code>IMS-DCC-Agile@sandisk.com</code>) and <code>app.ims-review.it-diagnostic-dl</code> for PLM IT (<code>pdl-plm-admin@sandisk.com</code>) &mdash; so repointing either DL updates the email automatically. The IT-triage variant is unchanged.' }
        ]
    },
    {
        date: 'July 13, 2026',
        title: 'ECN Dashboard &mdash; new &ldquo;Due Date Expiration&rdquo; view',
        items: [
            { badge: 'new', text: '<strong>The daily ECN Due Date Expiration email is now a live view inside the ECN Dashboard.</strong> A new <strong>Due Date Expiration</strong> tab shows every in-flight ECN that is overdue or due within a day &mdash; the same Critical / Overdue / Due&nbsp;Soon summary tiles (with Urgent vs Standard split) and sorted table (ECN, Product Line, Analyst, Proposal, Target Due Date, Priority, days-overdue, Comments) as the emailed report, refreshed and cached so it&rsquo;s always ready.' },
            { badge: 'new', text: '<strong>A daily-dues trend graph.</strong> A <em>Backlog over time</em> line chart shows how the Critical / Overdue / Due&nbsp;Soon counts move day to day (are we catching up?), alongside a <em>Due dates ahead</em> histogram of how many ECNs come due each day over the coming weeks.' },
            { badge: 'new', text: '<strong>Per-analyst tiles.</strong> A row of tiles shows how many at-risk ECNs sit with each Change Analyst &mdash; click one to filter the table to just that analyst.' },
            { badge: 'improve', text: '<strong>Toggle for IT / project ECNs.</strong> By default the view matches the operational email (product ECNs only); an <em>Include IT (-PROJ)</em> switch brings in project / IT-enhancement ECNs when you want the full picture.' },
            { badge: 'new', text: '<strong>Excel export.</strong> A <em>Download Excel</em> button exports the current list (same columns as the email) so you can share or slice it offline.' },
            { badge: 'new', admin: true, text: '<strong>Implementation (ECN-137642-PROJ):</strong> new <code>/api/ecn-report/due-date/{data,refresh,trend}</code> backed by <code>DueDateExpirationService</code> &mdash; direct Agile-Oracle SQL (no SDK), Target Due Date = <code>page_three.DATE31</code> (attr node 251739706). Population <strong>mirrors the saved search</strong> <code>ECNReports/ECNDueDateExpireReport</code> 1:1: in-flight status (Release/Review/Submit &mdash; Hold excluded), Number does-not-contain CCB, Product Line not EVB&nbsp;ENGG / N/A, and Request Classification not under &ldquo;Project&nbsp;Request&rdquo; (the Include-IT toggle lifts the last one). Notes from the latest CCB <code>change_history</code> comment. Cached to <code>./data/ecn-report/due-date-expiration-cache.json</code>; a daily <code>@Scheduled</code> refresh appends one backlog snapshot per day. Tunable via <code>app.duedate.*</code>.' }
        ]
    },
    {
        date: 'July 13, 2026',
        title: 'Meeting Mode &mdash; &ldquo;Discussed this meeting&rdquo; now surfaces on the enhancement',
        items: [
            { badge: 'improve', text: '<strong>During a live session, an enhancement you&rsquo;ve already discussed now shows it clearly.</strong> When you scroll back onto an item you updated earlier in the meeting, a green <strong>&ldquo;Discussed this meeting&rdquo;</strong> banner at the top of its detail pane lists exactly what was captured (each Agile update / note, with the time), a <strong>✓ DISCUSSED · N</strong> badge sits next to the ECN id, and the queue row on the left shows a green <strong>✓ Discussed · N</strong> pill &mdash; so the team stops re-discussing items it already handled. Updates live as you capture, undo, or cancel. The meeting log and the separate &ldquo;Notes from earlier meetings&rdquo; timeline are unchanged.' }
        ]
    },
    {
        date: 'July 13, 2026',
        title: 'Change History &mdash; enrich now handles huge files without OOM',
        items: [
            { badge: 'fix', text: '<strong>Enriching a very large file no longer risks taking the toolkit down for everyone.</strong> Change-History enrichment used to build the entire spreadsheet in memory, so a big upload (tens of thousands of rows) could run the server out of memory mid-request. It now switches to a streaming writer for large files &mdash; a 48,000-row report enriches in ~seconds with flat memory instead of crashing &mdash; while normal-sized files keep the exact same full-formatting output as before.' },
            { badge: 'improve', text: '<strong>Enrichment is a bit faster on files with repeated items.</strong> When the same item number appears on many rows, its Change History is now looked up once, not once per row.' },
            { badge: 'fix', text: '<strong>&ldquo;Show all&rdquo; on a huge search no longer freezes the page.</strong> Searching/​uploading tens of thousands of items in &ldquo;Show all&rdquo; mode could return hundreds of thousands of rows and lock up the browser tab. The on-screen results now show the first 10,000 with a banner telling you the true total &mdash; use <strong>Export</strong> or <strong>Enrich</strong> for the complete set (both stream all rows).' },
            { badge: 'improve', text: '<strong>&ldquo;First entry / Last entry&rdquo; now applies to Enrich, too.</strong> Previously the Enrich button always used the earliest matching change per item regardless of the radio. Now <em>First entry</em> fills the earliest and <em>Last entry</em> fills the most-recent change per item; <em>Show all</em> defaults to earliest (Enrich fills one value per item).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation (PT-124):</strong> <code>/api/history/enrich</code> built a full XSSF DOM and shifted every cell — a 48,471-row SKU report ballooned to ~2.4&nbsp;GB of XMLBeans objects (confirmed by live heap histogram: 20M <code>AttrXobj</code>) and OOM&rsquo;d the JVM. Now two-path: the DOM rewrite for files ≤25,000 rows (full fidelity), and a new streaming <code>StreamingEnrichWriter</code> (SAX read → SXSSF write, cloned styles + typed values + column widths) above that. Verified with the exact PT-124 file: 200 in a few seconds, peak heap a few hundred MB (was 3.4&nbsp;GB). Also capped the controller&rsquo;s <code>lastResults</code> export/email cache at 50K rows so one big search no longer pins ~1&nbsp;GB in a singleton for all users, and the query now dedups items first.' }
        ]
    },
    {
        date: 'July 10, 2026',
        title: 'Agent API &mdash; document discovery for AI agents',
        items: [
            { badge: 'new', text: '<strong>The Agent API can now find documents, not just fetch known ones.</strong> New endpoints let an AI agent search/filter the Agile document set by lifecycle, style, function, classification, owner, product or free-text on the description (e.g. &ldquo;all documents with <em>compliance</em> in the description&rdquo;), see faceted counts (&ldquo;how many Policy docs per business function&rdquo;), and then pull the attachment files &mdash; the flow behind the AtWork policy-Q&amp;A use case.' },
            { badge: 'fix', text: '<strong>Item search is more forgiving.</strong> Agent item searches that sent a filter list as plain text (instead of an array) were failing; they now work, and genuinely malformed requests get a clear error instead of a generic failure.' },
            { badge: 'new', admin: true, text: '<strong>Twice-daily Agent API usage digest.</strong> At 9am and 9pm PT an HTML email goes to PLM IT + the AtWork team with an Excel of every API call served and its latency, plus an AI briefing of what was asked about (themes), what worked (wins), and what to watch (misses — rate limits, errors, empty searches). A new metrics filter records per-call timing/status; recipients configurable; admin can trigger an off-cycle send via <code>POST /api/admin/agent-usage-report/send-now</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Usage-digest recipients are now hot-editable.</strong> The digest recipient list can be changed without a restart &mdash; edit a recipients file or use <code>GET/PUT /api/admin/agent-usage-report/recipients</code> and the next send picks it up. The admin send-now also accepts <code>?to=</code> to fire a private validation send to a single address without touching the standing list.' },
            { badge: 'improve', admin: true, text: '<strong>Agent document downloads are now cached.</strong> Repeated reads of the same attachment are served from an in-memory cache instead of re-invoking the Agile SDK each time — so the same policy docs read by many agent users cost one Agile fetch, not one per request. Cache hits also skip the file rate limit. Self-invalidates on new revisions (filenames carry the rev); 512 MB / 12 h defaults, tunable via <code>app.agent.filecache.*</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Agent API rate limits relaxed for shared-key use.</strong> Raised to 600 data + 120 file calls/min per key (was 60/10) since AtWork drives all users through one key, and moved attachment <em>listing</em> into the data bucket so file lookups no longer consume the download budget. Configurable via <code>app.agent.rate.*</code>.' },
            { badge: 'new', text: '<strong>Agents can now read a document’s text, not just download it.</strong> A new endpoint returns the extracted plain text of a PDF or Word attachment so an AI agent can read, compare, and summarize policy content directly (their agent does the summarizing; we supply the text + a policy-reasoning guidance skill). Unsupported formats fall back to a clear “download the file” note.' },
            { badge: 'new', text: '<strong>Documents can now be discovered by date.</strong> The document API accepts created-date and released-date ranges &mdash; e.g. &ldquo;documents created in 2026&rdquo; or &ldquo;released since 2025&rdquo; &mdash; alongside the existing free-text and facet filters (<code>createdFrom/createdTo</code>, <code>releasedFrom/releasedTo</code>). Each document now also reports its created date.' },
            { badge: 'improve', admin: true, text: '<strong>The document index now refreshes itself from Agile.</strong> Instead of manually exporting from Agile and dropping in a file, the index rebuilds live from the database &mdash; nightly, or on demand via <code>POST /api/admin/agent-docs/refresh</code> (PLM IT only). Validated to reproduce the Agile export exactly (9,567 documents). Implementation: <code>DocumentIndexService.refreshFromDb()</code> runs a single agile-schema query (ITEM+REV+PAGE_THREE+LISTENTRY+AGILE_FLEX+ATTACHMENT_FULL_MAP) and rewrites the index file so restarts persist.' },
            { badge: 'new', admin: true, text: '<strong>Implementation:</strong> <code>GET /api/agent/documents</code> (filter + free-text + paging, deduped by document number, metadata + attachment filenames with per-file <code>downloadUrl</code>), <code>GET /api/agent/documents/aggregate</code> (facet counts by any field), <code>GET /api/agent/documents/{number}</code>. Backed by <code>DocumentIndexService</code> &mdash; an in-memory index (~9.5K docs) loaded fail-soft from a JSON seed built by <code>data/agent-docs/build_document_index.py</code> from an Agile export (<code>app.documents.index.file</code>); no DB dependency, file bytes still via <code>files/download</code>. Also: <code>items/search</code> coerces <code>columns</code>/<code>values</code> from list-or-CSV and 400s on a bad body; <code>eco-timeline</code>/<code>returns/data</code> default missing dates to a wide window instead of erroring.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS review &mdash; approval PDF and change-order form polish',
        items: [
            { badge: 'fix', text: '<strong>No more stray &ldquo;?&rdquo; characters in the approval PDF.</strong> The header (&ldquo;Sandisk &mdash; IMS Document Review&rdquo;), the action line (&ldquo;Approval &mdash; Needs Change &mdash; DCO Submitted via Toolkit&rdquo;) and every empty field were showing a question mark. Empty fields now show a dash.' },
            { badge: 'new', text: '<strong>Edits to a document&rsquo;s SDSM attributes are now recorded on the document&rsquo;s History tab in Agile.</strong> The entry names the person who signed the change-order form and the DCO it was raised under &mdash; e.g. &ldquo;SDSM document attributes updated via PLM Toolkit by Singh, Vikas (1000296585) under DCO-523134: VAR Type -&gt; Equipment; Product Group -&gt; ARTEMIS&rdquo;. Previously the change showed against the service account with no link to a person or change order.' },
            { badge: 'improve', text: '<strong>When a change order edits the document&rsquo;s SDSM attributes, the approval PDF now records the new values.</strong> A &ldquo;SDSM document attributes written to Agile&rdquo; section lists VAR Type, Product Group, Spec, Product and PM Checklist. It only appears when something was actually edited.' },
            { badge: 'fix', text: '<strong>The approval PDF now lists people by name.</strong> Document Owner(s), Approvers and Observers were printing raw data (<code>{loginId=…, displayName=…, email=…}</code>); they now read &ldquo;Name (loginId)&rdquo;.' },
            { badge: 'improve', text: '<strong>Change-order form tidy-up.</strong> The &ldquo;Grab the released file&hellip;&rdquo; hint is gone; the auto-fill notes under Product Line(s) / Subcontractors and the &ldquo;Pre-filled from the document&rdquo; note under Document Owner(s) moved into their &#9432; tooltips; SDSM Business Unit now says &ldquo;Only applies to SDSM Documents&rdquo;; and section 04 reads &ldquo;This section only applies to SDSM Documents&rdquo;.' },
            { badge: 'fix', admin: true, text: '<strong>Implementation:</strong> <code>ImsReviewPdfService</code> mapped every non-Latin-1 character to <code>?</code> (<code>replaceAll("[^\\u0000-\\u00FF]","?")</code>) &mdash; and the copy is full of em-dashes, an ellipsis, and an em-dash empty-placeholder. Replaced with an <code>asciify()</code> transliterator (&mdash;/&ndash; &rarr; -, &hellip; &rarr; ..., curly quotes &rarr; straight, &rarr; &rarr; -&gt;), leaving Latin-1 (accents, &middot;) intact; &lsquo;?&rsquo; is now only a last resort. <code>formStr()</code> called <code>toString()</code> on the people maps &mdash; new <code>personStr()</code> renders displayName + loginId.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS review &mdash; the respond page opens fast again',
        items: [
            { badge: 'fix', text: '<strong>The review response page and change-order form no longer take up to a minute to appear.</strong> Two separate bottlenecks &mdash; loading the document&rsquo;s attributes, and loading the change-order form &mdash; both now return in well under a second.' },
            { badge: 'fix', admin: true, text: '<strong>Change-order drawer:</strong> <code>/token/dco-form-metadata</code> called agile-service&rsquo;s <code>/item/{n}/cells</code> on <em>every</em> drawer open (never cached) just to read two values for the Product Line / Subcontractors prefill. That endpoint is a <em>diagnostic</em>: for every list-bound cell it also pulls the cell&rsquo;s entire selectable catalog over the SDK &mdash; a dozen full-catalog round-trips on an IMS document. Added a targeted <code>/item/{n}/cell-values?baseIds=1004,1565</code> (value only, no <code>getAvailableValues()</code>) and the toolkit now fires it in parallel with <code>listValues</code>, so the drawer waits on the slower leg rather than the sum. The admin-list cache is now stale-while-revalidate with an async startup warm, and the two heavy catalogs (Product Group / Spec) load in the background so a cold cache never blocks a request. New <code>[IMS-TIMING]</code> and <code>[AGILE-CELLS]</code> log lines attribute latency per leg.' },
            { badge: 'fix', admin: true, text: '<strong>Implementation:</strong> Agile stores multi-value attributes as comma-wrapped id CSVs, and <code>ImsDocDetailsService</code> resolved them with <code>INSTR(csv, \',\'||le.ENTRYID||\',\') &gt; 0</code>. Wrapping the indexed column in a function forced a <em>full table scan per attribute</em> &mdash; 4&times; over <code>LISTENTRY</code> (263K rows) and 2&times; over <code>ITEM</code> (1.04M rows), roughly 3.1M rows scanned on every page load. Rewritten to one indexed row-read (ITEM by ITEM_NUMBER, PAGE_TWO/THREE by PK, AGILE_FLEX by ID+ATTID) returning the raw ids/CSVs, then three batched index lookups (<code>LISTENTRY.ENTRYID IN (…)</code>, <code>ITEM.ID IN (…)</code>, <code>AGILEUSER.ID IN (…)</code>, chunked at 900 for Oracle&rsquo;s IN limit), assembled in Java with CSV order preserved. Output verified byte-identical against the Excel-export baselines for <code>03-32-WW-02-00074</code> and <code>33-04-SM-03-00057</code>.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS review &mdash; refreshed respond page and change-order form',
        items: [
            { badge: 'improve', text: '<strong>The document-review response page and change-order form got a cleaner, approved redesign.</strong> A <em>Training Guide</em> button now sits next to <em>Download IMS Document</em> on both the response page and the change-order form. &ldquo;I need help&rdquo; is a full-width banner (&ldquo;Stuck? I need help&rdquo;) under the response options instead of a fourth card. In the change-order form, Stakeholder Notification shows <em>Email Copy</em> and <em>Email Addresses</em> as two equal boxes with a clear <strong>OR</strong> divider, help text tucks into &#9432; tooltips, section 05 is now <em>Attachments</em>, and an obsolete change order is titled <em>&ldquo;OBS - Don&rsquo;t need it&rdquo;</em> with an OBSOLETE-watermark hint on its final file.' },
            { badge: 'fix', text: '<strong>Fixed a stray leading comma</strong> when you picked your first stakeholder email from the directory search into an empty box. Pasting a distribution list straight from Outlook (Name &lt;email&gt;; &hellip;) is cleaned up automatically.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS Dashboard &mdash; closed reviews now show in the Closed tile',
        items: [
            { badge: 'fix', text: '<strong>Reviews that finished in Agile now appear under New DRR &rarr; Closed.</strong> When a change order (DCO) and its DRR are Implemented, Agile pushes the document&rsquo;s Next Review Date years into the future &mdash; which used to drop the document out of the dashboard&rsquo;s &ldquo;due soon&rdquo; window entirely, so a completed review vanished instead of landing in Closed. Toolkit-tracked reviews now stay on the dashboard through their closure regardless of the review-date window.' },
            { badge: 'improve', text: '<strong>DRR and DCO status is now a clear colored badge</strong> (green Implemented, purple Release, amber CCB/Submit&hellip;) instead of faint grey text &mdash; you can see at a glance where each change sits. A change order that&rsquo;s been <em>Released but not yet Implemented</em> (and whose DRR isn&rsquo;t closed) stays in <strong>In Process</strong> and now says so explicitly (&ldquo;DCO Released &middot; awaiting implementation&rdquo;), so it&rsquo;s clear the change cleared CCB and just needs to be incorporated.' },
            { badge: 'fix', admin: true, text: '<strong>Implementation:</strong> two root causes. (1) <code>dataForAdmin</code> now unions in every toolkit-tracked (queueStore) document that the windowed <code>pullDocsDueWithin</code> didn&rsquo;t return, via a new <code>pullDocsByNumbers</code> that pulls the latest DRR at <em>any</em> statustype (the window query&rsquo;s <code>statustype IN (0,1,2)</code> excludes Implemented=4, which would otherwise strip the doc&rsquo;s DRR link). (2) <code>imsreview-classify.js</code> gains <code>imsAgileClosed</code>: a toolkit review whose DRR or DCO reached a terminal Agile status (Implemented/Complete/Closed/&hellip;) classifies as <code>new.closed</code>, overriding the queue status &mdash; the toolkit never sees the post-submit Agile workflow. Verified against DCO-523133 / 03-32-WW-02-00074; +4 unit tests.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS review &mdash; edit 5 SDSM document attributes right on the change order',
        items: [
            { badge: 'new', text: '<strong>The &ldquo;SDSM IMS Document&rdquo; section of the change-order form is now editable.</strong> When you start a change order, you can update <em>VAR Type</em>, <em>Product Group</em>, <em>Spec</em>, <em>Product</em> and <em>PM Checklist</em> directly &mdash; single-select for VAR Type and PM Checklist, searchable multi-pick for Product Group and Spec, and a live item search for Product. Each field is pre-filled with the document&rsquo;s current value and offers exactly the choices Agile allows.' },
            { badge: 'new', text: '<strong>Your edits are written back to Agile.</strong> When the change order is created, the values you set are applied to the document&rsquo;s redline &mdash; no separate trip into Agile to update the attributes.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> extends the rich-DCO path across both services. Toolkit form (<code>imsreview-dco-form.js</code>) collects <code>varType/pmChecklist</code> + <code>productGroups/specs/products</code>; catalogs come from <code>AdminListCacheService</code> (4 new named lists) so options match Agile exactly, and Product uses a new <code>ItemSearchService</code> (<code>/api/agile/items/search</code>) typeahead. <code>DcoRichCreationService</code> STEP 5.7 writes the 5 cells onto the document redline via <code>getAvailableValues()&rarr;setSelection()&rarr;setValue()</code> (cells 1545/1547/251746287/251746291/251747721, all <code>@Value</code>-overridable). Skips empty values so a prefill miss can&rsquo;t wipe live data; every write is best-effort and never aborts the DCO. Branch <code>feat/ims-sdsm-edit-fields</code> (both repos) &mdash; QA validation of list-library names + cell ids pending.' }
        ]
    },
    {
        date: 'July 9, 2026',
        title: 'IMS review &mdash; the Document Details panel now shows live values',
        items: [
            { badge: 'improve', text: '<strong>The &ldquo;IMS Document Details&rdquo; reference panel on the review response page is now fully populated from Agile in real time.</strong> Title Block, More Info and Document Details attributes &mdash; Document Category, Product Line, Rev Release Date, Old Document Number, Create User/Date, APDS, Last/Next Review, Site Location, Function/Sub Function, Subcontractors, Document Style, Document Classification, VAR Type, Support document category/type &amp; number, PM Checklist, Applies to all Part Numbers, Part Number, Referenced Documents and more &mdash; used to render as a faint &ldquo;&mdash;&rdquo; and now show the document&rsquo;s actual current values, matching what you see in the Agile web client.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ImsDocDetailsService</code> live-reads the panel attributes in one self-contained SQL pass keyed by item number, resolving <code>LISTENTRY</code> single/cascade/multi-value lists, <code>AGILEUSER</code> owners and CSV item references to display labels in-database, with timestamps converted UTC&rarr;America/Los_Angeles to match Agile. Column map (ITEM/PAGE_TWO/PAGE_THREE/AGILE_FLEX/REV) verified live against agqa for <code>03-32-WW-02-00074</code> and the <code>28-01-SM-*</code> PM-checklist docs vs their Excel exports. Merged into <code>/api/ims-review/token/info</code>; runs on the primary datasource (agqa on QA, agprod in prod) and is fail-soft &mdash; a lookup error just leaves the panel placeholders and never breaks the response page.' }
        ]
    },
    {
        date: 'July 8, 2026',
        title: 'Agent API &mdash; read-only gateway for AI agents',
        items: [
            { badge: 'new', text: '<strong>A new machine-facing API lets an approved AI agent discover and read PLM data on its own.</strong> A single discovery endpoint (<code>/api/agent/catalog</code>) lists every available read-only endpoint &mdash; item &amp; part search, change history, BOM explode/implode, revisions, ECO timelines, document search, SKU lookups, report data, and document/attachment downloads &mdash; so the agent can navigate by intent rather than a hand-coded query list.' },
            { badge: 'new', text: '<strong>Locked down by default.</strong> Every call needs an API key; with no key configured the whole surface is closed. The gateway is strictly read-only &mdash; no writes, no emails, no exports &mdash; and exposes only business data any SanDisk employee could already see. It does not touch user-permissions, activity logs, or admin data.' },
            { badge: 'improve', text: '<strong>Rate-limited with honest signalling.</strong> When traffic is throttled the response says so explicitly, so the agent tells its user &ldquo;this answer is incomplete&rdquo; instead of quietly answering from partial data. Every call is recorded in the activity log.' },
            { badge: 'new', admin: true, text: '<strong>Implementation:</strong> new <code>/api/agent/*</code> surface &mdash; <code>AgentApiController</code> delegates in-process to existing read-only service beans; <code>AgentApiKeyGuard</code> (multi-key, constant-time, per-key audit label from <code>app.agent.api-keys</code>/<code>-labels</code>, blank&rarr;503) + <code>AgentRateLimiter</code> (independent DATA/FILES fixed-window buckets, <code>app.agent.rate.*</code>) + <code>AgentEndpointRegistry</code> (single source of truth for <code>/catalog</code>, drift-guarded by a parity test). <code>AuthFilter</code> exempts <code>/api/agent/</code> (key-gated in the controller); 429s carry <code>Retry-After</code> + a relayable <code>endUserMessage</code>; file bytes proxy via <code>AgileItemFilesClient</code>/<code>SdsmFileService</code> and 503 when plm-agile-service is down. Spec/plan: <code>docs/superpowers/{specs,plans}/2026-07-08-atwork-agent-api*</code>.' }
        ]
    },
    {
        date: 'July 8, 2026',
        title: 'Announcements &mdash; PLM IT broadcast with an AI email composer',
        items: [
            { badge: 'new', admin: true, text: '<strong>Admin &#9662; &rarr; Announcements:</strong> compose an update in rough notes, let AI wordsmith it and generate a branded HTML email, preview exactly what recipients get, send a test to yourself, then send now or on a schedule. Audiences: everyone who has ever logged in, the full access DL, or hand-picked users. Every send Ccs <code>pdl-plm-admin</code> and lands in the activity log.' },
            { badge: 'new', admin: true, text: '<strong>Safety rails:</strong> the real Send button stays locked until a test email has gone out for the current revision &mdash; any edit re-locks it. Scheduled announcements are shared drafts any PLM IT member can edit until they fire, with a who/when edit trail. AI output is scrubbed server-side so credentials can never reach an email body.' },
            { badge: 'new', text: '<strong>In-app banner:</strong> announcements can also surface as a dismissible banner on your next login (it shows until you dismiss it, once per person).' }
        ]
    },
    {
        date: 'July 8, 2026',
        title: 'Overdue Tracker &mdash; stop counting Pending ECNs',
        items: [
            { badge: 'fix', text: '<strong>The Overdue Tracker no longer flags ECNs that are sitting in <em>Pending</em></strong> (PT-114, Jimmy). It now only counts ECNs at Submittal and beyond (Submitted / Review / Release / Hold). An ECN that had been submitted and then kicked back to Pending kept a submit date, so its cycle-time clock kept ticking and it showed up as falsely overdue &mdash; those are now excluded.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>pickOverdueEcns</code> in <code>OverdueTrackerService</code> adds a parameterized <code>sn.name NOT IN (…)</code> guard in <em>open</em> mode driven by the new <code>app.overdue.excluded-statuses</code> property (default <code>Pending</code>, case-sensitive to <code>agile.nodetable.name</code>, blank disables). Retrospective/released mode is untouched &mdash; it already requires <code>release_date IS NOT NULL</code> so Pending can&rsquo;t appear. Config knob lets the exact status label be tuned server-side without a rebuild if Agile spells it differently.' }
        ]
    },
    {
        date: 'July 8, 2026',
        title: 'IMS Review &mdash; &ldquo;No DRR Required&rdquo; tile (QA preview)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>The &ldquo;No DRR Required&rdquo; box can now be hidden from the IMS Review dashboard&rsquo;s Need&nbsp;DRR section</strong> (PT-123, Jimmy). Documents that are exempt by style don&rsquo;t need tracking. This is currently <strong>enabled on QA only</strong> for evaluation &mdash; on prod the tile still shows until the change is promoted.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the <code>no_drr_required</code> tile stays in <code>imsreview.js</code>&rsquo;s KPI-strip config; <code>renderKpiStrip</code> drops it when the server sets <code>meta.hideNoDrrTile</code>. That flag is driven by the new <code>app.ims.hide-no-drr-tile</code> property (default <code>false</code> = visible), echoed from <code>ImsReviewController#/role</code>. Flip it to <code>true</code> in a box&rsquo;s external config to hide the tile there &mdash; no rebuild. The classifier in <code>imsreview-classify.js</code> still buckets exempt rows regardless.' }
        ]
    },
    {
        date: 'July 8, 2026',
        title: 'Team Report &mdash; server memory overhaul (OOM fix)',
        items: [
            { badge: 'fix', text: '<strong>Fixed the crashes that were restarting the toolkit</strong> (most recently Jul 7 evening, right after a Team Report generation). Root cause: every Team Report read &mdash; opening the tab, switching months, PPTX export, AI analysis &mdash; parsed the full workbook into memory at a measured cost of <strong>~1 GB per view</strong>. Those reads now stream the file instead (<strong>~4 MB</strong>, measured on the same July workbook) and the result is cached, so re-opening the tab costs nothing.' },
            { badge: 'improve', text: '<strong>Faster downloads after generating:</strong> the generated XLSX and discrepancies DOCX now download from the server&rsquo;s saved copy instead of being embedded inside the response &mdash; same files, same names, ~20 MB less data per generation.' },
            { badge: 'improve', text: '<strong>Overload protection:</strong> if server memory is genuinely tight, Team Report views and generations respond with a clear &ldquo;try again in a minute&rdquo; notice instead of risking a crash for everyone. Heavy report scans also queue one-at-a-time behind the scenes.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>extractTeamReportData</code>/<code>extractGroupedForMonth</code> rewritten from XSSFWorkbook DOM to POI&rsquo;s SAX event model (<code>XSSFReader</code> + <code>XSSFSheetXMLHandler</code>); derived /data payload LRU-cached per stash file keyed by path+mtime+size (analysis/meta sidecars stay uncached &mdash; regenerate-AI rewrites them without touching the xlsx); all scans single-flight behind a <code>Semaphore(1)</code> + <code>MemoryGuard</code> gate (503 <code>HEAP_BUSY</code>); generate envelope ships stash download URLs with base64 only as stash-failure fallback; discrepancies DOCX stashed alongside the xlsx with the same stamp prefix; <code>ItemCacheService.seed()</code> no longer double-copies 786K records; JVM adds <code>-XX:+UseStringDeduplication</code> + <code>-XX:InitiatingHeapOccupancyPercent=30</code> and heap dumps auto-prune after 14 days. Analysis: <code>docs/superpowers/plans/2026-07-08-prod-oom-team-report-analysis.md</code>.' }
        ]
    },
    {
        date: 'July 7, 2026',
        title: 'AI Help &mdash; Engineering Insider mode',
        items: [
            { badge: 'new', text: '<strong>A new Engineering mode inside the AI Help drawer</strong> for holders of the new <em>Engineering Insider</em> role: ask in-depth questions about how the toolkit is built &mdash; architecture, data flows, the AI stack, what it can and can&rsquo;t do today, and what it could do in the future. Hypotheticals welcome. Answers run on <code>Claude Opus 4.8</code> and each reply shows the model that produced it. Users without the role see the help drawer exactly as before.' },
            { badge: 'new', admin: true, text: '<strong>Granting the role:</strong> User Management has a new <em>Roles</em> column with an Engineering Insider toggle &mdash; visible to PLM IT only (<code>app.roles.engineering-insider.granters</code>). General PLM admins and permissions admins cannot grant it; the server enforces this with a 403, and every grant/revoke is activity-logged as <code>ROLE_GRANT</code>/<code>ROLE_REVOKE</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Guardrails:</strong> Engineering mode answers from a dedicated, role-gated knowledge file and is hard-instructed to never reveal credentials or reproduce actual source code &mdash; it describes design and behavior in prose only. Questions are logged as <code>ENG_INSIDER_ASK</code> with length + model, never the text.' }
        ]
    },
    {
        date: 'July 7, 2026',
        title: 'Configuration hardening',
        items: [
            { badge: 'improve', text: '<strong>Service credentials and access keys are now supplied exclusively by server-side configuration.</strong> The application package no longer carries embedded connection defaults &mdash; nothing changes in how any tab works.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the bundled <code>application.properties</code> ships with blank values for all credential keys (DB, LDAP, API keys, tokens); each environment&rsquo;s <code>--spring.config.additional-location</code> file is now the single source of those values. <code>config/application-prod.properties</code> in git is a pure placeholder template.' }
        ]
    },
    {
        date: 'July 7, 2026',
        title: 'Team Report — one-click &ldquo;Generate Last Month&rsquo;s Report&rdquo;',
        items: [
            { badge: 'new', text: '<strong>One button, zero inputs.</strong> A new <em>&#9889; Generate Jun 2026 Report</em> button in the Volume Reports header builds last month&rsquo;s Team Report in a single click &mdash; no month to type, no file to upload, no dates to pick. The server picks the last complete calendar month, rolls forward from your most recent saved report (the same file the drawer asks you to re-upload), and runs your team&rsquo;s numbers for that month. The XLSX + discrepancies DOCX download exactly as before, and the report lands in Recent Reports &mdash; which is what seeds <em>next</em> month&rsquo;s one-click.' },
            { badge: 'new', text: '<strong>Generating for another manager&rsquo;s team:</strong> open the Generate Team Report drawer, pick the manager, and use the new <em>&#9889; Skip the form</em> shortcut at the top &mdash; same one-click defaults, applied to that manager. The full drawer form stays available for custom months or a hand-picked prior file.' },
            { badge: 'improve', text: '<strong>The Team Report tab&rsquo;s Generate button no longer dead-ends on a cold session.</strong> Where it used to say &ldquo;run a Volume Reports query first&rdquo;, it now offers to generate last month&rsquo;s report in one click right there &mdash; the query-first walk-through remains as the Cancel path for custom months or another manager.' },
            { badge: 'improve', text: '<strong>The Team Report tab now nudges when a completed month is missing.</strong> If it&rsquo;s July and the newest report on disk is May, an amber banner says so and offers <em>&#9889; Generate Jun 2026 now</em> in place &mdash; the Excel + discrepancies download as usual, and the tab refreshes itself onto the new month when done (also after any drawer generation).' },
            { badge: 'fix', text: '<strong>The Team Report tab could stay stuck on an older month even after a newer report existed</strong> &mdash; the month list was sorted alphabetically, and &ldquo;May&rdquo; sorts after &ldquo;Jun&rdquo;. It now sorts chronologically, so the tab always opens on the newest generated report.' },
            { badge: 'fix', text: '<strong>Re-applied:</strong> reports generated before the per-PCM AI workload existed now auto-upgrade their AI analysis on the next tab open (this fix was lost from an earlier build).' },
            { badge: 'improve', text: '<strong>One-click now confirms before it runs.</strong> A preflight dialog shows exactly who the report will cover (&ldquo;<em>Noraida Nazri&rsquo;s 6 direct reports: &hellip;</em>&rdquo;), which saved workbook rolls forward, and lets you switch the manager from a dropdown. If the chosen account has no direct reports in AD, it says so up front instead of producing an all-zeros report. Generate stays disabled until the selection makes sense.' },
            { badge: 'new', text: '<strong>Every report now says whose team it covers.</strong> The Team Report header shows a &ldquo;<em>Noraida Nazri&rsquo;s team</em>&rdquo; pill, and downloaded files carry the manager in the name (<code>Team_Report_2026_Jun__Noraida_Nazri.xlsx</code>). Reports generated before this update simply omit the pill.' },
            { badge: 'fix', text: '<strong>The tab&rsquo;s Excel export could hand you the wrong file.</strong> It used to scan your own saved-reports folder by name substring &mdash; which could match a quarantined backup or miss the report the tab was actually showing (if another user generated it). It now downloads exactly the workbook the tab renders, and backup/sidecar files are excluded from Recent Reports listings and downloads.' },
            { badge: 'fix', text: '<strong>Excel no longer shows the &ldquo;Removed Part: pivotTable&rdquo; repair dialog on generated reports.</strong> The AI post-process step was re-saving the workbook through a library that subtly corrupts pivot-table internals; chained one-click generations compounded the damage until Excel had to strip a pivot. Pivot tables and chart styling now pass through that step byte-for-byte untouched (server-side script fix &mdash; already live).' },
            { badge: 'improve', text: '<strong>Guardrails:</strong> if there&rsquo;s no saved report to roll forward you get a clear pointer to run the drawer flow once; if your newest saved report skips a month (e.g. Apr on file, Jun requested) it asks before continuing; double-clicks and second tabs get a &ldquo;already running&rdquo; notice instead of a duplicate 2-minute run. Non-managers are told up front instead of receiving an empty report.' },
            { badge: 'fix', text: '<strong>Monthly generation was failing since early July</strong> (&ldquo;report generates but no attachment&rdquo;): the build script had gone missing from the server&rsquo;s <code>data/team-report/</code> folder, and the error was silent in the server logs. The script is restored and the failure now surfaces properly in the drawer&rsquo;s error banner.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>POST /api/team-report/generate-last-month</code> (optional <code>month</code>, <code>managerUsername</code>, <code>force</code>); the core of <code>/generate</code> is extracted into a shared <code>generateCore</code> guarded by a per-user in-flight set (429 <code>ALREADY_RUNNING</code>). Previous-month source = newest stashed xlsx strictly older than the target month across the target manager&rsquo;s and caller&rsquo;s <code>recent/</code> dirs (409 <code>NO_PRIOR_REPORT</code> / <code>STALE_PRIOR_REPORT</code>+<code>force</code>). AD direct-reports pre-check returns 409 <code>NOT_A_MANAGER</code> (the volume query otherwise &ldquo;succeeds&rdquo; with 0 rows). <code>RECENT_KEEP</code> 3&rarr;5 so same-month regens can&rsquo;t evict the roll-forward base. Response echoes a <code>defaults</code> object for the success banner.' }
        ]
    },
    {
        date: 'July 6, 2026',
        title: 'Restart ECN attachments — clearer size warning &amp; 100 MB limit',
        items: [
            { badge: 'improve', text: '<strong>The &ldquo;file too large&rdquo; warning now shows inside the Create Restart ECN dialog</strong> — right under the drop zone — instead of as a page banner behind it that was easy to miss. It names each skipped file and its actual size. Same treatment in the Restart-history <em>Add files</em> drawer.' },
            { badge: 'improve', text: '<strong>Attachment size limit raised from 25 MB to 100 MB,</strong> so larger deployment artifacts can be attached to a Restart ECN.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>restartEcn.attachment-max-mb</code> 25&rarr;100 (client guard + <code>attachmentMaxMb</code> served to the dialog); agile-service <code>spring.servlet.multipart.max-file-size/max-request-size</code> 25MB&rarr;120MB (headroom over the 100&nbsp;MB guard for multipart boundary/field overhead); toolkit multipart already 200MB. The in-dialog callout (<code>#reDropErr</code> / drawer <code>skipMsg</code>) replaces the background <code>showToast</code>.' }
        ]
    },
    {
        date: 'July 6, 2026',
        title: 'ECN Dashboard — new KPI Master tab (centralized classifications)',
        items: [
            { badge: 'new', text: '<strong>A new KPI Master tab holds every ECN classification mapping in one place</strong> — Request Classification &amp; Subclass &rarr; SLA Target (Std/Urgent), ECN Classification, and Change Type — sourced from the Agile <strong>D029-00006</strong> master sheet. Filter by any field to find a subclass fast.' },
            { badge: 'new', text: '<strong>SLA target editing now lives here.</strong> It was moved out of the Cycle Time tab into KPI Master, where PLM IT and the KPI owners (Vikas Singh, Jimmy Sessumes) can edit target days and Save as Profile / Promote to Active SLA. Everyone else sees it read-only. ECN Classification and Change Type are fixed by D029.' },
            { badge: 'improve', text: '<strong>Dashboards now derive ECN Classification and Change Type from this single lookup</strong> instead of each report computing its own, so they stay consistent as the mapping evolves.' },
            { badge: 'fix', text: '<strong>Returns Tracker Change Type no longer shows blank for in-flight ECNs.</strong> It now reads each return event&rsquo;s own Request Classification + Subclass straight from the KPI Master map, so ECNs that haven&rsquo;t landed in the completed-ECN snapshot yet still get a Change Type.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>KpiClassificationService</code> loads <code>data/ecn-report/kpi-classifications.json</code> (70 subclasses); <code>GET /api/ecn-report/kpi-classifications</code> feeds <code>kpimaster.js</code>. The editor reuses the existing <code>ecnState</code> target/profile machinery gated on <code>canEdit</code> (PLM admin OR configured editor); the Cycle Time SLA strip is now a read-only pointer. <code>weekly_rejection_report.py</code> now carries <code>requestClassification</code>/<code>requestSubclass</code> on every return event (<code>page_three.list35 &rarr; parent_entry</code>); <code>RejectionTrackerService.resolveChangeType</code> prefers those via the KPI map and falls back to the ecn_data lookup only when absent.' }
        ]
    },
    {
        date: 'July 6, 2026',
        title: 'Returns Tracker — cleaner layout &amp; sortable events',
        items: [
            { badge: 'improve', text: '<strong>Repeat Requestors and Product Teams now sit above the daily trend, each broken down by reason-code color</strong> (the same stacked-bar style as Top Product Lines), so you can see which categories drive each requestor/team at a glance.' },
            { badge: 'improve', text: '<strong>The Return-to-Pending events table is now fully sortable</strong> — it defaults to ECN# order, and clicking any column header sorts by it (click again to reverse).' },
            { badge: 'improve', text: '<strong>Product Line / Team / Requestor bar tooltips now show the % within each row</strong>, not just the count.' },
            { badge: 'improve', text: '<strong>Removed the AI-only sections</strong> (Executive Narrative, the &ldquo;excluded from AI&rdquo; callout, and the AI&harr;Audit mismatches table) to focus the dashboard on the audit view.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> all in <code>returnstracker.js</code> + the returns panel order in <code>index.html</code>; shared <code>returnsStackedBarTable</code>/<code>returnsBuildCatMatrix</code> helpers power the three stacked tiles; new <code>returnsState.sort</code> + <code>returnsSortBy()</code> for the events table.' }
        ]
    },
    {
        date: 'July 6, 2026',
        title: 'BOM Explorer — huge results no longer error out on screen',
        items: [
            { badge: 'fix', text: '<strong>Running an Explode/Where-Used on a very large assembly no longer fails with &ldquo;Unexpected end of JSON input.&rdquo;</strong> Previously the server tried to send the entire result (up to ~1M rows, a ~700&nbsp;MB response) to the browser, which could run out of memory and drop the response &mdash; leaving no results and no export buttons. The on-screen view now loads a capped preview with the true total, and the export buttons are always reachable.' },
            { badge: 'improve', text: '<strong>Clear path to the complete data.</strong> When a result is bigger than the preview, the banner shows the real row count and links straight to <strong>Export Excel</strong> or <strong>&darr; Full CSV (zip)</strong> for the full set. If a run ever still fails, you&rsquo;re offered the Full CSV download instead of a dead-end error.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>BomController</code> now serializes only the first <code>JSON_DISPLAY_CAP</code> (20K) rows to the response and returns <code>totalCount</code> + <code>displayTruncated</code>; the full set stays in <code>lastBomResults</code> for Export/Email. Frontend uses the server total in the cap banner and offers Full CSV on truncation or fetch failure.' }
        ]
    },
    {
        date: 'July 6, 2026',
        title: 'BOM Explorer — &ldquo;Full CSV (zip)&rdquo; download for huge explosions',
        items: [
            { badge: 'new', text: '<strong>Download the complete path-expanded BOM as a zipped CSV.</strong> Some assemblies &mdash; e.g. a Best Buy bundle SKU &mdash; explode to tens of millions of rows, far beyond Excel&rsquo;s 1,048,576-row sheet limit. The new <strong>&darr; Full CSV (zip)</strong> button next to Export Excel streams the entire result (every level, every path, to rock bottom) into a compressed CSV, with nothing truncated.' },
            { badge: 'improve', text: '<strong>Handles any size without slowing the app.</strong> The file streams straight to your download &mdash; it&rsquo;s never held in the browser or server memory &mdash; so a 20-million-row explosion is just a bigger (but still compact, ~a few hundred MB) zip. Note: opening it in Excel still shows only the first ~1M rows; use Power Query, pandas, or a database import to work with the full file.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>GET/POST /api/bom/export-csv</code> + <code>BomCsvStreamService</code> streams a <code>ZipOutputStream</code> to the response. Row source is hybrid: the in-memory BOM graph + item cache when the offline file is loaded (fast, no DB), else a forward-only streaming <code>CONNECT BY</code> <code>ResultSet</code> (works with just <code>bom_extract</code>). Download via hidden-iframe form POST so the browser writes to disk.' }
        ]
    },
    {
        date: 'July 2, 2026',
        title: 'Where-Used now returns every assembly (no more silent cap)',
        items: [
            { badge: 'fix', text: '<strong>A Where-Used (implosion) on a widely-used part now lists every distinct assembly that uses it.</strong> Previously the offline BOM path could stop at 200,000 rows &mdash; for a common component that meant your Excel was missing assemblies without telling you. It now returns the complete set of assemblies (each once, at its shallowest level).' },
            { badge: 'improve', text: '<strong>Much faster on hub parts, and Excel-safe.</strong> Results are de-duplicated to distinct assemblies instead of expanding every path, so a part used in a quarter-million places returns in seconds and stays within Excel&rsquo;s row limit. If a result ever does hit the (now far higher) cap, it&rsquo;s clearly flagged as truncated.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>BomExtractFileService.implodeFromFile</code> switched from per-path DFS to a distinct-node BFS (shallowest level, one representative path); row cap 200K&rarr;1,000,000 (under Excel&rsquo;s 1,048,576); new <code>wasTruncated()</code> flag propagated to the response <code>truncated</code>. Explode path unchanged.' }
        ]
    },
    {
        date: 'July 2, 2026',
        title: 'Items export — add CCB Category / Customer / Program &amp; Group',
        items: [
            { badge: 'new', text: '<strong>Four Inv/Planning fields are now available on the Items tab.</strong> Open <em>Columns</em> &rarr; &ldquo;more&rdquo; and add <strong>CCB Category</strong>, <strong>CCB Customer</strong>, <strong>CCB Program</strong>, and <strong>Group</strong> to your results and Excel export. Multi-value fields (Category/Customer/Program) show every value joined with &ldquo;; &rdquo;.' },
            { badge: 'improve', text: '<strong>These pull live from Agile.</strong> Unlike the other Items columns (which come from the nightly item extract), these four read the current values straight from the Agile item record, so they always reflect what&rsquo;s on the part today.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> resolved via correlated subqueries on <code>agile.PAGE_TWO</code> MULTILIST05/06/07 + LIST19 &rarr; <code>LISTENTRY</code> (mapping validated 300/300 against a SKU extract). Result-only columns (not filterable / no distinct); only resolved when selected. Same Oracle instance as <code>item_extract</code>, so no ETL. LISTAGG guarded with <code>ON OVERFLOW TRUNCATE</code>.' }
        ]
    },
    {
        date: 'July 2, 2026',
        title: 'ECN Dashboard — priority-split status timing, Q2 trend fix &amp; cleaner targets',
        items: [
            { badge: 'improve', text: '<strong>Avg Days by Workflow Status now splits Standard vs Urgent.</strong> Each stage tile (Submitted / Review / Release / Hold) shows Standard and <span style="color:#B8342B;">Urgent</span> average days side by side, so you can see exactly which priority is stalling at which stage instead of one blended number.' },
            { badge: 'fix', text: '<strong>Quarterly trend charts now roll into the new quarter.</strong> The last-3-completed-quarters window was anchored to the report&rsquo;s date filter, so after Q2 2026 closed the charts stayed stuck at Q1. They now advance the day a new quarter begins &mdash; Q2 2026 shows as expected.' },
            { badge: 'fix', text: '<strong>Target Days for &ldquo;Total Completed ECNs&rdquo; now shows NA.</strong> The total row was averaging two different targets into one misleading number; it now shows NA, while the Standard and Urgent rows below keep their own targets.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> all client-side in <code>ecnreport.js</code> &mdash; <code>ecnComputeCycleByStatusPanel</code> now emits std/urg sub-stats per status and <code>ecnCycleByStatusStripHtml</code> renders both; <code>ecnGetLast3CompletedQuarters</code> anchors the in-progress quarter to <code>new Date()</code> rather than <code>ecnState.dateRange.end</code>; the total-row Target Days cell is hardcoded to NA.' }
        ]
    },
    {
        date: 'July 2, 2026',
        title: 'Fix: &ldquo;Sign in to Agile&rdquo; timing out on the first save',
        items: [
            { badge: 'fix', text: '<strong>Saving an edit to Agile (go-live date, IT log note, Restart ECN, etc.) could fail the first time with &ldquo;Verify failed: SocketTimeoutException: Read timed out.&rdquo;</strong> The first per-user Agile sign-in after the service has been idle takes up to ~90 seconds to establish, but the verify step gave up at 60 seconds. The timeout is now 120 seconds, so that first sign-in completes; every sign-in after it is fast.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> raised the <code>app.ims-review.writeback-timeout-ms</code> compiled default 60s&rarr;120s in <code>AgileWriteBackClient</code> (prod&rsquo;s external config didn&rsquo;t override it, so it was silently using 60s) and added the explicit line to the prod <code>config/application.properties</code>.' }
        ]
    },
    {
        date: 'July 1, 2026',
        title: 'Restart ECN — attachments, history & collaboration',
        items: [
            { badge: 'improve', text: '<strong>Restart ECNs now stay in Pending.</strong> The ECN is no longer auto-submitted, so the team can keep adding enhancement ECNs and files before someone submits it manually in Agile when the bundle is final.' },
            { badge: 'new', text: '<strong>Attach files right from the Create dialog.</strong> Drag-and-drop or browse multiple files, tag each with one or more of the bundled ECNs, and pick an IT Attachment Type (required for .jar / .properties). Files land on the ECN&rsquo;s Attachments tab with a &ldquo;ECN#, ECN# &mdash; note&rdquo; description and live upload progress.' },
            { badge: 'new', text: '<strong>Restart history drawer.</strong> A new toolbar button lists every toolkit-created Restart ECN &mdash; each with its bundled ECNs, attachments, and an activity feed. From the detail you can <em>+ Add ECNs</em> and <em>+ Add files</em> to an existing Restart ECN (with a &ldquo;My ECNs only&rdquo; filter); email links deep-link straight to it.' },
            { badge: 'new', text: '<strong>Email notifications to pdl-plm-admin.</strong> A Created email on creation and an Updated email on every ECN/file addition, so the team can collaborate on the bundle.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> per-user attachment upload (IT Attachment Type cell 3653 / File Description 1045), append-only <code>history.jsonl</code>, <code>/restart-ecn/history|{ecn}|add-ecns|add-files</code> endpoints, agile-service <code>add-restart-attachment</code> + <code>add-relationships</code>; all Agile writes fail-soft with the needsAgileSignin retry.' }
        ]
    },
    {
        date: 'July 1, 2026',
        title: 'Browser tab shows the environment (QA/Test)',
        items: [
            { badge: 'improve', text: '<strong>Non-production tabs are now labelled in the browser tab title</strong> &mdash; e.g. &ldquo;Agile PLM Toolkit (QA)&rdquo; &mdash; so when you have Prod and QA open side by side you can tell them apart at a glance. Production is unchanged.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> reuses the existing <code>app.instance.label</code> config (empty on prod). <code>instance-badge.js</code> now also suffixes <code>document.title</code> with the label on both index and login pages; idempotent, no new config.' }
        ]
    },
    {
        date: 'July 1, 2026',
        title: 'Create Restart ECN from the IT Enhancements tab',
        items: [
            { badge: 'new', text: '<strong>Bundle your UAT-complete enhancements into a deployment ECN in one click.</strong> A new <strong>Create Restart ECN</strong> button opens a dialog where you pick a deployment date, check the enhancement ECNs going live (only those at IT Status <em>&ldquo;UAT complete, CAB Prep&rdquo;</em> are selectable), and choose an IT owner. The toolkit creates the ECN in Agile under your sign-in (Request Classification <em>Restart Agile Service</em>), links the selected ECNs on its Relationships tab, and auto-submits it &mdash; replacing the ~15-click manual process.' },
            { badge: 'new', text: '<strong>Proposal is written for you.</strong> It reads &ldquo;Deployment &lt;date&gt;:&rdquo; followed by an AI-condensed one-line summary of each bundled ECN; the Problem Statement lists the deployed ECN numbers.' },
            { badge: 'improve', text: '<strong>Separation of duties on the IT owner.</strong> The IT-owner picker only offers PLM IT team members who don&rsquo;t already own one of the bundled ECNs, and updates live as you change the selection.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>plm-agile-service</code> <code>POST /api/change/create-restart-ecn</code> (per-user PX session &rarr; create ECO/ECN subclass &rarr; cover cells &rarr; Relationships &rarr; auto-submit, fail-soft per step); toolkit <code>GET/POST /api/it-enhancements/restart-ecn(-candidates)</code> with server-side re-validation, AI proposal via the Portkey seam (fail-soft to raw description). Cover-page cell IDs are config (<code>agile.restartEcn.cell.*</code>) &mdash; confirm on QA.' }
        ]
    },
    {
        date: 'July 1, 2026',
        title: 'Returns Tracker — past-period snapshots & AI-vs-audit classification',
        items: [
            { badge: 'new', text: '<strong>Revisit prior reports from a Period dropdown.</strong> Weekly, monthly, and quarterly Returns Tracker snapshots are frozen as they&rsquo;re generated, so you can pull up &ldquo;June 2026&rdquo; or &ldquo;Q2 2026&rdquo; on demand even after the rolling 90-day window ages those ECNs out of Agile.' },
            { badge: 'new', text: '<strong>Audit-enforced vs AI-inferred, side by side.</strong> A toggle switches every panel between the human-entered audit reason code (<code>ID:/II:/WI:/DR:/RR:</code>) and the AI&rsquo;s inference. An <strong>AI&harr;Audit agreement %</strong> and a mismatch list show exactly where the two disagree.' },
            { badge: 'new', text: '<strong>Default view starts at the audit go-live (Jun 24, 2026),</strong> with a <em>Legacy (pre-audit)</em> preset for older data &mdash; so the new-requirements picture stays clean while legacy stays reachable.' },
            { badge: 'fix', text: '<strong>Excel export now reflects the agreed classification.</strong> The Report sheet shows both AI and Audit categories plus the classification source, and the Categories reference sheet uses the current taxonomy (no more retired &ldquo;Ambiguous Request&rdquo;).' },
            { badge: 'fix', text: '<strong>Fixed Refresh failing on the new range presets.</strong> Clicking Refresh while on &ldquo;Since audit go-live&rdquo; (or Legacy / This Quarter / Custom) no longer errors out &mdash; the refresh now maps the view to a valid narrative window instead of passing the UI label straight through.' },
            { badge: 'fix', text: '<strong>Events table now matches the selected classification.</strong> The Category column (and its colour) follow the Audit/AI toggle, so &ldquo;No audit code&rdquo; rows in the Categories panel now show matching rows in the table below instead of the AI-inferred label.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> per-event <code>aiCategory</code>/<code>auditCategory</code>/<code>categorySource</code>; <code>/api/ecn-report/returns/periods</code> + <code>?period=</code> load frozen snapshots; snapshots written by the weekly/monthly scheduler and back-filled once from the retained cache; go-live date via <code>app.returns.audit-golive-date</code>.' }
        ]
    },
    {
        date: 'June 30, 2026',
        title: 'Send New DRR — Prod guardrail',
        items: [
            { badge: 'fix', text: '<strong>&ldquo;Send New DRR&rdquo; can now be turned off per environment.</strong> On sites where DRR creation isn&rsquo;t cleared for go-live yet, the button is hidden and the action is blocked server-side &mdash; create and test DRRs on the QA toolkit instead. Existing review and notification emails are unaffected.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> config flag <code>app.ims-review.drr-creation-enabled</code> (default <code>true</code>; set <code>false</code> in the Prod config). Surfaced as <code>drrCreationEnabled</code> in the dashboard data; <code>ImsReviewService.sendNewDrr</code> rejects when off; the row action shows &ldquo;DRR creation off (use QA)&rdquo;.' }
        ]
    },
    {
        date: 'June 30, 2026',
        title: 'Obsolete (OBS) change orders — OB rev & field defaults',
        items: [
            { badge: 'improve', text: '<strong>Obsoleting a document now stamps the new revision as &ldquo;OB.&rdquo;</strong> When you submit an OBS change order, the affected item&rsquo;s New Rev is set to the literal <code>OB</code> instead of the normal next-rev bump.' },
            { badge: 'improve', text: '<strong>OBS forms pre-fill the right defaults</strong> &mdash; Training Requirement is set to <em>Self study email</em> and Change Impact Disposition to <em>No</em> (both still editable before you submit).' }
        ]
    },
    {
        date: 'June 30, 2026',
        title: 'IMS Dashboard & DCO form — review round 2',
        items: [
            { badge: 'new', text: '<strong>&ldquo;No DRR required&rdquo; tag for exempt documents.</strong> Docs whose Document Style is <em>Specs</em>, <em>Drawing</em>, or <em>Record - for dead document&hellip;</em> never need a DRR &mdash; those rows now show a grey &ldquo;No DRR required&rdquo; tag instead of the Send New DRR button, and roll up into a new <strong>No DRR Required</strong> tile under Need DRR. (Mirrors the Document-Review exemption rule; the style list is configurable.)' },
            { badge: 'improve', text: '<strong>Tiles now lead with &ldquo;In Process.&rdquo;</strong> New DRR: In Process &rarr; Pending Response &rarr; Need Owner &rarr; Need Help &rarr; Closed. Legacy DRR: In Process &rarr; Pending Response &rarr; Need Owner.' },
            { badge: 'new', text: '<strong>Self-study training guide is now linked</strong> next to the Training Requirement field in the Create-DCO form and in the Document-Owner review email.' },
            { badge: 'improve', text: '<strong>SDSM labels.</strong> &ldquo;Business Unit&rdquo; is now &ldquo;SDSM Business Unit,&rdquo; and the &ldquo;IMS Document &mdash; Edit&rdquo; section is now &ldquo;SDSM IMS Document &mdash; Edit.&rdquo;' },
            { badge: 'improve', text: '<strong>Obsolete (OBS) change orders are simpler.</strong> The OBS form now asks only for the Final Version + Stakeholder Notification &mdash; no redline copy.' },
            { badge: 'improve', text: '<strong>Observers are now optional</strong> on the Create-DCO form.' },
            { badge: 'fix', text: '<strong>Cleaner emails.</strong> Removed the red SanDisk logo from every IMS email footer, and rebuilt the &ldquo;Submit Response&rdquo; button so it renders correctly in Outlook Classic.' },
            { badge: 'fix', text: '<strong>Tidied the Stakeholder Notification field</strong> &mdash; removed the duplicate grey hint text inside the box (the same note already sits above it).' }
        ]
    },
    {
        date: 'June 30, 2026',
        title: 'Backlog grade, revamped — explainable & disputable',
        items: [
            { badge: 'new', text: '<strong>The Meeting-Mode Backlog grade is now a full panel</strong> with a plain-English &ldquo;How is this calculated?&rdquo; explainer that prints the live formula, and a <strong>trend vs the last review</strong>.' },
            { badge: 'new', text: '<strong>At-risk tab &mdash; dispute any item.</strong> The AI re-grades only on verifiable proof: it cross-checks your reason against the ECN&rsquo;s Agile record first (no upload needed when the record backs you up), asks for a screenshot when the claim rests on the ECN&rsquo;s wording, or accepts independent evidence (a date, owner, CCB/policy note). Weak arguments are rejected.' },
            { badge: 'new', text: '<strong>Dispute a whole rule</strong> and, with proof, the grading logic changes at run time (overdue grace period, unowned-high-priority weighting, or the missing-dates penalty) &mdash; with one-click <strong>revert</strong>.' },
            { badge: 'new', text: '<strong>History &amp; Outbox audit trail</strong> logs every accepted dispute and logic change with the AI&rsquo;s documented reason, the grade delta, and the email that went out. Accepted item disputes notify PLM admin; rule changes notify the IT team.' },
            { badge: 'new', text: '<strong>Pick the grading model</strong> per user &mdash; defaults to <strong>Claude Opus 4.8</strong>.' },
            { badge: 'improve', text: '<strong>Grade re-computes live</strong> as you exclude items or change rules &mdash; no reload.' },
            { badge: 'improve', text: '<strong>Ask AI is now a two-way assistant.</strong> In one plain-English message you can <strong>include and exclude</strong> items together (e.g. &ldquo;keep ECN-124234, drop the other three &mdash; reassigned per CCB-2026-120&rdquo;); it resolves categories and counts, <strong>requires a reason for every change</strong> (logged in History so the team sees why), no longer treats a bare ECN number as proof, and asks a clarifying question instead of looping when a request is ambiguous.' },
            { badge: 'fix', text: '<strong>Completed ECNs no longer count as overdue.</strong> An enhancement whose IT Status is <em>Completed</em> (or Complete / Closed / Released / Cancelled) is now treated as finished &mdash; it drops out of the grade instead of showing as an overdue at-risk item.' },
            { badge: 'fix', text: '<strong>Ask AI no longer jumps to the top</strong> after you send a message (PT-103) &mdash; the chat stays pinned to the latest reply, and the other tabs keep their scroll position across updates.' },
            { badge: 'new', text: '<strong>Standing theme policies.</strong> State a general rule about a <em>class</em> of ECNs &mdash; e.g. &ldquo;don&rsquo;t grade any ECN whose description says no Agile IT help is needed&rdquo; &mdash; in the rule box or Ask AI. The AI judges it on common sense (no per-ECN screenshot), applies it across every matching ECN, tells you <strong>how many are no longer being considered for grading</strong>, and <strong>re-applies it automatically on every future grade</strong> until you revert it in History.' }
        ]
    },
    {
        date: 'June 29, 2026',
        title: 'Copy fix, clickable DCO, Outlook-friendly stakeholder field',
        items: [
            { badge: 'fix', text: '<strong>Click-to-copy now works over plain http.</strong> The copy icon on Doc / DRR / DCO numbers fell back to &ldquo;Copy failed&rdquo; on the (non-secure) QA/prod hosts; it now uses a legacy-copy fallback so the number lands on your clipboard.' },
            { badge: 'fix', text: '<strong>The DCO number on the &ldquo;Your response is recorded&rdquo; page is now a clickable Agile link</strong> instead of plain text.' },
            { badge: 'fix', text: '<strong>The stakeholder-notification field now accepts Outlook-pasted names.</strong> Pasting &ldquo;Kolam, Bibi Anita &lt;bibi.anita.kolam@sandisk.com&gt;; &hellip;&rdquo; as-is used to fail with &ldquo;not a valid email address&rdquo;; the email addresses are now pulled out automatically (display names and commas-in-names handled).' }
        ]
    },
    {
        date: 'June 29, 2026',
        title: 'Send New DRR from the dashboard',
        items: [
            { badge: 'new', text: '<strong>Send New DRR</strong> button on &ldquo;Need DRR&rdquo; rows creates the DRR in Agile and sends the review to the document owner &mdash; the row moves to New DRR / Pending Response. (Shows &ldquo;reassign owner first&rdquo; when every owner has left.)' }
        ]
    },
    {
        date: 'June 29, 2026',
        title: 'IMS Dashboard refinements (round 2)',
        items: [
            { badge: 'improve', text: '<strong>&ldquo;New DRR&rdquo; now means toolkit-handled.</strong> A doc counts as New only once it&rsquo;s been sent through the dashboard or has a toolkit-created DCO &mdash; pre-existing DRRs the toolkit hasn&rsquo;t touched stay under Legacy.' },
            { badge: 'improve', text: '<strong>Tile sub-states sit on one line</strong> under each group header (more compact boxes).' },
            { badge: 'improve', text: '<strong>DRR &amp; DCO status shown inline</strong> in (brackets) under the change number; dropped the separate DRR Owner, DCO Status and DCO Owner columns.' },
            { badge: 'fix', text: '<strong>Removed the &ldquo;review &amp; reassign owners&rdquo; banner</strong> and moved the copy icon to the front of each number.' }
        ]
    },
    {
        date: 'June 29, 2026',
        title: 'IMS Dashboard refinements',
        items: [
            { badge: 'improve', text: '<strong>Summary tiles regrouped into New DRR / Legacy DRR / Need DRR</strong>, each with its own sub-states.' },
            { badge: 'improve', text: '<strong>Removed the redundant Not sent / In flight / Closed funnel</strong> and the go-live date filter row.' },
            { badge: 'new', text: '<strong>Data table now shows DRR Owner, DRR Created, DCO, DCO Status, DCO Owner and DCO Created columns.</strong>' },
            { badge: 'new', text: '<strong>Click-to-copy on every Doc / DRR / DCO number; per-column filters on all columns.</strong>' },
            { badge: 'fix', text: '<strong>DRR / DCO / Next Review dates now display as MM-DD-YYYY</strong> without a timestamp.' }
        ]
    },
    {
        date: 'June 26, 2026',
        title: 'OBA Required-Doc API &middot; machine retrieval of shipping label + checklist',
        items: [
            { badge: 'new', text: '<strong>Auto OBA document API.</strong> A keyed endpoint (<code>GET /api/oba/required-docs?sku=&hellip;</code>) resolves a SKU&rsquo;s Outer Shipping Label proof (L000), its D026 label specification, and the General SSD OBA Checklist, returning per-file download links the Auto OBA Buyoff System can pull.' },
            { badge: 'new', text: '<strong>Per-file download.</strong> <code>GET /api/oba/file?item=&hellip;&amp;name=&hellip;</code> streams a single attachment (PDF/docx) straight from Agile.' },
            { badge: 'new', text: '<strong>&ldquo;Attachment missing&rdquo; signal.</strong> Each document carries a <code>status</code> (<code>ok</code> / <code>attachment-missing</code> / <code>content-unavailable</code> / <code>agile-item-not-found</code>) plus a top-level <code>attachmentMissing</code> flag &mdash; so when an environment has the part record but not the actual file, the caller is told exactly which item needs the file added.' }
        ]
    },
    {
        date: 'June 25, 2026',
        title: 'Meeting Mode &middot; meeting lifecycle, undo, record management',
        items: [
            { badge: 'new', text: '<strong>Cancel a live meeting.</strong> A <em>Cancel</em> control in the live bar discards the session &mdash; drops every captured update, reverts the in-meeting date/status edits, and returns to pre-meeting. Nothing is written to Agile and no email is sent (writes only ever commit at Wrap up).' },
            { badge: 'new', text: '<strong>Undo a single update or note.</strong> Each meeting-log row has <em>undo ↺</em> &mdash; it removes that captured bubble, updates the counters, and reverts the underlying Target UAT / Go-Live change. Notes just drop.' },
            { badge: 'new', text: '<strong>Delete and consolidate meeting records (PLM admins).</strong> Admins can delete a record (soft-delete with an audit trail; never touches Agile) and merge two or more same-day records into one &mdash; totals summed, logs and action items combined, originals replaced.' },
            { badge: 'new', text: '<strong>IT-status filter.</strong> A multi-select <em>IT status</em> dropdown joins the urgency pills and Requestor/Priority filters; all of them AND together with the search.' },
            { badge: 'improve', text: '<strong>My Enhancements is sorted attention-first.</strong> A <em>Needs your attention</em> group (overdue UAT/Go-Live or high priority, with reason chips and a red marker) sits above <em>On track</em>, sorted by priority then UAT date.' },
            { badge: 'new', text: '<strong>Notes from earlier meetings.</strong> The detail-pane Notes area now shows a timeline of the meeting-only notes captured on this ECN in prior sessions &mdash; author, date, and a &ldquo;never sent to Agile&rdquo; tag.' },
            { badge: 'fix', text: '<strong>The list keeps its scroll position.</strong> Clicking a row to discuss it no longer jumps the enhancement list back to the top &mdash; it only resets when you change a filter or the search.' },
            { badge: 'improve', text: '<strong>Undo is easier to spot.</strong> The per-update <em>↺ Undo</em> in the meeting log is now a clear button (tints red on hover) instead of small underlined text.' }
        ]
    },
    {
        date: 'June 25, 2026',
        title: 'Meeting Mode &middot; workspace upgrades (filters, resize, full screen)',
        items: [
            { badge: 'new', text: '<strong>Urgency filter pills on the meeting list.</strong> Filter the left rail by <em>Overdue UAT / Go-Live</em>, <em>Approaching</em>, <em>No IT owner</em>, <em>Live &middot; verify in prod</em>, or <em>On track</em> &mdash; each with a live count &mdash; plus a search box (ECN / title / requestor / owner) that combines with the active pill.' },
            { badge: 'new', text: '<strong>Resizable list.</strong> Drag the divider between the list and the detail pane to widen it (220&ndash;600px); the width is remembered across reloads. Rows now show a 3-line description and a <em>{status} &middot; UAT {date}</em> line, with a colored left border for urgency.' },
            { badge: 'new', text: '<strong>Full screen.</strong> A <em>⤢ Full screen</em> toggle (in the pre-meeting and live bars) hides the top bar, page title, and view pills so the workspace fills the screen; the session controls and drawers keep working. <em>⤡ Exit full screen</em> restores everything.' },
            { badge: 'improve', text: '<strong>Taller workspace.</strong> The meeting workspace now sizes to the viewport and each column scrolls on its own, so you see more of the backlog at once.' },
            { badge: 'new', text: '<strong>Requestor and Priority filters.</strong> Two multi-select dropdowns next to the urgency pills let you narrow the list to specific requestors and/or priorities (they combine with the active pill and search). Priority now shows on each row and in the detail header.' },
            { badge: 'fix', text: '<strong>Backlog grade is fairer and explainable.</strong> Missing target dates is now a soft signal (early-stage items legitimately have none), so the grade reflects real risk &mdash; overdue and unowned high-priority items. <strong>Click any breakdown row to list the exact ECNs</strong> (with Agile links); the missing-dates list shows which date each is missing.' },
            { badge: 'fix', text: '<strong>&ldquo;Overdue UAT&rdquo; now respects the IT Status.</strong> An item already in UAT or past it (e.g. <em>UAT complete, CAB Prep</em>) is no longer flagged Overdue UAT just because its Target UAT date is in the past &mdash; that&rsquo;s expected once UAT has been reached. Same for go-live on items already live. Overdue now means a stage that hasn&rsquo;t been reached by its target date.' }
        ]
    },
    {
        date: 'June 24, 2026',
        title: 'IT Enhancements &middot; Meeting Mode &mdash; live sessions, bubbles &amp; meeting records',
        items: [
            { badge: 'new', text: '<strong>Run the backlog review as a live session.</strong> The IT Enhancements tab now has four views: <em>All Enhancements</em> (the existing grid, unchanged), <em>My Enhancements</em>, <em>Meeting Mode</em>, and <em>Meeting records</em>.' },
            { badge: 'new', text: '<strong>Capture updates as bubbles.</strong> In a live session you edit Target UAT / Go-Live and an IT-log line in the centre detail pane and press <em>Save to Agile</em> &mdash; each change is written to Agile under your own AD identity and docked into a running meeting log. A separate <em>Notes</em> field (with an optional assignee) records a meeting-only action item that never touches Agile.' },
            { badge: 'new', text: '<strong>Wrap up sends a recap and files a record.</strong> One consolidated email goes to everyone in the room (action items, what was saved to Agile, who was notified separately); owners/assignees who weren&rsquo;t in the room get their own heads-up. The whole session freezes into a read-only Meeting Record with action-item follow-through.' },
            { badge: 'new', text: '<strong>Grade &amp; AI agenda on demand.</strong> <em>Grade now</em> re-reads every enhancement and scores meeting readiness; <em>Build agenda</em> ranks items most-urgent-first for who&rsquo;s in the room, names the bottleneck stage, and toggles between Requestor and IT-owner framing.' },
            { badge: 'new', text: '<strong>My Enhancements.</strong> Items you own, edited inline straight to Agile (Target UAT / Go-Live / IT-log note) with a quiet &ldquo;Saved&rdquo; &mdash; no session, no email. IT Status is read-only everywhere.' },
            { badge: 'fix', text: '<strong>The browser no longer mistakes the Notes / IT-log fields for a password.</strong> Capture inputs are marked non-autofill, so Chrome stops offering to &ldquo;save password&rdquo; when you Tab out of a note.' },
            { badge: 'improve', text: '<strong>Change numbers are clickable everywhere.</strong> Every ECN in Meeting Mode, the meeting log, records, the agenda, and the wrap-up emails now opens the change in Agile (new tab).' },
            { badge: 'improve', text: '<strong>pdl-plm-admin is Cc&rsquo;d on every Meeting Mode email</strong> &mdash; the recap to the room and the separate owner notifications.' },
            { badge: 'fix', text: '<strong>My Enhancements now has a Save button per row.</strong> Edits no longer write to Agile on every field blur &mdash; you change Target UAT / Go-Live / IT-log note and click <em>Save to Agile</em>, which writes the changed fields together. This ends the &ldquo;object has been modified&rdquo; (PCAPIException 531) errors from overlapping per-field saves.' },
            { badge: 'improve', text: '<strong>Meeting Mode captures updates and writes them to Agile only at wrap-up.</strong> During a live session, editing Target UAT / Go-Live / IT-log <em>captures</em> the change (it docks into the log as &ldquo;saves on wrap-up&rdquo;); the actual Agile writes &mdash; under your AD identity &mdash; all happen when you click <em>Send</em>. You sign in to Agile once, at send. Anything that fails to write is flagged &ldquo;needs retry&rdquo; in the record and the recap email.' },
            { badge: 'new', admin: true, text: '<strong>Implementation:</strong> new <code>/api/meeting/*</code> endpoints (sessions, bubbles, wrap, send, records, action-items) backed by file-based JSON (<code>MeetingStorageService</code>); AGILE bubbles reuse the per-user <code>AgileWriteBackClient</code> write path; wrap-up emails via <code>MeetingEmailService</code>. Frontend is a vanilla-JS module (<code>meeting-mode.js</code> / <code>window.MeetingMode</code>) rendered into <code>#iteMMRoot</code>; the legacy Meeting-Mode sub-tab is retired.' }
        ]
    },
    {
        date: 'June 24, 2026',
        title: 'User Management &middot; DL invites are now manual &amp; verified',
        items: [
            { badge: 'fix', text: '<strong>Invite emails no longer fire on restart.</strong> They used to send automatically when the Permissions tab loaded after a deploy &mdash; that startup side-effect is removed.' },
            { badge: 'new', text: '<strong>Send invites on demand, with a live DL check.</strong> Each pending request has a <em>Send invite</em> action (plus bulk <em>Send all / Send selected</em>). The invite only goes out if the person is actually in the IT-APP-Agile-admin DL right now.' },
            { badge: 'improve', text: '<strong>Clear states &amp; results.</strong> Rows show Awaiting / Checking DL&hellip; / Invite sent / Not in DL yet, with an &ldquo;Add to DL ↗&rdquo; link and a summary banner naming anyone who still needs to be added.' }
        ]
    },
    {
        date: 'June 24, 2026',
        title: 'User Management &middot; add several people at once, with tab presets',
        items: [
            { badge: 'improve', text: '<strong>&ldquo;+ Add user from AD&rdquo; now queues multiple people.</strong> Search and click to add several users, then submit them together instead of one at a time.' },
            { badge: 'improve', text: '<strong>Tab presets.</strong> Start from <em>Viewer</em>, <em>Items team</em>, <em>Reporting</em>, <em>Full access</em> or <em>Blank</em>, then fine-tune by group or individual tab &mdash; granting one tab is now two clicks instead of unchecking everything.' },
            { badge: 'improve', text: '<strong>One request to IT.</strong> A single consolidated email covers everyone who still needs to be added to the access DL; people already in the DL just get their tabs set.' }
        ]
    },
    {
        date: 'June 24, 2026',
        title: 'User Management &middot; bulk import users from Excel',
        items: [
            { badge: 'new', text: '<strong>&ldquo;Import from Excel&rdquo; in User Management.</strong> Upload a roster (.xlsx / .xls / .csv) and add many users at once instead of one-by-one.' },
            { badge: 'new', text: '<strong>Columns are mapped for you.</strong> The tool reads your sheet and figures out which column is the name and which is the email &mdash; you&rsquo;re only asked to confirm when a column is ambiguous.' },
            { badge: 'new', text: '<strong>Everyone is matched against AD.</strong> Each row is resolved to a real account; people who already have access are flagged as a warning and skipped, and unclear matches let you pick the right person.' },
            { badge: 'improve', text: '<strong>One email to IT, not one per person.</strong> A single consolidated access request is sent for the whole batch, with the tabs each user will get.' },
            { badge: 'new', admin: true, text: '<strong>Implementation:</strong> new <code>/api/permissions/import/{analyze,resolve,submit}</code> endpoints. <code>UserSheetParser</code> (POI) &rarr; <code>UserColumnMapper</code> (header heuristic, Claude-Haiku fallback via Portkey) &rarr; <code>UserImportService</code> (AD resolve + dedupe) &rarr; <code>UserPermissionsService.submitBulkDLRequest</code> (N pending records, one consolidated DL email). Admin-gated like the rest of User Management.' }
        ]
    },
    {
        date: 'June 24, 2026',
        title: 'IMS Review &middot; response-page polish + obsolete (OBS) form',
        items: [
            { badge: 'improve', text: '<strong>The response page is roomier and easier to read.</strong> Wider layout so fields and values stop wrapping, larger <em>IMS Document Details</em> text, and the redundant &ldquo;7 fields / 5 fields&rdquo; counts were removed.' },
            { badge: 'improve', text: '<strong>Dashboard: the &ldquo;DRR Created&rdquo; column was removed.</strong> Pre-go-live DRRs are still flagged &mdash; the muted <em>legacy</em> badge now sits next to the DRR number instead.' },
            { badge: 'improve', text: '<strong>&ldquo;Download IMS Document&rdquo;.</strong> The download button was renamed (same function) on both the response page and the change-order form.' },
            { badge: 'new', text: '<strong>&ldquo;Don&rsquo;t need this &mdash; OBS it&rdquo; opens a trimmed form.</strong> The obsolete (OBS) flow now shows the change-order form without the <em>IMS Document &mdash; Edit</em> and <em>Documents</em> sections.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the DO email&rsquo;s <em>Submit Response</em> button is rebuilt as an Outlook-bulletproof <code>bgcolor</code> table button. OBS routing passes the picked action into <code>dcoOpen(info, action)</code> &rarr; <code>DCO.action</code>; sections 04/05 and their required-field gating are skipped when <code>DCO.action===\'OBS\'</code>. Response page <code>.wrap</code> widened to 1040px; <code>.co-ref2</code> fonts bumped. Cache-bust <code>imsreview-dco-form.js?v=20260623e</code>.' }
        ]
    },
    {
        date: 'June 23, 2026',
        title: 'IMS Review &middot; PLM-IT-only Unlock, legacy DRR tagging, and clickable numbers',
        items: [
            { badge: 'improve', text: '<strong>Older DRRs are now visible and clearly tagged.</strong> The dashboard shows all DRRs by default; those created before go-live (2026-07-05) are marked with a muted <em>legacy</em> badge so you can tell them apart at a glance.' },
            { badge: 'improve', text: '<strong>Item, change, DRR, and DCO numbers are now clickable everywhere.</strong> In the review emails, the response page, the document-details panel, and the change-order confirmation, every number links straight to the object in Agile PLM.' },
            { badge: 'improve', text: '<strong>The new-DCO email now goes to the stakeholders, with the team CC&rsquo;d.</strong> When a change order is created, the notification is addressed to the stakeholders being notified, and the Document Owner(s), Approvers, and Observers are copied (CC) so they stay in the loop.' },
            { badge: 'fix', admin: true, text: '<strong>Implementation:</strong> the &#128273; Unlock button and <code>/admin/unlock-token</code> are now restricted to <code>isPlmAdmin</code> (previously admin-or-ims-review-grant). <code>imsreview.js</code> defaults <code>drrCreatedFilterOn:false</code> and tags pre-cutoff rows via <code>isLegacyDrr</code>. Email number vars route through <code>agileLinkItem/agileLinkChange</code>; <code>agileWebclientUrl</code> was added to <code>token/info</code> + <code>dco-form-metadata</code> so the response page (<code>agileLink</code>) and the DCO success message build Agile deep-links client-side, fail-soft to plain text. Cache-bust <code>imsreview.js?v=20260623a</code>, <code>imsreview-dco-form.js?v=20260623b</code>.' }
        ]
    },
    {
        date: 'June 23, 2026',
        title: 'IMS Document Review &middot; streamlined email &rarr; response &rarr; change-order flow',
        items: [
            { badge: 'improve', text: '<strong>The review email is now a single button.</strong> Instead of three action buttons in the email, you get one prominent <em>Submit&nbsp;Response&nbsp;&rarr;</em> button that opens the secure response page, where you choose what to do. The summary table was trimmed to the essentials (Number, Description, Rev&nbsp;/&nbsp;Lifecycle, Next&nbsp;Review&nbsp;Date, Related&nbsp;DRR, Document&nbsp;Owner).' },
            { badge: 'improve', text: '<strong>A cleaner response page.</strong> Pick from four clearly-labelled options &mdash; <em>No change needed</em>, <em>Needs change &mdash; start a change order</em>, <em>Don&rsquo;t need this &mdash; OBS it</em>, and a quieter <em>I need help</em> &mdash; with a collapsible <em>IMS Document Details</em> reference panel so you can check the document&rsquo;s attributes while you decide.' },
            { badge: 'new', text: '<strong>&ldquo;Don&rsquo;t need this &mdash; OBS it&rdquo;.</strong> A new option to start an obsolete (OBS) change order when a document is no longer needed. For now it opens the standard change-order form; a dedicated retire flow is coming.' },
            { badge: 'improve', text: '<strong>The change-order form is now one scrollable page.</strong> The old step-by-step wizard is replaced by a single tabular form with six numbered, collapsible sections (Change details, Scope, People, IMS&nbsp;Document&nbsp;Edit, Documents, Review&nbsp;&amp;&nbsp;sign). Download the released document&rsquo;s attachment(s) right from the header, and a footer status line always shows what&rsquo;s still needed before you can sign.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> UI restructure across <code>ims-review-do.html</code> (single <code>${responseUrl}</code> CTA + new <code>documentOwner</code> var in <code>ImsReviewEmailService</code>), <code>ims-respond.html</code> (4-option <code>.opt-card</code> picker, shared <code>renderRefPanel</code> reference panel, header download, OBS routed to the change-order drawer), and <code>imsreview-dco-form.js</code> (the 3-step wizard flattened into a single <code>renderForm</code> of <code>coSection</code>/<code>coRow</code> rows; <code>collectForm()</code> and all <code>/token/&hellip;</code> endpoints unchanged). Section&nbsp;04 (IMS&nbsp;Document&nbsp;Edit) and the full reference-panel attribute feed are read-only stubs pending an Agile write path + extended <code>token/info</code> payload. Cache-bust <code>imsreview-dco-form.js?v=20260623a</code>.' }
        ]
    },
    {
        date: 'June 23, 2026',
        title: 'ECO Timeline &middot; new &ldquo;Primary #&rdquo; column from i2 (Blue Yonder)',
        items: [
            { badge: 'new', text: '<strong>Each component now shows its i2 primary part.</strong> A new <em>Primary&nbsp;#</em> column (right after Component&nbsp;#) shows the i2/Blue Yonder primary part <em>at the same BOM level as the component</em> &mdash; the primary of the i2 slot where the component is used, even when the component itself is listed there as a substitute. It appears in both the grouped and flat views, the detail drawer, and the Excel export, so planners can spot where Agile and i2 diverge without leaving the tool.' },
            { badge: 'improve', text: '<strong>Multiple primaries are shown when they exist.</strong> Where i2 lists more than one primary for an item, they&rsquo;re shown comma-separated; the placeholder &ldquo;Inactive Component&rdquo; is filtered out.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new read-only <code>pdcDataSource</code> (PDC schema, <code>scmprdfl</code>) + <code>I2PrimaryLookupService</code> batch-queries <code>PDC.BILLOFMATERIALS</code> for the slot <code>COMPONENTITEMID</code> where the component appears as the primary OR the substitute (<code>COMPONENTITEMID IN (&hellip;) OR SUBSTITUTEITEMID IN (&hellip;)</code>), <code>NAMESPACE=&#39;MASTERPLAN&#39;</code> and effective today; a component that is its own slot primary maps to itself, a substitute maps to the real primary. Results collapse to distinct/sorted/comma-joined and enrich <code>EcoTimelineRow.primaryNumber</code> post-classification. Fail-soft: a missing/unreachable PDC leaves the column blank and never blocks the page or startup (<code>initialization-fail-timeout=-1</code>). PDC credentials live in each environment&rsquo;s external <code>config/application.properties</code> only. Cache-bust <code>eco-timeline.js?v=20260623a</code>.' }
        ]
    },
    {
        date: 'June 22, 2026',
        title: 'Team Report &middot; in-app charts, Quarter view, and activity / product-line breakdowns',
        items: [
            { badge: 'new', text: '<strong>The Team Report tab now has charts, not just tables.</strong> New in-app visuals: <em>Total volume by PCM</em>, <em>Total volume by month</em>, and <em>Total changes by month</em> (ECO / MCO / AML) &mdash; each with affected-item totals.' },
            { badge: 'new', text: '<strong>Quarter view for the PCM table.</strong> The &ldquo;Total processing by PCM&rdquo; table now toggles <em>This&nbsp;month / Jan&ndash;YTD / Q1&hellip;Q4</em>, with a quarter-aware subheader.' },
            { badge: 'new', text: '<strong>Change-activities donut + ECN-by-product-line chart.</strong> A donut shows the % split across change categories (Part/BOM Change, Lifecycle, Firmware/Test&hellip;), and a stacked column breaks ECN volume down by product line (top&nbsp;12 + Other) per month.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> in-app charts are hand-rolled (div bars + CSS <code>conic-gradient</code> donut) in <code>teamreport-inapp.js</code>, fed by <code>/api/team-report/data</code> plus two re-derived keys &mdash; <code>activityTypes</code> (col&nbsp;P &rarr; <code>Classification Change Type</code> lookup) and <code>ecnByProductLine</code> (col&nbsp;G split on <code>|</code>) &mdash; added to <code>TeamReportController.extractTeamReportData</code> from the <code>Raw data-No Dup</code> sheet. Pure transforms unit-tested under <code>test/js/</code>.' }
        ]
    },
    {
        date: 'June 22, 2026',
        title: 'ECO Timeline &middot; ECO descriptions, grouped view, and a richer timeline',
        items: [
            { badge: 'new', text: '<strong>Each ECO now shows what it was for.</strong> The timeline pulls in the change order&rsquo;s <em>description</em> and <em>status</em> &mdash; so you can see why an ECO touched the BOM without leaving the tool. Description and Status are also new columns in the Excel export.' },
            { badge: 'new', text: '<strong>New default &ldquo;Grouped by ECO&rdquo; view.</strong> Results now collapse into one card per change order &mdash; ECO #, status, date, description, and a change-type summary (Added&nbsp;&times;2, Removed&nbsp;&times;1&hellip;) &mdash; with the per-component detail one click away. A <em>Flat table</em> toggle keeps the original grid (now with an ECO Description column), and your choice is remembered.' },
            { badge: 'new', text: '<strong>Click any ECO to open a detail drawer</strong> with its full description, affected components, and an <em>Open in Agile PLM&nbsp;&#8599;</em> deep-link.' },
            { badge: 'improve', text: '<strong>The timeline strip is easier to read.</strong> It now has a color legend, quarter date ticks, and a hover card per marker; busier ECOs (3+ changes) show as larger dots. Click a dot to filter both views.' },
            { badge: 'improve', text: '<strong>Faster to start a search.</strong> Quick-range presets (Last 90 days / 6 / 12 months / Since Jan 2025 / All time) and your last few searched items are one click away. The <em>Change Events</em> count replaces the old Query-Time tile (query time moved to the quiet status line).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>EcoTimelineService.fetchRedline</code> now joins <code>change.DESCRIPTION</code> and <code>NODETABLE</code> status for both CHANGE_IN and CHANGE_OUT; carried on <code>BomRedlineRow</code> &rarr; <code>EcoTimelineClassifier</code> &rarr; new <code>ecoDescription</code>/<code>ecoStatus</code>/<code>ecoReason</code> (nullable) on <code>EcoTimelineRow</code>. Excel export gains ECO Description + ECO Status columns. <code>eco-timeline.js</code> rewritten for grouped/flat views, drawer, timeline polish, presets, and recent searches; deep-link uses <code>window.AGILE_WEBCLIENT_URL + /object/&lt;type&gt;/&lt;eco#&gt;</code>. <em>Reason-for-change</em> is wired end-to-end but unpopulated &mdash; the ECO flex attid still needs confirming. Cache-bust <code>eco-timeline.js?v=20260622a</code>.' }
        ]
    },
    {
        date: 'June 19, 2026',
        title: 'Change-order form &middot; Change Impact Details now fills the Agile table',
        items: [
            { badge: 'new', text: '<strong>When Change Impact = Yes, you now fill a proper Resources / Potential Consequences / Responsibilities table.</strong> The form shows a 3-column row editor (add or remove rows); on submit it writes the standard Agile <em>Change Impact Details</em> table &mdash; header, column headers, and the NOTE preserved &mdash; instead of plain text. At least one row is required when disposition is Yes.' },
            { badge: 'fix', text: '<strong>The Change Impact Details field is now actually populated.</strong> Previously the change order&rsquo;s Change Impact Details cell was left as the blank template; the form now writes your rows into it.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ChangeImpactRow</code> + <code>changeImpactRows</code> on the form request; agile-service <code>buildChangeImpactHtml</code> renders the rows into the rich-text cell (baseId default <code>2000025512</code>, overridable via <code>agile.cell.dcoChangeImpactDetails</code>) and <code>DcoFormValidator</code> requires &ge;1 row when disposition=Yes. Frontend <code>imsreview-dco-form.js</code> adds the row editor (<code>cidRowsHtml</code>/<code>captureCidRows</code>). Two reusable agile-service cell endpoints were added for field research: <code>GET/POST /api/agile/change/{number}/cell/{baseId}</code>. Cache-bust <code>imsreview-dco-form.js?v=20260619d</code>.' }
        ]
    },
    {
        date: 'June 19, 2026',
        title: 'Change-order form &middot; Product Line + Subcontractors auto-filled from the document',
        items: [
            { badge: 'new', text: '<strong>The change-order form now suggests Product Line and Subcontractors from the document.</strong> When the form opens, those two fields are pre-filled with the document&rsquo;s current values &mdash; with a small &ldquo;Auto-filled from the document &mdash; a suggestion you can change or clear&rdquo; note beneath each &mdash; so you don&rsquo;t have to re-enter what Agile already knows. Product Line of <em>N/A</em> maps to the N/A toggle.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> toolkit-only (reuses the existing agile-service <code>/api/agile/item/{number}/cells</code>). <code>/token/dco-form-metadata</code> now also reads the item&rsquo;s cells and injects <code>docPrefill</code> (Product Line baseId 1004, Subcontractors baseId 1565, &lsquo;;&rsquo;-split) via new <code>AgileWriteBackClient.itemCells</code> + <code>extractScopePrefill</code>. <code>imsreview-dco-form.js</code> <code>applyDocScopePrefill</code> seeds <code>selectedValues</code> (only when empty) and flags the fields for the note. Best-effort: a failed cell read never blocks the form. Cache-bust <code>imsreview-dco-form.js?v=20260619c</code>.' }
        ]
    },
    {
        date: 'June 19, 2026',
        title: 'Change-order form &middot; clearer field tips + Business Unit for SDSM docs',
        items: [
            { badge: 'improve', text: '<strong>Plainer field tips.</strong> The <em>Description of Change</em> tip now reads &ldquo;Explains what is being changed in the product or process or system,&rdquo; and <em>Reason for Change</em> reads &ldquo;Explains why the change is needed.&rdquo; The example placeholder text inside both boxes was removed so the field starts clean.' },
            { badge: 'new', text: '<strong>Business Unit is now captured for SDSM documents.</strong> When the document number contains <code>-SM-</code>, the form shows a required <em>Business Unit</em> picker as inline buttons (SiP / SSD / Support Group) right on the label line, and you can select more than one. Non-SDSM documents are unaffected (Business Unit stays optional under Advanced).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>imsreview-dco-form.js</code> adds <code>isSdsmDoc()</code> (doc number contains <code>-SM-</code>); SDSM docs render <code>businessUnitButtonsHtml</code> (multi-select toggles, &lsquo;|&rsquo;-joined into the <code>dco-businessUnit</code> hidden input) and gate Business Unit in <code>missingFields(0)</code>. Agile-service <code>DcoRichCreationService</code> swaps <code>oneItem</code>&rarr;<code>splitMulti</code> for the Business Unit cell (its <code>setListCellIfConfigured</code> already does multi-value <code>setSelection</code>). Cache-bust <code>imsreview-dco-form.js?v=20260619b</code>.' }
        ]
    },
    {
        date: 'June 19, 2026',
        title: 'IMS Review &middot; fewer clicks to the change-order form + correct Document Owner pre-fill',
        items: [
            { badge: 'improve', text: '<strong>Needs Change opens the change-order form in one step.</strong> Choosing <em>Needs Change</em> (or clicking the Needs-Change button in the email) now opens the change-order form right away &mdash; the extra &ldquo;Continue &rarr; Fill the change-order form&rdquo; click is gone. (No&nbsp;Change and Need&nbsp;Help still show a Continue button, since their next step is the AD&nbsp;sign-off.)' },
            { badge: 'fix', text: '<strong>The change-order form now pre-fills the correct Document Owner(s).</strong> It was resolving the responder&rsquo;s email and could land the wrong Agile user (e.g. &ldquo;Administrator&rdquo;). It now pre-fills from the document&rsquo;s actual owners in Agile, so the Owner(s) match the document&rsquo;s Title Block.' },
            { badge: 'fix', text: '<strong>Download the document&rsquo;s attachments without leaving the change-order form.</strong> The download button now also sits inside the form&rsquo;s <em>Files</em> step &mdash; right where you build the redline/final &mdash; and downloads in the background, so grabbing the released file no longer means dismissing your in-progress form.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>TokenContext</code>/<code>token-info</code> now expose structured <code>owners</code> ({loginId, displayName, email} from <code>lookupDoc</code>); <code>imsreview-dco-form.js</code> <code>prefillDocumentOwners()</code> seeds the Owner(s) chips from those (falling back to the old recipient-email search only when absent). <code>onPickAction</code> opens the DCO drawer directly for <code>UPLOAD</code> + <code>dcoFormEnabled</code> (no Continue), and the email auto-advance skips its <code>onContinue()</code> in that case.' }
        ]
    },
    {
        date: 'June 18, 2026',
        title: 'IMS Review &middot; download document attachments from the response page (audited in Agile)',
        items: [
            { badge: 'new', text: '<strong>The response page now lets you download the document&rsquo;s attachments.</strong> A <em>Download document attachment(s)</em> button on the review page pulls the file (or a zip when there are several) straight from Agile &mdash; no Agile login needed.' },
            { badge: 'new', text: '<strong>Every download is recorded on the Agile document&rsquo;s History tab.</strong> Because the person responding to a review link doesn&rsquo;t have an Agile account, the History entry names the <em>invoker</em> &mdash; the link recipient and their IP &mdash; plus the file count and timestamp, so there&rsquo;s a clear audit trail of who pulled the document.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new toolkit endpoint <code>GET /api/ims-review/token/attachments</code> (token-scoped &mdash; the doc is derived from the token, so a holder can&rsquo;t fetch arbitrary items) streams the existing attachments bundle and calls <code>ImsReviewService.logAttachmentDownload</code>, which best-effort writes Agile History via a new <code>AgileWriteBackClient.appendItemHistory</code> &rarr; agile-service <code>POST /api/item/{item}/history</code> &rarr; <code>IItem.logAction(comment)</code> (no email, no version bump). The download never fails if the history write-back does. Requires the matching <code>plm-agile-service</code> jar for the History entry.' }
        ]
    },
    {
        date: 'June 18, 2026',
        title: 'IMS Review &middot; five workflow refinements (clickable field tips, Need-Help visibility, longer link window)',
        items: [
            { badge: 'fix', text: '<strong>&ldquo;Need Help&rdquo; messages now show in the dashboard.</strong> When a Document Owner picks <em>Need Help</em> and types a note, that note now appears right under the <em>Need Help</em> status pill in the IMS dashboard (with who wrote it). Previously the message was only delivered in the Doc-Control email and was invisible inside the toolkit.' },
            { badge: 'improve', text: '<strong>The response page shows more document attributes.</strong> It now lists the <em>Document Owner(s)</em> and the <em>Sent on</em> date alongside the existing details, so the responder can visually confirm they&rsquo;re on the right document.' },
            { badge: 'improve', text: '<strong>Field-info tips on the change-order form are now clickable.</strong> The small <em>&#9432;</em> icons next to field labels open a richer, multi-line popover on click (instead of a cramped single-line hover tooltip), with room for fuller explanations. Click away or press Esc to dismiss.' },
            { badge: 'improve', text: '<strong>Response links now last longer.</strong> The review link expiry window moved from 30 days to <strong>100 days</strong>, and it&rsquo;s now configurable so Doc Control can retune it without a new build. The email and the response page both reflect the new window.' },
            { badge: 'fix', text: '<strong>Clearer closure-email subject.</strong> The manager-approval email subject said <code>[Closed]</code>, which was misleading because the DRR/DCO may still be moving through Agile. It now reads <code>[Approved]</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>dataForAdmin</code> surfaces the latest <code>DO_RESPONSE_NEED_HELP</code> note as <code>helpNote</code> (rendered by <code>imsreview.js</code>); <code>TokenContext</code> + <code>/token/info</code> add <code>ownerNames</code> (from <code>lookupDoc</code>) and already carried <code>sentAt</code>; <code>imsreview-dco-form.js</code> swaps the native <code>title=</code> on <code>.dco-info</code> for a click-toggled <code>.dco-tip-pop</code> popover (supports newlines and <code>**bold**</code>); <code>TOKEN_TTL_MS</code> became <code>app.ims-review.token-ttl-days</code> (default 100), mirrored in <code>ImsReviewEmailService</code>; <code>[Closed]</code>&rarr;<code>[Approved]</code> at both DM-approval subject sites.' }
        ]
    },
    {
        date: 'June 18, 2026',
        title: 'New ECO Timeline tab &middot; trace every ECO that touched a part&rsquo;s whole indented BOM',
        items: [
            { badge: 'new', text: '<strong>A new <em>ECO Timeline</em> tab.</strong> Enter an item number and a start/end date, and it lists <strong>every released ECO in that window that changed any component anywhere in the part&rsquo;s full indented BOM</strong> &mdash; not just the top assembly, but sub-assemblies and their sub-assemblies, all the way down.' },
            { badge: 'new', text: '<strong>It tells you what each ECO actually changed</strong> &mdash; a component was added, removed, replaced (primary number changed), or had its quantity, find number, or notes changed &mdash; attributed to the exact sub-assembly where the change happened.' },
            { badge: 'new', text: '<strong>Components that came and went mid-window still show up.</strong> The report reconstructs the structure as it evolved across the date range, so a sub-assembly added and then removed inside the window is still captured.' },
            { badge: 'improve', text: '<strong>A breadcrumb <em>Path</em> column</strong> (SKU &#9656; sub-assembly &#9656; &hellip;) replaces the abstract level number, so you can see exactly where in the structure each change happened.' },
            { badge: 'improve', text: '<strong>A chronological timeline strip above the table</strong> &mdash; one marker per ECO, placed by release date and colored by its most-impactful change. Click a marker to filter the table to that ECO; click again to clear.' },
            { badge: 'improve', text: '<strong>Sortable, per-column filterable, and one-click export.</strong> Download the whole timeline to Excel or email it to yourself.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>EcoTimelineController</code> (<code>/api/eco-timeline/query|export|email</code>) over <code>EcoTimelineService</code>, which runs a recursive <code>CONNECT BY</code> over live <code>AGILE.BOM</code> (edges pre-filtered to lines active anywhere in the window) to build the evolved-union tree, then reads per-assembly redlines off <code>CHANGE_IN</code>/<code>CHANGE_OUT</code>. A pure, unit-tested <code>EcoTimelineClassifier</code> pairs add/remove rows via <code>PRIOR_BOM</code> into Added/Removed/Modified events. Frontend <code>eco-timeline.js</code> + new top-level tab.' }
        ]
    },
    {
        date: 'June 18, 2026',
        title: 'IMS Document Review email &middot; pick your response in one click, land straight on the form',
        items: [
            { badge: 'improve', text: '<strong>The Document Owner review email now shows the three responses as buttons again</strong> &mdash; <em>No change needed</em>, <em>Needs change &mdash; start a change order</em>, and <em>I need help</em> &mdash; and clicking one takes you <strong>straight to the sign-off (or the change-order form)</strong>. No landing page to re-pick the option, no extra <em>Continue</em> click. The common path is now two clicks: the email button, then <em>Sign &amp; Submit</em>.' },
            { badge: 'improve', text: '<strong>&ldquo;Needs change&rdquo; drops you right into the change-order form.</strong> The data-entry form opens immediately from the email, so you can start filling it out without any intermediate steps.' },
            { badge: 'fix', text: '<strong>Clicked the wrong button? No problem.</strong> A &ldquo;&larr; Choose a different response&rdquo; link on the sign-off page lets you switch to a different response without going back to your email.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ims-review-do.html</code> now renders three action buttons wired to the per-action URLs <code>ImsReviewEmailService</code> already stamps (<code>responseUrlNoChange</code> / <code>responseUrlUpload</code> / <code>responseUrlHelp</code>, each carrying <code>?action=</code>). <code>ims-respond.html</code> auto-advances past the picker when a valid <code>?action=</code> is present (calls <code>onPickAction()</code> then <code>onContinue()</code>) &mdash; landing on the sign card (No change / Help) or opening the DCO drawer (Needs change). Expired/redeemed tokens still short-circuit; DM-approval email and multi-owner handling unchanged.' }
        ]
    },
    {
        date: 'June 17, 2026',
        title: 'IMS Review &middot; DCO now always carries its Email Notification',
        items: [
            { badge: 'fix', text: '<strong>The auto-generated compliance PDF on a new DCO is now tagged &ldquo;Email Notification.&rdquo;</strong> Previously it landed with no attachment type. Agile hard-blocks a DCO from submitting unless an &ldquo;Email Notification&rdquo; attachment is present, so the PDF is now always attached with that type, before submit.' },
            { badge: 'improve', text: '<strong>You no longer have to upload an email copy to submit.</strong> Whether you attach your own Email Copy file(s) or just enter stakeholder addresses, the signed compliance PDF is attached as the Email Notification proof &mdash; so the DCO submits cleanly either way.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ImsReviewService</code> always adds the compliance PDF as a <code>file_email</code> blob into <code>create-dco-rich</code> (maps to attachment type &ldquo;Email Notification&rdquo;), dropping the old &ldquo;only when no Email Copy uploaded&rdquo; gate. The post-submit attestation copy is now attached to the DRR only (<code>dcoNumber=null</code>) to avoid a duplicate untyped copy on the DCO.' }
        ]
    },
    {
        date: 'June 17, 2026',
        title: 'Test instances now identify themselves',
        items: [
            { badge: 'new', text: '<strong>A test/QA instance now shows a red <em>TEST</em> badge and an amber warning ribbon</strong> &mdash; on both the sign-in page and inside the toolkit &mdash; so it&rsquo;s obvious at a glance when you&rsquo;re not on production. The ribbon can be dismissed; the badge stays put.' },
            { badge: 'improve', text: '<strong>The sign-in page reflects the real environment.</strong> Its status pill used to always read &ldquo;Production&rdquo; even on a test box; it now shows the actual environment (e.g. <em>TEST</em>).' },
            { badge: 'improve', text: '<strong>Email from a test instance is clearly tagged</strong> &mdash; it&rsquo;s sent from a <code>-qa</code> sender address so test notifications can&rsquo;t be mistaken for production ones.' },
            { badge: 'improve', text: '<strong>The warning ribbon knows whether it&rsquo;s on production data.</strong> If a test box is repointed to a non-prod database and Agile, the ribbon automatically drops the &ldquo;PROD data&rdquo; wording. It only stays in &ldquo;PROD data&rdquo; mode while either the DB or Agile still points at production &mdash; so it can&rsquo;t under-warn.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> two config keys (<code>app.instance.label</code>, <code>app.instance.banner</code>) default empty so production renders nothing; set only on the QA box&rsquo;s external <code>application.properties</code>. <code>/api/auth/session</code> returns them (unconditionally, so the pre-login page can read them); a shared <code>instance-badge.js</code> renders the pill/ribbon on <code>index.html</code> and <code>login.html</code>. Same JAR runs everywhere &mdash; prod is unchanged.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'IMS Dashboard &middot; DRR creation date column + &ldquo;after go-live&rdquo; filter',
        items: [
            { badge: 'new', text: '<strong>The IMS Dashboard queue now shows a &ldquo;DRR Created&rdquo; date column</strong> (the date the DRR first entered the toolkit) and a <strong>&ldquo;Only DRRs created on/after&rdquo; filter</strong> defaulting to the go-live date (Jul 5, 2026). This differentiates post-go-live DRRs from the back-catalog. Untick the filter (or change the date) to see all documents again. Since the go-live date is still in the future, the filtered view is intentionally empty for now &mdash; with a one-click &ldquo;Show all DRRs&rdquo; so nobody gets stuck.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> backend <code>ImsReviewService.dataForAdmin</code> adds <code>drrCreated</code> per row (earliest queue-event timestamp via new <code>earliestEventTs</code>). Frontend <code>imsreview.js</code> adds the sortable/filterable column, a default-on date filter (<code>drrCreatedFilterOn</code> / <code>drrCreatedAfter</code>) with a toggle bar that also renders in the empty state. Cache-bust <code>imsreview.js?v=20260616a</code>.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'DCO form &middot; first round of the requested form improvements',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Admin DCO-form preview:</strong> open <code>ims-respond.html?preview=1</code> (while logged into the toolkit as admin/DCC) to render the change-order form populated with the real dropdown lists but with submit disabled — a fast way to review form layout without running a whole review cycle. Falls back to sample lists when plm-agile-service is down. New endpoint <code>/api/ims-review/dco-form-preview-metadata</code>.' },
            { badge: 'improve', text: '<strong>Product Lines and Change Impact are now toggle buttons.</strong> Product Lines is a <em>Yes / N&#47;A</em> toggle (Yes enables the picker, N&#47;A records the N/A value); Change Impact Disposition is a <em>No / Yes</em> toggle, and choosing <em>Yes</em> reveals the Change Impact Details text right below it (which writes to Agile as before). Buttons across the change-order form were also made more compact.' },
            { badge: 'fix', text: '<strong>Multi-owner sign-off now uses the right person.</strong> When a document has multiple owners they all get the same response link &mdash; the change-order form was pre-filling the sign-off AD username with the <em>first</em> owner, so a co-owner could accidentally sign as someone else (e.g. it showed Vikas Jindal when Vikas Singh was responding). The username is now left blank for multi-owner documents with a &ldquo;sign in with your own AD username&rdquo; note; single-owner docs still pre-fill + lock as before.' },
            { badge: 'improve', text: '<strong>Description of Change and Reason for Change now allow up to 4000 characters</strong> (was 200), matching Agile’s field limits.' },
            { badge: 'improve', text: '<strong>Priority now defaults to &ldquo;Standard&rdquo;</strong> on the change-order form, and every dropdown reads a clearer <code>-- Select --</code> placeholder.' },
            { badge: 'improve', text: '<strong>The response-page option cards are more compact</strong> so the page is tighter and easier to scan.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'Maintenance banner &middot; &ldquo;Need more time?&rdquo; lets users extend the window before a deploy',
        items: [
            { badge: 'new', text: '<strong>The red &ldquo;Scheduled maintenance in N:NN&rdquo; banner now has &ldquo;+5 min&rdquo; / &ldquo;+10 min&rdquo; buttons.</strong> When a deploy is coming, you get the usual 5-minute warning &mdash; but if you need a little longer to finish, click to push the shutdown back. The countdown updates immediately for everyone. The final-30-second pop-up also offers a &ldquo;+10 more minutes&rdquo; button.' },
            { badge: 'new', text: '<strong>The deploy waits for the extension.</strong> Instead of sleeping a fixed 5 minutes, the deploy now waits until the (extended) deadline actually passes before taking the app down &mdash; so clicking &ldquo;+10 min&rdquo; genuinely buys you the time. Total extension is capped (20 minutes by default) so a deploy can&rsquo;t be delayed indefinitely, with a safety ceiling on the deploy side as a backstop.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>MaintenanceService.extend(minutes)</code> pushes <code>targetEpochMs</code> forward, clamped to <code>maxTargetEpochMs</code> (original target + <code>app.maintenance.max-extend-minutes</code>); re-arms the System.exit timer + re-persists on the admin-scheduled path, in-memory only on the deploy path. New endpoints <code>POST /api/maintenance/extend</code> (any authed user) and loopback-only <code>GET /api/maintenance/deploy-ready</code>; <code>/status</code> now returns <code>canExtend</code> + <code>extendBudgetMs</code>. <code>deploy.bat</code> swapped its fixed sleep for a 15s poll of <code>/deploy-ready</code> until ready or a safety ceiling. Cache-bust <code>maintenance.js?v=20260616a</code>.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'IMS Document Review email &middot; one &ldquo;Submit your response&rdquo; button instead of three options',
        items: [
            { badge: 'improve', text: '<strong>The Document Owner / Manager review email now shows a single &ldquo;Submit your response&rdquo; button</strong> instead of the three separate option cards (No Change / Needs Change / Need Help). Showing the three options in the email and then repeating the exact same three on the response page was redundant (Vikas Singh\'s feedback) &mdash; now you click once and pick your response on the page, where <strong>Continue</strong> enables as soon as you select an option.' },
            { badge: 'improve', text: '<strong>Added a short description under the button:</strong> it explains the button opens the IMS Document Management extension to submit a response, links <em>&ldquo;manage the DRR directly within Agile&rdquo;</em> straight to the change in Agile, and points to <code>IMS-Doc-Managers-Agile@sandisk.com</code> for support.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ims-review-do.html</code> three option cards &rarr; one <code>${responseUrl}</code> button + description. <code>ImsReviewEmailService.respondUrl</code> now omits the <code>&amp;action=</code> param when none is passed (so the page owns the choice); <code>sendOne</code> stamps a consolidated <code>responseUrl</code>, and the DO payload adds <code>drrAgileUrl</code> (new <code>agileUrlChange</code> raw-URL helper). The response page already gated Continue behind a selection, so no page change was needed. Legacy per-option URLs retained.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'Weekly Rejection Report &middot; new in-app dashboard view inside the ECN Dashboard',
        items: [
            { badge: 'new', text: '<strong>New &ldquo;Rejection Report&rdquo; view in the ECN Dashboard tab</strong> (sibling pill next to Cycle Time / Returns Tracker / Overdue / Volume / Team Report). It promotes the weekly rejection Excel utility into a first-class dashboard. <strong>This is distinct from the Returns Tracker</strong> &mdash; it captures the explicit <em>Reject</em> workflow action (event&nbsp;13) across <strong>all change classes</strong> (ECO, MCO, DCO, AML, Deviation, DRR, QCO, Special Orders), not just ECN status reverts to Pending. Both views stay separate.' },
            { badge: 'new', text: '<strong>KPI strip</strong> (Total Rejections with week-over-week delta, Unique Changes, Rejectors, <strong>Avg Days in CCB</strong> with 14-day threshold tint, Top Category), a <strong>6-bucket AI category</strong> breakdown (adds <em>Wrong Proposal</em>), a <strong>Days-in-CCB aging</strong> heat-map (0–7 / 8–14 / 14–30 / 30+ matching the Excel), an <strong>8-week rejection trend</strong>, Top Repeat Requestors, and Rejections by Change Type.' },
            { badge: 'new', text: '<strong>Rejection Events table</strong> with a row-click drawer showing the AI category + reasoning, the change description, and the <strong>full CCB comment chain</strong> (reject event emphasized). <strong>Export Excel</strong> returns the script&rsquo;s workbook (CCB heat-map + Analysis sheet) and <strong>Send to me</strong> emails you the HTML summary + Excel attachment.' },
            { badge: 'new', text: '<strong>Manual-fill gate:</strong> the few cells the script can&rsquo;t resolve (Program&nbsp;Name for DRR / Special Orders) are editable inline; saved values flow into the Excel + email on the next refresh. Export / Send confirm before shipping unresolved placeholders.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the Python engine (<code>data/ecn-report/weekly_rejection_report.py</code>) gained a JSON sidecar (<code>rejection-report-latest.json</code>), an 8-week trend query, and a manual-fill read pass. New Spring <code>WeeklyRejectionService</code> generates the per-run config from toolkit config (no creds in git), spawns the Python via the shared report-status lock, parses the sidecar, persists manual-fill, streams the Excel, and emails the script&rsquo;s own HTML+xlsx. New <code>WeeklyRejectionController</code> under <code>/api/ecn-report/rejection/*</code>; <code>WeeklyRejectionScheduler</code> runs Monday&nbsp;07:00 (gated by <code>app.scheduling.disabled</code>). Frontend <code>rejectionreport.js</code> builds the view to match the design mockup. Cache-bust <code>?v=20260616a</code>.' }
        ]
    },
    {
        date: 'June 16, 2026',
        title: 'IT Enhancements Meeting Mode &middot; one consolidated email per recipient set + full proposal text',
        items: [
            { badge: 'improve', text: '<strong>Wrap-up emails now send ONE consolidated email per recipient set instead of one per role.</strong> When the requestor and IT owner on an ECN are different people, both go on the same email\'s To: line; when they\'re the same person, the roles collapse onto one line. Multiple ECNs that share the same (requestor, IT&nbsp;owner) pair group into a single summary &mdash; e.g. Krati with 3 affected ECNs gets ONE email listing all 3, not 3 separate emails. <code>pdl-plm-admin@sandisk.com</code> stays on Cc.' },
            { badge: 'improve', text: '<strong>Each ECN now shows its full proposal text</strong> in both the in-app wrap-up preview and the sent email. The old 120-character snippet was too short for recipients to recognize which enhancement the ECN referred to (Krati\'s feedback) &mdash; the complete proposal is now included, with whitespace collapsed so long CLOBs don\'t sprawl.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> backend <code>ItEnhancementsNotificationService.sendChangeNotifications</code> groups edits by the resolved-recipient SET (deduped, sorted email keys) into <code>RecipientGroup</code>s, one <code>MimeMessage</code> per group, with a per-ECN pre-rendered role line (<em>Requestor: X &middot; IT&nbsp;Owner: Y</em>, collapsed when same person). Frontend <code>it-enhancements.js</code> wrap-up preview was converted to the matching shape: <code>meetingGroupByRecipient</code> keys by recipient set and carries a <code>roleLine</code> per item; <code>renderRecipientSummaryCard</code> reads <code>group.label</code> + <code>it.roleLine</code> and renders the full proposal (truncation removed) so the preview mirrors the actual email. Cache-bust <code>?v=20260616a</code>.' }
        ]
    },
    {
        date: 'June 15, 2026',
        title: 'BOM Export &middot; row-count guard prevents heap-blowing crashes on oversized exports',
        items: [
            { badge: 'fix', text: '<strong>BOM exports that exceed Excel\'s 1,048,576-row limit now reject cleanly with a clear error message</strong> instead of crashing mid-write and pinning ~2 GB of result objects in heap. Today this exact pattern (999-item implode &rarr; 1.6 M terminals &rarr; 6 export retries, each crashing at row 1,048,576 deep inside POI) drove the production JVM to 93% sustained heap pressure and triggered the heap-alert email. Going forward the export rejects with an inline alert: <em>&ldquo;BOM Where Used would produce 1,636,524 rows on the &lsquo;Top-Level Assemblies&rsquo; sheet, exceeding Excel\'s 1,048,576-row limit. Narrow the input list or tighten filters (status, lifecycle, prefixes) and try again.&rdquo;</em>' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>BomExcelExportService.ExcelRowLimitExceededException</code> + <code>EXCEL_MAX_ROW_COUNT</code> constant. <code>export(&hellip;)</code> pre-flights BOTH sheets it would write (main BOM rows + the implode-only Top-Level Assemblies dedup count) before allocating the workbook, so the failure costs nothing in heap or I/O. <code>BomController.export</code> catches the exception, calls <code>response.reset()</code>, and writes a 400 JSON body (<code>errorCode:&#39;EXCEL_ROW_LIMIT&#39;, sheet, neededRows, limit, message</code>). Frontend export path switched from hidden-form-submit to fetch + blob so the JSON 400 surfaces as an <code>appAlert</code> modal instead of navigating to a JSON page. Rejections are logged as a distinct activity action (<code>BOM_EXPORT_REJECTED</code>) for ops triage.' }
        ]
    },
    {
        date: 'June 15, 2026',
        title: 'Volume Report &middot; Manager dropdown hides people with no direct reports',
        items: [
            { badge: 'improve', text: '<strong>The Manager dropdown on the Volume Reports tab now only lists DL members who actually have at least one direct report in AD.</strong> Previously it showed everyone on the access list, including individual contributors with no team &mdash; picking them was always a "0 ECNs" dead end. Filtering removes the dead-end entries (e.g. Afrozuddin Muhammed in the live data) so the dropdown is shorter and every selection produces a meaningful report.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>LdapAuthService.filterUsernamesWithDirectReports(Collection&lt;String&gt;)</code> opens one LDAP context for the whole DL, does a DN lookup + a countLimit=1 <code>(&amp;(objectClass=user)(manager=&lt;dn&gt;))</code> search per user, returns those with at least one report. <code>VolumeReportController</code> caches the result process-wide for one hour (~30 round-trips on cold load, then instant). On AD failure the unfiltered DL is shown so the dropdown stays usable. Response now includes <code>totalDlMembers</code> and <code>filteredOutNoReports</code> for telemetry.' }
        ]
    },
    {
        date: 'June 15, 2026',
        title: 'IT Enhancements Meeting Mode &middot; Latest-Update attribution + autosave/recovery + per-edit Undo',
        items: [
            { badge: 'new', text: '<strong>&ldquo;Latest update by &lt;Author&gt;&rdquo; preview</strong> below the Proposal on the Meeting Mode focus card. Shows the most recent IT Log entry inline (no click to expand) with full attribution &mdash; author name, loginid, and timestamp &mdash; pulled live from Agile&rsquo;s raw IT Log CLOB. Earlier entries collapse into a <em>Show N earlier entries</em> details element. Works for every entry written through Agile <em>or</em> the toolkit.' },
            { badge: 'new', text: '<strong>Autosave + recovery for Meeting Mode drafts.</strong> Every pending edit and captured note is debounced (1 second) and POSTed to the server as a draft. On next login the toolkit shows a recovery prompt: <em>&ldquo;Found unsaved meeting draft VikasJindalEditedOn2026-06-15.json &mdash; 3 pending edits, 2 notes &mdash; saved 17 minutes ago. Recover or Discard?&rdquo;</em> Recover restores everything where you left off and lands you back in Meeting Mode if there were notes; Discard wipes the draft and starts fresh from the latest Agile state. Drafts are auto-deleted server-side as soon as every edit lands in Agile via <code>Save all changes</code>.' },
            { badge: 'new', text: '<strong>Undo / discard affordances on every pending edit.</strong> Row-level <em>↶ Undo</em> next to the <em>Edited &middot; pending save</em> / <em>Needs fix</em> chip discards every dirty entry on the focused ECN plus the captured note. Per-cell <em>↶</em> next to each pending date input discards just that cell. <em>↶ Discard note</em> in the note hint clears both the note and the IT Log pending edit. Undo only touches the in-memory dirty state &mdash; nothing has been sent to Agile yet, so saved values stay untouched.' },
            { badge: 'new', text: '<strong>Color legend</strong> strip between the focus pills and the panes documents rail-dot meanings (green = note captured, amber = pending edit, red = validation error) and risk-border colors (red = Overdue UAT, amber = Overdue Go-Live, blue = Approaching Go-Live).' },
            { badge: 'improve', text: '<strong>Requestor checklist sorted by backlog count</strong> (busiest first) instead of alphabetical &mdash; gets the people in the room to the top of the list without scrolling.' },
            { badge: 'improve', text: '<strong>Pinned rows hold their original rail position</strong> after you fix a date. Previously a re-baselined ECN jumped to the bottom (because its kind flipped to <em>On track</em> which sorts last); now <code>pinnedEcns[ecn]</code> stores the pre-edit kind for sort purposes only, so Next/Prev keep walking the list in the order you started with. The risk badge + left-border still reflect the live state.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> three new endpoints under <code>/api/it-enhancements</code>: <code>GET /it-log-history?ecn=&hellip;</code> (raw HTML CLOB &rarr; structured [{author, loginId, timestamp, date, body}, &hellip;] via regex on the bold author-band header pattern <code>&lt;b&gt;Last, First (loginid) MM/DD/YYYY HH:MM:SS AM/PM TZ&lt;/b&gt;</code>), and <code>GET/POST/DELETE /draft</code> for autosave (new <code>DraftStorageService</code> writes <code>./drafts/&lt;loginid&gt;-active.json</code> with atomic temp-file rename; the file name carried in the JSON metadata is the friendly auto-name <code>&lt;DisplayNameStripped&gt;EditedOn&lt;YYYY-MM-DD&gt;.json</code>). <code>/save-batch</code> deletes the draft after a fully-successful save (partial failures keep it so the user can retry). Frontend hooks <code>writeCellEdit</code>, <code>commitMeetingNote</code>, and <code>discardPending</code> for the autosave path; <code>STATE.itLogHistory</code> caches structured entries per ECN for the session and clears on <code>iteRefresh</code>. Cache-bust <code>?v=20260615f</code>.' }
        ]
    },
    {
        date: 'June 15, 2026',
        title: 'IT Enhancements &middot; Meeting Mode sub-tab (walk the backlog with the room)',
        items: [
            { badge: 'new', text: '<strong>New <em>Meeting Mode</em> sub-tab on the IT Enhancements page</strong> to run the weekly backlog review. Pick the requestors in the room from a checklist dropdown (or leave empty for everyone), choose a focus driver pill (<em>Overdue UAT/Go-Live</em>, <em>Going live &le; 7 days</em>, <em>High priority &middot; no IT owner</em>), and walk the list one ECN at a time. The Meeting Mode pill in the sub-nav shows a green badge with the number of notes captured this session.' },
            { badge: 'new', text: '<strong>Two-pane review surface.</strong> Left rail lists every in-scope ECN with index, ECN, one-line proposal, status, and a risk left-border (red for overdue UAT, amber for overdue Go-Live, blue for approaching Go-Live). Click an item to focus it on the right; arrow keys (<code>&uarr;</code>/<code>&darr;</code>/<code>&larr;</code>/<code>&rarr;</code>) walk through &mdash; ignored while you&rsquo;re typing in the note box. Each rail item shows two right-side dots when relevant: a <strong style="color:#1F8A4C;">green dot</strong> for &ldquo;note captured&rdquo; and an <strong style="color:#C7801B;">amber dot</strong> (or <strong style="color:#B8342B;">red</strong> for errors) when the row has any pending edit.' },
            { badge: 'new', text: '<strong>Focus card with editable Target dates.</strong> The right pane shows the focused ECN&rsquo;s requestor, IT owner, IT status, priority, hours, project, problem statement, and proposal &mdash; with <em>Target UAT</em> and <em>Target Go-Live</em> editable inline (marked &#x270E;). Changes flow into the same per-cell dirty pipeline as the All-Enhancements grid, get the <em>Edited &middot; pending save</em> chip on the focus card, and recompute the rail&rsquo;s risk badge + the pill counts live. If Go-Live lands before Target UAT the field turns red, gets a <em>Needs fix</em> chip, and is excluded from the batch save until corrected.' },
            { badge: 'new', text: '<strong>Meeting note &rarr; IT Log.</strong> The note textarea on the focus card mirrors into a pending IT Log edit, stamped <code>[YYYY-MM-DD&nbsp;Your&nbsp;Name]&nbsp;note</code> and prepended to the existing IT Log. Clearing the textarea removes the pending edit. The same notes feed the wrap-up consolidation emails so you don&rsquo;t have to retype anything.' },
            { badge: 'new', text: '<strong>Wrap-up scorecard.</strong> Click <em>Wrap up &amp; consolidate</em> to swap into the scorecard view: a letter grade (A/B/C/D/F) computed from a weighted composite of six metrics &mdash; IT Owner coverage, Target UAT set, Target Go-Live set, Effort set, IT Log populated, and a derived <em>Schedule health</em> (rows that are NOT overdue UAT, overdue Go-Live, or stale Analysis). Each metric gets a coloured progress bar (red &lt; 40%, amber 40&ndash;60%, blue 60&ndash;80%, green 80%+); risk-count flags appear under the bars; a <em>To reach an A</em> list of prioritized recommendations is generated from the live gap analysis. Includes a <em>Re-grade</em> button to recompute after edits.' },
            { badge: 'new', text: '<strong>Consolidated emails per requestor and per IT owner.</strong> The wrap-up groups every ECN that received a note by either dimension (toggle in the header). Each card shows the person&rsquo;s name, synthesized email (first.last@sandisk.com placeholder until AD lookup is wired), an explicit Cc to <code>pdl-plm-admin@sandisk.com</code>, the items discussed with their notes, and two actions: <em>Draft email</em> (opens a <code>mailto:</code> with To/Cc/Subject/Body prefilled) and <em>Copy email</em> (copies the full To/Cc/Subject/Body text to clipboard, ready to paste anywhere).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> all toolkit-side &mdash; no backend / agile-service changes (the three cells Meeting Mode writes &mdash; <code>Page Three.IT Log</code>, <code>Page Three.Target UAT Date</code>, <code>Page Three.Target Go Live Date</code> &mdash; are already in <code>ItEnhancementsController.EDITABLE_CELLS</code> + <code>PerUserChangeUpdateController.ALLOWED_CELLS</code>). New JS state under <code>STATE.meeting</code>: <code>selReqs</code> map, <code>focusPill</code>, <code>focusedEcn</code>, <code>pinnedEcns</code> (per spec &sect;5 pin-rule for rows that stop matching mid-edit), <code>notes</code> map keyed by ECN, <code>wrappedUp</code>, <code>consolBy</code>, <code>scorecard</code>. Live kind recomputation via <code>meetingComputeKind(row)</code> reads effective Target dates from the dirty map and mirrors <code>ItEnhancementsService.classifyRow</code>. The scorecard is deterministic (per spec the AI narrative + tailored recommendations belong on a server-side proxy with the approved LLM &mdash; not yet wired, but the deterministic grade + rule-based &ldquo;to reach an A&rdquo; is the documented fallback). Email synthesis is a <code>first.last@sandisk.com</code> placeholder &mdash; replace with the AD/Exchange directory lookup later. CSS scoped under <code>#panelItEnhancements .ite2-meet*</code>, <code>.ite2-ms*</code>, <code>.ite2-rail*</code>, <code>.ite2-focus*</code>, <code>.ite2-sc*</code>, <code>.ite2-consol*</code>. Cache-bust bumped to <code>?v=20260615b</code>.' }
        ]
    },
    {
        date: 'June 15, 2026',
        title: 'IT Enhancements &middot; My Enhancements sub-tab + bulk Extend-dates and Add log note',
        items: [
            { badge: 'new', text: '<strong>New <em>My Enhancements</em> sub-tab</strong> on the IT Enhancements panel scopes the grid to rows you own (matched by Agile loginid &mdash; the same identity the per-user write-back uses). The four band tiles (Active, Overdue UAT, Overdue Go-Live, Approaching) and the missing-date pills re-scope automatically; the IT-owner filter is hidden in this view since it&rsquo;s redundant. The header shows <em>&ldquo;Owned by &lt;your name&gt; (loginid)&rdquo;</em> so it&rsquo;s clear what the filter is doing. Inline editing works identically to the All tab.' },
            { badge: 'new', text: '<strong>Row-selection checkboxes</strong> appear as a sticky left column in My view, with a select-all in the header. Selection scopes the bulk actions and clears automatically when you switch sub-tabs.' },
            { badge: 'new', text: '<strong>Bulk Extend Dates &mdash; preview before save.</strong> Three buttons in the My-view toolbar push <em>Target UAT</em>, <em>Target Go-Live</em>, or <em>both</em> out by 7 days. Scope = selected rows if any are checked, else <em>all your rows</em>; within scope, a row contributes a date only if it&rsquo;s in the past or blank (future-dated rows are skipped). The action opens a <strong>preview modal</strong> listing each affected ECN&nbsp;/&nbsp;Field&nbsp;/&nbsp;Current&nbsp;&rarr;&nbsp;New. Rows where the resulting Go-Live would land BEFORE its Target UAT are flagged red with a <strong style="color:#B8342B;">!</strong> and a tooltip explaining why; conflict rows stay as <em>error</em> in the dirty map and don&rsquo;t get auto-batched. Pressing <strong>Save N to Agile</strong> writes those date cells under your own Agile identity via the same <code>/save-batch</code> path as the manual editor (so the Agile sign-in modal + retry just works).' },
            { badge: 'new', text: '<strong>Bulk Add IT-Log note.</strong> With one or more rows selected, click <em>Add log note&hellip;</em>, type a note, and the toolkit prepends a dated/attributed stamp (<code>[YYYY-MM-DD&nbsp;Your&nbsp;Name]&nbsp;note</code>) to each row&rsquo;s IT Log as a pending edit. Review the amber dots, then click <strong>Save all changes</strong> to commit them in one Agile round-trip.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> all changes are toolkit-side (<code>it-enhancements.js</code> + <code>index.html</code>); no controller/service edits required since <code>ItEnhancementsController.EDITABLE_CELLS</code> and <code>PerUserChangeUpdateController.ALLOWED_CELLS</code> already cover the 7 cells the bulk paths touch. New JS state: <code>STATE.subTab</code> (<code>all</code>|<code>my</code>), <code>STATE.currentUser={loginId,displayName}</code> fetched from <code>/api/auth/session</code> on first init, and <code>STATE.selected</code> keyed by ecnNumber. <code>isMine(r)</code> compares <code>row.itOwnerLoginId</code> (already on the Row model) against the session loginid case-insensitively. <code>renderBands/renderBlankPillCounts</code> now recompute against the scope row set when in My view. The Extend preview uses <code>BUSINESS_TODAY</code> set on module load (client clock; server still authoritative on the actual write). Cache-bust bumped to <code>?v=20260615a</code>.' }
        ]
    },
    {
        date: 'June 14, 2026',
        title: 'IMS Owner Change Audit &middot; hourly rebuild no longer times out (admin/scheduler fix)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>The hourly OWNER-AUDIT rebuild was hitting ORA-01013 every run from 21:32 Jun 13 onward.</strong> Root cause (verified on agprod): Oracle was pushing the <code>DBMS_LOB.SUBSTR(...) LIKE</code> predicate down onto the entire 9.09M-row 365-day <code>ITEM_HISTORY</code> slice, evaluating the CLOB filter <em>before</em> the join to the 16K-row IMS-items set. The CLOB I/O blew past the 120s JDBC timeout. <code>NO_MERGE</code> didn&rsquo;t help &mdash; Oracle under-costs <code>DBMS_LOB.SUBSTR</code> and ignored the fence.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>OwnerChangeAuditService.pullFromSql</code> rewritten with a <code>ROWNUM</code> pseudo-column inside the inline view that acts as a true optimization barrier &mdash; the outer <code>LIKE</code> predicates can no longer push down. The CLOB filter now runs on the ~97K post-join IMS-only rows (~93&times; fewer CLOB reads). Hint set <code>LEADING(i h) USE_HASH(h) FULL(i)</code> keeps IMS items as the hash-join build side. Cursor plan post-fix shows the <code>LIKE</code> sitting on the VIEW node above the hash join, confirmed on agprod. Same binds, same output columns, no caller / cache-format changes. Timeout kept at 240s as safety margin; happy-path runtime now well under 60s. DDL option (composite <code>(ITEM, TIMESTAMP)</code> index) documented in <code>docs/owner-audit-improved-sql.md</code> for the DBA if a longer-term NL plan is preferred.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'Go-Live &amp; Target-UAT sign-off &mdash; daily digests with tokenized sign-off pages (new)',
        items: [
            { badge: 'new', text: '<strong>Two new daily digest emails for IT Enhancements ECNs.</strong> <em>Go-Live sign-off</em> goes to the <strong>requestor</strong> when an ECN in UAT has a Target Go-Live within the next 1&ndash;3 days (or already past due). <em>Target UAT check</em> goes to the <strong>IT owner</strong> when an ECN in WIP / Analysis has a Target UAT in the same window. Both fire at 8:00 AM, group all of a person&rsquo;s qualifying ECNs into one envelope, and Cc <code>pdl-plm-admin@sandisk.com</code>.' },
            { badge: 'new', text: '<strong>Tokenized sign-off page &mdash; no toolkit login.</strong> The CTA in each email opens a token-gated page that lets the recipient confirm each upcoming date or pick a new one for past-due rows. Submitting needs the recipient&rsquo;s <strong>Agile loginid + password</strong>; the date is written to Agile under their own identity (the History tab attributes the change to them, not Administrator). Tokens are single-use, expire in 7 days, and lock after 5 wrong passwords.' },
            { badge: 'new', text: '<strong>&ldquo;Send sign-off&rdquo; toolbar button</strong> on the IT Enhancements tab lets an admin manually fire either digest for any loginid &mdash; useful for re-sending after a missed deadline or testing the email layout. The button pre-fills the loginid from the currently-selected row&rsquo;s requestor (Flow A) or IT owner (Flow B).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>GoLiveSignoffService</code> + <code>GoLiveSignoffController</code>. State persisted at <code>./data/golive-signoff-state.json</code> (atomic write); shape matches the spec&rsquo;s v2 envelope with <code>tokens</code> / <code>sent</code> / <code>lastOverdueReminder</code>. Auth filter exemption added for <code>/golive-signoff*</code> and <code>/api/golive-signoff/{data,submit}</code>; <code>/api/golive-signoff/trigger</code> stays admin-gated. Reuses <code>AgileWriteBackClient.updateChangeCellAsUser</code> + <code>verifyUserCredentials</code> &mdash; no agile-service changes required. Cell names: <code>Page Three.Target Go Live Date</code> (Flow A) and <code>Page Three.Target UAT Date</code> (Flow B) &mdash; both already in <code>PerUserChangeUpdateController.ALLOWED_CELLS</code>. The IT-ENH cache is patched in-memory after a successful write so the grid reflects the new date immediately.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'IT Enhancements &middot; editable grid (7 columns) live (Phase 2&ndash;7)',
        items: [
            { badge: 'new', text: '<strong>Spreadsheet-style editing on the IT Enhancements tab.</strong> Seven columns are now editable: <em>IT Owner</em>, <em>Hrs</em>, <em>Project</em>, <em>IT Actions Taken</em>, <em>Target UAT</em>, <em>Target Go-Live</em>, <em>IT Log</em>. Click a cell and type; the pencil mark (<span style="color:#4a6fa5;">&#x270E;</span>) in each header tells you what&rsquo;s editable. Every save is written to Agile under your own AD identity &mdash; the History tab attributes the change to you, not Administrator.' },
            { badge: 'new', text: '<strong>Keyboard navigation across editable cells.</strong> <code>Tab</code> / <code>Shift+Tab</code> jumps editable column to editable column (skips read-only ones). <code>Enter</code> commits an edit and drops down to the next row. <code>Esc</code> cancels. <code>Shift</code>+<code>&uarr;</code>/<code>&darr;</code> extends the selection range, and <code>&#x2318;/Ctrl+D</code> fills the active cell&rsquo;s value down into the range &mdash; or drag the small corner handle on the active cell to do the same with the mouse.' },
            { badge: 'new', text: '<strong>Per-cell save state shows up as a corner dot:</strong> <em>pending</em> (amber), <em>saving</em> (blue, pulsing), <em>saved</em> (green, fades after 2 s), <em>error</em> (red, hover the cell for the validation reason). The <code>Save all changes (N)</code> button batches every pending edit in one round-trip; errors stay dirty so you can fix and retry without losing the rest of your work.' },
            { badge: 'new', text: '<strong>Type-aware editors.</strong> Dates use the native date picker. <em>IT Owner</em> opens a single-select dropdown sourced from the owners present in your view. <em>Project</em> and <em>IT Actions Taken</em> show a text input with a datalist of suggestions seen in agprod (these are multi-list cells &mdash; comma-separate multiple values). <em>IT Log</em> opens a roomier popover modal instead of an inline editor.' },
            { badge: 'improve', text: '<strong>Density toggle</strong> in the toolbar lets you switch between Comfortable and Compact row heights; choice is remembered locally. The legend at the bottom of the grid documents the dot colours and keyboard shortcuts at a glance.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> vanilla-JS rewrite of <code>it-enhancements.js</code> (port of the React prototype in <code>handoff/IT Enhancements/grid.jsx</code>). New <code>COLUMNS</code> shape with per-column <code>type</code> + <code>edit</code> + <code>cell</code> + <code>opts</code> metadata; dirty map re-keyed as <code>ckey(ecn, cellName) &rarr; { value, status, msg }</code>; reverse map <code>CELL_TO_FIELD</code> patches saved rows in place. <code>OWNERS</code> / <code>PROJECT_SUGGESTIONS</code> / <code>ACTION_SUGGESTIONS</code> are computed lazily from <code>STATE.rows</code>, not hardcoded. Multi-list values (Project + IT Actions Taken) ride the wire as pipe-separated strings &mdash; the toolkit-side <code>EDITABLE_CELLS</code> mirror was added in the Phase 1 commit, and the agile-service dispatcher splits on <code>|</code>. CSS is scoped under <code>#panelItEnhancements .ite2-*</code> so it can&rsquo;t leak to other tabs; IT Log popover uses a custom <code>ite2-modal-back</code> overlay because <code>appPrompt</code> doesn&rsquo;t support multiline textareas. Cache-bust bumped to <code>v=20260612a</code>.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'IT Enhancements &middot; Phase 1 backend ready for write-back to 7 cells (IT-ENH editable grid)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>plm-agile-service\'s per-user write-back now supports all 7 IT-owned cells</strong>, not just Target UAT. The cell-name table was verified by probing ECN-128313-PROJ earlier today (see plan in <code>docs/superpowers/plans/2026-06-05-it-enhancements-editable-grid.md</code>). Three of the spec\'s six guesses turned out to be wrong &mdash; <em>Target Go Live Date</em> has no hyphen, <em>Effort &nbsp;(person-hours)</em> has a literal double space, and <em>IT Owner</em> is actually <em>Assigned IT Owner</em> as a regular single LIST (not a Users user-ref slot). Frontend wiring is still on the legacy single-column editor; this build only changes the backend allowlist + write dispatcher. Test path = curl directly against <code>/api/it-enhancements/save-cell</code> until the new grid UI ships.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>PerUserChangeUpdateController.ALLOWED_CELLS</code> extended from 1 &rarr; 7 entries; new <code>setCellValue(cell, cellName, raw)</code> dispatcher that switches on cellName into DATE / TEXT / LIST single / LIST multi handlers. LIST writes use the standard <code>cell.getAvailableValues() &rarr; setSelection(Object[]) &rarr; cell.setValue(list)</code> pattern. Multi-list values come over the wire as pipe-separated strings (so display values can contain commas, e.g. "Code Change (please fill in Jar Name/Class Name)"). IT Log + Effort use the TEXT handler &mdash; <code>cell.setValue(rawString)</code> passthrough. IT Log content is never echoed in the log line (the line records <code>old=(omitted, N chars) new=(omitted, M chars)</code>). Toolkit-side <code>ItEnhancementsController.EDITABLE_CELLS</code> mirror added for fail-fast on save-cell + save-batch &mdash; rejects unknown cellNames before the agile-service round-trip. The Phase 0 <code>_introspect</code> probe endpoint is removed from this build.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'Overdue Tracker &middot; Team column + Top Product Teams panel now populate (PT-95, Jimmy)',
        items: [
            { badge: 'fix', text: '<strong>The Team column in the Open Out-of-Target table and the &ldquo;Top Product Teams&rdquo; panel above it are no longer blank.</strong> The Overdue Tracker used to leave Product Team unresolved (it was marked &ldquo;not in this projection; v1&rdquo; in the SQL pick). It now uses the same 3-tier fallback the Returns Tracker and Cycle Time tabs do: <em>teamOverride annotation &rarr; ecn_data.json&rsquo;s productTeam &rarr; static Product Line &rarr; Team map</em>. Every overdue ECN with a known Product Line buckets into a team.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>OverdueTrackerService.resolveProductTeam(ecnNumber, productLine)</code> mirrors the logic from <code>RejectionTrackerService.resolveProductTeam</code> &mdash; reuses <code>RejectionTrackerService.PRODUCT_LINE_TEAM_MAP</code> directly so there&rsquo;s still one canonical table. Subtle wrinkle: the Overdue Tracker&rsquo;s product-line string is joined with <code>&nbsp;·&nbsp;</code> (middle dot, see <code>resolveProductLines</code>) where the Returns Tracker uses <code>;</code> &mdash; the splitter accepts both so the same map keys work. <code>pickOverdueEcns</code> now calls <code>resolveProductTeam(r.ecnNumber, r.productLine)</code> instead of the hardcoded empty string at the row construction site. <code>computeAggregates</code> already bucketed by <code>r.productTeam</code>, so the &ldquo;Top Product Teams&rdquo; panel inherits the fix without further changes.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'Returns Tracker &middot; Product Team column now populates for very fresh ECNs too (PT-94, Jimmy)',
        items: [
            { badge: 'fix', text: '<strong>The Product Team column on the Return-to-Pending Events table now resolves for ECNs that landed AFTER the most recent ECN Dashboard run.</strong> Previously the freshest ECNs &mdash; the ones at the top of Jimmy&rsquo;s view &mdash; were the most likely to show blank Product Team, because they hadn&rsquo;t been ingested into <code>ecn_data.json</code> yet. The Returns Tracker now falls through to a static Product Line &rarr; Team map (same TEAM_MAP Python uses) when the ECN lookup misses, so &ldquo;1024 - MicroSD&rdquo; routes to CS, &ldquo;1037 - Client - PCIe&rdquo; to CSSD, etc. independent of the ECN snapshot age.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>RejectionTrackerService.PRODUCT_LINE_TEAM_MAP</code> static (mirrors Python&rsquo;s <code>TEAM_MAP</code> in <code>ecn_report_generator.py</code>; 31 entries). <code>resolveProductTeam</code> gains a third fallback that splits the event&rsquo;s <code>productLine</code> on <code>;</code>, looks each segment up, and joins distinct teams in sorted order &mdash; matches Python&rsquo;s <code>resolve_team()</code> semantics for multi-PL ECNs. <code>RejectionTrackerEmailService.addProductLinesSheet</code> gets the same third-tier fallback so the Excel chart agrees with the in-app panel. Python script also now emits <code>teamMap</code> in the <code>ecn_data.json</code> sidecar for future-proofing (a long-term single source of truth) &mdash; not yet read by Java but available if we want to switch off the static copy later.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: '&ldquo;ECN Report&rdquo; renamed to &ldquo;ECN Dashboard&rdquo; everywhere it&rsquo;s visible to users (PT-93, Jimmy)',
        items: [
            { badge: 'improve', text: '<strong>The top-nav tab and every user-facing label that said &ldquo;ECN Report&rdquo; now reads &ldquo;ECN Dashboard&rdquo;.</strong> Affects the top tab button, the page heading, the email subject default (<em>&ldquo;ECN Dashboard &mdash; YTD&hellip;&rdquo;</em>), the email breadcrumb, the &ldquo;Send Email&rdquo; and &ldquo;Manage Editors&rdquo; modal headings, the &ldquo;Run the report?&rdquo; confirmation prompt, the User Management tab-permissions row name, and the AI chat&rsquo;s tab-list reference. URL routes and internal DOM ids (<code>tabEcnReport</code>, <code>switchTab(\'ecnreport\')</code>) are unchanged so existing links / bookmarks keep working.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> string-level rename across <code>index.html</code> (top-tab button, H2 heading, aria-label, eyebrow text), <code>ecnreport.js</code> (5 modal / email strings), <code>commands.js</code>, <code>app.js</code> (tab-label arrays), <code>overduetracker.js</code>, <code>UserPermissionsService.TabDef</code>, <code>AiHelpController</code> nav-layout string, and <code>app-knowledge.txt</code> (the AI assistant&rsquo;s vocabulary mapping now lists both names so older queries still route correctly). Java route paths (<code>/api/ecn-report/*</code>) and internal identifiers untouched.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'Returns Tracker &middot; Product Team column on the events table now populates for every ECN (PT-90 follow-up)',
        items: [
            { badge: 'fix', text: '<strong>The Product Team column on the Return-to-Pending Events table is no longer mostly blank.</strong> It now resolves from the same source the Cycle Time tab uses &mdash; the ECN&rsquo;s Python-resolved <code>productTeam</code> field (TEAM_MAP&rarr;Product Line lookup), with the admin-set <code>teamOverride</code> annotation winning when present. Same fix as PT-90 for the &ldquo;Top Product Teams&rdquo; panel; this entry just confirms it propagates through the events list too (same <code>enrichEvent</code> codepath, no extra code change needed).' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'deploy.bat &middot; fixes the &ldquo;new JVM never starts when a non-admin user is logged in&rdquo; race (PT-92, Vikas)',
        items: [
            { badge: 'fix', text: '<strong>Running <code>deploy.bat</code> while a non-admin is logged in no longer leaves the new watchdog stuck on &ldquo;file in use&rdquo; errors.</strong> The 5-minute grace window now keeps the running JVM alive for the full sleep — deploy.bat is the only thing that ever shuts it down. Previously, the JVM scheduled its own <code>System.exit</code> at the 5-minute mark, the watchdog respawned a fresh JVM from the OLD JAR while deploy.bat was still sleeping, and when deploy.bat finally woke and swapped the JAR, the orphan JVM was still holding <code>plm-toolkit.log</code> open — the new watchdog spammed &ldquo;The process cannot access the file because it is being used by another process&rdquo; until manual cleanup.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>MaintenanceService.fireWarningWithoutTimer(minutes, reason, scheduledBy*)</code> that snapshots recipients, sets the in-app banner, sends the &ldquo;going down&rdquo; email, and writes the <code>MAINTENANCE_WARN_ONLY</code> activity-log entry &mdash; but does NOT call <code>armTimer()</code> and does NOT persist to <code>pending-shutdown.json</code>. <code>MaintenanceController.scheduleFromDeploy</code> swaps from <code>maintenanceService.schedule()</code> to this new method. The admin in-app UI path (full <code>schedule()</code> with timer) is unchanged. Belt-and-braces in <code>deploy.bat</code>: after <code>stop.bat</code>, scan for any java.exe whose command line references the JAR path and kill them (catches orphan JVMs that haven&rsquo;t yet bound to 8090), then sleep 3s so Windows has time to fully release file handles before the new watchdog tries to <code>&gt;&gt;</code> <code>plm-toolkit.log</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Side effect:</strong> the &ldquo;back online&rdquo; email that fires after a planned maintenance restart only happens on the in-app-UI path now. The deploy.bat path no longer triggers it because <code>MaintenanceService.fire()</code> never runs (so the maintenance-mode flag file isn&rsquo;t written, so the next boot doesn&rsquo;t detect a maintenance window completing). Users still see the 5-minute warning banner+email when deploy.bat starts; the toolkit just comes back up clean without the extra &ldquo;we&rsquo;re back&rdquo; broadcast.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'ECN Report &middot; POM ECN KPIs Month panel uses a flat 10-day target regardless of priority (PT-91, Jimmy)',
        items: [
            { badge: 'fix', text: '<strong>The "Cycle Time (POM ECN KPIs Month)" panel now uses a flat 10-day target for every General ECN regardless of priority.</strong> This is the monthly POR roll-up Jimmy reports out &mdash; he wants the whole bucket measured against the same bar so the % reads consistently across months. The main Cycle Time table above still uses the priority-based per-row targets from the SLA matrix (Std=10, Urg=6) as published.' },
            { badge: 'improve', text: '<strong>Subtitle copy updated</strong> from "6-day target regardless of priority &middot; anything over 6 days is overdue" to "10-day target regardless of priority &middot; anything over 10 days is overdue" in the in-app POM panel, the email-mode POM panel, and the Excel report Panel 7 title.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new module-level <code>POM_FLAT_TARGET_DAYS = 10</code> in <code>ecnreport.js</code>. <code>ecnComputePomPanel</code> stops reading <code>r._onTarget</code> (which is priority-based after PT-88) and recomputes on-target locally as <code>r.actualDays &le; POM_FLAT_TARGET_DAYS</code> for every General ECN in the month. Excel sibling: <code>POM_FLAT_TARGET_DAYS = 10</code> inline in <code>ecn_report_generator.py</code>\'s Panel 7 block; <code>pct_on_target</code> recomputed against the flat target instead of summing <code>e["onTarget"]</code>. The Overdue Tracker "Days over target" tooltip dropped its hardcoded "6-day target" wording since it always reads the per-row target from the SLA matrix anyway.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'Returns Tracker &middot; Top Product Teams now uses the same team logic as the Cycle Time tab &mdash; no more 100% Unknown (PT-90, Jimmy)',
        items: [
            { badge: 'fix', text: '<strong>The "Top Product Teams" panel on the Returns Tracker now matches what the Cycle Time tab shows.</strong> It was bucketing every event as <em>Unknown</em> because it only read the admin-set <code>teamOverride</code> annotation, which almost no ECN has. Jimmy flagged the panel showing "Unknown · 1,547" against a Cycle Time tab that resolves CS / CSSD / AME / ESSD cleanly for the same ECNs.' },
            { badge: 'improve', text: '<strong>New fallback order:</strong> (1) admin-set Product Team override on the Cycle Time data table &rarr; (2) the team Python already resolved from the ECN&rsquo;s Product Line via the TEAM_MAP table (same field the Cycle Time tab reads) &rarr; (3) only then "Unknown" if neither has a value. Net effect: the Returns Tracker breakdown lines up with the Cycle Time (Product Team) panel for every ECN whose product line is mapped (the vast majority).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>RejectionTrackerService.resolveProductTeam(ecnNum, productLine, annotations, ecnLookup)</code> gains the existing <code>ecnLookup</code> arg and now falls through to <code>ecnLookup.get(ecnNum).get("productTeam")</code> &mdash; the value <code>ecn_report_generator.py</code> populates via <code>resolve_team(product_lines)</code> when it builds <code>ecn_data.json</code>. Old PT-61 behaviour (no Product-Line fallback) is preserved as the final step. The Returns Tracker Excel chart sheet (<code>RejectionTrackerEmailService.addProductLinesSheet</code>) gets the same fix &mdash; its old "fall back to raw Product Line string" path is replaced with the same <code>ecnLookup.productTeam</code> lookup so the in-app panel and the emailed Excel chart now agree.' }
        ]
    },
    {
        date: 'June 12, 2026',
        title: 'ECN Report &middot; General ECN target days now follow the published SLA matrix (PT-88, Jimmy follow-up)',
        items: [
            { badge: 'fix', text: '<strong>General ECNs are now measured against the published SLA matrix &mdash; Standard priority &le;10 days, Urgent priority &le;6 days &mdash; same shape as every other classification.</strong> The "uniform target for all General ECNs regardless of priority" override that PT-59 introduced never matched Jimmy\'s published spec. PT-84\'s tightening (to a flat 6 days) and yesterday\'s PT-87 historical schedule were patches on top of that same wrong premise. All three are now removed; the SLA matrix is the single source of truth for what the bar is.' },
            { badge: 'fix', text: '<strong>Cycle Time (General ECN) table now shows the correct Target Days per priority.</strong> Previously the table was showing Target Days = 10 for both Standard and Urgent rows; it now reads 10 for Standard and 6 for Urgent (matching what Jimmy publishes). The "Total Completed ECNs" header row\'s blended Target Days reflects the volume-weighted average across the two priorities.' },
            { badge: 'improve', text: '<strong>The "SLA Targets in Effect" banner reads its General-ECN pill straight from the matrix again.</strong> No more PT-87 historical-suffix text; the banner now matches what the Cycle Time table shows ("Std &le;10d, Urg &le;6d").' },
            { badge: 'improve', text: '<strong>The SLA banner pill labelled "Standard ECNs" is now labelled "General ECNs"</strong> &mdash; matching the Cycle Time panel and the rest of the dashboard. The SLA matrix CSV still carries the legacy "Standard ECN" label internally; the rename is cosmetic at display time so no data files were touched. Reported by Jimmy.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the <code>isStdEcn</code> branch in <code>ecnComputeStats</code> is deleted &mdash; General ECNs fall through to the same priority-based <code>t.urgent</code> / <code>t.standard</code> lookup the SLA matrix already exposes for every other classification. <code>GENERAL_ECN_TARGET_SCHEDULE</code>, <code>STANDARD_ECN_TARGET_DAYS</code>, and <code>ecnGeneralTargetForRow()</code> were removed from <code>ecnreport.js</code> (and the matching constants + <code>general_ecn_target_for_completed_date()</code> from <code>ecn_report_generator.py</code>). The General-ECN branch in <code>ecnSlaPill</code> is gone too &mdash; it reads <code>targetList[0].split(\'/\')</code> like every other pill. <code>STANDARD_ECN_CLASSIFICATIONS</code> stays as a classification set for filtering / labelling.' }
        ]
    },
    {
        date: 'June 11, 2026',
        title: 'ECN Report &middot; new "Team Report" sub-tab + PowerPoint export on the SanDisk template (Noraida)',
        items: [
            { badge: 'new', text: '<strong>New "Team Report" sub-tab under ECN Report</strong> brings Noraida\'s monthly report inside the toolkit. Three sections wired to the most recently-generated Team Report XLSX: <em>Total processing by PCM</em> (PCM table with This-month / Jan&ndash;YTD toggle), <em>Total affected items by month</em> (clickable bar chart by change type), and the <em>AI analysis</em> grid showing the per-Program-Team summary + risk callout that Claude already writes into the workbook. Month switcher up top jumps between any month with a report on disk; an Export menu drops the existing Excel pipeline (unchanged), a NEW PowerPoint export on the real SanDisk corporate template, and the discrepancy report.' },
            { badge: 'new', text: '<strong>PowerPoint export (NEW)</strong> &mdash; 4-slide deck on the SanDisk corporate template (v3.1, Feb 2026): title slide (black layout), PCM table, monthly affected-items bar chart, and an AI analysis card grid. Hit Export &rarr; PowerPoint from the Team Report sub-tab; the .pptx streams back instantly. Excel + discrepancy outputs unchanged.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> three new endpoints on <code>TeamReportController</code> &mdash; <code>GET /api/team-report/months</code>, <code>GET /api/team-report/data?month=…</code>, and <code>POST /api/team-report/{month}/pptx</code>. AI per-team JSON is now enriched at generation time with <code>urgentPct</code> / <code>risk</code> / single-line <code>callout</code> fields and persisted as a <code>&lt;stamp&gt;__&lt;stem&gt;.analysis.json</code> sidecar in the user\'s recent dir so the in-app view reads structured data instead of re-parsing the AI Analysis sheet prose. Frontend: new <code>teamreport-inapp.js</code> (vanilla JS port of Noraida\'s React mock, ~700 lines) plus a 5th pill in the ECN Report sub-tab strip; existing <code>teamReportOpenDrawer()</code> reused for generation. PowerPoint: new <code>team_report_pptx_generator.py</code> against <code>Sandisk_PPT_Template_v3_1.pptx</code> (one-time-stripped of sample slides; ships in <code>data/team-report/</code> alongside the build script). Smoke-tested locally end-to-end against Aida\'s May 2026 workbook.' }
        ]
    },
    {
        date: 'June 11, 2026',
        title: 'ECN Report &middot; rename "Standard ECN" &rarr; "General ECN", drop target to 6 days, remove Target Days from the Team panel (PT-84)',
        items: [
            { badge: 'improve', text: '<strong>The "Standard ECN" labels across the ECN Report are now "General ECN"</strong> &mdash; matching what the rest of the org calls them. Affects the Cycle Time panel title, the SLA banner, the POM ECN KPI section, the Overdue Tracker header / tooltips / empty-state text, the email-mode report, and the matching panels in the Excel report.' },
            { badge: 'improve', text: '<strong>The General-ECN cycle-time target moved from 10 days &rarr; 6 days.</strong> All overdue / on-target / SLA calculations now measure against the tighter 6-day window. The "POM ECN KPIs Month" panel\'s subtitle and the Excel report\'s POM panel title both reflect the new number.' },
            { badge: 'improve', text: '<strong>The Target Days column added to the Product Team panel last week (PT-83) is removed.</strong> Each team mixes General + PDR ECNs that have different target days, so a single blended number was misleading. The General-only Cycle Time panel keeps its Target Days column (where it makes sense).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>STANDARD_ECN_TARGET_DAYS</code> in <code>ecnreport.js</code> + <code>ecn_report_generator.py</code> dropped from 10 to 6. UI label rename touched <code>ecnreport.js</code> (SLA banner, Cycle Time email panel, POM subhead), <code>index.html</code> (Cycle Time panel header, POM subtitle, Overdue Tracker section header / refresh tooltip / classification chip tooltip / minor comments), <code>overduetracker.js</code> (category empty-state, table header switch, empty-state copy, querying spinner), and <code>ecn_report_generator.py</code> (Panel 2 title, POM Panel 7 title + 6-day text). Data-matching constants left as-is &mdash; <code>STANDARD_ECN_CLASSIFICATIONS</code> already includes both the legacy "Standard ECN" / "Standard ECNs" and new "General ECN" / "General ECNs" values so classifier behaviour is unchanged. <code>ecnRenderTeamPanel</code> headers + cells reverted to the pre-PT-83 5-column shape.' }
        ]
    },
    {
        date: 'June 11, 2026',
        title: 'Items Search &middot; default columns expanded from 5 to 12 (PT-86)',
        items: [
            { badge: 'improve', text: '<strong>Running a search on the Items tab now shows 12 columns by default</strong> instead of just the bare 5 (Part / Description / Rev / Status / Lifecycle). Added: Subcontractors, Build Plant, Create Date, PM, Material Type, Material Group, Product Line. The "More fields…" picker still has the rest of the 30-column allow-list if you need more. Reported by Jimmy Sessumes.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ItemsSearchService.defaultResultColumns</code> now seeds the LinkedHashSet with the 12 columns instead of 5. Column auto-pinning from query conditions still works as before. The one column Jimmy named that I couldn\'t map &mdash; "Group" &mdash; was left out pending clarification (no <code>GROUP</code> column in the allow-list; could be Material Group, Product Group, or a new field).' }
        ]
    },
    {
        date: 'June 11, 2026',
        title: 'Part Extract &middot; Export Excel + Email Me now work for large part lists (PT-85)',
        items: [
            { badge: 'fix', text: '<strong>Export Excel and Email Me on the Part Extract tab now work for any part-list size, not just small ones.</strong> Jimmy ran a 404-part JV PN List, both buttons died &mdash; Excel showed an HTTP 400, Email Me showed <em>"Failed: Unexpected token \'&lt;\', \'&lt;!doctype&hellip;\' is not valid JSON"</em>. Root cause: the toolkit was packing the entire comma-joined part list into the URL query string. Past ~300 parts the URL exceeded Tomcat\'s 8KB HTTP header buffer and the request was rejected by the web server before any toolkit code ran &mdash; the JS then tried to parse the resulting HTML error page as JSON and exploded.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> Search / Export / Email on <code>/api/parts/*</code> now accept a JSON request body (<code>PartsReq { items, columns, releaseDateFrom, releaseDateTo }</code>) in addition to the legacy query-string form. <code>parts.js</code> switched to POST + JSON body for all three calls; Export uses <code>fetch &rarr; res.blob() &rarr; createObjectURL</code> (same pattern <code>items-search.js</code> uses) instead of <code>window.location.href</code>. <code>server.max-http-header-size</code> bumped from the 8KB default to 64KB in <code>application.properties</code> as belt-and-suspenders for the legacy GET variants. Tested locally with Jimmy\'s 404-part list &mdash; both Export and Email round-trip clean.' }
        ]
    },
    {
        date: 'June 9, 2026',
        title: 'ECN Report &middot; Cycle Time tables now show Target Days alongside Avg Days (PT-83)',
        items: [
            { badge: 'improve', text: '<strong>The Cycle Time and Product Team tables under ECN Report &gt; Cycle Time now include a "Target Days" column right before "% on Target".</strong> Each row\'s cell shows the mean of the SLA target days across that bucket\'s ECNs &mdash; so you can eyeball Avg Days vs. the SLA that\'s being measured against, side-by-side, without having to remember the team config. Reported by Jimmy Sessumes.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new helper <code>ecnAvgTargetDays(arr)</code> in <code>ecnreport.js</code> &mdash; mean of numeric <code>_targetDays</code> across rows, skipping nulls / NA / TBD so the denominator matches the % on Target calculation. Added <code>avgTargetDays</code> to <code>ecnComputeStats</code>\'s total/standard/urgent rollups and to <code>ecnComputeTeamPanel</code>\'s team + Std/Urg substats. <code>ecnRenderCycleTimePanel</code> and <code>ecnRenderTeamPanel</code> got the new header slot + cell. Dedicated Process panel left alone (it has no % on Target column).' }
        ]
    },
    {
        date: 'June 8, 2026',
        title: 'IMS Review &middot; the [New DCO] stakeholder email now always Cc\'s every Document Owner who received the original IMS Review',
        items: [
            { badge: 'improve', text: '<strong>The "[New DCO]" stakeholder notification now Cc\'s every Document Owner who originally got the IMS Review email</strong>, even when the DCO form-filler didn\'t list them all in the Document Owners field on the form. The canonical roster comes from the token\'s SEND event (= the Agile doc-owner list at the time the review was opened), so multi-owner documents stay in sync regardless of which owner redeems or what they picked. Idempotent &mdash; no double-recipients if the owner is already in the To line via the form\'s approvers / observers / documentOwners selection.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> after the existing recipient-build pass in <code>ImsReviewEmailService.sendStakeholderNotify</code>, iterate <code>ctx.allowedActors</code> (the list of emails the original SEND event fanned out to). For each non-empty email, run the same dedup-against-To check we already use for the submitter and Cc it if absent. No new fields on <code>TokenContext</code>; uses the existing <code>allowedActors</code> roster the multi-DO redeem path already populates. The DRR-deferred and audit-blocked email paths are unaffected (they\'re gated on <code>!dcoActuallySubmitted</code>, so this Cc only fires alongside an actual submitted DCO).' }
        ]
    },
    {
        date: 'June 8, 2026',
        title: 'IMS Review &middot; stop sending the "[New DCO]" stakeholder email when the DCO did not actually auto-submit',
        items: [
            { badge: 'fix', text: '<strong>The toolkit no longer claims a DCO was submitted when it actually got stuck in Pending.</strong> Until today, the "[New DCO]" stakeholder notification was gated only on the DRR-side push having failed &mdash; so when <code>changeStatus(Submit)</code> on the DCO itself bounced (e.g. a different DCOAudit Pre-Submit check fired, like "blank Training Requirement" on an Automotive doc), the misleading "[New DCO]" went out anyway and the recipients found a Pending DCO in Agile. DCO-530023 on 2026-06-08 triggered this: N/A on Referenced Documents was set correctly, but the Training Requirement audit kicked changeStatus, and stakeholders still got "submitted" emails for a DCO that never moved. Fixed by gating on the actual <code>submitted=true</code> flag from the agile-service response.' },
            { badge: 'new', text: '<strong>When the DCO is created but the auto-submit fails for a non-DRR reason, two new emails now fire</strong> &mdash; an "action needed" note to the signer with the Agile audit message verbatim, a one-click "Open DCO in Agile" button, and clear instructions to fix the field on the affected Document and click Next Status &rarr; Submit manually. PLM IT also gets a parallel diagnostic email (Cc\'s the signer) so they can decide whether the missing field is worth a toolkit pre-fill on the next IMS Review cycle. Beats the previous behaviour where the [New DCO] stakeholder email went out claiming success while the DCO sat in Pending.' },
            { badge: 'fix', text: '<strong>The DCO-form success page now matches reality.</strong> Until today, the "Your response is recorded" page closed out with <em>"DCO X created and submitted. N file(s) attached. N stakeholder(s) notified. Doc Control has been notified."</em> regardless of whether the auto-submit actually went through. When submit was blocked (DRR-side or audit-side), the page now drops the "submitted" / "stakeholder(s) notified" / "Doc Control has been notified" lines and instead surfaces an amber follow-up directing the signer to check their inbox for the manual-submit instructions (or, for the DRR-blocked case, the friendlier "PLM IT is tracking" note).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> in <code>ImsReviewService.respondViaTokenWithDcoForm</code> (around line 3035), the post-create branch now reads <code>cr.body.get("submitted")</code> and <code>currentStatus</code> in addition to <code>dcoSubmitSkippedDueToDrr</code>. Three states: (a) <code>dcoSubmitSkippedDueToDrr=true</code> &rarr; existing DRR-deferred email path; (b) <code>submitted=false</code> without (a) &rarr; new <code>IMS_REVIEW_DCO_SUBMIT_FAILED</code> activity event + new <code>ImsReviewEmailService.sendDcoSubmitFailedEmails</code> which renders user + IT HTML mirroring the DRR-deferred layout but with copy honestly saying "Pre-Submit audit blocked changeStatus"; (c) <code>submitted=true</code> &rarr; unchanged, the <code>[New DCO]</code> stakeholder notification fires. Stakeholder skip-gate at line 3080 changed from <code>dcoSubmitSkipped</code> to <code>!dcoActuallySubmitted</code> so both failure modes suppress the misleading email. The "(submit failed: &lt;err&gt;)" envelope <code>DcoRichCreationService</code> writes into <code>currentStatus</code> is unwrapped for the email body so the recipient sees just the SDK message, not the toolkit-side prefix.' }
        ]
    },
    {
        date: 'June 8, 2026',
        title: 'IMS Review &middot; "Needs Change" auto-submit no longer bounces on the "Referenced Documents N/A" audit (now selects the real placeholder Part)',
        items: [
            { badge: 'fix', text: '<strong>The auto-submit of a "Needs Change" DCO no longer leaves the DCO stuck in Pending</strong> because the affected Document\'s Page Two &rarr; Referenced Documents field is blank. Earlier today\'s shipped fix attempted to write the literal string "N/A" into the cell &mdash; that turns out to be invalid (the cell is a strict MultiList of Items, not free text), so the audit still bounced (DCO-530023 on 2026-06-08). The cell\'s actual remediation, as DCC does it manually, is to select a real placeholder Part numbered "N/A" that already exists in Agile. The toolkit now looks up that IItem and stamps the Document\'s cell with it before submit; the DCOAudit pre-submit check sees a non-empty value and lets the DCO advance. Pre-existing values are still never overwritten.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> in <code>plm-agile-service</code> <code>DcoRichCreationService.create()</code> Step 5.5, the write path changed from <code>setValue("N/A")</code> + list-string-fallback to: <code>session.getObject(IItem.OBJECT_TYPE, "N/A")</code> &rarr; <code>cell.getAvailableValues()</code> &rarr; <code>list.setSelection(new Object[]{ naItem })</code> &rarr; <code>cell.setValue(list)</code>. Same setUserListCell pattern we already use for Document Owner / Approvers. If the "N/A" Item isn\'t found in Agile (unlikely &mdash; confirmed present at Number=N/A, Lifecycle=Preliminary), the step logs <code>[AGILE-WRITE-NOTE] setDocReferencedDocsToNA skipped</code> and continues so the existing "submit failed" path still leaves the DCO in Pending for DCC to handle manually. The success path appends <code>setDocReferencedDocsToNA</code> to <code>stepsOk</code> so the <code>dco-create</code> JSONL records which DCOs were pre-emptively fixed.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Review &middot; overdue DO emails &mdash; option buttons are clickable again in Outlook',
        items: [
            { badge: 'fix', text: '<strong>"No Change Needed" / "Needs Change &mdash; Upload" / "? Need Help" buttons in the IMS Document Review email were rendering as plain text in Outlook</strong> &mdash; no hover cursor, no right-click "copy link", clicks went nowhere. Jimmy Sessumes reported it 2026-06-05 (overdue notice for doc 00-04-WW-02-00003). Now clickable end-to-end; the secure response page opens as designed.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the template <code>src/main/resources/templates/email/ims-review-do.html</code> wrapped each option card as <code>&lt;a&gt;&lt;table&gt;&hellip;&lt;/table&gt;&lt;/a&gt;</code>. Outlook on Windows renders email HTML through Word\'s engine, and Word\'s HTML model strips the anchor when a block-level <code>&lt;table&gt;</code> is its child. The Document Number and DRR links survived because they use the inline-anchor pattern (<code>&lt;a&gt;&lt;span&gt;</code> inside a <code>&lt;td&gt;</code>). Refactored all three cards to <code>&lt;table&gt;&lt;tr&gt;&lt;td&gt;&lt;a&gt;&hellip;&lt;/a&gt;&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</code> &mdash; the table now carries the colored border + <code>margin-bottom</code>; the anchor wraps only the two inline <code>&lt;div&gt;</code>s. Audited every other template under <code>templates/email/</code>; no other instance of the anti-pattern was found. <code>ims-review-dm.html</code> (Approve / Send Back) already used the safe pattern.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Dashboard &middot; new "Owner Audit" tab + "Max overdue" filter + toolkit marker in Agile history',
        items: [
            { badge: 'new', text: '<strong>New "Owner Audit" tab</strong> shows every Document Owner change recorded in Agile for IMS docs, with the real instigator surfaced where possible. Toolkit-driven changes show the AD user who clicked Save in the IMS Dashboard (green "Toolkit" pill); manual Agile UI changes show as "Direct in Agile" (amber pill). Defaults to the last 90 days; toggle to 30/180/365. Search across doc number, owner names, and instigator. Jimmy Sessumes\'s 2026-06-05 chat ask: <em>"identify that action in the history of the part in a way we can run reports on or pull data automatically into the tool."</em>' },
            { badge: 'new', text: '<strong>The Agile item-history now carries a "Owners updated via PLM Toolkit IMS Dashboard by {AD user}" marker</strong> on every owner write the toolkit performs. Lets the Owner Audit tab distinguish toolkit-driven changes from manual Agile UI edits even though Agile\'s own audit always shows the SDK service account as the actor. Also handy for ad-hoc reporting outside the toolkit.' },
            { badge: 'new', text: '<strong>New "Max overdue" filter on the IMS Dashboard.</strong> Hides docs whose next-review date is more than N years overdue (default: 5 years). Stops the 2010-era legacy heap from drowning out the actionable backlog. Toggle in the toolbar: 1y / 2y / 5y / 10y / unlimited. Jimmy\'s 2026-06-05 chat ask. Status line shows "hiding N docs overdue by more than 5y" when the filter trims anything.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>OwnerChangeAuditService</code> reads <code>AGILE.ITEM_HISTORY</code> for SUBCLASS 9141 over the requested window, looking for two row patterns: (1) the Agile auto-generated cell-change row with <code>DETAILS LIKE \'%Document Owner%\'</code>, and (2) the toolkit marker row with <code>DETAILS LIKE \'Owners updated via PLM Toolkit%\'</code>. Java pairs each cell-change with its closest marker (same item, within 10s) to recover the AD user from the marker text. Owner-name parsing handles Agile\'s actual DETAILS format <code>"&lt;Document Details.Document Owner(s)&gt;was&lt;OLD&gt;is&lt;NEW&gt;"</code> (not the "from X to Y" I initially assumed) and strips the trailing employee-id parens for display. <code>ItemOwnerService.updateOwners(item, loginIds, notedBy)</code> writes the marker via <code>IItem.logActionWOVersionChange()</code> &mdash; new param threaded through the toolkit&rarr;agile-service hop. <code>ImsReviewService.dataForAdmin(daysAhead, maxOverdueYears)</code> applies the cutoff filter at read time so the docs cache stays keyed by daysAhead alone (no cache fragmentation). New tab added to <code>UserPermissionsService.TAB_CATALOG</code> as <code>owner-audit</code> (same access boundary as <code>ims-review</code>).' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Review &middot; auto-retry IMS Review write-backs when agile-service is briefly unreachable',
        items: [
            { badge: 'improve', text: '<strong>When the IMS Review write-back fails because plm-agile-service is unreachable, the toolkit now auto-retries instead of stranding the event.</strong> Backoff: 1 min → 5 min → 15 min → 60 min. If all four attempts fail, pdl-plm-admin gets an alert email so a human can investigate. The agile-service watchdog (shipped in the same release) should make this near-zero-fire in practice, but it\'s the seatbelt in case of a slow restart or a network blip.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ImsReviewRetryQueue</code> persists pending retries to <code>./data/ims-review/retry-queue.jsonl</code> (append-only JSONL, atomic temp-file-and-move snapshot on every mutation, survives JVM restarts). New <code>@Scheduled(fixedDelay=60s, initialDelay=30s)</code> runner in <code>ImsReviewService.runRetryQueue</code> processes due entries via the same <code>replayWriteback</code> the admin endpoint uses. <code>runAgileWriteBack</code> only enqueues when the failure is at <code>find-drr</code> AND the error matches <code>isTransientTransportError</code> (ConnectException / Connection refused / SocketTimeout / etc.) &mdash; nothing was committed in Agile yet, so blind retry is safe. Later step-level failures still go to the operator via <code>/replay-writeback</code> (per-step idempotency tracking would be needed before auto-retrying those). New admin endpoints: <code>GET /api/ims-review/admin/retry-queue</code> (dump pending + given-up entries) and <code>POST /api/ims-review/admin/retry-queue/remove {id}</code> (drop a stuck entry after manual replay). Activity log entries: <code>IMS_REVIEW_RETRY_ENQUEUED</code>, <code>IMS_REVIEW_RETRY_SUCCEEDED</code>, <code>IMS_REVIEW_RETRY_ATTEMPT_TRANSIENT_FAIL</code>, <code>IMS_REVIEW_RETRY_ATTEMPT_PERMANENT_FAIL</code>.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'Infrastructure &middot; agile-service now has a watchdog (no more silent 22-hour outages)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>plm-agile-service now respawns automatically if the JVM exits for any reason.</strong> Previously the launcher (<code>run-agile.bat</code>) was one-shot &mdash; when the JVM died (OOM, host log-out, uncaught error during a long idle period) port 8081 stopped listening and every per-user write-back / IMS Review cascade silently failed until somebody noticed and re-ran it manually. That gap stranded Bibi\'s DRR-0018775 for ~22 hours on 2026-06-04/05. The new <code>run-agile-loop.bat</code> mirrors the toolkit\'s <code>run-loop.bat</code>: respawns within 5 seconds of any JVM exit, logs every restart to <code>watchdog-agile.log</code>, honors a <code>STOP_AGILE</code> sentinel so the operator can take it down between iterations. <code>stop-all.bat</code> now stops both watchdogs cleanly; new <code>stop-agile.bat</code> takes down only agile-service (port 8081) and leaves the toolkit running. Cutover: <code>start "PLM Agile Watchdog" D:\\plm-toolkit\\run-agile-loop.bat</code> from the Windows server.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Review &middot; admin "replay writeback" for DRRs stuck by a deploy-window outage',
        items: [
            { badge: 'new', text: '<strong>New admin endpoint replays a failed IMS Review Agile write-back from the persisted queue.</strong> When plm-agile-service is briefly unreachable (e.g. mid-redeploy), a Doc Owner or DM response lands in the toolkit\'s queue and the email goes out &mdash; but Agile never receives the attestation attach / history line / status push, so the DRR sits in Pending with only a partial set of attachments. The new tool rebuilds the (event, ctx, verifier) tuple from queue.jsonl and reruns the same writeback the live response handler would run. Confirmed root-cause of Bibi\'s DRR-0018775 stuck-Pending case (Jimmy Approve at 10:29:09 hit "Connection refused: getsockopt" because the agile-service JVM was being swapped).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>POST /api/ims-review/admin/replay-writeback</code> with body <code>{docNumber, drrNumber, eventType?}</code>. <code>ImsReviewService.replayWriteback</code> walks the queue item\'s history backward to the latest event matching <code>eventType</code> (defaults to the last <code>DO_RESPONSE_*</code> / <code>DM_RESPONSE_*</code>), restores <code>pdfPath</code> from a sibling <code>PDF_GENERATED</code> event with the same actor role (needed because the JVM restart loses the in-memory pdfPath mutation on the response event), reconstructs <code>TokenContext</code> from a <code>DocRow</code> lookup, reconstructs <code>VerifyResult</code> from the response event\'s stamped <code>verifiedSamAccount</code> / <code>verifiedDisplayName</code> / <code>verifiedEmail</code>, then calls the same <code>runAgileWriteBack</code> the live path uses. Admin-only via <code>hasAdminOrDccAccess</code>. Activity log records <code>IMS_REVIEW_AGILE_WRITEBACK_REPLAYED</code> with the new corrId so the audit trail shows both attempts. Idempotency caveat: re-running a SUCCESSFUL writeback would duplicate the attach + history &mdash; only run after confirming the original failed.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Dashboard &middot; reassign email no longer mentions that the previous owner has left SanDisk',
        items: [
            { badge: 'improve', text: '<strong>The owner-reassign email is simpler and no longer carries person-left information.</strong> The red "has left SanDisk" pill next to the replaced owner\'s name &mdash; and the prominent "Heads up: the previous Document Owner is no longer at SanDisk" callout above the document details &mdash; are removed. Both leaked HR-sensitive attrition info to whoever the reassignment email landed in front of. The Replaced line now shows bare names only; the new owner still sees who they\'re replacing without the context being broadcast. Reported by Vikas 2026-06-05.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> in <code>ImsReviewEmailService</code>, <code>buildRemovedOwnersListHtml</code> no longer reads <code>leftCompany()</code> to decorate departed owners with a red pill or "(Cc\'d)" tag &mdash; it just emits bare names. <code>buildLeftCompanyCalloutHtml</code> is now a no-op shim returning the empty string so the template\'s <code>${leftCompanyCallout}</code> placeholder resolves cleanly without layout shift. The Cc-vs-skip logic on the email envelope still consults <code>leftCompany()</code> so departed owners aren\'t Cc\'d (no bounce) &mdash; it just doesn\'t expose that decision in the body.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Dashboard &middot; segmented filter overhaul: per-owner badges, In-Flight/Closed re-defined, new Legacy Documents section',
        items: [
            { badge: 'improve', text: '<strong>"Ready to send" now requires a DRR.</strong> Previously it included any NOT_SENT doc that had at least one valid owner &mdash; even docs the yearly-audit job hadn\'t opened a DRR for yet (legacy docs). Those would land in your bulk-send queue and fail at send-time. Now Ready to Send = has DRR + at least one valid owner, and legacy docs get their own tab.' },
            { badge: 'improve', text: '<strong>"Needs owner" now means <em>every</em> assigned owner has left SanDisk</strong>, not just one of them. A row with "Shamlou, Danny (left); Sessumes, Jimmy (active)" no longer shows up as Needs Owner &mdash; Jimmy is still able to act on the review. The red banner ("N documents need a new owner") counts only the strict all-left case now and uses the wording "every assigned Document Owner has left SanDisk".' },
            { badge: 'improve', text: '<strong>Per-owner badges in all tabs.</strong> The Needs-Owner tab used to show a single "&#8856; owner left" badge after the comma-separated owner list, which hid which specific owner was gone. Every tab now shows the per-owner indicator inline (e.g. "Shamlou, Danny &#8856; left SanDisk; Sessumes, Jimmy") so it\'s obvious which name to act on. Reported by Vikas 2026-06-05.' },
            { badge: 'improve', text: '<strong>"In flight" now includes DO_NEEDS_CHANGE.</strong> When the doc owner submits Needs Change, the auto-cascade creates a DCO that sits in Submit/CCB until the workflow runs through. That intermediate state used to count as "Closed" &mdash; misleading because the document isn\'t actually released yet. Closed now means only DM_APPROVED (DM signed off no-change) or CANCELLED. The pipeline funnel on top of the dashboard reflects this too.' },
            { badge: 'new', text: '<strong>New tab: Legacy Documents.</strong> Documents the yearly-audit job hasn\'t created a DRR for yet land here, with sub-filter pills "All legacy", "Valid owners", "No valid owners" so you can quickly see which legacy docs are still actionable vs which need ownership review before anyone can act on them. Useful for the historical baseline you took over from the previous owner.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ImsReviewService.dataForAdmin</code> now stamps three flags on every row &mdash; <code>hasDrr</code>, <code>hasValidOwner</code>, <code>allOwnersLeft</code> &mdash; so the client doesn\'t re-derive them per render. New KPI counters: <code>kpiAllOwnersLeft</code> (the strict banner count), <code>kpiLegacyTotal</code>, <code>kpiLegacyValidOwner</code>, <code>kpiLegacyNoValidOwner</code>. The DO_NEEDS_CHANGE bucket moved from <code>closed++</code> to <code>inFlight++</code>. <code>rowMatchesSegment</code> in <code>imsreview.js</code> falls back to the older "any missing" semantics when the server payload predates the new flags, so a JAR/JS skew window during deploy still renders sensibly. Cache-buster bumped to <code>20260605-legacy</code>.' }
        ]
    },
    {
        date: 'June 5, 2026',
        title: 'IMS Dashboard &middot; "Needs Change" auto-submit now sets DRR Requestor + UpdateRequired=Yes, and skips the misleading stakeholder email when DCO submit is deferred',
        items: [
            { badge: 'fix', text: '<strong>DRR auto-submit no longer fails with "Requestor cannot be Administrator".</strong> When you sign the DCO form, the toolkit now stamps the DRR\'s Requestor with the first valid Document Owner before pushing the DRR to Submit (the production yearly-audit job creates DRRs with Requestor=Administrator, which the DRR-Workflow rejects). Reported by Bibi Kolam against DRR-0018774 on June 5.' },
            { badge: 'fix', text: '<strong>DRR "Document Update Required" is now set to "Yes" when a DCO is created</strong> (was being set to "No"). The audit-trail standard at SanDisk is "Yes" when a change is needed, "No" when the doc owner signed off no-change. The "No" was a safeguard against the DocumentReview PX auto-creating a duplicate DCO; we now rely on the DCO Number cell (1575) being stamped before Submit, which is the PX\'s real duplicate-detection check.' },
            { badge: 'fix', text: '<strong>The "[New DCO]" stakeholder notification is no longer sent when the DCO auto-submit was deferred.</strong> Previously: when the DRR push failed (e.g. the Administrator-Requestor case above), three emails fired for a single event &mdash; the deferred-notice to the DO, the diagnostic to PDL-PLM-admin, AND the stakeholder "[New DCO]" email claiming the DCO was created and submitted. Now only the truthful deferred-notice fires; once PLM IT resolves the blocker and the DCO actually submits, Agile\'s own workflow notifies the stakeholders.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> in <code>DcoRichCreationService.create()</code> Step 9.5, added a (0) step that resolves the first valid Document Owner from <code>req.form.documentOwners</code> via <code>resolveUserPicks()</code> and stamps <code>ATT_COVER_PAGE_ORIGINATOR</code> on the DRR. Changed (a) <code>UpdateRequired</code> value from "No" to "Yes". In <code>ImsReviewService</code>, wrapped <code>sendStakeholderNotify</code> in <code>if (!dcoSubmitSkipped)</code> &mdash; activity log gets <code>IMS_REVIEW_STAKEHOLDER_NOTIFY_SKIPPED</code> when skipped so we can audit. Mirrors the proven pattern in <code>AgileWriteBackService.createDcoViaDrrSubmit</code> lines 603-659.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'IT Enhancements &middot; Sign-in-to-Agile modal for per-user write-back (Agile here is not AD-integrated)',
        items: [
            { badge: 'fix', text: '<strong>Save All Changes was failing with "Invalid username or password [errorCode=60062]"</strong> on the first prod attempt. Cause: the per-user write-back was sending the user\'s AD password to the Agile SDK, but Agile on this environment is not AD-integrated &mdash; it has its own user store with its own passwords. The AD password never matched what Agile expected.' },
            { badge: 'new', text: '<strong>The first time you click Save in a session, a small "Sign in to Agile" modal pops up</strong> asking for your <em>Agile</em> password (the one you use to sign in to Agile Web, not your Windows/AD password). The Agile loginid is pre-filled from your toolkit login. Hit Enter or "Sign in &amp; save" &mdash; the password is verified against Agile (transient SDK session, opened and closed in one round-trip), cached in this server\'s memory for the rest of your session, and the pending Save is auto-retried. Subsequent saves in the same session don\'t re-prompt. Hit Logout (or close the browser session) and the password is dropped.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>POST /api/agile/verify-user-credentials</code> on plm-agile-service that opens a transient SDK session with the supplied credentials, closes it, and reports back &mdash; never logs the password. New <code>POST /api/it-enhancements/agile-signin</code> on the toolkit calls verify, stashes the password as <code>agilePassword</code> on the <code>HttpSession</code>. <code>save-cell</code> / <code>save-batch</code> now read <code>agilePassword</code> (not <code>adPassword</code>) and respond with <code>needsAgileSignin:true</code> when missing or when the SDK returns errorCode 60062 / "Invalid username or password" (so a mid-session Agile password rotation triggers a re-prompt instead of silent failures). <code>AuthController.login</code> no longer stashes the AD password on session at all &mdash; one less secret in JVM memory.' },
            { badge: 'improve', admin: true, text: '<strong>Frontend:</strong> tiny modal in <code>index.html</code> + state in <code>it-enhancements.js</code>. When the save endpoint signals <code>needsAgileSignin</code>, the pending edits are remembered, the modal opens (Enter submits, Esc cancels), and on successful verify the edits are auto-retried via <code>runSaveBatch(edits, afterSignin=true)</code>. The afterSignin flag prevents an infinite re-prompt loop on the unlikely case verify passes but the save still fails 60062.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'IT Enhancements &middot; JSON cache + hourly refresh + blank-date filter pills',
        items: [
            { badge: 'improve', text: '<strong>IT Enhancements now reads from a JSON snapshot</strong> rather than re-running the agprod SQL on every tab open. Snapshot lives at <code>./data/it-enhancements-cache.json</code>, loads on startup so the first user after a deploy doesn\'t pay the SQL cost, and is rebuilt hourly in the background by a <code>@Scheduled</code> task at minute 0 of every hour. The toolbar now shows a "Snapshot · Nm ago" freshness indicator (hover for the exact cache timestamp) plus a "Refresh now" button that forces a re-pull from agprod when you need the latest data immediately.' },
            { badge: 'new', text: '<strong>Blank-date filter pills</strong> sit below the toolbar with live counts: "Target UAT (N)", "Target Go-Live (N)", "Submit date (N)", "Release date (N)". Click a pill to show only rows missing that date; click multiple to find rows missing all of them (e.g. Target UAT AND Target Go-Live blank, useful for finding ECNs that haven\'t been scheduled yet). Pills with zero matches dim but stay clickable.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ItEnhancementsService</code> now has <code>cachedRows</code> + <code>cachedAt</code> guarded by <code>this</code>, atomic temp-file-and-move persistence (so a half-written snapshot can never be loaded), and the <code>_kind</code> band classification is recomputed on every read (not cached) so the bands stay correct across midnight even if the snapshot is hours old. New <code>POST /api/it-enhancements/refresh</code> endpoint for the "Refresh now" button (same auth gate as <code>/data</code>). The hourly schedule is gated by <code>app.scheduling.disabled</code> via <code>Application.SchedulingConfig</code> like every other scheduled job — local dev never fires it. <code>/data</code> response now includes a <code>blanks</code> object with per-date-column blank counts so the UI can render the pills without re-scanning the row list.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'New tab &middot; IT Enhancements &mdash; live Agile dashboard with per-user Target UAT write-back',
        items: [
            { badge: 'new', text: '<strong>New "IT Enhancements" tab</strong> for PLM IT (auto-granted to anyone in the <code>PDL-PLM-admin</code> AD group; can also be granted per-user via User Management). Mirrors the weekly Excel report ("All Enhancements" sheet) as a live grid against agprod &mdash; sub-second load, 18 columns spanning ECN / Priority / IT Status / IT Owner / Requestor / Category / Problem Statement / Proposal / Hours / Project / IT Actions Taken / Target UAT / Target Go-Live / Rework Reason / IT Log / Submitted / Released / Workflow. Top of the panel shows three counts: Overdue UAT (red), Overdue Go-Live (amber), Approaching Go-Live in &le;7d (blue), each clickable to filter the grid. Search, IT-owner, and IT-status filters in the toolbar.' },
            { badge: 'new', text: '<strong>Click the Target UAT cell to edit it inline.</strong> The save is written to Agile using YOUR own AD identity (not Administrator), so the change\'s History tab attributes the modification to you. Multiple edits queue up as a dirty count next to "Save all changes" &mdash; one click writes them in a batch and the toast reports how many succeeded vs failed.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ItEnhancementsService.readAll()</code> runs the saved-search "IT ECN\'s" universe (workflow=Review with IT request-classification, OR has-IT-Status + released-in-last-7-days) and joins <code>page_three</code> + <code>agile_flex</code> + <code>agile_flex_clob</code> + <code>listentry</code> in one query &mdash; ~90 rows back in &lt;1s. <code>ItEnhancementsController</code> exposes <code>GET /data</code>, <code>POST /save-cell</code>, <code>POST /save-batch</code> &mdash; all gated by the <code>it-enhancements</code> entry in the session\'s <code>allowedTabs</code>. Per-user write goes through a new <code>PerUserChangeUpdateController</code> in plm-agile-service that opens a TRANSIENT Agile SDK session with the caller\'s cached AD password and closes it in <code>finally</code>; cell name is gated by a server-side allowlist (currently just <code>Page Three.Target UAT Date</code>) for defense-in-depth, and the password is never logged.' },
            { badge: 'improve', admin: true, text: '<strong>Credential handling:</strong> <code>AuthController.login</code> now stashes <code>adGroups</code> + <code>adPassword</code> (plaintext, in-memory only) on the <code>HttpSession</code> when <code>loginSource == "ad"</code>. The plaintext lives in JVM heap, on the session object, for the lifetime of the session (30 min idle, max 12 hr); it is never written to disk (no session persistence configured), never logged (verified across <code>AuthController</code>, <code>ItEnhancementsController</code>, <code>AgileWriteBackClient</code>, and the agile-service controller), and freed when the user logs out (which invalidates the session). Cached-login and emergency-login paths do NOT have access to the plaintext, so they see a clean "Re-login required to save" error from the toolkit instead of an opaque SDK failure.' },
            { badge: 'improve', admin: true, text: '<strong>AD-group auto-grant:</strong> <code>UserPermissionsService.getAllowedTabs(username, isAdmin, adGroups)</code> auto-adds <code>it-enhancements</code> when the user\'s AD groups include <code>pdl-plm-admin</code> (configurable via <code>app.it-enhancements.auto-grant-ad-group</code> in <code>application.properties</code>). The membership check happens once at login (no per-request LDAP probe) by stashing <code>adGroups</code> on the session.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'IMS Dashboard &middot; Refresh button now actually shows it\'s working',
        items: [
            { badge: 'fix', text: '<strong>The IMS Dashboard Refresh button felt dead.</strong> Cause: the loading message ("Running Agile query&hellip;") was anchored to the BOTTOM of the panel, below the table and KPI tiles &mdash; out of sight while you were looking at the toolbar where you just clicked. After 22 seconds the data would re-render with no visible change in the numbers (especially if the LDAP race had left owner statuses unenriched), so it looked like the click did nothing. Now Refresh fires the same top-right pending toast the Send button uses ("Refreshing IMS Dashboard&hellip;"), and on completion the toast swaps to "Refreshed &mdash; 1,473 documents." so you have unambiguous feedback throughout the wait.' },
            { badge: 'improve', text: '<strong>Tooltip updated:</strong> the Refresh button\'s hover text used to say <em>"bypasses 5-min cache"</em>, but the TTL was bumped to 60 min when the disk-persistent snapshot landed yesterday. Now reads <em>"~22s &mdash; bypasses the 60-min docs cache and queues a fresh LDAP enrichment"</em> so you know what to expect.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'Browser cache &middot; UI string changes now take effect on next page load &mdash; no more hard-refresh',
        items: [
            { badge: 'fix', text: '<strong>The toolkit\'s HTML and JS responses now ship with <code>Cache-Control: no-cache, must-revalidate</code>.</strong> Symptom this addresses: after a UI string change (e.g. today\'s IMS Review &rarr; IMS Dashboard rename), logging out and back in still showed the OLD label because the browser served its cached <code>index.html</code> instead of asking the server. Now the browser is forced to revalidate the HTML / JS on every page load &mdash; the server returns a cheap <code>304 Not Modified</code> when nothing has changed, and ships the fresh bytes when it has. No hard-refresh required to pick up a UI ship anymore.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>NoCacheStaticFilter</code> (<code>@Order(-1)</code> so it runs before <code>MaintenanceModeFilter</code> and <code>AuthFilter</code>). On any request whose path ends in <code>.html</code>, <code>.js</code>, or <code>.css</code> (or is the bare <code>/</code> root), the filter sets <code>Cache-Control: no-cache, must-revalidate</code> + <code>Pragma: no-cache</code>. PNG / SVG / fonts are left alone &mdash; they rarely change and the extra revalidation traffic isn\'t worth it. Headers verified end-to-end against <code>/index.html</code>, <code>/app.js</code>, <code>/whats-new.js</code>, and <code>/sandisk-logo-red.png</code> (the PNG correctly skips the header).' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'IMS Dashboard &middot; "N owners need a new owner" banner no longer disappears after concurrent post-deploy /data hits',
        items: [
            { badge: 'fix', text: '<strong>The red "X documents need a new owner" banner sometimes disappeared (showing 0) after a post-deploy warmup,</strong> even though hundreds of docs still had owners who\'d left SanDisk. Root cause: when three concurrent <code>/data</code> requests fired during the 22-second SQL warmup window, the first LDAP-enrichment executor task lost its row reference to a race between the request thread\'s on-disk persist and the executor\'s cache re-read, returning early with "empty by the time we ran"; subsequent enrichment requests were blocked by the in-flight flag and never ran either. The snapshot persisted with 1,786 owners all marked UNKNOWN, so <code>kpiOwnerMissing</code> evaluated to 0. Today\'s prod snapshot was hit by exactly this. After this build deploys, the next refresh (or the next hourly tick) will repopulate the snapshot with correct LDAP statuses and the banner will return.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> changed <code>scheduleLdapEnrichment(int daysAhead)</code> to <code>scheduleLdapEnrichment(int daysAhead, List&lt;DocRow&gt; rows)</code> &mdash; the caller passes the exact list it just pulled, so the executor cannot race with a concurrent SQL pull or cache replacement. After enrichment, the executor writes the enriched rows back into <code>docsCache</code> (overwriting whatever\'s currently in the bucket for that key) and re-persists the snapshot, so the on-disk file always reflects enriched data once a cycle finishes. Both call sites (<code>docsWithCache</code> and the hourly <code>scheduledDocsCacheRebuild</code>) now pass <code>fresh</code> explicitly. <code>enrichmentInFlight</code> still dedups so rapid /data hits don\'t queue duplicate passes; semantically the dedup now means "an enrichment pass is already running on these rows", not "we already submitted a pass that may have lost its row reference".' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'Audit Trail &middot; Change History Action column no longer leaks the Prev Status (PT-81)',
        items: [
            { badge: 'fix', text: '<strong>The Action column on the Audit Trail &gt; Change History tab no longer prefixes status-change rows with the Prev Status value.</strong> Example before: a row going from <em>Released</em> to <em>Implemented</em> showed Action = <code>"Released &rarr; Implemented"</code>; now it shows just <code>"Implemented"</code> &mdash; matching what Agile\'s own History tab displays for the same row. The Prev Status and Next Status columns sit right next to Action, so the transition is still readable left-to-right (<code>Released | Implemented | Implemented</code>) without the duplication. Reported by Vikas Singh against ECO-133128-A.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> one-block change to <code>AuditTrailService.formatAction(eventType, prevStatus, nextStatus)</code>. For <code>eventType == 65</code> (Status Change), return <code>nextStatus</code> when present, else fall back to <code>prevStatus</code>, else the <code>EVENT_TYPE_LABEL</code> map\'s <em>"Status Change"</em> entry, else the generic <code>"Action &lt;n&gt;"</code> fallback. Comment block retained with the PT reference + the example change number so the rationale survives future grep.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'IMS Review renamed to IMS Dashboard (PT-80)',
        items: [
            { badge: 'improve', text: '<strong>The IMS Review tab is now called "IMS Dashboard"</strong> &mdash; the tab button, in-tab heading, voice-command label, response-page header, every email subject + brand pill, and the chatbot KB section all updated. The underlying "yearly Document Review" activity stays named the same in DO/DM email body copy ("IMS Document Review &mdash; …") because that\'s the request itself, not the tab brand. Voice + chatbot still match the old "IMS Review" phrasing as a synonym. Activity log event names keep the <code>IMS_REVIEW_*</code> prefix for backwards-compat with any saved queries. Reported by Jimmy Sessumes (PT-80).' },
            { badge: 'improve', admin: true, text: '<strong>Files touched:</strong> <code>index.html</code> (tab button + 2 in-tab headings), <code>app.js</code> (TAB_PREFS_CONFIG label + voice-command label), <code>imsreview.js</code> (bulk-send confirm), <code>ims-respond.html</code> (footer + Need Help help-text), <code>ImsReviewEmailService.java</code> (2 subject lines + 6 inline-HTML brand refs in the DCO-deferred user/IT emails + 2 DCC alert subjects), and the 7 email templates under <code>resources/templates/email/</code> (footer pills on all 7, plus the title/nav/eyebrow brand on <code>ims-review-dco-stakeholder-notify.html</code> + <code>ims-review-owner-reassigned.html</code>). Chatbot KB <code>app-knowledge.txt</code>: <code>TAB 17</code> heading renamed, USER VOCABULARY entry now lists both "IMS Dashboard" and "IMS Review" as synonyms so old phrasings still match, last-updated bumped to June 4.' }
        ]
    },
    {
        date: 'June 4, 2026',
        title: 'Single/Sole Source &middot; tab now actually appears for granted non-admins (fixes a default-hidden HTML leftover)',
        items: [
            { badge: 'fix', text: '<strong>The Single/Sole Source tab was disappearing for non-admin users even when an admin had explicitly granted them access.</strong> Symptom: the tab flashed for a fraction of a second on initial render, then vanished. Root cause: <code>index.html</code> still had <code>style="display:none;"</code> on the <code>tabSingleSole</code> button from when the tab was admin-only, and only the <code>isPlmAdmin</code> path in <code>app.js</code> knew how to un-hide it. <code>applyServerTabPermissions</code> only knows how to HIDE tabs not on the user\'s allowedTabs list &mdash; it can\'t un-hide one that started life as <code>display:none</code>. Dropped the inline <code>display:none</code> so everyone sees the button by default; the server-driven hide path then hides it for users without the grant. Reported by Vikas Jindal on 2026-06-04 (Vikas Singh\'s session).' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; per-row Send no longer asks "are you sure?" &mdash; just sends and tells you who got it',
        items: [
            { badge: 'improve', text: '<strong>Clicking the per-row Send button on the IMS Review grid no longer pops up the native browser confirm dialog.</strong> It just sends and shows a toast: <em>"Email sent to vikas.jindal@sandisk.com."</em> (the actual recipient pulled from the cascade response). The Cancel and Reset buttons next to it are an adequate safety net if you misclick. Bulk send (multiple selected rows) still confirms because the blast radius is larger.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; DCO Requestor fix actually works now &mdash; we were sending the email instead of the sAMAccount',
        items: [
            { badge: 'fix', text: '<strong>Follow-up to this morning\'s DCO Requestor change.</strong> The first fix landed correctly but the DCO\'s Cover Page Requestor was still showing "Administrator" because the toolkit was sending the signer\'s email (e.g. <code>vikas.jindal@sandisk.com</code>) and the Agile SDK\'s <code>IUser</code> lookup in this environment keys by <strong>sAMAccountName</strong> (e.g. <code>8252</code>) and returns null on email lookups. The agile-service log on DCO-525540 made this obvious: <code>setRequestor: user lookup returned null for vikas.jindal@sandisk.com &mdash; DCO Requestor stays as Administrator</code>. Fixed by switching the toolkit to send <code>v.samAccount</code> instead of <code>verifiedEmail</code> — same identifier the long-working <code>closeNoChange</code> / DRR Requestor flow has always used. Next DCO that comes through Needs-Change will show the actual signer\'s name.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review response page &middot; SanDisk red logo in the header now matches the rest of the site',
        items: [
            { badge: 'improve', text: '<strong>The IMS Review response landing page (<code>ims-respond.html</code> &mdash; the page recipients land on when they click an email link) now shows the red SanDisk logo in its dark navy top bar</strong>, matching the in-app header and the email footers we updated earlier today. The lowercase "sandisk &middot; PLM Toolkit" text brand is gone. Same <code>sandisk-logo-red.png</code> asset as the rest of the site &mdash; <code>AuthFilter</code> already lets <code>.png</code> requests through unauthenticated, so the token-only response page can fetch it cold.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review emails &middot; Document Owner is now always copied so they stay in the loop',
        items: [
            { badge: 'improve', text: '<strong>Every email coming off the IMS Review tab now has the Document Owner copied</strong> (either as the To recipient when the email is addressed to them, or on Cc otherwise). Three places that previously missed this: (1) the DCO stakeholder-notify email used to silently drop the signer from To &mdash; now keeps them on Cc; (2) the DM-approved closure email was Cc\'ing the DM\'s own address (a bug) &mdash; now Ccs every current Document Owner on the doc; (3) the diagnostic email PLM IT receives on a deferred DCO submit also Ccs the signer. The DO/DM cascade emails were already Cc\'ing the DO correctly; this change brings the rest of the system in line.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ImsReviewService.getCurrentDocOwnerEmails(docNumber)</code> reads the cached <code>DocRow.owners</code> and returns deduplicated active email addresses; new <code>addCcDedupOnPayload(Payload, addr)</code> helper mirrors the existing private one in <code>ImsReviewEmailService</code>. <code>sendStakeholderNotify</code> in <code>ImsReviewEmailService</code> still removes the submitter from To (avoids them seeing their own send at the top of the recipient list) but re-adds them to Cc via <code>addCcDedup</code>. Both DM-approved closure call sites (token-flow + legacy session) call <code>getCurrentDocOwnerEmails(ctx.docNumber)</code> and loop the results through <code>addCcDedupOnPayload</code> before <code>emailService.send(cp)</code>. The DCO-submit-deferred IT diagnostic builds a single-entry Cc list with the signer email when it isn\'t the same as the diagnostic DL. All inserts are idempotent (case-insensitive dedup) so re-running a flow doesn\'t double up.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; auto-generated DCO Requestor defaults to the Document Owner instead of "Administrator"',
        items: [
            { badge: 'fix', text: '<strong>When the toolkit creates a DCO from a Needs-Change response, the DCO Requestor field now defaults to the Document Owner who signed the form</strong> (e.g. Sessumes, Jimmy) instead of the SDK service account that shows as "Administrator". If the signer isn\'t a valid Agile user (rare &mdash; they\'d have to sign with AD credentials that don\'t map to an Agile account), the cell falls back to the Agile default which is still "Administrator". Reported by Jimmy on 2026-06-03 with DRR-0016599 / DCO-525533 as the example case.</p>' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>requestorEmail</code> field on <code>ValidateFormRequest</code> in plm-agile-service. Toolkit stamps it in <code>ImsReviewService.handleDcoFormSubmit</code> right alongside <code>docNumber</code> using the verified DO email from the AD password sign step. plm-agile-service\'s <code>DcoRichCreationService</code> has a new optional STEP 3b: <code>setRequestor</code> &mdash; resolves the email via <code>session.getObject(IUser.OBJECT_TYPE, ...)</code> and sets <code>ChangeConstants.ATT_COVER_PAGE_ORIGINATOR</code> on the DCO. Failures are non-fatal (logged via the existing cause-chain <code>describeError</code> helper) and silently fall back to the Agile session-user default. Doesn\'t affect the DRR Requestor (already correctly the DO per the earlier fix), the stakeholder notify cascade, or the DCO submit flow.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review emails &middot; SanDisk logo in the footer now matches the in-app brand mark',
        items: [
            { badge: 'improve', text: '<strong>Every IMS Review email now shows the red SanDisk logo in the footer</strong> &mdash; the same brand mark the toolkit header uses, replacing the lowercase "sandisk" text pill that prior emails had. Applies to DO/DM response emails, the DM-approved closure, the Needs Change DCC notification, the Need Help DCC notification, the DCO stakeholder notify, the owner-reassignment notice, and the new DCO-submit-deferred user + IT diagnostic emails. Consistent branding across the in-app UI, login page, and outbound mail.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> a single <code>p.vars.putIfAbsent("logoUrl", toolkitBaseUrl + "/sandisk-logo-red.png")</code> in <code>ImsReviewEmailService.sendOne</code> threads an absolute URL into every template, so the 7 templates under <code>resources/templates/email/ims-review-*.html</code> just reference <code>${logoUrl}</code> in their footers (replaced the inline pill markup). The 2 inline-HTML emails built in Java (DCO-deferred user + IT) now use <code>esc(toolkitBaseUrl) + "/sandisk-logo-red.png"</code> directly &mdash; their builder helpers dropped <code>static</code> to access the instance field. <code>AuthFilter</code> already lets <code>.png</code> requests through unauthenticated, so email clients fetch the logo cold from the email-link landing path. Asset reused from the existing <code>static/sandisk-logo-red.png</code> shipped with the JAR.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'AI Help chatbot &middot; full IMS Review knowledge added &mdash; ask process questions and get accurate answers',
        items: [
            { badge: 'new', text: '<strong>The AI Help chatbot now knows everything about IMS Review</strong>: the Send → DO → DM cascade, the 4-step DCO form, DRR/DCO auto-submit behavior (including the deferred-submit fallback when the DRR push fails), owner reassign + the "has left SanDisk" badging, token lockout / unlock, the Reset escape hatch, the Need Help path to DCC, and the bulk-reassign flow. Ask things like "is the DCO auto-submitted?" / "what happens when the DRR submit fails?" / "how do I reset a stuck cascade?" / "can non-admins reassign owners?" / "where do attestation PDFs go in Agile?" and you\'ll get the right answer pulled from the live KB.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> added a new <code>TAB 17: IMS REVIEW</code> section to <code>app-knowledge.txt</code> with ~85 lines covering the process, the DO/DM response paths, the DCO form\'s 4 steps, the DCO-submit-deferred flow, the DRR Requestor fix, common Q&amp;A, and the logged activity events. Also added 8 new entries to the existing <code>USER VOCABULARY → TAB MAP</code> section so the chatbot can match natural-language phrasings like "yearly review", "DM approval", "is the DCO submitted automatically", "owner left", "invalid password", "token lockout" to the right concept. Last-updated timestamp bumped from May 13 to June 3. No code change &mdash; the chatbot reads the file fresh on every prompt, so the new content takes effect as soon as the JAR is deployed.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review DCO form &middot; "Notify on submit" is now optional + SanDisk directory typeahead',
        items: [
            { badge: 'improve', text: '<strong>The "Notify these people on submit" field on the DCO form is now optional</strong> &mdash; observers and approvers already cover the audit notification need, so the form no longer blocks Step 2 when the field is left blank. When you do want to add stakeholders, <strong>start typing 2 or more characters and a SanDisk directory typeahead appears with matching names and email addresses</strong>; click a match to insert it into the list. Stakeholders to notify don\'t have to be Agile users (DLs and non-engineering contacts are common), so the lookup queries AD instead of Agile.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new token-gated endpoint <code>GET /api/ims-review/token/ad-search?token=&amp;q=&amp;limit=</code> on <code>ImsReviewController</code>; backs onto <code>LdapAuthService.searchDirectory(q, limit)</code> (same path the org-wide <code>/api/permissions/ad-search</code> admin endpoint uses) but auth is via a valid IMS Review token instead of a session, since the DCO form is opened from the email link. Returns <code>{results:[{sAMAccountName, displayName, email}, ...]}</code>; clamps limit 1&ndash;50. Frontend: <code>fieldReq</code> swapped for <code>field(label, false, ...)</code>; new <code>notifyStakeholdersHtml(currentVal)</code> wraps the textarea in a <code>.dco-notify-wrap</code> with an absolute-positioned results panel; new <code>wireNotifyStakeholdersTypeahead()</code> uses a last-token-after-comma heuristic (the typed query is the trailing fragment after the last <code>,</code> or newline before the caret) so users can keep typing multiple stakeholders without losing previously-entered entries. Picked rows are inserted via <code>dcoNotifyInsertPick(email)</code> with a smart comma+space glue. <code>refreshNavButtons</code> still fires on every keystroke. Validator dropped the <code>m.push(\'notify list\')</code> line on Step 2.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review DCO form &middot; "No product line touched" radio shortcut on Scope step',
        items: [
            { badge: 'improve', text: '<strong>The DCO form\'s "Which product lines does this touch?" question now offers a clear "No product line touched" option</strong> instead of forcing the user to find and select <code>N/A</code> from the picker. Two radio choices appear above the picker: pick "Yes &mdash; this change touches specific product line(s)" for the normal multi-select chip flow, or pick "No product line touched (record as N/A)" to grey out the picker and submit <code>N/A</code> automatically. The choice is remembered if the user navigates back to Step 1 and forward again.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new state field <code>DCO.productLineMode</code> (<code>"specific"</code> default | <code>"none"</code>) on the form state object; new <code>productLineChooserHtml</code> wraps <code>pickerHtml(\'productLines\', …)</code> with two radio inputs and a <code>div#dco-pl-picker-wrap</code> that drops opacity to 0.45 and sets <code>pointer-events:none</code> when "none" is active. New global <code>dcoSetProductLineMode(mode)</code> toggles the wrap visuals, disables the picker input (so keyboard Tab can\'t land on it), pins <code>selectedValues.productLines</code> to <code>[\'N/A\']</code> when "none" or clears it back to <code>[]</code> when toggling out of "none". <code>wireStepInputs(step=1)</code> re-applies the mode after a render so step navigation preserves the choice. Backend gets <code>productLines:[\'N/A\']</code> &mdash; no controller/service change needed (N/A is a valid catalog value).' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; DCO auto-submit aborts cleanly when DRR submit fails &mdash; DO and PLM IT both notified',
        items: [
            { badge: 'fix', text: '<strong>When the toolkit creates a DCO via Needs-Change and the linked DRR can\'t be advanced from Pending to Submit, the DCO auto-submit is now aborted (was: attempted anyway and silently failed under Agile\'s ValidateDocument PX).</strong> The DCO is created fully populated with the revised file, attestation PDF, owners, approvers, and observers all in place &mdash; just left in Pending status for PLM IT to triage instead of being half-submitted and then rolled back. Example: DCO-525533 was created against DRR-0016599 but the DRR submit threw an opaque <code>PCAPIException</code>; previously the DCO submit was attempted and failed at the PX, leaving a confusing audit trail. Now the chain aborts cleanly at the DRR step.' },
            { badge: 'new', text: '<strong>Two follow-up emails fire automatically</strong> when the DCO submit is deferred: (1) the DO who signed gets a friendly business-style note that their submission landed, the DCO is created, and <em>PLM IT is tracking the submission &mdash; no further action needed</em>; (2) <code>pdl-plm-admin@sandisk.com</code> (configurable via <code>app.ims-review.it-diagnostic-dl</code>) gets the diagnostic email with the cause-chained SDK exception, corrId, doc/DRR/DCO numbers, signer identity, and timestamp so they can grep the agile-service.log for the full step trace and triage.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation, plm-agile-service:</strong> <code>CreateDcoRichResponse</code> grew two fields &mdash; <code>dcoSubmitSkippedDueToDrr</code> (boolean) and <code>drrSubmitErrorDetail</code> (cause-chained SDK message via new local <code>describeError</code> helper that walks <code>Throwable.getCause()</code> and includes every <code>APIException.errorCode</code>). <code>DcoRichCreationService</code> now sets these on the response when <code>pushDrrToSubmit</code> throws, and the STEP 10 <code>changeStatus</code> block early-returns with <code>currentStatus="Pending (DCO submit deferred — DRR still Pending)"</code>. Same flag is set when no Submit status exists on the DRR workflow at all. <strong>plm-field-tracker:</strong> <code>ImsReviewService.handleDcoFormSubmit</code> reads <code>cr.body.dcoSubmitSkippedDueToDrr</code> and on true emits a new <code>IMS_REVIEW_DCO_SUBMIT_DEFERRED</code> activity event and calls <code>ImsReviewEmailService.sendDcoSubmitDeferredEmails(ctx, v, dco, drrSubmitErr, corrId)</code>. New method builds two inline-templated HTML emails using the SanDisk palette and routes them through the existing redirect-mode pipeline. Failures on either email are logged but never block the DCO-created success path. <strong>Side effect:</strong> <code>AgileWriteBackLogger.stepFailed</code> now walks the cause chain too &mdash; every other PX/SDK failure across the codebase (Item owner reassign, DRR closure, change history posts) will surface a real error in the log instead of <em>"Call APIException.getRootCause() for details."</em>.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; Reassign email now spells out when the previous owner has left SanDisk',
        items: [
            { badge: 'improve', text: '<strong>The "Document Owner reassigned" email now shows a prominent red callout at the top when the previous owner has left SanDisk</strong>: <em>"Heads up: the previous Document Owner Jane Doe is no longer at SanDisk. That\'s why this document was reassigned to you &mdash; please pick up the review."</em> Earlier builds buried this in a small badge in the "Replaced" line; now the new owner sees the context immediately. Multiple departed owners are listed with proper grammar ("Jane Doe and John Smith are no longer at SanDisk").' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> two fixes. (1) <code>ImsReviewEmailService.sendOwnerReassignEmail</code> now freshens any old-owner <code>ldapStatus</code> that the docs cache marked <code>UNKNOWN</code> (or left null) via a live <code>LdapManagerLookup.checkUserStatus(email)</code> probe right before composing &mdash; previously, rows that were patched between cold pull and background enrichment carried UNKNOWN and <code>OwnerEntry.leftCompany()</code> would return false even for users that had genuinely left. New <code>OwnerEntry.needsStatusProbe()</code> helper gates the probe; small set (1&ndash;3 owners typically), so it\'s fine on the executor thread. (2) New template variable <code>leftCompanyCallout</code> + <code>buildLeftCompanyCalloutHtml</code> render a red <code>border-left:4px solid #B8342B</code> callout above the Document details block when at least one removed owner has <code>DISABLED</code>/<code>NOT_FOUND</code> status; renders empty when none. Existing per-owner badge in the "Replaced" line stays for completeness.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; DM approval no longer overwrites the DRR Requestor with the manager\'s name',
        items: [
            { badge: 'fix', text: '<strong>When the DM approves a No-Change DRR, the DRR\'s Requestor field now correctly stays as the Document Owner who initiated the confirmation</strong>, instead of being overwritten with the approving manager\'s name. Example: Jimmy (DO) confirmed No Change on DRR-0016599 and Pete (DM) approved &mdash; the Requestor used to flip to "Manks, Pete" on the cascade-to-CCB step, now stays as Jimmy. Existing DRRs that were already mis-stamped will not be auto-corrected (they need a manual cleanup in Agile); new DM-approve cascades from this build forward will be correct.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the <code>DM_RESPONSE_APPROVED</code> branch in <code>ImsReviewService</code> was passing <code>ev.verifiedSamAccount</code> &mdash; the DM\'s sAMAccountName &mdash; as the <code>requestorEmail</code> argument to <code>closeNoChange</code>, which writes <code>ATT_COVER_PAGE_ORIGINATOR</code> on the DRR. New helper <code>findDoSignerIdentity(docNumber, drrNumber)</code> walks the queue history backwards and returns the most recent <code>DO_RESPONSE_NO_CHANGE</code> event\'s <code>verifiedSamAccount</code> (handles Send Back + re-confirm cycles correctly). Falls back to the original <code>SEND_TO_DO</code> recipient email when no DO confirmation exists; falls back to null when nothing resolves, in which case <code>closeNoChange</code> skips Step 1 and leaves the existing DRR Requestor in place rather than overwriting it.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; Reset &amp; Unlock now available to anyone with the IMS Review tab',
        items: [
            { badge: 'improve', text: '<strong>The per-row &#x27F2; Reset and &#x1F511; Unlock controls are no longer admin-only.</strong> Any user who\'s been granted the IMS Review tab in User Management can now reset a stuck row (throw it back to Not Sent) or clear a password-attempt lockout on an active link &mdash; the same scope that already covers Reassign Doc Owner and the typeahead search. The "Admin only" hints in the row tooltips are gone. Auto-granted DO/DM users (who only see the card view) still can\'t reach these &mdash; they never see the admin grid.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> backend gates on <code>POST /api/ims-review/reset</code> and <code>POST /api/ims-review/admin/unlock-token</code> changed from <code>isPlmAdmin</code> to the existing <code>hasAdminOrDccAccess(session)</code> helper &mdash; same check <code>/admin/owner</code> and <code>/admin/users/search</code> already use. Frontend Reset / Unlock visibility flipped from <code>_state.meta.isAdmin</code> to <code>_state.meta.canSeeAdminView</code>. Admin <code>actAs</code> impersonation on <code>/respond</code> stays admin-only (different category &mdash; audit/security).' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; Reassign Doc Owner now emails the new owner with the file attached',
        items: [
            { badge: 'new', text: '<strong>When you reassign a Document Owner from the IMS Review grid, the new owner gets an email with the current Agile attachment, a link to the document, and a link to the related DRR.</strong> The removed owner is Cc\'d if they\'re still at SanDisk; if they\'ve left, the email body shows "<em>has left SanDisk</em>" instead so the new owner has the context. The Doc Control DL (defaults to <code>pdl-plm-admin@sandisk.com</code>, configurable via <code>app.ims-review.owner-reassign.cc-dl</code>) is always Cc\'d. Bulk reassign sends one email per doc.' },
            { badge: 'improve', text: '<strong>Wording cleanup across the toolkit:</strong> user-facing copy that used to say "Active Directory" / "not in AD" now says "SanDisk" / "left SanDisk" &mdash; the AD plumbing is unchanged, just the messages business users see. (The admin-only AD Health tab and the technical Tech Guide keep the literal "Active Directory" wording where it\'s describing infrastructure.)' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ImsReviewEmailService.sendOwnerReassignEmail(...)</code> wraps a new <code>ims-review-owner-reassigned.html</code> template and reuses the existing send / redirect / attachment machinery. New <code>OwnerEntry</code> DTO diffs old vs new rosters by <code>loginId</code> &mdash; added owners go in To, removed-and-still-ACTIVE go in Cc, removed-and-DISABLED/NOT_FOUND get the "left SanDisk" badge in the body instead. New properties <code>app.ims-review.owner-reassign.cc-dl</code> (default <code>pdl-plm-admin@sandisk.com</code>) and <code>app.ims-review.owner-reassign.enabled</code> (kill switch). <code>ImsReviewController.adminUpdateOwner</code> captures the pre-patch owner snapshot via new <code>ImsReviewService.findCachedDoc(docNumber)</code> helper, then queues the email on a dedicated <code>ims-reassign-email</code> single-thread daemon executor so the Agile attachment fetch (a few seconds) doesn\'t block the modal\'s Save response. Per-doc emails on bulk reassign (each Save call hits the endpoint independently). Failures are logged but never block or roll back the assignment.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; LDAP enrichment moved off the request thread &mdash; first-cold-deploy is now SQL-time, not LDAP-time',
        items: [
            { badge: 'improve', text: '<strong>The first IMS Review tab open after a deploy no longer waits on LDAP.</strong> Earlier today the first cold pull on prod took 9 min 45 s &mdash; almost all of it was 1,781 serial LDAP probes for AD account status. Now the docs come back as soon as the SQL pull finishes (typically tens of seconds), and the AD-status badges fill in on the next refresh after the background enrichment completes.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new single-thread daemon executor <code>ims-ldap-enrich</code> in <code>ImsReviewService</code>; <code>docsWithCache</code> now persists the SQL result immediately (so a JVM restart still skips the SQL even mid-enrichment) and calls <code>scheduleLdapEnrichment(daysAhead)</code> instead of running the enrichment inline. The executor task probes every owner via <code>LdapManagerLookup.checkUserStatus</code>, sets <code>OwnerRef.ldapStatus</code> on the cached rows, and re-persists the snapshot. A <code>Set&lt;Integer&gt; enrichmentInFlight</code> guards against stacking duplicate jobs when users hammer Refresh. The hourly rebuild also uses the same executor. <code>ownersWithStatus</code> on the read path no longer falls back to a live LDAP probe &mdash; it just emits <code>UNKNOWN</code> when <code>ldapStatus</code> is missing, which the UI renders as no badge (the visible badges are <code>DISABLED</code> and <code>NOT_FOUND</code> only). Net: the request thread is bounded by SQL only; LDAP cost is paid in the background, once per cache miss, with no stacking.' }
        ]
    },
    {
        date: 'June 3, 2026',
        title: 'IMS Review &middot; disk-persistent docs cache + hourly background rebuild &mdash; tab loads instantly',
        items: [
            { badge: 'improve', text: '<strong>The IMS Review tab now opens in milliseconds even right after a JVM restart.</strong> The "docs due for review" query (1,400+ rows pulled from Agile + LDAP status per owner) used to take ~100&ndash;120 seconds on a cold call &mdash; the first user after every deploy waited the full time. Now the cache survives restarts on disk and an hourly background job keeps it warm, so the cold case is essentially gone.' },
            { badge: 'improve', text: '<strong>Reassigning a Doc Owner is also instant now.</strong> Save used to trigger a full Agile rebuild (another 100+ second wait). Now the cache row gets patched in place with the new owners and the table refreshes immediately. The hourly job picks up any other Agile-side changes in the background.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> two cache layers in <code>ImsReviewService</code>. (1) In-memory map keyed by <code>daysAhead</code>, TTL bumped from 5 min to 60 min so the hourly rebuild beats expiry. (2) JSON snapshot at <code>./data/ims-review-docs-cache.json</code> &mdash; loaded in <code>@PostConstruct</code>, atomically rewritten (tmp + move) after every fresh pull or owner patch. New <code>scheduledDocsCacheRebuild()</code> runs <code>@Scheduled(cron = "0 0 * * * *")</code>, rebuilds every bucket the app has touched, persists, logs <code>[IMS-CACHE] Hourly rebuild done in N ms</code>. Buckets nobody has queried stay empty &mdash; no pre-warming windows nobody uses. <code>invalidateDocsCache()</code> (the Refresh button) now also deletes the snapshot file. New <code>patchOwnersInCache(docNumber, newOwners)</code> surgically replaces a row\'s owner list across all buckets, then persists. <code>POST /admin/owner</code> now accepts an <code>owners: [{loginId, displayName, email}, ...]</code> array (still falls back to bare <code>loginIds</code> for older frontends) and calls <code>patchOwnersInCache</code> instead of <code>invalidateDocsCache</code>; the frontend follows up with a normal <code>?refresh=false</code> fetch that hits the patched cache. Status overlay (NOT_SENT / SENT_TO_DO / etc.) is still computed fresh from <code>queueStore</code> on every read &mdash; send/respond/cancel mutations need no cache touch and never did. LDAP-status enrichment per owner is also unchanged; it was never the hot-path bottleneck.' }
        ]
    },
    {
        date: 'June 2, 2026',
        title: 'IMS Review &middot; Reassign Doc Owner now available to anyone with the IMS Review tab',
        items: [
            { badge: 'improve', text: '<strong>The &#9998; edit-owners pencil next to each Owner(s) cell is no longer admin-only.</strong> Any user who\'s been explicitly granted the IMS Review tab in User Management can now reassign Document Owners (writes back to Agile) &mdash; useful for DCC delegates who shouldn\'t need full PLM admin rights to fix a stale owner. Auto-granted DO/DM users (who only see their own card view) still don\'t get the pencil.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> frontend <code>canEditOwners()</code> in <code>imsreview.js</code> switched from <code>_state.meta.isAdmin</code> to <code>_state.meta.canSeeAdminView</code> (the same flag that already controls Export visibility &mdash; admin OR explicit <code>ims-review</code> grant, excluding auto-grant). Server-side <code>POST /api/ims-review/admin/owner</code> in <code>ImsReviewController</code> relaxed from a hard <code>isPlmAdmin</code> check to the existing <code>hasAdminOrDccAccess(session)</code> helper, which mirrors <code>/admin/users/search</code>. Reset / unlock-token buttons remain admin-only (no change).' }
        ]
    },
    {
        date: 'June 2, 2026',
        title: 'User Management &middot; Single/Sole Source Report is now grantable per-user',
        items: [
            { badge: 'improve', text: '<strong>The Single/Sole Source Report tab is no longer admin-only.</strong> The checkbox in the per-user permissions modal is now editable — admins can grant access to specific non-admin users without having to make them full PLM admins. The lock icon and "admin only" hint are gone.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> flipped <code>adminOnly</code> from <code>true</code> to <code>false</code> for <code>"singlesole"</code> in <code>TAB_CATALOG</code> (<code>UserPermissionsService</code>) and <code>TAB_REGISTRY</code> (<code>app.js</code>). Server-side defense-in-depth on <code>SingleSoleSourceController</code> moved from a hard <code>isPlmAdmin</code> session check to a tab-permission check via <code>UserPermissionsService.getAllowedTabs(username, isAdmin).contains("singlesole")</code> — so a non-admin who has the tab granted via the UI gets through, but anyone without the grant still hits 403 even if they know the URL. The check now mirrors the canonical "does this user see this tab" decision in one place instead of duplicating the admin gate.' }
        ]
    },
    {
        date: 'June 1, 2026',
        title: 'ECN Report &middot; New "KPI Classification" column in the RAW data sheets',
        items: [
            { badge: 'new', text: '<strong>Both raw-data sheets (Completed &amp; In Progress) now include a "KPI Classification" column</strong> (col P) showing the KPI bucket each ECN falls into — Standard ECNs, PDR ECNs, Dedicated Process, etc. Now you can use Excel\'s autofilter to slice the raw data by KPI type and reconcile against the dashboard numbers directly. Reported by Jimmy Sessumes (PT-79).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the KPI bucket isn\'t in the source SQL — it\'s derived from the <code>ecn-kpi-template.xlsx</code> Matrix sheet via a lookup keyed by <code>request_classification</code> (e.g. <code>"Engineering Change Requests|PDR ECN" &rarr; "PDR ECNs"</code>). Threaded <code>baseline_targets</code> through <code>generate_report &rarr; build_data_sheet &rarr; _write_data_rows</code> in <code>ecn_report_generator.py</code>. Added <code>"KPI Classification"</code> to <code>HEADERS</code> between Request Classification and Urgent Classification, shifting cols P&ndash;V to Q&ndash;W. <code>COL_WIDTHS</code>, <code>auto_filter.ref</code> (<code>A1:V &rarr; A1:W</code>), and the Days@&lt;Status&gt; center-align range (<code>range(18,23) &rarr; range(19,24)</code>) all updated to match. <code>"Unclassified"</code> fallback when the matrix has no entry, matching the existing dashboard sheet behavior. KPI Missed formula references col C (Priority) and H (Actual Days) — neither moves, so no formula changes needed.' }
        ]
    },
    {
        date: 'June 1, 2026',
        title: 'Reliability &middot; Overnight idle-window Full GC heads off heap pressure',
        items: [
            { badge: 'new', text: '<strong>The JVM now self-cleans during the overnight quiet hours.</strong> Every 15 minutes between <strong>21:00 and 06:00 PT</strong>, the toolkit checks heap usage and, if it\'s above 70% AND no end users have touched the server in the last 5 minutes, runs a Full GC to reclaim garbage that G1 hasn\'t collected. After multi-day uptime + nightly BOM batches a fair chunk of "used" heap is just uncollected garbage; this catches it before it crosses the 85% alert threshold and emails go out.' },
            { badge: 'improve', text: '<strong>No impact during business hours or while anyone is using the app.</strong> All three gates (time window, zero active users, above-threshold) must hold for the GC to fire. If a single user is active, the run is skipped silently. Logs <code>[HEAP-GC] Triggering Full GC — used=NN% (NN MB / NN MB), zero active users …</code> followed by a reclaim summary, so you can grep <code>HEAP-GC</code> in <code>plm-toolkit.log</code> the next morning to see what happened.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>idleWindowGc()</code> in <code>HeapPressureMonitor</code>, scheduled <code>@Scheduled(fixedRate = 15 * 60_000L, initialDelay = 5 * 60_000L)</code>. Time check uses <code>LocalTime.now(ZoneId.of("America/Los_Angeles"))</code> so it follows PT across DST. Idle check uses <code>sessionRegistry.listActive(5 * 60_000L)</code> — same primitive the deploy/maintenance flow already uses. New props with sensible defaults: <code>app.heap-gc.enabled=true</code>, <code>app.heap-gc.threshold-pct=0.70</code>, <code>app.heap-gc.window-start-hour=21</code>, <code>app.heap-gc.window-end-hour=6</code>, <code>app.heap-gc.idle-window-minutes=5</code> — none need to be set in the external <code>application.properties</code>. Two admin endpoints for verification: <code>GET /api/maintenance/heap-gc/status</code> (reports current gate evaluation without doing anything) and <code>POST /api/maintenance/heap-gc/test</code> (forces a Full GC bypassing all gates, returns before/after stats). Both gated by the existing maintenance allowlist. Local smoke test on a 4 GB heap at 52% used reclaimed 196 MB in 719 ms.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'Change History &middot; Prior Lifecycle column moved left + "Only with prior" toggle',
        items: [
            { badge: 'improve', text: '<strong>The Prior Phase column has been renamed to "Prior Lifecycle" and moved to sit immediately before Lifecycle</strong>, so each row reads naturally as a transition: <em>Prior Lifecycle &rarr; Lifecycle</em>. The Excel export and column filter follow the new order.' },
            { badge: 'new', text: '<strong>New "Only with prior" toggle</strong> in the Change History results bar. Click to hide rows whose Prior Lifecycle is blank — i.e. the first-ever rev of a SKU or revs that the LAG window can\'t place. Useful when you only care about real phase transitions and want to filter out noise. Click again to restore.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'Change History &middot; Prior Phase column + slimmer Part Type filter',
        items: [
            { badge: 'new', text: '<strong>New "Prior Phase" column</strong> shows the SKU\'s previous released lifecycle phase before the displayed rev. For 0TS2587\'s March 31 first-ACT entry the prior phase is PPROD — so at a glance you can see the transition the rev represents (PPROD &rarr; ACT), without opening Agile to walk the history yourself. Empty dash when no prior released rev exists.' },
            { badge: 'improve', text: '<strong>Part Type filter slimmed to the SKU chip plus a free-text input</strong> for everything else. The unused ECO/MCO/Drawing/Document/Assembly chips are gone — type what you want, comma-separated (e.g. <code>Drawing, M034, Lids</code>) and it substring-matches against <code>NEW_PART_CLASS</code> the same way the SKU chip does. Chip + typed values are unioned at search time.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> Prior Phase is computed in-SQL via <code>LAG(lp.DESCRIPTION) OVER (PARTITION BY i.ITEM_NUMBER ORDER BY r.RELEASE_DATE, r.REV_NUMBER)</code>. Lifecycle / Change Type row filters had to move to an outer subquery so the LAG sees the SKU\'s full release history — otherwise the prior for a first-ACT rev would skip the pre-ACT phases (PPROD, PROTO, DEV) and return nothing useful. <code>runBatch</code> dropped its <code>hasRowFilter</code> param since released-only is now always enforced at the inner scan. Controller HEADERS/KEYS gained <code>priorPhase</code> after <code>lifecyclePhase</code> so Excel exports get the column too. Frontend: new TH/TD in <code>index.html</code>, badge render in <code>history.js</code> with <code>opacity:0.7</code> to visually subordinate to the current phase. Part-type free-text input has its own <code>historyPartTypeText</code> state + <code>historyMergedPartTypes()</code> union helper called from <code>historyFilterQs</code>, <code>historyAppendFiltersToFormData</code>, and <code>doHistoryExport</code>. Perf with the LAG: ~5s on the user\'s exact filter set (was 3.7s); the inner scan can no longer be pruned by lifecycle, so the window function has to sort the SKU\'s full released history per partition.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'Change History &middot; "First entry" now returns the true first entry, not the first inside the date window',
        items: [
            { badge: 'fix', text: '<strong>When you search Change History with a Release Date range and "First entry only" (or "Last entry only"), the result now reflects the actual first/last time each SKU entered the requested phase — not just the first/last entry that happened to land inside the window.</strong> Example: SKU 0TS2587\'s first ACT entry was Mar 31, 2026 (via MCO-133719-A). A "last month + ACT + First entry" search used to return the May 12 SPECIAL-ORDERS rev because that was the only ACT row inside the window. Now it returns the correct March 31 row. The SKU still qualifies via its May release; the displayed row is its true first ACT.' },
            { badge: 'improve', text: '<strong>"Show all" entries no longer get trimmed by the Release Date range either.</strong> The date range is purely a cohort filter ("find me items released in this period"), so once a SKU qualifies you see its full change history. Use the inline column filters above the result grid if you want to trim displayed rows by date after the fact.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> rewired <code>ChangeHistoryService.getHistoryFiltered</code> into a two-step model — Step 1 builds the cohort (items list ∩ items-with-release-in-window, narrowed by Part Type via <code>NEW_PART_CLASS</code>); Step 2 fetches FULL history for that cohort with lifecycle / change-type row filters and (when mode = first/last) a <code>ROW_NUMBER() OVER (PARTITION BY item, phase ORDER BY release_date)</code> window. New <code>queryCohortItems</code>/<code>runCohortQuery</code> hit AGILE.REV with <code>SELECT DISTINCT ITEM_NUMBER … WHERE RELEASE_DATE BETWEEN … AND CHANGE != 0</code>, chunked over Oracle\'s 1000-item IN-list cap. <code>runBatch</code> no longer takes <code>dateFrom</code>/<code>dateTo</code> — date range is consumed entirely at cohort selection. The <code>NO_ITEMS_ROW_CAP</code> (25K) still applies, now across chunks via a shrinking row budget, but only when the cohort came from a pure date scan (items-explicit cohorts stay uncapped). Part Type narrowing prefers <code>ItemCacheService</code> (in-memory, sub-ms per item) over the DB-backed <code>custom_user.item_extract</code> lookup it used to do — the DB roundtrip was dominating query latency (~20s extra on a 1500-item cohort) and the cache is one delta-refresh behind at worst.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Need Help form now accepts multiple attachments',
        items: [
            { badge: 'new', text: '<strong>The Need Help response form now accepts multiple files in one submission</strong> — screenshot, draft, supporting doc, all in one go. Every attachment is sent with the DCC email and each is independently downloadable from the IMS Review dashboard (one 📎 link per file in the File column). Original filenames preserved on download.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> file input gets the <code>multiple</code> attribute; <code>onSubmit</code> in <code>ims-respond.html</code> loops <code>helpFile.files</code> and appends each as a separate <code>file</code> multipart part (Spring binds same-name parts into a <code>List&lt;MultipartFile&gt;</code>). Service: <code>respondViaToken</code> + internal <code>respond()</code> now take <code>List&lt;MultipartFile&gt;</code>; the legacy single-file <code>respond()</code> overload is kept so the session/admin-act-as path doesn\'t change. The first file populates the singular <code>uploadFile</code> / <code>uploadBytes</code> / <code>uploadName</code> (back-compat with PDF gen + dashboard); files 2..N become <code>List&lt;NamedBlob&gt;</code> extras that ride alongside the signed attestation PDF on the DCC email via <code>extraAttachments</code>. New <code>Event.uploadFiles</code> field stores ALL stored paths; <code>latestUploadMeta</code> + <code>getLatestUpload</code> now handle both shapes (old single-file events fall back to the singular field). New <code>file=&lt;storedName&gt;</code> query param on <code>/api/ims-review/upload</code> picks a specific file when there are multiple. Dashboard <code>renderUploadedAttachmentLink</code> emits one link per file.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; "invalid password" error now nudges Cc\'d colleagues who tried to sign',
        items: [
            { badge: 'fix', text: '<strong>When the password-attempt fails AND the AD Username field still has the original recipient\'s email</strong> (the common case where a Cc\'d colleague clicked the link, didn\'t change the username, and typed their own password), the error now appends a clear two-bullet hint: "if that\'s you, double-check the password; if you were just Cc\'d on the email, your own credentials won\'t work — please ask the intended recipient to sign off." Catches the case the earlier pre-check missed (where the username is left untouched).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>maybeWrongRecipientFollowup()</code> helper on <code>ImsReviewService</code>. Wraps every <code>v.message</code> on the LDAP-failure branch in both <code>respondViaToken()</code> and <code>respondViaTokenWithDcoForm()</code>. Only appends the hint when the supplied username normalizes to one of <code>ctx.allowedActors</code> — when the username has already been changed away from the recipient, the earlier <code>wrongRecipientHint()</code> pre-check (shipped this morning) catches that case with its own message. DM-DL fallback bypassed. Added <code>white-space:pre-wrap</code> to <code>.banner</code> in <code>ims-respond.html</code> so the bulleted hint renders on multiple lines.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; clearer error when a cc\'d colleague signs with their own credentials',
        items: [
            { badge: 'fix', text: '<strong>When someone who isn\'t the intended recipient tries to sign in, the response page now tells them so plainly</strong> — instead of returning the misleading "invalid password" that LDAP produces when the pre-filled username (the recipient\'s email) is paired with a different person\'s password. New message: <em>"This link was sent to &lt;Vikas&gt;. Only the intended recipient can sign it off — your own AD credentials won\'t grant access here. If you\'re &lt;Vikas&gt;, please replace the AD Username with your own login. Otherwise, please ask them to handle this response."</em> Triggered for both the standard response page and the DCO-form path.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>wrongRecipientHint()</code> helper on <code>ImsReviewService</code>. Runs BEFORE <code>ldapAuthService.verifyCredentials()</code> on both <code>respondViaToken()</code> and <code>respondViaTokenWithDcoForm()</code>. Compares the supplied username (normalized to both email and sam-account forms) against <code>ctx.allowedActors</code>; only short-circuits if there\'s clearly no match. DM-DL fallback case (when the DO was inactive and the link went to a distribution list) bypasses the pre-check so any sandisk.com user on the DL can still sign. Each wrong-recipient attempt still counts toward the password-failure lockout, so this isn\'t a probing vector.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; password lockout now auto-clears + admin unlock button',
        items: [
            { badge: 'fix', text: '<strong>The "This response link can\'t be used" lockout no longer sticks forever after a few wrong-password attempts.</strong> Previously, 5 failed attempts on a token killed it until JVM restart — even if the recipient had the correct password and just fat-fingered earlier. Now: (1) a <strong>successful verify clears the counter</strong>, (2) the lockout <strong>auto-releases after a 15-minute cooldown</strong> since the last failed attempt, and (3) admins get a <strong>&#x1F511; Unlock button</strong> on every in-flight row in the IMS Review dashboard to clear the lockout immediately when a recipient pings on Slack.' },
            { badge: 'improve', text: 'The locked-out error message is friendlier: "This link is temporarily locked after several failed password attempts. Try again in about N minutes, or contact Doc Control to unlock immediately." — instead of the prior "contact Doc Control" dead-end.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>tokenFailCounts</code> changed from <code>Map&lt;String,Integer&gt;</code> to <code>Map&lt;String,FailRecord&gt;</code> where <code>FailRecord = {count, lastFailMs}</code>. <code>describeToken()</code> checks the cooldown window (15 min default) before refusing; cleans up the entry when the cooldown elapses. New <code>clearTokenFail()</code> called from both <code>respondViaToken()</code> and <code>respondViaTokenWithDcoForm()</code> on the success branch right after <code>verifyCredentials()</code>. New <code>adminClearTokenLockouts(token, docNumber)</code> service method + <code>POST /api/ims-review/admin/unlock-token</code> endpoint (admin-only). Dashboard <code>segmentActionHtml()</code> renders the Unlock button for any admin row whose status is <code>SENT_TO_DO</code> / <code>SENT_TO_DM</code>; <code>imsUnlockToken()</code> JS handler hits the new endpoint.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review emails &middot; expanded "DO" / "DM" abbreviations to full role names',
        items: [
            { badge: 'improve', text: '<strong>Every IMS Review email now uses the full role names</strong> &mdash; <em>"Document Owner"</em> instead of "DO" and <em>"Document Owner Manager"</em> instead of "DM". Affects the DM-confirmation email body, the closure-to-DCC email (badge + headline + greeting), and the DCC "Needs Change" notes label. UI tab labels and column headers on the IMS Review dashboard are unchanged — the abbreviations only get expanded when copy lands in someone\'s inbox.' },
            { badge: 'improve', admin: true, text: '<strong>Files touched:</strong> <code>templates/email/ims-review-dm.html</code> (3 spots), <code>ims-review-dm-approved.html</code> (3 spots), <code>ims-review-dcc-needs-change.html</code> (1 spot), and the inline DCC alert HTML in <code>ImsReviewEmailService.java</code> (the DCO-creation-failed alert body + the "DO" table label). DO + DM in code comments left alone (internal-only).' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review dashboard &middot; doc # / DRR # / DCO # are now clickable Agile links',
        items: [
            { badge: 'new', text: '<strong>Document numbers, DRR numbers, and (when present) DCO numbers in the IMS Review dashboard now render as clickable links</strong> that open the Agile webclient in a new tab. Same URL patterns the IMS Review emails use &mdash; items go to <code>/object/Part/&lt;num&gt;/tab/13</code> (deep-linked to the Files tab), changes go to <code>/object/&lt;TYPE&gt;/&lt;num&gt;</code> (TYPE derived from the prefix: DRR, DCO, ECN, etc).' },
            { badge: 'new', text: '<strong>"DO: Needs Change" rows now display the created DCO number under the status pill</strong> so DCC sees which DCO the cascade produced without opening the row\'s history. Same Agile-link treatment.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ImsReviewService.dataForAdmin()</code> now stamps <code>agileWebclientUrl</code> on the response meta (sourced from <code>ImsReviewEmailService.agileWebclientUrl</code>, which already reads <code>app.ims-review.agile-webclient-url</code>) so the frontend has one source of truth per env. New <code>latestAgileDco()</code> helper walks queue history backward for the most recent event with a non-null <code>agileDco</code> and surfaces it on the row as <code>agileDco</code>. Frontend: new <code>agileItemLink()</code> / <code>agileChangeLink()</code> JS helpers mirror the Java versions in <code>ImsReviewEmailService.agileLinkItem()</code> / <code>agileLinkChange()</code>; new <code>renderDcoSubLink()</code> renders a "DCO: &lt;link&gt;" sub-row under the status pill on <code>DO_NEEDS_CHANGE</code> rows.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Need Help form now accepts an attachment + DCC can pull it from the dashboard',
        items: [
            { badge: 'new', text: '<strong>The "Need Help" response form now has an optional attachment upload</strong> &mdash; screenshot, draft note, anything that helps Doc Control understand what the DO is blocked on. The file is attached to the DCC email AND retained in the toolkit so the DCC team can pull it back later from the IMS Review dashboard without hunting through mailbox archives.' },
            { badge: 'new', text: '<strong>Dashboard now shows a 📎 link in the File column</strong> next to "Get File" whenever a DO uploaded something on the response page (Needs Change revised doc or Need Help attachment). Click downloads the original file with its original name. Same link shape used for both flows.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> the backend file-plumbing (<code>uploadBytes</code> → <code>DeferredDccEmail.uploadBytes</code> → <code>payloadForDccEmail</code> attachment) already supported Need Help &mdash; only the UI was missing the input. Added <code>&lt;input type="file" id="helpFile"&gt;</code> to <code>followupHelp</code> in <code>ims-respond.html</code> and a one-line append on <code>onSubmit</code>. New endpoint <code>GET /api/ims-review/upload?docNumber=…&amp;drrNumber=…</code> (admin/DCC-gated) walks the queue history for the latest event with <code>uploadFile</code>, strips the <code>docNumber-{ts}-</code> prefix to restore the original filename, and streams the bytes. New <code>ImsReviewService.UploadFile</code> result type + <code>getLatestUpload()</code> + <code>latestUploadMeta()</code> helpers. <code>dataForAdmin()</code> now stamps <code>uploadedAttachment: {filename, eventType, uploadedAt}</code> on each row for the dashboard render — no extra fetch per row.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Attestation PDFs now attach to the DRR (and DCO when applicable)',
        items: [
            { badge: 'new', text: '<strong>The signed attestation PDF generated when a DO/DM submits a response is now attached to the DRR in Agile</strong> &mdash; not just emailed. The audit trail in the Agile UI now mirrors what the email recipients received, so DCC can pull the proof from the DRR\'s Attachments tab without digging through mailbox archives.' },
            { badge: 'new', text: '<strong>For the Needs Change &rarr; DCO flow, the DO\'s attestation PDF also lands on the newly-created DCO.</strong> Same filename pattern (<code>IMS-Review-Attestation-DO-{docNumber}.pdf</code>). The DCO\'s Attachments tab now shows the revised document(s) <em>plus</em> the signed attestation, matching the screenshot Vikas flagged on DCO-525503.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>AgileWriteBackClient.attachFileToChange()</code> alias over the existing <code>/api/drr/{x}/attach-file</code> endpoint (no agile-service redeploy needed &mdash; the endpoint already resolves any <code>IChange</code> by number). New helpers on <code>ImsReviewService</code>: <code>readPdfBytes()</code> (reads PDF off disk by relPath; null-tolerant) + <code>attachAttestationPdfToChanges()</code> (best-effort attach to DRR + optional DCO, logs but never throws). Wired into three call sites: <code>runAgileWriteBack DO_RESPONSE_NO_CHANGE</code> (DRR only), <code>runAgileWriteBack DM_RESPONSE_APPROVED</code> (DRR only), <code>respondViaTokenWithDcoForm</code> after successful <code>createDcoRich</code> (DRR + DCO). Filename baked from role + docNumber so the Attachments list reads at a glance.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Coordinator dashboard overhaul (funnel, segmented filter, bulk actions)',
        items: [
            { badge: 'new', text: '<strong>The IMS Review admin grid now opens on a "Ready to send" view by default</strong> and the queue is structurally split so a Send button never appears on a row whose Document Owner has left the company. The flat KPI row has been replaced with a proportional <strong>pipeline funnel</strong> (Not sent / In flight / Closed) sized by volume — "almost nothing has moved" reads at a glance.' },
            { badge: 'new', text: '<strong>Segmented status filter</strong> with live counts replaces the prior Status dropdown: <em>Ready to send · Needs owner · In flight · Need help · Closed</em>. Each segment shows its own count derived from the current window, and only the rows that belong to it. The owner-missing alert (previously a KPI tile) is now a <strong>full-width red banner</strong> at the top with a single "Review &amp; reassign owners →" CTA that jumps you into the Needs-owner tab.' },
            { badge: 'new', text: '<strong>Triage order + grouping.</strong> Rows are now sorted by Next Review Date ascending (most overdue first), with three group dividers — <em>Overdue</em>, <em>Due soon (≤30 days)</em>, and <em>Upcoming</em> — so the worst stuff floats to the top. Overdue rows show a small "Xmo overdue" sub-line. Click any column header to sort by that column.' },
            { badge: 'new', text: '<strong>Bulk actions on Ready-to-send and Needs-owner tabs.</strong> Each row gets a checkbox + a sticky bulk bar above the table. On <em>Ready to send</em>, select-all-and-Send fires the per-row Send for every selected doc; on <em>Needs owner</em>, the Reassign button steps through the existing Edit-Owners modal for each selected doc in turn (Cancel mid-batch asks before aborting the rest). Cuts the "1,463 individual clicks" problem to one bulk action per tab.' },
            { badge: 'improve', text: '<strong>Row cleanup.</strong> Number + Description are now stacked in a single column. Dates render as <code>12 Jun 2024</code> (no more dangling <code>YYYY-MM-DD</code>). On Needs-owner the owner cell is muted with a single "⊘ owner left" badge; on Ready-to-send it\'s a plain blue name. Per-row Action follows the tab — Send, Assign owner, Resend/Cancel, or read-only — never a Send that would bounce.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> all changes in <code>src/main/resources/static/imsreview.js</code> + a one-line cache-bust in <code>index.html</code> — <strong>no backend changes</strong>. New state: <code>_state.segment</code>, <code>sortKey</code>, <code>sortDir</code>, <code>selectedDocs</code>, <code>bulkReassignQueue</code>. New helpers: <code>fmtDate()</code>, <code>overdueLabel()</code>, <code>dateBand()</code>, <code>rowMatchesSegment()</code>, <code>segmentCounts()</code>, <code>sortAdminRows()</code>, <code>sortableTh()</code>, <code>renderBulkBar()</code>, <code>segmentActionHtml()</code>. <code>renderKpiStrip()</code> now renders funnel + banner + segmented control (legacy <code>&lt;select id="imsReviewStatusFilter"&gt;</code> hidden by JS — left in DOM for back-compat with anything that probes it). <code>filterAdminRows()</code> drives off <code>_state.segment</code> via <code>rowMatchesSegment()</code> + composes with the existing owner-status indicator filter + per-column substring filters. Bulk Send loops the existing <code>/api/ims-review/send</code>; bulk Reassign reuses <code>imsOpenEditOwners()</code> with a queue cursor that advances on Save success.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Owner health indicators + edit-owners write-back',
        items: [
            { badge: 'new', text: '<strong>The Owner(s) column on the IMS Review admin grid now flags owners with a broken Active Directory account.</strong> Amber &#8856; <em>disabled</em> badge = AD record exists but the account is disabled; red &#8856; <em>not in AD</em> badge = no AD record at all (most likely they left the company). Healthy owners stay visually clean. Hover any badge for context. New KPI tiles ("Owner Disabled" / "Owner Missing") appear only when there\'s at least one problem row in the window.' },
            { badge: 'new', text: '<strong>Click any badge or KPI tile to filter the table to every row with that problem owner.</strong> The active filter is shown with a coloured border on the tile; click it again to clear. Stacks with the existing Status / per-column substring filters.' },
            { badge: 'new', text: '<strong>Edit Document Owners directly from the grid.</strong> A new &#9998; button next to each row\'s owners opens a modal where the admin can drop owners (chip &times;) and add new ones via typeahead. The typeahead only surfaces <strong>active Agile users</strong> (server-side filter on <code>agileuser.enabled = 1</code>) — matching the spec\'s "only users who are valid in Agile PLM should show up as replacement options" rule. Save writes back to Agile via plm-agile-service; the grid auto-refreshes on success.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>LdapManagerLookup.checkUserStatus()</code> returns a three-way enum (<code>ACTIVE</code> / <code>DISABLED</code> / <code>NOT_FOUND</code> / <code>UNKNOWN</code>) with 30-min caching keyed on lowercased email. <code>ImsReviewService.dataForAdmin()</code> enriches each row with an <code>owners[]</code> array (each entry: <code>loginId</code>, <code>displayName</code>, <code>email</code>, <code>ldapStatus</code>) plus two new KPIs (<code>kpiOwnerDisabled</code> / <code>kpiOwnerMissing</code>). New toolkit endpoints: <code>GET /api/ims-review/admin/users/search</code> (admin-gated proxy to plm-agile-service users.search) + <code>POST /api/ims-review/admin/owner</code> (admin-only). plm-agile-service: new <code>ItemOwnerService</code> + <code>POST /api/agile/item/{item}/owners</code> endpoint using the proven <code>cell.getAvailableValues() → setSelection() → setValue()</code> IAgileList pattern from <code>DcoRichCreationService.setUserListCell()</code>. New cell config <code>agile.cell.itemDocumentOwners</code> defaults to <code>-1</code>; pin the real cell ID before the first save attempt.' }
        ]
    },
    {
        date: 'May 29, 2026',
        title: 'IMS Review &middot; Managers DL + DCC on every notification (per CRD ECN-129414)',
        items: [
            { badge: 'improve', text: '<strong>Every DO and DM notification (initial + follow-ups) now Cc\'s the IMS-Doc-Managers-Agile DL and the DCC team</strong>, matching the spec in CRD ECN-129414. Previously the managers DL was hard-coded to <code>vikas.singh3@sandisk.com</code> as a placeholder; both DLs now live in <code>application.properties</code> and default to <code>pdl-plm-admin@sandisk.com</code> so DCC sees the full timeline without anyone forwarding manually.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> two new config keys &mdash; <code>app.ims-review.managers-dl</code> and <code>app.ims-review.dcc-dl</code>. <code>ImsReviewEmailService</code> swaps the prior <code>public static final String DM_DL_CC</code> / <code>ADMIN_CC</code> constants for <code>@Value</code>-injected instance fields (<code>managersDl</code> / <code>dccDl</code>), and a new <code>addCcDedup()</code> helper prevents a duplicate Cc when both DLs resolve to the same address (the default until real DLs ship). <code>ImsReviewService</code> updated everywhere the constants were referenced (DM resolution fallback, stakeholder-notify Cc, DCC failure-alert recipient, DRR history lines).' }
        ]
    },
    {
        date: 'May 28, 2026',
        title: 'IMS Review &middot; Rich DCO form for Needs Change',
        items: [
            { badge: 'new', text: '<strong>DO can now submit a fully-populated DCO directly from the IMS Review response page</strong> when picking "Needs Change &mdash; Upload". A side drawer mirrors the Agile DCO cover page (Priority, Product Lines, Subcontractors, Document Owners, Approvers, Observers, Notify Stakeholders, attachments with per-file Type, etc.). On submit the toolkit creates + auto-submits the DCO in Agile via plm-agile-service, sets the relationship rule to auto-close the DRR when the DCO implements, and sends a styled HTML notification to every stakeholder with the signed attestation PDF attached. Replaces the manual "DCC creates DCO" hand-off for this flow.' },
            { badge: 'new', text: '<strong>Pre-validation pass</strong> resolves every picked Agile user and cross-checks every list value against the cached IAdminList catalog <em>before</em> the token is burned. Form errors surface inline at the relevant field; the DO fixes and retries without losing the link.' },
            { badge: 'improve', text: '<strong>Typeahead</strong> for Document Owner / Approvers / Observers fields fires against active Agile users only (server-side filter on agileuser.inactive_flag = 0). Sub-100ms responses on indexed columns; debounced 200ms client-side.' },
            { badge: 'improve', text: 'New kill-switch <code>app.ims-review.dco-form-enabled</code> (default <strong>off</strong>) toggles the drawer independently of the Phase-4 legacy cascade. When off, UPLOAD falls back to the existing inline file + notes UI.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>AgileFormController</code> + <code>AdminListCacheService</code> + <code>UserSearchService</code> + <code>DcoFormValidator</code> + <code>DcoRichCreationService</code> in plm-agile-service. New endpoints: <code>GET /api/agile/dco/list-values</code> (cached 1h), <code>GET /api/agile/users/search</code>, <code>POST /api/agile/dco/validate-form</code>, <code>POST /api/drr/{drr}/create-dco-rich</code> (multipart). 11-step DCO orchestration with rollback (soft-delete via <code>dco.delete()</code>) on mid-cascade failure; every step emits a <code>[AGILE-WRITE]</code> log line tagged with the corrId from <code>X-Toolkit-Action-Id</code>. Toolkit-side: new <code>imsreview-dco-form.js</code>, drawer markup in <code>ims-respond.html</code>, <code>respondViaTokenWithDcoForm()</code> + <code>validateDcoForm()</code> on <code>ImsReviewService</code>, new template <code>ims-review-dco-stakeholder-notify.html</code>, 3 new fields on <code>Event</code> (<code>dcoForm</code>, <code>dcoAttachmentsManifest</code>, <code>dcoFormChecksum</code>). Cell IDs for Priority / Product Lines / Subcontractors / Training Requirement / Business Unit / Change Impact Disposition / Change Impact Details / Document Owners / Attachment Type default to <code>-1</code> and are skipped with <code>[AGILE-WRITE-NOTE]</code> lines &mdash; dry-run #3 surfaces what to pin them to in <code>application.properties</code>.' }
        ]
    },
    {
        date: 'May 28, 2026',
        title: 'ECN Report: quarterly trend on Volume + cycle-time charts switched to % on Target + Team chart redesign',
        items: [
            { badge: 'new', text: '<strong>ECN Volume YTD now has a "View quarterly trend ▾" toggle</strong> showing volume by priority (Standard + Urgent) across the last three completed quarters. Window slides forward automatically when a new quarter begins, and ignores the YTD date range — so the chart stays meaningful in early January when YTD has barely any data. Closes one slice of <strong>PT-77</strong>.' },
            { badge: 'improve', text: '<strong>Cycle Time charts (Standard / PDR / Dedicated) now track % on Target instead of Avg Cycle Days.</strong> Both the monthly bar chart and the quarterly trend show Std (blue) and Urg (red) % on Target with a 0–100% Y-axis. Avg Days remains visible in the table next to the chart — only the visualization metric changed. Jimmy\'s ask was to make the on-target performance the headline KPI.' },
            { badge: 'improve', text: '<strong>Quarterly trend window changed to "last 3 completed quarters"</strong> across every chart that has a quarterly toggle. Previously the toggle showed prev/last/current — the in-progress quarter pulled the bars down because it was always a half-sample. Now today (May 28, 2026 → Q2) shows Q3 2025 / Q4 2025 / Q1 2026.' },
            { badge: 'new', text: '<strong>Cycle Time (Product Team) chart redesigned: monthly volume by team.</strong> One bar per team per month showing count of completed ECNs. Top 6 teams by total volume are shown individually, the rest rolled up into an "Others" bar so the legend stays readable. Same pattern in the quarterly trend underneath. The detailed Std-vs-Urg table with Avg Days + % on Target stays as-is above the chart.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ecnGetLast3CompletedQuarters()</code> distinct from PT-74\'s <code>ecnGetLast3Quarters()</code>. <code>ecnComputeTrends()</code> now emits <code>stdPctOnTarget</code>/<code>urgPctOnTarget</code> per month; <code>ecnComputeQuarterlyTrends()</code> rewired to the completed-quarters window and adds the same per-priority pct slots. New <code>ecnComputeTeamMonthly()</code> / <code>ecnComputeTeamQuarterly()</code> with <code>ecnPickTopTeams()</code> driving the top-6 cut and "Others" rollup. <code>ecnDrawQuarterlyChart()</code> dispatches by <code>kind</code> (<code>cycleTimePct</code> / <code>volume</code> / <code>teamVolume</code>); <code>ecnAppendQuarterlyToggle()</code> takes the same kind so callers wire up the right series. <code>ecnDrawTeamVolumeChart()</code> replaces the cycle-time chart in the Team panel.' }
        ]
    },
    {
        date: 'May 27, 2026',
        title: 'Change History: dates render in Pacific Time across the screen + Excel export; cleaner export style',
        items: [
            { badge: 'fix', text: '<strong>Change History dates now render in Pacific Time</strong> on the on-screen table, the "Email Me" body, and the Excel export. Prod runs on a UTC-default JVM, so every transaction\'s time component was reading 7–8 hours off (a transaction at 12:56 AM Pacific was shown as ~7:56 AM UTC). Field users compare these against Agile\'s own UI which is Pacific, so the toolkit now matches.' },
            { badge: 'improve', text: '<strong>Change History Excel export now uses the cleaner Agile-Lookup style.</strong> Dropped the alternating grey zebra rows and switched the header band from the bright blue to the SanDisk slate (#2c3e50, matching email and UI). Same data, same column order, but easier on the eyes — Vikas Singh\'s feedback after demoing the report to Sophia Duran.' },
            { badge: 'fix', text: '<strong>JDBC now reads the AGILE.REV date columns as UTC</strong> (which is what Agile stores them as). Without this anchor, the JDBC driver was interpreting the bare DATE wall-clock in the JVM\'s default TZ (Pacific on the Windows server), parking each instant 7–8 h off — so even with the formatter pointed at Pacific, the Change History output matched UTC instead of what Agile UI shows. With the explicit UTC Calendar on every <code>rs.getTimestamp</code>, dates now line up exactly with Agile\'s PDT/PST rendering.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ChangeHistoryService.formatTs()</code> now sets <code>TimeZone(America/Los_Angeles)</code> on the SimpleDateFormat. <code>rs.getTimestamp(col, utcCalendar)</code> on rel_date / inc_date / eff_date / obs_date so the JDBC parse anchors to UTC, not the JVM default. Shared Pacific TZ constant is also used in <code>ChangeHistoryEnrichService.pickEarliestPerItem()</code> for sort symmetry; <code>setDate()</code> deliberately keeps the JVM-default parser so that POI\'s JVM-default-TZ Excel-serial conversion cancels out and the Excel cell ends up showing the same Pacific wall-clock the UI does. The export\'s header is now <code>XSSFColor(#2c3e50)</code> and the zebra <code>evenStyle</code> is gone.' }
        ]
    },
    {
        date: 'May 27, 2026',
        title: 'Single/Sole Source Report: counts now match Agile Advanced Search; Excel has 3 tabs',
        items: [
            { badge: 'fix', text: '<strong>Designation Needed now applies the Manufacturer/MPN Count = 1 filter</strong> that Agile Advanced Search uses. Shruthi confirmed (May 27) the Agile UI shows 299 items with this filter on; the toolkit previously reported ~319 because it was missing the MPN=1 condition. Counts now align with Agile baseline. Closes the follow-up half of <strong>PT-65</strong>.' },
            { badge: 'fix', text: '<strong>Single Source and Sole Source counts now reflect distinct items, not per-MPN rows.</strong> A previous fix on May 21 left Single/Sole at the per-MPN row granularity (~1200 vs Agile\'s 762; ~50 vs Agile\'s 31), explained as a granularity difference. Shruthi confirmed the toolkit should match Agile\'s item-distinct baseline. All three categories now dedup by item_id; the headline counts (and Excel row counts) match Agile UI exactly (299 / 762 / 31 on the May-27 snapshot).' },
            { badge: 'improve', text: '<strong>Excel now has 3 separate tabs</strong> &mdash; Designation Needed / Single Source / Sole Source &mdash; instead of the prior 2-tab layout (Needed + combined Provided). Each tab shows one row per item. The template already shipped with all three sheets natively, so the Sole Source tab is back from the dead. Spec change requested by Shruthi.' },
            { badge: 'improve', text: '<strong>Monthly email summary updates accordingly:</strong> the KPI strip now shows 3 tiles (Needed / Single Source / Sole Source) matching the Excel layout, instead of the combined "Designation Provided" tile.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>SingleSoleSourceService.fetch()</code> now dedups all three categories on a single <code>seenItem</code> set, and skips Designation Needed rows where <code>mpn_count != 1</code> (reads <code>TEXT67</code> &mdash; trial-matched Agile UI within noise on May 19, so TEXT67 is what Advanced Search reads too). <code>SingleSoleSourceExcelService.write()</code> writes three sheets using the template\'s native tab layout; the legacy "remove Sole Source tab" behavior is gone. <code>SingleSoleSourceEmailService</code> emits 3 KPI tiles. The <code>counts</code> shape returned by <code>write()</code> still has 4 slots <code>[needed, provided, single, sole]</code> for backward compat &mdash; <code>provided</code> is now just <code>single + sole</code>.' }
        ]
    },
    {
        date: 'May 27, 2026',
        title: 'ECN Report: trend charts now split Standard vs Urgent into side-by-side bars',
        items: [
            { badge: 'improve', text: '<strong>ECN Volume YTD trend chart: Standard and Urgent are now grouped side-by-side instead of stacked.</strong> Jimmy\'s ask was that the chart show <em>each priority\'s volume independently</em> rather than rolling them up into a single stacked total. Each month now renders two bars (Standard in blue, Urgent in red) standing next to each other on a non-stacked axis. Closes <strong>PT-76</strong> (first half).' },
            { badge: 'improve', text: '<strong>Cycle-time View-Trend charts also split by priority.</strong> The monthly bar chart under every Cycle Time panel (Standard ECN, PDR ECN, Dedicated Process, Product Team) used to show one bar per month for the combined Avg Cycle Days; it now shows two bars per month — Standard avg days + Urgent avg days — so each priority\'s target performance reads on its own. Same treatment applied to the quarterly trend toggle (View quarterly trend ▾). Closes <strong>PT-76</strong> (second half).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> <code>ecnComputeTrends()</code> now emits <code>stdAvgDays</code> / <code>urgAvgDays</code> per month and <code>ecnComputeQuarterlyTrends()</code> emits <code>stdAvgDays</code> / <code>urgAvgDays</code> / <code>stdTotal</code> / <code>urgTotal</code> per quarter. <code>ecnDrawVolumeChart()</code> dropped <code>x.stacked / y.stacked = true</code> so the same Standard + Urgent datasets render grouped. <code>ecnDrawCycleTimeChart()</code> and <code>ecnDrawQuarterlyChart()</code> now configure two datasets (Standard #4a6fa5, Urgent #B8342B) with the legend visible at the bottom. The chart-significance gate still uses the combined <code>avgDays</code> series so a quiet month still hides the chart cleanly.' }
        ]
    },
    {
        date: 'May 26, 2026',
        title: 'ECN Report: Cycle-time View Trends now use the same bar style as ECN Volume YTD, plus a new quarterly trend',
        items: [
            { badge: 'improve', text: '<strong>The "View trend" chart under every Cycle Time panel is now a bar chart in the same style as ECN Volume YTD</strong> &mdash; one blue bar per month showing Avg Cycle Days. Replaces the old dual-axis line chart (Avg Days + % on Target) that didn\'t match the rest of the dashboard. Affects Cycle Time (Standard ECN) YTD, Cycle Time (PDR ECN) YTD, Cycle Time (Dedicated Process) YTD, and Cycle Time (Product Team) YTD. Closes <strong>PT-74</strong> (first half).' },
            { badge: 'new', text: '<strong>New "View quarterly trend ▾" toggle under each cycle-time chart.</strong> Click to reveal a 3-bar chart comparing avg cycle days across the previous, last, and current calendar quarter. Hidden by default per Jimmy\'s spec ("can be hidden then expanded if needed"). Each panel tracks its own open/closed state. Closes <strong>PT-74</strong> (second half).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>ecnComputeQuarterlyTrends()</code> (anchored on <code>ecnState.dateRange.end</code> not wall-clock, so the dashboard\'s "as of" date drives the quarter window), <code>ecnDrawQuarterlyChart()</code>, and <code>ecnAppendQuarterlyToggle()</code> in <code>static/ecnreport.js</code>. <code>ecnDrawCycleTimeChart()</code> rewritten from <code>type:\'line\'</code> dual-axis to <code>type:\'bar\'</code> single-axis matching <code>ecnDrawVolumeChart()</code>. Per-canvas open state on <code>ecnState.expandedQuarterly[canvasId]</code> so re-renders restore the expanded view.' }
        ]
    },
    {
        date: 'May 26, 2026',
        title: 'ECN Report Cycle Time: D@&lt;Status&gt; now counts the most recent visit only',
        items: [
            { badge: 'fix', text: '<strong>D@Pend / D@Subm / D@Rev / D@Rel now reflect the <em>most recent</em> visit to each status</strong>, not the sum across all visits. When a change loops back through Review (Submitted → Review → Pending → Submitted → Review → Release) the prior review cycle represents work that was rolled back — those days no longer get bundled into D@Rev. Vikas Singh\'s example (ECN-P000015124) used to show D@Rev = 10 with both cycles summed; it now shows the most recent cycle alone. Closes <strong>PT-73</strong>.' },
            { badge: 'improve', text: '<strong>D@Hold stays cumulative</strong> — repeated holds are real elapsed time and continue to sum across all visits.' },
            { badge: 'improve', text: '<strong>UI now spells out the counting rule.</strong> A short caption above the ECN Data table reads: <em>"D@&lt;Status&gt; columns count business days only (Mon–Fri). D@Pend/D@Subm/D@Rev/D@Rel reflect the most recent visit to each status; D@Hold is cumulative across all holds."</em> The same explanation is on each column header as a hover tooltip.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> rewrote <code>compute_status_days()</code> in <code>data/ecn-report/ecn_report_generator.py</code>. Replaces the old <code>days_at[prev_status] += business_days(prev_ts, t["ts"])</code> accumulator with a <code>last_span[prev_status] = (prev_ts, t["ts"])</code> overwrite per workflow phase, keeping the original accumulator only for Hold. Business-day arithmetic (<code>business_days()</code>) is unchanged — weekends excluded, holidays still ignored. Re-run the ECN Report after JAR deploy to regenerate <code>ecn_data.json</code> with the new values.' }
        ]
    },
    {
        date: 'May 26, 2026',
        title: 'Revision History: Clear / Saved Search / Export & Email fixes + paste screenshots into Feedback',
        items: [
            { badge: 'fix', text: '<strong>Revision History — Export Excel and Email Me buttons now work after a date-range-only search.</strong> The buttons short-circuited silently when the Item Number box was empty, even though the result table was populated (date-range searches make items optional per <strong>PT-66</strong>). Both buttons now gate on having results, not on the item input. Closes <strong>PT-69</strong>.' },
            { badge: 'new', text: '<strong>Paste screenshots straight into the Feedback dialog.</strong> Copy a screen capture (⌃⇧⌘4 → clipboard, Snipping Tool, browser screenshot tool, …) and paste while the Feedback modal is open — the image is attached with a timestamped name (e.g. <code>pasted-2026-05-26T18-50-00.png</code>) and appears as a chip alongside any files you also picked manually. No more save-to-Desktop round-trip. Closes <strong>PT-70</strong>.' },
            { badge: 'fix', text: '<strong>Revision History Clear button now resets every pre-filter.</strong> Release Date range, Relative date dropdown, Part Type chips, and First/Last Entry radio used to survive a Clear — only Lifecycle and Change Type were being wiped. Clear now resets all of them and restores the default "Show all" entry-mode. Closes <strong>PT-71</strong>.' },
            { badge: 'fix', text: '<strong>Saved searches on Revision History now round-trip all the pre-filters.</strong> Saving a search persisted only the Item Number text; on reload everything else (Release Date range, Part Type chips, First/Last Entry, Lifecycle phases, Change Types) reset to defaults so the loaded search no longer matched what you saved. The full pre-filter set is now part of the saved-search payload and is restored back into both the JS state and the UI controls before the search fires. Closes <strong>PT-72</strong>.' }
        ]
    },
    {
        date: 'May 21, 2026',
        title: 'SSS report row counts now match Agile Advanced Search; Audit Trail explains "0 rows" intersection',
        items: [
            { badge: 'fix', text: '<strong>Single/Sole Source report: added <em>Material Group NOT IN (NONPROD)</em> filter to align with Agile Advanced Search.</strong> Field users compare the toolkit\'s SSS counts against Agile\'s "Items / Parts / Object Search" baseline; Agile\'s standard criteria exclude NONPROD material-group items. The toolkit now mirrors that filter across all three SSS values. <em>Note:</em> the larger Single Source / Sole Source gap (1214 vs 868; 39 vs 31) is a granularity difference — the toolkit\'s Excel report intentionally shows one row per active MPN, while Agile\'s Object Search returns one row per item. The summary counts now reflect the NONPROD-corrected MPN-row totals; per-item totals remain a separate dimension. Partial fix for <strong>PT-65</strong>.' },
            { badge: 'fix', text: '<strong>The Audit Trail "no matching rows" empty state now tells you why.</strong> When you supply both an Item Number and a Change Number, the toolkit combines them as <em>AND</em>: the change must touch the item (via AGILE.REV). If the change has plenty of history but doesn\'t reference your item — like <code>MCO-128249-A</code> entered alongside <code>54-62-EA191-768G</code> — you used to see a silent "No rows match these filters." Now the empty state explicitly says the change\'s history exists, the two don\'t relate, and offers the fix: clear the Item Number field to see the change\'s full audit trail. Closes <strong>PT-66</strong>.' }
        ]
    },
    {
        date: 'May 20, 2026',
        title: 'IMS Review: hide DCO rows from the admin table; drop unused Type row from DO email',
        items: [
            { badge: 'improve', text: '<strong>Admin table now lists only DRR rows.</strong> Docs that had unrelated DCOs against them (DCO-525233, DCO-525234, …) were producing extra "Not Sent" rows alongside the actual DRR — confusing and dangerous because clicking <em>Send to DO</em> on a DCO row would have written back to the wrong change object. The underlying SQL now filters changes to <code>change_number LIKE \'DRR-%\'</code>, so each doc in the review window shows up exactly once: with its DRR if one exists, or with an empty DRR cell if not.' },
            { badge: 'fix', text: '<strong>"Type: Document" row removed from the DO review email.</strong> The doc subclass was redundant with the document number itself and added a row of visual noise; the email now goes Document → Description → Rev / Lifecycle → Next Review Date without it.' }
        ]
    },
    {
        date: 'May 20, 2026',
        title: 'IMS Review: Agile write-back (ships behind a feature flag — dry-run mode)',
        items: [
            { badge: 'new', text: '<strong>The DRR side of IMS Review is now driven from the toolkit.</strong> When the DM confirms No Change, the toolkit writes a History entry on the DRR via the Agile SDK and pushes the DRR to <em>Review</em> status — DCC takes it the rest of the way (manually drives it to Implemented, attaches signed PDFs to Agile). When the DO submits <em>Needs Change</em> with a revised file, the toolkit attaches the file to the DRR, writes the DO\'s attestation to History, and creates a new DCO with the IMS Doc as Affected Item + a bumped rev (integer → next integer, single alpha → next letter) + an auto-close relationship rule so when DCC implements the DCO the linked DRR closes itself. Stakeholder emails + DLs from the DO panel land on the DCO\'s Notify List.' },
            { badge: 'improve', text: '<strong>Ships behind a kill-switch.</strong> <code>app.ims-review.writeback-enabled=false</code> by default; the existing email + PDF flow runs unchanged. We flip the flag env-by-env once each dry run passes — see the design doc for the 4-dry-run checklist (find-DRR → attach + history → DRR status push → DCO create end-to-end → auto-close rule end-to-end).' },
            { badge: 'improve', admin: true, text: '<strong>Robust observability built in.</strong> Every SDK call site on plm-agile-service emits a structured <code>[AGILE-WRITE]</code> line tagged with a correlation UUID (sent in <code>X-Toolkit-Action-Id</code>), plus a per-request <code>[AGILE-WRITE-SUMMARY]</code> with totalMs / stepsOk / stepsFailed / failedAt. The toolkit-side activity log gets <code>IMS_REVIEW_AGILE_WRITEBACK</code> entries carrying the same corrId so <code>grep &lt;uuid&gt; activity-log.jsonl queue.jsonl plm-agile-service.log</code> shows the whole chain end-to-end. queue.jsonl gets a new <code>AGILE_WRITEBACK</code> audit event with agileDrr / agileDco / agileSteps / agileErrorAt fields.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> 5 new endpoints on plm-agile-service — <code>GET /api/document/{doc}/pending-drr</code>, <code>POST /api/drr/{drr}/attach-file</code> (multipart), <code>POST /api/drr/{drr}/history</code> (uses <code>IChange.send(IUser[], comment)</code> since <code>TABLE_HISTORY</code> is read-only via SDK), <code>POST /api/drr/{drr}/status</code>, <code>POST /api/drr/{drr}/create-dco</code>. New <code>AgileWriteBackClient</code> on the toolkit with idempotency-on-retry semantics (idempotent calls retry 3× with 2/4/8s backoff; the others rely on service-side de-dup via the DCO# back-stamp on DRR cell 1575). commoncodebase classes snapshotted into <code>plm-agile-service/src/main/java/com/sandisk/plm/agile/support/</code> with provenance headers. Full design doc at <code>docs/superpowers/specs/2026-05-20-ims-review-agile-writeback.md</code>.' }
        ]
    },
    {
        date: 'May 19, 2026',
        title: 'IMS Review: bug fixes after first prod test',
        items: [
            { badge: 'improve', text: '<strong>Needs Change and Need Help now generate signed attestation PDFs too.</strong> Previously only No Change Needed (DO) and DM Approve produced PDFs. Now every DO/DM action except <em>Send Back</em> attaches a signed PDF to the DCC email — so Doc Control has tamper-evident proof of who submitted what file / what help request, when, signed by AD identity. <em>Send Back</em> still skips PDF (no approval happened; the DO will redo the work and a fresh attestation lands).' },
            { badge: 'fix', text: '<strong>Email action buttons were missing the token in the URL</strong> — clicking <em>Needs Change · Upload</em> landed users on <code>ims-respond.html</code> with no <code>?token=...&amp;action=...</code> and the page errored "No response token in the URL." Cause: per-action URLs were composed inside the payload builder using <code>p.token</code>, which the caller sets only AFTER the builder returns. Fix: stamp the URLs in <code>send()</code>, where the token is guaranteed to exist.' },
            { badge: 'fix', text: '<strong>Admin column-filter trap.</strong> If column filters wiped every row from the IMS Review admin table, the "No matching documents" empty state hid the filter row — leaving you with no way to clear the filter that was nuking your results. Now the filter row stays visible, and a <code>✕ Clear column filters (N)</code> button appears so you can recover without a page reload.' }
        ]
    },
    {
        date: 'May 19, 2026',
        title: 'IMS Review: password-gated approval + signed compliance PDFs',
        items: [
            { badge: 'new', text: '<strong>Email is now the only approval surface.</strong> DO emails carry three action buttons (<em>No Change Needed</em>, <em>Needs Change · Upload</em>, <em>Need Help</em>); DM emails carry two (<em>Confirm No Change</em>, <em>Send Back to DO</em>). Each button is a single-use HTTPS link valid for 30 days. Click → opens <code>ims-respond.html</code> → enter your AD username + password → action is recorded with your verified identity, IP, and a UTC timestamp.' },
            { badge: 'new', text: '<strong>Every approval generates a signed compliance PDF.</strong> Doc# / Rev / DRR, action taken, signer name + email + AD login, UTC timestamp + originating IP, an embedded record fingerprint, and a "Modifications void authenticity" notice. PDFs are stored under <code>data/ims-review/pdfs/</code> and the SHA-256 of the bytes is recorded on a <code>PDF_GENERATED</code> event in <code>queue.jsonl</code>, so an auditor can pull the PDF from the DRR, recompute the hash, and verify it matches the toolkit\'s system of record via <code>GET /api/ims-review/verify-pdf?eventUUID=…</code>.' },
            { badge: 'new', text: '<strong>DCC closure email attaches BOTH signed PDFs.</strong> When the DM approves, Doc Control receives a single closure email with the DO PDF + the DM PDF attached, ready to drop into the DRR as proof of the review cycle.' },
            { badge: 'improve', text: '<strong>First-valid-click wins for multi-owner docs.</strong> If three co-owners get the email, the first one to click + sign locks the response; the other two see "Already submitted by Krati Jain on YYYY-MM-DD HH:MM UTC" and can pull the PDF for reference. Failed-password attempts are counted per-token; 5 fails locks the link.' },
            { badge: 'improve', text: '<strong>In-tab "respond" buttons removed.</strong> The DO/DM card view inside the IMS Review tab is now a read-only "here\'s what\'s waiting for you" dashboard with a pointer to the email. This makes the audit trail unambiguous — every approval is a signed PDF, full stop.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation:</strong> new <code>POST /api/auth/verify</code> (LDAP bind, no session, no group check); new <code>ImsReviewPdfService</code> (Apache PDFBox 2.0.31, PDF/A-compatible layout); new <code>POST /api/ims-review/token/submit</code> + <code>GET /token/info</code> (public, token-authed); new <code>GET /api/ims-review/verify-pdf</code> (auditor self-service) + <code>GET /api/ims-review/pdf?eventUUID=…</code> (admin download). SEND_TO_DO / SEND_TO_DM events now carry a UUID token; response events carry <code>redeemsToken</code> + <code>verifiedSamAccount</code> / <code>verifiedDisplayName</code> / <code>verifiedEmail</code> / <code>verifyIp</code>. Activity log gets <code>IMS_REVIEW_VERIFY_FAIL</code>, <code>IMS_REVIEW_TOKEN_REDEEM</code>, <code>IMS_REVIEW_PDF_GENERATED</code>, <code>IMS_REVIEW_PDF_FAILED</code>, <code>IMS_REVIEW_CLOSURE_SENT</code>.' }
        ]
    },
    {
        date: 'May 19, 2026',
        title: 'New sub-tab: Items / Search — build queries by stacking AND/OR conditions',
        items: [
            { badge: 'new', text: '<strong>Items / Search</strong> is a new sub-tab under Items (alongside Part Extract, Agile Lookup, SKU Lookup). Pick any of 30 <code>item_extract</code> columns, choose an operator (equals, contains, starts with, in list, on/before/after, between, …), enter a value, and stack rows with per-row AND/OR connectors. The 15 most-used columns sit at the top of the dropdown; the other 15 are under <em>More fields…</em>.' },
            { badge: 'new', text: '<strong>Type-aware operators.</strong> String columns get text-style ops; categorical columns (Lifecycle Phase, Material Group, Build Plant, …) get an <em>in list</em> operator that opens a checkbox dropdown of distinct values pulled live from <code>item_extract</code>; date columns (Rev Release Date, Create Date) get <em>on/before</em>, <em>on/after</em>, <em>between</em> with date pickers — the <code>TO_DATE(SUBSTR(...))</code> wrapping is baked into the server so the VARCHAR format gotcha never reaches the user; multi-value columns (Subcontractors) get a <em>contains</em> that matches each comma-separated token cleanly.' },
            { badge: 'new', text: '<strong>Results table</strong> shows the default 5 columns (PART_NUMBER, DESCRIPTION, REV, STATUSCODE, LIFECYCLE_PHASE) plus any column you filtered on (auto-pinned). Sortable headers, per-column filter row, gear icon for a column picker. Cap is 1,000 rows on screen with a "showing first N — refine or export" banner; <strong>⬇ Export</strong> writes the full match set (up to 100K rows) to <code>Items-Search-YYYY-MM-DD.xlsx</code> via streaming SXSSF.' },
            { badge: 'improve', admin: true, text: '<strong>Security model:</strong> column names and operators are server-side allow-listed (the SQL builder rejects anything not in <code>ItemsSearchService.COLUMNS</code>), and every user-supplied value flows through <code>PreparedStatement</code> bind variables. Endpoints: <code>GET /api/items-search/columns</code> (metadata), <code>GET /api/items-search/distinct?column=X</code> (categorical values), <code>POST /api/items-search/run</code> (capped fetch + total count), <code>POST /api/items-search/export</code> (full Excel stream). Activity log: <code>ITEMS_SEARCH_RUN</code>, <code>ITEMS_SEARCH_EXPORT</code> with one-line condition summary. Closes <strong>PT-67</strong>.' }
        ]
    },
    {
        date: 'May 19, 2026',
        title: 'IMS Review: fixes + admin Reset button',
        items: [
            { badge: 'new', text: '<strong>Card view now shows every doc pending under your ownership</strong> — not just the one the email link landed you on. When a DO clicks "Submit your response," the page lists (a) the doc waiting on them with the usual 3-button response, plus (b) every other doc in the next 30 days where they\'re listed as an owner that hasn\'t been closed: "Sent to co-owner <name>," "Waiting on manager <name>," or "Not started — DCC will kick off when ready." No action buttons on the (b) cards, just status pills, so they\'re clearly informational. Lets the DO knock out multiple reviews in one sitting instead of one-email-one-doc.' },
            { badge: 'fix', text: '<strong>DO card view no longer triggers the heavy Agile query.</strong> <code>lookupDoc()</code> used to scan 365 days of docs (the same expensive SQL as admin /data) just to hydrate one row\'s description / rev / lifecycle. Now it runs a targeted single-doc lookup (<code>WHERE i.item_number = ?</code>) — sub-second regardless of cache state. As a tell, the loading spinner also stopped saying "Running Agile query…" on the DO/DM path; now it shows "Loading your queue…" for the card view and only says "Running Agile query…" when you actually force-refresh the admin table.' },
            { badge: 'fix', text: '<strong>Email deep links survive the login redirect now.</strong> Anyone clicking <em>Submit your response</em> from an IMS Review email while not already authenticated was bouncing to <code>/login.html</code> and then landing on the bare home page after signing in — the <code>?tab=ims-review&amp;asDO=true</code> deep-link was lost. <code>AuthFilter</code> now carries the original URL forward as <code>?returnTo=…</code> and <code>login.html</code> bounces back to it post-auth (open-redirect-guarded to same-origin paths). Applies to every deep link in the toolkit, not just IMS Review.' },
            { badge: 'new', text: '<strong>Admin-only <code>⟲ Reset</code> button</strong> in the Action column for any non-NOT_SENT row. Wipes the queue state for that doc so it goes back to the start and DCC can Send again from scratch. Past emails aren\'t unsent, but the row reverts cleanly and the full prior history stays in <code>queue.jsonl</code> + the activity log (<code>IMS_REVIEW_RESET</code>) as an audit trail. Useful when a cascade ran with the wrong recipient, a test needs to be re-run end-to-end, or the DCC analyst wants to start over after a <em>Needs Change</em> / <em>Need Help</em> closeout.' },
            { badge: 'fix', text: '<strong>"Submit your response" link in DO/DM emails was 404\'ing</strong> for recipients because the JAR default baked into the link was <code>http://localhost:8090</code>. Now defaults to <code>http://uls-ep-aglipccb:8090</code>; local-test config keeps the local override so the local-test redirect loop still works end-to-end.' },
            { badge: 'fix', text: '<strong>Tab clicks no longer re-run the heavy Agile query.</strong> The admin "docs due within N days" lookup is now cached for 5 min at the service layer; status overlay (NOT_SENT / SENT_TO_DO / DM_APPROVED / etc.) is still computed fresh on every <code>/data</code> call from the in-memory queue log, so send/respond/cancel show up immediately without invalidating the cache. The Refresh button now forces a re-query (passes <code>refresh=true</code>) — use it when you suspect Agile has new docs in the window.' },
            { badge: 'fix', text: '<strong>Email link with <code>?asDO=true</code> always lands in card view now</strong>, even for admins on the Cc DL with zero items in their queue. Previously the empty-queue case fell back to admin view and triggered the heavy Agile query on every click; now you see the empty-state card ("Nothing waiting for you right now…") with a "Switch to admin view" link if you actually want the table.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'New tab: IMS Review (pilot) — DRR workflow lives in the toolkit',
        items: [
            { badge: 'new', text: '<strong>New IMS Review tab</strong> alongside Review Tracker. Pilot of the new DRR (Document Review Request) workflow described in CRD ECN-129414-PROJ. DCC analysts manually trigger Review emails per-doc from the admin view; Document Owners + their Managers land on the same tab via the email link with a tailored card view. Three DO response options (<em>No Change Needed</em>, <em>Needs Change</em> w/ side-panel file upload, <em>Need Help</em>) and two DM options (<em>Confirm No Change</em>, <em>Send Back to DO</em>). The existing Agile SDK weekly job continues to run unchanged — this is purely additive.' },
            { badge: 'new', text: '<strong>Auto-grant for DOs and DMs</strong>: when someone clicks the email link, the toolkit validates their AD creds and gives them the IMS Review tab for that session only if there is a doc waiting for them. DCC analysts get persistent access via User Management (key: <code>ims-review</code>). Admins always see the tab and get the full admin view (table + KPI strip + Send/Resend/Cancel + Get File). Get File pulls attachments via plm-agile-service the same way Shop-Floor Docs does (single file → original name; multi → zipped).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>ImsReviewService</code> + <code>ImsReviewQueueStore</code> (JSONL event log at <code>data/ims-review/queue.jsonl</code>, replay on JVM start) + <code>ImsReviewEmailService</code> with five HTML templates under <code>templates/email/ims-review-*.html</code>. New endpoints under <code>/api/ims-review/{role,data,my-queue,send,respond,cancel,file}</code>. LDAP <code>manager</code> attribute lookup for DM resolution with graceful fallback to <code>vikas.singh3@sandisk.com</code> placeholder DL. <strong>Local-test email redirect</strong> via <code>app.ims-review.email-redirect-to</code> config — when set, all outbound emails go to that address with a banner showing the original To/Cc; subject suffixed with <code>[LOCAL TEST]</code>. Activity log gets seven new <code>IMS_REVIEW_*</code> event types. Full design spec at <code>docs/superpowers/specs/2026-05-18-ims-review-pilot-design.md</code>.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'New tab: Review Tracker — documents due for next review',
        items: [
            { badge: 'new', text: '<strong>New top-level tab</strong> between ECN Report and Shop-Floor Docs. Lists documents whose <em>Next Review Date</em> falls inside a selectable window: <em>Overdue</em>, <em>Next 7 days</em>, <em>Next 30 days</em> (default — matches the Agile UI search), <em>Next 90 days</em>, or <em>Custom…</em>. Excludes OBS / OBS-SKU / Preliminary lifecycle phases. Shows Document Number, Description, Lifecycle Phase, Rev, Document Type, Document Owner(s), Next Review Date, and any Pending Change attached to the doc. Document Number and Pending Change Number deep-link into Agile.' },
            { badge: 'new', text: '<strong>Sortable columns + per-column filters</strong> (same pattern as Revision History). Overdue dates are highlighted red. Insight strip up top: unique documents, total rows, count overdue, query time. <strong>↻ Refresh</strong> re-runs the Agile query; <strong>⬇ Export</strong> downloads <code>Doc-Reviews-YYYY-MM-DD.xlsx</code> with the same 9 columns. Both events log to the activity monitor (<code>DOC_REVIEW_SEARCH</code> / <code>DOC_REVIEW_EXPORT</code>).' },
            { badge: 'improve', text: '<strong>Refresh is now ~10× faster.</strong> Initial cold query took ~2.5 minutes on the Mac dev box; rewrote the SQL as a CTE chain that filters <code>item × page_three</code> by date + subclass first (~1,459 rows), then carries that qualified set down into the owner LISTAGG and pending-changes subqueries via <code>IN (SELECT id FROM qualified_with_phase)</code>. Now ~16 s cold, sub-second on cache hit. Identical 1,628-row output; same Document Owners and Pending Change columns.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>DocReviewService</code> runs <code>documents_next_review_search_optimized.sql</code> (CTE rewrite of the original). Joins <code>agile.item</code> → <code>page_three.date32</code> for the next-review date, the default-change REV for the current lifecycle phase, <code>agile_flex[attid=251733512]</code> via LISTAGG for the multilist Document Owners, and a per-item pending-change subquery (<code>statustype IN (0,1,2)</code>). New endpoints <code>GET /api/doc-review/data</code>, <code>POST /api/doc-review/refresh</code>, <code>GET /api/doc-review/export</code> with optional <code>window=overdue|7d|30d|90d|custom</code> + <code>from</code> / <code>to</code> ISO dates. Single-slot in-memory cache (~3 min cold) keyed by window so subsequent /data hits and Export are sub-second; Refresh forces a re-query. Manual-Refresh UX (tab click does NOT auto-query) follows the same pattern just shipped for Overdue Tracker. The biggest remaining win available is a DBA-side <code>AGILE_FLEX(ATTID, ID)</code> composite index — the owner LISTAGG joins via an unindexable LIKE on text, and that index would shrink the LIKE\'s scan space.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'Overdue Tracker: stop auto-running AI on tab click — manual Refresh only',
        items: [
            { badge: 'fix', text: '<strong>Tab clicks no longer trigger AI categorization.</strong> Until today, opening Overdue Tracker — or even just bouncing between sub-tabs — could kick off a fresh ~1–3 minute Portkey run, especially when the server had been restarted or when a small filter mismatch slipped through. Now <code>GET /api/ecn-report/overdue/data</code> is a pure cache reader; AI runs <em>only</em> when you press <strong>↻ Refresh</strong>.' },
            { badge: 'improve', text: '<strong>Clear empty / stale states.</strong> If the cache is empty (e.g., after a server restart), the tab shows a friendly <em>"Click ↻ Refresh to load Overdue Tracker data — takes ~1–3 minutes"</em> CTA instead of silently spinning. If you change a filter (classification chip, days-over-target, date created/released) without refreshing, an amber <em>"Filters changed since the last refresh"</em> banner appears above the previously-cached table — so you can see both the old result and the prompt to re-run.' },
            { badge: 'fix', admin: true, text: '<strong>Latent fingerprint bug fixed.</strong> The cache-invalidation fingerprint compared <code>resolveCreatedRange()</code>\'s <code>{null, null}</code> against the stored <code>{"", ""}</code>, which never matched when no date range was set — so every <code>/data</code> call was logging a "filters changed" miss and re-running. <code>getData()</code> now uses an <code>nz()</code> helper to normalize null → "" on both sides; combined with the manual-refresh switch above, tab clicks reliably hit the cache.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'Overdue Tracker: group by ECN classification (Standard + PDR by default; others as reference)',
        items: [
            { badge: 'new', text: '<strong>Classification chip filter</strong> on the Overdue Tracker. Default: <em>Standard ECNs</em> + <em>PDR ECN\'s</em> checked (the "actual count" classifications used by the KPI section). The reference-only classifications — <em>CCB ECN\'s</em>, <em>Dedicated Process ECN\'s</em>, <em>Factory Changes</em>, <em>Project ECN\'s</em> — appear as unchecked chips and can be toggled on for delay-reason analysis. Selection persists in localStorage. Per Jimmy 2026-05-18.' },
            { badge: 'improve', text: '<strong>Per-classification target days</strong>. The "over target" gate is now computed per-row from the row\'s classification + priority: <em>Standard</em> = 10d standard / 6d urgent, <em>PDR</em> = 15d / 10d, others = 30d fallback (since they have no formal SLA in baselineTargets). Each row now shows its <em>Target</em> column alongside <em>Days</em> and <em>Over Target</em>, and the Excel export gains <em>Classification</em> and <em>Target Days</em> columns. <em>Days = Actual − Target</em>, so "+5d" means truly 5 days past <em>this row\'s</em> SLA, not the Standard 10d baseline.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>OverdueTrackerService</code> now loads <em>all</em> classifications from <code>baselineTargets</code> (not just Standard) — 70 rc_keys spanning 6 classifications. <code>pickOverdueEcns</code> wraps the base query in a subquery and computes <code>target_days</code> via a dynamic <code>CASE WHEN UPPER(pri.entryvalue)=\'URGENT\' AND rc_key IN (…) THEN N WHEN rc_key IN (…) THEN M …</code> expression, grouped by unique (target, priority) buckets to keep the SQL compact. The outer <code>WHERE actual_days &gt; target_days + ? AND ≤ target_days + ?</code> applies the user\'s min/max-over window against each row\'s own target. New <code>classifications=</code> CSV param on <code>/data</code>, <code>/refresh</code>, <code>/export</code>; default Standard+PDR when empty. Cache fingerprint folds in the classification set so toggles invalidate cleanly. <code>"NA"</code> target days in baselineTargets fall back to <code>NO_SLA_FALLBACK_TARGET_DAYS=30</code>.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'Overdue Tracker: activity-monitor logging (refresh/export visible to admins)',
        items: [
            { badge: 'fix', text: '<strong>Overdue Tracker activity is now logged.</strong> Until today the Help &amp; Support activity widget mis-labeled an Overdue Tracker visit as <em>"Opened ECN Report → Cycle Time"</em>, and the Refresh / Export buttons left no audit trail at all — so when Jimmy ran a report this morning, the admin "what did Jimmy do today?" query showed nothing about Overdue Tracker usage. Fixed: switching to the Overdue Tracker pill now logs <em>"Opened ECN Report → Overdue Tracker"</em>, and Refresh + Export emit dedicated <code>OVERDUE_REFRESH</code> / <code>OVERDUE_EXPORT</code> events with the active filter set, mode (open / retrospective), and row count.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'Overdue Tracker: "Date Released" criterion → retrospective view',
        items: [
            { badge: 'new', text: '<strong>New "Date released" picker</strong> (same shape as Date created — Last 7d / Last 30d / YTD / Custom). Pick a window to flip the tab into a <strong>retrospective view</strong>: instead of "open ECNs over target right now," it shows <em>"Standard ECNs that were RELEASED in this window and were overdue at release time."</em> Cycle time switches from <code>SYSDATE − submit_date</code> to <code>release_date − submit_date</code> (frozen at release) and the open-only filter is dropped automatically.' },
            { badge: 'improve', text: 'Clear UX cues for the mode switch: an amber callout banner sits above the table explaining the retrospective semantics, a "?" tooltip on the picker, the KPI tile relabels from <em>Open &amp; overdue</em> → <em>Released &amp; overdue</em>, the section header re-titles to <em>Released Standard ECNs — overdue at release</em>, and the status line shows the active <em>Released:</em> window in amber so the mode is unmistakable. Clearing the dropdown returns to the live in-flight view.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>OverdueTrackerService.pickOverdueEcns(..., releasedFromIso, releasedToIso)</code> swaps two SQL fragments when a released window is present — <code>elapsedExpr</code> becomes <code>TRUNC(c.release_date − c.submit_date)</code> (used for the over-target gate, the ORDER BY, and <code>actual_days</code>), and the <code>sn.name NOT IN (...)</code> open filter is replaced by <code>c.release_date IS NOT NULL</code> plus the date-window clauses. <code>release_date</code> column confirmed from <code>plm_repos/agilechangextract/SandiskDBCheck.java:51</code>. Cache fingerprint folds in <em>both</em> date windows so switching between modes correctly invalidates. New <code>mode</code> field in the JSON response ("open" | "retrospective") drives the UI labels. Excel export gets a new <em>Release Date</em> column and the file is named <code>Overdue-Tracker-Retrospective-YYYY-MM-DD.xlsx</code> in retrospective mode.' }
        ]
    },
    {
        date: 'May 18, 2026',
        title: 'Overdue Tracker: Date Created filter (YTD presets) + Export Excel',
        items: [
            { badge: 'new', text: '<strong>Date Created filter</strong> on the Overdue Tracker toolbar — narrow to ECNs submitted in the <em>Last 7 days</em>, <em>Last 30 days</em>, <em>YTD</em>, or a <em>Custom</em> from/to range. <em>YTD</em> aligns this view with the Cycle Time KPI section so you can compare apples-to-apples (Jimmy 2026-05-18). The filter composes with the existing days-over-target range and the active window echoes back in the status line.' },
            { badge: 'new', text: '<strong>Export Excel</strong> button (next to Refresh). Downloads the current view as <code>Overdue-Tracker-YYYY-MM-DD.xlsx</code> with one sheet covering ECN, Product Line, Team, Analyst, Priority, Actual Days, Over Target, Status, <em>Created Date</em>, <em>AI Category</em>, <em>AI Summary</em>, and the full <em>Comment Chain</em>. Re-uses the in-memory cache when the filters match, so export after Refresh is instant (no second AI cost).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>OverdueTrackerService.pickOverdueEcns(int,int,String,String)</code> now injects <code>AND c.submit_date &gt;= TO_DATE(?, \'YYYY-MM-DD\') AND c.submit_date &lt; TO_DATE(?, \'YYYY-MM-DD\') + 1</code> when a range is set. New <code>resolveCreatedRange()</code> turns <code>last7d</code> / <code>last30d</code> / <code>ytd</code> / <code>custom</code> into concrete ISO dates at query time so a saved URL keeps rolling. Cache fingerprint now folds in the created window so switching presets correctly invalidates. New <code>GET /api/ecn-report/overdue/export</code> streams an <code>SXSSFWorkbook</code> built from the cache (with a re-fetch if filters miss).' }
        ]
    },
    {
        date: 'May 16, 2026',
        title: 'Overdue Tracker: live progress instead of a generic "Loading…"',
        items: [
            { badge: 'improve', text: '<strong>Stage-aware progress on first load.</strong> The Overdue Tracker initial load takes ~1–3 minutes (it pulls open out-of-target ECNs, hydrates the CCB comment chain for each, then AI-categorizes them in batches of 10). Instead of a stoic <em>"Loading…"</em>, the status line + the three empty panels now show what is actually happening: <em>"Querying Agile for open overdue Standard ECNs"</em> → <em>"Pulling CCB comment chains for 100 ECNs"</em> → <em>"AI-categorizing 100 ECNs · batch 4 / 10 (40%)"</em>, with an elapsed-seconds counter and a braille spinner so you can tell the page isn’t hung.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>GET /api/ecn-report/overdue/progress</code> reads volatile <code>progressStage</code> / <code>progressBatchDone</code> / <code>progressBatchTotal</code> / <code>progressEcnCount</code> fields on <code>OverdueTrackerService</code> without taking the synchronized lock the in-flight refresh holds. Stages: <code>idle → querying → comments → categorizing → done</code>. Frontend polls every 1.5s while <code>/data</code> or <code>/refresh</code> is in flight and re-paints the four <em>"Loading…"</em> slots; polling stops as soon as the main response resolves.' }
        ]
    },
    {
        date: 'May 16, 2026',
        title: 'Revision History: Item Numbers now optional when a Release Date range is set',
        items: [
            { badge: 'new', text: '<strong>Search without typing item numbers</strong> on the Revision History tab. Pick a <em>Release Date</em> window (e.g. <em>Last 7 days</em>) — and optionally a Lifecycle pill (<em>ACT</em>, <em>MKT</em>, …) — and the toolkit pulls every revision released in that window across <em>all</em> items. The classic workflow ("which items reached ACT in the last 7 days?") no longer requires sourcing the item list first. Per Vikas Jindal 2026-05-16 — the criteria <em>are</em> the input.' },
            { badge: 'improve', text: 'The placeholder on the Item Numbers field now reads <em>"Optional when Release Date is set"</em> and the empty-state hint calls out the new no-items workflow. Trying to Search with neither items nor a date range surfaces a friendlier alert instead of a silent empty result.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>ChangeHistoryService.getHistoryFiltered()</code> no longer short-circuits on empty <code>itemNumbers</code>. When the list is empty AND a release-date constraint is present, it runs a single filters-only query (no <code>ITEM_NUMBER IN (…)</code> clause) capped at <code>FETCH FIRST 25000 ROWS ONLY</code> and ordered <code>RELEASE_DATE DESC</code> so the cap returns the most-recent rows. Empty items + no date range is refused server-side (would otherwise scan all of <code>AGILE.REV</code>). <code>/api/history/{search,export,email}</code> drop the <code>required=true</code> on the <code>items</code> param; the existing First/Last entry, Lifecycle, Change Type, and Part Type filters all still compose.' }
        ]
    },
    {
        date: 'May 15, 2026',
        title: 'Overdue Tracker: filter ECNs by "days over target" range',
        items: [
            { badge: 'new', text: '<strong>Days-over-target range filter</strong> on the Overdue Tracker toolbar. Two number inputs (<em>min – max</em>, in days past the 10-day Standard target) let you ask narrower questions — e.g. <em>"show me ECNs that are 30+ days over target"</em> (min=30, max=170) or <em>"the freshly-late ones, 1–10 days over"</em> (min=1, max=10). Defaults to <em>0–170</em>, which matches the original 180-day-from-submit cap. The active range shows up in the status line and the KPI tiles / category breakdown / Top Product Teams panel all recompute against the filtered population.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>OverdueTrackerService.pickOverdueEcns(int minOver, int maxOver)</code> replaces the hard-coded <code>&gt; 10 AND &lt;= 180</code> with parameterized bounds that always anchor off <code>STANDARD_ECN_TARGET_DAYS</code>. Controller accepts <code>minOver</code> / <code>maxOver</code> on <code>/api/ecn-report/overdue/data</code> and <code>/refresh</code>; null → defaults (0 / 170). Hard ceiling at 3650 just to keep someone from typing 9999999 into the box. The in-memory cache is keyed by range now — switching ranges forces a re-pull + re-categorize so KPIs are always accurate for the current filter.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'ECN Report → new Overdue Tracker sub-tab with AI-categorized CCB comment chains',
        items: [
            { badge: 'new', text: '<strong>Overdue Tracker</strong> — third pill on the ECN Report toolbar (next to Cycle Time / Returns Tracker). Lists every <em>open Standard ECN that is past its 10-day target</em>, pulls the CCB comment chain for each from <code>change_history</code>, and sends each chain to Claude (via Portkey) for one-shot categorization into one of 8 reasons: <em>CCB / Approval Delay · Pending Stakeholder Action · Cost · Information Gap · Build/Sample · Customer/External · Tooling · Other</em>. Per Jimmy 2026-05-14 — the same AI-classification pattern Returns Tracker uses, aimed at the "why is this ECN sitting?" question PCM gets asked daily.' },
            { badge: 'new', text: 'KPI tiles up top (open & overdue count · avg days over target · total day-debt · AI-categorized ratio), a category-breakdown bar chart, a Top Product Teams panel, and a drill-down table per ECN with the AI summary + the raw CCB comment chain (collapsible).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>OverdueTrackerService</code> + <code>OverdueTrackerController</code> (<code>GET /api/ecn-report/overdue/data</code>, <code>POST .../refresh</code>). Comment-chain SQL mirrors <code>weekly_rejection_report.py</code>\'s <code>CCB_COMMENTS_SQL</code> but with a wider <code>event_type IN (14,15,17,65)</code> filter (the "Comment" action types — rejection uses <code>event_type=13</code>). Standard-ECN classification + 10-day target match <code>STANDARD_ECN_CLASSIFICATIONS</code> in the Python ECN generator. AI categorization batches 10 ECNs per Portkey call (<code>@anthropic-eastus2/claude-sonnet-4-6</code>) — same shape as <code>categorize_rejections_with_ai</code>. In-memory cache only for v1; lazy-loaded on first tab open, replaceable via the Refresh button.' },
            { badge: 'improve', admin: true, text: '<strong>Out of scope for v1, easy to add later</strong>: Excel export · email-this-view · recipient management · narrative AI · scheduled regenerator · trend chart over time.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Revision History: First/Last entry · Release Date range · Part Type chips (PT-63 + PT-64)',
        items: [
            { badge: 'new', text: '<strong>First / Last entry per (item × lifecycle state)</strong> radio in the Revision History pre-filter. Pick <em>First entry only</em> to see the moment each item first reached a state; <em>Last entry only</em> for the most-recent. Default <em>Show all</em> is unchanged. Lets you build single-row-per-state extracts for audits without post-processing the spreadsheet. (PT-63 — Vikas Singh)' },
            { badge: 'new', text: '<strong>Release Date filter</strong> (from / to) plus a <em>relative-date</em> dropdown (Last 7 days · Last month · Last quarter · Last year) so a saved + scheduled search keeps tracking the rolling window — the server re-resolves the macro at query time. Picking explicit dates clears the relative dropdown; picking a relative window auto-fills the date inputs for display. (PT-64 — Vikas Singh)' },
            { badge: 'new', text: '<strong>Part Type chips</strong> (SKU · ECO · MCO · Drawing · Document · Assembly) — toggle on the chips you care about and the result is post-filtered against <code>item_extract.NEW_PART_CLASS</code>. SKU-mostly workflows can now narrow without scrolling through unrelated change events. Substring-match is case-insensitive so "SKU" finds <em>"A199-Retail Finished Good (SKU)"</em>. (PT-64 — Vikas Singh)' },
            { badge: 'improve', text: 'Active pre-filters are summarized in a new chip on the result-bar so you can tell at a glance which constraints shaped the result (e.g. <em>"Per (item × state): Last entry · Release Date: 2026-04-14 → 2026-05-14"</em>).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: introduced <code>ChangeHistoryService.HistoryFilters</code> as a single value object so the controller doesn\'t carry a 9-arg positional API. PT-63 modes wrap the base query in a <code>SELECT * FROM (… ROW_NUMBER() OVER (PARTITION BY ITEM_NUMBER, lifecycle ORDER BY RELEASE_DATE ASC|DESC) AS rn) WHERE rn = 1</code> envelope; the Lifecycle pre-filter still composes (e.g. <em>last entry into MKT</em>). Date range becomes <code>RELEASE_DATE BETWEEN TO_DATE(?, \'YYYY-MM-DD\')</code> bounds with the upper bound rendered as <em>+1 day</em> so a TO_DATE comparison includes records released anywhere on that day. Part Type filter is a post-filter — <code>NEW_PART_CLASS</code> lives in the custom_user schema (different DataSource) so we can\'t in-line the join; we batch-look up matched items via <code>customDataSource</code> after the main query returns.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Help chatbot: "who built this site / how is AI used" now answers properly (PT-62)',
        items: [
            { badge: 'fix', text: '<strong>Help chatbot meta-question fix.</strong> Asking <em>"who built this site and how is AI used?"</em> in the help drawer used to return the <em>compute-intensive users in the last 24 hours</em> activity widget — embarrassing in a live demo. The chatbot now intercepts <em>who-built / how-is-AI-used / what-model-does-this-use / tech-stack</em> meta questions before any activity-report routing and returns a curated answer about the project, the team, and how AI is used inside the toolkit (Portkey gateway → Anthropic/Azure/Vertex, KB chatbot, Ask AI, AI Eval, smart upload, feedback triage, gibberish gate).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>tryMetaAboutToolkitAnswer()</code> runs at the top of <code>tryAdminActivityQuery</code> and short-circuits with an HTML answer when the question matches any of ~20 meta-phrase markers (<em>who built</em>, <em>how is ai used</em>, <em>what model</em>, <em>tech stack</em>, etc.). Separately the <code>parseActionFilter</code> matcher for compute-intensive actions was tightened — <code>q.contains("ai use")</code> was catching <em>"AI used"</em> in passive-voice meta questions and routing them into the activity widget; replaced with the volume-shaped markers <code>"ai usage"</code> / <code>"ai activity"</code> / <code>"ai calls"</code>.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Feedback Queue: ⬇ Export Excel button on the admin queue',
        items: [
            { badge: 'new', text: '<strong>Export Excel</strong> button on the Feedback Queue (User Permissions → Feedback). Downloads the items currently visible under your active filter (Open / In Progress / Dismissed / Done) as an .xlsx. Columns: <em>PT ID · Submitted · Status · Requestor · Email · Short Description · Estimated (hr) · Actual (hr)</em>. Estimated and Actual are numeric so you can pivot/aggregate over them in Excel.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>GET /api/feedback/queue/export?status=&lt;filter&gt;</code> (admin-only) streams an <code>SXSSFWorkbook</code>. <code>status=open</code> unions the <code>triaging</code> and <code>awaiting_approval</code> sub-states the same way the UI filter does. Short Description is the full ticket text with newlines collapsed to <code> / </code> so each ticket stays on one row.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: spec-chip rows collapse around your input — no more 250-chip walls',
        items: [
            { badge: 'fix', text: '<strong>Spec chip rows now collapse around the spec you searched.</strong> A top-level assembly drawing can apply to 250+ spec codes; rendering them all as chips obliterated the screen on every result card AND in the "Filter by Spec / Step Code" row above the results. Now: in <em>Find by Station / Product</em> mode, only your input spec shows by default with a <em>"show 248 other spec codes"</em> link to expand. In <em>Find by Part / Document Number</em> mode (no specific spec), the first 8 chips show with the same toggle. Click <em>show fewer</em> to collapse back.' },
            { badge: 'improve', text: 'The input spec is highlighted (filled with the doc-style accent color) so it’s instantly recognizable as <em>your</em> step. Per-card and top-row toggles are independent — expanding one card doesn’t expand the rest.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: cascading filter — dropdowns narrow as you type, style chips grey + show counts',
        items: [
            { badge: 'new', text: '<strong>Cascading filter</strong> on the Find by Station / Product form. Type <code>0411</code> in Spec → the Product Group dropdown shrinks to the 70 PGs that have at least one doc at step 0411, the Product dropdown shrinks to the products available there, and the Document Style chips re-render with live counts (<em>Drawing (184) · VAR (19) · VIC (6)</em> — everything else greys out). Pick OMAHA HHHL on top of that → counts drop to <em>Drawing (22) · VAR (2) · VIC (2)</em>. The <strong>Show Documents</strong> button shows a "26 matches" badge when all three required fields are filled, so you can see <em>before</em> clicking whether the search will return anything.' },
            { badge: 'improve', text: '<strong>Sub-millisecond cascade.</strong> Each keystroke (debounced 300ms) hits a single endpoint that does set intersections over the in-memory inverted index. No SQL fires. Greyed-out chips are <code>disabled</code> at the DOM level so misclicks are impossible. The current value in the field you’re typing into stays put even if it leaves the suggestion list — datalists only suggest, never restrict.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>SdsmContextIndex.options(...)</code> projects the per-axis intersection onto the other three axes ("axis-X candidates = items that match all OTHER filters"). Returns <code>{specs, productGroups, products, styleCounts, totalCandidates}</code>. <code>GET /api/sdsm/context-options?spec=&amp;productGroup=&amp;product=&amp;styles=</code> serializes the same shape; ~10-30ms warm-cache. The original three list endpoints (<code>/specs</code>, <code>/product-groups</code>, <code>/products</code>) stay for backward compat but the UI now uses the cascading endpoint exclusively.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: operator-first entry form — pick Spec / Product Group / Product instead of typing a doc number',
        items: [
            { badge: 'new', text: '<strong>Find by Station / Product</strong> is the new default mode on Shop-Floor Docs (<em>Cari mengikut Stesen / Produk</em>). Operators pick their <strong>Spec / Step Code</strong>, <strong>Product Group</strong>, and <strong>Product</strong> from typeahead-searchable dropdowns &mdash; the same three fields Camstar eVAR / TTMS use to pick which docs to surface. The result list is every controlled PDF that matches across all parts, not just the docs for one part number.' },
            { badge: 'new', text: 'Optional <strong>Document Style chips</strong> (WI / VAR / VIC / Drawing / etc.) narrow the result set further. Click a chip to toggle that style on/off; chip color matches the result-card badge color so it’s instantly obvious which type you’re filtering to.' },
            { badge: 'improve', text: '<strong>Find by Part / Document Number</strong> still exists as the secondary mode &mdash; doc owners verifying a freshly-released DCO and admins diagnosing tickets typically know the doc number directly. Toggle between modes with the pills at the top of the tab; preference persists across sessions.' },
            { badge: 'improve', text: '<strong>Bahasa subtitles</strong> on every form label since the audience is the Penang factory floor. Required-field markers (<span style="color:#B8342B;">*</span>) on Spec / Product Group / Product mirror the eVAR rule that all three together identify which docs apply.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>SdsmContextIndex</code> service maintains an in-memory inverted index (<code>spec→items</code>, <code>productGroup→items</code>, <code>product→items</code>) over every Document + Part that survives the SDSM gates. Built lazily on a 4-hour TTL with a <code>@PostConstruct</code> background pre-warm so the dropdowns are already populated when the operator opens the tab. Latest build = 1,308 items / 458 specs / 183 product groups / 2,433 products in ~2 minutes against agprod. Search-by-context = simple set intersection on the inverted maps with "OR ALL" union semantics on Product Group / Product (per eVAR training-guide page 22). New endpoints: <code>GET /api/sdsm/{specs,product-groups,products}</code> for dropdown population + <code>GET /api/sdsm/search-by-context?spec=&amp;productGroup=&amp;product=&amp;styles=</code> for the actual search. Same <code>SdsmAttachment</code> shape returned as the part-number search so the result card renderer is reused unchanged.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: eVAR/TTMS-aware — Offline VAR/WI styles, ALL-conflict detection, "How this works"',
        items: [
            { badge: 'new', text: '<strong>Three new Document Styles</strong> in the SDSM whitelist: <code>Offline VAR</code>, <code>Offline WI</code> (TTMS — offline stations), and <code>Temp VAR</code> (eVAR — temporary VARs). The two Offline-* styles render with a purple badge so operators can tell at a glance which docs come from the eVAR (online) set vs the TTMS (offline) set. Source: eVAR + Offline Document Training Guide (Agile doc <code>27-04-SM-02-00054</code>) pages 22 + 25.' },
            { badge: 'new', text: '<strong>"How this works" panel</strong> on the SDSM tab (collapsible, closed by default) explaining: this view is the live equivalent of Camstar eVAR / TTMS with no overnight ETL lag, the four matching gates a doc has to pass, and the silent-killer "ALL conflict" rule that bites doc owners.' },
            { badge: 'new', text: '<strong>"ALL conflict" detection</strong> in the auto-diagnostic. When the doc owner sets <em>Product / Product Group / Spec</em> to <code>ALL</code> AND also lists specific values in the same field, the doc silently doesn’t display in eVAR. The Why? diagnostic now flags this with a dedicated <code>FAIL</code> row + remediation step, so the doc owner can fix it without having to know the rule from page 22 of the training guide.' },
            { badge: 'improve', text: '<strong>Chatbot now answers eVAR/TTMS questions.</strong> Knowledge base extended with the eVAR vs TTMS distinction, the daily-ETL lag (~14 hours from DCO implement to floor display) and how this tab cuts it out, the supported Document Style sets per system, and the ALL-conflict rule. Ask things like <em>"what’s the difference between eVAR and TTMS?"</em> or <em>"why doesn’t my doc show up in Camstar?"</em>' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>SdsmConstants.DOC_STYLE_WHITELIST</code> grew from 7 to 10 entries. <code>SdsmDiagnoseService.readAllConflict()</code> resolves each token in the Documents-flex Product / Product Group / Spec via LISTENTRY (or ITEM.ITEM_NUMBER for the Product field) and flags fields where literal "ALL" coexists with another non-empty token. <code>FlexBundle.allConflict</code> carries the list of conflicting field names through to the diagnostic UI. The conflict check is silent when no conflict exists, so happy-path diagnostics still read clean.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Help → What’s New + the login-page link were dead — fixed',
        items: [
            { badge: 'fix', text: 'Clicking <strong>What’s New</strong> in the Help dropdown (and the <em>View all &rarr;</em> link on the bottom of the login page) was a no-op. Both call <code>showWhatsNew()</code>; that function lives in <code>whats-new.js</code> which had a syntax error introduced in an earlier entry &mdash; the entire file failed to parse, so every function in it (including <code>showWhatsNew</code>) was undefined. The badge dot in the dropdown still rendered fine because that uses <code>WHATS_NEW_RELEASES.length</code> via a different code path.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: line 243 of <code>whats-new.js</code> had <code>{key:\\\'daysAtPending\\\', label:\\\'Pending\\\'}</code> &mdash; the <code>\\\\</code> reduces to a literal <code>\\</code> and the next <code>\'</code> closed the surrounding JS string early. Replaced with a single backslash escape (<code>\\\'</code>). Validated end-to-end with <code>acorn.parse()</code> on the served file (was failing at pos 46007, line 243 col 121; now parses cleanly at 274,018 chars). Worth adding a CI parse-check step on <code>static/*.js</code> so this can’t happen again silently.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: recent-searches breadcrumb + cleaner empty state',
        items: [
            { badge: 'new', text: '<strong>Recent searches strip</strong> on Shop-Floor Docs &mdash; every part / document number that returned at least one doc gets pinned as a clickable chip just above the input. Click a chip to re-run the search; click the &times; on a chip to drop it. Capped at 10, persisted in localStorage so it survives refreshes. The strip stays hidden until your first successful search.' },
            { badge: 'improve', text: '<strong>Why? button removed</strong> &mdash; the diagnostic now fires automatically when a search returns zero documents, no extra click. The static yellow "No documents found" placeholder is replaced by the live diagnostic verdict + checks + next-steps + admin contact line.' },
            { badge: 'fix', text: '<strong>Footer subtitle removed</strong> from every tab (was: "Agile PLM Toolkit &middot; Field Changes from item_history &middot; BOM &amp; Parts from live database &middot; ..."). The line was pinning awkward whitespace below short-content tabs (Shop-Floor Docs empty state, Single/Sole Source pre-run). The build label is still on the navbar tooltip and the version pill, so nothing useful was lost.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: per-deviation "↓ zip" link bundles every QN attachment via SDK',
        items: [
            { badge: 'new', text: 'Each row in the <strong>Active Quality Notices</strong> banner now has a <strong>↓ zip</strong> link. Click it to download a single zip containing every attachment for that deviation (the .pdf, the .docx, the .eml, the .xlsx — whatever is on the change). Filename pattern: <code>&lt;deviation-number&gt;_attachments.zip</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>GET /api/sdsm/deviation-zip/{changeNumber}</code>. Resolves the change ID, lists every <code>ATTACHMENT_FULL_MAP</code> row with that PARENT_ID, then for each one calls <code>plm-agile-service</code> at <code>/api/sdsm-file/{attachId}</code> and streams the bytes into a <code>ZipOutputStream</code>. Deliberately bypasses the local-share probe — the goal is to verify the SDK download path end-to-end on prod. Response carries <code>X-SDSM-Source: agile-sdk</code>. Filename collisions inside one deviation are deduped by appending the attachId.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Shop-Floor Docs: new "Why?" button explains exactly which gate dropped a part',
        items: [
            { badge: 'new', text: '<strong>Why? button</strong> next to <em>Show Documents</em> on the Shop-Floor Docs tab. Type a part / document number and hit <em>Why?</em> to see a step-by-step audit: does the item exist, is its Document Style in the supported set (WI / VAR / VIC / FPS / Guideline / Checklist / Drawing), is there a latest <em>Implemented</em> DCO (or ECO for Parts), are Spec / Product / Product Group populated, and are there PDFs on the latest change. Each gate is reported as PASS / FAIL / INFO with the exact value the database holds.' },
            { badge: 'new', text: '<strong>Concrete next-steps + admin contact</strong> for every FAIL. The diagnostic ends with a list of fixes (e.g. "Set Page Three → Spec on the doc in Agile") plus the line: <em>"If you expected this part to show controlled documents, contact pdl-plm-admin@sandisk.com."</em>' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>SdsmDiagnoseService</code> + <code>GET /api/sdsm/diagnose?q=&hellip;</code>. Probes the same SQL gates the main pass uses (PAGE_THREE.LIST36 for Documents, PAGE_TWO.LIST72 for Parts, AGILE_FLEX for Document flex fields, ROW_NUMBER + label-based status filter for the latest Implemented DCO/ECO, ATTACHMENT_FULL_MAP for attachment counts). Returns a structured JSON with verdict + checks + nextSteps + contactNote; rendered by <code>sdsmExplain()</code> in <code>sdsm.js</code>. No LLM call &mdash; the diagnostic is deterministic against the schema.' }
        ]
    },
    {
        date: 'May 14, 2026',
        title: 'Returns Tracker (PT-61): new classification taxonomy, Top Product Teams panel, per-column filters',
        items: [
            { badge: 'improve', text: '<strong>New ECN Return classifications.</strong> The Return-to-Pending Categories chart and the Category column now use the agreed taxonomy: <em>Returned by Owner</em>, <em>Incomplete Documentation</em> (ID), <em>Insufficient Information</em> (II), <em>Wrong Information</em> (WI), <em>Duplicate Request</em> (DR), <em>Return Requested</em> (RR). The legacy "Ambiguous Request" bucket is retired (rolled up into Unknown for any pre-existing events) and "Returned By Requestor" is renamed to "Returned by Owner".' },
            { badge: 'improve', text: '<strong>Comment-prefix wins.</strong> If the analyst writes the audit comment as <code>WI: Input PN doesn’t exist in Agile</code> (full label or short code, case-insensitive, colon separator), that classification is used directly — no AI guess. Falls back to the cached AI category, then Unknown, when no recognized prefix is present.' },
            { badge: 'improve', text: '<strong>Top Product Lines + Top Product Teams panels.</strong> The right-side chart on the dashboard now renders Top Product <em>Lines</em> (was: Teams). The bottom-right panel — previously "Top Themes (AI-clustered)" — now shows <strong>Top Product Teams</strong>, matching the breakdown in the KPI report.' },
            { badge: 'improve', text: '<strong>Results table refresh.</strong> Added a <em>Product Team</em> column right after Product Line. Replaced the <em>Theme</em> column with <em>Change Type</em> (matches the KPI report convention). The single "Filter events..." search box on the right is gone — replaced by a dedicated filter input under each column header so you can narrow ECN# / Requestor / Category / Change Type independently.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>RejectionTrackerService.enrichEvent(...)</code> applies category / productTeam / changeType in one place; <code>getEventsInRange(...)</code> calls it before returning so both the dashboard aggregator and the events table see consistent fields. <code>CODE_TO_CATEGORY</code> + <code>LEGACY_CATEGORY_ALIAS</code> static maps own the prefix-resolution rules. Frontend: <code>RETURNS_EVENT_COLUMNS</code> array drives both the header row and the per-column filter inputs in <code>returnstracker.js</code>; <code>returnsState.columnFilters</code> replaces the single <code>eventsFilter</code> string. The Excel export (Noraida’s ECN Pullback template) is intentionally untouched in this PR — if a Product Team / Change Type column is wanted in the extract too, that’s a follow-up against the template definition.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Help & Support: spelled-out numbers ("last one hour") parse correctly',
        items: [
            { badge: 'fix', text: 'Asking the chatbot <em>"who has logged in the last one hour"</em> used to ignore the time window completely and return every login from the last 90 days (884 entries). The deterministic router\'s regex required digits (<code>\\d+</code>), so spelled-out numbers fell through to the default 24-hour window, then got widened to 90 days by the "last X" branch. Now spelled-out small numbers (one through twelve) are normalized to digits before any of the time-window parsers see them.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>AiHelpController.normalizeWordNumbers()</code> static helper applied at the top of <code>tryAdminActivityQuery()</code> and <code>buildActivityContextForLlm()</code>. Single regex per word matches <code>\\b{word}\\s+(hour|minute|day|week|month|year|second)s?\\b</code> only — narrowly scoped so "anyone" doesn\'t become "any1". 13+ continues to require digits.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Shop-Floor Docs (SDSM): new tab replaces the nightly Windows batch with live agprod queries',
        items: [
            { badge: 'new', text: '<strong>Shop-Floor Docs tab</strong> — type or scan a part / document number and instantly see every controlled PDF tied to its latest released revision (work instructions, drawings, visual inspection criteria). Filter by spec / step code chips at the top, or click <em>View PDF</em> to open the doc inline in a full-screen reader. Designed to look like the kiosk view a Penang factory operator wants in front of them on the floor.' },
            { badge: 'new', text: '<strong>Active Quality Notice banner</strong> — if any QN deviations are in effect right now, a red banner at the top of the tab lists them with the through-date — no need to look them up per part. Pulled live from <code>CHANGE</code> with the active-window predicate, not a stale cache.' },
            { badge: 'improve', text: 'Replaces the legacy nightly batch (<code>~/documents/sdsm/call_run.bat</code> &rarr; <code>sdsmDataUpload_5_31_2023.jar</code>) that produced an .xlsx mapping file consumed by the Penang MES. Same data, but live and on-demand instead of stale-by-up-to-24h.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: 4 new services (<code>SdsmDocumentsService</code> / <code>SdsmPartsService</code> / <code>SdsmDeviationsService</code> / <code>SdsmFileService</code>) backed by the same JdbcTemplate the rest of the toolkit uses. Documents pass = <code>ITEM</code> CLASS=9000 + latest implemented DCO + <code>ATTACHMENT_FULL_MAP</code>. Parts pass = CLASS=10000 (excl. SKU) + latest implemented ECO. Deviations = <code>CHANGE</code> SUBCLASS=20336 with <code>STATUS=251745989</code> + <code>WORKFLOW_ID=251745973</code> active window + <code>ATTACHMENTTYPE=3566238</code> (Quality Notice). PDF bytes try the local share (<code>sdsm.share.dir</code>) first, fall back to <code>plm-agile-service</code> for SDK download. Full schema discovery in <code>docs/SDSM-DB-REPLACEMENT.md</code>.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Audit Trail (Change History): three columns added to match Agile (PT-60)',
        items: [
            { badge: 'new', text: '<strong>Action</strong> column \u2014 maps the change_history <code>EVENT_TYPE</code> code to a friendly label (Submit, Approve, Status Change, Add Reference, Modify, Sign Off, etc.). For status-change events the cell shows the actual transition (e.g. <em>Pending &rarr; Released</em>). Unknown codes fall back to <code>Action {N}</code> so nothing disappears.' },
            { badge: 'new', text: '<strong>Affected Object</strong> column \u2014 surfaces the item number of the object the event affected (when the event has an <code>AFFECTED_ITEM</code> set). Blank for events that don\'t target a specific item.' },
            { badge: 'new', text: '<strong>User(s) Notified</strong> column \u2014 aggregates the signoff recipients for the event as a <code>Last, First (loginid)</code> list, semicolon-separated. Matches the format Agile uses in its own Change History grid.' },
            { badge: 'improve', text: 'Excel export (<code>audit-trail-change-...xlsx</code>) now includes all three new columns in the same order as the on-screen table.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>EVENT_TYPE_LABEL</code> map in <code>AuditTrailService</code> covering ~25 of the most common codes from the prod distribution. SQL adds <code>h.EVENT_TYPE</code> + <code>LEFT JOIN AGILE.ITEM ai</code> for affected-object and a correlated <code>LISTAGG</code> subquery against <code>AGILE.SIGNOFF</code> + <code>AGILE.AGILEUSER</code> for users-notified. Subquery cost stays bounded by the outer <code>maxRows</code> cap (10K). Item History (PT-54) is untouched \u2014 only Change History gets the new columns.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Cycle Time (POM ECN KPIs Month): new spec from Jimmy (PT-59)',
        items: [
            { badge: 'fix', text: '<strong>Standard ECN type only.</strong> The POM monthly cycle-time panel now excludes PDR and other ECN classifications from volume + target metrics \u2014 matches the spec for what \u201ccompleted within target dates\u201d is supposed to mean for the POM scorecard.' },
            { badge: 'fix', text: '<strong>10-day target regardless of priority.</strong> Both Standard and Urgent priority ECNs are evaluated against a flat 10-day target. Anything over 10 days is overdue. (Old logic: Urgent had 6-day target, Standard had 10-day target. That priority-based split is gone for the Standard ECN type.)' },
            { badge: 'improve', text: '<strong>Both metrics shown.</strong> Avg days + % on target now appear together for each month and for the YTD Standard-ECN cycle-time panel. Applied retroactively \u2014 every report regeneration uses the new logic across all historical data, no cutoff date.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new module-level constants <code>STANDARD_ECN_CLASSIFICATIONS = {"Standard ECNs", "Standard ECN", "General ECNs", "General ECN"}</code> and <code>STANDARD_ECN_TARGET_DAYS = 10</code> in both <code>ecn_report_generator.py</code> and <code>ecnreport.js</code> (kept in sync). The target lookup short-circuits when <code>ecnClassification</code> matches the set \u2014 other ECN types still use the SLA matrix\u2019s priority-based targets. Panel 7 (3-month comparison) and the email POM panel both filter to the Standard ECN set; the YTD Standard ECN panel inherits the new target through <code>row._onTarget</code>. Non-Standard panels (PDR, KPI by Priority) are untouched.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'deploy.bat self-elevates so any PLM admin can run it',
        items: [
            { badge: 'fix', admin: true, text: 'Running <code>deploy.bat</code> from a non-elevated cmd silently no-op\u2019d its cross-user process kills \u2014 PowerShell <code>Stop-Process</code> can\'t terminate another domain user\'s java.exe / watchdog cmd without the unfiltered admin token, even when both users are in local Administrators (UAC token-splitting). Caused a prod outage today when Krati\'s deploy left the original watchdog alive next to her new one; both kept respawning JVMs that collided on port 8090.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: deploy.bat now probes <code>net session</code> at the top and self-relaunches via <code>powershell Start-Process -Verb RunAs</code> if not elevated. UAC prompt confirms (no password if the operator is in <code>IT-APP-PLMAdmin</code>), then the deploy runs in a fresh elevated cmd that <code>cmd /k</code> keeps open so output stays visible after exit. No change to the deploy steps themselves \u2014 only the privilege envelope.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Auto-mark-done now runs at startup (closes the deploy gap)',
        items: [
            { badge: 'improve', text: 'The auto-mark-done sweep used to fire only on the 5-minute cron, so a deploy that landed at 17:38 had to wait until 17:40 or even 17:45 before stamped tickets closed. Now the sweep <strong>also runs once at JVM startup</strong> (right after Spring finishes wiring), so any ticket whose stamped SHA matches the freshly deployed JAR auto-resolves within seconds of bringing the server up. Caught after PT-54 deployed at the exact wrong moment in the cron window.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>@EventListener(ApplicationReadyEvent.class) autoMarkDoneOnStartup()</code> in <code>FeedbackQueueService</code> that calls the existing <code>autoMarkDoneSweep()</code> once after the context is fully ready. Wrapped in try/catch so a startup-sweep failure can\'t crash the app \u2014 the cron still retries every 5 min as backup.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Audit Trail: Item History vs Change History toggle (PT-54)',
        items: [
            { badge: 'new', text: '<strong>Source toggle</strong> at the top of the Audit Trail tab \u2014 pick <em>Item History</em> (field-level changes on items, from <code>AGILE.item_history</code>) or <em>Change History</em> (workflow events on ECNs, from <code>AGILE.change_history</code>) before searching. Each table has its own column set; the wrong one wouldn\u2019t answer the question. Default is Item History because that\u2019s what most people mean by "audit trail of item X".' },
            { badge: 'fix', text: 'When you searched by Item Number, the old Audit Trail returned <em>change</em> events for changes that touched the item \u2014 not item field changes. Real item-level events (e.g. <em>Inv/Planning.BOM Parameter List was \u2192 is Yes</em>) now show up in the new <em>Item History</em> source.' },
            { badge: 'fix', text: 'Per-column filter inputs showed a literal "filter\\u2026" placeholder (raw JS escape inside a static HTML attribute, same family as the diagram-blurb bug). Now built via DOM properties so the real ellipsis character lands.' },
            { badge: 'fix', text: 'Username example placeholder said "jsessumes, vsingh" \u2014 doesn\u2019t match the real Agile format. Updated to "Sessumes, Jimmy (1359)" which is how usernames actually look in the system.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>AuditTrailService.run()</code> dispatches on <code>Query.source</code> (default "change" for back-compat). New <code>runItemHistory()</code> queries <code>AGILE.ITEM_HISTORY</code> joined to <code>AGILE.ITEM</code> (no NODETABLE join needed \u2014 item_history has no status IDs). <code>Result.source</code> echoes back to the UI so the column set + result-pill match. Cross-validation rejects change-number filters against item source (and prompts to switch toggle). Excel export honors source-specific columns + filename suffix (<code>audit-trail-item-...</code> or <code>audit-trail-change-...</code>).' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'No more "uls-ep-aglipccb:8090 says" popups',
        items: [
            { badge: 'improve', text: 'Every confirmation / alert / input prompt across the app now uses a SanDisk-styled in-page modal with the <strong>PT</strong> mark in the header \u2014 instead of the browser\'s native dialog that leaked the server hostname (<em>uls-ep-aglipccb:8090 says</em>). Destructive actions (Delete, Remove, Revoke, Purge) get a red <strong>OK</strong> button so the danger is visible at a glance. Esc and click-outside both cancel.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new shared <code>ui-modal.js</code> exposing <code>appAlert(msg, opts)</code>, <code>appConfirm(msg, opts)</code>, and <code>appPrompt(msg, defaultValue, opts)</code> \u2014 all Promise-based. 110 call sites across 18 files swapped over (94 alerts, 17 confirms, 9 prompts). Loaded ahead of all other JS in <code>index.html</code>, <code>login.html</code>, and <code>debug.html</code>. opts supports <code>title</code>, <code>okText</code>, <code>cancelText</code>, <code>danger</code>, and <code>placeholder</code> (prompt only).' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'OoM resolve emails now attach the original file back',
        items: [
            { badge: 'improve', text: 'When an auto-filed <em>crash-recovery</em> ticket is marked done, the ready-to-test email now <strong>attaches the original upload back to the user</strong>. They don\u2019t have to find the file again to re-try \u2014 it\u2019s right there in the email. Capped at 25 MB total so the mail relay doesn\'t reject the message; over the cap, the email still sends without the attachment and the log notes that fact.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>FeedbackResolveEmailService.sendReadyToTest</code> switches to <code>MimeMultipart("mixed")</code> when <code>isCrashRecovery</code> and at least one readable attachment fits under <code>ATTACHMENT_TOTAL_BYTE_CAP=24MB</code> (1 MB headroom for HTML body + MIME overhead vs the 25 MB SMTP cap). New helper <code>collectAttachmentsUnderCap()</code> handles both <code>attachmentPaths</code> (PT-38 multi) and legacy single <code>attachmentPath</code>. Non-crash tickets continue to send as single-part HTML.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Ready-to-test email overhaul (PT-53)',
        items: [
            { badge: 'improve', text: '<strong>Subject lines now describe the ticket.</strong> Instead of <em>"Feedback &middot; Improvement &middot; PT-X ready to test"</em> you get <em>"Feedback &middot; PT-X &middot; Improvement &middot; \u201cinclude prod URL in ready-to-test email\u201d \u2014 ready to test"</em>. Picks the first 60 chars of the ticket text, snipped at a word boundary.' },
            { badge: 'improve', text: '<strong>Prod URL + "How to test" block</strong> now appears in every non-crash resolve email. Spells out the steps: open the toolkit, try the change, reply if anything\'s off. Saves the requestor a round trip back to ask where to test.' },
            { badge: 'improve', text: '<strong>OoM crash-recovery tickets are now re-routed to the actual upload user</strong>, not the plmadmin auto-filer. The body parses <code>User: Name (employeeId)</code> from the crash-filing template and LDAP-looks-up that user\'s email. Falls back to the original reporter on LDAP miss.' },
            { badge: 'improve', text: '<strong>Audience-aware body templates.</strong> Admins (members of <code>pdl-plm-admin</code>) still get the full template with effort comparison and admin notes. Non-admins on regular tickets get just the request echo + how-to-test walkthrough \u2014 no implementation details. Crash-recovery recipients get a minimal "fix is in, re-use your file" body regardless of role.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>FeedbackResolveEmailService.sendReadyToTest</code> refactored. New helpers: <code>extractCrashUser()</code> with regex <code>^User:\\s+([^()\\n]+?)\\s*\\((\\d+)\\)\\s*$</code>, <code>isUserInPlmAdmin()</code> backed by <code>LdapAuthService.listAdminUsernames()</code> (4 hr cache), <code>summariseForSubject()</code>, <code>renderHowToTest()</code>, <code>renderTestLink()</code>. New config: <code>app.prod.url</code> (defaults to the prod host). LDAP miss on the admin-membership check fails closed (renders the non-admin body) so a transient LDAP hiccup never leaks admin-only detail.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Share Feedback accepts any file type',
        items: [
            { badge: 'improve', text: 'The <strong>Share Feedback</strong> dialog\u2019s attachment picker no longer restricts file types. You can now attach <code>.eml</code> emails, Word docs, PowerPoints, zips \u2014 whatever helps describe the issue. The 25 MB total cap is unchanged.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'BOM Implode now handles .xls (binary) uploads \u2014 PT-46 crash fix',
        items: [
            { badge: 'fix', text: '<strong>BOM Where-Used enrichment</strong> no longer crashes when you upload an old-style <code>.xls</code> file (HSSF binary). Jimmy\u2019s 138-row Asic_PNs.xls had been auto-filing crash-recovery tickets because the parser was hard-wired to <code>.xlsx</code> only. Now both formats work and the output preserves whatever format you sent in.' },
            { badge: 'improve', text: 'A single die that rolls up into hundreds of top-level assemblies could blow past Excel\u2019s 32,767-character per-cell limit on the new <em>Top-Level Parent(s)</em> column. Now we list as many parents as fit, then append a visible <em>(+N more)</em> marker so the truncation is honest instead of crashing the export.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>BomImplodeEnrichService</code> switched from <code>new XSSFWorkbook(is)</code> to <code>WorkbookFactory.create(is)</code> (same path <code>FileItemParser</code> already used for the parse step). Added <code>BomController.isOle2()</code> which sniffs the first 8 bytes (D0 CF 11 E0 A1 B1 1A E1) to decide the response content-type + filename extension \u2014 filename-based detection is unreliable when users rename. New <code>joinParents()</code> helper enforces a 32,700-char budget on the joined parent list with a <code>(+N more)</code> tail.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Change History reorg + new Audit Trail sub-tab (PT-50 \u00b7 ECN-135744-PROJ)',
        items: [
            { badge: 'new', text: '<strong>Change History is now a parent tab</strong> with three sub-tabs: <em>Revision History</em> (the old Change History content), <em>Field Changes</em> (moved here from Items), and <em>Audit Trail</em> (new). Same sub-tab nav appears at the top of all three panels so you can pivot without leaving the Change History area.' },
            { badge: 'new', text: '<strong>New Audit Trail sub-tab.</strong> Query the live <code>AGILE.CHANGE_HISTORY</code> table with filters: item number(s), change number(s), date range, username(s), action (status transition), and substring matches on details / comments. Resolved workflow status names via <code>NODETABLE</code> joins (e.g. <em>Pending \u2192 Review</em>), not raw IDs. Results table renders inline; ordered newest-first; capped at 10,000 rows.' },
            { badge: 'new', text: '<strong>Per-column filters on the Audit Trail results table.</strong> Each column header has its own filter input \u2014 type to narrow the visible rows on the fly (substring, case-insensitive, no re-query). The row counter updates to show "N of M (K hidden by column filters)" so you can see how much the local filter is hiding.' },
            { badge: 'new', text: '<strong>Excel export from Audit Trail.</strong> Green "Export to Excel" button next to the row counter ships the full server-side query (not the locally-filtered subset \u2014 "exports everything" per the ECN). Header row is frozen and AutoFilter is enabled so the workbook is sort/filter-ready on open. Per-cell text is auto-truncated at Excel\'s 32,767-char limit so a serialized history payload can\'t break the export.' },
            { badge: 'improve', text: '<strong>Items tab</strong> now has three sub-tabs (Part Extract, Agile Lookup, SKU Lookup) \u2014 Field Changes moved out to Change History per the ECN. Clicking the Items header now defaults to Part Extract.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>AuditTrailService</code> + <code>AuditTrailController</code> (POST <code>/api/audit-trail/query</code> + <code>/export</code>). Hard guardrails: 1000-ID cap on item/change lists; date range required (max 1 year) when no item/change supplied. Item filter routes through <code>AGILE.REV</code> to find changes that touched the listed items. Export uses SXSSF streaming so it handles 10K rows without OOM on the JVM. All three deliverables in the ECN spec landed in one round.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Feedback Queue \u2014 attach files in the answer modal, process diagram, real names on orphans',
        items: [
            { badge: 'new', text: 'The <strong>Have Questions</strong> popup now lets you attach files alongside your written answers \u2014 specs, mockups, screenshots, anything that helps the AI understand the request. Multi-file picker; files upload in the same request as the answers. The next poll cycle re-triages with everything in scope.' },
            { badge: 'new', admin: true, text: '<strong>"How feedback flows" diagram</strong> at the top of the Feedback Queue tab (collapsible). Shows the path from <em>open \u2192 triaging \u2192 awaiting approval \u2192 in progress \u2192 done</em>, with the branches to <em>dismissed</em> and <em>have questions</em>. Same colored pills as the cards so the visual vocabulary matches.' },
            { badge: 'improve', text: 'Auto-filed crash-recovery tickets now show the actual person whose upload caused the failure (e.g. <em>"User: Jimmy Sessumes (1359)"</em> instead of just <em>"User: 1359"</em>). Existing orphan tickets are retro-fitted on startup via an LDAP lookup; new ones get the resolved name at file time.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>POST /api/feedback/queue/{ptId}/answer</code> now accepts <code>multipart/form-data</code> (answers as a JSON string + zero-or-more <code>files</code>). The JSON shape still works for legacy callers. <code>UploadQuarantineService.fileBugTicket</code> resolves <code>employeeID \u2192 displayName</code> via <code>LdapAuthService</code>. <code>FeedbackQueueService.backfillOrphanUserNames()</code> runs once on startup and rewrites the <code>User: &lt;id&gt;</code> line on every existing <em>[Auto-filed crash recovery]</em> ticket \u2014 idempotent (regex matches only the raw-numeric shape).'},
            { badge: 'fix', admin: true, text: 'Polish round on the queue UI: the <em>How feedback flows</em> diagram blurb was rendering <code>\\u2019</code> literally (raw JS escapes inside HTML body text) \u2014 now uses HTML entities. Also the <strong>Approve &amp; start</strong> button on <em>have_questions</em> tickets is now disabled until every question has a non-empty answer, so admins can\u2019t accidentally green-light work the AI flagged as ambiguous.' },
            { badge: 'new', text: '<strong>Estimated vs actual time on every closed ticket.</strong> Done cards now show a small \u23F1 pill with the AI\u2019s original effort estimate next to the actual elapsed time the agent took to ship the fix. The ready-to-test email includes the same comparison so the requestor (and the admin CC) can see whether the estimate held up. Color-coded: green when we hit or beat it, amber if within 50% over, red if we blew past. Older closed tickets without a recorded pickup time just won\u2019t show the pill \u2014 fresh closes from this build onward will.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>FeedbackItem.agentPolledAt</code> and <code>actualHours</code> fields. New admin endpoint <code>POST /api/feedback/queue/{ptId}/agent-pickup</code> (idempotent, first call wins) anchors the timer when the agent\u2019s poll cycle actually picks the work up \u2014 NOT when admin approves, because approval can sit unworked for hours. <code>actualHours</code> is computed and frozen at the moment the ticket transitions to done: <code>agentBuildAt \u2212 agentPolledAt</code>, falling back to <code>resolvedAt</code> for manual closes. The /poll-feedback skill is updated to POST /agent-pickup before any code edits.' }
        ]
    },
    {
        date: 'May 13, 2026',
        title: 'Feedback Queue \u2014 AI triage, admin sign-off, auto-resolve on deploy',
        items: [
            { badge: 'new', text: 'Every new feedback ticket now passes through an AI triage step before reaching the action queue. The AI tags each item <strong>Easy</strong> (green pill with a one-line approach), <strong>Hard</strong> (amber pill with a complexity reason + rough effort estimate), or <strong>Have Questions</strong> (blue pill the requestor can click to answer). Gibberish submissions are auto-dismissed without bothering anyone. Admins click <strong>Approve &amp; start</strong> on tagged items to green-light the agent\u2019s development work.' },
            { badge: 'new', text: '<strong>Auto-mark-done on deploy.</strong> When the agent finishes building a fix, it stamps the JAR\u2019s SHA-256 onto the ticket. After you run <code>deploy.bat</code>, the new JVM\u2019s built-in scheduled job (every 5 min) matches the running JAR\u2019s SHA against in-progress tickets and auto-resolves the matches \u2014 sending the same "ready to test" email the manual Mark Done button sends. One deploy can resolve N tickets at once. Manual Mark Done still works as a fallback.' },
            { badge: 'new', text: '<strong>Attach spec to a ticket after submission.</strong> New paperclip button on every active card lets the requestor or an admin upload additional context (specs, mockups, screenshots) without re-filing. Upload flips the ticket back to triaging so the AI re-assesses with the new attachment in scope.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new statuses <code>triaging</code> + <code>awaiting_approval</code> sit between <code>open</code> and <code>in_progress</code>. <code>FeedbackItem</code> gained <code>aiTag / aiAssessment / aiEffortHours / aiQuestions / aiAnswers / aiTriagedAt / agentBuildJarSha / agentBuildAt / agentStarted</code>. New <code>BuildInfoService</code> computes the running JAR\u2019s SHA-256 once at startup, exposed via <code>GET /api/admin/build-info</code>. <code>FeedbackQueueService.autoMarkDoneSweep()</code> is a <code>@Scheduled(cron = "0 */5 * * * *")</code> method that compares running SHA to every in-progress ticket\u2019s stamped SHA and auto-resolves matches. New endpoints: <code>POST /api/feedback/queue/{ptId}/{approve,triage,answer,attach,attach-build-sha}</code>. New frontend modal opens from the Have Questions pill and lets the requestor answer inline; status flips back to triaging on submit. Filter chips fold <code>triaging</code> and <code>awaiting_approval</code> into the <strong>Open</strong> tab so the existing UI shape stays.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'AD Bulk Lookup \u2014 Smart Fill reads your header row and fills AD-mapped columns',
        items: [
            { badge: 'new', text: 'New <strong>Smart Fill</strong> button on the AD Bulk Lookup utility (Utilities tab \u2192 AD Bulk Lookup). Upload an access-certification spreadsheet, click Smart Fill, get back the same file with every AD-mapped column filled in for every sheet. Headers we recognise: User ID, First/Last Name, Display Name, Email, Department, Job Title, Manager, Phone, Country, City, Office. People not found in AD get <em>Not found</em> in red italic.' },
            { badge: 'improve', text: 'Multi-sheet aware: every sheet in the workbook is processed independently. Un-mapped columns (e.g. Roles, BPO, Certify/Revoke) are left exactly as you sent them. The legacy "Lookup (legacy)" button still works the old way for plain CSV/TXT lists.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>POST /api/admin/ad-smart-fill</code> on <code>AdminController</code>. Detects the header row via the same heuristic shipped for Agile Lookup this morning (scan first 10 rows, score by non-empty + short-text + keyword hits). Each header is mapped to an AD field via <code>adFieldFor(String)</code> \u2014 a hardcoded synonym dictionary covering "first name"/"given name", "job title"/"title"/"position", "department"/"dept", etc. Looks up by <code>lookupUserById</code> then falls back to <code>lookupUserByUsername</code>. Per-user result cached in-request so the same key isn\u2019t hit twice across sheets. Fills only EMPTY cells (never overwrites). Output streams through the existing DownloadArchiveFilter so the file archive records the hash. Audited as <code>AD_SMART_FILL</code> with sheets / rows / found / notFound / cellsFilled counters.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Agile Lookup \u2014 smart header-row + part-number column detection (with disambiguation)',
        items: [
            { badge: 'new', text: 'The <strong>Agile Lookup</strong> tab now handles real-world Excel files \u2014 including the BOM-Implosion export from this toolkit itself, which has a title row + blank row above the actual headers. The companion service scans the first 10 rows to find which one looks like a header, then scores every column to pick the part-number column (e.g. <em>Component</em> in a BOM file, not <em>Level</em> or quantity).' },
            { badge: 'new', text: 'When two or more columns look equally like part numbers (e.g. <em>Parent</em> AND <em>Component</em>), a small dialog now asks you which one to use, with three sample values from each column so you can tell them apart. Pick one and the lookup re-runs with that column \u2014 no need to re-upload the file or edit the Excel.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: in <code>plm-agile-service</code>, new <code>findHeaderRow(Sheet)</code> + <code>rankItemColumnCandidates(Row, Sheet, int)</code> helpers. Header scoring blends non-empty count, short-text count, keyword hits (description / item / component / etc.), and "row below has at least as many cells" check. Column scoring blends header-name match (<em>component</em> / <em>part number</em> = strong, <em>item</em> / <em>parent</em> = medium) with value-shape match (alphanumeric, 4\u201330 chars, has both letters and digits). Ambiguity rule: top &lt; 100 OR (runner-up \u2265 80 AND lead &lt; 40) \u2192 return <code>{needsColumnPick: true, candidates: [...]}</code> instead of guessing. Toolkit\u2019s <code>AgileLookupService.forwardLookup()</code> now takes an optional <code>itemColumn</code> param; <code>AgileLookupController.upload()</code> accepts it and passes through. Frontend <code>doAgileExcelUpload()</code> extracted from <code>doAgileLookup()</code> so the same file can be re-submitted with the chosen column after the user picks.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Admin \u2014 File Archive: every upload kept 30 days, every download fingerprinted',
        items: [
            { badge: 'new', admin: true, text: 'New <strong>Admin \u2192 File Archive</strong> menu opens a viewer for every file that crosses the toolkit\u2019s user boundary. Uploads (Agile Lookup, BOM Compare, SKU Lookup, Part Extract, Feedback attachments, etc.) are kept on disk for <strong>30 days</strong> so admins can replay a failed lookup without bothering the user. Downloads (every Excel export, ECN/Volume/Team report) are recorded <strong>metadata-only</strong> \u2014 filename, size, SHA-256, user, route \u2014 kept <strong>90 days</strong>. Index rows survive longer than bytes so we can still identify what was sent even after the bytes are purged.' },
            { badge: 'improve', text: '<strong>Use case:</strong> &ldquo;Noraida\u2019s Agile Lookup errored on a file&rdquo; \u2192 we can now download her exact xlsx from the archive, reproduce locally, and triage \u2014 no &ldquo;please re-send the file&rdquo; round trip. Same for &ldquo;the report you generated for me yesterday is wrong&rdquo; \u2014 we can verify which exact bytes left the server via the SHA-256.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>FileArchiveService</code> writes uploads to <code>file-archive/uploads/YYYY/MM/DD/&lt;user&gt;/&lt;timestamp&gt;__&lt;id&gt;__&lt;name&gt;</code>, appends a JSON-lines index entry. Capture happens inside <code>UploadQuarantineService.quarantine()</code> so every controller that already used quarantine (= all of them) gets archiving for free. <code>SupportController.submitFeedback</code> wires the call manually (doesn\u2019t use quarantine). Downloads are intercepted by a new <code>DownloadArchiveFilter</code> servlet filter that tees the response output stream through a SHA-256 digest \u2014 records on every response whose Content-Disposition is &ldquo;attachment&rdquo;. Daily purge runs at 03:00 server time. Admin endpoints: <code>GET /api/admin/file-archive</code>, <code>GET /api/admin/file-archive/{id}</code>, <code>POST /api/admin/file-archive/{id}/pin</code>, <code>POST /api/admin/file-archive/purge</code>. All audited via <code>ADMIN_FILE_ARCHIVE_*</code> activity entries.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Admin \u2014 Server Logs viewer + Agile-service errors now self-explain',
        items: [
            { badge: 'new', admin: true, text: 'New <strong>Admin \u2192 Server Logs</strong> link opens an in-page viewer for every <code>.log</code> file in the server\u2019s logs directory \u2014 toolkit, agile-service, watchdog, deploy. Tail any file by N lines (capped at 5000), grep with a keyword and optional &plusmn;context, and jump to the bottom automatically so the most recent lines are visible. No more RDP / SMB mount needed to triage a prod incident.' },
            { badge: 'fix', admin: true, text: 'When the companion <code>plm-agile-service</code> returns an HTTP 500 (e.g. SDK call failed mid-lookup), the toolkit was throwing <code>Agile service returned HTTP 500</code> with no detail \u2014 the response body containing the actual SDK error was being read and then discarded. Now the body is appended to the exception (truncated to 300 chars) so users see what really failed. Fixed in both <code>AgileLookupService</code> (Agile Lookup tab) and <code>DataCompareService</code> (Data Compare tab).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>AdminLogsController</code> with <code>GET /api/admin/logs</code> (list) and <code>GET /api/admin/logs/{name}?lines=N&amp;q=keyword&amp;context=K</code> (tail / grep). Path-traversal protected (resolved path must equal the configured logs dir; whitelist is "any <code>*.log</code> file currently in that dir"). Tail uses <code>RandomAccessFile</code> with a 220 bytes/line heuristic to seek near the end \u2014 reads at most ~1 MB even from the 87 MB <code>watchdog.log</code>. Grep streams via <code>Files.lines()</code> with a sliding pre-context window. Every view is recorded in the activity log as <code>ADMIN_LOG_VIEW</code> with file name + line count.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Volume Report \u2014 Manager dropdown no longer shows duplicate names',
        items: [
            { badge: 'fix', text: 'On <strong>ECN Report \u2192 Volume Reports</strong>, the Manager dropdown was showing several people twice (Afrozuddin Muhammed, Henry Nghiem, Jimmy Sessumes, Krati Jain, etc.) \u2014 same person, identical employee ID. The dropdown now dedupes by username on the way out, so each manager appears exactly once.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>VolumeReportController.listManagers()</code> now skips repeats via a <code>HashSet&lt;String&gt;</code> of seen usernames before building the response list. Root cause is upstream \u2014 <code>LdapAuthService.listAllGroupMembers()</code> walks nested AD groups and yields one entry per membership path, so people in multiple sub-groups appear more than once. Fix kept in the controller so we don\u2019t poke the auth/LDAP layer; TODO comment in code points at the LDAP-layer follow-up for any future cleanup.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Feedback form \u2014 gibberish gate extended to the public Raise Issue path (PT-45)',
        items: [
            { badge: 'fix', text: 'PT-45 (<code>bcfkjdfbkdbkdbkd</code>) reached the queue via the standard <strong>Feedback form</strong> earlier today \u2014 the deterministic gibberish gate from this morning only protected the AI Eval bug-report path. Same gate now also fires on <code>POST /api/support/feedback</code>: keyboard-mashing is rejected at submit with an inline message ("That looks like random characters\u2026") and the form stays open with the user\u2019s text intact so they can edit and retry. Non-Latin scripts (CJK, Hindi, Arabic, Hebrew, etc.) are exempt \u2014 the heuristic only looks at ASCII a\u2013z letters now, so users writing in their own script never hit the gate.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: extracted the gibberish detector from <code>AiEvalController</code> into a shared <code>util/GibberishHeuristic.java</code> (vowel-ratio + consonant-run + token-level vowel check; tweaked to only collect ASCII a\u2013z so non-Latin scripts are skipped). Applied in <code>SupportController.submitFeedback</code> (public endpoint only) BEFORE the queue allocation \u2014 internal callers like AI Eval auto-file and crash-recovery orphans go through <code>submitFeedbackInternal</code> directly and bypass the gate. Frontend <code>submitFeedback()</code> in <code>app.js</code> no longer closes the dialog before checking <code>data.success</code> \u2014 on failure the form stays populated.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'ECN Report \u2014 "Pending" removed from the Avg Workflow by Status panel (PT-44)',
        items: [
            { badge: 'improve', text: 'The <em>Avg Workflow by Status</em> panel in the ECN Report no longer shows the <strong>Pending</strong> row \u2014 across all four sections (General, PDR, Dedicated, Overall). Per-row data in the table still keeps the <code>D@Pend</code> column for anyone debugging an individual ECN.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: dropped <code>{key:\'daysAtPending\', label:\'Pending\'}</code> from the <code>statuses</code> array in <code>ecnComputeCycleByStatusPanel()</code> in <code>ecnreport.js</code>. The panel is called four times (cbsGeneral / cbsPdr / cbsDedicated / cycleByStatus) so a single-line removal nukes Pending from every aggregate at once.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Voice input \u2014 hotfix: bars no longer starve the speech engine',
        items: [
            { badge: 'fix', text: 'The audio-bar feature from earlier today opened a parallel <code>getUserMedia</code> stream alongside SpeechRecognition, which on some Chrome / hardware combos caused Chrome to serialise mic access and starve the speech engine of audio \u2014 the bars stayed flat AND nothing got transcribed. The bars are now driven entirely by SpeechRecognition\u2019s own <code>onsoundstart</code> / <code>onresult</code> / <code>onspeechstart</code> events; no parallel mic stream is opened. Bars now indicate "speech engine is detecting sound" rather than raw audio level \u2014 which is the signal you actually want anyway.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>startMeter()</code> rewritten in <code>mic.js</code>. Removed the <code>navigator.mediaDevices.getUserMedia</code> call, the <code>AudioContext</code>, and the <code>AnalyserNode</code>. The new driver maintains a <code>lastActivityMs</code> timestamp, bumped on every recognition event; a <code>requestAnimationFrame</code> tick loop animates each bar with a phase-offset sine wave at "high" level when recent activity (\u2264 250ms), tapers toward idle over 1.25s, then flattens. Bars get individual phase offsets so they don\u2019t move in lockstep.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Voice input \u2014 live audio bars + auto-submit on a second mic click',
        items: [
            { badge: 'new', text: 'When you press the microphone button on <strong>Help &amp; Support</strong> or <strong>Ask AI</strong>, a small 5-bar level meter now appears next to the "Listening\u2026" label and dances with your voice \u2014 so you can SEE the mic is actually picking you up. If the meter stays flat, the mic isn\u2019t getting audio.' },
            { badge: 'improve', text: 'Press the mic a second time to stop. If what you said is usable (4+ characters, has letters, not pure repetition), it now <strong>auto-submits</strong> \u2014 no extra click on "Ask". If it doesn\u2019t look usable, the text stays in the box for you to edit.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>mic.js</code> now opens a parallel <code>getUserMedia({audio:true})</code> stream when recognition starts, pipes it through a Web Audio <code>AnalyserNode</code>, and animates 5 spans via <code>requestAnimationFrame</code> sampling 5 log-spaced FFT bands. Torn down in <code>onend</code> alongside the recognition handle. <code>micToggle()</code> takes an optional third arg <code>submitBtnSelector</code> \u2014 when provided, <code>onend</code> calls <code>transcriptLooksUsable()</code> (length \u2265 4, has letters, no pure repetition) and clicks the button via <code>setTimeout(.., 0)</code> so any focus cascade settles first. Backward compatible: existing 2-arg call sites keep today\u2019s no-auto-submit behavior. Wired in <code>help-sidebar.js</code> (\u2192 <code>#helpAskBtn</code>) and <code>index.html</code> Ask AI tab (\u2192 <code>#askBtn</code>). Also added a "network" speech error message for cases where the gateway to Google\u2019s speech service is firewalled.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Team Report \u2014 transient "Unexpected end of file" errors now retried automatically',
        items: [
            { badge: 'fix', text: 'Last night Noraida ran <strong>Volume Tab \u2192 Generate Team Report</strong> and every team\u2019s <em>Top Themes</em> cell rendered <code>(AI error: Unexpected end of file from server)</code> at 01:17 AM \u2014 a brief Portkey/Azure-AI gateway hiccup. The whole report had to be re-run by hand. The toolkit now retries automatically: one retry inside the Portkey client for transient transport errors (EOF, broken sockets, read timeouts), plus a second per-team retry in the Team Report loop with a 2-second pause. If both fail, the cell shows a friendlier <em>"AI temporarily unavailable for this team \u2014 re-run the report"</em> instead of leaking the raw exception text. Rate-limit (429) errors are NOT retried \u2014 retrying those makes things worse.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>PortkeyClient.chatWithHistory</code> now classifies exceptions via <code>isTransientTransportError()</code> (matches <code>EOFException</code>, <code>SocketException</code>, <code>SocketTimeoutException</code>, plus <code>IOException</code> messages containing "unexpected end of file" / "connection reset" / "premature" / "broken pipe"). 1.5s pause between the two attempts; each attempt opens a fresh <code>HttpURLConnection</code> because Java\u2019s HUC is single-use after a transport failure. <code>TeamReportController.aiSummariseTeam</code> wraps the call with its own 2-iteration loop and 2s pause \u2014 covers cases where the gateway returned a parseable-but-wrong response that the client wouldn\u2019t retry on. Activity log unchanged; PORTKEY log lines now include "transient error on attempt N, retrying once" when the retry path fires.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Ask AI \u2014 gibberish gate now deterministic (no longer leaks through when AI is rate-limited)',
        items: [
            { badge: 'fix', text: 'Earlier today the new "What did you expect?" validator was letting some gibberish through, e.g. <code>vhjdfbk df,vndsf</code>, when the upstream AI gateway hit its per-minute rate cap. The validator was correctly failing-open on rate-limit errors, but that defeated the whole point for obvious junk. A deterministic vowel-ratio + consonant-run heuristic now runs <strong>before</strong> the AI call \u2014 obvious keyboard-mashing is always rejected, even when the AI is unavailable. The AI is still consulted for borderline cases (e.g. "this is wrong"), where fail-open is acceptable.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>looksLikeGibberish()</code> static helper in <code>AiEvalController</code>. Strips non-letters, then rejects if (a) overall vowel ratio &lt; 15%, (b) any consonant run \u2265 6 chars, or (c) more than half of 4+ letter tokens have no vowel. Runs synchronously before the <code>PortkeyClient.chat()</code> call. Activity-log entry tagged <code>gate=gibberish-heuristic</code> so we can distinguish heuristic vs. AI rejections.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Ask AI \u2014 gibberish "what did you expect?" now rejected before becoming a Bug Report',
        items: [
            { badge: 'fix', text: 'When you grade an Ask AI answer C / D / F, the <em>"What did you expect?"</em> dialog now runs a quick AI check on your text. Gibberish ("gibebjvpdfslvnksfdbkfsb") or content-free entries ("this is wrong", "bad", "no") are rejected with an inline prompt to rewrite, so the PLM admin triage queue only sees actionable feedback. <strong>Cancel</strong> still saves the grade without filing a bug. If the validator itself errors out, your input is accepted (fail-open) so a broken check never blocks a real bug report.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>POST /api/ai-eval/ask/validate-expectation</code> on <code>AiEvalController</code>. Sends the question, AI answer (HTML-stripped, truncated to 1000 chars) and the expectation (truncated to 2000) to Haiku via <code>PortkeyClient.chat()</code>, which returns <code>{valid, reason}</code> JSON. Best-effort JSON parsing \u2014 any parse/LLM/network error falls back to <code>valid:true</code>. Frontend: <code>askExpectationClose()</code> in <code>ask-ai.js</code> now POSTs to the validator on <em>Save expectation</em>, disables the button to <em>Checking\u2026</em>, and shows <code>reason</code> in a red <code>askExpectationError</code> div below the textarea on rejection. Activity-log event: <code>AI_EVAL_EXPECTATION_VALIDATE</code> with outcome + length.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Help chatbot \u2014 admin "what was X upto?" routes to activity log (no more LLM hallucination)',
        items: [
            { badge: 'fix', text: 'Asking the help chatbot <em>"what was Vikas Singh upto?"</em> as an admin used to fall through to the LLM, which would respond with a confident hallucination like <em>"I don\u2019t have real-time access to the live server log from this chat session"</em> \u2014 even though the bot does have full access to the activity log and answers the literal phrasing <em>"what did Vikas Singh do today?"</em> correctly. The activity-log intercept now catches casual phrasings: <code>upto</code>, <code>up to</code>, <code>working on</code>, <code>been on</code>, <code>been at</code>.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: added the five phrasings above to <code>ACTIVITY_KEYWORDS</code> in <code>AiHelpController.java</code>. The activity intercept already had user-name matching, time-window parsing, and report rendering \u2014 it just wasn\u2019t being entered for these phrasings. The deterministic <code>tryAdminActivityQuery</code> path now wins before <code>portkeyClient.chat()</code> is called for these.' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Help sidebar \u2014 chat history survives tab navigation',
        items: [
            { badge: 'improve', text: 'When you ask the <strong>Help</strong> chatbot a question and it says "go to the <em>Change History</em> tab", clicking that tab no longer wipes your conversation. Reopen the help panel after navigating and your previous Q&amp;A is still there \u2014 ask a follow-up and the AI picks up where you left off, now aware of which tab you\u2019re on. A small "Now on: &lt;Tab Name&gt;" note is added so you can see the context shift.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>toggleHelpSidebar()</code> in <code>help-sidebar.js</code> no longer clears <code>helpAiChatHistory</code> or resets <code>helpChatArea.innerHTML</code> on tab change. Backend <code>/api/help/ask</code> already received the new <code>currentTab</code> with every request \u2014 the per-turn context was always correct; only the client was throwing away the conversation. The click-outside-closes behaviour is preserved (X button still works as before).' }
        ]
    },
    {
        date: 'May 12, 2026',
        title: 'Team Report AI \u2014 accept any 2xx response from Portkey gateway',
        items: [
            { badge: 'fix', text: '<strong>Volume Report &rarr; Generate Team Report</strong> occasionally rendered the raw AI envelope JSON ("Portkey/Vertex returned 246: {\u2026}") in the <em>Top Themes</em> cell for one team while other teams in the same run rendered correctly. The Portkey/Azure-AI gateway sometimes returns a non-standard 2xx status code (e.g. 246) alongside a fully valid chat-completion body \u2014 the client was treating any status \u2260 200 as an error and dumping the body into the cell. Now any 2xx is accepted as success.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>PortkeyClient.chatWithHistory</code> changed status check from <code>status != 200</code> to <code>status &lt; 200 || status &gt;= 300</code>. The parser already handled the OpenAI-compatible response shape correctly; the 246-on-success quirk was the only thing tripping the chat call.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Feedback \u2014 attach multiple files (PT-38)',
        items: [
            { badge: 'new', text: 'The <strong>Feedback form</strong> now accepts <strong>multiple file attachments</strong> in one submission. Pick several screenshots/PDFs at once; each one gets a chip below the file picker so you can see what you\u2019re sending. Cap is <strong>25 MB total</strong> across all selected files (the mail relay\u2019s inbound limit).' },
            { badge: 'improve', text: 'In the Feedback Queue, each multi-attach item now shows one chip per attachment. Single-attachment items still work exactly as before.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>FeedbackItem</code> gained <code>attachmentPaths</code> + <code>attachmentFilenames</code> lists; the legacy <code>attachmentPath</code>/<code>attachmentFilename</code> fields are kept and mirror the first file for back-compat. <code>FeedbackQueueService.addItem</code> overload takes <code>List&lt;MultipartFile&gt;</code> and dedups filename collisions with a <code>-N</code> suffix. <code>SupportController.submitFeedback</code> accepts both <code>attachments</code> (new) and <code>attachment</code> (legacy single, still used by AI Eval auto-file). Email loops all N files into the multipart body. <code>FeedbackQueueController</code>\u2019s attachment endpoint takes an optional <code>index</code> query param to fetch non-first files.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Change History \u2014 filter on the Item Number column',
        items: [
            { badge: 'improve', text: 'The <strong>Item Number</strong> column on the Change History results table now has its own filter input in the filter row (was the only filterable column without one). Helpful when you queried several PNs at once and want to narrow to changes for just one of them. The existing X clear-all link is now a small grey \u2715 inside the same cell.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'BOM Compare \u2014 tighter UI: one "Load Revs" button, working swap, dedup labels',
        items: [
            { badge: 'improve', text: '<strong>Different-parts mode</strong> now has a single <em>Load Revs</em> button that loads revisions for both sides in parallel (was two buttons, one per side).' },
            { badge: 'new', text: '<strong>Swap (\u2B0C) button</strong> on both same-part and different-parts modes \u2014 swaps left \u2194 right inputs and rev selections in one click, then auto-re-fires the comparison if a result is already on screen.' },
            { badge: 'fix', text: '<strong>Swap actually preserves your rev selection now.</strong> The first cut of the swap was rebuilding the rev dropdowns from cache, which silently reset the selected option to the default. Now we swap the dropdowns\u2019 <code>innerHTML</code> + <code>value</code> directly, so picking "rev 3" on the left and "rev (4)" on the right and then clicking swap correctly lands rev (4) on the left and rev 3 on the right.' },
            { badge: 'improve', text: '<strong>Compare result header no longer repeats itself.</strong> When both sides share the same Part, Description, or Lifecycle, those labels appear <strong>once</strong> in the summary strip instead of being duplicated per-side under each rev banner. Different values still get the A/B split with red highlighting. Per-side dark banner now shows just the rev label (e.g. "Rev 3 \u2014 ECO-132883-A") when comparing same part.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'BOM Compare \u2014 pending-ECO redline removes now correctly excluded (PT-37 fix)',
        items: [
            { badge: 'fix', text: '<strong>BOM &rarr; Compare</strong> against a pending rev (shown in parentheses) used to show rows redlined-for-removal on <strong>both sides</strong> as a MATCH. Now they correctly show as REMOVED on the previous-rev side only \u2014 matching what you see in Agile\u2019s redline view. Tested against <code>SDFPNVL-1T00-1006</code> rev 3 vs (4) / ECO-135471-A.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: added a <code>NOT EXISTS</code> filter in <code>RevCompareService.scoped_bom</code> branch (3) (unreleased ECOs). Agile records a pending BOM redline-remove by inserting a new row with <code>CHANGE_IN = &lt;pending ECO&gt;</code> AND <code>PRIOR_BOM = &lt;id of row being retired&gt;</code>. The retired row\u2019s <code>CHANGE_OUT</code> only flips at release time, so during the pending window the only signal is the new row\u2019s <code>PRIOR_BOM</code> link. Schema discovery + verification by a DB MCP agent; details in <code>docs/handoffs/2026-05-11-pt37-agile-redline-schema.md</code>. The temporary caveat banner is removed; <code>_diag*</code> diagnostic fields remain in the response one cycle in case edge cases surface.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'BOM Compare \u2014 description shown under the rev header',
        items: [
            { badge: 'improve', text: 'On <strong>BOM &rarr; Compare</strong> (rev-vs-rev mode), the dark header bar above each side now shows the <strong>parent BOM description</strong> right under the rev label, with the lifecycle phase on the line below. Previously you had to look at the small "A Desc:" / "B Desc:" caption line above the table; now the description sits with its rev so it\u2019s obvious at a glance which BOM is which.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'BOM Where-Used enrich \u2014 row cap + size guard to prevent OOMs',
        items: [
            { badge: 'fix', text: '<strong>BOM Where-Used enrichment</strong> on a 72 MB / 825K-row workbook brought the system down on a recent attempt. The size-based heap guard estimated 864 MB of need (12\u00d7 file size) which passed under the 6 GB heap, but POI XSSF DOM mode actually needs 2-3 GB of memory for that many cells. Added a row-count cap (25K for enrich, 200K for query) that the probe layer enforces before the upload even starts \u2014 oversized files now get a clear "this file has 825,131 rows, but enrichment is capped at 25,000 rows" message instead of a JVM crash.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>HeapGuard.Op</code> now carries a <code>maxRows</code> per op (PROBE=&infin;, QUERY=200K, ENRICH=25K). <code>UploadProbeController.probe</code> checks <code>StreamingExcelProbe</code>\u2019s <code>totalRows</code> against the cap after the size check and returns <code>tooLarge:true</code> when over. <code>BomController.enrichImplode</code> now re-runs <code>HeapGuard.check</code> server-side and catches a new <code>BomImplodeEnrichService.RowCapExceededException</code> to return HTTP 413 with a plain-text body. Same shape as the PT-29 parts-enrich fix.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Lifecycle Journey \u2014 one outer collapse, per-SKU sections always expanded',
        items: [
            { badge: 'improve', text: '<strong>Lifecycle Journey</strong> on Change History used to render one collapsible card per SKU \u2014 if you queried 10 SKUs you saw 10 collapsed boxes and had to click each to read it. Now there\u2019s a <strong>single parent header</strong> (<em>Lifecycle Journey (N items)</em>) at the top; click once and every SKU\u2019s journey shows up fully expanded inside. Default is still collapsed so the change-history table is the first thing you see on the page.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Sessions \u2014 longer timeout + heartbeat so active users don\u2019t get kicked out',
        items: [
            { badge: 'fix', text: '<strong>Session timeout bumped from 30 min to 2 h</strong>, AND the page now sends a quiet heartbeat to <code>/api/auth/session</code> every 5 minutes while the tab is visible. End result: as long as your tab is open and visible, your session stays warm \u2014 no more being kicked out mid-workflow because you were reading the screen for 30 minutes without clicking anything. Hidden background tabs don\u2019t heartbeat, so a tab you walked away from will still eventually time out (after 2 h) \u2014 that\u2019s by design so abandoned sessions don\u2019t pile up.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>server.servlet.session.timeout=2h</code> in <code>application.properties</code>; new heartbeat in <code>app.js</code> uses the Page Visibility API to pause when the tab is hidden, also re-pings on <code>visibilitychange</code>/<code>focus</code> so an alt-tabbed user refreshes immediately instead of waiting up to 5 min. If the heartbeat finds the session already gone (e.g. JAR was restarted), it shows the same "Session Expired" modal as the existing 401 path.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'SKU Lookup \u2014 dedup repeated input items',
        items: [
            { badge: 'fix', text: '<strong>SKU Lookup</strong> was producing duplicate rows when you typed the same item more than once in the input box (e.g. <code>ABC,ABC,DEF</code> returned ABC twice). The manual <code>/api/sku-data/search</code> path now dedups inputs via a <code>LinkedHashSet</code> before querying \u2014 same behavior as the file-upload path that was already deduping via <code>parsed.distinctItems()</code>.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Feedback form \u2014 require explicit Category selection',
        items: [
            { badge: 'improve', text: '<strong>Feedback form</strong> no longer defaults to <em>Feature Request</em>. The Category dropdown now starts at "Select a category\u2026" and the Submit button refuses to send until you pick one. After a successful submit, the dropdown resets to the prompt so the next submission also has to be explicit. Prevents accidental mis-categorization that made the queue harder to triage.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Part Extract enrich \u2014 stronger heap guard + row cap to prevent OOMs',
        items: [
            { badge: 'fix', text: '<strong>Part Extract enrichment</strong> on large files (3K+ items, wide column selection) was bringing the JVM close to its heap limit. The pre-flight heap guard was estimating cost as 8\u00d7 file size, which was too optimistic for parts \u2014 the real XSSF DOM + cell-shift + query-result combo runs closer to 12\u00d7. Bumped the multiplier so the guard rejects with a friendly message before the upload runs.' },
            { badge: 'fix', text: '<strong>Added a hard row-count ceiling of 10,000 rows for enrichment.</strong> Even if the size-based check passes, a wide file pushes POI past safe bounds. You now get a clear "this file has N rows, enrichment is capped at 10,000" message instead of a request that silently hangs.' },
            { badge: 'improve', admin: true, text: '<strong>Server-side defense-in-depth</strong>: <code>POST /api/parts/enrich</code> now re-runs <code>HeapGuard.check</code> itself, so a curl/script caller that bypasses the JS probe still gets bounced. The activity log entry for an enrich now includes a heap-before \u2192 heap-after snapshot so a future OOM is diagnosable instead of "JVM disappeared".' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Chatbot routing \u2014 short keywords no longer hijack long sentences',
        items: [
            { badge: 'fix', text: '<strong>Asking the chatbot a Change History question</strong> would sometimes navigate you to the Part Extract tab and silently drop your question. The cause was a soft keyword recognizer that matched single-word keywords like <code>parts</code>, <code>history</code>, <code>sku</code>, <code>agile</code> anywhere in the input \u2014 so a sentence like "show me parts changed last week" hijacked to the Parts tab just because the word "parts" appeared. Bare single-word keywords now require the input to be \u22643 words; multi-word phrases like "change history" require \u22646 words. Long sentences fall through to the chatbot as intended.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Chatbot \u201CRaise Issue\u201D now lands in the Feedback Queue',
        items: [
            { badge: 'fix', text: '<strong>Issues raised via the help-sidebar chatbot</strong> now show up in the <strong>Feedback Queue</strong> alongside regular feedback submissions. Previously the chatbot\u2019s <em>Raise Issue</em> button only sent an email \u2014 the queue UI was blind to them, so they didn\u2019t get tracked or triaged the same way. Now each one is filed as a <em>Bug Report</em> with the screenshot attached, the originating tab + URL captured inline, and a <code>PT-####</code> id shared between the email, the queue entry, and the chatbot confirmation message.' },
            { badge: 'improve', text: 'The chatbot\u2019s success message now includes the tracking id (e.g. <em>Tracking id: <strong>PT-29</strong> \u2014 you (or an admin) can find it in the Feedback Queue</em>) so reporters can refer to a specific item later instead of describing it again.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Login \u2014 accept email address as username',
        items: [
            { badge: 'improve', text: 'You can now sign in with your <strong>work email address</strong> (e.g. <code>jane.smith@sandisk.com</code>) in addition to your AD <code>sAMAccountName</code> / employee number. Updated the login form placeholder and the "Trouble signing in?" help text so people know either is valid. The session still keys on <code>sAMAccountName</code> internally, so anything that already worked (saved searches, activity log, permissions) keeps working unchanged.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>LdapAuthService.authenticate</code> now detects an email/UPN input (presence of <code>@</code>) and (a) uses it directly as the AD bind principal instead of double-appending <code>@domain</code>, and (b) searches by <code>mail</code> / <code>userPrincipalName</code> instead of <code>sAMAccountName</code>. The emergency-admin matcher accepts <code>plmadmin@sandisk.com</code> too. The credential cache (offline fallback) now keys by raw input, sAM, and email so an offline login resolves whether you type any of the three forms.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Security \u2014 emergency-admin password rotated; never reuse the prior value',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Rotated the local emergency-admin (<code>plmadmin</code>) password.</strong> The prior value was inadvertently included in a test-readiness email and is considered burned. New cleartext lives only in the project owner\u2019s password manager and in Claude\u2019s private memory \u2014 it is no longer in git, chat, or any email. Added a top-level <em>Security: never share credentials in outbound channels</em> rule to <code>CLAUDE.md</code> so the same mistake doesn\u2019t happen again. Hash updated in <code>application.properties</code>.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'BOM Explorer \u2014 Pre-filters (Lifecycle, Part Type, Prefix, Max Top-Level Parents)',
        items: [
            { badge: 'new', text: '<strong>Pre-filters panel</strong> on BOM Explorer (collapsed by default, below the input box). Pick one or more <strong>Lifecycle</strong> values, <strong>Part Type</strong> values, and/or <strong>Part Prefix</strong> patterns; each dimension has an <em>Include</em> / <em>Exclude</em> toggle. Defaults to <em>Exclude</em>, so the common case (\u201Cdrop OBS\u201D) is one click \u2192 one selection. Filters apply to the <strong>outward</strong> node at each level \u2014 the <strong>child</strong> in Explode, the <strong>parent</strong> in Where Used.' },
            { badge: 'new', text: '<strong>Max Top-Level Parents per Input</strong> input (Where Used only). Caps the \u201CTop-Level Assemblies\u201D walk\u2019s output per input item so a component that rolls up to 5,000+ assemblies doesn\u2019t flood the report. <code>0</code> (or empty) means no limit \u2014 unchanged from before.' },
            { badge: 'improve', text: 'Applied filters surface as a chip in the status bar (e.g. <em>Filters: Lifecycle \u2260 OBS \u00b7 Max top-level: 50</em>) so reviewers can see what shaped the result. Filters also flow into the Excel export and \u201CEmail Me\u201D output so the recipient sees the same view.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>BomFilters</code> POJO + parse helper; <code>BomDataService.explodeMultiple/implodeMultiple/findTopLevelAssemblies</code> take an optional <code>BomFilters</code> arg. Row filtering happens in <code>walkAndEmit</code> (batched path) and post-walk (parallel + legacy CONNECT BY paths). Top-level cap is per-input, applied after row filters. New <code>/api/refdata/bom-filters</code> returns distinct <code>LIFECYCLE_PHASE</code> + <code>NEW_PART_CLASS</code> values from <code>item_extract</code>, cached 30 minutes per process.' }
        ]
    },
    {
        date: 'May 11, 2026',
        title: 'Single/Sole Source Report released to all PLM admins',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Single/Sole Source</strong> tab is now visible to every PLM admin (previously gated to the IT allowlist \u2014 Vikas Jindal + Krati only \u2014 during pre-release review). Server-side endpoints now check <code>isPlmAdmin</code> instead of <code>isItAdmin</code>.' },
            { badge: 'fix', admin: true, text: '<strong>Export activity log</strong> (User Management \u2192 30-day login traffic) was returning a 500 when any row in the date range had a <em>Details</em> field longer than 32,767 characters \u2014 typically AI_DATA_QUERY rows that capture a large filter JSON. Excel\u2019s per-cell text limit is 32,767, and POI throws <code>IllegalArgumentException</code> when you exceed it. We now clamp the <em>Details</em> cell to 32,754 chars and append <code>\u2026 [truncated]</code> so the export completes and the truncation is visible.' }
        ]
    },
    {
        date: 'May 10, 2026',
        title: '\uD83E\uDDEA Labs reorganization + BOM Race redesign',
        items: [
            { badge: 'new', admin: true, text: '<strong>Labs is now a top-level tab</strong> (previously hidden behind the Admin dropdown). It groups three admin tools as sub-tabs: <strong>BOM Race</strong>, <strong>AD Health</strong>, <strong>AI Eval</strong>. Non-admins still don\u2019t see the tab \u2014 there\u2019s a small "Admin" badge so admins remember why teammates can\u2019t see it.' },
            { badge: 'improve', admin: true, text: 'The <strong>Admin dropdown</strong> is now slim \u2014 only operational actions remain: <em>Maintenance</em> and <em>User Management</em>. Plugin Guide moved to the Help dropdown; AD Health and AI Eval moved into Labs.' },
            { badge: 'new', admin: true, text: '<strong>BOM Race redesigned as a live demo:</strong> hero framing cards above the controls explain each lane, preset chips for Quick / Standard / Stress, a real <strong>racetrack</strong> with moving runners (\uD83D\uDCBB Toolkit vs \u2699\uFE0F SDK), live ticker showing the current item, and a per-lane <em>items/s</em> speed pill.' },
            { badge: 'new', admin: true, text: 'When the race finishes, a <strong>dark celebration card</strong> reveals the speedup as a giant numeral (e.g. <em>12.4\u00d7 faster</em>) with a "what this means" line that scales the savings to a 500-item monthly report \u2014 e.g. <em>"saves ~83 minutes of wall time and ~500 SDK sessions."</em>' },
            { badge: 'new', admin: true, text: '<strong>Past races leaderboard</strong> below the scoreboard \u2014 last 5 completed runs (when, N, toolkit time, SDK time, speedup, by). Persisted to a new <code>bomrace_run</code> table that auto-creates on first boot.' },
            { badge: 'new', admin: true, text: '<strong>"Replay last" button</strong> next to Start \u2014 replays the previous race from a localStorage recording at 2\u00d7 speed without hitting the backend. Useful when the SDK is slow or the demo time-window is tight.' }
        ]
    },
    {
        date: 'May 10, 2026',
        title: '\uD83E\uDDEA Labs \u2014 BOM Race concept showcase (admin only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>New "Labs" tab</strong> with the first showcase: <strong>BOM Race</strong>. Picks 10 random items that have BOMs, races the toolkit\'s cached SQL explode against a live Agile SDK explode (via the existing <code>plm-agile-service</code>), and shows a side-by-side scoreboard with timings + set/structural match scores.' },
            { badge: 'new', admin: true, text: 'Race UI is split-lane (toolkit on the left, Agile SDK on the right) with a live race clock, per-item progress on the SDK side, and a final bar chart with a one-line callout (e.g. <em>"Set match 10/10 \u00b7 Structural match 9/10 \u00b7 12.4\u00d7 faster"</em>). Diff details expander reveals per-item SDK timings and any parts found by only one side.' },
            { badge: 'new', admin: true, text: '<strong>Download buttons</strong> on the scoreboard return two xlsx files: <em>Input items</em> (one sheet, the raced part numbers) and <em>Race results</em> (two sheets \u2014 Toolkit output and Agile SDK output). Useful for off-line diffing or sharing with engineering. Available for ~10 minutes after the race finishes (until the run\'s lazy TTL sweep).' },
            { badge: 'improve', admin: true, text: 'Per-item details table now shows a <strong>side-by-side row-count comparison</strong> (Toolkit rows vs SDK rows vs SDK ms). Toolkit lane is batched in a single SQL call, so its total time is reported once at the bottom rather than per item.' },
            { badge: 'new', admin: true, text: '<strong>Optional <code>year</code> filter</strong> in the random sample (POST <code>/api/bomrace/start</code> with <code>"year":2026</code>) restricts to assemblies created in that calendar year via <code>item_extract.CREATE_DATE</code>. Backwards compatible \u2014 omit <code>year</code> for the existing all-time random pick.' },
            { badge: 'improve', admin: true, text: '<strong>Row-count parity tooltip</strong> (small <code>\u24D8</code> next to each lane\u2019s row count) explains the dedup difference: toolkit\u2019s flat result repeats common sub-assemblies once per parent occurrence; SDK uses a per-input visited set that compresses repeats. Use the Set match indicator to confirm the actual parts agree.' },
            { badge: 'improve', admin: true, text: 'When <code>plm-agile-service</code> is unreachable (the local-Mac case), the race screen shows a clean "Agile service unavailable \u2014 race can\'t start" callout instead of half-starting and timing out.' }
        ]
    },
    {
        date: 'May 10, 2026',
        title: 'Where Used \u2014 Top-Level Assemblies sheet now shows the full Path (Input \u2192 \u2026 \u2192 Terminal)',
        items: [
            { badge: 'fix', text: '<strong>Path column populated on the "Top-Level Assemblies" sheet of the BOM Where-Used Excel export.</strong> The bulk batched walk used to emit each (input, terminal-parent) pair without reconstructing the chain in between, so column H was blank on every row. Now we track the chain through the level-by-level walk and write it back as <code>INPUT &gt; parent1 &gt; parent2 &gt; \u2026 &gt; terminal</code> \u2014 same format as the main results sheet.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>collectTerminals</code> in <code>BomDataService</code> now threads a per-call <code>List&lt;String&gt; chain</code> and a <code>Map&lt;terminal, pathStr&gt;</code>; <code>findTopLevelAssembliesBatched</code> writes the path onto each top-level <code>BomResult</code> via <code>setPath()</code>.' }
        ]
    },
    {
        date: 'May 10, 2026',
        title: 'Smart upload everywhere \u2014 item-column detection + Download enriched file on BOM, Part Extract, SKU',
        items: [
            { badge: 'new', text: '<strong>The Change History smart-upload experience now runs on every upload tab.</strong> BOM (Explode / Where Used), Part Extract, and SKU Lookup all read row 1, auto-detect the item-number column (header heuristic \u2192 AI fallback), and surface a chip in the status bar like <em>"Item column: B (Number) \u00b7 via header"</em>. If neither tier is confident, a column-picker modal opens with sample values per column so you can pick the right one explicitly.' },
            { badge: 'new', text: '<strong>"Download enriched file" button on Where Used, Part Extract, and SKU Lookup.</strong> Returns your <em>original</em> spreadsheet with new columns inserted right after the item-number column \u2014 Where Used adds <strong>Top-Level Parent(s)</strong>; Part Extract and SKU append whichever fields you currently have in the <em>Displayed Fields</em> dual-list. Mirrors the Change History pattern; original formatting and the other columns are preserved.' },
            { badge: 'new', text: '<strong>Dedup awareness.</strong> When you upload a file with the same item repeated across many rows (typical of a re-uploaded BOM-Implosion export), the chip shows <em>"47 unique items from 2,800 rows"</em> and we dedup before querying \u2014 no more wasted IN-list passes on duplicate inputs.' },
            { badge: 'new', text: '<strong>Heap-aware preflight.</strong> Before reading a large file, the JVM checks whether it has enough memory to safely process it. If not, you get a friendly message (\u201CThis file is ~72 MB and would need roughly 600 MB of server memory for enrichment, but only \u2026 is currently available. Please contact IT to increase the toolkit\u2019s memory, or split the file into smaller chunks.\u201D) instead of an OOM crash.' },
            { badge: 'improve', text: '<strong>Streaming column detection.</strong> Column probing now uses POI\u2019s SAX reader, not the full DOM. A 72 MB BOM-Implosion file probes in ~50 MB of heap instead of ~700 MB.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>HeapGuard</code> + <code>StreamingExcelProbe</code> utils; new <code>POST /api/upload/probe</code> returns <code>{confident, column}</code> / <code>{confident:false, columns:[\u2026]}</code> / <code>{tooLarge, message}</code>. Every upload endpoint now accepts an optional <code>itemColumn=N</code> override so the modal can lock in the user\u2019s choice. New <code>PartsEnrichService</code>, <code>SkuEnrichService</code>, <code>BomImplodeEnrichService</code> mirror <code>ChangeHistoryEnrichService</code>. Shared frontend module <code>smart-upload.js</code> exposes <code>smartUpload(file, opts)</code> and <code>smartEnrich(file, opts)</code>; one column-picker modal element is created on first use and reused by every tab.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'BOM bulk upload \u2014 multi-sheet picker + batched IN-list queries (5+ minutes \u2192 ~30 seconds)',
        items: [
            { badge: 'fix', text: '<strong>Smart sheet picker for multi-sheet workbooks.</strong> The BOM upload was always reading <code>getSheetAt(0)</code>; for files where Sheet 1 is a pivot summary and the actual list lives on a different tab, it parsed the wrong rows. Now we score every sheet against the same item-number header keywords (item number, part number, sku, mpn, pn, item_id, etc.) and pick the one whose row-1 has the best priority match. Today\'s real example: Vikas Singh\'s "Summary_By_Demand_Type May-5 subassembly list.xlsx" has 5 sheets; we now correctly pick "Assembly list" (2,884 SKUs) instead of Sheet1 (9,816 pivot-summary rows).' },
            { badge: 'new', text: '<strong>Detected sheet + column shown on the result page.</strong> Status bar chip reads <em>"Sheet: Assembly list \u00b7 Item column: A (ITEM_ID) \u00b7 via header"</em> so the user can spot a wrong pick before relying on the data.' },
            { badge: 'fix', text: '<strong>Batched IN-list BOM queries</strong> for bulk uploads (>= 5 inputs). Old behavior ran one CONNECT BY query per input item. New behavior runs <strong>one IN-list query per level</strong> with the level\'s distinct parents/children, chunked at the Oracle 1000-IN cap. An assembly that\'s a parent of 50 different inputs is queried exactly once per level instead of 50 times \u2014 the dedup the user asked for.' },
            { badge: 'fix', text: '<strong>Top-level finder also batched.</strong> The "find all terminal parents" walk that runs after every implode used to spawn N parallel CONNECT BY queries at depth 99; on a 2,884-item upload that was the bulk of a 5+ minute response. Now: iterative IN-list widening up to 99 levels, walks once per level and emits one row per (input, terminal-parent) pair.' },
            { badge: 'improve', text: '<strong>Real numbers</strong>: 2,884-SKU implode at depth=1 went from 5+ minutes (timing out at the curl level) to <strong>30.7 seconds end-to-end</strong> \u2014 6.8s for the level-1 batch + 23.9s for the depth-99 top-level walk. Same dataset, same correctness, ~10x faster.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>BomDataService.runBatched(items, maxDepth, isExplode)</code> + <code>findTopLevelAssembliesBatched(items)</code> share the same <code>queryEdges</code> + DFS-emit pattern. Threshold is 5 inputs (single items keep the legacy CONNECT BY path which is still fastest for 1\u20134 inputs). New <code>FileItemParser.pickBestSheet()</code> ranks every sheet by row-1 keyword priority then non-empty rows; ties go to the earliest sheet. <code>UploadColumnDetector</code> exposes its priority list publicly so the sheet picker reuses it.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'Crash recovery \u2014 user uploads are saved before processing, emailed AND filed as a bug ticket after a JVM restart',
        items: [
            { badge: 'new', text: '<strong>Upload quarantine.</strong> Every file users upload to Change History, BOM upload, Part Extract, Agile Lookup, SKU Lookup, Data Compare, and Team Report is now copied to <code>./data/upload-quarantine/</code> <em>before</em> heavy work starts. On clean completion the copy is deleted. If the JVM dies mid-processing (OOM, kill, machine crash), the file stays on disk.' },
            { badge: 'new', text: '<strong>Auto-email + auto-bug-ticket after restart.</strong> When the toolkit boots, an <code>ApplicationReadyEvent</code> listener scans the quarantine dir. For each leftover file it (a) emails <code>pdl-plm-admin@sandisk.com</code> with the original file attached and metadata about the request (user, endpoint, file size, timestamp, request params), (b) files a <strong>Bug Report</strong> ticket in the feedback queue (filed AS <code>plmadmin</code>, attached file, body labels it as <em>[Auto-filed crash recovery]</em>) so the next <code>/poll-feedback</code> run picks it up alongside user-filed bugs. Files then move to <code>processed/</code> so they don\'t re-fire on subsequent restarts.' },
            { badge: 'improve', text: 'Why this matters: when the prod JVM OOM\'d on Vikas Singh\'s 13K-SKU upload earlier today, we had to ask him to re-share the file because it lived only in his browser. Going forward, IT gets the file directly the moment the toolkit comes back up, AND it\'s on the bug-tracker queue automatically \u2014 no need to bother the user.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>UploadQuarantineService.quarantine(file, user, endpoint, params)</code> writes the bytes plus a <code>.meta.json</code> sidecar; <code>release(ticket)</code> deletes both on success; <code>processOrphans()</code> fires once per startup, sends the email, and calls <code>SupportController.submitFeedbackInternal()</code> with a <code>FileMultipartFile</code> wrapper around the quarantined file to file the bug ticket. Disabled cleanly via <code>app.upload-quarantine.enabled=false</code>. Retention is configurable via <code>app.upload-quarantine.max-age-days</code>.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'Change History \u2014 smart item-column detection + Download enriched file',
        items: [
            { badge: 'fix', text: '<strong>Item-number column auto-detected from upload headers.</strong> Old behavior always read column A &mdash; if your spreadsheet had Product Line in A and Item Number in B (which is exactly Julia\'s OBS-SKU file), the upload silently parsed product-line strings as items and ran 13K useless queries. Now we read row 1 and pick the column whose header matches <code>Item Number / Number / Part Number / SKU / MPN / PN / Material / Component</code> (priority-ordered). Detection method shows up as a chip in the status bar: <em>Item column: B (Number) \u00b7 via header</em>.' },
            { badge: 'new', text: '<strong>AI fallback</strong> for files where the heuristic finds zero matching headers. Sends the headers + 2 sample rows to Claude Haiku via Portkey and gets back the column index; bounded to ~150 tokens out and only fires when the heuristic is empty. Chip then reads <em>via AI</em>. Disable via <code>app.upload-column-detect.ai-fallback=false</code> if needed.' },
            { badge: 'new', text: '<strong>Download enriched file</strong> button (status bar). Returns your <em>original</em> spreadsheet with 4 new columns inserted right after the item-number column: <strong>Lifecycle Phase Reached</strong>, <strong>Release Date</strong>, <strong>Change Number</strong>, <strong>Change Type</strong>. One row per input row; earliest released REV per item. Items with no match leave the new cells blank. Original formatting and the other 39 columns are preserved.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>UploadColumnDetector</code> (heuristic + AI fallback) + <code>FileItemParser.parseItemsWithDetection()</code> returning <code>{items, sourceColumn, sourceHeader, method}</code>. New <code>POST /api/history/enrich</code> backed by <code>ChangeHistoryEnrichService</code> that opens the input with XSSFWorkbook, shifts cells right of the item-number column by 4, fills in the 4 new fields per row, and streams back. Activity log line records the detected column and the method (header-match / ai-fallback / default-col-a) for every upload.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'Change History \u2014 Lifecycle / Change Type pre-filters + bulk-input safety (PT-OBS-SKU)',
        items: [
            { badge: 'new', text: '<strong>Lifecycle pre-filter pills</strong> above the search row. Click any combination of <code>DEV PROTO PPROD C-ACT ACT MKT EOL OBS OBS-SKU DEAD</code> to restrict to REVs that reached those phases. Empty = no filter (today\'s behavior).' },
            { badge: 'new', text: '<strong>Change Type pre-filter</strong> &mdash; comma-separated input next to the lifecycle pills (e.g. <code>ECO, MCO, AML</code>). Combines with lifecycle filter as AND.' },
            { badge: 'improve', text: 'Whenever a pre-filter is active, the query implicitly restricts to <strong>released REVs only</strong> (<code>r.RELEASE_DATE IS NOT NULL</code>) so pending changes that <em>propose</em> a phase transition don\'t leak in as if they\'d already happened. A small grey hint under the filter row makes the constraint explicit.' },
            { badge: 'fix', text: '<strong>Lifecycle Journey is now hidden when input > 10 items</strong> &mdash; replaces the per-item journey panel with a one-line "hidden &mdash; reduce to ≤10 to enable" notice. Prevents the DOM/heap explosion that crashed prod on a 13,105-item upload (Vikas Singh, 2026-05-08).' },
            { badge: 'fix', text: '<strong>Bulk Change History query is now batched</strong> (single <code>WHERE i.ITEM_NUMBER IN (\u2026)</code> per 1000-item chunk) instead of one query per item. Same inputs that took minutes and OOM\'d the JVM now finish in seconds.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>ChangeHistoryService.getHistoryFiltered(items, phases, types)</code> &mdash; chunks at 1000 (Oracle IN cap), builds dynamic SQL with <code>?</code> placeholders for phase/type lists. <code>ChangeHistoryController</code> now plumbs <code>lifecyclePhases</code> / <code>changeTypes</code> through search/upload/export/email. Activity log line now includes the active filter values for traceability.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'New tab: Single/Sole Source Report \u2014 replaces the legacy Windows batch (ECN-128313-PROJ)',
        items: [
            { badge: 'new', text: '<strong>New <em>Single/Sole Source</em> tab</strong> (admin only). Three sub-reports \u2014 <strong>Designation Needed</strong>, <strong>Single Source</strong>, <strong>Sole Source</strong> \u2014 generated from a single Oracle query against <code>agprod</code>. <strong>Zero Agile SDK calls.</strong> Replaces <code>F:\\Batch\\Shruthi\\SingleSourceReport.jar</code> entirely.' },
            { badge: 'new', text: '<strong>Monthly auto-run</strong> on the 1st of every month at 2 AM. Emails <code>jimmy.sessumes@sandisk.com</code> (cc <code>vikas.singh3@sandisk.com</code>) and uploads to SharePoint <code>Reports/Single_Sole_Source_Report/</code>. The legacy Windows scheduled task on the batch box can be retired once we sign off.' },
            { badge: 'improve', text: '<strong>Manufacturer rows now expand</strong> on the Single Source / Sole Source tabs \u2014 one row per active MPN (the legacy batch only emitted the first MPN per item). Designation Needed remains one row per item.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>SingleSoleSourceService</code> runs one CTE-based query joining <code>ITEM</code> + <code>REV</code> + <code>PAGE_TWO</code> + <code>NODETABLE</code> + <code>LISTENTRY</code> + <code>MANU_BY</code> + <code>MANU_PARTS</code> + <code>MANUFACTURERS</code>, partitioned in Java by <code>PAGE_TWO.LIST77</code> values (4026098 Designation Needed, 4026102 Single Source, 4026103 Sole Source). Lifecycle Phase filter excludes OBS / OBS-SKU / Preliminary. Excel writer clones row-3 cell styles from the existing template (preserves formatting). SharePoint upload is the ROPC Graph code from <code>SSReport.java</code> ported verbatim.' }
        ]
    },
    {
        date: 'May 8, 2026',
        title: 'ECN Report UX refresh \u2014 calmer topbar, grouped change records, restructured Volume Reports inputs, Generate Team Report drawer',
        items: [
            { badge: 'improve', text: '<strong>#1 Topbar calmed</strong>. The 11 pipe-separated links collapsed into <strong>Feedback</strong>, <strong>Help \u25BE</strong>, <strong>Admin \u25BE</strong>, and <strong>Account \u25BE</strong>. World clocks moved behind a hover popover off the <code>\U0001F310 6 zones</code> pill (the always-on horizontal bar is gone). Build/version pill carries the data-source subhead in its hover title. AD Health and AI Eval moved into Admin\u25BE \u2014 one less row of top-level tabs in the nav.' },
            { badge: 'improve', text: '<strong>#1 BETA banner is dismissible</strong> \u2014 click \u00d7 to hide. Reappears automatically on next deploy (key is the running app version).' },
            { badge: 'improve', text: '<strong>#3 Grouped Change Records table</strong>. The records table now opens at <strong>~423 collapsed rows</strong> (one per change) instead of 5,614 stacked rows. Toggle <em>[Group by change | One row per item]</em> in the table header; choice persists per user. Group rows show <em>Change# / Type / Priority / \u00d7N items / Program / Summary / Owner / Released / Done / Category</em>. Click a parent row to expand into tree-connector children. Sticky header. Counts in the title read <em>423 changes \u00b7 5,614 affected items</em>. Filter input moved to the top of the card. Press <kbd>/</kbd> from anywhere to focus it.' },
            { badge: 'improve', text: '<strong>#2 Volume Reports inputs restructured</strong>. Single inputs card with date <em>preset pills</em> (This month / Last month / Last quarter / YTD / Custom\u2026) replacing the two raw date pickers as the default. Status pill (<em>\u2713 Done \u00b7 5,614 rows \u00b7 1.2s</em>) lives in the header alongside the new <strong>Generate Team Report \u2192</strong> button and an Excel button. Press <kbd>R</kbd> to run from anywhere on the page.' },
            { badge: 'improve', text: '<strong>#2 KPI tiles + People chips are now filters</strong>. Click the <strong>ECO</strong> / <strong>MCO</strong> / <strong>AML</strong> tile to filter the records table to that change type (mutually exclusive). Click a People-Queried chip to filter to only changes that person created. Click again to clear. Active filters get a blue border on tiles and a tinted background on chips.' },
            { badge: 'new', text: '<strong>#4 Generate Team Report drawer</strong>. The inline build form is gone \u2014 click <strong>Generate Team Report \u2192</strong> in the header to open a 480px right-side drawer. Drag-and-drop the prior month\u2019s <code>Team_Report_YYYY_MMM.xlsx</code>, the target month auto-derives from the run date, a yellow heads-up appears if you\u2019re skipping a month (e.g. Feb \u2192 Apr instead of Feb \u2192 Mar). \u201cWhat this does\u201d accordion is open the first time, collapsed thereafter. Recent reports list under it shows your last 3 generated XLSX with one-click re-download.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>. <code>index.html</code>: full topbar restructure with <code>.np-menu</code> dropdown pattern; sub-tabs become <code>role="tablist"</code>; new drawer scaffold with scrim + sticky footer. <code>app.js</code>: <code>npToggleMenu</code> / <code>npCloseAllMenus</code> click-outside helpers; topbar build pill populated from <code>buildLabel</code>; BETA dismissal keyed to <code>window.appBuildVersion</code>. <code>volumereport.js</code>: <code>volumeApplyDatePreset</code>, <code>volumeToggleChangeTypeFilter</code> + <code>_volumeApplyAndRender</code> for client-side filtering, <code>volumeTogglePersonFilter</code>, drawer state machine (<code>teamReportOpenDrawer</code> / <code>teamReportFilePicked</code> / <code>_trUpdateMonthWarning</code> / <code>teamReportRefreshLastUsed</code> / <code>teamReportRefreshRecent</code>). <code>TeamReportController</code>: new <code>GET /api/team-report/recent</code> + <code>GET /api/team-report/recent/{storedAs}/download</code>; per-user cache at <code>./data/team-report/recent/&lt;username&gt;/</code> pruned to 3 newest after every successful generate. Path-traversal-safe filename gate.' },
            { badge: 'improve', admin: true, text: '<strong>Out of scope this round</strong>: KPI \u0394 vs prior period and 6-period sparklines (the handoff flagged these as optional; both need new <code>/api/ecn/volume?compareWith=prior</code> and <code>/sparkline</code> backend hooks). Easy to add later \u2014 the tile renderer already has the slot.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'Team Report \u2014 column-R \u201c#N/A\u201d \u2192 \u201cMix\u201d auto-fix + new AI Analysis tab with per-team themes and risk callouts',
        items: [
            { badge: 'improve', text: '<strong>Column R \u201c#N/A\u201d \u2192 \u201cMix\u201d</strong>: every <code>VLOOKUP</code> in column R of the <em>Raw data-affected item</em> tab is now wrapped with <code>IFERROR(\u2026,"Mix")</code>, so multi-line or unmapped product lines render as <strong>Mix</strong> instead of <code>#N/A</code>. Manual <code>MIX</code> overrides from prior months are preserved (only formulas get rewritten).' },
            { badge: 'new', text: '<strong>New \u201cAI Analysis\u201d tab</strong>: after the script finishes, Claude Sonnet (via SanDisk Vortex) reads the change descriptions for the input month, grouped by Program Team, and writes one row per team with <em>Top Themes</em> (anomalies, spikes, major contributions \u2014 e.g. \u201cPCIe Client = 79% of CSSD volume\u201d) and <em>Risk Callouts</em> (Urgent priorities, missed deliveries, CAPA / quality issues). Output is read-only, lives at the end of the workbook, doesn\'t touch the existing pivots or charts.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: a small Python <code>team_report_postprocess.py</code> runs after <code>build_team_report.py</code> \u2014 opens the output XLSX with openpyxl, rewrites column-R formulas, appends the AI Analysis sheet from a JSON Java pre-computed. Java extracts the rows from the just-built XLSX (Apache POI), groups by Program Team using the lookup in the <em>Program Team</em> sheet (multi-line G or unmapped product line \u2192 \u201cMix\u201d), then calls <code>PortkeyClient.chat()</code> per team in parallel via <code>CompletableFuture</code>. Best-effort: if Portkey is down or the postprocess script is missing, the request still returns the un-augmented XLSX with an <code>aiNote</code> in the response.' },
            { badge: 'improve', admin: true, text: '<strong>Latency</strong>: cache-warm typical run is now ~90s (script 6s + AI ~80s in parallel for 6\u20137 teams + postprocess few s). Cache-cold adds ~25s for the volume xlsx build. Front-end staged-progress messages updated to walk through the new steps so the user sees motion.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'ECN Report \u2192 Volume Reports \u2014 \u201cBuild next-month Team Report\u201d generates the PCM monthly XLSX without leaving the app',
        items: [
            { badge: 'new', text: '<strong>One-click monthly Team Report.</strong> Inside Volume Reports, after you click <em>Run Report</em>, a new <strong>Build next-month Team Report</strong> panel appears below the change-records table. Pick a target month (<code>MMM_YYYY</code>, e.g. <code>Mar_2026</code>), upload last month\u2019s <code>Team_Report_YYYY_MMM.xlsx</code>, click <strong>Generate</strong>, and the rolled-forward XLSX downloads automatically along with a discrepancy doc that flags any cell where computed values disagree with the prior month\u2019s hand entries. End-user no longer touches the file system or runs the Python CLI.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: new <code>POST /api/team-report/generate</code> endpoint (multipart: <code>month</code>, <code>previousMonth.xlsx</code>, plus the volume-report criteria). Server reuses <code>VolumeReportController.generateVolumeXlsxBytes()</code> (session-cached, falls through to a fresh query) so the volume XLSX is built server-side from the same rows the user is looking at \u2014 no binary round-trip through the browser. The bytes plus the user\'s prev-month upload are written to a <code>java.io.tmpdir</code> sub-dir and handed to <code>build_team_report.py</code> via <code>ProcessBuilder</code>, with a 2-minute timeout (script normally runs in ~25s). Output XLSX + <code>_discrepancies.docx</code> come back as base64 in one JSON envelope.' },
            { badge: 'improve', admin: true, text: '<strong>Operational</strong>: <code>build_team_report.py</code> (v15, ~108 KB, single-file) lives at <code>./data/team-report/build_team_report.py</code> on the server (matches the existing <code>./data/ecn-report/</code> pattern). Python deps: <code>openpyxl &gt;= 3.1</code> and <code>python-docx &gt;= 0.8</code> \u2014 same Python the existing ECN Report tab already uses. Concurrent requests are safe (each run gets its own temp dir, cleaned up in a <code>finally</code> block). 14-column <em>Changes</em> sheet shape from the existing <code>VolumeReportController.buildXlsx()</code> matches the script\'s <code>read_volume_report()</code> contract verbatim.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'Login page redesigned as a two-column \u201cworkspace gateway\u201d',
        items: [
            { badge: 'improve', text: '<strong>The sign-in page now pairs the auth form with at-a-glance system status and a \u201cWhat\'s new\u201d feed.</strong> Same login + access-request flow as before \u2014 only the layout and styling changed. Headlines use IBM Plex Serif; meta uses IBM Plex Mono; the primary action is the blue accent. Below 980px viewport (tablet/mobile) the left pane hides automatically and the form fills the screen.' },
            { badge: 'improve', admin: true, text: '<strong>Drop-in swap</strong>: replaced <code>src/main/resources/static/login.html</code>; <code>tokens.css</code>, <code>sandisk-logo-red.png</code>, and the <code>/api/auth/login</code> + <code>/api/auth/request-access</code> endpoints are reused unchanged. Function names (<code>doLogin</code>, <code>showAccessRequestForm</code>, <code>backToLogin</code>, <code>submitAccessRequest</code>) preserved verbatim. Old file backed up to <code>backups/login.html.bak-2026-05-07</code> outside <code>static/</code> so it isn\'t served. The left pane ships with static placeholder values; two non-blocking <code>fetch()</code> calls (<code>/api/system/status</code> and <code>/api/whats-new?limit=3</code>) hydrate it if those endpoints exist \u2014 today they don\'t, so the placeholders show.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'User Management \u2014 admin-only \u201cRemove\u201d button per non-admin user opens the IT-APP-Agile-admin AD group page',
        items: [
            { badge: 'new', admin: true, text: '<strong>Each non-admin row in All Users now has a red \u201cRemove\u201d button</strong> next to Edit, visible only to PLM admins. Click it to open <code>https://anywhere.sandisk.com/ad-group-info/IT-APP-Agile-admin</code> in a new tab \u2014 you handle the actual removal there. PLM admin rows still show the existing \u201cLocked\u201d pill (no Remove button) since revoking admin requires removing them from <code>pdl-plm-admin</code> instead.' },
            { badge: 'improve', admin: true, text: '<strong>No notification to the user being removed.</strong> The button is a pure external link \u2014 this tool fires no email or callback when an admin clicks Remove. The removed user finds out at their next login attempt (or when their session expires).' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'New \u201cMore\u2026\u201d parent tab consolidates five low-usage tabs',
        items: [
            { badge: 'improve', text: '<strong>PT-26 \u2014 \u201cMore\u2026\u201d parent tab</strong> (per Vikas Singh). Five tabs that aren\'t used every day are now grouped under a new <strong>More\u2026</strong> parent, in this order: <em>Work Instructions, User Management, Extensions, Data Compare, Change Reviews</em>. Same content, same URLs, fewer top-level buttons cluttering the nav. Click <strong>More\u2026</strong> to land on Work Instructions; the sub-tab nav at the top of each panel switches between the five children.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: same parent-tab pattern as <em>Items</em> and <em>BOM</em>. New <code>tabMore</code> button in <code>index.html</code>, the five original top-level buttons (<code>tabHelpCenter</code>, <code>tabPermissions</code>, <code>tabExtensions</code>, <code>tabCompare</code>, <code>tabChangeReviews</code>) kept at <code>display:none</code> for permission-grant compatibility. <code>app.js</code> adds a synthetic <code>more</code> key to <code>TAB_PREFS_CONFIG</code> + <code>applyServerTabPermissions</code> (visible if any of the 5 children is allowed), syncs five mirrored <code>.more-sub</code> navs, and re-surfaces specific sub-tabs for <code>isExtensionContributor</code> and <code>isPermissionsAdmin</code> users (those flags previously force-showed individual top-level buttons). <code>UserPermissionsService</code> tab labels now read \u201cMore \u2192 Work Instructions\u201d / etc., matching the existing \u201cItems \u2192 \u2026\u201d convention.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'Feedback queue sweep \u2014 Work Instructions rename, BOM Explorer column cleanup, Lifecycle Journey collapsed by default',
        items: [
            { badge: 'improve', text: '<strong>PT-21 \u2014 \u201cHelp Center\u201d tab renamed to \u201cWork Instructions\u201d</strong> (per Vikas Singh). The document knowledge base tab is now labelled <strong>Work Instructions</strong> in the top nav, in the User Management permission row, and in the AI Help bot\'s answers. Click-through and download still hit the same <code>/api/help-docs/{id}/download</code> endpoint, so existing links work; only the user-visible label changed. The AI bot still recognises legacy "help center" queries.' },
            { badge: 'improve', text: '<strong>PT-22 \u2014 BOM Explorer column cleanup</strong> (per Vikas Singh). On both the in-app table and the Excel export: the <em>Root SKU</em> column is now <strong>Input PN</strong>, and the table now shows only one of <strong>Parent BOM</strong> (in Where Used) or <strong>Child BOM</strong> (in Explode) instead of both. The export filename + sheet title now read <em>BOM Where Used</em> instead of <em>BOM Implosion</em>. The result chip on the page now says \u201cWhere Used\u201d to match the search button.' },
            { badge: 'improve', text: '<strong>PT-23 \u2014 Lifecycle Journey is collapsible, default Collapsed</strong> (per Vikas Singh). When you query several PNs at once, the Journey block was eating most of the screen above the change-history table. Each Journey card now has a chevron \u25B6 / \u25BC header \u2014 click to expand. Default is collapsed so the change-history table shows up immediately; the Journey is still one click away.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: PT-21 touches <code>index.html</code>, <code>app.js</code>, <code>UserPermissionsService</code>, <code>AiHelpController</code>, <code>app-knowledge.txt</code> (visible labels only; internal IDs <code>tabHelpCenter</code>, <code>helpcenter</code> route key, <code>helpcenter.js</code> are unchanged). PT-22 touches <code>index.html</code> + <code>bom.js</code> (toggles <code>display:none</code> on <code>.bom-col-parent</code> / <code>.bom-col-component</code> based on <code>bomCurrentMode</code>), <code>BomExcelExportService</code> (single column emitted, header order shifted by one), and <code>BomController</code> + <code>EmailService</code> (filename, title, subject all use \u201cWhere Used\u201d). PT-23 wraps the Lifecycle Journey body (timeline bar + summary table + stats) in a hidden <code>div</code> with a click handler on the header.' }
        ]
    },
    {
        date: 'May 7, 2026',
        title: 'ECN Report \u2014 new \u201cVolume Reports\u201d sub-tab: pick a manager + date window, get every ECO/MCO/AML their team raised',
        items: [
            { badge: 'new', text: '<strong>Three inputs, one report</strong>: pick a Manager from the access-DL dropdown, set Begin and End dates, and the app resolves the manager\'s direct reports from AD, looks up each report\'s Agile <code>LOGINID</code>, and runs the Web-Client equivalent of the Change Search (ECO + MCO + AML in one query). Output is a 13-column table with status, priority, classification, affected item, dates, requestor and analyst \u2014 plus KPI tiles for total rows / distinct changes / per-type counts.' },
            { badge: 'new', text: '<strong>Optional toggles</strong>: <em>Include indirect reports</em> walks the org tree (BFS, capped at 500 users / 6 levels) so directors can see their full org\'s volume; <em>Include manager</em> adds the manager\'s own changes alongside their reports\'. People with no <code>employeeID</code> attribute in AD are listed as warnings rather than silently dropped.' },
            { badge: 'new', text: '<strong>CSV export</strong> ships the same 14 columns the Web Client export uses, in the same header order, so the file is drop-in to existing leadership decks. Filenames embed manager + window for traceability.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: backend is one new <code>ChangeSearchService</code> running the SQL from <code>docs/CHANGE-SEARCH-API-SPEC.md</code> against the primary <code>dataSource</code> (Agile schema, same connection pool BOM Compare uses). LDAP lookups go through new <code>LdapAuthService.findDirectReports(manager, transitive)</code> + <code>lookupEmployeeIdByUsername</code>. Endpoints: <code>GET /api/ecn-report/volume/managers</code>, <code>POST /api/ecn-report/volume/run</code>. UI lives behind the existing <code>ecnreport</code> tab key \u2014 no new permission entry needed.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Returns Tracker \u2014 Top Product Teams now falls back to Product Line when no team annotation exists (no more 100% Unknown)',
        items: [
            { badge: 'fix', text: '<strong>The "Top Product Teams" panel was bucketing every event as Unknown on prod</strong> because most ECNs don\'t have an explicit <em>Product Team</em> annotation set yet (the dropdown is admin-only on the Cycle Time data table). With 1,626 returns and zero teamOverrides, the chart was useless.' },
            { badge: 'improve', text: '<strong>Fix</strong>: when an ECN has no <code>teamOverride</code> set, the panel now falls back to the event\'s first Product Line. So Wafer/Die, Enterprise PCIe, etc. show up as bars. ECNs that <em>do</em> have an explicit team override (CSSD, ESSD, AME, etc.) still show under the override \u2014 admins\' work isn\'t lost. A footnote on the panel explains the fallback and points to where to set Team values.' },
            { badge: 'improve', admin: true, text: '<strong>Both surfaces match</strong>: <code>RejectionTrackerService.getAggregates</code> (powers the in-app panel + JSON API) and <code>RejectionTrackerEmailService.addProductLinesSheet</code> (powers the Excel chart sheet) use the same fallback rule. Counts split a single event\'s multi-line <code>productLine</code> on <code>;</code> and use only the first segment for the team-bucket so totals match the events count.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Activity digest \u2014 AI insights now scoped to the digest window (no more April crashes showing up in a same-day digest)',
        items: [
            { badge: 'fix', text: '<strong>The "AI insight" callout in the admin Activity digest was pulling crashes/errors from weeks back</strong> even though the digest header said e.g. "May 6 8:59AM\u2013May 6 8:59PM". On a single-day window the AI was citing April 22 OOMs, April 16 servlet failures, and May 4 LLM HTTP 400s \u2014 all real, but outside the window the email claimed to cover.' },
            { badge: 'improve', admin: true, text: '<strong>Root cause</strong>: <code>DeltaReportService.getAiLogAnalysis(since)</code> received the <code>since</code> timestamp (last digest sent time) but never used it to filter log lines. It read every line in <code>plm-toolkit.log</code> and keyword-filtered them all, so any error from the start of the file could end up in the AI prompt.' },
            { badge: 'improve', admin: true, text: '<strong>Fix</strong>: log lines are now parsed by their leading <code>yyyy-MM-dd HH:mm:ss.SSS</code> timestamp; only lines with timestamp \u2265 <code>since</code> reach the keyword filter. Multi-line stack traces / wrapped messages inherit the previous timestamped line\'s time so they stay attached to their parent. Empty windows return "No log entries within the digest window." instead of stale crashes.' },
            { badge: 'improve', admin: true, text: '<strong>First-run behaviour preserved</strong>: when <code>last-report-time.txt</code> is missing (since=0), all timestamped lines pass \u2014 same as before. Only the "since=last-digest-sent-time" path is narrowed.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Returns Tracker \u2014 \u201cRejection\u201d renamed to \u201cReturn to Pending\u201d throughout the email and in-app UI',
        items: [
            { badge: 'improve', text: '<strong>Both the email body and the in-app Returns Tracker view now use "Return to Pending" instead of "Rejection".</strong> Section headers (<em>Daily Return-to-Pending Trend</em>, <em>Return-to-Pending Events</em>, <em>Return-to-Pending Drill-down</em>), KPI tile titles, AI summary phrasing, status text, tooltips, and Excel chart labels all updated. The Excel data sheet column was already renamed; this sweep covers everywhere else the old word was visible.' },
            { badge: 'improve', admin: true, text: '<strong>What changed where</strong>: <code>RejectionTrackerEmailService</code> Excel labels (Trend "Rejections" \u2192 "Returns to Pending", Y-axis "Rejection count" \u2192 "Return-to-Pending count", chart series + titles) and email-body inline text (subtitle, KPI tile, excluded-events callout, AI prompt body); <code>returnstracker.js</code> KPI tiles, status messages, SVG titles, drill-down labels; <code>index.html</code> section headers and the "Rejected by" / "Rejected from:" labels.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Returns Tracker \u2014 \u201cTop Product Lines\u201d switched to Product Team to match the ECN Report KPI breakdown',
        items: [
            { badge: 'improve', text: '<strong>Per Jimmy</strong>: the Returns Tracker now groups returns by Product Team instead of Product Line, mirroring the ECN Report KPI\'s existing Team breakdown. Affects the in-app panel (renamed <em>Top Product Teams</em>), the email body section, and the Excel chart sheet (renamed <em>Product Teams</em>, chart title <em>"Rejections by Product Team"</em>).' },
            { badge: 'improve', admin: true, text: '<strong>Implementation</strong>: <code>RejectionTrackerService.getAggregates</code> now also emits <code>topProductTeams</code> alongside the legacy <code>topProductLines</code>; team is resolved per event by looking up the ECN\'s <code>teamOverride</code> annotation (the same value admins set via the ECN Report data table dropdown). ECNs without a team annotation bucket as <em>Unknown</em>.' },
            { badge: 'improve', admin: true, text: '<strong>Backwards-compat</strong>: <code>topProductLines</code> is still emitted for any external consumer of the aggregates JSON. The frontend reads <code>topProductTeams || topProductLines</code> so the panel never goes blank during a partial deploy. The row-level Excel data sheet still shows the per-row Product Line column \u2014 only the aggregate breakdown changed.' },
            { badge: 'improve', admin: true, text: 'To populate Product Team values: open ECN Report \u2192 Cycle Time \u2192 scroll to the data table \u2192 use the Product Team dropdown column. Anything currently in the table without a team set will show under <em>Unknown</em> in the Returns Tracker breakdown.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Heap-pressure circuit breakers \u2014 four new gates close the OOM gaps that were still open',
        items: [
            { badge: 'improve', admin: true, text: '<strong>MemoryGuard threshold is now config-driven</strong> via <code>app.memory.pressure-threshold</code> (default 0.80). Set it to 0.70 in prod\'s <code>config/application.properties</code> for more headroom on the 6 GB heap (where a 700 MB cache-load spike can still happen on first ExternalSource query after restart). Local stays at 0.80.' },
            { badge: 'improve', admin: true, text: '<strong>Upload / replace-file / headers endpoints now check MemoryGuard before accepting the request</strong>. Returns 503 + retry-after JSON with a clear message if heap is already at threshold. Closes the gap where <code>JobQueueService.submitRebuild</code>\'s existing check fired too late \u2014 by then the multipart parse + temp-file staging had already happened.' },
            { badge: 'improve', admin: true, text: '<strong>ExternalSourceService cache load is OOM-guarded</strong>. <code>readCache()</code> now refuses to inflate a "big" (\u22650 MB) JSON cache when the JVM is already at threshold; throws <code>IllegalStateException</code> so callers surface a clear 503 instead of OOMing the JVM and taking down the whole web server. <code>System.gc()</code> hint between the first check and the verdict so MemoryGuard reads accurately. The biggest known offender (Vikas Singh\'s 660 MB ZPTF cost-file) is the exact target.' },
            { badge: 'improve', admin: true, text: '<strong>Returns Tracker email build is OOM-guarded too</strong>. <code>RejectionTrackerEmailService.buildExcel()</code> now checks MemoryGuard at the top \u2014 if pressured, throws and the email is skipped (logged, retried on the next scheduled run). Above 5,000 events the chart sheets are dropped to keep SST allocations bounded; data sheet content is preserved either way. Migration to streaming SXSSF deferred (POI streaming doesn\'t support charts), but this guard removes the immediate OOM risk.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Chatbot \u2014 help-docs interceptor stops short-circuiting the LLM with weak matches',
        items: [
            { badge: 'fix', text: '<strong>Generic process questions like "how do I compare 2 BOMs?" no longer get answered with unrelated ECN/Import-Export doc snippets.</strong> Earlier today the help-docs interceptor was firing on any single-word substring match (because the score gate was 3 and a generic word like "compare" appearing in any PLM doc cleared it), beating the LLM that had the correct, up-to-date answer in app-knowledge.txt.' },
            { badge: 'improve', admin: true, text: '<strong>What changed</strong>: gate now requires <em>both</em> (a) score &ge; 10 (excludes weak single-word substring matches; effectively requires either a phrase match or a multi-keyword strong match), and (b) at least half the question\'s significant terms actually appear in the doc snippet. Single-term queries ("what is an ECN") still pass since 1 of 1 = 100%. Multi-term weak matches now route to the LLM (which has the chat history + app-knowledge.txt) instead of silently returning irrelevant snippets. <code>HelpDocsService.search()</code> now also returns <code>queryTermsTotal</code> in each result so callers can compute coverage without re-tokenising.' },
            { badge: 'improve', admin: true, text: '<strong>Validated against 4 cases</strong>: "how do I compare 2 BOMs?" \u2192 LLM (was help-docs, wrong); "what is an ECN?" \u2192 LLM (clean definition); "how do I import attachments in Agile?" \u2192 help-docs (legit doc hit); "process for AML changes" \u2192 help-docs (legit doc hit \u2014 AML is a distinctive term).' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Heap-pressure alert \u2014 emails Vikas if prod JVM heap stays > 85% for 5 minutes',
        items: [
            { badge: 'new', admin: true, text: '<strong>New <code>HeapPressureMonitor</code> service samples used heap once per minute.</strong> When the rolling 5-sample window stays above <code>app.heap-alert.threshold-pct</code> (default 85%, prod can lower it), one email is sent to <code>vikas.jindal@sandisk.com</code> via SanDisk SMTP. After firing, a 30-minute cooldown blocks repeat alerts so a slow leak doesn\'t spam. Catches "wedged but not dead" states the watchdog can\'t see (the watchdog only respawns on hard crashes / actual OOMs).' },
            { badge: 'new', admin: true, text: '<strong>Email body</strong>: SanDisk-styled HTML showing current usedMb / maxMb / %, the threshold + cooldown that fired, item-cache footprint (records + on-disk MB), per-source external-cache footprints sorted largest first, and a link back to the app. Subject: <code>plm-toolkit prod: \u26a0\ufe0f heap pressure (X% used, Y MB / Z MB)</code>.' },
            { badge: 'new', admin: true, text: '<strong>Test endpoint</strong>: <code>POST /api/maintenance/heap-alert/test</code> (maintenance-admin gated) sends one email NOW using the current heap snapshot, bypassing threshold + cooldown. Use it once after deploy to verify the email lands and looks right; after that the scheduled sampler takes over.' },
            { badge: 'improve', admin: true, text: '<strong>Local instances are auto-skipped</strong> because the whole scheduling subsystem is gated by <code>app.scheduling.disabled=true</code> on local \u2014 same gate every other scheduled job uses. The manual test endpoint still works on local for rendering checks.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Help/chatbot KT refreshed for BOM Compare \u2014 mentions Same-part / Different-parts modes and the new BOM \u2192 Compare nav path',
        items: [
            { badge: 'fix', text: '<strong>The chatbot was still answering "how do I compare 2 BOMs?" with the pre-May-6 explanation</strong> ("Two Parts mode" + "Two Revisions mode") because <code>app-knowledge.txt</code> hadn\'t been updated alongside the new BOM Compare UI. Verified live: the chatbot now answers correctly with steps under <em>BOM \u2192 Compare</em>, mentions the <em>Same part</em> / <em>Different parts</em> pill toggle, and gives an example of comparing across two assemblies.' },
            { badge: 'improve', admin: true, text: '<strong>What got rewritten</strong> in <code>app-knowledge.txt</code>: the BOM Compare section now describes the parent/sub-tab structure, both modes, the rev dropdown ordering (DESC by creation, pending revs in parens at top, soft-deleted changes filtered), the cross-part comparison flow (Load Revs A / Load Revs B), and concrete examples including pending-rev redline diffs.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'ECN Report KPIs \u2014 month-over-month delta is now apples-to-apples (MTD vs same period last month)',
        items: [
            { badge: 'fix', text: '<strong>The "vs prior month" delta on the ECN Report KPI tiles was misleading early in a month.</strong> On May 6, the <em>Total ECNs &middot; YTD</em> tile would show "&darr; 95.7% vs prior month" because the underlying comparison was the current calendar month\'s partial bucket (5 days of May) against last month\'s full bucket (30 days of April). That looks like a 95% collapse; it\'s actually just five days vs thirty.' },
            { badge: 'improve', text: '<strong>Fix</strong>: tile deltas now compare <em>current MTD</em> against <em>the same day range last month</em>. So on May 6 you see "May 1-6 vs Apr 1-6", clamped if the prior month is shorter (May 31 caps to Apr 30). The suffix label spells out the window it\'s comparing so there\'s no ambiguity. Same logic for % On Target, Avg Net Days, and Urgent Share \u2014 all four tiles use the same MTD-vs-MTD windows.' },
            { badge: 'improve', admin: true, text: 'Implemented as <code>ecnComputeMtdDelta(allRows)</code> in <code>ecnreport.js</code>; filters Completed rows by month-of-year and clamps day-of-month to the smaller of (today, last day of prior month). Returns curr/prev buckets with <code>total</code>, <code>urgent</code>, <code>avgDays</code>, <code>pctOnTarget</code>, <code>urgentShare</code>. Replaces the old <code>stats.trends.data[lastMonth]</code> vs <code>[priorMonth]</code> shortcut.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Tab consolidation \u2014 sub-tab permissions enforced, TAB_CATALOG labels and chatbot KT updated to match',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Sub-tab buttons now hide based on per-user permissions.</strong> Previously, an admin could revoke e.g. <code>bomcompare</code> in User Management but the user could still click into the Compare sub-tab inside BOM (server-side data fetches would 403, but the panel chrome was reachable). With this change, <code>applyServerTabPermissions</code> also walks the BOM and Items sub-tab navs and hides each sub-tab button whose key isn\'t in <code>allowedTabs</code>. Per-key revokes work as expected again.' },
            { badge: 'improve', admin: true, text: '<strong>TAB_CATALOG labels reflect the new nav.</strong> In User Management, the Allowed Tabs list now reads <em>Items \u2192 Field Changes</em>, <em>Items \u2192 Part Extract</em>, etc. for the four Items children; <em>BOM \u2192 Explorer</em> / <em>BOM \u2192 Compare</em> for the BOM children. Underlying keys (<code>fields</code>, <code>parts</code>, <code>agile</code>, <code>sku</code>, <code>bom</code>, <code>bomcompare</code>) are unchanged \u2014 just the display labels.' },
            { badge: 'improve', text: '<strong>Help guides + chatbot KT updated.</strong> The User Guide and Tech Guide now have a banner at the top describing the new parent/sub-tab structure. The chatbot\'s app-knowledge.txt and AiHelpController action filters know that "User Management" is the renamed "User Permissions", and that "BOM Compare" / "Part Extract" / "Agile Lookup" / "SKU Lookup" are sub-tabs under their parents. The new-user onboarding email\'s "3 things to try first" tips now reference the new nav paths.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Tab nav \u2014 \u201cItems\u201d parent tab groups Field Changes / Part Extract / Agile Lookup / SKU Lookup; User Permissions \u2192 User Management',
        items: [
            { badge: 'improve', text: '<strong>Top nav is calmer.</strong> The four item-centric tabs \u2014 Field Changes, Part Extract, Agile Lookup, SKU Lookup \u2014 are now grouped under a single <strong>Items</strong> tab. Inside it, a sub-nav switches between the four. Clicking <em>Items</em> defaults to Field Changes (the most common entry point); the URL of each sub-tab still works the same way.' },
            { badge: 'improve', text: '<strong>Renamed</strong> the <em>User Permissions</em> tab to <strong>User Management</strong> \u2014 the page does more than just permissions now (DL membership, traffic stats, AD profile lookups, etc.).' },
            { badge: 'improve', admin: true, text: '<strong>No back-end migration</strong>: the underlying tab keys (<code>fields</code>, <code>parts</code>, <code>agile</code>, <code>sku</code>, <code>permissions</code>) are unchanged in <code>TAB_CATALOG</code>. Per-user permission grants on any of those continue to work as-is. The four hidden top-nav buttons are kept in the DOM (<code>display:none</code>) so back-end gating still has DOM elements to toggle when needed. Frontend <code>applyServerTabPermissions</code> treats the synthetic <code>items</code> key as visible whenever ANY of the four child keys is in <code>allowedTabs</code>.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Tab nav \u2014 BOM Explorer + BOM Compare consolidated under one \u201cBOM\u201d tab with Explorer / Compare sub-tabs',
        items: [
            { badge: 'improve', text: '<strong>Fewer top-level tabs.</strong> The old <em>BOM Explorer</em> and <em>BOM Compare</em> tabs are now grouped under a single <strong>BOM</strong> tab. Inside it, the existing pill nav switches between <em>Explorer</em> and <em>Compare</em> sub-tabs. Functionality is unchanged \u2014 same Explorer flow, same Compare flow (with today\u2019s Same-part / Different-parts toggle).' },
            { badge: 'improve', admin: true, text: '<strong>No-migration design</strong>: the underlying <code>tabBomCompare</code> nav button is hidden but kept in the DOM, and the back-end <code>TAB_CATALOG</code> still has the <code>bomcompare</code> entry. So per-user permission grants on either key continue to work as-is. Sub-tab clicks call <code>switchTab(\'bom\')</code> / <code>switchTab(\'bomcompare\')</code> as before; both highlight the same parent <em>BOM</em> tab, and the active-state CSS on the sub-tab pills is kept in sync across both panel headers.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'BOM Compare \u2014 unified \u201cSame part / Different parts\u201d toggle; rev dropdowns ordered by creation time',
        items: [
            { badge: 'improve', text: '<strong>The two old BOM Compare modes \u2014 Two Parts and Two Revisions \u2014 are now a single tab.</strong> A pill at the top toggles <em>Same part</em> (one part, two rev dropdowns, the old Two Revisions flow) vs. <em>Different parts</em> (two part inputs each with its own rev dropdown). Both modes use rev dropdowns now \u2014 you can compare any rev of part A against any rev of part B, including pending revs against released ones.' },
            { badge: 'improve', text: '<strong>Rev dropdowns are now ordered by creation time, descending.</strong> Pending revs (shown in <em>(parens)</em>) that were created after the most recent released rev now appear at the top of the dropdown instead of being forced to the bottom. Introductory remains at the bottom. Verified against A162-030556-256GR2: <code>(B) \u2014 ECO-135293-Q</code> now sits above <code>A \u2014 MCO-131836-A</code> and <code>A \u2014 ECO-131836-A</code>, matching the order most users expect (most-recently-relevant first).' },
            { badge: 'improve', admin: true, text: '<strong>Backend</strong>: <code>RevCompareService.getRevisions</code> now sorts by <code>r.ID DESC</code> (with the introductory CHANGE=0 row pinned to the bottom). Agile assigns rev IDs monotonically by creation, so this gives "newest-created first" without needing a CREATED_DATE column. <code>RevCompareController.compare</code> gained an optional <code>partB</code> query param \u2014 when supplied, side B queries the second part\u2019s rev/BOM; when omitted, both sides resolve against <code>part</code> (back-compat with all existing /compare callers, including the Excel export and email endpoints).' },
            { badge: 'improve', admin: true, text: '<strong>Frontend</strong>: panel rebuilt around a single shared results area. The old <code>doBomCompare</code> / bom_extract path is no longer surfaced \u2014 \u201cDifferent parts\u201d uses the same Agile-live rev-aware compare under the hood. Both modes share the result-table renderer, the field picker, and the email/export actions.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Extensions \u2014 new \u201cReplace\u201d button to refresh an existing source with a new upload',
        items: [
            { badge: 'new', text: '<strong>Each row in Extensions now has a \u21bb Replace button</strong> next to Generate / Schedule / Remove. It opens a small modal where you pick a fresh file, optionally choose a new key column from the file\u2019s headers, and click Replace. The cache rebuilds automatically. Previously the only way to swap data was to Remove the source and re-Add it (which lost the source\u2019s identity and any consumers).' },
            { badge: 'new', admin: true, text: '<strong>Auth gate matches Remove</strong>: admin can replace any source; a contributor (e.g. Vikas Singh) can replace only sources they themselves added (the <code>addedByUsername</code> check). Legacy sources without a recorded owner are admin-only.' },
            { badge: 'improve', text: '<strong>Original creator stays the same.</strong> The Added By column now shows two lines when applicable: <em>added by X \u00b7 N days ago</em> on top, and <em>\u21bb refreshed by Y \u00b7 just now</em> below it. So you always see who originally created the source and who last replaced its data, separately.' },
            { badge: 'improve', admin: true, text: '<strong>Implementation notes</strong>: new endpoint <code>POST /api/sku-data/external-sources/{id}/replace-file</code> (multipart). Saves the new file with a timestamp suffix (e.g. <code>./data/cost-file-sap-20260506-154807.xlsx</code>) so the previous file stays on disk in case the rebuild fails or user wants to roll back. <code>SourceDef</code> gained <code>lastRefreshedBy / lastRefreshedByUsername / lastRefreshedAt</code> fields. Activity log: new <code>EXT_SOURCE_REPLACE</code> action.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'BOM Compare \u2014 unreleased (pending) revs now return their actual BOM instead of 0 rows',
        items: [
            { badge: 'fix', text: '<strong>BOM Compare and Two-Revs comparisons now work correctly when one side is an unreleased rev.</strong> Previously, picking a rev wrapped in parens (e.g. <code>(B) \u2014 ECO-135293-Q</code>) returned an empty BOM because the SQL\'s release-date filter compared against the rev\'s release date \u2014 which is NULL for any rev that hasn\'t been released yet, so every BOM row was excluded. Confirmed against A162-030556-256GR2 (rev (B) from ECO-135293-Q): now shows 2 components (A190-012578-256G + T013-BOT-000342, the latter being the redline added by the pending ECO), matching what Agile\'s own UI shows.' },
            { badge: 'improve', admin: true, text: '<strong>Two SQL changes in <code>RevCompareService.getRevDetail</code></strong>: (1) <code>scoped_bom</code> WHERE clause now has a third branch for <code>pr.RELEASE_DATE IS NULL</code> that includes rows added by released ECOs OR by the pending change itself, and excludes rows removed by released ECOs OR by the pending change. (2) Service strips the parens off the dropdown\'s rev label (<code>(B)</code> \u2192 <code>B</code>) before binding to the SQL, so the parent_rev CTE matches the bare REV_NUMBER stored in the DB.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Data Compare rev dropdown \u2014 pending revs now show in parens, soft-deleted changes filtered out',
        items: [
            { badge: 'fix', text: '<strong>The Rev dropdown on Data Compare \u2192 Two Parts (and Two Revs) now matches Agile\u2019s own UI convention.</strong> Pending revs (rev exists but the parent change has not been released yet) display with the rev wrapped in parens \u2014 e.g. <code>(E1) \u2014 ECO-0235312</code> \u2014 instead of bare <code>E1</code>. Released revs continue to show as <code>E1</code>.' },
            { badge: 'fix', text: '<strong>Soft-deleted changes no longer appear</strong> in the rev dropdown. Agile keeps deleted change rows in the <code>change</code> table with <code>DELETE_FLAG</code> set; the rev query now adds <code>(r.CHANGE = 0 OR c.DELETE_FLAG IS NULL)</code> so those rows drop out. Found while debugging E006-003017 \u2014 ECO-0235310 was showing in the list even though it doesn\u2019t exist in Agile.' },
            { badge: 'improve', admin: true, text: '<strong>SQL change in <code>RevCompareService.getRevisions</code></strong>: rev_label CASE adds a <code>WHEN r.RELEASE_DATE IS NULL THEN \'(\' || r.REV_NUMBER || \')\'</code> branch; WHERE clause adds the DELETE_FLAG predicate. No new joins or query plan changes \u2014 same single-table-scan + index probe.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Field Changes tab missing after refresh \u2014 fixed key mismatch between client + server tab catalogs',
        items: [
            { badge: 'fix', text: '<strong>Field Changes tab no longer flashes on page load and disappears.</strong> The client-side tab-prefs config used <code>key: \'changes\'</code> for the Field Changes tab while the server\u2019s <code>TAB_CATALOG</code> used <code>\'fields\'</code>. <code>applyServerTabPermissions</code> matched the wrong key, decided the tab wasn\u2019t allowed, and hid it. Symptom was a one-frame flash of the tab on initial render before the JS hid it. Also explains why Field Changes was missing from the \u201cVisible Tabs\u201d dropdown under your name (the dropdown skips already-hidden tabs).' },
            { badge: 'improve', admin: true, text: 'Aligned the client and server keys to <code>\'fields\'</code>. The HTML element id (<code>tabChanges</code>), the panel id (<code>panelChanges</code>), and the <code>switchTab(\'changes\')</code> wiring are unchanged \u2014 only the prefs/permissions key was wrong.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Extensions tab \u2014 Key Column dropdown no longer hangs on large .xlsx uploads (read-side OOM-safety)',
        items: [
            { badge: 'fix', text: '<strong>The Key Column dropdown on the Add New Source form used to hang at \u201c-- reading headers... --\u201d for several minutes (or forever) when uploading a large .xlsx file.</strong> Vikas Singh hit this today with a 60 MB monthly cost-file extract; the same upload likely contributed to today\u2019s prod heap pressure that crashed the scheduled email job.' },
            { badge: 'improve', admin: true, text: '<strong>Root cause</strong>: <code>POST /api/sku-data/external-sources/headers</code> was inflating the ENTIRE workbook into memory (<code>new XSSFWorkbook(pkg)</code>) just to read row 0. For a 60 MB .xlsx, in-memory inflation was ~1\u20131.5 GB and took 10\u201330 s; pile that on top of a 6 GB heap with the item cache + change cache already loaded and the JVM was on the edge.' },
            { badge: 'improve', admin: true, text: '<strong>Fix</strong>: replaced with POI\'s SAX-based streaming reader (<code>XSSFReader</code> + <code>XSSFSheetXMLHandler</code>). A custom <code>SheetContentsHandler</code> bails out via a sentinel exception immediately after row 0, so the rest of the workbook is never parsed. Local benchmark on a 41 MB .xlsx: <strong>0.23 s, +6 MB heap</strong>. The handler reads cells via the shared-strings table the normal way \u2014 SST load is bounded too, and we never touch the data sheet rows.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Excel export OOM fix \u2014 swept ALL Excel-write sites to streaming POI + inline strings',
        items: [
            { badge: 'fix', text: '<strong>Field Changes <em>Export to Excel</em> and the scheduled email digest no longer OOM on large result sets.</strong> Prod crashed at 13:00 PDT today while emailing Krati Jain\'s daily Description-field digest (schedule <code>d5558736</code>); heap dump 10 GB on a 6 GB heap, stack pointed at <code>SharedStringsTable.addEntry</code>. Same signature was seen Apr 22 during a SKU export.' },
            { badge: 'fix', admin: true, text: '<strong>SKU Lookup export (SKU_EXPORT)</strong>: same OOM-safety fix \u2014 was the Apr 22 crash path.' },
            { badge: 'fix', admin: true, text: '<strong>Other Excel-write paths swept for the same pattern</strong>: BOM Explorer / BOM Compare exports, Change History download, Part Extract export, Agile Lookup exports (lookup template + result download), Data Compare exports (both write paths), Rev Compare export, BOM Notes Extract, Activity Log export, scheduled-report email attachment.' },
            { badge: 'improve', admin: true, text: '<strong>What changed at each site</strong>: <code>XSSFWorkbook</code> (in-memory) replaced with <code>SXSSFWorkbook(null, windowSize, false, false)</code>. Final flag <code>useSharedStringsTable=false</code> writes cells as <code>t="inlineStr"</code> rather than indexing into the per-workbook SST \u2014 sidesteps the O(N\u00b2) <code>SharedStringsTable.addEntry</code> behaviour that caused both the May 6 and Apr 22 OOMs. Streaming workbook keeps a rolling row window in memory; older rows are flushed to a temp file on disk. <code>workbook.dispose()</code> is called in <code>finally</code> to clean up those temp files.' },
            { badge: 'improve', admin: true, text: '<strong>Local benchmark</strong> on the same 10,196-row dataset that prod failed on: 1.1s vs 1.7s, heap delta <strong>-35 MB</strong> vs +245 MB during the export. Resulting <code>.xlsx</code> opens identically in Excel; the only on-disk difference is the empty 137-byte <code>sharedStrings.xml</code> stub.' },
            { badge: 'improve', admin: true, text: '<strong>One known follow-up not in this fix</strong>: <code>RejectionTrackerEmailService</code> still uses in-memory XSSF because its workbook contains chart sheets, which POI\'s streaming API does not support. Returns Tracker email volumes are bounded in practice, so this is a moderate-risk path \u2014 will revisit if it ever shows pressure.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Extensions tab: contributors (e.g. Vikas Singh) can now view + add their own extensions; delete is owner-scoped',
        items: [
            { badge: 'new', text: '<strong>Extension contributors now see the Extensions tab.</strong> Anyone granted contributor access via <code>/api/external-sources/permissions/contributors/&lt;username&gt;</code> sees the tab in their nav and can add new external sources via the file-upload form. Schedule and the contributor-management panel remain admin-only.' },
            { badge: 'new', admin: true, text: '<strong>Owner-scoped delete.</strong> Each newly-added external source records the AD username that created it (<code>addedByUsername</code>). Contributors can <em>only</em> delete sources they themselves added. Anyone else\u2019s source — or any pre-existing legacy source with no recorded owner — falls through to admin-only delete. The Remove button is hidden for non-owned rows; the row instead shows <em>owner: &lt;display name&gt;</em>.' },
            { badge: 'improve', admin: true, text: '<strong>Backend wiring</strong>: <code>ExternalSourceService.SourceDef</code> gained an <code>addedByUsername</code> field; <code>addSource()</code> now takes a 6th param (legacy 5-arg overload preserved). <code>UserPermissionsService.getAllowedTabs()</code> includes <code>extensions</code> for contributors. <code>DELETE /external-sources/{id}</code> now allows <code>admin || (contributor &amp;&amp; addedByUsername == session.username)</code>.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Ask AI bug reports now capture prior conversation + source path \u2014 enough to debug a graded F without re-running the question',
        items: [
            { badge: 'improve', text: '<strong>When you grade a C / D / F and provide an expectation, the resulting Bug Report in the Feedback Queue now includes the up-to-3 prior Q&A turns from the same session.</strong> Useful when the bad answer was a follow-up like <em>"of those, how many were 32GB?"</em> \u2014 without the prior block, the report had no record of what <em>"those"</em> referred to.' },
            { badge: 'new', admin: true, text: '<strong>Each Ask AI answer now carries a <code>source</code> tag</strong> indicating which code path produced it: <code>activity-log</code>, <code>data-query</code>, <code>help-docs</code>, <code>doc-list</code>, <code>column-metadata</code>, <code>unique-values-item-cache</code>, <code>unique-values-external</code>, <code>sku-lookup</code>, or <code>llm</code>. The source + latency is now shown on each Ask AI card (small grey line under the question) and is persisted into the Bug Report payload so a debugger can immediately see which interceptor or model branch to inspect.' },
            { badge: 'improve', admin: true, text: '<strong>Bug Report payload restructured</strong>: <code>QUESTION</code>, <code>MODEL</code>, <code>SOURCE</code>, <code>LATENCY</code>, <code>AI\u2019s ANSWER</code>, <code>USER\u2019s EXPECTATION</code>, plus the new <code>PRIOR CONVERSATION</code> block at the top when applicable. The activity log line for <code>AI_EVAL_USER_ASK</code> already carried <code>src=</code>, so the bug report and the activity log now agree on terminology.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Item Cache seed/delta \u2014 OOM no longer leaves the report stuck on \u201cRunning now\u201d forever',
        items: [
            { badge: 'fix', admin: true, text: '<strong>If the Item Cache full-seed (or delta) thread hits an <code>OutOfMemoryError</code>, the Utilities tile now correctly flips to <em>Failed</em> instead of staying on \u201cRunning now\u201d until the JVM restarts.</strong> The handler used to <code>catch (Exception e)</code>, but <code>OutOfMemoryError</code> is a <code>java.lang.Error</code>, not an <code>Exception</code> \u2014 so the catch never fired, <code>markBuiltinFailed</code> never ran, and the in-memory status sat at <code>running</code> indefinitely. Both <code>item-cache-seed</code> and <code>item-cache-delta</code> now <code>catch (Throwable t)</code> and record the actual exception class + message in the report status.' },
            { badge: 'improve', admin: true, text: '<strong>Local heap bumped from <code>-Xmx2g</code> to <code>-Xmx4g</code></strong> in <code>restart-local.sh</code> and <code>CLAUDE.md</code>. The full seed loads ~778K items + indexes \u2014 it OOMs reliably at 2g. Production already runs with a larger heap; this just brings local in line so smoke-tests don\u2019t crash the seed.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'Ask AI \u2014 newest Q&A on top, full interceptor chain (data queries / activity / docs), follow-up history',
        items: [
            { badge: 'fix', text: '<strong>The new question box now stays at the top of the Ask AI sub-tab.</strong> Each new Q&A renders directly below the input rather than at the bottom \u2014 no scrolling between asks. Old turns slide downward newest-first.' },
            { badge: 'fix', text: '<strong>Ask AI now answers data questions concretely instead of deflecting.</strong> Previously the sub-tab routed every question through the LLM-only eval entry point, so things like <em>"how many SKUs were created last week?"</em> got a hand-wave (\u201cI don\u2019t execute live SQL\u2026\u201d). Now Ask AI delegates to the same <code>/api/help/ask</code> flow the floating Help button uses \u2014 data-query interceptor, activity report (admin-only), help-docs search, SKU/external-source lookup, and finally the LLM. Real numbers come back.' },
            { badge: 'new', text: '<strong>Follow-up context.</strong> Each ask now includes the last 3 Q&A turns from the session in the LLM\u2019s history, so questions like <em>"of those, how many were 32GB?"</em> build on the prior answer. Interceptor branches don\u2019t need history; they answer fresh from data.' },
            { badge: 'improve', admin: true, text: '<strong>Model picker now scoped to LLM fallback only.</strong> If the question hits an interceptor (data query / activity / docs / SKU), the answer comes from that path regardless of which model the user picked. Only when the question falls through to the LLM does the picker take effect \u2014 via a new <code>modelOverride</code> body param on <code>/api/help/ask</code> that <code>callHaiku</code> honors. The activity log records <code>src=interceptor</code> vs <code>src=llm</code> so you can see which path each Ask AI question took.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'AI Eval \u2014 new \u201cAsk AI\u201d sub-tab open to all users; C/D/F grades with explanations auto-file as Bug Reports',
        items: [
            { badge: 'new', text: '<strong>New \u201cAsk AI\u201d sub-tab inside AI Eval, visible to every logged-in user.</strong> Pick a chatbot model (production default + the curated catalog), type a question, get an answer, grade it A / B / C / D / F. Anything you grade C / D / F prompts a quick \u201cwhat did you expect?\u201d modal. When you click Done, every C/D/F item with an expectation is auto-filed as a Bug Report in the Feedback Queue (same email + PT-#### flow as a manual feedback submission). The original eval-harness flow stays admin-only under a sibling \u201cRun Eval\u201d sub-tab.' },
            { badge: 'new', admin: true, text: '<strong>New endpoints</strong>: <code>POST /api/ai-eval/ask/question</code> (single Q\u2192A) and <code>POST /api/ai-eval/ask/submit-session</code> (collects graded items, files Bug Reports). Both require login but no admin role. <code>GET /api/ai-eval/models</code> dropped its admin gate \u2014 the catalog is needed for the picker. All other AI Eval endpoints (runs, results, validate, regrade, export) stay admin-only.' },
            { badge: 'improve', admin: true, text: '<strong>AI Eval tab is no longer admin-only in <code>TAB_CATALOG</code></strong> \u2014 it shows up in the tab nav for every user. Non-admins only see the Ask AI sub-tab; the Run Eval sub-tab button is hidden client-side and the underlying APIs return 403 if hit directly.' },
            { badge: 'improve', admin: true, text: '<strong>Each Ask AI question is logged as <code>AI_EVAL_USER_ASK</code></strong> in the activity log so you can see which users are exercising the chatbot.' }
        ]
    },
    {
        date: 'May 6, 2026',
        title: 'User Permissions \u2014 traffic-chart drilldowns are now PLM-admin-only (perms-admins see chart numbers but not per-user details)',
        items: [
            { badge: 'fix', text: '<strong>Permissions-admins (e.g. Vikas Singh) no longer see the per-user drilldowns under the User Permissions tab.</strong> They still see the 30-day login traffic chart with high-level numbers (total logins, peak unique users), and they can still manage tab visibility from the Users sub-tab. But they no longer get: clicking a bar to see who logged in that day with timestamps; clicking a chip to see what a user did during a session; or clicking a user name to see their AD profile + 7d/30d activity. Those affordances disappear from the UI for them and the underlying APIs return 403 if hit directly.' },
            { badge: 'improve', admin: true, text: '<strong>Three endpoints now strict-gated on <code>isPlmAdmin</code></strong>: <code>GET /api/permissions/stats/traffic/day</code>, <code>GET /api/permissions/stats/user/{u}/session</code>, <code>GET /api/permissions/stats/user/{u}/activity</code>. The <code>/traffic</code> chart endpoint stays open to perms-admins so they keep visibility into overall usage.' },
            { badge: 'improve', admin: true, text: '<strong>Frontend hides drilldown affordances</strong> for non-admin viewers: chart <code>onClick</code>/<code>onHover</code> handlers are not attached, "click any bar" hint text is suppressed in the chart summary, and user names in the user list render as plain text instead of clickable links.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'AI chat \u2014 "who/when last generated <report>" answers, ECN Report vs Returns Tracker disambiguation, REPORT_RUN sub-reports addressable individually',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Asking "who generated the ECN Report cycle time last time and when?" used to return Returns Tracker activity</strong> by mistake. The deterministic router conflated the two under a single <code>RETURNS</code> action filter, so ECN Report runs (logged as <code>ECN_REPORT_RUN</code> / <code>ECN_REPORT_DONE</code> / <code>ECN_REPORT_DOWNLOAD</code>) were never matched. Filter is now split: <em>ecn report</em> / <em>cycle time</em> \u2192 <code>ECN_REPORT_*</code>, <em>returns tracker</em> / <em>rejection tracker</em> \u2192 <code>RETURNS_*</code>. Legacy <em>"returns "</em> phrasing still works.' },
            { badge: 'new', admin: true, text: '<strong>"Last time / most recent / latest" headline</strong>: when the question is asking for the singular most-recent occurrence, the answer now leads with a one-line headline above the ranking table \u2014 <em>"Vikas Jindal last generated ECN Report on May 5, 6:03 PM."</em> followed by the action code and the original details. Triggers on <em>last time</em>, <em>most recent</em>, <em>latest</em>, <em>when was the last \u2026</em>, <em>when \u2026 last/recent</em>, and <em>"the last X"</em> when X is a noun (not a time unit). Questions without a time qualifier ("who has been using the ECN Report") keep the existing ranking-only behaviour.' },
            { badge: 'fix', admin: true, text: '<strong>"When was the last X" no longer falls back to the LLM with no data.</strong> The deterministic activity router used to gate on "who/which user" verbs only; "when" questions slipped through to the LLM which then guessed (or returned vague prose). The router now also accepts last-time intent. Default search window for these questions widens automatically from 24 hours to 90 days when the user hasn\u0027t pinned a window themselves \u2014 a report run from a few days ago still surfaces. Explicit qualifiers ("today", "this week", "the last 4 hours", "this month") override the widening.' },
            { badge: 'fix', admin: true, text: '<strong>System pseudonyms ("ECN Report", "Returns Tracker", "MonitorLog") no longer shadow real users in name-matching.</strong> Scheduled-job entries logged with <code>username="system"</code> were getting indexed as user names, so a question containing the report name would match the pseudonym and run a per-user report against the system entries (zero matches), instead of the intended report-name filter. The name index now skips <code>username="system"</code> rows.' },
            { badge: 'new', admin: true, text: '<strong>REPORT_RUN sub-reports are now individually addressable.</strong> The <code>ReportController</code> multiplexes several end-user reports onto a single <code>REPORT_RUN</code> action code (What\u2019s New digest, Activity Stats email, Dry Run, Item Cache seed/delta, Server Log download). The chatbot\u2019s action filter now supports an optional details-substring match, so questions like <em>"who triggered the last What\u2019s New digest"</em> or <em>"when was the last Item Cache delta"</em> route to the right slice instead of conflating all <code>REPORT_RUN</code> events.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'AI chat \u2014 \u201csuper intelligent\u201d on the activity log: tab-named questions, heaviest/quietest user, compute-intensive, free-form fallback with structured context',
        items: [
            { badge: 'new', admin: true, text: '<strong>The chatbot now answers tab-named activity questions</strong>: <em>"who used the AI Eval tab today?"</em>, <em>"who was on BOM Compare last week?"</em>, <em>"anyone using ECN Report this month?"</em>. Added a tab\u2192action map (AI_EVAL_*, BOM_*, FIELD_SEARCH, CHANGE_REVIEW_*, RETURNS_*, AGILE, SKU_LOOKUP, PERMISSIONS_*, AD_HEALTH_*, DEBUG_ANALYZE, FEEDBACK_*, etc.) so questions about specific tabs now get scoped reports.' },
            { badge: 'new', admin: true, text: '<strong>"Heaviest / busiest / most active" and "quietest" user questions</strong> now get a clear leaderboard. Heading reads e.g. <em>"Vikas Jindal was the most active in the last 24 hours with 245 actions."</em> followed by the full ranking. \u201cQuietest\u201d sorts ascending so dormant users surface first.' },
            { badge: 'new', admin: true, text: '<strong>"Compute activity" filter</strong>: questions like <em>"who did the most compute activity today?"</em> now scope to the LLM/DB-heavy actions (AI_DATA_QUERY, AI_EXT_LOOKUP, AI_EVAL_*, BOM_EXPLODE, BOM_IMPLODE, REPORT_RUN, DEBUG_ANALYZE, HISTORY_SEARCH).' },
            { badge: 'new', admin: true, text: '<strong>"And what they did" drilldown</strong>: when the question asks for the answer plus what each user did, the report adds a <em>Top actions</em> column listing each user\u2019s top 4 action types with counts. Resolves <em>"who logged in today and what did they do?"</em>.' },
            { badge: 'improve', admin: true, text: '<strong>Time windows</strong> extended: "yesterday" (last 48h), "this week" / "last week" (last 7 days), "this month" / "last month" (last 30 days). Default still 24h.' },
            { badge: 'new', admin: true, text: '<strong>LLM fallback with structured activity context</strong>: any activity-shaped question that doesn\u2019t cleanly match the deterministic router now reaches the LLM with a compact recent-activity summary in its system prompt \u2014 top users, top actions, logins-per-user, action\u2192tab taxonomy, and the last 60 events as a timeline. Lets the model answer fuzzy questions like <em>"is anyone using BOM Compare more than usual?"</em> or <em>"who\u2019s been quiet this week?"</em> with concrete data instead of guessing.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 login times now in YOUR timezone (from AD); admin-only \u201cExport activity log\u201d Excel button',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Login times in the per-day modal are now displayed in the viewer\u2019s local timezone</strong>, resolved from their AD <code>country</code> attribute. US viewers see <code>19:54 PDT</code>, Malaysia viewers see <code>10:54 MYT</code>, India viewers see <code>08:24 IST</code>, etc. Modal subtitle shows the active zone label. Underlying UTC anchors are still sent to the click-to-drill endpoint so session windows compute correctly across timezone boundaries. Country\u2192zone mapping covers US / Malaysia / India / China / Japan / Israel / UK / Germany / Korea / Singapore / Taiwan / Philippines / Thailand; unknown countries fall back to UTC.' },
            { badge: 'new', admin: true, text: '<strong>New \u201c\uD83D\uDCC2 Export activity log\u201d button</strong> visible only to PLM admins (top-right of the traffic-chart card). Click \u2192 pick from / to dates \u2192 download a comprehensive XLSX. <strong>Sheet 1 \u201cDaily Logins\u201d</strong>: per-day total + unique-users counts, bucketed in your AD timezone. <strong>Sheet 2 \u201cActivity Log\u201d</strong>: every event in range with timestamp (zoned), username, display name, action, details \u2014 frozen header row, broad columns. Endpoint: <code>GET /api/permissions/stats/export?from=YYYY-MM-DD&to=YYYY-MM-DD</code>. Strict <code>isPlmAdmin</code> gate (permissions-admins like Vikas Singh do not see the button or get a 403 if they hit the URL directly).' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 click any login timestamp to see what that user actually did during that session',
        items: [
            { badge: 'new', admin: true, text: '<strong>Each login-time chip in the per-day modal is now clickable.</strong> Click <code>02:54 UTC</code> next to a user\u2019s name and the row expands inline showing every activity-log entry that user generated between that login and the next (or +60 min if it was their last login of the day). Format: <code>HH:MM:SS \u00b7 ACTION \u00b7 details</code>. Lets you answer "what was Ravindar doing at 03:00?" with one click instead of grepping the activity log.' },
            { badge: 'new', admin: true, text: '<strong>New endpoint</strong> <code>GET /api/permissions/stats/user/{username}/session?fromIso=&toIso=</code> returns the windowed activity stream. Admin / permissions-admin only.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 click any bar / data point on the 30-day traffic chart to drill into that day\u2019s logins',
        items: [
            { badge: 'new', admin: true, text: '<strong>Click a bar (or the unique-users line dot) on the 30-day login traffic chart</strong> \u2014 a modal opens listing every user who logged in that day with their individual login timestamps. Format per user: <code>HH:MM UTC</code> chips for each session, plus a count. Sorted by most-active first. User names in the modal are clickable too \u2014 they jump straight into the existing AD profile + activity popover.' },
            { badge: 'improve', admin: true, text: '<strong>Cursor turns into a pointer</strong> when you hover a clickable bar/dot, so the affordance is discoverable. Header copy updated to <em>\u201cclick any bar for that day\u2019s logins.\u201d</em>' },
            { badge: 'new', admin: true, text: '<strong>Backend endpoint</strong> <code>GET /api/permissions/stats/traffic/day?date=YYYY-MM-DD</code> returns the per-user login breakdown for any date in the activity log window. Admin / permissions-admin only.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Returns Tracker \u2014 Stats sheet adds an \u201cECN # (Product Line)\u201d column next to the Reviewer column',
        items: [
            { badge: 'new', text: '<strong>New column E in the Stats sheet</strong> of the Returns Tracker Excel: <em>ECN # (Product Line)</em>. Same ECN ordering as column D (Reviewer who pushed back) for easy side-by-side reading. Format: <code>ECN-P000015203(PL-NAME), ECN-134721(PL-NAME)\u2026</code>. ECNs without a product line on file render with an em-dash placeholder. Resolves Noraida\u2019s ask \u2014 the requestor handle\u2192PL link is now visible without a separate pivot. Chart anchor shifted right by one column so it doesn\u2019t overlay the new column.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 newly-added users get an auto welcome invite email; manual \u201cResend invite\u201d button on every completed row',
        items: [
            { badge: 'new', admin: true, text: '<strong>Welcome invite email fires automatically</strong> the moment a pending DL request flips to <em>completed</em> AND the user has 0 logins. Sent <strong>To:</strong> the new user, <strong>CC:</strong> <code>pdl-plm-admin@sandisk.com</code> + the person who configured their tabs (looked up via LDAP). SanDisk-styled HTML body welcomes them by first name, lists the tabs they\u2019ll see, and has a big \u201cOpen PLM Toolkit\u201d CTA pointing at <code>app.maintenance.app-url</code>. Closes the loop \u2014 no more chasing the new user manually after IT adds them.' },
            { badge: 'new', admin: true, text: '<strong>Manual \u201c\u2709\ufe0f Send invite\u201d / \u201cResend invite\u201d button</strong> on every completed pending row. Green when no welcome has gone out yet; outlined when one has (tooltip shows last-sent timestamp + sender). Useful when the auto-trigger missed the window or the user lost the email. Endpoint: <code>POST /api/permissions/dl-request/{username}/welcome</code> (admin / permissions-admin only).' },
            { badge: 'improve', admin: true, text: '<strong>State persisted</strong> on each pending request: <code>welcomeSentAt</code> + <code>welcomeSentBy</code>. <code>system</code> for auto-trigger, the actor\u2019s AD username for manual sends. Survives restart so we don\u2019t double-send.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 Pending DL requests auto-close when the user is added to the DL (no need to wait for first login)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Pending DL requests now close as soon as the user shows up in the access DL roster</strong>, not just on first login. Each time you open the User Permissions tab, <code>UserPermissionsController.users</code> compares pending request usernames against the cached LDAP DL membership (<code>listAccessGroupCandidates</code>, ~1h TTL, plus the admin set). Any pending row whose target is already in the DL flips to <em>completed</em> in <code>data/user-permissions.json</code> and an activity entry <code>PERMISSIONS_DL_AUTO_COMPLETED</code> is recorded. Closes the loop after \u201c\u2192 Add to {group}\u201d \u2014 you click the AD self-service button, IT actually adds them, the next page-load drops them out of pending. Earlier behavior (close on first login via <code>notifyLogin()</code>) still works as a backstop in case the LDAP cache is stale.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 Pending DL row shows admins an \u201cAdd to access DL\u201d button instead of \u201cawaiting IT\u201d',
        items: [
            { badge: 'improve', admin: true, text: '<strong>The pending DL request row now reflects who\u2019s viewing.</strong> If you\u2019re a PLM admin (member of <code>pdl-plm-admin</code>), the row replaces the amber <em>awaiting IT</em> badge with a dark <code>\u2192 Add to {group}</code> button \u2014 same CTA the DL-request email carries, one click to open AD self-service pre-filled for the access group. Permissions-admins who can\u2019t do the add (e.g. Vikas Singh) still see the <em>awaiting IT</em> badge so the panel is honest about what they can act on. Closes the loop where IT had to dig the link out of the email instead of clicking it inside the app.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'BOM Compare \u2014 column picker labels harmonised across Two-Parts and Two-Revs modes; new \u201cSelect all / Clear all\u201d toggle',
        items: [
            { badge: 'improve', text: '<strong>Two-Parts and Two-Revs column pickers now use the same label for the same concept.</strong> \u201cItem Type\u201d \u2192 \u201cType\u201d in Two-Parts (matches \u201cType\u201d already used in Two-Revs). \u201cRev\u201d \u2192 \u201cComp Rev\u201d in Two-Parts (matches \u201cComp Rev\u201d already used in Two-Revs \u2014 both are the rev of the BOM-line component). Mode-specific columns (Status / Find # in Two-Parts; Comp Change / Seq # / Primary P/N in Two-Revs) keep their own labels because they refer to genuinely different fields. Resolves Vikas Singh\u2019s feedback.' },
            { badge: 'new', text: '<strong>\u201cSelect all / Clear all\u201d master checkbox</strong> at the start of both column pickers. Click once to check every column; click again to clear back to defaults. Indeterminate state when some are checked. Saves a chain of clicks when you want everything in the table.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Feedback Queue \u2014 LDAP email fallback, \u201cIn Progress\u201d status, dedicated Start button',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Mark-done modal\u2019s green button no longer stays stuck on \u201cSending\u2026\u201d</strong> after the first successful resolve. Bug was in <code>feedback-queue.js</code>: <code>feedbackQueueDoneOpen()</code> didn\u2019t reset the button label/disabled state, so the second item you opened showed a frozen <em>Sending\u2026</em> button before you\u2019d clicked anything. Now the button resets to <code>\u2713 Mark done &amp; send</code> on every modal open.' },
            { badge: 'fix', admin: true, text: '<strong>Resolve emails now find the reporter even when the queue row says \u201cno email on file.\u201d</strong> The activity-log importer never carried emails, and <code>UserPermissionsService</code> only knows about explicitly-managed users \u2014 so reporters like Vikas Singh (permissions-admin, no managed-tabs record) showed up with <code>reporterEmail=null</code>. Added a just-in-time <code>LdapAuthService.lookupUserByUsername(...)</code> fallback in <code>FeedbackResolveEmailService.sendReadyToTest()</code>: if the row has no email when you click \u201cMark done\u201d, we hit LDAP, fill the email, send the \u201cready to test\u201d notification, and persist the email back to the queue JSON so the row shows it next refresh. Also runs a one-time backfill at startup so existing items get cleaned up the moment the JAR boots.' },
            { badge: 'new', admin: true, text: '<strong>New \u201cIn Progress\u201d status</strong> between Open and Done, with a dedicated <code>&#9654; Start</code> button on every Open row. Click Start to flip an item to <em>in_progress</em> \u2014 records who started it (admin / IT member / the auto-poller running as <code>plmadmin</code>) and when. New filter pill in the queue and a separate sub-tab badge counts both Open and In Progress as \u201cneeds attention.\u201d Hard rule: cannot Start a Done or Dismissed item; reopen it first.' },
            { badge: 'new', text: '<strong>Reporters see when their request is being worked on.</strong> The user-name dropdown \u2192 \u201cMy feedback\u201d modal now shows an amber \u201c\u9654 In progress\u201d badge with a callout \u201c<em>Vikas Jindal is working on this.</em>\u201d once an admin clicks Start. Closes the loop where reporters had no signal between submitting and getting the \u201cready to test\u201d email.' },
            { badge: 'new', admin: true, text: '<strong>New endpoint</strong> <code>POST /api/feedback/queue/{ptId}/start</code>. Idempotent (clicking Start twice is a no-op). 409 if the item is already Done or Dismissed. Logged as <code>FEEDBACK_START</code> in the activity log so you can see who picked up what.' },
            { badge: 'improve', admin: true, text: '<strong>Mark-done from In Progress</strong> works like marking done from Open \u2014 same modal, same notify-reporter flow. The resolve email\u2019s \u201cMarked done by\u201d footer also reflects whoever did the final flip, even if a different admin had Started it earlier.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 30-day login traffic chart + clickable user names that pop up AD profile and personal activity',
        items: [
            { badge: 'new', admin: true, text: '<strong>30-day login traffic chart</strong> at the top of the User Permissions \u2192 Users sub-tab. Bars = daily logins, line overlay = unique users per day. Header shows total logins and peak unique-users count over the window. Built on Chart.js (already loaded), data from <code>activity-log.jsonl</code> via the new <code>GET /api/permissions/stats/traffic?days=30</code> endpoint. Empty days are pre-seeded so weekends visibly drop to 0 instead of being missing. Resolves Vikas Singh\u0027s feedback request.' },
            { badge: 'new', admin: true, text: '<strong>User names are now hyperlinks.</strong> Click any name in the User Permissions list to open a profile popover with: <strong>Department</strong>, <strong>Title</strong>, <strong>Manager</strong>, <strong>Office</strong> (city, country), and <strong>Email</strong> \u2014 fetched from AD via the existing <code>/api/auth/user-info/{id}</code> endpoint (4-hour cache so re-clicks are instant). Resolves Vikas Singh\u0027s feedback request.' },
            { badge: 'new', admin: true, text: '<strong>Personal activity drilldown</strong> in the same popover. Three KPI tiles: <strong>Logins last 7 days</strong>, <strong>Logins last 30 days</strong>, <strong>Total actions (30d)</strong>. Plus \u201cLast login: 3h ago\u201d (relative format) and a horizontal bar list of their 5 most-used features (Field Search, BOM Explore, AI Data Query, etc.) with usage counts. Tells you at a glance whether someone is dormant, occasional, or a power user \u2014 and what they actually do in the app.' },
            { badge: 'new', admin: true, text: '<strong>New REST endpoints</strong> under <code>/api/permissions/stats/</code>: <code>traffic?days=30</code> (1\u201390 days, daily login + unique-user buckets in UTC) and <code>user/{username}/activity?topN=5</code> (per-user 7d/30d/top-actions stats). Both gated to PLM admin or permissions-admin (same gate as the rest of the User Permissions tab).' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Feedback Queue \u2014 in-app feature requests / bugs / improvements now persist; one-click \u201cmark done\u201d emails the requestor',
        items: [
            { badge: 'new', admin: true, text: '<strong>New \u201cFeedback Queue\u201d sub-tab inside User Permissions.</strong> Every in-app feedback submission (the \uD83D\uDCAC button) now persists to <code>data/feedback-queue.json</code> with a sequential <code>PT-####</code> id. Three filter pills (Open / Dismissed / Done), each row renders the same card style as the feedback emails (eyebrow, serif title, From/Email/Category, attachment chip). On first run, the queue auto-imports any existing <code>FEEDBACK</code> rows from <code>activity-log.jsonl</code> with sequential ids and looks up reporter emails from the user-permissions cache.' },
            { badge: 'new', admin: true, text: '<strong>One-click \u201c\u2713 Mark done\u201d sends a SanDisk-styled \u201cready to test\u201d email to the original reporter</strong> (CC <code>pdl-plm-admin@sandisk.com</code>). Optional 1-line note field gets appended as a green callout (\u201cAdded under User Permissions \u2192 Traffic tab\u201d). If the imported item has no reporter email on file, the button shows \u201cno email \u2014 won\u0027t notify\u201d and just flips the status. New <code>app.feedback.email.outbound</code> flag (default <code>true</code>) lets local/dev configs disable real sends \u2014 the rendered HTML is logged instead, so you can test without emailing real users.' },
            { badge: 'new', admin: true, text: '<strong>\u201cDismiss\u201d action</strong> for items that won\u0027t ship (duplicates, by-design, won\u0027t-fix). Optional reason is recorded internally, no email is sent. Both Done and Dismissed items can be reopened from the queue.' },
            { badge: 'new', text: '<strong>\u201cMy feedback\u201d entry in the user-name dropdown</strong> (next to Visible Tabs). Any logged-in user sees a read-only modal with their own submitted items and the current status badge (Open / Done / Dismissed). If an admin left a note when marking it done, the note appears here too \u2014 reporters can verify the fix and reply.' },
            { badge: 'new', admin: true, text: '<strong>Attachments now persist</strong> to <code>data/feedback-attachments/&lt;PT-id&gt;/</code> instead of being dropped after the email send. Each row in the admin queue links to the saved file. Activity-log-imported items don\u0027t have attachments (those were never logged), but anything submitted from now on will.' },
            { badge: 'improve', admin: true, text: '<strong>Persistent <code>PT-####</code> id is now allocated up front</strong> in <code>SupportController.feedback</code> via <code>FeedbackQueueService.allocateNextId()</code>, then pinned into the outgoing admin-DL email\u0027s meta strip (overrides the random id <code>EmailTemplateService.wrap()</code> would otherwise generate). The id in your inbox now matches the id in the queue \u2014 you can grep for <code>PT-12</code> in either place.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Maintenance mode now also pauses scheduled emails (delta, what\u0027s-new, scheduled reports, weekly/monthly returns)',
        items: [
            { badge: 'fix', text: '<strong>Scheduled email jobs were still firing during maintenance mode</strong> \u2014 because <code>@Scheduled</code> methods run inside the JVM, not through the HTTP filter that intercepts UI traffic. Reported by Krati: she got her 1pm scheduled report while the maintenance page was up. Added <code>maintenanceService.isInMaintenanceMode()</code> early-return guards to the four user-visible schedulers: <code>ScheduledReportService.runDueSchedules</code> (every 5 min), <code>RejectionTrackerScheduler.weeklyEmail</code> (Mon 7am) + <code>monthlyEmail</code> (1st 7am), <code>DeltaReportService.sendDeltaReport</code> (9am + 9pm), <code>WhatsNewDigestService.sendDailyDigest</code> (10am). Each logs <em>"skipping &mdash; app is in maintenance mode"</em> and returns. Cache-refresh schedulers (<code>ItemCacheService</code>, <code>ChangeQueryService</code>, <code>BomExtractService</code>) keep running because they\u0027re internal and don\u0027t surprise users.' },
            { badge: 'fix', admin: true, text: '<strong>deploy.bat health probe rewritten in PowerShell.</strong> The original used <code>curl.exe</code> for the post-deploy HTTP check, which isn\u0027t on the PATH on the prod Windows server (\u201ccurl is not recognized as an internal or external command\u201d &mdash; reported running deploy.bat). Switched to <code>powershell -NoProfile -Command "try { (Invoke-WebRequest ...).StatusCode } catch { ... }"</code> which ships with every supported Windows. Also added a fallback: if 60s of HTTP probing fails but <code>netstat</code> shows port 8090 IS listening, log a warning + treat deploy as successful (the JVM is clearly up, the probe just couldn\u0027t verify). Prevents the misleading \u201capp didn\u0027t come up\u201d error when the app actually did.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'User Permissions \u2014 admins are now untouchable (can\u0027t be edited by Vikas Singh or anyone)',
        items: [
            { badge: 'fix', text: '<strong>Closed a privilege-escalation gap.</strong> Vikas Singh (permissions-admin allowlist) could open the Edit modal on a PLM admin and uncheck their non-admin tabs (e.g. ECN Report), effectively hiding tabs from someone who should always see everything. Three layers of fix: (1) the user list now renders a <strong>green PLM ADMIN badge</strong> + a disabled \u201c\uD83D\uDD12 Locked\u201d pill in place of the Edit button for admins. (2) <code>UserPermissionsService.upsertUser()</code> rejects with a clear error if the target is an admin (LDAP cache lookup via the new <code>LdapAuthService.listAdminUsernames()</code>). (3) <code>getAllowedTabs()</code> now ignores per-user records entirely when <code>isAdmin=true</code> \u2014 even a stale or hand-crafted record can\u0027t restrict an admin\u0027s view. Defense in depth: the request-add and DL-request flows hit the same upsert reject, so Vikas Singh can\u0027t sneak in an admin user via the Add-from-AD path either.' },
            { badge: 'improve', admin: true, text: '<strong>Admin users always appear in the user list now.</strong> Previously the list pulled from the access-DL <em>candidates</em> query (which explicitly excludes admins) plus the activity log. Admins who logged in showed up via the activity log, but admins who never logged in were invisible. Now we union the cached <code>listAdminUsernames()</code> set so every admin appears with the badge \u2014 visible but locked.' },
            { badge: 'improve', admin: true, text: '<strong>1-hour cached admin enumeration.</strong> <code>LdapAuthService.listAdminUsernames()</code> queries the <code>pdl-plm-admin</code> AD group once per hour and caches the lowercased sAMAccountName set. Same TTL as the candidates cache so they refresh on the same cadence.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'New tab: User Permissions \u2014 admin + Vikas Singh can curate per-user tab visibility and request DL adds',
        items: [
            { badge: 'new', admin: true, text: '<strong>New \u201cUser Permissions\u201d tab</strong> visible to PLM admins and to a tight permissions-admin allowlist (currently Vikas Singh, AD username <code>1000296585</code>; configured in <code>application.properties</code> as <code>app.permissions.allowed-users</code>). Lists <strong>every user</strong> in three buckets: (a) those who have logged in (cross-referenced from the activity log with last-login timestamp + login count), (b) DL members who have NEVER logged in, (c) per-user permission records (which can include people not yet in either bucket).' },
            { badge: 'new', admin: true, text: '<strong>Per-user tab visibility editor.</strong> Click \u201cEdit\u201d on any row to open a modal listing every tab in the app. Check the tabs the user should see, save. Behind the scenes, this writes to <code>data/user-permissions.json</code> and adds a <code>managedExplicitly:true</code> flag to that user. Users with no record see all non-admin tabs (current default behavior preserved).' },
            { badge: 'new', text: '<strong>HARD RULE: admin-only tabs are never grantable to non-admins, even by Vikas Singh.</strong> Admin-only checkboxes (Utilities, Extensions, AD Health, AI Eval, User Permissions itself) are rendered locked with a \uD83D\uDD12 lock icon in the editor. The rule is enforced server-side too \u2014 <code>UserPermissionsService.upsertUser()</code> filters admin-only tabs out of any allow-list it receives, so a crafted POST can\u0027t bypass it.' },
            { badge: 'new', admin: true, text: '<strong>Add user from AD typeahead.</strong> The \u201c+ Add user from AD\u201d button opens a modal with a search box that queries <strong>org-wide AD</strong> (not just the access DL) once you type 3 characters. Capped at 25 results, debounced 300ms, results show name + sAMAccountName + email + a \u201calready in DL\u201d badge if applicable. Pick a result, choose tabs, click \u201cSubmit request to IT\u201d.' },
            { badge: 'new', admin: true, text: '<strong>\u201cSubmit request to IT\u201d emails <code>pdl-plm-admin@sandisk.com</code></strong> with a leadership-grade HTML mail (IBM Plex, SanDisk palette, dark-mode meta, sandisk pill in footer) listing the user\u0027s name, AD username, email, the requested tabs, and who requested it. The permissions record is saved IMMEDIATELY (with the chosen tab list), so the moment IT adds the user to the access DL, their first login lands them on exactly the right tabs. IT\u0027s only job is the AD DL membership change.' },
            { badge: 'improve', text: '<strong>ECN Report tab is no longer admin-only by default.</strong> It\u0027s now grantable to non-admin business users (who use the Returns Tracker leadership view). Existing non-admins now see ECN Report by default; admins can hide it for specific users via the User Permissions tab if needed.' },
            { badge: 'improve', admin: true, text: '<strong>Auto-mark DL requests as completed on first login.</strong> When a user appears on the pending DL request list and then logs in for the first time, the request status flips from \u201cawaiting IT\u201d \u2192 \u201ccompleted\u201d automatically (no manual cleanup needed). A \u201cClear completed\u201d button removes them from the pending panel when the admin wants a tidy view.' },
            { badge: 'improve', admin: true, text: '<strong>Server-side enforcement</strong> via the new <code>/api/permissions/me</code> endpoint exposed in <code>/api/auth/session</code> as <code>allowedTabs</code>. Frontend\u0027s <code>applyServerTabPermissions()</code> hides any tab not in that set. Existing dynamic re-show paths (Reports for users with assigned reports, etc.) still work \u2014 they layer on top.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Returns Tracker \u2014 drop \"offender\" wording everywhere; \"Rejection Categories\" \u2192 \"Return to Pending Categories\"',
        items: [
            { badge: 'improve', text: '<strong>Business users hate the word \u201coffender\u201d</strong> \u2014 felt judgmental, especially when the same requestor was just doing their job and ran into a process gap. Removed every user-facing instance: AI executive narrative + anomaly callouts now use \u201crepeat requestors\u201d / \u201cfrequent requestors\u201d / \u201chigh-rework requestors\u201d (added an explicit TONE rule to the AI system prompt forbidding the word). HTML comments + JS doc comments updated for hygiene. Tooltip on the Repeat Requestors KPI tile reworded from <em>\u201c(Renamed from Repeat Offenders \u2014 same metric, less judgmental wording.)\u201d</em> to just describing the metric. App-knowledge.txt updated so the chatbot doesn\u0027t echo \u201coffender\u201d back at the user (vocabulary map still recognizes it as INPUT so we can match a question that uses the old wording).' },
            { badge: 'improve', text: '<strong>\u201cRejection Categories\u201d \u2192 \u201cReturn to Pending Categories\u201d</strong> on the dashboard chart header (index.html), in the leadership email section header, and in the Excel export\u0027s donut-chart title. The new wording matches how the workflow event is named in Agile and reads less negatively to non-technical readers.' },
            { badge: 'improve', text: '<strong>Python script change requires re-running Refresh to regenerate the AI narrative</strong> with the new tone (the cached <code>rejection-narrative.json</code> still has the old \u201coffenders\u201d wording until the next run). Or just delete the file before the next refresh to force regeneration.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Returns Tracker \u2014 UI refresh timeout bumped from 8 min to 15 min',
        items: [
            { badge: 'fix', text: '<strong>The \"Refresh timed out. Try Reload.\" message was firing on prod even when the Python script was still running and would complete successfully.</strong> Oracle SQL on the prod LAN has been taking 9-10 min to return rejection events lately (vs. <2s when healthy), so the 8-min UI poll cap was tripping before Phase 1 finished. Bumped to 15 min (300 polls \u00d7 3s) and reworded the timeout message: <em>\"Refresh timed out after 15 min. The script may still be running on the server \u2014 reload the page in a few minutes to see the latest data.\"</em> The 15-min cap covers worst-case Oracle slowness with margin while still bounding the spinner so it doesn\u0027t spin forever on a real failure. Server-side has no timeout on <code>p.waitFor()</code> so the actual upper bound is the Python <code>connection.call_timeout</code> (default 600s, prod has 1800s via env var). JS bumped to <code>?v=20260505a</code> for cache-busting.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Returns Tracker \u2014 Python rescan retroactively classifies cached self-returns',
        items: [
            { badge: 'fix', text: '<strong>The \"Returned By Requestor\" category was undercounted on prod (4 vs ~427 actual).</strong> The self-return rule (rejected_by name == requestor name \u2192 \"Returned By Requestor\", excluded from AI analysis) was added later in the project\u0027s life, but only ran on freshly-fetched events. Cached events from earlier runs were classified by AI before the rule existed, so they sat with stale categories like \"Insufficient Information\". Added a Phase 0 rescan that runs on every refresh and promotes any cached event whose <code>rejectedBy</code> name matches <code>requestor</code> name (after stripping the <code>(employeeId)</code> suffix). Idempotent (events already classified as \"Returned By Requestor\" are skipped) and cheap (just string compares, no AI calls, no DB queries). Confirmed on prod: promoted 427 cached events on the first run after deploy.' }
        ]
    },
    {
        date: 'May 5, 2026',
        title: 'Scheduled maintenance \u2014 nice humorous holding page now appears between shutdown and deploy',
        items: [
            { badge: 'new', text: '<strong>When a scheduled maintenance fires, users now see a friendly \u201cMaking your life better. Stay tuned.\u201d page</strong> instead of a connection-refused error or the watchdog\u0027s default behavior of bringing the OLD JAR straight back up. Flow: timer fires \u2192 JVM writes <code>data/maintenance-coming-back.json</code> flag \u2192 <code>System.exit(0)</code> \u2192 watchdog respawns the JVM \u2192 boot detects the flag is still there with no fresh JAR \u2192 enters maintenance mode \u2192 every page request is intercepted by <code>MaintenanceModeFilter</code> and served the static <code>maintenance-mode.html</code> page (auto-refreshes every 30s, shows a rotating witty subtitle from a pool of 13 quips).' },
            { badge: 'new', text: '<strong>The page automatically goes away when you deploy.</strong> On every JVM boot, init() compares the running JAR\u0027s mtime to the maintenance flag\u0027s mtime. If the JAR is newer, it means the admin actually copied a fresh build during the downtime \u2014 send the back-online emails, delete the flag, boot normally. If the JAR is older (auto-respawn without deploy), keep the flag and stay in maintenance mode. Means the user\u0027s existing deploy workflow needs zero changes \u2014 just <code>cp</code> the new JAR (which updates mtime) and the next boot picks normal mode automatically.' },
            { badge: 'new', admin: true, text: '<strong>Manual escape hatch: <code>POST /api/maintenance/exit-mode</code></strong> for allowlist admins (<code>vikasjindal</code>, <code>kratis</code>) to exit maintenance mode without a redeploy. Sends back-online emails, deletes the flag. Two ways to invoke: (a) <code>curl</code> with login session, or (b) <code>del data/maintenance-coming-back.json</code> directly on the file system \u2014 the <code>isInMaintenanceMode()</code> check stat\u0027s the file every request, so deletion is picked up live without a JVM restart.' },
            { badge: 'improve', admin: true, text: '<strong>API requests during maintenance return a structured 503</strong> with <code>Retry-After: 30</code> and <code>{"error":"PLM Toolkit is in maintenance mode","retryAfter":30}</code>, so any open browser polling (KPI tiles, monitor dashboards, etc.) sees a clean degradation instead of HTML-where-JSON-was-expected. Authenticated endpoints needed for the workflow stay open: <code>/api/auth/*</code>, <code>/api/maintenance/*</code>, <code>/api/monitor/*</code> (server-to-server), plus all static assets so the page can render.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'Scheduled-job Debug Assistant \u2014 fix Azure-AI 400 \"system: text content blocks must be non-empty\"',
        items: [
            { badge: 'fix', text: '<strong>Every scheduled-job alert that ran through the AI Debug Assistant was failing with HTTP 400 from Vortex.</strong> The Word doc emailed to the user (e.g. <code>DEBUG_UNKNOWN_*.docx</code>) showed \"AI analysis failed: Portkey/Vortex returned 400: azure-ai error: system: text content blocks must be non-empty\" in place of the actual root-cause analysis. Two callers (<code>DebugAssistantService.analyzeWithAI</code> and <code>MonitorAnalysisService.analyzeErrorWithAI</code>) pass <code>null</code> as the system prompt because everything they need is in the user message; <code>PortkeyClient.chatWithHistory</code> was emitting <code>{"role":"system","content":""}</code> regardless, and Azure\u0027s newer content-safety pipeline rejects empty system blocks (Anthropic + Vertex both ignored it, hence why this only broke now). Fixed by skipping the system block entirely when <code>systemPrompt</code> is null or empty \u2014 the request now sends just the user message, which is what the original design intended.' },
            { badge: 'fix', text: '<strong>Severity badge no longer shows \"MEDIUM\" on a failed AI analysis.</strong> The fallback in <code>extractSeverity</code> defaulted to MEDIUM when no severity tag was present, which made every \"AI analysis failed\" doc look like a real medium-severity event. Now those return <code>UNKNOWN</code> (rendered gray in the doc) and the keyword scan is skipped on failure strings so words like \"CRITICAL\" inside the failure text don\u0027t leak into the badge.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'Returns Tracker Excel \u2014 \"Stats\" sheet adds Unique-ECN count + ECN/Reviewer drilldown columns',
        items: [
            { badge: 'new', text: '<strong>Two new columns on the \"Stats\" sheet</strong>: <code>Count of Unique ECN#</code> (column C) and <code>ECN # (Reviewer who pushed back)</code> (column D). Column B (event count) and column C (distinct-ECN count) diverge whenever the same ECN is pulled back more than once \u2014 surfacing that gap directly answers \"is this requestor causing 29 separate problems, or 5 problems that keep bouncing?\". Column D drills into the actual ECN/reviewer pairs as <code>ECN1234(Reena Patel, Sunil Ramchandra), ECN223(Nik Hassan)</code>, so the user can see exactly which ECNs and which analyst(s) pushed each one back \u2014 no pivot needed.' },
            { badge: 'improve', text: '<strong>Reviewer names rendered as \"First Last\"</strong> inside the parens (matching how leadership reads names in conversation), even though the underlying data is stored \"Last, First (employeeId)\". The chart bars + column A still use \"Last, First\" so the chart axis stays sortable.' },
            { badge: 'improve', text: '<strong>Chart shifts right to column E</strong> so the new columns sit cleanly to its left without overlap. Column D wraps text and is set to 80 chars wide to fit longer ECN/reviewer lists.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'Returns Tracker Excel \u2014 \"Stats\" sheet now ranks Requestors (not Rejectors), self-returns excluded',
        items: [
            { badge: 'improve', text: '<strong>The \"Stats\" sheet in the Returns Tracker Excel export now counts how often each <em>Requestor\u0027s</em> ECN was pulled back to Pending</strong>, not how often each Rejector pushed something back. Counting rejectors mostly highlighted busy analysts (Patel, Reena = 54; Yahya, Redzuan = 47; etc.) which isn\u0027t actionable \u2014 of course the most active analysts reject the most. Counting requestors surfaces which submitters generate the most rework, which is what the dashboard\u0027s \"Top Repeat Requestors\" tile already shows. Excel now matches the dashboard.' },
            { badge: 'improve', text: '<strong>Self-returns excluded from the chart</strong> \u2014 events where the requestor pushed their own ECN back are tracked in the \"Returned By Requestor\" category instead, and listed as a count below the chart so the number isn\u0027t lost. Chart title updated to \"ECN Return Frequency by Requestor (Top 30) \u2014 self-returns excluded\"; axis labels now read \"Requestor\" / \"Times pulled back\".' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'Returns Tracker \u2014 \"Repeat Requestors\" rename + new \"Returned By Requestor\" / \"Unknown\" categories',
        items: [
            { badge: 'improve', text: '<strong>\"Top Repeat Offenders\" renamed to \"Top Repeat Requestors\"</strong> across the page header, the KPI tile, the status line, and the email digest. Same metric (requestors with \u22653 ECNs in the window), less judgmental wording.' },
            { badge: 'new', text: '<strong>New \"Returned By Requestor\" category</strong> for ECNs the requestor pushed back to Pending themselves (correcting their own work, not an analyst rejection). Detected at fetch time by comparing <code>ch.USER_NAME</code> to the originator\u0027s <code>USER_NAME</code> in the rejection-tracker SQL. These events are <strong>excluded from AI categorization, themes, narrative, and anomaly signals</strong> so the AI summary reflects real analyst pushbacks. A blue callout above the AI narrative tells the user how many ECNs were excluded; status line and email digest also surface the count.' },
            { badge: 'new', text: '<strong>New \"Unknown\" category</strong> when the AI can\u0027t pick a clear category (empty/generic comments, or two or more categories fit equally well). Previously every uncertain comment was force-fit into \u201cInsufficient Information\u201d, which polluted that bucket. The Claude prompt now explicitly says \u201cpick Unknown freely \u2014 accuracy beats coverage\u201d, and the post-validation defaults to \u201cUnknown\u201d for any unrecognised AI output.' },
            { badge: 'improve', admin: true, text: '<strong>Aggregator now persists <code>excludedFromAi</code></strong> count in the API response (alongside the existing per-category counts) plus a backwards-compatible <code>repeatRequestors</code> key. Old <code>repeatOffenders</code> key kept as an alias so any legacy callers don\u0027t break.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'Scheduled maintenance \u2014 allowlist admins can take the app offline with a 10-min warning',
        items: [
            { badge: 'new', admin: true, text: '<strong>New "\u26A0 Maintenance" link in the header</strong>, visible only to a tight allowlist (currently <code>vikasjindal</code>, <code>kratis</code>; configured in <code>application.properties</code> as <code>app.maintenance.allowed-users</code>). Click it, enter how many minutes (1\u201360, default 10) and an optional reason, and the system schedules a graceful shutdown.' },
            { badge: 'new', text: '<strong>Sticky red countdown banner appears at the top of every page</strong> as soon as a shutdown is scheduled (every browser polls <code>/api/maintenance/status</code> every 15s). Last 30 seconds: a full-screen modal pops up so users can\u0027t miss it. Banner shows who scheduled it, the optional reason, and a Cancel link for the allowlist admins.' },
            { badge: 'new', text: '<strong>"Going offline" email fires immediately on schedule</strong> to every user active in the last 30 minutes, so they\u0027re notified even if they close the browser. <strong>"Back online" email</strong> fires automatically when the app comes back up after restart \u2014 server writes a <code>./data/maintenance-coming-back.json</code> sentinel before <code>System.exit(0)</code>, then the boot path reads + emails + deletes it. Pending shutdowns also persist to <code>./data/pending-shutdown.json</code> so a JVM crash doesn\u0027t lose the schedule.' },
            { badge: 'improve', admin: true, text: '<strong>SessionRegistry tracks active users by lastSeen</strong> via the auth filter (touched on every authenticated request except the <code>/maintenance/status</code> poll itself), so the recipient list reflects who\u0027s actually using the app at the moment a shutdown is scheduled, not stale logins from yesterday.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'What\u0027s New digest email \u2014 unicode escapes no longer leak as literal text',
        items: [
            { badge: 'fix', text: '<strong>The hourly What\u0027s New digest email was rendering raw <code>\\u0027</code>, <code>\\u2014</code>, <code>\\u2713</code>, etc. as visible text</strong> instead of decoded characters (apostrophe, em-dash, check-mark). Reported by Vikas Singh from prod. Root cause: <code>WhatsNewDigestService.extractJsString</code> handled <code>\\\\</code>, <code>\\\'</code>, <code>\\"</code>, <code>\\n</code> but had no <code>\\uXXXX</code> branch, so the parser fell through and appended the literal six-character escape into the email HTML. Added a unicode-escape branch that consumes the 6-char sequence, parses the 4 hex digits, and appends the actual codepoint. Surrogate pairs (used for emoji like the regrade icon \ud83d\udd04) decode correctly because Java strings are UTF-16 and the parser emits each <code>\\uXXXX</code> char independently. The Whats New tab in the UI was always fine because browsers interpret JS escapes natively \u2014 only the server-side digest path was affected.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Help knowledge base \u2014 strategic / build-vs-buy context + drop "Western Digital" branding',
        items: [
            { badge: 'fix', text: '<strong>AI Help can now answer CIO-level "why did you build this?" questions.</strong> A simulated CIO persona asked "Why did the team build this instead of buying an existing PLM analytics product?" and the chatbot honestly said "the knowledge base doesn\u0027t document that". Added a WHY THIS TOOL EXISTS section to <code>app-knowledge.txt</code> covering build-vs-buy rationale (deep Agile coupling, speed of iteration, no per-seat licensing), differentiators vs generic PLM analytics (live SDK queries, admin self-service, AI features, one-click email-me), ROI signal (activity log volume; replaces ~5 ad-hoc spreadsheets), and audience (PLM IT, change analysts, BOM owners, MFG engineering, supplier-quality, finance/CCB).' },
            { badge: 'fix', text: '<strong>Removed "Western Digital" from the OVERVIEW.</strong> Per the global SanDisk naming policy, all user-facing content should say SanDisk only. The KB previously read "Agile PLM data at SanDisk/Western Digital" and the chatbot was repeating that phrasing back to users. Now reads "at SanDisk".' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 chatbot now respects PERSONA admin status, not session user (admin-only)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>AI Help was being told the SESSION user\u0027s admin status during eval runs, not the PERSONA\u0027s.</strong> A simulated "Peer engineer · Operations · Daily user" persona asked "I\u0027m not in the PLM admin group \u2014 what reports can I still create myself?" and AI Help opened with "As a PLM admin, you actually have full access to everything..." because the prompt was hard-coded to <code>isAdmin=true</code> (the real session user, Vikas, is admin). The chatbot then answered from the wrong perspective and got an F. Now <code>AiEvalService</code> derives admin status from the persona instead: simulated-real-person personas use the AD <code>realIsPlmAdmin</code> flag (Kathy Ashe = non-admin, Bret Harman = admin, etc.); abstract-bucket personas default to NON-admin so the eval tests the harder/more common case by default. Export brief snapshot now uses the same logic so the system-prompt block reflects what AI Help actually saw.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Help knowledge base \u2014 ECN Report and Help Center tabs now documented',
        items: [
            { badge: 'fix', text: '<strong>AI Help can now answer questions about the ECN Report and Help Center tabs.</strong> The knowledge base self-reported 13 tabs, but the actual nav bar has 15 \u2014 ECN Report and Help Center were entirely missing. As a result, the chatbot kept saying "there is no ECN Report tab" and deflecting users to Change Reviews / Change History. Bumped the OVERVIEW count 13 \u2192 15, added both tabs to TABS VISIBILITY and ADMIN FEATURES, replaced the wrong "no such tab" entry in USER VOCABULARY with the actual mapping, and added two full TAB sections: <strong>TAB 13: ECN REPORT</strong> covering Cycle Time view (6-card KPI grid, SLA targets + profiles, ECN Data table with Δ Days quick-filter pills, Show-all-statuses toggle, 2-sheet Excel download) and Returns Tracker view (date range, AI executive narrative, AI anomalies, rejection categories, top product lines, daily trend, repeat offenders, AI-clustered top themes, drill-down panel, recipients management, weekly Monday + monthly 1st auto-emails); and <strong>TAB 14: HELP CENTER</strong> covering full-text search across uploaded PPTX/PDF/XLSX/DOCX/TXT/CSV documents with admin upload zone. Renumbered AI Eval to TAB 15 + updated back-references.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Help knowledge base \u2014 vocabulary map for "ECN report" / "open changes" / "disposition" / etc.',
        items: [
            { badge: 'fix', text: '<strong>AI Help can now answer change-order questions in user vocabulary.</strong> A 10-question Gemini-Tester eval scored avg C with 7 of 10 failures \u2014 every failure was a question phrased around the (non-existent) "ECN Report tab", "open changes", "disposition", "manufacturing-impacting changes", "save my search", "back to default view". The chatbot couldn\u0027t map those phrases to the actual tabs (Change Reviews, Change History) so it deflected or guessed wrong. Added a USER VOCABULARY \u2192 TAB MAP section right under TABS VISIBILITY in <code>app-knowledge.txt</code> that explicitly says "ECN Report / Change Report \u2014 there is no tab literally called this; pick based on intent" and maps Change Reviews vs Change History vs Field Changes vs Dashboard mode to the typical asks. Added concrete entries for "disposition" (= Change Status column), "open" (= anything not Released, i.e. everything visible in Change Reviews), "manufacturing change" (= MCO type), "last quarter" (= Dashboard 90-day lookback). Plus a "FINDING A SPECIFIC ECN BY NUMBER" + "RETURNING TO DEFAULT VIEW" section so the chatbot stops giving generic "refresh the page" advice.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 chatbot no longer assumes the user is on the AI Eval tab (admin-only)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Eval-mode framing for the AI Help system prompt.</strong> Every eval question was being asked with <code>currentTab=\'ai-eval\'</code> in the system prompt because that\u0027s where the harness runs from \u2014 so when the persona goal was <em>"ask about the ECN report tab"</em>, the chatbot would still answer through the AI-Eval lens (e.g. "Since you\u0027re on the AI Eval tab..."), and the evaluator correctly graded those answers down for "Mentions the wrong tab". Fix: <code>askAiHelpForEval</code> now sets a new eval-mode flag that swaps the "user is currently on the X tab" sentence for "this is an evaluation question \u2014 use the knowledge base to identify which tab the question is about; do NOT assume the user is on any particular tab", plus optionally appends the persona\u0027s stated goal as soft context. Production AI Help (the chat drawer) is untouched. Export brief now snapshots the eval-mode prompt with the run\u0027s goal so what you hand to Claude matches what AI Help actually saw.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 Tester (Gemini 2.5 Pro) no longer truncates the question array (admin-only)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Tester model now gets a per-model + per-question-count token budget</strong> instead of a flat 1500. A 5-question Gemini 2.5 Pro run failed with "Tester returned malformed JSON twice: <code>\u0060\u0060\u0060json [...]</code>" because Gemini burns most of its budget on hidden chain-of-thought before producing visible output (verified earlier with the evaluator), and 1500 tokens left only ~150 visible tokens \u2014 enough for the opening <code>[</code> but not enough to close the array. New <code>budgetForTester(model, questionCount)</code> scales: <em>visible = max(5, count) * 80 + 400</em>; non-Gemini cap floor 1500, Gemini cap floor 4000 + 2500 reasoning overhead.' },
            { badge: 'fix', admin: true, text: '<strong>JSON parser now handles single-line markdown code fences</strong> too. Old code stripped the opening fence by finding the first newline \u2014 if Gemini emitted <code>\u0060\u0060\u0060json [..] \u0060\u0060\u0060</code> on a single line (no newline after <code>\u0060\u0060\u0060json</code>), the strip silently no-op\u0027d and parsing failed. Now we strip the fence regardless of newlines, drop any leading language tag (<code>json</code>/<code>JSON</code>), and as a last resort hunt for the first <code>[</code> and last <code>]</code> in the remaining text.' },
            { badge: 'improve', admin: true, text: '<strong>Tester failure message is now actionable</strong> ("Try a different tester model, or rerun \u2014 raw output logged server-side") instead of dumping the first 200 raw chars into the UI banner. The full raw response is now logged via WARNING for diagnosis without leaking it to the page.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval Regrade \u2014 "Re-ask AI Help" checkbox for fair after-prompt-edit retests (admin-only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>New "Re-ask AI Help (don\u0027t reuse parent answers)" checkbox</strong> in the Regrade modal, visible only when you keep the original chatbot. Use case: you edited the AI Help system prompt or knowledge base since the parent run and want a fair before/after comparison \u2014 the chatbot model is unchanged, but its inputs aren\u0027t, so the previous answers are stale. Checking the box forces AI Help to re-run on each question (slower \u2014 same path as a chatbot swap) instead of reusing parent answers. Default off so the fast pure-regrade path remains the default. Banner adapts: "AI Help <strong>re-asked</strong> with the same chatbot X (so any prompt or knowledge-base edits since the parent run take effect)..." Backend persists a <code>forceRerunAiHelp</code> flag on RunConfig so the banner can detect this case after a page reload.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 regrade banner now reflects what actually changed (admin-only)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Regrade-row banner used to always say "Same N questions + AI Help answers, regraded with X instead of Y"</strong> \u2014 even when the chatbot was swapped (in which case AI Help re-ran and the answers are NEW, not reused) or when the evaluator stayed the same (in which case the X-vs-Y comparison collapsed to "X instead of X"). Now the banner adapts: <em>chatbot-only swap</em> reads "Same N questions, but AI Help re-answered with chatbot X (was Y), then graded with the same evaluator Z"; <em>evaluator-only swap (pure regrade)</em> reads "Same N questions + AI Help answers (chatbot Z), regraded with X instead of Y"; <em>both changed</em> reads "Same N questions, but AI Help re-answered with chatbot X (was Y), then graded with Z (was W)".' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval Regrade \u2014 Tester no longer blocks Chatbot/Evaluator picks (admin-only)',
        items: [
            { badge: 'fix', admin: true, text: '<strong>Regrade modal no longer greys out the parent\u0027s Tester slug</strong> in the Chatbot or Evaluator pickers. The Tester ran during the original run to write the questions; on regrade those questions are frozen and the Tester doesn\u0027t run again, so its slug being unavailable was a leftover from the main Configure form\u0027s constraint that doesn\u0027t apply here. Only Chatbot \u2260 Evaluator still matters (so a model isn\u0027t grading itself). Now you can regrade with the same Sonnet 4.6 chatbot that was used as Tester in the parent run \u2014 useful when you want to A/B "what if AI Help ran on the same model that wrote the questions?". Backend constraint loosened to match.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Help knowledge base \u2014 AI Eval tab now documented',
        items: [
            { badge: 'fix', text: '<strong>AI Help can now answer questions about the AI Eval tab itself.</strong> Previous knowledge base had zero coverage of the AI Eval tab, so the bot deflected every question (3-model picker, duplicate-model behavior, selection persistence, regrade flow) to <code>pdl-plm-admin@sandisk.com</code>. Added a full TAB 13: AI EVAL section to <code>app-knowledge.txt</code> covering the 3-model picker (how to open, where it lives, defaults), the live duplicate-prevention behavior, what happens on page reload, the run flow, past-runs table, regrade flow with chatbot swap, override-grade flow, and Excel export. Also bumped the OVERVIEW tab count 12 \u2192 13 and added AI Eval to TABS VISIBILITY + ADMIN FEATURES. Discovered via the Export-failures-to-MD flow on a 5-question run that scored avg C with 3 D-grade failures \u2014 all 3 failures were "I don\u0027t have docs for that" deflections.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 Regrade modal upgraded: catalog + custom slugs + chatbot swap (admin-only)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Regrade modal now uses the full model catalog</strong> instead of the 3 hardcoded radios. You get every Anthropic / OpenAI / Google slug + a "Custom\u2026" option (with the same Validate-button flow as the Run config form). The Tester model is shown read-only since it\u0027s inherited from the parent run.' },
            { badge: 'new', admin: true, text: '<strong>Regrade can also swap the chatbot</strong>. New <em>Chatbot</em> dropdown defaults to "Keep original chatbot" (pure regrade \u2014 same Q/A, only the Evaluator runs, original V3 behavior). Pick a different chatbot and the system re-runs AI Help with the new model on each question, then grades the new answers. Lets you ask "what would these same persona questions look like if AI Help ran on Opus 4.7 instead of Sonnet 4.6?" without leaving the Regrade flow.' },
            { badge: 'improve', admin: true, text: '<strong>Modal copy adapts to the choice</strong>: "only Evaluator runs" when chatbot stays the same, vs "re-runs AI Help with the new chatbot, then grades" when it changes. Same constraint check as the main form: Chatbot &ne; Tester &ne; Evaluator.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 tester in Models, Excel export, AI latency column (admin-only)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Models column now shows all three models per row</strong> \u2014 chat / test / eval (was: just chat + eval). The Tester model is now visible at a glance without expanding the row.' },
            { badge: 'new', admin: true, text: '<strong>"\u2193 Export all (.xlsx)" button</strong> next to the Past Runs heading downloads the full history as Excel with two sheets: <em>AI Eval Runs</em> (one row per run \u2014 Date, Created by, Persona, Chatbot, Tester, Evaluator, Q\u0027s, Avg grade, Avg numeric, Fails, Avg AI ms, Total ms, \u0394 vs prior, Status, Regrade of, Run ID) and <em>Questions</em> (one row per question across all runs \u2014 Run ID, Q#, Question, Answer, Grade, Reason, Answer/Grade ms, plus override audit fields). Join the two sheets on Run ID. Drop into Excel + Claude Copilot to ask things like "which question topics fail most often?" or "is there a latency-vs-grade correlation?".' },
            { badge: 'new', admin: true, text: '<strong>New "Avg AI" column</strong> in Past Runs shows the mean AI Help answer latency across non-error questions per run \u2014 e.g. <code>4.5s</code> or <code>850ms</code>. Same value also lands in the Excel export so you can chart latency-vs-grade across runs. Per-question latencies were already captured; this just surfaces the average.' },
            { badge: 'fix', admin: true, text: '<strong>Regrade rows now show the regrade label correctly in the list view</strong>. The list-view shallow-copy was dropping <code>regradeOfRunId</code>, so all regrade rows would render as plain runs. Fixed in <code>AiEvalService.shallowCopyForList</code>.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 pick the chatbot model + custom Vortex slugs (admin-only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>You can now choose which model AI Help uses for an eval run</strong>. The form now has three model pickers \u2014 Chatbot, Tester, Evaluator \u2014 instead of two. Default for Chatbot is "Production default (Claude Sonnet 4.6)" so prod AI Help behavior is unchanged. Pick any other slug and that run will route AI Help through the new model just for this eval. Lets you ask "what would users see if AI Help ran on GPT-4o instead?" without touching prod config.' },
            { badge: 'new', admin: true, text: '<strong>Custom slug entry on every dropdown</strong> via a new "Custom\u2026" option. Pick it and a text input + Validate button appears. The validator fires a real 1-token "ping" call against the slug and reports reachability + latency (green check) or the actual error (red X) so you know it works before kicking off a 90-second run. The slug is locked-in until you edit the text \u2014 changing the input forces a re-validate.' },
            { badge: 'improve', admin: true, text: '<strong>Curated model catalog</strong> served from <code>GET /api/ai-eval/models</code> covers ~8 known-good Vortex slugs (Anthropic Sonnet 4.6 / Opus 4.7 / Haiku 4.5; OpenAI GPT-4o / GPT-4o-mini / GPT-4-turbo; Google Gemini 2.5 Pro / Flash). Edit one map in <code>AiEvalController.listModels()</code> to add more.' },
            { badge: 'improve', admin: true, text: '<strong>Past Runs table now shows both models per row</strong> \u2014 chat: <em>(prod default | override slug)</em> over eval: <em>slug</em>. Override runs get a purple <code>\u2731</code> prefix so you can spot non-default chatbot runs at a glance.' },
            { badge: 'fix', admin: true, text: '<strong>Constraint check tightened</strong>: Chatbot, Tester, and Evaluator must all be different from each other (was: only Tester \u2260 Evaluator). Enforced on both backend and frontend; conflicting catalog options grey out as you change selections.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval \u2014 download export, fix login counts, cleaner errors (admin-only)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>Export button now downloads the markdown brief to your browser</strong> instead of writing it to the server\u0027s filesystem. Click Export on any past run \u2014 the <code>eval-&lt;runId&gt;.md</code> file lands in your Downloads folder, ready to drag into VS Code or attach to Claude. Removes the need to SSH into prod or mount a network volume just to read the brief.' },
            { badge: 'fix', admin: true, text: '<strong>Persona inference now sees historical login counts</strong>. The Simulate-real-person card was showing <em>Logins (90d): 0 \u00b7 Total: 0</em> for users who clearly had logged in before, because <code>ActivityLogger.getActivitiesSince()</code> short-circuited on the in-memory cache (last 7 days only after restart) and never read the JSONL file when in-memory had even one entry. Now the in-memory shortcut is only used when the requested window fits inside the cache; otherwise the full file is read. Fixes login counts for everyone whose recent activity predates the last server restart.' },
            { badge: 'fix', admin: true, text: '<strong>Evaluator-failure reason no longer dumps raw model output</strong>. When the evaluator returned malformed JSON, the message used to splice in the first 200 chars of the raw response, which sometimes contained literal <code>\\u0027</code> (escaped apostrophes from Gemini) and stray code-fence markers. Now shows a clean message (<em>"Evaluator returned malformed JSON. Try a different evaluator model or rerun."</em>) and the raw text is logged server-side for debugging.' },
            { badge: 'fix', admin: true, text: '<strong>Helper text under "Simulate real person" now renders properly</strong>. The <code>\\u0027</code> escape (apostrophe in <em>"real person\u0027s voice"</em>) was a JS string literal mistakenly placed inside HTML \u2014 browsers render those literally. Replaced with HTML entity <code>&amp;#39;</code>. Same fix for the <code>\\u2026</code> ellipsis in the user-search and utility-search placeholders.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval V3 \u2014 Regrade with a different evaluator (admin-only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>\ud83d\udd04 Regrade button</strong> on every past-runs row. Click it, pick a different evaluator (the original evaluator and the Tester are greyed out), and the system reuses the SAME questions and AI Help answers from the original run \u2014 only the new Evaluator runs. Lets you compare evaluator calibration on identical Q/A: did Gemini grade these the same as GPT-4o? Faster too \u2014 skips question generation and AI Help calls (~40% less wall-clock).' },
            { badge: 'improve', admin: true, text: '<strong>Past runs table</strong> now shows a <span style="font-family:\'IBM Plex Mono\',Consolas,monospace;">\ud83d\udd04</span> prefix on regrade runs so you can spot them at a glance vs fresh runs.' },
            { badge: 'fix', admin: true, text: '<strong>Gemini-2.5-pro evaluator now produces real grades</strong> instead of all-ERR. Gemini is a reasoning model \u2014 burns ~95 hidden chain-of-thought tokens before producing visible output. Previous 600-token cap left only ~10 tokens for the actual JSON, which truncated to <code>\u0060\u0060\u0060json</code> and parsed-failed. Bumped Vertex/Gemini budget to 4000 tokens; Anthropic + OpenAI unaffected at 600.' }
        ]
    },
    {
        date: 'May 4, 2026',
        title: 'AI Eval V2 \u2014 override grades + simulate real users (admin-only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>Override any grade</strong>. The AI evaluator sometimes gets it wrong; you can disagree and overwrite. Click <em>\u270e edit grade</em> on any graded card (live or past-runs expand-row) \u2014 popover lets you change A/B/C/D/F + reason + an optional note explaining why. The original AI grade + reason are preserved (audit trail) and visible on hover of the new <strong>\ud83d\udc64 You</strong> badge. Run summary (avg + failure count) recalculates immediately.' },
            { badge: 'new', admin: true, text: '<strong>Simulate real person</strong>. Below the Tester block, check <em>Simulate real person</em> and pick a real PLM Toolkit user. The form auto-fills from their AD profile (title, department, account age) plus their actual usage history (login count, last seen, PLM admin status). The Tester model gets the verbatim demographics so it generates questions in that person\u0027s actual voice \u2014 e.g. a hands-on senior PLM admin asks operational/edge-case questions; a 6-month-tenure analyst asks discoverability questions. Picker has two modes: just-past-loggers (default, smaller list) and search-all-AD-users (toggle).' },
            { badge: 'improve', admin: true, text: '<strong>Past runs table</strong> shows simulated runs as <code>\ud83e\uddd1 Vikas Jindal (Director \u00b7 PLM IT)</code> instead of the abstract bucket. Rerun on a simulated row re-fetches the inference (in case AD or activity has changed since the original run).' },
            { badge: 'improve', admin: true, text: '<strong>LdapAuthService</strong> now exposes <code>whenCreated</code> as ISO-8601 <code>accountCreatedDate</code> on UserInfo \u2014 used to compute &ldquo;account age&rdquo; for persona inference (and available to any other feature that wants it).' }
        ]
    },
    {
        date: 'May 3, 2026',
        title: 'AI Eval tab \u2014 grade the chatbot with a different model (admin-only)',
        items: [
            { badge: 'new', admin: true, text: '<strong>New AI Eval tab</strong> for admins. Configure a Tester persona (role, team, experience, goal) and an Evaluator model, click <em>Run eval</em>, and the Tester auto-generates 5\u201350 questions for that persona, fires them at the AI Help chatbot, and the Evaluator grades each answer A\u2013F with a one-sentence reason. Live progress streams via SSE; runs persist to <code>./cache/ai-eval-runs.json</code>. Past runs table shows \u0394-grade vs the prior run with the same config so you can see whether a fix actually helped.' },
            { badge: 'new', admin: true, text: '<strong>Three Vortex providers</strong> available for both Tester and Evaluator: Claude Sonnet 4.6 (<code>@anthropic-eastus2</code>), GPT-4o (<code>@openai-eastus2</code>), Gemini 2.5 Pro (<code>@vertexai-global</code>). Tester and Evaluator must use different models \u2014 enforced in both UI and backend.' },
            { badge: 'new', admin: true, text: '<strong>\u201CExport for Claude\u201D button</strong> on every past run writes a focused failure-only markdown brief to <code>./debug-output/eval-latest.md</code>. Open it in VS Code and ask Claude to review \u2014 the brief includes the run config, the AI Help system prompt snapshotted at export time, and the failed Q/A/grade/reason rows so Claude can identify systemic issues to fix.' },
            { badge: 'improve', text: '<strong>PortkeyClient refactor</strong> \u2014 the duplicated <code>HttpURLConnection</code> + Vortex-call code that lived in 7 service classes is now one helper. No behavior change for existing AI features; AI Help, Returns Tracker, Delta Report, Monitor Analysis, Reports, Debug Assistant, and What\u0027s New all share the same code path.' },
            { badge: 'fix', admin: true, text: '<strong>OpenAI parameter quirk handled</strong> \u2014 Azure OpenAI\u0027s newer chat models reject <code>max_tokens</code> and require <code>max_completion_tokens</code>. PortkeyClient now picks the right parameter name based on the model slug, so GPT-4o evaluations actually run instead of returning HTTP 400 on every call.' }
        ]
    },
    {
        date: 'May 2, 2026',
        title: 'AI gateway \u2014 fully cut over to SanDisk Vortex (admin-only)',
        items: [
            { badge: 'improve', admin: true, text: '<strong>All seven AI-powered features now route through SanDisk Vortex</strong> (<code>ai.vortex.sandisk.com</code>) instead of the public Portkey gateway, so token usage shows up in Vortex analytics. Covered: AI Help drawer, Returns Tracker AI categorization + narrative + drill-down, Delta Report AI summary, What\u0027s New AI intro, Monitor Analysis, Reports utility env-var detection, Debug Assistant. Same Portkey key, same headers, same model \u2014 only the host changed. Externalized via <code>portkey.base-url</code> config (default falls back to <code>api.portkey.ai</code> if unset, so this JAR is safe to deploy without the config line). Per Basu Tripathy\u0027s ask.' }
        ]
    },
    {
        date: 'April 30, 2026',
        title: 'BOM Explorer \u2014 Root SKU column',
        items: [
            { badge: 'improve', text: '<strong>New "Root SKU" column</strong> on the BOM Explorer table and Excel export, sitting right after Level. Shows which input SKU the row traces back to \u2014 essential when exploding multiple SKUs at once. Filter or sort by Root SKU to isolate one SKU\u0027s full bill of materials in a multi-SKU explosion. Per Vikas Singh\u0027s feedback (couldn\u0027t find resistors per SKU because column B was the immediate parent subassembly, not the input SKU).' }
        ]
    },
    {
        date: 'April 30, 2026',
        title: 'Item Number paste \u2014 from Excel / text file just works',
        items: [
            { badge: 'improve', text: '<strong>Paste from Excel directly into any Item Number(s) input</strong> \u2014 The five Item Number fields (BOM, Parts, Agile Lookup, Change History, SKU) now accept comma, space, or new-line separators. Copy a column from Excel, paste, hit Search. The input auto-normalizes to comma-separated form on paste and on blur. Per Vikas Singh\u0027s feedback.' },
            { badge: 'improve', text: '<strong>Backend split hardened</strong> \u2014 All five item-list endpoints now split on whitespace, commas, or semicolons (was comma-only). Defense-in-depth in case any caller bypasses the frontend normalizer.' }
        ]
    },
    {
        date: 'April 30, 2026',
        title: 'ECN Report \u2014 Returns Tracker (with AI)',
        items: [
            { badge: 'new', text: '<strong>Returns Tracker view</strong> \u2014 Toggle inside the ECN Report tab between <em>Cycle Time</em> (existing) and <em>Returns Tracker</em> (new). Returns view tracks every rejection event (any status \u2192 Pending) over a chosen window (default 90 days; presets 7d / 30d / 90d / This Quarter / Custom). Per Noraida Nazri\u0027s ECN-135232-PROJ.' },
            { badge: 'new', text: '<strong>AI categorization of every rejection comment</strong> \u2014 Each rejection comment is auto-categorized into one of 5 fixed buckets (Insufficient Information / Incomplete Documentation / Ambiguous Request / Wrong Information / Duplicate Request) plus a short AI-generated theme phrase ("missing PCN commit date", "wrong product line", etc). Sourced from the COMMENTS field in change_history.' },
            { badge: 'new', text: '<strong>AI executive narrative + anomaly callouts</strong> \u2014 Top of the dashboard shows a 1-2 paragraph AI-written summary of what stood out this period, plus 0-4 anomaly callouts (week-over-week category spikes, new emerging themes, new top-5 repeat offenders).' },
            { badge: 'new', text: '<strong>AI per-requestor coaching</strong> \u2014 Top Repeat Offenders table includes AI-generated <em>pattern</em> ("PCN dates missing 8/12 times") and <em>suggested action</em> per requestor.' },
            { badge: 'new', text: '<strong>Drill-down side panel</strong> \u2014 Click any rejection event row \u2192 right-side panel slides in with full comment, AI explanation in plain English, and rejection metadata. Closes via the X button.' },
            { badge: 'new', text: '<strong>Auto weekly + monthly emails</strong> \u2014 Mondays at 7am SGT (prior 7 days) and 1st of month at 7am SGT (prior calendar month) auto-send a leadership email to Jimmy Sessumes, Noraida Nazri, Kathy Ashe, Andy Kuver, Aiyappa Cheppudira. Manage recipients via the gear icon.' },
            { badge: 'new', text: '<strong>"Email this view" button</strong> \u2014 Send the currently displayed window as an ad-hoc email to the same recipients, marked "(ad-hoc)" in the subject.' },
            { badge: 'improve', text: '<strong>Incremental cache</strong> \u2014 Rejections are AI-categorized only once (cached in <code>rejection-cache.json</code>); subsequent refreshes only process newly-arrived events. Tab loads instantly; weekly cron processes ~130 new events in seconds.' }
        ]
    },
    {
        date: 'April 30, 2026',
        title: 'ECN Report \u2014 Cycle Time by Workflow Status',
        items: [
            { badge: 'new', text: '<strong>Per-status breakdown inside each cycle time card</strong> \u2014 The <em>Cycle Time (Standard ECN)</em>, <em>Cycle Time (PDR ECN)</em>, and <em>Cycle Time (Dedicated Process)</em> cards now have a 5-tile sub-strip below the table showing avg business days at each workflow status (Pending / Submitted / Review / Release / Hold) for that classification only. Lets you see <em>where</em> the days are going, not just the totals.' },
            { badge: 'new', text: '<strong>Cycle Time by Workflow Status panel</strong> \u2014 New KPI panel on the ECN Report dashboard shows the average business days each completed ECN spent at each workflow status: <em>Pending, Submitted, Review, Release, Hold</em>. Each tile shows the avg, min\u2013max range, and ECN count. Sourced from the Agile change history (workflow transitions). Per Jimmy Sessumes\u0027s ask.' },
            { badge: 'new', text: '<strong>Days @ Status columns on ECN data table</strong> \u2014 Five new columns (D@Pend, D@Subm, D@Rev, D@Rel, D@Hold) appear after Status in the ECN data table. Populated only for completed ECNs; blank ("\u2014") for in-progress.' },
            { badge: 'new', text: '<strong>Excel report includes per-status cycle time</strong> \u2014 The downloaded ECN Excel now has 5 extra columns on the Completed sheet (Days @ Pending / Submitted / Review / Release / Hold) and a new "Cycle Time by Workflow Status YTD" panel on the KPI Dashboard sheet showing avg/min/max/count.' },
            { badge: 'fix', text: '<strong>business_days() no longer returns -1 on weekend-only spans</strong> \u2014 When two timestamps both landed on the same Saturday/Sunday, the helper used to return -1 (causing negative min values in cycle-time aggregates). Clamped to 0.' }
        ]
    },
    {
        date: 'April 29, 2026',
        title: 'ECN Report \u2014 Hold-Day Logic Removed, Excel Split by Status',
        items: [
            { badge: 'fix', text: '<strong>Actual Days no longer subtracts unreliable hold days</strong> \u2014 The Agile <em>Total days in Hold</em> field (PAGE_THREE.NUMERIC36) was found to be incorrect for many ECNs (e.g., ECN-132077 reported 40 hold days but never went on Hold), causing some completed ECNs to be clamped to <code>0</code>. Actual Days now equals straight <code>NETWORKDAYS(submit, complete)</code>. The amber <code>*</code> indicator and "hold exceeded" tooltip have been removed.' },
            { badge: 'improve', text: '<strong>Excel export split into Completed / In Progress tabs</strong> \u2014 The download Excel and emailed report now have two data tabs: <em>Completed Q&lt;n&gt; YYYY</em> (with Actual Days and KPI Missed formulas) and <em>In Progress Q&lt;n&gt; YYYY</em> (open ECNs without an Actual Days value). Removes confusion when scanning ECNs with blank cycle-time cells.' },
            { badge: 'improve', text: '<strong>ECN Data table defaults to Completed only</strong> \u2014 The data table at the bottom of the ECN Report tab now hides In Progress ECNs by default (they show no Actual Days value, which was confusing). Click <em>Show all statuses</em> next to the search box to bring back the full set; click again to restrict back to Completed only.' },
            { badge: 'improve', text: '<strong>Export CSV \u2192 Download Excel</strong> \u2014 The data table\u0027s export button now downloads a full <code>.xlsx</code> with two tabs: <em>Completed</em> (opens by default) and <em>In Progress</em>. Replaces the prior single-CSV export, which lumped both statuses together and confused readers when scanning blank Actual Days cells.' }
        ]
    },
    {
        date: 'April 29, 2026',
        title: 'Help & Support \u2014 Compare-Period Date Queries',
        items: [
            { badge: 'fix', text: '<strong>Year-over-year and date-range queries no longer fail with ORA-01843 / ORA-01756</strong> \u2014 Strengthened the SQL generation prompt with explicit CREATE_DATE examples and a hard rule to always wrap VARCHAR date columns as <code>TO_DATE(SUBSTR(col,1,11),\u0027DD-MON-YYYY\u0027)</code>. Added an explicit pattern for compare-style questions ("compare 2025 to 2026", "YTD vs same period last year"): one SELECT from DUAL with two count subqueries.' },
            { badge: 'improve', text: '<strong>Auto-correct on Oracle errors</strong> \u2014 If the generated SQL fails with ORA-01756, ORA-01843, ORA-00911, ORA-00936, ORA-00933, ORA-01722, ORA-00907, or ORA-00920, the assistant now sends the failing SQL plus the error back to the LLM and runs the corrected SQL automatically. Successful retries show an "Auto-corrected after Oracle error" badge.' },
            { badge: 'improve', text: '<strong>Failure messages show the SQL</strong> \u2014 When a query still fails, the chat now displays the SQL alongside the Oracle error so it\u0027s clear what was attempted.' }
        ]
    },
    {
        date: 'April 27, 2026',
        title: 'ECN Report Tab (Phase 2)',
        items: [
            { badge: 'new', text: '<strong>ECN Report tab</strong> \u2014 Standalone tab with interactive KPI dashboard mirroring the ECN Request Classification Email Format sheet. Six panels: ECN Volume YTD, Cycle Time (General/PDR/Dedicated Process ECN), Volume by Change Type, Cycle Time by Product Team, plus 3-month POM comparison.' },
            { badge: 'new', text: '<strong>Editable SLA targets</strong> \u2014 Click any target day value to edit it. Changes instantly recalculate all KPIs, % on Target, and charts across the dashboard. Save edits as named profiles (e.g., "Q2 2026 Adjusted Targets").' },
            { badge: 'new', text: '<strong>SLA profile management</strong> \u2014 Create, switch, and promote named SLA profiles. Promote a profile to "Active SLA" and all users see the updated targets in the tab and email reports.' },
            { badge: 'new', text: '<strong>ECN data table</strong> \u2014 Full row-level table with sortable columns, text filtering, pagination (50 per page), and CSV export. Editable columns: ECN Classification (dropdown), Product Team (dropdown), and Notes (free text).' },
            { badge: 'new', text: '<strong>Email report</strong> \u2014 Send branded HTML email with KPI summary tiles and Excel attachment. Default distribution list with ad-hoc recipient editing per send.' },
            { badge: 'new', text: '<strong>ECN Report editors</strong> \u2014 Admins can designate named editors (initially Jimmy Sessumes and Vikas Singh) who can run reports, promote SLA profiles, and send emails. Manage from the gear icon.' },
            { badge: 'new', text: '<strong>Chart.js trend charts</strong> \u2014 Stacked bar charts for volume panels, dual-axis line charts (Avg Days + % on Target) for cycle time panels. Interactive tooltips on hover.' }
        ]
    },
    {
        date: 'April 23, 2026',
        title: 'Debug Assistant Redesign (Admin)',
        items: [
            { badge: 'improve', text: '<strong>Debug Assistant \u2014 full redesign</strong> (admin-only) \u2014 Completely rebuilt UI with three states: input, running, and results. New hero input with collapsible Source & Repo options. Root cause banner extracts the exception and shows stats at a glance. Stack frames panel lists each source ref with found/not-found/SDK status. Polished design tokens and typography throughout.' },
            { badge: 'new', text: '<strong>Debug Assistant \u2014 email when done</strong> (admin-only) \u2014 Analysis taking a while? Enter your email during the running state and get the full report delivered to your inbox. Close the tab anytime \u2014 the report is sent automatically when analysis finishes.' },
            { badge: 'improve', text: '<strong>Debug Assistant \u2014 live progress stepper</strong> (admin-only) \u2014 Analysis now streams progress in real time with pill-shaped steps: parsing input, extracting traces, resolving source (with file-by-file counter), and AI analysis. Spinner and elapsed timer show exactly where things stand.' },
            { badge: 'new', text: '<strong>Debug Assistant \u2014 local JAR / .java source</strong> (admin-only) \u2014 Upload a source JAR or .java file to skip Bitbucket entirely. JARs must contain bundled .java source files. Much faster than network lookups.' },
            { badge: 'improve', text: '<strong>Debug Assistant \u2014 skip Agile SDK classes</strong> (admin-only) \u2014 <code>com.agile.api.*</code> classes (Oracle SDK) are now auto-skipped during Bitbucket source resolution. Previously these caused hundreds of futile HTTP calls, inflating analysis time to 10+ minutes. Now marked as "Agile SDK class" instantly.' }
        ]
    },
    {
        date: 'April 22, 2026',
        title: 'Review Dashboard, Multi-Analyst, Country Filters',
        items: [
            { badge: 'new', text: '<strong>Review Dashboard</strong> \u2014 New Dashboard mode in Change Reviews (toggle at top). Shows org-wide view of all changes in review with pie charts (by type, country), aging distribution bar, country health stacked bar, and a changes table grouped by analyst with collapsible sections.' },
            { badge: 'new', text: '<strong>Dashboard lookback</strong> \u2014 Choose how far back to look: 7 days, 30 days (default), 90 days, 1 year, or All time. Changes the date filter on change creation date so dashboards load fast.' },
            { badge: 'new', text: '<strong>Dashboard analyst filter</strong> \u2014 Multi-select dropdown to filter the dashboard to specific analysts. Charts and table update instantly (client-side, no re-fetch).' },
            { badge: 'new', text: '<strong>Dashboard Excel export</strong> \u2014 Export the full dashboard to Excel with all columns: Change, Description, Owner, Country, Status, Workflow, Type, Priority, Age, Approved/Awaiting/Rejected counts, % Complete.' },
            { badge: 'new', text: '<strong>Multi-analyst search</strong> \u2014 Select multiple analysts in the Queue and see their combined change queues. Analysts appear as removable blue chips. Backspace to remove the last one.' },
            { badge: 'new', text: '<strong>Analyst list from Agile</strong> \u2014 The analyst dropdown is now sourced from the Agile agileuser table (active users with change analyst role), cached 1 hour. No more dependency on AD for the dropdown.' },
            { badge: 'new', text: '<strong>Country filter</strong> \u2014 Country chips above the analyst input let you select a country to load all analysts from that region. Country data resolved from AD in a single batch lookup, cached alongside the analyst list.' },
            { badge: 'new', text: '<strong>Save & share Change Reviews searches</strong> \u2014 Star button saves your analyst selection. Share it with the team via the lock/link icon. Teammates can load your saved multi-analyst searches.' },
            { badge: 'fix', text: '<strong>Signoff percentage accuracy</strong> \u2014 Fixed: reviewers with non-standard REQUIRED values (e.g., group approvers like "Agile IT Admin Approvers") were excluded from the required count, inflating the percentage to 100% even when approvals were pending.' }
        ]
    },
    {
        date: 'April 21, 2026',
        title: 'Change Reviews Overhaul, BOM Compare, Timestamps & More',
        items: [
            { badge: 'new', text: '<strong>Change Reviews tab</strong> \u2014 See all changes in review for any analyst. Auto-loads your own queue on first visit. Type a name to search, or click <strong>Me</strong> to reload your queue. Click any change for full signoff detail.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 progress bar</strong> \u2014 Each row shows a stacked progress bar (green=Approved, blue=Acknowledged, red=Rejected, gray=Awaiting) with a percentage based on required reviewers only. Hover for the full reviewer list.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 KPI filters + chips</strong> \u2014 Click KPI tiles (ECN, ACR, etc.) to filter by type. Quick-filter chips: Ready to release, Has rejection, Stale >10d. Group by Workflow, Change Type, or Status with collapsible sections.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 Age column</strong> \u2014 Shows how many days the longest-awaiting reviewer has been waiting. Color-coded: normal \u22645d, amber 6-10d, red >10d.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 rejection details inline</strong> \u2014 Rows with rejections show a pink background with the rejection reason directly under the description (who rejected and why). No need to click through.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 activity momentum</strong> \u2014 A colored left-edge bar on each row: green = signoff in last 24h, amber = 1-3 days, gray = 3-10 days, faded = >10 days.' },
            { badge: 'new', text: '<strong>Change Reviews \u2014 recent searches</strong> \u2014 Last 5 searched analysts saved as clickable chips under the search box. Sticky table header and first column on scroll.' },
            { badge: 'improve', text: '<strong>BOM Compare \u2014 unified tab</strong> \u2014 BOM Compare and Rev Compare combined into one tab with a pill toggle: <strong>Two Parts</strong> vs <strong>Two Revisions</strong>.' },
            { badge: 'improve', text: '<strong>Field Changes \u2014 relative timestamps</strong> \u2014 "When" column shows "2m ago", "3h ago", "1d ago" with auto-refresh every 60 seconds. Hover for full date.' },
            { badge: 'new', text: '<strong>Field Changes \u2014 1-hour lookback</strong> \u2014 New "1 hour" option in the Lookback selector.' },
            { badge: 'new', text: '<strong>Copy item number</strong> \u2014 Clipboard icon next to each item number in Field Changes. Click to copy without expanding the row.' },
            { badge: 'new', text: '<strong>Tab personalization</strong> \u2014 Click your name to show/hide tabs. Preferences persist across sessions.' },
            { badge: 'fix', text: '<strong>Field Changes timestamps</strong> \u2014 Fixed timezone mismatch where Oracle UTC timestamps were misinterpreted, causing wrong relative times.' },
            { badge: 'fix', text: '<strong>Saved search + scheduled report cleanup</strong> \u2014 Deleting a saved search now also removes any associated scheduled reports, so you stop receiving emails.' },
            { badge: 'new', text: '<strong>Shared saved searches</strong> \u2014 Share your saved searches with the team. Click the lock icon (\ud83d\udd12) next to any saved search to share it \u2014 it becomes visible to all users in the Shared section of the My Searches dropdown. Click the link icon (\ud83d\udd17) to unshare. Shared searches show the owner\u2019s name so you know who created them.' },
            { badge: 'fix', text: '<strong>Change Reviews signoff dedup</strong> \u2014 Fixed duplicate reviewer rows from resubmission cycles. Added "Rejected" status (was showing raw number). Awaiting rows now correctly take priority over historical acknowledged/approved rows.' }
        ]
    },
    {
        date: 'April 20, 2026',
        title: 'BOM Compare, Rev Compare, AD Health & Major Fixes',
        items: [
            { badge: 'new', text: '<strong>Rev Compare</strong> \u2014 Compare two revisions of the same part side by side. Enter a part number to load all revisions, pick two from dropdowns, and see BOM differences highlighted. Shows lifecycle changes (DEAD vs MKT) and word-level description diffs. Excel export and email support.' },
            { badge: 'new', text: '<strong>BOM Compare</strong> \u2014 Compare two parent assemblies side by side. Shows components on each side with aligned rows, highlights differences in yellow/red. Choose which fields to compare (Qty, Description, Item Type, Status, Rev, Find #, Ref Designator, BOM Notes). Export and email support.' },
            { badge: 'new', text: '<strong>AD Health Check tab</strong> \u2014 Admin-only diagnostic tool that tests LDAP bind speed, service account connectivity, and group lookup performance. Shows Fast/Normal/Slow/Unreachable verdict with per-step timing.', admin: true },
            { badge: 'new', text: '<strong>Offline login support</strong> \u2014 Credential cache now persists to disk and survives restarts. If AD is unreachable, previously-logged-in users can still authenticate. Emergency local admin account available as last resort. A red banner shows when logged in via cache.', admin: true },
            { badge: 'new', text: '<strong>Agile Lookup saved searches</strong> \u2014 You can now save and restore searches on the Agile Lookup tab using the star button.' },
            { badge: 'improve', text: '<strong>BOM Where Used \u2014 full top-level discovery</strong> \u2014 The exported Excel always finds ALL top-level assemblies regardless of Max Depth. A notification bar appears if there are additional top-levels beyond your current depth. Input Part and Path columns added to the Top-Level Assemblies tab.' },
            { badge: 'improve', text: '<strong>Part Extract performance</strong> \u2014 File uploads with many items (100+) now use exact-match IN clauses instead of LIKE queries, dramatically faster. CSV headers and UTF-8 BOM are automatically stripped.' },
            { badge: 'improve', text: '<strong>Part Extract date format</strong> \u2014 Date columns in exports now use DD-MM-YYYY format without timestamps for easier sorting and filtering in Excel.' },
            { badge: 'improve', text: '<strong>Feedback email threading</strong> \u2014 Reply-To header now set on feedback and issue emails so the Reply button in your email client goes to the right person with full context.' },
            { badge: 'fix', text: '<strong>Feedback attachment upload</strong> \u2014 Fixed IOException when submitting feedback with file attachments.' },
            { badge: 'fix', text: '<strong>Part Extract source label</strong> \u2014 The results banner now correctly says "item_extract table" instead of "Live database query".' },
            { badge: 'fix', text: '<strong>Saved search delete crash</strong> \u2014 Fixed a crash when deleting saved searches that had missing IDs from older data.' },
            { badge: 'fix', text: '<strong>Agile SDK session leak</strong> \u2014 Agile Lookup microservice now guarantees session close via try-finally, even on errors.' }
        ]
    },
    {
        date: 'April 19, 2026',
        title: 'Utilities Tab Revamp',
        items: [
            { badge: 'new', text: '<strong>Grouped utilities</strong> — Utilities tab now organizes everything into three groups: Built-in Reports, Uploaded Scripts, and System Diagnostics.', admin: true },
            { badge: 'new', text: '<strong>Type chips</strong> — Each utility shows a clear type badge: BUILT-IN, UPLOADED \u00b7 .py/.jar, or SYSTEM.', admin: true },
            { badge: 'new', text: '<strong>Action menu</strong> — Uploaded utilities have a \u22ef menu with Edit, Duplicate, Download source, and Delete instead of inline buttons.', admin: true },
            { badge: 'new', text: '<strong>Run history</strong> — Click any utility row to expand and see run history with status, duration, and output downloads.' },
            { badge: 'new', text: '<strong>Params drawer</strong> — "Run with files" opens as a right-side drawer instead of a modal, with delivery options and last-config memory.', admin: true },
            { badge: 'new', text: '<strong>Search bar</strong> — Filter utilities by name or description as you type.' },
            { badge: 'improve', text: '<strong>Mine/All toggle</strong> — Business users see only what they can run. Admins can toggle to see everything.' }
        ]
    },
    {
        date: 'April 18, 2026',
        title: 'v2 Design System, Email Revamp & Insight Strips',
        items: [
            { badge: 'new', text: '<strong>v2 design system</strong> — IBM Plex typography (Sans, Mono, Serif), refined color palette with blue accent, and design tokens across all 9 tabs.' },
            { badge: 'new', text: '<strong>Insight strips</strong> — Every tab now shows a 4-stat dashboard bar (rows, items, query time, etc.) after each search.' },
            { badge: 'new', text: '<strong>Lifecycle chips</strong> — Status values (ACT, OBS, EOL, DEV) render as color-coded badges across BOM, Parts, History, SKU, and Compare tabs.' },
            { badge: 'new', text: '<strong>User avatars</strong> — Changed By names in Field Changes now show an initial-circle avatar next to the name.' },
            { badge: 'new', text: '<strong>BOM level pills</strong> — Level numbers in BOM results use styled badges, with the root level highlighted in dark.' },
            { badge: 'new', text: '<strong>Email redesign</strong> — All 8 email types share a unified envelope: PT header mark, Plex typography, KPI tiles, preview tables, dark mode support.' },
            { badge: 'new', text: '<strong>Extension health pills</strong> — Cache age in Extensions now shows as fresh/aging/stale badges instead of plain text.' },
            { badge: 'new', text: '<strong>Contributor name resolution</strong> — Extension contributor chips now show full names (resolved from AD) and are clickable to view email, manager, and country.' },
            { badge: 'improve', text: '<strong>No more red</strong> — Red accent removed from all interactive elements. Primary buttons and active states use dark ink. Red reserved for destructive actions only.' },
            { badge: 'improve', text: '<strong>Help sidebar restyled</strong> — White header, neutral "Raise Issue" button, blue focus rings on inputs.' },
            { badge: 'improve', text: '<strong>Login page refreshed</strong> — Plex fonts, dark login button, clean border navbar.' },
            { badge: 'improve', text: '<strong>Compare results styled</strong> — Match/Differ/Not Found statuses use v2 chips instead of raw colored text.' },
            { badge: 'improve', text: '<strong>Step indicators refined</strong> — Data Compare wizard steps use mono font pills with subtle borders.' },
            { badge: 'fix', text: '<strong>SKU filter inputs</strong> — Fixed a bug where typing in column filter inputs would lose focus and clear text (filter now only rebuilds table body, not headers).' },
            { badge: 'fix', text: '<strong>SKU description highlight</strong> — Yellow merged-value highlight no longer applies to Agile-only columns like Description that naturally contain commas.' },
            { badge: 'new', text: '<strong>Utilities tab</strong> — Upload and run Python scripts, Java programs, or JAR files directly from the browser with live stdout streaming, environment variables, and supporting file management.', admin: true },
            { badge: 'new', text: '<strong>AI script analysis</strong> — When uploading a script, AI scans the code and auto-detects entry points, environment variables, dependencies, CLI flags, and required config/input files.', admin: true },
            { badge: 'new', text: '<strong>Properties file validation</strong> — Uploaded .properties files are checked for path mismatches (Linux vs Windows) with one-click auto-fix.', admin: true },
            { badge: 'new', text: '<strong>JAR multi-entry-point support</strong> — JARs with multiple main() classes show an entry point picker. Source and bytecode-only classes are distinguished.', admin: true },
            { badge: 'new', text: '<strong>AI-powered What\'s New digest</strong> — Daily email to all users with a warm, AI-written summary of recent updates. Admins can trigger on-demand from Utilities.', admin: true },
            { badge: 'improve', text: '<strong>Admin tools consolidated</strong> — Send Stats, Download Log, and Dry Run moved from navbar to Utilities tab.', admin: true },
            { badge: 'improve', text: '<strong>Broadcast confirm modal</strong> — Reports that email multiple users require explicit confirmation before sending, with recipient count shown.', admin: true },
            { badge: 'new', text: '<strong>Reports ledger table</strong> — Reports redesigned from a card grid to a ledger table with Owner, Est. runtime, Last run, Recipients, and Action columns.' },
            { badge: 'new', text: '<strong>Live-stream panel</strong> — When a report is running, a terminal-style live stream panel appears below the table showing real-time output.' },
            { badge: 'improve', text: '<strong>Data Compare v2 styling</strong> — Step indicators, filter buttons, status chips, and diff cell colors now use v2 design tokens.' },
            { badge: 'improve', text: '<strong>Extensions v2 styling</strong> — Source table uses mono fonts, health pills (fresh/aging/stale), and v2 action buttons throughout.' }
        ]
    },
    {
        date: 'April 17, 2026',
        title: 'User Profiles, World Clocks, BOM Improvements & More',
        items: [
            { badge: 'new', text: '<strong>User info cards</strong> — Click any name in the Changed By column to see their department, manager, country, and email (pulled live from AD).' },
            { badge: 'new', text: '<strong>World clocks</strong> — A clock strip below the navbar shows the current time in US West Coast, India, Malaysia, China, Japan, and Israel.' },
            { badge: 'new', text: '<strong>Draggable tabs</strong> — Drag tabs to reorder them. Your preferred order is saved automatically across sessions.' },
            { badge: 'new', text: '<strong>Data source badges</strong> — Each tab now shows where its data comes from (e.g., "Oracle DB (live)", "JSON cache (daily 2 AM)"). Hover for details.' },
            { badge: 'new', text: '<strong>"What\'s New" on login</strong> — This changelog auto-pops after login if there are unseen updates. Check "Don\'t show automatically" to suppress.' },
            { badge: 'new', text: '<strong>Feedback attachments</strong> — The feedback form now supports file attachments (images, PDFs, spreadsheets up to 5 MB). The textarea is also resizable.' },
            { badge: 'new', text: '<strong>BOM "Top-Level Assemblies" tab</strong> — Where Used (implode) Excel exports now include a second tab listing all top-level parent assemblies.' },
            { badge: 'improve', text: '<strong>"Implode" renamed to "Where Used"</strong> — Clearer label for bottom-up BOM searches.' },
            { badge: 'improve', text: '<strong>BOM Max Depth</strong> — Changed from a dropdown (5/10/20/50) to a free-text input so you can enter any depth level. Default is now 1.' },
            { badge: 'improve', text: '<strong>BOM Excel export styling</strong> — Removed alternating grey/white row banding. All rows are now white with light grey borders for a cleaner look.' },
            { badge: 'improve', text: '<strong>BOM Implosion column order</strong> — In Where Used exports, columns are now Component then Parent (reversed from Explode) so the input item appears first.' },
            { badge: 'improve', text: '<strong>Agile Lookup redesign</strong> — Merged two upload boxes into one unified row. Upload .xlsx, .txt, or .csv from a single file picker.' },
            { badge: 'improve', text: '<strong>Extensions key column validation</strong> — Key column is now validated against Excel headers at upload time (case-insensitive). Invalid columns are rejected before the source is added.' },
            { badge: 'improve', text: '<strong>Case-insensitive SKU matching</strong> — External source key lookups now match regardless of case (e.g., "sdcz48" matches "SDCZ48").' },
            { badge: 'improve', text: '<strong>User guide expanded</strong> — Added documentation for Reports, Data Compare, SKU Lookup, and Extensions tabs (was missing 4 of 9 tabs).' },
            { badge: 'fix', text: '<strong>Saved search delete</strong> — Fixed a server-side crash when deleting saved searches with null IDs. Also improved the X button click target.' },
            { badge: 'fix', text: '<strong>Duplicate "PLM" text</strong> — Fixed "Agile PLM PLM Toolkit" on the main page, login page title, navbar, and footer.' },
            { badge: 'fix', text: '<strong>Field Changes null user</strong> — Fixed a crash when the Changed By field is empty for some records.' }
        ]
    },
    {
        date: 'April 16, 2026',
        title: 'Scheduled Reports & Report Scheduling UI',
        items: [
            { badge: 'new', text: '<strong>Scheduled Reports</strong> — Schedule Field Changes searches to run automatically and email you results on a daily or weekly basis.' },
            { badge: 'new', text: '<strong>Schedule modal</strong> — Clock icon next to the star button opens a dialog to configure frequency, day, and time.' }
        ]
    }
];

// Track which version the user last saw (date + item count as fingerprint)
var WHATS_NEW_VERSION = WHATS_NEW_RELEASES.length > 0
    ? WHATS_NEW_RELEASES[0].date + '-' + WHATS_NEW_RELEASES[0].items.length
    : '';

function showWhatsNew(autoTriggered) {
    // Only mark as seen when user clicks the button manually, not on auto-popup
    if (!autoTriggered) {
        localStorage.setItem('whatsNewSeen', WHATS_NEW_VERSION);
        updateWhatsNewBadge();
    }

    var isAdmin = !!window.extIsAdmin;

    // Load admin flags from server if admin, then render
    if (isAdmin) {
        fetch('/api/admin/whatsnew-flags')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                var flags = data.success ? (data.flags || {}) : {};
                _renderWhatsNewModal(autoTriggered, isAdmin, flags);
            })
            .catch(function() {
                _renderWhatsNewModal(autoTriggered, isAdmin, {});
            });
    } else {
        _renderWhatsNewModal(autoTriggered, false, {});
    }
}

function _makeItemKey(releaseDate, itemIndex) {
    return releaseDate + ':' + itemIndex;
}

function _renderWhatsNewModal(autoTriggered, isAdmin, serverFlags) {
    var overlay = document.createElement('div');
    overlay.className = 'whats-new-overlay';
    overlay.id = 'whatsNewOverlay';
    overlay.onclick = function(e) { if (e.target === overlay) closeWhatsNew(); };

    var html = '<div class="whats-new-modal">' +
        '<div class="wn-header"><h3>What\'s New</h3>' +
        (isAdmin ? '<span style="font-family:var(--font-mono,monospace);font-size:9px;letter-spacing:0.1em;color:var(--accent,#2c6fbb);background:var(--accent-2,#eef3fa);padding:2px 8px;border-radius:3px;margin-right:auto;margin-left:12px;">ADMIN VIEW</span>' : '') +
        '<button class="wn-close" onclick="closeWhatsNew()">&times;</button></div>' +
        '<div class="wn-body">';

    WHATS_NEW_RELEASES.forEach(function(rel) {
        // Merge server flags with inline admin flags
        var itemsWithFlags = rel.items.map(function(item, idx) {
            var key = _makeItemKey(rel.date, idx);
            var isAdminItem = item.admin || serverFlags[key];
            return { badge: item.badge, text: item.text, admin: isAdminItem, _key: key, _idx: idx };
        });

        // Filter items by audience
        var visibleItems = itemsWithFlags.filter(function(item) {
            return isAdmin || !item.admin;
        });
        if (visibleItems.length === 0) return;

        // For admins, sort admin items first
        if (isAdmin) {
            var adminItems = visibleItems.filter(function(i) { return i.admin; });
            var userItems = visibleItems.filter(function(i) { return !i.admin; });
            visibleItems = adminItems.concat(userItems);
        }

        html += '<div class="wn-release">';
        html += '<div class="wn-dot"></div>';
        html += '<div class="wn-date">' + rel.date + '</div>';
        html += '<div class="wn-title">' + rel.title + '</div>';
        html += '<ul>';
        var shownAdminHeader = false;
        visibleItems.forEach(function(item) {
            if (isAdmin && item.admin && !shownAdminHeader) {
                html += '<li style="list-style:none;margin:10px 0 6px -16px;padding:0;"><span style="font-family:var(--font-mono,monospace);font-size:9px;letter-spacing:0.12em;text-transform:uppercase;color:var(--accent,#2c6fbb);background:var(--accent-2,#eef3fa);padding:2px 8px;border-radius:3px;">Admin only</span></li>';
                shownAdminHeader = true;
            }
            var badgeClass = item.badge === 'new' ? 'wn-badge-new' : item.badge === 'fix' ? 'wn-badge-fix' : 'wn-badge-improve';
            var badgeLabel = item.badge === 'new' ? 'New' : item.badge === 'fix' ? 'Fix' : 'Improved';

            html += '<li style="display:flex;align-items:flex-start;gap:6px;">';
            html += '<div style="flex:1;"><span class="wn-badge ' + badgeClass + '">' + badgeLabel + '</span>' + item.text + '</div>';

            // Admin toggle
            if (isAdmin) {
                var checked = item.admin ? ' checked' : '';
                var flagLabel = item.admin ? 'ADMIN' : 'ALL';
                html += '<label title="Toggle: hide from regular users" style="flex-shrink:0;display:flex;align-items:center;gap:3px;cursor:pointer;margin-top:2px;">' +
                    '<input type="checkbox"' + checked + ' onchange="toggleWhatsNewAdminFlag(\'' + item._key.replace(/'/g, "\\'") + '\', this.checked, this)" style="accent-color:var(--accent,#2c6fbb);margin:0;">' +
                    '<span class="wn-flag-status" style="font-family:var(--font-mono,monospace);font-size:8px;color:var(--ink-4,#999);letter-spacing:0.06em;min-width:40px;">' + flagLabel + '</span>' +
                    '</label>';
            }

            html += '</li>';
        });
        html += '</ul></div>';
    });

    html += '</div>' +
        '<div style="padding:12px 24px; border-top:1px solid #e9ecef; display:flex; align-items:center; gap:8px;">' +
        '<input type="checkbox" id="whatsNewDontShow" style="margin:0;">' +
        '<label for="whatsNewDontShow" style="font-size:12px; color:#888; cursor:pointer; user-select:none;">Don\'t show this automatically on login</label>' +
        '</div>' +
        '</div>';
    overlay.innerHTML = html;
    document.body.appendChild(overlay);
}

function toggleWhatsNewAdminFlag(key, adminOnly, checkbox) {
    // Show saving indicator
    var label = checkbox ? checkbox.parentElement : null;
    var statusSpan = label ? label.querySelector('.wn-flag-status') : null;
    if (statusSpan) {
        statusSpan.textContent = '\u2026';
        statusSpan.style.color = 'var(--accent,#2c6fbb)';
    }

    fetch('/api/admin/whatsnew-flags', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key: key, adminOnly: adminOnly })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (statusSpan) {
            if (data.success) {
                statusSpan.textContent = adminOnly ? '\u2713 hidden from users' : '\u2713 visible to all';
                statusSpan.style.color = 'var(--good,#1f8a4c)';
                setTimeout(function() { statusSpan.textContent = adminOnly ? 'ADMIN' : 'ALL'; statusSpan.style.color = ''; }, 2000);
            } else {
                statusSpan.textContent = '\u2717 failed';
                statusSpan.style.color = 'var(--bad,#b8342b)';
            }
        }
    })
    .catch(function() {
        if (statusSpan) { statusSpan.textContent = '\u2717 error'; statusSpan.style.color = 'var(--bad)'; }
    });
}

function closeWhatsNew() {
    var cb = document.getElementById('whatsNewDontShow');
    if (cb && cb.checked) {
        localStorage.setItem('whatsNewAutoHide', 'true');
    }
    var overlay = document.getElementById('whatsNewOverlay');
    if (overlay) overlay.remove();
}

// Show a small notification dot on the button if there are unseen updates
function updateWhatsNewBadge() {
    var btn = document.getElementById('whatsNewBtn');
    if (!btn) return;
    var seen = localStorage.getItem('whatsNewSeen');
    if (seen !== WHATS_NEW_VERSION) {
        btn.style.position = 'relative';
        if (!document.getElementById('whatsNewDot')) {
            var dot = document.createElement('span');
            dot.id = 'whatsNewDot';
            dot.style.cssText = 'position:absolute; top:-3px; right:-6px; width:7px; height:7px; background:#28a745; border-radius:50%; border:1px solid #1a3a5c;';
            btn.appendChild(dot);
        }
    } else {
        var dot = document.getElementById('whatsNewDot');
        if (dot) dot.remove();
    }
}

// Init badge check + auto-show on login
document.addEventListener('DOMContentLoaded', function() {
    updateWhatsNewBadge();

    // Auto-show What's New after login if user hasn't opted out and there are unseen updates
    var params = new URLSearchParams(window.location.search);
    if (params.get('login') === '1') {
        // Clean up the URL parameter
        history.replaceState(null, '', window.location.pathname);
        var autoHide = localStorage.getItem('whatsNewAutoHide') === 'true';
        var seen = localStorage.getItem('whatsNewSeen');
        if (!autoHide && seen !== WHATS_NEW_VERSION) {
            setTimeout(function() { showWhatsNew(true); }, 500);
        }
    }
});
