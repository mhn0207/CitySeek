package com.cityseek.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cityseek.utils.RedisConstants.LOGIN_USER_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_END_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class VoucherSeckillLoadIT extends ContainerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runHttpSeckillLoadTest() throws Exception {
        int requests = Integer.getInteger("load.requests", 500);
        int stock = Integer.getInteger("load.stock", 200);
        int concurrency = Integer.getInteger("load.concurrency", 100);
        long voucherId = 9_800L;
        seedVoucher(voucherId, stock);
        List<String> tokens = seedSessions(requests);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoadSample>> futures = new ArrayList<>(requests);
        try {
            for (int index = 0; index < requests; index++) {
                String token = tokens.get(index);
                futures.add(executor.submit(() -> invokeSeckill(client, token, voucherId, start)));
            }

            long wallStart = System.nanoTime();
            start.countDown();
            List<LoadSample> samples = new ArrayList<>(requests);
            for (Future<LoadSample> future : futures) {
                samples.add(future.get(30, TimeUnit.SECONDS));
            }
            long wallNanos = System.nanoTime() - wallStart;

            long accepted = samples.stream().filter(LoadSample::accepted).count();
            long transportErrors = samples.stream().filter(LoadSample::transportError).count();
            awaitCondition(Duration.ofSeconds(30), () -> orderCount(voucherId) == accepted);
            awaitCondition(Duration.ofSeconds(15), () -> successfulStatusCount(samples) == accepted);

            int databaseOrders = orderCount(voucherId);
            int databaseStock = databaseStock(voucherId);
            int redisStock = Integer.parseInt(redisTemplate.opsForValue()
                    .get(SECKILL_STOCK_KEY + voucherId));
            long successfulStatuses = successfulStatusCount(samples);
            double qps = requests / (wallNanos / 1_000_000_000.0);
            String report = buildReport(
                    requests,
                    stock,
                    concurrency,
                    samples,
                    accepted,
                    transportErrors,
                    databaseOrders,
                    databaseStock,
                    redisStock,
                    successfulStatuses,
                    qps,
                    wallNanos
            );
            Path reportPath = Path.of("target", "seckill-load-test-report.md").toAbsolutePath();
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report, StandardCharsets.UTF_8);
            System.out.println(report);
            System.out.println("Load test report: " + reportPath);

            assertEquals(0, transportErrors);
            assertEquals(stock, accepted);
            assertEquals(stock, databaseOrders);
            assertEquals(0, databaseStock);
            assertEquals(0, redisStock);
            assertEquals(stock, successfulStatuses);
        } finally {
            executor.shutdownNow();
        }
    }

    private LoadSample invokeSeckill(
            HttpClient client,
            String token,
            long voucherId,
            CountDownLatch start) {
        try {
            start.await();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port
                            + "/voucher-order/seckill/" + voucherId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.nanoTime() - started;
            if (response.statusCode() != 200) {
                return new LoadSample(latency, false, false, 0L);
            }
            JsonNode body = objectMapper.readTree(response.body());
            boolean accepted = body.path("success").asBoolean(false);
            long orderId = accepted ? body.path("data").asLong(0L) : 0L;
            return new LoadSample(latency, accepted, false, orderId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new LoadSample(0L, false, true, 0L);
        } catch (IOException | RuntimeException exception) {
            return new LoadSample(0L, false, true, 0L);
        }
    }

    private List<String> seedSessions(int count) {
        List<String> tokens = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long userId = 50_000L + index;
            String token = "load-token-" + userId;
            Map<String, String> session = new HashMap<>();
            session.put("id", Long.toString(userId));
            session.put("nickName", "load-user-" + userId);
            session.put("icon", "");
            redisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, session);
            redisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
            tokens.add(token);
        }
        return tokens;
    }

    private void seedVoucher(long voucherId, int stock) {
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
        jdbcTemplate.update(
                "INSERT INTO tb_voucher "
                        + "(id, shop_id, title, pay_value, actual_value, type, status) "
                        + "VALUES (?, 1, ?, 100, 200, 1, 1)",
                voucherId,
                "load-voucher-" + voucherId
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
    }

    private long successfulStatusCount(List<LoadSample> samples) {
        return samples.stream()
                .filter(LoadSample::accepted)
                .filter(sample -> "SUCCESS".equals(redisTemplate.opsForHash()
                        .get(SECKILL_ORDER_STATUS_KEY + sample.orderId(), "status")))
                .count();
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

    private String buildReport(
            int requests,
            int stock,
            int concurrency,
            List<LoadSample> samples,
            long accepted,
            long transportErrors,
            int databaseOrders,
            int databaseStock,
            int redisStock,
            long successfulStatuses,
            double qps,
            long wallNanos) {
        List<Long> latencies = samples.stream()
                .map(LoadSample::latencyNanos)
                .filter(value -> value > 0)
                .sorted(Comparator.naturalOrder())
                .toList();
        DecimalFormat decimal = new DecimalFormat("0.00");
        double averageMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        boolean consistent = transportErrors == 0
                && accepted == stock
                && databaseOrders == stock
                && databaseStock == 0
                && redisStock == 0
                && successfulStatuses == stock;
        return "# CitySeek Seckill Load Test Result\n\n"
                + "- Executed at: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n"
                + "- Runtime: Spring Boot random HTTP port + Testcontainers MySQL 8.0.36 + Redis 7.2\n"
                + "- Result: **" + (consistent ? "PASS" : "FAIL") + "**\n\n"
                + "| Metric | Value |\n"
                + "| --- | ---: |\n"
                + "| Requests | " + requests + " |\n"
                + "| Concurrency | " + concurrency + " |\n"
                + "| Initial stock | " + stock + " |\n"
                + "| Wall time (s) | " + decimal.format(wallNanos / 1_000_000_000.0) + " |\n"
                + "| Throughput (requests/s) | " + decimal.format(qps) + " |\n"
                + "| Average latency (ms) | " + decimal.format(averageMs) + " |\n"
                + "| P50 latency (ms) | " + decimal.format(percentileMs(latencies, 0.50)) + " |\n"
                + "| P95 latency (ms) | " + decimal.format(percentileMs(latencies, 0.95)) + " |\n"
                + "| P99 latency (ms) | " + decimal.format(percentileMs(latencies, 0.99)) + " |\n"
                + "| Business success rate (%) | "
                + decimal.format(requests == 0 ? 0 : accepted * 100.0 / requests) + " |\n"
                + "| Transport success rate (%) | "
                + decimal.format(requests == 0 ? 0 : (requests - transportErrors) * 100.0 / requests) + " |\n"
                + "| Accepted | " + accepted + " |\n"
                + "| Rejected | " + (requests - accepted - transportErrors) + " |\n"
                + "| Transport errors | " + transportErrors + " |\n"
                + "| MySQL orders | " + databaseOrders + " |\n"
                + "| MySQL remaining stock | " + databaseStock + " |\n"
                + "| Redis remaining stock | " + redisStock + " |\n"
                + "| SUCCESS status records | " + successfulStatuses + " |\n\n"
                + "Consistency passes only when accepted requests, persisted orders, status records, "
                + "and both stock values agree exactly.\n";
    }

    private double percentileMs(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(percentile * sortedNanos.size()) - 1);
        return sortedNanos.get(index) / 1_000_000.0;
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
                throw new IllegalStateException("Interrupted while awaiting load-test condition", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met within " + timeout);
    }

    private record LoadSample(long latencyNanos, boolean accepted, boolean transportError, long orderId) {
    }
}
