package com.novaterm.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stores and retrieves SSH passwords/passphrases from the macOS Keychain
 * via the `security` CLI tool (available on all macOS versions).
 *
 * Passwords are stored under service "NovaTerm" with account = "user@host:port".
 */
public class KeychainCredentialStore {
    private static final Logger log = LoggerFactory.getLogger(KeychainCredentialStore.class);
    private static final String SERVICE = "NovaTerm";
    private static final KeychainCredentialStore INSTANCE = new KeychainCredentialStore();

    public static KeychainCredentialStore get() { return INSTANCE; }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private String accountKey(String username, String host, int port) {
        return username + "@" + host + ":" + port;
    }

    /** Store a password in the macOS Keychain. Returns true on success. */
    public boolean store(String username, String host, int port, String password) {
        if (!isMac()) return false;
        String account = accountKey(username, host, port);
        try {
            // Delete any existing entry first (add fails if already exists)
            new ProcessBuilder("security", "delete-generic-password",
                    "-s", SERVICE, "-a", account)
                .start().waitFor();

            Process p = new ProcessBuilder("security", "add-generic-password",
                    "-s", SERVICE,
                    "-a", account,
                    "-w", password,
                    "-U")  // -U = update if exists
                .start();
            int exit = p.waitFor();
            if (exit == 0) {
                log.info("Stored credential in Keychain for {}", account);
                return true;
            }
            log.warn("Keychain store failed with exit code {}", exit);
        } catch (Exception ex) {
            log.warn("Keychain store error: {}", ex.getMessage());
        }
        return false;
    }

    /** Retrieve a stored password from the macOS Keychain. Returns null if not found. */
    public String retrieve(String username, String host, int port) {
        if (!isMac()) return null;
        String account = accountKey(username, host, port);
        try {
            Process p = new ProcessBuilder("security", "find-generic-password",
                    "-s", SERVICE,
                    "-a", account,
                    "-w")  // -w = print password only
                .start();
            String output = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", String::concat)
                .trim();
            int exit = p.waitFor();
            if (exit == 0 && !output.isEmpty()) {
                log.debug("Retrieved credential from Keychain for {}", account);
                return output;
            }
        } catch (Exception ex) {
            log.debug("Keychain retrieve: {}", ex.getMessage());
        }
        return null;
    }

    /** Delete a stored credential from the Keychain. */
    public boolean delete(String username, String host, int port) {
        if (!isMac()) return false;
        String account = accountKey(username, host, port);
        try {
            Process p = new ProcessBuilder("security", "delete-generic-password",
                    "-s", SERVICE, "-a", account)
                .start();
            return p.waitFor() == 0;
        } catch (Exception ex) {
            log.warn("Keychain delete error: {}", ex.getMessage());
            return false;
        }
    }
}
