redis.call(
        'hset',
        KEYS[1],
        'status', 'SUCCESS',
        'userId', ARGV[1],
        'voucherId', ARGV[2],
        'persistedOrderId', ARGV[3],
        'error', '',
        'updatedAt', ARGV[4]
)
return redis.call('expire', KEYS[1], tonumber(ARGV[5]))
