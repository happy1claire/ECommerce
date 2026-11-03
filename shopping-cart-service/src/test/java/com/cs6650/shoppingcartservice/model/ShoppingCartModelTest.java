package com.cs6650.shoppingcartservice.model;

import com.cs6650.shoppingcartservice.service.CreditCardAuthService;
import com.cs6650.shoppingcartservice.service.WarehouseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests ShoppingCartModel against mocked Customer DB / Shoppingcart DB HTTP endpoints,
 * with the credit card authorizer and the warehouse queue mocked out.
 */
class ShoppingCartModelTest {

    private static final String CUSTOMER_DB = "http://customer-db";
    private static final String CART_DB = "http://cart-db";
    private static final String CARD = "1234-5678-9012-3456";

    private WarehouseService warehouseService;
    private CreditCardAuthService authService;
    private ShoppingCartModel model;
    private MockRestServiceServer db;

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        authService = mock(CreditCardAuthService.class);
        model = new ShoppingCartModel(warehouseService, authService);
        ReflectionTestUtils.setField(model, "customerDbBaseUrl", CUSTOMER_DB);
        ReflectionTestUtils.setField(model, "shoppingCartDbBaseUrl", CART_DB);

        RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(model, "restTemplate");
        db = MockRestServiceServer.bindTo(rest).build();
    }

    // ---------- get or create cartId ----------

    @Test
    void fetchCartId_existingCustomer_returnsFirstCartId() {
        db.expect(requestTo(CUSTOMER_DB + "/get/42"))
                .andRespond(withSuccess("{\"shoppingCartsIds\": [\"cart-1\", \"cart-2\"]}", MediaType.APPLICATION_JSON));

        assertThat(model.fetchCartIdFromDb("42")).isEqualTo("cart-1");
    }

    @Test
    void fetchCartId_unknownCustomer_returnsNull() {
        db.expect(requestTo(CUSTOMER_DB + "/get/42")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(model.fetchCartIdFromDb("42")).isNull();
    }

    @Test
    void fetchCartId_dbUnavailable_throwsInsteadOfCreatingDuplicateCart() {
        db.expect(requestTo(CUSTOMER_DB + "/get/42")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> model.fetchCartIdFromDb("42")).isInstanceOf(IllegalStateException.class);
    }

    // ---------- add to cart ----------

    @Test
    void addToCart_newCart_savesItem() {
        expectCartRead(HttpStatus.NOT_FOUND);
        expectCartWrite("{\"7\": 2}");

        assertThat(model.addToCart("cart-1", 7, 2)).containsExactly(entry(7, 2));
        db.verify();
    }

    @Test
    void addToCart_existingItem_mergesQuantity() {
        expectCartRead("{\"7\": 2}");
        expectCartWrite("{\"7\": 5}");

        assertThat(model.addToCart("cart-1", 7, 3)).containsExactly(entry(7, 5));
        db.verify();
    }

    @Test
    void addToCart_dbReadFails_doesNotOverwriteCart() {
        expectCartRead(HttpStatus.GATEWAY_TIMEOUT);

        // Before the fix the failed read was treated as an empty cart and the
        // existing items were overwritten by {7: 2}
        assertThatThrownBy(() -> model.addToCart("cart-1", 7, 2)).isInstanceOf(IllegalStateException.class);
        db.verify();
    }

    @Test
    void addToCart_invalidQuantity_isRejected() {
        assertThatThrownBy(() -> model.addToCart("cart-1", 7, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- checkout ----------

    @Test
    void checkout_success_clearsCartBeforeEnqueueingOrder() throws Exception {
        expectCartRead("{\"7\": 2}");
        expectCartWrite("{}");
        // By the time the order is enqueued, the cart must already be emptied
        doAnswer(invocation -> {
            db.verify();
            return null;
        }).when(warehouseService).ship(anyString());

        model.checkout("cart-1", CARD);

        verify(authService).authorize(CARD);
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        verify(warehouseService).ship(order.capture());
        JsonNode json = new ObjectMapper().readTree(order.getValue());
        assertThat(json.path("cartId").asText()).isEqualTo("cart-1");
        assertThat(json.path("items").get(0).path("productId").asInt()).isEqualTo(7);
        assertThat(json.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void checkout_paymentDeclined_keepsCartAndDoesNotShip() {
        expectCartRead("{\"7\": 2}");
        doThrow(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment was declined."))
                .when(authService).authorize(CARD);

        assertThatThrownBy(() -> model.checkout("cart-1", CARD)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(warehouseService);
        db.verify();
    }

    @Test
    void checkout_clearingCartFails_doesNotShip() {
        expectCartRead("{\"7\": 2}");
        db.expect(requestTo(CART_DB + "/item/cart-1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> model.checkout("cart-1", CARD)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(warehouseService);
    }

    @Test
    void checkout_enqueueFails_restoresCartItems() {
        expectCartRead("{\"7\": 2}");
        expectCartWrite("{}");
        expectCartWrite("{\"7\": 2}");
        doThrow(new IllegalStateException("Failed to enqueue ship request"))
                .when(warehouseService).ship(anyString());

        assertThatThrownBy(() -> model.checkout("cart-1", CARD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enqueue");
        db.verify();
    }

    @Test
    void checkout_emptyCart_isRejected() {
        expectCartRead(HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> model.checkout("cart-1", CARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cart is empty");
        verifyNoInteractions(authService, warehouseService);
    }

    @Test
    void checkout_invalidCardFormat_isRejectedBeforeAuthorization() {
        expectCartRead("{\"7\": 2}");

        assertThatThrownBy(() -> model.checkout("cart-1", "4111"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authService, warehouseService);
    }

    // ---------- helpers ----------

    private void expectCartRead(String itemsJson) {
        db.expect(requestTo(CART_DB + "/get/cart-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(dbResponse(itemsJson), MediaType.APPLICATION_JSON));
    }

    private void expectCartRead(HttpStatus status) {
        db.expect(requestTo(CART_DB + "/get/cart-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(status));
    }

    private void expectCartWrite(String itemsJson) {
        db.expect(requestTo(CART_DB + "/item/cart-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(itemsJson, JsonCompareMode.STRICT))
                .andRespond(withSuccess(dbResponse(itemsJson), MediaType.APPLICATION_JSON));
    }

    private static String dbResponse(String itemsJson) {
        return "{\"items\": " + itemsJson + ", \"version\": 1, \"timestamp\": 1}";
    }
}
