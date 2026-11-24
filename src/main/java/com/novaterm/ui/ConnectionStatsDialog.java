package com.novaterm.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;

/**
 * Dialog showing live connection statistics for all open SSH terminal tabs.
 * Refreshes every second while open.
 */
public class ConnectionStatsDialog extends Dialog<Void> {

    public record TabStat(
            String title,
            String host,
            String duration,
            String rxBytes,
            String txBytes,
            String reconnects,
            String cwd
    ) {}

    private final TableView<TabStat> table = new TableView<>();
    private final Timeline refresh;
    private Runnable dataSupplier;

    public ConnectionStatsDialog(java.util.function.Supplier<List<TabStat>> statsSupplier) {
        setTitle("Connection Statistics");
        setHeaderText(null);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(680);
        getDialogPane().setPrefHeight(380);

        TableColumn<TabStat, String> titleCol = col("Tab", TabStat::title, 120);
        TableColumn<TabStat, String> hostCol  = col("Host", TabStat::host, 140);
        TableColumn<TabStat, String> durCol   = col("Duration", TabStat::duration, 90);
        TableColumn<TabStat, String> rxCol    = col("↓ Received", TabStat::rxBytes, 90);
        TableColumn<TabStat, String> txCol    = col("↑ Sent", TabStat::txBytes, 80);
        TableColumn<TabStat, String> rcCol    = col("Reconnects", TabStat::reconnects, 80);
        TableColumn<TabStat, String> cwdCol   = col("CWD", TabStat::cwd, 160);
        table.getColumns().addAll(titleCol, hostCol, durCol, rxCol, txCol, rcCol, cwdCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No active SSH connections"));
        table.setStyle("-fx-font-family:'SF Mono',Menlo,monospace; -fx-font-size:11px;");

        Label hint = new Label("Updates every second while this dialog is open.");
        hint.setStyle("-fx-text-fill:#848d97; -fx-font-size:10px;");

        VBox content = new VBox(6, table, hint);
        content.setPadding(new Insets(8));
        VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(content);

        refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            List<TabStat> data = statsSupplier.get();
            table.getItems().setAll(data);
        }));
        refresh.setCycleCount(Animation.INDEFINITE);

        setOnShown(e -> {
            table.getItems().setAll(statsSupplier.get());
            refresh.play();
        });
        setOnHidden(e -> refresh.stop());
    }

    private static TableColumn<TabStat, String> col(String header,
            java.util.function.Function<TabStat, String> getter, double width) {
        TableColumn<TabStat, String> c = new TableColumn<>(header);
        c.setCellValueFactory(d -> new SimpleStringProperty(getter.apply(d.getValue())));
        c.setPrefWidth(width);
        return c;
    }
}
