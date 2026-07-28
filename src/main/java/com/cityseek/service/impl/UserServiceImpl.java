package com.cityseek.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.LoginFormDTO;
import com.cityseek.dto.Result;
import com.cityseek.dto.UserDTO;
import com.cityseek.dto.UserProfileUpdateDTO;
import com.cityseek.entity.User;
import com.cityseek.entity.UserInfo;
import com.cityseek.mapper.UserMapper;
import com.cityseek.service.IUserInfoService;
import com.cityseek.service.IUserService;
import com.cityseek.utils.RegexUtils;
import com.cityseek.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.*;
import static com.cityseek.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DefaultRedisScript<Long> LOGIN_CODE_CONSUME_SCRIPT;

    static {
        LOGIN_CODE_CONSUME_SCRIPT = new DefaultRedisScript<>();
        LOGIN_CODE_CONSUME_SCRIPT.setLocation(new ClassPathResource("login-code-consume.lua"));
        LOGIN_CODE_CONSUME_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Override
    public Result sendCode(String phone) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        Boolean allowed = stringRedisTemplate.opsForValue().setIfAbsent(
                LOGIN_CODE_COOLDOWN_KEY + phone, "1", LOGIN_CODE_COOLDOWN_TTL, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            return Result.fail("请求过于频繁，请稍后再试");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.在 Redis 内原子校验并消费验证码，防止并发重放
        String code = loginForm.getCode();
        Long codeConsumed = stringRedisTemplate.execute(
                LOGIN_CODE_CONSUME_SCRIPT,
                List.of(LOGIN_CODE_KEY + phone),
                code
        );
        if (!Long.valueOf(1L).equals(codeConsumed)) {
            return Result.fail("验证码错误");
        }

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        Boolean expirationSet = stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(expirationSet)) {
            stringRedisTemplate.delete(tokenKey);
            throw new IllegalStateException("登录状态创建失败");
        }
        stringRedisTemplate.delete(LOGIN_CODE_COOLDOWN_KEY + phone);

        // 8.返回token
        return Result.ok(token);
    }

    @Override
    public Result logout(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Result.ok();
        }
        String token = authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : authorization.trim();
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result updateProfile(UserProfileUpdateDTO profile, String authorization) {
        UserDTO currentUser = UserHolder.getUser();
        Long userId = currentUser.getId();

        User user = new User()
                .setId(userId)
                .setNickName(profile.getNickName().trim());
        if (profile.getIcon() != null) {
            user.setIcon(profile.getIcon().trim());
        }
        if (!updateById(user)) {
            return Result.fail("用户不存在或资料更新失败");
        }

        UserInfo userInfo = new UserInfo()
                .setUserId(userId)
                .setIntroduce(profile.getIntroduce())
                .setCity(profile.getCity())
                .setGender(profile.getGender())
                .setBirthday(profile.getBirthday());
        if (!userInfoService.saveOrUpdate(userInfo)) {
            throw new IllegalStateException("用户详情保存失败");
        }

        currentUser.setNickName(user.getNickName());
        if (profile.getIcon() != null) {
            currentUser.setIcon(user.getIcon());
        }
        String token = normalizeToken(authorization);
        if (token != null && !token.isBlank()) {
            Map<String, String> tokenFields = new HashMap<>();
            tokenFields.put("nickName", currentUser.getNickName());
            tokenFields.put("icon", currentUser.getIcon() == null ? "" : currentUser.getIcon());
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, tokenFields);
        }
        return Result.ok(currentUser);
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        try {
            if (!save(user)) {
                throw new IllegalStateException("用户创建失败");
            }
            return user;
        } catch (DuplicateKeyException exception) {
            User existing = query().eq("phone", phone).one();
            if (existing != null) {
                return existing;
            }
            throw exception;
        }
    }

    private String normalizeToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return authorization;
        }
        return authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : authorization.trim();
    }
}
