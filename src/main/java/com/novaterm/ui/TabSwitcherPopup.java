package com.novaterm.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * Floating popup for switching between open terminal tabs. Triggered by Cmd+Shift+E.
 * Shows tab titles with a fuzzy filter.
 */
public class TabSwitcherPopup {

    public record TabEntry(String title, String subtitle, int index) {}

    private final Stage stage;
    private final TextField searchField;
    private final ListView<TabEntry> listView;
    private final ObservableList<TabEntry> allItems = FXCollections.observableArrayList();
    private final FilteredList<TabEntry> filteredItems = new FilteredList<>(allItems);
    private final Consumer<Integer> onSelect;

    public TabSwitcherPopup(Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
        stage = new Stage(StageStyle.UNDECORATED);
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setWidth(440);
        stage.setHeight(320);

        searchField = new TextField();
        searchField.setPromptText("Search open tabs…");
        searchField.setId("palette-search");

        listView = new ListView<>(filteredItems);
        listView.setId("palette-list");
        listView.setCellFactory(lv -> new TabCell());

        searchField.textProperty().addListener((obs, o, q) -> {
            String lc = q.trim().toLowerCase();
            filteredItems.setPredicate(lc.isEmpty() ? null :
                    t -> t.title().toLowerCase().contains(lc) || t.subtitle().toLowerCase().contains(lc));
            if (!filteredItems.isEmpty()) listView.getSelectionModel().select(0);
        });

        searchField.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (!filteredItems.isEmpty()) {
                        listView.requestFocus();
                        listView.getSelectionModel().select(0);
                    }
                }
                case ENTER -> selectCurrent();
                case ESCAPE -> stage.hide();
            }
        });
        listView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) selectCurrent();
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
            if (e.getCode() == KeyCode.UP && listView.getSelectionModel().getSelectedIndex() == 0)
                searchField.requestFocus();
        });
        listView.setOnMouseClicked(e -> { if (e.getClickCount() == 2) selectCurrent(); });

        Label header = new Label("Switch Tab");
        header.setStyle("-fx-text-fill:#6c7086; -fx-font-size:11px; -fx-padding:6 14 4 14;");

        VBox root = new VBox(header, searchField, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);
        root.setStyle("-fx-background-color:#1e1e2e; -fx-border-color:#45475a; -fx-border-width:1; " +
                      "-fx-border-radius:8; -fx-background-radius:8;");

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/novaterm/styles.css").toExternalForm());
        scene.setFill(null);
        stage.setScene(scene);

        stage.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) stage.hide();
        });
    }

    private void selectCurrent() {
        TabEntry sel = listView.getSelectionModel().getSelectedItem();
        if (sel != null) {
            stage.hide();
            onSelect.accept(sel.index());
        }
    }

    public void show(Window owner, List<TabEntry> tabs) {
        allItems.setAll(tabs);
        filteredItems.setPredicate(null);
        searchField.clear();

        if (owner != null) {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 3);
        }
        stage.show();
        Platform.runLater(searchField::requestFocus);
        if (!filteredItems.isEmpty()) listView.getSelectionModel().select(0);
    }

    private static class TabCell extends ListCell<TabEntry> {
        @Override
        protected void updateItem(TabEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); setText(null); return; }
            Label titleLbl = new Label(item.title());
            titleLbl.setStyle("-fx-text-fill:#cdd6f4; -fx-font-size:12px;");
            Label subtitleLbl = new Label(item.subtitle());
            subtitleLbl.setStyle("-fx-text-fill:#6c7086; -fx-font-size:10px;");
            Label idxLbl = new Label(String.valueOf(item.index() + 1));
            idxLbl.setStyle("-fx-text-fill:#45475a; -fx-font-size:10px; -fx-min-width:16; -fx-alignment:center-right;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(8, new VBox(2, titleLbl, subtitleLbl), spacer, idxLbl);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }
    }
}
