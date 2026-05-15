package com.rahbar.widget;

/** All visual states for the Arc Reactor widget. Flow: IDLE→LISTENING→PROCESSING→SPEAKING→IDLE; any state→ERROR→IDLE. */
public enum WidgetState {

    IDLE,        // slow breathing glow, gentle ring rotation
    LISTENING,   // bright pulse, double ripple rings expanding outward
    PROCESSING,  // fast clockwise ring rotation, steady bright glow
    SPEAKING,    // slow rhythmic pulse mimicking speech cadence
    ERROR,       // red glow, double-beat pulse, canvas shakes, then fades to IDLE
    MINIMIZED,   // shrinks to 64px pulsing orb; double-click to restore
    BOOTING      // one-shot startup animation powering layers on; auto-transitions to IDLE
}
