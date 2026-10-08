package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Refusal;
import org.pragmatica.aether.deployment.cluster.ProvisionDisposition;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
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
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// #1543 E2 (v-2042): the replacement service and the reconciler's environment against a fake node. The fake records every command
/// the leader submits, so each test states WHAT is committed and in WHICH transaction, the things Ember cannot see (its in-process
/// admission needs no reservation, its roles are all core or worker, its ids are always fresh).
class NodeReplacementServiceTest {
    private static final NodeId SELF = new NodeId("self-1");
    private static final NodeId OLD_CORE = new NodeId("core-old");
    private static final NodeId OLD_WORKER = new NodeId("worker-old");
    private static final NodeId OLD_DHT = new NodeId("dht-old");
    private static final NodeId FRESH = new NodeId("fresh-1");

    private final List<KVCommand<AetherKey>> commands = new ArrayList<>();
    private final Map<AetherKey, AetherValue> stored = new HashMap<>();
    private final Map<NodeId, String> states = new HashMap<>();
    private final Map<NodeId, MemberDescriptor> descriptors = new HashMap<>();
    private final NodeReplacementIndex index = NodeReplacementIndex.nodeReplacementIndex();
    private final ClusterTopologyManager ctm = mock(ClusterTopologyManager.class);
    private Set<NodeId> readyAll = Set.of();
    private Set<NodeId> readyAdmitted = Set.of();
    private Set<NodeId> genesis = Set.of();
    private NodeReplacementWiring.Wiring wiring;

    NodeReplacementServiceTest() {
        member(OLD_CORE, "core");
        member(OLD_WORKER, "worker");
        member(OLD_DHT, "dht");
        when(ctm.provisionReplacement(any(), any(), any(), any(), any())).thenReturn(Promise.success(ProvisionDisposition.dispatched()));
        when(ctm.drainNode(any(), any())).thenAnswer(call -> Promise.unitPromise());
        wiring = NodeReplacementWiring.wire(inputs());
    }

    private void member(NodeId id, String role) {
        states.put(id, "Member");
        descriptors.put(id, new MemberDescriptor(Option.none(), role, "hetzner"));
    }

    @SuppressWarnings("unchecked")
    private NodeReplacementWiring.Inputs inputs() {
        var fsm = mock(MembershipFsm.class);

        when(fsm.memberStates()).thenAnswer(call -> Map.copyOf(states));
        when(fsm.memberDescriptor(any())).thenAnswer(call -> Option.option(descriptors.get(call.<NodeId> getArgument(0))));
        var store = mock(KVStore.class);

        when(store.getTyped(any(), any())).thenAnswer(call -> {
            var key = call.getArgument(0);

            if (LeaderKey.INSTANCE.equals(key)) {
                return Option.some(new LeaderValue(SELF, 1));
            }

            return Option.option(stored.get(key));
        });
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
                                                () -> readyAdmitted,
                                                () -> readyAll,
                                                node -> "",
                                                ctm,
                                                node -> Promise.success(NodeReplacementWiring.DrainOutcome.admitted()),
                                                node -> false,
                                                () -> "fresh",
                                                node -> false,
                                                () -> genesis,
                                                OperatorWarningSink.logOnly(),
                                                () -> 1_000L,
                                                NodeReplacementPlanner.Timings.parse("60000,60000,60000,60000,0,60000,60000"));
    }

    @SuppressWarnings("unchecked")
    private KVCommand.LeaderTransaction<AetherKey, AetherValue> onlyTransaction() {
        assertThat(commands).as("exactly one command is submitted").hasSize(1);

        return (KVCommand.LeaderTransaction<AetherKey, AetherValue>) commands.getFirst();
    }

    private static Option<AetherValue> replacementOf(KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction, AetherKey key) {
        return transaction.mutations().stream().filter(mutation -> mutation.key().equals(key)).findFirst().map(mutation -> mutation.replacement()).orElse(Option.none());
    }

    private static NodeReplacementValue recordIn(KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction, NodeId original) {
        return (NodeReplacementValue) replacementOf(transaction, new AetherKey.NodeReplacementKey(original)).unwrap();
    }

    // ---- begin: the record -------------------------------------------------------------------------------------------

    @Test
    void begin_recordsTheOriginalsRoleAndMode_ctmWhenNoIdIsChosen() {
        wiring.service().begin(OLD_WORKER, "").await();
        var record = recordIn(onlyTransaction(), OLD_WORKER);

        assertThat(record.role()).as("the role comes from the original's descriptor, not a constant").isEqualTo("worker");
        assertThat(record.mode()).isEqualTo(NodeReplacementValue.MODE_CTM);
        assertThat(onlyTransaction().mutations()).as("CTM commits the record alone").hasSize(1);
    }

    @Test
    void beginExternal_recordsExternalMode_andTheOriginalsRole() {
        wiring.service().beginExternal(OLD_WORKER, FRESH, "").await();
        var record = recordIn(onlyTransaction(), OLD_WORKER);

        assertThat(record.mode()).isEqualTo(NodeReplacementValue.MODE_EXTERNAL);
        assertThat(record.role()).isEqualTo("worker");
        assertThat(record.replacement()).isEqualTo(FRESH);
    }

    @Test
    void begin_acceptsCoreAndWorker_refusesAnyOtherRole() {
        assertThat(wiring.service().begin(OLD_CORE, "").await().isSuccess()).isTrue();
        commands.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD_CORE));
        assertThat(wiring.service().begin(OLD_WORKER, "").await().isSuccess()).as("a worker is accepted").isTrue();
        commands.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD_WORKER));
        var refused = wiring.service().begin(OLD_DHT, "").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.RoleNotSupported.class));
    }

    // ---- beginExternal: the identity checks --------------------------------------------------------------------------

    @Test
    void beginExternal_refusesAnIdThatIsAlreadyAMember() {
        assertRefusedAsInUse(OLD_CORE);
    }

    @Test
    void beginExternal_refusesAnIdThatAnotherPairingAlreadyUses() {
        index.put(new AetherKey.NodeReplacementKey(new NodeId("someone-else")), new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.DONE, 0L));

        assertRefusedAsInUse(FRESH);
    }

    @Test
    void beginExternal_refusesAnIdThatHoldsAReservation() {
        stored.put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.DISPATCHED));

        assertRefusedAsInUse(FRESH);
    }

    private void assertRefusedAsInUse(NodeId id) {
        var result = wiring.service().beginExternal(OLD_WORKER, id, "").await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.ReplacementIdInUse.class));
        assertThat(commands).as("nothing is committed for a refused id").isEmpty();
    }

    /// M2: CTM refuses a core replacement under a genesis voter's identity ("restore the original WAL for same-identity restart");
    /// an EXTERNAL core replacement is held to the same rule. A worker has no voter identity.
    @Test
    void beginExternal_refusesAGenesisVoterIdentityForACore_butNotForAWorker() {
        genesis = Set.of(FRESH);
        var core = wiring.service().beginExternal(OLD_CORE, FRESH, "").await();

        assertThat(core.isFailure()).isTrue();
        core.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.FormerVoterIdentity.class));
        assertThat(commands).isEmpty();
        assertThat(wiring.service().beginExternal(OLD_WORKER, FRESH, "").await().isSuccess()).as("control: a worker may use the id").isTrue();
    }

    // ---- beginExternal: what is committed with the record ------------------------------------------------------------

    @Test
    void beginExternal_commitsTheAdmissionIntentInTheSameTransactionAsTheRecord() {
        wiring.service().beginExternal(OLD_CORE, FRESH, "").await();
        var transaction = onlyTransaction();

        assertThat(replacementOf(transaction, new AetherKey.CapacityReservationKey(FRESH)).isPresent())
            .as("the reservation travels in the record's own transaction, never a second command").isTrue();
        assertThat(replacementOf(transaction, new AetherKey.NodeReplacementKey(OLD_CORE)).isPresent()).isTrue();
    }

    /// M1, acquire side: the fleet counter is counted up with the reservation when the ledger exists, so that the release is symmetric.
    @Test
    void beginExternal_countsTheFleetLedgerUp_whenTheLedgerExists() {
        var ledger = new CapacityLedgerValue(4, 7, true);

        stored.put(AetherKey.CapacityLedgerKey.INSTANCE, ledger);
        wiring.service().beginExternal(OLD_CORE, FRESH, "").await();
        var counted = (CapacityLedgerValue) replacementOf(onlyTransaction(), AetherKey.CapacityLedgerKey.INSTANCE).unwrap();

        assertThat(counted.allocated()).isEqualTo(5);
        assertThat(counted.version()).isEqualTo(8);
        assertThat(counted.inventoryComplete()).isTrue();
    }

    @Test
    void beginExternal_withoutALedger_commitsTheReservationAndTheRecordOnly() {
        wiring.service().beginExternal(OLD_CORE, FRESH, "").await();

        assertThat(onlyTransaction().mutations()).hasSize(2);
    }

    // ---- the reconciler's environment --------------------------------------------------------------------------------

    /// M1, release side: an EXTERNAL replacement that ends ROLLED_BACK without its node ever having been observed frees its id: the
    /// reservation moves to RELEASED in the SAME transaction as the terminal record.
    @Test
    void rollingBackAnExternalReplacementThatNeverArrived_releasesItsReservationInTheSameTransaction() {
        var reservation = new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.DISPATCHED);
        var rolledBack = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.ROLLED_BACK, 0L, "hetzner", "", NodeReplacementValue.MODE_EXTERNAL, 0, "x", 1L);

        stored.put(new AetherKey.CapacityReservationKey(FRESH), reservation);

        var released = NodeReplacementWiring.releaseUnarrived(rolledBack, Option.some(reservation));

        assertThat(released).hasSize(1);
        assertThat(((CapacityReservationValue) released.getFirst().replacement().unwrap()).phase()).isEqualTo(CapacityReservationPhase.RELEASED);
        assertThat(released.getFirst().expected().unwrap()).isEqualTo(reservation);
    }

    @Test
    void releaseUnarrived_leavesAnObservedReservationAlone_andIgnoresCtmAndNonTerminalRecords() {
        var dispatched = new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.DISPATCHED);
        var observed = new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.OBSERVED);
        var external = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.ROLLED_BACK, 0L, "", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L);
        var ctmMode = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.ROLLED_BACK, 0L, "", "", NodeReplacementValue.MODE_CTM, 0, "", 1L);
        var running = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 0L, "", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L);

        assertThat(NodeReplacementWiring.releaseUnarrived(external, Option.some(observed))).as("the lifecycle releases an observed node").isEmpty();
        assertThat(NodeReplacementWiring.releaseUnarrived(ctmMode, Option.some(dispatched))).as("CTM owns its own reservation").isEmpty();
        assertThat(NodeReplacementWiring.releaseUnarrived(running, Option.some(dispatched))).as("only a terminal rollback releases").isEmpty();
        assertThat(NodeReplacementWiring.releaseUnarrived(external, Option.none())).isEmpty();
    }

    @Test
    void theReconcilersCommit_ofAnExternalRollback_carriesTheRelease() {
        var reservation = new CapacityReservationValue("hetzner", "", "core", CapacityReservationPhase.DISPATCHED);

        stored.put(new AetherKey.CapacityReservationKey(FRESH), reservation);
        index.put(new AetherKey.NodeReplacementKey(OLD_CORE),
                  new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 999L, "hetzner", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L));
        wiring.reconciler().reconcile().await();
        var transaction = onlyTransaction();

        assertThat(recordIn(transaction, OLD_CORE).phase()).as("the join deadline has passed").isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(((CapacityReservationValue) replacementOf(transaction, new AetherKey.CapacityReservationKey(FRESH)).unwrap()).phase())
            .as("and the never-arrived reservation is released in the same transaction").isEqualTo(CapacityReservationPhase.RELEASED);
    }

    /// A worker's readiness comes from the all-nodes ready view, a core's from the admitted-core view: with only the first holding the
    /// replacement, a worker advances (JOINING -> CANARY, no swap) and a core does not.
    @Test
    void workerReadiness_isReadFromTheAllNodesView() {
        states.put(FRESH, "Member");
        readyAll = Set.of(FRESH);
        readyAdmitted = Set.of();
        index.put(new AetherKey.NodeReplacementKey(OLD_WORKER),
                  new NodeReplacementValue(FRESH, "worker", NodeReplacementPhase.JOINING, 999_999L, "hetzner", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));
        wiring.reconciler().reconcile().await();

        assertThat(recordIn(onlyTransaction(), OLD_WORKER).phase()).isEqualTo(NodeReplacementPhase.CANARY);

        commands.clear();
        index.remove(new AetherKey.NodeReplacementKey(OLD_WORKER));
        index.put(new AetherKey.NodeReplacementKey(OLD_CORE),
                  new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 999_999L, "hetzner", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));
        wiring.reconciler().reconcile().await();

        assertThat(commands).as("control: a core is not admitted by the all-nodes view").isEmpty();
    }

    /// The provisioning call carries the replacement's role and the original's source (a worker is provisioned as a worker, from the
    /// source it came from).
    @Test
    void provisioning_carriesTheRecordsRoleAndSource() {
        index.put(new AetherKey.NodeReplacementKey(OLD_WORKER),
                  new NodeReplacementValue(FRESH, "worker", NodeReplacementPhase.PROVISIONING, 999_999L, "hetzner-eu", "", NodeReplacementValue.MODE_CTM, 0, "", 0L));
        wiring.reconciler().reconcile().await();
        var role = ArgumentCaptor.forClass(NodeRole.class);
        var source = ArgumentCaptor.forClass(SourceName.class);

        verify(ctm).provisionReplacement(any(), any(), any(), role.capture(), source.capture());
        assertThat(role.getValue()).isEqualTo(NodeRole.WORKER);
        assertThat(source.getValue().value()).isEqualTo("hetzner-eu");
    }

    // ---- the wiring into the two reconcilers -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void connectReconcilers_handsTheLeaderOnlyCoreSurge_andThePlacementShieldAndSurge() {
        var workerPair = new NodeId("worker-new");
        var corePair = new NodeId("core-new");

        index.put(new AetherKey.NodeReplacementKey(OLD_WORKER), new NodeReplacementValue(workerPair, "worker", NodeReplacementPhase.JOINING, 0L));
        index.put(new AetherKey.NodeReplacementKey(OLD_CORE), new NodeReplacementValue(corePair, "core", NodeReplacementPhase.JOINING, 0L));
        var leaderSurge = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<Set<NodeId>>>();
        var shield = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<Set<NodeId>>>();
        var surge = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<Set<NodeId>>>();

        NodeReplacementWiring.connectReconcilers(index, leaderSurge::set, (protectedNodes, surgeNodes) -> {
            shield.set(protectedNodes);
            surge.set(surgeNodes);
        });

        assertThat(leaderSurge.get().get()).as("the leader reconciler counts only a CORE replacement as core capacity").containsExactly(corePair);
        assertThat(surge.get().get()).as("the placement reconciler's surge is every pairing's replacement").containsExactlyInAnyOrder(workerPair, corePair);
        assertThat(shield.get().get()).as("and its shield also holds the originals").contains(OLD_WORKER, OLD_CORE, workerPair, corePair);
    }
}
