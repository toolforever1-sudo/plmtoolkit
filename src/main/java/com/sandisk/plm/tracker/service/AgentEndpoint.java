package com.sandisk.plm.tracker.service;

import java.util.Collections;
import java.util.List;

/** Immutable descriptor for one Agent API endpoint. Backs the /catalog response. */
public final class AgentEndpoint {

    public static final class Param {
        public final String name;
        public final String type;      // "string" | "integer" | "boolean" | "csv" | "date(YYYY-MM-DD)"
        public final boolean required;
        public final String description;
        public Param(String name, String type, boolean required, String description) {
            this.name = name; this.type = type; this.required = required; this.description = description;
        }
    }

    public final String method;   // "GET" | "POST"
    public final String path;     // full path, e.g. "/api/agent/changes"
    public final String domain;   // grouping label, e.g. "Changes"
    public final String description;
    public final String returns;  // human description of the response shape
    public final List<Param> params;

    public AgentEndpoint(String method, String path, String domain,
                         String description, String returns, List<Param> params) {
        this.method = method; this.path = path; this.domain = domain;
        this.description = description; this.returns = returns;
        this.params = params == null ? Collections.emptyList() : Collections.unmodifiableList(params);
    }
}
