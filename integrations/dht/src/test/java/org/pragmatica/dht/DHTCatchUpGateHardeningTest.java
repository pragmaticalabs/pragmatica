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
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTTopologyListener.dhtTopologyListener;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1777 track 2, v1820 hardening of the catch-up gate: a serving source never suppresses the union, a
/// partition is never served without hearing from a source that exists, and a lost pending partition
/// leaves the set. Harness copied from DHTCatchUpGateTest.
///
/// #1777 track 2: the catch-up gate. A node that became a replica of a partition through a ring change, or
/// at boot, answers "not caught up" for it until anti-entropy has filled it from the nodes that may hold its
/// data — the current co-replicas and the surviving previous holders — and reads count "absent" only from
/// replicas that are serving.
///
/// Runs a small multi-node cluster over a synchronous in-process network. Every node has a real
/// [DHTNode], [DHTAntiEntropy], [DHTTopologyListener] and [DistributedDHTClient]; no periodic task runs, so
/// every catch-up step in a test is one the test asked for.
class DHTCatchUpGateHardeningTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(1).seconds());

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- P2: a serving, empty non-owner anchors the round, so catching-up holders are never pulled ----
    @Test
    void p2_wholeReplicaSetRestarted_copyOnOneOfThem_reachesTheOthers_evenWithServingNonOwnersInTheWalk() {
        var cluster = Cluster.of(6);
        var key = bytes("pushed-onto-one");
        var replicas = cluster.replicasOf(key);

        replicas.forEach(cluster::restart);
        cluster.seedOnly(replicas.get(1), key);   // a departure push landed on one of them
        cluster.member(replicas.get(0)).antiEntropy().catchUpNow();   // rounds race: the empty ones decide first
        cluster.member(replicas.get(2)).antiEntropy().catchUpNow();
        cluster.catchUpEverywhere();

        var holders = replicas.stream().filter(id -> cluster.holds(id, key)).map(NodeId::id).toList();
        System.out.printf("P2 replicas=%s holdersAfter=%s pending=%s%n", replicas, holders,
                          replicas.stream().map(id -> cluster.member(id).node().readiness(cluster.partitionOf(key))).toList());
        cluster.silence(replicas.get(1));
        var reader = cluster.readerOutside(replicas);
        var read = cluster.member(reader).client().get(key).await();
        System.out.printf("P2 read with the one holder silent: %s%n", read);
        assertThat(holders).as("the union must reach every replica (T4's guarantee)").hasSize(3);
    }

    /// P2 control: the same on a 3-node cluster (no non-owners) unions — T4.
    @Test
    void p2control_threeNodes_unions() {
        var cluster = Cluster.of(3);
        var key = bytes("pushed-onto-one");
        var replicas = cluster.replicasOf(key);

        replicas.forEach(cluster::restart);
        cluster.seedOnly(replicas.get(1), key);
        cluster.catchUpEverywhere();

        assertThat(replicas.stream().filter(id -> cluster.holds(id, key)).count()).isEqualTo(3);
    }

    // ---- P3: no live source (liveness view = self only) completes every pending partition at once ----
    @Test
    void p3_restartedReplica_withOnlySelfLive_doesNotServeBeforeHearingFromAnySource() {
        var cluster = Cluster.of(4);
        var key = bytes("held-by-peers");
        var replicas = cluster.replicasOf(key);
        var restarted = replicas.getFirst();

        cluster.seedOnReplicas(key);
        cluster.restart(restarted);
        cluster.onlySelfLive = true;
        cluster.clearTraffic();
        cluster.member(restarted).antiEntropy().catchUpNow();

        System.out.printf("P3 traffic=%d readiness=%s holds=%s pendingLeft=%d%n", cluster.catchUpTraffic(),
                          cluster.member(restarted).node().readiness(cluster.partitionOf(key)),
                          cluster.holds(restarted, key), cluster.member(restarted).node().pendingPartitions().size());
        assertThat(cluster.member(restarted).node().readiness(cluster.partitionOf(key)))
            .as("serving after zero answers from anyone").isEqualTo(Readiness.CATCHING_UP);
    }

    // ---- P4: a partition lost while pending stays pending forever ----
    @Test
    void p4_partitionsLostWhilePending_leaveThePendingSet() {
        var cluster = Cluster.of(4);
        var x = new NodeId("node-0");

        cluster.restart(x);
        var before = cluster.member(x).node().pendingPartitions().size();
        for (int i = 4; i < 8; i++) {
            cluster.joinWithoutCatchUp(new NodeId("node-" + i));
        }
        cluster.catchUpEverywhere();
        var node = cluster.member(x).node();
        var rf = CONFIG.effectiveReplicationFactor(node.ring().nodeCount());
        var notOwned = node.pendingPartitions().stream().filter(p -> !node.ring().nodesFor(p, rf).contains(x)).count();

        System.out.printf("P4 pendingAtBoot=%d pendingNow=%d ofWhichNotOwned=%d%n", before, node.pendingPartitions().size(), notOwned);
        assertThat(notOwned).as("design: a lost partition just leaves the set").isZero();
    }

    private record Member(NodeId id,
                          DHTNode node,
                          DHTAntiEntropy antiEntropy,
                          DHTTopologyListener listener,
                          DistributedDHTClient client) {}

    private record Gained(byte[] key, NodeId newcomer) {}

    /// A store that refuses every versioned write — the path migrated copies take — as a full disk or a
    /// rejecting fence would. Everything else delegates.
    private record RefusingCopies(StorageEngine delegate) implements StorageEngine {
        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return delegate.get(key);
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return delegate.put(key, value);
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return delegate.remove(key);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return delegate.exists(key);
        }

        @Override
        public Promise<Boolean> putVersioned(byte[] key,
                                             byte[] value,
                                             long version,
                                             long epochIncarnation,
                                             long epochTerm,
                                             long epochCounter) {
            return Causes.cause("copy refused").promise();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public Promise<Unit> clear() {
            return delegate.clear();
        }

        @Override
        public Promise<Unit> shutdown() {
            return delegate.shutdown();
        }

        @Override
        public Promise<List<byte[]>> keys() {
            return delegate.keys();
        }

        @Override
        public Promise<List<DHTMessage.KeyValue>> entries() {
            return delegate.entries();
        }

        @Override
        public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition) {
            return delegate.entriesForPartition(ring, partition);
        }
    }

    private static final class Cluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final Set<NodeId> silenced = new HashSet<>();
        private final Set<NodeId> dead = new HashSet<>();
        private int catchUpTraffic;

        /// `size` nodes formed and serving: every node knows every other.
        static Cluster of(int size) {
            var cluster = new Cluster();
            var ids = ids(size);

            ids.forEach(id -> cluster.members.put(id, cluster.member(id, ids)));

            return cluster;
        }

        /// `size` nodes booting together with empty stores: every owned partition starts catching up.
        static Cluster booting(int size) {
            var cluster = of(size);

            cluster.members.values().forEach(member -> member.node().beginCatchUp());

            return cluster;
        }

        private static List<NodeId> ids(int size) {
            return java.util.stream.IntStream.range(0, size).mapToObj(i -> new NodeId("node-" + i)).toList();
        }

        private Member member(NodeId id, List<NodeId> ringMembers) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ringMembers.forEach(ring::addNode);

            var node = dhtNode(id, memoryStorageEngine(), ring, CONFIG);
            DHTNetwork network = network();
            var antiEntropy = dhtAntiEntropy(node, network, CONFIG);
            var listener = dhtTopologyListener(node, dhtRebalancer(node, network, CONFIG), antiEntropy);

            return new Member(id, node, antiEntropy, listener, distributedDHTClient(node, network, CONFIG));
        }

        Member member(NodeId id) {
            return members.get(id);
        }

        /// Delivers synchronously; reports as live every member not killed (no view at all until one is).
        private DHTNetwork network() {
            return new DHTNetwork() {
                @Override
                public void send(NodeId target, ProtocolMessage message) {
                    deliver(target, message);
                }

                @Override
                public Set<NodeId> livePeers() {
                    return Cluster.this.livePeers();
                }
            };
        }

        /// The existing members learn of `joiner` through their listeners; the joiner itself boots knowing the
        /// whole ring and catching up. No anti-entropy round runs on the joiner.
        void joinWithoutCatchUp(NodeId joiner) {
            var ids = new java.util.ArrayList<>(members.keySet());

            ids.add(joiner);
            members.values().forEach(member -> member.node().changeRing(ring -> ring.addNode(joiner)));
            members.put(joiner, member(joiner, ids));
            members.get(joiner).node().beginCatchUp();
        }

        /// Like [#joinWithoutCatchUp], but the joiner's store accepts fresh writes and refuses every copy.
        void joinRefusingCopies(NodeId joiner) {
            var ids = new java.util.ArrayList<>(members.keySet());

            ids.add(joiner);
            members.values().forEach(member -> member.node().changeRing(ring -> ring.addNode(joiner)));

            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var node = dhtNode(joiner, new RefusingCopies(memoryStorageEngine()), ring, CONFIG);
            DHTNetwork network = network();
            var antiEntropy = dhtAntiEntropy(node, network, CONFIG);

            members.put(joiner,
                        new Member(joiner,
                                   node,
                                   antiEntropy,
                                   dhtTopologyListener(node, dhtRebalancer(node, network, CONFIG), antiEntropy),
                                   distributedDHTClient(node, network, CONFIG)));
            node.beginCatchUp();
        }

        /// Only the nodes in `aware` learn of the joiner — the others' rings disagree with it.
        void joinOnlyOn(NodeId joiner, Set<NodeId> aware) {
            var ids = new java.util.ArrayList<>(members.keySet());

            ids.add(joiner);
            members.values()
                   .stream()
                   .filter(member -> aware.contains(member.id()))
                   .forEach(member -> member.node().changeRing(ring -> ring.addNode(joiner)));
            members.put(joiner, member(joiner, ids));
            members.get(joiner).node().beginCatchUp();
        }

        /// A joiner boots with the current ring plus itself; every existing member learns of it.
        void bootJoiner(NodeId joiner) {
            joinWithoutCatchUp(joiner);
        }

        /// Every survivor learns of the removal through its listener; `last` hears of it after the others.
        void removeThroughListeners(NodeId removed, NodeId last) {
            members.remove(removed);
            silenced.add(removed);

            var view = List.copyOf(members.keySet());
            var decision = MembershipDecision.nodeRemoved(removed, view);

            members.values()
                   .stream()
                   .filter(member -> !member.id().equals(last))
                   .forEach(member -> member.listener().onNodeRemoved(decision));
            members.get(last).listener().onNodeRemoved(decision);
        }

        void catchUpEverywhere() {
            for (int round = 0; round < 3; round++) {
                members.values().forEach(member -> member.antiEntropy().catchUpNow());
            }
        }

        boolean everyPartitionServing() {
            return members.values().stream().allMatch(member -> member.node().pendingPartitions().isEmpty());
        }

        void silence(NodeId id) {
            silenced.add(id);
        }

        /// Silenced, and reported dead by every node's liveness view.
        void kill(NodeId id) {
            silenced.add(id);
            dead.add(id);
        }

        boolean onlySelfLive = false;

        /// The node restarts with an empty store, the current ring, and every owned partition catching up.
        void restart(NodeId id) {
            var ids = new java.util.ArrayList<>(members.keySet());
            members.put(id, member(id, ids));
            members.get(id).node().beginCatchUp();
        }

        private Set<NodeId> livePeers() {
            if (onlySelfLive) {
                return Set.of(new NodeId("nobody"));
            }
            if (dead.isEmpty()) {
                return Set.of();
            }

            var live = new HashSet<>(members.keySet());

            live.removeAll(dead);

            return live;
        }

        void clearTraffic() {
            catchUpTraffic = 0;
        }

        int catchUpTraffic() {
            return catchUpTraffic;
        }

        List<NodeId> replicasOf(byte[] key) {
            var any = members.values().iterator().next().node();

            return any.ring().nodesFor(key, CONFIG.effectiveReplicationFactor(any.ring().nodeCount()));
        }

        Partition partitionOf(byte[] key) {
            return members.values().iterator().next().node().partitionFor(key);
        }

        NodeId readerOutside(List<NodeId> replicas) {
            return members.keySet().stream().filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
        }

        void seedOnReplicas(byte[] key) {
            replicasOf(key).forEach(holder -> seedOnly(holder, key));
        }

        void seedOnly(NodeId holder, byte[] key) {
            members.get(holder).node().putLocal(key, bytes("payload")).await();
        }

        boolean holds(NodeId id, byte[] key) {
            return members.get(id).node().getLocal(key).await().or(Option.none()).isPresent();
        }

        /// A key the joiner becomes a replica of.
        byte[] keyGainedBy(NodeId joiner, String prefix) {
            var after = ringWith(joiner);

            for (int i = 0; i < 20_000; i++) {
                var candidate = bytes(prefix + "-" + i);

                if (after.nodesFor(candidate, 3).contains(joiner)) {
                    return candidate;
                }
            }

            throw new AssertionError("no key gained by " + joiner.id());
        }

        /// A key whose whole replica set, once the joiners are in the ring, is the joiners.
        byte[] keyWholeSetReplacedBy(List<NodeId> joiners, String prefix) {
            var after = ringWith(joiners.toArray(NodeId[]::new));

            for (int i = 0; i < 200_000; i++) {
                var candidate = bytes(prefix + "-" + i);

                if (Set.copyOf(after.nodesFor(candidate, 3)).equals(Set.copyOf(joiners))) {
                    return candidate;
                }
            }

            throw new AssertionError("no key whose replica set is wholly replaced");
        }

        /// A key whose replica set gains a node that never held it when `removed` leaves.
        Gained keyGainedOnRemoval(NodeId removed, String prefix) {
            var current = members.values().iterator().next().node().ring();
            var after = ConsistentHashRing.<NodeId>consistentHashRing();

            members.keySet().stream().filter(id -> !id.equals(removed)).forEach(after::addNode);

            for (int i = 0; i < 20_000; i++) {
                var candidate = bytes(prefix + "-" + i);
                var before = current.nodesFor(candidate, 3);

                if (before.contains(removed)) {
                    var fresh = after.nodesFor(candidate, 3).stream().filter(id -> !before.contains(id)).toList();

                    if (fresh.size() == 1) {
                        return new Gained(candidate, fresh.getFirst());
                    }
                }
            }

            throw new AssertionError("no key gaining a newcomer on removal");
        }

        private ConsistentHashRing<NodeId> ringWith(NodeId... extra) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            members.keySet().forEach(ring::addNode);
            List.of(extra).forEach(ring::addNode);

            return ring;
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            countCatchUp(message);

            var member = members.get(target);

            if (member == null || silenced.contains(target)) {
                return;
            }

            route(member, message);
        }

        private void countCatchUp(ProtocolMessage message) {
            if (message instanceof DHTMessage.DigestRequest || message instanceof DHTMessage.MigrationDataRequest) {
                catchUpTraffic++;
            }
        }

        private void route(Member member, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.GetRequest request ->
                    member.node().handleGetRequest(request, response -> reply(request.sender(), response));
                case DHTMessage.GetResponse response -> member.client().onGetResponse(response);
                case DHTMessage.ExistsRequest request ->
                    member.node().handleExistsRequest(request, response -> reply(request.sender(), response));
                case DHTMessage.ExistsResponse response -> member.client().onExistsResponse(response);
                case DHTMessage.DigestRequest request ->
                    member.node().handleDigestRequest(request, response -> reply(request.sender(), response));
                case DHTMessage.DigestResponse response -> member.antiEntropy().onDigestResponse(response);
                case DHTMessage.MigrationDataRequest request ->
                    member.node().handleMigrationDataRequest(request, response -> reply(request.sender(), response));
                case DHTMessage.MigrationDataResponse response -> member.antiEntropy().onMigrationDataResponse(response);
                default -> { }
            }
        }

        /// A silenced node neither receives nor answers.
        private void reply(NodeId to, ProtocolMessage response) {
            if (response instanceof DHTMessage.GetResponse get && silenced.contains(get.sender())) {
                return;
            }

            deliver(to, response);
        }
    }
}
