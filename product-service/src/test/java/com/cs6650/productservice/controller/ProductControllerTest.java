package com.cs6650.productservice.controller;

import com.cs6650.productservice.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ProductController against a fake ProductDB served by the JDK HTTP server.
 */
class ProductControllerTest {

  private HttpServer productDb;
  private final AtomicInteger dbStatus = new AtomicInteger();
  private final AtomicReference<String> dbResponseBody = new AtomicReference<>("");
  private final AtomicReference<String> dbRequestBody = new AtomicReference<>();

  private ProductController controller;

  @BeforeEach
  void setUp() throws IOException {
    productDb = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    productDb.createContext("/", exchange -> {
      dbRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = dbResponseBody.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(dbStatus.get(), body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    productDb.start();

    controller = new ProductController();
    ReflectionTestUtils.setField(controller, "productDbUrl", "http://localhost:" + productDb.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    productDb.stop(0);
  }

  @Test
  void createProduct_dbStoresIt_returns201AndSendsCorrectWeight() throws Exception {
    dbStatus.set(201);

    ResponseEntity<?> response = controller.createProduct(product());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Product) response.getBody()).getId()).isPositive();

    JsonNode sent = new ObjectMapper().readTree(dbRequestBody.get());
    assertThat(sent.path("sku").asText()).isEqualTo("ABC-123");
    // Before the fix someOtherId (789) was sent as the weight
    assertThat(sent.path("weight").asInt()).isEqualTo(1250);
  }

  @Test
  void createProduct_dbRejectsWrite_returns500() {
    dbStatus.set(500);
    dbResponseBody.set("Failed to propagate to all peers");

    ResponseEntity<?> response = controller.createProduct(product());

    // Before the fix this returned 201 even though nothing was stored
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("PRODUCT_DB_ERROR");
  }

  @Test
  void createProduct_dbUnreachable_returns500() {
    productDb.stop(0);

    ResponseEntity<?> response = controller.createProduct(product());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("IO_EXCEPTION");
  }

  @Test
  void getProduct_found_returnsProduct() {
    dbStatus.set(200);
    dbResponseBody.set("""
        {"product": {"id": 5, "sku": "ABC-123", "manufacturer": "Acme", "categoryId": 4, "weight": 1250},
         "version": 65537, "timestamp": 1}
        """);

    ResponseEntity<?> response = controller.getProduct(5);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Product product = (Product) response.getBody();
    assertThat(product.getSku()).isEqualTo("ABC-123");
    assertThat(product.getWeight()).isEqualTo(1250);
  }

  @Test
  void getProduct_notFound_returns404() {
    dbStatus.set(404);

    ResponseEntity<?> response = controller.getProduct(5);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getProduct_invalidId_returns400WithoutCallingDb() {
    ResponseEntity<?> response = controller.getProduct(0);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(dbRequestBody.get()).isNull();
  }

  private static Product product() {
    return new Product(0, "ABC-123", "Acme", 4, 1250, 789);
  }
}
