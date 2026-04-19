package commands;

import db.DatabaseManager;

public class AppManager {

    public String open(String command) {
        // Extract app name from command
        String appName = command.replace("open", "").trim();

        // Looking up the executable path from DB
        String path = DatabaseManager.getInstance().getAppPath(appName);
        if (path == null) return "App '" + appName + "' not found in registry.";

        try {
            new ProcessBuilder(path).start();
            return "Opening " + appName;
        } catch (Exception e) {
            return "Error: Could not open " + appName + ". Check permissions.";
        }
    }

    // UC-04: Close Application
    public String close(String command) {
        String appName = command.replace("close", "").trim();
        try {
            // Killing the process by name using Windows taskkill command
            Runtime.getRuntime().exec("taskkill /F /IM " + appName + ".exe");
            return "Closing " + appName;
        } catch (Exception e) {
            return "Error: Could not close " + appName;
        }
    }
}

