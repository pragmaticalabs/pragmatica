// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.segment.RefDurability;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.GarbageCollectorConfig;
import org.pragmatica.storage.StorageGarbageCollector;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// PROBE (#2258 / #802), test-only, never merged. Question: do sealed segments of `system:cluster-events`
/// that were written through to the DHT tier of the `streams` storage instance get freed after retention
/// expires and storage GC runs, or does their DHT entry stay forever?
///
/// Reaches: [OffHeapRingBuffer] eviction -> [SegmentSealer] -> [StorageSegmentSink] -> the real `streams`
/// instance built by [StorageFactory#defaultStreamStorage] (memory + disk + `DhtStorageTier`, WRITE_THROUGH)
/// -> [RetentionEnforcer] (production overload, started on its public schedule) -> the production
/// [StorageGarbageCollector] class. Stubbed: only the DHT cluster behind `DHTClient` (an in-memory map that
/// counts `remove` calls). Test seams, both stated: the retention window is a constructor argument (1 hour,
/// events stamped 2 hours old), and the collector is built over the setup's own instance and metadata store
/// with a 1 ms grace period instead of the production 1 h (grace gates WHEN, never WHICH tier).
///
/// The assertion that goes RED under reading R2 is `dhtEntriesAfterGc == dhtBaseline`. Controls: the same
/// probe with no DHT tier (disk blocks return to baseline: green), and, inside the DHT arm, disk blocks
/// return to baseline while the DHT entries do not, so the red cannot come from a collector that never ran.
class SystemStreamDhtReclaimProbeTest {
    private static final String STREAM = "system:cluster-events";
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final int EVENTS = 40;
    private static final int RING_CAPACITY = 8;
    private static final long WAIT_MS = 15_000L;

    @TempDir
    Path dir;

    @RepeatedTest(3)
    void dhtArm_sealedSegments_areFreedAfterRetentionAndGc() {
        var dht = new CountingDHTClient();
        var result = probe(Option.some(dht), dht);

        System.out.println("PROBE-RESULT arm=dht " + result);
        assertThat(result.segmentsSealed()).as("probe reached the seal path: " + result).isGreaterThan(0);
        assertThat(result.dhtAfterSeal()).as("probe wrote through to the DHT tier: " + result).isGreaterThan(result.dhtBaseline());
        assertThat(result.segmentsAfterRetention()).as("retention dropped every ref: " + result).isZero();
        assertThat(result.diskAfterGc()).as("control: private disk tier freed by GC: " + result).isEqualTo(result.diskBaseline() + result.liveRefs());
        assertThat(result.sealedDhtKeysSurviving()).as("R2 assertion: no sealed-segment DHT entry survives: " + result).isZero();
        assertThat(result.dhtAfterGc()).as("R2 assertion: DHT entries back to baseline plus live refs: " + result).isEqualTo(result.dhtBaseline() + result.liveRefs());
    }

    @RepeatedTest(3)
    void controlArm_noDhtTier_sealedSegmentsAreFreedAfterRetentionAndGc() {
        var result = probe(Option.none(), new CountingDHTClient());

        System.out.println("PROBE-RESULT arm=no-dht " + result);
        assertThat(result.segmentsSealed()).as("probe reached the seal path: " + result).isGreaterThan(0);
        assertThat(result.diskAfterSeal()).as("probe wrote blocks to the disk tier: " + result).isGreaterThan(result.diskBaseline());
        assertThat(result.segmentsAfterRetention()).as("retention dropped every ref: " + result).isZero();
        assertThat(result.diskAfterGc()).as("disk blocks back to baseline plus live refs: " + result).isEqualTo(result.diskBaseline() + result.liveRefs());
    }

    private Result probe(Option<DHTClient> client, CountingDHTClient dht) {
        var setup = StorageFactory.defaultStreamStorage(client, dir, "p2258").unwrap();

        setup.dhtMarkerCheck()
             .onPresent(check -> StorageFactory.verifyDhtMarker(dht, check)
                                               .await()
                                               .onFailure(cause -> fail("admitting the streams DHT tier failed: " + cause.message())));

        var storage = setup.instance();
        var index = new SegmentIndex();
        var dhtBaseline = dht.size();
        var diskBaseline = diskBlocks();
        var sink = StorageSegmentSink.storageSegmentSink(storage, index, RefDurability.snapshotted(setup.snapshotManager()));
        var sealer = SegmentSealer.segmentSealer(sink, 64L * 1024 * 1024);
        var eventTime = System.currentTimeMillis() - 2 * WINDOW_MS;

        try (var ring = OffHeapRingBuffer.offHeapRingBuffer(STREAM, 0, RING_CAPACITY, 1 << 20, sealer)) {
            for (var i = 0; i < EVENTS; i++) {
                ring.append(payload(i), eventTime + i).onFailure(cause -> fail("append failed: " + cause.message()));
            }
        }

        awaitTrue(() -> sealer.pendingBytes() == 0 && !index.listSegments(STREAM, 0).isEmpty());

        var segmentsSealed = index.listSegments(STREAM, 0).size();
        var dhtAfterSeal = dht.size();
        var sealedDhtKeys = dht.keys();
        var diskAfterSeal = diskBlocks();
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           WINDOW_MS,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           setup.snapshotManager(),
                                                           setup.garbageCollector(),
                                                           SegmentReader.segmentReader(storage, index),
                                                           SegmentTierPressure.localDiskOf(storage));

        enforcer.start(TimeSpan.timeSpan(50).millis());
        awaitTrue(() -> index.listSegments(STREAM, 0).isEmpty());
        enforcer.close();

        var segmentsAfterRetention = index.listSegments(STREAM, 0).size();
        var collector = StorageGarbageCollector.storageGarbageCollector(storage,
                                                                        setup.metadataStore(),
                                                                        GarbageCollectorConfig.garbageCollectorConfig(1, 500));

        collector.activate();
        sleep(20);
        awaitTrue(() -> collectOnce(collector) == 0 && diskBlocks() == diskBaseline);

        var survivors = (int) sealedDhtKeys.stream().filter(dht.keys()::contains).count();

        return new Result(segmentsSealed,
                          dhtBaseline,
                          dhtAfterSeal,
                          dht.size(),
                          diskBaseline,
                          diskAfterSeal,
                          diskBlocks(),
                          segmentsAfterRetention,
                          dht.removes.get(),
                          setup.metadataStore().listAllRefs().size(),
                          survivors);
    }

    private static int collectOnce(StorageGarbageCollector collector) {
        return collector.collectGarbage();
    }

    private static byte[] payload(int i) {
        return ("{\"event\":" + i + ",\"pad\":\"" + "x".repeat(200) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /// Block files on the node-private disk tier: files named by a 64-hex block id under `segments/`.
    private long diskBlocks() {
        var root = dir.resolve("segments");

        if (!Files.isDirectory(root)) {
            return 0;
        }

        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}"))
                        .count();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(25);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Result(int segmentsSealed,
                          int dhtBaseline,
                          int dhtAfterSeal,
                          int dhtAfterGc,
                          long diskBaseline,
                          long diskAfterSeal,
                          long diskAfterGc,
                          int segmentsAfterRetention,
                          int dhtRemoveCalls,
                          int liveRefs,
                          int sealedDhtKeysSurviving) {}

    private static final class CountingDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
        final AtomicInteger removes = new AtomicInteger();

        int size() {
            return store.size();
        }

        java.util.Set<String> keys() {
            return java.util.Set.copyOf(store.keySet());
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));

            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            removes.incrementAndGet();

            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
