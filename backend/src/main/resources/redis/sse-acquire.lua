local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
for _, key in ipairs(KEYS) do
    redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2])
    or redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then
    return 0
end
for _, key in ipairs(KEYS) do
    redis.call('ZADD', key, now + tonumber(ARGV[4]), ARGV[1])
    redis.call('PEXPIRE', key, ARGV[4])
end
return 1
