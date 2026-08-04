package com.cityseek.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RedisStreamMetricsCollector {

    private static final String STREAM = "stream.orders";
    private static final String DLQ = "stream.orders.dlq";
    private static final String GROUP = "g1";
    private static final int MAX_PENDING_SAMPLE = 1000;

    private final StringRedisTemplate redisTemplate;
    private final AtomicLong streamLength = new AtomicLong();
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong deadLetterLength = new AtomicLong();
    private final AtomicLong consumerLag = new AtomicLong();
    private final AtomicLong consumerCount = new AtomicLong();
    private volatile boolean collectionSucceeded;
    private volatile boolean groupExists;
    private volatile String lastError;

    public RedisStreamMetricsCollector(StringRedisTemplate redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        Gauge.builder("cityseek.redis.stream.length", streamLength, AtomicLong::get).register(registry);
        Gauge.builder("cityseek.redis.stream.pending", pendingCount, AtomicLong::get).register(registry);
        Gauge.builder("cityseek.redis.stream.pending.oldest.seconds", oldestPendingSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("cityseek.redis.stream.dlq.length", deadLetterLength, AtomicLong::get).register(registry);
        Gauge.builder("cityseek.redis.stream.consumer.lag", consumerLag, AtomicLong::get).register(registry);
        Gauge.builder("cityseek.redis.stream.consumers", consumerCount, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${app.redis.stream-metrics-interval:10s}")
    public void collect() {
        try {
            streamLength.set(valueOrZero(redisTemplate.opsForStream().size(STREAM)));
            deadLetterLength.set(valueOrZero(redisTemplate.opsForStream().size(DLQ)));
            StreamInfo.XInfoGroup group = findGroup(redisTemplate.opsForStream().groups(STREAM));
            groupExists = group != null;
            if (group == null) {
                throw new IllegalStateException("消费组不存在: " + GROUP);
            }
            consumerCount.set(valueOrZero(group.consumerCount()));
            Object lag = group.getRaw().get("lag");
            consumerLag.set(lag instanceof Number number ? number.longValue() : 0L);

            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(STREAM, GROUP);
            long totalPending = summary.getTotalPendingMessages();
            pendingCount.set(totalPending);
            oldestPendingSeconds.set(readOldestIdleSeconds(totalPending));
            collectionSucceeded = true;
            lastError = null;
        } catch (RuntimeException exception) {
            collectionSucceeded = false;
            lastError = exception.getClass().getSimpleName() + ":" + exception.getMessage();
            log.warn("Redis Stream metrics collection failed; keeping the last values", exception);
        }
    }

    private StreamInfo.XInfoGroup findGroup(StreamInfo.XInfoGroups groups) {
        for (StreamInfo.XInfoGroup group : groups) {
            if (GROUP.equals(group.groupName())) {
                return group;
            }
        }
        return null;
    }

    private long readOldestIdleSeconds(long totalPending) {
        if (totalPending == 0) {
            return 0L;
        }
        PendingMessages messages = redisTemplate.opsForStream().pending(
                STREAM, GROUP, Range.unbounded(), Math.min(totalPending, MAX_PENDING_SAMPLE));
        long oldest = 0L;
        for (PendingMessage message : messages) {
            oldest = Math.max(oldest, message.getElapsedTimeSinceLastDelivery().toSeconds());
        }
        return oldest;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                streamLength.get(), pendingCount.get(), oldestPendingSeconds.get(),
                deadLetterLength.get(), consumerLag.get(), consumerCount.get(),
                collectionSucceeded, groupExists, lastError);
    }

    public record Snapshot(
            long streamLength,
            long pendingCount,
            long oldestPendingSeconds,
            long deadLetterLength,
            long consumerLag,
            long consumerCount,
            boolean collectionSucceeded,
            boolean groupExists,
            String lastError) {
    }
}
