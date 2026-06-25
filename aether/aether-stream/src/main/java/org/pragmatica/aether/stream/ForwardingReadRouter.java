// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
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
import java.util.concurrent.ThreadLocalRandom;


/// The single forward-read routing core shared by every replicated-stream read path. Holds the
/// caught-up-replica selection + owner-forward fallback ALGORITHM exactly once; each caller supplies
/// only its type-specific local-read and decode closures, so the typed-`StreamEvent` reader
/// ({@link PartitionedStreamAccess}) and the raw-event reader ({@link StreamReadRouter}) cannot drift.
///
/// Routing precedence ({@link #route}):
///   1. `GOVERNOR` reads local; `ANY_REPLICA`/`NEAREST` enter replica selection (no registry → local).
///   2. `LINEARIZABLE` routes strictly to the COMMITTED owner (#345 item 1e): the read goes to
///      `StreamPartitionOwnershipValue.owner` (the fenced owner), NOT the on-the-fly HRW owner, so it
///      observes the authoritative log even when a reshuffle has made the two diverge. When self IS the
///      committed owner the read is served locally only after the owner-side guards pass (still committed
///      owner, epoch not stale, caught up to the handover); when a remote node is the committed owner the
///      read is forwarded there via the existing read-forward transport. With no committed record
///      (legacy / unowned partitions) it falls back to the replica-routed read so nothing breaks.
///   3. A REMOTE replica the LOCAL registry marks CAUGHT_UP exists → forward via {@link #attemptPrimary}
///      (random pick + single retry on a distinct replica).
///   4. Self is CAUGHT_UP for this partition (local has full coverage) → read local.
///   5. Self lagging, no locally-known caught-up peer → forward to the deterministic HRW owner (every
///      node computes the same owner; the owner self-promotes to CAUGHT_UP first at cold-start), failing
///      soft to a local read when the owner is unknown/self or no forward client is wired.
public final class ForwardingReadRouter<E> {
    private static final System.Logger LOG = System.getLogger(ForwardingReadRouter.class.getName());

    /// Type-specific local partition read (e.g. `readPartition` / `readLocal`).
    @FunctionalInterface
    public interface LocalReader<E> {
        Promise<List<E>> read(String streamName, int partition, long fromOffset, int maxEvents);
    }

    /// Decode forwarded {@link RawEventDto}s into the caller's event type (e.g. `decodeAll` /
    /// `toRawEvents`). Decoding cannot fail — a malformed remote payload is a transport-layer concern
    /// surfaced earlier — so this returns a plain list, matching the existing decoders.
    @FunctionalInterface
    public interface RemoteDecoder<E> {
        List<E> decode(List<RawEventDto> events, int partition);
    }

    /// Resolve the deterministic owner of `(streamName, partition)` — the HRW owner the publish path
    /// routes to. {@link Option#none()} during the bootstrap window (placement not yet known).
    @FunctionalInterface
    public interface OwnerResolver {
        Option<NodeId> resolve(String streamName, int partition);
    }

    private final Option<ReplicaRegistry> registry;
    private final NodeId selfNodeId;
    private final Option<StreamForwardClient> forwardClient;
    private final ReadPreference readPreference;
    private final OwnerResolver ownerResolver;
    private final LocalReader<E> localReader;
    private final RemoteDecoder<E> remoteDecoder;
    private final StreamReadForwardMetrics metrics;
    private final Option<CommittedStreamOwnerSource> committedOwnerSource;
    private final Option<OwnershipEpochHighWater> epochHighWater;

    private ForwardingReadRouter(Option<ReplicaRegistry> registry,
                                 NodeId selfNodeId,
                                 Option<StreamForwardClient> forwardClient,
                                 ReadPreference readPreference,
                                 OwnerResolver ownerResolver,
                                 LocalReader<E> localReader,
                                 RemoteDecoder<E> remoteDecoder,
                                 StreamReadForwardMetrics metrics,
                                 Option<CommittedStreamOwnerSource> committedOwnerSource,
                                 Option<OwnershipEpochHighWater> epochHighWater) {
        this.registry = registry;
        this.selfNodeId = selfNodeId;
        this.forwardClient = forwardClient;
        this.readPreference = readPreference;
        this.ownerResolver = ownerResolver;
        this.localReader = localReader;
        this.remoteDecoder = remoteDecoder;
        this.metrics = metrics;
        this.committedOwnerSource = committedOwnerSource;
        this.epochHighWater = epochHighWater;
    }

    public static <E> ForwardingReadRouter<E> forwardingReadRouter(Option<ReplicaRegistry> registry,
                                                                   NodeId selfNodeId,
                                                                   Option<StreamForwardClient> forwardClient,
                                                                   ReadPreference readPreference,
                                                                   OwnerResolver ownerResolver,
                                                                   LocalReader<E> localReader,
                                                                   RemoteDecoder<E> remoteDecoder,
                                                                   StreamReadForwardMetrics metrics) {
        return new ForwardingReadRouter<>(registry,
                                          selfNodeId,
                                          forwardClient,
                                          readPreference,
                                          ownerResolver,
                                          localReader,
                                          remoteDecoder,
                                          metrics,
                                          Option.none(),
                                          Option.none());
    }

    /// #345 item 1e overload: wires the COMMITTED-owner source + the ownership epoch high-water so the
    /// `LINEARIZABLE` arm can route to the fenced owner and run the owner-side guards. The non-linearizable
    /// arms ignore both and stay byte-for-byte identical to the base overload.
    public static <E> ForwardingReadRouter<E> forwardingReadRouter(Option<ReplicaRegistry> registry,
                                                                   NodeId selfNodeId,
                                                                   Option<StreamForwardClient> forwardClient,
                                                                   ReadPreference readPreference,
                                                                   OwnerResolver ownerResolver,
                                                                   LocalReader<E> localReader,
                                                                   RemoteDecoder<E> remoteDecoder,
                                                                   StreamReadForwardMetrics metrics,
                                                                   Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                                   Option<OwnershipEpochHighWater> epochHighWater) {
        return new ForwardingReadRouter<>(registry,
                                          selfNodeId,
                                          forwardClient,
                                          readPreference,
                                          ownerResolver,
                                          localReader,
                                          remoteDecoder,
                                          metrics,
                                          committedOwnerSource,
                                          epochHighWater);
    }

    public Promise<List<E>> route(String streamName, int partition, long fromOffset, int maxEvents) {
        return switch (readPreference) {
            case GOVERNOR -> localReader.read(streamName, partition, fromOffset, maxEvents);
            case ANY_REPLICA, NEAREST -> readFromReplicaOrLocal(streamName, partition, fromOffset, maxEvents);
            case LINEARIZABLE -> readLinearizable(streamName, partition, fromOffset, maxEvents);
        };
    }

    /// #345 item 1e: route a `LINEARIZABLE` read to the COMMITTED owner of `(streamName, partition)`. The
    /// committed owner is the fenced `StreamPartitionOwnershipValue.owner`, which during a reshuffle
    /// diverges from the on-the-fly HRW owner the other arms forward to — routing here is what makes the
    /// read linearizable. With no committed-owner source wired or no committed record yet (legacy /
    /// unowned partition) the read degrades to the replica-routed behaviour so nothing breaks.
    private Promise<List<E>> readLinearizable(String streamName, int partition, long fromOffset, int maxEvents) {
        return committedOwnerSource.flatMap(source -> source.committedOwner(streamName, partition))
                                   .map(committed -> routeToCommittedOwner(committed, streamName, partition, fromOffset, maxEvents))
                                   .or(() -> readFromReplicaOrLocal(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> routeToCommittedOwner(CommittedOwner committed,
                                                   String streamName,
                                                   int partition,
                                                   long fromOffset,
                                                   int maxEvents) {
        return committed.owner().equals(selfNodeId)
               ? serveAsCommittedOwner(committed, streamName, partition, fromOffset, maxEvents)
               : forwardReadToCommittedOwner(committed.owner(), streamName, partition, fromOffset, maxEvents);
    }

    /// Self IS the committed owner: serve locally only after the three owner-side guards pass — self is
    /// still the committed owner, the committed epoch is not stale relative to the partition high-water,
    /// and self has caught up to the handover (reusing the EXISTING CAUGHT_UP signal). Any guard failure
    /// rejects the read with a typed cause so the client re-resolves and retries; none blocks the thread.
    private Promise<List<E>> serveAsCommittedOwner(CommittedOwner committed,
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

    /// Owner-side epoch fence (the read-side analogue of the write-side `StaleEpochAppend` check): reject
    /// when the committed `ownerEpoch` is STRICTLY older than the `(stream, partition)` domain high-water —
    /// self is a deposed owner whose committed record is now stale. Fence-free routers ([Option#none])
    /// always pass.
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

    /// Catch-up gate: a freshly-promoted owner must have applied up to the handover before serving. Gate
    /// on the EXISTING signal — a self replica entry that is registered but NOT yet CAUGHT_UP for the
    /// partition (the SYNCING state the failover-recovery / backfill path clears on reaching the owner's
    /// retained history). An owner with no self replica entry at all (a single owner / not-yet-reconciled
    /// established owner — there is no prior owner to catch up to) is its own authority and serves, exactly
    /// as when no registry is wired. A registered-but-lagging new owner is rejected with
    /// `OwnerCatchupPending` so the client retries once it has caught up; the read is never blocked.
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

    /// True iff self IS a registered replica for the partition AND has not yet reached CAUGHT_UP — the
    /// fresh-takeover-still-syncing case the catch-up gate must hold back. An owner with no self replica
    /// entry is not "lagging"; it serves.
    private boolean selfIsLaggingReplica(ReplicaRegistry reg, String streamName, int partition) {
        return reg.replicasFor(streamName, partition)
                  .stream()
                  .filter(r -> r.nodeId().equals(selfNodeId))
                  .anyMatch(r -> !isCaughtUp(r));
    }

    private Promise<List<E>> forwardReadToCommittedOwner(NodeId owner,
                                                         String streamName,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        return forwardClient.map(client -> forwardReadStrict(client, owner, streamName, partition, fromOffset, maxEvents))
                            .or(() -> new StreamError.NotCurrentOwner(streamName, partition, owner, selfNodeId).promise());
    }

    /// LINEARIZABLE forward to the committed owner — UNLIKE {@link #forwardReadToNode}, a remote failure
    /// is PROPAGATED, never softened to a local read on this non-owner node: a linearizable read may only
    /// be served by the committed owner, so on a forward failure the client must retry rather than read a
    /// stale local copy.
    private Promise<List<E>> forwardReadStrict(StreamForwardClient client,
                                               NodeId owner,
                                               String streamName,
                                               int partition,
                                               long fromOffset,
                                               int maxEvents) {
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: linearizable owner-forward to {1} for {2}[{3}]",
                readPreference,
                owner,
                streamName,
                partition);

        return client.readRemote(owner, streamName, partition, fromOffset, maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(), partition));
    }

    private Promise<List<E>> readFromReplicaOrLocal(String streamName, int partition, long fromOffset, int maxEvents) {
        return registry.map(reg -> selectAndRead(reg, streamName, partition, fromOffset, maxEvents))
                       .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> selectAndRead(ReplicaRegistry reg,
                                           String streamName,
                                           int partition,
                                           long fromOffset,
                                           int maxEvents) {
        var caughtUpRemotes = reg.replicasFor(streamName, partition).stream().filter(ForwardingReadRouter::isCaughtUp).filter(r -> !r.nodeId()
                                                                                                                                     .equals(selfNodeId)).toList();

        if (!caughtUpRemotes.isEmpty()) {
            return forwardClient.map(client -> attemptPrimary(client,
                                                              caughtUpRemotes,
                                                              streamName,
                                                              partition,
                                                              fromOffset,
                                                              maxEvents))
                                .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
        }

        if (selfCoversPartition(reg, streamName, partition)) {
            return localReader.read(streamName, partition, fromOffset, maxEvents);
        }

        return forwardToOwner(streamName, partition, fromOffset, maxEvents);
    }

    private boolean selfCoversPartition(ReplicaRegistry reg, String streamName, int partition) {
        return reg.replicasFor(streamName, partition)
                  .stream()
                  .filter(r -> r.nodeId()
                                .equals(selfNodeId))
                  .anyMatch(ForwardingReadRouter::isCaughtUp);
    }

    private Promise<List<E>> forwardToOwner(String streamName, int partition, long fromOffset, int maxEvents) {
        return ownerResolver.resolve(streamName, partition)
                            .filter(owner -> !owner.equals(selfNodeId))
                            .flatMap(owner -> forwardClient.map(client -> forwardReadToNode(client,
                                                                                            owner,
                                                                                            streamName,
                                                                                            partition,
                                                                                            fromOffset,
                                                                                            maxEvents)))
                            .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> forwardReadToNode(StreamForwardClient client,
                                               NodeId owner,
                                               String streamName,
                                               int partition,
                                               long fromOffset,
                                               int maxEvents) {
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: owner-forward to {1} for {2}[{3}]",
                readPreference,
                owner,
                streamName,
                partition);

        return client.readRemote(owner, streamName, partition, fromOffset, maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(),
                                                         partition))
                     .fold(result -> result.fold(_ -> localReader.read(streamName, partition, fromOffset, maxEvents),
                                                 Promise::success));
    }

    private Promise<List<E>> attemptPrimary(StreamForwardClient client,
                                            List<ReplicaDescriptor> pool,
                                            String streamName,
                                            int partition,
                                            long fromOffset,
                                            int maxEvents) {
        var primary = pickReplica(pool);

        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: primary replica {1} for {2}[{3}]",
                readPreference,
                primary.nodeId(),
                streamName,
                partition);

        return client.readRemote(primary.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents).map(result -> remoteDecoder.decode(result.events(),
                                                                               partition))
                                .fold(result -> result.fold(cause -> retryOrFail(client,
                                                                                 primary,
                                                                                 pool,
                                                                                 streamName,
                                                                                 partition,
                                                                                 fromOffset,
                                                                                 maxEvents,
                                                                                 cause),
                                                            Promise::success));
    }

    private Promise<List<E>> retryOrFail(StreamForwardClient client,
                                         ReplicaDescriptor primary,
                                         List<ReplicaDescriptor> pool,
                                         String streamName,
                                         int partition,
                                         long fromOffset,
                                         int maxEvents,
                                         Cause firstCause) {
        var alternatives = pool.stream().filter(r -> !r.nodeId()
                                                       .equals(primary.nodeId())).toList();

        if (alternatives.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "ReadPreference {0}: single-replica failure for {1}[{2}]: {3}",
                    readPreference,
                    streamName,
                    partition,
                    firstCause.message());

            return firstCause.promise();
        }

        metrics.recordRetry();
        var retry = pickReplica(alternatives);

        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: retry replica {1} for {2}[{3}] after primary {4} failed: {5}",
                readPreference,
                retry.nodeId(),
                streamName,
                partition,
                primary.nodeId(),
                firstCause.message());

        return client.readRemote(retry.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(),
                                                         partition));
    }

    private static ReplicaDescriptor pickReplica(List<ReplicaDescriptor> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }
}
