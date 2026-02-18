package org.pragmatica.aether.example.banking.fraud;

import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.FraudStats;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.example.banking.shared.RiskAssessment;
import org.pragmatica.aether.example.banking.shared.RiskAssessment.RiskLevel;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/// Fraud detection service for transfer risk assessment.
///
/// Demonstrates:
///   - 0-param method: getStats
///   - 3-param method: assessTransfer
///   - No factory dependencies
@Slice
public interface FraudDetectionService {

    // === Operations ===

    /// Assess the risk of a transfer. 3-param method.
    Promise<RiskAssessment> assessTransfer(AccountId from, AccountId to, Money amount);

    /// Get aggregate fraud statistics. 0-param method.
    Promise<FraudStats> getStats();

    // === Factory ===
    static FraudDetectionService fraudDetectionService() {
        return new fraudDetectionService();
    }

    record fraudDetectionService() implements FraudDetectionService {
        private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000.00");
        private static final BigDecimal BLOCKED_THRESHOLD = new BigDecimal("100000.00");

        private static final AtomicLong TOTAL_ASSESSED = new AtomicLong();
        private static final AtomicLong BLOCKED_COUNT = new AtomicLong();
        private static final AtomicLong HIGH_RISK_COUNT = new AtomicLong();

        @Override
        public Promise<RiskAssessment> assessTransfer(AccountId from, AccountId to, Money amount) {
            TOTAL_ASSESSED.incrementAndGet();

            if (from.equals(to)) {
                BLOCKED_COUNT.incrementAndGet();
                return Promise.success(RiskAssessment.riskAssessment(RiskLevel.BLOCKED, 100,
                                                                      "Self-transfer not allowed"));
            }

            var value = amount.amount();

            if (value.compareTo(BLOCKED_THRESHOLD) >= 0) {
                BLOCKED_COUNT.incrementAndGet();
                return Promise.success(RiskAssessment.riskAssessment(RiskLevel.BLOCKED, 95,
                                                                      "Amount exceeds maximum threshold"));
            }

            if (value.compareTo(HIGH_VALUE_THRESHOLD) >= 0) {
                HIGH_RISK_COUNT.incrementAndGet();
                return Promise.success(RiskAssessment.riskAssessment(RiskLevel.HIGH, 70,
                                                                      "High-value transfer requires review"));
            }

            var score = value.intValue() / 200;
            var level = score > 40 ? RiskLevel.MEDIUM : RiskLevel.LOW;
            return Promise.success(RiskAssessment.riskAssessment(level, score, "Standard transfer"));
        }

        @Override
        public Promise<FraudStats> getStats() {
            return Promise.success(FraudStats.fraudStats(TOTAL_ASSESSED.get(),
                                                          BLOCKED_COUNT.get(),
                                                          HIGH_RISK_COUNT.get()));
        }
    }
}
