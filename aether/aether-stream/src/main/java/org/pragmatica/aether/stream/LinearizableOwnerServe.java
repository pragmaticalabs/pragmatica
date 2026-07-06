// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.ForwardingReadRouter.LocalReader;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;


/// The owner-side `LINEARIZABLE` serve pipeline (#345 item 1e-a) — the ONE place the committed-owner
/// serve decision lives, shared by BOTH read entry points so they cannot diverge:
///   - the LOCAL path ({@link ForwardingReadRouter#routeToCommittedOwner}), when self IS the committed
///     owner, calls {@link #serveAsCommittedOwner}; and
///   - the FORWARDED path ({@link org.pragmatica.aether.stream.forward.StreamForwardHandler}), when a
///     `LINEARIZABLE` read was routed here as the committed owner, calls {@link #serveForwarded}.
///
/// The pipeline: a fast-fail pre-check on the owner epoch fence → the no-op consensus round (when a
/// {@link LinearizableBarrier} is wired) → the POST-round epoch fence (the decision point, current
/// because the round has applied every ownership change committed before it) → the catch-up gate →
/// the local read. A fence-free / registry-free / barrier-free configuration ([Option#none]) degrades
/// each stage to a pass, so non-cluster paths and existing tests keep serving their local read. Every
/// rejection is a typed {@link StreamError} so the client re-resolves the owner and retries; nothing
/// blocks the thread.
public final class LinearizableOwnerServe<E> {
    private final NodeId selfNodeId;
    private final Option<ReplicaRegistry> registry;
    private final Option<CommittedStreamOwnerSource> committedOwnerSource;
    private final Option<OwnershipEpochHighWater> epochHighWater;
    private final Option<LinearizableBarrier> barrier;
    private final LocalReader<E> localReader;

    private LinearizableOwnerServe(NodeId selfNodeId,
                                   Option<ReplicaRegistry> registry,
                                   Option<CommittedStreamOwnerSource> committedOwnerSource,
                                   Option<OwnershipEpochHighWater> epochHighWater,
                                   Option<LinearizableBarrier> barrier,
                                   LocalReader<E> localReader) {
        this.selfNodeId = selfNodeId;
        this.registry = registry;
        this.committedOwnerSource = committedOwnerSource;
        this.epochHighWater = epochHighWater;
        this.barrier = barrier;
        this.localReader = localReader;
    }

    public static <E> LinearizableOwnerServe<E> linearizableOwnerServe(NodeId selfNodeId,
                                                                       Option<ReplicaRegistry> registry,
                                                                       Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                                       Option<OwnershipEpochHighWater> epochHighWater,
                                                                       Option<LinearizableBarrier> barrier,
                                                                       LocalReader<E> localReader) {
        return new LinearizableOwnerServe<>(selfNodeId,
                                            registry,
                                            committedOwnerSource,
                                            epochHighWater,
                                            barrier,
                                            localReader);
    }

    /// Self IS the committed owner: fast-fail on the pre-round epoch fence (a deposed owner whose
    /// high-water has already advanced locally is rejected without a round), else run the no-op round
    /// and re-decide against the post-round fence + catch-up gate.
    public Promise<List<E>> serveAsCommittedOwner(CommittedOwner committed,
                                                  String streamName,
                                                  int partition,
                                                  long fromOffset,
                                                  int maxEvents) {
        return guardOwnerEpoch(committed, streamName, partition).fold(Cause::promise,
                                                                      _ -> roundThenServe(committed,
                                                                                          streamName,
                                                                                          partition,
                                                                                          fromOffset,
                                                                                          maxEvents));
    }

    /// The forwarded-to node re-runs the SAME owner-side pipeline the local path uses: it resolves its
    /// OWN committed-owner view and, if it is the committed owner, serves through
    /// {@link #serveAsCommittedOwner} (fence + round + catch-up); if a routing race has moved ownership
    /// off this node it rejects {@link StreamError.NotCurrentOwner} so the client re-resolves. With no
    /// committed record it degrades to the local read (the sender would not have forwarded a
    /// linearizable read here without one, but this keeps legacy / unowned partitions serving).
    public Promise<List<E>> serveForwarded(String streamName, int partition, long fromOffset, int maxEvents) {
        return committedOwnerSource.flatMap(source -> source.committedOwner(streamName, partition))
                                   .fold(() -> localReader.read(streamName, partition, fromOffset, maxEvents),
                                         committed -> serveForwardedTo(committed,
                                                                       streamName,
                                                                       partition,
                                                                       fromOffset,
                                                                       maxEvents));
    }

    private Promise<List<E>> serveForwardedTo(CommittedOwner committed,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents) {
        return committed.owner()
                        .equals(selfNodeId)
               ? serveAsCommittedOwner(committed, streamName, partition, fromOffset, maxEvents)
               : new StreamError.NotCurrentOwner(streamName, partition, committed.owner(), selfNodeId).promise();
    }

    /// Issue the no-op round (when a barrier is wired) and only then re-decide; with no barrier the
    /// pipeline degrades to the post-round decision directly (existing / non-cluster behaviour).
    private Promise<List<E>> roundThenServe(CommittedOwner committed,
                                            String streamName,
                                            int partition,
                                            long fromOffset,
                                            int maxEvents) {
        return barrier.fold(() -> postRoundServe(committed, streamName, partition, fromOffset, maxEvents),
                            round -> round.awaitRound(streamName, partition)
                                          .flatMap(_ -> postRoundServe(committed,
                                                                       streamName,
                                                                       partition,
                                                                       fromOffset,
                                                                       maxEvents)));
    }

    /// The decision point: after the round has applied locally the epoch fence is current, so a
    /// deposed owner whose deposal committed during the round is rejected here.
    private Promise<List<E>> postRoundServe(CommittedOwner committed,
                                            String streamName,
                                            int partition,
                                            long fromOffset,
                                            int maxEvents) {
        return guardOwnerEpoch(committed, streamName, partition).fold(Cause::promise,
                                                                      _ -> serveIfCaughtUp(streamName,
                                                                                           partition,
                                                                                           fromOffset,
                                                                                           maxEvents));
    }

    /// Owner-side epoch fence: reject when the committed `ownerEpoch` is STRICTLY older than the
    /// `(stream, partition)` domain high-water — self is a deposed owner whose committed record is now
    /// stale. Fence-free routers ([Option#none]) always pass.
    private Result<Unit> guardOwnerEpoch(CommittedOwner committed, String streamName, int partition) {
        return epochHighWater.fold(Result::unitResult,
                                   highWater -> rejectIfStaleEpoch(highWater, committed, streamName, partition));
    }

    private static Result<Unit> rejectIfStaleEpoch(OwnershipEpochHighWater highWater,
                                                   CommittedOwner committed,
                                                   String streamName,
                                                   int partition) {
        var domain = OwnershipDomain.streamPartition(streamName, partition);

        return highWater.isStale(domain, committed.ownerEpoch())
               ? new StreamError.StaleEpochRead(streamName,
                                                partition,
                                                committed.ownerEpoch(),
                                                highWater.highWater(domain).or(committed.ownerEpoch())).result()
               : Result.unitResult();
    }

    /// Catch-up gate: a freshly-promoted owner registered but not yet CAUGHT_UP for the partition is
    /// held back with {@link StreamError.OwnerCatchupPending} until it has applied up to the handover;
    /// an owner with no self replica entry is its own authority and serves.
    private Promise<List<E>> serveIfCaughtUp(String streamName, int partition, long fromOffset, int maxEvents) {
        return registry.fold(() -> localReader.read(streamName, partition, fromOffset, maxEvents),
                             reg -> serveWhenSelfCovers(reg, streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> serveWhenSelfCovers(ReplicaRegistry reg,
                                                 String streamName,
                                                 int partition,
                                                 long fromOffset,
                                                 int maxEvents) {
        return selfIsLaggingReplica(reg, streamName, partition)
               ? new StreamError.OwnerCatchupPending(streamName, partition).promise()
               : localReader.read(streamName, partition, fromOffset, maxEvents);
    }

    private boolean selfIsLaggingReplica(ReplicaRegistry reg, String streamName, int partition) {
        return reg.replicasFor(streamName, partition)
                  .stream()
                  .filter(r -> r.nodeId()
                                .equals(selfNodeId))
                  .anyMatch(r -> !isCaughtUp(r));
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }
}
