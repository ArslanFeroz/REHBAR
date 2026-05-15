package com.rahbar.widget;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.geometry.VPos;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

public class ArcReactorCanvas extends Canvas {

    // ── Canvas dimensions
    public static final double SIZE = 190.0;
    private static final double CX  = SIZE / 2.0;
    private static final double CY  = SIZE / 2.0;

    // ── Reactor geometry radii
    private static final double R_HALO        = 92.0;
    private static final double R_DATA_RING   = 84.0;
    private static final double R_OUTER_RING  = 74.0;
    private static final double R_MID_RING    = 58.0;
    private static final double R_ARM_MOUNT   = 46.0;
    private static final double R_INNER_RING  = 34.0;
    private static final double R_CORE        = 24.0;
    private static final double R_MINIMIZED   = 10.0;

    private final WidgetAnimationEngine engine;

    // ── Theme color
    private Color themeColor = Color.color(0.0, 0.71, 0.85, 1.0); // #00B4D8

    // ── Urdu font for ر (tries common Arabic fonts; falls back to System) ──────
    private Font urduFont = null;

    public ArcReactorCanvas(WidgetAnimationEngine engine) {
        super(SIZE, SIZE);
        this.engine = engine;
        loadUrduFont();
    }

    private void loadUrduFont() {
        String[] candidates = {
                "Noto Nastaliq Urdu", "Noto Naskh Arabic", "Arabic Typesetting",
                "Traditional Arabic", "Aldhabi", "Arial Unicode MS", "Segoe UI"
        };
        for (String name : candidates) {
            Font f = Font.font(name, 18);
            if (f != null) { urduFont = f; break; }
        }
        if (urduFont == null) urduFont = Font.font("System", 18);
    }

    public void setThemeColor(Color color) {
        if (color != null) this.themeColor = color;
    }

    public Color getThemeColor() { return themeColor; }

    // ── Main redraw — called every frame by ArcReactorWidget's AnimationTimer ──

    public void redraw() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);

        double glow      = engine.glowIntensityProperty().get();
        double scale     = engine.ringScaleProperty().get();
        double rotation  = engine.rotationAngleProperty().get();
        double rippleR   = engine.rippleRadiusProperty().get();
        double rippleA   = engine.rippleOpacityProperty().get();
        double ripple2R  = engine.rippleRadius2Property().get();
        double ripple2A  = engine.rippleOpacity2Property().get();
        double shake     = engine.shakeOffsetProperty().get();
        double minProg   = engine.minimizeProgressProperty().get();
        WidgetState state = engine.getCurrentState();

        if (minProg >= 0.99) { drawMinimizedDot(gc, glow); return; } // fully minimized

        gc.save();
        gc.translate(shake, 0); // ERROR shake offset

        if (minProg > 0.01) { // scale reactor toward center dot during minimize transition
            double s = 1.0 - minProg * (1.0 - R_MINIMIZED / R_HALO);
            gc.translate(CX, CY);
            gc.scale(s, s);
            gc.translate(-CX, -CY);
        }

        Color primaryColor = getPrimaryColor(state);
        Color coreColor    = getCoreColor(state);
        Color dimColor     = primaryColor.deriveColor(0, 1, 0.28, 0.5);

        // per-layer boot activation windows (center→outside); factors=1.0 when not booting
        double bootProg = (state == WidgetState.BOOTING)
                ? engine.bootProgressProperty().get() : 1.0;
        double fCore   = WidgetAnimationEngine.bootWindow(bootProg, 0.00, 0.22);
        double fInner  = WidgetAnimationEngine.bootWindow(bootProg, 0.12, 0.34);
        double fArms   = WidgetAnimationEngine.bootWindow(bootProg, 0.24, 0.46);
        double fMiddle = WidgetAnimationEngine.bootWindow(bootProg, 0.36, 0.58);
        double fOuter  = WidgetAnimationEngine.bootWindow(bootProg, 0.48, 0.70);
        double fData   = WidgetAnimationEngine.bootWindow(bootProg, 0.60, 0.82);
        double fHalo   = WidgetAnimationEngine.bootWindow(bootProg, 0.72, 1.00);

        // Layer 1: Atmospheric halo
        drawHaloGlow(gc, bootBlend(primaryColor, fHalo), glow * fHalo);

        // Layer 2: Outer data ring (counter-rotates slowly)
        gc.save();
        gc.translate(CX, CY);
        gc.rotate(-rotation * 0.3);
        gc.translate(-CX, -CY);
        drawDataRing(gc, bootBlend(primaryColor, fData), glow * fData, scale);
        gc.restore();

        // Layer 3: Main outer ring (rotates with engine)
        gc.save();
        gc.translate(CX, CY);
        gc.rotate(rotation);
        gc.translate(-CX, -CY);
        drawOuterRing(gc, bootBlend(primaryColor, fOuter), bootBlend(dimColor, fOuter), glow * fOuter, scale);
        gc.restore();

        // Layer 4: Middle segmented ring (counter-rotates)
        gc.save();
        gc.translate(CX, CY);
        gc.rotate(-rotation * 0.5);
        gc.translate(-CX, -CY);
        drawMiddleRing(gc, bootBlend(primaryColor, fMiddle), glow * fMiddle, scale);
        gc.restore();

        // Layer 5: Triangle mount arms
        drawTriangleArms(gc, bootBlend(primaryColor, fArms), glow * fArms);

        // Layer 6: Inner hex ring with notch nodes
        drawInnerHexRing(gc, bootBlend(primaryColor, fInner), glow * fInner);

        // Layer 7: Ripple rings (LISTENING)
        if (state == WidgetState.LISTENING) {
            if (rippleA > 0.01)  drawRipple(gc, primaryColor, rippleR,  rippleA);
            if (ripple2A > 0.01) drawRipple(gc, primaryColor, ripple2R, ripple2A);
        }

        // Layer 8: Urdu letter ر — glowing centerpiece (replaces core circle + dot)
        drawUrduLetter(gc, bootBlend(coreColor, fCore), glow);

        gc.restore(); // restore shake translation
    }

    // ── Color logic ───────────────────────────────────────────────────────────

    private Color getPrimaryColor(WidgetState state) {
        if (state == WidgetState.ERROR) return Color.color(1.0, 0.15, 0.1, 1.0);
        return themeColor;
    }

    private Color getCoreColor(WidgetState state) {
        return switch (state) {
            case ERROR     -> Color.color(1.0, 0.3, 0.1, 1.0);
            case LISTENING -> Color.color(
                    Math.min(1.0, themeColor.getRed()   + 0.3),
                    Math.min(1.0, themeColor.getGreen() + 0.15),
                    Math.min(1.0, themeColor.getBlue()  + 0.1), 1.0);
            case SPEAKING  -> themeColor.deriveColor(0, 0.85, 0.9, 1.0);
            default        -> themeColor;
        };
    }

    // ── Drawing layers ────────────────────────────────────────────────────────

    private void drawHaloGlow(GraphicsContext gc, Color color, double glow) {
        // two-pass: wide diffuse + tighter inner glow
        RadialGradient outerHalo = new RadialGradient(
                0, 0, CX, CY, R_HALO, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, color.deriveColor(0, 1, 1,   glow * 0.20)),
                new Stop(0.5, color.deriveColor(0, 1, 0.7, glow * 0.10)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(outerHalo);
        gc.fillOval(CX - R_HALO, CY - R_HALO, R_HALO * 2, R_HALO * 2);

        double innerGlowR = R_CORE * 2.5;
        RadialGradient innerHalo = new RadialGradient(
                0, 0, CX, CY, innerGlowR, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, color.deriveColor(0, 1, 1.2, glow * 0.45)),
                new Stop(0.6, color.deriveColor(0, 1, 0.8, glow * 0.18)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(innerHalo);
        gc.fillOval(CX - innerGlowR, CY - innerGlowR, innerGlowR * 2, innerGlowR * 2);
    }

    /** 16 dashed arc segments + 8 square markers; counter-rotates slowly. */
    private void drawDataRing(GraphicsContext gc, Color primary, double glow, double scale) {
        double r = R_DATA_RING * scale;
        double alpha = 0.25 + glow * 0.25;

        // 16 dashed arc segments
        gc.setStroke(primary.deriveColor(0, 1, 1, alpha));
        gc.setLineWidth(1.0);
        int segs = 16;
        double arcSpan = 360.0 / segs;
        double gapDeg  = 4.5;
        for (int i = 0; i < segs; i++) {
            double start = i * arcSpan + gapDeg / 2.0 - 90.0;
            double sweep = arcSpan - gapDeg;
            gc.strokeArc(CX - r, CY - r, r * 2, r * 2,
                    start, sweep, javafx.scene.shape.ArcType.OPEN);
        }

        // 8 square markers at every other segment midpoint
        gc.setFill(primary.deriveColor(0, 1, 1, alpha + 0.2));
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            double mx = CX + Math.cos(angle) * r;
            double my = CY + Math.sin(angle) * r;
            gc.fillRect(mx - 2, my - 2, 4, 4);
        }
    }

    /** Thick ring with major ticks every 30° and minor ticks every 10°; diamond markers at N/E/S/W. */
    private void drawOuterRing(GraphicsContext gc, Color primary, Color dim,
                               double glow, double scale) {
        double r = R_OUTER_RING * scale;

        gc.setStroke(primary.deriveColor(0, 1, 1, 0.55 + glow * 0.45));
        gc.setLineWidth(2.5);
        gc.strokeOval(CX - r, CY - r, r * 2, r * 2);

        for (int i = 0; i < 36; i++) {
            double angle    = Math.toRadians(i * 10.0);
            boolean isMajor = (i % 3 == 0); // every 30°
            boolean isKey   = (i % 9 == 0); // every 90°

            double outerR  = r;
            double innerR  = r - (isKey ? 11.0 : isMajor ? 7.0 : 3.5);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            gc.setStroke(isKey   ? primary.deriveColor(0, 1, 1, 0.95)
                    : isMajor ? primary.deriveColor(0, 1, 1, 0.75)
                    : dim.deriveColor(0, 1, 1, 0.45));
            gc.setLineWidth(isKey ? 2.5 : isMajor ? 1.8 : 1.0);
            gc.strokeLine(CX + cos * innerR, CY + sin * innerR,
                    CX + cos * outerR, CY + sin * outerR);
        }

        // diamond markers at N/E/S/W
        gc.setFill(primary.deriveColor(0, 1, 1, 0.85 + glow * 0.15));
        for (int i = 0; i < 4; i++) {
            double angle = Math.toRadians(i * 90.0);
            double mx = CX + Math.cos(angle) * (r - 14.0);
            double my = CY + Math.sin(angle) * (r - 14.0);
            double[] dx = { mx, mx + 3.5, mx, mx - 3.5 };
            double[] dy = { my - 3.5, my, my + 3.5, my };
            gc.fillPolygon(dx, dy, 4);
        }
    }

    /** 6 thick arcs with cap lines + thin inner ghost ring for depth. */
    private void drawMiddleRing(GraphicsContext gc, Color primary, double glow, double scale) {
        double r = R_MID_RING * scale;

        gc.setStroke(primary.deriveColor(0, 1, 1, 0.45 + glow * 0.55));
        gc.setLineWidth(4.5);

        int    segments = 6;
        double arcSpan  = 360.0 / segments;
        double gapDeg   = 10.0;

        for (int i = 0; i < segments; i++) {
            double startAngle = i * arcSpan + gapDeg / 2.0 - 90.0;
            double sweepAngle = arcSpan - gapDeg;
            gc.strokeArc(CX - r, CY - r, r * 2, r * 2,
                    startAngle, sweepAngle,
                    javafx.scene.shape.ArcType.OPEN);

            double capAlpha = 0.5 + glow * 0.3;
            gc.setStroke(primary.deriveColor(0, 1, 0.8, capAlpha));
            gc.setLineWidth(1.5);
            for (int end = 0; end <= 1; end++) {
                double capAngle = Math.toRadians(startAngle + sweepAngle * end);
                double cos = Math.cos(capAngle);
                double sin = Math.sin(capAngle);
                gc.strokeLine(CX + cos * (r - 5), CY + sin * (r - 5),
                        CX + cos * (r + 5), CY + sin * (r + 5));
            }
            gc.setStroke(primary.deriveColor(0, 1, 1, 0.45 + glow * 0.55));
            gc.setLineWidth(4.5);
        }

        double rInner = r - 8.0; // inner ghost ring for depth
        gc.setStroke(primary.deriveColor(0, 1, 0.5, 0.2));
        gc.setLineWidth(1.0);
        gc.strokeOval(CX - rInner, CY - rInner, rInner * 2, rInner * 2);
    }

    /** Three trapezoid mount arms at 120° intervals, each with a triangular notch cut at the outer tip. */
    private void drawTriangleArms(GraphicsContext gc, Color primary, double glow) {
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(i * 120.0 + 90.0);
            double aLeft  = angle - Math.toRadians(15);
            double aRight = angle + Math.toRadians(15);
            double outerX1 = CX + Math.cos(aLeft)  * R_ARM_MOUNT;
            double outerY1 = CY + Math.sin(aLeft)  * R_ARM_MOUNT;
            double outerX2 = CX + Math.cos(aRight) * R_ARM_MOUNT;
            double outerY2 = CY + Math.sin(aRight) * R_ARM_MOUNT;

            double aLeftIn  = angle - Math.toRadians(5);
            double aRightIn = angle + Math.toRadians(5);
            double innerX1 = CX + Math.cos(aLeftIn)  * R_INNER_RING;
            double innerY1 = CY + Math.sin(aLeftIn)  * R_INNER_RING;
            double innerX2 = CX + Math.cos(aRightIn) * R_INNER_RING;
            double innerY2 = CY + Math.sin(aRightIn) * R_INNER_RING;

            gc.setFill(primary.deriveColor(0, 1, 0.25, 0.22));
            double[] xMain = { outerX1, outerX2, innerX2, innerX1 };
            double[] yMain = { outerY1, outerY2, innerY2, innerY1 };
            gc.fillPolygon(xMain, yMain, 4);
            gc.setStroke(primary.deriveColor(0, 1, 1, 0.60 + glow * 0.3));
            gc.setLineWidth(1.4);
            gc.strokePolygon(xMain, yMain, 4);

            // decorative triangular notch at outer tip
            double notchA  = angle;
            double notchR  = R_ARM_MOUNT - 6.0;
            double notchNx = CX + Math.cos(notchA) * notchR;
            double notchNy = CY + Math.sin(notchA) * notchR;

            double notchSideA1 = angle - Math.toRadians(10);
            double notchSideA2 = angle + Math.toRadians(10);
            double notchSideR  = R_ARM_MOUNT - 14.0;

            double notchS1x = CX + Math.cos(notchSideA1) * notchSideR;
            double notchS1y = CY + Math.sin(notchSideA1) * notchSideR;
            double notchS2x = CX + Math.cos(notchSideA2) * notchSideR;
            double notchS2y = CY + Math.sin(notchSideA2) * notchSideR;

            gc.setStroke(primary.deriveColor(0, 1, 1.2, 0.7 + glow * 0.2));
            gc.setFill(primary.deriveColor(0, 1, 0.5, 0.3));
            gc.setLineWidth(1.0);
            double[] nx = { notchNx, notchS1x, notchS2x };
            double[] ny = { notchNy, notchS1y, notchS2y };
            gc.fillPolygon(nx, ny, 3);
            gc.strokePolygon(nx, ny, 3);
        }
    }

    /** Hexagonal outline with a second inner hex for depth, plus a triangular notch at each of the 6 vertices. */
    private void drawInnerHexRing(GraphicsContext gc, Color primary, double glow) {
        int sides = 6;
        double[] xs = new double[sides];
        double[] ys = new double[sides];
        for (int i = 0; i < sides; i++) {
            double a = Math.toRadians(i * 60.0 + 30.0);
            xs[i] = CX + Math.cos(a) * R_INNER_RING;
            ys[i] = CY + Math.sin(a) * R_INNER_RING;
        }

        gc.setStroke(primary.deriveColor(0, 1, 1, 0.55 + glow * 0.4));
        gc.setLineWidth(1.8);
        gc.strokePolygon(xs, ys, sides);

        double innerScale = 0.72; // second inner hex for depth
        double[] xi = new double[sides];
        double[] yi = new double[sides];
        for (int i = 0; i < sides; i++) {
            double a = Math.toRadians(i * 60.0 + 30.0);
            xi[i] = CX + Math.cos(a) * R_INNER_RING * innerScale;
            yi[i] = CY + Math.sin(a) * R_INNER_RING * innerScale;
        }
        gc.setStroke(primary.deriveColor(0, 1, 0.7, 0.3));
        gc.setLineWidth(1.0);
        gc.strokePolygon(xi, yi, sides);

        for (int i = 0; i < sides; i++) { // triangular notch at each vertex pointing inward
            double nodeAngle = Math.toRadians(i * 60.0 + 30.0);
            double tipX = xs[i];
            double tipY = ys[i];
            double baseR = R_INNER_RING * 0.78;
            double b1a = nodeAngle - Math.toRadians(12);
            double b2a = nodeAngle + Math.toRadians(12);

            double[] nxp = {
                    tipX,
                    CX + Math.cos(b1a) * baseR,
                    CX + Math.cos(b2a) * baseR
            };
            double[] nyp = {
                    tipY,
                    CY + Math.sin(b1a) * baseR,
                    CY + Math.sin(b2a) * baseR
            };

            gc.setFill(primary.deriveColor(0, 1, 1.1, 0.7 + glow * 0.3));
            gc.setStroke(primary.deriveColor(0, 1, 1, 0.85));
            gc.setLineWidth(0.8);
            gc.fillPolygon(nxp, nyp, 3);
            gc.strokePolygon(nxp, nyp, 3);
        }
    }

    /** Outward expanding ripple ring; called twice for LISTENING double-ripple. */
    private void drawRipple(GraphicsContext gc, Color primary,
                            double normalizedRadius, double opacity) {
        double minR = R_CORE;
        double maxR = R_HALO * 0.9;
        double r    = minR + (maxR - minR) * normalizedRadius;

        gc.setStroke(primary.deriveColor(0, 1, 1, opacity * 0.65));
        gc.setLineWidth(2.0 * (1.0 - normalizedRadius * 0.55));
        gc.strokeOval(CX - r, CY - r, r * 2, r * 2);
    }

    /** Center Urdu letter ر — three passes: wide atmospheric glow, tight inner halo, then the letter itself. Brightness tracks glow intensity. */
    private void drawUrduLetter(GraphicsContext gc, Color coreColor, double glow) {
        gc.save();

        // pass 1: wide atmospheric glow
        double wideR = R_CORE * 1.8;
        RadialGradient wideGlow = new RadialGradient(
                0, 0, CX, CY, wideR, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, coreColor.deriveColor(0, 1, 1.1, glow * 0.55)),
                new Stop(0.45, coreColor.deriveColor(0, 1, 0.8, glow * 0.22)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(wideGlow);
        gc.fillOval(CX - wideR, CY - wideR, wideR * 2, wideR * 2);

        // pass 2: tight inner halo
        double tightR = R_CORE * 0.85;
        RadialGradient tightGlow = new RadialGradient(
                0, 0, CX, CY, tightR, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, coreColor.deriveColor(0, 1, 1.5, glow * 0.75)),
                new Stop(0.6, coreColor.deriveColor(0, 1, 1.1, glow * 0.35)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(tightGlow);
        gc.fillOval(CX - tightR, CY - tightR, tightR * 2, tightR * 2);

        // pass 3: the letter ر — font breathes (20→24px), color near-white when glowing
        double fontSize = 20.0 + glow * 4.0;
        double whiteness = 0.5 + glow * 0.5;
        Color letterColor = Color.color(
                Math.min(1.0, coreColor.getRed()   * (1.0 - whiteness) + whiteness),
                Math.min(1.0, coreColor.getGreen() * (1.0 - whiteness) + whiteness),
                Math.min(1.0, coreColor.getBlue()  * (1.0 - whiteness) + whiteness),
                0.6 + glow * 0.4
        );

        gc.setFont(Font.font(urduFont != null ? urduFont.getFamily() : "System", fontSize));
        gc.setFill(letterColor);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("ر", CX, CY-6);

        gc.restore();
    }

    /** factor=0→dark grey (off), factor=1→realColor; unchanged when ≥1.0 so non-boot paths are unaffected. */
    private static Color bootBlend(Color realColor, double factor) {
        if (factor >= 1.0) return realColor;
        final double offR = 0.18, offG = 0.20, offB = 0.25, offA = 0.55;
        double t = Math.max(0.0, factor);
        return Color.color(
                lerp(offR, realColor.getRed(),     t),
                lerp(offG, realColor.getGreen(),   t),
                lerp(offB, realColor.getBlue(),    t),
                lerp(offA, realColor.getOpacity(), t)
        );
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * Math.max(0.0, Math.min(1.0, t));
    }

    private void drawMinimizedDot(GraphicsContext gc, double glow) {
        double r = R_MINIMIZED;
        Color c  = themeColor;

        RadialGradient dot = new RadialGradient(
                0, 0, CX, CY, r * 2.5, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, c.deriveColor(0, 1, 1.2, 0.9)),
                new Stop(0.5, c.deriveColor(0, 1, 0.8, glow * 0.5)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(dot);
        gc.fillOval(CX - r * 2.5, CY - r * 2.5, r * 5, r * 5);

        RadialGradient core = new RadialGradient(
                0, 0, CX, CY, r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.WHITE.deriveColor(0, 1, 1, 0.95)),
                new Stop(0.5, c.deriveColor(0, 1, 1.3, 0.9)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(core);
        gc.fillOval(CX - r, CY - r, r * 2, r * 2);
    }
}
