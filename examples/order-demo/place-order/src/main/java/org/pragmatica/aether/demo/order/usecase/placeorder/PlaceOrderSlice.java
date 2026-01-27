package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.demo.order.domain.CustomerId;
import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderRepository;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.domain.ProductId;
import org.pragmatica.aether.demo.order.domain.Quantity;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.CheckStockRequest;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.ReserveStockRequest;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.StockAvailability;
import org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice.StockReservation;
import org.pragmatica.aether.demo.order.pricing.PricingServiceSlice.CalculateTotalRequest;
import org.pragmatica.aether.demo.order.pricing.PricingServiceSlice.OrderTotal;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

/**
 * PlaceOrder Use Case Slice.
 * <p>
 * Flow:
 * 1. Validate request
 * 2. Check stock for all items (parallel, calls InventoryService)
 * 3. Calculate total price (calls PricingService)
 * 4. Reserve stock for all items (parallel, calls InventoryService)
 * 5. Return order confirmation
 */
public record PlaceOrderSlice(MethodHandle<StockAvailability, CheckStockRequest> checkStockHandle,
                              MethodHandle<OrderTotal, CalculateTotalRequest> calculateTotalHandle,
                              MethodHandle<StockReservation, ReserveStockRequest> reserveStockHandle) implements Slice {
    // === Request ===
    public record PlaceOrderRequest(String customerId, List<OrderItemRequest> items, String discountCode) {
        public record OrderItemRequest(String productId, int quantity) {}
    }

    // === Response ===
    public record PlaceOrderResponse(OrderId orderId, OrderStatus status, Money total, List<String> reservationIds) {}

    // === Validated Input ===
    public record ValidPlaceOrderRequest(CustomerId customerId, List<ValidOrderItem> items, String discountCode) {
        public record ValidOrderItem(String productId, int quantity) {}

        public static Result<ValidPlaceOrderRequest> validPlaceOrderRequest(PlaceOrderRequest raw) {
            return CustomerId.customerId(raw.customerId())
                             .flatMap(customerId -> validateItems(raw.items())
            .map(items -> new ValidPlaceOrderRequest(customerId,
                                                     items,
                                                     raw.discountCode())));
        }

        private static Result<List<ValidOrderItem>> validateItems(List<PlaceOrderRequest.OrderItemRequest> items) {
            if (items == null || items.isEmpty()) {
                return new PlaceOrderError.InvalidRequest("At least one item required").result();
            }
            var validated = items.stream()
                                 .map(item -> Result.all(ProductId.productId(item.productId()),
                                                         Quantity.quantity(item.quantity()))
                                                    .map((pid, qty) -> new ValidOrderItem(pid.value(),
                                                                                          qty.value())))
                                 .toList();
            return Result.allOf(validated);
        }
    }

    // === Errors ===
    public sealed interface PlaceOrderError extends Cause {
        record InvalidRequest(String details) implements PlaceOrderError {
            @Override public String message() {
                return "Invalid order request: " + details;
            }
        }

        record InventoryCheckFailed(Cause cause) implements PlaceOrderError {
            @Override public String message() {
                return "Inventory check failed: " + cause.message();
            }
        }

        record PricingFailed(Cause cause) implements PlaceOrderError {
            @Override public String message() {
                return "Pricing calculation failed: " + cause.message();
            }
        }

        record ReservationFailed(Cause cause) implements PlaceOrderError {
            @Override public String message() {
                return "Stock reservation failed: " + cause.message();
            }
        }
    }

    // === Static Config ===
    private static final String INVENTORY = "org.pragmatica-lite.aether.demo:inventory-service:0.1.0";
    private static final String PRICING = "org.pragmatica-lite.aether.demo:pricing-service:0.1.0";

    // === Factory ===
    public static Result<PlaceOrderSlice> placeOrderSlice(SliceInvokerFacade invoker) {
        return Result.all(invoker.methodHandle(INVENTORY,
                                               "checkStock",
                                               new TypeToken<CheckStockRequest>() {},
                                               new TypeToken<StockAvailability>() {}),
                          invoker.methodHandle(PRICING,
                                               "calculateTotal",
                                               new TypeToken<CalculateTotalRequest>() {},
                                               new TypeToken<OrderTotal>() {}),
                          invoker.methodHandle(INVENTORY,
                                               "reserveStock",
                                               new TypeToken<ReserveStockRequest>() {},
                                               new TypeToken<StockReservation>() {}))
                     .map(PlaceOrderSlice::new);
    }

    // === Slice Implementation ===
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("placeOrder")
                                                   .expect("Invalid method name: placeOrder"),
                                         this::execute,
                                         new TypeToken<PlaceOrderResponse>() {},
                                         new TypeToken<PlaceOrderRequest>() {}));
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
        var stockChecks = request.items()
                                 .stream()
                                 .map(this::checkStock)
                                 .toList();
        return Promise.allOf(stockChecks)
                      .flatMap(results -> validateStockResults(results, request));
    }

    private Promise<StockAvailability> checkStock(ValidPlaceOrderRequest.ValidOrderItem item) {
        return checkStockHandle.invoke(new CheckStockRequest(item.productId(), item.quantity()));
    }

    private Promise<ValidWithStockCheck> validateStockResults(List<Result<StockAvailability>> results,
                                                              ValidPlaceOrderRequest request) {
        var allAvailable = results.stream()
                                  .allMatch(r -> r.isSuccess() && r.expect("Stock check")
                                                                   .sufficient());
        if (!allAvailable) {
            return new PlaceOrderError.InventoryCheckFailed(Causes.cause("Some items not available")).promise();
        }
        return Promise.success(new ValidWithStockCheck(request));
    }

    private Promise<ValidWithPrice> calculateTotal(ValidWithStockCheck context) {
        var lineItems = context.request()
                               .items()
                               .stream()
                               .map(item -> new CalculateTotalRequest.LineItem(item.productId(),
                                                                               item.quantity()))
                               .toList();
        return calculateTotalHandle.invoke(new CalculateTotalRequest(lineItems,
                                                                     context.request()
                                                                            .discountCode()))
                                   .map(total -> new ValidWithPrice(context.request(),
                                                                    total))
                                   .mapError(PlaceOrderError.PricingFailed::new);
    }

    private Promise<ValidWithReservations> reserveAllStock(ValidWithPrice context) {
        var orderId = OrderId.generate();
        var reservations = context.request()
                                  .items()
                                  .stream()
                                  .map(item -> reserveStock(item, orderId))
                                  .toList();
        return Promise.allOf(reservations)
                      .flatMap(results -> validateReservationResults(results, context, orderId));
    }

    private Promise<StockReservation> reserveStock(ValidPlaceOrderRequest.ValidOrderItem item, OrderId orderId) {
        return reserveStockHandle.invoke(new ReserveStockRequest(item.productId(), item.quantity(), orderId.value()));
    }

    private Promise<ValidWithReservations> validateReservationResults(List<Result<StockReservation>> results,
                                                                      ValidWithPrice context,
                                                                      OrderId orderId) {
        var successful = results.stream()
                                .filter(Result::isSuccess)
                                .map(r -> r.expect("Reservation succeeded"))
                                .toList();
        if (successful.size() != results.size()) {
            return new PlaceOrderError.ReservationFailed(Causes.cause("Failed to reserve all items")).promise();
        }
        return Promise.success(new ValidWithReservations(context.request(), context.total(), orderId, successful));
    }

    private PlaceOrderResponse createOrder(ValidWithReservations context) {
        var items = context.request()
                           .items()
                           .stream()
                           .map(this::toOrderItem)
                           .toList();
        var order = new OrderRepository.StoredOrder(context.orderId(),
                                                    context.request()
                                                           .customerId(),
                                                    OrderStatus.CONFIRMED,
                                                    context.total()
                                                           .total(),
                                                    items,
                                                    context.reservations()
                                                           .stream()
                                                           .map(StockReservation::reservationId)
                                                           .toList(),
                                                    java.time.Instant.now(),
                                                    java.time.Instant.now());
        OrderRepository.instance()
                       .save(order);
        return new PlaceOrderResponse(context.orderId(),
                                      OrderStatus.CONFIRMED,
                                      context.total()
                                             .total(),
                                      context.reservations()
                                             .stream()
                                             .map(StockReservation::reservationId)
                                             .toList());
    }

    private OrderRepository.OrderItem toOrderItem(ValidPlaceOrderRequest.ValidOrderItem item) {
        var unitPrice = Money.usd("29.99")
                             .expect("Invalid unit price");
        return new OrderRepository.OrderItem(item.productId(), item.quantity(), unitPrice);
    }

    // === Pipeline Context Records ===
    private record ValidWithStockCheck(ValidPlaceOrderRequest request) {}

    private record ValidWithPrice(ValidPlaceOrderRequest request, OrderTotal total) {}

    private record ValidWithReservations(ValidPlaceOrderRequest request,
                                         OrderTotal total,
                                         OrderId orderId,
                                         List<StockReservation> reservations) {}
}
