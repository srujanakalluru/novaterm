package com.novaterm.ui;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseEvent;

/**
 * Enables drag-to-reorder tabs in a TabPane.
 * Call TabReorder.install(tabPane) once after the TabPane is shown.
 */
public class TabReorder {
    private static final String DATA_KEY = "tab-reorder-source-idx";

    public static void install(TabPane tabPane) {
        // We use the tab header area nodes via CSS lookup after scene is attached
        tabPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.addEventFilter(MouseEvent.ANY, e -> {
                Node target = (Node) e.getTarget();
                Tab tab = findTabHeader(target, tabPane);
                if (tab == null) return;

                if (e.getEventType() == MouseEvent.MOUSE_PRESSED) {
                    int idx = tabPane.getTabs().indexOf(tab);
                    tabPane.setUserData(idx);
                } else if (e.getEventType() == MouseEvent.MOUSE_RELEASED) {
                    Object src = tabPane.getUserData();
                    if (!(src instanceof Integer srcIdx)) return;
                    tabPane.setUserData(null);

                    // Find drop position from mouse X within the tab header area
                    Node headerArea = tabPane.lookup(".headers-region");
                    if (headerArea == null) return;
                    double mouseX = e.getSceneX();
                    int destIdx = findDropIndex(tabPane, headerArea, mouseX);
                    if (destIdx < 0 || destIdx == srcIdx) return;

                    ObservableList<Tab> tabs = tabPane.getTabs();
                    Tab moving = tabs.remove((int) srcIdx);
                    tabs.add(Math.min(destIdx, tabs.size()), moving);
                    tabPane.getSelectionModel().select(moving);
                }
            });
        });
    }

    private static Tab findTabHeader(Node node, TabPane tabPane) {
        Node n = node;
        while (n != null) {
            String styleClass = n.getStyleClass().stream()
                    .reduce("", (a, b) -> a + " " + b);
            if (styleClass.contains("tab ") || styleClass.contains("tab-label")) {
                // Find which tab this header belongs to
                for (Tab t : tabPane.getTabs()) {
                    // Compare by label node lookup (best effort)
                    if (n.toString().contains(t.getText())) return t;
                }
                return tabPane.getSelectionModel().getSelectedItem();
            }
            n = n.getParent();
        }
        return null;
    }

    private static int findDropIndex(TabPane tabPane, Node headerArea, double sceneX) {
        double localX = headerArea.sceneToLocal(sceneX, 0).getX();
        double totalWidth = 0;
        int count = tabPane.getTabs().size();
        double tabWidth = headerArea.getBoundsInLocal().getWidth() / Math.max(1, count);
        int idx = (int) (localX / tabWidth);
        return Math.max(0, Math.min(count, idx));
    }
}
