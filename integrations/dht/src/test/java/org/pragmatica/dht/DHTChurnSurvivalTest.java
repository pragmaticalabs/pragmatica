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
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// In-JVM churn-survival coverage for the graceful-departure push (issue #427). Runs a small
/// multi-`DHTNode` cluster over a shared in-process dispatch network — no full aether topology — that
/// reproduces the exact scale-down loss mechanism: a key whose ONLY holder is a departing node.
///
/// The red/green pair is the proof:
///   - [#departingNode_uniquelyHeldKey_lostWithoutPush] — a managed departure WITHOUT the push (the
///     pre-fix survivor-only rebalance) loses the uniquely-held key: no survivor ever had a copy to
///     re-replicate from.
///   - [#departingNode_uniquelyHeldKey_survivesViaPush] — the SAME departure WITH the push moves the
///     chunk to the node that newly becomes responsible, so it survives.
class DHTChurnSurvivalTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void departingNode_uniquelyHeldKey_lostWithoutPush() {
        var cluster = fiveNodeCluster();
        var departing = new NodeId("node-3");
        var uniqueKey = cluster.findUniquelyHeldKeyWithNewcomer(departing, "lost");
        cluster.seedOnly(departing, uniqueKey, value("payload"));

        assertThat(cluster.resolve(uniqueKey)).isTrue();

        // Pre-fix behaviour: survivor-side rebalance only, no departing-node push.
        cluster.rebalanceSurvivorsFor(departing);
        cluster.remove(departing);

        assertThat(cluster.resolve(uniqueKey)).as("uniquely-held key is lost when the sole holder departs without pushing").isFalse();
    }

    @Test
    void departingNode_uniquelyHeldKey_survivesViaPush() {
        var cluster = fiveNodeCluster();
        var departing = new NodeId("node-3");
        var uniqueKey = cluster.findUniquelyHeldKeyWithNewcomer(departing, "survive");
        cluster.seedOnly(departing, uniqueKey, value("payload"));

        // The fix: the departing node pushes its held chunks to the new replicas before it leaves.
        cluster.member(departing).rebalancer().pushOnDeparture(DeparturePushObserver.noop()).await();
        cluster.remove(departing);

        assertThat(cluster.resolve(uniqueKey)).as("uniquely-held key survives the departure via the ack-gated push").isTrue();
    }

    @Test
    void churn_5to7to5_uniquelyHeldKeysSurvive_withPush() {
        var cluster = fiveNodeCluster();
        var firstDeparting = new NodeId("node-3");
        var secondDeparting = new NodeId("node-4");

        var seeded = new ArrayList<byte[]>();
        seedUniqueKeys(cluster, firstDeparting, "c-a", seeded);
        seedUniqueKeys(cluster, secondDeparting, "c-b", seeded);

        // Scale up 5 -> 7.
        cluster.add(new NodeId("node-5"));
        cluster.add(new NodeId("node-6"));

        // Managed departure of two original holders, 7 -> 5, each pushing before it leaves.
        departWithPush(cluster, firstDeparting);
        departWithPush(cluster, secondDeparting);

        seeded.forEach(k -> assertThat(cluster.resolve(k)).as("seeded key survives 5->7->5 churn").isTrue());
    }

    private void seedUniqueKeys(DhtCluster cluster, NodeId holder, String prefix, List<byte[]> seeded) {
        for (int i = 0; i < 3; i++) {
            var k = cluster.findUniquelyHeldKeyWithNewcomer(holder, prefix + "-" + i);
            cluster.seedOnly(holder, k, value("payload-" + prefix + "-" + i));
            seeded.add(k);
        }
    }

    private void departWithPush(DhtCluster cluster, NodeId departing) {
        cluster.member(departing).rebalancer().pushOnDeparture(DeparturePushObserver.noop()).await();
        cluster.remove(departing);
    }

    private DhtCluster fiveNodeCluster() {
        var cluster = new DhtCluster();
        for (int i = 0; i < 5; i++) {
            cluster.add(new NodeId("node-" + i));
        }
        return cluster;
    }

    // --- In-process multi-node harness ---

    private record Member(NodeId id,
                          DHTNode node,
                          DHTRebalancer rebalancer,
                          DHTAntiEntropy antiEntropy,
                          MemoryStorageEngine storage,
                          ConsistentHashRing<NodeId> ring) {}

    private static final class DhtCluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();

        void add(NodeId id) {
            members.values().forEach(existing -> existing.ring().addNode(id));
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            members.keySet().forEach(ring::addNode);
            ring.addNode(id);
            var storage = memoryStorageEngine();
            var node = dhtNode(id, storage, ring, CONFIG);
            DHTNetwork network = this::deliver;
            var member = new Member(id,
                                    node,
                                    dhtRebalancer(node, network, CONFIG),
                                    dhtAntiEntropy(node, network, CONFIG),
                                    storage,
                                    ring);
            members.put(id, member);
        }

        void remove(NodeId id) {
            members.remove(id);
            members.values().forEach(existing -> existing.ring().removeNode(id));
        }

        Member member(NodeId id) {
            return members.get(id);
        }

        void seedOnly(NodeId holder, byte[] key, byte[] value) {
            members.get(holder).node().putLocal(key, value).await();
        }

        boolean resolve(byte[] key) {
            return members.values().stream().anyMatch(member -> holds(member, key));
        }

        void rebalanceSurvivorsFor(NodeId departing) {
            members.values()
                   .stream()
                   .filter(member -> !member.id().equals(departing))
                   .forEach(member -> member.rebalancer().onNodeRemoved(departing));
        }

        byte[] findUniquelyHeldKeyWithNewcomer(NodeId holder, String prefix) {
            var ring = members.get(holder).ring();
            var replicationFactor = CONFIG.effectiveReplicationFactor(members.size());
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-probe-" + i);
                if (ring.nodesFor(candidate, replicationFactor).contains(holder)
                    && hasNewcomer(ring, candidate, holder, replicationFactor)) {
                    return candidate;
                }
            }
            throw new AssertionError("no uniquely-held key with a post-departure newcomer found");
        }

        private static boolean hasNewcomer(ConsistentHashRing<NodeId> ring, byte[] key, NodeId holder, int replicationFactor) {
            var newSet = ring.nodesFor(key, replicationFactor, candidate -> !candidate.equals(holder));
            var existing = new java.util.HashSet<>(ring.nodesFor(key, replicationFactor));
            existing.remove(holder);
            return newSet.stream().anyMatch(candidate -> !existing.contains(candidate));
        }

        private static boolean holds(Member member, byte[] key) {
            return member.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent();
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            var member = members.get(target);
            if (member == null) {
                return;  // target has departed — message dropped, as on a real halted node
            }
            route(member, message);
        }

        private void route(Member member, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.MigrationDataResponse response -> member.antiEntropy().onMigrationDataResponse(response);
                case DHTMessage.MigrationDataAck ack -> member.rebalancer().onMigrationDataAck(ack);
                default -> { }
            }
        }
    }
}
