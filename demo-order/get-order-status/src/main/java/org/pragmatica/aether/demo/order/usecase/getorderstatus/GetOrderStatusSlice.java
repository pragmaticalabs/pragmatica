package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GetOrderStatus Use Case - retrieves order information by ID.
 */
public record GetOrderStatusSlice() implements Slice {

    // Mock order storage (in production, this would be a database)
    private static final Map<String, StoredOrder> ORDERS = new ConcurrentHashMap<>();

    static {
        // Add some mock orders
        var now = Instant.now();
        ORDERS.put("ORD-12345678", new StoredOrder(
            new OrderId("ORD-12345678"),
            OrderStatus.CONFIRMED,
            Money.usd("129.97"),
            List.of(
                new GetOrderStatusResponse.OrderItem("PROD-ABC123", 2, Money.usd("29.99")),
                new GetOrderStatusResponse.OrderItem("PROD-DEF456", 1, Money.usd("49.99"))
            ),
            now.minusSeconds(3600),
            now
        ));
        ORDERS.put("ORD-87654321", new StoredOrder(
            new OrderId("ORD-87654321"),
            OrderStatus.SHIPPED,
            Money.usd("99.99"),
            List.of(
                new GetOrderStatusResponse.OrderItem("PROD-GHI789", 1, Money.usd("99.99"))
            ),
            now.minusSeconds(86400),
            now.minusSeconds(3600)
        ));
    }

    private record StoredOrder(
        OrderId orderId,
        OrderStatus status,
        Money total,
        List<GetOrderStatusResponse.OrderItem> items,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public static GetOrderStatusSlice getOrderStatusSlice() {
        return new GetOrderStatusSlice();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("getOrderStatus").unwrap(),
                this::execute,
                new TypeToken<GetOrderStatusResponse>() {},
                new TypeToken<GetOrderStatusRequest>() {}
            )
        );
    }

    private Promise<GetOrderStatusResponse> execute(GetOrderStatusRequest request) {
        return ValidGetOrderStatusRequest.validGetOrderStatusRequest(request)
                                          .async()
                                          .flatMap(this::findOrder);
    }

    private Promise<GetOrderStatusResponse> findOrder(ValidGetOrderStatusRequest validRequest) {
        var order = ORDERS.get(validRequest.orderId().value());

        if (order == null) {
            return new GetOrderStatusError.OrderNotFound(validRequest.orderId().value()).promise();
        }

        return Promise.success(new GetOrderStatusResponse(
            order.orderId(),
            order.status(),
            order.total(),
            order.items(),
            order.createdAt(),
            order.updatedAt()
        ));
    }

    // Method to store new orders (called by PlaceOrderSlice in a real system)
    public static void storeOrder(OrderId orderId, OrderStatus status, Money total,
                                   List<GetOrderStatusResponse.OrderItem> items) {
        var now = Instant.now();
        ORDERS.put(orderId.value(), new StoredOrder(orderId, status, total, items, now, now));
    }

    // Method to update order status
    public static void updateOrderStatus(String orderId, OrderStatus newStatus) {
        var order = ORDERS.get(orderId);
        if (order != null) {
            ORDERS.put(orderId, new StoredOrder(
                order.orderId(), newStatus, order.total(), order.items(),
                order.createdAt(), Instant.now()
            ));
        }
    }
}
