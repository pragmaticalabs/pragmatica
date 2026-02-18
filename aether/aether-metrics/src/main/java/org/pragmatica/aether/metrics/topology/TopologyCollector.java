package org.pragmatica.aether.metrics.topology;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;

/// Collects cluster topology information from various sources.
///
/// Aggregates data from:
///
///   - TopologyManager for node state
///   - LeaderManager for leader info
///   - KVStore for slice distribution
///
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

    /// Set the topology manager reference.
    public Result<Unit> setTopologyManager(TopologyManager manager) {
        topologyManager.set(manager);
        option(manager).onPresent(this::registerSelf);
        return unitResult();
    }

    /// Set the leader manager reference.
    public Result<Unit> setLeaderManager(LeaderManager manager) {
        leaderManager.set(manager);
        return unitResult();
    }

    /// Set the KV store reference.
    @SuppressWarnings("JBCT-ACR-01")
    public Result<Unit> setKVStore(KVStore<AetherKey, AetherValue> store) {
        kvStore.set(store);
        return unitResult();
    }

    /// Register a node as known.
    public Result<Unit> registerNode(NodeInfo node) {
        knownNodes.put(node.id()
                           .id(),
                       node);
        nodeSuspectTimes.remove(node.id()
                                    .id());
        return unitResult();
    }

    /// Unregister a node.
    public Result<Unit> unregisterNode(NodeId nodeId) {
        knownNodes.remove(nodeId.id());
        nodeSuspectTimes.remove(nodeId.id());
        return unitResult();
    }

    /// Mark a node as suspected.
    public Result<Unit> markSuspected(String nodeId) {
        nodeSuspectTimes.put(nodeId, System.currentTimeMillis());
        return unitResult();
    }

    /// Clear suspected status for a node.
    public Result<Unit> clearSuspected(String nodeId) {
        nodeSuspectTimes.remove(nodeId);
        return unitResult();
    }

    /// Take a snapshot of current cluster topology.
    public ClusterTopology snapshot() {
        var topology = option(topologyManager.get());
        var leader = option(leaderManager.get());
        var store = option(kvStore.get());
        if (topology.isEmpty()) {
            log.trace("TopologyManager not set, returning empty topology");
            return ClusterTopology.EMPTY;
        }
        Option<String> leaderId = leader.flatMap(LeaderManager::leader)
                                        .map(NodeId::id);
        var nodeInfos = buildNodeInfos(leaderId);
        int healthyCount = countHealthyNodes();
        Map<String, ClusterTopology.SliceInfo> sliceInfos = store.map(this::collectSliceInfo)
                                                                 .or(Map.of());
        int totalNodes = nodeInfos.size();
        int quorumSize = topology.map(TopologyManager::quorumSize)
                                 .or(0);
        boolean hasQuorum = healthyCount >= quorumSize;
        return new ClusterTopology(totalNodes, healthyCount, quorumSize, hasQuorum, leaderId, nodeInfos, sliceInfos);
    }

    private void registerSelf(TopologyManager m) {
        var self = m.self();
        knownNodes.put(self.id()
                           .id(),
                       self);
    }

    private ArrayList<ClusterTopology.NodeInfo> buildNodeInfos(Option<String> leaderId) {
        var nodeInfos = new ArrayList<ClusterTopology.NodeInfo>();
        knownNodes.forEach((nodeIdStr, node) -> nodeInfos.add(buildNodeInfo(nodeIdStr, node, leaderId)));
        return nodeInfos;
    }

    private ClusterTopology.NodeInfo buildNodeInfo(String nodeIdStr, NodeInfo node, Option<String> leaderId) {
        String address = node.address()
                             .host() + ":" + node.address()
                                                .port();
        boolean isLeader = leaderId.map(id -> id.equals(nodeIdStr))
                                   .or(false);
        return nodeSuspectTimes.containsKey(nodeIdStr)
               ? ClusterTopology.NodeInfo.suspectedNodeInfo(nodeIdStr, address)
               : ClusterTopology.NodeInfo.nodeInfo(nodeIdStr, address, isLeader);
    }

    private int countHealthyNodes() {
        return (int) knownNodes.keySet()
                              .stream()
                              .filter(id -> !nodeSuspectTimes.containsKey(id))
                              .count();
    }

    private Map<String, ClusterTopology.SliceInfo> collectSliceInfo(KVStore<AetherKey, AetherValue> store) {
        Map<String, ClusterTopology.SliceInfo> sliceInfos = new HashMap<>();
        try{
            var sliceCounts = new HashMap<String, Map<String, Integer>>();
            store.forEach(SliceNodeKey.class, SliceNodeValue.class, (key, _) -> countSlice(sliceCounts, key));
            sliceCounts.forEach((artifact, distribution) -> sliceInfos.put(artifact,
                                                                           buildSliceInfo(artifact, distribution)));
        } catch (Exception e) {
            log.debug("Failed to collect slice info: {}", e.getMessage());
        }
        return sliceInfos;
    }

    private ClusterTopology.SliceInfo buildSliceInfo(String artifact, Map<String, Integer> distribution) {
        int totalInstances = distribution.values()
                                         .stream()
                                         .mapToInt(Integer::intValue)
                                         .sum();
        return new ClusterTopology.SliceInfo(artifact, totalInstances, totalInstances, distribution);
    }

    private void countSlice(Map<String, Map<String, Integer>> sliceCounts, SliceNodeKey sliceKey) {
        String artifact = sliceKey.artifact()
                                  .asString();
        String nodeId = sliceKey.nodeId()
                                .id();
        sliceCounts.computeIfAbsent(artifact,
                                    _ -> new HashMap<>())
                   .merge(nodeId, 1, Integer::sum);
    }
}
