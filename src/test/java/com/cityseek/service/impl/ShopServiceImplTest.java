package com.cityseek.service.impl;

import com.cityseek.dto.request.ShopCreateRequest;
import com.cityseek.entity.Shop;
import com.cityseek.mapper.ShopMapper;
import com.cityseek.shop.ShopChangedEvent;
import com.cityseek.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceImplTest {

    @Test
    void initializesSystemStatisticsInsteadOfAcceptingClientValues() {
        ShopMapper mapper = mock(ShopMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ShopServiceImpl service = new ShopServiceImpl(
                mock(StringRedisTemplate.class), mock(CacheClient.class), publisher);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.insert(any(Shop.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Shop.class).setId(99L);
            return 1;
        });

        ShopCreateRequest request = new ShopCreateRequest();
        request.setName(" mapped shop ");
        request.setTypeId(1L);
        request.setImages(" /shop.jpg ");
        request.setAddress(" mapped address ");
        request.setX(-73.9);
        request.setY(-33.8);
        service.create(request);

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((Shop shop) -> {
            assertEquals("mapped shop", shop.getName());
            assertEquals(0, shop.getSold());
            assertEquals(0, shop.getComments());
            assertEquals(0, shop.getScore());
            return true;
        }));
        verify(publisher).publishEvent(any(ShopChangedEvent.class));
    }
}
