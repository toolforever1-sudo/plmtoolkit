package com.sandisk.plm.tracker.model;

/**
 * One attachment row from {@code ATTACHMENT_FULL_MAP} — what the factory MES needs
 * to render a controlled document for a part / step / deviation.
 *
 * <p>One physical PDF can apply to many spec codes; the SDSM job explodes that into
 * one summary row per spec. Here we keep a single attachment record with a list of
 * applicable specs, and expand at the UI layer.
 *
 * <p>See {@code docs/SDSM-DB-REPLACEMENT.md} for the SQL shape that produces these.
 */
public class SdsmAttachment {

    /** Source pass: "document", "part", or "deviation". */
    public String source;

    /** ITEM.ITEM_NUMBER (Documents/Parts) or CHANGE.CHANGE_NUMBER (Deviations). */
    public String parentNumber;

    /** Description of the parent (item or change). */
    public String parentDescription;

    /** Document Style label, e.g. "VIC", "Drawing", "WI", "Quality Notice". */
    public String documentStyle;

    /** Item subclass display name, e.g. "Document", "Part", "L000-Label Printing Inst", "Deviation". */
    public String itemType;

    /** Lifecycle phase — e.g. "ACT", "OBS". */
    public String lifecyclePhase;

    /** Rev letter from REV.REV_NUMBER, or null for deviations. */
    public String rev;

    /** Change number that introduced this attachment (DCO/ECO/Deviation number). */
    public String changeNumber;

    /** Product Group — display text(s), comma-joined. */
    public String productGroup;

    /** Product (multi-Item) — item numbers, comma-joined, OBS already excluded. */
    public String product;

    /** Spec / step codes that this document applies to (one PDF can apply to many). */
    public java.util.List<String> specs;

    /** ATTACHMENT_FULL_MAP.ATTACH_ID — primary key for the attachment object. */
    public long attachId;

    /** ATTACHMENT_FULL_MAP.FILEID — primary key for the file row. */
    public long fileId;

    /** Original filename, e.g. "06-05-WW-02-00002_..._Rev 22 Final Document.pdf". */
    public String fileName;

    /** "pdf" / "docx" / "eml" / etc. */
    public String fileType;

    /** File size in bytes. */
    public long fileSize;

    /** Vault path, e.g. "005/782/899/agile578289967.pdf". */
    public String vaultPath;

    /** Effective from (deviations only). ISO-ish string, may be null. */
    public String effectiveFrom;

    /** Effective to (deviations only). May be null. */
    public String effectiveTo;

    /** Customers Impacted (deviations only) — display text, comma-joined. */
    public String customersImpacted;
}
