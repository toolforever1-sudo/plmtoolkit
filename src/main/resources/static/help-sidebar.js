// === Ask Me Sidebar ===
var helpSidebarOpen = false;
var helpChatHistory = [];
var helpUnansweredCount = 0;
var helpLastTab = '';
var helpAiEnabled = false;
var helpAiChatHistory = []; // {role: 'user'|'assistant', content: '...'}

// === Engineering mode (engineering-insider role holders only) ===
// Non-holders get ZERO DOM traces of this — no toggle, no banner, no locked state.
var helpMode = 'help';            // 'help' | 'eng'
var engAiChatHistory = [];        // separate thread per mode
var helpRoleInfo = null;          // cached /api/permissions/me payload
var helpEngLastModel = '';        // slug from the last engineering response
var helpEngSuggestions = [
    'How is the tab + permission system built?',
    'Could the toolkit write changes back into Agile?',
    "What can't the AI help answer today?",
    'Hypothetically — what would SSO take?'
];

function helpShortModel(slug) {
    if (!slug) return '';
    var s = String(slug).split('/').pop();
    return s.indexOf('anthropic.') === 0 ? s.substring(10) : s;
}

// FAQ knowledge base — tab-aware
var HELP_FAQ = [
    // Generic "what does this page/tab do" — one per tab
    { tab: 'changes', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Field Changes</strong> tab. It shows field-level changes to Agile PLM items from the last 1-7 days. Use the filters above (Field Name, Item Number, Old/New Value, Changed By, Days Back) and click Search. Click any row to see an inline diff of old vs new values.' },
    { tab: 'bom', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>BOM Explorer</strong> tab. Enter an item number and click Explode (top-down) or Implode (bottom-up) to explore BOM relationships. Results include path, lifecycle, product line, subcontractors, and build plant. Use Full Extract for the complete BOM database or BOM Notes Extract for BOMs with notes.' },
    { tab: 'parts', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Part Extract</strong> tab. Look up part master data by entering item numbers (wildcards supported) and optionally filtering by release date. Use the column picker to choose which fields to display. Up to 8 columns show on screen; all selected columns appear in Excel exports.' },
    { tab: 'agile', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Agile Lookup</strong> tab. Look up live Agile field values. Two modes: (1) Upload an Excel file, or (2) Type item numbers + select fields using the typeahead (1,250+ fields). Fields are searched across Title Block, Page Two, and Page Three.' },
    { tab: 'history', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Change History</strong> tab. Enter exact item numbers to see their revision and change history. The Lifecycle Journey analysis above the table shows how the item progressed through lifecycle phases with a color-coded timeline and regression detection.' },
    { tab: 'reports', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Utilities</strong> tab (formerly Reports). It has three groups: <strong>Built-in Reports</strong>, <strong>Uploaded Scripts</strong>, and <strong>System Diagnostics</strong>. Use the search bar to find a utility, then click <strong>Run &amp; email me</strong> to execute it. Click any row to see run history.' },
    { tab: 'compare', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Data Compare</strong> tab. A 5-step wizard that compares an Excel file (e.g., a SAP export) against live Agile PLM data. Upload your file, pick the key column, map your columns to Agile fields (auto-suggested), and run the comparison. Results are color-coded: green=match, red=differ, amber=partial, gray=not found.' },
    { tab: 'sku', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>SKU Lookup</strong> tab. Instant lookup of SKU records against a pre-loaded JSON cache (~48K SKUs, refreshed daily at 2 AM). Type item numbers or upload a file. Optionally select one or more <em>External Data Sources</em> (uploaded Excel files like SAP) and pick which columns from each to join with the Agile result.' },
    { tab: 'extensions', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Extensions</strong> tab (admin-only). Manage external data sources used by SKU Lookup. Upload an Excel file, give it a name and key column, optionally schedule a daily refresh, then Generate the cache. Sources can be regenerated or removed at any time.' },
    // Tab-specific FAQs
    { tab: 'changes', keywords: ['field changes', 'field change', 'item history', 'what changed'], answer: 'The Field Changes tab shows field-level changes to Agile PLM items from the last 1-7 days. Use the search filters (Field Name, Item Number, Old/New Value, Changed By, Days Back) and click Search. Click any row to see an inline diff.' },
    { tab: 'changes', keywords: ['net change', 'net filter', 'reverted', 'toggle'], answer: 'The Net-change filter (enabled by default) hides changes that were subsequently reverted. Toggle it off to see all intermediate changes including reverts.' },
    { tab: 'changes', keywords: ['schedule', 'scheduled', 'recurring', 'automatic email', 'clock'], answer: 'Click the clock icon next to the star to schedule recurring email reports. Choose Daily or Weekly, pick a time (30-min intervals), and save. Reports are emailed automatically. If 0 results, no email is sent.' },
    { tab: 'bom', keywords: ['bom', 'explode', 'explosion', 'top down'], answer: 'Enter an assembly number and click Explode to see all components recursively (default 20 levels). Results show Level, Parent, Component, Qty, Description, Item Type, Status, Lifecycle, Product Line, Subcontractors, Build Plant, and the full traversal Path.' },
    { tab: 'bom', keywords: ['implode', 'where used', 'bottom up', 'parent'], answer: 'Enter a component number and click Implode to find all parent assemblies. Parents with no further parents are marked with a green "TOP" badge. Results are enriched with lifecycle, product line, subcontractors, and build plant.' },
    { tab: 'bom', keywords: ['full extract', 'bom extract', 'csv', 'download all'], answer: 'Click "Full Extract" to download the complete BOM database as a compressed CSV (~70 MB). It is regenerated daily at 2:00 AM. If no file exists, clicking triggers generation automatically.' },
    { tab: 'bom', keywords: ['bom notes', 'notes extract'], answer: 'Click "BOM Notes Extract" to generate an Excel of all BOMs with non-blank Notes. Progress is shown as you click the button. Rows with notes are highlighted in amber.' },
    { tab: 'bom', keywords: ['top level', 'top badge', 'top parent'], answer: 'In Implode results, parents with no further parents are marked with a green "TOP" badge — these are the ultimate top-level assemblies.' },
    { tab: 'bom', keywords: ['path', 'traversal', 'hierarchy path'], answer: 'The Path column shows the full traversal chain (e.g., "A190-001 > 54-07-123 > C-82-456"). Hover for the full path. Complete path is included in Excel exports.' },
    { tab: 'parts', keywords: ['part extract', 'part search', 'item extract'], answer: 'Look up part master data using item numbers (wildcards supported) and optional release date filters. Use the column picker to choose and reorder fields.' },
    { tab: 'parts', keywords: ['column', 'columns', 'field names', 'friendly names'], answer: 'Columns show friendly Agile names (e.g., "Product Line" instead of "PRODUCTLINE"). Only 8 columns show on screen — all selected columns appear in Excel exports.' },
    { tab: 'agile', keywords: ['agile lookup', 'lookup', 'field value', 'page two', 'page three'], answer: 'Agile Lookup queries live field values. Upload an Excel file or type item numbers + select fields via typeahead. Fields are searched across Title Block, Page Two, and Page Three.' },
    { tab: 'agile', keywords: ['typeahead', 'field search', 'autocomplete', 'suggest'], answer: 'Start typing in the Field Names box to search 1,250+ Agile fields. Click a suggestion to add it as a tag. Drag tags to reorder. You can also upload a .txt/.csv of item numbers.' },
    { tab: 'agile', keywords: ['field not present', 'found no data'], answer: '"Field Not Present" (red italic) means the field does not exist on that item type. A blank cell means the field exists but has no value.' },
    { tab: 'history', keywords: ['change history', 'lifecycle', 'revision history', 'rev history'], answer: 'Enter exact item numbers to see revision/change history. Results show Rev, Lifecycle Phase, Release Date, Change Number/Type/Description/Status, sorted by release date within each item.' },
    { tab: 'history', keywords: ['lifecycle journey', 'journey', 'regression', 'timeline', 'phase'], answer: 'The Lifecycle Journey (above the table) shows how the item progressed through phases. Color-coded timeline bar, duration in years/months/days, and regressions flagged in red.' },
    { tab: 'reports', keywords: ['python', 'run report', 'report progress', 'allowed users'], answer: 'Utilities run scripts (Python, Java, or JAR) server-side. Click <strong>Run &amp; email me</strong> to execute and receive results by email. Each utility can have an allowedUsers list. Long-running utilities use a 600-second timeout by default.' },
    { tab: 'reports', keywords: ['run', 'execute', 'report', 'utility', 'how do i run'], answer: 'Find the utility you want in the list (use the search bar to filter). Click <strong>Run &amp; email me</strong> to execute it and get results emailed to you. If the utility needs input files, click <strong>Run with files...</strong> instead. Click <strong>Download now</strong> to get the output from the last run without re-running.' },
    { tab: 'reports', keywords: ['three groups', 'groups', 'built-in', 'uploaded', 'diagnostics', 'categories'], answer: 'The Utilities tab has three groups: <strong>Built-in Reports</strong> (maintained by the PLM team), <strong>Uploaded Scripts</strong> (custom scripts added by admins), and <strong>System Diagnostics</strong> (health checks and environment tools).' },
    { tab: 'reports', keywords: ['run history', 'history', 'past runs', 'previous runs', 'expand'], answer: 'Click any utility row to expand it and see its run history. Each entry shows the date, who ran it, success/error status, and links to download the output file and server log.' },
    { tab: 'reports', keywords: ['upload', 'upload script', 'add script', 'new script'], answer: 'Uploading scripts is an admin-only feature. Admins can click "Upload Script" and follow a 3-step wizard to add a new Python, Java, or JAR utility. Contact your PLM admin if you need a new utility added.' },
    { tab: 'reports', keywords: ['environment variable', 'env var', 'edit env', 'configure'], answer: 'Editing environment variables is an admin-only feature. Admins can click the three-dot menu on a utility and choose "Edit script &amp; env" to manage environment variables, CLI arguments, and dependencies.' },
    { tab: 'reports', keywords: ['run with files', 'input files', 'supporting files', 'config file'], answer: '<strong>Run with files</strong> opens a drawer where you can upload supporting input files (configuration files, item number lists, etc.) before running the utility. Not all utilities require input files.' },
    { tab: 'reports', keywords: ['dependency', 'jar', 'add jar', 'classpath'], answer: 'Adding dependency JARs is an admin-only feature. Admins can edit a utility and add JAR files to its classpath via the "Edit script &amp; env" option in the three-dot menu.' },
    { tab: 'reports', keywords: ['jdk', 'java 8', '1.8', 'agile sdk'], answer: 'The "Use JDK 1.8" checkbox (admin-only) enables the Java 8 runtime for utilities that depend on the Agile SDK, which requires JDK 1.8. Admins can toggle this in the utility\'s edit settings.' },
    { tab: 'reports', keywords: ['output', 'download', 'output file', 'get file', 'result file'], answer: 'Click <strong>Download now</strong> on a utility card to download the output from its most recent run. You can also expand the utility row to see run history and download output or logs from any past run.' },
    { tab: 'reports', keywords: ['search', 'find', 'filter', 'mine', 'all', 'toggle'], answer: 'Use the search bar at the top of the Utilities tab to filter by name or description. The <strong>Mine / All</strong> toggle switches between utilities you have run before ("Mine") and the full available list ("All").' },
    { tab: 'compare', keywords: ['mapping', 'fuzzy', 'auto suggest', 'levenshtein', 'column map'], answer: 'In Step 3, your Excel column names are auto-mapped to Agile field names using fuzzy (Levenshtein) matching. You can override any suggestion or add a custom Agile field name yourself.' },
    { tab: 'compare', keywords: ['key column', 'item number column', 'match column'], answer: 'The Key column is the column in your Excel that maps to Agile Item Number. Pick it in Step 2 — every other column is then compared as a field value for that item.' },
    { tab: 'compare', keywords: ['color', 'green red amber gray', 'status', 'diff legend'], answer: 'Result colors: GREEN = match, RED = differ, AMBER = partial match (case/whitespace), GRAY = item not found in Agile. The status pill on each row summarizes the row-level result.' },
    { tab: 'sku', keywords: ['external source', 'external data', 'sap', 'join', 'multiple sources'], answer: 'You can pick one or many external sources at the same time. For each one, a column-checkbox grid appears so you can choose which external columns to join. The result table shows Agile columns first, then per-source external columns (each source gets its own header color).'},
    { tab: 'sku', keywords: ['delta', 'refresh', 'seed', 'cache'], answer: 'The SKU JSON cache is refreshed automatically daily at 2 AM via a smart-window delta job. Admins can also click Delta (incremental, ~1-2s) or Seed (full rebuild, ~30s) to refresh manually.' },
    { tab: 'extensions', keywords: ['add source', 'upload source', 'rebuild', 'generate cache', 'schedule source'], answer: 'Upload an Excel file via the form: pick a Name, Key Column, and optional Schedule Hour (0-23). The cache is auto-generated on upload. Use Generate to rebuild later, Schedule to set/change the daily refresh hour, or Remove to delete the source.' },
    // BOM Compare tab (unified: Two Parts + Two Revisions)
    { tab: 'bomcompare', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>BOM Compare</strong> tab. Use the pill toggle at the top to switch between <strong>Two Parts</strong> (compare two different assemblies) and <strong>Two Revisions</strong> (compare revisions of the same part). Both modes show a side-by-side diff with identical controls.' },
    { tab: 'bomcompare', keywords: ['toggle', 'two parts', 'two revisions', 'mode', 'switch', 'pill'], answer: 'Click <strong>Two Parts</strong> to compare two different assembly numbers. Click <strong>Two Revisions</strong> to compare two revisions of the same part number. Each mode has its own search inputs and results — switching back preserves your data.' },
    { tab: 'bomcompare', keywords: ['bom compare', 'compare bom', 'side by side', 'two boms', 'diff'], answer: 'BOM Compare shows a side-by-side table with an orange divider. <strong>Only in A</strong> (red) = removed, <strong>Only in B</strong> (green) = added, <strong>Different</strong> (orange) = fields changed (cells highlighted red), <strong>Same</strong> = identical. Use checkboxes to show/hide each category.' },
    { tab: 'bomcompare', keywords: ['field picker', 'fields', 'columns', 'qty', 'description'], answer: 'Click field checkboxes to choose which columns appear in the comparison: Qty, Description, Item Type, Status, Rev, Find #, Ref Designator, BOM Notes. Qty and Description are checked by default.' },
    { tab: 'bomcompare', keywords: ['export', 'excel', 'email', 'download'], answer: 'Click <strong>Export Excel</strong> to download the comparison as an .xlsx with the same side-by-side layout (dark blue/indigo banners, orange divider, yellow/red diff highlighting). Click <strong>Email to Me</strong> to send it to your inbox.' },
    { tab: 'bomcompare', keywords: ['example', 'how to', 'try'], answer: 'Example: enter <strong>54-50-04326-DCS</strong> as BOM A and <strong>54-50-04327-DCS</strong> as BOM B, then click Compare. You will see which components are unique to each and which fields differ.' },
    // Rev Compare tab
    { tab: 'revcompare', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Rev Compare</strong> tab. Enter a part number and click "Load Revs" to populate revision dropdowns. Pick two revisions and click Compare to see how the BOM changed between them. Differences are highlighted exactly like BOM Compare.' },
    { tab: 'revcompare', keywords: ['rev compare', 'revision compare', 'compare rev', 'revision diff', 'rev diff'], answer: 'Rev Compare shows the BOM at two revisions of the same part side by side. <strong>Only in A</strong> = component removed, <strong>Only in B</strong> = component added, <strong>Different</strong> = fields changed (e.g., qty went from 32 to 16). Lifecycle and description differences between the two revs are highlighted above the table.' },
    { tab: 'revcompare', keywords: ['load revs', 'dropdown', 'revision list', 'introductory'], answer: 'After entering a part number and clicking "Load Revs", two dropdowns appear showing all revisions (newest first, Introductory last). Each entry shows the rev letter and change number, e.g., "C — MCO-32326A". The same rev letter can appear twice with different change numbers.' },
    { tab: 'revcompare', keywords: ['lifecycle', 'dead', 'mkt', 'preliminary', 'lifecycle diff'], answer: 'When the two revisions have different lifecycle phases (e.g., DEAD vs MKT), they are shown with color-coded badges — DEAD in red, MKT in green, others in orange. This makes lifecycle changes immediately visible.' },
    { tab: 'revcompare', keywords: ['description', 'desc diff', 'word diff', 'description change'], answer: 'When the parent description differs between revisions, a word-level diff highlights only the changed words in red. For example, if "Sandisk" changed to "ULTRASTAR", only those words are highlighted — the rest stays normal.' },
    { tab: 'revcompare', keywords: ['field picker', 'fields', 'columns', 'comp rev', 'comp change'], answer: 'Choose which columns to compare: Qty, Description, Comp Rev, Comp Change, Type, Seq#, Primary P/N, Ref Designator, BOM Notes. Qty and Description are checked by default.' },
    { tab: 'revcompare', keywords: ['export', 'excel', 'email', 'download'], answer: 'Click <strong>Export Excel</strong> for a side-by-side spreadsheet (same styling as BOM Compare). Click <strong>Email to Me</strong> to send it to your inbox.' },
    { tab: 'revcompare', keywords: ['example', 'how to', 'try', 'test'], answer: 'Example: enter <strong>SDC256G32BA-0512N</strong>, click Load Revs, select "C — MCO-32326A" vs "C — ECO-10145A", then Compare. You will see the lifecycle changed from DEAD to MKT while the BOM rows stayed the same. Try comparing Rev B vs Rev A to see qty change from 32 to 16.' },
    { tab: 'revcompare', keywords: ['same rev letter', 'duplicate rev', 'two C revs', 'change number'], answer: 'The same rev letter can appear twice with different change numbers (e.g., "C — MCO-32326A" and "C — ECO-10145A"). This is normal in Agile — both appear in the dropdown so you can compare them.' },
    { tab: 'revcompare', keywords: ['data source', 'agile', 'live', 'oracle'], answer: 'Rev Compare queries live Agile PLM tables (item, rev, change, bom, refdesig, nodetable, listentry) — not the extract tables. Data is always current.' },
    // Change Reviews tab
    { tab: 'changereviews', keywords: ['this page', 'this tab', 'what does', 'how to use', 'help', 'getting started'], answer: 'You are on the <strong>Change Reviews</strong> tab. It auto-loads your own in-review changes when you first open it. You can also search for any analyst using the typeahead or click <strong>Me</strong> to reload your queue. Click any row for signoff detail. Use the KPI tiles, filter chips, and Group By to slice the data.' },
    { tab: 'changereviews', keywords: ['change reviews', 'analyst', 'review state', 'in review', 'pending approval'], answer: 'Change Reviews shows all changes with STATUSTYPE=2 (Review) owned by a specific analyst. This includes Review, CCB, Execution, and Disposition states. Row colors: <strong>green</strong> = all approved (ready to release), <strong>pink</strong> = has rejection, <strong>amber</strong> = stale (>10d awaiting). Left-edge bar shows activity recency.' },
    { tab: 'changereviews', keywords: ['signoff', 'workflow', 'approval', 'detail', 'click', 'modal'], answer: 'Click any change row to open a signoff detail modal. It shows each reviewer, their action (Approved, Rejected, Awaiting Approval, Acknowledged), whether they\'re required, when they signed off, any comments, and the duration in days. Duration over 5 days is red, over 2 days is orange.' },
    { tab: 'changereviews', keywords: ['filter', 'chip', 'ready', 'rejected', 'stale', 'kpi'], answer: 'Click KPI tiles (ECN, ACR, etc.) to filter by change type. Use the chips below: <strong>Ready</strong> = all approved, <strong>Rejected</strong> = has at least one rejection, <strong>Stale</strong> = awaiting >10 days. Click a chip again to clear. Use <strong>Group By</strong> to group rows by Workflow, Change Type, or Status.' },
    { tab: 'changereviews', keywords: ['progress bar', 'signoff bar', 'percentage', 'bar'], answer: 'The Signoff column shows a stacked progress bar: green = Approved, blue = Acknowledged, red = Rejected, gray = Awaiting, orange = Stale awaiting. The percentage counts only required reviewers. Hover for the full reviewer list.' },
    { tab: 'changereviews', keywords: ['age', 'days', 'duration', 'how long', 'waiting'], answer: 'The <strong>Age</strong> column shows how many days the longest-awaiting required reviewer has been waiting. Color-coded: normal ≤5d, amber 6-10d, red >10d.' },
    { tab: 'changereviews', keywords: ['rejection', 'rejected', 'why rejected', 'reason'], answer: 'Rows with rejections have a pink background and show an inline note under the description: who rejected and why (e.g., "✗ Rejected by Singh, Vikas — Extract has missing data"). Click the row to see the full detail.' },
    { tab: 'changereviews', keywords: ['auto load', 'my changes', 'me button', 'self', 'own queue'], answer: 'The tab auto-loads your own changes the first time you open it. Click the <strong>Me</strong> button to reload your queue after looking at someone else\'s. Your last 5 searched analysts are saved as clickable chips under the search box.' },
    { tab: 'changereviews', keywords: ['group', 'group by', 'workflow', 'collapse'], answer: 'Use the <strong>Group By</strong> dropdown (right side of filter chips) to group rows by Workflow, Change Type, or Status. Click a group header to collapse or expand it.' },
    { tab: 'changereviews', keywords: ['example', 'how to', 'try', 'test'], answer: 'Example: open the tab — your own changes load automatically. Click "ECN" tile to see only ECNs. Click "Rejected" chip to see only rejected items. Click any pink row to see who rejected and why. Use Group By: Workflow to batch ECR and TRC work separately.' },
    { tab: 'changereviews', keywords: ['dropdown', 'search', 'user', 'find person', 'typeahead', 'recent'], answer: 'Start typing a name — a dropdown shows matching analysts with their country. Select multiple analysts (they appear as blue chips). Recent searches show as clickable chips. Click <strong>Me</strong> for your own queue.' },
    { tab: 'changereviews', keywords: ['multiple', 'multi', 'several analysts', 'combine', 'team'], answer: 'Select multiple analysts by typing and picking from the dropdown — each appears as a removable blue chip. Their changes are combined into one view. Backspace removes the last chip. You can also click a country chip to load all analysts from that region.' },
    { tab: 'changereviews', keywords: ['country', 'region', 'malaysia', 'india', 'united states', 'location'], answer: 'Country chips appear above the analyst input. Click a country (e.g., "Malaysia (122)") to load all analysts from that region. Country data is resolved from AD and cached. In the Dashboard, the "By Country" pie chart and "Country Health" stacked bar show the geographic distribution.' },
    { tab: 'changereviews', keywords: ['dashboard', 'org', 'all analysts', 'aggregate', 'overview'], answer: 'Switch to <strong>Dashboard</strong> mode (toggle at the top) to see an org-wide view of all changes in review. Choose a lookback period (7d/30d/90d/1y/All), see pie charts by type and country, aging distribution bar, and a changes table grouped by analyst. Filter to specific analysts with the multi-select dropdown. Export to Excel.' },
    { tab: 'changereviews', keywords: ['chart', 'pie', 'bar', 'aging', 'visualization', 'graph'], answer: 'The Dashboard has 4 charts: <strong>Pie: By Change Type</strong> (ECN/ACR/ECO breakdown), <strong>Pie: By Country</strong>, <strong>Bar: Aging Distribution</strong> (color-coded 0-5d/6-10d/11-30d/31-60d/60d+), and <strong>Stacked Bar: Country Health</strong> (on-track vs stale per country).' },
    { tab: 'changereviews', keywords: ['export', 'excel', 'download', 'report'], answer: 'In Dashboard mode, click <strong>Export Excel</strong> to download all changes with columns: Change, Description, Owner, Country, Status, Workflow, Type, Priority, Age, Approved/Awaiting/Rejected counts, % Complete. The export respects the current lookback period.' },
    { tab: 'changereviews', keywords: ['lookback', 'days', 'period', 'time range', '30 days', '7 days'], answer: 'The Dashboard has a lookback filter: 7 days, 30 days (default), 90 days, 1 year, or All time. This filters by change creation date. Shorter periods load faster. The Queue mode (per-analyst) always shows all active changes regardless of age.' },
    { tab: 'changereviews', keywords: ['save', 'star', 'share', 'saved search'], answer: 'Click the star (★) button to save your current analyst selection. Share it via the lock/link icon in My Searches. Teammates can load your saved multi-analyst searches with one click.' },
    { tab: 'changereviews', keywords: ['activity', 'momentum', 'green bar', 'left edge', 'ping'], answer: 'The colored left edge on each row shows activity recency: <strong>green</strong> = signoff in the last 24h, <strong>amber</strong> = 1-3 days, <strong>gray</strong> = 3-10 days, <strong>faded</strong> = >10 days. Shows momentum at a glance.' },
    // General (no tab)
    { keywords: ['login', 'sign in', 'password', 'access', 'cannot login'], answer: 'Use your Windows/AD username (without domain) and password. You must be in the IT-APP-Agile-admin group. If not, fill out the access request form and you will be emailed within 10 minutes once added.' },
    { keywords: ['save search', 'saved search', 'star', 'my searches'], answer: 'Click the star icon on a supported tab to save the current search. Access saved searches from "My Searches" in the top nav. Click to load, X to delete. Deleting a saved search also removes any associated scheduled reports. Supported on Field Changes, BOM Explorer, Part Extract, Agile Lookup, and Change History.' },
    { keywords: ['share search', 'shared search', 'share', 'lock icon', 'link icon', 'team'], answer: 'Click the lock icon (\ud83d\udd12) next to any of your saved searches to share it with the team. Shared searches appear in a "Shared" section in everyone\'s My Searches dropdown with the owner\'s name. Click the link icon (\ud83d\udd17) to unshare. Others can use shared searches but cannot edit or delete them. Example: save a "Subcon changes, 7 days" search and share it so your teammates can use the same filter.' },
    { keywords: ['tab personalization', 'hide tab', 'show tab', 'visible tabs', 'customize tabs'], answer: 'Click your name in the top bar to open the Visible Tabs dropdown. Uncheck tabs you don\'t use to hide them. Preferences are saved and persist across sessions. Field Changes is always visible. Admin-only tabs only appear if you\'re an admin.' },
    { keywords: ['copy', 'clipboard', 'copy item', 'copy number', 'paste'], answer: 'On the Field Changes tab, click the small clipboard icon next to any item number to copy it to your clipboard without expanding the row. A checkmark appears for 1 second to confirm. Use this to quickly paste item numbers into Agile PLM.' },
    { keywords: ['relative time', 'ago', 'when column', 'time format', 'timestamp'], answer: 'The "When" column on Field Changes shows relative times like "2m ago", "3h ago", "1d ago" instead of raw timestamps. Hover for the full date and time. Times auto-refresh every 60 seconds. The Excel export still contains full timestamps.' },
    { keywords: ['1 hour', 'one hour', 'lookback', 'recent changes'], answer: 'The Lookback selector on Field Changes has a "1 hour" option for checking very recent changes. Select it and click Search to see only changes from the last 60 minutes.' },
    { keywords: ['export', 'excel', 'download', 'xlsx'], answer: 'Click "Export Excel" on any tab to download results as .xlsx. Includes all data (all columns for Part Extract, full path for BOM, Lifecycle Analysis tab for Change History).' },
    { keywords: ['email', 'email me', 'send report'], answer: 'Click "Email Me" on any tab to send the Excel report to your corporate email. Attachments over 5 MB are automatically zipped.' },
    { keywords: ['filter', 'column filter', 'exclude', 'search table'], answer: 'Type in filter boxes below column headers. Syntax: "value" = contains, "!value" = excludes, "a,b" = a OR b. Filters are instant (300ms debounce).' },
    { keywords: ['date filter', 'all dates', 'date dropdown'], answer: 'Date columns have a checkbox dropdown. Click "All dates" to open, check/uncheck dates. "All" and "None" for quick selection.' },
    { keywords: ['drag', 'drop', 'file upload', 'upload'], answer: 'All file upload areas support drag and drop. Drag a file onto the dashed zone. Formats: .txt/.csv for item lists, .xlsx for Agile Lookup.' },
    { keywords: ['session', 'expired', 'timeout', 'logged out', 'undefined'], answer: 'Sessions expire after 30 minutes of inactivity. You will be redirected to login. If you see "undefined", refresh and log in again.' },
    { keywords: ['beta', 'downtime', 'issue', 'problem', 'bug', 'error'], answer: 'This tool is in beta. For issues, contact pdl-plm-admin@sandisk.com or click "Raise Issue" below to submit a report with a screenshot.' },
    { keywords: ['admin', 'update field', 'field names csv'], answer: 'Admins can update field names by clicking "Update Field Names" on the Agile Lookup tab and uploading a new Agile Classes Report CSV.' }
];

// Check if AI help is available
function checkAiHelp() {
    fetch('/api/help/status')
        .then(function(res) { return res.json(); })
        .then(function(data) {
            helpAiEnabled = data.aiEnabled;
        })
        .catch(function() { helpAiEnabled = false; });
}
checkAiHelp();

function toggleHelpSidebar() {
    var sidebar = document.getElementById('helpSidebar');
    if (!sidebar) createHelpSidebar();
    sidebar = document.getElementById('helpSidebar');

    // Reset conversation if tab changed
    var currentTab = getCurrentTab();
    if (helpSidebarOpen && currentTab === helpLastTab) {
        // Same tab, just close
        helpSidebarOpen = false;
        sidebar.style.right = '-' + (sidebar.offsetWidth + 20) + 'px';
        return;
    }

    if (currentTab !== helpLastTab) {
        // Tab changed — keep chat history so the user can ask follow-ups after
        // navigating where the AI suggested. The backend gets the new currentTab
        // with every askAi call, so context shifts cleanly. We just append a
        // quiet bot note so the user can see the AI is now aware of the new tab.
        helpLastTab = currentTab;
        helpUnansweredCount = 0;
        var area = document.getElementById('helpChatArea');
        if (area) {
            var hasContent = helpAiChatHistory && helpAiChatHistory.length > 0;
            if (hasContent) {
                var activeEl = document.querySelector('.tab.active');
                var tabLabel = activeEl ? activeEl.textContent.trim() : currentTab;
                addChatMsg('<em style="color:#888;font-size:12px;">Now on: <strong>' + tabLabel + '</strong>. Ask a follow-up if you need more help here.</em>', 'bot');
            } else {
                area.innerHTML = '<div class="help-bot-msg">Hi! Ask me anything about PLM Toolkit, or click <strong>Raise Issue</strong> below to report a problem.</div>';
            }
        }
    }

    helpSidebarOpen = true;
    sidebar.style.right = '0';
}

// Close sidebar when clicking outside
document.addEventListener('click', function(e) {
    if (!helpSidebarOpen) return;
    var sidebar = document.getElementById('helpSidebar');
    var triggerBtn = document.getElementById('helpTriggerBtn');
    if (sidebar && !sidebar.contains(e.target) && e.target !== triggerBtn) {
        helpSidebarOpen = false;
        sidebar.style.right = '-' + (sidebar.offsetWidth + 20) + 'px';
    }
});

function createHelpSidebar() {
    var sidebar = document.createElement('div');
    sidebar.id = 'helpSidebar';
    // Restore last user-chosen width, if any
    var savedWidth = parseInt(localStorage.getItem('helpSidebarWidth') || '0', 10);
    if (savedWidth >= 320 && savedWidth <= window.innerWidth * 0.9) {
        sidebar.style.width = savedWidth + 'px';
    }
    sidebar.innerHTML =
        '<div id="helpSidebarResizer" title="Drag to resize"></div>' +
        '<div class="help-sidebar-header">' +
        '  <h3>Help &amp; Support</h3>' +
        '  <button onclick="showSavedQueries()" style="background:rgba(255,255,255,.15); border:none; color:white; font-size:11px; cursor:pointer; padding:3px 8px; border-radius:4px; margin-right:8px;" title="My Saved Queries">\u2605 My Queries</button>' +
        '  <button onclick="toggleHelpSidebar()" style="background:none; border:none; color:white; font-size:20px; cursor:pointer;">&times;</button>' +
        '</div>' +
        '<div id="helpModeBar" style="display:none; align-items:center; justify-content:space-between; gap:10px; padding:8px 14px; background:#fff; border-bottom:1px solid #E8E6DF;">' +
        '  <div style="display:inline-flex; border:1px solid #d0d5dd; border-radius:6px; overflow:hidden;">' +
        '    <button id="helpModeBtnHelp" onclick="helpSetMode(\'help\')" style="border:none; padding:5px 12px; font-size:12px; cursor:pointer; background:#1a3a5c; color:#fff; font-weight:600;">Help</button>' +
        '    <button id="helpModeBtnEng" onclick="helpSetMode(\'eng\')" style="border:none; padding:5px 12px; font-size:12px; cursor:pointer; background:#fff; color:#555;">Engineering</button>' +
        '  </div>' +
        '  <span id="helpModelPill" title="Model answering in this mode" style="display:inline-flex; align-items:center; gap:6px; padding:2px 9px; border-radius:999px; background:#f8f9fa; border:1px solid #E8E6DF; font-family:\'IBM Plex Mono\',Consolas,monospace; font-size:10.5px; color:#6B7280; white-space:nowrap;"><span id="helpModelDot" style="width:6px;height:6px;border-radius:999px;background:#adb5bd;"></span><span id="helpModelName"></span></span>' +
        '</div>' +
        '<div id="helpEngBanner" style="display:none; padding:9px 14px; background:#e8f0fe; border-bottom:1px solid #E8E6DF; font-size:12px; color:#1a3a5c; line-height:1.5;"><strong>Engineering insider mode.</strong> Ask in-depth questions about how the toolkit is built, what it can and can’t do, and what it could do in the future. Hypotheticals welcome.</div>' +
        '<div class="help-sidebar-chat" id="helpChatArea">' +
        '  <div class="help-bot-msg">Hi! Ask me anything about PLM Toolkit, or click <strong>Raise Issue</strong> below to report a problem.</div>' +
        '</div>' +
        '<div class="help-sidebar-chat" id="helpChatAreaEng" style="display:none;"></div>' +
        '<div class="help-sidebar-input">' +
        '  <input type="text" id="helpInput" placeholder="Type or speak a question..." onkeydown="if(event.key===\'Enter\')askHelp()">' +
        '  <select class="micLang" title="Language for speech input"></select>' +
        '  <button type="button" class="micBtn" onclick="micToggle(\'helpInput\', this, \'#helpAskBtn\')" title="Speak your question">\uD83C\uDFA4</button>' +
        '  <button id="helpAskBtn" onclick="askHelp()" style="background:#1a3a5c; color:white; border:none; border-radius:6px; padding:8px 12px; cursor:pointer; font-size:13px;">Ask</button>' +
        '</div>' +
        '<div class="help-sidebar-actions">' +
        '  <div style="display:flex;gap:8px;">' +
        '    <button onclick="showSavedQueries()" style="flex:1;padding:10px;background:#f8f9fa;border:1px solid #d0d5dd;border-radius:6px;cursor:pointer;font-size:13px;font-weight:500;color:#1a3a5c;">\u2605 My Queries</button>' +
        '    <button onclick="openRaiseIssue()" class="help-raise-btn" style="flex:1;">Raise Issue</button>' +
        '  </div>' +
        '</div>';
    document.body.appendChild(sidebar);
    initHelpSidebarResize(sidebar);
    // Populate the language picker next to the mic button (mic.js exposes this).
    if (typeof window.micRefresh === 'function') window.micRefresh();
    helpLoadRoleInfo();
}

/** Fetch /api/permissions/me once and reveal the mode toggle for role holders.
 *  Non-holders: helpModeBar stays display:none and nothing else changes. */
function helpLoadRoleInfo() {
    if (helpRoleInfo !== null) { helpApplyRoleUi(); return; }
    fetch('/api/permissions/me', { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
            helpRoleInfo = data || {};
            helpApplyRoleUi();
        })
        .catch(function () { helpRoleInfo = {}; });
}

function helpApplyRoleUi() {
    if (!helpRoleInfo || !helpRoleInfo.isEngineeringInsider) return;
    var bar = document.getElementById('helpModeBar');
    if (bar) bar.style.display = 'flex';
    helpUpdateModePill();
}

function helpUpdateModePill() {
    var pill = document.getElementById('helpModelPill');
    var dot = document.getElementById('helpModelDot');
    var name = document.getElementById('helpModelName');
    if (!pill || !name) return;
    if (helpMode === 'eng') {
        var slug = helpEngLastModel || (helpRoleInfo && helpRoleInfo.engineeringModel) || '';
        name.textContent = helpShortModel(slug) + ' · pinned';
        pill.style.background = '#e8f0fe';
        pill.style.borderColor = '#4a6fa5';
        pill.style.color = '#1a3a5c';
        if (dot) dot.style.background = '#4a6fa5';
        pill.title = 'Engineering mode is pinned to Claude Opus 4.8';
    } else {
        name.textContent = 'claude-sonnet-4-6';
        pill.style.background = '#f8f9fa';
        pill.style.borderColor = '#E8E6DF';
        pill.style.color = '#6B7280';
        if (dot) dot.style.background = '#adb5bd';
        pill.title = 'Production default help model';
    }
}

/** Switch between Help and Engineering. Each mode keeps its own chat DOM +
 *  history; switching just swaps visibility. */
function helpSetMode(mode) {
    if (mode === 'eng' && (!helpRoleInfo || !helpRoleInfo.isEngineeringInsider)) return;
    helpMode = mode;
    var isEng = mode === 'eng';
    var areaHelp = document.getElementById('helpChatArea');
    var areaEng = document.getElementById('helpChatAreaEng');
    var banner = document.getElementById('helpEngBanner');
    var input = document.getElementById('helpInput');
    var btnHelp = document.getElementById('helpModeBtnHelp');
    var btnEng = document.getElementById('helpModeBtnEng');
    if (areaHelp) areaHelp.style.display = isEng ? 'none' : '';
    if (areaEng) areaEng.style.display = isEng ? '' : 'none';
    if (banner) banner.style.display = isEng ? '' : 'none';
    if (input) input.placeholder = isEng ? 'Ask about the architecture, or a what-if…' : 'Type or speak a question...';
    if (btnHelp) { btnHelp.style.background = isEng ? '#fff' : '#1a3a5c'; btnHelp.style.color = isEng ? '#555' : '#fff'; btnHelp.style.fontWeight = isEng ? '400' : '600'; }
    if (btnEng)  { btnEng.style.background  = isEng ? '#1a3a5c' : '#fff'; btnEng.style.color  = isEng ? '#fff' : '#555'; btnEng.style.fontWeight  = isEng ? '600' : '400'; }
    helpUpdateModePill();
    if (isEng && areaEng && areaEng.childNodes.length === 0) helpRenderEngEmptyState(areaEng);
}

function helpRenderEngEmptyState(area) {
    var html = '<div class="help-bot-msg">You have the <strong>Engineering Insider</strong> role. Ask me anything about how this site was built — architecture, data flows, AI stack, what it can and can’t do, or what it could do in the future. Hypotheticals welcome.</div>';
    html += '<div class="help-bot-msg"><div style="font-family:\'IBM Plex Mono\',Consolas,monospace;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;">Try asking</div>';
    helpEngSuggestions.forEach(function (q, i) {
        html += '<button onclick="helpAskEngSuggestion(' + i + ')" style="display:block;width:100%;text-align:left;background:#fff;border:1px solid #E8E6DF;border-radius:6px;padding:7px 10px;margin:4px 0;font-size:12.5px;color:#1a3a5c;cursor:pointer;">' + q + ' →</button>';
    });
    html += '</div>';
    area.innerHTML = html;
}

function helpAskEngSuggestion(i) {
    var q = helpEngSuggestions[i];
    if (!q) return;
    addChatMsg(q.replace(/</g, '&lt;'), 'user');
    askAi(q);
}

/** Drag-to-resize: grab the left edge of the sidebar and drag horizontally.
 *  Width is persisted to localStorage so it sticks across page loads. */
function initHelpSidebarResize(sidebar) {
    var handle = document.getElementById('helpSidebarResizer');
    if (!handle) return;
    var startX = 0, startWidth = 0, dragging = false;

    handle.addEventListener('mousedown', function(e) {
        dragging = true;
        startX = e.clientX;
        startWidth = sidebar.offsetWidth;
        // Disable the slide transition while dragging so it tracks the cursor
        sidebar.style.transition = 'none';
        document.body.style.userSelect = 'none';
        e.preventDefault();
    });

    document.addEventListener('mousemove', function(e) {
        if (!dragging) return;
        var newWidth = startWidth + (startX - e.clientX);
        var min = 320, max = Math.floor(window.innerWidth * 0.9);
        if (newWidth < min) newWidth = min;
        if (newWidth > max) newWidth = max;
        sidebar.style.width = newWidth + 'px';
    });

    document.addEventListener('mouseup', function() {
        if (!dragging) return;
        dragging = false;
        sidebar.style.transition = '';
        document.body.style.userSelect = '';
        localStorage.setItem('helpSidebarWidth', String(sidebar.offsetWidth));
    });
}

function askHelp() {
    var input = document.getElementById('helpInput');
    var query = input.value.trim();
    if (!query) return;
    input.value = '';

    addChatMsg(query, 'user');

    // Intercept "what's new" queries — return the actual changelog.
    // Engineering mode has NO client interceptors: everything goes to the LLM.
    var lq = query.toLowerCase();
    if (helpMode !== 'eng' && (lq.indexOf('what') >= 0 && lq.indexOf('new') >= 0 || lq === 'changelog' || lq === 'updates' || lq === 'release notes')) {
        addChatMsg(buildWhatsNewAnswer(), 'bot');
        return;
    }

    // Always try the server first — it handles docs search, data queries, and AI
    // Only fall back to local keyword FAQ if the server is completely unreachable
    askAi(query);
}

function askAi(query) {
    var isEng = helpMode === 'eng';
    var historyArr = isEng ? engAiChatHistory : helpAiChatHistory;
    historyArr.push({ role: 'user', content: query });

    // Show typing indicator (in whichever chat area is active)
    var area = document.getElementById(isEng ? 'helpChatAreaEng' : 'helpChatArea');
    var typing = document.createElement('div');
    typing.className = 'help-bot-msg';
    typing.id = 'helpTyping';
    typing.innerHTML = '<em style="color:#999;">Thinking...</em>';
    area.appendChild(typing);
    area.scrollTop = area.scrollHeight;

    var payload = {
        question: query,
        currentTab: getCurrentTab(),
        history: historyArr.slice(-8)
    };
    if (isEng) payload.mode = 'engineering';

    fetch('/api/help/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
    .then(function(res) {
        if (res.status === 401) {
            var typingEl = document.getElementById('helpTyping');
            if (typingEl) typingEl.remove();
            addChatMsg('Your session has expired. Please <a href="/login.html" style="color:#4a6fa5; font-weight:600;">log in again</a> to continue.', 'bot');
            return null;
        }
        return res.json();
    })
    .then(function(data) {
        if (!data) return;
        var typingEl = document.getElementById('helpTyping');
        if (typingEl) typingEl.remove();

        if (data.success) {
            helpUnansweredCount = 0;

            // Handle special actions
            if (data.action === 'showSavedQueries') {
                showSavedQueries();
                historyArr.push({ role: 'assistant', content: 'Showing saved queries.' });
                return;
            }

            var answer;
            if (isEng) {
                // Engineering answers are plain text from the model \u2014 escape,
                // then newline-to-br, and stamp the mono model footer.
                if (data.model) { helpEngLastModel = data.model; helpUpdateModePill(); }
                answer = String(data.answer || '')
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/\n/g, '<br>');
                answer += '<div style="margin-top:8px;padding-top:6px;border-top:1px solid #eee;font-family:\'IBM Plex Mono\',Consolas,monospace;font-size:9.5px;letter-spacing:0.05em;color:#9aa1a9;">' +
                    helpShortModel(data.model || (helpRoleInfo && helpRoleInfo.engineeringModel) || '') + ' \u00B7 engineering mode</div>';
            } else {
                answer = data.answer.replace(/\n/g, '<br>');
            }
            // Add feedback button on all AI responses
            answer += '<div style="margin-top:10px;border-top:1px solid #eee;padding-top:8px;">' +
                '<button onclick="reportUnhelpful(this)" data-q="' + encodeURIComponent(query) + '" data-a="' + encodeURIComponent(data.answer.substring(0, Math.min(data.answer.length, 2000))) + '" ' +
                'style="font-size:11px;color:#999;background:none;border:1px solid #ddd;border-radius:4px;padding:3px 10px;cursor:pointer;">' +
                '\uD83D\uDC4E Not helpful \u2014 flag for admin</button></div>';
            addChatMsg(answer, 'bot');
            historyArr.push({ role: 'assistant', content: data.answer });
        } else if (data.fallback && !isEng) {
            // Fall back to keyword search (help mode only \u2014 engineering never
            // silently degrades to a different answer path)
            askKeyword(query);
        } else {
            addChatMsg((data.message || 'Sorry, something went wrong.').replace(/</g, '&lt;'), 'bot');
        }
    })
    .catch(function() {
        var typingEl = document.getElementById('helpTyping');
        if (typingEl) typingEl.remove();
        if (isEng) {
            addChatMsg('Could not reach the server. Please try again.', 'bot');
        } else {
            // Fall back to keyword search
            askKeyword(query);
        }
    });
}

function askKeyword(query) {
    var answer = searchFAQ(query);
    if (answer) {
        helpUnansweredCount = 0;
        addChatMsg(answer, 'bot');
    } else {
        helpUnansweredCount++;
        if (helpUnansweredCount >= 3) {
            addChatMsg("I'm not able to find an answer for that. Would you like to <strong>Raise an Issue</strong>? Our admin team at pdl-plm-admin@sandisk.com can help you directly.", 'bot');
            helpUnansweredCount = 0;
        } else {
            addChatMsg("I'm not sure about that. Try different keywords, or check the <a href='/user-guide.html' target='_blank' style='color:#4a6fa5;'>User Guide</a> for detailed documentation.", 'bot');
        }
    }
}

function buildWhatsNewAnswer() {
    if (typeof WHATS_NEW_RELEASES === 'undefined' || WHATS_NEW_RELEASES.length === 0) {
        return 'No release notes available.';
    }
    var html = '';
    // Show the latest 2 releases
    var showCount = Math.min(2, WHATS_NEW_RELEASES.length);
    for (var r = 0; r < showCount; r++) {
        var rel = WHATS_NEW_RELEASES[r];
        html += '<div style="margin-bottom:12px;">';
        html += '<strong style="color:#1a3a5c;">' + rel.date + ' — ' + rel.title + '</strong>';
        html += '<ul style="margin:6px 0 0 0; padding-left:16px;">';
        rel.items.forEach(function(item) {
            var color = item.badge === 'new' ? '#155724' : item.badge === 'fix' ? '#856404' : '#1a3a5c';
            var bg = item.badge === 'new' ? '#d4edda' : item.badge === 'fix' ? '#fff3cd' : '#e8f0fe';
            var label = item.badge === 'new' ? 'New' : item.badge === 'fix' ? 'Fix' : 'Improved';
            html += '<li style="margin:3px 0; font-size:12px;"><span style="display:inline-block; font-size:9px; font-weight:700; text-transform:uppercase; padding:1px 5px; border-radius:3px; background:' + bg + '; color:' + color + '; margin-right:4px;">' + label + '</span>' + item.text + '</li>';
        });
        html += '</ul></div>';
    }
    html += '<div style="font-size:11px; color:#888; margin-top:8px;">Click <strong>What\'s New</strong> in the top nav for the full timeline.</div>';
    return html;
}

function getCurrentTab() {
    var active = document.querySelector('.tab.active');
    if (!active) return '';
    var text = active.textContent.trim().toLowerCase();
    if (text.indexOf('field') >= 0) return 'changes';
    if (text === 'bom compare') {
        return (typeof bomCompareMode !== 'undefined' && bomCompareMode === 'revs') ? 'revcompare' : 'bomcompare';
    }
    if (text.indexOf('bom') >= 0) return 'bom';
    if (text.indexOf('part') >= 0) return 'parts';
    if (text.indexOf('agile') >= 0) return 'agile';
    if (text.indexOf('history') >= 0) return 'history';
    if (text.indexOf('utilit') >= 0 || text.indexOf('report') >= 0) return 'reports';
    if (text === 'change reviews') return 'changereviews';
    if (text.indexOf('compare') >= 0) return 'compare';
    if (text.indexOf('sku') >= 0) return 'sku';
    if (text.indexOf('extension') >= 0) return 'extensions';
    if (text.indexOf('ad health') >= 0) return 'adhealth';
    return '';
}

function searchFAQ(query) {
    var q = query.toLowerCase();
    var currentTab = getCurrentTab();
    var bestMatch = null;
    var bestScore = 0;

    for (var i = 0; i < HELP_FAQ.length; i++) {
        var faq = HELP_FAQ[i];
        var score = 0;
        for (var j = 0; j < faq.keywords.length; j++) {
            if (q.indexOf(faq.keywords[j]) >= 0) score += 2;
            var words = faq.keywords[j].split(' ');
            for (var w = 0; w < words.length; w++) {
                if (words[w].length > 3 && q.indexOf(words[w]) >= 0) score += 1;
            }
        }
        // Boost score if FAQ is for the current tab
        if (score > 0 && faq.tab && faq.tab === currentTab) score += 5;
        // Penalize if FAQ is for a different specific tab
        if (score > 0 && faq.tab && faq.tab !== currentTab) score -= 2;

        if (score > bestScore) {
            bestScore = score;
            bestMatch = faq;
        }
    }

    return bestScore >= 2 ? bestMatch.answer : null;
}

function addChatMsg(text, type) {
    var area = document.getElementById(helpMode === 'eng' ? 'helpChatAreaEng' : 'helpChatArea');
    var div = document.createElement('div');
    div.className = type === 'user' ? 'help-user-msg' : 'help-bot-msg';
    div.innerHTML = text;
    area.appendChild(div);
    area.scrollTop = area.scrollHeight;
}

// === Raise Issue with Screenshot ===
function openRaiseIssue() {
    var area = document.getElementById('helpChatArea');
    area.innerHTML +=
        '<div class="help-bot-msg">' +
        '<strong>Raise an Issue</strong><br><br>' +
        '<label style="font-size:12px; font-weight:600;">Describe your issue:</label><br>' +
        '<textarea id="issueDescription" rows="4" style="width:100%; border:1px solid #d0d5dd; border-radius:6px; padding:8px; font-size:13px; font-family:inherit; margin:4px 0 8px; resize:vertical;" placeholder="What happened? What did you expect?"></textarea><br>' +
        '<label style="font-size:12px; cursor:pointer;"><input type="checkbox" id="issueScreenshot" checked> Attach screenshot of current page</label><br><br>' +
        '<button onclick="submitIssue()" style="background:#1a3a5c; color:white; border:none; border-radius:6px; padding:8px 16px; cursor:pointer; font-size:13px; font-weight:600;">Submit Issue</button>' +
        ' <button onclick="cancelIssue()" style="background:#e9ecef; color:#555; border:none; border-radius:6px; padding:8px 16px; cursor:pointer; font-size:13px;">Cancel</button>' +
        '</div>';
    area.scrollTop = area.scrollHeight;
}

function cancelIssue() {
    addChatMsg('Issue cancelled. Let me know if you need anything else!', 'bot');
}

function submitIssue() {
    var desc = document.getElementById('issueDescription').value.trim();
    if (!desc) {
        appAlert('Please describe your issue.');
        return;
    }
    var includeScreenshot = document.getElementById('issueScreenshot').checked;

    // Temporarily hide sidebar for clean screenshot
    var sidebar = document.getElementById('helpSidebar');
    sidebar.style.display = 'none';

    var sendIssue = function(screenshotData) {
        sidebar.style.display = '';

        addChatMsg('Submitting your issue...', 'bot');

        fetch('/api/support/raise-issue', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                description: desc,
                screenshot: screenshotData || null,
                currentUrl: window.location.href,
                currentTab: document.querySelector('.tab.active') ? document.querySelector('.tab.active').textContent : 'unknown'
            })
        })
        .then(function(res) {
            if (res.status === 401) {
                addChatMsg('Your session has expired. Please <a href="/login.html" style="color:#4a6fa5; font-weight:600;">log in again</a> to submit the issue.', 'bot');
                return null;
            }
            return res.json();
        })
        .then(function(data) {
            if (!data) return;
            if (data.success) {
                var ptLine = data.ptId ? '<br><br>Tracking id: <strong>' + data.ptId + '</strong> &mdash; you (or an admin) can find it in the Feedback Queue.' : '';
                addChatMsg('Your issue has been submitted successfully! The PLM admin team will review it. You will receive a confirmation at your email address.' + ptLine, 'bot');
            } else {
                addChatMsg('Failed to submit: ' + (data.message || 'Unknown error') + '. Please email pdl-plm-admin@sandisk.com directly.', 'bot');
            }
        })
        .catch(function() {
            addChatMsg('Could not submit the issue. Please email pdl-plm-admin@sandisk.com directly with your issue description.', 'bot');
            sidebar.style.display = '';
        });
    };

    if (includeScreenshot && typeof html2canvas === 'function') {
        html2canvas(document.body, { scale: 0.5, logging: false, useCORS: true }).then(function(canvas) {
            var screenshotData = canvas.toDataURL('image/png');
            sendIssue(screenshotData);
        }).catch(function() {
            sendIssue(null);
        });
    } else {
        sidebar.style.display = '';
        sendIssue(null);
    }
}

// ── Saved Chat Queries ──

function saveChatQuery(sqlB64, btn) {
    appPrompt('Name this query:', 'My query').then(function (name) {
        if (!name) return;
        var rawSql = atob(sqlB64);
        btn.disabled = true; btn.textContent = 'Saving\u2026';

        fetch('/api/help/queries', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({name: name, sql: rawSql})
        })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.ok) {
                btn.textContent = '\u2713 Saved';
                btn.style.color = '#28a745'; btn.style.borderColor = '#28a745';
                addChatMsg('<div style="font-size:12px;color:#28a745;"><strong>\u2605 Query saved:</strong> ' + name +
                    '<br><span style="color:#888;">Access it from <strong>My Queries</strong> in the chatbot. You can also schedule or share it.</span></div>', 'bot');
            } else {
                btn.textContent = 'Failed'; btn.disabled = false;
            }
        }).catch(function() { btn.textContent = 'Failed'; btn.disabled = false; });
    });
}

function showSavedQueries() {
    fetch('/api/help/queries').then(function(r) { return r.json(); }).then(function(data) {
        var mine = data.mine || [];
        var shared = data.shared || [];
        var html = '<div style="font-size:13px;">';

        if (mine.length === 0 && shared.length === 0) {
            html += '<p style="color:#888;">No saved queries yet. Run a data query and click <strong>\u2605 Save</strong> to save it.</p>';
        }

        if (mine.length > 0) {
            html += '<p style="font-weight:600;margin:0 0 8px;">My Queries</p>';
            mine.forEach(function(q) {
                var schedBadge = q.scheduleFreq ? ' <span style="font-size:10px;background:#e8f0fe;color:#1a3a5c;padding:1px 5px;border-radius:3px;">' + q.scheduleFreq + ' ' + (q.scheduleTime || '') + '</span>' : '';
                var sharedBadge = q.shared ? ' <span style="font-size:10px;background:#e8f5e9;color:#1a6e34;padding:1px 5px;border-radius:3px;">shared</span>' : '';
                html += '<div style="padding:8px 10px;border:1px solid #eee;border-radius:6px;margin-bottom:6px;">';
                html += '<div style="display:flex;align-items:center;gap:6px;">';
                html += '<strong style="flex:1;cursor:pointer;color:#1a3a5c;" onclick="runSavedQuery(\'' + q.id + '\',\'' + btoa(q.sql) + '\')">' + q.name + '</strong>';
                html += schedBadge + sharedBadge;
                html += '</div>';
                html += '<div style="margin-top:6px;display:flex;gap:6px;font-size:11px;">';
                html += '<button onclick="runSavedQuery(\'' + q.id + '\',\'' + btoa(q.sql) + '\')" style="padding:2px 8px;background:#1a3a5c;color:#fff;border:none;border-radius:3px;cursor:pointer;">Run</button>';
                html += '<button onclick="exportSavedQuery(\'' + q.id + '\',this)" style="padding:2px 8px;background:#fff;color:#555;border:1px solid #ccc;border-radius:3px;cursor:pointer;">Export</button>';
                html += '<button onclick="toggleShareQuery(\'' + q.id + '\',this)" style="padding:2px 8px;background:#fff;color:#555;border:1px solid #ccc;border-radius:3px;cursor:pointer;">' + (q.shared ? '\uD83D\uDD17 Unshare' : '\uD83D\uDD12 Share') + '</button>';
                html += '<button onclick="showScheduleQuery(\'' + q.id + '\')" style="padding:2px 8px;background:#fff;color:#555;border:1px solid #ccc;border-radius:3px;cursor:pointer;">\u23F0 Schedule</button>';
                html += '<button onclick="deleteSavedQuery(\'' + q.id + '\')" style="padding:2px 8px;background:#fff;color:#dc3545;border:1px solid #dc3545;border-radius:3px;cursor:pointer;">\u2717</button>';
                html += '</div></div>';
            });
        }

        if (shared.length > 0) {
            html += '<p style="font-weight:600;margin:12px 0 8px;">Shared by team</p>';
            shared.forEach(function(q) {
                html += '<div style="padding:8px 10px;border:1px solid #eee;border-radius:6px;margin-bottom:6px;">';
                html += '<strong style="cursor:pointer;color:#1a3a5c;" onclick="runSavedQuery(\'' + q.id + '\',\'' + btoa(q.sql) + '\')">' + q.name + '</strong>';
                html += '<span style="font-size:11px;color:#888;margin-left:8px;">by ' + (q.ownerDisplay || q.owner) + '</span>';
                html += '<div style="margin-top:4px;"><button onclick="runSavedQuery(\'' + q.id + '\',\'' + btoa(q.sql) + '\')" style="font-size:11px;padding:2px 8px;background:#1a3a5c;color:#fff;border:none;border-radius:3px;cursor:pointer;">Run</button></div>';
                html += '</div>';
            });
        }

        html += '</div>';
        addChatMsg(html, 'bot');
    });
}

function runSavedQuery(id, sqlB64) {
    addChatMsg('Running saved query\u2026', 'bot');

    fetch('/api/help/queries/' + id + '/run', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'}
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        // Remove "Running..." message
        var msgs = document.querySelectorAll('.help-bot-msg');
        for (var i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].textContent.indexOf('Running saved query') >= 0) { msgs[i].remove(); break; }
        }

        if (data.error) {
            addChatMsg('<p style="color:#dc3545;">' + data.error + '</p>', 'bot');
            return;
        }

        var cols = data.columns || [];
        var rows = data.rows || [];
        var total = data.totalCount || rows.length;
        var sql = data.sql || '';
        var sqlB64 = btoa(sql);

        // Show query name and filter conditions
        var html = '';
        if (data.name) html += '<p style="font-weight:600;color:#1a3a5c;margin:0 0 4px;">\u2605 ' + data.name + '</p>';
        if (sql.indexOf('JSON_CACHE:') === 0) {
            try {
                var spec = JSON.parse(sql.substring(11));
                var filterParts = [];
                (spec.filters || []).forEach(function(f) {
                    var opLabel = {eq:'=',neq:'!=',like:'contains',in:'in',notin:'not in',gte:'>=',gt:'>',lte:'<=',lt:'<',notnull:'is not empty'}[f.op] || f.op;
                    filterParts.push(f.col + ' ' + opLabel + ' ' + (typeof f.val === 'object' ? JSON.stringify(f.val) : "'" + f.val + "'"));
                });
                if (filterParts.length > 0) {
                    var fullFilter = filterParts.join(' AND ');
                    var shortFilter = fullFilter.length > 120 ? fullFilter.substring(0, 117) + '\u2026' : fullFilter;
                    html += '<div style="font-size:10.5px;color:#888;margin:0 0 8px;font-family:monospace;word-break:break-word;position:relative;">';
                    html += '<span id="filterShort_' + id + '">Filter: ' + shortFilter + '</span>';
                    if (fullFilter.length > 120) {
                        html += ' <button onclick="toggleFullFilter(\'' + id + '\')" style="font-size:10px;color:#4a6fa5;background:none;border:none;cursor:pointer;text-decoration:underline;">show all</button>';
                        html += '<div id="filterFull_' + id + '" style="display:none;margin-top:4px;background:#f8f9fa;border:1px solid #e0e0e0;border-radius:4px;padding:8px;white-space:pre-wrap;">Filter: ' + fullFilter + '</div>';
                    }
                    html += ' <button onclick="copyFilter(\'' + id + '\',this)" data-filter="' + encodeURIComponent('Filter: ' + fullFilter) + '" style="font-size:10px;color:#4a6fa5;background:none;border:none;cursor:pointer;text-decoration:underline;">copy</button>';
                    html += '</div>';
                }
            } catch(e) {}
        } else if (sql) {
            html += '<p style="font-size:11px;color:#888;margin:0 0 8px;font-family:monospace;word-break:break-all;">' + sql.substring(0,200) + (sql.length>200?'\u2026':'') + '</p>';
        }
        html += '<p><strong>' + (total > rows.length ? total + '</strong> total results (showing first ' + rows.length + '):' : rows.length + '</strong> results:') + '</p>';
        html += '<div style="overflow-x:auto;margin:8px 0;">';
        html += '<table style="border-collapse:collapse;font-size:11px;font-family:monospace;width:100%;">';
        html += '<tr>';
        cols.forEach(function(c) {
            html += '<th style="text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;white-space:nowrap;font-size:10px;color:#666;">' + c + '</th>';
        });
        html += '</tr>';
        rows.forEach(function(row) {
            html += '<tr>';
            cols.forEach(function(c) {
                html += '<td style="padding:3px 8px;border-bottom:1px solid #eee;white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;">' + (row[c] || '') + '</td>';
            });
            html += '</tr>';
        });
        html += '</table></div>';

        // Buttons
        html += '<div style="margin-top:10px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;">';
        html += '<button onclick="clearChatQueryContext(this)" style="font-size:12px;padding:5px 12px;background:#fff;color:#555;border:1px solid #ccc;border-radius:5px;cursor:pointer;">New Query</button>';
        if (sql.indexOf('JSON_CACHE:') === 0) {
            // Cache query — use cache export
            var filterJson = sql.substring(11);
            try {
                var spec = JSON.parse(filterJson);
                var exportCols = (spec.select || ['PART_NUMBER','DESCRIPTION','REV']).join(',');
                html += '<button onclick="exportCacheQuery(this.dataset.filter,this.dataset.cols,this)" data-filter="' + btoa(filterJson) + '" data-cols="' + btoa(exportCols) + '" style="font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;">\u2913 Export all to Excel</button>';
            } catch(e) {}
            html += '<span style="font-size:11px;color:#28a745;">\u26A1 Item Cache (JSON)</span>';
        } else {
            html += '<button onclick="exportChatQuery(this.dataset.sql,this)" data-sql="' + btoa(sql) + '" style="font-size:12px;padding:5px 12px;background:#1a3a5c;color:#fff;border:none;border-radius:5px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;">\u2913 Export all to Excel</button>';
        }
        html += '</div>';

        addChatMsg(html, 'bot');
    })
    .catch(function(err) {
        addChatMsg('<p style="color:#dc3545;">Failed to run query: ' + err.message + '</p>', 'bot');
    });
}

function exportSavedQuery(id, btn) {
    btn.disabled = true; btn.textContent = 'Exporting\u2026';
    // First check what kind of query this is
    fetch('/api/help/queries/' + id + '/run', { method: 'POST', headers: {'Content-Type':'application/json'} })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.error) throw new Error(data.error);
            var sql = data.sql || '';
            if (sql.startsWith('JSON_CACHE:')) {
                // Export via cache endpoint
                var filterJson = sql.substring(11);
                var spec = JSON.parse(filterJson);
                var cols = (spec.select || ['PART_NUMBER','DESCRIPTION','REV']).join(',');
                var filterB64 = btoa(filterJson);
                var colsB64 = btoa(cols);
                return fetch('/api/help/export-cache', {
                    method: 'POST',
                    headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({filter: filterB64, cols: colsB64})
                });
            } else {
                // Export via SQL endpoint
                return fetch('/api/help/export-query', {
                    method: 'POST',
                    headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({sql: btoa(sql)})
                });
            }
        })
        .then(function(r) {
            if (!r.ok) throw new Error('Export failed');
            return r.blob();
        })
        .then(function(blob) {
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'SavedQuery-' + new Date().toISOString().slice(0,10) + '.xlsx';
            a.click(); URL.revokeObjectURL(a.href);
            btn.textContent = '\u2713'; setTimeout(function() { btn.textContent = 'Export'; btn.disabled = false; }, 2000);
        })
        .catch(function(err) { btn.textContent = 'Failed'; setTimeout(function() { btn.textContent = 'Export'; btn.disabled = false; }, 2000); });
}

function toggleShareQuery(id, btn) {
    fetch('/api/help/queries/' + id + '/share', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.ok) btn.textContent = data.shared ? '\uD83D\uDD17 Unshare' : '\uD83D\uDD12 Share';
        });
}

function showScheduleQuery(id) {
    // Sequential prompt chain: frequency -> day-of-week (if weekly) -> time (if scheduling) -> email.
    appPrompt('Schedule frequency: daily or weekly (leave blank to remove schedule)', '', { placeholder: 'daily / weekly / (blank)' }).then(function (freq) {
        if (freq === null) return;
        var dayPromise = (freq === 'weekly')
            ? appPrompt('Day of week (MON, TUE, WED, THU, FRI):', 'MON')
            : Promise.resolve('');
        dayPromise.then(function (day) {
            if (freq === 'weekly' && !day) return;
            var timePromise = freq
                ? appPrompt('Time (HH:mm, 24hr format):', '08:00')
                : Promise.resolve('');
            timePromise.then(function (time) {
                if (freq && !time) return;
                appPrompt('Email for results:', '', { placeholder: 'name@sandisk.com' }).then(function (email) {
                    if (freq && !email) return;
                    fetch('/api/help/queries/' + id + '/schedule', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({freq: freq, day: day, time: time, email: email})
                    }).then(function(r) { return r.json(); }).then(function(data) {
                        if (data.ok) {
                            addChatMsg(freq ? '<div style="font-size:12px;color:#28a745;">\u23F0 Scheduled <strong>' + freq + '</strong> at <strong>' + time + '</strong>. Results will be emailed to <strong>' + email + '</strong>.</div>'
                                : '<div style="font-size:12px;color:#888;">Schedule removed.</div>', 'bot');
                        }
                    });
                });
            });
        });
    });
}

function deleteSavedQuery(id) {
    appConfirm('Delete this saved query?', { danger: true, okText: 'Delete' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/help/queries/' + id, { method: 'DELETE' })
            .then(function() { addChatMsg('<span style="color:#888;">Query deleted.</span>', 'bot'); });
    });
}

// Toggle full filter display
function toggleFullFilter(id) {
    var full = document.getElementById('filterFull_' + id);
    if (full) full.style.display = full.style.display === 'none' ? 'block' : 'none';
}

// Copy filter text
function copyFilter(id, btn) {
    var text = decodeURIComponent(btn.dataset.filter || '');
    navigator.clipboard.writeText(text).then(function() {
        btn.textContent = '\u2713 copied';
        setTimeout(function() { btn.textContent = 'copy'; }, 2000);
    });
}

// Report unhelpful AI response
function reportUnhelpful(btn) {
    var q = decodeURIComponent(btn.dataset.q || '');
    var a = decodeURIComponent(btn.dataset.a || '');
    var tab = '';
    try { tab = document.querySelector('.tab.active') ? document.querySelector('.tab.active').textContent.trim() : ''; } catch(e){}

    btn.disabled = true;
    btn.textContent = 'Sending feedback\u2026';

    fetch('/api/help/feedback', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({question: q, answer: a, tab: tab})
    })
    .then(function(res) { return res.json(); })
    .then(function() {
        btn.textContent = '\u2713 Flagged \u2014 admin team notified';
        btn.style.color = '#28a745';
        btn.style.borderColor = '#28a745';
    })
    .catch(function() {
        btn.textContent = 'Failed to send';
        btn.style.color = '#dc3545';
    });
}

// Clear data query context
function clearChatQueryContext(btn) {
    fetch('/api/help/clear-query', { method: 'POST' })
        .then(function() {
            btn.textContent = '\u2713 Cleared';
            btn.disabled = true;
            setTimeout(function() { btn.textContent = 'New Query'; btn.disabled = false; }, 2000);
        });
}

// Export external source results to Excel
function exportExtSource(paramsB64, btn) {
    var origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;"></span> Exporting\u2026';

    fetch('/api/help/export-ext', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({params: paramsB64})
    })
    .then(function(res) {
        if (!res.ok) throw new Error('Export failed');
        return res.blob();
    })
    .then(function(blob) {
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'PLM-ExtSource-' + new Date().toISOString().slice(0, 10) + '.xlsx';
        a.click();
        URL.revokeObjectURL(a.href);
        btn.innerHTML = '\u2713 Downloaded';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; }, 3000);
    })
    .catch(function(err) {
        btn.innerHTML = '\u2717 ' + err.message;
        btn.style.background = '#dc3545';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; btn.style.background = '#1a3a5c'; }, 4000);
    });
}

// Export JSON cache query results to Excel
function exportCacheQuery(filterB64, colsB64, btn) {
    var origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;"></span> Exporting\u2026';
    var t0 = Date.now();

    fetch('/api/help/export-cache', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({filter: filterB64, cols: colsB64})
    })
    .then(function(res) {
        if (!res.ok) throw new Error('Export failed');
        var serverMs = res.headers.get('X-Export-Time-Ms') || '?';
        var rowCount = res.headers.get('X-Export-Rows') || '?';
        return res.blob().then(function(blob) { return {blob:blob, serverMs:serverMs, rowCount:rowCount}; });
    })
    .then(function(data) {
        var totalMs = Date.now() - t0;
        var a = document.createElement('a');
        a.href = URL.createObjectURL(data.blob);
        a.download = 'PLM-Query-' + data.rowCount + 'rows-' + new Date().toISOString().slice(0, 10) + '.xlsx';
        a.click(); URL.revokeObjectURL(a.href);
        btn.innerHTML = '\u2713 ' + data.rowCount + ' rows \u00b7 ' + data.serverMs + 'ms server \u00b7 ' + totalMs + 'ms total';
        btn.style.background = '#28a745';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; btn.style.background = '#1a3a5c'; }, 5000);
    })
    .catch(function(err) {
        btn.innerHTML = '\u2717 ' + err.message;
        btn.style.background = '#dc3545';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; btn.style.background = '#1a3a5c'; }, 4000);
    });
}

// Export data query results to Excel
function exportChatQuery(sqlB64, btn) {
    // Decode base64-encoded SQL
    var rawSql = atob(sqlB64);

    // Show spinner
    var origHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;"></span> Exporting\u2026';

    fetch('/api/help/export-query', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({sql: rawSql})
    })
    .then(function(res) {
        if (!res.ok) throw new Error('Export failed (HTTP ' + res.status + ')');
        return res.blob();
    })
    .then(function(blob) {
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'PLM-Query-' + new Date().toISOString().slice(0, 10) + '.xlsx';
        a.click();
        URL.revokeObjectURL(a.href);
        btn.innerHTML = '\u2713 Downloaded';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; }, 3000);
    })
    .catch(function(err) {
        btn.innerHTML = '\u2717 ' + err.message;
        btn.style.background = '#dc3545';
        setTimeout(function() { btn.innerHTML = origHtml; btn.disabled = false; btn.style.background = '#1a3a5c'; }, 4000);
    });
}
