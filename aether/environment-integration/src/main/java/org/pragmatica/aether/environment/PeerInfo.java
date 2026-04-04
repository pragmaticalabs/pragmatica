package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;


/// Information about a discovered peer node.
public record PeerInfo(String host, int port, Map<String, String> metadata) {
    public static Result<PeerInfo> peerInfo(String host, int port, Map<String, String> metadata) {
        return success(new PeerInfo(host, port, Map.copyOf(metadata)));
    }

    public static Result<PeerInfo> peerInfo(String host, int port) {
        return peerInfo(host, port, Map.of());
    }
}
