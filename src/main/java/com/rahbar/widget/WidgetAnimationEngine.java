package com.rahbar.widget;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

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
    private final DoubleProperty bootProgress    = new SimpleDoubleProperty(0.0);

    // ── Internal timing ───────────────────────────────────────────────────────
    private long   lastFrameNanos  = -1;
    private double timeSeconds     = 0.0;
    private double rippleProgress  = 0.0;   // first ripple, 0→1
    private double ripple2Progress = 0.5;   // second ripple, offset by half cycle
    private double idleRotation    = 0.0;   // accumulated idle rotation degrees
    private double minimizeTarget  = 0.0;   // 0 = full, 1 = minimized
    private double minimizeCurrent = 0.0;   // smoothly animated toward target
    private double bootTimeSeconds = 0.0;   // elapsed time in BOOTING state

    private static final double BOOT_DURATION = 4.0; // seconds for full boot sequence

    // ── Current state ─────────────────────────────────────────────────────────
    private volatile WidgetState currentState = WidgetState.BOOTING;

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

    /** Updates the animation target state; thread-safe via volatile field. */
    public void setState(WidgetState state) {
        // Don't interrupt the boot animation — it auto-completes to IDLE
        if (currentState == WidgetState.BOOTING && state != WidgetState.BOOTING) return;

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
            case BOOTING    -> tickBooting(delta);
            case IDLE       -> tickIdle(delta);
            case LISTENING  -> tickListening(delta);
            case PROCESSING -> tickProcessing(delta);
            case SPEAKING   -> tickSpeaking();
            case ERROR      -> tickError();
            case MINIMIZED  -> tickMinimized();
        }
    }

    /** BOOTING: powers on ring layers sequentially; auto-transitions to IDLE after BOOT_DURATION seconds. */
    private void tickBooting(double delta) {
        bootTimeSeconds += delta;
        double prog = Math.min(1.0, bootTimeSeconds / BOOT_DURATION);
        bootProgress.set(prog);

        // Glow builds gradually across the whole boot sequence
        glowIntensity.set(lerp(0.0, 0.55, prog));
        ringScale.set(1.0);

        // Rotation starts only after outer ring activates (~48% through boot)
        double outerFactor = bootWindow(prog, 0.48, 0.70);
        idleRotation = (idleRotation + delta * 5.0 * outerFactor) % 360.0;
        rotationAngle.set(idleRotation);

        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);

        if (prog >= 1.0) {
            currentState = WidgetState.IDLE;
        }
    }

    // Helper used by tickBooting and exposed for canvas
    static double bootWindow(double prog, double start, double end) {
        if (prog <= start) return 0.0;
        if (prog >= end)   return 1.0;
        return (prog - start) / (end - start);
    }

    /** IDLE: sinusoidal breathing glow (0.2→0.55, ~4s period) + slow outer ring drift at ~5°/sec. */
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

    /** LISTENING: bright pulsing core + two alternating ripple rings (1.2s loop, ring 2 offset by 0.6s). */
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

    /** PROCESSING: steady bright glow + fast clockwise ring rotation at 120°/s. */
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

    /** SPEAKING: rhythmic slow pulse mimicking speech cadence. */
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

    /** ERROR: double-beat pulse + horizontal shake; 1.5s cycle with two fast beats then rest. */
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

    /** MINIMIZED: tiny pulsing dot; full drawing suppressed by canvas via minimizeProgress. */
    private void tickMinimized() {
        double breath = Math.sin(timeSeconds * Math.PI * 1.5);
        glowIntensity.set(lerp(0.4, 0.9, (breath + 1.0) / 2.0));
        ringScale.set(1.0);
        rotationAngle.set(0.0);
        rippleOpacity.set(0.0);
        rippleOpacity2.set(0.0);
        shakeOffset.set(0.0);
    }

    /** Smoothly animates minimize/restore size transition; completes in ~0.3 seconds. */
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
    public DoubleProperty bootProgressProperty()    { return bootProgress; }

    public WidgetState getCurrentState() { return currentState; }
}
