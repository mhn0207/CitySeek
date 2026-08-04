package com.cityseek.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VoucherCreateRequest {

    @NotNull(message = "商铺不能为空")
    private Long shopId;

    @NotBlank(message = "优惠券标题不能为空")
    @Size(max = 255, message = "优惠券标题不能超过255个字符")
    private String title;

    @Size(max = 255, message = "副标题不能超过255个字符")
    private String subTitle;

    @Size(max = 1024, message = "使用规则不能超过1024个字符")
    private String rules;

    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须大于0")
    private Long payValue;

    @NotNull(message = "抵扣金额不能为空")
    @Positive(message = "抵扣金额必须大于0")
    private Long actualValue;
}
