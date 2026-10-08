package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.cluster.ProvisionDisposition;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// #1543 E1 (v-2008 round 2): the reconciler's environment, the drain outcome mapping and the owner-gated announcer against a fake
/// node that records every command the leader submits. Pins what Ember cannot: it never has a second leader, never refuses a drain
/// for a reason other than the floor, never lets a provider hang, and has one event feed that cannot show a duplicate.
class NodeReplacementEnvTest {
    private static final NodeId SELF = new NodeId("self-1");
    private static final NodeId OLD = new NodeId("core-old");
    private static final NodeId NEW = new NodeId("core-new");

    private final List<KVCommand<AetherKey>> commands = new ArrayList<>();
    private final Map<AetherKey, AetherValue> stored = new HashMap<>();
    private final Map<NodeId, String> states = new HashMap<>();
    private final NodeReplacementIndex index = NodeReplacementIndex.nodeReplacementIndex();
    private final ClusterTopologyManager ctm = mock(ClusterTopologyManager.class);
    private final AtomicBoolean oldDraining = new AtomicBoolean(false);
    private volatile long now = 1_000L;
    private volatile NodeReplacementWiring.DrainOutcome drainAnswer = NodeReplacementWiring.DrainOutcome.admitted();
    private NodeReplacementWiring.Wiring wiring;

    NodeReplacementEnvTest() {
        states.put(OLD, "Member");
        when(ctm.provisionReplacement(any(), any(), any(), any(), any())).thenReturn(Promise.success(ProvisionDisposition.dispatched()));
        when(ctm.drainNode(any(), any())).thenAnswer(call -> Promise.unitPromise());
        when(ctm.reapRetired(any(), any(), anyBoolean())).thenAnswer(call -> Promise.unitPromise());
        when(ctm.instanceListed(any(), any())).thenAnswer(call -> Promise.success(true));
        wiring = NodeReplacementWiring.wire(inputs(NodeReplacementPlanner.Timings.parse("60000,60000,60000,60000,0,60000,60000")));
    }

    @SuppressWarnings("unchecked")
    private NodeReplacementWiring.Inputs inputs(NodeReplacementPlanner.Timings timings) {
        var fsm = mock(MembershipFsm.class);

        when(fsm.memberStates()).thenAnswer(call -> Map.copyOf(states));
        when(fsm.memberDescriptor(any())).thenReturn(Option.some(new MemberDescriptor(Option.none(), "core", "hetzner")));
        var store = mock(KVStore.class);

        when(store.getTyped(any(), any())).thenAnswer(call -> LeaderKey.INSTANCE.equals(call.getArgument(0))
                                                              ? Option.some(new LeaderValue(SELF, 1))
                                                              : Option.option(stored.get(call.getArgument(0))));
        when(store.get(any())).thenAnswer(call -> Option.option(stored.get(call.getArgument(0))));

        return new NodeReplacementWiring.Inputs(SELF,
                                                () -> true,
                                                store,
                                                submitted -> {
                                                    commands.addAll(submitted);

                                                    return Promise.success(submitted.stream()
                                                                                    .map(command -> (Object) new KVCommand.TransactionResult(((KVCommand.LeaderTransaction<?, ?>) command).transactionId(),
                                                                                                                                             true))
                                                                                    .toList());
                                                },
                                                index,
                                                () -> fsm,
                                                Option::none,
                                                Option::none,
                                                Set::of,
                                                Set::of,
                                                node -> "",
                                                ctm,
                                                node -> Promise.success(drainAnswer),
                                                node -> oldDraining.get(),
                                                () -> "fresh",
                                                node -> false,
                                                Set::of,
                                                () -> Integer.MAX_VALUE,
                                                OperatorWarningSink.logOnly(),
                                                () -> now,
                                                timings);
    }

    @SuppressWarnings("unchecked")
    private NodeReplacementValue committedRecord() {
        assertThat(commands).as("exactly one command").hasSize(1);
        var transaction = (KVCommand.LeaderTransaction<AetherKey, AetherValue>) commands.getFirst();

        return (NodeReplacementValue) transaction.mutations()
                                                 .stream()
                                                 .filter(mutation -> mutation.key().equals(new AetherKey.NodeReplacementKey(OLD)))
                                                 .findFirst()
                                                 .orElseThrow()
                                                 .replacement()
                                                 .unwrap();
    }

    private void record(NodeReplacementPhase phase, long deadline) {
        index.put(new AetherKey.NodeReplacementKey(OLD), new NodeReplacementValue(NEW, "core", phase, deadline));
    }

    // ---- N1: a drain under way is not a drain nobody asked for --------------------------------------------------------

    /// The old node is reported DRAINING (by the leader's commanded set or by the node itself, which a NEW leader still sees): the
    /// drain state is IN_PROGRESS, so a replacement that dies now keeps both nodes. Without it the new leader reads
    /// NOT_REQUESTED and would swap the seat back under a node that is already draining.
    @Test
    void aDrainReportedUnderWay_isInProgress_soALostReplacementKeepsBoth_notReverts() {
        record(NodeReplacementPhase.DRAINING_OLD, 999_999L);
        states.put(NEW, "Dead");
        oldDraining.set(true);
        wiring.reconciler().reconcile().await();

        assertThat(committedRecord().phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
    }

    @Test
    void withoutAReportedDrain_aLostReplacementIsReverted_notKept() {
        record(NodeReplacementPhase.DRAINING_OLD, 999_999L);
        states.put(NEW, "Dead");
        wiring.reconciler().reconcile().await();

        assertThat(committedRecord().phase()).isEqualTo(NodeReplacementPhase.REVERTING);
    }

    /// Only the slice floor is a block an operator can act on; every other refusal is retried quietly and remembered.
    @Test
    void drainOutcomeOf_blocksOnlyOnTheSliceFloor() {
        var floor = new NodeLifecycleRoutes.SliceFloorBreached("core-old", "drain", List.of());

        assertThat(NodeReplacementWiring.drainOutcomeOf(Promise.<Unit> unitPromise()).await().unwrap()).isEqualTo(NodeReplacementWiring.DrainOutcome.admitted());
        var blocked = NodeReplacementWiring.drainOutcomeOf(floor.<Unit> promise()).await().unwrap();

        assertThat(blocked.blockedBy()).isEqualTo(floor.message());
        assertThat(blocked.refusedFor()).isEmpty();
        var other = NodeReplacementWiring.drainOutcomeOf(Causes.cause("Cannot drain node core-old from SYNCING (must be READY)").<Unit> promise()).await().unwrap();

        assertThat(other.blockedBy()).as("not a block").isEmpty();
        assertThat(other.refusedFor()).isEqualTo("Cannot drain node core-old from SYNCING (must be READY)");
    }

    /// S1: a drain refused for a non-floor reason leaves the replacement kept-both with the refusal in its reason.
    @Test
    void keptBoth_afterARefusedDrain_carriesTheRefusalInTheCommittedReason() {
        drainAnswer = NodeReplacementWiring.DrainOutcome.pending("Cannot drain node core-old from SYNCING (must be READY)");
        states.put(NEW, "Member");
        record(NodeReplacementPhase.DRAINING_OLD, 1_500L);
        wiring.reconciler().reconcile().await();

        assertThat(commands).as("the drain was asked for and refused: nothing is committed yet").isEmpty();

        now = 2_000L;
        wiring.reconciler().reconcile().await();
        var reason = committedRecord();

        assertThat(reason.phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(reason.reason()).contains("drain refused: Cannot drain node core-old from SYNCING (must be READY)");
    }

    // ---- B4: the cross-leader skip ------------------------------------------------------------------------------------

    /// A second leader that finds the record still PROVISIONING also finds the first leader's reservation for the same id: it must
    /// not provision the id again.
    @Test
    void aSecondLeader_doesNotProvisionAnIdThatAlreadyHoldsALiveReservation() {
        stored.put(new AetherKey.CapacityReservationKey(NEW), new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.DISPATCHED));
        record(NodeReplacementPhase.PROVISIONING, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm, never()).provisionReplacement(any(), any(), any(), any(), any());
        assertThat(committedRecord().phase()).as("it takes the dispatched reservation as the provision having happened").isEqualTo(NodeReplacementPhase.JOINING);
    }

    @Test
    void aRefusedReservation_failsTheProvisioning_andNoReservationProvisionsNormally() {
        stored.put(new AetherKey.CapacityReservationKey(NEW), new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.RELEASED));
        record(NodeReplacementPhase.PROVISIONING, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm, never()).provisionReplacement(any(), any(), any(), any(), any());
        assertThat(committedRecord().phase()).as("a refused reservation is a refused provision").isEqualTo(NodeReplacementPhase.ROLLED_BACK);

        commands.clear();
        stored.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD));
        record(NodeReplacementPhase.PROVISIONING, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm).provisionReplacement(any(), any(), any(), any(), any());
    }

    // ---- #1543: DONE only after the provider's instance is confirmed gone ----------------------------------------------

    /// The old node has left the cluster (Dead) and its hand-off is settled, so the cluster is already correct; the replacement is still
    /// not DONE while the termination of its instance is not confirmed, and it IS DONE the tick after the confirmation arrives.
    @Test
    void retiring_isNotDone_untilTheTerminationIsConfirmed() {
        states.put(OLD, "Dead");
        when(ctm.reapRetired(any(), any(), anyBoolean())).thenAnswer(call -> org.pragmatica.lang.utils.Causes.cause("instance of core-old: still listed at the provider after terminate").<org.pragmatica.lang.Unit> promise());
        record(NodeReplacementPhase.RETIRING_OLD, 999_999L);
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(commands).as("a refused termination commits nothing: no DONE").isEmpty();

        when(ctm.reapRetired(any(), any(), anyBoolean())).thenAnswer(call -> Promise.unitPromise());
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committedRecord().phase()).isEqualTo(NodeReplacementPhase.DONE);
    }

    /// At the deadline with the instance still not confirmed gone the record is FAILED_KEPT_BOTH, and its reason (which the operator
    /// event carries) names the instance and the last cause.
    @Test
    void retiringOverdue_withAnUnconfirmedTermination_isKeptBoth_namingTheInstanceAndTheCause() {
        states.put(OLD, "Dead");
        when(ctm.reapRetired(any(), any(), anyBoolean())).thenAnswer(call -> org.pragmatica.lang.utils.Causes.cause("instance of core-old: still listed at the provider after terminate: [i-1 Running]").<org.pragmatica.lang.Unit> promise());
        record(NodeReplacementPhase.RETIRING_OLD, 5_000L);
        wiring.reconciler().reconcile().await();
        now = 6_000L;
        wiring.reconciler().reconcile().await();

        var committed = committedRecord();

        assertThat(committed.phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(committed.reason()).contains("not confirmed terminated").contains("instance of core-old").contains("i-1 Running");
    }

    /// A rollback is ROLLED_BACK only once the replacement's instance is confirmed gone; if the termination keeps failing past the
    /// retiring budget the pair is kept, with the cause.
    @Test
    void aRollbackThatCannotTerminateTheReplacement_isKeptBoth_afterTheRetiringBudget() {
        when(ctm.reapRetired(any(), any(), anyBoolean())).thenAnswer(call -> org.pragmatica.lang.utils.Causes.cause("instance of core-new: quota").<org.pragmatica.lang.Unit> promise());
        record(NodeReplacementPhase.PROVISIONING, 500L);
        wiring.reconciler().reconcile().await();

        assertThat(commands).as("within the budget the termination is retried, nothing is rolled back").isEmpty();

        now = 62_000L;
        wiring.reconciler().reconcile().await();

        var committed = committedRecord();

        assertThat(committed.phase()).isEqualTo(NodeReplacementPhase.FAILED_KEPT_BOTH);
        assertThat(committed.reason()).contains("could not be rolled back").contains("instance of core-new: quota");
    }

    /// An old node the provider listed while it was up (before the drain) that then self-halts is confirmed gone by an empty listing:
    /// the reap is asked with seenBefore=true. A node never listed is asked with seenBefore=false, which the CTM refuses to call gone.
    @Test
    void anOldNodeListedBeforeTheDrain_isReapedAsSeen_andANeverListedOneIsNot() {
        record(NodeReplacementPhase.DRAINING_OLD, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm, atLeastOnce()).instanceListed(eq(OLD), any());

        states.put(OLD, "Dead");
        commands.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD));
        record(NodeReplacementPhase.RETIRING_OLD, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm).reapRetired(eq(OLD), any(), eq(true));

        var fresh = NodeReplacementWiring.wire(inputs(NodeReplacementPlanner.Timings.parse("60000,60000,60000,60000,0,60000,60000")));

        commands.clear();
        fresh.reconciler().reconcile().await();

        verify(ctm).reapRetired(eq(OLD), any(), eq(false));
    }

    /// A replacement that was up (the membership read it alive) and was then lost before any drain is listed while it is up, so its rollback
    /// is confirmed by an empty listing instead of waiting for an instance that already vanished: the reap is asked with seenBefore=true.
    @Test
    void aReplacementObservedWhileUp_isReapedAsSeen_whenItIsLaterRolledBack() {
        states.put(NEW, "Member");
        record(NodeReplacementPhase.CANARY, 999_999L);
        wiring.reconciler().reconcile().await();

        verify(ctm).instanceListed(eq(NEW), any());

        states.remove(NEW);
        commands.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD));
        record(NodeReplacementPhase.PROVISIONING, 500L);
        wiring.reconciler().reconcile().await();

        verify(ctm).reapRetired(eq(NEW), any(), eq(true));
    }

    // ---- B3: the owner gate -------------------------------------------------------------------------------------------

    /// Every node applies every committed record; only the owner of the cluster-events partition raises the events, and it raises
    /// EXACTLY one per transition. Two nodes see the same walk: the owner's events are the whole feed, with nothing doubled.
    @Test
    void ofTwoNodesApplyingTheSameWalk_exactlyTheOwnersEventsAreRaised_once() {
        var raised = new CopyOnWriteArrayList<OperatorWarning>();
        var sink = OperatorWarningSink.handingOffTo(raised::add);
        var owner = NodeReplacementIndex.nodeReplacementIndex();
        var follower = NodeReplacementIndex.nodeReplacementIndex();

        owner.onTransition(NodeReplacementWiring.announcer(() -> true, sink));
        follower.onTransition(NodeReplacementWiring.announcer(() -> false, sink));
        var key = new AetherKey.NodeReplacementKey(OLD);
        var started = new NodeReplacementValue(NEW, "core", NodeReplacementPhase.PROVISIONING, 0L);
        var walk = List.of(started,
                           started.advanced(NodeReplacementPhase.JOINING, 1L, ""),
                           started.advanced(NodeReplacementPhase.JOINING, 1L, "").advanced(NodeReplacementPhase.SWAPPING, 1L, ""),
                           started.advanced(NodeReplacementPhase.DONE, 1L, ""));

        for (var value : walk) {
            owner.put(key, value);
            follower.put(key, value);
        }

        awaitCount(raised, 2);
        assertThat(raised).extracting(warning -> warning.code())
                          .as("STARTED once and COMPLETED once, from the owner alone; nothing doubled, nothing from the follower")
                          .containsExactly(OperatorWarningCode.NODE_REPLACEMENT_STARTED, OperatorWarningCode.NODE_REPLACEMENT_COMPLETED);
        sleepBriefly();
        assertThat(raised).as("and nothing arrives late").hasSize(2);
    }

    @Test
    void aNodeThatDoesNotOwnTheEvents_raisesNone() {
        var raised = new CopyOnWriteArrayList<OperatorWarning>();
        var follower = NodeReplacementIndex.nodeReplacementIndex();

        follower.onTransition(NodeReplacementWiring.announcer(() -> false, OperatorWarningSink.handingOffTo(raised::add)));
        follower.put(new AetherKey.NodeReplacementKey(OLD), new NodeReplacementValue(NEW, "core", NodeReplacementPhase.PROVISIONING, 0L));
        sleepBriefly();

        assertThat(raised).isEmpty();
    }

    private static void awaitCount(List<?> list, int count) {
        for (int attempt = 0; attempt < 100 && list.size() < count; attempt++) {
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
