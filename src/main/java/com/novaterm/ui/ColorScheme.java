package com.novaterm.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Returns [bg, fg, cursorFg] colors for a named terminal color scheme. */
public class ColorScheme {

    // Order preserved via LinkedHashMap so ComboBox shows a logical sequence
    private static final Map<String, String[]> SCHEMES = new LinkedHashMap<>();

    static {
        // ── Dark themes ───────────────────────────────────────────────────────
        SCHEMES.put("Catppuccin Mocha",   new String[]{"#1e1e2e", "#cdd6f4", "#f5e0dc"});
        SCHEMES.put("Catppuccin Macchiato", new String[]{"#24273a", "#cad3f5", "#f4dbd6"});
        SCHEMES.put("Dracula",            new String[]{"#282a36", "#f8f8f2", "#ff79c6"});
        SCHEMES.put("One Dark",           new String[]{"#282c34", "#abb2bf", "#528bff"});
        SCHEMES.put("Tokyo Night",        new String[]{"#1a1b26", "#c0caf5", "#7aa2f7"});
        SCHEMES.put("Nord",               new String[]{"#2e3440", "#d8dee9", "#88c0d0"});
        SCHEMES.put("Gruvbox Dark",       new String[]{"#282828", "#ebdbb2", "#fe8019"});
        SCHEMES.put("Gruvbox Dark Hard",  new String[]{"#1d2021", "#ebdbb2", "#fe8019"});
        SCHEMES.put("Solarized Dark",     new String[]{"#002b36", "#839496", "#2aa198"});
        SCHEMES.put("Monokai",            new String[]{"#272822", "#f8f8f2", "#a6e22e"});
        SCHEMES.put("Material Dark",      new String[]{"#263238", "#eeffff", "#80cbc4"});
        SCHEMES.put("Rose Pine",          new String[]{"#191724", "#e0def4", "#c4a7e7"});
        SCHEMES.put("Rose Pine Moon",     new String[]{"#232136", "#e0def4", "#c4a7e7"});
        SCHEMES.put("Everforest Dark",    new String[]{"#2d353b", "#d3c6aa", "#a7c080"});
        SCHEMES.put("Kanagawa",           new String[]{"#1f1f28", "#dcd7ba", "#c8c093"});
        SCHEMES.put("Mellow",             new String[]{"#1e1e1e", "#c5c5c5", "#b0b0b0"});
        SCHEMES.put("Ayu Dark",           new String[]{"#0a0e14", "#b3b1ad", "#e6b450"});
        SCHEMES.put("Palenight",          new String[]{"#292d3e", "#a6accd", "#82aaff"});
        // ── Light themes ─────────────────────────────────────────────────────
        SCHEMES.put("Catppuccin Latte",   new String[]{"#eff1f5", "#4c4f69", "#dc8a78"});
        SCHEMES.put("Solarized Light",    new String[]{"#fdf6e3", "#657b83", "#268bd2"});
        SCHEMES.put("Gruvbox Light",      new String[]{"#fbf1c7", "#3c3836", "#af3a03"});
        SCHEMES.put("Rose Pine Dawn",     new String[]{"#faf4ed", "#575279", "#907aa9"});
        SCHEMES.put("Ayu Light",          new String[]{"#fafafa", "#575f66", "#ff9940"});
        SCHEMES.put("Github Light",       new String[]{"#ffffff", "#24292f", "#0969da"});
    }

    public static String[] resolve(String name) {
        return SCHEMES.getOrDefault(name,
                SCHEMES.get("Catppuccin Mocha"));
    }

    public static java.util.List<String> names() {
        return new java.util.ArrayList<>(SCHEMES.keySet());
    }
}
