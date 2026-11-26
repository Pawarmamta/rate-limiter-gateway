package com.example.gateway.web;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final String instance = resolveInstanceName();

    /** GET /api/hello?msg=hi  -> echoes the message (msg is optional). */
    @GetMapping("/api/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "") String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Hello from behind the rate limiter");
        if (!msg.isBlank()) {
            body.put("youSaid", msg);
        }
        body.put("instance", instance);
        body.put("time", Instant.now().toString());
        return body;
    }

    /** POST /api/message with JSON {"message":"hi"}  -> echoes it back. */
    @PostMapping("/api/message")
    public ResponseEntity<Map<String, Object>> message(@RequestBody Map<String, String> request) {
        String text = request.get("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'message' is required"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("youSaid", text);
        body.put("length", text.length());
        body.put("instance", instance);
        body.put("time", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    private static String resolveInstanceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}