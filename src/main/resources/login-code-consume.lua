local cachedCode = redis.call('get', KEYS[1])
if cachedCode == false or cachedCode ~= ARGV[1] then
    return 0
end

redis.call('del', KEYS[1])
return 1
