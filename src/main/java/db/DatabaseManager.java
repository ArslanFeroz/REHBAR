package db;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager -- singleton SQLite data layer.
 *
 * Integrated improvements:
 *   - richer seed data for training_data
 *   - voice / ASR-style variants added directly in Java seeding
 *   - duplicate-safe inserts using INSERT OR IGNORE
 *   - training phrases remain part of the core system, not a separate script
 */
public class DatabaseManager {

    private static DatabaseManager instance;
    private Connection connection;

    private static final String DB_PATH =
            System.getenv("APPDATA") + "/RAHBAR/rahbar.db";
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatabaseManager() {
        try {
            new File(System.getenv("APPDATA") + "/RAHBAR").mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            createTables();
        } catch (SQLException e) {
            System.err.println("[DB] Connection failed: " + e.getMessage());
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    // ── DTO ────────────────────────────────────────────────────────────────────

    public static class Alarm {
        public final int id;
        public final String label;
        public final LocalDateTime triggerTime;

        public Alarm(int id, String label, LocalDateTime triggerTime) {
            this.id = id;
            this.label = label;
            this.triggerTime = triggerTime;
        }
    }

    // ── Alarm methods ──────────────────────────────────────────────────────────

    public void saveAlarm(String label, LocalDateTime triggerTime) {
        String sql = "INSERT INTO alarms (label, trigger_time, is_triggered) VALUES (?,?,0)";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, label);
            p.setString(2, triggerTime.format(DT_FMT));
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] saveAlarm: " + e.getMessage());
        }
    }

    public void markAlarmTriggered(String label) {
        String sql = "UPDATE alarms SET is_triggered=1 WHERE label=? AND is_triggered=0";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, label);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] markAlarmTriggered: " + e.getMessage());
        }
    }

    public List<Alarm> getAllAlarms() {
        List<Alarm> list = new ArrayList<>();
        String sql = "SELECT id,label,trigger_time FROM alarms WHERE is_triggered=0";
        try (PreparedStatement p = connection.prepareStatement(sql);
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                list.add(new Alarm(
                        rs.getInt("id"),
                        rs.getString("label"),
                        LocalDateTime.parse(rs.getString("trigger_time"), DT_FMT)));
            }
        } catch (SQLException e) {
            System.err.println("[DB] getAllAlarms: " + e.getMessage());
        }
        return list;
    }

    // ── Existing query helpers ─────────────────────────────────────────────────

    public String getAppPath(String appName) {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT path FROM app_registry WHERE alias=?")) {
            p.setString(1, appName.toLowerCase());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString("path");
            }
        } catch (SQLException e) {
            System.err.println("[DB] getAppPath: " + e.getMessage());
        }
        return null;
    }

    public String getWebsiteUrl(String siteName) {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT url FROM website_registry WHERE alias=?")) {
            p.setString(1, siteName.toLowerCase());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString("url");
            }
        } catch (SQLException e) {
            System.err.println("[DB] getWebsiteUrl: " + e.getMessage());
        }
        return null;
    }

    public void logCommand(String command, String intent, String status) {
        try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO command_log (text,type,status) VALUES (?,?,?)")) {
            p.setString(1, command);
            p.setString(2, intent);
            p.setString(3, status);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] logCommand: " + e.getMessage());
        }
    }

    // ── Schema ─────────────────────────────────────────────────────────────────

    private void createTables() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS command_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                        "type TEXT, status TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);",
                "CREATE TABLE IF NOT EXISTS alarms (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT, " +
                        "trigger_time DATETIME NOT NULL, is_triggered INTEGER DEFAULT 0, " +
                        "repeat_type TEXT DEFAULT 'NONE');",
                "CREATE TABLE IF NOT EXISTS app_registry (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "alias TEXT UNIQUE NOT NULL, path TEXT NOT NULL);",
                "CREATE TABLE IF NOT EXISTS website_registry (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "alias TEXT UNIQUE NOT NULL, url TEXT NOT NULL);",
                "CREATE TABLE IF NOT EXISTS training_data (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "phrase TEXT NOT NULL UNIQUE, intent TEXT NOT NULL);",
                "CREATE TABLE IF NOT EXISTS settings (" +
                        "key TEXT PRIMARY KEY, value TEXT);"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : ddl) stmt.execute(sql);
            System.out.println("[DB] All tables initialised.");
            populateTables();
        } catch (SQLException e) {
            System.err.println("[DB] createTables: " + e.getMessage());
        }
    }

    private void populateTables() {
        String username = System.getProperty("user.name");

        try {
            connection.setAutoCommit(false);

            seedStaticData(username);
            seedTrainingData();

            connection.commit();
            System.out.println("[DB] Default data populated.");
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    private void seedStaticData(String username) throws SQLException {
        String[] staticData = {
                "INSERT OR IGNORE INTO app_registry (alias,path) VALUES " +
                        "('chrome','C:/Program Files/Google/Chrome/Application/chrome.exe')," +
                        "('notepad','C:/Windows/System32/notepad.exe')," +
                        "('calculator','C:/Windows/System32/calc.exe')," +
                        "('spotify','C:/Users/" + username + "/AppData/Roaming/Spotify/Spotify.exe')," +
                        "('vlc','C:/Program Files/VideoLAN/VLC/vlc.exe')," +
                        "('discord','C:/Users/" + username + "/AppData/Local/Discord/Update.exe --processStart Discord.exe')," +
                        "('vscode','C:/Users/" + username + "/AppData/Local/Programs/Microsoft VS Code/Code.exe')," +
                        "('explorer','C:/Windows/explorer.exe')," +
                        "('taskmgr','C:/Windows/System32/taskmgr.exe')," +
                        "('cmd','C:/Windows/System32/cmd.exe');",

                "INSERT OR IGNORE INTO website_registry (alias,url) VALUES " +
                        "('youtube','https://youtube.com')," +
                        "('google','https://google.com')," +
                        "('github','https://github.com')," +
                        "('gmail','https://gmail.com')," +
                        "('stackoverflow','https://stackoverflow.com')," +
                        "('reddit','https://reddit.com')," +
                        "('twitter','https://twitter.com')," +
                        "('facebook','https://facebook.com')," +
                        "('wikipedia','https://wikipedia.org')," +
                        "('linkedin','https://linkedin.com')," +
                        "('chatgpt','https://chat.openai.com')," +
                        "('netflix','https://netflix.com');",

                "INSERT OR IGNORE INTO settings (key,value) VALUES " +
                        "('voice_speed','160')," +
                        "('theme','dark')," +
                        "('ai_enabled','false')," +
                        "('ai_enable_phrase','enable ai')," +
                        "('ai_disable_phrase','disable ai')," +
                        "('ai_model','gemini-3-flash-preview')," +
                        "('ai_system_prompt','You are Rehbar, a concise, helpful desktop voice assistant. Reply naturally and keep spoken answers compact unless asked for detail.')," +
                        "('ai_max_output_tokens','220')," +
                        "('ai_temperature','0.6');"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : staticData) stmt.executeUpdate(sql);
        }
    }

    private void insertTrainingPhrase(PreparedStatement ps, String phrase, String intent) throws SQLException {
        ps.setString(1, phrase.trim().toLowerCase());
        ps.setString(2, intent);
        ps.addBatch();
    }

    private void seedTrainingData() throws SQLException {
        String sql = "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES (?,?)";

        String[] apps = {
                "chrome", "notepad", "calculator", "spotify", "vlc", "discord", "vscode",
                "explorer", "task manager", "cmd", "command prompt", "terminal",
                "paint", "zoom", "steam", "firefox", "edge"
        };

        String[] sites = {
                "youtube", "google", "github", "gmail", "stackoverflow", "reddit",
                "twitter", "facebook", "wikipedia", "linkedin", "chatgpt", "netflix",
                "google maps", "google docs", "google sheets"
        };

        String[] searchTopics = {
                "python tutorials", "java documentation", "donald trump", "weather today",
                "machine learning", "quantum computing", "cheap flights", "stock prices today",
                "latest tech news", "who is the president of pakistan", "where is tokyo",
                "how to cook pasta", "how to install ubuntu", "java streams", "black holes",
                "artificial intelligence", "restaurants near me", "python decorators",
                "space news", "news about tesla", "wikipedia"
        };

        String[] createTargets = {
                "projects", "notes", "backup", "work", "resume", "documents", "photos",
                "configs", "assets", "readme", "todo", "logs", "output"
        };

        String[] deleteTargets = {
                "homework", "old stuff", "temp", "resume", "data", "backup", "downloads",
                "notes", "archive", "log file", "old documents", "old backups"
        };

        String[] renameOld = {
                "report", "resume", "old", "test", "draft", "temp", "log file", "folder", "notes"
        };

        String[] renameNew = {
                "final", "cv", "new", "main", "final version", "cache", "archive", "assets", "readme"
        };

        String[] alarmPhrases = {
                "set alarm for 7 am", "set alarm for 5 30 am", "alarm at 9 pm",
                "new alarm for 8 30", "wake me up at 6 o clock", "wake me in 2 hours",
                "set a timer for 10 minutes", "timer for 25 minutes",
                "remind me at 3pm to drink water", "remind me in 30 minutes",
                "alarm for tomorrow at 7", "set reminder for gym",
                "set a reminder at 6 pm", "new alarm at midnight"
        };

        String[] systemInfoPhrases = {
                "what time is it", "tell me the time", "check the time",
                "what is the date", "show me the date", "what day is it today",
                "show battery level", "check my battery", "what is the battery percentage",
                "current cpu usage", "show cpu usage", "what is the cpu load",
                "how much ram is used", "what is the memory usage", "how much free ram do i have",
                "check disk space", "check my storage", "show system information",
                "show running processes", "how is my system performing"
        };

        String[] chatPhrases = {
                "hello", "hi", "hey", "good morning", "good evening",
                "how are you", "what is your name", "who made you",
                "thanks", "thank you", "okay", "cool", "alright"
        };

        String[] openVerbs = {"open", "launch", "start", "run", "fire up", "bring up"};
        String[] closeVerbs = {"close", "quit", "exit", "stop", "terminate", "kill", "shut down"};
        String[] siteVerbs = {"open", "go to", "visit", "navigate to", "take me to", "browse to"};
        String[] searchVerbs = {"search for", "look up", "find information about", "google", "search the web for"};
        String[] createVerbs = {"create", "make", "generate", "new"};
        String[] deleteVerbs = {"delete", "remove", "erase", "trash", "wipe", "get rid of"};
        String[] renameVerbs = {"rename", "change name of", "modify file name to", "relabel", "update the file name of"};

        String[] fillers = {
                "rehbar ", "hey rehbar ", "ok rehbar ", "okay rehbar ", "actually ", "well ", "i mean ", "so "
        };

        String[] noisyVariants = {
                "cloze chrome", "close krom", "open chrom", "rehbar open krom",
                "look up wikipidia", "serch for donald trump", "go too github",
                "set an allarm for seven am", "wut time is it", "renaim my file to final",
                "delite the bakup folder", "make new foulder", "cloze spotify"
        };

        try (PreparedStatement ps = connection.prepareStatement(sql)) {

            // Existing-style seed phrases, generated in a more scalable way
            for (String app : apps) {
                for (String verb : openVerbs) {
                    insertTrainingPhrase(ps, verb + " " + app, "OPEN_APP");
                }
                for (String verb : closeVerbs) {
                    insertTrainingPhrase(ps, verb + " " + app, "CLOSE_APP");
                }
            }

            for (String site : sites) {
                for (String verb : siteVerbs) {
                    insertTrainingPhrase(ps, verb + " " + site, "OPEN_SITE");
                }
            }

            for (String topic : searchTopics) {
                for (String verb : searchVerbs) {
                    insertTrainingPhrase(ps, verb + " " + topic, "WEB_SEARCH");
                }
            }

            for (String target : createTargets) {
                insertTrainingPhrase(ps, "create folder named " + target, "CREATE_FILE");
                insertTrainingPhrase(ps, "create a folder called " + target, "CREATE_FILE");
                insertTrainingPhrase(ps, "make a new directory " + target, "CREATE_FILE");
                insertTrainingPhrase(ps, "create a file named " + target, "CREATE_FILE");
                insertTrainingPhrase(ps, "make a new text file " + target, "CREATE_FILE");
            }

            for (String target : deleteTargets) {
                insertTrainingPhrase(ps, "delete the file " + target, "DELETE_FILE");
                insertTrainingPhrase(ps, "remove folder " + target, "DELETE_FILE");
                insertTrainingPhrase(ps, "erase file " + target, "DELETE_FILE");
                insertTrainingPhrase(ps, "delete old " + target, "DELETE_FILE");
            }

            for (int i = 0; i < Math.min(renameOld.length, renameNew.length); i++) {
                insertTrainingPhrase(ps, "rename file " + renameOld[i] + " to " + renameNew[i], "RENAME_FILE");
                insertTrainingPhrase(ps, "change name of file " + renameOld[i] + " to " + renameNew[i], "RENAME_FILE");
                insertTrainingPhrase(ps, "rename " + renameOld[i] + " to " + renameNew[i], "RENAME_FILE");
            }

            for (String phrase : alarmPhrases) insertTrainingPhrase(ps, phrase, "SET_ALARM");
            for (String phrase : systemInfoPhrases) insertTrainingPhrase(ps, phrase, "SYSTEM_INFO");

            // Wake-word / filler variants for real voice usage
            String[] commandish = {
                    "open chrome", "close chrome", "go to github", "search for donald trump",
                    "look up wikipedia", "set alarm for 7 am", "what time is it",
                    "make a new folder called ai", "delete the backup folder", "rename my file to final report"
            };
            for (String base : commandish) {
                for (String filler : fillers) {
                    insertTrainingPhrase(ps, filler + base, inferIntent(base));
                }
            }

            // Noisy voice / ASR-style examples
            insertTrainingPhrase(ps, "look up wikipidia", "WEB_SEARCH");
            insertTrainingPhrase(ps, "search for wikipidia", "WEB_SEARCH");
            insertTrainingPhrase(ps, "look up wikipedia", "WEB_SEARCH");
            insertTrainingPhrase(ps, "where is tokyo", "WEB_SEARCH");
            insertTrainingPhrase(ps, "who is donald trump", "WEB_SEARCH");
            insertTrainingPhrase(ps, "what is machine learning", "WEB_SEARCH");
            insertTrainingPhrase(ps, "how to install python", "WEB_SEARCH");

            insertTrainingPhrase(ps, "cloze chrome", "CLOSE_APP");
            insertTrainingPhrase(ps, "cloze spotify", "CLOSE_APP");
            insertTrainingPhrase(ps, "close krom", "CLOSE_APP");
            insertTrainingPhrase(ps, "open chrom", "OPEN_APP");
            insertTrainingPhrase(ps, "open krom", "OPEN_APP");
            insertTrainingPhrase(ps, "rehbar open krom", "OPEN_APP");
            insertTrainingPhrase(ps, "go too github", "OPEN_SITE");
            insertTrainingPhrase(ps, "serch for donald trump", "WEB_SEARCH");
            insertTrainingPhrase(ps, "set an allarm for seven am", "SET_ALARM");
            insertTrainingPhrase(ps, "wut time is it", "SYSTEM_INFO");
            insertTrainingPhrase(ps, "delite the bakup folder", "DELETE_FILE");
            insertTrainingPhrase(ps, "renaim my file to final", "RENAME_FILE");
            insertTrainingPhrase(ps, "make new foulder", "CREATE_FILE");

            for (String phrase : noisyVariants) {
                // Already inserted above in many cases, but INSERT OR IGNORE keeps this safe
                if (phrase.contains("allarm") || phrase.contains("alarm")) {
                    insertTrainingPhrase(ps, phrase, "SET_ALARM");
                } else if (phrase.contains("wut time")) {
                    insertTrainingPhrase(ps, phrase, "SYSTEM_INFO");
                } else if (phrase.contains("wikipidia") || phrase.contains("serch")) {
                    insertTrainingPhrase(ps, phrase, "WEB_SEARCH");
                } else if (phrase.contains("delite")) {
                    insertTrainingPhrase(ps, phrase, "DELETE_FILE");
                } else if (phrase.contains("renaim")) {
                    insertTrainingPhrase(ps, phrase, "RENAME_FILE");
                } else if (phrase.contains("foulder")) {
                    insertTrainingPhrase(ps, phrase, "CREATE_FILE");
                } else if (phrase.startsWith("open") || phrase.contains("open krom") || phrase.contains("open chrom")) {
                    insertTrainingPhrase(ps, phrase, "OPEN_APP");
                } else if (phrase.startsWith("cloze") || phrase.startsWith("close")) {
                    insertTrainingPhrase(ps, phrase, "CLOSE_APP");
                } else if (phrase.contains("github")) {
                    insertTrainingPhrase(ps, phrase, "OPEN_SITE");
                }
            }

            // Small talk
            for (String phrase : chatPhrases) {
                insertTrainingPhrase(ps, phrase, "CHAT");
            }

            ps.executeBatch();
        }

        System.out.println("[DB] training_data seeded / expanded.");
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    public String getSetting(String key) {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("value") : null;
        } catch (SQLException e) {
            System.err.println("[DB] getSetting: " + e.getMessage());
            return null;
        }
    }

    public void saveSetting(String key, String value) {
        String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] saveSetting: " + e.getMessage());
        }
    }

    // ── Command log (frontend query methods) ───────────────────────────────────

    public List<CommandLog> getRecentCommands(int limit) {
        List<CommandLog> list = new ArrayList<>();
        String sql = "SELECT text, type, status, timestamp FROM command_log ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new CommandLog(
                        rs.getString("text"),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getString("timestamp")));
            }
        } catch (SQLException e) {
            System.err.println("[DB] getRecentCommands: " + e.getMessage());
        }
        return list;
    }

    public int getCommandCountToday() {
        String sql = "SELECT COUNT(*) FROM command_log WHERE DATE(timestamp) = DATE('now')";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[DB] getCommandCountToday: " + e.getMessage());
            return 0;
        }
    }

    public int getSuccessRatePercent(int lastN) {
        String sql = "SELECT ROUND(100.0 * SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*))" +
                     " FROM (SELECT status FROM command_log ORDER BY timestamp DESC LIMIT ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, lastN);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[DB] getSuccessRatePercent: " + e.getMessage());
            return 0;
        }
    }

    public void clearCommandLog() {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM command_log")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] clearCommandLog: " + e.getMessage());
        }
    }

    public int[] getCommandCountByHour() {
        int[] counts = new int[24];
        String sql = "SELECT CAST(strftime('%H', timestamp) AS INTEGER) AS hour, COUNT(*) AS cnt" +
                     " FROM command_log WHERE DATE(timestamp) = DATE('now') GROUP BY hour";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int hour = rs.getInt("hour");
                if (hour >= 0 && hour < 24) counts[hour] = rs.getInt("cnt");
            }
        } catch (SQLException e) {
            System.err.println("[DB] getCommandCountByHour: " + e.getMessage());
        }
        return counts;
    }

    // ── Alarm query helpers ────────────────────────────────────────────────────

    public String getNextAlarmLabel() {
        String sql = "SELECT label FROM alarms WHERE is_triggered = 0 AND trigger_time > DATETIME('now')" +
                     " ORDER BY trigger_time ASC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("label") : null;
        } catch (SQLException e) {
            System.err.println("[DB] getNextAlarmLabel: " + e.getMessage());
            return null;
        }
    }

    // ── App registry (frontend CRUD) ───────────────────────────────────────────

    public int getAppRegistryCount() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM app_registry");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            System.err.println("[DB] getAppRegistryCount: " + e.getMessage());
            return 0;
        }
    }

    public List<String[]> getAllAppAliases() {
        List<String[]> list = new ArrayList<>();
        String sql = "SELECT alias, path FROM app_registry ORDER BY alias ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(new String[]{ rs.getString("alias"), rs.getString("path") });
        } catch (SQLException e) {
            System.err.println("[DB] getAllAppAliases: " + e.getMessage());
        }
        return list;
    }

    public void addAppAlias(String alias, String path) throws SQLException {
        String sql = "INSERT INTO app_registry (alias, path) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, alias.toLowerCase());
            ps.setString(2, path);
            ps.executeUpdate();
        }
    }

    public void removeAppAlias(String alias) {
        String sql = "DELETE FROM app_registry WHERE alias = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, alias);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] removeAppAlias: " + e.getMessage());
        }
    }

    // ── Website registry (frontend CRUD) ──────────────────────────────────────

    public List<String[]> getAllWebsiteAliases() {
        List<String[]> list = new ArrayList<>();
        String sql = "SELECT alias, url FROM website_registry ORDER BY alias ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(new String[]{ rs.getString("alias"), rs.getString("url") });
        } catch (SQLException e) {
            System.err.println("[DB] getAllWebsiteAliases: " + e.getMessage());
        }
        return list;
    }

    public void addWebsiteAlias(String alias, String url) throws SQLException {
        String sql = "INSERT INTO website_registry (alias, url) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, alias.toLowerCase());
            ps.setString(2, url);
            ps.executeUpdate();
        }
    }

    public void removeWebsiteAlias(String alias) {
        String sql = "DELETE FROM website_registry WHERE alias = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, alias);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] removeWebsiteAlias: " + e.getMessage());
        }
    }

    // ── Inner model class ──────────────────────────────────────────────────────

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

    private String inferIntent(String text) {
        String t = text.toLowerCase();
        if (t.contains("alarm") || t.contains("timer") || t.contains("remind") || t.contains("wake me")) {
            return "SET_ALARM";
        }
        if (t.contains("what time") || t.contains("battery") || t.contains("cpu") || t.contains("ram") || t.contains("date")) {
            return "SYSTEM_INFO";
        }
        if (t.startsWith("open chrome") || t.startsWith("close chrome")) {
            return t.startsWith("open") ? "OPEN_APP" : "CLOSE_APP";
        }
        if (t.contains("go to github")) {
            return "OPEN_SITE";
        }
        if (t.contains("search for") || t.contains("look up") || t.contains("wikipedia")) {
            return "WEB_SEARCH";
        }
        if (t.contains("make a new folder") || t.contains("create")) {
            return "CREATE_FILE";
        }
        if (t.contains("delete")) {
            return "DELETE_FILE";
        }
        if (t.contains("rename")) {
            return "RENAME_FILE";
        }
        return "WEB_SEARCH";
    }
}
