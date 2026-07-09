package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saves user-uploaded files to disk before the heavy processing starts so we can
 * recover them after a crash.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>{@link #quarantine(MultipartFile, String, String, Map)} writes the file plus a
 *       sidecar JSON with request metadata (user, endpoint, filename, params, timestamp)
 *       into {@code app.upload-quarantine.dir}, returns a ticket.</li>
 *   <li>The endpoint runs its work. On success, calls {@link #release(String)} which
 *       deletes the file + sidecar.</li>
 *   <li>If the JVM dies (OOM, kill, machine crash) before {@code release} runs, the
 *       file stays on disk. On the next startup, {@link #processOrphans()} fires
 *       (via {@link ApplicationReadyEvent}), emails {@code app.upload-quarantine.email-to}
 *       with each orphaned file as an attachment, and moves them to a {@code processed/}
 *       subdir so they aren't re-emailed.</li>
 * </ol>
 *
 * <p>Disabled cleanly when {@code app.upload-quarantine.enabled=false} — the methods
 * become no-ops returning empty tickets.</p>
 */
@Service
public class UploadQuarantineService {

    private static final Logger logger = Logger.getLogger(UploadQuarantineService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong COUNTER = new AtomicLong();

    @Value("${app.upload-quarantine.enabled:true}")
    private boolean enabled;

    @Value("${app.upload-quarantine.dir:./data/upload-quarantine}")
    private String dirPath;

    @Value("${app.upload-quarantine.email-to:pdl-plm-admin@sandisk.com}")
    private String emailTo;

    @Value("${app.upload-quarantine.max-age-days:14}")
    private int maxAgeDays;

    /** Don't attach files larger than this to the recovery email — base64 inflates
     *  ~33%, so 6 MB stays safely under the mail relay's ~10 MB message-size limit
     *  (it rejects oversized messages with "552 message size exceeds limit"). The
     *  file is preserved in processed/ and the file archive regardless. */
    @Value("${app.upload-quarantine.max-attach-bytes:6291456}")
    private long maxAttachBytes;

    @Value("${mail.smtp.host:mailrelay.sandisk.com}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from:PLM-Toolkit@sandisk.com}")
    private String mailFrom;

    @Autowired(required = false)
    private com.sandisk.plm.tracker.config.ConfigBanner configBanner;

    @Autowired(required = false)
    private com.sandisk.plm.tracker.controller.SupportController supportController;

    @Autowired(required = false)
    private FileArchiveService fileArchive;

    @Autowired(required = false)
    private LdapAuthService ldapAuthService;

    /** Save a copy of the upload before processing. Returns a ticket id (filename of
     *  the persisted copy) that the caller passes to {@link #release(String)} on success.
     *
     *  <p>Side-effect: also forwards the file to {@link FileArchiveService} for
     *  long-retention archival (30-day rolling). The two services have different
     *  lifecycles &mdash; quarantine deletes on success (crash-recovery only);
     *  archive keeps the file for replay regardless of outcome.
     */
    public String quarantine(MultipartFile file, String username, String endpoint,
                             Map<String, String> requestParams) {
        if (!enabled || file == null) return null;

        // Long-retention archival — fire-and-forget. Captures EVERY upload regardless
        // of success / failure of the downstream handler. Feature tag derived from
        // the endpoint URI so the admin viewer can group by feature.
        if (fileArchive != null) {
            String displayName = null;
            try {
                // Pull displayName from MDC if available; otherwise null. Most callers
                // pass username only — that's fine, the archive entry just shows the id.
            } catch (Exception ignored) {}
            String feature = featureFromEndpoint(endpoint);
            fileArchive.recordUpload(file, username, displayName, endpoint, feature, null);
        }

        try {
            File dir = new File(dirPath);
            if (!dir.exists()) dir.mkdirs();

            String ticket = makeTicket(username, file.getOriginalFilename());
            File copy = new File(dir, ticket);
            try (InputStream is = file.getInputStream();
                 FileOutputStream fos = new FileOutputStream(copy)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) >= 0) fos.write(buf, 0, n);
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("ticket", ticket);
            meta.put("username", nz(username));
            meta.put("endpoint", nz(endpoint));
            meta.put("originalFilename", nz(file.getOriginalFilename()));
            meta.put("sizeBytes", copy.length());
            meta.put("startedAt", Instant.now().toString());
            meta.put("requestParams", requestParams != null ? requestParams : new LinkedHashMap<>());
            JSON.writerWithDefaultPrettyPrinter()
                .writeValue(new File(dir, ticket + ".meta.json"), meta);

            logger.info("[QUARANTINE] saved " + ticket + " (" + copy.length() + " bytes) for " + endpoint);
            return ticket;
        } catch (Exception e) {
            // Quarantine is best-effort — never let an I/O glitch break the upload itself.
            logger.log(Level.WARNING, "[QUARANTINE] save failed: " + e.getMessage(), e);
            return null;
        }
    }

    /** Delete a previously quarantined file once processing finished cleanly. */
    public void release(String ticket) {
        if (!enabled || ticket == null) return;
        File dir = new File(dirPath);
        File copy = new File(dir, ticket);
        File meta = new File(dir, ticket + ".meta.json");
        if (copy.exists()) copy.delete();
        if (meta.exists()) meta.delete();
    }

    /** On startup, email any leftover quarantined files (= JVM died mid-processing)
     *  and move them aside so we don't re-email next time. */
    @EventListener(ApplicationReadyEvent.class)
    public void processOrphans() {
        if (!enabled) return;
        File dir = new File(dirPath);
        if (!dir.exists()) return;

        File processed = new File(dir, "processed");
        if (!processed.exists()) processed.mkdirs();

        // Scan top-level directory only; "processed" subdir is skipped by the filter.
        File[] metas = dir.listFiles((d, n) -> n.endsWith(".meta.json"));
        if (metas == null || metas.length == 0) {
            logger.info("[QUARANTINE] no orphaned uploads on startup");
        } else {
            logger.warning("[QUARANTINE] found " + metas.length + " orphaned upload(s) — emailing "
                    + emailTo);
            for (File m : metas) {
                try {
                    String base = m.getName().replaceFirst("\\.meta\\.json$", "");
                    File data = new File(dir, base);
                    if (!data.exists()) {
                        // Meta but no payload — still move the meta aside so we don't re-process it.
                        Files.move(m.toPath(), new File(processed, m.getName()).toPath(),
                                   StandardCopyOption.REPLACE_EXISTING);
                        continue;
                    }
                    Map<String, Object> meta = readMeta(m);
                    // Email + bug ticket are best-effort: a failure here (e.g. the mail
                    // relay rejecting an oversized message) must NOT keep the orphan in
                    // place, or it re-emails on every startup forever. The payload is
                    // preserved under processed/ (and the file archive) regardless.
                    try { sendOrphanEmail(data, meta); }
                    catch (Exception mailEx) { logger.warning("[QUARANTINE] orphan email failed for "
                            + base + " (moving aside anyway): " + mailEx.getMessage()); }
                    try { fileBugTicket(data, meta); }
                    catch (Exception ticketEx) { logger.warning("[QUARANTINE] bug-ticket file failed for "
                            + base + ": " + ticketEx.getMessage()); }
                    Files.move(data.toPath(), new File(processed, base).toPath(),
                               StandardCopyOption.REPLACE_EXISTING);
                    Files.move(m.toPath(),    new File(processed, m.getName()).toPath(),
                               StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "[QUARANTINE] failed to process orphan "
                            + m.getName() + ": " + e.getMessage(), e);
                }
            }
        }

        // Housekeeping — sweep old processed/ files past the retention window.
        long cutoff = System.currentTimeMillis() - (maxAgeDays * 24L * 60L * 60L * 1000L);
        File[] old = processed.listFiles();
        if (old != null) {
            for (File f : old) if (f.lastModified() < cutoff) f.delete();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(File metaFile) {
        try { return JSON.readValue(metaFile, Map.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private void sendOrphanEmail(File data, Map<String, Object> meta) throws Exception {
        String build = configBanner != null ? configBanner.getBuildLabel() : "unknown";
        String username  = (String) meta.getOrDefault("username", "");
        String endpoint  = (String) meta.getOrDefault("endpoint", "");
        String origName  = (String) meta.getOrDefault("originalFilename", data.getName());
        Object sizeObj   = meta.get("sizeBytes");
        long size = sizeObj instanceof Number ? ((Number) sizeObj).longValue() : data.length();
        String startedAt = (String) meta.getOrDefault("startedAt", "");
        Object params    = meta.getOrDefault("requestParams", new LinkedHashMap<>());

        String subject = "PLM Toolkit: \u26A0 orphaned upload recovered after restart \u2014 "
                + (origName.length() > 50 ? origName.substring(0, 50) + "\u2026" : origName);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"light dark\"></head>");
        html.append("<body style=\"margin:0;padding:20px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">");
        html.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"600\" style=\"max-width:600px;background:#fff;border:1px solid #E8E6DF;border-radius:8px;overflow:hidden;\">");
        html.append("<tr><td style=\"padding:14px 22px;border-bottom:1px solid #E8E6DF;\">");
        html.append("<span style=\"color:#6B7280;font-size:13px;\">PLM Toolkit / Crash Recovery</span>");
        html.append("<span style=\"display:inline-block;padding:2px 10px;margin-left:8px;background:#fff8e1;color:#856404;border-radius:12px;font-size:11px;font-weight:600;\">CRASH RECOVERY</span>");
        html.append("</td></tr>");
        html.append("<tr><td style=\"padding:22px;\">");
        html.append("<div style=\"text-transform:uppercase;color:#6B7280;letter-spacing:1px;font-size:11px;font-weight:600;\">ORPHANED UPLOAD</div>");
        html.append("<h1 style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:20px;margin:6px 0 14px;\">An upload was in flight when the previous JVM stopped responding.</h1>");
        boolean attachFile = data.length() <= maxAttachBytes;
        html.append("<p style=\"margin:0 0 14px;font-size:13.5px;line-height:1.55;\">The file was saved to disk before processing began, so we can investigate the crash without bothering the user. "
                + (attachFile
                    ? "The original file is attached."
                    : "The file is too large to attach (" + formatBytes(data.length()) + "); it is preserved on the server under <code>upload-quarantine/processed/</code> and in the file archive.")
                + "</p>");
        html.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"font-size:13px;\">");
        html.append(kv("User",        username));
        html.append(kv("Endpoint",    endpoint));
        html.append(kv("File name",   origName));
        html.append(kv("File size",   formatBytes(size)));
        html.append(kv("Started at",  startedAt));
        html.append(kv("Request params", params == null ? "" : String.valueOf(params)));
        html.append(kv("Restart build", build));
        html.append(kv("Recovered at", Instant.now().toString()));
        html.append("</table>");
        html.append("<p style=\"margin:14px 0 0;color:#6B7280;font-size:12px;\">This is an automated notification. The file has been moved to <code>processed/</code> and won't trigger another email on next startup.</p>");
        html.append("</td></tr>");
        html.append("<tr><td style=\"padding:14px 22px;background:#FAFAF7;border-top:1px solid #E8E6DF;text-align:center;\">");
        html.append("<span style=\"display:inline-block;padding:2px 10px;border:1px solid #ececec;border-radius:20px;font-size:11px;color:#6B7280;\">sandisk</span>");
        html.append("<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit \u00b7 Upload Quarantine \u00b7 Automated.</div>");
        html.append("</td></tr></table></body></html>");

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session sess = Session.getInstance(props);

        MimeMessage msg = new MimeMessage(sess);
        msg.setFrom(new InternetAddress(mailFrom));
        for (String addr : emailTo.split("[,;]\\s*")) {
            String t = addr.trim();
            if (!t.isEmpty()) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(t));
        }
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html.toString(), "text/html; charset=utf-8");

        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(htmlPart);
        if (attachFile) {
            MimeBodyPart attach = new MimeBodyPart();
            DataSource ds = new FileDataSource(data);
            attach.setDataHandler(new DataHandler(ds));
            attach.setFileName(origName);
            mp.addBodyPart(attach);
        }
        msg.setContent(mp);

        Transport.send(msg);
        logger.warning("[QUARANTINE] emailed orphan to " + emailTo
                + " (user=" + username + ", endpoint=" + endpoint + ", size=" + size
                + ", attached=" + attachFile + ")");
    }

    /** Open a Bug ticket in the feedback queue so the next /poll-feedback (or remote
     *  trigger) sees the orphan. Filed AS plmadmin; the original user's identity is
     *  recorded in the ticket body. Best-effort — never let a queue write block the
     *  startup sequence. */
    private void fileBugTicket(File data, Map<String, Object> meta) {
        if (supportController == null) {
            logger.warning("[QUARANTINE] SupportController not available — skipping bug ticket file");
            return;
        }
        try {
            String username   = (String) meta.getOrDefault("username", "");
            String endpoint   = (String) meta.getOrDefault("endpoint", "");
            String origName   = (String) meta.getOrDefault("originalFilename", data.getName());
            Object sizeObj    = meta.get("sizeBytes");
            long size = sizeObj instanceof Number ? ((Number) sizeObj).longValue() : data.length();
            String startedAt  = (String) meta.getOrDefault("startedAt", "");
            Object params     = meta.getOrDefault("requestParams", new LinkedHashMap<>());
            String build      = configBanner != null ? configBanner.getBuildLabel() : "unknown";

            StringBuilder text = new StringBuilder();
            text.append("[Auto-filed crash recovery] Orphaned upload after JVM restart.\n\n");
            text.append("The previous JVM stopped responding while processing a user upload. ");
            text.append("The file was saved to disk before processing began and is attached.\n\n");
            text.append("User: ").append(resolveUserLabel(username)).append("\n");
            text.append("Endpoint: ").append(endpoint).append("\n");
            text.append("File: ").append(origName).append(" (").append(formatBytes(size)).append(")\n");
            text.append("Upload started: ").append(startedAt).append("\n");
            text.append("Request params: ").append(params).append("\n");
            text.append("Restart build: ").append(build).append("\n");
            text.append("Recovered at: ").append(Instant.now()).append("\n\n");
            text.append("Likely cause: heap pressure / OOM from the volume of input. ");
            text.append("See toolkit log for the matching HEAP-ALERT and shutdown lines.");

            String contentType = guessContentType(origName);
            com.sandisk.plm.tracker.util.FileMultipartFile mpf =
                    new com.sandisk.plm.tracker.util.FileMultipartFile(data, origName, contentType);

            Map<String, Object> r = supportController.submitFeedbackInternal(
                    "Bug Report",
                    text.toString(),
                    mpf,
                    "plmadmin",
                    "PLM Toolkit (auto)",
                    "pdl-plm-admin@sandisk.com");
            Object ok = r.get("success");
            if (Boolean.TRUE.equals(ok)) {
                logger.warning("[QUARANTINE] filed bug ticket " + r.get("ptId") + " for orphan");
            } else {
                logger.warning("[QUARANTINE] feedback queue rejected orphan: " + r);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[QUARANTINE] failed to file bug ticket: " + e.getMessage(), e);
        }
    }

    private static String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        return "application/octet-stream";
    }

    private static String kv(String k, Object v) {
        return "<tr><td style=\"padding:5px 8px 5px 0;color:#6B7280;font-weight:600;width:35%;white-space:nowrap;\">"
                + esc(k) + "</td><td style=\"padding:5px 0;word-break:break-word;\">"
                + esc(String.valueOf(v == null ? "" : v)) + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0);
        return String.format(Locale.US, "%.1f GB", n / 1024.0 / 1024.0 / 1024.0);
    }

    private static String makeTicket(String username, String origName) {
        String ts = Instant.now().atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        long seq = COUNTER.incrementAndGet();
        String safeUser = sanitize(nz(username));
        String safeName = sanitize(origName == null ? "upload.bin" : origName);
        return ts + "_" + seq + "_" + safeUser + "_" + safeName;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9._\\-]", "_");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Turn a raw employeeID like {@code 1359} into a human-readable
     * {@code "Jimmy Sessumes (1359)"} via LDAP. Falls back to just the id
     * if the lookup misses (service account, ex-employee, LDAP unavailable).
     */
    String resolveUserLabel(String username) {
        if (username == null || username.isEmpty()) return "";
        if (ldapAuthService == null) return username;
        try {
            LdapAuthService.UserInfo info = ldapAuthService.lookupUserById(username);
            if (info == null || info.displayName == null || info.displayName.isEmpty()) {
                info = ldapAuthService.lookupUserByUsername(username);
            }
            if (info != null && info.displayName != null && !info.displayName.isEmpty()) {
                return info.displayName + " (" + username + ")";
            }
        } catch (Exception e) {
            logger.fine("[QUARANTINE] resolveUserLabel failed for " + username + ": " + e.getMessage());
        }
        return username;
    }

    /**
     * Derive a short feature tag from a request URI for the file archive index.
     * Maps the second URI segment (e.g. {@code /api/agile-lookup/upload} → {@code agile-lookup}),
     * or falls back to {@code unknown} if the shape doesn't match {@code /api/.../...}.
     */
    private static String featureFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return "unknown";
        String s = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        String[] parts = s.split("/", 4);
        // parts[0] = "api", parts[1] = feature, parts[2+] = sub-path
        if (parts.length >= 2 && "api".equalsIgnoreCase(parts[0])) {
            return parts[1].toLowerCase();
        }
        return "unknown";
    }
}
