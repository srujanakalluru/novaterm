package com.novaterm.ssh;

import javafx.application.Platform;
import javafx.scene.control.*;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.file.*;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trust-on-first-use verifier. Stores accepted fingerprints in
 * ~/Library/Application Support/NovaTerm/known_hosts.properties.
 */
public class KnownHostsVerifier implements ServerKeyVerifier {
    private static final Logger log = LoggerFactory.getLogger(KnownHostsVerifier.class);
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), "Library", "Application Support",
            "NovaTerm", "known_hosts.properties");

    private final Properties store = new Properties();

    public KnownHostsVerifier() {
        if (Files.exists(STORE_PATH)) {
            try (var in = Files.newInputStream(STORE_PATH)) {
                store.load(in);
            } catch (Exception e) {
                log.warn("Failed to load known hosts", e);
            }
        }
    }

    @Override
    public boolean verifyServerKey(ClientSession cs, SocketAddress remoteAddress, PublicKey serverKey) {
        String host = remoteAddress.toString();
        String fp = fingerprint(serverKey);
        String stored = store.getProperty(host);

        if (stored == null) {
            // First connection — ask user
            boolean trusted = askUser(host, fp, false);
            if (trusted) {
                store.setProperty(host, fp);
                persist();
            }
            return trusted;
        }
        if (stored.equals(fp)) return true;

        // Key changed — MITM warning
        return askUser(host, fp, true);
    }

    private boolean askUser(String host, String fingerprint, boolean changed) {
        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            Alert alert = changed
                    ? new Alert(Alert.AlertType.WARNING)
                    : new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(changed ? "Host Key Changed!" : "Unknown Host");
            alert.setHeaderText(changed
                    ? "WARNING: Remote host identification has changed for:\n" + host
                    : "The authenticity of host '" + host + "' cannot be established.");
            alert.setContentText("Server key fingerprint:\n" + fingerprint
                    + (changed ? "\n\nThis may indicate a man-in-the-middle attack." : "\n\nDo you want to connect?"));
            alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            result.set(alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES);
            latch.countDown();
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result.get();
    }

    private String fingerprint(PublicKey key) {
        try {
            byte[] encoded = key.getEncoded();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Returns all known hosts as an unmodifiable host→fingerprint map. */
    public Map<String, String> getEntries() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String key : store.stringPropertyNames()) map.put(key, store.getProperty(key));
        return Collections.unmodifiableMap(map);
    }

    /** Remove a host from the trust store. */
    public void remove(String host) {
        store.remove(host);
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (var out = Files.newOutputStream(STORE_PATH)) {
                store.store(out, "NovaTerm known hosts");
            }
        } catch (Exception e) {
            log.error("Failed to save known hosts", e);
        }
    }
}
