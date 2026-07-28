package com.sandisk.plm.tracker.model;

import java.sql.Timestamp;

/** One ECO-attributed change event in the timeline report. */
public class EcoTimelineRow {
    private final int level;
    private final String parentAssembly;
    private final String path;             // root→parent breadcrumb, e.g. "SKU / SubA / SubB"
    private final String component;
    private final String componentDescription;
    private final String ecoNumber;
    private final String ecoReleaseDate;   // formatted yyyy-MM-dd (Pacific)
    private final String changeType;
    private final String detail;
    private final String ecoDescription;   // CHANGE.DESCRIPTION — the change order's intent
    private final String ecoStatus;        // workflow status name, e.g. "Released"
    private final String ecoReason;        // reason-for-change (nullable; flex attid TBD)
    private String primaryNumber = "";     // i2 primary component(s) for this component (enriched post-classify)
    private final long releaseTsMillis;    // for sorting; harmless in JSON

    public EcoTimelineRow(int level, String parentAssembly, String path, String component,
                          String componentDescription, String ecoNumber,
                          String ecoReleaseDate, String changeType, String detail,
                          String ecoDescription, String ecoStatus, String ecoReason,
                          Timestamp releaseTs) {
        this.level = level;
        this.parentAssembly = parentAssembly == null ? "" : parentAssembly;
        this.path = path == null ? "" : path;
        this.component = component == null ? "" : component;
        this.componentDescription = componentDescription == null ? "" : componentDescription;
        this.ecoNumber = ecoNumber == null ? "" : ecoNumber;
        this.ecoReleaseDate = ecoReleaseDate == null ? "" : ecoReleaseDate;
        this.changeType = changeType == null ? "" : changeType;
        this.detail = detail == null ? "" : detail;
        this.ecoDescription = ecoDescription == null ? "" : ecoDescription;
        this.ecoStatus = ecoStatus == null ? "" : ecoStatus;
        this.ecoReason = ecoReason == null ? "" : ecoReason;
        this.releaseTsMillis = releaseTs == null ? 0L : releaseTs.getTime();
    }

    public int getLevel() { return level; }
    public String getParentAssembly() { return parentAssembly; }
    public String getPath() { return path; }
    public String getComponent() { return component; }
    public String getComponentDescription() { return componentDescription; }
    public String getEcoNumber() { return ecoNumber; }
    public String getEcoReleaseDate() { return ecoReleaseDate; }
    public String getChangeType() { return changeType; }
    public String getDetail() { return detail; }
    public String getEcoDescription() { return ecoDescription; }
    public String getEcoStatus() { return ecoStatus; }
    public String getEcoReason() { return ecoReason; }
    public String getPrimaryNumber() { return primaryNumber; }
    public void setPrimaryNumber(String primaryNumber) { this.primaryNumber = primaryNumber == null ? "" : primaryNumber; }
    public long getReleaseTsMillis() { return releaseTsMillis; }
}
