package commands;

import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import db.DatabaseManager;

public class WebManager {
    // UC-08: Web Search
    public String search(String command) {
        String query = command.replaceAll("(?i)(search for|google)", "").trim();
        if (query.isEmpty()) return "NEED_QUERY"; // UI will ask what to search
        String url = "https://www.google.com/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
        return openUrl(url, "Searching for " + query);
    }

    // UC-09: Open Website
    public String openSite(String command) {
        String siteName = command.replaceAll("(?i)open", "").trim();
        String url = DatabaseManager.getInstance().getWebsiteUrl(siteName);
        if (url == null) {
            // Try adding .com if not in DB
            url = "https://" + siteName + ".com";
        }
        return openUrl(url, "Opening " + siteName);
    }

    private String openUrl(String url, String message) {
        try {
            Desktop.getDesktop().browse(new URI(url));
            return message;
        } catch (Exception e) {
            return "Error: Could not open browser. Check your internet connection.";
        }
    }
}
