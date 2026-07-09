package com.sandisk.plm.tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/**
 * Logs the effective values of the most important properties at startup,
 * so config drift between bundled and external files is obvious from the
 * log. Specifically prevents the failure mode we hit on 2026-04-16 where
 * an external {@code spring.servlet.multipart.max-file-size=20MB} silently
 * overrode the JAR's bundled 100MB and we spent an afternoon debugging it.
 */
@Component
public class ConfigBanner {

    private static final Logger LOG = Logger.getLogger(ConfigBanner.class.getName());

    @Value("${server.port:?}")
    private String port;

    @Value("${spring.servlet.multipart.max-file-size:?}")
    private String maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:?}")
    private String maxRequestSize;

    @Value("${spring.datasource.jdbc-url:?}")
    private String dbUrl;

    @Value("${agile.service.url:?}")
    private String agileServiceUrl;

    @Value("${ai.help.enabled:false}")
    private boolean aiEnabled;

    @Value("${portkey.enabled:false}")
    private boolean portkeyEnabled;

    @Value("${app.reports.python:?}")
    private String pythonExe;

    @Value("${app.cache.file:?}")
    private String cacheFile;

    @Value("${app.log-file:?}")
    private String logFile;

    @Value("${ldap.url:?}")
    private String ldapUrl;

    private String buildTimestamp = "dev";
    private String buildVersion = "dev";

    @javax.annotation.PostConstruct
    public void loadBuildInfo() {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("build-info.properties")) {
            if (is != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(is);
                buildTimestamp = p.getProperty("build.timestamp", "dev");
                buildVersion = p.getProperty("build.version", "dev");
            }
        } catch (Exception e) {
            LOG.warning("[CONFIG] Could not read build-info.properties: " + e.getMessage());
        }
    }

    public String getBuildTimestamp() { return buildTimestamp; }
    public String getBuildVersion() { return buildVersion; }
    public String getBuildLabel() {
        try {
            java.time.LocalDateTime utc = java.time.LocalDateTime.parse(buildTimestamp,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            java.time.ZonedDateTime pst = utc.atZone(java.time.ZoneId.of("UTC"))
                    .withZoneSameInstant(java.time.ZoneId.of("America/Los_Angeles"));
            String pstStr = pst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a z"));
            return "v" + buildVersion + " · " + pstStr;
        } catch (Exception e) {
            return "v" + buildVersion + " · " + buildTimestamp;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void log() {
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);

        LOG.info("[CONFIG] ===== Effective configuration =====");
        LOG.info("[CONFIG] BUILD                                    = " + getBuildLabel());
        LOG.info("[CONFIG] server.port                              = " + port);
        LOG.info("[CONFIG] -Xmx (max heap)                          = " + maxMb + " MB");
        LOG.info("[CONFIG] spring.servlet.multipart.max-file-size   = " + maxFileSize);
        LOG.info("[CONFIG] spring.servlet.multipart.max-request-size= " + maxRequestSize);
        LOG.info("[CONFIG] spring.datasource.jdbc-url               = " + dbUrl);
        LOG.info("[CONFIG] ldap.url                                 = " + ldapUrl);
        LOG.info("[CONFIG] agile.service.url                        = " + agileServiceUrl);
        LOG.info("[CONFIG] ai.help.enabled                          = " + aiEnabled);
        LOG.info("[CONFIG] portkey.enabled                            = " + portkeyEnabled);
        LOG.info("[CONFIG] app.reports.python                       = " + pythonExe);
        LOG.info("[CONFIG] app.cache.file                           = " + cacheFile);
        LOG.info("[CONFIG] app.log-file                             = " + logFile);
        LOG.info("[CONFIG] ====================================");
    }
}
