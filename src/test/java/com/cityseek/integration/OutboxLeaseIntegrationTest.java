package com.cityseek.integration;

import com.cityseek.entity.OutboxEvent;
import com.cityseek.service.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.feed.outbox.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class OutboxLeaseIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OutboxEventService outboxEventService;

    @Test
    void expiredLeaseIsReclaimedAndOldTokenCannotCompleteEvent() {
        long eventId = 8_800_000_001L;
        jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE id = ?", eventId);
        jdbcTemplate.update("""
                INSERT INTO tb_outbox_event
                    (id, aggregate_type, aggregate_id, event_type, payload, status,
                     retry_count, claimed_by, claim_token, processing_deadline)
                VALUES (?, 'BLOG', 1, 'BLOG_PUBLISHED', '{}', 'PROCESSING',
                        0, 'dead-worker', 'dead-token', DATE_SUB(NOW(), INTERVAL 1 SECOND))
                """, eventId);

        List<OutboxEvent> firstClaim = outboxEventService.claimBatch(
                1, 5, Duration.ofSeconds(30), "worker-one");
        assertEquals(1, firstClaim.size());
        assertEquals(1, firstClaim.get(0).getRetryCount());
        String firstToken = firstClaim.get(0).getClaimToken();

        jdbcTemplate.update("""
                UPDATE tb_outbox_event SET processing_deadline = DATE_SUB(NOW(), INTERVAL 1 SECOND)
                WHERE id = ?
                """, eventId);
        List<OutboxEvent> secondClaim = outboxEventService.claimBatch(
                1, 5, Duration.ofSeconds(30), "worker-two");
        assertEquals(1, secondClaim.size());
        String secondToken = secondClaim.get(0).getClaimToken();
        assertNotEquals(firstToken, secondToken);

        assertFalse(outboxEventService.markSuccess(eventId, firstToken));
        assertTrue(outboxEventService.markSuccess(eventId, secondToken));
    }
}
