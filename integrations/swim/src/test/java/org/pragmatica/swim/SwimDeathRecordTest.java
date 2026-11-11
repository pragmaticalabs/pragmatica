/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #3028 design §3.1: the death record, written at the FAULTY edge with the boot token snapshotted there, and its
/// admission gate. A stale aliveness claim (incarnation at or below the record's, token equal to the record's or
/// unknown) is refused whatever the tombstone's age and whoever relays it; a higher incarnation, a different process,
/// or a retirement clears the record. A death whose token was unknown at the edge gates exactly as the tombstone does.
class SwimDeathRecordTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
    private static final long TOKEN_A = 0x3028AL;
    private static final long OTHER_TOKEN = 0x3028BL;
    private static final long DEATH_INCARNATION = 1L;

    private BootTokens tokens;
    private RecordingObservationSink observations;
    private SwimProtocol protocol;

    /// Same timings as `SwimProtocolTombstoneTest`: suspectTimeout 100 ms, so the residency sweep runs at ~300 ms and
    /// the tombstone TTL is 1000 ms. NORMAL phase, join-grace 0.
    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }

    @BeforeEach
    void setUp() {
        tokens = BootTokens.bootTokens(0x5E1FL);
        observations = new RecordingObservationSink();
        protocol = SwimProtocol.swimProtocol(tightConfig(),
                                             new RecordingTransport(),
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR,
                                             () -> false)
                               .unwrap();
        protocol.addObservationListener(observations);
        protocol.setBootTokens(tokens);
    }

    @AfterEach
    void tearDown() {
        protocol.stop();
    }

    private void gossip(long sequence, MembershipUpdate update) {
        protocol.onMessage(ADDR_B, new Ping(NODE_B, sequence, List.of(update)));
    }

    private static MembershipUpdate updateOfA(MemberState state, long incarnation, long token) {
        return MembershipUpdate.membershipUpdate(NODE_A, state, incarnation, ADDR_A, token);
    }

    /// NODE_A is ever-HEALTHY with `token` (0 = never learned), then FAULTY at [#DEATH_INCARNATION] by gossip with
    /// a local transport-down hint (death-path co-confirmation). The protocol is not started: no sweep, NODE_A
    /// stays resident FAULTY.
    private void driveToFaultyResident(long token) {
        gossip(1L, updateOfA(MemberState.ALIVE, 0, token));
        protocol.recordTransportHint(NODE_A,
                                     new TransportObservation.PeerUnreachable(NODE_A,
                                                                              Causes.cause("test peer down"),
                                                                              TransportObservation.HintOrigin.LINK_LOST));
        gossip(2L, updateOfA(MemberState.FAULTY, DEATH_INCARNATION, token));

        assertThat(protocol.members().get(NODE_A).state()).as("precondition: NODE_A FAULTY-resident")
                                                           .isEqualTo(MemberState.FAULTY);
    }

    /// As [#driveToFaultyResident], then started: NODE_A is swept, and the test waits until its tombstone has
    /// expired, so only the death record can still refuse.
    private void driveToDeathPastTombstoneTtl(long token) {
        driveToFaultyResident(token);
        protocol.start();

        await().atMost(Duration.ofSeconds(4))
               .until(() -> !protocol.members().containsKey(NODE_A) && !protocol.tombstonedForTest(NODE_A));
    }

    private boolean hasRecord() {
        return protocol.deathRecordForTest(NODE_A).isPresent();
    }

    // ---- write at the FAULTY edge (M3: token snapshotted there) ----

    @Test
    void deathRecord_writtenAtFaultyEdge_withFaultyIncarnationAndKnownToken() {
        driveToFaultyResident(TOKEN_A);

        var record = protocol.deathRecordForTest(NODE_A).unwrap();

        assertThat(record[0]).as("the FAULTY incarnation").isEqualTo(DEATH_INCARNATION);
        assertThat(record[1]).as("the token held at the edge").isEqualTo(TOKEN_A);
    }

    // ---- the gate outlives the tombstone (RED at base) ----

    @Test
    void deathRecord_sameTokenStaleAlive_refusedAfterTombstoneTtl() {
        driveToDeathPastTombstoneTtl(TOKEN_A);

        gossip(3L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, TOKEN_A));

        assertThat(protocol.members()).as("a same-process claim at the death incarnation is stale whatever the tombstone's age")
                                      .doesNotContainKey(NODE_A);
        assertThat(hasRecord()).as("a refused claim leaves the record").isTrue();
    }

    @Test
    void deathRecord_unknownTokenRelayedAlive_refusedAfterTombstoneTtl() {
        driveToDeathPastTombstoneTtl(TOKEN_A);

        gossip(3L, updateOfA(MemberState.ALIVE, 0, 0L));

        assertThat(protocol.members()).as("a relayed claim with token 0 is unknown, not different: only the incarnation is compared (D1a)")
                                      .doesNotContainKey(NODE_A);
        assertThat(hasRecord()).isTrue();
    }

    @Test
    void deathRecord_sameTokenAnnounce_refusedAfterTombstoneTtl() {
        driveToDeathPastTombstoneTtl(TOKEN_A);

        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", DEATH_INCARNATION, TOKEN_A));

        assertThat(protocol.members()).as("an ANNOUNCE from the declared-dead process at its death incarnation is refused")
                                      .doesNotContainKey(NODE_A);
        assertThat(hasRecord()).isTrue();
    }

    @Test
    void deathRecord_staleAliveGossip_afterAnnounceClearedTombstone_doesNotReAdmitResident() {
        driveToFaultyResident(TOKEN_A);
        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", DEATH_INCARNATION, TOKEN_A));
        assertThat(protocol.tombstonedForTest(NODE_A)).as("precondition: the ANNOUNCE cleared the tombstone").isFalse();
        observations.all.clear();

        gossip(3L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, TOKEN_A));

        assertThat(protocol.members().get(NODE_A).state()).as("the record refuses the re-admit the tombstone no longer does")
                                                           .isEqualTo(MemberState.FAULTY);
        assertThat(observations.byType(SwimObservation.HealthyObserved.class)).isEmpty();
    }

    @Test
    void deathRecord_strayAck_afterAnnounceClearedTombstone_doesNotFlipAlive() {
        driveToFaultyResident(TOKEN_A);
        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", DEATH_INCARNATION, TOKEN_A));
        assertThat(protocol.tombstonedForTest(NODE_A)).as("precondition: the ANNOUNCE cleared the tombstone").isFalse();
        observations.all.clear();

        protocol.onMessage(ADDR_A, new Ack(NODE_A, 99L, List.of()));

        assertThat(protocol.members().get(NODE_A).state()).as("an Ack carries no incarnation and is refused by the record")
                                                           .isEqualTo(MemberState.FAULTY);
        assertThat(observations.byType(SwimObservation.HealthyObserved.class)).isEmpty();
    }

    // ---- clears ----

    @Test
    void deathRecord_higherIncarnationAlive_clearsTheRecord_andIsAdmitted() {
        driveToDeathPastTombstoneTtl(TOKEN_A);

        gossip(3L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION + 1, TOKEN_A));

        assertThat(protocol.members()).as("a refutation above the death incarnation is admitted (clear (a))")
                                      .containsKey(NODE_A);
        assertThat(hasRecord()).as("the refutation clears the record").isFalse();
    }

    @Test
    void deathRecord_differentNonZeroToken_isAnotherProcess_admittedAndClears() {
        // A record whose token differs from the one the registry admits: only reachable when the record and the
        // registry disagree, so it is seeded directly. A different non-zero token is a new process (clear (b)).
        protocol.putDeathRecordForTest(NODE_A, DEATH_INCARNATION, OTHER_TOKEN, System.currentTimeMillis());

        gossip(3L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, TOKEN_A));

        assertThat(protocol.members()).as("a different non-zero token is not covered by the record").containsKey(NODE_A);
        assertThat(hasRecord()).as("the new process clears the record").isFalse();
    }

    @Test
    void deathRecord_faultyGossipAboveTheRecord_isNotAnAlivenessClaim_recordKept() {
        driveToDeathPastTombstoneTtl(TOKEN_A);
        protocol.stop();

        gossip(3L, updateOfA(MemberState.FAULTY, DEATH_INCARNATION + 5, TOKEN_A));
        gossip(4L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, TOKEN_A));

        assertThat(hasRecord()).as("a FAULTY update neither meets nor clears the record").isTrue();
        assertThat(protocol.members().get(NODE_A).state()).as("the stale ALIVE after it is still refused")
                                                           .isNotEqualTo(MemberState.ALIVE);
    }

    @Test
    void deathRecord_retirementOfADeadId_clearsTheRecord() {
        driveToFaultyResident(TOKEN_A);
        assertThat(hasRecord()).as("precondition: record written").isTrue();

        assertThat(tokens.admit(NODE_A, OTHER_TOKEN)).isEqualTo(BootTokens.Admission.CONFLICT);

        assertThat(protocol.isRetiredIdentity(NODE_A)).as("precondition: retired").isTrue();
        assertThat(hasRecord()).as("retirement clears the record (clear (c))").isFalse();
    }

    @Test
    void deathRecord_retirementOfALiveId_writesNoRecord() {
        gossip(1L, updateOfA(MemberState.ALIVE, 0, TOKEN_A));

        tokens.admit(NODE_A, OTHER_TOKEN);

        assertThat(protocol.members().get(NODE_A).state()).as("precondition: the retirement killed it")
                                                           .isEqualTo(MemberState.FAULTY);
        assertThat(hasRecord()).as("the death edge of a retired id writes no record").isFalse();
    }

    // ---- token 0 at the edge (D1b, M3) ----

    @Test
    void deathRecord_tokenZero_sameIncarnationRestart_admittedAfterTtl() {
        driveToFaultyResident(0L);
        assertThat(hasRecord()).as("a death whose token was unknown at the edge writes no record").isFalse();
        protocol.start();
        await().atMost(Duration.ofSeconds(4))
               .until(() -> !protocol.members().containsKey(NODE_A) && !protocol.tombstonedForTest(NODE_A));

        gossip(3L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, 0L));

        assertThat(protocol.members()).as("without a notice-capable record the gate ends with the tombstone (base behaviour)")
                                      .containsKey(NODE_A);
    }

    @Test
    void deathRecord_tokenLearnedAfterTheEdge_neverTurnsIntoAPermanentGate() {
        driveToFaultyResident(0L);
        gossip(3L, updateOfA(MemberState.FAULTY, DEATH_INCARNATION, TOKEN_A));
        assertThat(tokens.tokenOf(NODE_A)).as("precondition: the token is learned after the edge").isEqualTo(TOKEN_A);
        protocol.announceFromPinnedSourceForTest(ADDR_A, Announce.announce(INFO_A, "", 0, 0L));
        assertThat(protocol.tombstonedForTest(NODE_A)).as("precondition: tombstone cleared").isFalse();

        gossip(4L, updateOfA(MemberState.ALIVE, DEATH_INCARNATION, 0L));

        assertThat(protocol.members().get(NODE_A).state()).as("the token is snapshotted at the edge, never read live later (M3)")
                                                           .isEqualTo(MemberState.ALIVE);
    }

    // ---- bound ----

    @Test
    void deathRecord_capReached_evictsTheOldest() {
        var cap = 4096;

        for (var i = 0; i < cap; i++) {
            protocol.putDeathRecordForTest(new NodeId("dead-" + i), 1L, TOKEN_A, 1_000L + i);
        }

        assertThat(protocol.deathRecordCountForTest()).isEqualTo(cap);

        protocol.putDeathRecordForTest(new NodeId("dead-new"), 1L, TOKEN_A, 1_000_000L);

        assertThat(protocol.deathRecordCountForTest()).as("the cap holds").isEqualTo(cap);
        assertThat(protocol.deathRecordForTest(new NodeId("dead-0")).isPresent()).as("the oldest is evicted").isFalse();
        assertThat(protocol.deathRecordForTest(new NodeId("dead-1")).isPresent()).as("only the oldest").isTrue();
        assertThat(protocol.deathRecordForTest(new NodeId("dead-new")).isPresent()).as("the newest is kept").isTrue();
    }
}
