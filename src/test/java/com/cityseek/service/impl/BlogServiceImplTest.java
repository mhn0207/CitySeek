package com.cityseek.service.impl;

import com.cityseek.dto.Result;
import com.cityseek.dto.ScrollResult;
import com.cityseek.dto.UserDTO;
import com.cityseek.mapper.BlogMapper;
import com.cityseek.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class BlogServiceImplTest {

    private BlogServiceImpl service;
    private ZSetOperations<String, String> zSetOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new BlogServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        BlogMapper blogMapper = mock(BlogMapper.class);

        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(blogMapper.selectList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);

        UserDTO user = new UserDTO();
        user.setId(10L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void accumulatesOffsetAcrossPagesWithTheSameTimestamp() {
        when(zSetOperations.reverseRangeByScoreWithScores(
                anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(
                        tuples(tuple("1", 100), tuple("2", 100)),
                        tuples(tuple("3", 100), tuple("4", 100)),
                        tuples(tuple("5", 100))
                );

        ScrollResult first = scroll(service.queryBlogOfFollow(200L, 0));
        assertEquals(100L, first.getMinTime());
        assertEquals(2, first.getOffset());

        ScrollResult second = scroll(service.queryBlogOfFollow(first.getMinTime(), first.getOffset()));
        assertEquals(100L, second.getMinTime());
        assertEquals(4, second.getOffset());

        ScrollResult third = scroll(service.queryBlogOfFollow(second.getMinTime(), second.getOffset()));
        assertEquals(100L, third.getMinTime());
        assertEquals(5, third.getOffset());
    }

    @Test
    void resetsOffsetWhenTheMinimumTimestampChanges() {
        when(zSetOperations.reverseRangeByScoreWithScores(
                anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(tuples(tuple("1", 100), tuple("2", 99)));

        ScrollResult result = scroll(service.queryBlogOfFollow(100L, 4));

        assertEquals(99L, result.getMinTime());
        assertEquals(1, result.getOffset());
    }

    @Test
    void rejectsInvalidFeedCursors() {
        assertFalse(service.queryBlogOfFollow(0L, 0).getSuccess());
        assertFalse(service.queryBlogOfFollow(100L, -1).getSuccess());
        assertFalse(service.queryBlogOfFollow(100L, 10_001).getSuccess());
    }

    private ScrollResult scroll(Result result) {
        return (ScrollResult) result.getData();
    }

    @SafeVarargs
    private final Set<ZSetOperations.TypedTuple<String>> tuples(ZSetOperations.TypedTuple<String>... values) {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        Collections.addAll(tuples, values);
        return tuples;
    }

    private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        return new DefaultTypedTuple<>(value, score);
    }
}
