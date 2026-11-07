-- KEYS: leases (sorted set), circuit (hash). ARGV: token, capacity, lease millis.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
local generation = tonumber(redis.call('HGET', KEYS[2], 'generation') or '0')
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return {0, generation} end
local openUntil = tonumber(redis.call('HGET', KEYS[2], 'open_until') or '0')
if openUntil > 0 then
    local probe = redis.call('HGET', KEYS[2], 'probe')
    if now < openUntil or (probe and redis.call('ZSCORE', KEYS[1], probe)) then
        return {-1, generation}
    end
    -- 每次新探测都推进代次：超时/崩溃探测的迟到结果不能影响接替者。
    generation = redis.call('HINCRBY', KEYS[2], 'generation', 1)
    redis.call('HSET', KEYS[2], 'probe', ARGV[1])
end
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[3]), ARGV[1])
local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
redis.call('PEXPIRE', KEYS[1], math.max(1, tonumber(last[2]) - now))
return {1, generation}
