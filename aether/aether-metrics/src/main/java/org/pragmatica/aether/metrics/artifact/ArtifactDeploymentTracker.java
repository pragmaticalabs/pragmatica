package org.pragmatica.aether.metrics.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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
    /// Handle slice deployment event.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    /// Handle slice removal event.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /// Check if an artifact is deployed anywhere in the cluster.
    boolean isDeployed(Artifact artifact);

    /// Get all deployed artifacts.
    Set<Artifact> deployedArtifacts();

    /// Get count of deployed artifacts.
    int deployedCount();

    /// Create a new artifact deployment tracker.
    static ArtifactDeploymentTracker artifactDeploymentTracker() {
        return new ArtifactDeploymentTrackerImpl();
    }
}

class ArtifactDeploymentTrackerImpl implements ArtifactDeploymentTracker {
    private static final Logger log = LoggerFactory.getLogger(ArtifactDeploymentTrackerImpl.class);

    // Tracks artifact -> count of deployments across nodes
    private final ConcurrentHashMap<Artifact, Integer> deploymentCounts = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        var key = valuePut.cause()
                          .key();
        if (key instanceof SliceNodeKey sliceNodeKey) {
            var artifact = sliceNodeKey.artifact();
            deploymentCounts.compute(artifact, (_, count) -> incrementCount(count));
            log.debug("Artifact deployed: {} (total deployments: {})",
                      artifact.asString(),
                      deploymentCounts.get(artifact));
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        var key = valueRemove.cause()
                             .key();
        if (key instanceof SliceNodeKey sliceNodeKey) {
            var artifact = sliceNodeKey.artifact();
            deploymentCounts.compute(artifact, (_, count) -> decrementCount(count));
            log.debug("Artifact undeployed: {} (remaining deployments: {})",
                      artifact.asString(),
                      deploymentCounts.getOrDefault(artifact, 0));
        }
    }

    @SuppressWarnings("JBCT-RET-03") // null return required by ConcurrentHashMap.compute API to signal absence
    private static Integer incrementCount(Integer count) {
        return count == null
               ? 1
               : count + 1;
    }

    @SuppressWarnings("JBCT-RET-03") // null return required by ConcurrentHashMap.compute API to signal removal
    private static Integer decrementCount(Integer count) {
        return (count == null || count <= 1)
               ? null
               : count - 1;
    }

    @Override
    public boolean isDeployed(Artifact artifact) {
        return deploymentCounts.containsKey(artifact);
    }

    @Override
    public Set<Artifact> deployedArtifacts() {
        return Set.copyOf(deploymentCounts.keySet());
    }

    @Override
    public int deployedCount() {
        return deploymentCounts.size();
    }
}
