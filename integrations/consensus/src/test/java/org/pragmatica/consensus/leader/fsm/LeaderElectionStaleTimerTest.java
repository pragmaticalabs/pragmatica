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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the L1 fix: `Electing` and `ReElecting` are now data-carrying records that hold the
/// `ScheduledFuture` for the pending election tick. On state exit OR on a CAS-loss, that future
/// is cancelled — so a stale tick fired against a prior tenure cannot dispatch.
class LeaderElectionStaleTimerTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long delays so the tick never fires during the test — we only assert cancellation.
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    /// Long KvSync grace delay so the timer never fires during the test — these tests assert
    /// behaviour AFTER the FSM reaches Electing/ReElecting, which requires manually dispatching
    /// `KvSyncGraceTimeout` to drive the AwaitingKvSync → Electing/ReElecting transition.
    private static final TimeSpan KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness() {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "leader-election-stale-timer-test",
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        SELF,
                                                        Option.<LeaderProposalHandler>none(),
                                                        List.of(SELF, PEER_A, PEER_B),
                                                        MessageRouter.mutable(),
                                                        PROPOSAL_RETRY_DELAY,
                                                        BASE_ELECTION_DELAY,
                                                        PER_RANK_DELAY,
                                                        PROPOSAL_TIMEOUT,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        LeaderElectionContext.DEFAULT_CONSENSUS_READY_SUPPLIER,
                                                        LeaderElectionContext.DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER,
                                                        KV_SYNC_GRACE_DELAY);
                    return ctx.dormant();
                });
    }

    @Test
    void transitionOutOfElecting_cancelsStaleTickFuture() {
        var h = buildHarness();
        // Drive the FSM into Electing via Dormant → QuorumWaiting → Electing.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        // Drive the AwaitingKvSync → Electing fall-through manually (long grace timer suppressed).
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);
        var electing = (LeaderElectionState.Electing) h.state();
        var pendingTick = electing.tickFuture().get();
        assertThat((Object) pendingTick).as("tick future scheduled eagerly in Electing.fresh").isNotNull();
        assertThat(pendingTick.isCancelled()).isFalse();

        // Transition out: QuorumDisappeared → QuorumLost.
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        assertThat(pendingTick.isCancelled())
                .as("Electing.onExit must cancel the pending tick future")
                .isTrue();
    }

    @Test
    void shutdownFromElecting_cancelsStaleTickFuture() {
        var h = buildHarness();
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        var electing = (LeaderElectionState.Electing) h.state();
        var pendingTick = electing.tickFuture().get();

        h.dispatch(new ClusterFsmEvent.Shutdown());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Stopped.class);
        assertThat(pendingTick.isCancelled())
                .as("Electing.onExit must cancel pending tick on Shutdown path")
                .isTrue();
    }

    @Test
    void transitionOutOfReElecting_cancelsStaleTickFuture() {
        var h = buildHarness();
        // Reach ReElecting: hasEverHadLeader must be true. Drive
        // Dormant → QuorumWaiting → Electing → Led → ReElecting. We populate `currentTopology`
        // via NodeAdded in QuorumWaiting so that `adoptLeaderIfInTopology` accepts the
        // synthesized `LeaderCommitted` event.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        // LeaderCommitted handled directly by AwaitingKvSync.handle — no need for the grace
        // timeout. This mirrors the cold-boot fix path: AwaitingKvSync absorbs the leader-commit
        // notification and transitions straight to Led without ever entering Electing.
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted(SELF));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        h.dispatch(new ClusterFsmEvent.NodeGone(SELF, List.of(PEER_A, PEER_B)));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.ReElecting.class);
        var reElecting = (LeaderElectionState.ReElecting) h.state();
        var pendingTick = reElecting.tickFuture().get();
        assertThat((Object) pendingTick).isNotNull();
        assertThat(pendingTick.isCancelled()).isFalse();

        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        assertThat(pendingTick.isCancelled())
                .as("ReElecting.onExit must cancel the pending tick future")
                .isTrue();
    }

    @Test
    void casLostOnElecting_cancelsTickFuture() {
        // Build an FSM with a recording observer so we can observe CAS losses, and manually
        // exercise the CAS-loss path by trying to advance to a fresh Electing while the FSM is
        // already in a different state. This isolates the `onCasLost` cleanup contract.
        var h = buildHarness();
        // Drive into QuorumLost (a non-Electing state).
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        var fsm = h.fsm();

        // Construct a fresh Electing — this eagerly schedules a tick. We then pretend a CAS
        // attempt against an `expected` that is no longer current: tryAdvance returns false and
        // invokes target.onCasLost(), which must cancel the eagerly-scheduled tick.
        var ctx = ((LeaderElectionState.QuorumLost) h.state()).ctx();
        var freshElecting = LeaderElectionState.Electing.fresh(ctx);
        var pendingTick = freshElecting.tickFuture().get();
        assertThat((Object) pendingTick).isNotNull();
        assertThat(pendingTick.isCancelled()).isFalse();

        // Trigger CAS-loss via the public dispatch path: reuse a stale `expected` reference.
        // Easiest reliable path: call onCasLost directly. The contract under test is that this
        // hook cancels the eagerly-scheduled future.
        freshElecting.onCasLost();

        assertThat(pendingTick.isCancelled())
                .as("Electing.onCasLost must cancel the eagerly-scheduled tick future")
                .isTrue();
        // tickFuture holder is also drained (set to null) so a second cancel is a no-op.
        assertThat((Object) freshElecting.tickFuture().get()).isNull();
        // Suppress unused-variable warning on `fsm`.
        assertThat(fsm).isNotNull();
    }

    @Test
    void casLostOnReElecting_cancelsTickFuture() {
        var h = buildHarness();
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);

        var ctx = ((LeaderElectionState.QuorumLost) h.state()).ctx();
        var freshReElecting = LeaderElectionState.ReElecting.fresh(ctx);
        var pendingTick = freshReElecting.tickFuture().get();
        assertThat((Object) pendingTick).isNotNull();
        assertThat(pendingTick.isCancelled()).isFalse();

        freshReElecting.onCasLost();

        assertThat(pendingTick.isCancelled())
                .as("ReElecting.onCasLost must cancel the eagerly-scheduled tick future")
                .isTrue();
        assertThat((Object) freshReElecting.tickFuture().get()).isNull();
    }

    /// Sanity: holder type matches the expected `AtomicReference<ScheduledFuture<?>>` shape.
    @Test
    void electingHolderIsAtomicReference() {
        var h = buildHarness();
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        var electing = (LeaderElectionState.Electing) h.state();
        AtomicReference<ScheduledFuture<?>> holder = electing.tickFuture();
        assertThat(holder).isNotNull();
        AtomicReference<ScheduledFuture<?>> proposalHolder = electing.proposalTimeoutFuture();
        assertThat(proposalHolder).isNotNull();
        // Cleanup.
        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Suppress unused warning if the framework needs the Fsm reference type-locked.
    @SuppressWarnings("unused")
    private static Fsm<LeaderElectionState, ClusterFsmEvent> typeLock(Fsm<LeaderElectionState, ClusterFsmEvent> f) {
        return f;
    }
}
