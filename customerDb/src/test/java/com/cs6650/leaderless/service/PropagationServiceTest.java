package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Customer;
import com.cs6650.leaderless.model.VersionedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests the quorum read (R = 3) and write (W = 3) against a 3-node cluster where
 * this node is node1 and the two peers are mocked over HTTP.
 */
class PropagationServiceTest {

  private static final String SELF = "http://node1";

  private CustomerStore store;
  private PropagationService service;
  private MockRestServiceServer peers;

  @BeforeEach
  void setUp() {
    store = new CustomerStore(8081);
    service = new PropagationService(SELF + ",http://node2,http://node3", SELF, store);
    ReflectionTestUtils.setField(service, "propagationReadQuorum", 3);
    ReflectionTestUtils.setField(service, "propagationWriteQuorum", 3);
    ReflectionTestUtils.setField(service, "maxRetries", 0);

    RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(service, "rest");
    peers = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
  }

  @Test
  void readWithQuorum_keyMissingOnAllReplicas_returnsEmptyInsteadOfFailing() throws Exception {
    peers.expect(requestTo("http://node2/local_read/c1")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    peers.expect(requestTo("http://node3/local_read/c1")).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(service.readWithQuorum("c1", 2000)).isEmpty();
    peers.verify();
  }

  @Test
  void readWithQuorum_returnsNewestVersionAcrossReplicas() throws Exception {
    store.writeLocal("c1", customer("c1", "old-cart"), 1L << 16);
    peers.expect(requestTo("http://node2/local_read/c1"))
        .andRespond(withSuccess(localReadJson("c1", "new-cart", 5L << 16), MediaType.APPLICATION_JSON));
    peers.expect(requestTo("http://node3/local_read/c1")).andRespond(withStatus(HttpStatus.NOT_FOUND));

    Optional<VersionedValue> result = service.readWithQuorum("c1", 2000);

    assertThat(result).isPresent();
    assertThat(result.get().getVersion()).isEqualTo(5L << 16);
    assertThat(result.get().getCustomer().getShoppingcartIds()).containsExactly("new-cart");
  }

  @Test
  void readWithQuorum_notEnoughReplicasRespond_throwsTimeout() {
    peers.expect(requestTo("http://node2/local_read/c1")).andRespond(withServerError());
    peers.expect(requestTo("http://node3/local_read/c1")).andRespond(withServerError());

    assertThatThrownBy(() -> service.readWithQuorum("c1", 2000))
        .isInstanceOf(TimeoutException.class)
        .hasMessageContaining("1 of 3");
  }

  @Test
  void propagate_coordinatorPlusTwoPeers_meetsWriteQuorumOfThree() {
    peers.expect(requestTo("http://node2/propagate")).andRespond(withSuccess());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withSuccess());

    // Before the fix the coordinator's own write was not counted, so W = 3
    // could never be met in a 3-node cluster
    assertThat(service.propagate("c1", customer("c1", "cart-1"), 1L << 16, 2000)).isTrue();
  }

  @Test
  void propagate_coordinatorPlusOnePeer_failsWriteQuorumOfThree() {
    peers.expect(requestTo("http://node2/propagate")).andRespond(withSuccess());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withServerError());

    assertThat(service.propagate("c1", customer("c1", "cart-1"), 1L << 16, 2000)).isFalse();
  }

  private static Customer customer(String id, String cartId) {
    Customer c = new Customer();
    c.setCustomerId(id);
    c.setShoppingcartIds(List.of(cartId));
    return c;
  }

  private static String localReadJson(String id, String cartId, long version) {
    return """
        {"customer": {"customerId": "%s", "shoppingcartIds": ["%s"]}, "version": %d, "timestamp": 1}
        """.formatted(id, cartId, version);
  }
}
