package com.cs6650.shoppingcartservice.model;

import com.cs6650.shoppingcartservice.service.CreditCardAuthService;
import com.cs6650.shoppingcartservice.service.WarehouseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class ShoppingCartModel {

    // customerId -> cartId
    private final Map<String, String> cartIdByCustomer = new ConcurrentHashMap<>();
    // cartId -> (itemId -> quantity)
    private final Map<String, Map<String, Integer>> cartItems = new ConcurrentHashMap<>();
    // Randomly generate IDs
    private static final SecureRandom RAND = new SecureRandom();
    // ObjectMapper to convert our order object to a JSON string
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CreditCardAuthService authService;
    private final WarehouseService warehouseService;

    @Value("${customer.db.base-url:http://localhost:8080}")
    private String customerDbBaseUrl;

    @Value("${shoppingCart.db.base-url:http://localhost:8080}")
    private String shoppingCartDbBaseUrl;


    /**
     * RestTemplate for making HTTP calls to the Customer DB nodes.
     */
    private final RestTemplate restTemplate = new RestTemplate();
    /**
     * Constructor injection ensures that WarehouseService and CreditCardAuthService are provided.
     *
     */
    public ShoppingCartModel(WarehouseService warehouseService, CreditCardAuthService authService) {
        this.warehouseService = warehouseService;
        this.authService = authService;
    }

    /**
     * Add items with quantities to the cart
     */
    public Map<Integer, Integer> addToCart(String cartId, int itemId, int quantity) {
        if (cartId == null) {
            throw new IllegalArgumentException("cartId must not be null");
        }
        if (quantity < 1 || quantity > 10_000) {
            throw new IllegalArgumentException("quantity must be between 1 and 10000");
        }

        // Load existing items from DB: Map<itemId, quantity>
        Map<Integer, Integer> items = loadCartItemsFromDb(cartId);
        System.out.println("original existing items: " + items);

        // Merge quantity (add to existing if present)
        items.merge(itemId, quantity, Integer::sum);
        System.out.println("items after merge: " + items);

        // Save back to DB and return the updated items
        return saveCartItemsToDb(cartId, items);
    }


    /**
     * Randomly generate a customer ID.
     */
    public String getCustomerId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Get the items in the cart.
     * Returns an immutable empty map if cart does not exist.
     */
    public Map<Integer, Integer> getCartItems(String cartId) {
        return loadCartItemsFromDb(cartId);
    }

    /**
     * Get cartId and cartItems.
     */
    public String getCartIdAndItems(String cartId) {
        return getCartIdAndItems(cartId, getCartItems(cartId));
    }

    private String getCartIdAndItems(String cartId, Map<Integer, Integer> itemsMap) {
        List<Map<String, Object>> itemList = itemsMap.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> itemAsMap = new HashMap<>();
                    itemAsMap.put("productId", entry.getKey());   // integer
                    itemAsMap.put("quantity", entry.getValue());
                    return itemAsMap;
                })
                .collect(Collectors.toList());

        Map<String, Object> resultStructure = new HashMap<>();
        resultStructure.put("cartId", cartId);
        resultStructure.put("items", itemList);

        try {
            return objectMapper.writeValueAsString(resultStructure);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting cart data to JSON", e);
        }
    }

    /**
     * After User clicks checkout:
     * - call CreditCardAuthorizer
     * - enqueue a ship request to the Warehouse queue
     */
    public void checkout(String cartId, String creditCardNumber) {
        if (cartId == null) {
            throw new IllegalArgumentException("cartId must not be null");
        }

        // 1. Retrieve items from the cart using the DB-backed method
        Map<Integer, Integer> items = getCartItems(cartId);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty for cartId: " + cartId);
        }

        // 2. Credit card number format check: credit card must match the format 1234-5678-9012-3456
        if (!creditCardNumber.matches("\\d{4}-\\d{4}-\\d{4}-\\d{4}")) {
            throw new IllegalArgumentException("creditCardNumber format invalid");
        }

        // 3. Credit Card Authorization
        authService.authorize(creditCardNumber);

        // 4. Payment approved: enqueue ship request (cartId + items) to the Warehouse queue
        warehouseService.ship(getCartIdAndItems(cartId, items));
    }


    /**
     * Reads the cartId for a given customer from the Customer DB.
     *
     * It calls the leaderless customer service:
     *   GET {customerDbBaseUrl}/get/{customerId}
     *
     * Expected response body:
     *   {
     *     "shoppingCartsIds": ["cartId1", "cartId2", ...],
     *     "version": ...,
     *     "timestamp": ...
     *   }
     *
     * This method:
     *   - Returns the first cartId in "shoppingCartsIds" if present.
     *   - Returns null if the customer has no cartIds in the DB
     *     or if the record does not exist.
     */
    public String fetchCartIdFromDb(String customerId) {
        try {
            String url = customerDbBaseUrl + "/get/" + customerId;
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }

            Object idsObj = response.getBody().get("shoppingCartsIds");
            if (idsObj instanceof List<?> ids && !ids.isEmpty()) {
                // Use the first cartId in the list
                Object first = ids.get(0);
                return first != null ? first.toString() : null;
            }

            return null;
        } catch (HttpClientErrorException.NotFound e) {
            // The customer record does not exist in the DB
            return null;
        } catch (Exception e) {
            // For simplicity, treat any error as "no cartId in DB"
            // You may want to log or rethrow depending on your needs
            return null;
        }
    }

    /**
     * Generates a new cartId for the given customer, writes it to the Customer DB,
     * and returns the new cartId.
     *
     * Steps:
     * 1. Generate a new cartId.
     * 2. Build a list of cartIds (currently just one).
     * 3. Call the leaderless customer service:
     *    POST {customerDbBaseUrl}/customer/{customerId}
     *    Body: ["newCartId"]
     * 4. Optionally cache the mapping in memory.
     */
    public String createAndPersistCartId(String customerId) {
        // Generate a new cartId (you can replace this with your existing logic)
        String newCartId = UUID.randomUUID().toString();

        // Persist to Customer DB
        try {
            String url = customerDbBaseUrl + "/customer/" + customerId;
            System.out.println(url);
            List<String> body = List.of(newCartId);
            restTemplate.postForEntity(url, body, Void.class);
        } catch (Exception e) {
            // You may want to throw an IllegalStateException here,
            // so that the controller can translate it into a 500.
            throw new IllegalStateException("Failed to persist cartId to Customer DB", e);
        }

        // Optionally store the mapping in the local map as a cache
        cartIdByCustomer.put(customerId, newCartId);

        return newCartId;
    }

    // =========================
    //  Shoppingcart DB Helpers
    // =========================

    /**
     * Loads cart items from the leaderless Shoppingcart DB for a given cartId.
     *
     * It calls:
     *   GET {shoppingCartDbBaseUrl}/get/{cartId}
     *
     * Expected response:
     *   {
     *     "items": { "1": 2, "3": 5 },
     *     "version": ...,
     *     "timestamp": ...
     *   }
     *
     * @param cartId the cart identifier
     * @return a mutable map of productId -> quantity; empty map if not found or on errors
     */
    private Map<Integer, Integer> loadCartItemsFromDb(String cartId) {
        try {
            String url = shoppingCartDbBaseUrl + "/get/" + cartId;
            ShoppingCartDbResponse response =
                    restTemplate.getForObject(url, ShoppingCartDbResponse.class);

            if (response == null || response.getItems() == null) {
                return new HashMap<>();
            }
            return new HashMap<>(response.getItems());
        } catch (HttpClientErrorException.NotFound e) {
            // Cart record does not exist in the DB
            return new HashMap<>();
        } catch (Exception e) {
            // For simplicity, treat any error as "no items"
            return new HashMap<>();
        }
    }

    /**
     * Persists cart items into the leaderless Shoppingcart DB for a given cartId.
     *
     * It calls:
     *   POST {shoppingCartDbBaseUrl}/item/{cartId}
     * Body:
     *   { "1": 2, "3": 5 }
     *
     * The DB returns the stored value including version and timestamp.
     *
     * @param cartId the cart identifier
     * @param items  map of productId -> quantity to be stored
     * @return the items actually stored in DB (may be the same as input)
     */
    private Map<Integer, Integer> saveCartItemsToDb(String cartId, Map<Integer, Integer> items) {
        try {
            String url = shoppingCartDbBaseUrl + "/item/" + cartId;
            System.out.println("items passed to saveCartItemsToDb: " + items);

            ShoppingCartDbResponse response =
                    restTemplate.postForObject(url, items, ShoppingCartDbResponse.class);
            System.out.println("response: " + response);

            if (response != null && response.getItems() != null) {
                return new HashMap<>(response.getItems());
            }
            return new HashMap<>(items);
        } catch (Exception e) {
            // In a real system you would log and propagate a proper error.
            throw new IllegalStateException("Failed to save cart items to Shoppingcart DB", e);
        }
    }
}

