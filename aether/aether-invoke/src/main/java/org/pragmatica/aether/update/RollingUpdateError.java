package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;

/// Errors that can occur during rolling update operations.
public sealed interface RollingUpdateError extends Cause {
    /// Update not found.
    record UpdateNotFound(String updateId) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static UpdateNotFound updateNotFound(String updateId) {
            return new UpdateNotFound(updateId);
        }

        @Override
        public String message() {
            return "Rolling update not found: " + updateId;
        }
    }

    /// Update already exists for this artifact.
    record UpdateAlreadyExists(ArtifactBase artifactBase) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static UpdateAlreadyExists updateAlreadyExists(ArtifactBase artifactBase) {
            return new UpdateAlreadyExists(artifactBase);
        }

        @Override
        public String message() {
            return "Rolling update already in progress for " + artifactBase;
        }
    }

    /// Invalid state transition.
    record InvalidStateTransition(RollingUpdateState from, RollingUpdateState to) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static InvalidStateTransition invalidStateTransition(RollingUpdateState from, RollingUpdateState to) {
            return new InvalidStateTransition(from, to);
        }

        @Override
        public String message() {
            return "Invalid state transition from " + from + " to " + to;
        }
    }

    /// Version not found.
    record VersionNotFound(ArtifactBase artifactBase, Version version) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static VersionNotFound versionNotFound(ArtifactBase artifactBase, Version version) {
            return new VersionNotFound(artifactBase, version);
        }

        @Override
        public String message() {
            return "Version " + version + " not found for " + artifactBase;
        }
    }

    /// Initial deployment (no previous version exists).
    record InitialDeployment(ArtifactBase artifactBase) implements RollingUpdateError {
        public static InitialDeployment initialDeployment(ArtifactBase artifactBase) {
            return new InitialDeployment(artifactBase);
        }

        @Override
        public String message() {
            return "Initial deployment for " + artifactBase + " (no previous version)";
        }
    }

    /// Insufficient instances to satisfy routing ratio.
    record InsufficientInstances(VersionRouting routing,
                                 int newInstances,
                                 int oldInstances) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static InsufficientInstances insufficientInstances(VersionRouting routing,
                                                                  int newInstances,
                                                                  int oldInstances) {
            return new InsufficientInstances(routing, newInstances, oldInstances);
        }

        @Override
        public String message() {
            return "Cannot satisfy routing " + routing + " with " + newInstances + " new and " + oldInstances
                   + " old instances";
        }
    }

    /// Health check failed.
    record HealthCheckFailed(double errorRate,
                             long latencyMs,
                             HealthThresholds thresholds) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static HealthCheckFailed healthCheckFailed(double errorRate,
                                                          long latencyMs,
                                                          HealthThresholds thresholds) {
            return new HealthCheckFailed(errorRate, latencyMs, thresholds);
        }

        @Override
        public String message() {
            return "Health check failed: error rate " + errorRate + " (max " + thresholds.maxErrorRate() + "), latency " + latencyMs
                   + "ms (max " + thresholds.maxLatencyMs() + "ms)";
        }
    }

    /// Manual approval required.
    record ManualApprovalRequired(String updateId) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static ManualApprovalRequired manualApprovalRequired(String updateId) {
            return new ManualApprovalRequired(updateId);
        }

        @Override
        public String message() {
            return "Manual approval required for update: " + updateId;
        }
    }

    /// Deployment failed.
    record DeploymentFailed(String updateId, Cause cause) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static DeploymentFailed deploymentFailed(String updateId, Cause cause) {
            return new DeploymentFailed(updateId, cause);
        }

        @Override
        public String message() {
            return "Deployment failed for update " + updateId + ": " + cause.message();
        }
    }

    /// Rollback failed.
    record RollbackFailed(String updateId, Cause cause) implements RollingUpdateError {
        /// Factory method following JBCT naming convention. */
        public static RollbackFailed rollbackFailed(String updateId, Cause cause) {
            return new RollbackFailed(updateId, cause);
        }

        @Override
        public String message() {
            return "Rollback failed for update " + updateId + ": " + cause.message();
        }
    }

    /// Not the leader node.
    enum NotLeader implements RollingUpdateError {
        INSTANCE;
        @Override
        public String message() {
            return "Rolling update operations can only be performed by the leader node";
        }
    }
}
