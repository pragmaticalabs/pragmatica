package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.HashSet;
import java.util.Set;

/// Value type stored in the consensus KVStore
public sealed interface AetherValue {
    /// Slice target stores runtime scaling configuration for a slice.
    /// This is the "desired state" for how many instances should run and which version.
    ///
    /// @param currentVersion the version currently deployed/being deployed
    /// @param targetInstances desired number of instances to run
    /// @param owningBlueprint if this slice is part of an app blueprint, the blueprint ID; None for standalone
    /// @param updatedAt timestamp of last update
    record SliceTargetValue(Version currentVersion,
                            int targetInstances,
                            Option<BlueprintId> owningBlueprint,
                            long updatedAt) implements AetherValue {
        /// Creates a new slice target value with current timestamp.
        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version, instances, owner, System.currentTimeMillis());
        }

        /// Creates a standalone slice target (not part of any app blueprint).
        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version, instances, Option.none(), System.currentTimeMillis());
        }

        /// Returns a new value with updated instance count.
        public SliceTargetValue withInstances(int newCount) {
            return new SliceTargetValue(currentVersion, newCount, owningBlueprint, System.currentTimeMillis());
        }

        /// Returns a new value with updated version.
        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion, targetInstances, owningBlueprint, System.currentTimeMillis());
        }
    }

    /// Application blueprint contains the expanded blueprint with full dependency resolution
    record AppBlueprintValue(ExpandedBlueprint blueprint) implements AetherValue {
        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint) {
            return new AppBlueprintValue(blueprint);
        }
    }

    /// Deployment Vector (NodeId/Artifact) contains the current state of the loaded slice
    record SliceNodeValue(SliceState state) implements AetherValue {
        public static SliceNodeValue sliceNodeValue(SliceState state) {
            return new SliceNodeValue(state);
        }
    }

    /// Endpoint locator points to node where endpoint is available
    record EndpointValue(NodeId nodeId) implements AetherValue {
        public static EndpointValue endpointValue(NodeId nodeId) {
            return new EndpointValue(nodeId);
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
    record VersionRoutingValue(Version oldVersion,
                               Version newVersion,
                               int newWeight,
                               int oldWeight,
                               long updatedAt) implements AetherValue {
        /// Creates initial routing with all traffic to old version.
        public static VersionRoutingValue versionRoutingValue(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 0, 1, System.currentTimeMillis());
        }

        /// Creates routing with all traffic to new version.
        public static VersionRoutingValue versionRoutingValueAllNew(Version oldVersion, Version newVersion) {
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
    record RollingUpdateValue(String updateId,
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
                              long updatedAt) implements AetherValue {
        public static RollingUpdateValue rollingUpdateValue(String updateId,
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
                                                            long updatedAt) {
            return new RollingUpdateValue(updateId,
                                          artifactBase,
                                          oldVersion,
                                          newVersion,
                                          state,
                                          newWeight,
                                          oldWeight,
                                          newInstances,
                                          maxErrorRate,
                                          maxLatencyMs,
                                          requireManualApproval,
                                          cleanupPolicy,
                                          createdAt,
                                          updatedAt);
        }
    }

    /// Previous version tracking for rollback support.
    /// Stores the previous version of an artifact before a deployment update.
    ///
    /// @param artifactBase the artifact being tracked (version-agnostic)
    /// @param previousVersion the version that was replaced
    /// @param currentVersion the version that replaced it
    /// @param updatedAt timestamp when the version changed
    record PreviousVersionValue(ArtifactBase artifactBase,
                                Version previousVersion,
                                Version currentVersion,
                                long updatedAt) implements AetherValue {
        /// Creates a new previous version value with current timestamp.
        public static PreviousVersionValue previousVersionValue(ArtifactBase artifactBase,
                                                                Version previousVersion,
                                                                Version currentVersion) {
            return new PreviousVersionValue(artifactBase, previousVersion, currentVersion, System.currentTimeMillis());
        }
    }

    /// HTTP route mapping stored in consensus.
    /// Tracks which nodes have registered a particular HTTP route.
    ///
    /// @param nodes set of node IDs that have this route available
    record HttpRouteValue(Set<NodeId> nodes) implements AetherValue {
        /// Creates HTTP route value with given nodes (immutable copy).
        public static HttpRouteValue httpRouteValue(Set<NodeId> nodes) {
            return new HttpRouteValue(Set.copyOf(nodes));
        }

        /// Returns a new value with the given node added.
        public HttpRouteValue withNode(NodeId nodeId) {
            var updated = new HashSet<>(nodes);
            updated.add(nodeId);
            return new HttpRouteValue(Set.copyOf(updated));
        }

        /// Returns a new value with the given node removed.
        public HttpRouteValue withoutNode(NodeId nodeId) {
            var updated = new HashSet<>(nodes);
            updated.remove(nodeId);
            return new HttpRouteValue(Set.copyOf(updated));
        }

        /// Checks if no nodes have this route.
        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    /// Alert threshold configuration stored in consensus.
    /// Allows thresholds to survive restarts and sync across cluster nodes.
    ///
    /// @param metricName the metric this threshold applies to
    /// @param warningThreshold value at which a warning is triggered
    /// @param criticalThreshold value at which a critical alert is triggered
    /// @param updatedAt timestamp of last update
    record AlertThresholdValue(String metricName,
                               double warningThreshold,
                               double criticalThreshold,
                               long updatedAt) implements AetherValue {
        /// Creates a new threshold value with current timestamp.
        public static AlertThresholdValue alertThresholdValue(String metricName, double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }

        /// Updates the threshold values with current timestamp.
        public AlertThresholdValue withThresholds(double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }
    }

    /// Dynamic aspect configuration stored in consensus.
    /// Stores per-method aspect mode (logging/metrics) for runtime toggling.
    ///
    /// @param artifactBase the artifact this aspect applies to (groupId:artifactId, version-agnostic)
    /// @param methodName the method this aspect applies to
    /// @param mode the aspect mode (NONE, LOG, METRICS, LOG_AND_METRICS)
    /// @param updatedAt timestamp of last update
    record DynamicAspectValue(String artifactBase,
                              String methodName,
                              String mode,
                              long updatedAt) implements AetherValue {
        /// Creates a new dynamic aspect value with current timestamp.
        public static DynamicAspectValue dynamicAspectValue(String artifactBase, String methodName, String mode) {
            return new DynamicAspectValue(artifactBase, methodName, mode, System.currentTimeMillis());
        }
    }

    /// Dynamic configuration value stored in consensus.
    /// Stores a single configuration key-value pair with timestamp.
    ///
    /// @param key the configuration key (dot.notation)
    /// @param value the configuration value
    /// @param updatedAt timestamp of last update
    record ConfigValue(String key, String value, long updatedAt) implements AetherValue {
        /// Creates a new config value with current timestamp.
        public static ConfigValue configValue(String key, String value) {
            return new ConfigValue(key, value, System.currentTimeMillis());
        }
    }
}
