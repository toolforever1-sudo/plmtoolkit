package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import static com.sandisk.plm.tracker.service.SdsmConstants.*;

/**
 * "Why didn't this part return any documents?" diagnostic for the Shop-Floor
 * Docs tab. Mirrors the gates that {@link SdsmDocumentsService} and
 * {@link SdsmPartsService} apply in their main pass, then formats a structured
 * explanation the operator can read.
 *
 * <p>Designed to be called whenever the regular {@code /api/sdsm/search} returns
 * zero results — but works on any input. Output reads like a step-by-step audit:
 *
 * <ol>
 *   <li>Does the item exist in Agile?</li>
 *   <li>What class / subclass is it?</li>
 *   <li>Is the Document Style attribute populated?</li>
 *   <li>Is the Document Style one the SDSM job recognizes (WI / VIC / Drawing / …)?</li>
 *   <li>Does the item have a latest <i>Implemented</i> DCO (Documents) or ECO (Parts)?</li>
 *   <li>Are the Spec / Product Group / Product flex rows populated?</li>
 *   <li>Are there any attachments at all? Any PDFs?</li>
 * </ol>
 *
 * <p>All checks are deterministic — they probe the same tables the main service
 * does, so the reasons line up exactly. No AI / LLM call is needed for this:
 * the diagnostic is the source of truth for "why."
 */
@Service
public class SdsmDiagnoseService {

    private static final Logger LOG = Logger.getLogger(SdsmDiagnoseService.class.getName());

    @Autowired private DataSource dataSource;
    @Autowired private SdsmLookups lookups;

    /** One step of the diagnostic chain. */
    public static class Check {
        public final String name;        // short label
        public final String status;      // "pass" | "fail" | "info"
        public final String detail;      // what we observed
        public Check(String name, String status, String detail) {
            this.name = name; this.status = status; this.detail = detail;
        }
    }

    /** Top-level diagnostic envelope. */
    public static class Diagnosis {
        public String query;
        public boolean itemFound;
        public String itemNumber;
        public String description;
        public String subclassName;
        public String classification;     // "document" | "part" | "deviation" | "other"
        public String summary;            // one-line headline
        public List<Check> checks = new ArrayList<>();
        /** Concrete next steps the operator can take to fix the gap (or surface to the admin). */
        public List<String> nextSteps = new ArrayList<>();
        public String contactNote;
    }

    public Diagnosis diagnose(String query) {
        Diagnosis d = new Diagnosis();
        d.query = query == null ? "" : query.trim();
        d.contactNote = "If you expected this part to show controlled documents, contact pdl-plm-admin@sandisk.com.";

        if (d.query.isEmpty()) {
            d.summary = "No part / document number was entered.";
            d.checks.add(new Check("Input", "fail", "The search box was empty."));
            return d;
        }

        try {
            lookups.warmUp();

            // Try as an item first (covers Documents + Parts), then fall back to a
            // Change lookup (Deviation case).
            ItemRow item = lookupItem(d.query);
            if (item != null) {
                d.itemFound = true;
                d.itemNumber = item.itemNumber;
                d.description = item.description;
                d.subclassName = lookups.resolveSubclassName(item.subclassId);
                if (item.classId == CLASS_DOCUMENTS) {
                    d.classification = "document";
                    diagnoseDocument(item, d);
                } else if (item.classId == CLASS_ITEMS) {
                    d.classification = "part";
                    diagnosePart(item, d);
                } else {
                    d.classification = "other";
                    d.summary = "Item is class " + item.classId + " (" + d.subclassName + ") "
                            + "— Shop-Floor Docs only covers Documents (CLASS=9000) and Parts (CLASS=10000).";
                    d.checks.add(new Check("Class", "fail",
                            "Item class " + item.classId + " is outside the SDSM scope."));
                }
                return d;
            }

            // Not an item — maybe a Change number (Deviation).
            ChangeRow chg = lookupChange(d.query);
            if (chg != null) {
                d.itemFound = true;
                d.itemNumber = chg.changeNumber;
                d.description = chg.description;
                d.subclassName = lookups.resolveSubclassName(chg.subclassId);
                d.classification = "deviation";
                diagnoseDeviation(chg, d);
                return d;
            }

            // Nothing matched.
            d.summary = "No item or change matches '" + d.query + "' in Agile.";
            d.checks.add(new Check("Lookup", "fail",
                    "Agile has no Item or Change with that exact number. Check for typos or stray spaces."));
            d.nextSteps.add("Verify the part / document / change number is exact (case sensitive, no extra spaces).");
            return d;

        } catch (Exception e) {
            LOG.warning("[SDSM-DIAG] failed for q=" + d.query + ": " + e.getMessage());
            d.summary = "The diagnostic check itself failed: " + e.getMessage();
            d.checks.add(new Check("Diagnostic", "fail", "Internal error: " + e.getMessage()));
            return d;
        }
    }

    // -----------------------------------------------------------------------
    //  Document path
    // -----------------------------------------------------------------------

    private void diagnoseDocument(ItemRow item, Diagnosis d) throws SQLException {
        d.checks.add(new Check("Item exists", "pass",
                "Found Document " + item.itemNumber + " (subclass: " + d.subclassName + ")."));

        // Document Style — PAGE_THREE.LIST36
        Long docStyleId = readSingleLong("SELECT LIST36 FROM PAGE_THREE WHERE ID=?", item.itemId);
        String docStyle = docStyleId == null || docStyleId == 0 ? null : lookups.resolveListEntry(docStyleId);
        boolean docStyleOk = docStyle != null && DOC_STYLE_WHITELIST.contains(docStyle);
        if (docStyle == null) {
            d.checks.add(new Check("Document Style", "fail",
                    "PAGE_THREE.LIST36 is empty. Without a Document Style the SDSM job has no way to classify it."));
            d.nextSteps.add("Set Page Three → Document Style on the doc in Agile (one of: WI, VAR, VIC, FPS, Guideline, Checklist, Drawing).");
        } else if (!docStyleOk) {
            d.checks.add(new Check("Document Style", "fail",
                    "Document Style is '" + docStyle + "' — only WI, VAR, VIC, FPS, Guideline, Checklist, Drawing show on the floor."));
            d.nextSteps.add("If this doc should appear on the shop floor, change Page Three → Document Style to one of the supported values.");
        } else {
            d.checks.add(new Check("Document Style", "pass",
                    "Document Style = '" + docStyle + "' (in the SDSM whitelist)."));
        }

        // Latest Implemented DCO
        ChangeRow latestDco = findLatestImplementedChange(item.itemId, "DCO");
        if (latestDco == null) {
            d.checks.add(new Check("Latest Implemented DCO", "fail",
                    "No DCO with status 'Implemented' has ever released this document."));
            d.nextSteps.add("Run the document through a DCO and let it reach Implemented status.");
        } else {
            d.checks.add(new Check("Latest Implemented DCO", "pass",
                    "Latest implemented: " + latestDco.changeNumber
                            + " (rev " + (latestDco.revNumber == null ? "?" : latestDco.revNumber)
                            + ", released " + latestDco.releaseDate + ")."));
        }

        // Spec / Product Group / Product on the Documents flex schema
        FlexBundle flex = readDocumentFlex(item.itemId);
        addFlexChecks(d, flex, "Documents");

        // Attachments — count + PDFs
        AttachmentSummary atts = countAttachments(item.itemId, latestDco == null ? 0L : latestDco.changeId);
        addAttachmentChecks(d, atts, latestDco != null);

        // Headline
        d.summary = composeDocumentSummary(docStyle, docStyleOk, latestDco, flex, atts);
    }

    private String composeDocumentSummary(String docStyle, boolean docStyleOk,
                                          ChangeRow dco, FlexBundle flex, AttachmentSummary atts) {
        // Verdict order mirrors the main SdsmDocumentsService gates exactly:
        //   1) Document Style in whitelist
        //   2) Latest Implemented DCO exists
        //   3) Spec count > 0  (matches "if (specs.isEmpty()) continue;")
        //   4) Product count > 0 (matches "if (product.isEmpty()) continue;" — note:
        //      Product Group is NOT a gate in the main service, only Product is)
        //   5) ALL conflict on any Page-Three field (silent killer per training guide page 22)
        //   6) Latest change has at least one attachment
        //   7) That attachment includes at least one PDF
        if (!docStyleOk) {
            return docStyle == null
                    ? "Skipped because Document Style is not set on Page Three."
                    : "Skipped because Document Style '" + docStyle + "' is not one of the SDSM-relevant types.";
        }
        if (dco == null) {
            return "Skipped because no DCO with status 'Implemented' has released this document.";
        }
        if (flex.specCount == 0) {
            return "Skipped because Page Three → Spec is empty (no step-codes mapped to this doc).";
        }
        if (flex.productCount == 0) {
            return "Skipped because Page Three → Product is empty (the doc isn't tied to any product item).";
        }
        if (flex.allConflict != null && !flex.allConflict.isEmpty()) {
            return "Skipped because Page Three → " + String.join(", ", flex.allConflict)
                    + " mixes 'ALL' with specific values — eVAR / TTMS configuration logic forbids this and silently drops the doc.";
        }
        if (atts.totalForLatestChange == 0) {
            return "Skipped because the latest Implemented DCO (" + dco.changeNumber + ") has no attachments tied to it.";
        }
        if (atts.pdfForLatestChange == 0) {
            return "Skipped because the latest Implemented DCO has attachments but none are PDFs (only PDFs are shown on the floor).";
        }
        return "All gates passed — this doc should be visible. If the search still shows nothing, the operator may have entered the number with extra characters or under a different rev.";
    }

    // -----------------------------------------------------------------------
    //  Part path
    // -----------------------------------------------------------------------

    private void diagnosePart(ItemRow item, Diagnosis d) throws SQLException {
        d.checks.add(new Check("Item exists", "pass",
                "Found Part " + item.itemNumber + " (subclass: " + d.subclassName + ")."));

        if (item.subclassId == SUBCLASS_SKU) {
            d.checks.add(new Check("Subclass", "fail",
                    "SKUs are explicitly excluded from the Shop-Floor Docs lookup."));
            d.summary = "SKUs are excluded by design — operators see controlled docs at the assembly / drawing level, not at the saleable-SKU level.";
            return;
        }

        Long docStyleId = readSingleLong("SELECT LIST72 FROM PAGE_TWO WHERE ID=?", item.itemId);
        String docStyle = docStyleId == null || docStyleId == 0 ? null : lookups.resolveListEntry(docStyleId);
        boolean docStyleOk = docStyle != null && DOC_STYLE_WHITELIST.contains(docStyle);
        if (docStyle == null) {
            d.checks.add(new Check("Document Style", "fail",
                    "PAGE_TWO.LIST72 (Document Style) is empty on this part."));
            d.nextSteps.add("Set Page Two → Document Style on the part in Agile.");
        } else if (!docStyleOk) {
            d.checks.add(new Check("Document Style", "fail",
                    "Document Style is '" + docStyle + "' — only WI, VAR, VIC, FPS, Guideline, Checklist, Drawing surface on the floor."));
        } else {
            d.checks.add(new Check("Document Style", "pass",
                    "Document Style = '" + docStyle + "'."));
        }

        ChangeRow latestEco = findLatestImplementedChange(item.itemId, "ECO");
        if (latestEco == null) {
            d.checks.add(new Check("Latest Implemented ECO", "fail",
                    "No ECO with status 'Implemented' has ever released this part."));
            d.nextSteps.add("Run the part through an ECO and let it reach Implemented status.");
        } else {
            d.checks.add(new Check("Latest Implemented ECO", "pass",
                    "Latest implemented: " + latestEco.changeNumber
                            + " (rev " + (latestEco.revNumber == null ? "?" : latestEco.revNumber) + ")."));
        }

        FlexBundle flex = readPartFlex(item.itemId);
        addFlexChecks(d, flex, "Parts");

        AttachmentSummary atts = countAttachments(item.itemId, latestEco == null ? 0L : latestEco.changeId);
        addAttachmentChecks(d, atts, latestEco != null);

        d.summary = composeDocumentSummary(docStyle, docStyleOk, latestEco, flex, atts)
                .replaceAll("DCO", "ECO").replaceAll("Page Three", "Page Two");
    }

    // -----------------------------------------------------------------------
    //  Deviation path (when the user typed a Change number directly)
    // -----------------------------------------------------------------------

    private void diagnoseDeviation(ChangeRow chg, Diagnosis d) throws SQLException {
        d.checks.add(new Check("Change exists", "pass",
                "Found change " + chg.changeNumber + " (subclass: " + d.subclassName + ")."));
        if (chg.subclassId != SUBCLASS_DEVIATION) {
            d.checks.add(new Check("Subclass", "fail",
                    "Only Deviations (SUBCLASS=20336) are surfaced as Quality Notices on the Shop-Floor view."));
            d.summary = "Change exists but it isn't a Deviation, so it doesn't show as a Quality Notice on the floor.";
            return;
        }
        // (We could expand to active-window + QN attachment checks here. For now
        // the simpler note covers the common confusion.)
        d.summary = "This is a Deviation. If it doesn't appear on the floor, check that its Effective From/To window covers today and that it has an attachment of type 'Quality Notice'.";
        d.nextSteps.add("Verify the deviation's Effective From <= today <= Effective To.");
        d.nextSteps.add("Verify it has an attachment with Type = 'Quality Notice'.");
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static class ItemRow {
        long itemId; String itemNumber; String description; long classId; long subclassId;
    }
    private static class ChangeRow {
        long changeId; String changeNumber; String description;
        long subclassId; String revNumber; String releaseDate;
    }
    private static class FlexBundle {
        int productGroupCount;  // tokens
        int productCount;       // tokens
        int specCount;          // tokens
        /** Names of fields that mix 'ALL' with a specific value (Page 22 rule). Empty = no conflict. */
        java.util.List<String> allConflict = java.util.Collections.emptyList();
    }
    private static class AttachmentSummary {
        int totalAll;
        int pdfAll;
        int totalForLatestChange;
        int pdfForLatestChange;
    }

    private ItemRow lookupItem(String num) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ID, ITEM_NUMBER, DESCRIPTION, CLASS, SUBCLASS FROM agile.ITEM WHERE ITEM_NUMBER = ?")) {
            ps.setString(1, num);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ItemRow r = new ItemRow();
                    r.itemId = rs.getLong(1);
                    r.itemNumber = rs.getString(2);
                    r.description = rs.getString(3);
                    r.classId = rs.getLong(4);
                    r.subclassId = rs.getLong(5);
                    return r;
                }
            }
        }
        return null;
    }

    private ChangeRow lookupChange(String num) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ID, CHANGE_NUMBER, DESCRIPTION, SUBCLASS FROM agile.CHANGE WHERE CHANGE_NUMBER = ?")) {
            ps.setString(1, num);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ChangeRow r = new ChangeRow();
                    r.changeId = rs.getLong(1);
                    r.changeNumber = rs.getString(2);
                    r.description = rs.getString(3);
                    r.subclassId = rs.getLong(4);
                    return r;
                }
            }
        }
        return null;
    }

    private Long readSingleLong(String sql, long id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    if (rs.wasNull()) return null;
                    return v;
                }
            }
        }
        return null;
    }

    private ChangeRow findLatestImplementedChange(long itemId, String typeLabel) throws SQLException {
        String sql =
            "SELECT * FROM (" +
            "  SELECT r.REV_NUMBER, TO_CHAR(r.RELEASE_DATE,'YYYY-MM-DD') rd, c.ID, c.CHANGE_NUMBER, c.SUBCLASS, " +
            "         ROW_NUMBER() OVER (ORDER BY r.RELEASE_DATE DESC) rn " +
            "    FROM agile.REV r JOIN agile.CHANGE c ON c.ID = r.CHANGE " +
            "   WHERE r.ITEM = ? " +
            "     AND c.STATUS   IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = 'Implemented') " +
            "     AND c.SUBCLASS IN (SELECT ID FROM agile.NODETABLE WHERE DESCRIPTION = ?)" +
            ") WHERE rn = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, itemId);
            ps.setString(2, typeLabel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ChangeRow r = new ChangeRow();
                    r.revNumber    = rs.getString("REV_NUMBER");
                    r.releaseDate  = rs.getString("RD");
                    r.changeId     = rs.getLong("ID");
                    r.changeNumber = rs.getString("CHANGE_NUMBER");
                    r.subclassId   = rs.getLong("SUBCLASS");
                    return r;
                }
            }
        }
        return null;
    }

    private FlexBundle readDocumentFlex(long itemId) throws SQLException {
        FlexBundle b = readFlexBundle(itemId, ATTID_DOC_PRODUCT_GROUP, ATTID_DOC_PRODUCT, ATTID_DOC_SPEC);
        // ALL-vs-specific conflict detection — see eVAR training guide page 22:
        // "If document owner select 'ALL' in either Product Group, Product or
        //  Spec, they can't put in any other specific value. Because it will
        //  conflict with configuration logic." This silently blocks display in
        //  Camstar; surface it explicitly so the doc owner knows what's broken.
        b.allConflict = readAllConflict(itemId, ATTID_DOC_PRODUCT_GROUP, ATTID_DOC_PRODUCT, ATTID_DOC_SPEC);
        return b;
    }

    private FlexBundle readPartFlex(long itemId) throws SQLException {
        // Parts don't use AGILE_FLEX for these — they're on PAGE_TWO multilist columns.
        // Read directly.
        FlexBundle b = new FlexBundle();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MULTILIST09, MULTILIST10, MULTILIST11 FROM PAGE_TWO WHERE ID = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    b.productCount      = countCsvTokens(rs.getString(1));
                    b.specCount         = countCsvTokens(rs.getString(2));
                    b.productGroupCount = countCsvTokens(rs.getString(3));
                }
            }
        }
        return b;
    }

    private FlexBundle readFlexBundle(long itemId, long pgAttid, long prodAttid, long specAttid) throws SQLException {
        FlexBundle b = new FlexBundle();
        String sql = "SELECT ATTID, TEXT FROM agile.AGILE_FLEX WHERE ID = ? AND ATTID IN (?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, itemId);
            ps.setLong(2, pgAttid);
            ps.setLong(3, prodAttid);
            ps.setLong(4, specAttid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long attid = rs.getLong(1);
                    int n = countCsvTokens(rs.getString(2));
                    if (attid == pgAttid)        b.productGroupCount = n;
                    else if (attid == prodAttid) b.productCount      = n;
                    else if (attid == specAttid) b.specCount         = n;
                }
            }
        }
        return b;
    }

    private static int countCsvTokens(String csv) {
        if (csv == null || csv.trim().isEmpty()) return 0;
        int n = 0;
        for (String t : csv.split(",")) if (!t.trim().isEmpty()) n++;
        return n;
    }

    /**
     * Detects the "ALL conflict" rule from the eVAR training guide page 22:
     * Page Three.{Product Group, Product, Spec} cannot mix the literal value
     * "ALL" with any other specific value. We resolve each token through
     * LISTENTRY (or, for Product, through ITEM.ITEM_NUMBER) and check whether
     * one of them is "ALL" while the field has &gt;1 token.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<String> readAllConflict(long itemId, long pgAttid, long prodAttid, long specAttid) throws SQLException {
        java.util.List<String> conflicting = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT ATTID, TEXT FROM agile.AGILE_FLEX WHERE ID = ? AND ATTID IN (?, ?, ?)")) {
            ps.setLong(1, itemId);
            ps.setLong(2, pgAttid);
            ps.setLong(3, prodAttid);
            ps.setLong(4, specAttid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long attid = rs.getLong(1);
                    String csv = rs.getString(2);
                    if (csv == null || csv.trim().isEmpty()) continue;
                    String[] tokens = csv.split(",");
                    int real = 0;
                    boolean hasAll = false;
                    for (String t : tokens) {
                        String trimmed = t.trim();
                        if (trimmed.isEmpty()) continue;
                        real++;
                        // For Product (ATTID matches prodAttid) the token is an ITEM.ID,
                        // not a LISTENTRY ENTRYID. Other two are LISTENTRY-resolved.
                        try {
                            long id = Long.parseLong(trimmed);
                            String label;
                            if (attid == prodAttid) {
                                label = lookupItemNumber(id);
                            } else {
                                label = lookups.resolveListEntry(id);
                            }
                            if (label != null && "ALL".equalsIgnoreCase(label.trim())) hasAll = true;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (hasAll && real > 1) {
                        if (attid == pgAttid)        conflicting.add("Product Group");
                        else if (attid == prodAttid) conflicting.add("Product");
                        else if (attid == specAttid) conflicting.add("Spec");
                    }
                }
            }
        }
        return conflicting;
    }

    private String lookupItemNumber(long itemId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ITEM_NUMBER FROM agile.ITEM WHERE ID = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private void addFlexChecks(Diagnosis d, FlexBundle flex, String classLabel) {
        String pageLabel = "Documents".equals(classLabel) ? "Page Three" : "Page Two";
        if (flex.specCount > 0) {
            d.checks.add(new Check("Spec / Step Codes", "pass",
                    flex.specCount + " spec code(s) mapped via " + pageLabel + " → Spec."));
        } else {
            d.checks.add(new Check("Spec / Step Codes", "fail",
                    pageLabel + " → Spec is empty. The SDSM job needs at least one step code to know which factory operations the doc applies to."));
            d.nextSteps.add("Populate " + pageLabel + " → Spec with the step / spec codes this doc covers.");
        }
        if (flex.productCount > 0) {
            d.checks.add(new Check("Product", "pass",
                    flex.productCount + " product item(s) referenced via " + pageLabel + " → Product."));
        } else {
            d.checks.add(new Check("Product", "fail",
                    pageLabel + " → Product is empty (the doc isn't tied to any product item)."));
            d.nextSteps.add("Set " + pageLabel + " → Product to the product item(s) this doc covers (use 'ALL' if it applies broadly).");
        }
        if (flex.productGroupCount > 0) {
            d.checks.add(new Check("Product Group", "pass",
                    flex.productGroupCount + " product group(s) referenced via " + pageLabel + " → Product Group."));
        } else {
            // INFO not FAIL — Product Group is reported but not a gate in the main
            // SdsmDocumentsService (it never short-circuits when empty). Surface
            // the gap so doc owners know to fill it, but don't make it look like
            // it's blocking display when it isn't.
            d.checks.add(new Check("Product Group", "info",
                    pageLabel + " → Product Group is empty. Doesn't block display, but doc owners typically set this."));
        }
        // ALL-conflict surfacing — silent killer per eVAR training guide page 22.
        if (flex.allConflict != null && !flex.allConflict.isEmpty()) {
            String fields = String.join(", ", flex.allConflict);
            d.checks.add(new Check("'ALL' conflict", "fail",
                    "On " + pageLabel + " → " + fields + ", the value 'ALL' is mixed with one or more specific values. eVAR / TTMS configuration logic forbids this — the doc silently won't display."));
            d.nextSteps.add("On " + pageLabel + " → " + fields
                    + ", pick EITHER 'ALL' OR specific values, not both. (See eVAR training guide page 22.)");
        }
    }

    private AttachmentSummary countAttachments(long itemId, long latestChangeId) throws SQLException {
        AttachmentSummary s = new AttachmentSummary();
        String sqlAll =
            "SELECT FILEFILE_TYPE FROM agile.ATTACHMENT_FULL_MAP WHERE PARENT_ID = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sqlAll)) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.totalAll++;
                    String ft = rs.getString(1);
                    if (ft != null && ft.toLowerCase().contains("pdf")) s.pdfAll++;
                }
            }
        }
        if (latestChangeId > 0) {
            String sqlLatest =
                "SELECT FILEFILE_TYPE FROM agile.ATTACHMENT_FULL_MAP WHERE PARENT_ID = ? AND PARENT_ID2 = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sqlLatest)) {
                ps.setLong(1, itemId);
                ps.setLong(2, latestChangeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        s.totalForLatestChange++;
                        String ft = rs.getString(1);
                        if (ft != null && ft.toLowerCase().contains("pdf")) s.pdfForLatestChange++;
                    }
                }
            }
        }
        return s;
    }

    private void addAttachmentChecks(Diagnosis d, AttachmentSummary atts, boolean haveLatestChange) {
        if (atts.totalAll == 0) {
            d.checks.add(new Check("Attachments (any)", "fail",
                    "No attachments on this object at all."));
            d.nextSteps.add("Add the controlled PDF as an attachment in Agile.");
            return;
        }
        d.checks.add(new Check("Attachments (any)", "info",
                atts.totalAll + " total attachment(s) across all changes; " + atts.pdfAll + " PDF(s)."));
        if (!haveLatestChange) return;
        if (atts.totalForLatestChange == 0) {
            d.checks.add(new Check("Attachments on latest change", "fail",
                    "Older attachments exist but none were carried into the latest implemented change."));
            d.nextSteps.add("Re-attach the controlled PDF to the latest implemented change so it surfaces on the floor.");
        } else if (atts.pdfForLatestChange == 0) {
            d.checks.add(new Check("Attachments on latest change", "fail",
                    "Latest change has " + atts.totalForLatestChange + " attachment(s), but none are PDFs (Shop-Floor Docs only shows PDFs)."));
            d.nextSteps.add("Add a PDF version of the controlled document to the latest implemented change.");
        } else {
            d.checks.add(new Check("Attachments on latest change", "pass",
                    atts.pdfForLatestChange + " PDF(s) on the latest implemented change."));
        }
    }
}
