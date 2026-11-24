package com.novaterm.plugin;

/**
 * Contract every NovaTerm plugin JAR must implement.
 * Place a JAR in ~/Library/Application Support/NovaTerm/plugins/ to load it.
 */
public interface NovaTPlugin {
    /** Unique reverse-DNS identifier, e.g. "com.example.my-plugin" */
    String getId();

    /** Human-readable display name */
    String getName();

    /** Semantic version string */
    String getVersion();

    /** Called once when the plugin is loaded. */
    void onLoad(PluginContext context);

    /** Called once just before the plugin is unloaded / app exits. */
    void onUnload();
}
