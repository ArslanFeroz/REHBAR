package com.rahbar.widget;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * ArcReactorWidget
 *
 * The always-on-top, draggable, transparent desktop widget.
 *
 * Interaction:
 *   Left single-click   — IDLE → LISTENING (start listening) or cancel if active
 *   Left double-click   — toggle MINIMIZED ↔ IDLE
 *   Right-click         — context menu (Dashboard / Mute / Exit)
 *
 * Backend team:
 *   widget.setState(WidgetState.X)  — thread-safe, call from anywhere
 *
 * Widget window: 240×240 px transparent.
 * Canvas inside: 190×190, centered with 25px padding for glow overflow.
 */
public class ArcReactorWidget {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static ArcReactorWidget instance;

    public static ArcReactorWidget getInstance() {
        if (instance == null) instance = new ArcReactorWidget();
        return instance;
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final double WINDOW_SIZE    = 240.0;
    private static final double CANVAS_PADDING = 25.0;

    // Double-click threshold in milliseconds
    private static final long DOUBLE_CLICK_MS  = 350;

    // ── Core components ───────────────────────────────────────────────────────
    private Stage                 widgetStage;
    private ArcReactorCanvas      canvas;
    private WidgetAnimationEngine animationEngine;
    private AnimationTimer        renderLoop;

    // ── Drag tracking ─────────────────────────────────────────────────────────
    private double  dragOffsetX;
    private double  dragOffsetY;
    private boolean wasDragged = false;

    // ── Double-click tracking ─────────────────────────────────────────────────
    private long lastClickTime = 0;

    // ── Minimized state ───────────────────────────────────────────────────────
    private boolean isMinimized = false;

    // ── Callback hooks ────────────────────────────────────────────────────────
    private Runnable onOpenDashboard;
    private Runnable onExitApp;

    // ── Package-private click listener (read by VoiceWakeWordListener) ────────
    WidgetClickListener clickListener;

    private ArcReactorWidget() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initializes and shows the widget.
     * Must be called from the JavaFX Application Thread.
     */
    public void show(Runnable onOpenDashboard, Runnable onExitApp) {
        this.onOpenDashboard = onOpenDashboard;
        this.onExitApp       = onExitApp;

        buildWidget();
        widgetStage.show();
        animationEngine.start();
        startRenderLoop();
    }

    public void hide()   { Platform.runLater(() -> widgetStage.hide()); }
    public void reveal() { Platform.runLater(() -> widgetStage.show()); }

    /**
     * Sets the widget state. Thread-safe — can be called from any thread.
     * ERROR automatically resets to IDLE after 2.5 seconds via PauseTransition.
     */
    public void setState(WidgetState state) {
        animationEngine.setState(state);

        if (state == WidgetState.ERROR) {
            scheduleErrorReset();
        }

        // Sync the minimized flag
        if (state == WidgetState.MINIMIZED) {
            isMinimized = true;
        } else if (state != WidgetState.ERROR) {
            // Don't clear isMinimized on ERROR — preserve intended state
        }
    }

    public WidgetState getState() {
        return animationEngine.getCurrentState();
    }

    /**
     * Updates the accent color to match the selected app theme.
     * Thread-safe — color is applied on the next render frame.
     */
    public void setThemeColor(Color color) {
        if (canvas != null) canvas.setThemeColor(color);
    }

    public void setClickListener(WidgetClickListener listener) {
        this.clickListener = listener;
    }

    public double getPositionX() { return widgetStage.getX(); }
    public double getPositionY() { return widgetStage.getY(); }

    public void setPosition(double x, double y) {
        Platform.runLater(() -> {
            widgetStage.setX(x);
            widgetStage.setY(y);
        });
    }

    public void dispose() {
        if (renderLoop      != null) renderLoop.stop();
        if (animationEngine != null) animationEngine.stop();
        if (widgetStage     != null) widgetStage.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Widget construction
    // ─────────────────────────────────────────────────────────────────────────

    private void buildWidget() {
        animationEngine = new WidgetAnimationEngine();
        canvas          = new ArcReactorCanvas(animationEngine);

        StackPane root = new StackPane(canvas);
        root.setPadding(new Insets(CANVAS_PADDING));
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, WINDOW_SIZE, WINDOW_SIZE);
        scene.setFill(Color.TRANSPARENT);

        widgetStage = new Stage();
        widgetStage.initStyle(StageStyle.TRANSPARENT);
        widgetStage.setAlwaysOnTop(true);
        widgetStage.setResizable(false);
        widgetStage.setTitle("RAHBAR");
        widgetStage.setScene(scene);

        centerOnScreen();
        attachDragHandlers(root);
        attachClickHandlers(root);
        attachContextMenu(root);
    }

    private void centerOnScreen() {
        javafx.geometry.Rectangle2D screen =
            javafx.stage.Screen.getPrimary().getVisualBounds();
        widgetStage.setX((screen.getWidth()  - WINDOW_SIZE) / 2.0);
        widgetStage.setY((screen.getHeight() - WINDOW_SIZE) / 2.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag
    // ─────────────────────────────────────────────────────────────────────────

    private void attachDragHandlers(StackPane root) {
        root.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                dragOffsetX = event.getScreenX() - widgetStage.getX();
                dragOffsetY = event.getScreenY() - widgetStage.getY();
                wasDragged  = false;
            }
        });

        root.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                double newX = event.getScreenX() - dragOffsetX;
                double newY = event.getScreenY() - dragOffsetY;
                // Only count as a drag if actually moved more than 4px
                if (Math.abs(newX - widgetStage.getX()) > 4 ||
                    Math.abs(newY - widgetStage.getY()) > 4) {
                    wasDragged = true;
                }
                widgetStage.setX(newX);
                widgetStage.setY(newY);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click — single vs double click
    // ─────────────────────────────────────────────────────────────────────────

    private void attachClickHandlers(StackPane root) {
        root.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (wasDragged) return; // ignore drag-ends

            long now  = System.currentTimeMillis();
            long diff = now - lastClickTime;

            if (diff < DOUBLE_CLICK_MS) {
                // ── Double-click: toggle minimize ─────────────────────────────
                lastClickTime = 0; // reset so a 3rd click doesn't re-trigger
                handleDoubleClick();
            } else {
                // ── Single click: handle normally ─────────────────────────────
                lastClickTime = now;
                // Small delay to distinguish from double-click
                // We use a simple approach: handle single-click immediately
                // and double-click cancels its effect.
                handleSingleClick();
            }
        });
    }

    private void handleDoubleClick() {
        if (isMinimized) {
            // Restore
            isMinimized = false;
            animationEngine.setState(WidgetState.IDLE);
        } else {
            // Minimize
            isMinimized = true;
            animationEngine.setState(WidgetState.MINIMIZED);
        }
    }

    private void handleSingleClick() {
        WidgetState current = animationEngine.getCurrentState();

        if (current == WidgetState.MINIMIZED) {
            // Single click on minimized widget: restore it
            isMinimized = false;
            animationEngine.setState(WidgetState.IDLE);
            return;
        }

        switch (current) {
            case IDLE -> {
                animationEngine.setState(WidgetState.LISTENING);
                if (clickListener != null) clickListener.onWidgetClicked();
            }
            case LISTENING, PROCESSING, SPEAKING -> {
                animationEngine.setState(WidgetState.IDLE);
                if (clickListener != null) clickListener.onWidgetCancelled();
            }
            case ERROR -> animationEngine.setState(WidgetState.IDLE);
            default    -> {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context menu
    // ─────────────────────────────────────────────────────────────────────────

    private void attachContextMenu(StackPane root) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("""
            -fx-background-color: #020135;
            -fx-border-color: #00B4D8;
            -fx-border-width: 1px;
        """);

        MenuItem openDashboard = styledMenuItem("⚙  Open Dashboard");
        MenuItem muteItem      = styledMenuItem("🔇  Mute Assistant");
        MenuItem minimizeItem  = styledMenuItem("⬡  Minimize Widget");
        MenuItem separator     = new SeparatorMenuItem();
        MenuItem exitItem      = styledMenuItem("✕   Exit RAHBAR");

        final boolean[] muted = { false };
        muteItem.setOnAction(e -> {
            muted[0] = !muted[0];
            muteItem.setText(muted[0] ? "🔊  Unmute Assistant" : "🔇  Mute Assistant");
            if (clickListener != null) clickListener.onMuteToggled(muted[0]);
        });

        minimizeItem.setOnAction(e -> {
            if (isMinimized) {
                minimizeItem.setText("⬡  Minimize Widget");
                handleDoubleClick(); // reuse same toggle logic
            } else {
                minimizeItem.setText("⬡  Restore Widget");
                handleDoubleClick();
            }
        });

        openDashboard.setOnAction(e -> {
            if (onOpenDashboard != null) onOpenDashboard.run();
        });

        exitItem.setOnAction(e -> {
            if (onExitApp != null) onExitApp.run();
        });

        menu.getItems().addAll(openDashboard, muteItem, minimizeItem, separator, exitItem);

        root.setOnContextMenuRequested(event ->
            menu.show(widgetStage, event.getScreenX(), event.getScreenY())
        );
    }

    private MenuItem styledMenuItem(String text) {
        MenuItem item = new MenuItem(text);
        item.setStyle("""
            -fx-text-fill: #90E0EF;
            -fx-font-size: 13px;
            -fx-padding: 6 14 6 14;
        """);
        return item;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render loop
    // ─────────────────────────────────────────────────────────────────────────

    private void startRenderLoop() {
        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                canvas.redraw();
            }
        };
        renderLoop.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error auto-reset via PauseTransition (JavaFX-native, no raw threads)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedules an automatic reset from ERROR → IDLE after 2.5 seconds.
     * Uses PauseTransition — runs on the JavaFX Application Thread cleanly.
     * If the user manually dismisses the error first, the timer fires but
     * does nothing (state check guards against double-reset).
     */
    private void scheduleErrorReset() {
        Platform.runLater(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(2500));
            pause.setOnFinished(e -> {
                if (animationEngine.getCurrentState() == WidgetState.ERROR) {
                    animationEngine.setState(WidgetState.IDLE);
                }
            });
            pause.play();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click listener interface
    // ─────────────────────────────────────────────────────────────────────────

    public interface WidgetClickListener {
        void onWidgetClicked();
        void onWidgetCancelled();
        void onMuteToggled(boolean isMuted);
    }
}
