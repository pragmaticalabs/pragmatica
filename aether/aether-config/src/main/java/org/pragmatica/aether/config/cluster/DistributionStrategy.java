package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;

/// Distribution strategies for node placement across zones.
public enum DistributionStrategy {
    BALANCED("balanced"),
    MANUAL("manual");
    private static final Cause INVALID_STRATEGY = Causes.cause("Invalid distribution strategy: must be 'balanced' or 'manual'");
    private final String value;
    DistributionStrategy(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    /// Parse a distribution strategy from its string representation.
    public static Result<DistributionStrategy> distributionStrategy(String raw) {
        return Arrays.stream(values()).filter(ds -> ds.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_STRATEGY::result);
    }
}
