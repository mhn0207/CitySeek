package com.cityseek.config;

import com.cityseek.entity.Follow;
import com.cityseek.entity.SeckillVoucher;
import com.cityseek.entity.VoucherOrder;
import com.cityseek.service.IFollowService;
import com.cityseek.service.ISeckillVoucherService;
import com.cityseek.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

import static com.cityseek.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_END_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.redis", name = "initialize-cache", havingValue = "true", matchIfMissing = true)
public class RedisDataInitializer implements ApplicationRunner {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void run(ApplicationArguments args) {
        initializeFollows();
        initializeSeckillVouchers();
        initializeVoucherOrders();
        log.info("Redis derived data initialization completed");
    }

    private void initializeFollows() {
        for (Follow follow : followService.list()) {
            stringRedisTemplate.opsForSet().add(
                    "follows:" + follow.getUserId(), follow.getFollowUserId().toString());
        }
    }

    private void initializeSeckillVouchers() {
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            String voucherId = voucher.getVoucherId().toString();
            stringRedisTemplate.opsForValue().setIfAbsent(
                    SECKILL_STOCK_KEY + voucherId, voucher.getStock().toString());
            if (voucher.getBeginTime() != null) {
                long begin = voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                stringRedisTemplate.opsForValue().set(SECKILL_BEGIN_KEY + voucherId, Long.toString(begin));
            }
            if (voucher.getEndTime() != null) {
                long end = voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                stringRedisTemplate.opsForValue().set(SECKILL_END_KEY + voucherId, Long.toString(end));
            }
        }
    }

    private void initializeVoucherOrders() {
        for (VoucherOrder order : voucherOrderService.list()) {
            stringRedisTemplate.opsForSet().add(
                    SECKILL_ORDER_KEY + order.getVoucherId(), order.getUserId().toString());
        }
    }
}
