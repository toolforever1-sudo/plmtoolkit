package com.sandisk.plm.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prefixes outbound email subjects with the instance label (e.g. "[TEST] ")
 * on non-prod boxes, so QA/test mail is unmistakable in any inbox. No-op on
 * prod (empty {@code app.instance.label} → subject returned unchanged).
 *
 * <p>Exposed as a static helper so the ~20 independent email senders can wrap
 * their {@code setSubject(...)} calls without each needing the bean injected.
 * The Spring-managed setter populates the static field at startup.
 */
@Component
public class EmailEnvTag {

    private static volatile String label = "";

    @Value("${app.instance.label:}")
    public void setLabel(String l) {
        label = (l == null) ? "" : l.trim();
    }

    /** Returns {@code subject} prefixed with "[LABEL] " on non-prod; unchanged on prod. */
    public static String tag(String subject) {
        String l = label;
        if (l == null || l.isEmpty()) return subject;
        String prefix = "[" + l + "] ";
        if (subject == null) return prefix.trim();
        return subject.startsWith(prefix) ? subject : prefix + subject;
    }
}
