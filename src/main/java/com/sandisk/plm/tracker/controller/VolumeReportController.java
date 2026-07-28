package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.ChangeSearchService;
import com.sandisk.plm.tracker.service.LdapAuthService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Logger;

/**
 * Volume Reports — sub-tab under ECN Report. Three inputs:
 * <ul>
 *   <li>Manager (sAMAccountName from the access-DL list)</li>
 *   <li>Begin / End date</li>
 * </ul>
 * Resolves the manager's direct (and optionally transitive) reports via AD,
 * maps each to their {@code employeeID} (= Agile {@code LOGINID}), and runs
 * the Change Search SQL for ECO/MCO/AML rows in that window.
 */
@RestController
@RequestMapping("/api/ecn-report/volume")
public class VolumeReportController {

    private static final Logger logger = Logger.getLogger(VolumeReportController.class.getName());

    @Autowired
    private LdapAuthService ldapAuthService;

    @Autowired
    private ChangeSearchService changeSearchService;

    private static final String SESSION_CACHE_KEY = "volumeReportLastRun";

    /** Process-wide cache for "which DL members actually have direct reports
     *  in AD". Re-checked every {@link #HAS_REPORTS_TTL_MS} so org-chart
     *  reshuffles (a new hire, a promotion) show up within an hour without
     *  manual cache busting. Null means uninitialized / failed last attempt;
     *  empty set means "AD said no-one has reports" which is unusual enough
     *  that we don't apply the filter (better to show every DL member than
     *  hide them all if AD is misbehaving). */
    private static final long HAS_REPORTS_TTL_MS = 60L * 60L * 1000L;
    private volatile Set<String> hasReportsCache = null;
    private volatile long hasReportsCachedAt = 0L;

    /** Last successful /run result stashed in the user's HttpSession for fast /export reuse. */
    private static class CachedRun {
        final String key;
        final List<Map<String, Object>> reports;
        final List<String> warnings;
        final Map<String, Object> summary;
        final List<Map<String, Object>> rows;
        CachedRun(String key, List<Map<String, Object>> reports, List<String> warnings,
                  Map<String, Object> summary, List<Map<String, Object>> rows) {
            this.key = key;
            this.reports = reports;
            this.warnings = warnings;
            this.summary = summary;
            this.rows = rows;
        }
    }

    private static String cacheKey(String manager, String fromDate, String toDate, boolean transitive, boolean includeManager) {
        return manager + "|" + fromDate + "|" + toDate + "|" + transitive + "|" + includeManager;
    }

    /** Dropdown source: every member of the access DL who actually has at
     *  least one direct report in AD (i.e. someone could plausibly run a
     *  Volume Report for them). DL members with no reports get filtered out
     *  — picking them was always a "0 ECNs" dead end, and they cluttered
     *  the dropdown.
     *
     *  <p>The "has reports" check is cached process-wide for an hour
     *  ({@link #HAS_REPORTS_TTL_MS}) — it's ~30 LDAP round-trips for the
     *  full DL on a cold load. On AD failure we fall back to the unfiltered
     *  list so the dropdown stays usable. */
    @GetMapping("/managers")
    public Map<String, Object> listManagers(HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (session.getAttribute("username") == null) {
            resp.put("success", false);
            resp.put("message", "Not authenticated.");
            return resp;
        }
        List<LdapAuthService.DirectoryUser> users = ldapAuthService.listAllGroupMembers();
        // Dedup by username — the upstream listAllGroupMembers() can return the
        // same person multiple times when the access DL has nested groups that
        // all contain the user (each path yields one entry). Surfaced in PT-46
        // (Manager dropdown showing "Afrozuddin Muhammed (CW) — 7340642" twice,
        // etc.).
        Set<String> seenUsernames = new HashSet<>();
        List<LdapAuthService.DirectoryUser> deduped = new ArrayList<>();
        for (LdapAuthService.DirectoryUser u : users) {
            if (u.username == null || !seenUsernames.add(u.username)) continue;
            deduped.add(u);
        }

        // Filter to only managers with ≥1 direct report (cached 1h).
        Set<String> withReports = getOrComputeHasReports(seenUsernames);

        List<Map<String, String>> out = new ArrayList<>();
        int filteredOut = 0;
        for (LdapAuthService.DirectoryUser u : deduped) {
            // When withReports is empty we treat that as "AD lookup failed"
            // and skip the filter — better to show everyone than nobody.
            if (!withReports.isEmpty() && !withReports.contains(u.username)) {
                filteredOut++;
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("displayName", u.displayName == null || u.displayName.isEmpty() ? u.username : u.displayName);
            m.put("email", u.email == null ? "" : u.email);
            out.add(m);
        }
        resp.put("success", true);
        resp.put("managers", out);
        resp.put("totalDlMembers", deduped.size());
        resp.put("filteredOutNoReports", filteredOut);
        return resp;
    }

    private Set<String> getOrComputeHasReports(Set<String> dlUsernames) {
        long now = System.currentTimeMillis();
        Set<String> cached = hasReportsCache;
        if (cached != null && (now - hasReportsCachedAt) < HAS_REPORTS_TTL_MS) {
            return cached;
        }
        Set<String> fresh = ldapAuthService.filterUsernamesWithDirectReports(dlUsernames);
        hasReportsCache = fresh;
        hasReportsCachedAt = now;
        return fresh;
    }

    /** Run a volume report. */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (session.getAttribute("username") == null) {
            resp.put("success", false);
            resp.put("message", "Not authenticated.");
            return resp;
        }

        String manager = strOf(body.get("managerUsername"));
        String fromStr = strOf(body.get("fromDate"));
        String toStr = strOf(body.get("toDate"));
        boolean transitive = Boolean.TRUE.equals(body.get("transitive"));
        boolean includeManager = Boolean.TRUE.equals(body.get("includeManager"));
        String tz = strOf(body.get("timezone"));
        if (tz.isEmpty()) tz = "America/Los_Angeles";

        if (manager.isEmpty() || fromStr.isEmpty() || toStr.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "managerUsername, fromDate and toDate are required.");
            return resp;
        }

        LocalDate fromDate, toDate;
        ZoneId zone;
        try {
            fromDate = LocalDate.parse(fromStr);
            toDate = LocalDate.parse(toStr);
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "Invalid date or timezone: " + e.getMessage());
            return resp;
        }
        // Spec: toDate is exclusive at midnight. UI gives an inclusive end date — bump by 1 day.
        LocalDate toDateExclusive = toDate.plusDays(1);
        if (!fromDate.isBefore(toDateExclusive)) {
            resp.put("success", false);
            resp.put("message", "End Date must be on or after Begin Date.");
            return resp;
        }

        // === Resolve reports ===
        List<LdapAuthService.ReportingUser> reports = ldapAuthService.findDirectReports(manager, transitive);
        List<Map<String, Object>> reportSummaries = new ArrayList<>();
        List<String> loginIds = new ArrayList<>();
        List<String> warningsBeforeQuery = new ArrayList<>();

        if (includeManager) {
            String managerEmpId = ldapAuthService.lookupEmployeeIdByUsername(manager);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("samAccountName", manager);
            entry.put("displayName", manager + " (manager)");
            entry.put("employeeId", managerEmpId);
            entry.put("isManager", true);
            reportSummaries.add(entry);
            if (managerEmpId != null && !managerEmpId.isEmpty()) loginIds.add(managerEmpId);
            else warningsBeforeQuery.add("Manager has no employeeID in AD (skipped from query): " + manager);
        }
        for (LdapAuthService.ReportingUser r : reports) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("samAccountName", r.samAccountName);
            entry.put("displayName", r.displayName);
            entry.put("email", r.email);
            entry.put("employeeId", r.employeeId);
            entry.put("isManager", false);
            reportSummaries.add(entry);
            if (r.employeeId != null && !r.employeeId.isEmpty()) loginIds.add(r.employeeId);
            else warningsBeforeQuery.add("Report has no employeeID in AD (skipped): " + r.samAccountName);
        }

        if (loginIds.isEmpty()) {
            resp.put("success", true);
            resp.put("manager", manager);
            resp.put("transitive", transitive);
            resp.put("includeManager", includeManager);
            resp.put("reports", reportSummaries);
            resp.put("warnings", warningsBeforeQuery);
            resp.put("rows", new ArrayList<>());
            resp.put("summary", emptySummary());
            resp.put("message", reports.isEmpty()
                ? "No direct reports found in AD for this manager."
                : "Reports were found, but none have an employeeID in AD — nothing to query.");
            return resp;
        }

        // === Run change-search ===
        try {
            ChangeSearchService.Result result = changeSearchService.search(loginIds, fromDate, toDateExclusive, zone);

            List<String> allWarnings = new ArrayList<>(warningsBeforeQuery);
            allWarnings.addAll(result.warnings);

            // Resolved-user list as plain maps for JSON friendliness
            List<Map<String, Object>> resolved = new ArrayList<>();
            for (ChangeSearchService.ResolvedUser u : result.resolvedUsers) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("loginId", u.loginId);
                m.put("agileUserId", u.agileUserId);
                m.put("displayName", u.displayName);
                resolved.add(m);
            }

            resp.put("success", true);
            resp.put("manager", manager);
            resp.put("transitive", transitive);
            resp.put("includeManager", includeManager);
            resp.put("reports", reportSummaries);
            resp.put("resolvedUsers", resolved);
            resp.put("warnings", allWarnings);
            resp.put("summary", result.summary);
            resp.put("rows", result.rows);
            resp.put("criteria", buildCriteria(fromDate, toDate, zone));
            // Cache for /export so the user doesn't pay the SQL cost twice when
            // they click Run → Excel. Keyed by criteria so the export endpoint
            // can detect a stale cache and re-query.
            session.setAttribute(SESSION_CACHE_KEY, new CachedRun(
                cacheKey(manager, fromStr, toStr, transitive, includeManager),
                reportSummaries, allWarnings, result.summary, result.rows));
            return resp;
        } catch (IllegalArgumentException ex) {
            resp.put("success", false);
            resp.put("message", ex.getMessage());
            return resp;
        } catch (Exception ex) {
            logger.warning("[VOLUME-REPORT] Query failed: " + ex.getMessage());
            resp.put("success", false);
            resp.put("message", "Query failed: " + ex.getMessage());
            return resp;
        }
    }

    /** XLSX export — uses cached /run result when available; re-queries only if cache is missing/stale. */
    @PostMapping("/export")
    public void export(@RequestBody Map<String, Object> body, HttpSession session,
                       HttpServletResponse response) throws IOException {
        if (session.getAttribute("username") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }

        String manager = strOf(body.get("managerUsername"));
        String fromStr = strOf(body.get("fromDate"));
        String toStr = strOf(body.get("toDate"));
        boolean transitive = Boolean.TRUE.equals(body.get("transitive"));
        boolean includeManager = Boolean.TRUE.equals(body.get("includeManager"));
        String wantedKey = cacheKey(manager, fromStr, toStr, transitive, includeManager);

        List<Map<String, Object>> rows;
        Map<String, Object> summary;
        List<Map<String, Object>> reports;

        Object cachedObj = session.getAttribute(SESSION_CACHE_KEY);
        if (cachedObj instanceof CachedRun && wantedKey.equals(((CachedRun) cachedObj).key)) {
            CachedRun cached = (CachedRun) cachedObj;
            rows = cached.rows;
            summary = cached.summary;
            reports = cached.reports;
            logger.info("[VOLUME-REPORT] Export served from session cache: " + rows.size() + " rows");
        } else {
            // No cache hit (different inputs, expired session, or first call without /run)
            // — fall back to re-running the full /run flow synchronously.
            Map<String, Object> result = run(body, session);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    String.valueOf(result.getOrDefault("message", "Export failed")));
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> r1 = (List<Map<String, Object>>) result.getOrDefault("rows", new ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> s1 = (Map<String, Object>) result.getOrDefault("summary", new LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> p1 = (List<Map<String, Object>>) result.getOrDefault("reports", new ArrayList<>());
            rows = r1;
            summary = s1;
            reports = p1;
            logger.info("[VOLUME-REPORT] Export ran fresh query (no cache hit): " + rows.size() + " rows");
        }

        byte[] xlsx = buildXlsx(rows, summary, reports, manager, fromStr, toStr);

        String filename = "volume-report_" + sanitize(manager) + "_" + fromStr + "_to_" + toStr + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setContentLength(xlsx.length);
        response.getOutputStream().write(xlsx);
        response.getOutputStream().flush();
    }

    /**
     * Public entry-point used by TeamReportController to obtain the same XLSX bytes
     * the /export endpoint streams to the browser. Reuses session cache when the
     * caller passes the same criteria they last ran; otherwise re-runs the query.
     */
    public byte[] generateVolumeXlsxBytes(Map<String, Object> body, HttpSession session) throws IOException {
        String manager = strOf(body.get("managerUsername"));
        String fromStr = strOf(body.get("fromDate"));
        String toStr = strOf(body.get("toDate"));
        boolean transitive = Boolean.TRUE.equals(body.get("transitive"));
        boolean includeManager = Boolean.TRUE.equals(body.get("includeManager"));
        String wantedKey = cacheKey(manager, fromStr, toStr, transitive, includeManager);

        List<Map<String, Object>> rows;
        Map<String, Object> summary;
        List<Map<String, Object>> reports;

        Object cachedObj = session.getAttribute(SESSION_CACHE_KEY);
        if (cachedObj instanceof CachedRun && wantedKey.equals(((CachedRun) cachedObj).key)) {
            CachedRun cached = (CachedRun) cachedObj;
            rows = cached.rows;
            summary = cached.summary;
            reports = cached.reports;
        } else {
            Map<String, Object> result = run(body, session);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                throw new IOException("Volume report run failed: " + result.getOrDefault("message", "(no message)"));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> r1 = (List<Map<String, Object>>) result.getOrDefault("rows", new ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> s1 = (Map<String, Object>) result.getOrDefault("summary", new LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> p1 = (List<Map<String, Object>>) result.getOrDefault("reports", new ArrayList<>());
            rows = r1;
            summary = s1;
            reports = p1;
        }
        return buildXlsx(rows, summary, reports, manager, fromStr, toStr);
    }

    private byte[] buildXlsx(List<Map<String, Object>> rows, Map<String, Object> summary,
                             List<Map<String, Object>> reports,
                             String manager, String fromDate, String toDate) throws IOException {
        // SXSSF with inline strings (no shared-strings table) — same pattern as the
        // recent OOM-prevention sweep on the rest of the app's Excel writers.
        try (SXSSFWorkbook wb = new SXSSFWorkbook(null, 200, false, false)) {
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);

            // ----- Sheet 1: Summary -----
            SXSSFSheet sumSheet = wb.createSheet("Summary");
            int sr = 0;
            sumSheet.createRow(sr++).createCell(0).setCellValue("Volume Report");
            row2(sumSheet, sr++, "Manager", manager);
            row2(sumSheet, sr++, "Window", fromDate + " to " + toDate);
            row2(sumSheet, sr++, "Total rows", numStr(summary.get("totalRows")));
            row2(sumSheet, sr++, "Distinct changes", numStr(summary.get("distinctChanges")));
            sr++;
            sumSheet.createRow(sr++).createCell(0).setCellValue("By Change Type");
            @SuppressWarnings("unchecked")
            Map<String, Object> byType = (Map<String, Object>) summary.getOrDefault("byChangeType", new LinkedHashMap<>());
            for (Map.Entry<String, Object> e : byType.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) e.getValue();
                Row r = sumSheet.createRow(sr++);
                r.createCell(0).setCellValue(e.getKey());
                r.createCell(1).setCellValue(numStr(bucket.get("rows")) + " rows");
                r.createCell(2).setCellValue(numStr(bucket.get("distinct")) + " distinct");
            }
            sr++;
            sumSheet.createRow(sr++).createCell(0).setCellValue("By Status");
            @SuppressWarnings("unchecked")
            Map<String, Object> byStatus = (Map<String, Object>) summary.getOrDefault("byStatus", new LinkedHashMap<>());
            for (Map.Entry<String, Object> e : byStatus.entrySet()) {
                Row r = sumSheet.createRow(sr++);
                r.createCell(0).setCellValue(e.getKey());
                r.createCell(1).setCellValue(numStr(e.getValue()));
            }
            sumSheet.setColumnWidth(0, 30 * 256);
            sumSheet.setColumnWidth(1, 22 * 256);
            sumSheet.setColumnWidth(2, 22 * 256);

            // ----- Sheet 2: People queried -----
            SXSSFSheet pSheet = wb.createSheet("People Queried");
            String[] pHeaders = {"sAMAccountName", "Display Name", "Email", "Employee ID", "Is Manager"};
            Row pHead = pSheet.createRow(0);
            for (int i = 0; i < pHeaders.length; i++) {
                Cell c = pHead.createCell(i);
                c.setCellValue(pHeaders[i]);
                c.setCellStyle(headerStyle);
            }
            for (int i = 0; i < reports.size(); i++) {
                Map<String, Object> rep = reports.get(i);
                Row r = pSheet.createRow(i + 1);
                r.createCell(0).setCellValue(strOf(rep.get("samAccountName")));
                r.createCell(1).setCellValue(strOf(rep.get("displayName")));
                r.createCell(2).setCellValue(strOf(rep.get("email")));
                r.createCell(3).setCellValue(strOf(rep.get("employeeId")));
                r.createCell(4).setCellValue(Boolean.TRUE.equals(rep.get("isManager")) ? "Y" : "");
            }
            int[] pWidths = {18, 30, 32, 14, 12};
            for (int i = 0; i < pWidths.length; i++) pSheet.setColumnWidth(i, pWidths[i] * 256);

            // ----- Sheet 3: Changes (the main detail) -----
            SXSSFSheet sheet = wb.createSheet("Changes");
            String[] headers = {
                "Number", "Description", "Status", "Workflow", "Change Type",
                "Product Line(s)", "Priority", "Item Number (Affected Items)",
                "Create User", "Date Originated", "Date Released",
                "Requestor", "Analyst", "Classification"
            };
            Row hr = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(strOf(row.get("number")));
                r.createCell(1).setCellValue(strOf(row.get("description")));
                r.createCell(2).setCellValue(strOf(row.get("status")));
                r.createCell(3).setCellValue(strOf(row.get("workflow")));
                r.createCell(4).setCellValue(strOf(row.get("changeType")));
                r.createCell(5).setCellValue(strOf(row.get("productLines")));
                r.createCell(6).setCellValue(strOf(row.get("priority")));
                r.createCell(7).setCellValue(strOf(row.get("itemNumberAffected")));
                r.createCell(8).setCellValue(strOf(row.get("createUser")));
                r.createCell(9).setCellValue(strOf(row.get("dateOriginated")));
                r.createCell(10).setCellValue(strOf(row.get("dateReleased")));
                r.createCell(11).setCellValue(strOf(row.get("requestor")));
                r.createCell(12).setCellValue(strOf(row.get("analyst")));
                r.createCell(13).setCellValue(strOf(row.get("classification")));
            }
            int[] widths = {18, 50, 16, 24, 12, 28, 12, 22, 28, 24, 24, 28, 28, 36};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            sheet.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            wb.dispose(); // delete temp files
            return out.toByteArray();
        }
    }

    private static void row2(SXSSFSheet sheet, int rowIdx, String k, String v) {
        Row r = sheet.createRow(rowIdx);
        r.createCell(0).setCellValue(k);
        r.createCell(1).setCellValue(v);
    }

    private static String numStr(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "manager";
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String strOf(Object v) { return v == null ? "" : v.toString().trim(); }

    private static Map<String, Object> emptySummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalRows", 0);
        s.put("distinctChanges", 0);
        s.put("byChangeType", new LinkedHashMap<>());
        s.put("byStatus", new LinkedHashMap<>());
        return s;
    }

    private static Map<String, Object> buildCriteria(LocalDate from, LocalDate to, ZoneId zone) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("fromDate", from.toString());
        c.put("toDate", to.toString()); // user-facing inclusive end
        c.put("timezone", zone.getId());
        return c;
    }
}
