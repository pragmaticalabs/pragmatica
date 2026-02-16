package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Information about a compute instance.
public record InstanceInfo(InstanceId id,
                           InstanceStatus status,
                           List<String> addresses,
                           InstanceType type) {
    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type) {
        return success(new InstanceInfo(id, status, addresses, type));
    }
}
