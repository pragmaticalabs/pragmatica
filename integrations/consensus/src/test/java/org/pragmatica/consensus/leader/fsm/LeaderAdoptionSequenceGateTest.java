/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Leader-adoption gating (FIX 1 + FIX-1 refinement). Adoption of a COMMITTED `LeaderKey` is:
///   - **sequence-gated WITHIN an election** (`Electing` / `ReElecting`): a node that decided to
///     elect adopts ONLY a commit whose `viewSequence` POSTDATES the decision
///     (`committedViewSequence() > entryBaseline`). The killed leader's stale commit (≤ baseline)
///     is fenced out so re-election cannot wedge by re-adopting the corpse.
///   - **unconditional ELSEWHERE** (`QuorumWaiting`, `AwaitingKvSync`, `Led` leader-swap): those
///     states have NOT decided to elect; a committed leader is plain truth and is adopted as-is —
///     this keeps the stuck-joiner fix (the joiner adopts in `AwaitingKvSync` before ever electing).
class LeaderAdoptionSequenceGateTest {
    private static final NodeId SELF = new NodeId("node-2");
    private static final NodeId LEADER = new NodeId("node-1");
    private static final NodeId SUCCESSOR = new NodeId("node-3");

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(AtomicReference<Option<NodeId>> kvLeader) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "leader-adoption-sequence-gate-test",
                fsm -> {
                    LeaderProposalHandler neverSettling = (candidate, viewSeq) -> Promise.promise();
                    var ctx = new LeaderElectionContext(fsm,
                                                        SELF,
                                                        Option.some(neverSettling),
                                                        List.of(SELF, LEADER, SUCCESSOR),
                                                        MessageRouter.mutable(),
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        LONG,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        kvLeader::get,
                                                        LONG,
                                                        LONG);
                    return ctx.dormant();
                });
    }

    private LeaderElectionContext ctxOf(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        return ((LeaderElectionState) h.state()).ctx();
    }

    private void driveToElecting(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h, List<NodeId> topology) {
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, topology));
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state())
                .as("FSM in Electing after the AwaitingKvSync grace fall-through")
                .isInstanceOf(LeaderElectionState.Electing.class);
    }

    /// Scenario 1 — dead-leader re-election. Electing snapshots baseline N; the stale pre-death
    /// commit (still seq N) is IGNORED; the fresh successor commit (N+1) is adopted.
    @Test
    void electing_staleCommitAtBaseline_ignored_freshCommitAboveBaseline_adopted() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = harness(kvLeader);

        // The dead leader was committed at viewSequence N=5 before this node decided to elect.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, SUCCESSOR)));
        ctxOf(h).observeViewSequence(5L);
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);
        assertThat(((LeaderElectionState.Electing) h.state()).entryBaseline()).isEqualTo(5L);

        // Stale pull/push: the corpse (LEADER) is still LeaderKey at seq 5 (== baseline). IGNORED.
        h.dispatch(new LeaderCommitted(LEADER));
        assertThat(h.state())
                .as("stale pre-death commit (seq == baseline) must NOT be adopted")
                .isInstanceOf(LeaderElectionState.Electing.class);

        // A fresh successor commits at seq 6 (> baseline) — observed, then the LeaderCommitted lands.
        ctxOf(h).observeViewSequence(6L);
        h.dispatch(new LeaderCommitted(SUCCESSOR));
        assertThat(h.state())
                .as("a post-election commit (seq > baseline) is adopted by the elector")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(SUCCESSOR);
    }

    /// Scenario 2 — fresh joiner with a healthy leader adopts via the UNCONDITIONAL AwaitingKvSync
    /// path (never enters Electing). The original FIX-1 stuck-joiner scenario stays fixed.
    @Test
    void awaitingKvSync_committedLeader_adoptedUnconditionally_neverEntersElecting() {
        var kvLeader = new AtomicReference<>(Option.some(LEADER));
        var h = harness(kvLeader);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        // Topology deliberately excludes the healthy LEADER — the transport-absent FIX-1 condition.
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF)));
        h.dispatch(new ConsensusReady());

        assertThat(h.state())
                .as("joiner adopts the healthy committed leader in AwaitingKvSync, before electing")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(LEADER);
    }

    /// Scenario 3 — two simultaneous electors: the first commit N+1 wins the fence; the loser
    /// adopts it because N+1 > its own baseline N. No ping-pong.
    @Test
    void electing_loserAdoptsWinnerCommit_aboveBaseline_noPingPong() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = harness(kvLeader);

        // Both electors decided to elect off baseline N=2.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, SUCCESSOR)));
        ctxOf(h).observeViewSequence(2L);
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(((LeaderElectionState.Electing) h.state()).entryBaseline()).isEqualTo(2L);

        // The other elector's proposal commits first at seq 3. This (losing) node observes it and
        // adopts — it does NOT re-propose, because 3 > its baseline 2.
        ctxOf(h).observeViewSequence(3L);
        h.dispatch(new LeaderCommitted(SUCCESSOR));
        assertThat(h.state())
                .as("the losing elector adopts the winner's commit (seq > baseline) — no ping-pong")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(SUCCESSOR);
    }

    /// Scenario 4 — an Electing node receives a PUSH commit N+1 from a peer mid-election: adopts
    /// (the in-flight self-proposal is abandoned cleanly via Electing.onExit cancelling futures).
    @Test
    void electing_peerPushCommitAboveBaseline_adoptedMidElection() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = harness(kvLeader);

        driveToElecting(h, List.of(SELF, SUCCESSOR));
        ctxOf(h).observeViewSequence(0L); // baseline stays 0 (cold election)
        assertThat(((LeaderElectionState.Electing) h.state()).entryBaseline()).isEqualTo(0L);

        // A peer's proposal commits at seq 1 (> baseline 0) and arrives via the push path.
        ctxOf(h).observeViewSequence(1L);
        h.dispatch(new LeaderCommitted(SUCCESSOR));

        assertThat(h.state())
                .as("a peer push commit above baseline is adopted mid-election")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(SUCCESSOR);
    }
}
