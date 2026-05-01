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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies Fix A: `Electing` and `ReElecting` schedule an independent peer-observation
/// `ScheduledFuture` that calls `adoptLeaderFromKvIfPresent` at
/// [`LeaderElectionContext#peerObservationInterval`] cadence — regardless of whether a
/// proposal is in flight. Names a real architectural smell: the prior `Electing.handle`
/// arm conflated "drive my own proposal" with "observe peers". Once `tryStartProposal`
/// succeeded and `proposalInFlight=true`, neither `ElectionTick` nor any other pull-side
/// check fired until the in-flight `proposalTimeout` (10s default) elapsed and dispatched
/// `ProposalSettled.failure`. During that window the FSM was blind to peer-committed
/// leaders if the push notification was buffered (Rabia decisions held during Syncing) or
/// the listener was wired late. Concrete production stall observed on cold-boot of
/// cluster-B node-1 — the FSM sat in `Electing` for ~3 minutes (entire `proposalTimeout`
/// window, repeated retries) before observing a peer-committed leader.
///
/// Fix A is purely additive: a parallel observation channel that does NOT touch the
/// `proposalInFlight` machinery. The existing `LeaderCommitted` handler arm transitions
/// to `Led(thatLeader)`, preempting the in-flight proposal — its timeout is cancelled in
/// `Electing.onExit`.
class ElectingPeerObservationTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long delays so the Electing tick / proposal timeout never fire during the test —
    /// only the observation timer should drive the leader-adoption path under test.
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(60).seconds();
    /// Short observation interval so the test exercises the periodic path quickly. 50ms is
    /// enough to demonstrate the false→true edge while keeping the test fast.
    private static final TimeSpan SHORT_OBSERVATION_INTERVAL = TimeSpan.timeSpan(50).millis();
    /// Long observation interval used by tests that DO NOT want the periodic timer to fire.
    private static final TimeSpan LONG_OBSERVATION_INTERVAL = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            Option<LeaderProposalHandler> proposalHandler,
            AtomicReference<Option<NodeId>> kvLeader,
            TimeSpan observationInterval) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "electing-peer-observation-test",
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
                                                        kvLeader::get,
                                                        KV_SYNC_GRACE_DELAY,
                                                        observationInterval);
                    return ctx.dormant();
                });
    }

    /// Core regression test: enter `Electing`, drive `proposalInFlight=true` via an
    /// `ElectionTick` that successfully reserves the proposal slot, then inject a
    /// peer-committed leader into the KV supplier mid-flight. The independent observation
    /// timer fires `adoptLeaderFromKvIfPresent` at 50ms cadence, dispatches a synthetic
    /// `LeaderCommitted`, and the FSM transitions to `Led(thatLeader)` — preempting the
    /// in-flight self-proposal WITHOUT waiting for the 60s `proposalTimeout` to fire.
    @Test
    void electing_peerLeaderCommittedDuringInFlightProposal_transitionsToLedBeforeProposalTimeout()
            throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            // Return a never-completing Promise so the proposal stays in flight until the
            // observation timer drives the FSM out of Electing. (Promise.success(unit())
            // would dispatch ProposalSettled.success, which clears proposalInFlight — fine
            // for the FSM but it lets the test miss the in-flight window we want to cover.)
            return Promise.promise();
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(Option.some(trackingHandler), kvLeader, SHORT_OBSERVATION_INTERVAL);

        // Drive Dormant → QuorumWaiting → AwaitingKvSync → (via grace timeout) → Electing.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        // Manually drive the AwaitingKvSync → Electing fall-through (long grace timer
        // suppressed). With hasEverHadLeader=false we land in Electing, not ReElecting.
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state())
                .as("FSM in Electing after AwaitingKvSync grace fall-through")
                .isInstanceOf(LeaderElectionState.Electing.class);

        // Drive the ElectionTick path so trySubmitProposal acquires proposalInFlight=true.
        // The handler returns a never-completing Promise → proposalInFlight stays true.
        h.dispatch(new LeaderElectionEvents.ElectionTick());
        var ctx = ((LeaderElectionState.Electing) h.state()).ctx();
        assertThat(ctx.proposalInFlight())
                .as("proposal acquired the in-flight slot — observation timer is the ONLY "
                    + "path that can pull a peer-committed leader during this window")
                .isTrue();
        assertThat(proposalCount.get())
                .as("proposal handler invoked exactly once on the tick")
                .isEqualTo(1);

        // Inject a peer-committed leader mid-flight. KV supplier now returns Some(PEER_A).
        // The independent observation timer (50ms cadence) must observe this within a few
        // ticks and dispatch a synthetic LeaderCommitted, transitioning the FSM to Led.
        kvLeader.set(Option.some(PEER_A));

        // Poll for the transition. Allow up to 5s slack for CI scheduler jitter — we expect
        // the transition within a few observation ticks (well under 1s in practice). If the
        // observation timer is wired correctly, this will fire LONG before the 60s
        // proposalTimeout that would otherwise be the only path out of Electing.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
               && !(h.state() instanceof LeaderElectionState.Led)) {
            Thread.sleep(20);
        }

        assertThat(h.state())
                .as("Independent peer-observation timer dispatched LeaderCommitted while "
                    + "proposal was in flight — FSM transitioned to Led(PEER_A) WITHOUT "
                    + "waiting for the 60s proposalTimeout. This is the architectural fix.")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);
        assertThat(proposalCount.get())
                .as("no additional proposals submitted — preemption fired during the original window")
                .isEqualTo(1);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Lifecycle: `Electing.onExit` cancels the observation future and drains the holder.
    /// Mirror of the existing tickFuture / proposalTimeoutFuture cleanup contract.
    @Test
    void electing_onExit_cancelsObservationFuture() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(Option.<LeaderProposalHandler>none(), kvLeader, LONG_OBSERVATION_INTERVAL);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        var electing = (LeaderElectionState.Electing) h.state();
        AtomicReference<ScheduledFuture<?>> holder = electing.observationFuture();
        var observationFuture = holder.get();
        assertThat((Object) observationFuture)
                .as("observation future scheduled eagerly in Electing.fresh")
                .isNotNull();
        assertThat(observationFuture.isCancelled()).isFalse();

        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());

        assertThat(h.state()).isInstanceOf(LeaderElectionState.QuorumLost.class);
        assertThat(observationFuture.isCancelled())
                .as("Electing.onExit must cancel the observation future")
                .isTrue();
        assertThat((Object) holder.get())
                .as("Electing.onExit drains the observation-future holder")
                .isNull();
    }

    /// Lifecycle: `Electing.onCasLost` cancels the observation future. Same contract as
    /// the existing tickFuture cleanup — exercised by constructing a fresh `Electing`
    /// outside the FSM and invoking `onCasLost` directly.
    @Test
    void electing_onCasLost_cancelsObservationFuture() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(Option.<LeaderProposalHandler>none(), kvLeader, LONG_OBSERVATION_INTERVAL);

        // Drive into QuorumLost so we have a stable ctx to construct a fresh Electing on.
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        var ctx = ((LeaderElectionState.QuorumLost) h.state()).ctx();

        var freshElecting = LeaderElectionState.Electing.fresh(ctx);
        var observationFuture = freshElecting.observationFuture().get();
        assertThat((Object) observationFuture).isNotNull();
        assertThat(observationFuture.isCancelled()).isFalse();

        freshElecting.onCasLost();

        assertThat(observationFuture.isCancelled())
                .as("Electing.onCasLost must cancel the observation future")
                .isTrue();
        assertThat((Object) freshElecting.observationFuture().get())
                .as("Electing.onCasLost drains the observation-future holder")
                .isNull();
    }

    /// Symmetric coverage: `ReElecting` ALSO schedules an observation future. Construct a
    /// fresh `ReElecting` and exercise its `onCasLost` directly, mirroring
    /// `LeaderElectionStaleTimerTest#casLostOnReElecting_cancelsTickFuture`.
    @Test
    void reElecting_onCasLost_cancelsObservationFuture() {
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(Option.<LeaderProposalHandler>none(), kvLeader, LONG_OBSERVATION_INTERVAL);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        h.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        var ctx = ((LeaderElectionState.QuorumLost) h.state()).ctx();

        var freshReElecting = LeaderElectionState.ReElecting.fresh(ctx);
        var observationFuture = freshReElecting.observationFuture().get();
        assertThat((Object) observationFuture)
                .as("ReElecting.fresh schedules an observation future symmetrically with Electing")
                .isNotNull();
        assertThat(observationFuture.isCancelled()).isFalse();

        freshReElecting.onCasLost();

        assertThat(observationFuture.isCancelled())
                .as("ReElecting.onCasLost must cancel the observation future")
                .isTrue();
        assertThat((Object) freshReElecting.observationFuture().get())
                .as("ReElecting.onCasLost drains the observation-future holder")
                .isNull();
    }

    /// Sanity: when no peer leader is committed, the observation timer fires repeatedly
    /// but the FSM stays in Electing. Confirms the timer is a pull-only observer — it
    /// does NOT spuriously transition the FSM when KV is empty.
    @Test
    void electing_observationTimerWithEmptyKv_staysInElecting() throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            return Promise.success(Unit.unit());
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(Option.some(trackingHandler), kvLeader, SHORT_OBSERVATION_INTERVAL);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);

        // Let the observation timer fire ~5 times with an empty KV.
        Thread.sleep(300);

        assertThat(h.state())
                .as("observation timer with empty KV does not transition the FSM out of Electing")
                .isInstanceOf(LeaderElectionState.Electing.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}
