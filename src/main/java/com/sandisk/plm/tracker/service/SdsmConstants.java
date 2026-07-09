package com.sandisk.plm.tracker.service;

/**
 * NODETABLE / LISTENTRY / column constants for the SDSM attachment-pull replacement.
 * Every value here was verified live against {@code agile_prod} on 2026-05-13;
 * see {@code docs/SDSM-DB-REPLACEMENT.md} for the discovery trail.
 *
 * <p>Naming convention: {@code NODE_*} = NODETABLE.ID, {@code LE_*} = LISTENTRY.ENTRYID,
 * {@code ATTID_*} = AGILE_FLEX.ATTID, {@code COL_*} = a PAGE_TWO/PAGE_THREE column name.
 */
public final class SdsmConstants {
    private SdsmConstants() {}

    // ---- Class / subclass ----
    public static final long CLASS_DOCUMENTS  = 9000L;
    public static final long CLASS_ITEMS      = 10000L;   // parent of all Parts subclasses
    public static final long CLASS_CHANGES    = 8000L;

    public static final long SUBCLASS_DOCUMENT  = 9141L;   // most-populous Document subclass
    public static final long SUBCLASS_SKU       = 251736330L;  // exclude in Parts pass
    public static final long SUBCLASS_DEVIATION = 20336L;
    public static final long SUBCLASS_DCO       = 251733391L;
    public static final long SUBCLASS_ECO       = 6141L;   // populous one; do NOT use 251731131/251735258/20055

    // ---- Change status ----
    public static final long STATUS_IMPLEMENTED   = 251733425L;  // for DCO/ECO history filter
    public static final long STATUS_QN_EXECUTED   = 251745989L;  // Deviation pass
    public static final long WORKFLOW_QN          = 251745973L;  // Quality Notice Workflow

    // ---- Document Style vocabulary (LISTENTRY.PARENTID = 251746283) ----
    public static final long LISTPARENT_DOCSTYLE     = 251746283L;
    public static final long LISTPARENT_ATTACH_TYPE  = 4682L;

    // ---- ATTACHMENTTYPE LISTENTRY ENTRYIDs we filter on ----
    public static final long LE_QUALITY_NOTICE = 3566238L;  // Deviations pass attachment filter
    // (3566239 = Training Record — DO NOT use; common past mistake.)

    // ---- AGILE_FLEX ATTIDs ----
    // Documents (CLASS=9000), all parent 1628 (Documents subclass attribute scope)
    public static final long ATTID_DOC_PRODUCT_GROUP = 251746287L;  // TEXT CSV of LISTENTRY
    public static final long ATTID_DOC_SPEC          = 251746291L;  // TEXT CSV of LISTENTRY
    public static final long ATTID_DOC_PRODUCT       = 251747721L;  // TEXT CSV of ITEM.IDs

    // Deviations (parent 20337)
    public static final long ATTID_DEV_PRODUCT_GROUP = 251748095L;  // TEXT CSV of LISTENTRY
    public static final long ATTID_DEV_SPEC          = 251748093L;  // TEXT CSV of LISTENTRY
    // Deviation Product is on PAGE_TWO.MULTILIST03 directly (ATTID 1566)

    // ---- Whitelist of Document Style values the SDSM tab surfaces ----
    // Originally these were the only Document Style values the legacy Java
    // batch (GetAndSummarizeAttachments) accepted: "WI", "VAR", "VIC", "FPS",
    // "Guideline", "Checklist", "Drawing".
    //
    // Per the eVAR + Offline Document training guide (27-04-SM-02-00054 Rev 5,
    // pages 22 and 25), the actual supported set in eVAR (Camstar) is:
    //   VAR / WI / Guideline / FPS / QN / Temp VAR / Drawing / VIC
    // and the offline-document (TTMS) set is:
    //   Offline VAR / Offline WI
    //
    // QN is excluded here because Quality Notice attachments come through the
    // Deviations pass, not the Documents/Parts passes. Everything else from
    // both lists is included so the operator sees every controlled doc that
    // either Camstar or TTMS would surface.
    public static final java.util.Set<String> DOC_STYLE_WHITELIST =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "WI", "VAR", "VIC", "FPS", "Guideline", "Checklist", "Drawing",
            "Temp VAR",       // eVAR — temporary VAR (page 22)
            "Offline VAR",    // TTMS — offline VAR (page 25)
            "Offline WI"      // TTMS — offline WI  (page 25)
        ));

    // ---- Lifecycle phases that mean "do not show on shop floor" ----
    public static final java.util.Set<String> OBS_LIFECYCLES =
        new java.util.HashSet<>(java.util.Arrays.asList("OBS", "OBS-SKU"));
}
