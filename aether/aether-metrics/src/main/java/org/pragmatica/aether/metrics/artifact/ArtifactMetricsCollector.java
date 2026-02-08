package org.pragmatica.aether.metrics.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.Set;

/**
 * Collects and exposes artifact storage and deployment metrics.
 *
 * <p>Provides the following metrics:
 * <ul>
 *   <li>{@code artifact_chunks_total} - Total chunks stored on this node</li>
 *   <li>{@code artifact_memory_bytes} - Memory used (chunks x 64KB)</li>
 *   <li>{@code artifact_count} - Number of distinct artifacts stored</li>
 *   <li>{@code artifact_deployed_count} - Number of artifacts deployed in cluster</li>
 * </ul>
 *
 * <p>Combines storage metrics from {@link ArtifactStore} and deployment tracking
 * from {@link ArtifactDeploymentTracker}.
 */
public interface ArtifactMetricsCollector {
    // Metric names
    String ARTIFACT_CHUNKS_TOTAL = "artifact.chunks.total";
    String ARTIFACT_MEMORY_BYTES = "artifact.memory.bytes";
    String ARTIFACT_COUNT = "artifact.count";
    String ARTIFACT_DEPLOYED_COUNT = "artifact.deployed.count";

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
     * Collect all artifact metrics.
     */
    Map<String, Double> collectMetrics();

    /**
     * Check if an artifact is deployed anywhere in the cluster.
     */
    boolean isDeployed(Artifact artifact);

    /**
     * Get all deployed artifacts.
     */
    Set<Artifact> deployedArtifacts();

    /**
     * Get the artifact store metrics.
     */
    ArtifactStore.Metrics storeMetrics();

    /**
     * Get the deployment tracker for detailed queries.
     */
    ArtifactDeploymentTracker deploymentTracker();

    /**
     * Create an artifact metrics collector.
     *
     * @param artifactStore the artifact store to collect storage metrics from
     */
    static ArtifactMetricsCollector artifactMetricsCollector(ArtifactStore artifactStore) {
        return new ArtifactMetricsCollectorImpl(artifactStore, ArtifactDeploymentTracker.artifactDeploymentTracker());
    }
}

class ArtifactMetricsCollectorImpl implements ArtifactMetricsCollector {
    private final ArtifactStore artifactStore;
    private final ArtifactDeploymentTracker deploymentTracker;

    ArtifactMetricsCollectorImpl(ArtifactStore artifactStore,
                                 ArtifactDeploymentTracker deploymentTracker) {
        this.artifactStore = artifactStore;
        this.deploymentTracker = deploymentTracker;
    }

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        deploymentTracker.onValuePut(valuePut);
    }

    @Override
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        deploymentTracker.onValueRemove(valueRemove);
    }

    @Override
    public Map<String, Double> collectMetrics() {
        var storeMetrics = artifactStore.metrics();
        return Map.of(ARTIFACT_CHUNKS_TOTAL,
                      (double) storeMetrics.chunkCount(),
                      ARTIFACT_MEMORY_BYTES,
                      (double) storeMetrics.memoryBytes(),
                      ARTIFACT_COUNT,
                      (double) storeMetrics.artifactCount(),
                      ARTIFACT_DEPLOYED_COUNT,
                      (double) deploymentTracker.deployedCount());
    }

    @Override
    public boolean isDeployed(Artifact artifact) {
        return deploymentTracker.isDeployed(artifact);
    }

    @Override
    public Set<Artifact> deployedArtifacts() {
        return deploymentTracker.deployedArtifacts();
    }

    @Override
    public ArtifactStore.Metrics storeMetrics() {
        return artifactStore.metrics();
    }

    @Override
    public ArtifactDeploymentTracker deploymentTracker() {
        return deploymentTracker;
    }
}
