# Single/Sole Source Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Single/Sole Source" tab to the PLM Toolkit that replaces the standalone `SingleSourceReport.jar` Windows batch — runs a single Oracle query against `agprod` (zero Agile SDK calls), writes the data into the existing Excel template, supports on-demand UI download + monthly scheduled email + SharePoint upload.

**Architecture:** One SQL with three CTEs (one per tab) → flat result set → POJOs → Apache POI template-write. SharePoint upload code is ported verbatim from `SSReport.java`. UI is a single tab with three buttons (Run Now, Download Latest, Send Test Email). Scheduling uses Spring `@Scheduled` gated by `app.scheduling.disabled`, mirroring `RejectionTrackerScheduler`.

**Tech Stack:** Spring Boot 1.x style + Spring `@Scheduled` + Apache POI (already in pom) + javax.mail + Microsoft Graph (HTTP/JSON, no SDK).

**Spec:** [`docs/superpowers/specs/2026-05-08-single-sole-source-report-design.md`](../specs/2026-05-08-single-sole-source-report-design.md)

**Verification model:** This project has no JUnit/Mockito harness, so each task ends with manual verification — curl against `http://localhost:8090` (creds `plmadmin`/`newworld`), openpyxl inspection of the produced xlsx, or SQL row-count comparison. The user explicitly approved "best judgment" so we keep the verification practical, not ceremonial.

---

## Task 1: Project plumbing — template + config keys

**Files:**
- Create: `src/main/resources/templates/Single-Sole Source Report Template.xlsx`
- Modify: `src/main/resources/application.properties` (append config block)
- Modify: `config/application-prod.properties` (append prod-only secrets)
- Modify: `~/Documents/plm-toolkit 2/config/application.properties` (mirror local config — secrets too, since local can't reach SharePoint anyway but service code should not NPE on missing keys)

- [ ] **Step 1: Copy the template into resources**

```bash
cp "/Users/vikasjindal/Documents/SingleSourceReport/Single-Sole Source Report Template.xlsx" \
   "/Users/vikasjindal/git/plm-field-tracker/src/main/resources/templates/Single-Sole Source Report Template.xlsx"
```

- [ ] **Step 2: Verify template has the four expected sheets**

```bash
/opt/anaconda3/bin/python3 -c "
import openpyxl
wb = openpyxl.load_workbook('/Users/vikasjindal/git/plm-field-tracker/src/main/resources/templates/Single-Sole Source Report Template.xlsx')
print(wb.sheetnames)
"
```
Expected output (one of these — whichever matches the source file):
```
['Designation Needed', 'Single Source', 'Sole Source', 'Lists']
```
or
```
['Single MPN-Designation Needed', 'Single MPN-Designation Provided', 'Single-Sole Source Impacted SKU', 'Lists']
```

If the template has the OLD 3-tab structure (`Single MPN-Designation Needed` etc.), use the latest output xlsx as a reference template instead, since the current Java already writes to the new tab names. Source for the new-shape template:
`/Users/vikasjindal/Documents/SingleSourceReport/output/Single-Sole Source Report (04-15-2026).xlsx`. Strip data rows from row 3 onward in code; the template just needs the headers + formatting.

- [ ] **Step 3: Append config block to `src/main/resources/application.properties`**

```properties

# === Single/Sole Source Report (ECN-128313-PROJ) ===
app.singlesole.output.dir=./data/singlesole-reports
app.singlesole.template=classpath:/templates/Single-Sole Source Report Template.xlsx
app.singlesole.runs.file=./data/singlesole-runs.json
app.singlesole.schedule.cron=0 0 2 1 * *
app.singlesole.email.from=PLM-Toolkit@sandisk.com
app.singlesole.email.to=jimmy.sessumes@sandisk.com
app.singlesole.email.cc=vikas.singh3@sandisk.com
app.singlesole.email.subject=Single-Sole Source Report
app.singlesole.sharepoint.enabled=false
app.singlesole.sharepoint.drive.id=
app.singlesole.sharepoint.folder=Reports/Single_Sole_Source_Report
app.singlesole.graph.tenant.id=
app.singlesole.graph.client.id=
app.singlesole.graph.client.secret=
app.singlesole.graph.username=
app.singlesole.graph.password=
app.singlesole.graph.scope=https://graph.microsoft.com/.default
app.singlesole.graph.authority=
```

`sharepoint.enabled=false` and empty graph keys keep local dev safe — the upload service will short-circuit when disabled.

- [ ] **Step 4: Append prod-only secrets to `config/application-prod.properties`**

```properties

# === Single/Sole Source Report ===
app.singlesole.sharepoint.enabled=true
app.singlesole.sharepoint.drive.id=REPLACE_ME
app.singlesole.graph.tenant.id=REPLACE_ME
app.singlesole.graph.client.id=REPLACE_ME
app.singlesole.graph.client.secret=REPLACE_ME
app.singlesole.graph.username=REPLACE_ME
app.singlesole.graph.password=REPLACE_ME
app.singlesole.graph.authority=https://login.microsoftonline.com/REPLACE_ME/oauth2/v2.0/token
```

These come from `/Users/vikasjindal/Documents/SingleSourceReport/SingleSourceReport.properties` — the Windows batch's existing prod config.

- [ ] **Step 5: Make sure `./data/singlesole-reports` exists and is gitignored**

```bash
mkdir -p /Users/vikasjindal/git/plm-field-tracker/data/singlesole-reports
echo 'singlesole-reports/' >> /Users/vikasjindal/git/plm-field-tracker/data/.gitignore || true
echo 'singlesole-runs.json' >> /Users/vikasjindal/git/plm-field-tracker/data/.gitignore || true
```

(Skip if a project-wide `.gitignore` already covers `data/`.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/ src/main/resources/application.properties config/application-prod.properties data/.gitignore
git commit -m "feat(singlesole): scaffold config + template for Single/Sole Source report"
```

---

## Task 2: Row POJO + RunResult POJO

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/model/SingleSoleSourceRow.java`
- Create: `src/main/java/com/sandisk/plm/tracker/model/SingleSoleSourceRunResult.java`

- [ ] **Step 1: Create `SingleSoleSourceRow.java`**

```java
package com.sandisk.plm.tracker.model;

import java.util.Date;

/**
 * One row of the Single/Sole Source report. Mirrors columns A–O of the
 * template tabs. Row granularity:
 *  - Designation Needed: one row per item (first MPN if multiple).
 *  - Single Source / Sole Source: one row per active MPN (item rows repeat).
 */
public class SingleSoleSourceRow {
    public long itemId;
    public String number;
    public String description;
    public String productLine;
    public String lifecyclePhase;
    public String rev;
    public String partType;
    public Integer mpnCount;
    public String singleSoleSource;   // tab partition key
    public String materialGroup;
    public Date createDate;
    public Date revReleaseDate;
    public String mfrName;
    public String mfrPartNumber;
    public String preferredStatus;
}
```

- [ ] **Step 2: Create `SingleSoleSourceRunResult.java`**

```java
package com.sandisk.plm.tracker.model;

public class SingleSoleSourceRunResult {
    public String runId;                 // ISO-8601 UTC
    public String trigger;               // "schedule" | "ui"
    public String userId;
    public int designationNeededCount;
    public int singleSourceCount;
    public int soleSourceCount;
    public String xlsxPath;
    public long xlsxSizeBytes;
    public Boolean sharepointUploaded;   // null if not attempted
    public String sharepointUrl;
    public String sharepointError;
    public Boolean emailSent;            // null if not attempted
    public String emailTo;
    public String emailCc;
    public String emailError;
    public long durationMs;
    public String error;                 // top-level run error if SQL/render failed
}
```

- [ ] **Step 3: Compile to verify**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
mvn -q compile 2>&1 | tail -20
```
Expected: BUILD SUCCESS, zero errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/model/SingleSoleSourceRow.java \
        src/main/java/com/sandisk/plm/tracker/model/SingleSoleSourceRunResult.java
git commit -m "feat(singlesole): row + run-result POJOs"
```

---

## Task 3: SQL service — `SingleSoleSourceService`

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceService.java`

- [ ] **Step 1: Create the service class with the SQL**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Service
public class SingleSoleSourceService {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceService.class.getName());

    @Autowired
    private DataSource dataSource;

    private static final String QUERY =
        "WITH base AS (\n" +
        "  SELECT i.ID AS item_id, i.ITEM_NUMBER AS number_,\n" +
        "         REGEXP_REPLACE(i.DESCRIPTION, '[\\r\\n\\t]+', ' ') AS description,\n" +
        "         (SELECT LISTAGG(le.ENTRYVALUE, '; ') WITHIN GROUP (ORDER BY le.ENTRYVALUE)\n" +
        "            FROM AGILE.LISTENTRY le\n" +
        "           WHERE le.LANGID = 0\n" +
        "             AND INSTR(i.PRODUCT_LINES, ',' || le.ENTRYID || ',') > 0) AS product_line,\n" +
        "         n_lcp.DESCRIPTION AS lifecycle_phase,\n" +
        "         r.REV_NUMBER AS rev,\n" +
        "         n_sub.DESCRIPTION AS part_type,\n" +
        "         TO_NUMBER(NULLIF(TRIM(p2.TEXT67), '')) AS mpn_count,\n" +
        "         le_ss.ENTRYVALUE AS single_sole_source,\n" +
        "         le_mg.ENTRYVALUE AS material_group,\n" +
        "         TRUNC(p2.DATE04) AS create_date,\n" +
        "         TRUNC(r.RELEASE_DATE) AS rev_release_date\n" +
        "    FROM AGILE.ITEM i\n" +
        "    JOIN AGILE.REV r ON r.ITEM = i.ID AND r.CHANGE = i.DEFAULT_CHANGE AND r.SITE = 0\n" +
        "    JOIN AGILE.PAGE_TWO p2 ON p2.ID = i.ID\n" +
        "    LEFT JOIN AGILE.NODETABLE n_lcp ON n_lcp.ID = r.RELEASE_TYPE\n" +
        "    LEFT JOIN AGILE.NODETABLE n_sub ON n_sub.ID = i.SUBCLASS\n" +
        "    LEFT JOIN AGILE.LISTENTRY le_ss ON le_ss.ENTRYID = p2.LIST77 AND le_ss.LANGID = 0\n" +
        "    LEFT JOIN AGILE.LISTENTRY le_mg ON le_mg.ENTRYID = p2.LIST20 AND le_mg.LANGID = 0\n" +
        "   WHERE NVL(i.DELETE_FLAG, 0) <> 1\n" +
        "     AND i.SUBCLASS IN (SELECT ID FROM AGILE.NODETABLE WHERE PARENTID = 10004)\n" +
        "     AND p2.LIST77 IN (4026098, 4026102, 4026103)\n" +
        "     AND NVL(n_lcp.DESCRIPTION, 'X') NOT IN ('OBS','OBS-SKU','Preliminary')\n" +
        ")\n" +
        "SELECT b.item_id, b.number_, b.description, b.product_line, b.lifecycle_phase, b.rev,\n" +
        "       b.part_type, b.mpn_count, b.single_sole_source, b.material_group,\n" +
        "       b.create_date, b.rev_release_date,\n" +
        "       mfr.NAME AS mfr_name, mp.PART_NUMBER AS mfr_part_number,\n" +
        "       le_ps.ENTRYVALUE AS preferred_status\n" +
        "  FROM base b\n" +
        "  LEFT JOIN AGILE.MANU_BY mb ON mb.AGILE_PART = b.item_id AND mb.CHANGE_OUT = 0\n" +
        "  LEFT JOIN AGILE.MANU_PARTS mp ON mp.ID = mb.MANU_PART\n" +
        "  LEFT JOIN AGILE.MANUFACTURERS mfr ON mfr.ID = mp.MANU_ID\n" +
        "  LEFT JOIN AGILE.LISTENTRY le_ps ON le_ps.ENTRYID = mb.PREFER_STATUS AND le_ps.LANGID = 0\n" +
        " ORDER BY b.rev_release_date DESC NULLS LAST, b.number_";

    /** Returns ALL rows (Designation Needed: one per item; Single/Sole: one per active MPN). */
    public List<SingleSoleSourceRow> fetch() throws Exception {
        long t0 = System.currentTimeMillis();
        List<SingleSoleSourceRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(QUERY);
             ResultSet rs = ps.executeQuery()) {
            ps.setFetchSize(1000);
            // De-dup for "Designation Needed": only keep first MPN row per item_id.
            // (For Single Source / Sole Source we keep all MPN rows.)
            java.util.Set<Long> seenDesignation = new java.util.HashSet<>();
            while (rs.next()) {
                String src = rs.getString("single_sole_source");
                long itemId = rs.getLong("item_id");
                if ("Designation Needed".equals(src) && !seenDesignation.add(itemId)) {
                    continue;  // skip 2nd+ MPN row for the same item on the Designation tab
                }
                SingleSoleSourceRow r = new SingleSoleSourceRow();
                r.itemId = itemId;
                r.number = rs.getString("number_");
                r.description = rs.getString("description");
                r.productLine = rs.getString("product_line");
                r.lifecyclePhase = rs.getString("lifecycle_phase");
                r.rev = rs.getString("rev");
                r.partType = rs.getString("part_type");
                int mpn = rs.getInt("mpn_count");
                r.mpnCount = rs.wasNull() ? null : mpn;
                r.singleSoleSource = src;
                r.materialGroup = rs.getString("material_group");
                java.sql.Date cd = rs.getDate("create_date");
                r.createDate = cd == null ? null : new java.util.Date(cd.getTime());
                java.sql.Date rd = rs.getDate("rev_release_date");
                r.revReleaseDate = rd == null ? null : new java.util.Date(rd.getTime());
                r.mfrName = rs.getString("mfr_name");
                r.mfrPartNumber = rs.getString("mfr_part_number");
                r.preferredStatus = rs.getString("preferred_status");
                rows.add(r);
            }
        }
        logger.info("[SS-REPORT] fetched " + rows.size() + " rows in "
                + (System.currentTimeMillis() - t0) + " ms");
        return rows;
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify SQL row counts via standalone Python (sanity gate)**

The SQL was already verified during design (308 / 838 / 31 items, 634 / 2023 / 70 with MPN expansion). Skip if you trust the design-phase numbers; otherwise re-run the count check against agprod.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceService.java
git commit -m "feat(singlesole): SQL service for fetching report rows"
```

---

## Task 4: Excel writer — `SingleSoleSourceExcelService`

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceExcelService.java`

- [ ] **Step 1: Create the writer**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFCreationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

@Service
public class SingleSoleSourceExcelService {

    @Value("${app.singlesole.template}")
    private String templateLocation;

    @Autowired
    private ResourceLoader resourceLoader;

    private static final int DATA_START_ROW = 2;            // 0-indexed; row 3 in Excel
    private static final String[] TABS = {"Designation Needed", "Single Source", "Sole Source"};

    /** Writes the workbook to {@code out}. Returns counts per tab in the same order as TABS. */
    public int[] write(List<SingleSoleSourceRow> all, OutputStream out) throws Exception {
        Resource res = resourceLoader.getResource(templateLocation);
        try (InputStream in = res.getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            int[] counts = new int[TABS.length];
            for (int i = 0; i < TABS.length; i++) {
                String tab = TABS[i];
                XSSFSheet sheet = wb.getSheet(tab);
                if (sheet == null) {
                    throw new IllegalStateException("Template missing tab: " + tab);
                }
                List<SingleSoleSourceRow> rows = filterFor(all, tab);
                counts[i] = rows.size();
                writeSheet(wb, sheet, rows);
            }
            wb.write(out);
            return counts;
        }
    }

    private static List<SingleSoleSourceRow> filterFor(List<SingleSoleSourceRow> all, String tab) {
        java.util.ArrayList<SingleSoleSourceRow> out = new java.util.ArrayList<>();
        for (SingleSoleSourceRow r : all) {
            if (tab.equalsIgnoreCase(r.singleSoleSource)) out.add(r);
        }
        return out;
    }

    private static void writeSheet(XSSFWorkbook wb, XSSFSheet sheet, List<SingleSoleSourceRow> rows) {
        // Clear any data rows below the header (rows 0 and 1 are template title + headers).
        clearOldData(sheet, DATA_START_ROW);

        // Build column styles by cloning the template's data-row styles (row 3 = index 2).
        // Use 15 columns (A..O). H (index 7) is the "(To Be)" column — left blank.
        CellStyle[] styles = buildColStyles(wb, sheet, DATA_START_ROW, 15);
        CellStyle dateStyleK = cloneAsDateOnly(wb, styles[10]);  // Create Date
        CellStyle dateStyleL = cloneAsDateOnly(wb, styles[11]);  // Last Release Date

        int r = DATA_START_ROW;
        for (SingleSoleSourceRow row : rows) {
            Row excelRow = sheet.getRow(r);
            if (excelRow == null) excelRow = sheet.createRow(r);
            setStr(excelRow, 0,  row.productLine,      styles);
            setStr(excelRow, 1,  row.number,           styles);
            setStr(excelRow, 2,  row.description,      styles);
            setStr(excelRow, 3,  row.lifecyclePhase,   styles);
            setStr(excelRow, 4,  row.rev,              styles);
            if (row.mpnCount != null) {
                Cell c = excelRow.createCell(5);
                c.setCellValue(row.mpnCount.intValue());
                if (styles[5] != null) c.setCellStyle(styles[5]);
            } else {
                setStr(excelRow, 5, "", styles);
            }
            setStr(excelRow, 6,  row.singleSoleSource, styles);
            setStr(excelRow, 7,  "",                   styles);  // (To Be) — blank
            setStr(excelRow, 8,  row.partType,         styles);
            setStr(excelRow, 9,  row.materialGroup,    styles);
            setDate(excelRow, 10, row.createDate,      dateStyleK);
            setDate(excelRow, 11, row.revReleaseDate,  dateStyleL);
            setStr(excelRow, 12, row.mfrName,          styles);
            setStr(excelRow, 13, row.mfrPartNumber,    styles);
            setStr(excelRow, 14, row.preferredStatus,  styles);
            r++;
        }
    }

    private static void clearOldData(XSSFSheet sheet, int startRow) {
        int last = sheet.getLastRowNum();
        for (int i = startRow; i <= last; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            for (int j = 0; j < 15; j++) {
                Cell c = row.getCell(j);
                if (c != null && c.getCellType() != CellType.FORMULA) {
                    c.setBlank();
                }
            }
        }
    }

    private static CellStyle[] buildColStyles(XSSFWorkbook wb, XSSFSheet s, int templateRow, int cols) {
        CellStyle[] out = new CellStyle[cols];
        Row tr = s.getRow(templateRow);
        if (tr == null) return out;
        for (int i = 0; i < cols; i++) {
            Cell tc = tr.getCell(i);
            if (tc != null) {
                XSSFCellStyle cs = wb.createCellStyle();
                cs.cloneStyleFrom(tc.getCellStyle());
                out[i] = cs;
            }
        }
        return out;
    }

    private static CellStyle cloneAsDateOnly(XSSFWorkbook wb, CellStyle base) {
        XSSFCellStyle ds = wb.createCellStyle();
        if (base != null) ds.cloneStyleFrom(base);
        XSSFCreationHelper helper = wb.getCreationHelper();
        ds.setDataFormat(helper.createDataFormat().getFormat("dd-MMM-yyyy"));
        return ds;
    }

    private static void setStr(Row r, int c, String v, CellStyle[] styles) {
        Cell cell = r.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        if (styles != null && c < styles.length && styles[c] != null) cell.setCellStyle(styles[c]);
    }

    private static void setDate(Row r, int c, Date d, CellStyle style) {
        Cell cell = r.getCell(c);
        if (cell == null) cell = r.createCell(c);
        if (d == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(d);
            if (style != null) cell.setCellStyle(style);
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceExcelService.java
git commit -m "feat(singlesole): Excel writer using template-clone styles"
```

---

## Task 5: Run history persistence + run orchestrator

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceRunHistory.java`
- Modify: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceService.java` — add `runReport(...)` method

- [ ] **Step 1: Create `SingleSoleSourceRunHistory.java`**

```java
package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SingleSoleSourceRunHistory {

    private static final int MAX_KEEP = 60;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    @Value("${app.singlesole.runs.file}")
    private String runsFile;

    public void append(SingleSoleSourceRunResult result) throws IOException {
        lock.lock();
        try {
            List<SingleSoleSourceRunResult> all = readAll();
            all.add(0, result);
            while (all.size() > MAX_KEEP) all.remove(all.size() - 1);
            File f = new File(runsFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            json.writerWithDefaultPrettyPrinter().writeValue(f, all);
        } finally {
            lock.unlock();
        }
    }

    public List<SingleSoleSourceRunResult> readAll() {
        File f = new File(runsFile);
        if (!f.exists() || f.length() == 0) return new ArrayList<>();
        try {
            return new ArrayList<>(java.util.Arrays.asList(
                    json.readValue(f, SingleSoleSourceRunResult[].class)));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public SingleSoleSourceRunResult latest() {
        List<SingleSoleSourceRunResult> all = readAll();
        return all.isEmpty() ? null : all.get(0);
    }
}
```

- [ ] **Step 2: Add `runReport(...)` method to `SingleSoleSourceService`**

Add these imports:
```java
import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Value;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
```

Add fields:
```java
@Autowired private SingleSoleSourceExcelService excelService;
@Autowired private SingleSoleSourceRunHistory runHistory;
@Autowired(required = false) private SingleSoleSourceSharePointUploader sharepointUploader;
@Autowired(required = false) private SingleSoleSourceEmailService emailService;

@Value("${app.singlesole.output.dir}")
private String outputDir;
```

Add method:
```java
public SingleSoleSourceRunResult runReport(String trigger, String userId,
                                           boolean uploadSharePoint, boolean sendEmail) {
    long t0 = System.currentTimeMillis();
    SingleSoleSourceRunResult res = new SingleSoleSourceRunResult();
    res.runId = Instant.now().toString();
    res.trigger = trigger;
    res.userId = userId;
    File out = null;
    try {
        List<SingleSoleSourceRow> rows = fetch();

        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();
        String fname = "Single-Sole Source Report ("
                + new SimpleDateFormat("MM-dd-yyyy").format(new Date()) + ").xlsx";
        out = new File(dir, fname);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            int[] counts = excelService.write(rows, fos);
            res.designationNeededCount = counts[0];
            res.singleSourceCount = counts[1];
            res.soleSourceCount = counts[2];
        }
        res.xlsxPath = out.getAbsolutePath();
        res.xlsxSizeBytes = out.length();

        if (uploadSharePoint && sharepointUploader != null) {
            try {
                String url = sharepointUploader.upload(out);
                res.sharepointUploaded = true;
                res.sharepointUrl = url;
            } catch (Exception e) {
                res.sharepointUploaded = false;
                res.sharepointError = e.getMessage();
                logger.warning("[SS-REPORT] sharepoint upload failed: " + e.getMessage());
            }
        }
        if (sendEmail && emailService != null) {
            try {
                emailService.send(out, res);
                res.emailSent = true;
            } catch (Exception e) {
                res.emailSent = false;
                res.emailError = e.getMessage();
                logger.warning("[SS-REPORT] email failed: " + e.getMessage());
            }
        }
    } catch (Exception e) {
        res.error = e.getMessage();
        logger.severe("[SS-REPORT] run failed: " + e.getMessage());
    } finally {
        res.durationMs = System.currentTimeMillis() - t0;
        try { runHistory.append(res); } catch (Exception ignore) {}
    }
    return res;
}
```

(`SingleSoleSourceSharePointUploader` and `SingleSoleSourceEmailService` are added in subsequent tasks. Marking them `required = false` lets the service compile + run before those exist; `runReport` will simply not upload/email if the bean isn't present.)

- [ ] **Step 3: Compile**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceRunHistory.java \
        src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceService.java
git commit -m "feat(singlesole): run orchestrator + JSON run-history persistence"
```

---

## Task 6: SharePoint uploader (port verbatim from `SSReport.java`)

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceSharePointUploader.java`

- [ ] **Step 1: Create the uploader**

```java
package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Microsoft Graph upload — ROPC (resource-owner-password-credential) flow,
 * ported verbatim from the legacy SSReport.java (Windows batch).
 *
 * Disabled when {@code app.singlesole.sharepoint.enabled=false} (default in
 * application.properties; only application-prod.properties flips it on).
 */
@Service
public class SingleSoleSourceSharePointUploader {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceSharePointUploader.class.getName());

    @Value("${app.singlesole.sharepoint.enabled:false}") private boolean enabled;
    @Value("${app.singlesole.sharepoint.drive.id:}")     private String driveId;
    @Value("${app.singlesole.sharepoint.folder:}")       private String folder;
    @Value("${app.singlesole.graph.tenant.id:}")         private String tenantId;
    @Value("${app.singlesole.graph.client.id:}")         private String clientId;
    @Value("${app.singlesole.graph.client.secret:}")     private String clientSecret;
    @Value("${app.singlesole.graph.username:}")          private String username;
    @Value("${app.singlesole.graph.password:}")          private String password;
    @Value("${app.singlesole.graph.scope:https://graph.microsoft.com/.default}") private String scope;
    @Value("${app.singlesole.graph.authority:}")         private String authority;

    /** Returns the SharePoint URL of the uploaded file, or null if disabled / no-op. */
    public String upload(File file) throws Exception {
        if (!enabled) {
            logger.info("[SS-REPORT] SharePoint upload disabled — skipping");
            return null;
        }
        String token = getRopcToken();
        String clean = stripSlashes(folder);
        String url = "https://graph.microsoft.com/v1.0/drives/" + driveId
                + "/root:/" + encodePath(clean) + "/" + encodePath(file.getName())
                + ":/content";
        logger.info("[SS-REPORT] PUT " + url);
        return httpPut(url, token, file);
    }

    private String getRopcToken() throws Exception {
        String body = "grant_type=password"
                + "&client_id=" + enc(clientId)
                + "&username=" + enc(username)
                + "&password=" + enc(password)
                + "&scope=" + enc(scope)
                + "&client_secret=" + enc(clientSecret);
        HttpURLConnection con = (HttpURLConnection) new URL(authority).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setConnectTimeout(60000);
        con.setReadTimeout(60000);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        String resp = readAll(code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Graph token HTTP " + code + " :: " + resp);
        }
        String tok = jsonString(resp, "access_token");
        if (tok == null || tok.trim().isEmpty()) {
            throw new RuntimeException("No access_token in: " + resp);
        }
        return tok;
    }

    private String httpPut(String url, String token, File file) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("PUT");
        con.setDoOutput(true);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Content-Type", "application/octet-stream");
        con.setConnectTimeout(120000);
        con.setReadTimeout(120000);
        try (OutputStream os = con.getOutputStream();
             InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) >= 0) os.write(buf, 0, n);
        }
        int code = con.getResponseCode();
        String resp = readAll(code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Upload HTTP " + code + " :: " + resp);
        }
        return jsonString(resp, "webUrl");
    }

    private static String stripSlashes(String s) {
        if (s == null) return "";
        if (s.startsWith("/")) s = s.substring(1);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String encodePath(String path) throws UnsupportedEncodingException {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String jsonString(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceSharePointUploader.java
git commit -m "feat(singlesole): SharePoint Graph uploader (ROPC, ported from legacy)"
```

---

## Task 7: Email service

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceEmailService.java`

- [ ] **Step 1: Create the email service** — uses existing `EmailTemplateService` for the IBM-Plex/sandisk-pill layout per CLAUDE.md.

```java
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

@Service
public class SingleSoleSourceEmailService {

    @Value("${mail.smtp.host}") private String smtpHost;
    @Value("${mail.smtp.port:25}") private int smtpPort;

    @Value("${app.singlesole.email.from}")    private String from;
    @Value("${app.singlesole.email.to}")      private String to;
    @Value("${app.singlesole.email.cc:}")     private String cc;
    @Value("${app.singlesole.email.subject}") private String subject;

    @Autowired private EmailTemplateService tpl;

    /** Sends to the configured To/Cc with the xlsx attached. */
    public void send(File attachment, SingleSoleSourceRunResult res) throws Exception {
        send(to, cc, attachment, res);
    }

    /** Sends to a custom recipient (used by the "Send Test Email" UI button). */
    public void send(String toAddr, String ccAddr, File attachment, SingleSoleSourceRunResult res) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        for (String r : toAddr.split("[,;]\\s*")) {
            if (!r.isEmpty()) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(r.trim()));
        }
        if (ccAddr != null && !ccAddr.trim().isEmpty()) {
            for (String r : ccAddr.split("[,;]\\s*")) {
                if (!r.isEmpty()) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(r.trim()));
            }
        }
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy"));
        msg.setSubject(subject + " · " + date);

        // Body
        String kpiRow = tpl.kpiRow(
                tpl.kpiTile("Designation Needed", String.valueOf(res.designationNeededCount), null),
                tpl.kpiTile("Single Source", String.valueOf(res.singleSourceCount), null),
                tpl.kpiTile("Sole Source", String.valueOf(res.soleSourceCount), null)
        );
        String body =
            "<p style='margin:0 0 10px 0;'>Dear Agile User,</p>" +
            "<p style='margin:0 0 16px 0;'>Attached is the report of Single/Sole Source components in Agile. " +
            "Please review and respond back with changes you may need to the currently assigned single-sole source " +
            "identification under the &lsquo;Single/Sole Source (To Be)&rsquo; column.</p>";
        String attachStrip = tpl.attachmentStrip(attachment.getName(),
                (res.designationNeededCount + res.singleSourceCount + res.soleSourceCount) + " rows");
        String html = tpl.wrap("Single/Sole Source", "Monthly Review",
                "Single-Sole Source Report",
                "Components flagged for single/sole supplier review",
                kpiRow + body + attachStrip);

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
```

> Note: `EmailTemplateService.wrap(...)` may not have the exact signature above. If it differs (check the class), adapt the call to whatever it accepts. The `EmailTemplateService` already used by `EmailService` is the right place to inspect.

- [ ] **Step 2: Compile**

If `EmailTemplateService` signatures don't match, fix the call site. Compile until clean.

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -20
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceEmailService.java
git commit -m "feat(singlesole): email service with KPI tile + attachment"
```

---

## Task 8: REST controller

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/controller/SingleSoleSourceController.java`

- [ ] **Step 1: Create the controller**

```java
package com.sandisk.plm.tracker.controller;

import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import com.sandisk.plm.tracker.service.SingleSoleSourceEmailService;
import com.sandisk.plm.tracker.service.SingleSoleSourceRunHistory;
import com.sandisk.plm.tracker.service.SingleSoleSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/single-sole-source")
public class SingleSoleSourceController {

    @Autowired private SingleSoleSourceService service;
    @Autowired private SingleSoleSourceRunHistory runHistory;
    @Autowired(required = false) private SingleSoleSourceEmailService emailService;

    @Value("${app.singlesole.output.dir}")
    private String outputDir;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        SingleSoleSourceRunResult last = runHistory.latest();
        out.put("lastRun", last);
        out.put("recent", runHistory.readAll().subList(0, Math.min(10, runHistory.readAll().size())));
        return out;
    }

    /** Body params: uploadSharePoint=true|false, sendEmail=true|false (both default false). */
    @PostMapping("/run")
    public SingleSoleSourceRunResult run(@RequestBody(required = false) Map<String, Object> body,
                                         HttpSession session) {
        boolean upload = body != null && Boolean.TRUE.equals(body.get("uploadSharePoint"));
        boolean email  = body != null && Boolean.TRUE.equals(body.get("sendEmail"));
        String userId = (String) session.getAttribute("username");
        return service.runReport("ui", userId, upload, email);
    }

    @GetMapping("/download/latest")
    public ResponseEntity<FileSystemResource> downloadLatest() {
        File dir = new File(outputDir);
        File[] files = dir.exists() ? dir.listFiles((f) -> f.getName().endsWith(".xlsx")) : null;
        if (files == null || files.length == 0) {
            return ResponseEntity.notFound().build();
        }
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        HttpHeaders h = new HttpHeaders();
        h.setContentDispositionFormData("attachment", latest.getName());
        return ResponseEntity.ok()
                .headers(h)
                .contentLength(latest.length())
                .contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new FileSystemResource(latest));
    }

    /** Sends the LATEST xlsx to a custom recipient (defaults to the logged-in user). */
    @PostMapping("/send-test")
    public Map<String, Object> sendTest(@RequestBody(required = false) Map<String, String> body,
                                        HttpSession session) {
        Map<String, Object> out = new HashMap<>();
        if (emailService == null) {
            out.put("ok", false); out.put("error", "Email service not configured"); return out;
        }
        File latest = findLatestXlsx();
        if (latest == null) {
            out.put("ok", false); out.put("error", "No report file found — run the report first."); return out;
        }
        String to = body != null ? body.get("to") : null;
        if (to == null || to.trim().isEmpty()) {
            to = (String) session.getAttribute("email");
        }
        if (to == null || to.trim().isEmpty()) {
            out.put("ok", false); out.put("error", "No recipient — login session has no email"); return out;
        }
        try {
            SingleSoleSourceRunResult last = runHistory.latest();
            if (last == null) last = new SingleSoleSourceRunResult();
            emailService.send(to, "", latest, last);
            out.put("ok", true); out.put("sentTo", to);
        } catch (Exception e) {
            out.put("ok", false); out.put("error", e.getMessage());
        }
        return out;
    }

    private File findLatestXlsx() {
        File dir = new File(outputDir);
        File[] files = dir.exists() ? dir.listFiles((f) -> f.getName().endsWith(".xlsx")) : null;
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
git add src/main/java/com/sandisk/plm/tracker/controller/SingleSoleSourceController.java
git commit -m "feat(singlesole): REST endpoints for status/run/download/send-test"
```

---

## Task 9: Scheduler

**Files:**
- Create: `src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceScheduler.java`

- [ ] **Step 1: Create the scheduler**

```java
package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.model.SingleSoleSourceRunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * Monthly scheduled run of the Single/Sole Source report.
 * Honors {@code app.scheduling.disabled} (the global flag — when true,
 * Spring's scheduling subsystem is disabled and this method never fires).
 */
@Service
public class SingleSoleSourceScheduler {

    private static final Logger logger = Logger.getLogger(SingleSoleSourceScheduler.class.getName());

    @Autowired private SingleSoleSourceService service;
    @Autowired private MaintenanceService maintenanceService;

    @Scheduled(cron = "${app.singlesole.schedule.cron}")
    public void monthlyRun() {
        if (maintenanceService != null && maintenanceService.isInMaintenanceMode()) {
            logger.info("[SS-CRON] skipping — maintenance mode");
            return;
        }
        logger.info("[SS-CRON] starting monthly Single/Sole Source report");
        SingleSoleSourceRunResult r = service.runReport("schedule", "system", true, true);
        logger.info("[SS-CRON] done — designation=" + r.designationNeededCount
                + " single=" + r.singleSourceCount
                + " sole=" + r.soleSourceCount
                + " sharepoint=" + r.sharepointUploaded
                + " email=" + r.emailSent
                + " duration=" + r.durationMs + "ms");
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
git add src/main/java/com/sandisk/plm/tracker/service/SingleSoleSourceScheduler.java
git commit -m "feat(singlesole): @Scheduled monthly cron (1st of month, 2am)"
```

---

## Task 10: UI tab — HTML + JS

**Files:**
- Modify: `src/main/resources/static/index.html` (add tab button + panel)
- Create: `src/main/resources/static/single-sole-source.js`
- Modify: `src/main/resources/static/app.js` (register `singlesole` in `switchTab` if needed — check the existing pattern first)

- [ ] **Step 1: Add the tab button to `index.html`**

Find the row of `<button class="tab" ...>` definitions (around line 222–248) and add a new button before "ECN Report":

```html
    <button class="tab" id="tabSingleSole" onclick="switchTab('singlesole')" style="display:none;">Single/Sole Source</button>
```

Style it `display:none` initially — the existing pattern shows admin-only tabs default to none and get unhidden in `app.js` based on `isPlmAdmin`.

- [ ] **Step 2: Add the panel** in `index.html`

Add a panel `<div id="singlesole" class="tab-panel" style="display:none;">…</div>` near the other tab panels. Use the existing tab-panel patterns (look at how `ecnreport` panel is structured) so spacing/typography matches.

```html
<div id="singlesole" class="tab-panel" style="display:none;">
  <div class="panel-header">
    <h2>Single/Sole Source Report</h2>
    <p class="muted">Monthly review report for components with <code>Single/Sole Source</code> field set
       to Designation Needed, Single Source, or Sole Source.
       Replaces the legacy <code>SingleSourceReport.jar</code> Windows batch — zero Agile SDK calls.</p>
  </div>
  <div class="panel-body">
    <div class="actions" style="margin-bottom:16px;">
      <button id="ssRunBtn"      class="btn btn-primary"  onclick="ssRun()">Run Now</button>
      <button id="ssDownloadBtn" class="btn"              onclick="ssDownload()">Download Latest .xlsx</button>
      <button id="ssSendTestBtn" class="btn"              onclick="ssSendTest()">Send Test Email (to me)</button>
      <label style="margin-left:16px;"><input type="checkbox" id="ssOptUpload"/> Upload to SharePoint</label>
      <label style="margin-left:8px;"> <input type="checkbox" id="ssOptEmail"/> Send to recipients</label>
    </div>
    <div id="ssStatus" class="status-box muted">Loading…</div>
    <div id="ssRunLog" style="margin-top:24px;"></div>
  </div>
</div>
```

- [ ] **Step 3: Create `single-sole-source.js`**

```javascript
// Single/Sole Source Report tab
async function ssRefreshStatus() {
  const el = document.getElementById('ssStatus');
  if (!el) return;
  try {
    const r = await fetch('/api/single-sole-source/status');
    if (!r.ok) { el.textContent = 'Failed to load status: HTTP ' + r.status; return; }
    const j = await r.json();
    const last = j.lastRun;
    if (!last) {
      el.innerHTML = '<em>No runs yet. Click <strong>Run Now</strong> to generate the first report.</em>';
      return;
    }
    const counts = `Designation Needed <strong>${last.designationNeededCount}</strong> · ` +
                   `Single Source <strong>${last.singleSourceCount}</strong> · ` +
                   `Sole Source <strong>${last.soleSourceCount}</strong>`;
    el.innerHTML = `
      <div><strong>Last run:</strong> ${last.runId} (${last.trigger}, ${last.durationMs} ms)</div>
      <div><strong>Row counts:</strong> ${counts}</div>
      <div><strong>Output:</strong> <code>${last.xlsxPath || ''}</code> ` +
        `(${(last.xlsxSizeBytes/1024).toFixed(0)} KB)</div>` +
      (last.sharepointUploaded ?
        `<div><strong>SharePoint:</strong> <a href="${last.sharepointUrl}" target="_blank">${last.sharepointUrl}</a></div>` :
        last.sharepointError ? `<div><strong>SharePoint error:</strong> ${last.sharepointError}</div>` : '') +
      (last.emailSent ?
        `<div><strong>Email sent</strong> ✓</div>` :
        last.emailError ? `<div><strong>Email error:</strong> ${last.emailError}</div>` : '') +
      (last.error ? `<div style="color:#B8342B;"><strong>Run error:</strong> ${last.error}</div>` : '');
  } catch (e) {
    el.textContent = 'Status fetch failed: ' + e.message;
  }
}

async function ssRun() {
  const btn = document.getElementById('ssRunBtn');
  btn.disabled = true; btn.textContent = 'Running…';
  try {
    const upload = document.getElementById('ssOptUpload').checked;
    const email  = document.getElementById('ssOptEmail').checked;
    const r = await fetch('/api/single-sole-source/run', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({uploadSharePoint: upload, sendEmail: email})
    });
    const j = await r.json();
    if (j.error) {
      alert('Run failed: ' + j.error);
    } else {
      alert(`Done in ${j.durationMs} ms · DN=${j.designationNeededCount} SS=${j.singleSourceCount} Sole=${j.soleSourceCount}`);
    }
    await ssRefreshStatus();
  } catch (e) {
    alert('Run failed: ' + e.message);
  } finally {
    btn.disabled = false; btn.textContent = 'Run Now';
  }
}

function ssDownload() {
  window.location = '/api/single-sole-source/download/latest';
}

async function ssSendTest() {
  const btn = document.getElementById('ssSendTestBtn');
  btn.disabled = true;
  try {
    const r = await fetch('/api/single-sole-source/send-test', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: '{}'
    });
    const j = await r.json();
    alert(j.ok ? 'Email sent to ' + j.sentTo : 'Failed: ' + (j.error || 'unknown'));
  } catch (e) {
    alert('Failed: ' + e.message);
  } finally {
    btn.disabled = false;
  }
}

// Hook into the existing tab-switch event so we refresh status when shown.
document.addEventListener('DOMContentLoaded', function () {
  const t = document.getElementById('tabSingleSole');
  if (t) t.addEventListener('click', function() { setTimeout(ssRefreshStatus, 100); });
});
```

- [ ] **Step 4: Wire the JS** — add `<script src="single-sole-source.js"></script>` to `index.html` near other tab JS includes.

- [ ] **Step 5: Reveal the tab to admins** — find the `app.js` block that unhides admin tabs (`tabAiEval`, `tabPermissions`, etc.) and add `tabSingleSole` to the same logic. (Alternatively, leave the tab visible to all logged-in users — the report has no PII; check what the team prefers and default to admin-only for now to match `tabAiEval`.)

- [ ] **Step 6: Compile + commit**

```bash
cd /Users/vikasjindal/git/plm-field-tracker && mvn -q compile 2>&1 | tail -10
git add src/main/resources/static/index.html \
        src/main/resources/static/single-sole-source.js \
        src/main/resources/static/app.js
git commit -m "feat(singlesole): UI tab — Run / Download / Send Test"
```

---

## Task 11: What's New release entry

**Files:**
- Modify: `src/main/resources/static/whats-new.js`

- [ ] **Step 1: Add a new entry at the top of `WHATS_NEW_RELEASES`**

```javascript
{
  date: '2026-05-08',
  title: 'New: Single/Sole Source Report tab',
  items: [
    { badge: 'new', text: '<strong>"Single/Sole Source" tab</strong> — replaces the legacy <code>SingleSourceReport.jar</code> Windows batch (ECN-128313-PROJ). Three sub-reports (Designation Needed / Single Source / Sole Source) drawn from one Oracle query against agprod, written into the existing Excel template. <strong>Zero Agile SDK calls.</strong>' },
    { badge: 'new', text: 'Monthly cron (1st of month, 2 AM) auto-emails <code>jimmy.sessumes@sandisk.com</code> (cc <code>vikas.singh3@sandisk.com</code>) and uploads to SharePoint <code>Reports/Single_Sole_Source_Report/</code>. The Windows batch on F:\\Batch\\Shruthi\\ is being decommissioned.' },
    { badge: 'improve', text: '<strong>Manufacturer rows now expand</strong> on the Single Source / Sole Source tabs — one row per active MPN. (The legacy job only emitted the first MPN per item.)' }
  ]
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/whats-new.js
git commit -m "docs(whats-new): announce Single/Sole Source Report tab"
```

---

## Task 12: Build, deploy locally, smoke-test

- [ ] **Step 1: Build the JAR**

```bash
cd /Users/vikasjindal/git/plm-field-tracker
JAVA_HOME=/Users/vikasjindal/Library/Java/JavaVirtualMachines/corretto-1.8.0_432/Contents/Home \
  mvn -q -DskipTests package 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`, `target/plm-field-tracker-1.0.1.jar` produced.

- [ ] **Step 2: Copy to local plm-toolkit and the production drop point per CLAUDE.md**

```bash
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
   ~/Documents/plm-toolkit\ 2/plm-field-tracker-1.0.1.jar
# Production drop is best-effort; warn if not mounted
cp ~/git/plm-field-tracker/target/plm-field-tracker-1.0.1.jar \
   /Volumes/uls-ep-aglapp01/plm-toolkit/ 2>/dev/null \
   || echo "WARN: prod drop volume not mounted — skipping (this is expected in local dev)"
```

- [ ] **Step 3: Restart the local toolkit**

```bash
# Find and kill the existing local process
pgrep -f 'plm-field-tracker' | xargs -I {} kill {} 2>/dev/null
sleep 2
# Start fresh in the background
cd ~/Documents/plm-toolkit\ 2
nohup java -Xmx4g -jar plm-field-tracker-1.0.1.jar \
  --spring.config.additional-location=file:./config/application.properties \
  > toolkit.log 2>&1 &
echo "Started PID $!"
```

- [ ] **Step 4: Wait for boot + smoke-test login**

Poll `http://localhost:8090/login.html` until 200, then:

```bash
curl -sS -c /tmp/ss-cookies.txt \
     -H "Content-Type: application/json" \
     -d '{"username":"plmadmin","password":"newworld"}' \
     http://localhost:8090/api/auth/login
```
Expected: `{"ok":true, …}` or similar success JSON.

- [ ] **Step 5: Hit the new endpoints**

```bash
# Status (no runs yet)
curl -sS -b /tmp/ss-cookies.txt http://localhost:8090/api/single-sole-source/status

# Run the report (no upload / no email — local dev safe)
curl -sS -b /tmp/ss-cookies.txt \
     -H "Content-Type: application/json" \
     -X POST -d '{"uploadSharePoint":false,"sendEmail":false}' \
     http://localhost:8090/api/single-sole-source/run

# Download
curl -sS -b /tmp/ss-cookies.txt -o /tmp/ss-test.xlsx \
     http://localhost:8090/api/single-sole-source/download/latest && ls -la /tmp/ss-test.xlsx
```

- [ ] **Step 6: Inspect the produced xlsx**

```bash
/opt/anaconda3/bin/python3 -c "
import openpyxl
wb = openpyxl.load_workbook('/tmp/ss-test.xlsx', data_only=True)
for s in ['Designation Needed','Single Source','Sole Source']:
    sh = wb[s]; cnt = sum(1 for r in range(3, sh.max_row+1) if sh.cell(row=r,column=2).value)
    print(f'{s}: {cnt} rows')
"
```

Expected counts (against agprod, ballpark from design phase): 308 / 838 / 31 (or items×MPN-expansion: 634 / 2023 / 70 — depending on whether sub-rows for Single/Sole materialized).

- [ ] **Step 7: Open the page in a browser and click through manually**

URL: <http://localhost:8090/index.html> — log in as `plmadmin` / `newworld`, click the **Single/Sole Source** tab, click **Run Now**, then **Download Latest**, then **Send Test Email**.

Expected: status panel populates, alert pops with row counts, download triggers, email lands in `vikas.jindal@sandisk.com` (the logged-in admin user's session email).

---

## Task 13: Email Vikas + Krati that it's ready for testing

**Files:** none (one-off script via `mailrelay.sandisk.com:25` per CLAUDE.md).

- [ ] **Step 1: Send a leadership-grade HTML email** following CLAUDE.md guidelines (IBM Plex, sandisk pill, dark-mode meta, KPI tile per tab).

```python
/opt/anaconda3/bin/python3 << 'PY'
import smtplib
from email.mime.text import MIMEText
html = '''<!doctype html><html><head>
<meta name="color-scheme" content="light dark">
<style>
@media (prefers-color-scheme: dark) {
  .email-body{background:#1a1a1a !important; color:#eee !important;}
  .email-card{background:#252525 !important; border-color:#444 !important;}
  .kpi-tile{background:#2a2a2a !important;}
}
body{font-family:'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif;}
</style></head>
<body class="email-body" style="background:#FAFAF7; color:#0F1720; padding:20px;">
<div class="email-card" style="max-width:600px; margin:0 auto; background:#fff; border:1px solid #E8E6DF; border-radius:8px;">
  <div style="padding:14px 22px; color:#6B7280; font-size:13px; border-bottom:1px solid #E8E6DF;">
    Agile PLM / Single/Sole Source
  </div>
  <div style="padding:22px;">
    <div style="text-transform:uppercase; color:#6B7280; font-size:11px; letter-spacing:1px;">Ready for Testing</div>
    <h1 style="font-family:'IBM Plex Serif',Georgia,serif; font-size:22px; margin:6px 0 14px;">
      Single/Sole Source Report — local instance
    </h1>
    <p style="margin:0 0 14px;">The new <strong>Single/Sole Source</strong> tab is live on Vikas's
       local toolkit and ready for a first round of testing.</p>
    <p style="margin:0 0 14px;"><strong>URL:</strong>
       <a href="http://localhost:8090/index.html">http://localhost:8090/index.html</a><br/>
       <strong>Login:</strong> <code>plmadmin</code> / <code>newworld</code></p>
    <h2 style="font-family:'IBM Plex Serif',Georgia,serif; font-size:16px; margin:18px 0 8px;">What to test</h2>
    <ol style="margin:0 0 14px 18px; padding:0;">
      <li>Click the <strong>Single/Sole Source</strong> tab.</li>
      <li>Click <strong>Run Now</strong> (leave both checkboxes unchecked for the first run — this hits agprod
          and writes a local xlsx; nothing goes out by email or SharePoint).</li>
      <li>Verify row counts in the alert match expectations (~308 / 838 / 31 today).</li>
      <li>Click <strong>Download Latest .xlsx</strong> and open the file. Confirm:
        <ul style="margin:6px 0 0 18px;">
          <li>3 tabs: Designation Needed, Single Source, Sole Source</li>
          <li>15 columns A–O matching the existing template (with column H "(To Be)" left blank)</li>
          <li>Manufacturer rows are expanded on Single/Sole tabs (rows repeat for items with multiple MPNs)</li>
          <li>Date columns show date only, no time</li>
        </ul>
      </li>
      <li>Click <strong>Send Test Email (to me)</strong> — should land in your inbox with the latest xlsx attached.</li>
    </ol>
    <p style="margin:14px 0; color:#6B7280; font-size:13px;">
      <strong>Note:</strong> Local instance only — Krati, you may need to walk over to Vikas's machine
      or have him share his screen for the first pass. Once we sign off, this gets cut into prod.
    </p>
    <h2 style="font-family:'IBM Plex Serif',Georgia,serif; font-size:16px; margin:18px 0 8px;">Reference</h2>
    <ul style="margin:0 0 0 18px; padding:0;">
      <li>Spec: <code>docs/superpowers/specs/2026-05-08-single-sole-source-report-design.md</code></li>
      <li>Plan: <code>docs/superpowers/plans/2026-05-08-single-sole-source-report.md</code></li>
      <li>ECN: ECN-128313-PROJ (replaces F:\\Batch\\Shruthi\\SingleSourceReport.jar)</li>
    </ul>
  </div>
  <div style="padding:14px 22px; background:#FAFAF7; border-top:1px solid #E8E6DF; color:#6B7280; font-size:11px;">
    <span style="display:inline-block; padding:2px 10px; border:1px solid #ececec; border-radius:20px;">sandisk</span>
    &nbsp;&nbsp;PLM Toolkit · This is an automated notification.
  </div>
</div>
</body></html>'''
msg = MIMEText(html, 'html', 'utf-8')
msg['From'] = 'PLM-Toolkit@sandisk.com'
msg['To'] = 'vikas.jindal@sandisk.com,krati.jain@sandisk.com'
msg['Subject'] = 'PLM Toolkit: Single/Sole Source Report ready for testing'
with smtplib.SMTP('mailrelay.sandisk.com', 25) as s:
    s.send_message(msg)
print('Sent.')
PY
```

- [ ] **Step 2: That's it.**

The plan is complete. The user is notified by email; everything else is polish based on their feedback.

---

## Self-review

- **Spec coverage:** §1 ↔ Tasks 1; §2 ↔ Tasks 3+4; §3 ↔ Task 3; §4 ↔ Tasks 2–10 (all components); §5 ↔ Task 10; §6 ↔ Task 1; §7 ↔ Task 9; §8 ↔ Task 5; §9 ↔ Tasks 5/8; §10 ↔ Task 12; §11 ↔ docs only (no code); §12 ↔ resolved.
- **Placeholder scan:** Every code step contains the actual code. The only soft spot is Task 7 Step 2 which says "if EmailTemplateService signatures don't match, fix the call site" — this is intentional because the template service is shared and we shouldn't second-guess it without reading. Acceptable.
- **Type consistency:** `SingleSoleSourceRow` field names are stable across Tasks 2–4 and 8. `SingleSoleSourceRunResult` field names stable across Tasks 2/5/8/9.
- **Risk:** Task 6 ports SharePoint code verbatim — runtime issues only surface in prod (creds + drive ID need to be valid). For local dev, `sharepoint.enabled=false` short-circuits, so we're safe.
