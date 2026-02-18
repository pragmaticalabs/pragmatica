package org.pragmatica.aether.example.banking.shared;

/// Aggregate fraud detection statistics.
public record FraudStats(long totalAssessed,
                         long blockedCount,
                         long highRiskCount) {

    public static FraudStats fraudStats(long totalAssessed, long blockedCount, long highRiskCount) {
        return new FraudStats(totalAssessed, blockedCount, highRiskCount);
    }

    public static FraudStats empty() {
        return new FraudStats(0, 0, 0);
    }
}
