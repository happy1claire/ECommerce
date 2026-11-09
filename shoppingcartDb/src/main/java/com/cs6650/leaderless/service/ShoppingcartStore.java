package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Shoppingcart;
import com.cs6650.leaderless.model.VersionedValue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class ShoppingcartStore {
  /** ConcurrentHashMap<String CartId, VersionedValue>*/
  private final ConcurrentHashMap<String, VersionedValue> carts = new ConcurrentHashMap<>();

  private final AtomicLong versionCounter = new AtomicLong(0);
  private final int nodeId;

  /**
   * Largest Lamport counter accepted from another node: 2^40 (about 1.1 trillion writes).
   * Far above any real write count, but far below 2^47, where (counter << 16) in
   * nextVersion() would overflow into negative versions. Without this bound, a single
   * /propagate request with version Long.MAX_VALUE pins a record forever and makes
   * every later version on this node negative.
   */
  public static final long MAX_REMOTE_COUNTER = 1L << 40;

  /** A replicated version must be non-negative and its counter part must be within bounds. */
  public static boolean isValidRemoteVersion(long version) {
    return version >= 0 && (version >>> 16) <= MAX_REMOTE_COUNTER;
  }

  public ShoppingcartStore(@Value("${server.port}") int port) {
    // A simple way to get a unique node ID from the port (e.g., 8081 -> 81)
    // This assumes ports are in a reasonable range where this is unique.
    this.nodeId = port % 1000;
  }

  // increment and return new version
  public long nextVersion() {
    long counter = versionCounter.incrementAndGet();
    // Combine the counter with the node ID to create a unique, sortable version.
    // Use top 48 bits for counter, bottom 16 for node ID.
    return (counter << 16) | nodeId;
  }

  public VersionedValue getCart(String key) {
    return carts.get(key);
  }

  public void putIfNewer(String key, Shoppingcart shoppingcart, long version) throws InterruptedException {
    if (shoppingcart == null || !isValidRemoteVersion(version)) {
      throw new IllegalArgumentException("Rejected replicated write: null value or out-of-range version " + version);
    }
    // Extract counter from incoming version
    long incomingCounter = version >>> 16;

    // Raise local counter if remote counter is higher (Lamport merge)
    versionCounter.updateAndGet(cur -> Math.max(cur, incomingCounter));

    // Accept only if version is newer
    carts.compute(key, (k, existing) -> {
      if (existing == null || version > existing.getVersion()) {
        return new VersionedValue(shoppingcart, version, System.currentTimeMillis());
      } else {
        return existing;
      }
    });
  }

  // local immediate write (Coordinator writes first)
  public void writeLocal(String key, Shoppingcart shoppingcart, long version) {
    carts.put(key, new VersionedValue(shoppingcart, version, System.currentTimeMillis()));
  }
}
