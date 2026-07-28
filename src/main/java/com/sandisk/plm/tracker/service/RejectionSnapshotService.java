package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Freezes computed Returns Tracker periods (week/month/quarter) to immutable JSON
 * files so leadership can revisit prior reports from a dropdown even after the
 * rolling 90-day source window has aged the underlying ECNs out of Agile.
 *
 * Files live in {@code data/ecn-report/rejection-snapshots/}:
 *   week-YYYY-MM-DD.json | month-YYYY-MM.json | quarter-YYYY-QN.json
 * Each holds period bounds, both aggregate sets (audit + AI), the agreement block,
 * the enriched events, and the narrative (best-effort).
 */
@Service
public class RejectionSnapshotService {

    private static final Logger logger = Logger.getLogger(RejectionSnapshotService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String SNAP_DIR = DATA_DIR + "/rejection-snapshots";
    private static final String BACKFILL_MARKER = SNAP_DIR + "/.backfilled";

    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy");

    @Autowired private RejectionTrackerService rejectionService;

    // ---- id / label / bounds helpers (static, pure) ----

    public static String monthId(LocalDate d) {
        return "month-" + d.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
    public static String quarterId(LocalDate d) {
        int q = (d.getMonthValue() - 1) / 3 + 1;
        return "quarter-" + d.getYear() + "-Q" + q;
    }
    public static String weekId(LocalDate weekEnding) {
        return "week-" + weekEnding.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String labelFor(String id) {
        if (id.startsWith("month-")) {
            LocalDate d = LocalDate.parse(id.substring(6) + "-01");
            return d.format(MONTH_LABEL);
        }
        if (id.startsWith("quarter-")) {
            String[] p = id.substring(8).split("-Q");
            return "Q" + p[1] + " " + p[0];
        }
        if (id.startsWith("week-")) {
            LocalDate d = LocalDate.parse(id.substring(5));
            return "Week ending " + d.format(WEEK_LABEL);
        }
        return id;
    }

    public static String typeOf(String id) {
        if (id.startsWith("month-")) return "month";
        if (id.startsWith("quarter-")) return "quarter";
        if (id.startsWith("week-")) return "week";
        return "unknown";
    }

    /** Inclusive [start,end] for quarter n (1-4) of a year. */
    public static LocalDate[] quarterBounds(int year, int q) {
        int startMonth = (q - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        return new LocalDate[]{start, end};
    }

    /** Calendar months from span-min..span-max, excluding the month containing {@code today}. */
    public static List<YearMonth> completeMonthsBetween(LocalDate min, LocalDate max, LocalDate today) {
        List<YearMonth> out = new ArrayList<>();
        YearMonth cur = YearMonth.from(min);
        YearMonth last = YearMonth.from(max);
        YearMonth currentMonth = YearMonth.from(today);
        while (!cur.isAfter(last)) {
            if (cur.isBefore(currentMonth)) out.add(cur);
            cur = cur.plusMonths(1);
        }
        return out;
    }

    // ---- file IO (package-visible for tests) ----

    boolean writeSnapshotFile(File f, Map<String, Object> payload) {
        if (f.exists()) return false; // immutable — never overwrite history
        try {
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, payload);
            return true;
        } catch (Exception e) {
            logger.warning("[RT-SNAP] write failed " + f + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readSnapshotFile(File f) {
        try {
            return mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            logger.warning("[RT-SNAP] read failed " + f + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private File fileFor(String id) { return new File(SNAP_DIR, id + ".json"); }

    // ---- payload builder ----

    Map<String, Object> buildPayload(String id, LocalDate from, LocalDate to) {
        List<Map<String, Object>> events = rejectionService.getEventsInRange(from, to);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("type", typeOf(id));
        payload.put("label", labelFor(id));
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("events", events);
        payload.put("aggregates", rejectionService.getAggregatesFor(
            events, "auditCategory", RejectionTrackerService.AUDIT_CATEGORY_ORDER));
        payload.put("aggregatesAi", rejectionService.getAggregatesFor(
            events, "aiCategory", RejectionTrackerService.CATEGORIES));
        payload.put("agreement", rejectionService.computeAgreement(events));
        String windowKey = RejectionTrackerService.windowKeyForRange(from, to);
        payload.put("narrative", rejectionService.getNarrative(windowKey));
        return payload;
    }

    /** Freeze [from,to] as snapshot {@code id}; skips if it already exists. */
    public boolean writeSnapshot(String id, LocalDate from, LocalDate to) {
        File f = fileFor(id);
        if (f.exists()) return false;
        return writeSnapshotFile(f, buildPayload(id, from, to));
    }

    /** Load a frozen snapshot by id; null if absent. */
    public Map<String, Object> readSnapshot(String id) {
        File f = fileFor(id);
        if (!f.exists()) return null;
        return readSnapshotFile(f);
    }

    /** Directory listing → period metadata, newest first. */
    public List<Map<String, Object>> listPeriods() {
        File dir = new File(SNAP_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Map<String, Object> full = readSnapshotFile(f);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", id);
            meta.put("type", typeOf(id));
            meta.put("label", labelFor(id));
            meta.put("from", full.get("from"));
            meta.put("to", full.get("to"));
            meta.put("generatedAt", full.get("generatedAt"));
            out.add(meta);
        }
        // Sort by `to` date descending (fallback to id).
        out.sort((a, b) -> {
            String ta = String.valueOf(a.get("to")), tb = String.valueOf(b.get("to"));
            int c = tb.compareTo(ta);
            return c != 0 ? c : String.valueOf(b.get("id")).compareTo(String.valueOf(a.get("id")));
        });
        return out;
    }

    /** One-time: freeze all complete weeks (last 26), months, and quarters in the cache. */
    @PostConstruct
    public void backfillOnce() {
        try {
            if (new File(BACKFILL_MARKER).exists()) return;
            LocalDate[] span = rejectionService.getEventDateSpan();
            if (span == null) return;
            LocalDate today = LocalDate.now();

            // Months
            for (YearMonth ym : completeMonthsBetween(span[0], span[1], today)) {
                LocalDate from = ym.atDay(1);
                LocalDate to = ym.atEndOfMonth();
                writeSnapshot(monthId(from), from, to);
            }
            // Quarters (only fully-complete quarters before the current quarter)
            int curQ = (today.getMonthValue() - 1) / 3 + 1;
            for (int year = span[0].getYear(); year <= span[1].getYear(); year++) {
                for (int q = 1; q <= 4; q++) {
                    LocalDate[] qb = quarterBounds(year, q);
                    boolean complete = qb[1].isBefore(today)
                        && !(year == today.getYear() && q == curQ);
                    boolean inSpan = !qb[1].isBefore(span[0]) && !qb[0].isAfter(span[1]);
                    if (complete && inSpan) writeSnapshot(quarterId(qb[0]), qb[0], qb[1]);
                }
            }
            // Weeks — last 26 complete weeks ending on a Sunday, within span.
            LocalDate weekEnd = today.minusDays((today.getDayOfWeek().getValue() % 7) + 1); // last Sunday
            for (int i = 0; i < 26; i++) {
                LocalDate to = weekEnd.minusWeeks(i);
                LocalDate from = to.minusDays(6);
                if (to.isBefore(span[0])) break;
                if (from.isAfter(span[1])) continue;
                writeSnapshot(weekId(to), from, to);
            }
            new File(BACKFILL_MARKER).getParentFile().mkdirs();
            new File(BACKFILL_MARKER).createNewFile();
            logger.info("[RT-SNAP] Backfill complete: " + listPeriods().size() + " periods");
        } catch (Exception e) {
            logger.warning("[RT-SNAP] Backfill failed: " + e.getMessage());
        }
    }
}
