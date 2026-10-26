package com.cs6650.shoppingcartservice.service;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WarehouseService is responsible for sending ship requests to the Warehouse.
 * It publishes messages to the RabbitMQ warehouse queue (fire-and-forget),
 * and the Warehouse Service consumes them asynchronously.
 */
@Service
public class WarehouseService {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;
    private static final Logger logger = LoggerFactory.getLogger(WarehouseService.class);

    /**
     * rabbitmq.queue.name should be defined in application.properties, e.g.:
     *
     * rabbitmq.queue.name=warehouse_orders
     */
    public WarehouseService(RabbitTemplate rabbitTemplate,
            @Value("${rabbitmq.queue.name}") String queueName) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueName = queueName;
    }

    /**
     * Publishes a ship request to the warehouse queue.
     * Checkout does not wait for the Warehouse to ship.
     */
    public void ship(String shipRequestJson) {
        logger.info("Enqueue ship request: {}", shipRequestJson);

        try {
            rabbitTemplate.convertAndSend(queueName, shipRequestJson);
        } catch (AmqpException e) {
            throw new IllegalStateException("Failed to enqueue ship request", e);
        }
    }
}
