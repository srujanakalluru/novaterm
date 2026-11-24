package com.novaterm.plugin;

import com.novaterm.session.Session;
import javafx.scene.layout.Pane;
import java.util.function.Supplier;

/**
 * Services plugins can call back into the host application.
 */
public interface PluginContext {
    /** Register a sidebar panel with a label. */
    void registerSidePanel(String label, Supplier<Pane> factory);

    /** Register a menu item under Plugins menu. */
    void registerMenuAction(String label, Runnable action);

    /** Open a new SSH terminal tab for the given session. */
    void openTerminalTab(Session session);

    /** Show a small notification toast. */
    void showNotification(String title, String message);
}
