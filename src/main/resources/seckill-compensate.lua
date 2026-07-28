local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local originalRecordId = ARGV[4]
local errorMessage = ARGV[5]
local now = ARGV[6]
local statusTtl = tonumber(ARGV[7])
local dlqMaxLength = tonumber(ARGV[8])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local statusKey = 'seckill:order:status:' .. orderId

local currentStatus = redis.call('hget', statusKey, 'status')
if currentStatus == 'SUCCESS' then
    return 2
end
if currentStatus == 'FAILED' then
    return 0
end

local reservationRemoved = redis.call('srem', orderKey, userId)
if reservationRemoved == 1 then
    redis.call('incrby', stockKey, 1)
end

redis.call(
        'hset',
        statusKey,
        'status', 'FAILED',
        'userId', userId,
        'voucherId', voucherId,
        'persistedOrderId', '',
        'error', errorMessage,
        'compensated', tostring(reservationRemoved),
        'updatedAt', now
)
redis.call('expire', statusKey, statusTtl)
redis.call(
        'xadd',
        'stream.orders.dlq',
        'maxlen',
        '~',
        dlqMaxLength,
        '*',
        'originalRecordId', originalRecordId,
        'orderId', orderId,
        'userId', userId,
        'voucherId', voucherId,
        'error', errorMessage,
        'compensated', tostring(reservationRemoved),
        'failedAt', now
)
return 1
