package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.CloudProvider;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.QuotaStatus;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


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
        return CloudProviderSupport.provisionVia(computeProvider, group);
    }

    @Override public Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group) {
        return EnvironmentError.operationNotSupported("Hetzner does not support preemptible instances").promise();
    }

    @Override public Promise<Unit> destroy(List<String> nodeIds) {
        return CloudProviderSupport.destroyVia(computeProvider, nodeIds);
    }

    @Override public Promise<List<NodeAddress>> addresses(List<String> nodeIds) {
        return CloudProviderSupport.addressesVia(computeProvider, nodeIds);
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
}
