// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Batches owner-side events per partition and flushes each batch when it reaches `maxEvents` or when
/// `maxDelay` has elapsed since the batch's first event, whichever comes first.
///
/// #1246: there is no periodic scan. The first event of a batch schedules a one-shot flush for that batch,
/// and the first drain of an accumulator retires it and evicts it from the map, so an accumulator exists
/// only while its partition has a pending batch. Idle or released partitions cost nothing — no lock
/// acquisition, no map entry. An `add` racing with the drain sees the accumulator retired and retries on
/// a fresh one, so no event is lost to eviction. Every event is still flushed within `maxDelay` of its
/// `add`, the same bound the former fixed-rate scan gave. The trade: a lone event now waits the full
/// `maxDelay` (mean latency up from about `maxDelay/2` under the scan); the bound is unchanged.
///
/// `close()` ends the batcher's life (#1246 review N6): it cancels every pending one-shot and drains what was
/// accepted, and any later `add` is refused with [ReplicationError.Lifecycle#BATCHER_CLOSED]. An `add` that
/// passed the closed check while `close()` runs either appends — `close()` drains it, or, if it opened a fresh
/// accumulator after the drain pass, its own one-shot flushes it — or finds its accumulator already retired
/// and is refused by the retry's re-check of `closed`. Refused, not stranded: no accepted event is lost.
///
/// #1380: `close()` is also a barrier for flushes already in flight. A one-shot body runs on a scheduler
/// thread and `cancel(false)` cannot stop one that has started; a body that has already drained its
/// accumulator holds the only copy of that batch while `close()`'s own drain of it yields `EMPTY`. So
/// `close()` waits, after its drain pass, for every flush that entered `flushPartition` to leave it — bounded
/// by `closeBarrier` (default [#DEFAULT_CLOSE_BARRIER]). At the bound, or if the closing thread is
/// interrupted, it logs a WARN naming the number still in flight and returns; the flushes still complete on
/// their own threads (late, never lost), and `close()` never hangs on a transport that does not return.
///
/// #263 (overhaul 7.2 item 3): a drained batch is not sent by the thread that drained it. The drain hands the batch to
/// its partition's send lane UNDER the accumulator lock, so the lane receives a partition's batches in the order their
/// accumulators were retired, which is offset order: a successor accumulator can only receive events after its
/// predecessor is retired. One runner at a time drains a lane on the send executor and calls the [BatchSender]. So a
/// size flush, an epoch flush or a run no longer encode and send on the publishing thread (inside the partition's
/// append section), and a one-shot flush can no longer be overtaken by its successor's size flush. Lanes are a fixed
/// stripe array keyed by partition hash, so an idle partition still costs no map entry. A batch waiting in, or being
/// sent by, a lane counts as in flight for the `close()` barrier.
///
/// Adaptive first event (overhaul 7.2 item 2, production manager only): when `sendFirstAtOnce` is set, the event that
/// opens an accumulator is handed to the lane at once, alone, and the accumulator stays open as a window of
/// `maxDelay` that later events join. So an isolated publish pays no batching delay, and a partition under load sends
/// one lone event plus one batch per window. The flush rules of the window are the ones above.
public final class ReplicationBatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReplicationBatcher.class);
    static final int DEFAULT_MAX_EVENTS = 100;
    static final TimeSpan DEFAULT_MAX_DELAY = TimeSpan.timeSpan(1).millis();
    static final TimeSpan DEFAULT_CLOSE_BARRIER = TimeSpan.timeSpan(5).seconds();
    private static final long BARRIER_POLL_NANOS = 100_000L;
    /// Send lanes of a production batcher. A GUESS: one per core, so sends of distinct partitions proceed in parallel
    /// up to the core count; what would settle it is the flusher's CPU share under a many-partition load.
    static final int SEND_LANES = Math.max(2,
                                           Runtime.getRuntime().availableProcessors());

    /// The production send executor: a virtual thread per lane run, never the publishing thread.
    private static final Executor FLUSHER_THREADS = runnable -> Thread.ofVirtual()
                                                                      .name("replication-flusher")
                                                                      .start(runnable);

    /// The standalone factories' executor: a lane run happens on the thread that handed it the batch, after the
    /// accumulator lock is released, as every send did before #263.
    private static final Executor CALLER_THREAD = Runnable::run;

    private final ConcurrentHashMap<PartitionKey, BatchAccumulator> accumulators = new ConcurrentHashMap<>();
    private final BatchSender sender;
    private final int maxEvents;
    private final TimeSpan maxDelay;
    private final TimeSpan closeBarrier;
    private final FlushScheduler flushScheduler;
    private final boolean sendFirstAtOnce;
    private final SendLane[] lanes;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger inFlightFlushes = new AtomicInteger();

    /// Schedules one batch's one-shot flush. Production uses `SharedScheduler.schedule`; tests substitute a
    /// capturing scheduler to observe cancellation.
    @FunctionalInterface
    interface FlushScheduler {
        ScheduledFuture<?> schedule(Runnable flush, TimeSpan delay);
    }

    /// Sends one drained batch of one partition to its replicas. The production manager passes its own chunking,
    /// re-sending send path (`DefaultReplicationManager#replicateImmediately`, #1287 K3 and #2820); the standalone
    /// factories pass [#transportSender], one plain send per replica.
    @FunctionalInterface
    interface BatchSender {
        @Contract
        void deliver(PartitionKey key, BatchSnapshot snapshot);
    }

    private ReplicationBatcher(BatchSender sender,
                               int maxEvents,
                               TimeSpan maxDelay,
                               TimeSpan closeBarrier,
                               FlushScheduler flushScheduler,
                               boolean sendFirstAtOnce,
                               Executor sendExecutor,
                               int laneCount) {
        this.sender = sender;
        this.maxEvents = maxEvents;
        this.maxDelay = maxDelay;
        this.closeBarrier = closeBarrier;
        this.flushScheduler = flushScheduler;
        this.sendFirstAtOnce = sendFirstAtOnce;
        this.lanes = new SendLane[laneCount];
        for (var i = 0; i < laneCount; i++) {
            lanes[i] = new SendLane(sendExecutor);
        }
    }

    public static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                        ReplicaRegistry registry,
                                                        NodeId governorId) {
        return replicationBatcher(transport,
                                  registry,
                                  governorId,
                                  DEFAULT_MAX_EVENTS,
                                  DEFAULT_MAX_DELAY,
                                  DEFAULT_CLOSE_BARRIER,
                                  SharedScheduler::schedule);
    }

    /// The production batcher (#263): batches are sent by `sender` on flusher threads, [#SEND_LANES] lanes, and the
    /// first event of an idle partition goes out at once (adaptive first event).
    static ReplicationBatcher adaptiveReplicationBatcher(BatchSender sender, int maxEvents, TimeSpan maxDelay) {
        return adaptiveReplicationBatcher(sender, maxEvents, maxDelay, SharedScheduler::schedule, FLUSHER_THREADS);
    }

    /// Test seam of [#adaptiveReplicationBatcher(BatchSender, int, TimeSpan)]: the one-shot scheduler and the send
    /// executor are the caller's.
    static ReplicationBatcher adaptiveReplicationBatcher(BatchSender sender,
                                                         int maxEvents,
                                                         TimeSpan maxDelay,
                                                         FlushScheduler flushScheduler,
                                                         Executor sendExecutor) {
        return new ReplicationBatcher(sender,
                                      maxEvents,
                                      maxDelay,
                                      DEFAULT_CLOSE_BARRIER,
                                      flushScheduler,
                                      true,
                                      sendExecutor,
                                      SEND_LANES);
    }

    public static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                        ReplicaRegistry registry,
                                                        NodeId governorId,
                                                        int maxEvents,
                                                        TimeSpan maxDelay) {
        return replicationBatcher(transport, registry, governorId, maxEvents, maxDelay, SharedScheduler::schedule);
    }

    static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                 ReplicaRegistry registry,
                                                 NodeId governorId,
                                                 int maxEvents,
                                                 TimeSpan maxDelay,
                                                 FlushScheduler flushScheduler) {
        return replicationBatcher(transport,
                                  registry,
                                  governorId,
                                  maxEvents,
                                  maxDelay,
                                  DEFAULT_CLOSE_BARRIER,
                                  flushScheduler);
    }

    /// Test seam: `closeBarrier` bounds how long `close()` waits for in-flight flushes.
    static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                 ReplicaRegistry registry,
                                                 NodeId governorId,
                                                 int maxEvents,
                                                 TimeSpan maxDelay,
                                                 TimeSpan closeBarrier,
                                                 FlushScheduler flushScheduler) {
        return new ReplicationBatcher(transportSender(transport, registry, governorId),
                                      maxEvents,
                                      maxDelay,
                                      closeBarrier,
                                      flushScheduler,
                                      false,
                                      CALLER_THREAD,
                                      SEND_LANES);
    }

    /// One plain `ReplicateEvents` per replica other than `governorId`, no chunking and no re-send. Only the standalone
    /// factories use it; the production manager sends through its own path (see [BatchSender]).
    static BatchSender transportSender(ReplicationTransport transport, ReplicaRegistry registry, NodeId governorId) {
        return (key, snapshot) -> sendBatch(transport, registry, governorId, key, snapshot);
    }

    /// Hand a run of consecutive events (`payloads.get(i)` at `fromOffset + i`) to the partition's send lane as one
    /// batch, behind the partition's open batch, which is flushed first so the lane keeps offset order. Refused with
    /// [ReplicationError.Lifecycle#BATCHER_CLOSED] once `close()` has run.
    public Result<Unit> addRun(String streamName,
                               int partition,
                               long fromOffset,
                               List<byte[]> payloads,
                               List<Long> timestamps,
                               Epoch ownerEpoch) {
        return closed.get()
               ? ReplicationError.Lifecycle.BATCHER_CLOSED.result()
               : success(acceptRun(partitionKey(streamName, partition),
                                   new BatchSnapshot(fromOffset,
                                                     payloads.stream().map(byte[]::clone).toList(),
                                                     List.copyOf(timestamps),
                                                     ownerEpoch)));
    }

    /// Accept one event into its partition's batch, or refuse it with
    /// [ReplicationError.Lifecycle#BATCHER_CLOSED] once `close()` has run.
    public Result<Unit> add(String streamName,
                            int partition,
                            long offset,
                            byte[] payload,
                            long timestamp,
                            Epoch ownerEpoch) {
        return closed.get()
               ? ReplicationError.Lifecycle.BATCHER_CLOSED.result()
               : accept(partitionKey(streamName, partition), offset, payload, timestamp, ownerEpoch);
    }

    /// Cancels every pending one-shot, drains what was accepted, then waits (bounded by `closeBarrier`) for
    /// flushes already in flight to hand their batch to the transport; idempotent.
    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            accumulators.forEach(this::cancelAndFlush);
            awaitInFlightFlushes();
        }
    }

    /// Drains every pending batch now. The one-shots those batches armed are cancelled rather than left to
    /// fire as no-ops on the process-wide scheduler.
    @Contract
    void flushAll() {
        accumulators.forEach(this::cancelAndFlush);
    }

    int inFlightFlushes() {
        return inFlightFlushes.get();
    }

    int accumulatorCount() {
        return accumulators.size();
    }

    Option<BatchAccumulator> accumulatorFor(String streamName, int partition) {
        return option(accumulators.get(partitionKey(streamName, partition)));
    }

    private Result<Unit> accept(PartitionKey key, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {
        var accumulator = accumulators.computeIfAbsent(key, this::newAccumulator);
        var outcome = accumulator.add(offset, payload, timestamp, ownerEpoch, maxEvents, sendFirstAtOnce);

        return switch (outcome) {
            case RETIRED -> retryOnFreshAccumulator(key, accumulator, offset, payload, timestamp, ownerEpoch);
            case EPOCH_CHANGED -> flushThenRetry(key, accumulator, offset, payload, timestamp, ownerEpoch);
            case OPENED, SENT_AT_ONCE, APPENDED, FULL -> success(onAccepted(key, accumulator, outcome));
        };
    }

    private BatchAccumulator newAccumulator(PartitionKey key) {
        return new BatchAccumulator(key, laneFor(key));
    }

    private SendLane laneFor(PartitionKey key) {
        return lanes[Math.floorMod(key.hashCode(), lanes.length)];
    }

    /// The partition's open batch, if any, is flushed to the lane first; the run follows it, so the lane holds the
    /// partition's events in offset order. Callers hand a partition's events in offset order (the owner's append
    /// section); a one-shot that drains the open batch concurrently has handed it to the lane under the accumulator
    /// lock before `cancelAndFlush` can find it retired.
    private Unit acceptRun(PartitionKey key, BatchSnapshot run) {
        option(accumulators.get(key)).onPresent(open -> cancelAndFlush(key, open));
        var lane = laneFor(key);

        lane.offer(key, run);
        lane.start();

        return unit();
    }

    /// #1577: the open batch was accumulated under another owner epoch. It is flushed -- handed to the send lane
    /// before the event of the new epoch enters any batch -- and the event goes to a fresh accumulator, so a batch
    /// never spans two epochs and its stamp is the epoch of every event in it. A replica records the batch epoch as the
    /// provenance of the batch's first offset (#1596); a stamp covering earlier-epoch events would mislabel them and
    /// make a healthy partition read as divergent after every failover.
    private Result<Unit> flushThenRetry(PartitionKey key,
                                        BatchAccumulator stale,
                                        long offset,
                                        byte[] payload,
                                        long timestamp,
                                        Epoch ownerEpoch) {
        cancelAndFlush(key, stale);

        return retryOnFreshAccumulator(key, stale, offset, payload, timestamp, ownerEpoch);
    }

    private Unit onAccepted(PartitionKey key, BatchAccumulator accumulator, AddOutcome outcome) {
        return switch (outcome) {
            case OPENED -> scheduleFlush(key, accumulator);
            case SENT_AT_ONCE -> sentAtOnce(key, accumulator);
            case FULL -> flushNow(key, accumulator);
            case APPENDED, RETIRED, EPOCH_CHANGED -> unit();
        };
    }

    /// The opening event is already in the lane (handed over under the accumulator lock); start the lane and open the
    /// window: the accumulator's one-shot closes it `maxDelay` later.
    private Unit sentAtOnce(PartitionKey key, BatchAccumulator accumulator) {
        laneFor(key).start();

        return scheduleFlush(key, accumulator);
    }

    private Unit scheduleFlush(PartitionKey key, BatchAccumulator accumulator) {
        accumulator.attachFlush(flushScheduler.schedule(() -> flushPartition(key, accumulator), maxDelay));

        return unit();
    }

    private Unit flushNow(PartitionKey key, BatchAccumulator accumulator) {
        flushPartition(key, accumulator);

        return unit();
    }

    /// The accumulator was drained (and retired) between lookup and append. Evict it and append to a fresh
    /// accumulator instead. The eviction here is load-bearing, not merely idempotent with the drainer's own
    /// `remove` (#1246 review N4): a drainer descheduled between `drain()` and its `remove` leaves the retired
    /// accumulator in the map, and without this eviction every retry would find it again and recurse through
    /// `add` until the drainer runs — unbounded recursion, a `StackOverflowError` if it never does. Pinned by
    /// `ReplicationBatcherTest.RetryPath`.
    private Result<Unit> retryOnFreshAccumulator(PartitionKey key,
                                                 BatchAccumulator retired,
                                                 long offset,
                                                 byte[] payload,
                                                 long timestamp,
                                                 Epoch ownerEpoch) {
        accumulators.remove(key, retired);

        return add(key.streamName(), key.partition(), offset, payload, timestamp, ownerEpoch);
    }

    private void cancelAndFlush(PartitionKey key, BatchAccumulator accumulator) {
        accumulator.cancelFlush();
        flushPartition(key, accumulator);
    }

    /// Counted in flight from before the drain until the batch is in the lane, where the lane's own count takes over
    /// until the send returns, so `close()` can see a flush that already owns a batch it will never find in the map.
    private void flushPartition(PartitionKey key, BatchAccumulator accumulator) {
        inFlightFlushes.incrementAndGet();
        try {
            accumulator.drainToLane();
            accumulators.remove(key, accumulator);
        } finally {
            inFlightFlushes.decrementAndGet();
        }

        laneFor(key).start();
    }

    /// Returns when no flush is in flight, or at the bound, or when the closing thread is interrupted (the
    /// interrupt flag is left set; a parked-then-interrupted wait would otherwise spin to the deadline, #914).
    private void awaitInFlightFlushes() {
        var deadline = System.nanoTime() + closeBarrier.nanos();

        while (inFlightFlushes.get() > 0) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("ReplicationBatcher.close() interrupted with {} flush(es) still in flight; they complete on their own threads",
                         inFlightFlushes.get());

                return;
            }

            if (System.nanoTime() - deadline >= 0) {
                log.warn("ReplicationBatcher.close() gave up after {} ms with {} flush(es) still in flight; they complete on their own threads",
                         closeBarrier.millis(),
                         inFlightFlushes.get());

                return;
            }

            LockSupport.parkNanos(this, BARRIER_POLL_NANOS);
        }
    }

    @Contract
    private static void sendBatch(ReplicationTransport transport,
                                  ReplicaRegistry registry,
                                  NodeId governorId,
                                  PartitionKey key,
                                  BatchSnapshot snapshot) {
        // #262.2/.5: the HRW replica set is owner-first, so it contains self — exclude it. Replicating
        // a batch to self would loop it back through onReplicateEvents (double-append).
        var replicas = registry.replicasFor(key.streamName(),
                                            key.partition())
                               .stream()
                               .map(ReplicaDescriptor::nodeId)
                               .filter(nodeId -> !nodeId.equals(governorId))
                               .toList();

        if (replicas.isEmpty()) {
            return;
        }

        var message = replicateEvents(governorId,
                                      key.streamName(),
                                      key.partition(),
                                      snapshot.fromOffset(),
                                      snapshot.payloads(),
                                      snapshot.timestamps(),
                                      snapshot.ownerEpoch());
        // #2820: no re-send of a provably-not-sent outcome here, unlike DefaultReplicationManager#sendChunk: this sender
        // serves only the standalone factories; the production manager (#263) passes its own re-sending sender.
        replicas.forEach(replica -> transport.send(replica, message, DefaultReplicationManager.DEFAULT_ACK_TIMEOUT));
    }

    enum AddOutcome {
        OPENED,
        /// The event opened the accumulator and was handed to the lane alone (adaptive first event, #263).
        SENT_AT_ONCE,
        APPENDED,
        FULL,
        RETIRED,
        /// The batch is open under another owner epoch; nothing was added (#1577).
        EPOCH_CHANGED
    }

    private record QueuedBatch(PartitionKey key, BatchSnapshot snapshot) {}

    /// One stripe of send work (#263): FIFO, drained by at most one runner at a time on the send executor. `offer` may
    /// run under an accumulator lock; `start` must not, since with the caller-thread executor it sends.
    private final class SendLane {
        private final ConcurrentLinkedQueue<QueuedBatch> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Executor executor;

        private SendLane(Executor executor) {
            this.executor = executor;
        }

        @Contract
        void offer(PartitionKey key, BatchSnapshot snapshot) {
            inFlightFlushes.incrementAndGet();
            queue.add(new QueuedBatch(key, snapshot));
        }

        @Contract
        void start() {
            if (!queue.isEmpty() && running.compareAndSet(false, true)) {
                executor.execute(this::run);
            }
        }

        /// Re-checks the queue after releasing `running`: a batch offered while this runner was finishing found it
        /// running and did not start another, so this runner takes it.
        private void run() {
            do {
                try {
                    sendQueued();
                } finally {
                    running.set(false);
                }
            } while (!queue.isEmpty() && running.compareAndSet(false, true));
        }

        private void sendQueued() {
            for (var queued = queue.poll(); queued != null; queued = queue.poll()) {
                sendOne(queued);
            }
        }

        /// FER: a sender that throws loses this one batch, logged; the lane goes on with the next, so one bad batch
        /// cannot stall the partitions behind it. The replica's gap rule and backfill repair the lost range, and the
        /// owner's ack wait times out as for any lost frame (overhaul 7.2, failure modes).
        private void sendOne(QueuedBatch queued) {
            try {
                sender.deliver(queued.key(), queued.snapshot());
            } catch (RuntimeException failure) {
                log.warn("Replication batch {}[{}]@{} not sent: {}",
                         queued.key().streamName(),
                         queued.key().partition(),
                         queued.snapshot().fromOffset(),
                         failure.toString());
            } finally {
                inFlightFlushes.decrementAndGet();
            }
        }
    }

    /// One batch of one partition. Single-use: the first `drain` retires it, after which `add` refuses
    /// with `RETIRED` and the batcher evicts it.
    static final class BatchAccumulator {
        private final ReentrantLock lock = new ReentrantLock();
        private final List<byte[]> payloads = new ArrayList<>();
        private final List<Long> timestamps = new ArrayList<>();
        private final PartitionKey key;
        private final SendLane lane;
        private long fromOffset = -1;
        private Epoch ownerEpoch = Epoch.ZERO;
        private boolean started;
        private boolean retired;
        private volatile Option<ScheduledFuture<?>> flush = none();

        private BatchAccumulator(PartitionKey key, SendLane lane) {
            this.key = key;
            this.lane = lane;
        }

        @Contract
        void attachFlush(ScheduledFuture<?> scheduled) {
            flush = Option.some(scheduled);
        }

        @Contract
        void cancelFlush() {
            flush.onPresent(scheduled -> scheduled.cancel(false));
        }

        AddOutcome add(long offset,
                       byte[] payload,
                       long timestamp,
                       Epoch ownerEpoch,
                       int maxEvents,
                       boolean sendFirstAtOnce) {
            lock.lock();
            try {
                if (retired) {
                    return AddOutcome.RETIRED;
                }

                var opened = !started;
                var empty = payloads.isEmpty();

                started = true;
                if (!empty && !this.ownerEpoch.equals(ownerEpoch)) {
                    return AddOutcome.EPOCH_CHANGED;
                }

                if (opened && sendFirstAtOnce) {
                    lane.offer(key,
                               new BatchSnapshot(offset,
                                                 List.of(payload.clone()),
                                                 List.of(timestamp),
                                                 ownerEpoch));

                    return AddOutcome.SENT_AT_ONCE;
                }

                if (empty) {
                    fromOffset = offset;
                }
                // One batch, one epoch (#1577): the stamp is set when the batch opens and every later event
                // matches it, so the flush carries the epoch every accumulated event was published under.
                this.ownerEpoch = ownerEpoch;
                payloads.add(payload.clone());
                timestamps.add(timestamp);

                return outcome(opened, maxEvents);
            } finally {
                lock.unlock();
            }
        }

        /// Retires the accumulator; once retired its lists are never mutated again, so the snapshot
        /// shares them instead of copying. Only the first drain yields the batch — a later drain (the
        /// batch's one-shot firing after a size flush already took it) yields `EMPTY`, never a resend.
        BatchSnapshot drain() {
            lock.lock();
            try {
                return retire();
            } finally {
                lock.unlock();
            }
        }

        /// [#drain], and the batch, if any, handed to the partition's send lane under the same lock (#263): a successor
        /// accumulator receives events only after this one is retired, so the lane receives them after this batch.
        @Contract
        void drainToLane() {
            lock.lock();
            try {
                var snapshot = retire();

                if (!snapshot.isEmpty()) {
                    lane.offer(key, snapshot);
                }
            } finally {
                lock.unlock();
            }
        }

        private BatchSnapshot retire() {
            var alreadyDrained = retired;

            retired = true;
            if (alreadyDrained || payloads.isEmpty()) {
                return BatchSnapshot.EMPTY;
            }

            return new BatchSnapshot(fromOffset, payloads, timestamps, ownerEpoch);
        }

        private AddOutcome outcome(boolean opened, int maxEvents) {
            if (payloads.size() >= maxEvents) {
                return AddOutcome.FULL;
            }

            return opened
                   ? AddOutcome.OPENED
                   : AddOutcome.APPENDED;
        }
    }

    record BatchSnapshot(long fromOffset, List<byte[]> payloads, List<Long> timestamps, Epoch ownerEpoch) {
        static final BatchSnapshot EMPTY = new BatchSnapshot(-1, List.of(), List.of(), Epoch.ZERO);

        boolean isEmpty() {
            return payloads.isEmpty();
        }
    }
}
