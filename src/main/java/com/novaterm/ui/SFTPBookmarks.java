package com.novaterm.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Persistent SFTP directory bookmarks stored in java.util.prefs.
 * Key format: "bm.<index>" → path; "bm.count" → number of entries.
 */
public class SFTPBookmarks {

    private static final int MAX_BOOKMARKS = 30;
    private static final Preferences PREFS =
            Preferences.userRoot().node("com/novaterm/sftp-bookmarks");
    private static final SFTPBookmarks INSTANCE = new SFTPBookmarks();
    public static SFTPBookmarks get() { return INSTANCE; }

    public List<String> getAll() {
        int count = PREFS.getInt("count", 0);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String v = PREFS.get("bm." + i, null);
            if (v != null) list.add(v);
        }
        return list;
    }

    public void add(String path) {
        List<String> all = getAll();
        if (all.contains(path)) return;
        all.add(0, path);
        if (all.size() > MAX_BOOKMARKS) all = all.subList(0, MAX_BOOKMARKS);
        save(all);
    }

    public void remove(String path) {
        List<String> all = getAll();
        all.remove(path);
        save(all);
    }

    public boolean contains(String path) {
        return getAll().contains(path);
    }

    private void save(List<String> list) {
        try { PREFS.clear(); } catch (Exception ignored) {}
        PREFS.putInt("count", list.size());
        for (int i = 0; i < list.size(); i++) {
            PREFS.put("bm." + i, list.get(i));
        }
    }
}
