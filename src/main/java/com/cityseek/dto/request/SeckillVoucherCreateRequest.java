package com.cityseek.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SeckillVoucherCreateRequest extends VoucherCreateRequest {

    @NotNull(message = "库存不能为空")
    @Positive(message = "库存必须大于0")
    private Integer stock;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime beginTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @AssertTrue(message = "开始时间必须早于结束时间")
    public boolean isTimeRangeValid() {
        return beginTime == null || endTime == null || beginTime.isBefore(endTime);
    }
}
