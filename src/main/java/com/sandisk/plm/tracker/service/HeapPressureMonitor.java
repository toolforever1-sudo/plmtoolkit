package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Detects "wedged but not dead" JVM states — heap stays high for several
 * minutes without triggering an OOM crash. Watchdog ({@code run-loop.bat})
 * already auto-respawns on hard crashes, but it can't see slow leaks or GC
 * thrash that leave APIs sluggish without ever exiting.
 *
 * <p>Samples used heap once per minute via {@link MemoryGuard}. When the
 * rolling 5-sample window stays above {@code app.heap-alert.threshold-pct}
 * (default 0.85, i.e. 85% of -Xmx), sends a one-shot email to
 * {@code app.heap-alert.recipient} via SanDisk SMTP relay. After firing,
 * a {@code app.heap-alert.cooldown-minutes} cool-down (default 30) prevents
 * spamming during a slow leak.
 *
 * <p>Gated by {@code app.scheduling.disabled} like every other scheduled
 * service in this module — local instances (where the property is true)
 * skip this entirely.
 */
@Service
public class HeapPressureMonitor {

    private static final Logger logger = Logger.getLogger(HeapPressureMonitor.class.getName());

    /** Number of consecutive 60s samples required above threshold to trigger. */
    private static final int SAMPLE_WINDOW = 5;

    @Value("${app.heap-alert.enabled:true}")
    private boolean enabled;

    @Value("${app.heap-alert.threshold-pct:0.85}")
    private double thresholdPct;

    @Value("${app.heap-alert.cooldown-minutes:30}")
    private int cooldownMinutes;

    @Value("${app.heap-alert.recipient:vikas.jindal@sandisk.com}")
    private String recipient;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String mailFrom;

    @Value("${app.maintenance.app-url:http://uls-ep-aglipccb:8090}")
    private String appUrl;

    @Value("${app.heap-gc.enabled:true}")
    private boolean idleGcEnabled;

    @Value("${app.heap-gc.threshold-pct:0.70}")
    private double idleGcThresholdPct;

    @Value("${app.heap-gc.window-start-hour:21}")
    private int idleGcWindowStartHour;

    @Value("${app.heap-gc.window-end-hour:6}")
    private int idleGcWindowEndHour;

    @Value("${app.heap-gc.idle-window-minutes:5}")
    private int idleGcIdleWindowMinutes;

    @Autowired private MemoryGuard memoryGuard;
    @Autowired private ItemCacheService itemCacheService;
    @Autowired private ExternalSourceService externalSourceService;
    @Autowired private SessionRegistry sessionRegistry;

    private final Deque<Double> samples = new ArrayDeque<>();
    private volatile long lastAlertSentMs = 0;

    /**
     * Runs once per minute. Disabled at the @EnableScheduling level on local
     * (app.scheduling.disabled=true), so this never fires there. On prod
     * fires every 60s.
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 60_000L)
    public void sample() {
        if (!enabled) return;
        double frac = memoryGuard.usedFraction();
        synchronized (samples) {
            samples.addLast(frac);
            while (samples.size() > SAMPLE_WINDOW) samples.removeFirst();
            if (samples.size() < SAMPLE_WINDOW) return; // still warming up

            double sum = 0.0;
            for (Double s : samples) sum += s;
            double avg = sum / samples.size();

            if (avg <= thresholdPct) return;

            long now = System.currentTimeMillis();
            long cooldownMs = cooldownMinutes * 60_000L;
            if (now - lastAlertSentMs < cooldownMs) {
                logger.info("[HEAP-ALERT] Suppressed (cooldown) — avg=" + Math.round(avg * 100)
                    + "% over " + SAMPLE_WINDOW + " samples; next alert allowed in "
                    + ((cooldownMs - (now - lastAlertSentMs)) / 1000) + "s");
                return;
            }

            lastAlertSentMs = now;
            try {
                sendAlert(avg);
                logger.warning("[HEAP-ALERT] Fired — avg=" + Math.round(avg * 100)
                    + "% sustained over " + SAMPLE_WINDOW + " minutes; emailed " + recipient);
            } catch (Exception e) {
                logger.warning("[HEAP-ALERT] Failed to send alert email: " + e.getMessage());
            }
        }
    }

    /**
     * Overnight idle-window Full GC. Runs every 15 minutes; only actually
     * calls {@code System.gc()} when ALL of these hold:
     *
     * <ul>
     *   <li>Now is between 21:00 and 06:00 America/Los_Angeles.</li>
     *   <li>No user has touched the server in the last 5 minutes
     *       (via {@link SessionRegistry#listActive(long)}).</li>
     *   <li>Used heap is above {@code app.heap-gc.threshold-pct} (default 70%).</li>
     * </ul>
     *
     * Rationale: the heap monitor measures {@code totalMemory − freeMemory},
     * which includes garbage that G1 hasn't reclaimed yet. After multi-day
     * uptime + nightly BOM batches, a fair chunk of "used" heap is
     * collectable. A Full GC pause is 2–4s at 6 GB; gating it on zero recent
     * activity in the overnight window means no real user ever notices.
     */
    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 5 * 60_000L)
    public void idleWindowGc() {
        if (!idleGcEnabled) return;

        LocalTime nowPt = LocalTime.now(ZoneId.of("America/Los_Angeles"));
        if (!inOvernightWindow(nowPt.getHour())) return;

        int activeUsers = sessionRegistry.listActive(idleGcIdleWindowMinutes * 60_000L).size();
        if (activeUsers > 0) {
            logger.info("[HEAP-GC] Skipped — " + activeUsers + " user(s) active in last "
                + idleGcIdleWindowMinutes + " min");
            return;
        }

        double usedFracBefore = memoryGuard.usedFraction();
        if (usedFracBefore <= idleGcThresholdPct) return;

        long usedMbBefore = memoryGuard.usedMb();
        long maxMb = memoryGuard.maxMb();
        logger.info("[HEAP-GC] Triggering Full GC — used=" + Math.round(usedFracBefore * 100)
            + "% (" + usedMbBefore + " MB / " + maxMb + " MB), zero active users, time="
            + nowPt.withSecond(0).withNano(0) + " PT");

        long t0 = System.currentTimeMillis();
        System.gc();
        long elapsedMs = System.currentTimeMillis() - t0;

        long usedMbAfter = memoryGuard.usedMb();
        int pctAfter = memoryGuard.usedPercent();
        long reclaimedMb = usedMbBefore - usedMbAfter;
        logger.info("[HEAP-GC] Done in " + elapsedMs + " ms — reclaimed " + reclaimedMb
            + " MB; used now " + pctAfter + "% (" + usedMbAfter + " MB / " + maxMb + " MB)");
    }

    /**
     * True when {@code hour} is inside the wraparound window
     * [{@code idleGcWindowStartHour}, {@code idleGcWindowEndHour}). With
     * defaults 21 and 6, this is 21,22,23,0,1,2,3,4,5.
     */
    private boolean inOvernightWindow(int hour) {
        int start = idleGcWindowStartHour;
        int end = idleGcWindowEndHour;
        if (start == end) return false;
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end; // wraps midnight
    }

    /**
     * Reports the current evaluation of every gate that {@link #idleWindowGc()}
     * checks, so admins can see "would it run if the timer fired right now"
     * without actually doing anything. Used by
     * {@code GET /api/maintenance/heap-gc/status}.
     */
    public Map<String, Object> getIdleGcStatus() {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        LocalTime nowPt = LocalTime.now(ZoneId.of("America/Los_Angeles"));
        boolean inWindow = inOvernightWindow(nowPt.getHour());
        int activeUsers = sessionRegistry.listActive(idleGcIdleWindowMinutes * 60_000L).size();
        double usedFrac = memoryGuard.usedFraction();
        boolean aboveThreshold = usedFrac > idleGcThresholdPct;
        boolean wouldFire = idleGcEnabled && inWindow && activeUsers == 0 && aboveThreshold;

        out.put("enabled", idleGcEnabled);
        out.put("nowPt", nowPt.withSecond(0).withNano(0).toString());
        out.put("windowStartHour", idleGcWindowStartHour);
        out.put("windowEndHour", idleGcWindowEndHour);
        out.put("inWindow", inWindow);
        out.put("activeUsers", activeUsers);
        out.put("idleWindowMinutes", idleGcIdleWindowMinutes);
        out.put("usedHeapPct", Math.round(usedFrac * 100));
        out.put("thresholdPct", Math.round(idleGcThresholdPct * 100));
        out.put("aboveThreshold", aboveThreshold);
        out.put("wouldFire", wouldFire);
        return out;
    }

    /**
     * Admin-only forced run that bypasses ALL gates (enabled/window/idle/threshold)
     * and calls {@link System#gc()} immediately. Returns before/after heap stats.
     * Used by {@code POST /api/maintenance/heap-gc/test}.
     */
    public Map<String, Object> runIdleWindowGcNow() {
        long usedMbBefore = memoryGuard.usedMb();
        int pctBefore = memoryGuard.usedPercent();
        long maxMb = memoryGuard.maxMb();
        logger.info("[HEAP-GC] Forced test — running Full GC; used=" + pctBefore
            + "% (" + usedMbBefore + " MB / " + maxMb + " MB)");

        long t0 = System.currentTimeMillis();
        System.gc();
        long elapsedMs = System.currentTimeMillis() - t0;

        long usedMbAfter = memoryGuard.usedMb();
        int pctAfter = memoryGuard.usedPercent();
        long reclaimedMb = usedMbBefore - usedMbAfter;
        logger.info("[HEAP-GC] Forced test done in " + elapsedMs + " ms — reclaimed "
            + reclaimedMb + " MB; used now " + pctAfter + "% (" + usedMbAfter + " MB / " + maxMb + " MB)");

        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("elapsedMs", elapsedMs);
        out.put("usedMbBefore", usedMbBefore);
        out.put("usedMbAfter", usedMbAfter);
        out.put("reclaimedMb", reclaimedMb);
        out.put("pctBefore", pctBefore);
        out.put("pctAfter", pctAfter);
        out.put("maxMb", maxMb);
        return out;
    }

    /**
     * Admin-only manual trigger for testing the alert email rendering. Bypasses
     * the threshold + cooldown gates and sends one email using the current
     * heap snapshot (whatever it actually is — body shows real values, even
     * if well below threshold). Used by {@code POST /api/admin/heap-alert/test}.
     */
    public void sendTestAlert() throws Exception {
        sendAlert(memoryGuard.usedFraction());
        lastAlertSentMs = System.currentTimeMillis();
    }

    private void sendAlert(double avgFrac) throws Exception {
        long usedMb = memoryGuard.usedMb();
        long maxMb = memoryGuard.maxMb();
        int avgPct = (int) Math.round(avgFrac * 100);
        int currentPct = memoryGuard.usedPercent();

        // Item cache snapshot — best-effort, never blocks the alert if it errors.
        String itemCacheBlock;
        try {
            Map<String, Object> ic = itemCacheService.getStatus();
            Boolean loaded = (Boolean) ic.get("loaded");
            if (Boolean.TRUE.equals(loaded)) {
                itemCacheBlock = "<tr><td>Item Cache</td><td style='text-align:right;font-family:monospace;'>"
                    + ic.get("totalRecords") + " records, " + ic.get("fileSizeMB") + " MB on disk</td></tr>";
            } else {
                itemCacheBlock = "<tr><td>Item Cache</td><td style='text-align:right;color:#6B7280;'>not loaded</td></tr>";
            }
        } catch (Exception e) {
            itemCacheBlock = "<tr><td>Item Cache</td><td style='text-align:right;color:#B8342B;'>(snapshot failed)</td></tr>";
        }

        // External-source caches — sum + per-source, sorted by file size desc.
        StringBuilder extBlock = new StringBuilder();
        long extTotalMb = 0;
        try {
            List<Map<String, String>> sources = externalSourceService.getSourceList();
            // Sort by cacheSizeBytes desc
            sources.sort((a, b) -> {
                long sa = parseLongSafe(a.get("cacheSizeBytes"));
                long sb = parseLongSafe(b.get("cacheSizeBytes"));
                return Long.compare(sb, sa);
            });
            for (Map<String, String> s : sources) {
                long bytes = parseLongSafe(s.get("cacheSizeBytes"));
                long mb = bytes / (1024L * 1024L);
                extTotalMb += mb;
                String count = s.getOrDefault("recordCount", "");
                extBlock.append("<tr><td>External \u00b7 ").append(escHtml(s.get("name"))).append("</td>")
                    .append("<td style='text-align:right;font-family:monospace;'>")
                    .append(count.isEmpty() ? "?" : count).append(" records, ")
                    .append(mb).append(" MB on disk</td></tr>");
            }
        } catch (Exception e) {
            extBlock.append("<tr><td>External sources</td><td style='text-align:right;color:#B8342B;'>(snapshot failed)</td></tr>");
        }

        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date());
        String backWhen = "after " + cooldownMinutes + "-minute cooldown";

        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='color-scheme' content='light dark'>"
            + "<style>body{margin:0;padding:0;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720}"
            + ".card{max-width:600px;margin:24px auto;background:#fff;border:1px solid #E8E6DF;border-radius:8px}"
            + ".nav{padding:14px 20px;border-bottom:1px solid #E8E6DF;color:#6B7280;font-size:11px;letter-spacing:0.5px}"
            + ".hero{padding:18px 20px 8px}"
            + ".eyebrow{font-size:11px;color:#6B7280;text-transform:uppercase;letter-spacing:1px;margin:0 0 4px}"
            + ".title{font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;margin:0 0 4px;color:#0F1720}"
            + ".badge{display:inline-block;background:#fdeaea;color:#B8342B;padding:3px 10px;border-radius:14px;font-size:11px;font-weight:600;margin-left:6px;vertical-align:middle}"
            + ".content{padding:0 20px 20px;font-size:13.5px;line-height:1.55}"
            + ".callout{background:#fdeaea;border-left:4px solid #B8342B;padding:10px 14px;border-radius:0 6px 6px 0;margin:14px 0}"
            + ".kpi{font-family:'IBM Plex Mono',Consolas,monospace;font-size:18px;font-weight:600;color:#B8342B}"
            + ".tbl{width:100%;border-collapse:collapse;margin:10px 0;font-size:12.5px}"
            + ".tbl td{padding:6px 0;border-bottom:1px solid #f0eee8}"
            + ".foot{padding:14px 20px;border-top:1px solid #E8E6DF;background:#FAFAF7;font-size:11px;color:#6B7280}"
            + ".pill{display:inline-block;border:1px solid #ececec;border-radius:20px;padding:2px 10px;font-size:10px;color:#6B7280;background:#fff}</style></head><body>"
            + "<div class='card'>"
            + "<div class='nav'>PLM Toolkit / Heap Pressure <span class='badge'>ALERT</span></div>"
            + "<div class='hero'>"
            + "<p class='eyebrow'>SUSTAINED HEAP PRESSURE \u2014 " + SAMPLE_WINDOW + " MIN</p>"
            + "<h1 class='title'>JVM heap is " + avgPct + "% full on average</h1>"
            + "<p style='font-size:13px;color:#6B7280;margin:0 0 16px'>Currently " + currentPct + "% (" + usedMb + " MB / " + maxMb + " MB). The watchdog only catches hard crashes, not wedged states \u2014 worth a quick look before it becomes an outage.</p>"
            + "</div>"
            + "<div class='content'>"
            + "<div class='callout'><strong>Threshold</strong>: " + Math.round(thresholdPct * 100) + "% used heap, sustained over " + SAMPLE_WINDOW + " consecutive 60s samples.<br>"
            + "<strong>Next alert</strong>: not before " + backWhen + ".</div>"
            + "<p style='margin:14px 0 6px;font-weight:600'>What's resident:</p>"
            + "<table class='tbl'>" + itemCacheBlock + extBlock.toString()
            + "<tr style='font-weight:600'><td>External-source caches total</td><td style='text-align:right;font-family:monospace'>~" + extTotalMb + " MB on disk</td></tr>"
            + "</table>"
            + "<p style='font-size:12px;color:#6B7280;margin-top:14px'>Open <a href='" + escHtml(appUrl) + "' style='color:#4a6fa5'>" + escHtml(appUrl) + "</a> to investigate. If the JVM is unresponsive, run <code>./restart-local.sh</code> on local; on prod, the watchdog respawn is automatic on a real OOM.</p>"
            + "</div>"
            + "<div class='foot'><span class='pill'>sandisk</span> &nbsp; plm-field-tracker \u00b7 HeapPressureMonitor \u00b7 " + escHtml(now) + "<br>This is an automated notification. Please do not reply to this email.</div>"
            + "</div></body></html>";

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.auth", "false");
        Session session = Session.getInstance(props);
        Message msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(mailFrom));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("plm-toolkit prod: \u26a0\ufe0f heap pressure (" + avgPct + "% used, " + usedMb + " MB / " + maxMb + " MB)"));
        msg.setContent(html, "text/html; charset=utf-8");
        Transport.send(msg);
    }

    private static long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
