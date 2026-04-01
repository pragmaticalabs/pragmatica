package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Option.none;

/// Value type stored in the consensus KVStore
@Codec
@CodecFor(ExecutionMode.class)
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
            if ( placement == null || placement.isEmpty()) {
            placement = DEFAULT_PLACEMENT;}
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
    /// @param executionMode how the task fires across the cluster (SINGLE or ALL)
    record ScheduledTaskValue(NodeId registeredBy,
                              String interval,
                              String cron,
                              ExecutionMode executionMode,
                              boolean paused) implements AetherValue {
        /// Creates a scheduled task value with interval-based scheduling.
        public static ScheduledTaskValue intervalTask(NodeId registeredBy,
                                                      String interval,
                                                      ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, interval, "", executionMode, false);
        }

        /// Creates a scheduled task value with cron-based scheduling.
        public static ScheduledTaskValue cronTask(NodeId registeredBy, String cron, ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, "", cron, executionMode, false);
        }

        /// Returns a copy with the paused state changed.
        public ScheduledTaskValue withPaused(boolean paused) {
            return new ScheduledTaskValue(registeredBy, interval, cron, executionMode, paused);
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

    /// Execution state for a scheduled task.
    /// Tracks last execution time, next fire time, and failure statistics.
    ///
    /// @param lastExecutionAt epoch millis of last execution (0 if never run)
    /// @param nextFireAt epoch millis of next scheduled fire (0 if unknown)
    /// @param consecutiveFailures count of consecutive failures (reset on success)
    /// @param totalExecutions total number of executions
    /// @param lastFailureMessage message from the last failure (empty if none)
    /// @param updatedAt timestamp of last state update
    record ScheduledTaskStateValue(long lastExecutionAt,
                                   long nextFireAt,
                                   int consecutiveFailures,
                                   int totalExecutions,
                                   String lastFailureMessage,
                                   long updatedAt) implements AetherValue {
        /// Creates a state value for a successful execution.
        public static ScheduledTaskStateValue successState(long nextFireAt, int totalExecutions) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               0,
                                               totalExecutions,
                                               "",
                                               System.currentTimeMillis());
        }

        /// Creates a state value for a failed execution.
        public static ScheduledTaskStateValue failureState(long nextFireAt,
                                                           int consecutiveFailures,
                                                           int totalExecutions,
                                                           String failureMessage) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               consecutiveFailures,
                                               totalExecutions,
                                               failureMessage,
                                               System.currentTimeMillis());
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

    /// Persisted unified deployment state for blueprint-level deployments.
    ///
    /// @param deploymentId unique identifier for this deployment
    /// @param blueprintId blueprint identifier (e.g., "org.example:my-app:1.0")
    /// @param oldVersion version being replaced (as string)
    /// @param newVersion version being deployed (as string)
    /// @param strategy deployment strategy name (CANARY, BLUE_GREEN, ROLLING)
    /// @param state current deployment state name
    /// @param routing traffic routing in "new:old" format
    /// @param strategyConfig JSON string of strategy-specific configuration
    /// @param thresholds JSON string of health thresholds
    /// @param cleanupPolicy cleanup policy name
    /// @param artifacts comma-separated artifact base strings
    /// @param newInstances target instance count for new version
    /// @param createdAt timestamp when deployment was created
    /// @param updatedAt timestamp of last state change
    record DeploymentValue(String deploymentId,
                           String blueprintId,
                           String oldVersion,
                           String newVersion,
                           String strategy,
                           String state,
                           String routing,
                           String strategyConfig,
                           String thresholds,
                           String cleanupPolicy,
                           String artifacts,
                           int newInstances,
                           long createdAt,
                           long updatedAt) implements AetherValue {
        public static DeploymentValue deploymentValue(String deploymentId,
                                                      String blueprintId,
                                                      String oldVersion,
                                                      String newVersion,
                                                      String strategy,
                                                      String state,
                                                      String routing,
                                                      String strategyConfig,
                                                      String thresholds,
                                                      String cleanupPolicy,
                                                      String artifacts,
                                                      int newInstances,
                                                      long createdAt,
                                                      long updatedAt) {
            return new DeploymentValue(deploymentId,
                                       blueprintId,
                                       oldVersion,
                                       newVersion,
                                       strategy,
                                       state,
                                       routing,
                                       strategyConfig,
                                       thresholds,
                                       cleanupPolicy,
                                       artifacts,
                                       newInstances,
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
    @Codec enum NodeLifecycleState {
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
    /// @param host the node's cluster address host (empty string for backward compatibility)
    /// @param port the node's cluster address port (0 for backward compatibility)
    record NodeLifecycleValue(NodeLifecycleState state, long updatedAt, String host, int port) implements AetherValue {
        /// Compact constructor: normalize null host for backward compatibility with old serialization.
        public NodeLifecycleValue {
            if ( host == null) {
            host = "";}
        }

        /// Creates a new lifecycle value with current timestamp (no address).
        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
            return new NodeLifecycleValue(state, System.currentTimeMillis(), "", 0);
        }

        /// Creates a new lifecycle value with explicit timestamp (no address).
        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
            return new NodeLifecycleValue(state, updatedAt, "", 0);
        }

        /// Creates a new lifecycle value with current timestamp and address.
        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, String host, int port) {
            return new NodeLifecycleValue(state, System.currentTimeMillis(), host, port);
        }

        /// Returns true if this value carries a valid cluster address.
        public boolean hasAddress() {
            return ! host.isEmpty() && port > 0;
        }

        /// Returns a new value with updated state and current timestamp, preserving address.
        public NodeLifecycleValue withState(NodeLifecycleState newState) {
            return new NodeLifecycleValue(newState, System.currentTimeMillis(), host, port);
        }
    }

    /// Compound deployment state and endpoint registration for a single node-artifact pair.
    /// Carries both deployment state (from SliceNodeValue) and endpoint info (from EndpointValue).
    ///
    /// @param state the current deployment state
    /// @param failureReason when state is FAILED, carries the cause message; otherwise none
    /// @param fatal whether the failure is fatal (non-retryable)
    /// @param instanceNumber hash-based instance number for endpoint routing (0 when not ACTIVE)
    /// @param methods list of method names available on this slice (empty when not ACTIVE)
    record NodeArtifactValue(SliceState state,
                             Option<String> failureReason,
                             boolean fatal,
                             int instanceNumber,
                             List<String> methods) implements AetherValue {
        /// Creates a state-only value (no endpoints — used during LOAD/LOADING/LOADED transitions).
        public static NodeArtifactValue nodeArtifactValue(SliceState state) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of());
        }

        /// Creates a FAILED state value by classifying the cause.
        public static NodeArtifactValue failedNodeArtifactValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new NodeArtifactValue(SliceState.FAILED,
                                         Option.option(classified.message()),
                                         classified.isFatal(),
                                         0,
                                         List.of());
        }

        /// Creates an ACTIVE value with endpoint information.
        public static NodeArtifactValue activeNodeArtifactValue(int instanceNumber, List<String> methods) {
            return new NodeArtifactValue(SliceState.ACTIVE, Option.none(), false, instanceNumber, List.copyOf(methods));
        }

        /// Returns a new value with updated state, preserving endpoint info if transitioning to ACTIVE.
        public NodeArtifactValue withState(SliceState newState) {
            if ( newState == SliceState.ACTIVE) {
            return new NodeArtifactValue(newState, Option.none(), false, instanceNumber, methods);}
            return new NodeArtifactValue(newState, Option.none(), false, 0, List.of());
        }

        /// Returns true if this entry has active endpoints.
        public boolean hasEndpoints() {
            return state == SliceState.ACTIVE && !methods.isEmpty();
        }
    }

    /// Compound HTTP routes for one artifact on one node.
    /// One entry per artifact per node replaces N individual route entries.
    ///
    /// @param routes list of route registrations
    record NodeRoutesValue(List<RouteEntry> routes) implements AetherValue {
        /// Single HTTP route entry within the compound value.
        ///
        /// @param httpMethod HTTP method (GET, POST, etc.)
        /// @param pathPrefix path prefix for routing
        /// @param sliceMethod factory method name on the slice
        /// @param state route state: ACTIVE, DRAINING, or CANARY
        /// @param weight relative load factor (100 = normal, 0 = don't route)
        /// @param registeredAt epoch millis when this route was registered
        /// @param security security policy string (PUBLIC, AUTHENTICATED, API_KEY, BEARER_TOKEN, ROLE:name)
        public record RouteEntry(String httpMethod,
                                 String pathPrefix,
                                 String sliceMethod,
                                 String state,
                                 int weight,
                                 long registeredAt,
                                 String security) {
            /// Creates an active route entry with default weight and explicit security.
            public static RouteEntry activeRoute(String httpMethod,
                                                 String pathPrefix,
                                                 String sliceMethod,
                                                 String security) {
                return new RouteEntry(httpMethod,
                                      pathPrefix,
                                      sliceMethod,
                                      "ACTIVE",
                                      100,
                                      System.currentTimeMillis(),
                                      security);
            }

            /// Creates an active route entry with default weight and PUBLIC security (backward compat).
            public static RouteEntry activeRoute(String httpMethod, String pathPrefix, String sliceMethod) {
                return activeRoute(httpMethod, pathPrefix, sliceMethod, "PUBLIC");
            }

            /// Returns true if this route is active and routable.
            public boolean isRoutable() {
                return "ACTIVE".equals(state) && weight > 0;
            }
        }

        /// Creates an empty routes value.
        public static NodeRoutesValue empty() {
            return new NodeRoutesValue(List.of());
        }

        /// Creates a routes value from a list of entries.
        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes) {
            return new NodeRoutesValue(List.copyOf(routes));
        }
    }

    /// Blueprint resources configuration stored in consensus.
    ///
    /// @param tomlContent raw TOML content from resources.toml
    record BlueprintResourcesValue(String tomlContent) implements AetherValue {
        public static BlueprintResourcesValue blueprintResourcesValue(String tomlContent) {
            return new BlueprintResourcesValue(tomlContent);
        }
    }

    /// Schema version tracking for a datasource.
    ///
    /// @param datasourceName the datasource this tracks
    /// @param currentVersion current applied schema version number
    /// @param lastMigration filename of the last applied migration
    /// @param status current migration status
    /// @param artifactCoords Maven coordinates of the artifact that defined this schema
    /// @param attemptCount number of migration attempts (for retry tracking)
    /// @param updatedAt timestamp of last update
    record SchemaVersionValue(String datasourceName,
                              int currentVersion,
                              String lastMigration,
                              SchemaStatus status,
                              String artifactCoords,
                              int attemptCount,
                              long updatedAt) implements AetherValue {
        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          0,
                                          System.currentTimeMillis());
        }

        /// Factory with explicit attempt count for retry tracking.
        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords,
                                                            int attemptCount) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          attemptCount,
                                          System.currentTimeMillis());
        }

        /// Backward-compatible factory without artifact coordinates.
        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          "",
                                          0,
                                          System.currentTimeMillis());
        }
    }

    /// Schema migration lock for distributed coordination.
    ///
    /// @param datasourceName the datasource being migrated
    /// @param heldBy NodeId of the node holding the lock
    /// @param acquiredAt timestamp when lock was acquired
    /// @param expiresAt timestamp when lock expires (prevents deadlocks)
    record SchemaMigrationLockValue(String datasourceName,
                                    NodeId heldBy,
                                    long acquiredAt,
                                    long expiresAt) implements AetherValue {
        public static SchemaMigrationLockValue schemaMigrationLockValue(String datasourceName,
                                                                        NodeId heldBy,
                                                                        long ttlMs) {
            var now = System.currentTimeMillis();
            return new SchemaMigrationLockValue(datasourceName, heldBy, now, now + ttlMs);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /// Schema migration status.
    @Codec enum SchemaStatus {
        PENDING,
        MIGRATING,
        COMPLETED,
        FAILED
    }

    /// Persisted A/B test deployment state.
    ///
    /// @param testId unique identifier for this A/B test
    /// @param artifactBase the artifact being tested (version-agnostic)
    /// @param baselineVersion current baseline version
    /// @param variantVersionsJson serialized variant name to version mapping
    /// @param state current state name (stored as string for serialization)
    /// @param splitRuleJson serialized split rule configuration
    /// @param newWeight current traffic weight for new versions
    /// @param oldWeight current traffic weight for baseline
    /// @param blueprintId optional blueprint identifier
    /// @param createdAt timestamp when test was created
    /// @param updatedAt timestamp of last state change
    record AbTestValue(String testId,
                       ArtifactBase artifactBase,
                       Version baselineVersion,
                       String variantVersionsJson,
                       String state,
                       String splitRuleJson,
                       int newWeight,
                       int oldWeight,
                       String blueprintId,
                       long createdAt,
                       long updatedAt) implements AetherValue {
        public static AbTestValue abTestValue(String testId,
                                              ArtifactBase artifactBase,
                                              Version baselineVersion,
                                              String variantVersionsJson,
                                              String state,
                                              String splitRuleJson,
                                              int newWeight,
                                              int oldWeight,
                                              String blueprintId,
                                              long createdAt,
                                              long updatedAt) {
            return new AbTestValue(testId,
                                   artifactBase,
                                   baselineVersion,
                                   variantVersionsJson,
                                   state,
                                   splitRuleJson,
                                   newWeight,
                                   oldWeight,
                                   blueprintId,
                                   createdAt,
                                   updatedAt);
        }
    }

    /// A/B test routing configuration stored in consensus.
    /// Stores the split rule and variant versions for traffic routing decisions.
    ///
    /// @param testId the A/B test this routing belongs to
    /// @param splitRuleJson serialized split rule configuration
    /// @param variantVersionsJson serialized variant name to version mapping
    record AbTestRoutingValue(String testId,
                              String splitRuleJson,
                              String variantVersionsJson) implements AetherValue {
        public static AbTestRoutingValue abTestRoutingValue(String testId,
                                                            String splitRuleJson,
                                                            String variantVersionsJson) {
            return new AbTestRoutingValue(testId, splitRuleJson, variantVersionsJson);
        }
    }

    /// Stream metadata stored in consensus.
    /// Contains stream configuration replicated across the cluster.
    ///
    /// @param streamName the stream identifier
    /// @param partitionCount number of partitions
    /// @param retention retention mode: "time", "count", or "size"
    /// @param retentionValue value interpreted by retention mode
    /// @param maxEventSize maximum serialized event size
    /// @param backpressure behavior when ring buffer is full: "block", "drop-oldest", "reject"
    /// @param owningBlueprint blueprint that declared this stream
    /// @param createdAt timestamp when stream was created
    record StreamMetadataValue(String streamName,
                               int partitionCount,
                               String retention,
                               String retentionValue,
                               String maxEventSize,
                               String backpressure,
                               String owningBlueprint,
                               long createdAt) implements AetherValue {
        public static StreamMetadataValue streamMetadataValue(String streamName,
                                                              int partitionCount,
                                                              String retention,
                                                              String retentionValue,
                                                              String maxEventSize,
                                                              String backpressure,
                                                              String owningBlueprint) {
            return new StreamMetadataValue(streamName,
                                           partitionCount,
                                           retention,
                                           retentionValue,
                                           maxEventSize,
                                           backpressure,
                                           owningBlueprint,
                                           System.currentTimeMillis());
        }
    }

    /// Stream partition assignment for a consumer group.
    /// Maps partitions to consumer nodes.
    ///
    /// @param assignments list of partition-to-node assignments
    /// @param updatedAt timestamp of last assignment update
    record StreamPartitionAssignmentValue(List<PartitionAssignment> assignments,
                                          long updatedAt) implements AetherValue {
        /// Single partition-to-node assignment.
        public record PartitionAssignment(int partition, NodeId consumerNode) {
            public static PartitionAssignment partitionAssignment(int partition, NodeId consumerNode) {
                return new PartitionAssignment(partition, consumerNode);
            }
        }

        public static StreamPartitionAssignmentValue streamPartitionAssignmentValue(List<PartitionAssignment> assignments) {
            return new StreamPartitionAssignmentValue(List.copyOf(assignments), System.currentTimeMillis());
        }
    }

    /// Stream cursor checkpoint for a consumer group on a specific partition.
    /// Periodically persisted to consensus for crash recovery.
    ///
    /// @param committedOffset the last successfully processed offset
    /// @param commitTimestamp timestamp of the commit
    record StreamCursorCheckpointValue(long committedOffset,
                                       long commitTimestamp) implements AetherValue {
        public static StreamCursorCheckpointValue streamCursorCheckpointValue(long committedOffset) {
            return new StreamCursorCheckpointValue(committedOffset, System.currentTimeMillis());
        }
    }

    /// Stream registration linking a stream subscription to a slice method handler.
    ///
    /// @param nodeId the node hosting this consumer
    /// @param consumerGroup the consumer group name
    /// @param batchMode whether this consumer processes events in batches
    /// @param eventType fully qualified event type name
    record StreamRegistrationValue(NodeId nodeId,
                                   String consumerGroup,
                                   boolean batchMode,
                                   String eventType) implements AetherValue {
        public static StreamRegistrationValue streamRegistrationValue(NodeId nodeId,
                                                                      String consumerGroup,
                                                                      boolean batchMode,
                                                                      String eventType) {
            return new StreamRegistrationValue(nodeId, consumerGroup, batchMode, eventType);
        }
    }

    /// Canonical cluster configuration stored in consensus KV-Store.
    ///
    /// The TOML content is stored as a string for human readability and
    /// round-trip fidelity (comments are stripped, but field ordering is preserved).
    /// Structured fields are extracted for quick access without parsing.
    ///
    /// @param tomlContent full TOML string (comments stripped)
    /// @param clusterName extracted from [cluster].name
    /// @param version extracted from [cluster].version
    /// @param coreCount extracted from [cluster.core].count
    /// @param coreMin extracted from [cluster.core].min
    /// @param coreMax extracted from [cluster.core].max
    /// @param deploymentType extracted from [deployment].type
    /// @param configVersion monotonically increasing version for optimistic concurrency
    /// @param updatedAt epoch millis
    /// Storage block lifecycle value — tracks tier presence, reference count, and access timestamps.
    /// Tier names are stored as strings to avoid coupling to the storage module's TierLevel enum.
    ///
    /// @param blockIdHex hex-encoded SHA-256 block ID
    /// @param presentIn tier level names where the block is stored (e.g., "MEMORY", "LOCAL_DISK")
    /// @param refCount number of named references pointing to this block
    /// @param lastAccessedAt timestamp of last read access
    /// @param createdAt timestamp when first stored
    /// @param accessCount total number of read accesses (for frequency-based eviction)
    record StorageBlockValue(String blockIdHex,
                             Set<String> presentIn,
                             int refCount,
                             long lastAccessedAt,
                             long createdAt,
                             int accessCount) implements AetherValue {
        public static StorageBlockValue storageBlockValue(String blockIdHex,
                                                          Set<String> presentIn,
                                                          int refCount,
                                                          long lastAccessedAt,
                                                          long createdAt,
                                                          int accessCount) {
            return new StorageBlockValue(blockIdHex,
                                         Set.copyOf(presentIn),
                                         refCount,
                                         lastAccessedAt,
                                         createdAt,
                                         accessCount);
        }

        public StorageBlockValue withTierAdded(String tier) {
            var tiers = new HashSet<>(presentIn);
            tiers.add(tier);
            return new StorageBlockValue(blockIdHex, Set.copyOf(tiers), refCount, lastAccessedAt, createdAt, accessCount);
        }

        public StorageBlockValue withRefCountIncremented() {
            return new StorageBlockValue(blockIdHex, presentIn, refCount + 1, lastAccessedAt, createdAt, accessCount);
        }

        public StorageBlockValue withRefCountDecremented() {
            return new StorageBlockValue(blockIdHex,
                                         presentIn,
                                         Math.max(0, refCount - 1),
                                         lastAccessedAt,
                                         createdAt,
                                         accessCount);
        }

        public StorageBlockValue withAccessTimestamp() {
            return new StorageBlockValue(blockIdHex,
                                         presentIn,
                                         refCount,
                                         System.currentTimeMillis(),
                                         createdAt,
                                         accessCount + 1);
        }
    }

    /// Storage named reference value — maps a reference name to a block ID.
    ///
    /// @param blockIdHex hex-encoded SHA-256 block ID this reference points to
    /// @param updatedAt timestamp of last update
    record StorageRefValue(String blockIdHex, long updatedAt) implements AetherValue {
        public static StorageRefValue storageRefValue(String blockIdHex) {
            return new StorageRefValue(blockIdHex, System.currentTimeMillis());
        }
    }

    /// Per-node storage instance status — tier utilization, readiness, snapshot epoch.
    /// Each node publishes this for each storage instance; cluster routes aggregate across nodes.
    ///
    /// @param instanceName storage instance name
    /// @param tiers tier utilization details
    /// @param readinessState current readiness state name
    /// @param isReadReady whether reads are available
    /// @param isWriteReady whether writes are available
    /// @param lastSnapshotEpoch epoch number of the last snapshot
    /// @param lastSnapshotTimestamp epoch millis of the last snapshot
    /// @param updatedAt timestamp of last update
    record StorageStatusValue(String instanceName,
                              List<TierStatus> tiers,
                              String readinessState,
                              boolean isReadReady,
                              boolean isWriteReady,
                              long lastSnapshotEpoch,
                              long lastSnapshotTimestamp,
                              long updatedAt) implements AetherValue {
        /// Tier utilization detail within a storage status.
        ///
        /// @param level tier level name
        /// @param usedBytes bytes currently used
        /// @param maxBytes maximum capacity in bytes
        public record TierStatus(String level, long usedBytes, long maxBytes) {
            public static TierStatus tierStatus(String level, long usedBytes, long maxBytes) {
                return new TierStatus(level, usedBytes, maxBytes);
            }
        }

        /// Creates a storage status value with current timestamp.
        public static StorageStatusValue storageStatusValue(String instanceName,
                                                            List<TierStatus> tiers,
                                                            String readinessState,
                                                            boolean isReadReady,
                                                            boolean isWriteReady,
                                                            long lastSnapshotEpoch,
                                                            long lastSnapshotTimestamp) {
            return new StorageStatusValue(instanceName,
                                          List.copyOf(tiers),
                                          readinessState,
                                          isReadReady,
                                          isWriteReady,
                                          lastSnapshotEpoch,
                                          lastSnapshotTimestamp,
                                          System.currentTimeMillis());
        }
    }

    record ClusterConfigValue(String tomlContent,
                              String clusterName,
                              String version,
                              int coreCount,
                              int coreMin,
                              int coreMax,
                              String deploymentType,
                              long configVersion,
                              long updatedAt) implements AetherValue {
        /// Factory method with current timestamp.
        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            int coreCount,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion) {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          coreCount,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          System.currentTimeMillis());
        }

        /// Factory method with explicit timestamp.
        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            int coreCount,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion,
                                                            long updatedAt) {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          coreCount,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          updatedAt);
        }

        /// Returns a copy with incremented config version and updated timestamp.
        public ClusterConfigValue withIncrementedVersion() {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          coreCount,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion + 1,
                                          System.currentTimeMillis());
        }
    }
}
