package org.pragmatica.aether.environment;

public record ProvisionedNode(String nodeId, String serverId, String publicIp) {
    public static ProvisionedNode provisionedNode(String nodeId, String serverId, String publicIp) {
        return new ProvisionedNode(nodeId, serverId, publicIp);
    }
}
