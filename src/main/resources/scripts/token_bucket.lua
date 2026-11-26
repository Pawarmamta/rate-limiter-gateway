-- Atomic token bucket. Redis runs a script as one uninterrupted unit, so
-- concurrent requests from any number of gateway instances cannot interleave.
--
-- KEYS[1] = bucket key
-- ARGV[1] = limit (bucket capacity, tokens per window)
-- ARGV[2] = window in milliseconds
--
-- Returns { allowed (1/0), remaining tokens, retryAfterMillis }

local key       = KEYS[1]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

-- Use Redis's clock, not the app server's, so clock skew between gateway
-- instances can never corrupt the refill calculation.
local t   = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

local refill_per_ms = limit / window_ms

local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
    tokens = limit
    ts     = now
end

local elapsed = math.max(0, now - ts)
tokens = math.min(limit, tokens + elapsed * refill_per_ms)

local allowed = 0
local retry   = 0
if tokens >= 1 then
    tokens  = tokens - 1
    allowed = 1
else
    retry = math.ceil((1 - tokens) / refill_per_ms)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
-- After one full window an idle bucket is full again, so it is equivalent to
-- "no key". Expiring it keeps Redis memory bounded (fixes the Phase 1 leak).
redis.call('PEXPIRE', key, window_ms)

return { allowed, math.floor(tokens), retry }
