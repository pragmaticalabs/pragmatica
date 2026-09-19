// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.management.ThreadMXBean;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1269 — the checkpoint tick must not do work whose result it is about to discard.
///
/// Before the fix every tick copied and encoded the whole fold of every folded partition, owner and
/// replica alike, and only THEN asked whether the result advanced the last checkpoint — so an idle
/// partition paid a full encode every 30 s for nothing. And a save still in flight when the next tick came
/// round was simply started again.
class EntityCheckpointDriverCoalescingTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final int KEYS = 1_000;
    private static final int VALUE_BYTES = 10 * 1024;
    private static final long IDLE_TICK_BUDGET = 64 * 1024;

    /// About 10 MB of folded state and no appends between two ticks: the second has nothing to record, and
    /// must find that out BEFORE copying anything. Measured as bytes allocated by the ticking thread, which
    /// is where the copy and the encode run.
    @Test
    void tick_allocatesAlmostNothing_whenTheFoldHasNotAdvancedSinceTheLastCheckpoint() {
        var substrate = new CountingSubstrate(true);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);
        driver.tick();

        assertThat(substrate.saves.get()).as("the first tick checkpoints the populated fold").isEqualTo(1);

        var before = allocatedBytes();

        driver.tick();

        var allocated = allocatedBytes() - before;

        assertThat(substrate.saves.get()).as("an idle partition writes no second checkpoint").isEqualTo(1);
        assertThat(allocated).as("bytes an idle tick allocated, against ~%d MB of fold state",
                                 (long) KEYS * VALUE_BYTES / (1024 * 1024))
                             .isLessThan(IDLE_TICK_BUDGET);
    }

    /// A save that has not resolved by the next tick is still the partition's checkpoint in progress; the
    /// next tick leaves it alone rather than encoding and saving the same fold a second time.
    @Test
    void tick_startsNoSecondSave_whileTheFirstIsStillInFlight() {
        var substrate = new CountingSubstrate(false);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);
        driver.tick();
        driver.tick();

        assertThat(substrate.saves.get()).isEqualTo(1);
    }

    /// ...but the in-flight mark is BOUNDED: a save whose promise never settles must not hold the partition's
    /// checkpoints for the life of the node. Once the mark is [EntityCheckpointDriver#IN_FLIGHT_BOUND_TICKS]
    /// ticks old, the next tick starts another save.
    @Test
    void tick_startsAnotherSave_onceAnUnsettledSaveOutlivesItsBound() {
        var substrate = new CountingSubstrate(false);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);

        for (var tick = 0; tick < EntityCheckpointDriver.IN_FLIGHT_BOUND_TICKS; tick++) {
            driver.tick();
        }

        assertThat(substrate.saves.get()).as("within the bound the unsettled save still holds the partition")
                                         .isEqualTo(1);

        driver.tick();

        assertThat(substrate.saves.get()).as("past the bound the next tick must start another save").isEqualTo(2);
    }

    /// The abandoned save settling LATE must not clear the mark of the save that replaced it — or the very
    /// next tick would start a third save while the second is still in flight.
    @Test
    void tick_keepsTheReplacementsMark_whenTheAbandonedSaveSettlesLate() {
        var substrate = new CountingSubstrate(false);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);

        for (var tick = 0; tick <= EntityCheckpointDriver.IN_FLIGHT_BOUND_TICKS; tick++) {
            driver.tick();
        }

        assertThat(substrate.saves.get()).as("the bound elapsed and a replacement save started").isEqualTo(2);

        substrate.settleSave(0);
        driver.tick();

        assertThat(substrate.saves.get()).as("the replacement is still in flight").isEqualTo(2);
    }

    /// Taking over a stalled checkpoint lets the abandoned save land LATE, after its replacement. The
    /// committed side of that is closed in the substrate — checkpoint writes are `MonotonicFenced` (#700),
    /// so the consensus applier refuses the lower claim — and this pins the local side: the driver's own
    /// record of what it wrote must not be pulled back to the late save's lower offset, or the next tick
    /// would re-encode and re-save work already checkpointed — and the refused save must not be counted as
    /// a write. Saves settle inline (`withResult`), so no wait is needed after settling one.
    @Test
    void tick_keepsTheHigherCheckpointRecorded_whenTheAbandonedLowerSaveLandsLate() {
        var substrate = new CountingSubstrate(false);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);
        driver.tick();

        for (var i = KEYS; i < 2 * KEYS; i++) {
            fold.apply(PARTITION, i, EntityLogRecord.upsert("k" + i, new byte[16]));
        }

        for (var tick = 0; tick < EntityCheckpointDriver.IN_FLIGHT_BOUND_TICKS; tick++) {
            driver.tick();
        }

        assertThat(substrate.saves.get()).as("the replacement save claims the advanced fold").isEqualTo(2);

        substrate.succeedSave(1);

        assertThat(writes(driver)).as("the replacement's accepted save is counted").isEqualTo(1L);

        substrate.succeedSave(0);

        assertThat(writes(driver)).as("the late, lower save — refused by the fence — is not counted as a write")
                                  .isEqualTo(1L);

        assertThat(driver.snapshot()
                         .keyspaces()
                         .getFirst()
                         .checkpointedThrough()).containsEntry(PARTITION, 2L * KEYS - 1);
    }

    private static long writes(EntityCheckpointDriver driver) {
        return driver.snapshot()
                     .keyspaces()
                     .getFirst()
                     .writes();
    }

    /// A tick with NOTHING new to checkpoint must clear the mark it took (rev1283 M1). Left set, every idle
    /// partition would be "taken over" with a false WARN every [EntityCheckpointDriver#IN_FLIGHT_BOUND_TICKS]
    /// ticks, and a partition becoming active again would wait up to that many ticks for its checkpoint.
    @Test
    void tick_checkpointsTheNextAdvance_rightAfterAnIdleTick() {
        var substrate = new CountingSubstrate(true);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        driver.register(KEYSPACE, 1, fold, substrate);
        driver.tick();
        driver.tick();

        assertThat(substrate.saves.get()).as("the idle tick saves nothing").isEqualTo(1);

        fold.apply(PARTITION, KEYS, EntityLogRecord.upsert("fresh", new byte[16]));
        driver.tick();

        assertThat(substrate.saves.get()).as("the tick after the idle one must checkpoint the advance").isEqualTo(2);
    }

    /// The in-flight mark must not outlive a checkpoint that THREW while starting: left behind, it would
    /// stop that partition's checkpoints for the life of the node, silently. Regression fence for the mark
    /// — the pre-#1269 code passes this too, because it had no mark to leave behind.
    @Test
    void tick_retriesNextTick_whenTheSaveThrowsSynchronously() {
        var substrate = new CountingSubstrate(true);
        var fold = populatedFold(substrate);
        var driver = EntityCheckpointDriver.entityCheckpointDriver();

        substrate.throwOnNextSave();
        driver.register(KEYSPACE, 1, fold, substrate);
        driver.tick();
        driver.tick();

        assertThat(substrate.saves.get()).as("the second tick must try again").isEqualTo(2);
        assertThat(driver.snapshot()
                         .keyspaces()
                         .getFirst()
                         .checkpointedThrough()).containsEntry(PARTITION, (long) KEYS - 1);
    }

    private static EntityFold populatedFold(CountingSubstrate substrate) {
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        fold.ready(PARTITION).await().onFailure(cause -> fail(cause.message()));

        for (var i = 0; i < KEYS; i++) {
            fold.apply(PARTITION, i, EntityLogRecord.upsert("k" + i, new byte[VALUE_BYTES]));
        }

        return fold;
    }

    private static long allocatedBytes() {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

        return threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /// An empty log that counts checkpoint saves; each save either resolves at once or never.
    private static final class CountingSubstrate implements EntityLogSubstrate {
        private final boolean savesResolve;
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicBoolean throwOnNextSave = new AtomicBoolean();
        private final List<Promise<Unit>> pending = new CopyOnWriteArrayList<>();

        CountingSubstrate(boolean savesResolve) {
            this.savesResolve = savesResolve;
        }

        /// Fail the `index`-th save that was left pending — a save the driver may already have written off.
        void settleSave(int index) {
            pending.get(index)
                   .fail(Causes.cause("abandoned save settled late"));
        }

        void succeedSave(int index) {
            pending.get(index)
                   .succeed(Unit.unit());
        }

        void throwOnNextSave() {
            throwOnNextSave.set(true);
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            saves.incrementAndGet();

            if (throwOnNextSave.compareAndSet(true, false)) {
                throw new IllegalStateException("substrate threw instead of failing its promise");
            }

            if (savesResolve) {
                return Promise.unitPromise();
            }

            var save = Promise.<Unit> promise();

            pending.add(save);

            return save;
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            return Promise.success(0L);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            return Promise.success(List.of());
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return -1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return -1L;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }
}
