// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberView;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.swim.SwimHealth;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// §8.3 headcount-cap oracle (slot-based-membership-convergence-spec §6/§2 invariant). The
/// operator-visible `coreCount` is slot-derived: it counts provisioning slots whose occupant is
/// ON_DUTY. Because the cluster owns exactly `clusterSize` slots and each
/// slot has at most one occupant, the count is capped at `clusterSize` by construction. A dead
/// predecessor that lingers ON_DUTY in the SWIM/lifecycle view but is no longer any slot's
/// occupant is NOT counted — its replacement occupies the slot. This pins the §1 defect #1
/// over-count (`coreCount = S+1` against a target of `S`).
class ClusterTopologyRoutesSlotHeadcountTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId DEAD = new NodeId("dead-predecessor");
    private static final NodeId FRESH = new NodeId("fresh-replacement");

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, null, null);
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           default -> throw new UnsupportedOperationException("Not in proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private void seedSlot(String slotId, NodeId occupant, long epoch, Option<NodeId> superseded) {
        kvStore.process(new KVCommand.Put<>(ProvisioningSlotKey.provisioningSlotKey(slotId),
                                            new ProvisioningSlotValue(1L, 2L, Option.some(occupant), epoch, superseded)));
    }

    @Test
    void coreCount_deadPlusFreshOccupantSameSlot_neverExceedsClusterSize() {
        // Slot 0 is now occupied by FRESH (epoch 2), having superseded DEAD. The dead predecessor
        // is NOT a slot occupant — it only lingers ON_DUTY in the membership view.
        seedSlot("0", FRESH, 2L, Option.some(DEAD));
        seedSlot("1", SELF, 1L, Option.none());

        // The membership view (SWIM-emergent) over-reports: DEAD, FRESH and SELF are all ON_DUTY.
        // The slot-derived count must ignore DEAD (not an occupant) and report exactly 2 (= the
        // number of HEALTHY slot occupants), never 3.
        var view = onDutyView(DEAD, FRESH, SELF);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(), view);

        var clusterSize = 2;
        assertThat(count).as("slot-derived headcount must not exceed clusterSize despite a lingering DEAD ON_DUTY entry")
                         .isEqualTo(2)
                         .isLessThanOrEqualTo(clusterSize);
    }

    @Test
    void coreCount_occupantNotOnDuty_isExcluded() {
        // Slot 0's occupant is FRESH but the view reports it JOINING (not yet ON_DUTY); slot 1's
        // SELF is ON_DUTY. Only the ON_DUTY occupant counts.
        seedSlot("0", FRESH, 1L, Option.none());
        seedSlot("1", SELF, 1L, Option.none());
        var view = onDutyView(SELF);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(), view);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void coreCount_noSlotsSeeded_fallsBackToViewCount() {
        // Cold start: no slots in KV → fall back to the SWIM-derived count so a freshly
        // bootstrapped self still reports as a core.
        var view = onDutyView(SELF);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(), view);

        assertThat(count).isEqualTo(1);
    }

    private static MembershipView onDutyView(NodeId... onDuty) {
        var statuses = new HashMap<NodeId, MemberStatus>();
        for (var peer : Set.of(onDuty)) {
            statuses.put(peer, MemberStatus.ON_DUTY);
        }

        return new MembershipView() {
            @Override public Map<NodeId, MemberView> snapshot() {
                var map = new HashMap<NodeId, MemberView>();
                statuses.forEach((peer, status) -> map.put(peer,
                                                           new MemberView(peer, status, SwimHealth.HEALTHY)));

                return Map.copyOf(map);
            }

            @Override public Option<MemberView> get(NodeId peer) {
                return Option.option(statuses.get(peer))
                             .map(status -> new MemberView(peer, status, SwimHealth.HEALTHY));
            }
        };
    }
}
