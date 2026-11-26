package com.example.gateway.config;

import com.example.gateway.limiter.RateLimitRule;
import com.example.gateway.limiter.RateLimiter;
import com.example.gateway.limiter.RedisTokenBucketLimiter;
import com.example.gateway.limiter.SlidingWindowLimiter;
import com.example.gateway.limiter.TokenBucketLimiter;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class LimiterConfig {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties props, ObjectProvider<StringRedisTemplate> redis) {
        return switch (props.algorithm()) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(System::currentTimeMillis);
            case SLIDING_WINDOW -> new SlidingWindowLimiter(System::currentTimeMillis);
            case REDIS_TOKEN_BUCKET -> new RedisTokenBucketLimiter(redis.getObject(), props.failOpen());
        };
    }

    @Bean
    public RateLimitRule defaultRule(RateLimitProperties props) {
        return new RateLimitRule(props.limit(), Duration.ofSeconds(props.windowSeconds()));
    }
}
