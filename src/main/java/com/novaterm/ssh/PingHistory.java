package com.novaterm.ssh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores per-session ping latency samples (up to MAX_SAMPLES each).
 * Singleton; PingService feeds measurements here.
 */
public class PingHistory {

    private static final int MAX_SAMPLES = 60;
    private static final PingHistory INSTANCE = new PingHistory();
    public static PingHistory get() { return INSTANCE; }

    private final ConcurrentHashMap<String, Deque<Long>> history = new ConcurrentHashMap<>();

    private String key(com.novaterm.session.Session session) {
        return session.getHost() + ":" + session.getPort();
    }

    public void record(com.novaterm.session.Session session, long ms) {
        if (ms <= 0) return;
        String k = key(session);
        history.compute(k, (key, deq) -> {
            if (deq == null) deq = new ArrayDeque<>();
            deq.addLast(ms);
            while (deq.size() > MAX_SAMPLES) deq.removeFirst();
            return deq;
        });
    }

    /** Returns a snapshot of samples (oldest first) or empty list. */
    public List<Long> getSamples(com.novaterm.session.Session session) {
        Deque<Long> deq = history.get(key(session));
        return deq == null ? List.of() : new ArrayList<>(deq);
    }

    /** Min latency seen, or 0. */
    public long min(com.novaterm.session.Session session) {
        return getSamples(session).stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /** Max latency seen, or 0. */
    public long max(com.novaterm.session.Session session) {
        return getSamples(session).stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Average latency, or 0. */
    public long avg(com.novaterm.session.Session session) {
        return (long) getSamples(session).stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
