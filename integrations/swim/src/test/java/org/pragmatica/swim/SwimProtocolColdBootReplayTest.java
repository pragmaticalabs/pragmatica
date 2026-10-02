/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #1830: a FAULTY edge the cold-boot rule suppressed is deferred, not dropped. When the cold-boot gate
/// closes (`isBooting` flips to `false` — the 75 s convergence window expiring in production), a peer that
/// is still FAULTY and never-HEALTHY gets its real `FaultyObserved` + `DepartedObserved` at once.
///
/// Run-7 shape, time-scaled: a replacement boots with a seed that departed before it started. Before the
/// fix the seed's suppressed FAULTY sat until the residency sweep (here 450 ms after the edge), which — the
/// gate now closed — tombstoned it, so the real FAULTY waited out the tombstone TTL and a fresh probe cycle
/// (3 min 40 s in run 7). Every assertion window below is shorter than that residency, so a pass cannot
/// come from the sweep path.
///
/// The hazard the suppression exists for (A6, `ab1ad6462`, reachable from tag
/// `archive/backup/rc2-pre-rewrite-2026-07-16`): on a full-cluster restart a genuine seed whose QUIC link
/// forms late must not be FAULTY-evicted while the cluster is still forming. Two tests pin that the replay
/// keeps that safety: a genuine slow peer that comes up inside the window, and one whose link is up at the
/// moment the window closes.
class SwimProtocolColdBootReplayTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId PHANTOM = new NodeId("node-phantom");
    private static final NodeId GENUINE = new NodeId("node-genuine");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress PHANTOM_ADDR = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress GENUINE_ADDR = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9003);
    /// Shorter than the FAULTY residency (3 × 150 ms): a departure inside it came from the replay.
    private static final Duration WITHIN_RESIDENCY = Duration.ofMillis(300);
    /// Past the residency and several ticks: long enough for a wrongful departure to show.
    private static final Duration PAST_RESIDENCY = Duration.ofMillis(700);

    /// startupDelay 50 ms, period 20 ms, suspectTimeout 150 ms, joinGrace 0 — a seed that never answers is
    /// OBSERVED → SUSPECT → FAULTY within a few hundred ms. `lhmMaxScore=1` keeps the suspect window at base.
    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(0).millis())
                                                .withLhmMaxScore(1);
    }

    @Test
    void phantomSeed_suppressedDuringColdBoot_departsWhenGateCloses_withoutWaitingForSweep() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 booting::get)
                                   .unwrap();

        protocol.addObservationListener(observations);
        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> hasUnknown(observations, PHANTOM));
            assertThat(departures(observations, PHANTOM)).as("cold boot: the phantom's FAULTY is suppressed")
                                                         .isEmpty();

            booting.set(false);

            await().atMost(WITHIN_RESIDENCY)
                   .until(() -> !departures(observations, PHANTOM).isEmpty());
            assertThat(faulties(observations, PHANTOM)).as("the deferred edge fires the FAULTY pair once")
                                                       .hasSize(1);
            assertThat(departures(observations, PHANTOM)).hasSize(1);
            assertThat(protocol.tombstonedForTest(PHANTOM)).as("the replayed edge sets the FAULTY-edge tombstone")
                                                           .isTrue();
        } finally {
            protocol.stop();
        }
    }

    /// The replay runs BEFORE the residency sweep in the same tick. Here the gate closes and the residency
    /// expires before the protocol ever ticks, so the first tick sees both. Replay first: the phantom departs.
    /// Sweep first: it is removed and tombstoned (the gate is closed), its deferral is cleared with its death
    /// memory, and the verdict is lost again — the #1830 trap.
    @Test
    void replayPrecedesSweep_whenGateClosesAndResidencyExpiresInTheSameTick() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 booting::get)
                                   .unwrap();

        protocol.addObservationListener(observations);
        // Not started: no tick runs. A gossiped FAULTY for the phantom is suppressed by cold boot and stamped.
        protocol.onMessage(GOSSIPER_ADDR,
                           new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(PHANTOM, MemberState.FAULTY, 0, PHANTOM_ADDR))));
        assertThat(hasUnknown(observations, PHANTOM)).isTrue();
        Thread.sleep(PAST_RESIDENCY.toMillis());
        booting.set(false);

        protocol.start();
        try {
            await().atMost(WITHIN_RESIDENCY)
                   .until(() -> !departures(observations, PHANTOM).isEmpty());
        } finally {
            protocol.stop();
        }
    }

    @Test
    void genuineSlowPeer_comesUpBeforeGateCloses_isNeverDeparted() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 booting::get)
                                   .unwrap();

        protocol.addObservationListener(observations);
        // The genuine peer is not up yet: a peer's gossip says FAULTY, and cold boot suppresses it. The
        // edge is second-hand, the case the #336 kill-gate does not hold — so only the replay's own guard
        // stands between a since-recovered peer and a departure.
        protocol.onMessage(GOSSIPER_ADDR,
                           new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(GENUINE, MemberState.FAULTY, 0, GENUINE_ADDR))));
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> hasUnknown(observations, GENUINE));

            // "60 s": the genuine peer comes up and gossips its own Alive at its boot incarnation.
            protocol.onMessage(GENUINE_ADDR,
                               new Ping(GENUINE, 1L, List.of(MembershipUpdate.membershipUpdate(GENUINE, MemberState.ALIVE, 5, GENUINE_ADDR))));
            await().atMost(Duration.ofSeconds(1))
                   .until(() -> protocol.everSeenHealthyForTest(GENUINE));

            // "75 s": the cold-boot window closes.
            booting.set(false);

            await().pollDelay(PAST_RESIDENCY)
                   .atMost(PAST_RESIDENCY.plusSeconds(1))
                   .until(() -> true);
            assertThat(departures(observations, GENUINE)).as("a peer that came up inside the window is never departed")
                                                         .isEmpty();
            assertThat(faulties(observations, GENUINE)).isEmpty();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void genuinePeerWithLiveLink_whenGateCloses_isHeldByTransportVeto() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 booting::get,
                                                 GENUINE::equals)
                                   .unwrap();

        protocol.addObservationListener(observations);
        protocol.addSeedMember(GENUINE, GENUINE_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> hasUnknown(observations, GENUINE));

            // The QUIC link is up but SWIM has not yet heard the peer when the window closes.
            booting.set(false);

            await().pollDelay(WITHIN_RESIDENCY)
                   .atMost(WITHIN_RESIDENCY.plusSeconds(1))
                   .until(() -> true);
            assertThat(departures(observations, GENUINE)).as("the replay goes through the live-transport veto")
                                                         .isEmpty();
            assertThat(faulties(observations, GENUINE)).isEmpty();
        } finally {
            protocol.stop();
        }
    }

    private static boolean hasUnknown(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.UnknownObserved.class)
                           .stream()
                           .anyMatch(observation -> observation.peer().equals(peer));
    }

    private static List<SwimObservation.DepartedObserved> departures(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.DepartedObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }

    private static List<SwimObservation.FaultyObserved> faulties(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.FaultyObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }
}
