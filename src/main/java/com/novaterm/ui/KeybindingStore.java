package com.novaterm.ui;

import javafx.scene.input.KeyCombination;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Persistent user-configurable keyboard shortcuts.
 * Keys are action IDs; values are KeyCombination strings like "Meta+N".
 */
public class KeybindingStore {

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/novaterm/keybindings");
    private static final KeybindingStore INSTANCE = new KeybindingStore();

    public static KeybindingStore get() { return INSTANCE; }

    // Action ID → default accelerator
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("newSession",        "Meta+N");
        DEFAULTS.put("newLocalTerminal",  "Meta+T");
        DEFAULTS.put("closeTab",          "Meta+W");
        DEFAULTS.put("commandPalette",    "Meta+Shift+P");
        DEFAULTS.put("tabSwitcher",       "Meta+Shift+E");
        DEFAULTS.put("clipboardHistory",  "Meta+Shift+V");
        DEFAULTS.put("toggleSidebar",     "Meta+Shift+S");
        DEFAULTS.put("broadcastInput",    "Meta+Shift+B");
        DEFAULTS.put("portForwarding",    "Meta+Shift+T");
        DEFAULTS.put("findInTerminal",    "Meta+F");
        DEFAULTS.put("fontSizeUp",        "Meta+EQUALS");
        DEFAULTS.put("fontSizeDown",      "Meta+MINUS");
        DEFAULTS.put("fontSizeReset",     "Meta+DIGIT0");
        DEFAULTS.put("splitRight",        "Meta+D");
        DEFAULTS.put("splitDown",         "Meta+Shift+D");
    }

    // Action ID → human-readable label
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("newSession",        "New Session…");
        LABELS.put("newLocalTerminal",  "New Local Terminal");
        LABELS.put("closeTab",          "Close Tab");
        LABELS.put("commandPalette",    "Command Palette");
        LABELS.put("tabSwitcher",       "Tab Switcher");
        LABELS.put("clipboardHistory",  "Clipboard History");
        LABELS.put("toggleSidebar",     "Toggle Sidebar");
        LABELS.put("broadcastInput",    "Broadcast Input");
        LABELS.put("portForwarding",    "Port Forwarding");
        LABELS.put("findInTerminal",    "Find in Terminal");
        LABELS.put("fontSizeUp",        "Increase Font Size");
        LABELS.put("fontSizeDown",      "Decrease Font Size");
        LABELS.put("fontSizeReset",     "Reset Font Size");
        LABELS.put("splitRight",        "Split Right");
        LABELS.put("splitDown",         "Split Down");
    }

    public String get(String actionId) {
        return PREFS.get(actionId, DEFAULTS.getOrDefault(actionId, ""));
    }

    public void set(String actionId, String keyCombination) {
        PREFS.put(actionId, keyCombination);
    }

    public void reset(String actionId) {
        PREFS.remove(actionId);
    }

    public void resetAll() {
        try { PREFS.clear(); } catch (Exception ignored) {}
    }

    /** Parse to KeyCombination, or null on failure. */
    public KeyCombination parseOrNull(String actionId) {
        String v = get(actionId);
        if (v == null || v.isBlank()) return null;
        try {
            return KeyCombination.keyCombination(v);
        } catch (Exception e) {
            return null;
        }
    }
}
