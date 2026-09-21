// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Admission intent is independent of the role claimed in a peer's Hello. Only bootstrap/certified
/// identities, committed CORE provisioning intent, or explicit in-process harness admission qualify.
public record CoreAdmission(Supplier<MembershipFsm> membership,
                            Supplier<Set<NodeId>> verifiedVoters,
                            Function<NodeId, Option<CapacityReservationValue>> reservation,
                            Predicate<NodeId> trustedLocalAdmission) {
    public static CoreAdmission coreAdmission(Supplier<MembershipFsm> membership,
                                              Supplier<Set<NodeId>> verifiedVoters,
                                              Function<NodeId, Option<CapacityReservationValue>> reservation,
                                              Predicate<NodeId> trustedLocalAdmission) {
        return new CoreAdmission(membership, verifiedVoters, reservation, trustedLocalAdmission);
    }

    public boolean isAllowed(NodeId node) {
        return reservation.apply(node)
                          .filter(value -> value.phase() == CapacityReservationPhase.RETIRING)
                          .isEmpty()
               && hasCoreDescriptor(node)
               && hasAdmissionIntent(node);
    }

    private boolean hasCoreDescriptor(NodeId node) {
        return Option.option(membership.get())
                     .filter(fsm -> fsm.isTrackedAndNotDead(node))
                     .flatMap(fsm -> fsm.memberDescriptor(node))
                     .map(MemberDescriptor::isCore)
                     .or(false);
    }

    private boolean hasAdmissionIntent(NodeId node) {
        return verifiedVoters.get()
                             .contains(node) || trustedLocalAdmission.test(node) || reservation.apply(node)
                                                                                               .filter(CoreAdmission::reservesCore)
                                                                                               .isPresent();
    }

    private static boolean reservesCore(CapacityReservationValue reservation) {
        return "core".equals(reservation.intendedRole()) && (reservation.phase() == CapacityReservationPhase.DISPATCHED || reservation.phase() == CapacityReservationPhase.OBSERVED);
    }
}
