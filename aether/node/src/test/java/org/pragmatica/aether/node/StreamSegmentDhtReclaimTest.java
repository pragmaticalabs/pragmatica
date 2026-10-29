// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.SharedDelete;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteLimits;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// NT-120 (#2258, streams spec 3.2 item 6) on the production `streams` construction ([StorageFactory#defaultStreamStorage]
/// with a DHT tier): after a retention pass and one shared-tier collection run, the expired segments' blocks are gone from
/// the DHT, from memory and from disk -- the shared copy is no longer kept for good (measured at 1.0000 of the bytes before,
/// 1b-growth S1b-G1) -- while a segment inside its retention keeps every copy. The run collects more than the 500 blocks the
/// periodic tick takes (MT-60).
class StreamSegmentDhtReclaimTest {
    private static final long HOUR = 3_600_000L;
    private static final int EXPIRED = 600;
    private static final SharedDeleteLimits NO_GRACE = new SharedDeleteLimits(0L, 8, 10_000, 60_000L);

    @TempDir
    Path dir;

    @Test
    void expiredSegments_leaveTheDht_andEveryTier_afterAPassAndACollectionRun() {
        var dht = new InMemoryDHTClient();
        var setup = StorageFactory.defaultStreamStorage(Option.some(dht), dir, "test-node").unwrap();

        setup.dhtMarkerCheck()
             .onPresent(check -> StorageFactory.verifyDhtMarker(dht, check)
                                               .await()
                                               .onFailure(cause -> fail("admitting the streams DHT tier failed: " + cause.message())));

        var storage = setup.instance();
        var index = new SegmentIndex();
        var sink = StorageSegmentSink.storageSegmentSink(storage, index);
        var old = System.currentTimeMillis() - 2 * HOUR;
        var expired = new ArrayList<BlockId>();

        for (var offset = 0; offset < EXPIRED; offset++) {
            sink.seal(SealedSegment.sealedSegment("orders", 0, offset, offset, 1, old, old, event(offset, old))).await().unwrap();
            expired.add(storage.resolveRef("streams/orders/0/" + offset + "-" + offset).unwrap());
        }

        var now = System.currentTimeMillis();

        sink.seal(SealedSegment.sealedSegment("orders", 0, EXPIRED, EXPIRED, 1, now, now, event(EXPIRED, now))).await().unwrap();

        var young = storage.resolveRef("streams/orders/0/" + EXPIRED + "-" + EXPIRED).unwrap();

        assertThat(expired).allSatisfy(id -> assertThat(dht.holds(id)).as("control: every segment reached the DHT").isTrue());

        var hourly = RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, HOUR).withDeclared(RetentionDimensions.AGE);
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           _ -> Option.some(hourly),
                                                           24 * HOUR,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           setup.snapshotManager(),
                                                           setup.garbageCollector(),
                                                           SegmentReader.segmentReader(storage, index),
                                                           SegmentTierPressure.NONE);

        assertThat(pass(enforcer)).isEqualTo(EXPIRED);

        var run = setup.garbageCollector().collectSharedDeletes(setup.snapshotManager(), NO_GRACE);

        assertThat(run.deleted()).as("one run, past the tick's 500-block batch").isEqualTo(EXPIRED);
        assertThat(expired).allSatisfy(id -> {
            assertThat(dht.holds(id)).as("gone from the DHT").isFalse();
            assertThat(storage.exists(id).await().unwrap()).as("gone from every tier").isFalse();
        });
        assertThat(dht.holds(young)).as("a segment inside its retention keeps its DHT copy").isTrue();
        assertThat(storage.get(young).await().unwrap().isPresent()).isTrue();
    }

    /// The production construction gives the instance its identification predicate: a segment released without knowing its
    /// mark (after a restart, or by a destroy) leaves the DHT when its block carries the identity header, and a block that
    /// does not keeps its DHT copy.
    @Test
    void aReleaseWithoutKnowingTheMark_followsTheIdentityHeader_onTheProductionConstruction() {
        var dht = new InMemoryDHTClient();
        var setup = StorageFactory.defaultStreamStorage(Option.some(dht), dir, "test-node").unwrap();

        setup.dhtMarkerCheck()
             .onPresent(check -> StorageFactory.verifyDhtMarker(dht, check)
                                               .await()
                                               .onFailure(cause -> fail("admitting the streams DHT tier failed: " + cause.message())));

        var storage = setup.instance();
        var now = System.currentTimeMillis();

        StorageSegmentSink.storageSegmentSink(storage, new SegmentIndex())
                          .seal(SealedSegment.sealedSegment("orders", 0, 0, 0, 1, now, now, event(0, now)))
                          .await()
                          .unwrap();
        storage.putRef("streams/orders/0/1-1", event(1, now)).await().unwrap();

        var identified = storage.resolveRef("streams/orders/0/0-0").unwrap();
        var legacy = storage.resolveRef("streams/orders/0/1-1").unwrap();

        storage.deleteRef("streams/orders/0/0-0", SharedDelete.IF_IDENTIFIED).await();
        storage.deleteRef("streams/orders/0/1-1", SharedDelete.IF_IDENTIFIED).await();
        setup.garbageCollector().collectSharedDeletes(setup.snapshotManager(), NO_GRACE);

        assertThat(dht.holds(identified)).as("the identity header: gone from the DHT").isFalse();
        assertThat(dht.holds(legacy)).as("no header: its DHT copy stays").isTrue();
    }

    private static int pass(RetentionEnforcer enforcer) {
        try {
            var method = RetentionEnforcer.class.getDeclaredMethod("enforceNow");

            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            var pass = (Promise<Integer>) method.invoke(enforcer);

            return pass.await().fold(_ -> -1, removed -> removed);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("RetentionEnforcer.enforceNow is not reachable: " + e, e);
        }
    }

    private static byte[] event(long offset, long eventTime) {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) 7).array();
    }

    /// In-memory `DHTClient` backed by a map, as `StorageMaintenanceWiringTest`'s.
    private static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        boolean holds(BlockId id) {
            return store.keySet().stream().anyMatch(key -> key.endsWith(id.hexString()));
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
