package org.pragmatica.dht.storage;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 3: a remove stores a TOMBSTONE ordered exactly like a value, so it supersedes every older copy and
/// loses to every newer write — wherever repair, migration or a hand-off carries either.
class MemoryStorageEngineTombstoneTest {
    private static final byte[] KEY = "removed".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "value".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = HlcTimestamp.pack(System.currentTimeMillis(), 0);
    private static final long EARLIER = NOW - (1L << 20);
    private static final long LATER = NOW + (1L << 20);

    @Test
    void removeVersioned_overAnOlderValue_readsAbsentAndHoldsATombstone() {
        var storage = memoryStorageEngine();

        storage.putVersioned(KEY, VALUE, EARLIER).await();

        assertThat(storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await().or(false)).as("a live value was superseded").isTrue();
        assertThat(storage.get(KEY).await().or(Option.none()).isPresent()).isFalse();
        assertThat(storage.exists(KEY).await().or(true)).isFalse();
        assertThat(storage.keys().await().or(java.util.List.of())).isEmpty();
        assertThat(entry(storage).tombstone()).isTrue();
        assertThat(storage.tombstoneCount()).isEqualTo(1);
    }

    @Test
    void olderValueCopy_losesToTheTombstone() {
        var storage = memoryStorageEngine();

        storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await();

        assertThat(storage.putReplica(KEY, VALUE, EARLIER, 0L, 0L, 0L).await().or(true)).isFalse();
        assertThat(storage.putReplica(new KeyValue(KEY, VALUE, EARLIER, 0L, 0L, 0L), true).await().or(true)).isFalse();
        assertThat(entry(storage).tombstone()).isTrue();
    }

    @Test
    void newerPut_supersedesTheTombstone() {
        var storage = memoryStorageEngine();

        storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await();
        storage.putVersioned(KEY, VALUE, LATER).await();

        assertThat(storage.get(KEY).await().or(Option.none()).isPresent()).isTrue();
    }

    @Test
    void tombstoneCopy_supersedesAnOlderValue() {
        var storage = memoryStorageEngine();

        storage.putVersioned(KEY, VALUE, EARLIER).await();

        assertThat(storage.putReplica(tombstone(NOW), true).await().or(false)).isTrue();
        assertThat(storage.get(KEY).await().or(Option.none()).isPresent()).isFalse();
    }

    @Test
    void removeVersioned_staleEpoch_isRefusedByTheFence() {
        var storage = memoryStorageEngine(new OwnerEpochGate() {
            @Override
            public boolean isStale(byte[] key, long incarnation, long term, long counter) {
                return true;
            }

            @Override
            public void advance(byte[] key, long incarnation, long term, long counter) {}
        });

        var result = storage.removeVersioned(KEY, NOW, 0L, 1L, 1L).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.StaleEpochWrite.class));
    }

    /// An expired tombstone copy must not re-create an entry its holders already collected — or it would bounce
    /// between replicas forever — but it still kills a stale value it meets.
    @Test
    void copyNotCreatingIfAbsent_createsNoEntry_butSupersedesAnOlderValue() {
        var empty = memoryStorageEngine();

        assertThat(empty.putReplica(tombstone(NOW), false).await().or(true)).isFalse();
        assertThat(empty.getEntry(KEY).await().or(Option.none()).isPresent()).isFalse();

        var stale = memoryStorageEngine();

        stale.putVersioned(KEY, VALUE, EARLIER).await();

        assertThat(stale.putReplica(tombstone(NOW), false).await().or(false)).isTrue();
        assertThat(entry(stale).tombstone()).isTrue();
    }

    @Test
    void collectTombstones_takesOnlyTombstonesStampedAtOrBeforeTheCutoff_andNeverValues() {
        var storage = memoryStorageEngine();
        var ring = ring();
        var partition = ring.partitionFor(KEY);
        var value = keyInside(ring, partition);

        storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await();
        storage.putVersioned(value, VALUE, EARLIER).await();

        assertThat(storage.collectTombstones(ring, partition, HlcTimestamp.physicalMillis(NOW) - 1).await().or(-1)).isZero();
        assertThat(entry(storage).tombstone()).isTrue();
        assertThat(storage.collectTombstones(ring, partition, HlcTimestamp.physicalMillis(NOW)).await().or(-1)).isEqualTo(1);
        assertThat(storage.getEntry(KEY).await().or(Option.none()).isPresent()).isFalse();
        assertThat(storage.get(value).await().or(Option.none()).isPresent()).as("a value is never collected").isTrue();
    }

    @Test
    void dropPartition_dropsValuesAndTombstonesOfThatPartitionOnly() {
        var storage = memoryStorageEngine();
        var ring = ring();
        var partition = ring.partitionFor(KEY);

        storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await();
        var elsewhere = keyOutside(ring, partition);

        storage.putVersioned(elsewhere, VALUE, NOW).await();

        assertThat(storage.dropPartition(ring, partition).await().or(-1)).isEqualTo(1);
        assertThat(storage.getEntry(KEY).await().or(Option.none()).isPresent()).isFalse();
        assertThat(storage.get(elsewhere).await().or(Option.none()).isPresent()).isTrue();
    }

    @Test
    void entries_carryTombstones() {
        var storage = memoryStorageEngine();

        storage.removeVersioned(KEY, NOW, 0L, 0L, 0L).await();

        assertThat(storage.entries().await().or(java.util.List.of())).singleElement().matches(KeyValue::tombstone);
    }

    private static KeyValue tombstone(long version) {
        return new KeyValue(KEY, new byte[0], version, 0L, 0L, 0L, true);
    }

    private static KeyValue entry(MemoryStorageEngine storage) {
        return storage.getEntry(KEY).await().or(Option.none()).unwrap();
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("node-0"));

        return ring;
    }

    private static byte[] keyInside(ConsistentHashRing<NodeId> ring, org.pragmatica.dht.Partition partition) {
        for (int i = 0; ; i++) {
            var candidate = ("same-" + i).getBytes(StandardCharsets.UTF_8);

            if (ring.partitionFor(candidate).equals(partition) && !java.util.Arrays.equals(candidate, KEY)) {
                return candidate;
            }
        }
    }

    private static byte[] keyOutside(ConsistentHashRing<NodeId> ring, org.pragmatica.dht.Partition partition) {
        for (int i = 0; ; i++) {
            var candidate = ("other-" + i).getBytes(StandardCharsets.UTF_8);

            if (!ring.partitionFor(candidate).equals(partition)) {
                return candidate;
            }
        }
    }
}
