package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.File;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class SavedChatQueryService {

    private static final Logger logger = Logger.getLogger(SavedChatQueryService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.saved-chat-queries.file:./data/saved-chat-queries.json}")
    private String filePath;

    @Autowired
    @Qualifier("customDataSource")
    private DataSource customDataSource;

    @Autowired
    private EmailService emailService;

    // username -> list of saved queries
    private final ConcurrentHashMap<String, List<ChatQuery>> userQueries = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    public List<ChatQuery> getQueries(String username) {
        return userQueries.getOrDefault(username, new ArrayList<>());
    }

    public List<ChatQuery> getSharedQueries(String excludeUsername) {
        List<ChatQuery> result = new ArrayList<>();
        for (Map.Entry<String, List<ChatQuery>> entry : userQueries.entrySet()) {
            if (entry.getKey().equals(excludeUsername)) continue;
            for (ChatQuery q : entry.getValue()) {
                if (q.shared) result.add(q);
            }
        }
        return result;
    }

    public ChatQuery saveQuery(String username, String displayName, String name, String sql) {
        ChatQuery q = new ChatQuery();
        q.id = UUID.randomUUID().toString().substring(0, 8);
        q.name = name;
        q.sql = sql;
        q.createdAt = java.time.Instant.now().toString();
        q.owner = username;
        q.ownerDisplay = displayName;
        userQueries.computeIfAbsent(username, k -> new ArrayList<>()).add(0, q);
        saveToDisk();
        return q;
    }

    public void deleteQuery(String username, String queryId) {
        List<ChatQuery> list = userQueries.get(username);
        if (list != null) {
            list.removeIf(q -> q.id.equals(queryId));
            saveToDisk();
        }
    }

    public ChatQuery toggleShare(String username, String queryId) {
        List<ChatQuery> list = userQueries.get(username);
        if (list == null) return null;
        for (ChatQuery q : list) {
            if (q.id.equals(queryId)) {
                q.shared = !q.shared;
                saveToDisk();
                return q;
            }
        }
        return null;
    }

    public ChatQuery setSchedule(String username, String queryId, String freq, String day, String time, String email) {
        List<ChatQuery> list = userQueries.get(username);
        if (list == null) return null;
        for (ChatQuery q : list) {
            if (q.id.equals(queryId)) {
                q.scheduleFreq = freq;   // "daily", "weekly", or null/empty to remove
                q.scheduleDay = day;     // "MON","TUE",etc for weekly
                q.scheduleTime = time;   // "HH:mm"
                q.scheduleEmail = email;
                saveToDisk();
                return q;
            }
        }
        return null;
    }

    public ChatQuery findById(String queryId) {
        for (List<ChatQuery> list : userQueries.values()) {
            for (ChatQuery q : list) {
                if (q.id.equals(queryId)) return q;
            }
        }
        return null;
    }

    // ── Scheduler — check every minute ──
    @Scheduled(fixedRate = 60000)
    public void checkSchedules() {
        LocalTime now = LocalTime.now();
        String nowHHmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        String todayDay = java.time.LocalDate.now().getDayOfWeek().name().substring(0, 3);

        for (Map.Entry<String, List<ChatQuery>> entry : userQueries.entrySet()) {
            for (ChatQuery q : entry.getValue()) {
                if (q.scheduleFreq == null || q.scheduleFreq.isEmpty()) continue;
                if (q.scheduleTime == null || !q.scheduleTime.equals(nowHHmm)) continue;
                if ("weekly".equals(q.scheduleFreq) && (q.scheduleDay == null || !q.scheduleDay.equalsIgnoreCase(todayDay))) continue;
                if (q.scheduleEmail == null || q.scheduleEmail.isEmpty()) continue;

                // Prevent re-run within the same minute
                String runKey = q.id + "-" + nowHHmm;
                if (runKey.equals(q.lastRunKey)) continue;
                q.lastRunKey = runKey;

                logger.info("[CHAT-QUERY] Running scheduled query '" + q.name + "' for " + q.scheduleEmail);
                try {
                    executeAndEmail(q);
                } catch (Exception e) {
                    logger.warning("[CHAT-QUERY] Scheduled run failed for " + q.id + ": " + e.getMessage());
                }
            }
        }
    }

    private void executeAndEmail(ChatQuery q) throws Exception {
        // Run the SQL
        String sql = q.sql.replaceAll("(?i)FETCH\\s+FIRST\\s+\\d+\\s+ROWS\\s+ONLY", "FETCH FIRST 10000 ROWS ONLY");
        List<String> columns = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        try (Connection conn = customDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));
                while (rs.next() && rows.size() < 10000) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        row.put(columns.get(i - 1), val != null ? val : "");
                    }
                    rows.add(row);
                }
            }
        }

        if (rows.isEmpty()) {
            logger.info("[CHAT-QUERY] Scheduled query '" + q.name + "' returned 0 rows — skipping email");
            return;
        }

        // Build Excel
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.createSheet("Query Results");
        org.apache.poi.ss.usermodel.CellStyle hdrStyle = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font hdrFont = wb.createFont();
        hdrFont.setBold(true); hdrStyle.setFont(hdrFont);

        org.apache.poi.ss.usermodel.Row hdrRow = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            org.apache.poi.ss.usermodel.Cell cell = hdrRow.createCell(i);
            cell.setCellValue(columns.get(i)); cell.setCellStyle(hdrStyle);
        }
        for (int r = 0; r < rows.size(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
            Map<String, String> data = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                row.createCell(c).setCellValue(data.getOrDefault(columns.get(c), ""));
            }
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        wb.write(baos);
        wb.close();

        // Send email
        String date = java.time.LocalDate.now().toString();
        emailService.sendDebugReport(q.scheduleEmail,
            "Saved Query \u00b7 " + q.name + " \u00b7 " + rows.size() + " rows",
            q.name, rows.size(), 0, 0, 0,
            "Scheduled query: " + q.name + "\n\n" + rows.size() + " rows returned.\n\nSQL: " + q.sql);

        logger.info("[CHAT-QUERY] Emailed " + rows.size() + " rows to " + q.scheduleEmail + " for '" + q.name + "'");
    }

    // ── Persistence ──
    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, List<ChatQuery>> data = mapper.readValue(f, new TypeReference<Map<String, List<ChatQuery>>>() {});
            userQueries.putAll(data);
            int total = data.values().stream().mapToInt(List::size).sum();
            logger.info("[CHAT-QUERY] Loaded " + total + " saved queries for " + data.size() + " users");
        } catch (Exception e) {
            logger.warning("[CHAT-QUERY] Failed to load: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, userQueries);
        } catch (Exception e) {
            logger.warning("[CHAT-QUERY] Failed to save: " + e.getMessage());
        }
    }

    public static class ChatQuery {
        public String id;
        public String name;
        public String sql;
        public String createdAt;
        public String owner;
        public String ownerDisplay;
        public boolean shared;
        public String scheduleFreq;    // "daily", "weekly", or null
        public String scheduleDay;     // "MON","TUE"..., only for weekly
        public String scheduleTime;    // "HH:mm"
        public String scheduleEmail;
        public String lastRunKey;      // prevents re-run within same minute
    }
}
