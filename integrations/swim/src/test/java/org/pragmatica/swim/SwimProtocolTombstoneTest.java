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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Anti-oscillation tombstone regression tests (#231, 2026-06).
///
/// Root cause: a docker-killed node that WAS healthy oscillated SUSPECT<->FAULTY
/// forever on the leader because removing the FAULTY member cleared its death-memory
/// but there was no tombstone — third parties (gossip + bare channel re-seed) kept
/// re-introducing the dead id as SUSPECT, restarting the cycle.
///
/// The incarnation-aware, TTL-bounded tombstone refuses third-party re-introduction
/// of a PROVEN-HEALTHY-then-dead id while:
///   - preserving cold-boot formation (never-HEALTHY ids are NOT tombstoned),
///   - preserving partition-heal (self-ANNOUNCE clears the tombstone),
///   - honoring genuine restarts (a strictly-higher incarnation supersedes).
class SwimProtocolTombstoneTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);

    private RecordingTransport transport;
    private RecordingListener listener;
    private RecordingObservationSink observations;
    private SwimProtocol protocol;

    /// Tight timings: suspectTimeout 100ms => cleanup threshold 300ms, tombstone TTL
    /// 1000ms (10x). Period 20ms keeps cleanup ticks frequent. Phase NORMAL (false)
    /// so a once-HEALTHY-then-FAULTY peer produces the real FAULTY/Departed path.
    private static SwimConfig tightConfig() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis());
    }

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        listener = new RecordingListener();
        observations = new RecordingObservationSink();
        protocol = SwimProtocol.swimProtocol(tightConfig(), transport, listener, SELF_ID, SELF_ADDR, () -> false)
                               .unwrap();
        protocol.addObservationListener(observations);
    }

    /// Drive NODE_A: ALIVE gossip (sets everSeenHealthy) -> FAULTY gossip -> cleanup.
    /// After cleanup the proven-healthy id must be tombstoned.
    private void driveProvenHealthyToTombstone() {
        protocol.start();

        // ALIVE gossip: marks NODE_A everSeenHealthy + HEALTHY.
        var aliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveA)));
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

        // FAULTY gossip at higher incarnation: member goes FAULTY, suspect timestamp
        // is cleared so cleanup removes (and tombstones) it on the next tick.
        var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));

        await().atMost(Duration.ofSeconds(3))
               .until(() -> protocol.tombstonedForTest(NODE_A) && !protocol.members().containsKey(NODE_A));
    }

    @Test
    void gossipAndSeedReAdd_ofTombstonedProvenHealthyId_areBothRefused_noOscillation() {
        try {
            driveProvenHealthyToTombstone();

            // (a) third-party GOSSIP re-add at the tombstoned incarnation: refused.
            var gossipSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(gossipSuspect)));

            // (b) bare channel re-seed (no incarnation => 0): refused.
            protocol.addSeedMember(NODE_A, ADDR_A);

            assertThat(protocol.members().containsKey(NODE_A))
                .as("Tombstoned proven-healthy id must NOT be re-created by gossip or re-seed")
                .isFalse();
            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("Tombstone must remain in place after refused re-adds")
                .isTrue();

            // Give the cluster several ticks: the id must stay gone — no SUSPECT re-entry.
            await().pollDelay(Duration.ofMillis(300))
                   .atMost(Duration.ofSeconds(1))
                   .until(() -> !protocol.members().containsKey(NODE_A));
        } finally {
            protocol.stop();
        }
    }

    @Test
    void selfAnnounce_ofTombstonedId_isReAdmittedAsAlive_andClearsTombstone() {
        try {
            driveProvenHealthyToTombstone();

            // Authoritative self-ANNOUNCE: proof the node is alive again (partition heal).
            // FIX A: direct liveness evidence => re-admitted as ALIVE (not SUSPECT), with
            // no suspect-timer armed at birth. The unconditional tombstone-clear in
            // handleAnnounce removes the tombstone first, so the gated ALIVE introduction
            // is admitted.
            var nodeInfoA = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
            protocol.onMessage(ADDR_A, new Announce(nodeInfoA, "", 0));

            assertThat(protocol.members().containsKey(NODE_A))
                .as("Self-ANNOUNCE must re-admit a tombstoned id")
                .isTrue();
            assertThat(protocol.members().get(NODE_A).state())
                .as("Re-admitted via ANNOUNCE as ALIVE (direct liveness evidence)")
                .isEqualTo(MemberState.ALIVE);
            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("Self-ANNOUNCE must clear the tombstone")
                .isFalse();
            assertThat(protocol.everSeenHealthyForTest(NODE_A))
                .as("ALIVE re-admit via ANNOUNCE records proven-healthy")
                .isTrue();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void gossipReAdd_atHigherIncarnation_supersedesTombstone_andIsAllowed() {
        try {
            driveProvenHealthyToTombstone();

            // Genuine restart at a strictly-higher incarnation than the tombstone (1).
            var restartSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 99, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 4L, List.of(restartSuspect)));

            assertThat(protocol.members().containsKey(NODE_A))
                .as("Higher-incarnation gossip must supersede the tombstone and re-add")
                .isTrue();
            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("Higher-incarnation supersede must remove the tombstone")
                .isFalse();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void neverHealthyId_cleanedUp_isNotTombstoned_gossipReAddAllowed() {
        try {
            protocol.start();

            // NODE_A enters as SUSPECT via gossip and is NEVER observed HEALTHY.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
            assertThat(protocol.everSeenHealthyForTest(NODE_A)).isFalse();

            // Suspect-window expiry drives FAULTY, then cleanup removes it.
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !protocol.members().containsKey(NODE_A));

            // Cold-boot invariant: a never-HEALTHY id must NOT be tombstoned.
            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("Never-HEALTHY (cold-boot seed) id must NOT be tombstoned — formation path intact")
                .isFalse();

            // A subsequent gossip re-add IS allowed (formation can still proceed).
            var reAddSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 5L, List.of(reAddSuspect)));
            assertThat(protocol.members().containsKey(NODE_A))
                .as("Never-tombstoned id must be re-addable via gossip (cold-boot formation)")
                .isTrue();
        } finally {
            protocol.stop();
        }
    }

    @Test
    void afterTtlElapses_tombstonedId_canBeReAddedViaGossipAgain() {
        try {
            driveProvenHealthyToTombstone();

            // TTL = suspectTimeout(100ms) * 10 = 1000ms. Wait for the sweep (runs each
            // tick) to drop the tombstone, then gossip re-add must be allowed again.
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !protocol.tombstonedForTest(NODE_A));

            var gossipSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 6L, List.of(gossipSuspect)));

            assertThat(protocol.members().containsKey(NODE_A))
                .as("After the tombstone TTL elapses, gossip re-add must be allowed again")
                .isTrue();
        } finally {
            protocol.stop();
        }
    }

    // -- FAULTY-edge tombstone regression tests (pre-sweep, member still resident) --
    //
    // These exercise the PRIMARY tombstone set at the FAULTY edge: the member is still
    // present in `members` (no `start()` => no cleanup tick), so a re-admit attempt hits
    // `applyExistingMember` / `markAliveIfNeeded` rather than the `applyUpdate` isEmpty
    // add-path that the sweep-time tests above cover.

    /// Drive NODE_A ALIVE (proven healthy) then FAULTY via gossip, WITHOUT starting the
    /// protocol — so no cleanup tick fires and the FAULTY member stays resident. The
    /// FAULTY edge (`notifyFaulty`) sets the tombstone synchronously.
    private void driveProvenHealthyToFaultyResident() {
        var aliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveA)));
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

        var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));

        assertThat(protocol.members().get(NODE_A).state())
            .as("Member must be FAULTY-resident (not yet swept)")
            .isEqualTo(MemberState.FAULTY);
        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("Tombstone must be set at the FAULTY edge, before any sweep")
            .isTrue();
    }

    @Test
    void wasHealthy_faulty_thenGossipReAddAtSameIncarnation_isRefused() {
        driveProvenHealthyToFaultyResident();
        observations.all.clear();

        // Gossip ALIVE at the tombstoned incarnation (1) while the FAULTY member is still
        // resident: hits applyExistingMember regression-toward-ALIVE — must be refused and
        // the tombstone must survive (a same-incarnation re-add never supersedes it).
        var reAliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 1, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(reAliveA)));

        assertThat(protocol.members().get(NODE_A).state())
            .as("Tombstoned FAULTY-resident id must NOT regress to ALIVE via gossip")
            .isEqualTo(MemberState.FAULTY);
        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("Same-incarnation gossip re-add must NOT supersede the tombstone")
            .isTrue();
        assertThat(observations.byType(SwimObservation.HealthyObserved.class))
            .as("No HealthyObserved may be emitted for a refused re-admit")
            .isEmpty();
    }

    @Test
    void wasHealthy_faulty_thenStrayAck_doesNotFlipAlive() {
        driveProvenHealthyToFaultyResident();
        observations.all.clear();

        // A stray Ack relayed for the FAULTY-resident tombstoned id drives
        // markAliveIfNeeded — the tombstone gate (incarnation 0) must refuse the flip.
        protocol.onMessage(ADDR_A, new SwimMessage.Ack(NODE_A, 99L, List.of()));

        assertThat(protocol.members().get(NODE_A).state())
            .as("Stray Ack must NOT flip a tombstoned FAULTY-resident id to ALIVE")
            .isEqualTo(MemberState.FAULTY);
        assertThat(observations.byType(SwimObservation.HealthyObserved.class))
            .as("No HealthyObserved may be emitted off a refused stray Ack")
            .isEmpty();
    }

    @Test
    void partitionHeal_selfAnnounceHigherIncarnation_readmits() {
        driveProvenHealthyToFaultyResident();

        // S06 partition-heal: the node itself ANNOUNCEs at a higher incarnation. The id is
        // still resident as FAULTY, so the ANNOUNCE only CLEARS the tombstone here (FIX A's
        // ALIVE introduction is the unknown-member branch; a resident member is untouched
        // by the announce datagram beyond the tombstone clear). The subsequent
        // higher-incarnation gossip then re-admits the member as SUSPECT. Authoritative
        // self-liveness wins via the clear.
        var nodeInfoA = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
        protocol.onMessage(ADDR_A, new Announce(nodeInfoA, "", 5));

        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("Self-ANNOUNCE must clear the tombstone (partition heal)")
            .isFalse();

        // Higher-incarnation SUSPECT gossip now re-admits the member (tombstone gone).
        var reSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 5, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 7L, List.of(reSuspect)));
        assertThat(protocol.members().get(NODE_A).state())
            .as("After partition-heal clear, higher-incarnation gossip re-admits as SUSPECT")
            .isEqualTo(MemberState.SUSPECT);
    }

    @Test
    void liveFlap_suspectThenAliveRecovery_notBlocked() {
        // S04/S13 transient: a member goes SUSPECT (never FAULTY) then recovers to ALIVE.
        // It must NEVER be tombstoned (tombstone is FAULTY-edge only) and must recover.
        var aliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(aliveA)));
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isTrue();

        var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(suspectA)));
        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.SUSPECT);
        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("A SUSPECT flap (never FAULTY) must NOT be tombstoned")
            .isFalse();

        var recoverA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 2, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(recoverA)));
        assertThat(protocol.members().get(NODE_A).state())
            .as("SUSPECT->ALIVE recovery must be allowed (no tombstone block)")
            .isEqualTo(MemberState.ALIVE);
    }

    @Test
    void neverHealthy_faulty_notTombstoned_coldBootReAddAllowed() {
        // Cold-boot S04 seed: a never-HEALTHY id goes FAULTY via gossip. It must NOT be
        // tombstoned (everSeenHealthy gate), so a subsequent gossip re-add is allowed —
        // preserving formation.
        var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isFalse();

        var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));

        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("Never-HEALTHY id reaching FAULTY must NOT be tombstoned (cold-boot)")
            .isFalse();

        // Gossip re-add toward ALIVE at a higher incarnation is allowed (no tombstone
        // block). A higher incarnation is required because canonical SWIM never lets a
        // same-incarnation ALIVE override FAULTY — the point here is the ABSENCE of a
        // tombstone refusal, which a proven-healthy id would suffer at incarnation <= 1.
        var reAliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 2, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(reAliveA)));
        assertThat(protocol.members().get(NODE_A).state())
            .as("Never-tombstoned cold-boot id must be re-addable to ALIVE")
            .isEqualTo(MemberState.ALIVE);
    }

    // -- markAliveFromTransport (#34 cold-start formation): transport-plane liveness --
    //
    // A completed QUIC channel to a KNOWN member is reachability proof. markAliveFromTransport
    // promotes a never-tombstoned member to ALIVE (closing the startupDelay≈suspectTimeout
    // race) but is refused for a proven-healthy-then-FAULTY tombstoned id (#231).

    @Test
    void markAliveFromTransport_suspectNonTombstonedMember_promotesToAlive() {
        // NODE_A enters as SUSPECT via gossip, NEVER observed HEALTHY => not tombstoned.
        var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
        assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.SUSPECT);
        assertThat(protocol.everSeenHealthyForTest(NODE_A)).isFalse();
        assertThat(protocol.tombstonedForTest(NODE_A)).isFalse();
        observations.all.clear();

        // Transport-plane liveness proof (completed QUIC channel) promotes it ALIVE.
        protocol.markAliveFromTransport(NODE_A);

        assertThat(protocol.members().get(NODE_A).state())
            .as("A completed QUIC channel must promote a never-tombstoned SUSPECT member to ALIVE")
            .isEqualTo(MemberState.ALIVE);
        assertThat(observations.byType(SwimObservation.HealthyObserved.class))
            .as("markAliveFromTransport must emit HealthyObserved for the promoted member")
            .isNotEmpty();
    }

    @Test
    void markAliveFromTransport_provenHealthyThenFaultyTombstoned_refusesPromotion() {
        driveProvenHealthyToFaultyResident();
        observations.all.clear();

        // Transport-plane liveness proof for a tombstoned (proven-healthy-then-dead) id:
        // the tombstone gate must refuse the flip — no resurrection off a stale/reopened channel.
        protocol.markAliveFromTransport(NODE_A);

        assertThat(protocol.members().get(NODE_A).state())
            .as("Tombstoned proven-healthy id must NOT be promoted to ALIVE off transport liveness")
            .isEqualTo(MemberState.FAULTY);
        assertThat(protocol.tombstonedForTest(NODE_A))
            .as("Tombstone must survive a refused transport promotion")
            .isTrue();
        assertThat(observations.byType(SwimObservation.HealthyObserved.class))
            .as("No HealthyObserved may be emitted for a refused transport promotion")
            .isEmpty();
    }

    // -- Test infrastructure --

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<Object> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(message);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            handler.set(null);
            return Promise.success(Unit.unit());
        }
    }

    static class RecordingListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    static class RecordingObservationSink implements Consumer<SwimObservation> {
        final CopyOnWriteArrayList<SwimObservation> all = new CopyOnWriteArrayList<>();

        @Override public void accept(SwimObservation observation) {
            all.add(observation);
        }

        <T extends SwimObservation> List<T> byType(Class<T> type) {
            return all.stream()
                      .filter(type::isInstance)
                      .map(type::cast)
                      .toList();
        }
    }
}
