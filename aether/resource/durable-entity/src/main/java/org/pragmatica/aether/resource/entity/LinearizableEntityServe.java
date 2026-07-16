// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipDomain.StreamPartition;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The owner-side `LINEARIZABLE` serve pipeline for a [DurableEntity] read (#345 item 1e-b) — the
/// entity-module analog of the stream's `LinearizableOwnerServe`, built over the SAME shared ownership
/// substrate (the `StreamPartitionOwnershipValue` record family via [CommittedPartitionOwnerSource], the
/// [OwnershipEpochHighWater] fence, and the [EntityPartitionArc] key→arc mapping) but entity-shaped: it
/// serves the `Option<S>` state for a single key and rejects with [DurableEntityError] causes.
///
/// The pipeline for a key: resolve the key's `(keyspace, partition)` arc → read the arc's COMMITTED owner
/// → if a REMOTE node is the committed owner, reject [DurableEntityError.NotCurrentOwner] (no entity
/// read-forward transport exists in 1e-b — forwarding is a follow-up) → if SELF is the committed owner,
/// fast-fail on the pre-round epoch fence, run the no-op round (when a [EntityLinearizableBarrier] is
/// wired), re-check the POST-round epoch fence (the decision point, current because the round has applied
/// every ownership change committed before it), then serve the local value. A fence-free / barrier-free /
/// no-committed-record configuration degrades each stage to a pass or to the local read, so the un-wired
/// in-memory cut and existing tests keep serving their local read — which on a single owner already
/// reflects every acknowledged write. Every rejection is a typed [DurableEntityError] so the caller
/// re-resolves the owner and retries; nothing blocks the thread.
///
/// Unlike the stream pipeline, there is no replica catch-up gate — the in-memory entity has no replica
/// registry; the catch-up gate is stream-replication-specific and arrives with the entity's quorum
/// replication sub-slice (#277).
final class LinearizableEntityServe<K, S> {
    /// The key-scoped local read the pipeline serves once the owner-side guards pass — the entity's
    /// [ReadConsistency#BOUNDED_STALE] read for the key.
    @FunctionalInterface
    interface LocalReader<K, S> {
        Promise<Option<S>> read(K key);
    }

    private final NodeId selfNodeId;
    private final EntityPartitionArc arc;
    private final CommittedPartitionOwnerSource committedOwnerSource;
    private final Option<OwnershipEpochHighWater> epochHighWater;
    private final Option<EntityLinearizableBarrier> barrier;
    private final LocalReader<K, S> localReader;

    private LinearizableEntityServe(NodeId selfNodeId,
                                    EntityPartitionArc arc,
                                    CommittedPartitionOwnerSource committedOwnerSource,
                                    Option<OwnershipEpochHighWater> epochHighWater,
                                    Option<EntityLinearizableBarrier> barrier,
                                    LocalReader<K, S> localReader) {
        this.selfNodeId = selfNodeId;
        this.arc = arc;
        this.committedOwnerSource = committedOwnerSource;
        this.epochHighWater = epochHighWater;
        this.barrier = barrier;
        this.localReader = localReader;
    }

    static <K, S> LinearizableEntityServe<K, S> linearizableEntityServe(NodeId selfNodeId,
                                                                        EntityPartitionArc arc,
                                                                        CommittedPartitionOwnerSource committedOwnerSource,
                                                                        Option<OwnershipEpochHighWater> epochHighWater,
                                                                        Option<EntityLinearizableBarrier> barrier,
                                                                        LocalReader<K, S> localReader) {
        return new LinearizableEntityServe<>(selfNodeId, arc, committedOwnerSource, epochHighWater, barrier, localReader);
    }

    /// Resolve the key's committed owner and route: no committed record degrades to the local read; a
    /// remote committed owner rejects; a self committed owner runs the owner-side guards + round.
    Promise<Option<S>> serve(K key) {
        var domain = arc.arcOf(String.valueOf(key));

        return committedOwnerSource.committedOwner(domain.stream(),
                                                   domain.partition())
                                   .fold(() -> localReader.read(key),
                                         committed -> serveForCommitted(committed, key, domain));
    }

    private Promise<Option<S>> serveForCommitted(CommittedOwner committed, K key, StreamPartition domain) {
        return committed.owner()
                        .equals(selfNodeId)
               ? serveAsOwner(committed, key, domain)
               : notCurrentOwner(key, committed.owner());
    }

    /// Self IS the committed owner: fast-fail on the pre-round epoch fence (a deposed owner whose
    /// high-water has already advanced locally is rejected without a round), else run the no-op round and
    /// re-decide against the post-round fence.
    private Promise<Option<S>> serveAsOwner(CommittedOwner committed, K key, StreamPartition domain) {
        return guardOwnerEpoch(committed, key, domain).fold(Cause::promise, _ -> roundThenServe(committed, key, domain));
    }

    /// Issue the no-op round (when a barrier is wired) and only then re-decide; with no barrier the
    /// pipeline degrades to the post-round decision directly (un-wired / non-cluster behaviour).
    private Promise<Option<S>> roundThenServe(CommittedOwner committed, K key, StreamPartition domain) {
        return barrier.fold(() -> postRoundServe(committed, key, domain),
                            round -> awaitRoundThenServe(round, committed, key, domain));
    }

    private Promise<Option<S>> awaitRoundThenServe(EntityLinearizableBarrier round,
                                                   CommittedOwner committed,
                                                   K key,
                                                   StreamPartition domain) {
        return round.awaitRound(domain.stream(),
                                domain.partition())
                    .flatMap(_ -> postRoundServe(committed, key, domain));
    }

    /// The decision point: after the round has applied locally the epoch fence is current, so a deposed
    /// owner whose deposal committed during the round is rejected here.
    private Promise<Option<S>> postRoundServe(CommittedOwner committed, K key, StreamPartition domain) {
        return guardOwnerEpoch(committed, key, domain).fold(Cause::promise, _ -> localReader.read(key));
    }

    /// Owner-side epoch fence: reject when the committed `ownerEpoch` is STRICTLY older than the
    /// `(keyspace, partition)` arc high-water — self is a deposed owner whose committed record is now
    /// stale. A fence-free ([Option#none]) configuration always passes.
    private Result<Unit> guardOwnerEpoch(CommittedOwner committed, K key, StreamPartition domain) {
        return epochHighWater.fold(Result::unitResult,
                                   highWater -> rejectIfStaleEpoch(highWater, committed, key, domain));
    }

    private static Result<Unit> rejectIfStaleEpoch(OwnershipEpochHighWater highWater,
                                                   CommittedOwner committed,
                                                   Object key,
                                                   StreamPartition domain) {
        return highWater.isStale(domain, committed.ownerEpoch())
               ? staleEpochRead(key,
                                committed.ownerEpoch(),
                                highWater.highWater(domain).or(committed.ownerEpoch()))
               : Result.unitResult();
    }

    private static Result<Unit> staleEpochRead(Object key, Epoch committed, Epoch highWater) {
        return new DurableEntityError.StaleEpochRead(String.valueOf(key), epochText(committed), epochText(highWater)).result();
    }

    private static <S> Promise<Option<S>> notCurrentOwner(Object key, NodeId owner) {
        return new DurableEntityError.NotCurrentOwner(String.valueOf(key), owner.id()).promise();
    }

    private static String epochText(Epoch epoch) {
        return epoch.rabiaTerm() + ":" + epoch.localCounter();
    }
}
