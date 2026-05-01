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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the cold-boot self-proposal race fix: when a fresh node boots into a cluster of
/// peers booting simultaneously, the new `AwaitingKvSync` gate defers leader-proposal submission
/// until either (a) a peer-committed leader arrives via KV-sync OR (b) a configurable grace
/// window elapses (genuinely fresh cluster — proceed to election normally).
class AwaitingKvSyncTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long delays so timers never fire during the synchronous-path tests.
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    /// Long enough that no test that does not explicitly want the timeout fall-through observes it.
    private static final TimeSpan LONG_KV_SYNC_GRACE = TimeSpan.timeSpan(60).seconds();
    /// Short grace for the timeout-fall-through test — 100ms is enough to demonstrate behaviour
    /// while keeping the test fast.
    private static final TimeSpan SHORT_KV_SYNC_GRACE = TimeSpan.timeSpan(100).millis();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            Option<LeaderProposalHandler> proposalHandler,
            Option<NodeId> kvLeader,
            TimeSpan kvSyncGraceDelay) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "awaiting-kv-sync-test",
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        SELF,
                                                        proposalHandler,
                                                        List.of(SELF, PEER_A, PEER_B),
                                                        MessageRouter.mutable(),
                                                        PROPOSAL_RETRY_DELAY,
                                                        BASE_ELECTION_DELAY,
                                                        PER_RANK_DELAY,
                                                        PROPOSAL_TIMEOUT,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        () -> kvLeader,
                                                        kvSyncGraceDelay);
                    return ctx.dormant();
                });
    }

    /// Cold-boot race: fresh node enters AwaitingKvSync with empty local KV. A peer's KVSync
    /// arrives, populates LeaderKey locally, AetherNode synthesizes a `LeaderCommitted` event.
    /// The FSM must transition to `Led(thatLeader)` WITHOUT ever submitting a proposal.
    @Test
    void awaitingKvSync_leaderCommittedDuringGrace_transitionsToLedWithoutProposing() {
        var proposalCount = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            return Promise.success(Unit.unit());
        };
        var h = buildHarness(Option.some(trackingHandler), Option.none(), LONG_KV_SYNC_GRACE);

        // Drive Dormant → QuorumWaiting → AwaitingKvSync.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        // Populate topology so adoptLeaderIfInTopology accepts the leader.
        h.dispatch(new ClusterFsmEvent.NodeAdded(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        assertThat(h.state())
                .as("FSM advances to AwaitingKvSync, NOT directly to Electing")
                .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        // Peer KV-sync delivers LeaderKey → AetherNode dispatches LeaderCommitted.
        h.dispatch(new LeaderCommitted(PEER_A));

        // FSM must transition to Led(PEER_A) without ever proposing.
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);
        assertThat(proposalCount.get())
                .as("Cold-boot race fix: no proposal submitted when peer-committed leader observed during grace")
                .isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Auto-heal path: replacement node joins a live cluster. KV-sync from the live leader
    /// populates LeaderKey BEFORE the FSM enters AwaitingKvSync. The pull-side
    /// `currentLeaderFromKvSupplier` synthesizes LeaderCommitted on entry and the FSM passes
    /// through AwaitingKvSync in a single dispatch tick — no grace timer scheduled.
    @Test
    void awaitingKvSync_kvSupplierHasLeaderOnEntry_synchronousTransitionToLed() {
        var h = buildHarness(Option.none(), Option.some(PEER_A), LONG_KV_SYNC_GRACE);

        // Populate topology BEFORE entering AwaitingKvSync so adoptLeaderIfInTopology accepts
        // the synthesized LeaderCommitted event. (The harness's `consensusReadySupplier`
        // returns true → entering QuorumWaiting auto-dispatches ConsensusReady → enters
        // AwaitingKvSync — so we must seed topology in Dormant first.)
        h.dispatch(new ClusterFsmEvent.NodeAdded(PEER_A, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());

        // Synchronous: AwaitingKvSync.onEntry → adoptLeaderFromKvIfPresent → LeaderCommitted →
        // Led(PEER_A) all within a single dispatch tick.
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Genuinely fresh cluster: no peer commits a leader during the grace window. The FSM
    /// falls through to Electing via a synthesized `KvSyncGraceTimeout`, then proceeds with
    /// the normal proposal path.
    @Test
    void awaitingKvSync_graceTimeoutWithNoLeader_transitionsToElecting() throws InterruptedException {
        var h = buildHarness(Option.none(), Option.none(), SHORT_KV_SYNC_GRACE);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());

        assertThat(h.state())
                .as("Initially in AwaitingKvSync, waiting for the grace timer to fire")
                .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        // Wait for the grace timer (100ms) to fire — give it 5s of slack for CI scheduler jitter.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
               && h.state() instanceof LeaderElectionState.AwaitingKvSync) {
            Thread.sleep(20);
        }

        assertThat(h.state())
                .as("AwaitingKvSync grace timer fires KvSyncGraceTimeout → fall through to Electing")
                .isInstanceOf(LeaderElectionState.Electing.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Fall-through after grace timeout uses ReElecting if hasEverHadLeader (election re-run
    /// scenario, e.g. quorum dropped and recovered).
    @Test
    void awaitingKvSync_graceTimeoutAfterPriorLeader_transitionsToReElecting() {
        var h = buildHarness(Option.none(), Option.none(), LONG_KV_SYNC_GRACE);

        // First, drive a full cycle to set hasEverHadLeader.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        h.dispatch(new LeaderCommitted(SELF));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);

        // Drop quorum and recover — re-enters AwaitingKvSync from QuorumLost.
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(h.state())
                .as("QuorumLost → QuorumEstablished re-enters via AwaitingKvSync")
                .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        // Manually drive the timeout fall-through.
        h.dispatch(new KvSyncGraceTimeout());

        assertThat(h.state())
                .as("With hasEverHadLeader=true, fall-through goes to ReElecting (not Electing)")
                .isInstanceOf(LeaderElectionState.ReElecting.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// QuorumDisappeared during AwaitingKvSync transitions to QuorumLost and cancels the
    /// grace timer.
    @Test
    void awaitingKvSync_quorumDisappeared_transitionsToQuorumLostAndCancelsTimer() {
        var h = buildHarness(Option.none(), Option.none(), LONG_KV_SYNC_GRACE);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        var awaiting = (LeaderElectionState.AwaitingKvSync) h.state();
        AtomicReference<java.util.concurrent.ScheduledFuture<?>> holder = awaiting.graceTimeoutFuture();
        var graceFuture = holder.get();
        assertThat((Object) graceFuture).as("grace timer scheduled on entry").isNotNull();
        assertThat(graceFuture.isCancelled()).isFalse();

        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());

        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        assertThat(graceFuture.isCancelled())
                .as("AwaitingKvSync.onExit cancels the grace timer")
                .isTrue();
        assertThat((Object) holder.get())
                .as("AwaitingKvSync.onExit drains the holder")
                .isNull();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Shutdown during AwaitingKvSync transitions to Stopped and cancels the grace timer.
    @Test
    void awaitingKvSync_shutdown_transitionsToStoppedAndCancelsTimer() {
        var h = buildHarness(Option.none(), Option.none(), LONG_KV_SYNC_GRACE);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        var awaiting = (LeaderElectionState.AwaitingKvSync) h.state();
        var graceFuture = awaiting.graceTimeoutFuture().get();
        assertThat((Object) graceFuture).isNotNull();

        h.dispatch(new ClusterFsmEvent.Shutdown());

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Stopped.class);
        assertThat(graceFuture.isCancelled())
                .as("AwaitingKvSync.onExit cancels the grace timer on shutdown path")
                .isTrue();
    }

    /// Sanity: no `ElectionTick` is observed while the FSM sits in AwaitingKvSync — the eager
    /// tick is a property of `Electing.fresh`, not `AwaitingKvSync`.
    @Test
    void awaitingKvSync_noElectionTickScheduled() throws InterruptedException {
        var tickObserved = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            tickObserved.incrementAndGet();
            return Promise.success(Unit.unit());
        };
        var h = buildHarness(Option.some(trackingHandler), Option.none(), LONG_KV_SYNC_GRACE);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());

        // Wait briefly. If AwaitingKvSync had eagerly scheduled an ElectionTick, the tracking
        // handler would observe a proposal. (It won't because AwaitingKvSync doesn't handle
        // ElectionTick — even if one slipped through, there's no scheduler producing one.)
        Thread.sleep(200);

        assertThat(h.state()).isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        assertThat(tickObserved.get())
                .as("AwaitingKvSync does not schedule election ticks")
                .isZero();

        // Verify ElectionTick is ignored (not handled) while in AwaitingKvSync.
        h.dispatch(new ElectionTick());
        assertThat(h.state())
                .as("ElectionTick in AwaitingKvSync is ignored, no transition")
                .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}
