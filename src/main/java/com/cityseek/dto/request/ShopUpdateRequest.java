package com.cityseek.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopUpdateRequest {

    @NotNull(message = "商铺编号不能为空")
    private Long id;

    @Size(max = 128, message = "商铺名称不能超过128个字符")
    private String name;

    private Long typeId;

    @Size(max = 1024, message = "商铺图片地址不能超过1024个字符")
    private String images;

    @Size(max = 128, message = "商圈不能超过128个字符")
    private String area;

    @Size(max = 255, message = "商铺地址不能超过255个字符")
    private String address;

    @DecimalMin(value = "-180.0", message = "经度不能小于-180")
    @DecimalMax(value = "180.0", message = "经度不能大于180")
    private Double x;

    @DecimalMin(value = "-90.0", message = "纬度不能小于-90")
    @DecimalMax(value = "90.0", message = "纬度不能大于90")
    private Double y;

    @PositiveOrZero(message = "均价不能为负数")
    private Long avgPrice;

    @Size(max = 32, message = "营业时间不能超过32个字符")
    private String openHours;

    @AssertTrue(message = "可修改的文本字段不能为空白")
    public boolean isTextValid() {
        return notBlankWhenPresent(name)
                && notBlankWhenPresent(images)
                && notBlankWhenPresent(address);
    }

    @AssertTrue(message = "至少提供一个要修改的字段")
    public boolean isChangePresent() {
        return name != null || typeId != null || images != null || area != null
                || address != null || x != null || y != null || avgPrice != null || openHours != null;
    }

    private boolean notBlankWhenPresent(String value) {
        return value == null || !value.isBlank();
    }
}
