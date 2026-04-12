package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.CloudProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.QuotaStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.option;


/// Hetzner Cloud implementation of the CloudProvider SPI.
/// Wraps the existing ComputeProvider to provide bootstrap/apply-level operations.
/// Hetzner does not expose a quota API, so checkQuota returns unknown.
/// Hetzner does not support preemptible/spot instances.
public record HetznerCloudProvider(ComputeProvider computeProvider) implements CloudProvider {
    public static HetznerCloudProvider hetznerCloudProvider(ComputeProvider computeProvider) {
        return new HetznerCloudProvider(computeProvider);
    }

    @Override public Promise<QuotaStatus> checkQuota(NodeGroupConfig group) {
        return Promise.success(QuotaStatus.unknown(group.count()));
    }

    @Override public Promise<List<ProvisionedNode>> provision(NodeGroupConfig group) {
        var provisions = IntStream.range(0,
                                         group.count()).mapToObj(i -> provisionSingle(group, i))
                                        .toList();
        return Promise.allOf(provisions).flatMap(results -> Result.allOf(results).async());
    }

    @Override public Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group) {
        return EnvironmentError.operationNotSupported("Hetzner does not support preemptible instances").promise();
    }

    @Override public Promise<Unit> destroy(List<String> nodeIds) {
        var terminations = nodeIds.stream().map(HetznerCloudProvider::toInstanceId)
                                         .map(computeProvider::terminate)
                                         .toList();
        return Promise.allOf(terminations).flatMap(results -> Result.allOf(results).async())
                            .mapToUnit();
    }

    @Override public Promise<List<NodeAddress>> addresses(List<String> nodeIds) {
        var lookups = nodeIds.stream().map(id -> lookupAddress(toInstanceId(id)))
                                    .toList();
        return Promise.allOf(lookups).flatMap(results -> Result.allOf(results).async());
    }

    @Override public boolean supportsPreemptible() {
        return false;
    }

    @Override public Promise<Unit> openIngress(String sourceId,
                                               int port,
                                               String protocol,
                                               String sourceCidr,
                                               String description) {
        return EnvironmentError.operationNotSupported("openIngress (Hetzner firewall API not yet wired)").promise();
    }

    @Override public Promise<Unit> closeIngress(String sourceId, int port, String protocol, String sourceCidr) {
        return EnvironmentError.operationNotSupported("closeIngress (Hetzner firewall API not yet wired)").promise();
    }

    private Promise<ProvisionedNode> provisionSingle(NodeGroupConfig group, int index) {
        var nodeId = group.sourceName() + "-" + group.role() + "-" + index;
        return computeProvider.provision(InstanceType.ON_DEMAND).map(info -> toProvisionedNode(nodeId, info));
    }

    private Promise<NodeAddress> lookupAddress(InstanceId instanceId) {
        return computeProvider.instanceStatus(instanceId).map(info -> toNodeAddress(instanceId.value(), info));
    }

    private static ProvisionedNode toProvisionedNode(String nodeId, InstanceInfo info) {
        return ProvisionedNode.provisionedNode(nodeId,
                                               info.id().value(),
                                               firstAddress(info));
    }

    private static NodeAddress toNodeAddress(String nodeId, InstanceInfo info) {
        return NodeAddress.nodeAddress(nodeId, firstAddress(info), secondAddress(info));
    }

    private static String firstAddress(InstanceInfo info) {
        return info.addresses().isEmpty()
              ? ""
              : info.addresses().getFirst();
    }

    private static Option<String> secondAddress(InstanceInfo info) {
        return info.addresses().size() > 1
              ? option(info.addresses().get(1))
              : Option.empty();
    }

    private static InstanceId toInstanceId(String nodeId) {
        return new InstanceId(nodeId);
    }
}
