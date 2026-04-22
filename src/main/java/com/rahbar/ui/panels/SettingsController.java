package com.rahbar.ui.panels;

import com.rahbar.db.DatabaseManager;
import com.rahbar.ui.ThemeManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import com.rahbar.widget.ArcReactorWidget;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * SettingsController
 *
 * Changes over original:
 *   - Added hotkey fields: checkHotkeyEnabled, checkHotkeyCtrl/Shift/Alt,
 *     fieldHotkeyKey, hotkeyPreviewLabel
 *   - loadSettings() and onSaveSettings() extended to handle hotkey keys
 *   - All original fx:id bindings and methods are unchanged
 *
 * Hotkey DB keys:
 *   hotkey_enabled  — "true" / "false"
 *   hotkey_ctrl     — "true" / "false"
 *   hotkey_shift    — "true" / "false"
 *   hotkey_alt      — "true" / "false"
 *   hotkey_key      — single character, e.g. "R"
 *
 * Backend team: read these keys in RahbarApp.java to register the
 * JNativeHook global hotkey. The stub is in RahbarApp.registerGlobalHotkey().
 */
public class SettingsController implements Initializable {

    // ── Voice input ───────────────────────────────────────────────────────────
    @FXML private Slider sliderSensitivity;
    @FXML private Label  sensitivityValue;
    @FXML private Slider sliderPause;
    @FXML private Label  pauseValue;

    // ── TTS ───────────────────────────────────────────────────────────────────
    @FXML private ComboBox<String> comboVoice;
    @FXML private Slider sliderRate;
    @FXML private Label  rateValue;
    @FXML private Slider sliderVolume;
    @FXML private Label  volumeValue;

    // ── AI toggles ────────────────────────────────────────────────────────────
    @FXML private CheckBox checkAiEnabled;
    @FXML private CheckBox checkSentimentEnabled;

    // ── Theme cards ───────────────────────────────────────────────────────────
    @FXML private VBox themeCardNavy;
    @FXML private VBox themeCardBlack;
    @FXML private VBox themeCardGreen;
    @FXML private VBox themeCardPurple;
    @FXML private VBox themeCardWhite;

    // ── Hotkey fields ─────────────────────────────────────────────────────────
    @FXML private CheckBox   checkHotkeyEnabled;
    @FXML private CheckBox   checkHotkeyCtrl;
    @FXML private CheckBox   checkHotkeyShift;
    @FXML private CheckBox   checkHotkeyAlt;
    @FXML private TextField  fieldHotkeyKey;
    @FXML private Label      hotkeyPreviewLabel;

    // ── Feedback ──────────────────────────────────────────────────────────────
    @FXML private Label settingsFeedback;

    // ── Internal ──────────────────────────────────────────────────────────────
    private ThemeManager.Theme selectedTheme = ThemeManager.Theme.NAVY_CYAN;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupSliderListeners();
        setupHotkeyListeners();
        loadVoices();
        loadSettings();
        highlightCurrentTheme(ThemeManager.getInstance().getCurrentTheme());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slider listeners (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void setupSliderListeners() {
        sliderSensitivity.valueProperty().addListener((obs, old, val) ->
                sensitivityValue.setText(String.valueOf(val.intValue())));
        sliderPause.valueProperty().addListener((obs, old, val) ->
                pauseValue.setText(String.format("%.1fs", val.doubleValue())));
        sliderRate.valueProperty().addListener((obs, old, val) ->
                rateValue.setText(val.intValue() + " wpm"));
        sliderVolume.valueProperty().addListener((obs, old, val) ->
                volumeValue.setText(val.intValue() + "%"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hotkey live preview
    // ─────────────────────────────────────────────────────────────────────────

    private void setupHotkeyListeners() {
        // Update the preview label whenever any modifier or key changes
        Runnable updatePreview = this::refreshHotkeyPreview;

        checkHotkeyCtrl .selectedProperty().addListener((o, ov, nv) -> updatePreview.run());
        checkHotkeyShift.selectedProperty().addListener((o, ov, nv) -> updatePreview.run());
        checkHotkeyAlt  .selectedProperty().addListener((o, ov, nv) -> updatePreview.run());

        // Restrict key field to single uppercase character only
        fieldHotkeyKey.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank()) return;
            // Keep only the last typed character, uppercase
            String cleaned = newVal.trim().toUpperCase();
            if (cleaned.length() > 1) cleaned = cleaned.substring(cleaned.length() - 1);
            // Only allow A-Z and 0-9
            if (!cleaned.matches("[A-Z0-9]")) cleaned = oldVal.toUpperCase();
            fieldHotkeyKey.setText(cleaned);
            updatePreview.run();
        });

        refreshHotkeyPreview(); // set initial state
    }

    private void refreshHotkeyPreview() {
        StringBuilder sb = new StringBuilder();
        if (checkHotkeyCtrl.isSelected())  sb.append("Ctrl + ");
        if (checkHotkeyAlt.isSelected())   sb.append("Alt + ");
        if (checkHotkeyShift.isSelected()) sb.append("Shift + ");
        String key = fieldHotkeyKey.getText().trim().toUpperCase();
        sb.append(key.isEmpty() ? "?" : key);
        hotkeyPreviewLabel.setText(sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Voices (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void loadVoices() {
        comboVoice.getItems().addAll(
                "Microsoft David (Male)",
                "Microsoft Zira (Female)",
                "Microsoft Mark (Male)"
        );
        try {
            String saved = DatabaseManager.getInstance().getSetting("voice_name");
            if (saved != null && comboVoice.getItems().contains(saved))
                comboVoice.setValue(saved);
            else
                comboVoice.setValue("Microsoft David (Male)");
        } catch (Exception e) {
            comboVoice.setValue("Microsoft David (Male)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load settings (extended with hotkey keys)
    // ─────────────────────────────────────────────────────────────────────────

    private void loadSettings() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();

            setSlider(sliderSensitivity, db.getSetting("voice_sensitivity"), 300);
            setSlider(sliderPause,       db.getSetting("voice_pause"),        0.8);
            setSlider(sliderRate,        db.getSetting("voice_speed"),        175);
            setSlider(sliderVolume,      db.getSetting("voice_volume"),       90);

            checkAiEnabled.setSelected(!"false".equals(db.getSetting("ai_enabled")));
            checkSentimentEnabled.setSelected(!"false".equals(db.getSetting("sentiment_enabled")));

            // ── Hotkey settings ───────────────────────────────────────────────
            checkHotkeyEnabled.setSelected(!"false".equals(db.getSetting("hotkey_enabled")));
            checkHotkeyCtrl.setSelected(!"false".equals(db.getSetting("hotkey_ctrl")));
            checkHotkeyShift.setSelected(!"false".equals(db.getSetting("hotkey_shift")));
            checkHotkeyAlt.setSelected("true".equals(db.getSetting("hotkey_alt")));  // default off

            String savedKey = db.getSetting("hotkey_key");
            fieldHotkeyKey.setText(savedKey != null && !savedKey.isBlank()
                    ? savedKey.toUpperCase() : "R");
            refreshHotkeyPreview();

            // Theme
            String savedTheme = db.getSetting("theme");
            if (savedTheme != null) {
                selectedTheme = ThemeManager.getInstance().fromStoredName(savedTheme);
                highlightCurrentTheme(selectedTheme);
            }

        } catch (Exception e) {
            System.err.println("[SettingsController] Could not load settings: " + e.getMessage());
        }
    }

    private void setSlider(Slider slider, String val, double fallback) {
        try { slider.setValue(val != null ? Double.parseDouble(val) : fallback); }
        catch (NumberFormatException e) { slider.setValue(fallback); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Theme cards (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @FXML private void onSelectThemeNavyCyan()      { applyTheme(ThemeManager.Theme.NAVY_CYAN); }
    @FXML private void onSelectThemeBlackBlue()     { applyTheme(ThemeManager.Theme.BLACK_BLUE); }
    @FXML private void onSelectThemeCharcoalGreen() { applyTheme(ThemeManager.Theme.CHARCOAL_GREEN); }
    @FXML private void onSelectThemePurpleGold()    { applyTheme(ThemeManager.Theme.PURPLE_GOLD); }
    @FXML private void onSelectThemeWhiteBlack()    { applyTheme(ThemeManager.Theme.WHITE_BLACK); }

    private void applyTheme(ThemeManager.Theme theme) {
        selectedTheme = theme;
        ThemeManager.getInstance().applyTheme(theme);
        highlightCurrentTheme(theme);
        ArcReactorWidget.getInstance().setThemeColor(themeToWidgetColor(theme));
        try {
            DatabaseManager.getInstance().saveSetting("theme", theme.name());
        } catch (Exception e) {
            System.err.println("[SettingsController] Could not save theme: " + e.getMessage());
        }
    }

    private javafx.scene.paint.Color themeToWidgetColor(ThemeManager.Theme theme) {
        return switch (theme) {
            case BLACK_BLUE      -> javafx.scene.paint.Color.web("#3D7EFF");
            case CHARCOAL_GREEN  -> javafx.scene.paint.Color.web("#39FF14");
            case PURPLE_GOLD     -> javafx.scene.paint.Color.web("#FFD700");
            case WHITE_BLACK     -> javafx.scene.paint.Color.web("#00B4D8"); // stays Navy+Cyan
            default              -> javafx.scene.paint.Color.web("#00B4D8");
        };
    }

    private void highlightCurrentTheme(ThemeManager.Theme theme) {
        List<VBox> cards  = List.of(themeCardNavy, themeCardBlack, themeCardGreen, themeCardPurple, themeCardWhite);
        List<ThemeManager.Theme> themes = List.of(
                ThemeManager.Theme.NAVY_CYAN, ThemeManager.Theme.BLACK_BLUE,
                ThemeManager.Theme.CHARCOAL_GREEN, ThemeManager.Theme.PURPLE_GOLD,
                ThemeManager.Theme.WHITE_BLACK);
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).getStyleClass().remove("theme-card-active");
            if (themes.get(i) == theme) cards.get(i).getStyleClass().add("theme-card-active");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Voice preview (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    private void onPreviewVoice() {
        // ── BACKEND TEAM: call TTS preview here ───────────────────────────────
        System.out.println("[SettingsController] Voice preview — voice: " + comboVoice.getValue());
        showFeedback("▶  Playing preview...", true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save settings (extended with hotkey keys)
    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    private void onSaveSettings() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();

            // Original settings
            db.saveSetting("voice_sensitivity", String.valueOf((int) sliderSensitivity.getValue()));
            db.saveSetting("voice_pause",       String.format("%.1f", sliderPause.getValue()));
            db.saveSetting("voice_speed",       String.valueOf((int) sliderRate.getValue()));
            db.saveSetting("voice_volume",      String.valueOf((int) sliderVolume.getValue()));
            db.saveSetting("voice_name",        comboVoice.getValue());
            db.saveSetting("ai_enabled",        String.valueOf(checkAiEnabled.isSelected()));
            db.saveSetting("sentiment_enabled", String.valueOf(checkSentimentEnabled.isSelected()));
            db.saveSetting("theme",             selectedTheme.name());

            // ── Hotkey settings ───────────────────────────────────────────────
            db.saveSetting("hotkey_enabled", String.valueOf(checkHotkeyEnabled.isSelected()));
            db.saveSetting("hotkey_ctrl",    String.valueOf(checkHotkeyCtrl.isSelected()));
            db.saveSetting("hotkey_shift",   String.valueOf(checkHotkeyShift.isSelected()));
            db.saveSetting("hotkey_alt",     String.valueOf(checkHotkeyAlt.isSelected()));
            db.saveSetting("hotkey_key",     fieldHotkeyKey.getText().trim().toUpperCase());

            showFeedback("✓  Settings saved. Restart to apply hotkey changes.", true);

            // Notify Python bridge to reload voice/TTS settings immediately
            com.rahbar.RahbarApp.notifySettingsChanged();

        } catch (Exception e) {
            showFeedback("Error saving settings: " + e.getMessage(), false);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feedback (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private void showFeedback(String message, boolean success) {
        settingsFeedback.setText(message);
        settingsFeedback.setStyle("-fx-text-fill: " +
                (success ? "-color-success" : "-color-error") + "; -fx-font-size: 12px;");
        settingsFeedback.setVisible(true);
        PauseTransition p = new PauseTransition(Duration.seconds(3));
        p.setOnFinished(e -> settingsFeedback.setVisible(false));
        p.play();
    }
}