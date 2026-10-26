package com.cs6650.shoppingcartservice.controller;

import com.cs6650.shoppingcartservice.model.ShoppingCartModel;
import com.cs6650.shoppingcartservice.service.DelaySimulatorService;


import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/shopping-cart")
public class ShoppingCartController {

    private final ShoppingCartModel model;
    private final DelaySimulatorService delayService;

    public ShoppingCartController(ShoppingCartModel model, DelaySimulatorService delayService) {
        this.model = model;
        this.delayService = delayService;
    }

    /**
     * Get cartId or generate a new one
     * e.g. GET /shopping-cart/by-customer/{customerId}
     * Response: {"cartId":"1234567890"}
     */
    @GetMapping("/by-customer/{customerId}")
    public Map<String, String> getOrCreateCartId(@PathVariable String customerId) {
        // simulate delay before DB access
        delayService.simulateDelay();

        String existingCartId = model.fetchCartIdFromDb(customerId);
        System.out.println(existingCartId);

        if (existingCartId != null && !existingCartId.isEmpty()) {
            return Map.of("cartId", existingCartId);
        }

        String newCartId = model.createAndPersistCartId(customerId);
        return Map.of("cartId", newCartId);
    }

    /**
     * Add items to the cart
     * e.g. POST /shopping-cart/{cartId}/items?itemId=1&quantity=3
     * 201 Created
     */
    @PostMapping("/{cartId}/items")
    public ResponseEntity<Map<Integer, Integer>> addToCart(@PathVariable String cartId,
                                                           @RequestParam int itemId,
                                                           @RequestParam int quantity) {
        // simulate delay before DB access
        delayService.simulateLogNormalDelay();

        Map<Integer, Integer> updatedItems = model.addToCart(cartId, itemId, quantity);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "/shopping-cart/" + cartId + "/items");

        return new ResponseEntity<>(updatedItems, headers, HttpStatus.CREATED);
    }

    /**
     * Get all items in the cart
     * e.g. GET /shopping-cart/{cartId}/itemsWithId
     */
    @GetMapping("/{cartId}/itemsWithId")
    public String getCartIdAndItems(@PathVariable String cartId) {
        return model.getCartIdAndItems(cartId);
    }

    /**
     * checkout
     * e.g. POST /shopping-cart/{cartId}/checkout?creditCardNumber=4111-1111-1111-1111
     */
    @PostMapping("/{cartId}/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void checkout(@PathVariable String cartId,
                         @RequestParam String creditCardNumber) {
        model.checkout(cartId, creditCardNumber);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleInternalErrors(IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "An internal error occurred: " + ex.getMessage()));
    }
}
