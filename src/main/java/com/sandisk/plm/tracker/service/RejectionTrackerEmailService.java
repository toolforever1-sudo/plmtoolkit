package com.sandisk.plm.tracker.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Renders + sends the Returns Tracker leadership email and answers on-demand
 * "explain this event" requests for the drill-down panel.
 *
 * HTML rendering follows the email design tokens in {@code CLAUDE.md}:
 *   - Outlook-safe table layout, max-width 600px, IBM Plex Sans/Serif/Mono
 *   - Nav header → hero → narrative → anomalies → KPI tiles → tables → footer
 *   - Dark-mode-aware (color-scheme meta + prefers-color-scheme media block)
 *
 * Both the manual "Email this view" button and the scheduled weekly/monthly
 * jobs route through {@link #sendForWindow}.
 */
@Service
public class RejectionTrackerEmailService {

    private static final Logger logger = Logger.getLogger(RejectionTrackerEmailService.class.getName());

    @Autowired private RejectionTrackerService rejectionService;
    @Autowired private EcnReportService ecnReportService;
    @Autowired private PortkeyClient portkeyClient;
    @Autowired private MemoryGuard memoryGuard;

    /**
     * Hard upper bound on events per email. Today's volumes are 200–2000; this
     * cap is set well above that to catch a runaway aggregation (and also to
     * keep us out of the SST O(N²) territory that crashed prod on May 6 with
     * an unrelated 11k-record export). Above this we still send the email but
     * skip the chart sheets so the workbook stays bounded; data sheet content
     * is preserved.
     */
    private static final int CHART_SAFE_EVENT_LIMIT = 5000;

    @Value("${mail.smtp.host}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from}") private String mailFrom;

    @Value("${portkey.enabled:true}") private boolean portkeyEnabled;
    @Value("${portkey.api-key:}") private String portkeyApiKey;
    @Value("${portkey.provider:@anthropic-eastus2}") private String portkeyProvider;
    @Value("${portkey.model:claude-sonnet-4-6}") private String portkeyModel;
    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}") private String portkeyBaseUrl;

    /**
     * Render + send the email for a date window.
     *
     * @param from               window start (inclusive)
     * @param to                 window end (inclusive)
     * @param overrideRecipients if non-null, send to this list instead of the configured one
     * @param subjectSuffix      appended to the subject (e.g. " (ad-hoc)")
     * @return number of recipients the email was sent to
     */
    public int sendForWindow(LocalDate from, LocalDate to, List<String> overrideRecipients,
                              String subjectSuffix) throws MessagingException {
        List<Map<String, Object>> events = rejectionService.getEventsInRange(from, to);
        Map<String, Object> aggregates = rejectionService.getAggregates(events);
        String windowKey = RejectionTrackerService.windowKeyForRange(from, to);
        Map<String, Object> narrative = rejectionService.getNarrative(windowKey);

        String html = renderHtml(from, to, events, aggregates, narrative);
        byte[] excelBytes;
        try {
            excelBytes = buildExcel(events, ecnReportService.getEcnLookup());
        } catch (IOException e) {
            logger.warning("[RETURNS-EMAIL] Excel build failed, sending without attachment: " + e.getMessage());
            excelBytes = null;
        }
        String excelFilename = "ECN_Return_to_Pending_" + from + "_to_" + to + ".xlsx";

        String subject = buildSubject(from, to, subjectSuffix);
        List<String> recipients = overrideRecipients != null && !overrideRecipients.isEmpty()
            ? overrideRecipients
            : rejectionService.getRecipients();

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);

        int count = 0;
        for (String r : recipients) {
            if (r == null || r.trim().isEmpty()) continue;
            MimeMessage msg = new MimeMessage(mailSession);
            msg.setFrom(new InternetAddress(mailFrom));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(r.trim()));
            msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));
            if (excelBytes != null) {
                MimeMultipart multipart = new MimeMultipart("mixed");
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=utf-8");
                multipart.addBodyPart(htmlPart);
                MimeBodyPart attach = new MimeBodyPart();
                DataSource ds = new ByteArrayDataSource(excelBytes,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                attach.setDataHandler(new DataHandler(ds));
                attach.setFileName(excelFilename);
                multipart.addBodyPart(attach);
                msg.setContent(multipart);
            } else {
                msg.setContent(html, "text/html; charset=utf-8");
            }
            Transport.send(msg);
            count++;
        }
        logger.info("[RETURNS-EMAIL] Sent to " + count + " recipients (subject: " + subject
            + (excelBytes != null ? "; xlsx attached " + excelBytes.length + "b" : "; NO attachment") + ")");
        return count;
    }

    /**
     * Build the Excel byte[] for download or email attachment.
     *
     * Schema matches Noraida's "ECN Pullback &amp; Rejection Comments - Report.xlsx"
     * (the "Report" sheet) so leadership can drop our output into their existing
     * tracking workflow.
     *
     * Two columns are intentionally left blank for analyst use:
     *   - Manual Comment Summary (col 6)
     *   - Manual Category (col 8) — the AI category goes in col 7; manual override here
     */
    public byte[] buildExcel(List<Map<String, Object>> events,
                              Map<String, Map<String, Object>> ecnLookup) throws IOException {
        if (ecnLookup == null) ecnLookup = Collections.emptyMap();

        // Heap-pressure circuit breaker. This service still uses in-memory
        // XSSFWorkbook (because POI's streaming SXSSF doesn't support chart
        // sheets, and the chart sheets are part of the requested output).
        // If the JVM is already at threshold, the SST allocations from the
        // data sheet plus the chart-rendering metadata can push us over —
        // refuse rather than crash. Caller (sendForWindow) catches and logs.
        if (memoryGuard != null && memoryGuard.isUnderPressure()) {
            throw new IllegalStateException(
                "Returns Tracker email skipped: heap is " + memoryGuard.usedPercent()
                + "% full (threshold " + Math.round(memoryGuard.getThreshold() * 100)
                + "%). Workbook build would risk OOM. Email will retry on the next scheduled run.");
        }

        // For abnormally large event sets, drop the chart sheets to keep the
        // SST allocations bounded. Data sheet (Report) is preserved — the
        // recipient still gets the row-level detail, just without the chart
        // visualizations on top.
        boolean skipCharts = events.size() > CHART_SAFE_EVENT_LIMIT;
        if (skipCharts) {
            logger.warning("[RT-EMAIL] " + events.size() + " events exceeds chart-safe limit ("
                + CHART_SAFE_EVENT_LIMIT + ") — building Excel without chart sheets to avoid OOM.");
        }

        XSSFWorkbook wb = new XSSFWorkbook();

        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // Top-aligned (no wrap) — keeps rows uniform-height like Noraida's reference file.
        // Long text is truncated visually but the full string is in the cell so users
        // can click + read in the formula bar, or enable wrap themselves on demand.
        CellStyle wrapStyle = wb.createCellStyle();
        wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

        Sheet sheet = wb.createSheet("Report");
        sheet.createFreezePane(0, 1);

        String[] headers = {
            "ECN#", "ECN# (Grouped)", "User", "Requestor vs Analyst Return",
            "User Comment", "Manual Comment Summary", "AI Comment Category",
            "Manual Category", "Action", "Comment Date", "Proposal", "Status",
            "Product Line(s)", "Product Line/Program Name", "Analyst", "Requestor",
            "Category", "Request Classification", "Date Originated", "Submit Date", "Date Released",
            "Audit Category", "Classification Source"
        };
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hr.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }
        // Column widths roughly matching Noraida's layout
        int[] widths = {16, 16, 28, 18, 50, 24, 22, 18, 18, 18, 50, 12, 28, 32, 26, 26, 14, 32, 14, 14, 14, 22, 18};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }

        int rowIdx = 1;
        for (Map<String, Object> e : events) {
            String ecnNum = strOf(e.get("ecnNumber"));
            Map<String, Object> ecn = ecnLookup.getOrDefault(ecnNum, Collections.emptyMap());
            String rejectedBy = strOf(e.get("rejectedBy"));
            String requestor = strOf(e.get("requestor"));
            String analyst = strOf(ecn.get("analyst"));
            String userType = classifyUserType(rejectedBy, requestor, analyst);

            String fromStatus = strOf(e.get("fromStatus"));
            String action = !fromStatus.isEmpty() ? fromStatus + "=>Pending" : "";
            String ts = strOf(e.get("ts")).replace("T", " ");

            Row r = sheet.createRow(rowIdx++);
            setCell(r, 0,  ecnNum);
            setCell(r, 1,  ecnNum);
            setCell(r, 2,  rejectedBy);
            setCell(r, 3,  userType);
            setCell(r, 4,  strOf(e.get("comment")), wrapStyle);
            // 5 Manual Comment Summary — blank
            setCell(r, 6,  strOf(e.get("aiCategory")));
            // 7 Manual Category — blank
            setCell(r, 8,  action);
            setCell(r, 9,  ts);
            setCell(r, 10, strOf(e.get("description")), wrapStyle);
            setCell(r, 11, strOf(ecn.getOrDefault("status", e.get("currentStatus"))));
            setCell(r, 12, strOf(e.get("productLine")));
            // 13 Product Line/Program Name — same as productLine if not separately captured
            setCell(r, 13, strOf(e.get("productLine")));
            setCell(r, 14, analyst);
            setCell(r, 15, requestor);
            setCell(r, 16, strOf(ecn.get("priority")));
            setCell(r, 17, strOf(ecn.get("requestClassification")));
            // 18 Date Originated — not currently in either source; leave blank
            setCell(r, 19, strOf(ecn.get("createdDate")));
            setCell(r, 20, strOf(ecn.get("completedDate")));
            setCell(r, 21, strOf(e.get("auditCategory")));
            setCell(r, 22, strOf(e.get("categorySource")));
        }

        sheet.setAutoFilter(new CellRangeAddress(
            0, Math.max(rowIdx - 1, 1), 0, headers.length - 1));

        // Additional sheets to mirror Noraida's workbook structure. Skipped
        // when event count exceeds CHART_SAFE_EVENT_LIMIT — see skipCharts
        // computation above.
        if (!skipCharts) {
            addStatsSheet(wb, events, headerStyle);
            addCategoriesSheet(wb, events, headerStyle);
            addTrendSheet(wb, events, headerStyle);
            addThemesSheet(wb, events, headerStyle);
            addProductLinesSheet(wb, events, headerStyle);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();
        return baos.toByteArray();
    }

    // =========================================================================
    // Multi-sheet helpers (matches Noraida's workbook layout + adds visual sheets)
    // =========================================================================

    /**
     * Top requestor counts + bar chart. Counts how often each requestor's ECN
     * was pulled back to Pending, excluding self-returns (where the requestor
     * is the one pushing it back) — those are tracked separately in the
     * "Returned By Requestor" category and would otherwise inflate the chart.
     *
     * Columns: A Requestor | B Count of ECN# (events) | C Count of Unique ECN#
     * | D ECN # (Reviewer who pushed back) | E ECN # (Product Line).
     * One ECN can be pulled back multiple times, so B and C diverge — C is the
     * distinct-ECN count, B is the total event count. D drills into the actual
     * ECN/reviewer pairs so the user can see who is doing the pushing back
     * without needing a pivot. E pairs each ECN with its product line so the
     * requestor's portfolio is visible at a glance — same ECN ordering as D
     * for easy side-by-side reading.
     */
    private void addStatsSheet(XSSFWorkbook wb, List<Map<String, Object>> events, CellStyle headerStyle) {
        Map<String, Integer> counts = new HashMap<>();
        // requestor -> ecnNumber -> ordered set of reviewer display names
        Map<String, Map<String, Set<String>>> ecnByRequestor = new HashMap<>();
        // requestor -> ecnNumber -> ordered set of product lines (usually 1)
        Map<String, Map<String, Set<String>>> productLineByRequestor = new HashMap<>();
        int excluded = 0;
        for (Map<String, Object> e : events) {
            String requestor = stripId(strOf(e.get("requestor")));
            String rejectedByRaw = strOf(e.get("rejectedBy"));
            String rejectedBy = stripId(rejectedByRaw);
            if (requestor.isEmpty()) continue;
            if (!rejectedBy.isEmpty() && rejectedBy.equalsIgnoreCase(requestor)) {
                excluded++;
                continue;
            }
            counts.merge(requestor, 1, Integer::sum);

            String ecn = strOf(e.get("ecnNumber")).trim();
            if (ecn.isEmpty()) continue;
            String reviewer = nameFirstLast(rejectedByRaw);
            ecnByRequestor
                .computeIfAbsent(requestor, k -> new LinkedHashMap<>())
                .computeIfAbsent(ecn, k -> new LinkedHashSet<>())
                .add(reviewer);

            String productLine = strOf(e.get("productLine")).trim();
            if (!productLine.isEmpty()) {
                productLineByRequestor
                    .computeIfAbsent(requestor, k -> new LinkedHashMap<>())
                    .computeIfAbsent(ecn, k -> new LinkedHashSet<>())
                    .add(productLine);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int top = Math.min(30, sorted.size());

        XSSFSheet sheet = wb.createSheet("Stats");
        sheet.setColumnWidth(0, 36 * 256);
        sheet.setColumnWidth(1, 16 * 256);
        sheet.setColumnWidth(2, 22 * 256);
        sheet.setColumnWidth(3, 80 * 256);
        sheet.setColumnWidth(4, 60 * 256);

        CellStyle wrapStyle = wb.createCellStyle();
        wrapStyle.setWrapText(true);
        wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

        Row hr = sheet.createRow(0);
        Cell h0 = hr.createCell(0); h0.setCellValue("Requestor"); h0.setCellStyle(headerStyle);
        Cell h1 = hr.createCell(1); h1.setCellValue("Count of ECN#"); h1.setCellStyle(headerStyle);
        Cell h2 = hr.createCell(2); h2.setCellValue("Count of Unique ECN#"); h2.setCellStyle(headerStyle);
        Cell h3 = hr.createCell(3); h3.setCellValue("ECN # (Reviewer who pushed back)"); h3.setCellStyle(headerStyle);
        Cell h4 = hr.createCell(4); h4.setCellValue("ECN # (Product Line)"); h4.setCellStyle(headerStyle);

        for (int i = 0; i < top; i++) {
            String requestor = sorted.get(i).getKey();
            Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(requestor);
            r.createCell(1).setCellValue(sorted.get(i).getValue());

            Map<String, Set<String>> ecns = ecnByRequestor.getOrDefault(requestor, Collections.emptyMap());
            r.createCell(2).setCellValue(ecns.size());

            StringBuilder ecnList = new StringBuilder();
            for (Map.Entry<String, Set<String>> ecnEntry : ecns.entrySet()) {
                if (ecnList.length() > 0) ecnList.append(", ");
                ecnList.append(ecnEntry.getKey()).append('(');
                boolean first = true;
                for (String reviewer : ecnEntry.getValue()) {
                    if (!first) ecnList.append(", ");
                    ecnList.append(reviewer);
                    first = false;
                }
                ecnList.append(')');
            }
            Cell c3 = r.createCell(3);
            c3.setCellValue(ecnList.toString());
            c3.setCellStyle(wrapStyle);

            // Column E: same ECN ordering as column D, but with product line
            // in the parens. ECNs with no PL on file render as ECN-id(—).
            Map<String, Set<String>> pls = productLineByRequestor.getOrDefault(requestor, Collections.emptyMap());
            StringBuilder plList = new StringBuilder();
            for (Map.Entry<String, Set<String>> ecnEntry : ecns.entrySet()) {
                if (plList.length() > 0) plList.append(", ");
                String ecn = ecnEntry.getKey();
                plList.append(ecn).append('(');
                Set<String> ecnPls = pls.getOrDefault(ecn, Collections.emptySet());
                if (ecnPls.isEmpty()) {
                    plList.append('\u2014');
                } else {
                    boolean firstPl = true;
                    for (String pl : ecnPls) {
                        if (!firstPl) plList.append(", ");
                        plList.append(pl);
                        firstPl = false;
                    }
                }
                plList.append(')');
            }
            Cell c4 = r.createCell(4);
            c4.setCellValue(plList.toString());
            c4.setCellStyle(wrapStyle);
        }

        Row note = sheet.createRow(top + 2);
        note.createCell(0).setCellValue(
            "Self-returns excluded from this view: " + excluded
            + " (requestor pushed their own ECN back — see 'Returned By Requestor' category)");

        if (top == 0) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        // Anchor starts at col 5 (after the new "ECN # (Product Line)" column at col 4).
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 5, 0, 20, Math.max(top + 2, 12));
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("ECN Return Frequency by Requestor (Top " + top + ") — self-returns excluded");
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle("Requestor");
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle("Times pulled back");

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sheet,
            new CellRangeAddress(1, top, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sheet,
            new CellRangeAddress(1, top, 1, 1));

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        data.setBarDirection(BarDirection.BAR);
        XDDFChartData.Series series = data.addSeries(cats, vals);
        series.setTitle("Returns", null);
        chart.plot(data);
    }

    /** Static taxonomy reference — mirrors her Categories tab. */
    private void addCategoriesSheet(XSSFWorkbook wb, List<Map<String, Object>> events, CellStyle headerStyle) {
        XSSFSheet sheet = wb.createSheet("Categories");
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 80 * 256);
        sheet.setColumnWidth(2, 12 * 256);
        sheet.setColumnWidth(3, 14 * 256);

        Row hr = sheet.createRow(0);
        String[] headers = {"Return Category", "Description", "Count (window)", "% of Total"};
        for (int i = 0; i < headers.length; i++) {
            Cell c = hr.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(headerStyle);
        }

        String[][] taxonomy = {
            {"Returned by Owner", "ECN returned to Pending by its creator while @Submit (tracked as FYI; auto-interpreted)."},
            {"Incomplete Documentation", "Could not advance due to missing required documentation (MDDS, specs, etc.). Code ID:"},
            {"Insufficient Information", "Could not advance due to unclear/missing/incomplete required information. Code II:"},
            {"Wrong Information", "Provided information does not support the change or conflicts with process requirements. Code WI:"},
            {"Duplicate Request", "Duplicates an existing request already addressed under a different ECN. Code DR:"},
            {"Return Requested", "Analyst returned the ECN at the requestor's request. Code RR:"},
            {"No audit code", "Return comment carried no audit reason-code prefix (pre-audit or non-compliant)."}
        };
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> e : events) {
            counts.merge(strOf(e.get("auditCategory")), 1, Integer::sum);
        }
        int total = events.size();
        for (int i = 0; i < taxonomy.length; i++) {
            Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(taxonomy[i][0]);
            r.createCell(1).setCellValue(taxonomy[i][1]);
            int c = counts.getOrDefault(taxonomy[i][0], 0);
            r.createCell(2).setCellValue(c);
            Cell pctCell = r.createCell(3);
            pctCell.setCellValue(total > 0 ? (double) c / total : 0);
            CellStyle pctStyle = wb.createCellStyle();
            pctStyle.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            pctCell.setCellStyle(pctStyle);
        }

        // Doughnut chart visualizing share
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 5, 0, 14, 14);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Return to Pending Categories — share of total");
        chart.setTitleOverlay(false);
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.RIGHT);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sheet,
            new CellRangeAddress(1, taxonomy.length, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sheet,
            new CellRangeAddress(1, taxonomy.length, 2, 2));
        XDDFChartData data = chart.createData(ChartTypes.DOUGHNUT, null, null);
        data.addSeries(cats, vals);
        chart.plot(data);
    }

    /** Daily return-to-pending counts + bar chart. */
    private void addTrendSheet(XSSFWorkbook wb, List<Map<String, Object>> events, CellStyle headerStyle) {
        TreeMap<String, Integer> daily = new TreeMap<>();
        for (Map<String, Object> e : events) {
            String ts = strOf(e.get("ts"));
            if (ts.length() >= 10) daily.merge(ts.substring(0, 10), 1, Integer::sum);
        }
        XSSFSheet sheet = wb.createSheet("Trend");
        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 18 * 256);
        Row hr = sheet.createRow(0);
        Cell h0 = hr.createCell(0); h0.setCellValue("Date"); h0.setCellStyle(headerStyle);
        Cell h1 = hr.createCell(1); h1.setCellValue("Returns to Pending"); h1.setCellStyle(headerStyle);

        int row = 1;
        for (Map.Entry<String, Integer> e : daily.entrySet()) {
            Row r = sheet.createRow(row++);
            r.createCell(0).setCellValue(e.getKey());
            r.createCell(1).setCellValue(e.getValue());
        }
        if (daily.isEmpty()) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 18, Math.max(row + 1, 16));
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Daily ECN Return-to-Pending Trend");
        chart.setTitleOverlay(false);
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);
        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle("Date");
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle("Return-to-Pending count");
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sheet,
            new CellRangeAddress(1, row - 1, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sheet,
            new CellRangeAddress(1, row - 1, 1, 1));
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        data.setBarDirection(BarDirection.COL);
        data.addSeries(cats, vals).setTitle("Returns to Pending", null);
        chart.plot(data);
    }

    /** AI-discovered themes table + bar chart. */
    private void addThemesSheet(XSSFWorkbook wb, List<Map<String, Object>> events, CellStyle headerStyle) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> e : events) {
            String t = strOf(e.get("theme"));
            if (!t.isEmpty()) counts.merge(t, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int top = Math.min(30, sorted.size());

        XSSFSheet sheet = wb.createSheet("Top Themes");
        sheet.setColumnWidth(0, 60 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        Row hr = sheet.createRow(0);
        Cell h0 = hr.createCell(0); h0.setCellValue("Theme (AI)"); h0.setCellStyle(headerStyle);
        Cell h1 = hr.createCell(1); h1.setCellValue("Count"); h1.setCellStyle(headerStyle);
        for (int i = 0; i < top; i++) {
            Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(sorted.get(i).getKey());
            r.createCell(1).setCellValue(sorted.get(i).getValue());
        }
        if (top == 0) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 18, Math.max(top + 2, 16));
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Top AI-Discovered Themes");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        XDDFCategoryAxis ba = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis la = chart.createValueAxis(AxisPosition.LEFT);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sheet,
            new CellRangeAddress(1, top, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sheet,
            new CellRangeAddress(1, top, 1, 1));
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, ba, la);
        data.setBarDirection(BarDirection.BAR);
        data.addSeries(cats, vals).setTitle("Count", null);
        chart.plot(data);
    }

    /**
     * Top product teams table + bar chart. (Was "Top Product Lines" before
     * May 6, 2026 — Jimmy asked for grouping by the per-ECN team override
     * to match the ECN Report KPI's existing breakdown.) Team is resolved
     * via {@link EcnReportService#getAnnotations()} keyed by ecnNumber;
     * ECNs without an explicit team override bucket as "Unknown".
     */
    @SuppressWarnings("unchecked")
    private void addProductLinesSheet(XSSFWorkbook wb, List<Map<String, Object>> events, CellStyle headerStyle) {
        Map<String, Object> annotations = ecnReportService != null
            ? ecnReportService.getAnnotations() : Collections.emptyMap();
        // PT-90: align with the Cycle Time tab's team logic. Fall through to
        // ecn_data.json's productTeam (which Python's TEAM_MAP resolves from
        // Product Line) instead of dumping the raw first-Product-Line string
        // into the team column. Order: teamOverride → ecnLookup.productTeam → Unknown.
        Map<String, Map<String, Object>> ecnLookup = ecnReportService != null
            ? ecnReportService.getEcnLookup() : Collections.emptyMap();
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> e : events) {
            String ecnNum = strOf(e.get("ecnNumber"));
            String team = null;
            if (!ecnNum.isEmpty()) {
                Object ann = annotations.get(ecnNum);
                if (ann instanceof Map) {
                    Object t = ((Map<String, Object>) ann).get("teamOverride");
                    if (t != null) {
                        String s = t.toString().trim();
                        if (!s.isEmpty()) team = s;
                    }
                }
            }
            if (team == null && !ecnNum.isEmpty()) {
                Map<String, Object> row = ecnLookup.get(ecnNum);
                if (row != null) {
                    Object pt = row.get("productTeam");
                    if (pt != null) {
                        String s = pt.toString().trim();
                        if (!s.isEmpty()) team = s;
                    }
                }
            }
            // PT-94: third fallback — resolve from the event's Product Line via
            // the static PRODUCT_LINE_TEAM_MAP, so very fresh ECNs (not yet in
            // ecn_data.json) still bucket into the right team.
            if (team == null) {
                String pl = strOf(e.get("productLine"));
                if (!pl.isEmpty()) {
                    java.util.TreeSet<String> teams = new java.util.TreeSet<>();
                    for (String seg : pl.split(";")) {
                        String key = seg.trim();
                        if (key.isEmpty()) continue;
                        String t = RejectionTrackerService.PRODUCT_LINE_TEAM_MAP.get(key);
                        if (t != null) teams.add(t);
                    }
                    if (!teams.isEmpty()) team = String.join("; ", teams);
                }
            }
            if (team == null) team = "Unknown";
            counts.merge(team, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int top = Math.min(20, sorted.size());

        XSSFSheet sheet = wb.createSheet("Product Teams");
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        Row hr = sheet.createRow(0);
        Cell h0 = hr.createCell(0); h0.setCellValue("Product Team"); h0.setCellStyle(headerStyle);
        Cell h1 = hr.createCell(1); h1.setCellValue("Returns to Pending"); h1.setCellStyle(headerStyle);
        for (int i = 0; i < top; i++) {
            Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(sorted.get(i).getKey());
            r.createCell(1).setCellValue(sorted.get(i).getValue());
        }
        if (top == 0) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 18, Math.max(top + 2, 14));
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Returns to Pending by Product Team (Top " + top + ")");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        XDDFCategoryAxis ba = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis la = chart.createValueAxis(AxisPosition.LEFT);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sheet,
            new CellRangeAddress(1, top, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sheet,
            new CellRangeAddress(1, top, 1, 1));
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, ba, la);
        data.setBarDirection(BarDirection.BAR);
        data.addSeries(cats, vals).setTitle("Returns to Pending", null);
        chart.plot(data);
    }

    private void setCell(Row r, int col, String val) { setCell(r, col, val, null); }
    private void setCell(Row r, int col, String val, CellStyle style) {
        Cell c = r.createCell(col);
        c.setCellValue(val == null ? "" : val);
        if (style != null) c.setCellStyle(style);
    }
    private static String strOf(Object o) { return o == null ? "" : o.toString(); }

    /**
     * Match Noraida's "Requestor vs Analyst Return" column logic, extended so the
     * cell is never blank:
     *   - "Analyst" if the rejector is the same person as the ECN's analyst
     *   - "Requestor" if the rejector is the requestor pulling back their own ECN
     *   - the rejector's name (with employeeId stripped) when they're neither —
     *     keeps accountability visible instead of leaving the cell empty
     *
     * Names come in as "LastName, FirstName (employeeId)"; compare on the name part.
     */
    private static String classifyUserType(String rejectedBy, String requestor, String analyst) {
        String r = stripId(rejectedBy);
        String req = stripId(requestor);
        String an = stripId(analyst);
        if (!r.isEmpty() && r.equalsIgnoreCase(an)) return "Analyst";
        if (!r.isEmpty() && r.equalsIgnoreCase(req)) return "Requestor";
        return r;
    }
    private static String stripId(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
    }

    /** "Patel, Reena (12345)" → "Reena Patel". Falls back to stripped name when there's no comma. */
    private static String nameFirstLast(String s) {
        String stripped = stripId(s);
        int comma = stripped.indexOf(',');
        if (comma > 0 && comma < stripped.length() - 1) {
            String last = stripped.substring(0, comma).trim();
            String first = stripped.substring(comma + 1).trim();
            if (!first.isEmpty() && !last.isEmpty()) return first + " " + last;
        }
        return stripped;
    }

    private String buildSubject(LocalDate from, LocalDate to, String suffix) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d");
        String range;
        if (days == 7) {
            range = "Week ending " + to.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        } else if (from.getDayOfMonth() == 1 && to.getDayOfMonth() == to.lengthOfMonth()
                && from.getMonthValue() == to.getMonthValue() && from.getYear() == to.getYear()) {
            range = from.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
        } else {
            range = from.format(fmt) + " – " + to.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        }
        return "ECN Return-to-Pending Summary — " + range + (suffix != null ? suffix : "");
    }

    // =========================================================================
    // HTML rendering — follows email design tokens in CLAUDE.md
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String renderHtml(LocalDate from, LocalDate to,
                                List<Map<String, Object>> events,
                                Map<String, Object> aggregates,
                                Map<String, Object> narrative) {
        int total = (int) aggregates.getOrDefault("totalEvents", 0);
        int uniqueEcns = (int) aggregates.getOrDefault("uniqueEcns", 0);
        Number repeatNum = (Number) aggregates.getOrDefault("repeatRequestors",
                                aggregates.getOrDefault("repeatOffenders", 0L));
        long repeatRequestors = repeatNum.longValue();
        int excludedFromAi = ((Number) aggregates.getOrDefault("excludedFromAi", 0)).intValue();
        Map<String, Integer> categoryCounts = (Map<String, Integer>) aggregates.get("categories");
        List<Map<String, Object>> topRequestors = (List<Map<String, Object>>) aggregates.get("topRequestors");
        List<Map<String, Object>> topThemes = (List<Map<String, Object>>) aggregates.get("topThemes");
        // Per Jimmy (May 6, 2026): show Product Team breakdown instead of
        // Product Line so it matches the ECN Report KPI's existing breakdown.
        // Falls back to topProductLines on older aggregates (back-compat with
        // any cached aggregate that pre-dates the change).
        List<Map<String, Object>> topProductTeams = (List<Map<String, Object>>) aggregates.get("topProductTeams");
        if (topProductTeams == null) {
            topProductTeams = (List<Map<String, Object>>) aggregates.get("topProductLines");
        }
        List<Map<String, Object>> topProductLines = topProductTeams; // local alias to minimise diff below

        // Top category + percent
        String topCat = "—";
        int topCatCount = 0;
        for (Map.Entry<String, Integer> e : categoryCounts.entrySet()) {
            if (e.getValue() > topCatCount) { topCat = e.getKey(); topCatCount = e.getValue(); }
        }
        int topCatPct = total > 0 ? (int) Math.round(100.0 * topCatCount / total) : 0;

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head>")
         .append("<meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"light dark\">")
         .append("<style>")
         .append("body{margin:0;padding:0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;}")
         .append(".email-card{background:#fff;}")
         .append("@media (prefers-color-scheme: dark){")
         .append(".email-body{background:#0F1720!important;}")
         .append(".email-card{background:#1A2330!important;}")
         .append(".email-ink,.email-th{color:#E8E6DF!important;}")
         .append(".email-muted{color:#9AA0AA!important;}")
         .append(".kpi-tile{background:#1A2330!important;border-color:#2C3E50!important;}")
         .append("}")
         .append("</style></head>")
         .append("<body class=\"email-body\" style=\"margin:0;padding:20px 0;background:#FAFAF7;\">")
         .append("<table role=\"presentation\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" width=\"600\" style=\"max-width:600px;width:100%;background:#fff;border:1px solid #E8E6DF;border-radius:8px;\" class=\"email-card\">");

        // Nav header
        h.append("<tr><td style=\"padding:14px 20px;border-bottom:1px solid #E8E6DF;\">")
         .append("<span class=\"email-muted\" style=\"font-size:11px;color:#6B7280;letter-spacing:.04em;\">Agile PLM / ECN Returns Tracker</span>")
         .append("</td></tr>");

        // Hero
        h.append("<tr><td style=\"padding:18px 20px 6px;\">")
         .append("<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;font-weight:600;\">Return-to-Pending Summary</div>")
         .append("<div class=\"email-ink\" style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:bold;color:#0F1720;margin-top:4px;\">")
         .append(esc(buildSubject(from, to, "").replace("ECN Return-to-Pending Summary — ", "")))
         .append("</div>")
         .append("<div class=\"email-muted\" style=\"font-size:13px;color:#6B7280;margin-top:4px;\">")
         .append(total).append(" return-to-pending events across ").append(uniqueEcns).append(" ECNs &middot; ")
         .append(repeatRequestors).append(" repeat requestors (≥3)")
         .append("</div></td></tr>");

        // Excluded-from-AI callout (Returned By Requestor) — surfaced before the AI
        // narrative so the reader knows the AI numbers don't include self-returns.
        if (excludedFromAi > 0) {
            h.append("<tr><td style=\"padding:0 20px 12px;\">")
             .append("<div style=\"background:#e8f0fe;border-left:4px solid #4a6fa5;border-radius:0 6px 6px 0;padding:10px 14px;font-size:13px;color:#0F1720;line-height:1.5;\">")
             .append("<strong>").append(excludedFromAi).append(" ECN")
             .append(excludedFromAi == 1 ? "" : "s").append(" excluded from AI analysis</strong> &mdash; ")
             .append("the requestor pushed their own ECN back to Pending (correcting their own work, ")
             .append("not an analyst return-to-pending). These show up under <em>Returned By Requestor</em> in the ")
             .append("category table but contribute no theme, narrative input, or anomaly signal.")
             .append("</div></td></tr>");
        }

        // Narrative — render markdown bullets as <ul><li> with **bold** runs.
        // Falls back gracefully to paragraphs if the AI returned prose.
        String nar = narrative != null && narrative.get("narrative") instanceof String
            ? (String) narrative.get("narrative") : null;
        if (nar != null && !nar.isEmpty()) {
            h.append("<tr><td style=\"padding:6px 20px 12px;\">")
             .append("<div class=\"email-ink\" style=\"font-size:13px;color:#0F1720;line-height:1.55;background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;padding:12px 14px;\">")
             .append(renderMarkdownBullets(nar))
             .append("</div></td></tr>");
        }

        // Anomalies
        List<Map<String, Object>> anomalies = narrative != null && narrative.get("anomalies") instanceof List
            ? (List<Map<String, Object>>) narrative.get("anomalies") : Collections.emptyList();
        if (!anomalies.isEmpty()) {
            h.append("<tr><td style=\"padding:0 20px 12px;\">")
             .append("<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:.05em;font-weight:600;margin-bottom:6px;\">Anomalies</div>");
            for (Map<String, Object> a : anomalies) {
                String text = a.get("text") != null ? a.get("text").toString() : "";
                String kind = a.get("kind") != null ? a.get("kind").toString() : "";
                String dot = "spike".equals(kind) ? "🔺" : "theme".equals(kind) ? "🆕" : "👤";
                h.append("<div style=\"background:#fff8e1;border-left:4px solid #C7801B;border-radius:0 6px 6px 0;padding:8px 12px;margin-bottom:6px;font-size:13px;color:#0F1720;\">")
                 .append(dot).append("&nbsp;&nbsp;").append(applyInlineBold(text))
                 .append("</div>");
            }
            h.append("</td></tr>");
        }

        // KPI tiles row (5 across)
        h.append("<tr><td style=\"padding:0 20px 14px;\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\"><tr>");
        h.append(kpiTile("Total Returns to Pending", total, "#0F1720"));
        h.append(kpiTile("Affected ECNs", uniqueEcns, "#0F1720"));
        h.append(kpiTile("Top Category", topCatPct + "%", "#B8342B", topCat));
        h.append(kpiTile("Repeat Requestors", (int) repeatRequestors, "#C7801B"));
        // Avg returns-to-pending per ECN
        String avgRej = uniqueEcns > 0 ? String.format(Locale.US, "%.1f", (double) total / uniqueEcns) : "—";
        h.append(kpiTile("Avg/ECN", avgRej, "#0F1720"));
        h.append("</tr></table></td></tr>");

        // Category breakdown table
        h.append("<tr><td style=\"padding:0 20px 14px;\">");
        h.append(sectionTitle("Return to Pending Categories"));
        h.append("<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">");
        h.append("<tr style=\"background:#2c3e50;color:#fff;\">")
         .append("<th class=\"preview-th\" style=\"text-align:left;padding:6px 10px;border-bottom:1px solid #444;\">Category</th>")
         .append("<th class=\"preview-th\" style=\"text-align:right;padding:6px 10px;border-bottom:1px solid #444;\">Count</th>")
         .append("<th class=\"preview-th\" style=\"text-align:right;padding:6px 10px;border-bottom:1px solid #444;\">% of Total</th>")
         .append("</tr>");
        int rowIdx = 0;
        for (Map.Entry<String, Integer> ent : categoryCounts.entrySet()) {
            String bg = (rowIdx++ % 2 == 0) ? "#fff" : "#FAFAF7";
            int pct = total > 0 ? (int) Math.round(100.0 * ent.getValue() / total) : 0;
            h.append("<tr style=\"background:").append(bg).append(";\"><td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;\">")
             .append(esc(ent.getKey())).append("</td>")
             .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;text-align:right;\">").append(ent.getValue()).append("</td>")
             .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;text-align:right;\">").append(pct).append("%</td></tr>");
        }
        h.append("</table></td></tr>");

        // Top requestors (with AI pattern + suggested action if narrative supplied them)
        List<Map<String, Object>> aiRequestors = narrative != null && narrative.get("topRequestors") instanceof List
            ? (List<Map<String, Object>>) narrative.get("topRequestors") : null;
        h.append("<tr><td style=\"padding:0 20px 14px;\">");
        h.append(sectionTitle("Top Requestors"));
        h.append("<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">");
        h.append("<tr style=\"background:#2c3e50;color:#fff;\">")
         .append("<th class=\"preview-th\" style=\"text-align:left;padding:6px 10px;\">Requestor</th>")
         .append("<th class=\"preview-th\" style=\"text-align:right;padding:6px 10px;\">Count</th>")
         .append("<th class=\"preview-th\" style=\"text-align:left;padding:6px 10px;\">Pattern (AI)</th></tr>");
        rowIdx = 0;
        List<Map<String, Object>> requestorRows = aiRequestors != null && !aiRequestors.isEmpty() ? aiRequestors : topRequestors;
        if (requestorRows != null) {
            for (Map<String, Object> r : requestorRows) {
                if (rowIdx >= 5) break;
                String bg = (rowIdx++ % 2 == 0) ? "#fff" : "#FAFAF7";
                String name = (String) (r.containsKey("name") ? r.get("name") : "");
                Object cnt = r.get("count");
                String pattern = (String) (r.containsKey("pattern") ? r.get("pattern") : "");
                h.append("<tr style=\"background:").append(bg).append(";\">")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;color:#4a6fa5;font-weight:600;\">")
                 .append(esc(name == null ? "" : name.replaceAll("\\s*\\(\\d+\\)$", "")))
                 .append("</td>")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;text-align:right;\">").append(cnt).append("</td>")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;color:#6B7280;\">").append(applyInlineBold(pattern)).append("</td></tr>");
            }
        }
        h.append("</table></td></tr>");

        // Top themes (AI)
        List<Map<String, Object>> aiThemes = narrative != null && narrative.get("topThemes") instanceof List
            ? (List<Map<String, Object>>) narrative.get("topThemes") : null;
        List<Map<String, Object>> themeRows = aiThemes != null && !aiThemes.isEmpty() ? aiThemes : topThemes;
        if (themeRows != null && !themeRows.isEmpty()) {
            h.append("<tr><td style=\"padding:0 20px 14px;\">");
            h.append(sectionTitle("Top Themes"));
            h.append("<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">");
            h.append("<tr style=\"background:#2c3e50;color:#fff;\">")
             .append("<th class=\"preview-th\" style=\"text-align:left;padding:6px 10px;\">Theme</th>")
             .append("<th class=\"preview-th\" style=\"text-align:right;padding:6px 10px;\">Count</th></tr>");
            rowIdx = 0;
            for (Map<String, Object> r : themeRows) {
                if (rowIdx >= 5) break;
                String bg = (rowIdx++ % 2 == 0) ? "#fff" : "#FAFAF7";
                String t = String.valueOf(r.containsKey("theme") ? r.get("theme") : r.get("name"));
                Object cnt = r.get("count");
                h.append("<tr style=\"background:").append(bg).append(";\">")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;\">").append(esc(t)).append("</td>")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;text-align:right;\">").append(cnt).append("</td></tr>");
            }
            h.append("</table></td></tr>");
        }

        // Top product teams (was "Top Product Lines" before May 6 2026)
        if (topProductLines != null && !topProductLines.isEmpty()) {
            h.append("<tr><td style=\"padding:0 20px 14px;\">");
            h.append(sectionTitle("Top Product Teams"));
            h.append("<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" width=\"100%\" style=\"font-size:12px;border-collapse:collapse;\">");
            h.append("<tr style=\"background:#2c3e50;color:#fff;\">")
             .append("<th class=\"preview-th\" style=\"text-align:left;padding:6px 10px;\">Product Team</th>")
             .append("<th class=\"preview-th\" style=\"text-align:right;padding:6px 10px;\">Count</th></tr>");
            rowIdx = 0;
            for (Map<String, Object> r : topProductLines) {
                if (rowIdx >= 5) break;
                String bg = (rowIdx++ % 2 == 0) ? "#fff" : "#FAFAF7";
                h.append("<tr style=\"background:").append(bg).append(";\">")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;\">")
                 .append(esc(String.valueOf(r.get("name")))).append("</td>")
                 .append("<td class=\"preview-td\" style=\"padding:6px 10px;border:1px solid #cccccc;text-align:right;\">").append(r.get("count")).append("</td></tr>");
            }
            h.append("</table></td></tr>");
        }

        // Meta strip
        h.append("<tr><td style=\"padding:8px 20px;border-top:1px solid #E8E6DF;\">")
         .append("<span class=\"email-muted\" style=\"font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;\">")
         .append("Generated ").append(java.time.ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")))
         .append(" &middot; window ").append(from).append(" → ").append(to)
         .append("</span></td></tr>");

        // Footer
        h.append("<tr><td style=\"padding:14px 20px;background:#FAFAF7;border-top:1px solid #E8E6DF;border-radius:0 0 8px 8px;\">")
         .append("<span class=\"footer-pill\" style=\"display:inline-block;border:1px solid #ececec;border-radius:20px;padding:2px 10px;font-size:10px;color:#6B7280;\">sandisk</span>")
         .append("<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;margin-top:8px;\">PLM Toolkit &middot; ECN Returns Tracker</div>")
         .append("<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;\">This is an automated notification. Please do not reply to this email.</div>")
         .append("</td></tr>");

        h.append("</table></body></html>");
        return h.toString();
    }

    private String kpiTile(String label, Object value, String valueColor) {
        return kpiTile(label, value, valueColor, null);
    }

    private String kpiTile(String label, Object value, String valueColor, String subtitle) {
        StringBuilder s = new StringBuilder();
        s.append("<td valign=\"top\" style=\"padding:4px;\" width=\"20%\">")
         .append("<div class=\"kpi-tile\" style=\"background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;padding:10px 6px;text-align:center;\">")
         .append("<div class=\"email-muted\" style=\"font-size:10px;color:#6B7280;text-transform:uppercase;letter-spacing:.04em;font-weight:600;\">")
         .append(esc(label)).append("</div>")
         .append("<div style=\"font-size:22px;font-weight:bold;color:").append(valueColor).append(";margin-top:4px;line-height:1.1;\">")
         .append(esc(String.valueOf(value))).append("</div>");
        if (subtitle != null && !subtitle.isEmpty()) {
            s.append("<div class=\"email-muted\" style=\"font-size:10px;color:#6B7280;margin-top:3px;\">")
             .append(esc(subtitle)).append("</div>");
        }
        s.append("</div></td>");
        return s.toString();
    }

    private String sectionTitle(String txt) {
        return "<div class=\"email-muted\" style=\"font-size:11px;color:#6B7280;text-transform:uppercase;"
             + "letter-spacing:.05em;font-weight:600;border-bottom:1px solid #E8E6DF;padding-bottom:4px;margin-bottom:8px;\">"
             + esc(txt) + "</div>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Render a markdown-bullet response (lines starting with "- ", "* ", or "•")
     * as an HTML <ul>. Lines that aren't bullets become paragraphs above the list.
     * Inline **bold** runs are converted to <strong>. Mirrors the JS helper
     * `returnsRenderBullets` so the email and the in-tab dashboard look identical.
     */
    static String renderMarkdownBullets(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        StringBuilder paraBuf = new StringBuilder();
        boolean inList = false;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                if (inList) { out.append("</ul>"); inList = false; }
                if (paraBuf.length() > 0) {
                    out.append("<p style=\"margin:0 0 8px 0;\">")
                       .append(applyInlineBold(paraBuf.toString().trim()))
                       .append("</p>");
                    paraBuf.setLength(0);
                }
                continue;
            }
            // bullet detection: leading "- ", "* ", or "• "
            boolean isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ")
                            || trimmed.startsWith("\u2022 ");
            if (isBullet) {
                if (paraBuf.length() > 0) {
                    out.append("<p style=\"margin:0 0 8px 0;\">")
                       .append(applyInlineBold(paraBuf.toString().trim()))
                       .append("</p>");
                    paraBuf.setLength(0);
                }
                if (!inList) { out.append("<ul style=\"margin:0;padding-left:20px;\">"); inList = true; }
                String content = trimmed.substring(2).trim();
                out.append("<li style=\"margin:4px 0;line-height:1.55;\">")
                   .append(applyInlineBold(content))
                   .append("</li>");
            } else {
                if (inList) { out.append("</ul>"); inList = false; }
                if (paraBuf.length() > 0) paraBuf.append(' ');
                paraBuf.append(line);
            }
        }
        if (inList) out.append("</ul>");
        if (paraBuf.length() > 0) {
            out.append("<p style=\"margin:0 0 8px 0;\">")
               .append(applyInlineBold(paraBuf.toString().trim()))
               .append("</p>");
        }
        return out.toString();
    }

    /** Render **bold** runs to <strong>; escape everything else. */
    private static String applyInlineBold(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] parts = s.split("\\*\\*", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) sb.append(esc(parts[i]));
            else sb.append("<strong>").append(esc(parts[i])).append("</strong>");
        }
        return sb.toString();
    }

    // =========================================================================
    // Drill-down: AI explanation for a single event
    // =========================================================================

    public String explainEvent(String eventId) throws Exception {
        // Look up the event
        List<Map<String, Object>> all = rejectionService.getEventsInRange(
            LocalDate.now().minusDays(365), LocalDate.now().plusDays(1));
        Map<String, Object> event = null;
        for (Map<String, Object> e : all) {
            if (eventId.equals(e.get("eventId"))) { event = e; break; }
        }
        if (event == null) throw new IllegalArgumentException("Event " + eventId + " not found");
        String requestor = (String) event.get("requestor");
        // Pull this requestor's last 6 return-to-pending events for context
        List<Map<String, Object>> reqHistory = new ArrayList<>();
        for (Map<String, Object> e : all) {
            if (requestor != null && requestor.equals(e.get("requestor"))) {
                reqHistory.add(e);
                if (reqHistory.size() >= 6) break;
            }
        }
        String userPrompt = "Explain this ECN return-to-pending event for a senior leader audience as 3-5 short bullet points. "
                + "Each bullet must start with '- ' (dash + space) on its own line. Cover, in this order:\n"
                + "  - What was returned to Pending and from which status (1 bullet)\n"
                + "  - Why — key facts only (1-2 bullets)\n"
                + "  - Recurring pattern across the requestor's recent history, if any (skip if none)\n"
                + "  - Recommended next step (1 bullet)\n\n"
                + "Keep each bullet under 25 words. No headers, no preamble, no closing — just the bullets.\n\n"
                + "EVENT: " + jsonEscape(event) + "\n\n"
                + "REQUESTOR HISTORY (last 6): " + jsonEscape(reqHistory);
        return portkeyChat(
            "You are a SanDisk PLM analyst writing leadership briefings on ECN returns to pending. "
                + "You always respond as a short bulleted list. Stick to facts in the data; do not invent. "
                + "Be terse and specific.",
            userPrompt);
    }

    private String portkeyChat(String system, String user) throws Exception {
        String model = portkeyProvider + "/" + portkeyModel;
        return portkeyClient.chat(model, system, user, 512);
    }

    private static String jsonEscape(Object obj) {
        String s = obj instanceof String ? (String) obj : objectToJson(obj);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String objectToJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
