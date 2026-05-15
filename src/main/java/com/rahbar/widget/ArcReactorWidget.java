package com.rahbar.widget;

import com.rahbar.ui.ThemeManager;
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

/** Always-on-top draggable transparent widget. Single-click: IDLE↔LISTENING; double-click: minimize toggle; right-click: context menu. */
public class ArcReactorWidget {

    // Singleton
    private static ArcReactorWidget instance;

    public static ArcReactorWidget getInstance() {
        if (instance == null) instance = new ArcReactorWidget();
        return instance;
    }

    // ── Constants
    private static final double WINDOW_SIZE    = 240.0;
    private static final double CANVAS_PADDING = 25.0;

    private static final long DOUBLE_CLICK_MS  = 350; // ms threshold for double-click

    // ── Core components
    private Stage                 widgetStage;
    private ArcReactorCanvas      canvas;
    private WidgetAnimationEngine animationEngine;
    private AnimationTimer        renderLoop;

    // ── Drag tracking
    private double  dragOffsetX;
    private double  dragOffsetY;
    private boolean wasDragged = false;

    // ── Double-click tracking
    private long lastClickTime = 0;

    // ── Minimized state
    private boolean isMinimized = false;

    // ── Callback hooks
    private Runnable onOpenDashboard;
    private Runnable onExitApp;

    // ── Package-private click listener (read by VoiceWakeWordListener) ────────
    WidgetClickListener clickListener;

    private ArcReactorWidget() {}

    // Public API

    /** Must be called from the JavaFX Application Thread. */
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

    //making the widget

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

    //dragging part

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

    // single vs double click

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

        MenuItem openDashboard = new MenuItem("⚙  Open Dashboard");
        MenuItem muteItem      = new MenuItem("🔇  Mute Assistant");
        MenuItem minimizeItem  = new MenuItem("⬡  Minimize Widget");
        MenuItem separator     = new SeparatorMenuItem();
        MenuItem exitItem      = new MenuItem("✕   Exit RAHBAR");

        final boolean[] muted = { false };
        muteItem.setOnAction(e -> {
            muted[0] = !muted[0];
            muteItem.setText(muted[0] ? "🔊  Unmute Assistant" : "🔇  Mute Assistant");
            if (clickListener != null) clickListener.onMuteToggled(muted[0]);
        });

        minimizeItem.setOnAction(e -> {
            if (isMinimized) {
                minimizeItem.setText("⬡  Minimize Widget");
                handleDoubleClick();
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

        root.setOnContextMenuRequested(event -> {
            applyContextMenuTheme(menu, openDashboard, muteItem, minimizeItem, exitItem);
            menu.show(widgetStage, event.getScreenX(), event.getScreenY());
        });
    }

    private void applyContextMenuTheme(ContextMenu menu, MenuItem... items) {
        String[] c = contextMenuColors(ThemeManager.getInstance().getCurrentTheme());
        menu.setStyle(
            "-fx-background-color: " + c[0] + ";" +
            "-fx-border-color: "     + c[1] + ";" +
            "-fx-border-width: 1px;"
        );
        String itemStyle =
            "-fx-text-fill: " + c[2] + ";" +
            "-fx-font-size: 13px;" +
            "-fx-padding: 6 14 6 14;";
        for (MenuItem item : items) item.setStyle(itemStyle);
    }

    /**
     * Returns [background, border/accent, text] hex colors for the context menu
     * matching each theme.
     */
    private String[] contextMenuColors(ThemeManager.Theme theme) {
        return switch (theme) {
            case BLACK_BLUE     -> new String[]{"#070714", "#3D7EFF", "#8AB4FF"};
            case CHARCOAL_GREEN -> new String[]{"#111811", "#39FF14", "#90FF90"};
            case PURPLE_GOLD    -> new String[]{"#14082A", "#FFD700", "#FFE57A"};
            case WHITE_BLACK    -> new String[]{"#F0F0F0", "#111111", "#333333"};
            default             -> new String[]{"#020135", "#00B4D8", "#90E0EF"}; // NAVY_CYAN
        };
    }

    private void startRenderLoop() {
        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                canvas.redraw();
            }
        };
        renderLoop.start();
    }


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


    public interface WidgetClickListener {
        void onWidgetClicked();
        void onWidgetCancelled();
        void onMuteToggled(boolean isMuted);
    }
}
