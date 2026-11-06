// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// F1 #2643 kill test for mutant u11 (WorkerHierarchyAssembly `redrive.accept(put)` deleted): a placement put for a
/// tracked worker or spot node must invoke the redrive; an untracked node, or a tracked non-worker node, must not.
/// `retryPlacedWorker` is private, so it is reached by reflection; the wiring call site passes `membershipFsmRef::get`
/// and `placementRedrive` straight through, so the three arguments here are exactly what the call site supplies.
class PlacedWorkerRedriveTest {
    private static final NodeId NODE = NodeId.nodeId("placed-1").unwrap();

    @Test
    void placementPut_trackedWorker_invokesRedrive() throws Exception {
        assertThat(redrives(true, "worker")).as("tracked worker redrives").hasSize(1);
    }

    @Test
    void placementPut_trackedSpot_invokesRedrive() throws Exception {
        assertThat(redrives(true, "spot")).as("tracked spot redrives").hasSize(1);
    }

    @Test
    void placementPut_untrackedWorker_doesNotRedrive() throws Exception {
        assertThat(redrives(false, "worker")).as("untracked or dead node never redrives").isEmpty();
    }

    @Test
    void placementPut_trackedCore_doesNotRedrive() throws Exception {
        assertThat(redrives(true, "core")).as("tracked non-worker node never redrives").isEmpty();
    }

    private static List<ValuePut<NodePlacementKey, NodePlacementValue>> redrives(boolean tracked, String role) throws Exception {
        var fsm = mock(MembershipFsm.class);

        when(fsm.isTrackedAndNotDead(any())).thenReturn(tracked);
        when(fsm.memberDescriptor(any())).thenReturn(Option.some(new MemberDescriptor(Option.none(), role, "", "")));

        var put = new ValuePut<>(new KVCommand.Put<>(new NodePlacementKey(NODE),
                                                     new NodePlacementValue("src", Option.none(), "i-1")),
                                 Option.<NodePlacementValue>none());
        var seen = new ArrayList<ValuePut<NodePlacementKey, NodePlacementValue>>();
        Supplier<MembershipFsm> membership = () -> fsm;
        Consumer<ValuePut<NodePlacementKey, NodePlacementValue>> redrive = seen::add;
        Method method = WorkerHierarchyAssembly.class.getDeclaredMethod("retryPlacedWorker",
                                                                       ValuePut.class,
                                                                       Supplier.class,
                                                                       Consumer.class);

        method.setAccessible(true);
        method.invoke(null, put, membership, redrive);
        return seen;
    }
}
