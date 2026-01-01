package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during rolling update operations.
 */
public sealed interface RollingUpdateError extends Cause {
    /**
     * Update not found.
     */
    record UpdateNotFound(String updateId) implements RollingUpdateError {
        @Override
        public String message() {
            return "Rolling update not found: " + updateId;
        }
    }

    /**
     * Update already exists for this artifact.
     */
    record UpdateAlreadyExists(ArtifactBase artifactBase) implements RollingUpdateError {
        @Override
        public String message() {
            return "Rolling update already in progress for " + artifactBase;
        }
    }

    /**
     * Invalid state transition.
     */
    record InvalidStateTransition(RollingUpdateState from, RollingUpdateState to) implements RollingUpdateError {
        @Override
        public String message() {
            return "Invalid state transition from " + from + " to " + to;
        }
    }

    /**
     * Version not found.
     */
    record VersionNotFound(ArtifactBase artifactBase, Version version) implements RollingUpdateError {
        @Override
        public String message() {
            return "Version " + version + " not found for " + artifactBase;
        }
    }

    /**
     * Insufficient instances to satisfy routing ratio.
     */
    record InsufficientInstances(
    VersionRouting routing,
    int newInstances,
    int oldInstances) implements RollingUpdateError {
        @Override
        public String message() {
            return "Cannot satisfy routing " + routing + " with " + newInstances + " new and " + oldInstances
                   + " old instances";
        }
    }

    /**
     * Health check failed.
     */
    record HealthCheckFailed(
    double errorRate,
    long latencyMs,
    HealthThresholds thresholds) implements RollingUpdateError {
        @Override
        public String message() {
            return "Health check failed: error rate " + errorRate + " (max " + thresholds.maxErrorRate()
                   + "), latency " + latencyMs + "ms (max " + thresholds.maxLatencyMs() + "ms)";
        }
    }

    /**
     * Manual approval required.
     */
    record ManualApprovalRequired(String updateId) implements RollingUpdateError {
        @Override
        public String message() {
            return "Manual approval required for update: " + updateId;
        }
    }

    /**
     * Deployment failed.
     */
    record DeploymentFailed(String updateId, Cause cause) implements RollingUpdateError {
        @Override
        public String message() {
            return "Deployment failed for update " + updateId + ": " + cause.message();
        }
    }

    /**
     * Rollback failed.
     */
    record RollbackFailed(String updateId, Cause cause) implements RollingUpdateError {
        @Override
        public String message() {
            return "Rollback failed for update " + updateId + ": " + cause.message();
        }
    }

    /**
     * Not the leader node.
     */
    record NotLeader() implements RollingUpdateError {
        public static final NotLeader INSTANCE = new NotLeader();

        @Override
        public String message() {
            return "Rolling update operations can only be performed by the leader node";
        }
    }
}
