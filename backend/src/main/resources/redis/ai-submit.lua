local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
if not limit or not window or limit < 1 or window < 1
   or limit ~= math.floor(limit) or window ~= math.floor(window) then
    return redis.error_reply('INVALID_RATE_CONFIG')
end
local raw = redis.call('GET', KEYS[1])
if raw then
    local count = tonumber(raw)
    if not count or count < 0 or count ~= math.floor(count) then
        return redis.error_reply('INVALID_RATE_STATE')
    end
    local remaining = redis.call('PTTL', KEYS[1])
    if remaining == -1 then
        redis.call('PEXPIRE', KEYS[1], window)
        remaining = window
    end
    if count >= limit then return {0, remaining} end
end
redis.call('INCR', KEYS[1])
if not raw then redis.call('PEXPIRE', KEYS[1], window) end
return {1, redis.call('PTTL', KEYS[1])}
