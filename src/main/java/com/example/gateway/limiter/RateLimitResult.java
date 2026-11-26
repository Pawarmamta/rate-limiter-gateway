package com.example.gateway.limiter;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed          whether the request may proceed
 * @param limit            the configured limit (for the X-RateLimit-Limit header)
 * @param remaining        requests still available right now
 * @param retryAfterMillis when denied, how long until a retry can succeed; 0 if allowed
 */
public record RateLimitResult(boolean allowed, int limit, long remaining, long retryAfterMillis) {

    public static RateLimitResult allowed(int limit, long remaining) {
        return new RateLimitResult(true, limit, remaining, 0);
    }

    public static RateLimitResult denied(int limit, long retryAfterMillis) {
        return new RateLimitResult(false, limit, 0, retryAfterMillis);
    }
}
