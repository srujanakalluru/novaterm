package com.novaterm.ui;

import com.novaterm.ssh.KnownHostsVerifier;
import com.novaterm.ssh.SSHManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.Map;

/**
 * Dialog to view and manage trusted SSH host keys.
 */
public class KnownHostsDialog extends Dialog<Void> {

    public KnownHostsDialog() {
        setTitle("Known Hosts");
        setHeaderText("Trusted SSH Server Keys");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(560);

        KnownHostsVerifier verifier = SSHManager.get().getKnownHostsVerifier();

        TableView<Map.Entry<String, String>> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(300);

        TableColumn<Map.Entry<String, String>, String> hostCol = new TableColumn<>("Host");
        hostCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getKey()));
        hostCol.setPrefWidth(200);

        TableColumn<Map.Entry<String, String>, String> fpCol = new TableColumn<>("Fingerprint (SHA-256)");
        fpCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getValue()));
        fpCol.setPrefWidth(340);

        table.getColumns().addAll(hostCol, fpCol);

        Runnable reload = () -> {
            table.getItems().setAll(verifier.getEntries().entrySet());
        };
        reload.run();

        Button deleteBtn = new Button("Delete Selected");
        deleteBtn.setStyle("-fx-text-fill:#f38ba8;");
        deleteBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> deleteBtn.setDisable(n == null));
        deleteBtn.setOnAction(e -> {
            Map.Entry<String, String> sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove key for:\n" + sel.getKey());
            confirm.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> {
                verifier.remove(sel.getKey());
                reload.run();
            });
        });

        Label hint = new Label("Double-click a host to copy its fingerprint.");
        hint.setStyle("-fx-text-fill:#6c7086; -fx-font-size:10px;");

        table.setRowFactory(tv -> {
            TableRow<Map.Entry<String, String>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(row.getItem().getValue());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                }
            });
            return row;
        });

        VBox content = new VBox(8, table, deleteBtn, hint);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
    }
}
