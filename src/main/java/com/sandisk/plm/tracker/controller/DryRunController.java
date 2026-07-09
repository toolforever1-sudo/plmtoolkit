package com.sandisk.plm.tracker.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.Context;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only "dry run" health check — verifies the deployment's external dependencies
 * (DBs, LDAP, Agile proxy, Python, filesystem paths) so a freshly-deployed environment
 * can be validated before real traffic is sent to it.
 *
 * Returns a structured JSON report with per-check status. Each check is wrapped in its
 * own try/catch so a single failing dependency doesn't mask the others.
 */
@RestController
@RequestMapping("/api/admin")
public class DryRunController {

    private final DataSource primaryDataSource;
    private final DataSource customDataSource;

    @Value("${spring.datasource.jdbc-url:}")
    private String primaryJdbcUrl;
    @Value("${spring.datasource.username:}")
    private String primaryUsername;

    @Value("${custom.datasource.jdbc-url:}")
    private String customJdbcUrl;
    @Value("${custom.datasource.username:}")
    private String customUsername;

    @Value("${ldap.url:}")
    private String ldapUrl;
    @Value("${ldap.service.username:}")
    private String ldapServiceUsername;
    @Value("${ldap.service.password:}")
    private String ldapServicePassword;
    @Value("${ldap.domain:}")
    private String ldapDomain;

    @Value("${agile.service.url:}")
    private String agileServiceUrl;

    @Value("${app.reports.python:python}")
    private String pythonBinary;

    @Value("${app.cache.file:./cache/field-changes-cache.ser}")
    private String cacheFilePath;

    @Value("${app.log-file:./logs/plm-toolkit.log}")
    private String logFilePath;

    @Value("${app.activity-log.file:./data/activity-log.jsonl}")
    private String activityLogFile;

    public DryRunController(@Qualifier("dataSource") DataSource primary,
                            @Qualifier("customDataSource") DataSource custom) {
        this.primaryDataSource = primary;
        this.customDataSource = custom;
    }

    @GetMapping("/dry-run")
    public Map<String, Object> dryRun(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(session.getAttribute("isPlmAdmin"))) {
            response.put("overall", "fail");
            response.put("error", "Admin access required.");
            return response;
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkPrimaryDb());
        checks.add(checkCustomDb());
        checks.add(checkLdap());
        checks.add(checkAgileProxy());
        checks.add(checkPython());
        checks.add(checkPath("data dir", "./data", true));
        checks.add(checkPath("extracts dir", "./data/extracts", true));
        checks.add(checkPath("logs dir", "./logs", true));
        checks.add(checkPath("cache dir", new File(cacheFilePath).getParent(), true));
        checks.add(checkPath("activity log", activityLogFile, false));

        // Aggregate
        String overall = "ok";
        for (Map<String, Object> c : checks) {
            String s = String.valueOf(c.get("status"));
            if ("fail".equals(s)) { overall = "fail"; break; }
            if ("warn".equals(s)) overall = "warn";
        }

        response.put("overall", overall);
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        response.put("host", hostInfo());
        response.put("runtime", runtimeInfo());
        response.put("config", configSummary());
        response.put("checks", checks);
        return response;
    }

    // ------------------------------------------------------------------
    // Individual checks
    // ------------------------------------------------------------------

    private Map<String, Object> checkPrimaryDb() {
        return dbCheck("Primary DB (agile)", primaryDataSource, primaryJdbcUrl, primaryUsername,
                "SELECT 1 FROM DUAL");
    }

    private Map<String, Object> checkCustomDb() {
        return dbCheck("Custom DB (custom_user)", customDataSource, customJdbcUrl, customUsername,
                "SELECT COUNT(*) FROM (SELECT 1 FROM BOM_EXTRACT WHERE ROWNUM <= 1)");
    }

    private Map<String, Object> dbCheck(String name, DataSource ds, String url, String user, String sql) {
        Map<String, Object> r = base(name);
        r.put("target", redactJdbc(url) + " (user=" + user + ")");
        long t0 = System.currentTimeMillis();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement()) {
            s.setQueryTimeout(10);
            try (ResultSet rs = s.executeQuery(sql)) {
                String first = rs.next() ? rs.getString(1) : "";
                long ms = System.currentTimeMillis() - t0;
                r.put("status", "ok");
                r.put("detail", "Connected and queried in " + ms + " ms (result=" + first + ")");
            }
        } catch (Exception e) {
            r.put("status", "fail");
            r.put("detail", e.getClass().getSimpleName() + ": " + safeMsg(e));
        }
        return r;
    }

    private Map<String, Object> checkLdap() {
        Map<String, Object> r = base("LDAP service bind");
        r.put("target", ldapUrl + " (user=" + ldapServiceUsername + ")");
        if (ldapUrl == null || ldapUrl.isEmpty()) {
            r.put("status", "warn");
            r.put("detail", "ldap.url not configured");
            return r;
        }
        long t0 = System.currentTimeMillis();
        DirContext ctx = null;
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, ldapUrl);
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, ldapServiceUsername + "@" + ldapDomain);
            env.put(Context.SECURITY_CREDENTIALS, ldapServicePassword);
            env.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");
            env.put("com.sun.jndi.ldap.connect.timeout", "5000");
            env.put("com.sun.jndi.ldap.read.timeout", "5000");
            ctx = new InitialDirContext(env);
            long ms = System.currentTimeMillis() - t0;
            r.put("status", "ok");
            r.put("detail", "Service account bound in " + ms + " ms");
        } catch (Exception e) {
            r.put("status", "fail");
            r.put("detail", e.getClass().getSimpleName() + ": " + safeMsg(e));
        } finally {
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) {}
        }
        return r;
    }

    private Map<String, Object> checkAgileProxy() {
        Map<String, Object> r = base("Agile lookup proxy");
        String url = agileServiceUrl + "/api/lookup/health";
        r.put("target", url);
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            if (code == 200) {
                r.put("status", "ok");
                r.put("detail", "HTTP 200 in " + ms + " ms");
            } else {
                r.put("status", "warn");
                r.put("detail", "HTTP " + code + " in " + ms + " ms");
            }
        } catch (Exception e) {
            r.put("status", "warn"); // app runs fine without the proxy — just the Agile Lookup tab won't work
            r.put("detail", e.getClass().getSimpleName() + ": " + safeMsg(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private Map<String, Object> checkPython() {
        Map<String, Object> r = base("Python (Reports tab)");
        r.put("target", pythonBinary);
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonBinary, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line);
            }
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                r.put("status", "warn");
                r.put("detail", "python --version did not return within 5 s");
                return r;
            }
            if (p.exitValue() == 0) {
                r.put("status", "ok");
                r.put("detail", out.toString().trim());
            } else {
                r.put("status", "warn");
                r.put("detail", "exit=" + p.exitValue() + " out=" + out);
            }
        } catch (IOException e) {
            r.put("status", "warn");
            r.put("detail", "Not found / not executable: " + safeMsg(e));
        } catch (Exception e) {
            r.put("status", "warn");
            r.put("detail", e.getClass().getSimpleName() + ": " + safeMsg(e));
        }
        return r;
    }

    private Map<String, Object> checkPath(String name, String pathStr, boolean mustBeWritable) {
        Map<String, Object> r = base("Path: " + name);
        if (pathStr == null || pathStr.isEmpty()) {
            r.put("status", "warn");
            r.put("detail", "Not configured");
            return r;
        }
        Path p;
        try {
            p = Paths.get(pathStr).toAbsolutePath();
        } catch (Exception e) {
            r.put("status", "fail");
            r.put("detail", "Invalid path: " + pathStr);
            return r;
        }
        r.put("target", p.toString());
        if (!Files.exists(p)) {
            r.put("status", mustBeWritable ? "warn" : "warn");
            r.put("detail", "Does not exist (will be auto-created on first write)");
            return r;
        }
        if (mustBeWritable) {
            // Try creating a temp file to confirm write access
            Path probe = p.resolve(".dry-run-probe-" + System.currentTimeMillis());
            try {
                Files.createFile(probe);
                Files.delete(probe);
                r.put("status", "ok");
                r.put("detail", "Exists and writable");
            } catch (Exception e) {
                r.put("status", "fail");
                r.put("detail", "Exists but NOT writable: " + safeMsg(e));
            }
        } else {
            r.put("status", "ok");
            long size = 0;
            try { size = Files.size(p); } catch (IOException ignored) {}
            r.put("detail", "Exists (" + size + " bytes)");
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Metadata blocks
    // ------------------------------------------------------------------

    private Map<String, Object> hostInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            InetAddress addr = InetAddress.getLocalHost();
            m.put("hostname", addr.getHostName());
            m.put("address", addr.getHostAddress());
        } catch (Exception e) {
            m.put("hostname", "unknown");
        }
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        m.put("workingDir", new File(".").getAbsolutePath());
        m.put("user", System.getProperty("user.name"));
        return m;
    }

    private Map<String, Object> runtimeInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024;
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaVendor", System.getProperty("java.vendor"));
        m.put("heapMaxMB", rt.maxMemory() / mb);
        m.put("heapTotalMB", rt.totalMemory() / mb);
        m.put("heapFreeMB", rt.freeMemory() / mb);
        m.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / mb);
        m.put("availableProcessors", rt.availableProcessors());
        return m;
    }

    private Map<String, Object> configSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primaryDbUrl", redactJdbc(primaryJdbcUrl));
        m.put("customDbUrl", redactJdbc(customJdbcUrl));
        m.put("ldapUrl", ldapUrl);
        m.put("agileServiceUrl", agileServiceUrl);
        m.put("pythonBinary", pythonBinary);
        m.put("cacheFile", cacheFilePath);
        m.put("logFile", logFilePath);
        m.put("activityLogFile", activityLogFile);
        return m;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> base(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", "unknown");
        m.put("target", "");
        m.put("detail", "");
        return m;
    }

    /** Strip out password-like tokens from JDBC URLs before echoing them back to the UI. */
    private String redactJdbc(String url) {
        if (url == null) return "";
        return url.replaceAll("(?i)password=[^;&]+", "password=***");
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
