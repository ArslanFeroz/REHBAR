package com.rahbar.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * CommandPreviewPopup
 * ══════════════════════════════════════════════════════════════════════════
 * FEATURE: MINI COMMAND PREVIEW POPUP
 * Easy to disable: to remove this feature, simply delete this file and
 * remove the two calls to CommandPreviewPopup.show() from wherever the
 * backend invokes it (search for "CommandPreviewPopup.show" in the project).
 * No other files depend on this class.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Shows a small floating card near the Arc Reactor widget for 2 seconds
 * when a voice command is received and classified.
 *
 * Usage (call from backend when intent is classified):
 *
 *   // Show what RAHBAR heard and what it's doing
 *   CommandPreviewPopup.show("open chrome", "Opening Chrome...");
 *
 *   // Or with just a status message
 *   CommandPreviewPopup.show(null, "Searching for Python tutorials...");
 *
 * The popup:
 *   - Appears near the widget's current position (offset up by ~80px)
 *   - Fades in over 150ms, holds for 1.8s, fades out over 300ms
 *   - Auto-dismisses — no user interaction needed
 *   - Thread-safe: can be called from any thread
 *   - Only one popup shows at a time (calling show() replaces any current popup)
 */
public class CommandPreviewPopup {

    private static Stage currentPopup = null;

    /**
     * Shows the command preview popup near the widget.
     *
     * @param heardText   What RAHBAR heard (nullable — omitted if null/blank)
     * @param actionText  What RAHBAR is doing e.g. "Opening Chrome..."
     */
    public static void show(String heardText, String actionText) {
        Platform.runLater(() -> {
            // Dismiss any existing popup immediately
            dismissCurrent();

            Stage popup = buildPopup(heardText, actionText);
            currentPopup = popup;

            // Position near the widget
            positionNearWidget(popup);

            popup.show();

            // Animate: fade in → hold → fade out → close
            FadeTransition fadeIn = new FadeTransition(Duration.millis(150), popup.getScene().getRoot());
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);

            PauseTransition hold = new PauseTransition(Duration.millis(1800));

            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), popup.getScene().getRoot());
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(e -> {
                popup.close();
                if (currentPopup == popup) currentPopup = null;
            });

            new SequentialTransition(fadeIn, hold, fadeOut).play();
        });
    }

    /** Dismisses the currently showing popup immediately (if any). */
    public static void dismiss() {
        Platform.runLater(CommandPreviewPopup::dismissCurrent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private static void dismissCurrent() {
        if (currentPopup != null && currentPopup.isShowing()) {
            currentPopup.close();
        }
        currentPopup = null;
    }

    private static Stage buildPopup(String heardText, String actionText) {
        // ── Layout ────────────────────────────────────────────────────────────
        VBox root = new VBox(6);
        root.setPadding(new Insets(12, 16, 12, 16));
        root.setStyle(
            "-fx-background-color: #020135;" +
            "-fx-border-color: #00B4D8;" +
            "-fx-border-width: 1px;" +
            "-fx-border-radius: 0;" +
            "-fx-background-radius: 0;"
        );
        root.setOpacity(0); // starts invisible; FadeTransition brings it in

        // "Heard" row — only shown if heardText is present
        if (heardText != null && !heardText.isBlank()) {
            HBox heardRow = new HBox(8);
            heardRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label heardIcon = new Label("◉");
            heardIcon.setStyle(
                "-fx-text-fill: #4A7A8A; -fx-font-size: 9px; -fx-font-family: monospace;");

            Label heardLabel = new Label("\"" + heardText + "\"");
            heardLabel.setStyle(
                "-fx-text-fill: #90E0EF; -fx-font-size: 11px;" +
                "-fx-font-family: 'Consolas', 'Courier New', monospace;");

            heardRow.getChildren().addAll(heardIcon, heardLabel);
            root.getChildren().add(heardRow);
        }

        // Action row — always shown
        if (actionText != null && !actionText.isBlank()) {
            HBox actionRow = new HBox(8);
            actionRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label actionIcon = new Label("▶");
            actionIcon.setStyle(
                "-fx-text-fill: #00B4D8; -fx-font-size: 9px; -fx-font-family: monospace;");

            Label actionLabel = new Label(actionText);
            actionLabel.setStyle(
                "-fx-text-fill: #E8F4F8; -fx-font-size: 12px; -fx-font-weight: bold;" +
                "-fx-font-family: 'Consolas', 'Courier New', monospace;");

            actionRow.getChildren().addAll(actionIcon, actionLabel);
            root.getChildren().add(actionRow);
        }

        // ── Scene & Stage ─────────────────────────────────────────────────────
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setScene(scene);

        // Let JavaFX size it to content
        stage.sizeToScene();

        return stage;
    }

    /**
     * Positions the popup just above the widget.
     * Reads the widget's current screen position and offsets upward.
     */
    private static void positionNearWidget(Stage popup) {
        try {
            com.rahbar.widget.ArcReactorWidget widget =
                com.rahbar.widget.ArcReactorWidget.getInstance();
            double widgetX = widget.getPositionX();
            double widgetY = widget.getPositionY();
            double widgetSize = 240.0; // ArcReactorWidget.WINDOW_SIZE

            // Position: horizontally centered above widget, 90px up
            popup.setX(widgetX + (widgetSize / 2.0) - 100);
            popup.setY(widgetY - 90);
        } catch (Exception e) {
            // Widget not available — fall back to center screen
            javafx.geometry.Rectangle2D screen =
                javafx.stage.Screen.getPrimary().getVisualBounds();
            popup.setX(screen.getWidth()  / 2.0 - 100);
            popup.setY(screen.getHeight() / 2.0 - 80);
        }
    }
}
