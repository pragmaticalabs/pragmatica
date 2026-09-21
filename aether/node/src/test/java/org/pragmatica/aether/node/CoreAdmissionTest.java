// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import static org.assertj.core.api.Assertions.assertThat;

class CoreAdmissionTest {
    @Test
    void claimedRoleAndInventoryCannotAuthorizeNewCore() {
        var membership = membership();
        var node = new NodeId("candidate");
        membership.onMemberDescriptor(info(node, "core"));
        var reservations = new HashMap<NodeId, CapacityReservationValue>();
        var admission = CoreAdmission.coreAdmission(() -> membership, Set::of,
            id -> Option.option(reservations.get(id)), _ -> false);
        assertThat(admission.isAllowed(node)).isFalse();
        reservations.put(node, new CapacityReservationValue("east", "binding", "", CapacityReservationPhase.OBSERVED));
        assertThat(admission.isAllowed(node)).isFalse();
        reservations.put(node, new CapacityReservationValue("east", "binding", "core", CapacityReservationPhase.DISPATCHED));
        assertThat(admission.isAllowed(node)).isTrue();
        reservations.put(node, new CapacityReservationValue("east", "binding", "core", CapacityReservationPhase.RELEASED));
        assertThat(admission.isAllowed(node)).isFalse();
        membership.setTrackingEligibility(_ -> false);
    }

    @Test
    void verifiedHistoryAndExplicitHarnessAdmissionStillRequireCoreRole() {
        var membership = membership();
        var core = new NodeId("verified-core");
        var worker = new NodeId("worker");
        var unknown = new NodeId("unknown");
        membership.onMemberDescriptor(info(core, "core"));
        membership.onMemberDescriptor(info(worker, "worker"));
        membership.onMemberDescriptor(info(unknown, ""));
        var admission = CoreAdmission.coreAdmission(() -> membership, () -> Set.of(core, worker, unknown),
            _ -> Option.none(), _ -> true);
        assertThat(admission.isAllowed(core)).isTrue();
        assertThat(admission.isAllowed(worker)).isFalse();
        assertThat(admission.isAllowed(unknown)).isFalse();
        membership.setTrackingEligibility(_ -> false);
    }

    @Test
    void retiringAllocationOverridesHistoricalAndHarnessAdmission() {
        var membership = membership();
        var core = new NodeId("retiring-core");
        membership.onMemberDescriptor(info(core, "core"));
        var admission = CoreAdmission.coreAdmission(() -> membership, () -> Set.of(core),
            _ -> Option.some(new CapacityReservationValue("east", "binding", "core", CapacityReservationPhase.RETIRING)),
            _ -> true);
        assertThat(admission.isAllowed(core)).isFalse();
        membership.setTrackingEligibility(_ -> false);
    }

    private static MembershipFsm membership() {
        return MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
            Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
    }

    private static NodeInfo info(NodeId node, String role) {
        return NodeInfo.nodeInfo(node, NodeAddress.nodeAddress("localhost", 6000).unwrap(), Map.of(NodeInfo.LABEL_ROLE, role));
    }
}
