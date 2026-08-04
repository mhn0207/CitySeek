package com.cityseek.feed;

import com.cityseek.entity.OutboxEvent;
import com.cityseek.service.OutboxEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.feed.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeedOutboxWorker {

    private final OutboxEventService outboxEventService;
    private final FeedEventDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String instanceId = buildInstanceId();

    @Value("${app.feed.outbox.batch-size:10}")
    private int batchSize;
    @Value("${app.feed.outbox.follower-batch-size:200}")
    private int followerBatchSize;
    @Value("${app.feed.outbox.lease-duration:30s}")
    private Duration leaseDuration;
    @Value("${app.feed.outbox.max-retries:5}")
    private int maxRetries;

    public FeedOutboxWorker(
            OutboxEventService outboxEventService,
            FeedEventDispatcher dispatcher,
            ObjectMapper objectMapper) {
        this.outboxEventService = outboxEventService;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.feed.outbox.poll-interval:1000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<OutboxEvent> events = outboxEventService.claimBatch(
                    batchSize, maxRetries, leaseDuration, instanceId);
            events.forEach(this::process);
        } catch (RuntimeException exception) {
            log.warn("Feed outbox polling failed", exception);
        } finally {
            running.set(false);
        }
    }

    private void process(OutboxEvent event) {
        try {
            BlogPublishedEventPayload payload = objectMapper.readValue(
                    event.getPayload(), BlogPublishedEventPayload.class);
            dispatcher.dispatch(payload, Math.max(1, followerBatchSize),
                    () -> outboxEventService.renewLease(
                            event.getId(), event.getClaimToken(), leaseDuration));
            if (!outboxEventService.markSuccess(event.getId(), event.getClaimToken())) {
                throw new LostOutboxLeaseException(event.getId());
            }
        } catch (LostOutboxLeaseException exception) {
            log.warn("Stopped stale feed outbox worker: {}", exception.getMessage());
        } catch (Exception exception) {
            boolean updated = outboxEventService.markFailure(event, maxRetries, exception);
            if (!updated) {
                log.warn("Could not mark failed outbox event {}; its lease was lost", event.getId());
            } else {
                log.warn("Feed outbox event {} will be retried or marked failed", event.getId(), exception);
            }
        }
    }

    private String buildInstanceId() {
        String host = System.getenv().getOrDefault("COMPUTERNAME", "cityseek");
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
