package com.sandisk.plm.tracker.model;

import java.sql.Timestamp;

/** One raw AGILE.BOM redline row for a single parent assembly, with the
 *  CHANGE_IN / CHANGE_OUT metadata needed to classify what an ECO did. */
public class BomRedlineRow {
    public long bomRowId;          // BOM.ID
    public long priorBom;          // BOM.PRIOR_BOM (0 if none) — links a modify's add->removed row
    public String componentPn;     // BOM.ITEM_NUMBER (component part number)
    public String componentDesc;   // COALESCE(BOM.DESCRIPTION, ITEM.DESCRIPTION)
    public String quantity;        // BOM.QUANTITY (as string)
    public String findNumber;      // BOM.FIND_NUMBER (find # / seq)
    public String notes;           // BOM.NOTES

    public String changeInNum;     // CHANGE.CHANGE_NUMBER of CHANGE_IN (null if 0/none)
    public Timestamp changeInRd;   // CHANGE.RELEASE_DATE of CHANGE_IN
    public String changeInDesc;    // CHANGE.DESCRIPTION of CHANGE_IN
    public String changeInStatus;  // NODETABLE.DESCRIPTION for CHANGE_IN.STATUS (e.g. "Released")
    public String changeOutNum;    // CHANGE.CHANGE_NUMBER of CHANGE_OUT (null if 0/none)
    public Timestamp changeOutRd;  // CHANGE.RELEASE_DATE of CHANGE_OUT
    public String changeOutDesc;   // CHANGE.DESCRIPTION of CHANGE_OUT
    public String changeOutStatus; // NODETABLE.DESCRIPTION for CHANGE_OUT.STATUS

    public BomRedlineRow() { }
}
