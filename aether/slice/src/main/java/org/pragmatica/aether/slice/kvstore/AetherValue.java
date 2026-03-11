package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Option.none;

/// Value type stored in the consensus KVStore
@Codec
@SuppressWarnings("JBCT-NAM-01")
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
                            String placement,
                            long updatedAt) implements AetherValue {
        private static final String DEFAULT_PLACEMENT = "CORE_ONLY";

        /// Compact constructor: normalize null/empty placement at construction time.
        public SliceTargetValue {
            if (placement == null || placement.isEmpty()) {
                placement = DEFAULT_PLACEMENT;
            }
        }

        /// Creates a new slice target value with current timestamp.
        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        /// Creates a standalone slice target (not part of any app blueprint).
        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        /// Creates a standalone slice target with explicit minInstances.
        public static SliceTargetValue sliceTargetValue(Version version, int instances, int minInstances) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        /// Creates a slice target with explicit minInstances and owning blueprint.
        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        /// Creates a standalone slice target with explicit minInstances and placement.
        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        String placement) {
            return new SliceTargetValue(version, instances, minInstances, none(), placement, System.currentTimeMillis());
        }

        /// Returns the effective minimum instances (handles backward-compat where minInstances == 0).
        public int effectiveMinInstances() {
            return Math.max(1, minInstances);
        }

        /// Returns the effective placement (guaranteed non-null by compact constructor).
        public String effectivePlacement() {
            return placement;
        }

        /// Returns a new value with updated instance count, preserving minInstances and placement.
        public SliceTargetValue withInstances(int newCount) {
            return new SliceTargetValue(currentVersion,
                                        newCount,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis());
        }

        /// Returns a new value with updated placement, preserving all other fields.
        public SliceTargetValue withPlacement(String newPlacement) {
            return new SliceTargetValue(currentVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        newPlacement,
                                        System.currentTimeMillis());
        }

        /// Returns a new value with updated version, preserving minInstances and placement.
        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis());
        }
    }

    /// Application blueprint contains the expanded blueprint with full dependency resolution
    record AppBlueprintValue(ExpandedBlueprint blueprint) implements AetherValue {
        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint) {
            return new AppBlueprintValue(blueprint);
        }
    }

    /// Deployment Vector (NodeId/Artifact) contains the current state of the loaded slice.
    ///
    /// @param state the current deployment state
    /// @param failureReason when state is FAILED, carries the cause message; otherwise none
    record SliceNodeValue(SliceState state, Option<String> failureReason, boolean fatal) implements AetherValue {
        public static SliceNodeValue sliceNodeValue(SliceState state) {
            return new SliceNodeValue(state, none(), false);
        }

        /// Creates a FAILED state value by classifying the cause.
        public static SliceNodeValue failedSliceNodeValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new SliceNodeValue(SliceState.FAILED,
                                      Option.option(classified.message()),
                                      classified.isFatal());
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

    /// Per-node HTTP route registration stored in consensus.
    /// Each node publishes its own entry with route-specific metadata.
    /// Consumers reconstruct node sets from flat keys in-memory.
    ///
    /// @param artifactCoord full artifact coordinates (e.g., "com.example:users:1.2.0")
    /// @param sliceMethod factory method name for context
    /// @param state node's route state: ACTIVE, DRAINING, or CANARY
    /// @param weight relative load factor (100 = normal, 0 = don't route)
    /// @param registeredAt epoch millis when this node registered the route
    record HttpNodeRouteValue(String artifactCoord,
                              String sliceMethod,
                              String state,
                              int weight,
                              long registeredAt) implements AetherValue {
        /// Creates a new active route registration with default weight.
        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord, String sliceMethod) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, "ACTIVE", 100, System.currentTimeMillis());
        }

        /// Creates a route registration with explicit state and weight.
        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord,
                                                            String sliceMethod,
                                                            String state,
                                                            int weight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, weight, System.currentTimeMillis());
        }

        /// Returns a new value with updated state.
        public HttpNodeRouteValue withState(String newState) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, newState, weight, registeredAt);
        }

        /// Returns a new value with updated weight.
        public HttpNodeRouteValue withWeight(int newWeight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, newWeight, registeredAt);
        }

        /// Returns true if this route is active and routable.
        public boolean isRoutable() {
            return "ACTIVE".equals(state) && weight > 0;
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

    /// Observability depth configuration stored in consensus.
    /// Stores per-method depth threshold and sampling configuration.
    ///
    /// @param artifactBase the artifact this config applies to (groupId:artifactId, version-agnostic)
    /// @param methodName the method this config applies to
    /// @param depthThreshold depth threshold for SLF4J logging verbosity
    /// @param updatedAt timestamp of last update
    record ObservabilityDepthValue(String artifactBase,
                                   String methodName,
                                   int depthThreshold,
                                   long updatedAt) implements AetherValue {
        /// Creates a new observability depth value with current timestamp.
        public static ObservabilityDepthValue observabilityDepthValue(String artifactBase,
                                                                      String methodName,
                                                                      int depthThreshold) {
            return new ObservabilityDepthValue(artifactBase, methodName, depthThreshold, System.currentTimeMillis());
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

    /// Directive for workers to activate/deactivate slices.
    /// CDM writes this to consensus KV-Store; governors/workers watch for it.
    ///
    /// @param artifact the slice artifact to deploy
    /// @param targetInstances total desired instances across worker pool
    /// @param placement which pool(s) are eligible (matches PlacementPolicy enum name)
    /// @param targetCommunity optional community targeting (empty = all communities)
    /// @param updatedAt timestamp of last update
    record WorkerSliceDirectiveValue(Artifact artifact,
                                     int targetInstances,
                                     String placement,
                                     Option<String> targetCommunity,
                                     long updatedAt) implements AetherValue {
        public static WorkerSliceDirectiveValue workerSliceDirectiveValue(Artifact artifact,
                                                                          int targetInstances,
                                                                          String placement) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 targetInstances,
                                                 placement,
                                                 none(),
                                                 System.currentTimeMillis());
        }

        /// Creates a directive targeting a specific community.
        public static WorkerSliceDirectiveValue workerSliceDirectiveValue(Artifact artifact,
                                                                          int targetInstances,
                                                                          String placement,
                                                                          String targetCommunity) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 targetInstances,
                                                 placement,
                                                 Option.option(targetCommunity),
                                                 System.currentTimeMillis());
        }

        public WorkerSliceDirectiveValue withInstances(int newCount) {
            return new WorkerSliceDirectiveValue(artifact,
                                                 newCount,
                                                 placement,
                                                 targetCommunity,
                                                 System.currentTimeMillis());
        }
    }

    /// Activation role assignment from CDM to joining node.
    /// Written to consensus KV-Store so ALL nodes see the committed directive.
    ///
    /// @param role the assigned role: "CORE" for consensus participant, "WORKER" for passive worker
    record ActivationDirectiveValue(String role) implements AetherValue {
        public static final String CORE = "CORE";
        public static final String WORKER = "WORKER";

        public static ActivationDirectiveValue core() {
            return new ActivationDirectiveValue(CORE);
        }

        public static ActivationDirectiveValue worker() {
            return new ActivationDirectiveValue(WORKER);
        }
    }

    /// Gossip key rotation value stored in consensus.
    /// Contains the current and previous gossip encryption keys for rolling rotation.
    ///
    /// @param currentKeyId  ID of the current gossip key
    /// @param currentKey    base64-encoded current gossip key (32 bytes AES-256)
    /// @param previousKeyId ID of the previous gossip key (0 if none)
    /// @param previousKey   base64-encoded previous gossip key (empty if none)
    /// @param rotatedAt     timestamp when rotation occurred
    record GossipKeyRotationValue(int currentKeyId,
                                  String currentKey,
                                  int previousKeyId,
                                  String previousKey,
                                  long rotatedAt) implements AetherValue {
        /// Creates a new rotation value with current key only (initial state).
        public static GossipKeyRotationValue gossipKeyRotationValue(int currentKeyId, String currentKey) {
            return new GossipKeyRotationValue(currentKeyId, currentKey, 0, "", System.currentTimeMillis());
        }

        /// Creates a rotation value with both current and previous keys (during rotation).
        public static GossipKeyRotationValue gossipKeyRotationValue(int currentKeyId,
                                                                    String currentKey,
                                                                    int previousKeyId,
                                                                    String previousKey) {
            return new GossipKeyRotationValue(currentKeyId,
                                              currentKey,
                                              previousKeyId,
                                              previousKey,
                                              System.currentTimeMillis());
        }

        /// Returns true if a previous key is available for dual-key decryption.
        public boolean hasPreviousKey() {
            return ! previousKey.isEmpty();
        }
    }

    /// Governor announcement value for multi-group worker topology.
    /// Governors write this to consensus so core nodes track active worker communities.
    ///
    /// @param governorId the NodeId of the elected governor
    /// @param memberCount number of workers in this community
    /// @param members list of all member NodeIds in this community
    /// @param tcpAddress TCP address for cross-community governor mesh communication
    /// @param announcedAt timestamp when governor was announced
    record GovernorAnnouncementValue(NodeId governorId,
                                     int memberCount,
                                     List<NodeId> members,
                                     String tcpAddress,
                                     long announcedAt) implements AetherValue {
        /// Creates a new governor announcement with current timestamp (backward-compat, no members).
        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId, int memberCount) {
            return new GovernorAnnouncementValue(governorId, memberCount, List.of(), "", System.currentTimeMillis());
        }

        /// Creates a governor announcement with explicit timestamp (backward-compat, no members).
        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId, memberCount, List.of(), "", announcedAt);
        }

        /// Creates a governor announcement with full member list and TCP address.
        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 List.copyOf(members),
                                                 tcpAddress,
                                                 System.currentTimeMillis());
        }

        /// Returns an updated announcement with new member count.
        public GovernorAnnouncementValue withMemberCount(int newCount) {
            return new GovernorAnnouncementValue(governorId, newCount, members, tcpAddress, System.currentTimeMillis());
        }

        /// Returns an updated announcement with new members and TCP address.
        public GovernorAnnouncementValue withMembers(List<NodeId> newMembers, String newTcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis());
        }
    }

    /// Node lifecycle states for the state machine.
    ///
    /// State machine:
    /// ```
    /// JOINING → ON_DUTY ←→ DRAINING → DECOMMISSIONED → SHUTTING_DOWN
    ///                    ←────────────┘
    ///           any KV state ──────────→ SHUTTING_DOWN
    /// ```
    enum NodeLifecycleState {
        JOINING,
        ON_DUTY,
        DRAINING,
        DECOMMISSIONED,
        SHUTTING_DOWN
    }

    /// Node lifecycle value tracks the current lifecycle state of a cluster node.
    /// Written to KV when node first establishes quorum (ON_DUTY), updated during
    /// drain/decommission/shutdown operations.
    ///
    /// @param state the current lifecycle state
    /// @param updatedAt timestamp of last state transition
    record NodeLifecycleValue(NodeLifecycleState state, long updatedAt) implements AetherValue {
        /// Creates a new lifecycle value with current timestamp.
        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
            return new NodeLifecycleValue(state, System.currentTimeMillis());
        }

        /// Creates a new lifecycle value with explicit timestamp.
        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
            return new NodeLifecycleValue(state, updatedAt);
        }

        /// Returns a new value with updated state and current timestamp.
        public NodeLifecycleValue withState(NodeLifecycleState newState) {
            return new NodeLifecycleValue(newState, System.currentTimeMillis());
        }
    }
}
