// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #1978 (I-36, NT-173's wiring): the departure sweep is called for a core that leaves membership (`NodeRemoved`) and for a worker that
/// leaves (`WorkerLeaveDecision`, #731: a worker's leave never reaches `MembershipDecision`), and not for a join. `KvBackedStreamRegistryHoldersTest`
/// pins the sweep and `StreamRegistryHolderReconcilerTest` the level reconcile; this pins the routes, and (source-text pins, as
/// `NodeDepartureWiringTest`) that the node's assembly calls the route helper with the registry's sweep and schedules the reconcile.
class StreamRegistrySweepWiringTest {
    private static final NodeId LEAVER = new NodeId("node-leaver");
    private static final NodeId JOINER = new NodeId("node-joiner");
    private static final NodeId WORKER = new NodeId("worker-1");

    @Test
    void membershipRemoval_andWorkerLeave_sweepTheNode_butAJoinDoesNot() {
        var swept = new ArrayList<NodeId>();
        var entries = new ArrayList<MessageRouter.Entry<?>>();

        AetherNode.wireStreamRegistrySweep(entries, swept::add);
        dispatch(entries, MembershipDecision.nodeJoined(JOINER, List.of(JOINER)));
        assertThat(swept).as("control: a join sweeps nothing").isEmpty();

        dispatch(entries, MembershipDecision.nodeRemoved(LEAVER, List.of(JOINER)));
        dispatch(entries, WorkerLeaveDecision.workerLeaveDecision(WORKER, HlcTimestamp.ZERO));

        assertThat(swept).containsExactly(LEAVER, WORKER);
    }

    /// W03/W04 (#2505 mutation): the assembly calls the route helper with the registry's own sweep. Red when the call is deleted or its
    /// consumer replaced.
    @Test
    void theAssembly_routesDeparturesToTheRegistrySweep() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).containsOnlyOnce("varstreamRegistry=KvBackedStreamRegistry.kvBackedStreamRegistry(clusterNode,kvStore);wireStreamRegistrySweep(aetherEntries,streamRegistry::sweepHoldersOf);");
    }

    /// F6: the assembly builds the level reconcile over that registry, with the role-blind counted membership (cores and workers) and the
    /// leader gate, and schedules it at its interval. Red when the schedule is dropped or reads a core-only projection.
    @Test
    void theAssembly_schedulesTheLevelReconcile() {
        var code = NodeDepartureWiringTest.assemblyCode();

        assertThat(code).containsOnlyOnce("StreamRegistryHolderReconciler.streamRegistryHolderReconciler(streamRegistry,selfId,()->Option.option(membershipFsmRef.get()).map(MembershipFsm::countedMembers),isLeaderSupplier);");
        assertThat(code).containsOnlyOnce("periodicTasks.defer(()->SharedScheduler.scheduleAtFixedRate(streamRegistryHolderReconciler::tick,StreamRegistryHolderReconciler.INTERVAL,StreamRegistryHolderReconciler.INTERVAL));");
    }

    /// Every registered route sees the message, as the node's router does.
    private static void dispatch(List<MessageRouter.Entry<?>> entries, Message message) {
        entries.forEach(entry -> entry.asRouter().unwrap().route(message));
    }
}
