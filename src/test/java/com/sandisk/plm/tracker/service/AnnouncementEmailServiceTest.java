package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POJO unit tests for the announcement email renderer — template fill,
 * first-name derivation, and per-recipient personalization. No SMTP.
 */
public class AnnouncementEmailServiceTest {

    // ---- firstNameOf -------------------------------------------------

    @Test
    public void firstNameFromCommaFormat() {
        assertEquals("Vikas", AnnouncementEmailService.firstNameOf("Jindal, Vikas"));
    }

    @Test
    public void firstNameFromPlainFormat() {
        assertEquals("Vikas", AnnouncementEmailService.firstNameOf("Vikas Jindal"));
    }

    @Test
    public void firstNameFallsBackToThere() {
        assertEquals("there", AnnouncementEmailService.firstNameOf(null));
        assertEquals("there", AnnouncementEmailService.firstNameOf("  "));
    }

    @Test
    public void firstNameStripsSystemIdParens() {
        // "Zhu, Peter (14759)" style — id must never leak into the greeting
        assertEquals("Peter", AnnouncementEmailService.firstNameOf("Zhu, Peter (14759)"));
    }

    // ---- renderEmail (template wrap) ---------------------------------

    @Test
    public void renderEmailFillsSubjectDateAndBody() {
        AnnouncementEmailService svc = new AnnouncementEmailService();
        String html = svc.renderEmail("Toolkit is moving", "<p>Hi ${firstName} &mdash; body here</p>", "July 8, 2026");
        assertTrue(html.contains("Toolkit is moving"), "headline");
        assertTrue(html.contains("July 8, 2026"), "date label");
        assertTrue(html.contains("body here"), "body slot");
        // ${firstName} must SURVIVE the wrap — it is personalized per recipient later
        assertTrue(html.contains("${firstName}"), "firstName token preserved");
        // template chrome
        assertTrue(html.contains("PLM Toolkit"), "header brand");
        assertTrue(html.contains("Sent by PLM IT via PLM Toolkit"), "footer strip");
        assertFalse(html.contains("${subject}"), "no unresolved subject");
        assertFalse(html.contains("${dateLabel}"), "no unresolved date");
        assertFalse(html.contains("${bodyHtml}"), "no unresolved body");
    }

    // ---- personalize -------------------------------------------------

    @Test
    public void personalizeReplacesFirstNameAndStripsLeftovers() {
        String out = AnnouncementEmailService.personalize(
                "<p>Hi ${firstName} &mdash; note ${straggler}</p>", "Vikas");
        assertEquals("<p>Hi Vikas &mdash; note </p>", out);
    }

    @Test
    public void personalizeNullSafe() {
        assertNull(AnnouncementEmailService.personalize(null, "X"));
        assertEquals("<p>Hi there</p>",
                AnnouncementEmailService.personalize("<p>Hi ${firstName}</p>", null));
    }
}
