package com.cityseek.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.cityseek.dto.UserDTO;
import com.cityseek.security.UserRole;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.LOGIN_USER_KEY;
import static com.cityseek.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = normalizeToken(request.getHeader("authorization"));
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        userDTO.setRole(parseRole(userMap.get("role")));
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }

    private UserRole parseRole(Object value) {
        if (value == null) {
            return UserRole.USER;
        }
        try {
            return UserRole.valueOf(value.toString());
        } catch (IllegalArgumentException exception) {
            return UserRole.USER;
        }
    }

    private String normalizeToken(String authorization) {
        if (StrUtil.isBlank(authorization)) {
            return authorization;
        }
        return authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : authorization.trim();
    }
}
