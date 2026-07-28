# Bulk User Import from Excel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Import from Excel" flow to User Management that AI-maps a roster spreadsheet's columns to Name/Email, resolves each person against AD, flags rows already having access as warnings, and submits one consolidated DL-add request to IT.

**Architecture:** Three small, independently-testable backend units (sheet parser → column mapper → AD classifier) feed a thin `UserImportController` (analyze/resolve/submit). Submit reuses `UserPermissionsService.upsertUser` per row but sends a single consolidated IT email. A modal wizard in the existing User Management page drives upload → (optional) mapping confirm → preview → submit.

**Tech Stack:** Java 11 / Spring Boot, Apache POI 5.2.5 (already a dep), JUnit 5 + Mockito (spring-boot-starter-test), `PortkeyClient` (Haiku via Portkey), `LdapAuthService`, vanilla JS frontend.

**Spec:** `docs/superpowers/specs/2026-06-24-bulk-user-import-excel-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/sandisk/plm/tracker/util/UserSheetParser.java` — parse `.xlsx/.xls/.csv` → headers + all data rows (POI DOM; user rosters are small). One responsibility: bytes → grid of strings.
- `src/main/java/com/sandisk/plm/tracker/util/UserColumnMapper.java` — given headers + sample rows, decide which column is Name and which is Email (heuristic first, AI fallback). One responsibility: column semantics.
- `src/main/java/com/sandisk/plm/tracker/service/UserImportService.java` — resolve each `{name,email}` row against AD + dedupe → preview rows with status. One responsibility: AD classification.
- `src/main/java/com/sandisk/plm/tracker/controller/UserImportController.java` — `/api/permissions/import/{analyze,resolve,submit}`. One responsibility: HTTP wiring + auth gate.
- `src/test/java/com/sandisk/plm/tracker/util/UserSheetParserTest.java`
- `src/test/java/com/sandisk/plm/tracker/util/UserColumnMapperTest.java`
- `src/test/java/com/sandisk/plm/tracker/service/UserImportServiceTest.java`
- `src/test/java/com/sandisk/plm/tracker/service/BulkDlRequestHtmlTest.java`

**Modify:**
- `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java` — add `submitBulkDLRequest(...)`, `buildBulkDLRequestHtml(...)`, `sendBulkDLRequestEmail(...)`, and a `BulkOutcome` POJO.
- `src/main/resources/static/index.html` — Import button (near line 2444) + import modal wizard markup (after the Add modal, ~line 2655).
- `src/main/resources/static/user-permissions.js` — import wizard functions.
- `src/main/resources/static/whats-new.js` — changelog entry (pre-build rule).

---

## Task 1: UserSheetParser — parse spreadsheet to a grid

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/util/UserSheetParser.java`
- Test: `src/test/java/com/sandisk/plm/tracker/util/UserSheetParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class UserSheetParserTest {

    private MockMultipartFile xlsxOf(String name, String[][] grid) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Sheet1");
            for (int r = 0; r < grid.length; r++) {
                Row row = sh.createRow(r);
                for (int c = 0; c < grid[r].length; c++) row.createCell(c).setCellValue(grid[r][c]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return new MockMultipartFile("file", name, "application/vnd.ms-excel", bos.toByteArray());
        }
    }

    @Test
    void parsesXlsxHeadersAndRows() throws Exception {
        MockMultipartFile f = xlsxOf("test.xlsx", new String[][]{
            {"Name", "Email"},
            {"Philip Tam", "Philip.Tam@sandisk.com"},
            {"Eva Lu", "eva.lu@sandisk.com"}
        });
        UserSheetParser.ParsedSheet ps = new UserSheetParser().parse(f);
        assertEquals(2, ps.headers.size());
        assertEquals("Name", ps.headers.get(0));
        assertEquals("Email", ps.headers.get(1));
        assertEquals(2, ps.rows.size());
        assertEquals("Philip Tam", ps.rows.get(0).get(0));
        assertEquals("eva.lu@sandisk.com", ps.rows.get(1).get(1));
    }

    @Test
    void parsesCsv() throws Exception {
        String csv = "Name,Email\nPhilip Tam,Philip.Tam@sandisk.com\n";
        MockMultipartFile f = new MockMultipartFile("file", "test.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        UserSheetParser.ParsedSheet ps = new UserSheetParser().parse(f);
        assertEquals(2, ps.headers.size());
        assertEquals(1, ps.rows.size());
        assertEquals("Philip Tam", ps.rows.get(0).get(0));
    }

    @Test
    void rejectsEmptySheet() throws Exception {
        MockMultipartFile f = xlsxOf("empty.xlsx", new String[][]{});
        UserSheetParser p = new UserSheetParser();
        assertThrows(IllegalArgumentException.class, () -> p.parse(f));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UserSheetParserTest`
Expected: FAIL — `UserSheetParser` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.sandisk.plm.tracker.util;

import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a small user-roster spreadsheet (.xlsx/.xls/.csv) into a header row +
 * data rows. Rosters are tiny (tens to a few hundred rows), so a full POI DOM
 * load is fine here — unlike the 778K-row item caches that need streaming.
 */
public class UserSheetParser {

    /** Hard cap so a pasted-in giant file can't OOM the import path. */
    public static final int MAX_ROWS = 5000;

    public static class ParsedSheet {
        public List<String> headers = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
        /** First up-to-3 data rows, for the AI column-mapping prompt. */
        public List<List<String>> sampleRows() {
            return rows.subList(0, Math.min(3, rows.size()));
        }
    }

    public ParsedSheet parse(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        ParsedSheet out;
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            out = parseCsv(file);
        } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            out = parseExcel(file);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Use .xlsx, .xls, or .csv.");
        }
        if (out.headers.isEmpty()) {
            throw new IllegalArgumentException("The file appears to be empty — no header row found.");
        }
        return out;
    }

    private ParsedSheet parseExcel(MultipartFile file) throws Exception {
        ParsedSheet ps = new ParsedSheet();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            if (wb.getNumberOfSheets() == 0) return ps;
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int firstRow = sheet.getFirstRowNum();
            int lastRow = Math.min(sheet.getLastRowNum(), firstRow + MAX_ROWS);
            int width = 0;
            Row header = sheet.getRow(firstRow);
            if (header != null) {
                width = header.getLastCellNum();
                for (int c = 0; c < width; c++) ps.headers.add(fmt.formatCellValue(header.getCell(c)).trim());
            }
            for (int r = firstRow + 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                boolean anyValue = false;
                for (int c = 0; c < width; c++) {
                    String v = fmt.formatCellValue(row.getCell(c)).trim();
                    if (!v.isEmpty()) anyValue = true;
                    cells.add(v);
                }
                if (anyValue) ps.rows.add(cells);
            }
        }
        return ps;
    }

    private ParsedSheet parseCsv(MultipartFile file) throws Exception {
        ParsedSheet ps = new ParsedSheet();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (first) {
                    for (String h : splitCsv(line)) ps.headers.add(h.trim());
                    first = false;
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                if (count++ >= MAX_ROWS) break;
                List<String> cells = new ArrayList<>();
                for (String c : splitCsv(line)) cells.add(c.trim());
                ps.rows.add(cells);
            }
        }
        return ps;
    }

    /** Minimal CSV split handling double-quoted fields with embedded commas. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=UserSheetParserTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/util/UserSheetParser.java \
        src/test/java/com/sandisk/plm/tracker/util/UserSheetParserTest.java
git commit -m "feat(user-import): spreadsheet parser for roster import"
```

---

## Task 2: UserColumnMapper — map columns to Name/Email

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/util/UserColumnMapper.java`
- Test: `src/test/java/com/sandisk/plm/tracker/util/UserColumnMapperTest.java`

Mirrors `UploadColumnDetector`: header heuristic first, AI fallback (Haiku via `PortkeyClient`) only when the heuristic is not confident. `emailColumn = -1` means "no email column found" (allowed — rows then resolve by name only).

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.util;

import com.sandisk.plm.tracker.service.PortkeyClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserColumnMapperTest {

    private UserColumnMapper mapperWith(PortkeyClient client) {
        UserColumnMapper m = new UserColumnMapper();
        ReflectionTestUtils.setField(m, "portkeyClient", client);
        ReflectionTestUtils.setField(m, "aiModel", "test-model");
        ReflectionTestUtils.setField(m, "aiEnabled", true);
        return m;
    }

    @Test
    void heuristicMapsObviousHeaders() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Name", "Email"), Collections.emptyList());
        assertEquals(0, map.nameColumn);
        assertEquals(1, map.emailColumn);
        assertTrue(map.confident);
        assertEquals("heuristic", map.method);
    }

    @Test
    void heuristicHandlesReversedAndAltLabels() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("E-mail Address", "Full Name"), Collections.emptyList());
        assertEquals(1, map.nameColumn);
        assertEquals(0, map.emailColumn);
        assertTrue(map.confident);
    }

    @Test
    void aiFallbackUsedWhenHeadersAreOpaque() throws Exception {
        PortkeyClient client = mock(PortkeyClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyString(), anyInt()))
            .thenReturn("{\"nameColumn\":0,\"emailColumn\":1,\"confident\":true,\"reasoning\":\"col0 looks like names\"}");
        UserColumnMapper.Mapping map = mapperWith(client).map(
            Arrays.asList("Col1", "Col2"),
            Arrays.asList(Arrays.asList("Philip Tam", "Philip.Tam@sandisk.com")));
        assertEquals(0, map.nameColumn);
        assertEquals(1, map.emailColumn);
        assertTrue(map.confident);
        assertEquals("ai", map.method);
    }

    @Test
    void notConfidentWhenNoNameColumnAndAiDisabled() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Region", "Cost Center"), Collections.emptyList());
        assertFalse(map.confident);
        assertNotNull(map.question);
    }

    @Test
    void emailMissingIsAllowedButFlagged() {
        UserColumnMapper.Mapping map = mapperWith(null)
            .map(Arrays.asList("Full Name"), Collections.emptyList());
        assertEquals(0, map.nameColumn);
        assertEquals(-1, map.emailColumn);
        // name found but no email -> still usable, confident on name
        assertTrue(map.confident);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UserColumnMapperTest`
Expected: FAIL — `UserColumnMapper` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.sandisk.plm.tracker.util;

import com.sandisk.plm.tracker.service.PortkeyClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides which spreadsheet column holds the person's Name and which holds the
 * Email. Two tiers (same shape as {@link UploadColumnDetector}):
 *   1. Header heuristic (no network) — keyword match on header text.
 *   2. AI fallback (Claude Haiku via Portkey) — only when the heuristic can't
 *      confidently find a Name column. Returns JSON we parse best-effort.
 * If both fail, returns a non-confident Mapping carrying a {@code question}
 * the UI surfaces so the admin can pick columns manually.
 */
@Component
public class UserColumnMapper {

    private static final Logger logger = Logger.getLogger(UserColumnMapper.class.getName());

    @Autowired(required = false) private PortkeyClient portkeyClient;
    @Value("${portkey.model:@anthropic-eastus2/claude-haiku-4-5-20251001}") private String aiModel;
    @Value("${app.upload-column-detect.ai-fallback:true}") private boolean aiEnabled;

    public static class Mapping {
        public int nameColumn = -1;
        public int emailColumn = -1;
        public boolean confident = false;
        public String method = "none";   // "heuristic" | "ai" | "none"
        public String question;           // non-null when !confident
    }

    public Mapping map(List<String> headers, List<List<String>> sampleRows) {
        Mapping h = byHeuristic(headers);
        if (h.confident) return h;

        if (aiEnabled && portkeyClient != null && portkeyClient.isEnabled()) {
            try {
                Mapping ai = byAi(headers, sampleRows);
                if (ai != null && ai.nameColumn >= 0) { ai.method = "ai"; return ai; }
            } catch (Exception e) {
                logger.log(Level.WARNING, "[USER-IMPORT] AI column mapping failed: " + e.getMessage());
            }
        }
        // Fall back to whatever the heuristic found (may be partial), not confident.
        if (h.question == null) {
            h.question = "Couldn't tell which column is the person's name. Pick the Name column"
                + (h.emailColumn < 0 ? " and the Email column." : ".");
        }
        return h;
    }

    private Mapping byHeuristic(List<String> headers) {
        Mapping m = new Mapping();
        for (int c = 0; c < headers.size(); c++) {
            String n = normalize(headers.get(c));
            if (m.emailColumn < 0 && (n.contains("email") || n.contains("mail") || n.equals("e"))) m.emailColumn = c;
        }
        for (int c = 0; c < headers.size(); c++) {
            String n = normalize(headers.get(c));
            if (c == m.emailColumn) continue;
            if (m.nameColumn < 0 && (n.equals("name") || n.contains("fullname") || n.contains("displayname")
                    || n.contains("employeename") || n.equals("user") || n.contains("username"))) m.nameColumn = c;
        }
        m.method = "heuristic";
        // Confident if we found a name column. Email may legitimately be absent.
        m.confident = m.nameColumn >= 0;
        if (!m.confident) m.question = "Couldn't find a Name column. Which column holds the person's name?";
        return m;
    }

    private Mapping byAi(List<String> headers, List<List<String>> sampleRows) throws Exception {
        StringBuilder p = new StringBuilder();
        p.append("A PLM admin uploaded a spreadsheet of people to grant tool access to. ");
        p.append("Identify which column holds the person's NAME and which holds their EMAIL.\n\n");
        p.append("HEADERS (0-indexed): ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) p.append(" | ");
            p.append('[').append(i).append("]=").append(nz(headers.get(i)));
        }
        p.append("\n\nSAMPLE ROWS:\n");
        int n = Math.min(3, sampleRows == null ? 0 : sampleRows.size());
        for (int r = 0; r < n; r++) {
            p.append("row ").append(r + 1).append(": ");
            List<String> row = sampleRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) p.append(" | ");
                p.append('[').append(i).append("]=").append(nz(row.get(i)));
            }
            p.append('\n');
        }
        p.append("\nReply with JSON only, no prose: ");
        p.append("{\"nameColumn\": <int>, \"emailColumn\": <int or -1 if none>, ");
        p.append("\"confident\": <true|false>, \"reasoning\": \"<one short sentence>\"}");

        String system = "You map spreadsheet columns for a PLM tool. Reply with JSON only.";
        String resp = portkeyClient.chat(aiModel, system, p.toString(), 200);
        if (resp == null) return null;
        int s = resp.indexOf('{'), e = resp.lastIndexOf('}');
        if (s < 0 || e < s) return null;
        String json = resp.substring(s, e + 1);
        Integer nameCol = extractInt(json, "nameColumn");
        Integer emailCol = extractInt(json, "emailColumn");
        if (nameCol == null || nameCol < 0 || nameCol >= headers.size()) return null;
        Mapping m = new Mapping();
        m.nameColumn = nameCol;
        m.emailColumn = (emailCol != null && emailCol >= 0 && emailCol < headers.size()) ? emailCol : -1;
        m.confident = json.contains("\"confident\":true") || json.contains("\"confident\": true");
        return m;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[\\s_\\-#.]+", "");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Integer extractInt(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int start = j;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        if (j == start) return null;
        try { return Integer.parseInt(json.substring(start, j)); }
        catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=UserColumnMapperTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/util/UserColumnMapper.java \
        src/test/java/com/sandisk/plm/tracker/util/UserColumnMapperTest.java
git commit -m "feat(user-import): AI/heuristic column mapper for Name/Email"
```

---

## Task 3: UserImportService — resolve rows against AD + dedupe

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/UserImportService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/UserImportServiceTest.java`

Constructor-inject `LdapAuthService` + `UserPermissionsService` so the classifier is unit-testable with Mockito mocks (no Spring, no IO).

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UserImportServiceTest {

    private DirectoryUser du(String user, String name, String email) {
        return new DirectoryUser(user, name, email);
    }

    private UserImportService svc(LdapAuthService ldap, UserPermissionsService perms) {
        return new UserImportService(ldap, perms);
    }

    @Test
    void confidentEmailMatchIsMatched() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("philip.tam@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("philip.tam", "Philip Tam", "Philip.Tam@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Philip Tam", "philip.tam@sandisk.com");
        assertEquals("matched", row.status);
        assertNotNull(row.match);
        assertEquals("philip.tam", row.match.sAMAccountName);
    }

    @Test
    void emailMismatchFallsBackToNameThenAmbiguous() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        // Mahek's email doesn't share the name; email search returns the right person.
        when(ldap.searchDirectory(eq("mahek.naresh.oberai@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("mahek.oberai", "Mahek Amaria", "Mahek.Naresh.Oberai@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Mahek Amaria", "mahek.naresh.oberai@sandisk.com");
        assertEquals("matched", row.status);
        assertEquals("mahek.oberai", row.match.sAMAccountName);
    }

    @Test
    void multipleHitsAreAmbiguous() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("Eva Lu"), anyInt()))
            .thenReturn(Arrays.asList(du("eva.lu", "Eva Lu", "eva.lu@sandisk.com"),
                                      du("eva.lu2", "Eva Lu", "eva.lu2@sandisk.com")));
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("Eva Lu", "");
        assertEquals("ambiguous", row.status);
        assertEquals(2, row.candidates.size());
        assertNull(row.match);
    }

    @Test
    void noHitsIsNomatch() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(ldap.listAccessGroupCandidates()).thenReturn(Collections.emptyList());
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms).resolveRow("Ghost User", "ghost@sandisk.com");
        assertEquals("nomatch", row.status);
    }

    @Test
    void alreadyInDlIsFlaggedAsAccess() {
        LdapAuthService ldap = mock(LdapAuthService.class);
        UserPermissionsService perms = mock(UserPermissionsService.class);
        when(ldap.searchDirectory(eq("vaibhav.singh@sandisk.com"), anyInt()))
            .thenReturn(Collections.singletonList(du("vaibhav.singh", "Vaibhav Singh", "vaibhav.singh@sandisk.com")));
        when(ldap.listAccessGroupCandidates())
            .thenReturn(Collections.singletonList(du("vaibhav.singh", "Vaibhav Singh", "vaibhav.singh@sandisk.com")));
        when(perms.allRecords()).thenReturn(Collections.emptyMap());

        UserImportService.PreviewRow row = svc(ldap, perms)
            .resolveRow("Vaibhav Singh", "vaibhav.singh@sandisk.com");
        assertEquals("already-access", row.status);
        assertNotNull(row.match);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UserImportServiceTest`
Expected: FAIL — `UserImportService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.LdapAuthService.DirectoryUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resolves imported {name,email} rows against AD and classifies each one so the
 * import preview can show matched / ambiguous / no-match / already-has-access.
 * Dependencies are constructor-injected so the classifier is unit-testable.
 */
@Service
public class UserImportService {

    private final LdapAuthService ldap;
    private final UserPermissionsService perms;

    @Autowired
    public UserImportService(LdapAuthService ldap, UserPermissionsService perms) {
        this.ldap = ldap;
        this.perms = perms;
    }

    /** One resolved AD candidate, JSON-serializable for the frontend. */
    public static class Match {
        public String sAMAccountName;
        public String displayName;
        public String email;
        public Match() {}
        public Match(DirectoryUser u) {
            this.sAMAccountName = u.username;
            this.displayName = u.displayName;
            this.email = u.email;
        }
    }

    public static class PreviewRow {
        public String name;
        public String email;
        public String status;           // matched | ambiguous | nomatch | already-access
        public Match match;             // non-null when matched / already-access
        public List<Match> candidates = new ArrayList<>();
        public String message;          // warning/error text for the UI
    }

    public static class PreviewResult {
        public List<PreviewRow> rows = new ArrayList<>();
        public Map<String, Integer> summary = new LinkedHashMap<>();
    }

    public PreviewResult resolveAll(List<Map<String, String>> rawRows) {
        PreviewResult res = new PreviewResult();
        int matched = 0, ambiguous = 0, nomatch = 0, already = 0;
        for (Map<String, String> r : rawRows) {
            PreviewRow row = resolveRow(nz(r.get("name")), nz(r.get("email")));
            res.rows.add(row);
            switch (row.status) {
                case "matched": matched++; break;
                case "ambiguous": ambiguous++; break;
                case "nomatch": nomatch++; break;
                case "already-access": already++; break;
                default: break;
            }
        }
        res.summary.put("matched", matched);
        res.summary.put("ambiguous", ambiguous);
        res.summary.put("nomatch", nomatch);
        res.summary.put("alreadyAccess", already);
        return res;
    }

    public PreviewRow resolveRow(String name, String email) {
        PreviewRow row = new PreviewRow();
        row.name = name;
        row.email = email;

        DirectoryUser matched = null;
        List<DirectoryUser> candidates = new ArrayList<>();

        if (!email.isEmpty()) {
            List<DirectoryUser> hits = safeSearch(email);
            List<DirectoryUser> exact = new ArrayList<>();
            for (DirectoryUser u : hits) {
                if (u.email != null && u.email.equalsIgnoreCase(email)) exact.add(u);
            }
            if (exact.size() == 1) matched = exact.get(0);
            else if (exact.size() > 1) candidates = exact;
            else if (hits.size() == 1) matched = hits.get(0);
            else if (hits.size() > 1) candidates = hits;
        }
        if (matched == null && candidates.isEmpty() && !name.isEmpty()) {
            List<DirectoryUser> hits = safeSearch(name);
            if (hits.size() == 1) matched = hits.get(0);
            else if (hits.size() > 1) candidates = hits;
        }

        if (matched != null) {
            row.match = new Match(matched);
            if (hasAccess(matched.username)) {
                row.status = "already-access";
                row.message = (matched.displayName == null ? matched.username : matched.displayName)
                    + " already has access — skipped.";
            } else {
                row.status = "matched";
            }
        } else if (!candidates.isEmpty()) {
            row.status = "ambiguous";
            for (DirectoryUser u : candidates) row.candidates.add(new Match(u));
            row.message = "Multiple AD matches — pick the right person.";
        } else {
            row.status = "nomatch";
            row.message = "No AD user found for this row.";
        }
        return row;
    }

    private boolean hasAccess(String username) {
        if (username == null) return false;
        String key = username.trim().toLowerCase();
        // Already a managed user record?
        if (perms.allRecords().containsKey(key)) return true;
        // Already in the access DL?
        for (DirectoryUser u : safeDl()) {
            if (u.username != null && u.username.trim().toLowerCase().equals(key)) return true;
        }
        return false;
    }

    private List<DirectoryUser> safeSearch(String q) {
        try {
            List<DirectoryUser> hits = ldap.searchDirectory(q, 5);
            return hits == null ? Collections.emptyList() : hits;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<DirectoryUser> safeDl() {
        try {
            List<DirectoryUser> dl = ldap.listAccessGroupCandidates();
            return dl == null ? Collections.emptyList() : dl;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=UserImportServiceTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserImportService.java \
        src/test/java/com/sandisk/plm/tracker/service/UserImportServiceTest.java
git commit -m "feat(user-import): AD resolution + dedupe classifier"
```

---

## Task 4: Consolidated DL-request email (HTML builder)

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`
- Test: `src/test/java/com/sandisk/plm/tracker/service/BulkDlRequestHtmlTest.java`

Add a `buildBulkDLRequestHtml(List<PendingRequest>)` that lists every user in one email (reusing the existing `esc`, `detailRow`, `buildAdGroupCta`, palette). Make it package-private (not `private`) so the test can call it.

- [ ] **Step 1: Write the failing test**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.service.UserPermissionsService.PendingRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BulkDlRequestHtmlTest {

    private PendingRequest req(String name, String user, String email, List<String> tabs) {
        PendingRequest p = new PendingRequest();
        p.sAMAccountName = user;
        p.displayName = name;
        p.email = email;
        p.requestedTabs = tabs;
        p.requestedByDisplay = "Vikas Jindal";
        p.requestedBy = "vikas.jindal";
        p.requestedAt = "2026-06-24 10:00:00";
        return p;
    }

    @Test
    void listsEveryUserAndEscapes() {
        UserPermissionsService svc = new UserPermissionsService();
        List<PendingRequest> reqs = Arrays.asList(
            req("Philip Tam", "philip.tam", "philip.tam@sandisk.com", Arrays.asList("fields", "bom")),
            req("Eva <Lu>", "eva.lu", "eva.lu@sandisk.com", Collections.singletonList("history")));
        String html = svc.buildBulkDLRequestHtml(reqs);
        assertTrue(html.contains("Philip Tam"));
        assertTrue(html.contains("eva.lu@sandisk.com"));
        assertTrue(html.contains("Eva &lt;Lu&gt;"));   // escaped
        assertTrue(html.contains("2 ") || html.contains(">2<")); // count surfaced somewhere
        assertTrue(html.toLowerCase().contains("access"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=BulkDlRequestHtmlTest`
Expected: FAIL — `buildBulkDLRequestHtml` not defined.

- [ ] **Step 3: Add the builder to `UserPermissionsService`**

Insert after `buildDLRequestHtml(...)` (around line 716). Note `buildAdGroupCta()` and `esc(...)`/`detailRow(...)` already exist in this class.

```java
    /**
     * One consolidated "please add these users to the access DL" email body for
     * a bulk import. Lists each user + the shared tab set. Package-private so it
     * can be unit-tested without SMTP.
     */
    String buildBulkDLRequestHtml(List<PendingRequest> reqs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (TabDef t : TAB_CATALOG) labels.put(t.key, t.label);

        StringBuilder rows = new StringBuilder();
        for (PendingRequest req : reqs) {
            StringBuilder tabs = new StringBuilder();
            List<String> rt = req.requestedTabs == null ? new ArrayList<>() : req.requestedTabs;
            for (int i = 0; i < rt.size(); i++) {
                if (i > 0) tabs.append(", ");
                tabs.append(esc(labels.getOrDefault(rt.get(i), rt.get(i))));
            }
            rows.append("<tr>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:13px;\">")
                .append("<span style=\"color:#4a6fa5;font-weight:600;\">").append(esc(req.displayName)).append("</span>")
                .append("<div style=\"font-size:11px;color:#6B7280;\">").append(esc(req.sAMAccountName)).append("</div></td>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:13px;\">")
                .append(esc(req.email)).append("</td>")
                .append("<td style=\"padding:6px 10px;border-bottom:1px solid #cccccc;font-size:12px;color:#0F1720;\">")
                .append(tabs.length() == 0 ? "<span style=\"color:#6B7280;\">(none)</span>" : tabs).append("</td>")
                .append("</tr>");
        }

        String reqBy = reqs.isEmpty() ? "" : (reqs.get(0).requestedByDisplay + " (" + reqs.get(0).requestedBy + ")");
        String when = reqs.isEmpty() ? nowStamp() : reqs.get(0).requestedAt;

        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"color-scheme\" content=\"light dark\">"
            + "<title>PLM Toolkit · Bulk DL add request</title></head>"
            + "<body class=\"email-body\" style=\"margin:0;padding:24px;background:#FAFAF7;font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;color:#0F1720;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" class=\"email-card\" "
            + "style=\"max-width:600px;background:#fff;border:1px solid #E8E6DF;border-radius:8px;\">"
            + "<tr><td style=\"padding:18px 24px;border-bottom:1px solid #E8E6DF;font-size:12px;color:#6B7280;\">"
            + "<strong>PLM Toolkit</strong> &nbsp;/&nbsp; Access request "
            + "<span style=\"float:right;background:#e8f0fe;color:#1a3a5c;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:600;\">Action needed</span>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#6B7280;margin-bottom:6px;\">Please add to AD DL</div>"
            + "<div style=\"font-family:'IBM Plex Serif',Georgia,serif;font-size:22px;font-weight:600;margin-bottom:14px;\">"
            + reqs.size() + " user" + (reqs.size() == 1 ? "" : "s") + " need PLM Toolkit access</div>"
            + "<div style=\"font-size:13px;color:#0F1720;line-height:1.55;margin-bottom:12px;\">Requested by " + esc(reqBy)
            + " on " + esc(when) + ". Tabs are already saved in PLM Toolkit; each user sees them on first login.</div>"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;margin-bottom:18px;\">"
            + "<tr>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">User</th>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">Email</th>"
            + "<th style=\"text-align:left;background:#2c3e50;color:#fff;padding:8px 10px;font-size:12px;border-bottom:1px solid #444444;\">Tabs</th>"
            + "</tr>" + rows + "</table>"
            + "<div style=\"background:#FAFAF7;border-left:3px solid #4a6fa5;border-radius:0 6px 6px 0;padding:14px 18px;font-size:13px;color:#0F1720;line-height:1.55;\">"
            + "<strong>What IT needs to do:</strong> add these users to the PLM Toolkit access DL in AD. "
            + "PLM Toolkit already has tab visibility set up for each."
            + "</div>"
            + buildAdGroupCta()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;border-top:1px solid #E8E6DF;font-family:'IBM Plex Mono',Consolas,monospace;font-size:11px;color:#6B7280;\">"
            + nowStamp()
            + "</td></tr>"
            + "<tr><td style=\"padding:14px 24px;background:#FAFAF7;border-top:1px solid #E8E6DF;\">"
            + "<span style=\"display:inline-block;border:1px solid #ececec;border-radius:20px;padding:3px 10px;font-size:11px;color:#6B7280;\">sandisk</span>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:6px;\">PLM Toolkit · Access request notification</div>"
            + "<div style=\"font-size:11px;color:#6B7280;margin-top:2px;\">This is an automated notification. Please do not reply to this email.</div>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=BulkDlRequestHtmlTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java \
        src/test/java/com/sandisk/plm/tracker/service/BulkDlRequestHtmlTest.java
git commit -m "feat(user-import): consolidated bulk DL-request email body"
```

---

## Task 5: submitBulkDLRequest — persist N records, send one email

**Files:**
- Modify: `src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java`

No new unit test (it does file IO + SMTP + LDAP admin check; verified by the local smoke test in Task 9, consistent with how the existing `submitDLRequest` is covered). Reuses `upsertUser` (strips non-grantable tabs, rejects admins) per row.

- [ ] **Step 1: Add the outcome POJO + method** after `submitDLRequest(...)` (around line 495).

```java
    /** Per-user result of a bulk import submit. */
    public static class BulkOutcome {
        public String sAMAccountName;
        public String displayName;
        public boolean ok;
        public String error;   // null when ok
    }

    /**
     * Bulk variant of {@link #submitDLRequest}. For each row: save the per-user
     * tab record + create a pending DL request (one per user, so each person's
     * own first login still completes their request and fires their welcome
     * email). IT is notified with a SINGLE consolidated email instead of one
     * per user. Rows that fail (e.g. target is an admin) are collected and
     * reported; the rest still go through.
     *
     * @param users   list of maps with keys sAMAccountName, displayName, email
     * @param allowedTabs shared tab set applied to every user
     */
    public synchronized List<BulkOutcome> submitBulkDLRequest(List<Map<String, String>> users,
                                                              List<String> allowedTabs,
                                                              String actorUsername, String actorDisplayName) {
        List<BulkOutcome> outcomes = new ArrayList<>();
        List<PendingRequest> created = new ArrayList<>();

        for (Map<String, String> u : users) {
            String sam = u.get("sAMAccountName");
            String name = u.get("displayName");
            String email = u.get("email");
            BulkOutcome oc = new BulkOutcome();
            oc.sAMAccountName = sam;
            oc.displayName = name;
            try {
                UserRecord saved = upsertUser(sam, name, email, allowedTabs, actorUsername, actorDisplayName);
                String key = normalizeKey(sam);
                state.pendingDLRequests.removeIf(p -> normalizeKey(p.sAMAccountName).equals(key));
                PendingRequest req = new PendingRequest();
                req.sAMAccountName = sam;
                req.displayName = name;
                req.email = email;
                req.requestedTabs = saved.allowedTabs == null ? new ArrayList<>() : new ArrayList<>(saved.allowedTabs);
                req.requestedBy = actorUsername;
                req.requestedByDisplay = actorDisplayName;
                req.requestedAt = nowStamp();
                req.status = "pending";
                state.pendingDLRequests.add(req);
                created.add(req);
                oc.ok = true;
            } catch (Exception e) {
                oc.ok = false;
                oc.error = e.getMessage();
            }
            outcomes.add(oc);
        }
        save();

        if (!created.isEmpty()) {
            try {
                sendBulkDLRequestEmail(created);
            } catch (Exception e) {
                logger.warning("[PERMS] bulk DL request email failed: " + e.getMessage());
            }
        }

        int ok = 0; for (BulkOutcome o : outcomes) if (o.ok) ok++;
        activityLogger.log(actorUsername, actorDisplayName, "PERMISSIONS_BULK_IMPORT",
            "submitted=" + users.size() + " ok=" + ok + " failed=" + (users.size() - ok)
            + " tabs=" + String.join(",", allowedTabs == null ? new ArrayList<>() : allowedTabs));
        return outcomes;
    }

    private void sendBulkDLRequestEmail(List<PendingRequest> reqs) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session mailSession = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(mailSession);
        msg.setFrom(new InternetAddress(mailFrom));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(itEmail));
        msg.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag(
            "PLM Toolkit · Please add " + reqs.size() + " user" + (reqs.size() == 1 ? "" : "s") + " to the access DL"));
        msg.setContent(buildBulkDLRequestHtml(reqs), "text/html; charset=utf-8");
        javax.mail.Transport.send(msg);
    }
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/UserPermissionsService.java
git commit -m "feat(user-import): submitBulkDLRequest with single consolidated IT email"
```

---

## Task 6: UserImportController — analyze / resolve / submit endpoints

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/UserImportController.java`

Auth gate mirrors `UserPermissionsController.isPermsAdmin` (PLM admin OR permissions-admin). `analyze` returns the full grid (so the UI can re-map client-side without re-upload) plus the AI mapping + best-guess `{name,email}` rows.

- [ ] **Step 1: Write the controller**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.service.UserImportService;
import com.sandisk.plm.tracker.service.UserImportService.PreviewResult;
import com.sandisk.plm.tracker.service.UserPermissionsService;
import com.sandisk.plm.tracker.service.UserPermissionsService.BulkOutcome;
import com.sandisk.plm.tracker.util.UserColumnMapper;
import com.sandisk.plm.tracker.util.UserSheetParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.util.*;

/**
 * Bulk user import from a roster spreadsheet. Three steps:
 *   analyze  — parse + AI-map columns, return grid + mapping + best-guess rows
 *   resolve  — match {name,email} rows against AD + dedupe -> preview
 *   submit   — persist tab records + one consolidated DL-request email to IT
 * Same admin gate as {@link UserPermissionsController}.
 */
@RestController
@RequestMapping("/api/permissions/import")
public class UserImportController {

    @Autowired private UserSheetParser sheetParser;     // see Task 6 note: register as @Component
    @Autowired private UserColumnMapper columnMapper;
    @Autowired private UserImportService importService;
    @Autowired private UserPermissionsService permissionsService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam("file") MultipartFile file, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            UserSheetParser.ParsedSheet ps = sheetParser.parse(file);
            UserColumnMapper.Mapping map = columnMapper.map(ps.headers, ps.sampleRows());

            List<Map<String, String>> rows = new ArrayList<>();
            for (List<String> r : ps.rows) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", cell(r, map.nameColumn));
                m.put("email", cell(r, map.emailColumn));
                rows.add(m);
            }

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("nameColumn", map.nameColumn);
            mapping.put("emailColumn", map.emailColumn);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("columns", ps.headers);
            resp.put("allRows", ps.rows);          // full grid for client-side re-map
            resp.put("mapping", mapping);
            resp.put("confident", map.confident);
            resp.put("mappingQuestion", map.confident ? null : map.question);
            resp.put("method", map.method);
            resp.put("rows", rows);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err("Could not read the file: " + e.getMessage()));
        }
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> rows = rowsOf(body.get("rows"));
            PreviewResult res = importService.resolveAll(rows);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("rows", res.rows);
            resp.put("summary", res.summary);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isPermsAdmin(session)) return forbidden();
        try {
            List<Map<String, String>> users = rowsOf(body.get("rows"));   // sAMAccountName, displayName, email
            List<String> tabs = listOf(body.get("allowedTabs"));
            if (users.isEmpty()) return ResponseEntity.badRequest().body(err("No users to submit."));
            List<BulkOutcome> outcomes = permissionsService.submitBulkDLRequest(
                users, tabs, username(session), displayName(session));
            int ok = 0; for (BulkOutcome o : outcomes) if (o.ok) ok++;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("submitted", users.size());
            resp.put("ok", ok);
            resp.put("failed", users.size() - ok);
            resp.put("outcomes", outcomes);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(err(e.getMessage()));
        }
    }

    // ---- helpers ----

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || row == null || idx >= row.size()) return "";
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> rowsOf(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Map<String, String> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        row.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) if (x != null) out.add(x.toString());
        return out;
    }

    private boolean isPermsAdmin(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) return true;
        return permissionsService.isPermissionsAdmin(username(session));
    }

    private String username(HttpSession session) {
        Object o = session.getAttribute("username");
        return o == null ? "" : o.toString();
    }

    private String displayName(HttpSession session) {
        Object o = session.getAttribute("displayName");
        return o == null ? username(session) : o.toString();
    }

    private static ResponseEntity<?> forbidden() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", "User Permissions admin access required.");
        return ResponseEntity.status(403).body(e);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("error", msg == null ? "unknown error" : msg);
        return e;
    }
}
```

- [ ] **Step 2: Make `UserSheetParser` a Spring bean**

So `@Autowired UserSheetParser` resolves, add the annotation at the top of `UserSheetParser`:

```java
@org.springframework.stereotype.Component
public class UserSheetParser {
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/controller/UserImportController.java \
        src/main/java/com/sandisk/plm/tracker/util/UserSheetParser.java
git commit -m "feat(user-import): analyze/resolve/submit REST endpoints"
```

---

## Task 7: Frontend — Import button + wizard markup

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Add the Import button** next to the Add button at line 2444.

Find:
```html
        <button id="permsAddUserBtn" onclick="permsOpenAddModal()" style="background:#4a6fa5; color:#fff; border:none; padding:8px 16px; border-radius:6px; font-weight:600; cursor:pointer; font-size:13px;">+ Add user from AD</button>
```
Replace with (adds a second button before it, keeping the original):
```html
        <button id="permsImportBtn" onclick="permsOpenImport()" style="background:none; color:#4a6fa5; border:1px solid #4a6fa5; padding:8px 16px; border-radius:6px; font-weight:600; cursor:pointer; font-size:13px; margin-right:8px;">Import from Excel</button>
        <button id="permsAddUserBtn" onclick="permsOpenAddModal()" style="background:#4a6fa5; color:#fff; border:none; padding:8px 16px; border-radius:6px; font-weight:600; cursor:pointer; font-size:13px;">+ Add user from AD</button>
```

- [ ] **Step 2: Add the import modal** immediately after the Add modal's closing `</div>` (the Add modal ends around line 2655, just before the `<!-- Removed 2026-05-14 ... -->` comment).

```html
<!-- Import Users from Excel Modal -->
<div id="permsImportModal" style="display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.5); z-index:10000; align-items:flex-start; justify-content:center; padding-top:50px;">
  <div style="background:#fff; max-width:760px; width:94%; border-radius:8px; box-shadow:0 10px 40px rgba(0,0,0,0.2); padding:24px; max-height:88vh; overflow-y:auto;">
    <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:14px;">
      <div>
        <div style="font-family:'IBM Plex Serif',Georgia,serif; font-size:20px; font-weight:600;">Import users from Excel</div>
        <div style="font-size:12px; color:#6B7280; margin-top:2px;">Upload a spreadsheet of people &mdash; we map the columns, match them in AD, and request access in one go.</div>
      </div>
      <button onclick="permsCloseImport()" style="background:none; border:none; font-size:22px; color:#6B7280; cursor:pointer; line-height:1;">&times;</button>
    </div>

    <!-- Step 1: upload -->
    <div id="permsImportStepUpload">
      <input id="permsImportFile" type="file" accept=".xlsx,.xls,.csv" onchange="permsImportUpload(this)" style="display:block; margin-bottom:10px; font-size:13px;">
      <div style="font-size:12px; color:#6B7280;">Accepted: .xlsx, .xls, .csv. The sheet should have a column with people's names (an email column helps us match them exactly).</div>
      <div id="permsImportUploadMsg" style="font-size:12px; margin-top:10px;"></div>
    </div>

    <!-- Step 2: mapping confirm (only shown when AI is unsure) -->
    <div id="permsImportStepMapping" style="display:none;">
      <div id="permsImportMappingQ" style="background:#fff8e1; border-left:4px solid #C7801B; border-radius:0 6px 6px 0; padding:10px 14px; font-size:13px; color:#0F1720; margin-bottom:12px;"></div>
      <div style="display:flex; gap:16px; margin-bottom:14px;">
        <label style="font-size:13px;">Name column<br><select id="permsImportNameCol" style="margin-top:4px; padding:6px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;"></select></label>
        <label style="font-size:13px;">Email column<br><select id="permsImportEmailCol" style="margin-top:4px; padding:6px; border:1px solid #E8E6DF; border-radius:6px; font-size:13px;"></select></label>
      </div>
      <div style="display:flex; gap:8px; justify-content:flex-end;">
        <button onclick="permsCloseImport()" style="background:none; border:1px solid #E8E6DF; color:#6B7280; padding:8px 14px; border-radius:6px; font-size:13px; cursor:pointer;">Cancel</button>
        <button onclick="permsImportApplyMapping()" style="background:#4a6fa5; color:#fff; border:none; padding:8px 14px; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Continue</button>
      </div>
    </div>

    <!-- Step 3: preview + tabs + submit -->
    <div id="permsImportStepPreview" style="display:none;">
      <div id="permsImportSummary" style="font-size:13px; margin-bottom:10px;"></div>
      <div id="permsImportRows" style="border:1px solid #E8E6DF; border-radius:6px; max-height:300px; overflow-y:auto; margin-bottom:14px;"></div>
      <div style="font-size:13px; color:#0F1720; margin-bottom:8px;">Tabs to grant every imported user on first login:</div>
      <div id="permsImportTabList" style="border:1px solid #E8E6DF; border-radius:6px; padding:10px; max-height:220px; overflow-y:auto; margin-bottom:14px;"></div>
      <div style="display:flex; gap:8px; justify-content:flex-end;">
        <button onclick="permsCloseImport()" style="background:none; border:1px solid #E8E6DF; color:#6B7280; padding:8px 14px; border-radius:6px; font-size:13px; cursor:pointer;">Cancel</button>
        <button onclick="permsImportSubmit()" id="permsImportSubmitBtn" style="background:#4a6fa5; color:#fff; border:none; padding:8px 14px; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer;">Submit <span id="permsImportSubmitCount"></span> to IT</button>
      </div>
    </div>
  </div>
</div>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(user-import): import-from-excel button + wizard modal markup"
```

---

## Task 8: Frontend — wizard logic

**Files:**
- Modify: `src/main/resources/static/user-permissions.js`

Add these functions at the end of the "Add user from AD modal" section (before the Helpers section, ~line 478). Reuse `permsEsc`, `appAlert`, `appConfirm`, and `permsState.tabs` (already loaded). Track wizard state on `permsState.import`.

- [ ] **Step 1: Add wizard functions**

```javascript
// ---------------------------------------------------------------------------
// Import users from Excel
// ---------------------------------------------------------------------------

function permsOpenImport() {
    permsState.import = { columns: [], allRows: [], preview: [] };
    document.getElementById('permsImportFile').value = '';
    document.getElementById('permsImportUploadMsg').innerHTML = '';
    document.getElementById('permsImportStepUpload').style.display = '';
    document.getElementById('permsImportStepMapping').style.display = 'none';
    document.getElementById('permsImportStepPreview').style.display = 'none';
    document.getElementById('permsImportModal').style.display = 'flex';
}

function permsCloseImport() {
    document.getElementById('permsImportModal').style.display = 'none';
    permsState.import = null;
}

function permsImportUpload(inputEl) {
    if (!inputEl.files || !inputEl.files.length) return;
    var msg = document.getElementById('permsImportUploadMsg');
    msg.innerHTML = '<span style="color:#6B7280;">Reading & mapping columns…</span>';
    var fd = new FormData();
    fd.append('file', inputEl.files[0]);
    fetch('/api/permissions/import/analyze', { method: 'POST', body: fd })
        .then(function (r) { return r.json(); })
        .then(function (resp) {
            if (!resp.success) { msg.innerHTML = '<span style="color:#B8342B;">' + permsEsc(resp.error || 'Failed to read file.') + '</span>'; return; }
            permsState.import.columns = resp.columns || [];
            permsState.import.allRows = resp.allRows || [];
            permsState.import.mapping = resp.mapping || { nameColumn: -1, emailColumn: -1 };
            permsState.import.rows = resp.rows || [];
            if (resp.confident) {
                permsImportResolve(permsState.import.rows);
            } else {
                permsImportShowMapping(resp.mappingQuestion);
            }
        })
        .catch(function (e) { msg.innerHTML = '<span style="color:#B8342B;">Upload failed: ' + permsEsc(String(e)) + '</span>'; });
}

function permsImportShowMapping(question) {
    document.getElementById('permsImportStepUpload').style.display = 'none';
    document.getElementById('permsImportStepMapping').style.display = '';
    document.getElementById('permsImportMappingQ').textContent =
        question || 'Please confirm which columns hold the name and email.';
    var cols = permsState.import.columns;
    var nameSel = document.getElementById('permsImportNameCol');
    var emailSel = document.getElementById('permsImportEmailCol');
    var opts = '';
    cols.forEach(function (c, i) { opts += '<option value="' + i + '">' + permsEsc(c || ('Column ' + (i + 1))) + '</option>'; });
    nameSel.innerHTML = opts;
    emailSel.innerHTML = '<option value="-1">(none)</option>' + opts;
    if (permsState.import.mapping.nameColumn >= 0) nameSel.value = String(permsState.import.mapping.nameColumn);
    emailSel.value = String(permsState.import.mapping.emailColumn);
}

function permsImportApplyMapping() {
    var nameCol = parseInt(document.getElementById('permsImportNameCol').value, 10);
    var emailCol = parseInt(document.getElementById('permsImportEmailCol').value, 10);
    var rows = permsState.import.allRows.map(function (r) {
        return {
            name: (nameCol >= 0 && r[nameCol] != null) ? String(r[nameCol]).trim() : '',
            email: (emailCol >= 0 && r[emailCol] != null) ? String(r[emailCol]).trim() : ''
        };
    });
    permsImportResolve(rows);
}

function permsImportResolve(rows) {
    document.getElementById('permsImportStepUpload').style.display = 'none';
    document.getElementById('permsImportStepMapping').style.display = 'none';
    document.getElementById('permsImportStepPreview').style.display = '';
    document.getElementById('permsImportSummary').innerHTML = '<span style="color:#6B7280;">Matching ' + rows.length + ' rows against AD…</span>';
    document.getElementById('permsImportRows').innerHTML = '';
    fetch('/api/permissions/import/resolve', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rows: rows })
    }).then(function (r) { return r.json(); })
      .then(function (resp) {
          if (!resp.success) { document.getElementById('permsImportSummary').innerHTML = '<span style="color:#B8342B;">' + permsEsc(resp.error || 'Resolve failed.') + '</span>'; return; }
          permsState.import.preview = resp.rows || [];
          permsImportRenderPreview(resp.summary || {});
          permsImportRenderTabList();
      })
      .catch(function (e) { document.getElementById('permsImportSummary').innerHTML = '<span style="color:#B8342B;">Resolve failed: ' + permsEsc(String(e)) + '</span>'; });
}

function permsImportRenderPreview(summary) {
    var s = summary || {};
    document.getElementById('permsImportSummary').innerHTML =
        '<strong>' + (s.matched || 0) + '</strong> ready &middot; '
        + '<span style="color:#C7801B;">' + (s.ambiguous || 0) + ' need a pick</span> &middot; '
        + '<span style="color:#B8342B;">' + (s.nomatch || 0) + ' no match</span> &middot; '
        + '<span style="color:#155724;">' + (s.alreadyAccess || 0) + ' already have access</span>';
    var html = '';
    permsState.import.preview.forEach(function (row, idx) {
        var badge, tip = '';
        if (row.status === 'matched') badge = '<span style="background:#e8f5e9; color:#1F8A4C; padding:1px 8px; border-radius:8px; font-size:11px;">match</span>';
        else if (row.status === 'already-access') badge = '<span style="background:#e8f0fe; color:#1a3a5c; padding:1px 8px; border-radius:8px; font-size:11px;">already has access</span>';
        else if (row.status === 'ambiguous') badge = '<span style="background:#fff3cd; color:#856404; padding:1px 8px; border-radius:8px; font-size:11px;">pick one</span>';
        else badge = '<span style="background:#f8d7da; color:#721c24; padding:1px 8px; border-radius:8px; font-size:11px;">no match</span>';
        if (row.message) tip = '<div style="font-size:11px; color:#6B7280; margin-top:2px;">' + permsEsc(row.message) + '</div>';

        var matchLine = '';
        if (row.match) matchLine = '<div style="font-size:11px; color:#4a6fa5;">&rarr; ' + permsEsc(row.match.displayName || '') + ' (' + permsEsc(row.match.sAMAccountName || '') + ')</div>';
        else if (row.status === 'ambiguous') {
            var opts = row.candidates.map(function (c, ci) { return '<option value="' + ci + '">' + permsEsc((c.displayName || '') + ' — ' + (c.sAMAccountName || '')) + '</option>'; }).join('');
            matchLine = '<select onchange="permsImportPickCandidate(' + idx + ', this.value)" style="margin-top:4px; padding:4px; border:1px solid #E8E6DF; border-radius:6px; font-size:12px;">'
                + '<option value="-1">— choose —</option>' + opts + '</select>';
        }

        html += '<div style="padding:8px 12px; border-bottom:1px solid #FAFAF7; display:flex; justify-content:space-between; gap:10px;">'
            + '<div><div style="font-size:13px; font-weight:600;">' + permsEsc(row.name || '(no name)') + '</div>'
            + '<div style="font-size:11px; color:#6B7280;">' + permsEsc(row.email || '') + '</div>' + matchLine + tip + '</div>'
            + '<div style="white-space:nowrap;">' + badge + '</div></div>';
    });
    document.getElementById('permsImportRows').innerHTML = html;
    permsImportUpdateCount();
}

function permsImportPickCandidate(idx, ci) {
    var row = permsState.import.preview[idx];
    ci = parseInt(ci, 10);
    if (!row || ci < 0 || !row.candidates[ci]) { row.match = null; row.status = 'ambiguous'; }
    else { row.match = row.candidates[ci]; row.status = 'matched'; }
    permsImportUpdateCount();
}

function permsImportRenderTabList() {
    var listEl = document.getElementById('permsImportTabList');
    if (!listEl) return;
    var html = '';
    permsState.tabs.forEach(function (t) {
        var locked = t.adminOnly || t.permissionsAdmin;
        var defaultCheck = !locked && t.key !== 'aieval' && t.key !== 'extensions';
        var lockNote = '';
        if (t.adminOnly) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; admin only</span>';
        else if (t.permissionsAdmin) lockNote = '<span style="color:#B8342B; font-size:11px; margin-left:6px;">&#128274; permissions admin only</span>';
        html += '<label style="display:flex; align-items:center; padding:6px 4px; border-bottom:1px solid #FAFAF7;' + (locked ? ' opacity:0.55;' : ' cursor:pointer;') + '">'
            + '<input type="checkbox" data-tab-key="' + permsEsc(t.key) + '" ' + (defaultCheck ? 'checked ' : '') + (locked ? 'disabled ' : '') + 'style="margin-right:10px;">'
            + '<span style="font-size:13px;">' + permsEsc(t.label) + '</span>' + lockNote + '</label>';
    });
    listEl.innerHTML = html;
}

function permsImportIncludedRows() {
    return (permsState.import.preview || []).filter(function (row) {
        return row.status === 'matched' && row.match && row.match.sAMAccountName && row.match.email;
    });
}

function permsImportUpdateCount() {
    var n = permsImportIncludedRows().length;
    var el = document.getElementById('permsImportSubmitCount');
    if (el) el.textContent = n + (n === 1 ? ' user' : ' users');
    var btn = document.getElementById('permsImportSubmitBtn');
    if (btn) btn.disabled = n === 0;
}

function permsImportSubmit() {
    var included = permsImportIncludedRows();
    if (!included.length) { appAlert('No matched users with an email to submit.'); return; }
    var checks = document.querySelectorAll('#permsImportTabList input[type="checkbox"]');
    var allowed = [];
    checks.forEach(function (cb) { if (cb.checked && !cb.disabled) allowed.push(cb.getAttribute('data-tab-key')); });

    appConfirm('Submit ' + included.length + ' user' + (included.length === 1 ? '' : 's') + ' to IT for access? One consolidated email will be sent.', { okText: 'Submit' }).then(function (ok) {
        if (!ok) return;
        var users = included.map(function (row) {
            return { sAMAccountName: row.match.sAMAccountName, displayName: row.match.displayName || row.name, email: row.match.email };
        });
        var btn = document.getElementById('permsImportSubmitBtn');
        btn.disabled = true;
        fetch('/api/permissions/import/submit', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rows: users, allowedTabs: allowed })
        }).then(function (r) { return r.json(); })
          .then(function (resp) {
              if (!resp.success) { btn.disabled = false; appAlert('Submit failed: ' + (resp.error || 'unknown')); return; }
              permsCloseImport();
              permsLoadUsers();
              appAlert('Imported ' + resp.ok + ' user' + (resp.ok === 1 ? '' : 's') + '. IT emailed to add them to the access DL'
                  + (resp.failed ? '. ' + resp.failed + ' row(s) failed.' : '.'));
          })
          .catch(function (e) { btn.disabled = false; appAlert('Submit failed: ' + e); });
    });
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/user-permissions.js
git commit -m "feat(user-import): excel import wizard logic (upload/map/preview/submit)"
```

---

## Task 9: Changelog, build, local smoke test

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a What's New entry** at the top of the `WHATS_NEW_RELEASES` array (per the pre-build rule in CLAUDE.md). Match the existing entry shape in that file.

```javascript
  {
    date: '2026-06-24',
    title: 'Bulk user import from Excel',
    items: {
      new: [
        'User Management → "Import from Excel": upload a roster (.xlsx/.xls/.csv) and add many users at once.',
        'Columns are mapped automatically — you only get asked when a column is ambiguous.',
        'Each row is matched against AD; people who already have access are flagged as a warning and skipped.',
        'One consolidated email goes to IT instead of one per user.'
      ]
    }
  },
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn -q test`
Expected: all tests PASS (including the 4 new test classes).

- [ ] **Step 3: Build the JAR**

Run: `mvn -q -DskipTests package`
Expected: `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 4: Copy to local smoke-test setup**

```bash
cp target/plm-field-tracker-1.0.1.jar ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
```

- [ ] **Step 5: Run locally and smoke-test the parse/map path**

Start (heap ≥4g per CLAUDE.md):
```bash
cd ~/Documents/plm-toolkit\ 2 && java -Xmx4g -jar plm-field-tracker-1.0.1.jar --spring.config.additional-location=file:./config/application.properties
```
Log in (plmadmin), then verify `/api/permissions/import/analyze` maps the columns of `~/Downloads/test.xlsx`:
```bash
curl -sS -c /tmp/cookies.txt -H "Content-Type: application/json" \
     -d "{\"username\":\"plmadmin\",\"password\":\"<PWD>\"}" http://localhost:8090/api/auth/login
curl -sS -b /tmp/cookies.txt -F "file=@/Users/vikasjindal/Downloads/test.xlsx" \
     http://localhost:8090/api/permissions/import/analyze | python3 -m json.tool
```
Expected: `success:true`, `mapping` = `{nameColumn:0, emailColumn:1}`, `confident:true`, and 15 `rows` with name/email. (The `resolve`/`submit` AD-dependent paths are verified on the server, since LDAP isn't reachable from the Mac — note this in the result.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): bulk user import from Excel"
```

---

## Self-Review

**Spec coverage:**
- Upload .xlsx/.xls/.csv → Task 1. ✅
- AI column mapping, auto-proceed when confident, ask when unsure → Task 2 (`confident`/`question`) + Task 6 (`analyze`) + Task 8 (`permsImportShowMapping`). ✅
- Heuristic fallback when AI off/unsure → Task 2 `byHeuristic` + fallback path. ✅
- AD resolution email-first, name fallback → Task 3 `resolveRow`. ✅
- Status model matched/ambiguous/nomatch/already-access → Task 3. ✅
- Already-has-access shown as **warning**, skipped by default → Task 3 (`message`) + Task 8 preview badge/filter. ✅
- One tab set for whole batch → Task 8 `permsImportRenderTabList` + submit. ✅
- Consolidated IT email, N pending records → Task 4 + Task 5. ✅
- Activity log `PERMISSIONS_BULK_IMPORT` → Task 5. ✅
- Auth gate (permissions-admin) → Task 6. ✅
- Graceful degradation (AI/AD down) → Task 2 fallback, Task 3 `safeSearch`/`safeDl`. ✅

**Placeholder scan:** none — every step has full code.

**Type consistency:** `Mapping{nameColumn,emailColumn,confident,method,question}`, `PreviewRow{name,email,status,match,candidates,message}`, `Match{sAMAccountName,displayName,email}`, `BulkOutcome{sAMAccountName,displayName,ok,error}` — names match across backend Tasks 2/3/5/6 and the JS in Task 8. `DirectoryUser(username,displayName,email)` constructor matches `LdapAuthService` (verified in source). `submitBulkDLRequest` signature matches the controller call.

**Note on AD-dependent local testing:** `resolve`/`submit` need live LDAP, which isn't reachable from the Mac. Task 9 verifies the parse + AI-mapping path locally; the AD-match and email paths are handed to Vikas to verify on the server (consistent with this project's server-only-dependency handling).
