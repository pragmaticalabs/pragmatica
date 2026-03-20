package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Cause;

/// Errors that can occur during blue-green deployment operations.
public sealed interface BlueGreenDeploymentError extends Cause {
    /// Blue-green deployment not found.
    record DeploymentNotFound(String deploymentId) implements BlueGreenDeploymentError {
        public static DeploymentNotFound deploymentNotFound(String deploymentId) {
            return new DeploymentNotFound(deploymentId);
        }

        @Override
        public String message() {
            return "Blue-green deployment not found: " + deploymentId;
        }
    }

    /// Active blue-green deployment already exists for this artifact.
    record DeploymentAlreadyExists(ArtifactBase artifactBase) implements BlueGreenDeploymentError {
        public static DeploymentAlreadyExists deploymentAlreadyExists(ArtifactBase artifactBase) {
            return new DeploymentAlreadyExists(artifactBase);
        }

        @Override
        public String message() {
            return "Blue-green deployment already in progress for " + artifactBase;
        }
    }

    /// Invalid state for the requested operation.
    record InvalidDeploymentState(BlueGreenState from, BlueGreenState to) implements BlueGreenDeploymentError {
        public static InvalidDeploymentState invalidDeploymentState(BlueGreenState from, BlueGreenState to) {
            return new InvalidDeploymentState(from, to);
        }

        @Override
        public String message() {
            return "Invalid blue-green state transition from " + from + " to " + to;
        }
    }

    /// No current version exists (initial deployment).
    record InitialDeployment(ArtifactBase artifactBase) implements BlueGreenDeploymentError {
        public static InitialDeployment initialDeployment(ArtifactBase artifactBase) {
            return new InitialDeployment(artifactBase);
        }

        @Override
        public String message() {
            return "Initial deployment for " + artifactBase + " (no previous version)";
        }
    }

    /// Not the leader node.
    enum NotLeader implements BlueGreenDeploymentError {
        INSTANCE;
        @Override
        public String message() {
            return "Blue-green deployment operations can only be performed by the leader node";
        }
    }
}
