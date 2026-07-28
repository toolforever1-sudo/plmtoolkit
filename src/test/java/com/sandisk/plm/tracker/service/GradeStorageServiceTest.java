package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradeStorageServiceTest {

    private GradeStorageService svc;

    @BeforeEach
    void setup() throws Exception {
        Path dir = Files.createTempDirectory("grade-test");
        svc = new GradeStorageService(dir.toString());
    }

    @Test
    void saveAndList_roundTrips() {
        GradeModels.GradeRecord r = new GradeModels.GradeRecord();
        r.id = "gd_1"; r.kind = "item"; r.targetEcn = "ECN-119003";
        r.gradeBefore = "D+"; r.gradeAfter = "C"; r.scoreBefore = 68; r.scoreAfter = 74;
        r.actorAd = "8252"; r.createdAt = 1000L;
        svc.saveRecord(r);
        List<GradeModels.GradeRecord> all = svc.listRecords();
        assertEquals(1, all.size());
        assertEquals("ECN-119003", all.get(0).targetEcn);
    }

    @Test
    void listRecords_sortsByCreatedAtDesc() {
        GradeModels.GradeRecord a = new GradeModels.GradeRecord();
        a.id = "gd_a"; a.kind = "item"; a.createdAt = 100L;
        GradeModels.GradeRecord b = new GradeModels.GradeRecord();
        b.id = "gd_b"; b.kind = "rule"; b.ruleKey = "overdue"; b.createdAt = 200L;
        svc.saveRecord(a);
        svc.saveRecord(b);
        List<GradeModels.GradeRecord> all = svc.listRecords();
        assertEquals(2, all.size());
        assertEquals("gd_b", all.get(0).id);   // newest first
    }

    @Test
    void outbox_appendsAndReads() {
        GradeModels.OutboxEntry e = new GradeModels.OutboxEntry();
        e.id = "o1"; e.to = "pdl-plm-admin@sandisk.com"; e.subject = "x";
        e.body = Arrays.asList("a", "b"); e.sentAt = 1L;
        svc.appendOutbox(e);
        GradeModels.OutboxEntry e2 = new GradeModels.OutboxEntry();
        e2.id = "o2"; e2.to = "pdl-plm-it@sandisk.com"; e2.subject = "y"; e2.sentAt = 2L; e2.it = true;
        svc.appendOutbox(e2);
        List<GradeModels.OutboxEntry> box = svc.listOutbox();
        assertEquals(2, box.size());
        assertEquals("o2", box.get(0).id);      // newest first
        assertTrue(box.get(0).it);
    }

    @Test
    void deleteRecord_removes() {
        GradeModels.GradeRecord r = new GradeModels.GradeRecord();
        r.id = "gd_2"; r.kind = "rule"; r.ruleKey = "overdue"; r.createdAt = 5L;
        svc.saveRecord(r);
        svc.deleteRecord("gd_2");
        assertTrue(svc.listRecords().isEmpty());
    }

    @Test
    void lastGrade_roundTrips() {
        svc.saveLastGrade("C", 74);
        GradeModels.GradeRecord lg = svc.readLastGrade();
        assertNotNull(lg);
        assertEquals("C", lg.gradeAfter);
        assertEquals(74, lg.scoreAfter);
    }
}
