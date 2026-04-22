package com.rahbar.widget;

/**
 * WidgetState defines every possible visual state the Arc Reactor widget can be in.
 *
 * Backend team: call ArcReactorWidget.getInstance().setState(WidgetState.X)
 * from any thread — it is thread-safe.
 *
 * States flow like this:
 *   IDLE → LISTENING → PROCESSING → SPEAKING → IDLE
 *   Any state can go to ERROR, ERROR auto-returns to IDLE after 2 seconds.
 *   IDLE ←→ MINIMIZED  (double-click to toggle)
 */
public enum WidgetState {

    /**
     * Default resting state.
     * Visual: slow breathing glow, gentle outer ring rotation, full reactor drawn.
     */
    IDLE,

    /**
     * Voice command detected, microphone is open.
     * Visual: bright pulse, double ripple rings expanding outward rapidly.
     */
    LISTENING,

    /**
     * Voice input received, backend is classifying + executing the command.
     * Visual: fast clockwise ring rotation, steady bright glow.
     */
    PROCESSING,

    /**
     * TTS engine is speaking the response back to the user.
     * Visual: slow rhythmic pulse that mimics speech cadence.
     */
    SPEAKING,

    /**
     * Something went wrong — command failed, no mic, no internet, etc.
     * Visual: red glow, double-beat pulse, canvas physically shakes, then fades to IDLE.
     */
    ERROR,

    /**
     * Widget is minimized to a small glowing dot.
     * Visual: shrinks to 64px pulsing orb. Double-click to restore.
     */
    MINIMIZED
}
