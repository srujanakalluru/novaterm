package com.novaterm.ui;

import com.novaterm.session.Session;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Toolbar row: [user@host:port] [Connect] [Local Shell]
 * Parses user@host:port shorthand and maintains a recent-hosts history.
 */
public class QuickConnectBar {
    private static final Preferences PREFS = Preferences.userNodeForPackage(QuickConnectBar.class);
    private static final String PREF_HISTORY = "quickconnect.history";
    private static final int MAX_HISTORY = 20;

    private final HBox root;

    public QuickConnectBar(Consumer<Session> onConnect, Runnable onLocalShell) {
        TextField input = new TextField();
        input.setId("quick-connect-input");
        input.setPromptText("user@host:port  — press Enter to connect");
        input.setPrefWidth(300);
        HBox.setHgrow(input, Priority.ALWAYS);

        Button connectBtn = new Button("Connect");
        connectBtn.setId("quick-connect-btn");
        connectBtn.setDefaultButton(false);

        Button localBtn = new Button("Local Shell");
        localBtn.setId("local-shell-btn");
        localBtn.setOnAction(e -> onLocalShell.run());

        // Recent-host drop-down on focus or arrow-down
        ContextMenu historyMenu = new ContextMenu();
        historyMenu.setId("quick-connect-history");

        Runnable refreshHistory = () -> {
            String stored = PREFS.get(PREF_HISTORY, "");
            historyMenu.getItems().clear();
            if (stored.isBlank()) return;
            String filter = input.getText().trim().toLowerCase();
            Arrays.stream(stored.split("\n"))
                    .filter(h -> !h.isBlank() && h.toLowerCase().contains(filter))
                    .forEach(h -> {
                        MenuItem item = new MenuItem(h);
                        item.setOnAction(ev -> { input.setText(h); input.positionCaret(h.length()); });
                        historyMenu.getItems().add(item);
                    });
        };

        input.focusedProperty().addListener((obs, wasF, isF) -> {
            if (isF) {
                refreshHistory.run();
                if (!historyMenu.getItems().isEmpty()) {
                    historyMenu.show(input, javafx.geometry.Side.BOTTOM, 0, 0);
                }
            } else {
                historyMenu.hide();
            }
        });

        input.textProperty().addListener((obs, o, n) -> {
            refreshHistory.run();
            if (!historyMenu.getItems().isEmpty()) {
                historyMenu.show(input, javafx.geometry.Side.BOTTOM, 0, 0);
            } else {
                historyMenu.hide();
            }
        });

        Runnable doConnect = () -> {
            String text = input.getText().trim();
            if (text.isEmpty()) return;
            Session session = parse(text);
            if (session != null) {
                addToHistory(text);
                onConnect.accept(session);
                input.clear();
                historyMenu.hide();
            }
        };
        connectBtn.setOnAction(e -> doConnect.run());
        input.setOnAction(e -> doConnect.run());

        root = new HBox(6, input, connectBtn, new Separator(javafx.geometry.Orientation.VERTICAL), localBtn);
        root.setId("quick-connect-bar");
        root.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    }

    private void addToHistory(String entry) {
        String stored = PREFS.get(PREF_HISTORY, "");
        List<String> hist = new ArrayList<>(Arrays.asList(stored.split("\n")));
        hist.remove(entry);
        hist.add(0, entry);
        if (hist.size() > MAX_HISTORY) hist = hist.subList(0, MAX_HISTORY);
        PREFS.put(PREF_HISTORY, String.join("\n", hist));
    }

    /** Parses: [user@]host[:port] */
    private Session parse(String text) {
        try {
            String user = System.getProperty("user.name");
            String host;
            int port = 22;

            if (text.contains("@")) {
                user = text.substring(0, text.indexOf('@'));
                text = text.substring(text.indexOf('@') + 1);
            }
            if (text.contains(":")) {
                port = Integer.parseInt(text.substring(text.lastIndexOf(':') + 1));
                host = text.substring(0, text.lastIndexOf(':'));
            } else {
                host = text;
            }
            return new Session(user + "@" + host, host, port, user);
        } catch (Exception e) {
            return null;
        }
    }

    public HBox getRoot() { return root; }
}
