package com.cityseek.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.ShopCreateRequest;
import com.cityseek.dto.request.ShopUpdateRequest;
import com.cityseek.entity.Shop;

public interface IShopService extends IService<Shop> {

    Result create(ShopCreateRequest request);

    Result queryById(Long id);

    Result update(ShopUpdateRequest request);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
