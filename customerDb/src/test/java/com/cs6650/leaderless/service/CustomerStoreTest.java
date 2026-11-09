package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Customer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerStoreTest {

  @Test
  void nextVersion_increasesAndEncodesNodeIdInLowBits() {
    CustomerStore store = new CustomerStore(8081);

    long v1 = store.nextVersion();
    long v2 = store.nextVersion();

    assertThat(v2).isGreaterThan(v1);
    assertThat(v1 & 0xFFFF).isEqualTo(81);
    assertThat(v1 >>> 16).isEqualTo(1);
  }

  @Test
  void nextVersion_sameCounterOnDifferentNodesNeverCollides() {
    long fromNode1 = new CustomerStore(8081).nextVersion();
    long fromNode2 = new CustomerStore(8082).nextVersion();

    assertThat(fromNode1).isNotEqualTo(fromNode2);
  }

  @Test
  void putIfNewer_ignoresStaleVersion() throws Exception {
    CustomerStore store = new CustomerStore(8081);

    store.putIfNewer("c1", customer("new-cart"), 10L << 16);
    store.putIfNewer("c1", customer("old-cart"), 5L << 16);

    assertThat(store.getCustomer("c1").getCustomer().getShoppingcartIds()).containsExactly("new-cart");
  }

  @Test
  void putIfNewer_advancesLocalCounterPastRemoteVersion() throws Exception {
    CustomerStore store = new CustomerStore(8081);

    store.putIfNewer("c1", customer("cart"), (10L << 16) | 82);

    // The next local write must win over the replicated one
    assertThat(store.nextVersion() >>> 16).isEqualTo(11);
  }

  @Test
  void putIfNewer_rejectsOutOfRangeVersionsWithoutTouchingCounter() {
    CustomerStore store = new CustomerStore(8081);

    assertThatThrownBy(() -> store.putIfNewer("c1", customer("attacker-cart"), Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.putIfNewer("c1", customer("attacker-cart"), -1L))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(store.getCustomer("c1")).isNull();
    assertThat(store.nextVersion()).isEqualTo((1L << 16) | 81);
  }

  @Test
  void putIfNewer_rejectsNullValue() {
    CustomerStore store = new CustomerStore(8081);

    assertThatThrownBy(() -> store.putIfNewer("c1", null, 1L << 16))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.getCustomer("c1")).isNull();
  }

  private static Customer customer(String cartId) {
    Customer c = new Customer();
    c.setShoppingcartIds(List.of(cartId));
    return c;
  }
}
