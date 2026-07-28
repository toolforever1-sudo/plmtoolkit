package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POJO unit tests for AnnouncementService — file store round-trip, revision
 * bump semantics, the test-before-send gate, scheduler due-check, credential
 * scrubbing, and banner selection. Collaborator services are not exercised
 * (null-injected); audience resolution is covered by the e2e pass.
 */
public class AnnouncementServiceTest {

    @TempDir
    Path tmp;

    private AnnouncementService fresh() {
        AnnouncementService svc = new AnnouncementService(
                tmp.toString(), null, null, null, null, null);
        svc.init();
        return svc;
    }

    private AnnouncementService.Announcement draft(AnnouncementService svc) {
        AnnouncementService.Announcement patch = new AnnouncementService.Announcement();
        patch.subject = "Toolkit is moving";
        patch.draft = "rough notes";
        patch.audienceType = "everLoggedIn";
        patch.channelBanner = Boolean.TRUE;
        return svc.upsert(patch, "8252", "Jindal, Vikas");
    }

    // ---- store + revisions ------------------------------------------

    @Test
    public void upsertCreatesDraftWithRevisionZero() {
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);
        assertNotNull(a.id);
        assertEquals("draft", a.status);
        assertEquals(0, a.revision);
        assertEquals(-1, a.testedRevision);
        assertEquals("8252", a.createdBy);
    }

    @Test
    public void contentEditBumpsRevisionButRoughDraftEditDoesNot() {
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);

        // rough-notes edit: what goes out (subject/bodyHtml/audience) unchanged
        AnnouncementService.Announcement p1 = new AnnouncementService.Announcement();
        p1.id = a.id;
        p1.subject = a.subject;
        p1.draft = "reworded rough notes";
        p1.audienceType = a.audienceType;
        assertEquals(0, svc.upsert(p1, "8252", "Jindal, Vikas").revision);

        // subject edit: bumps
        AnnouncementService.Announcement p2 = new AnnouncementService.Announcement();
        p2.id = a.id;
        p2.subject = "New subject";
        p2.draft = "reworded rough notes";
        p2.audienceType = a.audienceType;
        assertEquals(1, svc.upsert(p2, "8252", "Jindal, Vikas").revision);

        // bodyHtml edit: bumps
        AnnouncementService.Announcement p3 = new AnnouncementService.Announcement();
        p3.id = a.id;
        p3.subject = "New subject";
        p3.draft = "reworded rough notes";
        p3.bodyHtml = "<p>Hi ${firstName}</p>";
        p3.audienceType = a.audienceType;
        assertEquals(2, svc.upsert(p3, "8252", "Jindal, Vikas").revision);
    }

    @Test
    public void partialPatchLeavesUnmentionedFieldsAlone() {
        // Regression: the generate action patches only bodyHtml — a "" field
        // initializer used to wipe the stored draft/subject through nulls.
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);
        AnnouncementService.Announcement p = new AnnouncementService.Announcement();
        p.id = a.id;
        p.bodyHtml = "<p>Hi ${firstName}</p>";
        AnnouncementService.Announcement out = svc.upsert(p, "8252", "Jindal, Vikas");
        assertEquals("Toolkit is moving", out.subject, "subject untouched");
        assertEquals("rough notes", out.draft, "draft untouched");
        assertEquals("everLoggedIn", out.audienceType, "audience untouched");
        assertEquals("<p>Hi ${firstName}</p>", out.bodyHtml);
        assertEquals(1, out.revision, "bodyHtml change bumps revision");
        assertEquals(Boolean.TRUE, out.channelEmail, "channel defaults survive partial patch");
        assertEquals(Boolean.TRUE, out.channelBanner, "explicit channel choice survives partial patch");
    }

    @Test
    public void persistenceRoundTrip() {
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);
        AnnouncementService svc2 = fresh(); // same tmp dir, new instance
        AnnouncementService.Announcement loaded = svc2.get(a.id);
        assertNotNull(loaded);
        assertEquals("Toolkit is moving", loaded.subject);
    }

    // ---- test-before-send gate ---------------------------------------

    @Test
    public void sendLockedUntilTestedAndRelockedOnEdit() {
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);
        assertFalse(svc.canSend(a), "no test yet");

        svc.markTested(a.id, "8252", "Jindal, Vikas");
        assertTrue(svc.canSend(svc.get(a.id)), "tested current revision");

        AnnouncementService.Announcement p = new AnnouncementService.Announcement();
        p.id = a.id;
        p.subject = "Edited after test";
        p.draft = a.draft;
        p.audienceType = a.audienceType;
        svc.upsert(p, "nalcantara", "Alcantara, Noraida");
        assertFalse(svc.canSend(svc.get(a.id)), "edit invalidates the test");
        assertEquals("nalcantara", svc.get(a.id).lastEditedBy);
        assertTrue(svc.get(a.id).revisions.size() >= 2, "revision trail recorded");
    }

    // ---- scheduler due-check ------------------------------------------

    @Test
    public void isDueMatrix() {
        AnnouncementService.Announcement a = new AnnouncementService.Announcement();
        a.status = "scheduled";
        a.scheduledFor = "2026-07-20T08:00";
        LocalDateTime before = LocalDateTime.of(2026, 7, 20, 7, 59);
        LocalDateTime after = LocalDateTime.of(2026, 7, 20, 8, 1);
        assertFalse(AnnouncementService.isDue(a, before));
        assertTrue(AnnouncementService.isDue(a, after));
        a.status = "sent";
        assertFalse(AnnouncementService.isDue(a, after));
        a.status = "scheduled";
        a.scheduledFor = "garbage";
        assertFalse(AnnouncementService.isDue(a, after));
    }

    // ---- credential scrub ---------------------------------------------

    @Test
    public void scrubRedactsCredentialValues() {
        assertEquals("password: [redacted]",
                AnnouncementService.scrubCredentials("password: hunter2xyz"));
        assertEquals("The password is [redacted] for the box",
                AnnouncementService.scrubCredentials("The password is Abc123! for the box"));
        assertEquals("api key = [redacted]",
                AnnouncementService.scrubCredentials("api key = sk-live-abc123"));
        assertEquals("Bearer token: [redacted]",
                AnnouncementService.scrubCredentials("Bearer token: eyJhbGciOi"));
    }

    @Test
    public void scrubLeavesBenignProseAlone() {
        String benign = "Reset your password via the portal. Login and saved searches carry over.";
        assertEquals(benign, AnnouncementService.scrubCredentials(benign));
        assertNull(AnnouncementService.scrubCredentials(null));
    }

    // ---- banner --------------------------------------------------------

    @Test
    public void bannerPicksNewestActiveAndHonorsDismiss() {
        AnnouncementService svc = fresh();

        AnnouncementService.Announcement a = draft(svc);
        svc.forceSentForTest(a.id, true, LocalDateTime.now().minusDays(2));

        AnnouncementService.Announcement patch = new AnnouncementService.Announcement();
        patch.subject = "Newer one";
        patch.draft = "x";
        patch.audienceType = "everLoggedIn";
        AnnouncementService.Announcement b = svc.upsert(patch, "8252", "Jindal, Vikas");
        svc.forceSentForTest(b.id, true, LocalDateTime.now().minusDays(1));

        AnnouncementService.Announcement banner = svc.activeBannerFor("someone");
        assertNotNull(banner);
        assertEquals(b.id, banner.id, "newest wins");

        svc.dismissBanner(b.id, "someone");
        assertEquals(a.id, svc.activeBannerFor("someone").id, "dismissed falls back to older");
        assertEquals(b.id, svc.activeBannerFor("someoneelse").id, "dismissal is per-user");
    }

    @Test
    public void bannerExpiresAfter14DaysAndSkipsNonBannerChannel() {
        AnnouncementService svc = fresh();
        AnnouncementService.Announcement a = draft(svc);
        svc.forceSentForTest(a.id, true, LocalDateTime.now().minusDays(15));
        assertNull(svc.activeBannerFor("someone"), "stale banner expired");

        AnnouncementService.Announcement patch = new AnnouncementService.Announcement();
        patch.subject = "No banner channel";
        patch.draft = "x";
        patch.audienceType = "everLoggedIn";
        AnnouncementService.Announcement b = svc.upsert(patch, "8252", "Jindal, Vikas");
        svc.forceSentForTest(b.id, false, LocalDateTime.now());
        assertNull(svc.activeBannerFor("someone"), "channelBanner=false never surfaces");
    }
}
