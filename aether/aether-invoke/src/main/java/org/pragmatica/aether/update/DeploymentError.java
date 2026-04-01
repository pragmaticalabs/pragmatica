package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;

/// Errors that can occur during deployment operations.
public sealed interface DeploymentError extends Cause {
    /// Deployment not found.
    record DeploymentNotFound(String deploymentId) implements DeploymentError {
        public static DeploymentNotFound deploymentNotFound(String deploymentId) {
            return new DeploymentNotFound(deploymentId);
        }

        @Override public String message() {
            return "Deployment not found: " + deploymentId;
        }
    }

    /// Active deployment already exists for this blueprint.
    record DeploymentAlreadyExists(String blueprintId) implements DeploymentError {
        public static DeploymentAlreadyExists deploymentAlreadyExists(String blueprintId) {
            return new DeploymentAlreadyExists(blueprintId);
        }

        @Override public String message() {
            return "Deployment already in progress for blueprint: " + blueprintId;
        }
    }

    /// Blueprint not found in KV-Store.
    record BlueprintNotFound(String blueprintId) implements DeploymentError {
        public static BlueprintNotFound blueprintNotFound(String blueprintId) {
            return new BlueprintNotFound(blueprintId);
        }

        @Override public String message() {
            return "Blueprint not found: " + blueprintId;
        }
    }

    /// No current version exists for a slice (initial deployment).
    record NoCurrentVersion(String artifactBase) implements DeploymentError {
        public static NoCurrentVersion noCurrentVersion(String artifactBase) {
            return new NoCurrentVersion(artifactBase);
        }

        @Override public String message() {
            return "No current version for " + artifactBase + " (initial deployment — use blueprint deploy instead)";
        }
    }

    /// Consensus apply failed.
    record ConsensusFailure(Cause cause) implements DeploymentError {
        public static ConsensusFailure consensusFailure(Cause cause) {
            return new ConsensusFailure(cause);
        }

        @Override public String message() {
            return "Consensus apply failed: " + cause.message();
        }
    }

    /// General errors with fixed messages.
    enum General implements DeploymentError {
        NOT_LEADER("Deployment operations can only be performed by the leader node"),
        NOT_ACTIVE("Deployment is not in an active state"),
        INVALID_STRATEGY_CONFIG("Strategy config does not match deployment strategy");

        private final String msg;

        General(String msg) {
            this.msg = msg;
        }

        @Override public String message() {
            return msg;
        }
    }
}
