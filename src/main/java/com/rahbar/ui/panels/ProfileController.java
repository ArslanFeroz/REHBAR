package com.rahbar.ui.panels;

import com.rahbar.db.DatabaseManager;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * ProfileController
 *
 * Reads profile settings from SQLite on load, saves them on "Save Profile".
 * All settings are stored in the 'settings' table using string keys.
 *
 * Keys used:
 *   profile_first_name, profile_last_name, profile_nickname,
 *   assistant_name, pref_startup, pref_show_widget, pref_confirm_delete
 */
public class ProfileController implements Initializable {

    // ── Personal fields ───────────────────────────────────────────────────────
    @FXML private TextField fieldFirstName;
    @FXML private TextField fieldLastName;
    @FXML private TextField fieldNickname;
    @FXML private TextField fieldAssistantName;

    // ── Checkboxes ────────────────────────────────────────────────────────────
    @FXML private CheckBox checkStartup;
    @FXML private CheckBox checkShowWidget;
    @FXML private CheckBox checkConfirmDelete;

    // ── Feedback ──────────────────────────────────────────────────────────────
    @FXML private Label feedbackLabel;

    // ─────────────────────────────────────────────────────────────────────────
    // Initialize
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        loadProfile();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load from DB
    // ─────────────────────────────────────────────────────────────────────────

    private void loadProfile() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();

            setField(fieldFirstName,    db.getSetting("profile_first_name"), "");
            setField(fieldLastName,     db.getSetting("profile_last_name"),  "");
            setField(fieldNickname,     db.getSetting("profile_nickname"),   "");
            setField(fieldAssistantName,db.getSetting("assistant_name"),     "RAHBAR");

            checkStartup.setSelected(    "true".equals(db.getSetting("pref_startup")));
            checkShowWidget.setSelected( !"false".equals(db.getSetting("pref_show_widget"))); // default true
            checkConfirmDelete.setSelected(!"false".equals(db.getSetting("pref_confirm_delete"))); // default true

        } catch (Exception e) {
            System.err.println("[ProfileController] Could not load profile: " + e.getMessage());
        }
    }

    /** Sets a text field's value, using fallback if the saved value is null. */
    private void setField(TextField field, String value, String fallback) {
        field.setText(value != null && !value.isBlank() ? value : fallback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button handlers
    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    private void onSaveProfile() {
        String firstName = fieldFirstName.getText().trim();
        String lastName  = fieldLastName.getText().trim();
        String nickname  = fieldNickname.getText().trim();
        String assistantName = fieldAssistantName.getText().trim();

        // ── Validation ────────────────────────────────────────────────────────
        if (firstName.isEmpty()) {
            showFeedback("Please enter your first name.", false);
            fieldFirstName.requestFocus();
            return;
        }
        if (nickname.isEmpty()) {
            showFeedback("Please enter a name for RAHBAR to call you.", false);
            fieldNickname.requestFocus();
            return;
        }
        if (assistantName.isEmpty()) {
            showFeedback("Please enter a name for your assistant.", false);
            fieldAssistantName.requestFocus();
            return;
        }

        // ── Save to DB ────────────────────────────────────────────────────────
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            db.saveSetting("profile_first_name",  firstName);
            db.saveSetting("profile_last_name",   lastName);
            db.saveSetting("profile_nickname",    nickname);
            db.saveSetting("assistant_name",      assistantName);
            db.saveSetting("pref_startup",        String.valueOf(checkStartup.isSelected()));
            db.saveSetting("pref_show_widget",    String.valueOf(checkShowWidget.isSelected()));
            db.saveSetting("pref_confirm_delete", String.valueOf(checkConfirmDelete.isSelected()));

            showFeedback("✓  Profile saved successfully.", true);

        } catch (Exception e) {
            showFeedback("Error saving profile: " + e.getMessage(), false);
        }
    }

    @FXML
    private void onResetDefaults() {
        fieldFirstName.clear();
        fieldLastName.clear();
        fieldNickname.setText("");
        fieldAssistantName.setText("RAHBAR");
        checkStartup.setSelected(false);
        checkShowWidget.setSelected(true);
        checkConfirmDelete.setSelected(true);
        showFeedback("Fields reset. Press 'Save Profile' to apply.", true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feedback helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows a brief message below the save button, then hides it after 3 seconds.
     * @param message  the text to show
     * @param success  true = green, false = red
     */
    private void showFeedback(String message, boolean success) {
        feedbackLabel.setText(message);
        feedbackLabel.setStyle("-fx-text-fill: " +
            (success ? "-color-success" : "-color-error") + "; -fx-font-size: 12px;");
        feedbackLabel.setVisible(true);

        PauseTransition pause = new PauseTransition(Duration.seconds(3));
        pause.setOnFinished(e -> feedbackLabel.setVisible(false));
        pause.play();
    }
}
