package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Cause;

/// Errors that can occur during canary deployment operations.
public sealed interface CanaryDeploymentError extends Cause {
    /// Canary deployment not found.
    record CanaryNotFound(String canaryId) implements CanaryDeploymentError {
        public static CanaryNotFound canaryNotFound(String canaryId) {
            return new CanaryNotFound(canaryId);
        }

        @Override
        public String message() {
            return "Canary deployment not found: " + canaryId;
        }
    }

    /// Active canary already exists for this artifact.
    record CanaryAlreadyExists(ArtifactBase artifactBase) implements CanaryDeploymentError {
        public static CanaryAlreadyExists canaryAlreadyExists(ArtifactBase artifactBase) {
            return new CanaryAlreadyExists(artifactBase);
        }

        @Override
        public String message() {
            return "Canary deployment already in progress for " + artifactBase;
        }
    }

    /// Invalid state for the requested operation.
    record InvalidCanaryState(CanaryState from, CanaryState to) implements CanaryDeploymentError {
        public static InvalidCanaryState invalidCanaryState(CanaryState from, CanaryState to) {
            return new InvalidCanaryState(from, to);
        }

        @Override
        public String message() {
            return "Invalid canary state transition from " + from + " to " + to;
        }
    }

    /// No current version exists (initial deployment).
    record InitialDeployment(ArtifactBase artifactBase) implements CanaryDeploymentError {
        public static InitialDeployment initialDeployment(ArtifactBase artifactBase) {
            return new InitialDeployment(artifactBase);
        }

        @Override
        public String message() {
            return "Initial deployment for " + artifactBase + " (no previous version)";
        }
    }

    /// Not the leader node.
    enum NotLeader implements CanaryDeploymentError {
        INSTANCE;
        @Override
        public String message() {
            return "Canary deployment operations can only be performed by the leader node";
        }
    }
}
