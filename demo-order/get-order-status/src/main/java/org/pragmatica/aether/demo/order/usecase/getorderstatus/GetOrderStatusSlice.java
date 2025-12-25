package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.aether.demo.order.domain.OrderRepository;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;

/**
 * GetOrderStatus Use Case - retrieves order information by ID.
 * Uses shared OrderRepository for cross-slice visibility.
 */
public record GetOrderStatusSlice() implements Slice {

    private OrderRepository repository() {
        return OrderRepository.instance();
    }

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

    @Override
    public List<SliceRoute> routes() {
        return List.of(
            SliceRoute.get("/api/orders/{orderId}", "getOrderStatus")
                .withPathVar("orderId")
                .build()
        );
    }

    private Promise<GetOrderStatusResponse> execute(GetOrderStatusRequest request) {
        return ValidGetOrderStatusRequest.validGetOrderStatusRequest(request)
                                          .async()
                                          .flatMap(this::findOrder);
    }

    private Promise<GetOrderStatusResponse> findOrder(ValidGetOrderStatusRequest validRequest) {
        return repository().findById(validRequest.orderId().value())
            .fold(
                () -> new GetOrderStatusError.OrderNotFound(validRequest.orderId().value()).<GetOrderStatusResponse>promise(),
                order -> {
                    var items = order.items().stream()
                        .map(item -> new GetOrderStatusResponse.OrderItem(
                            item.productId(), item.quantity(), item.unitPrice()
                        ))
                        .toList();

                    return Promise.success(new GetOrderStatusResponse(
                        order.orderId(),
                        order.status(),
                        order.total(),
                        items,
                        order.createdAt(),
                        order.updatedAt()
                    ));
                }
            );
    }
}
