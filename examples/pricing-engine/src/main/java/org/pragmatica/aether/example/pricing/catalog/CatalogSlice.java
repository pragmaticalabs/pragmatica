package org.pragmatica.aether.example.pricing.catalog;

import org.pragmatica.aether.example.pricing.discount.DiscountSlice;
import org.pragmatica.aether.example.pricing.discount.DiscountSlice.DiscountRequest;
import org.pragmatica.aether.example.pricing.tax.TaxSlice;
import org.pragmatica.aether.example.pricing.tax.TaxSlice.TaxRequest;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Catalog slice — orchestrates pricing by combining DB lookup, discount, tax, and event publishing.
///
/// Queries the `products` table for base price, conditionally calls DiscountSlice and TaxSlice,
/// publishes a HighValueOrderEvent for totals exceeding $500, and returns the full price breakdown.
@Slice
public interface CatalogSlice {
    int HIGH_VALUE_THRESHOLD_CENTS = 50000;

    record HighValueOrderEvent(String productId, int quantity, int totalCents, String regionCode) {
        static HighValueOrderEvent highValueOrderEvent(String productId,
                                                       int quantity,
                                                       int totalCents,
                                                       String regionCode) {
            return new HighValueOrderEvent(productId, quantity, totalCents, regionCode);
        }
    }

    record PriceRequest(String productId, int quantity, String regionCode, String couponCode) {
        public PriceRequest {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(regionCode, "regionCode");
            couponCode = Objects.requireNonNullElse(couponCode, "");
        }

        static PriceRequest priceRequest(String productId, int quantity, String regionCode, String couponCode) {
            return new PriceRequest(productId, quantity, regionCode, couponCode);
        }
    }

    record PriceResponse(int basePrice, int discountAmount, int taxAmount, int totalPrice, List<String> callPath) {
        public PriceResponse {
            callPath = List.copyOf(callPath);
        }

        static PriceResponse priceResponse(int basePrice,
                                           int discountAmount,
                                           int taxAmount,
                                           int totalPrice,
                                           List<String> callPath) {
            return new PriceResponse(basePrice, discountAmount, taxAmount, totalPrice, callPath);
        }
    }

    sealed interface PricingError extends Cause {
        record ProductNotFound(String productId) implements PricingError {
            @Override
            public String message() {
                return "Product not found: " + productId;
            }
        }

        record InvalidRequest(String reason) implements PricingError {
            @Override
            public String message() {
                return "Invalid request: " + reason;
            }
        }
    }

    Promise<PriceResponse> calculatePrice(PriceRequest request);

    static CatalogSlice catalogSlice(@Sql SqlConnector db,
                                     DiscountSlice discountSlice,
                                     TaxSlice taxSlice,
                                     @HighValueOrderPublisher Publisher<HighValueOrderEvent> highValuePublisher) {
        record catalogSlice(SqlConnector db,
                            DiscountSlice discountSlice,
                            TaxSlice taxSlice,
                            Publisher<HighValueOrderEvent> highValuePublisher) implements CatalogSlice {
            private static final String SELECT_PRICE = "SELECT price_cents FROM products WHERE product_id = ?";
            private static final Set<String> TAX_EXEMPT_REGIONS = Set.of("US-OR", "US-MT", "US-NH", "DE-FREE");

            @Override
            public Promise<PriceResponse> calculatePrice(PriceRequest request) {
                var quantity = request.quantity();
                return lookupBasePrice(request, quantity).flatMap(basePrice -> discountFor(request, basePrice))
                                      .flatMap(ctx -> taxFor(request, ctx))
                                      .flatMap(ctx -> publishIfHighValue(request, ctx));
            }

            private Promise<Integer> lookupBasePrice(PriceRequest request, int quantity) {
                return lookupUnitPrice(request.productId()).map(priceCents -> priceCents * quantity);
            }

            private Promise<Integer> lookupUnitPrice(String productId) {
                return db.queryOptional(SELECT_PRICE,
                                        row -> row.getInt("price_cents"),
                                        productId)
                         .flatMap(opt -> opt.async(new PricingError.ProductNotFound(productId)));
            }

            private Promise<PricingContext> discountFor(PriceRequest request, int basePrice) {
                if (hasCoupon(request)) {
                    return discountSlice.calculateDiscount(new DiscountRequest(request.couponCode(),
                                                                               basePrice))
                                        .map(resp -> pricingContext(basePrice,
                                                                    resp.discountAmountCents(),
                                                                    List.of("catalog", "discount")));
                }
                return Promise.success(pricingContext(basePrice, 0, List.of("catalog")));
            }

            private Promise<PricingContext> taxFor(PriceRequest request, PricingContext ctx) {
                var taxableAmount = ctx.basePrice() - ctx.discountAmount();
                if (isTaxExempt(request.regionCode())) {
                    return Promise.success(ctx.withTax(0));
                }
                return taxSlice.calculateTax(new TaxRequest(request.regionCode(),
                                                            taxableAmount))
                               .map(resp -> ctx.withTaxStep(resp.taxAmountCents()));
            }

            private Promise<PriceResponse> publishIfHighValue(PriceRequest request, PricingContext ctx) {
                var response = toResponse(ctx);
                if (response.totalPrice() > HIGH_VALUE_THRESHOLD_CENTS) {
                    return publishHighValueEvent(request, response);
                }
                return Promise.success(response);
            }

            private Promise<PriceResponse> publishHighValueEvent(PriceRequest request, PriceResponse response) {
                var event = new HighValueOrderEvent(request.productId(),
                                                    request.quantity(),
                                                    response.totalPrice(),
                                                    request.regionCode());
                return highValuePublisher.publish(event)
                                         .map(_ -> response);
            }

            private static PriceResponse toResponse(PricingContext ctx) {
                var total = ctx.basePrice() - ctx.discountAmount() + ctx.taxAmount();
                return new PriceResponse(ctx.basePrice(), ctx.discountAmount(), ctx.taxAmount(), total, ctx.callPath());
            }

            private static boolean hasCoupon(PriceRequest request) {
                return ! request.couponCode()
                               .isBlank();
            }

            private static boolean isTaxExempt(String regionCode) {
                return TAX_EXEMPT_REGIONS.contains(regionCode);
            }

            private static PricingContext pricingContext(int basePrice, int discountAmount, List<String> callPath) {
                return new PricingContext(basePrice, discountAmount, 0, callPath);
            }
        }
        return new catalogSlice(db, discountSlice, taxSlice, highValuePublisher);
    }

    /// Internal context carrying accumulated pricing data through the chain.
    /// Fully immutable — each transformation returns a new instance.
    record PricingContext(int basePrice, int discountAmount, int taxAmount, List<String> callPath) {
        public PricingContext {
            callPath = List.copyOf(callPath);
        }

        PricingContext withTax(int tax) {
            return new PricingContext(basePrice, discountAmount, tax, callPath);
        }

        PricingContext withStep(String step) {
            var updated = new ArrayList<>(callPath);
            updated.add(step);
            return new PricingContext(basePrice, discountAmount, taxAmount, updated);
        }

        PricingContext withTaxStep(int tax) {
            return withStep("tax").withTax(tax);
        }
    }
}
