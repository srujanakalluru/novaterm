package com.novaterm.ui;

import com.novaterm.session.Session;
import com.novaterm.session.SessionStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CommandPalette {
    private final Stage stage;
    private final ListView<CommandItem> listView;
    private final TextField searchField;
    private final SessionStore store;
    private final Consumer<Session> openSession;
    private final List<CommandItem> builtinCommands = new ArrayList<>();
    private final javafx.collections.ObservableList<CommandItem> allItems =
            FXCollections.observableArrayList();
    private FilteredList<CommandItem> filtered;

    record CommandItem(String label, String sub, String category, Runnable action) {
        @Override public String toString() { return label; }
    }

    public CommandPalette(Stage owner, SessionStore store, Consumer<Session> openSession) {
        this.store = store;
        this.openSession = openSession;

        stage = new Stage(StageStyle.UNDECORATED);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);

        searchField = new TextField();
        searchField.setPromptText("Search sessions or commands…");
        searchField.setId("palette-search");

        listView = new ListView<>();
        listView.setId("palette-list");
        listView.setPrefHeight(300);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(CommandItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Label main = new Label(item.label());
                main.setStyle("-fx-text-fill:#cdd6f4; -fx-font-size:13px;");
                Label sub = new Label(item.sub());
                sub.setStyle("-fx-text-fill:#6c7086; -fx-font-size:11px;");
                Label cat = new Label(item.category());
                cat.setStyle("-fx-text-fill:#89b4fa; -fx-font-size:10px; -fx-background-color:#1e3a5f; " +
                        "-fx-background-radius:3; -fx-padding:1 5 1 5;");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox top = new HBox(6, main, spacer, cat);
                top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                VBox box = new VBox(2, top, sub);
                setGraphic(box);
                setText(null);
            }
        });

        filtered = new FilteredList<>(allItems, p -> true);
        listView.setItems(filtered);

        searchField.textProperty().addListener((obs, old, text) -> {
            String lower = text.toLowerCase().trim();
            filtered.setPredicate(item -> lower.isEmpty()
                    || item.label().toLowerCase().contains(lower)
                    || item.sub().toLowerCase().contains(lower)
                    || item.category().toLowerCase().contains(lower));
            if (!filtered.isEmpty()) listView.getSelectionModel().selectFirst();
        });

        // Keyboard navigation
        searchField.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    int next = listView.getSelectionModel().getSelectedIndex() + 1;
                    if (next < listView.getItems().size()) listView.getSelectionModel().select(next);
                    e.consume();
                }
                case UP -> {
                    int prev = listView.getSelectionModel().getSelectedIndex() - 1;
                    if (prev >= 0) listView.getSelectionModel().select(prev);
                    e.consume();
                }
                case ENTER -> runSelected();
                case ESCAPE -> stage.hide();
                default -> {}
            }
        });
        listView.setOnMouseClicked(e -> { if (e.getClickCount() == 2) runSelected(); });
        listView.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) runSelected(); });

        VBox root = new VBox(searchField, listView);
        root.setStyle("-fx-background-color:#1e1e2e; -fx-border-color:#45475a; -fx-border-width:1; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 4);");
        root.setPadding(new Insets(0));

        Scene scene = new Scene(root, 520, 360);
        scene.getStylesheets().add(getClass().getResource("/com/novaterm/styles.css").toExternalForm());
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.hide(); });

        stage.setScene(scene);
    }

    /** Register built-in actions (called after MainWindow sets up all handlers). */
    public void addCommand(String label, String sub, Runnable action) {
        builtinCommands.add(new CommandItem(label, sub, "Command", action));
    }

    private void rebuildItems() {
        List<CommandItem> all = new ArrayList<>();

        // Sessions first
        for (Session s : store.getSessions()) {
            if ("__local__".equals(s.getGroup())) continue;
            String name = s.getName() != null ? s.getName() : s.getUsername() + "@" + s.getHost();
            String group = s.getGroup() != null ? s.getGroup() : "Default";
            all.add(new CommandItem(name,
                    s.getUsername() + "@" + s.getHost() + "  [" + group + "]",
                    "Session",
                    () -> { stage.hide(); openSession.accept(s); }));
        }

        // Built-in commands
        all.addAll(builtinCommands);

        allItems.setAll(all);
    }

    private void runSelected() {
        CommandItem sel = listView.getSelectionModel().getSelectedItem();
        if (sel != null) {
            stage.hide();
            sel.action().run();
        }
    }

    public void show() {
        rebuildItems();
        stage.sizeToScene();
        stage.centerOnScreen();
        searchField.clear();
        filtered.setPredicate(p -> true);
        if (!filtered.isEmpty()) listView.getSelectionModel().selectFirst();
        stage.show();
        Platform.runLater(searchField::requestFocus);
    }
}
