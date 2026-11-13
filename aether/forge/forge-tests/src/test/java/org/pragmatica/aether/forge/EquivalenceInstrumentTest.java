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
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

            assertThat(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, controlDivergences, List.of())).as(controlDivergences.toString()).isEmpty();
        }

        /// The control is judged against what FSM2 already shows: a family FSM2 already diverges on must still show the control's own finding.
        @Test
        void aControl_isNotCreditedWithADivergenceFsm2AlreadyHas() {
            var legacy = arm("legacy", false);
            var fsm2 = arm("fsm2", true);
            var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, MONITOR);

            assertThat(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, fsm2Divergences, List.of())).containsExactlyElementsOf(EquivalenceDivergentControl.FAMILIES);
        }

        /// E-09 regression: LEGACY wrote FAILED and FSM2 wrote ROLLED_BACK; the O1 control must still differ from LEGACY (it once flipped to FAILED).
        @Test
        void o1Control_firesWhenLegacyFailedAndFsm2RolledBack() {
            var legacy = withVerdict(arm("legacy", false), AetherValue.DeploymentOutcomeStatus.FAILED);
            var fsm2 = withVerdict(arm("fsm2", true), AetherValue.DeploymentOutcomeStatus.ROLLED_BACK);
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
            var silent = EquivalenceDivergentControl.silentFamilies(EquivalenceComparison.compare(legacy, fsm2, MONITOR), EquivalenceComparison.compare(legacy, control, MONITOR), List.of());

            assertThat(silent).doesNotContain("O1");
        }

        /// The E-09 ruled verdict (know 8018203973): LEGACY FAILED vs FSM2 ROLLED_BACK is no O1 divergence on E-09, and the O1 control (FSM2's
        /// verdict flipped to SUCCEEDED) still fires there with the mapping in place.
        @Test
        void e09RuledVerdict_hidesFailedVsRolledBack_andTheO1ControlStillFires() {
            var legacy = withVerdict(arm("legacy", false), AetherValue.DeploymentOutcomeStatus.FAILED);
            var fsm2 = withVerdict(arm("fsm2", true), AetherValue.DeploymentOutcomeStatus.ROLLED_BACK);
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
            var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, MONITOR, "E09");
            var controlDivergences = EquivalenceComparison.compare(legacy, control, MONITOR, "E09");

            assertThat(fsm2Divergences).extracting(EquivalenceComparison.Divergence::family).doesNotContain("O1");
            assertThat(controlDivergences).extracting(EquivalenceComparison.Divergence::family).contains("O1");
            assertThat(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, controlDivergences, List.of())).doesNotContain("O1");
        }

        /// The mapping is scoped: outside E-09 the same pair diverges, and on E-09 only the ruled pair is mapped (FSM2 SUCCEEDED vs LEGACY FAILED,
        /// or ROLLED_BACK against a LEGACY SUCCEEDED, still diverge).
        @Test
        void e09RuledVerdict_appliesToE09AndToTheRuledPairOnly() {
            var failed = withVerdict(arm("legacy", false), AetherValue.DeploymentOutcomeStatus.FAILED);
            var succeeded = withVerdict(arm("legacy", false), AetherValue.DeploymentOutcomeStatus.SUCCEEDED);
            var rolledBack = withVerdict(arm("fsm2", true), AetherValue.DeploymentOutcomeStatus.ROLLED_BACK);
            var fsm2Succeeded = withVerdict(arm("fsm2", true), AetherValue.DeploymentOutcomeStatus.SUCCEEDED);

            assertThat(EquivalenceComparison.compare(failed, rolledBack, MONITOR, "E01")).extracting(EquivalenceComparison.Divergence::family).contains("O1");
            assertThat(EquivalenceComparison.compare(failed, rolledBack, MONITOR)).extracting(EquivalenceComparison.Divergence::family).contains("O1");
            assertThat(EquivalenceComparison.compare(failed, fsm2Succeeded, MONITOR, "E09")).extracting(EquivalenceComparison.Divergence::family).contains("O1");
            assertThat(EquivalenceComparison.compare(succeeded, rolledBack, MONITOR, "E09")).extracting(EquivalenceComparison.Divergence::family).contains("O1");
        }

        /// The E-09 O4 allow-list (CTO R2): a slot made only of rollback transitions is excluded on E-09; a slot that mixes one in with anything
        /// else (the ROLLED_BACK terminal batch, which also promotes a goal) and every load-side transition stay counted; no other scenario excludes.
        @Test
        void e09RollbackAllowList_excludesPureRollbackSlotsOnly() {
            var slots = Map.of(1L, List.of("SliceObservedKey put LOADING slice-observed/n1/a"),
                               2L, List.of("SliceObservedKey put ACTIVE slice-observed/n1/a"),
                               3L, List.of("SliceDesiredKey put UNLOADED slice-desired/n1/a",
                                           "SliceDesiredKey put ACTIVE slice-desired/n1/b",
                                           "DeploymentOutcomeKey put ROLLED_BACK deployment-outcome/x"),
                               4L, List.of("SliceObservedKey put UNLOADING slice-observed/n1/a"),
                               5L, List.of("SliceObservedKey put UNLOADED slice-observed/n1/a"),
                               6L, List.of("SliceObservedKey remove - slice-observed/n1/a"),
                               7L, List.of("SliceDesiredKey remove - slice-desired/n1/a"),
                               8L, List.of("SliceTargetKey remove - slice-target/a", "SliceTargetKey remove - slice-target/b"),
                               9L, List.of("SliceDesiredKey put UNLOADED slice-desired/n1/b", "SliceDesiredKey put UNLOADED slice-desired/n2/b"));

            assertThat(EquivalenceComparison.o4Counted(slots, "E09")).isEqualTo(3L);
            assertThat(EquivalenceComparison.o4Counted(slots, "E01")).isEqualTo(9L);
            assertThat(EquivalenceComparison.o4Counted(slots, "")).isEqualTo(9L);
        }

        /// The O4 placement gate (CTO ruling on #2763): LEGACY placing an artifact on 1 node and FSM2 on 3 (the dev9 E-09 shape) read as different
        /// placements; the same per-artifact node counts read as equal across engines although the families and node ids differ; teardown puts
        /// and removes place nothing.
        @Test
        void placement_comparesPerArtifactNodeCountsAcrossEngines() {
            var legacyOneNode = Map.of(1L, List.of("NodeArtifactKey put LOAD node-artifact/l1/a", "NodeArtifactKey put LOAD node-artifact/l1/b"),
                                       2L, List.of("NodeArtifactKey put UNLOAD node-artifact/l2/a", "NodeArtifactKey remove - node-artifact/l3/a"));
            var legacyThreeNodes = Map.of(1L, List.of("NodeArtifactKey put LOAD node-artifact/l1/a", "NodeArtifactKey put LOAD node-artifact/l2/a"),
                                          2L, List.of("NodeArtifactKey put LOADING node-artifact/l3/a", "NodeArtifactKey put FAILED node-artifact/l1/b"),
                                          3L, List.of("NodeArtifactKey put LOAD node-artifact/l2/b", "NodeArtifactKey put LOAD node-artifact/l3/b"));
            var fsm2ThreeNodes = Map.of(1L, List.of("SliceDesiredKey put LOADED slice-desired/f1/a",
                                                    "SliceDesiredKey put LOADED slice-desired/f2/a",
                                                    "SliceDesiredKey put LOADED slice-desired/f3/a",
                                                    "DeploymentOutcomeKey put IN_PROGRESS deployment-outcome/x"),
                                        2L, List.of("SliceObservedKey put LOADING slice-observed/f1/b", "SliceObservedKey put LOADING slice-observed/f2/b"),
                                        3L, List.of("SliceObservedKey put FAILED slice-observed/f3/b", "SliceObservedKey put UNLOADED slice-observed/f4/b"));

            assertThat(EquivalenceComparison.placement(legacyOneNode)).isEqualTo("[a=1 nodes, b=1 nodes]");
            assertThat(EquivalenceComparison.placement(fsm2ThreeNodes)).isEqualTo("[a=3 nodes, b=3 nodes]");
            assertThat(EquivalenceComparison.placement(legacyThreeNodes)).isEqualTo(EquivalenceComparison.placement(fsm2ThreeNodes));
            assertThat(EquivalenceComparison.placement(legacyOneNode)).isNotEqualTo(EquivalenceComparison.placement(fsm2ThreeNodes));
        }

        private static Arm withVerdict(Arm arm, AetherValue.DeploymentOutcomeStatus status) {
            var kv = new HashMap<>(arm.quiescent().entries());

            kv.put(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT), new AetherValue.DeploymentOutcomeValue(status, List.of(), "", 1L, 2L, "a1"));

            return new Arm(arm.engine(), new EquivalenceKv(kv), arm.liveNodes(), arm.samples(), arm.o4Batches(), arm.o5PlacementMs(), arm.o6());
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

        /// The tombstone exemption ends with the 120 s grace: a tombstone of a departed node issued exactly 120 s before the snapshot is exempt, one
        /// millisecond older is residue. Mutation: drop the age bound (exempt every tombstone); the second assertion goes red. Mutation: `<=` -> `<`
        /// on the bound; the first goes red.
        @Test
        void tombstone_isExemptOnlyInsideTheGrace() {
            var kv = fsm2Kv(3);
            var gone = node("gone");

            kv.put(AetherKey.SliceDesiredKey.sliceDesiredKey(gone, SLICE), new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.UNLOADED, new AetherValue.Generation(1L, 9L), 1_000L));
            var atBound = new Arm("fsm2", new EquivalenceKv(kv), Set.copyOf(CORES), List.of(), 1, 1L, Map.of(), List.of(), 1_000L + EquivalenceComparison.TOMBSTONE_GRACE_MS);
            var past = new Arm("fsm2", new EquivalenceKv(kv), Set.copyOf(CORES), List.of(), 1, 1L, Map.of(), List.of(), 1_001L + EquivalenceComparison.TOMBSTONE_GRACE_MS);

            assertThat(EquivalenceComparison.residue(atBound)).isEmpty();
            assertThat(EquivalenceComparison.residue(past)).hasSize(1);
        }

        /// A scenario that places nothing (E-09: both arms -1) compares nothing for O5: the family is not measured, and the control plants no
        /// placement to credit it. Mutation: plant `1L` whenever FSM2 is negative (the old fallback); `control.o5PlacementMs()` goes red.
        @Test
        void o5_isNotMeasured_whenNeitherArmPlaces_andNothingIsPlanted() {
            var legacy = new Arm("legacy", new EquivalenceKv(legacyKv(0)), Set.copyOf(CORES), deploySamples(false), 1, -1L, Map.of());
            var fsm2 = new Arm("fsm2", new EquivalenceKv(fsm2Kv(0)), Set.copyOf(CORES), deploySamples(true), 1, -1L, Map.of());
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
            var notMeasured = EquivalenceComparison.notMeasured(legacy, fsm2);

            assertThat(control.o5PlacementMs()).isEqualTo(-1L);
            assertThat(notMeasured).contains("O5");
            assertThat(EquivalenceComparison.compare(legacy, control, MONITOR)).extracting(EquivalenceComparison.Divergence::family).doesNotContain("O5");
            assertThat(EquivalenceDivergentControl.silentFamilies(EquivalenceComparison.compare(legacy, fsm2, MONITOR),
                                                                  EquivalenceComparison.compare(legacy, control, MONITOR),
                                                                  notMeasured)).doesNotContain("O5");
            assertThat(EquivalenceDivergentControl.silentFamilies(EquivalenceComparison.compare(legacy, fsm2, MONITOR),
                                                                  EquivalenceComparison.compare(legacy, control, MONITOR),
                                                                  List.of())).as("measured, the unplanted family would be silent").contains("O5");
        }

        /// E-03 shape: the undeploy leaves no outcome and no target in either arm, so O1 and O2 compared nothing. Neither control plants a fallback
        /// (a SUCCEEDED outcome, an unplaced target); both families are reported not measured. Mutation: restore either fallback plant; the matching
        /// `doesNotContain` goes red.
        @Test
        void o1AndO2_areNotMeasured_whenThereIsNoOutcomeAndNoTarget_andNothingIsPlanted() {
            var legacy = new Arm("legacy", new EquivalenceKv(Map.of()), Set.copyOf(CORES), deploySamples(false), 1, 4_000L, Map.of());
            var fsm2 = new Arm("fsm2", new EquivalenceKv(Map.of()), Set.copyOf(CORES), deploySamples(true), 1, 4_000L, Map.of());
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);
            var notMeasured = EquivalenceComparison.notMeasured(legacy, fsm2);
            var controlFamilies = EquivalenceComparison.compare(legacy, control, MONITOR).stream().map(EquivalenceComparison.Divergence::family).toList();

            assertThat(notMeasured).containsExactly("O1", "O2");
            assertThat(controlFamilies).doesNotContain("O1", "O2");
            assertThat(EquivalenceDivergentControl.silentFamilies(EquivalenceComparison.compare(legacy, fsm2, MONITOR),
                                                                  EquivalenceComparison.compare(legacy, control, MONITOR),
                                                                  notMeasured)).isEmpty();
        }

        /// A target with no instance placed anywhere is measured by O2, so its control still plants the unplaced target: the fallback is kept
        /// only where the real comparison had something to compare.
        @Test
        void o2_isMeasuredWithAnUnplacedTarget_andItsControlStillFires() {
            var legacy = new Arm("legacy", new EquivalenceKv(shared(3)), Set.copyOf(CORES), deploySamples(false), 1, 4_000L, Map.of());
            var fsm2 = new Arm("fsm2", new EquivalenceKv(shared(3)), Set.copyOf(CORES), deploySamples(true), 1, 4_000L, Map.of());
            var control = EquivalenceDivergentControl.of(fsm2, legacy, MONITOR);

            assertThat(EquivalenceComparison.notMeasured(legacy, fsm2)).doesNotContain("O2");
            assertThat(EquivalenceComparison.compare(legacy, control, MONITOR)).extracting(EquivalenceComparison.Divergence::family).contains("O2");
        }

        /// The monitor is not measured below the sample floor, in either arm, and the control plants no tail on an arm without samples.
        /// Mutation: plant a tail after a synthetic sample when `samples` is empty (the old fallback); the control-sample assertion goes red.
        @Test
        void monitor_isNotMeasured_belowTheSampleFloor_andNothingIsPlanted() {
            var legacy = arm("legacy", false);
            var blind = new Arm("fsm2", fsm2Arm().quiescent(), Set.copyOf(CORES), List.of(), 10, 4_000L, Map.of());
            var floorMinusOne = new Arm("fsm2", fsm2Arm().quiescent(), Set.copyOf(CORES), deploySamples(true).subList(0, EquivalenceMonitor.MIN_SAMPLES - 1), 10, 4_000L, Map.of());
            var atFloor = new Arm("fsm2", fsm2Arm().quiescent(), Set.copyOf(CORES), deploySamples(true).subList(0, EquivalenceMonitor.MIN_SAMPLES), 10, 4_000L, Map.of());

            assertThat(EquivalenceComparison.notMeasured(legacy, blind)).contains("MON-i", "MON-ii", "MON-iii", "MON-iv");
            assertThat(EquivalenceComparison.notMeasured(legacy, floorMinusOne)).contains("MON-i");
            assertThat(EquivalenceComparison.notMeasured(legacy, atFloor)).doesNotContain("MON-i");
            assertThat(EquivalenceComparison.notMeasured(blind, legacy)).as("either arm").contains("MON-i");
            assertThat(EquivalenceDivergentControl.of(blind, legacy, MONITOR).samples()).isEmpty();
            assertThat(EquivalenceDivergentControl.silentFamilies(List.of(), List.of(), EquivalenceComparison.notMeasured(legacy, blind))).doesNotContain("MON-i", "MON-ii", "MON-iii", "MON-iv");
        }

        private static Arm fsm2Arm() {
            return arm("fsm2", true);
        }

        /// A blind sampler cannot reach the comparison: below the floor the arm fails, at the floor it does not.
        @Test
        void requireSamples_failsTheArmBelowTheFloor() {
            EquivalenceMonitor.requireSamples("fsm2", EquivalenceMonitor.MIN_SAMPLES);
            assertThatThrownBy(() -> EquivalenceMonitor.requireSamples("fsm2", EquivalenceMonitor.MIN_SAMPLES - 1)).isInstanceOf(IllegalStateException.class)
                                                                                                                     .hasMessageContaining("fsm2")
                                                                                                                     .hasMessageContaining("monitor samples");
        }

        /// A non-quiescent arm (o4 = -1) is a VOID of the round, not an O4 DIVERGE (vf4a 7). Mutations: `voids` returning empty; dropping a
        /// `hasO4Count` guard in `o4` (legacy -1 against 10 then reads `10 > -1.2`): each goes red.
        @Test
        void aNonQuiescentArm_isAVoid_notAnO4Divergence() {
            var legacy = arm("legacy", false);
            var fsm2 = arm("fsm2", true);
            var fsm2NotQuiescent = new Arm("fsm2", fsm2.quiescent(), fsm2.liveNodes(), fsm2.samples(), -1, 4_000L, Map.of());
            var legacyNotQuiescent = new Arm("legacy", legacy.quiescent(), legacy.liveNodes(), legacy.samples(), -1, 4_000L, Map.of());

            assertThat(EquivalenceComparison.compare(legacy, fsm2NotQuiescent, MONITOR)).isEmpty();
            assertThat(EquivalenceComparison.compare(legacyNotQuiescent, fsm2, MONITOR)).isEmpty();
            assertThat(EquivalenceComparison.voids(legacy, fsm2NotQuiescent)).singleElement().asString().contains("O4 void count", "fsm2=-1");
            assertThat(EquivalenceComparison.voids(legacyNotQuiescent, fsm2)).singleElement().asString().contains("legacy=-1");
            assertThat(EquivalenceComparison.voids(legacy, fsm2)).isEmpty();
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

        /// E09 r7 on LEGACY (vf4a): the target was put and removed between two 1 s samples while three instances were still LOADING. The sample at
        /// +1000 ms holds live=3 and no target, so a monitor fed by samples alone reads bound=0 and reports (i). Fed the ring's target transitions
        /// it opens the scale window at the put (peak 3) and the bound is 4. Mutation: ignore the events in `check`; the second assertion goes red.
        @Test
        void aTargetPutAndRemovedBetweenSamples_opensItsScaleWindowFromTheRing() {
            var loading = new HashMap<AetherKey, AetherValue>();

            for (var index = 0; index < 3; index++) {
                loading.put(new AetherKey.NodeArtifactKey(CORES.get(index), SLICE), AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.LOADING));
            }
            var samples = List.of(new EquivalenceMonitor.Sample(0L, new EquivalenceKv(Map.of())), new EquivalenceMonitor.Sample(1_000L, new EquivalenceKv(loading)));
            var events = List.of(new EquivalenceMonitor.TargetEvent(300L, SLICE.base(), 3), new EquivalenceMonitor.TargetEvent(900L, SLICE.base(), 0));

            assertThat(MONITOR.check(samples)).as("samples alone cannot see the step").extracting(EquivalenceMonitor.Violation::invariant).containsExactly("i");
            assertThat(MONITOR.check(samples, events)).as("the ring saw the step").isEmpty();
        }

        /// The ring-fed window is still a window: the same live=3 with no target long after the removal is a breach.
        @Test
        void aRingRecordedStep_doesNotExcuseInstancesOutsideItsWindow() {
            var loading = new HashMap<AetherKey, AetherValue>();

            for (var index = 0; index < 3; index++) {
                loading.put(new AetherKey.NodeArtifactKey(CORES.get(index), SLICE), AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.LOADING));
            }
            var samples = List.of(new EquivalenceMonitor.Sample(0L, new EquivalenceKv(Map.of())), new EquivalenceMonitor.Sample(20_000L, new EquivalenceKv(loading)));
            var events = List.of(new EquivalenceMonitor.TargetEvent(300L, SLICE.base(), 3), new EquivalenceMonitor.TargetEvent(900L, SLICE.base(), 0));

            assertThat(MONITOR.check(samples, events)).extracting(EquivalenceMonitor.Violation::invariant).containsExactly("i");
        }

        /// The ring-side extraction: a put carries the new target, a remove 0, a leader transaction its target mutations only.
        @Test
        void targetTransitions_readPutsRemovesAndTransactionMutations() {
            var ring = CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, _ -> 16, Runnable::run);
            var key = AetherKey.SliceTargetKey.sliceTargetKey(SLICE.base());
            var other = AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT);
            var target = AetherValue.SliceTargetValue.sliceTargetValue(SLICE.version(), 3);
            var tag = IncarnationTag.incarnationTag(1, "f4");

            ring.invalidate(tag, 0);
            ring.append(tag, 0, "put", List.of(new KVCommand.Put<>(key, target)), 1);
            ring.append(tag, 1, "remove", List.of(new KVCommand.Remove<>(key)), 2);
            ring.append(tag, 2,
                        "tx",
                        List.of(new KVCommand.LeaderTransaction<>(key,
                                                                  "t1",
                                                                  LeaderValue.leaderValue(CORES.getFirst(), 4L),
                                                                  List.of(),
                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(other, Option.none(), Option.none()),
                                                                          new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.some(target), Option.none())))),
                        3);
            var events = ring.entries().stream().flatMap(entry -> Fsm2EquivalenceRoundTest.targetTransitions(entry, 7L).stream()).toList();

            assertThat(events).containsExactly(new EquivalenceMonitor.TargetEvent(7L, SLICE.base(), 3),
                                               new EquivalenceMonitor.TargetEvent(7L, SLICE.base(), 0),
                                               new EquivalenceMonitor.TargetEvent(7L, SLICE.base(), 0));
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

    /// The O4 ring reader ([Fsm2EquivalenceRoundTest.RingCursor]) over a real [CommitRing]: a ring empty at the first read drops nothing, a floor
    /// that jumps past the read position is a gap, and a gap on every node read is an instrument error (round ERROR), never an O4 value (#2763).
    @Nested
    class RingReader {
        private static final IncarnationTag TAG = IncarnationTag.incarnationTag(1, "f4");

        private static CommitRing ring(long floor) {
            var ring = CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, _ -> 16, Runnable::run);

            ring.invalidate(TAG, floor);

            return ring;
        }

        private static void append(CommitRing ring, long slot) {
            ring.append(TAG, slot, "id-" + slot, List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, "v" + slot)), slot + 1);
        }

        /// A ring cleared at the frontier (floor 40, no entry) dropped nothing a recorder could have read; a cursor starting at 0 would report
        /// `[0, 40)` as a gap. Mutation: start the first read at 0 instead of the floor; this goes red.
        @Test
        void aRingEmptyAtTheFirstRead_dropsNothing_andLaterEntriesAreRead() {
            var ring = ring(40);
            var cursor = new Fsm2EquivalenceRoundTest.RingCursor("n1");

            assertThat(cursor.read(ring)).isEmpty();
            append(ring, 40);
            append(ring, 41);
            assertThat(cursor.read(ring)).extracting(ReplicaEntry::slot).containsExactly(40L, 41L);
            assertThat(cursor.read(ring)).isEmpty();
            assertThat(cursor.gapped()).as(cursor.describe()).isFalse();
            Fsm2EquivalenceRoundTest.RingCursor.requireCovered(List.of(cursor));
        }

        /// A clear between reads (an incarnation-touching batch, as in F4 r1/r3; a snapshot install) loses the batches after the position, and
        /// the clearing batch itself. Mutation: drop the
        /// `floorBefore > start` check; this goes red.
        @Test
        void aFloorAboveThePosition_isAGap() {
            var ring = ring(0);
            var cursor = new Fsm2EquivalenceRoundTest.RingCursor("n1");

            append(ring, 0);
            assertThat(cursor.read(ring)).hasSize(1);
            append(ring, 1);
            ring.invalidate(TAG, 5);
            append(ring, 5);
            assertThat(cursor.read(ring)).extracting(ReplicaEntry::slot).containsExactly(5L);
            assertThat(cursor.gapped()).isTrue();
            assertThat(cursor.describe()).as("a gap before the read, not one during it").endsWith("gaps=[[1,5)]");
        }

        /// A gap on every node read makes the O4 count unknown: an exception (the round's arm ERROR), not a negative count. One node that read
        /// the window covers it. Mutation: return instead of throwing in `requireCovered`; the first assertion goes red.
        @Test
        void aGapOnEveryNode_isAnInstrumentError_andOneCleanNodeCoversIt() {
            var gapped = List.of(gappedCursor("n1"), gappedCursor("n2"), gappedCursor("n3"));

            assertThatThrownBy(() -> Fsm2EquivalenceRoundTest.RingCursor.requireCovered(gapped)).isInstanceOf(IllegalStateException.class)
                                                                                                 .hasMessageContaining("dropped entries on every node")
                                                                                                 .hasMessageContaining("n3");
            var clean = new Fsm2EquivalenceRoundTest.RingCursor("n4");

            clean.read(ring(0));
            Fsm2EquivalenceRoundTest.RingCursor.requireCovered(List.of(gapped.get(0), gapped.get(1), clean));
        }

        private static Fsm2EquivalenceRoundTest.RingCursor gappedCursor(String node) {
            var ring = ring(0);
            var cursor = new Fsm2EquivalenceRoundTest.RingCursor(node);

            append(ring, 0);
            cursor.read(ring);
            ring.invalidate(TAG, 9);
            cursor.read(ring);

            return cursor;
        }
    }
}
