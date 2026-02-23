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

import static org.pragmatica.lang.Option.none;

/// Value type stored in the consensus KVStore
@SuppressWarnings({"JBCT-VO-01", "JBCT-NAM-01", "JBCT-STY-04"})
public sealed interface AetherValue {
    /// Slice target stores runtime scaling configuration for a slice.
    /// This is the "desired state" for how many instances should run and which version.
    ///
    /// @param currentVersion the version currently deployed/being deployed
    /// @param targetInstances desired number of instances to run
    /// @param minInstances minimum number of instances (from original blueprint); 0 means use default of 1
    /// @param owningBlueprint if this slice is part of an app blueprint, the blueprint ID; None for standalone
    /// @param updatedAt timestamp of last update
    record SliceTargetValue(Version currentVersion,
                            int targetInstances,
                            int minInstances,
                            Option<BlueprintId> owningBlueprint,
                            long updatedAt) implements AetherValue {
        /// Creates a new slice target value with current timestamp.
        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version, instances, instances, owner, System.currentTimeMillis());
        }

        /// Creates a standalone slice target (not part of any app blueprint).
        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version, instances, instances, none(), System.currentTimeMillis());
        }

        /// Returns the effective minimum instances (handles backward-compat where minInstances == 0).
        public int effectiveMinInstances() {
            return Math.max(1, minInstances);
        }

        /// Returns a new value with updated instance count, preserving minInstances.
        public SliceTargetValue withInstances(int newCount) {
            return new SliceTargetValue(currentVersion,
                                        newCount,
                                        minInstances,
                                        owningBlueprint,
                                        System.currentTimeMillis());
        }

        /// Returns a new value with updated version, preserving minInstances.
        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        System.currentTimeMillis());
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

    /// Topic subscription locator points to node where a subscriber handler is available.
    record TopicSubscriptionValue(NodeId nodeId) implements AetherValue {
        public static TopicSubscriptionValue topicSubscriptionValue(NodeId nodeId) {
            return new TopicSubscriptionValue(nodeId);
        }
    }

    /// Scheduled task configuration stored in consensus.
    /// Stores scheduling parameters for periodic slice method invocation.
    ///
    /// @param registeredBy the node that registered this scheduled task
    /// @param interval fixed-rate interval in TimeSpan format (e.g., "5m", "30s"); empty string if cron mode
    /// @param cron standard 5-field cron expression; empty string if interval mode
    /// @param leaderOnly whether only the leader node triggers this task
    record ScheduledTaskValue(NodeId registeredBy,
                              String interval,
                              String cron,
                              boolean leaderOnly) implements AetherValue {
        /// Creates a scheduled task value with interval-based scheduling.
        public static ScheduledTaskValue intervalTask(NodeId registeredBy, String interval, boolean leaderOnly) {
            return new ScheduledTaskValue(registeredBy, interval, "", leaderOnly);
        }

        /// Creates a scheduled task value with cron-based scheduling.
        public static ScheduledTaskValue cronTask(NodeId registeredBy, String cron, boolean leaderOnly) {
            return new ScheduledTaskValue(registeredBy, "", cron, leaderOnly);
        }

        /// Returns true if this is an interval-based schedule.
        public boolean isInterval() {
            return ! interval.isEmpty();
        }

        /// Returns true if this is a cron-based schedule.
        public boolean isCron() {
            return ! cron.isEmpty();
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

    /// Log level override stored in consensus.
    /// Stores per-logger log level overrides for runtime toggling.
    ///
    /// @param loggerName the logger this override applies to
    /// @param level the log level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF)
    /// @param updatedAt timestamp of last update
    record LogLevelValue(String loggerName,
                         String level,
                         long updatedAt) implements AetherValue {
        /// Creates a new log level value with current timestamp.
        public static LogLevelValue logLevelValue(String loggerName, String level) {
            return new LogLevelValue(loggerName, level, System.currentTimeMillis());
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
