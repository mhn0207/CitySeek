package com.cityseek.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.entity.SeckillVoucher;
import com.cityseek.entity.Voucher;
import com.cityseek.mapper.VoucherMapper;
import com.cityseek.service.ISeckillVoucherService;
import com.cityseek.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.cityseek.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher == null || voucher.getStock() == null || voucher.getStock() < 1
                || voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            throw new IllegalArgumentException("秒杀券参数不合法");
        }
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀信息保存失败");
        }

        String voucherId = voucher.getId().toString();
        long beginTime = voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTime = voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, voucher.getStock().toString());
        stringRedisTemplate.opsForValue().set(SECKILL_BEGIN_KEY + voucherId, Long.toString(beginTime));
        stringRedisTemplate.opsForValue().set(SECKILL_END_KEY + voucherId, Long.toString(endTime));
    }
}
