package org.pragmatica.aether.environment;

/// Unique identifier for a compute instance.
public record InstanceId(String value) {
    public static InstanceId instanceId(String value) {
        return new InstanceId(value);
    }
}
