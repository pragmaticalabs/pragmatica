// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.forge.EquivalenceComparison.Arm;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// F4 instrument validation, offline (FSM replacement plan 3.3; #2763): before any counted run, every control of the comparison, the monitor
/// and the oracle must fire on a synthetic but engine-shaped record, and a clean record must read clean. A control that cannot fire here can
/// not fire in a round either. The cluster-side half (the O4 scripted batch through the real commit ring, the oracle over a live LEGACY
/// quiescent point) is validated inside each round by `Fsm2EquivalenceRoundTest`.
class EquivalenceInstrumentTest {
    private static final Artifact SLICE = Artifact.artifact("org.f4.test:slice:1.0.0").unwrap();
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.f4.test:blueprint:1.0.0").unwrap();
    private static final List<NodeId> CORES = List.of(node("n1"), node("n2"), node("n3"));
    private static final EquivalenceMonitor MONITOR = new EquivalenceMonitor(5_000L, 10_000L);

    private static NodeId node(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    private static Map<AetherKey, AetherValue> shared(int target) {
        var kv = new HashMap<AetherKey, AetherValue>();

        kv.put(AetherKey.SliceTargetKey.sliceTargetKey(SLICE.base()), AetherValue.SliceTargetValue.sliceTargetValue(SLICE.version(), target));
        kv.put(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT),
               new AetherValue.DeploymentOutcomeValue(AetherValue.DeploymentOutcomeStatus.SUCCEEDED, List.of(), "", 1L, 2L, "a1"));

        return kv;
    }

    private static Map<AetherKey, AetherValue> legacyKv(int active) {
        var kv = shared(3);

        for (var index = 0; index < active; index++) {
            kv.put(new AetherKey.NodeArtifactKey(CORES.get(index), SLICE), AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
        }

        return kv;
    }

    private static Map<AetherKey, AetherValue> fsm2Kv(int active) {
        var kv = shared(3);

        for (var index = 0; index < active; index++) {
            var generation = new AetherValue.Generation(1L, index + 1L);

            kv.put(AetherKey.SliceDesiredKey.sliceDesiredKey(CORES.get(index), SLICE), new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.ACTIVE, generation, 0L));
            kv.put(AetherKey.SliceObservedKey.sliceObservedKey(CORES.get(index), SLICE),
                   new AetherValue.SliceObservedValue(AetherValue.SliceObservedState.ACTIVE, 1L, generation, new AetherValue.ProcessIncarnation(1L, 7L), Option.none(), false, 0, List.of(), 0L));
        }

        return kv;
    }

    /// A deploy 0 -> 3 seen at one sample per second: the target appears, instances come up one by one, then 20 s of steady state.
    private static List<EquivalenceMonitor.Sample> deploySamples(boolean fsm2) {
        var samples = new ArrayList<EquivalenceMonitor.Sample>();

        for (var second = 0; second <= 25; second++) {
            var active = Math.min(3, second);

            samples.add(new EquivalenceMonitor.Sample(second * 1_000L, new EquivalenceKv(fsm2 ? fsm2Kv(active) : legacyKv(active))));
        }

        return samples;
    }

    private static Arm arm(String engine, boolean fsm2) {
        return new Arm(engine,
                       new EquivalenceKv(fsm2 ? fsm2Kv(3) : legacyKv(3)),
                       Set.copyOf(CORES),
                       deploySamples(fsm2),
                       10,
                       4_000L,
                       Map.of("lost", 0.0, "settled_unknown", 0.0, "closed", 0.0));
    }

    @Nested
    class Comparison {
        @Test
        void equivalentArms_readClean() {
            assertThat(EquivalenceComparison.compare(arm("legacy", false), arm("fsm2", true), MONITOR)).isEmpty();
        }

        @Test
        void everyControlFamily_fires_againstACleanFsm2Arm() {
            var legacy = arm("legacy", false);
            var fsm2 = arm("fsm2", true);
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
            var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, MONITOR);
            var controlDivergences = EquivalenceComparison.compare(legacy, control, MONITOR);

            assertThat(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, controlDivergences)).as(controlDivergences.toString()).isEmpty();
        }

        /// The control is judged against what FSM2 already shows: a family FSM2 already diverges on must still show the control's own finding.
        @Test
        void aControl_isNotCreditedWithADivergenceFsm2AlreadyHas() {
            var legacy = arm("legacy", false);
            var fsm2 = arm("fsm2", true);
            var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, MONITOR);

            assertThat(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, fsm2Divergences)).containsExactlyElementsOf(EquivalenceDivergentControl.FAMILIES);
        }

        @Test
        void underPlacement_isO2_andAFlippedVerdict_isO1() {
            var legacy = arm("legacy", false);
            var short1 = new Arm("fsm2", new EquivalenceKv(fsm2Kv(2)), Set.copyOf(CORES), deploySamples(true), 10, 4_000L, Map.of());
            var families = EquivalenceComparison.compare(legacy, short1, MONITOR).stream().map(EquivalenceComparison.Divergence::family).toList();

            assertThat(families).containsExactly("O2");
        }

        /// Normaliser: an FSM2 desired tombstone (goal UNLOADED) is not residue; a non-tombstone desired row of a departed node is.
        @Test
        void tombstone_isExempt_butALiveGoalOfADepartedNode_isResidue() {
            var kv = fsm2Kv(3);
            var gone = node("gone");

            kv.put(AetherKey.SliceDesiredKey.sliceDesiredKey(gone, SLICE), new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.UNLOADED, new AetherValue.Generation(1L, 9L), 0L));
            var tombstoneOnly = new Arm("fsm2", new EquivalenceKv(kv), Set.copyOf(CORES), List.of(), 1, 1L, Map.of());

            assertThat(EquivalenceComparison.residue(tombstoneOnly)).isEmpty();
            kv.put(AetherKey.SliceDesiredKey.sliceDesiredKey(gone, SLICE), new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.ACTIVE, new AetherValue.Generation(1L, 9L), 0L));
            assertThat(EquivalenceComparison.residue(new Arm("fsm2", new EquivalenceKv(kv), Set.copyOf(CORES), List.of(), 1, 1L, Map.of()))).hasSize(1);
        }

        /// A scenario that places nothing (E-09: both arms -1) still gets an O5 control that fires.
        @Test
        void o5Control_firesWhenNeitherArmPlaces() {
            var legacy = new Arm("legacy", new EquivalenceKv(legacyKv(0)), Set.copyOf(CORES), List.of(), 1, -1L, Map.of());
            var fsm2 = new Arm("fsm2", new EquivalenceKv(fsm2Kv(0)), Set.copyOf(CORES), List.of(), 1, -1L, Map.of());
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);

            assertThat(EquivalenceDivergentControl.silentFamilies(EquivalenceComparison.compare(legacy, fsm2, MONITOR),
                                                                  EquivalenceComparison.compare(legacy, control, MONITOR))).doesNotContain("O5");
        }

        @Test
        void o4AndO5Gates_holdAtTheirBounds_andTripPastThem() {
            var legacy = arm("legacy", false);
            var atBound = new Arm("fsm2", legacy.quiescent(), legacy.liveNodes(), legacy.samples(), 12, 4_000L + 30_000L, Map.of());
            var past = new Arm("fsm2", legacy.quiescent(), legacy.liveNodes(), legacy.samples(), 13, 4_000L + 30_001L, Map.of());

            assertThat(EquivalenceComparison.compare(legacy, atBound, MONITOR)).isEmpty();
            assertThat(EquivalenceComparison.compare(legacy, past, MONITOR)).extracting(EquivalenceComparison.Divergence::family).containsExactly("O4", "O5");
        }
    }

    @Nested
    class Monitor {
        @Test
        void aScaleStep_mayOvershootByOne_butNotOutsideTheStep() {
            var samples = new ArrayList<EquivalenceMonitor.Sample>();
            var overshoot = legacyKv(3);

            overshoot.put(new AetherKey.NodeArtifactKey(node("n4"), SLICE), AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.LOADING));
            samples.add(new EquivalenceMonitor.Sample(0L, new EquivalenceKv(legacyKv(0))));
            samples.add(new EquivalenceMonitor.Sample(1_000L, new EquivalenceKv(overshoot)));
            assertThat(MONITOR.check(samples)).isEmpty();
            samples.add(new EquivalenceMonitor.Sample(20_000L, new EquivalenceKv(overshoot)));
            assertThat(MONITOR.check(samples)).extracting(EquivalenceMonitor.Violation::invariant).containsExactly("i");
        }

        @Test
        void aBreachShorterThanItsBound_isNotReported() {
            var samples = new ArrayList<>(deploySamples(true));
            var departed = fsm2Kv(3);

            departed.put(new AetherKey.NodeDepartureKey(CORES.getFirst()),
                         new AetherValue.NodeDepartureValue(AetherValue.DepartureState.DEPARTED, 1L, new AetherValue.ProcessIncarnation(1L, 1L), Option.none(), 0L));
            samples.add(new EquivalenceMonitor.Sample(26_000L, new EquivalenceKv(departed)));
            samples.add(new EquivalenceMonitor.Sample(31_000L, new EquivalenceKv(departed)));
            assertThat(MONITOR.check(samples)).as("held exactly one tick").isEmpty();
            samples.add(new EquivalenceMonitor.Sample(31_001L, new EquivalenceKv(departed)));
            assertThat(MONITOR.check(samples)).extracting(EquivalenceMonitor.Violation::invariant).containsExactly("iii");
        }
    }

    @Nested
    class Oracle {
        private final LeaderValue leader = LeaderValue.leaderValue(CORES.getFirst(), 4L);

        /// A converged LEGACY state (an unowned target, three ACTIVE instances on the three cores) projects to a state FSM2 leaves alone, and
        /// both D-8 (e) controls fire on it.
        @Test
        void convergedLegacyState_emitsNoSubmit_andBothControlsFire() {
            var verdict = EquivalenceOracle.check(new EquivalenceKv(legacyKv(3)), leader, Set.copyOf(CORES));

            assertThat(verdict.projectedInstances()).isEqualTo(3);
            assertThat(verdict.underPlacementControlFired()).as(verdict.describe()).isTrue();
            assertThat(verdict.strayControlFired()).as(verdict.describe()).isTrue();
            assertThat(verdict.disagreements()).as(verdict.describe()).isEmpty();
        }

        /// An under-placed LEGACY state is a real disagreement: the oracle reports the goal Submit (not on the frozen allow-list).
        @Test
        void underPlacedLegacyState_isADisagreement() {
            var verdict = EquivalenceOracle.check(new EquivalenceKv(legacyKv(2)), leader, Set.copyOf(CORES));

            assertThat(verdict.disagreements()).as(verdict.describe()).isNotEmpty();
        }

        /// After an undeploy the quiescent point holds no instance: the under-placement control is planted, and both controls still fire.
        @Test
        void emptyQuiescentPoint_stillFiresBothControls() {
            var verdict = EquivalenceOracle.check(new EquivalenceKv(Map.of()), leader, Set.copyOf(CORES));

            assertThat(verdict.controlsFired()).as(verdict.describe()).isTrue();
            assertThat(verdict.disagreements()).as(verdict.describe()).isEmpty();
        }

        @Test
        void theAllowList_isFrozenEmpty() {
            assertThat(EquivalenceOracle.allowList()).isEmpty();
        }
    }
}
