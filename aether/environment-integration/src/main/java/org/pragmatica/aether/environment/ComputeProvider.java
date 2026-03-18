package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/// SPI for provisioning and managing compute instances.
/// Implementations handle the actual instance lifecycle (Forge local nodes, cloud VMs, etc.).
/// CDM uses this interface to auto-heal when cluster size drops below target.
public interface ComputeProvider {
    Promise<InstanceInfo> provision(InstanceType instanceType);

    Promise<Unit> terminate(InstanceId instanceId);

    Promise<List<InstanceInfo>> listInstances();

    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);

    /// Restart an instance. Default implementation returns not-supported error.
    default Promise<Unit> restart(InstanceId id) {
        return EnvironmentError.operationNotSupported("restart")
                               .promise();
    }

    /// Apply tags to an instance. Default implementation returns not-supported error.
    default Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags")
                               .promise();
    }

    /// List instances filtered by tags. Default implementation delegates to listInstances()
    /// and filters client-side by matching all entries in the tag filter.
    default Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return listInstances().map(instances -> filterByTags(instances, tagFilter));
    }

    private static List<InstanceInfo> filterByTags(List<InstanceInfo> instances, Map<String, String> tagFilter) {
        return instances.stream()
                        .filter(instance -> matchesTags(instance, tagFilter))
                        .toList();
    }

    private static boolean matchesTags(InstanceInfo instance, Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .allMatch(entry -> entry.getValue()
                                                .equals(instance.tags()
                                                                .get(entry.getKey())));
    }
}
