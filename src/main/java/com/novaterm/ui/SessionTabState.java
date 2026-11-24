package com.novaterm.ui;

import com.novaterm.session.Session;
import com.novaterm.ssh.TunnelManager;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.apache.sshd.client.session.ClientSession;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable state for a terminal tab once its SSH connection is live.
 * MainWindow holds a map from Tab → SessionTabState for SFTP / tunnel access.
 */
public class SessionTabState {
    private final Session session;
    private final ObjectProperty<ClientSession> clientSession = new SimpleObjectProperty<>();
    private final TunnelManager tunnelManager = new TunnelManager();

    // Connection statistics
    private volatile Instant connectedAt;
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private volatile int reconnectCount;

    // Current working directory reported via OSC 7
    private volatile String currentDirectory;

    public SessionTabState(Session session) {
        this.session = session;
    }

    public Session getSession() { return session; }
    public ClientSession getClientSession() { return clientSession.get(); }
    public void setClientSession(ClientSession cs) {
        clientSession.set(cs);
        if (cs != null) {
            connectedAt = Instant.now();
            reconnectCount++;
        }
    }
    public ObjectProperty<ClientSession> clientSessionProperty() { return clientSession; }
    public TunnelManager getTunnelManager() { return tunnelManager; }

    public boolean isConnected() { return clientSession.get() != null && clientSession.get().isOpen(); }

    public void addBytesReceived(long n) { bytesReceived.addAndGet(n); }
    public void addBytesSent(long n) { bytesSent.addAndGet(n); }

    public Instant getConnectedAt() { return connectedAt; }
    public long getBytesReceived() { return bytesReceived.get(); }
    public long getBytesSent() { return bytesSent.get(); }
    public int getReconnectCount() { return reconnectCount; }

    /** Human-readable bytes, e.g. "1.4 MB". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public String getCurrentDirectory() { return currentDirectory; }
    public void setCurrentDirectory(String dir) { currentDirectory = dir; }

    /** Connected duration as "1h 23m 45s" or "12m 34s" or "56s". */
    public String connectedDuration() {
        if (connectedAt == null) return "—";
        long secs = java.time.Duration.between(connectedAt, Instant.now()).getSeconds();
        if (secs < 0) secs = 0;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return secs + "s";
    }
}
