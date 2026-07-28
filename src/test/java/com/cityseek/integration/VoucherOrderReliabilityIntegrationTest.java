package com.cityseek.integration;

import com.cityseek.dto.Result;
import com.cityseek.dto.UserDTO;
import com.cityseek.dto.VoucherOrderStatusDTO;
import com.cityseek.service.IVoucherOrderService;
import com.cityseek.utils.RedisStreamPendingClaimer;
import com.cityseek.utils.UserHolder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cityseek.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_END_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VoucherOrderReliabilityIntegrationTest extends ContainerIntegrationTest {

    private static final String ORDER_STREAM = "stream.orders";

    @Autowired
    private RedisStreamPendingClaimer pendingClaimer;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void autoClaimsMessageFromDeadConsumer() throws Exception {
        String stream = "test.auto-claim";
        String group = "test-group";
        redisTemplate.delete(stream);
        redisTemplate.opsForStream().add(stream, Map.of("id", "1", "value", "pending"));
        redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);

        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(group, "dead-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );
        assertNotNull(delivered);
        assertEquals(1, delivered.size());

        TimeUnit.MILLISECONDS.sleep(150L);
        RedisStreamPendingClaimer.ClaimBatch claimed = pendingClaimer.autoClaim(
                stream, group, "rescuer", Duration.ofMillis(100), "0-0", 10);

        assertEquals(1, claimed.records().size());
        assertEquals(delivered.get(0).getId(), claimed.records().get(0).getId());
        redisTemplate.opsForStream().acknowledge(stream, group, claimed.records().get(0).getId());
        redisTemplate.delete(stream);
    }

    @Test
    @Order(2)
    void concurrentSeckillNeverOversellsAndPersistsAcceptedOrders() throws Exception {
        long voucherId = 9001L;
        int stock = 20;
        int requestCount = 40;
        seedVoucher(voucherId, stock);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderAttempt>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                long userId = 10_000L + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    UserHolder.saveUser(user);
                    try {
                        return new OrderAttempt(userId, voucherOrderService.seckillVoucher(voucherId));
                    } finally {
                        UserHolder.removeUser();
                    }
                }));
            }
            start.countDown();

            List<OrderAttempt> attempts = new ArrayList<>();
            for (Future<OrderAttempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }
            List<OrderAttempt> accepted = attempts.stream()
                    .filter(attempt -> Boolean.TRUE.equals(attempt.result().getSuccess()))
                    .toList();
            assertEquals(stock, accepted.size());

            awaitCondition(Duration.ofSeconds(20), () -> orderCount(voucherId) == stock);
            awaitCondition(Duration.ofSeconds(10), () -> accepted.stream().allMatch(attempt -> {
                long orderId = ((Number) attempt.result().getData()).longValue();
                return "SUCCESS".equals(redisTemplate.opsForHash()
                        .get(SECKILL_ORDER_STATUS_KEY + orderId, "status"));
            }));

            assertEquals(stock, orderCount(voucherId));
            assertEquals(0, databaseStock(voucherId));
            assertEquals("0", redisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
            assertEquals(stock, redisTemplate.opsForSet().size(SECKILL_ORDER_KEY + voucherId));

            OrderAttempt firstAccepted = accepted.get(0);
            long acceptedOrderId = ((Number) firstAccepted.result().getData()).longValue();
            UserDTO owner = new UserDTO();
            owner.setId(firstAccepted.userId());
            UserHolder.saveUser(owner);
            try {
                Result statusResult = voucherOrderService.queryOrderStatus(acceptedOrderId);
                assertTrue(Boolean.TRUE.equals(statusResult.getSuccess()));
                assertTrue(statusResult.getData() instanceof VoucherOrderStatusDTO);
                VoucherOrderStatusDTO status = (VoucherOrderStatusDTO) statusResult.getData();
                assertEquals("SUCCESS", status.getStatus());
                assertEquals(acceptedOrderId, status.getOrderId());
                assertEquals(voucherId, status.getVoucherId());
            } finally {
                UserHolder.removeUser();
            }

            UserDTO stranger = new UserDTO();
            stranger.setId(99_999L);
            UserHolder.saveUser(stranger);
            try {
                Result statusResult = voucherOrderService.queryOrderStatus(acceptedOrderId);
                assertFalse(Boolean.TRUE.equals(statusResult.getSuccess()));
            } finally {
                UserHolder.removeUser();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(3)
    void failedOrderIsCompensatedAndMovedToDeadLetterStream() {
        long voucherId = 9902L;
        long userId = 19_902L;
        long orderId = 9_902_001L;
        String statusKey = SECKILL_ORDER_STATUS_KEY + orderId;

        redisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "4");
        redisTemplate.opsForSet().add(SECKILL_ORDER_KEY + voucherId, Long.toString(userId));
        redisTemplate.opsForHash().putAll(statusKey, Map.of(
                "status", "PENDING",
                "userId", Long.toString(userId),
                "voucherId", Long.toString(voucherId),
                "persistedOrderId", "",
                "error", "",
                "updatedAt", Long.toString(System.currentTimeMillis())
        ));
        redisTemplate.opsForStream().add(ORDER_STREAM, Map.of(
                "id", Long.toString(orderId),
                "userId", Long.toString(userId),
                "voucherId", Long.toString(voucherId)
        ));

        awaitCondition(Duration.ofSeconds(15), () -> "FAILED".equals(
                redisTemplate.opsForHash().get(statusKey, "status")));

        assertEquals("5", redisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
        assertFalse(Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember(SECKILL_ORDER_KEY + voucherId, Long.toString(userId))));
        assertEquals(0, orderCount(voucherId));
        Long deadLetterSize = redisTemplate.opsForStream().size("stream.orders.dlq");
        assertNotNull(deadLetterSize);
        assertTrue(deadLetterSize > 0);
    }

    private void seedVoucher(long voucherId, int stock) {
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
        jdbcTemplate.update(
                "INSERT INTO tb_voucher "
                        + "(id, shop_id, title, pay_value, actual_value, type, status) "
                        + "VALUES (?, 1, ?, 100, 200, 1, 1)",
                voucherId, "integration-voucher-" + voucherId
        );
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                        + "VALUES (?, ?, ?, ?)",
                voucherId,
                stock,
                Timestamp.valueOf(now.minusMinutes(1)),
                Timestamp.valueOf(now.plusMinutes(10))
        );
        redisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, Integer.toString(stock));
        redisTemplate.opsForValue().set(
                SECKILL_BEGIN_KEY + voucherId,
                Long.toString(System.currentTimeMillis() - 60_000L));
        redisTemplate.opsForValue().set(
                SECKILL_END_KEY + voucherId,
                Long.toString(System.currentTimeMillis() + 600_000L));
        redisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
    }

    private int orderCount(long voucherId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class,
                voucherId
        );
        return count == null ? 0 : count;
    }

    private int databaseStock(long voucherId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class,
                voucherId
        );
        return stock == null ? -1 : stock;
    }

    private void awaitCondition(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting integration condition", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met within " + timeout);
    }

    private record OrderAttempt(long userId, Result result) {
    }
}
