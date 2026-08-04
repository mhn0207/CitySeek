package com.cityseek.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.SeckillVoucherCreateRequest;
import com.cityseek.dto.request.VoucherCreateRequest;
import com.cityseek.entity.SeckillVoucher;
import com.cityseek.entity.Voucher;
import com.cityseek.mapper.VoucherMapper;
import com.cityseek.service.ISeckillVoucherService;
import com.cityseek.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.List;

import static com.cityseek.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_END_KEY;
import static com.cityseek.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate redisTemplate;

    public VoucherServiceImpl(
            ISeckillVoucherService seckillVoucherService,
            StringRedisTemplate redisTemplate) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public Long createVoucher(VoucherCreateRequest request) {
        Voucher voucher = mapVoucher(request, 0);
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }
        return voucher.getId();
    }

    @Override
    @Transactional
    public Long createSeckillVoucher(SeckillVoucherCreateRequest request) {
        Voucher voucher = mapVoucher(request, 1);
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(voucher.getId())
                .setStock(request.getStock())
                .setBeginTime(request.getBeginTime())
                .setEndTime(request.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀信息保存失败");
        }
        synchronizeSeckillAfterCommit(voucher.getId(), request);
        return voucher.getId();
    }

    private Voucher mapVoucher(VoucherCreateRequest request, int type) {
        return new Voucher()
                .setShopId(request.getShopId())
                .setTitle(request.getTitle().trim())
                .setSubTitle(trimToNull(request.getSubTitle()))
                .setRules(trimToNull(request.getRules()))
                .setPayValue(request.getPayValue())
                .setActualValue(request.getActualValue())
                .setType(type)
                .setStatus(1);
    }

    private void synchronizeSeckillAfterCommit(Long voucherId, SeckillVoucherCreateRequest request) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String id = voucherId.toString();
                long begin = request.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long end = request.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                redisTemplate.opsForValue().set(SECKILL_STOCK_KEY + id, request.getStock().toString());
                redisTemplate.opsForValue().set(SECKILL_BEGIN_KEY + id, Long.toString(begin));
                redisTemplate.opsForValue().set(SECKILL_END_KEY + id, Long.toString(end));
            }
        });
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
