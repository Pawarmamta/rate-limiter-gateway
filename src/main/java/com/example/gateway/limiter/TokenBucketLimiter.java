package com.example.gateway.limiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket: a bucket holds up to "limit" tokens and refills continuously.
 * Each request takes one token. Allows short bursts while enforcing an average rate.
 */
public class TokenBucketLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;

        Bucket(double tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis; // injectable so tests can control time

    public TokenBucketLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        long now = clockMillis.getAsLong();
        double refillPerMilli = (double) rule.limit() / rule.window().toMillis();

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(rule.limit(), now));

        synchronized (bucket) { // per-key lock: different users never block each other
            long elapsed = Math.max(0, now - bucket.lastRefillMillis);
            bucket.tokens = Math.min(rule.limit(), bucket.tokens + elapsed * refillPerMilli);
            bucket.lastRefillMillis = now;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return RateLimitResult.allowed(rule.limit(), (long) bucket.tokens);
            }
            long retryAfter = (long) Math.ceil((1.0 - bucket.tokens) / refillPerMilli);
            return RateLimitResult.denied(rule.limit(), retryAfter);
        }
    }
}
