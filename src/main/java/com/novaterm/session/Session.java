package com.novaterm.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Session {
    private String id;
    private String name;
    private String host;
    private int port = 22;
    private String username;
    private AuthType authType = AuthType.PASSWORD;
    private String privateKeyPath;
    private String group;
    private String color;
    private boolean favorite;
    private String notes;
    private List<TunnelConfig> tunnels = new ArrayList<>();
    private String startupCommand;
    private String envVars;   // newline-separated "KEY=value" pairs injected after connect
    private String jumpHost;
    private int jumpPort = 22;
    private String jumpUsername;
    private List<String> tags = new ArrayList<>();

    public enum AuthType { PASSWORD, KEY, AGENT }

    public Session() {
        this.id = UUID.randomUUID().toString();
    }

    public Session(String name, String host, int port, String username) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<TunnelConfig> getTunnels() { return tunnels; }
    public void setTunnels(List<TunnelConfig> tunnels) { this.tunnels = tunnels != null ? tunnels : new ArrayList<>(); }
    public String getStartupCommand() { return startupCommand; }
    public void setStartupCommand(String startupCommand) { this.startupCommand = startupCommand; }
    public String getEnvVars() { return envVars; }
    public void setEnvVars(String envVars) { this.envVars = envVars; }
    public String getJumpHost() { return jumpHost; }
    public void setJumpHost(String jumpHost) { this.jumpHost = jumpHost; }
    public int getJumpPort() { return jumpPort; }
    public void setJumpPort(int jumpPort) { this.jumpPort = jumpPort; }
    public String getJumpUsername() { return jumpUsername; }
    public void setJumpUsername(String jumpUsername) { this.jumpUsername = jumpUsername; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    /** Create a shallow copy with a new UUID — does NOT copy the id. */
    public Session duplicate() {
        Session s = new Session();
        s.name = this.name;
        s.host = this.host;
        s.port = this.port;
        s.username = this.username;
        s.authType = this.authType;
        s.privateKeyPath = this.privateKeyPath;
        s.group = this.group;
        s.color = this.color;
        s.favorite = this.favorite;
        s.notes = this.notes;
        s.startupCommand = this.startupCommand;
        s.envVars = this.envVars;
        s.jumpHost = this.jumpHost;
        s.jumpPort = this.jumpPort;
        s.jumpUsername = this.jumpUsername;
        s.tunnels = new ArrayList<>(this.tunnels);
        s.tags = new ArrayList<>(this.tags);
        return s;
    }

    @Override
    public String toString() {
        return name != null ? name : username + "@" + host;
    }
}
