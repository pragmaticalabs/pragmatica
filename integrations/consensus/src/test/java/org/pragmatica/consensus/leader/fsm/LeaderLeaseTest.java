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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the leader-acting lease in [`LeaderElectionState.Led`] (Option A: a node in `Led(X)`
/// where `X != self` re-elects when the believed leader stops stamping leader pings) plus the
/// crossed-pointer viewSequence fence on [`LeaderElectionState#handleLeaderCommittedInLed`].
///
/// The lease is the recovery edge for the tonight's zero-leader wedge: competing re-elections
/// committed seq2(node-1) then seq3(node-2); node-2 never received the seq3 decision, so the
/// cluster crossed pointers (1/4/5 believe node-2, node-2 believes node-1) with NOBODY believing
/// self. No `NodeGone` fired (both believed leaders stayed SWIM-alive), and `Led` had no timer —
/// so re-election never fired. The lease arms a per-interval freshness check; after
/// [`LeaderElectionContext#LEADER_SILENCE_THRESHOLD_INTERVALS`] silent checks the believed leader
/// is declared silent and the node re-elects. Every node's believed leader is silent in the wedge,
/// so all nodes re-elect and a fresh commit converges them.
class LeaderLeaseTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    /// Long election/proposal/grace delays so only the lease timer drives behaviour under test.
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan LONG_OBSERVATION_INTERVAL = TimeSpan.timeSpan(60).seconds();

    /// Short lease-check cadence so the test exercises the silence threshold quickly. 30ms x 4
    /// silent checks ≈ 120ms to trip the lease, comfortably fast while still real-timer-driven.
    private static final TimeSpan SHORT_LEASE_INTERVAL = TimeSpan.timeSpan(30).millis();
    /// Long lease-check cadence used by the Led(self) exemption test — the lease must not even
    /// schedule a timer there, so this value should never matter.
    private static final TimeSpan LONG_LEASE_INTERVAL = TimeSpan.timeSpan(60).seconds();
    /// Short tenure warm-up so the first lease check fires quickly in trip tests (production is
    /// 15s). 30ms — long enough to assert "no trip during warm-up", short enough to keep tests fast.
    private static final TimeSpan SHORT_LEASE_WARMUP = TimeSpan.timeSpan(30).millis();
    /// Long warm-up used to prove the lease does NOT trip during the warm-up window even with a
    /// permanently-silent leader ping.
    private static final TimeSpan LONG_LEASE_WARMUP = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            Predicate<NodeId> leaderPingFreshFn,
            TimeSpan leaseInterval) {
        return buildHarness(leaderPingFreshFn, leaseInterval, SHORT_LEASE_WARMUP);
    }

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            Predicate<NodeId> leaderPingFreshFn,
            TimeSpan leaseInterval,
            TimeSpan leaseWarmup) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "leader-lease-test",
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
                                                        () -> true,
                                                        Option::none,
                                                        KV_SYNC_GRACE_DELAY,
                                                        LONG_OBSERVATION_INTERVAL,
                                                        leaseInterval,
                                                        leaderPingFreshFn,
                                                        leaseWarmup);
                    return ctx.dormant();
                });
    }

    /// Drives the FSM into `Led(leader)` via Dormant → QuorumWaiting → AwaitingKvSync →
    /// (LeaderCommitted) → Led. The `LeaderCommitted` is absorbed directly by AwaitingKvSync's
    /// `adoptLeaderUnconditionally` (no grace timeout needed, mirroring the cold-boot fix path).
    private void driveToLed(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h, LeaderCommitted commit) {
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, List.of(SELF, PEER_A, PEER_B)));
        h.dispatch(new ConsensusReady());
        h.dispatch(commit);
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
    }

    private static boolean awaitState(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h,
                                      Class<? extends LeaderElectionState> target,
                                      long timeoutMillis) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (target.isInstance(h.state())) {
                return true;
            }
            Thread.sleep(10);
        }
        return target.isInstance(h.state());
    }

    /// (a) `Led(X != self)` with a believed leader whose ping is reported SILENT trips the lease
    /// after the silence threshold and transitions to `ReElecting`.
    @Test
    void led_followerTenureWithSilentLeaderPing_tripsLeaseAndReElects() throws InterruptedException {
        // Predicate reports the believed leader (PEER_A) as NEVER fresh → lease accumulates silence.
        Predicate<NodeId> alwaysSilent = _ -> false;
        var h = buildHarness(alwaysSilent, SHORT_LEASE_INTERVAL);

        driveToLed(h, new LeaderCommitted(PEER_A));
        assertThat(((LeaderElectionState.Led) h.state()).leader())
                .as("follower tenure for PEER_A")
                .isEqualTo(PEER_A);

        // After the warm-up (30ms) plus LEADER_SILENCE_THRESHOLD_INTERVALS silent checks
        // (4 x 30ms ≈ 120ms) the lease trips LeaderSilent → ReElecting. Allow generous slack for
        // CI scheduler jitter.
        assertThat(awaitState(h, LeaderElectionState.ReElecting.class, 5000))
                .as("silent leader ping for the silence threshold re-elects — the recovery edge "
                    + "for the SWIM-alive but non-committing leader wedge")
                .isTrue();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (a-warmup) The lease does NOT trip during the tenure warm-up window even when the believed
    /// leader's ping is PERMANENTLY silent — a freshly-elected leader must be given time to
    /// activate and broadcast its first readiness view before the lease can declare it silent.
    /// This is the fix for the perpetual re-election churn: a long warm-up suppresses the first
    /// lease check, so a node that stays in Led for the whole window (despite stale pings) is NOT
    /// re-elected.
    @Test
    void led_silentLeaderDuringWarmup_doesNotTrip() throws InterruptedException {
        // Permanently-silent predicate, SHORT check interval, but a LONG (60s) warm-up so the
        // first check cannot fire during the test window.
        Predicate<NodeId> alwaysSilent = _ -> false;
        var h = buildHarness(alwaysSilent, SHORT_LEASE_INTERVAL, LONG_LEASE_WARMUP);

        driveToLed(h, new LeaderCommitted(PEER_A));
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        // Let well over LEADER_SILENCE_THRESHOLD_INTERVALS × interval (4 × 30ms = 120ms) elapse.
        // Because the warm-up (60s) gates the FIRST check, no check fires → no trip.
        Thread.sleep(400);

        assertThat(h.state())
                .as("lease does not trip during warm-up even with a permanently-silent leader ping")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (b) `Led(self)` is exempt — a node leading itself stamps the pings the lease tracks, so it
    /// schedules NO lease timer and never fires `LeaderSilent`, even with an always-silent
    /// predicate. The FSM stays in `Led(self)`.
    @Test
    void led_selfTenure_neverTripsLease() throws InterruptedException {
        // Even an always-silent predicate must not re-elect a self-led node (the timer is never
        // scheduled). Use a SHORT interval so that, IF a timer were (wrongly) scheduled, it would
        // fire many times during the sleep below and surface the bug.
        Predicate<NodeId> alwaysSilent = _ -> false;
        var h = buildHarness(alwaysSilent, SHORT_LEASE_INTERVAL);

        driveToLed(h, new LeaderCommitted(SELF));
        var led = (LeaderElectionState.Led) h.state();
        assertThat(led.leader()).as("self tenure").isEqualTo(SELF);
        assertThat((Object) led.leaseFuture().get())
                .as("Led(self) schedules NO lease timer — the leader cannot observe itself silent")
                .isNull();

        // Let many lease intervals elapse; the FSM must remain in Led(self).
        Thread.sleep(300);

        assertThat(h.state())
                .as("Led(self) never trips the lease — stays leader")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(SELF);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (c) A FRESH ping resets the lease: while the believed leader's ping keeps being reported
    /// fresh, the consecutive-silent counter never reaches the threshold and the FSM stays in
    /// `Led(X)`. A predicate that flips silent for a couple of checks then fresh again must NOT
    /// trip the lease.
    @Test
    void led_freshPingResetsLease_staysInLed() throws InterruptedException {
        // Track how many checks reported silent. Pattern: silent for the first 2 checks (below the
        // threshold of 4), then fresh forever → the fresh check resets the counter, lease never
        // trips.
        var silentChecks = new AtomicInteger(0);
        Predicate<NodeId> flapThenFresh = _ -> silentChecks.getAndIncrement() >= 2;
        var h = buildHarness(flapThenFresh, SHORT_LEASE_INTERVAL);

        driveToLed(h, new LeaderCommitted(PEER_A));

        // Let well over LEADER_SILENCE_THRESHOLD_INTERVALS checks elapse (300ms / 30ms ≈ 10
        // checks). Because the counter resets on the first fresh check (3rd check onward), it
        // never reaches 4 consecutive silent.
        Thread.sleep(400);

        assertThat(h.state())
                .as("fresh pings reset the consecutive-silent counter — lease never trips")
                .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (d) The crossed-pointer viewSequence fence on `handleLeaderCommittedInLed`: a later-arriving
    /// LOWER-sequence `LeaderCommitted` is DISCARDED (the believed leader is unchanged), while a
    /// HIGHER-sequence one is APPLIED (leader swaps). The lease is disabled here (always-fresh) so
    /// only the fence is exercised.
    @Test
    void led_staleLowerSeqLeaderCommitted_discardedByFence_higherSeqApplies() {
        Predicate<NodeId> alwaysFresh = _ -> true;
        var h = buildHarness(alwaysFresh, LONG_LEASE_INTERVAL);

        // Adopt PEER_A at sequence 5 (production seeds observeViewSequence before dispatch; model
        // that by carrying the sequence on the event — adoptLeaderUnconditionally records it).
        driveToLed(h, new LeaderCommitted(PEER_A, 5L));
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        // A later-arriving LOWER-sequence commit (seq 3 < adopted 5) is fenced out — the straggling
        // crossed-pointer commit that would otherwise overwrite the newer leader.
        h.dispatch(new LeaderCommitted(PEER_B, 3L));
        assertThat(((LeaderElectionState.Led) h.state()).leader())
                .as("stale lower-sequence LeaderCommitted is discarded by the crossed-pointer fence")
                .isEqualTo(PEER_A);

        // A HIGHER-sequence commit (seq 7 > adopted 5) advances — the leader swaps to PEER_B.
        h.dispatch(new LeaderCommitted(PEER_B, 7L));
        assertThat(((LeaderElectionState.Led) h.state()).leader())
                .as("higher-sequence LeaderCommitted advances past the fence and swaps the leader")
                .isEqualTo(PEER_B);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (d-supplement) A sequence-less synthetic commit (`LeaderCommitted.NO_SEQUENCE`, the KV-pull
    /// / local-mode path) BYPASSES the fence and is adopted as before, so the fence cannot wedge
    /// the legacy paths that never carried a sequence.
    @Test
    void led_sequenceLessLeaderCommitted_bypassesFence() {
        Predicate<NodeId> alwaysFresh = _ -> true;
        var h = buildHarness(alwaysFresh, LONG_LEASE_INTERVAL);

        driveToLed(h, new LeaderCommitted(PEER_A, 9L));
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(PEER_A);

        // Sequence-less swap (NO_SEQUENCE) — even though 0 <= adopted 9, the sentinel bypasses the
        // fence and the swap applies (local-mode / KV-pull synthesis behaviour preserved).
        h.dispatch(new LeaderCommitted(PEER_B));
        assertThat(((LeaderElectionState.Led) h.state()).leader())
                .as("sequence-less synthetic commit bypasses the fence (legacy path preserved)")
                .isEqualTo(PEER_B);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// (e) Tonight's crossed-pointer topology, simulated per-node with one FSM harness per node.
    /// The single-node `FsmTestHarness` does not model inter-node message delivery, so we drive
    /// each node's FSM independently and assert the SELF-HEALING PROPERTY the lease guarantees:
    /// in the wedge EVERY node's believed leader is silent, so EVERY node's lease trips and EVERY
    /// node leaves `Led` for `ReElecting` within the silence threshold. (Convergence to a single
    /// fresh leader is a consensus-layer property exercised by the cluster/RabiaNode integration
    /// tests and the remote chaos suite, not reproducible in the per-node FSM harness — see the
    /// class javadoc and the report.)
    @Test
    void crossedPointerWedge_everyNodeLeaseTripsAndReElects() throws InterruptedException {
        // node-1 believes node-2 (silent), node-4 believes node-2 (silent), node-5 believes node-2
        // (silent), node-2 believes node-1 (silent). Model the two distinct believed-leader cases:
        //   - a node that believes PEER_A (node-2): SELF here stands for any of node-1/4/5.
        //   - the crossed node that believes a DIFFERENT peer.
        // Both are follower tenures with a silent believed leader, so both must trip.
        var trippedLeaders = ConcurrentHashMap.<NodeId>newKeySet();

        // Case 1: a node believing PEER_A, whose ping is silent.
        var hBelievesA = buildHarness(_ -> false, SHORT_LEASE_INTERVAL);
        driveToLed(hBelievesA, new LeaderCommitted(PEER_A));
        // Case 2: a node believing PEER_B, whose ping is silent (the crossed pointer).
        var hBelievesB = buildHarness(_ -> false, SHORT_LEASE_INTERVAL);
        driveToLed(hBelievesB, new LeaderCommitted(PEER_B));

        assertThat(awaitState(hBelievesA, LeaderElectionState.ReElecting.class, 5000))
                .as("node believing PEER_A re-elects when PEER_A's ping is silent")
                .isTrue();
        trippedLeaders.add(PEER_A);
        assertThat(awaitState(hBelievesB, LeaderElectionState.ReElecting.class, 5000))
                .as("crossed-pointer node believing PEER_B re-elects when PEER_B's ping is silent")
                .isTrue();
        trippedLeaders.add(PEER_B);

        assertThat(trippedLeaders)
                .as("BOTH distinct believed-leader cases trip the lease — in the real wedge every "
                    + "node's believed leader is silent, so the whole cluster re-elects within the "
                    + "silence threshold and a fresh consensus commit converges them")
                .containsExactlyInAnyOrderElementsOf(Set.of(PEER_A, PEER_B));

        hBelievesA.dispatch(new ClusterFsmEvent.Shutdown());
        hBelievesB.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Lifecycle: a follower `Led` tenure cancels its lease future on exit so a stale lease tick
    /// from a prior tenure cannot fire after the FSM has moved on. Mirrors the
    /// tick/proposal/observation cleanup contracts in the sibling tests.
    @Test
    void led_onExit_cancelsLeaseFuture() {
        Predicate<NodeId> alwaysFresh = _ -> true;
        var h = buildHarness(alwaysFresh, LONG_LEASE_INTERVAL);

        driveToLed(h, new LeaderCommitted(PEER_A));
        var led = (LeaderElectionState.Led) h.state();
        AtomicReference<ScheduledFuture<?>> holder = led.leaseFuture();
        var leaseFuture = holder.get();
        assertThat((Object) leaseFuture)
                .as("follower Led tenure schedules a lease future")
                .isNotNull();
        assertThat(leaseFuture.isCancelled()).isFalse();

        h.dispatch(new ClusterFsmEvent.Shutdown());

        assertThat(leaseFuture.isCancelled())
                .as("Led.onExit cancels the lease future")
                .isTrue();
    }

    /// Lifecycle: `Led.onCasLost` cancels the lease future and drains the holder. Exercised by
    /// constructing a fresh follower `Led` outside the FSM and invoking `onCasLost` directly.
    @Test
    void led_onCasLost_cancelsLeaseFuture() {
        Predicate<NodeId> alwaysFresh = _ -> true;
        var h = buildHarness(alwaysFresh, LONG_LEASE_INTERVAL);
        // Reach a stable ctx via a self tenure (no timer to leak), then build a fresh follower Led.
        driveToLed(h, new LeaderCommitted(SELF));
        var ctx = ((LeaderElectionState.Led) h.state()).ctx();

        var freshLed = LeaderElectionState.Led.fresh(ctx, PEER_A);
        freshLed.onEntry();
        var leaseFuture = freshLed.leaseFuture().get();
        assertThat((Object) leaseFuture)
                .as("fresh follower Led arms a lease future on entry")
                .isNotNull();
        assertThat(leaseFuture.isCancelled()).isFalse();

        freshLed.onCasLost();

        assertThat(leaseFuture.isCancelled())
                .as("Led.onCasLost cancels the lease future")
                .isTrue();
        assertThat((Object) freshLed.leaseFuture().get())
                .as("Led.onCasLost drains the lease-future holder")
                .isNull();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}
