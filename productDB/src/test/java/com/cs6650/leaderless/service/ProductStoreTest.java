package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductStoreTest {

  @Test
  void nextVersion_increasesAndEncodesNodeIdInLowBits() {
    ProductStore store = new ProductStore(8083);

    long v1 = store.nextVersion();
    long v2 = store.nextVersion();

    assertThat(v2).isGreaterThan(v1);
    assertThat(v1 & 0xFFFF).isEqualTo(83);
  }

  @Test
  void putIfNewer_ignoresStaleVersion() throws Exception {
    ProductStore store = new ProductStore(8081);

    store.putIfNewer(1, product("NEW"), 10L << 16);
    store.putIfNewer(1, product("OLD"), 5L << 16);

    assertThat(store.get(1).getProduct().getSku()).isEqualTo("NEW");
  }

  @Test
  void putIfNewer_advancesLocalCounterPastRemoteVersion() throws Exception {
    ProductStore store = new ProductStore(8081);

    store.putIfNewer(1, product("REMOTE"), (10L << 16) | 82);

    assertThat(store.nextVersion() >>> 16).isEqualTo(11);
  }

  private static Product product(String sku) {
    Product p = new Product();
    p.setSku(sku);
    return p;
  }
}
