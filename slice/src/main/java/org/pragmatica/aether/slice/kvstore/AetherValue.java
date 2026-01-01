package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.consensus.NodeId;

import java.util.List;

/// Value type stored in the consensus KVStore
public sealed interface AetherValue {
    /// Blueprints contain information about the exact number of nodes which need to be deployed (deprecated).
    @Deprecated
    record BlueprintValue(long instanceCount) implements AetherValue {}

    /// Application blueprint contains the expanded blueprint with full dependency resolution
    record AppBlueprintValue(ExpandedBlueprint blueprint) implements AetherValue {}

    /// Deployment Vector (NodeId/Artifact) contains the current state of the loaded slice
    record SliceNodeValue(SliceState state) implements AetherValue {}

    /// Endpoint locator points to node where endpoint is available
    record EndpointValue(NodeId nodeId) implements AetherValue {}

    /// Route definition for HTTP routing.
    /// Contains full route information for self-registration by slices.
    record RouteValue(
    Artifact artifact,
    String methodName,
    String httpMethod,
    String pathPattern,
    List<Binding> bindings) implements AetherValue {
        /// Check if this route matches another route (same target).
        /// Used for idempotent registration validation.
        public boolean matches(RouteValue other) {
            return artifact.equals(other.artifact) &&
            methodName.equals(other.methodName) &&
            httpMethod.equalsIgnoreCase(other.httpMethod) &&
            pathPattern.equals(other.pathPattern) &&
            bindings.equals(other.bindings);
        }
    }

    /// Version routing configuration for rolling updates.
    /// Stores traffic distribution between old and new versions.
    ///
    /// @param oldVersion the version being replaced
    /// @param newVersion the version being deployed
    /// @param newWeight traffic weight for new version
    /// @param oldWeight traffic weight for old version
    /// @param updatedAt timestamp of last update
    record VersionRoutingValue(
    Version oldVersion,
    Version newVersion,
    int newWeight,
    int oldWeight,
    long updatedAt) implements AetherValue {
        /// Creates initial routing with all traffic to old version.
        public static VersionRoutingValue initial(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 0, 1, System.currentTimeMillis());
        }

        /// Creates routing with all traffic to new version.
        public static VersionRoutingValue allNew(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 1, 0, System.currentTimeMillis());
        }

        /// Updates the routing weights.
        public VersionRoutingValue withRouting(int newWeight, int oldWeight) {
            return new VersionRoutingValue(oldVersion, newVersion, newWeight, oldWeight, System.currentTimeMillis());
        }

        /// Checks if all traffic goes to new version.
        public boolean isAllNew() {
            return oldWeight == 0;
        }

        /// Checks if all traffic goes to old version.
        public boolean isAllOld() {
            return newWeight == 0;
        }
    }

    /// Rolling update state stored in consensus.
    ///
    /// @param updateId unique identifier for this update
    /// @param artifactBase the artifact being updated (version-agnostic)
    /// @param oldVersion current version being replaced
    /// @param newVersion new version being deployed
    /// @param state current state name (stored as string for serialization)
    /// @param newWeight current traffic weight for new version
    /// @param oldWeight current traffic weight for old version
    /// @param newInstances target number of new version instances
    /// @param maxErrorRate health threshold for error rate
    /// @param maxLatencyMs health threshold for latency
    /// @param requireManualApproval whether manual approval is required
    /// @param cleanupPolicy cleanup policy name
    /// @param createdAt timestamp when update was created
    /// @param updatedAt timestamp of last state change
    record RollingUpdateValue(
    String updateId,
    ArtifactBase artifactBase,
    Version oldVersion,
    Version newVersion,
    String state,
    int newWeight,
    int oldWeight,
    int newInstances,
    double maxErrorRate,
    long maxLatencyMs,
    boolean requireManualApproval,
    String cleanupPolicy,
    long createdAt,
    long updatedAt) implements AetherValue {}
}
