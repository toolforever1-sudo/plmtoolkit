package com.sandisk.plm.tracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * Represents a single SKU item from Agile PLM with all 39 display fields.
 * Maps to the "All SKUs" advanced search in the Agile PLM UI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkuRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    // --- Title Block ---
    @JsonProperty("number")
    private String number;

    @JsonProperty("description")
    private String description;

    @JsonProperty("lifecyclePhase")
    private String lifecyclePhase;

    @JsonProperty("rev")
    private String rev;

    @JsonProperty("productLine")
    private String productLine;

    @JsonProperty("pm")
    private String pm;

    @JsonProperty("revReleaseDate")
    private String revReleaseDate;

    // --- PDM Attributes (PAGE_THREE) ---
    @JsonProperty("productLineProgramName")
    private String productLineProgramName;

    @JsonProperty("category")
    private String category;

    @JsonProperty("productCustomer")
    private String productCustomer;

    @JsonProperty("bomType")
    private String bomType;

    @JsonProperty("warrantyMonths")
    private String warrantyMonths;

    @JsonProperty("progProgramName")
    private String progProgramName;

    @JsonProperty("programShortName")
    private String programShortName;

    @JsonProperty("externalMarketingName")
    private String externalMarketingName;

    // --- Inv/Planning (PAGE_TWO) ---
    @JsonProperty("asBuildCapacityBinary")
    private String asBuildCapacityBinary;

    @JsonProperty("asSoldCapacityBinary")
    private String asSoldCapacityBinary;

    @JsonProperty("asMarketedCapacityDecimal")
    private String asMarketedCapacityDecimal;

    @JsonProperty("asMarketedCapacityUom")
    private String asMarketedCapacityUom;

    @JsonProperty("familyName")
    private String familyName;

    @JsonProperty("subcontractors")
    private String subcontractors;

    @JsonProperty("actualBuildPlant")
    private String actualBuildPlant;

    @JsonProperty("materialType")
    private String materialType;

    @JsonProperty("group")
    private String group;

    @JsonProperty("materialGroup")
    private String materialGroup;

    @JsonProperty("prodDivision")
    private String prodDivision;

    @JsonProperty("createDate")
    private String createDate;

    // --- AGILE_FLEX extended attributes ---
    @JsonProperty("businessSegmentSubSegment")
    private String businessSegmentSubSegment;

    @JsonProperty("productSegment")
    private String productSegment;

    @JsonProperty("productSubSegment")
    private String productSubSegment;

    @JsonProperty("interfaceType")
    private String interfaceType;

    @JsonProperty("formFactorType")
    private String formFactorType;

    @JsonProperty("productBrand")
    private String productBrand;

    @JsonProperty("brand")
    private String brand;

    @JsonProperty("partnership")
    private String partnership;

    @JsonProperty("productSwimlane")
    private String productSwimlane;

    @JsonProperty("stampSerializationFlag")
    private String stampSerializationFlag;

    @JsonProperty("retailer")
    private String retailer;

    @JsonProperty("itemClass")
    private String itemClass;

    // --- Getters and Setters ---

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLifecyclePhase() { return lifecyclePhase; }
    public void setLifecyclePhase(String lifecyclePhase) { this.lifecyclePhase = lifecyclePhase; }

    public String getRev() { return rev; }
    public void setRev(String rev) { this.rev = rev; }

    public String getProductLine() { return productLine; }
    public void setProductLine(String productLine) { this.productLine = productLine; }

    public String getPm() { return pm; }
    public void setPm(String pm) { this.pm = pm; }

    public String getRevReleaseDate() { return revReleaseDate; }
    public void setRevReleaseDate(String revReleaseDate) { this.revReleaseDate = revReleaseDate; }

    public String getProductLineProgramName() { return productLineProgramName; }
    public void setProductLineProgramName(String productLineProgramName) { this.productLineProgramName = productLineProgramName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getProductCustomer() { return productCustomer; }
    public void setProductCustomer(String productCustomer) { this.productCustomer = productCustomer; }

    public String getBomType() { return bomType; }
    public void setBomType(String bomType) { this.bomType = bomType; }

    public String getWarrantyMonths() { return warrantyMonths; }
    public void setWarrantyMonths(String warrantyMonths) { this.warrantyMonths = warrantyMonths; }

    public String getProgProgramName() { return progProgramName; }
    public void setProgProgramName(String progProgramName) { this.progProgramName = progProgramName; }

    public String getProgramShortName() { return programShortName; }
    public void setProgramShortName(String programShortName) { this.programShortName = programShortName; }

    public String getExternalMarketingName() { return externalMarketingName; }
    public void setExternalMarketingName(String externalMarketingName) { this.externalMarketingName = externalMarketingName; }

    public String getAsBuildCapacityBinary() { return asBuildCapacityBinary; }
    public void setAsBuildCapacityBinary(String asBuildCapacityBinary) { this.asBuildCapacityBinary = asBuildCapacityBinary; }

    public String getAsSoldCapacityBinary() { return asSoldCapacityBinary; }
    public void setAsSoldCapacityBinary(String asSoldCapacityBinary) { this.asSoldCapacityBinary = asSoldCapacityBinary; }

    public String getAsMarketedCapacityDecimal() { return asMarketedCapacityDecimal; }
    public void setAsMarketedCapacityDecimal(String asMarketedCapacityDecimal) { this.asMarketedCapacityDecimal = asMarketedCapacityDecimal; }

    public String getAsMarketedCapacityUom() { return asMarketedCapacityUom; }
    public void setAsMarketedCapacityUom(String asMarketedCapacityUom) { this.asMarketedCapacityUom = asMarketedCapacityUom; }

    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }

    public String getSubcontractors() { return subcontractors; }
    public void setSubcontractors(String subcontractors) { this.subcontractors = subcontractors; }

    public String getActualBuildPlant() { return actualBuildPlant; }
    public void setActualBuildPlant(String actualBuildPlant) { this.actualBuildPlant = actualBuildPlant; }

    public String getMaterialType() { return materialType; }
    public void setMaterialType(String materialType) { this.materialType = materialType; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getMaterialGroup() { return materialGroup; }
    public void setMaterialGroup(String materialGroup) { this.materialGroup = materialGroup; }

    public String getProdDivision() { return prodDivision; }
    public void setProdDivision(String prodDivision) { this.prodDivision = prodDivision; }

    public String getCreateDate() { return createDate; }
    public void setCreateDate(String createDate) { this.createDate = createDate; }

    public String getBusinessSegmentSubSegment() { return businessSegmentSubSegment; }
    public void setBusinessSegmentSubSegment(String businessSegmentSubSegment) { this.businessSegmentSubSegment = businessSegmentSubSegment; }

    public String getProductSegment() { return productSegment; }
    public void setProductSegment(String productSegment) { this.productSegment = productSegment; }

    public String getProductSubSegment() { return productSubSegment; }
    public void setProductSubSegment(String productSubSegment) { this.productSubSegment = productSubSegment; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public String getFormFactorType() { return formFactorType; }
    public void setFormFactorType(String formFactorType) { this.formFactorType = formFactorType; }

    public String getProductBrand() { return productBrand; }
    public void setProductBrand(String productBrand) { this.productBrand = productBrand; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getPartnership() { return partnership; }
    public void setPartnership(String partnership) { this.partnership = partnership; }

    public String getProductSwimlane() { return productSwimlane; }
    public void setProductSwimlane(String productSwimlane) { this.productSwimlane = productSwimlane; }

    public String getStampSerializationFlag() { return stampSerializationFlag; }
    public void setStampSerializationFlag(String stampSerializationFlag) { this.stampSerializationFlag = stampSerializationFlag; }

    public String getRetailer() { return retailer; }
    public void setRetailer(String retailer) { this.retailer = retailer; }

    public String getItemClass() { return itemClass; }
    public void setItemClass(String itemClass) { this.itemClass = itemClass; }
}
