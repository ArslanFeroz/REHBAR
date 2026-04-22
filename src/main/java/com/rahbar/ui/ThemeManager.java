package com.rahbar.ui;

import javafx.scene.Scene;

import java.util.HashMap;
import java.util.Map;

/**
 * ThemeManager
 *
 * Manages the 4 selectable color themes for the RAHBAR desktop application.
 * Themes are applied by swapping a single CSS stylesheet on the root Scene.
 * All component stylesheets use CSS variables (defined per-theme) so every
 * panel updates automatically when the theme changes — no component needs
 * to know which theme is active.
 *
 * Available themes:
 *   NAVY_CYAN     — Deep navy + cyan       (Jarvis / Iron Man classic)
 *   BLACK_BLUE    — Pure black + electric blue  (dark sci-fi)
 *   CHARCOAL_GREEN— Dark charcoal + neon green  (terminal hacker)
 *   PURPLE_GOLD   — Deep purple + gold     (premium / luxury)
 *
 * Usage:
 *   ThemeManager.getInstance().applyTheme(scene, Theme.NAVY_CYAN);
 *   ThemeManager.getInstance().getCurrentTheme();
 *
 * The current theme is persisted to SQLite via DatabaseManager.
 * On startup, DatabaseManager.getSetting("theme") is read and restored.
 */
public class ThemeManager {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static ThemeManager instance;

    public static ThemeManager getInstance() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }

    // ── Theme enum ────────────────────────────────────────────────────────────

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

    // ── CSS resource paths (files in src/main/resources/css/) ─────────────────
    // Each theme file defines the same set of CSS variables with different values.
    // Component stylesheets reference only the variables, never raw colors.
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

    // ── State ─────────────────────────────────────────────────────────────────
    private Theme   currentTheme = Theme.NAVY_CYAN;   // default
    private Scene   managedScene = null;

    // ── Apply theme ───────────────────────────────────────────────────────────

    /**
     * Attaches this ThemeManager to a Scene and applies the given theme.
     * Call this once in MainWindow after the Scene is created.
     * After this, call applyTheme(theme) with just the theme to switch.
     */
    public void init(Scene scene, Theme theme) {
        this.managedScene = scene;
        applyTheme(theme);
    }

    /**
     * Switches to the given theme on the managed scene.
     * Safe to call from the JavaFX Application Thread only.
     */
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

    /**
     * Converts a theme name string (as stored in SQLite) back to a Theme enum value.
     * Falls back to NAVY_CYAN if the stored value is unrecognised.
     */
    public Theme fromStoredName(String name) {
        if (name == null) return Theme.NAVY_CYAN;
        try {
            return Theme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Theme.NAVY_CYAN;
        }
    }
}