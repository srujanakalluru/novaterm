package com.novaterm.ui;

import com.novaterm.plugin.PluginContext;
import com.novaterm.plugin.PluginLoader;
import com.novaterm.session.Session;
import com.novaterm.session.SessionStore;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MainWindow {
    private final BorderPane root;
    private final TabPane tabPane;
    private final SessionManagerPanel sessionPanel;
    private final PluginContext pluginContext;
    private final PluginLoader pluginLoader;
    private final SessionStore sessionStore;
    private final Map<Tab, SessionTabState> tabStates = new HashMap<>();
    private final Map<Tab, TerminalTab> terminalTabs = new HashMap<>();
    private final MacroManager macroManager = new MacroManager();
    // Ring buffer of recently closed sessions (most recent first)
    private final java.util.ArrayDeque<com.novaterm.session.Session> recentlyClosed = new java.util.ArrayDeque<>();
    private static final int MAX_RECENTLY_CLOSED = 10;

    public MainWindow(SessionStore sessionStore, PluginLoader pluginLoader) {
        this.sessionStore = sessionStore;
        this.pluginLoader = pluginLoader;

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setId("main-tab-pane");

        sessionPanel = new SessionManagerPanel(sessionStore, this::openTerminalTab, this::editSession, this::deleteSession);

        // Snippet panel (command snippets side panel)
        SnippetPanel snippetPanel = new SnippetPanel(cmd -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            SessionTabState st = tabStates.get(sel);
            // Send via the terminal's toShell queue — no direct access, so via JS bridge workaround
            // We use the TerminalTab reference stored in terminalTabs
            TerminalTab tt = terminalTabs.get(sel);
            if (tt != null) tt.sendCommand(cmd + "\r");
        });

        TabPane sidebarTabs = new TabPane();
        sidebarTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sidebarTabs.setId("sidebar-tabs");
        Tab sessionsTab = new Tab("Sessions", sessionPanel.getRoot());
        Tab snippetsTab = new Tab("Snippets", snippetPanel.getRoot());
        sidebarTabs.getTabs().addAll(sessionsTab, snippetsTab);

        SplitPane splitPane = new SplitPane(sidebarTabs, tabPane);
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.setDividerPositions(0.22);
        SplitPane.setResizableWithParent(sessionPanel.getRoot(), false);

        MenuBar menuBar = buildMenuBar();

        javafx.scene.control.Label statusLabel = new javafx.scene.control.Label("Ready");
        statusLabel.setId("status-label");
        javafx.scene.control.Label tunnelLabel = new javafx.scene.control.Label();
        tunnelLabel.setId("tunnel-label");
        tunnelLabel.setVisible(false);
        javafx.scene.control.Label broadcastLabel = new javafx.scene.control.Label("⬡ BROADCAST");
        broadcastLabel.setId("broadcast-label");
        broadcastLabel.setVisible(false);
        javafx.scene.control.Label clockLabel = new javafx.scene.control.Label();
        clockLabel.setId("clock-label");
        javafx.scene.control.Label throughputLabel = new javafx.scene.control.Label();
        throughputLabel.setId("status-label");
        throughputLabel.setStyle("-fx-text-fill:#848d97; -fx-font-size:10px; -fx-font-family:'SF Mono',Menlo,monospace;");
        throughputLabel.setVisible(false);

        // Sample byte counters for throughput calculation
        final long[] lastRx = { 0L };
        final long[] lastTs = { System.currentTimeMillis() };

        // Update clock every second; also refresh tunnel count badge
        javafx.animation.Timeline clock = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                clockLabel.setText(java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                int totalTunnels = tabStates.values().stream()
                        .mapToInt(st -> st.getTunnelManager().getTunnels().size()).sum();
                if (totalTunnels > 0) {
                    tunnelLabel.setText("⇄ " + totalTunnels + " tunnel" + (totalTunnels > 1 ? "s" : ""));
                    tunnelLabel.setVisible(true);
                } else {
                    tunnelLabel.setVisible(false);
                }
                broadcastLabel.setVisible(TerminalTab.isBroadcastMode());
                // Throughput for active tab
                Tab selTab = tabPane.getSelectionModel().getSelectedItem();
                SessionTabState tst = selTab != null ? tabStates.get(selTab) : null;
                if (tst != null && tst.isConnected()) {
                    long now = System.currentTimeMillis();
                    long rx = tst.getBytesReceived();
                    long elapsed = Math.max(1, now - lastTs[0]);
                    long rxPerSec = (rx - lastRx[0]) * 1000 / elapsed;
                    lastRx[0] = rx;
                    lastTs[0] = now;
                    if (rxPerSec > 10) {
                        throughputLabel.setText("↓ " + SessionTabState.formatBytes(rxPerSec) + "/s");
                        throughputLabel.setVisible(true);
                    } else {
                        throughputLabel.setVisible(false);
                    }
                } else {
                    throughputLabel.setVisible(false);
                    lastRx[0] = 0;
                    lastTs[0] = System.currentTimeMillis();
                }
            })
        );
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();
        clockLabel.setText(java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));

        // Handle duplicate-tab requests
        tabPane.addEventHandler(TerminalTab.DUPLICATE_TAB_EVENT, e -> {
            Tab current = tabPane.getSelectionModel().getSelectedItem();
            if (current != null) {
                SessionTabState st = tabStates.get(current);
                if (st != null) openTerminalTab(st.getSession());
            }
        });

        // Update status when tab changes; also clear activity badge on the newly selected tab
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) { statusLabel.setText("Ready"); return; }
            SessionTabState st = tabStates.get(sel);
            if (st != null) {
                statusLabel.setText(st.isConnected()
                    ? "● Connected — " + st.getSession().getUsername() + "@" + st.getSession().getHost()
                    : "○ " + st.getSession().toString());
            } else {
                statusLabel.setText(sel.getText() != null ? sel.getText() : "");
            }
            TerminalTab tt = terminalTabs.get(sel);
            if (tt != null) tt.clearActivityBadge();
        });

        javafx.scene.layout.HBox statusBar = new javafx.scene.layout.HBox(8);
        statusBar.setId("status-bar");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        statusBar.getChildren().addAll(statusLabel, spacer, throughputLabel, broadcastLabel, tunnelLabel, clockLabel);

        QuickConnectBar quickBar = new QuickConnectBar(this::openTerminalTab, this::openLocalTerminal);

        VBox topArea = new VBox(menuBar, quickBar.getRoot());

        root = new BorderPane();
        root.setTop(topArea);
        root.setCenter(splitPane);
        root.setBottom(statusBar);
        root.setId("main-root");

        pluginContext = buildPluginContext();
    }

    private PluginContext buildPluginContext() {
        return new PluginContext() {
            @Override
            public void registerSidePanel(String label, Supplier<Pane> factory) {
                Tab pluginTab = new Tab(label);
                pluginTab.setContent(factory.get());
                tabPane.getTabs().add(pluginTab);
            }

            @Override
            public void registerMenuAction(String label, Runnable action) {
                MenuBar mb = (MenuBar) root.getTop();
                Menu pluginsMenu = mb.getMenus().stream()
                        .filter(m -> "Plugins".equals(m.getText()))
                        .findFirst().orElseThrow();
                MenuItem item = new MenuItem(label);
                item.setOnAction(e -> action.run());
                pluginsMenu.getItems().add(item);
            }

            @Override
            public void openTerminalTab(Session session) {
                MainWindow.this.openTerminalTab(session);
            }

            @Override
            public void showNotification(String title, String message) {
                javafx.application.Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle(title);
                    alert.setHeaderText(null);
                    alert.setContentText(message);
                    alert.show();
                });
            }
        };
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        bar.setUseSystemMenuBar(true); // macOS native menu bar

        Menu fileMenu = new Menu("File");
        MenuItem newSession = new MenuItem("New Session...");
        newSession.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+N"));
        newSession.setOnAction(e -> showNewSessionDialog());
        MenuItem newLocal = new MenuItem("New Local Terminal");
        newLocal.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+T"));
        newLocal.setOnAction(e -> openLocalTerminal());
        MenuItem closeTab = new MenuItem("Close Tab");
        closeTab.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+W"));
        closeTab.setOnAction(e -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel != null && sel.isClosable()) tabPane.getTabs().remove(sel);
        });
        MenuItem reopenTab = new MenuItem("Reopen Last Closed Tab");
        reopenTab.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+Z"));
        reopenTab.setOnAction(e -> {
            if (!recentlyClosed.isEmpty()) openTerminalTab(recentlyClosed.removeFirst());
        });
        MenuItem exportSessions = new MenuItem("Export Sessions…");
        exportSessions.setOnAction(e -> exportSessions());
        MenuItem importSessions = new MenuItem("Import Sessions…");
        importSessions.setOnAction(e -> importSessions());
        MenuItem importSSHConfig = new MenuItem("Import from SSH Config…");
        importSSHConfig.setOnAction(e -> importFromSSHConfig());
        fileMenu.getItems().addAll(newSession, newLocal, new SeparatorMenuItem(), closeTab, reopenTab,
                new SeparatorMenuItem(), exportSessions, importSessions, importSSHConfig,
                new SeparatorMenuItem(), quitItem());

        Menu viewMenu = new Menu("View");
        MenuItem toggleSidebar = new MenuItem("Toggle Sidebar");
        toggleSidebar.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+S"));
        toggleSidebar.setOnAction(e -> toggleSidebarVisible());
        MenuItem sftpItem = new MenuItem("Show SFTP Browser");
        sftpItem.setOnAction(e -> openSFTPForCurrentTab());
        MenuItem tunnelItem = new MenuItem("Port Forwarding...");
        tunnelItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+T"));
        tunnelItem.setOnAction(e -> openTunnelDialog());
        MenuItem splitRight = new MenuItem("Split Right");
        splitRight.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+D"));
        splitRight.setOnAction(e -> splitCurrentTab(true));
        MenuItem splitDown = new MenuItem("Split Down");
        splitDown.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+D"));
        splitDown.setOnAction(e -> splitCurrentTab(false));
        CheckMenuItem broadcastItem = new CheckMenuItem("Broadcast Input to All Tabs");
        broadcastItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+B"));
        broadcastItem.setOnAction(e -> TerminalTab.setBroadcastMode(broadcastItem.isSelected()));
        MenuItem statsItem = new MenuItem("Connection Statistics…");
        statsItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+I"));
        statsItem.setOnAction(e -> showConnectionStats());
        viewMenu.getItems().addAll(toggleSidebar, new SeparatorMenuItem(), sftpItem, tunnelItem,
                new SeparatorMenuItem(), splitRight, splitDown,
                new SeparatorMenuItem(), broadcastItem,
                new SeparatorMenuItem(), statsItem);

        Menu pluginsMenu = new Menu("Plugins");
        MenuItem managePlugins = new MenuItem("Manage Plugins...");
        managePlugins.setOnAction(e -> showPluginManager());
        pluginsMenu.getItems().add(managePlugins);

        // Preferences goes in the app menu on macOS (shown as "NovaTerm > Preferences...")
        Menu appMenu = new Menu("NovaTerm");
        MenuItem prefsItem = new MenuItem("Preferences...");
        prefsItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+,"));
        prefsItem.setOnAction(e -> new PreferencesDialog().showAndWait());
        MenuItem keybindingsItem = new MenuItem("Keyboard Shortcuts…");
        keybindingsItem.setOnAction(e -> new KeybindingsDialog().showAndWait());
        MenuItem knownHostsItem = new MenuItem("Known Hosts…");
        knownHostsItem.setOnAction(e -> new KnownHostsDialog().showAndWait());
        appMenu.getItems().addAll(prefsItem, keybindingsItem, new SeparatorMenuItem(), knownHostsItem);

        Menu macrosMenu = new Menu("Macros");
        MenuItem startRec = new MenuItem("Start Recording…");
        startRec.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+R"));
        startRec.setOnAction(e -> startMacroRecording());
        MenuItem stopRec = new MenuItem("Stop Recording");
        stopRec.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+S"));
        stopRec.setOnAction(e -> stopMacroRecording());
        MenuItem manageMacros = new MenuItem("Manage Macros…");
        manageMacros.setOnAction(e -> showMacroDialog());
        macrosMenu.getItems().addAll(startRec, stopRec, new SeparatorMenuItem(), manageMacros);

        Menu helpMenu = new Menu("Help");
        MenuItem shortcuts = new MenuItem("Keyboard Shortcuts");
        shortcuts.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Shift+/"));
        shortcuts.setOnAction(e -> new KeyboardShortcutsDialog().showAndWait());
        MenuItem about = new MenuItem("About NovaTerm");
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().addAll(shortcuts, new SeparatorMenuItem(), about);
        bar.getMenus().addAll(appMenu, fileMenu, viewMenu, pluginsMenu, macrosMenu, helpMenu);
        return bar;
    }

    private MenuItem quitItem() {
        MenuItem quit = new MenuItem("Quit NovaTerm");
        quit.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Meta+Q"));
        quit.setOnAction(e -> javafx.application.Platform.exit());
        return quit;
    }

    public void openTerminalTab(Session session) {
        TerminalTab termTab = new TerminalTab(session, sessionStore);
        termTab.setMacroManager(macroManager);
        termTab.setNewTabCallback(this::openTerminalTab);
        Tab tab = termTab.getTab();
        tabStates.put(tab, termTab.getState());
        terminalTabs.put(tab, termTab);
        tab.setOnClosed(e -> {
            SessionTabState st = tabStates.remove(tab);
            terminalTabs.remove(tab);
            // Track in recently-closed ring buffer
            if (st != null && st.getSession() != null
                    && !"__local__".equals(st.getSession().getGroup())) {
                recentlyClosed.addFirst(st.getSession());
                while (recentlyClosed.size() > MAX_RECENTLY_CLOSED) recentlyClosed.removeLast();
            }
        });
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    public void openLocalTerminal() {
        Session local = new Session("Local Shell", "localhost", 22, System.getProperty("user.name"));
        local.setGroup("__local__");
        openTerminalTab(local);
    }

    private void openSFTPForCurrentTab() {
        SessionTabState state = activeTabState();
        if (state == null || !state.isConnected()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "No active SSH session. Connect to a server first.").show();
            return;
        }
        openSFTPPanel(state.getSession(), state.getClientSession());
    }

    /** Open an SFTP panel for a connected session, docked as a bottom split in the tab. */
    public void openSFTPPanel(Session session, org.apache.sshd.client.session.ClientSession cs) {
        SFTPPanel sftp = new SFTPPanel(cs);
        Tab tab = new Tab("SFTP: " + session.getHost(), sftp.getRoot());
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    public void showNewSessionDialog() {
        SessionDialog dialog = new SessionDialog(null);
        dialog.showAndWait().ifPresent(session -> {
            sessionStore.add(session);
            sessionPanel.refresh();
            openTerminalTab(session);
        });
    }

    private void editSession(Session session) {
        SessionDialog dialog = new SessionDialog(session);
        dialog.showAndWait().ifPresent(updated -> {
            sessionStore.update(updated);
            sessionPanel.refresh();
        });
    }

    private void deleteSession(Session session) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete session \"" + session + "\"?");
        confirm.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> {
            sessionStore.remove(session);
            sessionPanel.refresh();
        });
    }

    private void showPluginManager() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Plugin Manager");
        alert.setHeaderText("Installed Plugins");
        StringBuilder sb = new StringBuilder();
        pluginLoader.getLoaded().forEach(p ->
                sb.append("• ").append(p.getName()).append(" v").append(p.getVersion()).append("\n"));
        if (sb.isEmpty()) {
            alert.setContentText("No plugins installed.\n\nDrop .jar files into:\n"
                    + "~/Library/Application Support/NovaTerm/plugins/");
        } else {
            alert.setContentText(sb.toString());
        }
        alert.show();
    }

    private void openTunnelDialog() {
        SessionTabState state = activeTabState();
        if (state == null || !state.isConnected()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "No active SSH session. Connect to a server first.").show();
            return;
        }
        com.novaterm.session.Session sess = state.getSession();
        new TunnelDialog(state.getClientSession(), state.getTunnelManager(), tc -> {
            sess.getTunnels().add(tc);
            sessionStore.update(sess);
        }).show();
    }

    public void toggleSidebarVisible() {
        SplitPane sp = (SplitPane) root.getCenter();
        var sidebar = sp.getItems().get(0);
        sidebar.setVisible(!sidebar.isVisible());
        sidebar.setManaged(sidebar.isVisible());
    }

    private SessionTabState activeTabState() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return selected != null ? tabStates.get(selected) : null;
    }

    public void splitCurrentTab(boolean horizontal) {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        SessionTabState state = tabStates.get(selected);
        Session session = state != null ? state.getSession() : null;
        if (session == null) return;

        // If the tab is already split, add another pane to the existing SplitPane
        javafx.scene.Node currentContent = selected.getContent();

        TerminalTab newTermTab = new TerminalTab(session, sessionStore);
        newTermTab.setMacroManager(macroManager);
        // Register the new pane — it shares the tab key with a suffix marker
        // We track it in a simple list stored on the tab's user data
        @SuppressWarnings("unchecked")
        java.util.List<TerminalTab> splitTabs = (currentContent.getUserData() instanceof java.util.List<?> l)
                ? (java.util.List<TerminalTab>) l : null;

        if (currentContent instanceof SplitPane existingSplit) {
            // Already split — just add another pane
            existingSplit.getItems().add(newTermTab.getContainer());
            double pos = 1.0 / existingSplit.getItems().size();
            double[] dividers = new double[existingSplit.getItems().size() - 1];
            for (int i = 0; i < dividers.length; i++) dividers[i] = (i + 1) * pos;
            existingSplit.setDividerPositions(dividers);
        } else if (currentContent instanceof javafx.scene.layout.BorderPane orig) {
            SplitPane split = new SplitPane();
            split.setOrientation(horizontal
                    ? javafx.geometry.Orientation.HORIZONTAL
                    : javafx.geometry.Orientation.VERTICAL);
            split.getItems().addAll(orig, newTermTab.getContainer());
            split.setDividerPositions(0.5);
            selected.setContent(split);
        }
    }

    private TerminalTab activeTerminalTab() {
        Tab sel = tabPane.getSelectionModel().getSelectedItem();
        return sel != null ? terminalTabs.get(sel) : null;
    }

    private void startMacroRecording() {
        TerminalTab tt = activeTerminalTab();
        if (tt == null) {
            new Alert(Alert.AlertType.INFORMATION, "No active terminal tab.").show();
            return;
        }
        TextInputDialog dlg = new TextInputDialog("Macro " + (macroManager.getMacros().size() + 1));
        dlg.setTitle("Start Recording");
        dlg.setHeaderText("Enter a name for this macro:");
        dlg.setContentText("Name:");
        dlg.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) tt.startRecording(name.trim());
        });
    }

    private void stopMacroRecording() {
        TerminalTab tt = activeTerminalTab();
        if (tt == null || !tt.isRecording()) {
            new Alert(Alert.AlertType.INFORMATION, "Not currently recording.").show();
            return;
        }
        MacroManager.Macro saved = tt.stopRecording();
        new Alert(Alert.AlertType.INFORMATION,
                "Macro \"" + saved.name() + "\" saved (" + saved.steps().size() + " steps).").show();
    }

    private void showMacroDialog() {
        TerminalTab tt = activeTerminalTab();
        new MacroDialog(macroManager, macro -> {
            if (tt != null) tt.playMacro(macro);
            else new Alert(Alert.AlertType.INFORMATION, "No active terminal tab.").show();
        }).show();
    }

    private void exportSessions() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export Sessions");
        chooser.setInitialFileName("novaterm-sessions.json");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("JSON Files", "*.json"));
        java.io.File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;
        try {
            sessionStore.exportTo(file);
            new Alert(Alert.AlertType.INFORMATION,
                    "Exported " + sessionStore.getSessions().size() + " session(s) to:\n" + file.getPath()).show();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage()).show();
        }
    }

    private void importSessions() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Import Sessions");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("JSON Files", "*.json"));
        java.io.File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        try {
            int count = sessionStore.importFrom(file);
            sessionPanel.refresh();
            new Alert(Alert.AlertType.INFORMATION,
                    "Imported " + count + " new session(s).").show();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Import failed: " + ex.getMessage()).show();
        }
    }

    private void importFromSSHConfig() {
        try {
            java.util.List<com.novaterm.session.Session> imported =
                    com.novaterm.ssh.SshConfigImporter.importFromConfig();
            if (imported.isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION,
                        "No hosts found in ~/.ssh/config (or file not found).").show();
                return;
            }
            // Deduplicate by host+user
            java.util.Set<String> existing = new java.util.HashSet<>();
            sessionStore.getSessions().forEach(s -> existing.add(s.getUsername() + "@" + s.getHost()));
            int added = 0;
            for (com.novaterm.session.Session s : imported) {
                if (existing.add(s.getUsername() + "@" + s.getHost())) {
                    sessionStore.add(s);
                    added++;
                }
            }
            int finalAdded = added;
            sessionPanel.refresh();
            new Alert(Alert.AlertType.INFORMATION,
                    "Imported " + finalAdded + " new session(s) from ~/.ssh/config\n"
                    + "(found " + imported.size() + " total, skipped " + (imported.size() - finalAdded) + " duplicates).").show();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to read ~/.ssh/config: " + ex.getMessage()).show();
        }
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About NovaTerm");
        alert.setHeaderText("NovaTerm 1.0.0");
        alert.setContentText("A modern macOS SSH terminal inspired by MobaXterm.\nBuilt with JavaFX + Apache MINA SSHD.");
        alert.show();
    }

    /** Wire built-in commands into the command palette. Called from App after Stage is ready. */
    public void registerPaletteCommands(CommandPalette palette) {
        palette.addCommand("New Session…",           "Create and connect to a new SSH session",   this::showNewSessionDialog);
        palette.addCommand("New Local Terminal",      "Open a local shell tab",                    this::openLocalTerminal);
        palette.addCommand("Preferences…",           "Open application preferences",              () -> new PreferencesDialog().showAndWait());
        palette.addCommand("Known Hosts…",           "Manage trusted SSH server keys",            () -> new KnownHostsDialog().showAndWait());
        palette.addCommand("Keyboard Shortcuts",     "Show keyboard shortcut reference",          () -> new KeyboardShortcutsDialog().showAndWait());
        palette.addCommand("Toggle Sidebar",         "Show or hide the session panel",            this::toggleSidebarVisible);
        palette.addCommand("Show SFTP Browser",      "Open SFTP file browser for active session", this::openSFTPForCurrentTab);
        palette.addCommand("Port Forwarding…",       "Configure SSH port tunnels",                this::openTunnelDialog);
        palette.addCommand("Export Sessions…",       "Save all sessions to a JSON file",          this::exportSessions);
        palette.addCommand("Import Sessions…",       "Load sessions from a JSON file",            this::importSessions);
        palette.addCommand("Import from SSH Config", "Load hosts from ~/.ssh/config",             this::importFromSSHConfig);
        palette.addCommand("Manage Macros…",         "View, edit and replay recorded macros",     this::showMacroDialog);
        palette.addCommand("Start Recording Macro",  "Begin recording a terminal macro",          this::startMacroRecording);
        palette.addCommand("Stop Recording Macro",   "Stop and save the current macro recording", this::stopMacroRecording);
    }

    public BorderPane getRoot() { return root; }
    public PluginContext getPluginContext() { return pluginContext; }
    public TabPane getTabPane() { return tabPane; }

    private void showConnectionStats() {
        new ConnectionStatsDialog(() -> {
            java.util.List<ConnectionStatsDialog.TabStat> stats = new java.util.ArrayList<>();
            for (Tab t : tabPane.getTabs()) {
                SessionTabState state = tabStates.get(t);
                if (state == null) continue;
                String title = "(tab)";
                if (t.getGraphic() instanceof javafx.scene.layout.HBox hb) {
                    for (var n : hb.getChildren()) {
                        if (n instanceof Label lbl && !lbl.getText().isBlank()) { title = lbl.getText(); break; }
                    }
                }
                stats.add(new ConnectionStatsDialog.TabStat(
                        title,
                        state.getSession().getHost() + ":" + state.getSession().getPort(),
                        state.connectedDuration(),
                        SessionTabState.formatBytes(state.getBytesReceived()),
                        SessionTabState.formatBytes(state.getBytesSent()),
                        String.valueOf(state.getReconnectCount()),
                        state.getCurrentDirectory() != null ? state.getCurrentDirectory() : "—"
                ));
            }
            return stats;
        }).show();
    }

    // ── Public action entry points (called from AcceleratorManager) ─────────

    public void openNewSessionDialog()  { showNewSessionDialog(); }
    public void toggleSidebar()         { toggleSidebarVisible(); }
    public void toggleBroadcast()       { TerminalTab.setBroadcastMode(!TerminalTab.isBroadcastMode()); }

    public void openFind() {
        Tab sel = tabPane.getSelectionModel().getSelectedItem();
        if (sel != null && sel.getUserData() instanceof TerminalTab tt) tt.showFind();
    }

    public void adjustFontSize(int delta) {
        java.util.prefs.Preferences prefs = PreferencesDialog.get();
        int cur = prefs.getInt(PreferencesDialog.PREF_FONT_SIZE, 13);
        int next = (delta == 0) ? 13 : Math.max(8, Math.min(32, cur + delta));
        prefs.putInt(PreferencesDialog.PREF_FONT_SIZE, next);
        TerminalTab.broadcastThemeReload();
    }

}
