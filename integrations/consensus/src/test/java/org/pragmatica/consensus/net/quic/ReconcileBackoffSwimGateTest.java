/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.addressOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// NT-A12 (cluster T, PR-A3): the per-peer reconcile backoff follows the raw-SWIM liveness gate. While the gate
/// reports the peer HEALTHY or SUSPECTED the backoff is evaluated with its cap equal to its initial value (5 s), so
/// the delay stays at the initial value and every 5 s tick may dial; while it does not, the delay doubles to the
/// 60 s cap as before. An unwired gate reads as not alive. The gate clocks are injected (`overrideWallClockForTests`
/// and the `nowMs` argument of [PeerState#reconcileBackoffAllowsWhileAlive]); nothing waits on a timed window for
/// a negative assertion. The tick-offset tests run the scheduler entry for real, because the offset is a
/// scheduling delay. Mutation MT-A9: leave the backoff uncapped when the gate reports alive.
@Timeout(60)
class ReconcileBackoffSwimGateTest {
    private static final long TICK_MS = 5_000;
    private static final long INITIAL_MS = 5_000;
    private static final long CAP_MS = 60_000;
    private static final long ALIVE_SPACING_MS = 4_000;
    private static final NodeId LOW = new NodeId("aaa-self");
    private static final NodeId HIGH = new NodeId("zzz-peer");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(TransportTripwireFixtures.AWAIT));
        networks.clear();
    }

    @Test
    void reconcileBackoffAllowsWhileAlive_allowsOnTheNextTick_afterTheDelayReachedItsCap() {
        var state = PeerState.peerState(HIGH, 1L);
        var now = 0L;

        while (state.reconcileCurrentDelayMs() < CAP_MS) {
            now += TICK_MS;
            state.reconcileBackoffAllows(now, INITIAL_MS, CAP_MS, delay -> delay * 12 / 10);
        }
        var lastAttempt = now;

        assertThat(state.reconcileBackoffAllowsWhileAlive(lastAttempt + ALIVE_SPACING_MS - 1, INITIAL_MS, ALIVE_SPACING_MS))
            .as("below the minimum spacing the alive evaluation still holds the dial back")
            .isFalse();
        assertThat(state.reconcileBackoffAllowsWhileAlive(lastAttempt + ALIVE_SPACING_MS, INITIAL_MS, ALIVE_SPACING_MS))
            .as("at exactly the minimum spacing after the last attempt the alive evaluation lets the dial through, whatever attempt time the 60 s cap earned")
            .isTrue();
        assertThat(state.reconcileBackoffAllowsWhileAlive(lastAttempt + ALIVE_SPACING_MS + 1, INITIAL_MS, ALIVE_SPACING_MS))
            .as("an allowed alive attempt is itself an attempt: the spacing restarts from it")
            .isFalse();
        assertThat(state.reconcileCurrentDelayMs()).as("the alive evaluation leaves the delay at its initial value").isEqualTo(INITIAL_MS);
    }

    @Test
    void reconcileBackoffAllowsWhileAlive_allowsEveryTickAndKeepsTheInitialDelay_whenNoAttemptWasMadeBefore() {
        var state = PeerState.peerState(HIGH, 1L);
        var allowed = 0;

        for (var now = 0L; now < 20 * TICK_MS; now += TICK_MS) {
            allowed += state.reconcileBackoffAllowsWhileAlive(now, INITIAL_MS, ALIVE_SPACING_MS) ? 1 : 0;
        }

        assertThat(allowed).as("one dial per tick while alive").isEqualTo(20);
        assertThat(state.reconcileCurrentDelayMs()).isEqualTo(INITIAL_MS);
    }

    @Test
    void drawReconcileTickOffsetMs_staysBelowTheJitterBoundAndVaries() {
        var distinct = new HashSet<Long>();

        for (var i = 0; i < 2_000; i++) {
            var offset = QuicClusterNetwork.drawReconcileTickOffsetMs();

            assertThat(offset).as("the offset keeps two consecutive ticks at least 4.2 s apart, above the 4 s alive spacing").isBetween(0L, 799L);
            distinct.add(offset);
        }

        assertThat(distinct).as("the offset is random, not constant: nodes started together do not tick together").hasSizeGreaterThan(100);
    }

    @Test
    void scheduleJitteredReconcileTick_defersTheDialByTheOffset_whenTheTickFiresFromTheScheduler() {
        var scenario = new Scenario(true);

        scenario.alive.set(true);

        assertThat(scenario.dialDelayMsFor(600L)).as("the dial happens at the jittered instant, not at the tick").isGreaterThanOrEqualTo(590L);
    }

    @Test
    void scheduleJitteredReconcileTick_dialsTwoNodesAtDifferentInstants_whenBothTicksFireTogether() {
        var early = new Scenario(true);
        var late = new Scenario(true);

        early.alive.set(true);
        late.alive.set(true);
        var earlyDialMs = early.dialDelayMsFor(50L);
        var lateDialMs = late.dialDelayMsFor(700L);

        assertThat(lateDialMs - earlyDialMs).as("two nodes whose ticks fire at the same instant dial a healed peer at different instants").isGreaterThanOrEqualTo(400L);
    }

    @Test
    void resetReconcileBackoff_allowsTheNextAliveAttemptAtOnce_afterAnAttach() {
        var state = PeerState.peerState(HIGH, 1L);

        state.reconcileBackoffAllowsWhileAlive(100_000L, INITIAL_MS, ALIVE_SPACING_MS);
        state.resetReconcileBackoff();

        assertThat(state.reconcileBackoffAllowsWhileAlive(100_001L, INITIAL_MS, ALIVE_SPACING_MS))
            .as("a successful attach resets the backoff: the next attempt is not held back by the spacing")
            .isTrue();
    }

    @Test
    void reconciler_keepsTheInitialDelayAndDialsEveryTick_whileTheGateReportsTheTopologyPeerAlive() {
        var scenario = new Scenario(true);

        scenario.alive.set(true);
        scenario.ticks(24);

        assertThat(scenario.delayMs()).as("backoff delay while SWIM reports the peer alive").isEqualTo(INITIAL_MS);
        assertThat(scenario.lookups()).as("one dial per tick while SWIM reports the peer alive").isEqualTo(24L);
    }

    @Test
    void reconciler_doublesTheDelayToTheCap_whileTheGateReportsTheTopologyPeerNotAlive() {
        var scenario = new Scenario(true);

        scenario.ticks(60);

        assertThat(scenario.delayMs()).as("backoff delay while SWIM does not report the peer alive").isEqualTo(CAP_MS);
        assertThat(scenario.lookups()).as("far fewer dials than ticks once the delay doubles").isLessThan(20L);
    }

    @Test
    void reconciler_doublesTheDelayToTheCap_whenNoLivenessGateIsWired() {
        var scenario = new Scenario(false);

        scenario.ticks(60);

        assertThat(scenario.delayMs()).as("an unwired gate reads as not alive: the legacy schedule").isEqualTo(CAP_MS);
    }

    @Test
    void reconciler_dialsOnTheNextTick_whenTheGateTurnsAliveAtTheCap() {
        var scenario = new Scenario(true);

        scenario.ticks(60);
        var before = scenario.lookups();
        scenario.alive.set(true);
        scenario.ticks(1);

        assertThat(scenario.lookups() - before).as("dials within one tick of the peer being reported alive").isEqualTo(1L);
        assertThat(scenario.delayMs()).as("the alive evaluation brings the delay back to its initial value").isEqualTo(INITIAL_MS);
    }

    @Test
    void reconciler_resumesDoublingFromTheInitialDelay_whenTheGateTurnsNotAliveAgain() {
        var scenario = new Scenario(true);

        scenario.alive.set(true);
        scenario.ticks(6);
        scenario.alive.set(false);
        scenario.ticks(60);

        assertThat(scenario.delayMs()).as("a peer SWIM stops seeing alive ramps up again to the cap").isEqualTo(CAP_MS);
    }

    @Test
    void reconciler_keepsTheInitialDelay_whileTheGateReportsTheDesiredPeerAlive() {
        var scenario = new Scenario(true);

        scenario.useDesiredConnections();
        scenario.alive.set(true);
        scenario.ticksAwaitingTheDial(12);

        assertThat(scenario.delayMs()).as("FSM desired-set path: backoff delay while SWIM reports the peer alive").isEqualTo(INITIAL_MS);
    }

    @Test
    void reconciler_doublesTheDelayToTheCap_whileTheGateReportsTheDesiredPeerNotAlive() {
        var scenario = new Scenario(true);

        scenario.useDesiredConnections();
        scenario.ticksAwaitingTheDial(40);

        assertThat(scenario.delayMs()).as("FSM desired-set path: backoff delay while SWIM does not report the peer alive").isEqualTo(CAP_MS);
    }

    /// A lower-id node whose single configured peer never answers. The topology path counts a lookup per allowed
    /// attempt (it returns no `NodeInfo`, so nothing is dialed and the peer stays INIT); the desired-set path dials a
    /// host that never resolves, so the dial fails without pinning the peer in CONNECTING.
    private final class Scenario {
        final AtomicBoolean alive = new AtomicBoolean(false);
        final AtomicLong clock = new AtomicLong(0);
        final TripwireTopology topology = new TripwireTopology(LOW, false, TimeSpan.timeSpan(1).seconds());
        final QuicClusterNetwork network;

        Scenario(boolean gateWired) {
            topology.addPeer(NodeInfo.nodeInfo(HIGH, addressOf("127.0.0.1", 1)));
            network = startedNetwork(topology, MessageRouter.mutable(), networks);
            network.overrideWallClockForTests(clock::get);
            if (gateWired) {
                network.setSwimLivenessGate(_ -> alive.get());
            }
        }

        void useDesiredConnections() {
            var peer = NodeInfo.nodeInfo(HIGH, addressOf("no-such-host.invalid", 4242));

            network.setDesiredConnections(() -> List.of(peer));
        }

        /// Fires the scheduler entry of the reconciler with a fixed offset and returns the delay until the dial
        /// (a topology lookup) arrives.
        long dialDelayMsFor(long offsetMs) {
            network.overrideReconcileTickOffsetForTests(() -> offsetMs);
            clock.addAndGet(TICK_MS);
            var start = System.nanoTime();

            network.scheduleJitteredReconcileTick();
            awaitTrue(() -> lookups() >= 1L, TransportTripwireFixtures.AWAIT, "the deferred dial");

            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        void ticks(int count) {
            for (var i = 0; i < count; i++) {
                clock.addAndGet(TICK_MS);
                network.reconcileMissingPeersTick();
            }
        }

        /// Resolution is async on the Netty event loop: let each dial settle before the next tick, as a
        /// 5 s tick would.
        void ticksAwaitingTheDial(int count) {
            for (var i = 0; i < count; i++) {
                ticks(1);
                awaitTrue(this::notConnecting, TransportTripwireFixtures.AWAIT, "the dial attempt to settle");
            }
        }

        boolean notConnecting() {
            return network.peerPhaseForTests(HIGH).map(phase -> phase != PeerState.Phase.CONNECTING).or(true);
        }

        long delayMs() {
            return network.peerStateForTests(HIGH).reconcileCurrentDelayMs();
        }

        long lookups() {
            return topology.lookupsOf(HIGH);
        }
    }
}
