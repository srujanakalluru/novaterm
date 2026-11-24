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
        SCHEMES.put("Github Light",       new String[]{"#ffffff", "#24292f", "#0969da"});
        SCHEMES.put("Catppuccin Latte",   new String[]{"#eff1f5", "#4c4f69", "#dc8a78"});
        SCHEMES.put("One Light",          new String[]{"#fafafa", "#383a42", "#4078f2"});
        SCHEMES.put("Solarized Light",    new String[]{"#fdf6e3", "#657b83", "#268bd2"});
        SCHEMES.put("Gruvbox Light",      new String[]{"#fbf1c7", "#3c3836", "#af3a03"});
        SCHEMES.put("Rose Pine Dawn",     new String[]{"#faf4ed", "#575279", "#907aa9"});
        SCHEMES.put("Ayu Light",          new String[]{"#fafafa", "#575f66", "#ff9940"});
        SCHEMES.put("Everforest Light",   new String[]{"#f2efdf", "#5c6a72", "#8da101"});
        SCHEMES.put("Flexoki Light",      new String[]{"#fffcf0", "#100f0f", "#d14d41"});
        SCHEMES.put("Material Light",     new String[]{"#fafafa", "#546e7a", "#00bcd4"});
        SCHEMES.put("Papercolor Light",   new String[]{"#eeeeee", "#444444", "#0087af"});
        SCHEMES.put("Tomorrow",           new String[]{"#ffffff", "#4d4d4c", "#4271ae"});
        SCHEMES.put("Alabaster",          new String[]{"#f7f7f7", "#433b35", "#aa3731"});
        SCHEMES.put("Parchment",          new String[]{"#f8f0d9", "#403428", "#c8a060"});
        SCHEMES.put("Quiet Light",        new String[]{"#f5f5f5", "#333333", "#007acc"});
        SCHEMES.put("Pencil Light",       new String[]{"#f1f1f1", "#212121", "#0088cc"});
        SCHEMES.put("Modus Operandi",     new String[]{"#ffffff", "#000000", "#0030a6"});
        SCHEMES.put("IntelliJ Light",     new String[]{"#ffffff", "#000000", "#2f6fba"});
        SCHEMES.put("Atelier Sulphurpool",new String[]{"#f5f7ff", "#5e6687", "#6679cc"});
    }

    public static String[] resolve(String name) {
        return SCHEMES.getOrDefault(name,
                SCHEMES.get("Github Light"));
    }

    public static java.util.List<String> names() {
        return new java.util.ArrayList<>(SCHEMES.keySet());
    }
}
