package org.pragmatica.aether.environment;

import java.util.List;

/// Information about a compute instance.
public record InstanceInfo(InstanceId id,
                           InstanceStatus status,
                           List<String> addresses,
                           InstanceType type) {
    public static InstanceInfo instanceInfo(InstanceId id,
                                            InstanceStatus status,
                                            List<String> addresses,
                                            InstanceType type) {
        return new InstanceInfo(id, status, addresses, type);
    }
}
