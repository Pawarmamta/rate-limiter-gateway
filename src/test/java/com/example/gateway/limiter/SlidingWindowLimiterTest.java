package com.example.gateway.limiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final RateLimiter limiter = new SlidingWindowLimiter(now::get);
    private final RateLimitRule rule = new RateLimitRule(3, Duration.ofSeconds(10));

    @Test
    void blocksAfterLimitInsideWindow() {
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryAcquire("u1", rule).allowed());
        assertFalse(limiter.tryAcquire("u1", rule).allowed());
    }

    @Test
    void oldRequestsSlideOut() {
        limiter.tryAcquire("u1", rule);      // t=0
        now.set(4_000);
        limiter.tryAcquire("u1", rule);      // t=4s
        limiter.tryAcquire("u1", rule);      // t=4s
        assertFalse(limiter.tryAcquire("u1", rule).allowed());

        now.set(10_000);                     // t=0 request leaves the window
        assertTrue(limiter.tryAcquire("u1", rule).allowed());
        assertFalse(limiter.tryAcquire("u1", rule).allowed());
    }

    @Test
    void reportsAccurateRetryAfter() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("u1", rule);
        now.set(3_000);
        RateLimitResult r = limiter.tryAcquire("u1", rule);
        assertFalse(r.allowed());
        assertEquals(7_000, r.retryAfterMillis());
    }
}
