package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Twice-daily (09:00 / 21:00 PT) email digest of Agent-API usage: an Excel of the
 * calls served + latency, plus an AI-written briefing (themes / wins / misses) —
 * scoped to the Agent API work. Reads the metrics JSONL written by AgentMetricsFilter.
 * Recipients resolved from config. Fail-soft: never throws out of the schedule.
 */
@Service
public class AgentUsageReportService {

    private static final Logger LOG = Logger.getLogger(AgentUsageReportService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.agent.metrics.file:./data/agent-metrics.jsonl}") private String metricsFile;
    @Value("${app.agent.usage-report.enabled:true}") private boolean enabled;
    @Value("${app.agent.usage-report.window-hours:12}") private int windowHours;
    @Value("${app.agent.usage-report.recipients:pdl-plm-admin@sandisk.com}") private String recipientsCsv;
    @Value("${app.agent.usage-report.recipients-file:./data/agent-usage-report-recipients.txt}") private String recipientsFile;
    @Value("${app.agent.usage-report.model:@vertexai-global/anthropic.claude-opus-4-8}") private String aiModel;
    @Value("${app.scheduling.disabled:false}") private boolean schedulingDisabled;
    @Value("${mail.smtp.host:mailrelay.sandisk.com}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;
    @Value("${mail.from:PLM-Toolkit@sandisk.com}") private String fromAddress;

    @Autowired private PortkeyClient portkeyClient;

    // ---- schedule ----

    @Scheduled(cron = "${app.agent.usage-report.cron:0 0 9,21 * * *}",
               zone = "${app.agent.usage-report.zone:America/Los_Angeles}")
    public void scheduled() {
        if (schedulingDisabled || !enabled) return;
        try {
            sendDigest();
        } catch (Exception e) {
            LOG.warning("[AGENT-DIGEST] scheduled run failed: " + e);
        }
    }

    /** Build + send the digest for the last window. Returns a small status map (for the admin trigger). */
    public Map<String, Object> sendDigest() throws Exception {
        return sendDigest(null);
    }

    /**
     * Build and send the digest. When {@code recipientsOverride} is non-empty, the mail goes only to
     * those addresses (used by the admin send-now for a private validation send); otherwise the
     * configured {@code app.agent.usage-report.recipients} list is used, as the scheduled run does.
     */
    public Map<String, Object> sendDigest(List<String> recipientsOverride) throws Exception {
        long sinceMs = System.currentTimeMillis() - windowHours * 3600_000L;
        List<Map<String, Object>> calls = readMetrics(sinceMs);
        String window = windowLabel();
        byte[] excel = buildExcel(calls, window);
        String ai = aiSummary(calls);
        String html = buildHtml(calls, ai, window);
        List<String> recipients = (recipientsOverride != null && !recipientsOverride.isEmpty())
                ? recipientsOverride : recipients();
        send(recipients, subject(calls), html, excel, window);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("calls", calls.size());
        status.put("recipients", recipients);
        status.put("window", window);
        LOG.info("[AGENT-DIGEST] sent to " + recipients + " — " + calls.size() + " calls over " + window);
        return status;
    }

    // ---- data ----

    private List<Map<String, Object>> readMetrics(long sinceMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        File f = new File(metricsFile);
        if (!f.isFile()) return out;
        String sinceIso = Instant.ofEpochMilli(sinceMs).toString();
        try {
            for (String line : Files.readAllLines(f.toPath())) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> m = MAPPER.readValue(line, Map.class);
                    String ts = String.valueOf(m.get("ts"));
                    if (ts.compareTo(sinceIso) >= 0) out.add(m);
                } catch (Exception ignore) { /* skip malformed line */ }
            }
        } catch (Exception e) {
            LOG.warning("[AGENT-DIGEST] could not read metrics: " + e);
        }
        return out;
    }

    private static String endpoint(Map<String, Object> c) {
        String p = String.valueOf(c.getOrDefault("path", "")).replace("/api/agent/", "");
        // Collapse path-variable segments so "/documents/14-01…" groups under "documents/{n}".
        if (p.startsWith("documents/") && !p.equals("documents/aggregate")) return "documents/{number}";
        if (p.startsWith("sdsm/file/")) return "sdsm/file/{id}";
        if (p.startsWith("returns/explain/")) return "returns/explain/{id}";
        return p;
    }

    private static int statusOf(Map<String, Object> c) {
        Object s = c.get("status");
        return s instanceof Number ? ((Number) s).intValue() : 0;
    }

    private static long msOf(Map<String, Object> c) {
        Object s = c.get("ms");
        return s instanceof Number ? ((Number) s).longValue() : 0;
    }

    // ---- Excel ----

    byte[] buildExcel(List<Map<String, Object>> calls, String window) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont(); hf.setBold(true); head.setFont(hf);

            Sheet detail = wb.createSheet("Calls");
            String[] cols = {"Timestamp (UTC)", "Method", "Endpoint", "Params", "HTTP", "ms"};
            Row h = detail.createRow(0);
            for (int i = 0; i < cols.length; i++) { Cell c = h.createCell(i); c.setCellValue(cols[i]); c.setCellStyle(head); }
            int r = 1;
            for (Map<String, Object> c : calls) {
                Row row = detail.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(c.getOrDefault("ts", "")));
                row.createCell(1).setCellValue(String.valueOf(c.getOrDefault("method", "")));
                row.createCell(2).setCellValue(String.valueOf(c.getOrDefault("path", "")).replace("/api/agent/", ""));
                row.createCell(3).setCellValue(String.valueOf(c.getOrDefault("query", "")));
                row.createCell(4).setCellValue(statusOf(c));
                row.createCell(5).setCellValue(msOf(c));
            }
            for (int i = 0; i < cols.length; i++) detail.autoSizeColumn(i);

            // Summary sheet: per-endpoint counts + latency + errors
            Map<String, long[]> agg = new TreeMap<>(); // endpoint -> [count, sumMs, maxMs, errors, rateLimited]
            for (Map<String, Object> c : calls) {
                String ep = endpoint(c);
                long[] a = agg.computeIfAbsent(ep, k -> new long[5]);
                long ms = msOf(c); int st = statusOf(c);
                a[0]++; a[1] += ms; a[2] = Math.max(a[2], ms);
                if (st >= 500 || (st >= 400 && st != 429)) a[3]++;
                if (st == 429) a[4]++;
            }
            Sheet sum = wb.createSheet("Summary by endpoint");
            String[] scols = {"Endpoint", "Calls", "Avg ms", "Max ms", "Errors", "Rate-limited (429)"};
            Row sh = sum.createRow(0);
            for (int i = 0; i < scols.length; i++) { Cell c = sh.createCell(i); c.setCellValue(scols[i]); c.setCellStyle(head); }
            int sr = 1;
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                long[] a = e.getValue();
                Row row = sum.createRow(sr++);
                row.createCell(0).setCellValue(e.getKey());
                row.createCell(1).setCellValue(a[0]);
                row.createCell(2).setCellValue(a[0] == 0 ? 0 : Math.round((double) a[1] / a[0]));
                row.createCell(3).setCellValue(a[2]);
                row.createCell(4).setCellValue(a[3]);
                row.createCell(5).setCellValue(a[4]);
            }
            for (int i = 0; i < scols.length; i++) sum.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ---- AI summary (themes / wins / misses) ----

    private String aiSummary(List<Map<String, Object>> calls) {
        if (calls.isEmpty()) return "No Agent-API calls in this window.";
        if (portkeyClient == null || !portkeyClient.isEnabled()) {
            return "(AI summary unavailable — AI gateway not enabled in this environment.)";
        }
        try {
            String digest = compactDigest(calls);
            String system = "You are briefing SanDisk PLM IT on how an external AI agent (AtWork) is using our read-only "
                + "'Agent API' to answer employee questions over PLM/policy documents. You are given the API calls for the "
                + "reporting window (endpoint + query params + HTTP status + latency). Write a concise briefing in three "
                + "clearly-labeled sections, plain text (no markdown headers, short bullet lines with '- '):\n"
                + "THEMES: what employees seem to be asking about — infer from documents 'q=' search terms, item/doc numbers, "
                + "and endpoints (e.g. gifts, compliance, a specific product). Be concrete.\n"
                + "WINS: what worked well — successful document retrievals/searches, fast responses, breadth of endpoints used, "
                + "graceful handling.\n"
                + "MISSES: problems to watch — rate-limited calls (429), server errors (5xx), bad requests (400), unsupported "
                + "file formats, or searches that returned nothing. If a section has nothing, say 'None notable.'\n"
                + "Keep it specific to the Agent API and under ~180 words total.";
            String reply = portkeyClient.chat(aiModel, system, digest, 900);
            return reply == null || reply.trim().isEmpty() ? "(AI summary returned empty.)" : reply.trim();
        } catch (Exception e) {
            LOG.warning("[AGENT-DIGEST] AI summary failed: " + e);
            return "(AI summary unavailable — " + e.getClass().getSimpleName() + ".)";
        }
    }

    /** Compact, token-friendly digest of the window for the model. */
    String compactDigest(List<Map<String, Object>> calls) {
        Map<String, Integer> byEp = new TreeMap<>();
        Map<Integer, Integer> byStatus = new TreeMap<>();
        List<String> qTerms = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> c : calls) {
            byEp.merge(endpoint(c), 1, Integer::sum);
            byStatus.merge(statusOf(c), 1, Integer::sum);
            String q = String.valueOf(c.getOrDefault("query", ""));
            if (q.contains("q=")) {
                for (String part : q.split("&")) {
                    if (part.startsWith("q=")) {
                        String term = java.net.URLDecoder.decode(part.substring(2), java.nio.charset.StandardCharsets.UTF_8);
                        if (!term.isEmpty() && n++ < 60) qTerms.add(term);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Total calls: ").append(calls.size()).append("\n");
        sb.append("By endpoint: ").append(byEp).append("\n");
        sb.append("By HTTP status: ").append(byStatus).append("\n");
        sb.append("Free-text search terms used (q=): ").append(qTerms.isEmpty() ? "none" : qTerms).append("\n");
        // a few notable raw calls (documents / files) for concreteness
        sb.append("Sample calls:\n");
        int shown = 0;
        for (Map<String, Object> c : calls) {
            String ep = endpoint(c);
            if ((ep.startsWith("documents") || ep.startsWith("files")) && shown++ < 40) {
                sb.append("  ").append(c.getOrDefault("path", "")).append(" ")
                  .append(c.getOrDefault("query", "")).append(" -> ").append(statusOf(c))
                  .append(" (").append(msOf(c)).append("ms)\n");
            }
        }
        return sb.toString();
    }

    // ---- HTML email (per the shared email design guidelines) ----

    private String buildHtml(List<Map<String, Object>> calls, String ai, String window) {
        int total = calls.size();
        long errors = 0, rl = 0, fileCalls = 0; long sumMs = 0; long maxMs = 0;
        Map<String, Integer> byEp = new TreeMap<>();
        for (Map<String, Object> c : calls) {
            int st = statusOf(c); long ms = msOf(c);
            if (st >= 500 || (st >= 400 && st != 429)) errors++;
            if (st == 429) rl++;
            String ep = endpoint(c);
            if (ep.startsWith("files")) fileCalls++;
            sumMs += ms; maxMs = Math.max(maxMs, ms);
            byEp.merge(ep, 1, Integer::sum);
        }
        long avg = total == 0 ? 0 : Math.round((double) sumMs / total);

        StringBuilder rows = new StringBuilder();
        byEp.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
            .forEach(e -> rows.append("<tr><td class='preview-td' style='padding:6px 10px;border:1px solid #cccccc'>")
                .append(esc(e.getKey())).append("</td><td class='preview-td' align='right' style='padding:6px 10px;border:1px solid #cccccc'>")
                .append(e.getValue()).append("</td></tr>"));

        String aiHtml = esc(ai).replace("\n", "<br>");

        return "<!-- Agent API usage digest -->"
            + "<meta name='color-scheme' content='light dark'>"
            + "<style>@media (prefers-color-scheme: dark){.email-body{background:#0F1720!important}.email-card{background:#1b2430!important}"
            + ".email-ink{color:#e8e6df!important}.email-muted{color:#9aa4b2!important}.kpi-tile{background:#141c26!important}"
            + ".preview-td{color:#e8e6df!important}.footer-pill{color:#9aa4b2!important}}</style>"
            + "<div class='email-body' style=\"background:#FAFAF7;padding:24px;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif\">"
            + "<div class='email-card' style='max-width:600px;margin:0 auto;background:#ffffff;border:1px solid #E8E6DF;border-radius:8px;overflow:hidden'>"
            + "<div style='padding:16px 24px 0'>"
            + "<span class='email-muted' style='font-size:12px;color:#6B7280'>Agile PLM / Agent API</span>"
            + "<span style='float:right;font-size:11px;background:#e8f0fe;color:#1a3a5c;border-radius:20px;padding:2px 10px'>Usage digest</span></div>"
            + "<div style='padding:8px 24px 4px'>"
            + "<div class='email-muted' style='font-size:11px;letter-spacing:1px;text-transform:uppercase;color:#6B7280'>AtWork agent · " + esc(window) + "</div>"
            + "<div class='email-ink' style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:700;color:#0F1720\">Agent API — Usage Digest</div></div>"
            // KPI tiles
            + "<table role='presentation' width='100%' style='border-collapse:collapse;padding:0 24px' cellpadding='0'><tr>"
            + kpi("Calls served", String.valueOf(total), "#1F8A4C")
            + kpi("Avg latency", avg + " ms", "#4a6fa5")
            + kpi("Rate-limited", String.valueOf(rl), rl > 0 ? "#C7801B" : "#6B7280")
            + kpi("Errors", String.valueOf(errors), errors > 0 ? "#B8342B" : "#6B7280")
            + "</tr></table>"
            // AI briefing
            + "<div style='padding:8px 24px 0'><div class='email-ink' style='font-size:14px;font-weight:600;color:#0F1720;border-bottom:1px solid #E8E6DF;padding-bottom:4px'>What the agent has been doing (AI summary)</div>"
            + "<div class='email-ink' style='font-size:13px;line-height:1.5;color:#0F1720;padding-top:8px'>" + aiHtml + "</div></div>"
            // top endpoints
            + "<div style='padding:14px 24px 0'><div class='email-muted' style='font-size:12px;color:#6B7280;border-bottom:1px solid #E8E6DF;padding-bottom:4px'>Top endpoints</div>"
            + "<table style='border-collapse:collapse;margin-top:8px;font-size:12px' width='100%'>"
            + "<tr><th class='preview-th' align='left' style='background:#2c3e50;color:#fff;padding:6px 10px;border-bottom:1px solid #444444'>Endpoint</th>"
            + "<th class='preview-th' align='right' style='background:#2c3e50;color:#fff;padding:6px 10px;border-bottom:1px solid #444444'>Calls</th></tr>"
            + rows + "</table>"
            + "<div class='email-muted' style='font-size:11px;color:#6B7280;padding-top:6px'>Full call log with latency is attached as Excel (" + total + " rows).</div></div>"
            // footer
            + "<div style='padding:16px 24px'><div style='border-top:1px solid #E8E6DF;padding-top:10px'>"
            + "<span class='footer-pill' style='font-size:11px;border:1px solid #ececec;border-radius:20px;padding:2px 10px;color:#6B7280'>sandisk</span>"
            + "<div class='email-muted' style='font-size:11px;color:#6B7280;padding-top:6px;font-family:\"IBM Plex Mono\",Consolas,monospace'>Generated " + esc(nowLabel()) + "</div>"
            + "<div class='email-muted' style='font-size:11px;color:#6B7280'>PLM Toolkit · Agent API usage digest. Automated notification — please do not reply.</div>"
            + "</div></div></div></div>";
    }

    private static String kpi(String label, String value, String color) {
        return "<td width='25%' style='padding:6px'><div class='kpi-tile' style='background:#FAFAF7;border:1px solid #E8E6DF;border-radius:6px;text-align:center;padding:10px 4px'>"
            + "<div class='email-muted' style='font-size:11px;text-transform:uppercase;color:#6B7280'>" + esc(label) + "</div>"
            + "<div style='font-size:22px;font-weight:700;color:" + color + "'>" + esc(value) + "</div></div></td>";
    }

    // ---- send ----

    private void send(List<String> recipients, String subject, String html, byte[] excel, String window) throws Exception {
        if (recipients.isEmpty()) { LOG.warning("[AGENT-DIGEST] no recipients configured — skipping send"); return; }
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddress));
        for (String to : recipients) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(EmailEnvTag.tag(subject));

        MimeBodyPart body = new MimeBodyPart();
        body.setContent(html, "text/html; charset=utf-8");

        MimeBodyPart attach = new MimeBodyPart();
        attach.setDataHandler(new javax.activation.DataHandler(
            new javax.mail.util.ByteArrayDataSource(excel, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
        attach.setFileName("agent-api-usage-" + window.replaceAll("[^A-Za-z0-9]+", "-") + ".xlsx");

        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(body);
        mp.addBodyPart(attach);
        msg.setContent(mp);
        Transport.send(msg);
    }

    // ---- helpers ----

    /**
     * Effective recipient list. Read fresh on every send so edits take effect without a restart
     * ("hot-editable"): the recipients override file (one address per line, or comma/semicolon
     * separated; blank lines and {@code #} comments ignored) wins when present and non-empty;
     * otherwise the configured {@code app.agent.usage-report.recipients} list is used.
     */
    List<String> recipients() {
        List<String> fromFile = readRecipientsFile();
        if (!fromFile.isEmpty()) return fromFile;
        return parseAddresses(recipientsCsv);
    }

    private List<String> readRecipientsFile() {
        List<String> out = new ArrayList<>();
        try {
            if (recipientsFile == null) return out;
            File f = new File(recipientsFile);
            if (!f.isFile()) return out;
            for (String line : Files.readAllLines(f.toPath())) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                out.addAll(parseAddresses(s));
            }
        } catch (Exception e) {
            LOG.warning("[AGENT-DIGEST] could not read recipients file " + recipientsFile + ": " + e.getMessage()
                    + " — falling back to configured recipients");
            return new ArrayList<>();
        }
        return dedupe(out);
    }

    private static List<String> parseAddresses(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split("[,;\\n\\r]")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        }
        return dedupe(out);
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : in) { if (seen.add(s.toLowerCase(Locale.ROOT))) out.add(s); }
        return out;
    }

    /** Current effective recipients + where they came from (for the admin GET). */
    public Map<String, Object> recipientsStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> file = readRecipientsFile();
        m.put("recipients", recipients());
        m.put("source", file.isEmpty() ? "config" : "file");
        m.put("file", recipientsFile);
        m.put("hotEditable", true);
        return m;
    }

    /**
     * Overwrite the recipients override file — takes effect on the next send with no restart.
     * Passing an empty list removes the override so the configured default applies again.
     */
    public Map<String, Object> setRecipients(List<String> addresses) throws Exception {
        List<String> clean = dedupe(addresses == null ? Collections.emptyList() : addresses);
        File f = new File(recipientsFile);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        if (clean.isEmpty()) {
            if (f.exists() && !f.delete()) throw new Exception("could not remove recipients file " + recipientsFile);
        } else {
            StringBuilder sb = new StringBuilder("# Agent-API usage-digest recipients — hot-editable, one per line.\n");
            for (String a : clean) sb.append(a).append('\n');
            Files.write(f.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        LOG.info("[AGENT-DIGEST] recipients updated to " + (clean.isEmpty() ? "(config default)" : clean));
        return recipientsStatus();
    }

    private String subject(List<Map<String, Object>> calls) {
        return "Agent API: " + calls.size() + " calls · " + windowLabel();
    }

    private static final DateTimeFormatter WIN =
        DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.of("America/Los_Angeles"));

    private String windowLabel() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Los_Angeles"));
        ZonedDateTime from = now.minusHours(windowHours);
        return WIN.format(from) + " – " + WIN.format(now) + " PT";
    }

    private String nowLabel() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.of("America/Los_Angeles"))
            .format(ZonedDateTime.now(ZoneId.of("America/Los_Angeles")));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
