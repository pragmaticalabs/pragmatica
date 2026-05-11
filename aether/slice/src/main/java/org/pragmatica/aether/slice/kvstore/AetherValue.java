// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Option.none;


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

    record SliceNodeValue(SliceState state, Option<String> failureReason, boolean fatal, long transitionedAt) implements AetherValue {
        public static SliceNodeValue sliceNodeValue(SliceState state) {
            return new SliceNodeValue(state, none(), false, defaultTransitionedAt(state));
        }

        public static SliceNodeValue sliceNodeValue(SliceState state, long transitionedAt) {
            return new SliceNodeValue(state, none(), false, transitionedAt);
        }

        public static SliceNodeValue failedSliceNodeValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new SliceNodeValue(SliceState.FAILED,
                                      Option.option(classified.message()),
                                      classified.isFatal(),
                                      0L);
        }

        private static long defaultTransitionedAt(SliceState state) {
            return state.isTransitional()
                  ? System.currentTimeMillis()
                  : 0L;
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
                                     long announcedAt,
                                     long communityTerm,
                                     Epoch communityEpoch,
                                     Epoch observedCoreEpoch,
                                     HlcTimestamp transitionedAt,
                                     boolean dissolved) implements AetherValue {
        public GovernorAnnouncementValue {
            members = members == null
                     ? List.of()
                     : List.copyOf(members);
            if (tcpAddress == null) {tcpAddress = "";}
            if (communityEpoch == null) {communityEpoch = Epoch.ZERO;}
            if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
            if (transitionedAt == null) {transitionedAt = HlcTimestamp.ZERO;}
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId, int memberCount) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 List.of(),
                                                 "",
                                                 System.currentTimeMillis(),
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 List.of(),
                                                 "",
                                                 announcedAt,
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 List.copyOf(members),
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          int memberCount,
                                                                          List<NodeId> members,
                                                                          String tcpAddress,
                                                                          long announcedAt) {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 members,
                                                 tcpAddress,
                                                 announcedAt,
                                                 0L,
                                                 Epoch.ZERO,
                                                 Epoch.ZERO,
                                                 HlcTimestamp.ZERO,
                                                 false);
        }

        public static GovernorAnnouncementValue governorAnnouncementValue(NodeId governorId,
                                                                          List<NodeId> members,
                                                                          String tcpAddress,
                                                                          long announcedAt,
                                                                          long communityTerm,
                                                                          Epoch communityEpoch,
                                                                          Epoch observedCoreEpoch,
                                                                          HlcTimestamp transitionedAt,
                                                                          boolean dissolved) {
            return new GovernorAnnouncementValue(governorId,
                                                 members.size(),
                                                 members,
                                                 tcpAddress,
                                                 announcedAt,
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withMemberCount(int newCount) {
            return new GovernorAnnouncementValue(governorId,
                                                 newCount,
                                                 members,
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withMembers(List<NodeId> newMembers, String newTcpAddress) {
            return new GovernorAnnouncementValue(governorId,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 dissolved);
        }

        public GovernorAnnouncementValue withGovernorChange(NodeId newGovernor,
                                                            List<NodeId> newMembers,
                                                            String newTcpAddress,
                                                            Epoch newObservedCoreEpoch,
                                                            HlcTimestamp newTransitionedAt) {
            var nextTerm = communityTerm + 1;
            return new GovernorAnnouncementValue(newGovernor,
                                                 newMembers.size(),
                                                 List.copyOf(newMembers),
                                                 newTcpAddress,
                                                 System.currentTimeMillis(),
                                                 nextTerm,
                                                 Epoch.epoch(nextTerm, 0L),
                                                 newObservedCoreEpoch,
                                                 newTransitionedAt,
                                                 false);
        }

        public GovernorAnnouncementValue withDissolved() {
            return new GovernorAnnouncementValue(governorId,
                                                 memberCount,
                                                 members,
                                                 tcpAddress,
                                                 System.currentTimeMillis(),
                                                 communityTerm,
                                                 communityEpoch,
                                                 observedCoreEpoch,
                                                 transitionedAt,
                                                 true);
        }
    }

    @Codec enum NodeLifecycleState {
        JOINING,
        ON_DUTY,
        DRAINING,
        DECOMMISSIONED,
        SHUTTING_DOWN,
        FAILED_DRAIN
    }

    /// Three-phase model (D.3, 2026-05-11):
    /// - `COLD_BOOT` — cluster never had quorum. SWIM suppresses `FaultyObserved` for
    ///   never-healthy peers (preserves the cold-boot-during-formation invariant).
    ///   HealthReconciler suppresses DECOMMISSIONED/SHUTTING_DOWN/DRAINING writes.
    ///   CTM auto-heal is suspended. Transition out: first time the cluster reaches a
    ///   quorum of ON_DUTY peers AND a leader is elected, sustained for `stableWindowMs`.
    /// - `NORMAL` — full failure semantics. No suppression anywhere.
    /// - `RECOVERING` — cluster previously reached NORMAL but lost quorum (e.g.,
    ///   compose-restart, network partition, sustained chaos). SWIM emits FaultyObserved
    ///   with NORMAL semantics — `everSeenHealthy` gate is bypassed because the peer was
    ///   visible-and-healthy in the prior NORMAL period. HealthReconciler writes lifecycle
    ///   transitions normally. CTM auto-heal stays suspended (operator-free recovery is
    ///   the goal; provisioning resumes only after stability). Transition back to NORMAL:
    ///   quorum-stable for `recoveryStableWindowMs`.
    @Codec enum ClusterPhase {
        COLD_BOOT,
        NORMAL,
        RECOVERING
    }

    record ClusterPhaseValue(ClusterPhase phase, long updatedAt) implements AetherValue {
        public ClusterPhaseValue {
            if (phase == null) {phase = ClusterPhase.COLD_BOOT;}
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase) {
            return new ClusterPhaseValue(phase, System.currentTimeMillis());
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase, long updatedAt) {
            return new ClusterPhaseValue(phase, updatedAt);
        }
    }

    @Codec enum ProvisioningSource {
        CTM,
        MANUAL,
        UNKNOWN
    }

    record NodeLifecycleValue(NodeLifecycleState state,
                              long updatedAt,
                              String host,
                              int port,
                              Epoch observedCoreEpoch,
                              HlcTimestamp transitionedAt,
                              ProvisioningSource provisioningSource) implements AetherValue {
        public NodeLifecycleValue {
            if (host == null) {host = "";}
            if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
            if (transitionedAt == null) {transitionedAt = HlcTimestamp.ZERO;}
            if (provisioningSource == null) {provisioningSource = ProvisioningSource.UNKNOWN;}
        }

        @Deprecated(since = "rc1-wave3", forRemoval = false) public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          "",
                                          0,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                            String host,
                                                            int port,
                                                            Epoch observedCoreEpoch) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          host,
                                          port,
                                          observedCoreEpoch,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
            return new NodeLifecycleValue(state,
                                          updatedAt,
                                          "",
                                          0,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, String host, int port) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          host,
                                          port,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                            String host,
                                                            int port,
                                                            ProvisioningSource provisioningSource) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          host,
                                          port,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          provisioningSource);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                            long updatedAt,
                                                            String host,
                                                            int port,
                                                            Epoch observedCoreEpoch,
                                                            HlcTimestamp transitionedAt) {
            return new NodeLifecycleValue(state,
                                          updatedAt,
                                          host,
                                          port,
                                          observedCoreEpoch,
                                          transitionedAt,
                                          ProvisioningSource.UNKNOWN);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                            long updatedAt,
                                                            String host,
                                                            int port,
                                                            Epoch observedCoreEpoch,
                                                            HlcTimestamp transitionedAt,
                                                            ProvisioningSource provisioningSource) {
            return new NodeLifecycleValue(state,
                                          updatedAt,
                                          host,
                                          port,
                                          observedCoreEpoch,
                                          transitionedAt,
                                          provisioningSource);
        }

        public boolean hasAddress() {
            return ! host.isEmpty() && port > 0;
        }

        public NodeLifecycleValue withState(NodeLifecycleState newState, HlcTimestamp now) {
            var nextTransitionedAt = newState == state
                                    ? transitionedAt
                                    : now;
            return new NodeLifecycleValue(newState,
                                          System.currentTimeMillis(),
                                          host,
                                          port,
                                          observedCoreEpoch,
                                          nextTransitionedAt,
                                          provisioningSource);
        }

        public NodeLifecycleValue withProvisioningSource(ProvisioningSource newSource) {
            return new NodeLifecycleValue(state, updatedAt, host, port, observedCoreEpoch, transitionedAt, newSource);
        }
    }

    record NodeArtifactValue(SliceState state,
                             Option<String> failureReason,
                             boolean fatal,
                             int instanceNumber,
                             List<String> methods,
                             long transitionedAt) implements AetherValue {
        public static NodeArtifactValue nodeArtifactValue(SliceState state) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of(), defaultTransitionedAt(state));
        }

        public static NodeArtifactValue nodeArtifactValue(SliceState state, long transitionedAt) {
            return new NodeArtifactValue(state, Option.none(), false, 0, List.of(), transitionedAt);
        }

        public static NodeArtifactValue failedNodeArtifactValue(Cause cause) {
            var classified = SliceLoadingFailure.classify(cause);
            return new NodeArtifactValue(SliceState.FAILED,
                                         Option.option(classified.message()),
                                         classified.isFatal(),
                                         0,
                                         List.of(),
                                         0L);
        }

        public static NodeArtifactValue activeNodeArtifactValue(int instanceNumber, List<String> methods) {
            return new NodeArtifactValue(SliceState.ACTIVE,
                                         Option.none(),
                                         false,
                                         instanceNumber,
                                         List.copyOf(methods),
                                         0L);
        }

        public NodeArtifactValue withState(SliceState newState) {
            if (newState == SliceState.ACTIVE) {return new NodeArtifactValue(newState,
                                                                             Option.none(),
                                                                             false,
                                                                             instanceNumber,
                                                                             methods,
                                                                             0L);}
            return new NodeArtifactValue(newState, Option.none(), false, 0, List.of(), defaultTransitionedAt(newState));
        }

        public boolean hasEndpoints() {
            return state == SliceState.ACTIVE && !methods.isEmpty();
        }

        private static long defaultTransitionedAt(SliceState state) {
            return state.isTransitional()
                  ? System.currentTimeMillis()
                  : 0L;
        }
    }

    record NodeRoutesValue(List<RouteEntry> routes, Epoch observedCoreEpoch) implements AetherValue {
        public NodeRoutesValue {
            routes = routes == null
                    ? List.of()
                    : List.copyOf(routes);
            if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
        }

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
            return new NodeRoutesValue(List.of(), Epoch.ZERO);
        }

        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes) {
            return new NodeRoutesValue(List.copyOf(routes), Epoch.ZERO);
        }

        public static NodeRoutesValue nodeRoutesValue(List<RouteEntry> routes, Epoch observedCoreEpoch) {
            return new NodeRoutesValue(List.copyOf(routes), observedCoreEpoch);
        }

        public NodeRoutesValue withObservedCoreEpoch(Epoch newEpoch) {
            return new NodeRoutesValue(routes, newEpoch);
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

    record ApiKeyValue(String keyId,
                       String keyHash,
                       long createdAt,
                       long expiresAt,
                       String status,
                       long revokedAt,
                       long gracePeriodMs,
                       String authorizationRole) implements AetherValue {
        static final String ACTIVE = "ACTIVE";

        static final String REVOKED = "REVOKED";

        static final String EXPIRED = "EXPIRED";

        public static final String DEFAULT_ROLE = "VIEWER";

        public static ApiKeyValue apiKeyValue(String keyId, String keyHash, long gracePeriodMs) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   System.currentTimeMillis(),
                                   - 1,
                                   ACTIVE,
                                   - 1,
                                   gracePeriodMs,
                                   DEFAULT_ROLE);
        }

        public static ApiKeyValue apiKeyValue(String keyId,
                                              String keyHash,
                                              long gracePeriodMs,
                                              String authorizationRole) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   System.currentTimeMillis(),
                                   - 1,
                                   ACTIVE,
                                   - 1,
                                   gracePeriodMs,
                                   authorizationRole);
        }

        public static ApiKeyValue apiKeyValue(String keyId,
                                              String keyHash,
                                              long createdAt,
                                              long expiresAt,
                                              String status,
                                              long revokedAt,
                                              long gracePeriodMs,
                                              String authorizationRole) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   status,
                                   revokedAt,
                                   gracePeriodMs,
                                   authorizationRole);
        }

        public boolean isActive() {
            return ACTIVE.equals(status);
        }

        public boolean isRevoked() {
            return REVOKED.equals(status);
        }

        public boolean isInGracePeriod() {
            return isRevoked() && revokedAt > 0 && System.currentTimeMillis() <revokedAt + gracePeriodMs;
        }

        public boolean isValidForAuth() {
            return isActive() || isInGracePeriod();
        }

        public ApiKeyValue withRevoked(long gracePeriod) {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   REVOKED,
                                   System.currentTimeMillis(),
                                   gracePeriod,
                                   authorizationRole);
        }

        public ApiKeyValue withExpired() {
            return new ApiKeyValue(keyId,
                                   keyHash,
                                   createdAt,
                                   expiresAt,
                                   EXPIRED,
                                   revokedAt,
                                   gracePeriodMs,
                                   authorizationRole);
        }
    }

    record ApiKeyAuditValue(String keyId, String action, long timestamp, String operatorHint) implements AetherValue {
        public static final String ACTION_CREATED = "CREATED";

        public static final String ACTION_ROTATED = "ROTATED";

        public static final String ACTION_REVOKED = "REVOKED";

        public static final String ACTION_EXPIRED = "EXPIRED";

        public static ApiKeyAuditValue apiKeyAuditValue(String keyId, String action, String operatorHint) {
            return new ApiKeyAuditValue(keyId, action, System.currentTimeMillis(), operatorHint);
        }

        public static ApiKeyAuditValue apiKeyAuditValue(String keyId,
                                                        String action,
                                                        long timestamp,
                                                        String operatorHint) {
            return new ApiKeyAuditValue(keyId, action, timestamp, operatorHint);
        }
    }

    record CloudCredentialsValue(byte[] encryptedToken, String provider, long storedAt) implements AetherValue {
        public static CloudCredentialsValue cloudCredentialsValue(byte[] encryptedToken, String provider) {
            return new CloudCredentialsValue(encryptedToken.clone(), provider, System.currentTimeMillis());
        }

        @Override public byte[] encryptedToken() {
            return encryptedToken.clone();
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

    record ConsumerGroupValue(NodeId assignedTo, String consumerId, long assignedAt) implements AetherValue {
        public static ConsumerGroupValue consumerGroupValue(NodeId assignedTo, String consumerId) {
            return new ConsumerGroupValue(assignedTo, consumerId, System.currentTimeMillis());
        }
    }

    record StreamConfigValue(StreamConfig config, long createdAt) implements AetherValue {
        public static StreamConfigValue streamConfigValue(StreamConfig config) {
            return new StreamConfigValue(config, System.currentTimeMillis());
        }

        public static StreamConfigValue streamConfigValue(StreamConfig config, long createdAt) {
            return new StreamConfigValue(config, createdAt);
        }
    }

    record DhtPartitionOwnershipValue(NodeId ownerNodeId,
                                      String ownerCommunityId,
                                      Epoch ownerEpoch,
                                      long ownershipTerm,
                                      HlcTimestamp transferredAt) implements AetherValue {
        public DhtPartitionOwnershipValue {
            if (ownerCommunityId == null) {ownerCommunityId = "";}
            if (ownerEpoch == null) {ownerEpoch = Epoch.ZERO;}
            if (transferredAt == null) {transferredAt = HlcTimestamp.ZERO;}
        }

        public static DhtPartitionOwnershipValue dhtPartitionOwnershipValue(NodeId ownerNodeId,
                                                                            String ownerCommunityId,
                                                                            Epoch ownerEpoch,
                                                                            long ownershipTerm,
                                                                            HlcTimestamp transferredAt) {
            return new DhtPartitionOwnershipValue(ownerNodeId,
                                                  ownerCommunityId,
                                                  ownerEpoch,
                                                  ownershipTerm,
                                                  transferredAt);
        }
    }

    @Codec enum SpokesmanStatus {
        ASSIGNED,
        ACTIVE,
        FAILED
    }

    record ProvisioningSlotValue(long spawnedAtMs, long deadlineMs, Option<NodeId> assignedNodeId) implements AetherValue {
        public ProvisioningSlotValue {
            if (assignedNodeId == null) {assignedNodeId = Option.none();}
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs, long deadlineMs) {
            return new ProvisioningSlotValue(spawnedAtMs, deadlineMs, Option.none());
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs,
                                                                  long deadlineMs,
                                                                  NodeId assignedNodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, deadlineMs, Option.option(assignedNodeId));
        }

        public ProvisioningSlotValue withAssignedNode(NodeId nodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, deadlineMs, Option.option(nodeId));
        }
    }

    record GenerationSnapshotValue(ClusterGenerationSnapshot snapshot) implements AetherValue {
        public static GenerationSnapshotValue generationSnapshotValue(ClusterGenerationSnapshot snapshot) {
            return new GenerationSnapshotValue(snapshot);
        }
    }

    record SpokesmanValue(List<String> communities,
                          Epoch assignedEpoch,
                          HlcTimestamp assignedAt,
                          long version,
                          SpokesmanStatus status,
                          String failureReason) implements AetherValue {
        public SpokesmanValue {
            communities = communities == null
                         ? List.of()
                         : List.copyOf(communities);
            if (assignedEpoch == null) {assignedEpoch = Epoch.ZERO;}
            if (assignedAt == null) {assignedAt = HlcTimestamp.ZERO;}
            if (status == null) {status = SpokesmanStatus.ASSIGNED;}
            if (failureReason == null) {failureReason = "";}
        }

        public static SpokesmanValue spokesmanValue(List<String> communities,
                                                    Epoch assignedEpoch,
                                                    HlcTimestamp assignedAt,
                                                    long version) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, SpokesmanStatus.ASSIGNED, "");
        }

        public SpokesmanValue withStatus(SpokesmanStatus newStatus) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, newStatus, failureReason);
        }

        public SpokesmanValue withFailure(String reason) {
            return new SpokesmanValue(communities, assignedEpoch, assignedAt, version, SpokesmanStatus.FAILED, reason);
        }
    }
}
