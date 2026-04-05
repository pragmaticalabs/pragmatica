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
@Codec@CodecFor(ExecutionMode.class) @SuppressWarnings("JBCT-NAM-01") public sealed interface AetherValue {
    record SliceTargetValue(Version currentVersion,
                            int targetInstances,
                            int minInstances,
                            Option<BlueprintId> owningBlueprint,
                            String placement,
                            long updatedAt) implements AetherValue {
        private static final String DEFAULT_PLACEMENT = "CORE_ONLY";

        public SliceTargetValue {
            if (placement == null || placement.isEmpty()) {placement = DEFAULT_PLACEMENT;}
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances, int minInstances) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis());
        }

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

        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        String placement) {
            return new SliceTargetValue(version, instances, minInstances, none(), placement, System.currentTimeMillis());
        }

        public int effectiveMinInstances() {
            return Math.max(1, minInstances);
        }

        public String effectivePlacement() {
            return placement;
        }

        public SliceTargetValue withInstances(int newCount) {
            return new SliceTargetValue(currentVersion,
                                        newCount,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis());
        }

        public SliceTargetValue withPlacement(String newPlacement) {
            return new SliceTargetValue(currentVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        newPlacement,
                                        System.currentTimeMillis());
        }

        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis());
        }
    }

    record AppBlueprintValue(ExpandedBlueprint blueprint) implements AetherValue {
        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint) {
            return new AppBlueprintValue(blueprint);
        }
    }

    record SliceNodeValue(SliceState state, Option<String> failureReason, boolean fatal) implements AetherValue {
        public static SliceNodeValue sliceNodeValue(SliceState state) {
            return new SliceNodeValue(state, none(), false);
        }

        public static SliceNodeValue failedSliceNodeValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new SliceNodeValue(SliceState.FAILED,
                                      Option.option(classified.message()),
                                      classified.isFatal());
        }
    }

    record EndpointValue(NodeId nodeId) implements AetherValue {
        public static EndpointValue endpointValue(NodeId nodeId) {
            return new EndpointValue(nodeId);
        }
    }

    record TopicSubscriptionValue(NodeId nodeId) implements AetherValue {
        public static TopicSubscriptionValue topicSubscriptionValue(NodeId nodeId) {
            return new TopicSubscriptionValue(nodeId);
        }
    }

    record ScheduledTaskValue(NodeId registeredBy,
                              String interval,
                              String cron,
                              ExecutionMode executionMode,
                              boolean paused) implements AetherValue {
        public static ScheduledTaskValue intervalTask(NodeId registeredBy,
                                                      String interval,
                                                      ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, interval, "", executionMode, false);
        }

        public static ScheduledTaskValue cronTask(NodeId registeredBy, String cron, ExecutionMode executionMode) {
            return new ScheduledTaskValue(registeredBy, "", cron, executionMode, false);
        }

        public ScheduledTaskValue withPaused(boolean paused) {
            return new ScheduledTaskValue(registeredBy, interval, cron, executionMode, paused);
        }

        public boolean isInterval() {
            return ! interval.isEmpty();
        }

        public boolean isCron() {
            return ! cron.isEmpty();
        }
    }

    record ScheduledTaskStateValue(long lastExecutionAt,
                                   long nextFireAt,
                                   int consecutiveFailures,
                                   int totalExecutions,
                                   String lastFailureMessage,
                                   long updatedAt) implements AetherValue {
        public static ScheduledTaskStateValue successState(long nextFireAt, int totalExecutions) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               0,
                                               totalExecutions,
                                               "",
                                               System.currentTimeMillis());
        }

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

    record VersionRoutingValue(Version oldVersion, Version newVersion, int newWeight, int oldWeight, long updatedAt) implements AetherValue {
        public static VersionRoutingValue versionRoutingValue(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 0, 1, System.currentTimeMillis());
        }

        public static VersionRoutingValue versionRoutingValueAllNew(Version oldVersion, Version newVersion) {
            return new VersionRoutingValue(oldVersion, newVersion, 1, 0, System.currentTimeMillis());
        }

        public VersionRoutingValue withRouting(int newWeight, int oldWeight) {
            return new VersionRoutingValue(oldVersion, newVersion, newWeight, oldWeight, System.currentTimeMillis());
        }

        public boolean isAllNew() {
            return oldWeight == 0;
        }

        public boolean isAllOld() {
            return newWeight == 0;
        }
    }

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

    record PreviousVersionValue(ArtifactBase artifactBase,
                                Version previousVersion,
                                Version currentVersion,
                                long updatedAt) implements AetherValue {
        public static PreviousVersionValue previousVersionValue(ArtifactBase artifactBase,
                                                                Version previousVersion,
                                                                Version currentVersion) {
            return new PreviousVersionValue(artifactBase, previousVersion, currentVersion, System.currentTimeMillis());
        }
    }

    record HttpNodeRouteValue(String artifactCoord, String sliceMethod, String state, int weight, long registeredAt) implements AetherValue {
        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord, String sliceMethod) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, "ACTIVE", 100, System.currentTimeMillis());
        }

        public static HttpNodeRouteValue httpNodeRouteValue(String artifactCoord,
                                                            String sliceMethod,
                                                            String state,
                                                            int weight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, weight, System.currentTimeMillis());
        }

        public HttpNodeRouteValue withState(String newState) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, newState, weight, registeredAt);
        }

        public HttpNodeRouteValue withWeight(int newWeight) {
            return new HttpNodeRouteValue(artifactCoord, sliceMethod, state, newWeight, registeredAt);
        }

        public boolean isRoutable() {
            return "ACTIVE".equals(state) && weight > 0;
        }
    }

    record AlertThresholdValue(String metricName, double warningThreshold, double criticalThreshold, long updatedAt) implements AetherValue {
        public static AlertThresholdValue alertThresholdValue(String metricName, double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }

        public AlertThresholdValue withThresholds(double warning, double critical) {
            return new AlertThresholdValue(metricName, warning, critical, System.currentTimeMillis());
        }
    }

    record LogLevelValue(String loggerName, String level, long updatedAt) implements AetherValue {
        public static LogLevelValue logLevelValue(String loggerName, String level) {
            return new LogLevelValue(loggerName, level, System.currentTimeMillis());
        }
    }

    record ObservabilityDepthValue(String artifactBase, String methodName, int depthThreshold, long updatedAt) implements AetherValue {
        public static ObservabilityDepthValue observabilityDepthValue(String artifactBase,
                                                                      String methodName,
                                                                      int depthThreshold) {
            return new ObservabilityDepthValue(artifactBase, methodName, depthThreshold, System.currentTimeMillis());
        }
    }

    record ConfigValue(String key, String value, long updatedAt) implements AetherValue {
        public static ConfigValue configValue(String key, String value) {
            return new ConfigValue(key, value, System.currentTimeMillis());
        }
    }

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

    record GossipKeyRotationValue(int currentKeyId,
                                  String currentKey,
                                  int previousKeyId,
                                  String previousKey,
                                  long rotatedAt) implements AetherValue {
        public static GossipKeyRotationValue gossipKeyRotationValue(int currentKeyId, String currentKey) {
            return new GossipKeyRotationValue(currentKeyId, currentKey, 0, "", System.currentTimeMillis());
        }

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

        public boolean hasPreviousKey() {
            return ! previousKey.isEmpty();
        }
    }

    record GovernorAnnouncementValue(NodeId governorId,
                                     int memberCount,
                                     List<NodeId> members,
                                     String tcpAddress,
                                     long announcedAt) implements AetherValue {
        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId, int memberCount) {
            return new GovernorAnnouncementValue(governorId, memberCount, List.of(), "", System.currentTimeMillis());
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId, memberCount, List.of(), "", announcedAt);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 List.copyOf(members),
                                                 tcpAddress,
                                                 System.currentTimeMillis());
        }

        public GovernorAnnouncementValue withMemberCount(int newCount) {
            return new GovernorAnnouncementValue(governorId, newCount, members, tcpAddress, System.currentTimeMillis());
        }

        public GovernorAnnouncementValue withMembers(List<NodeId> newMembers, String newTcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis());
        }
    }

    @Codec enum NodeLifecycleState {
        JOINING,
        ON_DUTY,
        DRAINING,
        DECOMMISSIONED,
        SHUTTING_DOWN
    }

    record NodeLifecycleValue(NodeLifecycleState state, long updatedAt, String host, int port) implements AetherValue {
        public NodeLifecycleValue {
            if (host == null) {host = "";}
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
            return new NodeLifecycleValue(state, System.currentTimeMillis(), "", 0);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
            return new NodeLifecycleValue(state, updatedAt, "", 0);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, String host, int port) {
            return new NodeLifecycleValue(state, System.currentTimeMillis(), host, port);
        }

        public boolean hasAddress() {
            return ! host.isEmpty() && port > 0;
        }

        public NodeLifecycleValue withState(NodeLifecycleState newState) {
            return new NodeLifecycleValue(newState, System.currentTimeMillis(), host, port);
        }
    }

    record NodeArtifactValue(SliceState state,
                             Option<String> failureReason,
                             boolean fatal,
                             int instanceNumber,
                             List<String> methods) implements AetherValue {
        public static NodeArtifactValue nodeArtifactValue(SliceState state) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of());
        }

        public static NodeArtifactValue failedNodeArtifactValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new NodeArtifactValue(SliceState.FAILED,
                                         Option.option(classified.message()),
                                         classified.isFatal(),
                                         0,
                                         List.of());
        }

        public static NodeArtifactValue activeNodeArtifactValue(int instanceNumber, List<String> methods) {
            return new NodeArtifactValue(SliceState.ACTIVE, Option.none(), false, instanceNumber, List.copyOf(methods));
        }

        public NodeArtifactValue withState(SliceState newState) {
            if (newState == SliceState.ACTIVE) {return new NodeArtifactValue(newState,
                                                                             Option.none(),
                                                                             false,
                                                                             instanceNumber,
                                                                             methods);}
            return new NodeArtifactValue(newState, Option.none(), false, 0, List.of());
        }

        public boolean hasEndpoints() {
            return state == SliceState.ACTIVE && !methods.isEmpty();
        }
    }

    record NodeRoutesValue(List<RouteEntry> routes) implements AetherValue {
        public record RouteEntry(String httpMethod,
                                 String pathPrefix,
                                 String sliceMethod,
                                 String state,
                                 int weight,
                                 long registeredAt,
                                 String security) {
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

            public static RouteEntry activeRoute(String httpMethod, String pathPrefix, String sliceMethod) {
                return activeRoute(httpMethod, pathPrefix, sliceMethod, "PUBLIC");
            }

            public boolean isRoutable() {
                return "ACTIVE".equals(state) && weight > 0;
            }
        }

        public static NodeRoutesValue empty() {
            return new NodeRoutesValue(List.of());
        }

        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes) {
            return new NodeRoutesValue(List.copyOf(routes));
        }
    }

    record BlueprintResourcesValue(String tomlContent) implements AetherValue {
        public static BlueprintResourcesValue blueprintResourcesValue(String tomlContent) {
            return new BlueprintResourcesValue(tomlContent);
        }
    }

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

    record SchemaMigrationLockValue(String datasourceName, NodeId heldBy, long acquiredAt, long expiresAt) implements AetherValue {
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

    @Codec enum SchemaStatus {
        PENDING,
        MIGRATING,
        COMPLETED,
        FAILED
    }

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

    record AbTestRoutingValue(String testId, String splitRuleJson, String variantVersionsJson) implements AetherValue {
        public static AbTestRoutingValue abTestRoutingValue(String testId,
                                                            String splitRuleJson,
                                                            String variantVersionsJson) {
            return new AbTestRoutingValue(testId, splitRuleJson, variantVersionsJson);
        }
    }

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

    record StreamPartitionAssignmentValue(List<PartitionAssignment> assignments, long updatedAt) implements AetherValue {
        public record PartitionAssignment(int partition, NodeId consumerNode) {
            public static PartitionAssignment partitionAssignment(int partition, NodeId consumerNode) {
                return new PartitionAssignment(partition, consumerNode);
            }
        }

        public static StreamPartitionAssignmentValue streamPartitionAssignmentValue(List<PartitionAssignment> assignments) {
            return new StreamPartitionAssignmentValue(List.copyOf(assignments), System.currentTimeMillis());
        }
    }

    record StreamCursorCheckpointValue(long committedOffset, long commitTimestamp) implements AetherValue {
        public static StreamCursorCheckpointValue streamCursorCheckpointValue(long committedOffset) {
            return new StreamCursorCheckpointValue(committedOffset, System.currentTimeMillis());
        }
    }

    record StreamRegistrationValue(NodeId nodeId, String consumerGroup, boolean batchMode, String eventType) implements AetherValue {
        public static StreamRegistrationValue streamRegistrationValue(NodeId nodeId,
                                                                      String consumerGroup,
                                                                      boolean batchMode,
                                                                      String eventType) {
            return new StreamRegistrationValue(nodeId, consumerGroup, batchMode, eventType);
        }
    }

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

    record StorageRefValue(String blockIdHex, long updatedAt) implements AetherValue {
        public static StorageRefValue storageRefValue(String blockIdHex) {
            return new StorageRefValue(blockIdHex, System.currentTimeMillis());
        }
    }

    record StorageStatusValue(String instanceName,
                              List<TierStatus> tiers,
                              String readinessState,
                              boolean isReadReady,
                              boolean isWriteReady,
                              long lastSnapshotEpoch,
                              long lastSnapshotTimestamp,
                              long updatedAt) implements AetherValue {
        public record TierStatus(String level, long usedBytes, long maxBytes) {
            public static TierStatus tierStatus(String level, long usedBytes, long maxBytes) {
                return new TierStatus(level, usedBytes, maxBytes);
            }
        }

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

    record TaskAssignmentValue(NodeId assignedTo, long assignedAtMs, AssignmentStatus status, String failureReason) implements AetherValue {
        @Codec public enum AssignmentStatus {
            ASSIGNED,
            ACTIVE,
            FAILED
        }

        public static TaskAssignmentValue taskAssignmentValue(NodeId assignedTo) {
            return new TaskAssignmentValue(assignedTo, System.currentTimeMillis(), AssignmentStatus.ASSIGNED, "");
        }

        public TaskAssignmentValue withStatus(AssignmentStatus newStatus) {
            return new TaskAssignmentValue(assignedTo, assignedAtMs, newStatus, failureReason);
        }

        public TaskAssignmentValue withFailure(String reason) {
            return new TaskAssignmentValue(assignedTo, assignedAtMs, AssignmentStatus.FAILED, reason);
        }
    }
}
