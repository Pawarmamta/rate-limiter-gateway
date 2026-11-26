package com.example.gateway.web;

import com.example.gateway.config.RateLimitProperties;
import com.example.gateway.limiter.RateLimitResult;
import com.example.gateway.limiter.RateLimitRule;
import com.example.gateway.limiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before every /api/** request and rejects callers who exceed their limit. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitRule rule;
    private final RateLimitProperties props;
    private final MeterRegistry metrics;

    public RateLimitFilter(RateLimiter limiter, RateLimitRule rule,
                           RateLimitProperties props, MeterRegistry metrics) {
        this.limiter = limiter;
        this.rule = rule;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = resolveClientKey(request);
        RateLimitResult result = limiter.tryAcquire(key, rule);

        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            metrics.counter("gateway.requests", "result", "allowed").increment();
            chain.doFilter(request, response);
            return;
        }

        metrics.counter("gateway.requests", "result", "blocked").increment();
        long retryAfterSeconds = (long) Math.ceil(result.retryAfterMillis() / 1000.0);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    /** Prefer an API key; fall back to client IP. */
    private String resolveClientKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        return (apiKey != null && !apiKey.isBlank()) ? "key:" + apiKey : "ip:" + request.getRemoteAddr();
    }
}
