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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.segment.StreamRetentionSource.EnforcedRetention;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #2201 Q6 = B (know 59dfb9e86c): the enforcer judges each stream by its own policy from [StreamRetentionSource], over the
/// dimensions it declared; a stream the source does not answer is withheld (3.2 items 2-4 and 8).
class DeclaredRetentionEnforcerTest {
    private static final String STREAM = "orders";
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long UNBOUNDED = Long.MAX_VALUE;
    private static final int BLOCK_BYTES = 21;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final CountingTier tier = new CountingTier();
    private final StorageInstance storage = StorageInstance.storageInstance("streams",
                                                                            List.of(tier),
                                                                            MetadataStore.inMemoryMetadataStore("streams"));

    private RetentionEnforcer enforcer(SegmentIndex index, StreamRetentionSource source, Option<SegmentReader> reader) {
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           source,
                                                           DAY,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           reader);

        enforcer.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add), "node-1");

        return enforcer;
    }

    private SegmentIndex sealed(String stream, int segments, long eventTime) {
        var index = new SegmentIndex();

        sealPartition(index, stream, 0, segments, eventTime);

        return index;
    }

    private void sealPartition(SegmentIndex index, String stream, int partition, int segments, long eventTime) {
        var sink = storageSegmentSink(storage, index);

        for (var offset = 0; offset < segments; offset++) {
            sink.seal(sealedSegment(stream, partition, offset, offset, 1, eventTime, eventTime, encoded(offset, eventTime))).await();
        }
    }

    /// Partition 0 holds `hot` one-event segments, each of partitions 1 to 3 holds `others`.
    private SegmentIndex fourPartitions(int hot, int others, long eventTime) {
        var index = new SegmentIndex();

        sealPartition(index, STREAM, 0, hot, eventTime);
        for (var partition = 1; partition < 4; partition++) {
            sealPartition(index, STREAM, partition, others, eventTime);
        }

        return index;
    }

    /// The index a restart rebuilds from the ref names alone: no timestamp and no size (#1604).
    private SegmentIndex rebuilt(String stream, int segments) {
        var refs = new HashMap<String, BlockId>();

        for (var offset = 0; offset < segments; offset++) {
            var name = "streams/" + stream + "/0/" + offset + "-" + offset;

            storage.resolveRef(name).onPresent(id -> refs.put(name, id));
        }

        var index = new SegmentIndex();

        index.rebuildFromRefs(refs);

        return index;
    }

    private static int remaining(SegmentIndex index, String stream) {
        return index.listSegments(stream, 0).size();
    }

    /// NT-116 at the enforcer: today's code judged every segment on the partition's totals (100 segments > 50), so the pass
    /// reclaimed all 100; each segment is now judged on the events newer than it.
    @Test
    void declaredCount_reclaimsExactlyTheOldestThatExceedIt_notThePartitionsWholeHistory() {
        var index = sealed(STREAM, 100, System.currentTimeMillis());
        var policy = RetentionPolicy.retentionPolicy(50, UNBOUNDED, UNBOUNDED).withDeclared(RetentionDimensions.COUNT);

        enforcer(index, StreamRetentionSource.fixed(policy), Option.none()).enforceNow().await();

        assertThat(remaining(index, STREAM)).isEqualTo(50);
        assertThat(index.listSegments(STREAM, 0).getFirst().startOffset()).isEqualTo(50);
    }

    /// NT-132: a 7-day topic's policy (the durable-topic substrate's shape: ring-sizing count and bytes, the age declared)
    /// keeps more than 100,000 sealed events and more than 256 MiB while they are younger than 7 days.
    @Test
    void aSevenDayTopic_keepsMoreThanItsRingSizingCountAndBytes_whileYoung() {
        var index = new SegmentIndex();
        var now = System.currentTimeMillis();
        var segments = 100_001;

        for (var offset = 0; offset < segments; offset++) {
            index.addSegment(STREAM, 0, offset, offset, now, 0, false, 3_000);
        }

        var sizing = RetentionPolicy.retentionPolicy();
        var topic = RetentionPolicy.retentionPolicy(sizing.maxCount(), sizing.maxBytes(), 7 * DAY)
                                   .withDeclared(RetentionDimensions.AGE);

        enforcer(index, StreamRetentionSource.fixed(topic), Option.none()).enforceNow().await();

        assertThat(remaining(index, STREAM)).isEqualTo(segments);
        assertThat(segments * 3_000L).as("more than the 256 MiB ring sizing").isGreaterThan(sizing.maxBytes());
    }

    /// NT-11 / MT-03: the policy comes from the source per stream. A stream whose policy declares a 1 h age is cut at an hour;
    /// the node default age (a day) would keep it.
    @Test
    void eachStreamIsJudgedByItsOwnPolicy() {
        var index = sealed(STREAM, 3, System.currentTimeMillis() - 2 * HOUR);
        var hourly = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR).withDeclared(RetentionDimensions.AGE);

        enforcer(index, StreamRetentionSource.fixed(hourly), Option.none()).enforceNow().await();

        assertThat(remaining(index, STREAM)).isZero();
    }

    /// A stream that declares no dimension keeps the node default age, today's rule: its filled-in count of 1 is ring
    /// sizing, not a declaration (review R32-04).
    @Test
    void aStreamThatDeclaresNothing_keepsTheNodeDefaultAge() {
        var index = sealed(STREAM, 3, System.currentTimeMillis() - 2 * HOUR);

        enforcer(index, StreamRetentionSource.fixed(RetentionPolicy.retentionPolicy(1, 1, HOUR)), Option.none()).enforceNow().await();

        assertThat(remaining(index, STREAM)).isEqualTo(3);
    }

    /// An entity stream keeps the node default age whatever its config says (its ring age stands in for "never").
    @Test
    void anEntityStream_keepsTheNodeDefaultAge_whateverItsPolicyDeclares() {
        var entity = "entity:carts";
        var index = sealed(entity, 3, System.currentTimeMillis() - 2 * HOUR);
        var hourly = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR).withDeclared(RetentionDimensions.AGE);

        enforcer(index, StreamRetentionSource.fixed(hourly), Option.none()).enforceNow().await();

        assertThat(remaining(index, entity)).isEqualTo(3);
    }

    /// NT-10: a stream the source does not answer is withheld for as long as it does not answer (review R32-03): twelve
    /// passes (an hour at the 5-minute tick) reclaim nothing and raise `STREAM_RETENTION_WITHHELD` once; once the policy
    /// appears the next pass reclaims and closes it.
    @Test
    void aStreamTheSourceDoesNotAnswer_isWithheld_raisedOnce_andReclaimedOnceThePolicyAppears() {
        var index = sealed(STREAM, 3, System.currentTimeMillis() - 2 * DAY);
        var answers = new ConcurrentHashMap<String, EnforcedRetention>();
        var enforcer = enforcer(index, stream -> Option.option(answers.get(stream)), Option.none());

        for (var pass = 0; pass < 12; pass++) {
            enforcer.enforceNow().await();
        }

        assertThat(remaining(index, STREAM)).as("nothing reclaimed while the source does not answer").isEqualTo(3);
        awaitWarnings(1);
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_RETENTION_WITHHELD);
        assertThat(warnings.getFirst().subject()).isEqualTo("node node-1 stream orders");

        answers.put(STREAM, EnforcedRetention.enforcedRetention(RetentionPolicy.retentionPolicy(), 1));
        enforcer.enforceNow().await();

        assertThat(remaining(index, STREAM)).as("the node default age reclaims the 2-day-old segments").isZero();
        awaitWarnings(2);
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_RETENTION_WITHHELD,
                                            OperatorWarningCode.STREAM_RETENTION_RESUMED);
    }

    /// A stream whose retention mode this build cannot read (`UNKNOWN`, written by a newer node) is withheld like an
    /// unanswered one, and announced the same way (#964; F4, e2).
    @Test
    void aStreamWhoseModeIsUnknown_isWithheld_andRaisedOnce() {
        var index = sealed(STREAM, 3, System.currentTimeMillis() - 2 * DAY);
        var unknown = RetentionPolicy.retentionPolicy(1, 1, 1, RetentionMode.UNKNOWN)
                                     .withDeclared(RetentionDimensions.retentionDimensions(true, true, true));
        var enforcer = enforcer(index, StreamRetentionSource.fixed(unknown), Option.none());

        for (var pass = 0; pass < 3; pass++) {
            enforcer.enforceNow().await();
        }

        assertThat(remaining(index, STREAM)).isEqualTo(3);
        awaitWarnings(1);
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_RETENTION_WITHHELD);
    }

    /// One pass withheld is not yet an episode: a source that answers on the next pass raises nothing.
    @Test
    void aSingleWithheldPass_raisesNothing() {
        var index = sealed(STREAM, 1, System.currentTimeMillis());
        var answered = new AtomicInteger();
        var enforcer = enforcer(index,
                                _ -> answered.getAndIncrement() == 0
                                     ? Option.none()
                                     : Option.some(EnforcedRetention.enforcedRetention(RetentionPolicy.retentionPolicy(), 1)),
                                Option.none());

        enforcer.enforceNow().await();
        enforcer.enforceNow().await();

        settle();
        assertThat(warnings).isEmpty();
    }

    /// T-33 (#3334, ST 3.1 O2): with 4 partitions each applies a QUARTER of `max_bytes`. Partition 0 takes 3 x its share
    /// and is reclaimed to its share plus one segment; partitions 1 to 3, under their share, are untouched. Today's rule
    /// (the whole `max_bytes` on each partition) reclaims nothing here.
    @Test
    void bytesShare_atFourPartitions_reclaimsTheHotPartitionToItsShare_andLeavesTheOthers() {
        var stored = sealed(STREAM, 1, System.currentTimeMillis()).listSegments(STREAM, 0).getFirst().originalSize();
        var index = fourPartitions(9, 2, System.currentTimeMillis());
        var bytes = RetentionPolicy.retentionPolicy(UNBOUNDED, 12L * stored, UNBOUNDED).withDeclared(RetentionDimensions.BYTES);

        enforcer(index, StreamRetentionSource.fixed(bytes, 4), Option.none()).enforceNow().await();

        assertThat(index.listSegments(STREAM, 0)).as("share is 3 segments of bytes: 6 oldest go").hasSize(3);
        assertThat(index.listSegments(STREAM, 0).getFirst().startOffset()).isEqualTo(6);
        for (var partition = 1; partition < 4; partition++) {
            assertThat(index.listSegments(STREAM, partition)).as("partition " + partition + " is under its share").hasSize(2);
        }
    }

    /// The count bound is shared the same way (ST 2.4 item 17): `max_count` 12 over 4 partitions keeps 3 events each.
    @Test
    void countShare_atFourPartitions_reclaimsTheHotPartitionToItsShare_andLeavesTheOthers() {
        var index = fourPartitions(9, 2, System.currentTimeMillis());
        var count = RetentionPolicy.retentionPolicy(12, UNBOUNDED, UNBOUNDED).withDeclared(RetentionDimensions.COUNT);

        enforcer(index, StreamRetentionSource.fixed(count, 4), Option.none()).enforceNow().await();

        assertThat(index.listSegments(STREAM, 0)).hasSize(3);
        for (var partition = 1; partition < 4; partition++) {
            assertThat(index.listSegments(STREAM, partition)).hasSize(2);
        }
    }

    /// T-32: under `all`, over the byte bound but under the age bound reclaims nothing; past both reclaims.
    @Test
    void modeAll_overBytesUnderAge_reclaimsNothing_pastBoth_reclaims() {
        var stored = sealed(STREAM, 1, System.currentTimeMillis()).listSegments(STREAM, 0).getFirst().originalSize();
        var policy = RetentionPolicy.retentionPolicy(UNBOUNDED, 3L * stored, HOUR, RetentionMode.ALL)
                                    .withDeclared(RetentionDimensions.retentionDimensions(false, true, true));
        var young = sealed(STREAM, 10, System.currentTimeMillis());
        var old = sealed(STREAM, 10, System.currentTimeMillis() - 2 * HOUR);

        enforcer(young, StreamRetentionSource.fixed(policy), Option.none()).enforceNow().await();
        enforcer(old, StreamRetentionSource.fixed(policy), Option.none()).enforceNow().await();

        assertThat(remaining(young, STREAM)).as("over the byte bound, under the age bound").isEqualTo(10);
        assertThat(remaining(old, STREAM)).as("past both: reclaimed to the byte bound").isEqualTo(3);
    }

    /// NT-117: a rebuilt ref counts 0 bytes until learned; age learning records the size in the same block read (one read
    /// per ref), and then the declared bytes rule fires.
    @Test
    void aRebuiltRefsSize_isLearnedInTheAgeRead_andThenTheBytesRuleFires() {
        var stored = sealed(STREAM, 10, System.currentTimeMillis()).listSegments(STREAM, 0)
                                                                     .getFirst()
                                                                     .originalSize();
        var index = rebuilt(STREAM, 10);
        var bytes = RetentionPolicy.retentionPolicy(UNBOUNDED, 3L * stored, UNBOUNDED).withDeclared(RetentionDimensions.BYTES);

        enforcer(index, StreamRetentionSource.fixed(bytes), Option.none()).enforceNow().await();
        assertThat(remaining(index, STREAM)).as("without a reader nothing is learned: every size counts 0").isEqualTo(10);

        tier.reads.set(0);

        var learning = enforcer(index, StreamRetentionSource.fixed(bytes), Option.some(SegmentReader.segmentReader(storage, index)));

        learning.enforceNow().await();

        assertThat(tier.reads.get()).as("one block read per rebuilt ref learns both the age and the size").isEqualTo(10);
        assertThat(remaining(index, STREAM)).as("3 blocks' bytes newer than each reclaimed segment").isEqualTo(3);
        assertThat(index.listSegments(STREAM, 0)).allSatisfy(ref -> assertThat(ref.originalSize()).isEqualTo(stored));
    }

    /// NT-134: an age-only stream with one deterministically unreadable segment: it takes the next newer segment's age, the
    /// prefix moves past it, and `STREAM_SEGMENT_UNREADABLE` is raised once and closed when retention drops it.
    @Test
    void anUnreadableSegment_takesTheNextNewerAge_isRaisedOnce_andClosedWhenReclaimed() {
        var old = System.currentTimeMillis() - 2 * DAY;

        sealed(STREAM, 3, old);

        var index = rebuilt(STREAM, 3);

        storage.resolveRef("streams/" + STREAM + "/0/0-0").onPresent(tier::lose);

        var ageOnly = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR).withDeclared(RetentionDimensions.AGE);
        var enforcer = enforcer(index, StreamRetentionSource.fixed(ageOnly), Option.some(SegmentReader.segmentReader(storage, index)));

        enforcer.enforceNow().await();
        enforcer.enforceNow().await();

        assertThat(remaining(index, STREAM)).as("the unreadable oldest segment no longer holds the prefix").isZero();
        awaitWarnings(2);
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_SEGMENT_UNREADABLE,
                                            OperatorWarningCode.STREAM_SEGMENT_UNREADABLE_RECLAIMED);
        assertThat(warnings.getFirst().subject()).isEqualTo("node node-1 stream orders[0] 0-0");
    }

    /// T-157, enforcement arm (#3334, ST 2.5): a `max_bytes` that grows online is the bound of the next pass. Four partitions
    /// share it; partition 0 holds 3 segments under a share of 3 segments of bytes (nothing goes), then 3 more are sealed and
    /// the bound doubles: the next pass reclaims nothing (the old bound would take 3), and no reclaimed segment returns.
    @Test
    void bytesBoundGrowing_isTheBoundOfTheNextPass_andNoReclaimedSegmentReturns() {
        var now = System.currentTimeMillis();
        var stored = sealed(STREAM, 1, now).listSegments(STREAM, 0).getFirst().originalSize();
        var index = new SegmentIndex();
        var bound = new AtomicReference<>(bytesBound(12L * stored));
        var enforcer = enforcer(index, _ -> Option.some(bound.get()), Option.none());

        sealRange(index, 0, 0, 2, now);
        var firstPass = enforcer.enforceNow().await().or(-1);
        sealRange(index, 0, 3, 5, now);
        bound.set(bytesBound(24L * stored));
        var afterGrowth = enforcer.enforceNow().await().or(-1);
        var afterGrowthAgain = enforcer.enforceNow().await().or(-1);

        assertThat(firstPass).as("control: 3 segments are within a share of 3").isZero();
        assertThat(afterGrowth).as("the grown bound (share 6) reclaims nothing from 6 segments; the old one would take 3").isZero();
        assertThat(afterGrowthAgain).isZero();
        assertThat(remaining(index, STREAM)).as("all 6 held: a reclaimed segment never comes back, a held one never goes").isEqualTo(6);
        assertThat(index.listSegments(STREAM, 0).getFirst().startOffset()).isZero();
    }

    /// T-158, enforcement arm (#3334, ST 2.5): a `max_bytes` that is halved online is the bound of the next pass at every
    /// holder: each of 4 partitions holding 6 segments under a share of 6 is reclaimed to the new share of 3, the earliest
    /// held offset moves to 3 (where a group below it is moved forward, once: T-34), and the first pass at the old bound
    /// took nothing.
    @Test
    void bytesBoundShrinking_reclaimsEveryHolderToTheNewShare_atItsNextPass() {
        var now = System.currentTimeMillis();
        var stored = sealed(STREAM, 1, now).listSegments(STREAM, 0).getFirst().originalSize();
        var index = fourPartitions(6, 6, now);
        var bound = new AtomicReference<>(bytesBound(24L * stored));
        var enforcer = enforcer(index, _ -> Option.some(bound.get()), Option.none());

        var atOldBound = enforcer.enforceNow().await().or(-1);
        bound.set(bytesBound(12L * stored));
        var atNewBound = enforcer.enforceNow().await().or(-1);

        assertThat(atOldBound).as("control: 6 segments are within a share of 6").isZero();
        assertThat(atNewBound).as("3 segments of each of 4 partitions").isEqualTo(12);
        for (var partition = 0; partition < 4; partition++) {
            assertThat(index.listSegments(STREAM, partition)).as("partition " + partition + " keeps its new share").hasSize(3);
            assertThat(index.listSegments(STREAM, partition).getFirst().startOffset()).as("partition " + partition + " earliest held offset").isEqualTo(3);
        }
    }

    private static EnforcedRetention bytesBound(long maxBytes) {
        return EnforcedRetention.enforcedRetention(RetentionPolicy.retentionPolicy(UNBOUNDED, maxBytes, UNBOUNDED).withDeclared(RetentionDimensions.BYTES),
                                                   4);
    }

    /// Seals one-event segments `from` to `to` of `partition`, after those already sealed.
    private void sealRange(SegmentIndex index, int partition, int from, int to, long eventTime) {
        var sink = storageSegmentSink(storage, index);

        for (var offset = from; offset <= to; offset++) {
            sink.seal(sealedSegment(STREAM, partition, offset, offset, 1, eventTime, eventTime, encoded(offset, eventTime))).await();
        }
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private void awaitWarnings(int count) {
        awaitUntil(() -> warnings.size() >= count);
        settle();
        assertThat(warnings).hasSize(count);
    }

    private static void awaitUntil(Supplier<Boolean> condition) {
        var deadline = System.currentTimeMillis() + 5_000;

        while (!condition.get() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// The sink hands warnings off on its own thread: a short quiet period shows that no further one follows.
    private static void settle() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] encoded(long offset, long eventTime) {
        return ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) offset).array();
    }

    /// A memory tier that counts reads and can lose a block, so its read answers absent for good.
    private static final class CountingTier implements StorageTier {
        private final MemoryTier delegate = MemoryTier.memoryTier(64L * 1024 * 1024, TierLevel.MEMORY);
        private final Map<BlockId, Boolean> lost = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        void lose(BlockId id) {
            lost.put(id, true);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            reads.incrementAndGet();

            return lost.containsKey(id)
                   ? Promise.success(Option.none())
                   : delegate.get(id);
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
