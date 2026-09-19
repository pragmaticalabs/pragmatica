// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1268 — [EntityFold#ready] must neither return `null` nor hand out a promise nothing will ever resolve.
/// Both are the shapes #701 removed from [EntityFold#caughtUp]; either one, reaching
/// `PartitionFencedDurableEntity.readyPartition` inside a per-key task, wedged the key (or the whole
/// partition) for the life of the process.
class EntityFoldReadyLivenessTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 3;
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final int CALLERS = 8;
    private static final int CALLS_PER_CALLER = 20_000;

    /// E4, deterministic by construction through [EntityFold#readyWindowProbe(java.util.function.Consumer)].
    /// The loser L is held in its two windows in turn:
    ///
    ///   1. slot read EMPTY, CAS not yet attempted — a winner W calls `ready()` on another thread and takes
    ///      the slot with a rebuild parked in its checkpoint load, so L's CAS is certain to LOSE;
    ///   2. CAS lost — W's checkpoint load now fails, its rebuild fails and clears the slot, and L waits
    ///      for W's promise to resolve, which happens only after the slot is cleared.
    ///
    /// The pre-#1268 code re-read the slot at that point and returned `null`. Re-entering makes L's own
    /// attempt, which here succeeds.
    @Test
    @Timeout(60)
    void ready_reEntersRatherThanReturningNull_whenTheWinnersRebuildFailsAfterTheLoserLostTheCas() {
        var substrate = new ScriptedSubstrate();
        var fold = EntityFold.entityFold(KEYSPACE, substrate);
        var winner = new AtomicReference<Promise<Unit>>();
        var emptyReadHeld = new AtomicBoolean();
        var casLossHeld = new AtomicBoolean();

        substrate.parkNextCheckpointLoad();
        fold.readyWindowProbe(window -> holdLoser(window, fold, substrate, winner, emptyReadHeld, casLossHeld));

        var loser = fold.ready(PARTITION);

        assertThat(casLossHeld.get()).as("the loser must have lost the CAS, or this test proves nothing").isTrue();
        assertThat(winner.get().await(AWAIT).isFailure()).as("the winner's rebuild must have failed").isTrue();
        assertThat(loser).as("ready() must never return null").isNotNull();
        loser.await(AWAIT)
             .onFailure(cause -> fail("the loser's own attempt must resolve, got: " + cause.message()));
    }

    private static void holdLoser(EntityFold.ReadyWindow window,
                                  EntityFold fold,
                                  ScriptedSubstrate substrate,
                                  AtomicReference<Promise<Unit>> winner,
                                  AtomicBoolean emptyReadHeld,
                                  AtomicBoolean casLossHeld) {
        switch (window) {
            case EMPTY_SLOT_READ -> {
                if (emptyReadHeld.compareAndSet(false, true)) {
                    joinQuietly(Thread.ofPlatform().start(() -> winner.set(fold.ready(PARTITION))));
                }
            }
            case CAS_LOST -> {
                if (casLossHeld.compareAndSet(false, true)) {
                    substrate.failParkedCheckpointLoad();
                    winner.get().await(AWAIT);
                }
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// E4, supplementary volume race: the loser of the memo's compare-and-set re-read the slot, and a winner whose rebuild failed
    /// SYNCHRONOUSLY (the partition is not held) could clear it in between — so `ready()` returned `null`.
    ///
    /// Forced by volume rather than by a hook. The ticket asked for an intercepted compare-and-set, but
    /// every method of `AtomicReference` is final, so the slot cannot be instrumented; instead several
    /// threads call `ready()` on a partition that is not held, where every winner takes and clears the
    /// slot within its own call. The window is the few instructions between the loser's failed CAS and its
    /// re-read. Measured against the pre-fix code: 3 of 3 runs returned null, 179k–209k times out of 1.6M
    /// calls; this count is a tenth of that.
    @Test
    @Timeout(120)
    void ready_neverReturnsNull_whenConcurrentCallersRaceAFailingRebuild() throws InterruptedException {
        var substrate = new ScriptedSubstrate();
        var fold = EntityFold.entityFold(KEYSPACE, substrate);
        var nulls = new LongAdder();
        var unresolved = new LongAdder();
        var start = new CountDownLatch(1);

        substrate.neverHold();

        var callers = IntStream.range(0, CALLERS)
                               .mapToObj(_ -> Thread.ofPlatform()
                                                    .start(() -> callReady(fold, start, nulls, unresolved)))
                               .toList();

        start.countDown();

        for (var caller : callers) {
            caller.join();
        }

        assertThat(nulls.sum()).as("ready() returned null").isZero();
        assertThat(unresolved.sum()).as("ready() returned a promise that did not resolve").isZero();
    }

    private static void callReady(EntityFold fold, CountDownLatch start, LongAdder nulls, LongAdder unresolved) {
        awaitQuietly(start);

        for (var i = 0; i < CALLS_PER_CALLER; i++) {
            Option.option(fold.ready(PARTITION))
                  .onEmpty(nulls::increment)
                  .onPresent(promise -> countUnresolved(promise, unresolved));
        }
    }

    private static void countUnresolved(Promise<Unit> promise, LongAdder unresolved) {
        if (promise.await(AWAIT).isFailure() && !promise.isResolved()) {
            unresolved.increment();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// E4b: a substrate that THROWS during the rebuild, instead of failing a promise, must not leave the
    /// memo holding a promise nothing resolves. The first call fails; the second — the substrate healthy
    /// again — makes its own attempt and resolves.
    @Test
    @Timeout(60)
    void ready_failsAndLeavesTheSlotReusable_whenTheRebuildThrowsSynchronously() {
        var substrate = new ScriptedSubstrate();
        var fold = EntityFold.entityFold(KEYSPACE, substrate);

        substrate.throwOnceFromHoldsCheck();

        var first = Result.lift(() -> fold.ready(PARTITION));

        assertThat(first.isSuccess()).as("a synchronous throw must not escape ready()").isTrue();
        first.onSuccess(promise -> assertThat(promise.await(AWAIT).isFailure()).as("the first ready() must fail")
                                                                              .isTrue());

        fold.ready(PARTITION)
            .await(AWAIT)
            .onFailure(cause -> fail("the second ready() must make its own attempt and resolve, got: "
                                     + cause.message()));
    }

    /// An empty log whose `holdsPartition` can be scripted: answer "not held" throughout, or throw once.
    /// Otherwise it answers "held".
    private static final class ScriptedSubstrate implements EntityLogSubstrate {
        private volatile boolean held = true;
        private final AtomicBoolean throwOnce = new AtomicBoolean();
        private final AtomicReference<Promise<Option<EntityCheckpoint>>> parked = new AtomicReference<>();
        private volatile Promise<Option<EntityCheckpoint>> parkedLoad;

        void neverHold() {
            held = false;
        }

        void throwOnceFromHoldsCheck() {
            throwOnce.set(true);
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            if (throwOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("substrate threw instead of answering");
            }

            return held;
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
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        /// The next checkpoint load stays pending until [#failParkedCheckpointLoad]; later ones answer at once.
        void parkNextCheckpointLoad() {
            parked.set(Promise.promise());
        }

        void failParkedCheckpointLoad() {
            parkedLoad.fail(Causes.cause("parked checkpoint load failed"));
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            var load = parked.getAndSet(null);

            if (load == null) {
                return Promise.success(Option.none());
            }

            parkedLoad = load;

            return load;
        }
    }
}
