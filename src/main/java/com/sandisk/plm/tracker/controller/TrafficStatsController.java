package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ActivityLogger;
import com.sandisk.plm.tracker.service.LdapAuthService;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Stats for the User Permissions tab — feeds the 30-day login traffic chart and
 * the per-user "what they typically do" popover.
 *
 * <p>Both endpoints are gated to PLM admin or permissions-admin (same gate as
 * {@link UserPermissionsController}) since they expose login activity that
 * normal users shouldn't see for other people.
 */
@RestController
@RequestMapping("/api/permissions/stats")
public class TrafficStatsController {

    @Autowired private ActivityLogger activityLogger;
    @Autowired private UserPermissionsService permissionsService;
    @Autowired private LdapAuthService ldapAuthService;

    /**
     * AD country → IANA ZoneId. SanDisk has offices in a known set of countries;
     * default to UTC for anything we don't recognise so we never silently misconvert.
     * (Could refine US to multiple zones via the city field later.)
     */
    private static final Map<String, ZoneId> COUNTRY_ZONES = new LinkedHashMap<>();
    static {
        COUNTRY_ZONES.put("united states", ZoneId.of("America/Los_Angeles"));
        COUNTRY_ZONES.put("usa",            ZoneId.of("America/Los_Angeles"));
        COUNTRY_ZONES.put("malaysia",       ZoneId.of("Asia/Kuala_Lumpur"));
        COUNTRY_ZONES.put("india",          ZoneId.of("Asia/Kolkata"));
        COUNTRY_ZONES.put("china",          ZoneId.of("Asia/Shanghai"));
        COUNTRY_ZONES.put("japan",          ZoneId.of("Asia/Tokyo"));
        COUNTRY_ZONES.put("israel",         ZoneId.of("Asia/Jerusalem"));
        COUNTRY_ZONES.put("united kingdom", ZoneId.of("Europe/London"));
        COUNTRY_ZONES.put("germany",        ZoneId.of("Europe/Berlin"));
        COUNTRY_ZONES.put("south korea",    ZoneId.of("Asia/Seoul"));
        COUNTRY_ZONES.put("korea",          ZoneId.of("Asia/Seoul"));
        COUNTRY_ZONES.put("singapore",      ZoneId.of("Asia/Singapore"));
        COUNTRY_ZONES.put("taiwan",         ZoneId.of("Asia/Taipei"));
        COUNTRY_ZONES.put("philippines",    ZoneId.of("Asia/Manila"));
        COUNTRY_ZONES.put("thailand",       ZoneId.of("Asia/Bangkok"));
    }

    /** Resolve the viewer's preferred display timezone from their AD country. */
    private ZoneId viewerZone(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        if (u == null) return ZoneId.of("UTC");
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserByUsername(u.toString());
            if (info == null || info.country == null) return ZoneId.of("UTC");
            ZoneId z = COUNTRY_ZONES.get(info.country.trim().toLowerCase());
            return z == null ? ZoneId.of("UTC") : z;
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /** Short human label for a zone at a given instant — e.g. "PDT", "MYT", "IST". */
    private static String zoneAbbrev(ZoneId zone, long epochMs) {
        try {
            return DateTimeFormatter.ofPattern("zzz").withZone(zone)
                    .format(Instant.ofEpochMilli(epochMs));
        } catch (Exception e) {
            return zone.getId();
        }
    }

    /** Daily login counts + unique-user counts for the last N days (default 30). */
    @GetMapping("/traffic")
    public ResponseEntity<?> traffic(@RequestParam(defaultValue = "30") int days, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        if (days < 1) days = 1;
        if (days > 90) days = 90;

        long now = System.currentTimeMillis();
        long since = now - (long) days * 24L * 60L * 60L * 1000L;
        // Round "since" down to midnight UTC of the start day so the bucket boundaries align.
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(since);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long sinceFloor = cal.getTimeInMillis();

        List<ActivityLogger.ActivityEntry> entries = activityLogger.getActivitiesSince(sinceFloor);

        // Bucket by yyyy-MM-dd (UTC).
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        dayFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        Map<String, Integer> loginCounts = new LinkedHashMap<>();
        Map<String, Set<String>> uniqueUsersByDay = new LinkedHashMap<>();
        // Pre-seed every day in the window so empty days show as 0 instead of being missing.
        Calendar walker = (Calendar) cal.clone();
        for (int i = 0; i < days; i++) {
            String key = dayFmt.format(walker.getTime());
            loginCounts.put(key, 0);
            uniqueUsersByDay.put(key, new HashSet<>());
            walker.add(Calendar.DAY_OF_MONTH, 1);
        }

        for (ActivityLogger.ActivityEntry e : entries) {
            if (!"LOGIN".equals(e.action)) continue;
            String key = dayFmt.format(new Date(e.timestamp));
            if (!loginCounts.containsKey(key)) continue; // outside requested window
            loginCounts.merge(key, 1, Integer::sum);
            if (e.username != null && !e.username.isEmpty()) {
                uniqueUsersByDay.get(key).add(e.username.toLowerCase());
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (String day : loginCounts.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("logins", loginCounts.get(day));
            row.put("uniqueUsers", uniqueUsersByDay.get(day).size());
            series.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("series", series);
        // Echo viewer admin status so the JS can decide whether to attach the
        // click-to-drill handlers (we restrict drilldown to PLM admins; perms-
        // admins like Vikas Singh see chart numbers only).
        out.put("isPlmAdmin", Boolean.TRUE.equals(session.getAttribute("isPlmAdmin")));
        return ResponseEntity.ok(out);
    }

    /**
     * Per-day login drilldown: every LOGIN event on the given date (UTC),
     * grouped by user with their individual login timestamps. Powers the
     * click-on-bar popup in the 30-day traffic chart.
     */
    @GetMapping("/traffic/day")
    public ResponseEntity<?> trafficDay(@RequestParam String date, HttpSession session) {
        // Drilldown is restricted to PLM admins (the chart itself is open to
        // permissions-admins, but the per-user breakdown is admin-only).
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return forbidden();
        if (date == null || date.isEmpty()) return ResponseEntity.badRequest().body(error("date required (YYYY-MM-DD)"));

        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        dayFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        long dayStart;
        try {
            dayStart = dayFmt.parse(date).getTime();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("invalid date — expected YYYY-MM-DD"));
        }
        long dayEnd = dayStart + 24L * 60L * 60L * 1000L;

        List<ActivityLogger.ActivityEntry> entries = activityLogger.getActivitiesSince(dayStart - 1);
        ZoneId zone = viewerZone(session);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
        DateTimeFormatter utcIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

        // username (lowercase) -> { displayName, times[ {display, utcIso} ] }
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();
        int totalLogins = 0;
        String zoneLabel = "UTC";
        for (ActivityLogger.ActivityEntry e : entries) {
            if (e.timestamp < dayStart || e.timestamp >= dayEnd) continue;
            if (!"LOGIN".equals(e.action)) continue;
            String key = e.username == null ? "" : e.username.toLowerCase();
            if (key.isEmpty()) continue;
            Map<String, Object> rec = byUser.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", e.username);
                m.put("displayName", e.displayName);
                m.put("times", new ArrayList<Map<String, String>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, String>> times = (List<Map<String, String>>) rec.get("times");
            Map<String, String> t = new LinkedHashMap<>();
            String tzShort = zoneAbbrev(zone, e.timestamp);
            t.put("display", timeFmt.format(Instant.ofEpochMilli(e.timestamp)) + " " + tzShort);
            t.put("utcIso", utcIso.format(Instant.ofEpochMilli(e.timestamp)));
            times.add(t);
            totalLogins++;
            zoneLabel = tzShort;
        }
        List<Map<String, Object>> users = new ArrayList<>(byUser.values());
        // Sort by login count desc, then by displayName.
        users.sort((a, b) -> {
            @SuppressWarnings("unchecked")
            int aLen = ((List<String>) a.get("times")).size();
            @SuppressWarnings("unchecked")
            int bLen = ((List<String>) b.get("times")).size();
            if (aLen != bLen) return Integer.compare(bLen, aLen);
            String an = String.valueOf(a.get("displayName"));
            String bn = String.valueOf(b.get("displayName"));
            return an.compareToIgnoreCase(bn);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("totalLogins", totalLogins);
        out.put("uniqueUsers", users.size());
        out.put("zone", zone.getId());
        out.put("zoneLabel", zoneLabel);
        out.put("users", users);
        return ResponseEntity.ok(out);
    }

    /**
     * Per-user "session" drilldown: every activity-log entry for {@code username}
     * inside the [fromIso, toIso) UTC window, ordered by timestamp ascending.
     * Powers the "what was this user doing after they logged in at HH:MM?"
     * inline expansion on the per-day login modal.
     */
    @GetMapping("/user/{username}/session")
    public ResponseEntity<?> userSession(@PathVariable String username,
                                          @RequestParam String fromIso,
                                          @RequestParam String toIso,
                                          HttpSession session) {
        // Drilldown is restricted to PLM admins (see /traffic/day comment).
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return forbidden();
        if (username == null || username.isEmpty()) return ResponseEntity.badRequest().body(error("username required"));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long fromMs, toMs;
        try {
            fromMs = iso.parse(fromIso).getTime();
            toMs = iso.parse(toIso).getTime();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("invalid fromIso/toIso — expected yyyy-MM-ddTHH:mm:ssZ"));
        }
        if (toMs <= fromMs) return ResponseEntity.badRequest().body(error("toIso must be after fromIso"));

        ZoneId zone = viewerZone(session);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);

        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(fromMs - 1);
        List<Map<String, Object>> events = new ArrayList<>();
        String zoneLabel = "UTC";
        for (ActivityLogger.ActivityEntry e : all) {
            if (e.timestamp < fromMs || e.timestamp >= toMs) continue;
            if (e.username == null || !e.username.equalsIgnoreCase(username)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", timeFmt.format(Instant.ofEpochMilli(e.timestamp)));
            row.put("action", e.action);
            row.put("details", e.details);
            events.add(row);
            zoneLabel = zoneAbbrev(zone, e.timestamp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("fromIso", fromIso);
        out.put("toIso", toIso);
        out.put("zone", zone.getId());
        out.put("zoneLabel", zoneLabel);
        out.put("events", events);
        return ResponseEntity.ok(out);
    }

    /**
     * Per-user activity drilldown: total logins in the last 7d / 30d, last login
     * timestamp, and the top-N actions they perform (excluding LOGIN since that's
     * already broken out).
     */
    @GetMapping("/user/{username}/activity")
    public ResponseEntity<?> userActivity(@PathVariable String username,
                                           @RequestParam(defaultValue = "5") int topN,
                                           HttpSession session) {
        // Per-user activity popover is admin-only (perms-admins see chart only).
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return forbidden();
        if (username == null || username.isEmpty()) return ResponseEntity.badRequest().body(error("username required"));
        if (topN < 1) topN = 5;
        if (topN > 20) topN = 20;

        long now = System.currentTimeMillis();
        long cutoff30 = now - 30L * 24L * 60L * 60L * 1000L;
        long cutoff7  = now -  7L * 24L * 60L * 60L * 1000L;

        // Read the log over a window that covers what we report (30 days is enough
        // for our report; "last login" we'll compute from whatever the file gives us).
        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(0L);

        int logins7 = 0, logins30 = 0, total30 = 0;
        long lastLoginMs = 0;
        Map<String, Integer> actionCounts = new HashMap<>();
        for (ActivityLogger.ActivityEntry e : all) {
            if (e.username == null || !e.username.equalsIgnoreCase(username)) continue;
            if ("LOGIN".equals(e.action) && e.timestamp > lastLoginMs) lastLoginMs = e.timestamp;
            if (e.timestamp >= cutoff30) {
                total30++;
                if ("LOGIN".equals(e.action)) {
                    logins30++;
                    if (e.timestamp >= cutoff7) logins7++;
                } else {
                    actionCounts.merge(e.action, 1, Integer::sum);
                }
            }
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(actionCounts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", sorted.get(i).getKey());
            row.put("count", sorted.get(i).getValue());
            top.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("logins7d", logins7);
        out.put("logins30d", logins30);
        out.put("totalActions30d", total30);
        out.put("lastLoginAt", lastLoginMs == 0 ? null : isoUtc(lastLoginMs));
        out.put("topActions", top);
        return ResponseEntity.ok(out);
    }

    private static String isoUtc(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    /**
     * IT-only Excel export: every activity-log entry in the requested date
     * range plus a daily-logins summary. Range is inclusive (UTC days).
     * Times in the workbook are formatted in the viewer's AD timezone.
     */
    @GetMapping("/export")
    public void exportActivityLog(@RequestParam String from,
                                    @RequestParam String to,
                                    HttpSession session,
                                    HttpServletResponse response) throws IOException {
        // IT-only — strict admin gate (not just permissions-admin).
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "PLM admin access required.");
            return;
        }
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        dayFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        long fromMs, toMs;
        try {
            fromMs = dayFmt.parse(from).getTime();
            toMs = dayFmt.parse(to).getTime() + 24L * 60L * 60L * 1000L;
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid from/to (expected YYYY-MM-DD)");
            return;
        }
        if (toMs <= fromMs) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "to must be on or after from");
            return;
        }

        ZoneId zone = viewerZone(session);
        DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(zone);
        DateTimeFormatter dateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone);

        List<ActivityLogger.ActivityEntry> all = activityLogger.getActivitiesSince(fromMs - 1);

        // Streaming + inline strings (OOM-safety).
        SXSSFWorkbook wb = new SXSSFWorkbook(null, 1000, false, false);
        try {
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Sheet 1: daily login counts (zone-aware bucket)
            SXSSFSheet logins = wb.createSheet("Daily Logins");
            logins.setColumnWidth(0, 14 * 256);
            logins.setColumnWidth(1, 12 * 256);
            logins.setColumnWidth(2, 18 * 256);
            Row lh = logins.createRow(0);
            Cell[] lhc = { lh.createCell(0), lh.createCell(1), lh.createCell(2) };
            lhc[0].setCellValue("Date (" + zone.getId() + ")");
            lhc[1].setCellValue("Total Logins");
            lhc[2].setCellValue("Unique Users");
            for (Cell c : lhc) c.setCellStyle(headerStyle);

            Map<String, int[]> perDay = new TreeMap<>();
            Map<String, Set<String>> uniqPerDay = new TreeMap<>();
            for (ActivityLogger.ActivityEntry e : all) {
                if (e.timestamp < fromMs || e.timestamp >= toMs) continue;
                if (!"LOGIN".equals(e.action)) continue;
                String d = dateOnly.format(Instant.ofEpochMilli(e.timestamp));
                perDay.computeIfAbsent(d, k -> new int[]{0})[0]++;
                if (e.username != null) uniqPerDay.computeIfAbsent(d, k -> new HashSet<>()).add(e.username.toLowerCase());
            }
            int rowIdx = 1;
            for (Map.Entry<String, int[]> e : perDay.entrySet()) {
                Row r = logins.createRow(rowIdx++);
                r.createCell(0).setCellValue(e.getKey());
                r.createCell(1).setCellValue(e.getValue()[0]);
                r.createCell(2).setCellValue(uniqPerDay.getOrDefault(e.getKey(), Collections.emptySet()).size());
            }

            // Sheet 2: every event in window
            SXSSFSheet events = wb.createSheet("Activity Log");
            events.setColumnWidth(0, 26 * 256);
            events.setColumnWidth(1, 16 * 256);
            events.setColumnWidth(2, 28 * 256);
            events.setColumnWidth(3, 22 * 256);
            events.setColumnWidth(4, 80 * 256);
            Row eh = events.createRow(0);
            String[] headers = { "Timestamp", "Username", "Display Name", "Action", "Details" };
            for (int i = 0; i < headers.length; i++) {
                Cell c = eh.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            int er = 1;
            for (ActivityLogger.ActivityEntry e : all) {
                if (e.timestamp < fromMs || e.timestamp >= toMs) continue;
                Row r = events.createRow(er++);
                r.createCell(0).setCellValue(tsFmt.format(Instant.ofEpochMilli(e.timestamp)));
                r.createCell(1).setCellValue(e.username == null ? "" : e.username);
                r.createCell(2).setCellValue(e.displayName == null ? "" : e.displayName);
                r.createCell(3).setCellValue(e.action == null ? "" : e.action);
                r.createCell(4).setCellValue(clampForExcel(e.details));
            }
            // Optionally freeze header row.
            events.createFreezePane(0, 1);
            logins.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            byte[] bytes = out.toByteArray();
            String filename = "activity-log_" + from + "_to_" + to + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } finally {
            wb.dispose();
            wb.close();
        }
    }

    /** Excel caps cell text at 32,767 chars. Truncate with a marker so AI_DATA_QUERY
     *  rows with huge filter JSON don't blow up the export. */
    private static String clampForExcel(String s) {
        if (s == null) return "";
        final int MAX = 32767;
        if (s.length() <= MAX) return s;
        final String marker = "… [truncated]";
        return s.substring(0, MAX - marker.length()) + marker;
    }

    private boolean isPermsAdmin(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return true;
        Object u = session.getAttribute("username");
        return u != null && permissionsService.isPermissionsAdmin(u.toString());
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(error("Admin access required."));
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }
}
