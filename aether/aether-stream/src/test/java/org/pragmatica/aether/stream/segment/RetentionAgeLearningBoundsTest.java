// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1616 R2/R3: learning the age of pre-restart segments reads whole blocks into heap, so it is bounded, a pass
/// never runs twice at once, and a block that cannot be read is read once per process, not once per pass.
class RetentionAgeLearningBoundsTest {
    private static final int SEGMENTS = 400;
    private static final long WINDOW_MS = 60 * 60 * 1000L;

    /// The verifier's P7: 400 pre-restart segments peaked at 400 concurrent block reads in one pass.
    @Test
    void aPassOverManyUnknownAges_keepsAtMostTheBoundInFlight_andLearnsThemAll() {
        var tier = new CountingTier();
        var setup = restartedWith(tier, SEGMENTS);

        setup.enforcer().enforceNow().await();

        assertThat(tier.peak.get()).as("peak in-flight block reads").isLessThanOrEqualTo(RetentionEnforcer.AGE_READ_CONCURRENCY);
        assertThat(setup.index().listSegments("orders", 0)).hasSize(SEGMENTS)
                                                         .allSatisfy(ref -> assertThat(ref.maxTimestamp()).isPositive());
    }

    /// Fixed-rate ticks can overlap a long pass: the second call joins the running pass instead of starting its
    /// own. Counted by passes, not by block reads -- the storage instance already coalesces concurrent reads of
    /// one block, so read counts cannot tell one pass from two. Each pass under pressure runs the pressure relief
    /// exactly once, so the relief count IS the pass count.
    @Test
    void overlappingPasses_runOnce() {
        var tier = new CountingTier();
        var setup = restartedWith(tier, SEGMENTS);
        var passes = new AtomicInteger();
        var enforcer = RetentionEnforcer.retentionEnforcer(setup.storage(),
                                                           setup.index(),
                                                           WINDOW_MS,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           SegmentReader.segmentReader(setup.storage(), setup.index()),
                                                           () -> 0.9,
                                                           passes::incrementAndGet);
        var first = enforcer.enforceNow();
        var second = enforcer.enforceNow();

        first.await();
        second.await();
        assertThat(passes.get()).as("one pass ran; the overlapping call joined it").isEqualTo(1);
    }

    /// A block that cannot be read (a missing key, a damaged block) is remembered: the next pass does not read
    /// it again, and it stays withheld rather than aging out.
    @Test
    void anUnreadableBlock_isReadOnce_andStaysWithheld() {
        var tier = new CountingTier();
        var storage = StorageInstance.storageInstance("age", List.of(tier), MetadataStore.inMemoryMetadataStore("age"));
        var missing = BlockId.blockId(new byte[]{42}).unwrap();

        storage.createRef("streams/orders/0/0-2", missing).await();

        var index = new SegmentIndex();

        index.rebuildFromRefs(storage.resolveRef("streams/orders/0/0-2")
                                     .map(id -> Map.of("streams/orders/0/0-2", id))
                                     .unwrap());
        var enforcer = enforcer(storage, index);

        enforcer.enforceNow().await();
        enforcer.enforceNow().await();

        assertThat(tier.reads.get()).as("read on the first pass only").isEqualTo(1);
        assertThat(storage.resolveRef("streams/orders/0/0-2").isPresent()).as("withheld, not aged out").isTrue();
    }

    /// #1616 R3, narrowed: a TRANSIENT read failure (an I/O error, a timeout) is not remembered -- the next pass
    /// retries and learns the age, so one bad read cannot pin a segment against age-out until a restart.
    @Test
    void aTransientReadFailure_isRetriedOnTheNextPass() {
        var tier = new CountingTier();
        var setup = restartedWith(tier, 1);

        tier.failNextReads.set(1);
        setup.enforcer().enforceNow().await();
        assertThat(setup.index().listSegments("orders", 0)).singleElement()
                                                         .extracting(SegmentIndex.SegmentRef::maxTimestamp)
                                                         .as("unknown after the failed read")
                                                         .isEqualTo(0L);

        setup.enforcer().enforceNow().await();
        assertThat(setup.index().listSegments("orders", 0)).singleElement()
                                                         .extracting(SegmentIndex.SegmentRef::maxTimestamp)
                                                         .as("learned on the retry")
                                                         .satisfies(timestamp -> assertThat(timestamp).isPositive());
    }

    /// #1616 R2d: a pass frees its slot BEFORE resuming the callers waiting on it, so a waiter that immediately
    /// asks for another pass gets a new one rather than the result of the pass that just ended. The waiter here
    /// is a JOINER: it holds the pass's own promise, whose continuations run as it resolves.
    @Test
    void aWaitersImmediateCall_startsANewPass() {
        var tier = new CountingTier();
        var setup = restartedWith(tier, 16);
        var passes = new AtomicInteger();
        var enforcer = RetentionEnforcer.retentionEnforcer(setup.storage(),
                                                           setup.index(),
                                                           WINDOW_MS,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           SegmentReader.segmentReader(setup.storage(), setup.index()),
                                                           () -> 0.9,
                                                           passes::incrementAndGet);

        var first = enforcer.enforceNow();
        var joined = enforcer.enforceNow();

        joined.flatMap(_ -> enforcer.enforceNow())
              .await();
        first.await();

        assertThat(passes.get()).as("the joiner's follow-up call ran a pass of its own").isEqualTo(2);
    }

    private static Setup restartedWith(CountingTier tier, int segments) {
        var storage = StorageInstance.storageInstance("age", List.of(tier), MetadataStore.inMemoryMetadataStore("age"));
        var sink = storageSegmentSink(storage, new SegmentIndex());
        var eventTime = System.currentTimeMillis() - WINDOW_MS / 2;

        for (var offset = 0; offset < segments; offset++) {
            sink.seal(sealedSegment("orders", 0, offset, offset, 1, eventTime, eventTime, encoded(offset, eventTime))).await();
        }

        tier.reads.set(0);

        var index = new SegmentIndex();

        index.rebuildFromRefs(allRefs(storage, segments));

        return new Setup(storage, index, enforcer(storage, index));
    }

    private static Map<String, BlockId> allRefs(StorageInstance storage, int segments) {
        var refs = new HashMap<String, BlockId>();

        for (var offset = 0; offset < segments; offset++) {
            var name = "streams/orders/0/" + offset + "-" + offset;

            storage.resolveRef(name).onPresent(id -> refs.put(name, id));
        }

        return refs;
    }

    private static RetentionEnforcer enforcer(StorageInstance storage, SegmentIndex index) {
        return RetentionEnforcer.retentionEnforcer(storage,
                                                   index,
                                                   WINDOW_MS,
                                                   RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                   SegmentReader.segmentReader(storage, index));
    }

    private static byte[] encoded(long offset, long eventTime) {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) offset).array();
    }

    private record Setup(StorageInstance storage, SegmentIndex index, RetentionEnforcer enforcer) {}

    /// A memory tier whose reads take a moment and are counted, with the peak number in flight at once.
    private static final class CountingTier implements StorageTier {
        private final MemoryTier delegate = MemoryTier.memoryTier(64L * 1024 * 1024, TierLevel.MEMORY);
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger failNextReads = new AtomicInteger();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            if (failNextReads.getAndDecrement() > 0) {
                return Causes.cause("injected I/O error").promise();
            }

            reads.incrementAndGet();
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);

            return Promise.lift(Causes::fromThrowable, this::pause)
                          .flatMap(_ -> delegate.get(id))
                          .fold(this::released);
        }

        /// Counted out as a DEPENDENT step, before whoever awaits the read sees it complete.
        private Promise<Option<byte[]>> released(Result<Option<byte[]>> result) {
            inFlight.decrementAndGet();

            return Promise.resolved(result);
        }

        private Unit pause() {
            LockSupport.parkNanos(2_000_000);

            return Unit.unit();
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }
    }
}
