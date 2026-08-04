package com.cityseek.controller;

import com.cityseek.dto.Result;
import com.cityseek.dto.request.SeckillVoucherCreateRequest;
import com.cityseek.dto.request.VoucherCreateRequest;
import com.cityseek.security.RequireRole;
import com.cityseek.security.UserRole;
import com.cityseek.service.IVoucherService;
import com.cityseek.utils.PublicEndpoint;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher")
public class VoucherController {

    private final IVoucherService voucherService;

    public VoucherController(IVoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @PostMapping("/seckill")
    @RequireRole(UserRole.ADMIN)
    public Result addSeckillVoucher(@Valid @RequestBody SeckillVoucherCreateRequest request) {
        return Result.ok(voucherService.createSeckillVoucher(request));
    }

    @PostMapping
    @RequireRole(UserRole.ADMIN)
    public Result addVoucher(@Valid @RequestBody VoucherCreateRequest request) {
        return Result.ok(voucherService.createVoucher(request));
    }

    @GetMapping("/list/{shopId}")
    @PublicEndpoint
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }
}
