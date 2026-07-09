package com.sandisk.plm.tracker.model;

import java.util.Date;

public class SingleSoleSourceRow {
    public long itemId;
    public String number;
    public String description;
    public String productLine;
    public String lifecyclePhase;
    public String rev;
    public String partType;
    public Integer mpnCount;
    public String singleSoleSource;   // tab partition key
    public String materialGroup;
    public Date createDate;
    public Date revReleaseDate;
    public String mfrName;
    public String mfrPartNumber;
    public String preferredStatus;
}
