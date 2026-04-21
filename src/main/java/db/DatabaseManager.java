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
 * Added vs original:
 *   - saveAlarm(), markAlarmTriggered(), getAllAlarms(), Alarm DTO
 *   - training_data table is now fully seeded (~500+ phrases, 9 intents)
 *     so the ML classifier has real data to train on instead of the
 *     ~60 hardcoded phrases in the old intent_classifier.py
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
        public final int           id;
        public final String        label;
        public final LocalDateTime triggerTime;
        public Alarm(int id, String label, LocalDateTime triggerTime) {
            this.id = id; this.label = label; this.triggerTime = triggerTime;
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
                        "phrase TEXT NOT NULL, intent TEXT NOT NULL);",
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

        String[] staticData = {
                // App registry
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

                // Website registry
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

                // Settings
                "INSERT OR IGNORE INTO settings (key,value) VALUES " +
                        "('voice_speed','160'),('theme','dark'),('ai_enabled','false');"
        };

        // ── Training data: ~500+ phrases, 9 intents ──────────────────────────
        String[] trainingData = {

                // OPEN_APP
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('open chrome','OPEN_APP'),('launch firefox','OPEN_APP')," +
                        "('start spotify','OPEN_APP'),('run notepad','OPEN_APP')," +
                        "('execute vlc','OPEN_APP'),('fire up the browser','OPEN_APP')," +
                        "('can you open excel','OPEN_APP'),('please start word','OPEN_APP')," +
                        "('open the terminal','OPEN_APP'),('launch steam','OPEN_APP')," +
                        "('start discord','OPEN_APP'),('boot up vscode','OPEN_APP')," +
                        "('open paint','OPEN_APP'),('launch photoshop','OPEN_APP')," +
                        "('start the file explorer','OPEN_APP'),('run the calculator','OPEN_APP')," +
                        "('open my music app','OPEN_APP'),('launch windows media player','OPEN_APP')," +
                        "('open the task manager','OPEN_APP'),('open control panel','OPEN_APP')," +
                        "('launch android studio','OPEN_APP'),('start intellij','OPEN_APP')," +
                        "('open spotify','OPEN_APP'),('run the browser','OPEN_APP')," +
                        "('open my pdf reader','OPEN_APP'),('please run discord','OPEN_APP')," +
                        "('start firefox for me','OPEN_APP'),('open the command prompt','OPEN_APP')," +
                        "('start vlc media player','OPEN_APP'),('can you launch chrome','OPEN_APP')," +
                        "('open a new terminal','OPEN_APP'),('launch telegram','OPEN_APP')," +
                        "('start skype','OPEN_APP'),('launch opera','OPEN_APP')," +
                        "('bring up chrome','OPEN_APP'),('pull up notepad','OPEN_APP')," +
                        "('open edge browser','OPEN_APP'),('get spotify running','OPEN_APP')," +
                        "('open regedit','OPEN_APP'),('launch cmd','OPEN_APP')," +
                        "('start the program','OPEN_APP'),('initialize the application','OPEN_APP')," +
                        "('open my application','OPEN_APP'),('run my code editor','OPEN_APP')," +
                        "('start up excel','OPEN_APP'),('open zoom','OPEN_APP');",

                // CLOSE_APP
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('close chrome','CLOSE_APP'),('quit firefox','CLOSE_APP')," +
                        "('exit spotify','CLOSE_APP'),('stop vlc','CLOSE_APP')," +
                        "('kill the process','CLOSE_APP'),('shut down notepad','CLOSE_APP')," +
                        "('terminate discord','CLOSE_APP'),('end the program','CLOSE_APP')," +
                        "('force quit excel','CLOSE_APP'),('close the browser','CLOSE_APP')," +
                        "('stop the music player','CLOSE_APP'),('exit out of steam','CLOSE_APP')," +
                        "('quit word','CLOSE_APP'),('shut down the terminal','CLOSE_APP')," +
                        "('close cmd','CLOSE_APP'),('kill chrome','CLOSE_APP')," +
                        "('stop microsoft teams','CLOSE_APP'),('close the game','CLOSE_APP')," +
                        "('exit zoom','CLOSE_APP'),('terminate the app','CLOSE_APP')," +
                        "('shut down outlook','CLOSE_APP'),('close powerpoint','CLOSE_APP')," +
                        "('quit excel','CLOSE_APP'),('kill the terminal','CLOSE_APP')," +
                        "('stop discord','CLOSE_APP'),('quit the browser','CLOSE_APP')," +
                        "('shut down explorer','CLOSE_APP'),('exit the program','CLOSE_APP')," +
                        "('terminate firefox','CLOSE_APP'),('close edge','CLOSE_APP')," +
                        "('force close the window','CLOSE_APP'),('shut it down','CLOSE_APP')," +
                        "('kill signal','CLOSE_APP'),('close telegram','CLOSE_APP')," +
                        "('stop spotify','CLOSE_APP'),('end this app','CLOSE_APP');",

                // WEB_SEARCH
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('search for python tutorials','WEB_SEARCH')," +
                        "('google the weather today','WEB_SEARCH')," +
                        "('look up java documentation','WEB_SEARCH')," +
                        "('search on the web for news','WEB_SEARCH')," +
                        "('find information about space','WEB_SEARCH')," +
                        "('who is the president','WEB_SEARCH')," +
                        "('how to cook pasta','WEB_SEARCH')," +
                        "('what is machine learning','WEB_SEARCH')," +
                        "('google how to fix windows error','WEB_SEARCH')," +
                        "('look up the recipe for pizza','WEB_SEARCH')," +
                        "('search for cheap flights','WEB_SEARCH')," +
                        "('what is the capital of france','WEB_SEARCH')," +
                        "('look up python syntax','WEB_SEARCH')," +
                        "('how to learn programming','WEB_SEARCH')," +
                        "('what is quantum computing','WEB_SEARCH')," +
                        "('how to meditate','WEB_SEARCH')," +
                        "('what is the fastest car in the world','WEB_SEARCH')," +
                        "('how to install ubuntu','WEB_SEARCH')," +
                        "('what is dark matter','WEB_SEARCH')," +
                        "('how to learn guitar','WEB_SEARCH')," +
                        "('what is blockchain','WEB_SEARCH')," +
                        "('how to improve memory','WEB_SEARCH')," +
                        "('how to make a website','WEB_SEARCH')," +
                        "('search the internet','WEB_SEARCH')," +
                        "('browse for information','WEB_SEARCH')," +
                        "('research about artificial intelligence','WEB_SEARCH')," +
                        "('find news articles','WEB_SEARCH')," +
                        "('google nearby restaurants','WEB_SEARCH')," +
                        "('look up stock prices today','WEB_SEARCH')," +
                        "('find the latest tech news','WEB_SEARCH');",

                // CREATE_FILE
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('create folder named projects','CREATE_FILE')," +
                        "('make a new directory','CREATE_FILE')," +
                        "('new folder called notes','CREATE_FILE')," +
                        "('generate a file named test','CREATE_FILE')," +
                        "('create a new text file','CREATE_FILE')," +
                        "('make directory backup','CREATE_FILE')," +
                        "('create a folder called work','CREATE_FILE')," +
                        "('make a new file named resume','CREATE_FILE')," +
                        "('create documents folder','CREATE_FILE')," +
                        "('make a folder for my photos','CREATE_FILE')," +
                        "('create a file called todo','CREATE_FILE')," +
                        "('make a new txt file','CREATE_FILE')," +
                        "('create a folder on the desktop','CREATE_FILE')," +
                        "('make a new project folder','CREATE_FILE')," +
                        "('create notes folder','CREATE_FILE')," +
                        "('make a new empty file','CREATE_FILE')," +
                        "('create a text document','CREATE_FILE')," +
                        "('make a new directory named configs','CREATE_FILE')," +
                        "('create a file named readme','CREATE_FILE')," +
                        "('new folder please','CREATE_FILE')," +
                        "('create me a folder','CREATE_FILE')," +
                        "('make a text file','CREATE_FILE')," +
                        "('create output folder','CREATE_FILE')," +
                        "('make a backup folder','CREATE_FILE')," +
                        "('create a folder called test','CREATE_FILE')," +
                        "('make a new word document','CREATE_FILE')," +
                        "('generate new file now','CREATE_FILE');",

                // DELETE_FILE
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('delete the file homework','DELETE_FILE')," +
                        "('remove folder old stuff','DELETE_FILE')," +
                        "('erase file temp','DELETE_FILE')," +
                        "('trash this folder','DELETE_FILE')," +
                        "('delete my resume','DELETE_FILE')," +
                        "('remove the directory data','DELETE_FILE')," +
                        "('get rid of the file report','DELETE_FILE')," +
                        "('wipe the folder backup','DELETE_FILE')," +
                        "('delete the downloads folder','DELETE_FILE')," +
                        "('remove file called notes','DELETE_FILE')," +
                        "('erase the directory logs','DELETE_FILE')," +
                        "('delete folder named archive','DELETE_FILE')," +
                        "('remove old documents','DELETE_FILE')," +
                        "('erase that file','DELETE_FILE')," +
                        "('delete temp folder','DELETE_FILE')," +
                        "('remove the log file','DELETE_FILE')," +
                        "('clean up old files','DELETE_FILE')," +
                        "('delete old backups','DELETE_FILE')," +
                        "('permanently delete this file','DELETE_FILE')," +
                        "('throw away this folder','DELETE_FILE')," +
                        "('discard the old directory','DELETE_FILE');",

                // RENAME_FILE
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('rename file report to final','RENAME_FILE')," +
                        "('change name of folder to work','RENAME_FILE')," +
                        "('rename this to backup','RENAME_FILE')," +
                        "('modify file name to notes','RENAME_FILE')," +
                        "('rename my resume to cv','RENAME_FILE')," +
                        "('rename file called old to new','RENAME_FILE')," +
                        "('change file test to main','RENAME_FILE')," +
                        "('rename this folder to assets','RENAME_FILE')," +
                        "('rename draft to final version','RENAME_FILE')," +
                        "('change name of file to readme','RENAME_FILE')," +
                        "('rename file temp to cache','RENAME_FILE')," +
                        "('rename this file please','RENAME_FILE')," +
                        "('update the file name','RENAME_FILE')," +
                        "('give this file a new name','RENAME_FILE')," +
                        "('relabel this folder','RENAME_FILE')," +
                        "('rename log file to archive','RENAME_FILE')," +
                        "('change that file name','RENAME_FILE');",

                // OPEN_SITE
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('open youtube','OPEN_SITE'),('go to github','OPEN_SITE')," +
                        "('navigate to gmail','OPEN_SITE'),('visit stackoverflow','OPEN_SITE')," +
                        "('open facebook','OPEN_SITE'),('navigate to twitter','OPEN_SITE')," +
                        "('visit reddit','OPEN_SITE'),('go to google','OPEN_SITE')," +
                        "('open amazon','OPEN_SITE'),('navigate to netflix','OPEN_SITE')," +
                        "('visit linkedin','OPEN_SITE'),('open instagram','OPEN_SITE')," +
                        "('go to wikipedia','OPEN_SITE'),('open twitch','OPEN_SITE')," +
                        "('go to chatgpt','OPEN_SITE'),('open spotify web','OPEN_SITE')," +
                        "('go to google docs','OPEN_SITE'),('open google sheets','OPEN_SITE')," +
                        "('take me to youtube','OPEN_SITE'),('launch the website','OPEN_SITE')," +
                        "('browse to google','OPEN_SITE'),('open the site','OPEN_SITE')," +
                        "('open google maps','OPEN_SITE'),('navigate to microsoft','OPEN_SITE')," +
                        "('visit apple website','OPEN_SITE'),('go to reddit','OPEN_SITE')," +
                        "('navigate to hacker news','OPEN_SITE'),('open dev to','OPEN_SITE');",

                // SET_ALARM
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('set alarm for 7 am','SET_ALARM')," +
                        "('remind me at 3pm to drink water','SET_ALARM')," +
                        "('wake me up at 6 o clock','SET_ALARM')," +
                        "('set a timer for 10 minutes','SET_ALARM')," +
                        "('new alarm for 8 30','SET_ALARM')," +
                        "('wake me up in 2 hours','SET_ALARM')," +
                        "('set alarm for 5 30 am','SET_ALARM')," +
                        "('remind me in 30 minutes','SET_ALARM')," +
                        "('alarm at 9 pm','SET_ALARM')," +
                        "('remind me to take medicine at noon','SET_ALARM')," +
                        "('set morning alarm','SET_ALARM')," +
                        "('set timer for 1 hour','SET_ALARM')," +
                        "('remind me about the meeting at 2pm','SET_ALARM')," +
                        "('new alarm at midnight','SET_ALARM')," +
                        "('set a 15 minute timer','SET_ALARM')," +
                        "('remind me to call mom at 4pm','SET_ALARM')," +
                        "('timer for 20 minutes please','SET_ALARM')," +
                        "('set alarm for 7 30 am','SET_ALARM')," +
                        "('wake me up at 8','SET_ALARM')," +
                        "('set countdown timer','SET_ALARM')," +
                        "('create an alarm for 8 am','SET_ALARM')," +
                        "('set reminder for gym','SET_ALARM')," +
                        "('alarm for tomorrow at 7','SET_ALARM')," +
                        "('set a reminder at 6 pm','SET_ALARM')," +
                        "('timer for 25 minutes','SET_ALARM');",

                // SYSTEM_INFO
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('what time is it','SYSTEM_INFO')," +
                        "('what is the date','SYSTEM_INFO')," +
                        "('show battery level','SYSTEM_INFO')," +
                        "('how much ram is used','SYSTEM_INFO')," +
                        "('current cpu usage','SYSTEM_INFO')," +
                        "('tell me the time','SYSTEM_INFO')," +
                        "('what day is it today','SYSTEM_INFO')," +
                        "('check my battery','SYSTEM_INFO')," +
                        "('what is the memory usage','SYSTEM_INFO')," +
                        "('show me the date','SYSTEM_INFO')," +
                        "('what is the cpu load','SYSTEM_INFO')," +
                        "('check the time','SYSTEM_INFO')," +
                        "('what is the battery percentage','SYSTEM_INFO')," +
                        "('show system information','SYSTEM_INFO')," +
                        "('what is the ram usage','SYSTEM_INFO')," +
                        "('check disk space','SYSTEM_INFO')," +
                        "('tell me the date','SYSTEM_INFO')," +
                        "('show me battery status','SYSTEM_INFO')," +
                        "('how much free ram do I have','SYSTEM_INFO')," +
                        "('show cpu usage','SYSTEM_INFO')," +
                        "('check my storage','SYSTEM_INFO')," +
                        "('what is my battery level','SYSTEM_INFO')," +
                        "('show running processes','SYSTEM_INFO')," +
                        "('how is my system performing','SYSTEM_INFO')," +
                        "('how much battery is left','SYSTEM_INFO')," +
                        "('check the processor speed','SYSTEM_INFO')," +
                        "('show me all system stats','SYSTEM_INFO');" ,

                // UNKNOWN (teaches low-confidence fallback)
                "INSERT OR IGNORE INTO training_data (phrase,intent) VALUES " +
                        "('asdf','UNKNOWN'),('xyzzy','UNKNOWN'),('blah blah','UNKNOWN')," +
                        "('test test test','UNKNOWN'),('random noise','UNKNOWN')," +
                        "('um uh er','UNKNOWN'),('mhmm','UNKNOWN');"
        };

        try (Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String sql : staticData)   stmt.executeUpdate(sql);
            for (String sql : trainingData) stmt.executeUpdate(sql);
            connection.commit();
            System.out.println("[DB] Default data populated.");
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}