package org.pragmatica.aether.example.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.example.fulfillment.FulfillmentService;
import org.pragmatica.aether.example.inventory.InventoryService;
import org.pragmatica.aether.example.payment.PaymentService;
import org.pragmatica.aether.example.pricing.PricingService;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Hand-rolled doubles for the four slices [PlaceOrder] orchestrates.
///
/// The module had no test sources before this; these exist to drive one behaviour -- the
/// compensating stock release after a failed payment -- so each stub does the least that lets the
/// pipeline reach `processPayment`, and only inventory and payment are configurable.
final class StubServices {
    private StubServices() {}

    /// Inventory stub. `releaseStock` records every call so a test can prove the compensation ran,
    /// and can be made to fail to drive the release-failure branch.
    static final class StubInventory implements InventoryService {
        private final List<String> releasedReservations = new CopyOnWriteArrayList<>();
        private Option<Cause> releaseFailure = Option.empty();

        static StubInventory stubInventory() {
            return new StubInventory();
        }

        void failReleaseWith(Cause cause) {
            releaseFailure = Option.present(cause);
        }

        List<String> releasedReservations() {
            return List.copyOf(releasedReservations);
        }

        @Override
        public Promise<StockAvailability> checkStock(CheckStockRequest request) {
            return Promise.success(StockAvailability.fullyAvailable(Map.of()));
        }

        @Override
        public Promise<StockReservation> reserveStock(ReserveStockRequest request) {
            return Promise.success(new StockReservation("RES-1",
                                                        request.orderId(),
                                                        Instant.now().plusSeconds(600)));
        }

        @Override
        public Promise<Unit> releaseStock(ReleaseStockRequest request) {
            releasedReservations.add(request.reservationId());

            return releaseFailure.map(Cause::<Unit>promise).or(Promise::unitPromise);
        }
    }

    /// Payment stub -- always fails in these tests, since the compensation is what is under test.
    static final class StubPayment implements PaymentService {
        private Option<Cause> failure = Option.empty();

        static StubPayment stubPayment() {
            return new StubPayment();
        }

        void failWith(Cause cause) {
            failure = Option.present(cause);
        }

        @Override
        public Promise<PaymentResult> processPayment(ProcessPaymentRequest request) {
            return failure.map(Cause::<PaymentResult>promise)
                          .or(() -> Promise.success(PaymentResult.authorized(request.orderId(),
                                                                             request.amount(),
                                                                             request.paymentMethod())));
        }

        @Override
        public Promise<RefundResult> processRefund(RefundRequest request) {
            return Promise.success(RefundResult.refundResult(request.transactionId(), Money.ZERO_USD));
        }
    }

    static PricingService stubPricing() {
        record stubPricing() implements PricingService {
            @Override
            public Promise<PriceBreakdown> calculatePrice(CalculatePriceRequest request) {
                return PriceBreakdown.builder().subtotal(Money.ZERO_USD).build().async();
            }

            @Override
            public Promise<DiscountResult> applyDiscount(ApplyDiscountRequest request) {
                return Promise.success(DiscountResult.noDiscount());
            }

            @Override
            public Promise<TaxResult> calculateTax(CalculateTaxRequest request) {
                return Promise.success(TaxResult.taxResult(Money.ZERO_USD, BigDecimal.ZERO, "TEST"));
            }
        }

        return new stubPricing();
    }

    static FulfillmentService stubFulfillment() {
        record stubFulfillment() implements FulfillmentService {
            @Override
            public Promise<ShippingQuote> calculateShipping(CalculateShippingRequest request) {
                return Promise.success(ShippingQuote.quote(List.of()));
            }

            @Override
            public Promise<Shipment> createShipment(CreateShipmentRequest request) {
                return Promise.success(Shipment.shipment(request.orderId(),
                                                         request.shippingAddress(),
                                                         request.shippingOption()));
            }

            /// Never reached by PlaceOrder; fails loudly rather than fabricating a shipment.
            @Override
            public Promise<TrackingInfo> trackShipment(TrackShipmentRequest request) {
                return Causes.cause("trackShipment is not stubbed").promise();
            }
        }

        return new stubFulfillment();
    }
}
