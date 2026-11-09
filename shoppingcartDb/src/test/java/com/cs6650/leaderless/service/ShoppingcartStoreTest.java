package com.cs6650.leaderless.service;

import com.cs6650.leaderless.model.Shoppingcart;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShoppingcartStoreTest {

  @Test
  void putIfNewer_acceptsNewerAndIgnoresStaleVersion() throws Exception {
    ShoppingcartStore store = new ShoppingcartStore(8081);

    store.putIfNewer("cart1", cart(1, 5), 10L << 16);
    store.putIfNewer("cart1", cart(1, 1), 5L << 16);

    assertThat(store.getCart("cart1").getShoppingcart().getItems()).containsEntry(1, 5);
  }

  @Test
  void putIfNewer_rejectsOutOfRangeVersionsWithoutTouchingCounter() {
    ShoppingcartStore store = new ShoppingcartStore(8081);

    assertThatThrownBy(() -> store.putIfNewer("cart1", cart(1, 1), Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.putIfNewer("cart1", cart(1, 1), -1L))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(store.getCart("cart1")).isNull();
    assertThat(store.nextVersion()).isEqualTo((1L << 16) | 81);
  }

  @Test
  void putIfNewer_rejectsNullValue() {
    ShoppingcartStore store = new ShoppingcartStore(8081);

    assertThatThrownBy(() -> store.putIfNewer("cart1", null, 1L << 16))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.getCart("cart1")).isNull();
  }

  private static Shoppingcart cart(int itemId, int quantity) {
    Shoppingcart c = new Shoppingcart();
    HashMap<Integer, Integer> items = new HashMap<>();
    items.put(itemId, quantity);
    c.setItems(items);
    return c;
  }
}
