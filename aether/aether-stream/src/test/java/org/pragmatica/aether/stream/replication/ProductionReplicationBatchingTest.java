// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #263 (streams overhaul 7.2, PR-4b): the production replication manager batches, and sends off the owner's append
/// section, in offset order, through the direct path's chunking and re-sending send.
///
/// The send executor and the batch one-shots are driven by hand: a lane run happens only when the test runs the
/// executor, and a window closes only when the test fires its captured one-shot. So "nothing was sent before the
/// executor ran" proves the publishing thread did not send, and every interleaving below is fixed, not raced.
class ProductionReplicationBatchingTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId REPLICA = new NodeId("replica-r");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final TimeSpan WINDOW = TimeSpan.timeSpan(1).millis();

    private final List<ReplicationMessage.ReplicateEvents> sent = new CopyOnWriteArrayList<>();
    private final Deque<Runnable> laneRuns = new ArrayDeque<>();
    private final Deque<Runnable> windows = new ArrayDeque<>();
    private final Executor manualExecutor = laneRuns::add;
    private final ReplicationBatcher.FlushScheduler capturingScheduler = (flush, _) -> {
        windows.add(flush);

        return SharedScheduler.schedule(() -> {}, TimeSpan.timeSpan(1).hours());
    };

    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, REPLICA);
    }

    @Test
    void isolatedEvent_isSentAtOnceAlone_onTheFlusherNotThePublishingThread() {
        var manager = manager(capturingTransport(), 100);

        manager.replicateEvent(STREAM, PARTITION, 0L, payload(0), 1L, Epoch.ZERO);

        assertThat(sent).as("the publishing thread sent nothing").isEmpty();

        runLanes();

        assertThat(sent).as("an isolated event does not wait for a window").hasSize(1);
        assertThat(sent.getFirst().fromOffset()).isZero();
        assertThat(sent.getFirst().payloads()).hasSize(1);
    }

    @Test
    void eventsInsideTheWindow_joinOneBatch_sentWhenTheWindowCloses() {
        var manager = manager(capturingTransport(), 100);

        publishSingles(manager, 0, 4);
        runLanes();

        assertThat(sent).as("only the window's first event left before the window closed").hasSize(1);

        closeWindow();
        runLanes();

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).fromOffset()).isEqualTo(1L);
        assertThat(sent.get(1).payloads()).hasSize(3);
    }

    @Test
    void sizeFlush_isSentOnTheFlusher_notOnThePublishingThread() {
        var manager = manager(capturingTransport(), 2);

        publishSingles(manager, 0, 3);

        assertThat(sent).as("neither the lone first event nor the full batch was sent by the publishing thread").isEmpty();

        runLanes();

        assertThat(offsetsSent()).containsExactly(List.of(0L), List.of(1L, 2L));
    }

    /// The race before #263: a window's one-shot drains batch A on a scheduler thread, then the publisher fills the
    /// successor batch to its size limit and sent it on its own thread, ahead of A. The lane receives A at its drain, so
    /// A goes first.
    @Test
    void windowFlushThenSizeFlushOfItsSuccessor_areSentInOffsetOrder() {
        var manager = manager(capturingTransport(), 2);

        publishSingles(manager, 0, 2);
        closeWindow();
        publishSingles(manager, 2, 5);
        runLanes();

        assertThat(offsetsSent()).containsExactly(List.of(0L), List.of(1L), List.of(2L), List.of(3L, 4L));
    }

    @Test
    void run_followsThePartitionsOpenBatch_asOneMessage() {
        var manager = manager(capturingTransport(), 100);

        publishSingles(manager, 0, 2);
        manager.replicateEvents(STREAM, PARTITION, 2L, List.of(payload(2), payload(3), payload(4)), List.of(2L, 3L, 4L), Epoch.ZERO);
        runLanes();

        assertThat(offsetsSent()).containsExactly(List.of(0L), List.of(1L), List.of(2L, 3L, 4L));
    }

    @Test
    void close_sendsTheOpenWindowsBatch() {
        var manager = manager(capturingTransport(), 100, Runnable::run);

        publishSingles(manager, 0, 3);
        manager.close();

        assertThat(offsetsSent()).containsExactly(List.of(0L), List.of(1L, 2L));
    }

    /// #577/#1596 on the lane: an event of a new owner epoch hands the open batch to the lane first, off the publishing
    /// thread, and each batch leaves stamped with its own epoch, in offset order.
    @Test
    void epochChange_handsTheOpenBatchToTheLane_firstAndStampedWithItsOwnEpoch() {
        var manager = manager(capturingTransport(), 100);
        var e1 = Epoch.epoch(0, 1, 0);
        var e2 = Epoch.epoch(0, 2, 0);

        manager.replicateEvent(STREAM, PARTITION, 0L, payload(0), 1L, e1);
        manager.replicateEvent(STREAM, PARTITION, 1L, payload(1), 2L, e1);
        manager.replicateEvent(STREAM, PARTITION, 2L, payload(2), 3L, e1);
        manager.replicateEvent(STREAM, PARTITION, 3L, payload(3), 4L, e2);

        assertThat(sent).as("the publishing thread sent nothing").isEmpty();

        runLanes();
        closeWindow();
        runLanes();

        assertThat(offsetsSent()).containsExactly(List.of(0L), List.of(1L, 2L), List.of(3L));
        assertThat(sent.stream().map(ReplicationMessage.ReplicateEvents::ownerEpoch).toList()).containsExactly(e1, e1, e2);
    }

    /// #2820 on the batch path: the production batcher sends through the direct path, so a batch the transport
    /// provably did not write is sent again. The batcher's own plain sender never re-sends.
    @Test
    void batchRefusedByTheTransport_isSentAgain() throws InterruptedException {
        var attempts = new AtomicInteger();
        var written = new CountDownLatch(1);
        ReplicationTransport refusesOnce = (target, message) -> attempts.incrementAndGet() == 1
                                                                ? Promise.success(new WriteOutcome.BackpressureRefused(target))
                                                                : written(written, target);
        var manager = manager(refusesOnce, 100);

        manager.replicateEvent(STREAM, PARTITION, 0L, payload(0), 1L, Epoch.ZERO);
        runLanes();

        assertThat(written.await(5, TimeUnit.SECONDS)).as("the refused batch was re-sent and written").isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }

    /// #1287 K3 on the batch path: a batch above the chunk budget leaves as consecutive chunks, in offset order.
    @Test
    void batchAboveTheChunkBudget_isSentAsConsecutiveChunks() {
        var manager = manager(capturingTransport(), 100);
        var half = new byte[(int) (DefaultReplicationManager.MAX_REPLICATE_MESSAGE_BYTES / 2)];

        manager.replicateEvent(STREAM, PARTITION, 0L, payload(0), 1L, Epoch.ZERO);
        manager.replicateEvent(STREAM, PARTITION, 1L, half, 2L, Epoch.ZERO);
        manager.replicateEvent(STREAM, PARTITION, 2L, half, 3L, Epoch.ZERO);
        closeWindow();
        runLanes();

        assertThat(sent.stream().map(ReplicationMessage.ReplicateEvents::fromOffset).toList()).containsExactly(0L, 1L, 2L);
    }

    private DefaultReplicationManager manager(ReplicationTransport transport, int maxEvents) {
        return manager(transport, maxEvents, manualExecutor);
    }

    /// `close()` waits for every batch in a lane, so a test that closes sends on the caller instead of by hand.
    private DefaultReplicationManager manager(ReplicationTransport transport, int maxEvents, Executor sendExecutor) {
        return new DefaultReplicationManager(OWNER,
                                             registry,
                                             transport,
                                             (_, _) -> -1L,
                                             sender -> ReplicationBatcher.adaptiveReplicationBatcher(sender,
                                                                                                    maxEvents,
                                                                                                    WINDOW,
                                                                                                    capturingScheduler,
                                                                                                    sendExecutor));
    }

    private ReplicationTransport capturingTransport() {
        return (target, message) -> written(sent, target, message);
    }

    private static Promise<WriteOutcome> written(List<ReplicationMessage.ReplicateEvents> sink,
                                                 NodeId target,
                                                 ReplicationMessage message) {
        sink.add((ReplicationMessage.ReplicateEvents) message);

        return Promise.success(new WriteOutcome.Sent(target));
    }

    private static Promise<WriteOutcome> written(CountDownLatch latch, NodeId target) {
        latch.countDown();

        return Promise.success(new WriteOutcome.Sent(target));
    }

    private static void publishSingles(ReplicationManager manager, long from, long to) {
        for (var offset = from; offset < to; offset++) {
            manager.replicateEvent(STREAM, PARTITION, offset, payload(offset), offset, Epoch.ZERO);
        }
    }

    private static byte[] payload(long offset) {
        return ("event-" + offset).getBytes();
    }

    private void runLanes() {
        while (!laneRuns.isEmpty()) {
            laneRuns.poll().run();
        }
    }

    private void closeWindow() {
        assertThat(windows).as("a window is open").isNotEmpty();
        windows.poll().run();
    }

    private List<List<Long>> offsetsSent() {
        var batches = new ArrayList<List<Long>>();

        sent.forEach(message -> batches.add(LongStream.range(message.fromOffset(),
                                                                              message.fromOffset() + message.payloads().size())
                                                                       .boxed()
                                                                       .toList()));

        return batches;
    }
}
