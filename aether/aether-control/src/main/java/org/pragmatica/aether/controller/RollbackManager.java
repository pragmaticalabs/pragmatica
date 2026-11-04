// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.update.DeploymentState;
import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
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
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

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

    /// D1-react (KVStore dispatcher design §3.5.1 row 19): the executor the record derivation of [#onSliceTargetPut], and
    /// every refusal retry of a derived write, run on. A node installs its gated executor once, at assembly; until then
    /// (and in a host that installs none) both run inline, as before D1-react. Default no-op for [#disabled].
    default Unit installReactExecutor(ReactExecutor executor) {
        return Unit.unit();
    }

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

        /// The in-memory details of a committed rollback, and nothing else: the counters, the versions and the bake anchor arrive
        /// from the committed record through [#withKVStoreUpdate] (D1-react: the mirror holds only committed values).
        public RollbackState withRolledBack(Version failedVersion, Version targetVersion) {
            return new RollbackState(artifactBase,
                                     previousVersion,
                                     currentVersion,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     Option.some(failedVersion),
                                     Option.some(targetVersion),
                                     failedVersions,
                                     currentSince);
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

    /// What triggered a derivation of the rollback record (#2613). The record is always re-derived from the
    /// committed target and record; the context only decides which previous version the derivation may name.
    sealed interface DerivationContext {
        /// A delivered SliceTarget put: the value it carried and the value it replaced.
        record Notification(SliceTargetValue value, Option<SliceTargetValue> oldValue) implements DerivationContext {}

        /// A refused write's own (previous, current) pair, and the artifact's version-change count when that write was derived.
        record Retry(Version previous, Version current, long versionChanges) implements DerivationContext {}

        /// A new leader's activation: no notification names the change.
        enum Activation implements DerivationContext {
            INSTANCE
        }
    }

    record RollbackStats(ArtifactBase artifactBase,
                         int rollbackCount,
                         long lastRollbackTimestamp,
                         Option<Version> lastRolledBackFrom,
                         Option<Version> lastRolledBackTo) {}

    /// A fixed policy, no rollback reporter and no operator-warning sink — for tests and embedding only; a node wires
    /// [#rollbackManager(NodeId, Supplier, ClusterNode, KVStore, LeaderManager, Consumer, OperatorWarningSink)].
    static RollbackManager rollbackManager(NodeId self,
                                           RollbackConfig config,
                                           ClusterNode<KVCommand<AetherKey>> cluster,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           LeaderManager leaderManager) {
        return rollbackManager(self,
                               () -> config,
                               cluster,
                               kvStore,
                               leaderManager,
                               RollbackManager::ignoreRollback,
                               OperatorWarningSink.logOnly());
    }

    @Contract
    private static void ignoreRollback(RollbackEvent.AutoRollbackExecuted executed) {}

    /// #1573: `policy` is the committed cluster-wide `[rollback]` section, read at every decision so a committed
    /// change (e.g. `enabled = false`) takes effect without a restart. `reporter` receives every committed
    /// rollback, with its evidence, for the CRITICAL cluster event. `warnings` receives the operator warning raised when
    /// the rollback record cannot be reconciled with its SliceTarget (#2613).
    static RollbackManager rollbackManager(NodeId self,
                                           Supplier<RollbackConfig> policy,
                                           ClusterNode<KVCommand<AetherKey>> cluster,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           LeaderManager leaderManager,
                                           Consumer<RollbackEvent.AutoRollbackExecuted> reporter,
                                           OperatorWarningSink warnings) {
        record rollbackManager(NodeId self,
                               Supplier<RollbackConfig> policy,
                               Consumer<RollbackEvent.AutoRollbackExecuted> reporter,
                               ClusterNode<KVCommand<AetherKey>> cluster,
                               KVStore<AetherKey, AetherValue> kvStore,
                               LeaderManager leaderManager,
                               ConcurrentHashMap<ArtifactBase, RollbackState> rollbackStates,
                               Set<ArtifactBase> exhausted,
                               ConcurrentHashMap<ArtifactBase, Long> versionChanges,
                               OperatorWarningSink warnings,
                               AtomicReference<ReactExecutor> react,
                               Logger log) implements RollbackManager {
            /// Attempts per refusal chain: the first write and two re-derivations. A guess (taste); settle by the contention test.
            private static final int MAX_RECORD_ATTEMPTS = 3;

            /// The committed target and record a derivation read.
            record Observed(ArtifactBase base, SliceTargetValue target, Option<PreviousVersionValue> record) {
                boolean isRecorded() {
                    return record.filter(value -> value.currentVersion()
                                                       .equals(target.currentVersion()))
                                 .isPresent();
                }

                /// The record names exactly the change (previous, the target's version). A version-only check would take a record
                /// that names the target's version from an EARLIER episode (the revert B -> A over a stale (X, A)) as current.
                boolean isRecordedAs(Version previous) {
                    return record.filter(value -> value.previousVersion()
                                                       .equals(previous) && value.currentVersion()
                                                                                 .equals(target.currentVersion()))
                                 .isPresent();
                }

                Option<Version> recordedVersion() {
                    return record.map(PreviousVersionValue::currentVersion);
                }
            }

            /// A guarded record write ready to submit.
            record RecordWrite(ArtifactBase base, KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {}

            @Override
            public Promise<Unit> activate() {
                log.info("Node {} activating RollbackManager", self);
                loadPreviousVersionsFromKvStore();
                deriveRecordsOnActivation();

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
            public Unit installReactExecutor(ReactExecutor executor) {
                react.set(executor);

                return Unit.unit();
            }

            @Override
            public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
                trackVersionChange(valuePut.cause().key().artifactBase(),
                                   valuePut.cause().value(),
                                   valuePut.oldValue());
            }

            @Override
            public void onPreviousVersionPut(ValuePut<PreviousVersionKey, PreviousVersionValue> valuePut) {
                updateLocalPreviousVersion(valuePut.cause().key().artifactBase(),
                                           valuePut.cause().value());
            }

            @Override
            public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
                // #1573 N2: the committed cluster config is read BEFORE the policy derived from it, and the
                // rollback write is guarded on it being unchanged — a disable committed after this read is
                // either seen by policy.get() or refuses the write.
                var configWitness = kvStore.get(ClusterConfigKey.CURRENT);
                var config = policy.get();

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
                      .onPresent(state -> decide(event, state, config, configWitness))
                      .onEmpty(() -> log.warn("[requestId={}] No previous version tracked for {}, cannot rollback",
                                              event.requestId(),
                                              event.artifact()));
            }

            /// #1573: a stale event (the failed version is no longer the target) and a managed deployment
            /// owning the artifact both skip before the state's own checks.
            @Contract
            private void decide(SliceFailureEvent.AllInstancesFailed event,
                                RollbackState state,
                                RollbackConfig config,
                                Option<AetherValue> configWitness) {
                var failedArtifact = event.artifact();

                eligibility(failedArtifact, state).flatMap(_ -> state.canRollback(config,
                                                                                  System.currentTimeMillis()))
                           .onFailure(cause -> logRollbackSkipped(cause,
                                                                  event.requestId(),
                                                                  failedArtifact,
                                                                  config))
                           .onSuccess(decision -> executeRollback(event, decision, config, configWitness));
            }

            private Result<Unit> eligibility(Artifact failedArtifact, RollbackState state) {
                if (!state.currentVersion().equals(failedArtifact.version())) {
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
                return ! isTerminal(value.state()) && Arrays.stream(value.artifacts().split(","))
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

            /// D1-react (the mirror holds only committed values): the reset writes the record only. The mirror's counters follow
            /// when the reset commits, through [#onPreviousVersionPut]; a refused or failed reset leaves them as committed. The
            /// in-memory last-rollback details are kept: they describe the last rollback, which the reset does not undo.
            @Override
            public void resetRollbackCount(ArtifactBase artifactBase) {
                log.info("Rollback count reset requested for {}", artifactBase);
                resetRecord(artifactBase, 1);
            }

            /// #2613: the reset is a record-only writer, so it is a leader transaction with a CAS on the record as read and no target
            /// witness. A refusal re-reads and retries within [#MAX_RECORD_ATTEMPTS]; the write needs this node to be the committed leader.
            @Contract
            private void resetRecord(ArtifactBase artifactBase, int attempt) {
                committedLeaderIsSelf().flatMap(leader -> resetWrite(leader, artifactBase))
                                     .onPresent(write -> submitRecord(write,
                                                                      attempt,
                                                                      () -> resetRecord(artifactBase, attempt + 1)))
                                     .onEmpty(() -> log.debug("Rollback count reset for {} not written: no record, or this node is not the committed leader",
                                                              artifactBase));
            }

            private Option<RecordWrite> resetWrite(LeaderValue leader, ArtifactBase artifactBase) {
                return previousVersionValue(artifactBase).map(record -> new RecordWrite(artifactBase,
                                                                                        recordTransaction(leader,
                                                                                                          List.of(),
                                                                                                          Option.some(record),
                                                                                                          record.withRollbackCountReset())));
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

            private static RollbackState computePreviousVersionUpdate(RollbackState existing,
                                                                      PreviousVersionValue value) {
                return Option.option(existing)
                             .map(state -> state.withKVStoreUpdate(value))
                             .or(() -> RollbackState.fromKVStore(value));
            }

            private Option<PreviousVersionValue> previousVersionValue(ArtifactBase artifactBase) {
                return kvStore.get(PreviousVersionKey.previousVersionKey(artifactBase))
                              .filter(PreviousVersionValue.class::isInstance)
                              .map(PreviousVersionValue.class::cast);
            }

            /// #2613: the mirror follows the notification (its `currentVersion` feeds [#decide]'s eligibility); its counters arrive only
            /// through [#onPreviousVersionPut] when a record commits, never from an uncommitted derivation. The record itself is then
            /// re-derived from the committed target and record, version change or not.
            ///
            /// D1-react (§3.5.1 row 19): the mirror and the version-change count stay in absorb; the derivation (HEAD reads plus the
            /// guarded submit) is a reaction behind the engine-alive gate. No undo is needed: the write is guarded, and a dropped
            /// derivation heals at the next SliceTarget notification or the next leader's activation.
            @Contract
            private void trackVersionChange(ArtifactBase artifactBase,
                                            SliceTargetValue sliceTargetValue,
                                            Option<SliceTargetValue> oldValue) {
                if (!leaderManager.isLeader()) {
                    return;
                }

                var currentVersion = sliceTargetValue.currentVersion();

                rollbackStates.compute(artifactBase,
                                       (ab, existing) -> computeVersionTracking(ab, existing, currentVersion));
                if (isVersionChange(sliceTargetValue, oldValue)) {
                    versionChanges.merge(artifactBase, 1L, Long::sum);
                }

                var context = new DerivationContext.Notification(sliceTargetValue, oldValue);

                react.get().react("RollbackManager record derive", () -> deriveRecord(artifactBase, context, 1));
            }

            private static boolean isVersionChange(SliceTargetValue value, Option<SliceTargetValue> oldValue) {
                return oldValue.filter(old -> old.currentVersion()
                                                 .equals(value.currentVersion()))
                               .isEmpty();
            }

            private long versionChangesOf(ArtifactBase artifactBase) {
                return versionChanges.getOrDefault(artifactBase, 0L);
            }

            private RollbackState computeVersionTracking(ArtifactBase ab,
                                                         RollbackState existing,
                                                         Version currentVersion) {
                return Option.option(existing)
                             .map(state -> trackVersion(state, ab, currentVersion))
                             .or(() -> initialDeploymentState(ab, currentVersion));
            }

            private RollbackState initialDeploymentState(ArtifactBase ab, Version currentVersion) {
                log.debug("First deployment of {}, no previous version to track", ab);

                return RollbackState.initial(ab, currentVersion);
            }

            private RollbackState trackVersion(RollbackState state, ArtifactBase artifactBase, Version newVersion) {
                if (state.currentVersion().equals(newVersion)) {
                    return state;
                }

                log.info("Version change detected for {}: {} -> {}",
                         artifactBase,
                         state.currentVersion(),
                         newVersion);

                return state.withVersionChange(newVersion);
            }

            /// A new leader records every version change no leader recorded across the handover. `[mechanism: the leader notification
            /// is derived from the committed LeaderKey, so at this point the store already names this node; a later LeaderKey
            /// naming another node refuses the write]`.
            @Contract
            private void deriveRecordsOnActivation() {
                kvStore.forEach(SliceTargetKey.class,
                                SliceTargetValue.class,
                                (key, _) -> deriveRecord(key.artifactBase(), DerivationContext.Activation.INSTANCE, 1));
            }

            /// #2613: the one rule that writes the rollback record, run by the SliceTarget handler, by every retry and by activation.
            /// Reads the HEAD target and record, then writes a guarded record only when the record lags the target and the context can
            /// name the previous version. Never writes from the mirror.
            @Contract
            private void deriveRecord(ArtifactBase artifactBase, DerivationContext context, int attempt) {
                committedLeaderIsSelf().onPresent(leader -> deriveAs(leader, artifactBase, context, attempt))
                                     .onEmpty(() -> log.debug("Rollback record for {} not derived: this node is not the committed leader",
                                                              artifactBase));
            }

            @Contract
            private void deriveAs(LeaderValue leader,
                                  ArtifactBase artifactBase,
                                  DerivationContext context,
                                  int attempt) {
                observe(artifactBase).onPresent(observed -> deriveFrom(leader, observed, context, attempt));
            }

            private Option<Observed> observe(ArtifactBase artifactBase) {
                return currentTarget(artifactBase).map(target -> new Observed(artifactBase,
                                                                              target,
                                                                              previousVersionValue(artifactBase)));
            }

            @Contract
            private void deriveFrom(LeaderValue leader, Observed observed, DerivationContext context, int attempt) {
                if (isRecorded(context, observed)) {
                    closeExhaustion(observed.base());

                    return;
                }
                // Read AFTER the HEAD reads in observe(): a version change visible there has had its notification counted.
                var changes = versionChangesOf(observed.base());

                previousVersion(context, observed, changes).onPresent(previous -> writeVersionChange(leader,
                                                                                                     observed,
                                                                                                     previous,
                                                                                                     changes,
                                                                                                     attempt));
            }

            /// Whether the record already names the change this derivation would write. A version change at HEAD and a retry know
            /// their (previous, current) pair and compare both; the rest know only the target's version.
            private static boolean isRecorded(DerivationContext context, Observed observed) {
                return switch (context) {
                    case DerivationContext.Notification notification -> versionChangeAtHead(notification, observed).map(observed::isRecordedAs).or(observed::isRecorded);
                    case DerivationContext.Retry retry -> observed.isRecordedAs(retry.previous());
                    case DerivationContext.Activation _ -> observed.isRecorded();
                };
            }

            /// A refused write re-derives with its own (previous, current) pair, so the retry rewrites exactly that change while the
            /// target still names it, and writes nothing once a later version change has replaced it. D1-react (§3.5.1 row 19): the
            /// retry is a reaction behind the engine-alive gate too, so a refusal answered after an engine stop burns no attempt and
            /// raises no exhaustion warning. It applies to an activation chain's retries as well (activation itself stays inline: it
            /// is a leader-change call, not a KV notification).
            @Contract
            private void writeVersionChange(LeaderValue leader,
                                            Observed observed,
                                            Version previous,
                                            long changes,
                                            int attempt) {
                var retry = new DerivationContext.Retry(previous,
                                                        observed.target().currentVersion(),
                                                        changes);

                submitRecord(versionChangeWrite(leader, observed, previous),
                             attempt,
                             () -> react.get()
                                        .react("RollbackManager record retry",
                                               () -> deriveRecord(observed.base(),
                                                                  retry,
                                                                  attempt + 1)));
            }

            /// Which version the record may name as previous, or none when this derivation must write nothing.
            ///
            /// - Notification equal to the HEAD target: the version it replaced if that differs (today's meaning, "previous of C is
            ///   B"), else the record's current version (a non-version change after an earlier change went unrecorded). Neither:
            ///   a first deployment, nothing to write. The gate is `oldValue` and the committed record, never the mirror.
            /// - Notification that differs from the HEAD target (read-ahead): nothing. The later notification for that key is queued
            ///   on this node and derives from its own `oldValue`.
            /// - Retry: its own previous, only if the target still names the version it carried AND no version change was
            ///   delivered since it was derived; a later version change, B -> C -> B included, is re-derived by its own notification.
            /// - Activation: the record's current version (an approximation across a handover: a version between the record's and
            ///   the target's may have gone unrecorded by every leader).
            private static Option<Version> previousVersion(DerivationContext context, Observed observed, long changes) {
                return switch (context) {
                    case DerivationContext.Notification notification -> notificationPrevious(notification, observed);
                    case DerivationContext.Retry retry -> retryPrevious(retry, observed, changes);
                    case DerivationContext.Activation _ -> observed.recordedVersion();
                };
            }

            private static Option<Version> notificationPrevious(DerivationContext.Notification notification,
                                                                Observed observed) {
                return notification.value()
                                   .equals(observed.target())
                       ? replacedVersion(notification, observed).orElse(observed.recordedVersion())
                       : Option.none();
            }

            private static Option<Version> versionChangeAtHead(DerivationContext.Notification notification,
                                                               Observed observed) {
                return notification.value()
                                   .equals(observed.target())
                       ? replacedVersion(notification, observed)
                       : Option.none();
            }

            private static Option<Version> replacedVersion(DerivationContext.Notification notification,
                                                           Observed observed) {
                return notification.oldValue()
                                   .map(SliceTargetValue::currentVersion)
                                   .filter(replaced -> !replaced.equals(observed.target().currentVersion()));
            }

            private static Option<Version> retryPrevious(DerivationContext.Retry retry,
                                                         Observed observed,
                                                         long changes) {
                return retry.current()
                            .equals(observed.target().currentVersion()) && retry.versionChanges() == changes
                       ? Option.some(retry.previous())
                       : Option.none();
            }

            private RecordWrite versionChangeWrite(LeaderValue leader, Observed observed, Version previous) {
                var current = observed.target().currentVersion();
                var next = observed.record()
                                   .map(value -> value.withVersionChange(previous,
                                                                         current,
                                                                         System.currentTimeMillis()))
                                   .or(() -> PreviousVersionValue.previousVersionValue(observed.base(),
                                                                                       previous,
                                                                                       current));
                var targetWitness = new KVCommand.ReadWitness<AetherKey>(SliceTargetKey.sliceTargetKey(observed.base()),
                                                                         Option.some(observed.target()));

                return new RecordWrite(observed.base(),
                                       recordTransaction(leader, List.of(targetWitness), observed.record(), next));
            }

            /// The guarded write of one rollback record: this node still the committed leader, every witness unchanged, and the record
            /// still the value read. The target witness is the whole value on purpose: a version-only comparison is not expressible
            /// (`ReadWitness` compares by `equals`), and the whole value bounds ABA because `SliceTargetValue.withVersion` stamps
            /// `updatedAt`. Do not simplify it to the version.
            private static KVCommand.LeaderTransaction<AetherKey, AetherValue> recordTransaction(LeaderValue leader,
                                                                                                 List<KVCommand.ReadWitness<AetherKey>> guards,
                                                                                                 Option<PreviousVersionValue> before,
                                                                                                 PreviousVersionValue next) {
                var recordKey = PreviousVersionKey.previousVersionKey(next.artifactBase());
                var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(recordKey,
                                                                              before.map(AetherValue.class::cast),
                                                                              Option.<AetherValue> some(next));

                return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(recordKey,
                                                                               UUID.randomUUID().toString(),
                                                                               leader,
                                                                               guards,
                                                                               List.of(mutation));
            }

            /// FER (degrade forward): a failed submit (no quorum, a stopping node) is logged and absorbed. The record keeps naming an
            /// older version, which the next SliceTarget notification or the next leader activation re-derives; nothing is retried
            /// here, because a submit that cannot reach consensus will not be helped by an immediate second one. Guarantee earned: a
            /// refused or failed write never leaves a wrong record behind, only a lagging one.
            @Contract
            private void submitRecord(RecordWrite write, int attempt, Runnable retry) {
                var transaction = write.transaction();

                cluster.<Object> apply(List.<KVCommand<AetherKey>> of(transaction))
                       .map(results -> accepted(results,
                                                transaction.transactionId()))
                       .onSuccess(accepted -> onRecordSubmitted(accepted, write, attempt, retry))
                       .onFailure(cause -> log.error("Failed to store rollback record for {}: {}",
                                                     write.base(),
                                                     cause.message()));
            }

            /// A refusal writes nothing itself: `retry` re-derives from the committed state, and the derivation decides whether a write is
            /// still owed. After the last attempt the record is left lagging, reported, and healed by the next SliceTarget update or
            /// the next leader's activation.
            @Contract
            private void onRecordSubmitted(boolean accepted, RecordWrite write, int attempt, Runnable retry) {
                if (accepted) {
                    log.debug("Stored rollback record for {}", write.base());
                    closeExhaustion(write.base());

                    return;
                }

                if (attempt >= MAX_RECORD_ATTEMPTS) {
                    reportExhausted(write.base());

                    return;
                }

                log.debug("Rollback record write for {} refused by its guard (attempt {}), re-deriving",
                          write.base(),
                          attempt);
                retry.run();
            }

            /// FER (degrade forward): after the last refusal the record is left lagging and the operator is told; there is no outbox
            /// and no further retry. Guarantee earned: the lag is visible (ERROR line and event) and is bounded by the next SliceTarget
            /// update or leader activation.
            @Contract
            private void reportExhausted(ArtifactBase artifactBase) {
                exhausted.add(artifactBase);
                OperatorWarnings.raise(log,
                                       warnings,
                                       OperatorWarningCode.ROLLBACK_RECORD_RECONCILE_EXHAUSTED,
                                       artifactBase.asString(),
                                       "Rollback record for {} still lags its SliceTarget after {} refused writes; "
                                      + "the next SliceTarget update or leader activation re-derives it",
                                       artifactBase.asString(),
                                       MAX_RECORD_ATTEMPTS);
            }

            /// Recovery of [#reportExhausted], raised once, by the node that raised the warning, when the record is next seen current.
            @Contract
            private void closeExhaustion(ArtifactBase artifactBase) {
                if (exhausted.remove(artifactBase)) {
                    OperatorWarnings.raise(log,
                                           warnings,
                                           OperatorWarningCode.ROLLBACK_RECORD_RECONCILED,
                                           artifactBase.asString(),
                                           "Rollback record for {} names its SliceTarget version again",
                                           artifactBase.asString());
                }
            }

            @Contract
            private void logRollbackSkipped(Cause cause, String requestId, Artifact artifact, RollbackConfig config) {
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
                    case RollbackError.General.OUTSIDE_BAKE_WINDOW -> log.warn("[requestId={}] All instances of {} failing outside its {} bake window: " + "alerting only, NOT rolling back (incident, not a bad deploy)",
                                                                               requestId,
                                                                               artifact,
                                                                               config.bakeWindow());
                    case RollbackError.General.TARGET_PREVIOUSLY_FAILED -> log.error("[requestId={}] CRITICAL: rollback target for {} already failed once; " + "not rolling back. Manual intervention required.",
                                                                                     requestId,
                                                                                     artifact);
                    default -> log.warn("[requestId={}] Rollback skipped for {}: {}",
                                        requestId,
                                        artifact,
                                        cause.message());
                }
            }

            @Contract
            private void executeRollback(SliceFailureEvent.AllInstancesFailed event,
                                         RollbackDecision decision,
                                         RollbackConfig config,
                                         Option<AetherValue> configWitness) {
                var failedArtifact = event.artifact();
                var requestId = event.requestId();
                var rollbackArtifact = Artifact.artifact(failedArtifact.base(), decision.targetVersion());

                log.warn("[requestId={}] INITIATING ROLLBACK: {} -> {} (rollback #{} of max {})",
                         requestId,
                         failedArtifact,
                         rollbackArtifact,
                         decision.rollbackNumber(),
                         config.maxRollbacks());
                commitRollback(event, rollbackArtifact, decision, configWitness);
            }

            /// #1573: the rollback record and the SliceTarget change commit as ONE leader transaction, record
            /// first, so the version-change notification the target raises already finds the record current (see
            /// [#deriveRecord]). #1573 N2: the transaction is fenced on the state the decision was made
            /// from — the committed cluster config (the `[rollback]` policy's source), the SliceTarget still
            /// naming the failed version, the rollback record as read, and this node still the committed
            /// leader. A lost race is a no-op and a DEBUG line, never a rollback of a newer target.
            @Contract
            private void commitRollback(SliceFailureEvent.AllInstancesFailed event,
                                        Artifact rollbackArtifact,
                                        RollbackDecision decision,
                                        Option<AetherValue> configWitness) {
                var now = System.currentTimeMillis();

                committedLeaderIsSelf().flatMap(leader -> fencedRollback(leader,
                                                                         rollbackArtifact.base(),
                                                                         decision,
                                                                         configWitness,
                                                                         now))
                                     .onEmpty(() -> log.debug("[requestId={}] Rollback of {} not attempted: the target no longer names {} or this node is not the committed leader",
                                                              event.requestId(),
                                                              rollbackArtifact.base(),
                                                              decision.failedVersion()))
                                     .onPresent(transaction -> submitRollback(event,
                                                                              rollbackArtifact,
                                                                              decision,
                                                                              transaction));
            }

            private Option<LeaderValue> committedLeaderIsSelf() {
                return kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                              .filter(leader -> leader.leader()
                                                      .equals(self));
            }

            /// Empty when a committed target already names another version: someone moved it since the
            /// decision, and rolling it back would undo a newer target. An absent target is written fresh.
            private Option<KVCommand.LeaderTransaction<AetherKey, AetherValue>> fencedRollback(LeaderValue leader,
                                                                                               ArtifactBase artifactBase,
                                                                                               RollbackDecision decision,
                                                                                               Option<AetherValue> configWitness,
                                                                                               long now) {
                var target = currentTarget(artifactBase);
                var movedOn = target.filter(current -> !current.currentVersion()
                                                               .equals(decision.failedVersion()))
                                    .isPresent();

                return movedOn
                       ? Option.none()
                       : Option.some(rollbackTransaction(leader, artifactBase, target, decision, configWitness, now));
            }

            private Option<SliceTargetValue> currentTarget(ArtifactBase artifactBase) {
                return kvStore.get(SliceTargetKey.sliceTargetKey(artifactBase))
                              .filter(SliceTargetValue.class::isInstance)
                              .map(SliceTargetValue.class::cast);
            }

            private KVCommand.LeaderTransaction<AetherKey, AetherValue> rollbackTransaction(LeaderValue leader,
                                                                                            ArtifactBase artifactBase,
                                                                                            Option<SliceTargetValue> target,
                                                                                            RollbackDecision decision,
                                                                                            Option<AetherValue> configWitness,
                                                                                            long now) {
                var recordBefore = previousVersionValue(artifactBase);
                var record = recordBefore.or(() -> PreviousVersionValue.previousVersionValue(artifactBase,
                                                                                             decision.targetVersion(),
                                                                                             decision.failedVersion()))
                                         .withRollback(decision.failedVersion(),
                                                       decision.targetVersion(),
                                                       now);
                var recordKey = PreviousVersionKey.previousVersionKey(artifactBase);
                var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);
                var recordMutation = new KVCommand.Mutation<AetherKey, AetherValue>(recordKey,
                                                                                    recordBefore.map(AetherValue.class::cast),
                                                                                    Option.<AetherValue> some(record));
                var rolledBackTarget = target.map(current -> current.withVersion(decision.targetVersion()))
                                             .or(() -> SliceTargetValue.sliceTargetValue(decision.targetVersion(),
                                                                                         SliceSpec.MIN_INSTANCES));
                var targetMutation = new KVCommand.Mutation<AetherKey, AetherValue>(targetKey,
                                                                                    target.map(AetherValue.class::cast),
                                                                                    Option.<AetherValue> some(rolledBackTarget));
                var configGuard = new KVCommand.ReadWitness<AetherKey>(ClusterConfigKey.CURRENT,
                                                                       configWitness.map(Object.class::cast));

                return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(targetKey,
                                                                               UUID.randomUUID().toString(),
                                                                               leader,
                                                                               List.of(configGuard),
                                                                               List.of(recordMutation, targetMutation));
            }

            @Contract
            private void submitRollback(SliceFailureEvent.AllInstancesFailed event,
                                        Artifact rollbackArtifact,
                                        RollbackDecision decision,
                                        KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {
                cluster.<Object> apply(List.<KVCommand<AetherKey>> of(transaction))
                       .map(results -> accepted(results,
                                                transaction.transactionId()))
                       .onSuccess(accepted -> onRollbackCommitted(accepted, event, decision, rollbackArtifact))
                       .onFailure(cause -> log.error("[requestId={}] ROLLBACK FAILED: Could not update slice target for {}: {}",
                                                     event.requestId(),
                                                     rollbackArtifact,
                                                     cause.message()));
            }

            private static boolean accepted(List<Object> results, String transactionId) {
                return results.stream()
                              .anyMatch(result -> result instanceof KVCommand.TransactionResult outcome
                                                  && outcome.transactionId()
                                                            .equals(transactionId)
                                                  && outcome.accepted());
            }

            @Contract
            private void onRollbackCommitted(boolean accepted,
                                             SliceFailureEvent.AllInstancesFailed event,
                                             RollbackDecision decision,
                                             Artifact rollbackArtifact) {
                if (!accepted) {
                    log.debug("[requestId={}] Rollback of {} refused by its fence: the cluster config, the target or the rollback record changed since the decision",
                              event.requestId(),
                              rollbackArtifact.base());

                    return;
                }

                recordRollbackCompleted(event, decision, rollbackArtifact);
            }

            /// D1-react (the mirror holds only committed values): the continuation records only the in-memory last-rollback details. The
            /// counters, the failed versions and the bake anchor arrive from the committed record through [#onPreviousVersionPut], the
            /// mirror's one counter writer, so a continuation that runs after a later record (a reset) cannot write older counters
            /// over it.
            @Contract
            private void recordRollbackCompleted(SliceFailureEvent.AllInstancesFailed event,
                                                 RollbackDecision decision,
                                                 Artifact rollbackArtifact) {
                rollbackStates.computeIfPresent(rollbackArtifact.base(),
                                                (_, state) -> state.withRolledBack(decision.failedVersion(),
                                                                                   decision.targetVersion()));
                log.info("[requestId={}] ROLLBACK INITIATED: SliceTarget updated to {}",
                         event.requestId(),
                         rollbackArtifact);
                reporter.accept(RollbackEvent.AutoRollbackExecuted.autoRollbackExecuted(event, decision));
            }
        }
        var manager = new rollbackManager(self,
                                          policy,
                                          reporter,
                                          cluster,
                                          kvStore,
                                          leaderManager,
                                          new ConcurrentHashMap<>(),
                                          ConcurrentHashMap.newKeySet(),
                                          new ConcurrentHashMap<>(),
                                          warnings,
                                          new AtomicReference<>(ReactExecutor.inline("RollbackManager")),
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
