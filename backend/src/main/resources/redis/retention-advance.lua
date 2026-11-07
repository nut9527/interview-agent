-- A failed/expired page is replayed by the next owner; only a completed page advances.
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('SET', KEYS[2], ARGV[2])
return 1
