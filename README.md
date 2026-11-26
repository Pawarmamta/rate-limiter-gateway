# Rate Limiter Gateway

A lightweight API gateway in Java / Spring Boot that protects services with
pluggable rate-limiting algorithms, exposes Prometheus metrics, and supports a
Redis-backed mode (atomic Lua script) so the limit holds across multiple instances.

## Status

- [x] **Phase 1:** In-memory token bucket + sliding window, servlet filter, metrics, unit tests
- [x] **Phase 2:** Redis-backed limiter (atomic Lua script) for multi-instance correctness
- [ ] **Phase 3:** Reverse-proxy routing to upstream services + per-route rules
- [ ] **Phase 4:** Prometheus + Grafana dashboard, load test results
- [ ] **Phase 5:** CI, architecture diagram, benchmarks

## Run

Requires JDK 17+ (Gradle is included via the wrapper).

```bash
./gradlew test
./gradlew bootRun
```

Try it (limit is 10 requests / 60 s by default):

```bash
for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/hello; done
# 200 x10, then 429 x2

curl -i -H "X-API-Key: alice" localhost:8080/api/hello   # separate bucket per API key
curl localhost:8080/actuator/prometheus | grep gateway_requests
```

Switch algorithm in `src/main/resources/application.yml`
(`token-bucket`, `sliding-window`, or `redis-token-bucket`).

## Distributed mode (Redis, no Docker needed)

With in-memory limiters, running 3 instances gives each client 3x the limit.
`redis-token-bucket` keeps counters in Redis so the limit is global.

1. Start a Redis server on `localhost:6379`
   (on Windows: the `tporadowski/redis` release zip, run `redis-server.exe`).
2. Build once: `./gradlew bootJar`
3. Start two instances in separate terminals:

```bash
java -jar build/libs/ratelimiter-gateway-0.1.0.jar --server.port=8081 --gateway.rate-limit.algorithm=redis-token-bucket
java -jar build/libs/ratelimiter-gateway-0.1.0.jar --server.port=8082 --gateway.rate-limit.algorithm=redis-token-bucket
```

4. Alternate requests between them as one user:

```powershell
1..14 | ForEach-Object { $p = if ($_ % 2) {8081} else {8082}; "port $p -> " + (curl.exe -s -o NUL -w "%{http_code}" -H "X-API-Key: alice" "localhost:$p/api/hello") }
```

Expected: ten `200`s then four `429`s, even though two servers handled the traffic.

The Redis integration tests (`RedisTokenBucketLimiterTest`) use Testcontainers and
are skipped automatically when Docker isn't available.

## Design

| Algorithm | Behavior | Trade-off |
|---|---|---|
| Token bucket | Allows bursts up to `limit`, refills steadily | O(1) memory per key |
| Sliding window log | Exact count over the last window | O(limit) memory per key |

- `RateLimiter` is an interface, so algorithms are swappable (Strategy pattern).
- Time is injected (`LongSupplier`), so tests are deterministic with no `sleep`.
- Per-key locking means different clients never contend with each other.
- Redis check-and-consume is one Lua script, so it is atomic across all instances.
- The script reads Redis's own clock (`TIME`), so clock skew between servers cannot affect limits.
- Keys carry a TTL, so Redis memory stays bounded.
- If Redis is unreachable the limiter fails **open** by default (traffic keeps flowing,
  a warning is logged); set `fail-open: false` to reject traffic instead.
- The in-memory limiters are per JVM and never evicted; use them for single-node setups.
