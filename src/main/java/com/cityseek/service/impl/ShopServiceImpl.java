package com.cityseek.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cityseek.dto.Result;
import com.cityseek.dto.request.ShopCreateRequest;
import com.cityseek.dto.request.ShopUpdateRequest;
import com.cityseek.entity.Shop;
import com.cityseek.exception.ResourceNotFoundException;
import com.cityseek.mapper.ShopMapper;
import com.cityseek.service.IShopService;
import com.cityseek.shop.ShopChangedEvent;
import com.cityseek.utils.CacheClient;
import com.cityseek.utils.SystemConstants;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.geo.Distance;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.cityseek.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.cityseek.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate redisTemplate;
    private final CacheClient cacheClient;
    private final ApplicationEventPublisher eventPublisher;

    public ShopServiceImpl(
            StringRedisTemplate redisTemplate,
            CacheClient cacheClient,
            ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.cacheClient = cacheClient;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop == null ? Result.fail("店铺不存在！") : Result.ok(shop);
    }

    @Override
    @Transactional
    public Result create(ShopCreateRequest request) {
        Shop shop = new Shop()
                .setName(request.getName().trim())
                .setTypeId(request.getTypeId())
                .setImages(request.getImages().trim())
                .setArea(trimToNull(request.getArea()))
                .setAddress(request.getAddress().trim())
                .setX(request.getX())
                .setY(request.getY())
                .setAvgPrice(request.getAvgPrice())
                .setOpenHours(trimToNull(request.getOpenHours()))
                .setSold(0)
                .setComments(0)
                .setScore(0);
        if (!save(shop)) {
            throw new IllegalStateException("店铺保存失败");
        }
        eventPublisher.publishEvent(new ShopChangedEvent(null, copyShop(shop)));
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(ShopUpdateRequest request) {
        Shop before = getById(request.getId());
        if (before == null) {
            throw new ResourceNotFoundException("店铺不存在");
        }
        Shop patch = new Shop().setId(request.getId());
        if (request.getName() != null) {
            patch.setName(request.getName().trim());
        }
        if (request.getTypeId() != null) {
            patch.setTypeId(request.getTypeId());
        }
        if (request.getImages() != null) {
            patch.setImages(request.getImages().trim());
        }
        if (request.getArea() != null) {
            patch.setArea(request.getArea().trim());
        }
        if (request.getAddress() != null) {
            patch.setAddress(request.getAddress().trim());
        }
        if (request.getX() != null) {
            patch.setX(request.getX());
        }
        if (request.getY() != null) {
            patch.setY(request.getY());
        }
        if (request.getAvgPrice() != null) {
            patch.setAvgPrice(request.getAvgPrice());
        }
        if (request.getOpenHours() != null) {
            patch.setOpenHours(request.getOpenHours().trim());
        }
        if (!updateById(patch)) {
            throw new IllegalStateException("店铺更新失败");
        }
        Shop after = getById(request.getId());
        if (after == null) {
            throw new IllegalStateException("店铺更新后无法读取");
        }
        eventPublisher.publishEvent(new ShopChangedEvent(copyShop(before), copyShop(after)));
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || current == null || current < 1) {
            return Result.fail("分页参数不合法");
        }
        if (x == null || y == null) {
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        var results = redisTemplate.opsForGeo().search(
                SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>();
        Map<String, Distance> distanceMap = new HashMap<>();
        results.getContent().stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });
        String idList = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idList + ")").list();
        shops.forEach(shop -> shop.setDistance(distanceMap.get(shop.getId().toString()).getValue()));
        return Result.ok(shops);
    }

    private Shop copyShop(Shop source) {
        return new Shop()
                .setId(source.getId())
                .setName(source.getName())
                .setTypeId(source.getTypeId())
                .setImages(source.getImages())
                .setArea(source.getArea())
                .setAddress(source.getAddress())
                .setX(source.getX())
                .setY(source.getY())
                .setAvgPrice(source.getAvgPrice())
                .setSold(source.getSold())
                .setComments(source.getComments())
                .setScore(source.getScore())
                .setOpenHours(source.getOpenHours())
                .setCreateTime(source.getCreateTime())
                .setUpdateTime(source.getUpdateTime());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
