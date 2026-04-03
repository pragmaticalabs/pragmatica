package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


/// Supported upgrade strategies for cluster version changes.
public enum UpgradeStrategy {
    ROLLING("rolling"),
    BLUE_GREEN("blue-green");
    private static final Cause INVALID_STRATEGY = Causes.cause("Invalid upgrade strategy: must be 'rolling' or 'blue-green'");
    private final String value;
    UpgradeStrategy(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<UpgradeStrategy> upgradeStrategy(String raw) {
        return Arrays.stream(values()).filter(us -> us.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_STRATEGY::result);
    }
}
