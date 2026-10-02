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

/// #1777 track 2: the catch-up gate. A node that became a replica of a partition through a ring change, or
/// at boot, answers "not caught up" for it until anti-entropy has filled it from the nodes that may hold its
/// data — the current co-replicas and the surviving previous holders — and reads count "absent" only from
/// replicas that are serving.
///
/// Runs a small multi-node cluster over a synchronous in-process network. Every node has a real
/// [DHTNode], [DHTAntiEntropy], [DHTTopologyListener] and [DistributedDHTClient]; no periodic task runs, so
/// every catch-up step in a test is one the test asked for.
class DHTCatchUpGateTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(1).seconds());

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// T1: a joiner is catching up for every partition it gained, and serving once its round pulled.
    @Test
    void joiner_answersCatchingUp_forAGainedPartition_untilItsRoundHasPulled() {
        var cluster = Cluster.of(5);
        var joiner = new NodeId("node-5");
        var key = cluster.keyGainedBy(joiner, "t1");

        cluster.seedOnReplicas(key);
        cluster.joinWithoutCatchUp(joiner);

        var partition = cluster.partitionOf(key);

        assertThat(cluster.member(joiner).node().readiness(partition)).as("a gained partition starts catching up")
                                                                     .isEqualTo(Readiness.CATCHING_UP);

        cluster.member(joiner).antiEntropy().catchUpNow();

        assertThat(cluster.member(joiner).node().readiness(partition)).isEqualTo(Readiness.SERVING);
        assertThat(cluster.holds(joiner, key)).as("it became serving by holding the data").isTrue();
    }

    /// T2, the v1770-F1 shape: RF3/R2, the key on two of its three replicas (the third missed the W=2 write),
    /// and a join displaces one holder. Before the joiner is filled, the read meets the replica that missed
    /// the write and the empty joiner while the remaining holder is slow and the displaced one unreachable
    /// (reachable, the #428 fallback probe would find its copy and mask the defect). Counting the joiner's
    /// empty as a vote resolves a false "absent"; the gate makes the read fail retryably instead.
    @Test
    void readAcrossAJoin_isNeverAFalseAbsent_whileTheJoinerIsCatchingUp() {
        var cluster = Cluster.of(5);
        var joiner = new NodeId("node-5");
        var key = cluster.keyGainedBy(joiner, "t2");
        var before = cluster.replicasOf(key);

        cluster.joinWithoutCatchUp(joiner);

        var after = cluster.replicasOf(key);
        var stayed = after.stream().filter(before::contains).toList();
        var missedTheWrite = stayed.getLast();
        var slowHolder = stayed.getFirst();

        before.stream()
              .filter(holder -> !holder.equals(missedTheWrite))
              .forEach(holder -> cluster.seedOnly(holder, key));
        cluster.silence(slowHolder);
        before.stream().filter(holder -> !after.contains(holder)).forEach(cluster::silence);

        var reader = cluster.readerOutside(after);
        var read = cluster.member(reader).client().get(key).await();

        assertThat(read.isFailure() || read.or(Option.none()).isPresent()).as("the read is never a false absent: %s", read)
                                                                          .isTrue();
    }

    /// T3: genesis. Every replica boots empty and catching up; no source is serving, so the anchorless rule
    /// makes them serving after one round, and an absent key reads absent rather than refusing forever.
    @Test
    void genesis_everyReplicaEmpty_becomesServingAfterOneRound_andAbsentReadsAbsent() {
        var cluster = Cluster.booting(3);

        cluster.catchUpEverywhere();

        assertThat(cluster.everyPartitionServing()).isTrue();
        assertThat(cluster.member(new NodeId("node-0")).client().get(bytes("nothing-here")).await())
            .isEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// T4: when no source is serving, completion unions what the catching-up sources hold — a copy a
    /// departing holder pushed onto one of them survives onto the others.
    @Test
    void anchorlessCompletion_unionsWhatTheCatchingUpSourcesHold() {
        var cluster = Cluster.booting(3);
        var key = bytes("pushed-before-genesis-round");
        var holder = new NodeId("node-0");
        var other = new NodeId("node-1");

        cluster.seedOnly(holder, key);
        cluster.member(other).antiEntropy().catchUpNow();

        assertThat(cluster.member(other).node().readiness(cluster.partitionOf(key))).isEqualTo(Readiness.SERVING);
        assertThat(cluster.holds(other, key)).as("the anchorless round pulled the union").isTrue();
    }

    /// T5: a removal makes a survivor the replica of partitions it never held, and starts filling them at
    /// once — not at the next tick. The newcomer hears of the removal last, so the holders it pulls from
    /// already agree it is a replica (until then they refuse, and the next tick retries).
    @Test
    void removal_startsCatchUpOfTheGainedPartitions_immediately() {
        var cluster = Cluster.of(5);
        var removed = new NodeId("node-2");
        var gained = cluster.keyGainedOnRemoval(removed, "t5");
        var newcomer = gained.newcomer();
        var key = gained.key();

        cluster.seedOnReplicas(key);
        cluster.removeThroughListeners(removed, newcomer);

        assertThat(cluster.member(newcomer).node().readiness(cluster.partitionOf(key))).isEqualTo(Readiness.SERVING);
        assertThat(cluster.holds(newcomer, key)).isTrue();
    }

    /// T6: `UNKNOWN` — the codec sentinel — is never authoritative, and is last.
    @Test
    void readiness_unknownIsTheLastConstant_andOnlyServingIsAuthoritative() {
        assertThat(Readiness.values()[Readiness.values().length - 1]).isEqualTo(Readiness.UNKNOWN);
        assertThat(Readiness.SERVING.authoritative()).isTrue();
        assertThat(Readiness.CATCHING_UP.authoritative()).isFalse();
        assertThat(Readiness.UNKNOWN.authoritative()).isFalse();
    }

    /// T8: in steady state the gate costs nothing — an absent key reads absent, with no catch-up traffic.
    @Test
    void steadyState_absentRead_resolvesAbsent_withNoCatchUpTraffic() {
        var cluster = Cluster.of(5);

        cluster.clearTraffic();

        var read = cluster.member(new NodeId("node-0")).client().get(bytes("absent-in-steady-state")).await();

        assertThat(read).isEqualTo(Result.success(Option.<byte[]>none()));
        assertThat(cluster.catchUpTraffic()).as("no digest or pull was sent").isZero();
    }

    /// T9: a holder whose ring disagrees refuses explicitly; the refusal is counted, and the partition stays
    /// catching up rather than taking the refusal for an empty, completed pull.
    @Test
    void refusedPull_isCounted_andDoesNotCompleteTheCatchUp() {
        var cluster = Cluster.of(5);
        var joiner = new NodeId("node-5");
        var key = cluster.keyGainedBy(joiner, "t9");

        cluster.seedOnReplicas(key);
        cluster.joinOnlyOn(joiner, Set.of(joiner));

        var member = cluster.member(joiner);

        member.antiEntropy().catchUpNow();

        assertThat(member.antiEntropy().refusedPullCount()).as("the holders, whose rings lack the joiner, refused")
                                                           .isPositive();
        assertThat(member.node().readiness(cluster.partitionOf(key))).isEqualTo(Readiness.CATCHING_UP);
    }

    /// T10 (ruling C1): three nodes join together and replace a partition's WHOLE replica set; every new
    /// replica boots empty while the old holders are still ring members. The old holders are catch-up
    /// sources, so the new replicas fill from them instead of completing anchorless on an empty union.
    @Test
    void wholeReplicaSetReplaced_withTheOldHoldersAlive_fillsFromThemAndNeverReadsAbsent() {
        var cluster = Cluster.of(3);
        var joiners = List.of(new NodeId("node-3"), new NodeId("node-4"), new NodeId("node-5"));
        var key = cluster.keyWholeSetReplacedBy(joiners, "t10");

        cluster.seedOnReplicas(key);
        joiners.forEach(cluster::bootJoiner);
        cluster.catchUpEverywhere();

        assertThat(cluster.replicasOf(key)).as("control: the new replica set is exactly the joiners")
                                           .containsExactlyInAnyOrderElementsOf(joiners);
        joiners.forEach(joiner -> assertThat(cluster.holds(joiner, key)).as("%s filled from an old holder", joiner)
                                                                        .isTrue());
        assertThat(cluster.member(joiners.getFirst()).client().get(key).await().or(Option.none()).isPresent()).isTrue();
    }

    /// T11: a whole-cluster cold restart is genesis — the in-memory data is gone everywhere, every replica
    /// boots catching up, and after one round reads answer absent rather than refusing forever.
    @Test
    void wholeClusterColdRestart_behavesAsGenesis() {
        var cluster = Cluster.booting(6);

        cluster.catchUpEverywhere();

        assertThat(cluster.everyPartitionServing()).isTrue();
        assertThat(cluster.member(new NodeId("node-4")).client().get(bytes("lost-in-restart")).await())
            .isEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// T2b: when quorum becomes unreachable because replicas refused as catching up, the read fails with
    /// the transient [DHTError.NotCaughtUp] — never "absent", and distinguishable from an unreachable quorum.
    @Test
    void readMeetingOnlyCatchingUpReplicas_failsNotCaughtUp() {
        var cluster = Cluster.booting(3);

        var read = cluster.member(new NodeId("node-0")).client().get(bytes("asked-too-early")).await();

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.NotCaughtUp.class));
    }

    /// T13: `exists` is gated like `get`: a `false` from a catching-up replica is a refusal.
    @Test
    void existsMeetingOnlyCatchingUpReplicas_failsNotCaughtUp() {
        var cluster = Cluster.booting(3);

        var exists = cluster.member(new NodeId("node-0")).client().exists(bytes("asked-too-early")).await();

        assertThat(exists.isFailure()).as("never a false 'does not exist': %s", exists).isTrue();
        exists.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.NotCaughtUp.class));
    }

    /// T12: completion is proven by readback. A copy the local store refuses leaves the partition catching
    /// up instead of letting it serve without the data.
    @Test
    void pulledCopyTheStoreRefuses_leavesThePartitionCatchingUp() {
        var cluster = Cluster.of(5);
        var joiner = new NodeId("node-5");
        var key = cluster.keyGainedBy(joiner, "t12");

        cluster.seedOnReplicas(key);
        cluster.joinRefusingCopies(joiner);
        cluster.member(joiner).antiEntropy().catchUpNow();

        assertThat(cluster.member(joiner).node().readiness(cluster.partitionOf(key))).isEqualTo(Readiness.CATCHING_UP);
    }

    // --- In-process cluster ---

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
            DHTNetwork network = this::deliver;
            var antiEntropy = dhtAntiEntropy(node, network, CONFIG);
            var listener = dhtTopologyListener(node, dhtRebalancer(node, network, CONFIG), antiEntropy);

            return new Member(id, node, antiEntropy, listener, distributedDHTClient(node, network, CONFIG));
        }

        Member member(NodeId id) {
            return members.get(id);
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
            DHTNetwork network = this::deliver;
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
