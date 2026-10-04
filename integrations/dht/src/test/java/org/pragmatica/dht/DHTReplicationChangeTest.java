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
        assertThat(cluster.nodes.get(writer).staleRefusal().map(DHTNode.StaleRefusal::fence))
            .as("the refused writer records its own stale refusal (the owner-rule event's source)")
            .isEqualTo(Option.some(DHTNode.NO_CHANGE));

        cluster.nodes.get(writer).resolveReplication(factors(3, 2), CHANGE);

        assertThat(cluster.nodes.get(writer).staleRefusal()).as("applying the change ends it").isEqualTo(Option.none());

        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).isTrue();
        replicas.forEach(id -> assertThat(cluster.read(id)).isEqualTo(Option.some("v")));
    }

    /// v1882 round 4, RESTART WINDOW (`probe-r4-restart-window-and-remove.patch`): a replica restarted after the settle
    /// holds no fence until its state restore hands it the committed change. Red at f04368fb6: the straddling W_old put
    /// landed on it, was acked at W1, and a read at R_new returned None. An unknown fence now refuses; once the replica
    /// has resolved and adopted the change, a retry is accepted.
    @Test
    void restartedReplica_refusesWrites_untilItHasAdoptedTheCommittedChange() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        cluster.holdPuts = true;
        var straddling = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        cluster.changeAll(factors(3, 2));
        cluster.settle(CHANGE, 3);

        var restarted = replicas.getLast();
        var fresh = cluster.restart(restarted);

        assertThat(fresh.acceptsWrites()).as("arming: restarted, state not restored yet").isFalse();

        cluster.deliverHeldTo(restarted);

        assertThat(straddling.await().isSuccess()).as("the straddling put is not acknowledged").isFalse();
        assertThat(cluster.holds(restarted)).as("the restarted replica refused it").isFalse();

        fresh.resolveReplication(factors(3, 2), CHANGE);
        assertThat(fresh.acceptsWrites()).as("resolved but not adopted: still unknown").isFalse();
        fresh.adoptReplicationChange(CHANGE, factors(3, 2));
        fresh.settleReplicationChange(CHANGE, factors(3, 2));
        assertThat(fresh.acceptsWrites()).as("adopted, but not confirmed caught up: still unknown").isFalse();
        fresh.confirmReplicationFence();

        assertThat(fresh.acceptsWrites()).isTrue();
        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).as("the retry, stamped under the change").isTrue();
        assertThat(cluster.holds(restarted)).isTrue();
    }

    /// v1882 round 4 on track 3: the restart window holds for tombstones too — a restarted replica refuses a remove until
    /// its fence is known.
    @Test
    void restartedReplica_refusesRemoves_untilItHasAdoptedTheCommittedChange() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var restarted = replicas.getLast();
        var fresh = cluster.restart(restarted);
        var response = new java.util.concurrent.atomic.AtomicReference<DHTMessage.RemoveResponse>();

        fresh.handleRemoveRequest(new DHTMessage.RemoveRequest("r", replicas.getFirst(), KEY, 1L, 0L, 0L, 0L, CHANGE), response::set);

        assertThat(response.get().fenceUnknown()).as("unknown fence: refused").isTrue();

        fresh.resolveReplication(factors(3, 1), CHANGE);
        fresh.adoptReplicationChange(CHANGE, factors(3, 1));
        fresh.confirmReplicationFence();
        fresh.handleRemoveRequest(new DHTMessage.RemoveRequest("r2", replicas.getFirst(), KEY, 2L, 0L, 0L, 0L, CHANGE), response::set);

        assertThat(response.get().fenceUnknown() || response.get().replicationStale()).as("known fence: accepted").isFalse();
    }

    /// v1882 round 5 (`probe-r5-restart-and-stale-record.patch`): two replicas restart; a HEALTHY writer on the current
    /// change is refused by their UNKNOWN fences. That says nothing about the writer, so it records no staleness; once
    /// the replicas know the change its next put succeeds. Red at 3cd00b197: the unknown-fence refusal recorded
    /// `Some(7)` and nothing cleared it, so DHT_WRITER_STALE would have fired five minutes later for a healthy node.
    @Test
    void healthyWriterRefusedByUnknownFences_recordsNoStaleness() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        cluster.changeAll(factors(3, 2));
        cluster.settle(CHANGE, 3);
        var restarted = List.of(replicas.get(1), replicas.get(2)).stream().map(cluster::restart).toList();

        var refused = cluster.client(writer).put(KEY, VALUE).await();
        boolean unknown = refused.fold(cause -> cause instanceof DHTError.ReplicationFenceUnknown, _ -> false);

        assertThat(unknown).as("a plain retriable refusal: " + refused).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("the writer is not stale").isEqualTo(Option.none());

        restarted.forEach(fresh -> {
            fresh.resolveReplication(factors(3, 2), CHANGE);
            fresh.adoptReplicationChange(CHANGE, factors(3, 2));
            fresh.confirmReplicationFence();
        });

        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).isEqualTo(Option.none());
    }

    /// V1882 r6 PROBE (not for merge). A put stamped under the OLD change is in flight while the writer applies the change;
    /// its refusals arrive AFTER the writer adopted. The writer is current; nothing else is written (an idle writer).
    @Test
    void v1882r6_refusalArrivingAfterTheWriterAdopted_leavesNoStaleRecord() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        cluster.holdPuts = true;
        var straddling = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        assertThat(cluster.held).as("arming: in flight to every replica").hasSize(3);

        cluster.changeAll(factors(3, 2));
        assertThat(cluster.nodes.get(writer).replicationFence()).as("arming: the writer adopted the change").isEqualTo(CHANGE);
        replicas.forEach(cluster::deliverHeldTo);
        var outcome = straddling.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        System.out.println("V1882-R6 race: outcome=" + outcome + " record=" + cluster.nodes.get(writer).staleRefusal()
                           + " writerFence=" + cluster.nodes.get(writer).replicationFence());
        assertThat(stale).as("arming: refused by the newer-fence path: " + outcome).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("a CURRENT writer is not stale").isEqualTo(Option.none());
    }

    /// V1882 r6 PROBE (not for merge): the roster-excluded writer is itself a REPLICA of the key. Its local slot is not
    /// fenced (`handleLocalPut`), and W_old = 1. Does its put succeed at W1 after the settle, and does the success clear
    /// the genuine stale record (alert suppression)? Then: does a read at R_new from the other replicas answer absent?
    @Test
    void v1882r6_excludedWriterThatIsAReplica_localSlotAcceptsAtWold() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.nodes.get(writer).noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);

        var outcome = cluster.client(writer).put(KEY, VALUE).await();
        var reader = replicas.get(1);
        var read = cluster.client(reader).get(KEY).await();

        System.out.println("V1882-R6 replica-writer: outcome=" + outcome + " record=" + cluster.nodes.get(writer).staleRefusal()
                           + " holds=" + replicas.stream().map(id -> id.id() + ":" + cluster.holds(id)).toList()
                           + " readerR=" + cluster.config(reader).readQuorum() + " read=" + read);
        assertThat(outcome.isSuccess()).as("a stale writer's put is refused: " + outcome).isFalse();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("the genuine record survives").isNotEqualTo(Option.none());
    }

    /// v1882 r7 F10, order (a), as restated in r10: the writer is a replica and the remote replies are still in flight. Its
    /// own slot is NOT applied while it waits for evidence (nothing to undo later), the put is not acknowledged, and when the
    /// remotes refuse it as stale it fails without ever having touched the writer's slot.
    @Test
    void v1882r10_orderA_stalePutWaitsForEvidence_failsStale_andNeverAppliesTheLocalSlot() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;

        assertThat(cluster.holds(writer)).as("the writer's own slot is not applied before the evidence gate releases").isFalse();
        assertThat(put.isResolved()).as("the put is not acknowledged before there is evidence").isFalse();

        others.forEach(cluster::deliverHeldTo);
        var outcome = put.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale, not acknowledged: " + outcome).isTrue();
        assertThat(cluster.holds(writer)).as("the writer's own slot was never written").isFalse();
        var deadline = System.nanoTime() + 2_000_000_000L;

        // the record is written by a callback that may run just after the caller's wait returns
        while (cluster.nodes.get(writer).staleRefusal().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(cluster.nodes.get(writer).staleRefusal()).as("the writer is recorded stale").isNotEqualTo(Option.none());
    }

    /// v1882 r7 F10, order (b), as restated in r10: the remote refusals arrive FIRST (before the writer's own slot would be
    /// applied). The put fails stale and the writer's slot is never written, so there is no later "local success" to count.
    @Test
    void v1882r10_orderB_remoteStaleFirst_failsStale_andNeverAppliesTheLocalSlot() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        var outcome = cluster.client(writer).put(KEY, VALUE).await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale: " + outcome).isTrue();
        assertThat(cluster.holds(writer)).as("the writer's own slot was never written").isFalse();
    }

    /// v1882 r7 F10, order (b), remote form: two replicas refuse as stale and only THEN a replica that had not applied the
    /// change accepts. A remote success exists, so no evidence is awaited, yet the refusals already said this writer is
    /// behind: the put fails instead of being acknowledged by the one lagging replica.
    @Test
    void v1882r7_orderB_remoteStaleThenRemoteSuccess_fails() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);
        var unaware = replicas.getFirst();
        var applied = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer) && !id.equals(unaware)).toList();

        applied.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, applied);

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        replicas.stream().filter(id -> !id.equals(unaware)).forEach(cluster::deliverHeldTo);
        cluster.deliverHeldTo(unaware);
        var outcome = put.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale although one replica accepted: " + outcome).isTrue();
    }

    /// v1882 r7 F10, order (c): a replica that has not applied the change accepts first, the put is acknowledged, and a
    /// replica that HAS applied it refuses afterwards. The acknowledgement stands; the late refusal is still recorded,
    /// because it is evidence this writer is behind.
    @Test
    void v1882r7_orderC_ackThenLateStale_ackStands_andTheRecordIsKept() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);
        var unaware = replicas.getFirst();
        var applied = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer) && !id.equals(unaware)).toList();

        applied.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, applied);

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        cluster.deliverHeldTo(unaware);
        var outcome = put.await();

        assertThat(outcome.isSuccess()).as("acknowledged by the replica that had not applied the change: " + outcome).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("nothing refused yet").isEqualTo(Option.none());

        replicas.stream().filter(id -> !id.equals(unaware)).forEach(cluster::deliverHeldTo);

        var deadline = System.nanoTime() + 2_000_000_000L;

        // the record is written by the collector's completion callback, which runs after the last reply is routed
        while (cluster.nodes.get(writer).staleRefusal().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(cluster.nodes.get(writer).staleRefusal()).as("the late refusal is recorded").isNotEqualTo(Option.none());
    }

    /// v1882 r9 probe E: the writer is a replica (W_old = 1 met on its own slot). The FIRST remote reply is a NON-stale
    /// refusal — a restarted replica whose fence is unknown — which is no evidence about this writer, so the put stays
    /// pending; the replica that applied the change then refuses as stale and the put fails.
    @Test
    void v1882r9_orderE_nonStaleRefusalFirst_thenStale_failsInsteadOfAckingOnTheNonStaleReply() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var restarting = replicas.get(1);
        var applied = replicas.get(2);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(restarting);

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        cluster.deliverHeldTo(restarting);

        assertThat(put.isResolved()).as("a fence-unknown refusal is not evidence: the put is not acknowledged yet").isFalse();

        cluster.deliverHeldTo(applied);
        var outcome = put.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale: " + outcome).isTrue();
        assertThat(cluster.holds(writer)).as("the local copy was rolled back").isFalse();
    }

    /// v1882 r9: a non-stale refusal first, then a SUCCESS from a replica that had not applied the change: the success is
    /// evidence, so the put is acknowledged.
    @Test
    void v1882r9_nonStaleRefusalFirst_thenSuccess_acks() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var restarting = replicas.get(1);
        var unaware = replicas.get(2);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer) && !id.equals(unaware)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(restarting);

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        cluster.deliverHeldTo(restarting);

        assertThat(put.isResolved()).as("arming: the non-stale refusal alone does not release the put").isFalse();

        cluster.deliverHeldTo(unaware);

        assertThat(put.await().isSuccess()).as("acknowledged on a remote success").isTrue();
    }

    /// v1882 r9: every remote replies and none is a success or a stale refusal (both restarted, fence unknown): there is no
    /// evidence either way, so the put is acknowledged per the named limit and NO stale record is set.
    @Test
    void v1882r9_allRemoteRepliesNonStale_acksPerTheLimit_andSetsNoStaleRecord() {
        // a 20 s operation timeout makes the evidence bound 2 s, clearly distinguishable from "immediate" (v1882 F16 probe L)
        var cluster = new Cluster(5, DHTConfig.dhtConfig(3, 1, 3, org.pragmatica.lang.io.TimeSpan.timeSpan(20).seconds()).unwrap());
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(replicas.get(1));
        cluster.restart(replicas.get(2));

        cluster.holdPuts = true;
        var put = cluster.client(writer).put(KEY, VALUE);
        cluster.holdPuts = false;
        cluster.deliverHeldTo(replicas.get(1));

        assertThat(put.isResolved()).as("arming: one non-stale reply is not the end of the evidence").isFalse();

        cluster.deliverHeldTo(replicas.get(2));
        // far inside the 2 s evidence bound: a put whose REMOTES all replied is not held to the bound, and the writer's own slot
        // (applied only after the gate) is not among the replies the gate waits for
        var started = System.nanoTime();
        var outcome = put.await(org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(outcome.isSuccess()).as("acknowledged per the limit once every remote replied: " + outcome).isTrue();
        assertThat(elapsedMillis).as("well under the 2 s evidence bound").isLessThan(500L);
        assertThat(settledRecord(cluster, writer)).isEqualTo(Option.none());
    }

    /// v1882 r9b/r10: the writer's slot holds the ONLY copy of a value and a put that would overwrite it is refused as stale.
    /// The slot is not touched at all: the value stays, byte- and version-identical (no apply, so no undo).
    @Test
    void v1882r10_stalePut_neverTouchesTheWritersOnlyLocalCopy() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        cluster.putReachingOnly(writer, writer);
        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        var before = entryOf(cluster, writer);

        var outcome = cluster.client(writer).put(KEY, "other".getBytes(StandardCharsets.UTF_8)).await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(before).as("arming: the writer holds the value").startsWith("v@");
        assertThat(stale).as("arming: the put was refused as stale: " + outcome).isTrue();
        assertThat(entryOf(cluster, writer)).as("the writer's copy is byte- and version-identical").isEqualTo(before);
    }

    /// v1882 probe S, kept as a pin (F15): a SUPERSEDED ack rolled back. A current writer's put X (CF2, W=2) is stamped BEFORE
    /// the stale writer-replica's put W but reaches that replica AFTER W's local accept, so the replica answers "superseded"
    /// — a success that counts toward X's quorum. W is then refused as stale and rolled back to the prior (absent) entry.
    /// How many replicas hold X once X is acknowledged?
    @Test
    void v1882r10_probeS_staleWriterReplica_neverLeavesAnAckedXBelowItsQuorum() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var stale = replicas.getFirst();
        var r1 = replicas.get(1);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(stale)).toList();
        var current = cluster.nonReplicaOf(replicas);

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        cluster.holdPuts = true;
        var x = cluster.client(current).put(KEY, "X".getBytes(StandardCharsets.UTF_8));
        var w = cluster.client(stale).put(KEY, "W".getBytes(StandardCharsets.UTF_8));
        cluster.holdPuts = false;
        var arming = entryOf(cluster, stale);

        cluster.deliverHeldTo(stale);
        cluster.deliverHeldTo(r1);
        var xOutcome = x.await();
        var wOutcome = w.await();
        var holdersOfX = replicas.stream().filter(id -> entryOf(cluster, id).startsWith("X@")).toList();

        System.out.println("V1882-S arming(stale slot)=" + arming + " x=" + xOutcome + " w=" + wOutcome
                           + " entries=" + replicas.stream().map(id -> id.id() + "=" + entryOf(cluster, id)).toList()
                           + " holdersOfX=" + holdersOfX + " W_new=" + cluster.config(r1).writeQuorum());
        var xVersion = Long.parseLong(entryOf(cluster, holdersOfX.getFirst()).replaceAll("^X@([0-9]+)/.*$", "$1"));
        var atOrAboveX = replicas.stream()
                                 .filter(id -> !entryOf(cluster, id).equals("absent"))
                                 .filter(id -> Long.parseLong(entryOf(cluster, id).replaceAll("^[^@]*@([0-9]+)/.*$", "$1")) >= xVersion)
                                 .toList();

        System.out.println("V1882-S atOrAboveX=" + atOrAboveX);
        assertThat(xOutcome.isSuccess()).as("arming: X was acknowledged").isTrue();
        assertThat(atOrAboveX.size()).as("an acknowledged X, or a write that superseded it, is on at least W = 2 replicas")
                                     .isGreaterThanOrEqualTo(2);
    }

    /// v1882 r9b liveness pin: the writer is a replica, CF = 1, and every remote is silent. The put is acknowledged within
    /// the evidence wait (operationTimeout / 10 = 2 s here), not after the whole 20 s operation timeout, and sets no stale
    /// record. Red when the wait is the full operation timeout.
    @Test
    void v1882r9b_allRemotesSilent_acksWithinTheEvidenceWait_notTheOperationTimeout() {
        var cluster = new Cluster(5,
                                  DHTConfig.dhtConfig(3, 1, 3, org.pragmatica.lang.io.TimeSpan.timeSpan(20).seconds()).unwrap());
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();

        cluster.dropPutsTo = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).collect(java.util.stream.Collectors.toSet());
        var started = System.nanoTime();
        var outcome = cluster.client(writer).put(KEY, VALUE).await(org.pragmatica.lang.io.TimeSpan.timeSpan(6).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(outcome.isSuccess()).as("acknowledged on the local slot per the limit: " + outcome).isTrue();
        assertThat(elapsedMillis).as("well under the 20 s operation timeout").isLessThan(4_000L);
        assertThat(settledRecord(cluster, writer)).isEqualTo(Option.none());
    }

    /// The writer's stale record after the put's completion callbacks had time to run: an absence read straight after the
    /// caller's wait returns could pass before the callback that would set the record.
    private static Option<DHTNode.StaleRefusal> settledRecord(Cluster cluster, NodeId writer) {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return cluster.nodes.get(writer).staleRefusal();
    }

    /// v1882 probe U (F17): a roster-excluded writer that is a replica (W_old = 1); one remote replica refuses as stale, the
    /// other is DOWN (silent). The stale refusal is a verdict: the put ends AT ONCE with the typed ReplicationChangeStale and
    /// the writer is recorded stale (the DHT_WRITER_STALE source) — not a generic Timeout after the full operation timeout.
    @Test
    void v1882r11_probeU_staleRefusalPlusASilentReplica_endsAtOnce_typed_andRecordsTheWriterStale() {
        var cluster = new Cluster(5, DHTConfig.dhtConfig(3, 1, 3, org.pragmatica.lang.io.TimeSpan.timeSpan(20).seconds()).unwrap());
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.dropPutsTo = java.util.Set.of(replicas.get(2));

        var started = System.nanoTime();
        var outcome = cluster.client(writer).put(KEY, VALUE).await(org.pragmatica.lang.io.TimeSpan.timeSpan(25).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale, typed: " + outcome).isTrue();
        assertThat(elapsedMillis).as("a stale refusal is a verdict; the put does not wait out the evidence bound").isLessThan(1_000L);
        assertThat(settledRecord(cluster, writer)).as("the genuinely stale writer is recorded").isNotEqualTo(Option.none());
        assertThat(cluster.holds(writer)).as("never applied locally").isFalse();
    }

    private static String entryOf(Cluster cluster, NodeId id) {
        return cluster.nodes.get(id)
                       .storage()
                       .entries()
                       .await()
                       .or(List.of())
                       .stream()
                       .filter(entry -> java.util.Arrays.equals(entry.key(), KEY))
                       .findFirst()
                       .map(entry -> new String(entry.value(), StandardCharsets.UTF_8) + "@" + entry.version() + "/"
                                     + entry.epochIncarnation() + "." + entry.epochTerm() + "." + entry.epochCounter())
                       .orElse("absent");
    }

    /// v1882 r9b probe T (#1885), as restated in r10: the writer's slot holds the ONLY copy of a value and the writer's REMOVE
    /// is refused as stale. Its local tombstone is never written, so the key is not left absent and there is nothing to undo.
    @Test
    void v1882r10_staleRemove_neverTouchesTheWritersOnlyLocalCopy() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        cluster.putReachingOnly(writer, writer);
        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        var before = entryOf(cluster, writer);

        var outcome = cluster.client(writer).remove(KEY).await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(before).as("arming: the writer holds a live value").startsWith("v@");
        assertThat(stale).as("arming: the remove was refused as stale: " + outcome).isTrue();
        assertThat(entryOf(cluster, writer)).as("the entry is byte- and version-identical").isEqualTo(before);
    }

    /// v1882 probe S, REMOVE twin (F15, #1885): a current writer's remove X is stamped BEFORE a stale writer-replica's remove W
    /// but reaches that replica AFTER W's would-be local tombstone. The replica must not answer X with a "superseded" success
    /// that is later undone: an acknowledged X, or a write that superseded it, is on at least W = 2 replicas.
    @Test
    void v1882r10_probeS_remove_staleWriterReplica_neverLeavesAnAckedXBelowItsQuorum() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var stale = replicas.getFirst();
        var r1 = replicas.get(1);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(stale)).toList();
        var current = cluster.nonReplicaOf(replicas);

        cluster.putReachingOnly(stale, stale);
        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        cluster.holdRemoves = true;
        var x = cluster.client(current).remove(KEY);
        var w = cluster.client(stale).remove(KEY);
        cluster.holdRemoves = false;
        var xVersion = cluster.heldRemoves.stream()
                                          .filter(entry -> entry.getValue().sender().equals(current))
                                          .findFirst()
                                          .orElseThrow()
                                          .getValue()
                                          .version();

        cluster.deliverHeldRemovesTo(stale);
        cluster.deliverHeldRemovesTo(r1);
        var xOutcome = x.await();
        w.await();
        var atOrAboveX = replicas.stream()
                                 .filter(id -> !entryOf(cluster, id).equals("absent"))
                                 .filter(id -> Long.parseLong(entryOf(cluster, id).replaceAll("^[^@]*@([0-9]+)/.*$", "$1")) >= xVersion)
                                 .toList();

        assertThat(xOutcome.isSuccess()).as("arming: X was acknowledged: " + xOutcome).isTrue();
        assertThat(atOrAboveX.size()).as("an acknowledged X, or a write that superseded it, is on at least W = 2 replicas: " + atOrAboveX)
                                     .isGreaterThanOrEqualTo(2);
    }

    /// v1882 r9b liveness pin (#1885): the writer is a replica, CF = 1, every remote silent: the remove is acknowledged within
    /// the evidence wait, not after the whole 20 s operation timeout, and sets no stale record.
    @Test
    void v1882r9b_remove_allRemotesSilent_acksWithinTheEvidenceWait_notTheOperationTimeout() {
        var cluster = new Cluster(5,
                                  DHTConfig.dhtConfig(3, 1, 3, org.pragmatica.lang.io.TimeSpan.timeSpan(20).seconds()).unwrap());
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();

        cluster.holdRemoves = true;
        var started = System.nanoTime();
        var outcome = cluster.client(writer).remove(KEY).await(org.pragmatica.lang.io.TimeSpan.timeSpan(6).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(outcome.isSuccess()).as("acknowledged on the local slot per the limit: " + outcome).isTrue();
        assertThat(elapsedMillis).as("well under the 20 s operation timeout").isLessThan(4_000L);
        assertThat(settledRecord(cluster, writer)).isEqualTo(Option.none());
    }

    /// v1882 r7 F10, order (d): the writer is a replica, every other replica stays silent for the whole operation. With no
    /// evidence either way the put is acknowledged on its own slot — the named limit — and NO stale record is set.
    @Test
    void v1882r7_orderD_totalSilence_acksPerTheLimit_andSetsNoStaleRecord() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();

        cluster.dropPutsTo = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).collect(java.util.stream.Collectors.toSet());
        var outcome = cluster.client(writer).put(KEY, VALUE).await();

        assertThat(outcome.isSuccess()).as("acknowledged on the local slot after the silence: " + outcome).isTrue();
        assertThat(settledRecord(cluster, writer)).isEqualTo(Option.none());
    }

    /// v1882 r7 F10, REMOVE form of `v1882r6_excludedWriterThatIsAReplica_localSlotAcceptsAtWold` (#1885): the roster-excluded
    /// writer is itself a replica. Its local tombstone is unfenced and W_old = 1, so without the evidence gate the remove
    /// is acknowledged on that one copy and clears the genuine stale record.
    @Test
    void v1882r7_remove_excludedWriterThatIsAReplica_localSlotAcceptsAtWold() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.nodes.get(writer).noteStaleRefusal(DHTNode.NO_CHANGE, 1_000L);

        var outcome = cluster.client(writer).remove(KEY).await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("a stale writer's remove is refused: " + outcome).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("the genuine record survives").isNotEqualTo(Option.none());
    }

    /// v1882 r7 F10, REMOVE, order (a) with the remote replies in flight: the local tombstone alone does not acknowledge.
    @Test
    void v1882r7_remove_orderA_localSlotAlone_doesNotAcknowledge_thenRemoteStaleFails() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);

        cluster.holdRemoves = true;
        var remove = cluster.client(writer).remove(KEY);
        cluster.holdRemoves = false;

        assertThat(remove.isResolved()).as("the local slot alone does not acknowledge the remove").isFalse();

        others.forEach(cluster::deliverHeldRemovesTo);
        var outcome = remove.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale: " + outcome).isTrue();
    }

    /// v1882 r7 F10, REMOVE, order (b): two replicas refuse as stale, then a replica that had not applied the change accepts.
    @Test
    void v1882r7_remove_orderB_remoteStaleThenRemoteSuccess_fails() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);
        var unaware = replicas.getFirst();
        var applied = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer) && !id.equals(unaware)).toList();

        applied.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, applied);

        cluster.holdRemoves = true;
        var remove = cluster.client(writer).remove(KEY);
        cluster.holdRemoves = false;
        replicas.stream().filter(id -> !id.equals(unaware)).forEach(cluster::deliverHeldRemovesTo);
        cluster.deliverHeldRemovesTo(unaware);
        var outcome = remove.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale although one replica accepted: " + outcome).isTrue();
    }

    /// v1882 r9 probe E, REMOVE form (#1885): the first remote reply is a NON-stale refusal (a restarted replica, fence unknown):
    /// no evidence, the remove stays pending; the replica on the newer change then refuses as stale and the remove fails.
    @Test
    void v1882r9_remove_orderE_nonStaleRefusalFirst_thenStale_failsInsteadOfAckingOnTheNonStaleReply() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var restarting = replicas.get(1);
        var applied = replicas.get(2);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(restarting);

        cluster.holdRemoves = true;
        var remove = cluster.client(writer).remove(KEY);
        cluster.holdRemoves = false;
        cluster.deliverHeldRemovesTo(restarting);

        assertThat(remove.isResolved()).as("a fence-unknown refusal is not evidence: the remove is not acknowledged yet").isFalse();

        cluster.deliverHeldRemovesTo(applied);
        var outcome = remove.await();
        boolean stale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("refused as stale: " + outcome).isTrue();
    }

    /// v1882 r9, REMOVE: a non-stale refusal first, then a success from a replica that had not applied the change: acknowledged.
    @Test
    void v1882r9_remove_nonStaleRefusalFirst_thenSuccess_acks() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var restarting = replicas.get(1);
        var unaware = replicas.get(2);
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer) && !id.equals(unaware)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(restarting);

        cluster.holdRemoves = true;
        var remove = cluster.client(writer).remove(KEY);
        cluster.holdRemoves = false;
        cluster.deliverHeldRemovesTo(restarting);

        assertThat(remove.isResolved()).as("arming: the non-stale refusal alone does not release the remove").isFalse();

        cluster.deliverHeldRemovesTo(unaware);

        assertThat(remove.await().isSuccess()).as("acknowledged on a remote success").isTrue();
    }

    /// v1882 r9, REMOVE: every remote replies and none is a success or a stale refusal: no evidence, acknowledged per the
    /// named limit before the timeout, and no stale record is set.
    @Test
    void v1882r9_remove_allRemoteRepliesNonStale_acksPerTheLimit_andSetsNoStaleRecord() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = replicas.getFirst();
        var others = cluster.nodes.keySet().stream().filter(id -> !id.equals(writer)).toList();

        others.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        cluster.settleOnly(CHANGE, 3, others);
        cluster.restart(replicas.get(1));
        cluster.restart(replicas.get(2));

        cluster.holdRemoves = true;
        var remove = cluster.client(writer).remove(KEY);
        cluster.holdRemoves = false;
        cluster.deliverHeldRemovesTo(replicas.get(1));

        assertThat(remove.isResolved()).as("arming: one non-stale reply is not the end of the evidence").isFalse();

        cluster.deliverHeldRemovesTo(replicas.get(2));
        var outcome = remove.await(org.pragmatica.lang.io.TimeSpan.timeSpan(150).millis());

        assertThat(outcome.isSuccess()).as("acknowledged per the limit once every remote replied: " + outcome).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).isEqualTo(Option.none());
    }

    /// V1882 r6 CONTROL: the same refusals delivered BEFORE the writer adopts; adoption clears the record.
    @Test
    void v1882r6_control_refusalBeforeAdoption_isClearedByAdoption() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);

        replicas.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        var outcome = cluster.client(writer).put(KEY, VALUE).await();

        boolean refusedStale = outcome.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);
        assertThat(refusedStale).as("" + outcome).isTrue();
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("arming: the genuine refusal is recorded").isNotEqualTo(Option.none());
        cluster.nodes.get(writer).resolveReplication(factors(3, 2), CHANGE);
        assertThat(cluster.nodes.get(writer).staleRefusal()).as("adoption clears it").isEqualTo(Option.none());
    }

    /// Any accepted write ends a stale-refusal episode: the writer's writes are fine again (v1882 round 5).
    @Test
    void acceptedWrite_clearsARecordedStaleRefusal() {
        var cluster = new Cluster(3, shortTimeout(3, 1));
        var writer = cluster.anyId();

        cluster.nodes.get(writer).noteStaleRefusal(CHANGE - 1, 1_000L);
        assertThat(cluster.client(writer).put(KEY, VALUE).await().isSuccess()).isTrue();

        assertThat(settledRecord(cluster, writer)).isEqualTo(Option.none());
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

    /// #1777 R1c on track 3: a remove writes a tombstone, and a tombstone sized under the old factors is fenced like a put.
    /// A writer still on the old change has its remove refused (typed, retriable), and the value stays readable; once it
    /// applies the change, the remove succeeds and the key reads absent.
    @Test
    void removeFromAWriterOnTheOldChange_isRefused_untilItAppliesTheChange() {
        var cluster = new Cluster(5, shortTimeout(3, 1));
        var replicas = cluster.replicasOf(KEY, 3);
        var writer = cluster.nonReplicaOf(replicas);
        var applier = replicas.getFirst();

        replicas.forEach(id -> cluster.nodes.get(id).resolveReplication(factors(3, 2), CHANGE));
        assertThat(cluster.client(applier).put(KEY, VALUE).await().isSuccess()).as("arming: written under the new change").isTrue();

        var refused = cluster.client(writer).remove(KEY).await();
        boolean stale = refused.fold(cause -> cause instanceof DHTError.ReplicationChangeStale, _ -> false);

        assertThat(stale).as("typed, retriable refusal: " + refused).isTrue();
        assertThat(cluster.read(applier)).as("the refused tombstone removed nothing").isEqualTo(Option.some("v"));

        cluster.nodes.get(writer).resolveReplication(factors(3, 2), CHANGE);

        assertThat(cluster.client(writer).remove(KEY).await().isSuccess()).isTrue();
        assertThat(cluster.read(applier)).isEqualTo(Option.none());
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
        volatile boolean holdRemoves;
        final List<Map.Entry<NodeId, DHTMessage.RemoveRequest>> heldRemoves = new java.util.ArrayList<>();

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

        /// Replace `id` with a fresh process in the production boot shape: empty store, awaiting its replication.
        DHTNode restart(NodeId id) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            nodes.keySet().forEach(ring::addNode);
            var fresh = DHTNode.dhtNodeAwaitingReplication(id,
                                                           memoryStorageEngine(),
                                                           ring,
                                                           DHTConfig.DEFAULT,
                                                           org.pragmatica.hlc.HlcClock.hlcClock(id));

            nodes.put(id, fresh);
            antiEntropies.put(id, dhtAntiEntropy(fresh, this::route, _ -> false));

            return fresh;
        }

        void deliverHeldRemovesTo(NodeId target) {
            heldRemoves.stream()
                       .filter(entry -> entry.getKey().equals(target))
                       .forEach(entry -> nodes.get(target).handleRemoveRequest(entry.getValue(), resp -> route(entry.getValue().sender(), resp)));
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
                case DHTMessage.RemoveRequest r -> {
                    if (holdRemoves) {
                        heldRemoves.add(Map.entry(target, r));
                    } else {
                        node.handleRemoveRequest(r, resp -> route(r.sender(), resp));
                    }
                }
                case DHTMessage.RemoveResponse r -> client.onRemoveResponse(r);
                case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.DigestResponse r -> antiEntropy.onDigestResponse(r);
                case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> route(r.sender(), resp));
                case DHTMessage.MigrationDataResponse r -> antiEntropy.onMigrationDataResponse(r);
                default -> {}
            }
        }
    }
}
