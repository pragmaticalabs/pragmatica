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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimObservationStreamTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A member's SWIM address is pinned to its process identity: the first source address that announced it and
/// answered a probe as that member. Each test announces the member from a second source address and pins one
/// thing that must not follow. A source address that has not answered a probe pins nothing and gets nothing but
/// that probe.
class SwimAnnounceSourcePinTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("10.0.0.100", 9000);
    private static final InetSocketAddress A_REAL = new InetSocketAddress("10.0.0.1", 9001);
    private static final InetSocketAddress A_MOVED = new InetSocketAddress("10.0.0.2", 9001);
    private static final InetSocketAddress A_OTHER = new InetSocketAddress("203.0.113.9", 9001);
    private static final InetSocketAddress B_ADDR = new InetSocketAddress("10.0.0.3", 9002);
    private static final InetSocketAddress C_ADDR = new InetSocketAddress("10.0.0.4", 9003);
    private static final long TOKEN_A = 4242L;

    private final RecordingTransport transport = new RecordingTransport();
    private final RecordingListener listener = new RecordingListener();
    private final RecordingObservationSink observations = new RecordingObservationSink();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private SwimProtocol protocol;

    @AfterEach
    void tearDown() {
        if (protocol != null) {
            protocol.stop();
        }
    }

    // -- probe address --

    @Test
    void announceFromAnotherSource_doesNotRewriteTheProbeAddress() {
        protocol = manualProtocol();
        pinnedMemberA();

        protocol.onMessage(A_OTHER, announceA());

        assertThat(protocol.members().get(NODE_A).address())
            .as("a pinned member's SWIM probe address must not follow another source address")
            .isEqualTo(A_REAL);
    }

    // -- dial address --

    @Test
    void announceFromAnotherSource_doesNotRewriteTheQuicDialAddress() {
        protocol = manualProtocol();
        pinnedMemberA();
        var joinsBefore = observations.byType(SwimObservation.JoinAnnounced.class).size();

        protocol.onMessage(A_OTHER, announceA());

        assertThat(observations.byType(SwimObservation.JoinAnnounced.class)
                               .stream()
                               .skip(joinsBefore)
                               .map(join -> join.nodeInfo().resolvedAddress().host()))
            .as("no dial hint may carry a source address other than the pin")
            .doesNotContain(A_OTHER.getAddress().getHostAddress());
    }

    // -- join ack --

    @Test
    void announceFromAnotherSource_getsNoSnapshotReply() {
        protocol = manualProtocol();
        protocol.putMemberForTest(NODE_B, B_ADDR, MemberState.ALIVE);
        protocol.putMemberForTest(NODE_C, C_ADDR, MemberState.ALIVE);
        pinnedMemberA();
        transport.sentMessages.clear();

        protocol.onMessage(A_OTHER, announceA());

        assertThat(transport.sentMessages.stream().filter(sent -> sent.target().equals(A_OTHER)).toList())
            .as("nothing may be sent to a source address that is not the member's pin")
            .isEmpty();
    }

    @Test
    void announceFromTheConfirmedSource_getsTheSnapshotReply() {
        protocol = manualProtocol();
        protocol.putMemberForTest(NODE_B, B_ADDR, MemberState.ALIVE);
        pinnedMemberA();
        transport.sentMessages.clear();

        protocol.onMessage(A_REAL, announceA());

        assertThat(transport.sentMessages.stream()
                            .filter(sent -> sent.target().equals(A_REAL))
                            .filter(sent -> sent.message() instanceof Ack ack && ack.sequence() == 0L)
                            .toList())
            .as("the join ack is still delivered to the pinned address")
            .isNotEmpty();
    }

    // -- tombstone --

    @Test
    void announceFromAnotherSource_doesNotClearTheTombstone() {
        protocol = tickingProtocol();
        pinnedMemberA();
        driveToTombstone();

        protocol.onMessage(A_OTHER, announceA());

        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("an ANNOUNCE from an unpinned address must not clear a tombstone")
            .isTrue();
        assertThat(protocol.members()).as("and must not re-introduce it").doesNotContainKey(NODE_A);
    }

    @Test
    void announceFromTheConfirmedSource_stillClearsTheTombstone_partitionHeal() {
        protocol = tickingProtocol();
        pinnedMemberA();
        driveToTombstone();

        protocol.onMessage(A_REAL, announceA());

        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("the member itself, from its own address, heals exactly as before")
            .isFalse();
        assertThat(protocol.members()).containsKey(NODE_A);
    }

    // -- the operator event --

    @Test
    void announceFromAnotherSource_raisesOneAddressConflictWarning_throttledPerAttemptedSource() {
        protocol = manualProtocol();
        pinnedMemberA();

        for (int i = 0; i < 50; i++) {
            protocol.onMessage(A_OTHER, announceA());
        }

        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> !warnings.isEmpty());
        assertThat(protocol.addressConflictsReportedForTest()).as("one transition, one report, not one per datagram").isEqualTo(1);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.SWIM_MEMBER_ADDRESS_CONFLICT);
        assertThat(warnings.getFirst().message()).contains(NODE_A.id(), "10.0.0.1", "203.0.113.9");
    }

    @Test
    void normalReAnnounceFromThePinnedSource_raisesNoWarning() {
        protocol = manualProtocol();
        pinnedMemberA();

        protocol.onMessage(A_REAL, announceA());
        protocol.onMessage(A_REAL, announceA());

        assertNoConflictRaised("a false alert is blocking");
    }

    @Test
    void newProcessOnANewAddress_getsAFreshPin_andRaisesNoWarning() {
        protocol = manualProtocol();
        pinnedMemberA();
        var restarted = new NodeId("node-a-restarted");
        var newSource = new InetSocketAddress("10.0.0.7", 9001);

        protocol.onMessage(newSource, Announce.announce(infoOf(restarted, 9001), "", 1L, 777L));
        confirmPin(newSource, restarted, 777L);

        assertNoConflictRaised("a restart (a new process identity) is not a conflict");
        assertThat(protocol.members()).containsKey(restarted);
        protocol.onMessage(newSource, Announce.announce(infoOf(restarted, 9001), "", 1L, 777L));
        assertNoConflictRaised("a re-announce from the new pin");
    }

    @Test
    void newTokenFromAnotherAddress_isAnAddressConflict_andRetiresNothing() {
        protocol = manualProtocol();
        pinnedMemberA();

        protocol.onMessage(A_MOVED, Announce.announce(infoOf(NODE_A, 9001), "", 1L, TOKEN_A + 1));

        assertThat(protocol.addressConflictsReportedForTest()).as("the pin refuses it before any token is looked at").isEqualTo(1);
        assertThat(protocol.bootTokenRefusals()).isZero();
        assertThat(protocol.addressPinForTest(NODE_A).map(ip -> ip.getHostAddress()).or("none")).isEqualTo("10.0.0.1");
    }

    // -- first contact --

    @Test
    void firstContact_otherSourceArrivingFirst_doesNotBecomeThePin() {
        protocol = manualProtocol();

        // an announce from an address that never answers arrives first
        protocol.onMessage(A_OTHER, announceA());
        // the member's own ANNOUNCE then arrives, and its probe is answered
        protocol.onMessage(A_REAL, announceA());
        confirmPin(A_REAL, NODE_A, TOKEN_A);

        assertThat(protocol.members().get(NODE_A).address())
            .as("the address that answered a probe is the pin, whichever ANNOUNCE came first")
            .isEqualTo(A_REAL);
        protocol.onMessage(A_OTHER, announceA());
        assertThat(protocol.addressConflictsReportedForTest()).as("the unanswered address is now a conflict with the pin").isEqualTo(1);
    }

    @Test
    void firstContact_noReplyToAnUnconfirmedSource() {
        protocol = manualProtocol();
        protocol.putMemberForTest(NODE_B, B_ADDR, MemberState.ALIVE);

        protocol.onMessage(A_OTHER, announceA());

        assertThat(transport.sentMessages.stream()
                            .filter(sent -> sent.target().equals(A_OTHER))
                            .filter(sent -> sent.message() instanceof Ack)
                            .toList())
            .as("no membership snapshot goes to a source that has not answered a probe")
            .isEmpty();
    }

    @Test
    void firstContact_isConfirmedByAMatchingAckOnly() {
        protocol = manualProtocol();
        protocol.onMessage(A_OTHER, announceA());
        var probe = lastPingTo(A_OTHER);

        // an ack for the right sequence, but from another source address, pins nothing
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, probe.sequence()));
        protocol.onMessage(A_MOVED, announceA());

        assertNoConflictRaised("no pin exists yet, so there is nothing to conflict with");
    }

    @Test
    void firstContact_ackNamingAnotherMember_pinsNothing() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var probe = lastPingTo(A_REAL);

        protocol.onMessage(A_REAL, Ack.ack(NODE_B, probe.sequence(), List.of()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("an ack from another member").isFalse();
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, probe.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("the right ack still pins: the bad one burned nothing").isTrue();
    }

    @Test
    void firstContact_twoUnconfirmedSources_firstToAnswerIsThePin_theOtherIsLaterRefused() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        protocol.onMessage(A_MOVED, announceA());
        var viaReal = lastPingTo(A_REAL);
        var viaMoved = lastPingTo(A_MOVED);

        assertNoConflictRaised("two unconfirmed sources are two candidates, not a conflict");
        protocol.onMessage(A_MOVED, provenAck(NODE_A, TOKEN_A, viaMoved.sequence()));
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, viaReal.sequence()));

        assertThat(protocol.addressPinForTest(NODE_A).map(ip -> ip.getHostAddress()).or("none"))
            .as("the first address to answer a probe keeps the pin")
            .isEqualTo("10.0.0.2");
        assertThat(protocol.addressConflictsReportedForTest()).as("the second answer is a conflict with it").isEqualTo(1);
    }

    @Test
    void firstContact_unresolvedSource_isIgnored_nothingIsSent() {
        protocol = manualProtocol();

        protocol.onMessage(InetSocketAddress.createUnresolved("a.invalid", 9001), announceA());

        assertThat(transport.sentMessages).as("an address with no IP cannot be pinned, so it is not even probed").isEmpty();
    }

    @Test
    void firstContact_staleSequenceAck_confirmsNothing_andEveryProbeHasItsOwnSequence() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var first = lastPingTo(A_REAL);
        protocol.sweepAddressCandidatesForTest(System.currentTimeMillis() + 11_000);
        protocol.onMessage(A_MOVED, announceA());
        var second = lastPingTo(A_MOVED);

        assertThat(second.sequence()).as("a sequence is never reused across candidates").isNotEqualTo(first.sequence());
        protocol.onMessage(A_MOVED, provenAck(NODE_A, TOKEN_A, first.sequence()));
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, first.sequence()));
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, second.sequence()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent())
            .as("an otherwise valid ack of an expired probe, or of another source's probe, confirms nothing")
            .isFalse();
    }

    @Test
    void firstContact_ackOfAnEarlierProcessLife_confirmsNothing_afterARestart() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var earlierLife = lastPingTo(A_REAL);
        protocol.stop();

        var restartedTransport = new RecordingTransport();
        protocol = wire(SwimProtocol.swimProtocol(manualConfig(), restartedTransport, listener, SELF_ID, SELF_ADDR, () -> false).unwrap());
        protocol.onMessage(A_REAL, announceA());
        var thisLife = restartedTransport.sentMessages.stream()
                                                      .filter(sent -> sent.message() instanceof Ping)
                                                      .map(sent -> (Ping) sent.message())
                                                      .findFirst()
                                                      .orElseThrow();

        assertThat(thisLife.sequence()).as("the restarted process does not draw the sequences of its earlier life")
                                       .isNotEqualTo(earlierLife.sequence());
        assertThat(thisLife.sequence()).isPositive();
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, earlierLife.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("an ack of the earlier life confirms nothing").isFalse();

        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, thisLife.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("control: the ack of this life's probe confirms").isTrue();
    }

    @Test
    void firstContact_isOneEmptyPing_notRepeatedWithinTheWindow() {
        protocol = manualProtocol();
        protocol.onMessage(B_ADDR,
                           new Ping(NODE_B, 1L, List.of(MembershipUpdate.membershipUpdate(NODE_C, MemberState.ALIVE, 1, C_ADDR))));

        protocol.onMessage(A_OTHER, announceA());
        protocol.onMessage(A_OTHER, announceA());
        protocol.onMessage(A_OTHER, announceA());

        var sent = transport.sentMessages.stream().filter(message -> message.target().equals(A_OTHER)).toList();

        assertThat(sent).hasSize(1);
        assertThat(((Ping) sent.getFirst().message()).piggyback()).as("never larger than the ANNOUNCE it answers").isEmpty();
    }

    @Test
    void firstContact_aCandidateThatNeverAnswered_expires() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var probe = lastPingTo(A_REAL);

        protocol.sweepAddressCandidatesForTest(System.currentTimeMillis() + 11_000);
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, probe.sequence()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("a late ack finds no candidate").isFalse();
    }

    @Test
    void firstContact_candidatesAreBounded_oldestIsDropped() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var first = lastPingTo(A_REAL);

        for (int i = 0; i < 4200; i++) {
            protocol.onMessage(new InetSocketAddress("172.16." + (i / 250) + "." + (i % 250 + 1), 9001), announceA());
        }
        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, first.sequence()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("the oldest candidate was evicted at the bound").isFalse();
    }

    @Test
    void firstContact_residentMemberTakesTheConfirmedAddress() {
        protocol = manualProtocol();
        protocol.putMemberForTest(NODE_A, new InetSocketAddress("10.9.9.9", 9001), MemberState.ALIVE);

        pinnedMemberA();

        assertThat(protocol.members().get(NODE_A).address().getAddress().getHostAddress())
            .as("a gossip- or seed-introduced member takes the address its pin was confirmed from")
            .isEqualTo("10.0.0.1");
    }

    @Test
    void pin_isDroppedWithTheMembersScope_andTheNextAddressIsAFirstContact() {
        protocol = tickingProtocol();
        pinnedMemberA();
        protocol.setMembershipEligibility(peer -> !peer.equals(NODE_A));
        await().atMost(Duration.ofSeconds(3)).until(() -> protocol.addressPinForTest(NODE_A).isEmpty());
        protocol.setMembershipEligibility(_ -> true);

        protocol.onMessage(A_MOVED, announceA());

        assertNoConflictRaised("with the pin gone there is nothing to conflict with");
    }

    // -- boot token: an unconfirmed token changes nothing --

    private static final long OLD_TOKEN = 1111L;

    @Test
    void oldTokenAnnounce_fromTheLiveMembersAddress_retiresNothing_untilItsProcessAnswers() {
        protocol = manualProtocol();
        pinnedMemberA();
        var refusalsBefore = protocol.bootTokenRefusals();
        transport.sentMessages.clear();

        protocol.onMessage(A_REAL, Announce.announce(infoOf(NODE_A, 9001), "", 1L, OLD_TOKEN));

        assertThat(protocol.bootTokenRefusals()).as("an unconfirmed token is not presented to the registry").isEqualTo(refusalsBefore);
        assertThat(protocol.members().get(NODE_A).state()).as("the live member is not declared dead").isNotEqualTo(MemberState.FAULTY);
        assertThat(transport.sentMessages.stream().anyMatch(sent -> sent.message() instanceof SwimMessage.IdentityRefused))
            .as("and nobody is told its identity is retired")
            .isFalse();
        var challenge = lastPingTo(A_REAL);

        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, challenge.sequence()));

        assertThat(protocol.bootTokenRefusals()).as("the live process answered with its own token").isEqualTo(refusalsBefore);
        assertThat(protocol.members().get(NODE_A).state()).isNotEqualTo(MemberState.FAULTY);
        assertThat(protocol.addressConflictsReportedForTest()).as("the mismatch is reported once").isEqualTo(1);
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> !warnings.isEmpty());
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.SWIM_MEMBER_IDENTITY_CONFLICT);
    }

    @Test
    void newProcessWithTheSameId_confirmedByItsOwnAck_isRefusedAsBefore() {
        protocol = manualProtocol();
        pinnedMemberA();
        transport.sentMessages.clear();

        protocol.onMessage(A_REAL, Announce.announce(infoOf(NODE_A, 9001), "", 2L, OLD_TOKEN));
        var challenge = lastPingTo(A_REAL);
        protocol.onMessage(A_REAL, provenAck(NODE_A, OLD_TOKEN, challenge.sequence()));

        assertThat(protocol.members().get(NODE_A).state()).as("the known process is treated as dead").isEqualTo(MemberState.FAULTY);
        assertThat(transport.sentMessages.stream().anyMatch(sent -> sent.target().equals(A_REAL) && sent.message() instanceof SwimMessage.IdentityRefused))
            .as("and the new process is told, as before")
            .isTrue();
        assertNoConflictRaised("a genuine new process is a refusal, not an alert");
    }

    @Test
    void unconfirmedFirstToken_doesNotPoisonTheRegistry() {
        protocol = manualProtocol();

        protocol.onMessage(A_OTHER, Announce.announce(infoOf(NODE_A, 9001), "", 1L, OLD_TOKEN));
        pinnedMemberA();

        assertThat(protocol.members()).as("the real process is admitted").containsKey(NODE_A);
        assertThat(protocol.members().get(NODE_A).state()).isNotEqualTo(MemberState.FAULTY);
        assertThat(protocol.bootTokenRefusals()).as("a token nobody proved never reached the registry").isZero();
    }

    @Test
    void firstContact_ackWithoutTheTokenProof_pinsNothing() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var probe = lastPingTo(A_REAL);

        protocol.onMessage(A_REAL, Ack.ack(NODE_A, probe.sequence(), List.of()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("the ack must carry the announced token").isFalse();
    }

    @Test
    void ack_alwaysCarriesThisProcessesIdentityFirst() {
        protocol = manualProtocol();
        protocol.announceJoin(infoOf(SELF_ID, 9000), "", 1L, 555L, List.of());

        protocol.onMessage(B_ADDR, new Ping(NODE_B, 1L, List.of()));

        var ack = transport.sentMessages.stream()
                           .filter(sent -> sent.target().equals(B_ADDR) && sent.message() instanceof Ack)
                           .map(sent -> (Ack) sent.message())
                           .findFirst()
                           .orElseThrow();

        assertThat(ack.piggyback()).isNotEmpty();
        assertThat(ack.piggyback().getFirst().nodeId()).isEqualTo(SELF_ID);
        assertThat(ack.piggyback().getFirst().bootToken()).as("the token a peer can confirm an ANNOUNCE against").isEqualTo(555L);
    }

    @Test
    void ack_carriesThisProcessesIdentityOnce_evenWhenTheBufferedGossipHasIt() {
        protocol = tickingProtocol();
        protocol.announceJoin(infoOf(SELF_ID, 9000), "", 1L, 555L, List.of());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            transport.sentMessages.clear();
            protocol.onMessage(B_ADDR, new Ping(NODE_B, 9L, List.of()));
            var own = transport.sentMessages.stream()
                               .filter(sent -> sent.target().equals(B_ADDR) && sent.message() instanceof Ack)
                               .flatMap(sent -> ((Ack) sent.message()).piggyback().stream())
                               .filter(update -> update.nodeId().equals(SELF_ID))
                               .toList();

            assertThat(own).as("once, never twice").hasSize(1);
            assertThat(own.getFirst().bootToken()).isEqualTo(555L);
        });
    }

    @Test
    void ack_keepsTheIdentity_andFitsTheDatagramBudget_whenGossipFillsIt() {
        protocol = manualProtocol();
        protocol.announceJoin(infoOf(SELF_ID, 9000), "", 1L, 555L, List.of());
        var gossip = new java.util.ArrayList<MembershipUpdate>();

        for (int i = 0; i < 8; i++) {
            gossip.add(MembershipUpdate.membershipUpdate(new NodeId("member-" + "x".repeat(118) + i),
                                                         MemberState.ALIVE,
                                                         1L,
                                                         new InetSocketAddress("10.1.0." + (i + 1), 9100)));
        }
        protocol.onMessage(B_ADDR, new Ping(NODE_B, 1L, gossip));
        transport.sentMessages.clear();

        protocol.onMessage(B_ADDR, new Ping(NODE_B, 2L, List.of()));

        var ack = transport.sentMessages.stream()
                           .filter(sent -> sent.target().equals(B_ADDR) && sent.message() instanceof Ack)
                           .map(sent -> (Ack) sent.message())
                           .findFirst()
                           .orElseThrow();

        assertThat(ack.piggyback().size()).as("gossip really fills the datagram").isGreaterThan(1);
        assertThat(ack.piggyback().getFirst().nodeId()).as("the identity is never the update that is left out").isEqualTo(SELF_ID);
        assertThat(ack.piggyback().stream().mapToInt(PiggybackBuffer::estimatedBytes).sum())
            .isLessThanOrEqualTo(PiggybackBuffer.piggybackBudgetFor(SELF_ID));
    }

    @Test
    void ack_keepsTheIdentity_evenWhenItAloneExceedsTheBudget() {
        var longId = new NodeId("s".repeat(700));

        protocol = wire(SwimProtocol.swimProtocol(manualConfig(), transport, listener, longId, SELF_ADDR, () -> false).unwrap());
        protocol.announceJoin(infoOf(longId, 9000), "", 1L, 555L, List.of());

        protocol.onMessage(B_ADDR, new Ping(NODE_B, 2L, List.of()));

        var ack = transport.sentMessages.stream()
                           .filter(sent -> sent.target().equals(B_ADDR) && sent.message() instanceof Ack)
                           .map(sent -> (Ack) sent.message())
                           .findFirst()
                           .orElseThrow();

        assertThat(ack.piggyback()).as("the one update that is never dropped").hasSize(1);
        assertThat(ack.piggyback().getFirst().nodeId()).isEqualTo(longId);
    }

    /// The binding of an ack to the outstanding probe, one dimension at a time: the sequence, the expected member and
    /// the probed address must ALL match; each wrong one alone confirms nothing, and none of them burns the probe.
    @Test
    void ackConfirmsOnlyWhenSequenceMemberAndProbedAddressAllMatch() {
        protocol = manualProtocol();
        protocol.onMessage(A_REAL, announceA());
        var probe = lastPingTo(A_REAL);

        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, probe.sequence() + 1));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("wrong sequence").isFalse();
        protocol.onMessage(A_REAL, provenAck(NODE_B, TOKEN_A, probe.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("wrong member").isFalse();
        protocol.onMessage(A_MOVED, provenAck(NODE_A, TOKEN_A, probe.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("wrong source address").isFalse();

        protocol.onMessage(A_REAL, provenAck(NODE_A, TOKEN_A, probe.sequence()));
        assertThat(protocol.addressPinForTest(NODE_A).isPresent()).as("control: all three match").isTrue();
    }

    // -- helpers --

    private static Ack provenAck(NodeId member, long token, long sequence) {
        return Ack.ack(member,
                       sequence,
                       List.of(MembershipUpdate.membershipUpdate(member, MemberState.ALIVE, 1L, new InetSocketAddress("10.0.0.1", 9001), token)));
    }

    private void assertNoConflictRaised(String because) {
        assertThat(protocol.addressConflictsReportedForTest()).as(because).isZero();
        assertThat(warnings.stream()
                           .filter(warning -> warning.code() == OperatorWarningCode.SWIM_MEMBER_ADDRESS_CONFLICT
                                              || warning.code() == OperatorWarningCode.SWIM_MEMBER_IDENTITY_CONFLICT))
            .as(because)
            .isEmpty();
    }

    private SwimProtocol manualProtocol() {
        return wire(SwimProtocol.swimProtocol(manualConfig(), transport, listener, SELF_ID, SELF_ADDR, () -> false).unwrap());
    }

    private static SwimConfig manualConfig() {
        return SwimConfig.swimConfig(timeSpan(1).hours(), timeSpan(1).hours(), 3, timeSpan(1).hours(), 8, timeSpan(1).hours(), "", 0);
    }

    private SwimProtocol tickingProtocol() {
        var config = SwimConfig.swimConfig(timeSpan(20).millis(),
                                           timeSpan(20).millis(),
                                           3,
                                           timeSpan(100).millis(),
                                           8,
                                           timeSpan(20).millis()).withJoinGrace(timeSpan(40).millis());
        var ticking = wire(SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false).unwrap());

        ticking.start();

        return ticking;
    }

    private SwimProtocol wire(SwimProtocol created) {
        created.addObservationListener(observations);
        created.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));

        return created;
    }

    private static NodeInfo infoOf(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, new NodeAddress("10.0.0.1", port), Map.of());
    }

    private static Announce announceA() {
        return Announce.announce(infoOf(NODE_A, 9001), "", 1L, TOKEN_A);
    }

    /// A announces from its own address and answers the probe sent to it: the pin is now effective.
    private void pinnedMemberA() {
        protocol.onMessage(A_REAL, announceA());
        confirmPin(A_REAL, NODE_A, TOKEN_A);
    }

    /// Answers a probe sent to `source` the way the member would. A no-op when no probe was sent.
    private void confirmPin(InetSocketAddress source, NodeId member, long token) {
        transport.sentMessages.stream()
                 .filter(sent -> sent.target().equals(source) && sent.message() instanceof Ping)
                 .map(sent -> (Ping) sent.message())
                 .toList()
                 .forEach(ping -> protocol.onMessage(source, provenAck(member, token, ping.sequence())));
    }

    private Ping lastPingTo(InetSocketAddress target) {
        return transport.sentMessages.stream()
                        .filter(sent -> sent.target().equals(target) && sent.message() instanceof Ping)
                        .map(sent -> (Ping) sent.message())
                        .reduce((_, last) -> last)
                        .orElseThrow(() -> new AssertionError("no probe was sent to " + target));
    }

    private void driveToTombstone() {
        var aliveA = MembershipUpdate.membershipUpdate(NODE_A, MemberState.ALIVE, 1, A_REAL);

        protocol.onMessage(B_ADDR, new Ping(NODE_B, 1L, List.of(aliveA)));
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();
        protocol.recordTransportHint(NODE_A,
                                     new TransportObservation.PeerUnreachable(NODE_A,
                                                                              Causes.cause("test peer down"),
                                                                              TransportObservation.HintOrigin.LINK_LOST));
        protocol.onMessage(B_ADDR,
                           new Ping(NODE_B, 2L, List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 2, A_REAL))));
        await().atMost(Duration.ofSeconds(3))
               .until(() -> protocol.tombstonedForTest(NODE_A) && !protocol.members().containsKey(NODE_A));
    }
}
