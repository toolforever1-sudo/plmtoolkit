package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomResult;
import com.sandisk.plm.tracker.model.ObaResolution;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Resolves a SKU to the OBA document item numbers by walking its BOM:
 *   SKU → C039 assembly → L000 "Outer ... Shipping Label" item → D026 spec item.
 * "Search the whole sub-tree at each level"; first match wins, extras are flagged.
 */
@Service
public class ObaResolverService {

    /** Deep enough for SKU→C039→L000→D026 (~4 levels) while staying on the pure-SQL
     *  path — BomDataService routes depth>10 to the offline file fallback. */
    static final int MAX_DEPTH = 10;

    private static final Pattern OUTER_SHIPPING =
            Pattern.compile("outer.*shipping label", Pattern.CASE_INSENSITIVE);

    private final BomDataService bomDataService;

    public ObaResolverService(BomDataService bomDataService) {
        this.bomDataService = bomDataService;
    }

    public ObaResolution resolve(String sku) {
        List<BomResult> rows = bomDataService.explode(sku.trim(), MAX_DEPTH);
        return resolveFromRows(sku.trim(), rows);
    }

    /**
     * Pure resolution over already-fetched BOM rows. Unit-tested without a DB.
     *
     * <p>Ancestry comes from each row's {@link BomResult#getPath()}
     * (SYS_CONNECT_BY_PATH), NOT {@code getParent()}. In {@code BomDataService.explode}
     * the {@code parent} column is {@code PRIOR BOM_NUMBER}, which for a deep row is the
     * GRANDPARENT's BOM number, not the immediate parent — so a parent-keyed adjacency
     * map mis-nests the tree (a real bug found in QA: L000 reported under the SKU instead
     * of under its C039). The path string carries the true chain
     * {@code "SKU > C039… > L000… > D026…"}, so we match "X is under Y" by testing whether
     * Y appears as an ancestor token in X's path.
     */
    public ObaResolution resolveFromRows(String sku, List<BomResult> rows) {
        ObaResolution res = new ObaResolution();

        // C039 assembly: any component (anywhere in the SKU tree) whose number starts C039.
        List<BomResult> c039s = collect(rows, r -> startsWith(r.getComponent(), "C039"));
        if (c039s.isEmpty()) {
            res.warnings.add("No C039 assembly found under SKU " + sku);
            return res;
        }
        res.c039Item = c039s.get(0).getComponent();
        int c039Distinct = distinctComponents(c039s);
        if (c039Distinct > 1) {
            res.warnings.add("Multiple C039 assemblies under " + sku + "; using first "
                    + res.c039Item + " (" + c039Distinct + " found)");
        }

        // L000 Outer Shipping Label: starts L000, description matches, and the chosen C039
        // is an ancestor on its BOM path.
        List<BomResult> labels = collect(rows, r -> startsWith(r.getComponent(), "L000")
                && OUTER_SHIPPING.matcher(nvl(r.getDescription())).find()
                && isUnder(r.getPath(), res.c039Item));
        if (labels.isEmpty()) {
            res.warnings.add("No L000 Outer Shipping Label under C039 " + res.c039Item);
            return res;
        }
        res.labelProofItem = labels.get(0).getComponent();
        res.labelProofDesc = labels.get(0).getDescription();
        int labelDistinct = distinctComponents(labels);
        if (labelDistinct > 1) {
            res.warnings.add("Multiple L000 Outer Shipping Label items under " + res.c039Item
                    + "; using first " + res.labelProofItem + " (" + labelDistinct + " found)");
        }

        // D026 spec: starts D026 and the chosen L000 is an ancestor on its BOM path.
        List<BomResult> specs = collect(rows, r -> startsWith(r.getComponent(), "D026")
                && isUnder(r.getPath(), res.labelProofItem));
        if (specs.isEmpty()) {
            res.warnings.add("No D026 spec under L000 " + res.labelProofItem);
            return res;
        }
        res.labelSpecItem = specs.get(0).getComponent();
        res.labelSpecDesc = specs.get(0).getDescription();
        int specDistinct = distinctComponents(specs);
        if (specDistinct > 1) {
            res.warnings.add("Multiple D026 spec items under " + res.labelProofItem
                    + "; using first " + res.labelSpecItem + " (" + specDistinct + " found)");
        }
        return res;
    }

    private static List<BomResult> collect(List<BomResult> rows, Predicate<BomResult> match) {
        List<BomResult> hits = new ArrayList<>();
        for (BomResult r : rows) if (match.test(r)) hits.add(r);
        return hits;
    }

    /** Count distinct component numbers (the same item can appear on several BOM paths,
     *  e.g. under both a C039 and a C057 branch — that is one item, not an ambiguity). */
    private static int distinctComponents(List<BomResult> rows) {
        Set<String> seen = new HashSet<>();
        for (BomResult r : rows) seen.add(upper(r.getComponent()));
        return seen.size();
    }

    /** True when {@code node} appears as an ANCESTOR on the {@code path}
     *  (SYS_CONNECT_BY_PATH, "A > B > C"), i.e. as any token except the final one
     *  (which is the component itself). */
    static boolean isUnder(String path, String node) {
        if (path == null || node == null) return false;
        String[] toks = path.split(">");
        String target = upper(node);
        // exclude the last token (the component itself); a node is "under" an ancestor.
        for (int i = 0; i < toks.length - 1; i++) {
            if (upper(toks[i]).equals(target)) return true;
        }
        return false;
    }

    private static boolean startsWith(String s, String prefix) {
        return s != null && s.trim().toUpperCase().startsWith(prefix);
    }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String nvl(String s) { return s == null ? "" : s; }
}
