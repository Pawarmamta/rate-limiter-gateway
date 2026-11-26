package com.example.gateway.limiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs against a real Redis in Docker. Skipped automatically if Docker isn't available. */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StringRedisTemplate template;

    // Long window => refill is negligible during a test, so counts are exact.
    private final RateLimitRule rule = new RateLimitRule(5, Duration.ofHours(1));

    @BeforeAll
    static void connect() {
        template = templateFor(redis.getHost(), redis.getMappedPort(6379));
    }

    private static StringRedisTemplate templateFor(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(factory);
        t.afterPropertiesSet();
        return t;
    }

    private static String uniqueKey() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter limiter = new RedisTokenBucketLimiter(template, true);
        String key = uniqueKey();

        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire(key, rule).allowed());
        RateLimitResult blocked = limiter.tryAcquire(key, rule);

        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterMillis() > 0);
    }

    @Test
    void twoGatewayInstancesShareOneLimit() {
        // Two separate limiter objects = two gateway servers talking to the same Redis.
        RateLimiter gatewayA = new RedisTokenBucketLimiter(template, true);
        RateLimiter gatewayB = new RedisTokenBucketLimiter(template, true);
        String key = uniqueKey();

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            RateLimiter g = (i % 2 == 0) ? gatewayA : gatewayB; // round-robin like a load balancer
            if (g.tryAcquire(key, rule).allowed()) allowed++;
        }
        assertEquals(5, allowed); // not 10: the limit is global, not per instance
    }

    @Test
    void neverExceedsLimitUnderConcurrencyAcrossInstances() throws Exception {
        RateLimitRule bigRule = new RateLimitRule(50, Duration.ofHours(1));
        RateLimiter gatewayA = new RedisTokenBucketLimiter(template, true);
        RateLimiter gatewayB = new RedisTokenBucketLimiter(template, true);
        String key = uniqueKey();

        int threads = 40, attempts = 25; // 1000 attempts competing for 50 slots
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            RateLimiter g = (t % 2 == 0) ? gatewayA : gatewayB;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { }
                for (int i = 0; i < attempts; i++) {
                    if (g.tryAcquire(key, bigRule).allowed()) allowed.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(50, allowed.get());
    }

    @Test
    void keysExpireSoRedisMemoryStaysBounded() {
        RateLimiter limiter = new RedisTokenBucketLimiter(template, true);
        String key = uniqueKey();
        limiter.tryAcquire(key, rule);

        Long ttlMillis = template.getExpire("rl:tb:" + key, TimeUnit.MILLISECONDS);
        assertNotNull(ttlMillis);
        assertTrue(ttlMillis > 0 && ttlMillis <= Duration.ofHours(1).toMillis());
    }

    @Test
    void failOpenAllowsTrafficWhenRedisIsDown() {
        StringRedisTemplate dead = templateFor("localhost", 1); // nothing listens on port 1
        RateLimitResult result = new RedisTokenBucketLimiter(dead, true).tryAcquire("k", rule);
        assertTrue(result.allowed());
    }

    @Test
    void failClosedRejectsTrafficWhenRedisIsDown() {
        StringRedisTemplate dead = templateFor("localhost", 1);
        RateLimitResult result = new RedisTokenBucketLimiter(dead, false).tryAcquire("k", rule);
        assertFalse(result.allowed());
    }
}
