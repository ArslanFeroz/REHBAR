package com.rahbar;

import com.rahbar.db.DatabaseManager;
import com.rahbar.ui.CommandPreviewPopup;
import com.rahbar.ui.MainWindow;
import com.rahbar.ui.OnboardingWindow;
import com.rahbar.widget.ArcReactorWidget;
import com.rahbar.widget.VoiceWakeWordListener;
import com.rahbar.widget.WidgetState;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * RahbarApp — JavaFX Application entry point.
 *
 * Startup flow:
 *   1. Init database (com.rahbar.db.DatabaseManager → delegates to db.DatabaseManager)
 *   2. First-launch check → OnboardingWindow or direct startup
 *   3. Build MainWindow, show Arc Reactor widget
 *   4. startPythonBridge() — launches Flask bridge + command listening loop in background thread
 *
 * Backend integration points (filled in vs original stubs):
 *   - startPythonBridge()  : creates bridge.PythonBridge + router.CommandRouter, runs listening loop
 *   - stopPythonBridge()   : shuts down bridge process and router
 *   - onWidgetClicked()    : updates UI status to LISTENING
 *   - onWidgetCancelled()  : resets to IDLE
 */
public class RahbarApp extends Application {

    private MainWindow mainWindow;

    // Backend references — set once bridge thread starts
    private volatile bridge.PythonBridge  pythonBridgeRef;
    private volatile router.CommandRouter commandRouterRef;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.hide();
        Platform.setImplicitExit(false);

        initDatabase();

        if (OnboardingWindow.isFirstLaunch()) {
            OnboardingWindow onboarding = new OnboardingWindow();
            onboarding.show(this::continueStartup);
        } else {
            continueStartup();
        }
    }

    private void continueStartup() {
        Platform.runLater(() -> {
            mainWindow = new MainWindow();

            ArcReactorWidget widget = ArcReactorWidget.getInstance();
            widget.show(this::onOpenDashboard, this::onExitApp);

            registerWidgetClickListener();
            restoreWidgetPosition();
            startPythonBridge();
            registerGlobalHotkey();

            System.out.println("[RahbarApp] Startup complete. Widget is live.");
        });
    }

    @Override
    public void stop() {
        System.out.println("[RahbarApp] Shutting down...");
        saveWidgetPosition();
        stopPythonBridge();
        ArcReactorWidget.getInstance().dispose();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Widget callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private void onOpenDashboard() { mainWindow.show(); }
    private void onExitApp()       { Platform.exit(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Widget click listener
    // ─────────────────────────────────────────────────────────────────────────

    private void registerWidgetClickListener() {
        ArcReactorWidget.getInstance().setClickListener(
                new ArcReactorWidget.WidgetClickListener() {
                    @Override
                    public void onWidgetClicked() {
                        // Widget already set state to LISTENING before calling this.
                        // Just sync the main window status label.
                        System.out.println("[RahbarApp] Widget clicked — listening.");
                        if (mainWindow.isShowing())
                            mainWindow.getController().setStatus("LISTENING", "#00B4D8");
                    }

                    @Override
                    public void onWidgetCancelled() {
                        System.out.println("[RahbarApp] Widget cancelled.");
                        ArcReactorWidget.getInstance().setState(WidgetState.IDLE);
                        if (mainWindow.isShowing())
                            mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                    }

                    @Override
                    public void onMuteToggled(boolean isMuted) {
                        System.out.println("[RahbarApp] Mute toggled: " + isMuted);
                    }
                }
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Python bridge — wires backend to frontend
    // ─────────────────────────────────────────────────────────────────────────

    private void startPythonBridge() {
        Thread bridgeThread = new Thread(() -> {
            try {
                // ── Launch Flask bridge ───────────────────────────────────────
                bridge.PythonBridge pb = new bridge.PythonBridge();
                pb.start(); // blocks until Flask returns /health 200

                // ── Build command router ──────────────────────────────────────
                router.CommandRouter cr = new router.CommandRouter();

                // Wire alarm TTS callback: fires from scheduler thread,
                // sends spoken text to Python TTS and updates widget.
                cr.setAlarmTtsCallback(text -> {
                    try { pb.sendResponse(text); } catch (Exception e) {
                        System.err.println("[RahbarApp] Alarm TTS error: " + e.getMessage());
                    }
                    Platform.runLater(() -> {
                        ArcReactorWidget.getInstance().setState(WidgetState.SPEAKING);
                        if (mainWindow != null && mainWindow.isShowing())
                            mainWindow.getController().setStatus("SPEAKING", "#00B4D8");
                    });
                });

                pythonBridgeRef  = pb;
                commandRouterRef = cr;

                // Bridge is ready — update widget to IDLE
                Platform.runLater(() -> {
                    ArcReactorWidget.getInstance().setState(WidgetState.IDLE);
                    if (mainWindow != null && mainWindow.isShowing())
                        mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                });

                System.out.println("[RahbarApp] Python bridge ready. RAHBAR is listening.");

                // ── Listening loop ────────────────────────────────────────────
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        com.google.gson.JsonObject cmd = pb.readCommand();
                        if (cmd == null) continue; // 204 — no speech yet

                        String text   = cmd.get("text").getAsString();
                        String intent = cmd.get("intent").getAsString();

                        System.out.println("[Bridge] text=" + text + " intent=" + intent);

                        // Update widget → PROCESSING
                        Platform.runLater(() -> {
                            ArcReactorWidget.getInstance().setState(WidgetState.PROCESSING);
                            if (mainWindow != null && mainWindow.isShowing())
                                mainWindow.getController().setStatus("PROCESSING", "#F39C12");
                        });

                        // Wake-word handled separately
                        if ("WAKE_WORD".equals(intent)) {
                            Platform.runLater(VoiceWakeWordListener::onWakeWordDetected);
                            continue;
                        }

                        // Route command via backend
                        String result = cr.route(text, intent);

                        // Show floating preview popup near the widget
                        CommandPreviewPopup.show(text, result);

                        // Update widget → SPEAKING
                        Platform.runLater(() -> {
                            ArcReactorWidget.getInstance().setState(WidgetState.SPEAKING);
                            if (mainWindow != null && mainWindow.isShowing())
                                mainWindow.getController().setStatus("SPEAKING", "#00B4D8");
                        });

                        // Send TTS to Python
                        pb.sendResponse(result);

                        // Brief pause then back to IDLE
                        Thread.sleep(500);
                        Platform.runLater(() -> {
                            ArcReactorWidget.getInstance().setState(WidgetState.IDLE);
                            if (mainWindow != null && mainWindow.isShowing())
                                mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                        });

                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("[RahbarApp] Listen error: " + e.getMessage());
                        Platform.runLater(() ->
                                ArcReactorWidget.getInstance().setState(WidgetState.ERROR));
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }

            } catch (Exception e) {
                System.err.println("[RahbarApp] Bridge startup failed: " + e.getMessage());
                Platform.runLater(() -> {
                    ArcReactorWidget.getInstance().setState(WidgetState.ERROR);
                    if (mainWindow != null && mainWindow.isShowing())
                        mainWindow.getController().setStatus("ERROR", "#E74C3C");
                });
            }
        }, "RahbarBridgeThread");

        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    private void stopPythonBridge() {
        bridge.PythonBridge  pb = pythonBridgeRef;
        router.CommandRouter cr = commandRouterRef;
        if (pb != null) { try { pb.shutdown(); } catch (Exception ignored) {} }
        if (cr != null) { try { cr.shutdown(); } catch (Exception ignored) {} }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Global hotkey (stub — wire JNativeHook here when ready)
    // ─────────────────────────────────────────────────────────────────────────

    private void registerGlobalHotkey() {
        // See RahbarApp original stub for full JNativeHook implementation guide.
        System.out.println("[RahbarApp] Global hotkey stub — wire JNativeHook here.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Database init
    // ─────────────────────────────────────────────────────────────────────────

    private void initDatabase() {
        try {
            DatabaseManager.getInstance();
            System.out.println("[RahbarApp] Database initialized.");
        } catch (Exception e) {
            System.err.println("[RahbarApp] Database init failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Widget position persistence
    // ─────────────────────────────────────────────────────────────────────────

    private void restoreWidgetPosition() {
        try {
            String x = DatabaseManager.getInstance().getSetting("widget_pos_x");
            String y = DatabaseManager.getInstance().getSetting("widget_pos_y");
            if (x != null && y != null)
                ArcReactorWidget.getInstance().setPosition(
                        Double.parseDouble(x), Double.parseDouble(y));
        } catch (Exception ignored) {}
    }

    private void saveWidgetPosition() {
        try {
            ArcReactorWidget w  = ArcReactorWidget.getInstance();
            DatabaseManager  db = DatabaseManager.getInstance();
            db.saveSetting("widget_pos_x", String.valueOf(w.getPositionX()));
            db.saveSetting("widget_pos_y", String.valueOf(w.getPositionY()));
        } catch (Exception e) {
            System.err.println("[RahbarApp] Could not save widget position: " + e.getMessage());
        }
    }

    public static void main(String[] args) { launch(args); }
}
