-- KEYS: leases, circuit. ARGV: token, generation, success, threshold, cooldown millis.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local expiry = redis.call('ZSCORE', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[1], ARGV[1])
if not expiry or tonumber(expiry) <= now then return 0 end
local generation = tonumber(redis.call('HGET', KEYS[2], 'generation') or '0')
if generation ~= tonumber(ARGV[2]) then return 0 end
local probe = redis.call('HGET', KEYS[2], 'probe') == ARGV[1]
if ARGV[3] == '1' then
    redis.call('HSET', KEYS[2], 'failures', 0)
    if probe then
        redis.call('HSET', KEYS[2], 'open_until', 0)
        redis.call('HDEL', KEYS[2], 'probe')
        redis.call('HINCRBY', KEYS[2], 'generation', 1)
    end
else
    local failures = redis.call('HINCRBY', KEYS[2], 'failures', 1)
    if probe or failures >= tonumber(ARGV[4]) then
        redis.call('HSET', KEYS[2], 'open_until', now + tonumber(ARGV[5]))
        redis.call('HDEL', KEYS[2], 'probe')
        redis.call('HINCRBY', KEYS[2], 'generation', 1)
    end
end
return 1
