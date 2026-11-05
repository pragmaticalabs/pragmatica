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

package org.pragmatica.dht.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// v-2509 probes (#2258): H3 (conservation under concurrency, with refusals, repair writes and tombstone cycles) and H7 (episodes).
class DhtCapacityProbesTest {
    private static final long CAP = 400;
    private static final int OVERHEAD = DhtPrefixAccounting.TOMBSTONE_OVERHEAD_BYTES;
    private static final long BASE = 1_000_000_000_000L;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Events(List<String> log) implements DhtCapacityListener {
        static Events events() {
            return new Events(new CopyOnWriteArrayList<>());
        }

        long count(String what) {
            return log.stream().filter(what::equals).count();
        }

        @Override
        public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
            log.add("nearFull");
        }

        @Override
        public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
            log.add("nearFullCleared");
        }

        @Override
        public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {
            log.add("full");
        }

        @Override
        public void fullCleared(String prefix, long usedBytes, long maxBytes) {
            log.add("fullCleared");
        }
    }

    /// H3, concurrent: eight threads run every path that changes an entry on shared keys of every bucket, with refusals happening
    /// (cap 400 bytes against ~40 keys of up to 60 bytes). After they join, each bucket's counters equal a recomputation over the map.
    @Test
    void everyPathUnderContention_keepsTheCountersEqualToARecomputation() throws Exception {
        for (var round = 0; round < 6; round++) {
            var accounting = DhtPrefixAccounting.dhtPrefixAccounting(CAP);

            accounting.register("orders-blocks");
            var engine = memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ring.addNode(new NodeId("n1"));
            var keys = keys();
            var clock = new AtomicLong(BASE);
            var start = new CountDownLatch(1);
            var failures = new CopyOnWriteArrayList<Throwable>();
            var threads = new ArrayList<Thread>();

            for (var t = 0; t < 8; t++) {
                var seed = round * 100L + t;

                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        var random = new Random(seed);

                        for (var step = 0; step < 6000; step++) {
                            step(engine, ring, keys, clock, random);
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }));
            }
            start.countDown();
            for (var thread : threads) {
                thread.join();
            }

            assertThat(failures).isEmpty();
            assertMatchesRecomputation(engine, "round " + round);
        }
    }

    private static void step(MemoryStorageEngine engine, ConsistentHashRing<NodeId> ring, List<byte[]> keys, AtomicLong clock, Random random) {
        var key = keys.get(random.nextInt(keys.size()));
        var version = random.nextInt(5) == 0
                      ? BASE - random.nextInt(20)
                      : clock.incrementAndGet();
        var value = new byte[1 + random.nextInt(60)];
        var partition = ring.partitionFor(key);

        switch (random.nextInt(16)) {
            case 0 -> engine.put(key, value).await();
            case 1, 2 -> engine.putVersioned(key, value, version, 0L, 0L, 0L, false).await();
            case 3 -> engine.putVersioned(key, value, version, 0L, 0L, 0L, true).await();
            case 4 -> engine.putVersionedDisplacing(key, value, version, 0L, 0L, 0L, false).await();
            case 5 -> engine.putVersionedDisplacing(key, value, version, 0L, 0L, 0L, true).await();
            case 6, 7 -> engine.removeVersioned(key, version, 0L, 0L, 0L).await();
            case 8 -> engine.removeVersionedDisplacing(key, version, 0L, 0L, 0L).await();
            case 9 -> engine.putReplica(key, value, version, 0L, 0L, 0L).await();
            case 10 -> engine.putReplica(new KeyValue(key, value, version, 0L, 0L, 0L), random.nextBoolean()).await();
            case 11 -> engine.putReplica(new KeyValue(key, new byte[0], version, 0L, 0L, 0L, true), random.nextBoolean()).await();
            case 12 -> engine.remove(key).await();
            case 13 -> engine.collectTombstones(ring, partition, Long.MAX_VALUE).await();
            case 14 -> engine.removeIfExactly(key, storedVersion(engine, key, version), 0L, 0L, 0L).await();
            default -> engine.restoreIfExactly(key, storedVersion(engine, key, version), 0L, 0L, 0L, prior(key, value, random)).await();
        }
        if (random.nextInt(2000) == 0) {
            engine.dropPartition(ring, partition).await();
        }
    }

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

    private static List<byte[]> keys() {
        var keys = new ArrayList<byte[]>();

        for (var i = 0; i < 10; i++) {
            keys.add(bytes("stream-segments/" + i));
            keys.add(bytes("artifacts/" + i));
            keys.add(bytes("orders-blocks/" + i));
            keys.add(bytes("cache:" + i));
        }

        return keys;
    }

    private static String bucketOf(byte[] key) {
        for (var prefix : List.of("stream-segments", "artifacts", "orders-blocks")) {
            if (new String(key, StandardCharsets.UTF_8).startsWith(prefix + "/")) {
                return prefix;
            }
        }

        return "other";
    }

    private static void assertMatchesRecomputation(MemoryStorageEngine engine, String after) {
        var live = new TreeMap<String, Long>();
        var tombstones = new TreeMap<String, Long>();

        engine.entries().await().or(List.of()).forEach(entry -> {
            var bucket = bucketOf(entry.key());

            if (entry.tombstone()) {
                tombstones.merge(bucket, (long) entry.key().length + OVERHEAD, Long::sum);
            } else {
                live.merge(bucket, (long) entry.value().length, Long::sum);
            }
        });
        for (var bucket : List.of("stream-segments", "artifacts", "orders-blocks", "other")) {
            assertThat(engine.usedBytes(bucket)).as("live bytes of %s after %s", bucket, after).isEqualTo(live.getOrDefault(bucket, 0L));
            assertThat(engine.tombstoneBytes(bucket)).as("tombstone bytes of %s after %s", bucket, after)
                                                     .isEqualTo(tombstones.getOrDefault(bucket, 0L));
        }
    }

    /// H7: a refusal episode is one FULL, closed by one CLEARED. A put LARGER than the room left, at 50% occupancy, is refused: at the
    /// head the FULL is raised and cleared inside the same call (live is below 85%), so every further refused attempt of the same
    /// block raises a new pair, with no byte freed in between.
    @Test
    void refusedAttemptsWithNoByteFreedInBetween_areOneEpisode_notOnePairPerAttempt() {
        var engine = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(100));
        var events = Events.events();

        engine.capacityListener(events);
        engine.put(bytes("stream-segments/held"), new byte[50]).await();
        for (var attempt = 0; attempt < 5; attempt++) {
            assertThat(engine.put(bytes("stream-segments/big"), new byte[60]).await().isFailure()).isTrue();
        }

        assertThat(events.count("full")).as("FULL raised once for five refusals with nothing freed (events %s)", events.log()).isEqualTo(1L);
        assertThat(events.count("fullCleared")).as("no CLEARED without a recovery (events %s)", events.log()).isZero();
    }

    /// H7, order: under contention the transitions are decided by a compare-and-set but REPORTED after it, outside any lock, so a CLEARED
    /// can overtake the NEAR_FULL it closes. Seen by the listener, the events of one prefix must alternate raise, clear, raise, clear.
    @Test
    void nearFullTransitions_underContention_areReportedInAlternation() throws Exception {
        var misordered = 0;

        for (var round = 0; round < 20; round++) {
            var engine = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(1000));
            var events = Events.events();
            var start = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();

            engine.capacityListener(events);
            engine.put(bytes("stream-segments/base"), new byte[800]).await();
            for (var t = 0; t < 6; t++) {
                var mine = bytes("stream-segments/t" + t);

                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (var i = 0; i < 20000; i++) {
                        engine.put(mine, new byte[i % 2 == 0 ? 60 : 1]).await();
                    }
                }));
            }
            start.countDown();
            for (var thread : threads) {
                thread.join();
            }
            var nearFull = false;

            for (var event : events.log()) {
                if (event.equals("nearFull")) {
                    if (nearFull) {
                        misordered++;
                    }
                    nearFull = true;
                } else if (event.equals("nearFullCleared")) {
                    if (!nearFull) {
                        misordered++;
                    }
                    nearFull = false;
                }
            }
        }

        assertThat(misordered).as("NEAR_FULL / CLEARED reports out of order across 20 contended rounds").isZero();
    }
}
