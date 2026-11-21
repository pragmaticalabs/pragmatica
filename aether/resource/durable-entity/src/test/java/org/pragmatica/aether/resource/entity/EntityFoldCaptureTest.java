// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3303 A4b: [EntityFold#capture] answers one capture of the final fold of a closed parent, or a typed refusal. Every refusal
/// arm is pinned with the lineage read counted: a refusal BEFORE the copy must not have copied (`lineageReads == 0`), so a dropped
/// before-check cannot hide behind the after-check that re-reads the same facts.
class EntityFoldCaptureTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final int OTHER_PARTITION = 3;

    private CaptureSubstrate substrate;
    private EntityFold fold;

    @BeforeEach
    void setUp() {
        substrate = new CaptureSubstrate();
        fold = EntityFold.entityFold(KEYSPACE, substrate);
    }

    @Test
    void capture_afterReadyAndCatchUp_decodesToTheFoldedStateAndTimers() {
        substrate.log.add(EntityLogRecord.upsert("a", bytes("1")).encode());
        substrate.log.add(EntityLogRecord.upsert("b", bytes("2")).encode());
        substrate.log.add(EntityLogRecord.timerSchedule("a", "t1", 9_000L, bytes("cmd")).encode());
        fold.ready(PARTITION).await();
        fold.caughtUp(PARTITION).await();

        var capture = fold.capture(PARTITION, 2L).unwrap();
        var decoded = EntityFoldSnapshot.decode(capture.snapshot()).unwrap();

        assertThat(capture.through()).isEqualTo(2L);
        assertThat(decoded.state()).containsOnlyKeys("a", "b");
        assertThat(decoded.state().get("a")).isEqualTo(bytes("1"));
        assertThat(decoded.timers()).containsOnlyKeys("a");
        assertThat(decoded.timers().get("a")).containsOnlyKeys("t1");
        assertThat(capture.lineage().isPresent()).as("lineage read at the closed offset").isTrue();
        assertThat(capture.lineage().or(new byte[0])).as("lineage of the record AT the closed offset, not beside it")
                                                      .isEqualTo(bytes("lineage-2"));
    }

    @Test
    void capture_ofAnEmptyClosedLog_afterReady_isAnAcceptedEmptyCapture() {
        fold.ready(PARTITION).await();

        var capture = fold.capture(PARTITION, -1L).unwrap();

        assertThat(capture.through()).isEqualTo(-1L);
        assertThat(EntityFoldSnapshot.decode(capture.snapshot()).unwrap().state()).isEmpty();
    }

    @Test
    void arm_a_appliedBelowTheClosedOffset_isRefused_beforeAnyCopy() {
        readyThrough(1);
        substrate.log.add(EntityLogRecord.upsert("late", bytes("9")).encode());

        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.AppliedMismatch.class);
    }

    @Test
    void arm_a_appliedAboveTheClosedOffset_isRefused_beforeAnyCopy() {
        readyThrough(1);
        var past = EntityLogRecord.upsert("past", bytes("9"));

        substrate.log.add(past.encode());
        fold.apply(PARTITION, 2L, past);
        fold.apply(PARTITION, 3L, past);

        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.AppliedMismatch.class);
    }

    @Test
    void arm_b_committedBelowTheClosedOffset_isRefused_beforeAnyCopy() {
        readyThrough(2);
        substrate.committed = 1L;

        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.NotCommitted.class);
    }

    @Test
    void arm_c_aCutBeforeTheCall_oddAndEven_isRefused_beforeAnyCopy() {
        readyThrough(2);
        substrate.cutGeneration = 1L;
        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.Cut.class);

        substrate.cutGeneration = 2L;
        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.Cut.class);
    }

    @Test
    void arm_c_aCutInsideTheCopy_isRefused_afterTheCopy() {
        readyThrough(2);
        substrate.duringLineageRead = () -> substrate.cutGeneration += 2;

        var result = fold.capture(PARTITION, 2L);

        assertThat(refusalOf(result)).isInstanceOf(EntityFold.CaptureRefusal.Cut.class);
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_d_anEmptyNeverReadyFold_isRefusedByReadinessAlone() {
        assertThat(substrate.headOffset(KEYSPACE, PARTITION)).as("(e) holds: head is the closed offset").isEqualTo(-1L);
        assertThat(substrate.committedOffset(KEYSPACE, PARTITION)).as("(b) holds").isGreaterThanOrEqualTo(-1L);
        assertThat(substrate.cutGeneration(KEYSPACE, PARTITION) & 1L).as("(c) holds: no cut in progress").isZero();

        assertRefusedBeforeCopy(fold.capture(PARTITION, -1L), EntityFold.CaptureRefusal.NotReady.class);

        fold.ready(PARTITION).await();

        assertThat(fold.capture(PARTITION, -1L).isSuccess()).as("control: the same call after ready is accepted").isTrue();
    }

    @Test
    void arm_e_aLogHeadAboveOrBelowTheClosedOffset_isRefused_beforeAnyCopy() {
        readyThrough(2);
        substrate.log.add(EntityLogRecord.upsert("late", bytes("9")).encode());

        assertRefusedBeforeCopy(fold.capture(PARTITION, 2L), EntityFold.CaptureRefusal.LogEndMismatch.class);
        assertRefusedBeforeCopy(fold.capture(PARTITION, 5L), EntityFold.CaptureRefusal.LogEndMismatch.class);
    }

    @Test
    void arm_a_anOffsetParkedAboveTheClosedOffset_isRefused_beforeAnyCopy() throws Exception {
        readyThrough(2);
        appendAndApply(4L);

        assertThat(parkedAhead()).as("control: offset 4 is parked above the watermark").containsExactly(4L);
        assertThat(refusalOf(fold.capture(PARTITION, 2L))).isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(PARTITION, 4L, 2L));
        assertThat(substrate.lineageReads.get()).as("refused before the copy").isZero();
    }

    @Test
    void arm_a_anOffsetParkedAboveTheClosedOffset_duringTheCopy_isRefused_afterTheCopy() throws Exception {
        readyThrough(2);
        substrate.duringLineageRead = () -> appendAndApply(4L);

        var result = fold.capture(PARTITION, 2L);

        assertThat(parkedAhead()).as("control: offset 4 parked during the copy, the watermark unmoved").containsExactly(4L);
        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(PARTITION, 4L, 2L));
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_e_anApplyInFlightDuringTheCopy_isRefused_afterTheCopy() throws Exception {
        readyThrough(2);
        var state = publishedState();
        substrate.duringLineageRead = () -> applyInFlight(state, 3L);

        var result = fold.capture(PARTITION, 2L);

        assertThat(state).as("control: the in-flight apply wrote its state").containsKey("above3");
        assertThat(parkedAhead()).as("control: its watermark step never ran").isEmpty();
        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(PARTITION, 3L, 2L));
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_e_anApplyInFlightBeforeTheCopy_isRefused_beforeAnyCopy() throws Exception {
        readyThrough(2);
        var state = publishedState();
        var fired = new AtomicInteger();
        substrate.afterHeadRead = () -> applyInFlightOnce(fired, state, 3L);

        var result = fold.capture(PARTITION, 2L);

        assertThat(fired.get()).as("control: the apply landed right after the first head read").isPositive();
        assertThat(state).as("control: the in-flight apply wrote its state").containsKey("above3");
        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(PARTITION, 3L, 2L));
        assertThat(substrate.lineageReads.get()).as("the head re-read before the copy refused, so nothing was copied").isZero();
    }

    @Test
    void arm_e_aLogHeadFallingDuringTheCopy_isRefused_afterTheCopy() {
        readyThrough(2);
        substrate.duringLineageRead = () -> substrate.log.removeLast();

        var result = fold.capture(PARTITION, 2L);

        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(PARTITION, 1L, 2L));
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_a_theWatermarkAdvancingDuringTheCopy_isRefused_afterTheCopy() {
        readyThrough(2);
        substrate.duringLineageRead = () -> appendAndApply(3L);

        var result = fold.capture(PARTITION, 2L);

        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.AppliedMismatch(PARTITION, 3L, 2L));
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_b_theCommitPointFallingDuringTheCopy_isRefused_afterTheCopy() {
        readyThrough(2);
        substrate.duringLineageRead = () -> substrate.committed = 1L;

        var result = fold.capture(PARTITION, 2L);

        assertThat(refusalOf(result)).isEqualTo(new EntityFold.CaptureRefusal.NotCommitted(PARTITION, 1L, 2L));
        assertThat(substrate.lineageReads.get()).as("the copy ran, so the after-check is what refused").isEqualTo(1);
    }

    @Test
    void arm_d_aMemoStillPending_isNotReady_neverCaptured() {
        readyThrough(2);
        var gate = Promise.<List<byte[]>> promise();
        substrate.readGate = gate;
        var rebuilding = EntityFold.entityFold(KEYSPACE, substrate);

        rebuilding.ready(PARTITION);

        assertThat(refusalOf(rebuilding.capture(PARTITION, 2L))).isEqualTo(new EntityFold.CaptureRefusal.NotReady(PARTITION));
        assertThat(substrate.lineageReads.get()).as("a refusal before the copy never copied").isZero();
    }

    @Test
    void arm_d_aMemoResolvedAsFailure_isNotReady_neverCaptured() throws Exception {
        readyThrough(2);
        rebuildSlot().set(Causes.cause("rebuild failed").<Unit> promise());

        assertThat(refusalOf(fold.capture(PARTITION, 2L))).isEqualTo(new EntityFold.CaptureRefusal.NotReady(PARTITION));
        assertThat(substrate.lineageReads.get()).as("a refusal before the copy never copied").isZero();
    }

    @Test
    void capture_drainsAnOffsetLeftParkedByRacingDrains_beforeTheCheck() throws Exception {
        readyThrough(1);
        substrate.log.add(EntityLogRecord.upsert("k2", bytes("2")).encode());
        publishedState().put("k2", bytes("2"));
        parkedAhead().add(2L);

        var capture = fold.capture(PARTITION, 2L).unwrap();

        assertThat(capture.through()).isEqualTo(2L);
        assertThat(parkedAhead()).as("the drain consumed the parked offset").isEmpty();
        assertThat(EntityFoldSnapshot.decode(capture.snapshot()).unwrap().state()).containsOnlyKeys("k0", "k1", "k2");
    }

    @Test
    void refusals_carryEveryField_onANonZeroPartition() {
        var other = EntityFold.entityFold(KEYSPACE, substrate);

        assertThat(refusalOf(other.capture(OTHER_PARTITION, -1L))).isEqualTo(new EntityFold.CaptureRefusal.NotReady(OTHER_PARTITION));

        readyThrough(other, 1);
        substrate.log.add(EntityLogRecord.upsert("late", bytes("9")).encode());
        assertThat(refusalOf(other.capture(OTHER_PARTITION, 2L)))
            .isEqualTo(new EntityFold.CaptureRefusal.AppliedMismatch(OTHER_PARTITION, 1L, 2L));
        assertThat(refusalOf(other.capture(OTHER_PARTITION, 5L)))
            .isEqualTo(new EntityFold.CaptureRefusal.LogEndMismatch(OTHER_PARTITION, 2L, 5L));

        other.caughtUp(OTHER_PARTITION).await();
        substrate.committed = 1L;
        assertThat(refusalOf(other.capture(OTHER_PARTITION, 2L)))
            .isEqualTo(new EntityFold.CaptureRefusal.NotCommitted(OTHER_PARTITION, 1L, 2L));

        substrate.committed = Long.MAX_VALUE;
        substrate.cutGeneration = 1L;
        assertThat(refusalOf(other.capture(OTHER_PARTITION, 2L))).isEqualTo(new EntityFold.CaptureRefusal.Cut(OTHER_PARTITION));
    }

    /// Appends to the log until `offset` is its head, then applies ONLY that last record: an offset above the watermark
    /// parks when the offsets between are never applied.
    private void appendAndApply(long offset) {
        var record = EntityLogRecord.upsert("above" + offset, bytes("9"));

        while (substrate.log.size() <= offset) {
            substrate.log.add(record.encode());
        }

        fold.apply(PARTITION, offset, record);
    }

    /// An append that landed in the log at `offset` and whose apply wrote the state but has not yet stepped the watermark, as
    /// [EntityFold#apply] does between its state write and its watermark step.
    private void applyInFlight(Map<String, byte[]> state, long offset) {
        substrate.log.add(EntityLogRecord.upsert("above" + offset, bytes("9")).encode());
        state.put("above" + offset, bytes("9"));
    }

    private void applyInFlightOnce(AtomicInteger fired, Map<String, byte[]> state, long offset) {
        if (fired.getAndIncrement() == 0) {
            applyInFlight(state, offset);
        }
    }

    /// The partition's published fold, reached by reflection. Its parked-offset set cannot be filled deterministically through `apply`
    /// (it drains itself), so a park left by racing drains is placed directly, with its state written first as production does. Its rebuild
    /// slot never holds a failed memo when read ([EntityFold#ready] clears the slot BEFORE it resolves a failed rebuild), but a capture that
    /// reads a PENDING memo and then loses the race to a failing rebuild holds a failed memo in its local; placing the failed memo in the
    /// slot models that read-then-fail window deterministically, so the success check in [EntityFold#capture] is live, not dead code.
    private ConcurrentSkipListSet<Long> parkedAhead() throws Exception {
        var published = publishedFold();

        @SuppressWarnings("unchecked")
        var parked = (ConcurrentSkipListSet<Long>) field(published, "appliedAhead").get(published);

        return parked;
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> publishedState() throws Exception {
        var published = publishedFold();

        return (Map<String, byte[]>) field(published, "state").get(published);
    }

    private Object publishedFold() throws Exception {
        return ((AtomicReference<?>) field(partitionFold(), "published").get(partitionFold())).get();
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Promise<Unit>> rebuildSlot() throws Exception {
        return (AtomicReference<Promise<Unit>>) field(partitionFold(), "rebuild").get(partitionFold());
    }

    private Object partitionFold() throws Exception {
        var method = EntityFold.class.getDeclaredMethod("partitionFold", int.class);

        method.setAccessible(true);

        return method.invoke(fold, PARTITION);
    }

    private static Field field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }

    private void readyThrough(int lastOffset) {
        readyThrough(fold, lastOffset);
    }

    private void readyThrough(EntityFold target, int lastOffset) {
        for (var i = 0; i <= lastOffset; i++) {
            substrate.log.add(EntityLogRecord.upsert("k" + i, bytes(String.valueOf(i))).encode());
        }
        target.ready(target == fold ? PARTITION : OTHER_PARTITION).await();
    }

    private void assertRefusedBeforeCopy(Result<EntityFold.Capture> result, Class<? extends Cause> expected) {
        assertThat(refusalOf(result)).isInstanceOf(expected);
        assertThat(substrate.lineageReads.get()).as("a refusal before the copy never copied").isZero();
    }

    private static Cause refusalOf(Result<EntityFold.Capture> result) {
        var holder = new AtomicReference<Cause>();

        result.onFailure(holder::set);

        return holder.get();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class CaptureSubstrate implements EntityLogSubstrate {
        private final List<byte[]> log = new CopyOnWriteArrayList<>();
        private final AtomicInteger lineageReads = new AtomicInteger();
        private volatile long committed = Long.MAX_VALUE;
        private volatile long cutGeneration;
        /// When set, every `read` answers this promise: a rebuild held in flight.
        private volatile Promise<List<byte[]>> readGate;
        /// Runs inside every `lineageAt`: a cut that lands while a capture is being assembled.
        private volatile Runnable duringLineageRead = () -> {};
        /// Runs after every `headOffset` read, before it answers: an append landing right behind a head read.
        private volatile Runnable afterHeadRead = () -> {};

        @Override
        public long cutGeneration(String keyspace, int partition) {
            return cutGeneration;
        }

        @Override
        public long committedOffset(String keyspace, int partition) {
            return Math.min(committed, log.size() - 1L);
        }

        @Override
        public Option<byte[]> lineageAt(String keyspace, int partition, long offset) {
            lineageReads.incrementAndGet();
            duringLineageRead.run();

            return offset < 0 || offset >= log.size()
                   ? Option.none()
                   : Option.some(bytes("lineage-" + offset));
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, ReplicationFactors replication) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            log.add(record);

            return Promise.success(log.size() - 1L);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            if (readGate != null) {
                return readGate;
            }

            return fromOffset >= log.size()
                   ? Promise.success(List.of())
                   : Promise.success(List.copyOf(log.subList((int) fromOffset, (int) Math.min(log.size(), fromOffset + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            var head = log.size() - 1L;

            afterHeadRead.run();

            return head;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return 0L;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }
}
