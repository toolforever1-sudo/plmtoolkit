package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmailService {

    private static final Logger logger = Logger.getLogger(EmailService.class.getName());

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.from}")
    private String fromAddress;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private EmailTemplateService emailTemplate;

    public void sendExcelReport(String toEmail, String displayName,
                                 java.util.List<com.sandisk.plm.tracker.model.ChangeRecord> records,
                                 String filterSummary) throws Exception {

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));

        Session session = Session.getInstance(props);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));

        int count = records.size();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd"));
        message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("Field Changes \u00b7 " + count + " rows \u00b7 " + datePart));

        // Extract days from filterSummary (pattern: "Days: N")
        String days = "?";
        Matcher daysMatcher = Pattern.compile("Days:\\s*(\\d+)").matcher(filterSummary);
        if (daysMatcher.find()) {
            days = daysMatcher.group(1);
        }

        // Truncate filterSummary for KPI tile if too long
        String filterShort = filterSummary.length() > 30
                ? filterSummary.substring(0, 27) + "..."
                : filterSummary;

        // Excel attachment
        ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
        excelExportService.export(records, excelOut);
        byte[] excelBytes = excelOut.toByteArray();
        String xlsFilename = "PLM-Changes-" + date + ".xlsx";

        // Determine actual attachment filename (may be zipped)
        String actualFilename = xlsFilename;
        byte[] zippedExcel = zipIfLarge(excelBytes, xlsFilename);
        if (zippedExcel != null) {
            actualFilename = xlsFilename.replace(".xlsx", ".zip");
        }

        // Build HTML body using EmailTemplateService
        String kpiHtml = emailTemplate.kpiRow(
                emailTemplate.kpiTile("Rows", String.valueOf(count), null),
                emailTemplate.kpiTile("Filters", filterShort, null),
                emailTemplate.kpiTile("Window", days + "d", null)
        );

        String bodyHtml = emailTemplate.kvBlock(
                emailTemplate.kvRow("Filters", filterSummary)
        );

        String attachHtml = emailTemplate.attachmentStrip(actualFilename, count + " rows");

        String htmlBody = emailTemplate.wrap(
                "Field Changes",           // section
                "Report",                  // tag
                "Report \u00b7 " + date,   // eyebrow
                count + " field changes, ready for review.",  // heroTitle
                "Full Excel is attached. Filters: " + filterSummary,  // heroSub
                kpiHtml,                   // kpiHtml
                bodyHtml,                  // bodyHtml
                "Open in PLM Toolkit",     // ctaText
                "http://uls-ep-aglipccb:8090/index.html",  // ctaHref
                null,                      // ctaHint
                attachHtml,                // attachHtml
                null,                      // metaStripHtml (auto-generate)
                null                       // footerLine (default)
        );

        // Body part
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setContent(htmlBody, "text/html; charset=utf-8");

        MimeBodyPart attachPart = new MimeBodyPart();
        if (zippedExcel != null) {
            String zipFilename = xlsFilename.replace(".xlsx", ".zip");
            DataSource ds = new ByteArrayDataSource(zippedExcel, "application/zip");
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(zipFilename);
        } else {
            DataSource ds = new ByteArrayDataSource(excelBytes,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(xlsFilename);
        }

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachPart);
        message.setContent(multipart);

        Transport.send(message);
        logger.info("Email sent to " + toEmail + " with " + count + " records");
    }

    public void sendBomReport(String toEmail, String displayName, byte[] excelData,
                               String filename, String title, int rowCount) throws Exception {

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));

        Session session = Session.getInstance(props);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd"));
        String shortTitle = title.replace("BOM Explosion - ", "").replace("BOM Where Used - ", "");
        message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("BOM export \u00b7 " + shortTitle + " \u00b7 " + rowCount + " parts"));

        // Determine mode from title
        String mode = title.contains("Explosion") ? "Explosion"
                    : title.contains("Where Used") ? "Where Used" : "Export";

        // Determine actual attachment filename (may be zipped)
        String actualFilename = filename;
        byte[] zipped = zipIfLarge(excelData, filename);
        if (zipped != null) {
            actualFilename = filename.replace(".xlsx", ".zip");
        }

        // Build HTML body using EmailTemplateService
        String kpiHtml = emailTemplate.kpiRow(
                emailTemplate.kpiTile("Parts", String.valueOf(rowCount), null),
                emailTemplate.kpiTile("Mode", mode, null),
                emailTemplate.kpiTile("Generated", datePart, null)
        );

        String attachHtml = emailTemplate.attachmentStrip(actualFilename, rowCount + " rows");

        String htmlBody = emailTemplate.wrap(
                "BOM Explorer",            // section
                "Report",                  // tag
                "Report \u00b7 " + date,   // eyebrow
                title,                     // heroTitle
                rowCount + " rows captured.",  // heroSub
                kpiHtml,                   // kpiHtml
                null,                      // bodyHtml
                "Open in PLM Toolkit",     // ctaText
                "http://uls-ep-aglipccb:8090/index.html",  // ctaHref
                null,                      // ctaHint
                attachHtml,                // attachHtml
                null,                      // metaStripHtml (auto-generate)
                null                       // footerLine (default)
        );

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setContent(htmlBody, "text/html; charset=utf-8");

        MimeBodyPart attachPart = new MimeBodyPart();
        if (zipped != null) {
            String zipFilename = filename.replace(".xlsx", ".zip");
            DataSource ds = new ByteArrayDataSource(zipped, "application/zip");
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(zipFilename);
        } else {
            DataSource ds = new ByteArrayDataSource(excelData,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(filename);
        }

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachPart);
        message.setContent(multipart);

        Transport.send(message);
        logger.info("BOM report emailed to " + toEmail + " (" + rowCount + " rows)");
    }

    /**
     * Send a debug analysis report as a branded HTML email.
     */
    public void sendDebugReport(String toEmail, String subject,
                                 String rootCause, int tracesFound, int sourcesResolved,
                                 int totalRefs, long timeMs, String analysisMarkdown) throws Exception {

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));

        Session session = Session.getInstance(props);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
        message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subject));

        String secs = String.format("%.1f", timeMs / 1000.0);

        String kpiHtml = emailTemplate.kpiRow(
                emailTemplate.kpiTile("Traces", String.valueOf(tracesFound), null),
                emailTemplate.kpiTile("Resolved", sourcesResolved + "/" + totalRefs, null),
                emailTemplate.kpiTile("Time", secs + "s", null)
        );

        // Convert analysis markdown to simple HTML
        String analysisHtml = "<div style=\"line-height:1.7;font-size:14px;color:#333;\">"
                + escHtml(analysisMarkdown)
                    .replace("\n\n", "</p><p style=\"margin:0 0 10px;\">")
                    .replace("```java\n", "<pre style=\"background:#1a1a2e;color:#e0e0e0;padding:14px;border-radius:6px;font-size:12px;overflow-x:auto;\">")
                    .replace("```\n", "<pre style=\"background:#1a1a2e;color:#e0e0e0;padding:14px;border-radius:6px;font-size:12px;overflow-x:auto;\">")
                    .replace("```", "</pre>")
                + "</div>";

        String htmlBody = emailTemplate.wrap(
                "Debug Assistant",           // section
                "Analysis",                  // tag
                "Debug Report",              // eyebrow
                rootCause,                   // heroTitle
                tracesFound + " traces · " + sourcesResolved + " of " + totalRefs + " sources resolved · " + secs + "s",
                kpiHtml,
                analysisHtml,
                "Open Debug Assistant",
                "http://uls-ep-aglipccb:8090/debug.html",
                null, null, null, null
        );

        message.setContent(htmlBody, "text/html; charset=utf-8");
        Transport.send(message);
        logger.info("Debug report emailed to " + toEmail);
    }

    private byte[] zipIfLarge(byte[] data, String filename) {
        if (data.length <= 5 * 1024 * 1024) return null; // no zip needed
        try {
            ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(zipOut);
            zos.putNextEntry(new java.util.zip.ZipEntry(filename));
            zos.write(data);
            zos.closeEntry();
            zos.close();
            byte[] zipped = zipOut.toByteArray();
            // Only use zip if it's significantly smaller (at least 30% reduction)
            if (zipped.length < data.length * 0.7) {
                logger.info("Zipped attachment: " + data.length + " -> " + zipped.length + " bytes (" +
                    Math.round(100.0 * zipped.length / data.length) + "%)");
                return zipped;
            }
        } catch (Exception e) {
            logger.warning("Failed to zip attachment: " + e.getMessage());
        }
        return null; // send unzipped
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
