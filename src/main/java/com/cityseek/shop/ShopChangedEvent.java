package com.cityseek.shop;

import com.cityseek.entity.Shop;

public record ShopChangedEvent(Shop before, Shop after) {
}
