// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ProvisioningWorld.KillPoint;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// G3 (q4 section 7.3): a model-based test of provisioning. The REAL leader reconciler, topology manager and capacity layer run against a
/// recording fake provider that behaves as P-G1 measured Hetzner, on one virtual clock, with leader kills, partitions, outages and lost
/// replies injected between calls. It is the hazard probe of G1: what it asserts for real holds today; what G1 will change is pinned by an
/// ENABLED tripwire that asserts today's behaviour, with the real assertion `@Disabled` beside it and the G1 step that enables it.
///
/// The invariants (ProvisioningInvariants) are checked over the call log, the fake's server set and the committed KV history. Each has a
/// planted violation (see [PlantedViolations]) that turns it red, so a green is evidence, not silence.
///
/// What is NOT in the model, so a green here does not say it: a member dying while a leader lives (the membership FSM's eviction backstop
/// runs in real time, so a leader kill replaces the stack instead), the CTM's own real-time timers in the seeded runs (K10 is a
/// real-time cell), the apply path (`WaveNodeProvisioning`, G1-f) and a real Hetzner.
class ProvisioningModelTest {
    private static final long FORTY_MINUTES = 2_400_000L;

    private static ProvisioningWorld run(ProvisioningWorld world, int alive, KillPoint point, long millis) {
        world.start(alive);
        world.killLeaderAt(point);
        world.run(millis);

        return world;
    }

    private static ProvisioningWorld killedAt(KillPoint point) {
        return run(new ProvisioningWorld(), 4, point, FORTY_MINUTES);
    }

    /// The fully converged, violation-free end state of every cell that does not leave a leftover VM behind.
    private static void assertConverged(ProvisioningWorld world) {
        assertThat(ProvisioningInvariants.allToday(world)).as("I1/I2/I3/I6 over the whole run").isEmpty();
        assertThat(world.members).as("members: the target is met").hasSize(ProvisioningWorld.TARGET);
        assertThat(world.nonMemberServers()).as("no server beyond the membership").isEmpty();
    }

    private static long creates(ProvisioningWorld world) {
        return world.provider.calls("create").stream().filter(call -> call.outcome().startsWith("sent")).count();
    }

    // ================================================================================================================= K1-K4

    /// The four points the leader can die at around one create (q4 section 7.3, faults).
    @Nested
    class LeaderKillCells {
        /// K1: the reservation commit never lands, so the dead leader made no provider call. The successor replaces both missing members.
        @Test
        void k1_killedBeforeTheReservationCommit_leavesNoReservationAndNoServer_andTheSuccessorHeals() {
            var world = killedAt(KillPoint.BEFORE_RESERVATION_COMMIT);

            assertThat(world.cells).contains("K1");
            assertThat(creates(world)).as("one create per missing member, none by the dead leader").isEqualTo(2);
            assertConverged(world);
        }

        /// K2: the reservation committed and the provider was never asked. The successor cannot know that, so it counts the reservation
        /// as capacity until it releases it; the id is never created, and the deficit is healed under another id.
        @Test
        void k2_killedAfterTheCommitBeforeTheCall_leavesAReservationWithNoCreate_thatIsReleased_andNeverCreated() {
            var world = killedAt(KillPoint.AFTER_COMMIT_BEFORE_CALL);
            var abandoned = world.reservationFacts.stream()
                                 .filter(fact -> fact.phase().equals("DISPATCHED"))
                                 .map(ProvisioningWorld.ReservationFact::node)
                                 .filter(node -> world.provider.calls("create").stream().noneMatch(call -> call.name().equals(node.id())))
                                 .toList();

            assertThat(world.cells).contains("K2");
            assertThat(abandoned).as("a committed reservation whose create was never sent").hasSize(1);
            assertThat(world.reservationPhase(abandoned.getFirst())).as("and it is not left holding capacity").isNull();
            assertConverged(world);
        }

        /// K3: the provider applied the create and the reply died with the leader. The server exists; the successor counts it through the
        /// reservation, does not create a second one for the same deficit, and the node joins.
        @Test
        void k3_killedAfterTheCallBeforeTheReply_leavesAServerTheSuccessorCountsAndDoesNotDuplicate() {
            var world = killedAt(KillPoint.AFTER_CALL_BEFORE_REPLY);

            assertThat(world.cells).contains("K3");
            assertThat(creates(world)).as("a deficit of two is two creates, not three").isEqualTo(2);
            assertConverged(world);
        }

        /// K4: the reply reached the leader and nothing recorded OBSERVED yet.
        @Test
        void k4_killedAfterTheReplyBeforeObserved_leavesAServerTheSuccessorCountsAndDoesNotDuplicate() {
            var world = killedAt(KillPoint.AFTER_REPLY_BEFORE_OBSERVED);

            assertThat(world.cells).contains("K4");
            assertThat(creates(world)).isEqualTo(2);
            assertConverged(world);
        }
    }

    // ================================================================================================================= K5-K9

    @Nested
    class OtherFaultCells {
        /// K5: the create is applied and the reply is dropped, with no leader change. The caller sees a failure; the server exists.
        /// Today the failed create is reaped (see [Tripwires#k5_aLostReplyServer_isDeletedByTheDroppedReplacementReap]).
        @Test
        void k5_aCreateAppliedWithItsReplyDropped_endsWithOneServerPerMissingMember_andNoDuplicate() {
            var world = new ProvisioningWorld();
            var calls = new AtomicInteger();

            world.provider.fate = (name, attempt) -> calls.getAndIncrement() == 0 ? ModelComputeProvider.Fate.APPLIED_REPLY_LOST : ModelComputeProvider.Fate.APPLIED;
            world.start(4);
            world.run(FORTY_MINUTES);

            assertThat(world.cells).contains("K5");
            assertConverged(world);
        }

        /// K6: the create is dropped before it was applied, the reply is lost too: nothing exists, and the deficit is healed.
        @Test
        void k6_aCreateDroppedBeforeItWasApplied_leavesNothing_andTheDeficitIsHealedUnderAnotherId() {
            var world = new ProvisioningWorld();
            var calls = new AtomicInteger();

            world.provider.fate = (name, attempt) -> calls.getAndIncrement() == 0 ? ModelComputeProvider.Fate.NOT_APPLIED_REPLY_LOST : ModelComputeProvider.Fate.APPLIED;
            world.start(4);
            world.run(FORTY_MINUTES);

            assertThat(world.cells).contains("K6");
            assertThat(creates(world)).as("the dropped one and its successor").isEqualTo(2);
            assertConverged(world);
        }

        /// K7: the leader is partitioned with a minority after its reservation committed: it still reaches the provider but not the KV. The
        /// majority's successor sees the committed reservation, so one deficit gets one server.
        @Test
        void k7_aLeaderPartitionedAfterItsCommit_stillCreates_andTheSuccessorDoesNotDuplicate() {
            var world = new ProvisioningWorld();

            world.partitionInsteadOfKillMs = 30_000L;
            run(world, 4, KillPoint.AFTER_COMMIT_BEFORE_CALL, FORTY_MINUTES);

            assertThat(world.cells).contains("K7");
            assertThat(creates(world)).as("one deficit, one create").isEqualTo(1);
            assertConverged(world);
        }

        /// K7b: partitioned before the commit: it cannot reserve, so it cannot create.
        @Test
        void k7_aLeaderPartitionedBeforeItsCommit_createsNothing_andTheSuccessorHeals() {
            var world = new ProvisioningWorld();

            world.partitionInsteadOfKillMs = 30_000L;
            run(world, 4, KillPoint.BEFORE_RESERVATION_COMMIT, FORTY_MINUTES);

            assertThat(world.cells).contains("K7");
            assertThat(creates(world)).isEqualTo(1);
            assertConverged(world);
        }

        /// K8: the provider is down across a leader change; creates fail, reservations are kept, and the cluster heals once it is back.
        @Test
        void k8_aProviderOutageSpanningALeaderChange_isHealedAfterwards_withoutDuplicates() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.run(10_000);
            world.provider.outage = true;
            world.killLeader();
            world.run(90_000);
            world.provider.outage = false;
            world.run(FORTY_MINUTES);

            assertThat(world.cells).contains("K8");
            assertConverged(world);
        }

        /// K9: a server is deleted outside Aether after the leader change, while its reservation is live. Its id is never created again.
        @Test
        void k9_aServerDeletedFromOutside_acrossALeaderChange_isNeverRecreatedUnderItsName() {
            for (var point : new KillPoint[] {KillPoint.AFTER_REPLY_BEFORE_OBSERVED, KillPoint.AFTER_CALL_BEFORE_REPLY}) {
                var world = new ProvisioningWorld();

                world.start(4);
                world.killLeaderAt(point);
                world.run(25_000);
                var victims = world.provider.servers().stream().map(ModelComputeProvider.Server::name).toList();

                victims.forEach(world::externalDelete);
                world.run(FORTY_MINUTES);

                assertThat(world.cells).as("after " + point).contains("K9");
                assertThat(victims).isNotEmpty();
                victims.forEach(name -> assertThat(world.provider.calls("create").stream().filter(call -> call.name().equals(name)).count())
                                            .as("creates of the deleted " + name + " after " + point).isEqualTo(1));
                assertConverged(world);
            }
        }

        /// I5: with auto-heal disabled the model sees no automatic create and no automatic delete.
        @Test
        void i5_withAutoHealDisabled_theLogHasNoAutomaticDeleteAndNoCreate() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.leader.ctm.setAutoHealEnabled(false, "g3 I5").await();
            world.run(FORTY_MINUTES);

            assertThat(ProvisioningInvariants.i5(world)).isEmpty();
            assertThat(world.provider.calls("create")).as("auto-heal off: nothing is created either").isEmpty();
            assertThat(world.members).as("and the deficit stays open").hasSize(4);
        }
    }

    // ============================================================================================================== tripwires

    /// Each tripwire is ENABLED and asserts what today's code does; the failure message says what to do when G1 lands. The real assertion sits
    /// beside it, `@Disabled`.
    @Nested
    class Tripwires {
        private ProvisioningWorld lateVisibleCreate() {
            var world = new ProvisioningWorld();
            var first = new AtomicReference<String>();

            world.provider.fate = (name, attempt) -> {
                first.compareAndSet(null, name);

                return name.equals(first.get()) ? ModelComputeProvider.Fate.APPLIED_REPLY_LOST : ModelComputeProvider.Fate.APPLIED;
            };
            world.provider.lagOf = (name, attempt) -> name.equals(first.get()) ? 1_800_000L : null;
            world.firstServerNeverBoots = true;
            world.start(4);

            return world;
        }

        private static final long T0 = 1_000_000L;

        /// K11, today's behaviour: a create whose server is listed only after its reservation was released. The inventory sweep later ADOPTS it as
        /// a new OBSERVED reservation (so it holds a slot), it never joins, nothing deletes it, and the only trace is the CAPACITY_DISPATCH_RELEASED
        /// warning. The window in which it was live at the provider and unmapped (release -> adoption) is measured from the committed history.
        @Test
        void k11_aLateVisibleCreate_isAdoptedAfterItsReleaseWindow_neverJoins_isNeverDeleted_andRaisesTheReleasedWarning() throws InterruptedException {
            var world = lateVisibleCreate();
            var warnings = new CopyOnWriteArrayList<OperatorWarningCode>();

            world.leader.lifecycle.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> warnings.add(warning.code())));
            world.run(3_600_000L);

            for (var waited = 0; waited < 100 && warnings.isEmpty(); waited++) {
                Thread.sleep(50);
            }

            var victim = world.provider.calls("create").getFirst().name();
            var phases = world.reservationFacts.stream().filter(fact -> fact.node().id().equals(victim)).map(ProvisioningWorld.ReservationFact::phase).toList();
            var released = world.reservationFacts.stream().filter(fact -> fact.node().id().equals(victim) && fact.phase().equals("REMOVED")).findFirst().orElseThrow();
            var adopted = world.reservationFacts.stream().filter(fact -> fact.node().id().equals(victim) && fact.phase().equals("OBSERVED")).findFirst().orElseThrow();

            System.out.println("G3 K11: the late VM was live and unmapped from t=" + (released.atMs() - T0) / 1000 + "s to t=" + (adopted.atMs() - T0) / 1000 + "s ("
                               + (adopted.atMs() - released.atMs()) / 1000 + "s)");
            var dispatched = world.reservationFacts.stream().filter(fact -> fact.node().id().equals(victim) && fact.phase().equals("DISPATCHED")).findFirst().orElseThrow();

            assertThat(world.cells).contains("K11");
            assertThat(released.atMs() - dispatched.atMs()).as("an uncertain create is released only after the create ceiling (10 min) and %d consecutive misses, one inventory pass (5 min) apart",
                                                               CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS)
                                                         .isGreaterThanOrEqualTo(SourceProfile.DEFAULT_REPLACEMENT_CEILING.millis() + (CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS - 1) * 300_000L);
            assertThat(phases).as("TRIPWIRE K11: released, then adopted by the sweep once listed. When G1-d lands (sweep: record absent -> operator event, never an adoption of a "
                                  + "never-joined VM into the ledger) revisit this test").containsSubsequence("DISPATCHED", "REMOVED", "OBSERVED");
            assertThat(world.nonMemberServers()).as("TRIPWIRE K11: the VM never joins and stays").extracting(ModelComputeProvider.Server::name).contains(victim);
            assertThat(world.provider.calls("delete")).as("TRIPWIRE K11: nothing deletes it").isEmpty();
            assertThat(warnings).as("TRIPWIRE K11: the only trace today").contains(OperatorWarningCode.CAPACITY_DISPATCH_RELEASED);
            assertThat(ProvisioningInvariants.i4(world)).as("I4 at the end of the run: green, because the sweep adopted it").isEmpty();
        }

        /// K5 / I6 delete arm: a create whose reply was lost is reaped as a dropped replacement, and the reaped server held an OBSERVED
        /// reservation. G1-d's rule is that nothing automatic deletes such a server.
        @Test
        void k5_aLostReplyServer_isDeletedByTheDroppedReplacementReap() {
            var world = lostReplyWorld();

            world.run(FORTY_MINUTES);

            assertThat(ProvisioningInvariants.i6Delete(world))
                .as("TRIPWIRE I6 delete arm: today the dropped-replacement reap (#1111) deletes the server of a lost-reply create, whose reservation is live. When G1-d "
                    + "lands (resolution by label, delete only through the gate for TERMINATED), delete this assertion and enable k5_i6_noAutomaticDeleteOfAReservedServer")
                .hasSize(1);
        }

        @Test
        @Disabled("enable with G1-d: a server whose reservation is DISPATCHED/OBSERVED is never deleted automatically")
        void k5_i6_noAutomaticDeleteOfAReservedServer() {
            var world = lostReplyWorld();

            world.run(FORTY_MINUTES);

            assertThat(ProvisioningInvariants.i6Delete(world)).isEmpty();
        }

        private ProvisioningWorld lostReplyWorld() {
            var world = new ProvisioningWorld();
            var calls = new AtomicInteger();

            world.provider.fate = (name, attempt) -> calls.getAndIncrement() == 0 ? ModelComputeProvider.Fate.APPLIED_REPLY_LOST : ModelComputeProvider.Fate.APPLIED;
            world.start(4);

            return world;
        }

        /// K10 / AP-5: a server the core never recorded (made by an operator-side apply) that has not joined. The activation replay lists
        /// it at two reads one provisioning window apart and deletes it. The window here is 60 ms of REAL time (the CTM's sweep timers are
        /// real), standing for the production 60 s.
        @Test
        void k10_anUnrecordedUnjoinedServer_isDeletedByTheActivationReplay_afterTwoReads() throws InterruptedException {
            var world = unjoinedApplyCreatedServer();

            awaitDelete(world);

            assertThat(world.provider.calls("delete").stream().filter(call -> call.name().equals(APPLY_NODE) && call.outcome().startsWith("deleted")).count())
                .as("TRIPWIRE AP-5: today the core sweep deletes an unrecorded, unjoined server after two reads. When G1-d lands (record absent -> grace T_join, event, never "
                    + "delete) delete this assertion and enable k10_ap5_aServerYoungerThanTheJoinGraceIsNotDeleted")
                .isEqualTo(1);
        }

        @Test
        @Disabled("enable with G1-d (AP-5): an unrecorded server younger than T_join is neither announced nor deleted, and older is announced, never deleted")
        void k10_ap5_aServerYoungerThanTheJoinGraceIsNotDeleted() throws InterruptedException {
            var world = unjoinedApplyCreatedServer();

            awaitDelete(world);

            assertThat(world.provider.calls("delete")).isEmpty();
        }

        private static final String APPLY_NODE = "aether-test-cluster-node-applycreated";

        private ProvisioningWorld unjoinedApplyCreatedServer() {
            var world = new ProvisioningWorld(true);

            world.plantCreate(APPLY_NODE);
            world.time.advance(3_000);
            world.start(5);

            return world;
        }

        private void awaitDelete(ProvisioningWorld world) throws InterruptedException {
            var deadline = System.currentTimeMillis() + 10_000L;

            while (System.currentTimeMillis() < deadline && world.provider.calls("delete").isEmpty()) {
                Thread.sleep(50);
                world.run(1_000);
            }
        }

        /// I1 under G1: no `NodeIdentityKey` intent exists, and the creates are guarded by the capacity reservation instead.
        @Test
        void i1_noNodeIdentityIntentExists_theReservationIsTheOnlyRecordBeforeACreate() {
            var world = killedAt(KillPoint.NONE);

            assertThat(keyKindNames())
                .as("TRIPWIRE: no NodeIdentityKey kind exists. Under O1 delete this tripwire; under O2 enable i1_everyCreateFollowsACommittedNodeIdentityIntent")
                .doesNotContain("NodeIdentityKey");
            Map<?, ?> committed = world.store.snapshot();

            assertThat(committed.keySet().stream().map(key -> key.getClass().getSimpleName()).toList())
                .as("and no such record was committed").doesNotContain("NodeIdentityKey");
        }

        @Test
        @Disabled("enable under owner choice O2: every create is preceded by a committed NodeIdentityKey in INTENDED state")
        void i1_everyCreateFollowsACommittedNodeIdentityIntent() {
            var world = killedAt(KillPoint.NONE);
            Map<?, ?> committed = world.store.snapshot();
            var intents = committed.keySet().stream().filter(key -> key.getClass().getSimpleName().equals("NodeIdentityKey")).map(Object::toString).toList();

            world.provider.calls("create").forEach(call -> assertThat(intents).as("intent for " + call.name()).anyMatch(intent -> intent.contains(call.name())));
        }

        private Set<String> keyKindNames() {
            var names = new java.util.TreeSet<String>();

            collect(AetherKey.class, names);

            return names;
        }

        private void collect(Class<?> type, Set<String> names) {
            for (var nested : type.getDeclaredClasses()) {
                names.add(nested.getSimpleName());
                collect(nested, names);
            }
        }
    }

    // ===================================================================================================== planted violations

    /// The instrument check: each invariant must go red when its violation is planted into an otherwise clean world.
    @Nested
    class PlantedViolations {
        /// I1 red: a lifecycle that skips the capacity layer's `reserveBound`, so creates are sent with no committed reservation.
        @Test
        void i1_aLifecycleThatSkipsReserveBound_createsWithNoCommittedReservation() {
            var world = new ProvisioningWorld();

            world.bypassCapacityLayer = true;
            world.start(4);
            world.run(FORTY_MINUTES);

            assertThat(creates(world)).isGreaterThan(0);
            assertThat(ProvisioningInvariants.i1(world)).isNotEmpty();
        }

        /// I2 red: the same bypass plus a kill after the call - the successor knows nothing of the unreserved server and over-provisions.
        @Test
        void i2_aLifecycleThatSkipsReserveBound_overProvisionsAcrossALeaderKill() {
            var world = new ProvisioningWorld();

            world.bypassCapacityLayer = true;
            run(world, 4, KillPoint.AFTER_CALL_BEFORE_REPLY, FORTY_MINUTES);

            assertThat(ProvisioningInvariants.i2(world)).isNotEmpty();
        }

        /// I3 red: a provider that never answers a listing leaves the reservation DISPATCHED for ever.
        @Test
        void i3_aProviderThatNeverAnswers_leavesTheReservationDispatched() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.after(10_000L, () -> world.provider.outage = true);
            world.run(3_600_000L);

            assertThat(ProvisioningInvariants.i3(world)).isNotEmpty();
        }

        /// I5 red: a sweep that deletes with auto-heal disabled.
        @Test
        void i5_aSweepThatDeletesWithAutoHealDisabled() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.leader.ctm.setAutoHealEnabled(false, "g3 plant").await();
            world.plantCreate("aether-test-cluster-node-stray");
            world.run(10_000);
            world.plantDelete("aether-test-cluster-node-stray");

            assertThat(ProvisioningInvariants.i5(world)).isNotEmpty();
        }

        /// I6 create arm red: a recovery that re-issues a create for a name that already has one.
        @Test
        void i6_aRecoveryThatReCreatesAnIdThatWasAlreadyCreated() {
            var world = killedAt(KillPoint.AFTER_CALL_BEFORE_REPLY);
            var name = world.provider.calls("create").getFirst().name();

            assertThat(ProvisioningInvariants.i6(world)).isEmpty();
            world.plantCreate(name);
            world.run(5_000);

            assertThat(ProvisioningInvariants.i6(world)).anyMatch(violation -> violation.contains("creates for " + name));
        }

        /// I6 duplicate-name red: a provider that admits a duplicate name, and a recovery that re-creates into it.
        @Test
        void i6_aProviderThatAdmitsADuplicateName_holdsTwoServersUnderOneName() {
            var world = new ProvisioningWorld();

            world.provider.uniqueNames = false;
            run(world, 4, KillPoint.AFTER_CALL_BEFORE_REPLY, 120_000L);
            var name = world.provider.servers().getFirst().name();

            world.plantCreate(name);
            world.run(5_000);

            assertThat(ProvisioningInvariants.i6(world)).anyMatch(violation -> violation.contains("live servers named " + name));
        }

        /// I6 delete arm red: a sweep that deletes the server of a live reservation.
        @Test
        void i6_aSweepThatDeletesALiveReservationsServer() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.run(30_000);
            var reserved = world.provider.servers().stream().filter(server -> world.reservationPhase(new NodeId(server.name())) != null).findFirst().orElseThrow().name();

            world.plantDelete(reserved);

            assertThat(ProvisioningInvariants.i6Delete(world)).anyMatch(violation -> violation.contains(reserved) && violation.contains(":reserved-"));
        }

        /// I6 delete arm red: a sweep that deletes a member.
        @Test
        void i6_aSweepThatDeletesAMember() {
            var world = new ProvisioningWorld();

            world.start(4);
            world.run(120_000);
            var member = world.provider.servers().stream().filter(server -> world.members.contains(new NodeId(server.name()))).findFirst().orElseThrow().name();

            world.plantDelete(member);

            assertThat(ProvisioningInvariants.i6Delete(world)).anyMatch(violation -> violation.contains(member) && violation.contains(":member"));
        }
    }

    // ================================================================================================================ seeded

    @Nested
    class SeededRuns {
        private static final int SEEDS = 200;
        private static final List<String> CELLS = List.of("K1", "K2", "K3", "K4", "K5", "K6", "K7", "K8", "K9", "K11");

        /// 200 seeded schedules. On failure the seed is in the message: `new ProvisioningSchedule(seed).run()` replays it exactly.
        @Test
        void twoHundredSchedules_keepTheInvariantsThatHoldToday_andReachEveryCell() {
            var started = System.nanoTime();
            var coverage = new TreeMap<String, Integer>();
            var failures = new LinkedHashMap<Long, List<String>>();

            for (var seed = 1L; seed <= SEEDS; seed++) {
                var world = new ProvisioningSchedule(seed).run();
                var violations = new ArrayList<String>();

                world.cells.forEach(cell -> coverage.merge(cell, 1, Integer::sum));
                ProvisioningInvariants.i1(world).forEach(violation -> violations.add("I1 " + violation));
                ProvisioningInvariants.i3(world).forEach(violation -> violations.add("I3 " + violation));
                ProvisioningInvariants.i6(world).forEach(violation -> violations.add("I6 " + violation));

                if (!world.cells.contains("K11")) {
                    ProvisioningInvariants.i2(world).forEach(violation -> violations.add("I2 " + violation));
                }

                if (!violations.isEmpty()) {
                    failures.put(seed, violations);
                }
            }

            var elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            System.out.println("G3 seeded runs: " + SEEDS + " schedules in " + elapsedMs + " ms; cell coverage " + coverage);
            assertThat(failures).as("invariant violations by seed (replay: new ProvisioningSchedule(seed).run())").isEmpty();
            assertThat(coverage.keySet()).as("NOT EXPLORING: every cell must be reached in %d seeds, reached %s", SEEDS, coverage).containsAll(CELLS);
            assertThat(elapsedMs).as("CI budget").isLessThan(120_000L);
        }

        /// K11 runs are excluded from the I2 check above: the late-visible VM is a leftover the replacement then coexists with, which is I4's
        /// tripwire seen from the count side. This pins that the exclusion hides nothing else: every I2 violation in a K11 run is that.
        @Test
        void i2_isRedOnlyInRunsThatReachedK11() {
            var redWithoutK11 = new ArrayList<Long>();

            for (var seed = 1L; seed <= SEEDS; seed++) {
                var world = new ProvisioningSchedule(seed).run();

                if (!ProvisioningInvariants.i2(world).isEmpty() && !world.cells.contains("K11")) {
                    redWithoutK11.add(seed);
                }
            }

            assertThat(redWithoutK11).as("seeds with an I2 violation and no K11").isEmpty();
        }

        /// Seeded runs are deterministic: a seed replays to the same call log (names are random ULIDs, so compare by order of first appearance).
        @Test
        void aSeed_replaysToTheSameCallLog() {
            for (var seed = 1L; seed <= 25L; seed++) {
                var first = shape(new ProvisioningSchedule(seed).run());
                var second = shape(new ProvisioningSchedule(seed).run());
                var at = 0;

                while (at < Math.min(first.size(), second.size()) && first.get(at).equals(second.get(at))) {
                    at++;
                }

                assertThat(first).as("seed " + seed + " diverges at entry " + at + ": " + (at < first.size() ? first.get(at) : "-") + " vs " + (at < second.size() ? second.get(at) : "-")).isEqualTo(second);
            }
        }

        /// The call log and the committed reservation history without node names (random ULIDs) and without the order of entries in the same
        /// millisecond (their order follows the ULIDs): what a seed must reproduce is what happened and when.
        private List<String> shape(ProvisioningWorld world) {
            var shape = new ArrayList<String>();

            world.provider.callLog().forEach(call -> shape.add(call.atMs() + " " + call.op() + " " + call.outcome()));
            world.reservationFacts.forEach(fact -> shape.add(fact.atMs() + " reservation " + fact.phase()));
            world.joinLog.forEach(join -> shape.add("join " + join.replaceAll("[0-9a-z]{26}", "ID")));
            shape.sort(null);

            return shape;
        }
    }
}
