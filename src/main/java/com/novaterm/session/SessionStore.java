package com.novaterm.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SessionStore {
    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), "Library", "Application Support",
            "NovaTerm", "sessions.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private List<Session> sessions = new ArrayList<>();

    public void load() {
        if (!Files.exists(STORE_PATH)) return;
        try {
            sessions = mapper.readValue(STORE_PATH.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to load sessions", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(STORE_PATH.toFile(), sessions);
        } catch (Exception e) {
            log.error("Failed to save sessions", e);
        }
    }

    public List<Session> getSessions() { return sessions; }

    public void add(Session s) { sessions.add(s); save(); }

    public void remove(Session s) { sessions.remove(s); save(); }

    public void update(Session s) {
        sessions.replaceAll(existing -> existing.getId().equals(s.getId()) ? s : existing);
        save();
    }

    /** Export all sessions to a JSON file. */
    public void exportTo(java.io.File file) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, sessions);
    }

    /**
     * Import sessions from a JSON file. Existing sessions with the same id are skipped;
     * new ones are appended. Returns the count of newly imported sessions.
     */
    public int importFrom(java.io.File file) throws Exception {
        List<Session> imported = mapper.readValue(file, new TypeReference<>() {});
        java.util.Set<String> existingIds = new java.util.HashSet<>();
        sessions.forEach(s -> existingIds.add(s.getId()));
        int count = 0;
        for (Session s : imported) {
            if (!existingIds.contains(s.getId())) {
                sessions.add(s);
                count++;
            }
        }
        if (count > 0) save();
        return count;
    }
}
