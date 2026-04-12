package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


/// Load balancer modes. §6.3
public enum LoadBalancerMode {
    NONE("none"),
    EXTERNAL("external"),
    ELECTED("elected");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid load balancer mode: must be 'none', 'external', or 'elected'");
    private final String value;
    LoadBalancerMode(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<LoadBalancerMode> loadBalancerMode(String raw) {
        return Arrays.stream(values()).filter(lm -> lm.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
