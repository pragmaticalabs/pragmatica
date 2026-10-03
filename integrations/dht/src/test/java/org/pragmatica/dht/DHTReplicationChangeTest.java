package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 1, CTO ruling R1 (from v1882's probe): ANY live change of the committed (RF, CF) — not only one that
/// gains a partition — must keep every value acked under the OLD factors readable. A CF raise at a fixed RF gains no
/// partition yet lowers the read quorum below what the old writes were acked at; an RF decrease moves the replica set
/// away from where the old copies are. Until the new replica set has been filled from the old one, a node reads and
/// writes with the transitional quorums max(old, new), and every replica refuses "absent" (the catch-up gate).
///
/// CTO ruling R1b: the switch to the new quorums is cluster-wide and COMMITTED. A node keeps the transitional quorums
/// until the cluster commits the change as settled, which it does only after every writer applied the change AND every
/// replica completed a catch-up pass that began after that ([DHTNode#writersSwitched]). These tests drive the DHT half of
/// that protocol by hand; the commit side is pinned in aether/node.
class DHTReplicationChangeTest {
    private static final byte[] KEY = "change-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "v".getBytes(StandardCharsets.UTF_8);
    private static final long CHANGE = 7L;

    /// RF3/CF1 -> RF3/CF2: old W1 R3, new W2 R2; W_old + R_new = 3 is not > 3. The write reached ONE replica.
    @Test
    void cfRaiseAtFixedRf_threeNodeRing_valueAckedUnderTheOldFactors_staysReadable_andIsFilledEverywhere() {
        cfRaiseAtFixedRf(3);
    }

    /// Five nodes: the #428 fallback has two non-replicas to probe, which must not be what saves the read.
    @Test
    void cfRaiseAtFixedRf_fiveNodeRing_valueAckedUnderTheOldFactors_staysReadable_andIsFilledEverywhere() {
        cfRaiseAtFixedRf(5);
    }

    private static void cfRaiseAtFixedRf(int size) {
        var cluster = new Cluster(size, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);

        cluster.putReachingOnly(replicas.getLast(), replicas.getLast());
        cluster.changeAll(factors(3, 2));

        assertThat(replicas).as("the change re-opened the catch-up gate on every replica")
                            .allMatch(id -> cluster.readiness(id) == Readiness.CATCHING_UP);
        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("the transitional read quorum is max(R_old, R_new)")
                                                                     .isEqualTo(3);
        assertThat(cluster.valueOrRefusal(replicas.getFirst())).as("read during the change: the value or a retryable refusal, never absent")
                                                                .isTrue();

        cluster.catchUp(3);

        assertThat(replicas).as("every key is on the new replica set").allMatch(cluster::holds);
        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("caught up locally is not settled: still R_t")
                                                                     .isEqualTo(3);

        cluster.settle(CHANGE, 3);

        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("settled: the new read quorum").isEqualTo(2);
        assertThat(cluster.config(replicas.getFirst()).writeQuorum()).isEqualTo(2);
        assertThat(cluster.read(replicas.getFirst())).isEqualTo(Option.some("v"));
        assertThat(cluster.fallbackResolutions.get()).as("no #428 fallback was needed").isZero();
    }

    /// RF5/CF2 -> RF3/CF2 on five nodes: the write reached only replicas 4 and 5 of the old set, neither of which is in
    /// the new set {1, 2, 3}. Without the gate the new set reads "absent" and only the #428 fallback probe could rescue
    /// it; with it, the read refuses (retryable) until the new set has pulled from the old holders.
    @Test
    void rfDecrease_valueOnlyOutsideTheNewReplicaSet_isNeverAbsent_andNeedsNoFallback() {
        var cluster = new Cluster(5, factors(5, 2));
        var replicas = cluster.replicasOf(KEY, 5);

        cluster.putReachingOnly(replicas.get(3), replicas.get(3), replicas.get(4));
        cluster.changeAll(factors(3, 2));

        var reader = replicas.getFirst();

        assertThat(cluster.valueOrRefusal(reader)).as("during the change: the value or a retryable refusal, never absent")
                                                  .isTrue();

        cluster.catchUp(3);

        assertThat(cluster.replicasOf(KEY, 3)).as("every key is on the new replica set").allMatch(cluster::holds);
        assertThat(cluster.read(reader)).isEqualTo(Option.some("v"));
        assertThat(cluster.fallbackResolutions.get()).as("no #428 fallback was needed").isZero();
    }

    /// The transitional quorums are what protect a value written by a node that has NOT YET applied the change (it
    /// writes at W_old) after two of the three replicas have already caught up: a coordinator still settling reads
    /// R_t = 3 and reaches the one replica holding it. With R_new = 2 it would read two caught-up, authoritative
    /// "absent" answers and return a false "absent".
    @Test
    void laggingWriterAtTheOldQuorum_isReadByASettlingCoordinator() {
        var cluster = new Cluster(5, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var coordinator = cluster.nonReplicaOf(replicas);

        cluster.changeAll(factors(3, 2));
        cluster.catchUpOnly(replicas.get(0), replicas.get(1));
        assertThat(cluster.readiness(replicas.get(0))).as("arming: the first two replicas have caught up").isEqualTo(Readiness.SERVING);
        assertThat(cluster.readiness(replicas.get(1))).isEqualTo(Readiness.SERVING);

        var lagging = distributedDHTClient(cluster.nodes.get(replicas.get(2)), cluster::route, factors(3, 1));

        cluster.dropPutsTo = Set.of(replicas.get(0), replicas.get(1));
        assertThat(lagging.put(KEY, VALUE).await().isSuccess()).as("acked at the old W1 by a node yet to apply the change").isTrue();
        cluster.dropPutsTo = Set.of();

        assertThat(cluster.config(coordinator).readQuorum()).as("arming: the coordinator is still settling").isEqualTo(3);
        assertThat(cluster.read(coordinator)).as("never a false absent").isEqualTo(Option.some("v"));
    }

    /// A second change while the first is still settling keeps the strictest quorums of all of them.
    @Test
    void secondChangeWhileSettling_keepsTheStrictestQuorums() {
        var cluster = new Cluster(5, factors(5, 4));
        var node = cluster.anyId();

        cluster.changeAll(factors(5, 2));
        cluster.changeAll(factors(3, 1));

        assertThat(cluster.config(node).writeQuorum()).as("min(max(4, 2, 1), RF 3)").isEqualTo(3);
        assertThat(cluster.config(node).readQuorum()).as("min(max(2, 4, 3), RF 3)").isEqualTo(3);
    }

    /// v1882's round-2 probe (`probe-r2-lagging-writer.patch`), kept as its scenario: RF3, CF1 -> CF2. Two replicas
    /// apply the change and finish their own catch-up; the third has not applied it and writes at W1, reaching only
    /// itself. Under R1's per-node switch the appliers read at R_new = 2 and answered absent (red at 132bf80c9:
    /// `Success(None())`). Under R1b nothing is settled until the cluster commits it, so the appliers still read at
    /// R_t = max(3, 2) = 3 and reach the laggard.
    @Test
    void laggingWriter_atOldW_isReadByAnApplierThatCaughtUp_becauseNothingIsSettled() {
        var cluster = new Cluster(3, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var lagging = replicas.getLast();
        var appliers = replicas.subList(0, 2);

        appliers.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.catchUpOnly(appliers.toArray(NodeId[]::new));

        appliers.forEach(id -> assertThat(cluster.readiness(id)).as("arming: caught up locally: " + id).isEqualTo(Readiness.SERVING));
        appliers.forEach(id -> assertThat(cluster.config(id).readQuorum()).as("no committed settle: R_t on " + id).isEqualTo(3));
        assertThat(cluster.config(lagging).writeQuorum()).as("arming: the laggard is still at W_old").isEqualTo(1);

        cluster.putReachingOnly(lagging, lagging);

        assertThat(cluster.read(appliers.getFirst())).as("a write acked by the lagging node never reads absent").isEqualTo(Option.some("v"));
    }

    /// The ordering the settle needs: a write acked at W_old AFTER the replicas' first catch-up pass sits on one replica
    /// only, and no pass that ran before it can have copied it. Settling on "every writer applied" plus "every replica
    /// caught up" — in either order — would let readers drop to R_new = 2 and miss it. The writers-switched pass begins
    /// after the last writer applied the change, so it copies that write to every replica before the change settles.
    @Test
    void writeAtOldW_afterTheFirstPass_isOnEveryReplicaBeforeTheChangeSettles() {
        var cluster = new Cluster(5, factors(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var lagging = replicas.getLast();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(lagging)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.catchUp(3);
        cluster.putReachingOnly(lagging, lagging);
        cluster.nodes.get(lagging).resolveReplication(factors(3, 2), CHANGE);
        cluster.catchUp(3);

        cluster.settle(CHANGE, 3);

        assertThat(replicas).as("the writers-switched pass copied the late W_old write to every replica").allMatch(cluster::holds);
        assertThat(cluster.config(replicas.getFirst()).readQuorum()).as("settled: R_new").isEqualTo(2);
        replicas.forEach(id -> assertThat(cluster.read(id)).as("read at R_new on " + id).isEqualTo(Option.some("v")));
    }

    /// The settle is fenced on the change version: settling an older change leaves a newer one's floor in place, and a
    /// committed floor for a change already settled is ignored.
    @Test
    void settle_isFencedOnTheChangeVersion_andMonotone() {
        var cluster = new Cluster(3, factors(3, 1));
        var id = cluster.anyId();
        var node = cluster.nodes.get(id);

        node.resolveReplication(factors(3, 2), CHANGE);
        node.settleReplicationChange(CHANGE - 1);

        assertThat(node.replicationChangeSettling()).as("a stale settle does not settle a newer change").isTrue();
        assertThat(cluster.config(id).readQuorum()).isEqualTo(3);

        node.settleReplicationChange(CHANGE);
        node.holdReplicationChange(CHANGE, 1, 3);
        node.settleReplicationChange(CHANGE - 1);

        assertThat(node.replicationChangeSettling()).as("settled stays settled: a stale floor and a stale settle change nothing").isFalse();
        assertThat(cluster.config(id).readQuorum()).isEqualTo(2);
    }

    /// A node that did not observe the change (restarted after it, or a worker) holds the committed floor it is handed.
    @Test
    void committedFloor_isHeldByANodeThatNeverSawTheChange() {
        var cluster = new Cluster(3, factors(3, 2));
        var id = cluster.anyId();
        var node = cluster.nodes.get(id);

        node.holdReplicationChange(CHANGE, 1, 3);

        assertThat(cluster.config(id).readQuorum()).as("R_t = max(R_old 3, R_new 2)").isEqualTo(3);
        assertThat(cluster.config(id).writeQuorum()).as("W_t = max(W_old 1, W_new 2)").isEqualTo(2);
    }

    /// The writers-switched pass is a NEW pending spell, even for a partition still pending from the first pass: a round
    /// started before the switch may have copied before the last W_old write landed, so it must not complete the
    /// partition (the anti-entropy completes a round only for the spell it began in).
    @Test
    void writersSwitched_startsAFreshSpell_soARoundFromBeforeCannotCompleteIt() {
        var cluster = new Cluster(3, factors(3, 1));
        var node = cluster.nodes.get(cluster.anyId());
        var partition = node.partitionFor(KEY);

        node.resolveReplication(factors(3, 2), CHANGE);
        var firstPass = node.catchUpGeneration(partition);

        assertThat(firstPass).as("arming: pending from the first pass").isNotZero();

        node.writersSwitched(CHANGE, 3);

        assertThat(node.catchUpGeneration(partition)).as("a new spell").isNotEqualTo(firstPass);
    }

    /// CTO ruling R1c, v1882's round-3 straddle (`probe-r3-straddle.patch`): a non-replica writer starts a put at W_old;
    /// it stays in flight while every node applies the change, the writers switch, every replica completes its pass and
    /// the change settles; then it lands on ONE replica. Red at e067c2a8b: that replica acked it, the put succeeded at
    /// W1, and a read at R_new returned None. The put carries the replication change it was sized under, so the replica,
    /// which has applied a newer one, refuses it; the writer retries under the change and the value is readable.
    @Test
    void straddlingPut_startedUnderTheOldFactors_isRefusedAfterTheChange_andTheRetrySucceeds() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        cluster.holdPuts = true;
        var straddling = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        assertThat(cluster.held).as("arming: the W_old put is in flight to every replica").hasSize(3);

        cluster.changeAll(factors(3, 2));
        cluster.settle(CHANGE, 3);
        cluster.deliverHeldTo(replicas.getLast());

        var outcome = straddling.await();

        assertThat(outcome.isSuccess()).as("the straddling put is not acknowledged: " + outcome).isFalse();
        assertThat(cluster.holds(replicas.getLast())).as("the replica refused it").isFalse();

        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).as("the retry, under the applied change").isTrue();
        replicas.forEach(id -> assertThat(cluster.read(id)).as("readable at R_new on " + id).isEqualTo(Option.some("v")));
    }

    /// A writer the roster wrongly excluded (held Dead by a wrong membership view, so the change settled without its report) still
    /// writes under the old factors. Every replica that applied the change refuses it with the typed, retriable
    /// `ReplicationChangeStale`; once the writer applies the change, its retry succeeds and reads at R_new.
    @Test
    void writerExcludedFromTheSettle_isRefused_untilItAppliesTheChange() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        var refused = cluster.client(writer).put(KEY, VALUE).await();

        boolean stale = refused.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("typed, retriable refusal: " + refused).isTrue();
        assertThat(replicas).as("no replica took the W_old write").noneMatch(cluster::holds);

        cluster.nodes.get(writer).resolveReplication(factors(3, 2), CHANGE);

        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).isTrue();
        replicas.forEach(id -> assertThat(cluster.read(id)).isEqualTo(Option.some("v")));
    }

    /// A worker that learned the OLD factors from a core that had not applied the change yet stamps its puts with the old
    /// change it adopted; replicas on the new change refuse them.
    @Test
    void writerTaughtTheOldChange_isFencedByReplicasOnTheNewOne() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        cluster.nodes.get(writer).adoptReplicationChange(CHANGE - 2, factors(3, 1));
        replicas.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));

        assertThat(cluster.nodes.get(writer).replicationFence()).as("arming: the writer is on the old change").isEqualTo(CHANGE - 2);
        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).as("fenced").isFalse();
        assertThat(replicas).noneMatch(cluster::holds);
    }

    /// Why the fence is keyed on the change a replica has APPLIED, not on the writers-switched stage it has observed.
    /// Keyed on writers-switched, a replica that has applied the change but not yet observed that stage would still
    /// accept a W_old write — after its co-replicas' writers-switched passes completed, so no pass carries it to them,
    /// and once the change settles a read at R_new over those co-replicas misses it. Keyed on application, every W_old
    /// write a replica accepts predates that replica's report, hence the writers-switched commit, hence every pass.
    @Test
    void wOldWriteLandingBetweenAReplicasApplyAndItsWritersSwitched_isRefused() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var late = replicas.getFirst();
        var writer = cluster.nonReplicaOf(replicas);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        others.stream().filter(id -> !id.equals(late)).forEach(id -> cluster.nodes.get(id).writersSwitched(CHANGE, 3));
        cluster.catchUpOnly(others.stream().filter(id -> !id.equals(late)).toArray(NodeId[]::new));

        var acked = cluster.putReachingOnlyOutcome(writer, late);

        cluster.nodes.get(late).writersSwitched(CHANGE, 3);
        cluster.catchUp(3);
        others.forEach(id -> cluster.nodes.get(id).settleReplicationChange(CHANGE));

        assertThat(acked).as("a W_old write landing on an applied replica is refused").isFalse();
        replicas.stream().filter(id -> !id.equals(late))
                .forEach(id -> assertThat(cluster.read(id).isPresent() || !acked).as("no acked write reads absent at " + id).isTrue());
    }

    /// Caught up for a change is reported once, only for a pass that began after the writers switched.
    @Test
    void caughtUp_isReportedOncePerChange_afterTheWritersSwitchedPass() {
        var cluster = new Cluster(3, factors(3, 1));
        var reported = new java.util.ArrayList<Long>();

        cluster.changeAll(factors(3, 2));
        cluster.catchUp(3);
        cluster.nodes.values().forEach(node -> node.onReplicationCaughtUp(reported::add));

        assertThat(reported).as("the first pass alone reports nothing").isEmpty();

        cluster.nodes.values().forEach(node -> node.writersSwitched(CHANGE, 3));
        assertThat(cluster.nodes.values()).as("re-gated").allMatch(node -> !node.pendingPartitions().isEmpty());
        cluster.catchUp(3);
        cluster.nodes.values().forEach(node -> node.writersSwitched(CHANGE, 3));

        assertThat(reported).as("one report per node, for this change").containsExactly(CHANGE, CHANGE, CHANGE);
        assertThat(cluster.nodes.values()).allMatch(node -> node.replicationCaughtUpVersion() == CHANGE);
    }

    private static DHTConfig factors(int replicationFactor, int confirmationFactor) {
        return DHTConfig.DEFAULT.withFactors(replicationFactor, confirmationFactor).unwrap();
    }

    /// The old factors with a short operation timeout, so a put whose replies never all arrive fails fast.
    private static DHTConfig shortTimeout(int replicationFactor, int confirmationFactor) {
        return DHTConfig.dhtConfig(replicationFactor,
                                   confirmationFactor,
                                   replicationFactor - confirmationFactor + 1,
                                   org.pragmatica.lang.io.TimeSpan.timeSpan(500).millis())
                        .unwrap();
    }

    private static final class Cluster {
        final Map<NodeId, DHTNode> nodes = new LinkedHashMap<>();
        final Map<NodeId, DHTAntiEntropy> antiEntropies = new LinkedHashMap<>();
        final Map<NodeId, DistributedDHTClient> clients = new LinkedHashMap<>();
        final AtomicInteger fallbackResolutions = new AtomicInteger();
        volatile Set<NodeId> dropPutsTo = Set.of();
        volatile boolean holdPuts;
        final List<Map.Entry<NodeId, DHTMessage.PutRequest>> held = new java.util.ArrayList<>();

        Cluster(int size, DHTConfig config) {
            var ids = IntStream.range(0, size).mapToObj(i -> new NodeId("node-" + i)).toList();
            var observer = new ResolveFallbackObserver() {
                @Override
                public void onResolvedViaFallback(String keyHex, int probed) {
                    fallbackResolutions.incrementAndGet();
                }

                @Override
                public void onUnresolvedAfterFallback(ResolveMiss miss) {}
            };

            ids.forEach(id -> {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();

                ids.forEach(ring::addNode);
                nodes.put(id, dhtNode(id, memoryStorageEngine(), ring, config));
            });
            ids.forEach(id -> antiEntropies.put(id, dhtAntiEntropy(nodes.get(id), this::route, _ -> false)));
            ids.forEach(id -> clients.put(id,
                                          distributedDHTClient(nodes.get(id), this::route, OwnerEpochSource.zero())
                                              .withResolveFallbackObserver(observer)));
        }

        NodeId anyId() {
            return nodes.keySet().iterator().next();
        }

        List<NodeId> replicasOf(byte[] key, int replicationFactor) {
            return nodes.get(anyId()).ring().nodesFor(key, replicationFactor);
        }

        /// Put through `writer` while every replica except `reaching` drops the request.
        void putReachingOnly(NodeId writer, NodeId... reaching) {
            var reached = Set.of(reaching);

            dropPutsTo = Set.copyOf(nodes.keySet().stream().filter(id -> !reached.contains(id)).toList());
            assertThat(clients.get(writer).put(KEY, VALUE).await().isSuccess()).as("acked under the old factors").isTrue();
            dropPutsTo = Set.of();
            assertThat(nodes.keySet().stream().filter(id -> !reached.contains(id)))
                .as("arming: only the reached replicas hold the value")
                .noneMatch(this::holds);
        }

        void changeAll(DHTConfig config) {
            nodes.values().forEach(node -> node.resolveReplication(config, CHANGE));
        }

        /// The DHT half of a committed settle: every writer applied `version`, every replica runs the writers-switched
        /// pass to completion, then the cluster settles it.
        void settle(long version, int sourceReplicationFactor) {
            nodes.values().forEach(node -> node.writersSwitched(version, sourceReplicationFactor));
            catchUp(3);
            assertThat(nodes.values()).as("arming: every replica completed the writers-switched pass")
                                      .allMatch(node -> node.replicationCaughtUpVersion() == version);
            nodes.values().forEach(node -> node.settleReplicationChange(version));
        }

        void deliverHeldTo(NodeId target) {
            held.stream()
                .filter(entry -> entry.getKey().equals(target))
                .forEach(entry -> nodes.get(target).handlePutRequest(entry.getValue(), resp -> route(entry.getValue().sender(), resp)));
        }

        /// The DHT half of a committed settle, over `members` only (the rest were excluded from the roster).
        void settleOnly(long version, int sourceReplicationFactor, List<NodeId> members) {
            members.forEach(id -> nodes.get(id).writersSwitched(version, sourceReplicationFactor));
            catchUpOnly(members.toArray(NodeId[]::new));
            members.forEach(id -> nodes.get(id).settleReplicationChange(version));
        }

        /// Put through `writer` while every node except `reaching` drops the request; whether it was acknowledged.
        boolean putReachingOnlyOutcome(NodeId writer, NodeId... reaching) {
            var reached = Set.of(reaching);

            dropPutsTo = Set.copyOf(nodes.keySet().stream().filter(id -> !reached.contains(id)).toList());
            var outcome = clients.get(writer).put(KEY, VALUE).await();
            dropPutsTo = Set.of();

            return outcome.isSuccess();
        }

        void catchUpOnly(NodeId... ids) {
            for (int round = 0; round < 3; round++) {
                for (var id : ids) {
                    antiEntropies.get(id).catchUpNow();
                }
            }
        }

        NodeId nonReplicaOf(List<NodeId> replicas) {
            return nodes.keySet().stream().filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
        }

        boolean valueOrRefusal(NodeId reader) {
            return clients.get(reader)
                          .get(KEY)
                          .await()
                          .fold(cause -> cause instanceof DHTError.NotCaughtUp, value -> value.isPresent());
        }

        void catchUp(int rounds) {
            for (int round = 0; round < rounds; round++) {
                antiEntropies.values().forEach(DHTAntiEntropy::catchUpNow);
            }
        }

        boolean holds(NodeId id) {
            return nodes.get(id).getLocal(KEY).await().or(Option.none()).isPresent();
        }

        Readiness readiness(NodeId id) {
            return nodes.get(id).readiness(nodes.get(id).partitionFor(KEY));
        }

        DHTConfig config(NodeId id) {
            return nodes.get(id).config();
        }

        DistributedDHTClient client(NodeId id) {
            return clients.get(id);
        }

        Option<String> read(NodeId id) {
            return clients.get(id).get(KEY).await().unwrap().map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        }

        void route(NodeId target, ProtocolMessage message) {
            var node = nodes.get(target);
            var antiEntropy = antiEntropies.get(target);
            var client = clients.get(target);

            switch (message) {
                case DHTMessage.GetRequest r -> node.handleGetRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.GetResponse r -> client.onGetResponse(r);
                case DHTMessage.PutRequest r -> {
                    if (holdPuts) {
                        held.add(Map.entry(target, r));
                    } else if (!dropPutsTo.contains(target)) {
                        node.handlePutRequest(r, resp -> route(r.sender(), resp));
                    }
                }
                case DHTMessage.PutResponse r -> client.onPutResponse(r);
                case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.DigestResponse r -> antiEntropy.onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> antiEntropy.onMigrationDataResponse(r);
                default -> {}
            }
        }
    }
}
