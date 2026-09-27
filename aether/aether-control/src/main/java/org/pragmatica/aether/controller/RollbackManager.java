// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.update.DeploymentState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public interface RollbackManager {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    boolean isActive();

    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @MessageReceiver
    void onPreviousVersionPut(ValuePut<PreviousVersionKey, PreviousVersionValue> valuePut);

    @MessageReceiver
    void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event);

    Option<RollbackStats> getStats(ArtifactBase artifactBase);
    void resetRollbackCount(ArtifactBase artifactBase);

    /// Per-artifact-base rollback bookkeeping. Everything but `lastRolledBackFrom`/`lastRolledBackTo` is
    /// rebuilt from the committed [PreviousVersionValue], so cooldown, maxRollbacks, the failed-version
    /// record and the bake-window anchor (`currentSince`) survive a leader change (#1573).
    record RollbackState(ArtifactBase artifactBase,
                         Option<Version> previousVersion,
                         Version currentVersion,
                         int rollbackCount,
                         long lastRollbackTimestamp,
                         Option<Version> lastRolledBackFrom,
                         Option<Version> lastRolledBackTo,
                         Set<Version> failedVersions,
                         long currentSince) {
        public RollbackState {
            failedVersions = Set.copyOf(failedVersions);
        }

        public static RollbackState initial(ArtifactBase artifactBase, Version currentVersion) {
            return new RollbackState(artifactBase,
                                     Option.none(),
                                     currentVersion,
                                     0,
                                     0,
                                     Option.none(),
                                     Option.none(),
                                     Set.of(),
                                     System.currentTimeMillis());
        }

        public static RollbackState fromKVStore(ArtifactBase artifactBase,
                                                Version previousVersion,
                                                Version currentVersion) {
            return fromKVStore(PreviousVersionValue.previousVersionValue(artifactBase, previousVersion, currentVersion));
        }

        public static RollbackState fromKVStore(PreviousVersionValue value) {
            return new RollbackState(value.artifactBase(),
                                     Option.some(value.previousVersion()),
                                     value.currentVersion(),
                                     value.rollbackCount(),
                                     value.lastRollbackAt(),
                                     Option.none(),
                                     Option.none(),
                                     Set.copyOf(value.failedVersions()),
                                     value.updatedAt());
        }

        public RollbackState withVersionChange(Version newVersion) {
            return new RollbackState(artifactBase,
                                     Option.some(currentVersion),
                                     newVersion,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     lastRolledBackFrom,
                                     lastRolledBackTo,
                                     failedVersions,
                                     System.currentTimeMillis());
        }

        public RollbackState withRollbackCompleted(Version failedVersion, Version targetVersion, long timestamp) {
            var failed = new HashSet<>(failedVersions);

            failed.add(failedVersion);

            return new RollbackState(artifactBase,
                                     Option.some(failedVersion),
                                     targetVersion,
                                     rollbackCount + 1,
                                     timestamp,
                                     Option.some(failedVersion),
                                     Option.some(targetVersion),
                                     failed,
                                     timestamp);
        }

        public RollbackState withReset() {
            return new RollbackState(artifactBase,
                                     previousVersion,
                                     currentVersion,
                                     0,
                                     0,
                                     Option.none(),
                                     Option.none(),
                                     failedVersions,
                                     currentSince);
        }

        /// Rebuild from the committed value, keeping only the in-memory last-rollback details.
        public RollbackState withKVStoreUpdate(PreviousVersionValue value) {
            var rebuilt = fromKVStore(value);

            return new RollbackState(artifactBase,
                                     rebuilt.previousVersion(),
                                     rebuilt.currentVersion(),
                                     rebuilt.rollbackCount(),
                                     rebuilt.lastRollbackTimestamp(),
                                     lastRolledBackFrom,
                                     lastRolledBackTo,
                                     rebuilt.failedVersions(),
                                     rebuilt.currentSince());
        }

        /// #1573 order of checks: the bake window first (a failure outside it is an incident, never a
        /// bad deploy), then a target to roll back to, never one that already failed (no oscillation),
        /// then cooldown and the rollback budget.
        public Result<RollbackDecision> canRollback(RollbackConfig config, long currentTime) {
            if (currentTime - currentSince > config.bakeWindow().millis()) {
                return RollbackError.General.OUTSIDE_BAKE_WINDOW.result();
            }

            if (previousVersion.isEmpty()) {
                return RollbackError.General.NO_PREVIOUS_VERSION.result();
            }

            if (failedVersions.contains(previousVersion.unwrap())) {
                return RollbackError.General.TARGET_PREVIOUSLY_FAILED.result();
            }

            var cooldownMs = config.cooldown().millis();

            if (lastRollbackTimestamp > 0 && (currentTime - lastRollbackTimestamp) < cooldownMs) {
                return RollbackError.General.COOLDOWN_ACTIVE.result();
            }

            if (rollbackCount >= config.maxRollbacks()) {
                return RollbackError.General.MAX_ROLLBACKS_EXCEEDED.result();
            }

            return Result.success(RollbackDecision.rollbackDecision(previousVersion.unwrap(),
                                                                    currentVersion,
                                                                    rollbackCount + 1));
        }

        public RollbackStats toStats() {
            return new RollbackStats(artifactBase,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     lastRolledBackFrom,
                                     lastRolledBackTo);
        }
    }

    record RollbackDecision(Version targetVersion, Version failedVersion, int rollbackNumber) {
        public static RollbackDecision rollbackDecision(Version target, Version failed, int num) {
            return new RollbackDecision(target, failed, num);
        }
    }

    sealed interface RollbackError extends Cause {
        enum General implements RollbackError {
            NOT_LEADER("Rollback skipped: not leader"),
            DISABLED("Rollback is disabled in configuration"),
            NO_PREVIOUS_VERSION("No previous version available for rollback"),
            COOLDOWN_ACTIVE("Rollback skipped: cooldown period active"),
            MAX_ROLLBACKS_EXCEEDED("Rollback skipped: maximum rollbacks exceeded, manual intervention required"),
            OUTSIDE_BAKE_WINDOW("Rollback skipped: the version is past its bake window; treated as an incident, not a bad deploy"),
            TARGET_PREVIOUSLY_FAILED("Rollback skipped: the rollback target already failed once; manual intervention required"),
            STALE_EVENT("Rollback skipped: the failed version is no longer the target"),
            DEPLOYMENT_IN_PROGRESS("Rollback skipped: a managed deployment owns this artifact");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record RollbackFailed(Artifact artifact, Cause cause) implements RollbackError {
            @Override
            public String message() {
                return "Rollback failed for " + artifact + ": " + cause.message();
            }
        }
    }

    record RollbackStats(ArtifactBase artifactBase,
                         int rollbackCount,
                         long lastRollbackTimestamp,
                         Option<Version> lastRolledBackFrom,
                         Option<Version> lastRolledBackTo) {}

    static RollbackManager rollbackManager(NodeId self,
                                           RollbackConfig config,
                                           ClusterNode<KVCommand<AetherKey>> cluster,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           LeaderManager leaderManager) {
        record rollbackManager(NodeId self,
                               RollbackConfig config,
                               ClusterNode<KVCommand<AetherKey>> cluster,
                               KVStore<AetherKey, AetherValue> kvStore,
                               LeaderManager leaderManager,
                               ConcurrentHashMap<ArtifactBase, RollbackState> rollbackStates,
                               Logger log) implements RollbackManager {
            @Override
            public Promise<Unit> activate() {
                log.info("Node {} activating RollbackManager", self);
                loadPreviousVersionsFromKvStore();

                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> deactivate() {
                log.info("Node {} deactivating RollbackManager", self);
                rollbackStates.clear();

                return Promise.unitPromise();
            }

            @Override
            public boolean isActive() {
                return leaderManager.isLeader();
            }

            @Override
            public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
                trackVersionChange(valuePut.cause().key().artifactBase(),
                                   valuePut.cause().value());
            }

            @Override
            public void onPreviousVersionPut(ValuePut<PreviousVersionKey, PreviousVersionValue> valuePut) {
                updateLocalPreviousVersion(valuePut.cause().key().artifactBase(),
                                           valuePut.cause().value());
            }

            @Override
            public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
                if (!config.enabled()) {
                    log.debug("Rollback disabled, ignoring AllInstancesFailed for {}", event.artifact());

                    return;
                }

                if (!config.triggerOnAllInstancesFailed()) {
                    log.debug("Rollback on AllInstancesFailed disabled, ignoring event for {}", event.artifact());

                    return;
                }

                if (!leaderManager.isLeader()) {
                    log.debug("Not leader, skipping rollback decision for {}", event.artifact());

                    return;
                }

                var artifactBase = event.artifact().base();

                Option.option(rollbackStates.get(artifactBase))
                      .onPresent(state -> decide(event, state))
                      .onEmpty(() -> log.warn("[requestId={}] No previous version tracked for {}, cannot rollback",
                                              event.requestId(),
                                              event.artifact()));
            }

            /// #1573: a stale event (the failed version is no longer the target) and a managed deployment
            /// owning the artifact both skip before the state's own checks.
            @Contract
            private void decide(SliceFailureEvent.AllInstancesFailed event, RollbackState state) {
                var failedArtifact = event.artifact();

                eligibility(failedArtifact, state).flatMap(_ -> state.canRollback(config, System.currentTimeMillis()))
                                                  .onFailure(cause -> logRollbackSkipped(cause,
                                                                                         event.requestId(),
                                                                                         failedArtifact))
                                                  .onSuccess(decision -> executeRollback(failedArtifact,
                                                                                         decision,
                                                                                         event.requestId()));
            }

            private Result<Unit> eligibility(Artifact failedArtifact, RollbackState state) {
                if (!state.currentVersion()
                          .equals(failedArtifact.version())) {
                    return RollbackError.General.STALE_EVENT.result();
                }

                return deploymentInProgress(failedArtifact.base())
                       ? RollbackError.General.DEPLOYMENT_IN_PROGRESS.result()
                       : Result.unitResult();
            }

            /// A non-terminal managed deployment (rolling, canary, blue-green) covering `artifactBase` owns
            /// its own health gate and rollback; read from KV so it holds on whichever node leads.
            private boolean deploymentInProgress(ArtifactBase artifactBase) {
                var inProgress = new AtomicBoolean(false);

                kvStore.forEach(DeploymentKey.class,
                                DeploymentValue.class,
                                (_, value) -> inProgress.compareAndSet(false,
                                                                       activeDeploymentCovers(value, artifactBase)));

                return inProgress.get();
            }

            private static boolean activeDeploymentCovers(DeploymentValue value, ArtifactBase artifactBase) {
                return !isTerminal(value.state()) && Arrays.stream(value.artifacts()
                                                                                                 .split(","))
                                                                                    .map(String::trim)
                                                                                    .anyMatch(artifactBase.asString()::equals);
            }

            private static boolean isTerminal(String state) {
                return Arrays.stream(DeploymentState.values())
                             .filter(DeploymentState::isTerminal)
                             .anyMatch(terminal -> terminal.name()
                                                           .equals(state));
            }

            @Override
            public Option<RollbackStats> getStats(ArtifactBase artifactBase) {
                return Option.option(rollbackStates.get(artifactBase)).map(RollbackState::toStats);
            }

            @Override
            public void resetRollbackCount(ArtifactBase artifactBase) {
                rollbackStates.computeIfPresent(artifactBase, (_, state) -> resetState(artifactBase, state));
                previousVersionValue(artifactBase).onPresent(value -> putPreviousVersion(value.withRollbackCountReset()));
            }

            private RollbackState resetState(ArtifactBase artifactBase, RollbackState state) {
                log.info("Rollback count reset for {}", artifactBase);

                return state.withReset();
            }

            private void loadPreviousVersionsFromKvStore() {
                kvStore.forEach(AetherKey.class, AetherValue.class, this::loadPreviousVersionEntry);
                log.debug("Loaded {} previous version entries from KVStore", rollbackStates.size());
            }

            private void loadPreviousVersionEntry(AetherKey key, AetherValue value) {
                if (key instanceof PreviousVersionKey previousVersionKey && value instanceof PreviousVersionValue previousVersionValue) {
                    updateLocalPreviousVersion(previousVersionKey.artifactBase(), previousVersionValue);
                }
            }

            private void updateLocalPreviousVersion(ArtifactBase artifactBase, PreviousVersionValue value) {
                rollbackStates.compute(artifactBase, (_, existing) -> computePreviousVersionUpdate(existing, value));
            }

            private static RollbackState computePreviousVersionUpdate(RollbackState existing, PreviousVersionValue value) {
                return Option.option(existing)
                             .map(state -> state.withKVStoreUpdate(value))
                             .or(() -> RollbackState.fromKVStore(value));
            }

            private Option<PreviousVersionValue> previousVersionValue(ArtifactBase artifactBase) {
                return kvStore.get(PreviousVersionKey.previousVersionKey(artifactBase))
                              .filter(PreviousVersionValue.class::isInstance)
                              .map(PreviousVersionValue.class::cast);
            }

            private void trackVersionChange(ArtifactBase artifactBase, SliceTargetValue sliceTargetValue) {
                if (!leaderManager.isLeader()) {
                    return;
                }

                var currentVersion = sliceTargetValue.currentVersion();

                rollbackStates.compute(artifactBase,
                                       (ab, existing) -> computeVersionTracking(ab, existing, currentVersion));
            }

            private RollbackState computeVersionTracking(ArtifactBase ab, RollbackState existing, Version currentVersion) {
                return Option.option(existing)
                             .map(state -> computeVersionChange(state, ab, currentVersion))
                             .or(() -> initialDeploymentState(ab, currentVersion));
            }

            private RollbackState initialDeploymentState(ArtifactBase ab, Version currentVersion) {
                log.debug("First deployment of {}, no previous version to track", ab);

                return RollbackState.initial(ab, currentVersion);
            }

            /// #1573: the committed value is authoritative. A rollback commits its PreviousVersionValue in the
            /// same batch as (and ahead of) the SliceTarget put, so by the time this runs for a rollback the
            /// value already names the new target and nothing is rewritten — the rollback history is never
            /// clobbered by the version change it caused.
            private RollbackState computeVersionChange(RollbackState state, ArtifactBase artifactBase, Version newVersion) {
                if (state.currentVersion()
                         .equals(newVersion)) {
                    return state;
                }

                log.info("Version change detected for {}: {} -> {}", artifactBase, state.currentVersion(), newVersion);

                var committed = previousVersionValue(artifactBase);

                if (committed.filter(value -> value.currentVersion()
                                                   .equals(newVersion))
                             .isPresent()) {
                    return state.withKVStoreUpdate(committed.unwrap());
                }

                var next = committed.map(value -> value.withVersionChange(newVersion, System.currentTimeMillis()))
                                    .or(() -> PreviousVersionValue.previousVersionValue(artifactBase,
                                                                                        state.currentVersion(),
                                                                                        newVersion));

                putPreviousVersion(next);

                return state.withKVStoreUpdate(next);
            }

            @Contract
            private void putPreviousVersion(PreviousVersionValue value) {
                var command = new KVCommand.Put<AetherKey, AetherValue>(PreviousVersionKey.previousVersionKey(value.artifactBase()),
                                                                        value);

                cluster.apply(List.of(command))
                       .onSuccess(_ -> log.debug("Stored rollback record for {}: previous={} current={}",
                                                 value.artifactBase(),
                                                 value.previousVersion(),
                                                 value.currentVersion()))
                       .onFailure(cause -> log.error("Failed to store rollback record for {}: {}",
                                                     value.artifactBase(),
                                                     cause.message()));
            }

            @Contract
            private void logRollbackSkipped(Cause cause, String requestId, Artifact artifact) {
                switch (cause) {
                    case RollbackError.General.NO_PREVIOUS_VERSION -> log.warn("[requestId={}] No previous version available for {}, cannot rollback",
                                                                               requestId,
                                                                               artifact);
                    case RollbackError.General.COOLDOWN_ACTIVE -> log.warn("[requestId={}] Rollback cooldown active for {}. Skipping rollback.",
                                                                           requestId,
                                                                           artifact);
                    case RollbackError.General.MAX_ROLLBACKS_EXCEEDED -> log.error("[requestId={}] CRITICAL: Max rollbacks ({}) exceeded for {}. Manual intervention required.",
                                                                                   requestId,
                                                                                   config.maxRollbacks(),
                                                                                   artifact);
                    case RollbackError.General.OUTSIDE_BAKE_WINDOW -> log.warn("[requestId={}] All instances of {} failing outside its {} bake window: "
                                                                               + "alerting only, NOT rolling back (incident, not a bad deploy)",
                                                                               requestId,
                                                                               artifact,
                                                                               config.bakeWindow());
                    case RollbackError.General.TARGET_PREVIOUSLY_FAILED -> log.error("[requestId={}] CRITICAL: rollback target for {} already failed once; "
                                                                                     + "not rolling back. Manual intervention required.",
                                                                                     requestId,
                                                                                     artifact);
                    default -> log.warn("[requestId={}] Rollback skipped for {}: {}", requestId, artifact, cause.message());
                }
            }

            @Contract
            private void executeRollback(Artifact failedArtifact, RollbackDecision decision, String requestId) {
                var rollbackArtifact = Artifact.artifact(failedArtifact.base(), decision.targetVersion());

                log.warn("[requestId={}] INITIATING ROLLBACK: {} -> {} (rollback #{} of max {})",
                         requestId,
                         failedArtifact,
                         rollbackArtifact,
                         decision.rollbackNumber(),
                         config.maxRollbacks());
                commitRollback(rollbackArtifact, decision, requestId);
            }

            /// #1573: the rollback record goes FIRST in the same batch as the SliceTarget put, so the
            /// version-change notification the put raises already sees it (see [#computeVersionChange]).
            @Contract
            private void commitRollback(Artifact rollbackArtifact, RollbackDecision decision, String requestId) {
                var artifactBase = rollbackArtifact.base();
                var now = System.currentTimeMillis();
                var target = kvStore.get(SliceTargetKey.sliceTargetKey(artifactBase))
                                    .filter(SliceTargetValue.class::isInstance)
                                    .map(SliceTargetValue.class::cast)
                                    .map(current -> current.withVersion(decision.targetVersion()))
                                    .or(SliceTargetValue.sliceTargetValue(decision.targetVersion(), 1));
                var record = previousVersionValue(artifactBase).or(() -> PreviousVersionValue.previousVersionValue(artifactBase,
                                                                                                                   decision.targetVersion(),
                                                                                                                   decision.failedVersion()))
                                                               .withRollback(decision.failedVersion(),
                                                                             decision.targetVersion(),
                                                                             now);
                List<KVCommand<AetherKey>> batch = List.of(new KVCommand.Put<AetherKey, AetherValue>(PreviousVersionKey.previousVersionKey(artifactBase),
                                                                               record),
                                                           new KVCommand.Put<AetherKey, AetherValue>(SliceTargetKey.sliceTargetKey(artifactBase),
                                                                               target));

                cluster.apply(batch)
                       .onSuccess(_ -> recordRollbackCompleted(artifactBase, decision, requestId, rollbackArtifact, now))
                       .onFailure(cause -> log.error("[requestId={}] ROLLBACK FAILED: Could not update slice target for {}: {}",
                                                     requestId,
                                                     rollbackArtifact,
                                                     cause.message()));
            }

            @Contract
            private void recordRollbackCompleted(ArtifactBase artifactBase,
                                                 RollbackDecision decision,
                                                 String requestId,
                                                 Artifact rollbackArtifact,
                                                 long timestamp) {
                rollbackStates.computeIfPresent(artifactBase,
                                                (_, state) -> state.withRollbackCompleted(decision.failedVersion(),
                                                                                          decision.targetVersion(),
                                                                                          timestamp));
                log.info("[requestId={}] ROLLBACK INITIATED: SliceTarget updated to {}", requestId, rollbackArtifact);
            }
        }

        var manager = new rollbackManager(self,
                                          config,
                                          cluster,
                                          kvStore,
                                          leaderManager,
                                          new ConcurrentHashMap<>(),
                                          LoggerFactory.getLogger(RollbackManager.class));

        manager.loadPreviousVersionsFromKvStore();

        return manager;
    }

    static RollbackManager disabled() {
        return Disabled.INSTANCE;
    }

    enum Disabled implements RollbackManager {
        INSTANCE;
        private static final Logger log = LoggerFactory.getLogger(RollbackManager.class);
        @Override
        public Promise<Unit> activate() {
            return Promise.unitPromise();
        }
        @Override
        public Promise<Unit> deactivate() {
            return Promise.unitPromise();
        }
        @Override
        public boolean isActive() {
            return false;
        }
        @Override
        public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {}
        @Override
        public void onPreviousVersionPut(ValuePut<PreviousVersionKey, PreviousVersionValue> valuePut) {}
        @Override
        public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
            log.debug("Rollback disabled, ignoring AllInstancesFailed for {}", event.artifact());
        }
        @Override
        public Option<RollbackStats> getStats(ArtifactBase artifactBase) {
            return Option.none();
        }
        @Override
        public void resetRollbackCount(ArtifactBase artifactBase) {}
    }
}
