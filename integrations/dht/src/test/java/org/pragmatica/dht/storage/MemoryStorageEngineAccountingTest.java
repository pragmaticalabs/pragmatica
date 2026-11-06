package org.pragmatica.dht.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 7: the engine counts the live value bytes and the tombstone bytes it holds per registered prefix, on
/// every path that changes an entry (NT-124), and every key that matches no registered prefix lands in `other` (NT-135).
class MemoryStorageEngineAccountingTest {
    private static final long BASE = HlcTimestamp.pack(System.currentTimeMillis(), 0);
    private static final int TOMBSTONE_OVERHEAD = 64;
    private static final List<String> REGISTERED = List.of("stream-segments", "artifacts", "orders-blocks");
    private static final List<String> BUCKETS = List.of("stream-segments", "artifacts", "orders-blocks", "other");

    private final MemoryStorageEngine engine = memoryStorageEngine(OwnerEpochGate.noOp(), accounting());
    private final ConsistentHashRing<NodeId> ring = ring();

    private static DhtPrefixAccounting accounting() {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting();

        accounting.register("orders-blocks");

        return accounting;
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));

        return ring;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] payload(int size) {
        return new byte[size];
    }

    /// The bucket of a key by the spec's rule, computed independently of the engine: the key's bytes start with a registered
    /// prefix followed by `/`.
    private static String bucketOf(byte[] key) {
        return REGISTERED.stream()
                         .filter(prefix -> startsWith(key, bytes(prefix + "/")))
                         .findFirst()
                         .orElse("other");
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }

        for (var i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    /// NT-124: a random sequence of every path that changes an entry keeps each prefix's counters equal to a recomputation
    /// over the map. Run under several seeds, so a path that forgot its accounting is hit.
    @Test
    void aRandomSequenceOfEveryPath_keepsTheCountersEqualToARecomputation() {
        for (var seed = 1L; seed <= 8; seed++) {
            var engine = memoryStorageEngine(OwnerEpochGate.noOp(), accounting());

            runSequence(engine, new Random(seed), seed);
        }
    }

    private void runSequence(MemoryStorageEngine engine, Random random, long seed) {
        var keys = keys();
        var clock = new long[]{BASE};

        for (var step = 0; step < 400; step++) {
            var key = keys.get(random.nextInt(keys.size()));
            var op = random.nextInt(13);
            var version = random.nextInt(4) == 0
                          ? BASE - random.nextInt(50)
                          : ++clock[0];
            var value = payload(1 + random.nextInt(40));

            apply(engine, op, key, value, version, random);
            assertMatchesRecomputation(engine, "seed " + seed + " step " + step + " op " + op);
        }
    }

    private void apply(MemoryStorageEngine engine, int op, byte[] key, byte[] value, long version, Random random) {
        var partition = ring.partitionFor(key);

        switch (op) {
            case 0 -> engine.put(key, value).await();
            case 1 -> engine.putVersioned(key, value, version).await();
            case 2 -> engine.putVersionedDisplacing(key, value, version, 0L, 0L, 0L).await();
            case 3 -> engine.removeVersioned(key, version, 0L, 0L, 0L).await();
            case 4 -> engine.removeVersionedDisplacing(key, version, 0L, 0L, 0L).await();
            case 5 -> engine.putReplica(key, value, version, 0L, 0L, 0L).await();
            case 6 -> engine.putReplica(new KeyValue(key, value, version, 0L, 0L, 0L), random.nextBoolean()).await();
            case 7 -> engine.putReplica(new KeyValue(key, new byte[0], version, 0L, 0L, 0L, true), random.nextBoolean()).await();
            case 8 -> engine.remove(key).await();
            case 9 -> engine.collectTombstones(ring, partition, Long.MAX_VALUE).await();
            case 10 -> engine.dropPartition(ring, partition).await();
            case 11 -> engine.removeIfExactly(key, storedVersion(engine, key, version), 0L, 0L, 0L).await();
            default -> engine.restoreIfExactly(key, storedVersion(engine, key, version), 0L, 0L, 0L, prior(key, value, random))
                             .await();
        }
    }

    /// What the counters must read: a recomputation over every entry the map holds.
    /// The version the key holds now, so a compare-and-act on it matches; the given one when the key is absent.
    private static long storedVersion(MemoryStorageEngine engine, byte[] key, long fallback) {
        return engine.getEntry(key).await().or(Option.<KeyValue>none()).map(KeyValue::version).or(fallback);
    }

    private static Option<KeyValue> prior(byte[] key, byte[] value, Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> Option.none();
            case 1 -> Option.some(new KeyValue(key, value, BASE - 100, 0L, 0L, 0L));
            default -> Option.some(new KeyValue(key, new byte[0], BASE - 100, 0L, 0L, 0L, true));
        };
    }

    private void assertMatchesRecomputation(MemoryStorageEngine subject, String after) {
        var live = new TreeMap<String, Long>();
        var tombstones = new TreeMap<String, Long>();

        BUCKETS.forEach(bucket -> {
            live.put(bucket, 0L);
            tombstones.put(bucket, 0L);
        });
        subject.entries()
               .await()
               .or(List.of())
               .forEach(entry -> {
                            var bucket = bucketOf(entry.key());

                            if (entry.tombstone()) {
                                tombstones.merge(bucket, (long) entry.key().length + TOMBSTONE_OVERHEAD, Long::sum);
                            } else {
                                live.merge(bucket, (long) entry.value().length, Long::sum);
                            }
                        });
        BUCKETS.forEach(bucket -> {
            assertThat(subject.usedBytes(bucket)).as("live bytes of %s after %s", bucket, after).isEqualTo(live.get(bucket));
            assertThat(subject.tombstoneBytes(bucket)).as("tombstone bytes of %s after %s", bucket, after)
                                                      .isEqualTo(tombstones.get(bucket));
        });
    }

    private static List<byte[]> keys() {
        var keys = new ArrayList<byte[]>();

        for (var i = 0; i < 6; i++) {
            keys.add(bytes("stream-segments/" + i));
            keys.add(bytes("artifacts/" + i));
            keys.add(bytes("orders-blocks/" + i));
            keys.add(bytes("cache:" + i));
        }
        keys.add(bytes("stream-segments"));
        keys.add(new byte[]{0, 1, 2, (byte) 0xFF});

        return keys;
    }

    /// MT-53: a tombstone write moves the key from live to tombstone bytes, at the key's length plus the fixed overhead; the
    /// tombstone's collection takes it away again.
    @Test
    void aTombstoneWrite_releasesTheLiveBytes_andCountsKeyLengthPlusOverhead_untilCollected() {
        var key = bytes("stream-segments/block-1");

        engine.putVersioned(key, payload(100), BASE).await();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(100L);
        assertThat(engine.tombstoneBytes("stream-segments")).isZero();

        engine.removeVersioned(key, BASE + 1, 0L, 0L, 0L).await();

        assertThat(engine.usedBytes("stream-segments")).as("the value is gone").isZero();
        assertThat(engine.tombstoneBytes("stream-segments")).isEqualTo(key.length + TOMBSTONE_OVERHEAD);

        engine.collectTombstones(ring, ring.partitionFor(key), Long.MAX_VALUE).await();

        assertThat(engine.tombstoneBytes("stream-segments")).as("collected").isZero();
    }

    /// An idempotent re-put of the same block adds nothing; a replacement counts new minus old.
    @Test
    void aReplacement_countsNewMinusOld_andAnIdempotentRePutAddsNothing() {
        var key = bytes("stream-segments/block-1");

        engine.putVersioned(key, payload(100), BASE).await();
        engine.putVersioned(key, payload(100), BASE).await();
        assertThat(engine.usedBytes("stream-segments")).as("same version, same bytes").isEqualTo(100L);

        engine.putVersioned(key, payload(130), BASE + 1).await();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(130L);

        engine.putVersioned(key, payload(10), BASE + 2).await();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(10L);
    }

    /// NT-135: a key without the separator, a cache `namespace:key`, a binary key and a prefix without the `/` after it all
    /// count in `other`; a registered prefix needs the `/`.
    @Test
    void keysThatMatchNoRegisteredPrefix_countInOther() {
        engine.put(bytes("no-separator"), payload(1)).await();
        engine.put(bytes("namespace:key"), payload(2)).await();
        engine.put(new byte[]{0x00, (byte) 0xFE, 0x01}, payload(4)).await();
        engine.put(bytes("stream-segments"), payload(8)).await();
        engine.put(bytes("stream-segments-extra/x"), payload(16)).await();
        engine.put(bytes("unregistered/x"), payload(32)).await();

        assertThat(engine.usedBytes("other")).isEqualTo(1 + 2 + 4 + 8 + 16 + 32);
        assertThat(engine.usedBytes("stream-segments")).isZero();
        assertThat(engine.usedBytes("artifacts")).isZero();
    }

    /// NT-135: the counter table never grows past the registered prefixes plus `other`, however many distinct leading
    /// segments the keys carry.
    @Test
    void theCounterTable_neverGrowsPastTheRegisteredPrefixesPlusOther() {
        var random = new Random(7);

        for (var i = 0; i < 2_000; i++) {
            engine.put(bytes("tenant-" + random.nextInt(1_000) + "/" + i), payload(1)).await();
            engine.put(bytes("ns" + i + ":key"), payload(1)).await();
        }

        assertThat(new HashSet<>(engine.accountedPrefixes())).isEqualTo(new HashSet<>(BUCKETS));
        assertThat(engine.accountedPrefixes()).hasSize(BUCKETS.size());
        assertThat(engine.usedBytes("other")).isEqualTo(4_000L);
    }
}
