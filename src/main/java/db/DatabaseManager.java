package db;

import java.io.File;
import java.sql.*;
//import java.util.Strin

public class DatabaseManager {
    private static DatabaseManager instance;
    private Connection connection;
    private static final String DB_PATH =
            System.getenv("APPDATA") + "/RAHBAR/rahbar.db";

    private DatabaseManager() {
        // Create the RAHBAR folder in AppData if it doesn't exist

        try {
            new File(System.getenv("APPDATA") + "/RAHBAR").mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            createTables();  // Create all tables on first run
        }
        catch (SQLException e){
            System.err.println("Database connection failed: " + e.getMessage());
            e.printStackTrace();    // printing the error
        }

    }

    public static DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }




    private void populate_tables() {
        String username = System.getProperty("user.name");      // getting the name of the user

        // Prepared SQL queries
        String[] inserts = {
                // App Registry
                "INSERT OR IGNORE INTO app_registry (alias, path) VALUES " +
                        "('chrome', 'C:/Program Files/Google/Chrome/Application/chrome.exe'), " +
                        "('notepad', 'C:/Windows/System32/notepad.exe'), " +
                        "('calculator', 'C:/Windows/System32/calc.exe'), " +
                        "('spotify', 'C:/Users/" + username + "/AppData/Roaming/Spotify/Spotify.exe');",

                // Website Registry
                "INSERT OR IGNORE INTO website_registry (alias, url) VALUES " +
                        "('youtube', 'https://youtube.com'), " +
                        "('google', 'https://google.com'), " +
                        "('github', 'https://github.com'), " +
                        "('gmail', 'https://gmail.com');",

                // Settings
                "INSERT OR IGNORE INTO settings (key, value) VALUES " +
                        "('voice_speed', '150'), " +
                        "('theme', 'dark'), " +
                        "('ai_enabled', 'true');"
        };

        try (java.sql.Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false); // Start transaction

            for (String sql : inserts) {
                stmt.executeUpdate(sql);
            }

            connection.commit(); // Save all changes
            System.out.println("Default data populated successfully.");
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    private void createTables() {
        String[] queries = {
                // Table 1: Command Log
                "CREATE TABLE IF NOT EXISTS command_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "text TEXT NOT NULL, " +
                        "type TEXT, " +
                        "status TEXT, " +
                        "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);",

                // Table 2: Alarms
                "CREATE TABLE IF NOT EXISTS alarms (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT, " +
                        "trigger_time DATETIME NOT NULL, " +
                        "is_triggered INTEGER DEFAULT 0, " +
                        "repeat_type TEXT DEFAULT 'NONE');",

                // Table 3: App Registry
                "CREATE TABLE IF NOT EXISTS app_registry (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "alias TEXT UNIQUE NOT NULL, " +
                        "path TEXT NOT NULL);",

                // Table 4: Website Registry
                "CREATE TABLE IF NOT EXISTS website_registry (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "alias TEXT UNIQUE NOT NULL, " +
                        "url TEXT NOT NULL);",

                // Table 5: Training Data
                "CREATE TABLE IF NOT EXISTS training_data (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "phrase TEXT NOT NULL, " +
                        "intent TEXT NOT NULL);",

                // Table 6: Settings
                "CREATE TABLE IF NOT EXISTS settings (" +
                        "key TEXT PRIMARY KEY, " +
                        "value TEXT);"
        };

        // Use the existing connection to execute the statements
        try (java.sql.Statement stmt = connection.createStatement()) {
            for (String sql : queries) {
                stmt.execute(sql);
            }
            System.out.println("All tables initialized successfully.");
            populate_tables();
        } catch (SQLException e) {
            System.err.println("Error initializing database tables: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // some utitlity functions
    public String getAppPath(String appname) {
        String sql = "SELECT path FROM app_registry WHERE alias = ?";


        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {

            pstmt.setString(1, appname.toLowerCase());  // REMINDER TO CHECK THIS IF I FORGOT TO PUT NAMES IN LOWER CASE
            try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // If a record is found, returning the path
                    return rs.getString("path");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error looking up app path: " + e.getMessage());
            e.printStackTrace();
        }

        // Return null or throw an exception if the app is not found
        return null;
    }

    public String getWebsiteUrl(String web_name) {
        // Select the url where the alias matches the input
        String sql = "SELECT url FROM website_registry WHERE alias = ?";

        // Use try-with-resources to automatically close the statement and result set
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {

            // Use lowercase to ensure consistent matching with your database entries
            pstmt.setString(1, web_name.toLowerCase());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // If a record is found, return the url
                    return rs.getString("url");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error looking up website URL: " + e.getMessage());
            e.printStackTrace();
        }

        // Return null if no website was found with that alias
        return null;
    }


    public void logCommand(String command, String intent, String status) {
        String sql = "INSERT INTO command_log (text, type, status) VALUES (?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {

            pstmt.setString(1, command);
            pstmt.setString(2, intent);
            pstmt.setString(3, status);

            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Error logging command: " + e.getMessage());

        }
    }
}



