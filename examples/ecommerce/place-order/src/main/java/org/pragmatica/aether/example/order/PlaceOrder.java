package org.pragmatica.aether.example.order;

import org.pragmatica.aether.example.fulfillment.FulfillmentService;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.CalculateShippingRequest;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.CreateShipmentRequest;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.Shipment;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.ShippingOption;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.ShippingQuote;
import org.pragmatica.aether.example.inventory.InventoryService;
import org.pragmatica.aether.example.inventory.InventoryService.CheckStockRequest;
import org.pragmatica.aether.example.inventory.InventoryService.ReleaseStockRequest;
import org.pragmatica.aether.example.inventory.InventoryService.ReserveStockRequest;
import org.pragmatica.aether.example.inventory.InventoryService.StockReservation;
import org.pragmatica.aether.example.payment.PaymentService;
import org.pragmatica.aether.example.payment.PaymentService.PaymentMethod;
import org.pragmatica.aether.example.payment.PaymentService.PaymentResult;
import org.pragmatica.aether.example.payment.PaymentService.ProcessPaymentRequest;
import org.pragmatica.aether.example.pricing.PricingService;
import org.pragmatica.aether.example.pricing.PricingService.ApplyDiscountRequest;
import org.pragmatica.aether.example.pricing.PricingService.CalculatePriceRequest;
import org.pragmatica.aether.example.pricing.PricingService.CalculateTaxRequest;
import org.pragmatica.aether.example.pricing.PricingService.DiscountResult;
import org.pragmatica.aether.example.pricing.PricingService.PriceBreakdown;
import org.pragmatica.aether.example.pricing.PricingService.TaxResult;
import org.pragmatica.aether.example.shared.Address;
import org.pragmatica.aether.example.shared.CustomerId;
import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.aether.example.shared.OrderId;
import org.pragmatica.aether.example.shared.ProductId;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.time.Instant;
import java.util.List;

/**
 * Place Order - Lean Slice orchestrating the complete order flow.
 * <p>
 * This demonstrates:
 * <ul>
 *   <li>Inter-slice dependencies (Inventory, Pricing, Payment, Fulfillment)</li>
 *   <li>Complex multi-step orchestration using sequencer pattern</li>
 *   <li>Fork-join for parallel operations (pricing + shipping quote)</li>
 *   <li>Error handling with compensation (release stock on payment failure)</li>
 *   <li>Parse-don't-validate pattern for request validation</li>
 * </ul>
 */
@Slice
public interface PlaceOrder {
    // === Request ===
    record PlaceOrderRequest(String customerId,
                             List<LineItem.RawLineItem> items,
                             RawAddress shippingAddress,
                             RawPaymentMethod paymentMethod,
                             ShippingOption shippingOption,
                             Option<String> discountCode) {
        public record RawAddress(String street, String city, String state, String postalCode, String country) {}

        public record RawPaymentMethod(String cardNumber,
                                       String expiryMonth,
                                       String expiryYear,
                                       String cvv,
                                       String cardholderName) {}

        public static PlaceOrderRequest placeOrderRequest(String customerId,
                                                          List<LineItem.RawLineItem> items,
                                                          RawAddress address,
                                                          RawPaymentMethod payment,
                                                          ShippingOption shipping,
                                                          String discountCode) {
            return new PlaceOrderRequest(customerId,
                                         items,
                                         address,
                                         payment,
                                         shipping,
                                         Option.option(discountCode)
                                               .filter(s -> !s.isBlank()));
        }
    }

    // === Response ===
    record OrderConfirmation(OrderId orderId,
                             CustomerId customerId,
                             List<LineItem> items,
                             PriceBreakdown pricing,
                             PaymentResult payment,
                             Shipment shipment,
                             OrderStatus status,
                             Instant createdAt) {
        public enum OrderStatus {
            CONFIRMED,
            PROCESSING,
            SHIPPED,
            DELIVERED,
            CANCELLED
        }

        public static OrderConfirmation confirmed(ValidOrder order,
                                                  PriceBreakdown pricing,
                                                  PaymentResult payment,
                                                  Shipment shipment) {
            return new OrderConfirmation(order.orderId(),
                                         order.customerId(),
                                         order.items(),
                                         pricing,
                                         payment,
                                         shipment,
                                         OrderStatus.CONFIRMED,
                                         Instant.now());
        }

        public Money total() {
            return pricing.total();
        }

        public String summary() {
            return String.format("Order %s confirmed. Total: %s. Tracking: %s. Estimated delivery: %s",
                                 orderId.value(),
                                 pricing.total(),
                                 shipment.trackingNumber(),
                                 shipment.estimatedDelivery());
        }
    }

    // === Validated Input ===
    record ValidOrder(OrderId orderId,
                      CustomerId customerId,
                      List<LineItem> items,
                      Address shippingAddress,
                      PaymentMethod paymentMethod,
                      ShippingOption shippingOption,
                      Option<String> discountCode) {
        public static Result<ValidOrder> validOrder(PlaceOrderRequest raw) {
            return Result.all(CustomerId.customerId(raw.customerId()),
                              LineItem.lineItems(raw.items()),
                              validateAddress(raw.shippingAddress()),
                              validatePayment(raw.paymentMethod()))
                         .map((customerId, items, address, payment) -> new ValidOrder(OrderId.generate(),
                                                                                      customerId,
                                                                                      items,
                                                                                      address,
                                                                                      payment,
                                                                                      raw.shippingOption(),
                                                                                      raw.discountCode()));
        }

        private static Result<Address> validateAddress(PlaceOrderRequest.RawAddress raw) {
            return Address.address(raw.street(), raw.city(), raw.state(), raw.postalCode(), raw.country());
        }

        private static Result<PaymentMethod> validatePayment(PlaceOrderRequest.RawPaymentMethod raw) {
            return PaymentMethod.paymentMethod(raw.cardNumber(),
                                               raw.expiryMonth(),
                                               raw.expiryYear(),
                                               raw.cvv(),
                                               raw.cardholderName());
        }
    }

    // === Errors ===
    sealed interface OrderError extends Cause {
        record ValidationFailed(List<String> errors) implements OrderError {
            @Override
            public String message() {
                return "Order validation failed: " + String.join(", ", errors);
            }
        }

        record OutOfStock(List<ProductId> products) implements OrderError {
            @Override
            public String message() {
                return "Items out of stock: " + products.stream()
                                                       .map(ProductId::value)
                                                       .toList();
            }
        }

        record PaymentDeclined(String reason) implements OrderError {
            @Override
            public String message() {
                return "Payment declined: " + reason;
            }
        }

        record FulfillmentFailed(String reason) implements OrderError {
            @Override
            public String message() {
                return "Cannot fulfill order: " + reason;
            }
        }

        record ProcessingFailed(Throwable cause) implements OrderError {
            @Override
            public String message() {
                return "Order processing failed: " + cause.getMessage();
            }
        }
    }

    // === Operation ===
    Promise<OrderConfirmation> execute(PlaceOrderRequest request);

    // === Factory ===
    static PlaceOrder placeOrder(InventoryService inventory,
                                 PricingService pricing,
                                 PaymentService payment,
                                 FulfillmentService fulfillment) {
        return new PlaceOrderImpl(inventory, pricing, payment, fulfillment);
    }
}

class PlaceOrderImpl implements PlaceOrder {
    private final InventoryService inventory;
    private final PricingService pricing;
    private final PaymentService payment;
    private final FulfillmentService fulfillment;

    PlaceOrderImpl(InventoryService inventory,
                   PricingService pricing,
                   PaymentService payment,
                   FulfillmentService fulfillment) {
        this.inventory = inventory;
        this.pricing = pricing;
        this.payment = payment;
        this.fulfillment = fulfillment;
    }

    @Override
    public Promise<OrderConfirmation> execute(PlaceOrderRequest request) {
        return ValidOrder.validOrder(request)
                         .async()
                         .flatMap(this::checkStockAvailability)
                         .flatMap(this::calculateFullPricing)
                         .flatMap(this::reserveStock)
                         .flatMap(this::processPayment)
                         .flatMap(this::createShipment)
                         .map(this::buildConfirmation);
    }

    private Promise<ValidOrder> checkStockAvailability(ValidOrder order) {
        var checkRequest = CheckStockRequest.checkStockRequest(order.items());
        return inventory.checkStock(checkRequest)
                        .flatMap(availability -> availability.isFullyAvailable()
                                                 ? Promise.success(order)
                                                 : new OrderError.OutOfStock(availability.unavailableItems()).promise());
    }

    private Promise<OrderWithPricing> calculateFullPricing(ValidOrder order) {
        var pricePromise = pricing.calculatePrice(CalculatePriceRequest.calculatePriceRequest(order.customerId(),
                                                                                              order.items()));
        var shippingPromise = fulfillment.calculateShipping(CalculateShippingRequest.calculateShippingRequest(order.items(),
                                                                                                              order.shippingAddress()));
        return Promise.all(pricePromise, shippingPromise)
                      .flatMap((priceBreakdown, shippingQuote) -> applyDiscount(order, priceBreakdown, shippingQuote));
    }

    private Promise<OrderWithPricing> applyDiscount(ValidOrder order,
                                                    PriceBreakdown basePrice,
                                                    ShippingQuote shippingQuote) {
        var discountRequest = order.discountCode()
                                   .map(code -> ApplyDiscountRequest.applyDiscountRequest(order.customerId(),
                                                                                          basePrice.subtotal(),
                                                                                          code))
                                   .or(() -> ApplyDiscountRequest.withoutCode(order.customerId(),
                                                                              basePrice.subtotal()));
        return pricing.applyDiscount(discountRequest)
                      .flatMap(discount -> calculateTaxAndBuildPrice(order, basePrice, shippingQuote, discount));
    }

    private Promise<OrderWithPricing> calculateTaxAndBuildPrice(ValidOrder order,
                                                                PriceBreakdown basePrice,
                                                                ShippingQuote shippingQuote,
                                                                DiscountResult discount) {
        return basePrice.subtotal()
                        .subtract(discount.discountAmount())
                        .map(subtotalAfterDiscount -> CalculateTaxRequest.calculateTaxRequest(subtotalAfterDiscount,
                                                                                              order.shippingAddress()))
                        .async()
                        .flatMap(pricing::calculateTax)
                        .flatMap(tax -> buildFinalPrice(basePrice, shippingQuote, order, discount, tax))
                        .map(finalPrice -> new OrderWithPricing(order, finalPrice, shippingQuote));
    }

    private Promise<PriceBreakdown> buildFinalPrice(PriceBreakdown basePrice,
                                                    ShippingQuote shippingQuote,
                                                    ValidOrder order,
                                                    DiscountResult discount,
                                                    TaxResult tax) {
        var shippingCost = findShippingCost(shippingQuote, order);
        return PriceBreakdown.builder()
                             .linePrices(basePrice.linePrices())
                             .subtotal(basePrice.subtotal())
                             .discountAmount(discount.discountAmount())
                             .taxAmount(tax.taxAmount())
                             .shippingCost(shippingCost)
                             .build()
                             .async();
    }

    private Money findShippingCost(ShippingQuote quote, ValidOrder order) {
        return quote.options()
                    .stream()
                    .filter(opt -> opt.option() == order.shippingOption())
                    .findFirst()
                    .map(ShippingQuote.ShippingOptionQuote::cost)
                    .orElse(Money.ZERO_USD);
    }

    private Promise<OrderWithReservation> reserveStock(OrderWithPricing context) {
        var reserveRequest = ReserveStockRequest.reserveStockRequest(context.order()
                                                                            .orderId(),
                                                                     context.order()
                                                                            .items());
        return inventory.reserveStock(reserveRequest)
                        .map(reservation -> new OrderWithReservation(context, reservation));
    }

    private Promise<OrderWithPayment> processPayment(OrderWithReservation context) {
        var paymentRequest = ProcessPaymentRequest.processPaymentRequest(context.context()
                                                                                .order()
                                                                                .orderId(),
                                                                         context.context()
                                                                                .order()
                                                                                .customerId(),
                                                                         context.context()
                                                                                .pricing()
                                                                                .total(),
                                                                         context.context()
                                                                                .order()
                                                                                .paymentMethod());
        return payment.processPayment(paymentRequest)
                      .map(result -> new OrderWithPayment(context, result))
                      .onFailure(cause -> releaseStockOnFailure(context));
    }

    private void releaseStockOnFailure(OrderWithReservation context) {
        var releaseRequest = ReleaseStockRequest.releaseStockRequest(context.reservation()
                                                                            .reservationId());
        inventory.releaseStock(releaseRequest);
    }

    private Promise<OrderComplete> createShipment(OrderWithPayment context) {
        var order = context.reservation()
                           .context()
                           .order();
        var shipmentRequest = CreateShipmentRequest.createShipmentRequest(order.orderId(),
                                                                          order.items(),
                                                                          order.shippingAddress(),
                                                                          order.shippingOption());
        return fulfillment.createShipment(shipmentRequest)
                          .map(shipment -> new OrderComplete(context, shipment));
    }

    private OrderConfirmation buildConfirmation(OrderComplete complete) {
        return OrderConfirmation.confirmed(complete.payment()
                                                   .reservation()
                                                   .context()
                                                   .order(),
                                           complete.payment()
                                                   .reservation()
                                                   .context()
                                                   .pricing(),
                                           complete.payment()
                                                   .payment(),
                                           complete.shipment());
    }

    // === Context Records for Pipeline ===
    private record OrderWithPricing(ValidOrder order, PriceBreakdown pricing, ShippingQuote shippingQuote) {}

    private record OrderWithReservation(OrderWithPricing context, StockReservation reservation) {}

    private record OrderWithPayment(OrderWithReservation reservation, PaymentResult payment) {}

    private record OrderComplete(OrderWithPayment payment, Shipment shipment) {}
}
