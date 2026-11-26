package com.example.gateway.limiter;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Token bucket whose state lives in Redis, so every gateway instance shares the
 * same counters. The check-and-consume runs as a single Lua script, which Redis
 * executes atomically.
 */
public class RedisTokenBucketLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketLimiter.class);
    private static final String KEY_PREFIX = "rl:tb:";

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final boolean failOpen;

    /**
     * @param failOpen if Redis is unreachable: true = let requests through (availability
     *                 over protection), false = reject them (protection over availability)
     */
    public RedisTokenBucketLimiter(StringRedisTemplate redis, boolean failOpen) {
        this.redis = redis;
        this.failOpen = failOpen;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        s.setResultType(List.class);
        this.script = s;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        try {
            List<?> result = redis.execute(
                    script,
                    List.of(KEY_PREFIX + key),
                    String.valueOf(rule.limit()),
                    String.valueOf(rule.window().toMillis()));

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Unexpected script result: " + result);
            }
            boolean allowed = ((Number) result.get(0)).longValue() == 1L;
            long remaining = ((Number) result.get(1)).longValue();
            long retryAfterMillis = ((Number) result.get(2)).longValue();

            return allowed
                    ? RateLimitResult.allowed(rule.limit(), remaining)
                    : RateLimitResult.denied(rule.limit(), retryAfterMillis);
        } catch (RuntimeException e) {
            log.warn("Redis rate-limit check failed for key '{}' ({}); failing {}",
                    key, e.getClass().getSimpleName(), failOpen ? "OPEN" : "CLOSED");
            return failOpen
                    ? RateLimitResult.allowed(rule.limit(), rule.limit())
                    : RateLimitResult.denied(rule.limit(), 1000);
        }
    }
}
