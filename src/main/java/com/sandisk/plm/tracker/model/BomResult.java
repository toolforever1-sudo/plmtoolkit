package com.sandisk.plm.tracker.model;

public class BomResult {

    private int level;
    private String parent;
    private String component;
    private String quantity;
    private String description;
    private String status;
    private String rev;
    private String refDesignator;
    private String findNumber;
    private String itemType;
    private String notes;
    private String path;
    private String lifecyclePhase;
    private String productLine;
    private String subcontractors;
    private String actualBuildPlant;
    private boolean topLevel;
    private String inputItem;

    public BomResult() {}

    public BomResult(int level, String parent, String component, String quantity,
                     String description, String notes, String status, String rev,
                     String refDesignator, String findNumber, String itemType) {
        this.level = level;
        this.parent = parent;
        this.component = component;
        this.quantity = quantity;
        this.description = description;
        this.notes = notes;
        this.status = status;
        this.rev = rev;
        this.refDesignator = refDesignator;
        this.findNumber = findNumber;
        this.itemType = itemType;
    }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public String getQuantity() { return quantity; }
    public void setQuantity(String quantity) { this.quantity = quantity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRev() { return rev; }
    public void setRev(String rev) { this.rev = rev; }
    public String getRefDesignator() { return refDesignator; }
    public void setRefDesignator(String refDesignator) { this.refDesignator = refDesignator; }
    public String getFindNumber() { return findNumber; }
    public void setFindNumber(String findNumber) { this.findNumber = findNumber; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getLifecyclePhase() { return lifecyclePhase; }
    public void setLifecyclePhase(String lifecyclePhase) { this.lifecyclePhase = lifecyclePhase; }
    public String getProductLine() { return productLine; }
    public void setProductLine(String productLine) { this.productLine = productLine; }
    public String getSubcontractors() { return subcontractors; }
    public void setSubcontractors(String subcontractors) { this.subcontractors = subcontractors; }
    public String getActualBuildPlant() { return actualBuildPlant; }
    public void setActualBuildPlant(String actualBuildPlant) { this.actualBuildPlant = actualBuildPlant; }
    public boolean isTopLevel() { return topLevel; }
    public void setTopLevel(boolean topLevel) { this.topLevel = topLevel; }
    public String getInputItem() { return inputItem; }
    public void setInputItem(String inputItem) { this.inputItem = inputItem; }
}
