package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Cause;

/// Errors that can occur during A/B test deployment operations.
public sealed interface AbTestDeploymentError extends Cause {
    /// A/B test not found.
    record TestNotFound(String testId) implements AbTestDeploymentError {
        public static TestNotFound testNotFound(String testId) {
            return new TestNotFound(testId);
        }

        @Override public String message() {
            return "A/B test not found: " + testId;
        }
    }

    /// Active A/B test already exists for this artifact.
    record TestAlreadyExists(ArtifactBase artifactBase) implements AbTestDeploymentError {
        public static TestAlreadyExists testAlreadyExists(ArtifactBase artifactBase) {
            return new TestAlreadyExists(artifactBase);
        }

        @Override public String message() {
            return "A/B test already in progress for " + artifactBase;
        }
    }

    /// Invalid state for the requested operation.
    record InvalidTestState(AbTestState from, AbTestState to) implements AbTestDeploymentError {
        public static InvalidTestState invalidTestState(AbTestState from, AbTestState to) {
            return new InvalidTestState(from, to);
        }

        @Override public String message() {
            return "Invalid A/B test state transition from " + from + " to " + to;
        }
    }

    /// No current version exists (initial deployment).
    record InitialDeployment(ArtifactBase artifactBase) implements AbTestDeploymentError {
        public static InitialDeployment initialDeployment(ArtifactBase artifactBase) {
            return new InitialDeployment(artifactBase);
        }

        @Override public String message() {
            return "Initial deployment for " + artifactBase + " (no previous version)";
        }
    }

    /// Winning variant not found in the test.
    record VariantNotFound(String testId, String variant) implements AbTestDeploymentError {
        public static VariantNotFound variantNotFound(String testId, String variant) {
            return new VariantNotFound(testId, variant);
        }

        @Override public String message() {
            return "Variant '" + variant + "' not found in A/B test " + testId;
        }
    }

    /// Not the leader node.
    enum NotLeader implements AbTestDeploymentError {
        INSTANCE;
        @Override public String message() {
            return "A/B test operations can only be performed by the leader node";
        }
    }
}
