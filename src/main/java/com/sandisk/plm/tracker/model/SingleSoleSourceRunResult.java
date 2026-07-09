package com.sandisk.plm.tracker.model;

public class SingleSoleSourceRunResult {
    public String runId;                 // ISO-8601 UTC
    public String trigger;               // "schedule" | "ui"
    public String userId;
    public int designationNeededCount;
    /** Combined Single Source + Sole Source rows on the "Designation Provided" sheet. */
    public int designationProvidedCount;
    /** Breakdown within the Provided sheet — kept for reporting clarity, not used in
     *  the email KPIs (the report itself doesn't separate them anymore per the spec). */
    public int singleSourceCount;
    public int soleSourceCount;
    public String xlsxPath;
    public long xlsxSizeBytes;
    public Boolean sharepointUploaded;   // null if not attempted
    public String sharepointUrl;
    public String sharepointError;
    public Boolean emailSent;            // null if not attempted
    public String emailTo;
    public String emailCc;
    public String emailError;
    public long durationMs;
    public String error;                 // top-level run error
}
