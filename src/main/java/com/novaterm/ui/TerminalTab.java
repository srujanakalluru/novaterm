package com.novaterm.ui;

import com.novaterm.session.Session;
import com.novaterm.session.SessionStore;
import com.novaterm.ssh.SSHManager;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class TerminalTab {
    private static final Logger log = LoggerFactory.getLogger(TerminalTab.class);

    private static javafx.scene.layout.HBox buildTabGraphic(
            javafx.scene.shape.Circle dot, Label label) {
        javafx.scene.layout.HBox hb = new javafx.scene.layout.HBox(4, dot, label);
        hb.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return hb;
    }

    private static String toHexColor(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    private void adjustFontSize(int delta) {
        engine.executeScript(
            "if(window.term){" +
            "  window.term.fontSize = Math.max(6, Math.min(32, window.term.fontSize + " + delta + "));" +
            "  window.term._measureCell();" +
            "  window.term.render();" +
            "}");
    }

    /** Custom event type fired on the tab pane to request duplication of this tab. */
    public static final javafx.event.EventType<javafx.event.Event> DUPLICATE_TAB_EVENT =
            new javafx.event.EventType<>(javafx.event.Event.ANY, "DUPLICATE_TAB");

    // All live terminal tabs register here so PreferencesDialog can push theme updates
    private static final java.util.List<TerminalTab> ALL_TABS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static volatile boolean broadcastMode = false;
    public static boolean isBroadcastMode() { return broadcastMode; }
    public static void setBroadcastMode(boolean on) { broadcastMode = on; }

    public static void broadcastThemeReload() {
        ALL_TABS.forEach(TerminalTab::reloadTheme);
    }

    public void reloadTheme() {
        if (engine == null) return;
        int fs = PreferencesDialog.get().getInt(PreferencesDialog.PREF_FONT_SIZE, 13);
        String ff = PreferencesDialog.get().get(PreferencesDialog.PREF_FONT_FAMILY, "SF Mono");
        String scheme = PreferencesDialog.get().get(PreferencesDialog.PREF_COLOR_SCHEME, "Github Light");
        String cursorStyle = PreferencesDialog.get().get(PreferencesDialog.PREF_CURSOR_STYLE, "block");
        int transparency = PreferencesDialog.get().getInt(PreferencesDialog.PREF_TRANSPARENCY, 0);
        boolean ligatures = PreferencesDialog.get().getBoolean(PreferencesDialog.PREF_LIGATURES, false);
        String[] colors = ColorScheme.resolve(scheme);
        double opacity = 1.0 - transparency / 100.0;
        String ligJs = ligatures
            ? "var c=document.getElementById('term-canvas');if(c){c.style.fontVariantLigatures='common-ligatures';c.style.textRendering='optimizeLegibility';}"
            : "var c=document.getElementById('term-canvas');if(c){c.style.fontVariantLigatures='none';}";
        String js = String.format(
            "if(window.term){window.term.fontSize=%d;window.term.fontFamily=\"'%s',Menlo,'Courier New',monospace\";" +
            "window.term.setColors('%s','%s');" +
            "window.__TERM_CURSOR_STYLE__='%s';" +
            "window.term._measureCell();window.term.render();" +
            "document.body.style.opacity='%s';}" + ligJs,
            fs, ff.replace("'", "\\'"), colors[0], colors[1], cursorStyle,
            String.format("%.2f", opacity));
        Platform.runLater(() -> engine.executeScript(js));
    }

    /** Programmatically open the in-terminal search bar. */
    public void showFind() {
        if (searchFieldRef != null) {
            searchFieldRef.setVisible(true);
            searchFieldRef.setManaged(true);
            searchFieldRef.requestFocus();
        }
    }

    private final Tab tab;
    private final Session session;
    private final SessionTabState state;
    private WebEngine engine;
    private final LinkedBlockingQueue<String> toShell = new LinkedBlockingQueue<>();
    private ClientSession sshSession;
    private OutputStream shellIn;
    private com.novaterm.ssh.ShellChannel shellChannel;
    private volatile boolean alive = true;
    private javafx.scene.layout.BorderPane container;
    private MacroManager macroManager;
    private java.util.function.Consumer<com.novaterm.session.Session> newTabCallback;
    private javafx.scene.control.TextField searchFieldRef;

    /** Exposed to JavaScript — receives keystrokes typed in the terminal canvas. */
    public class TermBridge {
        public void sendData(String data) {
            if ("__RIGHTCLICK__".equals(data)) {
                Platform.runLater(() -> showContextMenu());
                return;
            }
            // Handle terminal resize notifications from JS
            if (data.startsWith("RESIZE:")) {
                String[] parts = data.split(":");
                try {
                    int cols = Integer.parseInt(parts[1]);
                    int rows = Integer.parseInt(parts[2]);
                    if (shellChannel != null) {
                        try { shellChannel.sendResize(cols, rows); } catch (Exception ignored) {}
                    }
                } catch (NumberFormatException ignored) {}
                return;
            }
            if (macroManager != null) macroManager.record(data);
            if (broadcastMode) {
                // Fan out to all connected SSH sessions
                ALL_TABS.forEach(t -> { if (t != TerminalTab.this) t.toShell.offer(data); });
            }
            toShell.offer(data);
        }
        public void copyToClipboard(String text) {
            Platform.runLater(() -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });
        }

        public void setDirectory(String dir) {
            // Called from OSC 7 — save for SCP drag-and-drop target
            if (dir != null && !dir.isBlank()) {
                state.setCurrentDirectory(dir);
            }
        }

        public void setTitle(String title) {
            Platform.runLater(() -> {
                // Update the tab label to show the shell-reported title
                tab.setTooltip(new Tooltip(title));
                // Only change visible text if it's the user's label (not a custom rename)
                if (tab.getGraphic() instanceof javafx.scene.layout.HBox hb) {
                    hb.getChildren().stream()
                            .filter(n -> n instanceof Label)
                            .map(n -> (Label) n)
                            .findFirst()
                            .ifPresent(lbl -> lbl.setText(title.length() > 30
                                    ? title.substring(0, 29) + "…" : title));
                }
            });
        }

        public void bell() {
            String mode = PreferencesDialog.get().get(PreferencesDialog.PREF_BELL_MODE, "audio");
            Platform.runLater(() -> {
                switch (mode) {
                    case "audio" -> {
                        try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Exception ignored) {}
                    }
                    case "visual" -> {
                        // Flash the terminal with a brief white overlay via CSS
                        engine.executeScript(
                            "var fl=document.createElement('div');" +
                            "fl.style.cssText='position:fixed;inset:0;background:rgba(255,255,255,0.3);" +
                            "pointer-events:none;animation:bellFlash 0.15s linear;';" +
                            "document.body.appendChild(fl);" +
                            "setTimeout(function(){fl.remove()},200);");
                    }
                    case "none" -> {}
                }
            });
        }

        public void log(String msg) { TerminalTab.log.debug("[term] {}", msg); }

        public void openUrl(String url) {
            Platform.runLater(() -> {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                } catch (Exception ex) {
                    log.warn("Could not open URL: {}", url);
                }
            });
        }
    }

    // ── Auto-logging ──────────────────────────────────────────────────────────
    private volatile java.io.BufferedWriter logWriter;

    private void startLogging() {
        if (logWriter != null) return; // already logging
        try {
            java.nio.file.Path logDir = java.nio.file.Path.of(
                    System.getProperty("user.home"), "Library", "Application Support", "NovaTerm", "logs");
            java.nio.file.Files.createDirectories(logDir);
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            java.nio.file.Path logFile = logDir.resolve(session.getHost().replace('/', '_') + "-" + ts + ".log");
            logWriter = java.nio.file.Files.newBufferedWriter(logFile, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            writeToTerm("\\r\\n[logging to " + logFile.getFileName() + "]\\r\\n");
        } catch (java.io.IOException ex) {
            log.warn("Could not open log file", ex);
        }
    }

    private void stopLogging() {
        java.io.BufferedWriter w = logWriter;
        logWriter = null;
        if (w != null) {
            try { w.close(); } catch (java.io.IOException ignored) {}
            writeToTerm("\\r\\n[logging stopped]\\r\\n");
        }
    }

    public TerminalTab(Session session, SessionStore sessionStore) {
        this.session = session;
        this.state = new SessionTabState(session);

        tab = new Tab(session.toString());
        tab.setClosable(true);

        // Color dot for session color coding
        javafx.scene.shape.Circle colorDot = new javafx.scene.shape.Circle(4);
        String sessionColor = session.getColor();
        if (sessionColor != null && !sessionColor.isBlank()) {
            try {
                colorDot.setFill(javafx.scene.paint.Color.web(sessionColor));
                colorDot.setVisible(true);
            } catch (Exception ignored) {
                colorDot.setVisible(false);
            }
        } else {
            colorDot.setVisible(false);
        }
        colorDot.setManaged(colorDot.isVisible());

        // Double-click tab label to rename
        Label tabLabel = new Label(session.toString());
        tabLabel.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TextField tf = new TextField(tab.getText());
                tf.setPrefWidth(120);
                tf.setOnAction(ae -> {
                    String newName = tf.getText().trim();
                    if (!newName.isEmpty()) {
                        tab.setText(newName);
                        tabLabel.setText(newName);
                    }
                    tab.setGraphic(buildTabGraphic(colorDot, tabLabel));
                });
                tf.focusedProperty().addListener((obs, wasF, isF) -> {
                    if (!isF) tab.setGraphic(buildTabGraphic(colorDot, tabLabel));
                });
                tab.setGraphic(tf);
                tf.selectAll();
                tf.requestFocus();
            }
        });
        tab.setGraphic(buildTabGraphic(colorDot, tabLabel));
        tab.setText(null);

        // Right-click on tab label → context menu
        javafx.scene.control.ContextMenu tabCtx = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem pinItem = new javafx.scene.control.MenuItem("📌 Pin Tab");
        pinItem.setOnAction(e -> {
            tab.setClosable(!tab.isClosable());
            pinItem.setText(tab.isClosable() ? "📌 Pin Tab" : "📌 Unpin Tab");
        });
        javafx.scene.control.MenuItem duplicateItem = new javafx.scene.control.MenuItem("⎘ Duplicate Tab");
        duplicateItem.setOnAction(e -> {
            if (tab.getTabPane() != null) {
                // Create a new TerminalTab for the same session
                // We just notify via the bridge — actual duplication requires the store reference
                // So we just emit a custom event that MainWindow can intercept
                // For now, store the session on the tab's user data for external pickup
                tab.getTabPane().fireEvent(new javafx.event.Event(DUPLICATE_TAB_EVENT));
            }
        });
        javafx.scene.control.MenuItem closeOthers = new javafx.scene.control.MenuItem("✕ Close Other Tabs");
        closeOthers.setOnAction(e -> {
            if (tab.getTabPane() == null) return;
            new java.util.ArrayList<>(tab.getTabPane().getTabs()).stream()
                    .filter(t -> t != tab && t.isClosable())
                    .forEach(t -> tab.getTabPane().getTabs().remove(t));
        });
        // Set Tab Color…
        javafx.scene.control.MenuItem colorItem = new javafx.scene.control.MenuItem("🎨 Set Tab Color…");
        colorItem.setOnAction(e -> {
            javafx.scene.control.ColorPicker picker = new javafx.scene.control.ColorPicker(
                    colorDot.isVisible() ? (javafx.scene.paint.Color) colorDot.getFill()
                                        : javafx.scene.paint.Color.web("#0969da"));
            javafx.scene.control.ButtonType applyBtn = new javafx.scene.control.ButtonType("Apply",
                    javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            javafx.scene.control.ButtonType clearBtn = new javafx.scene.control.ButtonType("Clear");
            javafx.scene.control.ButtonType cancelBtn2 = javafx.scene.control.ButtonType.CANCEL;
            javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dlg = new javafx.scene.control.Dialog<>();
            dlg.setTitle("Set Tab Color");
            dlg.getDialogPane().setContent(picker);
            dlg.getDialogPane().getButtonTypes().setAll(applyBtn, clearBtn, cancelBtn2);
            dlg.showAndWait().ifPresent(result -> {
                if (result == applyBtn) {
                    colorDot.setFill(picker.getValue());
                    colorDot.setVisible(true);
                    colorDot.setManaged(true);
                    session.setColor(toHexColor(picker.getValue()));
                } else if (result == clearBtn) {
                    colorDot.setVisible(false);
                    colorDot.setManaged(false);
                    session.setColor(null);
                }
                tab.setGraphic(buildTabGraphic(colorDot, tabLabel));
            });
        });

        tabCtx.getItems().addAll(pinItem, duplicateItem, colorItem,
                new javafx.scene.control.SeparatorMenuItem(), closeOthers);
        tab.getGraphic().setOnContextMenuRequested(e -> tabCtx.show(tab.getGraphic(), e.getScreenX(), e.getScreenY()));

        // Show session notes in a tab tooltip
        if (session.getNotes() != null && !session.getNotes().isBlank()) {
            Tooltip tabTooltip = new Tooltip(session.getNotes());
            tabTooltip.setWrapText(true);
            tabTooltip.setMaxWidth(300);
            Tooltip.install(tab.getGraphic(), tabTooltip);
        }

        WebView webView = new WebView();
        webView.setContextMenuEnabled(false);
        engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);

        // Search bar (hidden by default, Cmd+F to show)
        javafx.scene.control.TextField searchField = new javafx.scene.control.TextField();
        searchField.setPromptText("Search…");
        searchField.setId("term-search");
        searchField.setVisible(false);
        searchField.setManaged(false);
        searchField.setMaxWidth(220);
        searchFieldRef = searchField;

        javafx.scene.control.Label matchCount = new javafx.scene.control.Label();
        matchCount.setId("search-match-count");
        matchCount.setStyle("-fx-text-fill:#656d76; -fx-font-size:11px; -fx-min-width:60;");

        javafx.scene.control.Button prevMatch = new javafx.scene.control.Button("↑");
        prevMatch.setId("search-prev-btn");
        prevMatch.setOnAction(e -> {
            Object idx = engine.executeScript("window.term ? window.term.findPrev() : -1");
            Object total = engine.executeScript("window.term ? window.term.findMatches.length : 0");
            if (idx instanceof Number n && total instanceof Number t && t.intValue() > 0)
                matchCount.setText((n.intValue() + 1) + " / " + t.intValue());
        });

        javafx.scene.control.Button nextMatch = new javafx.scene.control.Button("↓");
        nextMatch.setId("search-next-btn");
        nextMatch.setOnAction(e -> {
            Object idx = engine.executeScript("window.term ? window.term.findNext() : -1");
            Object total = engine.executeScript("window.term ? window.term.findMatches.length : 0");
            if (idx instanceof Number n && total instanceof Number t && t.intValue() > 0)
                matchCount.setText((n.intValue() + 1) + " / " + t.intValue());
        });

        javafx.scene.control.Button closeSearch = new javafx.scene.control.Button("✕");
        closeSearch.setId("search-close-btn");
        closeSearch.setOnAction(e -> {
            engine.executeScript("if(window.term) window.term.clearFind()");
            searchField.clear();
            matchCount.setText("");
            searchField.setVisible(false);
            searchField.setManaged(false);
            webView.requestFocus();
        });

        javafx.scene.layout.HBox searchBar = new javafx.scene.layout.HBox(4, searchField, matchCount, prevMatch, nextMatch, closeSearch);
        searchBar.setId("search-bar");
        searchBar.visibleProperty().bind(searchField.visibleProperty());
        searchBar.managedProperty().bind(searchField.managedProperty());
        searchBar.setStyle("-fx-background-color:#d0d7de; -fx-padding:4 8 4 8; -fx-alignment:center-right;");

        javafx.scene.layout.BorderPane container = new javafx.scene.layout.BorderPane();
        container.setCenter(webView);
        container.setBottom(searchBar);

        // Clipboard history popup (lazy init)
        ClipboardHistoryPopup[] clipboardPopup = { null };

        // Pinch-to-zoom: accumulate zoom factor deltas, apply ±1pt font steps
        double[] zoomAccum = { 1.0 };
        webView.setOnZoom(ze -> {
            zoomAccum[0] *= ze.getZoomFactor();
            if (zoomAccum[0] > 1.1) {
                adjustFontSize(1);
                zoomAccum[0] = 1.0;
            } else if (zoomAccum[0] < 0.9) {
                adjustFontSize(-1);
                zoomAccum[0] = 1.0;
            }
            ze.consume();
        });

        // Cmd+F opens search; Cmd+Plus/Minus/0 zoom font size; Cmd+Shift+V clipboard history
        webView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isMetaDown() && e.isShiftDown() && e.getCode() == javafx.scene.input.KeyCode.V) {
                if (clipboardPopup[0] == null) {
                    clipboardPopup[0] = new ClipboardHistoryPopup(text -> {
                        // Paste selected history item into the terminal
                        String safe = text.replace("\\", "\\\\").replace("'", "\\'")
                                .replace("\n", "\\n").replace("\r", "\\r");
                        engine.executeScript("if(window.term) window.term.pasteText('" + safe + "')");
                        toShell.offer(text);
                    });
                }
                clipboardPopup[0].show(webView.getScene().getWindow());
                e.consume();
                return;
            }
            if (e.isMetaDown() && e.getCode() == javafx.scene.input.KeyCode.F) {
                searchField.setVisible(true);
                searchField.setManaged(true);
                searchField.requestFocus();
                e.consume();
            } else if (e.isMetaDown() && (e.getCode() == javafx.scene.input.KeyCode.EQUALS
                    || e.getCode() == javafx.scene.input.KeyCode.PLUS)) {
                adjustFontSize(1);
                e.consume();
            } else if (e.isMetaDown() && e.getCode() == javafx.scene.input.KeyCode.MINUS) {
                adjustFontSize(-1);
                e.consume();
            } else if (e.isMetaDown() && e.getCode() == javafx.scene.input.KeyCode.DIGIT0) {
                // Reset to preference default
                int def = PreferencesDialog.get().getInt(PreferencesDialog.PREF_FONT_SIZE, 13);
                engine.executeScript(
                    "if(window.term){window.term.fontSize=" + def + ";window.term._measureCell();window.term.render();}");
                e.consume();
            }
        });

        // Live search: highlight matches as the user types
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String q = newVal.trim();
            String escaped = q.replace("\\", "\\\\").replace("'", "\\'");
            if (q.isEmpty()) {
                Platform.runLater(() -> {
                    engine.executeScript("if(window.term) window.term.clearFind()");
                    matchCount.setText("");
                });
            } else {
                Platform.runLater(() -> {
                    Object n = engine.executeScript(
                        "if(window.term){var n=window.term.findText('" + escaped + "');n;}else{0}");
                    int total = n instanceof Number num ? num.intValue() : 0;
                    matchCount.setText(total == 0 ? "No matches"
                            : "1 / " + total);
                });
            }
        });

        // Escape closes the find bar and clears highlights
        searchField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                engine.executeScript("if(window.term) window.term.clearFind()");
                searchField.clear();
                matchCount.setText("");
                searchField.setVisible(false);
                searchField.setManaged(false);
                webView.requestFocus();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                nextMatch.fire();
                e.consume();
            }
        });

        // Drag-and-drop: local files → SCP upload to remote current directory
        container.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && state.getClientSession() != null) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            e.consume();
        });
        container.setOnDragDropped(e -> {
            javafx.scene.input.Dragboard db = e.getDragboard();
            if (db.hasFiles() && state.getClientSession() != null) {
                String remoteDir = state.getCurrentDirectory();
                if (remoteDir == null || remoteDir.isBlank()) remoteDir = "~";
                final String dest = remoteDir;
                for (java.io.File f : db.getFiles()) {
                    final java.nio.file.Path local = f.toPath();
                    writeToTerm("\\r\\n[SCP uploading " + f.getName() + " → " + dest + "]\\r\\n");
                    final String localName = f.getName();
                    final String destFinal = dest;
                    com.novaterm.ssh.ScpTransfer.upload(
                        state.getClientSession(), local, destFinal + "/" + localName,
                        () -> writeToTerm("\\r\\n[SCP upload complete: " + localName + "]\\r\\n"),
                        ex -> writeToTerm("\\r\\n[SCP upload failed: " + ex.getMessage() + "]\\r\\n")
                    );
                }
                e.setDropCompleted(true);
            }
            e.consume();
        });

        this.container = container;
        tab.setContent(container);
        ALL_TABS.add(this);
        tab.setOnCloseRequest(e -> {
            if (state.isConnected()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Close connection to " + session.getHost() + "?");
                confirm.setTitle("Close Session");
                confirm.setHeaderText(null);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    e.consume();
                }
            }
        });
        tab.setOnClosed(e -> { ALL_TABS.remove(this); disconnect(); });

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("javaBridge", new TermBridge());
                Thread.ofVirtual().start(() -> connect());
            }
        });

        engine.loadContent(buildHtml());
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private void connect() {
        try {
            if (isLocal()) {
                connectLocal();
            } else {
                connectSSH();
            }
        } catch (Exception e) {
            log.error("Connection failed", e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            writeToTerm("\\r\\nConnection failed: " + msg.replace("'", "\\'") + "\\r\\n");
        }
    }

    private boolean isLocal() {
        return "__local__".equals(session.getGroup());
    }

    private void connectLocal() throws IOException {
        String shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        ProcessBuilder pb = new ProcessBuilder(shell, "--login")
                .redirectErrorStream(true);
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("COLORTERM", "truecolor");
        Process proc = pb.start();

        pumpOutput(proc.getInputStream());
        pumpInput(proc.getOutputStream());
    }

    private void connectSSH() throws Exception {
        String password = promptPassword();
        if (password == null) {
            Platform.runLater(() -> { if (tab.getTabPane() != null) tab.getTabPane().getTabs().remove(tab); });
            return;
        }

        int maxAttempts = 1 + com.novaterm.ssh.ConnectionConfig.retryCount();
        int delaySecs   = com.novaterm.ssh.ConnectionConfig.retryDelay();
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sshSession = SSHManager.get().connect(session, password);
                lastEx = null;
                break;
            } catch (Exception e) {
                lastEx = e;
                if (attempt < maxAttempts) {
                    int att = attempt;
                    writeToTerm("\\r\\nConnect attempt " + att + " failed: "
                            + e.getMessage().replace("'", "\\'")
                            + " — retrying in " + delaySecs + "s…\\r\\n");
                    Thread.sleep(delaySecs * 1000L);
                }
            }
        }
        if (lastEx != null) throw lastEx;

        PipedOutputStream fromShellPipe = new PipedOutputStream();
        PipedInputStream shellOut = new PipedInputStream(fromShellPipe, 65536);

        PipedOutputStream toShellPipe = new PipedOutputStream();
        PipedInputStream shellInStream = new PipedInputStream(toShellPipe, 65536);
        shellIn = toShellPipe;

        shellChannel = SSHManager.get().openShell(sshSession, 220, 50, shellInStream, fromShellPipe);
        state.setClientSession(sshSession);

        // Auto-start any persisted tunnel configs
        for (com.novaterm.session.TunnelConfig tc : session.getTunnels()) {
            try {
                if (tc.isRemote()) {
                    state.getTunnelManager().addRemoteForward(sshSession,
                            tc.getLabel(), tc.getRemotePort(), tc.getRemoteHost(), tc.getLocalPort());
                } else {
                    state.getTunnelManager().addLocalForward(sshSession,
                            tc.getLabel(), tc.getLocalPort(), tc.getRemoteHost(), tc.getRemotePort());
                }
                writeToTerm("\\r\\n[auto-tunnel] " + tc.getLabel() + " started\\r\\n");
            } catch (Exception ex) {
                writeToTerm("\\r\\n[auto-tunnel] " + tc.getLabel() + " failed: "
                        + ex.getMessage().replace("'", "\\'") + "\\r\\n");
            }
        }

        pumpOutput(shellOut);
        pumpInput(null); // SSH: use shellIn directly

        // Inject environment variables and startup command
        String envVars = session.getEnvVars();
        String startup = session.getStartupCommand();
        boolean hasPost = (envVars != null && !envVars.isBlank()) || (startup != null && !startup.isBlank());
        if (hasPost) {
            Thread.sleep(300);
            if (envVars != null && !envVars.isBlank()) {
                for (String line : envVars.split("\\r?\\n")) {
                    String kv = line.trim();
                    if (!kv.isEmpty() && kv.contains("=")) {
                        toShell.offer("export " + kv + "\r");
                    }
                }
            }
            if (startup != null && !startup.isBlank()) {
                toShell.offer(startup.trim() + "\r");
            }
        }
    }

    // Activity badge: set when this tab has new output while not selected
    private volatile boolean hasActivity = false;

    /** Called by MainWindow when a tab becomes selected — clears the activity badge. */
    public void clearActivityBadge() {
        if (!hasActivity) return;
        hasActivity = false;
        Platform.runLater(() -> {
            if (tab.getGraphic() instanceof javafx.scene.layout.HBox hb) {
                hb.getChildren().stream()
                        .filter(n -> n instanceof Label l && l.getText().startsWith("● "))
                        .map(n -> (Label) n)
                        .findFirst()
                        .ifPresent(lbl -> lbl.setText(lbl.getText().substring(2)));
            }
        });
    }

    private void markActivity() {
        if (hasActivity) return;
        // Check if this tab is currently selected
        if (tab.getTabPane() != null && tab.getTabPane().getSelectionModel().getSelectedItem() == tab) return;
        hasActivity = true;
        Platform.runLater(() -> {
            if (tab.getGraphic() instanceof javafx.scene.layout.HBox hb) {
                hb.getChildren().stream()
                        .filter(n -> n instanceof Label l && !l.getText().startsWith("● "))
                        .map(n -> (Label) n)
                        .findFirst()
                        .ifPresent(lbl -> lbl.setText("● " + lbl.getText()));
            }
        });
    }

    private void pumpOutput(InputStream src) {
        Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while (alive && (n = src.read(buf)) != -1) {
                    final byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    state.addBytesReceived(n);
                    markActivity();
                    java.io.BufferedWriter lw = logWriter;
                    if (lw != null) {
                        try { lw.write(new String(chunk, StandardCharsets.UTF_8)); lw.flush(); }
                        catch (IOException ignored) {}
                    }
                    String b64 = Base64.getEncoder().encodeToString(chunk);
                    final String b = b64;
                    Platform.runLater(() -> engine.executeScript("receiveData('" + b + "')"));
                }
            } catch (IOException e) {
                if (alive) log.debug("Shell output ended: {}", e.getMessage());
            }
            stopLogging();
            writeToTerm("\\r\\n[disconnected]\\r\\n");
            Platform.runLater(TerminalTab.this::showReconnectOverlay);
        });
    }

    private void showReconnectOverlay() {
        if (container == null) return;
        javafx.scene.layout.BorderPane bp = container;

        int autoDelaySecs = 5;
        javafx.scene.control.Label countLabel = new javafx.scene.control.Label(
                "Reconnecting in " + autoDelaySecs + "s…");
        countLabel.setStyle("-fx-text-fill:#1f2328; -fx-font-size:12px;");

        javafx.scene.control.Button reconnectNow = new javafx.scene.control.Button("⟳  Now");
        reconnectNow.setStyle("-fx-background-color:#0969da; -fx-text-fill:#f6f8fa; -fx-padding:5 14 5 14; -fx-cursor:hand;");

        javafx.scene.control.Button cancel = new javafx.scene.control.Button("✕");
        cancel.setStyle("-fx-background-color:#c8d1db; -fx-text-fill:#1f2328; -fx-padding:5 10 5 10; -fx-cursor:hand;");

        javafx.animation.Timeline[] timerRef = {null};

        Runnable doReconnect = () -> {
            if (timerRef[0] != null) timerRef[0].stop();
            bp.setTop(null);
            alive = true;
            Thread.ofVirtual().start(() -> connect());
        };

        reconnectNow.setOnAction(e -> doReconnect.run());
        cancel.setOnAction(e -> {
            if (timerRef[0] != null) timerRef[0].stop();
            bp.setTop(null);
        });

        int[] remaining = {autoDelaySecs};
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                remaining[0]--;
                if (remaining[0] <= 0) {
                    doReconnect.run();
                } else {
                    countLabel.setText("Reconnecting in " + remaining[0] + "s…");
                }
            })
        );
        timer.setCycleCount(autoDelaySecs);
        timerRef[0] = timer;
        timer.play();

        javafx.scene.layout.HBox overlay = new javafx.scene.layout.HBox(10,
                countLabel, reconnectNow, cancel);
        overlay.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        overlay.setStyle("-fx-background-color:#eaeef2; -fx-padding:6 12 6 12; -fx-border-color:#c8d1db; -fx-border-width:0 0 1 0;");
        bp.setTop(overlay);
    }

    private void pumpInput(OutputStream target) {
        Thread.ofVirtual().start(() -> {
            try {
                while (alive) {
                    String data = toShell.take();
                    OutputStream out = target != null ? target : shellIn;
                    if (out != null) {
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                        out.write(bytes);
                        out.flush();
                        state.addBytesSent(bytes.length);
                    }
                }
            } catch (InterruptedException | IOException e) {
                if (alive) log.debug("Shell input ended");
            }
        });
    }

    private void showContextMenu() {
        javafx.scene.control.ContextMenu ctx = new javafx.scene.control.ContextMenu();

        javafx.scene.control.MenuItem sessionInfo = new javafx.scene.control.MenuItem("Session Info…");
        sessionInfo.setOnAction(e -> showSessionInfo());

        javafx.scene.control.MenuItem paste = new javafx.scene.control.MenuItem("Paste");
        paste.setOnAction(e -> {
            String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            if (text == null || text.isEmpty()) return;
            Boolean bracketed = Boolean.TRUE.equals(engine.executeScript(
                    "!!(window.term && window.term.bracketedPaste)"));
            String data = Boolean.TRUE.equals(bracketed)
                    ? "\u001B[200~" + text + "\u001B[201~"
                    : text;
            toShell.offer(data);
        });

        // New Tab Here — opens same session with startup command cd <cwd>
        javafx.scene.control.MenuItem newTabHere = new javafx.scene.control.MenuItem("New Tab Here");
        String cwd = state.getCurrentDirectory();
        newTabHere.setDisable(cwd == null || cwd.isBlank() || newTabCallback == null);
        newTabHere.setOnAction(e -> {
            if (newTabCallback == null) return;
            com.novaterm.session.Session dup = session.duplicate();
            dup.setStartupCommand("cd " + cwd);
            newTabCallback.accept(dup);
        });

        javafx.scene.control.MenuItem selectAll = new javafx.scene.control.MenuItem("Select All");
        selectAll.setOnAction(e -> {
            Object text = engine.executeScript("window.term ? window.term.getScrollbackText() : ''");
            String s = text instanceof String str ? str : "";
            if (!s.isEmpty()) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(s);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            }
        });

        javafx.scene.control.MenuItem saveLog = new javafx.scene.control.MenuItem("Save Scrollback…");
        saveLog.setOnAction(e -> saveScrollback());

        javafx.scene.control.CheckMenuItem broadcast = new javafx.scene.control.CheckMenuItem("Broadcast Input to All Tabs");
        broadcast.setSelected(broadcastMode);
        broadcast.setOnAction(e -> setBroadcastMode(broadcast.isSelected()));

        javafx.scene.control.CheckMenuItem logItem = new javafx.scene.control.CheckMenuItem("Log Session to File");
        logItem.setSelected(logWriter != null);
        logItem.setOnAction(e -> {
            if (logItem.isSelected()) startLogging(); else stopLogging();
        });

        // Clear screen / scrollback
        javafx.scene.control.MenuItem clearScrollback = new javafx.scene.control.MenuItem("Clear Scrollback");
        clearScrollback.setOnAction(e ->
            engine.executeScript("if(window.term){window.term.scrollback=[];window.term.render();}"));

        ctx.getItems().addAll(sessionInfo, paste, selectAll, newTabHere,
                new javafx.scene.control.SeparatorMenuItem(),
                saveLog, clearScrollback, logItem, broadcast);
        ctx.show(tab.getContent(), javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void showSessionInfo() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Session Info");
        a.setHeaderText(session.getUsername() + "@" + session.getHost() + ":" + session.getPort());
        String status = state.isConnected() ? "Connected" : "Disconnected";
        a.setContentText(
            "Status:         " + status + "\n" +
            "Connected for:  " + state.connectedDuration() + "\n" +
            "Reconnects:     " + Math.max(0, state.getReconnectCount() - 1) + "\n" +
            "Bytes received: " + SessionTabState.formatBytes(state.getBytesReceived()) + "\n" +
            "Bytes sent:     " + SessionTabState.formatBytes(state.getBytesSent()) + "\n" +
            "Auth type:      " + session.getAuthType() + "\n" +
            (session.getJumpHost() != null ? "Jump host:      " + session.getJumpHost() + "\n" : "") +
            (session.getGroup() != null ? "Group:          " + session.getGroup() : "")
        );
        a.show();
    }

    private void saveScrollback() {
        // Get the terminal text as a plain string from JS
        Object rawObj = engine.executeScript(
                "window.term ? window.term.getScrollbackText() : ''");
        String text = rawObj instanceof String s ? s : String.valueOf(rawObj);

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save Terminal Log");
        chooser.setInitialFileName(session.getHost() + "-terminal.log");
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("Text Files", "*.txt", "*.log"),
                new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*"));
        java.io.File file = chooser.showSaveDialog(tab.getContent().getScene().getWindow());
        if (file == null) return;
        try {
            java.nio.file.Files.writeString(file.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).show();
        }
    }

    private void writeToTerm(String escapedMsg) {
        Platform.runLater(() -> engine.executeScript("writeRaw('" + escapedMsg + "')"));
    }

    // ── Password prompt ───────────────────────────────────────────────────────

    private String promptPassword() {
        if (session.getAuthType() == Session.AuthType.KEY) {
            // Check Keychain for stored passphrase
            String stored = com.novaterm.ssh.KeychainCredentialStore.get()
                    .retrieve(session.getUsername(), session.getPrivateKeyPath() != null
                            ? session.getPrivateKeyPath() : session.getHost(), session.getPort());
            if (stored != null) return stored;
            return promptCredential("Key Passphrase",
                    "Enter passphrase for key:\n" + session.getPrivateKeyPath(),
                    "Passphrase (leave blank if unencrypted)", true);
        }
        if (session.getAuthType() != Session.AuthType.PASSWORD) return "";
        // Check Keychain for stored password
        String stored = com.novaterm.ssh.KeychainCredentialStore.get()
                .retrieve(session.getUsername(), session.getHost(), session.getPort());
        if (stored != null) return stored;
        return promptCredential("SSH Password",
                session.getUsername() + "@" + session.getHost() + ":" + session.getPort(),
                "Password", false);
    }

    private String promptCredential(String title, String header, String promptText, boolean isKey) {
        String[] result = {null};
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            Dialog<String> dlg = new Dialog<>();
            dlg.setTitle(title);
            dlg.setHeaderText(header);

            ButtonType okBtn = new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE);
            dlg.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

            PasswordField pw = new PasswordField();
            pw.setPromptText(promptText);
            pw.setPrefWidth(300);

            Label hint = new Label(promptText + ":");
            hint.setStyle("-fx-text-fill: #1f2328; -fx-font-size: 12px;");

            javafx.scene.control.CheckBox saveToKeychain = new javafx.scene.control.CheckBox("Save to Keychain");
            saveToKeychain.setSelected(false);
            saveToKeychain.setStyle("-fx-text-fill:#656d76; -fx-font-size:11px;");

            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, hint, pw, saveToKeychain);
            box.setPadding(new javafx.geometry.Insets(12, 0, 4, 0));
            dlg.getDialogPane().setContent(box);
            dlg.getDialogPane().setPrefWidth(420);

            pw.setOnAction(e -> {
                javafx.scene.Node btn = dlg.getDialogPane().lookupButton(okBtn);
                btn.fireEvent(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, true, false, false, null));
            });

            Platform.runLater(pw::requestFocus);
            dlg.setResultConverter(b -> {
                if (b != okBtn) return null;
                String pwd = pw.getText();
                if (saveToKeychain.isSelected() && pwd != null && !pwd.isEmpty()) {
                    String keyHost = isKey && session.getPrivateKeyPath() != null
                            ? session.getPrivateKeyPath() : session.getHost();
                    com.novaterm.ssh.KeychainCredentialStore.get()
                            .store(session.getUsername(), keyHost, session.getPort(), pwd);
                }
                return pwd;
            });
            result[0] = dlg.showAndWait().orElse(null);
            latch.countDown();
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    private void disconnect() {
        alive = false;
        if (sshSession != null) SSHManager.get().disconnect(session.getId());
    }

    // ── HTML / JS ─────────────────────────────────────────────────────────────

    private String buildHtml() {
        String vt100js = loadResource("/com/novaterm/web/vt100.js");
        int fontSize = PreferencesDialog.get().getInt(PreferencesDialog.PREF_FONT_SIZE, 13);
        String fontFamily = PreferencesDialog.get().get(PreferencesDialog.PREF_FONT_FAMILY, "SF Mono");
        String colorScheme = PreferencesDialog.get().get(PreferencesDialog.PREF_COLOR_SCHEME, "Github Light");
        String[] colors = ColorScheme.resolve(colorScheme); // [bg, fg, cursorFg]

        int scrollbackLines = PreferencesDialog.get().getInt(PreferencesDialog.PREF_SCROLLBACK, 10000);
        String cursorStyle = PreferencesDialog.get().get(PreferencesDialog.PREF_CURSOR_STYLE, "block");
        int transparency = PreferencesDialog.get().getInt(PreferencesDialog.PREF_TRANSPARENCY, 0);
        int scrollSpeed = PreferencesDialog.get().getInt(PreferencesDialog.PREF_SCROLL_SPEED, 3);
        boolean ligatures = PreferencesDialog.get().getBoolean(PreferencesDialog.PREF_LIGATURES, false);
        double opacity = 1.0 - transparency / 100.0;

        // Inject user prefs into the VT100 JS constants before they're used
        String prefsOverride = String.format("""
            window.__TERM_FONT_SIZE__     = %d;
            window.__TERM_FONT_FAMILY__   = '%s';
            window.__TERM_BG__            = '%s';
            window.__TERM_FG__            = '%s';
            window.__TERM_SCROLLBACK__    = %d;
            window.__TERM_CURSOR_STYLE__  = '%s';
            window.__TERM_OPACITY__       = %s;
            window.__TERM_SCROLL_SPEED__  = %d;
            window.__TERM_LIGATURES__     = %s;
            """, fontSize, fontFamily.replace("'", "\\'"), colors[0], colors[1], scrollbackLines, cursorStyle,
            String.format("%.2f", opacity), scrollSpeed, ligatures ? "true" : "false");

        return ("""
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              html, body { width:100%; height:100%; background:%s; overflow:hidden; }
              canvas { display:block; }
            </style>
            <script>
            """).formatted(colors[0]) + prefsOverride + vt100js + """
            </script>
            </head>
            <body>
            <canvas id="term" tabindex="0"></canvas>
            <script>
            var term = null;
            var decoder = new TextDecoder('utf-8');

            window.onload = function() {
              var canvas = document.getElementById('term');
              canvas.width  = window.innerWidth;
              canvas.height = window.innerHeight;
              var cols = Math.floor(canvas.width  / 8) || 220;
              var rows = Math.floor(canvas.height / 20) || 50;
              term = new VT100Terminal(canvas, cols, rows);
              window.term = term; // expose for external JS calls (find bar etc.)

              // Apply transparency
              if (window.__TERM_OPACITY__ !== undefined && window.__TERM_OPACITY__ < 1.0) {
                document.body.style.opacity = window.__TERM_OPACITY__;
              }

              // Apply font ligatures
              if (window.__TERM_LIGATURES__) {
                canvas.style.fontVariantLigatures = 'common-ligatures';
                canvas.style.textRendering = 'optimizeLegibility';
              }

              window.addEventListener('resize', function() {
                canvas.width  = window.innerWidth;
                canvas.height = window.innerHeight;
                var nc = Math.floor(canvas.width  / term.cw) || 220;
                var nr = Math.floor(canvas.height / term.ch) || 50;
                term.resize(nc, nr);
                if (window.javaBridge) window.javaBridge.sendData('RESIZE:' + nc + ':' + nr);
              });

              canvas.focus();

              // Paste via Cmd+V: read clipboard and send to shell
              canvas.addEventListener('paste', function(e) {
                var text = e.clipboardData ? e.clipboardData.getData('text') : '';
                if (text && window.javaBridge) {
                  if (term.bracketedPaste) {
                    window.javaBridge.sendData('\\x1b[200~' + text + '\\x1b[201~');
                  } else {
                    window.javaBridge.sendData(text);
                  }
                }
                e.preventDefault();
              });

              // ── Mouse selection + mouse reporting ───────────────────────
              var mouseDown = false;
              canvas.addEventListener('mousedown', function(e) {
                var rect = canvas.getBoundingClientRect();
                var cell = term.cellFromPixel(e.clientX - rect.left, e.clientY - rect.top);
                // If terminal app requested mouse events, forward them
                if (term.mouseMode !== 0) {
                  var seq = term.mouseReport(e.button, cell.col, cell.row, true);
                  if (seq && window.javaBridge) window.javaBridge.sendData(seq);
                  e.preventDefault();
                  return;
                }
                if (e.button !== 0) return;
                term.startSelection(cell.col, cell.row);
                mouseDown = true;
                e.preventDefault();
              });
              canvas.addEventListener('mousemove', function(e) {
                if (!mouseDown) return;
                var rect = canvas.getBoundingClientRect();
                var cell = term.cellFromPixel(e.clientX - rect.left, e.clientY - rect.top);
                if (term.mouseMode >= 1002) {
                  var seq = term.mouseReport(0, cell.col, cell.row, true);
                  if (seq && window.javaBridge) window.javaBridge.sendData(seq);
                  return;
                }
                term.updateSelection(cell.col, cell.row);
              });
              canvas.addEventListener('mouseup', function(e) {
                if (term.mouseMode !== 0) {
                  var rect = canvas.getBoundingClientRect();
                  var cell = term.cellFromPixel(e.clientX - rect.left, e.clientY - rect.top);
                  var seq = term.mouseReport(e.button, cell.col, cell.row, false);
                  if (seq && window.javaBridge) window.javaBridge.sendData(seq);
                  return;
                }
                mouseDown = false;
                term.endSelection();
                var txt = term.getSelectedText();
                if (txt) {
                  if (window.javaBridge) window.javaBridge.copyToClipboard(txt);
                } else {
                  term.clearSelection();
                }
              });

              // Right-click context menu: copy/paste
              canvas.addEventListener('contextmenu', function(e) {
                e.preventDefault();
                if (window.javaBridge) window.javaBridge.sendData('__RIGHTCLICK__');
              });

              // URL hover: change cursor; Cmd+Click: open URL
              canvas.addEventListener('mousemove', function(e) {
                if (term && term.mouseMode === 0) {
                  var rect = canvas.getBoundingClientRect();
                  var url = term.urlAtPixel(e.clientX - rect.left, e.clientY - rect.top);
                  canvas.style.cursor = url ? 'pointer' : 'text';
                }
              });
              canvas.addEventListener('click', function(e) {
                if (e.metaKey && term) {
                  var rect = canvas.getBoundingClientRect();
                  var url = term.urlAtPixel(e.clientX - rect.left, e.clientY - rect.top);
                  if (url && window.javaBridge) { window.javaBridge.openUrl(url); e.preventDefault(); }
                }
              });

              // Mouse wheel scroll: navigate scrollback when on primary screen,
              // send VT sequences to the app when on alt screen (vim, htop, etc.)
              var scrollSpeed = (typeof window.__TERM_SCROLL_SPEED__ !== 'undefined') ? window.__TERM_SCROLL_SPEED__ : 3;
              canvas.addEventListener('wheel', function(e) {
                e.preventDefault();
                var lines = Math.max(1, Math.round(Math.abs(e.deltaY) / 40 * scrollSpeed));
                if (term.altScreen === null) {
                  // Primary screen: scroll through scrollback buffer
                  if (e.deltaY < 0) {
                    term.scrollBackBy(-lines);
                  } else {
                    term.scrollBackBy(lines);
                  }
                } else {
                  // Alt screen (vim, less, htop…): send cursor keys to the app
                  var seq = e.deltaY < 0 ? '\\x1b[A' : '\\x1b[B';
                  for (var i = 0; i < lines; i++) {
                    if (window.javaBridge) window.javaBridge.sendData(seq);
                  }
                }
              }, {passive: false});

              canvas.addEventListener('keydown', function(e) {
                // Any keystroke snaps back to live view if scrolled into history
                if (term.altScreen === null) term.scrollToBottom();
                var d = '';
                if (e.ctrlKey && !e.altKey) {
                  var k = e.key.toUpperCase();
                  if (k >= 'A' && k <= 'Z') d = String.fromCharCode(k.charCodeAt(0) - 64);
                  else if (k === '[') d = '\\x1b';
                  else if (k === ']') d = '\\x1d';
                  else if (k === '\\\\') d = '\\x1c';
                  // Ctrl+Arrow: word movement
                  else if (e.key === 'ArrowRight') d = '\\x1b[1;5C';
                  else if (e.key === 'ArrowLeft')  d = '\\x1b[1;5D';
                  else if (e.key === 'ArrowUp')    d = '\\x1b[1;5A';
                  else if (e.key === 'ArrowDown')  d = '\\x1b[1;5B';
                } else if (e.altKey && !e.ctrlKey) {
                  if (e.key.length === 1) d = '\\x1b' + e.key;
                } else {
                  switch(e.key) {
                    case 'Enter':     d = '\\r'; break;
                    case 'Backspace': d = '\\x7f'; break;
                    case 'Tab':       d = '\\t'; break;
                    case 'Escape':    d = '\\x1b'; break;
                    case 'ArrowUp':   d = '\\x1b[A'; break;
                    case 'ArrowDown': d = '\\x1b[B'; break;
                    case 'ArrowRight':d = '\\x1b[C'; break;
                    case 'ArrowLeft': d = '\\x1b[D'; break;
                    case 'Home':      d = '\\x1b[H'; break;
                    case 'End':       d = '\\x1b[F'; break;
                    case 'Delete':    d = '\\x1b[3~'; break;
                    case 'Insert':    d = '\\x1b[2~'; break;
                    case 'PageUp':    d = '\\x1b[5~'; break;
                    case 'PageDown':  d = '\\x1b[6~'; break;
                    case 'F1':  d = '\\x1bOP'; break;
                    case 'F2':  d = '\\x1bOQ'; break;
                    case 'F3':  d = '\\x1bOR'; break;
                    case 'F4':  d = '\\x1bOS'; break;
                    case 'F5':  d = '\\x1b[15~'; break;
                    case 'F6':  d = '\\x1b[17~'; break;
                    case 'F7':  d = '\\x1b[18~'; break;
                    case 'F8':  d = '\\x1b[19~'; break;
                    case 'F9':  d = '\\x1b[20~'; break;
                    case 'F10': d = '\\x1b[21~'; break;
                    case 'F11': d = '\\x1b[23~'; break;
                    case 'F12': d = '\\x1b[24~'; break;
                    default:
                      if (e.key.length === 1) d = e.key;
                  }
                }
                if (d) { e.preventDefault(); if (window.javaBridge) window.javaBridge.sendData(d); }
              });
            };

            function receiveData(b64) {
              if (!term) return;
              var bin = atob(b64);
              var bytes = new Uint8Array(bin.length);
              for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              var str = decoder.decode(bytes);
              term.write(str);
            }

            function writeRaw(str) {
              if (term) term.write(str);
            }
            </script>
            </body>
            </html>
            """;
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Send a raw string to the shell (called from outside, e.g. snippet panel). */
    public void sendCommand(String data) { toShell.offer(data); }

    public Tab getTab() { return tab; }
    public SessionTabState getState() { return state; }
    public javafx.scene.layout.BorderPane getContainer() { return container; }

    public void setMacroManager(MacroManager mm) { this.macroManager = mm; }
    public void setNewTabCallback(java.util.function.Consumer<com.novaterm.session.Session> cb) { this.newTabCallback = cb; }

    /** Small replay-progress banner, shown at top of container during macro replay. */
    private javafx.scene.control.Label replayBanner;
    private volatile boolean replayCancelled;

    private javafx.scene.control.Label ensureReplayBanner() {
        if (replayBanner == null) {
            replayBanner = new javafx.scene.control.Label();
            replayBanner.setId("replay-banner");
            replayBanner.setStyle("-fx-background-color:#d0d7de; -fx-text-fill:#1f2328; " +
                    "-fx-font-size:11px; -fx-padding:4 10 4 10; -fx-background-radius:0;");
            replayBanner.setMaxWidth(Double.MAX_VALUE);
            javafx.scene.control.Button cancelBtn = new javafx.scene.control.Button("✕ Cancel");
            cancelBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#cf222e; -fx-cursor:hand; -fx-font-size:10px;");
            cancelBtn.setOnAction(e -> replayCancelled = true);
            javafx.scene.layout.HBox wrapper = new javafx.scene.layout.HBox(8, replayBanner, cancelBtn);
            wrapper.setId("replay-banner-row");
            wrapper.setStyle("-fx-background-color:#d0d7de; -fx-padding:0;");
            wrapper.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            javafx.scene.layout.HBox.setHgrow(replayBanner, javafx.scene.layout.Priority.ALWAYS);
            if (container != null) container.setTop(wrapper);
        }
        return replayBanner;
    }

    public void playMacro(MacroManager.Macro macro) {
        int total = macro.steps().size();
        replayCancelled = false;
        Platform.runLater(() -> {
            ensureReplayBanner();
            replayBanner.setText("▶ Replaying \"" + macro.name() + "\"  0 / " + total);
            if (container.getTop() != null) container.getTop().setVisible(true);
        });
        Thread.ofVirtual().start(() -> {
            for (int i = 0; i < total && !replayCancelled; i++) {
                toShell.offer(macro.steps().get(i));
                final int cur = i + 1;
                Platform.runLater(() -> replayBanner.setText(
                        "▶ Replaying \"" + macro.name() + "\"  " + cur + " / " + total));
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            Platform.runLater(() -> {
                if (container.getTop() != null) container.getTop().setVisible(false);
            });
        });
    }

    public void startRecording(String name) {
        if (macroManager != null) macroManager.startRecording(name);
        Platform.runLater(() -> updateRecordingIndicator());
    }

    public MacroManager.Macro stopRecording() {
        if (macroManager == null) return null;
        MacroManager.Macro m = macroManager.stopRecording();
        Platform.runLater(() -> updateRecordingIndicator());
        return m;
    }

    public boolean isRecording() {
        return macroManager != null && macroManager.isRecording();
    }

    private void updateRecordingIndicator() {
        boolean rec = isRecording();
        // Show/hide a recording dot on the tab label
        javafx.scene.Node graphic = tab.getGraphic();
        if (graphic instanceof javafx.scene.control.Label lbl) {
            String text = lbl.getText();
            if (rec && !text.startsWith("● ")) lbl.setText("● " + text);
            else if (!rec && text.startsWith("● ")) lbl.setText(text.substring(2));
        }
    }
}
