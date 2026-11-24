package com.novaterm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Modal dialog listing all keyboard shortcuts.
 */
public class KeyboardShortcutsDialog extends Dialog<Void> {

    private record ShortcutEntry(String keys, String description) {}

    public KeyboardShortcutsDialog() {
        setTitle("Keyboard Shortcuts");
        setHeaderText("NovaTerm Keyboard Shortcuts");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(480);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));

        content.getChildren().addAll(
            section("General", new ShortcutEntry[]{
                new ShortcutEntry("Cmd+N",         "New session…"),
                new ShortcutEntry("Cmd+T",         "New local terminal"),
                new ShortcutEntry("Cmd+W",         "Close current tab"),
                new ShortcutEntry("Cmd+,",         "Preferences"),
                new ShortcutEntry("Cmd+Shift+P",   "Command palette"),
                new ShortcutEntry("Cmd+?  / F1",   "This shortcuts dialog"),
            }),
            section("Navigation", new ShortcutEntry[]{
                new ShortcutEntry("Cmd+Shift+S",   "Toggle sidebar"),
                new ShortcutEntry("Cmd+Shift+T",   "Port forwarding…"),
                new ShortcutEntry("Cmd+D",         "Split tab right"),
                new ShortcutEntry("Cmd+Shift+D",   "Split tab down"),
                new ShortcutEntry("Cmd+1 … 9",     "Switch to tab N"),
            }),
            section("Terminal", new ShortcutEntry[]{
                new ShortcutEntry("Cmd+F",         "Find in terminal"),
                new ShortcutEntry("Cmd++  /  Cmd+-","Zoom font size"),
                new ShortcutEntry("Cmd+0",         "Reset font size"),
                new ShortcutEntry("Scroll Up",     "Browse scrollback history"),
                new ShortcutEntry("Scroll Down",   "Return to live view"),
                new ShortcutEntry("Ctrl+C",        "Send interrupt (SIGINT)"),
                new ShortcutEntry("Ctrl+D",        "Send EOF"),
                new ShortcutEntry("Ctrl+Z",        "Suspend (SIGTSTP)"),
            }),
            section("Broadcast & Macros", new ShortcutEntry[]{
                new ShortcutEntry("Cmd+Shift+B",   "Toggle broadcast mode (send to all tabs)"),
                new ShortcutEntry("Cmd+Shift+R",   "Start recording macro"),
                new ShortcutEntry("Cmd+Shift+S",   "Stop recording macro"),
            })
        );

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:transparent;");
        getDialogPane().setContent(scroll);
    }

    private TitledPane section(String title, ShortcutEntry[] entries) {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        grid.setPadding(new Insets(6, 0, 6, 0));

        for (int i = 0; i < entries.length; i++) {
            Label keys = new Label(entries[i].keys());
            keys.setStyle("-fx-font-family:'SF Mono',Menlo,monospace; -fx-font-size:12px; " +
                    "-fx-text-fill:#0969da; -fx-background-color:#d0d7de; " +
                    "-fx-background-radius:3; -fx-padding:2 6 2 6;");
            Label desc = new Label(entries[i].description());
            desc.setStyle("-fx-text-fill:#1f2328; -fx-font-size:12px;");
            grid.addRow(i, keys, desc);
        }

        TitledPane pane = new TitledPane(title, grid);
        pane.setCollapsible(false);
        pane.setStyle("-fx-text-fill:#656d76; -fx-font-size:11px;");
        return pane;
    }
}
