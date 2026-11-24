package com.novaterm.ui;

import com.novaterm.session.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

public class SessionDialog extends Dialog<Session> {

    public SessionDialog(Session existing) {
        setTitle(existing == null ? "New Session" : "Edit Session");
        setHeaderText(null);

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        TextField nameField = new TextField(existing != null ? existing.getName() : "");
        nameField.setPromptText("My Server");
        TextField hostField = new TextField(existing != null ? existing.getHost() : "");
        hostField.setPromptText("hostname or IP");
        TextField portField = new TextField(existing != null ? String.valueOf(existing.getPort()) : "22");
        TextField userField = new TextField(existing != null ? existing.getUsername() : "");
        userField.setPromptText("root");
        ComboBox<Session.AuthType> authCombo = new ComboBox<>();
        authCombo.getItems().addAll(Session.AuthType.values());
        authCombo.setValue(existing != null ? existing.getAuthType() : Session.AuthType.PASSWORD);
        TextField keyField = new TextField(existing != null && existing.getPrivateKeyPath() != null
                ? existing.getPrivateKeyPath() : "");
        keyField.setPromptText("~/.ssh/id_rsa");
        keyField.disableProperty().bind(
                authCombo.valueProperty().isNotEqualTo(Session.AuthType.KEY));
        TextField groupField = new TextField(existing != null ? (existing.getGroup() != null ? existing.getGroup() : "") : "");
        groupField.setPromptText("Optional group");
        TextArea notesArea = new TextArea(existing != null && existing.getNotes() != null ? existing.getNotes() : "");
        notesArea.setPromptText("Notes, hints, sudo password hint…");
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        TextField startupField = new TextField(existing != null && existing.getStartupCommand() != null
                ? existing.getStartupCommand() : "");
        startupField.setPromptText("e.g. tmux new -As main  (run on connect)");

        TextArea envVarsArea = new TextArea(existing != null && existing.getEnvVars() != null
                ? existing.getEnvVars() : "");
        envVarsArea.setPromptText("KEY=value  (one per line, injected as export commands)");
        envVarsArea.setPrefRowCount(2);
        envVarsArea.setWrapText(false);

        TextField jumpHostField = new TextField(existing != null && existing.getJumpHost() != null
                ? existing.getJumpHost() : "");
        jumpHostField.setPromptText("bastion.example.com (blank = direct)");
        TextField jumpPortField = new TextField(existing != null && existing.getJumpPort() != 22
                ? String.valueOf(existing.getJumpPort()) : "22");
        jumpPortField.setPrefWidth(60);
        TextField jumpUserField = new TextField(existing != null && existing.getJumpUsername() != null
                ? existing.getJumpUsername() : "");
        jumpUserField.setPromptText(System.getProperty("user.name"));

        // Tab color picker
        javafx.scene.control.ColorPicker colorPicker = new javafx.scene.control.ColorPicker();
        colorPicker.setId("session-color-picker");
        if (existing != null && existing.getColor() != null && !existing.getColor().isBlank()) {
            try { colorPicker.setValue(Color.web(existing.getColor())); }
            catch (Exception ignored) { colorPicker.setValue(Color.TRANSPARENT); }
        } else {
            colorPicker.setValue(Color.TRANSPARENT);
        }
        colorPicker.getStyleClass().add("button");
        Label colorLabel = new Label("Tab color:");
        Label colorHint = new Label("(transparent = no dot)");
        colorHint.setStyle("-fx-text-fill:#6c7086; -fx-font-size:10px;");
        HBox colorRow = new HBox(6, colorPicker, colorHint);
        colorRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        grid.addRow(0, new Label("Name:"), nameField);
        grid.addRow(1, new Label("Host:"), hostField);
        grid.addRow(2, new Label("Port:"), portField);
        grid.addRow(3, new Label("Username:"), userField);
        grid.addRow(4, new Label("Auth:"), authCombo);
        grid.addRow(5, new Label("Key file:"), keyField);
        grid.addRow(6, new Label("Group:"), groupField);
        grid.addRow(7, colorLabel, colorRow);
        grid.addRow(8, new Label("Startup cmd:"), startupField);
        HBox jumpRow = new HBox(6, jumpHostField, new Label(":"), jumpPortField, new Label("user:"), jumpUserField);
        jumpRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.addRow(9, new Label("Jump host:"), jumpRow);
        grid.addRow(10, new Label("Env vars:"), envVarsArea);
        grid.addRow(11, new Label("Notes:"), notesArea);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(480);

        // Validation
        getDialogPane().lookupButton(saveBtn).setDisable(true);
        hostField.textProperty().addListener((obs, o, n) ->
                getDialogPane().lookupButton(saveBtn).setDisable(n.trim().isEmpty()));
        userField.textProperty().addListener((obs, o, n) ->
                getDialogPane().lookupButton(saveBtn).setDisable(
                        hostField.getText().trim().isEmpty() || n.trim().isEmpty()));

        setResultConverter(btn -> {
            if (btn != saveBtn) return null;
            Session s = existing != null ? existing : new Session();
            s.setName(nameField.getText().trim().isEmpty()
                    ? userField.getText().trim() + "@" + hostField.getText().trim()
                    : nameField.getText().trim());
            s.setHost(hostField.getText().trim());
            try { s.setPort(Integer.parseInt(portField.getText().trim())); }
            catch (NumberFormatException e) { s.setPort(22); }
            s.setUsername(userField.getText().trim());
            s.setAuthType(authCombo.getValue());
            if (!keyField.getText().isBlank()) s.setPrivateKeyPath(keyField.getText().trim());
            if (!groupField.getText().isBlank()) s.setGroup(groupField.getText().trim());
            s.setNotes(notesArea.getText().trim().isEmpty() ? null : notesArea.getText().trim());
            s.setStartupCommand(startupField.getText().trim().isEmpty() ? null : startupField.getText().trim());
            s.setEnvVars(envVarsArea.getText().trim().isEmpty() ? null : envVarsArea.getText().trim());
            String jh = jumpHostField.getText().trim();
            if (!jh.isEmpty()) {
                s.setJumpHost(jh);
                try { s.setJumpPort(Integer.parseInt(jumpPortField.getText().trim())); }
                catch (NumberFormatException ex) { s.setJumpPort(22); }
                String ju = jumpUserField.getText().trim();
                s.setJumpUsername(ju.isEmpty() ? System.getProperty("user.name") : ju);
            } else {
                s.setJumpHost(null);
                s.setJumpPort(22);
                s.setJumpUsername(null);
            }
            Color c = colorPicker.getValue();
            if (c != null && c.getOpacity() > 0.01) {
                s.setColor(String.format("#%02x%02x%02x",
                        (int)(c.getRed() * 255), (int)(c.getGreen() * 255), (int)(c.getBlue() * 255)));
            } else {
                s.setColor(null);
            }
            return s;
        });
    }
}
