package com.cityseek.integration;

import com.cityseek.dto.UserDTO;
import com.cityseek.dto.request.BlogCreateRequest;
import com.cityseek.entity.OutboxEvent;
import com.cityseek.service.IBlogService;
import com.cityseek.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cityseek.utils.RedisConstants.FEED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FeedOutboxIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    private IBlogService blogService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void publishesFeedThroughRecoverableIdempotentOutbox() {
        long authorId = 1L;
        long followerId = 2L;
        String image = "/blogs/outbox-integration.jpg";
        jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id = ? AND follow_user_id = ?", followerId, authorId);
        jdbcTemplate.update("INSERT INTO tb_follow (user_id, follow_user_id) VALUES (?, ?)", followerId, authorId);
        jdbcTemplate.update("DELETE FROM tb_upload_file WHERE path = ?", image);
        jdbcTemplate.update("""
                INSERT INTO tb_upload_file (path, uploader_id, business_type, status)
                VALUES (?, ?, 'BLOG', 0)
                """, image, authorId);

        UserDTO author = new UserDTO();
        author.setId(authorId);
        UserHolder.saveUser(author);
        long blogId;
        try {
            BlogCreateRequest request = new BlogCreateRequest();
            request.setShopId(1L);
            request.setTitle("outbox integration");
            request.setImages(image);
            request.setContent("outbox integration content");
            blogId = ((Number) blogService.saveBlog(request).getData()).longValue();
        } finally {
            UserHolder.removeUser();
        }

        await(Duration.ofSeconds(15), () -> OutboxEvent.SUCCESS.equals(outboxStatus(blogId)));
        assertEquals(1L, redisTemplate.opsForZSet().size(FEED_KEY + followerId));
        assertTrue(redisTemplate.opsForZSet().score(
                FEED_KEY + followerId, Long.toString(blogId)) != null);

        jdbcTemplate.update("""
                UPDATE tb_outbox_event
                SET status = 'PENDING', next_retry_time = NOW(), retry_count = 0
                WHERE aggregate_id = ? AND event_type = 'BLOG_PUBLISHED'
                """, blogId);
        await(Duration.ofSeconds(15), () -> OutboxEvent.SUCCESS.equals(outboxStatus(blogId)));
        assertEquals(1L, redisTemplate.opsForZSet().size(FEED_KEY + followerId));
    }

    private String outboxStatus(long blogId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM tb_outbox_event
                WHERE aggregate_id = ? AND event_type = 'BLOG_PUBLISHED'
                """, String.class, blogId);
    }

    private void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Condition not met within " + timeout);
    }
}
