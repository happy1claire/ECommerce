package com.cs6650.productservice.controller;


import com.cs6650.productservice.model.Product;
import com.cs6650.productservice.model.StoreProductRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
public class ProductController {

  // Add configuration to enable "bad" behavior mode
  // Set environment variable BAD_SERVICE_MODE=true for the bad instance
  @Value("${bad.service.mode:false}")
  private boolean badServiceMode;

  @Value("${productdb.base-url}")
  private String productDbUrl;

  private final Random random = new Random();

  // Bounded timeouts so a slow or misrouted productDB call cannot pin request threads forever.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .build();
  private final ObjectMapper objectMapper = new ObjectMapper();


    @GetMapping("/products/{productId}")
  public ResponseEntity<?> getProduct(@PathVariable Integer productId) {

    // validate that productId is positive (productId >= 1)
    if (productId < 1) {
      Map<String, String> error = new HashMap<>();
      error.put("error", "INVALID_PRODUCT_ID");
      error.put("message", "Product ID must be a positive integer");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    log.info("Request to get product with ID: {}", productId);

    try{
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(productDbUrl + "/get/" + productId))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient
                .send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        String body = response.body();

        log.info("ProductDB GET response status: {}, body: {}", statusCode, body);

        if (statusCode == 404) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "PRODUCT_NOT_FOUND");
            error.put("message", "Product with id " + productId + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        if (statusCode != 200) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "PRODUCT_DB_ERROR");
            error.put("message", "ProductDB returned status " + statusCode);
            error.put("downstreamResponse", body);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        System.out.println("BODY: ");
        System.out.println(body);

        System.out.println(objectMapper.readTree(body).get("product").toString());

        Product product = objectMapper.treeToValue(objectMapper.readTree(body).get("product"), Product.class);
        return ResponseEntity.ok(product);
    }catch (IOException e) {
        log.error("IO error when calling ProductDB", e);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "IO_EXCEPTION");
        error.put("message", "I/O error when calling ProductDB");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    } catch (InterruptedException e) {
        log.error("Request to ProductDB was interrupted", e);
        Thread.currentThread().interrupt();  // 恢復 interrupted 狀態
        Map<String, Object> error = new HashMap<>();
        error.put("error", "INTERRUPTED");
        error.put("message", "Request to ProductDB was interrupted");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

//    product.setProductId(productId);
//    product.setSku("ABC123XYZ");
//    product.setManufacturer("Acme Corporation");
//    product.setCategoryId(4568);
//    product.setWeight(1250);
//    product.setSomeOtherId(789);

  }


  // The ? wildcard allows returning either Product (success) or Map (error)
  @PostMapping("/product")
  public ResponseEntity<?> createProduct(@RequestBody Product productData) {
    log.info("Request to create product with data: {}", productData);

    // If in "bad service mode", return 503 error 50% of the time
    // This simulates a failing service for load balancer testing
    if (badServiceMode && random.nextBoolean()) {
      log.warn("BAD SERVICE MODE: Returning 503 Service Unavailable");
      Map<String, Object> error = new HashMap<>();
      error.put("error", "SERVICE_UNAVAILABLE");
      error.put("message", "Service temporarily unavailable (simulated failure)");
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }
      // Generate random productId (positive 32-bit int)
      int newProductId = random.nextInt(Integer.MAX_VALUE - 1) + 1;
      productData.setId(newProductId);

      StoreProductRequest requestBody = new StoreProductRequest(
              productData.getId(),
              productData.getSku(),
              productData.getManufacturer(),
              productData.getCategoryId(),
              productData.getWeight()
      );
      try {

          String json = objectMapper.writeValueAsString(requestBody);

//          System.out.println("JSON-------------");
//          System.out.println(json);

          HttpRequest httpRequest = HttpRequest.newBuilder()
                  .uri(URI.create(productDbUrl+"/product"))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(json))
                  .build();

          HttpResponse<String> response =
                  httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

          log.info("ProductDB POST response status: {}, body: {}", response.statusCode(), response.body());

          // Only report success if ProductDB actually stored the product
          if (response.statusCode() != 201) {
              Map<String, Object> error = new HashMap<>();
              error.put("error", "PRODUCT_DB_ERROR");
              error.put("message", "ProductDB returned status " + response.statusCode());
              error.put("downstreamResponse", response.body());
              return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
          }
      } catch (IOException e) {
          log.error("IO error when calling ProductDB", e);
          Map<String, Object> error = new HashMap<>();
          error.put("error", "IO_EXCEPTION");
          error.put("message", "I/O error when calling ProductDB");
          return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
      } catch (InterruptedException e) {
          log.error("Request to ProductDB was interrupted", e);
          Thread.currentThread().interrupt();
          Map<String, Object> error = new HashMap<>();
          error.put("error", "INTERRUPTED");
          error.put("message", "Request to ProductDB was interrupted");
          return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
      }

    return ResponseEntity.status(HttpStatus.CREATED).body(productData);
  }

  // Health check endpoint for load balancer
  @GetMapping("/health")
  public ResponseEntity<String> healthCheck() {
    return ResponseEntity.ok("Product Service is healthy");
  }
}