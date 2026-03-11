package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Removes entries for departed nodes from DHT maps.
/// Called by the governor when SWIM detects a DEAD node.
///
/// Accepts pre-extracted endpoint keys to avoid circular dependency
/// with aether-invoke (which depends on aether-dht).
@SuppressWarnings("JBCT-UTIL-02") // Utility interface — static methods only
public sealed interface DhtNodeCleanup {
    Logger log = LoggerFactory.getLogger(DhtNodeCleanup.class);

    /// Remove all endpoints owned by the given dead node from the DHT endpoint map.
    ///
    /// @param deadNode the departed node
    /// @param endpointMap the endpoints ReplicatedMap
    /// @param endpointKeysForNode pre-extracted endpoint keys belonging to the dead node
    /// @return promise completing when cleanup is done
    static Promise<Unit> cleanupDeadNodeEndpoints(NodeId deadNode,
                                                  ReplicatedMap<EndpointKey, EndpointValue> endpointMap,
                                                  List<EndpointKey> endpointKeysForNode) {
        if (endpointKeysForNode.isEmpty()) {
            log.debug("No endpoints to clean up for dead node {}", deadNode);
            return Promise.unitPromise();
        }
        log.info("Cleaning up {} endpoints for dead node {}", endpointKeysForNode.size(), deadNode);
        return removeEndpointsSequentially(deadNode, endpointMap, endpointKeysForNode);
    }

    private static Promise<Unit> removeEndpointsSequentially(NodeId deadNode,
                                                             ReplicatedMap<EndpointKey, EndpointValue> endpointMap,
                                                             List<EndpointKey> keys) {
        var result = Promise.unitPromise();
        for (var key : keys) {
            result = result.flatMap(_ -> endpointMap.remove(key)
                                                    .mapToUnit());
        }
        return result.onSuccess(_ -> log.info("Completed cleanup of {} endpoints for dead node {}",
                                              keys.size(),
                                              deadNode));
    }

    record unused() implements DhtNodeCleanup {}
}
