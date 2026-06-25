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
import org.pragmatica.lang.utils.Causes;
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
    /// joinGrace 0 — these tombstone-edge tests assert the immediate (post-grace) NORMAL
    /// behavior; the NORMAL-phase JOIN-GRACE deferral is covered in
    /// SwimProtocolPhaseAwareSuppressionTest and joinGraceConfig() below.
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
        transport = new RecordingTransport();
        listener = new RecordingListener();
        observations = new RecordingObservationSink();
        protocol = SwimProtocol.swimProtocol(tightConfig(), transport, listener, SELF_ID, SELF_ADDR, () -> false)
                               .unwrap();
        protocol.addObservationListener(observations);
    }

    /// COLD_BOOT-phase protocol with a configured join-grace, used to confirm the
    /// booting branch still suppresses regardless of the grace term.
    private SwimProtocol coldBootProtocol() {
        var coldBoot = SwimProtocol.swimProtocol(tightConfig(), transport, listener, SELF_ID, SELF_ADDR, () -> true)
                                   .unwrap();
        coldBoot.addObservationListener(observations);
        return coldBoot;
    }

    /// NORMAL-phase protocol with a join-grace (600ms) LONGER than the suspect-window (100ms):
    /// a freshly-seeded OBSERVED joiner stays OBSERVED (no death timer) within grace and only
    /// escalates OBSERVED->SUSPECT->FAULTY after the join deadline, when the FAULTY-edge tombstone
    /// fires (#336/#241). `lhmMaxScore=1` keeps the post-escalation suspect window at base so the
    /// FAULTY-edge timing is deterministic within the test budget.
    private SwimProtocol longGraceProtocol() {
        var cfg = swimConfig(timeSpan(20).millis(),
                             timeSpan(20).millis(),
                             3,
                             timeSpan(100).millis(),
                             8,
                             timeSpan(20).millis()).withJoinGrace(timeSpan(600).millis())
                                                   .withLhmMaxScore(1);
        var graced = SwimProtocol.swimProtocol(cfg, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                 .unwrap();
        graced.addObservationListener(observations);
        return graced;
    }

    /// NORMAL-phase protocol with a join-grace (40ms) SHORTER than the suspect-window
    /// (100ms): a gossip-introduced never-HEALTHY SUSPECT reaches its FAULTY edge at the
    /// suspect-window (past the short grace) and IS tombstoned (06-02 anti-oscillation preserved).
    private SwimProtocol shortGraceProtocol() {
        var cfg = swimConfig(timeSpan(20).millis(),
                             timeSpan(20).millis(),
                             3,
                             timeSpan(100).millis(),
                             8,
                             timeSpan(20).millis()).withJoinGrace(timeSpan(40).millis());
        var graced = SwimProtocol.swimProtocol(cfg, transport, listener, SELF_ID, SELF_ADDR, () -> false)
                                 .unwrap();
        graced.addObservationListener(observations);
        return graced;
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
        // is cleared so cleanup removes (and tombstones) it on the next tick. The
        // second-hand (gossip) FAULTY needs local transport-down corroboration to drive
        // the death path (P1 death-path co-confirmation).
        protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
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
    void selfAnnounce_ofTombstonedId_isReAdmittedAsObserved_andClearsTombstone() {
        try {
            driveProvenHealthyToTombstone();

            // Authoritative self-ANNOUNCE: proof the node is alive again (partition heal). The
            // unconditional tombstone-clear in handleAnnounce removes the tombstone first, so the
            // gated introduction is admitted — as OBSERVED (#336/#241): re-admitted and
            // probe-eligible, but NOT counted ALIVE/HEALTHY until a probe-ack confirms it.
            var nodeInfoA = NodeInfo.nodeInfo(NODE_A, new NodeAddress("127.0.0.1", 9001));
            protocol.onMessage(ADDR_A, new Announce(nodeInfoA, "", 0));

            assertThat(protocol.members().containsKey(NODE_A))
                .as("Self-ANNOUNCE must re-admit a tombstoned id")
                .isTrue();
            assertThat(protocol.members().get(NODE_A).state())
                .as("Re-admitted via ANNOUNCE as OBSERVED (not yet confirmed alive)")
                .isEqualTo(MemberState.OBSERVED);
            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("Self-ANNOUNCE must clear the tombstone")
                .isFalse();
            assertThat(protocol.everSeenHealthyForTest(NODE_A))
                .as("OBSERVED re-admit is not yet proven-healthy — a probe-ack sets ever-seen-healthy later")
                .isFalse();
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
    void neverHealthyId_cleanedUp_inColdBoot_isNotTombstoned_gossipReAddAllowed() {
        var coldBoot = coldBootProtocol();
        try {
            coldBoot.start();

            // NODE_A enters as SUSPECT via gossip and is NEVER observed HEALTHY.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            coldBoot.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
            assertThat(coldBoot.everSeenHealthyForTest(NODE_A)).isFalse();

            // Suspect-window expiry drives FAULTY, then cleanup removes it.
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> !coldBoot.members().containsKey(NODE_A));

            // COLD_BOOT invariant: a never-HEALTHY id must NOT be tombstoned in cold-boot,
            // so a slow seed can still be re-added and formation can proceed.
            assertThat(coldBoot.tombstonedForTest(NODE_A))
                .as("COLD_BOOT: never-HEALTHY (cold-boot seed) id must NOT be tombstoned — formation path intact")
                .isFalse();

            // A subsequent gossip re-add IS allowed (formation can still proceed).
            var reAddSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            coldBoot.onMessage(ADDR_B, new Ping(NODE_B, 5L, List.of(reAddSuspect)));
            assertThat(coldBoot.members().containsKey(NODE_A))
                .as("COLD_BOOT: never-tombstoned id must be re-addable via gossip (cold-boot formation)")
                .isTrue();
        } finally {
            coldBoot.stop();
        }
    }

    @Test
    void neverHealthyId_faultyInNormalPhase_isTombstoned_reAddRefused() {
        // 02-chaos S01: a replacement node killed while NEVER-HEALTHY (JOINING/SYNCING
        // window). In NORMAL phase its FAULTY edge MUST tombstone it so the stale-Hello
        // re-seed / gossip re-add is refused — ending the ~1 Hz SUSPECT<->FAULTY
        // oscillation that otherwise republishes a generation snapshot every second and
        // keeps cluster quiescence DEGRADED forever.
        try {
            protocol.start();

            // NODE_A enters as SUSPECT via gossip and is NEVER observed HEALTHY.
            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
            assertThat(protocol.everSeenHealthyForTest(NODE_A)).isFalse();

            // Suspect-window expiry drives FAULTY; the FAULTY edge tombstones it (NORMAL),
            // and cleanup then removes it from membership.
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> protocol.tombstonedForTest(NODE_A) && !protocol.members().containsKey(NODE_A));

            assertThat(protocol.tombstonedForTest(NODE_A))
                .as("NORMAL phase: a never-HEALTHY id reaching FAULTY MUST be tombstoned (S01)")
                .isTrue();

            // (a) bare channel re-seed (stale Hello re-completes, no incarnation => 0): refused.
            protocol.addSeedMember(NODE_A, ADDR_A);
            assertThat(protocol.members().containsKey(NODE_A))
                .as("Tombstoned never-HEALTHY id must NOT be re-created by bare re-seed")
                .isFalse();

            // (b) third-party gossip re-add at the tombstoned incarnation (0): refused.
            var gossipSuspect = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            protocol.onMessage(ADDR_B, new Ping(NODE_B, 5L, List.of(gossipSuspect)));
            assertThat(protocol.members().containsKey(NODE_A))
                .as("Tombstoned never-HEALTHY id must NOT be re-created by gossip")
                .isFalse();

            // Several ticks later it must still be gone — no SUSPECT re-entry, no oscillation.
            await().pollDelay(Duration.ofMillis(300))
                   .atMost(Duration.ofSeconds(1))
                   .until(() -> !protocol.members().containsKey(NODE_A) && protocol.tombstonedForTest(NODE_A));
        } finally {
            protocol.stop();
        }
    }

    @Test
    void observedJoiner_withinJoinGrace_notTombstoned_thenTombstonedAfterDeadline() throws InterruptedException {
        // OBSERVED birth (#336/#241): a freshly-seeded never-acking joiner stays OBSERVED within its
        // join-grace window — never SUSPECT, never FAULTY, never tombstoned — so the leader's probe
        // cycle still has time to confirm it. Once the join deadline passes, a probe-timeout escalates
        // it OBSERVED->SUSPECT->FAULTY and the FAULTY-edge tombstone fires (the 06-02 anti-oscillation
        // contract is deferred to the deadline, never skipped).
        var graced = longGraceProtocol();
        try {
            graced.addSeedMember(NODE_A, ADDR_A);
            assertThat(graced.members().get(NODE_A).state()).isEqualTo(MemberState.OBSERVED);
            assertThat(graced.everSeenHealthyForTest(NODE_A)).isFalse();

            graced.start();

            // WITHIN grace (600ms): probe timeouts never arm death — still OBSERVED, no tombstone, no emission.
            Thread.sleep(300L);
            assertThat(graced.members().get(NODE_A).state())
                .as("Within join-grace: an un-acked OBSERVED joiner stays OBSERVED")
                .isEqualTo(MemberState.OBSERVED);
            assertThat(graced.tombstonedForTest(NODE_A))
                .as("Within join-grace: an OBSERVED joiner must NOT be tombstoned")
                .isFalse();
            assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                .as("Within join-grace: no FAULTY edge, no FaultyObserved")
                .isEmpty();
            assertThat(observations.byType(SwimObservation.UnknownObserved.class))
                .as("Within join-grace: nothing is emitted at all")
                .isEmpty();

            // AFTER the join deadline: OBSERVED -> SUSPECT -> FAULTY in NORMAL phase => tombstoned + FaultyObserved.
            await().atMost(Duration.ofSeconds(2))
                   .until(() -> graced.tombstonedForTest(NODE_A)
                                && !observations.byType(SwimObservation.FaultyObserved.class).isEmpty());
            assertThat(observations.byType(SwimObservation.FaultyObserved.class))
                .as("After the join deadline: the FAULTY edge emits FaultyObserved (no UNKNOWN suppression)")
                .isNotEmpty();
        } finally {
            graced.stop();
        }
    }

    @Test
    void neverHealthyId_faultyAfterJoinGraceExpiry_isTombstoned_reAddRefused() {
        // Join-grace (40ms) SHORTER than the suspect-window (100ms): a gossip-introduced
        // never-HEALTHY SUSPECT reaches its FAULTY edge at the suspect-window — past the short
        // grace — and IS tombstoned, exactly as today (the 06-02 anti-oscillation contract holds).
        // The OBSERVED join-grace path (#336/#241) applies only to seeded/announced members.
        var graced = shortGraceProtocol();
        try {
            graced.start();

            var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
            graced.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
            assertThat(graced.everSeenHealthyForTest(NODE_A)).isFalse();

            await().atMost(Duration.ofSeconds(3))
                   .until(() -> graced.tombstonedForTest(NODE_A) && !graced.members().containsKey(NODE_A));

            assertThat(graced.tombstonedForTest(NODE_A))
                .as("After join-grace expiry: never-HEALTHY FAULTY member MUST be tombstoned (06-02)")
                .isTrue();

            // Bare re-seed refused — oscillation ends.
            graced.addSeedMember(NODE_A, ADDR_A);
            assertThat(graced.members().containsKey(NODE_A))
                .as("After expiry+tombstone, bare re-seed must be refused")
                .isFalse();
        } finally {
            graced.stop();
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

        // Second-hand (gossip) FAULTY needs local transport-down corroboration to drive
        // the death path (P1 death-path co-confirmation).
        protocol.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
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
        // Cold-boot S04 seed: a never-HEALTHY id goes FAULTY via gossip in COLD_BOOT phase.
        // It must NOT be tombstoned (cold-boot gate), so a subsequent gossip re-add is
        // allowed — preserving formation. (In NORMAL phase it WOULD be tombstoned; see
        // neverHealthyId_faultyInNormalPhase_isTombstoned_reAddRefused.)
        var coldBoot = coldBootProtocol();
        var suspectA = new MembershipUpdate(NODE_A, MemberState.SUSPECT, 0, ADDR_A);
        coldBoot.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(suspectA)));
        assertThat(coldBoot.everSeenHealthyForTest(NODE_A)).isFalse();

        // Second-hand (gossip) FAULTY needs local transport-down corroboration to drive
        // the FAULTY edge (P1 death-path co-confirmation) — without it the verdict would
        // be downgraded to SUSPECT and the FAULTY-edge tombstone gate would never run.
        coldBoot.recordTransportHint(NODE_A, new TransportObservation.PeerUnreachable(NODE_A, Causes.cause("test peer down")));
        var faultyA = new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A);
        coldBoot.onMessage(ADDR_B, new Ping(NODE_B, 2L, List.of(faultyA)));

        assertThat(coldBoot.tombstonedForTest(NODE_A))
            .as("COLD_BOOT: never-HEALTHY id reaching FAULTY must NOT be tombstoned")
            .isFalse();

        // Gossip re-add toward ALIVE at a higher incarnation is allowed (no tombstone
        // block). A higher incarnation is required because canonical SWIM never lets a
        // same-incarnation ALIVE override FAULTY — the point here is the ABSENCE of a
        // tombstone refusal, which a proven-healthy id would suffer at incarnation <= 1.
        var reAliveA = new MembershipUpdate(NODE_A, MemberState.ALIVE, 2, ADDR_A);
        coldBoot.onMessage(ADDR_B, new Ping(NODE_B, 3L, List.of(reAliveA)));
        assertThat(coldBoot.members().get(NODE_A).state())
            .as("COLD_BOOT: never-tombstoned cold-boot id must be re-addable to ALIVE")
            .isEqualTo(MemberState.ALIVE);
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
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
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
