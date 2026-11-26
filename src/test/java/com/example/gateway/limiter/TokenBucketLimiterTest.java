package com.example.gateway.limiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final RateLimiter limiter = new TokenBucketLimiter(now::get);
    private final RateLimitRule rule = new RateLimitRule(5, Duration.ofSeconds(10));

    @Test
    void allowsUpToLimitThenBlocks() {
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire("u1", rule).allowed());
        RateLimitResult blocked = limiter.tryAcquire("u1", rule);
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterMillis() > 0);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 5; i++) limiter.tryAcquire("u1", rule);
        assertFalse(limiter.tryAcquire("u1", rule).allowed());

        now.addAndGet(2_000); // 5 tokens / 10s = 1 token per 2s
        assertTrue(limiter.tryAcquire("u1", rule).allowed());
        assertFalse(limiter.tryAcquire("u1", rule).allowed());
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) limiter.tryAcquire("u1", rule);
        assertFalse(limiter.tryAcquire("u1", rule).allowed());
        assertTrue(limiter.tryAcquire("u2", rule).allowed());
    }

    @Test
    void neverExceedsLimitUnderConcurrency() throws Exception {
        int threads = 50, attemptsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (limiter.tryAcquire("shared", rule).allowed()) allowed.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(5, allowed.get()); // clock frozen, so exactly the bucket capacity
    }
}
