package com.example.gateway.web;

import com.example.gateway.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A password-protected endpoint. Because the path starts with /api/, the
 * RateLimitFilter runs BEFORE this code: every request (genuine or fake) is
 * counted first, so password guessing is throttled.
 */
@RestController
public class SecureController {

    private static final Logger log = LoggerFactory.getLogger(SecureController.class);

    private final byte[] expectedPassword;

    public SecureController(AuthProperties auth) {
        this.expectedPassword = auth.password().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/secure")
    public ResponseEntity<Map<String, Object>> secure(@RequestBody Map<String, String> request,
                                                      HttpServletRequest http) {
        String message = request.getOrDefault("message", "");
        String password = request.getOrDefault("password", "");

        // Constant-time comparison: does not leak how many characters matched.
        boolean correct = MessageDigest.isEqual(expectedPassword, password.getBytes(StandardCharsets.UTF_8));

        if (!correct) {
            log.warn("Wrong password from client '{}' ({})", http.getHeader("X-API-Key"), http.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "wrong_password"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "access_granted");
        body.put("youSaid", message);
        body.put("reply", message.equalsIgnoreCase("hello")
                ? "Hello! You are authenticated."
                : "Message received.");
        body.put("time", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}