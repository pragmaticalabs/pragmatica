// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
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


/// Post-E.7 (spec §9). The legacy SWIM-driven gate stack and self-promotion path are gone;
/// SWIM-driven lifecycle writes now live in `MembershipFsm` (E.5). The surface exercised
/// here is the slimmed reconciler: operator writes (`requestDrain` / `requestDecommission` /
/// `requestActivate` / `requestFailedDrain`), the phase-evaluation path (still owned for
/// flag-off mode until E.8 retires it), and phase-listener wiring.
class HealthReconcilerTest {
    private static final NodeId SELF = nodeId("self").unwrap();
    private static final NodeId TARGET = nodeId("target").unwrap();

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

    private static SwimObservation healthy(NodeId target) {
        return new SwimObservation.HealthyObserved(target, 1L);
    }

    @Nested class OperatorWrites {
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

        @Test
        void reconciler_requestActivate_writesOnDuty() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            lifecycleStore.put(TARGET,
                               NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 0L));
            reconciler.requestActivate(TARGET);
            assertThat(applier.commands).hasSize(1);
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.ON_DUTY);
        }

        @Test
        void reconciler_requestFailedDrain_writesFailedDrain() {
            var reconciler = buildReconciler(3, HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            lifecycleStore.put(TARGET,
                               NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 0L));
            reconciler.requestFailedDrain(TARGET);
            assertThat(applier.commands).hasSize(1);
            assertThat(lastWriteState(applier)).isEqualTo(NodeLifecycleState.FAILED_DRAIN);
        }
    }

    @Nested class SwimObservationAfterE7 {
        /// Post-E.7: `onSwimObservation` is a phase-evaluation trigger only — it MUST NOT
        /// drive lifecycle writes. The legacy aggregator + `handleAggregatedEdge` path is
        /// deleted; SWIM-driven lifecycle writes now flow exclusively through
        /// `MembershipFsm.onSwimObservation` (E.5).
        @Test
        void onSwimObservation_doesNotWriteLifecycle_afterE7() {
            var config = HealthReconcilerConfig.healthReconcilerConfig(timeSpan(60).seconds(),
                                                                       timeSpan(0).millis(),
                                                                       timeSpan(5).seconds(),
                                                                       timeSpan(30).seconds(),
                                                                       timeSpan(0).millis());
            var reconciler = buildReconciler(1, config);
            reconciler.start();
            onDutyCount.set(1);
            reconciler.onSwimObservation(healthy(TARGET));
            assertThat(applier.commands.stream().noneMatch(HealthReconcilerTest::isLifecycleWriteFor))
                    .as("Post-E.7 SWIM observations do not drive lifecycle writes")
                    .isTrue();
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

    private static final class RecordingApplier implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<?>> commands = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> input) {
            commands.addAll(input);
            return Promise.success(List.of());
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
