package com.cityseek.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderStatusDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    private String status;
    private Long voucherId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long persistedOrderId;
    private String error;
    private Long updatedAt;
}
