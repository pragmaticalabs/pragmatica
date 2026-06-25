// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.pragmatica.aether.stream.replication.ReplicationError.General.NOT_ENOUGH_REPLICAS;
import static org.pragmatica.aether.stream.replication.ReplicationError.General.REPLICATION_TIMEOUT;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


final class DefaultReplicationManager implements ReplicationManager {
    private static final TimeSpan DEFAULT_ACK_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private final NodeId governorId;
    private final ReplicaRegistry registry;
    private final ReplicationTransport transport;
    private final Option<ReplicationBatcher> batcher;
    private final EarliestRetainedOffset earliestRetained;
    private final ConcurrentHashMap<PendingAckKey, PendingAck> pendingAcks = new ConcurrentHashMap<>();

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

    private DefaultReplicationManager(NodeId governorId,
                                      ReplicaRegistry registry,
                                      ReplicationTransport transport,
                                      Option<ReplicationBatcher> batcher,
                                      EarliestRetainedOffset earliestRetained) {
        this.governorId = governorId;
        this.registry = registry;
        this.transport = transport;
        this.batcher = batcher;
        this.earliestRetained = earliestRetained;
    }

    @Contract
    @Override
    public void replicateEvent(String streamName,
                               int partition,
                               long offset,
                               byte[] payload,
                               long timestamp,
                               Epoch ownerEpoch) {
        batcher.onPresent(b -> b.add(streamName, partition, offset, payload, timestamp, ownerEpoch)).onEmpty(() -> replicateImmediately(streamName,
                                                                                                                                        partition,
                                                                                                                                        offset,
                                                                                                                                        payload,
                                                                                                                                        timestamp,
                                                                                                                                        ownerEpoch));
    }

    @Contract
    @Override
    public void handleAck(ReplicationMessage.ReplicateAck ack) {
        registry.updateWatermark(ack.streamName(),
                                 ack.partition(),
                                 ack.replicaId(),
                                 ack.confirmedOffset(),
                                 promotionState(ack));
        resolvePendingAck(ack.streamName(), ack.partition(), ack.replicaId(), ack.confirmedOffset());
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
    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        var targets = replicationTargets(streamName, partition);

        if (targets.size() < minAcks) {
            return NOT_ENOUGH_REPLICAS.promise();
        }
        // #262.3 (ack-before-register race): replication fires inside the publish onSuccess BEFORE the
        // caller awaits, so a fast peer ack can land in the registry before this await is registered.
        // The registry already records each replica's latest confirmed offset (updated in handleAck
        // ahead of resolvePendingAck), so seed the distinct-ack set with peers that ALREADY cover the
        // awaited offset — a race-won ack is honored instead of waiting out the 5s timeout.
        var alreadyAcked = peersAtOrAbove(targets, streamName, partition, offset);

        if (alreadyAcked.size() >= minAcks) {
            return Promise.success(unit());
        }

        return registerPendingAck(streamName, partition, offset, minAcks, alreadyAcked);
    }

    /// Distinct non-self replicas whose registry-recorded confirmed offset already reaches `offset`.
    private Set<NodeId> peersAtOrAbove(List<NodeId> targets, String streamName, int partition, long offset) {
        var byNode = registry.replicasFor(streamName, partition).stream().collect(Collectors.toMap(ReplicaDescriptor::nodeId,
                                                                                                   ReplicaDescriptor::confirmedOffset,
                                                                                                   Math::max));

        return targets.stream()
                      .filter(nodeId -> byNode.getOrDefault(nodeId, -1L) >= offset)
                      .collect(Collectors.toSet());
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

    private Promise<Unit> registerPendingAck(String streamName,
                                             int partition,
                                             long offset,
                                             int minAcks,
                                             Set<NodeId> alreadyAcked) {
        Promise<Unit> promise = Promise.promise();
        var key = new PendingAckKey(streamName, partition, offset);
        var acked = ConcurrentHashMap.<NodeId> newKeySet();

        acked.addAll(alreadyAcked);
        var pending = new PendingAck(promise, acked, minAcks);

        pendingAcks.put(key, pending);
        SharedScheduler.schedule(() -> timeoutPendingAck(key), DEFAULT_ACK_TIMEOUT);

        return promise;
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

        pendingAcks.entrySet().stream().filter(entry -> matchesAwait(entry.getKey(),
                                                                     streamName,
                                                                     partition,
                                                                     confirmedOffset)).forEach(entry -> recordAndResolve(entry.getKey(),
                                                                                                                         entry.getValue(),
                                                                                                                         replicaId));
    }

    private static boolean matchesAwait(PendingAckKey key, String streamName, int partition, long confirmedOffset) {
        return key.streamName()
                  .equals(streamName)
               && key.partition() == partition
               && key.offset() <= confirmedOffset;
    }

    private void recordAndResolve(PendingAckKey key, PendingAck pending, NodeId replicaId) {
        pending.ackedReplicas().add(replicaId);
        if (pending.ackedReplicas().size() >= pending.minAcks()) {
            pendingAcks.remove(key);
            pending.promise().resolve(success(unit()));
        }
    }

    private void timeoutPendingAck(PendingAckKey key) {
        option(pendingAcks.remove(key)).onPresent(pending -> pending.promise()
                                                                    .resolve(REPLICATION_TIMEOUT.result()));
    }

    record PendingAckKey(String streamName, int partition, long offset) {}

    /// `ackedReplicas` is the set of DISTINCT non-self replica identities that have acked at-or-past the
    /// awaited offset; the await resolves once it reaches `minAcks` (#262.1).
    record PendingAck(Promise<Unit> promise, Set<NodeId> ackedReplicas, int minAcks) {}
}
