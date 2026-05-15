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

/** Profile controller — loads/saves name, assistant name, show-widget and confirm-delete prefs. */
public class ProfileController implements Initializable {

    // ── Personal fields ───────────────────────────────────────────────────────
    @FXML private TextField fieldFirstName;
    @FXML private TextField fieldLastName;
    @FXML private TextField fieldNickname;
    @FXML private TextField fieldAssistantName;

    // ── Checkboxes ────────────────────────────────────────────────────────────
    @FXML private CheckBox checkShowWidget;
    @FXML private CheckBox checkConfirmDelete;

    // ── Feedback ──────────────────────────────────────────────────────────────
    @FXML private Label feedbackLabel;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        loadProfile();
    }

    // ── Load from DB ──────────────────────────────────────────────────────────

    private void loadProfile() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();

            setField(fieldFirstName,     db.getSetting("profile_first_name"), "");
            setField(fieldLastName,      db.getSetting("profile_last_name"),  "");
            setField(fieldNickname,      db.getSetting("profile_nickname"),   "");
            setField(fieldAssistantName, db.getSetting("assistant_name"),     "RAHBAR");

            checkShowWidget.setSelected(!"false".equals(db.getSetting("pref_show_widget")));   // default true
            checkConfirmDelete.setSelected(!"false".equals(db.getSetting("pref_confirm_delete"))); // default true

        } catch (Exception e) {
            System.err.println("[ProfileController] Could not load profile: " + e.getMessage());
        }
    }

    private void setField(TextField field, String value, String fallback) {
        field.setText(value != null && !value.isBlank() ? value : fallback);
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    @FXML
    private void onSaveProfile() {
        String firstName     = fieldFirstName.getText().trim();
        String lastName      = fieldLastName.getText().trim();
        String nickname      = fieldNickname.getText().trim();
        String assistantName = fieldAssistantName.getText().trim();

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

        try {
            DatabaseManager db = DatabaseManager.getInstance();
            db.saveSetting("profile_first_name",  firstName);
            db.saveSetting("profile_last_name",   lastName);
            db.saveSetting("profile_nickname",    nickname);
            db.saveSetting("assistant_name",      assistantName);
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
        checkShowWidget.setSelected(true);
        checkConfirmDelete.setSelected(true);
        showFeedback("Fields reset. Press 'Save Profile' to apply.", true);
    }

    // ── Feedback ──────────────────────────────────────────────────────────────

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
