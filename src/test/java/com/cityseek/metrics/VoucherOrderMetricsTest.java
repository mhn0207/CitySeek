package com.cityseek.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoucherOrderMetricsTest {

    @Test
    void recordsBoundedSeckillAndReliabilityMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VoucherOrderMetrics metrics = new VoucherOrderMetrics(registry);

        metrics.request();
        metrics.accepted();
        metrics.rejected("out_of_stock");
        metrics.persisted();
        metrics.retry();
        metrics.compensated();
        metrics.deadLetter();

        assertEquals(1.0, registry.counter("cityseek.seckill.requests").count());
        assertEquals(1.0, registry.counter("cityseek.seckill.accepted").count());
        assertEquals(1.0, registry.counter(
                "cityseek.seckill.rejected", "reason", "out_of_stock").count());
        assertEquals(1.0, registry.counter("cityseek.order.persisted").count());
        assertEquals(1.0, registry.counter("cityseek.order.retry").count());
        assertEquals(1.0, registry.counter("cityseek.order.compensated").count());
        assertEquals(1.0, registry.counter("cityseek.order.dlq").count());
    }
}
