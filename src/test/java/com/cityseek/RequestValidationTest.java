package com.cityseek;

import com.cityseek.dto.request.BlogCreateRequest;
import com.cityseek.dto.request.SeckillVoucherCreateRequest;
import com.cityseek.dto.request.ShopCreateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsSignedCoordinatesAndRejectsOutOfRangeCoordinates() {
        ShopCreateRequest request = validShop();
        request.setX(-73.9857);
        request.setY(-33.8);
        assertTrue(validator.validate(request).isEmpty());

        request.setX(-180.1);
        assertFalse(validator.validate(request).isEmpty());
        request.setX(120.0);
        request.setY(90.1);
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsInvalidBlogLengthsAndSeckillTimeRange() {
        BlogCreateRequest blog = new BlogCreateRequest();
        blog.setShopId(1L);
        blog.setTitle(" ");
        blog.setImages("/blogs/1.png");
        blog.setContent("content");
        assertFalse(validator.validate(blog).isEmpty());

        SeckillVoucherCreateRequest voucher = new SeckillVoucherCreateRequest();
        voucher.setShopId(1L);
        voucher.setTitle("券");
        voucher.setPayValue(100L);
        voucher.setActualValue(200L);
        voucher.setStock(1);
        voucher.setBeginTime(LocalDateTime.now().plusHours(2));
        voucher.setEndTime(LocalDateTime.now().plusHours(1));
        assertFalse(validator.validate(voucher).isEmpty());
    }

    private ShopCreateRequest validShop() {
        ShopCreateRequest request = new ShopCreateRequest();
        request.setName("测试商铺");
        request.setTypeId(1L);
        request.setImages("/shop.jpg");
        request.setAddress("测试地址");
        request.setX(120.0);
        request.setY(30.0);
        return request;
    }
}
