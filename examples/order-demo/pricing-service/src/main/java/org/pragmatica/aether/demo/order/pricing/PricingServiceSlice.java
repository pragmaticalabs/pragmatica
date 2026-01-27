package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Pricing Service Slice - handles product pricing and order total calculation.
 */
public record PricingServiceSlice() implements Slice {
    // === Requests ===
    public record GetPriceRequest(String productId) {}

    public record CalculateTotalRequest(List<LineItem> items, String discountCode) {
        public record LineItem(String productId, int quantity) {}
    }

    // === Responses ===
    public record ProductPrice(String productId, Money unitPrice) {}

    public record OrderTotal(Money subtotal, Money discount, Money total) {}

    // === Errors ===
    public sealed interface PricingError extends Cause {
        record PriceNotFound(String productId) implements PricingError {
            @Override
            public String message() {
                return "Price not found for product: " + productId;
            }
        }

        record DiscountCodeInvalid(String code) implements PricingError {
            @Override
            public String message() {
                return "Invalid discount code: " + code;
            }
        }
    }

    // === Static Data ===
    private static final Map<String, BigDecimal> PRICES = Map.of("PROD-ABC123",
                                                                 new BigDecimal("29.99"),
                                                                 "PROD-DEF456",
                                                                 new BigDecimal("49.99"),
                                                                 "PROD-GHI789",
                                                                 new BigDecimal("99.99"));

    private static final Map<String, BigDecimal> DISCOUNTS = Map.of("SAVE10",
                                                                    new BigDecimal("0.10"),
                                                                    "SAVE20",
                                                                    new BigDecimal("0.20"));

    // === Factory ===
    public static PricingServiceSlice pricingServiceSlice() {
        return new PricingServiceSlice();
    }

    // === Slice Implementation ===
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("getPrice")
                                                   .expect("Invalid method name: getPrice"),
                                         this::getPrice,
                                         new TypeToken<ProductPrice>() {},
                                         new TypeToken<GetPriceRequest>() {}),
                       new SliceMethod<>(MethodName.methodName("calculateTotal")
                                                   .expect("Invalid method name: calculateTotal"),
                                         this::calculateTotal,
                                         new TypeToken<OrderTotal>() {},
                                         new TypeToken<CalculateTotalRequest>() {}));
    }

    private Promise<ProductPrice> getPrice(GetPriceRequest request) {
        return Option.option(PRICES.get(request.productId()))
                     .toResult(new PricingError.PriceNotFound(request.productId()))
                     .flatMap(Money::usd)
                     .map(price -> new ProductPrice(request.productId(),
                                                    price))
                     .async();
    }

    private Promise<OrderTotal> calculateTotal(CalculateTotalRequest request) {
        return calculateSubtotal(request.items()).flatMap(subtotal -> applyDiscount(subtotal,
                                                                                    request.discountCode()))
                                .async();
    }

    private Result<BigDecimal> calculateSubtotal(List<CalculateTotalRequest.LineItem> items) {
        var missingProduct = items.stream()
                                  .filter(item -> !PRICES.containsKey(item.productId()))
                                  .findFirst();
        if (missingProduct.isPresent()) {
            return new PricingError.PriceNotFound(missingProduct.get()
                                                                .productId()).result();
        }
        var subtotal = items.stream()
                            .map(item -> PRICES.get(item.productId())
                                               .multiply(BigDecimal.valueOf(item.quantity())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Result.success(subtotal);
    }

    private Result<OrderTotal> applyDiscount(BigDecimal subtotal, String discountCode) {
        var discount = Option.option(discountCode)
                             .flatMap(code -> Option.option(DISCOUNTS.get(code)))
                             .map(subtotal::multiply)
                             .or(BigDecimal.ZERO);
        var total = subtotal.subtract(discount);
        return Result.all(Money.usd(subtotal),
                          Money.usd(discount),
                          Money.usd(total))
                     .map(OrderTotal::new);
    }
}
