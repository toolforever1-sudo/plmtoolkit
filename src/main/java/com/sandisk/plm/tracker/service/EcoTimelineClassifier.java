package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Pure logic: turn AGILE.BOM redline rows for one parent assembly into
 * ECO-attributed timeline events. No DB access — unit-tested in isolation.
 *
 * An event is produced for a change only when that change released inside the
 * [from,to] window. A row added (CHANGE_IN) whose PRIOR_BOM points at a row
 * removed (CHANGE_OUT) by the SAME change is a modify; otherwise the add and
 * remove stand alone. A modify whose only differing fields are untracked
 * (e.g. ref-designator) is dropped.
 */
public class EcoTimelineClassifier {

    public static final String ADDED = "Added";
    public static final String REMOVED = "Removed";
    public static final String PRIMARY_NUMBER_CHANGED = "Primary number changed";
    public static final String QUANTITY_CHANGED = "Quantity changed";
    public static final String FIND_NUMBER_CHANGED = "Find # changed";
    public static final String NOTES_CHANGED = "Notes changed";
    public static final String MODIFIED = "Modified";

    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(PT);

    public List<EcoTimelineRow> classifyAssembly(String parentPn, String path, int level,
                                                 List<BomRedlineRow> rows,
                                                 Timestamp from, Timestamp to) {
        Map<String, List<BomRedlineRow>> addsByChange = new LinkedHashMap<>();
        Map<String, List<BomRedlineRow>> remsByChange = new LinkedHashMap<>();
        Map<String, Timestamp> rdByChange = new HashMap<>();
        Map<String, String> descByChange = new HashMap<>();    // change # -> CHANGE.DESCRIPTION
        Map<String, String> statusByChange = new HashMap<>();  // change # -> workflow status name

        for (BomRedlineRow r : rows) {
            if (r.changeInNum != null && inWindow(r.changeInRd, from, to)) {
                addsByChange.computeIfAbsent(r.changeInNum, k -> new ArrayList<>()).add(r);
                rdByChange.put(r.changeInNum, r.changeInRd);
                descByChange.put(r.changeInNum, r.changeInDesc);
                statusByChange.put(r.changeInNum, r.changeInStatus);
            }
            if (r.changeOutNum != null && inWindow(r.changeOutRd, from, to)) {
                remsByChange.computeIfAbsent(r.changeOutNum, k -> new ArrayList<>()).add(r);
                rdByChange.put(r.changeOutNum, r.changeOutRd);
                descByChange.put(r.changeOutNum, r.changeOutDesc);
                statusByChange.put(r.changeOutNum, r.changeOutStatus);
            }
        }

        List<EcoTimelineRow> out = new ArrayList<>();
        Set<String> changes = new LinkedHashSet<>();
        changes.addAll(addsByChange.keySet());
        changes.addAll(remsByChange.keySet());

        for (String chg : changes) {
            List<BomRedlineRow> adds = new ArrayList<>(addsByChange.getOrDefault(chg, Collections.emptyList()));
            List<BomRedlineRow> rems = new ArrayList<>(remsByChange.getOrDefault(chg, Collections.emptyList()));
            Timestamp rdTs = rdByChange.get(chg);
            String rd = FMT.format(rdTs.toInstant());
            String desc = descByChange.get(chg);
            String status = statusByChange.get(chg);

            // Pair modifies via PRIOR_BOM.
            Iterator<BomRedlineRow> ai = adds.iterator();
            while (ai.hasNext()) {
                BomRedlineRow a = ai.next();
                if (a.priorBom == 0) continue;
                BomRedlineRow match = null;
                for (BomRedlineRow rmv : rems) {
                    if (rmv.bomRowId == a.priorBom) { match = rmv; break; }
                }
                if (match != null) {
                    rems.remove(match);
                    ai.remove();
                    EcoTimelineRow mod = modifyRow(parentPn, path, level, chg, rd, rdTs, desc, status, match, a);
                    if (mod != null) out.add(mod);   // null = only untracked fields changed
                }
            }
            for (BomRedlineRow a : adds) {
                out.add(new EcoTimelineRow(level, parentPn, path, a.componentPn, a.componentDesc,
                        chg, rd, ADDED, "Added", desc, status, null, rdTs));
            }
            for (BomRedlineRow rmv : rems) {
                out.add(new EcoTimelineRow(level, parentPn, path, rmv.componentPn, rmv.componentDesc,
                        chg, rd, REMOVED, "Removed", desc, status, null, rdTs));
            }
        }
        return out;
    }

    private EcoTimelineRow modifyRow(String parentPn, String path, int level, String chg, String rd,
                                     Timestamp rdTs, String ecoDesc, String ecoStatus,
                                     BomRedlineRow oldR, BomRedlineRow newR) {
        List<String> details = new ArrayList<>();
        String type = null;
        if (!eq(oldR.componentPn, newR.componentPn)) {
            details.add("Replaced " + nz(oldR.componentPn) + " → " + nz(newR.componentPn));
            type = PRIMARY_NUMBER_CHANGED;
        }
        if (!eq(oldR.quantity, newR.quantity)) {
            details.add("Qty " + nz(oldR.quantity) + " → " + nz(newR.quantity));
            type = QUANTITY_CHANGED;
        }
        if (!eq(oldR.findNumber, newR.findNumber)) {
            details.add("Find# " + nz(oldR.findNumber) + " → " + nz(newR.findNumber));
            type = FIND_NUMBER_CHANGED;
        }
        if (!eq(oldR.notes, newR.notes)) {
            details.add("Notes changed");
            type = NOTES_CHANGED;
        }
        if (details.isEmpty()) return null;   // only untracked fields (e.g. ref-des) changed
        String finalType = details.size() > 1 ? MODIFIED : type;
        String comp = newR.componentPn != null ? newR.componentPn : oldR.componentPn;
        String desc = newR.componentDesc != null ? newR.componentDesc : oldR.componentDesc;
        return new EcoTimelineRow(level, parentPn, path, comp, desc, chg, rd, finalType,
                String.join("; ", details), ecoDesc, ecoStatus, null, rdTs);
    }

    private static boolean inWindow(Timestamp t, Timestamp from, Timestamp to) {
        return t != null && !t.before(from) && !t.after(to);
    }

    private static boolean eq(String a, String b) { return nz(a).equals(nz(b)); }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
