package com.cityseek.controller;

import com.cityseek.dto.Result;
import com.cityseek.dto.VoucherOrderStatusDTO;
import com.cityseek.service.IVoucherOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherOrderControllerTest {

    private static final long LARGE_ORDER_ID = 625_000_000_000_000_001L;

    @Test
    void returnsAcceptedOrderIdAsStringToPreserveJavascriptPrecision() {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        when(service.seckillVoucher(1L)).thenReturn(Result.ok(LARGE_ORDER_ID));
        VoucherOrderController controller = new VoucherOrderController(service);

        Result response = controller.seckillVoucher(1L);

        assertEquals(Long.toString(LARGE_ORDER_ID), response.getData());
    }

    @Test
    void serializesStatusOrderIdsAsStrings() throws Exception {
        VoucherOrderStatusDTO status = new VoucherOrderStatusDTO(
                LARGE_ORDER_ID, "SUCCESS", 1L, LARGE_ORDER_ID, null, 1L);

        String json = new ObjectMapper().writeValueAsString(status);

        assertTrue(json.contains("\"orderId\":\"" + LARGE_ORDER_ID + "\""));
        assertTrue(json.contains("\"persistedOrderId\":\"" + LARGE_ORDER_ID + "\""));
    }
}
