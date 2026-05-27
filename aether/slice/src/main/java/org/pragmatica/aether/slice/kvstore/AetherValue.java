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

    /// `registerOnly` semantically signals that this blueprint was registered via
    /// `/api/blueprints/publish` (not `/api/blueprints/deploy`). The publish endpoint
    /// stores the blueprint definition for use by a future strategy-based deploy upgrade
    /// without immediately making it the active version. Consumed by
    /// `ClusterDeploymentState.handleAppBlueprintChange`, which suppresses the
    /// `SliceTargetValue` Put when `registerOnly && existing SliceTargetValue present`.
    record AppBlueprintValue(ExpandedBlueprint blueprint, boolean registerOnly) implements AetherValue {
        /// Backward-compat constructor — pre-existing call sites pass blueprint only;
        /// `registerOnly` defaults to `false` (the historical deploy-on-publish semantics).
        public AppBlueprintValue(ExpandedBlueprint blueprint) {
            this(blueprint, false);
        }

        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint) {
            return new AppBlueprintValue(blueprint, false);
        }

        public static AppBlueprintValue appBlueprintValue(ExpandedBlueprint blueprint, boolean registerOnly) {
            return new AppBlueprintValue(blueprint, registerOnly);
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

    /// Collapsed 4-value lifecycle (cluster-convergence-reconciler-spec §5.1, I-step). The pre-RC1
    /// 6-value alphabet (`DECOMMISSIONED` / `SHUTTING_DOWN` / `FAILED_DRAIN`) is unified into the
    /// single terminal `STOPPED`; the previously-distinct semantics survive via the [StopReason]
    /// sidecar on `NodeLifecycleValue.stopReason`:
    /// - `STOPPED + FORCED`        ← old `DECOMMISSIONED`
    /// - `STOPPED + GRACEFUL`      ← old `SHUTTING_DOWN`
    /// - `STOPPED + DRAIN_FAILED`  ← old `FAILED_DRAIN`
    @Codec enum NodeLifecycleState {
        JOINING,
        ON_DUTY,
        DRAINING,
        STOPPED
    }

    /// Three-phase model (D.3, 2026-05-11):
    /// - `COLD_BOOT` — cluster never had quorum. SWIM suppresses `FaultyObserved` for
    ///   never-healthy peers (preserves the cold-boot-during-formation invariant).
    ///   MembershipFsm structural bootstrap-safety suppresses STOPPED/DRAINING writes.
    ///   CTM auto-heal is suspended. Transition out: first time the cluster reaches a
    ///   quorum of ON_DUTY peers AND a leader is elected, sustained for `stableWindowMs`.
    /// - `NORMAL` — full failure semantics. No suppression anywhere.
    /// - `RECOVERING` — cluster previously reached NORMAL but lost quorum (e.g.,
    ///   compose-restart, network partition, sustained chaos). SWIM emits FaultyObserved
    ///   with NORMAL semantics — `everSeenHealthy` gate is bypassed because the peer was
    ///   visible-and-healthy in the prior NORMAL period. the leader FSM writes lifecycle
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

    /// Reason a node entered the terminal `STOPPED` state. Sidecar to the collapsed
    /// `NodeLifecycleState` enum (cluster-convergence-reconciler-spec §5.1, D2.2). Present
    /// only when `state == STOPPED`; `Option.none()` otherwise. Read by observers that
    /// previously distinguished `DECOMMISSIONED` / `SHUTTING_DOWN` / `FAILED_DRAIN` and by
    /// the reconciler audit stream.
    @Codec enum StopReason {
        GRACEFUL,
        FORCED,
        DRAIN_FAILED
    }

    /// Replicated lifecycle truth for a peer.
    ///
    /// ### Versioning
    /// `transitionedAt` = HLC stamp of the most recent state change (per-peer monotonic).
    /// `version`        = wire-format schema version (global, monotonic per code release;
    /// see [CURRENT_VERSION]). Placed LAST so auto-generated `@Codec` decoders remain
    /// compatible with pre-RC1 persisted state — but RC1 is the version-floor:
    /// pre-RC1 KV state lacking the trailing byte is not migrated.
    /// Receiver policy on mismatch: fail-closed (membership truth must not be silently lost).
    record NodeLifecycleValue(NodeLifecycleState state,
                              long updatedAt,
                              String host,
                              int port,
                              Epoch observedCoreEpoch,
                              HlcTimestamp transitionedAt,
                              ProvisioningSource provisioningSource,
                              Option<StopReason> stopReason,
                              byte version) implements AetherValue {
        /// Current schema version stamped onto every newly-constructed value.
        /// Bump on any structural change to the record payload.
        /// Version 3 (cluster-convergence-reconciler PR-A): `NodeLifecycleState` collapsed
        /// from 6 values to 4 (`JOINING / ON_DUTY / DRAINING / STOPPED`); the prior
        /// `DECOMMISSIONED` / `SHUTTING_DOWN` / `FAILED_DRAIN` ordinals are now invalid on
        /// the wire.
        public static final byte CURRENT_VERSION = 3;

        public NodeLifecycleValue {
            if (host == null) {host = "";}
            if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
            if (transitionedAt == null) {transitionedAt = HlcTimestamp.ZERO;}
            if (provisioningSource == null) {provisioningSource = ProvisioningSource.UNKNOWN;}
            if (stopReason == null) {stopReason = none();}
        }

        /// Backward-compatible 7-arg constructor — preserves pre-stopReason call sites while
        /// stamping `version = CURRENT_VERSION` and `stopReason = none()`.
        public NodeLifecycleValue(NodeLifecycleState state,
                                  long updatedAt,
                                  String host,
                                  int port,
                                  Epoch observedCoreEpoch,
                                  HlcTimestamp transitionedAt,
                                  ProvisioningSource provisioningSource) {
            this(state, updatedAt, host, port, observedCoreEpoch, transitionedAt, provisioningSource, none(), CURRENT_VERSION);
        }

        /// Backward-compatible 8-arg constructor — preserves pre-stopReason call sites that
        /// passed an explicit `version`. Defaults `stopReason = none()`.
        public NodeLifecycleValue(NodeLifecycleState state,
                                  long updatedAt,
                                  String host,
                                  int port,
                                  Epoch observedCoreEpoch,
                                  HlcTimestamp transitionedAt,
                                  ProvisioningSource provisioningSource,
                                  byte version) {
            this(state, updatedAt, host, port, observedCoreEpoch, transitionedAt, provisioningSource, none(), version);
        }

        @Deprecated(since = "rc1-wave3", forRemoval = false) public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          "",
                                          0,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN,
                                          CURRENT_VERSION);
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
                                          ProvisioningSource.UNKNOWN,
                                          CURRENT_VERSION);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
            return new NodeLifecycleValue(state,
                                          updatedAt,
                                          "",
                                          0,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN,
                                          CURRENT_VERSION);
        }

        public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, String host, int port) {
            return new NodeLifecycleValue(state,
                                          System.currentTimeMillis(),
                                          host,
                                          port,
                                          Epoch.ZERO,
                                          HlcTimestamp.ZERO,
                                          ProvisioningSource.UNKNOWN,
                                          CURRENT_VERSION);
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
                                          provisioningSource,
                                          CURRENT_VERSION);
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
                                          ProvisioningSource.UNKNOWN,
                                          CURRENT_VERSION);
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
                                          provisioningSource,
                                          CURRENT_VERSION);
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
                                          provisioningSource,
                                          stopReason,
                                          version);
        }

        public NodeLifecycleValue withProvisioningSource(ProvisioningSource newSource) {
            return new NodeLifecycleValue(state, updatedAt, host, port, observedCoreEpoch, transitionedAt, newSource, stopReason, version);
        }

        /// Set or clear the `stopReason` sidecar. The reconciler / `LifecycleWriter`
        /// command-handler calls this when transitioning to `STOPPED` to record `GRACEFUL` /
        /// `FORCED` / `DRAIN_FAILED`. Reset to `none()` when leaving `STOPPED` (currently
        /// only possible via re-join after slot reuse).
        public NodeLifecycleValue withStopReason(Option<StopReason> newStopReason) {
            return new NodeLifecycleValue(state, updatedAt, host, port, observedCoreEpoch, transitionedAt, provisioningSource, newStopReason, version);
        }
    }

    /// Phase 1 step J — replicated mirror of the in-process `JOIN_DEADLINE` scheduler
    /// entry. `deadlineMs` is wall-clock millis (epoch) when the join deadline fires;
    /// `setAt` is the HLC stamp of the originating JOINING-entry transition (provides
    /// causal ordering across leader takeover for observers reconstructing the deadline).
    /// Pure observability atom — see [AetherKey.JoinDeadlineKey] for trigger semantics.
    record JoinDeadlineValue(long deadlineMs, HlcTimestamp setAt) implements AetherValue {
        public JoinDeadlineValue {
            if (setAt == null) {setAt = HlcTimestamp.ZERO;}
        }

        public static JoinDeadlineValue joinDeadlineValue(long deadlineMs, HlcTimestamp setAt) {
            return new JoinDeadlineValue(deadlineMs, setAt);
        }
    }

    /// Phase 1 step J — replicated mirror of the in-process `DRAIN_DEADLINE` scheduler
    /// entry. `deadlineMs` is wall-clock millis (epoch) when the drain hard-deadline
    /// fires; `setAt` is the HLC stamp of the DRAINING-entry transition. Pure
    /// observability atom — see [AetherKey.DrainDeadlineKey] for trigger semantics.
    record DrainDeadlineValue(long deadlineMs, HlcTimestamp setAt) implements AetherValue {
        public DrainDeadlineValue {
            if (setAt == null) {setAt = HlcTimestamp.ZERO;}
        }

        public static DrainDeadlineValue drainDeadlineValue(long deadlineMs, HlcTimestamp setAt) {
            return new DrainDeadlineValue(deadlineMs, setAt);
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

    /// Canonical field order: `(spawnedAtMs, assignedNodeId, occupantEpoch, supersededNodeId)`.
    ///
    /// `spawnedAtMs` is the wall-clock instant (epoch millis) the slot's current FILLING/occupied
    /// generation was stamped; `0` means EMPTY/never-stamped. The FILLING-marker EXPIRY is NOT
    /// stored — it is derived at check time as `spawnedAtMs + autoHealConfig.provisioningTimeout()`
    /// (the single source of truth for the timeout is the shared auto-heal `TimeSpan`; per the
    /// project rule, derived deadline instants are not persisted). `occupantEpoch` is a monotonic,
    /// slot-local generation counter; `0` means empty/never-occupied. `supersededNodeId` records the
    /// predecessor occupant this assignment replaced; `none()` on first fill.
    ///
    /// Backward compatibility (slot-based-membership-convergence-spec §4.2): the legacy
    /// construction sites that passed a `deadlineMs` argument still compile via the deadline-arg
    /// constructors and `provisioningSlotValue(..)` factories below, which discard the now-derived
    /// deadline. Mirrors the `NodeLifecycleValue` trailing-field pattern.
    record ProvisioningSlotValue(long spawnedAtMs,
                                 Option<NodeId> assignedNodeId,
                                 long occupantEpoch,
                                 Option<NodeId> supersededNodeId) implements AetherValue {
        public ProvisioningSlotValue {
            if (assignedNodeId == null) {assignedNodeId = Option.none();}
            if (supersededNodeId == null) {supersededNodeId = Option.none();}
        }

        /// Backward-compatible constructor — preserves call sites that passed the now-derived
        /// `deadlineMs` (discarded). Defaults `occupantEpoch = 0`, `supersededNodeId = none()`.
        public ProvisioningSlotValue(long spawnedAtMs, long deadlineMs, Option<NodeId> assignedNodeId) {
            this(spawnedAtMs, assignedNodeId, 0L, Option.none());
        }

        /// Backward-compatible 5-arg constructor — preserves the pre-remodel fenced-form call sites
        /// that passed `deadlineMs` at position 1 (discarded; expiry is derived now).
        public ProvisioningSlotValue(long spawnedAtMs,
                                     long deadlineMs,
                                     Option<NodeId> assignedNodeId,
                                     long occupantEpoch,
                                     Option<NodeId> supersededNodeId) {
            this(spawnedAtMs, assignedNodeId, occupantEpoch, supersededNodeId);
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.none(), 0L, Option.none());
        }

        /// Backward-compatible factory — `deadlineMs` discarded (derived).
        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs, long deadlineMs) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.none(), 0L, Option.none());
        }

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs,
                                                                  NodeId assignedNodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(assignedNodeId), 0L, Option.none());
        }

        /// Backward-compatible factory — `deadlineMs` discarded (derived).
        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs,
                                                                  long deadlineMs,
                                                                  NodeId assignedNodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(assignedNodeId), 0L, Option.none());
        }

        public ProvisioningSlotValue withAssignedNode(NodeId nodeId) {
            return new ProvisioningSlotValue(spawnedAtMs, Option.option(nodeId), occupantEpoch, supersededNodeId);
        }
    }

    record GenerationSnapshotValue(ClusterGenerationSnapshot snapshot) implements AetherValue {
        public static GenerationSnapshotValue generationSnapshotValue(ClusterGenerationSnapshot snapshot) {
            return new GenerationSnapshotValue(snapshot);
        }
    }

    /// RC1 Step 1 — cluster-scoped replicated event log value.
    ///
    /// Replicated via Rabia (KV-Store). One value per `(epoch, seq)` key. `nodeId` is the
    /// originator (the node whose code emitted the event); for transport-derived events
    /// (e.g., `PeerJoined`) only the LEADER publishes, so the leader's id is the originator.
    ///
    /// `at` is an HLC timestamp for human-readable timeline reconstruction and cross-cluster
    /// diagnostics. Ordering uses the key's `(epoch, seq)`, NOT `at` — sidesteps OB1
    /// receive-time races.
    ///
    /// `version` is the wire-format schema version (LAST-position default = 1) — follows the
    /// same pattern as `NodeLifecycleValue` for forward/backward compatibility.
    record ClusterEventValue(HlcTimestamp at,
                              EventType type,
                              Severity severity,
                              String nodeId,
                              String message,
                              java.util.Map<String, String> metadata,
                              byte version) implements AetherValue {
        public static final byte CURRENT_VERSION = 1;

        public ClusterEventValue {
            if (at == null) {at = HlcTimestamp.ZERO;}
            if (nodeId == null) {nodeId = "";}
            if (message == null) {message = "";}
            if (metadata == null) {metadata = java.util.Map.of();} else {metadata = java.util.Map.copyOf(metadata);}
        }

        public ClusterEventValue(HlcTimestamp at,
                                 EventType type,
                                 Severity severity,
                                 String nodeId,
                                 String message,
                                 java.util.Map<String, String> metadata) {
            this(at, type, severity, nodeId, message, metadata, CURRENT_VERSION);
        }

        public static ClusterEventValue clusterEventValue(HlcTimestamp at,
                                                          EventType type,
                                                          Severity severity,
                                                          String nodeId,
                                                          String message,
                                                          java.util.Map<String, String> metadata) {
            return new ClusterEventValue(at, type, severity, nodeId, message, metadata, CURRENT_VERSION);
        }

        /// Canonical cluster-event taxonomy. Mirrors `ClusterEvent.EventType` in `aether/node`
        /// (the node module also re-exports these via a type-alias for API stability).
        @Codec public enum EventType {
            NODE_JOINED,
            NODE_LEFT,
            NODE_FAILED,
            LEADER_ELECTED,
            LEADER_LOST,
            QUORUM_ESTABLISHED,
            QUORUM_LOST,
            DEPLOYMENT_STARTED,
            DEPLOYMENT_COMPLETED,
            DEPLOYMENT_FAILED,
            SCALE_UP,
            SCALE_DOWN,
            SLICE_FAILURE,
            CONNECTION_ESTABLISHED,
            CONNECTION_FAILED,
            COMMUNITY_SCALE_REQUEST,
            COMMUNITY_METRICS_SNAPSHOT,
            ACCESS_DENIED,
            NODE_LIFECYCLE_CHANGED,
            CONFIG_CHANGED,
            BACKUP_CREATED,
            BACKUP_RESTORED,
            BLUEPRINT_DEPLOYED,
            BLUEPRINT_DELETED,
            GENERATION_CHANGED,
            ALERT_INJECTED,
            TRACE_INJECTED,
            /// Emitted by the draining node itself when its `SelfDrainCoordinator` flips
            /// from `ACTIVE` to `DRAINING` (membership-architecture-spec.md §16.1, scenarios
            /// S19/S20). Severity WARNING — operational event indicating the node is about
            /// to halt because it observed sustained-below-quorum visibility,
            /// `QuorumStateNotification.DISAPPEARED`, or `RabiaEngine.Paused`. The originator
            /// in `nodeId` is the draining node, NOT the cluster leader; this event is
            /// intentionally NOT leader-gated because a partition victim is the only source
            /// of truth for "I am self-draining" and may not even be able to reach the leader.
            /// Details map carries `nodeId`, `reason` (one of `sustained-below-quorum`,
            /// `quorum-disappeared`, `rabia-paused`), and `graceMs`.
            SELF_DRAIN_INITIATED
        }

        @Codec public enum Severity {
            INFO,
            WARNING,
            CRITICAL
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
