package com.cs6650.leaderless.controller;

import com.cs6650.leaderless.model.Product;
import com.cs6650.leaderless.model.PropagateRequest;
import com.cs6650.leaderless.model.VersionedValue;
import com.cs6650.leaderless.service.ProductStore;
import com.cs6650.leaderless.service.PropagationService;
import com.cs6650.leaderless.util.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Random;

/**
 * This controller is a leaderless replication model where any node
 * can handle client requests. Writes are propagated to all peers,
 * and each node maintains versioned values for conflict resolution.
 */
@RestController
public class ProductDbController {

  private final ProductStore productStorage;
  private final PropagationService propagationService;

  @Value("${propagation.timeout.ms}")
  private int propagationTimeoutMs;

  private final Random random = new Random();
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
   * Constructs a new {@code LeaderlessController} with dependencies injected.
   *
   * @param productStorage      the key-value store handling local reads/writes
   * @param propagationService  the service responsible for propagating updates to peers
   */
  @Autowired
  public ProductDbController(ProductStore productStorage, PropagationService propagationService) {
    this.productStorage = productStorage;
    this.propagationService = propagationService;
  }

  /**
   * Handles client write requests. (Call by clients)
   * The receiving node becomes the coordinator for this write:
   * it updates its local store with a new versioned value,
   * and then propagates the update to all peer nodes.
   *
   * @param product a JSON map containing "key" and "value" fields
   * @return {@code 201 Created} if successfully written and propagated to all peers;
   *         {@code 400 Bad Request} if the key is missing;
   *         {@code 500 Internal Server Error} if propagation fails
   */
  @PostMapping("/product")
  public ResponseEntity<?> addProduct(@RequestBody Product product) {

    simulateDelay();

    Integer key = product.getId();

    Transaction.begin();

    // increment version and write locally
    long version = productStorage.nextVersion();
    productStorage.writeLocal(key, product, version);

    // propagate to peers (W = N)
    boolean ok = propagationService.propagateToAll(key, product, version, propagationTimeoutMs);

    if (ok) {
      Transaction.commit();
      VersionedValue stored = productStorage.get(key);
      return ResponseEntity.status(HttpStatus.CREATED).body(
              Map.of(
                      "product", stored.getProduct(),
                      "version", stored.getVersion(),
                      "timestamp", stored.getTimestamp()
              ));
    } else {
      Transaction.abort();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Failed to propagate to all peers");
    }
  }

  /**
   * Updates an existing product by ID.
   * Behaves like a write: increments version, writes locally, then propagates (W = N).
   *
   * @param id the product ID to update
   * @param product the updated product body
   * @return 200 OK with updated version info, or 404 if not found
   */
  @PutMapping("/product/{id}")
  public ResponseEntity<?> updateProduct(@PathVariable Integer id, @RequestBody Product product) {
    simulateDelay();

    Transaction.begin();

    if (id == null) {
      Transaction.abort();
      return ResponseEntity.badRequest().body("ID must not be empty");
    }

    // Ensure ID matches payload
    product.setId(id);

    VersionedValue existing = productStorage.get(id);
    if (existing == null) {
      Transaction.abort();
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Product not found");
    }

    long version = productStorage.nextVersion();
    productStorage.writeLocal(id, product, version);

    boolean ok = propagationService.propagateToAll(id, product, version, propagationTimeoutMs);

    if (ok) {
      Transaction.commit();
      VersionedValue stored = productStorage.get(id);
      return ResponseEntity.ok(
          Map.of(
              "product", stored.getProduct(),
              "version", stored.getVersion(),
              "timestamp", stored.getTimestamp()
          )
      );
    } else {
      Transaction.abort();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Failed to propagate to all peers");
    }
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
    Integer key = request.getKey();
    Product product = request.getProduct();
    long version = request.getVersion();

    // Reject malformed replication messages (null key/value, negative or absurdly large version)
    if (key == null || product == null || !ProductStore.isValidRemoteVersion(version)) {
      return ResponseEntity.badRequest().body("Invalid propagate request: key, product and a valid version are required");
    }

    simulateDelay();

    // write if newer
    VersionedValue current = productStorage.get(key);
    if (current == null || version > current.getVersion()) {
      productStorage.putIfNewer(key, product, version);
    }

    return ResponseEntity.ok().build();
  }

  /**
   * Handles client read requests.
   * Performs a local read (R=1) on this node and returns the value and version.
   * Reads may reflect slightly stale data
   * depending on propagation delays.
   *
   * @param key the key to retrieve
   * @return {@code 200 OK} with the current value and version;
   *         {@code 404 Not Found} if the key does not exist
   * @throws InterruptedException if future read delays are simulated
   */
  @GetMapping("/get/{key}")
  public ResponseEntity<?> lookUpProduct(@PathVariable Integer key) throws InterruptedException {
    simulateDelay();
    VersionedValue v = productStorage.get(key);
    if (v == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

    // For simulation, follower read delay is not necessary here since this is leaderless,
    // but you can insert a small sleep if you want to create larger inconsistency windows.
    return ResponseEntity.ok(
        Map.of(
            "product", v.getProduct(),
            "version", v.getVersion(),
            "timestamp", v.getTimestamp()
        )
    );
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
  public ResponseEntity<?> localRead(@PathVariable Integer key) {
    simulateDelay();
    VersionedValue v = productStorage.get(key);
    if (v == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    return ResponseEntity.ok(Map.of("product", v.getProduct(), "version", v.getVersion(), "timestamp", v.getTimestamp()));
  }

}
