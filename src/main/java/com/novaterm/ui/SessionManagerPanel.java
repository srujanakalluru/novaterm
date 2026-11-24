package com.novaterm.ui;

import com.novaterm.session.Session;
import com.novaterm.session.SessionStore;
import com.novaterm.ssh.PingService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.*;
import java.util.function.Consumer;
import javafx.scene.layout.FlowPane;
import javafx.scene.control.TextInputDialog;

public class SessionManagerPanel {
    private final VBox root;
    private final SessionStore store;
    private final Consumer<Session> onOpen;
    private final Consumer<Session> onEdit;
    private final Consumer<Session> onDelete;
    private final TreeView<Object> treeView;
    private final TreeItem<Object> treeRoot;
    private String activeTagFilter = "";
    private FlowPane tagFilterRowRef;
    private TextField textFilterRef;

    public SessionManagerPanel(SessionStore store,
                                Consumer<Session> onOpen,
                                Consumer<Session> onEdit,
                                Consumer<Session> onDelete) {
        this.store = store;
        this.onOpen = onOpen;
        this.onEdit = onEdit;
        this.onDelete = onDelete;

        treeRoot = new TreeItem<>("root");
        treeRoot.setExpanded(true);

        treeView = new TreeView<>(treeRoot);
        treeView.setShowRoot(false);
        treeView.setId("session-list");
        treeView.setCellFactory(tv -> new SessionTreeCell(onOpen, onEdit, onDelete, store, this::refresh));
        treeView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<Object> sel = treeView.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() instanceof Session s) onOpen.accept(s);
            }
        });

        Button addBtn = new Button("+ New Session");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setId("add-session-btn");
        addBtn.setOnAction(e -> {
            SessionDialog dialog = new SessionDialog(null);
            dialog.showAndWait().ifPresent(s -> {
                store.add(s);
                refresh();
                onOpen.accept(s);
            });
        });

        Label title = new Label("SESSIONS");
        title.setId("panel-title");

        TextField filterField = new TextField();
        filterField.setPromptText("Filter sessions…");
        filterField.setId("session-filter");
        filterField.textProperty().addListener((obs, old, text) -> refresh());

        // Tag filter row — rebuilt on every refresh
        FlowPane tagFilterRow = new FlowPane(4, 4);
        tagFilterRow.setId("tag-filter-row");
        tagFilterRow.setPadding(new javafx.geometry.Insets(0, 4, 2, 4));

        // Notes preview at bottom — shows notes for selected session
        TextArea notesPreview = new TextArea();
        notesPreview.setId("session-notes-preview");
        notesPreview.setEditable(false);
        notesPreview.setWrapText(true);
        notesPreview.setPrefRowCount(3);
        notesPreview.setMaxHeight(72);
        notesPreview.setVisible(false);
        notesPreview.setManaged(false);
        notesPreview.setStyle("-fx-background-color:#181825; -fx-text-fill:#a6adc8; -fx-font-size:11px; " +
                              "-fx-border-color:#313244; -fx-border-width:1 0 0 0;");
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getValue() instanceof Session s
                    && s.getNotes() != null && !s.getNotes().isBlank()) {
                notesPreview.setText(s.getNotes());
                notesPreview.setVisible(true);
                notesPreview.setManaged(true);
            } else {
                notesPreview.setText("");
                notesPreview.setVisible(false);
                notesPreview.setManaged(false);
            }
        });

        root = new VBox(2, title, filterField, tagFilterRow, treeView, notesPreview, addBtn);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        root.setId("session-panel");

        // Store for use in refresh
        this.tagFilterRowRef = tagFilterRow;
        this.textFilterRef = filterField;

        refresh();
    }

    public void refresh() {
        String filter = textFilterRef != null ? textFilterRef.getText().trim().toLowerCase() : "";
        rebuildTagFilterRow();
        refreshFiltered(filter, activeTagFilter);
    }

    private void rebuildTagFilterRow() {
        if (tagFilterRowRef == null) return;
        tagFilterRowRef.getChildren().clear();

        // Collect all unique tags across all sessions
        Set<String> allTags = new java.util.TreeSet<>();
        for (Session s : store.getSessions()) {
            if (s.getTags() != null) allTags.addAll(s.getTags());
        }
        if (allTags.isEmpty()) return;

        for (String tag : allTags) {
            Label chip = new Label(tag);
            boolean active = tag.equals(activeTagFilter);
            chip.setStyle("-fx-background-color:" + (active ? "#89b4fa" : "#313244") + ";" +
                          "-fx-text-fill:" + (active ? "#1e1e2e" : "#cdd6f4") + ";" +
                          "-fx-padding:2 6 2 6; -fx-background-radius:9; -fx-font-size:10px; -fx-cursor:hand;");
            chip.setOnMouseClicked(e -> {
                activeTagFilter = active ? "" : tag;
                refresh();
            });
            tagFilterRowRef.getChildren().add(chip);
        }
    }

    private void refreshFiltered(String filter, String tagFilter) {
        // Remember which groups were expanded
        Set<String> expanded = new HashSet<>();
        for (TreeItem<Object> item : treeRoot.getChildren()) {
            if (item.isExpanded() && item.getValue() instanceof String g) expanded.add(g);
        }

        treeRoot.getChildren().clear();

        // Group sessions
        List<Session> favorites = new ArrayList<>();
        Map<String, List<Session>> groups = new LinkedHashMap<>();
        for (Session s : store.getSessions()) {
            String g = s.getGroup() == null || s.getGroup().isBlank() ? "Default" : s.getGroup();
            if (g.equals("__local__")) continue;
            if (!filter.isEmpty()) {
                boolean match = s.getName().toLowerCase().contains(filter)
                        || s.getHost().toLowerCase().contains(filter)
                        || (s.getUsername() != null && s.getUsername().toLowerCase().contains(filter))
                        || (s.getTags() != null && s.getTags().stream().anyMatch(t -> t.toLowerCase().contains(filter)));
                if (!match) continue;
            }
            if (!tagFilter.isEmpty()) {
                boolean hasTag = s.getTags() != null && s.getTags().contains(tagFilter);
                if (!hasTag) continue;
            }
            if (s.isFavorite()) favorites.add(s);
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(s);
        }

        // Favorites group first
        if (!favorites.isEmpty()) {
            TreeItem<Object> favItem = new TreeItem<>("★ Favorites");
            favItem.setExpanded(true);
            for (Session s : favorites) favItem.getChildren().add(new TreeItem<>(s));
            treeRoot.getChildren().add(favItem);
        }

        for (Map.Entry<String, List<Session>> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            TreeItem<Object> groupItem = new TreeItem<>(groupName);
            groupItem.setExpanded(!filter.isEmpty() || !tagFilter.isEmpty() || expanded.isEmpty() || expanded.contains(groupName));
            for (Session s : entry.getValue()) {
                groupItem.getChildren().add(new TreeItem<>(s));
            }
            treeRoot.getChildren().add(groupItem);
        }
    }

    public VBox getRoot() { return root; }

    private static class SessionTreeCell extends TreeCell<Object> {
        private final Consumer<Session> onOpen;
        private final Consumer<Session> onEdit;
        private final Consumer<Session> onDelete;
        private final SessionStore store;
        private final Runnable onRefresh;

        SessionTreeCell(Consumer<Session> onOpen, Consumer<Session> onEdit, Consumer<Session> onDelete,
                        SessionStore store, Runnable onRefresh) {
            this.onOpen = onOpen;
            this.onEdit = onEdit;
            this.onDelete = onDelete;
            this.store = store;
            this.onRefresh = onRefresh;
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            if (item instanceof String groupName) {
                // Group header row — double-click to rename group
                Label lbl = new Label(groupName.toUpperCase());
                lbl.setId("group-header-label");
                setGraphic(lbl);
                setText(null);
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !groupName.startsWith("★")) {
                        TextInputDialog dlg = new TextInputDialog(groupName);
                        dlg.setTitle("Rename Group");
                        dlg.setHeaderText("New name for group \"" + groupName + "\":");
                        dlg.showAndWait().ifPresent(newName -> {
                            if (!newName.isBlank() && !newName.equals(groupName)) {
                                for (Session s : store.getSessions()) {
                                    if (groupName.equals(s.getGroup())) {
                                        s.setGroup(newName.trim());
                                        store.update(s);
                                    }
                                }
                                onRefresh.run();
                            }
                        });
                    }
                });
                // Context menu: group-level actions
                ContextMenu gCtx = new ContextMenu();
                // Connect All in Group
                MenuItem connectAll = new MenuItem("Connect All in Group");
                connectAll.setOnAction(e -> {
                    List<Session> groupSessions = store.getSessions().stream()
                            .filter(s -> groupName.equals(s.getGroup()) ||
                                    (groupName.startsWith("★") && s.isFavorite()))
                            .toList();
                    groupSessions.forEach(onOpen::accept);
                });
                gCtx.getItems().add(connectAll);

                if (!groupName.startsWith("★")) {
                    MenuItem newInGroup = new MenuItem("New Session in Group…");
                    newInGroup.setOnAction(e -> {
                        com.novaterm.ui.SessionDialog dlg = new com.novaterm.ui.SessionDialog(null);
                        dlg.showAndWait().ifPresent(s -> {
                            s.setGroup(groupName);
                            store.add(s);
                            onRefresh.run();
                            onOpen.accept(s);
                        });
                    });
                    MenuItem renameGroup = new MenuItem("Rename Group…");
                    renameGroup.setOnAction(e -> {
                        TextInputDialog dlg = new TextInputDialog(groupName);
                        dlg.setTitle("Rename Group");
                        dlg.setHeaderText("New name for group \"" + groupName + "\":");
                        dlg.showAndWait().ifPresent(newName -> {
                            if (!newName.isBlank() && !newName.equals(groupName)) {
                                for (Session s : store.getSessions()) {
                                    if (groupName.equals(s.getGroup())) {
                                        s.setGroup(newName.trim());
                                        store.update(s);
                                    }
                                }
                                onRefresh.run();
                            }
                        });
                    });
                    gCtx.getItems().addAll(newInGroup, new SeparatorMenuItem(), renameGroup);
                }
                setContextMenu(gCtx);
                return;
            }

            Session session = (Session) item;
            Circle dot = new Circle(5);
            String color = session.getColor();
            dot.setFill(color != null ? Color.web(color) : Color.web("#89b4fa"));

            Label name = new Label(session.toString());
            Label host = new Label(session.getHost() + ":" + session.getPort());
            host.setId("session-cell-host");

            // Latency badge — trigger measurement on first display
            long cachedMs = PingService.get().getLatency(session);
            if (cachedMs == 0) PingService.get().measure(session); // fire async measurement
            Label pingLbl = new Label(PingService.badge(cachedMs));
            pingLbl.setStyle("-fx-font-size:9px; -fx-padding:1 4 1 4; -fx-background-radius:3; " +
                             "-fx-background-color:#313244; -fx-text-fill:" +
                             PingService.badgeColor(cachedMs) + ";");
            pingLbl.setVisible(cachedMs != 0);

            // Refresh ping label after a short delay if not yet measured
            if (cachedMs == 0) {
                Thread.ofVirtual().start(() -> {
                    try { Thread.sleep(3500); } catch (InterruptedException ignored) {}
                    long ms = PingService.get().getLatency(session);
                    Platform.runLater(() -> {
                        if (ms != 0) {
                            pingLbl.setText(PingService.badge(ms));
                            pingLbl.setStyle("-fx-font-size:9px; -fx-padding:1 4 1 4; -fx-background-radius:3; " +
                                            "-fx-background-color:#313244; -fx-text-fill:" +
                                            PingService.badgeColor(ms) + ";");
                            pingLbl.setVisible(true);
                        }
                    });
                });
            }

            // Sparkline popup on hover over ping badge
            LatencySparklinePopup[] sparkline = { null };
            pingLbl.setOnMouseEntered(e -> {
                if (sparkline[0] == null) sparkline[0] = new LatencySparklinePopup();
                sparkline[0].showFor(session, pingLbl, e.getScreenX(), e.getScreenY());
            });
            pingLbl.setOnMouseExited(e -> {
                if (sparkline[0] != null) sparkline[0].hide();
            });

            // Tag chips (inline, small)
            HBox tagChips = new HBox(3);
            if (session.getTags() != null) {
                for (String tag : session.getTags()) {
                    Label tc = new Label(tag);
                    tc.setStyle("-fx-background-color:#313244; -fx-text-fill:#89b4fa;" +
                                "-fx-padding:0 4 0 4; -fx-background-radius:5; -fx-font-size:9px;");
                    tagChips.getChildren().add(tc);
                }
            }

            VBox text = new VBox(1, name, host, tagChips);
            tagChips.setVisible(!tagChips.getChildren().isEmpty());
            tagChips.setManaged(!tagChips.getChildren().isEmpty());
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(8, dot, text, pingLbl);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
            if (session.getNotes() != null && !session.getNotes().isBlank()) {
                setTooltip(new Tooltip(session.getNotes()));
            } else {
                setTooltip(null);
            }

            ContextMenu ctx = new ContextMenu();
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> onOpen.accept(session));
            MenuItem edit = new MenuItem("Edit...");
            edit.setOnAction(e -> onEdit.accept(session));
            MenuItem fav = new MenuItem(session.isFavorite() ? "★ Remove from Favorites" : "☆ Add to Favorites");
            fav.setOnAction(e -> {
                session.setFavorite(!session.isFavorite());
                store.update(session);
                onRefresh.run();
            });

            // Move to group submenu
            Menu moveMenu = new Menu("Move to Group");
            Set<String> allGroups = new LinkedHashSet<>();
            for (Session s : store.getSessions()) {
                String g = s.getGroup();
                if (g != null && !g.isBlank() && !g.startsWith("__")) allGroups.add(g);
            }
            allGroups.remove(session.getGroup());
            for (String g : allGroups) {
                MenuItem mi = new MenuItem(g);
                mi.setOnAction(e -> { session.setGroup(g); store.update(session); onRefresh.run(); });
                moveMenu.getItems().add(mi);
            }
            MenuItem newGroupItem = new MenuItem("New Group…");
            newGroupItem.setOnAction(e -> {
                TextInputDialog dlg = new TextInputDialog("");
                dlg.setTitle("Move to New Group");
                dlg.setHeaderText("Enter new group name:");
                dlg.showAndWait().ifPresent(g -> {
                    if (!g.isBlank()) { session.setGroup(g.trim()); store.update(session); onRefresh.run(); }
                });
            });
            moveMenu.getItems().add(new SeparatorMenuItem());
            moveMenu.getItems().add(newGroupItem);

            MenuItem editTags = new MenuItem("Edit Tags…");
            editTags.setOnAction(e -> {
                String current = session.getTags() == null ? "" : String.join(", ", session.getTags());
                TextInputDialog dlg = new TextInputDialog(current);
                dlg.setTitle("Edit Tags");
                dlg.setHeaderText("Tags for \"" + session.getName() + "\" (comma-separated):");
                dlg.showAndWait().ifPresent(val -> {
                    List<String> newTags = Arrays.stream(val.split(","))
                            .map(String::trim).filter(t -> !t.isEmpty()).toList();
                    session.setTags(new ArrayList<>(newTags));
                    store.update(session);
                    onRefresh.run();
                });
            });

            MenuItem delete = new MenuItem("Delete");
            delete.setStyle("-fx-text-fill: #f38ba8;");
            delete.setOnAction(e -> onDelete.accept(session));
            ctx.getItems().addAll(open, edit, fav, editTags, moveMenu, new SeparatorMenuItem(), delete);
            setContextMenu(ctx);
        }
    }
}
