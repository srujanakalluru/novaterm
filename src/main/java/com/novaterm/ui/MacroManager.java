package com.novaterm.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Records and replays terminal keystroke macros.
 * Macros are stored in ~/Library/Application Support/NovaTerm/macros.json.
 */
public class MacroManager {
    private static final Logger log = LoggerFactory.getLogger(MacroManager.class);
    private static final Path STORE_PATH = Path.of(
            System.getProperty("user.home"), "Library", "Application Support",
            "NovaTerm", "macros.json");

    public record Macro(String name, List<String> steps) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Macro> macros = new ArrayList<>();

    // Recording state
    private boolean recording = false;
    private String recordingName;
    private final List<String> recordBuffer = new ArrayList<>();

    public MacroManager() { load(); }

    public void load() {
        macros.clear();
        if (!Files.exists(STORE_PATH)) return;
        try {
            List<Macro> loaded = mapper.readValue(STORE_PATH.toFile(), new TypeReference<>() {});
            macros.addAll(loaded);
        } catch (Exception e) {
            log.error("Failed to load macros", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(STORE_PATH.toFile(), macros);
        } catch (Exception e) {
            log.error("Failed to save macros", e);
        }
    }

    public List<Macro> getMacros() { return Collections.unmodifiableList(macros); }

    public boolean isRecording() { return recording; }

    public void startRecording(String name) {
        recordingName = name;
        recordBuffer.clear();
        recording = true;
    }

    public void record(String data) {
        if (recording) recordBuffer.add(data);
    }

    /** Stops recording and saves the macro. Returns the saved macro. */
    public Macro stopRecording() {
        recording = false;
        Macro m = new Macro(recordingName, new ArrayList<>(recordBuffer));
        macros.add(m);
        save();
        return m;
    }

    public void deleteMacro(Macro m) {
        macros.remove(m);
        save();
    }

    /** Returns the steps of the named macro, or empty list if not found. */
    public List<String> getSteps(String name) {
        return macros.stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .map(Macro::steps)
                .orElse(List.of());
    }
}
