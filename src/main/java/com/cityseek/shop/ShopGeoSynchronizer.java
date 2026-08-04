package com.cityseek.shop;

import com.cityseek.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

import static com.cityseek.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.cityseek.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Component
public class ShopGeoSynchronizer {

    private final StringRedisTemplate redisTemplate;

    public ShopGeoSynchronizer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShopChanged(ShopChangedEvent event) {
        Shop before = event.before();
        Shop after = event.after();
        try {
            redisTemplate.delete(CACHE_SHOP_KEY + after.getId());
            boolean typeChanged = before != null
                    && !Objects.equals(before.getTypeId(), after.getTypeId());
            boolean coordinatesChanged = before == null
                    || !Objects.equals(before.getX(), after.getX())
                    || !Objects.equals(before.getY(), after.getY());
            if (typeChanged) {
                redisTemplate.opsForGeo().remove(
                        SHOP_GEO_KEY + before.getTypeId(), after.getId().toString());
            }
            if (before == null || typeChanged || coordinatesChanged) {
                redisTemplate.opsForGeo().add(
                        SHOP_GEO_KEY + after.getTypeId(),
                        new Point(after.getX(), after.getY()),
                        after.getId().toString());
            }
        } catch (RuntimeException exception) {
            log.error("Shop GEO synchronization failed after commit; run reconciliation for shop {}",
                    after.getId(), exception);
        }
    }
}
