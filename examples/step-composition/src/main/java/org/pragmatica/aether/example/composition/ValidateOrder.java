package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.example.composition.shared.Money;
import org.pragmatica.aether.example.composition.shared.OrderError;
import org.pragmatica.aether.example.composition.shared.OrderId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.Result.all;


/// Pure validation step — no resources needed.
///
/// This step demonstrates several composition concepts:
///
/// 1. **No @Slice annotation** — it is a plain interface, not a slice.
///    The annotation processor discovers it as a dependency of OrderProcessor
///    by scanning the factory method parameters.
///
/// 2. **Zero-parameter factory** — `validateOrder()` takes no arguments,
///    so the processor constructs it directly without resource provisioning.
///
/// 3. **Leaf pattern** — a single atomic operation that validates input
///    and produces a validated type (ValidOrder). No I/O, no side effects.
///
/// 4. **Result.all() for parallel validation** — all fields are validated
///    independently; failures are collected, not short-circuited.
public interface ValidateOrder {

    Promise<ValidOrder> apply(OrderProcessor.OrderRequest raw);

    /// Validated order — only constructible through the `validOrder` factory.
    /// Guarantees all fields have passed validation.
    record ValidOrder(OrderId orderId, String customerId, String productId, int quantity, Money amount) {

        public static Result<ValidOrder> validOrder(OrderProcessor.OrderRequest raw) {
            return all(
                Result.success(OrderId.generate()),
                validateCustomerId(raw.customerId()),
                validateProductId(raw.productId()),
                validateQuantity(raw.quantity()),
                Money.money(raw.amount())
            ).map(ValidOrder::new);
        }

        private static Result<String> validateCustomerId(String customerId) {
            return Verify.ensure(customerId, Verify.Is::notBlank, OrderError.ValidationFailed::new);
        }

        private static Result<String> validateProductId(String productId) {
            return Verify.ensure(productId, Verify.Is::notBlank, OrderError.ValidationFailed::new);
        }

        private static Result<Integer> validateQuantity(int quantity) {
            return Verify.ensure(quantity, q -> q > 0, _ -> new OrderError.ValidationFailed("Quantity must be positive"));
        }
    }

    /// Factory with zero parameters — the processor constructs this step
    /// directly without any resource provisioning.
    static ValidateOrder validateOrder() {
        return raw -> ValidOrder.validOrder(raw).async();
    }
}
