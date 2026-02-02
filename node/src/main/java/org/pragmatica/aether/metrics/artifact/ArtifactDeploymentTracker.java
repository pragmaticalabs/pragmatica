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

/**
 * Tracks artifact deployment status across the cluster by watching KV-Store events.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Watch ValuePut/ValueRemove events for slice-node keys</li>
 *   <li>Maintain set of deployed artifacts across the cluster</li>
 *   <li>Provide deployment status queries</li>
 * </ul>
 *
 * <p>Key format watched: {@code slices/{nodeId}/{artifact}}
 */
public interface ArtifactDeploymentTracker {
    /**
     * Handle slice deployment event.
     */
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    /**
     * Handle slice removal event.
     */
    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * Check if an artifact is deployed anywhere in the cluster.
     */
    boolean isDeployed(Artifact artifact);

    /**
     * Get all deployed artifacts.
     */
    Set<Artifact> deployedArtifacts();

    /**
     * Get count of deployed artifacts.
     */
    int deployedCount();

    /**
     * Create a new artifact deployment tracker.
     */
    static ArtifactDeploymentTracker artifactDeploymentTracker() {
        return new ArtifactDeploymentTrackerImpl();
    }
}

class ArtifactDeploymentTrackerImpl implements ArtifactDeploymentTracker {
    private static final Logger log = LoggerFactory.getLogger(ArtifactDeploymentTrackerImpl.class);

    // Tracks artifact -> count of deployments across nodes
    private final ConcurrentHashMap<Artifact, Integer> deploymentCounts = new ConcurrentHashMap<>();

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        var key = valuePut.cause()
                          .key();
        if (key instanceof SliceNodeKey sliceNodeKey) {
            var artifact = sliceNodeKey.artifact();
            deploymentCounts.compute(artifact, (_, count) -> count == null
                                                             ? 1
                                                             : count + 1);
            log.debug("Artifact deployed: {} (total deployments: {})",
                      artifact.asString(),
                      deploymentCounts.get(artifact));
        }
    }

    @Override
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        var key = valueRemove.cause()
                             .key();
        if (key instanceof SliceNodeKey sliceNodeKey) {
            var artifact = sliceNodeKey.artifact();
            deploymentCounts.compute(artifact,
                                     (_, count) -> {
                                         if (count == null || count <= 1) {
                                             return null;
                                         }
                                         return count - 1;
                                     });
            log.debug("Artifact undeployed: {} (remaining deployments: {})",
                      artifact.asString(),
                      deploymentCounts.getOrDefault(artifact, 0));
        }
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
