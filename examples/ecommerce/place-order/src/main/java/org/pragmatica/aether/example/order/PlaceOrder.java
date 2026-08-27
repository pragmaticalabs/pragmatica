package org.pragmatica.aether.example.order;

import java.time.Instant;
import java.util.List;

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
import org.pragmatica.lang.Unit;


/// Order placement orchestrator -- the entry slice of the e-commerce example.
///
/// Demonstrates: a Sequencer across four sibling slices (inventory, pricing, payment, fulfillment),
/// Fork-Join for the independent price and shipping quotes, and parse-don't-validate on the inbound
/// request via ValidOrder.
///
/// Compensation (BER): when payment fails after stock was reserved, the releasing call is composed
/// into the chain, so the order does not resolve until the release has finished and a release that
/// itself fails is reported rather than swallowed -- see [#releaseStock].
///
/// Does NOT demonstrate: durable saga state. The whole order lives in one in-process promise chain,
/// so a node that dies between reserving stock and releasing it strands the reservation until it
/// expires on its own; a production saga needs a persisted log to drive the release on restart.
@Slice
public interface PlaceOrder {
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
                                         Option.option(discountCode).filter(s -> !s.isBlank()));
        }
    }

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

        /// Payment failed AND the compensating stock release failed. Both causes are named because
        /// neither alone explains the state the order is in: the customer was not charged, but the
        /// reservation is stranded until it expires.
        record StockReleaseFailed(Cause paymentFailure, Cause releaseFailure) implements OrderError {
            @Override
            public String message() {
                return "Payment failed and the reserved stock could not be released: payment failed: " + paymentFailure.message()
                     + "; stock release ALSO failed: " + releaseFailure.message();
            }
        }

        record ProcessingFailed(Throwable cause) implements OrderError {
            @Override
            public String message() {
                return "Order processing failed: " + cause.getMessage();
            }
        }
    }

    record OrderWithPricing(ValidOrder order, PriceBreakdown pricing, ShippingQuote shippingQuote) {}

    record OrderWithReservation(OrderWithPricing context, StockReservation reservation) {}

    record OrderWithPayment(OrderWithReservation reservation, PaymentResult payment) {}

    record OrderComplete(OrderWithPayment payment, Shipment shipment) {}

    Promise<OrderConfirmation> execute(PlaceOrderRequest request);

    static PlaceOrder placeOrder(InventoryService inventory,
                                 PricingService pricing,
                                 PaymentService payment,
                                 FulfillmentService fulfillment) {
        record placeOrder(InventoryService inventory,
                          PricingService pricing,
                          PaymentService payment,
                          FulfillmentService fulfillment) implements PlaceOrder {
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

                return Promise.all(pricePromise, shippingPromise).flatMap((priceBreakdown, shippingQuote) -> applyDiscount(order,
                                                                                                                           priceBreakdown,
                                                                                                                           shippingQuote));
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
                return Option.from(quote.options()
                                        .stream()
                                        .filter(opt -> opt.option() == order.shippingOption())
                                        .findFirst())
                             .map(ShippingQuote.ShippingOptionQuote::cost)
                             .or(Money.ZERO_USD);
            }

            private Promise<OrderWithReservation> reserveStock(OrderWithPricing context) {
                var reserveRequest = ReserveStockRequest.reserveStockRequest(context.order().orderId(),
                                                                             context.order().items());

                return inventory.reserveStock(reserveRequest)
                                .map(reservation -> new OrderWithReservation(context, reservation));
            }

            private Promise<OrderWithPayment> processPayment(OrderWithReservation context) {
                var paymentRequest = ProcessPaymentRequest.processPaymentRequest(context.context().order().orderId(),
                                                                                 context.context().order().customerId(),
                                                                                 context.context().pricing().total(),
                                                                                 context.context()
                                                                                        .order()
                                                                                        .paymentMethod());

                return payment.processPayment(paymentRequest)
                              .fold(result -> settlePayment(context, result));
            }

            private Promise<OrderWithPayment> settlePayment(OrderWithReservation context,
                                                            Result<PaymentResult> result) {
                return result.fold(cause -> releaseStock(context, cause),
                                   paid -> Promise.success(new OrderWithPayment(context, paid)));
            }

            /// BER -- compensate-by-inverse. Stock was already reserved when payment failed, so the
            /// reservation has to go back.
            ///
            /// `onFailure` cannot express this: it is an independent side effect, started on
            /// resolution and never awaited, so the order would resolve while the release was still
            /// in flight and a failed release would be invisible. `fold` is Promise's error-path
            /// branch -- the primitive `flatMap` is built from -- and the only combinator here that
            /// lets the failure path do more asynchronous work before the chain resolves.
            ///
            /// Guarantee earned: the caller never sees a failed order whose stock is still held
            /// without being told. Mechanism: one in-process releasing call -- no retry and no
            /// durable log, so a crash before it runs leaves the reservation to expire on its own.
            private Promise<OrderWithPayment> releaseStock(OrderWithReservation context, Cause paymentFailure) {
                var releaseRequest = ReleaseStockRequest.releaseStockRequest(context.reservation().reservationId());

                return inventory.releaseStock(releaseRequest)
                                .fold(release -> reportRelease(paymentFailure, release));
            }

            /// Propagates the ORIGINAL payment failure when the release worked -- that is what the
            /// caller asked about -- and a cause naming both when it did not.
            private static Promise<OrderWithPayment> reportRelease(Cause paymentFailure, Result<Unit> release) {
                return release.fold(releaseFailure -> new OrderError.StockReleaseFailed(paymentFailure, releaseFailure).promise(),
                                    _ -> paymentFailure.promise());
            }

            private Promise<OrderComplete> createShipment(OrderWithPayment context) {
                var order = context.reservation().context().order();
                var shipmentRequest = CreateShipmentRequest.createShipmentRequest(order.orderId(),
                                                                                  order.items(),
                                                                                  order.shippingAddress(),
                                                                                  order.shippingOption());

                return fulfillment.createShipment(shipmentRequest)
                                  .map(shipment -> new OrderComplete(context, shipment));
            }

            private OrderConfirmation buildConfirmation(OrderComplete complete) {
                return OrderConfirmation.confirmed(complete.payment().reservation().context().order(),
                                                   complete.payment().reservation().context().pricing(),
                                                   complete.payment().payment(),
                                                   complete.shipment());
            }
        }

        return new placeOrder(inventory, pricing, payment, fulfillment);
    }
}
