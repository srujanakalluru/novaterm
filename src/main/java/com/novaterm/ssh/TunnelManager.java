package com.novaterm.ssh;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class TunnelManager {
    private static final Logger log = LoggerFactory.getLogger(TunnelManager.class);

    private final List<TunnelEntry> tunnels = new ArrayList<>();

    public record TunnelEntry(
            String label,
            int localPort,
            String remoteHost,
            int remotePort,
            ExplicitPortForwardingTracker tracker
    ) {}

    /** Local port forward: localhost:localPort -> remoteHost:remotePort via SSH session */
    public TunnelEntry addLocalForward(ClientSession session, String label,
                                       int localPort, String remoteHost, int remotePort) throws Exception {
        SshdSocketAddress local = new SshdSocketAddress("127.0.0.1", localPort);
        SshdSocketAddress remote = new SshdSocketAddress(remoteHost, remotePort);
        ExplicitPortForwardingTracker tracker = session.createLocalPortForwardingTracker(local, remote);
        TunnelEntry entry = new TunnelEntry(label, localPort, remoteHost, remotePort, tracker);
        tunnels.add(entry);
        log.info("Tunnel: 127.0.0.1:{} -> {}:{}", localPort, remoteHost, remotePort);
        return entry;
    }

    /** Remote port forward: remotePort on SSH server -> localhost:localPort */
    public TunnelEntry addRemoteForward(ClientSession session, String label,
                                        int remotePort, String localHost, int localPort) throws Exception {
        SshdSocketAddress remote = new SshdSocketAddress("0.0.0.0", remotePort);
        SshdSocketAddress local = new SshdSocketAddress(localHost, localPort);
        ExplicitPortForwardingTracker tracker = session.createRemotePortForwardingTracker(remote, local);
        TunnelEntry entry = new TunnelEntry(label, localPort, localHost, remotePort, tracker);
        tunnels.add(entry);
        log.info("Remote tunnel: server:{} -> {}:{}", remotePort, localHost, localPort);
        return entry;
    }

    public void close(TunnelEntry entry) {
        tunnels.remove(entry);
        try { entry.tracker().close(); } catch (Exception e) { log.warn("Error closing tunnel", e); }
    }

    public void closeAll() {
        new ArrayList<>(tunnels).forEach(this::close);
    }

    public List<TunnelEntry> getTunnels() {
        return List.copyOf(tunnels);
    }
}
