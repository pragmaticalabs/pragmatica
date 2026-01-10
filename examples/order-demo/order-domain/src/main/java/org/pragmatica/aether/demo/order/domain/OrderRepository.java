package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

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
    Unit save(StoredOrder order);

    /**
     * Find order by ID.
     */
    Option<StoredOrder> findById(String orderId);

    /**
     * Update order status.
     */
    Unit updateStatus(String orderId, OrderStatus newStatus);

    /**
     * Get a random order ID (for testing).
     */
    Option<String> randomOrderId();

    /**
     * Stored order record.
     */
    record StoredOrder(OrderId orderId,
                       CustomerId customerId,
                       OrderStatus status,
                       Money total,
                       List<OrderItem> items,
                       List<String> reservationIds,
                       Instant createdAt,
                       Instant updatedAt) {
        public StoredOrder withStatus(OrderStatus newStatus) {
            return new StoredOrder(orderId,
                                   customerId,
                                   newStatus,
                                   total,
                                   items,
                                   reservationIds,
                                   createdAt,
                                   Instant.now());
        }
    }

    /**
     * Order line item.
     */
    record OrderItem(String productId,
                     int quantity,
                     Money unitPrice) {}

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
        var order1 = new StoredOrder(new OrderId("ORD-12345678"),
                                     CustomerId.customerId("CUST-00000001")
                                               .expect("Invalid customer ID: CUST-00000001"),
                                     OrderStatus.CONFIRMED,
                                     Money.usd("129.97")
                                          .expect("Invalid mock order total"),
                                     List.of(new OrderItem("PROD-ABC123",
                                                           2,
                                                           Money.usd("29.99")
                                                                .expect("Invalid mock unit price")),
                                             new OrderItem("PROD-DEF456",
                                                           1,
                                                           Money.usd("49.99")
                                                                .expect("Invalid mock unit price"))),
                                     List.of("RES-11111111", "RES-22222222"),
                                     now.minusSeconds(3600),
                                     now);
        orders.put(order1.orderId()
                         .value(),
                   order1);
        // Order 2 - Shipped
        var order2 = new StoredOrder(new OrderId("ORD-87654321"),
                                     CustomerId.customerId("CUST-00000002")
                                               .expect("Invalid customer ID: CUST-00000002"),
                                     OrderStatus.SHIPPED,
                                     Money.usd("99.99")
                                          .expect("Invalid mock order total"),
                                     List.of(new OrderItem("PROD-GHI789",
                                                           1,
                                                           Money.usd("99.99")
                                                                .expect("Invalid mock unit price"))),
                                     List.of("RES-33333333"),
                                     now.minusSeconds(86400),
                                     now.minusSeconds(3600));
        orders.put(order2.orderId()
                         .value(),
                   order2);
    }

    @Override
    public Unit save(StoredOrder order) {
        orders.put(order.orderId()
                        .value(),
                   order);
        return Unit.unit();
    }

    @Override
    public Option<StoredOrder> findById(String orderId) {
        return Option.option(orders.get(orderId));
    }

    @Override
    public Unit updateStatus(String orderId, OrderStatus newStatus) {
        Option.option(orders.get(orderId))
              .onPresent(order -> orders.put(orderId,
                                             order.withStatus(newStatus)));
        return Unit.unit();
    }

    @Override
    public Option<String> randomOrderId() {
        if (orders.isEmpty()) {
            return Option.empty();
        }
        var keys = orders.keySet()
                         .toArray(new String[0]);
        var idx = (int)(Math.random() * keys.length);
        return Option.option(keys[idx]);
    }
}
