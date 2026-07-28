package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the Agent API surface. The /catalog endpoint renders
 * this list, and AgentApiCatalogParityTest asserts these paths exactly match the
 * paths mapped on AgentApiController — so the catalog can never drift from reality.
 */
@Component
public class AgentEndpointRegistry {

    private final List<AgentEndpoint> endpoints = new ArrayList<>();

    public AgentEndpointRegistry() {
        // --- Items / Parts (Task 7 adds parts/search) ---
        add("GET", "/api/agent/items/columns", "Items",
            "List searchable item columns and the operators allowed on each.",
            "{ columns: [ {name, label, type, operators[]} ] }");
        add("GET", "/api/agent/items/distinct", "Items",
            "Distinct values for a categorical item column.",
            "{ column, values: [string] }",
            p("column", "string", true, "Column key from /items/columns (categorical only)"));
        add("POST", "/api/agent/items/search", "Items",
            "Attribute search over items. Body: { conditions:[{connector,column,operator,value,values[]}], columns:[string] }.",
            "{ rows:[{col:value}], columns:[string], matchedCount, truncated, elapsedMs }");
        add("GET", "/api/agent/parts/search", "Items",
            "Part-extract search over item_extract for one or more item numbers.",
            "{ count, data:[{col:value}] }",
            p("items", "csv", true, "Comma-separated item numbers"),
            p("columns", "csv", false, "Columns to return (default: service default set)"),
            p("releaseDateFrom", "date(YYYY-MM-DD)", false, "Filter: released on/after"),
            p("releaseDateTo", "date(YYYY-MM-DD)", false, "Filter: released on/before"));

        // --- Changes / History ---
        add("GET", "/api/agent/changes", "Changes",
            "Field-level change history search across items/users over a day window.",
            "{ results:[{item,field,oldValue,newValue,user,revNumber,...}], totalCount, uniqueItems, truncated, dbOffline, dataAsOf }",
            p("field", "string", false, "Field name filter"),
            p("item", "string", false, "Item number filter"),
            p("user", "string", false, "User filter"),
            p("days", "integer", false, "Lookback window in days (default 7)"),
            p("oldContains", "string", false, "Old-value substring filter"),
            p("newContains", "string", false, "New-value substring filter"),
            p("netFilter", "boolean", false, "Collapse to net change per item+field"));
        add("GET", "/api/agent/history/search", "Changes",
            "Item change/release history with lifecycle/change-type/part-type filters.",
            "{ count, data:[{col:value}] }",
            p("items", "csv", true, "Comma-separated item numbers"),
            p("lifecyclePhases", "csv", false, "Lifecycle phase filter"),
            p("changeTypes", "csv", false, "Change type filter"),
            p("partTypes", "csv", false, "Part type filter"),
            p("releaseDateFrom", "date(YYYY-MM-DD)", false, "Released on/after"),
            p("releaseDateTo", "date(YYYY-MM-DD)", false, "Released on/before"),
            p("entryMode", "string", false, "ALL | FIRST | LAST (default ALL)"));
        // --- BOM ---
        add("GET", "/api/agent/bom/explode", "BOM",
            "Multi-level BOM explosion for one or more assemblies.",
            "{ count, data:[BomResult] }",
            p("items", "csv", true, "Comma-separated parent item numbers"),
            p("maxDepth", "integer", false, "Max explode depth (default 20)"),
            p("lifecycles", "csv", false, "Lifecycle filter values"),
            p("lifecyclesMode", "string", false, "include | exclude (default include)"),
            p("partTypes", "csv", false, "Part-type filter values"),
            p("partTypesMode", "string", false, "include | exclude"),
            p("prefixes", "csv", false, "Item-number prefix filter"),
            p("prefixesMode", "string", false, "include | exclude"),
            p("maxTopLevelParents", "integer", false, "Cap (where-used only)"));
        add("GET", "/api/agent/bom/implode", "BOM",
            "Where-used (reverse BOM) for one or more components.",
            "{ count, data:[BomResult] }",
            p("items", "csv", true, "Comma-separated component item numbers"),
            p("maxDepth", "integer", false, "Max implode depth (default 20)"),
            p("lifecycles", "csv", false, "Lifecycle filter values"),
            p("lifecyclesMode", "string", false, "include | exclude"),
            p("partTypes", "csv", false, "Part-type filter values"),
            p("partTypesMode", "string", false, "include | exclude"),
            p("prefixes", "csv", false, "Item-number prefix filter"),
            p("prefixesMode", "string", false, "include | exclude"),
            p("maxTopLevelParents", "integer", false, "Cap on top-level parents emitted"));
        add("GET", "/api/agent/bom/components", "BOM",
            "Direct (single-level) component rows for one parent item.",
            "{ count, data:[{col:value}] }",
            p("parent", "string", true, "Parent item number"));

        // --- Revisions ---
        add("GET", "/api/agent/rev-compare/revs", "Revisions",
            "List revisions for a part.",
            "{ count, data:[{rev,change,...}] }",
            p("part", "string", true, "Part number"));
        add("GET", "/api/agent/rev-compare/detail", "Revisions",
            "Attribute/BOM detail for one part at one revision/change.",
            "{ ...rev detail map... }",
            p("part", "string", true, "Part number"),
            p("rev", "string", true, "Revision label"),
            p("change", "string", false, "Change number pinning the rev"));

        // --- ECO Timeline ---
        add("GET", "/api/agent/eco-timeline", "ECO Timeline",
            "ECO/change timeline for an item over a date range.",
            "{ ...timeline map... }",
            p("item", "string", true, "Item number"),
            p("from", "date(YYYY-MM-DD)", false, "Range start"),
            p("to", "date(YYYY-MM-DD)", false, "Range end"),
            p("maxDepth", "integer", false, "Max depth (default 25)"));

        // --- Change Review ---
        add("GET", "/api/agent/change-reviews/analysts", "Change Review",
            "List change-review analysts.",
            "{ count, data:[{loginid,name,...}] }");
        add("GET", "/api/agent/change-reviews/detail", "Change Review",
            "Sign-off detail for one change.",
            "{ ...signoff detail map... }",
            p("change", "string", true, "Change number"));
        add("GET", "/api/agent/change-reviews/dashboard", "Change Review",
            "Changes currently in review over a lookback window.",
            "{ count, data:[{change,...}] }",
            p("days", "integer", false, "Lookback days (default 30)"));

        // --- Documents (Doc Review) ---
        add("GET", "/api/agent/doc-review/data", "Documents",
            "Document-review dataset for a time window.",
            "{ count, data:[{col:value}] }",
            p("window", "string", false, "Named window (service-defined)"),
            p("from", "date(YYYY-MM-DD)", false, "Custom range start"),
            p("to", "date(YYYY-MM-DD)", false, "Custom range end"));

        // --- Documents (SDSM) ---
        add("GET", "/api/agent/sdsm/search", "Documents",
            "Shop-floor (SDSM) document search by item-number substring.",
            "{ count, data:[SdsmAttachment] }",
            p("q", "string", false, "Item-number filter (blank = all, capped by service)"));
        add("GET", "/api/agent/sdsm/specs", "Documents", "List SDSM spec facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/product-groups", "Documents", "List SDSM product-group facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/products", "Documents", "List SDSM product facet values.", "{ count, data:[string] }");
        add("GET", "/api/agent/sdsm/active-deviations", "Documents", "List active SDSM deviations.", "{ count, data:[SdsmAttachment] }");

        // --- SKU ---
        add("GET", "/api/agent/sku/fields", "SKU", "List available SKU fields.", "{ count, data:[string] }");
        add("GET", "/api/agent/sku/search", "SKU",
            "Look up SKU records by item number(s) from the SKU cache.",
            "{ count, data:[{field:value}] }",
            p("items", "csv", true, "Comma-separated SKU/item numbers"));

        // --- Reports data ---
        add("GET", "/api/agent/ecn-report/data", "Reports",
            "ECN report dataset (KPIs/SLA rows) from the cached report data.",
            "{ ...ecn data map... }");
        add("GET", "/api/agent/ecn-report/kpi-classifications", "Reports",
            "ECN KPI classification reference entries.",
            "{ count, data:[{...}] }");
        add("GET", "/api/agent/returns/data", "Reports",
            "Returns/rejection events in a date range.",
            "{ count, data:[{eventId,...}] }",
            p("from", "date(YYYY-MM-DD)", false, "Range start"),
            p("to", "date(YYYY-MM-DD)", false, "Range end"));
        add("GET", "/api/agent/returns/periods", "Reports",
            "Available frozen returns snapshot periods.",
            "{ count, data:[{period,...}] }");
        add("GET", "/api/agent/returns/explain/{eventId}", "Reports",
            "Human-readable explanation of one returns event's classification.",
            "{ eventId, explanation }",
            p("eventId", "string", true, "Returns event id (path segment)"));
        add("GET", "/api/agent/overdue/data", "Reports",
            "Overdue-change tracker dataset with optional filters.",
            "{ ...overdue data map... }",
            p("minOver", "integer", false, "Min days overdue"),
            p("maxOver", "integer", false, "Max days overdue"),
            p("classifications", "csv", false, "Classification filter (CSV)"));

        // --- Files ---
        add("GET", "/api/agent/files/list", "Files",
            "List attachment files on an item/document (with per-file downloadUrl).",
            "{ item, found, files:[{fileName,fileDescription,fileType,byteSize,contentAvailable,downloadUrl}] }",
            p("item", "string", true, "Item/document number"));
        add("GET", "/api/agent/files/download", "Files",
            "Download one attachment file's bytes (proxied from the Agile document service).",
            "binary file bytes (Content-Disposition: attachment)",
            p("item", "string", true, "Item/document number"),
            p("name", "string", false, "File name from /files/list (omit for the item's primary file)"));
        add("GET", "/api/agent/files/text", "Files",
            "Extracted plain text of one attachment (PDF/.docx), for an agent to read or summarize. Same fetch as /files/download but returns text.",
            "{ item, fileName, byteSize, extractionStatus (ok|empty|unsupported|error), truncated, chars, text }",
            p("item", "string", true, "Item/document number"),
            p("name", "string", false, "File name from /files/list or /documents/{number}"));
        add("GET", "/api/agent/sdsm/file/{attachId}", "Files",
            "Download an SDSM shop-floor document by attachment id.",
            "binary file bytes (Content-Disposition: attachment)",
            p("attachId", "integer", true, "Attachment id (path segment)"),
            p("fileName", "string", false, "Original file name (helps the file lookup)"),
            p("rev", "string", false, "Document revision"),
            p("parentNumber", "string", false, "Parent document number"));

        // --- Documents (Agile documents with attachments — metadata index) ---
        add("GET", "/api/agent/documents", "Documents",
            "Search/filter Agile documents (deduped by document number), metadata only + attachment filenames, with free-text, date ranges and paging. Use this to find policy/compliance docs then fetch their files.",
            "{ total, page, size, returned, indexGeneratedAt, documents:[{number,description,lifecyclePhase,rev,revReleaseDate,createDate,documentType,classification,owners,style,function,product,attachments:[{fileName,fileDescription,downloadUrl}]}] }",
            p("q", "string", false, "Free-text match on description, number, or attachment filename (e.g. 'compliance')"),
            p("lifecycle", "string", false, "Filter: Lifecycle Phase (ACT / OBS / Preliminary)"),
            p("style", "string", false, "Filter: Document Style (WI, VAR, System Map/Procedure, Guideline, …; discover values via aggregate)"),
            p("function", "string", false, "Filter: Function / Sub Function (exact, e.g. 'Quality Management Systems|General')"),
            p("classification", "string", false, "Filter: Document Classification (Automotive / Non-Automotive / Both …)"),
            p("owner", "string", false, "Filter: Document Owner(s)"),
            p("product", "string", false, "Filter: Product"),
            p("type", "string", false, "Filter: Document Type"),
            p("createdFrom", "date(YYYY-MM-DD)", false, "Created on/after this date (ITEM created date, inclusive)"),
            p("createdTo", "date(YYYY-MM-DD)", false, "Created on/before this date (inclusive)"),
            p("releasedFrom", "date(YYYY-MM-DD)", false, "Current rev released on/after this date (inclusive)"),
            p("releasedTo", "date(YYYY-MM-DD)", false, "Current rev released on/before this date (inclusive)"),
            p("page", "integer", false, "0-based page index (default 0)"),
            p("size", "integer", false, "Page size (default 50, max 200)"));
        add("GET", "/api/agent/documents/aggregate", "Documents",
            "Faceted counts of documents grouped by a field, after optional filters + free-text. Use to 'see what's out there' before pulling — e.g. counts by Function or Style.",
            "{ groupBy, matchedDocuments, buckets:[{value, count}] }",
            p("groupBy", "string", true, "One of: lifecycle | style | function | classification | owner | product | type | rev | number"),
            p("q", "string", false, "Free-text pre-filter (same match as /documents)"),
            p("lifecycle", "string", false, "Pre-filter (same fields as /documents)"),
            p("style", "string", false, "Pre-filter"),
            p("function", "string", false, "Pre-filter"),
            p("classification", "string", false, "Pre-filter"),
            p("owner", "string", false, "Pre-filter"),
            p("product", "string", false, "Pre-filter"),
            p("type", "string", false, "Pre-filter"),
            p("createdFrom", "date(YYYY-MM-DD)", false, "Pre-filter: created on/after"),
            p("createdTo", "date(YYYY-MM-DD)", false, "Pre-filter: created on/before"),
            p("releasedFrom", "date(YYYY-MM-DD)", false, "Pre-filter: released on/after"),
            p("releasedTo", "date(YYYY-MM-DD)", false, "Pre-filter: released on/before"));
        add("GET", "/api/agent/documents/{number}", "Documents",
            "One document's metadata + attachments (each with a downloadUrl). Fetch bytes via the downloadUrl (/api/agent/files/download).",
            "{ number, description, …, attachments:[{fileName, fileDescription, downloadUrl}] }",
            p("number", "string", true, "Document number (path segment)"));
    }

    /** No-param add. */
    private void add(String method, String path, String domain, String desc, String returns) {
        endpoints.add(new AgentEndpoint(method, path, domain, desc, returns, new ArrayList<>()));
    }

    /** Add with params. */
    private void add(String method, String path, String domain, String desc, String returns,
                     AgentEndpoint.Param... params) {
        endpoints.add(new AgentEndpoint(method, path, domain, desc, returns,
                new ArrayList<>(Arrays.asList(params))));
    }

    private static AgentEndpoint.Param p(String name, String type, boolean required, String desc) {
        return new AgentEndpoint.Param(name, type, required, desc);
    }

    public List<AgentEndpoint> all() { return endpoints; }

    public Set<String> paths() {
        Set<String> s = new LinkedHashSet<>();
        for (AgentEndpoint e : endpoints) s.add(e.path);
        return s;
    }
}
