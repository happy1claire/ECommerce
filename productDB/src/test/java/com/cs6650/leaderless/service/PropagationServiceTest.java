package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests the W = N write propagation against a 3-node cluster where this node is node1
 * and the two peers are mocked over HTTP.
 */
class PropagationServiceTest {

  private static final String SELF = "http://node1";

  private PropagationService service;
  private MockRestServiceServer peers;

  @BeforeEach
  void setUp() {
    service = new PropagationService(SELF + ",http://node2,http://node3", SELF);
    ReflectionTestUtils.setField(service, "maxRetries", 0);

    RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(service, "rest");
    peers = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
  }

  @Test
  void propagateToAll_allPeersAck_succeedsAndSkipsSelf() {
    peers.expect(requestTo("http://node2/propagate"))
        .andExpect(jsonPath("$.key").value(1))
        .andExpect(jsonPath("$.version").value(42))
        .andRespond(withSuccess());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withSuccess());

    assertThat(service.propagateToAll(1, product(1), 42, 2000)).isTrue();
    peers.verify();
  }

  @Test
  void propagateToAll_onePeerFails_failsWholeWrite() {
    peers.expect(requestTo("http://node2/propagate")).andRespond(withSuccess());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withServerError());

    assertThat(service.propagateToAll(1, product(1), 42, 2000)).isFalse();
  }

  @Test
  void propagateToAll_retriesFailedPeer() {
    ReflectionTestUtils.setField(service, "maxRetries", 2);
    peers.expect(requestTo("http://node2/propagate")).andRespond(withSuccess());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withServerError());
    peers.expect(requestTo("http://node3/propagate")).andRespond(withSuccess());

    assertThat(service.propagateToAll(1, product(1), 42, 5000)).isTrue();
    peers.verify();
  }

  @Test
  void propagateToAll_singleNode_succeedsWithoutCallingPeers() {
    PropagationService single = new PropagationService(SELF, SELF);

    assertThat(single.propagateToAll(1, product(1), 42, 2000)).isTrue();
  }

  private static Product product(int id) {
    Product p = new Product();
    p.setId(id);
    p.setSku("ABC-123");
    return p;
  }
}
