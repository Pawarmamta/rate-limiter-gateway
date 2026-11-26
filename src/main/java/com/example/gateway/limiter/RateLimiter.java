package com.example.gateway.limiter;

/** Strategy interface so algorithms (and later Redis-backed ones) are swappable. */
public interface RateLimiter {

    /**
     * Atomically checks and consumes one permit for the given key.
     *
     * @param key  identity being limited (API key, user id, IP...)
     * @param rule the limit to enforce
     */
    RateLimitResult tryAcquire(String key, RateLimitRule rule);
}
