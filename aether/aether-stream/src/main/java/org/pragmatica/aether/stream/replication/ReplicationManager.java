// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.aether.stream.replication.ReplicationBatcher.replicationBatcher;
import static org.pragmatica.lang.Promise.success;
import static org.pragmatica.lang.Unit.unit;


public interface ReplicationManager extends AutoCloseable {
    /// Owner-side view of a partition's earliest retained offset (its local ring tail). The owner's
    /// live-ack path consults it to promote an acking replica to CAUGHT_UP only when its confirmed
    /// offset reaches back to the partition's retained history, instead of on any ack (#261). The
    /// default ({@link #ALWAYS_PROMOTE}) reports `-1`, preserving the promote-on-any-ack behavior where
    /// no partition view is wired (tests, minimal runtimes).
    @FunctionalInterface
    interface EarliestRetainedOffset {
        long earliestRetainedOffset(String streamName, int partition);
    }

    EarliestRetainedOffset ALWAYS_PROMOTE = (_, _) -> - 1L;

    /// Owner-side observer of the replica-ack stream (#1235). It is told of each ack BEFORE the registry
    /// records it (ruling after the #1279 review, N2): a waiter can be resolved from a registry read, so an
    /// observer that ran after the update could run after that waiter. It therefore reads the ack through
    /// [#replicatedThrough(ReplicationMessage.ReplicateAck, int)], which overlays the ack on the registry.
    /// It is told a second time after the update. That call only moves visibility forward, and it closes
    /// the race with a concurrent owner fsync that read the registry before this ack landed.
    @FunctionalInterface
    interface AckObserver {
        @Contract
        void acked(ReplicationMessage.ReplicateAck ack);
    }

    /// Replicate one accepted owner-local append to the partition's replica set, carrying the owner's
    /// `ownerEpoch` fencing token (#345 item 1d-ii) so each replica fences a deposed owner's batch
    /// against its own partition high-water before landing it.
    @Contract
    void replicateEvent(String streamName,
                        int partition,
                        long offset,
                        byte[] payload,
                        long timestamp,
                        Epoch ownerEpoch);

    @Contract
    void handleAck(ReplicationMessage.ReplicateAck ack);

    ReplicaRegistry registry();
    Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks);
    /// The highest offset at least `minAcks` DISTINCT non-self replicas have acknowledged for
    /// `(stream, partition)` — the non-blocking reading of the same condition [#awaitReplication] waits
    /// for (#1235). `-1` when fewer replicas than `minAcks` have acknowledged anything; [Long#MAX_VALUE]
    /// when `minAcks <= 0`, since no ack is required.
    long replicatedThrough(String streamName, int partition, int minAcks);
    /// [#replicatedThrough(String, int, int)] as it will read once `pending` is recorded: the ack is
    /// overlaid on the registry rows (never lowering a row), so an [AckObserver] running before the
    /// registry update sees the ack it is being told about.
    long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks);

    /// Install the single [AckObserver] (#1235). The partition manager installs itself at construction.
    @Contract
    void observeAcks(AckObserver observer);

    /// Pre-append floor check (#1236): whether the partition's replica set can POSSIBLY deliver
    /// `minAcks` distinct non-self acks, answered BEFORE anything is appended. A publish refused here
    /// is genuinely not in the log, so `NOT_ENOUGH_REPLICAS` is a clean failure; the same verdict
    /// reached inside [#awaitReplication] comes after the append and is not. The default admits every
    /// publish — the no-op manager has no replica set to fall short of, matching its always-succeeding
    /// [#awaitReplication].
    default Result<Unit> ensureReplicaFloor(String streamName, int partition, int minAcks) {
        return Result.unitResult();
    }

    /// Fail every pending [#awaitReplication] for `(stream, partition)` whose offset lies in `[fromOffset,
    /// toOffset]` with `causeFor(offset)` (#1352). The owner's ring calls this, through the partition manager,
    /// when DROP_OLDEST evicts events above the visible position: they were never acknowledged and are no
    /// longer in the log, so their publishers learn a definite failure now rather than a `REPLICATION_TIMEOUT`
    /// (outcome-unknown) 5 s later. An await already resolved by an ack is untouched. The default is a no-op:
    /// the no-op manager registers no awaits.
    @Contract
    default void failPendingAcks(String streamName,
                                 int partition,
                                 long fromOffset,
                                 long toOffset,
                                 Fn1<Cause, Long> causeFor) {}

    @Contract
    @Override
    default void close() {}

    ReplicationManager NONE = noOpReplicationManager();

    static ReplicationManager replicationManager(NodeId governorId,
                                                 ReplicaRegistry registry,
                                                 ReplicationTransport transport) {
        return new DefaultReplicationManager(governorId, registry, transport);
    }

    /// Owner-aware factory: `earliestRetained` lets the live-ack path gate the SYNCING→CAUGHT_UP
    /// promotion on genuine history coverage (#261).
    static ReplicationManager replicationManager(NodeId governorId,
                                                 ReplicaRegistry registry,
                                                 ReplicationTransport transport,
                                                 EarliestRetainedOffset earliestRetained) {
        return new DefaultReplicationManager(governorId, registry, transport, earliestRetained);
    }

    static ReplicationManager replicationManager(NodeId governorId, ReplicaRegistry registry) {
        return replicationManager(governorId, registry, ReplicationTransport.NOOP);
    }

    static ReplicationManager batchingReplicationManager(NodeId governorId,
                                                         ReplicaRegistry registry,
                                                         ReplicationTransport transport) {
        var batcher = replicationBatcher(transport, registry, governorId);

        return new DefaultReplicationManager(governorId, registry, transport, batcher);
    }

    static ReplicationManager batchingReplicationManager(NodeId governorId,
                                                         ReplicaRegistry registry,
                                                         ReplicationTransport transport,
                                                         int maxEvents,
                                                         TimeSpan maxDelay) {
        var batcher = replicationBatcher(transport, registry, governorId, maxEvents, maxDelay);

        return new DefaultReplicationManager(governorId, registry, transport, batcher);
    }

    private static ReplicationManager noOpReplicationManager() {
        var emptyRegistry = ReplicaRegistry.replicaRegistry();

        return new ReplicationManager() {
            @Contract
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Contract
            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return emptyRegistry;
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                return success(unit());
            }

            /// Consistent with [#awaitReplication] above: with no replication every ack requirement is met.
            @Override
            public long replicatedThrough(String streamName, int partition, int minAcks) {
                return Long.MAX_VALUE;
            }

            @Override
            public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
                return Long.MAX_VALUE;
            }

            /// No replicas, so no acks to observe.
            @Contract
            @Override
            public void observeAcks(AckObserver observer) {}
        };
    }
}
