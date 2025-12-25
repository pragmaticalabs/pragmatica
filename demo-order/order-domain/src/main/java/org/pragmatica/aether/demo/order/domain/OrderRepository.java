package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for order storage.
 * Uses static shared storage for demo purposes (orders visible across all slices).
 */
public interface OrderRepository {

    /**
     * Store a new order.
     */
    void save(StoredOrder order);

    /**
     * Find order by ID.
     */
    Option<StoredOrder> findById(String orderId);

    /**
     * Update order status.
     */
    void updateStatus(String orderId, OrderStatus newStatus);

    /**
     * Get a random order ID (for testing).
     */
    Option<String> randomOrderId();

    /**
     * Stored order record.
     */
    record StoredOrder(
        OrderId orderId,
        CustomerId customerId,
        OrderStatus status,
        Money total,
        List<OrderItem> items,
        List<String> reservationIds,
        Instant createdAt,
        Instant updatedAt
    ) {
        public StoredOrder withStatus(OrderStatus newStatus) {
            return new StoredOrder(
                orderId, customerId, newStatus, total, items,
                reservationIds, createdAt, Instant.now()
            );
        }
    }

    /**
     * Order line item.
     */
    record OrderItem(
        String productId,
        int quantity,
        Money unitPrice
    ) {}

    /**
     * Get the shared repository instance.
     */
    static OrderRepository instance() {
        return InMemoryOrderRepository.INSTANCE;
    }
}

/**
 * In-memory implementation with static storage for cross-slice visibility.
 */
final class InMemoryOrderRepository implements OrderRepository {
    static final InMemoryOrderRepository INSTANCE = new InMemoryOrderRepository();

    private final Map<String, StoredOrder> orders = new ConcurrentHashMap<>();

    private InMemoryOrderRepository() {
        // Seed with some mock orders for demo
        seedMockOrders();
    }

    private void seedMockOrders() {
        var now = Instant.now();

        // Order 1 - Confirmed
        var order1 = new StoredOrder(
            new OrderId("ORD-12345678"),
            CustomerId.customerId("CUST-00000001").unwrap(),
            OrderStatus.CONFIRMED,
            Money.usd("129.97"),
            List.of(
                new OrderItem("PROD-ABC123", 2, Money.usd("29.99")),
                new OrderItem("PROD-DEF456", 1, Money.usd("49.99"))
            ),
            List.of("RES-11111111", "RES-22222222"),
            now.minusSeconds(3600),
            now
        );
        orders.put(order1.orderId().value(), order1);

        // Order 2 - Shipped
        var order2 = new StoredOrder(
            new OrderId("ORD-87654321"),
            CustomerId.customerId("CUST-00000002").unwrap(),
            OrderStatus.SHIPPED,
            Money.usd("99.99"),
            List.of(
                new OrderItem("PROD-GHI789", 1, Money.usd("99.99"))
            ),
            List.of("RES-33333333"),
            now.minusSeconds(86400),
            now.minusSeconds(3600)
        );
        orders.put(order2.orderId().value(), order2);
    }

    @Override
    public void save(StoredOrder order) {
        orders.put(order.orderId().value(), order);
    }

    @Override
    public Option<StoredOrder> findById(String orderId) {
        return Option.option(orders.get(orderId));
    }

    @Override
    public void updateStatus(String orderId, OrderStatus newStatus) {
        var order = orders.get(orderId);
        if (order != null) {
            orders.put(orderId, order.withStatus(newStatus));
        }
    }

    @Override
    public Option<String> randomOrderId() {
        if (orders.isEmpty()) {
            return Option.empty();
        }
        var keys = orders.keySet().toArray(new String[0]);
        var idx = (int) (Math.random() * keys.length);
        return Option.option(keys[idx]);
    }
}
