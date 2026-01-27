package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderRepository;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.time.Instant;
import java.util.List;

/**
 * GetOrderStatus Use Case Slice - retrieves order information by ID.
 * Uses shared OrderRepository for cross-slice visibility.
 */
public record GetOrderStatusSlice() implements Slice {
    // === Request ===
    public record GetOrderStatusRequest(String orderId) {}

    // === Response ===
    public record GetOrderStatusResponse(OrderId orderId,
                                         OrderStatus status,
                                         Money total,
                                         List<OrderItem> items,
                                         Instant createdAt,
                                         Instant updatedAt) {
        public record OrderItem(String productId, int quantity, Money unitPrice) {}
    }

    // === Validated Input ===
    public record ValidGetOrderStatusRequest(OrderId orderId) {
        public static Result<ValidGetOrderStatusRequest> validGetOrderStatusRequest(GetOrderStatusRequest raw) {
            return OrderId.orderId(raw.orderId())
                          .map(ValidGetOrderStatusRequest::new);
        }
    }

    // === Errors ===
    public sealed interface GetOrderStatusError extends Cause {
        record InvalidRequest(String details) implements GetOrderStatusError {
            @Override public String message() {
                return "Invalid request: " + details;
            }
        }

        record OrderNotFound(String orderId) implements GetOrderStatusError {
            @Override public String message() {
                return "Order not found: " + orderId;
            }
        }
    }

    // === Factory ===
    public static GetOrderStatusSlice getOrderStatusSlice() {
        return new GetOrderStatusSlice();
    }

    private OrderRepository repository() {
        return OrderRepository.instance();
    }

    // === Slice Implementation ===
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("getOrderStatus")
                                                   .expect("Invalid method name: getOrderStatus"),
                                         this::execute,
                                         new TypeToken<GetOrderStatusResponse>() {},
                                         new TypeToken<GetOrderStatusRequest>() {}));
    }

    private Promise<GetOrderStatusResponse> execute(GetOrderStatusRequest request) {
        return ValidGetOrderStatusRequest.validGetOrderStatusRequest(request)
                                         .async()
                                         .flatMap(this::findOrder);
    }

    private Promise<GetOrderStatusResponse> findOrder(ValidGetOrderStatusRequest validRequest) {
        return repository().findById(validRequest.orderId()
                                                 .value())
                         .toResult(orderNotFound(validRequest))
                         .async()
                         .flatMap(this::toResponse);
    }

    private Cause orderNotFound(ValidGetOrderStatusRequest validRequest) {
        return new GetOrderStatusError.OrderNotFound(validRequest.orderId()
                                                                 .value());
    }

    private Promise<GetOrderStatusResponse> toResponse(OrderRepository.StoredOrder order) {
        var items = order.items()
                         .stream()
                         .map(this::toOrderItem)
                         .toList();
        return Promise.success(new GetOrderStatusResponse(order.orderId(),
                                                          order.status(),
                                                          order.total(),
                                                          items,
                                                          order.createdAt(),
                                                          order.updatedAt()));
    }

    private GetOrderStatusResponse.OrderItem toOrderItem(OrderRepository.OrderItem item) {
        return new GetOrderStatusResponse.OrderItem(item.productId(), item.quantity(), item.unitPrice());
    }
}
