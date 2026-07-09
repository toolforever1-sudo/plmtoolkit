package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.annotation.PostConstruct;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Backs the <b>Weekly Rejection Report</b> view inside the ECN Dashboard tab.
 *
 * <p>Wraps {@code weekly_rejection_report.py} (the data/Excel/email engine) the same
 * way {@link RejectionTrackerService} wraps {@code rejection_tracker.py}. The Python
 * script is the single source of truth for the Oracle queries (event-13 rejections,
 * Days-in-CCB), the AI 6-bucket categorization, the Excel workbook (CCB heat-map +
 * Analysis sheet), and the Outlook-safe HTML email. This service only:
 * <ul>
 *   <li>generates a per-run {@code config.properties} from the toolkit's own config
 *       (db creds, Portkey key, SMTP, lookback) — db creds are NOT duplicated in the
 *       repo;</li>
 *   <li>spawns the Python and streams its stdout into the shared report-status lock;</li>
 *   <li>reads back the JSON sidecar it writes ({@code rejection-report-latest.json});</li>
 *   <li>persists analyst manual-fill values so the next build substitutes them;</li>
 *   <li>streams the Excel for download and emails the logged-in user the script's own
 *       HTML summary + Excel attachment.</li>
 * </ul>
 *
 * <p>This is a distinct feature from the Returns Tracker — it captures the explicit
 * <b>Reject</b> workflow action (event 13) across <b>all change classes</b>, not the
 * return-to-Pending status reverts (event 65) the Returns Tracker tracks. Both views
 * coexist; neither replaces the other.
 */
@Service
public class WeeklyRejectionService {

    private static final Logger logger = Logger.getLogger(WeeklyRejectionService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Shared report-status id (matches the {@code ReportService} builtin used by the controller). */
    public static final String JOB_ID = "rejection-report";

    private static final String DATA_DIR = "./data/ecn-report";
    private static final String SCRIPT = "weekly_rejection_report.py";
    private static final String GENERATED_CONFIG = "weekly-rejection-config.properties"; // relative to DATA_DIR
    private static final String LATEST_JSON = DATA_DIR + "/rejection-report-latest.json";
    private static final String MANUAL_FILL_FILE = DATA_DIR + "/rejection-manual-fill.json";

    // --- toolkit config reused to drive the Python (no creds duplicated in git) ---
    @Value("${spring.datasource.jdbc-url:}") private String jdbcUrl;
    @Value("${spring.datasource.username:agile}") private String dbUser;
    @Value("${spring.datasource.password:}") private String dbPassword;

    @Value("${portkey.api-key:}") private String portkeyApiKey;
    @Value("${portkey.provider:@anthropic-eastus2}") private String portkeyProvider;
    @Value("${portkey.model:claude-sonnet-4-6}") private String portkeyModel;
    @Value("${portkey.base-url:https://api.portkey.ai/v1/chat/completions}") private String portkeyBaseUrl;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}") private String mailFrom;

    @Value("${app.reports.python:python}") private String pythonExe;
    @Value("${app.rejection-report.ccb-threshold-days:14}") private int ccbThresholdDays;
    @Value("${app.rejection-report.lookback-days:7}") private int defaultLookbackDays;

    // In-memory cache of the last good sidecar, reloaded when the file's mtime advances.
    private long jsonMtime = 0L;
    private Map<String, Object> cached = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        new File(DATA_DIR).mkdirs();
        loadJsonIfStale();
    }

    // =========================================================================
    // Read API
    // =========================================================================

    public boolean hasData() {
        loadJsonIfStale();
        return cached != null && !cached.isEmpty() && cached.get("kpis") != null;
    }

    /**
     * Returns the parsed JSON sidecar plus a {@code stale} flag. "Stale" means the
     * cached run doesn't cover the requested lookback window or is older than a day —
     * the UI uses it to nudge the user to hit Refresh.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getData(int lookbackDays) {
        loadJsonIfStale();
        Map<String, Object> out = new LinkedHashMap<>(cached);
        boolean stale = false;
        Object period = cached.get("period");
        if (period instanceof Map) {
            Object lb = ((Map<String, Object>) period).get("lookbackDays");
            if (lb != null && !String.valueOf(lb).equals(String.valueOf(lookbackDays))) stale = true;
        }
        String gen = (String) cached.get("generatedAt");
        if (gen == null) stale = true;
        out.put("stale", stale);
        out.put("readyToExport", isReadyToExport());
        return out;
    }

    public synchronized boolean isReadyToExport() {
        Object mf = cached.get("manualFill");
        return !(mf instanceof List) || ((List<?>) mf).isEmpty();
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getManualFillGaps() {
        Object mf = cached.get("manualFill");
        return mf instanceof List ? (List<Map<String, Object>>) mf : new ArrayList<>();
    }

    private synchronized void loadJsonIfStale() {
        File f = new File(LATEST_JSON);
        if (!f.exists()) { cached = new LinkedHashMap<>(); jsonMtime = 0L; return; }
        long m = f.lastModified();
        if (m == jsonMtime && !cached.isEmpty()) return;
        try {
            cached = mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
            jsonMtime = m;
            Object kpis = cached.get("kpis");
            logger.info("[REJECT-REPORT] Loaded sidecar (" + (kpis != null ? kpis : "no kpis") + ")");
        } catch (Exception e) {
            logger.warning("[REJECT-REPORT] Failed to load sidecar: " + e.getMessage());
        }
    }

    // =========================================================================
    // Refresh — generate config + spawn the Python (blocking; caller threads it)
    // =========================================================================

    /**
     * Writes the per-run config and runs the Python synchronously, streaming each
     * stdout line to {@code logSink}. Returns the process exit code.
     *
     * @param emailTo when non-null, the Python is run with {@code email.enabled=true}
     *                and {@code email.to=<emailTo>} so the script sends the report itself.
     */
    public int runReport(int lookbackDays, String emailTo, Consumer<String> logSink) throws Exception {
        writeConfig(lookbackDays, emailTo);
        ProcessBuilder pb = new ProcessBuilder(
                pythonExe, "-u", SCRIPT, GENERATED_CONFIG);
        pb.directory(new File(DATA_DIR));
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (logSink != null) logSink.accept(line);
            }
        }
        int exit = p.waitFor();
        // Force a reload on next read so the dashboard reflects the new run.
        jsonMtime = 0L;
        return exit;
    }

    /** Generates {@code weekly-rejection-config.properties} in DATA_DIR from toolkit config. */
    private void writeConfig(int lookbackDays, String emailTo) throws Exception {
        String[] hp = parseJdbc(jdbcUrl); // host, port, service
        boolean emailEnabled = emailTo != null && !emailTo.trim().isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated by WeeklyRejectionService — do not edit by hand.\n");
        sb.append("db.host=").append(hp[0]).append("\n");
        sb.append("db.port=").append(hp[1]).append("\n");
        sb.append("db.service=").append(hp[2]).append("\n");
        sb.append("db.user=").append(dbUser).append("\n");
        sb.append("db.password=").append(dbPassword).append("\n");
        sb.append("db.schema=agile\n");
        sb.append("report.lookback_days=").append(lookbackDays).append("\n");
        sb.append("report.title=Weekly Change Rejection Report\n");
        sb.append("report.output_dir=./output\n");
        sb.append("report.filename_prefix=Weekly_Rejection_Report\n");
        sb.append("report.json_latest=./rejection-report-latest.json\n");
        sb.append("report.manual_fill=./rejection-manual-fill.json\n");
        // Classification / program parsing tuning (carried over from the utility config).
        sb.append("skip_classification_types=DCO,DRR,Deviation,SpecialOrders,NMA\n");
        sb.append("skip_program_from_reason_types=DCO,DRR,Deviation,SpecialOrders,NMA\n");
        sb.append("p3_classification_types=PDRNew,NMA\n");
        sb.append("p3_program_types=PDRNew\n");
        sb.append("deviation_classification_column=list33\n");
        sb.append("reason_max_length=500\n");
        sb.append("reason_extended_length=1500\n");
        sb.append("ccb_threshold_days=").append(ccbThresholdDays).append("\n");
        // Email — disabled for the in-app/preview path; enabled only for Send-to-me.
        sb.append("email.enabled=").append(emailEnabled).append("\n");
        sb.append("email.smtp_host=").append(smtpHost).append("\n");
        sb.append("email.smtp_port=").append(smtpPort).append("\n");
        sb.append("email.smtp_use_tls=false\n");
        sb.append("email.smtp_user=\n");
        sb.append("email.smtp_password=\n");
        sb.append("email.from=").append(mailFrom).append("\n");
        sb.append("email.to=").append(emailEnabled ? emailTo.trim() : "").append("\n");
        sb.append("email.cc=\n");
        sb.append("email.subject=Weekly Change Rejection Report - {date_range}\n");
        // AI gateway.
        if (portkeyApiKey != null && !portkeyApiKey.isEmpty()) {
            sb.append("portkey.api_key=").append(portkeyApiKey).append("\n");
            sb.append("portkey.url=").append(portkeyBaseUrl).append("\n");
            sb.append("portkey.model=").append(portkeyProvider).append("/").append(portkeyModel).append("\n");
        }
        File cf = new File(DATA_DIR, GENERATED_CONFIG);
        Files.write(cf.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Parse {@code jdbc:oracle:thin:@host:port:service} → [host, port, service]. */
    static String[] parseJdbc(String url) {
        String host = "", port = "1521", service = "";
        if (url != null) {
            int at = url.indexOf('@');
            if (at >= 0) {
                String tail = url.substring(at + 1);
                // host:port:service  OR  host:port/service
                String[] parts = tail.split("[:/]");
                if (parts.length >= 1) host = parts[0];
                if (parts.length >= 2) port = parts[1];
                if (parts.length >= 3) service = parts[2];
            }
        }
        return new String[]{host, port, service};
    }

    // =========================================================================
    // Manual fill
    // =========================================================================

    /**
     * Persists an analyst-entered value (keyed {@code "<changeNumber>|<field>"}) and
     * patches the in-memory sidecar so the dashboard updates instantly. The saved
     * value flows into the Excel + email + JSON on the next Python build.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> applyManualFill(String changeNumber, String field, String value) {
        Map<String, Object> store = loadManualFillStore();
        String key = changeNumber + "|" + field;
        if (value == null || value.trim().isEmpty()) {
            store.remove(key);
        } else {
            store.put(key, value.trim());
        }
        saveManualFillStore(store);

        // Patch the cached sidecar: drop the resolved gap, set programName on events.
        loadJsonIfStale();
        if (cached != null && !cached.isEmpty()) {
            Object mf = cached.get("manualFill");
            if (mf instanceof List) {
                List<Map<String, Object>> gaps = (List<Map<String, Object>>) mf;
                gaps.removeIf(g -> changeNumber.equals(String.valueOf(g.get("changeNumber")))
                        && field.equals(String.valueOf(g.get("field"))));
            }
            if ("programName".equals(field) && value != null && !value.trim().isEmpty()) {
                Object ev = cached.get("events");
                if (ev instanceof List) {
                    for (Object o : (List<Object>) ev) {
                        if (o instanceof Map && changeNumber.equals(String.valueOf(((Map<String, Object>) o).get("changeNumber")))) {
                            ((Map<String, Object>) o).put("programName", value.trim());
                        }
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manualFill", getManualFillGaps());
        out.put("readyToExport", isReadyToExport());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadManualFillStore() {
        File f = new File(MANUAL_FILL_FILE);
        if (!f.exists()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(f, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            logger.warning("[REJECT-REPORT] manual-fill store unreadable: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveManualFillStore(Map<String, Object> store) {
        try {
            File f = new File(MANUAL_FILL_FILE);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, store);
        } catch (Exception e) {
            logger.warning("[REJECT-REPORT] Failed to save manual-fill store: " + e.getMessage());
        }
    }

    // =========================================================================
    // Excel download
    // =========================================================================

    /** The xlsx bytes for the latest run, or {@code null} if no run exists yet. */
    public synchronized byte[] readLatestExcel() throws Exception {
        loadJsonIfStale();
        String excelFile = (String) cached.get("excelFile");
        if (excelFile == null || excelFile.isEmpty()) return null;
        File xlsx = new File(DATA_DIR + "/output", excelFile);
        if (!xlsx.exists()) return null;
        return Files.readAllBytes(xlsx.toPath());
    }

    public synchronized String latestExcelName() {
        loadJsonIfStale();
        Object n = cached.get("excelFile");
        return n != null ? n.toString() : "Weekly_Rejection_Report.xlsx";
    }

    // =========================================================================
    // Send to me — emails the script's own HTML summary + Excel attachment
    // =========================================================================

    /**
     * Sends the most recent run's HTML summary + Excel to a single recipient, reusing
     * the artifacts the Python already wrote (no Oracle/AI re-run). Throws if no run
     * exists yet.
     */
    public synchronized void sendToMe(String email) throws Exception {
        loadJsonIfStale();
        String excelFile = (String) cached.get("excelFile");
        if (excelFile == null || excelFile.isEmpty()) {
            throw new IllegalStateException("No report generated yet — click Refresh first.");
        }
        File outDir = new File(DATA_DIR + "/output");
        File xlsx = new File(outDir, excelFile);
        File htmlF = new File(outDir, excelFile.replace(".xlsx", ".html"));
        if (!xlsx.exists()) throw new IllegalStateException("Excel artifact missing — click Refresh first.");
        String html = htmlF.exists()
                ? new String(Files.readAllBytes(htmlF.toPath()), StandardCharsets.UTF_8)
                : "<p>Weekly Change Rejection Report attached.</p>";

        String dateRange = "";
        Object period = cached.get("period");
        if (period instanceof Map) {
            Map<?, ?> p = (Map<?, ?>) period;
            dateRange = p.get("from") + " to " + p.get("to");
        }
        String subject = "Weekly Change Rejection Report — " + dateRange;

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);

        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(mailFrom));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(email.trim()));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));

        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=utf-8");
        multipart.addBodyPart(htmlPart);
        MimeBodyPart attach = new MimeBodyPart();
        DataSource ds = new ByteArrayDataSource(Files.readAllBytes(xlsx.toPath()),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        attach.setDataHandler(new DataHandler(ds));
        attach.setFileName(excelFile);
        multipart.addBodyPart(attach);
        msg.setContent(multipart);

        Transport.send(msg);
        logger.info("[REJECT-REPORT] Sent report to " + email + " (xlsx " + xlsx.length() + "b)");
    }

    public int getDefaultLookbackDays() { return defaultLookbackDays; }
}
