package org.pragmatica.aether.example.payment;

import org.pragmatica.aether.example.shared.CustomerId;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.aether.example.shared.OrderId;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment processing slice.
 * Handles credit card authorization, capture, and refunds.
 */
@Slice
public interface PaymentService {
    // === Requests ===
    record ProcessPaymentRequest(OrderId orderId, CustomerId customerId, Money amount, PaymentMethod paymentMethod) {
        public static ProcessPaymentRequest processPaymentRequest(OrderId orderId,
                                                                  CustomerId customerId,
                                                                  Money amount,
                                                                  PaymentMethod paymentMethod) {
            return new ProcessPaymentRequest(orderId, customerId, amount, paymentMethod);
        }
    }

    record RefundRequest(String transactionId, Option<Money> partialAmount, String reason) {
        public static RefundRequest fullRefund(String transactionId, String reason) {
            return new RefundRequest(transactionId, Option.empty(), reason);
        }

        public static RefundRequest partialRefund(String transactionId, Money amount, String reason) {
            return new RefundRequest(transactionId, Option.some(amount), reason);
        }
    }

    // === Responses ===
    record PaymentResult(String transactionId,
                         OrderId orderId,
                         Money amount,
                         String cardType,
                         String maskedCard,
                         Instant processedAt,
                         PaymentStatus status) {
        public enum PaymentStatus {
            AUTHORIZED,
            CAPTURED,
            PENDING_CAPTURE
        }

        public static PaymentResult authorized(OrderId orderId, Money amount, PaymentMethod method) {
            return new PaymentResult(IdGenerator.generate("TXN"),
                                     orderId,
                                     amount,
                                     method.cardType(),
                                     method.maskedNumber(),
                                     Instant.now(),
                                     PaymentStatus.AUTHORIZED);
        }

        public PaymentResult capture() {
            return new PaymentResult(transactionId,
                                     orderId,
                                     amount,
                                     cardType,
                                     maskedCard,
                                     Instant.now(),
                                     PaymentStatus.CAPTURED);
        }
    }

    record RefundResult(String refundId, String originalTransactionId, Money refundedAmount, Instant processedAt) {
        public static RefundResult refundResult(String originalTransactionId, Money amount) {
            return new RefundResult(IdGenerator.generate("REF"), originalTransactionId, amount, Instant.now());
        }
    }

    record PaymentMethod(String cardNumber, String expiryMonth, String expiryYear, String cvv, String cardholderName) {
        private static final Fn1<Cause, String> INVALID_CARD = Causes.forOneValue("Invalid card number: %s");
        private static final Fn1<Cause, String> INVALID_EXPIRY = Causes.forOneValue("Invalid expiry: %s");
        private static final Fn1<Cause, String> INVALID_CVV = Causes.forOneValue("Invalid CVV: %s");

        public static Result<PaymentMethod> paymentMethod(String cardNumber,
                                                          String expiryMonth,
                                                          String expiryYear,
                                                          String cvv,
                                                          String cardholderName) {
            return Result.all(validateCardNumber(cardNumber),
                              validateExpiry(expiryMonth, expiryYear),
                              validateCvv(cvv),
                              Verify.ensure(cardholderName,
                                            Verify.Is::notBlank,
                                            Causes.forOneValue("Invalid cardholder name: {}")))
                         .map((card, _, validCvv, name) -> new PaymentMethod(card,
                                                                             expiryMonth,
                                                                             expiryYear,
                                                                             validCvv,
                                                                             name));
        }

        private static Result<String> validateCardNumber(String number) {
            return Verify.ensure(number, Verify.Is::notBlank, INVALID_CARD)
                         .map(n -> n.replaceAll("\\s+", ""))
                         .filter(INVALID_CARD, PaymentMethod::isValidCardNumber);
        }

        private static boolean isValidCardNumber(String number) {
            if (!number.matches("\\d{13,19}")) return false;
            return luhnCheck(number);
        }

        private static boolean luhnCheck(String number) {
            int sum = 0;
            boolean alternate = false;
            for (int i = number.length() - 1; i >= 0; i--) {
                int n = Character.digit(number.charAt(i), 10);
                if (alternate) {
                    n *= 2;
                    if (n > 9) n -= 9;
                }
                sum += n;
                alternate = !alternate;
            }
            return sum % 10 == 0;
        }

        private static Result<String> validateExpiry(String month, String year) {
            var combined = month + "/" + year;
            return Verify.ensure(month,
                                 m -> m.matches("\\d{2}"),
                                 _ -> INVALID_EXPIRY.apply(combined))
                         .flatMap(_ -> Verify.ensure(year,
                                                     y -> y.matches("\\d{2,4}"),
                                                     _ -> INVALID_EXPIRY.apply(combined)))
                         .map(_ -> combined);
        }

        private static Result<String> validateCvv(String cvv) {
            return Verify.ensure(cvv, c -> c.matches("\\d{3,4}"), INVALID_CVV);
        }

        public String maskedNumber() {
            return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
        }

        public String cardType() {
            return switch (cardNumber.charAt(0)) {
                case '4' -> "Visa";
                case '5' -> "Mastercard";
                case '3' -> "Amex";
                case '6' -> "Discover";
                default -> "Unknown";
            };
        }
    }

    // === Errors ===
    sealed interface PaymentError extends Cause {
        record Declined(String reason) implements PaymentError {
            @Override
            public String message() {
                return "Payment declined: " + reason;
            }
        }

        record TransactionNotFound(String transactionId) implements PaymentError {
            @Override
            public String message() {
                return "Transaction not found: " + transactionId;
            }
        }

        record RefundExceedsOriginal(Money requested, Money original) implements PaymentError {
            @Override
            public String message() {
                return "Refund amount " + requested + " exceeds original payment " + original;
            }
        }

        record InvalidAmount(String reason) implements PaymentError {
            @Override
            public String message() {
                return "Invalid payment amount: " + reason;
            }
        }

        record FraudSuspected() implements PaymentError {
            @Override
            public String message() {
                return "Payment flagged for potential fraud - manual review required";
            }
        }

        record ProcessingFailed(Throwable cause) implements PaymentError {
            @Override
            public String message() {
                return "Payment processing failed: " + cause.getMessage();
            }
        }
    }

    // === Operations ===
    Promise<PaymentResult> processPayment(ProcessPaymentRequest request);

    Promise<RefundResult> processRefund(RefundRequest request);

    // === Factory ===
    static PaymentService paymentService() {
        record paymentService(Map<String, PaymentResult> transactions, Random random) implements PaymentService {
            private static final double DECLINE_RATE = 0.05;
            private static final long FRAUD_CHECK_DELAY_MS = 100;

            @Override
            public Promise<PaymentResult> processPayment(ProcessPaymentRequest request) {
                return simulateFraudCheck().flatMap(_ -> validatePaymentAmount(request.amount()))
                                         .flatMap(_ -> simulateAuthorization(request));
            }

            @Override
            public Promise<RefundResult> processRefund(RefundRequest request) {
                return Option.option(transactions.get(request.transactionId()))
                             .toResult(new PaymentError.TransactionNotFound(request.transactionId()))
                             .flatMap(original -> validateRefundAmount(request, original))
                             .map(refund -> RefundResult.refundResult(request.transactionId(),
                                                                      refund))
                             .async();
            }

            private Result<Money> validateRefundAmount(RefundRequest request, PaymentResult original) {
                var refundAmount = request.partialAmount()
                                          .or(original.amount());
                return original.amount()
                               .isGreaterThan(refundAmount)
                               .flatMap(isGreater -> isGreater || refundAmount.amount()
                                                                              .equals(original.amount()
                                                                                              .amount())
                                                     ? Result.success(refundAmount)
                                                     : new PaymentError.RefundExceedsOriginal(refundAmount,
                                                                                              original.amount()).result());
            }

            private Promise<Unit> simulateFraudCheck() {
                return Promise.lift(PaymentError.ProcessingFailed::new, this::performFraudCheckDelay);
            }

            private Unit performFraudCheckDelay() throws InterruptedException {
                Thread.sleep(FRAUD_CHECK_DELAY_MS);
                return Unit.unit();
            }

            private Promise<Money> validatePaymentAmount(Money amount) {
                if (amount.isZero()) {
                    return new PaymentError.InvalidAmount("Amount cannot be zero").promise();
                }
                if (amount.amount()
                          .compareTo(BigDecimal.valueOf(50000)) > 0) {
                    return new PaymentError.InvalidAmount("Amount exceeds maximum ($50,000)").promise();
                }
                return Promise.success(amount);
            }

            private Promise<PaymentResult> simulateAuthorization(ProcessPaymentRequest request) {
                if (random.nextDouble() < DECLINE_RATE) {
                    return new PaymentError.Declined("Card declined by issuer").promise();
                }
                var cardNumber = request.paymentMethod()
                                        .cardNumber();
                if (cardNumber.endsWith("0000")) {
                    return new PaymentError.Declined("Insufficient funds").promise();
                }
                if (cardNumber.endsWith("1111")) {
                    return new PaymentError.Declined("Card expired").promise();
                }
                if (cardNumber.endsWith("2222")) {
                    return new PaymentError.FraudSuspected().promise();
                }
                var result = PaymentResult.authorized(request.orderId(),
                                                      request.amount(),
                                                      request.paymentMethod())
                                          .capture();
                transactions.put(result.transactionId(), result);
                return Promise.success(result);
            }
        }
        return new paymentService(new ConcurrentHashMap<>(), new Random());
    }
}
