package com.rahbar.db;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Frontend facade delegating all calls to db.DatabaseManager; provides com.rahbar.db package path and inner model types for UI classes. */
public class DatabaseManager {

    private static DatabaseManager instance;

    public static DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    private final db.DatabaseManager backend = db.DatabaseManager.getInstance();

    private DatabaseManager() {}

    // ── Settings ──────────────────────────────────────────────────────────────

    public String getSetting(String key)                    { return backend.getSetting(key); }
    public void   saveSetting(String key, String value)     { backend.saveSetting(key, value); }

    // ── Command log ───────────────────────────────────────────────────────────

    public void logCommand(String text, String type, String status) {
        backend.logCommand(text, type, status);
    }

    public List<CommandLog> getRecentCommands(int limit) {
        List<db.DatabaseManager.CommandLog> raw = backend.getRecentCommands(limit);
        List<CommandLog> result = new ArrayList<>(raw.size());
        for (db.DatabaseManager.CommandLog cl : raw) {
            result.add(new CommandLog(cl.getText(), cl.getType(), cl.getStatus(), cl.getTimestamp()));
        }
        return result;
    }

    public int    getCommandCountToday()              { return backend.getCommandCountToday(); }
    public int    getSuccessRatePercent(int lastN)    { return backend.getSuccessRatePercent(lastN); }
    public void   clearCommandLog()                   { backend.clearCommandLog(); }
    public int[]  getCommandCountByHour()             { return backend.getCommandCountByHour(); }

    // ── Alarms ────────────────────────────────────────────────────────────────

    public void saveAlarm(String label, LocalDateTime time) { backend.saveAlarm(label, time); }

    public List<Alarm> getAllAlarms() {
        List<db.DatabaseManager.Alarm> raw = backend.getAllAlarms();
        List<Alarm> result = new ArrayList<>(raw.size());
        for (db.DatabaseManager.Alarm a : raw) {
            result.add(new Alarm(a.label, a.triggerTime.toString()));
        }
        return result;
    }

    public void   markAlarmTriggered(String label)    { backend.markAlarmTriggered(label); }
    public String getNextAlarmLabel()                 { return backend.getNextAlarmLabel(); }

    // ── App registry ──────────────────────────────────────────────────────────

    public String         getAppPath(String alias)              { return backend.getAppPath(alias); }
    public int            getAppRegistryCount()                 { return backend.getAppRegistryCount(); }
    public List<String[]> getAllAppAliases()                    { return backend.getAllAppAliases(); }
    public void           removeAppAlias(String alias)          { backend.removeAppAlias(alias); }

    public void addAppAlias(String alias, String path) throws SQLException {
        backend.addAppAlias(alias, path);
    }

    // ── Website registry ──────────────────────────────────────────────────────

    public String         getWebsiteUrl(String alias)           { return backend.getWebsiteUrl(alias); }
    public List<String[]> getAllWebsiteAliases()                { return backend.getAllWebsiteAliases(); }
    public void           removeWebsiteAlias(String alias)      { backend.removeWebsiteAlias(alias); }

    public void addWebsiteAlias(String alias, String url) throws SQLException {
        backend.addWebsiteAlias(alias, url);
    }

    // ── Inner model classes (match what frontend UI expects) ──────────────────

    public static class CommandLog {
        private final String text;
        private final String type;
        private final String status;
        private final String timestamp;

        public CommandLog(String text, String type, String status, String timestamp) {
            this.text      = text      != null ? text      : "";
            this.type      = type      != null ? type      : "";
            this.status    = status    != null ? status    : "";
            this.timestamp = timestamp != null ? timestamp : "";
        }

        public String getText()      { return text; }
        public String getType()      { return type; }
        public String getStatus()    { return status; }
        public String getTimestamp() { return timestamp; }
    }

    public static class Alarm {
        private final String label;
        private final String triggerTime;

        public Alarm(String label, String triggerTime) {
            this.label       = label       != null ? label       : "";
            this.triggerTime = triggerTime != null ? triggerTime : "";
        }

        public String getLabel()       { return label; }
        public String getTriggerTime() { return triggerTime; }
    }
}
