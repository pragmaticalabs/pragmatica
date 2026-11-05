package org.pragmatica.dht.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 7, the cap on every engine path: which writes are client writes (refused above the cap), which are
/// repair or replica writes (accounted, never refused), how a refusal leaves the engine, and that every path that changes
/// an entry reports its transitions. The per-path pins the mutation sweep of the PR asked for.
class MemoryStorageEngineCapacityPathsTest {
    private static final long CAP = 100L;
    private static final long BASE = HlcTimestamp.pack(System.currentTimeMillis(), 0);
    private static final String SEG = "stream-segments/";

    private final MemoryStorageEngine engine = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(CAP));
    private long version = BASE;

    private static byte[] key(String name) {
        return (SEG + name).getBytes(StandardCharsets.UTF_8);
    }

    private static Cause failureOf(Promise<?> promise) {
        return promise.await().<Cause>fold(cause -> cause, _ -> null);
    }

    private void fill(String name, int size) {
        assertThat(engine.putVersioned(key(name), new byte[size], ++version).await().isSuccess()).isTrue();
    }

    private void assertRefusedAtSixtyOfHundred(Cause cause) {
        assertThat(cause).isInstanceOf(DHTError.ReplicaFull.class);
        var full = (DHTError.ReplicaFull) cause;

        assertThat(full.usedBytes()).isEqualTo(60L);
        assertThat(full.maxBytes()).isEqualTo(CAP);
        assertThat(cause.message()).startsWith("DHT replica full");
    }

    @Test
    void anUnversionedPut_aboveTheCap_isRefusedReplicaFull_andStoresNothing() {
        assertThat(engine.put(key("a"), new byte[60]).await().isSuccess()).isTrue();

        assertRefusedAtSixtyOfHundred(failureOf(engine.put(key("b"), new byte[60])));
        assertThat(engine.getEntry(key("b")).await().or(Option.<KeyValue>none()).isPresent()).isFalse();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(60L);
    }

    @Test
    void theOverloadsWithoutTheRepairFlag_areClientWrites() {
        fill("a", 60);

        assertRefusedAtSixtyOfHundred(failureOf(engine.putVersioned(key("b"), new byte[60], ++version, 0L, 0L, 0L)));
        assertRefusedAtSixtyOfHundred(failureOf(engine.putVersionedDisplacing(key("c"), new byte[60], ++version, 0L, 0L, 0L)));
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(60L);
    }

    @Test
    void aDisplacingPut_isRefusedAboveTheCapUnlessItIsARepair_andReportsTheRefusal() {
        fill("a", 60);

        assertRefusedAtSixtyOfHundred(failureOf(engine.putVersionedDisplacing(key("b"), new byte[60], ++version, 0L, 0L, 0L, false)));
        assertThat(engine.getEntry(key("b")).await().or(Option.<KeyValue>none()).isPresent()).as("nothing stored").isFalse();

        assertThat(engine.putVersionedDisplacing(key("b"), new byte[60], ++version, 0L, 0L, 0L, true).await().isSuccess()).isTrue();
        assertThat(engine.usedBytes("stream-segments")).as("a repair is accounted past the cap").isEqualTo(120L);
    }

    @Test
    void putReplica_inBothForms_aboveTheCap_isStoredAndCounted() {
        fill("a", 100);

        assertThat(engine.putReplica(key("r1"), new byte[30], ++version, 0L, 0L, 0L).await().or(false)).isTrue();
        assertThat(engine.putReplica(new KeyValue(key("r2"), new byte[20], ++version, 0L, 0L, 0L), true).await().or(false)).isTrue();

        assertThat(engine.usedBytes("stream-segments")).isEqualTo(150L);
    }

    /// Over the cap (repairs put it there), a client write that adds nothing or frees bytes is accepted, and one the version
    /// check rejects reports "superseded", not "full".
    @Test
    void aboveTheCap_aClientWriteThatAddsNothing_isNotRefused() {
        fill("a", 100);
        engine.putVersionedDisplacing(key("b"), new byte[60], ++version, 0L, 0L, 0L, true).await();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(160L);

        assertThat(engine.putVersioned(key("b"), new byte[60], ++version).await().or(false)).as("same size, newer version").isTrue();
        assertThat(engine.putVersioned(key("b"), new byte[10], ++version).await().or(false)).as("shrinking").isTrue();
        assertThat(engine.putVersioned(key("b"), new byte[10], BASE - 10).await().isSuccess()).as("an older version is superseded, not refused")
                                                                                                .isTrue();
        assertThat(engine.putVersioned(key("b"), new byte[10], BASE - 10).await().or(true)).isFalse();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(110L);
    }

    /// A refused put stores nothing and moves nothing: the owner-epoch high water in particular.
    @Test
    void aRefusedPut_doesNotAdvanceTheEpochHighWater() {
        var advances = new CopyOnWriteArrayList<String>();
        var gate = new OwnerEpochGate() {
            @Override
            public boolean isStale(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
                return false;
            }

            @Override
            public void advance(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
                advances.add(new String(key, StandardCharsets.UTF_8));
            }
        };
        var gated = memoryStorageEngine(gate, DhtPrefixAccounting.dhtPrefixAccounting(CAP));

        gated.putVersioned(key("a"), new byte[60], ++version, 1L, 1L, 1L).await();
        gated.putVersioned(key("b"), new byte[60], ++version, 1L, 1L, 2L).await();

        assertThat(advances).as("only the accepted put advanced it").containsExactly(SEG + "a");
    }

    /// A restore may put back more live bytes than the cap leaves room for: it is not a client write, and the counters follow.
    @Test
    void aRestoreAboveTheCap_isCounted() {
        var tombstoneVersion = BASE + 500;

        fill("a", 60);
        engine.removeVersioned(key("a"), tombstoneVersion, 0L, 0L, 0L).await();
        fill("b", 100);

        var restored = engine.restoreIfExactly(key("a"), tombstoneVersion, 0L, 0L, 0L,
                                               Option.some(new KeyValue(key("a"), new byte[60], BASE, 0L, 0L, 0L))).await();

        assertThat(restored.or(false)).isTrue();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(160L);
        assertThat(engine.tombstoneBytes("stream-segments")).isZero();
    }

    @Test
    void aWriteOverATombstone_underTheCap_releasesTheTombstoneBytes() {
        fill("a", 10);
        engine.removeVersioned(key("a"), ++version, 0L, 0L, 0L).await();
        assertThat(engine.tombstoneBytes("stream-segments")).isPositive();

        fill("a", 10);

        assertThat(engine.tombstoneBytes("stream-segments")).isZero();
        assertThat(engine.usedBytes("stream-segments")).isEqualTo(10L);
    }

    @Test
    void dropPartitionAndCollectTombstones_reportHowManyEntriesTheyTook() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        var keys = new ArrayList<byte[]>();

        for (var i = 0; i < 5; i++) {
            keys.add(key("k" + i));
            fill("k" + i, 5);
        }
        for (var i = 0; i < 2; i++) {
            engine.removeVersioned(keys.get(i), ++version, 0L, 0L, 0L).await();
        }

        var collected = keys.stream()
                            .map(ring::partitionFor)
                            .distinct()
                            .mapToInt(partition -> engine.collectTombstones(ring, partition, Long.MAX_VALUE).await().or(0))
                            .sum();

        assertThat(collected).isEqualTo(2);

        var dropped = keys.stream()
                          .map(ring::partitionFor)
                          .distinct()
                          .mapToInt(partition -> engine.dropPartition(ring, partition).await().or(0))
                          .sum();

        assertThat(dropped).isEqualTo(3);
        assertThat(engine.usedBytes("stream-segments")).isZero();
    }

    @Test
    void remove_reportsWhetherTheKeyWasThere() {
        fill("a", 10);

        assertThat(engine.remove(key("a")).await().or(false)).isTrue();
        assertThat(engine.remove(key("a")).await().or(true)).isFalse();
    }

    @Test
    void clearAndShutdown_zeroTheCounters_tombstonesIncluded() {
        for (var path : List.of("clear", "shutdown")) {
            fill("live-" + path, 10);
            fill("dead-" + path, 10);
            engine.removeVersioned(key("dead-" + path), ++version, 0L, 0L, 0L).await();

            if (path.equals("clear")) {
                engine.clear().await();
            } else {
                engine.shutdown().await();
            }

            assertThat(engine.usedBytes("stream-segments")).as(path).isZero();
            assertThat(engine.tombstoneBytes("stream-segments")).as(path).isZero();
        }
    }

    @Test
    void theEngineHandsItsListenerToTheAccounting() {
        var events = new ArrayList<String>();

        engine.capacityListener(new DhtCapacityListener() {
            @Override
            public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                events.add("nearFull");
            }

            @Override
            public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {}

            @Override
            public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {}

            @Override
            public void fullCleared(String prefix, long usedBytes, long maxBytes) {}
        });
        fill("a", 90);

        assertThat(events).containsExactly("nearFull");
    }

    @Test
    void anEngineWithoutACap_isUnboundedAndReadsZeroForAnUnknownPrefix() {
        var plain = memoryStorageEngine();

        assertThat(plain.maxBytes("stream-segments")).isEqualTo(Long.MAX_VALUE);
        assertThat(plain.putVersioned(key("big"), new byte[10_000], BASE).await().or(false)).isTrue();
        assertThat(plain.usedBytes("no-such-prefix")).isZero();
        assertThat(plain.tombstoneBytes("no-such-prefix")).isZero();
    }

    /// A key that is exactly `prefix/` belongs to the prefix.
    @Test
    void aKeyEqualToThePrefixAndSeparator_countsInThePrefix() {
        engine.put(SEG.getBytes(StandardCharsets.UTF_8), new byte[5]).await();

        assertThat(engine.usedBytes("stream-segments")).isEqualTo(5L);
        assertThat(engine.usedBytes("other")).isZero();
    }
}
