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
        confirmPin(newSource, restarted);

        assertNoConflictRaised("a restart (a new process identity) is not a conflict");
        assertThat(protocol.members()).containsKey(restarted);
        protocol.onMessage(newSource, Announce.announce(infoOf(restarted, 9001), "", 1L, 777L));
        assertNoConflictRaised("a re-announce from the new pin");
    }

    @Test
    void sameIdWithANewBootToken_isRefusedByTheBootTokenGate_notReportedAsAnAddressConflict() {
        protocol = manualProtocol();
        pinnedMemberA();

        protocol.onMessage(A_MOVED, Announce.announce(infoOf(NODE_A, 9001), "", 1L, TOKEN_A + 1));

        assertNoConflictRaised("the boot-token layer owns this refusal");
        assertThat(protocol.addressPinForTest(NODE_A).map(ip -> ip.getHostAddress()).or("none"))
            .as("a new token for a known id is refused before the pin is consulted, so no re-pin path exists")
            .isEqualTo("10.0.0.1");
        assertThat(protocol.members().get(NODE_A).address()).isNotEqualTo(A_MOVED);
    }

    // -- first contact --

    @Test
    void firstContact_otherSourceArrivingFirst_doesNotBecomeThePin() {
        protocol = manualProtocol();

        // an announce from an address that never answers arrives first
        protocol.onMessage(A_OTHER, announceA());
        // the member's own ANNOUNCE then arrives, and its probe is answered
        protocol.onMessage(A_REAL, announceA());
        confirmPin(A_REAL, NODE_A);

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
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, probe.sequence(), List.of()));
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
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, probe.sequence(), List.of()));
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
        protocol.onMessage(A_MOVED, Ack.ack(NODE_A, viaMoved.sequence(), List.of()));
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, viaReal.sequence(), List.of()));

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
        protocol.onMessage(A_MOVED, Ack.ack(NODE_A, first.sequence(), List.of()));
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, first.sequence(), List.of()));
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, second.sequence(), List.of()));

        assertThat(protocol.addressPinForTest(NODE_A).isPresent())
            .as("an otherwise valid ack of an expired probe, or of another source's probe, confirms nothing")
            .isFalse();
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
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, probe.sequence(), List.of()));

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
        protocol.onMessage(A_REAL, Ack.ack(NODE_A, first.sequence(), List.of()));

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

    // -- helpers --

    private void assertNoConflictRaised(String because) {
        assertThat(protocol.addressConflictsReportedForTest()).as(because).isZero();
        assertThat(warnings).as(because).isEmpty();
    }

    private SwimProtocol manualProtocol() {
        var config = SwimConfig.swimConfig(timeSpan(1).hours(), timeSpan(1).hours(), 3, timeSpan(1).hours(), 8, timeSpan(1).hours(), "", 0);

        return wire(SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR, () -> false).unwrap());
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
        confirmPin(A_REAL, NODE_A);
    }

    /// Answers a probe sent to `source` the way the member would. A no-op when no probe was sent.
    private void confirmPin(InetSocketAddress source, NodeId member) {
        transport.sentMessages.stream()
                 .filter(sent -> sent.target().equals(source) && sent.message() instanceof Ping)
                 .map(sent -> (Ping) sent.message())
                 .toList()
                 .forEach(ping -> protocol.onMessage(source, Ack.ack(member, ping.sequence(), List.of())));
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
