package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.entity.Blog;
import com.cityseek.entity.OutboxEvent;
import com.cityseek.exception.ResourceNotFoundException;
import com.cityseek.feed.BlogPublishedEventPayload;
import com.cityseek.mapper.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxEventService extends ServiceImpl<OutboxEventMapper, OutboxEvent> {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Counter deliveredCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;

    public OutboxEventService(
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.deliveredCounter = meterRegistry.counter("cityseek.feed.outbox.delivered");
        this.retryCounter = meterRegistry.counter("cityseek.feed.outbox.retry");
        this.failedCounter = meterRegistry.counter("cityseek.feed.outbox.failed");
    }

    public void createBlogPublishedEvent(Blog blog, long publishedAt) {
        BlogPublishedEventPayload payload = new BlogPublishedEventPayload(
                blog.getId(), blog.getUserId(), publishedAt);
        try {
            OutboxEvent event = new OutboxEvent()
                    .setAggregateType("BLOG")
                    .setAggregateId(blog.getId())
                    .setEventType("BLOG_PUBLISHED")
                    .setPayload(objectMapper.writeValueAsString(payload))
                    .setStatus(OutboxEvent.PENDING)
                    .setRetryCount(0);
            if (!save(event)) {
                throw new IllegalStateException("Outbox 事件保存失败");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Outbox 事件序列化失败", exception);
        }
    }

    public List<OutboxEvent> claimBatch(
            int batchSize,
            int maxRetries,
            Duration leaseDuration,
            String instanceId) {
        List<OutboxEvent> claimed = transactionTemplate.execute(status -> {
            List<OutboxEvent> candidates = jdbcTemplate.query("""
                    SELECT id, aggregate_type, aggregate_id, event_type, payload, status,
                           retry_count, next_retry_time, error_message, claimed_by,
                           claim_token, processing_deadline, create_time, update_time
                    FROM tb_outbox_event
                    WHERE (status = 'PENDING'
                           AND (next_retry_time IS NULL OR next_retry_time <= NOW()))
                       OR (status = 'PROCESSING' AND processing_deadline <= NOW())
                    ORDER BY create_time
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, this::mapEvent, Math.max(1, batchSize));
            List<OutboxEvent> result = new ArrayList<>();
            for (OutboxEvent event : candidates) {
                int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
                if (OutboxEvent.PROCESSING.equals(event.getStatus())) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        int terminalUpdate = jdbcTemplate.update("""
                                UPDATE tb_outbox_event
                                SET status = 'FAILED', retry_count = ?,
                                    error_message = 'processing lease expired too many times',
                                    claimed_by = NULL, claim_token = NULL, processing_deadline = NULL
                                WHERE id = ?
                                """, retryCount, event.getId());
                        if (terminalUpdate == 1) {
                            failedCounter.increment();
                        }
                        continue;
                    }
                    retryCounter.increment();
                }
                String token = UUID.randomUUID().toString();
                LocalDateTime deadline = LocalDateTime.now().plus(leaseDuration);
                int updated = jdbcTemplate.update("""
                        UPDATE tb_outbox_event
                        SET status = 'PROCESSING', retry_count = ?, claimed_by = ?,
                            claim_token = ?, processing_deadline = ?, error_message = NULL
                        WHERE id = ?
                        """, retryCount, instanceId, token, Timestamp.valueOf(deadline), event.getId());
                if (updated == 1) {
                    event.setStatus(OutboxEvent.PROCESSING)
                            .setRetryCount(retryCount)
                            .setClaimedBy(instanceId)
                            .setClaimToken(token)
                            .setProcessingDeadline(deadline);
                    result.add(event);
                }
            }
            return result;
        });
        return claimed == null ? List.of() : claimed;
    }

    public boolean renewLease(Long eventId, String claimToken, Duration leaseDuration) {
        return jdbcTemplate.update("""
                UPDATE tb_outbox_event
                SET processing_deadline = ?
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.valueOf(LocalDateTime.now().plus(leaseDuration)), eventId, claimToken) == 1;
    }

    public boolean markSuccess(Long eventId, String claimToken) {
        boolean updated = jdbcTemplate.update("""
                UPDATE tb_outbox_event
                SET status = 'SUCCESS', next_retry_time = NULL, error_message = NULL,
                    claimed_by = NULL, claim_token = NULL, processing_deadline = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, eventId, claimToken) == 1;
        if (updated) {
            deliveredCounter.increment();
        }
        return updated;
    }

    public boolean markFailure(OutboxEvent event, int maxRetries, Throwable failure) {
        int nextRetryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        boolean failed = nextRetryCount >= maxRetries;
        long delaySeconds = Math.min(300L, 1L << Math.min(8, nextRetryCount));
        LocalDateTime nextRetryTime = failed ? null : LocalDateTime.now().plusSeconds(delaySeconds);
        boolean updated = jdbcTemplate.update("""
                UPDATE tb_outbox_event
                SET status = ?, retry_count = ?, next_retry_time = ?, error_message = ?,
                    claimed_by = NULL, claim_token = NULL, processing_deadline = NULL
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                failed ? OutboxEvent.FAILED : OutboxEvent.PENDING,
                nextRetryCount,
                nextRetryTime == null ? null : Timestamp.valueOf(nextRetryTime),
                errorSummary(failure),
                event.getId(), event.getClaimToken()) == 1;
        if (updated) {
            (failed ? failedCounter : retryCounter).increment();
        }
        return updated;
    }

    public void replay(Long eventId) {
        OutboxEvent event = getById(eventId);
        if (event == null) {
            throw new ResourceNotFoundException("Outbox 事件不存在");
        }
        int updated = jdbcTemplate.update("""
                UPDATE tb_outbox_event
                SET status = 'PENDING', retry_count = 0, next_retry_time = NOW(),
                    error_message = NULL, claimed_by = NULL, claim_token = NULL,
                    processing_deadline = NULL
                WHERE id = ? AND status = 'FAILED'
                """, eventId);
        if (updated != 1) {
            throw new IllegalArgumentException("只有 FAILED 事件可以重放");
        }
    }

    private OutboxEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent()
                .setId(resultSet.getLong("id"))
                .setAggregateType(resultSet.getString("aggregate_type"))
                .setAggregateId(resultSet.getLong("aggregate_id"))
                .setEventType(resultSet.getString("event_type"))
                .setPayload(resultSet.getString("payload"))
                .setStatus(resultSet.getString("status"))
                .setRetryCount(resultSet.getInt("retry_count"))
                .setNextRetryTime(toLocalDateTime(resultSet.getTimestamp("next_retry_time")))
                .setErrorMessage(resultSet.getString("error_message"))
                .setClaimedBy(resultSet.getString("claimed_by"))
                .setClaimToken(resultSet.getString("claim_token"))
                .setProcessingDeadline(toLocalDateTime(resultSet.getTimestamp("processing_deadline")))
                .setCreateTime(toLocalDateTime(resultSet.getTimestamp("create_time")))
                .setUpdateTime(toLocalDateTime(resultSet.getTimestamp("update_time")));
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String errorSummary(Throwable throwable) {
        String message = throwable.getMessage();
        String summary = throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
        return summary.length() <= 512 ? summary : summary.substring(0, 512);
    }
}
