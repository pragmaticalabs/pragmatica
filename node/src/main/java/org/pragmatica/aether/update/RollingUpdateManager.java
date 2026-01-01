package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

/**
 * Manages rolling update operations across the cluster.
 *
 * <p>Implements a two-stage rolling update model:
 * <ol>
 *   <li><b>Deploy stage</b>: New version instances deployed with 0% traffic</li>
 *   <li><b>Route stage</b>: Traffic gradually shifted to new version</li>
 * </ol>
 *
 * <p>Rolling updates are orchestrated by the leader node via consensus.
 * All state is stored in the KV-Store for persistence and visibility.
 *
 * <p>Usage:
 * <pre>{@code
 * // Start rolling update (deploys new version with 0% traffic)
 * manager.startUpdate(artifactBase, newVersion, 3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD)
 *     .await()
 *     .onSuccess(update -> {
 *         // Gradually shift traffic
 *         manager.adjustRouting(update.updateId(), VersionRouting.parse("1:3")).await();
 *         manager.adjustRouting(update.updateId(), VersionRouting.parse("1:1")).await();
 *         manager.adjustRouting(update.updateId(), VersionRouting.parse("1:0")).await();
 *
 *         // Complete and cleanup
 *         manager.completeUpdate(update.updateId()).await();
 *     });
 * }</pre>
 */
public interface RollingUpdateManager {
    /**
     * Starts a new rolling update.
     *
     * <p>This initiates the deploy stage:
     * <ul>
     *   <li>Creates update record in KV-Store</li>
     *   <li>Deploys new version instances (0% traffic)</li>
     *   <li>Waits for new instances to become healthy</li>
     *   <li>Transitions to DEPLOYED state</li>
     * </ul>
     *
     * @param artifactBase the artifact to update (version-agnostic)
     * @param newVersion the new version to deploy
     * @param instances number of new version instances
     * @param thresholds health thresholds for auto-progression
     * @param cleanupPolicy how to handle old version cleanup
     * @return the created rolling update
     */
    Promise<RollingUpdate> startUpdate(ArtifactBase artifactBase,
                                       Version newVersion,
                                       int instances,
                                       HealthThresholds thresholds,
                                       CleanupPolicy cleanupPolicy);

    /**
     * Adjusts traffic routing between versions.
     *
     * <p>Can only be called when update is in DEPLOYED, ROUTING, or VALIDATING state.
     * The routing ratio is scaled to available instances.
     *
     * @param updateId the update to adjust
     * @param newRouting the new routing configuration
     * @return updated rolling update
     */
    Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting);

    /**
     * Manually approves the current routing configuration.
     *
     * <p>Required when {@link HealthThresholds#requireManualApproval()} is true.
     * Allows progression to the next stage even if automatic health checks
     * would prevent it.
     *
     * @param updateId the update to approve
     * @return updated rolling update
     */
    Promise<RollingUpdate> approveRouting(String updateId);

    /**
     * Completes the rolling update.
     *
     * <p>Should only be called when all traffic is routed to new version (1:0).
     * Initiates cleanup of old version according to cleanup policy.
     *
     * @param updateId the update to complete
     * @return updated rolling update
     */
    Promise<RollingUpdate> completeUpdate(String updateId);

    /**
     * Rolls back the update to the old version.
     *
     * <p>Can be called at any non-terminal state. Routes all traffic back to
     * old version and removes new version instances.
     *
     * @param updateId the update to rollback
     * @return updated rolling update
     */
    Promise<RollingUpdate> rollback(String updateId);

    /**
     * Gets the current state of a rolling update.
     *
     * @param updateId the update to query
     * @return the update if found
     */
    Option<RollingUpdate> getUpdate(String updateId);

    /**
     * Gets the active update for an artifact (if any).
     *
     * @param artifactBase the artifact to query
     * @return the active update if found
     */
    Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase);

    /**
     * Lists all active (non-terminal) rolling updates.
     *
     * @return list of active updates
     */
    List<RollingUpdate> activeUpdates();

    /**
     * Lists all rolling updates (including completed ones).
     *
     * @return list of all updates
     */
    List<RollingUpdate> allUpdates();

    /**
     * Gets health metrics for an update's versions.
     *
     * @param updateId the update to query
     * @return health metrics for old and new versions
     */
    Promise<VersionHealthMetrics> getHealthMetrics(String updateId);

    /**
     * Health metrics for comparing old and new version performance.
     */
    record VersionHealthMetrics(
    String updateId,
    VersionMetrics oldVersion,
    VersionMetrics newVersion,
    long collectedAt) {
        public boolean isNewVersionHealthy(HealthThresholds thresholds) {
            return thresholds.isHealthy(newVersion.errorRate, newVersion.p99LatencyMs);
        }
    }

    /**
     * Metrics for a single version.
     */
    record VersionMetrics(
    Version version,
    long requestCount,
    long errorCount,
    double errorRate,
    long p99LatencyMs,
    long avgLatencyMs) {}
}
