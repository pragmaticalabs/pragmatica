package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.aether.demo.order.domain.OrderRepository;
import org.pragmatica.aether.demo.order.domain.OrderRepository.StoredOrder;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.inventory.ReleaseStockRequest;
import org.pragmatica.aether.demo.order.inventory.StockReleased;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.aether.slice.SliceRuntime;
import org.pragmatica.aether.slice.SliceRuntime.SliceInvokerFacade;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * CancelOrder Use Case - cancels an order and releases reserved stock.
 * Uses shared OrderRepository for cross-slice visibility.
 *
 * Flow:
 * 1. Validate request
 * 2. Find order and check if cancellable
 * 3. Release all stock reservations (calls InventoryService)
 * 4. Update order status to CANCELLED
 * 5. Return cancellation confirmation
 */
public record CancelOrderSlice() implements Slice {

    private static final String INVENTORY = "org.pragmatica-lite.aether.demo:inventory-service:0.1.0";

    // Status that allow cancellation
    private static final Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(
        OrderStatus.PENDING,
        OrderStatus.CONFIRMED,
        OrderStatus.PROCESSING
    );

    private OrderRepository repository() {
        return OrderRepository.instance();
    }

    public static CancelOrderSlice cancelOrderSlice() {
        return new CancelOrderSlice();
    }

    private SliceInvokerFacade invoker() {
        return SliceRuntime.sliceInvoker();
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
        return repository().findById(validRequest.orderId().value())
            .fold(
                () -> new CancelOrderError.OrderNotFound(validRequest.orderId().value()).<OrderWithContext>promise(),
                order -> {
                    if (!CANCELLABLE_STATUSES.contains(order.status())) {
                        return new CancelOrderError.OrderNotCancellable(
                            validRequest.orderId().value(),
                            "Order is in " + order.status() + " status"
                        ).<OrderWithContext>promise();
                    }
                    return Promise.success(new OrderWithContext(validRequest, order));
                }
            );
    }

    private Promise<OrderWithReleases> releaseAllStock(OrderWithContext context) {
        var releases = context.order().reservationIds().stream()
            .map(reservationId -> invoker().invokeAndWait(
                INVENTORY,
                "releaseStock",
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
        // Update order status in the repository
        var orderId = context.request().orderId().value();
        repository().updateStatus(orderId, OrderStatus.CANCELLED);

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
}
