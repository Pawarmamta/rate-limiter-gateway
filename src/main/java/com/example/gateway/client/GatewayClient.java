package com.example.gateway.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Scanner;

/**
 * Interactive terminal client for testing the gateway.
 *
 * Type a message (for example "hello") and it asks for the password, sends one
 * request and prints what happened. "/burst 20" sends 20 requests very fast, a
 * mix of genuine (correct password) and fake (wrong password) ones.
 */
public class GatewayClient {

    private static final String BASE_URL = System.getProperty("gateway.url", "http://localhost:8080");
    private static final String[] FAKE_PASSWORDS = {"123456", "password", "admin", "qwerty", "letmein", "hunter2"};
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static String user = "terminal-user";

    private record Result(int status, String remaining, String retryAfter, String body) { }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.println("=== Rate Limiter Test Client ===");
        System.out.println("Server : " + BASE_URL);
        System.out.println("Type a message (for example: hello). You will then be asked for the password.");
        System.out.println("Commands: /burst <n>   /user <name>   /help   /exit");
        System.out.println();

        while (true) {
            System.out.print("[" + user + "] message> ");
            if (!in.hasNextLine()) break;
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;

            String lower = line.toLowerCase();
            if (lower.equals("/exit") || lower.equals("/quit")) break;
            if (lower.equals("/help")) { printHelp(); continue; }
            if (lower.startsWith("/user")) { changeUser(line); continue; }
            if (lower.startsWith("/burst")) { burst(line, in); continue; }
            sendOne(line, in);
        }
        System.out.println("Bye.");
    }

    // ---------- single request ----------

    private static void sendOne(String message, Scanner in) {
        System.out.print("password> ");
        String password = in.hasNextLine() ? in.nextLine() : "";
        try {
            Result r = send(message, password);
            System.out.println("  " + tag(r.status()));
            System.out.println("  server  : " + r.body());
            if (r.status() == 429) {
                System.out.println("  retry   : try again in " + r.retryAfter() + " seconds");
            } else {
                System.out.println("  left    : " + r.remaining() + " request(s) remaining in this window");
            }
        } catch (IOException | InterruptedException e) {
            unreachable(e);
        }
        System.out.println();
    }

    // ---------- many requests, some genuine and some fake ----------

    private static void burst(String line, Scanner in) {
        int n = 20;
        String[] parts = line.split("\\s+");
        if (parts.length > 1) {
            try {
                n = Math.max(1, Math.min(200, Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                System.out.println("  Usage: /burst <number>   example: /burst 20\n");
                return;
            }
        }
        System.out.print("Enter the GENUINE password (used for every 3rd request, the rest are fake): ");
        String realPassword = in.hasNextLine() ? in.nextLine() : "";

        System.out.println();
        System.out.println("Sending " + n + " requests as user '" + user + "' as fast as possible...");
        System.out.println();

        int accepted = 0, wrong = 0, blockedGenuine = 0, blockedFake = 0;
        try {
            for (int i = 1; i <= n; i++) {
                boolean genuine = i % 3 == 0;
                String password = genuine ? realPassword : FAKE_PASSWORDS[i % FAKE_PASSWORDS.length];
                Result r = send("hello", password);

                String detail = r.status() == 429 ? "retry in " + r.retryAfter() + "s" : "left=" + r.remaining();
                System.out.printf("#%02d  %-7s -> %-21s %s%n",
                        i, genuine ? "GENUINE" : "FAKE", shortTag(r.status()), detail);

                if (r.status() == 200) accepted++;
                else if (r.status() == 401) wrong++;
                else if (r.status() == 429) { if (genuine) blockedGenuine++; else blockedFake++; }
            }
        } catch (IOException | InterruptedException e) {
            unreachable(e);
            return;
        }

        int blocked = blockedGenuine + blockedFake;
        System.out.println();
        System.out.println("-------------------- SUMMARY --------------------");
        System.out.printf("Requests sent               : %d%n", n);
        System.out.printf("Passed the rate limiter     : %d  (%d genuine accepted, %d fake rejected: wrong password)%n",
                accepted + wrong, accepted, wrong);
        System.out.printf("Blocked by the rate limiter : %d  (%d genuine, %d fake)%n",
                blocked, blockedGenuine, blockedFake);
        System.out.println("-------------------------------------------------");
        System.out.println();
    }

    // ---------- HTTP ----------

    private static Result send(String message, String password) throws IOException, InterruptedException {
        String json = "{\"message\":\"" + escape(message) + "\",\"password\":\"" + escape(password) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/secure"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-API-Key", user)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(
                response.statusCode(),
                response.headers().firstValue("X-RateLimit-Remaining").orElse("-"),
                response.headers().firstValue("Retry-After").orElse("-"),
                response.body().trim());
    }

    // ---------- helpers ----------

    private static String tag(int status) {
        return switch (status) {
            case 200 -> "[ALLOWED]        200  password correct, access granted";
            case 401 -> "[WRONG PASSWORD] 401  reached the server, but the password is wrong (fake request)";
            case 429 -> "[BLOCKED]        429  rate limit exceeded, the gateway refused this request";
            default -> "[UNEXPECTED]     " + status;
        };
    }

    private static String shortTag(int status) {
        return switch (status) {
            case 200 -> "[ALLOWED] 200";
            case 401 -> "[WRONG PASSWORD] 401";
            case 429 -> "[BLOCKED] 429";
            default -> "[UNEXPECTED] " + status;
        };
    }

    private static void changeUser(String line) {
        String[] parts = line.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            System.out.println("  Current user: " + user + "   (change with: /user bob)\n");
            return;
        }
        user = parts[1].trim();
        System.out.println("  Now sending as user '" + user + "' (each user has a separate limit).\n");
    }

    private static void printHelp() {
        System.out.println("  <any text>    send it as a message, then enter a password");
        System.out.println("  /burst <n>    send n requests quickly (every 3rd genuine, the rest fake)");
        System.out.println("  /user <name>  act as a different client (separate rate limit)");
        System.out.println("  /exit         quit");
        System.out.println();
    }

    private static void unreachable(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        System.out.println("  Cannot reach the gateway at " + BASE_URL);
        System.out.println("  Is it running?  Start it in another terminal with: ./gradlew bootRun");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}