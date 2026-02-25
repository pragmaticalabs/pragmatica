package org.pragmatica.aether.example.pricing.discount;

import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

/// Discount calculation slice — reads discount_codes from DB.
///
/// Unknown coupon codes return zero discount (keeps load generation simple).
@Slice
public interface DiscountSlice {
    record DiscountRequest(String couponCode, int subtotalCents) {
        static DiscountRequest discountRequest(String couponCode, int subtotalCents) {
            return new DiscountRequest(couponCode, subtotalCents);
        }
    }

    record DiscountResponse(int discountAmountCents, String appliedCode) {
        static DiscountResponse discountResponse(int discountAmountCents, String appliedCode) {
            return new DiscountResponse(discountAmountCents, appliedCode);
        }
    }

    Promise<DiscountResponse> calculateDiscount(DiscountRequest request);

    static DiscountSlice discountSlice(@Sql SqlConnector db) {
        record discountSlice(SqlConnector db) implements DiscountSlice {
            private static final String SELECT_DISCOUNT = "SELECT percent_off FROM discount_codes WHERE code = ?";

            @Override
            public Promise<DiscountResponse> calculateDiscount(DiscountRequest request) {
                var couponCode = request.couponCode();
                return db.queryOptional(SELECT_DISCOUNT, PERCENT_OFF_MAPPER, couponCode)
                         .map(maybePercent -> resolveDiscount(request, maybePercent));
            }

            private static final RowMapper<Integer> PERCENT_OFF_MAPPER = row -> row.getInt("percent_off");

            private static DiscountResponse resolveDiscount(DiscountRequest request, Option<Integer> maybePercent) {
                return maybePercent.map(pct -> discountFor(request, pct))
                                   .or(noDiscount());
            }

            private static DiscountResponse discountFor(DiscountRequest request, int percentOff) {
                return new DiscountResponse(request.subtotalCents() * percentOff / 100, request.couponCode());
            }

            private static DiscountResponse noDiscount() {
                return new DiscountResponse(0, "");
            }
        }
        return new discountSlice(db);
    }
}
