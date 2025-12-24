package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Pricing Service Slice - handles product pricing and order total calculation.
 */
public record PricingServiceSlice() implements Slice {

    private static final Map<String, BigDecimal> PRICES = Map.of(
        "PROD-ABC123", new BigDecimal("29.99"),
        "PROD-DEF456", new BigDecimal("49.99"),
        "PROD-GHI789", new BigDecimal("99.99")
    );

    private static final Map<String, BigDecimal> DISCOUNTS = Map.of(
        "SAVE10", new BigDecimal("0.10"),
        "SAVE20", new BigDecimal("0.20")
    );

    public static PricingServiceSlice pricingServiceSlice() {
        return new PricingServiceSlice();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("getPrice").unwrap(),
                this::getPrice,
                new TypeToken<ProductPrice>() {},
                new TypeToken<GetPriceRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("calculateTotal").unwrap(),
                this::calculateTotal,
                new TypeToken<OrderTotal>() {},
                new TypeToken<CalculateTotalRequest>() {}
            )
        );
    }

    private Promise<ProductPrice> getPrice(GetPriceRequest request) {
        var price = PRICES.get(request.productId());

        if (price == null) {
            return new PricingError.PriceNotFound(request.productId()).promise();
        }

        return Promise.success(new ProductPrice(request.productId(), Money.usd(price)));
    }

    private Promise<OrderTotal> calculateTotal(CalculateTotalRequest request) {
        var subtotal = BigDecimal.ZERO;

        for (var item : request.items()) {
            var price = PRICES.get(item.productId());
            if (price == null) {
                return new PricingError.PriceNotFound(item.productId()).promise();
            }
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(item.quantity())));
        }

        var discountRate = request.discountCode() != null ? DISCOUNTS.get(request.discountCode()) : null;
        var discount = discountRate != null ? subtotal.multiply(discountRate) : BigDecimal.ZERO;
        var total = subtotal.subtract(discount);

        return Promise.success(new OrderTotal(
            Money.usd(subtotal),
            Money.usd(discount),
            Money.usd(total)
        ));
    }
}
