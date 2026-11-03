package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Shoppingcart;
import com.cs6650.leaderless.model.VersionedValue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Service responsible for propagating key-value updates among peer nodes
 * in a leaderless replication setup.
 */
@Service
public class PropagationService {

    /** HTTP client used for sending POST requests to peer nodes. */
    private final RestTemplate rest = new RestTemplate();

    @Value("${propagation.write.quorum}")
    private int propagationWriteQuorum;

    @Value("${propagation.read.quorum}")
    private int propagationReadQuorum;

    /**
     * List of peer node of comma separated URLs participating in replication.
     * Example:
     * -- GETTER --
     * Returns the list of peer node URLs.
     *
     * {@code http://localhost:8081,http://localhost:8082,http://localhost:8083}
     * 
     * @return list of peers
     */
    @Getter
    @Setter
    public List<String> peers;

    /**
     * The URL of this node itself, used to avoid sending propagation to self.
     * -- GETTER --
     * Returns the URL of this current node.
     *
     * @return self URL
     */
    @Getter
    @Setter
    public String selfAddress;

    /** Thread pool executor used to send propagation requests concurrently. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Maximum retry time when propagation fail. */
    @Value("${max.retries}")
    private int maxRetries;

    private final ShoppingcartStore shoppingcartStore;

    /**
     * Constructs a {@link PropagationService} that handles replication to peer
     * nodes.
     *
     * @param peerList comma-separated list of peer URLs from application properties
     *                 (e.g. {@code http://localhost:8081,http://localhost:8082})
     * @param selfUrl  the URL of this current node, used to skip self during
     *                 propagation
     */
    @Autowired
    public PropagationService(
            @Value("${peers:}") String peerList,
            @Value("${self.url:}") String selfUrl,
            ShoppingcartStore shoppingcartStore, ShoppingcartStore shoppingcartStore1){
        this.peers = Arrays.stream(peerList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        this.selfAddress = selfUrl;
        this.shoppingcartStore = shoppingcartStore1;
    }

    /**
     * Performs a quorum-based read (R) across all replicas in the leaderless system.
     *
     * A replica that answers "not found" still counts toward the quorum, so a cart
     * that does not exist is reported as empty instead of as a failure.
     * If quorum cannot be achieved within the timeout window, the method throws
     * {@link TimeoutException}, allowing the controller to respond with 504 Gateway Timeout.
     *
     * @param key the key being read (shopping cart ID)
     * @param timeoutMs maximum time allowed to gather quorum responses
     * @return the newest {@link VersionedValue} observed among quorum responses,
     *         or empty if the quorum agrees the cart does not exist
     * @throws TimeoutException if quorum was not reached
     */
    public Optional<VersionedValue> readWithQuorum(String key, long timeoutMs) throws TimeoutException {

        int quorum = this.propagationReadQuorum;

        List<String> otherPeers = peers.stream()
                .filter(peer -> !peer.equalsIgnoreCase(selfAddress))
                .toList();

        // Each task returns the replica's value, Optional.empty() if the replica
        // does not have the key, or null if the replica could not be reached
        List<Callable<Optional<VersionedValue>>> tasks = new ArrayList<>();

        tasks.add(() -> Optional.ofNullable(shoppingcartStore.getCart(key)));

        for (String peer : otherPeers) {
            tasks.add(() -> {
                try {
                    String url = peer + "/local_read/" + key;
                    ResponseEntity<Map> resp = rest.getForEntity(url, Map.class);

                    if (!resp.getStatusCode().is2xxSuccessful()) return null;
                    if (resp.getBody() == null) return null;

                    Map<String, Object> body = resp.getBody();
                    Map<String, Object> cartMap = (Map<String, Object>) body.get("shoppingcart");
                    if (cartMap == null) return null;

                    Shoppingcart sc = new Shoppingcart();
                    sc.setCustomerId((String) cartMap.get("customerId"));

                    Map<String, Integer> rawItems = (Map<String, Integer>) cartMap.get("items");
                    HashMap<Integer, Integer> items = new HashMap<>();

                    if (rawItems != null) {
                        for (Map.Entry<String, Integer> entry : rawItems.entrySet()) {
                            try {
                                items.put(Integer.valueOf(entry.getKey()), entry.getValue());
                            } catch (NumberFormatException ignore) {}
                        }
                    }

                    sc.setItems(items);

                    long version = ((Number) body.get("version")).longValue();
                    long timestamp = ((Number) body.get("timestamp")).longValue();

                    return Optional.of(new VersionedValue(sc, version, timestamp));

                } catch (HttpClientErrorException.NotFound e) {
                    return Optional.empty();
                } catch (Exception e) {
                    return null;
                }
            });
        }

        ExecutorService exec = Executors.newCachedThreadPool();
        List<Future<Optional<VersionedValue>>> futures = new ArrayList<>();
        for (Callable<Optional<VersionedValue>> t : tasks) futures.add(exec.submit(t));

        long deadline = System.currentTimeMillis() + timeoutMs;
        int success = 0;
        VersionedValue best = null;

        for (Future<Optional<VersionedValue>> f : futures) {
            long timeout = Math.max(1, deadline - System.currentTimeMillis());
            Optional<VersionedValue> result = null;

            try {
                result = f.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {}

            if (result != null) {
                success++;

                if (result.isPresent() && (best == null || result.get().getVersion() > best.getVersion())) {
                    best = result.get();
                }

                if (success >= quorum) {
                    exec.shutdownNow();
                    return Optional.ofNullable(best);
                }
            }
        }

        exec.shutdownNow();
        throw new TimeoutException("Only " + success + " of " + quorum + " replicas responded");
    }

    /**
     * Propagates a key-value update to all peer nodes and waits for quorum
     * acknowledgements
     *
     * @param key                the key being updated
     * @param product            the new value associated with the key
     * @param version            the version number of this update
     * @param propagateTimeoutMs timeout in milliseconds to wait for acknowledgements
     * @return {@code true} if at least 3 peers acknowledge the write; {@code false} otherwise
     */
    public boolean propagate(String key, Shoppingcart product, long version, long propagateTimeoutMs) {

        // Filter out self so we only propagate to other peers
        List<String> otherPeers = peers.stream()
                .filter(peer -> !peer.equalsIgnoreCase(selfAddress))
                .toList();

        // If there are no other peers, the write is trivially successful
        if (otherPeers.isEmpty()) {
            return true;
        }

        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (String peer : otherPeers) {
            tasks.add(() -> {
                String url = peer + "/propagate";

                Map<String, Object> payload = new HashMap<>();
                payload.put("key", key);
                payload.put("shoppingcart", product);
                payload.put("version", version);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);

                boolean success = false;

                for (int i = 0; i <= this.maxRetries && !success; i++) {
                    try {
                        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);
                        success = resp.getStatusCode().is2xxSuccessful();

                        if (!success && i < maxRetries) {
                            Thread.sleep(100);
                        }

                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    } catch (Exception e) {
                        System.err.println("Error propagating to " + peer + ": " + e.getMessage());
                    }

                    if (!success && i < maxRetries) {
                        Thread.sleep(100);
                    }
                }

                System.out.println("Peer " + peer + " propagation " + (success ? "succeeded" : "failed"));
                return success;
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(task));
            }

            long deadline = System.currentTimeMillis() + propagateTimeoutMs;

            // The coordinator has already written locally, which counts toward W
            int successCount = 1;
            if (successCount >= propagationWriteQuorum) {
                return true;
            }

            for (Future<Boolean> future : futures) {
                long timeout = Math.max(1, deadline - System.currentTimeMillis());
                boolean ok = false;
                try {
                    ok = !future.isCancelled() && future.get(timeout, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    ok = false;
                }

                if (ok) {
                    successCount++;
                    if (successCount >= propagationWriteQuorum) {
                        futures.forEach(f -> f.cancel(true));  // cancel remaining tasks (optional)
                        return true;
                    }
                }
            }
            return false;

        } catch (Exception e) {
            System.err.println("Error during propagation: " + e.getMessage());
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            return false;
        }
    }
}