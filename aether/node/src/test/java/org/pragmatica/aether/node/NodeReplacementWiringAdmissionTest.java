package org.pragmatica.aether.node;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #1543 E2: an EXTERNAL core replacement commits the core-admission intent in the same transaction as its record, and that
/// intent is exactly what `CoreAdmission` admits a core by. Ember cannot pin this (its harness admits in-process nodes
/// without any reservation), so the rule is pinned here against the real admission predicate.
class NodeReplacementWiringAdmissionTest {
    private static final NodeId FRESH = NodeId.nodeId("fresh-9").unwrap();

    @Test
    void externalCore_commitsTheReservationCoreAdmissionAdmitsBy() {
        var mutations = NodeReplacementWiring.admissionMutations(true, "core", FRESH, "hetzner");

        assertThat(mutations).hasSize(1);
        var mutation = mutations.getFirst();

        assertThat(mutation.key()).isEqualTo(new AetherKey.CapacityReservationKey(FRESH));
        var reservation = (CapacityReservationValue) mutation.replacement().unwrap();
        var admission = CoreAdmission.coreAdmission(() -> coreMemberFsm(), Set::of, node -> Option.some(reservation), _ -> false);

        assertThat(admission.isAllowed(FRESH)).as("the committed intent admits the core").isTrue();
        assertThat(CoreAdmission.coreAdmission(() -> coreMemberFsm(), Set::of, _ -> Option.none(), _ -> false)
                                .isAllowed(FRESH)).as("control: without it the same core is refused").isFalse();
    }

    @Test
    void ctmReplacement_commitsTheRecordAlone() {
        assertThat(NodeReplacementWiring.admissionMutations(false, "core", FRESH, "")).isEmpty();
        assertThat(NodeReplacementWiring.admissionMutations(false, "worker", FRESH, "")).isEmpty();
    }

    /// v-2042: worker admission is decided by `AetherNode.workerAdmissionAllowed`, which admits a worker only on a committed
    /// capacity reservation for its role (or a local environment authorization). An EXTERNAL worker replacement therefore needs the
    /// reservation committed with its record, exactly like a core; without it the operator-started worker is never admitted.
    @Test
    void externalWorker_commitsTheReservationWorkerAdmissionAdmitsBy() {
        var mutations = NodeReplacementWiring.admissionMutations(true, "worker", FRESH, "hetzner");

        assertThat(mutations).as("an EXTERNAL worker replacement commits its admission intent").hasSize(1);
        var mutation = mutations.getFirst();

        assertThat(mutation.key()).isEqualTo(new AetherKey.CapacityReservationKey(FRESH));
        var reservation = (CapacityReservationValue) mutation.replacement().unwrap();

        assertThat(AetherNode.workerAdmissionAllowed(FRESH, workerMemberFsm(), storeHolding(Option.some(reservation)), configWithoutEnvironment()))
            .as("the committed intent admits the worker").isTrue();
        assertThat(AetherNode.workerAdmissionAllowed(FRESH, workerMemberFsm(), storeHolding(Option.none()), configWithoutEnvironment()))
            .as("control: without it the same worker is refused").isFalse();
    }

    private static MembershipFsm workerMemberFsm() {
        var fsm = mock(MembershipFsm.class);

        when(fsm.memberDescriptor(any())).thenReturn(Option.some(new MemberDescriptor(Option.none(), "worker", "")));

        return fsm;
    }

    @SuppressWarnings("unchecked")
    private static org.pragmatica.cluster.state.kvstore.KVStore<AetherKey, AetherValue> storeHolding(Option<CapacityReservationValue> reservation) {
        var store = mock(org.pragmatica.cluster.state.kvstore.KVStore.class);

        when(store.getTyped(any(), any())).thenReturn(reservation);

        return store;
    }

    private static AetherNodeConfig configWithoutEnvironment() {
        var config = mock(AetherNodeConfig.class);

        when(config.environment()).thenReturn(Option.none());

        return config;
    }

    private static MembershipFsm coreMemberFsm() {
        var fsm = mock(MembershipFsm.class);

        when(fsm.isTrackedAndNotDead(any())).thenReturn(true);
        when(fsm.memberDescriptor(any())).thenReturn(Option.some(new MemberDescriptor(Option.none(), "core", "")));

        return fsm;
    }
}
