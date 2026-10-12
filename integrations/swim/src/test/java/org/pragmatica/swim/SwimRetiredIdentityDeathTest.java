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
import org.pragmatica.consensus.net.BootTokens;
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

/// #2173: a member whose identity was retired (a boot-token conflict, proven by the member's own answer) is dead, and the
/// death must reach the membership FSM even while the cold-boot rule would otherwise defer a never-HEALTHY peer's FAULTY.
/// Without it, a killed core that a same-NodeId process then superseded stayed MEMBER on the survivor for good: the
/// retirement is a death-path removal (it feeds the FSM no liveness-gone), and the only other signal, the SWIM FAULTY,
/// was turned into UNKNOWN by the cold-boot rule and then dropped when the member was swept.
///
/// The cold-boot rule itself is untouched for every peer that was not retired (control tests below, and
/// `SwimProtocolColdBootReplayTest`).
class SwimRetiredIdentityDeathTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId PHANTOM = new NodeId("node-phantom");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final NodeId CLAIMANT = new NodeId("node-claimant");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress PHANTOM_ADDR = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9003);
    private static final long TOKEN = 0x1111L;
    private static final long OTHER_TOKEN = 0x2222L;
    /// Past the FAULTY residency (3 x 150 ms) and several ticks: the sweep has run by then.
    private static final Duration PAST_RESIDENCY = Duration.ofMillis(900);

    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(150).millis(),
                          8,
                          timeSpan(50).millis()).withJoinGrace(timeSpan(0).millis())
                                                .withLhmMaxScore(1);
    }

    private static SwimProtocol protocol(AtomicBoolean booting, RecordingObservationSink observations, BootTokens tokens) {
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 booting::get)
                                   .unwrap();

        protocol.addObservationListener(observations);
        protocol.setBootTokens(tokens);

        return protocol;
    }

    @Test
    void retiredNeverHealthyMember_duringColdBoot_isDeclaredDeadAtOnce() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);

        protocol.addSeedMember(NODE_A, ADDR_A);
        tokens.admit(NODE_A, TOKEN);
        tokens.admit(NODE_A, OTHER_TOKEN);

        assertThat(protocol.members().get(NODE_A).state()).as("the retirement marks the member FAULTY").isEqualTo(MemberState.FAULTY);
        assertThat(faulties(observations, NODE_A)).as("the death is emitted, not deferred by the cold-boot rule").hasSize(1);
        assertThat(departures(observations, NODE_A)).hasSize(1);
        protocol.stop();
    }

    /// The member's FAULTY edge came first and was deferred (cold boot); the retirement arrives afterwards. It finds the member
    /// already FAULTY, so there is no new edge, yet the death must still be emitted.
    @Test
    void retirementAfterADeferredFaultyEdge_emitsTheDeferredDeath() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);

        protocol.onMessage(GOSSIPER_ADDR,
                           new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 0, ADDR_A))));
        assertThat(hasUnknown(observations, NODE_A)).as("precondition: the cold-boot rule deferred the FAULTY edge").isTrue();
        assertThat(departures(observations, NODE_A)).as("precondition: nothing departed yet").isEmpty();

        tokens.admit(NODE_A, TOKEN);
        tokens.admit(NODE_A, OTHER_TOKEN);

        assertThat(departures(observations, NODE_A)).as("the retirement replaces the deferral with the death").hasSize(1);
        assertThat(faulties(observations, NODE_A)).hasSize(1);
        protocol.stop();
    }

    /// Control: the same shape without a retirement stays deferred. A same-token admit is a heal, not a conflict.
    @Test
    void sameTokenAdmit_isNotARetirement_andTheColdBootDeferralStands() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);

        protocol.onMessage(GOSSIPER_ADDR,
                           new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 0, ADDR_A))));
        tokens.admit(NODE_A, TOKEN);
        tokens.admit(NODE_A, TOKEN);

        assertThat(protocol.isRetiredIdentity(NODE_A)).isFalse();
        assertThat(hasUnknown(observations, NODE_A)).isTrue();
        assertThat(departures(observations, NODE_A)).as("a heal never departs the member").isEmpty();
        protocol.stop();
    }

    /// The sweep must not drop a deferred death. The residency (3 x 150 ms) expires while the cold-boot gate is still shut; the
    /// member is swept then (a slow seed stays re-addable, see `SwimProtocolTombstoneTest`), and its deferral used to be
    /// cleared with its death memory, so the verdict was lost when the gate closed. It is now kept and replayed.
    @Test
    void deferredDeath_survivesTheResidencySweep_andIsEmittedWhenTheGateCloses() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());

            assertThat(departures(observations, PHANTOM)).as("gate still shut: nothing departs").isEmpty();
            assertThat(protocol.members()).as("positive control: the residency sweep removed the member").doesNotContainKey(PHANTOM);

            booting.set(false);

            await().atMost(Duration.ofSeconds(3)).until(() -> !departures(observations, PHANTOM).isEmpty());
            assertThat(faulties(observations, PHANTOM)).hasSize(1);
            assertThat(protocol.tombstonedForTest(PHANTOM)).as("the replayed edge sets the FAULTY-edge tombstone, as for a resident member").isTrue();
        } finally {
            protocol.stop();
        }
    }

    /// An identity refutation lifts the cold-boot suppression at once (`SwimProtocolIdentityRefutationTest`); that must hold
    /// for a deferral whose member was already swept.
    @Test
    void sweptDeferral_isReplayedOnAnIdentityRefutation_beforeTheGateCloses() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: swept").doesNotContainKey(PHANTOM);
            assertThat(departures(observations, PHANTOM)).isEmpty();

            protocol.recordTransportHint(PHANTOM, new TransportObservation.IdentityRefuted(PHANTOM, CLAIMANT));

            assertThat(departures(observations, PHANTOM)).as("replayed on the refutation, gate still shut").hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    /// A peer that came back after the sweep is judged anew; the old deferral must not depart it when the gate closes.
    @Test
    void sweptDeferral_isNotReplayed_whenThePeerCameBack() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: swept").doesNotContainKey(PHANTOM);

            protocol.putMemberForTest(PHANTOM, PHANTOM_ADDR, MemberState.ALIVE);
            booting.set(false);

            // A replay would run on the next tick (20 ms); a fresh FAULTY for the returned peer needs a probe cycle and the
            // 150 ms suspect window, so nothing may depart in the first 100 ms.
            await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).until(() -> departures(observations, PHANTOM).isEmpty());
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
