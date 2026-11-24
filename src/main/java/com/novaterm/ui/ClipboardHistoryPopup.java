package com.novaterm.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * Floating popup for clipboard history. Triggered by Cmd+Shift+V.
 * Shows recent clipboard entries; selecting one sends it to the terminal.
 */
public class ClipboardHistoryPopup {

    private final Stage stage;
    private final TextField searchField;
    private final ListView<String> listView;
    private final ObservableList<String> allItems = FXCollections.observableArrayList();
    private final FilteredList<String> filteredItems = new FilteredList<>(allItems);
    private final Consumer<String> onPaste;

    public ClipboardHistoryPopup(Consumer<String> onPaste) {
        this.onPaste = onPaste;
        stage = new Stage(StageStyle.UNDECORATED);
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setWidth(500);
        stage.setHeight(360);

        searchField = new TextField();
        searchField.setPromptText("Search clipboard history…");
        searchField.setId("palette-search");

        listView = new ListView<>(filteredItems);
        listView.setId("palette-list");
        listView.setCellFactory(lv -> new ClipCell());

        searchField.textProperty().addListener((obs, o, q) -> {
            String lc = q.trim().toLowerCase();
            filteredItems.setPredicate(lc.isEmpty() ? null : s -> s.toLowerCase().contains(lc));
        });

        // Keyboard navigation
        searchField.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (!filteredItems.isEmpty()) {
                        listView.requestFocus();
                        listView.getSelectionModel().select(0);
                        listView.scrollTo(0);
                    }
                }
                case ENTER -> pasteSelected();
                case ESCAPE -> stage.hide();
            }
        });
        listView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) pasteSelected();
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
            if (e.getCode() == KeyCode.UP && listView.getSelectionModel().getSelectedIndex() == 0) {
                searchField.requestFocus();
            }
        });
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) pasteSelected();
        });

        Label header = new Label("Clipboard History");
        header.setStyle("-fx-text-fill:#6c7086; -fx-font-size:11px; -fx-padding:6 14 4 14;");

        VBox root = new VBox(header, searchField, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);
        root.setStyle("-fx-background-color:#1e1e2e; -fx-border-color:#45475a; -fx-border-width:1; -fx-border-radius:8; -fx-background-radius:8;");

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/novaterm/styles.css").toExternalForm());
        scene.setFill(null);
        stage.setScene(scene);

        // Hide on focus loss
        stage.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) stage.hide();
        });
    }

    private void pasteSelected() {
        String sel = listView.getSelectionModel().getSelectedItem();
        if (sel != null) {
            stage.hide();
            onPaste.accept(sel);
        }
    }

    public void show(Window owner) {
        List<String> history = ClipboardHistory.get().getEntries();
        allItems.setAll(history);
        filteredItems.setPredicate(null);
        searchField.clear();

        if (history.isEmpty()) {
            allItems.add("(no clipboard history yet)");
        }

        // Center over owner
        if (owner != null) {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 3);
        }
        stage.show();
        Platform.runLater(searchField::requestFocus);
        if (!filteredItems.isEmpty()) {
            listView.getSelectionModel().select(0);
        }
    }

    private static class ClipCell extends ListCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            // Show first line as title, rest as preview
            String[] lines = item.split("\n", 3);
            String title = lines[0].length() > 80 ? lines[0].substring(0, 80) + "…" : lines[0];
            if (lines.length == 1) {
                setText(title);
                setGraphic(null);
                setTooltip(item.length() > 200 ? new Tooltip(item.substring(0, 200) + "…") : null);
            } else {
                Label titleLbl = new Label(title);
                titleLbl.setStyle("-fx-text-fill:#cdd6f4; -fx-font-size:12px;");
                String preview = lines.length > 2
                        ? lines[1] + "\n" + lines[2].substring(0, Math.min(lines[2].length(), 60)) + "…"
                        : lines[1];
                if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
                Label previewLbl = new Label(preview);
                previewLbl.setStyle("-fx-text-fill:#6c7086; -fx-font-size:10px;");
                setGraphic(new VBox(2, titleLbl, previewLbl));
                setText(null);
                setTooltip(new Tooltip(item.length() > 500 ? item.substring(0, 500) + "…" : item));
            }
        }
    }
}
