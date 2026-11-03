package com.cs6650.leaderless.controller;

import com.cs6650.leaderless.model.Customer;
import com.cs6650.leaderless.model.VersionedValue;
import com.cs6650.leaderless.service.CustomerStore;
import com.cs6650.leaderless.service.PropagationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerDbController.class)
class CustomerDbControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private PropagationService propagationService;

  @MockitoBean
  private CustomerStore customerStore;

  @Test
  void get_existingCustomer_returns200WithCartIds() throws Exception {
    Customer c = new Customer();
    c.setShoppingcartIds(List.of("cart-1"));
    when(propagationService.readWithQuorum(eq("c1"), anyLong()))
        .thenReturn(Optional.of(new VersionedValue(c, 1L << 16, 1)));

    mvc.perform(get("/get/c1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shoppingCartsIds[0]").value("cart-1"));
  }

  @Test
  void get_missingCustomer_returns404() throws Exception {
    when(propagationService.readWithQuorum(eq("c1"), anyLong())).thenReturn(Optional.empty());

    mvc.perform(get("/get/c1")).andExpect(status().isNotFound());
  }

  @Test
  void get_quorumNotReached_returns504() throws Exception {
    when(propagationService.readWithQuorum(eq("c1"), anyLong()))
        .thenThrow(new TimeoutException("Only 1 of 3 replicas responded"));

    mvc.perform(get("/get/c1")).andExpect(status().isGatewayTimeout());
  }
}
