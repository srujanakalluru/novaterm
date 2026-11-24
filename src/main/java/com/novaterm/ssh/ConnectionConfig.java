package com.novaterm.ssh;

import com.novaterm.ui.PreferencesDialog;

import java.util.prefs.Preferences;

public class ConnectionConfig {
    private static final Preferences PREFS = PreferencesDialog.get();

    public static final String PREF_CONNECT_TIMEOUT  = "ssh.connectTimeoutSecs";
    public static final String PREF_AUTH_TIMEOUT     = "ssh.authTimeoutSecs";
    public static final String PREF_KEEPALIVE_SECS   = "ssh.keepAliveIntervalSecs";
    public static final String PREF_RETRY_COUNT      = "ssh.retryCount";
    public static final String PREF_RETRY_DELAY_SECS = "ssh.retryDelaySecs";

    public static int connectTimeout()  { return PREFS.getInt(PREF_CONNECT_TIMEOUT,  10); }
    public static int authTimeout()     { return PREFS.getInt(PREF_AUTH_TIMEOUT,     30); }
    public static int keepAlive()       { return PREFS.getInt(PREF_KEEPALIVE_SECS,   60); }
    public static int retryCount()      { return PREFS.getInt(PREF_RETRY_COUNT,       2); }
    public static int retryDelay()      { return PREFS.getInt(PREF_RETRY_DELAY_SECS,  3); }
}
