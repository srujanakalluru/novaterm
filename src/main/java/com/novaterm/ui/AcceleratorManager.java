package com.novaterm.ui;

import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages live rebinding of scene-level keyboard accelerators.
 * Actions register once; rebindAll() re-applies with current KeybindingStore values.
 */
public class AcceleratorManager {

    private static final AcceleratorManager INSTANCE = new AcceleratorManager();
    public static AcceleratorManager get() { return INSTANCE; }

    private Scene scene;
    private final Map<String, Runnable> actions = new LinkedHashMap<>();

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    /** Register an action Runnable for the given action ID. */
    public void register(String actionId, Runnable action) {
        actions.put(actionId, action);
    }

    /**
     * Remove all previously bound accelerators for the managed action IDs, then
     * re-add them from the current KeybindingStore values. Call after saving prefs.
     */
    public void rebindAll() {
        if (scene == null) return;

        // Remove existing bindings for all managed action IDs
        for (String actionId : KeybindingStore.DEFAULTS.keySet()) {
            // Remove any old combo that was bound to this action
            String oldCombo = KeybindingStore.get().get(actionId);
            if (oldCombo != null && !oldCombo.isBlank()) {
                try {
                    KeyCombination kc = KeyCombination.keyCombination(oldCombo);
                    scene.getAccelerators().remove(kc);
                } catch (Exception ignored) {}
            }
            // Also remove the default combo in case the user saved the default
            String defCombo = KeybindingStore.DEFAULTS.get(actionId);
            if (defCombo != null && !defCombo.isBlank()) {
                try {
                    scene.getAccelerators().remove(KeyCombination.keyCombination(defCombo));
                } catch (Exception ignored) {}
            }
        }

        // Re-add all actions with current (possibly updated) combos
        for (Map.Entry<String, Runnable> entry : actions.entrySet()) {
            String actionId = entry.getKey();
            Runnable action = entry.getValue();
            KeyCombination kc = KeybindingStore.get().parseOrNull(actionId);
            if (kc != null) {
                scene.getAccelerators().put(kc, action);
            }
        }
    }
}
