package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Looks up the i2 / Blue Yonder "primary component at the same BOM level" for
 * Agile components, from the PDC schema's BILLOFMATERIALS table.
 *
 * Rule (per Planning, Vishal 2026-06-24): for a component C, find the i2 BOM slot
 * where C is used — whether C is the slot's PRIMARY (COMPONENTITEMID) or its
 * SUBSTITUTE (SUBSTITUTEITEMID) — and return that slot's PRIMARY. So a component
 * that is itself the primary maps to itself; a component used as a substitute maps
 * to the real primary at the same level. Restricted to the MASTERPLAN namespace and
 * rows effective as of today. Several slots can match — primaries are returned as a
 * distinct, sorted, comma-joined string; null/blank and the literal "Inactive
 * Component" placeholder are dropped.
 *
 * (This replaces the earlier rule that returned C's i2 CHILD — one level too low.)
 *
 * All access is read-only and fails soft: if the PDC datasource is unreachable or
 * unconfigured, lookups return an empty map so the ECO Timeline still renders.
 */
@Service
public class I2PrimaryLookupService {

    private static final Logger logger = Logger.getLogger(I2PrimaryLookupService.class.getName());
    private static final String INACTIVE = "Inactive Component";
    private static final int CHUNK = 1000;   // Oracle IN-list limit

    private final DataSource pdcDataSource;

    public I2PrimaryLookupService(@Qualifier("pdcDataSource") DataSource pdcDataSource) {
        this.pdcDataSource = pdcDataSource;
    }

    /** Map of item number -> "primary1, primary2" for the given items. Items with no
     *  i2 primary are simply absent. Never throws — returns what it can. */
    public Map<String, String> primaryByItem(Collection<String> items) {
        Map<String, String> out = new HashMap<>();
        if (items == null || items.isEmpty()) return out;
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(items));
        Set<String> inputSet = new HashSet<>(distinct);
        try (Connection c = pdcDataSource.getConnection()) {
            List<String[]> rows = new ArrayList<>();   // each = { COMPONENTITEMID, SUBSTITUTEITEMID }
            for (int i = 0; i < distinct.size(); i += CHUNK) {
                List<String> chunk = distinct.subList(i, Math.min(i + CHUNK, distinct.size()));
                rows.addAll(fetchChunk(c, chunk));
            }
            out = slotPrimaries(inputSet, rows);
        } catch (Exception e) {
            // fail soft — the Primary # column just stays blank
            logger.warning("i2 primary lookup skipped (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        return out;
    }

    /** Fetch the i2 BOM slots where any of the chunk's parts is used — as the slot's
     *  primary (COMPONENTITEMID) OR its substitute (SUBSTITUTEITEMID). Returns rows of
     *  { COMPONENTITEMID, SUBSTITUTEITEMID } so the caller can map each input to its
     *  slot primary. */
    private List<String[]> fetchChunk(Connection c, List<String> chunk) throws SQLException {
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) ph.append(i == 0 ? "?" : ",?");
        String sql =
            "SELECT COMPONENTITEMID, SUBSTITUTEITEMID FROM PDC.BILLOFMATERIALS " +
            "WHERE NAMESPACE = 'MASTERPLAN' " +
            "  AND SYSDATE BETWEEN EFFFROMDATE AND EFFUPTODATE " +
            "  AND COMPONENTITEMID IS NOT NULL " +
            "  AND (COMPONENTITEMID IN (" + ph + ") OR SUBSTITUTEITEMID IN (" + ph + "))";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            int n = chunk.size();
            for (int i = 0; i < n; i++) {           // COMPONENTITEMID IN (...)
                ps.setString(i + 1, chunk.get(i));
            }
            for (int i = 0; i < n; i++) {           // SUBSTITUTEITEMID IN (...)
                ps.setString(n + i + 1, chunk.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{ rs.getString(1), rs.getString(2) });
            }
        }
        return rows;
    }

    /** Map each input component to the PRIMARY of the i2 slot(s) where it appears
     *  (as the primary itself, or as a substitute). rows are { COMPONENTITEMID,
     *  SUBSTITUTEITEMID }. Distinct/sorted/comma-joined; null/blank/"Inactive
     *  Component" primaries dropped; inputs with no slot are absent. */
    public static Map<String, String> slotPrimaries(Set<String> inputs, List<String[]> rows) {
        List<String[]> pairs = new ArrayList<>();   // (inputComponent, slotPrimary)
        for (String[] r : rows) {
            String primary = r[0];                  // COMPONENTITEMID
            String substitute = r[1];               // SUBSTITUTEITEMID
            if (primary != null && inputs.contains(primary)) {
                pairs.add(new String[]{ primary, primary });        // it is its own slot's primary
            }
            if (substitute != null && inputs.contains(substitute)) {
                pairs.add(new String[]{ substitute, primary });     // substitute -> the slot's primary
            }
        }
        return collapsePrimaries(pairs);
    }

    /** Pure collapse: (key, value) rows -> key -> distinct, sorted, comma-joined
     *  values, dropping null/blank and the "Inactive Component" placeholder. */
    public static Map<String, String> collapsePrimaries(List<String[]> rows) {
        Map<String, TreeSet<String>> byItem = new HashMap<>();
        for (String[] r : rows) {
            String item = r[0], comp = r[1];
            if (item == null) continue;
            if (comp == null) continue;
            comp = comp.trim();
            if (comp.isEmpty() || comp.equalsIgnoreCase(INACTIVE)) continue;
            byItem.computeIfAbsent(item, k -> new TreeSet<>()).add(comp);
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, TreeSet<String>> e : byItem.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), String.join(", ", e.getValue()));
        }
        return out;
    }
}
