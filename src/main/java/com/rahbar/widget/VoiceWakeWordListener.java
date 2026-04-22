package com.rahbar.widget;

/**
 * VoiceWakeWordListener
 *
 * Listens for the wake phrase "RAHBAR Guide me" in the background.
 * When detected, it activates the widget exactly as if the user clicked it.
 *
 * ── How this connects to the Python bridge ──────────────────────────────────
 * The actual microphone listening happens in Python (voice_listener.py).
 * When the Python bridge sends a JSON payload where "intent" == "WAKE_WORD",
 * the Java CommandRouter calls VoiceWakeWordListener.onWakeWordDetected().
 *
 * This class is purely the Java side of that contract — it receives the signal
 * and updates the widget state accordingly.
 *
 * Backend team: In your CommandRouter, add:
 *
 *   case "WAKE_WORD" -> {
 *       VoiceWakeWordListener.onWakeWordDetected();
 *       return "RAHBAR activated.";
 *   }
 *
 * Python team: In intent_classifier.py training data, add:
 *
 *   phrases += ['RAHBAR guide me', 'hey RAHBAR', 'rahbar wake up']
 *   labels  += ['WAKE_WORD', 'WAKE_WORD', 'WAKE_WORD']
 *
 * ────────────────────────────────────────────────────────────────────────────
 */
public class VoiceWakeWordListener {

    // The canonical wake phrase that Python's STT should detect.
    // Keep this in sync with the Python training data.
    public static final String WAKE_PHRASE = "RAHBAR guide me";

    /**
     * Called by CommandRouter when Python sends intent == "WAKE_WORD".
     * Updates the widget to LISTENING state and notifies the click listener,
     * so the backend starts the full listening pipeline.
     *
     * Thread-safe — can be called from any thread.
     */
    public static void onWakeWordDetected() {
        ArcReactorWidget widget = ArcReactorWidget.getInstance();

        // Only activate if currently idle — ignore if already active
        if (widget.getState() == WidgetState.IDLE) {
            widget.setState(WidgetState.LISTENING);

            // Notify the click listener so the backend knows to start listening
            // This reuses the same pathway as a manual click — backend treats them identically
            ArcReactorWidget.WidgetClickListener listener = getRegisteredListener(widget);
            if (listener != null) {
                listener.onWidgetClicked();
            }
        }
    }

    /**
     * Called when the wake word detection fails (no internet, mic error, etc.)
     * Puts the widget into ERROR state briefly.
     */
    public static void onWakeWordError() {
        ArcReactorWidget.getInstance().setState(WidgetState.ERROR);
    }

    // ── Reflection-free listener access ───────────────────────────────────────
    // We can't access the private field directly, so the widget exposes a
    // package-private accessor. Both classes are in com.rahbar.widget.

    static ArcReactorWidget.WidgetClickListener getRegisteredListener(ArcReactorWidget widget) {
        return widget.clickListener;
    }
}
