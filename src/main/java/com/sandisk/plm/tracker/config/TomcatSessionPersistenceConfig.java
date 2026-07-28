package com.sandisk.plm.tracker.config;

import org.apache.catalina.session.FileStore;
import org.apache.catalina.session.PersistentManager;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.logging.Logger;

/**
 * Persist HTTP sessions to disk so JSESSIONIDs survive JVM restarts.
 *
 * <p>Why: Spring Boot's default {@code StandardManager} keeps sessions purely
 * in-memory. Every {@code deploy.bat} run (JAR drop in staging → killing the
 * live JVM → watchdog respawn) silently invalidates every active session, and
 * the user's next API call returns 401 → the "Session Expired" modal fires.
 * On a heavy-deploy day (2026-06-15: 5 deploys in one hour) every signed-in
 * user gets booted five times — confusing and disruptive.
 *
 * <p>How: replace the default manager with Tomcat's {@link PersistentManager}
 * + {@link FileStore} pointed at {@code ./data/sessions/}. Tomcat ships these
 * classes with the embedded build (no extra dependencies). With
 * {@code maxIdleBackup=0}, every session is continuously mirrored to disk —
 * a forced kill loses at most the most-recent in-flight write. On restart,
 * the new JVM rehydrates the directory and the same JSESSIONID cookie keeps
 * working.
 *
 * <p>Session attribute Serializable check (2026-06-15 audit of AuthController
 * + the per-tab controllers): every attribute we put on session is a String,
 * Boolean, primitive wrapper, or Set/List of those — all Serializable. The
 * {@code agilePassword} attribute set by IT-Enhancements is also a String.
 * Nothing on session references a Spring bean.
 */
@Component
public class TomcatSessionPersistenceConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final Logger LOG = Logger.getLogger(TomcatSessionPersistenceConfig.class.getName());
    private static final String SESSION_DIR = "./data/sessions";

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        File dir = new File(SESSION_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LOG.warning("[SESSION] Could not create session-persistence directory "
                    + dir.getAbsolutePath() + " — falling back to in-memory sessions.");
            return;
        }

        factory.addContextCustomizers(context -> {
            PersistentManager manager = new PersistentManager();
            FileStore store = new FileStore();
            store.setDirectory(dir.getAbsolutePath());
            manager.setStore(store);
            // Continuously back up sessions to disk: maxIdleBackup=0 means
            // "back up any session that's been idle for 0 seconds since its
            // last access" — i.e. write-through to disk on every change.
            // taskkill /F in deploy.bat means we can't rely on a clean shutdown
            // flush, so we want the disk copy to always be current.
            manager.setMaxIdleBackup(0);
            // Don't expire idle sessions just because they're paged to disk —
            // they stick around for the normal server.servlet.session.timeout
            // (2h) so reads after a deploy still find them.
            manager.setMaxIdleSwap(-1);
            manager.setMinIdleSwap(-1);
            // Unbounded — the deploy use case has at most ~50 concurrent users.
            manager.setMaxActiveSessions(-1);
            context.setManager(manager);
            LOG.info("[SESSION] Tomcat PersistentManager + FileStore wired (dir="
                    + dir.getAbsolutePath() + ") — sessions survive JVM restarts.");
        });
    }
}
