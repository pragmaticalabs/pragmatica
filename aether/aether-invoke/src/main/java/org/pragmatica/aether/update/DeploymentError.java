package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;


/// Errors that can occur during deployment operations.
public sealed interface DeploymentError extends Cause {
    record DeploymentNotFound(String deploymentId) implements DeploymentError {
        public static DeploymentNotFound deploymentNotFound(String deploymentId) {
            return new DeploymentNotFound(deploymentId);
        }

        @Override public String message() {
            return "Deployment not found: " + deploymentId;
        }
    }

    record DeploymentAlreadyExists(String blueprintId) implements DeploymentError {
        public static DeploymentAlreadyExists deploymentAlreadyExists(String blueprintId) {
            return new DeploymentAlreadyExists(blueprintId);
        }

        @Override public String message() {
            return "Deployment already in progress for blueprint: " + blueprintId;
        }
    }

    record BlueprintNotFound(String blueprintId) implements DeploymentError {
        public static BlueprintNotFound blueprintNotFound(String blueprintId) {
            return new BlueprintNotFound(blueprintId);
        }

        @Override public String message() {
            return "Blueprint not found: " + blueprintId;
        }
    }

    record NoCurrentVersion(String artifactBase) implements DeploymentError {
        public static NoCurrentVersion noCurrentVersion(String artifactBase) {
            return new NoCurrentVersion(artifactBase);
        }

        @Override public String message() {
            return "No current version for " + artifactBase + " (initial deployment — use blueprint deploy instead)";
        }
    }

    record ConsensusFailure(Cause cause) implements DeploymentError {
        public static ConsensusFailure consensusFailure(Cause cause) {
            return new ConsensusFailure(cause);
        }

        @Override public String message() {
            return "Consensus apply failed: " + cause.message();
        }
    }

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
