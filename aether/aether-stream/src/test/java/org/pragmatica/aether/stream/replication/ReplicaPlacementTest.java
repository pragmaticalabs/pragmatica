// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaPlacementTest {

    private static List<NodeId> nodes(int count) {
        var list = new ArrayList<NodeId>();
        for (var i = 0; i < count; i++) {
            list.add(new NodeId("node-" + i));
        }
        return list;
    }

    private static Placement placeOrFail(String stream, int partition, List<NodeId> members, int rf) {
        return ReplicaPlacement.place(stream, partition, members, rf)
                               .or(() -> {
                                   throw new AssertionError("expected a placement");
                               });
    }

    @Test
    void emptyMembersYieldsNone() {
        assertThat(ReplicaPlacement.place("orders", 0, List.of(), 3).isEmpty()).isTrue();
    }

    @Test
    void singleMemberOwnsAndIsSoleReplica() {
        var only = new NodeId("solo");
        var placement = placeOrFail("orders", 0, List.of(only), 3);

        assertThat(placement.owner()).isEqualTo(only);
        assertThat(placement.replicas()).containsExactly(only);
    }

    @Test
    void replicasAreOwnerFirst() {
        var members = nodes(5);

        for (var p = 0; p < 20; p++) {
            var placement = placeOrFail("orders", p, members, 3);
            assertThat(placement.replicas()).hasSize(3);
            assertThat(placement.replicas().getFirst()).isEqualTo(placement.owner());
        }
    }

    @Test
    void deterministicAcrossRepetitions() {
        var members = nodes(7);
        var first = placeOrFail("orders", 3, members, 4);

        for (var i = 0; i < 1000; i++) {
            assertThat(placeOrFail("orders", 3, members, 4)).isEqualTo(first);
        }
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        var members = nodes(8);
        var baseline = placeOrFail("orders", 5, members, 5);
        var rng = new Random(42);

        for (var i = 0; i < 200; i++) {
            var shuffled = new ArrayList<>(members);
            Collections.shuffle(shuffled, rng);
            assertThat(placeOrFail("orders", 5, shuffled, 5)).isEqualTo(baseline);
        }
    }

    @Test
    void removingNonOwnerLeavesOwnerUnchanged() {
        var members = nodes(6);

        for (var p = 0; p < 50; p++) {
            var owner = placeOrFail("orders", p, members, 3).owner();
            // pick any non-owner to remove
            var victim = members.stream()
                                .filter(node -> !node.equals(owner))
                                .findFirst()
                                .orElseThrow();
            var reduced = new ArrayList<>(members);
            reduced.remove(victim);

            assertThat(placeOrFail("orders", p, reduced, 3).owner()).isEqualTo(owner);
        }
    }

    @Test
    void removingOwnerPromotesRankedSecond() {
        var members = nodes(6);

        for (var p = 0; p < 50; p++) {
            var ranked = ReplicaPlacement.rank("orders", p, members);
            var owner = ranked.getFirst();
            var expectedSuccessor = ranked.get(1);

            var reduced = new ArrayList<>(members);
            reduced.remove(owner);

            assertThat(placeOrFail("orders", p, reduced, 3).owner()).isEqualTo(expectedSuccessor);
        }
    }

    @Test
    void removingNodeOnlyChangesPlacementsThatIncludedIt() {
        var members = nodes(8);
        var victim = members.get(3);
        var reduced = new ArrayList<>(members);
        reduced.remove(victim);

        var changed = 0;
        var unchanged = 0;

        for (var p = 0; p < 500; p++) {
            var stream = "stream-" + (p % 7);
            var before = placeOrFail(stream, p, members, 4);
            var after = placeOrFail(stream, p, reduced, 4);

            if (before.replicas().contains(victim)) {
                changed++;
            } else {
                // placement that did not contain the victim must be byte-identical
                assertThat(after).isEqualTo(before);
                unchanged++;
            }
        }

        // sanity: both buckets are non-trivially populated
        assertThat(changed).isGreaterThan(0);
        assertThat(unchanged).isGreaterThan(0);
    }

    @Test
    void replicationFactorClampsRequested() {
        // rf > N -> N
        assertThat(ReplicaPlacement.place("orders", 0, nodes(4), 10).or(() -> null).replicas())
                .hasSize(4);
        // rf < 1 -> 1
        assertThat(ReplicaPlacement.place("orders", 0, nodes(4), 0).or(() -> null).replicas())
                .hasSize(1);
        assertThat(ReplicaPlacement.place("orders", 0, nodes(4), -5).or(() -> null).replicas())
                .hasSize(1);
    }

    @Test
    void appReplicationFactorClamps() {
        assertThat(ReplicaPlacement.replicationFactor(3, 5)).isEqualTo(3);
        assertThat(ReplicaPlacement.replicationFactor(10, 5)).isEqualTo(5);
        assertThat(ReplicaPlacement.replicationFactor(0, 5)).isEqualTo(1);
        assertThat(ReplicaPlacement.replicationFactor(3, 0)).isEqualTo(0);
    }

    @Test
    void systemReplicationFactorFormula() {
        assertThat(ReplicaPlacement.systemReplicationFactor(1)).isEqualTo(1);
        assertThat(ReplicaPlacement.systemReplicationFactor(3)).isEqualTo(3);
        assertThat(ReplicaPlacement.systemReplicationFactor(5)).isEqualTo(3);
        assertThat(ReplicaPlacement.systemReplicationFactor(7)).isEqualTo(5);
        assertThat(ReplicaPlacement.systemReplicationFactor(10)).isEqualTo(8);
        assertThat(ReplicaPlacement.systemReplicationFactor(0)).isEqualTo(0);
    }

    @Test
    void streamClassDispatch() {
        assertThat(ReplicaPlacement.replicationFactor(StreamClass.APP, 2, 5)).isEqualTo(2);
        assertThat(ReplicaPlacement.replicationFactor(StreamClass.SYSTEM, 2, 5)).isEqualTo(3);
    }

    @Test
    void isOwnerMatchesPlacementOwner() {
        var members = nodes(5);
        var owner = placeOrFail("orders", 0, members, 3).owner();

        assertThat(ReplicaPlacement.isOwner("orders", 0, members, 3, owner)).isTrue();
        members.stream()
               .filter(node -> !node.equals(owner))
               .forEach(node ->
                                assertThat(ReplicaPlacement.isOwner("orders", 0, members, 3, node)).isFalse());
    }

    @Test
    void ownershipSpreadsAcrossNodes() {
        var members = nodes(5);
        var ownerCounts = new java.util.HashMap<NodeId, Integer>();
        members.forEach(node -> ownerCounts.put(node, 0));

        for (var p = 0; p < 1000; p++) {
            var owner = placeOrFail("orders", p, members, 3).owner();
            ownerCounts.merge(owner, 1, Integer::sum);
        }

        // loose: every node owns at least one of 1000 partitions
        ownerCounts.values().forEach(count -> assertThat(count).isGreaterThan(0));
    }
}
