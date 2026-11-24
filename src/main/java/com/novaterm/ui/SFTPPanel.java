package com.novaterm.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.impl.DefaultSftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class SFTPPanel {
    private static final Logger log = LoggerFactory.getLogger(SFTPPanel.class);

    private final VBox root;
    private final TableView<FileEntry> tableView;
    private final Label currentPathLabel;
    private final ClientSession session;
    private SftpClient sftpClient;
    private String currentPath = "/";
    private javafx.scene.control.ProgressBar progressBar;
    private javafx.scene.control.Label progressLabel;
    private javafx.scene.layout.HBox progressRow;
    private final javafx.scene.control.ListView<String> bookmarkList = new javafx.scene.control.ListView<>();
    private Button bookmarkBtn;

    public record FileEntry(String name, String size, String permissions, boolean isDir, long bytes) {}

    public SFTPPanel(ClientSession session) {
        this.session = session;
        currentPathLabel = new Label("/");
        currentPathLabel.setId("sftp-path");

        // Path navigation field
        TextField pathField = new TextField("/");
        pathField.setId("sftp-path-field");
        pathField.setOnAction(e -> navigate(pathField.getText().trim()));

        tableView = new TableView<>();
        tableView.setId("sftp-table");
        tableView.setPlaceholder(new Label("Loading…"));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<FileEntry, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(
                (d.getValue().isDir() ? "📁 " : "📄 ") + d.getValue().name()));
        nameCol.setPrefWidth(200);
        TableColumn<FileEntry, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().size()));
        sizeCol.setPrefWidth(80);
        TableColumn<FileEntry, String> permCol = new TableColumn<>("Permissions");
        permCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().permissions()));
        permCol.setPrefWidth(100);
        tableView.getColumns().addAll(nameCol, sizeCol, permCol);

        // Double-click to navigate directories; right-click for context menu
        tableView.setRowFactory(tv -> {
            TableRow<FileEntry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    FileEntry entry = row.getItem();
                    if (entry.isDir() && !entry.name().equals("..")) {
                        String newPath = currentPath.equals("/")
                                ? "/" + entry.name()
                                : currentPath + "/" + entry.name();
                        navigate(normalizePath(newPath));
                    } else if (entry.isDir()) {
                        navigate(parent(currentPath));
                    }
                } else if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY && !row.isEmpty()) {
                    tableView.getSelectionModel().select(row.getIndex());
                    buildContextMenu(row.getItem()).show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });

        // Drag-and-drop: local files dropped onto the table → upload
        tableView.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        tableView.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                e.setDropCompleted(true);
                List<java.io.File> files = db.getFiles();
                uploadFiles(files);
            }
            e.consume();
        });

        // Toolbar buttons
        Button homeBtn = new Button("⌂");
        homeBtn.setTooltip(new Tooltip("Home"));
        homeBtn.setOnAction(e -> navigate("/home/" + session.getUsername()));
        Button upBtn = new Button("↑");
        upBtn.setTooltip(new Tooltip("Parent directory"));
        upBtn.setOnAction(e -> navigate(parent(currentPath)));
        Button refreshBtn = new Button("⟳");
        refreshBtn.setTooltip(new Tooltip("Refresh"));
        refreshBtn.setOnAction(e -> navigate(currentPath));
        Button uploadBtn = new Button("⬆ Upload");
        uploadBtn.setTooltip(new Tooltip("Choose files to upload"));
        uploadBtn.setOnAction(e -> chooseAndUpload());
        Button downloadBtn = new Button("⬇ Download");
        downloadBtn.setTooltip(new Tooltip("Download selected file"));
        downloadBtn.setOnAction(e -> downloadSelected());
        Button deleteBtn = new Button("🗑");
        deleteBtn.setTooltip(new Tooltip("Delete selected"));
        deleteBtn.setStyle("-fx-text-fill:#f38ba8;");
        deleteBtn.setOnAction(e -> deleteSelected());
        Button mkdirBtn = new Button("+ Dir");
        mkdirBtn.setTooltip(new Tooltip("Create new directory"));
        mkdirBtn.setOnAction(e -> createDirectory());

        bookmarkBtn = new Button("☆");
        bookmarkBtn.setTooltip(new Tooltip("Bookmark this directory"));
        bookmarkBtn.setOnAction(e -> toggleBookmark());

        HBox.setHgrow(pathField, Priority.ALWAYS);
        HBox toolbar = new HBox(4, homeBtn, upBtn, refreshBtn, pathField,
                bookmarkBtn, uploadBtn, downloadBtn, mkdirBtn, deleteBtn);
        toolbar.setId("sftp-toolbar");

        // Drop zone hint label
        Label dropHint = new Label("Drop files here to upload");
        dropHint.setId("sftp-drop-hint");
        dropHint.setMouseTransparent(true);

        StackPane tableStack = new StackPane(tableView);
        // Show drop hint when dragging over
        tableView.setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles()) {
                tableStack.setStyle("-fx-border-color:#89b4fa; -fx-border-width:2;");
            }
        });
        tableView.setOnDragExited(e -> tableStack.setStyle(""));

        progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressLabel = new javafx.scene.control.Label("Transferring…");
        progressLabel.setStyle("-fx-text-fill:#89b4fa; -fx-font-size:11px;");
        progressRow = new javafx.scene.layout.HBox(8, progressLabel, progressBar);
        progressRow.setStyle("-fx-background-color:#eaeef2; -fx-padding:4 8 4 8;");
        progressRow.setVisible(false);
        progressRow.setManaged(false);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        // Bookmarks sidebar
        bookmarkList.setId("sftp-bookmarks");
        bookmarkList.setPrefWidth(160);
        bookmarkList.setPlaceholder(new Label("No bookmarks"));
        bookmarkList.setStyle("-fx-background-color:#eaeef2; -fx-font-size:11px;");
        refreshBookmarks();
        bookmarkList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = bookmarkList.getSelectionModel().getSelectedItem();
                if (sel != null) navigate(sel);
            }
        });
        bookmarkList.setContextMenu(buildBookmarkContextMenu());

        Label bmTitle = new Label("BOOKMARKS");
        bmTitle.setStyle("-fx-text-fill:#848d97; -fx-font-size:10px; -fx-padding:4 6 2 6;");
        VBox bmPane = new VBox(bmTitle, bookmarkList);
        VBox.setVgrow(bookmarkList, Priority.ALWAYS);

        javafx.scene.control.SplitPane mainSplit = new javafx.scene.control.SplitPane(bmPane, tableStack);
        mainSplit.setDividerPositions(0.22);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        root = new VBox(toolbar, mainSplit, progressRow);
        root.setId("sftp-panel");

        // Sync path field with navigation
        currentPathLabel.textProperty().addListener((obs, old, val) -> {
            pathField.setText(val);
            updateBookmarkBtn(val);
        });

        Thread.ofVirtual().start(() -> {
            try {
                sftpClient = DefaultSftpClientFactory.INSTANCE.createSftpClient(session);
                navigate("/");
            } catch (IOException e) {
                log.error("SFTP init failed", e);
                Platform.runLater(() -> tableView.setPlaceholder(
                        new Label("SFTP connection failed: " + e.getMessage())));
            }
        });
    }

    private void navigate(String path) {
        currentPath = path;
        Platform.runLater(() -> currentPathLabel.setText(path));
        Thread.ofVirtual().start(() -> {
            try {
                List<FileEntry> entries = new ArrayList<>();
                if (!path.equals("/")) entries.add(new FileEntry("..", "", "", true, 0));
                for (SftpClient.DirEntry entry : sftpClient.readDir(path)) {
                    if (".".equals(entry.getFilename())) continue;
                    SftpClient.Attributes attrs = entry.getAttributes();
                    boolean isDir = attrs.isDirectory();
                    long bytes = isDir ? 0 : attrs.getSize();
                    entries.add(new FileEntry(
                            entry.getFilename(), isDir ? "" : humanSize(bytes),
                            permsString(attrs), isDir, bytes));
                }
                entries.sort((a, b) -> {
                    if (a.isDir() && !b.isDir()) return -1;
                    if (!a.isDir() && b.isDir()) return 1;
                    return a.name().compareTo(b.name());
                });
                Platform.runLater(() -> tableView.getItems().setAll(entries));
            } catch (IOException e) {
                log.error("SFTP read failed: {}", path, e);
                Platform.runLater(() -> tableView.setPlaceholder(
                        new Label("Error: " + e.getMessage())));
            }
        });
    }

    private void showProgress(String msg, double value) {
        Platform.runLater(() -> {
            progressLabel.setText(msg);
            progressBar.setProgress(value < 0 ? ProgressBar.INDETERMINATE_PROGRESS : value);
            progressRow.setVisible(true);
            progressRow.setManaged(true);
        });
    }

    private void hideProgress() {
        Platform.runLater(() -> { progressRow.setVisible(false); progressRow.setManaged(false); });
    }

    private void uploadFiles(List<java.io.File> files) {
        Thread.ofVirtual().start(() -> {
            for (java.io.File f : files) {
                long total = f.length();
                String remotePath = currentPath.equals("/")
                        ? "/" + f.getName()
                        : currentPath + "/" + f.getName();
                showProgress("Uploading " + f.getName() + "…", total > 0 ? 0 : -1);
                try (InputStream rawIn = new FileInputStream(f);
                     OutputStream out = sftpClient.write(remotePath,
                             SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                             SftpClient.OpenMode.Truncate)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n;
                    while ((n = rawIn.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final double pct = (double) done / total;
                            Platform.runLater(() -> progressBar.setProgress(pct));
                        }
                    }
                    log.info("Uploaded: {} → {}", f.getName(), remotePath);
                } catch (IOException e) {
                    log.error("Upload failed: {}", f.getName(), e);
                    final String name = f.getName();
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "Upload failed: " + name + "\n" + e.getMessage()).show());
                }
            }
            hideProgress();
            navigate(currentPath);
        });
    }

    private void chooseAndUpload() {
        Platform.runLater(() -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Upload Files");
            List<java.io.File> files = chooser.showOpenMultipleDialog(root.getScene().getWindow());
            if (files != null) uploadFiles(files);
        });
    }

    private void downloadSelected() {
        List<FileEntry> selected = tableView.getSelectionModel().getSelectedItems().stream()
                .filter(e -> !e.isDir()).toList();
        if (selected.isEmpty()) return;

        Platform.runLater(() -> {
            if (selected.size() == 1) {
                // Single file: show Save As dialog
                FileEntry sel = selected.get(0);
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
                chooser.setTitle("Save As");
                chooser.setInitialFileName(sel.name());
                java.io.File dest = chooser.showSaveDialog(root.getScene().getWindow());
                if (dest == null) return;
                downloadFileTo(sel, dest);
            } else {
                // Multiple files: pick a directory
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle("Download " + selected.size() + " files to…");
                java.io.File dir = chooser.showDialog(root.getScene().getWindow());
                if (dir == null) return;
                for (FileEntry sel : selected) {
                    downloadFileTo(sel, new java.io.File(dir, sel.name()));
                }
            }
        });
    }

    private void downloadFileTo(FileEntry sel, java.io.File dest) {
        String remotePath = currentPath.equals("/")
                ? "/" + sel.name() : currentPath + "/" + sel.name();
        long remoteSize = sel.bytes();
        showProgress("Downloading " + sel.name() + "…", remoteSize > 0 ? 0 : -1);
        Thread.ofVirtual().start(() -> {
            try (InputStream in = sftpClient.read(remotePath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (remoteSize > 0) {
                        final double pct = (double) done / remoteSize;
                        Platform.runLater(() -> progressBar.setProgress(pct));
                    }
                }
                log.info("Downloaded: {} → {}", remotePath, dest);
            } catch (IOException e) {
                log.error("Download failed", e);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Download failed: " + e.getMessage()).show());
            } finally {
                hideProgress();
            }
        });
    }

    private void deleteSelected() {
        FileEntry sel = tableView.getSelectionModel().getSelectedItem();
        if (sel == null || "..".equals(sel.name())) return;
        Platform.runLater(() -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete \"" + sel.name() + "\" on the server?");
            confirm.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> {
                String remotePath = currentPath.equals("/")
                        ? "/" + sel.name() : currentPath + "/" + sel.name();
                Thread.ofVirtual().start(() -> {
                    try {
                        if (sel.isDir()) sftpClient.rmdir(remotePath);
                        else sftpClient.remove(remotePath);
                        navigate(currentPath);
                    } catch (IOException e) {
                        log.error("Delete failed", e);
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "Delete failed: " + e.getMessage()).show());
                    }
                });
            });
        });
    }

    private void createDirectory() {
        Platform.runLater(() -> {
            TextInputDialog dlg = new TextInputDialog("new-folder");
            dlg.setTitle("Create Directory");
            dlg.setHeaderText("New directory name:");
            dlg.showAndWait().ifPresent(name -> {
                if (name.isBlank()) return;
                String remotePath = currentPath.equals("/")
                        ? "/" + name.trim() : currentPath + "/" + name.trim();
                Thread.ofVirtual().start(() -> {
                    try {
                        sftpClient.mkdir(remotePath);
                        navigate(currentPath);
                    } catch (IOException e) {
                        log.error("mkdir failed", e);
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "mkdir failed: " + e.getMessage()).show());
                    }
                });
            });
        });
    }

    private javafx.scene.control.ContextMenu buildContextMenu(FileEntry entry) {
        javafx.scene.control.MenuItem download = new javafx.scene.control.MenuItem("⬇ Download");
        download.setOnAction(e -> downloadSelected());
        download.setDisable(entry.isDir());

        javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem("✏ Rename…");
        rename.setOnAction(e -> renameSelected(entry));
        rename.setDisable("..".equals(entry.name()));

        javafx.scene.control.MenuItem delete = new javafx.scene.control.MenuItem("🗑 Delete");
        delete.setStyle("-fx-text-fill:#f38ba8;");
        delete.setOnAction(e -> deleteSelected());
        delete.setDisable("..".equals(entry.name()));

        javafx.scene.control.MenuItem copyPath = new javafx.scene.control.MenuItem("📋 Copy Path");
        copyPath.setOnAction(e -> {
            String remotePath = currentPath.equals("/")
                    ? "/" + entry.name() : currentPath + "/" + entry.name();
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(remotePath);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });

        return new javafx.scene.control.ContextMenu(download, rename, copyPath,
                new javafx.scene.control.SeparatorMenuItem(), delete);
    }

    private void renameSelected(FileEntry entry) {
        Platform.runLater(() -> {
            TextInputDialog dlg = new TextInputDialog(entry.name());
            dlg.setTitle("Rename");
            dlg.setHeaderText("New name for \"" + entry.name() + "\":");
            dlg.showAndWait().ifPresent(newName -> {
                if (newName.isBlank() || newName.equals(entry.name())) return;
                String oldPath = currentPath.equals("/")
                        ? "/" + entry.name() : currentPath + "/" + entry.name();
                String newPath = currentPath.equals("/")
                        ? "/" + newName.trim() : currentPath + "/" + newName.trim();
                Thread.ofVirtual().start(() -> {
                    try {
                        sftpClient.rename(oldPath, newPath);
                        navigate(currentPath);
                    } catch (IOException e) {
                        log.error("Rename failed", e);
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                "Rename failed: " + e.getMessage()).show());
                    }
                });
            });
        });
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String permsString(SftpClient.Attributes attrs) {
        int perms = attrs.getPermissions();
        char[] p = "rwxrwxrwx".toCharArray();
        for (int i = 0; i < 9; i++) {
            if ((perms & (1 << (8 - i))) == 0) p[i] = '-';
        }
        return new String(p);
    }

    private String parent(String path) {
        if (path.equals("/")) return "/";
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    private String normalizePath(String path) {
        return path.replaceAll("/+", "/");
    }

    private void toggleBookmark() {
        SFTPBookmarks bm = SFTPBookmarks.get();
        if (bm.contains(currentPath)) {
            bm.remove(currentPath);
        } else {
            bm.add(currentPath);
        }
        refreshBookmarks();
        updateBookmarkBtn(currentPath);
    }

    private void refreshBookmarks() {
        bookmarkList.getItems().setAll(SFTPBookmarks.get().getAll());
    }

    private void updateBookmarkBtn(String path) {
        if (bookmarkBtn == null) return;
        boolean starred = SFTPBookmarks.get().contains(path);
        bookmarkBtn.setText(starred ? "★" : "☆");
        bookmarkBtn.setStyle(starred ? "-fx-text-fill:#f9e2af;" : "");
        bookmarkBtn.setTooltip(new Tooltip(starred ? "Remove bookmark" : "Bookmark this directory"));
    }

    private javafx.scene.control.ContextMenu buildBookmarkContextMenu() {
        javafx.scene.control.ContextMenu ctx = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem openItem = new javafx.scene.control.MenuItem("Navigate Here");
        openItem.setOnAction(e -> {
            String sel = bookmarkList.getSelectionModel().getSelectedItem();
            if (sel != null) navigate(sel);
        });
        javafx.scene.control.MenuItem removeItem = new javafx.scene.control.MenuItem("Remove Bookmark");
        removeItem.setStyle("-fx-text-fill:#f38ba8;");
        removeItem.setOnAction(e -> {
            String sel = bookmarkList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                SFTPBookmarks.get().remove(sel);
                refreshBookmarks();
                updateBookmarkBtn(currentPath);
            }
        });
        ctx.getItems().addAll(openItem, removeItem);
        return ctx;
    }

    public VBox getRoot() { return root; }
}
