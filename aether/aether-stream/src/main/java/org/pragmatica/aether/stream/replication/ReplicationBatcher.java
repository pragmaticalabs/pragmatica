// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

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
/// passed the closed check while `close()` runs may still append; `close()` drains it, or — if it lands after
/// the drain — its own one-shot flushes it, so an accepted event is never stranded.
public final class ReplicationBatcher implements AutoCloseable {
    static final int DEFAULT_MAX_EVENTS = 100;
    static final TimeSpan DEFAULT_MAX_DELAY = TimeSpan.timeSpan(1).millis();

    private final ConcurrentHashMap<PartitionKey, BatchAccumulator> accumulators = new ConcurrentHashMap<>();
    private final ReplicationTransport transport;
    private final ReplicaRegistry registry;
    private final NodeId governorId;
    private final int maxEvents;
    private final TimeSpan maxDelay;
    private final FlushScheduler flushScheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /// Schedules one batch's one-shot flush. Production uses `SharedScheduler.schedule`; tests substitute a
    /// capturing scheduler to observe cancellation.
    @FunctionalInterface
    interface FlushScheduler {
        ScheduledFuture<?> schedule(Runnable flush, TimeSpan delay);
    }

    private ReplicationBatcher(ReplicationTransport transport,
                               ReplicaRegistry registry,
                               NodeId governorId,
                               int maxEvents,
                               TimeSpan maxDelay,
                               FlushScheduler flushScheduler) {
        this.transport = transport;
        this.registry = registry;
        this.governorId = governorId;
        this.maxEvents = maxEvents;
        this.maxDelay = maxDelay;
        this.flushScheduler = flushScheduler;
    }

    public static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                        ReplicaRegistry registry,
                                                        NodeId governorId) {
        return new ReplicationBatcher(transport,
                                      registry,
                                      governorId,
                                      DEFAULT_MAX_EVENTS,
                                      DEFAULT_MAX_DELAY,
                                      SharedScheduler::schedule);
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
        return new ReplicationBatcher(transport, registry, governorId, maxEvents, maxDelay, flushScheduler);
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

    /// Cancels every pending one-shot and drains what was accepted; idempotent.
    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            accumulators.forEach(this::closePartition);
        }
    }

    @Contract
    void flushAll() {
        accumulators.forEach(this::flushPartition);
    }

    int accumulatorCount() {
        return accumulators.size();
    }

    Option<BatchAccumulator> accumulatorFor(String streamName, int partition) {
        return option(accumulators.get(partitionKey(streamName, partition)));
    }

    private Result<Unit> accept(PartitionKey key, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {
        var accumulator = accumulators.computeIfAbsent(key, _ -> new BatchAccumulator());
        var outcome = accumulator.add(offset, payload, timestamp, ownerEpoch, maxEvents);

        return outcome == AddOutcome.RETIRED
               ? retryOnFreshAccumulator(key, accumulator, offset, payload, timestamp, ownerEpoch)
               : success(onAccepted(key, accumulator, outcome));
    }

    private Unit onAccepted(PartitionKey key, BatchAccumulator accumulator, AddOutcome outcome) {
        return switch (outcome) {
            case OPENED -> scheduleFlush(key, accumulator);
            case FULL -> flushNow(key, accumulator);
            case APPENDED, RETIRED -> unit();
        };
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

    private void closePartition(PartitionKey key, BatchAccumulator accumulator) {
        accumulator.cancelFlush();
        flushPartition(key, accumulator);
    }

    private void flushPartition(PartitionKey key, BatchAccumulator accumulator) {
        var snapshot = accumulator.drain();

        accumulators.remove(key, accumulator);
        if (snapshot.isEmpty()) {
            return;
        }

        sendBatch(key, snapshot);
    }

    private void sendBatch(PartitionKey key, BatchSnapshot snapshot) {
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

        replicas.forEach(replica -> transport.send(replica, message));
    }

    enum AddOutcome {
        OPENED,
        APPENDED,
        FULL,
        RETIRED
    }

    /// One batch of one partition. Single-use: the first `drain` retires it, after which `add` refuses
    /// with `RETIRED` and the batcher evicts it.
    static final class BatchAccumulator {
        private final ReentrantLock lock = new ReentrantLock();
        private final List<byte[]> payloads = new ArrayList<>();
        private final List<Long> timestamps = new ArrayList<>();
        private long fromOffset = -1;
        private Epoch ownerEpoch = Epoch.ZERO;
        private boolean retired;
        private volatile Option<ScheduledFuture<?>> flush = none();

        @Contract
        void attachFlush(ScheduledFuture<?> scheduled) {
            flush = Option.some(scheduled);
        }

        @Contract
        void cancelFlush() {
            flush.onPresent(scheduled -> scheduled.cancel(false));
        }

        AddOutcome add(long offset, byte[] payload, long timestamp, Epoch ownerEpoch, int maxEvents) {
            lock.lock();
            try {
                if (retired) {
                    return AddOutcome.RETIRED;
                }

                var opened = payloads.isEmpty();

                if (opened) {
                    fromOffset = offset;
                }
                // A single owner accumulates a partition batch at one epoch; the latest stamp wins so
                // a flush always carries the most recent owner epoch the accumulated events were
                // published under (monotonic within an owner).
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
                var alreadyDrained = retired;

                retired = true;
                if (alreadyDrained || payloads.isEmpty()) {
                    return BatchSnapshot.EMPTY;
                }

                return new BatchSnapshot(fromOffset, payloads, timestamps, ownerEpoch);
            } finally {
                lock.unlock();
            }
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
