package com.sandisk.plm.tracker.model;

import java.util.Map;
import java.util.UUID;

public class ScheduledReport {

    public String id;
    public String username;
    public String displayName;
    public String email;
    public Map<String, String> searchParams;
    public String frequency;   // "daily" or "weekly"
    public String dayOfWeek;   // "MONDAY".."SUNDAY", null for daily
    public String timeOfDay;   // "HH:mm" 24-hour format
    public boolean active;
    public Long lastRun;       // epoch ms, null if never run
    public long createdAt;

    public ScheduledReport() {}

    public ScheduledReport(String username, String email, Map<String, String> searchParams,
                           String frequency, String dayOfWeek, String timeOfDay) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.username = username;
        this.email = email;
        this.searchParams = searchParams;
        this.frequency = frequency;
        this.dayOfWeek = dayOfWeek;
        this.timeOfDay = timeOfDay;
        this.active = true;
        this.lastRun = null;
        this.createdAt = System.currentTimeMillis();
    }
}
