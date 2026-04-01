package org.pragmatica.aether.example.pgshowcase;

import org.pragmatica.aether.example.pgshowcase.OrderPersistence.CreateOrderRequest;
import org.pragmatica.aether.example.pgshowcase.OrderPersistence.OrderRow;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.math.BigDecimal;

/// Order processing slice demonstrating @PgSql persistence usage.
@Slice public interface OrderProcessing {
    record PlaceOrderRequest(String userId, String total) {
        private static final Cause EMPTY_USER_ID = OrderError.validationFailed("User ID cannot be empty");
        private static final Cause EMPTY_TOTAL = OrderError.validationFailed("Total cannot be empty");

        public static Result<PlaceOrderRequest> placeOrderRequest(String userId, String total) {
            return Result.all(Verify.ensure(userId, Verify.Is::notBlank, EMPTY_USER_ID),
                              Verify.ensure(total, Verify.Is::notBlank, EMPTY_TOTAL))
            .map(PlaceOrderRequest::new);
        }
    }

    record OrderConfirmation(long orderId, long userId, BigDecimal total, String status) {
        public static OrderConfirmation fromRow(OrderRow row) {
            return new OrderConfirmation(row.id(), row.userId(), row.total(), row.status());
        }
    }

    sealed interface OrderError extends Cause {
        record ValidationFailed(String reason) implements OrderError {
            @Override public String message() {
                return "Order validation failed: " + reason;
            }
        }

        static ValidationFailed validationFailed(String reason) {
            return new ValidationFailed(reason);
        }
    }

    record GetOrderRequest(long orderId) {
        private static final Cause INVALID_ORDER_ID = OrderError.validationFailed("Order ID must be a positive number");

        public static Result<GetOrderRequest> getOrderRequest(long orderId) {
            return Verify.ensure(orderId, id -> id > 0, INVALID_ORDER_ID).map(GetOrderRequest::new);
        }
    }

    Promise<OrderConfirmation> placeOrder(PlaceOrderRequest request);
    Promise<Option<OrderRow>> getOrder(GetOrderRequest request);

    static OrderProcessing orderProcessing(OrderPersistence orders, UserPersistence users) {
        record orderProcessing( OrderPersistence orders, UserPersistence users) implements OrderProcessing {
            private static final Cause INVALID_USER_ID = OrderError.validationFailed("User ID must be a positive number");
            private static final Cause INVALID_TOTAL = OrderError.validationFailed("Total must be a positive number");

            @Override public Promise<OrderConfirmation> placeOrder(PlaceOrderRequest request) {
                return validateInput(request).async()
                                    .flatMap(this::verifyUserExists)
                                    .flatMap(this::createOrder)
                                    .map(OrderConfirmation::fromRow);
            }

            @Override public Promise<Option<OrderRow>> getOrder(GetOrderRequest request) {
                return orders.findById(request.orderId());
            }

            private Promise<ValidInput> verifyUserExists(ValidInput valid) {
                return users.existsById(valid.userId())
                .flatMap(exists -> exists
                                  ? Promise.success(valid)
                                  : OrderError.validationFailed("User does not exist").promise());
            }

            private Promise<OrderRow> createOrder(ValidInput valid) {
                return orders.createOrder(new CreateOrderRequest(valid.userId(), valid.total(), "pending"));
            }

            private static Result<ValidInput> validateInput(PlaceOrderRequest raw) {
                return Result.all(parseLong(raw.userId()), parseBigDecimal(raw.total())).map(ValidInput::new);
            }

            private static Result<Long> parseLong(String value) {
                return Result.lift(() -> Long.parseLong(value.trim())).filter(_ -> INVALID_USER_ID, id -> id > 0);
            }

            private static Result<BigDecimal> parseBigDecimal(String value) {
                return Result.lift(() -> new BigDecimal(value.trim()))
                .filter(_ -> INVALID_TOTAL, total -> total.compareTo(BigDecimal.ZERO) > 0);
            }
        }
        return new orderProcessing(orders, users);
    }

    record ValidInput(long userId, BigDecimal total){}
}
