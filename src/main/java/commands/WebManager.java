package commands;

import db.DatabaseManager;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WebManager -- web search and website navigation.
 *
 * Fixes vs original:
 *  - extractSearchQuery() strips ALL WEB_SEARCH training phrases
 *    (search for, google, look up, find, what is, how to, browse for, etc.)
 *    so the actual query reaches Google instead of the verb phrase.
 *  - extractSiteName() strips ALL OPEN_SITE training phrases
 *    (go to, navigate to, visit, browse to, take me to, open, etc.)
 *    so the DB lookup or URL construction works correctly.
 *  - openSite() falls back to a Google search when the extracted name
 *    still contains spaces (stripping was incomplete) instead of building
 *    a broken URL like "https://go to youtube.com".
 *  - Desktop.isDesktopSupported() check added to avoid crash on headless JVMs.
 */
public class WebManager {

    // ── Web Search ────────────────────────────────────────────────────────────

    public String search(String command) {
        String query = extractSearchQuery(command);
        if (query.isBlank()) {
            return "What would you like me to search for?";
        }
        String url = "https://www.google.com/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
        return openUrl(url, "Searching for: " + query);
    }

    // ── Open Website ──────────────────────────────────────────────────────────

    public String openSite(String command) {
        String siteName = extractSiteName(command);
        if (siteName.isBlank()) {
            return "Which website would you like me to open?";
        }

        // ── 1. Website registry lookup ────────────────────────────────────────
        String url = DatabaseManager.getInstance().getWebsiteUrl(siteName);
        if (url != null) {
            return openUrl(url, "Opening " + siteName);
        }

        // ── 2. App registry fallback ──────────────────────────────────────────
        // If "go to flex" is said but "flex" is actually an app alias, launch it.
        String appPath = DatabaseManager.getInstance().getAppPath(siteName);
        if (appPath != null) {
            java.io.File exe = new java.io.File(appPath);
            if (exe.exists()) {
                try {
                    new ProcessBuilder(appPath).start();
                    return "Opening " + siteName + ".";
                } catch (Exception e) {
                    return "Could not open '" + siteName + "'.";
                }
            }
        }

        // ── 3. No registry match — synthesise or search ───────────────────────
        // If the site name still has spaces, fall back to a Google search
        // to avoid building a broken URL like "https://go to youtube.com"
        if (siteName.contains(" ")) {
            String searchUrl = "https://www.google.com/search?q=" +
                    URLEncoder.encode(siteName, StandardCharsets.UTF_8);
            return openUrl(searchUrl, "Searching for " + siteName);
        }

        // Single word — try https://<name>.com
        return openUrl("https://" + siteName + ".com", "Opening " + siteName);
    }

    // ── URL launcher ──────────────────────────────────────────────────────────

    private String openUrl(String url, String successMessage) {
        if (!Desktop.isDesktopSupported()) {
            return "Cannot open a browser -- desktop mode is not available.";
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
            return successMessage;
        } catch (Exception e) {
            return "Could not open the browser. " +
                    "Make sure a default browser is set and try again.";
        }
    }

    // ── Text extraction helpers ───────────────────────────────────────────────

    /**
     * Strips every WEB_SEARCH verb phrase to isolate the raw query.
     *
     * Covers all training-data phrases:
     *   "search for X", "google X", "look up X", "find X",
     *   "what is X", "how to X", "browse for X", "research about X", etc.
     */
    private String extractSearchQuery(String command) {
        String cmd = command.toLowerCase().trim();

        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        // Multi-word phrases first (longest match wins)
        cmd = cmd.replaceAll(
                "^(search the internet for|search on the web for|" +
                        "find information (on|about)|find articles about|" +
                        "find tutorials for|find the latest|find news articles|" +
                        "browse for information on|search information about|" +
                        "google how to|look up the recipe for|look up|" +
                        "search for news about|research about|find the best|" +
                        "search youtube for|google best|google nearby|" +
                        "google symptoms of|google|how do you|how to|" +
                        "what is the|what is|who is the|who is|" +
                        "when is|where is|find|search|browse|lookup)\\s*", "");

        return cmd.trim();
    }

    /**
     * Strips every OPEN_SITE navigation phrase to isolate the bare site name.
     *
     * Covers all training-data phrases:
     *   "open X", "go to X", "navigate to X", "visit X",
     *   "browse to X", "take me to X", etc.
     */
    private String extractSiteName(String command) {
        String cmd = command.toLowerCase().trim();

        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        // Multi-word navigation phrases first
        cmd = cmd.replaceAll(
                "^(navigate to|take me to|browse to|go to|" +
                        "launch the website|open the site|open)\\s*", "");

        // Single-word verbs that may remain
        cmd = cmd.replaceAll("^(visit|go|browse)\\s+", "");

        // Leading articles
        cmd = cmd.replaceAll("^(the|my|a|an)\\s+", "");

        return cmd.trim();
    }
}