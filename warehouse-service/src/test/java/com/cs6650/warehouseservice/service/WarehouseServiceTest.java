package com.cs6650.warehouseservice.service;

import com.cs6650.warehouseservice.dto.ShipRequest;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WarehouseServiceTest {

    private static final String SHIP_ENDPOINT = "http://warehouse/warehouse/ship";

    private RestTemplate restTemplate;
    private Channel channel;
    private WarehouseService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        channel = mock(Channel.class);
        service = new WarehouseService(restTemplate);
        ReflectionTestUtils.setField(service, "shipEndpoint", SHIP_ENDPOINT);
    }

    @Test
    void validOrder_shipsEachItemAndAcks() throws Exception {
        String order = """
                {"cartId": "cart-1", "items": [{"productId": 7, "quantity": 2}, {"productId": 9, "quantity": 1}]}
                """;

        service.handleOrderMessage(order, channel, 5L);

        ArgumentCaptor<ShipRequest> shipped = ArgumentCaptor.forClass(ShipRequest.class);
        verify(restTemplate, times(2)).postForEntity(eq(SHIP_ENDPOINT), shipped.capture(), eq(String.class));
        assertThat(shipped.getAllValues())
                .extracting(ShipRequest::getProductId, ShipRequest::getQuantity)
                .containsExactly(tuple(7, 2), tuple(9, 1));
        verify(channel).basicAck(5L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void malformedMessage_isNackedWithoutRequeue() throws Exception {
        service.handleOrderMessage("not json", channel, 6L);

        verify(channel).basicNack(6L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(restTemplate);
    }
}
