package commands;

import db.DatabaseManager;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;

/** Opens and closes desktop applications via DB alias lookup with executable-exists check and taskkill for close. */
public class AppManager implements CommandHandler {

    // ── Open ──────────────────────────────────────────────────────────────────

    @Override
    public String execute(String input) { return open(input); }

    public String open(String command) {
        String appName = extractAppName(command);

        if (appName.isBlank()) {
            return "Please tell me which application you want to open.";
        }

        // ── 1. App registry ───────────────────────────────────────────────────
        String path = DatabaseManager.getInstance().getAppPath(appName);
        if (path != null) {
            java.io.File exeFile = new java.io.File(path);
            if (!exeFile.exists()) {
                return "The executable for '" + appName + "' was not found at: " + path +
                        ". Please update its path in the Registry panel.";
            }
            try {
                new ProcessBuilder(path).start();
                return "Opening " + appName + ".";
            } catch (IOException e) {
                return "Could not open '" + appName + "'. Check that you have permission to run it.";
            }
        }

        // ── 2. Website registry fallback ──────────────────────────────────────
        // Handles the case where the user says "open flex" but "flex" is stored
        // as a website alias (intent classifier can't know which registry to use).
        String url = DatabaseManager.getInstance().getWebsiteUrl(appName);
        if (url != null) {
            if (!Desktop.isDesktopSupported()) {
                return "Cannot open a browser — desktop mode is not available.";
            }
            try {
                Desktop.getDesktop().browse(new URI(url));
                return "Opening " + appName + ".";
            } catch (Exception e) {
                return "Could not open '" + appName + "' in the browser.";
            }
        }

        // ── 3. Not found in either registry ───────────────────────────────────
        return "I don't have '" + appName + "' in my registry. " +
                "Add it in the Registry panel and I'll be able to open it.";
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

    /** Strips all action/filler/politeness words so the bare app name reaches the DB alias lookup. */
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