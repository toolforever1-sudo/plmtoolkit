package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which users are currently active in the app, keyed by username (one entry per user).
 * Updated on every authenticated request via {@code AuthFilter}; cleared on explicit logout.
 *
 * <p>Used by {@link MaintenanceService} to figure out who needs to be notified when an
 * admin schedules a maintenance shutdown.
 *
 * <p>"Active" = lastSeen within the configurable cutoff (default 30 min, matching session timeout).
 */
@Service
public class SessionRegistry {

    public static class ActiveUser {
        public final String username;
        public final String displayName;
        public final String email;
        public final boolean isPlmAdmin;
        public final long loginAt;
        public volatile long lastSeen;

        public ActiveUser(String username, String displayName, String email,
                          boolean isPlmAdmin, long loginAt) {
            this.username = username;
            this.displayName = displayName;
            this.email = email;
            this.isPlmAdmin = isPlmAdmin;
            this.loginAt = loginAt;
            this.lastSeen = loginAt;
        }
    }

    private final ConcurrentHashMap<String, ActiveUser> users = new ConcurrentHashMap<>();

    /**
     * Insert (or update lastSeen for) the given user. Called from AuthFilter on every
     * authenticated request so lastSeen reflects real activity, not just login time.
     */
    public void touch(String username, String displayName, String email, boolean isPlmAdmin) {
        if (username == null || username.isEmpty()) return;
        long now = System.currentTimeMillis();
        users.compute(username, (k, existing) -> {
            if (existing == null) {
                return new ActiveUser(username, displayName, email, isPlmAdmin, now);
            }
            existing.lastSeen = now;
            return existing;
        });
    }

    /** Remove on explicit logout. */
    public void remove(String username) {
        if (username != null) users.remove(username);
    }

    /**
     * Snapshot of users active within the last {@code staleMs} milliseconds.
     * Pruning happens implicitly here — we don't mutate the map, but excluded users
     * eventually get overwritten on their next login.
     */
    public List<ActiveUser> listActive(long staleMs) {
        long cutoff = System.currentTimeMillis() - staleMs;
        List<ActiveUser> out = new ArrayList<>();
        for (ActiveUser u : users.values()) {
            if (u.lastSeen >= cutoff) out.add(u);
        }
        return out;
    }

    /** Total tracked users (including stale entries that haven't been overwritten yet). */
    public int size() { return users.size(); }

    /** All currently-tracked users without freshness filtering. Mostly for diagnostics. */
    public Collection<ActiveUser> all() { return users.values(); }
}
