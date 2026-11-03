package com.cs6650.shoppingcartservice.controller;

import com.cs6650.shoppingcartservice.model.ShoppingCartModel;
import com.cs6650.shoppingcartservice.service.DelaySimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the HTTP contract of the shopping cart API: status codes and response bodies.
 */
@WebMvcTest(ShoppingCartController.class)
class ShoppingCartControllerTest {

    private static final String CARD = "1234-5678-9012-3456";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ShoppingCartModel model;

    @MockitoBean
    private DelaySimulatorService delayService;

    @Test
    void getCartId_existingCustomer_returnsExistingCart() throws Exception {
        when(model.fetchCartIdFromDb("42")).thenReturn("cart-1");

        mvc.perform(get("/shopping-cart/by-customer/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value("cart-1"));
        verify(model, never()).createAndPersistCartId(anyString());
    }

    @Test
    void getCartId_newCustomer_createsCart() throws Exception {
        when(model.fetchCartIdFromDb("42")).thenReturn(null);
        when(model.createAndPersistCartId("42")).thenReturn("cart-new");

        mvc.perform(get("/shopping-cart/by-customer/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value("cart-new"));
    }

    @Test
    void getCartId_customerDbUnavailable_returns500AndDoesNotCreateCart() throws Exception {
        when(model.fetchCartIdFromDb("42")).thenThrow(new IllegalStateException("Failed to read cartId"));

        mvc.perform(get("/shopping-cart/by-customer/42")).andExpect(status().isInternalServerError());
        verify(model, never()).createAndPersistCartId(anyString());
    }

    @Test
    void addItem_returns201WithLocation() throws Exception {
        when(model.addToCart("cart-1", 7, 2)).thenReturn(Map.of(7, 2));

        mvc.perform(post("/shopping-cart/cart-1/items").param("itemId", "7").param("quantity", "2"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/shopping-cart/cart-1/items"))
                .andExpect(jsonPath("$.7").value(2));
    }

    @Test
    void checkout_success_returns202() throws Exception {
        mvc.perform(post("/shopping-cart/cart-1/checkout").param("creditCardNumber", CARD))
                .andExpect(status().isAccepted());
        verify(model).checkout("cart-1", CARD);
    }

    @Test
    void checkout_paymentDeclined_returns402() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment was declined."))
                .when(model).checkout("cart-1", CARD);

        mvc.perform(post("/shopping-cart/cart-1/checkout").param("creditCardNumber", CARD))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void checkout_emptyCart_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Cart is empty for cartId: cart-1"))
                .when(model).checkout("cart-1", CARD);

        mvc.perform(post("/shopping-cart/cart-1/checkout").param("creditCardNumber", CARD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cart is empty for cartId: cart-1"));
    }

    @Test
    void checkout_dbFailure_returns500() throws Exception {
        doThrow(new IllegalStateException("Failed to save cart items to Shoppingcart DB"))
                .when(model).checkout("cart-1", CARD);

        mvc.perform(post("/shopping-cart/cart-1/checkout").param("creditCardNumber", CARD))
                .andExpect(status().isInternalServerError());
    }
}
