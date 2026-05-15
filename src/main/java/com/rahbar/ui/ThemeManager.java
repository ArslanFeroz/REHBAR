package com.rahbar.ui;

import javafx.scene.Scene;

import java.util.HashMap;
import java.util.Map;

/** Manages theme CSS swapping on the root Scene. Themes defined per-enum with CSS variable files. */
public class ThemeManager {

    // ── Singleton
    private static ThemeManager instance;

    public static ThemeManager getInstance() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }


    public enum Theme {
        NAVY_CYAN      ("Navy + Cyan",      "Deep navy & cyan — the classic Jarvis palette"),
        BLACK_BLUE     ("Black + Blue",     "Pure black & electric blue — dark sci-fi"),
        CHARCOAL_GREEN ("Charcoal + Green", "Dark charcoal & neon green — terminal hacker"),
        PURPLE_GOLD    ("Purple + Gold",    "Deep purple & gold — premium luxury feel"),
        WHITE_BLACK    ("White + Black",    "Clean white canvas & dark sidebar — editorial professional");

        public final String displayName;
        public final String description;

        Theme(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
    }

    // CSS resource paths — each theme file defines the same CSS variables with different values
    private static final Map<Theme, String> THEME_CSS = new HashMap<>();

    static {
        THEME_CSS.put(Theme.NAVY_CYAN,       "/css/theme-navy-cyan.css");
        THEME_CSS.put(Theme.BLACK_BLUE,      "/css/theme-black-blue.css");
        THEME_CSS.put(Theme.CHARCOAL_GREEN,  "/css/theme-charcoal-green.css");
        THEME_CSS.put(Theme.PURPLE_GOLD,     "/css/theme-purple-gold.css");
        THEME_CSS.put(Theme.WHITE_BLACK,     "/css/theme-white-black.css");
    }

    // The shared component stylesheet loaded on top of whichever theme is active
    private static final String COMPONENTS_CSS = "/css/components.css";

    private Theme   currentTheme = Theme.NAVY_CYAN;   // default
    private Scene   managedScene = null;


    public void init(Scene scene, Theme theme) {
        this.managedScene = scene;
        applyTheme(theme);
    }

    /** Switches to the given theme. Call from JavaFX Application Thread only. */
    public void applyTheme(Theme theme) {
        if (managedScene == null) {
            System.err.println("[ThemeManager] applyTheme called but no scene is managed yet.");
            return;
        }

        currentTheme = theme;
        managedScene.getStylesheets().clear();

        java.net.URL themeResource = getClass().getResource(THEME_CSS.get(theme));
        java.net.URL componentsResource = getClass().getResource(COMPONENTS_CSS);

        if (themeResource == null) {
            System.err.println("[ThemeManager] CSS file not found: " + THEME_CSS.get(theme));
            return;
        }
        if (componentsResource == null) {
            System.err.println("[ThemeManager] CSS file not found: " + COMPONENTS_CSS);
            return;
        }

        managedScene.getStylesheets().add(themeResource.toExternalForm());
        managedScene.getStylesheets().add(componentsResource.toExternalForm());
    }

    /** Returns the currently active theme. */
    public Theme getCurrentTheme() {
        return currentTheme;
    }

    /** Records the theme without applying CSS  */
    public void preloadTheme(Theme theme) {
        currentTheme = theme;
    }

    public Theme fromStoredName(String name) {
        if (name == null) return Theme.NAVY_CYAN;
        try {
            return Theme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Theme.NAVY_CYAN;
        }
    }
}