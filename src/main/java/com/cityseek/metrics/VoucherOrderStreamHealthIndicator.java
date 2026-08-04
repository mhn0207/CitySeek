package com.cityseek.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("voucherOrderStreamHealthIndicator")
public class VoucherOrderStreamHealthIndicator implements HealthIndicator {

    private final RedisStreamMetricsCollector collector;

    @Value("${app.redis.pending-warning-age:60s}")
    private Duration pendingWarningAge;

    public VoucherOrderStreamHealthIndicator(RedisStreamMetricsCollector collector) {
        this.collector = collector;
    }

    @Override
    public Health health() {
        RedisStreamMetricsCollector.Snapshot state = collector.snapshot();
        Health.Builder builder;
        if (!state.collectionSucceeded() || !state.groupExists()) {
            builder = Health.down();
        } else if (state.deadLetterLength() > 0
                || state.oldestPendingSeconds() > pendingWarningAge.toSeconds()) {
            builder = Health.status(Status.OUT_OF_SERVICE);
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("streamLength", state.streamLength())
                .withDetail("pending", state.pendingCount())
                .withDetail("oldestPendingSeconds", state.oldestPendingSeconds())
                .withDetail("deadLetterLength", state.deadLetterLength())
                .withDetail("consumerLag", state.consumerLag())
                .withDetail("consumers", state.consumerCount())
                .withDetail("lastCollectionError", state.lastError() == null ? "" : state.lastError())
                .build();
    }
}
