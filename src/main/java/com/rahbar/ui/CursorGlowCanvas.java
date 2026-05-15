package com.rahbar.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.Random;

public class CursorGlowCanvas {

    private static final int    NODE_COUNT  = 50;
    private static final double GLOW_RADIUS = 200.0;
    private static final double LINE_RADIUS = 160.0;
    private static final double PEER_RADIUS = 90.0;

    private final Canvas        canvas;
    private final AnimationTimer timer;
    private final double[]      nx = new double[NODE_COUNT];
    private final double[]      ny = new double[NODE_COUNT];

    private double cursorX  = -1;
    private double cursorY  = -1;
    private double timeS    =  0;
    private long   lastNanos = -1;

    public CursorGlowCanvas(Canvas canvas) {
        this.canvas = canvas;

        Random rng = new Random(17);
        for (int i = 0; i < NODE_COUNT; i++) {
            nx[i] = 0.03 + rng.nextDouble() * 0.94;
            ny[i] = 0.03 + rng.nextDouble() * 0.94;
        }

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanos < 0) { lastNanos = now; return; }
                double dt = Math.min((now - lastNanos) / 1_000_000_000.0, 0.05);
                lastNanos = now;
                timeS += dt;
                draw();
            }
        };
    }

    public void updateCursor(double x, double y) { cursorX = x; cursorY = y; }

    public void clearCursor() {
        cursorX = -1;
        cursorY = -1;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    public void start() { lastNanos = -1; timer.start(); }
    public void stop()  { timer.stop(); clearCursor(); }


    private void draw() {
        double W = canvas.getWidth();
        double H = canvas.getHeight();
        if (W <= 0 || H <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, W, H);

        if (cursorX < 0) return;

        Color accent = accentColor();
        double pulse = 0.5 + 0.5 * Math.sin(timeS * 2.5); // 0..1

        double[] ax = new double[NODE_COUNT];
        double[] ay = new double[NODE_COUNT];
        double[] brt = new double[NODE_COUNT];
        for (int i = 0; i < NODE_COUNT; i++) {
            ax[i] = nx[i] * W;
            ay[i] = ny[i] * H;
            double d = dist(ax[i], ay[i], cursorX, cursorY);
            brt[i] = d < GLOW_RADIUS ? Math.pow(1.0 - d / GLOW_RADIUS, 1.8) : 0.0;
        }

        for (int i = 0; i < NODE_COUNT; i++) { // cursor → node lines
            if (brt[i] < 0.04) continue;
            if (dist(ax[i], ay[i], cursorX, cursorY) > LINE_RADIUS) continue;
            gc.setStroke(accent.deriveColor(0, 1, 1, brt[i] * 0.38));
            gc.setLineWidth(0.8);
            gc.strokeLine(cursorX, cursorY, ax[i], ay[i]);
        }

        for (int i = 0; i < NODE_COUNT; i++) { // node → node peer lines
            if (brt[i] < 0.15) continue;
            for (int j = i + 1; j < NODE_COUNT; j++) {
                if (brt[j] < 0.15) continue;
                double d = dist(ax[i], ay[i], ax[j], ay[j]);
                if (d > PEER_RADIUS) continue;
                double a = Math.min(brt[i], brt[j]) * 0.22 * (1.0 - d / PEER_RADIUS);
                gc.setStroke(accent.deriveColor(0, 1, 1, a));
                gc.setLineWidth(0.5);
                gc.strokeLine(ax[i], ay[i], ax[j], ay[j]);
            }
        }

        for (int i = 0; i < NODE_COUNT; i++) { // node dots
            double b = brt[i];
            double r = 1.5 + b * 2.8;
            gc.setFill(accent.deriveColor(0, 1, 1, 0.07 + b * 0.80));
            gc.fillOval(ax[i] - r, ay[i] - r, r * 2, r * 2);
            if (b > 0.35) {
                gc.setFill(accent.deriveColor(0, 1, 1, b * 0.11));
                gc.fillOval(ax[i] - r * 3.2, ay[i] - r * 3.2, r * 6.4, r * 6.4);
            }
        }

        // Cursor rings + dot
        double rr = 13 + pulse * 6;
        gc.setStroke(accent.deriveColor(0, 1, 1, 0.30 + pulse * 0.30));
        gc.setLineWidth(1.1);
        gc.strokeOval(cursorX - rr, cursorY - rr, rr * 2, rr * 2);
        gc.setStroke(accent.deriveColor(0, 1, 1, 0.15));
        gc.setLineWidth(0.7);
        gc.strokeOval(cursorX - 6, cursorY - 6, 12, 12);
        gc.setFill(accent.deriveColor(0, 1, 1.1, 0.90));
        gc.fillOval(cursorX - 2.5, cursorY - 2.5, 5, 5);
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static Color accentColor() {
        ThemeManager.Theme t = ThemeManager.getInstance().getCurrentTheme();
        return switch (t) {
            case BLACK_BLUE     -> Color.web("#3D7EFF");
            case CHARCOAL_GREEN -> Color.web("#39FF14");
            case PURPLE_GOLD    -> Color.web("#FFD700");
            case WHITE_BLACK    -> Color.web("#333333");
            default             -> Color.web("#00B4D8");
        };
    }
}
