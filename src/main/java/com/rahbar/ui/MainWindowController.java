package com.rahbar.ui;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * MainWindowController  —  Terminal Brutalist layout
 *
 * Features added over original:
 *   - Collapsible sidebar rail (52px collapsed / 220px expanded)
 *   - Geometric Re logo on Canvas
 *   - Live digital clock in the top bar center (ticks every second)
 *   - 150ms FadeTransition on every panel switch
 */
public class MainWindowController implements Initializable {

    private static final double SIDEBAR_COLLAPSED = 52.0;
    private static final double SIDEBAR_EXPANDED  = 220.0;

    // ── FXML nodes ────────────────────────────────────────────────────────────
    @FXML private BorderPane rootPane;
    @FXML private HBox       topBar;
    @FXML private Label      topBarPageName;
    @FXML private Label      topBarClock;        // live clock — injected from FXML
    @FXML private Label      statusDot;
    @FXML private Label      statusLabel;
    @FXML private StackPane  contentArea;

    @FXML private VBox   sidebar;
    @FXML private VBox   logoBlock;
    @FXML private Canvas logoCanvas;
    @FXML private VBox   logoTextBlock;
    @FXML private VBox   versionBlock;
    @FXML private Button btnToggleSidebar;
    @FXML private Label  labelMain;
    @FXML private Label  labelSystem;
    @FXML private Label  lblDashboard;
    @FXML private Label  lblProfile;
    @FXML private Label  lblHistory;
    @FXML private Label  lblSettings;
    @FXML private Label  lblRegistry;
    @FXML private Button btnDashboard;
    @FXML private Button btnProfile;
    @FXML private Button btnHistory;
    @FXML private Button btnSettings;
    @FXML private Button btnRegistry;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean sidebarExpanded = false;
    private double  dragOffsetX, dragOffsetY;
    private final Map<String, Parent> panelCache = new HashMap<>();
    private String activePanelId = "";
    private Stage  stage;

    private static final DateTimeFormatter CLOCK_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String PANEL_DASHBOARD = "dashboard";
    private static final String PANEL_PROFILE   = "profile";
    private static final String PANEL_HISTORY   = "history";
    private static final String PANEL_SETTINGS  = "settings";
    private static final String PANEL_REGISTRY  = "registry";

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        applySidebarState(false);
        Platform.runLater(this::drawLogoCanvas);
        setupWindowDrag();
        startClock();
        navigateTo(PANEL_DASHBOARD, btnDashboard, "dashboard");
    }

    public void setStage(Stage stage) { this.stage = stage; }

    // ─────────────────────────────────────────────────────────────────────────
    // Live clock — ticks every second via JavaFX Timeline (runs on FX thread)
    // ─────────────────────────────────────────────────────────────────────────

    private void startClock() {
        topBarClock.setText(LocalTime.now().format(CLOCK_FMT));
        Timeline clock = new Timeline(
                new KeyFrame(Duration.seconds(1),
                        e -> topBarClock.setText(LocalTime.now().format(CLOCK_FMT)))
        );
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geometric Re logo
    // ─────────────────────────────────────────────────────────────────────────

    private void drawLogoCanvas() { drawLogo(logoCanvas, 32, 32); }

    public static void drawLogo(Canvas canvas, double w, double h) {
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        double cx = w / 2.0, cy = h / 2.0;
        Color accent = logoAccentColor;
        Color dim    = accent.deriveColor(0, 1, 0.5, 0.6);

        gc.setStroke(dim);
        gc.setLineWidth(0.8);
        gc.strokeOval(2, 2, w - 4, h - 4);

        gc.setStroke(accent);
        gc.setLineWidth(2.2);
        double arcCX = cx + 2, arcCY = cy - 1, arcR = w * 0.27;
        gc.strokeArc(arcCX - arcR, arcCY - arcR, arcR * 2, arcR * 2,
                20, 200, javafx.scene.shape.ArcType.OPEN);

        double tx = arcCX + Math.cos(Math.toRadians(220)) * arcR;
        double ty = arcCY - Math.sin(Math.toRadians(220)) * arcR;
        gc.setLineWidth(2.0);
        gc.strokeLine(tx, ty, tx - 3.5, ty + 5.5);

        gc.setFill(accent);
        gc.fillOval(cx + 3, cy - arcR - 3, 2.5, 2.5);
    }

    private static Color logoAccentColor = Color.color(0.0, 0.706, 0.847, 1.0);

    public void setLogoAccentColor(Color color) {
        logoAccentColor = color;
        Platform.runLater(this::drawLogoCanvas);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sidebar toggle
    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    private void onToggleSidebar() {
        sidebarExpanded = !sidebarExpanded;
        applySidebarState(sidebarExpanded);
    }

    private void applySidebarState(boolean expanded) {
        double w = expanded ? SIDEBAR_EXPANDED : SIDEBAR_COLLAPSED;
        sidebar.setMinWidth(w); sidebar.setMaxWidth(w); sidebar.setPrefWidth(w);
        btnToggleSidebar.setText(expanded ? "⟨" : "⟩");
        setNodeVisible(logoTextBlock, expanded);
        setNodeVisible(versionBlock,  expanded);
        setNodeVisible(labelMain,     expanded);
        setNodeVisible(labelSystem,   expanded);
        setNodeVisible(lblDashboard,  expanded);
        setNodeVisible(lblProfile,    expanded);
        setNodeVisible(lblHistory,    expanded);
        setNodeVisible(lblSettings,   expanded);
        setNodeVisible(lblRegistry,   expanded);
        logoBlock.setAlignment(expanded
                ? javafx.geometry.Pos.CENTER_LEFT
                : javafx.geometry.Pos.CENTER);
    }

    private void setNodeVisible(javafx.scene.Node n, boolean v) {
        n.setVisible(v); n.setManaged(v);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window drag
    // ─────────────────────────────────────────────────────────────────────────

    private void setupWindowDrag() {
        topBar.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                dragOffsetX = e.getScreenX() - (stage != null ? stage.getX() : 0);
                dragOffsetY = e.getScreenY() - (stage != null ? stage.getY() : 0);
            }
        });
        topBar.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && stage != null) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });
    }

    @FXML private void onMinimize() { if (stage != null) stage.setIconified(true); }
    @FXML private void onClose()    { if (stage != null) stage.hide(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation with 150ms fade-in
    // ─────────────────────────────────────────────────────────────────────────

    @FXML private void showDashboard() { navigateTo(PANEL_DASHBOARD, btnDashboard, "dashboard"); }
    @FXML private void showProfile()   { navigateTo(PANEL_PROFILE,   btnProfile,   "profile"); }
    @FXML private void showHistory()   { navigateTo(PANEL_HISTORY,   btnHistory,   "command-history"); }
    @FXML private void showSettings()  { navigateTo(PANEL_SETTINGS,  btnSettings,  "settings"); }
    @FXML private void showRegistry()  { navigateTo(PANEL_REGISTRY,  btnRegistry,  "registry"); }

    private void navigateTo(String panelId, Button activeBtn, String pageLabel) {
        if (panelId.equals(activePanelId)) return;
        activePanelId = panelId;
        topBarPageName.setText(pageLabel);
        updateNavHighlight(activeBtn);

        Parent panel = getOrLoadPanel(panelId);
        if (panel == null) return;

        contentArea.getChildren().forEach(n -> n.setVisible(false));
        if (!contentArea.getChildren().contains(panel)) contentArea.getChildren().add(panel);

        // 150ms fade-in
        panel.setOpacity(0);
        panel.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(150), panel);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    private Parent getOrLoadPanel(String panelId) {
        if (panelCache.containsKey(panelId)) return panelCache.get(panelId);
        try {
            URL res = getClass().getResource("/fxml/panels/" + panelId + ".fxml");
            if (res == null) { System.err.println("[MWC] Not found: " + panelId); return null; }
            FXMLLoader loader = new FXMLLoader(res);
            Parent panel = loader.load();
            panel.setUserData(loader.getController());
            panelCache.put(panelId, panel);
            if (contentArea.getScene() != null)
                contentArea.getScene().getRoot().setUserData(this);
            return panel;
        } catch (IOException e) {
            System.err.println("[MWC] Failed to load: " + panelId); e.printStackTrace(); return null;
        }
    }

    private void updateNavHighlight(Button active) {
        for (Button b : new Button[]{btnDashboard,btnProfile,btnHistory,btnSettings,btnRegistry})
            b.getStyleClass().remove("nav-btn-active");
        if (!active.getStyleClass().contains("nav-btn-active"))
            active.getStyleClass().add("nav-btn-active");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void setStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusDot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 9px;");
        });
    }

    public void openPanel(String panelId) {
        Platform.runLater(() -> {
            switch (panelId) {
                case PANEL_DASHBOARD -> showDashboard();
                case PANEL_PROFILE   -> showProfile();
                case PANEL_HISTORY   -> showHistory();
                case PANEL_SETTINGS  -> showSettings();
                case PANEL_REGISTRY  -> showRegistry();
            }
        });
    }

    public void invalidatePanel(String panelId) {
        panelCache.remove(panelId);
        if (panelId.equals(activePanelId)) { activePanelId = ""; openPanel(panelId); }
    }

    public void onSceneReady() {
        if (contentArea.getScene() != null)
            contentArea.getScene().getRoot().setUserData(this);
    }
}