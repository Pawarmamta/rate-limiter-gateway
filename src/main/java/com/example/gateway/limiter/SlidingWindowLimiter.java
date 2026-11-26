package com.example.gateway.limiter;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sliding window log: remembers the timestamp of every accepted request and
 * counts those inside the last "window". Exact (no boundary bursts) but uses
 * more memory than a token bucket.
 */
public class SlidingWindowLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, ArrayDeque<Long>> logs = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public SlidingWindowLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        long now = clockMillis.getAsLong();
        long windowMillis = rule.window().toMillis();

        ArrayDeque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (log) {
            // drop timestamps that fell out of the window
            while (!log.isEmpty() && log.peekFirst() <= now - windowMillis) {
                log.pollFirst();
            }
            if (log.size() < rule.limit()) {
                log.addLast(now);
                return RateLimitResult.allowed(rule.limit(), rule.limit() - log.size());
            }
            long retryAfter = log.peekFirst() + windowMillis - now;
            return RateLimitResult.denied(rule.limit(), Math.max(1, retryAfter));
        }
    }
}
