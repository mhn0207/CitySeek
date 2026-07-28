local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local now = tonumber(ARGV[4])
local statusTtl = tonumber(ARGV[5])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local beginKey = 'seckill:begin:' .. voucherId
local endKey = 'seckill:end:' .. voucherId
local statusKey = 'seckill:order:status:' .. orderId

local stock = tonumber(redis.call('get', stockKey))
if (stock == nil) then
    return 5
end

local beginTime = tonumber(redis.call('get', beginKey))
local endTime = tonumber(redis.call('get', endKey))
if (beginTime ~= nil and now < beginTime) then
    return 3
end
if (endTime ~= nil and now > endTime) then
    return 4
end

if (stock <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call(
        'hset',
        statusKey,
        'status', 'PENDING',
        'userId', userId,
        'voucherId', voucherId,
        'persistedOrderId', '',
        'error', '',
        'updatedAt', tostring(now)
)
redis.call('expire', statusKey, statusTtl)

redis.call(
        'xadd',
        'stream.orders',
        '*',
        'userId', userId,
        'voucherId', voucherId,
        'id', orderId
)
return 0
