package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Cloud provider SPI for cluster bootstrap and apply operations. Section 11.1
public interface CloudProvider {
    Promise<QuotaStatus> checkQuota(NodeGroupConfig group);
    Promise<List<ProvisionedNode>> provision(NodeGroupConfig group);
    Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group);
    Promise<Unit> destroy(List<String> nodeIds);
    Promise<List<NodeAddress>> addresses(List<String> nodeIds);
    boolean supportsPreemptible();
    Promise<Unit> openIngress(String sourceId, int port, String protocol, String sourceCidr, String description);
    Promise<Unit> closeIngress(String sourceId, int port, String protocol, String sourceCidr);
}
