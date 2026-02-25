package org.pragmatica.aether.example.pricing.tax;

import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

/// Tax calculation slice — reads tax_rates from DB.
///
/// Tax-exempt regions (no row in DB) return zero tax.
@Slice
public interface TaxSlice {
    record TaxRequest(String regionCode, int amountCents) {
        static TaxRequest taxRequest(String regionCode, int amountCents) {
            return new TaxRequest(regionCode, amountCents);
        }
    }

    record TaxResponse(int taxAmountCents, String regionCode, int taxRateBps) {
        static TaxResponse taxResponse(int taxAmountCents, String regionCode, int taxRateBps) {
            return new TaxResponse(taxAmountCents, regionCode, taxRateBps);
        }
    }

    Promise<TaxResponse> calculateTax(TaxRequest request);

    static TaxSlice taxSlice(@Sql SqlConnector db) {
        record taxSlice(SqlConnector db) implements TaxSlice {
            private static final String SELECT_TAX_RATE = "SELECT rate_bps FROM tax_rates WHERE region_code = ?";
            private static final RowMapper<Integer> RATE_BPS_MAPPER = row -> row.getInt("rate_bps");

            @Override
            public Promise<TaxResponse> calculateTax(TaxRequest request) {
                var regionCode = request.regionCode();
                return db.queryOptional(SELECT_TAX_RATE, RATE_BPS_MAPPER, regionCode)
                         .map(maybeRate -> resolveTax(request, maybeRate));
            }

            private static TaxResponse resolveTax(TaxRequest request, Option<Integer> maybeRate) {
                return maybeRate.map(bps -> taxFor(request, bps))
                                .or(zeroTax(request));
            }

            private static TaxResponse taxFor(TaxRequest request, int rateBps) {
                return new TaxResponse(request.amountCents() * rateBps / 10000, request.regionCode(), rateBps);
            }

            private static TaxResponse zeroTax(TaxRequest request) {
                return new TaxResponse(0, request.regionCode(), 0);
            }
        }
        return new taxSlice(db);
    }
}
