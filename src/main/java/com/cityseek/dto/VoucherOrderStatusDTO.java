package com.cityseek.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderStatusDTO {

    private Long orderId;
    private String status;
    private Long voucherId;
    private Long persistedOrderId;
    private String error;
    private Long updatedAt;
}
