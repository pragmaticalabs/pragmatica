// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #1730: committed ISR members stay placed (they keep receiving the partition, so acknowledgements do not stall on a
/// member nobody sends to), and a partition whose owner and every ISR member left the live set reports why it has no
/// owner instead of going silent.
class IsrPlacementTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId D = new NodeId("node-d");
    private static final NodeId E = new NodeId("node-e");

    @Test
    void placeWithOwnerAndIsr_keepsEveryIsrMemberPlaced_evenBeyondTheHrwTop() {
        var members = List.of(A, B, C, D, E);
        var hrwRest = ReplicaPlacement.placeWithOwner(STREAM, PARTITION, A, members, 2).replicas().get(1);
        var outsideHrwTop = members.stream()
                                   .filter(node -> !node.equals(A) && !node.equals(hrwRest))
                                   .findFirst()
                                   .orElseThrow();

        var placement = ReplicaPlacement.placeWithOwnerAndIsr(STREAM, PARTITION, A, List.of(A, outsideHrwTop), members, 2);

        assertThat(placement.owner()).isEqualTo(A);
        assertThat(placement.replicas()).containsExactly(A, outsideHrwTop);
    }

    @Test
    void noInSyncReplica_ownerAndEveryIsrMemberGone_reportsTheBlock() {
        var controller = controller(List.of(C, D));

        controller.committedOwnerSource((_, _) -> Option.some(A));
        controller.committedIsrSource((_, _) -> List.of(A, B));

        assertThat(controller.noInSyncReplica(STREAM, PARTITION)
                             .filter(OwnerActivation.ActivationBlock.NoInSyncReplica.class::isInstance)
                             .isPresent()).isTrue();
    }

    @Test
    void noInSyncReplica_anIsrMemberLive_reportsNothing() {
        var controller = controller(List.of(B, C));

        controller.committedOwnerSource((_, _) -> Option.some(A));
        controller.committedIsrSource((_, _) -> List.of(A, B));

        assertThat(controller.noInSyncReplica(STREAM, PARTITION).isEmpty()).isTrue();
    }

    private static ReplicaSetController controller(List<NodeId> members) {
        return ReplicaSetController.replicaSetController(replicaRegistry(),
                                                         C,
                                                         () -> members,
                                                         members::size,
                                                         List::of,
                                                         (_, _) -> {},
                                                         _ -> {},
                                                         Runnable::run);
    }
}
