package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.dht.ReplicatedMap;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Cleans up DHT entries for departed worker nodes.
/// Called by the governor when SWIM detects FAULTY or LEFT members.
///
/// Maintains an index of node-to-key mappings for efficient cleanup.
@SuppressWarnings({"JBCT-RET-01", "JBCT-STY-05"})
public final class GovernorCleanup {
    private static final Logger log = LoggerFactory.getLogger(GovernorCleanup.class);

    private final AetherMaps aetherMaps;
    private final Map<NodeId, Set<EndpointKey>> endpointIndex;
    private final Map<NodeId, Set<SliceNodeKey>> sliceNodeIndex;
    private final Map<NodeId, Set<HttpNodeRouteKey>> httpRouteIndex;

    private GovernorCleanup(AetherMaps aetherMaps) {
        this.aetherMaps = aetherMaps;
        this.endpointIndex = new ConcurrentHashMap<>();
        this.sliceNodeIndex = new ConcurrentHashMap<>();
        this.httpRouteIndex = new ConcurrentHashMap<>();
    }

    public static GovernorCleanup governorCleanup(AetherMaps aetherMaps) {
        return new GovernorCleanup(aetherMaps);
    }

    /// Track an endpoint published by a node.
    public void trackEndpoint(NodeId nodeId, EndpointKey key) {
        endpointIndex.computeIfAbsent(nodeId,
                                      _ -> ConcurrentHashMap.newKeySet())
                     .add(key);
    }

    /// Track a slice-node entry for a node.
    public void trackSliceNode(NodeId nodeId, SliceNodeKey key) {
        sliceNodeIndex.computeIfAbsent(nodeId,
                                       _ -> ConcurrentHashMap.newKeySet())
                      .add(key);
    }

    /// Track an HTTP route entry for a node.
    public void trackHttpRoute(NodeId nodeId, HttpNodeRouteKey key) {
        httpRouteIndex.computeIfAbsent(nodeId,
                                       _ -> ConcurrentHashMap.newKeySet())
                      .add(key);
    }

    /// Untrack an endpoint (e.g., on explicit removal).
    public void untrackEndpoint(NodeId nodeId, EndpointKey key) {
        var keys = endpointIndex.get(nodeId);
        if (keys != null) {
            keys.remove(key);
        }
    }

    /// Untrack a slice-node entry.
    public void untrackSliceNode(NodeId nodeId, SliceNodeKey key) {
        var keys = sliceNodeIndex.get(nodeId);
        if (keys != null) {
            keys.remove(key);
        }
    }

    /// Untrack an HTTP route entry.
    public void untrackHttpRoute(NodeId nodeId, HttpNodeRouteKey key) {
        var keys = httpRouteIndex.get(nodeId);
        if (keys != null) {
            keys.remove(key);
        }
    }

    /// Clean up all DHT entries for a dead node.
    /// Removes endpoints, slice-node entries, and HTTP routes.
    public Promise<Unit> cleanupDeadNode(NodeId deadNode) {
        var endpointKeys = List.copyOf(endpointIndex.getOrDefault(deadNode, Set.of()));
        var sliceNodeKeys = List.copyOf(sliceNodeIndex.getOrDefault(deadNode, Set.of()));
        var httpRouteKeys = List.copyOf(httpRouteIndex.getOrDefault(deadNode, Set.of()));
        if (endpointKeys.isEmpty() && sliceNodeKeys.isEmpty() && httpRouteKeys.isEmpty()) {
            log.debug("No DHT entries to clean up for dead node {}", deadNode);
            return Promise.unitPromise();
        }
        log.info("Cleaning up {} endpoints, {} slice-nodes, {} http-routes for dead node {}",
                 endpointKeys.size(),
                 sliceNodeKeys.size(),
                 httpRouteKeys.size(),
                 deadNode);
        return removeSequentially(aetherMaps.endpoints(),
                                  endpointKeys).flatMap(_ -> removeSequentially(aetherMaps.sliceNodes(),
                                                                                sliceNodeKeys))
                                 .flatMap(_ -> removeSequentially(aetherMaps.httpRoutes(),
                                                                  httpRouteKeys))
                                 .onSuccess(_ -> clearIndices(deadNode));
    }

    private void clearIndices(NodeId deadNode) {
        endpointIndex.remove(deadNode);
        sliceNodeIndex.remove(deadNode);
        httpRouteIndex.remove(deadNode);
        log.info("Completed DHT cleanup for dead node {}", deadNode);
    }

    private static <K> Promise<Unit> removeSequentially(ReplicatedMap<K, ?> map, List<K> keys) {
        var result = Promise.unitPromise();
        for (var key : keys) {
            result = result.flatMap(_ -> map.remove(key)
                                            .mapToUnit());
        }
        return result;
    }
}
