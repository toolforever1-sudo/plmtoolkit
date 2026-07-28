package com.sandisk.plm.tracker.service;

import com.sandisk.plm.tracker.Application;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Computes the SHA-256 of the running JAR once at startup and caches it.
 * Surfaced via {@code GET /api/admin/build-info} so the feedback-queue
 * auto-mark-done logic (and external pollers) can match a ticket's
 * {@code agentBuildJarSha} against the currently-deployed binary.
 *
 * <p>Falls back gracefully when the app is run unpacked (IDE / test harness)
 * where there is no JAR file to hash — the SHA becomes {@code "unknown"} and
 * the auto-done logic simply never matches.
 */
@Service
public class BuildInfoService {

    private static final Logger logger = Logger.getLogger(BuildInfoService.class.getName());

    @Value("${app.version:1.0.1}")
    private String version;

    private volatile String jarSha = "unknown";
    private volatile String builtAt = null;
    private volatile long jarSizeBytes = 0;

    @PostConstruct
    public void init() {
        try {
            Path jarPath = locateRunningJar();
            if (jarPath == null) {
                logger.info("[BUILD-INFO] couldn't locate a JAR file (probably running unpacked / IDE). Auto-done disabled.");
                return;
            }
            BasicFileAttributes attrs = Files.readAttributes(jarPath, BasicFileAttributes.class);
            jarSizeBytes = attrs.size();
            builtAt = attrs.lastModifiedTime().toInstant().toString();
            jarSha = computeSha256(jarPath);
            logger.info("[BUILD-INFO] running JAR: " + jarPath.getFileName()
                + " (" + jarSizeBytes + " bytes, built " + builtAt + ", sha=" + jarSha.substring(0, 12) + "…)");
        } catch (Exception e) {
            logger.warning("[BUILD-INFO] failed to compute JAR SHA: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Spring Boot's fat-JAR launcher returns a {@code jar:file:/path/to/foo.jar!/BOOT-INF/classes/}
     * URL from {@code codeSource}. We strip the {@code jar:} prefix and the
     * {@code !/...} suffix to get the actual JAR file path. Falls back to
     * scanning {@code java.class.path} system property — works for IDE runs
     * where codeSource may be a directory.
     */
    private static Path locateRunningJar() throws Exception {
        URL codeLoc = Application.class.getProtectionDomain().getCodeSource().getLocation();
        if (codeLoc != null) {
            String s = codeLoc.toString();
            // jar:file:/path/to/x.jar!/BOOT-INF/classes/  →  file:/path/to/x.jar
            if (s.startsWith("jar:")) s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang > 0) s = s.substring(0, bang);
            if (s.startsWith("file:")) {
                Path p = Paths.get(new java.net.URI(s));
                if (Files.isRegularFile(p) && s.toLowerCase().endsWith(".jar")) return p;
            }
        }
        // Fallback: scan java.class.path for the toolkit JAR.
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.toLowerCase().contains("plm-field-tracker") && entry.toLowerCase().endsWith(".jar")) {
                Path p = Paths.get(entry);
                if (Files.isRegularFile(p)) return p;
            }
        }
        return null;
    }

    public String getJarSha256() { return jarSha; }
    public String getBuiltAt()   { return builtAt == null ? Instant.now().toString() : builtAt; }
    public String getVersion()   { return version; }
    public long   getJarSizeBytes() { return jarSizeBytes; }

    private static String computeSha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
