package com.novaterm;

import com.novaterm.plugin.PluginLoader;
import com.novaterm.session.SessionStore;
import com.novaterm.ui.AcceleratorManager;
import com.novaterm.ui.ClipboardHistory;
import com.novaterm.ui.CommandPalette;
import com.novaterm.ui.KeybindingStore;
import com.novaterm.ui.MainWindow;
import com.novaterm.ui.TabReorder;
import com.novaterm.ui.TabSwitcherPopup;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App extends Application {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final SessionStore sessionStore = new SessionStore();
    private final PluginLoader pluginLoader = new PluginLoader();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        sessionStore.load();
        ClipboardHistory.get().start();

        MainWindow mainWindow = new MainWindow(sessionStore, pluginLoader);
        pluginLoader.loadAll(mainWindow.getPluginContext());

        Scene scene = new Scene(mainWindow.getRoot(), 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/com/novaterm/styles.css").toExternalForm());

        stage.setTitle("NovaTerm");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(500);

        // Restore saved window geometry
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(App.class);
        double wx = prefs.getDouble("win.x", -1);
        double wy = prefs.getDouble("win.y", -1);
        double ww = prefs.getDouble("win.w", 1200);
        double wh = prefs.getDouble("win.h", 800);
        stage.setWidth(ww);
        stage.setHeight(wh);
        if (wx >= 0) stage.setX(wx);
        if (wy >= 0) stage.setY(wy);

        stage.show();

        // Cmd+1..9 to switch tabs
        for (int i = 1; i <= 9; i++) {
            final int idx = i - 1;
            scene.getAccelerators().put(
                javafx.scene.input.KeyCombination.keyCombination("Meta+" + i),
                () -> {
                    var tabs = mainWindow.getRoot().lookup("#main-tab-pane");
                    if (tabs instanceof javafx.scene.control.TabPane tp
                            && idx < tp.getTabs().size()) {
                        tp.getSelectionModel().select(idx);
                    }
                }
            );
        }

        // Enable tab drag-to-reorder after scene is set
        TabReorder.install(mainWindow.getTabPane());

        // Build all named actions and register with AcceleratorManager for live rebinding
        CommandPalette palette = new CommandPalette(stage, sessionStore, mainWindow::openTerminalTab);
        mainWindow.registerPaletteCommands(palette);

        TabSwitcherPopup tabSwitcher = new TabSwitcherPopup(idx -> {
            var tp = mainWindow.getTabPane();
            if (idx >= 0 && idx < tp.getTabs().size()) tp.getSelectionModel().select(idx);
        });
        Runnable showTabSwitcher = () -> {
            var tp = mainWindow.getTabPane();
            java.util.List<TabSwitcherPopup.TabEntry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < tp.getTabs().size(); i++) {
                var t = tp.getTabs().get(i);
                String title = t.getText() != null && !t.getText().isBlank() ? t.getText() : "(tab " + (i+1) + ")";
                if (t.getGraphic() instanceof javafx.scene.layout.HBox hb) {
                    for (var node : hb.getChildren()) {
                        if (node instanceof javafx.scene.control.Label lbl && !lbl.getText().isBlank()) {
                            title = lbl.getText();
                            break;
                        }
                    }
                }
                String subtitle = t.getTooltip() != null ? t.getTooltip().getText() : "";
                entries.add(new TabSwitcherPopup.TabEntry(title, subtitle, i));
            }
            tabSwitcher.show(stage, entries);
        };

        AcceleratorManager am = AcceleratorManager.get();
        am.setScene(scene);
        am.register("newSession",       () -> mainWindow.openNewSessionDialog());
        am.register("newLocalTerminal", () -> mainWindow.openLocalTerminal());
        am.register("closeTab",         () -> {
            var tp = mainWindow.getTabPane();
            var sel = tp.getSelectionModel().getSelectedItem();
            if (sel != null) tp.getTabs().remove(sel);
        });
        am.register("commandPalette",   palette::show);
        am.register("tabSwitcher",      showTabSwitcher);
        am.register("toggleSidebar",    mainWindow::toggleSidebar);
        am.register("broadcastInput",   mainWindow::toggleBroadcast);
        am.register("findInTerminal",   mainWindow::openFind);
        am.register("fontSizeUp",       () -> mainWindow.adjustFontSize(+1));
        am.register("fontSizeDown",     () -> mainWindow.adjustFontSize(-1));
        am.register("fontSizeReset",    () -> mainWindow.adjustFontSize(0));
        am.register("splitRight",       () -> mainWindow.splitCurrentTab(false));
        am.register("splitDown",        () -> mainWindow.splitCurrentTab(true));
        am.rebindAll();

        // Save window geometry on close
        stage.setOnCloseRequest(e -> {
            prefs.putDouble("win.x", stage.getX());
            prefs.putDouble("win.y", stage.getY());
            prefs.putDouble("win.w", stage.getWidth());
            prefs.putDouble("win.h", stage.getHeight());
            ClipboardHistory.get().stop();
            pluginLoader.unloadAll();
        });

        log.info("NovaTerm started");
    }
}
