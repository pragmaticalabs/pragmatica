package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.inventory.ReleaseStockRequest;
import org.pragmatica.aether.demo.order.inventory.StockReleased;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CancelOrder Use Case - cancels an order and releases reserved stock.
 *
 * Flow:
 * 1. Validate request
 * 2. Find order and check if cancellable
 * 3. Release all stock reservations (calls InventoryService)
 * 4. Update order status to CANCELLED
 * 5. Return cancellation confirmation
 */
public record CancelOrderSlice(SliceInvoker invoker) implements Slice {

    private static final Artifact INVENTORY = Artifact.artifact("org.pragmatica-lite.aether.demo:inventory-service:0.1.0").unwrap();

    // Mock order storage with reservations
    private static final Map<String, StoredOrder> ORDERS = new ConcurrentHashMap<>();

    // Status that allow cancellation
    private static final Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(
        OrderStatus.PENDING,
        OrderStatus.CONFIRMED,
        OrderStatus.PROCESSING
    );

    static {
        // Add mock orders with reservations
        ORDERS.put("ORD-12345678", new StoredOrder(
            new OrderId("ORD-12345678"),
            OrderStatus.CONFIRMED,
            List.of("RES-11111111", "RES-22222222")
        ));
        ORDERS.put("ORD-87654321", new StoredOrder(
            new OrderId("ORD-87654321"),
            OrderStatus.SHIPPED,
            List.of("RES-33333333")
        ));
    }

    private record StoredOrder(
        OrderId orderId,
        OrderStatus status,
        List<String> reservationIds
    ) {}

    public static CancelOrderSlice cancelOrderSlice(SliceInvoker invoker) {
        return new CancelOrderSlice(invoker);
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("cancelOrder").unwrap(),
                this::execute,
                new TypeToken<CancelOrderResponse>() {},
                new TypeToken<CancelOrderRequest>() {}
            )
        );
    }

    @Override
    public List<SliceRoute> routes() {
        return List.of(
            SliceRoute.delete("/api/orders/{orderId}", "cancelOrder")
                .withPathVar("orderId")
                .withBody()
                .build()
        );
    }

    private Promise<CancelOrderResponse> execute(CancelOrderRequest request) {
        return ValidCancelOrderRequest.validCancelOrderRequest(request)
                                       .async()
                                       .flatMap(this::findAndValidateOrder)
                                       .flatMap(this::releaseAllStock)
                                       .map(this::updateAndConfirm);
    }

    private Promise<OrderWithContext> findAndValidateOrder(ValidCancelOrderRequest validRequest) {
        var order = ORDERS.get(validRequest.orderId().value());

        if (order == null) {
            return new CancelOrderError.OrderNotFound(validRequest.orderId().value()).promise();
        }

        if (!CANCELLABLE_STATUSES.contains(order.status())) {
            return new CancelOrderError.OrderNotCancellable(
                validRequest.orderId().value(),
                "Order is in " + order.status() + " status"
            ).promise();
        }

        return Promise.success(new OrderWithContext(validRequest, order));
    }

    private Promise<OrderWithReleases> releaseAllStock(OrderWithContext context) {
        var releases = context.order().reservationIds().stream()
            .map(reservationId -> invoker.invokeAndWait(
                INVENTORY,
                MethodName.methodName("releaseStock").unwrap(),
                new ReleaseStockRequest(reservationId),
                StockReleased.class
            ))
            .toList();

        return Promise.allOf(releases)
                      .flatMap(results -> {
                          var allSuccess = results.stream().allMatch(r -> r.isSuccess());

                          if (!allSuccess) {
                              // In production, we'd handle partial releases
                              return new CancelOrderError.StockReleaseFailed(
                                  new CancelOrderError.InvalidRequest("Some reservations could not be released")
                              ).promise();
                          }

                          return Promise.success(new OrderWithReleases(context.request(), context.order()));
                      });
    }

    private CancelOrderResponse updateAndConfirm(OrderWithReleases context) {
        // Update order status
        var orderId = context.request().orderId().value();
        var order = context.order();

        ORDERS.put(orderId, new StoredOrder(
            order.orderId(),
            OrderStatus.CANCELLED,
            order.reservationIds()
        ));

        return new CancelOrderResponse(
            context.request().orderId(),
            OrderStatus.CANCELLED,
            context.request().reason(),
            Instant.now()
        );
    }

    // Pipeline context records
    private record OrderWithContext(ValidCancelOrderRequest request, StoredOrder order) {}
    private record OrderWithReleases(ValidCancelOrderRequest request, StoredOrder order) {}

    // Method to store order with reservations (called by PlaceOrderSlice)
    public static void storeOrderWithReservations(OrderId orderId, OrderStatus status, List<String> reservationIds) {
        ORDERS.put(orderId.value(), new StoredOrder(orderId, status, reservationIds));
    }
}
