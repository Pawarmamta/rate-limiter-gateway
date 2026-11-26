package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("token-bucket") Algorithm algorithm,
        @DefaultValue("10") int limit,
        @DefaultValue("60") int windowSeconds,
        @DefaultValue("true") boolean failOpen) {

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW, REDIS_TOKEN_BUCKET }
}
