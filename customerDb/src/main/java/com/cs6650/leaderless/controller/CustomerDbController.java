package com.cs6650.leaderless.controller;

import com.cs6650.leaderless.model.Customer;
import com.cs6650.leaderless.model.PropagateRequest;
import com.cs6650.leaderless.model.VersionedValue;
import com.cs6650.leaderless.service.CustomerStore;
import com.cs6650.leaderless.service.PropagationService;
import com.cs6650.leaderless.util.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/**
 * A leaderless-replication Customer database controller.
 *
 * Any node can serve read/write requests. Writes are propagated to all peers,
 * and each replica stores versioned values to resolve conflicts using
 * last-write-wins semantics.
 */
@RestController
public class CustomerDbController {

  private final CustomerStore customerStore;
  private final PropagationService propagationService;

  @Value("${propagation.timeout.ms}")
  private int propagationTimeoutMs;

  private final Random random = new Random();

  /**
   * Constructs a new CustomerDbController with injected dependencies.
   *
   * @param customerStore        the local key-value store for Customer records
   * @param propagationService   the service responsible for propagating writes to peer nodes
   */
  @Autowired
  public CustomerDbController(CustomerStore customerStore, PropagationService propagationService) {
    this.customerStore = customerStore;
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
   * Performs a quorum-based read for a Customer record.
   *
   * The coordinator contacts peer replicas and waits for enough responses
   * to satisfy the read quorum. It selects the value with the highest
   * version across all responses.
   *
   * If the quorum agrees the customer does not exist, HTTP 404 is returned.
   * If quorum is not reached within the timeout window, HTTP 504 is returned.
   *
   * @param key the customerId
   * @return the resolved Customer value with version + timestamp metadata,
   *         404 if the customer does not exist, or 504 if quorum cannot be achieved
   */
  @GetMapping("/get/{key}")
  public ResponseEntity<?> getCustomer(@PathVariable String key) throws InterruptedException {
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

    VersionedValue customer = result.get();
    return ResponseEntity.ok(
            Map.of(
                    "shoppingCartsIds", customer.getCustomer().getShoppingcartIds(),
                    "version", customer.getVersion(),
                    "timestamp", customer.getTimestamp()
            )
    );
  }

  /**
   * Creates or updates a Customer record using leaderless replication.
   *
   * The write is first applied locally, then propagated to other replicas.
   * If write quorum cannot be satisfied, the write is aborted and an error is returned.
   *
   * @param key      the customerId
   * @param cartIds  the list of shoppingCartIds associated with the customer
   * @return metadata about the stored value (version, timestamp), or an error if quorum fails
   */
  @PostMapping("/customer/{key}")
  public ResponseEntity<?> setCartId(@PathVariable String key, @RequestBody List<String> cartIds) {
    simulateDelay();
    if (key == null || key.isEmpty()) {
      return ResponseEntity.badRequest().body("Key must not be empty");
    }

    Transaction.begin();

    VersionedValue existing = customerStore.getCustomer(key);
    Customer customer;

    if (existing == null) {
      customer = new Customer();
      customer.setCustomerId(key);
      customer.setShoppingcartIds(cartIds);
    } else {
      customer = existing.getCustomer();
      customer.setShoppingcartIds(cartIds);
    }

    long version = customerStore.nextVersion();
    customerStore.writeLocal(key, customer, version);

    boolean ok = propagationService.propagate(key, customer, version, propagationTimeoutMs);

    if (!ok) {
      Transaction.abort();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Failed to propagate to enough peers (W quorum not met)");
    }

    Transaction.commit();

    VersionedValue stored = customerStore.getCustomer(key);

    return ResponseEntity.status(HttpStatus.CREATED).body(
            Map.of(
                    "shoppingCartsIds", stored.getCustomer().getShoppingcartIds(),
                    "version", stored.getVersion(),
                    "timestamp", stored.getTimestamp()
            )
    );
  }

  /**
   * Handles write propagation requests coming from peer replicas.
   *
   * A follower node will update its local store only if the incoming version
   * is newer than the current stored version. This ensures eventual consistency
   * across all replicas.
   *
   * @param request contains { key, customer, version }
   * @return 200 OK once propagation is applied
   */
  @PostMapping("/propagate")
  public ResponseEntity<?> propagate(@RequestBody PropagateRequest request) throws InterruptedException {
    String key = request.getKey();
    Customer customer = request.getCustomer();
    long version = request.getVersion();

    // Reject malformed replication messages (null key/value, negative or absurdly large version)
    if (key == null || customer == null || !CustomerStore.isValidRemoteVersion(version)) {
      return ResponseEntity.badRequest().body("Invalid propagate request: key, customer and a valid version are required");
    }

    simulateDelay();

    VersionedValue current = customerStore.getCustomer(key);
    if (current == null || version > current.getVersion()) {
      customerStore.putIfNewer(key, customer, version);
    }

    return ResponseEntity.ok().build();
  }

  /**
   * Returns the value stored *locally* at this replica, without quorum.
   *
   * This is useful for debugging and ensuring that propagation is occurring
   * correctly across nodes.
   *
   * @param key the customerId
   * @return the local Customer value with version + timestamp, or 404 if not found
   */
  @GetMapping("/local_read/{key}")
  public ResponseEntity<?> localRead(@PathVariable String key) {
    simulateDelay();
    VersionedValue v = customerStore.getCustomer(key);
    if (v == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    return ResponseEntity.ok(Map.of("customer", v.getCustomer(), "version", v.getVersion(), "timestamp", v.getTimestamp()));
  }

}