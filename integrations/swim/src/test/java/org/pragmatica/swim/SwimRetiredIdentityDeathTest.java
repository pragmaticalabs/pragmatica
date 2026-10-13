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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolPhaseAwareSuppressionTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;

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
/// `SwimProtocolColdBootReplayTest`). The cases from the independent verification of #2171 are folded in: exactly-once
/// across refusals, sweep and re-seed; the formed-cluster shape past the co-confirmation gate; the transport veto; and
/// the swept-deferral edge cases (a stale deferral, a descoped peer).
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
    private static final NodeInfo PHANTOM_INFO = NodeInfo.nodeInfo(PHANTOM, new NodeAddress("127.0.0.1", 9002));
    private static final long TOKEN = 0x1111L;
    private static final long OTHER_TOKEN = 0x2222L;
    /// Past the FAULTY residency (3 x 150 ms) and several ticks: the sweep has run by then.
    private static final Duration PAST_RESIDENCY = Duration.ofMillis(900);
    /// Many ticks (20 ms each) after the gate closed: a replay would have run by then.
    private static final Duration AFTER_GATE_CLOSE = Duration.ofMillis(400);

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
        return protocol(booting, observations, tokens, new RecordingTransport());
    }

    private static SwimProtocol protocol(AtomicBoolean booting,
                                         RecordingObservationSink observations,
                                         BootTokens tokens,
                                         RecordingTransport transport) {
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 transport,
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

    /// The retirement's death also reaches the cluster-wide `TransportObservation` stream (`PeerObservedFaulty`), the
    /// stream the SWIM-internal observations do not feed, and only once across repeated refusals.
    @Test
    void retirement_emitsTheClusterFaultyObservation_once() {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);
        var clusterObservations = new CopyOnWriteArrayList<Object>();

        protocol.addTransportObservationEmitter(clusterObservations::add);
        protocol.addSeedMember(NODE_A, ADDR_A);
        tokens.admit(NODE_A, TOKEN);
        tokens.admit(NODE_A, OTHER_TOKEN);
        assertThat(tokens.admit(NODE_A, OTHER_TOKEN)).isEqualTo(BootTokens.Admission.RETIRED);

        assertThat(clusterObservations).as("one cluster-wide PeerObservedFaulty for the retired identity")
                                       .filteredOn(observation -> observation instanceof PeerObservedFaulty faulty && faulty.nodeId()
                                                                                                                             .equals(NODE_A))
                                       .hasSize(1);
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

    /// Formed-cluster shape: an ever-HEALTHY member retired by its own answer, in NORMAL phase. Its first-hand FAULTY has one
    /// accuser, so the co-confirmation gate would hold it, and gossip about a retired id is refused, so no second accuser
    /// can arrive. The retirement must depart it at once.
    @Test
    void retiredEverHealthyMember_inNormalPhase_isDepartedPastTheCoConfirmationGate() {
        var observations = new RecordingObservationSink();
        var transport = new RecordingTransport();
        var protocol = protocol(new AtomicBoolean(false), observations, BootTokens.bootTokens(0x5E1FL), transport);
        var answered = new HashSet<Long>();

        try {
            protocol.announceFromPinnedSourceForTest(PHANTOM_ADDR, Announce.announce(PHANTOM_INFO, "", 5, TOKEN));
            answerPingsWith(protocol, transport, answered, 5, TOKEN);
            protocol.probeOnceForTest();
            answerPingsWith(protocol, transport, answered, 5, TOKEN);
            assertThat(protocol.everSeenHealthyForTest(PHANTOM)).as("precondition: ever-HEALTHY").isTrue();

            protocol.announceFromPinnedSourceForTest(PHANTOM_ADDR, Announce.announce(PHANTOM_INFO, "", 6, OTHER_TOKEN));
            answerPingsWith(protocol, transport, answered, 6, OTHER_TOKEN);

            assertThat(protocol.isRetiredIdentity(PHANTOM)).as("precondition: retired by its own answer").isTrue();
            assertThat(departures(observations, PHANTOM)).as("the retirement departs an ever-HEALTHY member at once").hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    /// NORMAL phase, never-HEALTHY member whose transport link is up (the live-transport veto would defer its FAULTY): a
    /// retirement departs it at once.
    @Test
    void retiredNeverHealthyMember_withALiveLink_isDepartedPastTheTransportVeto() {
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = SwimProtocol.swimProtocol(tightConfig(),
                                                 new RecordingTransport(),
                                                 new RecordingListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 () -> false,
                                                 _ -> true)
                                   .unwrap();

        protocol.addObservationListener(observations);
        protocol.setBootTokens(tokens);
        try {
            protocol.addSeedMember(NODE_A, ADDR_A);
            tokens.admit(NODE_A, TOKEN);
            tokens.admit(NODE_A, OTHER_TOKEN);

            assertThat(departures(observations, NODE_A)).as("the retirement departs past the live-transport veto").hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    /// NORMAL phase, retired while the protocol ticks: one FAULTY/DEPARTED pair, and none more after further refused
    /// admissions of either token, hearsay FAULTY gossip, the residency sweep (which clears the edge marker), and a seed
    /// re-add attempt.
    @Test
    void retirement_departsExactlyOnce_acrossRefusalsGossipSweepAndReseed() throws InterruptedException {
        var booting = new AtomicBoolean(false);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);

        protocol.addSeedMember(NODE_A, ADDR_A);
        protocol.start();
        try {
            tokens.admit(NODE_A, TOKEN);
            assertThat(tokens.admit(NODE_A, OTHER_TOKEN)).isEqualTo(BootTokens.Admission.CONFLICT);
            assertThat(departures(observations, NODE_A)).as("retirement emits the death at once").hasSize(1);

            assertThat(tokens.admit(NODE_A, OTHER_TOKEN)).isEqualTo(BootTokens.Admission.RETIRED);
            assertThat(tokens.admit(NODE_A, TOKEN)).isEqualTo(BootTokens.Admission.RETIRED);
            protocol.onMessage(GOSSIPER_ADDR,
                               new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 3, ADDR_A))));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: the residency sweep ran").doesNotContainKey(NODE_A);

            protocol.addSeedMember(NODE_A, ADDR_A);
            Thread.sleep(200);

            assertThat(protocol.members()).as("a retired identity is never re-seeded").doesNotContainKey(NODE_A);
            assertThat(departures(observations, NODE_A)).as("exactly one DEPARTED for the retired identity").hasSize(1);
            assertThat(faulties(observations, NODE_A)).as("exactly one FAULTY for the retired identity").hasSize(1);
        } finally {
            protocol.stop();
        }
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

    /// The author's declared limit: a retirement for an id whose member was already swept emits nothing at once; the kept
    /// deferral departs it when the gate closes, exactly once.
    @Test
    void retirementOfASweptDeferredPeer_departsOnceWhenTheGateCloses() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var protocol = protocol(booting, observations, tokens);

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: swept").doesNotContainKey(PHANTOM);

            tokens.admit(PHANTOM, TOKEN);
            tokens.admit(PHANTOM, OTHER_TOKEN);
            assertThat(protocol.isRetiredIdentity(PHANTOM)).isTrue();
            assertThat(departures(observations, PHANTOM)).as("no member: the retirement itself emits nothing").isEmpty();

            booting.set(false);
            await().atMost(Duration.ofSeconds(3)).until(() -> !departures(observations, PHANTOM).isEmpty());
            Thread.sleep(300);

            assertThat(departures(observations, PHANTOM)).hasSize(1);
            assertThat(faulties(observations, PHANTOM)).hasSize(1);
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

    /// A peer that came back after the sweep is judged anew; the old deferral must not depart it when the gate closes. The
    /// returned peer answers every probe, so it never earns a FAULTY edge of its own: any FAULTY, UNKNOWN or DEPARTED after
    /// the gate closes can only be the replay.
    @Test
    void sweptDeferral_isNotReplayed_whenThePeerCameBack() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var transport = new AutoAckTransport();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL), transport);

        transport.protocol = protocol;
        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: swept").doesNotContainKey(PHANTOM);

            transport.answering = true;
            protocol.putMemberForTest(PHANTOM, PHANTOM_ADDR, MemberState.ALIVE);
            await().atMost(Duration.ofSeconds(3)).until(() -> protocol.everSeenHealthyForTest(PHANTOM));
            var unknownsBeforeGateClose = unknowns(observations, PHANTOM).size();

            booting.set(false);
            Thread.sleep(AFTER_GATE_CLOSE.toMillis());

            assertThat(departures(observations, PHANTOM)).as("the returned peer is not departed").isEmpty();
            assertThat(faulties(observations, PHANTOM)).as("the returned peer is not declared FAULTY").isEmpty();
            assertThat(unknowns(observations, PHANTOM)).as("no deferred edge is replayed for the returned peer")
                                                       .hasSize(unknownsBeforeGateClose);
        } finally {
            protocol.stop();
        }
    }

    /// A deferral is kept across a sweep. The peer then comes back, proves HEALTHY, dies (its pair is emitted) and is swept
    /// again, all before the cold-boot gate closes. The stale deferral must not be replayed as a second DEPARTED at the old
    /// incarnation when the gate closes.
    @Test
    void sweptDeferral_ofAPeerThatCameBackAndDiedAgain_isNotReplayedAsASecondDeparture() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var transport = new AutoAckTransport();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL), transport);

        transport.protocol = protocol;
        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: first sweep, deferral kept").doesNotContainKey(PHANTOM);

            // The peer comes back and proves itself HEALTHY by answering probes (acked at once, inside the probe timeout).
            transport.answering = true;
            protocol.announceFromPinnedSourceForTest(PHANTOM_ADDR, Announce.announce(PHANTOM_INFO, "", 1, 0L));
            await().atMost(Duration.ofSeconds(3)).until(() -> protocol.everSeenHealthyForTest(PHANTOM));
            transport.answering = false;
            assertThat(healthies(observations, PHANTOM)).as("positive control: the returned peer is HEALTHY").isNotEmpty();

            dieAndAwaitTheSweep(protocol, observations);

            booting.set(false);
            Thread.sleep(AFTER_GATE_CLOSE.toMillis());

            assertThat(departures(observations, PHANTOM)).as("the stale deferral must not depart the peer a second time").hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    /// The same staleness without a first sweep: the deferred member is refuted back to ALIVE before its residency ends,
    /// proves HEALTHY, dies (its pair is emitted) and is swept, all before the cold-boot gate closes. The deferral it carried
    /// from before it proved HEALTHY must not be kept by that sweep and replayed as a second DEPARTED.
    @Test
    void deferredEdge_ofAPeerThatProvedHealthyBeforeTheSweep_isNotReplayedAsASecondDeparture() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var transport = new AutoAckTransport();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL), transport);

        transport.protocol = protocol;
        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));

            transport.answering = true;
            protocol.putMemberForTest(PHANTOM, PHANTOM_ADDR, MemberState.ALIVE);
            await().atMost(Duration.ofSeconds(3)).until(() -> protocol.everSeenHealthyForTest(PHANTOM));
            transport.answering = false;
            assertThat(protocol.members()).as("positive control: refuted before the sweep, never swept").containsKey(PHANTOM);

            dieAndAwaitTheSweep(protocol, observations);

            booting.set(false);
            Thread.sleep(AFTER_GATE_CLOSE.toMillis());

            assertThat(departures(observations, PHANTOM)).as("the pre-HEALTHY deferral must not depart the peer a second time").hasSize(1);
        } finally {
            protocol.stop();
        }
    }

    /// Scope removal is not a death verdict: a kept deferral for a peer that leaves the membership scope before the gate closes
    /// must never be replayed as a FAULTY/DEPARTED pair.
    @Test
    void sweptDeferral_ofAPeerLeavingTheMembershipScope_isNeverReplayedAsADeath() throws InterruptedException {
        var booting = new AtomicBoolean(true);
        var observations = new RecordingObservationSink();
        var protocol = protocol(booting, observations, BootTokens.bootTokens(0x5E1FL));

        protocol.addSeedMember(PHANTOM, PHANTOM_ADDR);
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(3)).until(() -> hasUnknown(observations, PHANTOM));
            Thread.sleep(PAST_RESIDENCY.toMillis());
            assertThat(protocol.members()).as("positive control: swept, deferral kept").doesNotContainKey(PHANTOM);

            protocol.setMembershipEligibility(peer -> !peer.equals(PHANTOM));
            booting.set(false);
            Thread.sleep(AFTER_GATE_CLOSE.toMillis());

            assertThat(departures(observations, PHANTOM)).as("a descoped peer's kept deferral is dropped, not replayed").isEmpty();
        } finally {
            protocol.stop();
        }
    }

    /// The HEALTHY phantom dies: a second-hand FAULTY verdict is authoritative for an ever-HEALTHY peer, so its pair is
    /// emitted at once; the residency sweep then removes the member.
    private static void dieAndAwaitTheSweep(SwimProtocol protocol, RecordingObservationSink observations) {
        protocol.onMessage(GOSSIPER_ADDR,
                           new Ping(GOSSIPER, 1L, List.of(MembershipUpdate.membershipUpdate(PHANTOM, MemberState.FAULTY, 1, PHANTOM_ADDR))));
        await().atMost(Duration.ofSeconds(3)).until(() -> !departures(observations, PHANTOM).isEmpty());
        await().atMost(Duration.ofSeconds(3)).until(() -> !protocol.members().containsKey(PHANTOM));
        assertThat(departures(observations, PHANTOM)).as("positive control: the real death departed once").hasSize(1);
    }

    private static void answerPingsWith(SwimProtocol protocol,
                                        RecordingTransport transport,
                                        Set<Long> answered,
                                        long incarnation,
                                        long token) {
        transport.sentMessages.stream()
                              .filter(sent -> sent.target().equals(PHANTOM_ADDR) && sent.message() instanceof Ping)
                              .map(sent -> (Ping) sent.message())
                              .filter(ping -> answered.add(ping.sequence()))
                              .toList()
                              .forEach(ping -> protocol.onMessage(PHANTOM_ADDR,
                                                                  Ack.ack(PHANTOM,
                                                                          ping.sequence(),
                                                                          List.of(MembershipUpdate.membershipUpdate(PHANTOM,
                                                                                                                    MemberState.ALIVE,
                                                                                                                    incarnation,
                                                                                                                    PHANTOM_ADDR,
                                                                                                                    token)))));
    }

    /// Answers every Ping to the phantom's address at once, on another thread, while `answering` is set.
    static final class AutoAckTransport extends RecordingTransport {
        volatile SwimProtocol protocol;
        volatile boolean answering;

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            var sent = super.send(target, message);

            if (answering && target.equals(PHANTOM_ADDR) && message instanceof Ping ping) {
                Thread.ofVirtual()
                      .start(() -> protocol.onMessage(PHANTOM_ADDR,
                                                      Ack.ack(PHANTOM,
                                                              ping.sequence(),
                                                              List.of(MembershipUpdate.membershipUpdate(PHANTOM, MemberState.ALIVE, 1, PHANTOM_ADDR)))));
            }

            return sent;
        }
    }

    private static boolean hasUnknown(RecordingObservationSink observations, NodeId peer) {
        return !unknowns(observations, peer).isEmpty();
    }

    private static List<SwimObservation.UnknownObserved> unknowns(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.UnknownObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
    }

    private static List<SwimObservation.HealthyObserved> healthies(RecordingObservationSink observations, NodeId peer) {
        return observations.byType(SwimObservation.HealthyObserved.class)
                           .stream()
                           .filter(observation -> observation.peer().equals(peer))
                           .toList();
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
