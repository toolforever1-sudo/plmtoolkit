package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.BomRedlineRow;
import com.sandisk.plm.tracker.model.EcoTimelineRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EcoTimelineClassifierTest {

    private EcoTimelineClassifier classifier;
    private Timestamp from;   // window start
    private Timestamp to;     // window end
    private Timestamp inWin;  // a release date inside the window

    @BeforeEach
    void setUp() {
        classifier = new EcoTimelineClassifier();
        from = new Timestamp(1_000_000L);
        to   = new Timestamp(9_000_000L);
        inWin = new Timestamp(5_000_000L);
    }

    private BomRedlineRow addedRow(long id, String comp, String eco) {
        BomRedlineRow r = new BomRedlineRow();
        r.bomRowId = id; r.priorBom = 0; r.componentPn = comp; r.componentDesc = comp + " desc";
        r.quantity = "1"; r.findNumber = "10"; r.notes = "";
        r.changeInNum = eco; r.changeInRd = inWin;
        return r;
    }

    @Test
    void addedEvent_carriesChangeInDescriptionAndStatus() {
        BomRedlineRow r = addedRow(100, "ABC-001", "ECO-1");
        r.changeInDesc = "Qualify second-source NAND";
        r.changeInStatus = "Released";
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1", 1, List.of(r), from, to);
        assertEquals(1, out.size());
        assertEquals("Qualify second-source NAND", out.get(0).getEcoDescription());
        assertEquals("Released", out.get(0).getEcoStatus());
    }

    @Test
    void removedEvent_carriesChangeOutDescriptionAndStatus() {
        BomRedlineRow r = new BomRedlineRow();
        r.bomRowId = 200; r.priorBom = 0; r.componentPn = "ABC-002"; r.componentDesc = "d";
        r.quantity = "2"; r.findNumber = "20"; r.notes = "";
        r.changeOutNum = "ECO-2"; r.changeOutRd = inWin;
        r.changeOutDesc = "Drop obsolete part"; r.changeOutStatus = "Released";
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1", 1, List.of(r), from, to);
        assertEquals(1, out.size());
        assertEquals("Drop obsolete part", out.get(0).getEcoDescription());
        assertEquals("Released", out.get(0).getEcoStatus());
    }

    @Test
    void modifyEvent_carriesChangeDescription() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "2"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-3"; removed.changeOutRd = inWin;
        removed.changeOutDesc = "Increase decoupling cap"; removed.changeOutStatus = "Released";

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "4"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-3"; added.changeInRd = inWin;
        added.changeInDesc = "Increase decoupling cap"; added.changeInStatus = "Released";

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1", 1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals("Increase decoupling cap", out.get(0).getEcoDescription());
        assertEquals("Released", out.get(0).getEcoStatus());
    }

    @Test
    void pureAdd_isAdded() {
        List<BomRedlineRow> rows = new ArrayList<>();
        rows.add(addedRow(100, "ABC-001", "ECO-1"));
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, rows, from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.ADDED, out.get(0).getChangeType());
        assertEquals("ABC-001", out.get(0).getComponent());
        assertEquals("ECO-1", out.get(0).getEcoNumber());
        assertEquals("ASSY-1", out.get(0).getParentAssembly());
        assertEquals("SKU / ASSY-1", out.get(0).getPath());
        assertEquals(1, out.get(0).getLevel());
    }

    @Test
    void pureRemove_isRemoved() {
        BomRedlineRow r = new BomRedlineRow();
        r.bomRowId = 200; r.priorBom = 0; r.componentPn = "ABC-002"; r.componentDesc = "d";
        r.quantity = "2"; r.findNumber = "20"; r.notes = "";
        r.changeOutNum = "ECO-2"; r.changeOutRd = inWin;
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",2, List.of(r), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.REMOVED, out.get(0).getChangeType());
        assertEquals("ABC-002", out.get(0).getComponent());
    }

    @Test
    void quantityModify_pairsViaPriorBom() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "2"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-3"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "4"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-3"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        EcoTimelineRow row = out.get(0);
        assertEquals(EcoTimelineClassifier.QUANTITY_CHANGED, row.getChangeType());
        assertTrue(row.getDetail().contains("2"));
        assertTrue(row.getDetail().contains("4"));
        assertEquals("ECO-3", row.getEcoNumber());
    }

    @Test
    void primaryNumberModify_componentReplaced() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "old";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-4"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "DEF-002"; added.componentDesc = "new";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-4"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.PRIMARY_NUMBER_CHANGED, out.get(0).getChangeType());
        assertEquals("DEF-002", out.get(0).getComponent());        // shows the new component
        assertTrue(out.get(0).getDetail().contains("ABC-001"));
        assertTrue(out.get(0).getDetail().contains("DEF-002"));
    }

    @Test
    void multiFieldModify_isModified() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-5"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "3"; added.findNumber = "99"; added.notes = "";   // qty AND find# changed
        added.changeInNum = "ECO-5"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.MODIFIED, out.get(0).getChangeType());
        assertTrue(out.get(0).getDetail().contains("Qty"));
        assertTrue(out.get(0).getDetail().contains("Find#"));
    }

    @Test
    void outOfWindow_producesNothing() {
        BomRedlineRow r = addedRow(100, "ABC-001", "ECO-9");
        r.changeInRd = new Timestamp(100L);   // before window start
        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(r), from, to);
        assertTrue(out.isEmpty());
    }

    @Test
    void refDesOnlyModify_isSkipped() {
        // Pair where NO tracked field differs (only an untracked field like ref-des
        // would have changed) must produce no row — ref-des is out of scope.
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "n";
        removed.changeOutNum = "ECO-6"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "n";   // identical tracked fields
        added.changeInNum = "ECO-6"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertTrue(out.isEmpty());
    }

    @Test
    void notesOnlyModify_isNotesChanged() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "old note";
        removed.changeOutNum = "ECO-7"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "new note";
        added.changeInNum = "ECO-7"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.NOTES_CHANGED, out.get(0).getChangeType());
    }

    @Test
    void findNumberOnlyModify_isFindNumberChanged() {
        BomRedlineRow removed = new BomRedlineRow();
        removed.bomRowId = 10; removed.componentPn = "ABC-001"; removed.componentDesc = "d";
        removed.quantity = "1"; removed.findNumber = "10"; removed.notes = "";
        removed.changeOutNum = "ECO-8"; removed.changeOutRd = inWin;

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-001"; added.componentDesc = "d";
        added.quantity = "1"; added.findNumber = "20"; added.notes = "";
        added.changeInNum = "ECO-8"; added.changeInRd = inWin;

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(removed, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.FIND_NUMBER_CHANGED, out.get(0).getChangeType());
    }

    @Test
    void addWithPriorBomButNoInWindowRemove_fallsThroughToAdded() {
        // The row this add replaces was retired by a change that released OUTSIDE
        // the window, so there is no in-window "removed" to pair with. The add
        // must still be reported as Added (not silently dropped).
        BomRedlineRow oldRow = new BomRedlineRow();
        oldRow.bomRowId = 10; oldRow.componentPn = "ABC-001"; oldRow.componentDesc = "d";
        oldRow.quantity = "1"; oldRow.findNumber = "10"; oldRow.notes = "";
        oldRow.changeOutNum = "ECO-OLD"; oldRow.changeOutRd = new Timestamp(100L); // out of window

        BomRedlineRow added = new BomRedlineRow();
        added.bomRowId = 11; added.priorBom = 10; added.componentPn = "ABC-002"; added.componentDesc = "d2";
        added.quantity = "1"; added.findNumber = "10"; added.notes = "";
        added.changeInNum = "ECO-NEW"; added.changeInRd = inWin; // in window

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(oldRow, added), from, to);
        assertEquals(1, out.size());
        assertEquals(EcoTimelineClassifier.ADDED, out.get(0).getChangeType());
        assertEquals("ABC-002", out.get(0).getComponent());
        assertEquals("ECO-NEW", out.get(0).getEcoNumber());
    }

    @Test
    void windowBoundaries_areInclusive() {
        BomRedlineRow onFrom = new BomRedlineRow();
        onFrom.bomRowId = 1; onFrom.componentPn = "ON-FROM"; onFrom.componentDesc = "d";
        onFrom.quantity = "1"; onFrom.findNumber = "1"; onFrom.notes = "";
        onFrom.changeInNum = "ECO-FROM"; onFrom.changeInRd = from; // exactly on window start

        BomRedlineRow onTo = new BomRedlineRow();
        onTo.bomRowId = 2; onTo.componentPn = "ON-TO"; onTo.componentDesc = "d";
        onTo.quantity = "1"; onTo.findNumber = "1"; onTo.notes = "";
        onTo.changeInNum = "ECO-TO"; onTo.changeInRd = to; // exactly on window end

        List<EcoTimelineRow> out = classifier.classifyAssembly("ASSY-1", "SKU / ASSY-1",1, List.of(onFrom, onTo), from, to);
        assertEquals(2, out.size());
    }
}
