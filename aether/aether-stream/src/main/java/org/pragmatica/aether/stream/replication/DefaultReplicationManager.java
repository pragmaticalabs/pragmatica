// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.ReplicationError.General.NOT_ENOUGH_REPLICAS;
import static org.pragmatica.aether.stream.replication.ReplicationError.General.REPLICATION_TIMEOUT;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


final class DefaultReplicationManager implements ReplicationManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultReplicationManager.class);
    private static final TimeSpan DEFAULT_ACK_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final Runnable NO_OP = DefaultReplicationManager::noOp;

    private final NodeId governorId;
    private final ReplicaRegistry registry;
    private final ReplicationTransport transport;
    private final Option<ReplicationBatcher> batcher;
    private final EarliestRetainedOffset earliestRetained;

    /// Pending awaits indexed per partition, ordered by awaited offset (#1260): an ack for a partition
    /// walks only that partition's waiters at or below its confirmed offset. Each await is its own entry
    /// — the sequence in [WaiterKey] keeps two awaits on one offset from overwriting each other (#1259).
    private final ConcurrentHashMap<PartitionKey, ConcurrentSkipListMap<WaiterKey, PendingAck>> pendingAcks = new ConcurrentHashMap<>();

    private final AtomicLong waiterSequence = new AtomicLong();
    private final Runnable betweenSteps;
    private final Fn2<ScheduledFuture<?>, Runnable, TimeSpan> timerScheduler;
    /// Test probe (#1260). A `LongAdder`, not an `AtomicLong`: it is bumped per visited waiter on the ack
    /// hot path of every partition, and a single CAS'd counter would reintroduce cross-partition contention.
    private final LongAdder ackVisits = new LongAdder();
    private volatile Option<AckObserver> ackObserver = none();

    DefaultReplicationManager(NodeId governorId, ReplicaRegistry registry, ReplicationTransport transport) {
        this(governorId, registry, transport, none(), ALWAYS_PROMOTE);
    }

    DefaultReplicationManager(NodeId governorId,
                              ReplicaRegistry registry,
                              ReplicationTransport transport,
                              EarliestRetainedOffset earliestRetained) {
        this(governorId, registry, transport, none(), earliestRetained);
    }

    DefaultReplicationManager(NodeId governorId,
                              ReplicaRegistry registry,
                              ReplicationTransport transport,
                              ReplicationBatcher batcher) {
        this(governorId, registry, transport, some(batcher), ALWAYS_PROMOTE);
    }

    DefaultReplicationManager(NodeId governorId,
                              ReplicaRegistry registry,
                              ReplicationTransport transport,
                              ReplicationBatcher batcher,
                              EarliestRetainedOffset earliestRetained) {
        this(governorId, registry, transport, some(batcher), earliestRetained);
    }

    /// Test seam (#1259/#1260): `betweenSteps` runs inside [#awaitReplication] right after the registry
    /// snapshot, so a test can land an ack at exactly that point; `timerScheduler` replaces the shared
    /// scheduler so a test can observe whether the ack timer is cancelled. Production passes a no-op and
    /// [SharedScheduler#schedule].
    DefaultReplicationManager(NodeId governorId,
                              ReplicaRegistry registry,
                              ReplicationTransport transport,
                              Runnable betweenSteps,
                              Fn2<ScheduledFuture<?>, Runnable, TimeSpan> timerScheduler) {
        this(governorId, registry, transport, none(), ALWAYS_PROMOTE, betweenSteps, timerScheduler);
    }

    private DefaultReplicationManager(NodeId governorId,
                                      ReplicaRegistry registry,
                                      ReplicationTransport transport,
                                      Option<ReplicationBatcher> batcher,
                                      EarliestRetainedOffset earliestRetained) {
        this(governorId, registry, transport, batcher, earliestRetained, NO_OP, SharedScheduler::schedule);
    }

    private DefaultReplicationManager(NodeId governorId,
                                      ReplicaRegistry registry,
                                      ReplicationTransport transport,
                                      Option<ReplicationBatcher> batcher,
                                      EarliestRetainedOffset earliestRetained,
                                      Runnable betweenSteps,
                                      Fn2<ScheduledFuture<?>, Runnable, TimeSpan> timerScheduler) {
        this.governorId = governorId;
        this.registry = registry;
        this.transport = transport;
        this.batcher = batcher;
        this.earliestRetained = earliestRetained;
        this.betweenSteps = betweenSteps;
        this.timerScheduler = timerScheduler;
    }

    @Contract
    @Override
    public void replicateEvent(String streamName,
                               int partition,
                               long offset,
                               byte[] payload,
                               long timestamp,
                               Epoch ownerEpoch) {
        // FER: `replicateEvent` is fire-and-forget (void); the only refusal is BATCHER_CLOSED after close(),
        // when this manager is shutting down and no replica may be sent to — logged, then dropped.
        batcher.onPresent(b -> b.add(streamName, partition, offset, payload, timestamp, ownerEpoch)
                                .onFailure(cause -> log.warn("Event {}[{}]@{} not replicated: {}",
                                                             streamName,
                                                             partition,
                                                             offset,
                                                             cause.message())))
               .onEmpty(() -> replicateImmediately(streamName, partition, offset, payload, timestamp, ownerEpoch));
    }

    @Contract
    @Override
    public void handleAck(ReplicationMessage.ReplicateAck ack) {
        notifyObserver(ack);
        registry.updateWatermark(ack.streamName(),
                                 ack.partition(),
                                 ack.replicaId(),
                                 ack.confirmedOffset(),
                                 promotionState(ack));
        notifyObserver(ack);
        resolvePendingAck(ack.streamName(), ack.partition(), ack.replicaId(), ack.confirmedOffset());
    }

    /// Called BEFORE the registry update, so no waiter a registry read could resolve is resolved before the
    /// observer sees the ack; and again AFTER it, see [AckObserver].
    @Contract
    private void notifyObserver(ReplicationMessage.ReplicateAck ack) {
        ackObserver.onPresent(observer -> observer.acked(ack));
    }

    @Contract
    @Override
    public void observeAcks(AckObserver observer) {
        ackObserver = some(observer);
    }

    /// A live ack promotes the replica to CAUGHT_UP only when its confirmed offset reaches back to the
    /// owner's earliest retained offset — i.e. the replica's contiguous run (guaranteed by the receiver's
    /// offset verification, #260) covers the partition's retained history. A replica still below that
    /// floor (e.g. holding only the post-join suffix) stays SYNCING and is excluded from the read path
    /// and from being a backfill source until its backfill confirms coverage (#261).
    private ReplicationState promotionState(ReplicationMessage.ReplicateAck ack) {
        var floor = earliestRetained.earliestRetainedOffset(ack.streamName(), ack.partition());

        return ack.confirmedOffset() >= floor
               ? ReplicationState.CAUGHT_UP
               : ReplicationState.SYNCING;
    }

    @Override
    public ReplicaRegistry registry() {
        return registry;
    }

    @Contract
    @Override
    public void close() {
        batcher.onPresent(ReplicationBatcher::close);
    }

    private void replicateImmediately(String streamName,
                                      int partition,
                                      long offset,
                                      byte[] payload,
                                      long timestamp,
                                      Epoch ownerEpoch) {
        var replicas = replicationTargets(streamName, partition);

        if (replicas.isEmpty()) {
            return;
        }

        sendToAllReplicas(replicas, streamName, partition, offset, payload, timestamp, ownerEpoch);
    }

    /// The set of nodes an owner replicates a published event to: the registered replica set MINUS
    /// self. The HRW placement is owner-first, so the registry's replica set always contains the owner
    /// itself (#262.2/.5); replicating to self would loop the event back through `onReplicateEvents`
    /// (double-append) and counting a self-ack would let the owner satisfy `minAcks` without a single
    /// real peer copy. Both are eliminated by excluding self here and in {@link #awaitReplication}.
    private List<NodeId> replicationTargets(String streamName, int partition) {
        return registry.replicasFor(streamName, partition)
                       .stream()
                       .map(ReplicaDescriptor::nodeId)
                       .filter(nodeId -> !nodeId.equals(governorId))
                       .toList();
    }

    @Override
    public Result<Unit> ensureReplicaFloor(String streamName, int partition, int minAcks) {
        return replicationTargets(streamName, partition).size() < minAcks
               ? NOT_ENOUGH_REPLICAS.result()
               : Result.unitResult();
    }

    @Override
    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        var targets = replicationTargets(streamName, partition);

        if (targets.size() < minAcks) {
            return NOT_ENOUGH_REPLICAS.promise();
        }

        return registerThenReconcile(targets, streamName, partition, offset, minAcks);
    }

    /// #1259: REGISTER the waiter first, THEN seed it from the registry. #262.3 seeded from the registry
    /// so an ack that won the race against the await is honoured; but it sampled BEFORE registering, so an
    /// ack landing between the sample and the registration found no waiter and was reflected in neither —
    /// the await timed out on a replicated write. Registered first, every ack is caught by one of the two
    /// paths: it either finds the waiter in [#pendingAcks], or it reached the registry before the
    /// snapshot below read it. Both paths complete through [#complete], which resolves exactly once.
    private Promise<Unit> registerThenReconcile(List<NodeId> targets,
                                                String streamName,
                                                int partition,
                                                long offset,
                                                int minAcks) {
        var waiters = pendingAcks.computeIfAbsent(PartitionKey.partitionKey(streamName, partition),
                                                  _ -> new ConcurrentSkipListMap<>());
        var key = new WaiterKey(offset, waiterSequence.incrementAndGet());
        var pending = PendingAck.pendingAck(minAcks);

        waiters.put(key, pending);
        armTimer(pending,
                 timerScheduler.apply(() -> complete(waiters, key, pending, REPLICATION_TIMEOUT.result()),
                                      DEFAULT_ACK_TIMEOUT));
        var alreadyAcked = peersAtOrAbove(targets, streamName, partition, offset);

        betweenSteps.run();
        pending.ackedReplicas().addAll(alreadyAcked);
        resolveIfSatisfied(waiters, key, pending);

        return pending.promise();
    }

    /// Distinct non-self replicas whose registry-recorded confirmed offset already reaches `offset`.
    private Set<NodeId> peersAtOrAbove(List<NodeId> targets, String streamName, int partition, long offset) {
        var byNode = confirmedByNode(streamName, partition);

        return targets.stream()
                      .filter(nodeId -> byNode.getOrDefault(nodeId, -1L) >= offset)
                      .collect(Collectors.toSet());
    }

    /// Reads the SAME registry rows [#awaitReplication] seeds from, so an offset reported here is one an
    /// await for it would resolve on at once.
    @Override
    public long replicatedThrough(String streamName, int partition, int minAcks) {
        return minAcks <= 0
               ? Long.MAX_VALUE
               : minAcksConfirmedOffset(peerConfirmedDescending(streamName,
                                                                partition,
                                                                confirmedByNode(streamName, partition)),
                                        minAcks);
    }

    @Override
    public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
        return minAcks <= 0
               ? Long.MAX_VALUE
               : minAcksConfirmedOffset(peerConfirmedDescending(pending.streamName(),
                                                                pending.partition(),
                                                                withAck(pending)),
                                        minAcks);
    }

    /// The registry rows with `pending` overlaid — a row is only ever raised, as the registry update would.
    private Map<NodeId, Long> withAck(ReplicationMessage.ReplicateAck pending) {
        var byNode = new HashMap<>(confirmedByNode(pending.streamName(), pending.partition()));

        byNode.merge(pending.replicaId(), pending.confirmedOffset(), Math::max);

        return byNode;
    }

    /// The `minAcks`-th highest confirmed offset among the non-self replicas: every offset at or below it
    /// is covered by at least `minAcks` distinct peers.
    private static long minAcksConfirmedOffset(List<Long> descending, int minAcks) {
        return descending.size() >= minAcks
               ? descending.get(minAcks - 1)
               : -1L;
    }

    private List<Long> peerConfirmedDescending(String streamName, int partition, Map<NodeId, Long> byNode) {
        return replicationTargets(streamName, partition).stream()
                                 .map(nodeId -> byNode.getOrDefault(nodeId, -1L))
                                 .sorted(Comparator.reverseOrder())
                                 .toList();
    }

    private Map<NodeId, Long> confirmedByNode(String streamName, int partition) {
        return registry.replicasFor(streamName, partition)
                       .stream()
                       .collect(Collectors.toMap(ReplicaDescriptor::nodeId,
                                                 ReplicaDescriptor::confirmedOffset,
                                                 Math::max));
    }

    private void sendToAllReplicas(List<NodeId> replicas,
                                   String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp,
                                   Epoch ownerEpoch) {
        var message = replicateEvents(governorId,
                                      streamName,
                                      partition,
                                      offset,
                                      List.of(payload),
                                      List.of(timestamp),
                                      ownerEpoch);

        replicas.forEach(replica -> transport.send(replica, message));
    }

    /// Resolve every pending await whose awaited offset is at or below `confirmedOffset` for this
    /// `(stream, partition)`, counting DISTINCT acking replica identities (#262.1). A replica that has
    /// caught up PAST the awaited offset acks a higher watermark; an exact `(stream, partition, offset)`
    /// match would miss it and only the timeout would fire (m1) — matching `offset <= confirmedOffset`
    /// makes a higher ack correctly satisfy a lower-offset await. The acking replica's identity is added
    /// to the pending entry's distinct-ack set, so two acks from the SAME replica count once and only
    /// `minAcks` DISTINCT non-self replicas resolve the await.
    private void resolvePendingAck(String streamName, int partition, NodeId replicaId, long confirmedOffset) {
        if (replicaId.equals(governorId)) {
            return;  // a self-ack never counts toward minAcks (#262.2/.5)
        }

        option(pendingAcks.get(PartitionKey.partitionKey(streamName, partition))).onPresent(waiters -> recordAck(waiters,
                                                                                                                 replicaId,
                                                                                                                 confirmedOffset));
    }

    /// #1260: only this partition's waiters, and only those awaiting an offset the ack covers.
    private void recordAck(ConcurrentSkipListMap<WaiterKey, PendingAck> waiters,
                           NodeId replicaId,
                           long confirmedOffset) {
        waiters.headMap(WaiterKey.lastAt(confirmedOffset),
                        true)
               .forEach((key, pending) -> recordAndResolve(waiters, key, pending, replicaId));
    }

    private void recordAndResolve(ConcurrentSkipListMap<WaiterKey, PendingAck> waiters,
                                  WaiterKey key,
                                  PendingAck pending,
                                  NodeId replicaId) {
        ackVisits.increment();
        pending.ackedReplicas().add(replicaId);
        resolveIfSatisfied(waiters, key, pending);
    }

    private void resolveIfSatisfied(ConcurrentSkipListMap<WaiterKey, PendingAck> waiters,
                                    WaiterKey key,
                                    PendingAck pending) {
        if (pending.ackedReplicas().size() >= pending.minAcks()) {
            complete(waiters, key, pending, success(unit()));
        }
    }

    /// The single completion path (#1259): only the caller whose conditional remove succeeds resolves the
    /// promise, so an ack, the post-registration reconcile and the timeout can race without a double
    /// resolution. A completed await cancels its timer (#1260) rather than leaving it queued for 5 s.
    private void complete(ConcurrentSkipListMap<WaiterKey, PendingAck> waiters,
                          WaiterKey key,
                          PendingAck pending,
                          Result<Unit> outcome) {
        if (waiters.remove(key, pending)) {
            pending.promise().resolve(outcome);
            cancelTimer(pending);
        }
    }

    /// Store the timer, then cancel it at once if the await completed before the timer existed —
    /// completion and arming each check the other, so the timer is cancelled whichever runs last.
    private static void armTimer(PendingAck pending, ScheduledFuture<?> scheduled) {
        pending.timer().set(some(scheduled));
        if (pending.promise().isResolved()) {
            cancelTimer(pending);
        }
    }

    private static void cancelTimer(PendingAck pending) {
        pending.timer().get().onPresent(scheduled -> scheduled.cancel(false));
    }

    private static void noOp() {}

    /// Test probe (#1260): pending-await entries visited by ack resolution since construction.
    long ackVisitCount() {
        return ackVisits.sum();
    }

    /// Orders a partition's waiters by awaited offset; `sequence` makes each await a distinct entry.
    record WaiterKey(long offset, long sequence) implements Comparable<WaiterKey> {
        /// The greatest possible key at `offset` — the inclusive bound for "awaits at or below `offset`".
        static WaiterKey lastAt(long offset) {
            return new WaiterKey(offset, Long.MAX_VALUE);
        }

        @Override
        public int compareTo(WaiterKey other) {
            return offset != other.offset
                   ? Long.compare(offset, other.offset)
                   : Long.compare(sequence, other.sequence);
        }
    }

    /// `ackedReplicas` is the set of DISTINCT non-self replica identities that have acked at-or-past the
    /// awaited offset; the await resolves once it reaches `minAcks` (#262.1). `timer` holds the ack
    /// timeout so completion can cancel it (#1260).
    record PendingAck(Promise<Unit> promise,
                      Set<NodeId> ackedReplicas,
                      int minAcks,
                      AtomicReference<Option<ScheduledFuture<?>>> timer) {
        static PendingAck pendingAck(int minAcks) {
            return new PendingAck(Promise.promise(),
                                  ConcurrentHashMap.newKeySet(),
                                  minAcks,
                                  new AtomicReference<>(none()));
        }
    }
}
