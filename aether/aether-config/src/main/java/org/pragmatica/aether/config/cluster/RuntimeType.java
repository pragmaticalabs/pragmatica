package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


/// Supported runtime types for Aether node execution.
public enum RuntimeType {
    CONTAINER("container"),
    JVM("jvm");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid runtime type: must be 'container' or 'jvm'");
    private final String value;
    RuntimeType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<RuntimeType> runtimeType(String raw) {
        return Arrays.stream(values()).filter(rt -> rt.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
