package com.rahbar.widget;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * WidgetAnimationEngine
 *
 * Drives all animation values for the Arc Reactor widget using a single
 * AnimationTimer (~60fps). All values exposed as JavaFX DoubleProperties.
 *
 * Animation values produced:
 *   - glowIntensity    : 0.0 → 1.0   how bright the reactor core glows
 *   - ringScale        : 0.8 → 1.2   scale multiplier for the outer rings
 *   - rotationAngle    : 0 → 360     degrees, outer ring slow idle + fast PROCESSING spin
 *   - rippleRadius     : 0.0 → 1.0   normalized radius of first ripple ring (LISTENING)
 *   - rippleOpacity    : 0.0 → 1.0   fades out as first ripple expands
 *   - rippleRadius2    : 0.0 → 1.0   second ripple ring (offset by half cycle)
 *   - rippleOpacity2   : 0.0 → 1.0   second ripple opacity
 *   - shakeOffset      : -N → +N     px horizontal shake during ERROR
 *   - minimizeProgress : 0.0 → 1.0   0 = full size, 1 = fully minimized dot
 */
public class WidgetAnimationEngine {

    // ── Published animation properties (read by ArcReactorCanvas) ────────────
    private final DoubleProperty glowIntensity   = new SimpleDoubleProperty(0.3);
    private final DoubleProperty ringScale       = new SimpleDoubleProperty(1.0);
    private final DoubleProperty rotationAngle   = new SimpleDoubleProperty(0.0);
    private final DoubleProperty rippleRadius    = new SimpleDoubleProperty(0.0);
    private final DoubleProperty rippleOpacity   = new SimpleDoubleProperty(0.0);
    private final DoubleProperty rippleRadius2   = new SimpleDoubleProperty(0.0);
    private final DoubleProperty rippleOpacity2  = new SimpleDoubleProperty(0.0);
    private final DoubleProperty shakeOffset     = new SimpleDoubleProperty(0.0);
    private final DoubleProperty minimizeProgress= new SimpleDoubleProperty(0.0);

    // ── Internal timing ───────────────────────────────────────────────────────
    private long   lastFrameNanos  = -1;
    private double timeSeconds     = 0.0;
    private double rippleProgress  = 0.0;   // first ripple, 0→1
    private double ripple2Progress = 0.5;   // second ripple, offset by half cycle
    private double idleRotation    = 0.0;   // accumulated idle rotation degrees
    private double minimizeTarget  = 0.0;   // 0 = full, 1 = minimized
    private double minimizeCurrent = 0.0;   // smoothly animated toward target

    // ── Current state ─────────────────────────────────────────────────────────
    private volatile WidgetState currentState = WidgetState.IDLE;

    private final AnimationTimer timer;

    public WidgetAnimationEngine() {
        timer = new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                if (lastFrameNanos < 0) {
                    lastFrameNanos = nowNanos;
                    return;
                }
                double delta = (nowNanos - lastFrameNanos) / 1_000_000_000.0;
                lastFrameNanos = nowNanos;
                if (delta > 0.1) delta = 0.1;
                timeSeconds += delta;
                tickAnimation(delta);
            }
        };
    }

    public void start() {
        lastFrameNanos = -1;
        timer.start();
    }

    public void stop() {
        timer.stop();
    }

    /**
     * Updates the animation target state. Thread-safe (volatile).
     */
    public void setState(WidgetState state) {
        if (state == WidgetState.LISTENING && currentState != WidgetState.LISTENING) {
            rippleProgress  = 0.0;
            ripple2Progress = 0.5; // offset so they alternate
        }
        if (state == WidgetState.MINIMIZED) {
            minimizeTarget = 1.0;
        } else if (currentState == WidgetState.MINIMIZED) {
            minimizeTarget = 0.0; // expanding back out
        }
        this.currentState = state;
    }

    // ── Core animation tick ───────────────────────────────────────────────────

    private void tickAnimation(double delta) {
        // Always animate minimize/restore transition smoothly
        tickMinimizeTransition(delta);

        switch (currentState) {
            case IDLE       -> tickIdle(delta);
            case LISTENING  -> tickListening(delta);
            case PROCESSING -> tickProcessing(delta);
            case SPEAKING   -> tickSpeaking();
            case ERROR      -> tickError();
            case MINIMIZED  -> tickMinimized();
        }
    }

    /**
     * IDLE: slow sinusoidal breathing glow + very slow outer ring drift.
     * Glow: 0.2 → 0.55, period ~4s.
     * Idle rotation: ~5°/sec — just enough to feel alive, not distracting.
     */
    private void tickIdle(double delta) {
        double breath = Math.sin(timeSeconds * (Math.PI / 2.0));
        glowIntensity.set(lerp(0.2, 0.55, (breath + 1.0) / 2.0));
        ringScale.set(lerp(0.97, 1.03, (Math.sin(timeSeconds * 0.8) + 1.0) / 2.0));

        // Slow continuous idle rotation
        idleRotation = (idleRotation + delta * 5.0) % 360.0;
        rotationAngle.set(idleRotation);

        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);
    }

    /**
     * LISTENING: bright core + two alternating ripple rings expanding outward.
     * Each ripple loops every 1.2 seconds. Ring 2 is offset by 0.6s.
     */
    private void tickListening(double delta) {
        double fastPulse = Math.sin(timeSeconds * Math.PI * 3.0);
        glowIntensity.set(lerp(0.75, 1.0, (fastPulse + 1.0) / 2.0));
        ringScale.set(lerp(1.0, 1.08, (Math.sin(timeSeconds * Math.PI * 4) + 1.0) / 2.0));

        // Ripple 1
        rippleProgress += delta / 1.2;
        if (rippleProgress > 1.0) rippleProgress = 0.0;
        rippleRadius.set(rippleProgress);
        rippleOpacity.set(1.0 - rippleProgress);

        // Ripple 2 — offset by half cycle (0.6s)
        ripple2Progress += delta / 1.2;
        if (ripple2Progress > 1.0) ripple2Progress = 0.0;
        rippleRadius2.set(ripple2Progress);
        rippleOpacity2.set(1.0 - ripple2Progress);

        // Rotation: keep the idle drift going but slower
        idleRotation = (idleRotation + delta * 3.0) % 360.0;
        rotationAngle.set(idleRotation);

        shakeOffset.set(0.0);
    }

    /**
     * PROCESSING: steady bright glow + fast clockwise ring rotation (120°/s).
     */
    private void tickProcessing(double delta) {
        double steadyPulse = Math.sin(timeSeconds * Math.PI * 1.5);
        glowIntensity.set(lerp(0.7, 0.9, (steadyPulse + 1.0) / 2.0));
        ringScale.set(1.0);

        idleRotation = (idleRotation + delta * 120.0) % 360.0;
        rotationAngle.set(idleRotation);

        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);
    }

    /**
     * SPEAKING: rhythmic slow pulse mimicking speech cadence.
     */
    private void tickSpeaking() {
        double speechPulse = Math.sin(timeSeconds * Math.PI * 2.2);
        glowIntensity.set(lerp(0.5, 0.85, (speechPulse + 1.0) / 2.0));
        ringScale.set(lerp(0.98, 1.06, (speechPulse + 1.0) / 2.0));

        idleRotation = (idleRotation + 0.05) % 360.0; // nearly stopped
        rotationAngle.set(idleRotation);

        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);
    }

    /**
     * ERROR: red state — double-beat pulse + horizontal shake.
     * 1.5s cycle: two fast beats then rest.
     */
    private void tickError() {
        double phase = timeSeconds % 1.5;
        double intensity;
        if (phase < 0.15) {
            intensity = phase / 0.15;
        } else if (phase < 0.3) {
            intensity = 1.0 - ((phase - 0.15) / 0.15);
        } else if (phase < 0.45) {
            intensity = (phase - 0.3) / 0.15;
        } else if (phase < 0.6) {
            intensity = 1.0 - ((phase - 0.45) / 0.15);
        } else {
            intensity = 0.1;
        }

        glowIntensity.set(lerp(0.1, 1.0, intensity));
        ringScale.set(1.0 + intensity * 0.05);
        rotationAngle.set(idleRotation);

        // Shake: fast sine at ~18Hz, amplitude scales with intensity
        double shake = Math.sin(timeSeconds * Math.PI * 18.0) * intensity * 6.0;
        shakeOffset.set(shake);

        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
    }

    /**
     * MINIMIZED: tiny pulsing dot.
     * Full drawing is suppressed by canvas reading minimizeProgress.
     */
    private void tickMinimized() {
        double breath = Math.sin(timeSeconds * Math.PI * 1.5);
        glowIntensity.set(lerp(0.4, 0.9, (breath + 1.0) / 2.0));
        ringScale.set(1.0);
        rotationAngle.set(0.0);
        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);
    }

    /**
     * Smoothly animates the minimize/restore size transition.
     * Speed: full transition in ~0.3 seconds.
     */
    private void tickMinimizeTransition(double delta) {
        double speed = delta / 0.3; // covers 0→1 in 0.3s
        if (minimizeCurrent < minimizeTarget) {
            minimizeCurrent = Math.min(minimizeCurrent + speed, minimizeTarget);
        } else if (minimizeCurrent > minimizeTarget) {
            minimizeCurrent = Math.max(minimizeCurrent - speed, minimizeTarget);
        }
        minimizeProgress.set(minimizeCurrent);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private double lerp(double a, double b, double t) {
        return a + (b - a) * Math.clamp(t, 0.0, 1.0);
    }

    // ── Property accessors ────────────────────────────────────────────────────

    public DoubleProperty glowIntensityProperty()   { return glowIntensity; }
    public DoubleProperty ringScaleProperty()       { return ringScale; }
    public DoubleProperty rotationAngleProperty()   { return rotationAngle; }
    public DoubleProperty rippleRadiusProperty()    { return rippleRadius; }
    public DoubleProperty rippleOpacityProperty()   { return rippleOpacity; }
    public DoubleProperty rippleRadius2Property()   { return rippleRadius2; }
    public DoubleProperty rippleOpacity2Property()  { return rippleOpacity2; }
    public DoubleProperty shakeOffsetProperty()     { return shakeOffset; }
    public DoubleProperty minimizeProgressProperty(){ return minimizeProgress; }

    public WidgetState getCurrentState() { return currentState; }
}
