package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.inventory.CheckStockRequest;
import org.pragmatica.aether.demo.order.inventory.ReserveStockRequest;
import org.pragmatica.aether.demo.order.inventory.StockAvailability;
import org.pragmatica.aether.demo.order.inventory.StockReservation;
import org.pragmatica.aether.demo.order.pricing.CalculateTotalRequest;
import org.pragmatica.aether.demo.order.pricing.OrderTotal;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceRoute;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaceOrder Use Case
 *
 * Flow:
 * 1. Validate request
 * 2. Check stock for all items (parallel, calls InventoryService)
 * 3. Calculate total price (calls PricingService)
 * 4. Reserve stock for all items (parallel, calls InventoryService)
 * 5. Return order confirmation
 */
public record PlaceOrderSlice(SliceInvoker invoker) implements Slice {

    private static final Artifact INVENTORY = Artifact.artifact("org.pragmatica-lite.aether.demo:inventory-service:0.1.0").unwrap();
    private static final Artifact PRICING = Artifact.artifact("org.pragmatica-lite.aether.demo:pricing-service:0.1.0").unwrap();

    public static PlaceOrderSlice placeOrderSlice(SliceInvoker invoker) {
        return new PlaceOrderSlice(invoker);
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("placeOrder").unwrap(),
                this::execute,
                new TypeToken<PlaceOrderResponse>() {},
                new TypeToken<PlaceOrderRequest>() {}
            )
        );
    }

    @Override
    public List<SliceRoute> routes() {
        return List.of(
            SliceRoute.post("/api/orders", "placeOrder")
                .withBody()
                .build()
        );
    }

    private Promise<PlaceOrderResponse> execute(PlaceOrderRequest request) {
        return ValidPlaceOrderRequest.validPlaceOrderRequest(request)
                                     .async()
                                     .flatMap(this::checkAllStock)
                                     .flatMap(this::calculateTotal)
                                     .flatMap(this::reserveAllStock)
                                     .map(this::createOrder);
    }

    private Promise<ValidWithStockCheck> checkAllStock(ValidPlaceOrderRequest request) {
        var stockChecks = request.items().stream()
            .map(item -> invoker.invokeAndWait(
                INVENTORY,
                MethodName.methodName("checkStock").unwrap(),
                new CheckStockRequest(item.productId(), item.quantity()),
                StockAvailability.class
            ))
            .toList();

        return Promise.allOf(stockChecks)
                      .flatMap(results -> {
                          var allAvailable = results.stream()
                              .allMatch(r -> r.isSuccess() && r.unwrap().sufficient());

                          if (!allAvailable) {
                              return new PlaceOrderError.InventoryCheckFailed(
                                  Causes.cause("Some items not available")
                              ).promise();
                          }
                          return Promise.success(new ValidWithStockCheck(request));
                      });
    }

    private Promise<ValidWithPrice> calculateTotal(ValidWithStockCheck context) {
        var lineItems = context.request().items().stream()
            .map(item -> new CalculateTotalRequest.LineItem(item.productId(), item.quantity()))
            .toList();

        return invoker.invokeAndWait(
            PRICING,
            MethodName.methodName("calculateTotal").unwrap(),
            new CalculateTotalRequest(lineItems, context.request().discountCode()),
            OrderTotal.class
        ).map(total -> new ValidWithPrice(context.request(), total))
         .mapError(cause -> new PlaceOrderError.PricingFailed(cause));
    }

    private Promise<ValidWithReservations> reserveAllStock(ValidWithPrice context) {
        var orderId = OrderId.generate();

        var reservations = context.request().items().stream()
            .map(item -> invoker.invokeAndWait(
                INVENTORY,
                MethodName.methodName("reserveStock").unwrap(),
                new ReserveStockRequest(item.productId(), item.quantity(), orderId.value()),
                StockReservation.class
            ))
            .toList();

        return Promise.allOf(reservations)
                      .flatMap(results -> {
                          var successful = new ArrayList<StockReservation>();
                          for (var r : results) {
                              if (r.isSuccess()) {
                                  successful.add(r.unwrap());
                              }
                          }

                          if (successful.size() != results.size()) {
                              return new PlaceOrderError.ReservationFailed(
                                  Causes.cause("Failed to reserve all items")
                              ).promise();
                          }

                          return Promise.success(new ValidWithReservations(
                              context.request(), context.total(), orderId, successful
                          ));
                      });
    }

    private PlaceOrderResponse createOrder(ValidWithReservations context) {
        return new PlaceOrderResponse(
            context.orderId(),
            OrderStatus.CONFIRMED,
            context.total().total(),
            context.reservations().stream().map(StockReservation::reservationId).toList()
        );
    }

    // Pipeline context records
    private record ValidWithStockCheck(ValidPlaceOrderRequest request) {}
    private record ValidWithPrice(ValidPlaceOrderRequest request, OrderTotal total) {}
    private record ValidWithReservations(
        ValidPlaceOrderRequest request,
        OrderTotal total,
        OrderId orderId,
        List<StockReservation> reservations
    ) {}
}
