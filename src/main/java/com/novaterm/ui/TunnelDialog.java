package com.novaterm.ui;

import com.novaterm.session.TunnelConfig;
import com.novaterm.ssh.TunnelManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.apache.sshd.client.session.ClientSession;

import java.util.function.Consumer;

public class TunnelDialog extends Dialog<Void> {

    public TunnelDialog(ClientSession session, TunnelManager tunnelManager) {
        this(session, tunnelManager, null);
    }

    public TunnelDialog(ClientSession session, TunnelManager tunnelManager,
                        Consumer<TunnelConfig> onSave) {
        setTitle("Port Forwarding");
        setHeaderText(null);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(600);

        // ── Active tunnels table ─────────────────────────────────────────────
        TableView<TunnelManager.TunnelEntry> table = new TableView<>();
        TableColumn<TunnelManager.TunnelEntry, String> labelCol = new TableColumn<>("Label");
        labelCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().label()));
        TableColumn<TunnelManager.TunnelEntry, String> localCol = new TableColumn<>("Local Port");
        localCol.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().localPort())));
        TableColumn<TunnelManager.TunnelEntry, String> remoteCol = new TableColumn<>("Remote");
        remoteCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().remoteHost() + ":" + d.getValue().remotePort()));
        table.getColumns().addAll(labelCol, localCol, remoteCol);
        table.getItems().addAll(tunnelManager.getTunnels());
        table.setPrefHeight(160);

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                removeBtn.setDisable(n == null));
        removeBtn.setOnAction(e -> {
            TunnelManager.TunnelEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                tunnelManager.close(sel);
                table.getItems().remove(sel);
            }
        });

        // ── Add new tunnel form ──────────────────────────────────────────────
        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(8);
        form.setPadding(new Insets(12, 0, 0, 0));

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("Local (L)", "Remote (R)");
        typeCombo.setValue("Local (L)");

        TextField labelField = new TextField();
        labelField.setPromptText("e.g. dev-db");
        TextField localPortField = new TextField();
        localPortField.setPromptText("5432");
        TextField remoteHostField = new TextField();
        remoteHostField.setPromptText("localhost");
        TextField remotePortField = new TextField();
        remotePortField.setPromptText("5432");

        form.addRow(0, new Label("Type:"), typeCombo, new Label("Label:"), labelField);
        form.addRow(1, new Label("Local port:"), localPortField, new Label("Remote host:"), remoteHostField);
        form.addRow(2, new Label(""), new Label(""), new Label("Remote port:"), remotePortField);

        CheckBox saveCheck = new CheckBox("Save with session (auto-start on reconnect)");
        saveCheck.setVisible(onSave != null);
        saveCheck.setManaged(onSave != null);

        Button addBtn = new Button("Add Tunnel");
        addBtn.setDefaultButton(false);
        addBtn.setOnAction(e -> {
            try {
                String label = labelField.getText().isBlank() ? "tunnel" : labelField.getText().trim();
                int localPort = Integer.parseInt(localPortField.getText().trim());
                String remoteHost = remoteHostField.getText().isBlank() ? "localhost" : remoteHostField.getText().trim();
                int remotePort = Integer.parseInt(remotePortField.getText().trim());
                boolean isRemote = !typeCombo.getValue().startsWith("Local");
                TunnelManager.TunnelEntry entry;
                if (!isRemote) {
                    entry = tunnelManager.addLocalForward(session, label, localPort, remoteHost, remotePort);
                } else {
                    entry = tunnelManager.addRemoteForward(session, label, remotePort, remoteHost, localPort);
                }
                table.getItems().add(entry);
                if (onSave != null && saveCheck.isSelected()) {
                    onSave.accept(new TunnelConfig(label, isRemote, localPort, remoteHost, remotePort));
                }
                labelField.clear(); localPortField.clear(); remoteHostField.clear(); remotePortField.clear();
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Invalid port number.").show();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to add tunnel: " + ex.getMessage()).show();
            }
        });

        VBox content = new VBox(8,
                new Label("Active tunnels:"),
                table, removeBtn,
                new Separator(),
                new Label("Add new tunnel:"),
                form, saveCheck, addBtn);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
    }
}
