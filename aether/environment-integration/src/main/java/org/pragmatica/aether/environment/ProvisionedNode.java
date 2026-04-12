package org.pragmatica.aether.environment;


/// A provisioned node with its ID and address. Section 11.1
public record ProvisionedNode(String nodeId, String serverId, String publicIp) {
    public static ProvisionedNode provisionedNode(String nodeId, String serverId, String publicIp) {
        return new ProvisionedNode(nodeId, serverId, publicIp);
    }
}
