package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DurableDeleteCluster.bytes;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// T-06 (stream-storage design §9.1, §4.5): pins that tombstone expiry does not resurrect a deleted block. A replica C
/// cut off past the tombstone expiry, then healed, does not bring the block back, by two rules:
/// - keep until agreed: an expired tombstone is collected only in a round where every co-replica answered with an
///   equal digest (`DHTAntiEntropy.startAgreementRound`, `DHTAntiEntropy.java:738-746`; `voteAgreement`, `:966-974`;
///   `onAgreed`, `:833-860`). A cut-off C never answers, so A and B keep the tombstone. This is the UNREACHABLE
///   variant; `DHTDurableDeleteTest.expiredTombstone_isCollectedOnlyAfterEveryCoReplicaAgrees` pins the mismatching one.
/// - an expired tombstone still supersedes: a copied expired tombstone never creates an entry, but it is applied to a
///   stale value it meets (`DHTNode.applyReplica`, `DHTNode.java:1402-1411`), so C's live copy dies at the heal.
///
/// Control arm: healed before the expiry, the block stays deleted on every replica.
///
/// The "never creates an entry" half of the second rule is pinned by
/// `expiredTombstoneRejoin_neverCreatesAnEntryOnAReplicaThatNeverHeldTheBlock`.
class DHTExpiredTombstoneRejoinTest {
    private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final byte[] BLOCK = bytes("block");
    private static final long ONE_MINUTE = 60_000L;

    @Test
    void expiredTombstoneRejoin_readSha() {
        var control = new DurableDeleteCluster(3, RF3);
        var controlKey = bytes("t06-control");
        var controlCutOff = deleteWhileCutOff(control, controlKey);

        control.advanceClockBy(retentionMillis(control) - ONE_MINUTE);
        healAndRunOneRound(control, controlCutOff);

        assertThat(control.members()).as("control arm: healed before the tombstone expiry, the block stays deleted on A, B and C")
                                     .allMatch(member -> !control.holdsLive(member.id(), controlKey));

        var cluster = new DurableDeleteCluster(3, RF3);
        var key = bytes("t06-main");
        var cutOff = deleteWhileCutOff(cluster, key);
        var holders = otherThan(cluster.replicasOf(key), cutOff);

        cluster.advanceClockBy(retentionMillis(cluster) + ONE_MINUTE);

        assertThat(holders).as("positive control: the injected clock reaches the expiry of A's and B's tombstone")
                           .allMatch(id -> isExpiredTombstone(cluster, id, key));

        holders.forEach(id -> cluster.member(id).antiEntropy().synchronizeNow());

        assertThat(holders).as("keep until agreed: a round on A and B while C is cut off does not collect the expired tombstone")
                           .allMatch(id -> cluster.holdsTombstone(id, key));

        healAndRunOneRound(cluster, cutOff);
        cluster.synchronizeAll();

        assertThat(holders).as("holders: A and B do not take C's stale value back after the heal (two rounds: the first keeps or collects, the second would let a surviving live copy propagate)")
                           .noneMatch(id -> cluster.holdsLive(id, key));
        assertThat(cluster.holdsLive(cutOff, key)).as("C: the expired tombstone supersedes C's stale value after the heal")
                                                  .isFalse();
    }

    @Test
    void expiredTombstoneRejoin_neverCreatesAnEntryOnAReplicaThatNeverHeldTheBlock() {
        var cluster = new DurableDeleteCluster(3, RF3);
        var key = bytes("t06-never-held");
        var replicas = cluster.replicasOf(key);
        var cutOff = replicas.getLast();
        var holders = otherThan(replicas, cutOff);
        var companion = keyInPartitionOf(cluster, key);

        cluster.unreachable.add(cutOff);
        cluster.member(replicas.getFirst()).client().put(key, BLOCK).await().unwrap();
        cluster.member(replicas.getFirst()).client().put(companion, BLOCK).await().unwrap();
        cluster.member(replicas.getFirst()).client().remove(key).await().unwrap();
        cluster.advanceClockBy(retentionMillis(cluster) + ONE_MINUTE);

        assertThat(holders).as("A and B hold the expired tombstone while C never held the block")
                           .allMatch(id -> isExpiredTombstone(cluster, id, key));
        assertThat(cluster.entryAt(cutOff, key).isPresent()).as("C holds no entry before the heal").isFalse();

        healAndRunOneRound(cluster, cutOff);

        assertThat(cluster.holdsLive(cutOff, companion)).as("positive control: the heal pulled the partition's page to C, which carries the expired tombstone")
                                                        .isTrue();
        assertThat(cluster.entryAt(cutOff, key).isPresent()).as("C: a copied expired tombstone does not create an entry (judged after one round: a second round would collect a created one)").isFalse();
    }

    /// Writes the block to all three replicas, cuts replica C off, and deletes the block on A and B (CF 2).
    private static NodeId deleteWhileCutOff(DurableDeleteCluster cluster, byte[] key) {
        var replicas = cluster.replicasOf(key);
        var cutOff = replicas.getLast();
        var writer = replicas.getFirst();

        cluster.member(writer).client().put(key, BLOCK).await().unwrap();
        cluster.unreachable.add(cutOff);
        cluster.member(writer).client().remove(key).await().unwrap();

        assertThat(cluster.holdsLive(cutOff, key)).as("C missed the delete").isTrue();
        assertThat(otherThan(replicas, cutOff)).as("A and B hold the tombstone").allMatch(id -> cluster.holdsTombstone(id, key));

        return cutOff;
    }

    /// A second block in the same partition as `key`, so that a live entry keeps the partition digests of A, B and C apart
    /// (an expired tombstone is excluded from the digest) and the heal pulls the partition's page, expired tombstone included.
    private static byte[] keyInPartitionOf(DurableDeleteCluster cluster, byte[] key) {
        var node = cluster.members().getFirst().node();
        var partition = node.partitionFor(key);

        return IntStream.range(0, 100_000)
                        .mapToObj(i -> bytes("t06-companion-" + i))
                        .filter(candidate -> node.partitionFor(candidate).equals(partition))
                        .findFirst()
                        .orElseThrow();
    }

    private static void healAndRunOneRound(DurableDeleteCluster cluster, NodeId cutOff) {
        cluster.unreachable.remove(cutOff);
        cluster.synchronizeAll();
    }

    private static boolean isExpiredTombstone(DurableDeleteCluster cluster, NodeId id, byte[] key) {
        return cluster.entryAt(id, key).filter(cluster.member(id).node()::expiredTombstone).isPresent();
    }

    private static long retentionMillis(DurableDeleteCluster cluster) {
        return cluster.members().getFirst().node().tombstoneRetention().millis();
    }

    private static List<NodeId> otherThan(List<NodeId> replicas, NodeId excluded) {
        return replicas.stream().filter(id -> !id.equals(excluded)).toList();
    }
}
