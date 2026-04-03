package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Cleans up KV-Store entries for departed worker nodes.
/// Called by the governor when SWIM detects FAULTY or LEFT members.
///
/// Maintains an index of node-to-key mappings for efficient cleanup.
/// Tracks NodeArtifactKey and NodeRoutesKey for forwarding removes via consensus.
@SuppressWarnings({"JBCT-RET-01", "JBCT-STY-05"}) public interface GovernorCleanup {
    Logger log = LoggerFactory.getLogger(GovernorCleanup.class);

    String NODE_ARTIFACT_PREFIX = "node-artifact/";

    String NODE_ROUTES_PREFIX = "node-routes/";

    void trackNodeArtifact(NodeId nodeId, NodeArtifactKey key);
    void trackNodeRoutes(NodeId nodeId, NodeRoutesKey key);
    void untrackNodeArtifact(NodeId nodeId, NodeArtifactKey key);
    void untrackNodeRoutes(NodeId nodeId, NodeRoutesKey key);
    Promise<Unit> cleanupDeadNodes(Set<NodeId> aliveNodes);
    Promise<Unit> cleanupDeadNode(NodeId deadNode);
    Promise<Unit> rebuildIndex(DHTNode dhtNode);

    static GovernorCleanup governorCleanup(MutationForwarder mutationForwarder) {
        record governorCleanup(MutationForwarder mutationForwarder,
                               Map<NodeId, Set<NodeArtifactKey>> nodeArtifactIndex,
                               Map<NodeId, Set<NodeRoutesKey>> nodeRoutesIndex,
                               AtomicLong correlationCounter) implements GovernorCleanup {
            @Override public void trackNodeArtifact(NodeId nodeId, NodeArtifactKey key) {
                nodeArtifactIndex.computeIfAbsent(nodeId, _ -> ConcurrentHashMap.newKeySet()).add(key);
            }

            @Override public void trackNodeRoutes(NodeId nodeId, NodeRoutesKey key) {
                nodeRoutesIndex.computeIfAbsent(nodeId, _ -> ConcurrentHashMap.newKeySet()).add(key);
            }

            @Override public void untrackNodeArtifact(NodeId nodeId, NodeArtifactKey key) {
                var keys = nodeArtifactIndex.get(nodeId);
                if (keys != null) {keys.remove(key);}
            }

            @Override public void untrackNodeRoutes(NodeId nodeId, NodeRoutesKey key) {
                var keys = nodeRoutesIndex.get(nodeId);
                if (keys != null) {keys.remove(key);}
            }

            @Override public Promise<Unit> cleanupDeadNodes(Set<NodeId> aliveNodes) {
                var deadNodes = new HashSet<NodeId>();
                deadNodes.addAll(nodeArtifactIndex.keySet());
                deadNodes.addAll(nodeRoutesIndex.keySet());
                deadNodes.removeAll(aliveNodes);
                if (deadNodes.isEmpty()) {
                    log.info("No dead nodes found during reconciliation");
                    return Promise.unitPromise();
                }
                log.info("Reconciliation found {} dead nodes: {}", deadNodes.size(), deadNodes);
                var result = Promise.unitPromise();
                for (var deadNode : deadNodes) {result = result.flatMap(_ -> cleanupDeadNode(deadNode));}
                return result;
            }

            @Override public Promise<Unit> cleanupDeadNode(NodeId deadNode) {
                var nodeArtifactKeys = List.copyOf(nodeArtifactIndex.getOrDefault(deadNode, Set.of()));
                var nodeRoutesKeys = List.copyOf(nodeRoutesIndex.getOrDefault(deadNode, Set.of()));
                if (nodeArtifactKeys.isEmpty() && nodeRoutesKeys.isEmpty()) {
                    log.debug("No KV entries to clean up for dead node {}", deadNode);
                    return Promise.unitPromise();
                }
                log.info("Cleaning up {} node-artifacts, {} node-routes for dead node {}",
                         nodeArtifactKeys.size(),
                         nodeRoutesKeys.size(),
                         deadNode);
                forwardRemoveAll(nodeArtifactKeys, deadNode, "node-artifact");
                forwardRemoveAll(nodeRoutesKeys, deadNode, "node-routes");
                clearIndices(deadNode);
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> rebuildIndex(DHTNode dhtNode) {
                clearAllIndices();
                return dhtNode.storage().entries()
                                      .map(this::processEntries)
                                      .mapToUnit();
            }

            private void clearAllIndices() {
                nodeArtifactIndex.clear();
                nodeRoutesIndex.clear();
            }

            private int processEntries(List<DHTMessage.KeyValue> entries) {
                var count = 0;
                for (var entry : entries) {
                    var keyStr = new String(entry.key(), StandardCharsets.UTF_8);
                    if (tryProcessNodeArtifact(keyStr) || tryProcessNodeRoutes(keyStr)) {count++;}
                }
                log.info("Rebuilt cleanup index from DHT: {} tracked entries", count);
                return count;
            }

            private boolean tryProcessNodeArtifact(String keyStr) {
                if (!keyStr.startsWith(NODE_ARTIFACT_PREFIX)) {return false;}
                return NodeArtifactKey.nodeArtifactKey(keyStr).fold(_ -> false, this::trackAndReturnNodeArtifact);
            }

            private boolean trackAndReturnNodeArtifact(NodeArtifactKey nak) {
                trackNodeArtifact(nak.nodeId(), nak);
                return true;
            }

            private boolean tryProcessNodeRoutes(String keyStr) {
                if (!keyStr.startsWith(NODE_ROUTES_PREFIX)) {return false;}
                return NodeRoutesKey.nodeRoutesKey(keyStr).fold(_ -> false, this::trackAndReturnNodeRoutes);
            }

            private boolean trackAndReturnNodeRoutes(NodeRoutesKey nrk) {
                trackNodeRoutes(nrk.nodeId(), nrk);
                return true;
            }

            private void clearIndices(NodeId deadNode) {
                nodeArtifactIndex.remove(deadNode);
                nodeRoutesIndex.remove(deadNode);
                log.info("Completed KV cleanup for dead node {}", deadNode);
            }

            @SuppressWarnings("unchecked") private <K extends AetherKey> void forwardRemoveAll(List<K> keys,
                                                                                               NodeId deadNode,
                                                                                               String type) {
                for (var key : keys) {
                    var correlationId = "cleanup-" + deadNode.id() + "-" + type + "-" + correlationCounter.incrementAndGet();
                    KVCommand<AetherKey> command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);
                    mutationForwarder.forward(WorkerMutation.workerMutation(deadNode, correlationId, command));
                }
            }
        }
        return new governorCleanup(mutationForwarder,
                                   new ConcurrentHashMap<>(),
                                   new ConcurrentHashMap<>(),
                                   new AtomicLong(0));
    }
}
