// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1555 sticky ownership. Every node routes by the COMMITTED ownership record; only the leader's writer judges
/// liveness ([ReplicaSetController#desiredOwner]) and moves ownership only when the committed owner leaves its live
/// set.
///
///   (i)  observers disagree: a node that still counts the old owner live routes to the record's owner anyway;
///   (ii) a higher-ranked node joins: ownership does not move, and the joiner becomes a replica.
class StickyOwnershipTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RF = 2;
    private static final StreamCatalog CATALOG = () -> List.of(new StreamCatalog.StreamSpec(STREAM, 1, RF, 0));

    private static ReplicaSetController controller(NodeId self,
                                                   AtomicReference<List<NodeId>> members,
                                                   AtomicReference<Option<NodeId>> committed) {
        var controller = ReplicaSetController.replicaSetController(ReplicaRegistry.replicaRegistry(),
                                                                   self,
                                                                   members::get,
                                                                   () -> members.get().size(),
                                                                   CATALOG,
                                                                   (_, _) -> {},
                                                                   Runnable::run);

        controller.committedOwnerSource((_, _) -> committed.get());

        return controller;
    }

    private static List<NodeId> nodes(String... ids) {
        var result = new ArrayList<NodeId>();

        for (var id : ids) {
            result.add(new NodeId(id));
        }

        return result;
    }

    private static NodeId hrwOwner(List<NodeId> members) {
        return ReplicaPlacement.place(STREAM, PARTITION, members, RF)
                               .map(ReplicaPlacement.Placement::owner)
                               .or(members.getFirst());
    }

    /// (i) The observer's own live set still contains the old HRW owner (it never saw it die), but the leader has
    /// committed a new owner: the observer routes, and assigns roles, by the record.
    @Test
    void ownerFor_observerStillSeesOldOwnerLive_routesToCommittedOwner() {
        var members = nodes("n1", "n2", "n3", "n4", "n5");
        var oldOwner = hrwOwner(members);
        var newOwner = members.stream()
                              .filter(node -> !node.equals(oldOwner))
                              .findFirst()
                              .orElseThrow();
        var observer = members.stream()
                              .filter(node -> !node.equals(oldOwner) && !node.equals(newOwner))
                              .findFirst()
                              .orElseThrow();
        var committed = new AtomicReference<>(Option.some(newOwner));
        var observerView = controller(observer, new AtomicReference<>(members), committed);
        var newOwnerView = controller(newOwner, new AtomicReference<>(members), committed);

        assertThat(observerView.ownerFor(STREAM, PARTITION)).isEqualTo(Option.some(newOwner));
        assertThat(newOwnerView.roleFor(STREAM, PARTITION)).isEqualTo(ReplicaSetController.Role.OWNER);
        assertThat(hrwOwner(members)).as("arming: the HRW winner is still the old owner").isEqualTo(oldOwner);
    }

    /// (ii) A node that ranks above the committed owner joins: the leader keeps the owner, and the joiner is placed
    /// as a replica rather than taking ownership.
    @Test
    void desiredOwner_higherRankedNodeJoins_ownershipDoesNotMove() {
        var members = new AtomicReference<>(nodes("n1", "n2", "n3"));
        var owner = hrwOwner(members.get());
        var committed = new AtomicReference<>(Option.some(owner));
        var leader = controller(members.get().getLast(), members, committed);
        var joiner = higherRankedJoiner(members.get());

        members.set(withJoiner(members.get(), joiner));

        assertThat(hrwOwner(members.get())).as("arming: the joiner now wins HRW").isEqualTo(joiner);
        assertThat(leader.desiredOwner(STREAM, PARTITION)).isEqualTo(Option.some(owner));
        assertThat(leader.ownerFor(STREAM, PARTITION)).isEqualTo(Option.some(owner));
        assertThat(controller(joiner, members, committed).roleFor(STREAM, PARTITION)).isEqualTo(ReplicaSetController.Role.REPLICA);
    }

    /// The one move: the committed owner leaves the leader's live set, so the leader re-places by HRW over the rest.
    @Test
    void desiredOwner_committedOwnerLeftLiveSet_movesToHrwOwnerOfTheRest() {
        var all = nodes("n1", "n2", "n3", "n4");
        var owner = hrwOwner(all);
        var survivors = all.stream()
                           .filter(node -> !node.equals(owner))
                           .toList();
        var leader = controller(survivors.getFirst(), new AtomicReference<>(survivors), new AtomicReference<>(Option.some(owner)));

        assertThat(leader.desiredOwner(STREAM, PARTITION)).isEqualTo(Option.some(hrwOwner(survivors)));
    }

    @Test
    void ownerFor_noRecordYet_isHrwOwner() {
        var members = nodes("n1", "n2", "n3");
        var view = controller(members.getFirst(), new AtomicReference<>(members), new AtomicReference<>(Option.none()));

        assertThat(view.ownerFor(STREAM, PARTITION)).isEqualTo(Option.some(hrwOwner(members)));
    }

    private static NodeId higherRankedJoiner(List<NodeId> members) {
        for (var i = 0; i < 1000; i++) {
            var candidate = new NodeId("joiner-" + i);

            if (hrwOwner(withJoiner(members, candidate)).equals(candidate)) {
                return candidate;
            }
        }

        throw new AssertionError("no higher-ranked joiner found");
    }

    private static List<NodeId> withJoiner(List<NodeId> members, NodeId joiner) {
        var result = new ArrayList<>(members);

        result.add(joiner);

        return List.copyOf(result);
    }
}
