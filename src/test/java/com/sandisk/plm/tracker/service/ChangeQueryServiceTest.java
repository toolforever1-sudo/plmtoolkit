package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.ChangeRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChangeQueryServiceTest {

    private ChangeQueryService service;

    @BeforeEach
    void setUp() {
        service = new ChangeQueryService();
    }

    @Test
    void parseDetails_standardFormat() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                "<Inv/Planning.Subcontractors>was<C002, R010>is<C002, R010, C067>",
                new Timestamp(System.currentTimeMillis()), "Zhu, Peter", "B");
        assertNotNull(rec);
        assertEquals("Inv/Planning.Subcontractors", rec.getFieldName());
        assertEquals("C002, R010", rec.getOldValue());
        assertEquals("C002, R010, C067", rec.getNewValue());
    }

    @Test
    void parseDetails_blankOldValue() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                "<Description>was<>is<New description>",
                new Timestamp(System.currentTimeMillis()), "Kim, Sarah", "A");
        assertNotNull(rec);
        assertEquals("(blank)", rec.getOldValue());
        assertEquals("New description", rec.getNewValue());
    }

    @Test
    void parseDetails_blankNewValue() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                "<Description>was<Old description>is<>",
                new Timestamp(System.currentTimeMillis()), "Kim, Sarah", "A");
        assertNotNull(rec);
        assertEquals("Old description", rec.getOldValue());
        assertEquals("(blank)", rec.getNewValue());
    }

    @Test
    void parseDetails_truncatedFormat() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                "<Description>was<Very long old value>is<Very long new val",
                new Timestamp(System.currentTimeMillis()), "Li, Wei", "C");
        assertNotNull(rec);
        assertEquals("Description", rec.getFieldName());
        assertEquals("Very long old value", rec.getOldValue());
        assertEquals("Very long new val", rec.getNewValue());
    }

    @Test
    void parseDetails_nullDetails_returnsNull() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                null, new Timestamp(System.currentTimeMillis()), "User", "A");
        assertNull(rec);
    }

    @Test
    void parseDetails_unparseable_returnsNull() {
        ChangeRecord rec = service.parseDetails("01-12345-00",
                "some random text", new Timestamp(System.currentTimeMillis()), "User", "A");
        assertNull(rec);
    }

    @Test
    void filterByField_singleToken_partialMatch() {
        ChangeRecord rec = makeRecord("Inv/Planning.Subcontractors", "old", "new");
        List<ChangeRecord> result = service.filterByField(Arrays.asList(rec), "Subcon");
        assertEquals(1, result.size());
    }

    @Test
    void filterByField_commaOr_matchesAny() {
        ChangeRecord rec1 = makeRecord("Description", "old", "new");
        ChangeRecord rec2 = makeRecord("Inv/Planning.Subcontractors", "old", "new");
        ChangeRecord rec3 = makeRecord("Lifecycle Phase", "old", "new");
        List<ChangeRecord> result = service.filterByField(
                Arrays.asList(rec1, rec2, rec3), "Subcon,Lifecycle");
        assertEquals(2, result.size());
    }

    @Test
    void filterByField_caseInsensitive() {
        ChangeRecord rec = makeRecord("Description", "old", "new");
        List<ChangeRecord> result = service.filterByField(Arrays.asList(rec), "description");
        assertEquals(1, result.size());
    }

    @Test
    void filterByField_emptyFilter_returnsAll() {
        ChangeRecord rec = makeRecord("Description", "old", "new");
        List<ChangeRecord> result = service.filterByField(Arrays.asList(rec), "");
        assertEquals(1, result.size());
    }

    @Test
    void filterByField_nullFilter_returnsAll() {
        ChangeRecord rec = makeRecord("Description", "old", "new");
        List<ChangeRecord> result = service.filterByField(Arrays.asList(rec), null);
        assertEquals(1, result.size());
    }

    @Test
    void filterByOldContains_singleToken() {
        ChangeRecord rec1 = makeRecord("Field", "C002, R010", "C002, R010, C067");
        ChangeRecord rec2 = makeRecord("Field", "R020", "R020, R010");
        List<ChangeRecord> result = service.filterByOldContains(
                Arrays.asList(rec1, rec2), "C002");
        assertEquals(1, result.size());
        assertEquals("C002, R010", result.get(0).getOldValue());
    }

    @Test
    void filterByOldContains_commaOr() {
        ChangeRecord rec1 = makeRecord("Field", "C002, R010", "new");
        ChangeRecord rec2 = makeRecord("Field", "R020", "new");
        ChangeRecord rec3 = makeRecord("Field", "C067", "new");
        List<ChangeRecord> result = service.filterByOldContains(
                Arrays.asList(rec1, rec2, rec3), "C002, R020");
        assertEquals(2, result.size());
    }

    @Test
    void filterByNewContains_commaOr() {
        ChangeRecord rec1 = makeRecord("Field", "old", "C002, R010, C067");
        ChangeRecord rec2 = makeRecord("Field", "old", "R020, R010");
        ChangeRecord rec3 = makeRecord("Field", "old", "C039");
        List<ChangeRecord> result = service.filterByNewContains(
                Arrays.asList(rec1, rec2, rec3), "C067,R020");
        assertEquals(2, result.size());
    }

    @Test
    void filterByNewContains_emptyFilter_returnsAll() {
        ChangeRecord rec = makeRecord("Field", "old", "new");
        List<ChangeRecord> result = service.filterByNewContains(Arrays.asList(rec), "");
        assertEquals(1, result.size());
    }

    @Test
    void filterNoNetChange_removesRoundTrip() {
        Timestamp t1 = new Timestamp(1000L);
        Timestamp t2 = new Timestamp(2000L);
        ChangeRecord rec1 = new ChangeRecord("01-100", "Field", "X", "Y", t1, "User", "A");
        ChangeRecord rec2 = new ChangeRecord("01-100", "Field", "Y", "X", t2, "User", "A");
        List<ChangeRecord> result = service.filterNoNetChange(Arrays.asList(rec1, rec2));
        assertEquals(0, result.size());
    }

    @Test
    void filterNoNetChange_keepsRealChange() {
        Timestamp t1 = new Timestamp(1000L);
        Timestamp t2 = new Timestamp(2000L);
        ChangeRecord rec1 = new ChangeRecord("01-100", "Field", "X", "Y", t1, "User", "A");
        ChangeRecord rec2 = new ChangeRecord("01-100", "Field", "Y", "Z", t2, "User", "A");
        List<ChangeRecord> result = service.filterNoNetChange(Arrays.asList(rec1, rec2));
        assertEquals(2, result.size());
    }

    @Test
    void filterNoNetChange_differentFields_independent() {
        Timestamp t1 = new Timestamp(1000L);
        ChangeRecord rec1 = new ChangeRecord("01-100", "FieldA", "X", "Y", t1, "User", "A");
        ChangeRecord rec2 = new ChangeRecord("01-100", "FieldB", "M", "M", t1, "User", "A");
        List<ChangeRecord> result = service.filterNoNetChange(Arrays.asList(rec1, rec2));
        assertEquals(1, result.size());
        assertEquals("FieldA", result.get(0).getFieldName());
    }

    private ChangeRecord makeRecord(String fieldName, String oldValue, String newValue) {
        return new ChangeRecord("01-TEST", fieldName, oldValue, newValue,
                new Timestamp(System.currentTimeMillis()), "TestUser", "A");
    }
}
