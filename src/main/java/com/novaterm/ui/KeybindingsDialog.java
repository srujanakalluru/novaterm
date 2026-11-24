package com.novaterm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dialog for editing keyboard shortcut bindings.
 * Shows a table of action → current keybinding. Click a row to record a new shortcut.
 */
public class KeybindingsDialog extends Dialog<Void> {

    public KeybindingsDialog() {
        setTitle("Keyboard Shortcuts");
        setHeaderText(null);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(480);
        getDialogPane().setPrefHeight(520);

        KeybindingStore store = KeybindingStore.get();

        // Build table rows: label → current binding textfield
        Map<String, TextField> fields = new LinkedHashMap<>();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(8));

        int row = 0;
        for (String actionId : KeybindingStore.DEFAULTS.keySet()) {
            String label = KeybindingStore.LABELS.getOrDefault(actionId, actionId);
            Label lbl = new Label(label);
            lbl.setMinWidth(180);

            TextField tf = new TextField(store.get(actionId));
            tf.setPromptText("click to record shortcut…");
            tf.setEditable(false);
            tf.setPrefWidth(160);
            tf.setStyle("-fx-font-family:'SF Mono',Menlo,monospace; -fx-font-size:11px;");
            tf.setUserData(actionId);

            tf.setOnMouseClicked(e -> {
                tf.setText("…press a key combo…");
                tf.requestFocus();
            });

            tf.setOnKeyPressed(ke -> {
                ke.consume();
                KeyCode code = ke.getCode();
                // Ignore modifier-only presses
                if (code == KeyCode.SHIFT || code == KeyCode.CONTROL ||
                    code == KeyCode.ALT || code == KeyCode.META ||
                    code == KeyCode.COMMAND || code == KeyCode.WINDOWS) {
                    return;
                }
                if (code == KeyCode.ESCAPE) {
                    // Cancel — restore previous value
                    tf.setText(store.get(actionId));
                    return;
                }
                // Build combo string
                StringBuilder sb = new StringBuilder();
                if (ke.isMetaDown())    sb.append("Meta+");
                if (ke.isControlDown()) sb.append("Ctrl+");
                if (ke.isAltDown())     sb.append("Alt+");
                if (ke.isShiftDown())   sb.append("Shift+");
                sb.append(code.getName());
                tf.setText(sb.toString());
            });

            Button resetBtn = new Button("↺");
            resetBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#848d97; -fx-cursor:hand; -fx-padding:2 6 2 6;");
            resetBtn.setTooltip(new Tooltip("Reset to default: " + KeybindingStore.DEFAULTS.get(actionId)));
            resetBtn.setOnAction(e -> tf.setText(KeybindingStore.DEFAULTS.getOrDefault(actionId, "")));

            HBox right = new HBox(4, tf, resetBtn);
            right.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            grid.addRow(row++, lbl, right);
            fields.put(actionId, tf);
        }

        Button resetAllBtn = new Button("Reset All Shortcuts to Defaults");
        resetAllBtn.setOnAction(e -> {
            for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                entry.getValue().setText(KeybindingStore.DEFAULTS.getOrDefault(entry.getKey(), ""));
            }
        });
        resetAllBtn.setStyle("-fx-background-color:#d0d7de; -fx-text-fill:#cf222e; -fx-cursor:hand;");

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:#f6f8fa;");

        VBox content = new VBox(8, scroll, resetAllBtn);
        content.setPadding(new Insets(8));
        getDialogPane().setContent(content);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                    String val = entry.getValue().getText().trim();
                    if (!val.isBlank() && !val.startsWith("…")) {
                        // Validate it parses
                        try {
                            KeyCombination.keyCombination(val);
                            store.set(entry.getKey(), val);
                        } catch (Exception ignored) {
                            store.reset(entry.getKey()); // fall back to default
                        }
                    } else {
                        store.reset(entry.getKey());
                    }
                }
                // Live-apply the new bindings to the running scene
                AcceleratorManager.get().rebindAll();
            }
            return null;
        });
    }
}
