package com.novaterm.ssh;

import com.novaterm.session.Session;
import org.apache.sshd.agent.unix.UnixAgentFactory;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SSHManager {
    private static final Logger log = LoggerFactory.getLogger(SSHManager.class);
    private static final SSHManager INSTANCE = new SSHManager();

    private final SshClient client;
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    private final KnownHostsVerifier knownHostsVerifier = new KnownHostsVerifier();

    public KnownHostsVerifier getKnownHostsVerifier() { return knownHostsVerifier; }

    private SSHManager() {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(knownHostsVerifier);
        // Enable SSH agent forwarding when SSH_AUTH_SOCK is set
        String agentSock = System.getenv("SSH_AUTH_SOCK");
        if (agentSock != null && !agentSock.isBlank()) {
            try {
                client.setAgentFactory(new UnixAgentFactory());
                log.info("SSH agent socket: {}", agentSock);
            } catch (Exception e) {
                log.warn("Could not configure SSH agent factory", e);
            }
        }
        client.start();
    }

    public static SSHManager get() { return INSTANCE; }

    /** @param credential password for PASSWORD auth, or key passphrase for KEY auth (may be empty) */
    public ClientSession connect(Session session, String credential) throws Exception {
        // If a jump host is configured, tunnel through it
        if (session.getJumpHost() != null && !session.getJumpHost().isBlank()) {
            return connectViaJump(session, credential);
        }
        return connectDirect(session, credential);
    }

    private ClientSession connectViaJump(Session session, String credential) throws Exception {
        // Step 1: connect to jump host (use same credential for now)
        ConnectFuture jumpFuture = client.connect(
                session.getJumpUsername() != null ? session.getJumpUsername() : session.getUsername(),
                session.getJumpHost(), session.getJumpPort());
        jumpFuture.await(ConnectionConfig.connectTimeout(), TimeUnit.SECONDS);
        ClientSession jumpSession = jumpFuture.getSession();
        jumpSession.addPasswordIdentity(credential);
        jumpSession.auth().verify(Duration.ofSeconds(ConnectionConfig.authTimeout()));

        // Step 2: open a direct-tcpip channel through the jump session to the target
        java.net.InetSocketAddress target = new java.net.InetSocketAddress(session.getHost(), session.getPort());
        org.apache.sshd.client.channel.ClientChannelEvent[] waitFor =
                {org.apache.sshd.client.channel.ClientChannelEvent.CLOSED};
        // Use SshClient to connect through a port forwarding on a local ephemeral port
        int freePort;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0)) {
            freePort = tmp.getLocalPort();
        }
        var localAddr = new org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", freePort);
        var remoteAddr = new org.apache.sshd.common.util.net.SshdSocketAddress(session.getHost(), session.getPort());
        jumpSession.startLocalPortForwarding(localAddr, remoteAddr);

        // Step 3: connect a new SSH session to 127.0.0.1:freePort (which tunnels through jump)
        ConnectFuture targetFuture = client.connect(session.getUsername(), "127.0.0.1", freePort);
        targetFuture.await(ConnectionConfig.connectTimeout(), TimeUnit.SECONDS);
        ClientSession cs = targetFuture.getSession();
        authenticateSession(cs, session, credential);
        sessions.put(session.getId(), cs);
        log.info("Connected via jump {}: {}@{}:{}", session.getJumpHost(),
                session.getUsername(), session.getHost(), session.getPort());
        return cs;
    }

    private ClientSession connectDirect(Session session, String credential) throws Exception {
        ConnectFuture future = client.connect(session.getUsername(), session.getHost(), session.getPort());
        future.await(ConnectionConfig.connectTimeout(), TimeUnit.SECONDS);
        ClientSession cs = future.getSession();
        authenticateSession(cs, session, credential);
        sessions.put(session.getId(), cs);
        log.info("Connected: {}@{}:{}", session.getUsername(), session.getHost(), session.getPort());
        return cs;
    }

    private void authenticateSession(ClientSession cs, Session session, String credential) throws Exception {
        switch (session.getAuthType()) {
            case PASSWORD -> cs.addPasswordIdentity(credential);
            case KEY -> {
                Path keyPath = Path.of(session.getPrivateKeyPath());
                FileKeyPairProvider provider = new FileKeyPairProvider(keyPath);
                if (credential != null && !credential.isBlank()) {
                    provider.setPasswordFinder(FilePasswordProvider.of(credential));
                }
                cs.setKeyIdentityProvider(provider);
            }
            case AGENT -> {
                String sock = System.getenv("SSH_AUTH_SOCK");
                if (sock == null || sock.isBlank()) {
                    throw new IllegalStateException("SSH_AUTH_SOCK not set; cannot use agent auth");
                }
            }
        }
        cs.auth().verify(Duration.ofSeconds(ConnectionConfig.authTimeout()));
        int keepAlive = ConnectionConfig.keepAlive();
        if (keepAlive > 0) {
            cs.setSessionHeartbeat(
                    org.apache.sshd.common.session.SessionHeartbeatController.HeartbeatType.IGNORE,
                    Duration.ofSeconds(keepAlive));
        }
    }

    /**
     * Opens a PTY shell channel.
     * @return ShellChannel wrapping the PtyCapableChannelSession for resize calls.
     */
    public ShellChannel openShell(ClientSession cs, int cols, int rows,
                                   InputStream shellIn, OutputStream shellOut) throws Exception {
        PtyCapableChannelSession channel = cs.createShellChannel();
        channel.setupSensibleDefaultPty();
        channel.setPtyColumns(cols);
        channel.setPtyLines(rows);
        channel.setIn(shellIn);
        channel.setOut(shellOut);
        channel.setErr(shellOut);
        channel.open().verify(Duration.ofSeconds(10));
        return new ShellChannel(channel);
    }

    public void disconnect(String sessionId) {
        ClientSession cs = sessions.remove(sessionId);
        if (cs != null) {
            try { cs.close(); } catch (Exception e) { log.warn("Error closing session", e); }
        }
    }

    public void shutdown() {
        sessions.values().forEach(cs -> { try { cs.close(); } catch (Exception ignored) {} });
        try { client.stop(); } catch (Exception ignored) {}
    }
}
