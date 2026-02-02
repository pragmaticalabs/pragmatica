package org.pragmatica.aether.metrics.topology;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;

/**
 * Collects cluster topology information from various sources.
 * <p>
 * Aggregates data from:
 * <ul>
 *   <li>TopologyManager for node state</li>
 *   <li>LeaderManager for leader info</li>
 *   <li>KVStore for slice distribution</li>
 * </ul>
 */
public final class TopologyCollector {
    private static final Logger log = LoggerFactory.getLogger(TopologyCollector.class);

    private final AtomicReference<TopologyManager> topologyManager = new AtomicReference<>();
    private final AtomicReference<LeaderManager> leaderManager = new AtomicReference<>();
    private final AtomicReference<KVStore<AetherKey, AetherValue>> kvStore = new AtomicReference<>();

    // Known nodes tracked via topology change notifications
    private final ConcurrentHashMap<String, NodeInfo> knownNodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nodeSuspectTimes = new ConcurrentHashMap<>();

    private TopologyCollector() {}

    public static TopologyCollector topologyCollector() {
        return new TopologyCollector();
    }

    /**
     * Set the topology manager reference.
     */
    public void setTopologyManager(TopologyManager manager) {
        topologyManager.set(manager);
        // Register self
        option(manager).onPresent(m -> {
            var self = m.self();
            knownNodes.put(self.id()
                               .id(),
                           self);
        });
    }

    /**
     * Set the leader manager reference.
     */
    public void setLeaderManager(LeaderManager manager) {
        leaderManager.set(manager);
    }

    /**
     * Set the KV store reference.
     */
    public void setKVStore(KVStore<AetherKey, AetherValue> store) {
        kvStore.set(store);
    }

    /**
     * Register a node as known.
     */
    public void registerNode(NodeInfo node) {
        knownNodes.put(node.id()
                           .id(),
                       node);
        nodeSuspectTimes.remove(node.id()
                                    .id());
    }

    /**
     * Unregister a node.
     */
    public void unregisterNode(NodeId nodeId) {
        knownNodes.remove(nodeId.id());
        nodeSuspectTimes.remove(nodeId.id());
    }

    /**
     * Mark a node as suspected.
     */
    public void markSuspected(String nodeId) {
        nodeSuspectTimes.put(nodeId, System.currentTimeMillis());
    }

    /**
     * Clear suspected status for a node.
     */
    public void clearSuspected(String nodeId) {
        nodeSuspectTimes.remove(nodeId);
    }

    /**
     * Take a snapshot of current cluster topology.
     */
    public ClusterTopology snapshot() {
        var topology = option(topologyManager.get());
        var leader = option(leaderManager.get());
        var store = option(kvStore.get());
        if (topology.isEmpty()) {
            log.trace("TopologyManager not set, returning empty topology");
            return ClusterTopology.EMPTY;
        }
        // Get leader info using Option properly
        Option<String> leaderId = leader.flatMap(LeaderManager::leader)
                                        .map(NodeId::id);
        // Build node info from known nodes
        var nodeInfos = new ArrayList<ClusterTopology.NodeInfo>();
        int healthyCount = 0;
        for (var entry : knownNodes.entrySet()) {
            String nodeIdStr = entry.getKey();
            NodeInfo node = entry.getValue();
            String address = node.address()
                                 .host() + ":" + node.address()
                                                    .port();
            boolean isLeader = leaderId.map(id -> id.equals(nodeIdStr))
                                       .or(false);
            if (nodeSuspectTimes.containsKey(nodeIdStr)) {
                nodeInfos.add(ClusterTopology.NodeInfo.suspectedNodeInfo(nodeIdStr, address));
            } else {
                nodeInfos.add(ClusterTopology.NodeInfo.nodeInfo(nodeIdStr, address, isLeader));
                healthyCount++;
            }
        }
        // Get slice info from KV store
        Map<String, ClusterTopology.SliceInfo> sliceInfos = store.map(this::collectSliceInfo)
                                                                 .or(Map.of());
        int totalNodes = nodeInfos.size();
        int quorumSize = topology.map(TopologyManager::quorumSize)
                                 .or(0);
        boolean hasQuorum = healthyCount >= quorumSize;
        return new ClusterTopology(totalNodes, healthyCount, quorumSize, hasQuorum, leaderId, nodeInfos, sliceInfos);
    }

    private Map<String, ClusterTopology.SliceInfo> collectSliceInfo(KVStore<AetherKey, AetherValue> store) {
        Map<String, ClusterTopology.SliceInfo> sliceInfos = new HashMap<>();
        try{
            var snapshot = store.snapshot();
            var sliceCounts = new HashMap<String, Map<String, Integer>>();
            // Count slices per artifact per node
            for (var entry : snapshot.entrySet()) {
                if (entry.getKey() instanceof AetherKey.SliceNodeKey sliceKey) {
                    String artifact = sliceKey.artifact()
                                              .asString();
                    String nodeId = sliceKey.nodeId()
                                            .id();
                    sliceCounts.computeIfAbsent(artifact,
                                                _ -> new HashMap<>())
                               .merge(nodeId, 1, Integer::sum);
                }
            }
            // Build SliceInfo for each artifact
            for (var entry : sliceCounts.entrySet()) {
                String artifact = entry.getKey();
                var distribution = entry.getValue();
                int totalInstances = distribution.values()
                                                 .stream()
                                                 .mapToInt(Integer::intValue)
                                                 .sum();
                sliceInfos.put(artifact,
                               new ClusterTopology.SliceInfo(artifact, totalInstances, totalInstances, distribution));
            }
        } catch (Exception e) {
            log.debug("Failed to collect slice info: {}", e.getMessage());
        }
        return sliceInfos;
    }
}
