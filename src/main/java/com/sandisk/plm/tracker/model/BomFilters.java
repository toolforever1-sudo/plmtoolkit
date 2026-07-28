package com.sandisk.plm.tracker.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-filters for the BOM Explorer. Applied to each row at emit time using
 * metadata on the "outward" node — the child in Explode mode, the parent in
 * Where Used mode. Empty filter lists mean "no filter on this dimension".
 *
 * <p>{@code maxTopLevelParents} only applies to Where Used and caps the
 * top-level walk's final emit count.
 */
public class BomFilters {

    public enum Mode { INCLUDE, EXCLUDE }

    private final Set<String> lifecycles;
    private final Mode lifecyclesMode;
    private final Set<String> partTypes;
    private final Mode partTypesMode;
    private final List<String> prefixes;
    private final Mode prefixesMode;
    private final int maxTopLevelParents;

    private BomFilters(Set<String> lifecycles, Mode lifecyclesMode,
                       Set<String> partTypes, Mode partTypesMode,
                       List<String> prefixes, Mode prefixesMode,
                       int maxTopLevelParents) {
        this.lifecycles = lifecycles;
        this.lifecyclesMode = lifecyclesMode;
        this.partTypes = partTypes;
        this.partTypesMode = partTypesMode;
        this.prefixes = prefixes;
        this.prefixesMode = prefixesMode;
        this.maxTopLevelParents = maxTopLevelParents;
    }

    public static BomFilters none() {
        return new BomFilters(Collections.emptySet(), Mode.INCLUDE,
                              Collections.emptySet(), Mode.INCLUDE,
                              Collections.emptyList(), Mode.INCLUDE, 0);
    }

    /** Parse from URL query params (all optional). CSV lists; modes are
     *  case-insensitive "include" or "exclude" (default: include). */
    public static BomFilters parse(String lifecyclesCsv, String lifecyclesMode,
                                   String partTypesCsv, String partTypesMode,
                                   String prefixesCsv, String prefixesMode,
                                   Integer maxTopLevelParents) {
        return new BomFilters(
            csvToUpperSet(lifecyclesCsv),
            parseMode(lifecyclesMode),
            csvToUpperSet(partTypesCsv),
            parseMode(partTypesMode),
            csvToList(prefixesCsv),
            parseMode(prefixesMode),
            maxTopLevelParents == null ? 0 : Math.max(0, maxTopLevelParents)
        );
    }

    private static Set<String> csvToUpperSet(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t.toUpperCase());
        }
        return out;
    }

    private static List<String> csvToList(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t.toUpperCase());
        }
        return out;
    }

    private static Mode parseMode(String s) {
        return "exclude".equalsIgnoreCase(s) ? Mode.EXCLUDE : Mode.INCLUDE;
    }

    /** True when no row-level filters are set (we can skip the predicate check). */
    public boolean isEmpty() {
        return lifecycles.isEmpty() && partTypes.isEmpty() && prefixes.isEmpty();
    }

    /** Apply to one row's outward-node metadata. The component string is the
     *  thing whose prefix we test (the part number). Null fields are treated
     *  as empty — INCLUDE filters reject empty, EXCLUDE filters keep them. */
    public boolean accept(String lifecyclePhase, String partType, String component) {
        if (!matches(lifecycles, lifecyclesMode, up(lifecyclePhase))) return false;
        if (!matches(partTypes, partTypesMode, up(partType))) return false;
        if (!prefixes.isEmpty()) {
            String c = up(component);
            boolean any = false;
            for (String p : prefixes) {
                if (c.startsWith(p)) { any = true; break; }
            }
            if (prefixesMode == Mode.INCLUDE && !any) return false;
            if (prefixesMode == Mode.EXCLUDE && any) return false;
        }
        return true;
    }

    private static boolean matches(Set<String> set, Mode mode, String value) {
        if (set.isEmpty()) return true;
        boolean in = set.contains(value);
        return mode == Mode.INCLUDE ? in : !in;
    }

    private static String up(String s) { return s == null ? "" : s.toUpperCase(); }

    public int getMaxTopLevelParents() { return maxTopLevelParents; }

    /** Human label for chips / Excel footer. Empty string when no filters. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        appendDim(sb, "Lifecycle", lifecycles, lifecyclesMode);
        appendDim(sb, "Part Type", partTypes, partTypesMode);
        appendDim(sb, "Prefix", new HashSet<>(prefixes), prefixesMode);
        if (maxTopLevelParents > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("Max top-level: ").append(maxTopLevelParents);
        }
        return sb.toString();
    }

    private static void appendDim(StringBuilder sb, String label, Set<String> set, Mode mode) {
        if (set.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(label).append(' ').append(mode == Mode.INCLUDE ? "=" : "≠").append(' ');
        List<String> sorted = new ArrayList<>(set);
        Collections.sort(sorted);
        sb.append(String.join(",", sorted));
    }
}
