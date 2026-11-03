package com.cs6650.leaderless.controller;

import com.cs6650.leaderless.model.Shoppingcart;
import com.cs6650.leaderless.model.PropagateRequest;
import com.cs6650.leaderless.model.VersionedValue;
import com.cs6650.leaderless.service.ShoppingcartStore;
import com.cs6650.leaderless.service.PropagationService;
import com.cs6650.leaderless.util.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/**
 * This controller is a leaderless replication model where any node
 * can handle client requests. Writes are propagated to all peers,
 * and each node maintains versioned values for conflict resolution.
 */
@RestController
public class ShoppingcartDbController {

  private final ShoppingcartStore shoppingcartStore;
  private final PropagationService propagationService;

  @Value("${propagation.timeout.ms}")
  private int propagationTimeoutMs;

  private final Random random = new Random();

  /**
   * Constructs a new {@code LeaderlessController} with dependencies injected.
   *
   * @param shoppingcartStore   the key-value store handling local reads/writes
   * @param propagationService  the service responsible for propagating updates to peers
   */
  @Autowired
  public ShoppingcartDbController(ShoppingcartStore shoppingcartStore, PropagationService propagationService) {
    this.shoppingcartStore = shoppingcartStore;
    this.propagationService = propagationService;
  }

  /**
   * Simulates business logic processing time for this microservice endpoint.
   * @throws IllegalStateException if the thread is interrupted while sleeping
   */
  private void simulateDelay() {
    try {
      Thread.sleep(100 + random.nextInt(900)); // 100–1000ms
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Reads a shopping cart using quorum-based replication.
   *
   * It returns the versioned cart value that has the highest version among
   * the quorum responses.
   *
   * If the quorum agrees the cart does not exist, the method returns HTTP 404.
   * If quorum is not achieved within the timeout window, the method returns
   * HTTP 504 (Gateway Timeout), indicating that the system cannot guarantee
   * a consistent read at this moment.
   *
   * @param key the cart identifier (typically customer ID)
   * @return the shopping cart value with metadata (version, timestamp),
   *         404 if the cart does not exist, or 504 if quorum read fails
   * @throws InterruptedException if the read process is interrupted
   */
  @GetMapping("/get/{key}")
  public ResponseEntity<?> getItems(@PathVariable String key) throws InterruptedException {
    simulateDelay();

    Optional<VersionedValue> result;
    try {
      result = propagationService.readWithQuorum(key, propagationTimeoutMs);
    } catch (TimeoutException e) {
      return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
              .body("Quorum read failed: " + e.getMessage());
    }

    if (result.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    VersionedValue cart = result.get();
    return ResponseEntity.ok(
            Map.of(
                    "items", cart.getShoppingcart().getItems(),
                    "version", cart.getVersion(),
                    "timestamp", cart.getTimestamp()
            )
    );
  }

  /**
   * Writes or updates a shopping cart under a leaderless replication model.
   *
   * @param key the unique cart identifier (e.g., user or cart ID)
   * @param items the map of itemId → quantity to store in the cart
   * @return the updated cart value with version metadata, or an error if write quorum fails
   */
  @PostMapping("/item/{key}")
  public ResponseEntity<?> setItem(@PathVariable String key, @RequestBody HashMap<Integer, Integer> items) {
    simulateDelay();

    if (key == null || key.isEmpty()) {
      return ResponseEntity.badRequest().body("Key must not be empty");
    }

    Transaction.begin();

    VersionedValue existing = shoppingcartStore.getCart(key);
    Shoppingcart shoppingcart;

    if (existing == null) {
      shoppingcart = new Shoppingcart();
      shoppingcart.setCustomerId(key);
      shoppingcart.setItems(items);
    } else {
      shoppingcart = existing.getShoppingcart();
      shoppingcart.setItems(items);
    }

    long version = shoppingcartStore.nextVersion();
    shoppingcartStore.writeLocal(key, shoppingcart, version);

    boolean ok = propagationService.propagate(key, shoppingcart, version, propagationTimeoutMs);

    if (!ok) {
      Transaction.abort();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Failed to propagate to enough peers (W quorum not met)");
    }

    Transaction.commit();

    VersionedValue stored = shoppingcartStore.getCart(key);

    return ResponseEntity.status(HttpStatus.CREATED).body(
            Map.of(
                    "items", stored.getShoppingcart().getItems(),
                    "version", stored.getVersion(),
                    "timestamp", stored.getTimestamp()
            )
    );
  }

  /**
   * Handles propagation requests from peer nodes. (Call by other nodes.)
   * When a coordinator sends updates, each follower receives the propagated
   * value and updates its local store only if the received version is newer
   * than its current version. This ensures eventual consistency.
   *
   * @param request a JSON map containing "key", "product", and "version"
   * @return {@code 200 OK} once the propagation is processed
   * @throws InterruptedException if the artificial delay is interrupted
   */
  @PostMapping("/propagate")
  public ResponseEntity<?> propagate(@RequestBody PropagateRequest request) throws InterruptedException {

    simulateDelay();

    String key = request.getKey();
    Shoppingcart shoppingcart = request.getShoppingcart();
    long version = request.getVersion();

    // write if newer
    VersionedValue current = shoppingcartStore.getCart(key);
    if (current == null || version > current.getVersion()) {
      shoppingcartStore.putIfNewer(key, shoppingcart, version);
    }

    return ResponseEntity.ok().build();
  }

  /**
   * Returns the local value for a given key.
   * This endpoint is intended for debugging and testing.
   * It returns the stored value along with version and timestamp metadata.
   *
   * @param key the key to look up
   * @return {@code 200 OK} with local value details;
   *         {@code 404 Not Found} if the key does not exist locally
   */
  @GetMapping("/local_read/{key}")
  public ResponseEntity<?> localRead(@PathVariable String key) {
    simulateDelay();
    VersionedValue v = shoppingcartStore.getCart(key);
    if (v == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    return ResponseEntity.ok(Map.of("shoppingcart", v.getShoppingcart(), "version", v.getVersion(), "timestamp", v.getTimestamp()));
  }

}
