package commands;

import db.DatabaseManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * AppManager -- opens and closes desktop applications.
 *
 * Fixes vs original:
 *  - extractAppName() strips ALL action verbs used in training data
 *    (open, launch, start, run, execute, fire up, boot up, bring up,
 *    pull up, please, can you, could you, hey rahbar, rahbar, the, my, a, an)
 *    so the DB alias lookup actually finds the app.
 *  - open() checks the executable path exists on disk before launching.
 *  - close() uses ProcessBuilder string-array form instead of
 *    Runtime.exec(String) -- avoids shell tokenisation and injection bugs.
 *  - close() reads taskkill exit code: 0 = killed, non-zero = not running.
 *  - Both methods return clear spoken messages on every failure path.
 */
public class AppManager {

    // ── Open ──────────────────────────────────────────────────────────────────

    public String open(String command) {
        String appName = extractAppName(command);

        if (appName.isBlank()) {
            return "Please tell me which application you want to open.";
        }

        String path = DatabaseManager.getInstance().getAppPath(appName);
        if (path == null) {
            return "I don't have '" + appName + "' in my app registry. " +
                    "You can register it in the database.";
        }

        java.io.File exeFile = new java.io.File(path);
        if (!exeFile.exists()) {
            return "The executable for '" + appName + "' was not found at: " + path +
                    ". Please update its path in the registry.";
        }

        try {
            new ProcessBuilder(path).start();
            return "Opening " + appName + ".";
        } catch (IOException e) {
            return "Could not open '" + appName + "'. Check that you have permission to run it.";
        }
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    public String close(String command) {
        String appName = extractAppName(command);

        if (appName.isBlank()) {
            return "Please tell me which application you want to close.";
        }

        String exeName = appName.endsWith(".exe") ? appName : appName + ".exe";

        try {
            Process p = new ProcessBuilder("taskkill", "/F", "/IM", exeName)
                    .start();
            int exit = p.waitFor();

            if (exit == 0) {
                return "Closed " + appName + ".";
            } else {
                return "'" + appName + "' does not appear to be running.";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Close operation was interrupted.";
        } catch (IOException e) {
            return "Could not run taskkill. Make sure you are on Windows.";
        }
    }

    // ── Name extraction ───────────────────────────────────────────────────────

    /**
     * Strips every action/filler word so the bare app name reaches the DB.
     *
     * Examples:
     *   "please open chrome"         ->  "chrome"
     *   "can you launch firefox"     ->  "firefox"
     *   "fire up spotify"            ->  "spotify"
     *   "bring up the task manager"  ->  "task manager"
     *   "force close the window"     ->  "window"
     *   "quit microsoft teams"       ->  "microsoft teams"
     */
    private String extractAppName(String command) {
        String cmd = command.toLowerCase().trim();

        // Leading politeness / wake-word
        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        // Open-family verb phrases (longest first)
        cmd = cmd.replaceAll(
                "^(fire up|boot up|start up|pull up|bring up|" +
                        "open the|launch the|start the|run the|" +
                        "open a new|open my|open|launch|start|run|" +
                        "execute|boot|initialize the|get running)\\s*", "");

        // Close-family verb phrases
        cmd = cmd.replaceAll(
                "^(force quit|force close|shut down|shut it down|" +
                        "exit out of|terminate|kill|quit|close|stop|end|exit)\\s*", "");

        // Leading articles / possessives
        cmd = cmd.replaceAll("^(the|my|a|an)\\s+", "");

        // Trailing noise
        cmd = cmd.replaceAll("\\s+(for me|please|now)$", "");

        return cmd.trim();
    }
}