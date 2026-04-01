package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Information about a compute instance.
public record InstanceInfo( InstanceId id,
                            InstanceStatus status,
                            List<String> addresses,
                            InstanceType type,
                            Map<String, String> tags) {
    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type,
                                                    Map<String, String> tags) {
        return success(new InstanceInfo(id, status, List.copyOf(addresses), type, Map.copyOf(tags)));
    }

    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type) {
        return instanceInfo(id, status, addresses, type, Map.of());
    }
}
