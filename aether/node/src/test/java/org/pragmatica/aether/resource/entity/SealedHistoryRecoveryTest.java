// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1240: a durable entity partition whose checkpoint lags further behind than its ring reaches must be
/// rebuilt from the history this node sealed to storage, not refused. Reads cross from sealed segments
/// into the ring at the ring's earliest offset with no gap and no duplicate; a sealed history that is
/// itself broken — an offset skipped, an offset repeated, a hole between segments — is refused, never
/// folded around.
///
/// Lives in the entity package so it can drive the package-private [EntityFold] against the REAL node
/// substrate, partition manager, sealer and segment storage — the whole recovery path, stubbing nothing
/// but consensus KV (which holds no checkpoint here, deliberately).
class SealedHistoryRecoveryTest {
    private static final String KEYSPACE = "orders";
    private static final String STREAM = EntityPartitionArc.arcName(KEYSPACE);
    private static final int PARTITION = 0;
    /// More than the entity ring's 10,000-record capacity, so the oldest records leave the ring.
    private static final int RECORDS = 12_000;
    private static final int READ_BATCH = 512;
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final long MANAGER_BUDGET_BYTES = 512L * 1024 * 1024;
    private static final long SEAL_WAIT_MILLIS = 10_000;
    /// Well inside the ~2 s in-place retry window of the substrate's sealed read.
    private static final long GATE_OPENS_INSIDE_WINDOW_MILLIS = 300;

    private StorageInstance storage;
    private SegmentIndex index;

    @BeforeEach
    void setUpStorage() {
        storage = StorageInstance.storageInstance("entity-sealed-history",
                                                  List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
    }

    /// The production shape: the global segment sealer takes every record the ring evicts and seals it to
    /// storage; no checkpoint was ever written.
    @Nested
    class RecoveryFromSealedHistory {
        private SegmentSealer sealer;
        private StreamPartitionManager partitionManager;
        private EntityLogSubstrate substrate;

        @BeforeEach
        void setUp() throws InterruptedException {
            sealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(storage, index));
            partitionManager = StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                             sealer,
                                                                             Option.none(),
                                                                             index::lastSealedOffset);
            substrate = substrate(partitionManager, sealer);
            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            awaitAllSealed(sealer);
        }

        @Test
        void ready_rebuildsEveryKeyFromSealedHistory_whenHistoryHasLeftTheRingAndNoCheckpointExists() {
            assertThat(partitionManager.earliestRetainedOffset(STREAM, PARTITION)).as("control: the oldest records must have left the ring, or this test never reaches sealed storage")
                      .isGreaterThan(0L);
            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            fold.ready(PARTITION)
                .await()
                .onFailure(cause -> fail("rebuild must succeed from sealed history: " + cause.message()));
            assertThat(missingKeys(fold)).as("every key written must be in the rebuilt fold with its value").isZero();
        }

        @Test
        void read_returnsEveryOffsetExactlyOnce_acrossTheSealedToRingBoundary() {
            var ringEarliest = partitionManager.earliestRetainedOffset(STREAM, PARTITION);
            var keys = readAllKeys(substrate, substrate.headOffset(KEYSPACE, PARTITION));

            assertThat(ringEarliest).as("control: the read must actually cross a boundary").isGreaterThan(0L);
            assertThat(keys).containsExactlyElementsOf(LongStream.range(0, RECORDS)
                                                                 .mapToObj(SealedHistoryRecoveryTest::key)
                                                                 .toList());
        }

        @Test
        void earliestRetainedOffset_reachesBackIntoSealedHistory() {
            assertThat(substrate.earliestRetainedOffset(KEYSPACE, PARTITION)).isZero();
        }
    }

    /// The three paths the #1332 review found unpinned: the eviction-race reroute, the reclaimed-offset
    /// mapping, and the takeover refusal. Each is RED under the mutation named on it.
    @Nested
    class ReviewPins {
        /// An upper bound, not a target: the appender runs until the READER stops it, so the two overlap
        /// however fast either side turns out to be.
        private static final long CONCURRENT_APPENDS_CAP = 2_000_000;
        private static final long BOUNDARY_TARGET_READS = 500;
        private static final int BOUNDARY_BATCH = 64;
        private static final long RECLAIM_AT_LEAST = 500;
        /// The loop stops with the appender or at this bound, whichever comes first, and a read that waits
        /// out an in-flight seal costs about 2 s — so a read count is not a time budget.
        private static final long BOUNDARY_LOOP_SECONDS = 20;
        /// Deliberately low: a boundary read that waits out an in-flight seal costs about 2 s, so the
        /// meaningful control is that the ring EVICTED under the reads, asserted separately below.
        private static final long BOUNDARY_MIN_READS = 3;

        /// MA: with `evictedDuringRead` no longer rerouting, a read at the ring's earliest offset surfaces
        /// `CursorExpired` the moment an append evicts under it.
        ///
        /// Offset ALIGNMENT is counted and reported but deliberately not asserted: under concurrent
        /// eviction the ring itself can answer one offset's slot with another's record (#1340, pre-existing
        /// and rc4-wide), and this test is about the reroute, not about that defect.
        @Test
        void read_staysAtTheBoundary_whileTheRingEvictsUnderIt() throws InterruptedException {
            var sealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(storage, index));
            var partitionManager = sealingManager(sealer);
            var substrate = substrate(partitionManager, sealer);

            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            var done = new AtomicBoolean(false);
            var earliestBefore = partitionManager.earliestRetainedOffset(STREAM, PARTITION);
            var appender = Thread.ofPlatform().start(() -> appendMore(substrate, done));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUNDARY_LOOP_SECONDS);
            var refusals = new StringBuilder();
            var reads = 0L;
            var transients = 0L;
            var misaligned = 0L;

            while (reads < BOUNDARY_TARGET_READS && !done.get() && System.nanoTime() < deadline && refusals.length() < 2_000) {
                var from = Math.max(0, partitionManager.earliestRetainedOffset(STREAM, PARTITION) - (reads % 2));
                var batch = substrate.read(KEYSPACE, PARTITION, from, BOUNDARY_BATCH).await();

                reads++;
                transients += batch.fold(cause -> recordUnlessTransient(refusals, from, cause), _ -> 0L);
                misaligned += batch.fold(_ -> 0L, records -> misalignedIn(records, from));
            }

            done.set(true);
            appender.join();
            System.out.println("boundary reads=" + reads
                              + " transients(in-flight seal)=" + transients
                              + " misaligned(ring torn, #1340)=" + misaligned);
            assertThat(reads).as("control: the boundary loop must have run").isGreaterThanOrEqualTo(BOUNDARY_MIN_READS);
            assertThat(partitionManager.earliestRetainedOffset(STREAM, PARTITION)).as("control: the ring must have evicted UNDER the reads, or the race was never exercised")
                      .isGreaterThan(earliestBefore);
            assertThat(refusals.toString()).as("a read at the boundary must reroute to sealed storage, never surface CursorExpired")
                      .isEmpty();
        }

        /// MC: with the `CursorExpired` arm of `sealedReadFailure` dropped, a read of an offset retention
        /// has reclaimed leaks the raw stream cause instead of refusing the fold.
        @Test
        void read_refusesAsFoldFailedNamingCursorExpired_whenRetentionReclaimedTheOffset() throws InterruptedException {
            var sealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(storage, index));
            var substrate = substrate(sealingManager(sealer), sealer);

            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            awaitAllSealed(sealer);
            reclaimPrefix();
            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).isTrue();
            read.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldFailed.class)
                                              .extracting(failed -> ((EntityLogError.FoldFailed) failed).reason())
                                              .isInstanceOf(StreamError.CursorExpired.class));
        }

        /// MD: a node that never sealed any of this partition has an EMPTY index, so its earliest readable
        /// offset stays at its ring and the fold refuses with the pre-#1240 gap message. A promoted REPLICA
        /// is the opposite case and is covered by the recovery tests: it sealed its own segments and reads
        /// them.
        @Test
        void ready_refusesWithThePreFixGapMessage_whenThisNodeSealedNoneOfThePartition() {
            var substrate = substrate(StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                                    EvictionListener.NOOP,
                                                                                    Option.none(),
                                                                                    index::lastSealedOffset),
                                      EvictionListener.NOOP);

            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            var ringEarliest = substrate.earliestRetainedOffset(KEYSPACE, PARTITION);

            assertThat(ringEarliest).as("control: history left the ring and nothing was sealed").isGreaterThan(0L);
            var result = EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("checkpoint resumes at 0 but the earliest readable offset here is " + ringEarliest));
        }

        /// An in-flight seal is the transient the design promises, so it is counted rather than failed;
        /// anything else at the boundary — `CursorExpired` above all — is the defect this pins.
        private static long recordUnlessTransient(StringBuilder refusals, long from, Cause cause) {
            if (cause instanceof EntityLogError.FoldInProgress) {
                return 1L;
            }

            refusals.append("read@").append(from).append(" failed: ").append(cause.message()).append('\n');

            return 0L;
        }

        private static long misalignedIn(List<byte[]> records, long from) {
            return IntStream.range(0,
                                   records.size())
                            .filter(position -> !key(from + position).equals(EntityLogRecord.decode(records.get(position))
                                                                                            .map(EntityLogRecord::key)
                                                                                            .or("<undecodable>")))
                            .count();
        }

        /// Appends until the reader stops it (or the cap is reached), so eviction is still running under
        /// every one of the reader's boundary reads.
        private static void appendMore(EntityLogSubstrate substrate, AtomicBoolean done) {
            LongStream.range(RECORDS, RECORDS + CONCURRENT_APPENDS_CAP)
                      .takeWhile(_ -> !done.get())
                      .forEach(offset -> substrate.append(KEYSPACE,
                                                          PARTITION,
                                                          record(offset))
                                                  .await()
                                                  .unwrap());
            done.set(true);
        }

        /// What `RetentionEnforcer` does below the checkpoint floor: drop the lowest sealed segments.
        private void reclaimPrefix() {
            var through = -1L;

            for (var ref : index.listSegments(STREAM, PARTITION)) {
                if (through >= RECLAIM_AT_LEAST) {
                    break;
                }

                index.removeSegment(STREAM, PARTITION, ref.startOffset());
                through = ref.endOffset();
            }

            assertThat(through).as("control: a sealed prefix was reclaimed").isGreaterThanOrEqualTo(RECLAIM_AT_LEAST);
        }
    }

    /// Evicted records whose seals are held back by a gated sink: the sealer retains them, and nothing
    /// reaches a segment until the gate opens.
    @Nested
    class SealInFlight {
        private GatedSink sink;
        private SegmentSealer sealer;
        private EntityLogSubstrate substrate;

        @BeforeEach
        void setUp() {
            sink = new GatedSink(StorageSegmentSink.storageSegmentSink(storage, index));
            sealer = SegmentSealer.segmentSealer(sink);
            substrate = substrate(StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                                sealer,
                                                                                Option.none(),
                                                                                index::lastSealedOffset),
                                  sealer);
            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
        }

        @Test
        void earliestRetainedOffset_countsHistoryStillInFlight() {
            assertThat(substrate.earliestRetainedOffset(KEYSPACE, PARTITION)).as("nothing is sealed yet; the in-flight history starts at 0")
                      .isZero();
        }

        @Test
        void ready_succeedsWithoutFoldInProgress_whenTheSealLandsInsideTheRetryWindow() throws InterruptedException {
            var opener = Thread.ofPlatform().start(() -> openAfter(sink, GATE_OPENS_INSIDE_WINDOW_MILLIS));
            var fold = EntityFold.entityFold(KEYSPACE, substrate);
            var result = fold.ready(PARTITION).await();

            opener.join();
            result.onFailure(cause -> fail("a seal landing inside the retry window must not surface: " + cause.message()));
            assertThat(missingKeys(fold)).isZero();
        }

        @Test
        void ready_failsFoldInProgress_whileTheSealPersists_andSucceedsOnReaccessOnceItLands() throws InterruptedException {
            var fold = EntityFold.entityFold(KEYSPACE, substrate);
            var first = fold.ready(PARTITION).await();

            assertThat(first.isFailure()).isTrue();
            first.onFailure(cause -> assertThat(cause).as("in flight is retried, never skipped and never refused")
                                               .isInstanceOf(EntityLogError.FoldInProgress.class));
            sink.open();
            awaitAllSealed(sealer);
            fold.ready(PARTITION)
                .await()
                .onFailure(cause -> fail("re-access after the seal landed must rebuild: " + cause.message()));
            assertThat(missingKeys(fold)).isZero();
        }

        @Test
        void read_failsFoldInProgress_whenTheEvictedOffsetIsStillBeingSealed() {
            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).isTrue();
            read.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldInProgress.class));
        }
    }

    /// Sealed history that is itself broken. The ring's evictions are dropped here, and the segments
    /// covering the evicted range are written by hand so each defect can be placed exactly.
    @Nested
    class BrokenSealedHistory {
        private StreamPartitionManager partitionManager;
        private EntityLogSubstrate substrate;
        private long ringEarliest;

        @BeforeEach
        void setUp() {
            partitionManager = StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                             EvictionListener.NOOP,
                                                                             Option.none(),
                                                                             index::lastSealedOffset);
            substrate = substrate(partitionManager, EvictionListener.NOOP);
            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            ringEarliest = partitionManager.earliestRetainedOffset(STREAM, PARTITION);
        }

        @Test
        void read_crossesIntoTheRing_whenTheSealedHistoryIsIntact() {
            sealOffsets(0,
                        ringEarliest - 1,
                        LongStream.range(0, ringEarliest).boxed().toList());
            assertThat(readAllKeys(substrate, substrate.headOffset(KEYSPACE, PARTITION))).containsExactlyElementsOf(LongStream.range(0,
                                                                                                                                     RECORDS)
                                                                                                                              .mapToObj(SealedHistoryRecoveryTest::key)
                                                                                                                              .toList());
        }

        @Test
        void read_refusesTheBatch_whenASealedSegmentSkipsAnOffset() {
            var skipped = new ArrayList<>(LongStream.range(0, ringEarliest).boxed().toList());

            skipped.remove(Long.valueOf(5));
            sealOffsets(0, ringEarliest - 1, skipped);
            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).as("a skipped offset must be refused, not folded as if the next record were it")
                      .isTrue();
            read.onFailure(cause -> assertThat(cause.message()).contains("expected offset 5, found 6"));
        }

        @Test
        void read_refusesTheBatch_whenASealedSegmentRepeatsAnOffset() {
            var repeated = new ArrayList<>(LongStream.range(0, ringEarliest).boxed().toList());

            repeated.add(6, 5L);
            sealOffsets(0, ringEarliest - 1, repeated);
            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).as("a repeated offset must be refused, not applied twice").isTrue();
            read.onFailure(cause -> assertThat(cause.message()).contains("expected offset 6, found 5"));
        }

        @Test
        void ready_refusesLoudly_whenTheSealedHistoryHasAHole() {
            sealOffsets(0,
                        99,
                        LongStream.range(0, 100).boxed().toList());
            sealOffsets(200,
                        ringEarliest - 1,
                        LongStream.range(200, ringEarliest).boxed().toList());
            var result = EntityFold.entityFold(KEYSPACE, substrate).ready(PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldFailed.class));
        }

        /// The ruled FORK 2 case: sealed history below, the ring above, and a hole between them. The read
        /// that reaches the hole is refused as a failed fold naming the missing range, never served.
        @Test
        void read_refusesLoudly_whenAHoleSitsBetweenTheLowestSealedOffsetAndTheRing() {
            sealOffsets(0,
                        99,
                        LongStream.range(0, 100).boxed().toList());
            sealOffsets(200,
                        ringEarliest - 1,
                        LongStream.range(200, ringEarliest).boxed().toList());
            assertThat(substrate.earliestRetainedOffset(KEYSPACE, PARTITION)).isZero();
            var read = substrate.read(KEYSPACE, PARTITION, 100, READ_BATCH).await();

            assertThat(read.isFailure()).isTrue();
            read.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldFailed.class)
                                              .extracting(Cause::message)
                                              .asString()
                                              .contains("[100, 200)"));
        }

        /// A segment claiming `[start, end]` whose CONTENT is the given offsets, in the given order.
        private void sealOffsets(long start, long end, List<Long> offsets) {
            StorageSegmentSink.storageSegmentSink(storage, index)
                              .seal(sealedSegment(STREAM,
                                                  PARTITION,
                                                  start,
                                                  end,
                                                  offsets.size(),
                                                  0L,
                                                  0L,
                                                  serialize(offsets)))
                              .await()
                              .unwrap();
        }
    }

    private EntityLogSubstrate substrate(StreamPartitionManager partitionManager, EvictionListener evictionListener) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(partitionManager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(1,
                                                                                                                                       true),
                                                                 TieredStreamReader.tieredStreamReader(index, storage),
                                                                 index,
                                                                 evictionListener,
                                                                 storage,
                                                                 emptyKvStore(),
                                                                 _ -> Promise.success(List.of()));
    }

    private static long missingKeys(EntityFold fold) {
        return LongStream.range(0, RECORDS)
                         .filter(offset -> !fold.get(PARTITION,
                                                     key(offset))
                                                .map(state -> new String(state, StandardCharsets.UTF_8))
                                                .map(value(offset)::equals)
                                                .or(false))
                         .count();
    }

    private static void openAfter(GatedSink sink, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        sink.open();
    }

    private static void appendRecords(EntityLogSubstrate substrate) {
        for (long offset = 0; offset < RECORDS; offset++) {
            substrate.append(KEYSPACE, PARTITION, record(offset)).await().unwrap();
        }
    }

    private StreamPartitionManager sealingManager(SegmentSealer sealer) {
        return StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                             sealer,
                                                             Option.none(),
                                                             index::lastSealedOffset);
    }

    private static void awaitAllSealed(SegmentSealer sealer) throws InterruptedException {
        var deadline = System.currentTimeMillis() + SEAL_WAIT_MILLIS;

        while (sealer.pendingBytes() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sealer.pendingBytes()).as("every evicted record sealed").isZero();
    }

    /// Read `[0, head]` the way a fold does — batch after batch from the offset after the last record
    /// returned — decoding each record's key, so a gap or a duplicate shows up as a misaligned key.
    private static List<String> readAllKeys(EntityLogSubstrate substrate, long head) {
        var keys = new ArrayList<String>();
        var from = 0L;

        while (from <= head) {
            var batch = substrate.read(KEYSPACE, PARTITION, from, READ_BATCH).await().unwrap();

            assertThat(batch).as("read at %d below head %d", from, head).isNotEmpty();
            batch.forEach(raw -> keys.add(EntityLogRecord.decode(raw).unwrap().key()));
            from += batch.size();
        }

        return keys;
    }

    private static byte[] record(long offset) {
        return EntityLogRecord.upsert(key(offset),
                                      value(offset).getBytes(StandardCharsets.UTF_8))
                              .encode();
    }

    private static String key(long offset) {
        return "k-" + offset;
    }

    private static String value(long offset) {
        return "v-" + offset;
    }

    /// Segment payload in the sealer's format: `[offset:8][timestamp:8][len:4][data:len]` per record.
    private static byte[] serialize(List<Long> offsets) {
        var records = offsets.stream().map(SealedHistoryRecoveryTest::record).toList();
        var size = records.stream().mapToInt(data -> Long.BYTES + Long.BYTES + Integer.BYTES + data.length).sum();
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < offsets.size(); i++) {
            buffer.putLong(offsets.get(i));
            buffer.putLong(0L);
            buffer.putInt(records.get(i).length);
            buffer.put(records.get(i));
        }

        return buffer.array();
    }

    /// Holds each seal until [#open], then passes it and every later one to the real sink. The sealer keeps
    /// one seal in flight per partition, so at most one is ever held.
    ///
    /// Every seal completes on its own thread, as a storage tier's would. The memory tier completes
    /// synchronously, and releasing a backlog into a synchronously completing sink overflows the sealer's
    /// drain recursion and wedges it — a #1297 defect reported separately, which these tests must not
    /// depend on.
    private static final class GatedSink implements SegmentSink {
        private final SegmentSink delegate;
        private final List<HeldSeal> held = new ArrayList<>();
        private boolean open;

        private GatedSink(SegmentSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized Promise<Unit> seal(SealedSegment segment) {
            var promise = Promise.<Unit> promise();

            if (open) {
                sealOffThread(new HeldSeal(segment, promise));
            } else {
                held.add(new HeldSeal(segment, promise));
            }

            return promise;
        }

        synchronized void open() {
            open = true;
            held.forEach(this::sealOffThread);
            held.clear();
        }

        private void sealOffThread(HeldSeal seal) {
            Thread.ofVirtual().start(() -> delegate.seal(seal.segment())
                                                   .onResult(seal.promise()::resolve));
        }

        private record HeldSeal(SealedSegment segment, Promise<Unit> promise) {}
    }

    private static KVStore<AetherKey, AetherValue> emptyKvStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
