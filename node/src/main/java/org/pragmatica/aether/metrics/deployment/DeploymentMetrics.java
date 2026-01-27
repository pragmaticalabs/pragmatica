package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment timing metrics for a single slice deployment.
 * Tracks timestamps for each state transition to calculate latencies.
 */
public record DeploymentMetrics(Artifact artifact,
                                NodeId nodeId,
                                long startTime,
                                // T0: Blueprint change / LOAD command issued
long loadTime,
                                // T1: LOAD state received by node
long loadedTime,
                                // T2: LOADED state committed
long activateTime,
                                // T3: ACTIVATE state committed
long activeTime,
                                // T4: ACTIVE state committed (0 if not reached)
DeploymentStatus status) {
    public enum DeploymentStatus {
        IN_PROGRESS,
        SUCCESS,
        FAILED_LOADING,
        FAILED_ACTIVATING;
        public static DeploymentStatus deploymentStatus(String value) {
            return switch (value) {
                case "IN_PROGRESS" -> IN_PROGRESS;
                case "SUCCESS" -> SUCCESS;
                case "FAILED_LOADING" -> FAILED_LOADING;
                case "FAILED_ACTIVATING" -> FAILED_ACTIVATING;
                default -> IN_PROGRESS;
            };
        }
    }

    /**
     * Full deployment time from blueprint change to ACTIVE state.
     * Returns -1 if deployment not yet complete.
     */
    public long fullDeploymentTime() {
        return activeTime > 0
               ? activeTime - startTime
               : - 1;
    }

    /**
     * Net deployment time from LOADED to ACTIVE state.
     * Returns -1 if deployment not yet complete.
     */
    public long netDeploymentTime() {
        return activeTime > 0 && loadedTime > 0
               ? activeTime - loadedTime
               : - 1;
    }

    /**
     * Time spent in each transition.
     */
    public Map<String, Long> transitionLatencies() {
        var latencies = new LinkedHashMap<String, Long>();
        if (loadTime > 0 && startTime > 0) {
            latencies.put("START_TO_LOAD", loadTime - startTime);
        }
        if (loadedTime > 0 && loadTime > 0) {
            latencies.put("LOAD_TO_LOADED", loadedTime - loadTime);
        }
        if (activateTime > 0 && loadedTime > 0) {
            latencies.put("LOADED_TO_ACTIVATE", activateTime - loadedTime);
        }
        if (activeTime > 0 && activateTime > 0) {
            latencies.put("ACTIVATE_TO_ACTIVE", activeTime - activateTime);
        }
        return latencies;
    }

    /**
     * Convert to protocol message entry for network transmission.
     */
    public DeploymentMetricsEntry toEntry() {
        return new DeploymentMetricsEntry(artifact.asString(),
                                          nodeId.id(),
                                          startTime,
                                          loadTime,
                                          loadedTime,
                                          activateTime,
                                          activeTime,
                                          status.name());
    }

    /**
     * Create from protocol message entry.
     * Returns empty Option if artifact or nodeId parsing fails (should not happen with valid entries).
     */
    public static Option<DeploymentMetrics> fromEntry(DeploymentMetricsEntry entry) {
        return Artifact.artifact(entry.artifact())
                       .flatMap(artifact -> NodeId.nodeId(entry.nodeId())
                                                  .map(nodeId -> new DeploymentMetrics(artifact,
                                                                                       nodeId,
                                                                                       entry.startTime(),
                                                                                       entry.loadTime(),
                                                                                       entry.loadedTime(),
                                                                                       entry.activateTime(),
                                                                                       entry.activeTime(),
                                                                                       DeploymentStatus.deploymentStatus(entry.status()))))
                       .option();
    }

    /**
     * Create a new in-progress deployment starting now.
     */
    public static DeploymentMetrics deploymentMetrics(Artifact artifact, NodeId nodeId, long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, timestamp, 0, 0, 0, 0, DeploymentStatus.IN_PROGRESS);
    }

    /**
     * Update with LOAD state timestamp.
     */
    public DeploymentMetrics withLoadTime(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     timestamp,
                                     loadedTime,
                                     activateTime,
                                     activeTime,
                                     status);
    }

    /**
     * Update with LOADED state timestamp.
     */
    public DeploymentMetrics withLoadedTime(long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, startTime, loadTime, timestamp, activateTime, activeTime, status);
    }

    /**
     * Update with ACTIVATE state timestamp.
     */
    public DeploymentMetrics withActivateTime(long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, startTime, loadTime, loadedTime, timestamp, activeTime, status);
    }

    /**
     * Mark as completed with ACTIVE state timestamp.
     */
    public DeploymentMetrics completed(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     loadedTime,
                                     activateTime,
                                     timestamp,
                                     DeploymentStatus.SUCCESS);
    }

    /**
     * Mark as failed during loading.
     */
    public DeploymentMetrics failedLoading(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     timestamp,
                                     0,
                                     0,
                                     DeploymentStatus.FAILED_LOADING);
    }

    /**
     * Mark as failed during activation.
     */
    public DeploymentMetrics failedActivating(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     loadedTime,
                                     activateTime,
                                     timestamp,
                                     DeploymentStatus.FAILED_ACTIVATING);
    }
}
