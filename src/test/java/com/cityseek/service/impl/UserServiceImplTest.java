package com.cityseek.service.impl;

import com.cityseek.dto.LoginFormDTO;
import com.cityseek.dto.Result;
import com.cityseek.entity.User;
import com.cityseek.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class UserServiceImplTest {

    private UserServiceImpl service;
    private StringRedisTemplate redisTemplate;
    private UserMapper userMapper;
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new UserServiceImpl();
        redisTemplate = mock(StringRedisTemplate.class);
        userMapper = mock(UserMapper.class);
        hashOperations = mock(HashOperations.class);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.expire(anyString(), anyLong(), eq(TimeUnit.MINUTES))).thenReturn(true);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @Test
    void rejectsWrongCodeAllowsCorrectCodeAndRejectsReplay() {
        when(redisTemplate.execute(
                any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(0L, 1L, 0L);
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        Result wrong = service.login(loginForm("000000"));
        Result correct = service.login(loginForm("123456"));
        Result replay = service.login(loginForm("123456"));

        assertFalse(wrong.getSuccess());
        assertTrue(correct.getSuccess());
        assertNotNull(correct.getData());
        assertFalse(replay.getSuccess());
        verify(userMapper, times(1)).selectOne(any());
    }

    @Test
    void reloadsUserAfterConcurrentInsertWins() {
        User existing = existingUser();
        when(redisTemplate.execute(
                any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(1L);
        when(userMapper.selectOne(any())).thenReturn(null, existing);
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("duplicate phone"));

        Result result = service.login(loginForm("123456"));

        assertTrue(result.getSuccess());
        verify(userMapper, times(2)).selectOne(any());
    }

    @Test
    void removesTokenWhenExpirationCannotBeSet() {
        when(redisTemplate.execute(
                any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(1L);
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(redisTemplate.expire(anyString(), anyLong(), eq(TimeUnit.MINUTES))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.login(loginForm("123456")));
        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.startsWith(LOGIN_USER_KEY));
    }

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800138000");
        form.setCode(code);
        return form;
    }

    private User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setPhone("13800138000");
        user.setNickName("test-user");
        return user;
    }
}
