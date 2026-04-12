package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


/// Node address information. Section 11.1
public record NodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
    public static NodeAddress nodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
        return new NodeAddress(nodeId, publicIp, privateIp);
    }
}
