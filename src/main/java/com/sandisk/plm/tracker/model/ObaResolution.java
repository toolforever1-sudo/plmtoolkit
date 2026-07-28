package com.sandisk.plm.tracker.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of resolving a SKU to its OBA document item numbers. Internal — the
 * controller maps this onto the JSON response. A null item field means that
 * level could not be resolved (a matching warning is added).
 */
public class ObaResolution {
    public String c039Item;          // the chosen C039 assembly (diagnostic)
    public String labelProofItem;    // L000 Outer Shipping Label item, or null
    public String labelProofDesc;    // its BOM description, or null
    public String labelSpecItem;     // D026 spec item, or null
    public String labelSpecDesc;     // its BOM description, or null
    public final List<String> warnings = new ArrayList<>();
}
