package com.cityseek.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.ShopCreateRequest;
import com.cityseek.dto.request.ShopUpdateRequest;
import com.cityseek.entity.Shop;
import com.cityseek.security.RequireRole;
import com.cityseek.security.UserRole;
import com.cityseek.service.IShopService;
import com.cityseek.shop.ShopGeoReconciliationService;
import com.cityseek.utils.PublicEndpoint;
import com.cityseek.utils.SystemConstants;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop")
public class ShopController {

    private final IShopService shopService;
    private final ShopGeoReconciliationService reconciliationService;

    public ShopController(IShopService shopService, ShopGeoReconciliationService reconciliationService) {
        this.shopService = shopService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/{id}")
    @PublicEndpoint
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    @PostMapping
    @RequireRole(UserRole.ADMIN)
    public Result saveShop(@Valid @RequestBody ShopCreateRequest request) {
        return shopService.create(request);
    }

    @PutMapping
    @RequireRole(UserRole.ADMIN)
    public Result updateShop(@Valid @RequestBody ShopUpdateRequest request) {
        return shopService.update(request);
    }

    @PostMapping("/geo/reconcile")
    @RequireRole(UserRole.ADMIN)
    public Result reconcileGeo() {
        return Result.ok(reconciliationService.rebuild());
    }

    @GetMapping("/of/type")
    @PublicEndpoint
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    @GetMapping("/of/name")
    @PublicEndpoint
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
