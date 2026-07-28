package com.cityseek.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.VoucherOrderStatusDTO;
import com.cityseek.entity.VoucherOrder;
import com.cityseek.mapper.VoucherOrderMapper;
import com.cityseek.service.ISeckillVoucherService;
import com.cityseek.service.IVoucherOrderService;
import com.cityseek.utils.RedisIdWorker;
import com.cityseek.utils.RedisStreamPendingClaimer;
import com.cityseek.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String DEAD_LETTER_STREAM = "stream.orders.dlq";
    private static final String RETRY_KEY_PREFIX = "stream.orders:retry:";
    private static final int MAX_RETRY_COUNT = 3;
    private static final int MAX_PENDING_SCAN_BATCHES = 100;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("seckill-compensate.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedisStreamPendingClaimer pendingClaimer;

    @Value("${app.redis.stream-consumer:cityseek-consumer}")
    private String consumerName;

    @Value("${app.redis.pending-min-idle:30s}")
    private Duration pendingMinIdle;

    @Value("${app.redis.pending-recovery-interval:5s}")
    private Duration pendingRecoveryInterval;

    @Value("${app.redis.pending-batch-size:20}")
    private int pendingBatchSize;


    @Value("${app.redis.dlq-max-length:10000}")
    private long dlqMaxLength;

    private volatile long nextPendingRecoveryAtNanos;

    private final ExecutorService orderExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voucher-order-consumer");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void init() {
        createConsumerGroup();
        orderExecutor.submit(this::consumeOrders);
    }

    @PreDestroy
    void shutdown() {
        orderExecutor.shutdownNow();
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null) {
            return Result.fail("优惠券不存在");
        }
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), Long.toString(orderId),
                Long.toString(now), Long.toString(orderStatusTtlSeconds())
        );
        if (result == null) {
            throw new IllegalStateException("秒杀服务暂不可用");
        }
        return switch (result.intValue()) {
            case 0 -> Result.ok(orderId);
            case 1 -> Result.fail("库存不足");
            case 2 -> Result.fail("不能重复下单");
            case 3 -> Result.fail("秒杀尚未开始");
            case 4 -> Result.fail("秒杀已经结束");
            default -> Result.fail("优惠券不存在");
        };
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单编号不合法");
        }
        Long userId = UserHolder.getUser().getId();
        Map<Object, Object> status = stringRedisTemplate.opsForHash()
                .entries(SECKILL_ORDER_STATUS_KEY + orderId);
        if (!status.isEmpty()) {
            Long statusUserId = parseLong(status.get("userId"));
            if (!userId.equals(statusUserId)) {
                return Result.fail("订单不存在");
            }
            VoucherOrderStatusDTO dto = new VoucherOrderStatusDTO(
                    orderId,
                    stringValue(status.get("status")),
                    parseLong(status.get("voucherId")),
                    parseLong(status.get("persistedOrderId")),
                    blankToNull(stringValue(status.get("error"))),
                    parseLong(status.get("updatedAt"))
            );
            return Result.ok(dto);
        }

        VoucherOrder order = query().eq("id", orderId).eq("user_id", userId).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        return Result.ok(new VoucherOrderStatusDTO(
                orderId, "SUCCESS", order.getVoucherId(), order.getId(), null, null));
    }

    private void createConsumerGroup() {
        RecordId bootstrapRecord = null;
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(STREAM_KEY))) {
                bootstrapRecord = stringRedisTemplate.opsForStream()
                        .add(STREAM_KEY, Collections.singletonMap("bootstrap", "true"));
            }
            stringRedisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
        } catch (RuntimeException exception) {
            if (!containsMessage(exception, "BUSYGROUP")) {
                throw exception;
            }
        } finally {
            if (bootstrapRecord != null) {
                stringRedisTemplate.opsForStream().delete(STREAM_KEY, bootstrapRecord);
            }
        }
    }

    private void consumeOrders() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                recoverPendingIfDue();
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, consumerName),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) {
                    continue;
                }
                processRecord(records.get(0));
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("Failed to consume voucher order", exception);
                sleepQuietly(100L);
            }
        }
    }

    private void recoverPendingIfDue() {
        long now = System.nanoTime();
        if (now < nextPendingRecoveryAtNanos) {
            return;
        }
        long intervalNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(100L), pendingRecoveryInterval.toNanos());
        nextPendingRecoveryAtNanos = now + intervalNanos;
        recoverPendingOrders();
    }

    void recoverPendingOrders() {
        String cursor = "0-0";
        for (int batchNumber = 0;
             batchNumber < MAX_PENDING_SCAN_BATCHES && !Thread.currentThread().isInterrupted();
             batchNumber++) {
            RedisStreamPendingClaimer.ClaimBatch batch = pendingClaimer.autoClaim(
                    STREAM_KEY,
                    GROUP_NAME,
                    consumerName,
                    pendingMinIdle,
                    cursor,
                    Math.max(1, pendingBatchSize)
            );
            for (MapRecord<String, Object, Object> record : batch.records()) {
                try {
                    processRecord(record);
                } catch (RuntimeException exception) {
                    log.warn("Pending voucher order remains unacknowledged: {}", record.getId(), exception);
                }
            }
            String nextCursor = batch.nextStartId();
            if ("0-0".equals(nextCursor) || nextCursor.equals(cursor)) {
                return;
            }
            cursor = nextCursor;
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        String retryKey = RETRY_KEY_PREFIX + record.getId().getValue();
        VoucherOrder order = null;
        try {
            order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
            validateOrderMessage(order);
            VoucherOrder persistedOrder = createVoucherOrder(order);
            markOrderStatusSuccess(order, persistedOrder.getId());
            acknowledge(record);
            stringRedisTemplate.delete(retryKey);
        } catch (RuntimeException exception) {
            handleProcessingFailure(record, order, retryKey, exception);
        }
    }

    private void handleProcessingFailure(
            MapRecord<String, Object, Object> record,
            VoucherOrder order,
            String retryKey,
            RuntimeException originalException) {
        Long retryCount = stringRedisTemplate.opsForValue().increment(retryKey);
        stringRedisTemplate.expire(retryKey, 10, TimeUnit.MINUTES);
        if (retryCount == null || retryCount < MAX_RETRY_COUNT) {
            throw originalException;
        }

        if (!hasCompleteIdentity(order)) {
            moveMalformedMessageToDeadLetter(record, originalException);
            acknowledge(record);
            stringRedisTemplate.delete(retryKey);
            return;
        }

        RLock compensationLock = redissonClient.getLock("lock:order:" + order.getUserId());
        if (!compensationLock.tryLock()) {
            throw new IllegalStateException(
                    "Order is still being processed by another consumer", originalException);
        }
        try {
            VoucherOrder persistedOrder = findPersistedOrder(order);
            if (persistedOrder != null) {
                markOrderStatusSuccess(order, persistedOrder.getId());
                acknowledge(record);
                stringRedisTemplate.delete(retryKey);
                return;
            }

            String error = errorSummary(originalException);
            Long compensationResult = stringRedisTemplate.execute(
                    COMPENSATE_SCRIPT,
                    Collections.emptyList(),
                    order.getVoucherId().toString(),
                    order.getUserId().toString(),
                    order.getId().toString(),
                    record.getId().getValue(),
                    error,
                    Long.toString(System.currentTimeMillis()),
                    Long.toString(orderStatusTtlSeconds()),
                    Long.toString(Math.max(1L, dlqMaxLength))
            );
            if (compensationResult == null) {
                throw new IllegalStateException("Failed to compensate voucher order", originalException);
            }
            acknowledge(record);
            stringRedisTemplate.delete(retryKey);
            log.error(
                    "Voucher order compensated and moved to dead letter stream: {}",
                    record.getId(),
                    originalException
            );
        } finally {
            if (compensationLock.isHeldByCurrentThread()) {
                compensationLock.unlock();
            }
        }
    }

    private VoucherOrder createVoucherOrder(VoucherOrder order) {
        RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("订单正在处理中");
        }
        try {
            VoucherOrder persisted = transactionTemplate.execute(status -> {
                VoucherOrder existing = findPersistedOrder(order);
                if (existing != null) {
                    return existing;
                }
                boolean stockUpdated = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", order.getVoucherId()).gt("stock", 0)
                        .update();
                if (!stockUpdated) {
                    throw new IllegalStateException("数据库库存不足");
                }
                if (!save(order)) {
                    throw new IllegalStateException("订单保存失败");
                }
                return order;
            });
            if (persisted == null) {
                throw new IllegalStateException("订单事务未返回结果");
            }
            return persisted;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private VoucherOrder findPersistedOrder(VoucherOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getId() != null) {
            VoucherOrder byId = getById(order.getId());
            if (byId != null) {
                return byId;
            }
        }
        if (order.getUserId() == null || order.getVoucherId() == null) {
            return null;
        }
        return query().eq("user_id", order.getUserId())
                .eq("voucher_id", order.getVoucherId()).one();
    }

    private void markOrderStatusSuccess(VoucherOrder order, Long persistedOrderId) {
        String key = SECKILL_ORDER_STATUS_KEY + order.getId();
        Map<String, String> fields = new HashMap<>();
        fields.put("status", "SUCCESS");
        fields.put("userId", order.getUserId().toString());
        fields.put("voucherId", order.getVoucherId().toString());
        fields.put("persistedOrderId", persistedOrderId.toString());
        fields.put("error", "");
        fields.put("updatedAt", Long.toString(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll(key, fields);
        Boolean expirationSet = stringRedisTemplate.expire(
                key, SECKILL_ORDER_STATUS_TTL, TimeUnit.DAYS);
        if (!Boolean.TRUE.equals(expirationSet)) {
            throw new IllegalStateException("订单状态过期时间设置失败");
        }
    }

    private void moveMalformedMessageToDeadLetter(
            MapRecord<String, Object, Object> record,
            RuntimeException exception) {
        Map<Object, Object> deadLetter = new HashMap<>(record.getValue());
        deadLetter.put("originalRecordId", record.getId().getValue());
        deadLetter.put("error", errorSummary(exception));
        deadLetter.put("compensated", "0");
        deadLetter.put("failedAt", Long.toString(System.currentTimeMillis()));
        stringRedisTemplate.opsForStream().add(
                DEAD_LETTER_STREAM,
                deadLetter,
                RedisStreamCommands.XAddOptions.maxlen(Math.max(1L, dlqMaxLength))
                        .approximateTrimming(true)
        );
    }

    private void validateOrderMessage(VoucherOrder order) {
        if (!hasCompleteIdentity(order)) {
            throw new IllegalArgumentException("订单消息字段不完整");
        }
    }

    private boolean hasCompleteIdentity(VoucherOrder order) {
        return order != null && order.getId() != null
                && order.getUserId() != null && order.getVoucherId() != null;
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
    }

    private long orderStatusTtlSeconds() {
        return TimeUnit.DAYS.toSeconds(SECKILL_ORDER_STATUS_TTL);
    }

    private String errorSummary(Throwable throwable) {
        String message = throwable.getMessage();
        String summary = throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
        return summary.length() <= 255 ? summary : summary.substring(0, 255);
    }

    private Long parseLong(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean containsMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
