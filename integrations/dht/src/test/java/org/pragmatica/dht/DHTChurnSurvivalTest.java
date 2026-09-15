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
    /// Keyspace of the production-shaped join pin. Large enough that an over-pulling round strands
    /// hundreds of keys (~90% of the keyspace at RF 3 on 5 nodes), small enough to stay in-JVM cheap.
    private static final int SEEDED_KEYS = 400;

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

    /// The DEPARTING→MEMBER recovery edge re-adds a pruned node to the ring — the same shape as a
    /// join: it counts toward RF again while holding whatever it missed while pruned, so it pulls.
    @Test
    void recoveredNode_pullsWhatItMissedWhilePruned() {
        var cluster = fiveNodeCluster();
        var recovering = new NodeId("node-4");

        cluster.members().forEach(m -> m.topologyListener().onNodeDeparting(recovering));

        var key = cluster.findKeyGainedByRejoin(recovering, "recover");
        cluster.responsibleFor(key).forEach(holder -> cluster.seedOnly(holder, key, value("payload")));

        assertThat(cluster.holds(recovering, key)).as("pruned while the key was written").isFalse();

        cluster.members().forEach(m -> m.topologyListener().onNodeRecovered(recovering));

        assertThat(cluster.holds(recovering, key)).as("the recovery edge runs a round").isTrue();
        assertThat(cluster.responsibleFor(key)).contains(recovering);
    }

    /// Issue #420, round 2 — the SAME join as the test above, shaped the way production shapes it (see
    /// [DhtCluster#joinIncrementally]), and asserted for EVERY position at which the joiner's own
    /// promotion can appear in its staircase. A joiner is a replica of nothing until it is in its own
    /// ring; from that event on, every remaining round runs on a ring that is still missing members,
    /// and on such a ring `nodesFor(partition, effectiveRF)` returns the joiner for ALL 1,024
    /// partitions — so it asked for, and kept, whatever its peers held. Nothing in this module ever
    /// releases an unowned copy.
    ///
    /// The invariant, which does not depend on that order: however the staircase runs, the joiner ends
    /// holding every key it owns and no key it does not. Ownership on a partial ring is settled by the
    /// node that HOLDS the data ([DHTNode#handleMigrationDataRequest]) — a replica is acquired only
    /// where the holder's view and the requester's view agree.
    @Test
    void productionShapedJoin_pullsEveryPartitionItOwns_andNothingElse() {
        var strandedByPosition = new ArrayList<String>();
        var ownedByPosition = new ArrayList<String>();

        for (int selfAnnouncedAt = 0; selfAnnouncedAt <= 5; selfAnnouncedAt++) {
            var cluster = fiveNodeCluster();
            var joiner = new NodeId("node-5");
            var seeded = new ArrayList<byte[]>();

            for (int i = 0; i < SEEDED_KEYS; i++) {
                var seededKey = key("prod-" + selfAnnouncedAt + "-" + i);

                cluster.responsibleFor(seededKey).forEach(holder -> cluster.seedOnly(holder, seededKey, value("payload")));
                seeded.add(seededKey);
            }

            cluster.joinIncrementally(joiner, selfAnnouncedAt);

            var owned = seeded.stream().filter(k -> cluster.responsibleFor(k).contains(joiner)).toList();
            var missing = owned.stream().filter(k -> !cluster.holds(joiner, k)).count();
            var stranded = seeded.stream()
                                 .filter(k -> !cluster.responsibleFor(k).contains(joiner))
                                 .filter(k -> cluster.holds(joiner, k))
                                 .count();

            assertThat(owned).as("control at position %d: the joiner gained partitions, so the rounds had something to pull",
                                 selfAnnouncedAt)
                             .isNotEmpty();
            ownedByPosition.add(selfAnnouncedAt + ":" + owned.size() + "/missing=" + missing);
            strandedByPosition.add(selfAnnouncedAt + ":" + stranded);
        }

        System.out.printf("JOIN-OVER-PULL owned-by-self-announce-position=%s stranded-by-position=%s (of %d seeded)%n",
                          ownedByPosition,
                          strandedByPosition,
                          SEEDED_KEYS);
        assertThat(ownedByPosition).as("the joiner holds every key it owns, at every announcement position")
                                   .allMatch(entry -> entry.endsWith("missing=0"));
        assertThat(strandedByPosition).as("the joiner keeps no key it does not own, at every announcement position")
                                      .allMatch(entry -> entry.endsWith(":0"));
    }

    /// Issue #1136 — the survivor-side rebalance places by partition, so the primary's push after a
    /// crash reaches the node that NEWLY becomes responsible. Keyed on a key whose post-crash owner
    /// set gains exactly one node that never held it: under the pre-#420 placement
    /// (`nodesFor("partition:" + p, rf)` — the partition string hashed as a KEY) the push goes to a
    /// set that agrees with the real owners at chance level, so that newcomer is not stocked.
    @Test
    void survivorRebalance_stocksTheNewlyResponsibleNonHolder() {
        var cluster = fiveNodeCluster();
        var crashing = new NodeId("node-3");
        var gained = cluster.findKeyGainedByExactlyOneNewcomerOnCrash(crashing, "rebalance");
        var newcomer = gained.newcomer();
        var gainedKey = gained.key();

        cluster.responsibleFor(gainedKey).forEach(holder -> cluster.seedOnly(holder, gainedKey, value("payload")));

        assertThat(cluster.holds(newcomer, gainedKey)).as("control: the newcomer held nothing before the crash").isFalse();

        cluster.crashWithSurvivorRebalance(crashing);

        assertThat(cluster.responsibleFor(gainedKey)).as("the newcomer is responsible after the crash").contains(newcomer);
        assertThat(cluster.holds(newcomer, gainedKey)).as("the primary's survivor push reached the newly responsible node").isTrue();
        assertThat(cluster.inSetCopies(gainedKey)).as("the responsible set is restored to RF").isEqualTo(3);
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

        /// A PRODUCTION-SHAPED join, which [#join] is not. `AetherNode` creates the joiner's DHT ring
        /// EMPTY (`ConsistentHashRing.consistentHashRing()`) and `MembershipDeltaProjector.emitJoin`
        /// emits ONE `NodeJoined` per member as the joiner's own FSM promotes it — each carrying a
        /// `topology()` snapshot of what that node has announced SO FAR. So the joiner's ring grows one
        /// node per event and [DHTTopologyListener#onNodeJoined] fires a full anti-entropy round after
        /// every one of them, on a PARTIAL ring. [#join] hides this by pre-filling the joiner's ring
        /// with the whole membership before delivering a single decision.
        ///
        /// `selfAnnouncedAt` is the index at which the joiner's own promotion appears in its staircase,
        /// and it is deliberately a PARAMETER rather than a guess: the joiner is a replica of nothing
        /// until it is in its own ring, so which rounds are dangerous depends entirely on where that
        /// falls, and the FSM's promotion order is driven by SWIM observation and boot seeding rather
        /// than by anything this module can see. The invariant is asserted for every position.
        ///
        /// The existing members are given the joiner's decision FIRST: `MembershipDecision` is
        /// consensus-committed, so by the time the joiner processes its staircase the cluster has
        /// agreed it is a member. That is also the worst case for over-pull — every holder is willing
        /// to answer — which is what this harness is for.
        void joinIncrementally(NodeId id, int selfAnnouncedAt) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            var storage = memoryStorageEngine();
            var node = dhtNode(id, storage, ring, CONFIG);
            DHTNetwork network = this::deliver;
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            var antiEntropy = dhtAntiEntropy(node, network, CONFIG);
            var existing = List.copyOf(members.keySet());
            var wholeCluster = new ArrayList<>(existing);

            wholeCluster.add(id);
            members.put(id,
                        new Member(id,
                                   node,
                                   rebalancer,
                                   antiEntropy,
                                   dhtTopologyListener(node, rebalancer, antiEntropy),
                                   storage,
                                   ring));

            var joinerJoined = MembershipDecision.nodeJoined(id, wholeCluster);

            existing.forEach(peer -> members.get(peer).topologyListener().onNodeJoined(joinerJoined));

            var order = new ArrayList<>(existing);

            order.add(Math.min(selfAnnouncedAt, order.size()), id);

            var announced = new ArrayList<NodeId>();

            for (var promoted : order) {
                announced.add(promoted);
                members.get(id)
                       .topologyListener()
                       .onNodeJoined(MembershipDecision.nodeJoined(promoted, List.copyOf(announced)));
            }
        }

        /// A crash: the node is gone from every ring before the survivors rebalance, which is the
        /// production order (`DHTTopologyListener.removeFromRing` prunes the ring, THEN calls the
        /// rebalancer). No departure push — the node did not drain.
        void crashWithSurvivorRebalance(NodeId crashing) {
            remove(crashing);
            members.values().forEach(member -> member.rebalancer().onNodeRemoved(crashing));
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

        java.util.Collection<Member> members() {
            return members.values();
        }

        /// A key whose responsible set on the current (pruned) rings excludes `node` and includes it
        /// once `node` is back in the ring.
        byte[] findKeyGainedByRejoin(NodeId node, String prefix) {
            var pruned = members.values().iterator().next().ring();
            var rf = CONFIG.effectiveReplicationFactor(members.size());
            var whole = ConsistentHashRing.<NodeId>consistentHashRing();
            members.keySet().forEach(whole::addNode);
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-probe-" + i);
                if (!pruned.nodesFor(candidate, rf).contains(node) && whole.nodesFor(candidate, rf).contains(node)) {
                    return candidate;
                }
            }
            throw new AssertionError("no key gained by the rejoin found");
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

        record GainedOnCrash(byte[] key, NodeId newcomer) {}

        /// A key currently owned by `crashing` whose post-crash responsible set gains EXACTLY ONE node
        /// that is not already a holder — the single newcomer the survivor rebalance must stock.
        GainedOnCrash findKeyGainedByExactlyOneNewcomerOnCrash(NodeId crashing, String prefix) {
            var rf = CONFIG.effectiveReplicationFactor(members.size());
            var afterCrash = ConsistentHashRing.<NodeId>consistentHashRing();

            members.keySet().stream().filter(id -> !id.equals(crashing)).forEach(afterCrash::addNode);

            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-probe-" + i);
                var before = responsibleFor(candidate);

                if (!before.contains(crashing)) {
                    continue;
                }

                var fresh = afterCrash.nodesFor(candidate, rf).stream().filter(id -> !before.contains(id)).toList();

                if (fresh.size() == 1) {
                    return new GainedOnCrash(candidate, fresh.getFirst());
                }
            }

            throw new AssertionError("no key gaining exactly one newcomer on the crash found");
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
