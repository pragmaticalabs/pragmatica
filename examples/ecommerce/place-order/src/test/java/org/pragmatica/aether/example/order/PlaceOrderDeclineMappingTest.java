package org.pragmatica.aether.example.order;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.fulfillment.FulfillmentService.ShippingOption;
import org.pragmatica.aether.example.order.PlaceOrder.OrderError;
import org.pragmatica.aether.example.order.PlaceOrder.PlaceOrderRequest;
import org.pragmatica.aether.example.order.StubServices.StubInventory;
import org.pragmatica.aether.example.order.StubServices.StubPayment;
import org.pragmatica.aether.example.payment.PaymentService.PaymentError;
import org.pragmatica.aether.example.shared.LineItem.RawLineItem;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2267 -- a declined payment answers 402. The `HTTP_402` pattern in `routes.toml` named a type that
/// does not exist (`*PaymentFailed*`), and the payment slice's own `Declined` is not a Cause of this
/// package, so the generated error mapper never saw it and answered 500.
///
/// The tests drive a real decline through [PlaceOrder] and then through the GENERATED mapper
/// (`PlaceOrderRoutes#errorMapper`), so the `routes.toml` pattern is exercised, not restated.
class PlaceOrderDeclineMappingTest {
    private StubPayment payment;
    private PlaceOrder placeOrder;

    @BeforeEach
    void setup() {
        payment = StubPayment.stubPayment();
        placeOrder = PlaceOrder.placeOrder(StubInventory.stubInventory(),
                                           StubServices.stubPricing(),
                                           payment,
                                           StubServices.stubFulfillment());
    }

    @Test
    void execute_failsWithPaymentDeclined_whenPaymentSliceDeclines() {
        payment.failWith(new PaymentError.Declined("Card declined by issuer"));

        assertThat(failureOf()).isInstanceOf(OrderError.PaymentDeclined.class)
                               .extracting(Cause::message)
                               .isEqualTo("Payment declined: Card declined by issuer");
    }

    @Test
    void declinedPayment_answers402_throughTheGeneratedErrorMapper() {
        payment.failWith(new PaymentError.Declined("Insufficient funds"));

        assertThat(statusOf(failureOf())).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void nonDeclinePaymentFailure_staysUnmapped_andAnswers500() {
        payment.failWith(Causes.cause("gateway unreachable"));

        assertThat(statusOf(failureOf())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private Cause failureOf() {
        return placeOrder.execute(request())
                         .await()
                         .fold(cause -> cause,
                               _ -> fail("Expected the order to fail"));
    }

    private static HttpStatus statusOf(Cause cause) {
        return new PlaceOrderRoutes().errorMapper()
                                     .map(cause)
                                     .status();
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
