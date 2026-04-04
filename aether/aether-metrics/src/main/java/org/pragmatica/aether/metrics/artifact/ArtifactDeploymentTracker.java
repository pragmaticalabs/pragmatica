package org.pragmatica.aether.metrics.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.lang.Contract;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Tracks artifact deployment status across the cluster by watching KV-Store events.
///
///
/// Responsibilities:
///
///   - Watch ValuePut/ValueRemove events for slice-node keys
///   - Maintain set of deployed artifacts across the cluster
///   - Provide deployment status queries
///
///
///
/// Key format watched: `slices/{nodeId`/{artifact}}
public interface ArtifactDeploymentTracker {
    @Contract void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @Contract void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    boolean isDeployed(Artifact artifact);
    Set<Artifact> deployedArtifacts();
    int deployedCount();

    static ArtifactDeploymentTracker artifactDeploymentTracker() {
        return new ArtifactDeploymentTrackerImpl();
    }
}

class ArtifactDeploymentTrackerImpl implements ArtifactDeploymentTracker {
    private static final Logger log = LoggerFactory.getLogger(ArtifactDeploymentTrackerImpl.class);

    private final ConcurrentHashMap<Artifact, Integer> deploymentCounts = new ConcurrentHashMap<>();

    @Override@Contract public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
        var artifact = valuePut.cause().key()
                                     .artifact();
        deploymentCounts.compute(artifact, (_, count) -> incrementCount(count));
        log.debug("Artifact deployed (NodeArtifactKey): {} (total: {})",
                  artifact.asString(),
                  deploymentCounts.get(artifact));
    }

    @Override@Contract public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
        var artifact = valueRemove.cause().key()
                                        .artifact();
        deploymentCounts.compute(artifact, (_, count) -> decrementCount(count));
        log.debug("Artifact undeployed (NodeArtifactKey): {} (remaining: {})",
                  artifact.asString(),
                  deploymentCounts.getOrDefault(artifact, 0));
    }

    @SuppressWarnings("JBCT-RET-03") private static Integer incrementCount(Integer count) {
        return count == null
              ? 1
              : count + 1;
    }

    @SuppressWarnings("JBCT-RET-03") private static Integer decrementCount(Integer count) {
        return (count == null || count <= 1)
              ? null
              : count - 1;
    }

    @Override public boolean isDeployed(Artifact artifact) {
        return deploymentCounts.containsKey(artifact);
    }

    @Override public Set<Artifact> deployedArtifacts() {
        return Set.copyOf(deploymentCounts.keySet());
    }

    @Override public int deployedCount() {
        return deploymentCounts.size();
    }
}
