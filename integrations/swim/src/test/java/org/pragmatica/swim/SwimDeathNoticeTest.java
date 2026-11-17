/*
 *  Copyright (c) 2026 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.IdentityRefused;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #3028 PR C, design §3.2-§3.3: the death notice `Faulty(X, record.incarnation, record.bootToken)` a holder of a death
/// record sends back to a stale aliveness claim of the recorded process itself, its triggers (the pinger's own claim in
/// a Ping, Announce; never a third-party entry or an Ack) and its position in the reply. The last test closes the
/// loop of package 1: a falsely-dead same-token process receives the notice, refutes it and is admitted again.
class SwimDeathNoticeTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9000));
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
    private static final long SELF_TOKEN = 0x5E1FL;
    private static final long TOKEN_A = 0x3028AL;
    private static final long TOKEN_B = 0x3028CL;
    private static final long TOKEN_C = 0x3028DL;
    private static final long OTHER_TOKEN = 0x3028BL;

    private RecordingTransport transport;
    private SwimProtocol protocol;
    private SwimProtocol claimant;

    private static SwimConfig config(int maxPiggyback) {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          maxPiggyback,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }

    private void createHolder(int maxPiggyback) {
        transport = new RecordingTransport();
        protocol = SwimProtocol.swimProtocol(config(maxPiggyback),
                                             transport,
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR,
                                             () -> false)
                               .unwrap();
        protocol.setBootTokens(BootTokens.bootTokens(SELF_TOKEN));
    }

    @BeforeEach
    void setUp() {
        createHolder(8);
    }

    @AfterEach
    void tearDown() {
        protocol.stop();
        if (claimant != null) {
            claimant.stop();
        }
    }

    private static MembershipUpdate updateOf(NodeId id, MemberState state, long incarnation, long token) {
        return MembershipUpdate.membershipUpdate(id, state, incarnation, ADDR_A, token);
    }

    private void ping(NodeId from, long sequence, List<MembershipUpdate> piggyback) {
        protocol.onMessage(ADDR_A, new Ping(from, sequence, piggyback));
    }

    private static NodeId longId(char fill, int length) {
        return new NodeId(String.valueOf(fill).repeat(length));
    }

    /// NODE_A ever-HEALTHY with TOKEN_A, then FAULTY at incarnation 1 (transport-down hint co-confirms): the record
    /// `(1, TOKEN_A)` is written at the edge. Not started: NODE_A stays resident FAULTY.
    private void driveToFaultyResident() {
        ping(NODE_B, 1L, List.of(updateOf(NODE_A, MemberState.ALIVE, 0, TOKEN_A)));
        protocol.recordTransportHint(NODE_A,
                                     new TransportObservation.PeerUnreachable(NODE_A,
                                                                              Causes.cause("test peer down"),
                                                                              TransportObservation.HintOrigin.LINK_LOST));
        ping(NODE_B, 2L, List.of(updateOf(NODE_A, MemberState.FAULTY, 1L, TOKEN_A)));

        assertThat(protocol.deathRecordForTest(NODE_A).isPresent()).as("precondition: record written").isTrue();
    }

    private Ack ackOf(RecordingTransport recorder, long sequence) {
        return recorder.sentMessages.stream()
                                    .filter(Ack.class::isInstance)
                                    .map(Ack.class::cast)
                                    .filter(ack -> ack.sequence() == sequence)
                                    .reduce((first, last) -> last)
                                    .orElseThrow();
    }

    private static Predicate<MembershipUpdate> noticeOf(NodeId id, long incarnation, long token) {
        return update -> update.nodeId().equals(id) && update.state() == MemberState.FAULTY
                         && update.incarnation() == incarnation && update.bootToken() == token;
    }

    private static boolean carriesFaultyOf(Ack ack, NodeId id) {
        return ack.piggyback()
                  .stream()
                  .anyMatch(update -> update.nodeId().equals(id) && update.state() == MemberState.FAULTY);
    }

    private boolean anySyncAckCarries(Predicate<MembershipUpdate> entry) {
        return transport.sentMessages.stream()
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .filter(ack -> ack.sequence() == 0L)
                                     .anyMatch(ack -> ack.piggyback().stream().anyMatch(entry));
    }

    // ---- Ping: the sender's self-Alive is the claim ----

    @Test
    void deathNotice_pingSelfAliveClaim_ackCarriesNotice() {
        driveToFaultyResident();

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));

        assertThat(ackOf(transport, 7L).piggyback()).as("Faulty(X, record.incarnation, record.token) answers X's stale self-Alive")
                                                    .anyMatch(noticeOf(NODE_A, 1L, TOKEN_A));
    }

    @Test
    void deathNotice_claimBelowTheRecord_noticeCarriesTheRecordIncarnation() {
        protocol.putDeathRecordForTest(NODE_A, 3L, TOKEN_A, System.currentTimeMillis());

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));

        assertThat(ackOf(transport, 7L).piggyback()).as("the notice states the record, not the claim")
                                                    .anyMatch(noticeOf(NODE_A, 3L, TOKEN_A));
    }

    /// The reachable shape of the comparison guard: the resident FAULTY member sits ABOVE the record (Faulty(A,3) gossip
    /// raises it; the record is written once, at the FAULTY edge), so a claim above the record but below the resident
    /// returns early in the member update, never meets the death-record gate, and only the notice's own incarnation
    /// comparison stops an answer.
    @Test
    void deathNotice_pingClaimAboveTheRecordBelowTheResident_noNotice() {
        driveToFaultyResident();
        ping(NODE_B, 5L, List.of(updateOf(NODE_A, MemberState.FAULTY, 3L, TOKEN_A)));

        assertThat(protocol.members().get(NODE_A).incarnation()).as("precondition: the resident is above the record").isEqualTo(3L);
        assertThat(protocol.deathRecordForTest(NODE_A).map(record -> record[0]).or(-1L)).as("precondition: the record stays at its edge incarnation").isEqualTo(1L);

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 2L, TOKEN_A)));

        assertThat(protocol.deathRecordForTest(NODE_A).isPresent()).as("the claim did not clear the record").isTrue();
        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("a claim above the record earns no notice").isFalse();
    }

    @Test
    void deathNotice_unknownTokenGossip_noNotice() {
        driveToFaultyResident();

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, 0L)));

        assertThat(protocol.deathRecordForTest(NODE_A).isPresent()).as("the token-0 claim is refused, the record kept")
                                                                   .isTrue();
        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("a token-0 claim is not the recorded process speaking")
                                                                 .isFalse();
    }

    @Test
    void deathNotice_suspectClaim_isNotAnAlivenessClaim_noNotice() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        ping(NODE_B, 7L, List.of(updateOf(NODE_A, MemberState.SUSPECT, 1L, TOKEN_A)));

        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).isFalse();
    }

    // ---- Relay (D3): the relayer appends the requester's identity; the target answers it ----

    @Test
    void deathNotice_pingReqRelay_appendsRequesterIdentity() {
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);

        protocol.onMessage(ADDR_A, new PingReq(NODE_A, NODE_B, 9L));

        var relayed = transport.sentMessages.stream()
                                            .filter(Ping.class::isInstance)
                                            .map(Ping.class::cast)
                                            .reduce((first, last) -> last)
                                            .orElseThrow();

        assertThat(relayed.piggyback()).as("the relayed Ping carries the requester as the relayer holds it")
                                       .anyMatch(update -> update.nodeId().equals(NODE_A) && update.state() == MemberState.ALIVE);
    }

    /// CTO ruling (2026-11-11): only the pinger's own self-`Alive` is the claim. A third-party `Alive` in a Ping,
    /// including the requester entry a relayer appends (D3), is gossip the gate already refuses and earns no notice.
    @Test
    void deathNotice_thirdPartyAliveInPing_noNotice_pingersOwnClaimDoes() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());
        protocol.putDeathRecordForTest(NODE_B, 1L, TOKEN_B, System.currentTimeMillis());

        ping(NODE_B, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));
        ping(NODE_B, 8L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A),
                                 updateOf(NODE_B, MemberState.ALIVE, 1L, TOKEN_B)));

        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("a third-party Alive earns no notice")
                                                                 .isFalse();
        assertThat(protocol.deathRecordForTest(NODE_A).isPresent()).as("the third-party claim is refused, the record kept")
                                                                   .isTrue();
        assertThat(ackOf(transport, 8L).piggyback()).as("the pinger's own claim earns the notice, the third-party one none")
                                                    .anyMatch(noticeOf(NODE_B, 1L, TOKEN_B))
                                                    .noneMatch(noticeOf(NODE_A, 1L, TOKEN_A));
    }

    /// CTO ruling (2026-11-11, wire marker): a relayed Ping names the requester in `onBehalfOf`; the entry for that
    /// id is answered like the pinger's own claim, every other third-party entry still earns none.
    @Test
    void deathNotice_relayedPing_onBehalfOfRequester_earnsNoticeForTheRequesterOnly() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());
        protocol.putDeathRecordForTest(NODE_C, 1L, TOKEN_C, System.currentTimeMillis());

        protocol.onMessage(ADDR_B,
                           Ping.relayedPing(NODE_B,
                                            7L,
                                            List.of(updateOf(NODE_C, MemberState.ALIVE, 1L, TOKEN_C),
                                                    updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)),
                                            NODE_A));

        assertThat(ackOf(transport, 7L).piggyback()).as("the named requester's claim earns the notice")
                                                    .anyMatch(noticeOf(NODE_A, 1L, TOKEN_A))
                                                    .as("a third-party entry other than the requester earns none")
                                                    .noneMatch(noticeOf(NODE_C, 1L, TOKEN_C));
    }

    @Test
    void deathNotice_relayedPing_onBehalfOfNamesOther_noNoticeForTheEntryHeld() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        protocol.onMessage(ADDR_B,
                           Ping.relayedPing(NODE_B, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)), NODE_C));

        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("onBehalfOf names C, the entry for A is gossip")
                                                                 .isFalse();
    }

    @Test
    void deathNotice_pingReqRelay_marksTheRequesterOnTheRelayedPing_directPingsCarryNone() {
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);

        protocol.onMessage(ADDR_A, new PingReq(NODE_A, NODE_B, 9L));

        var relayed = transport.sentMessages.stream()
                                            .filter(Ping.class::isInstance)
                                            .map(Ping.class::cast)
                                            .reduce((first, last) -> last)
                                            .orElseThrow();

        assertThat(relayed.onBehalfOf().isPresent()).isTrue();
        assertThat(relayed.onBehalfOf().or(NODE_B)).as("names the PingReq requester").isEqualTo(NODE_A);

        transport.sentMessages.clear();
        protocol.probeRoundForTest();

        var probes = transport.sentMessages.stream().filter(Ping.class::isInstance).map(Ping.class::cast).toList();

        assertThat(probes).as("precondition: the protocol sent a probe Ping").isNotEmpty();
        assertThat(probes).as("a Ping the protocol sends directly names no requester")
                          .allMatch(probe -> probe.onBehalfOf().isEmpty());
    }

    /// Mutant m29 (notice for any claim state): the relayed requester entry is answered like the pinger's own claim,
    /// but only an `Alive` claim earns a notice. The relayer's snapshot of the requester may be `Suspect`.
    @Test
    void deathNotice_relayedPing_requesterEntryIsSuspect_noNotice_aliveControlEarnsOne() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        protocol.onMessage(ADDR_B,
                           Ping.relayedPing(NODE_B, 7L, List.of(updateOf(NODE_A, MemberState.SUSPECT, 1L, TOKEN_A)), NODE_A));
        protocol.onMessage(ADDR_B,
                           Ping.relayedPing(NODE_B, 8L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)), NODE_A));

        assertThat(carriesFaultyOf(ackOf(transport, 8L), NODE_A)).as("control: the Alive claim of the same requester earns the notice")
                                                                 .isTrue();
        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("a Suspect claim is not an aliveness claim: no Faulty notice")
                                                                 .isFalse();
    }

    /// Mutant m40 (floor 1): when the requester entry does not fit beside the buffered gossip, the gossip is trimmed
    /// down to nothing (floor 0) and the requester entry is still sent.
    @Test
    void deathNotice_pingReqRelay_overBudget_gossipTrimmedToZero_requesterEntryKept() {
        var overlong = longId('z', 1250);

        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);
        ping(NODE_B, 1L, List.of(updateOf(overlong, MemberState.ALIVE, 1L, 0L)));
        transport.sentMessages.clear();

        protocol.onMessage(ADDR_A, new PingReq(NODE_A, NODE_B, 9L));

        var relayed = transport.sentMessages.stream()
                                            .filter(Ping.class::isInstance)
                                            .map(Ping.class::cast)
                                            .reduce((first, last) -> last)
                                            .orElseThrow();

        assertThat(relayed.piggyback()).as("no gossip entry survives beside the requester entry")
                                       .singleElement()
                                       .matches(update -> update.nodeId().equals(NODE_A));
    }

    @Test
    void deathNotice_directPing_emptyOnBehalfOf_unchanged() {
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));

        assertThat(carriesFaultyOf(ackOf(transport, 7L), NODE_A)).as("the pinger's own claim, no marker").isTrue();
    }

    // ---- Announce (D1c): the refused ANNOUNCE still gets the snapshot, with the notice ----

    @Test
    void deathRecord_announceRefused_snapshotCarriesNotice() {
        driveToFaultyResident();
        protocol.start();
        await().atMost(Duration.ofSeconds(4))
               .until(() -> !protocol.members().containsKey(NODE_A) && !protocol.tombstonedForTest(NODE_A));
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);

        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", 1L, TOKEN_A));

        assertThat(protocol.members()).as("refused by the record").doesNotContainKey(NODE_A);
        assertThat(anySyncAckCarries(noticeOf(NODE_A, 1L, TOKEN_A))).as("the snapshot reply carries the notice").isTrue();
        assertThat(transport.sentMessages).as("never via refuseAnnounce: IdentityRefused makes the receiver exit")
                                          .noneMatch(IdentityRefused.class::isInstance);
    }

    @Test
    void deathNotice_announceToAnEmptyView_noticeInTheStopAck() {
        driveToFaultyResident();

        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", 1L, TOKEN_A));

        assertThat(anySyncAckCarries(noticeOf(NODE_A, 1L, TOKEN_A))).isTrue();
    }

    @Test
    void deathNotice_announceAboveTheRecord_noNotice() {
        driveToFaultyResident();

        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", 2L, TOKEN_A));

        assertThat(anySyncAckCarries(update -> update.nodeId().equals(NODE_A) && update.state() == MemberState.FAULTY))
            .as("an ANNOUNCE above the record is a refutation, no notice")
            .isFalse();
    }

    // ---- Position in the reply (§3.3) ----

    @Test
    void deathNotice_maxPiggybackOne_identityAndNoticeBothSent() {
        protocol.stop();
        createHolder(1);
        protocol.announceJoin(INFO_SELF, "", 1L, SELF_TOKEN, List.of());
        ping(NODE_B, 1L, List.of(updateOf(NODE_B, MemberState.ALIVE, 1L, TOKEN_B)));
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));

        var piggyback = ackOf(transport, 7L).piggyback();

        assertThat(piggyback).hasSize(2);
        assertThat(piggyback.getFirst().nodeId()).as("identity leads").isEqualTo(SELF_ID);
        assertThat(piggyback.getLast()).as("the notice is one entry beyond maxPiggyback")
                                       .matches(noticeOf(NODE_A, 1L, TOKEN_A));
    }

    /// Two gossip entries of 600 estimated bytes fill the budget with the identity (control: both sent). With a
    /// notice the second no longer fits: trailing gossip is trimmed, never the notice or the identity.
    @Test
    void deathNotice_shortBudget_gossipTrimmedNotNotice() {
        protocol.announceJoin(INFO_SELF, "", 1L, SELF_TOKEN, List.of());
        var first = longId('x', 543);
        var second = longId('y', 543);

        ping(NODE_B, 1L, List.of(updateOf(first, MemberState.ALIVE, 1L, 0L), updateOf(second, MemberState.ALIVE, 1L, 0L)));
        protocol.putDeathRecordForTest(NODE_A, 1L, TOKEN_A, System.currentTimeMillis());

        ping(NODE_B, 6L, List.of());
        ping(NODE_A, 7L, List.of(updateOf(NODE_A, MemberState.ALIVE, 1L, TOKEN_A)));

        var control = ackOf(transport, 6L).piggyback();
        var withNotice = ackOf(transport, 7L).piggyback();
        var budget = PiggybackBuffer.piggybackBudgetFor(SELF_ID);

        assertThat(control).as("control: identity and both long entries fit").hasSize(3);
        assertThat(withNotice).hasSize(3);
        assertThat(withNotice.getFirst().nodeId()).isEqualTo(SELF_ID);
        assertThat(withNotice.getLast()).matches(noticeOf(NODE_A, 1L, TOKEN_A));
        assertThat(withNotice.stream().mapToInt(PiggybackBuffer::estimatedBytes).sum()).isLessThanOrEqualTo(budget);
    }

    @Test
    void deathNotice_overBudgetNotice_identityNeverEvicted() {
        protocol.announceJoin(INFO_SELF, "", 1L, SELF_TOKEN, List.of());
        var overlong = longId('z', 1250);

        protocol.putDeathRecordForTest(overlong, 1L, TOKEN_A, System.currentTimeMillis());
        ping(overlong, 7L, List.of(updateOf(overlong, MemberState.ALIVE, 1L, TOKEN_A)));

        var piggyback = ackOf(transport, 7L).piggyback();

        assertThat(piggyback).as("floor: identity plus notice").hasSize(2);
        assertThat(piggyback.getFirst().nodeId()).isEqualTo(SELF_ID);
        assertThat(piggyback.getLast()).matches(noticeOf(overlong, 1L, TOKEN_A));
    }

    /// Without an identity (no incarnation yet) the floor is zero: gossip yields to the notice.
    @Test
    void deathNotice_overBudgetNotice_noIdentity_gossipYields() {
        var overlong = longId('z', 1250);

        ping(NODE_B, 1L, List.of(updateOf(NODE_B, MemberState.ALIVE, 1L, TOKEN_B)));
        protocol.putDeathRecordForTest(overlong, 1L, TOKEN_A, System.currentTimeMillis());
        ping(overlong, 7L, List.of(updateOf(overlong, MemberState.ALIVE, 1L, TOKEN_A)));

        assertThat(ackOf(transport, 1L).piggyback()).as("control: the gossip entry is sent without a notice")
                                                         .anyMatch(update -> update.nodeId().equals(NODE_B));
        assertThat(ackOf(transport, 7L).piggyback()).as("no identity to keep: only the notice fits")
                                                    .singleElement()
                                                    .matches(noticeOf(overlong, 1L, TOKEN_A));
    }

    // ---- The loop closes: the falsely-dead same-token process rejoins (package 1 residual resolved) ----

    /// The holder declared NODE_A dead at incarnation 1 and its tombstone has expired, so only the record refuses.
    /// NODE_A's real protocol (same token, incarnation 1) pings with its self-Alive; the holder refuses it and acks
    /// with the notice; NODE_A refutes to incarnation 2, and its next self-Alive is admitted.
    @Test
    void deathNotice_falselyDeadSameTokenProcess_refutesAndRejoins() {
        driveToFaultyResident();
        protocol.start();
        await().atMost(Duration.ofSeconds(4))
               .until(() -> !protocol.members().containsKey(NODE_A) && !protocol.tombstonedForTest(NODE_A));
        var claimantTransport = new RecordingTransport();

        claimant = SwimProtocol.swimProtocol(config(8),
                                             claimantTransport,
                                             new RecordingListener(),
                                             NODE_A,
                                             ADDR_A,
                                             () -> false)
                               .unwrap();
        claimant.announceJoin(INFO_A, "", 1L, TOKEN_A, List.of());

        claimant.onMessage(SELF_ADDR, new Ping(SELF_ID, 11L, List.of()));
        ping(NODE_A, 21L, ackOf(claimantTransport, 11L).piggyback());

        assertThat(protocol.members()).as("the stale self-Alive is refused").doesNotContainKey(NODE_A);
        assertThat(ackOf(transport, 21L).piggyback()).anyMatch(noticeOf(NODE_A, 1L, TOKEN_A));

        claimant.onMessage(SELF_ADDR, ackOf(transport, 21L));
        claimant.onMessage(SELF_ADDR, new Ping(SELF_ID, 12L, List.of()));
        ping(NODE_A, 22L, ackOf(claimantTransport, 12L).piggyback());

        assertThat(protocol.members()).as("the refuted claim at incarnation 2 is admitted").containsKey(NODE_A);
        assertThat(protocol.members().get(NODE_A).incarnation()).isEqualTo(2L);
        assertThat(protocol.deathRecordForTest(NODE_A).isPresent()).as("the refutation cleared the record").isFalse();
    }
}
