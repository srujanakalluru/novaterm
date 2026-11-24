package com.novaterm.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Panel of saved shell command snippets.
 * Double-click a snippet to send it to the active terminal.
 */
public class SnippetPanel {
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), "Library", "Application Support",
            "NovaTerm", "snippets.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    public record Snippet(String label, String command, String group) {}

    private final VBox root;
    private final ListView<Snippet> listView;
    private final List<Snippet> snippets = new ArrayList<>();
    private final Consumer<String> onSend;

    public SnippetPanel(Consumer<String> onSend) {
        this.onSend = onSend;

        load();

        TextField filter = new TextField();
        filter.setPromptText("Filter snippets…");
        filter.setId("session-filter");

        listView = new ListView<>();
        listView.setId("session-list");
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Snippet item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Label lbl = new Label(item.label());
                lbl.setStyle("-fx-text-fill:#cdd6f4; -fx-font-size:12px;");
                Label cmd = new Label(item.command().length() > 60
                        ? item.command().substring(0, 59) + "…" : item.command());
                cmd.setStyle("-fx-text-fill:#6c7086; -fx-font-size:10px; -fx-font-family:'SF Mono',Menlo,monospace;");
                VBox box = new VBox(2, lbl, cmd);
                setGraphic(box); setText(null);
            }
        });

        refresh(filter.getText());
        filter.textProperty().addListener((obs, old, text) -> refresh(text.trim().toLowerCase()));

        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Snippet sel = listView.getSelectionModel().getSelectedItem();
                if (sel != null) onSend.accept(sel.command());
            }
        });

        listView.setOnContextMenuRequested(e -> {
            Snippet sel = listView.getSelectionModel().getSelectedItem();
            ContextMenu ctx = new ContextMenu();
            if (sel != null) {
                MenuItem send = new MenuItem("▶ Send to Terminal");
                send.setOnAction(ev -> onSend.accept(sel.command()));
                MenuItem edit = new MenuItem("✏ Edit…");
                edit.setOnAction(ev -> editSnippet(sel));
                MenuItem delete = new MenuItem("🗑 Delete");
                delete.setStyle("-fx-text-fill:#f38ba8;");
                delete.setOnAction(ev -> { snippets.remove(sel); save(); refresh(filter.getText()); });
                ctx.getItems().addAll(send, edit, new SeparatorMenuItem(), delete);
            }
            MenuItem add = new MenuItem("+ Add Snippet…");
            add.setOnAction(ev -> addSnippet(filter.getText()));
            ctx.getItems().add(add);
            ctx.show(listView, e.getScreenX(), e.getScreenY());
        });

        Button addBtn = new Button("+ Add Snippet");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setId("add-session-btn");
        addBtn.setOnAction(e -> addSnippet(""));

        Label title = new Label("SNIPPETS");
        title.setId("panel-title");

        root = new VBox(title, filter, listView, addBtn);
        VBox.setVgrow(listView, Priority.ALWAYS);
        root.setId("session-panel");
    }

    private void refresh(String filter) {
        Platform.runLater(() -> {
            listView.getItems().setAll(snippets.stream()
                    .filter(s -> filter.isEmpty()
                            || s.label().toLowerCase().contains(filter)
                            || s.command().toLowerCase().contains(filter))
                    .toList());
        });
    }

    private void addSnippet(String prefilter) {
        showSnippetDialog(null, s -> { snippets.add(s); save(); refresh(prefilter); });
    }

    private void editSnippet(Snippet old) {
        showSnippetDialog(old, updated -> {
            int idx = snippets.indexOf(old);
            if (idx >= 0) snippets.set(idx, updated);
            else snippets.add(updated);
            save();
            refresh("");
        });
    }

    private void showSnippetDialog(Snippet existing, Consumer<Snippet> onDone) {
        Dialog<Snippet> dlg = new Dialog<>();
        dlg.setTitle(existing == null ? "Add Snippet" : "Edit Snippet");
        dlg.setHeaderText(null);
        ButtonType ok = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        grid.setPadding(new javafx.geometry.Insets(14));

        TextField labelField = new TextField(existing != null ? existing.label() : "");
        labelField.setPromptText("My snippet");
        TextArea cmdArea = new TextArea(existing != null ? existing.command() : "");
        cmdArea.setPromptText("Command to send…");
        cmdArea.setPrefRowCount(4);
        cmdArea.setWrapText(true);
        TextField groupField = new TextField(existing != null ? existing.group() : "");
        groupField.setPromptText("Optional group");

        grid.addRow(0, new Label("Label:"), labelField);
        grid.addRow(1, new Label("Command:"), cmdArea);
        grid.addRow(2, new Label("Group:"), groupField);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().setPrefWidth(400);

        dlg.setResultConverter(btn -> btn == ok
                ? new Snippet(
                    labelField.getText().trim().isEmpty() ? cmdArea.getText().trim() : labelField.getText().trim(),
                    cmdArea.getText(),
                    groupField.getText().trim().isEmpty() ? null : groupField.getText().trim())
                : null);

        dlg.showAndWait().ifPresent(onDone);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(STORE_PATH)) return;
        try {
            List<Map<String, String>> raw = mapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> m : raw) {
                snippets.add(new Snippet(
                        m.getOrDefault("label", ""),
                        m.getOrDefault("command", ""),
                        m.get("group")));
            }
        } catch (IOException e) {
            // first run or corrupt file — start fresh
        }
    }

    private void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            List<Map<String, String>> raw = new ArrayList<>();
            for (Snippet s : snippets) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("label", s.label());
                m.put("command", s.command());
                if (s.group() != null) m.put("group", s.group());
                raw.add(m);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(STORE_PATH.toFile(), raw);
        } catch (IOException e) {
            // log silently — non-critical
        }
    }

    public VBox getRoot() { return root; }
}
