// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #3754: the stop of a [StreamPartitionManager] ends the lifetime of its two scheduled tasks, the unsettled-repair report
/// (an operator warning) and the park expiry of an ack waiter (an answer to a caller). Production order throughout: the
/// task is scheduled by the real code path, the manager is stopped by its real `close()`, and only the clock is replaced by a
/// fake scheduling seam that fires the lifetime's wrapper by hand.
class StreamPartitionManagerStopLifetimeTest {
    private static final String SINGLE = "single";
    private static final String ORDERS = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final TimeSpan ANSWER_WAIT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan REPORT_BOUND = TimeSpan.timeSpan(300).millis();

    @TempDir
    Path walDir;

    private static final Logger LOG = LoggerFactory.getLogger(StreamPartitionManagerStopLifetimeTest.class);

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    /// Markers raised through the same sink after the action under test; the sink delivers in order, so a marker that has
    /// arrived proves every earlier warning has too ([#drainSink]).
    private final List<OperatorWarning> markers = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(this::deliver);
    private final FakeSeam seam = new FakeSeam();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private StreamPartitionManager manager;
    private ReplicationManager replication;
    private Promise<Unit> parked;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    // ---- site 1: the unsettled-repair report (operator warning) -------------------------------------------------------------
    /// Control for the two tests below: the fake seam reaches the report, so a silent run after the stop is the stop's doing.
    @Test
    void unsettledReport_firedOnALiveManager_warns() {
        startWithUnsettledRepair();
        assertThat(seam.wrappers).as("the repair armed one report timer").hasSize(1);
        seam.wrappers.getFirst().run();
        awaitWarnings(1);
        assertThat(warnings).singleElement()
                  .satisfies(warning -> {
                                 assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_DIVERGENT_TAIL_TRUNCATED);
                                 assertThat(warning.message()).contains("repairSettled=false");
                             });
    }

    /// (a) A report scheduled before the stop never runs its body after it: the stop cancels the timer, and a wrapper that fires
    /// anyway finds the lifetime ended. Mutant: `close()` does not end the lifetime.
    @Test
    void unsettledReport_scheduledBeforeTheStop_neverReportsAfterIt() {
        startWithUnsettledRepair();
        manager.close();
        assertThat(seam.futures).as("the stop cancelled the report timer").allMatch(FakeFuture::isCancelled);
        seam.wrappers.getFirst().run();
        drainSink();
        assertThat(warnings).as("a stopped manager raises no operator warning").isEmpty();
    }

    /// (b) A report already past the wrapper's read when the stop runs does not warn: the body re-reads the lifetime before the
    /// warning. Mutant: the re-read in `reportCut` is removed (the cleared `streams` makes the factor 0, which warns).
    @Test
    void unsettledReport_inFlightAtTheStop_doesNotWarn() {
        startWithUnsettledRepair();
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);

        manager.beforeUnsettledReportProbe(() -> pauseUntil(started, stopped));
        var body = Thread.ofVirtual().start(seam.wrappers.getFirst());

        assertThat(awaitLatch(started)).as("the body is in flight").isTrue();
        manager.close();
        stopped.countDown();
        joinQuietly(body);
        drainSink();
        assertThat(warnings).as("the report in flight at the stop is dropped").isEmpty();
    }

    // ---- site 2: the park expiry of an ack waiter (answer to a caller) -----------------------------------------------------
    /// (a) A park expiry scheduled before the stop is cancelled by it, and the waiter is answered at the stop rather than
    /// orphaned (the answer its expiry would have given); the expiry wrapper firing later changes nothing. Mutants: `close()`
    /// does not end the lifetime (timer not cancelled); `close()` does not answer the parked waiters (waiter hangs).
    @Test
    void parkExpiry_scheduledBeforeTheStop_isCancelled_andTheWaiterAnsweredOnce() {
        startWithParkedWaiter();
        var answer = parked;

        assertThat(seam.futures).as("the lapsed waiter armed one expiry").hasSize(1);
        assertThat(answer.isResolved()).as("control: parked, unanswered").isFalse();
        manager.close();
        assertThat(seam.futures).as("the stop cancelled the expiry").allMatch(FakeFuture::isCancelled);
        assertThat(answer.isResolved()).as("the waiter is answered at the stop").isTrue();
        answer.await(ANSWER_WAIT)
              .onSuccess(_ -> fail("a lapsed waiter must not acknowledge"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerLeaseLapsedBeforeAck.class));
        seam.wrappers.getFirst().run();
        assertThat(answer.await(ANSWER_WAIT).isFailure()).as("the late expiry answers nothing new").isTrue();
    }

    /// (b) A waiter that joins the park list after the stop, past the close-time drain, is answered at once instead of waiting on
    /// an expiry that was never enqueued. Mutant: `park` does not answer a waiter whose expiry came back cancelled.
    @Test
    void parkExpiry_joiningAfterTheStop_isAnsweredAtOnce() {
        start(2);
        var answer = manager.awaitReplication(ORDERS, PARTITION, publish(), 1);

        leaseValid.set(false);
        manager.beforeParkJoinProbe(manager::close);
        ack(0L);
        assertThat(answer.isResolved()).as("answered at once, not left to an expiry that was never enqueued").isTrue();
        answer.await(ANSWER_WAIT)
              .onSuccess(_ -> fail("a lapsed waiter must not acknowledge"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerLeaseLapsedBeforeAck.class));
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------------
    private void startWithUnsettledRepair() {
        var epoch = Epoch.epoch(1L, 2L, 3L);

        manager = streamPartitionManager(Long.MAX_VALUE,
                                         Option.some(walDir.resolve("cf1")));
        manager.schedulingSeam(seam);
        manager.createStream(StreamConfig.streamConfig(SINGLE).withReplication(ReplicationFactors.replicationFactors(1,
                                                                                                                     1).unwrap()))
               .onFailure(cause -> fail(cause.message()));
        manager.operatorWarnings(sink);
        manager.repairReportBound(REPORT_BOUND);
        for (var i = 0; i < 12; i++) {
            manager.appendRecovered(SINGLE, PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, epoch).unwrap();
        }

        manager.syncReplicated(SINGLE, PARTITION).await();
        manager.appendRecovered(SINGLE, PARTITION, 9, "different".getBytes(UTF_8), 1009L, epoch);
        manager.repairDivergence(SINGLE, PARTITION, _ -> true).unwrap();
    }

    private void startWithParkedWaiter() {
        start(2);
        parked = manager.awaitReplication(ORDERS, PARTITION, publish(), 1);
        leaseValid.set(false);
        ack(0L);
    }

    private void start(int confirmationFactor) {
        replication = replicationWithPeer();
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        manager.schedulingSeam(seam);
        manager.ownerLease(leaseValid::get);
        manager.ownershipRecords((_, _) -> Option.some(record()));
        manager.createStream(StreamConfig.streamConfig(ORDERS,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       3,
                                                       confirmationFactor,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
    }

    private long publish() {
        return manager.publishLocal(ORDERS,
                                    PARTITION,
                                    "e0".getBytes(UTF_8),
                                    1L)
                      .onFailure(cause -> fail("publish failed: " + cause.message()))
                      .or(-1L);
    }

    private void ack(long offset) {
        replication.handleAck(replicateAck(PEER, ORDERS, PARTITION, offset));
    }

    private static StreamPartitionOwnershipValue record() {
        return new StreamPartitionOwnershipValue(SELF,
                                                 E1,
                                                 1L,
                                                 HlcTimestamp.ZERO,
                                                 List.of(SELF, PEER),
                                                 1L,
                                                 false,
                                                 List.of(),
                                                 List.of());
    }

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(ORDERS, PARTITION, SELF);
        registry.registerReplica(ORDERS, PARTITION, PEER);

        return replicationManager(SELF, registry);
    }

    private void awaitWarnings(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private void deliver(OperatorWarning warning) {
        (warning.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_PRESERVED
         ? markers
         : warnings).add(warning);
    }

    /// A negative assertion on the asynchronous sink: raises a marker through the sink and waits for it. The sink is one queue
    /// drained in order, so every warning raised before the marker has been delivered when the marker arrives. Its positive
    /// control is [#unsettledReport_firedOnALiveManager_warns].
    private void drainSink() {
        var expected = markers.size() + 1;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        OperatorWarnings.raise(LOG,
                               sink,
                               OperatorWarningCode.STREAM_DIVERGENT_TAIL_PRESERVED,
                               "marker-" + expected,
                               "marker");
        while (markers.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(markers).as("the marker came through the sink behind the warnings").hasSize(expected);
    }

    private static void pauseUntil(CountDownLatch started, CountDownLatch released) {
        started.countDown();
        awaitLatch(released);
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Records every enqueue and hands back a future that remembers its cancellation; the test fires the wrappers by hand.
    private static final class FakeSeam implements OwnerLifetime.Seam {
        private final List<Runnable> wrappers = new CopyOnWriteArrayList<>();
        private final List<FakeFuture> futures = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
            var future = new FakeFuture();

            wrappers.add(wrapper);
            futures.add(future);

            return future;
        }
    }

    private static final class FakeFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
