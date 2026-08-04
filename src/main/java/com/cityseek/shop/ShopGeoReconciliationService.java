package com.cityseek.shop;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cityseek.entity.Shop;
import com.cityseek.mapper.ShopMapper;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.cityseek.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopGeoReconciliationService {

    private static final int PAGE_SIZE = 500;
    private static final StringRedisSerializer SERIALIZER = StringRedisSerializer.UTF_8;

    private final ShopMapper shopMapper;
    private final StringRedisTemplate redisTemplate;

    public ShopGeoReconciliationService(ShopMapper shopMapper, StringRedisTemplate redisTemplate) {
        this.shopMapper = shopMapper;
        this.redisTemplate = redisTemplate;
    }

    public ReconciliationReport rebuild() {
        List<String> staleKeys = scanGeoKeys();
        if (!staleKeys.isEmpty()) {
            redisTemplate.delete(staleKeys);
        }

        long rebuilt = 0;
        long pageNumber = 1;
        while (true) {
            Page<Shop> page = shopMapper.selectPage(
                    new Page<>(pageNumber, PAGE_SIZE, false),
                    new QueryWrapper<Shop>().orderByAsc("id"));
            List<Shop> shops = page.getRecords().stream()
                    .filter(this::hasGeoData)
                    .toList();
            writeBatch(shops);
            rebuilt += shops.size();
            if (page.getRecords().size() < PAGE_SIZE) {
                break;
            }
            pageNumber++;
        }
        return new ReconciliationReport(rebuilt, staleKeys.size());
    }

    private List<String> scanGeoKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(SHOP_GEO_KEY + "*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private void writeBatch(List<Shop> shops) {
        if (shops.isEmpty()) {
            return;
        }
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Shop shop : shops) {
                connection.geoCommands().geoAdd(
                        SERIALIZER.serialize(SHOP_GEO_KEY + shop.getTypeId()),
                        new Point(shop.getX(), shop.getY()),
                        SERIALIZER.serialize(shop.getId().toString()));
            }
            return null;
        });
    }

    private boolean hasGeoData(Shop shop) {
        return shop.getId() != null && shop.getTypeId() != null
                && shop.getX() != null && shop.getY() != null;
    }

    public record ReconciliationReport(long rebuiltShops, long removedGeoKeys) {
    }
}
