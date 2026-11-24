package com.novaterm.ssh;

import com.novaterm.session.Session;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Measures TCP connect latency (ms) to each session's host:port.
 * Results are cached and refreshed every 30 seconds.
 * Values: -1 = unreachable, 0 = not yet measured.
 */
public class PingService {
    private static final PingService INSTANCE = new PingService();
    private final Map<String, Long> latencyMs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            4, r -> { Thread t = Thread.ofVirtual().unstarted(r); t.setDaemon(true); return t; });

    private PingService() {}

    public static PingService get() { return INSTANCE; }

    public void start() {
        // No-op — measurements are on-demand / scheduled per session
    }

    public void stop() {
        scheduler.shutdown();
    }

    /** Returns cached latency in ms, or 0 if not yet measured, or -1 if unreachable. */
    public long getLatency(Session session) {
        String key = key(session);
        return latencyMs.getOrDefault(key, 0L);
    }

    /** Schedule a one-shot measurement for this session. */
    public void measure(Session session) {
        scheduler.submit(() -> {
            long ms = tcpPing(session.getHost(), session.getPort(), 3000);
            latencyMs.put(key(session), ms);
            PingHistory.get().record(session, ms);
        });
    }

    /** Schedule periodic measurements every 30 seconds. */
    public void schedulePeriodic(Session session) {
        scheduler.scheduleAtFixedRate(() -> {
            long ms = tcpPing(session.getHost(), session.getPort(), 3000);
            latencyMs.put(key(session), ms);
            PingHistory.get().record(session, ms);
        }, 0, 30, TimeUnit.SECONDS);
    }

    private static long tcpPing(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String key(Session session) {
        return session.getHost() + ":" + session.getPort();
    }

    /** Human-readable latency badge text. */
    public static String badge(long ms) {
        if (ms == 0) return "";
        if (ms < 0) return "✕";
        if (ms < 10) return ms + "ms";
        if (ms < 100) return ms + "ms";
        if (ms < 1000) return ms + "ms";
        return ">1s";
    }

    /** CSS color class for the latency value. */
    public static String badgeColor(long ms) {
        if (ms < 0) return "#f38ba8";   // red = unreachable
        if (ms < 50) return "#a6e3a1";  // green
        if (ms < 150) return "#f9e2af"; // yellow
        return "#f38ba8";               // red = slow
    }
}
