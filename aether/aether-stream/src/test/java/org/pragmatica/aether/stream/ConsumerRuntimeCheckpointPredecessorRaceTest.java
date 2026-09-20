// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.ConsumerRuntimeState.CheckpointIssuePoint;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;


/// #1355 — the detach flush chains behind the consumer's periodic commit through
/// `ConsumerState.periodicCommit()` (#1239), so that slot must hold the commit being issued from the
/// moment a flush can observe it. `issueCheckpoint` used to assign it only after `observedCommit` had
/// returned — after the store call had been made, the `TrackedCommit` registered, three handlers attached
/// and a timeout timer armed — so a flush arriving in that window read the previous, settled commit and was
/// issued at once, overlapping the in-flight one. The scheduler-dependent version of this race is
/// `StreamConsumerRuntimeClusterCursorTest.detachFlush_waitsForTheInFlightPeriodicCommit_…` in
/// `aether-node`, red once in a full-module run under host contention.
///
/// Each test parks the issuing thread at one [CheckpointIssuePoint] through
/// [ConsumerRuntimeState#checkpointIssueProbe] and detaches the consumer from the test thread while it is
/// parked, so the interleaving is entered by construction rather than by luck.
class ConsumerRuntimeCheckpointPredecessorRaceTest {
    private static final String GROUP = "group-1";

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig("orders",
                                                       4,
                                                       RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000),
                                                       "earliest"));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    /// The issuing thread is parked AFTER its cancellation check and BEFORE the store call. A detach flush
    /// arriving there must find the slot already holding this commit: it is issued only once the periodic
    /// commit has settled, never beside it.
    @Test
    void detachFlush_arrivingWhileThePeriodicCommitIsBeingIssued_waitsForThatCommit() throws InterruptedException {
        var commits = new AtomicInteger();
        Promise<CommitOutcome> heldA = Promise.promise();
        var runtime = (ConsumerRuntimeState) streamConsumerRuntime(manager,
                                                                   DeadLetterHandler.deadLetterHandler(),
                                                                   holdingFirst(commits, heldA));
        var parkedInWindow = new CountDownLatch(1);
        var detached = new CountDownLatch(1);

        runtime.checkpointIssueProbe(parkFirstIssueAt(CheckpointIssuePoint.BEFORE_STORE_CALL, parkedInWindow, detached));
        try {
            runtime.subscribe("orders", 0, config(), (offset, payload, ts) -> Promise.unitPromise());
            // The 10ms interval elapses first, so the single delivery trips the time-based checkpoint.
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
            assertThat(parkedInWindow.await(5, TimeUnit.SECONDS)).as("the periodic commit's issuing thread parked before the store call")
                      .isTrue();
            assertThat(commits.get()).as("nothing has reached the store while the issue is parked").isZero();
            runtime.unsubscribe("orders", 0, GROUP);
            detached.countDown();
            awaitCount(commits::get, 1);
            Thread.sleep(200);
            assertThat(commits.get()).as("commit B (detach flush) must not be issued while commit A (periodic) is in flight")
                      .isEqualTo(1);
            heldA.succeed(CommitOutcome.persisted());
            awaitCount(commits::get, 2);
            assertThat(commits.get()).as("B is issued once A settles").isEqualTo(2);
        } finally {
            heldA.succeed(CommitOutcome.persisted());
            runtime.close();
        }
    }

    /// The issuing thread is parked AFTER the slot holds this commit and BEFORE its cancellation check. A
    /// detach flush arriving there reads the fresh slot and waits on it; the check then sees the cancel and
    /// bails without a store call, so the slot MUST settle right there — otherwise the flush waits out the
    /// shutdown bound for a commit that was never made. The total pins the ordering too: a slot assigned only
    /// after the check would let the flush read the settled predecessor and the checkpoint be issued beside
    /// it, two commits where the fix makes exactly one. Pins the bail path of the #1355 fix.
    @Test
    void detachFlush_arrivingBeforeTheCancellationCheck_isIssuedAsSoonAsTheCheckpointBails() throws InterruptedException {
        var commits = new AtomicInteger();
        var runtime = (ConsumerRuntimeState) streamConsumerRuntime(manager,
                                                                   DeadLetterHandler.deadLetterHandler(),
                                                                   persistingAll(commits));
        var parkedInWindow = new CountDownLatch(1);
        var detached = new CountDownLatch(1);

        runtime.checkpointIssueProbe(parkFirstIssueAt(CheckpointIssuePoint.SLOT_ASSIGNED, parkedInWindow, detached));
        try {
            runtime.subscribe("orders", 0, config(), (offset, payload, ts) -> Promise.unitPromise());
            Thread.sleep(50);
            manager.publishLocal("orders", 0, "event-1".getBytes(UTF_8), 1000L);
            assertThat(parkedInWindow.await(5, TimeUnit.SECONDS)).as("the periodic commit's issuing thread parked after assigning the slot")
                      .isTrue();
            runtime.unsubscribe("orders", 0, GROUP);
            Thread.sleep(100);
            assertThat(commits.get()).as("the detach flush waits on the slot it read").isZero();
            detached.countDown();
            awaitCount(commits::get, 1);
            Thread.sleep(200);
            assertThat(commits.get()).as("only the detach flush is committed: the cancelled checkpoint bails without a store call and settles the slot, releasing the flush at once")
                      .isEqualTo(1);
        } finally {
            runtime.close();
        }
    }

    /// The store hands the FIRST commit `held` and settles every later one at once, so the test decides when
    /// the periodic commit completes.
    private static ConsumerCursorStore holdingFirst(AtomicInteger commits, Promise<CommitOutcome> held) {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                return commits.incrementAndGet() == 1
                       ? held
                       : Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private static ConsumerCursorStore persistingAll(AtomicInteger commits) {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                commits.incrementAndGet();

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private static ConsumerConfig config() {
        return ConsumerConfig.consumerConfig(GROUP, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 10L, 3, "");
    }

    /// Parks the first issue that reaches `point` until `release` is counted down; every other issue (and
    /// every other point) passes straight through. A park that times out is not hidden: the thread then
    /// proceeds and the test's count assertions redden.
    private static Consumer<CheckpointIssuePoint> parkFirstIssueAt(CheckpointIssuePoint point,
                                                                   CountDownLatch parked,
                                                                   CountDownLatch release) {
        var claimed = new AtomicBoolean(false);

        return reached -> {
            if (reached == point && claimed.compareAndSet(false, true)) {
                parked.countDown();
                awaitRestoringInterrupt(release);
            }
        };
    }

    private static void awaitRestoringInterrupt(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Commit calls are made from the issuing thread, which the test thread only observes through the
    /// counter; the wait is bounded so a missing call reddens instead of hanging.
    private static void awaitCount(IntSupplier actual, int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (actual.getAsInt() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
