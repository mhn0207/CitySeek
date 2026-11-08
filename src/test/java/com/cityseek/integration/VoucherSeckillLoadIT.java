package com.cityseek.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.serializer.StringRedisSerializer;
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
import java.util.concurrent.ConcurrentHashMap;
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

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void runHttpSeckillLoadTest() throws Exception {
        int requests = Integer.getInteger("load.requests", 500);
        int stock = Integer.getInteger("load.stock", 200);
        int concurrency = Integer.getInteger("load.concurrency", 100);
        assertTrue(requests > 0 && stock > 0 && stock <= requests && concurrency > 0,
                "Require requests >= stock > 0 and concurrency > 0");
        long voucherId = 9_800L;
        seedVoucher(voucherId, stock);
        List<String> tokens = seedSessions(requests);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Long.getLong("load.connect-timeout-ms", 5_000L)))
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoadSample>> futures = new ArrayList<>(requests);
        CompletionObserver observer = new CompletionObserver(voucherId);
        ExecutorService observationExecutor = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < requests; index++) {
                String token = tokens.get(index);
                futures.add(executor.submit(() -> invokeSeckill(client, token, voucherId, start)));
            }

            long wallStart = System.nanoTime();
            Future<?> observation = observationExecutor.submit(observer);
            start.countDown();
            List<LoadSample> samples = new ArrayList<>(requests);
            for (Future<LoadSample> future : futures) {
                samples.add(future.get(30, TimeUnit.SECONDS));
            }
            long wallNanos = System.nanoTime() - wallStart;
            long httpFinishedAt = System.nanoTime();

            long accepted = samples.stream().filter(LoadSample::accepted).count();
            long transportErrors = samples.stream().filter(LoadSample::transportError).count();
            boolean databaseDrained = awaitCondition(Duration.ofMillis(Long.getLong("load.db-drain-ms", 30_000L)),
                    () -> observer.databaseCompleted.size() == accepted);
            boolean statusesDrained = awaitCondition(Duration.ofMillis(Long.getLong("load.status-drain-ms", 15_000L)),
                    () -> observer.statusCompleted.size() == accepted);
            observer.running = false;
            observation.get(15, TimeUnit.SECONDS);

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
            List<Long> databaseLatencies = completionLatencies(samples, observer.databaseCompleted);
            List<Long> completionLatencies = completionLatencies(samples, observer.statusCompleted);
            report += buildDiagnostics(observer, databaseLatencies, completionLatencies,
                    httpFinishedAt, databaseDrained, statusesDrained);
            report += "\nRequest error categories: " + samples.stream().filter(LoadSample::transportError)
                    .collect(java.util.stream.Collectors.groupingBy(LoadSample::errorReason,
                            java.util.TreeMap::new, java.util.stream.Collectors.counting())) + "\n";
            if (!databaseDrained || !statusesDrained || observer.error != null) {
                report = report.replace("**PASS**", "**FAIL**");
            }
            Path reportPath = Path.of("target", "seckill-load-test-report.md").toAbsolutePath();
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report, StandardCharsets.UTF_8);
            Path archive = reportPath.getParent().resolve("load-reports").resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                            + "-c" + concurrency + "-r" + requests + "-s" + stock + ".md");
            Files.createDirectories(archive.getParent());
            Files.writeString(archive, report, StandardCharsets.UTF_8);
            System.out.println(report);
            System.out.println("Load test report: " + reportPath);
            System.out.println("Archived load test report: " + archive);

            assertTrue(databaseDrained, "Database drain timed out; see load report");
            assertTrue(statusesDrained, "SUCCESS observation timed out; see load report");
            assertTrue(observer.error == null, "Completion observation failed: " + observer.error);
            assertEquals(0, transportErrors);
            assertEquals(stock, accepted);
            assertEquals(stock, databaseOrders);
            assertEquals(0, databaseStock);
            assertEquals(0, redisStock);
            assertEquals(stock, successfulStatuses);
        } finally {
            observer.running = false;
            observationExecutor.shutdownNow();
            executor.shutdownNow();
        }
    }

    private LoadSample invokeSeckill(
            HttpClient client,
            String token,
            long voucherId,
            CountDownLatch start) {
        long started = 0L;
        try {
            start.await();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port
                            + "/voucher-order/seckill/" + voucherId))
                    .timeout(Duration.ofMillis(Long.getLong("load.request-timeout-ms", 10_000L)))
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            started = System.nanoTime();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.nanoTime() - started;
            if (response.statusCode() != 200) {
                return new LoadSample(started, latency, false, true, 0L, "HTTP_" + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            boolean accepted = body.path("success").asBoolean(false);
            long orderId = accepted ? body.path("data").asLong(0L) : 0L;
            if (accepted && orderId <= 0) {
                return new LoadSample(started, latency, false, true, 0L, "invalid_order_id");
            }
            return new LoadSample(started, latency, accepted, false, orderId, "none");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new LoadSample(started, elapsedSince(started), false, true, 0L,
                    exception.getClass().getSimpleName());
        } catch (IOException | RuntimeException exception) {
            return new LoadSample(started, elapsedSince(started), false, true, 0L,
                    exception.getClass().getSimpleName());
        }
    }

    private long elapsedSince(long started) {
        return started == 0L ? 0L : System.nanoTime() - started;
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
                + "| Connect timeout (ms) | " + Long.getLong("load.connect-timeout-ms", 5_000L) + " |\n"
                + "| Request timeout (ms) | " + Long.getLong("load.request-timeout-ms", 10_000L) + " |\n"
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

    private boolean awaitCondition(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting load-test condition", exception);
            }
        }
        return condition.getAsBoolean();
    }

    private List<Long> completionLatencies(List<LoadSample> samples, Map<Long, Long> observed) {
        return samples.stream().filter(LoadSample::accepted)
                .filter(sample -> observed.containsKey(sample.orderId()))
                .map(sample -> observed.get(sample.orderId()) - sample.startedNanos())
                .sorted().toList();
    }

    private String buildDiagnostics(CompletionObserver observer, List<Long> databaseLatencies,
                                    List<Long> completionLatencies, long httpFinishedAt,
                                    boolean databaseDrained, boolean statusesDrained) {
        Timer processing = meterRegistry.find("cityseek.order.processing").timer();
        long lastCompletion = observer.statusCompleted.values().stream()
                .mapToLong(Long::longValue).max().orElse(httpFinishedAt);
        String drain = statusesDrained && observer.error == null
                ? String.format(java.util.Locale.ROOT, "%.2f", Math.max(0L, lastCompletion - httpFinishedAt) / 1_000_000.0)
                : "N/A (not fully observed)";
        StringBuilder report = new StringBuilder("\n## Phase measurements and diagnostics\n\n")
                .append("Completion is first observed by a background poller (100 ms sleep between polls). ")
                .append("Latencies start immediately before each HTTP send; they exclude client executor queueing. ")
                .append("Database/SUCCESS observations are upper bounds, include query time, and add measurement load.\n\n")
                .append("| Metric | Value |\n| --- | ---: |\n")
                .append("| Database drain within configured timeout (ms: ")
                .append(Long.getLong("load.db-drain-ms", 30_000L)).append(") | ").append(databaseDrained).append(" |\n")
                .append("| SUCCESS drain within subsequent timeout (ms: ")
                .append(Long.getLong("load.status-drain-ms", 15_000L)).append(") | ").append(statusesDrained).append(" |\n")
                .append("| Observed database completions | ").append(observer.databaseCompleted.size()).append(" |\n")
                .append("| Observed SUCCESS completions | ").append(observer.statusCompleted.size()).append(" |\n")
                .append("| Post-HTTP SUCCESS drain (ms) | ").append(drain).append(" |\n");
        appendPercentiles(report, "Request to DB visibility", databaseLatencies);
        appendPercentiles(report, "Request to SUCCESS visibility", completionLatencies);
        report.append("| Observer error | ").append(observer.error == null ? "none" : observer.error).append(" |\n");
        if (processing != null) {
            report.append("| Consumer processing samples | ").append(processing.count()).append(" |\n")
                    .append("| Consumer processing mean (ms) | ").append(processing.mean(TimeUnit.MILLISECONDS)).append(" |\n")
                    .append("| Consumer processing max (ms) | ").append(processing.max(TimeUnit.MILLISECONDS)).append(" |\n");
        }
        for (String name : List.of("cityseek.order.retry", "cityseek.order.compensated", "cityseek.order.dlq")) {
            var counter = meterRegistry.find(name).counter();
            report.append("| ").append(name).append(" | ").append(counter == null ? "N/A" : counter.count()).append(" |\n");
        }
        try {
            var group = redisTemplate.opsForStream().groups("stream.orders").stream()
                    .filter(info -> "g1".equals(info.groupName())).findFirst().orElseThrow();
            report.append("| Final Pending | ").append(redisTemplate.opsForStream()
                    .pending("stream.orders", "g1").getTotalPendingMessages()).append(" |\n")
                    .append("| Final consumer lag | ").append(group.getRaw().get("lag")).append(" |\n");
        } catch (RuntimeException exception) {
            report.append("| Stream diagnostics error | ").append(exception.getClass().getSimpleName()).append(" |\n");
        }
        report.append("\nCompletion percentiles describe observed orders only; check counts and drain flags before interpreting them. ")
                .append("Consumer processing includes lock, DB transaction, status update and ACK, but excludes queue wait. ")
                .append("Final Pending and lag are end snapshots, not peak backlog measurements.\n");
        return report.toString();
    }

    private void appendPercentiles(StringBuilder report, String phase, List<Long> latencies) {
        for (double percentile : new double[]{0.50, 0.95, 0.99}) {
            report.append("| ").append(phase).append(" P").append((int) (percentile * 100))
                    .append(" (ms) | ").append(latencies.isEmpty() ? "N/A" : percentileMs(latencies, percentile)).append(" |\n");
        }
    }

    private class CompletionObserver implements Runnable {
        private final long voucherId;
        private final Map<Long, Long> databaseCompleted = new ConcurrentHashMap<>();
        private final Map<Long, Long> statusCompleted = new ConcurrentHashMap<>();
        private volatile boolean running = true;
        private volatile String error;

        private CompletionObserver(long voucherId) {
            this.voucherId = voucherId;
        }

        @Override
        public void run() {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    List<Long> ids = jdbcTemplate.queryForList(
                            "SELECT id FROM tb_voucher_order WHERE voucher_id = ?", Long.class, voucherId);
                    long observedAt = System.nanoTime();
                    ids.forEach(id -> databaseCompleted.putIfAbsent(id, observedAt));
                    List<Long> unchecked = ids.stream().filter(id -> !statusCompleted.containsKey(id)).toList();
                    if (!unchecked.isEmpty()) {
                        var serializer = StringRedisSerializer.UTF_8;
                        List<Object> statuses = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                            for (Long id : unchecked) {
                                connection.hashCommands().hGet(serializer.serialize(SECKILL_ORDER_STATUS_KEY + id),
                                        serializer.serialize("status"));
                            }
                            return null;
                        });
                        long statusObservedAt = System.nanoTime();
                        for (int index = 0; index < unchecked.size(); index++) {
                            if ("SUCCESS".equals(statuses.get(index))) {
                                statusCompleted.putIfAbsent(unchecked.get(index), statusObservedAt);
                            }
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(100L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                error = exception.toString().replace('|', '/').replace('\n', ' ');
            }
        }
    }

    private record LoadSample(long startedNanos, long latencyNanos, boolean accepted, boolean transportError,
                              long orderId, String errorReason) {
    }
}
