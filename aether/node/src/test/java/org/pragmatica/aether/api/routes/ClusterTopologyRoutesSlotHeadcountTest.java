// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberView;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimHealth;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// §8.3 headcount-cap oracle (legacy slot-based-membership convergence §6/§2 invariant; spec removed, see git history). The
/// operator-visible `coreCount` is slot-derived: it counts provisioning slots whose occupant is
/// present. Because the cluster owns exactly `clusterSize` slots and each
/// slot has at most one occupant, the count is capped at `clusterSize` by construction. A dead
/// predecessor that lingers present in the SWIM/membership view but is no longer any slot's
/// occupant is NOT counted — its replacement occupies the slot. This pins the §1 defect #1
/// over-count (`coreCount = S+1` against a target of `S`). #583 additionally pins the cold-start
/// fallback (no slots seeded yet): it must be role-scoped, not raw SWIM presence — a
/// cluster-minted worker present before slots exist must not inflate the "core" headcount.
class ClusterTopologyRoutesSlotHeadcountTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId DEAD = new NodeId("dead-predecessor");
    private static final NodeId FRESH = new NodeId("fresh-replacement");
    private static final MembershipFsm NO_FSM = null;

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
    }

    /// No-op serializer: this test seeds the KV store directly (not via consensus dedup), so
    /// the content-based batch id is irrelevant — an empty encoding satisfies `createBatch`.
    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    /// `fsm` is only reached by the cold-start (no-slots) branch — tests that seed slots pass
    /// `NO_FSM` and never trigger it, matching StatusRoutesQuorumTest's proxy convention.
    private ManageableNode nodeProxy(MembershipFsm fsm) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           case "membershipFsm" -> fsm;
                                                           case "self" -> SELF;
                                                           default -> throw new UnsupportedOperationException("Not in proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private void seedSlot(String slotId, NodeId occupant, long epoch, Option<NodeId> superseded) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ProvisioningSlotKey.provisioningSlotKey(slotId),
                                            new ProvisioningSlotValue(1L, 2L, Option.some(occupant), epoch, superseded)))));
    }

    private static MembershipFsm fsmWithSelfAsCore() {
        var fsm = MembershipFsm.membershipFsm();

        promoteCore(fsm, SELF);

        return fsm;
    }

    private static MembershipFsm fsmWithSelfCoreAndPresentWorker(NodeId worker) {
        var fsm = fsmWithSelfAsCore();

        promoteWorker(fsm, worker);

        return fsm;
    }

    private static void promoteCore(MembershipFsm fsm, NodeId id) {
        fsm.onSwimHealthy(id, 1L);
        fsm.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "core")));
    }

    private static void promoteWorker(MembershipFsm fsm, NodeId id) {
        fsm.onSwimHealthy(id, 1L);
        fsm.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "worker")));
    }

    private static NodeInfo labeledInfo(NodeId id, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("host-x", 6000).unwrap(), labels);
    }

    @Test
    void coreCount_deadPlusFreshOccupantSameSlot_neverExceedsClusterSize() {
        // Slot 0 is now occupied by FRESH (epoch 2), having superseded DEAD. The dead predecessor
        // is NOT a slot occupant — it only lingers present in the membership view.
        seedSlot("0", FRESH, 2L, Option.some(DEAD));
        seedSlot("1", SELF, 1L, Option.none());

        // The membership view (SWIM-emergent) over-reports: DEAD, FRESH and SELF are all present.
        // The slot-derived count must ignore DEAD (not an occupant) and report exactly 2 (= the
        // number of HEALTHY slot occupants), never 3.
        var view = presentView(DEAD, FRESH, SELF);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(NO_FSM), view);

        var clusterSize = 2;
        assertThat(count).as("slot-derived headcount must not exceed clusterSize despite a lingering DEAD present entry")
                         .isEqualTo(2)
                         .isLessThanOrEqualTo(clusterSize);
    }

    @Test
    void coreCount_occupantNotPresent_isExcluded() {
        // Slot 0's occupant is FRESH but absent from the presence view; slot 1's
        // SELF is present. Only the present occupant counts.
        seedSlot("0", FRESH, 1L, Option.none());
        seedSlot("1", SELF, 1L, Option.none());
        var view = presentView(SELF);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(NO_FSM), view);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void coreCount_noSlotsSeeded_selfOnlyCore_fallsBackToObservedCoreCount() {
        // Cold start: no slots in KV → fall back to the FSM's core-scoped observed-member count
        // (mirrors StatusRoutes.fallbackQuorumStatus) so a freshly bootstrapped self still
        // reports as a core.
        var view = presentView(SELF);
        var fsm = fsmWithSelfAsCore();

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(fsm), view);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void coreCount_noSlotsSeeded_workerPresentAlongsideSelf_excludesWorkerFromCount() {
        // #583 regression: before the fix, the cold-start fallback was view.presentMembers().size()
        // — every SWIM-present member counted regardless of role, so a cluster-minted worker
        // present before slots exist inflated the "core" headcount to 2. The FSM's core-scoped
        // coreObservedMembers excludes the explicit worker at the source, keeping this at 1.
        var worker = new NodeId("cluster-minted-worker");
        var view = presentView(SELF, worker);
        var fsm = fsmWithSelfCoreAndPresentWorker(worker);

        var count = ClusterTopologyRoutes.slotDerivedCoreCount(nodeProxy(fsm), view);

        assertThat(count).as("a present worker must not inflate the slot-derived core headcount during cold start")
                         .isEqualTo(1);
    }

    private static MembershipView presentView(NodeId... present) {
        var members = Set.of(present);

        return new MembershipView() {
            @Override public Map<NodeId, MemberView> snapshot() {
                var map = new HashMap<NodeId, MemberView>();
                members.forEach(peer -> map.put(peer, new MemberView(peer, SwimHealth.HEALTHY)));

                return Map.copyOf(map);
            }

            @Override public Option<MemberView> get(NodeId peer) {
                return members.contains(peer)
                       ? Option.some(new MemberView(peer, SwimHealth.HEALTHY))
                       : Option.none();
            }
        };
    }
}
