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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.addressOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// C-12 (cluster T assessment, rc4 `7c786db0d3`): re-link after a partition is gated by the per-peer reconcile
/// backoff, 5 s doubling to a 60 s cap with +-20% jitter, reset only by a successful attach. After about 75 s of
/// failed attempts both ends sit at the cap, so a heal is noticed only at the next allowed attempt: up to 72 s
/// later (60 s x 1.2) plus one 5 s tick on the grid. PR-A3 (OQ-7(a)) caps the backoff at its initial value while
/// the raw-SWIM liveness gate reports the peer alive; a peer SWIM does not see alive keeps this schedule, which
/// the remaining tripwires pin. The ones that held the old behaviour for an alive peer are replaced by
/// `reconciler_dialsWithinOneTick_afterThePeerIsReportedAlive`; the gate's own cases are in
/// [ReconcileBackoffSwimGateTest].
///
/// Two levels. [PeerState#reconcileBackoffAllows] takes its jitter as a parameter, so the schedule is reproduced
/// deterministically there (worst case and best case). The network level drives the real reconciler on a fake
/// clock (the same hook `QuicClusterNetworkReconcilerTest` uses) and counts topology lookups, which happen only
/// after the backoff gate lets an attempt through. The liveness input is [QuicClusterNetwork#setSwimLivenessGate],
/// the one `AetherNode` wires from SWIM health (HEALTHY or SUSPECTED), and the target is one 5 s tick.
@Timeout(30)
class ReconcileBackoffTripwireTest {
    private static final long TICK_MS = 5_000;
    private static final long INITIAL_MS = 5_000;
    private static final long CAP_MS = 60_000;
    private static final long SCHEDULE_HORIZON_MS = 1_200_000;
    private static final NodeId LOW = new NodeId("aaa-self");
    private static final NodeId HIGH = new NodeId("zzz-peer");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(TransportTripwireFixtures.AWAIT));
        networks.clear();
    }

    /// Allowed-attempt instants on a 5 s tick grid, every attempt failing, jitter fixed at `factor`.
    private static List<Long> allowedAttempts(double factor) {
        var state = PeerState.peerState(HIGH, 1L);
        var attempts = new ArrayList<Long>();

        for (long now = 0; now <= SCHEDULE_HORIZON_MS; now += TICK_MS) {
            if (state.reconcileBackoffAllows(now, INITIAL_MS, CAP_MS, delay -> Math.round(delay * factor))) {
                attempts.add(now);
            }
        }

        return attempts;
    }

    private static long largestGap(List<Long> instants) {
        long largest = 0;

        for (var i = 1; i < instants.size(); i++) {
            largest = Math.max(largest, instants.get(i) - instants.get(i - 1));
        }

        return largest;
    }

    private static long smallestGapAfterTheFifthAttempt(List<Long> instants) {
        long smallest = Long.MAX_VALUE;

        for (var i = 6; i < instants.size(); i++) {
            smallest = Math.min(smallest, instants.get(i) - instants.get(i - 1));
        }

        return smallest;
    }

    @Test
    void reconcileBackoff_worstCaseGapBetweenAttemptsIsAboutSeventyFiveSeconds_atTheCap() {
        var gap = largestGap(allowedAttempts(1.2));

        assertThat(gap).as("TRIPWIRE C-12: with +20 percent jitter the gap between allowed attempts at the cap is 72 s on a 5 s "
                           + "grid, i.e. 75 s; the schedule for a peer SWIM does not see alive (OQ-7(a): the 60 s cap stays) "
                           + "has changed.")
                       .isBetween(72_000L, 77_000L);
    }

    @Test
    void reconcileBackoff_bestCaseGapBetweenAttemptsIsAboutFortyEightSeconds_atTheCap() {
        var gap = smallestGapAfterTheFifthAttempt(allowedAttempts(0.8));

        assertThat(gap).as("TRIPWIRE C-12: with -20 percent jitter the gap at the cap is 48 s (50 s on the grid): no attempt "
                           + "is closer than that once the cap is reached; the backoff schedule has changed.")
                       .isBetween(48_000L, 52_000L);
    }

    @Test
    void reconciler_dialsWithinOneTick_afterThePeerIsReportedAlive() {
        var scenario = new Outage();

        scenario.runToTheCap();
        scenario.alive.set(true);
        var dialsAfterOneTick = scenario.dialsInNext(TICK_MS);

        assertThat(dialsAfterOneTick).as("dials within one tick of the peer being reported alive").isEqualTo(1L);
    }

    @Test
    void reconciler_spacesAttemptsAtLeastFortyEightSeconds_afterFiveFailedRounds() {
        var scenario = new Outage();

        scenario.runToTheCap();
        var gaps = scenario.gapsOverNext(400_000);

        assertThat(gaps).as("TRIPWIRE C-12: real reconciler on a fake clock: gaps between dispatched dials once the cap is "
                            + "reached; they are 48 to 77 s. Backoff has changed.")
                        .isNotEmpty()
                        .allSatisfy(gap -> assertThat(gap).isBetween(48_000L, 77_000L));
    }

    /// A lower-id node whose single configured peer never answers: the topology returns no `NodeInfo`, so each
    /// lookup is an attempt counted without a real dial (the peer stays INIT and the backoff keeps advancing).
    private final class Outage {
        final AtomicBoolean alive = new AtomicBoolean(false);
        final AtomicLong clock = new AtomicLong(0);
        final TripwireTopology topology = new TripwireTopology(LOW, false, TimeSpan.timeSpan(1).seconds());
        final QuicClusterNetwork network;

        Outage() {
            topology.addPeer(NodeInfo.nodeInfo(HIGH, addressOf("127.0.0.1", 1)));
            network = startedNetwork(topology, MessageRouter.mutable(), networks);
            network.overrideWallClockForTests(clock::get);
            network.setSwimLivenessGate(_ -> alive.get());
        }

        /// 300 s of failed rounds, ending on the tick that dispatched an attempt (so the next one is a full gap away).
        void runToTheCap() {
            dialsInNext(300_000);
            while (dialsInNext(TICK_MS) == 0) {
                // keep ticking until an attempt has just been dispatched
            }
        }

        long dialsInNext(long windowMs) {
            var before = topology.lookupsOf(HIGH);
            var end = clock.get() + windowMs;

            while (clock.get() < end) {
                clock.addAndGet(TICK_MS);
                network.reconcileMissingPeersTick();
            }

            return topology.lookupsOf(HIGH) - before;
        }

        List<Long> gapsOverNext(long windowMs) {
            var gaps = new ArrayList<Long>();
            var end = clock.get() + windowMs;
            var last = clock.get();

            while (clock.get() < end) {
                var before = topology.lookupsOf(HIGH);

                clock.addAndGet(TICK_MS);
                network.reconcileMissingPeersTick();
                if (topology.lookupsOf(HIGH) > before) {
                    gaps.add(clock.get() - last);
                    last = clock.get();
                }
            }

            return gaps;
        }
    }
}
