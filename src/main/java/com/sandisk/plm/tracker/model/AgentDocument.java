package com.sandisk.plm.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One Agile document (deduped by Number) in the Agent API document index.
 * Populated from the JSON built by data/agent-docs/build_document_index.py.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDocument {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attachment {
        public String fileName;
        public String fileDescription;
    }

    public String number;
    public String description;
    public String lifecyclePhase;
    public String rev;
    public String revReleaseDate;   // YYYY-MM-DD
    public String createDate;       // YYYY-MM-DD (ITEM.CREATED)
    public String documentType;
    public String classification;
    public String owners;
    public String style;
    public String function;   // "Function / Sub Function", e.g. "Quality Management Systems|General"
    public String product;
    public List<Attachment> attachments = new ArrayList<>();
}
