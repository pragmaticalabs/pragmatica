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
import org.pragmatica.consensus.topology.MembershipDecision;
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
import static org.pragmatica.dht.DHTTopologyListener.dhtTopologyListener;
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

    /// Issue #420 — join-time backfill. After a join the ring says RF=3 while only the two pre-join
    /// holders that stayed in the responsible set actually hold the key; the joiner counts toward
    /// the factor and holds nothing until the next periodic anti-entropy round (30s). A crash of
    /// those two holders inside that window leaves the responsible set with ZERO copies. The fix
    /// runs one anti-entropy round on the joiner at join time, so it holds its partitions before
    /// the window opens. No periodic round runs in this harness (anti-entropy is never started), so
    /// the only pull is the join-time one.
    @Test
    void joinerHoldsItsPartitions_beforeTheFirstPeriodicRound_soTwoCrashesInsideTheWindowKeepTheReplicaSetStocked() {
        var cluster = fiveNodeCluster();
        var joiner = new NodeId("node-5");
        var key = cluster.findKeyGainedByJoiner(joiner, "join");
        var preJoinHolders = cluster.responsibleFor(key);
        preJoinHolders.forEach(holder -> cluster.seedOnly(holder, key, value("payload")));

        cluster.join(joiner);

        assertThat(cluster.holds(joiner, key)).as("the joiner pulled its partition at join time").isTrue();
        assertThat(cluster.inSetCopies(key)).as("the responsible set is fully stocked right after the join").isEqualTo(3);

        var inSetOldHolders = cluster.responsibleFor(key).stream().filter(preJoinHolders::contains).toList();
        assertThat(inSetOldHolders).as("two pre-join holders remain in the post-join set").hasSize(2);
        inSetOldHolders.forEach(cluster::remove);  // crash: no departure push

        assertThat(cluster.inSetCopies(key)).as("the responsible set still holds the key").isGreaterThanOrEqualTo(1);
    }

    /// The control for the test above: the same join without the join-time round. The joiner is
    /// empty, the two crashes leave the responsible set with zero copies — the pre-fix window.
    @Test
    void withoutTheJoinTimeRound_twoCrashesInsideTheWindowEmptyTheReplicaSet() {
        var cluster = fiveNodeCluster();
        var joiner = new NodeId("node-5");
        var key = cluster.findKeyGainedByJoiner(joiner, "control");
        var preJoinHolders = cluster.responsibleFor(key);
        preJoinHolders.forEach(holder -> cluster.seedOnly(holder, key, value("payload")));

        cluster.add(joiner);  // ring updated, no listener, no pull

        assertThat(cluster.holds(joiner, key)).isFalse();
        assertThat(cluster.inSetCopies(key)).as("pre-fix: the set claims RF=3 and holds RF-1 copies").isEqualTo(2);

        cluster.responsibleFor(key).stream().filter(preJoinHolders::contains).toList().forEach(cluster::remove);

        assertThat(cluster.holds(joiner, key)).as("the joiner never received the key").isFalse();
        assertThat(cluster.resolve(key)).as("only the third pre-join holder's stranded copy is left").isTrue();
    }

    /// A drain that races the joiner's pull: the departure push excludes every CURRENT member of
    /// the responsible set, joiner included, so a not-yet-backfilled joiner is not pushed to. The
    /// key still survives on the push's newcomer target, and the joiner's own round (here run late)
    /// completes the set from that holder — which is why membership, not holding, remains the
    /// push's exclusion criterion (a per-key holding query would need a new wire message).
    @Test
    void drainRacingTheJoinersPull_keepsTheKeyOnTheNewcomer_andTheLateRoundCompletesTheJoiner() {
        var cluster = fiveNodeCluster();
        var joiner = new NodeId("node-5");
        var key = cluster.findKeyGainedByJoiner(joiner, "race");
        var preJoinHolders = cluster.responsibleFor(key);
        preJoinHolders.forEach(holder -> cluster.seedOnly(holder, key, value("payload")));

        cluster.add(joiner);  // pull not yet run

        var departing = cluster.responsibleFor(key).stream().filter(preJoinHolders::contains).toList();
        departing.forEach(node -> departWithPush(cluster, node));

        assertThat(cluster.holds(joiner, key)).as("the push skipped the joiner (a current member)").isFalse();
        assertThat(cluster.inSetCopies(key)).as("the push stocked the newcomers").isGreaterThanOrEqualTo(1);

        cluster.member(joiner).antiEntropy().synchronizeNow();

        assertThat(cluster.holds(joiner, key)).as("the joiner's round pulls from the stocked newcomer").isTrue();
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
                          DHTTopologyListener topologyListener,
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
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            var antiEntropy = dhtAntiEntropy(node, network, CONFIG);
            var member = new Member(id,
                                    node,
                                    rebalancer,
                                    antiEntropy,
                                    dhtTopologyListener(node, rebalancer, antiEntropy),
                                    storage,
                                    ring);
            members.put(id, member);
        }

        /// A consensus-committed join: every member (the joiner included) receives `NodeJoined`
        /// through its topology listener, as `AetherNode` routes `MembershipDecision.NodeJoined`.
        void join(NodeId id) {
            add(id);
            var view = List.copyOf(members.keySet());
            var decision = MembershipDecision.nodeJoined(id, view);
            members.values().forEach(member -> member.topologyListener().onNodeJoined(decision));
        }

        List<NodeId> responsibleFor(byte[] key) {
            var any = members.values().iterator().next();
            return any.ring().nodesFor(key, CONFIG.effectiveReplicationFactor(members.size()));
        }

        long inSetCopies(byte[] key) {
            return responsibleFor(key).stream().filter(id -> members.containsKey(id) && holds(id, key)).count();
        }

        boolean holds(NodeId id, byte[] key) {
            return holds(members.get(id), key);
        }

        /// A key held by exactly three pre-join members whose responsible set gains the joiner.
        byte[] findKeyGainedByJoiner(NodeId joiner, String prefix) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            members.keySet().forEach(ring::addNode);
            var rf = CONFIG.effectiveReplicationFactor(members.size());
            var after = ConsistentHashRing.<NodeId>consistentHashRing();
            members.keySet().forEach(after::addNode);
            after.addNode(joiner);
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-probe-" + i);
                if (ring.nodesFor(candidate, rf).size() == rf && after.nodesFor(candidate, rf).contains(joiner)) {
                    return candidate;
                }
            }
            throw new AssertionError("no key whose responsible set gains the joiner found");
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
                case DHTMessage.DigestRequest request ->
                    member.node().handleDigestRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.DigestResponse response -> member.antiEntropy().onDigestResponse(response);
                case DHTMessage.MigrationDataRequest request ->
                    member.node().handleMigrationDataRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.MigrationDataResponse response -> member.antiEntropy().onMigrationDataResponse(response);
                case DHTMessage.MigrationDataAck ack -> member.rebalancer().onMigrationDataAck(ack);
                default -> { }
            }
        }
    }
}
