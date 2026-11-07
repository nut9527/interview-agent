-- KEYS: lease, cursor; ARGV: unique token, lease milliseconds.
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
    return redis.call('GET', KEYS[2]) or ''
end
return nil
