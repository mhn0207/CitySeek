package com.cityseek.feed;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cityseek.entity.Follow;
import com.cityseek.mapper.FollowMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BooleanSupplier;

import static com.cityseek.utils.RedisConstants.FEED_KEY;

@Component
public class FeedEventDispatcher {

    private static final StringRedisSerializer SERIALIZER = StringRedisSerializer.UTF_8;
    private final FollowMapper followMapper;
    private final StringRedisTemplate redisTemplate;

    public FeedEventDispatcher(FollowMapper followMapper, StringRedisTemplate redisTemplate) {
        this.followMapper = followMapper;
        this.redisTemplate = redisTemplate;
    }

    public void dispatch(
            BlogPublishedEventPayload event,
            int followerBatchSize,
            BooleanSupplier renewLease) {
        long lastFollowId = 0L;
        while (true) {
            List<Follow> followers = followMapper.selectList(new QueryWrapper<Follow>()
                    .eq("follow_user_id", event.authorId())
                    .gt("id", lastFollowId)
                    .orderByAsc("id")
                    .last("LIMIT " + Math.max(1, followerBatchSize)));
            if (followers.isEmpty()) {
                return;
            }
            writeFeedBatch(followers, event);
            if (!renewLease.getAsBoolean()) {
                throw new LostOutboxLeaseException(event.blogId());
            }
            lastFollowId = followers.get(followers.size() - 1).getId();
            if (followers.size() < followerBatchSize) {
                return;
            }
        }
    }

    private void writeFeedBatch(List<Follow> followers, BlogPublishedEventPayload event) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] member = SERIALIZER.serialize(event.blogId().toString());
            for (Follow follower : followers) {
                connection.zSetCommands().zAdd(
                        SERIALIZER.serialize(FEED_KEY + follower.getUserId()),
                        event.publishedAt().doubleValue(),
                        member);
            }
            return null;
        });
    }
}
