package com.cs6650.leaderless.controller;

import com.cs6650.leaderless.model.Shoppingcart;
import com.cs6650.leaderless.model.VersionedValue;
import com.cs6650.leaderless.service.PropagationService;
import com.cs6650.leaderless.service.ShoppingcartStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShoppingcartDbController.class)
class ShoppingcartDbControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private PropagationService propagationService;

  @MockitoBean
  private ShoppingcartStore shoppingcartStore;

  @Test
  void get_existingCart_returns200WithItems() throws Exception {
    Shoppingcart sc = new Shoppingcart();
    HashMap<Integer, Integer> items = new HashMap<>();
    items.put(1, 3);
    sc.setItems(items);
    when(propagationService.readWithQuorum(eq("cart1"), anyLong()))
        .thenReturn(Optional.of(new VersionedValue(sc, 1L << 16, 1)));

    mvc.perform(get("/get/cart1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.1").value(3));
  }

  @Test
  void get_missingCart_returns404() throws Exception {
    when(propagationService.readWithQuorum(eq("cart1"), anyLong())).thenReturn(Optional.empty());

    mvc.perform(get("/get/cart1")).andExpect(status().isNotFound());
  }

  @Test
  void get_quorumNotReached_returns504() throws Exception {
    when(propagationService.readWithQuorum(eq("cart1"), anyLong()))
        .thenThrow(new TimeoutException("Only 1 of 3 replicas responded"));

    mvc.perform(get("/get/cart1")).andExpect(status().isGatewayTimeout());
  }
}
