# NovaTerm

A modern macOS SSH terminal inspired by MobaXterm, built with JavaFX and Apache MINA SSHD.

## Features

- **Multi-tab SSH terminal** — connect to multiple servers simultaneously
- **Local shell tab** — open a local `/bin/zsh` or `/bin/bash` session
- **Full VT100/ANSI emulator** — supports 256-color, bold/italic/underline, alternate screen (vim, htop, etc.), scrolling regions
- **SFTP browser** — browse remote filesystems with double-click navigation
- **Port forwarding** — local and remote tunnel management per session
- **Session manager** — persistent session list with groups and color labels
- **Known-hosts verification** — TOFU (trust-on-first-use) with SHA-256 fingerprints; warns on key changes
- **Plugin system** — drop `.jar` files into the plugins directory; uses Java ServiceLoader
- **Preferences** — font size, font family, color scheme (Catppuccin, Dracula, Solarized, One Dark, Tokyo Night, …)
- **Keyboard shortcuts** — `Cmd+N` new session, `Cmd+T` local terminal, `Cmd+W` close tab, `Cmd+1`–`Cmd+9` switch tabs
- **macOS native menu bar** — Preferences at `Cmd+,`, system menu bar integration

## Requirements

- **Java 21+** (OpenJDK 21 or Temurin 21 recommended)
- **Gradle 8.6+** (wrapper included — no install needed)
- For `.dmg` packaging: macOS + JDK 14+ with `jpackage` + Xcode command-line tools

## Build

```bash
# Clone or unzip the project
cd novaterm

# Compile and package
./gradlew build

# Run directly
./gradlew run

# Build a fat JAR
./gradlew fatJar
# Output: build/libs/novaterm-1.0.0-all.jar

# Build a macOS .dmg (run on macOS only)
./gradlew jpackage
# Output: build/dist/NovaTerm-1.0.0.dmg
```

## Running the fat JAR

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-exports=javafx.web/com.sun.javafx.webkit=ALL-UNNAMED \
     -jar build/libs/novaterm-1.0.0-all.jar
```

## Project Layout

```
src/main/java/com/novaterm/
  App.java                   JavaFX Application entry point
  Main.java                  Main class (delegates to App)
  session/
    Session.java             Session model (host, port, auth, group)
    SessionStore.java        JSON persistence (~/.../sessions.json)
  ssh/
    SSHManager.java          MINA SSHD client; connect/openShell/disconnect
    ShellChannel.java        PTY channel wrapper with resize support
    TunnelManager.java       Local and remote port forward management
    KnownHostsVerifier.java  TOFU known-hosts verifier with MITM warning
  ui/
    MainWindow.java          Root UI: menu bar + sidebar + tab pane
    SessionManagerPanel.java Left sidebar: session list with context menu
    SessionDialog.java       New/edit session dialog
    SessionTabState.java     Per-tab state (ClientSession, TunnelManager)
    TerminalTab.java         WebView-based terminal; VT100 JS bridge
    SFTPPanel.java           SFTP file browser panel
    TunnelDialog.java        Port forwarding add/remove UI
    PreferencesDialog.java   Font, color scheme, scrollback prefs
    ColorScheme.java         Named color scheme palette map
  plugin/
    NovaTPlugin.java         Plugin interface (id, name, version, onLoad, onUnload)
    PluginContext.java        Services plugins call back into the host
    PluginLoader.java        ServiceLoader-based JAR plugin loader

src/main/resources/com/novaterm/
  styles.css                 Catppuccin Mocha dark theme
  web/
    vt100.js                 VT100/ANSI terminal emulator (canvas-based)
```

## Writing a Plugin

1. Create a new Gradle project with `compileOnly` on `novaterm-*.jar`
2. Implement `com.novaterm.plugin.NovaTPlugin`
3. Add a `META-INF/services/com.novaterm.plugin.NovaTPlugin` file listing your class
4. Build a JAR and drop it in `~/Library/Application Support/NovaTerm/plugins/`
5. Restart NovaTerm — your plugin will appear under Plugins → Manage Plugins

See `../novaterm-sample-plugin/` for a working example (System Info panel + menu action).

## Data Files

| Path | Content |
|------|---------|
| `~/Library/Application Support/NovaTerm/sessions.json` | Saved sessions |
| `~/Library/Application Support/NovaTerm/known_hosts.properties` | Accepted host fingerprints |
| `~/Library/Application Support/NovaTerm/plugins/` | Plugin JARs |
| `~/Library/Logs/NovaTerm/novaterm.log` | Application log (rotated, 5 MB/7 days) |
| `~/Library/Preferences/com.novaterm.ui.PreferencesDialog.plist` | User preferences |

## Authentication

| Method | How to use |
|--------|-----------|
| Password | Enter in the prompt dialog at connect time |
| Private key | Set Auth = Key in session settings; path to `~/.ssh/id_rsa` etc. |
| SSH agent | Set Auth = Agent; ensure `SSH_AUTH_SOCK` is set |

## Known Limitations / Next Steps

- **Mouse support** in terminal not yet implemented (vim mouse mode won't work)
- **Clipboard** paste needs a right-click menu or `Cmd+V` handler in JS
- **Search** (`Cmd+F`) in terminal not yet implemented
- **Split panes** (side-by-side terminals) not yet implemented
- **X11 forwarding** not yet implemented
- **Connection retry / reconnect** on drop not yet implemented
- **Tab drag-and-drop** reordering not yet implemented
