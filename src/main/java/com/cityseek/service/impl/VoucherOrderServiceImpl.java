package com.cityseek.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.VoucherOrderStatusDTO;
import com.cityseek.entity.VoucherOrder;
import com.cityseek.mapper.VoucherOrderMapper;
import com.cityseek.metrics.VoucherOrderMetrics;
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
import org.springframework.dao.DuplicateKeyException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final DefaultRedisScript<Long> STATUS_SUCCESS_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("seckill-compensate.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);

        STATUS_SUCCESS_SCRIPT = new DefaultRedisScript<>();
        STATUS_SUCCESS_SCRIPT.setLocation(new ClassPathResource("seckill-status-success.lua"));
        STATUS_SUCCESS_SCRIPT.setResultType(Long.class);
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

    @Resource
    private VoucherOrderMetrics metrics;

    @Value("${app.redis.stream-consumer:cityseek-consumer}")
    private String consumerName = "cityseek-consumer";

    @Value("${app.redis.stream-consumer-concurrency:4}")
    private int streamConsumerConcurrency = 4;

    @Value("${app.redis.stream-read-batch-size:16}")
    private int streamReadBatchSize = 16;

    @Value("${app.redis.pending-min-idle:30s}")
    private Duration pendingMinIdle = Duration.ofSeconds(30);

    @Value("${app.redis.pending-recovery-interval:5s}")
    private Duration pendingRecoveryInterval = Duration.ofSeconds(5);

    @Value("${app.redis.pending-batch-size:20}")
    private int pendingBatchSize = 20;

    @Value("${app.redis.dlq-max-length:10000}")
    private long dlqMaxLength = 10000L;

    private final AtomicLong nextPendingRecoveryAtNanos = new AtomicLong();

    private ExecutorService orderExecutor;

    @PostConstruct
    void init() {
        createConsumerGroup();
        int workerCount = Math.max(1, streamConsumerConcurrency);
        AtomicInteger threadSequence = new AtomicInteger(1);
        orderExecutor = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task, "voucher-order-consumer-" + threadSequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        for (int index = 0; index < workerCount; index++) {
            String workerName = workerConsumerName(index, workerCount);
            orderExecutor.submit(() -> consumeOrders(workerName));
        }
    }

    @PreDestroy
    void shutdown() {
        if (orderExecutor != null) {
            orderExecutor.shutdownNow();
        }
    }

    String workerConsumerName(int index, int workerCount) {
        return workerCount <= 1 ? consumerName : consumerName + "-" + (index + 1);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        metrics.request();
        if (voucherId == null) {
            metrics.rejected("not_found");
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
            case 0 -> {
                metrics.accepted();
                yield Result.ok(orderId);
            }
            case 1 -> rejected("out_of_stock", "库存不足");
            case 2 -> rejected("duplicate", "不能重复下单");
            case 3 -> rejected("not_started", "秒杀尚未开始");
            case 4 -> rejected("ended", "秒杀已经结束");
            default -> rejected("not_found", "优惠券不存在");
        };
    }

    private Result rejected(String reason, String message) {
        metrics.rejected(reason);
        return Result.fail(message);
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

    private void consumeOrders(String workerConsumerName) {
        int batchSize = Math.max(1, streamReadBatchSize);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                recoverPendingIfDue(workerConsumerName);
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, workerConsumerName),
                        StreamReadOptions.empty().count(batchSize).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) {
                    continue;
                }
                consumeBatch(records, workerConsumerName);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("Failed to read voucher order stream on consumer {}", workerConsumerName, exception);
                sleepQuietly(100L);
            }
        }
    }

    void consumeBatch(List<MapRecord<String, Object, Object>> records, String workerConsumerName) {
        for (MapRecord<String, Object, Object> record : records) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                processRecord(record, false);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.error("Failed to consume voucher order {} on consumer {}",
                        record.getId(), workerConsumerName, exception);
            }
        }
    }

    boolean recoverPendingIfDue(String claimantConsumer) {
        long now = System.nanoTime();
        long dueAt = nextPendingRecoveryAtNanos.get();
        if (now < dueAt) {
            return false;
        }
        long intervalNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(100L), pendingRecoveryInterval.toNanos());
        if (!nextPendingRecoveryAtNanos.compareAndSet(dueAt, now + intervalNanos)) {
            return false;
        }
        recoverPendingOrders(claimantConsumer);
        return true;
    }

    private void recoverPendingOrders(String claimantConsumer) {
        String cursor = "0-0";
        for (int batchNumber = 0;
             batchNumber < MAX_PENDING_SCAN_BATCHES && !Thread.currentThread().isInterrupted();
             batchNumber++) {
            RedisStreamPendingClaimer.ClaimBatch batch = pendingClaimer.autoClaim(
                    STREAM_KEY,
                    GROUP_NAME,
                    claimantConsumer,
                    pendingMinIdle,
                    cursor,
                    Math.max(1, pendingBatchSize)
            );
            for (MapRecord<String, Object, Object> record : batch.records()) {
                try {
                    processRecord(record, true);
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

    void processRecord(MapRecord<String, Object, Object> record, boolean isRedelivery) {
        var sample = metrics.startProcessing();
        String retryKey = RETRY_KEY_PREFIX + record.getId().getValue();
        VoucherOrder order = null;
        try {
            order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
            validateOrderMessage(order);
            VoucherOrder persistedOrder = createVoucherOrder(order, isRedelivery);
            markOrderStatusSuccess(order, persistedOrder.getId());
            acknowledge(record);
            if (isRedelivery) {
                stringRedisTemplate.delete(retryKey);
            }
        } catch (RuntimeException exception) {
            handleProcessingFailure(record, order, retryKey, exception);
        } finally {
            metrics.stopProcessing(sample);
        }
    }
    private void handleProcessingFailure(
            MapRecord<String, Object, Object> record,
            VoucherOrder order,
            String retryKey,
            RuntimeException originalException) {
        metrics.retry();
        Long retryCount = stringRedisTemplate.opsForValue().increment(retryKey);
        stringRedisTemplate.expire(retryKey, 10, TimeUnit.MINUTES);
        if (retryCount == null || retryCount < MAX_RETRY_COUNT) {
            throw originalException;
        }

        if (!hasCompleteIdentity(order)) {
            moveMalformedMessageToDeadLetter(record, originalException);
            metrics.deadLetter();
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
            metrics.compensated();
            metrics.deadLetter();
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

    private VoucherOrder createVoucherOrder(VoucherOrder order, boolean isRedelivery) {
        RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("订单正在处理中");
        }
        try {
            boolean[] created = {false};
            VoucherOrder persisted;
            try {
                persisted = transactionTemplate.execute(status -> {
                    if (isRedelivery) {
                        VoucherOrder existing = findPersistedOrder(order);
                        if (existing != null) {
                            return existing;
                        }
                    }
                    boolean stockUpdated = seckillVoucherService.update()
                            .setSql("stock = stock - 1")
                            .eq("voucher_id", order.getVoucherId()).gt("stock", 0)
                            .update();
                    if (!stockUpdated) {
                        VoucherOrder existing = findPersistedOrder(order);
                        if (existing != null) {
                            return existing;
                        }
                        throw new IllegalStateException("数据库库存不足");
                    }
                    if (!save(order)) {
                        throw new IllegalStateException("订单保存失败");
                    }
                    created[0] = true;
                    return order;
                });
            } catch (DuplicateKeyException duplicateKeyException) {
                VoucherOrder existing = findPersistedOrder(order);
                if (existing == null) {
                    throw duplicateKeyException;
                }
                persisted = existing;
            }
            if (persisted == null) {
                throw new IllegalStateException("订单事务未返回结果");
            }
            if (created[0]) {
                metrics.persisted();
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
        Long expirationSet = stringRedisTemplate.execute(
                STATUS_SUCCESS_SCRIPT,
                Collections.singletonList(key),
                order.getUserId().toString(),
                order.getVoucherId().toString(),
                persistedOrderId.toString(),
                Long.toString(System.currentTimeMillis()),
                Long.toString(orderStatusTtlSeconds())
        );
        if (!Long.valueOf(1L).equals(expirationSet)) {
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
