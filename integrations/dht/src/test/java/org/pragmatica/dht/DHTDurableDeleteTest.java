package org.pragmatica.dht;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DurableDeleteCluster.bytes;
import static org.pragmatica.dht.DurableDeleteCluster.joined;
import static org.pragmatica.dht.DurableDeleteCluster.removed;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1777 track 3: a remove acknowledged at the confirmation factor is never undone — not by a replica that missed it,
/// not by anti-entropy, not by a stray copy a ring change left behind, not by a node that rejoins with its old store.
class DHTDurableDeleteTest {
    private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final DHTConfig RF4 = new DHTConfig(4, 3, 2, timeSpan(2).seconds());
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, timeSpan(2).seconds());
    private static final long TWO_HOURS = 2 * 3600_000L;
    private static final byte[] VALUE = bytes("v");

    @Nested
    class Reads {
        /// The reader's own stale replica answers first with the removed value; the newer tombstone from a replica
        /// that took the remove must win the read.
        @Test
        void staleValueAndNewerTombstone_readAndExists_resolveAbsent() {
            var cluster = new DurableDeleteCluster(3, RF3);
            var key = bytes("read-merge");
            var stale = cluster.replicasOf(key).getFirst();

            putThenRemoveMissing(cluster, key, stale);

            assertThat(cluster.holdsLive(stale, key)).as("control: the stale replica still holds the value").isTrue();
            assertThat(cluster.member(stale).client().get(key).await().unwrap().isPresent()).isFalse();
            assertThat(cluster.member(stale).client().exists(key).await().unwrap()).isFalse();
        }

        /// A tombstone in the R-set is decisive: the fallback probe that could only find a copy the remove
        /// superseded never runs, and that copy is not re-homed.
        @Test
        void tombstoneInTheReadSet_resolvesAbsent_withoutReHomingAStrayCopy() {
            var cluster = new DurableDeleteCluster(5, RF3);
            var key = bytes("stray-after-remove");
            var stray = strayOf(cluster, key);
            var reader = cluster.replicasOf(key).getFirst();

            cluster.member(stray).storage().putReplica(key, VALUE, olderStamp(), 0L, 0L, 0L).await();
            cluster.member(reader).client().put(key, VALUE).await().unwrap();
            cluster.member(reader).client().remove(key).await().unwrap();

            assertThat(cluster.member(reader).client().get(key).await().unwrap().isPresent()).isFalse();
            assertThat(cluster.replicasOf(key)).allMatch(id -> cluster.holdsTombstone(id, key));
        }

        /// F1: a copy found by the fallback probe is re-homed with its ORIGINAL stamp, so it can never beat a remove
        /// stamped after it.
        @Test
        void fallbackRepair_keepsTheCopysOriginalStamp() {
            var cluster = new DurableDeleteCluster(5, RF3);
            var key = bytes("stranded-copy");
            var stray = strayOf(cluster, key);
            var reader = cluster.replicasOf(key).getFirst();
            var original = olderStamp();

            cluster.member(stray).storage().putReplica(key, VALUE, original, 0L, 0L, 0L).await();

            assertThat(cluster.member(reader).client().get(key).await().unwrap().isPresent()).as("control: found via fallback")
                                                                                        .isTrue();
            assertThat(cluster.replicasOf(key)).allMatch(id -> cluster.entryAt(id, key)
                                                                      .filter(entry -> entry.version() == original)
                                                                      .isPresent());

            cluster.member(reader).client().remove(key).await().unwrap();

            assertThat(cluster.member(reader).client().get(key).await().unwrap().isPresent()).isFalse();
        }
    }

    @Nested
    class Repair {
        /// The ticket's acceptance: RF 3 / CF 2, the remove misses one replica, anti-entropy runs everywhere — the
        /// value is resurrected nowhere.
        @Test
        void removeMissedByOneReplica_isNotResurrectedByAntiEntropy() {
            var cluster = new DurableDeleteCluster(3, RF3);
            var key = bytes("acceptance");
            var stale = cluster.replicasOf(key).getLast();

            putThenRemoveMissing(cluster, key, stale);
            cluster.synchronizeAll();
            cluster.synchronizeAll();

            assertThat(cluster.members()).allMatch(member -> !cluster.holdsLive(member.id(), key));
            assertThat(cluster.members()).allMatch(member -> member.client().get(key).await().unwrap().isEmpty());
        }

        /// A rollback after an indeterminate remove is an exact-stamp HARD delete: a tombstone there would beat the
        /// previous value on every replica it reached and delete the key cluster-wide.
        @Test
        void fencedRemove_isIndeterminate_andTheValueSurvivesEverywhere() {
            var deposed = new NodeId("node-0");
            var cluster = new DurableDeleteCluster(3,
                                                   RF3,
                                                   id -> new Gate(id.equals(deposed) ? OLD : NEW),
                                                   _ -> new FixedEpoch(OLD));
            var key = bytes("deposed-remove");

            cluster.members()
                   .forEach(member -> member.storage().putReplica(key, VALUE, olderStamp(), OLD[0], OLD[1], OLD[2]).await());

            var remove = cluster.member(deposed).client().remove(key).await();

            assertThat(remove.isFailure()).isTrue();
            remove.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.WriteIndeterminate.class)
                                                      .isInstanceOf(Cause.Transient.class));
            cluster.synchronizeAll();
            cluster.synchronizeAll();

            assertThat(cluster.members()).allMatch(member -> cluster.holdsLive(member.id(), key));
        }
    }

    @Nested
    class Collection {
        /// An expired tombstone is held while a co-replica still holds the value it removed; once every co-replica
        /// agrees, it is collected, and the value comes back nowhere.
        @Test
        void expiredTombstone_isCollectedOnlyAfterEveryCoReplicaAgrees() {
            var cluster = new DurableDeleteCluster(3, RF3);
            var key = bytes("gc-agreement");
            var stale = cluster.replicasOf(key).getLast();
            var holders = cluster.replicasOf(key).stream().filter(id -> !id.equals(stale)).toList();

            putThenRemoveMissing(cluster, key, stale);
            cluster.advanceClockBy(TWO_HOURS);
            holders.forEach(id -> cluster.member(id).antiEntropy().synchronizeNow());

            assertThat(holders).as("held while %s holds the value", stale.id())
                               .allMatch(id -> cluster.holdsTombstone(id, key));

            cluster.member(stale).antiEntropy().synchronizeNow();
            assertThat(cluster.holdsLive(stale, key)).as("the expired tombstone still killed the stale value").isFalse();

            cluster.synchronizeAll();

            assertThat(cluster.members()).allMatch(member -> cluster.entryAt(member.id(), key).isEmpty());
            assertThat(cluster.members()).allMatch(member -> member.node().collectedTombstoneCount() <= 1);
            assertThat(cluster.member(stale).client().get(key).await().unwrap().isPresent()).isFalse();
        }

        /// A holder displaced by a replica-set change keeps a stray copy until the stray horizon; a tombstone it
        /// could outlive is not collected until the set has been stable for the retention, so the stray is always
        /// gone first and no fallback probe can find it afterwards.
        @Test
        void tombstone_outlivesAStrayCopyThatADisplacedHolderKept() {
            var cluster = new DurableDeleteCluster(4, RF4);
            var key = bytes("gc-stray");
            var displaced = cluster.replicasOf(key).getLast();

            putThenRemoveMissing(cluster, key, displaced);
            cluster.advanceClockBy(TWO_HOURS);
            cluster.members().forEach(member -> member.node().resolveReplication(RF3));

            var replicas = cluster.replicasOf(key);
            var reader = replicas.getFirst();

            assertThat(replicas).as("control: the displaced holder left the replica set").doesNotContain(displaced);
            cluster.synchronizeAll();

            assertThat(replicas).as("a holder left within the retention: the tombstone waits")
                                .allMatch(id -> cluster.holdsTombstone(id, key));
            assertThat(cluster.member(reader).client().get(key).await().unwrap().isPresent()).isFalse();

            cluster.advanceClockBy(DHTNode.DEFAULT_TOMBSTONE_RETENTION.millis() + 1_000L);
            cluster.synchronizeAll();
            cluster.synchronizeAll();

            assertThat(cluster.entryAt(displaced, key).isEmpty()).as("the stray copy was dropped").isTrue();
            assertThat(replicas).as("then the tombstone was collected").allMatch(id -> cluster.entryAt(id, key).isEmpty());
            assertThat(cluster.member(reader).client().get(key).await().unwrap().isPresent()).isFalse();
        }

        /// A catch-up that pulls an EXPIRED tombstone it has no entry for completes IN THAT ROUND: the tombstone is
        /// deliberately not re-created, and that must not read as a copy the store refused. (Without the exemption the
        /// partition still completes, one round later, because the digest leaves expired tombstones out.)
        @Test
        void catchUp_completesWhenAPulledTombstoneIsExpiredAndAbsentHere() {
            var cluster = new DurableDeleteCluster(3, RF3);
            var key = bytes("expired-in-catch-up");
            var live = bytes("live-neighbour");
            var booter = cluster.replicasOf(key).getFirst();
            var partition = cluster.member(booter).node().partitionFor(key);

            cluster.members()
                   .stream()
                   .filter(member -> !member.id().equals(booter))
                   .forEach(member -> {
                       member.storage().putReplica(new DHTMessage.KeyValue(key, new byte[0], olderStamp(), 0L, 0L, 0L, true), true).await();
                       member.storage().putReplica(samePartition(cluster, booter, key, live), VALUE, olderStamp(), 0L, 0L, 0L).await();
                   });
            cluster.advanceClockBy(TWO_HOURS);
            cluster.member(booter).node().beginCatchUp();
            cluster.member(booter).antiEntropy().catchUpNow();

            assertThat(cluster.member(booter).node().readiness(partition)).isEqualTo(DHTMessage.Readiness.SERVING);
            assertThat(cluster.entryAt(booter, key).isEmpty()).isTrue();
        }
    }

    @Nested
    class Rejoin {
        /// Owner acceptance for the cache (RF 1): the owner leaves, the entry is removed at the new owner, the old
        /// owner regains the key still holding the value — and reads absent, because catch-up brings the tombstone.
        @Test
        void cacheRf1_removeWhileOwnerAway_isNotServedAfterItRegainsTheKey() {
            var cluster = new DurableDeleteCluster(3, RF1);
            var key = bytes("cache-entry");
            var owner = cluster.replicasOf(key).getFirst();

            cluster.member(owner).client().put(key, VALUE).await().unwrap();
            cluster.members().forEach(member -> member.node().changeRing(ring -> ring.removeNode(owner)));
            cluster.catchUpAll(3);

            var interim = cluster.replicasOf(key).getFirst();

            cluster.member(interim).client().remove(key).await().unwrap();
            cluster.members().forEach(member -> member.node().changeRing(ring -> ring.addNode(owner)));
            cluster.catchUpAll(3);

            assertThat(cluster.holdsLive(owner, key)).isFalse();
            assertThat(cluster.members()).allMatch(member -> member.client().get(key).await().unwrap().isEmpty());
        }

        /// A node REMOVED from the cluster while running — paused, so it ran nothing while it was out — drops its
        /// store when it applies its own removal. If it rejoins after the tombstone was collected it cannot resurrect
        /// the value: it rejoins empty, as a restarted node does.
        @Test
        void nodeRemovedWhileRunning_rejoinsEmpty_afterTheTombstoneWasCollected() {
            var cluster = new DurableDeleteCluster(3, RF1);
            var key = bytes("rejoin-after-gc");
            var owner = cluster.replicasOf(key).getFirst();
            var others = cluster.members().stream().filter(member -> !member.id().equals(owner)).toList();

            cluster.member(owner).client().put(key, VALUE).await().unwrap();
            others.forEach(member -> member.listener().onNodeRemoved(removed(owner)));
            others.forEach(member -> member.antiEntropy().catchUpNow());

            var interim = others.getFirst().node().ring().nodesFor(key, 1).getFirst();

            cluster.member(interim).client().remove(key).await().unwrap();
            cluster.advanceClockBy(TWO_HOURS);
            others.forEach(member -> member.antiEntropy().synchronizeNow());

            assertThat(cluster.entryAt(interim, key).isEmpty()).as("control: the tombstone was collected").isTrue();

            cluster.member(owner).listener().onNodeRemoved(removed(owner));
            cluster.members().forEach(member -> member.listener().onNodeJoined(joined(owner)));
            cluster.catchUpAll(3);

            assertThat(cluster.holdsLive(owner, key)).isFalse();
            assertThat(cluster.members()).allMatch(member -> member.client().get(key).await().unwrap().isEmpty());
        }

        /// A DEPARTING node keeps its store: its departure push hands it off before it halts.
        @Test
        void departingNode_keepsItsStore_whenItsRemovalIsCommitted() {
            var cluster = new DurableDeleteCluster(3, RF3);
            var key = bytes("departing-keeps");
            var departing = cluster.replicasOf(key).getFirst();

            cluster.member(departing).client().put(key, VALUE).await().unwrap();
            cluster.member(departing).listener().onNodeDeparting(departing);
            cluster.member(departing).listener().onNodeRemoved(removed(departing));

            assertThat(cluster.holdsLive(departing, key)).isTrue();
        }
    }

    // --- helpers ---

    private static final long[] OLD = {0L, 1L, 1L};
    private static final long[] NEW = {0L, 2L, 2L};

    /// Put through `stale`, then remove while `stale` is unreachable: it keeps the value the others removed.
    private static void putThenRemoveMissing(DurableDeleteCluster cluster, byte[] key, NodeId stale) {
        var writer = cluster.replicasOf(key).stream().filter(id -> !id.equals(stale)).findFirst().orElseThrow();

        cluster.member(writer).client().put(key, VALUE).await().unwrap();
        cluster.unreachable.add(stale);
        cluster.member(writer).client().remove(key).await().unwrap();
        cluster.unreachable.remove(stale);
    }

    /// A ring member outside `key`'s replica set.
    private static NodeId strayOf(DurableDeleteCluster cluster, byte[] key) {
        var replicas = cluster.replicasOf(key);

        return cluster.members().stream().map(DurableDeleteCluster.Member::id).filter(id -> !replicas.contains(id)).findFirst().orElseThrow();
    }

    /// Another key in `key`'s partition, derived from `seed`.
    private static byte[] samePartition(DurableDeleteCluster cluster, NodeId any, byte[] key, byte[] seed) {
        var node = cluster.member(any).node();
        var partition = node.partitionFor(key);

        return IntStream.range(0, 1_000_000)
                        .mapToObj(i -> bytes(new String(seed) + "-" + i))
                        .filter(candidate -> node.partitionFor(candidate).equals(partition))
                        .findFirst()
                        .orElseThrow();
    }

    /// An HLC stamp an hour old — older than any stamp a client writes during the test.
    private static long olderStamp() {
        return HlcTimestamp.pack(System.currentTimeMillis() - 3600_000L, 0);
    }

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater;

        Gate(long[] seeded) {
            highWater = new AtomicReference<>(seeded);
        }

        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return Arrays.compare(highWater.get(), new long[]{incarnation, term, counter}) > 0;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {
            highWater.accumulateAndGet(new long[]{incarnation, term, counter},
                                       (current, presented) -> Arrays.compare(presented, current) > 0 ? presented : current);
        }
    }

    private record FixedEpoch(long[] epoch) implements OwnerEpochSource {
        @Override
        public long currentEpochIncarnation() {
            return epoch[0];
        }

        @Override
        public long currentEpochTerm() {
            return epoch[1];
        }

        @Override
        public long currentEpochCounter() {
            return epoch[2];
        }
    }
}
