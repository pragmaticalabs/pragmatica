package org.pragmatica.aether.example.order;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.ShippingOption;
import org.pragmatica.aether.example.order.PlaceOrder.OrderError;
import org.pragmatica.aether.example.order.PlaceOrder.PlaceOrderRequest;
import org.pragmatica.aether.example.order.StubServices.StubInventory;
import org.pragmatica.aether.example.order.StubServices.StubPayment;
import org.pragmatica.aether.example.shared.LineItem.RawLineItem;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Compensation tests for [PlaceOrder] -- the first test sources this module has had.
///
/// They pin one behaviour: when payment fails after stock was reserved, the releasing call is
/// composed into the chain rather than fired and forgotten. Before that fix the release Promise was
/// dropped, so a failing release was invisible; these tests fail against that version.
class PlaceOrderCompensationTest {
    private static final Cause PAYMENT_DECLINED = Causes.cause("card declined");
    private static final Cause RELEASE_REJECTED = Causes.cause("inventory unreachable");

    private StubInventory inventory;
    private StubPayment payment;
    private PlaceOrder placeOrder;

    @BeforeEach
    void setup() {
        inventory = StubInventory.stubInventory();
        payment = StubPayment.stubPayment();
        placeOrder = PlaceOrder.placeOrder(inventory,
                                           StubServices.stubPricing(),
                                           payment,
                                           StubServices.stubFulfillment());
    }

    @Nested
    class HappyPath {
        @Test
        void execute_succeeds_andReleasesNothing_whenPaymentSucceeds() {
            placeOrder.execute(request())
                      .await()
                      .onFailureRun(() -> fail("Expected success"));

            assertThat(inventory.releasedReservations()).isEmpty();
        }
    }

    @Nested
    class Compensation {
        @Test
        void execute_releasesReservedStock_whenPaymentFails() {
            payment.failWith(PAYMENT_DECLINED);

            placeOrder.execute(request()).await();

            assertThat(inventory.releasedReservations()).containsExactly("RES-1");
        }

        @Test
        void execute_propagatesPaymentFailure_whenReleaseSucceeds() {
            payment.failWith(PAYMENT_DECLINED);

            placeOrder.execute(request())
                      .await()
                      .onSuccessRun(() -> fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause.message()).isEqualTo(PAYMENT_DECLINED.message()));
        }

        @Test
        void execute_reportsBothCauses_whenReleaseAlsoFails() {
            payment.failWith(PAYMENT_DECLINED);
            inventory.failReleaseWith(RELEASE_REJECTED);

            placeOrder.execute(request())
                      .await()
                      .onSuccessRun(() -> fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause.message()).contains(PAYMENT_DECLINED.message())
                                                                     .contains(RELEASE_REJECTED.message()));
        }

        @Test
        void execute_failsWithStockReleaseFailed_whenReleaseAlsoFails() {
            payment.failWith(PAYMENT_DECLINED);
            inventory.failReleaseWith(RELEASE_REJECTED);

            placeOrder.execute(request())
                      .await()
                      .onSuccessRun(() -> fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(OrderError.StockReleaseFailed.class));
        }

        /// The release must GATE the chain: by the time the order resolves, the release has already
        /// been attempted. A fired-and-forgotten Promise cannot guarantee this.
        @Test
        void execute_completesReleaseBeforeResolving_whenPaymentFails() {
            payment.failWith(PAYMENT_DECLINED);

            placeOrder.execute(request()).await();

            assertThat(inventory.releasedReservations()).hasSize(1);
        }
    }

    private static PlaceOrderRequest request() {
        return PlaceOrderRequest.placeOrderRequest("CUST-00000001",
                                                   List.of(new RawLineItem("PROD-0001", 1)),
                                                   new PlaceOrderRequest.RawAddress("1 Main St",
                                                                                    "Springfield",
                                                                                    "IL",
                                                                                    "62701",
                                                                                    "US"),
                                                   new PlaceOrderRequest.RawPaymentMethod("4242424242424242",
                                                                                          "12",
                                                                                          "2030",
                                                                                          "123",
                                                                                          "Alice Tester"),
                                                   ShippingOption.STANDARD,
                                                   null);
    }
}
