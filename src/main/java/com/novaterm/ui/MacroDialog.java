package com.novaterm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

public class MacroDialog extends Dialog<Void> {
    public MacroDialog(MacroManager manager, Consumer<MacroManager.Macro> onRun) {
        setTitle("Macros");
        setHeaderText(null);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(400);

        ListView<MacroManager.Macro> list = new ListView<>();
        list.getItems().setAll(manager.getMacros());
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MacroManager.Macro m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) { setText(null); return; }
                setText(m.name() + "  (" + m.steps().size() + " steps)");
            }
        });
        list.setPrefHeight(200);

        Button runBtn = new Button("▶ Run");
        runBtn.setDisable(true);
        Button deleteBtn = new Button("Delete");
        deleteBtn.setDisable(true);
        deleteBtn.setStyle("-fx-text-fill: #f38ba8;");

        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            runBtn.setDisable(sel == null);
            deleteBtn.setDisable(sel == null);
        });

        runBtn.setOnAction(e -> {
            MacroManager.Macro sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                close();
                onRun.accept(sel);
            }
        });

        deleteBtn.setOnAction(e -> {
            MacroManager.Macro sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                manager.deleteMacro(sel);
                list.getItems().remove(sel);
            }
        });

        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                MacroManager.Macro sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) { close(); onRun.accept(sel); }
            }
        });

        HBox toolbar = new HBox(8, runBtn, deleteBtn);
        toolbar.setPadding(new Insets(8, 0, 0, 0));

        Label hint = new Label("Double-click or press ▶ to replay a macro in the active terminal.");
        hint.setStyle("-fx-text-fill:#6c7086; -fx-font-size:11px;");

        VBox content = new VBox(8, list, toolbar, hint);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
    }
}
