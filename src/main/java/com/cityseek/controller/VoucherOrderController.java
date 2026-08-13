package com.cityseek.controller;


import com.cityseek.dto.Result;
import com.cityseek.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    private final IVoucherOrderService voucherOrderService;

    public VoucherOrderController(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        Result result = voucherOrderService.seckillVoucher(voucherId);
        if (Boolean.TRUE.equals(result.getSuccess()) && result.getData() instanceof Number orderId) {
            return Result.ok(orderId.toString());
        }
        return result;
    }

    @GetMapping("status/{id}")
    public Result queryOrderStatus(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }
}
