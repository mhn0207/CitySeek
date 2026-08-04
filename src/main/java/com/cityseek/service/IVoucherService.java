package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.SeckillVoucherCreateRequest;
import com.cityseek.dto.request.VoucherCreateRequest;
import com.cityseek.entity.Voucher;

public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    Long createVoucher(VoucherCreateRequest request);

    Long createSeckillVoucher(SeckillVoucherCreateRequest request);
}
