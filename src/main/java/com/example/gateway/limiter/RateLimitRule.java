package com.example.gateway.limiter;

import java.time.Duration;

/** "limit" requests per "window". Example: 100 requests per 1 minute. */
public record RateLimitRule(int limit, Duration window) {
    public RateLimitRule {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
