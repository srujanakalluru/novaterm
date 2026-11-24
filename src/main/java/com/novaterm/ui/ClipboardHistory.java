package com.novaterm.ui;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the system clipboard and keeps a ring buffer of recent text entries.
 * Poll-based because JavaFX doesn't expose clipboard change events.
 */
public class ClipboardHistory {

    private static final int MAX_ENTRIES = 50;
    private static final ClipboardHistory INSTANCE = new ClipboardHistory();

    private final List<String> entries = new ArrayList<>();
    private String lastSeen = null;
    private final ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setDaemon(true);
                return t;
            });

    private ClipboardHistory() {}

    public static ClipboardHistory get() { return INSTANCE; }

    public void start() {
        poller.scheduleAtFixedRate(this::poll, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        poller.shutdown();
    }

    private void poll() {
        // Clipboard access must run on FX thread
        Platform.runLater(() -> {
            try {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasString()) {
                    String text = cb.getString();
                    if (text != null && !text.isBlank() && !text.equals(lastSeen)) {
                        lastSeen = text;
                        synchronized (entries) {
                            entries.remove(text);           // de-dup
                            entries.add(0, text);           // most recent first
                            if (entries.size() > MAX_ENTRIES) {
                                entries.subList(MAX_ENTRIES, entries.size()).clear();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    /** Snapshot of history, most recent first. */
    public List<String> getEntries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /** Copy text to clipboard and add to history. */
    public void copyToClipboard(String text) {
        Platform.runLater(() -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
            lastSeen = text;
            synchronized (entries) {
                entries.remove(text);
                entries.add(0, text);
                if (entries.size() > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size()).clear();
            }
        });
    }
}
