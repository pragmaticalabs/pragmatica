// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.nio.file.Files;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.pair;
import static org.assertj.core.api.Assertions.assertThat;


/// Departure (spec C17, C17b, R-4.3.0, R-4.3.3, R-4.3.5): the fenced DEPARTED record and what follows it, on the real
/// applier.
class DepartureTest {
    private static final long TOKEN_2 = 0x2222L;

    /// core-2 holds an ACTIVE instance of ORDERS (goal and row at gen 1), and routes for it.
    private MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w.target(ORDERS, 1, "CORE_ONLY")
                .directive(CORE_2,
                           ActivationDirectiveValue.core())
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .routes(CORE_2, ORDERS);
    }

    private static boolean touches(ClusterEffect.Submit submit, Class<?> keyType) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> keyType.isInstance(mutation.key()));
    }

    // NT-38
    // Red when: C17 is not ONE transaction carrying the fenced DEPARTED Put (expected = the observed record), the desired
    // removes and the route removes; or it contains an observed removal or a directive mutation.
    @Test
    void selfShutdown_isOneFencedTransaction_withoutObservedOrDirectiveMutations() {
        var w = world().activate();

        w.h.clearLog();
        w.h.deliver(new ClusterInput.SelfShutdown(CORE_2));
        var departures = w.h.submits(SubmitKind.DEPARTURE);

        assertThat(departures).hasSize(1);
        var mutations = departures.getFirst().transaction().mutations();

        assertThat(mutations.getFirst().key()).isEqualTo(new NodeDepartureKey(CORE_2));
        assertThat(mutations.getFirst().expected().isEmpty()).as("expects the observed record: absent").isTrue();
        assertThat(mutations.getFirst().replacement().map(v -> ((NodeDepartureValue) v).state())).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(mutations.getFirst().replacement().map(v -> ((NodeDepartureValue) v).cause())).isEqualTo(Option.some(Option.some(DepartureCause.SELF_SHUTDOWN)));
        assertThat(mutations).extracting(m -> m.key())
                  .contains(new SliceDesiredKey(CORE_2, ORDERS),
                            new NodeRoutesKey(CORE_2, ORDERS));
        assertThat(touches(departures.getFirst(), SliceObservedKey.class)).isFalse();
        assertThat(touches(departures.getFirst(), ActivationDirectiveKey.class)).isFalse();
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).as("the directive waits for source-confirmed termination")
                  .isTrue();
    }

    // NT-38 (fence arm)
    // Red when: the Put does not expect the ADMITTED value it observed.
    @Test
    void departure_expectsTheAdmittedRecord_itObserved() {
        var w = world().record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null).activate();

        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var put = w.h.submits(SubmitKind.DEPARTURE).getFirst().transaction().mutations().getFirst();

        assertThat(put.expected().map(v -> ((NodeDepartureValue) v).state())).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // NT-79
    // Red when: a zero-footprint departure submits an empty transaction, anything beyond the DEPARTED Put, or a directive
    // mutation.
    @Test
    void workerLeave_withNoFootprint_isTheDepartedPutAlone_andKeepsTheDirective() {
        var w = MbWorld.world(CORE_1);
        var worker = node("worker-1");

        w.admit(worker, 0x4444L);
        w.community("c1", 5, CommunityState.ACTIVE).directive(worker, Fixtures.worker("c1")).activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        var submits = w.h.submits();

        assertThat(submits).hasSize(1);
        assertThat(submits.getFirst().transaction().mutations()).hasSize(1);
        assertThat(submits.getFirst().kind()).isEqualTo(SubmitKind.DEPARTURE);
        assertThat(w.present(new ActivationDirectiveKey(worker))).isTrue();
        assertThat(w.recordOf(worker).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.recordOf(worker).map(NodeDepartureValue::epoch)).as("names epochOf(n): no record yet, so 0 (the epoch is leader-assigned, MQ-4)")
                  .isEqualTo(Option.some(0L));
    }

    // NT-92 (a, b)
    // Red when: C17b runs before the DEPARTED record commits; or a C17b with a stale expected value is applied.
    @Test
    void cleanup_waitsForTheCommittedRecord_andANewerRowIsNotRemovedByAStaleExpectation() {
        var w = world().record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null).activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var departure = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no C17b before the DEPARTED record commits").isEmpty();
        w.h.applyHeld(departure.correlationId());
        w.h.drain();
        var cleanup = w.h.submits(SubmitKind.CLEANUP);

        assertThat(cleanup).hasSize(1);
        new NodeSim(w.h, CORE_2, process(TOKEN_2)).writes(ORDERS, SliceObservedState.DEACTIVATING, gen(1));
        w.h.drain();
        var verdict = w.h.applyHeld(cleanup.getFirst().correlationId());

        assertThat(verdict).as("the applier refuses a removal at a value that moved")
                  .isInstanceOf(Settlement.Refused.class);
        assertThat(w.stored(new SliceObservedKey(CORE_2, ORDERS)).map(v -> ((SliceObservedValue) v).state())).isEqualTo(Option.some(SliceObservedState.DEACTIVATING));
        w.h.settleHeld(cleanup.getFirst(), verdict);
        var stale = cleanup.getFirst()
                           .transaction()
                           .mutations()
                           .stream()
                           .filter(m -> m.key() instanceof SliceObservedKey)
                           .findFirst()
                           .orElseThrow()
                           .expected();
        var again = w.h.submits(SubmitKind.CLEANUP)
                       .stream()
                       .skip(1)
                       .flatMap(s -> s.transaction()
                                      .mutations()
                                      .stream())
                       .filter(m -> m.key() instanceof SliceObservedKey)
                       .filter(m -> m.expected()
                                     .equals(stale))
                       .toList();

        assertThat(again).as("the stale expectation is not resubmitted").isEmpty();
    }

    // NT-92 (c)
    // Red when: C17b is built for a superseded (joined, same-process) record.
    @Test
    void cleanup_isNotBuilt_forASupersededRecord_andTheRecordIsRewrittenAsAdmitted() {
        var w = world().record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED);
        var joined = new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, TOKEN_2));

        w.h.deliverTogether(new ClusterInput.Activate(), joined);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).isEmpty();
        assertThat(w.counts(CORE_2)).as("a superseded DEPARTED leaves the node counted").isTrue();
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).as("replaced by ADMITTED(e_d + 1)")
                  .isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::epoch)).isEqualTo(Option.some(2L));
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isTrue();
    }

    // NT-99 (witness arm)
    // Red when: C17b applies, or is resubmitted, after its record moved (DEPARTED replaced by ADMITTED).
    @Test
    void cleanup_isRefusedWhenItsRecordMoved_andNotResubmitted() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getFirst();
        var departed = w.recordOf(CORE_2).orElse(() -> Option.none()).or((NodeDepartureValue) null);
        var admitted = new NodeDepartureValue(DepartureState.ADMITTED, 2L, process(TOKEN_2), Option.none(), w.h.wallMs);

        w.admit(CORE_2, TOKEN_2);
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        var before = w.h.submits(SubmitKind.ADMISSION);

        assertThat(before).as("the departed record is replaced through a fenced ADMITTED write").isNotEmpty();
        w.h.applyHeld(before.getLast().correlationId());
        w.h.drain();
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("the row survives").isTrue();
        var count = w.h.submits(SubmitKind.CLEANUP).size();

        w.h.settleHeld(cleanup, verdict);
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no resubmission after the record moved").hasSize(count);
        assertThat(departed).isNotNull();
        assertThat(admitted.state()).isEqualTo(DepartureState.ADMITTED);
    }

    // NT-99 (write arm)
    // Red when: a C17b built on a row the node has since rewritten is applied.
    @Test
    void cleanup_writeArm_aRewrittenRowIsKept() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getFirst();

        new NodeSim(w.h, CORE_2, process(TOKEN_2)).writes(ORDERS, SliceObservedState.DEACTIVATING, gen(1));
        w.h.applyHeld(cleanup.correlationId());
        assertThat(w.stored(new SliceObservedKey(CORE_2, ORDERS)).map(v -> ((SliceObservedValue) v).state())).isEqualTo(Option.some(SliceObservedState.DEACTIVATING));
    }

    // NT-111
    // Red when: a desired key of a departed node with no observation survives C17 and step 10 (and, for an inferred
    // departure, C17b).
    @Test
    void departed_desiredKeyWithNoObservation_doesNotSurvive() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.target(ORDERS, 1, "CORE_ONLY");
        w.h.seedQuietly(new SliceDesiredKey(CORE_2, ORDERS), desired(SliceGoal.LOADED, gen(1)));
        w.activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).as("removed by C17 in the DEPARTED transaction")
                  .isFalse();
    }

    // NT-111 (inferred arm)
    // Red when: after a C17s record (the Put alone) the desired key is left behind by C17b.
    @Test
    void inferredDeparture_desiredKeyIsRemovedByC17b() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.target(ORDERS, 1, "CORE_ONLY");
        w.h.seedQuietly(new SliceDesiredKey(CORE_2, ORDERS), desired(SliceGoal.LOADED, gen(1)));
        w.directive(CORE_2, ActivationDirectiveValue.core());
        w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1))));
        w.activate();
        w.h.advance(ms(bounds().coreRemoval()) + 31_000L);
        assertThat(w.recordOf(CORE_2).flatMap(NodeDepartureValue::cause)).isEqualTo(Option.some(DepartureCause.STALE));
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).isFalse();
    }

    // NT-112 (cluster half)
    // Red when: an idle falsely-departed node keeps its rows past C17b, or the slice is not re-placed.
    @Test
    void falselyDeparted_idleNode_losesItsRows_andItsSliceIsReplacedElsewhere() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.drain();
        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).isFalse();
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).isFalse();
        var replaced = List.of(CORE_1, CORE_3)
                           .stream()
                           .filter(node -> w.present(new SliceDesiredKey(node, ORDERS)))
                           .toList();

        assertThat(replaced).as("one new goal for the affected instance").hasSize(1);
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).as("identity persists").isTrue();
    }

    // NT-106 (a)
    // Red when: the directive goes before C17b and the record is removed while rows remain apart from the rows' own removal;
    // R-4.3.3 removes record and rows in ONE transaction.
    @Test
    void directiveRemoval_ofADepartedNode_removesRecordAndRowsTogether() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        w.h.clearLog();
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL);

        assertThat(removal).hasSize(1);
        assertThat(removal.getFirst().transaction().mutations()).extracting(m -> m.key())
                  .contains(new NodeDepartureKey(CORE_2),
                            new SliceObservedKey(CORE_2, ORDERS));
        assertThat(w.h.submits(SubmitKind.GC).stream().anyMatch(s -> touches(s, NodeDepartureKey.class))).as("the record is not collected on its own while rows remain")
                  .isFalse();
    }

    // NT-106 (b) (MQ-4: R-4.3.5 (b) is withdrawn)
    // Red when: a STALE record with no residue is garbage-collected by the reconcile: a record leaves only through R-4.3.3.
    @Test
    void staleRecord_withNoResidue_isKept() {
        var w = MbWorld.world(CORE_1);
        var worker = node("worker-9");

        w.record(worker, DepartureState.DEPARTED, 1L, 0x9999L, DepartureCause.STALE).activate();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.advance(w.h.settings.timing().tick() * 3);
        assertThat(w.recordOf(worker).isPresent()).isTrue();
        assertThat(w.h.submits(SubmitKind.GC)).as("nothing is collected").isEmpty();
    }

    // NT-106 (c)
    // Red when: a healed seed core (ADMITTED record, no directive, no rows, current member) loses its record in step 10.
    @Test
    void admittedRecord_ofACurrentMember_isKept() {
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.record(CORE_1, DepartureState.ADMITTED, 2L, 0x1111L, null).activate();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.recordOf(CORE_1).isPresent()).isTrue();
    }

    // NT-116 (tripwire)
    // Red when: production code constructs or emits NodeDecommissioned.
    @Test
    void nodeDecommissioned_isNeverProduced() throws Exception {
        var root = Tripwire.repositoryRoot();

        assertThat(Tripwire.constructions(root, "NodeDecommissioned")).as("production constructors of NodeDecommissioned")
                  .isEmpty();
        assertThat(Tripwire.mentions(root.resolve("aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/fsm2"),
                                     "NodeDecommissioned")).as("the cluster FSM names it nowhere")
                  .isEmpty();
    }

    // NT-116 (positive control)
    // Red when: the scan cannot see a construction (it would report an all-clear on any tree).
    @Test
    void nodeDecommissionedTripwire_seesAConstruction_inAPlantedFile() throws Exception {
        var dir = Files.createTempDirectory("tripwire");

        Files.writeString(dir.resolve("Planted.java"),
                          "class Planted { Object x = new MembershipDecision.NodeDecommissioned(null, null, 0, 0); }");
        assertThat(Tripwire.constructions(dir, "NodeDecommissioned")).hasSize(1);
    }

    // NT-119
    // Red when: a STALE core counted again keeps its DEPARTED record, or a core re-admitted by membership is not
    // re-admitted by the FSM.
    @Test
    void staleCore_readmittedByMembership_isReplacedByAdmitted_andCounts() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.STALE)
         .directive(CORE_2,
                    ActivationDirectiveValue.core())
         .activate();
        assertThat(w.counts(CORE_2)).as("departed: not counted").isFalse();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, TOKEN_2)));
        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.counts(CORE_2)).isTrue();
    }

    private static List<GovernorAnnouncer> unused() {
        return List.of();
    }
}
