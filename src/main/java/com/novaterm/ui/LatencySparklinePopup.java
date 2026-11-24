package com.novaterm.ui;

import com.novaterm.session.Session;
import com.novaterm.ssh.PingHistory;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;

import java.util.List;

/**
 * A lightweight Popup that draws a sparkline of recent ping samples for a session.
 * Show it on hover over the latency badge in the session panel.
 */
public class LatencySparklinePopup extends Popup {

    private static final int W = 220;
    private static final int H = 60;
    private static final Color BG  = Color.web("#1e1e2e");
    private static final Color LINE = Color.web("#89b4fa");
    private static final Color FILL = Color.web("#89b4fa44");
    private static final Color TEXT = Color.web("#cdd6f4");
    private static final Color DIM  = Color.web("#6c7086");

    private final Canvas canvas = new Canvas(W, H);
    private final Label statsLabel = new Label();

    public LatencySparklinePopup() {
        setAutoHide(true);
        setHideOnEscape(true);

        statsLabel.setStyle("-fx-text-fill:#a6adc8; -fx-font-size:10px; -fx-font-family:'SF Mono',Menlo,monospace;");

        VBox box = new VBox(4, canvas, statsLabel);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color:#1e1e2e; -fx-background-radius:6; " +
                     "-fx-effect:dropshadow(gaussian,#00000066,10,0,0,2);");
        box.setPrefWidth(W + 16);
        getContent().add(box);
    }

    public void showFor(Session session, javafx.scene.Node anchor, double screenX, double screenY) {
        draw(session);
        show(anchor, screenX + 8, screenY - H - 40);
    }

    private void draw(Session session) {
        List<Long> samples = PingHistory.get().getSamples(session);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(BG);
        gc.fillRoundRect(0, 0, W, H, 6, 6);

        if (samples.isEmpty()) {
            gc.setFill(DIM);
            gc.setFont(javafx.scene.text.Font.font("SF Mono", 11));
            gc.fillText("No data yet", 8, H / 2.0 + 4);
            statsLabel.setText("");
            return;
        }

        long minMs = samples.stream().mapToLong(Long::longValue).min().orElse(1);
        long maxMs = samples.stream().mapToLong(Long::longValue).max().orElse(1);
        long avgMs = (long) samples.stream().mapToLong(Long::longValue).average().orElse(0);

        double range = Math.max(maxMs - minMs, 1);
        double padX = 4, padY = 6;
        double usableW = W - padX * 2;
        double usableH = H - padY * 2;

        int n = samples.size();
        double stepX = usableW / Math.max(n - 1, 1);

        // Build path
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = padX + i * stepX;
            double norm = (samples.get(i) - minMs) / range;
            ys[i] = padY + usableH * (1.0 - norm);
        }

        // Fill under curve
        gc.setFill(FILL);
        gc.beginPath();
        gc.moveTo(xs[0], H - padY);
        gc.lineTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) gc.lineTo(xs[i], ys[i]);
        gc.lineTo(xs[n - 1], H - padY);
        gc.closePath();
        gc.fill();

        // Line
        gc.setStroke(LINE);
        gc.setLineWidth(1.5);
        gc.beginPath();
        gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) gc.lineTo(xs[i], ys[i]);
        gc.stroke();

        // Last point dot
        gc.setFill(LINE);
        gc.fillOval(xs[n - 1] - 3, ys[n - 1] - 3, 6, 6);

        // Stats
        statsLabel.setText(String.format("min %dms  avg %dms  max %dms  (%d samples)", minMs, avgMs, maxMs, n));
    }
}
