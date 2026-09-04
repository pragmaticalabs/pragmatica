// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.EpochBearing;
import org.pragmatica.cluster.state.kvstore.VersionFenced;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.Option.none;


@Codec
@CodecFor(ExecutionMode.class)
@SuppressWarnings("JBCT-NAM-01")
public sealed interface AetherValue {
    record SliceTargetValue(Version currentVersion,
                            int targetInstances,
                            int minInstances,
                            Option<BlueprintId> owningBlueprint,
                            String placement,
                            long updatedAt,
                            Option<Integer> maxInstances,
                            Option<Double> scaleUpThreshold,
                            Option<Double> scaleDownThreshold) implements AetherValue {
        private static final String DEFAULT_PLACEMENT = "CORE_ONLY";

        public SliceTargetValue {
            if (placement == null || placement.isEmpty()) {
                placement = DEFAULT_PLACEMENT;
            }

            if (maxInstances == null) {
                maxInstances = none();
            }

            if (scaleUpThreshold == null) {
                scaleUpThreshold = none();
            }

            if (scaleDownThreshold == null) {
                scaleDownThreshold = none();
            }
        }

        /// Backward-compatible constructor — pre-existing call sites pass the six historical fields;
        /// the per-slice autoscaler overrides (#424) default to `none()`. Mirrors the trailing-field
        /// backward-compat idiom used across `AetherValue` (cf. `AppBlueprintValue`,
        /// `ProvisioningSlotValue`).
        public SliceTargetValue(Version currentVersion,
                                int targetInstances,
                                int minInstances,
                                Option<BlueprintId> owningBlueprint,
                                String placement,
                                long updatedAt) {
            this(currentVersion,
                 targetInstances,
                 minInstances,
                 owningBlueprint,
                 placement,
                 updatedAt,
                 none(),
                 none(),
                 none());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances, Option<BlueprintId> owner) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances) {
            return new SliceTargetValue(version,
                                        instances,
                                        instances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version, int instances, int minInstances) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
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
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        String placement) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        none(),
                                        placement,
                                        System.currentTimeMillis(),
                                        none(),
                                        none(),
                                        none());
        }

        /// Deploy-time factory (#424) carrying the per-slice autoscaler overrides —
        /// `maxInstances` bounds scale-up; the threshold overrides win over the cluster tier.
        public static SliceTargetValue sliceTargetValue(Version version,
                                                        int instances,
                                                        int minInstances,
                                                        Option<BlueprintId> owner,
                                                        Option<Integer> maxInstances,
                                                        Option<Double> scaleUpThreshold,
                                                        Option<Double> scaleDownThreshold) {
            return new SliceTargetValue(version,
                                        instances,
                                        minInstances,
                                        owner,
                                        DEFAULT_PLACEMENT,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
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
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }

        public SliceTargetValue withPlacement(String newPlacement) {
            return new SliceTargetValue(currentVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        newPlacement,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
        }

        public SliceTargetValue withVersion(Version newVersion) {
            return new SliceTargetValue(newVersion,
                                        targetInstances,
                                        minInstances,
                                        owningBlueprint,
                                        placement,
                                        System.currentTimeMillis(),
                                        maxInstances,
                                        scaleUpThreshold,
                                        scaleDownThreshold);
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

    /// Durable terminal outcome of one blueprint's deployment attempt, keyed by
    /// `AetherKey.DeploymentOutcomeKey`. Written by `ClusterDeploymentState` at the FSM's terminal
    /// transitions (full deployment, ALL_OR_NOTHING rollback) and never removed when the blueprint's
    /// own `AppBlueprintValue` is torn down — this is the record of what happened, not part of the
    /// blueprint's active configuration, so it survives rollback and remains readable via
    /// `BlueprintService.lastOutcome` after `GET /api/blueprints/status/{id}` would otherwise 404.
    ///
    /// `timestampMs` is passed in explicitly (no internal `System.currentTimeMillis()` default,
    /// unlike `SchemaVersionValue`) so the FSM call site can supply `ClusterDeploymentContext.nowMs()`
    /// — the same test-controllable clock already used for this class's `DeploymentFailed` event
    /// timestamps, keeping both artifacts of one failure event on one clock read.
    record DeploymentOutcomeValue(DeploymentOutcomeStatus status,
                                  List<String> failingSlices,
                                  String cause,
                                  long timestampMs) implements AetherValue {
        public static DeploymentOutcomeValue succeeded(long timestampMs) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.SUCCEEDED, List.of(), "", timestampMs);
        }

        public static DeploymentOutcomeValue failed(List<String> failingSlices, String cause, long timestampMs) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.FAILED,
                                              List.copyOf(failingSlices),
                                              cause,
                                              timestampMs);
        }

        public static DeploymentOutcomeValue rolledBack(List<String> failingSlices, String cause, long timestampMs) {
            return new DeploymentOutcomeValue(DeploymentOutcomeStatus.ROLLED_BACK,
                                              List.copyOf(failingSlices),
                                              cause,
                                              timestampMs);
        }
    }

    /// `FAILED` — the blueprint's own slices never reached ACTIVE and there was no previous
    /// blueprint to fall back to (`unloadBlueprintSlices`'s path), OR a BEST_EFFORT partial deploy
    /// left one or more slices permanently failed while the rest stayed up
    /// (`recordBestEffortFailureOutcome`'s path — `failingSlices` accumulates across independent
    /// failures in the same blueprint rather than being overwritten by the last one). `ROLLED_BACK`
    /// — a previous blueprint existed and was restored in this blueprint's place
    /// (`restorePreviousBlueprint`'s path); kept distinct from `FAILED` because a caller needs to
    /// know whether the failure left the deployment empty or reverted it to a known-good prior
    /// version.
    ///
    /// #760/#724 review round 2 item g: this record is written only at the specific terminal points
    /// enumerated above and in `recordSucceededOutcome`. A blueprint deployment that never reaches
    /// any of them — the FSM host crashes mid-flight before a terminal `submitBatch`/`apply` call is
    /// even issued, or a deployment simply never resolves (no further `NodeArtifactPutReceived`
    /// events ever arrive, no deterministic or transient failure is ever reported) — leaves NO
    /// `DeploymentOutcomeKey` entry at all. Absence of a key is therefore NOT equivalent to any of
    /// the three statuses below; it means "no attempt reached a terminal write," which is
    /// indistinguishable, from this record alone, from "no attempt was ever made."
    @Codec
    enum DeploymentOutcomeStatus {
        SUCCEEDED,
        FAILED,
        ROLLED_BACK
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
                                   long updatedAt,
                                   int skippedOverlaps) implements AetherValue {
        public static ScheduledTaskStateValue successState(long nextFireAt, int totalExecutions, int skippedOverlaps) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               0,
                                               totalExecutions,
                                               "",
                                               System.currentTimeMillis(),
                                               skippedOverlaps);
        }

        public static ScheduledTaskStateValue failureState(long nextFireAt,
                                                           int consecutiveFailures,
                                                           int totalExecutions,
                                                           int skippedOverlaps,
                                                           String failureMessage) {
            return new ScheduledTaskStateValue(System.currentTimeMillis(),
                                               nextFireAt,
                                               consecutiveFailures,
                                               totalExecutions,
                                               failureMessage,
                                               System.currentTimeMillis(),
                                               skippedOverlaps);
        }

        /// Records a skipped fixed-rate fire (previous invocation still in flight). Preserves every
        /// other field from `prior` untouched — only `updatedAt` (doubling as "last-skip timestamp")
        /// and `skippedOverlaps` advance. Absent prior state (first-ever fire skipped) starts from zero.
        public static ScheduledTaskStateValue skippedOverlapState(Option<ScheduledTaskStateValue> prior,
                                                                  long skippedAt) {
            return prior.map(p -> new ScheduledTaskStateValue(p.lastExecutionAt(),
                                                              p.nextFireAt(),
                                                              p.consecutiveFailures(),
                                                              p.totalExecutions(),
                                                              p.lastFailureMessage(),
                                                              skippedAt,
                                                              p.skippedOverlaps() + 1))
                        .or(new ScheduledTaskStateValue(0, 0, 0, 0, "", skippedAt, 1));
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

    record ObservabilityConfigValue(String artifactBase,
                                    String methodName,
                                    boolean logging,
                                    boolean metrics,
                                    boolean spans,
                                    boolean tracing,
                                    int depth,
                                    long updatedAt) implements AetherValue {
        public static ObservabilityConfigValue observabilityConfigValue(String artifactBase,
                                                                        String methodName,
                                                                        boolean logging,
                                                                        boolean metrics,
                                                                        boolean spans,
                                                                        boolean tracing,
                                                                        int depth) {
            return new ObservabilityConfigValue(artifactBase,
                                                methodName,
                                                logging,
                                                metrics,
                                                spans,
                                                tracing,
                                                depth,
                                                System.currentTimeMillis());
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

    /// Worker/core activation directive (worker-membership-spec §4 line 79): the leader-authored,
    /// node-keyed role assignment. Extended additively with `communityId` + `governorHint` so a
    /// WORKER directive carries its community assignment and a governor address hint through the
    /// already-canonical directive path — community assignment happens at the same moment as role
    /// assignment. Both are empty for a CORE directive (and for a community-less WORKER).
    ///
    /// Optional-field idiom mirrors [DhtPartitionOwnershipValue.ownerCommunityId]: empty-string is
    /// the canonical "absent" form, normalized in the compact constructor so a `null` from a
    /// wire/codec edge collapses to `""` (preserving `equals` with the role-only constructors).
    record ActivationDirectiveValue(String role, String communityId, String governorHint) implements AetherValue {
        public static final String CORE = "CORE";
        public static final String WORKER = "WORKER";

        public ActivationDirectiveValue {
            if (communityId == null) {
                communityId = "";
            }

            if (governorHint == null) {
                governorHint = "";
            }
        }

        /// Backward-compatible role-only constructor — pre-existing call sites pass a bare role;
        /// `communityId`/`governorHint` default empty (CORE or community-less WORKER semantics).
        public ActivationDirectiveValue(String role) {
            this(role, "", "");
        }

        public static ActivationDirectiveValue core() {
            return new ActivationDirectiveValue(CORE, "", "");
        }

        public static ActivationDirectiveValue worker() {
            return new ActivationDirectiveValue(WORKER, "", "");
        }

        /// Community-assigned WORKER directive — carries the committed `communityId` and a governor
        /// address hint alongside the WORKER role (§4 line 79).
        public static ActivationDirectiveValue worker(String communityId, String governorHint) {
            return new ActivationDirectiveValue(WORKER, communityId, governorHint);
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
                                     boolean dissolved) implements AetherValue, EpochBearing<Epoch> {
        /// Ownership fence (#345 piece 1a): the governor's `communityEpoch` is the fencing token, so
        /// the Rabia applier rejects a deposed governor's strictly-older-epoch announcement. Same-epoch
        /// re-writes (reannounce / dissolve) are accepted — only `withGovernorChange` bumps the epoch.
        @Override
        public Epoch fenceEpoch() {
            return communityEpoch;
        }

        public GovernorAnnouncementValue {
            members = members == null
                      ? List.of()
                      : List.copyOf(members);
            if (tcpAddress == null) {
                tcpAddress = "";
            }

            if (communityEpoch == null) {
                communityEpoch = Epoch.ZERO;
            }

            if (observedCoreEpoch == null) {
                observedCoreEpoch = Epoch.ZERO;
            }

            if (transitionedAt == null) {
                transitionedAt = HlcTimestamp.ZERO;
            }
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

    /// Three-phase model (D.3, 2026-05-11):
    /// - `COLD_BOOT` — cluster never had quorum. SWIM suppresses `FaultyObserved` for
    ///   never-healthy peers (preserves the cold-boot-during-formation invariant).
    ///   MembershipFsm structural bootstrap-safety suppresses STOPPED/DRAINING writes.
    ///   CTM auto-heal is suspended. Transition out: first time the cluster reaches a
    ///   quorum of present peers AND a leader is elected, sustained for `stableWindowMs`.
    /// - `NORMAL` — full failure semantics. No suppression anywhere.
    /// - `RECOVERING` — cluster previously reached NORMAL but lost quorum (e.g.,
    ///   compose-restart, network partition, sustained chaos). SWIM emits FaultyObserved
    ///   with NORMAL semantics — `everSeenHealthy` gate is bypassed because the peer was
    ///   visible-and-healthy in the prior NORMAL period. the leader FSM writes lifecycle
    ///   transitions normally. CTM auto-heal stays suspended (operator-free recovery is
    ///   the goal; provisioning resumes only after stability). Transition back to NORMAL:
    ///   quorum-stable for `recoveryStableWindowMs`.
    @Codec
    enum ClusterPhase {
        COLD_BOOT,
        NORMAL,
        RECOVERING
    }

    record ClusterPhaseValue(ClusterPhase phase, long updatedAt) implements AetherValue {
        public ClusterPhaseValue {
            if (phase == null) {
                phase = ClusterPhase.COLD_BOOT;
            }
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase) {
            return new ClusterPhaseValue(phase, System.currentTimeMillis());
        }

        public static ClusterPhaseValue clusterPhaseValue(ClusterPhase phase, long updatedAt) {
            return new ClusterPhaseValue(phase, updatedAt);
        }
    }

    @Codec
    enum ProvisioningSource {
        CTM,
        MANUAL,
        UNKNOWN
    }

    /// Phase 1 step J — replicated mirror of the in-process `JOIN_DEADLINE` scheduler
    /// entry. `deadlineMs` is wall-clock millis (epoch) when the join deadline fires;
    /// `setAt` is the HLC stamp of the originating JOINING-entry transition (provides
    /// causal ordering across leader takeover for observers reconstructing the deadline).
    /// Pure observability atom — see [AetherKey.JoinDeadlineKey] for trigger semantics.
    record JoinDeadlineValue(long deadlineMs, HlcTimestamp setAt) implements AetherValue {
        public JoinDeadlineValue {
            if (setAt == null) {
                setAt = HlcTimestamp.ZERO;
            }
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
            if (setAt == null) {
                setAt = HlcTimestamp.ZERO;
            }
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
            if (newState == SliceState.ACTIVE) {
                return new NodeArtifactValue(newState, Option.none(), false, instanceNumber, methods, 0L);
            }

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
            if (observedCoreEpoch == null) {
                observedCoreEpoch = Epoch.ZERO;
            }
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

    /// Datasource names are cluster-global (`BlueprintArtifactParser` derives them from the
    /// migration script path, so two blueprints using the default layout both claim `"database"`),
    /// therefore the record must name the blueprint that owns the migration set. Ownership is
    /// REQUIRED, not optional: the deploy-time gate in `BlueprintService` refuses to write a record
    /// for a datasource another blueprint already migrates, and the activation gate in
    /// `ClusterDeploymentState.areSchemasReady` matches records to slices by this owner so one
    /// blueprint's failed migration cannot hold an unrelated blueprint's slices.
    record SchemaVersionValue(String datasourceName,
                              int currentVersion,
                              String lastMigration,
                              SchemaStatus status,
                              String artifactCoords,
                              BlueprintId owningBlueprint,
                              int attemptCount,
                              long updatedAt) implements AetherValue {
        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords,
                                                            BlueprintId owningBlueprint) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          owningBlueprint,
                                          0,
                                          System.currentTimeMillis());
        }

        public static SchemaVersionValue schemaVersionValue(String datasourceName,
                                                            int currentVersion,
                                                            String lastMigration,
                                                            SchemaStatus status,
                                                            String artifactCoords,
                                                            BlueprintId owningBlueprint,
                                                            int attemptCount) {
            return new SchemaVersionValue(datasourceName,
                                          currentVersion,
                                          lastMigration,
                                          status,
                                          artifactCoords,
                                          owningBlueprint,
                                          attemptCount,
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

    @Codec
    enum SchemaStatus {
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

    /// Payload of an [AetherKey.EntityKeyspaceRegistrationKey] — the fact the leader-only ownership
    /// writer cannot derive for itself: how many `(entity:<keyspace>, partition)` arcs the keyspace
    /// spreads over, so it can mint an ownership record for each. Taken from the keyspace's
    /// `DurableEntityConfig.partitionCount` at provisioning time, which is the first moment it is known
    /// (the manifest carries only the config SECTION name, not the section's contents). The OTHER fact
    /// the writer needs — which nodes host the keyspace — lives in the per-node KEY, not here: the set
    /// of committed registration keys IS the hosting set.
    record EntityKeyspaceRegistrationValue(int partitionCount) implements AetherValue {
        public static EntityKeyspaceRegistrationValue entityKeyspaceRegistrationValue(int partitionCount) {
            return new EntityKeyspaceRegistrationValue(partitionCount);
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

    /// `walBytes` (#634-3): live stream-WAL bytes on the reporting node — non-zero only for the
    /// `streams` instance, whose disk footprint was otherwise under-reported by the entire WAL (the
    /// WAL is a sibling directory of the segment store, not a tier).
    record StorageStatusValue(String instanceName,
                              List<TierStatus> tiers,
                              String readinessState,
                              boolean isReadReady,
                              boolean isWriteReady,
                              long lastSnapshotEpoch,
                              long lastSnapshotTimestamp,
                              long walBytes,
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
                                                            long lastSnapshotTimestamp,
                                                            long walBytes) {
            return new StorageStatusValue(instanceName,
                                          List.copyOf(tiers),
                                          readinessState,
                                          isReadReady,
                                          isWriteReady,
                                          lastSnapshotEpoch,
                                          lastSnapshotTimestamp,
                                          walBytes,
                                          System.currentTimeMillis());
        }
    }

    /// Desired cluster shape, per source and per role (RFC-0017 C1).
    ///
    /// `desiredTopology` REPLACES the former stored `coreCount`. That field was a core-only scalar
    /// that scale operations rewrote while leaving `tomlContent` untouched, so after any scale the
    /// two representations of desired size disagreed — and it could not express
    /// "3 cores in hetzner-eu + 5 workers in aws-us" at all, which is what cores need in order to
    /// provision workers themselves.
    ///
    /// [#coreCount] is now DERIVED from this map rather than stored alongside it, so the two can
    /// never drift: there is one authoritative representation and one way to read it.
    ///
    /// The role is carried as a `String` because `aether/slice` deliberately does not depend on
    /// `aether-config` (where `NodeRole` lives); the deployment layer converts at its boundary.
    record TopologyEntry(String sourceName, String role, int count) {
        public static final String CORE_ROLE = "core";

        public static TopologyEntry topologyEntry(String sourceName, String role, int count) {
            return new TopologyEntry(sourceName, role, count);
        }

        public boolean isCore() {
            return CORE_ROLE.equalsIgnoreCase(role);
        }
    }

    record ClusterConfigValue(String tomlContent,
                              String clusterName,
                              String version,
                              List<TopologyEntry> desiredTopology,
                              int coreMin,
                              int coreMax,
                              String deploymentType,
                              long configVersion,
                              long updatedAt) implements AetherValue, VersionFenced {
        public ClusterConfigValue {
            desiredTopology = List.copyOf(desiredTopology);
        }

        /// Lost-update fence version (RFC-0018, #570): the applier rejects a `Put` of this value
        /// unless its `configVersion` is the immediate successor of the committed one. Every write
        /// site therefore derives from the CURRENT committed value and bumps by exactly one — which
        /// all six existing sites already did ([#withDesiredCount] and friends bump; the two
        /// bootstrap seeds write against an absent key, which the fence does not guard). A rejected
        /// write is invisible in the apply result (batch merging), so writers confirm by re-reading
        /// committed state and checking their change landed.
        @Override
        public long fenceVersion() {
            return configVersion;
        }

        /// Derived — never stored. Total CORE nodes across every source.
        public int coreCount() {
            return desiredTopology.stream()
                                  .filter(TopologyEntry::isCore)
                                  .mapToInt(TopologyEntry::count)
                                  .sum();
        }

        /// Desired count for one (source, role), or 0 when the pair is not in the topology.
        public int desiredCountFor(String sourceName, String role) {
            return desiredTopology.stream()
                                  .filter(entry -> entry.sourceName()
                                                        .equals(sourceName) && entry.role()
                                                                                    .equalsIgnoreCase(role))
                                  .mapToInt(TopologyEntry::count)
                                  .findFirst()
                                  .orElse(0);
        }

        /// Sources declaring an entry for `role`, in topology order, without duplicates.
        ///
        /// This is what makes "scale cores to N" answerable without guessing: exactly one source
        /// means the request is unambiguous, several means it genuinely does not say which source
        /// absorbs the change. The former core-only scalar hid that distinction by overwriting a
        /// single number regardless.
        public List<String> sourcesWithRole(String role) {
            return desiredTopology.stream()
                                  .filter(entry -> entry.role()
                                                        .equalsIgnoreCase(role))
                                  .map(TopologyEntry::sourceName)
                                  .distinct()
                                  .toList();
        }

        /// True when the topology already declares this (source, role).
        ///
        /// [#withDesiredCount] APPENDS an absent pair, which is right for composing a topology and
        /// wrong for a scale request: a mistyped source name would silently become a new entry that
        /// provisioning then tries to satisfy. Scale callers gate on this first.
        public boolean declares(String sourceName, String role) {
            return desiredTopology.stream()
                                  .anyMatch(entry -> entry.sourceName()
                                                          .equals(sourceName) && entry.role()
                                                                                      .equalsIgnoreCase(role));
        }

        /// Replace the desired count for one (source, role), preserving every other entry, and bump
        /// the config version. Adds the pair when absent.
        public ClusterConfigValue withDesiredCount(String sourceName, String role, int count) {
            var updated = new ArrayList<TopologyEntry>();
            var replaced = false;

            for (var entry : desiredTopology) {
                if (entry.sourceName().equals(sourceName) && entry.role().equalsIgnoreCase(role)) {
                    updated.add(new TopologyEntry(sourceName, role, count));
                    replaced = true;
                } else {
                    updated.add(entry);
                }
            }

            if (!replaced) {
                updated.add(new TopologyEntry(sourceName, role, count));
            }

            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          List.copyOf(updated),
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion + 1,
                                          System.currentTimeMillis());
        }

        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            List<TopologyEntry> desiredTopology,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion) {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          desiredTopology,
                                          coreMin,
                                          coreMax,
                                          deploymentType,
                                          configVersion,
                                          System.currentTimeMillis());
        }

        public static ClusterConfigValue clusterConfigValue(String tomlContent,
                                                            String clusterName,
                                                            String version,
                                                            List<TopologyEntry> desiredTopology,
                                                            int coreMin,
                                                            int coreMax,
                                                            String deploymentType,
                                                            long configVersion,
                                                            long updatedAt) {
            return new ClusterConfigValue(tomlContent,
                                          clusterName,
                                          version,
                                          desiredTopology,
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
                                          desiredTopology,
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
            return isRevoked()
                   && revokedAt > 0
                   && System.currentTimeMillis() < revokedAt + gracePeriodMs;
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

        @Override
        public byte[] encryptedToken() {
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
                                      HlcTimestamp transferredAt) implements AetherValue, EpochBearing<Epoch> {
        /// Ownership fence (#345 piece 1a): the owner's `ownerEpoch` is the fencing token, so the
        /// Rabia applier rejects a deposed owner's strictly-older-epoch ownership write. The writer
        /// (`BootstrapModule.buildCorePartitionCommand`) couples `ownerEpoch.localCounter ==
        /// ownershipTerm` (#345 DHT parity), so a stale-owner takeover advances the `ownerEpoch` (via
        /// the bumped `ownershipTerm` local counter) and STRICTLY dominates the deposed owner's epoch —
        /// even within the same generation term, closing the same-term-takeover fence gap.
        @Override
        public Epoch fenceEpoch() {
            return ownerEpoch;
        }

        public DhtPartitionOwnershipValue {
            if (ownerCommunityId == null) {
                ownerCommunityId = "";
            }

            if (ownerEpoch == null) {
                ownerEpoch = Epoch.ZERO;
            }

            if (transferredAt == null) {
                transferredAt = HlcTimestamp.ZERO;
            }
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

    /// Per-`(stream, partition)` ownership record (#345 item 1d-i) — the stream-side mirror of
    /// [DhtPartitionOwnershipValue], and the first persisted slice of #265's reshuffle ring.
    /// Stream-partition ownership was previously pure HRW recomputed on the fly with no persisted
    /// record and no fencing token; this record gives the partition's owner an `ownerEpoch` that the
    /// leader advances on every owner change, so the append fence (1d-ii) can reject a deposed owner.
    ///
    /// There is no `ownerCommunityId` — streams have no community arc (that field is DHT-specific). The
    /// `ownerEpoch` is sourced from the committed generation epoch (`Epoch.epoch(rabiaTerm, 0)`); the
    /// `ownershipTerm` is a monotonic per-partition takeover counter, bumped on each owner change.
    record StreamPartitionOwnershipValue(NodeId owner,
                                         Epoch ownerEpoch,
                                         long ownershipTerm,
                                         HlcTimestamp transferredAt) implements AetherValue, EpochBearing<Epoch> {
        /// Ownership fence (#345 piece 1a): the owner's `ownerEpoch` is the fencing token, so the Rabia
        /// applier rejects a deposed owner's strictly-older-epoch ownership write for free (it fences
        /// ANY `EpochBearing` value). A stale-owner takeover at the same epoch (bumping only
        /// `ownershipTerm`) is accepted, mirroring `DhtPartitionOwnershipValue`.
        @Override
        public Epoch fenceEpoch() {
            return ownerEpoch;
        }

        public StreamPartitionOwnershipValue {
            if (ownerEpoch == null) {
                ownerEpoch = Epoch.ZERO;
            }

            if (transferredAt == null) {
                transferredAt = HlcTimestamp.ZERO;
            }
        }

        public static StreamPartitionOwnershipValue streamPartitionOwnershipValue(NodeId owner,
                                                                                  Epoch ownerEpoch,
                                                                                  long ownershipTerm,
                                                                                  HlcTimestamp transferredAt) {
            return new StreamPartitionOwnershipValue(owner, ownerEpoch, ownershipTerm, transferredAt);
        }
    }

    @Codec
    enum SpokesmanStatus {
        ASSIGNED,
        ACTIVE,
        FAILED
    }

    /// Desired-state community record (worker-membership-spec §2 line 78): the leader-authored
    /// target for a committed [AetherKey.CommunityKey]. `state` is the per-community FSM state
    /// (leader-evaluated, §3.3). `dissolvedAt` is present only once the community reaches the
    /// `DISSOLVED` terminal fact; absent (`none()`) for every live state.
    ///
    /// Mirrors [DhtPartitionOwnershipValue] for optional-field canonicalization: empty Option /
    /// empty string are the canonical "absent" forms, normalized in the compact constructor so a
    /// `null` from a wire/codec edge collapses to the same value (and the same `equals`).
    record CommunityValue(String sourceName,
                          String role,
                          int targetSize,
                          CommunityState state,
                          long createdAt,
                          Option<Long> dissolvedAt) implements AetherValue {
        public CommunityValue {
            if (sourceName == null) {
                sourceName = "";
            }

            if (role == null) {
                role = "";
            }

            if (state == null) {
                state = CommunityState.FORMING;
            }

            if (dissolvedAt == null) {
                dissolvedAt = Option.none();
            }
        }

        public static CommunityValue communityValue(String sourceName,
                                                    String role,
                                                    int targetSize,
                                                    CommunityState state,
                                                    long createdAt,
                                                    Option<Long> dissolvedAt) {
            return new CommunityValue(sourceName, role, targetSize, state, createdAt, dissolvedAt);
        }

        /// FORMING mint — the leader creating a fresh community (growth policy demands a new slot,
        /// §3.3): stamps `createdAt = now`, `state = FORMING`, `dissolvedAt = none()`.
        public static CommunityValue communityValue(String sourceName, String role, int targetSize) {
            return new CommunityValue(sourceName,
                                      role,
                                      targetSize,
                                      CommunityState.FORMING,
                                      System.currentTimeMillis(),
                                      none());
        }

        /// Per-community FSM transition (worker-membership-spec §3.3): the leader re-stamps only the
        /// `state` on an edge, preserving every other committed field. Mirrors
        /// [NodeArtifactValue#withState] — a copy via the canonical constructor.
        public CommunityValue withState(CommunityState newState) {
            return new CommunityValue(sourceName, role, targetSize, newState, createdAt, dissolvedAt);
        }
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
    /// Backward compatibility (legacy slot-based-membership convergence, §4.2; spec removed, see git history): the legacy
    /// construction sites that passed a `deadlineMs` argument still compile via the deadline-arg
    /// constructors and `provisioningSlotValue(..)` factories below, which discard the now-derived
    /// deadline. Mirrors the trailing-field backward-compat pattern used across `AetherValue`.
    record ProvisioningSlotValue(long spawnedAtMs,
                                 Option<NodeId> assignedNodeId,
                                 long occupantEpoch,
                                 Option<NodeId> supersededNodeId) implements AetherValue {
        public ProvisioningSlotValue {
            if (assignedNodeId == null) {
                assignedNodeId = Option.none();
            }

            if (supersededNodeId == null) {
                supersededNodeId = Option.none();
            }
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

        public static ProvisioningSlotValue provisioningSlotValue(long spawnedAtMs, NodeId assignedNodeId) {
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
            if (assignedEpoch == null) {
                assignedEpoch = Epoch.ZERO;
            }

            if (assignedAt == null) {
                assignedAt = HlcTimestamp.ZERO;
            }

            if (status == null) {
                status = SpokesmanStatus.ASSIGNED;
            }

            if (failureReason == null) {
                failureReason = "";
            }
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

    // Stage 1 (stream-namespaces) additive graft: stream-registry value. Added alongside the
    // retained ClusterEventValue (cluster-events replacement is a later stage).
    /// Consensus-replicated form of [StreamRegistryEntry].
    ///
    /// Single-key collapse of the spec's `stream-meta:{addr}` and `stream-refs:{addr}` (§7.1, §7.2)
    /// — implementation choice for atomic refcount mutation in the same consensus round as the
    /// SliceNodeValue update (§8.5). The conceptual separation in the spec is preserved at the
    /// reader/writer API but the wire form is one record.
    record StreamRegistryValue(StreamRegistryEntry entry) implements AetherValue {
        public static StreamRegistryValue streamRegistryValue(StreamRegistryEntry entry) {
            return new StreamRegistryValue(entry);
        }
    }

    // Stage 2 (stream-namespaces) additive graft: per-blueprint resolved alias->ResourceAddress map.
    /// Per-blueprint resolved alias→`ResourceAddress` map persisted at deploy time.
    ///
    /// Kept as `List<NamedAddress>` instead of `Map<String, ResourceAddress>` so the compile-time
    /// codec processor doesn't have to handle a `Map<K, V>` where `V` is a record-typed codec
    /// element (only `Map<String, String>` is exercised by the processor today; List-of-record
    /// is explicitly tested).
    ///
    /// Spec reference: event-stream-namespaces §8.5 (resolved address required for slice-time
    /// refcount accounting).
    record BlueprintStreamBindingsValue(List<NamedAddress> bindings) implements AetherValue {
        public BlueprintStreamBindingsValue {
            bindings = bindings == null
                       ? List.of()
                       : List.copyOf(bindings);
        }

        public static BlueprintStreamBindingsValue blueprintStreamBindingsValue(List<NamedAddress> bindings) {
            return new BlueprintStreamBindingsValue(bindings);
        }

        public Option<ResourceAddress> addressFor(String alias) {
            return Option.option(bindings.stream().filter(b -> b.alias()
                                                                .equals(alias)).findFirst().orElse(null)).map(NamedAddress::address);
        }

        public record NamedAddress(String alias, ResourceAddress address) {
            public static NamedAddress namedAddress(String alias, ResourceAddress address) {
                return new NamedAddress(alias, address);
            }
        }
    }

    /// Payload of an [AetherKey.EntityCheckpointKey] — where a partition's folded state lives and how far
    /// forward it accounts for (#345 I3).
    ///
    /// `blockIdHex` names a block in the node's stream storage, whose tier chain ends in a DHT tier, so
    /// any node can fetch it. `throughOffset` is the LAST log offset folded into that block: a recovering
    /// owner loads the block and then replays from `throughOffset + 1`.
    ///
    /// The offset is what makes this safe to act on. A recovering node compares `throughOffset + 1`
    /// against the earliest offset it can still read, and refuses when the two do not meet — see
    /// `EntityLogSubstrate#earliestRetainedOffset`. Storing the block id without the offset would leave a
    /// reader unable to tell a complete recovery from one silently missing every mutation in the gap.
    ///
    /// ## Why the name says "Fold" — historical, and now load-bearing for a different reason
    /// This type was named to dodge a tag collision. Codec tags were derived purely by hashing the
    /// fully-qualified type name into a 16256-slot space, and the obvious name, `EntityCheckpointValue`,
    /// hashed to 7612 — already claimed by `org.pragmatica.cluster.metrics.HealthHintWire`. Registering
    /// both threw at `NodeCodecs` static init and poisoned every test that touched it.
    ///
    /// That derivation is gone. System types now carry hand-assigned tags in
    /// `org.pragmatica.serialization.SystemTags`, so this type's tag no longer depends on its name and
    /// the collision cannot recur. The name still matters, but the reason inverted: the SystemTags key
    /// IS the fully-qualified name, so a rename leaves the entry unmatched, drops the type into the
    /// hashed user range, and fails the build at `SliceCodec#systemCodec`. Renaming is therefore a
    /// deliberate two-step — rename, then re-key — and a tag, once assigned, is never renumbered.
    ///
    /// @param throughOffset last log offset folded into the snapshot
    /// @param blockIdHex    content id of the snapshot block in stream storage
    /// @param timestamp     wall-clock ms the checkpoint was written, for operator diagnosis only —
    ///                      never for ordering, which is `throughOffset`'s job
    /// [org.pragmatica.cluster.state.kvstore.MonotonicFenced]: the checkpoint claim is a running
    /// max — the retention floor reclaims log segments below it, so the applier refuses a Put that
    /// would LOWER the committed `throughOffset` (#700; a lower honest claim landing after a higher
    /// one would leave the records between them on no reachable node). Equal offsets are accepted:
    /// a fresh snapshot at unchanged coverage replaces the block pointer harmlessly.
    record EntityFoldCheckpointValue(long throughOffset, String blockIdHex, long timestamp) implements AetherValue, org.pragmatica.cluster.state.kvstore.MonotonicFenced {
        @Override
        public long fenceWatermark() {
            return throughOffset;
        }

        public static EntityFoldCheckpointValue entityFoldCheckpointValue(long throughOffset, String blockIdHex) {
            return new EntityFoldCheckpointValue(throughOffset, blockIdHex, System.currentTimeMillis());
        }
    }
}
