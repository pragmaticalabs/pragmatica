package org.pragmatica.aether.example.pricing;

import org.pragmatica.aether.example.shared.Address;
import org.pragmatica.aether.example.shared.CustomerId;
import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.aether.example.shared.ProductId;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Pricing slice.
 * Calculates prices, applies discounts, and computes taxes.
 */
@Slice
public interface PricingService {
    // === Requests ===
    record CalculatePriceRequest(CustomerId customerId, List<LineItem> items) {
        public static CalculatePriceRequest calculatePriceRequest(CustomerId customerId, List<LineItem> items) {
            return new CalculatePriceRequest(customerId, List.copyOf(items));
        }
    }

    record ApplyDiscountRequest(CustomerId customerId, Money subtotal, Option<String> discountCode) {
        public static ApplyDiscountRequest applyDiscountRequest(CustomerId customerId, Money subtotal, String code) {
            return new ApplyDiscountRequest(customerId,
                                            subtotal,
                                            Option.option(code)
                                                  .filter(s -> !s.isBlank()));
        }

        public static ApplyDiscountRequest withoutCode(CustomerId customerId, Money subtotal) {
            return new ApplyDiscountRequest(customerId, subtotal, Option.empty());
        }
    }

    record CalculateTaxRequest(Money subtotal, Address shippingAddress) {
        public static CalculateTaxRequest calculateTaxRequest(Money subtotal, Address address) {
            return new CalculateTaxRequest(subtotal, address);
        }
    }

    // === Responses ===
    record PriceBreakdown(Map<ProductId, LinePrice> linePrices,
                          Money subtotal,
                          Money discountAmount,
                          Money taxAmount,
                          Money shippingCost,
                          Money total) {
        public record LinePrice(ProductId productId, Money unitPrice, int quantity, Money lineTotal) {
            public static LinePrice linePrice(ProductId productId, Money unitPrice, int quantity) {
                return new LinePrice(productId,
                                     unitPrice,
                                     quantity,
                                     unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public List<String> summary() {
            return List.of("Subtotal: " + subtotal,
                           "Discount: -" + discountAmount,
                           "Tax: " + taxAmount,
                           "Shipping: " + shippingCost,
                           "Total: " + total);
        }

        public static class Builder {
            private Map<ProductId, LinePrice> linePrices = Map.of();
            private Money subtotal = Money.ZERO_USD;
            private Money discountAmount = Money.ZERO_USD;
            private Money taxAmount = Money.ZERO_USD;
            private Money shippingCost = Money.ZERO_USD;

            public Builder linePrices(Map<ProductId, LinePrice> linePrices) {
                this.linePrices = Map.copyOf(linePrices);
                return this;
            }

            public Builder subtotal(Money subtotal) {
                this.subtotal = subtotal;
                return this;
            }

            public Builder discountAmount(Money discountAmount) {
                this.discountAmount = discountAmount;
                return this;
            }

            public Builder taxAmount(Money taxAmount) {
                this.taxAmount = taxAmount;
                return this;
            }

            public Builder shippingCost(Money shippingCost) {
                this.shippingCost = shippingCost;
                return this;
            }

            public Result<PriceBreakdown> build() {
                return subtotal.subtract(discountAmount)
                               .flatMap(afterDiscount -> afterDiscount.add(taxAmount))
                               .flatMap(afterTax -> afterTax.add(shippingCost))
                               .map(total -> new PriceBreakdown(linePrices,
                                                                subtotal,
                                                                discountAmount,
                                                                taxAmount,
                                                                shippingCost,
                                                                total));
            }
        }
    }

    record DiscountResult(Money discountAmount, Option<String> appliedCode, String description) {
        public static DiscountResult noDiscount() {
            return new DiscountResult(Money.ZERO_USD, Option.empty(), "No discount applied");
        }

        public static DiscountResult percentOff(int percent, Money amount, String code) {
            return new DiscountResult(amount, Option.some(code), percent + "% off with code " + code);
        }

        public static DiscountResult loyaltyDiscount(Money amount) {
            return new DiscountResult(amount, Option.empty(), "Loyalty discount");
        }

        public static DiscountResult bulkDiscount(Money amount) {
            return new DiscountResult(amount, Option.empty(), "Bulk order discount");
        }

        public boolean hasDiscount() {
            return ! discountAmount.isZero();
        }
    }

    record TaxResult(Money taxAmount, BigDecimal taxRate, String jurisdiction) {
        public static TaxResult taxResult(Money amount, BigDecimal rate, String jurisdiction) {
            return new TaxResult(amount, rate, jurisdiction);
        }

        public String description() {
            return String.format("%s tax (%.1f%%): %s",
                                 jurisdiction,
                                 taxRate.multiply(BigDecimal.valueOf(100)),
                                 taxAmount);
        }
    }

    // === Errors ===
    sealed interface PricingError extends Cause {
        record InvalidDiscountCode(String code) implements PricingError {
            @Override
            public String message() {
                return "Invalid discount code: " + code;
            }
        }

        record MinimumPurchaseNotMet(BigDecimal minimum) implements PricingError {
            @Override
            public String message() {
                return "Minimum purchase of $" + minimum.toPlainString() + " required for this discount";
            }
        }

        record ProductNotFound(String productId) implements PricingError {
            @Override
            public String message() {
                return "Price not found for product: " + productId;
            }
        }
    }

    // === Operations ===
    Promise<PriceBreakdown> calculatePrice(CalculatePriceRequest request);

    Promise<DiscountResult> applyDiscount(ApplyDiscountRequest request);

    Promise<TaxResult> calculateTax(CalculateTaxRequest request);

    // === Internal Types ===
    record DiscountCode(String code, int percentOff, BigDecimal minimumPurchase) {}

    // === Factory ===
    static PricingService pricingService() {
        record pricingService(Map<ProductId, Money> productPrices,
                              Map<String, DiscountCode> discountCodes,
                              Map<String, BigDecimal> taxRates,
                              Set<String> loyalCustomers) implements PricingService {
            @Override
            public Promise<PriceBreakdown> calculatePrice(CalculatePriceRequest request) {
                var linePrices = calculateLinePrices(request.items());
                return calculateSubtotal(linePrices)
                                        .map(subtotal -> PriceBreakdown.builder()
                                                                       .linePrices(linePrices)
                                                                       .subtotal(subtotal))
                                        .flatMap(PriceBreakdown.Builder::build)
                                        .async();
            }

            @Override
            public Promise<DiscountResult> applyDiscount(ApplyDiscountRequest request) {
                return request.discountCode()
                              .map(code -> applyCodeDiscount(code,
                                                             request.subtotal()))
                              .or(() -> applyAutomaticDiscount(request));
            }

            @Override
            public Promise<TaxResult> calculateTax(CalculateTaxRequest request) {
                var state = request.shippingAddress()
                                   .state()
                                   .toUpperCase();
                var rate = taxRates.getOrDefault(state, BigDecimal.valueOf(0.08));
                var taxAmount = request.subtotal()
                                       .percentage((int)(rate.doubleValue() * 100));
                return Promise.success(TaxResult.taxResult(taxAmount, rate, state));
            }

            private Map<ProductId, PriceBreakdown.LinePrice> calculateLinePrices(List<LineItem> items) {
                return items.stream()
                            .collect(Collectors.toMap(LineItem::productId, this::calculateLinePrice));
            }

            private PriceBreakdown.LinePrice calculateLinePrice(LineItem item) {
                var unitPrice = productPrices.getOrDefault(item.productId(), Money.ZERO_USD);
                return PriceBreakdown.LinePrice.linePrice(item.productId(),
                                                          unitPrice,
                                                          item.quantity()
                                                              .value());
            }

            private Result<Money> calculateSubtotal(Map<ProductId, PriceBreakdown.LinePrice> linePrices) {
                var totals = linePrices.values()
                                       .stream()
                                       .map(PriceBreakdown.LinePrice::lineTotal)
                                       .toList();
                return sumMoney(totals);
            }

            private static Result<Money> sumMoney(List<Money> amounts) {
                return amounts.stream()
                              .reduce(Result.success(Money.ZERO_USD),
                                      pricingService::addToAccumulator,
                                      pricingService::combineMoneyResults);
            }

            private static Result<Money> addToAccumulator(Result<Money> acc, Money money) {
                return acc.flatMap(m -> m.add(money));
            }

            private static Result<Money> combineMoneyResults(Result<Money> a, Result<Money> b) {
                return Result.all(a, b)
                             .flatMap(Money::add);
            }

            private Promise<DiscountResult> applyCodeDiscount(String code, Money subtotal) {
                return Option.option(discountCodes.get(code.toUpperCase()))
                             .toResult(new PricingError.InvalidDiscountCode(code))
                             .flatMap(discount -> validateMinimumPurchase(discount, subtotal))
                             .map(discount -> DiscountResult.percentOff(discount.percentOff(),
                                                                        subtotal.percentage(discount.percentOff()),
                                                                        code))
                             .async();
            }

            private Result<DiscountCode> validateMinimumPurchase(DiscountCode discount, Money subtotal) {
                return subtotal.amount()
                               .compareTo(discount.minimumPurchase()) >= 0
                       ? Result.success(discount)
                       : new PricingError.MinimumPurchaseNotMet(discount.minimumPurchase()).result();
            }

            private Promise<DiscountResult> applyAutomaticDiscount(ApplyDiscountRequest request) {
                // Bulk discount: 10% off for orders over $500
                if (request.subtotal()
                           .amount()
                           .compareTo(BigDecimal.valueOf(500)) >= 0) {
                    return Promise.success(DiscountResult.bulkDiscount(request.subtotal()
                                                                              .percentage(10)));
                }
                // Loyalty discount: 5% off for loyal customers
                if (loyalCustomers.contains(request.customerId()
                                                   .value())) {
                    return Promise.success(DiscountResult.loyaltyDiscount(request.subtotal()
                                                                                 .percentage(5)));
                }
                return Promise.success(DiscountResult.noDiscount());
            }
        }
        var productPrices = new ConcurrentHashMap<ProductId, Money>();
        var discountCodes = new ConcurrentHashMap<String, DiscountCode>();
        var taxRates = new ConcurrentHashMap<String, BigDecimal>();
        Set<String> loyalCustomers = ConcurrentHashMap.newKeySet();
        initializePrices(productPrices);
        initializeDiscountCodes(discountCodes);
        initializeTaxRates(taxRates);
        return new pricingService(productPrices, discountCodes, taxRates, loyalCustomers);
    }

    private static void initializePrices(Map<ProductId, Money> productPrices) {
        addPrice(productPrices, "LAPTOP-PRO", "999.99");
        addPrice(productPrices, "MOUSE-WIRELESS", "49.99");
        addPrice(productPrices, "KEYBOARD-MECH", "149.99");
        addPrice(productPrices, "MONITOR-4K", "599.99");
        addPrice(productPrices, "HEADSET-BT", "79.99");
        addPrice(productPrices, "WEBCAM-HD", "89.99");
        addPrice(productPrices, "USB-HUB", "29.99");
        addPrice(productPrices, "CHARGER-65W", "39.99");
    }

    private static void addPrice(Map<ProductId, Money> productPrices, String productId, String price) {
        ProductId.productId(productId)
                 .flatMap(id -> Money.usd(price)
                                     .map(p -> Map.entry(id, p)))
                 .onSuccess(entry -> productPrices.put(entry.getKey(),
                                                       entry.getValue()));
    }

    private static void initializeDiscountCodes(Map<String, DiscountCode> discountCodes) {
        discountCodes.put("SAVE10", new DiscountCode("SAVE10", 10, BigDecimal.valueOf(50)));
        discountCodes.put("SAVE20", new DiscountCode("SAVE20", 20, BigDecimal.valueOf(100)));
        discountCodes.put("BLACKFRIDAY", new DiscountCode("BLACKFRIDAY", 30, BigDecimal.valueOf(200)));
        discountCodes.put("WELCOME", new DiscountCode("WELCOME", 15, BigDecimal.ZERO));
    }

    private static void initializeTaxRates(Map<String, BigDecimal> taxRates) {
        taxRates.put("CA", BigDecimal.valueOf(0.0725));
        taxRates.put("NY", BigDecimal.valueOf(0.08));
        taxRates.put("TX", BigDecimal.valueOf(0.0625));
        taxRates.put("FL", BigDecimal.valueOf(0.06));
        taxRates.put("WA", BigDecimal.valueOf(0.065));
        taxRates.put("OR", BigDecimal.ZERO);
        taxRates.put("DE", BigDecimal.ZERO);
    }
}
