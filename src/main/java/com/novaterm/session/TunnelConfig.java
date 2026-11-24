package com.novaterm.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A port-forwarding rule persisted with a session and auto-started on connect. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TunnelConfig {
    private String label;
    private boolean remote; // false = local forward, true = remote forward
    private int localPort;
    private String remoteHost;
    private int remotePort;

    public TunnelConfig() {}

    public TunnelConfig(String label, boolean remote, int localPort, String remoteHost, int remotePort) {
        this.label = label;
        this.remote = remote;
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isRemote() { return remote; }
    public void setRemote(boolean remote) { this.remote = remote; }
    public int getLocalPort() { return localPort; }
    public void setLocalPort(int localPort) { this.localPort = localPort; }
    public String getRemoteHost() { return remoteHost; }
    public void setRemoteHost(String remoteHost) { this.remoteHost = remoteHost; }
    public int getRemotePort() { return remotePort; }
    public void setRemotePort(int remotePort) { this.remotePort = remotePort; }
}
