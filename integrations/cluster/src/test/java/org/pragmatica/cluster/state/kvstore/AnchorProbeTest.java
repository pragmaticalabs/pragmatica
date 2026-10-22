// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.AnchorProbeModel.Replica;
import org.pragmatica.cluster.state.kvstore.AnchorProbeModel.SimRing;
import org.pragmatica.cluster.state.kvstore.AnchorProbeModel.Verdict;
import org.pragmatica.cluster.state.kvstore.ReplicaFeedModel.Core;
import org.pragmatica.consensus.rabia.infrastructure.OlderCheckpointCluster;
import org.pragmatica.consensus.rabia.infrastructure.OlderCheckpointCluster.Applied;

import static org.assertj.core.api.Assertions.assertThat;

/// P-0 (W-OBS design section 9; NEVER MERGED): what a position-only replica, a single-pair anchor and an 8-pair anchor each do when a
/// cluster restarts from an OLDER checkpoint under one identity and commits different batches at the reused slots.
///
/// The ring, the anchors and the replica are SIMULATED (`AnchorProbeModel`): they do not exist before W1. Everything they are fed is
/// real: the batches, slots and batch ids come from real `RabiaEngine`s and real `KVStore`s (`OlderCheckpointCluster`), the older
/// checkpoint comes from a real pause (`RabiaEngine.saveState`), the restart restores it through the engine's own-state path.
///
/// EXPECTED RESULTS, written down BEFORE the first run (commit order shows it):
///
/// - (a) positive control. Scenario S1: the second history differs from the first at every slot after the checkpoint. A position-only
///   replica that followed the first history resubscribes to the restarted cluster: ACCEPTED (its position is not ahead), and after
///   catching up its state DIVERGES from the source's. If this arm did not diverge, nothing below would mean anything.
/// - (b) single-pair anchor, S1: REFUSED (the replica's newest pair carries a different batch id in the source). With the batch-id
///   comparison dropped (mutation) it is ACCEPTED, which is what turns (b) red; that is only meaningful because (a) diverged.
/// - (c) collision arm, scenario S2: histories differ at the first two slots after the checkpoint and the NEWEST batch is byte-identical
///   (same commands, so same content-derived id at the same slot). Single-pair anchor: ACCEPTED, and the replica's state diverges
///   silently. This is the residual the design admits (equal id at equal slot is evidence, not proof).
/// - (d) 8-pair anchor. (d1) on S2: REFUSED (the divergence is inside the window: two of the three post-checkpoint pairs differ).
///   (d2) scenario S3, divergence at the FIRST post-checkpoint slot followed by 8 byte-identical batches: ACCEPTED, silently diverged
///   (the divergence is below the window). (d3) the boundary, S3 with 7 identical batches after the divergence: REFUSED (the window
///   still reaches the divergent batch). So k = 8 needs a divergence at least 8 effect batches deep to be invisible.
/// - (e) interleave arm: real commits drive a simulated source that bumps its frontier, lets a heartbeat fall in the window, then
///   offers the entry. The draft-2 replica (a heartbeat advances applied) LOSES the effect of every batch (positive control); the
///   design replica (only entries advance applied) loses none; with the offer inside the append (no window) even the draft-2 replica
///   loses none.
class AnchorProbeTest {
    private record Cmd(String key, String value) {}

    private record Run(List<Applied> firstHistory, Map<String, String> firstState,
                       List<Applied> secondHistory, Map<String, String> secondState, long floor) {
        SimRing ring() {
            return new SimRing(secondHistory, floor);
        }
    }

    private static final List<Cmd> COMMON = List.of(new Cmd("c0", "kept"), new Cmd("c1", "kept"));

    private static Map<String, String> stateOf(OlderCheckpointCluster.RecordingStore store) {
        return store.snapshot().entrySet().stream().collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
    }

    /// Runs the crash scenario on a real cluster: commit the common prefix, take a real pause-time checkpoint, commit `first`, crash,
    /// restart every node from the checkpoint, commit `second`. Records node-1's history and state on both sides.
    private static Run run(List<Cmd> first, List<Cmd> second) {
        var generation1 = OlderCheckpointCluster.fresh().awaitActive();
        Map<org.pragmatica.consensus.NodeId, org.pragmatica.consensus.rabia.RabiaPersistence.SavedState<KVCommand<org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey>>> checkpoints;
        long floor;
        List<Applied> firstHistory;
        Map<String, String> firstState;

        try {
            COMMON.forEach(cmd -> generation1.commit(cmd.key(), cmd.value()));
            checkpoints = generation1.pauseAndCheckpoint();
            floor = checkpoints.get(generation1.node(1).id()).lastCommittedPhase().value();
            first.forEach(cmd -> generation1.commit(cmd.key(), cmd.value()));
            firstHistory = generation1.node(1).store().applied();
            firstState = stateOf(generation1.node(1).store());
        } finally {
            generation1.close();
        }

        var generation2 = OlderCheckpointCluster.restartedFrom(checkpoints).awaitActive();

        try {
            second.forEach(cmd -> generation2.commit(cmd.key(), cmd.value()));

            return new Run(firstHistory, firstState, generation2.node(1).store().applied(), stateOf(generation2.node(1).store()), floor);
        } finally {
            generation2.close();
        }
    }

    private static List<Cmd> same(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> new Cmd("s" + index, "same-" + index)).toList();
    }

    private static List<Cmd> concat(List<Cmd> head, List<Cmd> tail) {
        var all = new ArrayList<>(head);

        all.addAll(tail);

        return all;
    }

    private static void report(String arm, Verdict verdict, boolean diverged) {
        System.out.println("P0-RESULT " + arm + ": " + verdict.outcome() + " (" + verdict.reason() + ")" + (verdict.accepted() ? " diverged=" + diverged : ""));
    }

    private static boolean divergedAfterAccepting(Run run, Replica replica) {
        replica.catchUp(run.ring());

        return !replica.state().equals(run.secondState());
    }

    private static void assertSlotsContiguous(Run run) {
        assertThat(run.firstHistory().stream().map(Applied::slot).toList()).as("precondition: the first history is one batch per slot from 0").isEqualTo(LongStreamRange.of(0, run.firstHistory().size()));
        assertThat(run.secondHistory().stream().map(Applied::slot).toList()).as("precondition: the restarted history is one batch per slot from the checkpoint slot %d", run.floor())
                                                                           .isEqualTo(LongStreamRange.of(run.floor(), run.secondHistory().size()));
    }

    private static final class LongStreamRange {
        static List<Long> of(long from, int count) {
            return IntStream.range(0, count).mapToObj(index -> from + index).toList();
        }
    }

    /// S1: every post-checkpoint batch differs; the second history is longer so the replica's position is not ahead of the source.
    private static Run s1() {
        return run(List.of(new Cmd("k1", "old-1"), new Cmd("k2", "old-2"), new Cmd("k3", "old-3")),
                   List.of(new Cmd("k1", "new-1"), new Cmd("k2", "new-2"), new Cmd("k3", "new-3"), new Cmd("k4", "new-4")));
    }

    /// S2: the first two post-checkpoint batches differ, the newest (third) is byte-identical.
    private static Run s2() {
        return run(List.of(new Cmd("k1", "x1"), new Cmd("k2", "x2"), new Cmd("k3", "same")),
                   List.of(new Cmd("k1", "y1"), new Cmd("k2", "y2"), new Cmd("k3", "same"), new Cmd("k4", "extra")));
    }

    /// S3: the first post-checkpoint batch differs, then `identical` byte-identical batches follow.
    private static Run s3(int identical) {
        return run(concat(List.of(new Cmd("k0", "x0")), same(identical)),
                   concat(concat(List.of(new Cmd("k0", "y0")), same(identical)), List.of(new Cmd("k9", "extra"))));
    }

    @Test
    void armA_positionOnlyReplica_divergesAfterAnOlderCheckpointRestore() {
        var run = s1();

        assertSlotsContiguous(run);
        var replica = Replica.following(run.firstHistory());

        assertThat(replica.state()).as("control: the replica model followed the first history faithfully").isEqualTo(run.firstState());

        var verdict = AnchorProbeModel.positionOnly(replica, run.ring());
        var diverged = verdict.accepted() && divergedAfterAccepting(run, replica);

        report("a position-only on S1", verdict, diverged);
        assertThat(verdict.accepted()).as("a position-only replica is not refused: its position is not ahead").isTrue();
        assertThat(diverged).as("(a) positive control: it accepted and its state diverged from the restarted cluster's").isTrue();
    }

    @Test
    void armB_singlePairAnchor_refusesIt_andDroppingTheIdCheckIsWhatBreaksIt() {
        var run = s1();
        var replica = Replica.following(run.firstHistory());
        var verdict = AnchorProbeModel.anchored(replica, run.ring(), 1, true);

        report("b single-pair on S1", verdict, false);
        assertThat(verdict.accepted()).as("(b) the newest pair's batch id differs in the source, so the single-pair anchor refuses").isFalse();

        var mutant = AnchorProbeModel.anchored(replica, run.ring(), 1, false);

        report("b single-pair on S1, id check dropped (mutation)", mutant, true);
        assertThat(mutant.accepted()).as("mutation: without the batch-id comparison the same anchor accepts, so the refusal above is the id check").isTrue();
    }

    @Test
    void armC_collisionArm_singlePairAcceptsWhenOnlyTheNewestBatchIsByteIdentical() {
        var run = s2();

        assertSlotsContiguous(run);
        var replica = Replica.following(run.firstHistory());
        var newest = replica.lastPairs(1).getFirst();

        assertThat(run.ring().idAt(newest.slot())).as("precondition: the newest batch is byte-identical (same id at the same slot)").isEqualTo(newest.batchId());

        var verdict = AnchorProbeModel.anchored(replica, run.ring(), 1, true);
        var diverged = verdict.accepted() && divergedAfterAccepting(run, replica);

        report("c single-pair on S2 (newest identical)", verdict, diverged);
        assertThat(verdict.accepted()).as("(c) a single-pair anchor accepts: equal id at equal slot is all it checks").isTrue();
        assertThat(diverged).as("(c) and the replica's state silently diverged").isTrue();
    }

    @Test
    void armD1_eightPairAnchor_refusesWhenTheDivergenceIsInsideTheWindow() {
        var run = s2();
        var replica = Replica.following(run.firstHistory());
        var verdict = AnchorProbeModel.anchored(replica, run.ring(), Replica.K, true);

        report("d1 8-pair on S2 (divergence inside the window)", verdict, false);
        assertThat(verdict.accepted()).as("(d1) the divergent pairs at the first two post-checkpoint slots are inside the window and verifiable").isFalse();
    }

    @Test
    void armD2_eightPairAnchor_acceptsWhenTheDivergenceIsBelowTheWindow() {
        var run = s3(Replica.K);

        assertSlotsContiguous(run);
        var replica = Replica.following(run.firstHistory());
        var verdict = AnchorProbeModel.anchored(replica, run.ring(), Replica.K, true);
        var diverged = verdict.accepted() && divergedAfterAccepting(run, replica);

        report("d2 8-pair on S3 (divergence 8 batches deep)", verdict, diverged);
        assertThat(verdict.accepted()).as("(d2) the last 8 pairs are the byte-identical batches; the divergence is below the window").isTrue();
        assertThat(diverged).as("(d2) the replica silently diverged").isTrue();
    }

    @Test
    void armD3_eightPairAnchor_refusesAtTheBoundary_whenTheWindowStillReachesTheDivergence() {
        var run = s3(Replica.K - 1);
        var replica = Replica.following(run.firstHistory());
        var verdict = AnchorProbeModel.anchored(replica, run.ring(), Replica.K, true);

        report("d3 8-pair on S3 (divergence 7 batches deep, boundary)", verdict, false);
        assertThat(verdict.accepted()).as("(d3) with 7 identical batches after the divergence the 8-pair window includes the divergent batch").isFalse();
    }

    /// The simulated source of arm (e): for every batch the real store commits it bumps its frontier, lets a heartbeat fall into the
    /// window (when `interleave` is set), then offers the entry. One source per replica rule, fed by the same real commits.
    private static final class SimSource {
        private final Core core = new Core(0);
        private final boolean interleave;

        SimSource(boolean interleave) {
            this.interleave = interleave;
        }

        void onCommitted(Applied entry) {
            var effects = AnchorProbeModel.effectsOf(entry).entrySet().stream().map(effect -> effect.getKey() + "=" + effect.getValue()).toList();

            if (interleave) {
                core.bump(effects);
                core.heartbeat();
                core.offerPending();
            } else {
                core.appendAtomically(effects);
                core.heartbeat();
            }
        }

        List<ReplicaFeedModel.Message> delivered() {
            return core.delivered();
        }
    }

    @Test
    void armE_interleave_draft2RuleLosesTheEffect_designRuleDoesNot() {
        var windowed = new SimSource(true);
        var atomic = new SimSource(false);
        var cluster = OlderCheckpointCluster.fresh().awaitActive();

        try {
            cluster.node(1).store().onCommitted(entry -> {
                windowed.onCommitted(entry);
                atomic.onCommitted(entry);
            });
            cluster.commit("e1", "one").commit("e2", "two").commit("e3", "three");

            var expected = cluster.node(1).store().applied().stream().flatMap(entry -> AnchorProbeModel.effectsOf(entry).entrySet().stream()).map(effect -> effect.getKey() + "=" + effect.getValue()).toList();
            var draft2 = new ReplicaFeedModel.Replica(0, true);
            var design = new ReplicaFeedModel.Replica(0, false);
            var draft2Atomic = new ReplicaFeedModel.Replica(0, true);

            draft2.receive(windowed.delivered());
            design.receive(windowed.delivered());
            draft2Atomic.receive(atomic.delivered());

            System.out.println("P0-RESULT e draft-2 rule, heartbeat in the window: effects held " + draft2.effects().size() + " of " + expected.size());
            System.out.println("P0-RESULT e design rule, heartbeat in the window: effects held " + design.effects().size() + " of " + expected.size());
            System.out.println("P0-RESULT e draft-2 rule, offer inside the append: effects held " + draft2Atomic.effects().size() + " of " + expected.size());
            assertThat(expected).as("precondition: the real cluster committed three batches").hasSize(3);
            assertThat(draft2.effects()).as("(e) positive control: the draft-2 rule lost the effects when a heartbeat fell between bump and offer").isEmpty();
            assertThat(design.effects()).as("(e) the design rule (only entries advance applied) lost none").containsExactlyInAnyOrderElementsOf(expected);
            assertThat(draft2Atomic.effects()).as("(e) with the offer inside the append there is no window, so even the draft-2 rule lost none").containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            cluster.close();
        }
    }
}
