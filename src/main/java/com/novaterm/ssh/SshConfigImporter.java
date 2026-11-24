package com.novaterm.ssh;

import com.novaterm.session.Session;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Parses ~/.ssh/config and converts Host entries to Session objects.
 */
public class SshConfigImporter {

    public static List<Session> importFromConfig() throws IOException {
        Path configPath = Path.of(System.getProperty("user.home"), ".ssh", "config");
        if (!Files.exists(configPath)) return List.of();
        List<String> lines = Files.readAllLines(configPath);
        return parse(lines);
    }

    static List<Session> parse(List<String> lines) {
        List<Session> result = new ArrayList<>();
        Map<String, String> current = null;
        String currentAlias = null;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int spaceIdx = line.indexOf(' ');
            int equalsIdx = line.indexOf('=');
            int splitIdx;
            if (spaceIdx < 0) splitIdx = equalsIdx;
            else if (equalsIdx < 0) splitIdx = spaceIdx;
            else splitIdx = Math.min(spaceIdx, equalsIdx);

            if (splitIdx < 0) continue;
            String key = line.substring(0, splitIdx).trim().toLowerCase();
            String value = line.substring(splitIdx + 1).trim();
            if (value.startsWith("=")) value = value.substring(1).trim();

            if ("host".equals(key)) {
                if (current != null) {
                    Session s = toSession(currentAlias, current);
                    if (s != null) result.add(s);
                }
                currentAlias = value;
                current = new LinkedHashMap<>();
            } else if (current != null) {
                current.putIfAbsent(key, value);
            }
        }
        if (current != null) {
            Session s = toSession(currentAlias, current);
            if (s != null) result.add(s);
        }
        return result;
    }

    private static Session toSession(String alias, Map<String, String> props) {
        // Skip wildcard hosts
        if (alias == null || alias.contains("*") || alias.contains("?")) return null;
        // Skip if no hostname and alias doesn't look like a hostname
        String hostname = props.getOrDefault("hostname", alias);
        String user = props.getOrDefault("user", System.getProperty("user.name"));
        int port = 22;
        try { port = Integer.parseInt(props.getOrDefault("port", "22")); } catch (NumberFormatException ignored) {}
        String keyFile = props.get("identityfile");

        Session s = new Session(alias, hostname, port, user);
        s.setGroup("SSH Config");
        if (keyFile != null && !keyFile.isBlank()) {
            String expanded = keyFile.replace("~", System.getProperty("user.home"));
            s.setPrivateKeyPath(expanded);
            s.setAuthType(Session.AuthType.KEY);
        }
        return s;
    }
}
