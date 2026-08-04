package com.cityseek.integration;

import com.cityseek.dto.request.ShopCreateRequest;
import com.cityseek.dto.request.ShopUpdateRequest;
import com.cityseek.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.cityseek.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.cityseek.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ShopGeoIntegrationTest extends ContainerIntegrationTest {

    @Autowired
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void movesShopToNewGeoTypeAfterDatabaseCommit() {
        ShopCreateRequest create = new ShopCreateRequest();
        create.setName("geo-integration-shop");
        create.setTypeId(1L);
        create.setImages("/shop.jpg");
        create.setAddress("geo address");
        create.setX(-73.9857);
        create.setY(-33.8);
        long shopId = ((Number) shopService.create(create).getData()).longValue();
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + shopId, "cached");

        ShopUpdateRequest update = new ShopUpdateRequest();
        update.setId(shopId);
        update.setTypeId(2L);
        update.setX(120.12);
        update.setY(30.28);
        shopService.update(update);

        List<Point> oldPosition = redisTemplate.opsForGeo()
                .position(SHOP_GEO_KEY + 1, Long.toString(shopId));
        List<Point> newPosition = redisTemplate.opsForGeo()
                .position(SHOP_GEO_KEY + 2, Long.toString(shopId));
        assertNull(oldPosition.get(0));
        assertEquals(120.12, newPosition.get(0).getX(), 0.0001);
        assertEquals(30.28, newPosition.get(0).getY(), 0.0001);
        assertNull(redisTemplate.opsForValue().get(CACHE_SHOP_KEY + shopId));
    }
}
