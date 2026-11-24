package com.novaterm.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PluginLoader {
    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);
    private static final String PLUGIN_DIR = System.getProperty("user.home")
            + "/Library/Application Support/NovaTerm/plugins";

    private final Map<String, NovaTPlugin> loaded = new LinkedHashMap<>();

    public void loadAll(PluginContext context) {
        Path dir = Path.of(PLUGIN_DIR);
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (Exception e) { /* ignore */ }
            return;
        }

        File[] jars = dir.toFile().listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null) return;

        for (File jar : jars) {
            try {
                URLClassLoader cl = new URLClassLoader(
                        new URL[]{jar.toURI().toURL()},
                        getClass().getClassLoader()
                );
                ServiceLoader<NovaTPlugin> sl = ServiceLoader.load(NovaTPlugin.class, cl);
                for (NovaTPlugin plugin : sl) {
                    if (loaded.containsKey(plugin.getId())) {
                        log.warn("Duplicate plugin id '{}', skipping {}", plugin.getId(), jar.getName());
                        continue;
                    }
                    plugin.onLoad(context);
                    loaded.put(plugin.getId(), plugin);
                    log.info("Loaded plugin: {} v{}", plugin.getName(), plugin.getVersion());
                }
            } catch (Exception e) {
                log.error("Failed to load plugin from {}: {}", jar.getName(), e.getMessage());
            }
        }
    }

    public void unloadAll() {
        loaded.values().forEach(p -> {
            try { p.onUnload(); } catch (Exception e) { log.warn("Error unloading {}", p.getId(), e); }
        });
        loaded.clear();
    }

    public Collection<NovaTPlugin> getLoaded() {
        return Collections.unmodifiableCollection(loaded.values());
    }
}
