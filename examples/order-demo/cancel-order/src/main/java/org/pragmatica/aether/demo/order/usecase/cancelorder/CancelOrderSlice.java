package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderRepository;
import org.pragmatica.aether.demo.order.domain.OrderRepository.StoredOrder;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.ReleaseStockRequest;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.StockReleased;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.aether.slice.SliceRuntime;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * CancelOrder Use Case Slice - cancels an order and releases reserved stock.
 * Uses shared OrderRepository for cross-slice visibility.
 * <p>
 * Flow:
 * 1. Validate request
 * 2. Find order and check if cancellable
 * 3. Release all stock reservations (calls InventoryService)
 * 4. Update order status to CANCELLED
 * 5. Return cancellation confirmation
 */
public record CancelOrderSlice() implements Slice {
    // === Request ===
    public record CancelOrderRequest(String orderId, String reason) {}

    // === Response ===
    public record CancelOrderResponse(OrderId orderId, OrderStatus status, String reason, Instant cancelledAt) {}

    // === Validated Input ===
    public record ValidCancelOrderRequest(OrderId orderId, String reason) {
        public static Result<ValidCancelOrderRequest> validCancelOrderRequest(CancelOrderRequest raw) {
            return OrderId.orderId(raw.orderId())
                          .map(orderId -> new ValidCancelOrderRequest(orderId,
                                                                      raw.reason()));
        }
    }

    // === Errors ===
    public sealed interface CancelOrderError extends Cause {
        record InvalidRequest(String details) implements CancelOrderError {
            @Override public String message() {
                return "Invalid request: " + details;
            }
        }

        record OrderNotFound(String orderId) implements CancelOrderError {
            @Override public String message() {
                return "Order not found: " + orderId;
            }
        }

        record OrderNotCancellable(String orderId, String reason) implements CancelOrderError {
            @Override public String message() {
                return "Order " + orderId + " cannot be cancelled: " + reason;
            }
        }

        record StockReleaseFailed(Cause cause) implements CancelOrderError {
            @Override public String message() {
                return "Failed to release stock: " + cause.message();
            }
        }
    }

    // === Static Config ===
    private static final String INVENTORY = "org.pragmatica-lite.aether.demo:inventory-service:0.1.0";
    private static final Set<OrderStatus> CANCELLABLE_STATUSES = Set.of(OrderStatus.PENDING,
                                                                        OrderStatus.CONFIRMED,
                                                                        OrderStatus.PROCESSING);

    // === Factory ===
    public static CancelOrderSlice cancelOrderSlice() {
        return new CancelOrderSlice();
    }

    private OrderRepository repository() {
        return OrderRepository.instance();
    }

    private SliceInvokerFacade invoker() {
        return SliceRuntime.sliceInvoker();
    }

    // === Slice Implementation ===
    @Override
    public List<SliceMethod< ?, ? >> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("cancelOrder")
                                                   .expect("Invalid method name: cancelOrder"),
                                         this::execute,
                                         new TypeToken<CancelOrderResponse>() {},
                                         new TypeToken<CancelOrderRequest>() {}));
    }

    @Override
    public List<SliceRoute> routes() {
        return List.of(SliceRoute.delete("/api/orders/{orderId}", "cancelOrder")
                                 .withPathVar("orderId")
                                 .withBody()
                                 .build());
    }

    private Promise<CancelOrderResponse> execute(CancelOrderRequest request) {
        return ValidCancelOrderRequest.validCancelOrderRequest(request)
                                      .async()
                                      .flatMap(this::findAndValidateOrder)
                                      .flatMap(this::releaseAllStock)
                                      .map(this::updateAndConfirm);
    }

    private Promise<OrderWithContext> findAndValidateOrder(ValidCancelOrderRequest validRequest) {
        return repository()
                         .findById(validRequest.orderId()
                                               .value())
                         .toResult(orderNotFound(validRequest))
                         .async()
                         .flatMap(order -> validateCancellable(order, validRequest));
    }

    private Cause orderNotFound(ValidCancelOrderRequest validRequest) {
        return new CancelOrderError.OrderNotFound(validRequest.orderId()
                                                              .value());
    }

    private Promise<OrderWithContext> validateCancellable(StoredOrder order, ValidCancelOrderRequest validRequest) {
        if (!CANCELLABLE_STATUSES.contains(order.status())) {
            return new CancelOrderError.OrderNotCancellable(validRequest.orderId()
                                                                        .value(),
                                                            "Order is in " + order.status() + " status").promise();
        }
        return Promise.success(new OrderWithContext(validRequest, order));
    }

    private Promise<OrderWithReleases> releaseAllStock(OrderWithContext context) {
        var releases = context.order()
                              .reservationIds()
                              .stream()
                              .map(this::releaseStock)
                              .toList();
        return Promise.allOf(releases)
                      .flatMap(results -> validateReleases(results, context));
    }

    private Promise<StockReleased> releaseStock(String reservationId) {
        return invoker()
                      .invokeAndWait(INVENTORY,
                                     "releaseStock",
                                     new ReleaseStockRequest(reservationId),
                                     StockReleased.class);
    }

    private Promise<OrderWithReleases> validateReleases(List<Result<StockReleased>> results, OrderWithContext context) {
        var allSuccess = results.stream()
                                .allMatch(Result::isSuccess);
        if (!allSuccess) {
            return new CancelOrderError.StockReleaseFailed(new CancelOrderError.InvalidRequest("Some reservations could not be released")).promise();
        }
        return Promise.success(new OrderWithReleases(context.request(), context.order()));
    }

    private CancelOrderResponse updateAndConfirm(OrderWithReleases context) {
        repository()
                  .updateStatus(context.request()
                                       .orderId()
                                       .value(),
                                OrderStatus.CANCELLED);
        return new CancelOrderResponse(context.request()
                                              .orderId(),
                                       OrderStatus.CANCELLED,
                                       context.request()
                                              .reason(),
                                       Instant.now());
    }

    // === Pipeline Context Records ===
    private record OrderWithContext(ValidCancelOrderRequest request, StoredOrder order) {}

    private record OrderWithReleases(ValidCancelOrderRequest request, StoredOrder order) {}
}
