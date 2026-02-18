package org.pragmatica.aether.example.banking.shared;

/// Result of fraud risk assessment for a transfer.
public record RiskAssessment(RiskLevel level,
                             int score,
                             String reason) {

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        BLOCKED
    }

    public static RiskAssessment riskAssessment(RiskLevel level, int score, String reason) {
        return new RiskAssessment(level, score, reason);
    }

    public boolean isAcceptable() {
        return level != RiskLevel.BLOCKED;
    }
}
