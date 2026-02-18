package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Unique identifier for a compute instance.
public record InstanceId(String value) {
    public static Result<InstanceId> instanceId(String value) {
        return success(new InstanceId(value));
    }
}
