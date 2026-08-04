package com.cityseek.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class VoucherOrderMetrics {

    private final MeterRegistry registry;
    private final Counter requests;
    private final Counter accepted;
    private final Counter persisted;
    private final Counter retries;
    private final Counter compensated;
    private final Counter deadLetters;
    private final Timer processing;
    private final Map<String, Counter> rejected;

    public VoucherOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requests = registry.counter("cityseek.seckill.requests");
        this.accepted = registry.counter("cityseek.seckill.accepted");
        this.persisted = registry.counter("cityseek.order.persisted");
        this.retries = registry.counter("cityseek.order.retry");
        this.compensated = registry.counter("cityseek.order.compensated");
        this.deadLetters = registry.counter("cityseek.order.dlq");
        this.processing = registry.timer("cityseek.order.processing");
        this.rejected = Map.of(
                "out_of_stock", rejectedCounter("out_of_stock"),
                "duplicate", rejectedCounter("duplicate"),
                "not_started", rejectedCounter("not_started"),
                "ended", rejectedCounter("ended"),
                "not_found", rejectedCounter("not_found"));
    }

    public void request() {
        requests.increment();
    }

    public void accepted() {
        accepted.increment();
    }

    public void rejected(String reason) {
        Counter counter = rejected.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    public void persisted() {
        persisted.increment();
    }

    public void retry() {
        retries.increment();
    }

    public void compensated() {
        compensated.increment();
    }

    public void deadLetter() {
        deadLetters.increment();
    }

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    public void stopProcessing(Timer.Sample sample) {
        sample.stop(processing);
    }

    private Counter rejectedCounter(String reason) {
        return Counter.builder("cityseek.seckill.rejected")
                .tag("reason", reason)
                .register(registry);
    }
}
