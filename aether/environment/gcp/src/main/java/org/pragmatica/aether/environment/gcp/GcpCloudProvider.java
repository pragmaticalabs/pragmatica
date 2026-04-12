package org.pragmatica.aether.environment.gcp;

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


/// GCP Cloud implementation of the CloudProvider SPI.
/// Wraps the existing ComputeProvider to provide bootstrap/apply-level operations.
/// GCP supports preemptible instances, but spot provisioning is deferred to v2.
public record GcpCloudProvider(ComputeProvider computeProvider) implements CloudProvider {
    public static GcpCloudProvider gcpCloudProvider(ComputeProvider computeProvider) {
        return new GcpCloudProvider(computeProvider);
    }

    @Override public Promise<QuotaStatus> checkQuota(NodeGroupConfig group) {
        return Promise.success(QuotaStatus.unknown(group.count()));
    }

    @Override public Promise<List<ProvisionedNode>> provision(NodeGroupConfig group) {
        return CloudProviderSupport.provisionVia(computeProvider, group);
    }

    @Override public Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group) {
        return EnvironmentError.operationNotSupported("spot provisioning not implemented in v1 for GCP").promise();
    }

    @Override public Promise<Unit> destroy(List<String> nodeIds) {
        return CloudProviderSupport.destroyVia(computeProvider, nodeIds);
    }

    @Override public Promise<List<NodeAddress>> addresses(List<String> nodeIds) {
        return CloudProviderSupport.addressesVia(computeProvider, nodeIds);
    }

    @Override public boolean supportsPreemptible() {
        return true;
    }

    @Override public Promise<Unit> openIngress(String sourceId,
                                               int port,
                                               String protocol,
                                               String sourceCidr,
                                               String description) {
        return EnvironmentError.operationNotSupported("openIngress (GCP firewall API not yet wired)").promise();
    }

    @Override public Promise<Unit> closeIngress(String sourceId, int port, String protocol, String sourceCidr) {
        return EnvironmentError.operationNotSupported("closeIngress (GCP firewall API not yet wired)").promise();
    }
}
