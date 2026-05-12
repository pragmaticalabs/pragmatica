// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.swim.SwimObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


class HealthReconcilerTest {
    private static final NodeId SELF = nodeId("self").unwrap();
    private static final NodeId TARGET = nodeId("target").unwrap();

    /// Default config with periodic phase-evaluation tick disabled. Tests that pair
    /// `HealthReconcilerConfig.DEFAULT` with `immediateRetryScheduler` would otherwise
    /// recurse: the immediate scheduler synchronously invokes the tick callback, which
    /// re-schedules itself, which fires immediately again → StackOverflowError.
    private static final HealthReconcilerConfig DEFAULT_NO_TICK =
            HealthReconcilerConfig.healthReconcilerConfig(timeSpan(10).seconds(),
                                                          timeSpan(5).seconds(),
                                                          timeSpan(5).seconds(),
                                                          timeSpan(5).seconds(),
                                                          timeSpan(0).millis());

    private RecordingApplier applier;
    private LifecycleStore lifecycleStore;
    private AtomicReference<ClusterPhase> phaseRef;
    private AtomicReference<NodeId> leaderRef;
    private AtomicInteger onDutyCount;

    @BeforeEach
    void setUp() {
        applier = new RecordingApplier();
        lifecycleStore = new LifecycleStore();
        phaseRef = new AtomicReference<>(ClusterPhase.NORMAL);
        leaderRef = new AtomicReference<>(SELF);
        onDutyCount = new AtomicInteger(3);
    }

    private HealthReconciler buildReconciler(int clusterSize, HealthReconcilerConfig config) {
        Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader = lifecycleStore::get;
        Supplier<Option<ClusterPhase>> phaseReader = () -> Option.option(phaseRef.get());
        Supplier<Option<NodeId>> leaderReader = () -> Option.option(leaderRef.get());
        Supplier<Integer> onDutySupplier = onDutyCount::get;
        return HealthReconciler.healthReconciler(SELF,
                                                 clusterSize,
                                                 lifecycleReader,
                                                 phaseReader,
                                                 leaderReader,
                                                 onDutySupplier,
                                                 applier,
                                                 config);
    }

    private HealthReconciler buildReconcilerWith(int clusterSize,
                                                 HealthReconcilerConfig config,
                                                 Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> customApplier,
                                                 HealthReconciler.RetryScheduler retryScheduler) {
        Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader = lifecycleStore::get;
        Supplier<Option<ClusterPhase>> phaseReader = () -> Option.option(phaseRef.get());
        Supplier<Option<NodeId>> leaderReader = () -> Option.option(leaderRef.get());
        Supplier<Integer> onDutySupplier = onDutyCount::get;
        return HealthReconciler.healthReconciler(SELF,
                                                 clusterSize,
                                                 lifecycleReader,
                                                 phaseReader,
                                                 leaderReader,
                                                 onDutySupplier,
                                                 customApplier,
                                                 config,
                                                 HealthReconciler.defaultSelfOnDutyAtomFactory(),
                                                 retryScheduler);
    }

    private static HealthReconciler.RetryScheduler immediateRetryScheduler() {
        return (runnable, delay) -> runnable.run();
    }

    private static SwimObservation healthy(NodeId target) {
        return new SwimObservation.HealthyObserved(target, 1L);
    }

    private static SwimObservation faulty(NodeId target) {
        return new SwimObservation.FaultyObserved(target, 1L);
    }

    @Nested class HappyPath {
        @Test
        void reconciler_writesNodeLifecycleKey_onAggregatedHealthyEdge() {
            // Cluster of 1 (single-node), k=1. SELF observation alone reaches quorum.
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(0).millis(),
                                                                       timeSpan(5).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(1, config);
            reconciler.start();
            onDutyCount.set(1);
            reconciler.onSwimObservation(healthy(TARGET));
            // Single observer reaches k=1, ON_DUTY edge emitted, write proposed.
            assertThat(applier.commands).isNotEmpty();
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.ON_DUTY);
        }

        @Test
        void reconciler_requestDrain_writesDraining() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            lifecycleStore.put(TARGET,
                               NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, 0L));
            reconciler.requestDrain(TARGET);
            assertThat(applier.commands).hasSize(1);
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.DRAINING);
        }

        @Test
        void reconciler_requestDecommission_writesDecommissioned() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            lifecycleStore.put(TARGET,
                               NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 0L));
            reconciler.requestDecommission(TARGET);
            assertThat(applier.commands).hasSize(1);
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        }
    }

    @Nested class StepFailures {
        @Test
        void reconciler_cooldown_suppressesRepeatedWritesWithinWindow() {
            // 30s cooldown → after a SWIM-driven aggregated edge produces a write,
            // a subsequent same-state SWIM-driven edge within the cooldown window must
            // be suppressed. Threshold-quorum migration (RC1): onDutyCount=1 floors
            // threshold to 1 so the leader's single local SWIM observation alone reaches
            // the aggregator threshold (production cross-node observation propagation is
            // a follow-up; per-API the public surface still only feeds SELF observations).
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(5).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(3, config);
            reconciler.start();
            onDutyCount.set(1);
            // First SWIM HEALTHY observation: leader writes ON_DUTY, recordWrite stamps lastWriteAt.
            // A ClusterPhase RECOVERING write may be interleaved because onDutyCount=1 < quorum;
            // filter on lifecycle writes specifically.
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(applier.commands.stream().filter(HealthReconcilerTest::isLifecycleWriteFor).count())
                    .as("Exactly one TARGET lifecycle write recorded")
                    .isEqualTo(1L);
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.ON_DUTY);
            // Reset edge state in aggregator (recordWrite did this) — to force a fresh edge
            // emission, the aggregator's lastAggregated must differ from the next observation.
            // We simulate this by clearing the recorded commands and observing FAULTY: with
            // a recently-written lastWriteAt, the cooldown gate must suppress the FAULTY write.
            applier.commands.clear();
            reconciler.onSwimObservation(faulty(TARGET));
            // Cooldown still active → no lifecycle write for TARGET (phase writes don't count)
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("Cooldown suppresses repeated TARGET lifecycle writes within window")
                    .isTrue();
        }

        // Contract change (commit 81e48e234, "drop respectColdBoot suppression"):
        // The reconciler's cold-boot suppression no longer keys off everSeenHealthy.
        // The phase gate `suppressedByPhase` only fires when phase == COLD_BOOT. In NORMAL
        // phase the reconciler now writes DECOMMISSIONED regardless of whether the target
        // was ever observed HEALTHY (the upstream SwimProtocol.emitFaultyOrUnknown gate
        // owns that filtering, and tests that feed observations directly to the reconciler
        // bypass it).
        // The 2x2 truth table below pins the new contract end-to-end.
        @Test
        void reconciler_suppressedByPhase_inBooting_evenWhenNeverHealthy() {
            // COLD_BOOT + never-HEALTHY → suppressedByPhase blocks the DECOMMISSIONED write.
            // onDutyCount=1 floors threshold to 1 so a single SELF observation drives the
            // aggregator edge (post threshold-quorum migration the production aggregator
            // requires majority; this test pins the phase-gate behavior in isolation).
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.COLD_BOOT));
            phaseRef.set(ClusterPhase.COLD_BOOT);
            onDutyCount.set(1);
            reconciler.onSwimObservation(faulty(TARGET));
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("COLD_BOOT phase suppresses DECOMMISSIONED lifecycle write")
                    .isTrue();
        }

        @Test
        void reconciler_suppressedByPhase_inBooting_evenWhenEverHealthy() {
            // COLD_BOOT + previously-HEALTHY → suppressedByPhase still blocks. Phase gate
            // is independent of everSeenHealthy. onDutyCount=1 (see note above).
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            // First, in NORMAL, drive an ON_DUTY edge so the aggregator records HEALTHY.
            phaseRef.set(ClusterPhase.NORMAL);
            onDutyCount.set(1);
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.ON_DUTY);
            applier.commands.clear();
            // Now flip to COLD_BOOT and feed FAULTY: the phase gate suppresses the write.
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.COLD_BOOT));
            phaseRef.set(ClusterPhase.COLD_BOOT);
            reconciler.onSwimObservation(faulty(TARGET));
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("COLD_BOOT phase suppresses DECOMMISSIONED write regardless of prior HEALTHY")
                    .isTrue();
        }

        @Test
        void reconciler_writesDecommissioned_inNormal_evenWhenNeverHealthy() {
            // NORMAL + never-HEALTHY → write proceeds. This is the formerly-suppressed
            // case: the old aggregator's respectColdBoot used to filter this out, which
            // silently dropped FAULTY edges for the leader on cloud Container post-kill.
            // Now the reconciler trusts upstream gating and writes DECOMMISSIONED.
            // onDutyCount=1 floors threshold to 1 (see notes on threshold migration).
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            phaseRef.set(ClusterPhase.NORMAL);
            onDutyCount.set(1);
            reconciler.onSwimObservation(faulty(TARGET));
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("NORMAL phase + never-HEALTHY: leader writes DECOMMISSIONED")
                    .isTrue();
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
        }

        // The fourth corner of the 2x2 table (NORMAL + everSeenHealthy → writes
        // DECOMMISSIONED) is intentionally NOT covered by a dedicated test here:
        // (a) it is the standard healthy-then-faulty happy path already exercised
        //     end-to-end by integration tests, and
        // (b) the new contract makes everSeenHealthy irrelevant inside the reconciler
        //     (cold-boot gating moved upstream per 81e48e234), so the "NORMAL + never"
        //     and "NORMAL + ever" rows produce the same behavior; one row suffices.
    }

    @Nested class SelfOnDutyRetry {
        @Test
        void signalSelfReady_writeRejectedByInactive_retriesUntilSuccess() {
            // Applier fails first 3 attempts with NodeInactive, then succeeds on 4th.
            var flakyApplier = new FlakyInactiveApplier(3);
            var immediateScheduler = immediateRetryScheduler();
            var reconciler = buildReconcilerWith(3,
                                                 DEFAULT_NO_TICK,
                                                 flakyApplier,
                                                 immediateScheduler);
            reconciler.start();
            reconciler.signalSelfReady();
            assertThat(flakyApplier.totalAttempts())
                    .as("Applier called once initially + 3 retries = 4 total")
                    .isEqualTo(4);
            assertThat(flakyApplier.commands.stream().anyMatch(HealthReconcilerTest::isSelfOnDutyWrite))
                    .as("ON_DUTY write proposed at least once")
                    .isTrue();
            // Final attempt succeeded, recordWrite executed
            assertThat(flakyApplier.successfulWrites.get())
                    .as("Exactly one successful ON_DUTY write after retries")
                    .isEqualTo(1);
        }

        @Test
        void signalSelfReady_writeRejectedRepeatedly_givesUpAfterMaxAttempts() {
            // Applier always fails with NodeInactive — must give up after MAX_SELF_ONDUTY_RETRIES.
            var alwaysFailingApplier = new FlakyInactiveApplier(Integer.MAX_VALUE);
            var immediateScheduler = immediateRetryScheduler();
            var reconciler = buildReconcilerWith(3,
                                                 DEFAULT_NO_TICK,
                                                 alwaysFailingApplier,
                                                 immediateScheduler);
            reconciler.start();
            reconciler.signalSelfReady();
            assertThat(alwaysFailingApplier.totalAttempts())
                    .as("Caps at MAX_SELF_ONDUTY_RETRIES (8) attempts")
                    .isEqualTo(HealthReconcilerImpl.MAX_SELF_ONDUTY_RETRIES);
            assertThat(alwaysFailingApplier.successfulWrites.get())
                    .as("No successful ON_DUTY write after exhausting retries")
                    .isEqualTo(0);
        }

        @Test
        void signalSelfReady_writeRejectedByNonRetriableCause_doesNotRetry() {
            // Non-NodeInactive failure (e.g. arbitrary Cause) must NOT trigger retry.
            var nonRetriableApplier = new NonRetriableFailingApplier();
            var immediateScheduler = immediateRetryScheduler();
            var reconciler = buildReconcilerWith(3,
                                                 DEFAULT_NO_TICK,
                                                 nonRetriableApplier,
                                                 immediateScheduler);
            reconciler.start();
            reconciler.signalSelfReady();
            assertThat(nonRetriableApplier.totalAttempts())
                    .as("Non-retriable cause: exactly one attempt, no retries")
                    .isEqualTo(1);
        }
    }

    @Nested class SelfPromotion {
        @Test
        void healthReconciler_promotesSelfToOnDuty_onSelfReadySignal() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            // Self has no prior NodeLifecycleValue; signalSelfReady must trigger an ON_DUTY write.
            reconciler.signalSelfReady();
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isSelfOnDutyWrite))
                    .as("signalSelfReady writes ON_DUTY for self")
                    .isTrue();
        }

        @Test
        void healthReconciler_signalSelfReady_writesOnce_evenAfterRepeatedCalls() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            reconciler.signalSelfReady();
            reconciler.signalSelfReady();
            reconciler.signalSelfReady();
            var onDutyWrites = applier.commands.stream().filter(HealthReconcilerTest::isSelfOnDutyWrite).count();
            assertThat(onDutyWrites)
                    .as("Idempotent: self ON_DUTY is proposed exactly once")
                    .isEqualTo(1L);
        }

        @Test
        void healthReconciler_signalSelfReady_skipsWrite_whenSelfAlreadyOnDuty() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            // Pre-seed self as already ON_DUTY
            lifecycleStore.put(SELF, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, 0L));
            reconciler.signalSelfReady();
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isSelfOnDutyWrite))
                    .as("No new ON_DUTY write when self is already ON_DUTY")
                    .isTrue();
        }
    }

    @Nested class LeaderGate {
        @Test
        void handleAggregatedEdge_followerObservation_doesNotProposeWrite() {
            // Follower (leader != self) must NOT propose lifecycle writes from its own
            // SWIM observations, even when the aggregator emits an edge.
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(0).millis(),
                                                                       timeSpan(5).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(1, config);
            reconciler.start();
            // Demote self: another node is leader
            leaderRef.set(nodeId("other-leader").unwrap());
            onDutyCount.set(1);
            // SWIM observation that would otherwise emit ON_DUTY edge
            reconciler.onSwimObservation(healthy(TARGET));
            // No lifecycle write proposed for TARGET
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("Follower must not propose lifecycle writes for SWIM-driven edges")
                    .isTrue();
        }

        @Test
        void handleAggregatedEdge_leaderObservation_proposesWrite() {
            // Leader (self == leader) MUST propose lifecycle writes when the aggregator
            // emits an edge from its own SWIM observation.
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(0).millis(),
                                                                       timeSpan(5).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(1, config);
            reconciler.start();
            // Self is leader (default in setUp), but assert explicitly
            leaderRef.set(SELF);
            onDutyCount.set(1);
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("Leader proposes lifecycle write on aggregated edge")
                    .isTrue();
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.ON_DUTY);
        }
    }

    @Nested class PhaseTransitions {
        @Test
        void reconciler_phaseTransitionsColdBootToNormal_onStableLeaderAndOnDuty() {
            // Use a tiny stable window so the test completes quickly
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(1).millis(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(3, config);
            reconciler.start();
            // Reset to COLD_BOOT (start() reads from phaseReader which returns NORMAL by default)
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.COLD_BOOT));
            phaseRef.set(ClusterPhase.COLD_BOOT);
            // Pre-conditions: leader present, quorum=2 of 3 ON_DUTY satisfied
            onDutyCount.set(3);
            // First observation establishes stable marker
            reconciler.onSwimObservation(healthy(TARGET));
            // Sleep > stableWindow then send another observation to trigger phase eval
            try {Thread.sleep(50L);} catch (InterruptedException ignored) {Thread.currentThread().interrupt();}
            reconciler.onSwimObservation(healthy(TARGET));
            // Leader writes ClusterPhaseValue=NORMAL through commandApplier
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isClusterPhaseNormal)).isTrue();
        }

        @Test
        void reconciler_phaseTransitionsNormalToRecovering_onQuorumLoss() {
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(1).millis(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(5, config);
            reconciler.start();
            phaseRef.set(ClusterPhase.NORMAL);
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.NORMAL));
            // For a 5-node cluster, quorum=3. ON_DUTY drops to 2 → RECOVERING.
            onDutyCount.set(2);
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isClusterPhaseRecovering)).isTrue();
        }

        @Test
        void reconciler_phaseTransitionsRecoveringToNormal_onQuorumRestoredWithStability() throws InterruptedException {
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(1).millis(),
                                                                       timeSpan(1).millis(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(5, config);
            reconciler.start();
            phaseRef.set(ClusterPhase.RECOVERING);
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.RECOVERING));
            onDutyCount.set(5);
            reconciler.onSwimObservation(healthy(TARGET));
            Thread.sleep(20L);
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(applier.commands.stream().anyMatch(HealthReconcilerTest::isClusterPhaseNormal)).isTrue();
        }

        @Test
        void reconciler_phaseChangedListenerNotified_onTransition() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            var capturedRef = new AtomicReference<ClusterPhaseChanged>();
            reconciler.addPhaseListener(capturedRef::set);
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.NORMAL));
            // Initial state from start() was phaseReader.get() = NORMAL (default in setUp);
            // first listener invocation requires a different phase. Force via two transitions.
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.RECOVERING));
            assertThat(capturedRef.get()).isNotNull();
            assertThat(capturedRef.get().current()).isEqualTo(ClusterPhase.RECOVERING);
        }
    }

    /// Returns the state of the most recent NodeLifecycle Put recorded by `applier`.
    /// Filters out ClusterPhase Puts that may have been interleaved by the phase
    /// transition logic when the test sets onDutyCount < quorum.
    private static NodeLifecycleState lastWriteState(RecordingApplier applier) {
        return applier.commands.stream()
                                .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof NodeLifecycleKey)
                                .map(cmd -> (KVCommand.Put<?, ?>) cmd)
                                .map(put -> ((NodeLifecycleValue) put.value()).state())
                                .reduce((_, last) -> last)
                                .orElseThrow(() -> new AssertionError("no NodeLifecycle write recorded"));
    }

    private static boolean isClusterPhaseNormal(KVCommand<?> cmd) {
        return isClusterPhaseEqualTo(cmd, ClusterPhase.NORMAL);
    }

    private static boolean isClusterPhaseRecovering(KVCommand<?> cmd) {
        return isClusterPhaseEqualTo(cmd, ClusterPhase.RECOVERING);
    }

    private static boolean isClusterPhaseEqualTo(KVCommand<?> cmd, ClusterPhase expected) {
        if (!(cmd instanceof KVCommand.Put<?, ?> put)) {return false;}
        if (!(put.key() instanceof AetherKey.ClusterPhaseKey)) {return false;}
        return put.value() instanceof ClusterPhaseValue v && v.phase() == expected;
    }

    private static boolean isLifecycleWriteFor(KVCommand<?> cmd) {
        if (!(cmd instanceof KVCommand.Put<?, ?> put)) {return false;}
        if (!(put.key() instanceof NodeLifecycleKey lifecycleKey)) {return false;}
        return lifecycleKey.nodeId().equals(TARGET);
    }

    private static boolean isSelfOnDutyWrite(KVCommand<?> cmd) {
        if (!(cmd instanceof KVCommand.Put<?, ?> put)) {return false;}
        if (!(put.key() instanceof NodeLifecycleKey lifecycleKey)) {return false;}
        if (!lifecycleKey.nodeId().equals(SELF)) {return false;}
        return put.value() instanceof NodeLifecycleValue v && v.state() == NodeLifecycleState.ON_DUTY;
    }

    private static final class RecordingApplier implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<?>> commands = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> input) {
            commands.addAll(input);
            return Promise.success(List.of());
        }
    }

    /// Fails first {@code failuresBeforeSuccess} attempts with `ConsensusError.NodeInactive`,
    /// succeeds thereafter. Records every command attempted (including failed ones).
    private static final class FlakyInactiveApplier implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<?>> commands = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger(0);
        final AtomicInteger successfulWrites = new AtomicInteger(0);
        private final int failuresBeforeSuccess;

        FlakyInactiveApplier(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        int totalAttempts() {
            return attempts.get();
        }

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> input) {
            commands.addAll(input);
            var attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return new ConsensusError.NodeInactive(SELF).promise();
            }
            successfulWrites.incrementAndGet();
            return Promise.success(List.of());
        }
    }

    /// Always fails with a non-retriable `Cause` (not `ConsensusError.NodeInactive`).
    private static final class NonRetriableFailingApplier implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final AtomicInteger attempts = new AtomicInteger(0);

        int totalAttempts() {
            return attempts.get();
        }

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> input) {
            attempts.incrementAndGet();
            return new PermanentReject().promise();
        }
    }

    /// Test-only `Cause` distinct from `ConsensusError.NodeInactive` to validate
    /// non-retriable failure handling.
    record PermanentReject() implements Cause {
        @Override public String message() {
            return "permanent rejection (test fixture)";
        }
    }

    private static final class LifecycleStore {
        private final java.util.Map<NodeId, NodeLifecycleValue> values = new java.util.HashMap<>();

        Option<NodeLifecycleValue> get(NodeId nodeId) {
            return Option.option(values.get(nodeId));
        }

        void put(NodeId nodeId, NodeLifecycleValue value) {
            values.put(nodeId, value);
        }
    }
}
