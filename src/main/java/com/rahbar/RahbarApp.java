package com.rahbar;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.rahbar.db.DatabaseManager;
import com.rahbar.ui.CommandPreviewPopup;
import com.rahbar.ui.MainWindow;
import com.rahbar.ui.OnboardingWindow;
import com.rahbar.ui.ThemeManager;
import com.rahbar.widget.ArcReactorWidget;
import com.rahbar.widget.VoiceWakeWordListener;
import com.rahbar.widget.WidgetState;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/** JavaFX Application entry point; inits DB, shows Arc Reactor widget, starts Python bridge and command listening loop. */
public class RahbarApp extends Application {

    // Static reference used by SettingsController to notify settings changes
    private static volatile RahbarApp instance;

    private MainWindow mainWindow;

    // Backend references — set once bridge thread starts
    private volatile bridge.PythonBridge  pythonBridgeRef;
    private volatile router.CommandRouter commandRouterRef;

    @Override
    public void start(Stage primaryStage) {
        instance = this;
        primaryStage.hide();
        Platform.setImplicitExit(false);

        // Shutdown hook: fires even when IntelliJ stop button is pressed.
        // Ensures the Python subprocess is killed so no orphan process remains.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[RahbarApp] JVM shutdown — force-killing Python bridge...");
            bridge.PythonBridge pb = pythonBridgeRef;
            if (pb != null) pb.forceShutdown();
        }, "PythonKillHook"));

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

            // Hide widget immediately if user disabled "Show Widget on Launch"
            try {
                String showWidget = DatabaseManager.getInstance().getSetting("pref_show_widget");
                if ("false".equals(showWidget)) widget.hide();
            } catch (Exception ignored) {}

            // Restore widget accent color from saved theme (Issue 4)
            try {
                String savedTheme = DatabaseManager.getInstance().getSetting("theme");
                if (savedTheme != null) {
                    ThemeManager.Theme t = ThemeManager.getInstance().fromStoredName(savedTheme);
                    ThemeManager.getInstance().preloadTheme(t);
                    widget.setThemeColor(themeToWidgetColor(t));
                }
            } catch (Exception ignored) {}

            registerWidgetClickListener();
            restoreWidgetPosition();
            startPythonBridge();
            registerGlobalHotkey();

            System.out.println("[RahbarApp] Startup complete. Widget is live.");
        });
    }

    private Color themeToWidgetColor(ThemeManager.Theme theme) {
        return switch (theme) {
            case BLACK_BLUE     -> Color.web("#3D7EFF");
            case CHARCOAL_GREEN -> Color.web("#39FF14");
            case PURPLE_GOLD    -> Color.web("#FFD700");
            default             -> Color.web("#00B4D8");
        };
    }

    /** Called by SettingsController when the gesture toggle changes. Not persisted (privacy). */
    public static void notifyGestureToggle(boolean enable) {
        RahbarApp app = instance;
        if (app == null) return;
        bridge.PythonBridge pb = app.pythonBridgeRef;
        if (pb == null) return;
        Thread t = new Thread(() -> {
            try {
                if (enable) pb.startGestures();
                else        pb.stopGestures();
                System.out.println("[RahbarApp] Gesture control " + (enable ? "STARTED" : "STOPPED") + " OK.");
            } catch (Exception e) {
                System.err.println("[RahbarApp] Gesture toggle error: " + e.getMessage());
            }
        }, "GestureToggleThread");
        t.setDaemon(true);
        t.start();
    }

    /** Called by SettingsController after saving. Notifies Python to reload. */
    public static void notifySettingsChanged() {
        RahbarApp app = instance;
        if (app == null) return;
        bridge.PythonBridge pb = app.pythonBridgeRef;
        if (pb == null) return;
        Thread t = new Thread(() -> {
            try { pb.reloadSettings(); }
            catch (Exception e) {
                System.err.println("[RahbarApp] Settings reload error: " + e.getMessage());
            }
        }, "SettingsReloadThread");
        t.setDaemon(true);
        t.start();
    }

    /** Called by SettingsController "Preview" button; sends a TTS test sentence using the current voice. */
    public static void notifyVoicePreview() {
        RahbarApp app = instance;
        if (app == null) return;
        bridge.PythonBridge pb = app.pythonBridgeRef;
        if (pb == null) {
            System.err.println("[RahbarApp] notifyVoicePreview: bridge not ready.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                pb.sendResponse("Hello. This is a preview of your selected voice.");
                System.out.println("[RahbarApp] Voice preview sent.");
            } catch (Exception e) {
                System.err.println("[RahbarApp] Voice preview error: " + e.getMessage());
            }
        }, "VoicePreviewThread");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        System.out.println("[RahbarApp] Shutting down...");
        saveWidgetPosition();
        stopPythonBridge();
        ArcReactorWidget.getInstance().dispose();
    }

    // ── Widget callbacks ──────────────────────────────────────────────────────

    private void onOpenDashboard() { mainWindow.show(); }
    private void onExitApp()       { Platform.exit(); }

    // ── Widget click listener ─────────────────────────────────────────────────

    private void registerWidgetClickListener() {
        ArcReactorWidget.getInstance().setClickListener(
                new ArcReactorWidget.WidgetClickListener() {
                    @Override
                    public void onWidgetClicked() {
                        System.out.println("[RahbarApp] Widget clicked — listening.");
                        if (mainWindow.isShowing())
                            mainWindow.getController().setStatus("LISTENING", "#00B4D8");
                        // Enable Python voice capture (Issue 1)
                        sendListenCommand(true);
                    }

                    @Override
                    public void onWidgetCancelled() {
                        System.out.println("[RahbarApp] Widget cancelled.");
                        ArcReactorWidget.getInstance().setState(WidgetState.IDLE);
                        if (mainWindow.isShowing())
                            mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                        // Disable Python voice capture (Issue 1)
                        sendListenCommand(false);
                    }

                    @Override
                    public void onMuteToggled(boolean isMuted) {
                        System.out.println("[RahbarApp] Mute toggled: " + isMuted);
                    }
                }
        );
    }

    private void sendListenCommand(boolean start) {
        bridge.PythonBridge pb = pythonBridgeRef;
        if (pb == null) {
            System.err.println("[RahbarApp] sendListenCommand(" + start + ") ignored — bridge not ready.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (start) pb.startListening();
                else        pb.stopListening();
                System.out.println("[RahbarApp] Python listening " + (start ? "STARTED" : "STOPPED") + " OK.");
            } catch (Exception e) {
                System.err.println("[RahbarApp] " + (start ? "startListening" : "stopListening")
                        + " HTTP error: " + e.getMessage());
            }
        }, start ? "StartListenThread" : "StopListenThread");
        t.setDaemon(true);
        t.start();
    }

    // ── Python bridge ─────────────────────────────────────────────────────────

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

                        // Gesture intents — no TTS, just UI action
                        if (intent.startsWith("GESTURE_")) {
                            handleGestureIntent(intent);
                            Platform.runLater(() ->
                                ArcReactorWidget.getInstance().setState(WidgetState.IDLE));
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

                        // If a yes/no confirmation is pending, keep listening so the
                        // user can reply without clicking the widget again.
                        if (cr.hasPendingConfirmation()) {
                            Platform.runLater(() ->
                                ArcReactorWidget.getInstance().setState(WidgetState.LISTENING));
                        } else {
                            // Normal command finished — stop listening and return to IDLE
                            try { pb.stopListening(); }
                            catch (Exception e) {
                                System.err.println("[RahbarApp] stopListening error: " + e.getMessage());
                            }

                            Thread.sleep(100);
                            Platform.runLater(() -> {
                                ArcReactorWidget.getInstance().setState(WidgetState.IDLE);
                                if (mainWindow != null && mainWindow.isShowing())
                                    mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                            });
                        }

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

    // ── Global hotkey

    // ── Gesture intent handler

    private void handleGestureIntent(String intent) {
        System.out.println("[RahbarApp] Gesture intent: " + intent);
        switch (intent) {
            case "GESTURE_TOGGLE_WIDGET" -> Platform.runLater(() -> {
                ArcReactorWidget w = ArcReactorWidget.getInstance();
                if (w.getState() == WidgetState.MINIMIZED) {
                    w.setState(WidgetState.IDLE);
                } else {
                    w.setState(WidgetState.MINIMIZED);
                }
            });
            default -> System.out.println("[RahbarApp] Unknown gesture intent: " + intent);
        }
    }

    /** Registers a JNativeHook system-wide hotkey from DB settings (hotkey_enabled/ctrl/shift/alt/key); toggles listening. */
    private void registerGlobalHotkey() {
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            boolean enabled = !"false".equals(db.getSetting("hotkey_enabled"));
            if (!enabled) {
                System.out.println("[RahbarApp] Global hotkey disabled by user setting.");
                return;
            }

            boolean useCtrl  = !"false".equals(db.getSetting("hotkey_ctrl"));
            boolean useShift = !"false".equals(db.getSetting("hotkey_shift"));
            boolean useAlt   = "true".equals(db.getSetting("hotkey_alt"));
            String  keyStr   = db.getSetting("hotkey_key");
            if (keyStr == null || keyStr.isBlank()) keyStr = "R";
            final String hotkeyChar = keyStr.trim().toUpperCase();

            // Silence JNativeHook's noisy logger
            Logger nativeLogger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            nativeLogger.setLevel(Level.WARNING);
            nativeLogger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();

            final boolean fCtrl  = useCtrl;
            final boolean fShift = useShift;
            final boolean fAlt   = useAlt;

            GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
                @Override
                public void nativeKeyPressed(NativeKeyEvent e) {
                    int mods = e.getModifiers();
                    boolean ctrlOk  = !fCtrl  || (mods & NativeKeyEvent.CTRL_MASK)  != 0;
                    boolean shiftOk = !fShift || (mods & NativeKeyEvent.SHIFT_MASK) != 0;
                    boolean altOk   = !fAlt   || (mods & NativeKeyEvent.ALT_MASK)   != 0;

                    if (!ctrlOk || !shiftOk || !altOk) return;

                    String pressed = NativeKeyEvent.getKeyText(e.getKeyCode());
                    if (!pressed.equalsIgnoreCase(hotkeyChar)) return;

                    // Toggle listening — same logic as widget click
                    Platform.runLater(() -> {
                        ArcReactorWidget widget = ArcReactorWidget.getInstance();
                        WidgetState state = widget.getState();
                        if (state == WidgetState.IDLE) {
                            widget.setState(WidgetState.LISTENING);
                            if (mainWindow != null && mainWindow.isShowing())
                                mainWindow.getController().setStatus("LISTENING", "#00B4D8");
                            sendListenCommand(true);
                        } else if (state == WidgetState.LISTENING
                                || state == WidgetState.PROCESSING
                                || state == WidgetState.SPEAKING) {
                            widget.setState(WidgetState.IDLE);
                            if (mainWindow != null && mainWindow.isShowing())
                                mainWindow.getController().setStatus("ACTIVE", "#2ECC71");
                            sendListenCommand(false);
                        }
                    });
                }

                @Override public void nativeKeyReleased(NativeKeyEvent e) {}
                @Override public void nativeKeyTyped(NativeKeyEvent e) {}
            });

            System.out.printf("[RahbarApp] Global hotkey registered: %s%s%s%s%n",
                    useCtrl  ? "Ctrl+"  : "",
                    useAlt   ? "Alt+"   : "",
                    useShift ? "Shift+" : "",
                    hotkeyChar);

        } catch (NativeHookException e) {
            System.err.println("[RahbarApp] Could not register global hotkey: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[RahbarApp] Global hotkey setup error: " + e.getMessage());
        }
    }

    // ── Database init

    private void initDatabase() {
        try {
            DatabaseManager.getInstance();
            System.out.println("[RahbarApp] Database initialized.");
        } catch (Exception e) {
            System.err.println("[RahbarApp] Database init failed: " + e.getMessage());
        }
    }

    // ── Widget position persistence

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
