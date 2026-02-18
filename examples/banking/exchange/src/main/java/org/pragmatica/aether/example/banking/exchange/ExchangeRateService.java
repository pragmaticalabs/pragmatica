package org.pragmatica.aether.example.banking.exchange;

import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.ExchangeRate;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/// Exchange rate service for currency conversion.
///
/// Demonstrates:
///   - 0-param method: listRates
///   - 2-param methods: getRate, convert
///   - No factory dependencies
@Slice
public interface ExchangeRateService {

    // === Errors ===
    sealed interface ExchangeError extends Cause {
        record RateNotFound(Currency from, Currency to) implements ExchangeError {
            @Override
            public String message() {
                return "Exchange rate not found: " + from + " -> " + to;
            }
        }
    }

    // === Operations ===

    /// Get exchange rate between two currencies. 2-param method.
    Promise<ExchangeRate> getRate(Currency from, Currency to);

    /// Convert a money amount to a target currency. 2-param method.
    Promise<Money> convert(Money amount, Currency targetCurrency);

    /// List all available exchange rates. 0-param method.
    Promise<List<ExchangeRate>> listRates();

    // === Factory ===
    static ExchangeRateService exchangeRateService() {
        var rates = initializeRates();
        return new exchangeRateService(rates);
    }

    record exchangeRateService(Map<String, BigDecimal> rates) implements ExchangeRateService {
        @Override
        public Promise<ExchangeRate> getRate(Currency from, Currency to) {
            if (from.equals(to)) {
                return Promise.success(ExchangeRate.exchangeRate(from, to, BigDecimal.ONE));
            }
            var key = rateKey(from, to);
            var rate = rates.get(key);
            return rate != null
                   ? Promise.success(ExchangeRate.exchangeRate(from, to, rate))
                   : new ExchangeError.RateNotFound(from, to).promise();
        }

        @Override
        public Promise<Money> convert(Money amount, Currency targetCurrency) {
            if (amount.currency().equals(targetCurrency)) {
                return Promise.success(amount);
            }
            return getRate(amount.currency(), targetCurrency)
                .flatMap(exchangeRate -> amount.multiply(exchangeRate.rate()).async());
        }

        @Override
        public Promise<List<ExchangeRate>> listRates() {
            var allRates = rates.entrySet()
                                .stream()
                                .map(entry -> {
                                    var parts = entry.getKey().split("/");
                                    return ExchangeRate.exchangeRate(new Currency(parts[0]),
                                                                     new Currency(parts[1]),
                                                                     entry.getValue());
                                })
                                .toList();
            return Promise.success(allRates);
        }

        private static String rateKey(Currency from, Currency to) {
            return from.code() + "/" + to.code();
        }
    }

    private static Map<String, BigDecimal> initializeRates() {
        return Map.ofEntries(
            Map.entry("USD/EUR", new BigDecimal("0.92")),
            Map.entry("EUR/USD", new BigDecimal("1.09")),
            Map.entry("USD/GBP", new BigDecimal("0.79")),
            Map.entry("GBP/USD", new BigDecimal("1.27")),
            Map.entry("USD/JPY", new BigDecimal("149.50")),
            Map.entry("JPY/USD", new BigDecimal("0.0067")),
            Map.entry("EUR/GBP", new BigDecimal("0.86")),
            Map.entry("GBP/EUR", new BigDecimal("1.16")),
            Map.entry("EUR/JPY", new BigDecimal("162.50")),
            Map.entry("JPY/EUR", new BigDecimal("0.0062")),
            Map.entry("GBP/JPY", new BigDecimal("189.70")),
            Map.entry("JPY/GBP", new BigDecimal("0.0053"))
        );
    }
}
