package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Sends the Single/Sole Source xlsx via SMTP. Uses the shared
 * {@link EmailTemplateService} for IBM-Plex / sandisk-pill / dark-mode layout
 * per CLAUDE.md email guidelines.
 */
@Service
public class SingleSoleSourceEmailService {

    @Value("${mail.smtp.host}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;

    @Value("${app.singlesole.email.from}")    private String fromAddr;
    @Value("${app.singlesole.email.to}")      private String defaultTo;
    @Value("${app.singlesole.email.cc:}")     private String defaultCc;
    @Value("${app.singlesole.email.subject}") private String subjectBase;

    @Autowired private EmailTemplateService tpl;

    /** Sends to the configured To/Cc with the xlsx attached. */
    public void send(File attachment, SingleSoleSourceRunResult res) throws Exception {
        send(defaultTo, defaultCc, attachment, res);
    }

    /** Sends to a custom recipient (used by the "Send Test Email" UI button). */
    public void send(String toAddr, String ccAddr, File attachment, SingleSoleSourceRunResult res) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromAddr));
        for (String r : toAddr.split("[,;]\\s*")) {
            String t = r.trim();
            if (!t.isEmpty()) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(t));
        }
        if (ccAddr != null && !ccAddr.trim().isEmpty()) {
            for (String r : ccAddr.split("[,;]\\s*")) {
                String t = r.trim();
                if (!t.isEmpty()) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(t));
            }
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy"));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(subjectBase + " \u00b7 " + date));

        // PT-65 follow-up: 3 KPI tiles matching the 3-tab Excel layout
        // (Needed / Single Source / Sole Source), instead of the prior combined
        // Provided tile.
        String kpiRow = tpl.kpiRow(
                tpl.kpiTile("Designation Needed", String.valueOf(res.designationNeededCount), null),
                tpl.kpiTile("Single Source",     String.valueOf(res.singleSourceCount),       null),
                tpl.kpiTile("Sole Source",       String.valueOf(res.soleSourceCount),         null)
        );
        String body =
            "<p style='margin:0 0 10px 0;'>Dear Agile User,</p>" +
            "<p style='margin:0 0 16px 0;'>Attached is the report of Single/Sole Source components in Agile. " +
            "Please review and respond back with changes you may need to the currently assigned single-sole source " +
            "identification under the &lsquo;Single/Sole Source (To Be)&rsquo; column.</p>";
        int total = res.designationNeededCount + res.designationProvidedCount;
        String attachStrip = tpl.attachmentStrip(attachment.getName(), total + " rows");

        // wrap signature: section, tag, eyebrow, heroTitle, heroSub,
        //                 kpiHtml, bodyHtml, ctaText, ctaHref, ctaHint,
        //                 attachHtml, metaStripHtml, footerLine
        String html = tpl.wrap(
                "Single/Sole Source",                          // section (nav)
                "Monthly",                                      // tag (status pill)
                "MONTHLY REVIEW",                               // eyebrow
                "Single-Sole Source Report",                    // heroTitle
                "Components flagged for single/sole supplier review", // heroSub
                kpiRow,                                         // kpiHtml
                body,                                           // bodyHtml
                null, null, null,                               // no CTA
                attachStrip,                                    // attachHtml
                null,                                           // auto meta strip
                null                                            // default footer
        );

        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=utf-8");
        mp.addBodyPart(htmlPart);

        MimeBodyPart att = new MimeBodyPart();
        DataSource ds = new FileDataSource(attachment);
        att.setDataHandler(new DataHandler(ds));
        att.setFileName(attachment.getName());
        mp.addBodyPart(att);

        msg.setContent(mp);
        Transport.send(msg);
    }
}
