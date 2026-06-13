/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Verifies the seed-500 part-2 DEPARTING-edge ring prune ([`DHTTopologyListener#onNodeDeparting`]):
/// a node entering the membership DEPARTING state is pruned from the consistent-hash ring at the
/// drain-command edge — seconds ahead of the SWIM-driven `NodeRemoved` DEAD-edge — so a key whose
/// replicas included the drainer re-replicates its ownership to LIVE nodes inside the in-flight
/// write's bounded retry window. The prune reuses the SAME ring-prune + rebalance as the DEAD edge
/// and is strictly idempotent with it.
class DHTTopologyListenerDepartingTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId DEPARTING = new NodeId("departing");

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static DHTNode threeNodeNode() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(LOCAL);
        ring.addNode(PEER_A);
        ring.addNode(DEPARTING);
        return dhtNode(LOCAL, memoryStorageEngine(), ring, DHTConfig.DEFAULT);
    }

    private static MembershipDecision.NodeRemoved nodeRemoved(NodeId nodeId) {
        return new MembershipDecision.NodeRemoved(nodeId, List.of(), 1L, HlcTimestamp.ZERO);
    }

    private static MembershipDecision.NodeJoined nodeJoined(NodeId nodeId) {
        return new MembershipDecision.NodeJoined(nodeId, List.of(), 1L, HlcTimestamp.ZERO);
    }

    /// The keys that hash onto the drainer in a 3-node, RF=3 ring: every key resolves to all three
    /// nodes, so the drainer is a replica of every key — proving the prune across the keyspace.
    private static boolean ringHoldsDepartingForAnyKey(DHTNode node) {
        for (int i = 0; i < 100; i++) {
            if (node.ring()
                    .nodesFor(key("k-" + i), 3)
                    .contains(DEPARTING)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void onNodeDeparting_removesNodeFromRing_soNodesForNoLongerReturnsIt() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node, dhtRebalancer(node, (target, message) -> {}, DHTConfig.DEFAULT));
        assertThat(ringHoldsDepartingForAnyKey(node)).as("drainer is a replica before the prune").isTrue();

        listener.onNodeDeparting(DEPARTING);

        assertThat(node.ring().nodes()).doesNotContain(DEPARTING);
        assertThat(ringHoldsDepartingForAnyKey(node)).as("no key routes to the drainer after the prune").isFalse();
    }

    @Test
    void onNodeDeparting_leavesOtherNodesInRing_prunesOnlyTheDrainer() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node, dhtRebalancer(node, (target, message) -> {}, DHTConfig.DEFAULT));

        listener.onNodeDeparting(DEPARTING);

        assertThat(node.ring().nodes()).containsExactlyInAnyOrder(LOCAL, PEER_A);
    }

    @Test
    void onNodeDeparting_thenDeadEdgeForSameNode_isIdempotentNoOp() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node, dhtRebalancer(node, (target, message) -> {}, DHTConfig.DEFAULT));

        listener.onNodeDeparting(DEPARTING);
        var afterDeparting = node.ring().nodes();

        listener.onNodeRemoved(nodeRemoved(DEPARTING));

        assertThat(node.ring().nodes()).as("a later DEAD-edge prune for an already-departed node changes nothing")
                                       .isEqualTo(afterDeparting)
                                       .containsExactlyInAnyOrder(LOCAL, PEER_A);
    }

    @Test
    void onNodeDeparting_withoutRebalancer_stillPrunesRing() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node);

        listener.onNodeDeparting(DEPARTING);

        assertThat(node.ring().nodes()).doesNotContain(DEPARTING);
    }

    @Test
    void onNodeRecovered_afterDepartingPrune_reAddsNodeToRing() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node, dhtRebalancer(node, (target, message) -> {}, DHTConfig.DEFAULT));

        listener.onNodeDeparting(DEPARTING);
        assertThat(node.ring().nodes()).as("pruned on DEPARTING").doesNotContain(DEPARTING);

        listener.onNodeRecovered(DEPARTING);

        assertThat(node.ring().nodes()).as("re-added on recovery — symmetry restored").containsExactlyInAnyOrder(LOCAL, PEER_A, DEPARTING);
        assertThat(ringHoldsDepartingForAnyKey(node)).as("the recovered node owns replicas again").isTrue();
    }

    @Test
    void onNodeRecovered_thenNodeJoinedForSameNode_isIdempotentNoOp() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node, dhtRebalancer(node, (target, message) -> {}, DHTConfig.DEFAULT));

        listener.onNodeDeparting(DEPARTING);
        listener.onNodeRecovered(DEPARTING);
        var afterRecovery = node.ring().nodes();

        listener.onNodeJoined(nodeJoined(DEPARTING));

        assertThat(node.ring().nodes()).as("a concurrent NodeJoined for an already-re-added node changes nothing")
                                       .isEqualTo(afterRecovery)
                                       .containsExactlyInAnyOrder(LOCAL, PEER_A, DEPARTING);
    }

    @Test
    void onNodeRecovered_alreadyPresent_isIdempotentNoOp() {
        var node = threeNodeNode();
        var listener = DHTTopologyListener.dhtTopologyListener(node);

        // DEPARTING is still in the ring (never pruned) — a stray recovery must not duplicate it.
        listener.onNodeRecovered(DEPARTING);

        assertThat(node.ring().nodes()).containsExactlyInAnyOrder(LOCAL, PEER_A, DEPARTING);
    }
}
